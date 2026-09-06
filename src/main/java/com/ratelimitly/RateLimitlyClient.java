package com.ratelimitly;

import java.util.concurrent.CompletionStage;

/**
 * Reusable, thread-safe client for resource admission and latency reporting.
 *
 * <p>The two operations are independent. Callers must close the client when its owning
 * application component stops. A failure is neither a grant nor a rejection.</p>
 */
public interface RateLimitlyClient extends AutoCloseable {
    /**
     * Requests one atomic resource-admission decision.
     *
     * @param request resources to consume and latency conditions to evaluate
     * @return a grant or rejection from the response selected by the configured policy
     * @throws RateLimitlyException if a decision cannot be obtained or the request is invalid
     */
    RateLimitDecision checkRateLimit(RateLimitRequest request) throws RateLimitlyException;

    /**
     * Requests one atomic resource-admission decision asynchronously.
     *
     * <p>The stage completes exceptionally with {@link RateLimitlyException} when the equivalent
     * blocking call would throw. Cancellation stops observation; it cannot retract a request that
     * may already have been transmitted.</p>
     *
     * @param request resources to consume and latency conditions to evaluate
     * @return a stage yielding a grant or rejection
     */
    CompletionStage<RateLimitDecision> checkRateLimitAsync(RateLimitRequest request);

    /**
     * Sends latency samples to the currently discovered servers.
     *
     * <p>This operation is independent of resource admission and does not return an admission
     * decision.</p>
     *
     * @param report one or more observed service latencies
     * @throws RateLimitlyException if the report is invalid, discovery fails, or no send succeeds
     */
    void reportLatency(LatencyReport report) throws RateLimitlyException;

    /**
     * Sends latency samples asynchronously.
     *
     * @param report one or more observed service latencies
     * @return a stage that completes after at least one send succeeds, or exceptionally with
     *     {@link RateLimitlyException}
     */
    CompletionStage<Void> reportLatencyAsync(LatencyReport report);

    /**
     * Returns a point-in-time operational snapshot without changing client behavior.
     *
     * @return immutable diagnostics that never contain API-key secret material
     */
    ClientDiagnostics diagnostics();

    /**
     * Releases sockets, router threads, and any client-owned asynchronous executor.
     *
     * <p>This method is idempotent. It never shuts down an executor supplied by the caller.</p>
     */
    @Override
    void close();
}
