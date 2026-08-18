package com.assessment.shortener.domain;

import java.time.Instant;

/** One recorded redirect (click) against a short code. */
public class ClickEvent {

    private final String code;
    private final Instant timestamp;
    private final String userAgent;
    private final String referrer;

    public ClickEvent(String code, Instant timestamp, String userAgent, String referrer) {
        this.code = code;
        this.timestamp = timestamp;
        this.userAgent = userAgent;
        this.referrer = referrer;
    }

    public String getCode() { return code; }
    public Instant getTimestamp() { return timestamp; }
    public String getUserAgent() { return userAgent; }
    public String getReferrer() { return referrer; }
}
