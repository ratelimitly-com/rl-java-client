package com.ratelimitly;

/**
 * Unsigned limits encoded in a format-version 1 request credential.
 *
 * @param rateBucketsMax maximum number of distinct rate buckets
 * @param latencyServicesMax maximum number of distinct latency trackers
 * @param metricsLabelsMax maximum number of distinct request metrics labels
 * @param latencyBufferSizeMax maximum sample-storage capacity of one latency tracker
 * @param dedupTtlMsMax maximum request deduplication horizon in milliseconds
 * @param rateWindowSizeMsMax maximum resource window duration in milliseconds
 */
public record ApiKeyQuotas(
    long rateBucketsMax,
    long latencyServicesMax,
    long metricsLabelsMax,
    long latencyBufferSizeMax,
    long dedupTtlMsMax,
    long rateWindowSizeMsMax
) {
    /** Ensures every decoded limit fits an unsigned 32-bit integer. */
    public ApiKeyQuotas {
        requireUint32("rateBucketsMax", rateBucketsMax);
        requireUint32("latencyServicesMax", latencyServicesMax);
        requireUint32("metricsLabelsMax", metricsLabelsMax);
        requireUint32("latencyBufferSizeMax", latencyBufferSizeMax);
        requireUint32("dedupTtlMsMax", dedupTtlMsMax);
        requireUint32("rateWindowSizeMsMax", rateWindowSizeMsMax);
    }

    private static void requireUint32(String field, long value) {
        if (value < 0 || value > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException(field + " must fit in an unsigned 32-bit integer");
        }
    }
}
