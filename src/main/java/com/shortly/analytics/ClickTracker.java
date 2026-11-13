package com.shortly.analytics;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

import com.shortly.config.ShortlyProperties;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Decouples click recording from the redirect. The redirect only does a non-blocking
 * {@code offer} into a bounded in-memory buffer; a background task drains it and writes
 * to Postgres in batches (one multi-row insert instead of one transaction per click).
 *
 * <p>Trade-offs, deliberately chosen for a single-service deployment:
 * <ul>
 *   <li>Bounded buffer => under overload we <em>drop analytics</em> (counted in the
 *       {@code shortly.clicks.dropped} metric) rather than slow down or fail redirects.</li>
 *   <li>Buffered events are lost if the process is killed hard (graceful shutdown flushes).
 *       If clicks were billable, this buffer would be replaced by a durable log
 *       (Kafka / Redis Streams) behind the same {@link #record} call.</li>
 * </ul>
 */
@Component
public class ClickTracker {

    private static final Logger log = LoggerFactory.getLogger(ClickTracker.class);

    private final ClickRepository repository;
    private final BlockingQueue<ClickEvent> buffer;
    private final int batchSize;
    private final String salt;
    private final Counter dropped;

    public ClickTracker(ClickRepository repository, ShortlyProperties props, MeterRegistry metrics) {
        this.repository = repository;
        this.buffer = new ArrayBlockingQueue<>(props.clicks().bufferSize());
        this.batchSize = props.clicks().batchSize();
        this.salt = props.ipHashSalt();
        this.dropped = metrics.counter("shortly.clicks.dropped");
    }

    /** Never blocks and never throws: analytics must not be able to break a redirect. */
    public void record(String code, String clientIp, String refererHeader, String userAgent) {
        ClickEvent event = new ClickEvent(
                code, Instant.now(), referrerHost(refererHeader), classifyBrowser(userAgent), hashIp(clientIp));
        if (!buffer.offer(event)) {
            dropped.increment();
        }
    }

    @Scheduled(fixedDelayString = "${shortly.clicks.flush-interval-ms:2000}")
    public void flush() {
        List<ClickEvent> batch = new ArrayList<>(batchSize);
        while (buffer.drainTo(batch, batchSize) > 0) {
            try {
                Map<String, Long> countsByCode =
                        batch.stream().collect(Collectors.groupingBy(ClickEvent::code, Collectors.counting()));
                repository.saveBatch(batch, countsByCode);
            } catch (RuntimeException e) {
                dropped.increment(batch.size());
                log.error("Dropping {} click events after failed flush", batch.size(), e);
            }
            batch.clear();
        }
    }

    @PreDestroy
    void flushOnShutdown() {
        flush();
    }

    /** Keep only the referring host: full referrer URLs can carry tokens and personal data. */
    static String referrerHost(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(header.trim()).getHost();
            return host == null ? null : truncate(host.toLowerCase(), 255);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Coarse on purpose; order matters because Edge and Chrome both advertise "Safari". */
    static String classifyBrowser(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown";
        }
        String ua = userAgent.toLowerCase();
        if (ua.contains("bot") || ua.contains("crawler") || ua.contains("spider") || ua.contains("curl")) {
            return "Bot";
        }
        if (ua.contains("edg/")) {
            return "Edge";
        }
        if (ua.contains("firefox/")) {
            return "Firefox";
        }
        if (ua.contains("chrome/") || ua.contains("crios/")) {
            return "Chrome";
        }
        if (ua.contains("safari/")) {
            return "Safari";
        }
        return "Other";
    }

    /** Salted hash lets us count unique visitors without storing raw IP addresses. */
    private String hashIp(String ip) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((salt + "|" + ip).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

}
