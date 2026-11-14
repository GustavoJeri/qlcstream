"""Command contracts without Docker: mount identity, private logging and stop metadata."""
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('backup', ROOT / 'scripts/qlc-backup.py')
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)


def bind(source, target='/config'):
    return {'Type': 'bind', 'Source': source, 'Destination': target}


class BackupCommandTests(unittest.TestCase):
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
        with patch.object(b, 'exists', return_value=True), patch.object(b, 'private', return_value=json.dumps(manifest).encode()):
            with self.assertRaisesRegex(ValueError, '^BACKUP_INCOMPLETO$'):
                b.metadata(type('Args', (), {'vault': 'fixture', 'key_vault': 'key'})())


unittest.main()
