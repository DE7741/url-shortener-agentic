package com.assessment.shortener.domain;

import java.time.Instant;

/** A shortened URL mapping. */
public class ShortUrl {

    private final String code;
    private final String originalUrl;
    private final Instant createdAt;
    private final Instant expiresAt; // null means it never expires

    public ShortUrl(String code, String originalUrl, Instant createdAt, Instant expiresAt) {
        this.code = code;
        this.originalUrl = originalUrl;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getCode() { return code; }
    public String getOriginalUrl() { return originalUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}
