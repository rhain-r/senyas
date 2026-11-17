package com.benten.senyas;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Offline account store; connected builds delegate identity and profiles to Firebase. */
public final class AccountStore {
    public static final String DEMO_STUDENT = "student@senyas.local";
    public static final String DEMO_TUTOR = "tutor@senyas.local";
    public static final String DEMO_ADMIN = "admin@senyas.local";
    private static final String PREFIX = "account:";
    private static final int ITERATIONS = 210000;
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;

    public static final class Account {
        public final String id, email, name;
        public final int role;
        public final boolean active;
        private Account(JSONObject record) throws JSONException {
            email = normalize(record.getString("email"));
            id = email;
            name = record.getString("name");
            role = record.getInt("role");
            active = record.getBoolean("active");
            if (email.isEmpty() || role < 0 || role > 3) throw new JSONException("Invalid account");
        }
        private Account(String uid, String email, String name, int role, boolean active) {
            this.id = uid;
            this.email = normalize(email);
            this.name = name.trim();
            this.role = role;
            this.active = active;
        }
        /** Server profile identity. Cloud UIDs must never be replaced with an email address. */
        public static Account cloud(String uid, String email, String name, int role, boolean active) {
            String normalized = normalize(email);
            if (uid == null || uid.isEmpty() || uid.length() > 128 || uid.contains("/")
                    || name == null || name.trim().isEmpty() || name.trim().length() > 100
                    || normalized.length() > 254 || !normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
                    || role < 0 || role > 3) throw new IllegalArgumentException("Invalid server profile");
            return new Account(uid, normalized, name, role, active);
        }
    }

    public enum Problem { INVALID_INPUT, EMAIL_EXISTS, ROLE_NOT_ALLOWED, STORAGE_FAILURE }
    public static final class AccountException extends Exception {
        public final Problem problem;
        AccountException(Problem problem) { super(problem.name()); this.problem = problem; }
    }

    public AccountStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("senyas_accounts", Context.MODE_PRIVATE);
        if (!CloudRuntime.enabled() && (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            seedDebugAccounts(context);
    }

    public Account register(String name, String email, String password, int role) throws AccountException {
        if (role < 0 || role > 2) throw new AccountException(Problem.ROLE_NOT_ALLOWED);
        String normalized = normalize(email);
        if (name == null || name.trim().isEmpty() || name.trim().length() > 100 || normalized.length() > 254
                || password == null || password.length() < 8
                || !normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new AccountException(Problem.INVALID_INPUT);
        }
        if (CloudRuntime.enabled()) return CloudAccounts.register(name.trim(), normalized, password, role);
        synchronized (LOCK) {
            if (prefs.contains(PREFIX + normalized)) throw new AccountException(Problem.EMAIL_EXISTS);
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            try {
                JSONObject record = new JSONObject().put("email", normalized).put("name", name.trim())
                        .put("role", role).put("active", true).put("iterations", ITERATIONS)
                        .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                        .put("hash", Base64.encodeToString(derive(password, salt, ITERATIONS), Base64.NO_WRAP));
                if (!prefs.edit().putString(PREFIX + normalized, record.toString())
                        .putString("session", normalized).commit()) throw new AccountException(Problem.STORAGE_FAILURE);
                return new Account(record);
            } catch (JSONException | GeneralSecurityException error) {
                throw new AccountException(Problem.STORAGE_FAILURE);
            }
        }
    }

    public Account login(String email, String password) {
        if (CloudRuntime.enabled()) return CloudAccounts.login(normalize(email), password);
        synchronized (LOCK) {
            JSONObject record = record(normalize(email));
            if (record == null || password == null) return null;
            try {
                Account account = new Account(record);
                int iterations = record.getInt("iterations");
                byte[] salt = Base64.decode(record.getString("salt"), Base64.DEFAULT);
                byte[] expected = Base64.decode(record.getString("hash"), Base64.DEFAULT);
                if (!account.active || iterations < 100000 || iterations > 1000000 || salt.length < 16
                        || expected.length != 32 || !MessageDigest.isEqual(expected, derive(password, salt, iterations))) return null;
                return prefs.edit().putString("session", account.id).commit() ? account : null;
            } catch (JSONException | GeneralSecurityException | IllegalArgumentException error) { return null; }
        }
    }

    public Account current() {
        if (CloudRuntime.enabled()) return CloudAccounts.currentCached();
        String id = prefs.getString("session", null);
        Account account = profile(id);
        return account != null && account.active ? account : null;
    }

    public void logout() {
        if (CloudRuntime.enabled()) { CloudAccounts.logout(); return; }
        prefs.edit().remove("session").commit();
    }

    public Account profile(String id) {
        if (CloudRuntime.enabled()) return CloudAccounts.profileCached(id);
        JSONObject record = record(id);
        if (record == null) return null;
        try { return new Account(record); }
        catch (JSONException invalid) { return null; }
    }

    public List<Account> profiles() {
        if (CloudRuntime.enabled()) return CloudAccounts.profilesCached();
        List<Account> result = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) if (key.startsWith(PREFIX)) {
            Account profile = profile(key.substring(PREFIX.length()));
            if (profile != null) result.add(profile);
        }
        return result;
    }

