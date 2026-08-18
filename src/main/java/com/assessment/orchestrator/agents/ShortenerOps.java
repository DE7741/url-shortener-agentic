package com.assessment.orchestrator.agents;

/**
 * The operational surface agents may act on. Kept narrow on purpose: this interface
 * is the autonomy boundary, so agents can't touch anything not exposed here.
 */
public interface ShortenerOps {

    String createUrl(String url, String customCode, Long ttlSeconds);

    /** Returns the original URL; throws if unknown or expired. */
    String resolve(String code);

    boolean deleteUrl(String code);

    int getRateLimitCapacity();

    double getRateLimitRefillPerSecond();

    void setRateLimit(int capacity, double refillPerSecond);

    boolean isHttpsOnly();

    void setHttpsOnly(boolean httpsOnly);

    void blockDomain(String domain);

    /** Counts how many of n immediate probe requests the rate limiter admits (test key). */
    int rateLimitBurstAllowance(int n);
}
