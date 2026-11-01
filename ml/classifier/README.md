# Senyas sign recognition (computer vision)

How Senyas reads Filipino Sign Language from the camera, and how to train it with free data.
This matches proposal sections 8 (Machine Learning Integration) and 10 (Custom-trained ML Model).

## How it works, step by step

```
front camera (CameraX)
   │  every frame, about 15–30 per second
   ▼
MediaPipe Hand Landmarker  ── on the phone, free, up to 2 hands × 21 keypoints (x, y, z)
   │
   ▼
FeatureEncoder  ── each frame becomes 132 numbers:
   │               hand shape relative to the wrist, wrist position, hand size, per hand
   │               the last 2.5 s are trimmed and resampled to 32 frames
   ▼
Sign classifier  ── which sign is it, with what confidence?
   │   • TfliteSignClassifier: the team's trained TensorFlow Lite model (train.py)
   │   • TemplateSignClassifier: compares against recordings made on the phone (no training)
   ▼
SignRecognizer  ── reads 4× per second; only names a sign when 3 of the last 4 readings agree
   ▼
Screen: sign name, confidence bar, top 3 guesses
Practice: score 0–100, fingers in green/red, a correction arrow and a written hint
          ("Move your hand slightly higher.", "Straighten your index finger.")
          Best score is saved as the learner's Gesture Accuracy Score in Firestore.
```

The code lives in `android/app/src/main/java/com/senyas/cv/` (camera, tracking, recognition) and
`android/app/src/main/java/com/senyas/ml/` (the practice scorer). Open the app and you land in
**Sign Lab**, which has three modes:

| Mode | What it does |
| --- | --- |
| Recognize | Shows which sign you are making. |
| Practice | Pick a sign. You get a score, green/red fingers, an arrow and a written correction. "Save best score" stores it in your practice history. |
| Record | Saves takes of a sign. The phone can recognise them immediately, and **Export** zips them as training data. |

### What it can and cannot do (proposal 4.2)

- It recognises **distinct vocabulary signs and handshapes**, including two-handed signs and movement.
- It does **not** read facial expressions or full sentences, and it is not a translator.
- Accuracy depends on data: aim for many signers per sign. The proposal's target is **85%**;
  `train.py` reports whether the trained model meets it.

## Where to get FSL data (all free)

| Source | What it is | Licence | How to use it |
| --- | --- | --- | --- |
| **FSL-105** (De La Salle University) — [Mendeley Data](https://data.mendeley.com/datasets/48y2y99mb9/2) | 2,130 four-second videos of **105 everyday FSL signs** (greetings, numbers, days, family, colours, food…), chosen by an FSL expert, with train/test CSV files and a labels file | CC BY 4.0 (credit the authors) | Best starting point. Steps below. |
| FSL-105 mirror — [Hugging Face (SEACrowd)](https://huggingface.co/datasets/SEACrowd/fsl_105) | Same dataset, loader script | CC BY 4.0 | Alternative download. |
| **Your own recordings** — Sign Lab → Record → Export | Landmarks of any sign you need (e.g. *salamat*, *bayad*, *para*), recorded by Deaf tutors | Yours | Best for the sari-sari, jeepney and public-service signs. Ask signers for consent; only landmarks are stored, never video. |
| Roboflow Universe FSL projects, e.g. [FSL](https://universe.roboflow.com/madds/fsl-kyyjj) | Photos of FSL handshapes (mostly the alphabet) | Check each project's licence | Static handshapes only; useful later for a fingerspelling feature. |

Not free or not usable: copying videos from FSL apps or YouTube without permission. RA 11106 makes
FSL the national sign language of Filipino Deaf people, so check signs with a Deaf FSL educator
before teaching them in the app.

## Train a model (free: your laptop or Google Colab)

Needs Python 3.10–3.12. On Colab, upload this `ml` folder and run the same commands with `!` in front.

```bash
cd ml/classifier
python -m venv .venv
.venv\Scripts\activate          # Windows (macOS/Linux: source .venv/bin/activate)
pip install -r requirements.txt
```

1. **Download FSL-105** from the Mendeley page above and unzip it, e.g. to `C:\data\FSL-105`.

2. **Turn the videos into landmarks.** This is the slow step, roughly 30–60 minutes on a laptop; `--every 2` halves it:

   ```bash
   python extract_landmarks.py --csv C:\data\FSL-105\train.csv --root C:\data\FSL-105 --labels C:\data\FSL-105\labels.csv --out data/fsl105/train --every 2
   python extract_landmarks.py --csv C:\data\FSL-105\test.csv  --root C:\data\FSL-105 --labels C:\data\FSL-105\labels.csv --out data/fsl105/test  --every 2
   ```

   The script finds the clip-path and label columns itself and prints which it used. If it
   guesses wrong, pass `--path-col` and `--label-col`. For your own videos, use one folder per
   sign instead: `python extract_landmarks.py --videos my_videos --out data/mine`.

3. **Add phone recordings (optional).** Unzip Sign Lab exports into `data/phone`.

4. **Train, check accuracy, and install into the app:**

   ```bash
   python train.py --data data/fsl105/train data/phone --test-data data/fsl105/test --install
   ```

   This prints the test accuracy against the 85% target, writes `out/report.txt` (weakest signs,
   most-confused pairs), and copies `fsl_model.tflite`, `fsl_labels.txt` and `fsl_model.json` into
   `android/app/src/main/assets`. Rebuild the app in Android Studio; Sign Lab then says
   "Trained model (N signs)".

### Tests

```bash
python -m unittest discover -s tests -v
```

This checks the feature encoder against the shared fixture and trains a tiny model on made-up
motions to prove that extraction, training and export fit together. The Android side checks the
same fixture in `FeatureEncoderParityTest`. If you change the features, change
`senyas_ml/features.py` **and** `FeatureEncoder.java`, bump `VERSION` in both, then run
`python tests/make_parity_fixture.py`.

## Cost

Everything above is free: MediaPipe, TensorFlow/LiteRT and OpenCV (open source), FSL-105
(CC BY 4.0), Google Colab's free tier, and Firebase's Spark plan for Firestore and Authentication.
Recognition runs on the phone, so there is no server or per-use charge.
