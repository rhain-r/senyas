"""Sign scorer of the Senyas Practice Room (Python reference design of the app's SignScorer.java).

A sign is judged on the parameters FSL teachers name, each on its own:

  handshape    finger points in the hand's own 3D palm frame, in palm sizes (rotation and size free)
  orientation  where the fingers point (u) and where the palm faces (n), camera space
  movement     the dominant hand's path (palm centre, palm sizes, centred on its own mean) compared
               point by point along the alignment ("movement"), and how big it is ("size")
  two hands    two-handed signs: the second hand is there, sits where it should relative to the
               dominant hand and points/faces the right way. One-handed signs: the second hand must
               not take part (a hand resting in view is fine, a second hand moving along is not).
  direction    at each aligned moment the dominant hand moves the way the reference moves
               (saturated palm-centre velocity); only counted where the reference clearly moves
               (DIR_MIN_G), with its own tolerance floor (DIR_FLOOR).

One shared alignment: open-begin/open-end DTW of the live stream against the whole reference, with
slope limits (each reference frame takes 1..3 live frames, or two reference frames share one: the
learner may be 3x slower .. 2x faster) and a local cost that mixes the per-frame parameters
(handshape, orientation, movement direction, second hand). Along the best path every parameter gets
its own distance d. Each becomes a 0..100 sub-score 100 * 0.85 ** ((d / (K * tau)) ** 2), where the
tolerance tau is learned from the references themselves (median distance between different
reference signers, per sign and parameter, never below a floor) and K is the sign's pass threshold
("passK", default 1.25). The weakest sub-score is the score, capped at 50 when the dominant hand is
seen in under 70% of the sign; a score of 85 or more passes. So the app can say which parameter is
off, when (start / middle / end of the sign) and which way to correct it (explain()).

Live handling: MediaPipe Left/Right labels are ignored. Duplicate detections of one hand are merged;
hands are kept in two slots by wrist continuity; each match tries both slots as the dominant hand and
both the sign and its mirror image (left-handed learners), so label flips, a resting second hand and
left-handers need no special case. Everything is on a fixed time grid (RATE_HZ) built from
timestamps, so 7..30 fps cameras look the same. Coordinates are made isotropic (y in units of the
image width), because the references are 16:9 and the phone analysis frames 4:3.
"""
import math

import numpy as np

POINTS = 21

DEFAULTS = dict(
    RATE_HZ=10.0,         # time grid (Hz)
    GAP_MS=350.0,         # a hand missing up to this long is interpolated
    DUP_DIST=1.0,         # two detections closer than this (mean landmark distance, palm sizes) are one hand
    STALE_MS=1000.0,      # a slot not seen this long forgets where its hand was
    V0=5.0,               # palm sizes/s: speed at which the movement-direction feature saturates
    VEL_W=1,              # grid frames each side for the palm-centre velocity
    Z_W=0.5,              # weight of palm-normal (depth) components of the handshape
    SIG_HS=0.45,          # typical spreads of the per-frame distances (normalise the alignment cost)
    SIG_OR=0.80,
    SIG_VEL=1.00,
    SIG_REL=2.00,
    EDGE_MS=0.0,          # references: this much of the first / last hand frames is dropped (a hand
                          # entering or leaving the picture is first seen at a random point)
    MV_RMS=False,         # movement error: RMS (True) or mean (False) over the path
    MISS=3.0,             # alignment cost of a grid frame without the dominant hand
    REL_MISS=6.0,         # palm sizes: second-hand error when it is missing but should be there
    STEP_PEN=0.3,         # alignment cost of a non-diagonal step (keeps the tempo even where nothing moves)
    SKIP_MS=300.0,        # the first / last this-much of a reference may go unmatched, but only where the
                          # live hand has just come into / gone out of the picture (not seen = not wrong)
    SKIP_PEN=1.0,         # alignment cost per unmatched reference frame
    K=1.25,               # a parameter scores 85 at K x its reference tolerance
    FLOOR_SCALE=1.0,      # multiplies the tolerance floors (FLOOR) - for robustness checks
    ONE_HAND_TOL=0.35,    # one-handed signs: tolerated second-hand activity (its path / the dominant's)
    MATCH_EVERY=3,        # grid frames between whole-sign matches (300 ms at 10 Hz)
    TOP=3,                # references fully evaluated per match (lowest alignment cost first)
    DIR_FLOOR=0.35,       # tolerance floor of the direction parameter
    DIR_ON=True,          # score the direction parameter (False: the design without it; score() only)
    DIR_MIN_G=0.15,       # direction counts only where the reference clearly moves
    MIRROR=True,          # also try the mirror image (left-handed learners)
    SWAP=True,            # also try the other hand slot as dominant
)

SHAPE_PTS = [2, 3, 4, 6, 8, 10, 12, 14, 16, 18, 20]
FINGERS = {"thumb": [0, 1, 2], "index finger": [3, 4], "middle finger": [5, 6], "ring finger": [7, 8],
           "little finger": [9, 10]}          # indices into SHAPE_PTS
