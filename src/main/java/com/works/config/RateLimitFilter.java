package com.works.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RateLimitFilter extends OncePerRequestFilter {

    private static final String PRODUCT_PATH = "/product/";
    private static final String NOTE_PATH = "/note/";
    private static final Duration REFRESH_PERIOD = Duration.ofMinutes(1);

    private record LimiterEntry(RateLimiter limiter, long startNanos) {}

    private final RateLimiterRegistry registry;
    private final ConcurrentMap<String, LimiterEntry> rateLimiters = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimiterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal());

        String bucket;
        int limit;

        if (path.startsWith(PRODUCT_PATH)) {
            bucket = "product";
            limit = 10;
        } else if (path.startsWith(NOTE_PATH)) {
            bucket = "note";
            limit = 7;
        } else if (!authenticated) {
            bucket = "anonymous";
            limit = 13;
        } else {
            filterChain.doFilter(request, response);
            return;
        }

        String identity = authenticated ? authentication.getName() : getClientIp(request);
        String limiterKey = bucket + ":" + identity;

        LimiterEntry entry = rateLimiters.computeIfAbsent(limiterKey, key -> {
            long start = System.nanoTime();
            RateLimiter limiter = registry.rateLimiter(
                    key,
                    RateLimiterConfig.custom()
                            .limitRefreshPeriod(REFRESH_PERIOD)
                            .limitForPeriod(limit)
                            .timeoutDuration(Duration.ZERO)
                            .build()
            );
            return new LimiterEntry(limiter, start);
        });

        if (!entry.limiter().acquirePermission()) {
            long periodNanos = REFRESH_PERIOD.toNanos();
            long elapsedInCycle = (System.nanoTime() - entry.startNanos()) % periodNanos;
            long retryAfterMillis = Duration.ofNanos(periodNanos - elapsedInCycle).toMillis();
            long retryAfterSeconds = Math.max(1, (retryAfterMillis + 999) / 1000);

            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("""
                {
                  "status": 429,
                  "error": "Too Many Requests",
                  "message": "Rate limit exceeded",
                  "retryAfterSeconds": %d,
                  "retryAfterMillis": %d
                }
                """.formatted(retryAfterSeconds, retryAfterMillis));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}