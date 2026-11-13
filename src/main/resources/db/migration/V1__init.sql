-- IDs come from a sequence so the application can derive the short code *before* inserting.
CREATE SEQUENCE short_url_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE short_urls (
    id          BIGINT       PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL,
    long_url    TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ,
    click_count BIGINT       NOT NULL DEFAULT 0,
    -- The unique index is the final arbiter of collisions (generated vs. custom alias, races).
    CONSTRAINT uq_short_urls_code UNIQUE (code)
);

-- Append-only event log. At real scale this table would be time-partitioned
-- (or live in a columnar store) and rolled up into daily aggregates.
CREATE TABLE click_events (
    id         BIGSERIAL    PRIMARY KEY,
    code       VARCHAR(32)  NOT NULL,
    clicked_at TIMESTAMPTZ  NOT NULL,
    referrer   VARCHAR(255),
    browser    VARCHAR(32)  NOT NULL,
    ip_hash    VARCHAR(32)  NOT NULL
);

CREATE INDEX idx_click_events_code_time ON click_events (code, clicked_at);
