package com.ratelimitly;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable operational snapshot of one client.
 *
 * @param discoveredEndpoints currently discovered endpoint descriptions
 * @param lastDnsRefresh time of the last successful membership refresh, or {@code null} before one
 * @param serverStats per-server observations keyed by unsigned server ID in a Java {@code long}
 * @param steeringFeedbackZeroCount responses requesting a source-port change
 * @param portChanges successful local source-port changes
 */
public record ClientDiagnostics(
    List<String> discoveredEndpoints,
    Instant lastDnsRefresh,
    Map<Long, ServerStats> serverStats,
    long steeringFeedbackZeroCount,
    long portChanges
) {
    /** Takes immutable copies of diagnostic collections. */
    public ClientDiagnostics {
        discoveredEndpoints = discoveredEndpoints == null ? List.of() : List.copyOf(discoveredEndpoints);
        serverStats = serverStats == null ? Map.of() : Map.copyOf(serverStats);
    }

    /**
     * Returns a snapshot for a client with no discovery or traffic history.
     *
     * @return an empty immutable diagnostic snapshot
     */
    public static ClientDiagnostics empty() {
        return new ClientDiagnostics(List.of(), null, Map.of(), 0, 0);
    }
}
