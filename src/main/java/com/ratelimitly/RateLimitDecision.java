package com.ratelimitly;

import java.util.List;

/**
 * Grant or rejection returned for one logical resource request.
 *
 * <p>For a non-empty request, a value exists only after a valid server response. An empty
 * request succeeds locally, with empty decision lists, server ID zero, and no steering feedback.
 * Client, discovery, transport, and protocol failures are represented by {@link RateLimitlyException}, not by
 * {@code success == false}.</p>
 *
 * @param success {@code true} for a grant and {@code false} for a rejection
 * @param guardDecisions returned results for the requested latency guards
 * @param resourceDecisions returned results for the requested resource consumptions
 * @param serverId unsigned ID of the selected server, or zero for a local empty-request result
 * @param steeringFeedback source-port steering indication returned by the selected server
 */
public record RateLimitDecision(
    boolean success,
    List<GuardDecision> guardDecisions,
    List<ResourceDecision> resourceDecisions,
    long serverId,
    boolean steeringFeedback
) {
    /** Takes immutable copies of the decision collections. */
    public RateLimitDecision {
        guardDecisions = guardDecisions == null ? List.of() : List.copyOf(guardDecisions);
        resourceDecisions = resourceDecisions == null ? List.of() : List.copyOf(resourceDecisions);
    }
}
