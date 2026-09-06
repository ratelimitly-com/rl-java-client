package com.ratelimitly;

import java.net.InetAddress;

/**
 * One trusted server endpoint supplied by discovery.
 *
 * @param host canonical host name returned by discovery
 * @param address resolved network address
 * @param port UDP service port
 * @param serverId unsigned server identity stored in a Java {@code long}
 */
public record ResolvedServer(String host, InetAddress address, int port, long serverId) {
    /** Validates the host, address, and service port. */
    public ResolvedServer {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (address == null) {
            throw new IllegalArgumentException("address must not be null");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in the range 1..65535");
        }
    }
}
