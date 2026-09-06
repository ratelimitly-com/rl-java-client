# Configuration

## Create one client

Create and reuse a client for one API key:

```java
String apiKey = System.getenv("RATELIMITLY_AUTH_KEY");

RateLimitlyClientConfig config = RateLimitlyClientConfig
    .builder(apiKey)
    .build();

RateLimitlyClient client = RateLimitlyClients.create(config);
```

The client validates the API key when it is created and derives the normal
production discovery domain from its unsigned key ID:

```text
c-<key-id>.p0.ratelimitly.com
```

It resolves `_ratelimitly._udp.<derived-domain>`. Applications normally do not
configure DNS separately. An explicit name or custom resolver remains
available for tests, private environments, and advanced host integration.

Do not place the API key directly in source. See [Security](../SECURITY.md) for
credential-handling requirements.

## API keys and authentication

The encoded Bech32 API key supplies:

- format version;
- API-key ID;
- authentication method and secret material;
- limits enforced by the client or service.

`ApiKeyDecoder.decode(apiKey)` returns an `ApiKeyInfo` with the authentication
mode, format version, unsigned 64-bit ID (stored in a Java `long`), defensive
secret copies, and optional `ApiKeyQuotas`. `ApiKeyInfo.defaultDnsName()`
returns the production discovery domain. Its rendering never contains the
encoded key or secret bytes.

The client accepts current format-version 1 request credentials. Old,
malformed, and unsupported-version request credentials fail during client
construction. A management credential is not a request credential and is
rejected before an operation is transmitted.

Authentication modes are:

| Key family | Intended use |
| --- | --- |
| `rl-aes...` | AES-256-GCM for normal deployments, including untrusted networks. |
| `rl-cookie...` | Trusted private networks whose threat model accepts an unencrypted reusable cookie. |
| `rl-none...` | Isolated development and tests only. |

## API-key limits

Format-version 1 carries these limits:

| Limit | Client behavior |
| --- | --- |
| `dedupTtlMsMax` | Rejects a request policy whose derived horizon exceeds the limit. |
| `rateWindowSizeMsMax` | Rejects a resource whose window exceeds the limit before transmission. |
| `rateBucketsMax` | Decoded for inspection; distinct-bucket capacity is enforced remotely. |
| `latencyServicesMax` | Decoded for inspection; distinct-tracker capacity is enforced remotely. |
| `latencyBufferSizeMax` | Decoded for inspection; tracker storage capacity is enforced remotely. |
| `metricsLabelsMax` | Decoded for inspection; distinct-label capacity is enforced remotely. |

The encoded limits are safeguards, not default bucket or tracker definitions.
Applications still provide each resource and latency-tracker definition.

## Builder settings

| Setting | Default | Meaning |
| --- | --- | --- |
| `dnsTimeoutMs` | 1,000 ms | Maximum duration of a DNS lookup. |
| `dnsRefreshIntervalSeconds` | 300 s | Maximum interval before activity refreshes membership; a smaller SRV TTL wins. |
| `requestPolicy` | Standard policy | Fan-out, replay, selection, completion delivery, and deduplication horizon. |
| `dnsResolver` | dnsjava resolver | Optional discovery implementation, primarily for tests or host integration. |
| `steeringFeedback` | `false` | Source-port steering indication sent in requests. |
| `ignoreSteeringFeedback` | `false` | Ignore a server request to change the local UDP source port. |
| `asyncExecutor` | Client-owned virtual-thread executor | Optional caller-owned executor for asynchronous operations. |

The complete HA policy is documented in [Request policy](request-policy.md).

## Custom discovery

Implement `DnsResolver` when the host application owns discovery or tests need
deterministic membership:

```java
DnsResolver resolver = ignoredName -> List.of(
    new ResolvedServer(host, address, port, serverId)
);

RateLimitlyClientConfig config = RateLimitlyClientConfig
    .builder(apiKey)
    .dnsName("test.invalid")
    .dnsResolver(resolver)
    .build();
```

Each `ResolvedServer` must carry the trusted server ID associated with that
endpoint. The client discards responses whose IDs are not in the immutable
membership snapshot for the logical request.

## Metrics labels

`RateLimitRequest.metricsLabel()` is optional. When present, the client encodes
it as UTF-8 and includes it in packet-size validation. Labels are for grouping
request metrics; they do not change bucket or latency-tracker identity.
