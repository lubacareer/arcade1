"""Check the SDK guard's allowlist using fake verification and vendor commands."""
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASH = (str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe")
        if os.name == "nt" else shutil.which("bash"))
LOCKED = ["platform-tools", "cmdline-tools;22.0", "build-tools;36.0.0",
          "platforms;android-37", "emulator", "system-images;android-26;google_apis;x86_64"]


class SdkManagerGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="sdk guard ")
        self.addCleanup(self.temp.cleanup)
        self.sdk = Path(self.temp.name)
        metadata = self.sdk / ".space-pong"
        metadata.mkdir()
        (metadata / "packages.json").write_text(json.dumps(LOCKED), encoding="utf-8")
        self.helper = metadata / "android_sdk.py"
        self.helper.write_text(r"""import json, pathlib, sys
args = sys.argv[1:]
root = pathlib.Path(args[2])
with (root / 'helper-calls.jsonl').open('a', encoding='utf-8') as log:
    log.write(json.dumps(args) + '\n')
if args[1:2] != ['--sdk-root'] or (root / 'invalid-sdk').exists():
    sys.exit(17)
if args[0] == 'verify-packages':
    locked = json.loads((root / '.space-pong/packages.json').read_text())
    if args[3:4] != ['--'] or not args[4:] or any(p not in locked for p in args[4:]):
        sys.exit(19)
elif args[0] != 'verify' or len(args) != 3:
    sys.exit(21)
""", encoding="utf-8", newline="\n")
        vendor = self.sdk / "cmdline-tools/22.0/bin/sdkmanager.unwrapped"
        vendor.parent.mkdir(parents=True)
        vendor.write_text(r"""#!/usr/bin/env bash
printf '%s\n' "$@" >> "$ANDROID_HOME/vendor-calls"
printf '%s\n' "$@"
if [[ "${TEST_VENDOR_MUTATES:-0}" == 1 ]]; then touch "$ANDROID_HOME/invalid-sdk"; fi
exit "${TEST_VENDOR_EXIT:-0}"
""", encoding="utf-8", newline="\n")
        vendor.chmod(0o755)
        commands = self.sdk / "test-bin"
        commands.mkdir()
        python = commands / "python3"
        python.write_text(
            "#!/usr/bin/env bash\nexec " + shlex.quote(Path(sys.executable).as_posix()) + ' "$@"\n',
            encoding="utf-8", newline="\n")
        python.chmod(0o755)
        self.environment = {**os.environ, "ANDROID_HOME": self.sdk.as_posix(),
                            "PATH": str(commands) + os.pathsep + os.environ["PATH"],
                            "MSYS2_ARG_CONV_EXCL": "*"}

    def run_guard(self, *args, **environment):
        return subprocess.run(
            [BASH, (ROOT / "tools/sdkmanager-pinned.sh").as_posix(), *args],
            env={**self.environment, **environment}, capture_output=True, text=True, check=False,
        )

    def helper_calls(self):
        path = self.sdk / "helper-calls.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def reset_calls(self):
        for name in ["helper-calls.jsonl", "vendor-calls"]:
            (self.sdk / name).unlink(missing_ok=True)

    def test_install_forms_verify_exact_packages_without_invoking_vendor(self):
        for args, packages in [
            (["platform-tools"], ["platform-tools"]),
            (["--install", "cmdline-tools;22.0", "--channel=0"], ["cmdline-tools;22.0"]),
            (["--channel=0", "--install", *LOCKED], LOCKED),
            (["emulator", "--channel=0"], ["emulator"]),
        ]:
            with self.subTest(args=args):
                self.reset_calls()
                result = self.run_guard(*args)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual([["verify-packages", "--sdk-root", self.sdk.as_posix(), "--", *packages]], self.helper_calls())
                self.assertFalse((self.sdk / "vendor-calls").exists())

    def test_licenses_verify_without_vendor_or_license_mutation(self):
        result = self.run_guard("--licenses")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([["verify", "--sdk-root", self.sdk.as_posix()]], self.helper_calls())
        self.assertFalse((self.sdk / "vendor-calls").exists())

    def test_queries_keep_exact_arguments_and_verify_before_and_after_vendor(self):
        for query in ["--version", "--list_installed"]:
            with self.subTest(query=query):
                self.reset_calls()
                result = self.run_guard(query)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual([query], result.stdout.splitlines())
                self.assertEqual([query], (self.sdk / "vendor-calls").read_text().splitlines())
                self.assertEqual([["verify", "--sdk-root", self.sdk.as_posix()]] * 2, self.helper_calls())

    def test_rejects_missing_packages_unknown_options_and_mixed_commands(self):
        for args in [
            [], ["--install"], ["--channel=0"], [""], ["--update"], ["-u"],
            ["--uninstall", "platform-tools"], ["--install=platform-tools"],
            ["--package_file=packages.txt"], ["--sdk_root=/tmp/elsewhere", "platform-tools"],
            ["--channel=1", "platform-tools"], ["--channel=2", "platform-tools"],
            ["--channel=3", "platform-tools"], ["--verbose", "platform-tools"],
            ["--install", "--install", "platform-tools"], ["--channel=0", "platform-tools", "--channel=0"],
            ["--licenses", "platform-tools"], ["--install", "--licenses"],
            ["--list"], ["--version", "--list_installed"], ["--version", "--channel=0"],
            ["platform-tools", "--version"], ["--", "platform-tools"],
        ]:
            with self.subTest(args=args):
                self.reset_calls()
                result = self.run_guard(*args)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], self.helper_calls())
                self.assertFalse((self.sdk / "vendor-calls").exists())

    def test_unknown_or_uninstalled_packages_are_rejected_by_verifier(self):
        for package in ["build-tools;99.0.0", "cmdline-tools;latest", "platform-tools;37.0.3", "not-installed"]:
            with self.subTest(package=package):
                self.reset_calls()
                result = self.run_guard("--install", "platform-tools", package)
                self.assertEqual(19, result.returncode, result.stderr)
                self.assertEqual([["verify-packages", "--sdk-root", self.sdk.as_posix(), "--", "platform-tools", package]], self.helper_calls())
                self.assertFalse((self.sdk / "vendor-calls").exists())

    def test_failed_preverification_prevents_every_supported_operation(self):
        (self.sdk / "invalid-sdk").touch()
        for args in [["--licenses"], ["--version"], ["--list_installed"], ["platform-tools"]]:
            with self.subTest(args=args):
                result = self.run_guard(*args)
                self.assertEqual(17, result.returncode, result.stderr)
                self.assertFalse((self.sdk / "vendor-calls").exists())

    def test_query_always_reverifies_even_when_vendor_fails(self):
        for mutate, expected in [("0", 43), ("1", 17)]:
            with self.subTest(mutate=mutate):
                self.reset_calls()
                result = self.run_guard("--version", TEST_VENDOR_EXIT="43", TEST_VENDOR_MUTATES=mutate)
                self.assertEqual(expected, result.returncode, result.stderr)
                self.assertEqual([["verify", "--sdk-root", self.sdk.as_posix()]] * 2, self.helper_calls())

    def test_successful_query_cannot_leave_an_unverified_sdk(self):
        result = self.run_guard("--list_installed", TEST_VENDOR_MUTATES="1")
        self.assertEqual(17, result.returncode, result.stderr)
        self.assertEqual([["verify", "--sdk-root", self.sdk.as_posix()]] * 2, self.helper_calls())

    def test_missing_verifier_does_not_invoke_vendor(self):
        self.helper.unlink()
        result = self.run_guard("--version")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.sdk / "vendor-calls").exists())


if __name__ == "__main__":
    unittest.main()
