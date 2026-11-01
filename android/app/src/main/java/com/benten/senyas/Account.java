package com.benten.senyas;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** A local account on this device. The password is kept only as a salted hash (see Passwords). */
public final class Account {
    public final String name;
    public final int role;      // Repo.ROLE_LEARNER, ROLE_STUDENT or ROLE_TUTOR
    public final String email;  // the key: trimmed and lowercase
    final String passwordHash;
    public final boolean active; // false once an admin suspends it

    public Account(String name, int role, String email, String passwordHash) {
        this(name, role, email, passwordHash, true);
    }

    public Account(String name, int role, String email, String passwordHash, boolean active) {
        this.name = name;
        this.role = role >= Repo.ROLE_LEARNER && role <= Repo.ROLE_TUTOR ? role : Repo.ROLE_LEARNER;
        this.email = key(email);
        this.passwordHash = passwordHash;
        this.active = active;
    }

    /** The same account, suspended or reactivated. */
    Account withActive(boolean on) {
        return new Account(name, role, email, passwordHash, on);
    }

    /** Emails are matched without regard to case or surrounding spaces. */
    public static String key(@Nullable String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    String toJson() {
        try {
            return new JSONObject().put("name", name).put("role", role).put("email", email)
                    .put("hash", passwordHash).put("active", active).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Null when there is no saved account or it cannot be read. */
    @Nullable
    static Account fromJson(@Nullable String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            String email = o.optString("email", ""), hash = o.optString("hash", "");
            if (email.isEmpty() || hash.isEmpty()) return null;
            // Accounts saved before suspension existed have no flag: they are active.
            return new Account(o.optString("name", ""), o.optInt("role", Repo.ROLE_LEARNER), email, hash,
                    o.optBoolean("active", true));
        } catch (JSONException e) {
            return null;
        }
    }
}
