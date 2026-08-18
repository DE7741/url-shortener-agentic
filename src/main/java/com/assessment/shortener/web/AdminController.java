package com.assessment.shortener.web;

import com.assessment.shortener.ratelimit.TokenBucketRateLimiter;
import com.assessment.shortener.service.SafetySettings;
import com.assessment.shortener.service.UrlShortenerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operational settings endpoint. In production this would sit behind authn/authz.
 * It's also the surface the orchestrator's agents act on, with approvals.
 */
@RestController
@RequestMapping("/api/admin/settings")
public class AdminController {

    private final SafetySettings safetySettings;
    private final TokenBucketRateLimiter rateLimiter;
    private final UrlShortenerService service;

    public AdminController(SafetySettings safetySettings, TokenBucketRateLimiter rateLimiter,
                           UrlShortenerService service) {
        this.safetySettings = safetySettings;
        this.rateLimiter = rateLimiter;
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("httpsOnly", safetySettings.isHttpsOnly());
        resp.put("blockedDomains", new ArrayList<>(safetySettings.getBlockedDomains()));
        Map<String, Object> rl = new LinkedHashMap<>();
        rl.put("capacity", rateLimiter.getCapacity());
        rl.put("refillPerSecond", rateLimiter.getRefillPerSecond());
        resp.put("rateLimit", rl);
        resp.put("totalUrls", service.totalUrls());
        return resp;
    }

    /** Body (all optional): { "httpsOnly": bool, "blockedDomains": [..], "rateLimit": {"capacity": n, "refillPerSecond": x} } */
    @PutMapping
    @SuppressWarnings("unchecked")
    public Map<String, Object> update(@RequestBody Map<String, Object> body) {
        if (body.containsKey("httpsOnly")) {
            safetySettings.setHttpsOnly(Boolean.parseBoolean(String.valueOf(body.get("httpsOnly"))));
        }
        if (body.get("blockedDomains") instanceof List) {
            for (Object d : (List<Object>) body.get("blockedDomains")) {
                safetySettings.blockDomain(String.valueOf(d));
            }
        }
        if (body.get("rateLimit") instanceof Map) {
            Map<String, Object> rl = (Map<String, Object>) body.get("rateLimit");
            int capacity = rl.get("capacity") != null
                    ? Integer.parseInt(String.valueOf(rl.get("capacity"))) : rateLimiter.getCapacity();
            double refill = rl.get("refillPerSecond") != null
                    ? Double.parseDouble(String.valueOf(rl.get("refillPerSecond"))) : rateLimiter.getRefillPerSecond();
            rateLimiter.reconfigure(capacity, refill);
        }
        return get();
    }
}
