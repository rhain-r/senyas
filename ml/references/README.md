# Senyas Sign References: FSL-105 Reference Pipeline

This folder holds the Python pipeline that builds the built-in sign references of the Senyas app.
Senyas is a Filipino Sign Language (FSL) learning app; its Practice Room scores a learner's signing against these references.

## Contents

1. [Purpose](#1-purpose)
2. [Dataset](#2-dataset)
3. [Folder layout](#3-folder-layout)
4. [Setup](#4-setup)
5. [Pipeline](#5-pipeline)
6. [Scoring method](#6-scoring-method)
7. [Pass thresholds (passK)](#7-pass-thresholds-passk)
8. [Evaluation protocol](#8-evaluation-protocol)
9. [Results](#9-results)
10. [Outputs and app files](#10-outputs-and-app-files)
11. [Reproducibility](#11-reproducibility)
12. [Limitations](#12-limitations)
13. [References and license](#13-references-and-license)

## 1. Purpose

Learners never record their own reference signs; the app ships references tracked from a public FSL dataset.
This pipeline makes those references, the demonstration videos, the per-sign pass thresholds and the test fixtures of the app's Java scorer.

- It turns FSL-105 video clips into hand-landmark sequences with the same MediaPipe model the app uses.
- It defines the scoring design that the app's `SignScorer.java` reproduces, and it measures that design.

## 2. Dataset

The references come from FSL-105 by I. J. Tupal, De La Salle University, licensed CC BY 4.0 ([doi.org/10.17632/48y2y99mb9.2](https://doi.org/10.17632/48y2y99mb9.2)).
It has 105 signs, each performed by 4 signers 5 times, as 640x360 videos at 60 fps.

- The release (`clips.zip`, about 380 MB) has 2,235 clips; most signs have 20, a few have 18-22.
- Clip `clips/<class>/<k>.MOV` is take `k`; signer = `k // 5` (0-4 A, 5-9 B, 10-14 C, 15-19 D).
- Extra takes 20-21 belong to no signer fold, and no step uses them.

**How to get it.** Download `clips.zip` from the Mendeley Data record above and save it as `data/clips.zip`.
The dataset is not stored in this repository; only the 26 re-encoded demonstration videos ship inside the app, with attribution.

The app uses 26 of the 105 signs:

| App sign | FSL-105 class | App sign | FSL-105 class |
|---|---|---|---|
| kumusta | 3 Hello | alam_ko | 12 Know |
| magandang_umaga | 0 Good Morning | hindi_ko_alam | 13 Don't Know |
| magandang_gabi | 2 Good Evening | mali | 16 Wrong |
| salamat | 7 Thank You | tama | 17 Correct |
| walang_anuman | 8 You're Welcome | mabagal | 18 Slow |
| oo | 15 Yes | mabilis | 19 Fast |
| hindi | 14 No | dalawa | 21 Two |
| nanay | 53 Mother | lima | 24 Five |
| tatay | 52 Father | tinapay | 85 Bread |
| magandang_hapon | 1 Good Afternoon | itlog | 86 Egg |
| kita_tayo_bukas | 9 See You Tomorrow | gatas | 98 Milk |
| naiintindihan | 10 Understand | kape | 99 Coffee |
| hindi_naiintindihan | 11 Don't Understand | asukal | 103 Sugar |

## 3. Folder layout

```
ml/references/
├── README.md                  this document
├── requirements.txt           steps 1, 3-8 (numpy)
├── requirements-extract.txt   step 2 (mediapipe, opencv, Python 3.12)
├── senyas_refs/               shared Python package
│   ├── config.py              default paths ($SENYAS_DATA, $SENYAS_OUT)
│   ├── kit.py                 the 26 signs, loading, probes, evaluation protocol
│   └── sign_scorer.py         the scoring design (SignScorer)
├── scripts/                   pipeline steps, in order
│   ├── 01_unpack_clips.py
│   ├── 02_extract_landmarks.py
│   ├── 03_export_references.py
│   ├── 04_pass_thresholds.py
│   ├── 05_make_videos.py
│   ├── 06_parity_fixtures.py
│   ├── 07_evaluate.py
│   └── 08_threshold_study.py
└── results/                   summary tables (CSV) of the reported runs
```

Inputs go in `data/` and outputs in `out/`; both folders are git-ignored.
Set `SENYAS_DATA` or `SENYAS_OUT` to use other folders, or pass every path on the command line.

## 4. Setup

Two Python environments are used, because MediaPipe needs an older Python than the rest.

```
# steps 1, 3-8 (tested: Python 3.14, numpy 2.5.4)
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt          # Linux/macOS: .venv/bin/pip

# step 2 only (tested: Python 3.12, mediapipe 0.10.21)
py -3.12 -m venv .venv-extract                        # Linux/macOS: python3.12 -m venv
.venv-extract/Scripts/pip install -r requirements-extract.txt
```

- Step 5 also needs `ffmpeg` with libx264 on the PATH (tested: ffmpeg 9.0.2, x264 core 165).
- Copy the app's hand model `app/src/main/assets/hand_landmarker.task` to `data/hand_landmarker.task`.

## 5. Pipeline

Run every command from `ml/references/`; each script prints its own `--help`.

| Step | Command | Output |
|---|---|---|
| 1 | `python scripts/01_unpack_clips.py --zip data/clips.zip --out data/clips` | 1,164 clips `CCC_KK.mov` |
| 2 | `.venv-extract/Scripts/python scripts/02_extract_landmarks.py --model data/hand_landmarker.task --clips data/clips --out data/landmarks --workers 4` | 1,164 landmark files `CCC_KK.json` |
| 3 | `python scripts/03_export_references.py --landmarks data/landmarks --out out/sign_refs` | 26 files `<sign>.json` |
| 4 | `python scripts/04_pass_thresholds.py --refs out/sign_refs --kmin 1.25 --kmax 1.8 --csv out/pass_thresholds.csv` | `passK` added to each file |
| 5 | `python scripts/05_make_videos.py --clips data/clips --out out/signs/fsl105` | 26 videos `<sign>.mp4` |
| 6 | `python scripts/06_parity_fixtures.py --refs out/sign_refs --landmarks data/landmarks --out out/scorer_parity.json` | Java test fixture |
| 7 | `python scripts/07_evaluate.py --landmarks data/landmarks --out out/eval --tag s26 --workers 8 --csv out/eval.csv` | evaluation at K = 1.25 |
| 8a | `python scripts/08_threshold_study.py collect --landmarks data/landmarks --out out/threshold_study --workers 8` | probe distances per sign |
| 8b | `python scripts/08_threshold_study.py analyze --out out/threshold_study --kmin 1.25 --kmax 1.8 --csv-dir out` | threshold-rule tables |

What each step does:

1. **Unpack.** It keeps every clip of the 26 app signs, plus clips 0-4, 5, 10 and 15 of every other sign as negatives.
2. **Track.** MediaPipe HandLandmarker (VIDEO mode, 2 hands, confidences 0.5) runs on every 4th frame, which gives 15 fps like a phone pipeline.
3. **Export.** Per signer, it keeps the 2 clips whose dominant hand is seen in the most frames, so each sign has 8 references.
4. **Thresholds.** It writes the per-sign pass threshold `passK` (Section 7); it must run on the full 26-sign folder.
5. **Videos.** It re-encodes signer A's first clip of each sign; the default recipe repeats the app's two encoding batches.
6. **Fixtures.** It scores fixed inputs (unseen clips, held poses, reversed and half-rate clips, look-alikes) for `SignScorerParityTest`.
7. **Evaluate.** It runs the leave-one-signer-out protocol (Section 8); `--set DIR_ON=False` scores the design without direction.
8. **Study.** It stores each probe's distance once, then compares threshold rules without scoring again.

Steps 4, 7 and 8a are slow: step 4 takes about 12 minutes, and steps 7 and 8a take a few CPU-minutes per sign.

## 6. Scoring method

The scorer (`senyas_refs/sign_scorer.py`) judges a sign on the parameters that FSL teachers name, each on its own.
The weakest parameter decides the score, so the app can say which parameter to correct.

| Parameter | What is compared |
|---|---|
| Handshape | Finger points in the hand's own 3D palm frame, in palm sizes; free of rotation and size. |
| Orientation | Where the fingers point and where the palm faces, in camera space. |
| Movement | The dominant hand's path (palm centre, in palm sizes, centred on its mean) along the alignment. |
| Size | How large the movement is, as the log ratio of path radii. |
| Two hands | Two-handed signs: the second hand's position, pointing and facing relative to the dominant hand. One-handed signs: the second hand must not move along. |
| Direction | At each aligned moment, the dominant hand moves the way the reference moves; counted only where the reference clearly moves. |

**Preparation.** MediaPipe's Left/Right labels are ignored; hands are kept in two slots by wrist continuity, and duplicate detections are merged.
Coordinates are mirrored like the app's preview, and y is scaled to image widths so that 16:9 references and 4:3 phone frames agree.

**Alignment.** Live and reference streams are put on a 10 Hz time grid, then aligned by open-begin, open-end dynamic time warping (DTW).
Slope limits let the learner be up to 3x slower or 2x faster; the local cost mixes handshape, orientation, velocity and second-hand terms.

**Variants.** Each match also tries the other hand slot as dominant and the mirror image of the sign.
So label flips, a resting second hand and left-handed learners need no special case.

**Tolerances.** Each parameter's tolerance τ is learned from the references: the median distance between clips of different reference signers.
A floor keeps τ from becoming too small; for direction it is `DIR_FLOOR` = 0.35.
Direction is counted only where the reference's saturated speed is at least `DIR_MIN_G` = 0.15.

**Score.** A parameter with distance d scores 100 · 0.85^((d / (K·τ))²), so it scores 85 exactly at d = K·τ.
The sign's score is the lowest parameter score, capped at 50 if the dominant hand is seen in under 70% of the sign; 85 or more passes.

## 7. Pass thresholds (passK)

K sets how many tolerances a parameter may be off and still pass; the design default is 1.25.
Each sign gets its own K, chosen so that no other built-in sign's reference clip passes it.

- passK = clamp(0.99 · x_closest, 1.25, 1.8), computed by `04_pass_thresholds.py`.
- x_closest is the smallest x = max over parameters of d / τ, over all bundled clips of the other 25 signs.
- A sign without look-alikes thus gets a forgiving threshold; a sign with a close look-alike keeps 1.25.

The rule was chosen in the threshold study (step 8, Table 2).
There the per-fold K uses the other app signs' first 2 clips by the 3 reference signers, as an estimate of the bundle.

## 8. Evaluation protocol

The protocol is leave-one-signer-out, because a learner is always a person the references never saw.
For each sign and each held-out signer, the references are the other 3 signers' first 2 clips (6 references).

| Probe | Input | Wanted |
|---|---|---|
| P (positive) | The held-out signer's 5 clips of the sign. | Pass (high rate). |
| N (negative) | The held-out signer's clips of every other sign (1,132 per sign). | Fail (low rate). |
| H (hold) | One frame of each P clip (start, middle, end) held still for 2.5 s. | Fail (rate 0). |
| E (half rate) | Each P clip at 7.5 fps instead of 15 fps. | Pass like P. |
| R (reverse) | Each P clip played backwards. | Fail (low rate). |

- A rate is the share of probes that pass; every sign has 20 P, 60 H, 20 E and 20 R probes.
- P measures sensitivity, N false acceptance, H whether a still pose is rejected, E robustness to slow cameras and R sensitivity to movement order.

## 9. Results

Numbers come from the stored runs summarised in `results/`; Section 11 shows that re-runs give the same output.

**Table 1. Overall pass rates, 26 signs.**

| Setting | P | N | H | E | R |
|---|---|---|---|---|---|
| One global K = 1.25 (step 7, score ≥ 85) | 0.777 | 0.012 | 0.000 | 0.788 | 0.354 |
| One global K = 1.25 (step 8, x ≤ K) | 0.763 | 0.010 | 0.000 | 0.775 | 0.327 |
| **Per-sign passK, contrast(1.25, 1.8): shipped** | **0.871** | **0.014** | **0.000** | **0.869** | **0.429** |

Against one global K = 1.25 under the same criterion, per-sign passK raises P by 11 points while N rises by 0.4 points.
The step 8 criterion x ≤ K is the unrounded form of score ≥ 85, so it is slightly stricter.

**Table 2. Threshold rules compared (step 8; full table in `results/threshold_rules.csv`).**

| Rule | P | N | H | E | R |
|---|---|---|---|---|---|
| global K 1.25 | 0.763 | 0.0102 | 0.000 | 0.775 | 0.327 |
| global K 1.4 | 0.867 | 0.0239 | 0.000 | 0.865 | 0.473 |
| global K 1.6 | 0.933 | 0.0603 | 0.000 | 0.931 | 0.592 |
| global K 1.8 | 0.962 | 0.1203 | 0.000 | 0.960 | 0.694 |
| contrast(1.0, 1.8) | 0.800 | 0.0071 | 0.000 | 0.792 | 0.365 |
| contrast(1.1, 1.8) | 0.840 | 0.0084 | 0.000 | 0.819 | 0.379 |
| contrast(1.25, 1.6) | 0.860 | 0.0129 | 0.000 | 0.860 | 0.415 |
| **contrast(1.25, 1.8)** | **0.871** | **0.0138** | **0.000** | **0.869** | **0.429** |
| contrast(1.25, 2.0) | 0.871 | 0.0141 | 0.000 | 0.873 | 0.433 |

A global K of 1.4 reaches the same P but with 1.7 times as many false passes (N 0.024 vs 0.014).
Raising KMAX above 1.8 adds almost no P, so 1.8 was kept.

**Table 3. Effect of the direction parameter, 9 original signs, K = 1.25 (step 7).**

| Design | P | N | H | E | R |
|---|---|---|---|---|---|
| Without direction (5 parameters) | 0.86 | 0.02 | 0 | 0.84 | 0.36 |
| With direction (shipped) | 0.844 | 0.020 | 0 | 0.833 | 0.311 |

The direction parameter cuts backward passes (R) by 5 points for a 1.6-point loss in P.

**Table 4. Per-sign results under the shipped rule (step 8) and shipped passK (step 4).**

| Sign | Hands | passK | Closest look-alike (x) | P | N | E | R |
|---|---|---|---|---|---|---|---|
| kumusta | one | 1.25 | tatay (1.19) | 0.95 | 0.018 | 1.00 | 0.70 |
| magandang_umaga | one | 1.49 | magandang_gabi (1.50) | 0.95 | 0.003 | 1.00 | 0.05 |
| magandang_gabi | one | 1.25 | magandang_hapon (1.06) | 0.80 | 0.012 | 0.75 | 0.00 |
| salamat | two | 1.80 | itlog (2.40) | 0.95 | 0.000 | 0.95 | 0.55 |
| walang_anuman | one | 1.80 | kumusta (1.87) | 0.85 | 0.011 | 0.75 | 0.00 |
| oo | one | 1.25 | hindi (1.25) | 1.00 | 0.018 | 1.00 | 0.35 |
| hindi | one | 1.30 | oo (1.31) | 0.90 | 0.005 | 0.90 | 0.70 |
| nanay | one | 1.25 | alam_ko (0.88) | 0.80 | 0.064 | 0.80 | 0.70 |
| tatay | one | 1.25 | nanay (1.15) | 0.70 | 0.046 | 0.60 | 0.25 |
| magandang_hapon | one | 1.25 | magandang_gabi (0.82) | 0.75 | 0.013 | 0.75 | 0.00 |
| kita_tayo_bukas | one | 1.35 | mali (1.36) | 0.90 | 0.011 | 0.95 | 0.00 |
| naiintindihan | one | 1.25 | hindi_naiintindihan (1.22) | 0.95 | 0.027 | 0.90 | 0.75 |
| hindi_naiintindihan | one | 1.80 | kita_tayo_bukas (2.07) | 1.00 | 0.005 | 1.00 | 0.00 |
| alam_ko | one | 1.25 | hindi_ko_alam (1.12) | 0.80 | 0.002 | 0.85 | 0.50 |
| hindi_ko_alam | one | 1.80 | magandang_umaga (2.14) | 1.00 | 0.008 | 0.95 | 0.00 |
| mali | one | 1.70 | naiintindihan (1.72) | 0.80 | 0.004 | 0.80 | 0.85 |
| tama | one | 1.25 | mabilis (1.15) | 0.40 | 0.051 | 0.55 | 0.10 |
| mabagal | one | 1.80 | hindi_naiintindihan (1.89) | 0.45 | 0.004 | 0.65 | 0.40 |
| mabilis | one | 1.26 | tama (1.27) | 1.00 | 0.018 | 1.00 | 0.90 |
| dalawa | one | 1.32 | hindi (1.33) | 1.00 | 0.002 | 1.00 | 0.95 |
| lima | one | 1.25 | tatay (1.03) | 0.95 | 0.006 | 0.95 | 0.80 |
| tinapay | two | 1.80 | salamat (2.41) | 1.00 | 0.000 | 0.90 | 0.90 |
| itlog | two | 1.80 | salamat (1.87) | 0.95 | 0.008 | 0.90 | 0.05 |
| gatas | one | 1.45 | kape (1.46) | 1.00 | 0.007 | 0.90 | 0.55 |
| kape | two | 1.49 | tama (1.51) | 0.85 | 0.013 | 0.85 | 0.70 |
| asukal | one | 1.40 | lima (1.41) | 0.95 | 0.003 | 0.95 | 0.40 |

H is 0.00 for every sign, so it is left out of Table 4.
`mabagal` and `tama` pass under half of their P probes, so the app shows them in the dictionary only, without scoring.

## 10. Outputs and app files

Copy the outputs into the Android project to update the app.

| Pipeline output | App file | Read by |
|---|---|---|
| `out/sign_refs/<sign>.json` | `app/src/main/assets/sign_refs/<sign>.json` | `SignReferences`, `ReferenceClip`, `SignScorer` |
| `out/signs/fsl105/<sign>.mp4` | `app/src/main/assets/signs/fsl105/<sign>.mp4` | `SignVideos` |
| `out/scorer_parity.json` | `app/src/test/resources/scorer_parity.json` | `SignScorerParityTest` |
| (input) `data/hand_landmarker.task` | `app/src/main/assets/hand_landmarker.task` | the app's live hand tracking |

A reference file has this shape (63 numbers per hand: x, y, z of 21 landmarks; `null` when the hand is not seen):

```
{"sign": "salamat", "fsl105Class": 7, "source": "...", "coords": "...",
 "refs": [{"clip": "7/1", "signer": 0, "frames": [{"t": 0, "dom": [63 numbers], "nd": null}, ...]}, ...],
 "passK": 1.8}
```

`t` is in milliseconds from the first frame with a hand, `dom` is the dominant hand and `nd` the other hand.

## 11. Reproducibility

The scripts were checked against the files the app ships, using the landmark set already extracted on the development machine.

| Check | Result |
|---|---|
| Steps 3 + 4 on all 26 signs, from landmarks | 26 of 26 `sign_refs` files byte-identical. |
| Step 3 on 3 signs (kumusta, salamat, tinapay) | Identical to the app's files apart from `passK`. |
| Step 4 on a copy of the app's `sign_refs` | All 26 passK values unchanged. |
| Step 6 on all 26 signs | All 69 cases identical, as are tolerances, `two` and passK; the app's 2 private cases are not made. |
| Step 5 on all 26 signs | 26 of 26 videos byte-identical (ffmpeg 9.0.2, x264 core 165). |
| Step 2 on 2 clips (007_00, 085_03) | Landmark files byte-identical (mediapipe 0.10.21). |
| Step 1, classes 7 and 85 | 40 of 40 clips byte-identical to the first extraction; the default selection equals the 1,164 landmark files. |
| Step 7 re-run on lima and dalawa; step 8a on lima | Same output as the stored runs. |
| Step 8b on the stored probes | Output identical to the original analysis. |

Known limits of reproduction:

- The app's fixture has 2 more salamat cases from the developer's private webcam recordings; they are not in this repository.
- `--extra-salamat DIR` adds such recordings; without them the Java test simply checks the other 69 cases.
- x264 output can depend on ffmpeg version and CPU thread count, so videos made elsewhere may differ in bytes but not in content.
- MediaPipe results can differ slightly across versions or CPUs, which would change the references and passK values.
- The 9-sign "without direction" row of Table 3 comes from an earlier run; `07_evaluate.py --set DIR_ON=False` repeats it.

## 12. Limitations

- **No face tracking.** The scorer sees hands only, so location near the face and facial expressions are not judged.
- **Camera distance.** FSL-105 is filmed from a distance, while a phone's front camera sees the hands close up; no phone-recorded test set exists yet.
- **Palindromic signs.** Signs whose movement looks the same backwards pass when reversed (R 0.95 for dalawa, 0.90 for mabilis and tinapay).
- **Signer variation.** The four signers perform `mabagal` and `tama` differently (P 0.45 and 0.40), so these signs are dictionary-only in the app.
- **Small sample.** Only 4 signers are available, so every rate rests on 20 positive probes per sign.
- **Same data for choice and report.** The threshold rule was chosen on the probes it is reported on, so Table 1's passK row is somewhat optimistic.

## 13. References and license

- Tupal, I. J. *FSL-105* [dataset]. Mendeley Data, version 2. De La Salle University. https://doi.org/10.17632/48y2y99mb9.2 (CC BY 4.0).
- Google. *MediaPipe Hand Landmarker* (`hand_landmarker.task`), used through mediapipe 0.10.21.

The code in this folder is under the repository's MIT license.
Data derived from FSL-105 (references, videos, fixtures) stays under CC BY 4.0 and must credit the dataset as above.
