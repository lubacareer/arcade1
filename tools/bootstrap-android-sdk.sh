#!/usr/bin/env bash
# Install isolated, checksum-verified Linux SDK tools before executing vendor code.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
: "${RUNNER_TEMP:?}" "${GITHUB_ENV:?}" "${GITHUB_PATH:?}"
sdk_root="$(mktemp -d "$RUNNER_TEMP/space-pong-sdk.XXXXXX")"
downloads="$(mktemp -d "$RUNNER_TEMP/space-pong-sdk-downloads.XXXXXX")"
trap 'rm -rf -- "$downloads"' EXIT
cli_archive=commandlinetools-linux-15859902_latest.zip
platform_archive=platform-tools_r37.0.1-linux.zip
for archive in "$cli_archive" "$platform_archive"; do
  curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
    "https://dl.google.com/android/repository/$archive" --output "$downloads/$archive"
done
(cd "$downloads" && sha256sum --check "$script_dir/android-sdk.sha256")
# No archive is extracted, and no SDK executable runs, before both hashes pass.
mkdir -p "$sdk_root/cmdline-tools"
unzip -q "$downloads/$cli_archive" -d "$sdk_root/cmdline-tools"
mv "$sdk_root/cmdline-tools/cmdline-tools" "$sdk_root/cmdline-tools/latest"
unzip -q "$downloads/$platform_archive" -d "$sdk_root"
grep -Fx 'Pkg.Revision=22.0' "$sdk_root/cmdline-tools/latest/source.properties"
grep -Fx 'Pkg.Revision=37.0.1' "$sdk_root/platform-tools/source.properties"
# Keep the guard in the action's preferred bin directory so PATH changes cannot bypass it.
mv "$sdk_root/cmdline-tools/latest/bin/sdkmanager" "$sdk_root/cmdline-tools/latest/bin/sdkmanager.unwrapped"
cp "$script_dir/sdkmanager-pinned.sh" "$sdk_root/cmdline-tools/latest/bin/sdkmanager"
chmod +x "$sdk_root/cmdline-tools/latest/bin/sdkmanager" "$sdk_root/cmdline-tools/latest/bin/sdkmanager.unwrapped"
# Also detect any implicit dependency upgrade attempted by later sdkmanager installs.
(cd "$sdk_root" && find cmdline-tools/latest platform-tools -type f -print0 | sort -z | xargs -0 sha256sum > .pinned-tools.sha256)
export ANDROID_HOME="$sdk_root" ANDROID_SDK_ROOT="$sdk_root"
export PATH="$sdk_root/cmdline-tools/latest/bin:$sdk_root/platform-tools:$PATH"
printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$sdk_root" "$sdk_root" >> "$GITHUB_ENV"
printf '%s\n' "$sdk_root/cmdline-tools/latest/bin" "$sdk_root/platform-tools" >> "$GITHUB_PATH"
# yes may exit on SIGPIPE after sdkmanager finishes; retain sdkmanager's exit status.
(set +o pipefail; yes | sdkmanager --licenses > /dev/null)
sdkmanager --version
adb --version
