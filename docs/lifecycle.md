# Lifecycle and asynchronous calls

## Ownership

`RateLimitlyClients.create(config)` creates a reusable client that owns:

- a UDP socket;
- daemon response-router threads used across source-port transitions;
- cached DNS membership;
- in-flight request routing state;
- per-server diagnostics;
- a virtual-thread-per-task executor for asynchronous calls, unless the caller
  supplies one explicitly.

Create the client outside the application request path and reuse it. Creating a
new client for every resource request discards discovery state, opens new
sockets, and creates unnecessary threads.

The client implements `AutoCloseable`:

```java
try (RateLimitlyClient client = RateLimitlyClients.create(config)) {
    runApplication(client);
}
```

`close()` is idempotent. It closes sockets, stops router threads, shuts down the
client-owned asynchronous executor, and discards in-flight routing state. It
never shuts down an executor supplied through the configuration builder. Calls
made after close fail. Applications should stop submitting work before closing
the shared client.

## Blocking calls

`checkRateLimit()` blocks until it selects a valid response or the configured
request-policy horizon fails. `reportLatency()` performs discovery if needed,
attempts the datagram sends, and returns without waiting for a response.

Do not call a blocking operation on an event-loop thread. Framework adapters
must choose an execution model appropriate for their host environment.

## Asynchronous calls

`checkRateLimitAsync()` and `reportLatencyAsync()` return `CompletionStage`
values. A successful stage contains the same result as the blocking method. A
failed stage completes exceptionally with `RateLimitlyException`; blocking
retrieval methods such as `get()` and `join()` wrap that exception according to
the normal `CompletableFuture` contract.

By default, the client runs each asynchronous operation on a Java 21 virtual
thread owned by the client. It never submits blocking DNS or UDP work to
`ForkJoinPool.commonPool()`.

Applications may call `.asyncExecutor(executor)` on the configuration builder.
The supplied executor is caller-owned: the client uses it but never shuts it
down. An executor that runs tasks inline can make an asynchronous call block
its caller; that behavior follows from the supplied executor. If task
submission is rejected, the returned stage fails with a `RateLimitlyException`
whose kind is `CONFIGURATION`.

## Concurrency and steering

One `RateLimitlyClient` supports concurrent calls to its request, report, and
diagnostics methods. Applications should stop submitting operations and let
them finish before calling `close()`; a concurrent close terminates transport
resources and may make in-flight operations fail without a usable decision.

The client routes concurrent logical requests by request ID. Every resource
request and latency-report send holds a lease on the UDP socket generation it
uses. When a valid response requests a source-port change, the client stops new
operations from joining that generation and waits for every existing lease to
drain.

The replacement port is selected by a per-address-family cursor. It advances
by exactly one through ports 49,152 through 65,535, wraps only after 65,535,
and skips occupied candidates through the complete range. Steering never falls
back to port zero. Sockets bind with address reuse disabled so collision
handling remains exclusive on Windows as well as Unix-like systems.

After binding a replacement, the client starts its receive path before making
the socket current and closing the drained old socket. Concurrent requests
therefore keep receiving on the socket from which they were sent, while the
next operation uses the replacement. Multiple steering indications for one
draining generation are coalesced into one transition.

Steering is an optimization. If no dynamic-range port can be bound, the client
keeps the existing socket and returns the already selected grant or rejection;
a local steering failure cannot turn that decision into a request failure.

## Cancellation

Cancelling the returned future cancels observation of its result only. It does
not retract a logical request that may already have been transmitted, undo
resource consumption, or suppress best-effort completion delivery. Use
`close()` to terminate the client's transport when shutting down the owning
application component.