PARAMS = ("handshape", "orientation", "movement", "size", "two_hands", "direction")

# tolerance floors (a parameter's tolerance is never below this, whatever the references say)
# (the direction parameter's floor is DEFAULTS["DIR_FLOOR"])
FLOOR = {"handshape": 0.15, "orientation": 0.15, "movement": 0.5, "size": math.log(1.3), "two_hands": 0.8}


# ----------------------------------------------------------------------------- geometry

def palm_size(h):
    return float(np.mean([np.linalg.norm(h[a] - h[b]) for a, b in ((0, 5), (0, 17), (5, 17), (0, 9))])) or 1e-6


def hand_features(H, z_w):
    """H (N,21,3) iso coordinates -> q (N,33) handshape, u, n (N,3) finger direction and palm normal,
    c (N,2) palm centre, s (N,) 3D palm size."""
    s = np.mean([np.linalg.norm(H[:, a] - H[:, b], axis=1) for a, b in ((0, 5), (0, 17), (5, 17), (0, 9))], 0)
    s = np.maximum(s, 1e-6)
    y = H[:, 9] - H[:, 0]
    y /= np.maximum(np.linalg.norm(y, axis=1, keepdims=True), 1e-9)
    x = H[:, 5] - H[:, 17]
    x -= y * np.sum(x * y, 1, keepdims=True)
    x /= np.maximum(np.linalg.norm(x, axis=1, keepdims=True), 1e-9)
    z = np.cross(x, y)
    rel = (H[:, SHAPE_PTS] - H[:, :1]) / s[:, None, None]
    q = np.stack([np.sum(rel * x[:, None], 2), np.sum(rel * y[:, None], 2), z_w * np.sum(rel * z[:, None], 2)], 2)
    c = H[:, [0, 5, 9, 13, 17], :2].mean(1)
    return {"q": q.reshape(len(H), -1), "u": y, "n": z, "c": c, "s": s}


# ----------------------------------------------------------------------------- tracking

def _dup(a, b, dup):
    s = min(palm_size(a), palm_size(b))
    return float(np.mean(np.linalg.norm(a[:, :2] - b[:, :2], axis=1))) < dup * s


class Tracker:
    """Two hand slots by wrist continuity, causal (the same code runs live). Labels are ignored."""

    def __init__(self, dup, stale_ms):
        self.dup, self.stale = dup, stale_ms
        self.pos = [None, None]
        self.last = [-1e18, -1e18]

    def push(self, t, hands):
        hands = list(hands)
        if len(hands) >= 2 and _dup(hands[0], hands[1], self.dup):
            hands = hands[:1]
        hands = hands[:2]
        for s in range(2):
            if t - self.last[s] > self.stale:
                self.pos[s] = None
        cur = [None, None]

        def d(h, s):
            return float(np.hypot(*(h[0, :2] - self.pos[s]))) / palm_size(h)

        if len(hands) == 2:
            a, b = hands
            if self.pos[0] is None and self.pos[1] is None:
                cur = [a, b] if a[0, 0] >= b[0, 0] else [b, a]
            elif self.pos[1] is None:
                cur = [a, b] if d(a, 0) <= d(b, 0) else [b, a]
            elif self.pos[0] is None:
                cur = [b, a] if d(a, 1) <= d(b, 1) else [a, b]
            else:
                cur = [a, b] if d(a, 0) + d(b, 1) <= d(a, 1) + d(b, 0) else [b, a]
        elif len(hands) == 1:
            h = hands[0]
            if self.pos[0] is None and self.pos[1] is None:
                s = 0 if self.last[0] >= self.last[1] else 1   # the hand seen last keeps its slot
            elif self.pos[1] is None:
                s = 0
            elif self.pos[0] is None:
                s = 1
            else:
                s = 0 if d(h, 0) <= d(h, 1) else 1
            cur[s] = h
        for s in range(2):
            if cur[s] is not None:
                self.pos[s] = cur[s][0, :2].copy()
                self.last[s] = t
        return cur


def track(frames, dup, stale):
    tr = Tracker(dup, stale)
    return [(t, *tr.push(t, hs)) for t, hs in frames]


class Seq(list):
    """A prepped sequence [(t, dom, nd)] that remembers where it came from (clip, signer)."""
    meta = None


# ----------------------------------------------------------------------------- time grid

