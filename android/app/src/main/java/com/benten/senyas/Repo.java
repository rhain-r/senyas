package com.benten.senyas;

import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared UI state plus the signed-in account's data. The marketplace keeps sample lessons,
 * sessions and payouts by the seeded tutors for the class demo; everything an account does
 * (practice, bookings, listings, notifications) is loaded from and saved to the device through
 * AccountStore. Replaced by Firestore in the Firebase batch.
 */
public final class Repo {
    private Repo() {}

    // ----- roles / identity -----
    public static final int ROLE_LEARNER = 0, ROLE_STUDENT = 1, ROLE_TUTOR = 2, ROLE_ADMIN = 3;
    public static int role = ROLE_LEARNER;
    public static String userName = "";
    public static String ownerName = ""; // the name on the signed-in account's listings and sessions

    public static String initialsOf(String name) {
        if (name == null) return "?";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty() && sb.length() < 2) sb.append(Character.toUpperCase(p.charAt(0)));
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    /** "Ana Reyes" -> "Ana". */
    public static String firstName(String name) {
        String n = name == null ? "" : name.trim();
        int space = n.indexOf(' ');
        return space > 0 ? n.substring(0, space) : n;
    }

    public static final String CAT_K12 = "K-12 Academic Support";
    public static final String CAT_TECH = "Digital & Tech Skills";
    public static final String CAT_VOC = "Vocational & TESDA Prep";
    public static final String CAT_LIFE = "Life & Financial Literacy";
    public static final String[] CATEGORIES = {CAT_K12, CAT_TECH, CAT_VOC, CAT_LIFE};

    public static final List<Lesson> LESSONS = new ArrayList<>();

    // ----- list filter state -----
    public static String filterCategory = null;   // null = all
    public static int filterDistanceKm = 5;
    public static String filterQuery = "";
    public static boolean offline = false;        // demo toggle: long-press the "Find a Deaf tutor" title
    public static boolean loadedOnce = false;

    public static void clearFilters() {
        filterCategory = null;
        filterDistanceKm = 5;
        filterQuery = "";
    }

    public static int activeFilterCount() {
        return (filterCategory != null ? 1 : 0) + (filterDistanceKm != 5 ? 1 : 0);
    }

    public static List<Lesson> query(String category, int maxKm, String q) {
        List<Lesson> out = new ArrayList<>();
        String needle = q == null ? "" : q.toLowerCase(Locale.ROOT).trim();
        for (Lesson l : LESSONS) {
            if (category != null && !category.equals(l.category)) continue;
            if (l.distanceKm > maxKm) continue;
            if (!needle.isEmpty()
                    && !l.title.toLowerCase(Locale.ROOT).contains(needle)
                    && !l.tutor.toLowerCase(Locale.ROOT).contains(needle)) continue;
            out.add(l);
        }
        return out;
    }

    public static Lesson byId(int id) {
        Lesson l = find(id);
        return l != null ? l : LESSONS.get(0);
    }

    /** The lesson with this id, or null when there is none. */
    @Nullable
    public static Lesson find(int id) {
        for (Lesson l : LESSONS) if (l.id == id) return l;
        return null;
    }

    public static String shortCategory(String c) {
        if (CAT_K12.equals(c)) return "K-12 Academic";
        if (CAT_TECH.equals(c)) return "Digital & Tech";
        if (CAT_VOC.equals(c)) return "Vocational & TESDA";
        return "Life & Financial";
    }

    // ----- booking draft + confirmed bookings -----
    public static Lesson draftLesson;
    public static String draftDay = "Mon, 19 October 2026";
    public static int draftStartMin = 15 * 60;
    public static int draftDuration = 60;
    public static int draftPrice = 400;
    public static String draftPayment = "GCash";
    public static int draftPayKind = 0; // 0 GCash, 1 Maya, 2 Card (only the masked last 4 digits are ever kept)

    public static class Booking {
        public String title, tutor, dayLabel, timeRange, payment, reference;
        public int duration, price;
        public boolean past;
    }

