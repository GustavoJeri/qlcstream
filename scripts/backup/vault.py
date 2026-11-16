"""Private Linux volume package. Never prints paths, configuration or native errors."""
import base64
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import sys
import tarfile

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

COMPONENTS = ('prowlarr', 'qbittorrent', 'media')


def require(condition, code):
    if not condition:
        raise ValueError(code)


def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def members(archive):
    """Validate the entire archive before creating even its first directory."""
    items = archive.getmembers()
    seen = set()
    for item in items:
        p = PurePosixPath(item.name)
        require(item.name and not p.is_absolute() and '..' not in p.parts
                and '\\' not in item.name and ':' not in item.name
                and (item.isdir() or item.isreg()) and item.name not in seen,
                'ARQUIVO_INSEGURO')
        seen.add(item.name)
    names = {item.name: item for item in items}
    for item in items:
        require(all(str(parent) not in names or names[str(parent)].isdir()
                    for parent in PurePosixPath(item.name).parents), 'ARQUIVO_INSEGURO')
    return items


def archive(source, destination, directories_only=False):
    source = Path(source)
    require(source.is_dir() and not source.is_symlink(), 'ORIGEM_INVALIDA')
    with tarfile.open(destination, 'w') as out:
        for p in [source, *sorted(source.rglob('*'))]:
            require(not p.is_symlink() and (p.is_dir() or p.is_file()), 'LINK_OU_TIPO_NAO_SUPORTADO')
            if directories_only and not p.is_dir():
                continue
            out.add(p, arcname='.' if p == source else p.relative_to(source).as_posix(), recursive=False)


def component_members(archive, component):
    items = members(archive)
    required = {'prowlarr': {'config.xml', 'prowlarr.db'},
                'qbittorrent': {'qBittorrent/config/qBittorrent.conf'}, 'media': set()}[component]
    require(required <= {m.name for m in items if m.isreg()}, 'BACKUP_INCOMPLETO')
    require(any(m.name == '.' and m.isdir() for m in items), 'BACKUP_INCOMPLETO')
    return items


def validate_key(manifest, key_path):
    require(key_path.is_file(), 'CHAVE_AUSENTE')
    try:
        encoded = key_path.read_bytes().strip()
        key = base64.b64decode(encoded, validate=True)
        require(len(key) == 32, 'CHAVE_INVALIDA')
        require(hashlib.sha256(encoded).hexdigest() == manifest['keyHash'], 'CHAVE_INCORRETA')
        cipher = AESGCM(key)
        for row in manifest['settings']:
            nonce, value = row['encrypted_config'].split('.')
            cipher.decrypt(base64.b64decode(nonce, validate=True), base64.b64decode(value, validate=True),
                           ('qlcstream:integration:v1:' + row['integration_id']).encode())
    except ValueError:
        raise
    except Exception:
        raise ValueError('CHAVE_INCORRETA') from None


def verify(root, key_root, compatibility, completed=True):
    if completed:
        require((root / 'COMPLETE').is_file(), 'BACKUP_INCOMPLETO')
        require(digest(root / 'manifest.json') == (root / 'COMPLETE').read_text(), 'BACKUP_CORROMPIDO')
    m = json.loads((root / 'manifest.json').read_text())
    require(m['format'] == 1, 'FORMATO_INCOMPATIVEL')
    require(m['compatibility'] == compatibility, 'VERSAO_ESQUEMA_INCOMPATIVEL')
    expected = {'database.dump', 'prowlarr.tar', 'qbittorrent.tar', 'media.tar'}
    require(set(m['files']) == expected, 'BACKUP_INCOMPLETO')
    for name, sha in m['files'].items():
        require((root / name).is_file() and digest(root / name) == sha, 'BACKUP_CORROMPIDO')
        if name.endswith('.tar'):
            with tarfile.open(root / name) as t:
                component_members(t, name[:-4])
    validate_key(m, key_root / 'integration-settings.key')
    return m


