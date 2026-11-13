package com.shortly.analytics;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.shortly.analytics.UrlStats.Count;
import com.shortly.analytics.UrlStats.DailyClicks;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ClickRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public ClickRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Events and the denormalised counter move together, so the total never drifts from the log. */
    @Transactional
    public void saveBatch(List<ClickEvent> events, Map<String, Long> countsByCode) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO click_events (code, clicked_at, referrer, browser, ip_hash) VALUES (?, ?, ?, ?, ?)",
                events, events.size(),
                (ps, e) -> {
                    ps.setString(1, e.code());
                    ps.setTimestamp(2, Timestamp.from(e.clickedAt()));
                    ps.setString(3, e.referrer());
                    ps.setString(4, e.browser());
                    ps.setString(5, e.ipHash());
                });
        // One UPDATE per distinct code (not per click) keeps row-lock time on hot links short.
        jdbcTemplate.batchUpdate(
                "UPDATE short_urls SET click_count = click_count + ? WHERE code = ?",
                countsByCode.entrySet(), countsByCode.size(),
                (ps, entry) -> {
                    ps.setLong(1, entry.getValue());
                    ps.setString(2, entry.getKey());
                });
    }

    public long countUniqueVisitors(String code, Instant since) {
        return jdbc.sql("SELECT count(DISTINCT ip_hash) FROM click_events WHERE code = :code AND clicked_at >= :since")
                .param("code", code)
                .param("since", Timestamp.from(since))
                .query(Long.class)
                .single();
    }

    public List<DailyClicks> clicksByDay(String code, Instant since) {
        return jdbc.sql("""
                        SELECT CAST(clicked_at AT TIME ZONE 'UTC' AS date) AS day, count(*) AS clicks
                        FROM click_events
                        WHERE code = :code AND clicked_at >= :since
                        GROUP BY day ORDER BY day
                        """)
                .param("code", code)
                .param("since", Timestamp.from(since))
                .query((rs, n) -> new DailyClicks(rs.getObject("day", LocalDate.class), rs.getLong("clicks")))
                .list();
    }

    public List<Count> topReferrers(String code, Instant since, int limit) {
        return topBy("COALESCE(referrer, 'direct')", code, since, limit);
    }

    public List<Count> topBrowsers(String code, Instant since, int limit) {
        return topBy("browser", code, since, limit);
    }

    /** {@code expression} is a compile-time constant from this class, never user input. */
    private List<Count> topBy(String expression, String code, Instant since, int limit) {
        return jdbc.sql("""
                        SELECT %s AS name, count(*) AS clicks
                        FROM click_events
                        WHERE code = :code AND clicked_at >= :since
                        GROUP BY name ORDER BY clicks DESC, name LIMIT :limit
                        """.formatted(expression))
                .param("code", code)
                .param("since", Timestamp.from(since))
                .param("limit", limit)
                .query((rs, n) -> new Count(rs.getString("name"), rs.getLong("clicks")))
                .list();
    }
}
