"""Feature spec v1: MediaPipe hand landmarks -> the numbers the sign classifier reads.

Must match app/src/main/java/com/senyas/cv/FeatureEncoder.java exactly. Both sides are checked
against the same fixture (tests/test_features.py and FeatureEncoderParityTest.java). If you change
anything here, change the Java side too and bump VERSION.

Per frame: 132 numbers = two hand slots of 66. Slot 0 is the hand MediaPipe labels "Right",
slot 1 the hand it labels "Left". For a present hand:
  0..62  the 21 landmarks relative to the wrist, divided by hand size
         (hand size = wrist to middle-finger knuckle, in image units)
  63, 64 wrist position in the image, centred (x - 0.5, y - 0.5)
  65     hand size
A missing hand is all zeros.

Per sign: frames without hands are trimmed from both ends, and the rest is resampled to
SEQUENCE_LENGTH frames evenly spaced in time (nearest frame, ties go to the later frame).
"""
import math

import numpy as np

VERSION = 1
SEQUENCE_LENGTH = 32
PER_HAND = 66
FEATURES = PER_HAND * 2
WRIST = 0
MIDDLE_MCP = 9


def assign_slots(hands):
    """Slot 0 for "Right", slot 1 for "Left"; on a clash the more confident hand keeps its slot."""
    slots = [None, None]
    for h in sorted(hands, key=lambda h: -float(h["score"])):
        preferred = 0 if h["label"] == "Right" else 1
        if slots[preferred] is None:
            slots[preferred] = h
        elif slots[1 - preferred] is None:
            slots[1 - preferred] = h
    return slots


def encode_hand(lm):
    lm = np.asarray(lm, dtype=np.float32).reshape(21, 3)
    x0, y0 = lm[WRIST, 0], lm[WRIST, 1]
    dx = np.float32(lm[MIDDLE_MCP, 0] - x0)
    dy = np.float32(lm[MIDDLE_MCP, 1] - y0)
    size = np.float32(math.sqrt(float(np.float32(dx * dx + dy * dy))))
    if size < 1e-6:
        size = np.float32(1.0)
    out = np.empty(PER_HAND, dtype=np.float32)
    out[:63] = ((lm - lm[WRIST]) / size).reshape(-1)
    out[63] = x0 - np.float32(0.5)
    out[64] = y0 - np.float32(0.5)
    out[65] = size
    return out


def encode_frame(frame):
    out = np.zeros(FEATURES, dtype=np.float32)
    for s, hand in enumerate(assign_slots(frame["hands"])):
        if hand is not None:
            out[s * PER_HAND:(s + 1) * PER_HAND] = encode_hand(hand["lm"])
    return out


def encode_sequence(frames, length=SEQUENCE_LENGTH):
    """frames: list of {"t": ms, "hands": [...]}. Returns (length, 132) float32 or None."""
    with_hands = [i for i, f in enumerate(frames) if f["hands"]]
    if not with_hands or with_hands[-1] - with_hands[0] < 1:
        return None
    first, last = with_hands[0], with_hands[-1]
    t0, t1 = float(frames[first]["t"]), float(frames[last]["t"])
    out = np.empty((length, FEATURES), dtype=np.float32)
    j = first
    for k in range(length):
        target = t0 + (t1 - t0) * k / (length - 1)
        while j < last and abs(frames[j + 1]["t"] - target) <= abs(frames[j]["t"] - target):
            j += 1
        out[k] = encode_frame(frames[j])
    return out


def hand_presence(frames):
    if not frames:
        return 0.0
    return sum(1 for f in frames if f["hands"]) / len(frames)


def mirror(seq):
    """The same sign done with the other hand (or a mirrored video): swap slots, flip x."""
    seq = np.array(seq, dtype=np.float32, copy=True)
    a = seq[:, :PER_HAND].copy()
    seq[:, :PER_HAND] = seq[:, PER_HAND:]
    seq[:, PER_HAND:] = a
    for off in (0, PER_HAND):
        seq[:, off:off + 63:3] *= -1
        seq[:, off + 63] *= -1
    return seq
