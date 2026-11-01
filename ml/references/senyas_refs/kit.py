"""Shared helpers: the 26 app signs, landmark loading, test probes and the evaluation protocol.

Landmark files are named CCC_KK.json (CCC = FSL-105 class, KK = clip number). FSL-105 has four
signers with five clips each per sign, so signer = KK // 5 (0-4 A, 5-9 B, 10-14 C, 15-19 D). A few
classes also have clips 20-21; they belong to no signer fold and no step uses them.

Coordinates follow the app: x is mirrored (1 - x), so the picture reads like a mirror.

Protocol (leave-one-signer-out: the learner is a person the references never saw):
  for each app sign S and each held-out signer h:
    refs = scorer.reference(seq) for S's clips by the 3 other signers
    P  S's clips by h                           -> should pass (score >= 85)
    N  other signs' clips by h                  -> should not pass
    H  one moment of each P clip (start, middle, end) held still 2.5 s -> must not pass
    E  P clips at half the frame rate (7.5 fps) -> should pass like P
    R  P clips played backwards                 -> should not pass
"""
import glob
import json
import os

import numpy as np

from .sign_scorer import Seq

# App sign -> FSL-105 class. Order matters: it is the order of the parity fixture's signs.
SIGNS = {
    "kumusta": 3, "magandang_umaga": 0, "magandang_gabi": 2, "salamat": 7, "walang_anuman": 8,
    "oo": 15, "hindi": 14, "nanay": 53, "tatay": 52,
    "magandang_hapon": 1, "kita_tayo_bukas": 9, "naiintindihan": 10, "hindi_naiintindihan": 11, "alam_ko": 12,
    "hindi_ko_alam": 13, "mali": 16, "tama": 17, "mabagal": 18, "mabilis": 19, "dalawa": 21, "lima": 24,
    "tinapay": 85, "itlog": 86, "gatas": 98, "kape": 99, "asukal": 103,
}
NAMES = {v: k for k, v in SIGNS.items()}

SOURCE = ("FSL-105 dataset, I. J. Tupal, De La Salle University (CC BY 4.0), doi.org/10.17632/48y2y99mb9.2. "
          "Hand landmarks tracked with MediaPipe HandLandmarker at 15 fps.")
COORDS = "x mirrored (1 - x), y in image widths (y * height / width), z as tracked"


def signer(clip):
    return clip // 5


def parse_signs(arg):
    """'all' or a comma list of app sign names -> list of names (in SIGNS order for 'all')."""
    if not arg or arg == "all":
        return list(SIGNS)
    names = [s.strip() for s in arg.split(",") if s.strip()]
    for n in names:
        if n not in SIGNS:
            raise SystemExit("unknown sign %r; known: %s" % (n, ", ".join(SIGNS)))
    return names


# ----------------------------------------------------------------------------- loading

def load_landmarks(landmarks_dir):
    """{(class, clip): sample} from the CCC_KK.json files written by 02_extract_landmarks.py."""
    out = {}
    for p in sorted(glob.glob(os.path.join(landmarks_dir, "*.json"))):
        name = os.path.basename(p)[:-5]
        c, k = name.split("_")
        with open(p) as f:
            s = json.load(f)
        s["name"] = name
        s["cls"], s["clip"] = int(c), int(k)
        out[(int(c), int(k))] = s
    if not out:
        raise SystemExit("no landmark files (CCC_KK.json) in %s" % landmarks_dir)
    return out


def load_recordings(rec_dir):
    """Optional app recordings (SignSample JSON), named user_<last 7 chars of the file name>."""
    out = []
    for p in sorted(glob.glob(os.path.join(rec_dir, "*.json"))):
        with open(p) as f:
            s = json.load(f)
        s["name"] = "user_" + os.path.basename(p)[-12:-5]
        out.append(s)
    return out


def arr(v):
    """A stored hand (63 floats or None) -> (21, 3) float64, read through float32 like the app."""
    return None if v is None else np.array(v, dtype=np.float32).astype(np.float64).reshape(21, 3)


def load_reference_doc(refs_dir, name):
    with open(os.path.join(refs_dir, name + ".json")) as f:
        return json.load(f)


