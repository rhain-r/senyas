"""Step 6: writes the parity fixtures for the app's Java scorer test (SignScorerParityTest).

The Python design scores fixed inputs against the same bundled references the app ships.
The Java test must then reproduce the tolerances, scores, sub-scores and correction tips.
Inputs per sign: the first unbundled clip of signer A and of signer D. For kumusta, salamat, oo and
tatay also a held pose, the clip played backwards and the clip at half frame rate. For a few signs
also a look-alike sign's clip. Each sign is scored with its own passK, as the app does.
Frames are rounded to 5 decimals so that Java reads exactly the same numbers.

The app's fixture also has two salamat cases from the developer's own webcam recordings.
They are private and not part of this repository; --extra-salamat DIR adds such recordings.

usage: python scripts/06_parity_fixtures.py [--refs DIR] [--landmarks DIR] [--out JSON] [--signs all]
                                            [--extra-salamat DIR]
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from senyas_refs import config, kit  # noqa: E402
from senyas_refs.sign_scorer import PARAMS, SignScorer  # noqa: E402

HELD_SIGNS = ("salamat", "oo", "kumusta", "tatay")
NEGATIVES = {"salamat": [(3, 17), (53, 17)], "nanay": [(52, 17)], "oo": [(14, 17)], "kumusta": [(53, 12)]}


def rounded(h):
    return None if h is None else np.round(np.asarray(h, dtype=np.float64), 5)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--refs", default=config.out(config.REFS_DIR), help="folder of <sign>.json (with passK)")
    ap.add_argument("--landmarks", default=config.data(config.LANDMARKS_DIR), help="folder of CCC_KK.json")
    ap.add_argument("--out", default=config.out(config.PARITY_JSON), help="fixture file to write")
    ap.add_argument("--signs", default="all", help="'all' or a comma list of app signs")
    ap.add_argument("--extra-salamat", help="optional folder of app recordings added as salamat cases (first 2)")
    a = ap.parse_args()
    data = kit.load_landmarks(a.landmarks)
    users = kit.load_recordings(a.extra_salamat) if a.extra_salamat else []
    fixture = {"params": list(PARAMS), "signs": []}
    for name in kit.parse_signs(a.signs):
        c = kit.SIGNS[name]
        doc = kit.load_reference_doc(a.refs, name)
        ph = SignScorer(K=doc.get("passK", 1.25))  # the sign's own pass threshold, as the app uses it
        refs = [ph.reference(seq) for seq in kit.reference_sequences(doc)]
        P = ph._pack(refs)
        sign = {"sign": name, "two": bool(P["two"]), "tau": [P["tau"][p] for p in PARAMS],
                "passK": doc.get("passK", 1.25), "cases": []}

        probes = []
        bundled = {r["clip"] for r in doc["refs"]}
        unseen = [next(k for k in range(lo, lo + 5) if "%d/%d" % (c, k) not in bundled) for lo in (0, 15)]
        for k in unseen:
            seq = ph.prep(data[(c, k)])
            probes.append(("%d/%d" % (c, k), seq))
            if k == unseen[-1] and name in HELD_SIGNS:
                probes.append(("%d/%d hold" % (c, k), kit.hold(kit.trim(seq), 0.5)))
                probes.append(("%d/%d backwards" % (c, k), kit.backwards(kit.trim(seq))))
                probes.append(("%d/%d half rate" % (c, k), kit.half_rate(seq)))
        for cc, kk in NEGATIVES.get(name, []):
            probes.append(("%d/%d as %s" % (cc, kk, name), ph.prep(data[(cc, kk)])))
        if name == "salamat":
            for u in users[:2]:
                probes.append((u["name"], ph.prep(u)))

        for label, seq in probes:
            frames = []
            seq2 = []
            for t, d, n in seq:
                d, n = rounded(d), rounded(n)
                seq2.append((t, d, n))
                frames.append({"t": int(t), "hands": [h.reshape(-1).tolist() for h in (d, n) if h is not None]})
            res = ph.score(refs, seq2)
            ex = ph.explain(refs, seq2)
            sign["cases"].append({
                "name": label, "frames": frames,
                "score": -1 if res is None else int(res["score"]),
                "sub": None if res is None else [float(res["sub"][p]) for p in PARAMS],
                "mirrored": None if res is None else bool(res["mirrored"]),
                "ref": None if res is None else res["ref"],
                "messages": ex["messages"],
            })
            print("%-20s %-24s score %4s  %s" % (name, label, sign["cases"][-1]["score"], " | ".join(ex["messages"])))
        fixture["signs"].append(sign)
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, "w") as f:
        json.dump(fixture, f, separators=(",", ":"))
    print("wrote", a.out, os.path.getsize(a.out) // 1024, "KB")


if __name__ == "__main__":
    main()
