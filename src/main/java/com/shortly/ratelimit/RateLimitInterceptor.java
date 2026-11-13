package com.shortly.ratelimit;

import com.shortly.config.ShortlyProperties;
import com.shortly.config.ShortlyProperties.Bucket;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;
    private final ShortlyProperties.RateLimit config;

    public RateLimitInterceptor(RateLimiter limiter, ShortlyProperties props) {
        this.limiter = limiter;
        this.config = props.rateLimit();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!config.enabled() || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        RateLimited limited = method.getMethodAnnotation(RateLimited.class);
        if (limited == null) {
            return true;
        }
        Bucket bucket = switch (limited.value()) {
            case CREATE -> config.create();
            case REDIRECT -> config.redirect();
        };
        // getRemoteAddr() is the proxy-resolved client IP when server.forward-headers-strategy
        // is set; never parse X-Forwarded-For by hand, it is trivially spoofable.
        RateLimiter.Decision decision =
                limiter.tryAcquire(limited.value().name().toLowerCase(), request.getRemoteAddr(), bucket);

        response.setHeader("X-RateLimit-Limit", String.valueOf(bucket.capacity()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }
        return true;
    }
}
