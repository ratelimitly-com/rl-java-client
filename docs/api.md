# Public Java API

The library exposes two independent operations through `RateLimitlyClient`:

```java
RateLimitDecision checkRateLimit(RateLimitRequest request)
    throws RateLimitlyException;

void reportLatency(LatencyReport report)
    throws RateLimitlyException;
```

Each operation also has an asynchronous `CompletionStage` form. Client
construction and ownership are covered in [Lifecycle](lifecycle.md).

## Module name and API boundary

The JAR declares the stable automatic module name `com.ratelimitly.client`, so
named applications can require it on the Java module path. The supported API
is the `com.ratelimitly` package. Classes in `com.ratelimitly.internal` remain
implementation details even though an automatic module cannot enforce that
package boundary; applications must not compile against them.

## Resource requests

A `RateLimitRequest` contains:

- zero or more `ResourceRequest` resource consumptions;
- zero or more `LatencyGuard` conditions;
- an optional metrics label.

The following request shapes are valid:

| Resources | Guards | Meaning |
| --- | --- | --- |
| none | none | Identity request; succeeds locally without DNS or network activity. |
| one or more | none | Consume the requested quantities if every resource has capacity. |
| none | one or more | Test the latency conditions without consuming a rate resource. |
| one or more | one or more | Evaluate all consumptions and guards as one atomic decision. |

For every non-empty request, `RateLimitDecision.success()` is `true` for a
grant and `false` for a rejection. On a grant, every requested quantity is
consumed. On a rejection, none is consumed.

`guardDecisions()` and `resourceDecisions()` describe the conditions returned
by the selected server. `serverId()` identifies that server, and
`steeringFeedback()` carries its source-port steering indication.

An exception is neither a grant nor a rejection. A failure may occur before
transmission, but it may also occur after one or more copies of a request were
sent. Applications must choose their failure policy with that ambiguity in
mind.

## Latency reports

A `LatencyReport` contains one or more `ServiceLatencyReport` values. Each
value contributes one observed latency to a named latency tracker. Reports do
not request or consume rate resources and do not produce admission decisions.

The synchronous method attempts to send the report to every currently
discovered server. It returns when at least one datagram was sent successfully;
it does not wait for a response. If no discovered endpoint accepts a send, it
throws the last transport failure.

The following fields define one tracker and must agree between its guards and
reports:

- `latencyTrackerName`;
- `ttlMs`;
- `maxSamples`;
- `minSampleThreshold`.

`thresholdMs` belongs only to a guard. `observedLatencyMs` belongs only to a
report. Neither is part of the tracker identity.

`minSampleThreshold` controls warm-up: tracked latency starts controlling
admission once sufficient samples have been observed. Before that, the tracked
latency is zero, so a positive latency threshold can pass during warm-up.

## Content-defined state identities

Callers name rate buckets and latency trackers. The client derives their wire
identities from the name and complete stored-state definition:

- bucket: `bucketName`, `windowSizeMs`, and `rateLimit`;
- latency tracker: `latencyTrackerName`, `ttlMs`, `maxSamples`, and
  `minSampleThreshold`.

Changing an identity-defining setting creates a different bucket or tracker.
This lets independent client implementations address the same state without
sharing generated identifiers. `CanonicalIds` exposes the same derivation for
applications that need the exact 16-byte ID.

## Errors

`RateLimitlyException.kind()` provides a structured category:

| Kind | Meaning |
| --- | --- |
| `DNS_DISCOVERY` | No usable server membership could be resolved. |
| `TIMEOUT` | No valid response arrived inside the request-policy horizon. |
| `PROTOCOL` | A packet or protocol value was invalid. |
| `AUTHENTICATION` | Local authentication or cryptographic processing failed. |
| `CONFIGURATION` | Configuration, policy, API-key, executor, or API-key quota validation failed. |
| `TRANSPORT_IO` | Socket creation, send, receive, or interruption failed. |
| `NO_VALID_RESPONSE` | Responses arrived but none was usable. |
| `REQUEST_TOO_LARGE` | The encoded request would exceed the packet-size target. |
| `UNSUPPORTED` | The requested behavior is not implemented by this client version. |

Do not infer grant or rejection from an error message. Switch on `kind()` and
apply an explicit application failure policy.

## Packet-size validation

The client rejects a resource request or latency report whose encoded packet
would exceed 1,200 bytes. It does not silently truncate resources, guards,
reports, or metrics labels.

## Diagnostics

`client.diagnostics()` returns a read-only snapshot containing:

- discovered endpoints and server IDs;
- the last DNS refresh time;
- per-server valid-response, timeout, authentication/decryption, identity
  mismatch, and send-failure counters;
- steering feedback and local-port change counters.

Diagnostics are operational observations. They do not alter the selected
admission decision and must never contain the encoded API key or raw secret.

## Further reading

- [Configuration](configuration.md)
- [Resource-request HA policy](request-policy.md)
- [Lifecycle and asynchronous calls](lifecycle.md)
- [Security](../SECURITY.md)
