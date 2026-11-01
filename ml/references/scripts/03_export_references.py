"""Step 3: writes the app's built-in references, sign_refs/<sign>.json.

Each file holds, per signer, the 2 best-tracked clips of the sign (8 references in all).
Best-tracked means the dominant hand is seen in the most frames; ties go to the lower clip number.
Clips are prepared exactly as the scorer prepares references (SignScorer.prep): both hands followed
by position, duplicate detections merged, the more active hand made dominant, then trimmed to the
frames with hands. Coordinates: x mirrored (1 - x), y in image widths (y * 360 / 640), z as tracked.
The pass threshold "passK" is added afterwards by 04_pass_thresholds.py.

usage: python scripts/03_export_references.py [--landmarks DIR] [--out DIR] [--per-signer 2] [--signs all]
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from senyas_refs import config, kit  # noqa: E402
from senyas_refs.sign_scorer import SignScorer  # noqa: E402


def pack(h):
    return None if h is None else [round(float(v), 4) for v in h.reshape(-1)]


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--landmarks", default=config.data(config.LANDMARKS_DIR), help="folder of CCC_KK.json")
    ap.add_argument("--out", default=config.out(config.REFS_DIR), help="folder for <sign>.json")
    ap.add_argument("--per-signer", type=int, default=2, help="clips kept per signer (default 2)")
    ap.add_argument("--signs", default="all", help="'all' or a comma list of app signs")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    ph = SignScorer()
    data = kit.load_landmarks(a.landmarks)
    total = 0
    for name in kit.parse_signs(a.signs):
        c = kit.SIGNS[name]
        refs = []
        for signer in range(4):
            # Each signer's best-tracked clips: the dominant hand seen in most frames of the sign.
            cands = []
            for k in range(signer * 5, signer * 5 + 5):
                if (c, k) not in data:
                    continue
                seq = kit.trim(list(ph.prep(data[(c, k)])))
                if len(seq) < 5:
                    continue
                coverage = sum(1 for f in seq if f[1] is not None) / len(seq)
                cands.append((-coverage, k, seq))
            for _, k, seq in sorted(cands, key=lambda x: (x[0], x[1]))[:a.per_signer]:
                t0 = seq[0][0]
                refs.append({"clip": "%d/%d" % (c, k), "signer": signer,
                             "frames": [{"t": int(t - t0), "dom": pack(d), "nd": pack(n)} for t, d, n in seq]})
        path = os.path.join(a.out, name + ".json")
        with open(path, "w") as f:
            json.dump({"sign": name, "fsl105Class": c, "source": kit.SOURCE, "coords": kit.COORDS, "refs": refs}, f,
                      separators=(",", ":"))
        size = os.path.getsize(path)
        total += size
        print("%-20s refs=%2d  %6.1f KB" % (name, len(refs), size / 1024))
    print("total %.1f KB" % (total / 1024))


if __name__ == "__main__":
    main()
