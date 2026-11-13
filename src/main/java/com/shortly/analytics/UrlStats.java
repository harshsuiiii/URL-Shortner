package com.shortly.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record UrlStats(
        String code,
        String longUrl,
        Instant createdAt,
        Instant expiresAt,
        long totalClicks,
        int windowDays,
        long uniqueVisitors,
        List<DailyClicks> clicksByDay,
        List<Count> topReferrers,
        List<Count> browsers) {

    public record DailyClicks(LocalDate date, long clicks) {}

    public record Count(String name, long clicks) {}
}
