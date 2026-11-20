"""Contract tests for scripts/qlc-release.py without Docker: packaging, reproducibility, secrets and safe apply."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('release', ROOT / 'scripts/qlc-release.py')
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)

bspec = importlib.util.spec_from_file_location('backup', ROOT / 'scripts/qlc-backup.py')
b = importlib.util.module_from_spec(bspec)
bspec.loader.exec_module(b)


def repack_zip(source_zip, dest_zip, transform):
    with zipfile.ZipFile(source_zip, 'r') as zin, zipfile.ZipFile(dest_zip, 'w', compression=zipfile.ZIP_DEFLATED) as zout:
        entries = [(info, zin.read(info)) for info in zin.infolist()]
        for info, data in transform(entries):
            zout.writestr(info, data)


class ReleasePackageTests(unittest.TestCase):
    def test_reproducible_package_verify_inspect_and_backup_compatibility_without_git(self):
        with tempfile.TemporaryDirectory() as tmp:
            out1 = Path(tmp) / 'build1'
            out2 = Path(tmp) / 'build2'
            res1 = r.build_package(ROOT, out1, allow_dirty=True)
            res2 = r.build_package(ROOT, out2, allow_dirty=True)

            # Bit-for-bit reproducibility of ZIP and public manifest.
            self.assertEqual(res1['packageSha256'], res2['packageSha256'])
            self.assertEqual(res1['manifestSha256'], res2['manifestSha256'])

            zip_path = Path(res1['package'])
            checksums_path = Path(res1['checksums'])
            manifest = r.verify_package(zip_path, checksums_path)

            # Ensure forbidden paths are completely absent from the package.
            for rel in manifest['files']:
                self.assertFalse(rel.startswith('.git'))
                self.assertNotEqual(rel, '.env')
                self.assertFalse(rel.startswith('data/'))
                self.assertFalse(rel.startswith('docs/validation/'))
                self.assertFalse(rel.startswith('frontend/src-tauri/'))
                self.assertFalse(rel.startswith('backend/src/test/'))
                self.assertFalse(rel.endswith('.spec.ts'))
                self.assertFalse(rel.endswith('.key'))

            # Extract into a fresh directory with NO .git and verify without git available.
            extracted = Path(tmp) / 'extracted'
            with zipfile.ZipFile(zip_path, 'r') as archive:
                archive.extractall(extracted)
            install_dir = extracted / f"qlcstream-{manifest['version']}"
            self.assertFalse((install_dir / '.git').exists())

            with patch.object(r, 'git_run', side_effect=AssertionError('git should not be called')):
                inspected = r.inspect_installation(install_dir)
                verified = r.verify_directory(install_dir)
            self.assertEqual(inspected['source'], 'release-package')
            self.assertTrue(inspected['manifestPresent'])
            self.assertEqual(inspected['version'], manifest['version'])
            self.assertEqual(inspected['schemaVersion'], manifest['schemaVersion'])
            self.assertEqual(inspected['commit'], manifest['commit'])
            self.assertEqual(verified['compatibility'], manifest['compatibility'])

            # Backup identity/compatibility must work in the extracted package without git
            # and match the compatibility map computed on the package.
            with patch.object(b, 'run', side_effect=AssertionError('git should not be called')):
                backup_id = b.source_identity(install_dir)
            self.assertEqual(backup_id['commit'], manifest['commit'])
            self.assertEqual(backup_id['releaseVersion'], manifest['version'])
            self.assertEqual(backup_id['compatibility'], manifest['compatibility'])

    def test_verify_rejects_corrupted_checksum_tampered_file_forbidden_env_and_secret(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / 'build'
            res = r.build_package(ROOT, out, allow_dirty=True)
            zip_path = Path(res['package'])
            checksums_path = Path(res['checksums'])
            version = res['version']

            # 1. Corrupted checksum file rejected.
            bad_sums = Path(tmp) / 'bad.sha256'
            bad_sums.write_text('0' * 64 + f'  {zip_path.name}\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, '^CHECKSUM_DIVERGENTE$'):
                r.verify_package(zip_path, bad_sums)

            # 2. Tampered file inside ZIP rejected.
            tampered = Path(tmp) / 'tampered.zip'
            def tamper_readme(entries):
                return [(info, b'tampered\n' if info.filename.endswith('/README.md') else data) for info, data in entries]
            repack_zip(zip_path, tampered, tamper_readme)
            with self.assertRaisesRegex(ValueError, '^ARQUIVO_CORROMPIDO$'):
                r.verify_package(tampered)

            # 3. Injected .env rejected.
            with_env = Path(tmp) / 'with-env.zip'
            def add_env(entries):
                info = zipfile.ZipInfo(f'qlcstream-{version}/.env')
                return [*entries, (info, b'TMDB_API_TOKEN=secret\n')]
            repack_zip(zip_path, with_env, add_env)
            with self.assertRaisesRegex(ValueError, '^ARQUIVO_PROIBIDO$'):
                r.verify_package(with_env)

            # 4. Injected private key secret rejected.
            with_secret = Path(tmp) / 'with-secret.zip'
            def add_secret(entries):
                return [
                    (info, b'-----BEGIN PRIVATE KEY-----\nabc\n' if info.filename.endswith('/README.md') else data)
                    for info, data in entries
                ]
            repack_zip(zip_path, with_secret, add_secret)
            with self.assertRaisesRegex(ValueError, '^SEGREDO_DETECTADO$'):
                r.verify_package(with_secret)

    def test_apply_preserves_user_env_and_data_cleans_obsolete_and_refuses_git_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / 'build'
            res = r.build_package(ROOT, out, allow_dirty=True)
            zip_path = Path(res['package'])
            checksums_path = Path(res['checksums'])

            # Prepare an existing extracted installation with user .env, data/, and an obsolete file from previous manifest.
            target = Path(tmp) / 'installed'
            r.apply_package(zip_path, target, checksums_path)

            env_file = target / '.env'
            env_file.write_text('TMDB_API_TOKEN=fictional_user_value\nMEDIA_HOST_PATH=./data/media\n', encoding='utf-8')
            key_file = target / 'data/integration-secrets/integration-settings.key'
            key_file.parent.mkdir(parents=True, exist_ok=True)
            key_file.write_bytes(b'fictional-key-bytes\n')
            media_file = target / 'data/media/incoming/owned.txt'
            media_file.parent.mkdir(parents=True, exist_ok=True)
            media_file.write_bytes(b'owned-media-bytes\n')

            # Simulate an obsolete file from a previous manifest to confirm cleanup without touching user files.
            obsolete_file = target / 'docs/old-obsolete-doc.md'
            obsolete_file.write_text('old doc\n', encoding='utf-8')
            manifest_path = target / 'RELEASE-MANIFEST.json'
            old_manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
            old_manifest['files']['docs/old-obsolete-doc.md'] = hashlib.sha256(b'old doc\n').hexdigest()
            manifest_path.write_text(json.dumps(old_manifest, sort_keys=True), encoding='utf-8')

            applied = r.apply_package(zip_path, target, checksums_path)
            self.assertEqual(applied['status'], 'applied')
            self.assertEqual(applied['removedObsoleteCount'], 1)
            self.assertFalse(obsolete_file.exists())
            # User .env, key and media must be preserved byte-for-byte.
            self.assertEqual(env_file.read_text(encoding='utf-8'),
                             'TMDB_API_TOKEN=fictional_user_value\nMEDIA_HOST_PATH=./data/media\n')
            self.assertEqual(key_file.read_bytes(), b'fictional-key-bytes\n')
            self.assertEqual(media_file.read_bytes(), b'owned-media-bytes\n')

            # Applying onto a git checkout must be refused.
            git_dir = Path(tmp) / 'git-checkout'
            (git_dir / '.git').mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, '^DESTINO_GIT_NAO_SUPORTADO$'):
                r.apply_package(zip_path, git_dir, checksums_path)

            # Applying onto an unmanaged non-empty directory must be refused without mutating it.
            unmanaged = Path(tmp) / 'unmanaged'
            unmanaged.mkdir()
            stray = unmanaged / 'custom-file.txt'
            stray.write_text('keep untouched\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, '^DESTINO_NAO_GERENCIADO$'):
                r.apply_package(zip_path, unmanaged, checksums_path)
            self.assertEqual([p.name for p in unmanaged.iterdir()], ['custom-file.txt'])
            self.assertEqual(stray.read_text(encoding='utf-8'), 'keep untouched\n')

            # Packaging without --allow-dirty refuses a dirty working tree.
            with patch.object(r, 'git_run', return_value=b' M README.md\n'):
                with self.assertRaisesRegex(ValueError, '^ARVORE_SUJA$'):
                    r.collect_release_files(ROOT, allow_dirty=False)

    @unittest.skipUnless(os.name == 'posix', 'POSIX symlink rejection contract')
    def test_verify_dir_and_apply_reject_symlinked_root_and_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / 'build'
            res = r.build_package(ROOT, out, allow_dirty=True)
            zip_path = Path(res['package'])
            checksums_path = Path(res['checksums'])

            real_dir = Path(tmp) / 'real'
            r.apply_package(zip_path, real_dir, checksums_path)
            link_dir = Path(tmp) / 'link-dir'
            link_dir.symlink_to(real_dir)
            with self.assertRaisesRegex(ValueError, '^LINK_NAO_SUPORTADO$'):
                r.verify_directory(link_dir)
            with self.assertRaisesRegex(ValueError, '^LINK_NAO_SUPORTADO$'):
                r.apply_package(zip_path, link_dir, checksums_path)

            victim = Path(tmp) / 'outside.json'
            victim.write_text('do-not-overwrite\n', encoding='utf-8')
            (real_dir / 'RELEASE-MANIFEST.json').unlink()
            (real_dir / 'RELEASE-MANIFEST.json').symlink_to(victim)
            with self.assertRaisesRegex(ValueError, '^LINK_NAO_SUPORTADO$'):
                r.apply_package(zip_path, real_dir, checksums_path)
            self.assertEqual(victim.read_text(encoding='utf-8'), 'do-not-overwrite\n')


if __name__ == '__main__':
    unittest.main()
