# Maven release runbook

See [Public Maven distribution](registry-publication.md) for the registry,
credentials, approval gates, and interrupted-upload recovery contract.

## Before release

- Confirm signing-key backup/recovery and GitLab account recovery.
- Select the immutable version in a reviewed PR; the first is `3.0.0`.
- Run `python3 tools/test_publication_policy.py` and `python3 tools/test_registry.py`.
- Run `SOURCE_DATE_EPOCH=$(git show -s --format=%ct HEAD) ./tools/build-maven-dry-run.sh 3.0.0`.
- Reproduce the unsigned artifacts with the same JDK and timestamp.
- Merge and wait for successful CI and CodeQL on that exact main SHA.

## Publish

Set the protected environment's `MAVEN_PUBLICATION_APPROVED=true` only when the
preceding gates pass. Dispatch `publish-mvn` from main, `action=publish`, with
`expected_commit` equal to the full main SHA and `expected_version=3.0.0`.
Approve the protected environment job after reviewing those values.

Uploads are immediately public on GitLab. The workflow validates the anonymous
downloads, signatures, and a clean Maven consumer before publishing the GitHub
release. It never contacts Sonatype's publisher service.

If upload or finalization fails, follow the [recovery contract](registry-publication.md).
`action=finalize` verifies already uploaded files and never uploads again.
Keep main at the release commit until finalization is complete.
