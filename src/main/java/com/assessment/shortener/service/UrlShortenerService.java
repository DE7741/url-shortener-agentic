package com.assessment.shortener.service;

import com.assessment.shortener.domain.ClickEvent;
import com.assessment.shortener.domain.ShortUrl;
import com.assessment.shortener.store.UrlStore;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Core business logic: create, resolve, and delete short URLs.
 * Kept framework-free so it can be unit tested without Spring.
 */
public class UrlShortenerService {

    public static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_COLLISION_RETRIES = 5;

    private final UrlStore store;
    private final CodeGenerator codeGenerator;
    private final SafetySettings safetySettings;
    private final Clock clock;

    public UrlShortenerService(UrlStore store, CodeGenerator codeGenerator,
                               SafetySettings safetySettings, Clock clock) {
        this.store = store;
        this.codeGenerator = codeGenerator;
        this.safetySettings = safetySettings;
        this.clock = clock;
    }

    /**
     * Creates a short URL.
     *
     * @param originalUrl target URL (http/https, validated)
     * @param customCode  optional caller-chosen code
     * @param ttlSeconds  optional time-to-live; null = never expires
     */
    public ShortUrl create(String originalUrl, String customCode, Long ttlSeconds) {
        String validated = validateUrl(originalUrl);
        Instant now = clock.instant();
        Instant expiresAt = null;
        if (ttlSeconds != null) {
            if (ttlSeconds < 1) throw new ValidationException("ttlSeconds must be >= 1");
            expiresAt = now.plus(Duration.ofSeconds(ttlSeconds));
        }

        if (customCode != null && !customCode.isBlank()) {
            if (!CodeGenerator.isValidCustomCode(customCode)) {
                throw new ValidationException("customCode must be 4-32 chars of [A-Za-z0-9_-]");
            }
            ShortUrl url = new ShortUrl(customCode, validated, now, expiresAt);
            if (!store.putIfAbsent(url)) {
                throw new ConflictException("code already in use: " + customCode);
            }
            return url;
        }

        for (int attempt = 0; attempt < MAX_COLLISION_RETRIES; attempt++) {
            ShortUrl url = new ShortUrl(codeGenerator.next(), validated, now, expiresAt);
            if (store.putIfAbsent(url)) {
                return url;
            }
        }
        throw new IllegalStateException("could not allocate a unique code after "
                + MAX_COLLISION_RETRIES + " attempts");
    }

    /** Resolves a code for redirecting and records the click. */
    public ShortUrl resolveForRedirect(String code, String userAgent, String referrer) {
        ShortUrl url = store.find(code).orElseThrow(() -> new NotFoundException("unknown code: " + code));
        if (url.isExpired(clock.instant())) {
            throw new ExpiredException("code expired: " + code);
        }
        store.recordClick(new ClickEvent(code, clock.instant(), userAgent, referrer));
        return url;
    }

    public Optional<ShortUrl> find(String code) {
        return store.find(code);
    }

    public boolean delete(String code) {
        return store.delete(code);
    }

    public long totalUrls() {
        return store.count();
    }

    private String validateUrl(String raw) {
        if (raw == null || raw.isBlank()) throw new ValidationException("url is required");
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new ValidationException("url exceeds max length " + MAX_URL_LENGTH);
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new ValidationException("malformed url");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new ValidationException("only http/https urls are supported");
        }
        if (safetySettings.isHttpsOnly() && !scheme.equalsIgnoreCase("https")) {
            throw new ValidationException("policy: only https urls are accepted");
        }
        if (uri.getHost() == null) {
            throw new ValidationException("url must have a host");
        }
        if (safetySettings.isDomainBlocked(uri.getHost())) {
            throw new ValidationException("policy: domain is blocked");
        }
        return trimmed;
    }

    // Framework-free exceptions, mapped to HTTP statuses in the web layer.

    public static class ValidationException extends RuntimeException {
        public ValidationException(String m) { super(m); }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String m) { super(m); }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String m) { super(m); }
    }

    public static class ExpiredException extends RuntimeException {
        public ExpiredException(String m) { super(m); }
    }
}
