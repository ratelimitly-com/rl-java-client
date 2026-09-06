package com.ratelimitly;

import com.ratelimitly.internal.ProtocolCodec;
import com.ratelimitly.internal.ProtocolValidation;
import com.ratelimitly.internal.SrvResolver;
import java.io.Serial;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class DefaultRateLimitlyClient implements RateLimitlyClient {
    private static final int ROUTER_SOCKET_TIMEOUT_MS = 250;
    private static final int RECEIVE_BUFFER_SIZE = 2048;
    private static final int STEERING_PORT_BASE = 49_152;
    private static final int STEERING_PORT_SPAN = 16_384;

    private final RateLimitlyClientConfig config;
    private final ApiKeyInfo apiKeyInfo;
    private final String dnsName;
    private final Executor asyncExecutor;
    private final ExecutorService ownedAsyncExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object ioLock = new Object();
    private final ConcurrentMap<Long, MutableServerStats> serverStats = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, BlockingQueue<ResponsePacket>> inflight = new ConcurrentHashMap<>();
    private final Set<Thread> responseRouterThreads = ConcurrentHashMap.newKeySet();
    private volatile List<ResolvedServer> servers = List.of();
    private volatile Set<Long> allowedServerIds = Set.of();
    private volatile Instant lastDnsRefresh;
    private volatile long lastDnsMinSrvTtlMs;
    private volatile long steeringFeedbackZeroCount;
    private volatile long portChanges;
    private volatile DatagramSocket socket;
    private long socketGeneration;
    private int activeSocketUsers;
    private long pendingSteeringGeneration = -1;
    // The next candidate is kept separately for IPv4 and IPv6 sockets.
    private int nextSteeringPort;
    private int nextSteeringPortIpv6;

    DefaultRateLimitlyClient(RateLimitlyClientConfig config) throws RateLimitlyException {
        this.config = Objects.requireNonNull(config, "config");
        this.apiKeyInfo = ApiKeyDecoder.decode(config.apiKey());
        ProtocolValidation.validateConfig(config, apiKeyInfo);
        this.dnsName = config.dnsNameOverrideValue() == null
            ? apiKeyInfo.defaultDnsName()
            : config.dnsNameOverrideValue();
        if (config.asyncExecutor() == null) {
            this.ownedAsyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
            this.asyncExecutor = ownedAsyncExecutor;
        } else {
            this.ownedAsyncExecutor = null;
            this.asyncExecutor = config.asyncExecutor();
        }
        try {
            this.socket = createSocket(0);
            startResponseRouter(this.socket, this.socketGeneration);
        } catch (RateLimitlyException | RuntimeException | Error error) {
            if (ownedAsyncExecutor != null) {
                ownedAsyncExecutor.shutdownNow();
            }
            throw error;
        }
    }

    @Override
    public RateLimitDecision checkRateLimit(RateLimitRequest request) throws RateLimitlyException {
        ensureOpen();
        ProtocolValidation.validateRateLimitRequest(config, apiKeyInfo, request);
        if (request.resources().isEmpty() && request.guards().isEmpty()) {
            return new RateLimitDecision(true, List.of(), List.of(), 0, false);
        }
        refreshServersIfNeeded(false);

        List<ResolvedServer> requestServers = servers;
        Set<Long> requestAllowedServerIds = allowedServerIds;
        if (requestServers.isEmpty()) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.DNS_DISCOVERY,
                "No SRV servers available for " + dnsName
            );
        }

        SocketLease lease = acquireSocketLease();
        try {
            UUID requestId = UUID.randomUUID();
            ProtocolCodec.EncodedRequest encoded = ProtocolCodec.encodeRateLimitRequest(
                config, apiKeyInfo, request, requestId
            );
            RateLimitDecision decision = sendRateRequest(
                requestId,
                encoded,
                request,
                requestServers,
                requestAllowedServerIds,
                lease.socket()
            );
            requestSteering(decision, lease.generation());
            return decision;
        } finally {
            releaseSocketLease(lease);
        }
    }

    @Override
    public CompletionStage<RateLimitDecision> checkRateLimitAsync(RateLimitRequest request) {
        try {
            ensureOpen();
            ProtocolValidation.validateRateLimitRequest(config, apiKeyInfo, request);
            return submitAsync(() -> checkRateLimit(request));
        } catch (RateLimitlyException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void reportLatency(LatencyReport report) throws RateLimitlyException {
        ensureOpen();
        ProtocolValidation.validateLatencyReport(config, apiKeyInfo, report);
        refreshServersIfNeeded(false);
        List<ResolvedServer> requestServers = servers;
        if (requestServers.isEmpty()) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.DNS_DISCOVERY,
                "No SRV servers available for " + dnsName
            );
        }
        ProtocolCodec.EncodedRequest encoded = ProtocolCodec.encodeLatencyReport(
            config,
            apiKeyInfo,
            report,
            UUID.randomUUID()
        );
        SocketLease lease = acquireSocketLease();
        try {
            int delivered = 0;
            RateLimitlyException lastFailure = null;
            for (ResolvedServer server : requestServers) {
                try {
                    sendPacket(lease.socket(), server, encoded.packet());
                    delivered++;
                } catch (RateLimitlyException e) {
                    lastFailure = e;
                    recordSendFailure(server.serverId());
                }
            }
            if (delivered == 0 && lastFailure != null) {
                throw lastFailure;
            }
        } finally {
            releaseSocketLease(lease);
        }
    }

    @Override
    public CompletionStage<Void> reportLatencyAsync(LatencyReport report) {
        try {
            ensureOpen();
            return submitAsync(() -> {
                reportLatency(report);
                return null;
            });
        } catch (RateLimitlyException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public ClientDiagnostics diagnostics() {
        List<String> endpoints = new ArrayList<>();
        for (ResolvedServer server : servers) {
            endpoints.add(server.address().getHostAddress() + ":" + server.port() + " (server_id=" + server.serverId() + ")");
        }
        Map<Long, ServerStats> snapshot = new HashMap<>();
        for (Map.Entry<Long, MutableServerStats> entry : serverStats.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().snapshot());
        }
        return new ClientDiagnostics(endpoints, lastDnsRefresh, snapshot, steeringFeedbackZeroCount, portChanges);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        DatagramSocket current;
        synchronized (ioLock) {
            current = socket;
            socket = null;
            pendingSteeringGeneration = -1;
            ioLock.notifyAll();
        }
        if (current != null) {
            current.close();
        }
        if (ownedAsyncExecutor != null) {
            ownedAsyncExecutor.shutdownNow();
        }
        for (Thread thread : List.copyOf(responseRouterThreads)) {
            thread.interrupt();
        }
        for (Thread thread : List.copyOf(responseRouterThreads)) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        inflight.clear();
    }

    private void ensureOpen() throws RateLimitlyException {
        if (closed.get()) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.CONFIGURATION,
                "Client is already closed"
            );
        }
    }

    private <T> CompletionStage<T> submitAsync(AsyncOperation<T> operation) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return operation.run();
                } catch (RateLimitlyException error) {
                    throw new RuntimeRateLimitlyException(error);
                }
            }, asyncExecutor).exceptionallyCompose(
                error -> CompletableFuture.failedFuture(unwrap(error))
            );
        } catch (RejectedExecutionException error) {
            return CompletableFuture.failedFuture(new RateLimitlyException(
                RateLimitlyException.ErrorKind.CONFIGURATION,
                "Asynchronous executor rejected the operation",
                error
            ));
        }
    }

    private void refreshServersIfNeeded(boolean force) throws RateLimitlyException {
        synchronized (ioLock) {
            if (!force && lastDnsRefresh != null) {
                long ageMs = System.currentTimeMillis() - lastDnsRefresh.toEpochMilli();
                long refreshIntervalMs = config.dnsRefreshIntervalSeconds() * 1_000L;
                if (lastDnsMinSrvTtlMs > 0 && lastDnsMinSrvTtlMs < refreshIntervalMs) {
                    refreshIntervalMs = lastDnsMinSrvTtlMs;
                }
                if (ageMs < refreshIntervalMs) {
                    return;
                }
            }
            List<ResolvedServer> resolved;
            long minSrvTtlMs = 0;
            if (config.dnsResolver() == null) {
                SrvResolver.ResolveResult result = SrvResolver.resolveWithMetadata(dnsName, config.dnsTimeoutMs());
                resolved = result.servers();
                minSrvTtlMs = result.minSrvTtlMs();
            } else {
                resolved = List.copyOf(config.dnsResolver().resolve(dnsName));
            }
            Set<Long> ids = new HashSet<>(resolved.size());
            for (ResolvedServer server : resolved) {
                ids.add(server.serverId());
            }
            servers = resolved;
            allowedServerIds = Set.copyOf(ids);
            lastDnsMinSrvTtlMs = minSrvTtlMs;
            lastDnsRefresh = Instant.now();
        }
    }

    private RateLimitDecision sendRateRequest(
        UUID requestId,
        ProtocolCodec.EncodedRequest encoded,
        RateLimitRequest request,
        List<ResolvedServer> requestServers,
        Set<Long> requestAllowedServerIds,
        DatagramSocket requestSocket
    ) throws RateLimitlyException {
        BlockingQueue<ResponsePacket> responseQueue = new LinkedBlockingQueue<>();
        BlockingQueue<ResponsePacket> previous = inflight.putIfAbsent(requestId, responseQueue);
        if (previous != null) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.TRANSPORT_IO,
                "Duplicate inflight request_id: " + requestId
            );
        }

        try {
            Set<Long> seenServerIds = new HashSet<>();
            RequestPolicy policy = config.requestPolicy();
            long oldestServerId = requestServers.stream()
                .mapToLong(ResolvedServer::serverId)
                .boxed()
                .min(this::compareServerAge)
                .orElseThrow();
            RateLimitDecision best = null;
            long requestStartNanos = System.nanoTime();

            for (int round = 0; round <= policy.replayCount(); round++) {
                sendToMissing(requestServers, encoded.packet(), seenServerIds, requestSocket);
                long roundUnits = policy.replayGap().units(round);
                long roundDeadline = addDurationNanos(System.nanoTime(), policy.unitMs(), roundUnits);
                while (System.nanoTime() < roundDeadline) {
                    ResponsePacket response = pollResponse(responseQueue, roundDeadline);
                    if (response == null) {
                        break;
                    }
                    Long peekedServerId = peekServerId(response.data());
                    if (peekedServerId == null || !requestAllowedServerIds.contains(peekedServerId)) {
                        if (peekedServerId != null) {
                            recordIdMismatch(peekedServerId);
                        }
                        continue;
                    }
                    try {
                        RateLimitDecision decision = ProtocolCodec.parseRateResponse(
                            response.data(), apiKeyInfo, request, encoded.requestId()
                        );
                        recordValidResponse(decision.serverId());
                        seenServerIds.add(decision.serverId());
                        if (best == null || compareServerAge(decision.serverId(), best.serverId()) < 0) {
                            best = decision;
                        }
                        if (decision.serverId() == oldestServerId || round > 0) {
                            completionDelivery(
                                policy,
                                requestServers,
                                encoded.packet(),
                                seenServerIds,
                                requestStartNanos,
                                requestSocket
                            );
                            return best;
                        }
                    } catch (RateLimitlyException error) {
                        recordFailure(error, peekedServerId);
                    }
                }
                if (best != null) {
                    completionDelivery(
                        policy,
                        requestServers,
                        encoded.packet(),
                        seenServerIds,
                        requestStartNanos,
                        requestSocket
                    );
                    return best;
                }
            }

            if (policy.finalReceiveUnits() > 0) {
                long finalDeadline = addDurationNanos(
                    System.nanoTime(), policy.unitMs(), policy.finalReceiveUnits()
                );
                while (System.nanoTime() < finalDeadline) {
                    ResponsePacket response = pollResponse(responseQueue, finalDeadline);
                    if (response == null) {
                        break;
                    }
                    Long peekedServerId = peekServerId(response.data());
                    if (peekedServerId == null || !requestAllowedServerIds.contains(peekedServerId)) {
                        continue;
                    }
                    try {
                        RateLimitDecision decision = ProtocolCodec.parseRateResponse(
                            response.data(), apiKeyInfo, request, encoded.requestId()
                        );
                        recordValidResponse(decision.serverId());
                        seenServerIds.add(decision.serverId());
                        completionDelivery(
                            policy,
                            requestServers,
                            encoded.packet(),
                            seenServerIds,
                            requestStartNanos,
                            requestSocket
                        );
                        return decision;
                    } catch (RateLimitlyException error) {
                        recordFailure(error, peekedServerId);
                    }
                }
            }

            incrementTimeouts(requestServers);
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.TIMEOUT,
                "No valid response received within the request-policy horizon"
            );
        } finally {
            inflight.remove(requestId, responseQueue);
        }
    }

    private ResponsePacket pollResponse(BlockingQueue<ResponsePacket> queue, long deadlineNanos)
        throws RateLimitlyException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            return null;
        }
        try {
            return queue.poll(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.TRANSPORT_IO,
                "Interrupted while waiting for UDP response",
                error
            );
        }
    }

    private long addDurationNanos(long start, long unitMs, long units) throws RateLimitlyException {
        try {
            return Math.addExact(start, Math.multiplyExact(Math.multiplyExact(unitMs, units), 1_000_000L));
        } catch (ArithmeticException error) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.CONFIGURATION,
                "request policy deadline overflows",
                error
            );
        }
    }

    /**
     * Sends to every server that has not answered yet, tolerating a per-endpoint failure.
     *
     * <p>A single unreachable endpoint (for example an IPv6 address on an IPv4-only host) must not
     * discard the replicas that were never attempted, so failures are recorded per endpoint and the
     * transport error is propagated only when no endpoint at all could be reached.
     */
    private void sendToMissing(
        List<ResolvedServer> requestServers,
        byte[] packet,
        Set<Long> seenServerIds,
        DatagramSocket requestSocket
    )
        throws RateLimitlyException {
        int attempted = 0;
        int delivered = 0;
        RateLimitlyException lastFailure = null;
        for (ResolvedServer server : requestServers) {
            if (seenServerIds.contains(server.serverId())) {
                continue;
            }
            attempted++;
            try {
                sendPacket(requestSocket, server, packet);
                delivered++;
            } catch (RateLimitlyException e) {
                lastFailure = e;
                recordSendFailure(server.serverId());
            }
        }
        if (attempted > 0 && delivered == 0) {
            throw lastFailure;
        }
    }

    private void completionDelivery(
        RequestPolicy policy,
        List<ResolvedServer> requestServers,
        byte[] packet,
        Set<Long> seenServerIds,
        long requestStartNanos,
        DatagramSocket requestSocket
    ) {
        if (!policy.completionDelivery()) {
            return;
        }
        long limit = apiKeyInfo.quotas().map(ApiKeyQuotas::dedupTtlMsMax).orElse(0xFFFF_FFFFL);
        long horizonMs = policy.horizonMs(limit);
        if (System.nanoTime() - requestStartNanos >= horizonMs * 1_000_000L) {
            return;
        }
        for (ResolvedServer server : requestServers) {
            if (!seenServerIds.contains(server.serverId())) {
                try {
                    sendPacket(requestSocket, server, packet);
                } catch (RateLimitlyException ignored) {
                    // Completion delivery is deliberately best effort.
                }
            }
        }
    }

    private int compareServerAge(long left, long right) {
        long leftStart = SrvResolver.serverStartSecondsFromId(left);
        long rightStart = SrvResolver.serverStartSecondsFromId(right);
        if (leftStart != rightStart) {
            return Long.compare(leftStart, rightStart);
        }
        return Long.compareUnsigned(left, right);
    }

    private void startResponseRouter(DatagramSocket routerSocket, long generation)
        throws RateLimitlyException {
        CountDownLatch ready = new CountDownLatch(1);
        Thread thread = new Thread(
            () -> responseRouterLoop(routerSocket, ready),
            "ratelimitly-java-response-router-" + generation
        );
        thread.setDaemon(true);
        responseRouterThreads.add(thread);
        try {
            thread.start();
            if (!ready.await(1, TimeUnit.SECONDS)) {
                routerSocket.close();
                thread.interrupt();
                throw new RateLimitlyException(
                    RateLimitlyException.ErrorKind.TRANSPORT_IO,
                    "UDP response router did not start"
                );
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            routerSocket.close();
            thread.interrupt();
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.TRANSPORT_IO,
                "Interrupted while starting UDP response router",
                error
            );
        } catch (RuntimeException | Error error) {
            responseRouterThreads.remove(thread);
            routerSocket.close();
            throw error;
        }
    }

    private void responseRouterLoop(DatagramSocket routerSocket, CountDownLatch ready) {
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        ready.countDown();

        try {
            while (!closed.get() && !routerSocket.isClosed()) {
                try {
                    packet.setLength(buffer.length);
                    routerSocket.receive(packet);
                    UUID requestId = peekRequestId(packet.getData(), packet.getOffset(), packet.getLength());
                    if (requestId == null) {
                        continue;
                    }
                    BlockingQueue<ResponsePacket> responseQueue = inflight.get(requestId);
                    if (responseQueue == null) {
                        continue;
                    }
                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                    InetSocketAddress remoteAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());
                    responseQueue.offer(new ResponsePacket(data, remoteAddress));
                } catch (SocketTimeoutException ignored) {
                } catch (Exception error) {
                    if (closed.get() || routerSocket.isClosed()) {
                        break;
                    }
                }
            }
        } finally {
            responseRouterThreads.remove(Thread.currentThread());
        }
    }

    private void sendPacket(DatagramSocket requestSocket, ResolvedServer server, byte[] packet)
        throws RateLimitlyException {
        if (requestSocket == null || requestSocket.isClosed()) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.TRANSPORT_IO,
                "UDP socket is not available"
            );
        }
        try {
            DatagramPacket datagram = new DatagramPacket(
                packet,
                packet.length,
                new InetSocketAddress(server.address(), server.port())
            );
            requestSocket.send(datagram);
        } catch (Exception e) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.TRANSPORT_IO,
                "Failed to send UDP packet to " + server.address().getHostAddress() + ":" + server.port(),
                e
            );
        }
    }

    private SocketLease acquireSocketLease() throws RateLimitlyException {
        synchronized (ioLock) {
            while (true) {
                ensureOpen();
                if (pendingSteeringGeneration == socketGeneration) {
                    if (activeSocketUsers == 0) {
                        performPendingSteeringLocked();
                        continue;
                    }
                    try {
                        ioLock.wait();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new RateLimitlyException(
                            RateLimitlyException.ErrorKind.TRANSPORT_IO,
                            "Interrupted while waiting for UDP steering rebind",
                            error
                        );
                    }
                    continue;
                }

                DatagramSocket current = socket;
                if (current == null || current.isClosed()) {
                    throw new RateLimitlyException(
                        RateLimitlyException.ErrorKind.TRANSPORT_IO,
                        "UDP socket is not available"
                    );
                }
                activeSocketUsers++;
                return new SocketLease(current, socketGeneration);
            }
        }
    }

    private void releaseSocketLease(SocketLease lease) {
        synchronized (ioLock) {
            if (activeSocketUsers <= 0) {
                throw new IllegalStateException("UDP socket lease count underflow");
            }
            activeSocketUsers--;
            if (activeSocketUsers == 0 && pendingSteeringGeneration == socketGeneration) {
                performPendingSteeringLocked();
            }
            ioLock.notifyAll();
        }
    }

    private void requestSteering(RateLimitDecision decision, long requestGeneration) {
        if (decision.steeringFeedback() || config.ignoreSteeringFeedback()) {
            return;
        }

        synchronized (ioLock) {
            steeringFeedbackZeroCount++;
            if (requestGeneration == socketGeneration) {
                pendingSteeringGeneration = requestGeneration;
            }
        }
    }

    private void performPendingSteeringLocked() {
        if (closed.get()) {
            pendingSteeringGeneration = -1;
            return;
        }

        DatagramSocket oldSocket = socket;
        if (oldSocket == null || oldSocket.isClosed() || pendingSteeringGeneration != socketGeneration) {
            pendingSteeringGeneration = -1;
            return;
        }

        DatagramSocket replacement = null;
        try {
            replacement = createSteeringSocket(oldSocket);
            long replacementGeneration = socketGeneration + 1;
            startResponseRouter(replacement, replacementGeneration);
            socket = replacement;
            socketGeneration = replacementGeneration;
            pendingSteeringGeneration = -1;
            oldSocket.close();
            portChanges++;
        } catch (Exception error) {
            if (replacement != null) {
                replacement.close();
            }
            // Steering is an optimization. Exhaustion or a local bind failure
            // must not turn an already selected grant or rejection into failure.
            pendingSteeringGeneration = -1;
        }
    }

    private DatagramSocket createSteeringSocket(DatagramSocket oldSocket) throws SocketException {
        InetAddress bindAddress = oldSocket.getLocalAddress();
        boolean ipv6 = bindAddress instanceof Inet6Address;
        int candidate = ipv6 ? nextSteeringPortIpv6 : nextSteeringPort;
        if (candidate < STEERING_PORT_BASE || candidate > 65_535) {
            candidate = firstSteeringCandidate(oldSocket.getLocalPort());
        }

        for (int attempt = 0; attempt < STEERING_PORT_SPAN; attempt++) {
            if (ipv6) {
                nextSteeringPortIpv6 = incrementSteeringPort(candidate);
            } else {
                nextSteeringPort = incrementSteeringPort(candidate);
            }
            try {
                return bindSocket(bindAddress, candidate);
            } catch (BindException occupied) {
                candidate = incrementSteeringPort(candidate);
            }
        }
        throw new BindException("No UDP source port is available in the steering range");
    }

    private static int firstSteeringCandidate(int currentPort) {
        if (currentPort >= STEERING_PORT_BASE && currentPort <= 65_535) {
            return incrementSteeringPort(currentPort);
        }
        return STEERING_PORT_BASE;
    }

    static int incrementSteeringPort(int port) {
        return port >= 65_535 ? STEERING_PORT_BASE : port + 1;
    }

    private void recordValidResponse(long serverId) {
        serverStats.computeIfAbsent(serverId, ignored -> new MutableServerStats()).recordValid();
    }

    private void incrementTimeouts(List<ResolvedServer> requestServers) {
        for (ResolvedServer server : requestServers) {
            serverStats.computeIfAbsent(server.serverId(), ignored -> new MutableServerStats()).recordTimeout();
        }
    }

    private void recordFailure(RateLimitlyException exception, Long serverId) {
        if (serverId == null) {
            return;
        }
        MutableServerStats stats = serverStats.computeIfAbsent(serverId, ignored -> new MutableServerStats());
        switch (exception.kind()) {
            case AUTHENTICATION -> stats.recordAuthenticationFailure();
            case PROTOCOL -> stats.recordDecryptFailure();
            default -> {
            }
        }
    }

    private void recordSendFailure(long serverId) {
        serverStats.computeIfAbsent(serverId, ignored -> new MutableServerStats()).recordSendFailure();
    }

    private void recordIdMismatch(long serverId) {
        serverStats.computeIfAbsent(serverId, ignored -> new MutableServerStats()).recordIdMismatch();
    }

    private Long peekServerId(byte[] data) {
        if (data.length < 12) {
            return null;
        }
        int tlvType = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
        int tlvSize = ((data[3] & 0xFF) << 8) | (data[2] & 0xFF);
        if (tlvType != 0x4C52 || tlvSize < 40 || data.length < tlvSize) {
            return null;
        }
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value |= ((long) data[4 + i] & 0xFF) << (8 * i);
        }
        return value;
    }

    private UUID peekRequestId(byte[] data, int offset, int length) {
        if (length < 28) {
            return null;
        }
        int tlvType = ((data[offset + 1] & 0xFF) << 8) | (data[offset] & 0xFF);
        int tlvSize = ((data[offset + 3] & 0xFF) << 8) | (data[offset + 2] & 0xFF);
        if (tlvType != 0x4C52 || tlvSize < 40 || length < tlvSize) {
            return null;
        }
        ByteBuffer requestId = ByteBuffer.wrap(data, offset + 12, 16);
        return new UUID(requestId.getLong(), requestId.getLong());
    }

    private DatagramSocket createSocket(int port) throws RateLimitlyException {
        try {
            return bindSocket(null, port);
        } catch (SocketException e) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.TRANSPORT_IO,
                "Failed to create UDP socket",
                e
            );
        }
    }

    private DatagramSocket bindSocket(InetAddress bindAddress, int port) throws SocketException {
        DatagramSocket datagramSocket = new DatagramSocket(null);
        try {
            // Set before bind so Windows cannot share a supposedly occupied
            // steering candidate even when the JVM's exclusive-bind property
            // has been disabled.
            datagramSocket.setReuseAddress(false);
            InetSocketAddress localAddress = bindAddress == null
                ? new InetSocketAddress(port)
                : new InetSocketAddress(bindAddress, port);
            datagramSocket.bind(localAddress);
            datagramSocket.setSoTimeout(ROUTER_SOCKET_TIMEOUT_MS);
            return datagramSocket;
        } catch (SocketException | RuntimeException error) {
            datagramSocket.close();
            throw error;
        }
    }

    private RateLimitlyException unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current instanceof RuntimeException) {
            if (current instanceof RuntimeRateLimitlyException wrapped) {
                return wrapped.exception;
            }
            current = current.getCause();
        }
        if (current instanceof RuntimeRateLimitlyException wrapped) {
            return wrapped.exception;
        }
        return new RateLimitlyException(
            RateLimitlyException.ErrorKind.UNSUPPORTED,
            current == null ? "Unknown async error" : current.getMessage(),
            current
        );
    }

    private record SocketLease(DatagramSocket socket, long generation) {
    }

    private record ResponsePacket(byte[] data, InetSocketAddress remoteAddress) {
    }

    @FunctionalInterface
    private interface AsyncOperation<T> {
        T run() throws RateLimitlyException;
    }

    private static final class MutableServerStats {
        private Instant firstSeen;
        private Instant lastSeen;
        private long validResponses;
        private long timeouts;
        private long decryptFailures;
        private long authenticationFailures;
        private long idMismatchCount;
        private long sendFailures;

        synchronized void recordValid() {
            Instant now = Instant.now();
            if (firstSeen == null) {
                firstSeen = now;
            }
            lastSeen = now;
            validResponses++;
        }

        synchronized void recordTimeout() {
            timeouts++;
        }

        synchronized void recordDecryptFailure() {
            decryptFailures++;
        }

        synchronized void recordAuthenticationFailure() {
            authenticationFailures++;
        }

        synchronized void recordIdMismatch() {
            idMismatchCount++;
        }

        synchronized void recordSendFailure() {
            sendFailures++;
        }

        synchronized ServerStats snapshot() {
            return new ServerStats(
                firstSeen,
                lastSeen,
                validResponses,
                timeouts,
                decryptFailures,
                authenticationFailures,
                idMismatchCount,
                sendFailures
            );
        }
    }

    private static final class RuntimeRateLimitlyException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final RateLimitlyException exception;

        RuntimeRateLimitlyException(RateLimitlyException exception) {
            super(exception);
            this.exception = exception;
        }
    }
}
