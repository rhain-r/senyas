# Senyas Machine Learning

This folder holds the two Python pipelines behind the app's sign feedback.

| Folder | Purpose |
| --- | --- |
| [references/](references/README.md) | Builds the built-in sign references, videos and pass thresholds from FSL-105, and evaluates the Practice Room scorer. |
| [classifier/](classifier/README.md) | Trains an optional TensorFlow Lite classifier that Sign Lab can use to recognize signs. |

Both pipelines use MediaPipe hand landmarks, the same ones the app tracks on the phone.
Datasets and generated outputs are not stored in the repository; each README explains how to get or rebuild them.
