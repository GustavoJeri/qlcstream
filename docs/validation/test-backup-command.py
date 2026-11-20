"""Command contracts without Docker: mount identity, private logging, stop metadata and release manifests."""
import hashlib
import importlib.util
import json
import os
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('backup', ROOT / 'scripts/qlc-backup.py')
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)

rspec = importlib.util.spec_from_file_location('release', ROOT / 'scripts/qlc-release.py')
r = importlib.util.module_from_spec(rspec)
rspec.loader.exec_module(r)

SAMPLE_COMPOSE = (
    'services:\n'
    '  setup-directories:\n'
    '    image: alpine:3.23@sha256:' + 'a' * 64 + '\n'
    '  database:\n'
    '    image: postgres:18.6-alpine@sha256:' + 'b' * 64 + '\n'
    '  backend:\n'
    '    build:\n'
    '      context: ./backend\n'
    '      args:\n'
    '        QLCSTREAM_VERSION: ${QLCSTREAM_VERSION:-0.1.0-rc.1}\n'
    '  frontend:\n'
    '    build:\n'
    '      context: ./frontend\n'
    '      args:\n'
    '        QLCSTREAM_VERSION: ${QLCSTREAM_VERSION:-0.1.0-rc.1}\n'
    '  prowlarr:\n'
    '    image: lscr.io/linuxserver/prowlarr:2.7.0.5723-ls168@sha256:' + 'c' * 64 + '\n'
    '  qbittorrent:\n'
    '    image: qbittorrentofficial/qbittorrent-nox:5.3.0-2@sha256:' + 'd' * 64 + '\n'
)


def create_synthetic_package(folder, commit='a' * 40, version='0.1.0-rc.1'):
    root = Path(folder)
    notes = '\n'.join(['# Release', *r.REQUIRED_NOTE_MARKERS]) + '\n'
    files = {
        'VERSION': (version + '\n').encode('utf-8'),
        'compose.yaml': SAMPLE_COMPOSE.replace('0.1.0-rc.1', version).encode('utf-8'),
        'backend/Dockerfile': f'FROM scratch\nARG QLCSTREAM_VERSION={version}\n'.encode('utf-8'),
        'frontend/Dockerfile': f'FROM scratch\nARG QLCSTREAM_VERSION={version}\n'.encode('utf-8'),
        'backend/pom.xml': b'<project/>',
        'backend/src/main/resources/release-metadata.properties': (
            f'qlcstream.release.version={version}\n'
            f'qlcstream.release.channel={"candidate" if "-" in version else "release"}\n'
            'qlcstream.release.schema-version=V1\n'
        ).encode('utf-8'),
        'backend/src/main/resources/db/migration/V1__init.sql': b'SELECT 1;\n',
        'frontend/package.json': b'{"name":"frontend"}\n',
        f'docs/releases/{version}.md': notes.encode('utf-8'),
    }
    hashes = {}
    for rel, data in files.items():
        target = root / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        hashes[rel] = hashlib.sha256(data).hexdigest()
    compat = {k: v for k, v in hashes.items() if b.is_runtime_compatibility_path(k)}
    manifest = {
        'format': 1,
        'version': version,
        'channel': 'candidate' if '-' in version else 'release',
        'schemaVersion': 'V1',
        'commit': commit,
        'integrations': r.parse_compose_images(files['compose.yaml'].decode('utf-8')),
        'files': hashes,
        'compatibility': compat,
    }
    (root / 'RELEASE-MANIFEST.json').write_text(json.dumps(manifest, sort_keys=True), encoding='utf-8')
    return root, manifest


def bind(source, target='/config'):
    return {'Type': 'bind', 'Source': source, 'Destination': target}


