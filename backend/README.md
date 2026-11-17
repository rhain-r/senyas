# Firebase backend

Senyas has no server of its own. The connected build talks directly to Firebase Authentication and Cloud Firestore, and the security rules in this folder decide what each user may read or write. Everything fits Firebase's free Spark plan, so no Cloud Functions or billing account are needed.

What the rules enforce, in plain words, is described in [Accounts, lessons and bookings](../docs/accounts-and-bookings.md).

## Files

| File | Purpose |
| --- | --- |
| `firestore.rules` | Security rules for profiles, lessons, bookings, tutor schedules, progress and class attendance |
| `firestore.indexes.json` | Firestore indexes (none needed yet) |
| `firebase.json` | Rule file locations and emulator ports (Auth `9199`, Firestore `8180`) |
| `.firebaserc` | The default Firebase project, `senyas-4d7f2` |
| `tests/rules.test.mjs` | 14 tests that try to break the rules |
| `package.json`, `pnpm-lock.yaml` | Test tools: Firebase CLI 15.34.2, Firebase JS SDK, rules-unit-testing |

## Run the rule tests

You need Node.js, [pnpm](https://pnpm.io) and Java 21 or newer (the Firebase emulators run on Java).

```bash
cd backend
pnpm install
pnpm test
```

`pnpm test` starts local Auth and Firestore emulators under the demo project `demo-senyas`, runs the tests, then shuts the emulators down. Nothing touches the real project. The tests check, among other things, that:

- sign-up cannot create an administrator, and profiles stay private;
- pending lessons are hidden from students until an administrator approves them;
- two conflicting bookings cannot both succeed, including partial overlaps across different lessons of one tutor;
- price, tutor, time, owner and length cannot be forged;
- only the owner can cancel, and cancelling frees the slot again;
- suspended accounts are blocked immediately;
- progress belongs only to its learner;
- classes cannot be joined early or by outsiders, and payouts need an administrator and a finished class.

## Deploy the rules

```bash
cd backend
pnpm exec firebase login
pnpm exec firebase deploy --only firestore
```

This deploys `firestore.rules` and `firestore.indexes.json` to the project in `.firebaserc`. You must be signed in as an owner or editor of that project.

## First-time Firebase setup

The rules and indexes are already deployed to `senyas-4d7f2`. For that project, or for a new one:

1. In **Authentication → Sign-in method**, enable **Email/Password**.
2. Create a **Cloud Firestore** database and deploy the rules (above).
3. Create the first administrator in **Authentication → Users**. Use a private email address for a real deployment.
4. Copy that user's UID. In **Firestore Database → profiles**, create a document whose ID is exactly that UID, with these fields:

   ```text
   uid:    <same UID>            (string)
   email:  <same email>          (string)
   name:   <administrator name>  (string)
   role:   3                     (number)
   active: true                  (boolean)
   ```

5. Register an Android app with the ID `com.benten.senyas.cameraqa`, download its `google-services.json`, and place it as described in the [Android guide](../android/README.md#firebase-configuration-for-the-connected-build).

The app cannot create an administrator from its sign-up screen, so step 4 is the only way to get one. Tutors who sign up get role `2`. Their new lessons are saved as unreviewed and appear to learners only after an administrator approves them.
