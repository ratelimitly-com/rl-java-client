# Manual Maven publication contract

Preparation does not publish anything. Keep `3.0.0-SNAPSHOT` until a reviewed
release PR selects the immutable version. The `publish-mvn` workflow replaces
automatic publication on a numeric-version push; PRs, pushes, and the default
manual action are credential-free dry runs.

## Operator sequence

1. Confirm `com.ratelimitly` namespace/organization ownership, account recovery,
   signing-key backup/revocation, and public-key availability. Confirm with
   Sonatype whether the [publisher terms](https://central.sonatype.org/publish/producer-terms/)
   concerning service-dependent SDKs require permission or fees for RateLimitly.
   MIT licensing alone does not answer that question.
2. Configure the `maven-central` GitHub environment: allow only `main`, require
   maintainer approval (self-approval allowed for the solo maintainer), and set
   `MAVEN_CENTRAL_APPROVED=true` only after the preceding checks. Store the four
   secrets from [the runbook](releasing.md) here, not at repository scope. Record
   the full signing fingerprint in `MAVEN_GPG_FINGERPRINT` (an environment variable,
   not a secret). Restrict who can edit the workflow/environment.
3. Merge the version/changelog PR and wait for exact-commit CI and CodeQL. Dispatch
   `stage` from `main`, entering its full SHA and matching version. First inspect
   Portal deployments, including failed/in-progress ones, and acknowledge
   `portal_checked` only when no prior upload for this version needs recovery.
4. The approved stage job rebuilds, compares the unsigned artifacts, signs, and
   uploads with `autoPublish=false`, `waitUntil=validated`. Record the deployment
   ID from the job log/Portal and the uploaded bundle checksum. No public version,
   GitHub release, or tag is created by staging.
5. Inspect the actual validated Portal deployment and publish that deployment in
   the Portal. This is the deliberate irreversible step. Do not upload it again.
6. Dispatch `finalize` with the same SHA/version and deployment ID. The workflow
   requires that exact deployment to be `PUBLISHED` with the expected coordinate,
   compares the public POM/JARs with the reviewed build, checks a clean consumer,
   and only then creates the matching GitHub release/tag.

Keep `main` at the selected release SHA until finalization completes; dispatch
uses the checked-out main commit, not an arbitrary unreviewed ref. Only then
merge the next development snapshot. Exceptional recovery after main has moved
requires a separately reviewed recovery procedure, not rewriting main or a tag.

The required check results must be from GitHub Actions on the exact commit;
missing, skipped, cancelled, failing, stale, or still-running checks stop the
operation. Serialize release runs and never cancel an upload in progress.
CodeQL analyses must complete successfully; unresolved high/critical security
alerts and error-level findings block release. Previously reviewed dismissals
are not treated as open alerts. Lower-severity findings still require review.

## Recovery and limits

Never use a Central repository 404 as evidence that no Portal upload exists.
An upload can be pending while the public coordinate still returns 404. Stage
reruns are refused: after any interrupted stage, inspect Portal and recover the
deployment ID before proceeding. If validation failed and a new upload is really
needed, deliberately drop/resolve the failed deployment in Portal, then dispatch
a new stage run after rechecking. If the upload response/log was lost, find the
deployment by its version/SHA name in Portal; do not guess its state.

`finalize` never uploads. A not-yet-published deployment or delayed Central
resolution fails safely and can be retried. Existing public artifacts must match
exactly; changed artifacts need a new version. A post-publication GitHub failure
does not justify another Central deployment. No production key is needed for
dry-run tests; their disposable signing key proves packaging, not identity.

Pin the build toolchain and output timestamp. Compare unsigned JARs/POMs; OpenPGP
signatures contain signing-time information and are not expected to be identical
between builds. Do not cache the publishing job's Maven home or GPG keyring, use
debug logging with secrets, or use untrusted PR artifacts for publication.
