# Contributing

This project is released under the MIT License. Contributions should keep the
public API small, documented, framework-independent, and usable from a clean
checkout.

## Development setup

Install Java 21 or newer and Maven. From the repository root, run:

```sh
mvn -B verify
```

This is the authoritative build gate: Maven compiles the client, discovers and
runs the complete JUnit 5 suite, and packages the client. A build that discovers
zero tests fails.

## Repository scope

This repository contains the framework-independent Java client library.

Keep these outside it:

- RateLimitly server or control-plane implementation details;
- Spring, servlet, or other framework-specific integration code;
- production credentials or credentialed smoke tests;
- load generators, performance clients, and operational traffic tools.

Framework integrations belong in their own repositories. Cross-client and
live-environment testing belongs in the private integration-test system.

## Public API

Application-facing types live in `com.ratelimitly`. Treat changes to those
types as public API changes and include documentation and tests. Classes under
`com.ratelimitly.internal` are implementation details and must not be used by
consumers, examples, or downstream integrations.

Do not add a dependency that selects or suppresses an application's logging
backend. Keep dependencies narrow and explain why each runtime dependency is
needed.

## Documentation

Public documentation must stand on its own. Do not refer readers to private
workspaces, private repositories, server source files, or unpublished internal
specifications. Describe application-visible client behavior and link to the
public API document for delivery mechanics.

Use “API key” for the credential and for the account identity represented by
that key. The 3.0 public API deliberately contains no legacy tenant-named
aliases.

## Pull requests

- Keep changes scoped to one reviewable purpose.
- Add or update tests for behavioral changes.
- Update API documentation when consumers can observe the change.
- Never place real API keys in source, fixtures, command lines, logs, issues,
  or pull requests.
- Run the complete local checks before requesting review.

Maintainers preparing an immutable release must also follow the
[Maven Central release runbook](docs/releasing.md). Pull requests and ordinary
development builds must remain credential-free and incapable of publishing.
