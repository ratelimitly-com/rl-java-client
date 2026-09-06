package com.ratelimitly;

/**
 * Result returned for one latency guard in a resource request.
 *
 * @param latencyTrackerName application-defined tracker name
 * @param thresholdMs maximum latency allowed by the guard, in milliseconds
 * @param currentLatencyMs latency observed by the selected server, in milliseconds
 * @param passed whether the latency condition passed
 */
public record GuardDecision(
    String latencyTrackerName,
    long thresholdMs,
    long currentLatencyMs,
    boolean passed
) {
}
