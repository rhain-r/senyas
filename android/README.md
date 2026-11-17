# Senyas Android app

The Android Studio project for Senyas. It is written in Java 11, runs on Android 8.0 (API 26) and newer, and targets API 37. Current version: **1.5** (version code 7).

## Build types

| Build type | App ID | Accounts and bookings | Notes |
| --- | --- | --- | --- |
| `debug` | `com.benten.senyas` | Stored on the phone | Adds three sample accounts: a student, a tutor and an administrator. |
| `release` | `com.benten.senyas` | Stored on the phone | No sample accounts. Unsigned until you add a signing config. |
| `connected` | `com.benten.senyas.cameraqa` | Shared Firebase project | Needs `google-services.json` (see below). |
| `connectedEmulator` | `com.benten.senyas.cameraqa` | Local Firebase emulators | Used only by the automated cloud tests. |

The two app IDs can be installed side by side on one phone.

## Build

Open this folder in Android Studio and press **Run**, or use the Gradle wrapper:

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleConnected    # app/build/outputs/apk/connected/app-connected.apk
```

On Windows without a separate JDK, point `JAVA_HOME` at the one bundled with Android Studio, for example `C:\Program Files\Android\Android Studio\jbr`, and run `gradlew.bat`.

### Firebase configuration for the connected build

1. In the Firebase console, register an Android app with the ID `com.benten.senyas.cameraqa`.
2. Download its `google-services.json` and save it as `app/src/connected/assets/google-services.json`.

The file is kept out of git. Without it the connected build shows an error at sign-in; it never falls back to offline accounts. Setting up the Firebase project itself is covered in [`backend/README.md`](../backend/README.md).

## Tests

| Command | What it checks | Needs |
| --- | --- | --- |
| `./gradlew testDebugUnitTest` | 47 unit tests: scheduling and time zones, access rules, sign features, and that the Java model gives the same answers as the Python one | Nothing extra |
| `./gradlew connectedDebugAndroidTest` | On-device tests: sign-up and login, account isolation, bookings, camera practice, videos, skip, saved progress | A phone or emulator, plus `app/src/androidTest/assets/demo_accounts_private.json` |
| `./gradlew -PsenyasTestBuildType=connectedEmulator :app:connectedConnectedEmulatorAndroidTest` | Firebase sign-in, shared data and the cloud screens | The Firebase emulators from [`backend/`](../backend/) running, plus an Android emulator |

`demo_accounts_private.json` holds the plain-text passwords of the sample accounts. It is kept out of git; ask a team member for a copy. The app itself only contains salted PBKDF2 hashes of those passwords, in `app/src/debug/assets/demo_accounts.json`.

## Code layout

All app code is in one package, `com.benten.senyas`, under `app/src/main/java/`.

| Area | Main classes |
| --- | --- |
| Start-up and navigation | `SenyasApp`, `SplashActivity`, `LoginActivity`, `MainActivity` |
| Accounts and permissions | `AccountStore`, `AccessPolicy`, `CloudRuntime`, `CloudAccounts` |
| Learning path and lessons | `PathFragment`, `LearnFragment`, `SignLessonFragment`, `LessonVideos`, `Scenario` |
| Camera practice | `PracticeFragment`, `PracticeSummaryFragment`, `CameraPreviewFragment`, `ScoreBarView`, `LearningProgressStore` |
| Sign recognition | `HandTracker`, `HandOverlayView`, `SignFeatures`, `SequenceRecognizer`, `FslSigns` |
| Camera problems | `CameraCheckFragment`, `CameraUnavailableFragment`, `CameraDiagnostics`, `DiagnosticJournal` |
| Finding and booking tutors | `TutorsFragment`, `FilterFragment`, `LessonDetailFragment`, `ScheduleFragment`, `ReviewFragment`, `ConfirmedFragment`, `BookingsFragment` |
| Class time | `SessionTime`, `LessonAvailability`, `LiveClassroomFragment`, `WhiteboardView` |
| Tutor screens | `TutorHomeFragment`, `TutorSessionsFragment`, `MyListingsFragment`, `LessonFormFragment`, `MediaFragment`, `PayoutsFragment` |
| Administrator screens | `AdminDashboardFragment`, `AdminListingsFragment`, `AdminUsersFragment`, `AdminPayoutsFragment`, `BarChartView` |
| App data | `Repo` (in-memory data), `RepoStore` (saved to the phone), `CloudData` and `CloudCodec` (Firestore) |

How the sign recognition classes work together is described in [`docs/sign-recognition.md`](../docs/sign-recognition.md).

## Bundled files

| Path | Contents |
| --- | --- |
| `app/src/main/assets/hand_landmarker.task` | MediaPipe hand model |
| `app/src/main/assets/pose_landmarker_lite.task` | MediaPipe body pose model |
| `app/src/main/assets/fsl_recognizer.bin` | The trained sign model, produced by [`ml/`](../ml/) |
| `app/src/main/assets/fsl_recognizer_report.json` | Training and test results for that model |
| `app/src/main/assets/fsl105-labels.csv`, `FSL105-LICENSE.txt` | The 105 dataset labels and the dataset licence |
| `app/src/main/res/raw/fsl105_*.mov` | Nine demonstration videos from FSL-105 (1.6 MB in total) |
| `app/src/main/res/values-fil/` | Filipino translations |

## Main libraries

| Library | Version | Used for |
| --- | --- | --- |
| CameraX | 1.6.2 | Camera preview and frame analysis |
| MediaPipe Tasks Vision | 1.2.2 | Hand and body landmarks |
| Firebase BoM (Auth, Firestore) | 35.1.0 | Shared accounts and data in the connected build |
| Material Components | 1.14.0 | User interface |
| Android Gradle Plugin | 9.3.3 | Build |
