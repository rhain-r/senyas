"""Training-time variations so the model copes with new signers, cameras and lighting.

Each function takes and returns one encoded sign of shape (SEQUENCE_LENGTH, 132).
"""
import numpy as np

from .features import PER_HAND, mirror


def _hands(seq):
    """Rows where each slot holds a hand (all-zero means missing)."""
    return [np.any(seq[:, off:off + PER_HAND] != 0, axis=1) for off in (0, PER_HAND)]


def rotate_scale(seq, rng, max_deg=12.0, scale=(0.9, 1.1)):
    """Small in-plane rotation and size change of the hand shapes (camera tilt, hand size)."""
    out = seq.copy()
    a = np.deg2rad(rng.uniform(-max_deg, max_deg))
    c, s = np.cos(a), np.sin(a)
    k = rng.uniform(*scale)
    present = _hands(seq)
    for slot, off in enumerate((0, PER_HAND)):
        rows = present[slot]
        xy = out[rows, off:off + 63].reshape(-1, 21, 3)
        x, y = xy[..., 0].copy(), xy[..., 1].copy()
        xy[..., 0] = (c * x - s * y) * k
        xy[..., 1] = (s * x + c * y) * k
        xy[..., 2] *= k
        out[rows, off:off + 63] = xy.reshape(-1, 63)
    return out


def shift(seq, rng, max_shift=0.06):
    """Signer standing a little left/right/up/down in the frame."""
    out = seq.copy()
    dx, dy = rng.uniform(-max_shift, max_shift, size=2)
    present = _hands(seq)
    for slot, off in enumerate((0, PER_HAND)):
        rows = present[slot]
        out[rows, off + 63] += dx
        out[rows, off + 64] += dy
    return out


def jitter(seq, rng, sigma=0.02):
    """Landmark noise, as from a cheaper camera or poor light."""
    out = seq.copy()
    present = _hands(seq)
    for slot, off in enumerate((0, PER_HAND)):
        rows = present[slot]
        out[rows, off:off + 63] += rng.normal(0, sigma, size=(int(rows.sum()), 63)).astype(np.float32)
    return out


def time_crop(seq, rng, keep=(0.8, 1.0)):
    """Starting or stopping a little early/late, then stretched back to full length."""
    n = len(seq)
    k = max(2, int(round(n * rng.uniform(*keep))))
    start = rng.integers(0, n - k + 1)
    idx = np.round(np.linspace(start, start + k - 1, n)).astype(int)
    return seq[idx]


def augment(seq, rng):
    out = seq
    if rng.random() < 0.5:
        out = mirror(out)
    out = time_crop(out, rng)
    out = rotate_scale(out, rng)
    out = shift(out, rng)
    out = jitter(out, rng)
    return out.astype(np.float32)
