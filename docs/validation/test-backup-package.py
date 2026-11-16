"""Archive validation tests, run inside the explicitly built helper image."""
import importlib.util
import base64
import hashlib
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('vault', '/vault.py')
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


class ArchiveTests(unittest.TestCase):
    def test_config_xml_alone_cannot_be_a_complete_prowlarr_profile(self):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w') as t:
            root = tarfile.TarInfo('.')
            root.type = tarfile.DIRTYPE
            t.addfile(root)
            t.addfile(tarfile.TarInfo('config.xml'))
        data.seek(0)
        with tarfile.open(fileobj=data) as t, self.assertRaisesRegex(ValueError, 'BACKUP_INCOMPLETO'):
            v.component_members(t, 'prowlarr')

    def test_key_must_authenticate_ciphertext_even_with_matching_fingerprint(self):
        with tempfile.TemporaryDirectory() as folder:
            p = Path(folder) / 'key'
            original = bytes(32)
            nonce = bytes(12)
            encrypted = v.AESGCM(original).encrypt(nonce, b'{"secret":"fictional"}', b'qlcstream:integration:v1:prowlarr')
            p.write_bytes(base64.b64encode(original))
            m = {'keyHash': hashlib.sha256(p.read_bytes()).hexdigest(), 'settings': [
                {'integration_id': 'prowlarr', 'encrypted_config': base64.b64encode(nonce).decode() + '.' +
                 base64.b64encode(encrypted).decode()}]}
            v.validate_key(m, p)
            p.write_bytes(base64.b64encode(bytes([1]) * 32))
            m['keyHash'] = hashlib.sha256(p.read_bytes()).hexdigest()
            with self.assertRaisesRegex(ValueError, 'CHAVE_INCORRETA'):
                v.validate_key(m, p)

    def test_rejects_paths_links_devices_duplicates_and_file_parents(self):
        cases = [('../escape', None), ('/absolute', None), ('C:/drive', None), ('a\\escape', None),
                 ('link', tarfile.SYMTYPE), ('hard', tarfile.LNKTYPE), ('device', tarfile.CHRTYPE)]
        for name, kind in cases:
            with self.subTest(name=name):
                data = io.BytesIO()
                with tarfile.open(fileobj=data, mode='w') as t:
                    m = tarfile.TarInfo(name)
                    if kind:
                        m.type, m.linkname = kind, '/outside'
                    t.addfile(m)
                data.seek(0)
                with tarfile.open(fileobj=data) as t, self.assertRaisesRegex(ValueError, 'ARQUIVO_INSEGURO'):
                    v.members(t)
        for names in [('duplicate', 'duplicate'), ('file', 'file/child')]:
            data = io.BytesIO()
            with tarfile.open(fileobj=data, mode='w') as t:
                for name in names:
                    t.addfile(tarfile.TarInfo(name))
            data.seek(0)
            with tarfile.open(fileobj=data) as t, self.assertRaisesRegex(ValueError, 'ARQUIVO_INSEGURO'):
                v.members(t)

    def test_source_link_refused_without_following_it(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / 'source'
            source.mkdir()
            (root / 'outside').write_bytes(b'private outside')
            (source / 'link').symlink_to(root / 'outside')
            with self.assertRaisesRegex(ValueError, 'LINK_OU_TIPO_NAO_SUPORTADO'):
                v.archive(source, root / 'partial.tar')
            self.assertEqual((root / 'outside').read_bytes(), b'private outside')

    def test_directory_only_policy_retains_mode_and_excludes_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            incoming = root / 'source/incoming'
            incoming.mkdir(parents=True)
            incoming.chmod(0o2770)
            (incoming / 'own.txt').write_bytes(b'owned')
            v.archive(root / 'source', root / 'media.tar', directories_only=True)
            with tarfile.open(root / 'media.tar') as t:
                members = v.members(t)
                self.assertTrue(all(m.isdir() for m in members))
                self.assertEqual(t.getmember('incoming').mode, 0o2770)


unittest.main()
