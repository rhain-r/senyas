package com.benten.senyas;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Dummy in-memory data + shared UI state. Replaced by Firestore in the Firebase batch. */
public final class Repo {
    private Repo() {}

    // ----- roles / identity -----
    public static final int ROLE_LEARNER = 0, ROLE_STUDENT = 1, ROLE_TUTOR = 2, ROLE_ADMIN = 3;
    public static int role = -1;
    public static String userName = "";
    public static String ownerName = "";
    private static AccountStore.Account identity;
    private static LearningProgressStore progressStore;
    private static RepoStore committedStore;
    static final java.util.Set<String> rewardedSigns = new java.util.HashSet<>();
    static int[][] pathBest;

    public static String accountId() { return identity == null ? "" : identity.id; }
    public static int accountRole() { return identity == null ? -1 : identity.role; }

    public static void activateAccount(AccountStore.Account account) {
        if (account == null || !account.active) { clearIdentity(); return; }
        boolean changed = !account.id.equals(accountId());
        if (changed) {
            saveProgress();
            rewardedSigns.clear();
            resetPathProgress();
            practiceBest = null;
            startPractice(0, 0);
            xp = dailyXp = streakDays = 0;
            clearFilters();
            draft = null;
            draftLesson = null;
            lastBooking = null;
            draftPayKind = 0;
            draftPayment = "GCash";
            draftStartAtMillis = 0;
        }
        identity = account;
        role = account.role;
        userName = account.name;
        ownerName = role == ROLE_TUTOR ? account.name : "";
        if (changed && progressStore != null && AccessPolicy.isLearner(account.role)) progressStore.load(account.id);
        for (Lesson lesson : LESSONS) lesson.mine = role == ROLE_TUTOR && account.id.equals(lesson.tutorId);
    }

    public static void clearIdentity() {
        saveProgress();
        rewardedSigns.clear();
        resetPathProgress();
        identity = null;
        role = -1;
        userName = ownerName = "";
        practiceBest = null;
        draft = null;
        draftLesson = null;
        lastBooking = null;
        draftPayKind = 0;
        draftPayment = "GCash";
        draftStartAtMillis = 0;
        for (Lesson lesson : LESSONS) lesson.mine = false;
    }

    private static void require(AccessPolicy.Area area) {
        if (identity == null || !AccessPolicy.allows(identity.role, area)) throw new SecurityException("Account access denied");
    }

    public static String initialsOf(String name) {
        if (name == null) return "?";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty() && sb.length() < 2) sb.append(Character.toUpperCase(p.charAt(0)));
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    private static String seedTutorId(String name) {
        return "Maria Santos".equals(name) ? AccountStore.DEMO_TUTOR : "sample-tutor:" + name;
    }

    private static String seedLearnerId(String name) {
        return "Ana Reyes".equals(name) ? AccountStore.DEMO_STUDENT : "sample-learner:" + name;
    }

    public static final String CAT_K12 = "K-12 Academic Support";
    public static final String CAT_TECH = "Digital & Tech Skills";
    public static final String CAT_VOC = "Vocational & TESDA Prep";
    public static final String CAT_LIFE = "Life & Financial Literacy";
    public static final String[] CATEGORIES = {CAT_K12, CAT_TECH, CAT_VOC, CAT_LIFE};

