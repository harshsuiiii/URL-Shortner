package com.shortly.web;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import com.shortly.analytics.StatsService;
import com.shortly.analytics.UrlStats;
import com.shortly.config.ShortlyProperties;
import com.shortly.ratelimit.RateLimited;
import com.shortly.ratelimit.RateLimited.Policy;
import com.shortly.url.ShortUrl;
import com.shortly.url.UrlService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/urls")
@Validated
public class UrlController {

    /** Shared with the redirect route so anything creatable is also resolvable. */
    public static final String CODE_PATTERN = "[0-9A-Za-z_-]{4,32}";

    public record CreateRequest(
            @NotBlank @Size(max = 2048) String url,
            @Pattern(regexp = CODE_PATTERN, message = "must be 4-32 characters of letters, digits, '_' or '-'")
            String alias,
            @Positive @Max(315_360_000) Long ttlSeconds) {}

    public record UrlResponse(String code, String shortUrl, String longUrl, Instant createdAt, Instant expiresAt) {}

    private final UrlService urls;
    private final StatsService stats;
    private final String baseUrl;

    public UrlController(UrlService urls, StatsService stats, ShortlyProperties props) {
        this.urls = urls;
        this.stats = stats;
        this.baseUrl = props.baseUrl().replaceAll("/+$", "");
    }

    @PostMapping
    @RateLimited(Policy.CREATE)
    public ResponseEntity<UrlResponse> create(@Valid @RequestBody CreateRequest request) {
        Duration ttl = request.ttlSeconds() == null ? null : Duration.ofSeconds(request.ttlSeconds());
        UrlResponse body = toResponse(urls.create(request.url(), request.alias(), ttl));
        return ResponseEntity.created(URI.create(body.shortUrl())).body(body);
    }

    @GetMapping("/{code:" + CODE_PATTERN + "}")
    public UrlResponse get(@PathVariable String code) {
        return toResponse(urls.get(code));
    }

    @GetMapping("/{code:" + CODE_PATTERN + "}/stats")
    public UrlStats stats(@PathVariable String code,
                          @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        return stats.stats(code, days);
    }

    private UrlResponse toResponse(ShortUrl url) {
        return new UrlResponse(url.code(), baseUrl + "/" + url.code(), url.longUrl(), url.createdAt(), url.expiresAt());
    }
}
