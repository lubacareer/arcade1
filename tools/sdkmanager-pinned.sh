#!/usr/bin/env bash
# The pinned emulator action always requests platform-tools. Keep our verified copy.
set -euo pipefail
verify_pins() {
  (cd "${ANDROID_HOME:?}" && sha256sum --status --check "$ANDROID_HOME/.pinned-tools.sha256")
}
verify_pins
args=()
skipped_platform=false
has_package=false
query=false
for arg in "$@"; do
  case "$arg" in
    --licenses|--list|--list_installed|--version)
      query=true
      args+=("$arg")
      ;;
    --install|--verbose|--channel=[0-3]) args+=("$arg") ;;
    -*)
      echo "Refusing unsupported SDK-manager option that could bypass pinned tools: $arg" >&2
      exit 2
      ;;
    platform-tools) skipped_platform=true ;;
    platform-tools*|cmdline-tools*)
      echo "Command-line and platform tools must be changed through the checked-in checksum pins." >&2
      exit 2
      ;;
    *)
      has_package=true
      args+=("$arg")
      ;;
  esac
done
if "$skipped_platform"; then
  if "$query"; then
    echo "Refusing ambiguous platform-tools request with a query operation." >&2
    exit 2
  fi
  echo "Keeping repository-pinned Android platform-tools." >&2
  if ! "$has_package"; then exit 0; fi
fi
"${ANDROID_HOME:?}/cmdline-tools/latest/bin/sdkmanager.unwrapped" "${args[@]}"
# SDK package dependencies can trigger implicit upgrades: fail before any new tools run.
verify_pins
