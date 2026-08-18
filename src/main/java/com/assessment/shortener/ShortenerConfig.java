package com.assessment.shortener;

import com.assessment.shortener.ratelimit.TokenBucketRateLimiter;
import com.assessment.shortener.service.AnalyticsService;
import com.assessment.shortener.service.CodeGenerator;
import com.assessment.shortener.service.SafetySettings;
import com.assessment.shortener.service.UrlShortenerService;
import com.assessment.shortener.store.InMemoryUrlStore;
import com.assessment.shortener.store.UrlStore;
import com.assessment.shortener.web.RateLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the framework-free core services into the Spring context. */
@Configuration
public class ShortenerConfig {

    @Bean
    public UrlStore urlStore() {
        return new InMemoryUrlStore();
    }

    @Bean
    public CodeGenerator codeGenerator(@Value("${shortener.code-length:7}") int codeLength) {
        return new CodeGenerator(codeLength);
    }

    @Bean
    public SafetySettings safetySettings() {
        return new SafetySettings();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public UrlShortenerService urlShortenerService(UrlStore store, CodeGenerator generator,
                                                   SafetySettings settings, Clock clock) {
        return new UrlShortenerService(store, generator, settings, clock);
    }

    @Bean
    public AnalyticsService analyticsService(UrlStore store) {
        return new AnalyticsService(store);
    }

    @Bean
    public TokenBucketRateLimiter rateLimiter(
            @Value("${shortener.rate-limit.capacity:10}") int capacity,
            @Value("${shortener.rate-limit.refill-per-second:5}") double refillPerSecond) {
        return new TokenBucketRateLimiter(capacity, refillPerSecond);
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(TokenBucketRateLimiter limiter) {
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(new RateLimitFilter(limiter));
        reg.addUrlPatterns("/r/*");
        reg.setOrder(1);
        return reg;
    }
}