class BackupCommandTests(unittest.TestCase):
    @unittest.skipUnless(os.name == 'posix', 'POSIX executable lookup regression')
    def test_restore_resolves_host_docker_before_forwarding_container_path(self):
        with tempfile.TemporaryDirectory() as folder:
            executable = Path(folder) / 'docker'
            executable.write_text('#!/bin/sh\nprintf "%s" "$PATH"\n')
            executable.chmod(0o700)
            with patch.dict(os.environ, {'PATH': folder}):
                output = b.docker('create', '--env', 'PATH', environment={'PATH': '/container-only/bin'})
            self.assertEqual(output, b'/container-only/bin')

    def test_every_private_action_disables_daemon_logging(self):
        for action in ('create', 'verify', 'metadata', 'extract', 'dump'):
            with self.subTest(action=action), patch.object(b, 'docker', return_value=b'private-result') as call:
                self.assertEqual(b.private(action, 'vault', 'keys', {}), b'private-result')
                args = call.call_args.args
                self.assertEqual(args[args.index('--log-driver') + 1], 'none')
                self.assertIn('--rm', args)

    def test_bind_identity_uses_source_and_detects_parent_child_overlap(self):
        chosen = bind('/synthetic/profiles/prowlarr')
        for path in ('/synthetic/profiles/prowlarr', '/synthetic/profiles',
                     '/synthetic/profiles/prowlarr/database'):
            self.assertTrue(b.mounts_overlap(chosen, bind(path, '/elsewhere')))
            self.assertTrue(b.mounts_overlap(bind(path, '/elsewhere'), chosen))
        self.assertFalse(b.mounts_overlap(chosen, bind('/synthetic/profiles/prowlarr-other')))
        self.assertFalse(b.mounts_overlap(chosen, bind('/unrelated', '/config')))
        self.assertTrue(b.mounts_overlap(bind(r'C:\Synthetic\Profile'), bind('c:/synthetic/profile/db')))

    def test_volume_names_and_underlying_bind_sources_both_detect_use(self):
        volume = {'Type': 'volume', 'Name': 'fictional-volume', 'Source': '/volumes/fictional/_data'}
        self.assertTrue(b.mounts_overlap(volume, dict(volume, Destination='/other')))
        self.assertTrue(b.mounts_overlap(volume, bind('/volumes/fictional/_data/child')))
        self.assertFalse(b.mounts_overlap(volume, {'Type': 'tmpfs', 'Source': ''}))

    def test_inspection_requests_only_mounts_and_refuses_an_external_writer(self):
        chosen = bind('/synthetic/profile')
        returned = (json.dumps([bind('/synthetic/profile', '/different')]) + '\nnull\n').encode()
        with patch.object(b, 'docker', side_effect=[b'writer\nempty\n', returned]) as call:
            with self.assertRaisesRegex(ValueError, '^VOLUME_EM_USO$'):
                b.require_idle_mounts([chosen])
            self.assertEqual(call.call_args_list[1].args,
                             ('inspect', '--format', '{{json .Mounts}}', 'writer', 'empty'))
        with patch.object(b, 'docker', side_effect=[b'reader\n', b'null\n']):
            b.require_idle_mounts([chosen])

    def test_subpaths_are_refused_before_being_collapsed_to_a_volume_name(self):
        for key in ('Subpath', 'SubPath'):
            c = {'Mounts': [{'Type': 'volume', 'Name': 'fixture', 'Source': '/volumes/fixture/_data',
                             'Destination': '/data'}],
                 'HostConfig': {'Mounts': [{'Target': '/data/', 'VolumeOptions': {key: 'selected'}}]}}
            with self.assertRaisesRegex(ValueError, '^SUBDIRETORIO_DE_VOLUME_NAO_SUPORTADO$'):
                b.mount(c, '/data')

    def test_stop_metadata_preserves_explicit_timeout_and_signal(self):
        self.assertEqual(b.stop_options({'stopTimeout': 1800, 'stopSignal': 'SIGINT'}),
                         ['--stop-timeout', '1800', '--stop-signal', 'SIGINT'])
        self.assertEqual(b.stop_options({'stopTimeout': None, 'stopSignal': None}), [])
        self.assertEqual(b.stop_options({'stopTimeout': -1, 'stopSignal': None}), ['--stop-timeout', '-1'])

    def test_incomplete_or_invalid_stop_metadata_is_refused_during_preflight(self):
        for value in ({}, {'stopTimeout': 1800}, {'stopSignal': 'SIGTERM'},
                      {'stopTimeout': True, 'stopSignal': None}, {'stopTimeout': -2, 'stopSignal': None}):
            with self.assertRaisesRegex(ValueError, '^BACKUP_INCOMPLETO$'):
                b.stop_options(value)
        manifest = {'services': {s: {'stopTimeout': None, 'stopSignal': None} for s in b.SERVICES}}
        del manifest['services']['qbittorrent']['stopTimeout']
        with patch.object(b, 'exists', return_value=True), patch.object(b, 'compatibility_request', return_value={'compatibility': {}}), patch.object(b, 'private', return_value=json.dumps(manifest).encode()):
            with self.assertRaisesRegex(ValueError, '^BACKUP_INCOMPLETO$'):
                b.metadata(type('Args', (), {'vault': 'fixture', 'key_vault': 'key'})())

    def test_release_package_provides_identity_and_compatibility_without_git(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest = create_synthetic_package(folder)
            # Runtime user files (.env, data/) must be allowed without breaking manifest checks.
            (root / '.env').write_text('TMDB_API_TOKEN=fictional\n', encoding='utf-8')
            (root / 'data').mkdir()
            (root / 'data/note.txt').write_text('user data\n', encoding='utf-8')
            # Must not invoke git at all when RELEASE-MANIFEST.json is present.
            with patch.object(b, 'run', side_effect=AssertionError('git should not be called')):
                identity = b.source_identity(root)
                payload = b.compatibility_request(root)
            self.assertEqual(identity['commit'], manifest['commit'])
            self.assertEqual(identity['releaseVersion'], '0.1.0-rc.1')
            self.assertEqual(identity['compatibility'], manifest['compatibility'])
            self.assertIsNone(identity['legacyCompatibility'])
            self.assertEqual(payload, {'compatibility': manifest['compatibility']})

    def test_shared_manifest_validator_rejects_version_divergence_between_manifest_and_version_file(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest = create_synthetic_package(folder, version='0.1.0-rc.1')
            # Mutate VERSION and update its hash in RELEASE-MANIFEST.json while keeping manifest['version'] at 0.1.0-rc.1.
            new_version_bytes = b'9.9.9-rc.1\n'
            (root / 'VERSION').write_bytes(new_version_bytes)
            manifest['files']['VERSION'] = hashlib.sha256(new_version_bytes).hexdigest()
            (root / 'RELEASE-MANIFEST.json').write_text(json.dumps(manifest, sort_keys=True), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, '^VERSAO_DIVERGENTE$'):
                r.verify_directory(root)
            with self.assertRaisesRegex(ValueError, '^MANIFESTO_INVALIDO$'):
                b.source_identity(root)

    def test_git_checkout_preserves_legacy_compatibility_for_existing_format_1_backups(self):
        if not (ROOT / '.git').exists():
            self.skipTest('requires git checkout')
        payload = b.compatibility_request(ROOT)
        self.assertIn('compatibility', payload)
        self.assertIn('legacyCompatibility', payload)
        runtime_keys = set(payload['compatibility'])
        legacy_keys = set(payload['legacyCompatibility'])
        self.assertTrue(runtime_keys < legacy_keys)
        self.assertIn('frontend/src-tauri/Cargo.toml', legacy_keys - runtime_keys)
        self.assertTrue(any(k.endswith('.spec.ts') for k in legacy_keys - runtime_keys))
        self.assertFalse(any('/src/test/' in k for k in legacy_keys))

    def test_release_package_rejects_tampering_extra_migrations_and_unidentified_directories(self):
        with tempfile.TemporaryDirectory() as folder:
            root, _ = create_synthetic_package(folder)
            # 1. Modified runtime file is rejected.
            (root / 'backend/pom.xml').write_bytes(b'<project>tampered</project>')
            with self.assertRaisesRegex(ValueError, '^MANIFESTO_INVALIDO$'):
                b.source_identity(root)

        with tempfile.TemporaryDirectory() as folder:
            root, _ = create_synthetic_package(folder)
            # 2. Rogue unmanifested Flyway migration is rejected.
            (root / 'backend/src/main/resources/db/migration/V13__rogue.sql').write_text('SELECT 2;\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, '^MANIFESTO_INVALIDO$'):
                b.source_identity(root)

        with tempfile.TemporaryDirectory() as folder:
            root, _ = create_synthetic_package(folder)
            # 3. Directory without .git and without RELEASE-MANIFEST.json is refused.
            (root / 'RELEASE-MANIFEST.json').unlink()
            with self.assertRaisesRegex(ValueError, '^ORIGEM_NAO_IDENTIFICADA$'):
                b.source_identity(root)

        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            # 4. Non-file RELEASE-MANIFEST.json is rejected even if .git is present.
            (root / '.git').mkdir()
            (root / 'RELEASE-MANIFEST.json').mkdir()
            with self.assertRaisesRegex(ValueError, '^MANIFESTO_INVALIDO$'):
                b.source_identity(root)


unittest.main()
