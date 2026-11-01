"""Step 7: leave-one-signer-out evaluation of the scorer (probes P, N, H, E, R; see senyas_refs.kit).

For each sign and held-out signer, the references are the other 3 signers' first 2 clips (6 refs).
Every probe counts as passed at score >= 85, with one global K for all signs (default 1.25).
Each sign runs in its own process and writes OUT/<tag>__<sign>.json; the totals are then printed.
One sign takes a few minutes of one CPU core; --workers runs several signs at once.

usage: python scripts/07_evaluate.py [--landmarks DIR] [--out DIR] [--tag s26] [--signs all]
                                     [--workers 4] [--set KEY=VALUE ...] [--csv table.csv]
       python scripts/07_evaluate.py --aggregate-only [--out DIR] [--tag s26] [--csv table.csv]
--set changes a scorer setting, e.g. --set K=1.4 or --set DIR_ON=False (the design without direction).
"""
import os

for _v in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS"):  # one core per process
    os.environ.setdefault(_v, "1")

import argparse  # noqa: E402
import ast  # noqa: E402
import csv  # noqa: E402
import glob  # noqa: E402
import json  # noqa: E402
import sys  # noqa: E402
import time  # noqa: E402
from concurrent.futures import ProcessPoolExecutor  # noqa: E402

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from senyas_refs import config, kit  # noqa: E402
from senyas_refs.sign_scorer import SignScorer  # noqa: E402

KINDS = ("P", "N", "H", "E", "R")


def run_one(job):
    landmarks, sign, out_path, kw = job
    t0 = time.time()
    scorer = SignScorer(**kw)
    out = kit.protocol(scorer, kit.load_landmarks(landmarks), signs=[sign], refs_per_signer=2)
    out["seconds"] = round(time.time() - t0)
    with open(out_path, "w") as f:
        json.dump(out, f)
    return sign, out["seconds"]


def aggregate(out_dir, tag, csv_path=None):
    """Pass rates per sign and in total (weighted by probe counts), from OUT/<tag>__*.json."""
    tot = {}
    rows = []
    for p in sorted(glob.glob(os.path.join(out_dir, tag + "__*.json"))):
        with open(p) as f:
            o = json.load(f)
        for name, m in o["per_sign"].items():
            row = {"sign": name}
            for k in KINDS:
                if k not in m:
                    continue
                n, r = m[k]["n"], m[k]["pass85"]
                a = tot.setdefault(k, [0, 0.0])
                a[0] += n
                a[1] += n * r
                row[k] = r
                row["n_" + k] = n
            rows.append(row)
    if not rows:
        raise SystemExit("no results %s__*.json in %s" % (tag, out_dir))
    print("%-10s ALL  %s" % (tag, "  ".join("%s %.3f" % (k, v[1] / v[0]) for k, v in tot.items())))
    for row in rows:
        print("  %-20s %s" % (row["sign"], "  ".join("%s %.2f" % (k, row[k]) for k in KINDS if k in row)))
    if csv_path:
        with open(csv_path, "w", newline="") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(["sign"] + list(KINDS) + ["n_" + k for k in KINDS])
            for row in rows:
                w.writerow([row["sign"]] + [row.get(k, "") for k in KINDS] + [row.get("n_" + k, "") for k in KINDS])
            w.writerow(["ALL"] + [round(tot[k][1] / tot[k][0], 4) if k in tot else "" for k in KINDS]
                       + [tot[k][0] if k in tot else "" for k in KINDS])
    return tot


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--landmarks", default=config.data(config.LANDMARKS_DIR), help="folder of CCC_KK.json")
    ap.add_argument("--out", default=config.out(config.EVAL_DIR), help="folder for <tag>__<sign>.json")
    ap.add_argument("--tag", default="s26", help="name of this run (default s26)")
    ap.add_argument("--signs", default="all", help="'all' or a comma list of app signs")
    ap.add_argument("--workers", type=int, default=4, help="parallel processes (default 4)")
    ap.add_argument("--set", action="append", default=[], metavar="KEY=VALUE", help="scorer setting")
    ap.add_argument("--csv", help="also write the per-sign table as CSV")
    ap.add_argument("--aggregate-only", action="store_true", help="only summarise existing results")
    a = ap.parse_args()
    if not a.aggregate_only:
        kw = {}
        for s in a.set:
            k, v = s.split("=", 1)
            kw[k] = ast.literal_eval(v)
        SignScorer(**kw)  # fails early on an unknown setting
        os.makedirs(a.out, exist_ok=True)
        jobs = [(a.landmarks, sign, os.path.join(a.out, "%s__%s.json" % (a.tag, sign)), kw)
                for sign in kit.parse_signs(a.signs)]
        with ProcessPoolExecutor(a.workers) as ex:
            for sign, sec in ex.map(run_one, jobs):
                print("%-20s done in %d s" % (sign, sec), flush=True)
    aggregate(a.out, a.tag, a.csv)


if __name__ == "__main__":
    main()
