package com.assessment.shortener.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketRateLimiterTest {

    @Test
    void admitsUpToCapacityThenRejects() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 0.0001);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("client-a"), "request " + i + " should be admitted");
        }
        assertFalse(limiter.tryAcquire("client-a"));
    }

    @Test
    void clientsAreIsolated() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 0.0001);
        assertTrue(limiter.tryAcquire("client-a"));
        assertTrue(limiter.tryAcquire("client-b"));
        assertFalse(limiter.tryAcquire("client-a"));
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 50); // 50 tokens/sec
        assertTrue(limiter.tryAcquire("client-a"));
        assertFalse(limiter.tryAcquire("client-a"));
        Thread.sleep(100); // ~5 tokens refilled, capped at capacity 1
        assertTrue(limiter.tryAcquire("client-a"));
    }

    @Test
    void reconfigureAppliesImmediately() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 1);
        limiter.reconfigure(10, 1);
        assertEquals(10, limiter.getCapacity());
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire("client-a")) allowed++;
        }
        assertEquals(10, allowed);
    }

    @Test
    void rejectsInvalidConfiguration() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 1);
        assertThrows(IllegalArgumentException.class, () -> limiter.reconfigure(0, 1));
        assertThrows(IllegalArgumentException.class, () -> limiter.reconfigure(5, 0));
    }
}
