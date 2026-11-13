package com.shortly.url;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import com.shortly.config.ShortlyProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cache-aside store for code -> long URL lookups (the hot path: reads outnumber writes ~100:1).
 *
 * <ul>
 *   <li><b>Negative caching</b>: unknown/expired codes are cached as sentinels with a short TTL,
 *       so scanning random codes cannot hammer Postgres (cache penetration).</li>
 *   <li><b>TTL jitter</b>: ±10% so keys written together do not expire together (cache avalanche).</li>
 *   <li><b>Link expiry</b>: the Redis TTL never outlives the link's own expires_at.</li>
 *   <li><b>Fail-open</b>: any Redis error is treated as a miss; Postgres stays the source of truth.</li>
 * </ul>
 */
@Component
public class UrlCache {

    /** Sentinels cannot clash with real values: stored URLs always start with "http". */
    public static final String MISSING = "!missing";
    public static final String GONE = "!gone";

    private static final Logger log = LoggerFactory.getLogger(UrlCache.class);
    private static final String PREFIX = "url:";

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Duration negativeTtl;

    public UrlCache(StringRedisTemplate redis, ShortlyProperties props) {
        this.redis = redis;
        this.ttl = props.cache().ttl();
        this.negativeTtl = props.cache().negativeTtl();
    }

    /** @return the cached long URL or a sentinel; empty on a miss or when Redis is unavailable */
    public Optional<String> get(String code) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(PREFIX + code));
        } catch (RuntimeException e) {
            log.warn("Redis read failed, falling back to database: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void putUrl(String code, String longUrl, Instant expiresAt) {
        Duration effective = jitter(ttl);
        if (expiresAt != null) {
            Duration remaining = Duration.between(Instant.now(), expiresAt);
            if (remaining.compareTo(Duration.ofSeconds(1)) < 0) {
                return;
            }
            if (remaining.compareTo(effective) < 0) {
                effective = remaining;
            }
        }
        set(code, longUrl, effective);
    }

    public void putMissing(String code) {
        set(code, MISSING, negativeTtl);
    }

    public void putGone(String code) {
        set(code, GONE, negativeTtl);
    }

    private void set(String code, String value, Duration duration) {
        try {
            redis.opsForValue().set(PREFIX + code, value, duration);
        } catch (RuntimeException e) {
            log.warn("Redis write failed, continuing without cache: {}", e.getMessage());
        }
    }

    private static Duration jitter(Duration base) {
        double factor = 0.9 + ThreadLocalRandom.current().nextDouble() * 0.2;
        return Duration.ofMillis((long) (base.toMillis() * factor));
    }
}
