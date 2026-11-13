package com.shortly.url;

import java.time.Instant;

public record ShortUrl(long id, String code, String longUrl, Instant createdAt, Instant expiresAt, long clickCount) {

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
