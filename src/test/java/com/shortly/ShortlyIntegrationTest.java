package com.shortly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortly.analytics.ClickTracker;
import com.shortly.url.ShortCodeGenerator;
import com.shortly.url.UrlCache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** End-to-end against real Postgres and Redis: the interesting behaviour lives in SQL and Lua. */
@SpringBootTest
@AutoConfigureMockMvc
class ShortlyIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "2s");
        registry.add("spring.data.redis.connect-timeout", () -> "2s");
        // Tiny create bucket with negligible refill so the 429 test is deterministic.
        registry.add("shortly.rate-limit.create.capacity", () -> "5");
        registry.add("shortly.rate-limit.create.refill-per-second", () -> "0.001");
        // Tests flush explicitly; keep the scheduler out of the way.
        registry.add("shortly.clicks.flush-interval-ms", () -> "3600000");
    }

    /** Each test acts as a distinct client so rate-limit buckets do not leak between tests. */
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);
    private final String clientIp = "10.0.0." + NEXT_CLIENT.getAndIncrement();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ClickTracker clickTracker;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcClient jdbc;
    @Autowired ShortCodeGenerator generator;

    @Test
    void createsShortUrlAndRedirects() throws Exception {
        JsonNode created = create("{\"url\": \"https://example.com/some/long/path?q=1\"}");
        String code = created.get("code").asText();

        assertThat(code).hasSize(7).matches("[0-9A-Za-z]+");
        assertThat(created.get("shortUrl").asText()).isEqualTo("http://localhost:8080/" + code);

        mvc.perform(from(get("/" + code)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/some/long/path?q=1"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void servesRedirectsFromRedisWithoutTouchingTheDatabase() throws Exception {
        String code = create("{\"url\": \"https://example.com/cached\"}").get("code").asText();
        assertThat(redis.opsForValue().get("url:" + code)).isEqualTo("https://example.com/cached");

        // Remove the row behind the cache's back: a hit must still resolve.
        jdbc.sql("DELETE FROM short_urls WHERE code = :code").param("code", code).update();

        mvc.perform(from(get("/" + code))).andExpect(status().isFound());
    }

    @Test
    void repopulatesCacheFromDatabaseOnMiss() throws Exception {
        String code = create("{\"url\": \"https://example.com/miss\"}").get("code").asText();
        redis.delete("url:" + code);

        mvc.perform(from(get("/" + code))).andExpect(status().isFound());

        assertThat(redis.opsForValue().get("url:" + code)).isEqualTo("https://example.com/miss");
        assertThat(redis.getExpire("url:" + code)).isPositive();
    }

    @Test
    void unknownCodeIs404AndNegativelyCached() throws Exception {
        mvc.perform(from(get("/nope1234")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        assertThat(redis.opsForValue().get("url:nope1234")).isEqualTo(UrlCache.MISSING);
    }

    @Test
    void creatingAnAliasOverwritesItsNegativeCacheEntry() throws Exception {
        mvc.perform(from(get("/launch-day"))).andExpect(status().isNotFound());

        create("{\"url\": \"https://example.com/launch\", \"alias\": \"launch-day\"}");

        mvc.perform(from(get("/launch-day")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/launch"));
    }

    @Test
    void duplicateAliasIs409() throws Exception {
        create("{\"url\": \"https://example.com/1\", \"alias\": \"taken-alias\"}");

        mvc.perform(from(post("/api/v1/urls")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"https://example.com/2\", \"alias\": \"taken-alias\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void generatedCodeSkipsOverACollidingCustomAlias() throws Exception {
        // Squat on the exact code an upcoming sequence value would produce.
        create("{\"url\": \"https://example.com/advance-sequence\"}");
        long current = jdbc.sql("SELECT last_value FROM short_url_id_seq").query(Long.class).single();
        // The alias insert itself consumes current+1, so the next generated ID is current+2.
        String squatted = generator.encode(current + 2);
        create("{\"url\": \"https://example.com/squatter\", \"alias\": \"" + squatted + "\"}");

        String code = create("{\"url\": \"https://example.com/victim\"}").get("code").asText();

        assertThat(code).isEqualTo(generator.encode(current + 3));
        mvc.perform(from(get("/" + squatted)))
                .andExpect(header().string("Location", "https://example.com/squatter"));
        mvc.perform(from(get("/" + code)))
                .andExpect(header().string("Location", "https://example.com/victim"));
    }

    @Test
    void expiredLinkIs410() throws Exception {
        String code = create("{\"url\": \"https://example.com/brief\", \"ttlSeconds\": 3600}").get("code").asText();
        mvc.perform(from(get("/" + code))).andExpect(status().isFound());

        jdbc.sql("UPDATE short_urls SET expires_at = now() - interval '1 minute' WHERE code = :code")
                .param("code", code).update();
        redis.delete("url:" + code);

        mvc.perform(from(get("/" + code))).andExpect(status().isGone());
        assertThat(redis.opsForValue().get("url:" + code)).isEqualTo(UrlCache.GONE);
    }

    @Test
    void cacheEntryNeverOutlivesTheLink() throws Exception {
        String code = create("{\"url\": \"https://example.com/short-lived\", \"ttlSeconds\": 30}").get("code").asText();

        assertThat(redis.getExpire("url:" + code)).isBetween(1L, 30L);
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        for (String body : new String[] {
                "{\"url\": \"javascript:alert(1)\"}",
                "{\"url\": \"\"}",
                "{}",
                "{\"url\": \"https://example.com\", \"alias\": \"a b\"}",
                "{\"url\": \"https://example.com\", \"alias\": \"api\"}",
                "{\"url\": \"https://example.com\", \"alias\": \"actuator\"}",
                "{\"url\": \"https://example.com\", \"ttlSeconds\": -5}",
                "{\"url\": \"http://localhost:8080/abc1234\"}",
        }) {
            // Fresh client per request: this test is about validation, not the rate limit.
            mvc.perform(post("/api/v1/urls").with(remoteAddr("10.9.9." + NEXT_CLIENT.getAndIncrement()))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void rateLimitsCreationPerClient() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(from(post("/api/v1/urls")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\": \"https://example.com/rl/" + i + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(4 - i)));
        }

        mvc.perform(from(post("/api/v1/urls")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"https://example.com/rl/over\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429));

        // A different client is unaffected.
        mvc.perform(post("/api/v1/urls").with(remoteAddr("10.8.8.8")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"https://example.com/rl/other\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void tracksClicksAndReportsStats() throws Exception {
        String code = create("{\"url\": \"https://example.com/tracked\"}").get("code").asText();
        String chrome = "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
        String firefox = "Mozilla/5.0 Gecko/20100101 Firefox/131.0";

        mvc.perform(get("/" + code).with(remoteAddr("10.7.0.1"))
                .header("User-Agent", chrome).header("Referer", "https://news.example.org/post/1?utm=x"));
        mvc.perform(get("/" + code).with(remoteAddr("10.7.0.1"))
                .header("User-Agent", chrome).header("Referer", "https://news.example.org/post/2"));
        mvc.perform(get("/" + code).with(remoteAddr("10.7.0.2")).header("User-Agent", firefox));

        // Nothing is written on the request path...
        mvc.perform(get("/api/v1/urls/" + code + "/stats")).andExpect(jsonPath("$.totalClicks").value(0));

        // ...until the background flush runs.
        clickTracker.flush();

        mvc.perform(get("/api/v1/urls/" + code + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(3))
                .andExpect(jsonPath("$.uniqueVisitors").value(2))
                .andExpect(jsonPath("$.windowDays").value(30))
                .andExpect(jsonPath("$.clicksByDay.length()").value(1))
                .andExpect(jsonPath("$.clicksByDay[0].clicks").value(3))
                .andExpect(jsonPath("$.topReferrers[0].name").value("news.example.org"))
                .andExpect(jsonPath("$.topReferrers[0].clicks").value(2))
                .andExpect(jsonPath("$.topReferrers[1].name").value("direct"))
                .andExpect(jsonPath("$.browsers[0].name").value("Chrome"))
                .andExpect(jsonPath("$.browsers[0].clicks").value(2))
                .andExpect(jsonPath("$.browsers[1].name").value("Firefox"));

        // Raw IPs are never persisted.
        assertThat(jdbc.sql("SELECT count(*) FROM click_events WHERE ip_hash LIKE '10.7.%'").query(Long.class).single())
                .isZero();
    }

    @Test
    void failedRedirectsAreNotCountedAsClicks() throws Exception {
        mvc.perform(from(get("/ghost-link"))).andExpect(status().isNotFound());
        clickTracker.flush();

        assertThat(jdbc.sql("SELECT count(*) FROM click_events WHERE code = 'ghost-link'").query(Long.class).single())
                .isZero();
    }

    @Test
    void statsForUnknownCodeIs404AndBadWindowIs400() throws Exception {
        mvc.perform(get("/api/v1/urls/missing99/stats")).andExpect(status().isNotFound());

        String code = create("{\"url\": \"https://example.com/window\"}").get("code").asText();
        mvc.perform(get("/api/v1/urls/" + code + "/stats?days=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/urls/" + code + "/stats?days=9999")).andExpect(status().isBadRequest());
    }

    private JsonNode create(String body) throws Exception {
        String response = mvc.perform(from(post("/api/v1/urls")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request) {
        return request.with(remoteAddr(clientIp));
    }

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
