package com.ratelimitly;

import com.ratelimitly.internal.Blake2s;
import com.ratelimitly.internal.MetricsLabelTlv;
import com.ratelimitly.internal.ProtocolCodec;
import com.ratelimitly.internal.ProtocolValidation;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Assumptions;

final class ClientTestScenarios {
    private static final String UNREACHABLE_PROBE_HOST = "100::1";
    private static final String SAMPLE_NONE_API_KEY =
        "rl-none1qyyqwps9qspsyq2sk8e0sfdp3ys";

    private ClientTestScenarios() {
    }

    static void testDecodeNoneCredential() throws Exception {
        ApiKeyInfo decoded = ApiKeyDecoder.decode(SAMPLE_NONE_API_KEY);
        check(decoded.authMethod() == AuthMethod.NONE, "expected NONE auth");
        check(decoded.formatVersion() == 1, "expected API-key format version 1");
        check(decoded.keyId() == 0x0102030405060708L, "expected canonical API-key ID");
        check(decoded.quotas().isPresent(), "expected quotas in none credential");
        check(decoded.quotas().get().latencyBufferSizeMax() == 32L, "expected latency_buffer_size_max 32");
        check(decoded.quotas().get().dedupTtlMsMax() == 300L, "expected dedup_ttl_ms_max 300");
        check(decoded.quotas().get().rateWindowSizeMsMax() == 0xFFFF_FFFFL, "expected full u32 rate window");
    }

    static void testDecodeCookieAndAesCredentials() throws Exception {
        ApiKeyQuotas quotas = new ApiKeyQuotas(65_536, 1_024, 4_096, 32, 300, 0xFFFF_FFFFL);
        String cookieKey = encodeApiKey("cookie", 2L, repeat((byte) 2, 32), quotas);
        String aesKey = encodeApiKey("aes", 3L, repeat((byte) 3, 32), quotas);

        ApiKeyInfo cookie = ApiKeyDecoder.decode(cookieKey);
        check(cookie.authMethod() == AuthMethod.COOKIE, "expected COOKIE auth");
        check(cookie.keyId() == 2L, "expected cookie key_id 2");
        check(cookie.authSecret().length == 32, "expected 32-byte cookie secret");
        check(cookie.quotas().isPresent(), "expected cookie quotas");

        ApiKeyInfo aes = ApiKeyDecoder.decode(aesKey);
        check(aes.authMethod() == AuthMethod.AES, "expected AES auth");
        check(aes.keyId() == 3L, "expected aes key_id 3");
        check(aes.authSecret().length == 32, "expected 32-byte aes secret");
        check(aes.quotas().isPresent(), "expected aes quotas");
    }

    static void testCredentialSafety() throws Exception {
        byte[] expectedSecret = repeat((byte) 0x5A, 32);
        ApiKeyQuotas quotas = new ApiKeyQuotas(65_536, 1_024, 4_096, 32, 300, 0xFFFF_FFFFL);
        String apiKey = encodeApiKey("aes", 7L, expectedSecret, quotas);
        RateLimitlyClientConfig config = RateLimitlyClientConfig.builder(apiKey).dnsName("example.local").build();
        ApiKeyInfo decoded = ApiKeyDecoder.decode(apiKey);

        check(!config.toString().contains(apiKey), "client config rendering exposed the encoded API key");
        check(config.toString().contains("<redacted>"), "client config rendering did not mark the API key as redacted");
        check(!decoded.toString().contains(apiKey), "decoded credential rendering exposed the encoded API key");

        byte[] callerCopy = decoded.authSecret();
        callerCopy[0] = 0;
        check(decoded.authSecret()[0] == expectedSecret[0], "authSecret did not return a defensive copy");

        boolean hasMutableSecretView = Arrays.stream(ApiKeyInfo.class.getMethods())
            .anyMatch(method -> method.getName().equals("authSecretView"));
        check(!hasMutableSecretView, "decoded credential exposes a mutable authSecretView method");
    }

    static void testRejectLegacyCredential() throws Exception {
        String legacyAes =
            "rl-aes1qvqqqqqqqqqqqqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqqqqzqqqqsqqqqqsqqqyqqqqqquzv6tw";
        try {
            ApiKeyDecoder.decode(legacyAes);
            throw new AssertionError("expected legacy credential decoding to fail");
        } catch (RateLimitlyException expected) {
            check(
                expected.getMessage().contains("expected 45 bytes"),
                "expected legacy-length error message"
            );
        }
    }

    static void testRejectManagementKey() throws Exception {
        String managementKey = encodeApiKey(
            "secret",
            0,
            repeat((byte) 0x2A, 32),
            new ApiKeyQuotas(1, 1, 1, 1, 10, 1)
        );
        try {
            RateLimitlyClients.create(RateLimitlyClientConfig.builder(managementKey).build());
            throw new AssertionError("accepted a management key for client requests");
        } catch (RateLimitlyException expected) {
            check(expected.kind() == RateLimitlyException.ErrorKind.CONFIGURATION, "unexpected failure kind");
            check(expected.getMessage().contains("management key"), "expected management-key error");
        }
    }

    static void testRejectInvalidApiKeyV1Boundaries() throws Exception {
        for (String invalid : List.of(
            "rl-aes1qqpqqqqqqqqqqqqzqgpqyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqszqghjmuhccp9vn9",
            "rl-aes1qgpqqqqqqqqqqqqzqgpqyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqszqghjmuhcchgqf0",
            "rl-aes1qyysqqqqqqqqqqqfpyysjzgfpyysjzgfpyysjzgfpyysjzgfpyysjzgfpyysjzgfpyqqqqqqurys6m",
            "rl-aes1qyysqqqqqqqqqqqfpyysjzgfpyysjzgfpyysjzgfpyysjzgfpyysjzgfpyysjzgfpyvsqzqqs45cew"
        )) {
            try {
                ApiKeyDecoder.decode(invalid);
                throw new AssertionError("accepted invalid API key");
            } catch (RateLimitlyException expected) {
                // Expected strict format-version or packed-quota rejection.
            }
        }
    }

