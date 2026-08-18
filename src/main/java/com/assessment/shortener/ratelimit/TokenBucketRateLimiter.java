package com.assessment.shortener.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client token bucket rate limiter. Capacity (burst) and refill rate (sustained)
 * can be changed at runtime, which the brownfield scenario relies on, and which makes
 * the change reversible for rollback.
 */
public class TokenBucketRateLimiter {

    private static class Bucket {
        double tokens;
        long lastRefillNanos;
        Bucket(double tokens, long now) { this.tokens = tokens; this.lastRefillNanos = now; }
    }

    private volatile int capacity;           // max burst
    private volatile double refillPerSecond; // sustained rate
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(int capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    /** Returns true if the request is allowed for the given client key. */
    public boolean tryAcquire(String clientKey) {
        long now = System.nanoTime();
        Bucket bucket = buckets.computeIfAbsent(clientKey, k -> new Bucket(capacity, now));
        synchronized (bucket) {
            double elapsedSeconds = (now - bucket.lastRefillNanos) / 1_000_000_000.0;
            bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * refillPerSecond);
            bucket.lastRefillNanos = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    public int getCapacity() { return capacity; }
    public double getRefillPerSecond() { return refillPerSecond; }

    /** Runtime reconfiguration. Buckets reset so the new settings apply immediately. */
    public void reconfigure(int newCapacity, double newRefillPerSecond) {
        if (newCapacity < 1 || newRefillPerSecond <= 0) {
            throw new IllegalArgumentException("invalid rate limiter configuration");
        }
        this.capacity = newCapacity;
        this.refillPerSecond = newRefillPerSecond;
        buckets.clear();
    }
}
