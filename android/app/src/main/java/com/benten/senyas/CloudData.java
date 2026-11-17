package com.benten.senyas;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskExecutors;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared records. No operation reports success until its server acknowledgement arrives. */
public final class CloudData {
    private CloudData() { }
    private static final SecureRandom RANDOM = new SecureRandom();
    // These caches, like Repo, are read and changed only in main-thread callbacks.
    private static String cachedUid = "";
    private static int cachedRole = -1;
    private static final Map<String, Map<String, Object>> bookingRecords = new LinkedHashMap<>();
    private static final Map<String, Boolean> notificationReads = new HashMap<>();
    private static final Map<Repo.Notif, String> notificationKeys = new IdentityHashMap<>();

    public static final class IdentityChangedException extends IllegalStateException {
        IdentityChangedException() { super("The signed-in account changed. Open this screen again."); }
    }

    private static final class Actor {
        final String uid, name;
        final int role;
        Actor(String uid, String name, int role) { this.uid = uid; this.name = name; this.role = role; }
        void authenticated() {
            FirebaseUser user = CloudRuntime.auth().getCurrentUser();
            if (user == null || !uid.equals(user.getUid())) throw new IdentityChangedException();
        }
        void current() {
            authenticated();
            if (!sameIdentity(uid, role)) throw new IdentityChangedException();
        }
    }

    /** Package visible for the snapshot identity regression test. */
    static boolean sameIdentity(String uid, int role) { return uid.equals(Repo.accountId()) && role == Repo.accountRole(); }

    private static Actor actor() {
        if (!CloudRuntime.enabled()) throw new IllegalStateException("The shared backend is not configured");
        String uid = Repo.accountId(); int role = Repo.accountRole();
        if (uid.isEmpty() || role < 0 || role > 3) throw new SecurityException("Sign in to continue");
        Actor actor = new Actor(uid, Repo.userName, role); actor.authenticated(); return actor;
    }
    private static void role(Actor actor, int expected) {
        if (actor.role != expected) throw new SecurityException("Account access denied");
    }
    private static FirebaseFirestore db() { return CloudRuntime.db(); }
    private static DocumentReference lessonRef(int id) { return db().collection("lessons").document(Integer.toString(id)); }
    private static DocumentReference bookingRef(String reference) {
        if (reference == null || !reference.matches("SEN-[A-Za-z0-9-]{1,100}")) throw new IllegalArgumentException("Invalid booking reference");
        return db().collection("bookings").document(reference);
    }
    private static Map<String, Object> record(DocumentSnapshot snapshot) {
        if (!snapshot.exists() || snapshot.getData() == null) throw new IllegalStateException("This record no longer exists");
        return snapshot.getData();
    }
    private static AccountStore.Account profile(Map<String, Object> data) {
        return AccountStore.Account.cloud(CloudCodec.string(data, "uid"), CloudCodec.string(data, "email"),
                CloudCodec.string(data, "name"), CloudCodec.integer(data, "role"), CloudCodec.bool(data, "active"));
    }

