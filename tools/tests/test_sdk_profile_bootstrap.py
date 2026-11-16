"""Exercise CI profile wiring and cleanup without downloading vendor binaries."""
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


class ProfileBootstrapTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="sdk bootstrap ")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.bin = self.base / "bin"
        self.bin.mkdir()
        self.fixture = self.base / "installer.py"
        self.fixture.write_text(r'''import json, os, pathlib, sys
args = sys.argv[1:]
def native(value):
    if os.name == "nt" and value.startswith("/") and len(value) > 2 and value[2] == "/":
        value = value[1].upper() + ":" + value[2:]
    return pathlib.Path(value)
base = native(os.environ["RUNNER_TEMP"])
(base / "installer-call.json").write_text(json.dumps(args))
if os.environ.get("INSTALL_FAIL") == "1":
    sys.exit(24)
sdk = native(args[args.index("--sdk-root") + 1])
for name in ["cmdline-tools/latest/bin/sdkmanager", "platform-tools/adb"]:
    target = sdk / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("#!/usr/bin/env bash\nexit 0\n")
    target.chmod(0o755)
''', encoding="utf-8", newline="\n")
        python = self.bin / "python3"
        python.write_text("#!/usr/bin/env bash\nexec " + shlex.quote(Path(sys.executable).as_posix()) +
                          " " + shlex.quote(self.fixture.as_posix()) + ' "$@"\n', newline="\n")
        python.chmod(0o755)
        # The pre-fix bootstrap must fail locally; tests can never download archives.
        for name in ["curl", "unzip"]:
            blocked = self.bin / name
            blocked.write_text("#!/usr/bin/env bash\nexit 37\n", newline="\n")
            blocked.chmod(0o755)
        self.env_file = self.base / "github-env"
        self.path_file = self.base / "github-path"
        runner_temp = self.base.as_posix()
        if os.name == "nt":
            runner_temp = "/" + runner_temp[0].lower() + runner_temp[2:]
        self.environment = {**os.environ, "RUNNER_TEMP": runner_temp,
                            "GITHUB_ENV": self.env_file.as_posix(), "GITHUB_PATH": self.path_file.as_posix(),
                            "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
                            "MSYS2_ARG_CONV_EXCL": "*", "TEST_BIN": self.bin.as_posix(),
                            "TEST_BOOTSTRAP": (ROOT / "tools/bootstrap-android-sdk.sh").as_posix()}

    @staticmethod
    def native(value):
        if os.name == "nt" and value.startswith("/") and len(value) > 2 and value[2] == "/":
            value = value[1].upper() + ":" + value[2:]
        return Path(value)

    def run_bootstrap(self, *args, **environment):
        return subprocess.run([BASH, "-c", 'export PATH="$(cd "$TEST_BIN" && pwd):$PATH"; source "$TEST_BOOTSTRAP" "$@"', "bootstrap-test", *args],
                              env={**self.environment, **environment}, capture_output=True, text=True, timeout=20)

    def test_selected_profile_uses_locked_installer_and_exports_isolated_sdk(self):
        result = self.run_bootstrap("emulator35")
        self.assertEqual(0, result.returncode, result.stderr)
        args = json.loads((self.base / "installer-call.json").read_text())
        self.assertEqual("install", args[1])
        self.assertEqual("emulator35", args[args.index("--profile") + 1])
        self.assertIn("--accept-licenses", args)
        sdk_value = args[args.index("--sdk-root") + 1]
        sdk = self.native(sdk_value)
        cache = self.native(args[args.index("--cache-dir") + 1])
        self.assertTrue(sdk.is_dir())
        self.assertFalse(cache.exists())
        self.assertIn("ANDROID_HOME=" + sdk_value, self.env_file.read_text())
        self.assertIn("ANDROID_SDK_ROOT=" + sdk_value, self.env_file.read_text())
        for path in ["cmdline-tools/latest/bin", "platform-tools", "emulator"]:
            self.assertIn(sdk_value + "/" + path, self.path_file.read_text())

    def test_failed_install_cleans_owned_download_cache_and_does_not_export_sdk(self):
        result = self.run_bootstrap("build", INSTALL_FAIL="1")
        self.assertEqual(24, result.returncode, result.stderr)
        args = json.loads((self.base / "installer-call.json").read_text())
        self.assertFalse(self.native(args[args.index("--cache-dir") + 1]).exists())
        self.assertFalse(self.env_file.exists())
        self.assertFalse(self.path_file.exists())

    def test_unknown_profile_and_extra_arguments_are_rejected_before_install(self):
        for args in [("unreviewed",), ("build", "extra")]:
            with self.subTest(args=args):
                result = self.run_bootstrap(*args)
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertFalse((self.base / "installer-call.json").exists())


if __name__ == "__main__":
    unittest.main()
