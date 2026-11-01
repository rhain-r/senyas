package com.benten.senyas;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Salted PBKDF2 password hashes, stored as "pbkdf2-sha256$iterations$salt$hash" (Base64), so the
 * work factor can be raised later without breaking saved accounts. Passwords themselves are never kept.
 * Hashing takes a noticeable moment on a phone, so call it off the main thread.
 */
public final class Passwords {
    static final int ITERATIONS = 100_000;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256", PREFIX = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16, KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {}

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return hash(password, salt, ITERATIONS);
    }

    static String hash(String password, byte[] salt, int iterations) {
        Base64.Encoder b64 = Base64.getEncoder();
        return PREFIX + "$" + iterations + "$" + b64.encodeToString(salt) + "$"
                + b64.encodeToString(derive(password, salt, iterations, KEY_BITS));
    }

    /** True when the password matches the stored hash. A malformed hash never matches. */
    public static boolean verify(String password, String stored) {
        if (password == null || password.isEmpty() || stored == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !PREFIX.equals(p[0])) return false;
        try {
            int iterations = Integer.parseInt(p[1]);
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] expected = Base64.getDecoder().decode(p[3]);
            if (iterations < 1 || salt.length == 0 || expected.length == 0) return false;
            return MessageDigest.isEqual(expected, derive(password, salt, iterations, expected.length * 8));
        } catch (IllegalArgumentException e) { // bad number or bad Base64
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations, int bits) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, bits);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }
}