    /** The signed-in account's own bookings. */
    public static final List<Booking> BOOKINGS = new ArrayList<>();
    public static Booking lastBooking;
    private static int nextRef = MarketData.FIRST_REF;

    /** Books the draft once; null when there is no draft (e.g. after the app was closed). */
    @Nullable
    public static Booking confirmDraft() {
        if (draftLesson == null) return null;
        Booking b = new Booking();
        b.title = draftLesson.title;
        b.tutor = draftLesson.tutor;
        b.dayLabel = draftDay;
        b.timeRange = Ui.timeRange(draftStartMin, draftDuration);
        b.duration = draftDuration;
        b.price = draftPrice;
        b.payment = draftPayment;
        b.reference = "SEN-" + (nextRef++);
        BOOKINGS.add(0, b);
        lastBooking = b;
        String tutorKey = OWNERS.get(draftLesson.id); // null for the sample tutors
        TutorSession s = session(userName, draftLesson.tutor, draftLesson.title, draftLesson.category,
                b.dayLabel, b.timeRange, b.duration, b.price, b.reference, false);
        s.tutorKey = tutorKey;
        TUTOR_SESSIONS.add(0, s);
        Payout p = payout(draftLesson.title, draftLesson.tutor, draftPrice, false);
        p.tutorKey = tutorKey;
        p.reference = b.reference;
        PAYOUTS.add(0, p);
        addNotif(0, N_BOOKING_CONFIRMED, draftLesson.title, "", 0, false);
        if (tutorKey != null) {
            notifyAccount(tutorKey, new Notif(1, N_NEW_BOOKING, userName, draftLesson.title,
                    System.currentTimeMillis(), false));
        }
        draftLesson = null; // a second tap on Pay books nothing
        save();
        return b;
    }

    /** The account's booking with this reference, or null. */
    @Nullable
    public static Booking findBooking(@Nullable String reference) {
        if (reference == null) return null;
        for (Booking b : BOOKINGS) if (reference.equals(b.reference)) return b;
        return null;
    }

    /** Cancels a booking: the tutor's session and the unreleased payout with its reference go too. */
    public static void cancelBooking(Booking bk) {
        BOOKINGS.remove(bk);
        if (lastBooking == bk) lastBooking = null;
        String ref = bk.reference;
        if (ref != null && !ref.isEmpty()) {
            String tutorKey = null;
            for (TutorSession s : TUTOR_SESSIONS) if (ref.equals(s.reference)) tutorKey = s.tutorKey;
            TUTOR_SESSIONS.removeIf(s -> ref.equals(s.reference));
            PAYOUTS.removeIf(p -> !p.released && ref.equals(p.reference));
            if (tutorKey != null) {
                notifyAccount(tutorKey, new Notif(1, N_BOOKING_CANCELLED, userName, bk.title,
                        System.currentTimeMillis(), false));
            }
        }
        save();
    }

    // ----- learning scenarios + practice session -----
    public static final List<Scenario> SCENARIOS = new ArrayList<>();
    public static int practiceScenario = -1;
    public static int practiceIndex = 0;
    public static int[] practiceBest;   // -1 not tried, -2 skipped, else best score 0-100

    public static final String TODAY = "22 October 2026";
    public static int nextLessonId = MarketData.FIRST_LESSON_ID;
    public static Lesson draft;

    public static class Payout {
        public String title;
        public int amount;
        public boolean released;
        public String tutor;
        public String tutorKey; // email key of a tutor account; null for the sample tutors
        public String reference; // the booking it pays for; null for the sample payouts
    }
    public static final List<Payout> PAYOUTS = new ArrayList<>();

    public static class User {
        public final String name;
        public final int role;
        public boolean active;
        @Nullable public final String email; // account key; null for the sample users
        public User(String name, int role, boolean active) { this(name, role, active, null); }
        public User(String name, int role, boolean active, @Nullable String email) {
            this.name = name; this.role = role; this.active = active; this.email = email;
        }
    }
    public static final List<User> USERS = new ArrayList<>();

