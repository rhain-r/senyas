# Senyas Android App

This folder is the Android Studio project of Senyas.
It is written in Java 11 and targets Android 8.0 (API 26) and newer.

## Build

Open this folder in Android Studio and press **Run**, or use the Gradle wrapper.

```bash
./gradlew assembleDebug        # debug APK: app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests
./gradlew assembleRelease      # release APK: app/build/outputs/apk/release/
```

On Windows without a separate JDK, point `JAVA_HOME` at Android Studio's bundled one, for example `C:\Program Files\Android\Android Studio\jbr`.

## Release signing

The release build is signed only when `keystore.properties` exists in this folder.
It holds `storeFile`, `storePassword`, `keyAlias` and `keyPassword`; keep it and the key file out of version control.
Updates to an installed app must be signed with the same key.

## Main libraries

| Library | Version | Use |
| --- | --- | --- |
| CameraX | 1.6.2 | Camera preview and frame analysis. |
| MediaPipe Tasks Vision | 0.10.35 | Hand landmarks and face detection. |
| LiteRT | 1.4.2 | Optional trained sign classifier in Sign Lab. |
| Material Components | 1.14.0 | User interface. |

## Assets

| Path | Contents |
| --- | --- |
| `app/src/main/assets/hand_landmarker.task` | MediaPipe hand model. |
| `app/src/main/assets/blaze_face_short_range.tflite` | MediaPipe face detector. |
| `app/src/main/assets/signs/fsl105/` | 26 demonstration videos from FSL-105. |
| `app/src/main/assets/sign_refs/` | 26 built-in sign references with pass thresholds. |

The videos and references are produced by [../ml/references](../ml/references/README.md).
