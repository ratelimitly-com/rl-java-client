# Physical UDP traffic

`client.trafficStats()` returns immutable lifetime counters, including after
close. `sent()` and `received()` each expose `datagrams()`, `payloadBytes()`, and
`ipBytes()` as exact unsigned 64-bit values represented by `BigInteger`.
`sendFailures()` counts failed sends, which add no sent bytes.

Successful physical sends include retries, replicas, completion delivery and
latency reports. All received datagrams count, including invalid/unmatched
packets. DNS is excluded. Totals span socket rotations.

IP bytes estimate payload plus UDP and minimal IP headers: 28 bytes for IPv4 or
IPv4-mapped IPv6, 48 for IPv6. Ethernet, IP options and extension headers are
excluded; these are not provider invoices. Counters saturate at unsigned 64-bit
maximum and `incomplete()` becomes true. Do not reconcile incomplete snapshots.
The client may stop before all replica replies arrive: its received bytes can
be lower than server sent bytes.
