package com.ratelimitly;

/**
 * Requests a quantity from one content-defined rate bucket.
 *
 * <p>The name, window size, and rate limit together identify the bucket. Changing any of those
 * values addresses a different bucket.</p>
 *
 * @param bucketName application-defined bucket name
 * @param windowSizeMs rate-window duration in milliseconds
 * @param rateLimit token capacity for the window
 * @param tokensRequested positive token quantity to consume
 */
public record ResourceRequest(
    String bucketName,
    long windowSizeMs,
    long rateLimit,
    int tokensRequested
) {
    /** Validates the bucket definition and requested quantity. */
    public ResourceRequest {
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName must not be blank");
        }
        if (windowSizeMs < 1 || windowSizeMs > 31536000000L) throw new IllegalArgumentException("windowSizeMs must be 1..365 days");
        requireUint32("rateLimit", rateLimit);
        if (tokensRequested <= 0 || tokensRequested > 0xFFFF) {
            throw new IllegalArgumentException("tokensRequested must be in the range 1..65535");
        }
    }

    private static void requireUint32(String field, long value) {
        if (value <= 0 || value > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException(field + " must be in the range 1..2^32-1");
        }
    }
}
