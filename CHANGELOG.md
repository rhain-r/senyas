# Changelog

Newest version first. Test counts are the results recorded when each version was finished.

## 1.5: Shared accounts with Firebase (November 2026)

- New **connected** build (`com.benten.senyas.cameraqa`). Accounts, approved lessons, bookings and learning progress are now shared between phones through Firebase Authentication and Cloud Firestore.
- Firestore security rules check every booking on the server: the time must be one the tutor offered and still in the future, the price cannot be changed, and two bookings can never overlap for the same tutor, even across different lessons.
- Administrator accounts can only be created by the Firebase project owner, never from the app's sign-up screen.
- The offline build still works with no internet connection or setup.
- 14 backend rule tests run against the Firebase emulators.

## 1.4: Real feedback on your signing

- The match bar is now driven by a trained sign model instead of hand detection alone. Seven signs are graded; see the [README](README.md#signs-the-camera-can-grade).
- XP is awarded once per sign per account, and only for a verified correct attempt. The best score is saved.
- Fixed a crash, reported from a phone running 1.3, when leaving the learning path while a lesson was opening.
- Hand and body tracking now use the same confidence settings (0.35) as the training data.
- Progress survives closing and reopening the app, and a new account always starts at zero.

## 1.2.1: Skip a sign (5 November 2026)

- Lessons have a **Skip sign** link. Skipping moves on without opening the camera, keeps any score already earned and awards no XP.
- 10 targeted device tests and 16 unit tests passed.

## 1.2: Tutor schedules and real videos (5 November 2026)

- Tutors set availability with calendar and clock pickers instead of free text. Learners now see the tutor's real dates and times, and confirmation re-checks that the slot is still free.
- Nine FSL-105 demonstration videos are built into the app, with credits, replay, captions and slow motion. Words without a verified video say so.
- A separate administrator sign-in entry was added.
- When the camera fails, the app keeps a diagnostic report that the user can choose to share.
- 38 of 38 device tests and 16 of 16 unit tests passed.

## 1.1: Accounts, sessions and camera fixes (4 November 2026)

- Login checks the stored account and its role; the role picker on the login screen was removed. Sign-up cannot create an administrator.
- Students, tutors and administrators each see only their own screens and data. Bookings, listings, payouts and notifications are tied to account IDs.
- A class can be joined only by its booked student and tutor, only between its start and end time. A camera check is available before the class.
- Scheduling uses current dates in Philippine time, rejects past slots and rejects overlapping bookings for either person.
- Hand detection became more sensitive (confidence 0.5 instead of 0.6).
- 26 of 26 device tests and 8 of 8 unit tests passed.

## Earlier prototype

Commits before version 1.5 in this repository contain an earlier prototype (labelled 2.0). It scored signs against recorded templates and included a Sign Lab recorder. It is kept in the git history only.