    /** One booked session, seen by the tutor (Sessions tab) and the admin (dashboard). */
    public static class TutorSession {
        public String learner, tutor, title, category, dayLabel, timeRange, reference;
        public String tutorKey; // email key of a tutor account; null for the sample tutors
        public int duration, price;
        public boolean past;
    }
    public static final List<TutorSession> TUTOR_SESSIONS = new ArrayList<>();

    private static TutorSession session(String learner, String tutor, String title, String cat, String day,
                                        String time, int dur, int price, String ref, boolean past) {
        TutorSession s = new TutorSession();
        s.learner = learner; s.tutor = tutor; s.title = title; s.category = cat; s.dayLabel = day;
        s.timeRange = time; s.duration = dur; s.price = price; s.reference = ref; s.past = past;
        return s;
    }

    private static Payout payout(String title, String tutor, int amount, boolean released) {
        Payout p = new Payout();
        p.title = title; p.tutor = tutor; p.amount = amount; p.released = released;
        return p;
    }

    public static int pendingPayout(String tutorOrNull) {
        int sum = 0;
        for (Payout p : PAYOUTS) {
            if (!p.released && (tutorOrNull == null || tutorOrNull.equals(p.tutor))) sum += p.amount;
        }
        return sum;
    }

    public static void startDraft() {
        draft = new Lesson(-1, "", ownerName, initialsOf(ownerName), CAT_VOC, 1.2, 0, 60, true, "", 0, 0xFF1E3AB8, false);
        draft.mine = true;
        draft.hasCover = false;
        draft.hasPreview = false;
        draft.created = TODAY;
        draft.day = "Friday, 23 October 2026";
        draft.slots = "";
    }

    /** Publishes the new-lesson draft; null when there is none or it has no title or rate. */
    @Nullable
    public static Lesson publishDraft() {
        Lesson l = draft;
        if (l == null || l.title == null || l.title.trim().isEmpty() || l.price <= 0) return null;
        l.id = nextLessonId++;
        l.previewSeconds = l.hasPreview ? 30 : 0;
        l.reviewed = false;
        LESSONS.add(0, l);
        if (account != null) OWNERS.put(l.id, account.email);
        addNotif(2, N_REVIEW_NEEDED, l.title, "", 0, false);
        draft = null;
        save();
        return l;
    }

    /** Listings published by the signed-in account. */
    public static List<Lesson> myLessons() {
        List<Lesson> out = new ArrayList<>();
        for (Lesson l : LESSONS) if (l.mine) out.add(l);
        return out;
    }

    /** Sessions learners booked on the signed-in tutor's listings. */
    public static List<TutorSession> mySessions() {
        List<TutorSession> out = new ArrayList<>();
        for (TutorSession s : TUTOR_SESSIONS) if (isMine(s.tutorKey)) out.add(s);
        return out;
    }

    public static List<Payout> myPayouts() {
        List<Payout> out = new ArrayList<>();
        for (Payout p : PAYOUTS) if (isMine(p.tutorKey)) out.add(p);
        return out;
    }

    public static int myPendingPayout() {
        int sum = 0;
        for (Payout p : myPayouts()) if (!p.released) sum += p.amount;
        return sum;
    }

    private static boolean isMine(@Nullable String tutorKey) {
        return account != null && account.email.equals(tutorKey);
    }

    public static int pendingPayout() {
        int sum = 0;
        for (Payout p : PAYOUTS) if (!p.released) sum += p.amount;
        return sum;
    }

    // ----- notifications (push delivery is Firebase Cloud Messaging later) -----
    public static final int N_BOOKING_CONFIRMED = 0, N_SESSION_REMINDER = 1, N_STREAK = 2, N_NEW_BOOKING = 3,
            N_PAYOUT_RELEASED = 4, N_LISTING_APPROVED = 5, N_REVIEW_NEEDED = 6, N_PAYOUT_PENDING = 7,
            N_BOOKING_CANCELLED = 8;

    public static class Notif {
        public final int group, type; // group: 0 learner, 1 tutor, 2 admin
        public final String a, b;
        public final long at;         // when it was created, epoch ms
        public boolean read;
        boolean sample;               // seeded demo data, never saved

