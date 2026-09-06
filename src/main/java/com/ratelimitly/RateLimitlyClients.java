package com.ratelimitly;

/** Factory methods for the supported RateLimitly client implementation. */
public final class RateLimitlyClients {
    private RateLimitlyClients() {
    }

    /**
     * Creates a reusable client and validates its API key and request policy.
     *
     * @param config immutable client configuration
     * @return a new open client owned by the caller
     * @throws RateLimitlyException if the API key or policy is invalid, or transport setup fails
     */
    public static RateLimitlyClient create(RateLimitlyClientConfig config) throws RateLimitlyException {
        return new DefaultRateLimitlyClient(config);
    }
}
