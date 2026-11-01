"""Train the Senyas FSL sign classifier and export it for the Android app.

    python train.py --data data/fsl105/train data/phone --test-data data/fsl105/test --out out --install

Reads landmark samples (from extract_landmarks.py and/or the app's Record mode), trains a small
1-D CNN, reports accuracy against the proposal's 85% target, and writes:
    out/fsl_model.tflite   the model (LiteRT / TensorFlow Lite, built-in ops only)
    out/fsl_labels.txt     one sign label per line, in model output order
    out/fsl_model.json     feature version, sequence length, accuracy, sample counts
    out/report.txt         per-sign accuracy and the most confused pairs
--install copies the first three into android/app/src/main/assets, where the app picks them up.
"""
import argparse
import json
import shutil
import sys
import tempfile
import time
from collections import Counter
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from senyas_ml import features  # noqa: E402
from senyas_ml.augment import augment  # noqa: E402
from senyas_ml.samples import iter_samples  # noqa: E402

TARGET_ACCURACY = 0.85  # proposal, section 3.2
APP_ASSETS = HERE.parents[1] / "android" / "app" / "src" / "main" / "assets"


def load(dirs):
    xs, ys, sources = [], [], Counter()
    skipped = 0
    for s in iter_samples(*dirs):
        if s.get("featureVersion", features.VERSION) != features.VERSION:
            print(f"  skipping {s['_path']}: made for feature version {s.get('featureVersion')}")
            skipped += 1
            continue
        seq = features.encode_sequence(s["frames"])
        if seq is None:
            skipped += 1
            continue
        xs.append(seq)
        ys.append(s["label"])
        sources[str(s.get("source", "")).split(":")[0] or "unknown"] += 1
    if skipped:
        print(f"  {skipped} sample(s) had too few frames with hands and were skipped")
    x = np.stack(xs).astype(np.float32) if xs else np.zeros((0, features.SEQUENCE_LENGTH, features.FEATURES), np.float32)
    return x, np.array(ys), sources


def stratified_split(y, fraction, rng):
    """Index arrays (keep, held_out) with about `fraction` of every label held out."""
    keep, held = [], []
    for label in np.unique(y):
        idx = np.flatnonzero(y == label)
        rng.shuffle(idx)
        n = int(round(len(idx) * fraction))
        if len(idx) >= 3:
            n = max(1, n)
        held.extend(idx[:n])
        keep.extend(idx[n:])
    return np.array(keep, dtype=int), np.array(held, dtype=int)


def make_batches(x, y_onehot, batch, rng):
    import keras

    class Augmented(keras.utils.PyDataset):
        """Fresh random variations of every training sign, every epoch."""

        def __init__(self):
            super().__init__()
            self.order = np.arange(len(x))

        def __len__(self):
            return int(np.ceil(len(x) / batch))

        def __getitem__(self, i):
            idx = self.order[i * batch:(i + 1) * batch]
            xb = np.stack([augment(x[j], rng) for j in idx])
            return xb, y_onehot[idx]

        def on_epoch_end(self):
            rng.shuffle(self.order)

    return Augmented()


def export_tflite(model, out_path):
    import tensorflow as tf

    with tempfile.TemporaryDirectory() as tmp:
        saved = Path(tmp) / "saved_model"
        model.export(str(saved), format="tf_saved_model", verbose=False)
        converter = tf.lite.TFLiteConverter.from_saved_model(str(saved))
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
        data = converter.convert()
    Path(out_path).write_bytes(data)
    return data


def tflite_predict(model_bytes, x):
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:
        import tensorflow as tf
        Interpreter = tf.lite.Interpreter
    interp = Interpreter(model_content=model_bytes)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    preds = []
    for seq in x:
        interp.set_tensor(inp["index"], seq[None].astype(np.float32))
        interp.invoke()
        preds.append(interp.get_tensor(out["index"])[0])
    return np.array(preds)


