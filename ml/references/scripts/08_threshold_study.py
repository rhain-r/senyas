"""Step 8: threshold study that chose the per-sign passK rule (contrast, KMIN 1.25, KMAX 1.8).

collect  runs the leave-one-signer-out probes (P, N, H, E, R) for each sign. For every probe it keeps
         x = max over parameters of distance / tolerance at the best alignment.
         A probe passes at threshold K when x <= K and the dominant hand is present >= 70% of the sign.
         It also keeps "C" probes: the other app signs' first 2 clips by the reference signers.
         Those are the clips the app bundles, so they show how close each look-alike comes.
analyze  replays the pass/fail decisions under several rules, without scoring again:
         global K       the same K for every sign
         contrast       per sign and fold, K = clamp(0.99 * min x over the C probes, KMIN, KMAX)

usage: python scripts/08_threshold_study.py collect [--landmarks DIR] [--out DIR] [--signs all] [--workers 4]
       python scripts/08_threshold_study.py analyze [--out DIR] [--kmin 1.25] [--kmax 1.8] [--csv-dir DIR]
"""
import os

for _v in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS"):  # one core per process
    os.environ.setdefault(_v, "1")

import argparse  # noqa: E402
import csv  # noqa: E402
import glob  # noqa: E402
import json  # noqa: E402
import sys  # noqa: E402
import time  # noqa: E402
from concurrent.futures import ProcessPoolExecutor  # noqa: E402

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from senyas_refs import config, kit  # noqa: E402
from senyas_refs.sign_scorer import PARAMS, SignScorer  # noqa: E402

MARGIN = 0.99
KINDS = "PNHER"
GLOBAL_KS = (1.25, 1.4, 1.6, 1.8, 2.0)
CONTRAST_KMINS = (1.0, 1.1, 1.25)
CONTRAST_KMAXS = (1.4, 1.6, 1.8, 2.0, 2.5)


# ----------------------------------------------------------------------------- collect

def collect_one(job):
    landmarks, name, out_path = job
    sc = SignScorer()
    data = kit.load_landmarks(landmarks)
    c = kit.SIGNS[name]
    app = set(kit.SIGNS.values())
    prepped = {}

    def prep(key):
        if key not in prepped:
            prepped[key] = sc.prep(data[key])
        return prepped[key]

    rows = []
    t0 = time.time()
    for h in range(4):
        refs = [sc.reference(prep((c, k))) for k in range(20) if kit.signer(k) != h and k % 5 < 2 and (c, k) in data]
        refs = [r for r in refs if r is not None]
        P = sc._pack(refs)

        def rec(kind, seq, other=None):
            if not seq:
                return
            r = sc.score(refs, seq)
            if r is None:
                rows.append({"fold": h, "kind": kind, "other": other, "x": None})
                return
            xs = {p: r["d"][p] / P["tau"][p] for p in PARAMS if p in r["sub"]}
            rows.append({"fold": h, "kind": kind, "other": other, "x": max(xs.values()), "xs": xs,
                         "present": r["d"]["_present"], "score": r["score"]})

        for k in range(h * 5, h * 5 + 5):
            if (c, k) not in data:
                continue
            seq = prep((c, k))
            rec("P", seq)
            rec("E", kit.half_rate(seq))
            rec("R", kit.backwards(kit.trim(seq)))
            for fr in (0.0, 0.5, 1.0):
                rec("H", kit.hold(kit.trim(seq), fr))
        for (cc, kk) in sorted(data):
            if cc == c:
                continue
            if kit.signer(kk) == h:
                rec("N", prep((cc, kk)), "%03d_%02d" % (cc, kk))
            elif cc in app and kk % 5 < 2 and kit.signer(kk) < 4:
                rec("C", prep((cc, kk)), "%03d_%02d" % (cc, kk))
    with open(out_path, "w") as f:
        json.dump({"sign": name, "rows": rows, "seconds": round(time.time() - t0)}, f)
    return name, round(time.time() - t0)


# ----------------------------------------------------------------------------- analyze

def load(d):
    out = {}
    for p in sorted(glob.glob(os.path.join(d, "*.json"))):
        with open(p) as f:
            o = json.load(f)
        out[o["sign"]] = o["rows"]
    if not out:
        raise SystemExit("no collect output (*.json) in %s" % d)
    return out


def passes(r, k):
    return r["x"] is not None and r["x"] <= k and r.get("present", 1) >= 0.7


def fold_k(rows, h, rule):
    if rule[0] == "global":
        return rule[1]
    xs = [r["x"] for r in rows if r["kind"] == "C" and r["fold"] == h and r["x"] is not None]
    closest = min(xs) if xs else float("inf")
    return min(rule[2], max(rule[1], MARGIN * closest))


