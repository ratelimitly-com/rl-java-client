package com.ratelimitly;

import java.util.Map;

/** Tagged window permission; unlimited is never a request duration or TTL. */
public record WindowCap(boolean unlimited, long milliseconds) {
    /** Validates exact finite bounds, or a tag with no numeric payload. */
    public WindowCap {
        if (unlimited ? milliseconds != 0 : milliseconds < 1 || milliseconds > 31536000000L)
            throw new IllegalArgumentException("Invalid window cap");
    }
    /** Canonical v2 JSON-compatible representation, without numeric infinity. */
    public Map<String,Object> toJson() {
        return unlimited ? Map.of("kind","unlimited") : Map.of("kind","finite","milliseconds",milliseconds);
    }
}
