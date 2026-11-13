package com.shortly.url;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

import com.shortly.config.ShortlyProperties;
import com.shortly.web.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class UrlValidator {

    static final int MAX_URL_LENGTH = 2048;

    /** Paths the app itself serves; an alias must never shadow them. */
    private static final Set<String> RESERVED_ALIASES = Set.of("api", "actuator", "error", "health", "admin", "static");

    private final String ownHost;

    public UrlValidator(ShortlyProperties props) {
        this.ownHost = URI.create(props.baseUrl()).getHost();
    }

    /** @return the trimmed URL, or throws 400 */
    public String validateTarget(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty() || url.length() > MAX_URL_LENGTH) {
            throw badRequest("url must be between 1 and " + MAX_URL_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw badRequest("url is not a valid URI");
        }
        String scheme = uri.getScheme();
        // Allow-list schemes: javascript:, data:, file: etc. would turn us into an XSS/phishing relay.
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw badRequest("url must use http or https");
        }
        if (uri.getHost() == null) {
            throw badRequest("url must have a host");
        }
        // Shortening our own links creates redirect chains/loops.
        if (uri.getHost().equalsIgnoreCase(ownHost)) {
            throw badRequest("url must not point at this shortener");
        }
        return url;
    }

    public void validateAlias(String alias) {
        if (RESERVED_ALIASES.contains(alias.toLowerCase())) {
            throw badRequest("alias '" + alias + "' is reserved");
        }
    }

    private static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
