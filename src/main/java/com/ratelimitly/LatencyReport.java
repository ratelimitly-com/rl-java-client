package com.ratelimitly;

import java.util.List;

/**
 * One or more observed service latencies submitted independently of resource admission.
 *
 * @param reports latency samples to submit
 */
public record LatencyReport(List<ServiceLatencyReport> reports) {
    /** Takes an immutable copy and requires at least one report. */
    public LatencyReport {
        reports = reports == null ? List.of() : List.copyOf(reports);
        if (reports.isEmpty()) {
            throw new IllegalArgumentException("reports must not be empty");
        }
        if (reports.size() > 0xFFFF) {
            throw new IllegalArgumentException("report count must fit in a 16-bit counter");
        }
    }
}
