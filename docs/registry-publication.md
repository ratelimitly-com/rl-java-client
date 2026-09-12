# Public Maven distribution

RateLimitly publishes client and integration artifacts through the
[public GitLab Maven registry](https://gitlab.com/ratelimitly-com/maven-registry).
Source code, reviews, CI, and release downloads stay on GitHub. No server
artifacts are published. Consumers do not need a GitLab account or token.

The project-scoped Maven endpoint is:

```text
https://gitlab.com/api/v4/projects/86375734/packages/maven
```

Keep the coordinates `com.ratelimitly:ratelimitly-java-client`. GitLab is an
additional Maven repository, not a replacement or mirror for Maven Central's
third-party dependencies. Use explicit release versions; disable snapshots in
consumer configuration.

## Why not Maven Central?

Sonatype classified these service-client libraries as commercial-nature
publishing and quoted an annual subscription on 2026-09-11. We selected GitLab's
Free package registry instead. The MIT license is unchanged. This is a choice of
distribution host, not a move of the source repository, and there is no Sonatype
publication step or subscription in this workflow.

## Publication contract

1. Review and merge the version and publication changes. Require successful CI
   and CodeQL for the exact main commit. Run the credential-free signed dry run.
2. Dispatch `publish-mvn` from `main` with `action=publish`, its full SHA, and the
   exact non-SNAPSHOT POM version. The protected `maven-publication` environment
   requires maintainer approval; self-approval is allowed.
3. Before any upload, require the coordinate to be absent, including incomplete
   package records. Rebuild, compare the reviewed unsigned artifacts, and sign
   with the dedicated RateLimitly key. Upload with Maven's deploy plugin.
4. Download anonymously and compare every POM/JAR with the reviewed build. Verify
   signatures against the pinned signing fingerprint, and compile a consumer
   using an empty Maven cache. Only then create the GitHub release.

GitLab uploads become public immediately: there is **no Portal staging or
atomic multi-file publication**. Duplicate Maven uploads are disabled on the
GitLab group. Never overwrite or delete a published version to repair a release.
Automatic publish reruns are refused. If an upload was interrupted, inspect the
package records and job logs first. If all files arrived, dispatch `finalize`
with the same SHA/version: it only verifies existing registry artifacts and
completes the GitHub release. If files are missing, stop for an explicitly
reviewed recovery or select a new version; do not blindly rerun deployment.

Keep main at the selected release commit until finalization finishes. GitHub
release assets include the POM/JARs, detached signatures, the public signing key,
and SHA256SUMS. Unsigned artifacts are byte-reproducible; signatures include a
signing timestamp and need not be identical across builds.

## Credentials and safeguards

The environment permits only branch `main`, requires a maintainer's approval,
and disallows administrator bypass. Production credentials are never available
to PR or automatic dry-run jobs. The publishing job does not restore a Maven
cache and removes its isolated GPG keyring on exit.

| Environment secret | Purpose |
| --- | --- |
| `GITLAB_MAVEN_USERNAME` | Project deploy-token username |
| `GITLAB_MAVEN_TOKEN` | Project token with package read/write scopes only |
| `MAVEN_GPG_PRIVATE_KEY` | Armored, passphrase-protected signing key |
| `MAVEN_GPG_PASSPHRASE` | Signing-key passphrase |

Set `MAVEN_GPG_FINGERPRINT` to the pinned full fingerprint and
`MAVEN_PUBLICATION_APPROVED=true` only after release readiness and key recovery
are confirmed. Separate Java/Spring tokens allow independent revocation but
both have access to the dedicated registry project, not individual coordinates.
Package-write scope can also delete packages; protect the secrets accordingly.
Do not log credentials, put them in POMs, or add them to consumer instructions.

The initially provisioned deploy tokens expire on 2027-09-12. Rotate before
expiry. Existing Sonatype environments remain disabled and are not used.

References: [GitLab Maven registry](https://docs.gitlab.com/user/packages/maven_repository/),
[project deploy tokens](https://docs.gitlab.com/user/project/deploy_tokens/).
