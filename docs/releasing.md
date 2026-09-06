# Maven Central release runbook

This runbook is for RateLimitly maintainers. It publishes only the Java client
artifact under:

```text
com.ratelimitly:ratelimitly-java-client:<version>
```

Maven Central versions are immutable. Never use a release coordinate for a
test upload, and never attempt to replace a published version.

## One-time Central Portal setup

1. Create a publisher account at
   [Central Portal](https://central.sonatype.com/).
2. Register the `com.ratelimitly` namespace and prove control of
   `ratelimitly.com` with the DNS TXT value supplied by the Portal.
3. Confirm that the namespace is verified and mapped to the RateLimitly
   organization before creating a release.
4. Generate a Central Portal user token. The generated username and password
   are publishing credentials; neither value is the interactive account
   password.

See the official guides for
[namespace registration](https://central.sonatype.org/register/namespace/) and
[Portal tokens](https://central.sonatype.org/publish/generate-portal-token/).

## One-time signing-key setup

Create a dedicated, passphrase-protected OpenPGP release key whose public
identity uses the RateLimitly organization and a `ratelimitly.com` address.
Keep an offline encrypted backup of the private key and its revocation
certificate. Record its owner, fingerprint, creation date, expiration date,
and recovery location outside this repository.

Distribute the public key through a key server supported by Maven Central so
consumers can verify signatures. Never upload the private key. The official
[Central signing guide](https://central.sonatype.org/publish/requirements/gpg/)
describes supported key servers and key rotation.

## GitHub release secrets

Configure these Actions secrets in the release repository:

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | username generated with the Central Portal token |
| `MAVEN_CENTRAL_TOKEN` | password generated with the Central Portal token |
| `MAVEN_GPG_PRIVATE_KEY` | ASCII-armored exported secret signing key |
| `MAVEN_GPG_PASSPHRASE` | signing-key passphrase |

Do not put these values in Maven settings committed to Git, command lines,
workflow logs, issues, pull requests, or release notes. Limit repository access
to maintainers allowed to publish immutable releases. Rotate the Portal token
regularly and immediately after suspected exposure. Rotate or extend the
signing key before it expires, then redistribute its public key.

## Credential-free dry run

Ordinary verification remains:

```sh
mvn -B -ntp clean verify
```

The release dry run creates a disposable one-day signing key, builds the main,
source, and zero-warning Javadoc JARs, signs the POM and all three JARs, verifies
the signatures, and creates a 24-entry structural Central bundle. It never
invokes Maven's `deploy` phase and cannot contact Central:

```sh
./tools/build-central-dry-run.sh 3.0.0-SNAPSHOT
```

The resulting bundle is `target/central-dry-run-bundle.zip`. Its signatures
prove the build path, not publisher identity; only the release workflow uses
the RateLimitly signing key.

## Cut a release

1. Confirm the public repository's documentation, license, security controls,
   and protected-branch settings are current. Require successful CI and code
   scanning on the exact public `main` commit. Central-consumer and downstream
   rollout checks necessarily follow the publication.
2. Confirm the Central namespace, Portal token, signing key, and four GitHub
   secrets above are current.
3. Update `CHANGELOG.md` for the release.
4. In one focused pull request, change the POM version from the current
   `-SNAPSHOT` value to a numeric `MAJOR.MINOR.PATCH` version.
5. Require the complete CI and release dry-run checks on that exact PR head.
6. Review the generated JAR and Javadoc contents. Merge only when the release
   is intended to become public and immutable.
7. Do not create the Git tag manually. The push workflow builds the exact
   merge commit, compares all three JARs against the validated release artifacts
   during Maven's `verify` phase, publishes it through Central Portal, waits for Central to
   report `published`, verifies a clean consumer, then creates the matching
   GitHub tag and release.

After `3.0.0`, return `main` to the next intended `-SNAPSHOT` version in a
separate pull request.

## Verify the published release

The workflow performs these checks, but the operator should also inspect the
Portal deployment and Maven Central coordinate:

```text
https://repo1.maven.org/maven2/com/ratelimitly/ratelimitly-java-client/<version>/
```

Confirm that the directory contains the POM, main JAR, source JAR, Javadoc JAR,
signatures, and checksums. Verify that the GitHub release tag targets the same
commit used by the workflow. Finally, build a clean consumer with no local
RateLimitly artifact in its Maven repository.

## Recovery

- If local or pull-request validation fails, fix it before merging. No public
  coordinate has been consumed.
- If Central rejects a deployment before publication, inspect the Portal
  validation details, drop the failed deployment if necessary, fix the cause,
  and rerun only after confirming that the coordinate was never published.
- If Central has published the coordinate but a later GitHub step fails, do not
  redeploy or change the artifact. A rerun must verify that the existing
  Central artifacts match the exact release build, then complete the GitHub
  release. Only HTTP 404 permits a new deployment; network failures and other
  HTTP errors stop the workflow without redeploying.
- If a defect is discovered after publication, publish a new patch version.
  Maven Central will not replace or delete the immutable release as a normal
  recovery mechanism.
