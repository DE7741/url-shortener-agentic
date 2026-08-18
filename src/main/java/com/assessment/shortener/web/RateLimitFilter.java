package com.assessment.shortener.web;

import com.assessment.shortener.ratelimit.TokenBucketRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-client-IP rate limiting on redirect requests (/r/**), since redirects are the
 * hot path. API endpoints are left unthrottled in the prototype.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final TokenBucketRateLimiter limiter;

    public RateLimitFilter(TokenBucketRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/r/")) {
            String clientKey = request.getRemoteAddr();
            if (!limiter.tryAcquire(clientKey)) {
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"rate limit exceeded\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
