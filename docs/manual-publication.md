# Manual Maven publication contract

The current contract is [Public Maven distribution](registry-publication.md).
The [release runbook](releasing.md) gives the operator sequence.

PRs, pushes, and the default manual action perform credential-free dry runs.
Only explicit `publish` or `finalize` dispatches from main can reach the
approval-protected environment. GitLab replaces Sonatype Portal; there is no
Portal deployment ID, staging step, or Maven Central publication.