    /** Fetches the actor's entire permitted view atomically from the UI's perspective. */
    public static Task<Void> refresh() {
        try {
            final Actor actor = actor();
            Query lessons = db().collection("lessons"), bookings = db().collection("bookings");
            if (actor.role == Repo.ROLE_TUTOR) {
                lessons = lessons.whereEqualTo("tutorId", actor.uid);
                bookings = bookings.whereEqualTo("tutorId", actor.uid);
            } else if (AccessPolicy.isLearner(actor.role)) {
                lessons = lessons.whereEqualTo("reviewed", true);
                bookings = bookings.whereEqualTo("learnerId", actor.uid);
            }
            final Task<DocumentSnapshot> own = db().collection("profiles").document(actor.uid).get(Source.SERVER);
            final Task<QuerySnapshot> listingTask = lessons.get(Source.SERVER), bookingTask = bookings.get(Source.SERVER);
            final Task<QuerySnapshot> reads = db().collection("profiles").document(actor.uid).collection("notificationReads").get(Source.SERVER);
            final Task<QuerySnapshot> profiles = actor.role == Repo.ROLE_ADMIN ? db().collection("profiles").get(Source.SERVER) : null;
            List<Task<?>> requests = new ArrayList<>();
            Collections.addAll(requests, own, listingTask, bookingTask, reads); if (profiles != null) requests.add(profiles);
            return Tasks.whenAll(requests).onSuccessTask(TaskExecutors.MAIN_THREAD, ignored -> {
                actor.current();
                AccountStore.Account account = profile(record(own.getResult()));
                if (!account.id.equals(actor.uid) || account.role != actor.role || !account.active)
                    throw new SecurityException("This account's access has changed. Sign in again.");
                List<Lesson> newLessons = new ArrayList<>();
                for (DocumentSnapshot document : listingTask.getResult().getDocuments()) {
                    Lesson lesson = CloudCodec.lesson(record(document));
                    if (!document.getId().equals(Integer.toString(lesson.id))
                            || (actor.role == Repo.ROLE_TUTOR && !actor.uid.equals(lesson.tutorId))
                            || (AccessPolicy.isLearner(actor.role) && !lesson.reviewed)) throw new SecurityException("Invalid listing response");
                    newLessons.add(lesson);
                }
                Map<String, Map<String, Object>> newBookings = new LinkedHashMap<>();
                for (DocumentSnapshot document : bookingTask.getResult().getDocuments()) {
                    Map<String, Object> data = record(document); Repo.Booking booking = CloudCodec.booking(data);
                    if (!document.getId().equals(booking.reference) || !mayReadBooking(actor, booking)) throw new SecurityException("Invalid booking response");
                    CloudCodec.session(data, booking); CloudCodec.bool(data, "released");
                    newBookings.put(booking.reference, new HashMap<>(data));
                }
                Map<String, Boolean> newReads = new HashMap<>();
                for (DocumentSnapshot read : reads.getResult().getDocuments()) if (Boolean.TRUE.equals(read.getBoolean("read"))) newReads.put(read.getId(), true);
                List<AccountStore.Account> newProfiles = new ArrayList<>();
                if (profiles != null) for (DocumentSnapshot document : profiles.getResult().getDocuments()) {
                    AccountStore.Account other = profile(record(document));
                    if (!document.getId().equals(other.id)) throw new SecurityException("Invalid profile response");
                    newProfiles.add(other);
                } else newProfiles.add(account);
                // All decoding completes before replacing any visible collection.
                actor.current();
                CloudAccounts.cacheAccount(account);
                actor.current();
                CloudAccounts.cacheProfiles(newProfiles);
                Repo.activateAccount(account);
                applyLessonSnapshot(actor.uid, actor.role, newLessons);
                cachedUid = actor.uid; cachedRole = actor.role;
                bookingRecords.clear(); bookingRecords.putAll(newBookings);
                notificationReads.clear(); notificationReads.putAll(newReads);
                Repo.USERS.clear();
                if (actor.role == Repo.ROLE_ADMIN) for (AccountStore.Account other : newProfiles) {
                    Repo.User user = new Repo.User(other.name, other.role, other.active); user.accountId = other.id; Repo.USERS.add(user);
                }
                rebuildBookingViews(actor);
                return Tasks.forResult(null);
            });
        } catch (Exception error) { return Tasks.forException(error); }
    }

    public static Task<Lesson> saveLesson(Lesson lesson, boolean creating) {
        try {
            final Actor actor = actor(); role(actor, Repo.ROLE_TUTOR);
            if (lesson == null || !actor.uid.equals(lesson.tutorId)) throw new SecurityException("Not your listing");
            final int id = creating ? 1 + RANDOM.nextInt(Integer.MAX_VALUE - 1) : lesson.id;
            final Map<String, Object> payload = CloudCodec.lessonPayload(lesson, id, actor.uid, actor.name);
            for (Object offer : (List<?>) payload.get("offers")) if (((Number) offer).longValue() <= System.currentTimeMillis())
                throw new IllegalArgumentException("Choose future availability dates and times");
            final DocumentReference ref = lessonRef(id);
            return db().runTransaction(transaction -> {
                actor.authenticated(); DocumentSnapshot existing = transaction.get(ref);
                if (creating) {
                    if (existing.exists()) throw new IllegalStateException("Listing ID collision. Please publish again.");
                } else if (!actor.uid.equals(CloudCodec.string(record(existing), "tutorId"))) throw new SecurityException("Not your listing");
                transaction.set(ref, payload); return payload;
            }).onSuccessTask(TaskExecutors.MAIN_THREAD, confirmedData -> {
                        actor.current(); Lesson confirmed = CloudCodec.lesson(confirmedData);
                        Lesson applied = upsertLesson(actor, confirmed);
                        if (Repo.draft == lesson) Repo.draft = null;
                        rebuildNotifications(actor); return Tasks.forResult(applied);
                    });
        } catch (Exception error) { return Tasks.forException(error); }
    }

