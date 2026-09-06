package com.ratelimitly.internal;

import com.ratelimitly.ResolvedServer;
import com.ratelimitly.RateLimitlyException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SRVRecord;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

public final class SrvResolver {
    private static final long SERVER_ID_EPOCH_2025 = 1_735_689_600L;

    private SrvResolver() {
    }

    public record ResolveResult(List<ResolvedServer> servers, long minSrvTtlMs) {
        public ResolveResult {
            servers = List.copyOf(servers);
            if (minSrvTtlMs < 0) {
                throw new IllegalArgumentException("minSrvTtlMs must be >= 0");
            }
        }
    }

    public static List<ResolvedServer> resolve(String dnsName) throws RateLimitlyException {
        return resolveWithMetadata(dnsName, 5_000L).servers();
    }

    public static List<ResolvedServer> resolve(String dnsName, long timeoutMs) throws RateLimitlyException {
        return resolveWithMetadata(dnsName, timeoutMs).servers();
    }

    public static ResolveResult resolveWithMetadata(String dnsName, long timeoutMs) throws RateLimitlyException {
        String recordName = "_ratelimitly._udp." + dnsName;
        try {
            Resolver resolver = createResolver(timeoutMs);
            Lookup lookup = new Lookup(recordName, Type.SRV);
            lookup.setResolver(resolver);
            Record[] records = lookup.run();
            if (records == null || records.length == 0) {
                throw dns("No SRV servers found for " + recordName);
            }

            List<ResolvedServer> servers = new ArrayList<>();
            long minSrvTtlMs = 0L;
            for (Record record : records) {
                if (!(record instanceof SRVRecord srvRecord)) {
                    continue;
                }
                String target = srvRecord.getTarget().toString(true);
                Long serverId = parseServerIdFromTarget(target);
                if (serverId == null) {
                    continue;
                }
                long ttlMs = Math.max(0L, record.getTTL() * 1000L);
                if (ttlMs > 0 && (minSrvTtlMs == 0 || ttlMs < minSrvTtlMs)) {
                    minSrvTtlMs = ttlMs;
                }
                for (InetAddress address : resolveAddresses(resolver, target)) {
                    servers.add(new ResolvedServer(target, address, srvRecord.getPort(), serverId));
                }
            }

            if (servers.isEmpty()) {
                throw dns("No valid SRV targets found for " + recordName);
            }

            servers.sort(
                Comparator.comparingLong((ResolvedServer s) -> serverStartSecondsFromId(s.serverId()))
                    .thenComparingLong(ResolvedServer::serverId)
                    .thenComparing(s -> s.address().getHostAddress())
                    .thenComparingInt(ResolvedServer::port)
            );
            return new ResolveResult(servers, minSrvTtlMs);
        } catch (RateLimitlyException e) {
            throw e;
        } catch (Exception e) {
            throw dns("SRV lookup failed for " + recordName + ": " + e.getMessage(), e);
        }
    }

    public static long serverStartSecondsFromId(long serverId) {
        return SERVER_ID_EPOCH_2025 + (serverId >>> 23);
    }

    public static Long parseServerIdFromTarget(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        String trimmed = target.endsWith(".") ? target.substring(0, target.length() - 1) : target;
        String[] labels = trimmed.split("\\.");
        if (labels.length == 0) {
            return null;
        }
        String first = labels[0].toLowerCase();
        if (!first.startsWith("s-") || first.length() <= 2) {
            return null;
        }
        String decimal = first.substring(2);
        if (decimal.length() > 1 && decimal.startsWith("0")) {
            return null;
        }
        if (!decimal.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Long.parseUnsignedLong(decimal);
    }

    private static RateLimitlyException dns(String message) {
        return new RateLimitlyException(RateLimitlyException.ErrorKind.DNS_DISCOVERY, message);
    }

    private static RateLimitlyException dns(String message, Throwable cause) {
        return new RateLimitlyException(RateLimitlyException.ErrorKind.DNS_DISCOVERY, message, cause);
    }

    private static Resolver createResolver(long timeoutMs) throws Exception {
        long effectiveTimeoutMs = Math.max(200L, timeoutMs);
        SimpleResolver resolver;
        String configuredResolver = System.getenv("RCLIENT_DNS_SERVER");
        if (configuredResolver != null && !configuredResolver.isBlank()) {
            resolver = new SimpleResolver();
            InetSocketAddress address = parseResolverAddress(configuredResolver.trim());
            resolver.setAddress(address);
        } else {
            resolver = new SimpleResolver();
        }
        resolver.setTimeout(Duration.ofMillis(effectiveTimeoutMs));
        return resolver;
    }

    private static InetSocketAddress parseResolverAddress(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("[") && trimmed.contains("]")) {
            int end = trimmed.indexOf(']');
            String host = trimmed.substring(1, end);
            int port = 53;
            if (end + 1 < trimmed.length() && trimmed.charAt(end + 1) == ':') {
                port = Integer.parseInt(trimmed.substring(end + 2));
            }
            return new InetSocketAddress(host, port);
        }

        int firstColon = trimmed.indexOf(':');
        int lastColon = trimmed.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            String host = trimmed.substring(0, firstColon);
            int port = Integer.parseInt(trimmed.substring(firstColon + 1));
            return new InetSocketAddress(host, port);
        }
        return new InetSocketAddress(trimmed, 53);
    }

    private static List<InetAddress> resolveAddresses(Resolver resolver, String hostname) throws RateLimitlyException {
        try {
            List<InetAddress> addresses = new ArrayList<>();
            addRecords(resolver, hostname, Type.A, addresses);
            addRecords(resolver, hostname, Type.AAAA, addresses);
            if (addresses.isEmpty()) {
                throw dns("No A/AAAA records found for " + hostname);
            }
            return addresses;
        } catch (RateLimitlyException e) {
            throw e;
        } catch (Exception e) {
            throw dns("Address lookup failed for " + hostname + ": " + e.getMessage(), e);
        }
    }

    private static void addRecords(Resolver resolver, String hostname, int type, List<InetAddress> addresses)
        throws TextParseException {
        Lookup lookup = new Lookup(Name.fromString(hostname + "."), type);
        lookup.setResolver(resolver);
        Record[] records = lookup.run();
        if (records == null) {
            return;
        }
        for (Record record : records) {
            if (record instanceof ARecord aRecord) {
                addresses.add(aRecord.getAddress());
            } else if (record instanceof AAAARecord aaaaRecord) {
                addresses.add(aaaaRecord.getAddress());
            }
        }
    }

}
