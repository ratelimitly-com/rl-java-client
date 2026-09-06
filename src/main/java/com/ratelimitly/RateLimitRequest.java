package com.ratelimitly;

import java.util.List;

/**
 * One atomic request containing resource consumptions and optional latency conditions.
 *
 * <p>All requested consumptions and guards are evaluated together. A grant consumes every
 * requested quantity; a rejection consumes none. An empty request is valid and succeeds locally
 * without discovery or network activity.</p>
 *
 * @param resources resource quantities to consume, possibly empty
 * @param guards latency conditions to satisfy, possibly empty
 * @param metricsLabel optional application label for request metrics, or {@code null}
 */
public record RateLimitRequest(
    List<ResourceRequest> resources,
    List<LatencyGuard> guards,
    String metricsLabel
) {
    /** Takes immutable copies and validates the number of request items. */
    public RateLimitRequest {
        resources = resources == null ? List.of() : List.copyOf(resources);
        guards = guards == null ? List.of() : List.copyOf(guards);
        if (resources.size() > 0xFFFF || guards.size() > 0xFFFF) {
            throw new IllegalArgumentException("resource and guard counts must fit in 16-bit counters");
        }
    }
}
