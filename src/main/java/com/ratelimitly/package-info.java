/**
 * Public API for creating RateLimitly clients, requesting atomic resource admission decisions,
 * and reporting observed service latencies.
 *
 * <p>Create a reusable client with {@link com.ratelimitly.RateLimitlyClients}, close it when its
 * owning application component stops, and treat {@link com.ratelimitly.RateLimitlyException} as a
 * failure distinct from both a grant and a rejection.</p>
 */
package com.ratelimitly;