def grid(seq, rate, gap_ms):
    """seq [(t, a, b)] -> times (G,), A (G,21,3) with NaN where missing, B likewise. Linear
    interpolation between the samples around each tick when they are at most gap_ms apart."""
    if not seq:
        return np.zeros(0), np.zeros((0, POINTS, 3)), np.zeros((0, POINTS, 3))
    ts = np.array([f[0] for f in seq], dtype=float)
    step = 1000.0 / rate
    G = int(math.floor((ts[-1] - ts[0]) / step)) + 1
    times = ts[0] + step * np.arange(G)
    out = []
    for k in (1, 2):
        idx = [i for i, f in enumerate(seq) if f[k] is not None]
        arr = np.full((G, POINTS, 3), np.nan)
        if idx:
            tt = ts[idx]
            H = np.stack([seq[i][k] for i in idx])
            nxt = np.searchsorted(tt, times, side="left")          # first sample >= T
            for g, T in enumerate(times):
                b = nxt[g]
                if b < len(tt) and tt[b] == T:
                    arr[g] = H[b]
                    continue
                a = b - 1
                if 0 <= a and b < len(tt) and tt[b] - tt[a] <= gap_ms:
                    w = (T - tt[a]) / max(tt[b] - tt[a], 1e-9)
                    arr[g] = (1 - w) * H[a] + w * H[b]
                elif 0 <= a and T - tt[a] <= gap_ms / 2:
                    arr[g] = H[a]
                elif b < len(tt) and tt[b] - T <= gap_ms / 2:
                    arr[g] = H[b]
        out.append(arr)
    return times, out[0], out[1]


def flip(H):
    G = H.copy()
    G[..., 0] = -G[..., 0]
    return G


# ----------------------------------------------------------------------------- per-variant features

def stream_features(D, N, cfg):
    """Dominant / second hand grids (G,21,3, NaN = missing) -> per-frame feature arrays."""
    G = len(D)
    pd = ~np.isnan(D[:, 0, 0])
    pn = ~np.isnan(N[:, 0, 0])
    f = {"pd": pd, "pn": pn, "q": np.zeros((G, 33)), "u": np.zeros((G, 3)), "n": np.zeros((G, 3)),
         "c": np.full((G, 2), np.nan), "s": np.full(G, np.nan), "g": np.zeros((G, 2)),
         "r": np.zeros((G, 2)), "nu": np.zeros((G, 3)), "nn": np.zeros((G, 3)), "nc": np.full((G, 2), np.nan)}
    if pd.any():
        hf = hand_features(D[pd], cfg["Z_W"])
        for k in ("q", "u", "n", "c", "s"):
            f[k][pd] = hf[k]
        # movement direction: central difference of the palm centre, palm sizes/s, saturated
        w = int(cfg["VEL_W"])
        dt = 2.0 * w / cfg["RATE_HZ"]
        c, s = f["c"], f["s"]
        v = np.zeros((G, 2))
        ok = np.zeros(G, bool)
        if G > 2 * w:
            ok[w:-w] = pd[:-2 * w] & pd[2 * w:]
            v[w:-w] = (c[2 * w:] - c[:-2 * w]) / dt
        sv = np.where(ok, s, 1.0)
        v = np.where(ok[:, None], v / sv[:, None], 0.0)
        sp = np.linalg.norm(v, axis=1, keepdims=True)
        f["g"] = v / np.sqrt(sp ** 2 + cfg["V0"] ** 2)
    if pn.any():
        hn = hand_features(N[pn], cfg["Z_W"])
        f["nc"][pn] = hn["c"]
        both = pd & pn
        if both.any():
            hb = hand_features(N[both], cfg["Z_W"])
            f["r"][both] = (hb["c"] - f["c"][both]) / f["s"][both, None]
            f["nu"][both] = hb["u"]
            f["nn"][both] = hb["n"]
    return f


# ----------------------------------------------------------------------------- words for corrections

def _dir_word(v, mirrored):
    """Unit 3D direction (x right on screen, y down, z away from camera) -> word."""
    x, y, z = float(v[0]) * (-1 if mirrored else 1), float(v[1]), float(v[2])
    a = max((abs(x), "right" if x > 0 else "left"), (abs(y), "down" if y > 0 else "up"),
            (abs(z), "away from you" if z > 0 else "toward the camera"))
    return a[1]


def _phase_word(j, m):
    return ("at the start", "in the middle", "at the end")[min(2, int(3 * j / max(m, 1)))]


# ----------------------------------------------------------------------------- scorer