def run(data, rule, verbose=False):
    """Pass rates under one rule -> (totals {kind: [passed, n]}, per-sign rows)."""
    tot = {k: [0, 0] for k in KINDS}
    per_sign = []
    for sign, rows in data.items():
        kf = {h: fold_k(rows, h, rule) for h in range(4)}
        per = {}
        for kind in KINDS:
            rs = [r for r in rows if r["kind"] == kind]
            n = len(rs)
            p = sum(1 for r in rs if passes(r, kf[r["fold"]]))
            tot[kind][0] += p
            tot[kind][1] += n
            per[kind] = p / n if n else 0
        per_sign.append((sign, kf, per))
    print("%-24s P %.3f N %.4f H %.3f E %.3f R %.3f" % (str(rule), *(tot[k][0] / max(1, tot[k][1]) for k in KINDS)))
    if verbose:
        for sign, kf, per in per_sign:
            print("  %-20s K %s  P %.2f N %.3f H %.2f E %.2f R %.2f" % (
                sign, "/".join("%.2f" % kf[h] for h in range(4)), per["P"], per["N"], per["H"], per["E"], per["R"]))
    return tot, per_sign


def confusions(data, rule):
    out = []
    for sign, rows in data.items():
        for h in range(4):
            k = fold_k(rows, h, rule)
            for r in rows:
                if r["kind"] == "N" and r["fold"] == h and passes(r, k):
                    out.append((sign, r["other"], round(r["x"], 2), round(k, 2)))
    return out


def analyze(a):
    data = load(a.out)
    print("signs:", len(data))
    rules = [("global", k) for k in GLOBAL_KS] + [("contrast", lo, hi) for lo in CONTRAST_KMINS for hi in CONTRAST_KMAXS]
    summary = [(rule, run(data, rule)[0]) for rule in rules]
    best = ("contrast", a.kmin, a.kmax)
    print("\nDETAIL", best)
    tot, per_sign = run(data, best, verbose=True)
    print("false passes:", confusions(data, best)[:40])
    if a.csv_dir:
        os.makedirs(a.csv_dir, exist_ok=True)
        with open(os.path.join(a.csv_dir, "threshold_rules.csv"), "w", newline="") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(["rule", "K_or_KMIN", "KMAX"] + list(KINDS))
            for rule, t in summary:
                w.writerow([rule[0], rule[1], rule[2] if len(rule) > 2 else ""]
                           + [round(t[k][0] / max(1, t[k][1]), 4) for k in KINDS])
        with open(os.path.join(a.csv_dir, "per_sign_pass_k.csv"), "w", newline="") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(["sign"] + ["K_fold_%s" % s for s in "ABCD"] + list(KINDS))
            for sign, kf, per in per_sign:
                w.writerow([sign] + [round(kf[h], 2) for h in range(4)] + [round(per[k], 3) for k in KINDS])
            w.writerow(["ALL", "", "", "", ""] + [round(tot[k][0] / max(1, tot[k][1]), 4) for k in KINDS])


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("collect", help="score the probes and store x per probe")
    c.add_argument("--landmarks", default=config.data(config.LANDMARKS_DIR), help="folder of CCC_KK.json")
    c.add_argument("--out", default=config.out(config.STUDY_DIR), help="folder for <sign>.json")
    c.add_argument("--signs", default="all", help="'all' or a comma list of app signs")
    c.add_argument("--workers", type=int, default=4, help="parallel processes (default 4)")
    n = sub.add_parser("analyze", help="compare threshold rules on collected probes")
    n.add_argument("--out", default=config.out(config.STUDY_DIR), help="folder with collect output")
    n.add_argument("--kmin", type=float, default=1.25, help="KMIN of the detailed rule (default 1.25)")
    n.add_argument("--kmax", type=float, default=1.8, help="KMAX of the detailed rule (default 1.8)")
    n.add_argument("--csv-dir", help="also write threshold_rules.csv and per_sign_pass_k.csv here")
    a = ap.parse_args()
    if a.cmd == "collect":
        os.makedirs(a.out, exist_ok=True)
        jobs = [(a.landmarks, s, os.path.join(a.out, s + ".json")) for s in kit.parse_signs(a.signs)]
        with ProcessPoolExecutor(a.workers) as ex:
            for name, sec in ex.map(collect_one, jobs):
                print("%-20s done in %d s" % (name, sec), flush=True)
    else:
        analyze(a)


if __name__ == "__main__":
    main()
