package com.benten.senyas;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Source;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Server-authoritative identity with a small, private, nonblocking current-profile cache. */
public final class CloudAccounts {
    private static final Object CACHE = new Object(), OPERATIONS = new Object();
    private static final Executor BACKGROUND = Executors.newCachedThreadPool();
    private static final Executor DIRECT = Runnable::run;
    private static final int TIMEOUT_SECONDS = 25;
    private static final AtomicLong identityEpoch = new AtomicLong();
    private static final Map<String, AccountStore.Account> adminProfiles = new LinkedHashMap<>();
    private static SharedPreferences prefs;
    private static AccountStore.Account current;
    private static Task<AuthResult> unfinishedAuth;
    private CloudAccounts() { }

    static void initialize(Context context, boolean emulator) {
        prefs = context.getSharedPreferences(emulator ? "senyas_emulator_identity" : "senyas_cloud_identity", Context.MODE_PRIVATE);
        try { current = decode(new JSONObject(prefs.getString("profile", "{}"))); }
        catch (Exception invalid) { current = null; }
        CloudRuntime.auth().addAuthStateListener(auth -> {
            FirebaseUser user = auth.getCurrentUser();
            synchronized (CACHE) {
                if (current != null && (user == null || !current.id.equals(user.getUid())
                        || !current.email.equals(normalize(user.getEmail())))) {
                    identityEpoch.incrementAndGet();
                    clearCache();
                }
            }
        });
    }

