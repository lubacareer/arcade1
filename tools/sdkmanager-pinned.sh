#!/usr/bin/env bash
# Requests may only use the already installed, fully verified SDK profile.
set -euo pipefail
sdk_root="${ANDROID_HOME:?ANDROID_HOME must name the pinned SDK}"
helper="$sdk_root/.space-pong/android_sdk.py"

verify_sdk() {
  python3 "$helper" verify --sdk-root "$sdk_root"
}

reject() {
  printf 'Refusing SDK-manager request outside the pinned profile: %s\n' "$1" >&2
  exit 2
}

if (( $# == 1 )); then
  case "$1" in
    --licenses)
      # The installer recorded the locked licenses; this operation cannot accept new ones.
      verify_sdk
      exit 0
      ;;
    --version|--list_installed)
      verify_sdk
      status=0
      "$sdk_root/cmdline-tools/22.0/bin/sdkmanager.unwrapped" "$@" || status=$?
      # Verify even after a failing query, before another SDK tool can run.
      verify_sdk
      exit "$status"
      ;;
  esac
fi

packages=()
seen_install=false
seen_channel=false
for arg in "$@"; do
  case "$arg" in
    --install)
      if "$seen_install"; then reject "duplicate --install"; fi
      seen_install=true
      ;;
    --channel=0)
      if "$seen_channel"; then reject "duplicate --channel=0"; fi
      seen_channel=true
      ;;
    ""|-*) reject "$arg" ;;
    *) packages+=("$arg") ;;
  esac
done
if (( ${#packages[@]} == 0 )); then reject "no package IDs supplied"; fi

# Exact IDs must be locked and installed. Never delegate installation or dependency resolution.
python3 "$helper" verify-packages --sdk-root "$sdk_root" -- "${packages[@]}"
