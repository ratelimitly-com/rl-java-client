# Resource-request HA policy

An API key may resolve to one or more RateLimitly servers. Membership and
delivery do not change the logical meaning of a resource request: the selected
response still represents one atomic grant or rejection.

`RequestPolicy` controls fan-out, response selection, replay timing, optional
completion delivery, and the deduplication TTL carried by a non-empty resource
request. Empty requests return local success and use none of this machinery.

## Parameters

| Field | Symbol | Meaning |
| --- | --- | --- |
| `unitMs` | `U` | Base duration unit in milliseconds. |
| `replayCount` | `N` | Number of replay rounds after the initial transmission. |
| `replayGap` | `B(k)` | Duration of transmission round `k`, measured in units of `U`. |
| `finalReceiveUnits` | `F` | Receive-only duration after all transmission rounds. Zero removes this phase. |
| `completionDelivery` | — | Before returning a grant or rejection, best-effort resend to servers that have not returned a valid response. |

`replayGap` may be fixed, linear, or exponential. Linear and exponential
schedules grow until `maxUnits`; a fixed schedule uses the same value for every
round.

## Horizon and deduplication TTL

There are `N + 1` transmission rounds: round zero is the initial fan-out and
rounds `1..N` are replays. The complete horizon is:

```text
H = U * (sum(B(k), k = 0..N) + F)
```

The same `H` is encoded as the request's deduplication TTL. Every transmission
of one logical request carries the same request identity. A server that has
already processed it within the deduplication window can return the remembered
response instead of consuming resources again.

Client construction fails if the schedule is invalid, arithmetic overflows,
or `H` exceeds the API key's `dedupTtlMsMax` limit.

## Selection algorithm

1. Freeze the current DNS membership for this logical request.
2. Send the request to every member in round zero.
3. Prefer the response from the oldest server. Its valid response completes
   immediately.
4. Remember the best valid response from a younger server. If the oldest does
   not answer before the end of round zero, return the oldest response that did
   arrive.
5. If no response arrived, enter each configured replay round. Resend only to
   servers that have not returned a valid response. In a replay round, the first
   valid response completes the request.
6. If all transmission rounds produced no response and `F > 0`, wait without
   sending. The first valid response completes the request.
7. If the complete horizon expires, fail with `TIMEOUT`.

Server age is derived from the server ID. Earlier start time wins; equal start
times are ordered by the unsigned full server ID.

Completion delivery is outcome-independent. When enabled, the client
best-effort sends the same request to still-missing servers before returning
either a grant or a rejection, provided the deduplication horizon has not
expired. This helps replicas converge; it never changes or delays the selected
decision by waiting for another response.

## Default policy

```text
U = 20 ms
N = 1
B(0) = B(1) = 1
F = 1
completion_delivery = true
H = 3 * U = 60 ms
```

Equivalent Java configuration:

```java
RequestPolicy policy = new RequestPolicy(
    20,                         // U: milliseconds per unit
    1,                          // N: one replay after initial fan-out
    RequestPolicy.Schedule.fixed(1),
    1,                          // one receive-only unit
    true                        // completion delivery
);
```

Changing the policy trades response latency and network work against the chance
of obtaining a decision and synchronizing all discovered servers. Replays are
safe only within the deduplication contract; applications for which duplicate
consumption is especially costly should evaluate that trade-off explicitly.
