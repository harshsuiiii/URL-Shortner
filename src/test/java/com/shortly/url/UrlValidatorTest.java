package com.shortly.url;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shortly.config.ShortlyProperties;
import com.shortly.web.ApiException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

    private final UrlValidator validator =
            new UrlValidator(new ShortlyProperties("https://sho.rt", "salt", null, null, null));

    @Test
    void acceptsAndTrimsHttpUrls() {
        assertThat(validator.validateTarget("  https://example.com/a?b=c#d ")).isEqualTo("https://example.com/a?b=c#d");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "example.com/no-scheme",
            "javascript:alert(1)",
            "ftp://example.com/file",
            "file:///etc/passwd",
            "http:///no-host",
            "https://exa mple.com",
            "https://sho.rt/abc1234",   // our own domain -> redirect loop
    })
    void rejectsUnsafeOrMalformedUrls(String url) {
        assertThatThrownBy(() -> validator.validateTarget(url)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsOverlongUrls() {
        String url = "https://example.com/" + "a".repeat(UrlValidator.MAX_URL_LENGTH);
        assertThatThrownBy(() -> validator.validateTarget(url)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsReservedAliases() {
        assertThatThrownBy(() -> validator.validateAlias("Actuator")).isInstanceOf(ApiException.class);
        validator.validateAlias("my-link");
    }
}
