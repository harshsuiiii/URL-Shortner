package com.shortly.url;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ShortUrlRepository {

    private final JdbcClient jdbc;

    public ShortUrlRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long nextId() {
        return jdbc.sql("SELECT nextval('short_url_id_seq')").query(Long.class).single();
    }

    /**
     * Inserts unless the code is already taken. The unique constraint decides, atomically,
     * so there is no check-then-insert race between concurrent requests or app instances.
     *
     * @return false if another row already owns {@code code}
     */
    public boolean insertIfCodeFree(long id, String code, String longUrl, Instant createdAt, Instant expiresAt) {
        int rows = jdbc.sql("""
                        INSERT INTO short_urls (id, code, long_url, created_at, expires_at)
                        VALUES (:id, :code, :longUrl, :createdAt, :expiresAt)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .param("id", id)
                .param("code", code)
                .param("longUrl", longUrl)
                .param("createdAt", Timestamp.from(createdAt))
                .param("expiresAt", expiresAt == null ? null : Timestamp.from(expiresAt), Types.TIMESTAMP)
                .update();
        return rows == 1;
    }

    public Optional<ShortUrl> findByCode(String code) {
        return jdbc.sql("""
                        SELECT id, code, long_url, created_at, expires_at, click_count
                        FROM short_urls WHERE code = :code
                        """)
                .param("code", code)
                .query((rs, n) -> {
                    Timestamp expires = rs.getTimestamp("expires_at");
                    return new ShortUrl(
                            rs.getLong("id"),
                            rs.getString("code"),
                            rs.getString("long_url"),
                            rs.getTimestamp("created_at").toInstant(),
                            expires == null ? null : expires.toInstant(),
                            rs.getLong("click_count"));
                })
                .optional();
    }
}