    public boolean setActive(String id, boolean active) {
        if (CloudRuntime.enabled()) return false; // Connected administration requires an acknowledged CloudData write.
        synchronized (LOCK) {
            Account actor = current(), target = profile(id);
            if (actor == null || actor.role != 3 || target == null || target.role == 3) return false;
            JSONObject record = record(target.id);
            try { return prefs.edit().putString(PREFIX + target.id, record.put("active", active).toString()).commit(); }
            catch (JSONException invalid) { return false; }
        }
    }

    private JSONObject record(String id) {
        if (id == null || id.isEmpty()) return null;
        try {
            String json = prefs.getString(PREFIX + normalize(id), null);
            return json == null ? null : new JSONObject(json);
        } catch (JSONException | ClassCastException invalid) { return null; }
    }

    private static String normalize(String email) { return email == null ? "" : email.trim().toLowerCase(Locale.ROOT); }

    private static byte[] derive(String password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    private void seedDebugAccounts(Context context) {
        synchronized (LOCK) {
            // Repair missing test identities after an upgrade; never overwrite existing accounts.
            if (prefs.getBoolean("debug_seeded", false)
                    && prefs.contains(PREFIX + DEMO_STUDENT)
                    && prefs.contains(PREFIX + DEMO_TUTOR)
                    && prefs.contains(PREFIX + DEMO_ADMIN)
                    && prefs.getInt("debug_admin_credentials_version", 0) >= 2) return;
            try (InputStream stream = context.getAssets().open("demo_accounts.json")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                JSONArray records = new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                SharedPreferences.Editor edit = prefs.edit();
                for (int index = 0; index < records.length(); index++) {
                    JSONObject record = records.getJSONObject(index);
                    Account account = new Account(record);
                    if (!prefs.contains(PREFIX + account.id)) edit.putString(PREFIX + account.id, record.toString());
                    else if (DEMO_ADMIN.equals(account.id) && prefs.getInt("debug_admin_credentials_version", 0) < 2) {
                        JSONObject existing = record(account.id);
                        // Rotate only bootstrap admin credentials; preserve profile, role and session.
                        if (existing != null && new Account(existing).role == 3) {
                            existing.put("salt", record.getString("salt")).put("hash", record.getString("hash"))
                                    .put("iterations", record.getInt("iterations"));
                            edit.putString(PREFIX + account.id, existing.toString());
                        }
                    }
                }
                edit.putBoolean("debug_seeded", true).putInt("debug_admin_credentials_version", 2).commit();
            } catch (IOException missingDebugFixture) {
                // Release builds contain no bootstrap credentials and cannot self-register admins.
            } catch (JSONException invalidFixture) {
                throw new IllegalStateException("Invalid debug account fixture", invalidFixture);
            }
        }
    }
}