    static void testMetricsLabelTlvPadding() throws Exception {
        byte[] tlv = MetricsLabelTlv.encode("abc");
        check(tlv.length == 12, "expected padded TLV size of 12");
        check(readLe16(tlv, 0) == 0x4C4D, "unexpected TLV type");
        check(readLe16(tlv, 2) == 12, "unexpected TLV size");
        check(readLe16(tlv, 4) == 3, "unexpected label length");
        check(tlv[6] == 'a' && tlv[7] == 'b' && tlv[8] == 'c', "unexpected label bytes");
        check(tlv[9] == 0 && tlv[10] == 0 && tlv[11] == 0, "expected zero padding");
    }

    static void testBlake2sKnownVector() {
        byte[] digest = Blake2s.digest(new byte[0]);
        String hex = toHex(digest);
        check(
            hex.equals("69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9"),
            "unexpected BLAKE2s digest for empty input"
        );
    }

    static void testCanonicalStateIdVectors() {
        check(
            toHex(CanonicalIds.bucketId("checkout", 1000, 100))
                .equals("f5cf3ad8b8406854b596ba3614f16eff"),
            "unexpected canonical checkout bucket ID"
        );
        check(
            toHex(CanonicalIds.latencyTrackerId("inventory-backend", 10000, 100, 5))
                .equals("6a17d07a424568304e50d28540f76e67"),
            "unexpected canonical inventory latency-tracker ID"
        );
        check(
            toHex(CanonicalIds.latencyTrackerId("café", 60000, 200, 3))
                .equals("0f04bcd0fa9d655ca40dd204f50196f7"),
            "unexpected UTF-8 latency-tracker ID"
        );
        byte[] binaryName = {
            'b', 'i', 'n', 'a', 'r', 'y', 0, 't', 'r', 'a', 'c', 'k', 'e', 'r'
        };
        check(
            toHex(CanonicalIds.latencyTrackerId(
                binaryName,
                0xFFFF_FFFFL,
                0xFFFF_FFFFL,
                0xFFFF_FFFFL
            )).equals("2944d00ab0f1829a4d598d47f32fb0fa"),
            "unexpected embedded-NUL latency-tracker ID"
        );
    }

    static void testCanonicalStateIdsInRateRequest() throws Exception {
        RateLimitlyClientConfig config = RateLimitlyClientConfig.builder(SAMPLE_NONE_API_KEY).dnsName("example.local").build();
        ApiKeyInfo credential = ApiKeyDecoder.decode(SAMPLE_NONE_API_KEY);
        RateLimitRequest request = new RateLimitRequest(
            List.of(new ResourceRequest("checkout", 1000, 100, 1)),
            List.of(new LatencyGuard("inventory-backend", 50, 10000, 100, 5)),
            null
        );
        UUID requestId = UUID.randomUUID();
        ProtocolCodec.EncodedRequest encoded = ProtocolCodec.encodeRateLimitRequest(config, credential, request, requestId);
        byte[] packet = encoded.packet();
        int guardOffset = 40 + 4 + 8 + 4;
        int resourceOffset = guardOffset + 36;
        check(
            toHex(Arrays.copyOfRange(packet, guardOffset, guardOffset + 16))
                .equals("6a17d07a424568304e50d28540f76e67"),
            "expected canonical latency-tracker ID in guard block"
        );
        check(
            toHex(Arrays.copyOfRange(packet, resourceOffset, resourceOffset + 16))
                .equals("f5cf3ad8b8406854b596ba3614f16eff"),
            "expected canonical bucket ID in resource block"
        );
    }

    static void testLatencyReportUses32ByteBlocks() throws Exception {
        RateLimitlyClientConfig config = RateLimitlyClientConfig.builder(SAMPLE_NONE_API_KEY).dnsName("example.local").build();
        ApiKeyInfo credential = ApiKeyDecoder.decode(SAMPLE_NONE_API_KEY);
        LatencyReport report = new LatencyReport(List.of(
            new ServiceLatencyReport("service", 85, 1000, 10, 1)
        ));

        ProtocolCodec.EncodedRequest encoded =
            ProtocolCodec.encodeLatencyReport(config, credential, report, UUID.randomUUID());
        byte[] packet = encoded.packet();
        check(packet.length == 88, "expected one-service latency packet to be 88 bytes with NONE auth");
        check(readLe16(packet, 44) == 0x524C, "expected latency report PDU");
        check(readLe16(packet, 46) == 44, "expected one-service latency PDU to be 44 bytes");
        check(readLe16(packet, 52) == 1, "expected one service latency block");
        check(
            Arrays.equals(
                Arrays.copyOfRange(packet, 56, 72),
                CanonicalIds.latencyTrackerId("service", 1000, 10, 1)
            ),
            "expected canonical latency-tracker ID in latency report"
        );
        check(
            packet[84] == 85 && packet[85] == 0 && packet[86] == 0 && packet[87] == 0,
            "expected observed latency to end the 32-byte service block"
        );
    }

