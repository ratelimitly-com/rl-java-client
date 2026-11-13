package com.ratelimitly;

import com.ratelimitly.internal.RateWindowUnits;

/**
 * Limits encoded in a format-version 2 request credential.
 *
 * @param rateBucketsMax maximum number of distinct rate buckets
 * @param latencyServicesMax maximum number of distinct latency trackers
 * @param metricsLabelsMax maximum number of distinct request metrics labels
 * @param latencyBufferSizeMax maximum sample-storage capacity of one latency tracker
 * @param dedupTtlMsMax maximum request deduplication horizon in milliseconds
 * @param rateWindowSizeMsMax maximum resource window duration in milliseconds, or {@code null}
 *     for unlimited
 */
public record ApiKeyQuotas(
    long rateBucketsMax,
    long latencyServicesMax,
    long metricsLabelsMax,
    long latencyBufferSizeMax,
    long dedupTtlMsMax,
    Long rateWindowSizeMsMax
) {
    /** Validates every decoded quota limit. */
    public ApiKeyQuotas {
        requireUint32("rateBucketsMax", rateBucketsMax);
        requireUint32("latencyServicesMax", latencyServicesMax);
        requireUint32("metricsLabelsMax", metricsLabelsMax);
        requireUint32("latencyBufferSizeMax", latencyBufferSizeMax);
        requireUint32("dedupTtlMsMax", dedupTtlMsMax);
        if (rateWindowSizeMsMax != null) {
            RateWindowUnits.toWire10ms(rateWindowSizeMsMax);
        }
    }

    /**
     * Returns {@code true} when this credential places no finite cap on resource windows.
     *
     * @return {@code true} if {@link #rateWindowSizeMsMax()} is {@code null}
     */
    public boolean isRateWindowUnlimited() {
        return rateWindowSizeMsMax == null;
    }

    private static void requireUint32(String field, long value) {
        if (value < 0 || value > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException(field + " must fit in an unsigned 32-bit integer");
        }
    }
}
