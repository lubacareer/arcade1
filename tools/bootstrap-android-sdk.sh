#!/usr/bin/env bash
# Install only repository-locked Linux SDK archives before executing vendor code.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
profile="${1:-build}"
if (( $# > 1 )); then
  printf 'Usage: %s [build|emulator26|emulator35]\n' "$0" >&2
  exit 2
fi
case "$profile" in
  build|emulator26|emulator35) ;;
  *) printf 'Unknown locked SDK profile: %s\n' "$profile" >&2; exit 2 ;;
esac
: "${RUNNER_TEMP:?}" "${GITHUB_ENV:?}" "${GITHUB_PATH:?}"
sdk_root="$(mktemp -d "$RUNNER_TEMP/space-pong-sdk.XXXXXX")"
downloads="$(mktemp -d "$RUNNER_TEMP/space-pong-sdk-downloads.XXXXXX")"
# Only this invocation's freshly created archive cache is removed.
trap 'rm -rf -- "$downloads"' EXIT
python3 "$script_dir/android_sdk.py" install --profile "$profile" \
  --sdk-root "$sdk_root" --cache-dir "$downloads" --accept-licenses
export ANDROID_HOME="$sdk_root" ANDROID_SDK_ROOT="$sdk_root"
export PATH="$sdk_root/cmdline-tools/latest/bin:$sdk_root/platform-tools:$sdk_root/emulator:$PATH"
printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$sdk_root" "$sdk_root" >> "$GITHUB_ENV"
printf '%s\n' "$sdk_root/cmdline-tools/latest/bin" "$sdk_root/platform-tools" "$sdk_root/emulator" >> "$GITHUB_PATH"
# The guard validates the complete installed profile around this read-only vendor query.
sdkmanager --version
adb --version
