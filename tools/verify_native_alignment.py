"""Verify packaged Android native ABIs, ELF alignment, and APK ZIP alignment.

No third-party packages or Android SDK are required. Malformed or empty inputs
fail rather than being mistaken for a successful compatibility check. For AABs,
this inspects native ELF payloads; installable APK ZIP alignment is checked on APKs.
"""
import argparse
import struct
import sys
import zipfile
from pathlib import Path

PAGE_SIZE = 16384
ABI_MACHINES = {"arm64-v8a": 183, "x86_64": 62}


def inspect_elf(data, expected_machine=None):
    errors = []
    if (
        len(data) < 64
        or data[:4] != b"\x7fELF"
        or data[4:7] != bytes([2, 1, 1])
    ):
        return ["expected a current little-endian ELF64 library"]
    object_type, machine, version = struct.unpack_from("<HHI", data, 16)
    header_size = struct.unpack_from("<H", data, 52)[0]
    if object_type != 3 or version != 1 or header_size != 64:
        return ["invalid ELF shared-object header"]
    if machine not in ABI_MACHINES.values():
        errors.append(f"unsupported ELF machine {machine}")
    elif expected_machine is not None and machine != expected_machine:
        errors.append(f"ELF machine {machine} does not match packaged ABI {expected_machine}")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if (
        entry_size != 56
        or not count
        or offset < header_size
        or offset + entry_size * count > len(data)
    ):
        return errors + ["invalid or truncated ELF program headers"]
    loads = 0
    for index in range(count):
        values = struct.unpack_from("<IIQQQQQQ", data, offset + index * entry_size)
        kind, _, file_offset, address, _, file_size, memory_size, alignment = values
        if kind != 1:
            continue
        loads += 1
        if alignment < PAGE_SIZE or alignment & (alignment - 1):
            errors.append(f"LOAD {index}: alignment {alignment} is not a power of two >= {PAGE_SIZE}")
        elif file_offset % alignment != address % alignment:
            errors.append(f"LOAD {index}: file and virtual offsets disagree modulo {alignment}")
        if file_size > memory_size or file_offset + file_size > len(data):
            errors.append(f"LOAD {index}: invalid segment bounds")
        if address + memory_size > 1 << 64:
            errors.append(f"LOAD {index}: virtual address range overflows ELF64")
    if not loads:
        errors.append("ELF has no loadable segments")
    return errors


def native_abi(name, suffix):
    parts = name.split("/")
    prefix = ["lib"] if suffix == ".apk" else ["base", "lib"]
    if (
        len(parts) != len(prefix) + 2
        or parts[:len(prefix)] != prefix
        or any(part in ("", ".", "..") or "\\" in part for part in parts)
    ):
        return None
    return parts[-2] if parts[-2] in ABI_MACHINES else None


def inspect_package(path):
    path = Path(path)
    suffix = path.suffix.lower()
    if suffix not in (".apk", ".aab"):
        return ["expected an APK or AAB package"], []
    errors = []
    names = []
    seen_names = set()
    found_abis = set()
    try:
        with zipfile.ZipFile(path) as package, open(path, "rb") as raw:
            for info in package.infolist():
                if not info.filename.endswith(".so"):
                    continue
                names.append(info.filename)
                if info.filename in seen_names:
                    errors.append(f"{info.filename}: duplicate native entry")
                seen_names.add(info.filename)
                abi = native_abi(info.filename, suffix)
                if abi is None:
                    errors.append(f"{info.filename}: unexpected native library path or ABI")
                    continue
                found_abis.add(abi)
                errors.extend(
                    f"{info.filename}: {issue}"
                    for issue in inspect_elf(package.read(info), ABI_MACHINES[abi])
                )
                if suffix == ".apk":
                    if info.compress_type != zipfile.ZIP_STORED:
                        errors.append(f"{info.filename}: native library must be uncompressed")
                    raw.seek(info.header_offset)
                    header = raw.read(30)
                    if len(header) != 30 or header[:4] != b"PK\x03\x04":
                        errors.append(f"{info.filename}: invalid ZIP local header")
                        continue
                    name_size, extra_size = struct.unpack_from("<HH", header, 26)
                    data_offset = info.header_offset + 30 + name_size + extra_size
                    if data_offset % PAGE_SIZE:
                        errors.append(f"{info.filename}: ZIP data offset {data_offset} is not 16KB aligned")
    except (OSError, zipfile.BadZipFile, RuntimeError, struct.error) as exc:
        return [f"cannot inspect package: {exc}"], names
    if not names:
        errors.append("package contains no native libraries")
    for abi in ABI_MACHINES:
        if abi not in found_abis:
            errors.append(f"package is missing {abi}")
    return errors, names


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path)
    args = parser.parse_args()
    errors, names = inspect_package(args.package)
    for error in errors:
        print(f"FAIL: {error}", file=sys.stderr)
    if errors:
        return 1
    checks = "ABI and ELF alignment" if args.package.suffix.lower() == ".aab" else "ABI, ELF and APK ZIP alignment"
    print(f"PASS: {len(names)} native libraries pass 16KB {checks} in {args.package.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