        public Notif(int group, int type, String a, String b, long at, boolean read) {
            this.group = group; this.type = type; this.a = a; this.b = b;
            this.at = at; this.read = read;
        }

        public int minutesAgo(long now) {
            return (int) Math.max(0, (now - at) / 60_000L);
        }
    }
    public static final List<Notif> NOTIFS = new ArrayList<>();

    // ----- sign dictionary: every sign that has a bundled demonstration video -----
    public static final String[][] SIGNS = {
            {"Kumusta", "Hello"}, {"Magandang umaga", "Good morning"}, {"Magandang hapon", "Good afternoon"},
            {"Magandang gabi", "Good evening"}, {"Salamat", "Thank you"}, {"Walang anuman", "You're welcome"},
            {"Kita tayo bukas", "See you tomorrow"}, {"Oo", "Yes"}, {"Hindi", "No"},
            {"Tama", "Correct"}, {"Mali", "Wrong"}, {"Alam ko", "I know"}, {"Hindi ko alam", "I don't know"},
            {"Naiintindihan", "I understand"}, {"Hindi naiintindihan", "I don't understand"},
            {"Tinapay", "Bread"}, {"Itlog", "Egg"}, {"Kape", "Coffee"}, {"Gatas", "Milk"}, {"Asukal", "Sugar"},
            {"Lima", "Five"}, {"Dalawa", "Two"}, {"Mabagal", "Slow"}, {"Mabilis", "Fast"},
            {"Nanay", "Mother"}, {"Tatay", "Father"}};

    /** "How do I sign 'hospital'?" -> "hospital". Used for typed and spoken searches. */
    public static String normalizeQuery(String q) {
        if (q == null) return "";
        String s = q.toLowerCase(Locale.ROOT).replaceAll("[“”\"'?!.,]", " ").trim();
        s = s.replaceAll("^(how do i sign|how to sign|how do you sign|what is the sign for|sign for|sign|paano i-sign ang|paano mag-sign ng|paano ang sign ng)\\s+", "");
        s = s.replaceAll("^(the word|the|ang|ng)\\s+", "");
        return s.replaceAll("\\s+", " ").trim();
    }

    public static List<String[]> searchSigns(String q) {
        String n = normalizeQuery(q);
        List<String[]> out = new ArrayList<>();
        if (n.isEmpty()) return out;
        for (String[] s : SIGNS) {
            String w = s[0].toLowerCase(Locale.ROOT);
            String e = s[1].toLowerCase(Locale.ROOT).replace("?", "");
            if (w.contains(n) || e.contains(n) || e.replace("'", " ").contains(n)) out.add(s);
        }
        return out;
    }

    /** Where a dictionary word also appears in a practice scenario: {scenarioId, signIndex}, or null. */
    public static int[] findSign(String word) {
        for (Scenario sc : SCENARIOS) {
            for (int i = 0; i < sc.signs.length; i++) {
                if (sc.signs[i][0].equalsIgnoreCase(word)) return new int[]{sc.id, i};
            }
        }
        return null;
    }

    public static void addNotif(int group, int type, String a, String b, int minutesAgo, boolean read) {
        NOTIFS.add(0, new Notif(group, type, a, b, System.currentTimeMillis() - minutesAgo * 60_000L, read));
    }

    public static int myGroup() {
        return role == ROLE_ADMIN ? 2 : role == ROLE_TUTOR ? 1 : 0;
    }

    public static int unreadCount() {
        int n = 0;
        for (Notif x : NOTIFS) if (x.group == myGroup() && !x.read) n++;
        return n;
    }

    /** Opens a scenario in the Practice Room at a sign, with the account's saved scores for it. */
    public static void startPractice(int scenario, int index) {
        Scenario sc = SCENARIOS.get(scenario);
        int n = sc.signs.length;
        if (scenario != practiceScenario || practiceBest == null || practiceBest.length != n) {
            syncPractice(); // keep the scenario being left
            practiceScenario = scenario;
            practiceBest = data.progress.scores(sc);
        }
        practiceIndex = Math.min(Math.max(index, 0), n - 1);
    }

