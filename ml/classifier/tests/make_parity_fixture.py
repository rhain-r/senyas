"""Writes the fixture that both feature encoders (Python and Java) are tested against.

    python tests/make_parity_fixture.py

Covers: empty frames at both ends and in the middle, uneven frame timing, two hands with the
same handedness label, a left hand alone, and a degenerate hand (zero size).
"""
import json
import sys
from pathlib import Path

import numpy as np

ML = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ML))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from senyas_ml import features  # noqa: E402
import synthetic  # noqa: E402

OUT = ML.parents[1] / "android" / "app" / "src" / "test" / "resources" / "cv" / "parity_fixture.json"


def main():
    rng = np.random.default_rng(42)
    frames = []
    t = 0
    for i in range(40):
        t += int(rng.integers(28, 39))
        hands = []
        if 3 <= i <= 36 and i != 20:
            a = synthetic.place(synthetic.SHAPES["open"], 0.45 + 0.004 * i, 0.6, 0.1, rng)
            hands.append({"label": "Right", "score": 0.96, "lm": a.reshape(-1).round(6).tolist()})
            if i % 3 == 0:
                b = synthetic.place(synthetic.SHAPES["vee"], 0.25, 0.62, 0.09, rng)
                label = "Right" if i % 2 == 0 else "Left"  # sometimes a clash
                hands.append({"label": label, "score": 0.91, "lm": b.reshape(-1).round(6).tolist()})
        if i == 10:
            hands = [{"label": "Left", "score": 0.8, "lm": synthetic.place(synthetic.SHAPES["fist"], 0.5, 0.5, 0.1, rng).reshape(-1).round(6).tolist()}]
        if i == 11:
            flat = np.full((21, 3), 0.5, np.float32)
            hands = [{"label": "Right", "score": 0.7, "lm": flat.reshape(-1).tolist()}]
        frames.append({"t": t, "hands": hands})

    data = {
        "featureVersion": features.VERSION,
        "frames": frames,
        "frameFeatures": [features.encode_frame(f).tolist() for f in frames],
        "sequence": features.encode_sequence(frames, features.SEQUENCE_LENGTH).tolist(),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data), encoding="utf-8")
    print(f"Wrote {OUT}")


if __name__ == "__main__":
    main()
