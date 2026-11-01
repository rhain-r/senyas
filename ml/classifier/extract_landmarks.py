"""Turn FSL sign videos into landmark samples (the same JSON the Senyas app records).

Two ways to point it at videos:

1. One folder per sign:
       videos/salamat/clip01.mp4, videos/salamat/clip02.mp4, videos/magkano/...
   python extract_landmarks.py --videos path/to/videos --out data/samples

2. A CSV that lists the clips, such as FSL-105's train.csv and test.csv:
   python extract_landmarks.py --csv FSL-105/train.csv --root FSL-105 --out data/fsl105/train --labels FSL-105/labels.csv
   The clip-path and label columns are found automatically (override with --path-col/--label-col).
   --labels turns numeric label ids into sign names.

MediaPipe runs on the CPU; --workers uses several cores and --every 2 reads every second frame
(about twice as fast, and still plenty for 30 fps video). Finished clips are skipped, so an
interrupted run can simply be started again.
"""
import argparse
import csv
import multiprocessing as mp_pool
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from senyas_ml.samples import normalize_label, save_sample  # noqa: E402

MODEL_URL = ("https://storage.googleapis.com/mediapipe-models/hand_landmarker/"
             "hand_landmarker/float16/latest/hand_landmarker.task")
VIDEO_EXT = {".mp4", ".mov", ".avi", ".mkv", ".webm", ".m4v"}


def ensure_model(path):
    path = Path(path)
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        print(f"Downloading the MediaPipe hand model to {path} ...")
        urllib.request.urlretrieve(MODEL_URL, path)
    return path


def extract_video(video_path, model_path, every=1):
    """Runs MediaPipe Hand Landmarker (video mode, up to 2 hands) on every n-th frame."""
    import cv2
    import mediapipe as mp
    from mediapipe.tasks import python as mp_tasks
    from mediapipe.tasks.python import vision

    options = vision.HandLandmarkerOptions(
        base_options=mp_tasks.BaseOptions(model_asset_path=str(model_path)),
        running_mode=vision.RunningMode.VIDEO,
        num_hands=2,
        min_hand_detection_confidence=0.5,
        min_hand_presence_confidence=0.5,
        min_tracking_confidence=0.5,
    )
    cap = cv2.VideoCapture(str(video_path))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    frames = []
    index = 0
    with vision.HandLandmarker.create_from_options(options) as landmarker:
        while True:
            ok, bgr = cap.read()
            if not ok:
                break
            if index % every == 0:
                t = int(round(index * 1000.0 / fps))
                rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
                image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
                result = landmarker.detect_for_video(image, t)
                hands = []
                for points, handedness in zip(result.hand_landmarks, result.handedness):
                    best = handedness[0]
                    hands.append({
                        "label": best.category_name,
                        "score": round(float(best.score), 4),
                        "lm": [round(float(v), 6) for p in points for v in (p.x, p.y, p.z)],
                    })
                frames.append({"t": t, "hands": hands})
            index += 1
    cap.release()
    return frames


def _job(args):
    video, label, out_file, model_path, every, source = args
    if Path(out_file).exists():
        return "skip", video
    try:
        frames = extract_video(video, model_path, every)
    except Exception as e:  # keep going; report at the end
        return f"error: {e}", video
    if not any(f["hands"] for f in frames):
        return "no hands", video
    save_sample(out_file, label, source, frames)
    return "ok", video


def jobs_from_folders(videos_root, out_root, model_path, every):
    videos_root = Path(videos_root)
    for video in sorted(videos_root.rglob("*")):
        if video.suffix.lower() not in VIDEO_EXT:
            continue
        label = normalize_label(video.parent.name)
        out = Path(out_root) / label / (video.stem + ".json")
        yield str(video), label, str(out), str(model_path), every, f"video:{video.relative_to(videos_root).as_posix()}"


