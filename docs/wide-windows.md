# Wide rate windows (source support, unreleased)

Configure `RateLimitlyClientConfig.builder(key).wideRateRequests(true)` only for
servers with health capability bit 5. Existing constructors remain source
compatible. Long `ResourceRequest` windows select QW/RW; default all-short
requests retain RT/RR. `CanonicalIds.bucketId` retains short v1 IDs in mixed
packets. All windows are exact finite milliseconds, at most 31536000000.

The key reader accepts formats 1 and 2. `ApiKeyInfo.windowCap()` exposes an
explicit tag; `WindowCap.toJson()` emits the canonical v2 finite/unlimited map.
`ApiKeyQuotas.unlimitedWindow()` distinguishes its native marker from finite
values. Never use unlimited as a request window or TTL. V1 code31 stays finite
4294967295. No writer, migration, release or deployment is added.

Guards, latency reports and dedup fields stay narrow; retries never change PDU.
Run `mvn test` under JDK21. Shared caps/IDs/wire KATs are part of V2Test.
