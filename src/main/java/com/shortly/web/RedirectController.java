package com.shortly.web;

import java.net.URI;

import com.shortly.analytics.ClickTracker;
import com.shortly.ratelimit.RateLimited;
import com.shortly.ratelimit.RateLimited.Policy;
import com.shortly.url.UrlService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedirectController {

    private final UrlService urls;
    private final ClickTracker tracker;

    public RedirectController(UrlService urls, ClickTracker tracker) {
        this.urls = urls;
        this.tracker = tracker;
    }

    /**
     * 302 (not 301) on purpose: browsers cache permanent redirects and would skip us on
     * repeat visits, which silently breaks click analytics and link expiry. The cost is one
     * extra hop to our servers per click — which is exactly the thing we want to count.
     */
    @GetMapping("/{code:" + UrlController.CODE_PATTERN + "}")
    @RateLimited(Policy.REDIRECT)
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        String target = urls.resolve(code);
        tracker.record(code, request.getRemoteAddr(),
                request.getHeader(HttpHeaders.REFERER), request.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