    public static AccountStore.Account register(String name, String email, String password, int role)
            throws AccountStore.AccountException {
        requireWorker();
        if (role < 0 || role > 2) throw new AccountStore.AccountException(AccountStore.Problem.ROLE_NOT_ALLOWED);
        try { AccountStore.Account.cloud("pending", email, name, role, true); }
        catch (RuntimeException invalid) { throw new AccountStore.AccountException(AccountStore.Problem.INVALID_INPUT); }
        if (password == null || password.length() < 8)
            throw new AccountStore.AccountException(AccountStore.Problem.INVALID_INPUT);
        synchronized (OPERATIONS) {
            FirebaseUser created = null;
            try {
                FirebaseAuth auth = beginOperation();
                long epoch = identityEpoch.get();
                AuthResult result = awaitAuth(auth.createUserWithEmailAndPassword(email, password), name, email, role);
                created = result.getUser();
                if (created == null) throw new IllegalStateException("Account creation did not return an identity");
                AccountStore.Account profile = AccountStore.Account.cloud(created.getUid(), created.getEmail(), name, role, true);
                rememberPending(profile);
                Tasks.await(CloudRuntime.db().collection("profiles").document(profile.id).set(fields(profile)),
                        TIMEOUT_SECONDS, TimeUnit.SECONDS);
                AccountStore.Account verified = readServerProfile(created, false);
                acceptOwnProfile(created, verified, epoch);
                clearPending(profile.id);
                return verified;
            } catch (Exception failed) {
                if (created != null) {
                    try {
                        Tasks.await(created.delete(), TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        clearPending(created.getUid());
                    } catch (Exception cannotRollback) {
                        // Retain only the safe signup profile, so the next valid login can finish it.
                    }
                }
                safeSignOut();
                if (causedByCollision(failed)) throw new AccountStore.AccountException(AccountStore.Problem.EMAIL_EXISTS);
                throw new AccountStore.AccountException(AccountStore.Problem.STORAGE_FAILURE);
            }
        }
    }

    public static AccountStore.Account login(String email, String password) {
        requireWorker();
        if (email == null || email.isEmpty() || password == null || password.isEmpty()) return null;
        synchronized (OPERATIONS) {
            try {
                FirebaseAuth auth = beginOperation();
                long epoch = identityEpoch.get();
                FirebaseUser user = awaitAuth(auth.signInWithEmailAndPassword(email, password), null, null, 0).getUser();
                if (user == null) throw new IllegalStateException("Sign in did not return an identity");
                AccountStore.Account profile = readServerProfile(user, true);
                acceptOwnProfile(user, profile, epoch);
                clearPending(profile.id);
                return profile;
            } catch (Exception failed) { safeSignOut(); return null; }
        }
    }

    /** Always uses the server. Callers receive a failed task on outages, missing or suspended profiles. */
    public static Task<AccountStore.Account> refreshProfile() {
        try {
            FirebaseUser user = CloudRuntime.auth().getCurrentUser();
            if (user == null) return Tasks.forException(new IllegalStateException("Sign in is required"));
            long epoch = identityEpoch.get();
            return Tasks.call(BACKGROUND, () -> {
                try {
                    AccountStore.Account profile = readServerProfile(user, false);
                    acceptOwnProfile(user, profile, epoch);
                    return profile;
                } catch (Exception failure) {
                    if (isInvalidProfile(failure) && isIdentity(user, epoch)) logout();
                    throw failure;
                }
            });
        } catch (Exception unavailable) { return Tasks.forException(unavailable); }
    }

    public static AccountStore.Account currentCached() {
        synchronized (CACHE) {
            try {
                FirebaseUser user = CloudRuntime.auth().getCurrentUser();
                if (current != null && current.active && user != null && current.id.equals(user.getUid())
                        && current.email.equals(normalize(user.getEmail()))) return current;
            } catch (RuntimeException unavailable) { }
            clearCache();
            return null;
        }
    }

    public static AccountStore.Account profileCached(String uid) {
        synchronized (CACHE) {
            AccountStore.Account actor = currentCached();
            if (actor == null || uid == null) return null;
            return actor.id.equals(uid) ? actor : actor.role == 3 ? adminProfiles.get(uid) : null;
        }
    }

    public static List<AccountStore.Account> profilesCached() {
        synchronized (CACHE) {
            AccountStore.Account actor = currentCached();
            List<AccountStore.Account> result = new ArrayList<>();
            if (actor == null) return result;
            if (actor.role == 3) result.addAll(adminProfiles.values());
            if (result.stream().noneMatch(account -> account.id.equals(actor.id))) result.add(actor);
            return result;
        }
    }

    /** The caller must discard server callbacks when its authenticated UID changes. */
    public static void cacheProfiles(List<AccountStore.Account> profiles) {
        synchronized (CACHE) {
            AccountStore.Account actor = currentCached();
            adminProfiles.clear();
            if (actor == null || actor.role != 3 || profiles == null) return;
            for (AccountStore.Account profile : profiles) if (profile != null) adminProfiles.put(profile.id, profile);
        }
    }

    public static void cacheAccount(AccountStore.Account profile) {
        if (profile == null) return;
        synchronized (CACHE) {
            AccountStore.Account actor = currentCached();
            if (actor == null) return;
            if (actor.id.equals(profile.id)) {
                if (!profile.active || !actor.email.equals(profile.email)) { logout(); return; }
                storeCurrent(profile);
            } else if (actor.role == 3) adminProfiles.put(profile.id, profile);
        }
    }

    public static void logout() {
        identityEpoch.incrementAndGet();
        safeSignOut();
    }

    private static FirebaseAuth beginOperation() {
        if (unfinishedAuth != null) throw new IllegalStateException("A previous sign in is still finishing");
        identityEpoch.incrementAndGet();
        FirebaseAuth auth = CloudRuntime.auth();
        auth.signOut();
        synchronized (CACHE) { clearCache(); }
        return auth;
    }

    private static AuthResult awaitAuth(Task<AuthResult> task, String signupName, String signupEmail, int signupRole) throws Exception {
        try { return Tasks.await(task, TIMEOUT_SECONDS, TimeUnit.SECONDS); }
        catch (TimeoutException timeout) {
            unfinishedAuth = task;
            // Auth tasks cannot be cancelled. Quarantine a late result and retain a recoverable signup.
            task.addOnCompleteListener(DIRECT, finished -> {
                synchronized (OPERATIONS) {
                    if (signupName != null && finished.isSuccessful() && finished.getResult().getUser() != null) {
                        FirebaseUser user = finished.getResult().getUser();
                        try { rememberPending(AccountStore.Account.cloud(user.getUid(), signupEmail, signupName, signupRole, true)); }
                        catch (Exception ignored) { }
                    }
                    safeSignOut();
                    if (unfinishedAuth == task) unfinishedAuth = null;
                }
            });
            throw timeout;
        }
    }

    private static AccountStore.Account readServerProfile(FirebaseUser user, boolean repairPending) throws Exception {
        DocumentSnapshot document = Tasks.await(CloudRuntime.db().collection("profiles").document(user.getUid()).get(Source.SERVER),
                TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!document.exists() && repairPending) {
            AccountStore.Account pending = pendingFor(user);
            if (pending != null) {
                Tasks.await(document.getReference().set(fields(pending)), TIMEOUT_SECONDS, TimeUnit.SECONDS);
                document = Tasks.await(document.getReference().get(Source.SERVER), TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }
        if (!document.exists()) throw new InvalidProfile();
        Map<String, Object> map = document.getData();
        AccountStore.Account profile;
        try { profile = fromFields(user.getUid(), map); }
        catch (RuntimeException invalid) { throw new InvalidProfile(); }
        if (!profile.active || !profile.email.equals(normalize(user.getEmail()))) throw new InvalidProfile();
        return profile;
    }

    private static AccountStore.Account fromFields(String uid, Map<String, Object> map) {
        if (map == null || !uid.equals(map.get("uid")) || !(map.get("email") instanceof String)
                || !(map.get("name") instanceof String) || !(map.get("role") instanceof Number)
                || !(map.get("active") instanceof Boolean)) throw new IllegalArgumentException("Invalid profile");
        Number role = (Number) map.get("role");
        if (role.longValue() < 0 || role.longValue() > 3 || role.doubleValue() != role.longValue())
            throw new IllegalArgumentException("Invalid role");
        return AccountStore.Account.cloud(uid, (String) map.get("email"), (String) map.get("name"), role.intValue(), (Boolean) map.get("active"));
    }

    private static void acceptOwnProfile(FirebaseUser user, AccountStore.Account profile, long epoch) {
        synchronized (CACHE) {
            if (!isIdentity(user, epoch)) throw new IllegalStateException("Account changed during refresh");
            storeCurrent(profile);
        }
    }
    private static boolean isIdentity(FirebaseUser user, long epoch) {
        FirebaseUser now = CloudRuntime.auth().getCurrentUser();
        return epoch == identityEpoch.get() && now != null && now.getUid().equals(user.getUid());
    }
    private static void storeCurrent(AccountStore.Account profile) {
        current = profile;
        if (profile.role != 3) adminProfiles.clear();
        prefs.edit().putString("profile", new JSONObject(fields(profile)).toString()).apply();
    }
    private static void clearCache() {
        current = null;
        adminProfiles.clear();
        if (prefs != null) prefs.edit().remove("profile").apply();
    }
    private static void safeSignOut() {
        try { CloudRuntime.auth().signOut(); } catch (RuntimeException unavailable) { }
        synchronized (CACHE) { clearCache(); }
    }
    private static Map<String, Object> fields(AccountStore.Account account) {
        Map<String, Object> result = new HashMap<>();
        result.put("uid", account.id); result.put("email", account.email); result.put("name", account.name);
        result.put("role", account.role); result.put("active", account.active);
        return result;
    }
    private static AccountStore.Account decode(JSONObject json) throws Exception {
        return AccountStore.Account.cloud(json.getString("uid"), json.getString("email"), json.getString("name"),
                json.getInt("role"), json.getBoolean("active"));
    }
    private static void rememberPending(AccountStore.Account profile) {
        if (profile.role > 2 || !prefs.edit().putString("pending:" + profile.id, new JSONObject(fields(profile)).toString()).commit())
            throw new IllegalStateException("Signup recovery could not be saved");
    }
    private static AccountStore.Account pendingFor(FirebaseUser user) {
        try {
            AccountStore.Account profile = decode(new JSONObject(prefs.getString("pending:" + user.getUid(), "{}")));
            return profile.role <= 2 && profile.active && profile.id.equals(user.getUid())
                    && profile.email.equals(normalize(user.getEmail())) ? profile : null;
        } catch (Exception absent) { return null; }
    }
    private static void clearPending(String uid) { if (prefs != null) prefs.edit().remove("pending:" + uid).apply(); }
    private static boolean causedByCollision(Throwable error) {
        while (error != null) {
            if (error instanceof FirebaseAuthUserCollisionException) return true;
            error = error.getCause();
        }
        return false;
    }
    private static boolean isInvalidProfile(Throwable error) {
        while (error != null) {
            if (error instanceof InvalidProfile) return true;
            if (error instanceof FirebaseFirestoreException) {
                FirebaseFirestoreException.Code code = ((FirebaseFirestoreException) error).getCode();
                if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED || code == FirebaseFirestoreException.Code.UNAUTHENTICATED) return true;
            }
            error = error.getCause();
        }
        return false;
    }
    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static void requireWorker() {
        if (Looper.getMainLooper() == Looper.myLooper()) throw new IllegalStateException("Shared account operations require a worker thread");
    }
    private static final class InvalidProfile extends Exception { }
}
