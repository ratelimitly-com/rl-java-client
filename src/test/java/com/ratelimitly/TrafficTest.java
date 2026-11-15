package com.ratelimitly;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

final class TrafficTest {
    @Test void unsignedCountersAndIpFamilies() throws Exception {
        TrafficCounters counters = new TrafficCounters();
        counters.record(true, 100, InetAddress.getByName("127.0.0.1"));
        counters.record(true, 100, InetAddress.getByName("::1"));
        byte[] mapped = new byte[16];
        mapped[10] = mapped[11] = (byte) 255;
        mapped[12] = 127; mapped[15] = 1;
        counters.record(true, 100, Inet6Address.getByAddress(null, mapped, 0));
        TrafficStats before = counters.snapshot();
        assertEquals(BigInteger.valueOf(404), before.sent().ipBytes());
        counters.record(true, Long.MAX_VALUE, InetAddress.getByName("127.0.0.1"));
        assertTrue(counters.snapshot().sent().ipBytes().compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0);
        counters.record(true, Long.MAX_VALUE, InetAddress.getByName("127.0.0.1"));
        assertEquals(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE), counters.snapshot().sent().ipBytes());
        assertTrue(counters.snapshot().incomplete());
        assertEquals(BigInteger.valueOf(3), before.sent().datagrams());
    }

    @Test void countsReplayReportAndMalformedReceiveAndSurvivesClose() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket server = new DatagramSocket(0, loopback)) {
            server.setSoTimeout(2000);
            List<Integer> sentLengths = new ArrayList<>();
            List<Integer> sourcePorts = new ArrayList<>();
            CompletableFuture<Void> responder = CompletableFuture.runAsync(() -> {
                try {
                    byte[] first = null;
                    for (int i = 0; i < 3; i++) {
                        DatagramPacket packet = new DatagramPacket(new byte[2048], 2048);
                        server.receive(packet);
                        byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                        sentLengths.add(data.length);
                        sourcePorts.add(packet.getPort());
                        if (i == 0) first = data; // deliberately drop initial send
                        if (i == 1) {
                            assertArrayEquals(first, data);
                            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                            b.putLong(4, 21L); b.put(36, (byte) 0);
                            b.putShort(44, (short) 0x3252); b.putShort(80, (short) 0);
                            server.send(new DatagramPacket(new byte[4200], 4200, packet.getSocketAddress()));
                            server.send(new DatagramPacket(data, data.length, packet.getSocketAddress()));
                        }
                    }
                } catch (Exception e) { throw new CompletionException(e); }
            });
            RateLimitlyClientConfig config = RateLimitlyClientConfig.builder("rl-none1qgyqwps9qspsyq2sk8e0shcgndc")
                .dnsName("example.test")
                .dnsResolver(name -> List.of(new ResolvedServer(name, loopback, server.getLocalPort(), 21L)))
                .requestPolicy(new RequestPolicy(50, 1, RequestPolicy.Schedule.fixed(1), 0, false)).build();
            RateLimitlyClient client = RateLimitlyClients.create(config);
            TrafficStats stats;
            try (client) {
                assertEquals(BigInteger.ZERO, client.trafficStats().sent().datagrams());
                assertTrue(client.checkRateLimit(new RateLimitRequest(List.of(new ResourceRequest("traffic", 1000, 100, 1)), List.of(), null)).success());
                client.reportLatency(new LatencyReport(List.of(new ServiceLatencyReport("traffic", 10, 1000, 10, 5))));
                responder.get(3, TimeUnit.SECONDS);
                stats = client.trafficStats();
                assertEquals(BigInteger.valueOf(3), stats.sent().datagrams());
                assertEquals(BigInteger.valueOf(sentLengths.stream().mapToInt(Integer::intValue).sum()), stats.sent().payloadBytes());
                assertEquals(stats.sent().payloadBytes().add(BigInteger.valueOf(84)), stats.sent().ipBytes());
                assertEquals(BigInteger.valueOf(2), stats.received().datagrams());
                assertEquals(BigInteger.valueOf(84 + 4200 + 56), stats.received().ipBytes());
                assertEquals(BigInteger.ZERO, stats.sendFailures());
                assertFalse(stats.incomplete());
                assertNotEquals(sourcePorts.get(1), sourcePorts.get(2));
            }
            assertEquals(stats, client.trafficStats());
        }
    }
}