    /** "Practice again": back to the first sign. Best scores are kept; they belong to the account. */
    public static void restartPractice() {
        startPractice(practiceScenario < 0 ? 0 : practiceScenario, 0);
    }

    // ----- signed-in account -----
    private static AccountStore store;
    @Nullable private static Account account;
    private static AccountData data = new AccountData();
    /** Listing id -> email key of the account that published it (listings made on this device). */
    private static final Map<Integer, String> OWNERS = new HashMap<>();

    public static boolean isSignedIn(@Nullable String email) {
        return account != null && account.email.equals(Account.key(email));
    }

    /** Loads the account's own data over a fresh copy of the sample marketplace. */
    public static void signIn(AccountStore s, Account a, boolean adminConsole) {
        reset();
        store = s;
        account = a;
        role = adminConsole ? ROLE_ADMIN : a.role;
        userName = a.name;
        ownerName = a.name;

        MarketData m = s.loadMarket();
        nextLessonId = m.nextLessonId;
        nextRef = m.nextRef;
        for (Lesson l : m.lessons) l.mine = a.email.equals(m.owners.get(l.id));
        OWNERS.putAll(m.owners);
        LESSONS.addAll(0, m.lessons);
        TUTOR_SESSIONS.addAll(0, m.sessions);
        PAYOUTS.addAll(0, m.payouts);
        NOTIFS.addAll(m.notifs);
        for (Account other : s.all()) USERS.add(new User(other.name, other.role, other.active, other.email));

        data = s.loadData(a.email);
        BOOKINGS.addAll(data.bookings);
        NOTIFS.addAll(data.notifs);
        Collections.sort(NOTIFS, (x, y) -> Long.compare(y.at, x.at));

        int sc = Math.min(data.progress.lastScenario(), SCENARIOS.size() - 1);
        practiceScenario = -1;
        startPractice(sc, data.progress.position(sc));
    }

    /** Suspends or reactivates a user. Accounts are saved; the sample users change only in memory. */
    public static void setActive(User u, boolean active) {
        u.active = active;
        if (u.email != null && store != null) store.setActive(u.email, active);
    }

    /** Saves the account and its marketplace records, then clears everything it loaded. */
    public static void signOut() {
        save();
        account = null;
        reset();
    }

    /** Saves the signed-in account's data and the marketplace records made on this device. */
    public static void save() {
        if (account == null || store == null) return;
        syncPractice();
        data.bookings.clear();
        data.bookings.addAll(BOOKINGS);
        data.notifs.clear();
        int own = account.role == ROLE_TUTOR ? 1 : 0;
        MarketData m = new MarketData();
        m.nextLessonId = nextLessonId;
        m.nextRef = nextRef;
        for (Notif n : NOTIFS) {
            if (n.sample) continue;
            if (n.group == own) data.notifs.add(n);
            else if (n.group == 2) m.notifs.add(n);
        }
        for (Lesson l : LESSONS) {
            String owner = OWNERS.get(l.id);
            if (owner == null) continue;
            m.lessons.add(l);
            m.owners.put(l.id, owner);
        }
        for (TutorSession s : TUTOR_SESSIONS) if (s.tutorKey != null) m.sessions.add(s);
        for (Payout p : PAYOUTS) if (p.tutorKey != null) m.payouts.add(p);
        store.saveData(account.email, data);
        store.saveMarket(m);
    }

    /** The signed-in account's practice progress, including the Practice Room's latest scores. */
    public static Progress progress() {
        syncPractice();
        return data.progress;
    }

    public static long today() {
        return LocalDate.now().toEpochDay();
    }

    private static void syncPractice() {
        if (practiceScenario < 0 || practiceBest == null) return;
        data.progress.store(SCENARIOS.get(practiceScenario), practiceBest, today());
        data.progress.setPosition(practiceScenario, practiceIndex);
        data.progress.setLastScenario(practiceScenario);
    }

