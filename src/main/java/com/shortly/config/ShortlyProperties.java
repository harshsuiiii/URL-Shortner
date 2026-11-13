package com.shortly.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("shortly")
public record ShortlyProperties(
        String baseUrl,
        String ipHashSalt,
        Cache cache,
        RateLimit rateLimit,
        Clicks clicks) {

    public record Cache(Duration ttl, Duration negativeTtl) {}

    public record RateLimit(boolean enabled, Bucket create, Bucket redirect) {}

    public record Bucket(int capacity, double refillPerSecond) {}

    public record Clicks(int bufferSize, int batchSize) {}
}
