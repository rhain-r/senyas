# Accounts, lessons and bookings

This page explains who can do what in Senyas, how a tutor lesson gets from draft to booked class, and where the data is kept.

## Offline and connected versions

| | Offline (`debug`, `release`) | Connected (`connected`) |
| --- | --- | --- |
| Accounts | Saved on the phone; passwords stored as salted PBKDF2-HMAC-SHA256 hashes | Firebase Authentication (email and password) |
| Lessons and bookings | Saved on the phone; the tutor catalogue starts with sample data | Firestore, shared by every phone |
| Learning progress | Saved on the phone | Saved in Firestore for the signed-in account |
| Internet needed | No | Yes, for anything shared |

The connected version never quietly falls back to offline accounts. If the server cannot confirm an action, the app shows an error and nothing is reported as saved.

## Roles

| Role | Number | Created by | Can |
| --- | --- | --- | --- |
| Hearing Learner | 0 | Sign-up screen | Learn, practise, book tutors |
| Deaf Student | 1 | Sign-up screen | Learn, practise, book tutors |
| Deaf Tutor | 2 | Sign-up screen | Publish lessons, teach booked sessions, see payouts |
| Administrator | 3 | The project owner only | Approve lessons, suspend or restore users, release payouts |

Nobody can make themselves an administrator from the app. In the offline debug build a sample administrator account exists; in the connected build the project owner creates one by hand (see [`backend/README.md`](../backend/README.md#first-time-firebase-setup)).

The role is read from the saved account, never from the login screen. Each role has its own screens, and every booking, listing, payout and notification is tied to an account ID rather than a display name, so two accounts with the same name never see each other's data. Logging out clears the session and any cached account data.

In the connected version, a profile holds only `uid`, `email`, `name`, `role` and `active`. Users can read and rename only their own profile. Administrators can read all profiles and switch an account between active and suspended; a suspended account is blocked from all server reads and writes.

## From lesson to class

1. **A tutor publishes a lesson** with a title, a price, a length of 30 or 60 minutes, one or more dates, and exact start times chosen with calendar and clock pickers.
2. **An administrator reviews it.** New lessons, and any edit to a lesson's content, wait as "pending". Learners only see reviewed lessons.
3. **A learner books a time** from the tutor's published slots. Times are shown in Philippine time (`Asia/Manila`), and past slots are disabled.
4. **The booking is checked twice.** The app re-checks the slot on the confirmation screen, and in the connected version the server checks it again in a single transaction (details below).
5. **Both people can test their camera** before the class from Bookings (learner) or Sessions (tutor). This does not let anyone in early.
6. **The class opens at its start time** for the booked learner and tutor only, and closes at its end time. There is no early entry.
7. **After the class ends,** an administrator can release the tutor's payout. A cancelled class cannot be paid out.

### How double booking is prevented

Every booking stores the learner, the tutor, and a fixed copy of the lesson details and price at the time of booking. The Firestore rules accept a new booking only when:

- the lesson is approved and the start time is one the tutor offered;
- the start time is in the future, by the server's clock;
- the length is 30 or 60 minutes, the class ends before midnight, and the price matches the lesson's price for that length;
- none of the tutor's minutes in that period are already taken.

Taken minutes are kept in one `tutorDays` document per tutor per day. A booking and its minutes are written in the same transaction, so of two learners racing for the same slot, only one succeeds, even if they booked different lessons from the same tutor. Only the learner who made a booking can cancel it, and only before it starts; cancelling frees exactly those minutes. Nobody can change a booking's price, time or participants.

## Payments

The booking screen offers GCash, Maya and card, but no payment is processed. For cards, only the last four digits are stored. Releasing a payout only marks it as released in the app.

## Data in Firestore

| Collection | Holds | Who can read it |
| --- | --- | --- |
| `profiles/{uid}` | Name, email, role, active flag | The account itself and administrators |
| `profiles/{uid}/notificationReads` | Which notifications were read | The account itself |
| `lessons/{id}` | Tutor lesson listings | Everyone signed in, once approved; the owner and administrators before that |
| `bookings/{id}` | Bookings with their price and lesson snapshot | The learner and tutor of that booking, and administrators |
| `tutorDays/{tutor_day}` | Minutes already booked for a tutor on one day | Everyone signed in (it shows only busy minutes) |
| `progress/{uid}` | XP, streak, scores and learning-path state | The learner only |
| `attendance/{booking}/participants/{uid}` | Who joined a class and when | The booking's learner and tutor, only during the class |

Anything not listed is closed to all clients. The rules are in [`backend/firestore.rules`](../backend/firestore.rules).

## Not built yet

- Two-way video, chat and a shared whiteboard during class. Each person currently sees their own camera and their own whiteboard.
- Real payments and payouts.