    private static void notifyAccount(String key, Notif n) {
        if (isMine(key)) NOTIFS.add(0, n);
        else if (store != null) store.deliver(key, n);
    }

    /** Tells the tutor account that published this listing (the sample tutors have none). */
    public static void notifyOwner(Lesson l, int type, String a, String b) {
        String key = OWNERS.get(l.id);
        if (key != null) notifyAccount(key, new Notif(1, type, a, b, System.currentTimeMillis(), false));
    }

    /** Tells the tutor account this payout belongs to (the sample tutors have none). */
    public static void notifyOwner(Payout p, int type, String a, String b) {
        if (p.tutorKey != null) notifyAccount(p.tutorKey, new Notif(1, type, a, b, System.currentTimeMillis(), false));
    }

    /** Back to the sample marketplace with no account data (also done before every sign-in). */
    private static void reset() {
        LESSONS.clear(); PAYOUTS.clear(); USERS.clear(); TUTOR_SESSIONS.clear();
        NOTIFS.clear(); BOOKINGS.clear(); OWNERS.clear();
        seedSamples();
        data = new AccountData();
        role = ROLE_LEARNER;
        userName = "";
        ownerName = "";
        lastBooking = null;
        draftLesson = null;
        draft = null;
        clearFilters();
        offline = false;
        loadedOnce = false;
        nextLessonId = MarketData.FIRST_LESSON_ID;
        nextRef = MarketData.FIRST_REF;
        practiceScenario = -1;
        practiceBest = null;
        startPractice(0, 0);
    }

    /** Sample marketplace for the class demo: lessons, sessions and payouts of the seeded tutors. */
    private static void seedSamples() {
        LESSONS.add(new Lesson(1, "Basic Bookkeeping in FSL", "Maria Santos", "MS", CAT_VOC, 1.2, 400, 60, true,
                "Learn to record sales and expenses for a small business, explained fully in Filipino Sign Language.",
                45, 0xFF1E3AB8, false));
        LESSONS.add(new Lesson(2, "Intro to Graphic Design", "Paolo Reyes", "PR", CAT_TECH, 2.8, 250, 30, true,
                "Start with layout, color and simple tools for making posters and social posts, taught in FSL.",
                40, 0xFFFFC22D, true));
        LESSONS.add(new Lesson(3, "Grade 7 Math: Fractions", "Liza Dela Cruz", "LD", CAT_K12, 4.5, 250, 30, false,
                "Add, subtract and compare fractions with worked visual examples signed step by step.",
                35, 0xFF111A33, false));
        LESSONS.add(new Lesson(4, "Everyday Budgeting in FSL", "Ben Aquino", "BA", CAT_LIFE, 8.0, 300, 60, true,
                "Plan weekly spending, savings and small loans using real market and jeepney costs.",
                50, 0xFF0F6B35, false));
        LESSONS.add(new Lesson(5, "Intro to Spreadsheets", "Joy Lim", "JL", CAT_TECH, 9.1, 350, 60, true,
                "Build a simple income and expense sheet with formulas, explained in FSL.",
                42, 0xFF6B3FA0, false));

        PAYOUTS.add(payout("Basic Bookkeeping", "Maria Santos", 400, false));
        PAYOUTS.add(payout("Basic Bookkeeping", "Maria Santos", 400, true));
        PAYOUTS.add(payout("Intro to Graphic Design", "Paolo Reyes", 250, false));
        PAYOUTS.add(payout("Everyday Budgeting in FSL", "Ben Aquino", 300, true));

        USERS.add(new User("Ana Reyes", ROLE_LEARNER, true));
        USERS.add(new User("Carlo Mendoza", ROLE_STUDENT, true));
        USERS.add(new User("Jun Dizon", ROLE_LEARNER, true));
        USERS.add(new User("Lea Ramos", ROLE_STUDENT, true));
        USERS.add(new User("Maria Santos", ROLE_TUTOR, true));
        USERS.add(new User("Paolo Reyes", ROLE_TUTOR, true));
        USERS.add(new User("Liza Dela Cruz", ROLE_TUTOR, true));
        USERS.add(new User("Ben Aquino", ROLE_TUTOR, true));
        USERS.add(new User("Joy Lim", ROLE_TUTOR, false));

        addNotif(2, N_PAYOUT_PENDING, "Paolo Reyes", "₱250", 300, false);
        addNotif(2, N_REVIEW_NEEDED, "Intro to Spreadsheets", "", 120, false);
        for (Notif n : NOTIFS) n.sample = true;

        String bk = "Basic Bookkeeping in FSL";
        TUTOR_SESSIONS.add(session("Ana Reyes", "Maria Santos", bk, CAT_VOC, "Mon, 19 October 2026", "3:00–4:00 pm", 60, 400, "SEN-830", false));
        TUTOR_SESSIONS.add(session("Carlo Mendoza", "Maria Santos", bk, CAT_VOC, "Tue, 20 October 2026", "2:00–3:00 pm", 60, 400, "SEN-827", false));
        TUTOR_SESSIONS.add(session("Lea Ramos", "Maria Santos", bk, CAT_VOC, "Fri, 9 October 2026", "4:00–5:00 pm", 60, 400, "SEN-815", true));
        TUTOR_SESSIONS.add(session("Ana Reyes", "Paolo Reyes", "Intro to Graphic Design", CAT_TECH, "Fri, 9 October 2026", "2:00–2:30 pm", 30, 250, "SEN-829", true));
        TUTOR_SESSIONS.add(session("Jun Dizon", "Paolo Reyes", "Intro to Graphic Design", CAT_TECH, "Sat, 10 October 2026", "9:00–9:30 am", 30, 250, "SEN-816", true));
        TUTOR_SESSIONS.add(session("Jun Dizon", "Liza Dela Cruz", "Grade 7 Math: Fractions", CAT_K12, "Sat, 3 October 2026", "10:00–10:30 am", 30, 250, "SEN-818", true));
        TUTOR_SESSIONS.add(session("Lea Ramos", "Liza Dela Cruz", "Grade 7 Math: Fractions", CAT_K12, "Sun, 4 October 2026", "10:00–10:30 am", 30, 250, "SEN-819", true));
        TUTOR_SESSIONS.add(session("Carlo Mendoza", "Ben Aquino", "Everyday Budgeting in FSL", CAT_LIFE, "Mon, 5 October 2026", "1:00–2:00 pm", 60, 300, "SEN-822", true));
    }

