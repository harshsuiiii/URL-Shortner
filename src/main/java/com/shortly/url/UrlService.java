package com.shortly.url;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.shortly.web.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class UrlService {

    /**
     * A generated code can only collide with a custom alias that happens to look like one
     * (or after the 62^7 space wraps). Each retry burns one sequence value; needing more
     * than a couple means something is systematically wrong, so give up loudly.
     */
    private static final int MAX_GENERATION_ATTEMPTS = 5;

    private final ShortUrlRepository repository;
    private final ShortCodeGenerator generator;
    private final UrlValidator validator;
    private final UrlCache cache;

    public UrlService(ShortUrlRepository repository, ShortCodeGenerator generator,
                      UrlValidator validator, UrlCache cache) {
        this.repository = repository;
        this.generator = generator;
        this.validator = validator;
        this.cache = cache;
    }

    public ShortUrl create(String rawUrl, String customAlias, Duration ttl) {
        String longUrl = validator.validateTarget(rawUrl);
        Instant now = Instant.now();
        Instant expiresAt = ttl == null ? null : now.plus(ttl);

        ShortUrl created = customAlias != null
                ? createWithAlias(customAlias, longUrl, now, expiresAt)
                : createWithGeneratedCode(longUrl, now, expiresAt);

        // Write-through: warms the cache for the imminent first click and overwrites any
        // "!missing" sentinel left by someone probing this code before it existed.
        cache.putUrl(created.code(), created.longUrl(), created.expiresAt());
        return created;
    }

    private ShortUrl createWithAlias(String alias, String longUrl, Instant now, Instant expiresAt) {
        validator.validateAlias(alias);
        long id = repository.nextId();
        if (!repository.insertIfCodeFree(id, alias, longUrl, now, expiresAt)) {
            throw new ApiException(HttpStatus.CONFLICT, "alias '" + alias + "' is already taken");
        }
        return new ShortUrl(id, alias, longUrl, now, expiresAt, 0);
    }

    private ShortUrl createWithGeneratedCode(String longUrl, Instant now, Instant expiresAt) {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            long id = repository.nextId();
            String code = generator.encode(id);
            if (repository.insertIfCodeFree(id, code, longUrl, now, expiresAt)) {
                return new ShortUrl(id, code, longUrl, now, expiresAt, 0);
            }
        }
        throw new IllegalStateException("could not allocate a short code after " + MAX_GENERATION_ATTEMPTS + " attempts");
    }

    /** Hot path. Cache first, Postgres on a miss, then populate the cache (including negatives). */
    public String resolve(String code) {
        Optional<String> cached = cache.get(code);
        if (cached.isPresent()) {
            return switch (cached.get()) {
                case UrlCache.MISSING -> throw notFound(code);
                case UrlCache.GONE -> throw gone(code);
                default -> cached.get();
            };
        }

        Optional<ShortUrl> found = repository.findByCode(code);
        if (found.isEmpty()) {
            cache.putMissing(code);
            throw notFound(code);
        }
        ShortUrl url = found.get();
        if (url.isExpiredAt(Instant.now())) {
            cache.putGone(code);
            throw gone(code);
        }
        cache.putUrl(code, url.longUrl(), url.expiresAt());
        return url.longUrl();
    }

    public ShortUrl get(String code) {
        return repository.findByCode(code).orElseThrow(() -> notFound(code));
    }

    private static ApiException notFound(String code) {
        return new ApiException(HttpStatus.NOT_FOUND, "no short URL with code '" + code + "'");
    }

    private static ApiException gone(String code) {
        return new ApiException(HttpStatus.GONE, "short URL '" + code + "' has expired");
    }
}
