package com.ratelimitly;

/**
 * Requires the latency tracked for a service to be below a threshold.
 *
 * <p>The name, sample lifetime, maximum sample count, and minimum sample threshold together
 * identify the tracker. They must match the values used by corresponding
 * {@link ServiceLatencyReport} instances.</p>
 *
 * @param latencyTrackerName application-defined tracker name
 * @param thresholdMs maximum acceptable tracked latency in milliseconds
 * @param ttlMs lifetime of one latency sample in milliseconds
 * @param maxSamples maximum samples considered by the tracker
 * @param minSampleThreshold warm-up sample threshold before tracked latency controls admission
 */
public record LatencyGuard(
    String latencyTrackerName,
    long thresholdMs,
    long ttlMs,
    long maxSamples,
    long minSampleThreshold
) {
    /** Validates the tracker definition and guard threshold. */
    public LatencyGuard {
        if (latencyTrackerName == null || latencyTrackerName.isBlank()) {
            throw new IllegalArgumentException("latencyTrackerName must not be blank");
        }
        requireUint32("thresholdMs", thresholdMs);
        requireUint32("ttlMs", ttlMs);
        requireUint32("maxSamples", maxSamples);
        requireUint32("minSampleThreshold", minSampleThreshold);
    }

    private static void requireUint32(String field, long value) {
        if (value <= 0 || value > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException(field + " must be in the range 1..2^32-1");
        }
    }
}