    // Runs last, after every static field above has its initial value.
    static {
        SCENARIOS.add(new Scenario(0, "At the sari-sari store", "Sari-sari store scenario",
                R.string.scenario_title_0, R.string.scenario_label_0, R.string.scenario_desc_0, new String[][]{
                {"Magandang umaga", "Good morning"}, {"Kumusta", "Hello"}, {"Tinapay", "Bread"},
                {"Itlog", "Egg"}, {"Kape", "Coffee"}, {"Gatas", "Milk"}, {"Asukal", "Sugar"},
                {"Lima", "Five"}, {"Salamat", "Thank you"}, {"Walang anuman", "You're welcome"}}));
        SCENARIOS.add(new Scenario(1, "Riding a jeepney", "Jeepney scenario",
                R.string.scenario_title_1, R.string.scenario_label_1, R.string.scenario_desc_1, new String[][]{
                {"Magandang hapon", "Good afternoon"}, {"Dalawa", "Two"}, {"Mabilis", "Fast"},
                {"Oo", "Yes"}, {"Salamat", "Thank you"}, {"Kita tayo bukas", "See you tomorrow"}}));
        SCENARIOS.add(new Scenario(2, "Public services", "Public service scenario",
                R.string.scenario_title_2, R.string.scenario_label_2, R.string.scenario_desc_2, new String[][]{
                {"Naiintindihan", "I understand"}, {"Hindi naiintindihan", "I don't understand"},
                {"Alam ko", "I know"}, {"Hindi ko alam", "I don't know"}, {"Oo", "Yes"},
                {"Hindi", "No"}, {"Salamat", "Thank you"}}));
        reset();
    }
}
