package org.dbplatform.common.util;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Identifier generation. {@link #ulid()} produces 26-char Crockford base32 ULIDs (48-bit millisecond
 * timestamp + 80 random bits), monotonic within the same millisecond on one JVM so that events sort by
 * creation time. {@link #uuid()} is a random UUID string.
 */
public final class Ids {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom SEED = new SecureRandom();
    private static final Object LOCK = new Object();
    private static long lastTime = -1;
    private static long lastHi;   // upper 16 random bits
    private static long lastLo;   // lower 64 random bits

    private Ids() {}

    /** New ULID, e.g. {@code 01J9K3Z8T6E4Q0ZQ2M2W8H3M1X}. */
    public static String ulid() {
        long now = System.currentTimeMillis();
        long hi;
        long lo;
        synchronized (LOCK) {
            if (now == lastTime) {
                // increment the 80-bit random part to stay monotonic
                lastLo++;
                if (lastLo == 0) {
                    lastHi = (lastHi + 1) & 0xFFFF;
                }
            } else {
                if (now < lastTime) {
                    now = lastTime; // clock went backwards: keep ordering
                    lastLo++;
                } else {
                    ThreadLocalRandom r = ThreadLocalRandom.current();
                    lastHi = r.nextLong() & 0xFFFF;
                    lastLo = r.nextLong();
                }
                lastTime = now;
            }
            hi = lastHi;
            lo = lastLo;
        }
        return encode(now, hi, lo);
    }

    /** Random UUID string (36 chars). */
    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    /** Short random token (URL-safe base32, {@code length} chars) using a secure random source, e.g. for API keys. */
    public static String token(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be > 0");
        }
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET[SEED.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }

    /** Millisecond timestamp encoded in a ULID produced by {@link #ulid()}. */
    public static long ulidTimestamp(String ulid) {
        if (ulid == null || ulid.length() != 26) {
            throw new IllegalArgumentException("not a ULID: " + ulid);
        }
        long t = 0;
        for (int i = 0; i < 10; i++) {
            t = (t << 5) | decode(ulid.charAt(i));
        }
        return t;
    }

    private static String encode(long time, long hi16, long lo64) {
        char[] c = new char[26];
        // 10 chars of time (48 bits, 50 encoded -> top 2 bits zero)
        for (int i = 9; i >= 0; i--) {
            c[i] = ALPHABET[(int) (time & 0x1F)];
            time >>>= 5;
        }
        // 16 chars of randomness = 80 bits: hi16 (16 bits) + lo64 (64 bits)
        // Split: first 3 chars from hi16 (15 bits) + 1 bit of lo; simpler: build with BigInteger-free arithmetic
        long bits = lo64;
        for (int i = 25; i >= 13; i--) { // 13 chars = 65 bits from lo64 (+1 bit of hi)
            if (i == 13) {
                int v = (int) ((bits & 0xF) | ((hi16 & 1) << 4));
                c[i] = ALPHABET[v];
                hi16 >>>= 1;
            } else {
                c[i] = ALPHABET[(int) (bits & 0x1F)];
                bits >>>= 5;
            }
        }
        for (int i = 12; i >= 10; i--) { // remaining 15 bits of hi16
            c[i] = ALPHABET[(int) (hi16 & 0x1F)];
            hi16 >>>= 5;
        }
        return new String(c);
    }

    private static int decode(char ch) {
        char u = Character.toUpperCase(ch);
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == u) {
                return i;
            }
        }
        // Crockford aliases
        return switch (u) {
            case 'O' -> 0;
            case 'I', 'L' -> 1;
            default -> throw new IllegalArgumentException("invalid ULID char: " + ch);
        };
    }
}