class SignScorer:
    """Scores live signing against a sign's references; cfg keys are those of DEFAULTS."""

    def __init__(self, **kw):
        self.cfg = dict(DEFAULTS)
        for k, v in kw.items():
            if k not in self.cfg:
                raise KeyError(k)
            self.cfg[k] = v
        self._packs = {}

    # ---- input
    def prep(self, sample):
        aspect = 360.0 / 640.0 if sample.get("source") == "fsl105" else 480.0 / 640.0
        frames = []
        for fr in sample["frames"]:
            hs = []
            for h in sorted(fr["hands"], key=lambda h: -h.get("score", 1.0)):
                a = np.array(h["lm"], dtype=np.float64).reshape(POINTS, 3)
                a[:, 0] = 1.0 - a[:, 0]          # mirrored like the app's preview
                a[:, 1] *= aspect                # y in units of image width (x, y, z isotropic)
                hs.append(a)
            frames.append((fr["t"], hs))
        seq = track(frames, self.cfg["DUP_DIST"], self.cfg["STALE_MS"])
        # dominant slot = the one that moves more (offline, for references; live tries both)
        act = []
        for s in (1, 2):
            pts = [(f[s][0, :2], palm_size(f[s])) for f in seq if f[s] is not None]
            path = sum(float(np.hypot(*(pts[i][0] - pts[i - 1][0]))) / pts[i][1] for i in range(1, len(pts)))
            act.append(len(pts) * 0.1 + path)
        if act[1] > act[0]:
            seq = [(t, b, a) for t, a, b in seq]
        out = Seq(seq)
        out.meta = {"name": sample.get("name"), "clip": sample.get("clip"),
                    "signer": (sample["clip"] // 5) if sample.get("clip") is not None else None}
        return out

    # ---- references
    def reference(self, seq):
        idx = [i for i, f in enumerate(seq) if f[1] is not None]
        if len(idx) < 3:
            return None
        t0, t1 = seq[idx[0]][0] + self.cfg["EDGE_MS"], seq[idx[-1]][0] - self.cfg["EDGE_MS"]
        if t1 - t0 < 300:
            t0, t1 = seq[idx[0]][0], seq[idx[-1]][0]
        core = [f for f in seq[idx[0]: idx[-1] + 1] if t0 <= f[0] <= t1]
        times, D, N = grid(core, self.cfg["RATE_HZ"], self.cfg["GAP_MS"])
        f = stream_features(D, N, self.cfg)
        if f["pd"].sum() < 3:
            return None
        S = float(np.nanmedian(f["s"]))
        two = float(f["pn"][f["pd"]].mean()) >= 0.5
        meta = getattr(seq, "meta", None) or {}
        return {**f, "m": len(times), "pos": f["c"] / S, "two": two, "S": S, "signer": meta.get("signer"),
                "name": meta.get("name"), "D": D, "N": N}

    def _pack(self, refs, calibrate=True):
        """Stacks references (padded) for batched matching; with calibrate, also learns the tolerances."""
        key = tuple(id(r) for r in refs)
        if key in self._packs:
            return self._packs[key]
        R, M = len(refs), max(r["m"] for r in refs)
        P = {"refs": refs, "R": R, "M": M, "m": np.array([r["m"] for r in refs]),
             "two": sum(r["two"] for r in refs) * 2 > len(refs)}
        for k, shape, fill in (("q", 33, 0.0), ("u", 3, 0.0), ("n", 3, 0.0), ("g", 2, 0.0), ("r", 2, 0.0),
                               ("nu", 3, 0.0), ("nn", 3, 0.0), ("pos", 2, np.nan)):
            a = np.full((R, M, shape), fill)
            for i, r in enumerate(refs):
                a[i, :r["m"]] = r[k]
            P[k] = a
        for k in ("pd", "pn"):
            a = np.zeros((R, M), bool)
            for i, r in enumerate(refs):
                a[i, :r["m"]] = r[k]
            P[k] = a
        if calibrate:
            dists = {p: [] for p in PARAMS}
            for r in refs:
                others = [o for o in refs if o is not r and (r["signer"] is None or o["signer"] != r["signer"])]
                if not others:
                    continue
                po = self._pack(others, calibrate=False)
                po["two"] = P["two"]
                d = self._match(self._variants_of(r["D"], r["N"], swap=self.cfg["SWAP"]), po, final_only=True)
                if d is not None:
                    for p in PARAMS:
                        dists[p].append(d[p])
            P["tau"] = {}
            for p in PARAMS:
                v = [x for x in dists[p] if np.isfinite(x)]
                fl = (self.cfg["DIR_FLOOR"] if p == "direction" else FLOOR[p]) * self.cfg["FLOOR_SCALE"]
                P["tau"][p] = max(fl, float(np.median(v)) if v else fl)
            if not P["two"]:
                P["tau"]["two_hands"] = self.cfg["ONE_HAND_TOL"]
        self._packs[key] = P
        return P

    # ---- live
    def variants(self, seq):
        """Live: forget prep's slots (hands come unordered), track again causally, build the variants."""
        frames = []
        for f in seq:
            hs = [h for h in (f[1], f[2]) if h is not None]
            hs.sort(key=lambda h: h[0, 0])
            frames.append((f[0], hs))
        tr = track(frames, self.cfg["DUP_DIST"], self.cfg["STALE_MS"])
        times, A, B = grid(tr, self.cfg["RATE_HZ"], self.cfg["GAP_MS"])
        return times, self._variants_of(A, B, swap=self.cfg["SWAP"])

    def _variants_of(self, A, B, swap=False):
        """[(features, mirrored)] for (dominant, second) = (A, B) [and (B, A)], each as is [and mirrored]."""
        pairs = [(A, B)] + ([(B, A)] if swap else [])
        out = []
        for D, N in pairs:
            out.append((stream_features(D, N, self.cfg), False))
            if self.cfg["MIRROR"]:
                out.append((stream_features(flip(D), flip(N), self.cfg), True))
        return out

    def replay(self, refs, seq):
        res = self.score(refs, seq)
        return -1 if res is None else res["score"]

    def score(self, refs, seq):
        refs = [r for r in refs if r is not None]
        if not refs or not seq:
            return None
        P = self._pack(refs)
        times, vs = self.variants(seq)
        if len(times) < 3:
            return None
        return self._match(vs, P, final_only=False)

    # ---- matching core
    def _local(self, L, P):
        """Per-frame distances live L (n frames) x all references (R, M) -> dict of (R,n,M) arrays."""
        c = self.cfg
        dq = L["q"][None, :, None, :] - P["q"][:, None, :, :]
        hs = np.sqrt(np.einsum("rnmk,rnmk->rnm", dq, dq) / 33.0)
        du = L["u"][None, :, None, :] - P["u"][:, None, :, :]
        dn = L["n"][None, :, None, :] - P["n"][:, None, :, :]
        orr = np.sqrt((np.einsum("rnmk,rnmk->rnm", du, du) + np.einsum("rnmk,rnmk->rnm", dn, dn)) / 2)
        dg = L["g"][None, :, None, :] - P["g"][:, None, :, :]
        vel = np.sqrt(np.einsum("rnmk,rnmk->rnm", dg, dg))
        out = {"hs": hs, "or": orr}
        cost = hs / c["SIG_HS"] + orr / c["SIG_OR"] + vel / c["SIG_VEL"]
        if P["two"]:
            dr = L["r"][None, :, None, :] - P["r"][:, None, :, :]
            dnu = L["nu"][None, :, None, :] - P["nu"][:, None, :, :]
            dnn = L["nn"][None, :, None, :] - P["nn"][:, None, :, :]
            no = np.sqrt((np.einsum("rnmk,rnmk->rnm", dnu, dnu) + np.einsum("rnmk,rnmk->rnm", dnn, dnn)) / 2)
            rel = np.sqrt(np.einsum("rnmk,rnmk->rnm", dr, dr)) + 2.0 * no
            lpn = L["pn"][None, :, None]
            rpn = P["pn"][:, None, :]
            rel = np.where(lpn & rpn, rel, np.where(rpn, c["REL_MISS"], 0.0))
            out["rel"] = rel
            cost = cost + rel / c["SIG_REL"]
        lpd = L["pd"][None, :, None]
        rpd = P["pd"][:, None, :]
        miss = c["MISS"] + (np.where(P["pn"][:, None, :], c["REL_MISS"] / c["SIG_REL"], 0.0) if P["two"] else 0.0)
        cost = np.where(lpd, cost, miss)          # no dominant hand: worse than any one-handed attempt
        cost = np.where(rpd, cost, np.where(lpd, 1.0, 0.0))
        out["cost"] = cost
        return out

    def _dtw(self, C, start_ok, jmax):
        """Open-begin/open-end DTW over a batch of cost matrices C (B,n,M). Each reference frame is
        matched to 1..3 live frames, or two reference frames to one live frame; non-diagonal steps
        pay STEP_PEN per reference frame. Returns accumulated cost (sum over reference frames) and
        step choices (B,n,M)."""
        B, n, M = C.shape
        pen = self.cfg["STEP_PEN"]
        A = np.full((B, n, M), np.inf)
        ch = np.zeros((B, n, M), np.int8)
        A[:, :, 0] = C[:, :, 0]
        st = np.full((5, B, M - 1), np.inf)
        J = int(jmax.max()) if len(jmax) else 0
        jr = np.arange(1, M)
        skip_cost = np.where(jr[None, :] <= jmax[:, None], jr[None, :] * self.cfg["SKIP_PEN"], np.inf)  # (B,M-1)
        for i in range(1, n):
            st[:] = np.inf
            if J > 0:
                st[4] = np.where(start_ok[:, i, None], C[:, i, 1:] + skip_cost, np.inf)
            st[0] = A[:, i - 1, :-1] + C[:, i, 1:]
            if i >= 2:
                st[1] = A[:, i - 2, :-1] + (C[:, i - 1, 1:] + C[:, i, 1:]) / 2 + pen
            if i >= 3:
                st[2] = A[:, i - 3, :-1] + (C[:, i - 2, 1:] + C[:, i - 1, 1:] + C[:, i, 1:]) / 3 + 2 * pen
            st[3, :, 1:] = A[:, i - 1, :-2] + C[:, i, 1:-1] + C[:, i, 2:] + 2 * pen
            k = np.argmin(st, 0)
            A[:, i, 1:] = np.take_along_axis(st, k[None], 0)[0]
            ch[:, i, 1:] = k
        return A, ch

    @staticmethod
    def _path(ch, b, i, j):
        """Backtrack -> arrays (jj, ii, wt): reference frame, live frame, weight (1 / live frames of it)."""
        jj, ii, wt = [], [], []
        while j > 0:
            k = ch[b, i, j]
            if k == 4:                       # path starts here, reference frames < j unmatched
                jj += [j]; ii += [i]; wt += [1.0]
                return np.array(jj[::-1]), np.array(ii[::-1]), np.array(wt[::-1])
            if k == 0:
                jj += [j]; ii += [i]; wt += [1.0]
                i, j = i - 1, j - 1
            elif k == 1:
                jj += [j, j]; ii += [i - 1, i]; wt += [0.5, 0.5]
                i, j = i - 2, j - 1
            elif k == 2:
                jj += [j, j, j]; ii += [i - 2, i - 1, i]; wt += [1 / 3.0] * 3
                i, j = i - 3, j - 1
            else:
                jj += [j, j - 1]; ii += [i, i]; wt += [1.0, 1.0]
                i, j = i - 1, j - 2
        if j == 0:
            jj += [0]; ii += [i]; wt += [1.0]
        return np.array(jj[::-1]), np.array(ii[::-1]), np.array(wt[::-1])

    def _stage2(self, L, P, ri, loc, path):
        """Per-parameter distances along the alignment (+ per-frame details for explanations)."""
        jj, ii, wt = path
        m = int(P["m"][ri])
        bc = lambda v: np.bincount(jj, wt * v, minlength=m)          # noqa: E731  per reference frame
        lpd = L["pd"][ii]
        miss = np.bincount(jj, (~lpd).astype(float), minlength=m) > 0
        covered = np.bincount(jj, minlength=m) > 0
        okd = ~miss & P["pd"][ri, :m] & covered
        if okd.sum() < 2:
            return None
        hs = bc(loc["hs"][ri, ii, jj])
        orr = bc(loc["or"][ri, ii, jj])
        d = {"handshape": float(hs[okd].mean()), "orientation": float(orr[okd].mean())}
        # movement: palm-centre path in palm sizes, centred on its own mean, against the reference's
        S_l = float(np.median(L["s"][ii[lpd]]))
        cl = np.nan_to_num(L["c"][ii])
        Pl = np.stack([bc(cl[:, 0]), bc(cl[:, 1])], 1) / S_l
        Pr = P["pos"][ri, :m]
        Pl0 = Pl[okd] - Pl[okd].mean(0)
        Pr0 = Pr[okd] - Pr[okd].mean(0)
        err = np.linalg.norm(Pl0 - Pr0, axis=1)
        d["movement"] = float(np.sqrt(np.mean(err ** 2))) if self.cfg["MV_RMS"] else float(np.mean(err))
        rad_l = float(np.sqrt(np.mean(np.sum(Pl0 ** 2, 1))))
        rad_r = float(np.sqrt(np.mean(np.sum(Pr0 ** 2, 1))))
        d["_size_ratio"] = (rad_l + 0.1) / (rad_r + 0.1)
        d["size"] = abs(math.log(d["_size_ratio"]))
        lpn = L["pn"][ii]
        if P["two"]:
            relj = bc(loc["rel"][ri, ii, jj])
            wr = P["pn"][ri, :m] & okd
            d["two_hands"] = float(relj[wr].mean()) if wr.any() else 0.0
            d["_nd_seen"] = float(bc(lpn.astype(float))[wr].mean()) if wr.any() else 1.0
            d["_rel_err"] = None
            if wr.any():
                rl = bc(np.nan_to_num(L["r"][ii, 0]) * lpn), bc(np.nan_to_num(L["r"][ii, 1]) * lpn)
                seen = bc(lpn.astype(float))
                ok2 = wr & (seen > 0.5)
                if ok2.any():
                    rl = np.stack(rl, 1)[ok2] / seen[ok2, None]
                    d["_rel_err"] = (P["r"][ri, :m][ok2] - rl).mean(0)
        else:
            # one-handed sign: a second hand that moves along (two-handed attempt) is wrong,
            # a hand resting in view is not
            frac = float(np.sum(wt * lpn) / np.sum(wt))
            d["_nd_seen"] = frac
            if lpn.sum() >= 3:
                nc = L["nc"][ii[lpn]] / S_l
                nrad = float(np.sqrt(np.mean(np.sum((nc - nc.mean(0)) ** 2, 1))))
                d["two_hands"] = frac * nrad / max(rad_l, 0.5)
            else:
                d["two_hands"] = 0.0
        gl = np.nan_to_num(L["g"][ii])
        gr = np.nan_to_num(P["g"][ri, jj])
        dirj = bc(np.linalg.norm(gl - gr, axis=1))
        moving = okd & (np.hypot(P["g"][ri, :m, 0], P["g"][ri, :m, 1]) >= self.cfg["DIR_MIN_G"])
        d["direction"] = float(dirj[moving].mean()) if moving.any() else 0.0
        d["_present"] = float(1.0 - np.sum(wt * ~lpd) / np.sum(wt))
        d["_frames"] = {"m": m, "okd": okd, "handshape": hs, "orientation": orr, "movement": err, "direction": dirj,
                        "Pl0": Pl0, "Pr0": Pr0, "path": path}
        return d

    def _subscores(self, d, P):
        tau, K = P["tau"], self.cfg["K"]
        s = {}
        for p in PARAMS:
            if p == "direction" and not self.cfg["DIR_ON"]:
                continue
            x = d[p] / (K * tau[p])
            s[p] = 100.0 * (0.85 ** (x * x))
        return s

    def _match(self, vs, P, final_only):
        """vs: live variants [(features, mirrored)] (equal length n); P: packed references.
        final_only (calibration): per-parameter distances of the lowest-cost path.
        Otherwise emulate the live loop: every MATCH_EVERY grid frames, the TOP references with the
        lowest alignment cost over the new end frames are evaluated; best score over time."""
        n = len(vs[0][0]["pd"])
        R, M, m = P["R"], P["M"], P["m"]
        locs = [self._local(L, P) for L, _ in vs]
        V = len(vs)
        pdv = np.stack([L["pd"] for L, _ in vs])
        start_v = np.zeros_like(pdv)
        start_v[:, 1:] = pdv[:, 1:] & ~pdv[:, :-1]          # the hand has just come into the picture
        end_v = np.zeros_like(pdv)
        end_v[:, :-1] = pdv[:, :-1] & ~pdv[:, 1:]           # ... has just gone out of it
        mm = np.tile(m, V)
        jmax = np.minimum(int(round(self.cfg["SKIP_MS"] * self.cfg["RATE_HZ"] / 1000.0)), mm // 5)
        A, ch = self._dtw(np.concatenate([lc["cost"] for lc in locs], 0), np.repeat(start_v, R, 0), jmax)
        bb = np.arange(V * R)[:, None]
        ends = A[bb, np.arange(n)[None, :], (mm - 1)[:, None]]
        endj = np.repeat((mm - 1)[:, None], n, 1)
        end_ok = np.repeat(end_v, R, 0)
        for sk in range(1, int(jmax.max()) + 1 if len(jmax) else 1):
            ok = (sk <= jmax)[:, None] & end_ok
            e2 = A[bb, np.arange(n)[None, :], np.maximum(mm - 1 - sk, 0)[:, None]] + sk * self.cfg["SKIP_PEN"]
            better = ok & (e2 < ends)
            ends = np.where(better, e2, ends)
            endj = np.where(better, (mm - 1 - sk)[:, None], endj)
        ends = ends / mm[:, None]                                              # (B, n) mean cost
        if final_only:
            if not np.isfinite(ends).any():
                return None
            b, i = np.unravel_index(np.argmin(ends), ends.shape)
            v, r = divmod(int(b), R)
            return self._stage2(vs[v][0], P, r, locs[v], self._path(ch, b, i, endj[b, i]))
        step = self.cfg["MATCH_EVERY"]
        best, seen = None, {}
        per_ref = ends.reshape(len(vs), R, n).min(0)                           # best variant per (ref, end)
        var_of = ends.reshape(len(vs), R, n).argmin(0)
        for T in range(step - 1, n + step - 1, step):
            lo, hi = max(0, T - step + 1), min(n, T + 1)
            if lo >= hi:
                continue
            seg = per_ref[:, lo:hi]
            cost = seg.min(1)
            endi = seg.argmin(1) + lo
            for r in np.argsort(cost)[: self.cfg["TOP"]]:
                if not np.isfinite(cost[r]):
                    continue
                v = int(var_of[r, endi[r]])
                key = (v, int(r), int(endi[r]))
                if key not in seen:
                    d = self._stage2(vs[v][0], P, r, locs[v], self._path(ch, v * R + r, endi[r], endj[v * R + r, endi[r]]))
                    if d is None:
                        seen[key] = None
                    else:
                        sub = self._subscores(d, P)
                        sc = min(sub.values())
                        if d["_present"] < 0.7:
                            sc = min(sc, 50.0)
                        seen[key] = {"score": int(round(sc)), "sub": sub, "d": d, "ref": P["refs"][r].get("name"),
                                     "ri": int(r), "mirrored": vs[v][1], "variant": v, "end": int(endi[r]),
                                     "end_frac": float((endi[r] + 1) / n), "cost": float(cost[r])}
                res = seen[key]
                if res is not None and (best is None or res["score"] > best["score"]):
                    best = res
        return best

    # ---- explanations
    def explain(self, refs, seq):
        """Score + what to correct, in the four parameters' words."""
        res = self.score(refs, seq)
        if res is None:
            return {"score": -1, "messages": ["Show your hand to the camera and sign."]}
        P = self._pack([r for r in refs if r is not None])
        d, sub, fr = res["d"], res["sub"], res["d"]["_frames"]
        mir = res["mirrored"]
        ref = P["refs"][res["ri"]]
        m = fr["m"]
        passed = res["score"] >= 85
        msgs = ["Good! Your sign matches the reference signers."] if passed else []
        if d["_present"] < 0.7:
            msgs.append("Keep your hand inside the camera view for the whole sign.")
        order = sorted(sub, key=lambda p: sub[p])
        for n_p, p in enumerate(order[:2]):
            if passed and (n_p > 0 or sub[p] >= 92):
                break                      # passed: at most one tip, only for a weak-ish parameter
            if not passed and n_p > 0 and sub[p] >= 85:
                break                      # failed: the second correction only if it also fails
            j_all = np.where(fr["okd"])[0]
            if p in ("handshape", "orientation"):
                per = fr[p]
                j = int(j_all[np.argmax(per[j_all])])
                ph = _phase_word(j, m)
                jj, ii, wt = fr["path"]
                lives = ii[jj == j]
                if p == "handshape":
                    L = self._live_cache(seq, res)
                    ql = L["q"][lives].mean(0).reshape(11, 3)
                    qr = ref["q"][j].reshape(11, 3)
                    worst, gap = None, -1
                    for name, idx in FINGERS.items():
                        e = float(np.linalg.norm(ql[idx] - qr[idx]))
                        if e > gap:
                            worst, gap = name, e
                    tip = FINGERS[worst][-1]
                    if np.linalg.norm(ql[tip]) < np.linalg.norm(qr[tip]):
                        msgs.append("Hand shape %s: straighten your %s more." % (ph, worst))
                    else:
                        msgs.append("Hand shape %s: bend your %s more." % (ph, worst))
                else:
                    L = self._live_cache(seq, res)
                    du = np.linalg.norm(L["u"][lives].mean(0) - ref["u"][j])
                    dn = np.linalg.norm(L["n"][lives].mean(0) - ref["n"][j])
                    if dn >= du:
                        msgs.append("Palm direction %s: turn your palm to face %s." % (ph, _dir_word(-ref["n"][j], mir)))
                    else:
                        msgs.append("Hand direction %s: point your fingers %s." % (ph, _dir_word(ref["u"][j], mir)))
            elif p == "movement":
                e = fr["Pr0"] - fr["Pl0"]
                k = int(np.argmax(np.linalg.norm(e, axis=1)))
                j = int(j_all[k])
                lo, hi = max(0, k - 1), min(len(e), k + 2)
                v = e[lo:hi].mean(0)
                msgs.append("Movement %s: move your hand further %s." % (_phase_word(j, m),
                                                                         _dir_word(np.array([v[0], v[1], 0.0]), mir)))
            elif p == "direction":
                per = fr["direction"]
                j = int(j_all[np.argmax(per[j_all])])
                g = ref["g"][j]
                if np.hypot(g[0], g[1]) < 0.1:
                    msgs.append("Movement %s: keep your hand still." % _phase_word(j, m))
                else:
                    msgs.append("Movement %s: move your hand %s." % (_phase_word(j, m),
                                                                   _dir_word(np.array([g[0], g[1], 0.0]), mir)))
            elif p == "size":
                msgs.append("Movement: make the movement %s." % ("bigger" if d["_size_ratio"] < 1 else "smaller"))
            elif p == "two_hands":
                if P["two"]:
                    if d["_nd_seen"] < 0.5:
                        msgs.append("This sign uses both hands: keep both hands in view.")
                    elif d.get("_rel_err") is not None:
                        msgs.append("Second hand: move it further %s (compared with your other hand)." %
                                    _dir_word(np.array([*d["_rel_err"], 0.0]), mir))
                    else:
                        msgs.append("Second hand: copy its shape and direction from the video.")
                else:
                    msgs.append("This sign uses one hand: keep the other hand still.")
        good = [p for p in PARAMS if sub[p] >= 92 and p not in ("size", "direction") and (p != "two_hands" or P["two"])]
        if not passed and good:
            words = {"handshape": "hand shape", "orientation": "palm and finger direction", "movement": "movement path",
                     "two_hands": "second hand"}
            msgs.insert(0, "Your %s %s right." % (" and ".join(words[p] for p in good[:2]), "are" if len(good[:2]) > 1 else "is"))
        if not msgs:
            msgs.append("Almost: watch the video once more and repeat the whole sign.")
        return {"score": res["score"], "sub": {p: int(round(v)) for p, v in sub.items()}, "messages": msgs,
                "good": good, "mirrored": mir, "ref": res["ref"]}

    def _live_cache(self, seq, res):
        times, vs = self.variants(seq)
        return vs[res["variant"]][0]
