package com.ratelimitly;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class PublicApiContractTest {
    private static final String SAMPLE_API_KEY =
        "rl-none1qyyqwps9qspsyq2sk8e0sfdp3ys";

    private PublicApiContractTest() {
    }

    @Test
    void apiKeyOnlyConstructionDerivesDiscoveryAndAllowsOverride() throws Exception {
        runSocketAware(PublicApiContractTest::testApiKeyOnlyConstructionAndDiscovery);
    }

    @Test
    void emptyRequestReturnsLocalIdentityDecision() throws Exception {
        runSocketAware(PublicApiContractTest::testEmptyRequestReturnsLocalSuccess);
    }

    @Test
    void defaultAsyncExecutorUsesOwnedVirtualThreadsAndCloses() throws Exception {
        runSocketAware(PublicApiContractTest::testDefaultAsyncExecutor);
    }

    @Test
    void customAsyncExecutorIsUsedAndRemainsCallerOwned() throws Exception {
        runSocketAware(PublicApiContractTest::testCustomAsyncExecutorOwnership);
    }

    @Test
    void rejectedAsyncSubmissionReturnsStructuredFailure() throws Exception {
        runSocketAware(PublicApiContractTest::testRejectedAsyncExecution);
    }

    @Test
    void cancellationDoesNotRetractSubmittedWork() throws Exception {
        runSocketAware(PublicApiContractTest::testCancellationDoesNotRetractSubmittedWork);
    }

    @Test
    void collaboratorRenderingCannotLeakApiKey() {
        testCollaboratorRenderingCannotLeakApiKey();
    }

    @Test
    void obsoletePublicApiIsAbsent() {
        testObsoleteApiIsAbsent();
    }

    @Test
    void moduleExportsOnlyPublicPackage() {
        testModuleBoundary();
    }

    private static void runSocketAware(ThrowingRunnable test) throws Exception {
        try {
            test.run();
        } catch (Exception error) {
            if (ClientTestScenarios.isSocketPermissionError(error)) {
                Assumptions.abort("local UDP sockets are not permitted in this environment");
            }
            throw error;
        }
    }

    private static void testApiKeyOnlyConstructionAndDiscovery() throws Exception {
        ApiKeyInfo apiKey = ApiKeyDecoder.decode(SAMPLE_API_KEY);
        String expectedDnsName = "c-" + Long.toUnsignedString(apiKey.keyId()) + ".p0.ratelimitly.com";
        AtomicReference<String> discoveredName = new AtomicReference<>();

        RateLimitlyClientConfig config = RateLimitlyClientConfig.builder(SAMPLE_API_KEY)
            .dnsResolver(name -> {
                discoveredName.set(name);
                return List.of();
            })
            .build();

        check(!config.toString().contains(SAMPLE_API_KEY), "configuration rendering exposed the API key");
        try (RateLimitlyClient client = RateLimitlyClients.create(config)) {
            expectBlockingFailure(() -> client.checkRateLimit(oneTokenRequest()), RateLimitlyException.ErrorKind.DNS_DISCOVERY);
        }
        check(expectedDnsName.equals(discoveredName.get()), "production DNS was not derived from the API-key ID");

        AtomicReference<String> overrideName = new AtomicReference<>();
        RateLimitlyClientConfig overrideConfig = RateLimitlyClientConfig.builder(SAMPLE_API_KEY)
            .dnsName("custom.example")
            .dnsResolver(name -> {
                overrideName.set(name);
                return List.of();
            })
            .build();
        try (RateLimitlyClient client = RateLimitlyClients.create(overrideConfig)) {
            expectBlockingFailure(() -> client.checkRateLimit(oneTokenRequest()), RateLimitlyException.ErrorKind.DNS_DISCOVERY);
        }
        check("custom.example".equals(overrideName.get()), "explicit DNS override was not used");
    }

    private static void testEmptyRequestReturnsLocalSuccess() throws Exception {
        AtomicBoolean discoveryCalled = new AtomicBoolean();
        RateLimitlyClientConfig config = RateLimitlyClientConfig.builder(SAMPLE_API_KEY)
            .dnsResolver(name -> {
                discoveryCalled.set(true);
                return List.of();
            })
            .build();

        try (RateLimitlyClient client = RateLimitlyClients.create(config)) {
            RateLimitDecision decision = client.checkRateLimit(
                new RateLimitRequest(List.of(), List.of(), null)
            );
            check(decision.success(), "empty request did not return the identity grant");
            check(decision.guardDecisions().isEmpty(), "empty request gained guard decisions");
            check(decision.resourceDecisions().isEmpty(), "empty request gained resource decisions");
            check(decision.serverId() == 0, "empty request should not select a server");
        }
        check(!discoveryCalled.get(), "empty request performed DNS discovery");
    }

    private static void testDefaultAsyncExecutor() throws Exception {
        AtomicReference<Thread> executionThread = new AtomicReference<>();
        RateLimitlyClientConfig config = RateLimitlyClientConfig.builder(SAMPLE_API_KEY)
            .dnsResolver(name -> {
                executionThread.set(Thread.currentThread());
                return List.of();
            })
            .build();

        RateLimitlyClient client = RateLimitlyClients.create(config);
        ExecutorService ownedExecutor = ownedExecutor(client);
        try {
            expectStageFailure(
                client.checkRateLimitAsync(oneTokenRequest()),
                RateLimitlyException.ErrorKind.DNS_DISCOVERY
            );
            Thread thread = executionThread.get();
            check(thread != null, "default asynchronous operation did not execute");
            check(thread.isVirtual(), "default asynchronous operation did not use a virtual thread");
            check(!thread.getName().contains("ForkJoinPool"), "default asynchronous operation used the common pool");
        } finally {
            client.close();
        }
        check(ownedExecutor.isShutdown(), "closing the client did not shut down its owned executor");
        expectBlockingFailure(
            () -> client.checkRateLimit(oneTokenRequest()),
            RateLimitlyException.ErrorKind.CONFIGURATION
        );
        expectStageFailure(
            client.checkRateLimitAsync(oneTokenRequest()),
            RateLimitlyException.ErrorKind.CONFIGURATION
        );
    }

    private static void testCustomAsyncExecutorOwnership() throws Exception {
        AtomicReference<Thread> executionThread = new AtomicReference<>();
        ExecutorService customExecutor = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "ratelimitly-test-executor")
        );
        RateLimitlyClientConfig config = RateLimitlyClientConfig.builder(SAMPLE_API_KEY)
            .asyncExecutor(customExecutor)
            .dnsResolver(name -> {
                executionThread.set(Thread.currentThread());
                return List.of();
            })
            .build();

        try {
            try (RateLimitlyClient client = RateLimitlyClients.create(config)) {
                expectStageFailure(
                    client.checkRateLimitAsync(oneTokenRequest()),
                    RateLimitlyException.ErrorKind.DNS_DISCOVERY
                );
                check(
                    executionThread.get() != null
                        && "ratelimitly-test-executor".equals(executionThread.get().getName()),
                    "asynchronous operation did not use the supplied executor"
                );
            }
            check(!customExecutor.isShutdown(), "client closed the caller-owned executor");
        } finally {
            customExecutor.shutdownNow();
        }
    }

    private static void testRejectedAsyncExecution() throws Exception {
        RateLimitlyClientConfig config = RateLimitlyClientConfig.builder(SAMPLE_API_KEY)
            .asyncExecutor(task -> {
                throw new RejectedExecutionException("expected rejection");
            })
            .build();
        try (RateLimitlyClient client = RateLimitlyClients.create(config)) {
            expectStageFailure(
                client.checkRateLimitAsync(new RateLimitRequest(List.of(), List.of(), null)),
                RateLimitlyException.ErrorKind.CONFIGURATION
            );
        }
    }

    private static void testCancellationDoesNotRetractSubmittedWork() throws Exception {
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        AtomicBoolean operationRan = new AtomicBoolean();
        Executor holdingExecutor = task -> {
            if (!submitted.compareAndSet(null, task)) {
                throw new RejectedExecutionException("only one task expected");
            }
        };
        RateLimitlyClientConfig config = RateLimitlyClientConfig.builder(SAMPLE_API_KEY)
            .asyncExecutor(holdingExecutor)
            .dnsResolver(name -> {
                operationRan.set(true);
                return List.of();
            })
            .build();

        try (RateLimitlyClient client = RateLimitlyClients.create(config)) {
            var result = client.checkRateLimitAsync(oneTokenRequest()).toCompletableFuture();
            check(result.cancel(false), "asynchronous result was not cancellable");
            check(submitted.get() != null, "operation was not submitted before cancellation");
            submitted.get().run();
            check(operationRan.get(), "cancellation retracted work already submitted to the executor");
        }
    }

    private static void testCollaboratorRenderingCannotLeakApiKey() {
        DnsResolver resolver = new DnsResolver() {
            @Override
            public List<ResolvedServer> resolve(String dnsName) {
                return List.of();
            }

            @Override
            public String toString() {
                return SAMPLE_API_KEY;
            }
        };
        Executor executor = new Executor() {
            @Override
            public void execute(Runnable task) {
                // Rendering this configuration never submits work.
            }

            @Override
            public String toString() {
                return SAMPLE_API_KEY;
            }
        };
        RateLimitlyClientConfig config = RateLimitlyClientConfig.builder(SAMPLE_API_KEY)
            .dnsResolver(resolver)
            .asyncExecutor(executor)
            .build();
        check(!config.toString().contains(SAMPLE_API_KEY), "collaborator rendering exposed the API key");
    }

    private static void testObsoleteApiIsAbsent() {
        for (String obsolete : List.of(
            "com.ratelimitly.DecodedTenantCredential",
            "com.ratelimitly.TenantCredentialDecoder",
            "com.ratelimitly.TenantQuotas"
        )) {
            try {
                Class.forName(obsolete);
                throw new AssertionError("obsolete public type remains: " + obsolete);
            } catch (ClassNotFoundException expected) {
                // The 3.0 API intentionally has no compatibility aliases.
            }
        }

        var publicMethods = Arrays.asList(RateLimitlyClientConfig.class.getMethods());
        check(!RateLimitlyClientConfig.class.isRecord(), "configuration remains a public record");
        check(
            publicMethods.stream().noneMatch(method -> method.getName().equals("debug")
                || method.getName().equals("apiKey")
                || method.getName().equals("bech32Credential")
                || method.getName().equals("decodeCredential")),
            "obsolete configuration API remains"
        );
        var builders = publicMethods.stream()
            .filter(method -> method.getName().equals("builder"))
            .toList();
        check(builders.size() == 1, "configuration exposes more than one public builder entry point");
        check(
            Arrays.equals(builders.getFirst().getParameterTypes(), new Class<?>[] {String.class}),
            "configuration builder is not API-key-only"
        );
    }

    private static void testModuleBoundary() {
        Module module = RateLimitlyClient.class.getModule();
        if (module.isNamed()) {
            check("com.ratelimitly.client".equals(module.getName()), "unexpected Java module name");
            check(module.isExported("com.ratelimitly"), "public client package is not exported");
            check(!module.isExported("com.ratelimitly.internal"), "internal implementation package is exported");
        }
    }

    private static RateLimitRequest oneTokenRequest() {
        return new RateLimitRequest(
            List.of(new ResourceRequest("public-api-test", 1_000, 100, 1)),
            List.of(),
            null
        );
    }

    private static ExecutorService ownedExecutor(RateLimitlyClient client) throws Exception {
        Field field = DefaultRateLimitlyClient.class.getDeclaredField("ownedAsyncExecutor");
        field.setAccessible(true);
        return (ExecutorService) field.get(client);
    }

    private static void expectBlockingFailure(
        ThrowingRunnable operation,
        RateLimitlyException.ErrorKind expectedKind
    ) throws Exception {
        try {
            operation.run();
            throw new AssertionError("expected operation to fail with " + expectedKind);
        } catch (RateLimitlyException error) {
            check(error.kind() == expectedKind, "unexpected failure kind " + error.kind());
        }
    }

    private static void expectStageFailure(
        CompletionStage<?> stage,
        RateLimitlyException.ErrorKind expectedKind
    ) throws Exception {
        try {
            stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
            throw new AssertionError("expected asynchronous operation to fail with " + expectedKind);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            check(cause instanceof RateLimitlyException, "async failure was not RateLimitlyException: " + cause);
            check(
                ((RateLimitlyException) cause).kind() == expectedKind,
                "unexpected asynchronous failure kind " + ((RateLimitlyException) cause).kind()
            );
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
