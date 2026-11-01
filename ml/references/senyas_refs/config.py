"""Default file locations of the reference pipeline.

Every script accepts explicit paths; these defaults apply only when a path is left out.
Inputs live under the data root ($SENYAS_DATA, default ml/references/data).
Outputs go under the output root ($SENYAS_OUT, default ml/references/out).
"""
import os

PACKAGE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Names inside the data root (inputs).
CLIPS_ZIP = "clips.zip"                 # FSL-105 download
CLIPS_DIR = "clips"                     # unpacked clips, CCC_KK.mov
LANDMARKS_DIR = "landmarks"             # tracked clips, CCC_KK.json
MODEL = "hand_landmarker.task"          # MediaPipe hand model (the app's copy)

# Names inside the output root.
REFS_DIR = "sign_refs"                  # -> app/src/main/assets/sign_refs/
VIDEOS_DIR = os.path.join("signs", "fsl105")   # -> app/src/main/assets/signs/fsl105/
PARITY_JSON = "scorer_parity.json"      # -> app/src/test/resources/scorer_parity.json
EVAL_DIR = "eval"                       # leave-one-signer-out results
STUDY_DIR = "threshold_study"           # pass-threshold probes and tables


def data_root():
    return os.environ.get("SENYAS_DATA") or os.path.join(PACKAGE_ROOT, "data")


def out_root():
    return os.environ.get("SENYAS_OUT") or os.path.join(PACKAGE_ROOT, "out")


def data(*parts):
    return os.path.join(data_root(), *parts)


def out(*parts):
    return os.path.join(out_root(), *parts)
