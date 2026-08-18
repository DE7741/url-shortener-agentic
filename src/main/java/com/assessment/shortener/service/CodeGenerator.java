package com.assessment.shortener.service;

import java.security.SecureRandom;

/** Generates URL-safe base62 short codes using a secure RNG. */
public class CodeGenerator {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private final SecureRandom random = new SecureRandom();
    private final int length;

    public CodeGenerator(int length) {
        if (length < 4 || length > 32) {
            throw new IllegalArgumentException("code length must be between 4 and 32");
        }
        this.length = length;
    }

    public String next() {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public int getLength() { return length; }

    /** Valid custom codes: 4-32 chars from the base62 alphabet plus '-' and '_'. */
    public static boolean isValidCustomCode(String code) {
        if (code == null || code.length() < 4 || code.length() > 32) return false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }
}
