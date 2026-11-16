"""Install and verify the repository-locked Linux Android SDK without live SDK resolution.

Archives are checked before extraction. Package registration and accepted license
text come from reviewed repository files, never from live repository metadata.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

CONTROL = ".space-pong"
LOCK_NAME = "android-sdk-lock.json"
RECEIPT_NAME = "installed.json"
CLI_PACKAGE = "cmdline-tools;22.0"
CHUNK_SIZE = 1024 * 1024


class SdkError(RuntimeError):
    """A missing, unapproved or modified SDK input must not be used."""


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise SdkError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def _json(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=_unique_object)
    except (OSError, ValueError) as error:
        raise SdkError(f"Cannot read JSON {path}: {error}") from error


def _relative(value):
    if not isinstance(value, str) or not value or "\\" in value or ":" in value:
        raise SdkError(f"Unsafe relative path: {value!r}")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in ("", ".", "..") for part in value.split("/")):
        raise SdkError(f"Unsafe relative path: {value!r}")
    return path


def _inside(root, relative):
    path = Path(root).joinpath(*_relative(relative).parts)
    if not path.resolve().is_relative_to(Path(root).resolve()):
        raise SdkError(f"Path escapes SDK directory: {relative}")
    return path


def _hashes(path):
    sha256, sha1 = hashlib.sha256(), hashlib.sha1()
    size = 0
    with Path(path).open("rb") as source:
        while chunk := source.read(CHUNK_SIZE):
            size += len(chunk)
            sha256.update(chunk)
            sha1.update(chunk)
    return size, sha256.hexdigest(), sha1.hexdigest()


def _revision(value):
    if not isinstance(value, str) or not re.fullmatch(r"\d+(?:\.\d+){0,2}", value):
        raise SdkError(f"Invalid package revision: {value!r}")
    pieces = tuple(int(part) for part in value.split("."))
    return pieces + (0,) * (3 - len(pieces))


def _registration(lock_dir, package):
    path = _inside(lock_dir, package["registration_file"])
    data = path.read_bytes()
    if len(data) > 256 * 1024 or hashlib.sha256(data).hexdigest() != package["registration_sha256"]:
        raise SdkError(f"Registration checksum mismatch: {package['id']}")
    if b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper():
        raise SdkError("Registration must not define XML entities")
    try:
        root = ET.fromstring(data)
    except ET.ParseError as error:
        raise SdkError(f"Invalid registration XML: {package['id']}") from error
    locals_ = root.findall("localPackage")
    if len(locals_) != 1 or locals_[0].get("path") != package["id"]:
        raise SdkError(f"Registration package identity mismatch: {package['id']}")
    local = locals_[0]
    revision = local.find("revision")
    if revision is None:
        raise SdkError(f"Missing registration revision: {package['id']}")
    numbers = [revision.findtext(name, "0") for name in ("major", "minor", "micro")]
    if _revision(".".join(numbers)) != _revision(package["revision"]):
        raise SdkError(f"Registration revision mismatch: {package['id']}")
    if local.find("type-details") is None:
        raise SdkError(f"Missing registration type details: {package['id']}")
    license_id = package["license_id"]
    license_node = next((node for node in root.findall("license") if node.get("id") == license_id), None)
    license_ref = local.find("uses-license")
    if license_node is None or license_ref is None or license_ref.get("ref") != license_id:
        raise SdkError(f"Missing pinned license: {package['id']}")
    # Matches com.android.repository.api.License.getLicenseHash in pinned CLI 22.0.
    license_hash = hashlib.sha1((license_node.text or "").encode("utf-8")).hexdigest()
    if license_hash != package["license_sha1"]:
        raise SdkError(f"License text checksum mismatch: {package['id']}")
    return data


def load_lock(path):
    path = Path(path)
    lock = _json(path)
    try:
        if lock["schema_version"] != 1 or lock["host"] != "linux-x86_64":
            raise SdkError("Unsupported SDK lock schema or host")
        packages = lock["packages"]
        if not isinstance(packages, list) or not packages:
            raise SdkError("SDK package lock is empty")
        ids, destinations = set(), []
        for package in packages:
            identifier = package["id"]
            if not isinstance(identifier, str) or not re.fullmatch(r"[A-Za-z0-9_.;-]+", identifier):
                raise SdkError("Invalid SDK package ID")
            if identifier in ids:
                raise SdkError(f"Duplicate SDK package ID: {identifier}")
            ids.add(identifier)
            destination = str(_relative(package["install_path"]))
            if destination != identifier.replace(";", "/"):
                raise SdkError(f"Install path does not match SDK package ID: {identifier}")
            if any(destination.startswith(old + "/") or old.startswith(destination + "/") for old in destinations):
                raise SdkError("SDK package install directories overlap")
            destinations.append(destination)
            if len(_relative(package["archive_root"]).parts) != 1:
                raise SdkError("Archives must have one explicitly locked top-level directory")
            _relative(package["registration_file"])
            _relative(package["license_id"])
            _revision(package["revision"])
            url = urllib.parse.urlsplit(package["url"])
            if (url.scheme != "https" or url.netloc != "dl.google.com" or
                    not url.path.startswith("/android/repository/") or not url.path.endswith(".zip") or
                    url.query or url.fragment):
                raise SdkError(f"Unapproved SDK archive URL: {package['url']}")
            if not isinstance(package["size"], int) or package["size"] <= 0:
                raise SdkError("Invalid SDK archive size")
            for field, length in (("sha256", 64), ("registration_sha256", 64), ("source_sha1", 40), ("license_sha1", 40)):
                if not isinstance(package[field], str) or not re.fullmatch(r"[0-9a-f]{" + str(length) + "}", package[field]):
                    raise SdkError(f"Invalid {field}: {identifier}")
            if package["license_sha1"] not in lock["license_hashes"].get(package["license_id"], []):
                raise SdkError(f"License is not approved by lock: {identifier}")
            _registration(path.parent, package)
        for profile, selection in lock["profiles"].items():
            if (not re.fullmatch(r"[a-z0-9]+", profile) or not isinstance(selection, list) or
                    len(selection) != len(set(selection)) or not set(selection).issubset(ids) or
                    not {CLI_PACKAGE, "platform-tools"}.issubset(selection)):
                raise SdkError(f"Invalid SDK profile: {profile}")
    except (KeyError, TypeError, AttributeError, OSError, ValueError) as error:
        raise SdkError(f"Invalid SDK lock: {error}") from error
    return lock


class _GoogleOnlyRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        url = urllib.parse.urlsplit(new_url)
        if url.scheme != "https" or url.netloc != "dl.google.com":
            raise SdkError("SDK archive redirect left the approved HTTPS origin")
        return super().redirect_request(request, response, code, message, headers, new_url)


def _download(package, target):
    opener = urllib.request.build_opener(_GoogleOnlyRedirect())
    for attempt in range(3):
        try:
            with opener.open(package["url"], timeout=240) as source, target.open("wb") as output:
                received = 0
                while chunk := source.read(CHUNK_SIZE):
                    received += len(chunk)
                    if received > package["size"]:
                        raise SdkError(f"Archive exceeds locked size: {package['id']}")
                    output.write(chunk)
            return
        except (OSError, urllib.error.URLError):
            if attempt == 2:
                raise
            time.sleep(attempt + 1)


def _archive(package, cache, downloader):
    filename = Path(urllib.parse.urlsplit(package["url"]).path).name
    path = cache / filename
    if not path.exists():
        descriptor, temporary_name = tempfile.mkstemp(prefix=filename + ".", suffix=".part", dir=cache)
        os.close(descriptor)
        temporary = Path(temporary_name)
        try:
            downloader(package, temporary)
            _check_archive(temporary, package)
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)
    _check_archive(path, package)
    return path


def _check_archive(path, package):
    size, sha256, sha1 = _hashes(path)
    if size != package["size"] or sha256 != package["sha256"] or sha1 != package["source_sha1"]:
        raise SdkError(f"SDK archive size/checksum mismatch: {package['id']}")


def _extract(archive_path, package, destination):
    root = package["archive_root"]
    try:
        with zipfile.ZipFile(archive_path) as archive:
            entries, names = [], set()
            for entry in archive.infolist():
                raw = entry.filename.rstrip("/")
                parts = _relative(raw).parts
                if parts[0] != root:
                    raise SdkError(f"Unexpected archive root: {entry.filename}")
                if len(parts) == 1:
                    if not entry.is_dir():
                        raise SdkError("Archive root is not a directory")
                    continue
                relative = "/".join(parts[1:])
                if relative in names:
                    raise SdkError(f"Duplicate archive entry: {relative}")
                names.add(relative)
                mode = entry.external_attr >> 16
                kind = stat.S_IFMT(mode)
                if kind not in (0, stat.S_IFREG, stat.S_IFDIR) or stat.S_ISLNK(mode):
                    raise SdkError(f"Unsupported archive file type: {entry.filename}")
                entries.append((entry, relative, mode))
            if not entries:
                raise SdkError("Empty SDK archive")
            destination.mkdir(parents=True)
            for entry, relative, mode in entries:
                target = _inside(destination, relative)
                if entry.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(entry) as source, target.open("xb") as output:
                    shutil.copyfileobj(source, output, CHUNK_SIZE)
                # Preserve reviewed Unix executable bits, never setuid/setgid bits.
                target.chmod((mode & 0o777) or 0o644)
    except (zipfile.BadZipFile, OSError, RuntimeError) as error:
        if isinstance(error, SdkError):
            raise
        raise SdkError(f"Cannot extract {package['id']}: {error}") from error


def _properties(path):
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def _install_package(lock_path, package, archive, stage):
    destination = _inside(stage, package["install_path"])
    _extract(archive, package, destination)
    try:
        properties = _properties(destination / "source.properties")
        if _revision(properties.get("Pkg.Revision")) != _revision(package["revision"]):
            raise SdkError(f"Payload revision disagrees with lock: {package['id']}")
        if properties.get("Pkg.Path", package["id"]) != package["id"]:
            raise SdkError(f"Payload package path disagrees with lock: {package['id']}")
    except OSError as error:
        raise SdkError(f"Missing SDK source.properties: {package['id']}") from error
    (destination / "package.xml").write_bytes(_registration(lock_path.parent, package))


def _install_controls(lock_path, lock, stage):
    control = stage / CONTROL
    control.mkdir()
    shutil.copy2(lock_path, control / LOCK_NAME)
    for package in lock["packages"]:
        destination = _inside(control, package["registration_file"])
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(_inside(lock_path.parent, package["registration_file"]), destination)
    shutil.copy2(Path(__file__), control / "android_sdk.py")
    canonical = stage / "cmdline-tools/22.0/bin"
    (canonical / "sdkmanager").rename(canonical / "sdkmanager.unwrapped")
    latest = stage / "cmdline-tools/latest/bin"
    latest.mkdir(parents=True)
    for directory in (canonical, latest):
        shutil.copy2(lock_path.parent / "sdkmanager-pinned.sh", directory / "sdkmanager")
        (directory / "sdkmanager").chmod(0o755)
    # latest is only an action compatibility entry point; registration stays at canonical 22.0.
    (latest / "avdmanager").write_text(
        '#!/usr/bin/env bash\nset -euo pipefail\n'
        'python3 "${ANDROID_HOME:?}/.space-pong/android_sdk.py" verify --sdk-root "$ANDROID_HOME"\n'
        'exec "$ANDROID_HOME/cmdline-tools/22.0/bin/avdmanager" "$@"\n', encoding="utf-8", newline="\n")
    (latest / "avdmanager").chmod(0o755)


def _inventory(root):
    files = {}
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise SdkError(f"SDK inventory contains a symlink: {path.relative_to(root)}")
        relative = path.relative_to(root).as_posix()
        if relative == ".knownPackages":
            # Vendor queries refresh this non-code cache; package payloads stay fully pinned.
            metadata = path.stat()
            if (not stat.S_ISREG(metadata.st_mode) or metadata.st_size > 1024 * 1024 or
                    (os.name != "nt" and metadata.st_mode & 0o111)):
                raise SdkError("SDK root package cache must be a bounded non-executable regular file")
            continue
        if path.is_file():
            if relative == CONTROL + "/" + RECEIPT_NAME:
                continue
            size, digest, _ = _hashes(path)
            files[relative] = {"size": size, "sha256": digest,
                               "executable": bool(path.stat().st_mode & stat.S_IXUSR)}
    return files


def install_profile(lock_path, profile, sdk_root, cache_dir, *, downloader=None, accept_licenses=False):
    lock_path, sdk_root, cache = Path(lock_path).resolve(), Path(sdk_root).resolve(), Path(cache_dir).resolve()
    lock = load_lock(lock_path)
    if profile not in lock["profiles"]:
        raise SdkError(f"Unknown SDK profile: {profile}")
    if not accept_licenses:
        raise SdkError("Installing this locked SDK requires --accept-licenses")
    if sdk_root.exists() and (not sdk_root.is_dir() or any(sdk_root.iterdir())):
        raise SdkError("SDK destination must be absent or empty; existing SDKs are never replaced")
    if cache == sdk_root or cache.is_relative_to(sdk_root):
        raise SdkError("Archive cache must be outside the SDK destination")
    sdk_root.parent.mkdir(parents=True, exist_ok=True)
    cache.mkdir(parents=True, exist_ok=True)
    packages = {p["id"]: p for p in lock["packages"]}
    selection = lock["profiles"][profile]
    with tempfile.TemporaryDirectory(prefix=".space-pong-sdk-stage-", dir=sdk_root.parent) as temporary:
        stage = Path(temporary)
        for identifier in selection:
            package = packages[identifier]
            archive = _archive(package, cache, downloader or _download)
            _install_package(lock_path, package, archive, stage)
        _install_controls(lock_path, lock, stage)
        license_dir = stage / "licenses"
        license_dir.mkdir()
        selected_licenses = {}
        for identifier in selection:
            package = packages[identifier]
            selected_licenses.setdefault(package["license_id"], set()).add(package["license_sha1"])
        for license_id, hashes in selected_licenses.items():
            (license_dir / license_id).write_text("\n".join(sorted(hashes)) + "\n", encoding="utf-8", newline="\n")
        receipt = {"schema_version": 1, "profile": profile, "packages": selection,
                   "lock_sha256": _hashes(stage / CONTROL / LOCK_NAME)[1], "files": _inventory(stage)}
        (stage / CONTROL / RECEIPT_NAME).write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8", newline="\n")
        verify_sdk(stage)
        if sdk_root.exists():
            sdk_root.rmdir()  # Only an empty caller-provided destination can be removed.
        os.replace(stage, sdk_root)
    return receipt


def verify_sdk(sdk_root, expected_packages=()):
    root = Path(sdk_root).resolve()
    receipt = _json(root / CONTROL / RECEIPT_NAME)
    lock_path = root / CONTROL / LOCK_NAME
    lock = load_lock(lock_path)
    try:
        if (receipt["schema_version"] != 1 or receipt["lock_sha256"] != _hashes(lock_path)[1] or
                receipt["packages"] != lock["profiles"].get(receipt["profile"])):
            raise SdkError("SDK installation receipt does not match its repository lock/profile")
        installed = set(receipt["packages"])
        if not set(expected_packages).issubset(installed):
            raise SdkError("Requested SDK package is not locked and installed in this profile")
        expected = receipt["files"]
        if not isinstance(expected, dict) or not expected:
            raise SdkError("SDK inventory is empty")
        for relative in expected:
            _inside(root, relative)
        actual = _inventory(root)
        if set(actual) != set(expected):
            raise SdkError("SDK inventory has missing or unexpected files")
        for relative, value in actual.items():
            previous = expected[relative]
            if value["size"] != previous["size"] or value["sha256"] != previous["sha256"]:
                raise SdkError(f"SDK inventory checksum mismatch: {relative}")
            if os.name != "nt" and value["executable"] != previous["executable"]:
                raise SdkError(f"SDK executable permission changed: {relative}")
        packages = {p["id"]: p for p in lock["packages"]}
        for identifier in installed:
            package = packages[identifier]
            destination = _inside(root, package["install_path"])
            if (destination / "package.xml").read_bytes() != _registration(lock_path.parent, package):
                raise SdkError(f"SDK registration changed: {identifier}")
            accepted = (root / "licenses" / package["license_id"]).read_text(encoding="utf-8").splitlines()
            if package["license_sha1"] not in accepted:
                raise SdkError(f"SDK license is not accepted: {identifier}")
    except (OSError, KeyError, TypeError, ValueError) as error:
        raise SdkError(f"Invalid SDK installation: {error}") from error
    return receipt


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    install = commands.add_parser("install")
    install.add_argument("--lock", type=Path, default=Path(__file__).with_name(LOCK_NAME))
    install.add_argument("--profile", required=True)
    install.add_argument("--sdk-root", type=Path, required=True)
    install.add_argument("--cache-dir", type=Path, required=True)
    install.add_argument("--accept-licenses", action="store_true")
    for command in ("verify", "verify-packages"):
        subparser = commands.add_parser(command)
        subparser.add_argument("--sdk-root", type=Path, required=True)
        if command == "verify-packages":
            subparser.add_argument("packages", nargs="+")
    args = parser.parse_args(argv)
    try:
        if args.command == "install":
            if sys.platform != "linux" or os.uname().machine not in ("x86_64", "amd64"):
                raise SdkError("The locked production SDK targets Linux x86_64")
            receipt = install_profile(args.lock, args.profile, args.sdk_root, args.cache_dir,
                                      accept_licenses=args.accept_licenses)
            print(f"Installed verified SDK profile {receipt['profile']} ({len(receipt['packages'])} packages)")
        else:
            verify_sdk(args.sdk_root, getattr(args, "packages", ()))
        return 0
    except (SdkError, OSError) as error:
        print(f"SDK verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
