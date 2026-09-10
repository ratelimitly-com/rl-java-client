#!/usr/bin/env bash
set -euo pipefail

# Run after the release build. No signing key or registry credentials are used.
version="${1:?usage: test-release-artifact-gate.sh VERSION}"
fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
artifact="ratelimitly-java-client-${version}"
cp "target/${artifact}"*.jar "$fixture_dir/"
cp pom.xml "$fixture_dir/${artifact}.pom"

check_build() {
  mvn -B -ntp -Pcentral-release -DskipTests -Dgpg.skip=true \
    -Dproject.build.outputTimestamp="${SOURCE_DATE_EPOCH:-2026-01-01T00:00:00Z}" \
    -Drelease.expectedArtifacts="$fixture_dir" verify
}

check_build > "$fixture_dir/matching.log" 2>&1 || {
  cat "$fixture_dir/matching.log"
  exit 1
}

# A different Javadoc JAR must fail before Maven can reach install/deploy.
printf 'tampered artifact\n' >> "$fixture_dir/${artifact}-javadoc.jar"
if check_build > "$fixture_dir/tampered.log" 2>&1; then
  echo 'ERROR: Maven accepted a changed release artifact' >&2
  exit 1
fi
grep -q 'Release artifact mismatch' "$fixture_dir/tampered.log"
cp "target/${artifact}-javadoc.jar" "$fixture_dir/"

rm "$fixture_dir/${artifact}-sources.jar"
if check_build > "$fixture_dir/missing.log" 2>&1; then
  echo 'ERROR: Maven accepted a missing release artifact' >&2
  exit 1
fi
grep -q 'Release artifact mismatch' "$fixture_dir/missing.log"
cp "target/${artifact}-sources.jar" "$fixture_dir/"
printf '\n<!-- changed -->\n' >> "$fixture_dir/${artifact}.pom"
if check_build > "$fixture_dir/pom.log" 2>&1; then
  echo 'ERROR: Maven accepted a changed release POM' >&2
  exit 1
fi
grep -q 'Release POM mismatch' "$fixture_dir/pom.log"
echo 'Artifact gate passed: matching artifacts accepted; changed/missing JARs and changed POM rejected.'
