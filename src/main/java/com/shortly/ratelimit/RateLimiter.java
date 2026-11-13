package com.shortly.ratelimit;

import java.util.List;

import com.shortly.config.ShortlyProperties.Bucket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Distributed token-bucket limiter. State lives in Redis so every app instance shares one
 * view of each client; the Lua script makes refill + take a single atomic step.
 *
 * <p>Token bucket (vs. fixed window) allows short bursts while enforcing an average rate,
 * and has no boundary problem where 2x the limit slips through across a window edge.
 */
@Component
public class RateLimiter {

    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {}

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final StringRedisTemplate redis;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> script;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        this.script.setResultType(List.class);
    }

    public Decision tryAcquire(String bucketName, String clientId, Bucket bucket) {
        try {
            List<?> result = redis.execute(script,
                    List.of("rl:" + bucketName + ":" + clientId),
                    String.valueOf(bucket.capacity()),
                    String.valueOf(bucket.refillPerSecond()));
            return new Decision(
                    ((Number) result.get(0)).longValue() == 1,
                    ((Number) result.get(1)).longValue(),
                    ((Number) result.get(2)).longValue());
        } catch (RuntimeException e) {
            // Fail open: a Redis outage should degrade protection, not take the service down.
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
            return new Decision(true, bucket.capacity(), 0);
        }
    }
}
