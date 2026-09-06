# Security

Do not open a public issue for a suspected vulnerability.

Use GitHub's [private vulnerability reporting](https://github.com/ratelimitly-com/rl-java-client/security/advisories/new)
or email:

```text
wojciech@ratelimitly.com
```

Include the affected release or commit, Java and operating-system versions,
redacted configuration, reproduction steps, and expected and observed
behavior. Do not include a real API key or captured authenticated packet.

## API-key handling

An API key may contain reusable cookie or AES key material. Treat the complete
encoded value as a secret:

- do not log it, interpolate it into exception messages, or include it in
  diagnostics;
- do not pass it on a command line, where it may appear in process listings or
  shell history;
- prefer a dedicated secret manager or a narrowly scoped environment variable;
- do not commit it to source, test fixtures, build logs, or CI configuration.

`RateLimitlyClientConfig.toString()` redacts the encoded API key, and
`ApiKeyInfo.toString()` omits both the encoded value and raw
secret. This is defense in depth, not permission to log credential-bearing
objects: the application still retains its input API-key string, and
`ApiKeyInfo.authSecret()` returns a defensive copy when explicitly called.

## Authentication modes

Use AES credentials when traffic crosses an untrusted network. AES-256-GCM
encrypts the request payload and authenticates the complete datagram, while the
packet header remains visible.

Cookie credentials are intended for private networks whose threat model
excludes passive capture and on-path modification. Cookie mode does not provide
packet confidentiality or integrity and places a reusable cookie value on the
wire.

The `none` authentication mode is for isolated development and tests only. A
management credential is not a client request credential and is rejected by
client construction before a socket is opened.

## Failures and replay

The client accepts a response only for a currently active request identifier
and only after validating its expected server identity and authentication.
Late and duplicate responses do not represent additional decisions.

An authentication failure at the remote service may be indistinguishable from
packet loss because no usable response arrives. Applications must treat a
timeout as failure to obtain a decision, not as either a grant or a rejection.

## Supported versions

Security fixes are provided for the most recent public release. Users of older
releases should upgrade unless release notes explicitly extend support.
