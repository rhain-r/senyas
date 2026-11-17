# How sign checking works

When you practise a sign, Senyas watches your hands and upper body through the front camera and decides whether you made the sign the lesson asked for. Everything happens on the phone: camera frames are never saved or sent anywhere.

The first half of this page explains the idea in plain words. The second half is for developers.

## In plain words

**The phone sees dots, not faces.** Google's MediaPipe turns each camera frame into points: 21 on each hand, plus the nose, shoulders, elbows and wrists. From then on the app works only with those points.

**A sign is a movement, not a pose.** The app keeps the last few seconds of points. When you lower your hands for a moment, it treats what came before as one attempt, from start to finish.

**The model compares your attempt with 105 signs.** It was trained on the FSL-105 dataset, so it knows 105 signs, not only the ones taught in the app. Your attempt counts only if it looks more like the requested sign than like any of the other 104, and by a clear margin. That is why a similar-looking sign does not pass.

**The bar is a match score, not a grade.** The bar passes 85 exactly when the model accepts the attempt. While you are still signing, the bar can move, but it cannot pass 85 until the attempt is complete. A still hand, random waving or an empty frame scores zero.

**XP is earned once.** The first accepted attempt of each sign earns XP for your account; later attempts can only raise your best score. Skipping a sign records it as skipped and earns nothing.

**Some signs are not graded yet.** Only signs that passed the accuracy test are graded. The list is in the [README](../README.md#signs-the-camera-can-grade).

## Step by step (for developers)

| Step | Class | What happens |
| --- | --- | --- |
| 1 | `HandTracker` | CameraX delivers frames, preferring the front camera and falling back to the rear one. Frames are turned upright before detection. |
| 2 | `HandTracker` | MediaPipe Hand Landmarker (up to 2 hands) and Pose Landmarker (1 person) run in video mode with detection, presence and tracking confidence of 0.35, the same values used in training. |
| 3 | `SignFeatures` | Turns the hands and pose into 156 numbers per frame. The layout is defined in the [feature contract](../ml/README.md#feature-contract-v1). |
| 4 | `SequenceRecognizer` | Buffers frames, decides when an attempt is complete and runs the neural network. |
| 5 | `PracticeFragment`, `ScoreBarView` | Shows the match bar and the result, saves the best score and awards XP. |
| – | `HandOverlayView` | Draws the detected hand points over the camera preview. |

`SignFeatures` and `SequenceRecognizer` have no Android dependencies, so they run in plain JVM unit tests.

### Input coordinates

Call `SignFeatures.extract(hands, pose, width, height)` with an upright detector image's dimensions, hands shaped `[hand][21][x,y,z]`, and pose shaped `[33][x,y,z,visibility]`. If the image is mirrored, mirror the whole image, so hands and pose are flipped together.

Normalized x and z are multiplied by `width/height` before distances are measured. This stops a portrait camera crop from changing finger proportions compared with the landscape training videos. If both shoulders are not clearly visible, the frame gets a zero body-present feature and cannot be accepted.

### Buffering and attempts

Load the model once with `SequenceRecognizer.load(InputStream)`. A missing, truncated, incompatible or non-finite model throws `IOException`, and the screen shows that the model is unavailable.

Call `addFrame(features, monotonicTimestampMs, targetClassId)` on the camera analysis thread. The buffer holds at most 4 seconds and 128 frames. An attempt must last at least 600 ms and contain at least 6 frames with hands. It is complete once hands or body have been missing for more than 300 ms. The first and last usable frames are trimmed, and the rest is resampled to 16 frames by timestamp. The network runs at most once every 250 ms.

Gaps of up to 300 ms keep the attempt going. Longer gaps, timestamps that go backwards, gaps over one second, invalid features and a change of target all discard the old frames. Call `reset()` on Skip, retry, lesson completion or leaving the camera.

### When an attempt is accepted

All of these must hold:

- hands and body were present in enough frames, and the hands moved (a frozen pose has zero motion);
- the requested sign has the highest probability of all 105 signs;
- that probability reaches the sign's calibrated threshold;
- it beats the runner-up by the sign's calibrated margin.

A threshold or margin above 1 switches a sign off; `supportsTarget(id)` reports this. How the thresholds were chosen is described in [`ml/README.md`](../ml/README.md#how-thresholds-are-chosen).

### The match score

`Prediction.targetConfidence` is the temperature-scaled softmax output for the requested sign. The bar shows `round(85 × targetConfidence / classThreshold)`, capped at 100. Rejected attempts are capped at 84, and invalid, missing, still or warming-up input scores 0. The 85 marker therefore matches the accept decision exactly. The score is not a measured accuracy or a percentage of correctness.

`Prediction.evaluatedAtMs` stays the same when a throttled call reuses a prediction. `targetClassId` lets the screen ignore results that belong to an earlier sign.

## Models

| Model | Source | Licence |
| --- | --- | --- |
| Hand Landmarker (float16, v1) | [Download](https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task), [guide](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker), [model card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Hand%20Tracking%20%28Lite_Full%29%20with%20Fairness%20Oct%202021.pdf). SHA-256 `fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1` | Apache 2.0 |
| Pose Landmarker Lite | Google MediaPipe | Apache 2.0 |
| `fsl_recognizer.bin` | Trained by the scripts in [`ml/`](../ml/) from FSL-105 | CC BY 4.0 (derived from FSL-105) |

## Other approaches we looked at

- MediaPipe's built-in gesture recogniser knows a few generic hand shapes (open palm, fist, thumbs up), not FSL words, so it was not used.
- [Pansinayan](https://github.com/remi-9/fslr-transformer-vs-iv3gru) also uses body, hand and face points over time for 105 glosses. It was a useful design reference, but it is research code without a licence file.
- [Senya](https://github.com/weimodein/senya) recognises fingerspelling rather than words, and its authors note it was trained on one signer.

## Tests

| Test | Checks |
| --- | --- |
| `SignFeaturesTest` | A 156-value fixture generated by Python, portrait and landscape crops, mirroring, hand order and broken detections |
| `SequenceRecognizerTest` | Model loading, matrix layout, ReLU and softmax maths, calibration, timestamp resampling, dropouts, reset, disabled signs and overflow |
| `TrainedModelParityTest` | The shipped `fsl_recognizer.bin` gives the same probabilities in Java as in Python |
| `RealFslFeedbackTest` (device) | A real FSL-105 video runs through hand tracking, pose tracking and the shipped model; held-out HELLO updates the bar, and HELLO is never graded as THANK YOU |
| `VerifiedFeedbackRegressionTest` (device) | An accepted sign saves the best score and awards XP once per account and sign; rejected or outdated results award nothing |
| `HandTrackerTest` (device) | Two real hands are found in a reference photo, and empty frames clear the landmarks |
