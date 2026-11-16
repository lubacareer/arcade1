"""Small synthetic archives exercise installation without fetching or executing SDK code."""
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

from tools.android_sdk import SdkError, install_profile, load_lock, verify_sdk


class LockedSdkTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="locked sdk ")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.repo = self.base / "tools"
        self.repo.mkdir()
        self.sdk = self.base / "sdk"
        self.cache = self.base / "cache"
        self.downloads = []
        self.blobs = {}
        self.license_text = "Pinned fixture license.\n"
        self.license_hash = hashlib.sha1(self.license_text.encode()).hexdigest()
        self.lock = {
            "schema_version": 1, "host": "linux-x86_64",
            "license_hashes": {"android-sdk-license": [self.license_hash]},
            "profiles": {"build": ["cmdline-tools;22.0", "platform-tools"],
                         "emulator26": ["cmdline-tools;22.0", "platform-tools", "emulator",
                                        "system-images;android-26;google_apis;x86_64"]},
            "packages": [],
        }
        self.add_package("cmdline-tools;22.0", "22.0", "cmdline-tools", {
            "bin/sdkmanager": (b"#!/bin/sh\nexit 0\n", 0o755),
            "bin/avdmanager": (b"#!/bin/sh\nexit 0\n", 0o755),
            "lib/tool.jar": (b"fixture", 0o644),
        })
        self.add_package("platform-tools", "37.0.1", "platform-tools", {"adb": (b"fixture adb", 0o755)})
        self.add_package("emulator", "37.2.13", "emulator", {"emulator": (b"fixture emulator", 0o755)})
        self.add_package("system-images;android-26;google_apis;x86_64", "16", "x86_64",
                         {"system.img": (b"fixture system image", 0o644)}, image=True)
        self.lock_path = self.repo / "android-sdk-lock.json"
        (self.repo / "sdkmanager-pinned.sh").write_text("#!/bin/sh\nexit 0\n", newline="\n")
        self.write_lock()

    def add_package(self, identifier, revision, archive_root, files, image=False):
        type_details = '<type-details xsi:type="generic:genericDetailsType"/>'
        if image:
            type_details = ('<type-details xsi:type="sysimg:sysImgDetailsType"><api-level>26</api-level>'
                            '<tag><id>google_apis</id><display>Google APIs</display></tag>'
                            '<vendor><id>google</id><display>Google Inc.</display></vendor>'
                            '<abi>x86_64</abi><abis>x86_64</abis></type-details>')
        fields = ["major", "minor", "micro"]
        revision_xml = ''.join(f'<{key}>{value}</{key}>' for key, value in zip(fields, revision.split('.')))
        xml = (f'<common:repository xmlns:common="http://schemas.android.com/repository/android/common/02" '
               f'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
               f'xmlns:generic="http://schemas.android.com/repository/android/generic/02" '
               f'xmlns:sysimg="http://schemas.android.com/sdk/android/repo/sys-img2/05">'
               f'<license id="android-sdk-license" type="text">{self.license_text}</license>'
               f'<localPackage path="{identifier}" obsolete="false">{type_details}'
               f'<revision>{revision_xml}</revision><display-name>Fixture</display-name>'
               f'<uses-license ref="android-sdk-license"/></localPackage></common:repository>').encode()
        filename = identifier.replace(';', '-') + '.xml'
        metadata = self.repo / 'android-sdk-metadata' / filename
        metadata.parent.mkdir(exist_ok=True)
        metadata.write_bytes(xml)
        files = {**files, 'source.properties': (f'Pkg.Revision={revision}\n'.encode(), 0o644)}
        archive = self.make_zip({archive_root + '/' + name: value for name, value in files.items()})
        url = 'https://dl.google.com/android/repository/' + filename + '.zip'
        self.blobs[url] = archive
        self.lock['packages'].append({
            'id': identifier, 'revision': revision, 'url': url, 'size': len(archive),
            'sha256': hashlib.sha256(archive).hexdigest(), 'source_sha1': hashlib.sha1(archive).hexdigest(),
            'archive_root': archive_root, 'install_path': identifier.replace(';', '/'),
            'registration_file': 'android-sdk-metadata/' + filename,
            'registration_sha256': hashlib.sha256(xml).hexdigest(),
            'license_id': 'android-sdk-license', 'license_sha1': self.license_hash,
        })

    @staticmethod
    def make_zip(files):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            for name, (data, mode) in files.items():
                info = zipfile.ZipInfo(name)
                info.create_system = 3
                info.external_attr = (stat.S_IFREG | mode) << 16
                archive.writestr(info, data)
        return stream.getvalue()

    def write_lock(self):
        self.lock_path.write_text(json.dumps(self.lock), encoding='utf-8', newline='\n')

    def download(self, package, target):
        self.downloads.append(package['id'])
        target.write_bytes(self.blobs[package['url']])

    def install(self, profile='build'):
        return install_profile(self.lock_path, profile, self.sdk, self.cache,
                               downloader=self.download, accept_licenses=True)

    def replace_blob(self, identifier, blob):
        package = next(p for p in self.lock['packages'] if p['id'] == identifier)
        self.blobs[package['url']] = blob
        (self.cache / package['url'].rsplit('/', 1)[-1]).unlink(missing_ok=True)
        package.update(size=len(blob), sha256=hashlib.sha256(blob).hexdigest(), source_sha1=hashlib.sha1(blob).hexdigest())
        self.write_lock()

    def test_build_installs_only_selected_packages_and_registers_canonical_cli(self):
        self.install()
        self.assertEqual(self.lock['profiles']['build'], self.downloads)
        self.assertFalse((self.sdk / 'emulator').exists())
        cli = self.sdk / 'cmdline-tools/22.0'
        self.assertTrue((cli / 'bin/sdkmanager.unwrapped').is_file())
        self.assertTrue((self.sdk / 'cmdline-tools/latest/bin/avdmanager').is_file())
        self.assertEqual('cmdline-tools;22.0', ET.parse(cli / 'package.xml').getroot().find('localPackage').get('path'))
        receipt = verify_sdk(self.sdk)
        self.assertEqual(self.lock['profiles']['build'], receipt['packages'])
        self.assertIn(self.license_hash, (self.sdk / 'licenses/android-sdk-license').read_text())
        if os.name != 'nt':
            self.assertTrue((self.sdk / 'platform-tools/adb').stat().st_mode & stat.S_IXUSR)

    def test_image_registration_keeps_image_type_tag_abi_and_revision(self):
        self.install('emulator26')
        node = ET.parse(self.sdk / 'system-images/android-26/google_apis/x86_64/package.xml').getroot().find('localPackage')
        self.assertEqual('sysimg:sysImgDetailsType', node.find('type-details').get('{http://www.w3.org/2001/XMLSchema-instance}type'))
        self.assertEqual('google_apis', node.findtext('type-details/tag/id'))
        self.assertEqual('x86_64', node.findtext('type-details/abi'))
        self.assertEqual('16', node.findtext('revision/major'))
        self.assertEqual(self.lock['profiles']['emulator26'], self.downloads)

    def test_bad_archive_checksum_never_publishes_a_partial_sdk(self):
        package = self.lock['packages'][1]
        self.blobs[package['url']] = b'not the reviewed archive'
        with self.assertRaises(SdkError):
            self.install()
        self.assertFalse(self.sdk.exists())

    def test_wrong_size_fails_even_when_archive_digest_matches(self):
        self.lock['packages'][0]['size'] += 1
        self.write_lock()
        with self.assertRaises(SdkError):
            self.install()
        self.assertFalse(self.sdk.exists())

    def test_unapproved_profile_or_missing_license_consent_writes_nothing(self):
        with self.assertRaises(SdkError):
            self.install('unreviewed')
        with self.assertRaises(SdkError):
            install_profile(self.lock_path, 'build', self.sdk, self.cache, downloader=self.download)
        self.assertFalse(self.sdk.exists())
        self.assertEqual([], self.downloads)

    def test_archive_traversal_and_unexpected_root_are_rejected(self):
        for name in ['platform-tools/../../outside', '/absolute', 'other/adb', 'platform-tools/..\\outside']:
            with self.subTest(name=name):
                self.replace_blob('platform-tools', self.make_zip({name: (b'bad', 0o755)}))
                with self.assertRaises(SdkError):
                    self.install()
                self.assertFalse(self.sdk.exists())
                self.assertFalse((self.base / 'outside').exists())

    def test_archive_symlinks_are_rejected_before_extraction(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            entry = zipfile.ZipInfo('platform-tools/adb')
            entry.create_system = 3
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(entry, '../../outside')
        self.replace_blob('platform-tools', stream.getvalue())
        with self.assertRaises(SdkError):
            self.install()
        self.assertFalse(self.sdk.exists())

    def test_registration_checksum_and_package_identity_are_validated(self):
        package = self.lock['packages'][0]
        metadata = self.repo / package['registration_file']
        changed = metadata.read_bytes().replace(b'cmdline-tools;22.0', b'cmdline-tools;99.0')
        metadata.write_bytes(changed)
        with self.assertRaises(SdkError):
            load_lock(self.lock_path)
        package['registration_sha256'] = hashlib.sha256(changed).hexdigest()
        self.write_lock()
        with self.assertRaises(SdkError):
            load_lock(self.lock_path)

    def test_payload_revision_must_match_locked_registration(self):
        blob = self.make_zip({'platform-tools/source.properties': (b'Pkg.Revision=99.0\n', 0o644),
                              'platform-tools/adb': (b'bad', 0o755)})
        self.replace_blob('platform-tools', blob)
        with self.assertRaises(SdkError):
            self.install()
        self.assertFalse(self.sdk.exists())

    def test_registered_inventory_detects_modified_missing_and_added_payloads(self):
        self.install()
        adb = self.sdk / 'platform-tools/adb'
        original = adb.read_bytes()
        adb.write_bytes(b'tampered')
        with self.assertRaises(SdkError):
            verify_sdk(self.sdk)
        adb.write_bytes(original)
        adb.unlink()
        with self.assertRaises(SdkError):
            verify_sdk(self.sdk)
        adb.write_bytes(original)
        if os.name != 'nt': adb.chmod(0o755)
        (self.sdk / 'platform-tools/injected.so').write_bytes(b'bad')
        with self.assertRaises(SdkError):
            verify_sdk(self.sdk)

    def test_requested_packages_must_be_locked_and_installed_in_this_profile(self):
        self.install()
        verify_sdk(self.sdk, ['platform-tools'])
        for identifier in ['emulator', 'not-in-lock']:
            with self.subTest(identifier=identifier), self.assertRaises(SdkError):
                verify_sdk(self.sdk, [identifier])

    def test_registration_and_license_files_remain_integrity_protected(self):
        self.install()
        license_file = self.sdk / 'licenses/android-sdk-license'
        license_file.write_text('changed')
        with self.assertRaises(SdkError):
            verify_sdk(self.sdk)

    def test_existing_destination_is_preserved(self):
        self.sdk.mkdir()
        sentinel = self.sdk / 'preserve.txt'
        sentinel.write_text('existing data')
        with self.assertRaises(SdkError):
            self.install()
        self.assertEqual('existing data', sentinel.read_text())

    def test_duplicate_manifest_keys_and_non_https_sources_fail_closed(self):
        self.lock_path.write_text('{"schema_version":1,"schema_version":2}')
        with self.assertRaises(SdkError):
            load_lock(self.lock_path)
        self.lock['packages'][0]['url'] = 'http://dl.google.com/untrusted.zip'
        self.write_lock()
        with self.assertRaises(SdkError):
            load_lock(self.lock_path)


    def test_sdk_manager_root_cache_may_change_without_changing_payload(self):
        self.install()
        cache = self.sdk / '.knownPackages'
        cache.write_text('vendor cache')
        verify_sdk(self.sdk)
        cache.write_text('updated vendor cache')
        verify_sdk(self.sdk)

    def test_sdk_manager_cache_must_be_a_regular_non_executable_file(self):
        self.install()
        cache = self.sdk / '.knownPackages'
        cache.mkdir()
        with self.assertRaises(SdkError):
            verify_sdk(self.sdk)
        cache.rmdir()
        if os.name != 'nt':
            cache.write_text('executable cache')
            cache.chmod(0o755)
            with self.assertRaises(SdkError):
                verify_sdk(self.sdk)

    def test_root_cache_exception_never_excludes_package_payload(self):
        self.install()
        (self.sdk / 'platform-tools/.knownPackages').write_text('unapproved payload')
        with self.assertRaises(SdkError):
            verify_sdk(self.sdk)


if __name__ == '__main__':
    unittest.main()