def _read_csv(path):
    with open(path, newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


def _detect_columns(rows, path_col, label_col):
    cols = list(rows[0].keys())
    if not path_col:
        path_col = next((c for c in cols if Path(str(rows[0][c])).suffix.lower() in VIDEO_EXT), None)
        if not path_col:
            path_col = next((c for c in cols if any(k in c.lower() for k in ("path", "file", "vid", "clip"))), None)
    if not label_col:
        others = [c for c in cols if c != path_col]
        label_col = next((c for c in others if any(k in c.lower() for k in ("label", "class", "sign", "gloss", "id"))), None)
        if not label_col and others:
            label_col = others[0]
    if not path_col or not label_col:
        raise SystemExit(f"Could not tell the path and label columns apart in {cols}. Use --path-col and --label-col.")
    return path_col, label_col


def _label_names(labels_csv):
    """Maps label ids to sign names (FSL-105's labels.csv also has a category column, skipped)."""
    rows = _read_csv(labels_csv)
    cols = list(rows[0].keys())
    id_col = next((c for c in cols if all(str(r[c]).strip().isdigit() for r in rows)), cols[0])
    others = [c for c in cols if c != id_col]
    name_col = next((c for c in others if any(k in c.lower() for k in ("label", "sign", "name", "gloss", "word"))),
                    next((c for c in others if "categ" not in c.lower()), others[0]))
    print(f"Label names: id = {id_col!r}, name = {name_col!r}")
    return {str(r[id_col]).strip(): r[name_col] for r in rows}


def jobs_from_csv(csv_path, root, out_root, model_path, every, path_col, label_col, labels_csv):
    rows = _read_csv(csv_path)
    if not rows:
        return
    path_col, label_col = _detect_columns(rows, path_col, label_col)
    names = _label_names(labels_csv) if labels_csv else {}
    root = Path(root)
    by_name = None
    print(f"Using columns: path = {path_col!r}, label = {label_col!r}")
    for r in rows:
        rel = str(r[path_col]).strip()
        video = root / rel
        if not video.exists():
            if by_name is None:  # build once: clip file name -> full path
                by_name = {p.name: p for p in root.rglob("*") if p.suffix.lower() in VIDEO_EXT}
            video = by_name.get(Path(rel).name, video)
        raw_label = str(r[label_col]).strip()
        label = normalize_label(names.get(raw_label, raw_label))
        out = Path(out_root) / label / (Path(rel).stem + ".json")
        yield str(video), label, str(out), str(model_path), every, f"video:{rel}"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--videos", help="folder with one sub-folder of videos per sign")
    src.add_argument("--csv", help="CSV listing clips (e.g. FSL-105 train.csv)")
    ap.add_argument("--root", default=".", help="folder the CSV paths are relative to")
    ap.add_argument("--labels", help="CSV mapping numeric label ids to sign names")
    ap.add_argument("--path-col")
    ap.add_argument("--label-col")
    ap.add_argument("--out", required=True, help="where to write the JSON samples")
    ap.add_argument("--model", default=str(HERE / "models" / "hand_landmarker.task"))
    ap.add_argument("--every", type=int, default=1, help="use every n-th frame (2 = twice as fast)")
    ap.add_argument("--workers", type=int, default=max(1, mp_pool.cpu_count() - 1))
    args = ap.parse_args(argv)

    model_path = ensure_model(args.model)
    if args.videos:
        jobs = list(jobs_from_folders(args.videos, args.out, model_path, args.every))
    else:
        jobs = list(jobs_from_csv(args.csv, args.root, args.out, model_path, args.every,
                                  args.path_col, args.label_col, args.labels))
    if not jobs:
        raise SystemExit("No videos found.")
    print(f"{len(jobs)} clips, {args.workers} worker(s)")

    start = time.time()
    counts = {}
    problems = []
    with mp_pool.Pool(args.workers) as pool:
        for i, (status, video) in enumerate(pool.imap_unordered(_job, jobs), 1):
            key = status.split(":")[0]
            counts[key] = counts.get(key, 0) + 1
            if key not in ("ok", "skip"):
                problems.append(f"{status}  {video}")
            if i % 25 == 0 or i == len(jobs):
                print(f"  {i}/{len(jobs)}  {counts}  {time.time() - start:.0f}s", flush=True)
    for p in problems[:20]:
        print("  ", p)
    if len(problems) > 20:
        print(f"   ... and {len(problems) - 20} more")
    print(f"Done: samples are in {args.out}")


if __name__ == "__main__":
    main()
