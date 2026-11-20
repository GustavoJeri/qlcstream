"""Reproducible release packaging, public distribution manifest, integrity check, and safe upgrade apply.

Python 3.12+, standard library only, no host installs. Never reads .env or data/.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SEMVER_RE = re.compile(r'^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$')
SHA40_RE = re.compile(r'^[0-9a-f]{40}$')
SHA64_RE = re.compile(r'^[0-9a-f]{64}$')
MIGRATION_RE = re.compile(r'^V(\d+)__.+\.sql$')
PINNED_IMAGE_RE = re.compile(r'^\s*image:\s*([^\s#]+@sha256:[0-9a-f]{64})\s*$')
JWT_RE = re.compile(r'eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}')
PEM_RE = re.compile(r'-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----')

ROOT_FILES = (
    'VERSION',
    'compose.yaml',
    '.env.example',
    'README.md',
    'CHANGELOG.md',
    'iniciar-qlcstream.bat',
)

SCRIPT_FILES = (
    'scripts/Start-QLCStream.ps1',
    'scripts/WindowsLauncher.ps1',
    'scripts/check-integration-versions.py',
    'scripts/qlc-backup.py',
    'scripts/qlc-release.py',
    'scripts/backup/Dockerfile',
    'scripts/backup/vault.py',
)

DOC_FILES = (
    'docs/architecture.md',
    'docs/assistente-primeira-configuracao.md',
    'docs/backup-restauracao.md',
    'docs/configuracao-integracoes.md',
    'docs/diagnostico-armazenamento.md',
    'docs/diagnostico-integracoes.md',
    'docs/downloads-confiabilidade.md',
    'docs/inicializacao-windows.md',
    'docs/primeira-execucao.md',
    'docs/versoes-atualizacao.md',
    'docs/versoes-integracoes.md',
)

REQUIRED_NOTE_MARKERS = (
    '## Alterações',
    '## Matriz de versões',
    '## Instalação limpa',
    '## Atualização',
    '## Recuperação',
    '## Limitações',
)

FORBIDDEN_PARTS = frozenset({
    '.git', '.env', 'data', 'node_modules', 'target', 'dist',
    '.angular', '__pycache__', 'src-tauri', '.idea', '.vscode',
})

FORBIDDEN_SUFFIXES = (
    '.spec.ts', '.key', '.pem', '.dump', '.db', '.sqlite', '.sqlite3', '.pyc', '.log',
)

EXCLUDED_DESCRIPTION = (
    '.git metadata and history',
    '.env and host secrets',
    'data/ (profiles, integration key, media, validation outputs)',
    'docs/validation/ test harnesses and fixtures',
    'frontend/src-tauri/ desktop scaffold (separate delivery channel)',
    'backend/src/test/ and frontend *.spec.ts unit tests',
    'build caches (backend/target, frontend/node_modules, frontend/dist, frontend/.angular, __pycache__)',
)

USER_RUNTIME_EXEMPT = frozenset({'.env', '.qlcstream-start.lock', 'data'})
LOCAL_BUILD_CACHE_PREFIXES = (
    'backend/target/',
    'frontend/node_modules/',
    'frontend/dist/',
    'frontend/.angular/',
)
ALLOWED_EXAMPLE_POSTGRES_PASSWORDS = frozenset({'', 'qlc_stream_local'})


def require(condition, code):
    if not condition:
        raise ValueError(code)


def sha256_bytes(content):
    return hashlib.sha256(content).hexdigest()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def is_runtime_compatibility_path(rel_posix):
    if not (rel_posix.startswith('backend/') or rel_posix.startswith('frontend/')):
        return False
    if '/src/test/' in rel_posix or rel_posix.startswith('backend/src/test/'):
        return False
    if rel_posix.startswith('frontend/src-tauri/'):
        return False
    if rel_posix.endswith('.spec.ts'):
        return False
    return True


def validate_safe_relpath(rel_posix):
    require(isinstance(rel_posix, str) and bool(rel_posix), 'CAMINHO_INVALIDO')
    require('\\' not in rel_posix and ':' not in rel_posix and '\0' not in rel_posix, 'CAMINHO_INVALIDO')
    p = PurePosixPath(rel_posix)
    require(not p.is_absolute() and '..' not in p.parts and '.' not in p.parts, 'CAMINHO_INVALIDO')
    require(p.as_posix() == rel_posix, 'CAMINHO_INVALIDO')
    return p


def ensure_path_allowed(rel_posix):
    p = validate_safe_relpath(rel_posix)
    require(not (FORBIDDEN_PARTS & set(p.parts)), 'ARQUIVO_PROIBIDO')
    require(not rel_posix.startswith('docs/validation/'), 'ARQUIVO_PROIBIDO')
    require(not rel_posix.startswith('backend/src/test/') and '/src/test/' not in rel_posix, 'ARQUIVO_PROIBIDO')
    require(rel_posix != '.qlcstream-start.lock', 'ARQUIVO_PROIBIDO')
    require(not any(rel_posix.endswith(suffix) for suffix in FORBIDDEN_SUFFIXES), 'ARQUIVO_PROIBIDO')


def scan_content_for_secrets(rel_posix, raw_bytes):
    if b'\0' in raw_bytes:
        return
    text = raw_bytes.decode('utf-8', errors='replace')
    require(not PEM_RE.search(text), 'SEGREDO_DETECTADO')
    require(not JWT_RE.search(text), 'SEGREDO_DETECTADO')
    if rel_posix == '.env.example':
        for line in text.splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith('#'):
                continue
            key, sep, value = stripped.partition('=')
            if not sep:
                continue
            key = key.strip()
            cleaned = value.strip()
            if key in ('TMDB_API_TOKEN', 'PROWLARR_API_KEY', 'QBITTORRENT_PASSWORD'):
                require(cleaned == '', 'SEGREDO_DETECTADO')
            elif key == 'POSTGRES_PASSWORD':
                require(cleaned in ALLOWED_EXAMPLE_POSTGRES_PASSWORDS, 'SEGREDO_DETECTADO')


def parse_properties(text):
    props = {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith('#') or stripped.startswith('!'):
            continue
        key, sep, value = stripped.partition('=')
        if sep:
            props[key.strip()] = value.strip()
    return props


def parse_compose_images(compose_text):
    images = {}
    current_service = None
    in_services = False
    for line in compose_text.splitlines():
        if re.match(r'^services:\s*$', line):
            in_services = True
            current_service = None
            continue
        if in_services and re.match(r'^\S', line):
            in_services = False
            current_service = None
            continue
        if not in_services:
            continue
        svc = re.match(r'^  ([a-z0-9-]+):\s*$', line)
        if svc:
            current_service = svc.group(1)
            continue
        if current_service:
            match = PINNED_IMAGE_RE.match(line)
            if match:
                images[current_service] = match.group(1)
    required_pinned = {'setup-directories', 'database', 'prowlarr', 'qbittorrent'}
    require(required_pinned <= set(images), 'IMAGEM_NAO_FIXADA')
    return {k: images[k] for k in sorted(required_pinned)}


def latest_flyway_schema(migration_names):
    versions = []
    for name in migration_names:
        m = MIGRATION_RE.fullmatch(name)
        if m:
            versions.append(int(m.group(1)))
    require(bool(versions), 'ESQUEMA_AUSENTE')
    return f'V{max(versions)}'


def validate_release_consistency(file_bytes_map):
    require('VERSION' in file_bytes_map, 'VERSAO_AUSENTE')
    version = file_bytes_map['VERSION'].decode('utf-8').strip()
    require(SEMVER_RE.fullmatch(version) is not None, 'VERSAO_INVALIDA')
    expected_channel = 'candidate' if '-' in version else 'release'

    meta_path = 'backend/src/main/resources/release-metadata.properties'
    require(meta_path in file_bytes_map, 'METADADOS_AUSENTES')
    props = parse_properties(file_bytes_map[meta_path].decode('utf-8'))
    require(props.get('qlcstream.release.version') == version, 'VERSAO_DIVERGENTE')
    require(props.get('qlcstream.release.channel') == expected_channel, 'CANAL_DIVERGENTE')

    migrations = [
        PurePosixPath(path).name
        for path in file_bytes_map
        if path.startswith('backend/src/main/resources/db/migration/')
    ]
    schema_version = latest_flyway_schema(migrations)
    require(props.get('qlcstream.release.schema-version') == schema_version, 'ESQUEMA_DIVERGENTE')

    # Dockerfiles and compose default build args must agree with VERSION.
    for path, pattern, expected_count in (
        ('backend/Dockerfile', rf'ARG QLCSTREAM_VERSION={re.escape(version)}\b', 1),
        ('frontend/Dockerfile', rf'ARG QLCSTREAM_VERSION={re.escape(version)}\b', 1),
        ('compose.yaml', rf'QLCSTREAM_VERSION:\s*\$\{{QLCSTREAM_VERSION:-{re.escape(version)}\}}', 2),
    ):
        require(path in file_bytes_map, 'ARQUIVO_OBRIGATORIO_AUSENTE')
        text = file_bytes_map[path].decode('utf-8')
        require(len(re.findall(pattern, text)) == expected_count, 'VERSAO_DIVERGENTE')

    notes_path = f'docs/releases/{version}.md'
    require(notes_path in file_bytes_map, 'NOTAS_RELEASE_AUSENTES')
    notes_text = file_bytes_map[notes_path].decode('utf-8')
    for marker in REQUIRED_NOTE_MARKERS:
        require(marker in notes_text, 'NOTAS_RELEASE_INCOMPLETAS')

    integrations = parse_compose_images(file_bytes_map['compose.yaml'].decode('utf-8'))
    return {
        'version': version,
        'channel': expected_channel,
        'schemaVersion': schema_version,
        'notes': notes_path,
        'integrations': integrations,
    }


def git_run(root, *args, data=None):
    p = subprocess.run(['git', *args], cwd=root, input=data, capture_output=True)
    require(p.returncode == 0, 'GIT_INDISPONIVEL')
    return p.stdout


def read_head_blobs(root, selected_paths):
    """Read canonical Git blobs from HEAD so LF/CRLF working-tree settings never alter hashes."""
    tree_raw = git_run(root, 'ls-tree', '-r', '-z', '--full-tree', 'HEAD')
    tree_entries = {}
    for entry in tree_raw.split(b'\0'):
        if not entry:
            continue
        meta_part, sep, path_part = entry.partition(b'\t')
        require(bool(sep), 'GIT_INDISPONIVEL')
        parts = meta_part.decode('utf-8').split(' ')
        require(len(parts) == 3, 'GIT_INDISPONIVEL')
        mode, obj_type, oid = parts
        tree_entries[path_part.decode('utf-8')] = (mode, obj_type, oid)

    ordered = []
    for rel in sorted(selected_paths):
        require(rel in tree_entries, 'ARQUIVO_NAO_VERSIONADO')
        mode, obj_type, oid = tree_entries[rel]
        require(obj_type == 'blob', 'ARQUIVO_OBRIGATORIO_AUSENTE')
        require(mode in ('100644', '100755'), 'LINK_NAO_SUPORTADO')
        ordered.append((rel, oid))

    batch_in = ''.join(f'{oid}\n' for _, oid in ordered).encode('utf-8')
    batch_out = git_run(root, 'cat-file', '--batch', data=batch_in)
    cursor = 0
    blobs = {}
    for rel, expected_oid in ordered:
        nl = batch_out.find(b'\n', cursor)
        require(nl != -1, 'GIT_INDISPONIVEL')
        header = batch_out[cursor:nl].decode('utf-8').split(' ')
        require(len(header) == 3 and header[0] == expected_oid and header[1] == 'blob', 'GIT_INDISPONIVEL')
        size = int(header[2])
        start = nl + 1
        end = start + size
        require(end < len(batch_out) and batch_out[end:end + 1] == b'\n', 'GIT_INDISPONIVEL')
        blobs[rel] = batch_out[start:end]
        cursor = end + 1
    require(cursor == len(batch_out), 'GIT_INDISPONIVEL')
    return blobs


def collect_release_files(root, allow_dirty=False):
    require((root / '.git').exists(), 'REPOSITORIO_GIT_NECESSARIO')
    tracked_dirty = git_run(root, 'status', '--porcelain', '--untracked-files=no').decode('utf-8').strip()
    untracked_managed = git_run(
        root, 'ls-files', '--others', '--exclude-standard', 'backend', 'frontend', 'scripts', 'docs',
    ).decode('utf-8').strip()
    status = tracked_dirty or untracked_managed
    working_tree = 'clean'
    if status:
        require(allow_dirty, 'ARVORE_SUJA')
        working_tree = 'dirty'

    if allow_dirty:
        version_path = root / 'VERSION'
        require(version_path.is_file() and not version_path.is_symlink(), 'VERSAO_AUSENTE')
        version = version_path.read_text(encoding='utf-8').strip()
        tracked = [
            name for name in git_run(root, 'ls-files', '-z').decode('utf-8').split('\0') if name
        ]
        tracked_set = set(tracked)
    else:
        head_version = read_head_blobs(root, ('VERSION',))['VERSION']
        version = head_version.decode('utf-8').strip()
        tracked = [
            name for name in git_run(root, 'ls-tree', '-r', '-z', '--name-only', '--full-tree', 'HEAD')
            .decode('utf-8').split('\0') if name
        ]
        tracked_set = set(tracked)

    require(SEMVER_RE.fullmatch(version) is not None, 'VERSAO_INVALIDA')

    selected = set()
    for rel in (*ROOT_FILES, *SCRIPT_FILES, *DOC_FILES, f'docs/releases/{version}.md'):
        if rel not in tracked_set:
            require(allow_dirty and (root / rel).is_file(), 'ARQUIVO_NAO_VERSIONADO')
        selected.add(rel)

    for rel in tracked:
        if is_runtime_compatibility_path(rel):
            selected.add(rel)

    if allow_dirty:
        untracked = [
            name for name in git_run(root, 'ls-files', '-z', '--others', '--exclude-standard', 'backend', 'frontend')
            .decode('utf-8').split('\0') if name
        ]
        for rel in untracked:
            if is_runtime_compatibility_path(rel):
                selected.add(rel)

    file_bytes = {}
    if allow_dirty:
        for rel in sorted(selected):
            ensure_path_allowed(rel)
            full = root / rel
            require(full.is_file() and not full.is_symlink(), 'ARQUIVO_OBRIGATORIO_AUSENTE')
            current = full.parent
            while current != root:
                require(not current.is_symlink(), 'LINK_NAO_SUPORTADO')
                current = current.parent
            content = full.read_bytes()
            scan_content_for_secrets(rel, content)
            file_bytes[rel] = content
    else:
        for rel in sorted(selected):
            ensure_path_allowed(rel)
        blobs = read_head_blobs(root, selected)
        for rel, content in sorted(blobs.items()):
            scan_content_for_secrets(rel, content)
            file_bytes[rel] = content

    meta = validate_release_consistency(file_bytes)
    commit = git_run(root, 'rev-parse', 'HEAD').decode('utf-8').strip().lower()
    require(SHA40_RE.fullmatch(commit) is not None, 'COMMIT_INVALIDO')
    commit_epoch = int(git_run(root, 'show', '-s', '--format=%ct', 'HEAD').decode('utf-8').strip())
    commit_dt = datetime.fromtimestamp(commit_epoch, tz=timezone.utc)

    files_hashes = {rel: sha256_bytes(data) for rel, data in sorted(file_bytes.items())}
    compat_hashes = {rel: digest for rel, digest in files_hashes.items() if is_runtime_compatibility_path(rel)}

    manifest = {
        'format': 1,
        'version': meta['version'],
        'channel': meta['channel'],
        'schemaVersion': meta['schemaVersion'],
        'commit': commit,
        'commitUtc': commit_dt.isoformat(),
        'workingTree': working_tree,
        'notes': meta['notes'],
        'integrations': meta['integrations'],
        'files': files_hashes,
        'compatibility': compat_hashes,
        'excluded': list(EXCLUDED_DESCRIPTION),
    }
    return manifest, file_bytes, commit_dt


def zip_date_time(dt):
    year = max(dt.year, 1980)
    return (year, dt.month, dt.day, dt.hour, dt.minute, dt.second)


def executable_mode(rel_posix):
    if rel_posix.endswith('.sh') or rel_posix in ('backend/mvnw',):
        return 0o100755 << 16
    return 0o100644 << 16


def build_package(root, output_dir, allow_dirty=False):
    manifest, file_bytes, commit_dt = collect_release_files(root, allow_dirty=allow_dirty)
    version = manifest['version']
    prefix = f'qlcstream-{version}'
    output_dir = Path(output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    manifest_bytes = (json.dumps(manifest, indent=2, sort_keys=True) + '\n').encode('utf-8')
    zip_path = output_dir / f'{prefix}.zip'
    manifest_path = output_dir / f'{prefix}.manifest.json'
    checksums_path = output_dir / f'{prefix}.sha256'

    stamp = zip_date_time(commit_dt)
    with zipfile.ZipFile(zip_path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for rel in sorted(file_bytes):
            info = zipfile.ZipInfo(f'{prefix}/{rel}', date_time=stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = executable_mode(rel)
            archive.writestr(info, file_bytes[rel])
        m_info = zipfile.ZipInfo(f'{prefix}/RELEASE-MANIFEST.json', date_time=stamp)
        m_info.compress_type = zipfile.ZIP_DEFLATED
        m_info.create_system = 3
        m_info.external_attr = 0o100644 << 16
        archive.writestr(m_info, manifest_bytes)

    manifest_path.write_bytes(manifest_bytes)
    zip_sha = sha256_file(zip_path)
    manifest_sha = sha256_file(manifest_path)
    checksums_content = (
        f'{zip_sha}  {zip_path.name}\n'
        f'{manifest_sha}  {manifest_path.name}\n'
    )
    checksums_path.write_text(checksums_content, encoding='utf-8')

    # Self-verify immediately before reporting success.
    verify_package(zip_path, checksums_path)
    return {
        'version': version,
        'channel': manifest['channel'],
        'schemaVersion': manifest['schemaVersion'],
        'commit': manifest['commit'],
        'workingTree': manifest['workingTree'],
        'package': str(zip_path),
        'packageSha256': zip_sha,
        'manifest': str(manifest_path),
        'manifestSha256': manifest_sha,
        'checksums': str(checksums_path),
        'fileCount': len(manifest['files']),
    }


def verify_checksums_file(checksums_path, target_file):
    checksums_path = Path(checksums_path)
    target_file = Path(target_file)
    require(checksums_path.is_file() and target_file.is_file(), 'CHECKSUM_AUSENTE')
    expected = None
    for line in checksums_path.read_text(encoding='utf-8').splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        parts = stripped.split(None, 1)
        require(len(parts) == 2 and SHA64_RE.fullmatch(parts[0]) is not None, 'CHECKSUM_INVALIDO')
        name = parts[1].lstrip('*')
        if name == target_file.name:
            expected = parts[0]
            break
    require(expected is not None, 'CHECKSUM_AUSENTE')
    require(sha256_file(target_file) == expected, 'CHECKSUM_DIVERGENTE')


def validate_manifest_schema(manifest):
    require(isinstance(manifest, dict) and manifest.get('format') == 1, 'MANIFESTO_INVALIDO')
    version = manifest.get('version')
    require(isinstance(version, str) and SEMVER_RE.fullmatch(version) is not None, 'MANIFESTO_INVALIDO')
    channel = manifest.get('channel')
    require(channel == ('candidate' if '-' in version else 'release'), 'MANIFESTO_INVALIDO')
    commit = manifest.get('commit')
    require(isinstance(commit, str) and SHA40_RE.fullmatch(commit) is not None, 'MANIFESTO_INVALIDO')
    files = manifest.get('files')
    compat = manifest.get('compatibility')
    require(isinstance(files, dict) and len(files) > 0, 'MANIFESTO_INVALIDO')
    require(isinstance(compat, dict) and len(compat) > 0, 'MANIFESTO_INVALIDO')
    for rel, digest in files.items():
        ensure_path_allowed(rel)
        require(isinstance(digest, str) and SHA64_RE.fullmatch(digest) is not None, 'MANIFESTO_INVALIDO')
    expected_compat = {rel: digest for rel, digest in files.items() if is_runtime_compatibility_path(rel)}
    require(compat == expected_compat, 'MANIFESTO_INVALIDO')
    return manifest


def read_and_verify_zip(zip_path):
    zip_path = Path(zip_path)
    require(zip_path.is_file(), 'PACOTE_AUSENTE')
    file_bytes = {}
    manifest_raw = None
    prefix = None
    seen = set()

    with zipfile.ZipFile(zip_path, 'r') as archive:
        for info in archive.infolist():
            name = info.filename
            require(name and '\\' not in name and ':' not in name and '\0' not in name, 'ARQUIVO_INSEGURO')
            require(not name.startswith('/'), 'ARQUIVO_INSEGURO')
            # Reject symlinks (UNIX mode 0o120000 in upper 16 bits).
            unix_mode = (info.external_attr >> 16) & 0o170000
            require(unix_mode != 0o120000, 'LINK_NAO_SUPORTADO')
            require(name not in seen, 'ARQUIVO_DUPLICADO')
            seen.add(name)
            if info.is_dir():
                continue
            p = PurePosixPath(name)
            require('..' not in p.parts and len(p.parts) >= 2, 'ARQUIVO_INSEGURO')
            if prefix is None:
                prefix = p.parts[0]
            else:
                require(p.parts[0] == prefix, 'PACOTE_INVALIDO')
            rel = PurePosixPath(*p.parts[1:]).as_posix()
            data = archive.read(info)
            if rel == 'RELEASE-MANIFEST.json':
                manifest_raw = data
            else:
                ensure_path_allowed(rel)
                scan_content_for_secrets(rel, data)
                file_bytes[rel] = data

    require(manifest_raw is not None and prefix is not None, 'MANIFESTO_AUSENTE')
    manifest = validate_manifest_schema(json.loads(manifest_raw.decode('utf-8')))
    require(prefix == f"qlcstream-{manifest['version']}", 'PACOTE_INVALIDO')
    require(set(file_bytes) == set(manifest['files']), 'MANIFESTO_DIVERGENTE')
    for rel, expected_sha in manifest['files'].items():
        require(sha256_bytes(file_bytes[rel]) == expected_sha, 'ARQUIVO_CORROMPIDO')

    meta = validate_release_consistency(file_bytes)
    require(meta['version'] == manifest['version'], 'VERSAO_DIVERGENTE')
    require(meta['channel'] == manifest['channel'], 'CANAL_DIVERGENTE')
    require(meta['schemaVersion'] == manifest['schemaVersion'], 'ESQUEMA_DIVERGENTE')
    require(meta['integrations'] == manifest.get('integrations'), 'MATRIZ_DIVERGENTE')
    return manifest, file_bytes, manifest_raw


def verify_package(zip_path, checksums_path=None):
    if checksums_path is not None:
        verify_checksums_file(checksums_path, zip_path)
    manifest, _, _ = read_and_verify_zip(zip_path)
    return manifest


def verify_directory(target_dir):
    raw_root = Path(target_dir)
    require(not raw_root.is_symlink(), 'LINK_NAO_SUPORTADO')
    root = raw_root.resolve()
    require(root.is_dir(), 'DIRETORIO_AUSENTE')
    manifest_path = root / 'RELEASE-MANIFEST.json'
    require(not manifest_path.is_symlink(), 'LINK_NAO_SUPORTADO')
    require(manifest_path.is_file(), 'MANIFESTO_AUSENTE')
    try:
        manifest = validate_manifest_schema(json.loads(manifest_path.read_text(encoding='utf-8')))
    except ValueError:
        raise
    except Exception:
        raise ValueError('MANIFESTO_INVALIDO') from None

    file_bytes = {}
    for rel, expected_sha in manifest['files'].items():
        full = root / rel
        require(full.is_file() and not full.is_symlink(), 'ARQUIVO_AUSENTE')
        current = full.parent
        while current != root:
            require(not current.is_symlink(), 'LINK_NAO_SUPORTADO')
            current = current.parent
        data = full.read_bytes()
        require(sha256_bytes(data) == expected_sha, 'ARQUIVO_CORROMPIDO')
        file_bytes[rel] = data

    # Refuse any unexpected or tampered runtime file under backend/ or frontend/
    # (for example, an unmanifested Flyway migration or altered Java/Angular file).
    manifested_runtime = set(manifest['compatibility'])
    observed_runtime = set()
    for top in ('backend', 'frontend'):
        base = root / top
        require(base.is_dir() and not base.is_symlink(), 'ARQUIVO_AUSENTE')
        for dirpath, dirnames, filenames in os.walk(base, followlinks=False):
            rel_dir = Path(dirpath).relative_to(root).as_posix()
            pruned = []
            for d in dirnames:
                full_d = Path(dirpath) / d
                require(not full_d.is_symlink(), 'LINK_NAO_SUPORTADO')
                rel_child = f'{rel_dir}/{d}/'
                if any(rel_child.startswith(prefix) for prefix in LOCAL_BUILD_CACHE_PREFIXES):
                    continue
                if d == '__pycache__':
                    continue
                pruned.append(d)
            dirnames[:] = pruned
            for fname in filenames:
                full_f = Path(dirpath) / fname
                require(not full_f.is_symlink(), 'LINK_NAO_SUPORTADO')
                rel_posix = f'{rel_dir}/{fname}'
                require(is_runtime_compatibility_path(rel_posix), 'ARQUIVO_EXTRA_NAO_PERMITIDO')
                observed_runtime.add(rel_posix)
    require(observed_runtime == manifested_runtime, 'ARQUIVO_EXTRA_NAO_PERMITIDO')

    meta = validate_release_consistency(file_bytes)
    require(meta['version'] == manifest['version'], 'VERSAO_DIVERGENTE')
    require(meta['channel'] == manifest['channel'], 'CANAL_DIVERGENTE')
    require(meta['schemaVersion'] == manifest['schemaVersion'], 'ESQUEMA_DIVERGENTE')
    require(meta['integrations'] == manifest.get('integrations'), 'MATRIZ_DIVERGENTE')
    return manifest


class IncompleteRollbackError(RuntimeError):
    def __init__(self, backup_dir, message):
        super().__init__(message)
        self.backup_dir = Path(backup_dir)


def _replace_file(src_path, dst_path):
    dst_path.parent.mkdir(parents=True, exist_ok=True)
    os.replace(src_path, dst_path)


def _prune_empty_parents(path, stop_at):
    parent = path.parent
    while parent != stop_at:
        try:
            parent.rmdir()
        except OSError:
            break
        parent = parent.parent


def apply_package(zip_path, target_dir, checksums_path=None):
    if checksums_path is not None:
        verify_checksums_file(checksums_path, zip_path)
    new_manifest, file_bytes, manifest_raw = read_and_verify_zip(zip_path)

    raw_target = Path(target_dir)
    require(not raw_target.is_symlink(), 'LINK_NAO_SUPORTADO')
    target = raw_target.resolve()
    require(not (target / '.git').exists() and not (target / '.git').is_symlink(), 'DESTINO_GIT_NAO_SUPORTADO')

    old_manifest = None
    old_files = set()
    old_version = None
    old_manifest_path = target / 'RELEASE-MANIFEST.json'
    target_existed = target.exists()

    # Preflight existing target completely BEFORE writing a single byte.
    if target_existed:
        require(target.is_dir(), 'DIRETORIO_INVALIDO')
        require(not old_manifest_path.is_symlink(), 'LINK_NAO_SUPORTADO')
        if old_manifest_path.exists():
            old_manifest = verify_directory(target)
            old_files = set(old_manifest['files'])
            old_version = old_manifest['version']
        else:
            existing_entries = [entry.name for entry in target.iterdir()]
            unexpected = [name for name in existing_entries if name not in USER_RUNTIME_EXEMPT]
            require(not unexpected, 'DESTINO_NAO_GERENCIADO')

    # Pre-check every destination path and ancestor before mutating anything.
    for rel in file_bytes:
        top = PurePosixPath(rel).parts[0]
        require(top not in USER_RUNTIME_EXEMPT, 'ARQUIVO_PROIBIDO')
        dest = target / rel
        if dest.is_symlink():
            raise ValueError('LINK_NAO_SUPORTADO')
        if dest.exists():
            require(dest.is_file(), 'DIRETORIO_INVALIDO')
            if rel not in old_files:
                raise ValueError('COLISAO_ARQUIVO_LOCAL')
        current = dest.parent
        while current != target:
            require(not current.is_symlink(), 'LINK_NAO_SUPORTADO')
            if current.exists():
                require(current.is_dir(), 'DIRETORIO_INVALIDO')
            current = current.parent

    obsolete = sorted(old_files - set(file_bytes))
    for rel in obsolete:
        top = PurePosixPath(rel).parts[0]
        if top in USER_RUNTIME_EXEMPT:
            continue
        old_path = target / rel
        require(not old_path.is_symlink(), 'LINK_NAO_SUPORTADO')
        current = old_path.parent
        while current != target:
            require(not current.is_symlink(), 'LINK_NAO_SUPORTADO')
            current = current.parent

    # Stage and verify the full new release tree in a sibling temp directory before touching target.
    target.parent.mkdir(parents=True, exist_ok=True)
    stage_dir = Path(tempfile.mkdtemp(prefix='.qlcstream-stage-', dir=target.parent))
    backup_dir = Path(tempfile.mkdtemp(prefix='.qlcstream-rollback-', dir=target.parent))
    backed_up = []
    created_files = []
    created_target_dir = False
    keep_backup_dir = False

    try:
        for rel, content in sorted(file_bytes.items()):
            staged = stage_dir / rel
            staged.parent.mkdir(parents=True, exist_ok=True)
            staged.write_bytes(content)
            if os.name == 'posix' and (rel.endswith('.sh') or rel == 'backend/mvnw'):
                staged.chmod(0o755)
        (stage_dir / 'RELEASE-MANIFEST.json').write_bytes(manifest_raw)
        verify_directory(stage_dir)

        if not target_existed:
            target.mkdir(parents=True, exist_ok=True)
            created_target_dir = True

        # Save existing managed files and manifest to rollback area before replacing anything.
        to_backup = sorted((set(file_bytes) | set(obsolete)) & old_files)
        if old_manifest is not None:
            to_backup.append('RELEASE-MANIFEST.json')
        for rel in to_backup:
            src = target / rel
            if src.is_file() and not src.is_symlink():
                bkp = backup_dir / rel
                bkp.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, bkp)
                backed_up.append((rel, bkp))

        # Preserve the rollback copy before the first mutation; remove it only after
        # either the upgrade or a complete rollback passes verify_directory(target).
        keep_backup_dir = bool(backed_up)

        # Apply staged files atomically per file; roll back completely on any error or interrupt.
        try:
            for rel in sorted(file_bytes):
                dest = target / rel
                if not dest.exists():
                    created_files.append(rel)
                _replace_file(stage_dir / rel, dest)

            for rel in obsolete:
                top = PurePosixPath(rel).parts[0]
                if top in USER_RUNTIME_EXEMPT:
                    continue
                old_path = target / rel
                if old_path.is_file() and not old_path.is_symlink():
                    old_path.unlink()
                    _prune_empty_parents(old_path, target)

            require(not old_manifest_path.is_symlink(), 'LINK_NAO_SUPORTADO')
            if not old_manifest_path.exists():
                created_files.append('RELEASE-MANIFEST.json')
            _replace_file(stage_dir / 'RELEASE-MANIFEST.json', old_manifest_path)

            verify_directory(target)
            keep_backup_dir = False
        except BaseException as apply_error:
            rollback_errors = []
            rollback_interrupted = None
            # Roll back target to its exact pre-apply state without touching .env or data/.
            try:
                for rel in reversed(created_files):
                    added = target / rel
                    try:
                        if added.is_file() and not added.is_symlink():
                            added.unlink()
                            _prune_empty_parents(added, target)
                    except Exception as rb_error:
                        rollback_errors.append(f'{rel}: {rb_error}')
                for rel, bkp in backed_up:
                    dest = target / rel
                    try:
                        if not bkp.is_file():
                            raise FileNotFoundError(str(bkp))
                        dest.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(bkp, dest)
                    except Exception as rb_error:
                        rollback_errors.append(f'{rel}: {rb_error}')
                if not rollback_errors and old_manifest is not None:
                    try:
                        verify_directory(target)
                    except Exception as rb_error:
                        rollback_errors.append(f'verify: {rb_error}')
                if not rollback_errors and created_target_dir and target.is_dir():
                    try:
                        target.rmdir()
                    except OSError:
                        pass
            except BaseException as rb_interrupt:
                rollback_interrupted = rb_interrupt
                rollback_errors.append(f'interrupted: {type(rb_interrupt).__name__}')

            if rollback_errors:
                if backed_up:
                    keep_backup_dir = True
                    cause = rollback_interrupted if rollback_interrupted is not None else apply_error
                    raise IncompleteRollbackError(
                        backup_dir,
                        f'REVERSAO_INCOMPLETA: copia preservada em {backup_dir}',
                    ) from cause
                if rollback_interrupted is not None:
                    raise rollback_interrupted
                raise ValueError('REVERSAO_INCOMPLETA') from apply_error

            keep_backup_dir = False
            raise
    finally:
        shutil.rmtree(stage_dir, ignore_errors=True)
        if not keep_backup_dir:
            shutil.rmtree(backup_dir, ignore_errors=True)

    integrations_changed = []
    if old_manifest is not None:
        old_int = old_manifest.get('integrations') or {}
        new_int = new_manifest.get('integrations') or {}
        integrations_changed = sorted(
            svc for svc in (set(old_int) | set(new_int)) if old_int.get(svc) != new_int.get(svc)
        )

    return {
        'status': 'applied',
        'previousVersion': old_version,
        'version': new_manifest['version'],
        'channel': new_manifest['channel'],
        'schemaVersion': new_manifest['schemaVersion'],
        'commit': new_manifest['commit'],
        'target': str(target),
        'removedObsoleteCount': len(obsolete),
        'integrationsChanged': integrations_changed,
    }


def inspect_installation(target_dir):
    raw_root = Path(target_dir)
    require(not raw_root.is_symlink(), 'LINK_NAO_SUPORTADO')
    root = raw_root.resolve()
    require(root.is_dir(), 'DIRETORIO_AUSENTE')
    manifest_path = root / 'RELEASE-MANIFEST.json'
    if manifest_path.exists() or manifest_path.is_symlink():
        manifest = verify_directory(root)
        return {
            'version': manifest['version'],
            'channel': manifest['channel'],
            'schemaVersion': manifest['schemaVersion'],
            'commit': manifest['commit'],
            'source': 'release-package',
            'manifestPresent': True,
            'integrations': manifest.get('integrations', {}),
        }

    version_path = root / 'VERSION'
    require(version_path.is_file(), 'VERSAO_AUSENTE')
    version = version_path.read_text(encoding='utf-8').strip()
    require(SEMVER_RE.fullmatch(version) is not None, 'VERSAO_INVALIDA')

    meta_path = root / 'backend/src/main/resources/release-metadata.properties'
    require(meta_path.is_file(), 'METADADOS_AUSENTES')
    props = parse_properties(meta_path.read_text(encoding='utf-8'))
    require(props.get('qlcstream.release.version') == version, 'VERSAO_DIVERGENTE')

    migrations_dir = root / 'backend/src/main/resources/db/migration'
    require(migrations_dir.is_dir(), 'ESQUEMA_AUSENTE')
    schema_version = latest_flyway_schema([p.name for p in migrations_dir.glob('V*__*.sql')])
    require(props.get('qlcstream.release.schema-version') == schema_version, 'ESQUEMA_DIVERGENTE')

    commit = 'unknown'
    source = 'unpacked-directory'
    if (root / '.git').exists():
        source = 'git-checkout'
        try:
            candidate = git_run(root, 'rev-parse', 'HEAD').decode('utf-8').strip().lower()
            if SHA40_RE.fullmatch(candidate):
                commit = candidate
        except ValueError:
            commit = 'unknown'

    compose_path = root / 'compose.yaml'
    integrations = parse_compose_images(compose_path.read_text(encoding='utf-8')) if compose_path.is_file() else {}
    return {
        'version': version,
        'channel': props.get('qlcstream.release.channel', 'candidate' if '-' in version else 'release'),
        'schemaVersion': schema_version,
        'commit': commit,
        'source': source,
        'manifestPresent': False,
        'integrations': integrations,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)

    p_inspect = sub.add_parser('inspect')
    p_inspect.add_argument('--dir', default=str(ROOT))

    p_pkg = sub.add_parser('package')
    p_pkg.add_argument('--output', required=True)
    p_pkg.add_argument('--allow-dirty', action='store_true')

    p_verify = sub.add_parser('verify')
    p_verify.add_argument('--package', required=True)
    p_verify.add_argument('--checksums')

    p_vdir = sub.add_parser('verify-dir')
    p_vdir.add_argument('--dir', default=str(ROOT))

    p_apply = sub.add_parser('apply')
    p_apply.add_argument('--package', required=True)
    p_apply.add_argument('--target', required=True)
    p_apply.add_argument('--checksums')

    args = parser.parse_args()
    if args.action == 'inspect':
        print(json.dumps(inspect_installation(args.dir), indent=2, sort_keys=True))
    elif args.action == 'package':
        print(json.dumps(build_package(ROOT, args.output, allow_dirty=args.allow_dirty), indent=2, sort_keys=True))
    elif args.action == 'verify':
        manifest = verify_package(args.package, args.checksums)
        print(json.dumps({
            'status': 'verified',
            'version': manifest['version'],
            'channel': manifest['channel'],
            'schemaVersion': manifest['schemaVersion'],
            'commit': manifest['commit'],
            'fileCount': len(manifest['files']),
        }, indent=2, sort_keys=True))
    elif args.action == 'verify-dir':
        manifest = verify_directory(args.dir)
        print(json.dumps({
            'status': 'verified',
            'version': manifest['version'],
            'channel': manifest['channel'],
            'schemaVersion': manifest['schemaVersion'],
            'commit': manifest['commit'],
            'fileCount': len(manifest['files']),
        }, indent=2, sort_keys=True))
    elif args.action == 'apply':
        print(json.dumps(apply_package(args.package, args.target, args.checksums), indent=2, sort_keys=True))


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
    try:
        main()
    except IncompleteRollbackError as error:
        print(f'REVERSAO_INCOMPLETA: copia preservada em {error.backup_dir} — consulte docs/versoes-atualizacao.md.', file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        print('OPERACAO_INTERROMPIDA: instalacao anterior preservada; consulte docs/versoes-atualizacao.md.', file=sys.stderr)
        sys.exit(130)
    except Exception as error:
        code = str(error) if isinstance(error, ValueError) and str(error).replace('_', '').isupper() else 'OPERACAO_FALHOU'
        print(code + ': consulte docs/versoes-atualizacao.md.', file=sys.stderr)
        sys.exit(1)
