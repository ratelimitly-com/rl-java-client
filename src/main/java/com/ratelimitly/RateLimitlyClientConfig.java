package com.ratelimitly;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Immutable client configuration built around one RateLimitly API key.
 *
 * <p>Use {@link #builder(String)} for normal construction. The encoded key is deliberately
 * redacted from this object's string representation.</p>
 */
public final class RateLimitlyClientConfig {
    private final String apiKey;
    private final String dnsNameOverride;
    private final long dnsTimeoutMs;
    private final long dnsRefreshIntervalSeconds;
    private final RequestPolicy requestPolicy;
    private final DnsResolver dnsResolver;
    private final Executor asyncExecutor;

    private RateLimitlyClientConfig(Builder builder) {
        apiKey = requireNonBlank("apiKey", builder.apiKey);
        dnsNameOverride = builder.dnsNameOverride == null
            ? null
            : requireNonBlank("dnsName", builder.dnsNameOverride);
        if (builder.dnsTimeoutMs <= 0) {
            throw new IllegalArgumentException("dnsTimeoutMs must be > 0");
        }
        if (builder.dnsRefreshIntervalSeconds <= 0) {
            throw new IllegalArgumentException("dnsRefreshIntervalSeconds must be > 0");
        }
        dnsTimeoutMs = builder.dnsTimeoutMs;
        dnsRefreshIntervalSeconds = builder.dnsRefreshIntervalSeconds;
        requestPolicy = Objects.requireNonNull(builder.requestPolicy, "requestPolicy");
        dnsResolver = builder.dnsResolver;
        asyncExecutor = builder.asyncExecutor;
    }

    /**
     * Starts a configuration using the production discovery name derived from the API-key ID.
     *
     * @param apiKey encoded RateLimitly request credential
     * @return a new configuration builder
     * @throws IllegalArgumentException if the key is null or blank
     */
    public static Builder builder(String apiKey) {
        return new Builder(apiKey);
    }

    /**
     * Returns the explicit discovery override.
     *
     * @return the configured override, or empty for normal API-key-derived discovery
     */
    public Optional<String> dnsNameOverride() {
        return Optional.ofNullable(dnsNameOverride);
    }

    /**
     * Returns the maximum duration of one DNS lookup.
     *
     * @return timeout in milliseconds
     */
    public long dnsTimeoutMs() {
        return dnsTimeoutMs;
    }

    /**
     * Returns the maximum interval before activity refreshes cached membership.
     *
     * @return refresh interval in seconds
     */
    public long dnsRefreshIntervalSeconds() {
        return dnsRefreshIntervalSeconds;
    }

    /**
     * Returns the resource-request delivery and response-selection policy.
     *
     * @return the configured immutable policy
     */
    public RequestPolicy requestPolicy() {
        return requestPolicy;
    }

    String apiKey() {
        return apiKey;
    }

    String dnsNameOverrideValue() {
        return dnsNameOverride;
    }

    DnsResolver dnsResolver() {
        return dnsResolver;
    }

    Executor asyncExecutor() {
        return asyncExecutor;
    }

    @Override
    public String toString() {
        return "RateLimitlyClientConfig["
            + "apiKey=<redacted>"
            + ", dnsNameOverride=" + dnsNameOverride
            + ", dnsTimeoutMs=" + dnsTimeoutMs
            + ", dnsRefreshIntervalSeconds=" + dnsRefreshIntervalSeconds
            + ", requestPolicy=" + requestPolicy
            + ", dnsResolver=" + (dnsResolver == null ? "<default>" : "<custom>")
            + ", asyncExecutor=" + (asyncExecutor == null ? "<client-owned>" : "<caller-owned>")
            + ']';
    }

    private static String requireNonBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /** Mutable builder for one immutable {@link RateLimitlyClientConfig}. */
    public static final class Builder {
        private final String apiKey;
        private String dnsNameOverride;
        private long dnsTimeoutMs = 1_000;
        private long dnsRefreshIntervalSeconds = 300;
        private RequestPolicy requestPolicy = RequestPolicy.defaultPolicy();
        private DnsResolver dnsResolver;
        private Executor asyncExecutor;

        private Builder(String apiKey) {
            this.apiKey = requireNonBlank("apiKey", apiKey);
        }

        /**
         * Overrides API-key-derived production discovery.
         *
         * <p>This is primarily useful for tests, private environments, and advanced host
         * integration.</p>
         *
         * @param dnsName discovery domain without the {@code _ratelimitly._udp} prefix
         * @return this builder
         * @throws IllegalArgumentException if the name is null or blank
         */
        public Builder dnsName(String dnsName) {
            dnsNameOverride = requireNonBlank("dnsName", dnsName);
            return this;
        }

        /**
         * Sets the maximum duration of one DNS lookup.
         *
         * @param dnsTimeoutMs positive timeout in milliseconds
         * @return this builder
         */
        public Builder dnsTimeoutMs(long dnsTimeoutMs) {
            this.dnsTimeoutMs = dnsTimeoutMs;
            return this;
        }

        /**
         * Sets the maximum interval before activity refreshes membership.
         *
         * @param dnsRefreshIntervalSeconds positive interval in seconds
         * @return this builder
         */
        public Builder dnsRefreshIntervalSeconds(long dnsRefreshIntervalSeconds) {
            this.dnsRefreshIntervalSeconds = dnsRefreshIntervalSeconds;
            return this;
        }

        /**
         * Sets the resource-request delivery and response-selection policy.
         *
         * @param requestPolicy immutable request policy
         * @return this builder
         * @throws NullPointerException if {@code requestPolicy} is null
         */
        public Builder requestPolicy(RequestPolicy requestPolicy) {
            this.requestPolicy = Objects.requireNonNull(requestPolicy, "requestPolicy");
            return this;
        }

        /**
         * Supplies a custom discovery implementation.
         *
         * @param dnsResolver resolver used by the client
         * @return this builder
         * @throws NullPointerException if {@code dnsResolver} is null
         */
        public Builder dnsResolver(DnsResolver dnsResolver) {
            this.dnsResolver = Objects.requireNonNull(dnsResolver, "dnsResolver");
            return this;
        }

        /**
         * Uses a caller-owned executor for asynchronous operations.
         *
         * <p>The client never shuts down the supplied executor.</p>
         *
         * @param asyncExecutor executor on which blocking client operations may run
         * @return this builder
         * @throws NullPointerException if {@code asyncExecutor} is null
         */
        public Builder asyncExecutor(Executor asyncExecutor) {
            this.asyncExecutor = Objects.requireNonNull(asyncExecutor, "asyncExecutor");
            return this;
        }

        /**
         * Validates all settings and creates an immutable configuration.
         *
         * @return a new immutable configuration
         * @throws IllegalArgumentException if a duration or interval is not positive
         */
        public RateLimitlyClientConfig build() {
            return new RateLimitlyClientConfig(this);
        }
    }
}