def main(action):
    os.umask(0o077)
    root, keys = Path('/vault/package'), Path('/keyvault/key')
    request = json.load(sys.stdin)
    if action == 'create':
        require(not root.exists() and not keys.exists(), 'DESTINO_OCUPADO')
        root.mkdir(mode=0o700)
        keys.mkdir(mode=0o700)
        m = request['manifest']
        key = Path('/source/keys/integration-settings.key')
        require(key.is_file() and not key.is_symlink(), 'CHAVE_AUSENTE')
        m['keyHash'] = hashlib.sha256(key.read_bytes().strip()).hexdigest()
        m['keyMetadata'] = {name: {'uid': p.stat().st_uid, 'gid': p.stat().st_gid,
                                  'mode': p.stat().st_mode & 0o7777}
                            for name, p in (('directory', key.parent), ('file', key))}
        validate_key(m, key)
        # Separate protected key package, never inside the database/profile package.
        (keys / 'integration-settings.key').write_bytes(key.read_bytes())
        (keys / 'integration-settings.key').chmod(0o600)
        (root / 'database.dump').write_bytes(base64.b64decode(request['dump'], validate=True))
        for component in COMPONENTS:
            archive('/source/' + component, root / (component + '.tar'),
                    directories_only=component == 'media' and not m['mediaIncluded'])
        m['files'] = {p.name: digest(p) for p in root.iterdir()}
        (root / 'manifest.json').write_text(json.dumps(m, sort_keys=True))
        verify(root, keys, m['compatibility'], completed=False)
        # Publication is last. Failure leaves an unmistakably incomplete private package.
        (root / 'COMPLETE').write_text(digest(root / 'manifest.json'))
    elif action in ('verify', 'metadata', 'extract', 'dump'):
        m = verify(root, keys, request['compatibility'])
        if action == 'metadata':
            print(json.dumps(m))
        elif action == 'dump':
            sys.stdout.buffer.write((root / 'database.dump').read_bytes())
        elif action == 'extract':
            for component in COMPONENTS:
                dest = Path('/target/' + component)
                require(dest.is_dir() and not any(dest.iterdir()), 'DESTINO_OCUPADO')
            require(not any(Path('/target/keys').iterdir()), 'DESTINO_OCUPADO')
            # Every path and every archive was validated above, before writing.
            for component in COMPONENTS:
                with tarfile.open(root / (component + '.tar')) as t:
                    t.extractall('/target/' + component, members=members(t), numeric_owner=True, filter='fully_trusted')
                    for item in members(t):
                        p = Path('/target/' + component) / item.name
                        stat = p.lstat()
                        require(not p.is_symlink() and (stat.st_uid, stat.st_gid, stat.st_mode & 0o7777)
                                == (item.uid, item.gid, item.mode), 'PERMISSOES_DIVERGENTES')
                        if item.isreg():
                            require(stat.st_size == item.size, 'ARQUIVOS_DIVERGENTES')
                            with t.extractfile(item) as stream:
                                h = hashlib.file_digest(stream, 'sha256').hexdigest()
                            require(digest(p) == h, 'ARQUIVOS_DIVERGENTES')
            target = Path('/target/keys')
            (target / 'integration-settings.key').write_bytes((keys / 'integration-settings.key').read_bytes())
            for name, p in (('directory', target), ('file', target / 'integration-settings.key')):
                meta = m['keyMetadata'][name]
                os.chown(p, meta['uid'], meta['gid'])
                p.chmod(meta['mode'])
    else:
        raise ValueError('OPERACAO_INVALIDA')


if __name__ == '__main__':
    try:
        main(sys.argv[1])
    except Exception as error:
        safe = str(error) if isinstance(error, ValueError) and str(error).replace('_', '').isupper() else 'PACOTE_INVALIDO'
        print(safe, file=sys.stderr)
        sys.exit(1)