    static void testDedupQuotaEnforcement() throws Exception {
        RequestPolicy policy = new RequestPolicy(101, 1, RequestPolicy.Schedule.fixed(1), 1, true);
        RateLimitlyClientConfig config = RateLimitlyClientConfig.builder(SAMPLE_NONE_API_KEY).dnsName("example.local")
            .requestPolicy(policy)
            .build();
        ApiKeyInfo credential = ApiKeyDecoder.decode(SAMPLE_NONE_API_KEY);
        try {
            ProtocolValidation.validateConfig(config, credential);
            throw new AssertionError("expected dedup quota validation failure");
        } catch (RateLimitlyException expected) {
            check(
                expected.getMessage().contains("dedup_ttl_ms_max"),
                "expected dedup quota error message"
            );
        }
    }

    static void testRateWindowQuotaEnforcement() throws Exception {
        ApiKeyQuotas quotas = new ApiKeyQuotas(65_536, 1_024, 4_096, 32, 300, 1_024);
        String key = encodeApiKey("none", 9, new byte[0], quotas);
        RateLimitlyClientConfig config = RateLimitlyClientConfig.builder(key).dnsName("example.local").build();
        RateLimitRequest request = new RateLimitRequest(
            List.of(new ResourceRequest("bucket", 1_025, 100, 1)),
            List.of(),
            null
        );
        try {
            ProtocolValidation.validateRateLimitRequest(config, ApiKeyDecoder.decode(key), request);
            throw new AssertionError("expected rate-window quota validation failure");
        } catch (RateLimitlyException expected) {
            check(expected.getMessage().contains("rate_window_size_ms_max"), "expected rate-window quota error");
        }
    }

    static void testUnifiedPolicySchedules() {
        RequestPolicy defaults = RequestPolicy.defaultPolicy();
        check(defaults.horizonMs(300) == 60, "default horizon must be 3 * 20 ms");
        check(RequestPolicy.Schedule.linear(1, 2, 6).units(3) == 6, "linear schedule must cap");
        check(RequestPolicy.Schedule.exponential(1, 2, 8).units(3) == 8, "exponential schedule must grow");
        RequestPolicy noFinal = new RequestPolicy(25, 3, RequestPolicy.Schedule.fixed(1), 0, false);
        check(noFinal.horizonMs(300) == 100, "disabled final wait must not count toward TTL");
    }

    static void testRateResponseRoundTripNoneAuth() throws Exception {
        RateLimitlyClientConfig config = RateLimitlyClientConfig.builder(SAMPLE_NONE_API_KEY).dnsName("example.local").build();
        ApiKeyInfo credential = ApiKeyDecoder.decode(SAMPLE_NONE_API_KEY);
        RateLimitRequest request = new RateLimitRequest(
            List.of(new ResourceRequest("bucket", 1000, 100, 1)),
            List.of(new LatencyGuard("service", 50, 1000, 10, 1)),
            "lbl"
        );
        UUID requestId = UUID.randomUUID();
        ProtocolCodec.EncodedRequest encoded = ProtocolCodec.encodeRateLimitRequest(config, credential, request, requestId);
        byte[] response = buildRateResponsePacket(
            credential,
            7L,
            encoded.requestId(),
            new long[] {25},
            new int[] {0},
            new long[] {100}
        );
        RateLimitDecision decision = ProtocolCodec.parseRateResponse(response, credential, request, encoded.requestId());
        check(decision.success(), "expected successful round-trip decision");
        check(decision.serverId() == 7L, "expected server_id 7");
        check(decision.guardDecisions().size() == 1, "expected one guard decision");
        check(decision.resourceDecisions().size() == 1, "expected one resource decision");
        check(decision.guardDecisions().getFirst().currentLatencyMs() == 25L, "expected guard latency 25");
    }

    static void testGuardOnlyRequestRoundTrip() throws Exception {
        RateLimitlyClientConfig config = RateLimitlyClientConfig.builder(SAMPLE_NONE_API_KEY)
            .dnsName("example.local")
            .build();
        ApiKeyInfo apiKey = ApiKeyDecoder.decode(SAMPLE_NONE_API_KEY);
        RateLimitRequest request = new RateLimitRequest(
            List.of(),
            List.of(new LatencyGuard("service", 50, 1_000, 10, 1)),
            null
        );
        ProtocolCodec.EncodedRequest encoded = ProtocolCodec.encodeRateLimitRequest(
            config, apiKey, request, UUID.randomUUID()
        );
        byte[] response = buildRateResponsePacket(
            apiKey,
            12L,
            encoded.requestId(),
            new long[] {10},
            new int[0],
            new long[0]
        );

        RateLimitDecision decision = ProtocolCodec.parseRateResponse(
            response, apiKey, request, encoded.requestId()
        );
        check(decision.success(), "guard-only request should grant when its guard passes");
        check(decision.guardDecisions().size() == 1, "guard-only response lost its guard decision");
        check(decision.resourceDecisions().isEmpty(), "guard-only response gained a resource decision");
    }

    static void testRateResponseRoundTripAesAuth() throws Exception {
        ApiKeyQuotas quotas = new ApiKeyQuotas(65_536, 1_024, 4_096, 32, 300, 0xFFFF_FFFFL);
        String aesKey = encodeApiKey("aes", 3L, repeat((byte) 3, 32), quotas);
        RateLimitlyClientConfig config = RateLimitlyClientConfig.builder(aesKey).dnsName("example.local").build();
        ApiKeyInfo credential = ApiKeyDecoder.decode(aesKey);
        RateLimitRequest request = new RateLimitRequest(
            List.of(new ResourceRequest("bucket", 1000, 100, 1)),
            List.of(),
            null
        );
        UUID requestId = UUID.randomUUID();
        ProtocolCodec.EncodedRequest encoded = ProtocolCodec.encodeRateLimitRequest(config, credential, request, requestId);
        byte[] response = buildRateResponsePacket(
            credential,
            11L,
            encoded.requestId(),
            new long[0],
            new int[] {0},
            new long[] {100}
        );
        RateLimitDecision decision = ProtocolCodec.parseRateResponse(response, credential, request, encoded.requestId());
        check(decision.success(), "expected AES round-trip decision to succeed");
        check(decision.serverId() == 11L, "expected AES server_id 11");

        byte[] tampered = Arrays.copyOf(response, response.length);
        tampered[4] ^= 0x01;
        boolean failed = false;
        try {
            ProtocolCodec.parseRateResponse(tampered, credential, request, encoded.requestId());
        } catch (RateLimitlyException expected) {
            failed = true;
        }
        check(failed, "expected AES response decrypt to reject tampered AAD");
    }

