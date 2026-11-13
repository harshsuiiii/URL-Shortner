package com.shortly.analytics;

import java.time.Duration;
import java.time.Instant;

import com.shortly.url.ShortUrl;
import com.shortly.url.UrlService;

import org.springframework.stereotype.Service;

@Service
public class StatsService {

    private static final int TOP_N = 10;

    private final UrlService urls;
    private final ClickRepository clicks;

    public StatsService(UrlService urls, ClickRepository clicks) {
        this.urls = urls;
        this.clicks = clicks;
    }

    /**
     * {@code totalClicks} is all-time (denormalised counter); everything else covers the last
     * {@code days} days. Numbers lag real time by up to one flush interval — eventual consistency
     * is the price of keeping the redirect path write-free.
     */
    public UrlStats stats(String code, int days) {
        ShortUrl url = urls.get(code);
        Instant since = Instant.now().minus(Duration.ofDays(days));
        return new UrlStats(
                url.code(),
                url.longUrl(),
                url.createdAt(),
                url.expiresAt(),
                url.clickCount(),
                days,
                clicks.countUniqueVisitors(code, since),
                clicks.clicksByDay(code, since),
                clicks.topReferrers(code, since, TOP_N),
                clicks.topBrowsers(code, since, TOP_N));
    }
}
