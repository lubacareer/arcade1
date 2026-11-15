import struct
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

from tools.verify_native_alignment import inspect_elf, inspect_package


def elf64(alignment=16384, machine=183):
    data = bytearray(120)
    data[:4] = b"\x7fELF"
    data[4:7] = bytes([2, 1, 1])
    struct.pack_into("<HHI", data, 16, 3, machine, 1)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HHH", data, 52, 64, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 120, 120, alignment)
    return bytes(data)


class AlignmentTest(unittest.TestCase):
    def test_accepts_16kb_load_segments(self):
        self.assertEqual(inspect_elf(elf64()), [])

    def test_rejects_four_kb_alignment(self):
        self.assertTrue(inspect_elf(elf64(4096)))

    def test_rejects_non_elf(self):
        self.assertTrue(inspect_elf(b"garbage"))

    def test_rejects_truncated_program_header(self):
        self.assertTrue(inspect_elf(elf64()[:-1]))

    def test_rejects_no_load_segments(self):
        data = bytearray(elf64())
        struct.pack_into("<I", data, 64, 0)
        self.assertTrue(inspect_elf(bytes(data)))

    def test_rejects_incongruent_offsets(self):
        data = bytearray(elf64())
        struct.pack_into("<Q", data, 72, 100)
        self.assertTrue(inspect_elf(bytes(data)))

    def test_rejects_invalid_class(self):
        data = bytearray(elf64())
        data[4] = 9
        self.assertTrue(inspect_elf(bytes(data)))

    def test_rejects_wrong_machine_for_abi(self):
        self.assertTrue(inspect_elf(elf64(machine=62), expected_machine=183))

    def test_rejects_unsupported_machine(self):
        self.assertTrue(inspect_elf(elf64(machine=0)))

    def test_rejects_executable_instead_of_shared_object(self):
        data = bytearray(elf64())
        struct.pack_into("<H", data, 16, 2)
        self.assertTrue(inspect_elf(bytes(data)))

    def test_rejects_malformed_elf_header_size(self):
        data = bytearray(elf64())
        struct.pack_into("<H", data, 52, 0)
        self.assertTrue(inspect_elf(bytes(data)))

    def test_rejects_program_headers_inside_elf_header(self):
        data = bytearray(elf64())
        struct.pack_into("<Q", data, 32, 0)
        self.assertTrue(inspect_elf(bytes(data)))

    def test_rejects_segment_incongruence_at_declared_alignment(self):
        data = bytearray(elf64(alignment=32768))
        struct.pack_into("<Q", data, 80, 16384)
        self.assertTrue(inspect_elf(bytes(data)))


class PackageTest(unittest.TestCase):
    def inspect(self, entries, suffix=".aab"):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ("game" + suffix)
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(path, "w") as archive:
                    for name, content in entries:
                        archive.writestr(name, content)
            return inspect_package(path)[0]

    def entries(self, prefix="base/lib"):
        return [
            (f"{prefix}/arm64-v8a/libgdx.so", elf64(machine=183)),
            (f"{prefix}/x86_64/libgdx.so", elf64(machine=62)),
        ]

    def test_accepts_valid_bundle_native_payloads(self):
        self.assertEqual(self.inspect(self.entries()), [])

    def test_rejects_assets_masquerading_as_native_libs(self):
        self.assertTrue(self.inspect(self.entries("assets/cache")))

    def test_rejects_swapped_architecture_libraries(self):
        entries = self.entries()
        entries[0] = (entries[0][0], elf64(machine=62))
        self.assertTrue(self.inspect(entries))

    def test_rejects_duplicate_native_entries(self):
        entries = self.entries()
        self.assertTrue(self.inspect(entries + entries[:1]))

    def test_rejects_unknown_container_extension(self):
        self.assertTrue(self.inspect(self.entries(), ".zip"))


if __name__ == "__main__":
    unittest.main()
