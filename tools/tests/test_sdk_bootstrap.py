"""Exercise SDK mutation protection using a harmless stand-in for the vendor CLI."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASH = (str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe")
        if os.name == "nt" else shutil.which("bash"))


class SdkManagerGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="sdk guard ")
        self.addCleanup(self.temp.cleanup)
        self.sdk = Path(self.temp.name)
        cli = self.sdk / "cmdline-tools/latest/bin/sdkmanager.unwrapped"
        cli.parent.mkdir(parents=True)
        cli.write_text("#!/usr/bin/env bash\nprintf '%s\\n' \"$@\"\n", encoding="utf-8", newline="\n")
        cli.chmod(0o755)
        self.vendor = cli
        self.record_pin()

    def record_pin(self):
        digest = hashlib.sha256(self.vendor.read_bytes()).hexdigest()
        (self.sdk / ".pinned-tools.sha256").write_text(
            digest + "  cmdline-tools/latest/bin/sdkmanager.unwrapped\n", encoding="utf-8", newline="\n")

    def run_guard(self, *args):
        return subprocess.run(
            [BASH, (ROOT / "tools/sdkmanager-pinned.sh").as_posix(), *args],
            env={**os.environ, "ANDROID_HOME": self.sdk.as_posix()},
            capture_output=True, text=True, check=False,
        )

    def test_delegates_licenses_and_nonprotected_packages_unchanged(self):
        for args in [
            ["--licenses"],
            ["--version"],
            ["--install", "emulator", "--channel=0"],
            ["--install", "system-images;android-26;google_apis;x86_64", "--channel=0"],
            ["platforms;android-37.0", "build-tools;36.0.0"],
        ]:
            with self.subTest(args=args):
                result = self.run_guard(*args)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(args, result.stdout.splitlines())

    def test_runner_install_keeps_other_packages_but_skips_platform_tools(self):
        result = self.run_guard("--install", "build-tools;37.0.0", "platform-tools", "platforms;android-35")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--install", "build-tools;37.0.0", "platforms;android-35"], result.stdout.splitlines())

    def test_platform_tools_only_is_a_successful_no_op(self):
        for args in [["--install", "platform-tools"], ["platform-tools"]]:
            result = self.run_guard(*args)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("", result.stdout)

    def test_rejects_mutation_and_ambiguous_forms_before_vendor_execution(self):
        for args in [
            ["--update"], ["-u"], ["--uninstall", "platform-tools"],
            ["--install", "cmdline-tools;22.0"], ["--install", "platform-tools;37.0.3"],
            ["--install=platform-tools"], ["--package_file=packages.txt"],
            ["--sdk_root=/tmp/another-sdk", "platform-tools"],
            ["--licenses", "platform-tools"],
        ]:
            with self.subTest(args=args):
                result = self.run_guard(*args)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", result.stdout)

    def test_detects_implicit_vendor_replacement_before_later_tools_run(self):
        self.vendor.write_text('#!/usr/bin/env bash\nprintf "changed" >> "$0"\n', encoding="utf-8", newline="\n")
        self.record_pin()
        result = self.run_guard("--install", "emulator")
        self.assertNotEqual(0, result.returncode)

    def test_detects_modified_tools_before_vendor_execution(self):
        self.vendor.write_text('#!/usr/bin/env bash\necho unsafe\n', encoding="utf-8", newline="\n")
        result = self.run_guard("--licenses")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", result.stdout)


if __name__ == "__main__":
    unittest.main()
