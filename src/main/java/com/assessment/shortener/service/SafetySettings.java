package com.assessment.shortener.service;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mutable runtime safety configuration for URL creation. The orchestrator's agents
 * can adjust these settings (behind approval gates) in the ambiguous scenario.
 */
public class SafetySettings {

    private final AtomicBoolean httpsOnly = new AtomicBoolean(false);
    private final Set<String> blockedDomains = new CopyOnWriteArraySet<>();

    public boolean isHttpsOnly() { return httpsOnly.get(); }
    public void setHttpsOnly(boolean value) { httpsOnly.set(value); }

    public Set<String> getBlockedDomains() { return blockedDomains; }
    public void blockDomain(String domain) { blockedDomains.add(domain.toLowerCase()); }
    public void unblockDomain(String domain) { blockedDomains.remove(domain.toLowerCase()); }

    public boolean isDomainBlocked(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        for (String blocked : blockedDomains) {
            if (h.equals(blocked) || h.endsWith("." + blocked)) return true;
        }
        return false;
    }
}
