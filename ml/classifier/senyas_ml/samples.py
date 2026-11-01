"""Read and write sign samples: the JSON format shared with the Senyas app's Record mode.

{"label": "salamat", "source": "...", "featureVersion": 1,
 "frames": [{"t": 0, "hands": [{"label": "Right", "score": 0.98, "lm": [63 numbers]}]}]}
"""
import json
import re
from pathlib import Path


def normalize_label(raw):
    """Same rule as SignSample.normalizeLabel: "Good morning" -> "good_morning"."""
    return re.sub(r"[\W_]+", "_", str(raw).strip().lower()).strip("_")


def save_sample(path, label, source, frames):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    data = {"label": normalize_label(label), "source": source, "featureVersion": 1, "frames": frames}
    path.write_text(json.dumps(data, separators=(",", ":")), encoding="utf-8")


def load_sample(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def iter_samples(*roots):
    """Every *.json sample under the given folders (phone exports and extracted videos alike)."""
    for root in roots:
        for p in sorted(Path(root).rglob("*.json")):
            try:
                s = load_sample(p)
            except (OSError, ValueError):
                continue
            if isinstance(s, dict) and "label" in s and "frames" in s:
                s["_path"] = str(p)
                yield s