    static void testMockUdpExchangeWithInjectedResolver() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(2000);
            long serverId = 21L;
            Thread responder = new Thread(() -> {
                try {
                    DatagramPacket requestPacket = new DatagramPacket(new byte[2048], 2048);
                    server.receive(requestPacket);
                    byte[] requestBytes = Arrays.copyOfRange(
                        requestPacket.getData(),
                        requestPacket.getOffset(),
                        requestPacket.getOffset() + requestPacket.getLength()
                    );
                    byte[] requestId = Arrays.copyOfRange(requestBytes, 12, 28);
                    ApiKeyInfo credential = ApiKeyDecoder.decode(SAMPLE_NONE_API_KEY);
                    byte[] response = buildRateResponsePacket(
                        credential,
                        serverId,
                        requestId,
                        new long[] {10},
                        new int[] {0},
                        new long[] {100}
                    );
                    DatagramPacket reply = new DatagramPacket(
                        response,
                        response.length,
                        requestPacket.getAddress(),
                        requestPacket.getPort()
                    );
                    server.send(reply);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            responder.start();

            RateLimitlyClientConfig config = RateLimitlyClientConfig.builder(SAMPLE_NONE_API_KEY).dnsName("example.local")
                .dnsResolver(dnsName -> List.of(new ResolvedServer(
                    dnsName,
                    InetAddress.getLoopbackAddress(),
                    server.getLocalPort(),
                    serverId
                )))
                .dnsTimeoutMs(500)
                .build();

            RateLimitRequest request = new RateLimitRequest(
                List.of(new ResourceRequest("bucket", 1000, 100, 1)),
                List.of(new LatencyGuard("service", 50, 1000, 10, 1)),
                null
            );

            try (RateLimitlyClient client = RateLimitlyClients.create(config)) {
                RateLimitDecision decision = client.checkRateLimit(request);
                check(decision.success(), "expected UDP mock exchange to succeed");
                check(decision.serverId() == serverId, "expected mock server id");
                check(client.diagnostics().discoveredEndpoints().size() == 1, "expected one discovered endpoint");
            }

            responder.join(2000);
            check(!responder.isAlive(), "mock UDP responder thread did not finish");
        } catch (Exception e) {
            if (isSocketPermissionError(e)) {
                Assumptions.abort("local UDP sockets are not permitted in this environment");
            }
            throw e;
        }
    }

    /**
     * A dual-stack SRV target expands to one endpoint per address sharing a single server id. When
     * one of those addresses is unreachable the request must still complete through the other, and
     * the failure must be visible in diagnostics rather than swallowed.
     */
    static void testUnreachableEndpointDoesNotAbortRequest() throws Exception {
        InetAddress unreachable = InetAddress.getByName(UNREACHABLE_PROBE_HOST);
        requireSynchronousSendFailure(unreachable);

        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(2000);
            long serverId = 31L;
            Thread responder = new Thread(() -> {
                try {
                    DatagramPacket requestPacket = new DatagramPacket(new byte[2048], 2048);
                    server.receive(requestPacket);
                    byte[] requestBytes = Arrays.copyOfRange(
                        requestPacket.getData(),
                        requestPacket.getOffset(),
                        requestPacket.getOffset() + requestPacket.getLength()
                    );
                    byte[] requestId = Arrays.copyOfRange(requestBytes, 12, 28);
                    ApiKeyInfo credential = ApiKeyDecoder.decode(SAMPLE_NONE_API_KEY);
                    byte[] response = buildRateResponsePacket(
                        credential,
                        serverId,
                        requestId,
                        new long[] {10},
                        new int[] {0},
                        new long[] {100}
                    );
                    server.send(new DatagramPacket(
                        response,
                        response.length,
                        requestPacket.getAddress(),
                        requestPacket.getPort()
                    ));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            responder.start();

            int reachablePort = server.getLocalPort();
            // The unreachable address is listed first so a successful request proves the send loop
            // continued past it rather than stopping at the first failure.
            RateLimitlyClientConfig config = RateLimitlyClientConfig.builder(SAMPLE_NONE_API_KEY).dnsName("example.local")
                .dnsResolver(dnsName -> List.of(
                    new ResolvedServer(dnsName, unreachable, reachablePort, serverId),
                    new ResolvedServer(dnsName, InetAddress.getLoopbackAddress(), reachablePort, serverId)
                ))
                .dnsTimeoutMs(500)
                .build();

            RateLimitRequest request = new RateLimitRequest(
                List.of(new ResourceRequest("bucket", 1000, 100, 1)),
                List.of(new LatencyGuard("service", 50, 1000, 10, 1)),
                null
            );

            try (RateLimitlyClient client = RateLimitlyClients.create(config)) {
                RateLimitDecision decision = client.checkRateLimit(request);
                check(decision.success(), "expected the request to survive an unreachable endpoint");
                check(decision.serverId() == serverId, "expected the reachable endpoint's server id");

                ServerStats stats = client.diagnostics().serverStats().get(serverId);
                check(stats != null, "expected diagnostics for the server");
                check(stats.sendFailures() >= 1, "expected the failed endpoint to be recorded");
                check(stats.validResponses() >= 1, "expected the reachable endpoint's response counted");
            }

            responder.join(2000);
            check(!responder.isAlive(), "mock UDP responder thread did not finish");
        } catch (Exception e) {
            if (isSocketPermissionError(e)) {
                Assumptions.abort("local UDP sockets are not permitted in this environment");
            }
            throw e;
        }
    }

    /**
     * Skips the caller unless this host fails a datagram send to {@code address} synchronously.
     * Networks that blackhole rather than reject the discard prefix cannot exercise the behaviour.
     */
    private static void requireSynchronousSendFailure(InetAddress address) throws Exception {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.send(new DatagramPacket(new byte[] {0}, 1, address, 9));
        } catch (java.io.IOException expected) {
            return;
        }
        Assumptions.abort(
            "this host accepts datagrams addressed to " + address.getHostAddress()
        );
    }

    static void testHaSelectsPromptOldestResponse() throws Exception {
        try (
            DatagramSocket olderServer = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            DatagramSocket newerServer = new DatagramSocket(0, InetAddress.getLoopbackAddress())
        ) {
            long olderId = (10L << 23) | 1;
            long newerId = (20L << 23) | 2;
            ApiKeyInfo credential = ApiKeyDecoder.decode(SAMPLE_NONE_API_KEY);
            Thread older = responderThread(olderServer, credential, olderId, 15, 0);
            Thread newer = responderThread(newerServer, credential, newerId, 0, 1);
            older.start();
            newer.start();

            RequestPolicy policy = new RequestPolicy(
                100, 0, RequestPolicy.Schedule.fixed(1), 0, false
            );
            RateLimitlyClientConfig config = RateLimitlyClientConfig
                .builder(SAMPLE_NONE_API_KEY)
                .dnsName("example.local")
                .requestPolicy(policy)
                .dnsResolver(ignored -> List.of(
                    new ResolvedServer("newer", InetAddress.getLoopbackAddress(), newerServer.getLocalPort(), newerId),
                    new ResolvedServer("older", InetAddress.getLoopbackAddress(), olderServer.getLocalPort(), olderId)
                ))
                .build();
            RateLimitRequest request = new RateLimitRequest(
                List.of(new ResourceRequest("bucket", 1000, 100, 1)), List.of(), null
            );
            long started = System.nanoTime();
            try (RateLimitlyClient client = RateLimitlyClients.create(config)) {
                RateLimitDecision decision = client.checkRateLimit(request);
                check(decision.serverId() == olderId, "globally oldest server must win before the deadline");
                check(decision.success(), "older server's grant must be selected over newer denial");
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            check(elapsedMs < 100, "oldest response should complete before the round deadline");
            older.join(1000);
            newer.join(1000);
        } catch (Exception error) {
            if (isSocketPermissionError(error)) {
                Assumptions.abort("local UDP sockets are not permitted in this environment");
            }
            throw error;
        }
    }

    private static Thread responderThread(
        DatagramSocket socket,
        ApiKeyInfo credential,
        long serverId,
        long delayMs,
        int deficit
    ) {
        return new Thread(() -> {
            try {
                DatagramPacket request = new DatagramPacket(new byte[2048], 2048);
                socket.receive(request);
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                byte[] requestId = Arrays.copyOfRange(request.getData(), 12, 28);
                byte[] response = buildRateResponsePacket(
                    credential, serverId, requestId, new long[0], new int[] {deficit}, new long[] {100}
                );
                socket.send(new DatagramPacket(
                    response, response.length, request.getAddress(), request.getPort()
                ));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
    }

    static void testSteeringSkipsOccupiedCandidate() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(2_000);
            long serverId = 41L;
            ApiKeyInfo credential = ApiKeyDecoder.decode(SAMPLE_NONE_API_KEY);
            AtomicReference<Throwable> responderFailure = new AtomicReference<>();
            Thread responder = new Thread(() -> {
                try {
                    DatagramPacket first = receivePacket(server);
                    byte[] firstResponse = buildRateResponsePacket(
                        credential,
                        serverId,
                        requestId(first),
                        new long[0],
                        new int[] {0},
                        new long[] {100},
                        false
                    );
                    server.send(new DatagramPacket(
                        firstResponse, firstResponse.length, first.getAddress(), first.getPort()
                    ));

                    DatagramPacket second = receivePacket(server);
                    byte[] secondResponse = buildRateResponsePacket(
                        credential,
                        serverId,
                        requestId(second),
                        new long[0],
                        new int[] {0},
                        new long[] {100},
                        true
                    );
                    server.send(new DatagramPacket(
                        secondResponse, secondResponse.length, second.getAddress(), second.getPort()
                    ));
                } catch (Throwable error) {
                    responderFailure.set(error);
                }
            });
            responder.start();

            RateLimitlyClientConfig config = localServerConfig(server, serverId, RequestPolicy.defaultPolicy());
            RateLimitRequest request = oneTokenRequest("steering-collision");
            try (RateLimitlyClient client = RateLimitlyClients.create(config)) {
                DatagramSocket currentSocket = clientSocket(client);
                try (DatagramSocket blocker = bindDynamicRangeSocket(currentSocket.getLocalAddress())) {
                    seedSteeringCursor(client, blocker.getLocalPort());

                    RateLimitDecision decision = client.checkRateLimit(request);
                    check(decision.success(), "occupied steering candidate changed the grant");
                    check(client.diagnostics().portChanges() == 1, "successful steering rebind was not recorded");
                    check(clientSocket(client).getLocalPort() != blocker.getLocalPort(), "client reused occupied port");

                    RateLimitDecision afterRebind = client.checkRateLimit(oneTokenRequest("steering-after-rebind"));
                    check(afterRebind.success(), "replacement receive path missed an immediate response");
                }
            }

            responder.join(2_000);
            check(!responder.isAlive(), "steering collision responder did not finish");
            if (responderFailure.get() != null) {
                throw new AssertionError("steering collision responder failed", responderFailure.get());
            }
        } catch (Exception error) {
            if (isSocketPermissionError(error)) {
                Assumptions.abort("local UDP sockets are not permitted in this environment");
            }
            throw error;
        }
    }

    static void testSteeringCursorIncrementsAndWraps() {
        check(
            DefaultRateLimitlyClient.incrementSteeringPort(49_152) == 49_153,
            "steering cursor did not advance by exactly one"
        );
        check(
            DefaultRateLimitlyClient.incrementSteeringPort(65_534) == 65_535,
            "steering cursor skipped the final dynamic-range port"
        );
        check(
            DefaultRateLimitlyClient.incrementSteeringPort(65_535) == 49_152,
            "steering cursor did not wrap at the dynamic-range boundary"
        );
    }

    static void testSteeringDrainsConcurrentRequests() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(2_000);
            long serverId = 42L;
            ApiKeyInfo credential = ApiKeyDecoder.decode(SAMPLE_NONE_API_KEY);
            CountDownLatch firstResponseSent = new CountDownLatch(1);
            CountDownLatch releaseSecondResponse = new CountDownLatch(1);
            AtomicReference<Throwable> responderFailure = new AtomicReference<>();

            Thread responder = new Thread(() -> {
                try {
                    DatagramPacket first = receivePacket(server);
                    DatagramPacket second = receivePacket(server);
                    SocketAddress firstClient = first.getSocketAddress();
                    SocketAddress secondClient = second.getSocketAddress();
                    check(firstClient.equals(secondClient), "concurrent requests did not share the original socket");

                    byte[] firstResponse = buildRateResponsePacket(
                        credential,
                        serverId,
                        requestId(first),
                        new long[0],
                        new int[] {0},
                        new long[] {100},
                        false
                    );
                    server.send(new DatagramPacket(firstResponse, firstResponse.length, firstClient));
                    firstResponseSent.countDown();

                    check(releaseSecondResponse.await(2, TimeUnit.SECONDS), "second response was never released");
                    byte[] secondResponse = buildRateResponsePacket(
                        credential,
                        serverId,
                        requestId(second),
                        new long[0],
                        new int[] {0},
                        new long[] {100},
                        true
                    );
                    server.send(new DatagramPacket(secondResponse, secondResponse.length, secondClient));
                } catch (Throwable error) {
                    responderFailure.set(error);
                    firstResponseSent.countDown();
                }
            });
            responder.start();

            RequestPolicy policy = new RequestPolicy(
                100, 0, RequestPolicy.Schedule.fixed(1), 2, false
            );
            RateLimitlyClientConfig config = localServerConfig(server, serverId, policy);
            try (RateLimitlyClient client = RateLimitlyClients.create(config)) {
                CompletableFuture<RateLimitDecision> first = checkInThread(
                    client, oneTokenRequest("steering-concurrent-a")
                );
                CompletableFuture<RateLimitDecision> second = checkInThread(
                    client, oneTokenRequest("steering-concurrent-b")
                );

                check(firstResponseSent.await(2, TimeUnit.SECONDS), "first steering response did not arrive");
                CompletableFuture.anyOf(first, second).get(2, TimeUnit.SECONDS);
                boolean changedWhileRequestWasInflight = client.diagnostics().portChanges() != 0;
                releaseSecondResponse.countDown();

                RateLimitDecision firstDecision = first.get(2, TimeUnit.SECONDS);
                RateLimitDecision secondDecision = second.get(2, TimeUnit.SECONDS);
                check(!changedWhileRequestWasInflight, "steering changed the socket before concurrent requests drained");
                check(firstDecision.success() && secondDecision.success(), "steering changed a concurrent grant");
                check(client.diagnostics().portChanges() == 1, "deferred steering rebind did not run after drain");
            } finally {
                releaseSecondResponse.countDown();
            }

            responder.join(2_000);
            check(!responder.isAlive(), "concurrent steering responder did not finish");
            if (responderFailure.get() != null) {
                throw new AssertionError("concurrent steering responder failed", responderFailure.get());
            }
        } catch (Exception error) {
            if (isSocketPermissionError(error)) {
                Assumptions.abort("local UDP sockets are not permitted in this environment");
            }
            throw error;
        }
    }

    private static RateLimitlyClientConfig localServerConfig(
        DatagramSocket server,
        long serverId,
        RequestPolicy policy
    ) {
        return RateLimitlyClientConfig.builder(SAMPLE_NONE_API_KEY).dnsName("example.local")
            .requestPolicy(policy)
            .dnsResolver(dnsName -> List.of(new ResolvedServer(
                dnsName,
                InetAddress.getLoopbackAddress(),
                server.getLocalPort(),
                serverId
            )))
            .build();
    }

    private static RateLimitRequest oneTokenRequest(String bucketName) {
        return new RateLimitRequest(
            List.of(new ResourceRequest(bucketName, 1_000, 100, 1)), List.of(), null
        );
    }

    private static DatagramPacket receivePacket(DatagramSocket socket) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[2_048], 2_048);
        socket.receive(packet);
        byte[] data = Arrays.copyOfRange(
            packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength()
        );
        return new DatagramPacket(data, data.length, packet.getSocketAddress());
    }

