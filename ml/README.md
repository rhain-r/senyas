# Sign model training

These Python scripts turn the FSL-105 video dataset into the small model the app uses to recognise signs, `android/app/src/main/assets/fsl_recognizer.bin`. The app already ships with a trained model, so you only need this folder to retrain it or to check how it was made.

## How the model is made

| Step | Script | What it does | Output |
| --- | --- | --- | --- |
| 1 | `extract_features.py` | Runs MediaPipe on every clip at about 8 frames per second and saves 156 numbers per frame describing the hands and upper body. Videos are unpacked one at a time and deleted afterwards. | `cache/` |
| 2 | `train.py` | Trains a neural network with two hidden layers (128 and 64 units) on all 105 signs. Picks the confidence each sign needs before it is accepted. | `runs/<name>/` |
| 3 | `evaluate_episodes.py` | Replays clips as complete attempts, the way the app sees them, switches off signs that are not reliable enough, and writes the model file the app ships with. | `runs/episodes/` |

`features.py` holds the shared feature code, and `test_contract.py` checks it. `evaluate_live_hold.py` tests an alternative rule (two accepted predictions in a row); its output in `runs/shipping/` is not used by the app.

Training uses all 105 signs, not just the ones taught in the app. The other signs act as competition: a learner's attempt only counts if it looks more like the requested sign than like any of the other 104.

## Data

FSL-105 by Isaiah Jassen Tupal, Mendeley Data dataset `48y2y99mb9`, version 2, CC BY 4.0: <https://data.mendeley.com/datasets/48y2y99mb9/2>. It has 2,130 four-second clips of 105 signs.

`train.csv` and `test.csv` are the dataset's official split and are kept here unchanged. Download the clips separately as `clips.zip` from the same page. Its SHA-256 is `c048c0820be1621101dfedeabb76a9fbfca76ed2868eebe23b4cb90d5c748463`.

The split is made per clip, and the dataset has no signer IDs. Results therefore must not be described as performance on unseen signers.

## Reproduce

Use Python 3.12. From the repository root on Windows:

```powershell
python -m venv ml/.venv
ml/.venv/Scripts/python.exe -m pip install -r ml/requirements.txt
ml/.venv/Scripts/python.exe ml/test_contract.py
ml/.venv/Scripts/python.exe ml/extract_features.py --zip path/to/clips.zip --workers 4 --fps 8
ml/.venv/Scripts/python.exe ml/train.py --name baseline
ml/.venv/Scripts/python.exe ml/evaluate_episodes.py
```

On macOS or Linux, use `ml/.venv/bin/python` instead. Then copy `ml/runs/episodes/fsl_recognizer.bin` and `ml/runs/episodes/report.json` into `android/app/src/main/assets/` as `fsl_recognizer.bin` and `fsl_recognizer_report.json`.

The extractor reuses the MediaPipe models bundled with the app. It uses CPU tasks with detection, presence and tracking thresholds of 0.35, and shrinks images to 640 pixels on the long side. Re-running it continues from the files already in `cache/`.

`cache/` and `runs/` are not stored in git; the steps above recreate them.

## How thresholds are chosen

`train.py` sets aside 20% of the **official training split** (stratified, seed 291) before any augmentation. Only these calibration clips are used to pick the best epoch, the temperature, each sign's acceptance threshold and its winning margin over the runner-up. The official test set is never used for training or calibration. Add `--evaluate-test` only to the chosen run to produce the final test numbers.

A sign is enabled only if at least two calibration clips are recognised with no wrong clip accepted. The release check also requires at least two recognised official test clips with no wrong acceptances. Signs that fail are switched off in the app.

The shipped model enables GOOD MORNING, HELLO, THANK YOU, YOURE WELCOME, NO, YES and FATHER. GOOD EVENING and MOTHER are switched off; their videos stay in the app.

The rule for a completed attempt was developed after inspecting early streaming test failures. Treat the results as exploratory development numbers, not as an untouched benchmark. Performance for real users and for unknown moving gestures still needs testing on phones.

## Feature contract v1

The Java code in `SignFeatures.java` must produce exactly the same numbers. Golden fixtures check both the features and the model's float32 probabilities.

Each frame contains 156 float32 values. Convert normalized `(x,y,z)` into image-height units `(x*width/height,y,z*width/height)`.

Body origin is the midpoint of shoulders 11 and 12. Body scale is their 2D distance. Both shoulder visibilities must be at least 0.3 and distance at least 0.05 for a valid body. When invalid, use origin `(0.5*aspect,0.5)` and scale `0.3*aspect` for finite hand features; body features and presence remain zero.

Sort detected hands by wrist x, ascending; a single hand always occupies the first slot. Each 67-value hand slot contains 21 wrist-relative xyz triples, divided by mean 3D wrist-to-MCP distance for indices 5,9,13,17 (minimum 0.0001); wrist body-relative x,y; palm scale/body scale; presence 1. Missing slots are all zero.

The final 22 values are pose joints `[0,11,12,13,14,15,16]`, each containing body-relative x,y and visibility, followed by body presence. Missing or invalid body is all zero.

Trim each sequence to first through last hand-present frame, inclusive. Resample linearly at 16 equally spaced times between those endpoints, preserving endpoints. Flatten in frame-major order to 2,496 values. Mirror augmentation negates local and body-relative x, exchanges hand slots only when both are present, and exchanges left/right pose joints. Mirroring occurs after the training/calibration split.

Motion is RMS of consecutive resampled hand features 0..65 and 67..132. Include a hand slot only when that slot is present at both consecutive frames. Presence and pose features do not count as motion. Static sequences have zero motion.

## Model file format

Little-endian `FSL1`, then int32 frame count (16), feature count (156), layer count (3). For each layer: int32 input count, int32 output count, float32 weights in row-major `[input,output]` order, float32 output biases. Hidden layers use ReLU. Final logits use softmax after division by the appended float32 temperature.

Then follow 105 float32 class thresholds, 105 float32 top-two probability margins, int32 minimum duration milliseconds, int32 maximum duration milliseconds, int32 minimum hand frames, float32 minimum motion, and int32 minimum body frames. Class output indices equal original dataset IDs 0..104. Thresholds above 1 disable a class. Runtime gates, chosen thresholds and evidence limits are recorded in the model report.
