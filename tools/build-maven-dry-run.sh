#!/usr/bin/env bash
set -euo pipefail

version="${1:?usage: build-maven-dry-run.sh VERSION}"
project_version="$({
  mvn -B -ntp -q \
    org.apache.maven.plugins:maven-help-plugin:3.5.2:evaluate \
    -DforceStdout \
    -Dexpression=project.version
} | tail -n 1 | tr -d '[:space:]')"

if [[ "$project_version" != "$version" ]]; then
  echo "expected project version $version, found $project_version" >&2
  exit 1
fi

dry_run_home="$(mktemp -d)"
cleanup() {
  gpgconf --homedir "$dry_run_home" --kill all >/dev/null 2>&1 || true
  rm -rf "$dry_run_home"
}
trap cleanup EXIT

export GNUPGHOME="$dry_run_home"
gpg --batch \
  --pinentry-mode loopback \
  --passphrase '' \
  --quick-generate-key \
  'rl-java-client CI dry run <ci@invalid>' \
  rsa2048 \
  sign \
  1d

output_timestamp="${SOURCE_DATE_EPOCH:-2026-01-01T00:00:00Z}"

# Deliberately stop at verify. The maven-release profile still builds and signs every artifact,
# while no deploy goal exists from which the Maven plugin could contact the publishing service.
mvn -B -ntp \
  -Pmaven-release \
  -Dproject.build.outputTimestamp="$output_timestamp" \
  clean verify

"$(dirname "$0")/verify-maven-artifacts.sh" target "$version"