    private static byte[] requestId(DatagramPacket packet) {
        return Arrays.copyOfRange(packet.getData(), packet.getOffset() + 12, packet.getOffset() + 28);
    }

    private static DatagramSocket clientSocket(RateLimitlyClient client) throws Exception {
        Field socket = DefaultRateLimitlyClient.class.getDeclaredField("socket");
        socket.setAccessible(true);
        return (DatagramSocket) socket.get(client);
    }

    private static void seedSteeringCursor(RateLimitlyClient client, int port) throws Exception {
        Field ipv4Cursor = DefaultRateLimitlyClient.class.getDeclaredField("nextSteeringPort");
        ipv4Cursor.setAccessible(true);
        ipv4Cursor.setInt(client, port);
        try {
            Field ipv6Cursor = DefaultRateLimitlyClient.class.getDeclaredField("nextSteeringPortIpv6");
            ipv6Cursor.setAccessible(true);
            ipv6Cursor.setInt(client, port);
        } catch (NoSuchFieldException oldImplementation) {
            // The red test starts against the old single-cursor implementation.
        }
    }

    private static DatagramSocket bindDynamicRangeSocket(InetAddress bindAddress) throws Exception {
        for (int port = 49_152; port <= 65_535; port++) {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(false);
                socket.bind(new java.net.InetSocketAddress(bindAddress, port));
                return socket;
            } catch (java.net.BindException occupied) {
                if (socket != null) {
                    socket.close();
                }
                // Continue in strict +1 order until this test owns one candidate.
            } catch (Exception error) {
                if (socket != null) {
                    socket.close();
                }
                throw error;
            }
        }
        Assumptions.abort("no dynamic-range UDP port is available for collision testing");
        throw new AssertionError("JUnit assumption unexpectedly returned");
    }

    private static CompletableFuture<RateLimitDecision> checkInThread(
        RateLimitlyClient client,
        RateLimitRequest request
    ) {
        CompletableFuture<RateLimitDecision> result = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                result.complete(client.checkRateLimit(request));
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        thread.start();
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static byte[] repeat(byte value, int count) {
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) {
            out[i] = value;
        }
        return out;
    }

    private static int readLe16(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF) | ((buffer[offset + 1] & 0xFF) << 8);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private static String encodeApiKey(String authMethod, long keyId, byte[] secret, ApiKeyQuotas quotas) {
        byte[] quotaWord = toLe32(packQuotas(quotas));
        byte[] payload = switch (authMethod) {
            case "secret" -> secret;
            case "none" -> concat(
                new byte[] {1},
                toLe64(keyId),
                quotaWord
            );
            case "cookie", "aes" -> concat(
                new byte[] {1},
                toLe64(keyId),
                secret,
                quotaWord
            );
            default -> throw new IllegalArgumentException("unsupported auth method " + authMethod);
        };
        return bech32Encode("rl-" + authMethod, payload);
    }

    private static long packQuotas(ApiKeyQuotas quotas) {
        int rateExp = Long.numberOfTrailingZeros(quotas.rateBucketsMax());
        int latencyExp = Long.numberOfTrailingZeros(quotas.latencyServicesMax());
        int labelsExp = Long.numberOfTrailingZeros(quotas.metricsLabelsMax());
        int bufferExp = Long.numberOfTrailingZeros(quotas.latencyBufferSizeMax());
        int windowExp = quotas.rateWindowSizeMsMax() == 0xFFFF_FFFFL
            ? 31 : Long.numberOfTrailingZeros(quotas.rateWindowSizeMsMax());
        return rateExp
            | ((long) latencyExp << 5)
            | ((long) labelsExp << 10)
            | ((long) bufferExp << 15)
            | ((quotas.dedupTtlMsMax() / 10) << 19)
            | ((long) windowExp << 27);
    }

    private static String bech32Encode(String hrp, byte[] payload) {
        byte[] data = convertBits(payload, 8, 5, true);
        byte[] checksum = createChecksum(hrp, data);
        byte[] combined = concat(data, checksum);
        StringBuilder out = new StringBuilder(hrp.length() + 1 + combined.length);
        out.append(hrp);
        out.append('1');
        for (byte value : combined) {
            out.append(charsetChar(value));
        }
        return out.toString();
    }

    private static byte[] createChecksum(String hrp, byte[] data) {
        byte[] values = concat(hrpExpand(hrp), data, new byte[6]);
        int polymod = polymod(values) ^ 1;
        byte[] checksum = new byte[6];
        for (int i = 0; i < 6; i++) {
            checksum[i] = (byte) ((polymod >> (5 * (5 - i))) & 31);
        }
        return checksum;
    }

    private static byte[] hrpExpand(String hrp) {
        byte[] out = new byte[(hrp.length() * 2) + 1];
        int pos = 0;
        for (int i = 0; i < hrp.length(); i++) {
            out[pos++] = (byte) (hrp.charAt(i) >> 5);
        }
        out[pos++] = 0;
        for (int i = 0; i < hrp.length(); i++) {
            out[pos++] = (byte) (hrp.charAt(i) & 31);
        }
        return out;
    }

    private static int polymod(byte[] values) {
        int[] gen = {0x3B6A57B2, 0x26508E6D, 0x1EA119FA, 0x3D4233DD, 0x2A1462B3};
        int chk = 1;
        for (byte value : values) {
            int top = chk >>> 25;
            chk = ((chk & 0x1FF_FFFF) << 5) ^ (value & 0xFF);
            for (int i = 0; i < gen.length; i++) {
                if (((top >>> i) & 1) != 0) {
                    chk ^= gen[i];
                }
            }
        }
        return chk;
    }

    private static byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        int maxValue = (1 << toBits) - 1;
        int maxAcc = (1 << (fromBits + toBits - 1)) - 1;
        byte[] tmp = new byte[(data.length * fromBits + toBits - 1) / toBits + 1];
        int out = 0;
        for (byte datum : data) {
            int value = datum & 0xFF;
            acc = ((acc << fromBits) | value) & maxAcc;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                tmp[out++] = (byte) ((acc >>> bits) & maxValue);
            }
        }
        if (pad && bits > 0) {
            tmp[out++] = (byte) ((acc << (toBits - bits)) & maxValue);
        }
        byte[] result = new byte[out];
        System.arraycopy(tmp, 0, result, 0, out);
        return result;
    }

    private static char charsetChar(byte value) {
        return "qpzry9x8gf2tvdw0s3jn54khce6mua7l".charAt(value);
    }

    private static byte[] concat(byte[]... arrays) {
        int size = 0;
        for (byte[] array : arrays) {
            size += array.length;
        }
        byte[] out = new byte[size];
        int pos = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, out, pos, array.length);
            pos += array.length;
        }
        return out;
    }

    private static byte[] toLe32(long value) {
        return new byte[] {
            (byte) (value & 0xFF),
            (byte) ((value >>> 8) & 0xFF),
            (byte) ((value >>> 16) & 0xFF),
            (byte) ((value >>> 24) & 0xFF)
        };
    }

    private static byte[] toLe64(long value) {
        return new byte[] {
            (byte) (value & 0xFF),
            (byte) ((value >>> 8) & 0xFF),
            (byte) ((value >>> 16) & 0xFF),
            (byte) ((value >>> 24) & 0xFF),
            (byte) ((value >>> 32) & 0xFF),
            (byte) ((value >>> 40) & 0xFF),
            (byte) ((value >>> 48) & 0xFF),
            (byte) ((value >>> 56) & 0xFF)
        };
    }

    private static byte[] buildRateResponsePacket(
        ApiKeyInfo credential,
        long serverId,
        byte[] requestId,
        long[] currentLatencies,
        int[] deficits,
        long[] actualRates
    ) throws Exception {
        return buildRateResponsePacket(
            credential, serverId, requestId, currentLatencies, deficits, actualRates, true
        );
    }

    private static byte[] buildRateResponsePacket(
        ApiKeyInfo credential,
        long serverId,
        byte[] requestId,
        long[] currentLatencies,
        int[] deficits,
        long[] actualRates,
        boolean steeringFeedback
    ) throws Exception {
        byte[] pdu = buildRateResponsePdu(currentLatencies, deficits, actualRates);

        ByteBuffer apiKeyHeader = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
        apiKeyHeader.putShort((short) 0x4C52);
        apiKeyHeader.putShort((short) 40);
        apiKeyHeader.putLong(serverId);
        apiKeyHeader.put(requestId);
        apiKeyHeader.putLong(System.currentTimeMillis());
        apiKeyHeader.put((byte) (steeringFeedback ? 1 : 0));
        apiKeyHeader.put((byte) 0);
        apiKeyHeader.putShort((short) 0);

        byte[] authAndPdu = switch (credential.authMethod()) {
            case NONE -> concat(toLe16(0x414E), toLe16(4), pdu);
            case COOKIE -> concat(toLe16(0x4143), toLe16(36), credential.authSecret(), pdu);
            case AES -> {
                byte[] nonce = new byte[12];
                Arrays.fill(nonce, (byte) 7);
                byte[] authHeader = concat(toLe16(0x4541), toLe16(32));
                byte[] aad = concat(apiKeyHeader.array(), authHeader, nonce);
                AesResult encrypted = encryptAes(pdu, credential.authSecret(), nonce, aad);
                yield concat(authHeader, nonce, encrypted.authTag, encrypted.ciphertext);
            }
            case SECRET -> throw new IllegalArgumentException("rl-secret is not a request credential");
        };

        return concat(apiKeyHeader.array(), authAndPdu);
    }

    private static byte[] buildRateResponsePdu(long[] currentLatencies, int[] deficits, long[] actualRates) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + 4 + (currentLatencies.length * 36) + (deficits.length * 28))
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 0x5252);
        buffer.putShort((short) (8 + 4 + (currentLatencies.length * 36) + (deficits.length * 28)));
        buffer.putInt(0);
        buffer.putShort((short) currentLatencies.length);
        buffer.putShort((short) deficits.length);
        for (long currentLatency : currentLatencies) {
            buffer.put(new byte[16]);
            buffer.putInt(1000);
            buffer.putInt(10);
            buffer.putInt(1);
            buffer.putInt(50);
            buffer.putInt((int) currentLatency);
        }
        for (int i = 0; i < deficits.length; i++) {
            buffer.put(new byte[16]);
            buffer.putInt(1000);
            buffer.putInt((int) actualRates[i]);
            buffer.putShort((short) deficits[i]);
            buffer.putShort((short) 0);
        }
        return buffer.array();
    }

    private static AesResult encryptAes(byte[] pdu, byte[] keyBytes, byte[] nonce, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);
        cipher.updateAAD(aad);
        byte[] ciphertextWithTag = cipher.doFinal(pdu);
        byte[] ciphertext = Arrays.copyOf(ciphertextWithTag, ciphertextWithTag.length - 16);
        byte[] tag = Arrays.copyOfRange(ciphertextWithTag, ciphertextWithTag.length - 16, ciphertextWithTag.length);
        return new AesResult(ciphertext, tag);
    }

    private static byte[] toLe16(int value) {
        return new byte[] {
            (byte) (value & 0xFF),
            (byte) ((value >>> 8) & 0xFF)
        };
    }

    static boolean isSocketPermissionError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("Operation not permitted")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record AesResult(byte[] ciphertext, byte[] authTag) {
    }

}
