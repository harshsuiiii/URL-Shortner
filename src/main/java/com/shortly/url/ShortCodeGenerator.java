package com.shortly.url;

import org.springframework.stereotype.Component;

/**
 * Turns a unique numeric ID into a fixed-length Base62 short code.
 *
 * <p>Why counter + Base62 rather than hashing the URL: a database sequence is already
 * unique, so the mapping ID -> code is collision-free by construction. Truncated hashes
 * (MD5/SHA-256 cut to 7 chars) collide per the birthday bound (~50% after ~2.2M URLs in a
 * 62^7 space) and force a read-before-write or retry loop on every insert.
 *
 * <p>Plain Base62 of a counter leaks volume and makes codes enumerable (…a, …b, …c), so the
 * ID is first scrambled with a multiplicative permutation: {@code (id * M) mod 62^7}. Because
 * M is coprime to 62^7 the mapping is a bijection — still zero collisions — but neighbouring
 * IDs land far apart. This is obfuscation, not cryptography; a keyed Feistel network / FPE
 * would be the next step if unguessability were a hard requirement.
 */
@Component
public class ShortCodeGenerator {

    public static final int LENGTH = 7;

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = ALPHABET.length();

    /** 62^7 ≈ 3.5 trillion codes: ~110 years at 1,000 new URLs per second. */
    static final long SPACE = pow(BASE, LENGTH);

    /** Prime, coprime to 62 = 2 * 31, and small enough that (SPACE - 1) * M fits in a long. */
    private static final long MULTIPLIER = 1_000_003L;

    public String encode(long id) {
        if (id < 0) {
            throw new IllegalArgumentException("id must be non-negative");
        }
        long n = (id % SPACE) * MULTIPLIER % SPACE;
        char[] out = new char[LENGTH];
        for (int i = LENGTH - 1; i >= 0; i--) {
            out[i] = ALPHABET.charAt((int) (n % BASE));
            n /= BASE;
        }
        return new String(out);
    }

    private static long pow(long base, int exp) {
        long result = 1;
        for (int i = 0; i < exp; i++) {
            result = Math.multiplyExact(result, base);
        }
        return result;
    }
}