    public static final List<Lesson> LESSONS = new ArrayList<>();
    static {
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
    }

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
            if (!l.reviewed) continue;
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
        for (Lesson l : LESSONS) if (l.id == id) return l;
        return null;
    }

    public static boolean isBookable(Lesson lesson) {
        return lesson != null && byId(lesson.id) == lesson && lesson.reviewed && lesson.available;
    }

    public static final class StorageException extends IllegalStateException {
        StorageException() { super("The change could not be saved"); }
    }

    public static boolean persist() {
        saveProgress();
        boolean saved = committedStore == null || committedStore.save();
        if (!saved) CameraDiagnostics.event("committed_state_save_failed");
        return saved;
    }

    private static boolean saveProgress() {
        return progressStore == null || identity == null || !AccessPolicy.isLearner(identity.role)
                || progressStore.save(identity.id);
    }

    public static void initialize(android.content.Context context) {
        if (committedStore == null) {
            committedStore = new RepoStore(context.getApplicationContext());
            committedStore.load();
        }
        if (progressStore == null) progressStore = new LearningProgressStore(
                context.getApplicationContext().getSharedPreferences("senyas_learning_progress", android.content.Context.MODE_PRIVATE));
    }

    public static Lesson ownedLesson(int id) {
        Lesson lesson = byId(id);
        return lesson != null && ownsLesson(lesson) ? lesson : null;
    }

    public static boolean ownsLesson(Lesson lesson) {
        return lesson != null && accountRole() == ROLE_TUTOR && accountId().equals(lesson.tutorId);
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
    public static long draftStartAtMillis;

    public static class Booking {
        public String title, tutor, dayLabel, timeRange, payment, reference;
        public int duration, price;
        public boolean past;
        public String learnerId, tutorId;
        public long startAtMillis, endAtMillis;
        public boolean cancelled;
    }

    public static final List<Booking> BOOKINGS = new ArrayList<>();
    public static Booking lastBooking;
    static int nextRef = 831;

    public static Booking confirmDraft() {
        require(AccessPolicy.Area.LEARNER);
        if (!isBookable(draftLesson) || draftStartAtMillis <= System.currentTimeMillis()
                || draftLesson.duration <= 0 || draftLesson.price <= 0
                || (long) draftLesson.price * draftDuration / draftLesson.duration != draftPrice
                || !LessonAvailability.offers(draftLesson, draftStartAtMillis, draftDuration)
                || !slotAvailable(draftLesson.tutorId, draftStartAtMillis, draftDuration)) {
            throw new IllegalStateException("The selected slot is unavailable");
        }
        int previousRef = nextRef, previousNotifications = NOTIFS.size();
        Booking previousBooking = lastBooking;
        Booking b = new Booking();
        b.learnerId = accountId();
        b.tutorId = draftLesson.tutorId;
        b.startAtMillis = draftStartAtMillis;
        b.endAtMillis = SessionTime.endAt(b.startAtMillis, draftDuration);
        b.title = draftLesson.title;
        b.tutor = draftLesson.tutor;
        b.dayLabel = LessonAvailability.formatDay(java.time.Instant.ofEpochMilli(b.startAtMillis).atZone(SessionTime.ZONE).toLocalDate());
        int startMinute = java.time.Instant.ofEpochMilli(b.startAtMillis).atZone(SessionTime.ZONE).getHour() * 60
                + java.time.Instant.ofEpochMilli(b.startAtMillis).atZone(SessionTime.ZONE).getMinute();
        b.timeRange = Ui.timeRange(startMinute, draftDuration);
        b.duration = draftDuration;
        b.price = draftPrice;
        b.payment = draftPayment;
        b.reference = "SEN-" + (nextRef++);
        BOOKINGS.add(0, b);
        lastBooking = b;
        TutorSession s = session(userName, draftLesson.tutor, draftLesson.title, draftLesson.category,
                b.dayLabel, b.timeRange, b.duration, b.price, b.reference, false);
        s.learnerId = b.learnerId;
        s.tutorId = b.tutorId;
        s.startAtMillis = b.startAtMillis;
        s.endAtMillis = b.endAtMillis;
        TUTOR_SESSIONS.add(0, s);
        Payout payout = payout(draftLesson.title, draftLesson.tutor, draftPrice, false);
        payout.tutorId = b.tutorId;
        payout.reference = b.reference;
        PAYOUTS.add(0, payout);
        addNotif(0, N_BOOKING_CONFIRMED, draftLesson.title, "", 0, false);
        addNotifFor(b.tutorId, 1, N_NEW_BOOKING, userName, draftLesson.title, 0, false);
        if (!persist()) {
            BOOKINGS.remove(b); TUTOR_SESSIONS.remove(s); PAYOUTS.remove(payout);
            while (NOTIFS.size() > previousNotifications) NOTIFS.remove(0);
            nextRef = previousRef; lastBooking = previousBooking;
            throw new StorageException();
        }
        return b;
    }

    // ----- learning scenarios + practice session -----
    public static final List<Scenario> SCENARIOS = new ArrayList<>();
    public static int practiceScenario = -1;
    public static int practiceIndex = 0;
    public static int[] practiceBest;   // -1 not tried, -2 skipped, else best score 0-100

    // ----- account-owned learning progress -----
    public static int streakDays = 0;
    public static int xp = 0;
    public static int dailyXp = 0;
    public static int dailyGoalXp = 50;

    public static void addXp(int n) {
        xp += n;
        dailyXp += n;
    }

    public static final String TODAY = "22 October 2026";
    public static int nextLessonId = 6;
    public static Lesson draft;

    public static class Payout {
        public String title;
        public int amount;
        public boolean released;
        public String tutor;
        public String tutorId;
        public String reference = "";
    }
    public static final List<Payout> PAYOUTS = new ArrayList<>();

    public static class User {
        public final String name;
        public final int role;
        public boolean active;
        public String accountId;
        public User(String name, int role, boolean active) { this.name = name; this.role = role; this.active = active; }
    }
    public static final List<User> USERS = new ArrayList<>();

    /** One booked session, seen by the tutor (Sessions tab) and the admin (dashboard). */
    public static class TutorSession {
        public String learner, tutor, title, category, dayLabel, timeRange, reference;
        public int duration, price;
        public boolean past;
        public String learnerId, tutorId;
        public long startAtMillis, endAtMillis;
        public boolean cancelled;
    }
    public static final List<TutorSession> TUTOR_SESSIONS = new ArrayList<>();

    private static TutorSession session(String learner, String tutor, String title, String cat, String day,
                                        String time, int dur, int price, String ref, boolean past) {
        TutorSession s = new TutorSession();
        s.learner = learner; s.tutor = tutor; s.title = title; s.category = cat; s.dayLabel = day;
        s.timeRange = time; s.duration = dur; s.price = price; s.reference = ref; s.past = past;
        s.learnerId = seedLearnerId(learner);
        s.tutorId = seedTutorId(tutor);
        s.startAtMillis = SessionTime.parseStart(day, time);
        s.endAtMillis = SessionTime.endAt(s.startAtMillis, dur);
        return s;
    }

    private static Payout payout(String title, String tutor, int amount, boolean released) {
        Payout p = new Payout();
        p.title = title; p.tutor = tutor; p.amount = amount; p.released = released;
        p.tutorId = seedTutorId(tutor);
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
        require(AccessPolicy.Area.TUTOR);
        draft = new Lesson(-1, "", ownerName, initialsOf(ownerName), CAT_VOC, 1.2, 0, 60, true, "", 0, 0xFF1E3AB8, false);
        draft.tutorId = accountId();
        draft.mine = true;
        draft.hasCover = false;
        draft.hasPreview = false;
        draft.created = TODAY;
        draft.day = "";
        draft.slots = "";
    }

    public static Lesson publishDraft() {
        require(AccessPolicy.Area.TUTOR);
        if (draft == null || !accountId().equals(draft.tutorId)) throw new SecurityException("Not your listing");
        if (draft.title == null || draft.title.trim().isEmpty() || draft.title.length() > 200 || draft.price <= 0
                || draft.description == null || draft.description.length() > 10000
                || (draft.duration != 30 && draft.duration != 60)
                || !java.util.Arrays.asList(CATEGORIES).contains(draft.category)
                || draft.city == null || draft.city.trim().isEmpty() || draft.city.length() > 200
                || LessonAvailability.validate(LessonAvailability.dateLabels(draft), draft.slots, draft.duration, System.currentTimeMillis()) != LessonAvailability.Error.NONE)
            throw new IllegalStateException("Incomplete listing details");
        Lesson l = draft;
        int previousId = l.id, previousSequence = nextLessonId, previousPreview = l.previewSeconds;
        int previousNotifications = NOTIFS.size();
        boolean previousReviewed = l.reviewed;
        l.id = nextLessonId++;
        l.previewSeconds = l.hasPreview ? 30 : 0;
        l.reviewed = false;
        LESSONS.add(0, l);
        addNotif(2, N_REVIEW_NEEDED, l.title, "", 0, false);
        draft = null;
        if (!persist()) {
            LESSONS.remove(l);
            while (NOTIFS.size() > previousNotifications) NOTIFS.remove(0);
            nextLessonId = previousSequence; l.id = previousId; l.previewSeconds = previousPreview;
            l.reviewed = previousReviewed; draft = l;
            throw new StorageException();
        }
        return l;
    }

    public static List<Lesson> myLessons() {
        List<Lesson> out = new ArrayList<>();
        for (Lesson l : LESSONS) if (accountRole() == ROLE_TUTOR && accountId().equals(l.tutorId)) out.add(l);
        return out;
    }

    public static int pendingPayout() {
        int sum = 0;
        for (Payout p : PAYOUTS) if (!p.released) sum += p.amount;
        return sum;
    }

    // ----- notifications (sample data; push delivery is Firebase Cloud Messaging later) -----
    public static final int N_BOOKING_CONFIRMED = 0, N_SESSION_REMINDER = 1, N_STREAK = 2, N_NEW_BOOKING = 3,
            N_PAYOUT_RELEASED = 4, N_LISTING_APPROVED = 5, N_REVIEW_NEEDED = 6, N_PAYOUT_PENDING = 7;

    public static class Notif {
        public final int group, type; // group: 0 learner, 1 tutor, 2 admin
        public final String a, b, recipientId;
        public int minutesAgo;
        public boolean read;
        public Notif(int group, int type, String a, String b, int minutesAgo, boolean read) {
            this(seedRecipient(group), group, type, a, b, minutesAgo, read);
        }
        public Notif(String recipientId, int group, int type, String a, String b, int minutesAgo, boolean read) {
            this.recipientId = recipientId;
            this.group = group; this.type = type; this.a = a; this.b = b;
            this.minutesAgo = minutesAgo; this.read = read;
        }
    }
    public static final List<Notif> NOTIFS = new ArrayList<>();

    static {
        SCENARIOS.add(new Scenario(0, "At the sari-sari store", "Sari-sari store scenario",
                "Beginner", new String[][]{
                {"Kumusta", "Hello"}, {"Bili", "Buy"}, {"Magkano", "How much?"}, {"Salamat", "Thank you"},
                {"Pakiusap", "Please"}, {"Sukli", "Change"}, {"Tubig", "Water"}, {"Mura", "Cheap"},
                {"Mahal", "Expensive"}, {"Paalam", "Goodbye"}}));
        SCENARIOS.add(new Scenario(1, "Riding a jeepney", "Jeepney scenario",
                "Fares, stops, and polite requests", new String[][]{
                {"Para", "Stop here"}, {"Bayad", "Fare"}, {"Estudyante", "Student"},
                {"Saan", "Where?"}, {"Salamat", "Thank you"}}));
        SCENARIOS.add(new Scenario(2, "Public services", "Public service scenario",
                "Asking for help", new String[][]{
                {"Tulong", "Help"}, {"Doktor", "Doctor"}, {"Pulis", "Police"},
                {"Porma", "Form"}, {"Salamat", "Thank you"}}));
        resetPathProgress();
        startPractice(0, 0);

        for (Lesson lesson : LESSONS) lesson.tutorId = seedTutorId(lesson.tutor);
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

        addNotif(0, N_BOOKING_CONFIRMED, "Basic Bookkeeping in FSL", "", 1500, true);
        addNotif(0, N_STREAK, "3", "", 600, false);
        addNotif(0, N_SESSION_REMINDER, "Basic Bookkeeping in FSL", "3:00–4:00 pm", 90, false);
        addNotif(1, N_PAYOUT_RELEASED, "₱400", "", 2900, true);
        addNotif(1, N_NEW_BOOKING, "Carlo Mendoza", "Basic Bookkeeping in FSL", 240, false);
        addNotif(2, N_PAYOUT_PENDING, "Paolo Reyes", "₱250", 300, false);
        addNotif(2, N_REVIEW_NEEDED, "Intro to Spreadsheets", "", 120, false);

        String bk = "Basic Bookkeeping in FSL";
        TUTOR_SESSIONS.add(session("Ana Reyes", "Maria Santos", bk, CAT_VOC, "Mon, 19 October 2026", "3:00–4:00 pm", 60, 400, "SEN-830", false));
        TUTOR_SESSIONS.add(session("Carlo Mendoza", "Maria Santos", bk, CAT_VOC, "Tue, 20 October 2026", "2:00–3:00 pm", 60, 400, "SEN-827", false));
        TUTOR_SESSIONS.add(session("Lea Ramos", "Maria Santos", bk, CAT_VOC, "Fri, 9 October 2026", "4:00–5:00 pm", 60, 400, "SEN-815", true));
        TUTOR_SESSIONS.add(session("Ana Reyes", "Paolo Reyes", "Intro to Graphic Design", CAT_TECH, "Fri, 9 October 2026", "2:00–2:30 pm", 30, 250, "SEN-829", true));
        TUTOR_SESSIONS.add(session("Jun Dizon", "Paolo Reyes", "Intro to Graphic Design", CAT_TECH, "Sat, 10 October 2026", "9:00–9:30 am", 30, 250, "SEN-816", true));
        TUTOR_SESSIONS.add(session("Jun Dizon", "Liza Dela Cruz", "Grade 7 Math: Fractions", CAT_K12, "Sat, 3 October 2026", "10:00–10:30 am", 30, 250, "SEN-818", true));
        TUTOR_SESSIONS.add(session("Lea Ramos", "Liza Dela Cruz", "Grade 7 Math: Fractions", CAT_K12, "Sun, 4 October 2026", "10:00–10:30 am", 30, 250, "SEN-819", true));
        TUTOR_SESSIONS.add(session("Carlo Mendoza", "Ben Aquino", "Everyday Budgeting in FSL", CAT_LIFE, "Mon, 5 October 2026", "1:00–2:00 pm", 60, 300, "SEN-822", true));

        Booking past = new Booking();
        past.title = "Intro to Graphic Design"; past.tutor = "Paolo Reyes";
        past.dayLabel = "Fri, 9 October 2026"; past.timeRange = "2:00–2:30 pm";
        past.duration = 30; past.price = 250; past.payment = "GCash"; past.reference = "SEN-829"; past.past = true;
        Booking next = new Booking();
        next.title = "Basic Bookkeeping in FSL"; next.tutor = "Maria Santos";
        next.dayLabel = "Mon, 19 October 2026"; next.timeRange = "3:00–4:00 pm";
        next.duration = 60; next.price = 400; next.payment = "GCash"; next.reference = "SEN-830";
        past.learnerId = next.learnerId = AccountStore.DEMO_STUDENT;
        past.tutorId = seedTutorId(past.tutor);
        next.tutorId = seedTutorId(next.tutor);
        past.startAtMillis = SessionTime.parseStart(past.dayLabel, past.timeRange);
        past.endAtMillis = SessionTime.endAt(past.startAtMillis, past.duration);
        next.startAtMillis = SessionTime.parseStart(next.dayLabel, next.timeRange);
        next.endAtMillis = SessionTime.endAt(next.startAtMillis, next.duration);
        BOOKINGS.add(next);
        BOOKINGS.add(past);
    }

    // ----- sign dictionary (sample data: the real dictionary comes from the database) -----
    public static final String[][] SIGNS = {
            {"Kumusta", "Hello"}, {"Mahal kita", "I love you"}, {"Magandang umaga", "Good morning"}, {"Magandang gabi", "Good evening"},
            {"Salamat", "Thank you"}, {"Walang anuman", "You're welcome"}, {"Paalam", "Goodbye"},
            {"Pakiusap", "Please"}, {"Oo", "Yes"}, {"Hindi", "No"}, {"Pasensya", "Sorry"},
            {"Tulong", "Help"}, {"Ospital", "Hospital"}, {"Doktor", "Doctor"}, {"Nars", "Nurse"},
            {"Gamot", "Medicine"}, {"Masakit", "Pain"}, {"Pulis", "Police"}, {"Emergency", "Emergency"},
            {"Porma", "Form"}, {"Bili", "Buy"}, {"Magkano", "How much?"}, {"Sukli", "Change"},
            {"Tubig", "Water"}, {"Pagkain", "Food"}, {"Mura", "Cheap"}, {"Mahal", "Expensive"},
            {"Para", "Stop here"}, {"Bayad", "Fare"}, {"Estudyante", "Student"}, {"Saan", "Where?"},
            {"Dyip", "Jeepney"}, {"Nanay", "Mother"}, {"Tatay", "Father"}, {"Kapatid", "Sibling"},
            {"Pamilya", "Family"}, {"Paaralan", "School"}, {"Trabaho", "Work"}, {"Kaibigan", "Friend"},
            {"Banyo", "Restroom"}};

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
            String w = normalizeQuery(s[0]);
            String e = normalizeQuery(s[1]);
            if (w.contains(n) || e.contains(n)) out.add(s);
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
        String recipient = identity != null && group == myGroup() ? accountId() : seedRecipient(group);
        addNotifFor(recipient, group, type, a, b, minutesAgo, read);
    }

    private static String seedRecipient(int group) {
        return group == 2 ? AccountStore.DEMO_ADMIN : group == 1 ? AccountStore.DEMO_TUTOR : AccountStore.DEMO_STUDENT;
    }

    public static void addNotifFor(String recipient, int group, int type, String a, String b, int minutesAgo, boolean read) {
        if (recipient != null && !recipient.isEmpty()) NOTIFS.add(0, new Notif(recipient, group, type, a, b, minutesAgo, read));
    }

    public static List<Notif> myNotifications() {
        List<Notif> result = new ArrayList<>();
        if (identity != null) for (Notif notification : NOTIFS)
            if (notification.group == myGroup() && accountId().equals(notification.recipientId)) result.add(notification);
        return result;
    }

    public static int myGroup() {
        return role == ROLE_ADMIN ? 2 : role == ROLE_TUTOR ? 1 : 0;
    }

    public static int unreadCount() {
        int n = 0;
        for (Notif x : myNotifications()) if (!x.read) n++;
        return n;
    }

    public static List<TutorSession> sessionsFor(String tutor) {
        List<TutorSession> out = new ArrayList<>();
        for (TutorSession s : TUTOR_SESSIONS) if (accountRole() == ROLE_TUTOR && accountId().equals(s.tutorId)) out.add(s);
        return out;
    }

    public static List<Booking> myBookings() {
        List<Booking> out = new ArrayList<>();
        if (AccessPolicy.isLearner(accountRole())) for (Booking booking : BOOKINGS)
            if (accountId().equals(booking.learnerId)) out.add(booking);
        return out;
    }

    public static TutorSession sessionByReference(String reference) {
        if (reference == null || reference.isEmpty()) return null;
        for (TutorSession session : TUTOR_SESSIONS) if (reference.equals(session.reference)) return session;
        return null;
    }

    public static boolean canJoinSession(String reference, long now) {
        TutorSession session = sessionByReference(reference);
        return session != null && AccessPolicy.canAttend(accountRole(), accountId(), session.learnerId,
                session.tutorId, session.startAtMillis, session.endAtMillis, session.cancelled || session.past, now);
    }

    public static boolean isPast(Booking booking, long now) {
        return booking.past || booking.cancelled || (booking.endAtMillis > 0 && now >= booking.endAtMillis);
    }

    public static boolean isPast(TutorSession session, long now) {
        return session.past || session.cancelled || (session.endAtMillis > 0 && now >= session.endAtMillis);
    }

    public static boolean slotAvailable(String tutorId, long start, int duration) {
        long end = SessionTime.endAt(start, duration);
        if (tutorId == null || tutorId.isEmpty() || start <= 0 || end <= start) return false;
        for (TutorSession session : TUTOR_SESSIONS) if (!session.cancelled
                && (tutorId.equals(session.tutorId) || (AccessPolicy.isLearner(accountRole()) && accountId().equals(session.learnerId)))
                && start < session.endAtMillis && end > session.startAtMillis) return false;
        return true;
    }

    public static boolean cancelBooking(Booking booking) {
        if (booking == null || !BOOKINGS.contains(booking) || !AccessPolicy.isLearner(accountRole())
                || !accountId().equals(booking.learnerId) || isPast(booking, System.currentTimeMillis())) return false;
        int originalIndex = BOOKINGS.indexOf(booking);
        List<Payout> previousPayouts = new ArrayList<>(PAYOUTS);
        booking.cancelled = true;
        TutorSession session = sessionByReference(booking.reference);
        boolean previouslyCancelled = session != null && session.cancelled;
        if (session != null) session.cancelled = true;
        BOOKINGS.remove(booking);
        PAYOUTS.removeIf(payout -> !payout.released && booking.reference.equals(payout.reference));
        if (!persist()) {
            booking.cancelled = false;
            if (session != null) session.cancelled = previouslyCancelled;
            BOOKINGS.add(originalIndex, booking);
            PAYOUTS.clear(); PAYOUTS.addAll(previousPayouts);
            return false;
        }
        return true;
    }

    public static int myPendingPayout() {
        int total = 0;
        if (accountRole() == ROLE_TUTOR) for (Payout payout : PAYOUTS)
            if (!payout.released && accountId().equals(payout.tutorId)) total += payout.amount;
        return total;
    }

    public static void syncAccountUsers(AccountStore store) {
        USERS.clear();
        for (AccountStore.Account account : store.profiles()) {
            User user = new User(account.name, account.role, account.active);
            user.accountId = account.id;
            USERS.add(user);
        }
    }

    static void resetPathProgress() {
        pathBest = new int[SCENARIOS.size()][];
        for (int s = 0; s < pathBest.length; s++) {
            pathBest[s] = new int[SCENARIOS.get(s).signs.length];
            java.util.Arrays.fill(pathBest[s], -1);
        }
    }

    public static int pathScore(int scenario, int index) { return pathBest[scenario][index]; }

    public static int resumeSignIndex(int scenario) {
        int[] scores = pathBest[scenario];
        for (int i = 0; i < scores.length; i++) if (scores[i] == -1) return i;
        return scores.length - 1;
    }

    public static int scenarioProgressPct(int scenario) {
        int completed = 0;
        for (int score : pathBest[scenario]) if (score >= 85) completed++;
        return completed * 100 / pathBest[scenario].length;
    }

    public static void startPractice(int scenario, int index) {
        int n = SCENARIOS.get(scenario).signs.length;
        if (scenario != practiceScenario || practiceBest == null || practiceBest.length != n) {
            practiceScenario = scenario;
            practiceBest = pathBest[scenario].clone();
        }
        practiceIndex = Math.min(Math.max(index, 0), n - 1);
    }

    /** Skip without replacing an earned score or awarding XP. Returns whether another sign exists. */
    public static boolean skipPracticeSign() {
        if (practiceBest[practiceIndex] < 0) practiceBest[practiceIndex] = -2;
        if (pathBest[practiceScenario][practiceIndex] == -1)
            pathBest[practiceScenario][practiceIndex] = practiceBest[practiceIndex];
        if (practiceIndex + 1 >= practiceBest.length) { saveProgress(); return false; }
        practiceIndex++;
        saveProgress();
        return true;
    }

    /** Called only after a stable, accepted model result for the currently displayed sign. */
    static boolean recordVerifiedMatch(int scenario, int index, int score) {
        if (!AccessPolicy.isLearner(accountRole()) || scenario != practiceScenario || index != practiceIndex
                || scenario < 0 || scenario >= SCENARIOS.size() || practiceBest == null
                || index < 0 || index >= practiceBest.length || score < 85 || score > 100) return false;
        String word = SCENARIOS.get(scenario).signs[index][0];
        if (FslSigns.label(word) < 0) return false;
        boolean first = !rewardedSigns.contains(word);
        if (!first && practiceBest[index] >= score) return true;
        if (first && (xp > Integer.MAX_VALUE - 10 || dailyXp > Integer.MAX_VALUE - 10)) return false;
        int previous = practiceBest[index], previousPath = pathBest[scenario][index], previousXp = xp, previousDaily = dailyXp;
        practiceBest[index] = Math.max(previous, score);
        pathBest[scenario][index] = Math.max(previousPath, score);
        if (first) { rewardedSigns.add(word); addXp(10); }
        if (!saveProgress()) {
            practiceBest[index] = previous; xp = previousXp; dailyXp = previousDaily;
            pathBest[scenario][index] = previousPath;
            if (first) rewardedSigns.remove(word);
            CameraDiagnostics.event("verified_feedback_save_failed");
            return false;
        }
        return true;
    }

    public static void restartPractice() {
        practiceBest = null;
        startPractice(practiceScenario < 0 ? 0 : practiceScenario, 0);
        java.util.Arrays.fill(practiceBest, -1);
        saveProgress();
    }
}
