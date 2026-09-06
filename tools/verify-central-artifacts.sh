#!/usr/bin/env bash
set -euo pipefail

target_dir="${1:?usage: verify-central-artifacts.sh TARGET_DIR VERSION}"
version="${2:?usage: verify-central-artifacts.sh TARGET_DIR VERSION}"
artifact="ratelimitly-java-client-${version}"

unsigned=(
  "${artifact}.pom"
  "${artifact}.jar"
  "${artifact}-sources.jar"
  "${artifact}-javadoc.jar"
)

for name in "${unsigned[@]}"; do
  file="${target_dir}/${name}"
  signature="${file}.asc"
  if [[ ! -s "$file" || ! -s "$signature" ]]; then
    echo "missing Central artifact or signature: $name" >&2
    exit 1
  fi
  gpg --batch --verify "$signature" "$file"
done

staging="$(mktemp -d "${target_dir}/central-staging.XXXXXX")"
bundle="$(realpath "$target_dir")/central-dry-run-bundle.zip"
coordinate_dir="${staging}/com/ratelimitly/ratelimitly-java-client/${version}"
mkdir -p "$coordinate_dir"

cleanup() {
  rm -rf "$staging"
}
trap cleanup EXIT

for name in "${unsigned[@]}"; do
  cp "${target_dir}/${name}" "${target_dir}/${name}.asc" "$coordinate_dir/"
done

for name in "${unsigned[@]}"; do
  file="${coordinate_dir}/${name}"
  md5sum "$file" | cut -d ' ' -f 1 > "${file}.md5"
  sha1sum "$file" | cut -d ' ' -f 1 > "${file}.sha1"
  sha256sum "$file" | cut -d ' ' -f 1 > "${file}.sha256"
  sha512sum "$file" | cut -d ' ' -f 1 > "${file}.sha512"
done

rm -f "$bundle"
(
  cd "$staging"
  find com -type f -print | LC_ALL=C sort | zip -X -q "$bundle" -@
)

expected_count=$((${#unsigned[@]} * 6))
actual_count="$(unzip -Z1 "$bundle" | wc -l | tr -d '[:space:]')"
if [[ "$actual_count" != "$expected_count" ]]; then
  echo "Central dry-run bundle has $actual_count entries, expected $expected_count" >&2
  exit 1
fi

unzip -t "$bundle"
echo "Validated ${#unsigned[@]} signed artifacts and ${expected_count} Central bundle entries."
