package com.benten.senyas;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Accounts and their saved data on this device (SharedPreferences) until Firebase Auth and
 * Firestore replace them. Keys use the account's email in lowercase: "account.<email>" holds the
 * Account with its password hash, "data.<email>" its AccountData; "market" holds the MarketData
 * that every account on the device shares.
 */
public final class AccountStore {
    public static final int OK = 0, EMAIL_TAKEN = 1, NO_ACCOUNT = 2, WRONG_PASSWORD = 3, SUSPENDED = 4;
    private static final String FILE = "senyas_accounts", ACCOUNT = "account.", DATA = "data.", MARKET = "market";

    private final SharedPreferences prefs;

    public AccountStore(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Creates the account, or EMAIL_TAKEN. Hashes the password, so call it off the main thread. */
    @SuppressLint("ApplySharedPref") // already off the main thread; written before the user moves on
    public synchronized int register(String name, int role, String email, String password) {
        String key = Account.key(email);
        if (prefs.contains(ACCOUNT + key)) return EMAIL_TAKEN;
        Account a = new Account(name.trim(), role, key, Passwords.hash(password));
        prefs.edit().putString(ACCOUNT + key, a.toJson()).commit();
        return OK;
    }

    /** OK, NO_ACCOUNT, WRONG_PASSWORD or SUSPENDED. Hashes the password, so call it off the main thread. */
    public int check(String email, String password) {
        return checkAccount(find(email), password);
    }

    /** The password is checked first, so a wrong guess never learns that an account is suspended. */
    static int checkAccount(@Nullable Account a, String password) {
        if (a == null) return NO_ACCOUNT;
        if (!Passwords.verify(password, a.passwordHash)) return WRONG_PASSWORD;
        return a.active ? OK : SUSPENDED;
    }

    /** Suspends or reactivates a saved account. Does nothing when there is no such account. */
    public synchronized void setActive(String email, boolean active) {
        Account a = find(email);
        if (a != null) prefs.edit().putString(ACCOUNT + a.email, a.withActive(active).toJson()).apply();
    }

    @Nullable
    public Account find(@Nullable String email) {
        return Account.fromJson(prefs.getString(ACCOUNT + Account.key(email), null));
    }

    public List<Account> all() {
        List<Account> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (!e.getKey().startsWith(ACCOUNT) || !(e.getValue() instanceof String)) continue;
            Account a = Account.fromJson((String) e.getValue());
            if (a != null) out.add(a);
        }
        return out;
    }

    AccountData loadData(String key) {
        return AccountData.fromJson(prefs.getString(DATA + key, null));
    }

    void saveData(String key, AccountData d) {
        prefs.edit().putString(DATA + key, d.toJson()).apply();
    }

    MarketData loadMarket() {
        return MarketData.fromJson(prefs.getString(MARKET, null));
    }

    void saveMarket(MarketData m) {
        prefs.edit().putString(MARKET, m.toJson()).apply();
    }

    /** Adds a notification to another account's saved inbox, e.g. a tutor whose listing was booked. */
    void deliver(String key, Repo.Notif n) {
        AccountData d = loadData(key);
        d.notifs.add(0, n);
        saveData(key, d);
    }
}
