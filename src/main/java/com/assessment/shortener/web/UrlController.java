package com.assessment.shortener.web;

import com.assessment.shortener.domain.ShortUrl;
import com.assessment.shortener.service.AnalyticsService;
import com.assessment.shortener.service.UrlShortenerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/urls")
public class UrlController {

    private final UrlShortenerService service;
    private final AnalyticsService analytics;

    public UrlController(UrlShortenerService service, AnalyticsService analytics) {
        this.service = service;
        this.analytics = analytics;
    }

    /** Body: { "url": "...", "customCode": "optional", "ttlSeconds": optional } */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body,
                                                      UriComponentsBuilder uriBuilder) {
        String url = body.get("url") == null ? null : String.valueOf(body.get("url"));
        String customCode = body.get("customCode") == null ? null : String.valueOf(body.get("customCode"));
        Long ttlSeconds = null;
        Object ttl = body.get("ttlSeconds");
        if (ttl instanceof Number) {
            ttlSeconds = ((Number) ttl).longValue();
        } else if (ttl != null) {
            ttlSeconds = Long.parseLong(String.valueOf(ttl));
        }

        ShortUrl created = service.create(url, customCode, ttlSeconds);
        String shortUrl = uriBuilder.path("/r/{code}").buildAndExpand(created.getCode()).toUriString();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", created.getCode());
        resp.put("shortUrl", shortUrl);
        resp.put("originalUrl", created.getOriginalUrl());
        resp.put("createdAt", created.getCreatedAt());
        resp.put("expiresAt", created.getExpiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping("/{code}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String code) {
        return service.find(code)
                .map(u -> {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("code", u.getCode());
                    resp.put("originalUrl", u.getOriginalUrl());
                    resp.put("createdAt", u.getCreatedAt());
                    resp.put("expiresAt", u.getExpiresAt());
                    return ResponseEntity.ok(resp);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{code}/stats")
    public ResponseEntity<Map<String, Object>> stats(@PathVariable String code) {
        if (service.find(code).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(analytics.statsFor(code));
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        return service.delete(code) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
