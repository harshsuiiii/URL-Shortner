package com.shortly.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClickTrackerParsingTest {

    @Test
    void keepsOnlyTheReferrerHost() {
        assertThat(ClickTracker.referrerHost("https://News.Example.com/story?token=secret")).isEqualTo("news.example.com");
        assertThat(ClickTracker.referrerHost(null)).isNull();
        assertThat(ClickTracker.referrerHost("  ")).isNull();
        assertThat(ClickTracker.referrerHost("not a url")).isNull();
    }

    @Test
    void classifiesCommonBrowsers() {
        assertThat(ClickTracker.classifyBrowser(
                "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36 Edg/130.0"))
                .isEqualTo("Edge");
        assertThat(ClickTracker.classifyBrowser(
                "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"))
                .isEqualTo("Chrome");
        assertThat(ClickTracker.classifyBrowser(
                "Mozilla/5.0 (Macintosh) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"))
                .isEqualTo("Safari");
        assertThat(ClickTracker.classifyBrowser("Mozilla/5.0 (X11; Linux) Gecko/20100101 Firefox/131.0")).isEqualTo("Firefox");
        assertThat(ClickTracker.classifyBrowser("Googlebot/2.1 (+http://www.google.com/bot.html)")).isEqualTo("Bot");
        assertThat(ClickTracker.classifyBrowser(null)).isEqualTo("Unknown");
    }
}
