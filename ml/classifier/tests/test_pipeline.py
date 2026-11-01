"""Checks the Python half of the pipeline. Run from the ml folder:

    python -m unittest discover -s tests -v

test_train_and_export trains on made-up signs (takes about a minute) to prove that samples,
features, training and TensorFlow Lite export fit together.
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

ML = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ML))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from senyas_ml import features  # noqa: E402
from senyas_ml.augment import augment  # noqa: E402
from senyas_ml.samples import iter_samples, normalize_label, save_sample  # noqa: E402
import synthetic  # noqa: E402

FIXTURE = ML.parents[1] / "android" / "app" / "src" / "test" / "resources" / "cv" / "parity_fixture.json"


class FeatureTest(unittest.TestCase):
    def test_parity_fixture_matches(self):
        """The fixture the Java test also reads: both encoders must produce these numbers."""
        data = json.loads(FIXTURE.read_text(encoding="utf-8"))
        for frame, expected in zip(data["frames"], data["frameFeatures"]):
            np.testing.assert_allclose(features.encode_frame(frame), expected, atol=1e-6)
        seq = features.encode_sequence(data["frames"], features.SEQUENCE_LENGTH)
        np.testing.assert_allclose(seq, data["sequence"], atol=1e-6)

    def test_hands_are_put_in_fixed_slots(self):
        lm = synthetic.place(synthetic.SHAPES["open"], 0.5, 0.6, 0.1, np.random.default_rng(1)).reshape(-1).tolist()
        only_left = features.encode_frame({"hands": [{"label": "Left", "score": 0.9, "lm": lm}]})
        self.assertTrue(np.all(only_left[:66] == 0))
        self.assertTrue(np.any(only_left[66:] != 0))

    def test_mirror_twice_is_identity(self):
        rng = np.random.default_rng(2)
        seq = features.encode_sequence(synthetic.make_sample("two_open", rng))
        np.testing.assert_allclose(features.mirror(features.mirror(seq)), seq, atol=1e-6)

    def test_trims_and_resamples(self):
        rng = np.random.default_rng(3)
        frames = [{"t": 0, "hands": []}] + synthetic.make_sample("wave", rng)
        for f in frames[1:]:
            f["t"] += 40
        seq = features.encode_sequence(frames)
        self.assertEqual(seq.shape, (features.SEQUENCE_LENGTH, features.FEATURES))
        self.assertTrue(np.all(np.any(seq != 0, axis=1)), "every resampled frame should hold a hand")
        self.assertIsNone(features.encode_sequence([{"t": 0, "hands": []}]))

    def test_augment_keeps_shape(self):
        rng = np.random.default_rng(4)
        seq = features.encode_sequence(synthetic.make_sample("vee_circle", rng))
        self.assertEqual(augment(seq, rng).shape, seq.shape)

    def test_labels_match_the_app(self):
        self.assertEqual(normalize_label("  Good Morning! "), "good_morning")
        self.assertEqual(normalize_label("Salamat"), "salamat")


class TrainTest(unittest.TestCase):
    def test_train_and_export(self):
        import train

        rng = np.random.default_rng(5)
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp) / "samples"
            for name in synthetic.SIGNS:
                for i in range(30):
                    save_sample(data / name / f"{i}.json", name, "synthetic", synthetic.make_sample(name, rng))
            self.assertEqual(sum(1 for _ in iter_samples(data)), 30 * len(synthetic.SIGNS))
            out = Path(tmp) / "out"
            acc = train.main(["--data", str(data), "--out", str(out), "--epochs", "40", "--batch", "32"])
            self.assertGreaterEqual(acc, 0.9)
            info = json.loads((out / "fsl_model.json").read_text())
            self.assertEqual(info["featureVersion"], features.VERSION)
            self.assertEqual((out / "fsl_labels.txt").read_text().split(), sorted(synthetic.SIGNS))
            self.assertLess((out / "fsl_model.tflite").stat().st_size, 1_000_000)


if __name__ == "__main__":
    unittest.main()
