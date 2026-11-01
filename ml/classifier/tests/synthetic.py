"""Made-up "signs" for testing the pipeline end to end without a real dataset.

These are simple hand shapes and motions, not FSL. They only prove that extraction format,
features, training and export fit together.
"""
import math

import numpy as np


def hand_shape(open_fingers):
    """21 landmarks in hand-size units (wrist at 0,0; y grows downward like image coords)."""
    p = np.zeros((21, 3), np.float32)
    p[1] = (-0.35, -0.25, 0)
    p[2] = (-0.6, -0.5, 0)
    p[3] = (-0.8, -0.75, 0)
    p[4] = (-0.95, -1.0, 0) if open_fingers[0] else (-0.3, -0.9, 0)
    for f in range(1, 5):
        x = -0.3 + 0.2 * (f - 1)
        o = open_fingers[f]
        base = 1 + f * 4
        p[base] = (x, -1.0, 0)
        p[base + 1] = (x, -1.5 if o else -1.3, 0)
        p[base + 2] = (x, -1.9 if o else -1.0, 0)
        p[base + 3] = (x, -2.2 if o else -0.8, 0)
    return p


SHAPES = {
    "open": hand_shape([1, 1, 1, 1, 1]),
    "fist": hand_shape([0, 0, 0, 0, 0]),
    "point": hand_shape([0, 1, 0, 0, 0]),
    "vee": hand_shape([0, 1, 1, 0, 0]),
}

# name -> (shape, second hand?, motion(u) -> (dx, dy)) with u running 0..1 over the sign
SIGNS = {
    "wave": ("open", False, lambda u: (0.12 * math.sin(u * 4 * math.pi), 0.0)),
    "fist_down": ("fist", False, lambda u: (0.0, 0.25 * u)),
    "point_hold": ("point", False, lambda u: (0.0, 0.0)),
    "two_open": ("open", True, lambda u: (0.15 * u, 0.0)),
    "vee_circle": ("vee", False, lambda u: (0.1 * math.cos(u * 2 * math.pi), 0.1 * math.sin(u * 2 * math.pi))),
}


def place(shape, cx, cy, size, rng, noise=0.004):
    pts = shape * size
    pts[:, 0] += cx
    pts[:, 1] += cy
    pts[:, :2] += rng.normal(0, noise, size=(21, 2))
    return pts


def make_sample(name, rng):
    shape_name, two_hands, motion = SIGNS[name]
    shape = SHAPES[shape_name]
    fps = rng.choice([24, 30])
    duration = rng.uniform(1.2, 2.4)
    n = int(duration * fps)
    lead, tail = rng.integers(0, 6), rng.integers(0, 6)
    cx, cy = rng.uniform(0.4, 0.6), rng.uniform(0.55, 0.7)
    size = rng.uniform(0.08, 0.12)
    frames = []
    for i in range(lead + n + tail):
        t = int(round(i * 1000 / fps))
        hands = []
        if lead <= i < lead + n:
            u = (i - lead) / max(1, n - 1)
            dx, dy = motion(u)
            right = place(shape, cx + dx, cy + dy, size, rng)
            hands.append({"label": "Right", "score": 0.97, "lm": right.reshape(-1).round(6).tolist()})
            if two_hands:
                left = place(shape * np.array([-1, 1, 1], np.float32), cx - 0.25 - dx, cy + dy, size, rng)
                hands.append({"label": "Left", "score": 0.95, "lm": left.reshape(-1).round(6).tolist()})
        frames.append({"t": t, "hands": hands})
    return frames