def report(y_true, probs, labels):
    pred = probs.argmax(1)
    acc = float((pred == y_true).mean()) if len(y_true) else 0.0
    top3 = float(np.mean([t in np.argsort(-p)[:3] for t, p in zip(y_true, probs)])) if len(y_true) else 0.0
    lines = [f"Test accuracy: {acc:.1%}   top-3: {top3:.1%}   ({len(y_true)} signs)",
             f"Proposal target {TARGET_ACCURACY:.0%}: {'MET' if acc >= TARGET_ACCURACY else 'NOT MET yet'}", "",
             "Weakest signs:"]
    per = []
    for k, name in enumerate(labels):
        m = y_true == k
        if m.any():
            per.append((float((pred[m] == k).mean()), name, int(m.sum())))
    for a, name, n in sorted(per)[:15]:
        lines.append(f"  {a:6.1%}  {name}  ({n} test samples)")
    confused = Counter((labels[t], labels[p]) for t, p in zip(y_true, pred) if t != p)
    if confused:
        lines += ["", "Most confused (true -> predicted):"]
        lines += [f"  {n}x  {a} -> {b}" for (a, b), n in confused.most_common(15)]
    return acc, top3, "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", nargs="+", required=True, help="folders of training samples")
    ap.add_argument("--test-data", nargs="*", help="separate test folders (otherwise 15%% is held out)")
    ap.add_argument("--out", default=str(HERE / "out"))
    ap.add_argument("--epochs", type=int, default=120)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--min-samples", type=int, default=3, help="drop signs with fewer samples than this")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--install", action="store_true", help="copy the model into the app's assets")
    args = ap.parse_args(argv)

    import keras
    from senyas_ml.model import build_model

    rng = np.random.default_rng(args.seed)
    keras.utils.set_random_seed(args.seed)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    print("Loading samples ...")
    x, y, sources = load(args.data)
    counts = Counter(y.tolist())
    keepers = sorted(k for k, n in counts.items() if n >= args.min_samples)
    dropped = sorted(set(counts) - set(keepers))
    if dropped:
        print(f"  dropping {len(dropped)} sign(s) with fewer than {args.min_samples} samples: {dropped[:10]}")
    mask = np.isin(y, keepers)
    x, y = x[mask], y[mask]
    if len(keepers) < 2:
        raise SystemExit("Need at least 2 signs with enough samples to train.")
    labels = keepers
    index = {name: i for i, name in enumerate(labels)}
    yi = np.array([index[v] for v in y])
    print(f"  {len(x)} samples, {len(labels)} signs, sources {dict(sources)}")

    if args.test_data:
        xt, yt_names, _ = load(args.test_data)
        m = np.isin(yt_names, labels)
        x_test, y_test = xt[m], np.array([index[v] for v in yt_names[m]])
        train_idx, val_idx = stratified_split(yi, 0.15, rng)
    else:
        rest, test_idx = stratified_split(yi, 0.15, rng)
        x_test, y_test = x[test_idx], yi[test_idx]
        sub_train, sub_val = stratified_split(yi[rest], 0.15 / 0.85, rng)
        train_idx, val_idx = rest[sub_train], rest[sub_val]
    x_train, y_train = x[train_idx], yi[train_idx]
    x_val, y_val = x[val_idx], yi[val_idx]
    print(f"  train {len(x_train)}, validate {len(x_val)}, test {len(x_test)}")

    n = len(labels)
    model = build_model(n)
    model.compile(optimizer=keras.optimizers.Adam(1e-3),
                  loss=keras.losses.CategoricalCrossentropy(label_smoothing=0.05),
                  metrics=["accuracy"])
    onehot = np.eye(n, dtype=np.float32)
    batches = make_batches(x_train, onehot[y_train], args.batch, rng)
    callbacks = [
        keras.callbacks.EarlyStopping(monitor="val_accuracy", patience=25, restore_best_weights=True),
        keras.callbacks.ReduceLROnPlateau(monitor="val_loss", factor=0.5, patience=10, min_lr=1e-5),
    ]
    start = time.time()
    model.fit(batches, validation_data=(x_val, onehot[y_val]), epochs=args.epochs, callbacks=callbacks, verbose=2)
    print(f"Trained in {time.time() - start:.0f}s")

    keras_probs = model.predict(x_test, verbose=0) if len(x_test) else np.zeros((0, n))
    print("Exporting TensorFlow Lite model ...")
    tflite_path = out / "fsl_model.tflite"
    data = export_tflite(model, tflite_path)
    lite_probs = tflite_predict(data, x_test) if len(x_test) else keras_probs
    if len(x_test):
        agree = float((lite_probs.argmax(1) == keras_probs.argmax(1)).mean())
        print(f"  TFLite agrees with Keras on {agree:.1%} of test signs (max diff {np.abs(lite_probs - keras_probs).max():.4f})")

    acc, top3, text = report(y_test, lite_probs, labels)
    print(text)
    (out / "report.txt").write_text(text + "\n", encoding="utf-8")
    (out / "fsl_labels.txt").write_text("\n".join(labels) + "\n", encoding="utf-8")
    info = {
        "featureVersion": features.VERSION,
        "sequenceLength": features.SEQUENCE_LENGTH,
        "features": features.FEATURES,
        "labels": n,
        "testAccuracy": round(acc, 4),
        "testTop3": round(top3, 4),
        "testSamples": int(len(y_test)),
        "trainSamples": int(len(x_train)),
        "sources": dict(sources),
        "trainedAt": time.strftime("%Y-%m-%d %H:%M"),
        "sizeBytes": len(data),
    }
    (out / "fsl_model.json").write_text(json.dumps(info, indent=2), encoding="utf-8")
    print(f"Wrote {tflite_path} ({len(data) / 1024:.0f} KB), fsl_labels.txt, fsl_model.json, report.txt")

    if args.install:
        APP_ASSETS.mkdir(parents=True, exist_ok=True)
        for name in ("fsl_model.tflite", "fsl_labels.txt", "fsl_model.json"):
            shutil.copy2(out / name, APP_ASSETS / name)
        print(f"Installed into {APP_ASSETS}. Rebuild the app to use it.")
    return acc


if __name__ == "__main__":
    main()
