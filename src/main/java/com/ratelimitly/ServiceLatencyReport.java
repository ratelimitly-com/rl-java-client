package com.ratelimitly;

/**
 * One observed latency sample for an application-defined service tracker.
 *
 * <p>The name, sample lifetime, maximum sample count, and minimum sample threshold together
 * identify the tracker. They must match corresponding {@link LatencyGuard} values.</p>
 *
 * @param latencyTrackerName application-defined tracker name
 * @param observedLatencyMs observed service latency in milliseconds
 * @param ttlMs lifetime of this sample in milliseconds
 * @param maxSamples maximum samples considered by the tracker
 * @param minSampleThreshold warm-up sample threshold before tracked latency controls admission
 */
public record ServiceLatencyReport(
    String latencyTrackerName,
    long observedLatencyMs,
    long ttlMs,
    long maxSamples,
    long minSampleThreshold
) {
    /** Validates the tracker definition and observed latency. */
    public ServiceLatencyReport {
        if (latencyTrackerName == null || latencyTrackerName.isBlank()) {
            throw new IllegalArgumentException("latencyTrackerName must not be blank");
        }
        requireUint32("observedLatencyMs", observedLatencyMs);
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
