# Maven Central release runbook

Publishes only `com.ratelimitly:ratelimitly-java-client:<version>`.
Maven Central releases are immutable. A merge, version change, or ordinary push
never publishes. See the [manual publication contract](manual-publication.md)
for the complete stage / Portal approval / finalize sequence and recovery rules.

## One-time publisher setup

1. Register at [Central Portal](https://central.sonatype.com/), using the intended
   maintainer identity and a recoverable RateLimitly email address.
2. Verify `com.ratelimitly` with the Portal-provided DNS TXT value on exactly
   `ratelimitly.com`; check DNS propagation before requesting verification.
3. Confirm the RateLimitly organization and namespace access. Clarify with
   Sonatype whether its [publisher terms](https://central.sonatype.org/publish/producer-terms/)
   for service-dependent SDKs affect this publication. Do not infer permission
   or free publishing from the MIT license alone.
4. Generate an expiring Portal user token. Its generated username/password are
   publishing credentials, not your interactive account password.
5. Create a dedicated passphrase-protected signing key with a `ratelimitly.com`
   identity. Record its fingerprint, owner, expiry, encrypted offline backup, and
   revocation certificate. Distribute only the public key through a
   [supported keyserver](https://central.sonatype.org/publish/requirements/gpg/).

## Protected GitHub environment

Create `maven-central`, restrict it to `main`, and require maintainer approval.
Allow self-approval for the solo maintainer. Keep these secrets in that environment:

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Portal token username |
| `MAVEN_CENTRAL_TOKEN` | Portal token password |
| `MAVEN_GPG_PRIVATE_KEY` | ASCII-armored secret signing key |
| `MAVEN_GPG_PASSPHRASE` | Signing-key passphrase |

Set environment variables `MAVEN_GPG_FINGERPRINT` to the full 40-hex-digit
fingerprint and `MAVEN_CENTRAL_APPROVED=true` only after ownership, policy, key
distribution, and recovery checks are complete. No secret values belong in source,
issues, command arguments, caches, logs, or release artifacts. Rotate credentials
and renew/redistribute the public key before expiry.

## Credential-free verification

```sh
python3 tools/test_publication_policy.py
mvn -B -ntp clean verify
./tools/build-central-dry-run.sh 3.0.0-SNAPSHOT
./tools/test-release-artifact-gate.sh 3.0.0-SNAPSHOT
```

The dry run uses a disposable one-day key and stops at `verify`: it cannot upload.
It signs the POM and main/source/Javadoc JARs, verifies signatures, and checks a
24-entry structural bundle. This does not claim Portal validation or publisher
identity. CI additionally compares unsigned JARs between clean builds. The
production Maven `verify` gate compares the POM and all three JARs with the
reviewed artifacts before an upload can happen.

## First release

Keep `3.0.0-SNAPSHOT` until the intended release PR updates the POM and changelog.
After merge, require the exact commit's full CI and CodeQL results. Dispatch
`publish-mvn` on `main` with `action=stage`, matching `expected_commit` and
`expected_version`, and `portal_checked=true` only after inspecting Portal for
previous/in-progress uploads. Review the protected-environment approval.

The workflow uploads for validation, not automatic publication. Preserve its
`central-handoff` artifact (bundle/hash/identity and deployment ID when captured),
and inspect the deployment in Portal. The deployment name includes version/SHA;
if the upload response is lost, find it there before retrying anything.

Publish the reviewed existing deployment in Portal. Then dispatch `finalize`
with the same SHA/version and its `deployment_id`. It never deploys: it verifies
the Portal state and coordinate, compares Central's public bytes, tests an empty
Maven-cache consumer, and creates the matching GitHub release/tag. Do not create
tags manually. Return development to the next intended snapshot in a separate PR.

## Interrupted publication

- A failed or interrupted stage must not be rerun automatically. Inspect Portal
  first; a public repository 404 does not mean an upload never happened.
- Recover the existing deployment ID. Finish validation/publication there, or
  explicitly resolve/drop the failed deployment before requesting a new upload.
- If Central is published but propagation or GitHub finalization failed, rerun
  `finalize`, never `stage`. Byte mismatches stop recovery; fix released defects
  with a new version, not replacement artifacts.
- If the GitHub release already exists, verify its tag and artifacts manually;
  the workflow refuses to overwrite it. Repository publication is not transactional
  with Central, so a partial completion must remain visible to the operator.

References: [Portal namespaces](https://central.sonatype.org/register/namespace/),
[tokens](https://central.sonatype.org/publish/generate-portal-token/),
[plugin configuration](https://central.sonatype.org/publish/publish-portal-maven/),
and [deployment states](https://central.sonatype.org/publish/publish-portal-api/).
