package com.assessment.orchestrator.agents;

import com.assessment.shortener.domain.ShortUrl;
import com.assessment.shortener.ratelimit.TokenBucketRateLimiter;
import com.assessment.shortener.service.SafetySettings;
import com.assessment.shortener.service.UrlShortenerService;

import java.util.UUID;

/** Binds the agent ops surface to the live services. */
public class DefaultShortenerOps implements ShortenerOps {

    private final UrlShortenerService service;
    private final TokenBucketRateLimiter rateLimiter;
    private final SafetySettings safetySettings;

    public DefaultShortenerOps(UrlShortenerService service, TokenBucketRateLimiter rateLimiter,
                               SafetySettings safetySettings) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.safetySettings = safetySettings;
    }

    @Override
    public String createUrl(String url, String customCode, Long ttlSeconds) {
        ShortUrl created = service.create(url, customCode, ttlSeconds);
        return created.getCode();
    }

    @Override
    public String resolve(String code) {
        return service.resolveForRedirect(code, "orchestrator-probe", null).getOriginalUrl();
    }

    @Override
    public boolean deleteUrl(String code) {
        return service.delete(code);
    }

    @Override
    public int getRateLimitCapacity() {
        return rateLimiter.getCapacity();
    }

    @Override
    public double getRateLimitRefillPerSecond() {
        return rateLimiter.getRefillPerSecond();
    }

    @Override
    public void setRateLimit(int capacity, double refillPerSecond) {
        rateLimiter.reconfigure(capacity, refillPerSecond);
    }

    @Override
    public boolean isHttpsOnly() {
        return safetySettings.isHttpsOnly();
    }

    @Override
    public void setHttpsOnly(boolean httpsOnly) {
        safetySettings.setHttpsOnly(httpsOnly);
    }

    @Override
    public void blockDomain(String domain) {
        safetySettings.blockDomain(domain);
    }

    @Override
    public int rateLimitBurstAllowance(int n) {
        String probeKey = "probe-" + UUID.randomUUID();
        int allowed = 0;
        for (int i = 0; i < n; i++) {
            if (rateLimiter.tryAcquire(probeKey)) allowed++;
        }
        return allowed;
    }
}
