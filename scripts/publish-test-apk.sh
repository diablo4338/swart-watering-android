#!/usr/bin/env bash
set -euo pipefail

android_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
releases_dir="${APK_RELEASES_DIR:?APK_RELEASES_DIR is required}"
api_base_url="${APK_API_BASE_URL:?APK_API_BASE_URL is required}"
debug_keystore="${APK_DEBUG_KEYSTORE:?APK_DEBUG_KEYSTORE is required}"
latest_manifest="$releases_dir/latest.json"

if [[ ! -f "$debug_keystore" ]]; then
  echo "Android debug keystore does not exist: $debug_keystore" >&2
  echo "Run one debug build from Android Studio first or set APK_DEBUG_KEYSTORE" >&2
  exit 2
fi

version_name="${APK_VERSION_NAME:-}"
version_code="${APK_VERSION_CODE:-}"
if [[ -f "$latest_manifest" ]]; then
  current_version="$(awk -F'"' '/"version_name"/ {print $4; exit}' "$latest_manifest")"
  current_code="$(awk -F: '/"version_code"/ {gsub(/[^0-9]/, "", $2); print $2; exit}' "$latest_manifest")"
  if [[ ! "$current_version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    echo "Invalid published release metadata in $latest_manifest" >&2
    exit 1
  fi
  current_major="${BASH_REMATCH[1]}"
  current_minor="${BASH_REMATCH[2]}"
  current_patch="${BASH_REMATCH[3]}"
  if [[ ! "$current_code" =~ ^[0-9]+$ ]]; then
    echo "Invalid published release metadata in $latest_manifest" >&2
    exit 1
  fi
  version_name="${version_name:-$current_major.$current_minor.$((current_patch + 1))}"
  version_code="${version_code:-$((current_code + 1))}"
else
  version_name="${version_name:-1.0.0}"
  version_code="${version_code:-1001}"
fi

if [[ ! "$version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "APK_VERSION_NAME must have MAJOR.MINOR.PATCH format" >&2
  exit 2
fi
if [[ ! "$version_code" =~ ^[0-9]+$ ]]; then
  echo "APK_VERSION_CODE must be a positive integer" >&2
  exit 2
fi
if [[ -n "${current_code:-}" && "$version_code" -le "$current_code" ]]; then
  echo "APK_VERSION_CODE must be greater than published $current_code" >&2
  exit 2
fi

output_dir="$(mktemp -d "${TMPDIR:-/tmp}/smart-watering-apk.XXXXXXXX")"
staging="$releases_dir/.incoming-test-$version_code"
test ! -e "$staging"
cleanup() {
  rm -rf -- "$output_dir"
  if [[ -d "$staging" ]]; then
    rm -rf -- "$staging"
  fi
}
trap cleanup EXIT

git_commit="$(git -C "$android_dir" rev-parse HEAD 2>/dev/null || printf unknown)"
docker buildx build \
  --file "$android_dir/docker/Dockerfile.apk" \
  --target artifact \
  --output "type=local,dest=$output_dir" \
  --build-arg BUILD_VARIANT=debug \
  --build-arg VERSION_NAME="$version_name" \
  --build-arg VERSION_CODE="$version_code" \
  --build-arg PUBLIC_API_BASE_URL="$api_base_url" \
  --build-arg GIT_COMMIT="$git_commit" \
  --secret "id=debug_keystore,src=$debug_keystore" \
  "$android_dir"

test ! -e "$releases_dir/$version_code"
install -d -m 755 "$releases_dir" "$staging"
cp -a "$output_dir/releases/$version_code" "$staging/"
cp "$output_dir/releases/latest.json" "$staging/latest.json"
chmod 755 "$staging/$version_code"
chmod 644 "$staging/$version_code"/* "$staging/latest.json"
mv "$staging/$version_code" "$releases_dir/$version_code"
mv "$staging/latest.json" "$releases_dir/latest.json"
rmdir "$staging"

echo "Published test APK $version_name ($version_code)"
echo "Latest metadata: ${api_base_url%/}/api/v2/app/latest"
