package com.benten.senyas;

/** Role and participant rules, independent of caller-provided navigation arguments. */
public final class AccessPolicy {
    public enum Area { SHARED, LEARNER, TUTOR, ADMIN, SESSION }
    private AccessPolicy() { }

    public static boolean isLearner(int role) { return role == 0 || role == 1; }

    public static boolean allows(int role, Area area) {
        if (role < 0 || role > 3 || area == null) return false;
        switch (area) {
            case SHARED: return true;
            case LEARNER: return isLearner(role);
            case TUTOR: return role == 2;
            case ADMIN: return role == 3;
            case SESSION: return isLearner(role) || role == 2;
            default: return false;
        }
    }

    public static boolean isParticipant(int role, String accountId, String learnerId, String tutorId) {
        if (accountId == null || accountId.isEmpty()) return false;
        return (isLearner(role) && accountId.equals(learnerId)) || (role == 2 && accountId.equals(tutorId));
    }

    public static boolean canAttend(int role, String accountId, String learnerId, String tutorId,
                                    long start, long end, boolean cancelled, long now) {
        return isParticipant(role, accountId, learnerId, tutorId) && !cancelled
                && start > 0 && end > start && now >= start && now < end;
    }
}
