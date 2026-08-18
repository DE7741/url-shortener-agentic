package com.assessment.shortener.service;

import com.assessment.shortener.domain.ShortUrl;
import com.assessment.shortener.store.InMemoryUrlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlShortenerServiceTest {

    /** Mutable test clock so expiry tests are deterministic. */
    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private MutableClock clock;
    private SafetySettings settings;
    private UrlShortenerService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        settings = new SafetySettings();
        service = new UrlShortenerService(new InMemoryUrlStore(), new CodeGenerator(7), settings, clock);
    }

    @Test
    void createsAndResolves() {
        ShortUrl url = service.create("https://example.com/page", null, null);
        assertNotNull(url.getCode());
        assertNull(url.getExpiresAt());
        assertEquals("https://example.com/page",
                service.resolveForRedirect(url.getCode(), "ua", null).getOriginalUrl());
    }

    @Test
    void honorsCustomCode() {
        ShortUrl url = service.create("https://example.com", "my-code", null);
        assertEquals("my-code", url.getCode());
        assertThrows(UrlShortenerService.ConflictException.class,
                () -> service.create("https://example.com/other", "my-code", null));
    }

    @Test
    void expiresAfterTtl() {
        ShortUrl url = service.create("https://example.com", null, 60L);
        service.resolveForRedirect(url.getCode(), "ua", null); // fine before expiry
        clock.advance(Duration.ofSeconds(61));
        assertThrows(UrlShortenerService.ExpiredException.class,
                () -> service.resolveForRedirect(url.getCode(), "ua", null));
    }

    @Test
    void rejectsInvalidUrls() {
        assertThrows(UrlShortenerService.ValidationException.class,
                () -> service.create(null, null, null));
        assertThrows(UrlShortenerService.ValidationException.class,
                () -> service.create("ftp://example.com", null, null));
        assertThrows(UrlShortenerService.ValidationException.class,
                () -> service.create("not a url", null, null));
        assertThrows(UrlShortenerService.ValidationException.class,
                () -> service.create("https://example.com", null, 0L));
    }

    @Test
    void enforcesHttpsOnlyWhenEnabled() {
        settings.setHttpsOnly(true);
        assertThrows(UrlShortenerService.ValidationException.class,
                () -> service.create("http://example.com", null, null));
        service.create("https://example.com", null, null); // still fine
    }

    @Test
    void enforcesDomainBlocklist() {
        settings.blockDomain("evil.example");
        assertThrows(UrlShortenerService.ValidationException.class,
                () -> service.create("https://evil.example/x", null, null));
        assertThrows(UrlShortenerService.ValidationException.class,
                () -> service.create("https://sub.evil.example/x", null, null));
    }

    @Test
    void unknownCodeThrowsNotFound() {
        assertThrows(UrlShortenerService.NotFoundException.class,
                () -> service.resolveForRedirect("nope123", "ua", null));
    }
}