    public static Task<Void> deleteLesson(Lesson lesson) {
        try {
            final Actor actor = actor(); role(actor, Repo.ROLE_TUTOR);
            if (lesson == null || !actor.uid.equals(lesson.tutorId)) throw new SecurityException("Not your listing");
            final int id = lesson.id; final DocumentReference ref = lessonRef(id);
            return db().runTransaction(transaction -> {
                actor.authenticated();
                if (!actor.uid.equals(CloudCodec.string(record(transaction.get(ref)), "tutorId"))) throw new SecurityException("Not your listing");
                transaction.delete(ref); return null;
            }).onSuccessTask(TaskExecutors.MAIN_THREAD, ignored -> {
                        actor.current();
                        Repo.LESSONS.removeIf(value -> value.id == id);
                        if (Repo.draftLesson != null && Repo.draftLesson.id == id) Repo.draftLesson = null;
                        rebuildNotifications(actor); return Tasks.forResult(null);
                    });
        } catch (Exception error) { return Tasks.forException(error); }
    }

    public static Task<Void> approveLesson(Lesson lesson) {
        try {
            final Actor actor = actor(); role(actor, Repo.ROLE_ADMIN);
            if (lesson == null) throw new IllegalArgumentException("Choose a listing");
            final DocumentReference ref = lessonRef(lesson.id);
            return db().runTransaction(transaction -> {
                actor.authenticated(); Map<String, Object> data = record(transaction.get(ref));
                transaction.update(ref, "reviewed", true); data.put("reviewed", true); return data;
            }).onSuccessTask(TaskExecutors.MAIN_THREAD, confirmedData -> {
                        actor.current(); upsertLesson(actor, CloudCodec.lesson(confirmedData));
                        rebuildNotifications(actor); return Tasks.forResult(null);
                    });
        } catch (Exception error) { return Tasks.forException(error); }
    }