def reference_sequences(doc):
    """The reference clips of one sign_refs document as scorer sequences."""
    seqs = []
    for r in doc["refs"]:
        seq = Seq([(fr["t"], arr(fr["dom"]), arr(fr["nd"])) for fr in r["frames"]])
        seq.meta = {"name": r["clip"], "clip": None, "signer": r["signer"]}
        seqs.append(seq)
    return seqs


def trim(seq):
    """Drops leading/trailing frames without any hand."""
    idx = [i for i, (_, a, b) in enumerate(seq) if a is not None or b is not None]
    return seq[idx[0]: idx[-1] + 1] if idx else []


# ----------------------------------------------------------------------------- probes

def hold(seq, frac, seconds=2.5, hz=15):
    """One moment of the sign (frac 0 = start, 1 = end) held still for `seconds`."""
    hands = [f for f in seq if f[1] is not None or f[2] is not None]
    if not hands:
        return None
    pick = hands[min(len(hands) - 1, int(frac * (len(hands) - 1)))]
    n = int(seconds * hz)
    return [(int(k * 1000 / hz), pick[1], pick[2]) for k in range(n)]


def half_rate(seq):
    return seq[::2]


def backwards(seq):
    ts = [f[0] for f in seq]
    return [(ts[i], f[1], f[2]) for i, f in enumerate(reversed(seq))]


# ----------------------------------------------------------------------------- protocol

def protocol(scorer, data, signs=None, neg_classes=None, folds=(0, 1, 2, 3), refs_per_signer=None):
    """Runs the leave-one-signer-out protocol. scorer must provide:
         prep(sample)           -> live sequence; probes are built from it
         reference(seq)         -> reference object from a prepped clip (or None to skip)
         replay(refs, seq)      -> best score 0..100 over the sequence (-1 if never scored)
    neg_classes limits the negative classes (None = every class available for the held-out signer).
    refs_per_signer limits reference clips per other signer (None = all 5)."""
    signs = signs or list(SIGNS)
    prepped = {}

    def prep(key):
        if key not in prepped:
            prepped[key] = scorer.prep(data[key])
        return prepped[key]

    res = {k: [] for k in ("P", "N", "H", "E", "R")}
    confusions = []
    per_sign = {}
    for name in signs:
        c = SIGNS[name]
        sp = {k: [] for k in ("P", "N", "H", "E", "R")}
        for h in folds:
            ref_keys = [(c, k) for k in range(20) if signer(k) != h and (c, k) in data]
            if refs_per_signer:
                ref_keys = [key for key in ref_keys if key[1] % 5 < refs_per_signer]
            refs = [r for r in (scorer.reference(prep(key)) for key in ref_keys) if r is not None]
            if not refs:
                continue
            for k in range(h * 5, h * 5 + 5):
                if (c, k) not in data:
                    continue
                seq = prep((c, k))
                sp["P"].append(scorer.replay(refs, seq))
                sp["E"].append(scorer.replay(refs, half_rate(seq)))
                sp["R"].append(scorer.replay(refs, backwards(trim(seq))))
                for frac in (0.0, 0.5, 1.0):
                    hd = hold(trim(seq), frac)
                    if hd:
                        sp["H"].append(scorer.replay(refs, hd))
            for (cc, kk) in sorted(data):
                if cc == c or signer(kk) != h:
                    continue
                if neg_classes is not None and cc not in neg_classes:
                    continue
                s = scorer.replay(refs, prep((cc, kk)))
                sp["N"].append(s)
                if s >= 85:
                    confusions.append((name, data[(cc, kk)]["name"], s))
        per_sign[name] = summarize(sp)
        for k in res:
            res[k] += sp[k]
    return {"all": summarize(res), "per_sign": per_sign, "confusions": sorted(confusions, key=lambda x: -x[2])[:40]}


def summarize(res):
    m = {}
    for k, v in res.items():
        v = [x for x in v if x is not None]
        if not v:
            continue
        m[k] = {"n": len(v), "pass85": round(sum(1 for x in v if x >= 85) / len(v), 3),
                "p10": round(float(np.percentile(v, 10)), 1), "median": round(float(np.percentile(v, 50)), 1),
                "p90": round(float(np.percentile(v, 90)), 1), "max": max(v)}
    return m
