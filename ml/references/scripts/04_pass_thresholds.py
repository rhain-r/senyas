"""Step 4: writes each sign's pass threshold "passK" into sign_refs/<sign>.json (in place).

passK = clamp(0.99 * x_closest, KMIN, KMAX), with KMIN 1.25 and KMAX 1.8 in the app.
x_closest is how close any reference clip of another built-in sign comes to passing this sign
(x = max over parameters of distance / tolerance, best alignment).
So a sign without look-alikes gets a more forgiving threshold, and one with a close look-alike a
stricter one. Run it on the full 26-sign folder: the result depends on which other signs are present.

usage: python scripts/04_pass_thresholds.py [--refs DIR] [--kmin 1.25] [--kmax 1.8] [--csv report.csv]
"""
import argparse
import csv
import glob
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from senyas_refs import config, kit  # noqa: E402
from senyas_refs.sign_scorer import PARAMS, SignScorer  # noqa: E402

MARGIN = 0.99


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--refs", default=config.out(config.REFS_DIR), help="folder of <sign>.json (updated in place)")
    ap.add_argument("--kmin", type=float, default=1.25, help="lowest passK (default 1.25)")
    ap.add_argument("--kmax", type=float, default=1.8, help="highest passK (default 1.8)")
    ap.add_argument("--csv", help="also write a per-sign table (sign, passK, closest clip, x)")
    a = ap.parse_args()
    ph = SignScorer()
    docs, seqs = {}, {}
    for p in sorted(glob.glob(os.path.join(a.refs, "*.json"))):
        name = os.path.basename(p)[:-5]
        with open(p) as f:
            docs[name] = json.load(f)
        seqs[name] = kit.reference_sequences(docs[name])
    rows = []
    for name, doc in docs.items():
        refs = [ph.reference(s) for s in seqs[name]]
        P = ph._pack(refs)
        closest, who = float("inf"), None
        for other, oseqs in seqs.items():
            if other == name:
                continue
            for s in oseqs:
                r = ph.score(refs, s)
                if r is None:
                    continue
                x = max(r["d"][p] / P["tau"][p] for p in PARAMS if p in r["sub"])
                if x < closest:
                    closest, who = x, other + " " + s.meta["name"]
        k = min(a.kmax, max(a.kmin, MARGIN * closest))
        doc["passK"] = round(k, 4)
        with open(os.path.join(a.refs, name + ".json"), "w") as f:
            json.dump(doc, f, separators=(",", ":"))
        rows.append((name, doc["passK"], who, round(closest, 4)))
        print("%-20s passK %.3f  closest %s at %.3f" % (name, k, who, closest))
    if a.csv:
        with open(a.csv, "w", newline="") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(["sign", "passK", "closest_other_sign", "closest_clip", "x_closest"])
            for name, k, who, x in rows:
                sign, clip = who.split(" ") if who else ("", "")
                w.writerow([name, k, sign, clip, x])


if __name__ == "__main__":
    main()