    public static Task<Repo.Booking> bookDraft() {
        try {
            final Actor actor = actor();
            if (!AccessPolicy.isLearner(actor.role)) throw new SecurityException("Only learners can book sessions");
            if (Repo.draftLesson == null) throw new IllegalArgumentException("Choose a listing");
            final int lessonId = Repo.draftLesson.id, duration = Repo.draftDuration, quote = Repo.draftPrice;
            final long start = Repo.draftStartAtMillis;
            final String paymentKind = Repo.draftPayKind == 0 ? "GCash" : Repo.draftPayKind == 1 ? "Maya" : Repo.draftPayKind == 2 ? "Card" : "";
            final String last4;
            if ("Card".equals(paymentKind)) {
                java.util.regex.Matcher digits = java.util.regex.Pattern.compile("([0-9]{4})\\s*$").matcher(Repo.draftPayment == null ? "" : Repo.draftPayment);
                if (!digits.find()) throw new IllegalArgumentException("Check the card details"); last4 = digits.group(1);
            } else last4 = "";
            if (!CloudCodec.duration(duration) || quote <= 0 || start <= System.currentTimeMillis() || start % 60000L != 0)
                throw new IllegalArgumentException("Choose a future session time");
            final String reference = "SEN-" + UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT);
            final DocumentReference ref = bookingRef(reference), listing = lessonRef(lessonId);
            return db().runTransaction(transaction -> {
                actor.authenticated();
                Map<String, Object> listingData = record(transaction.get(listing)); Lesson current = CloudCodec.lesson(listingData);
                if (!current.reviewed || !current.available || start <= System.currentTimeMillis()
                        || !CloudCodec.offered(listingData, start) || !LessonAvailability.offers(current, start, duration)
                        || (long) current.price * duration / current.duration != quote)
                    throw new IllegalStateException("The listing, quote or selected time changed. Choose a time again.");
                DocumentReference day = db().collection("tutorDays").document(CloudCodec.dayId(current.tutorId, start));
                DocumentSnapshot daySnapshot = transaction.get(day), existing = transaction.get(ref);
                if (existing.exists()) throw new IllegalStateException("Booking reference collision. Please try again.");
                Map<String, Object> busy = CloudCodec.reserveMinutes(CloudCodec.busy(daySnapshot.getData()), start, duration);
                Map<String, Object> payload = CloudCodec.bookingPayload(reference, current, actor.uid, actor.name, start, duration, quote, paymentKind, last4);
                payload.put("createdAt", FieldValue.serverTimestamp());
                transaction.set(day, CloudCodec.dayPayload(current.tutorId, start, reference, busy));
                transaction.set(ref, payload); return payload;
            }).onSuccessTask(TaskExecutors.MAIN_THREAD, confirmed -> {
                        actor.current(); cacheBooking(actor, confirmed);
                        Repo.Booking booking = findBooking(reference); Repo.lastBooking = booking;
                        return Tasks.forResult(booking);
                    });
        } catch (Exception error) { return Tasks.forException(error); }
    }

    public static Task<Void> cancelBooking(Repo.Booking booking) {
        try {
            final Actor actor = actor();
            if (!AccessPolicy.isLearner(actor.role) || booking == null || !actor.uid.equals(booking.learnerId)) throw new SecurityException("Not your booking");
            final String reference = booking.reference; final DocumentReference ref = bookingRef(reference);
            return db().runTransaction(transaction -> {
                actor.authenticated(); Map<String, Object> data = record(transaction.get(ref)); Repo.Booking current = CloudCodec.booking(data);
                if (!actor.uid.equals(current.learnerId) || current.cancelled || current.startAtMillis <= System.currentTimeMillis())
                    throw new IllegalStateException("Only your future, uncancelled booking can be cancelled");
                DocumentReference day = db().collection("tutorDays").document(CloudCodec.dayId(current.tutorId, current.startAtMillis));
                Map<String, Object> occupied = CloudCodec.busy(record(transaction.get(day)));
                Map<String, Object> remaining = CloudCodec.releaseMinutes(occupied, current.startAtMillis, current.duration);
                transaction.set(day, CloudCodec.dayPayload(current.tutorId, current.startAtMillis, reference, remaining));
                transaction.update(ref, "cancelled", true); data.put("cancelled", true); return data;
            }).onSuccessTask(TaskExecutors.MAIN_THREAD, confirmed -> {
                        actor.current(); cacheBooking(actor, confirmed); booking.cancelled = true; return Tasks.forResult(null);
                    });
        } catch (Exception error) { return Tasks.forException(error); }
    }

    public static Task<Void> setAccountActive(String uid, boolean active) {
        try {
            final Actor actor = actor(); role(actor, Repo.ROLE_ADMIN);
            if (uid == null || uid.isEmpty() || uid.contains("/")) throw new IllegalArgumentException("Choose an account");
            final DocumentReference ref = db().collection("profiles").document(uid);
            return db().runTransaction(transaction -> {
                actor.authenticated(); Map<String, Object> data = record(transaction.get(ref)); AccountStore.Account target = profile(data);
                if (target.role == Repo.ROLE_ADMIN) throw new SecurityException("Admin accounts cannot be suspended here");
                transaction.update(ref, "active", active); data.put("active", active); return data;
            }).onSuccessTask(TaskExecutors.MAIN_THREAD, confirmedData -> {
                        actor.current(); AccountStore.Account confirmed = profile(confirmedData); CloudAccounts.cacheAccount(confirmed);
                        for (Repo.User user : Repo.USERS) if (uid.equals(user.accountId)) user.active = confirmed.active;
                        return Tasks.forResult(null);
                    });
        } catch (Exception error) { return Tasks.forException(error); }
    }

    /** Records an admin release decision only; this does not transfer money. */
    public static Task<Void> releasePayout(Repo.Payout payout) {
        try {
            final Actor actor = actor(); role(actor, Repo.ROLE_ADMIN);
            if (payout == null) throw new IllegalArgumentException("Choose a payout");
            final DocumentReference ref = bookingRef(payout.reference);
            return db().runTransaction(transaction -> {
                actor.authenticated(); Map<String, Object> data = record(transaction.get(ref)); Repo.Booking current = CloudCodec.booking(data);
                if (current.cancelled || current.endAtMillis > System.currentTimeMillis() || CloudCodec.bool(data, "released"))
                    throw new IllegalStateException("Only a completed, unpaid session can be released");
                transaction.update(ref, "released", true); data.put("released", true); return data;
            }).onSuccessTask(TaskExecutors.MAIN_THREAD, confirmed -> {
                        actor.current(); cacheBooking(actor, confirmed); return Tasks.forResult(null);
                    });
        } catch (Exception error) { return Tasks.forException(error); }
    }

    /** Firestore rules evaluate the actual server time and participant identity. */
    public static Task<Void> joinSession(String reference) {
        try {
            final Actor actor = actor(); final DocumentReference ref = bookingRef(reference);
            final DocumentReference participant = db().collection("attendance").document(reference).collection("participants").document(actor.uid);
            return ref.get(Source.SERVER).onSuccessTask(TaskExecutors.MAIN_THREAD, snapshot -> {
                actor.current(); Repo.Booking booking = CloudCodec.booking(record(snapshot));
                if (booking.cancelled || !(actor.uid.equals(booking.learnerId) || actor.uid.equals(booking.tutorId)))
                    throw new SecurityException("Only the booked participants can join");
                Map<String, Object> entry = new HashMap<>(); entry.put("uid", actor.uid); entry.put("joinedAt", FieldValue.serverTimestamp());
                return participant.set(entry);
            }).onSuccessTask(TaskExecutors.MAIN_THREAD, ignored -> {
                        actor.current();
                        return Tasks.forResult(null);
                    });
        } catch (Exception error) { return Tasks.forException(error); }
    }

    public static String notificationKey(Repo.Notif notification) { return notificationKeys.get(notification); }

    public static Task<Void> markNotificationRead(String key) {
        try {
            final Actor actor = actor();
            if (key == null || !key.matches("[A-Za-z0-9_-]{1,200}")) throw new IllegalArgumentException("Invalid notification");
            final DocumentReference ref = db().collection("profiles").document(actor.uid).collection("notificationReads").document(key);
            return ref.set(Collections.singletonMap("read", true))
                    .onSuccessTask(TaskExecutors.MAIN_THREAD, ignored -> {
                        actor.current();
                        ensureCache(actor); notificationReads.put(key, true);
                        for (Map.Entry<Repo.Notif, String> entry : notificationKeys.entrySet()) if (key.equals(entry.getValue())) entry.getKey().read = true;
                        return Tasks.forResult(null);
                    });
        } catch (Exception error) { return Tasks.forException(error); }
    }

    private static boolean mayReadBooking(Actor actor, Repo.Booking booking) {
        return actor.role == Repo.ROLE_ADMIN || (actor.role == Repo.ROLE_TUTOR ? actor.uid.equals(booking.tutorId)
                : AccessPolicy.isLearner(actor.role) && actor.uid.equals(booking.learnerId));
    }
    private static void ensureCache(Actor actor) {
        if (!actor.uid.equals(cachedUid) || actor.role != cachedRole) {
            bookingRecords.clear(); notificationReads.clear(); notificationKeys.clear(); cachedUid = actor.uid; cachedRole = actor.role;
        }
    }
    static boolean applyLessonSnapshot(String uid, int role, List<Lesson> incoming) {
        if (!sameIdentity(uid, role)) return false;
        Map<Integer, Lesson> existing = new HashMap<>(); for (Lesson lesson : Repo.LESSONS) existing.put(lesson.id, lesson);
        List<Lesson> applied = new ArrayList<>();
        for (Lesson lesson : incoming) {
            Lesson canonical = existing.get(lesson.id); if (canonical == null) canonical = lesson;
            else CloudCodec.copyLesson(lesson, canonical, uid);
            canonical.mine = role == Repo.ROLE_TUTOR && uid.equals(canonical.tutorId); applied.add(canonical);
        }
        applied.sort(Comparator.comparingInt(value -> value.id)); Repo.LESSONS.clear(); Repo.LESSONS.addAll(applied);
        if (Repo.draftLesson != null) Repo.draftLesson = Repo.byId(Repo.draftLesson.id);
        return true;
    }
    private static Lesson upsertLesson(Actor actor, Lesson lesson) {
        Lesson canonical = Repo.byId(lesson.id);
        if (canonical == null) { canonical = lesson; Repo.LESSONS.add(0, canonical); }
        else CloudCodec.copyLesson(lesson, canonical, actor.uid);
        canonical.mine = actor.role == Repo.ROLE_TUTOR && actor.uid.equals(canonical.tutorId);
        if (Repo.draftLesson != null && Repo.draftLesson.id == canonical.id) Repo.draftLesson = canonical;
        return canonical;
    }
    private static void cacheBooking(Actor actor, Map<String, Object> data) {
        Repo.Booking booking = CloudCodec.booking(data);
        if (!mayReadBooking(actor, booking)) throw new SecurityException("Not your booking");
        ensureCache(actor); bookingRecords.put(booking.reference, new HashMap<>(data)); rebuildBookingViews(actor);
    }
    private static Repo.Booking findBooking(String reference) {
        for (Repo.Booking booking : Repo.BOOKINGS) if (reference.equals(booking.reference)) return booking;
        return null;
    }
    private static void rebuildBookingViews(Actor actor) {
        Repo.BOOKINGS.clear(); Repo.TUTOR_SESSIONS.clear(); Repo.PAYOUTS.clear();
        List<Map<String, Object>> sorted = new ArrayList<>(bookingRecords.values());
        sorted.sort((left, right) -> Long.compare(CloudCodec.number(right, "startAtMillis").longValue(), CloudCodec.number(left, "startAtMillis").longValue()));
        for (Map<String, Object> data : sorted) {
            Repo.Booking booking = CloudCodec.booking(data);
            if (!booking.cancelled) Repo.BOOKINGS.add(booking);
            Repo.TUTOR_SESSIONS.add(CloudCodec.session(data, booking));
            if (!booking.cancelled && (actor.role == Repo.ROLE_TUTOR || actor.role == Repo.ROLE_ADMIN)) {
                Repo.Payout payout = new Repo.Payout(); payout.title = booking.title; payout.tutor = booking.tutor; payout.tutorId = booking.tutorId;
                payout.reference = booking.reference; payout.amount = booking.price; payout.released = CloudCodec.bool(data, "released"); Repo.PAYOUTS.add(payout);
            }
        }
        if (Repo.lastBooking != null) Repo.lastBooking = findBooking(Repo.lastBooking.reference);
        rebuildNotifications(actor);
    }
    private static void rebuildNotifications(Actor actor) {
        ensureCache(actor); Repo.NOTIFS.clear(); notificationKeys.clear(); long now = System.currentTimeMillis();
        for (Map<String, Object> data : bookingRecords.values()) {
            Repo.Booking booking = CloudCodec.booking(data); if (booking.cancelled) continue;
            Object created = data.get("createdAt"); long createdAt = created instanceof Timestamp ? ((Timestamp) created).toDate().getTime() : now;
            if (AccessPolicy.isLearner(actor.role)) {
                addNotification(actor, "booked_" + booking.reference, Repo.N_BOOKING_CONFIRMED, booking.title, "", createdAt, now);
                if (now < booking.endAtMillis) addNotification(actor, "reminder_" + booking.reference, Repo.N_SESSION_REMINDER, booking.title, booking.timeRange, createdAt, now);
            } else if (actor.role == Repo.ROLE_TUTOR) {
                addNotification(actor, "booked_" + booking.reference, Repo.N_NEW_BOOKING, CloudCodec.string(data, "learner"), booking.title, createdAt, now);
                if (CloudCodec.bool(data, "released")) addNotification(actor, "released_" + booking.reference, Repo.N_PAYOUT_RELEASED, "₱" + booking.price, "", booking.endAtMillis, now);
            } else if (!CloudCodec.bool(data, "released") && booking.endAtMillis <= now) {
                addNotification(actor, "pending_" + booking.reference, Repo.N_PAYOUT_PENDING, booking.tutor, "₱" + booking.price, booking.endAtMillis, now);
            }
        }
        for (Lesson lesson : Repo.LESSONS) {
            if (actor.role == Repo.ROLE_TUTOR && lesson.reviewed && actor.uid.equals(lesson.tutorId))
                addNotification(actor, "approved_" + lesson.id, Repo.N_LISTING_APPROVED, lesson.title, "", now, now);
            else if (actor.role == Repo.ROLE_ADMIN && !lesson.reviewed)
                addNotification(actor, "review_" + lesson.id, Repo.N_REVIEW_NEEDED, lesson.title, "", now, now);
        }
    }
    private static void addNotification(Actor actor, String key, int type, String a, String b, long time, long now) {
        int group = actor.role == Repo.ROLE_ADMIN ? 2 : actor.role == Repo.ROLE_TUTOR ? 1 : 0;
        int minutes = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (now - time) / 60000L));
        Repo.Notif notification = new Repo.Notif(actor.uid, group, type, a, b, minutes, Boolean.TRUE.equals(notificationReads.get(key)));
        Repo.NOTIFS.add(notification); notificationKeys.put(notification, key);
    }
}
