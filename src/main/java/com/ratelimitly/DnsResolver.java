package com.ratelimitly;

import java.util.List;

/** Resolves the RateLimitly server membership used by a client. */
@FunctionalInterface
public interface DnsResolver {
    /**
     * Resolves all usable servers for a discovery domain.
     *
     * @param dnsName discovery domain without the {@code _ratelimitly._udp} prefix
     * @return resolved servers; implementations should return an immutable or caller-owned list
     * @throws RateLimitlyException if discovery fails or yields no usable membership
     */
    List<ResolvedServer> resolve(String dnsName) throws RateLimitlyException;
}
