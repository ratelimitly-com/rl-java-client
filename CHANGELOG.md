# Changelog

This project records notable changes following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Public releases use
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 3.0.0 - 2026-09-13

- Publish signed artifacts to the public GitLab Maven registry and matching
  GitHub release assets; replace the Sonatype publisher plugin and credentials.
- Add anonymous-download, immutable-coordinate, and interrupted-upload checks.

### Security

- Redacted API keys from client-configuration rendering and removed the public
  mutable view of retained authentication secret bytes.

### Changed

- Replaced the private 2.x configuration surface with the breaking 3.0 API:
  API-key-only construction now derives production DNS, while explicit DNS is
  an advanced builder override.
- Replaced `DecodedTenantCredential`, `TenantCredentialDecoder`, and
  `TenantQuotas` with `ApiKeyInfo`, `ApiKeyDecoder`, and `ApiKeyQuotas`; no
  compatibility aliases remain.
- Replaced common-pool asynchronous execution with a client-owned virtual-thread
  executor and an optional caller-owned executor.
- Added the stable automatic module name `com.ratelimitly.client`; the supported
  public API remains the `com.ratelimitly` package.
- Replaced the custom `SelfTest` runner with focused JUnit 5 tests and made
  `mvn verify` the authoritative local, CI, and release build gate.
- Removed the direct `slf4j-nop` runtime dependency so the client does not
  choose the host application's logging provider.
- Added clean consumer compilation checks for both classpath and Java module
  path usage.
- Added complete Maven Central metadata, signed release-bundle validation,
  zero-warning public API Javadocs, and a maintainer release runbook.
- Removed the inert `debug` configuration option and moved the development
  version to `3.0.0-SNAPSHOT`.
- Began the clean public-release and Maven Central preparation tracked by
  [issue #15](https://github.com/ratelimitly-com/rl-java-client/issues/15).
- Replaced internal workspace documentation with layered public client
  documentation.
- Removed performance and metrics traffic programs from the library artifact.
- Removed the credentialed production smoke probe from public contributor CI;
  live-environment validation belongs in the private integration-test system.

### Fixed

- Made source-port steering scan occupied candidates in monotonic order,
  preserve concurrent requests on the old socket until they drain, and start
  the replacement receive path before closing the old socket.

## 2.0.0 - 2026-08-30

Private GitHub release. Updated the latency tracker definition and wire format
to remove `buffer_size`, including 36-byte guard blocks and 32-byte latency
report blocks. This version was not published to a Maven repository.

## 1.0.1 - 2026-08-22

Private GitHub maintenance release with CI/release improvements and tolerance
for a failed send to one endpoint when other discovered endpoints remain
usable. This version was not published to a Maven repository.

## 1.0.0 - 2026-08-20

Initial private GitHub release with versioned Bech32 API-key format v1, packed
quotas, and the unified HA scheduling policy. This version was not published to
a Maven repository.
