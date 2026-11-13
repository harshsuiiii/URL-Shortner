package com.shortly.analytics;

import java.time.Instant;

public record ClickEvent(String code, Instant clickedAt, String referrer, String browser, String ipHash) {}
