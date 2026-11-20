"""Contract tests for scripts/qlc-release.py without Docker: packaging, reproducibility, secrets and safe apply."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
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


def build_derived_candidate_zip(base_zip, output_dir, next_version, new_prowlarr_digest=None):
    base_manifest, file_bytes, _ = r.read_and_verify_zip(base_zip)
    old_version = base_manifest['version']
    next_files = dict(file_bytes)
    next_files['VERSION'] = (next_version + '\n').encode('utf-8')
    meta_path = 'backend/src/main/resources/release-metadata.properties'
    next_files[meta_path] = next_files[meta_path].decode('utf-8').replace(
        f'qlcstream.release.version={old_version}',
        f'qlcstream.release.version={next_version}',
    ).encode('utf-8')
    for path in ('backend/Dockerfile', 'frontend/Dockerfile', 'compose.yaml'):
        next_files[path] = next_files[path].decode('utf-8').replace(old_version, next_version).encode('utf-8')
    if new_prowlarr_digest:
        old_ref = base_manifest['integrations']['prowlarr']
        repo_tag, _, _ = old_ref.partition('@sha256:')
        next_files['compose.yaml'] = next_files['compose.yaml'].decode('utf-8').replace(
            old_ref, f'{repo_tag}@sha256:{new_prowlarr_digest}'
        ).encode('utf-8')
    old_notes = f'docs/releases/{old_version}.md'
    next_notes = f'docs/releases/{next_version}.md'
    next_files[next_notes] = next_files[old_notes].decode('utf-8').replace(old_version, next_version).encode('utf-8')
    del next_files[old_notes]

    meta = r.validate_release_consistency(next_files)
    commit = hashlib.sha256((next_version + ':' + base_manifest['commit']).encode()).hexdigest()[:40]
    files_hashes = {rel: r.sha256_bytes(data) for rel, data in sorted(next_files.items())}
    compat_hashes = {rel: h for rel, h in files_hashes.items() if r.is_runtime_compatibility_path(rel)}
    manifest = {
        'format': 1, 'version': meta['version'], 'channel': meta['channel'], 'schemaVersion': meta['schemaVersion'],
        'commit': commit, 'commitUtc': base_manifest['commitUtc'], 'workingTree': 'synthetic-candidate',
        'notes': meta['notes'], 'integrations': meta['integrations'],
        'files': files_hashes, 'compatibility': compat_hashes, 'excluded': list(r.EXCLUDED_DESCRIPTION),
    }
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    prefix = f'qlcstream-{next_version}'
    zip_path = output_dir / f'{prefix}.zip'
    sums_path = output_dir / f'{prefix}.sha256'
    manifest_bytes = (json.dumps(manifest, indent=2, sort_keys=True) + '\n').encode('utf-8')
    with zipfile.ZipFile(zip_path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for rel, data in sorted(next_files.items()):
            archive.writestr(f'{prefix}/{rel}', data)
        archive.writestr(f'{prefix}/RELEASE-MANIFEST.json', manifest_bytes)
    sums_path.write_text(f'{r.sha256_file(zip_path)}  {zip_path.name}\n', encoding='utf-8')
    return zip_path, sums_path, manifest


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

            # 5. Non-example POSTGRES_PASSWORD in .env.example rejected; empty and example value allowed.
            with_pg_secret = Path(tmp) / 'with-pg-secret.zip'
            def add_pg_secret(entries):
                return [
                    (info, data.replace(b'POSTGRES_PASSWORD=qlc_stream_local', b'POSTGRES_PASSWORD=private_real_pwd')
                     if info.filename.endswith('/.env.example') else data)
                    for info, data in entries
                ]
            repack_zip(zip_path, with_pg_secret, add_pg_secret)
            with self.assertRaisesRegex(ValueError, '^SEGREDO_DETECTADO$'):
                r.verify_package(with_pg_secret)
            r.scan_content_for_secrets('.env.example', b'POSTGRES_PASSWORD=\nTMDB_API_TOKEN=\n')
            r.scan_content_for_secrets('.env.example', b'POSTGRES_PASSWORD=qlc_stream_local\nTMDB_API_TOKEN=\n')
            with self.assertRaisesRegex(ValueError, '^SEGREDO_DETECTADO$'):
                r.scan_content_for_secrets('.env.example', b'POSTGRES_PASSWORD = private_real_pwd\n')

            # 6. Half-bumped compose.yaml (only backend updated, frontend still on old version) rejected.
            _, valid_files, _ = r.read_and_verify_zip(zip_path)
            half_bumped = dict(valid_files)
            half_bumped['compose.yaml'] = half_bumped['compose.yaml'].decode('utf-8').replace(
                f'QLCSTREAM_VERSION:-{version}', 'QLCSTREAM_VERSION:-0.0.9', 1
            ).encode('utf-8')
            with self.assertRaisesRegex(ValueError, '^VERSAO_DIVERGENTE$'):
                r.validate_release_consistency(half_bumped)

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
            obsolete_file.write_bytes(b'old doc\n')
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

    def test_clean_mode_reads_head_blobs_for_identical_hashes_across_lf_and_crlf_checkouts(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            lf_repo = base / 'clone-lf'
            crlf_repo = base / 'clone-crlf'
            subprocess.run(['git', 'clone', '--no-hardlinks', '-c', 'core.autocrlf=false', str(ROOT), str(lf_repo)],
                           check=True, capture_output=True)
            subprocess.run(['git', 'clone', '--no-hardlinks', '-c', 'core.autocrlf=true', str(ROOT), str(crlf_repo)],
                           check=True, capture_output=True)
            # Confirm working trees actually differ in line endings on disk.
            self.assertNotIn(b'\r\n', (lf_repo / 'README.md').read_bytes())
            self.assertIn(b'\r\n', (crlf_repo / 'README.md').read_bytes())

            res_lf = r.build_package(lf_repo, base / 'out-lf', allow_dirty=False)
            res_crlf = r.build_package(crlf_repo, base / 'out-crlf', allow_dirty=False)
            self.assertEqual(res_lf['workingTree'], 'clean')
            self.assertEqual(res_crlf['workingTree'], 'clean')
            self.assertEqual(res_lf['packageSha256'], res_crlf['packageSha256'])
            self.assertEqual(res_lf['manifestSha256'], res_crlf['manifestSha256'])

    def test_apply_preserves_previous_installation_on_extra_file_mid_failure_and_reports_matrix_changes(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            v1_res = r.build_package(ROOT, base / 'v1-out', allow_dirty=True)
            v1_zip, v1_sums, v1_ver = Path(v1_res['package']), Path(v1_res['checksums']), v1_res['version']
            v2_zip, v2_sums, _ = build_derived_candidate_zip(v1_zip, base / 'v2-out', '0.1.0-rc.2')
            v3_zip, v3_sums, _ = build_derived_candidate_zip(
                v1_zip, base / 'v3-out', '0.1.0-rc.3', new_prowlarr_digest='9' * 64
            )

            target = base / 'installed'
            r.apply_package(v1_zip, target, v1_sums)
            (target / '.env').write_text('TMDB_API_TOKEN=fictional\n', encoding='utf-8')
            (target / 'data').mkdir()
            (target / 'data/owned.txt').write_bytes(b'owned-bytes\n')
            v1_manifest_before = (target / 'RELEASE-MANIFEST.json').read_bytes()

            # Case 1: Extra unmanifested file under frontend/ must fail BEFORE modifying target.
            extra = target / 'frontend/extra-unexpected.txt'
            extra.write_text('stray file\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, '^ARQUIVO_EXTRA_NAO_PERMITIDO$'):
                r.apply_package(v2_zip, target, v2_sums)
            self.assertEqual((target / 'VERSION').read_text(encoding='utf-8').strip(), v1_ver)
            self.assertEqual((target / 'RELEASE-MANIFEST.json').read_bytes(), v1_manifest_before)
            extra.unlink()
            self.assertEqual(r.inspect_installation(target)['version'], v1_ver)

            # Case 2: Simulated failure midway through file replacement rolls back completely to v1.
            real_replace = r._replace_file
            calls = {'count': 0}

            def failing_replace(src, dst):
                calls['count'] += 1
                if calls['count'] == 4:
                    raise OSError('simulated write error')
                return real_replace(src, dst)

            with patch.object(r, '_replace_file', side_effect=failing_replace):
                with self.assertRaises(OSError):
                    r.apply_package(v2_zip, target, v2_sums)
            self.assertEqual(r.inspect_installation(target)['version'], v1_ver)
            self.assertEqual((target / 'RELEASE-MANIFEST.json').read_bytes(), v1_manifest_before)
            self.assertFalse((target / 'docs/releases/0.1.0-rc.2.md').exists())
            self.assertEqual((target / '.env').read_text(encoding='utf-8'), 'TMDB_API_TOKEN=fictional\n')
            self.assertEqual((target / 'data/owned.txt').read_bytes(), b'owned-bytes\n')

            # Case 3: Local file outside old_files colliding with a new package file is refused before writing.
            colliding_note = target / 'docs/releases/0.1.0-rc.2.md'
            colliding_note.write_text('local operator notes — do not overwrite\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, '^COLISAO_ARQUIVO_LOCAL$'):
                r.apply_package(v2_zip, target, v2_sums)
            self.assertEqual(colliding_note.read_text(encoding='utf-8'), 'local operator notes — do not overwrite\n')
            self.assertEqual((target / 'VERSION').read_text(encoding='utf-8').strip(), v1_ver)
            self.assertEqual((target / 'RELEASE-MANIFEST.json').read_bytes(), v1_manifest_before)
            self.assertEqual(r.inspect_installation(target)['version'], v1_ver)
            colliding_note.unlink()

            # Case 4: Failure during apply followed by failure during rollback preserves the rollback copy and reports its path.
            calls = {'replace': 0, 'restore': 0}
            real_copy2 = shutil.copy2

            def failing_replace_double(src, dst):
                calls['replace'] += 1
                if calls['replace'] == 4:
                    raise OSError('simulated apply failure')
                return real_replace(src, dst)

            def failing_restore_copy2(src, dst, *args, **kwargs):
                # Allow initial backup copies into .qlcstream-rollback-*; fail when restoring back into target.
                if '.qlcstream-rollback-' in str(src):
                    calls['restore'] += 1
                    if calls['restore'] == 2:
                        raise OSError('simulated rollback failure')
                return real_copy2(src, dst, *args, **kwargs)

            with patch.object(r, '_replace_file', side_effect=failing_replace_double), \
                    patch.object(r.shutil, 'copy2', side_effect=failing_restore_copy2):
                with self.assertRaises(r.IncompleteRollbackError) as ctx:
                    r.apply_package(v2_zip, target, v2_sums)
            err = ctx.exception
            self.assertIn('REVERSAO_INCOMPLETA', str(err))
            self.assertIn(str(err.backup_dir), str(err))
            self.assertTrue(err.backup_dir.is_dir(), 'rollback directory must be preserved when restore fails')
            self.assertEqual((err.backup_dir / 'VERSION').read_text(encoding='utf-8').strip(), v1_ver)
            self.assertEqual((err.backup_dir / 'RELEASE-MANIFEST.json').read_bytes(), v1_manifest_before)
            # Clean up target back to v1 using the preserved rollback copy and confirm recovery succeeds.
            for item in err.backup_dir.rglob('*'):
                if item.is_file():
                    rel = item.relative_to(err.backup_dir)
                    dst = target / rel
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    real_copy2(item, dst)
            shutil.rmtree(err.backup_dir)
            self.assertEqual(r.inspect_installation(target)['version'], v1_ver)

            # Case 5: KeyboardInterrupt after replacing VERSION rolls back cleanly to v1 and cleans rollback dir only after verification.
            replaced_version = {'done': False}

            def interrupt_after_version(src, dst):
                if replaced_version['done']:
                    raise KeyboardInterrupt()
                real_replace(src, dst)
                if Path(dst).name == 'VERSION':
                    replaced_version['done'] = True

            with patch.object(r, '_replace_file', side_effect=interrupt_after_version):
                with self.assertRaises(KeyboardInterrupt):
                    r.apply_package(v2_zip, target, v2_sums)
            self.assertEqual((target / 'VERSION').read_text(encoding='utf-8').strip(), v1_ver)
            self.assertEqual((target / 'RELEASE-MANIFEST.json').read_bytes(), v1_manifest_before)
            self.assertEqual(r.inspect_installation(target)['version'], v1_ver)
            self.assertEqual(list(base.glob('.qlcstream-rollback-*')), [])

            # Case 6: KeyboardInterrupt after replacing VERSION followed by KeyboardInterrupt during rollback preserves backup_dir.
            replaced_version_2 = {'done': False}
            restore_interrupt_calls = {'count': 0}

            def interrupt_after_version_2(src, dst):
                if replaced_version_2['done']:
                    raise KeyboardInterrupt()
                real_replace(src, dst)
                if Path(dst).name == 'VERSION':
                    replaced_version_2['done'] = True

            def interrupt_during_restore(src, dst, *args, **kwargs):
                if '.qlcstream-rollback-' in str(src):
                    restore_interrupt_calls['count'] += 1
                    if restore_interrupt_calls['count'] == 2:
                        raise KeyboardInterrupt()
                return real_copy2(src, dst, *args, **kwargs)

            with patch.object(r, '_replace_file', side_effect=interrupt_after_version_2), \
                    patch.object(r.shutil, 'copy2', side_effect=interrupt_during_restore):
                with self.assertRaises(r.IncompleteRollbackError) as int_ctx:
                    r.apply_package(v2_zip, target, v2_sums)
            int_err = int_ctx.exception
            self.assertIn('REVERSAO_INCOMPLETA', str(int_err))
            self.assertIn(str(int_err.backup_dir), str(int_err))
            self.assertTrue(int_err.backup_dir.is_dir(), 'rollback directory must be preserved when rollback is interrupted')
            self.assertEqual((int_err.backup_dir / 'VERSION').read_text(encoding='utf-8').strip(), v1_ver)
            self.assertEqual((int_err.backup_dir / 'RELEASE-MANIFEST.json').read_bytes(), v1_manifest_before)
            # Recover target from preserved rollback directory and confirm v1 passes verify-dir.
            for item in int_err.backup_dir.rglob('*'):
                if item.is_file():
                    rel = item.relative_to(int_err.backup_dir)
                    dst = target / rel
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    real_copy2(item, dst)
            shutil.rmtree(int_err.backup_dir)
            self.assertEqual(r.inspect_installation(target)['version'], v1_ver)

            # Case 7: Successful upgrades report integrationsChanged accurately.
            applied_v2 = r.apply_package(v2_zip, target, v2_sums)
            self.assertEqual(applied_v2['integrationsChanged'], [])
            applied_v3 = r.apply_package(v3_zip, target, v3_sums)
            self.assertEqual(applied_v3['integrationsChanged'], ['prowlarr'])

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
