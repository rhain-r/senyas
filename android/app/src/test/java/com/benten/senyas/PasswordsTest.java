package com.benten.senyas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class PasswordsTest {

    @Test
    public void rightPasswordVerifiesAndWrongOneDoesNot() {
        String stored = Passwords.hash("sari-sari 2026");
        assertTrue(Passwords.verify("sari-sari 2026", stored));
        assertFalse(Passwords.verify("sari-sari 2027", stored));
        assertFalse(Passwords.verify("Sari-sari 2026", stored));
        assertFalse(Passwords.verify("", stored));
        assertFalse(Passwords.verify(null, stored));
    }

    @Test
    public void storedHashNamesItsWorkFactorAndNeverContainsThePassword() {
        String stored = Passwords.hash("kumusta123");
        assertTrue(stored.startsWith("pbkdf2-sha256$" + Passwords.ITERATIONS + "$"));
        assertEquals(4, stored.split("\\$").length);
        assertFalse(stored.contains("kumusta123"));
    }

    @Test
    public void sameSaltAndPasswordGiveTheSameHashWhileFreshSaltsDiffer() {
        byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        assertEquals(Passwords.hash("salamat", salt, 1000), Passwords.hash("salamat", salt, 1000));
        assertNotEquals(Passwords.hash("salamat"), Passwords.hash("salamat"));
    }

    @Test
    public void olderWorkFactorsStillVerify() {
        byte[] salt = "fedcba9876543210".getBytes(StandardCharsets.US_ASCII);
        String stored = Passwords.hash("magandang umaga", salt, 1000);
        assertTrue(Passwords.verify("magandang umaga", stored));
        assertFalse(Passwords.verify("magandang gabi", stored));
    }

    @Test
    public void malformedHashesNeverMatch() {
        assertFalse(Passwords.verify("secret1", null));
        assertFalse(Passwords.verify("secret1", ""));
        assertFalse(Passwords.verify("secret1", "secret1"));
        assertFalse(Passwords.verify("secret1", "md5$1000$abc$def"));
        assertFalse(Passwords.verify("secret1", "pbkdf2-sha256$many$AAAA$AAAA"));
        assertFalse(Passwords.verify("secret1", "pbkdf2-sha256$1000$not base64!$AAAA"));
        assertFalse(Passwords.verify("secret1", "pbkdf2-sha256$0$AAAA$AAAA"));
    }
}
