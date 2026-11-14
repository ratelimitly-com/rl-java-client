package com.ratelimitly.internal;

/** Shared validation and 10 ms wire-unit conversion for resource rate windows. */
public final class RateWindowUnits {
    public static final long UNIT_MS = 10L;
    public static final long MIN_WINDOW_MS = 10L;
    public static final long MAX_WINDOW_MS = 31_536_000_000L;

    private RateWindowUnits() {
    }

    /** Validates {@code windowSizeMs} (10 ms..365 d, multiple of 10 ms) and returns 10 ms ticks. */
    public static int toWire10ms(long windowSizeMs) {
        if (windowSizeMs < MIN_WINDOW_MS || windowSizeMs > MAX_WINDOW_MS) {
            throw new IllegalArgumentException(
                "windowSizeMs must be in the range " + MIN_WINDOW_MS + ".." + MAX_WINDOW_MS + " ms"
            );
        }
        if (windowSizeMs % UNIT_MS != 0L) {
            throw new IllegalArgumentException("windowSizeMs must be a multiple of " + UNIT_MS + " ms");
        }
        return (int) (windowSizeMs / UNIT_MS);
    }
}
