package com.assessment.shortener.service;

import com.assessment.shortener.domain.ClickEvent;
import com.assessment.shortener.store.UrlStore;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Click analytics computed over stored click events. */
public class AnalyticsService {

    private final UrlStore store;

    public AnalyticsService(UrlStore store) {
        this.store = store;
    }

    public Map<String, Object> statsFor(String code) {
        List<ClickEvent> events = store.clicks(code);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("code", code);
        stats.put("totalClicks", events.size());
        stats.put("lastClickAt", events.isEmpty() ? null : events.get(events.size() - 1).getTimestamp());

        // clicks per hour bucket over the last 24h
        Map<String, Integer> perHour = new LinkedHashMap<>();
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        for (ClickEvent e : events) {
            if (e.getTimestamp().isBefore(cutoff)) continue;
            String bucket = e.getTimestamp().truncatedTo(ChronoUnit.HOURS).toString();
            perHour.merge(bucket, 1, Integer::sum);
        }
        stats.put("clicksLast24hByHour", perHour);
        return stats;
    }
}
