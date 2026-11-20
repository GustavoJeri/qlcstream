"""Real isolated Docker rehearsal for Issue #2 (F04.3): clean package install, upgrade, failure and recovery.

Uses only synthetic volumes, internal networks, zero published host ports, and zero access to
personal .env, data/, or the daily qlc-stream installation.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
bspec = importlib.util.spec_from_file_location('backup', ROOT / 'scripts/qlc-backup.py')
b = importlib.util.module_from_spec(bspec)
bspec.loader.exec_module(b)

rspec = importlib.util.spec_from_file_location('release', ROOT / 'scripts/qlc-release.py')
r = importlib.util.module_from_spec(rspec)
rspec.loader.exec_module(r)

PYTHON = 'python:3.13-alpine@sha256:9ef1a599f863231c3d91e941389bfdc4a2d6070557243d54df1a4e57015b960e'
COMPOSE = ROOT / 'docs/validation/versions-compose.yaml'
ENV = ROOT / 'docs/validation/integrations.env'


def next_candidate_version(version):
    m = re.fullmatch(r'(\d+\.\d+\.\d+)-rc\.(\d+)', version)
    if m:
        return f'{m.group(1)}-rc.{int(m.group(2)) + 1}'
    base = version.split('-', 1)[0]
    return f'{base}-rc.2'


def build_synthetic_next_package(base_zip, output_dir, next_version, new_prowlarr_digest=None):
    """Create a valid synthetic next candidate ZIP from the packaged v1 candidate, preserving V12 schema."""
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

    old_notes_path = f'docs/releases/{old_version}.md'
    next_notes_path = f'docs/releases/{next_version}.md'
    next_files[next_notes_path] = next_files[old_notes_path].decode('utf-8').replace(
        old_version, next_version
    ).encode('utf-8')
    del next_files[old_notes_path]

    meta = r.validate_release_consistency(next_files)
    next_commit = hashlib.sha256(('synthetic-upgrade:' + base_manifest['commit'] + ':' + next_version).encode()).hexdigest()[:40]
    files_hashes = {rel: r.sha256_bytes(data) for rel, data in sorted(next_files.items())}
    compat_hashes = {rel: h for rel, h in files_hashes.items() if r.is_runtime_compatibility_path(rel)}
    manifest = {
        'format': 1,
        'version': meta['version'],
        'channel': meta['channel'],
        'schemaVersion': meta['schemaVersion'],
        'commit': next_commit,
        'commitUtc': base_manifest['commitUtc'],
        'workingTree': 'synthetic-candidate',
        'notes': meta['notes'],
        'integrations': meta['integrations'],
        'files': files_hashes,
        'compatibility': compat_hashes,
        'excluded': list(r.EXCLUDED_DESCRIPTION),
    }

    output_dir = Path(output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    prefix = f'qlcstream-{next_version}'
    zip_path = output_dir / f'{prefix}.zip'
    manifest_path = output_dir / f'{prefix}.manifest.json'
    checksums_path = output_dir / f'{prefix}.sha256'
    manifest_bytes = (json.dumps(manifest, indent=2, sort_keys=True) + '\n').encode('utf-8')

    stamp = (2026, 11, 19, 0, 0, 0)
    with zipfile.ZipFile(zip_path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for rel in sorted(next_files):
            info = zipfile.ZipInfo(f'{prefix}/{rel}', date_time=stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = r.executable_mode(rel)
            archive.writestr(info, next_files[rel])
        m_info = zipfile.ZipInfo(f'{prefix}/RELEASE-MANIFEST.json', date_time=stamp)
        m_info.compress_type = zipfile.ZIP_DEFLATED
        m_info.create_system = 3
        m_info.external_attr = 0o100644 << 16
        archive.writestr(m_info, manifest_bytes)

    manifest_path.write_bytes(manifest_bytes)
    zip_sha = r.sha256_file(zip_path)
    manifest_sha = r.sha256_file(manifest_path)
    checksums_path.write_text(f'{zip_sha}  {zip_path.name}\n{manifest_sha}  {manifest_path.name}\n', encoding='utf-8')
    r.verify_package(zip_path, checksums_path)
    return {
        'version': next_version,
        'commit': next_commit,
        'package': str(zip_path),
        'checksums': str(checksums_path),
        'manifest': manifest,
    }


def run_cli(script_path, cwd, *args, expected_fault=None):
    p = subprocess.run([sys.executable, str(script_path), *args], cwd=cwd, capture_output=True)
    if expected_fault:
        assert p.returncode != 0 and expected_fault.encode() in p.stderr, (
            f'Expected fault {expected_fault}, got rc={p.returncode}, stderr={p.stderr.decode(errors="replace")}'
        )
        return p
    assert p.returncode == 0, f'Command failed: {args}\n{p.stderr.decode(errors="replace")}'
    return p


def assert_status_identity(network, expected_version, expected_channel, expected_schema, expected_commit):
    probe = (
        "import json, time, urllib.request\n"
        "for _ in range(60):\n"
        "  try:\n"
        "    with urllib.request.urlopen('http://frontend/api/system/status', timeout=3) as resp:\n"
        "      body = resp.read().decode()\n"
        "    with urllib.request.urlopen('http://frontend/', timeout=3) as page:\n"
        "      html = page.read().decode()\n"
        "    if '<app-root' in html:\n"
        "      print(body)\n"
        "      raise SystemExit(0)\n"
        "  except Exception:\n"
        "    time.sleep(1)\n"
        "raise SystemExit('frontend/backend status endpoint unreachable')\n"
    )
    out = b.docker('run', '--rm', '--network', network, '--pull', 'never', PYTHON, 'python', '-c', probe)
    data = json.loads(out.decode('utf-8'))
    assert data['application'] == 'QLC Stream', data
    assert data['status'] == 'ready', data
    assert data['version'] == expected_version, data
    assert data['channel'] == expected_channel, data
    assert data['schemaVersion'] == expected_schema, data
    assert data['commit'] == expected_commit, data
    return data


def assert_packaged_version_checker(network, install_dir):
    env = {
        'PROWLARR_BASE_URL': 'http://prowlarr:9696',
        'PROWLARR_API_KEY': '0123456789abcdef0123456789abcdef',
        'QBITTORRENT_BASE_URL': 'http://qbittorrent:8080',
        'QBITTORRENT_USERNAME': 'fixture',
        'QBITTORRENT_PASSWORD': 'fictional_versions_password',
    }
    probe = (
        "import json, subprocess, sys, time\n"
        "last = ''\n"
        "for _ in range(60):\n"
        "  p = subprocess.run([sys.executable, '/pkgscripts/check-integration-versions.py'], capture_output=True, text=True)\n"
        "  last = p.stdout\n"
        "  rows = [json.loads(line) for line in p.stdout.splitlines() if line.strip()]\n"
        "  if p.returncode == 0 and {row['service']: row['status'] for row in rows} == {'prowlarr': 'match', 'qbittorrent': 'match'}:\n"
        "    print(p.stdout, end='')\n"
        "    raise SystemExit(0)\n"
        "  time.sleep(1)\n"
        "print(last, file=sys.stderr)\n"
        "raise SystemExit('packaged version checker did not converge')\n"
    )
    args = ['run', '--rm', '--network', network, '--pull', 'never',
            '-v', str(install_dir / 'scripts') + ':/pkgscripts:ro']
    for k in env:
        args += ['--env', k]
    out = b.docker(*args, PYTHON, 'python', '-c', probe, environment=env)
    rows = [json.loads(line) for line in out.decode('utf-8').splitlines() if line.strip()]
    assert {row['service']: row['status'] for row in rows} == {'prowlarr': 'match', 'qbittorrent': 'match'}, rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-dirty', action='store_true')
    args = parser.parse_args()

    run_id = uuid.uuid4().hex[:10]
    project = 'qlcrelease-src-' + run_id
    base = 'qlcbackup-rel-' + run_id
    scratch = ROOT / 'data' / ('release-validation-' + run_id)
    scratch.mkdir(parents=True)

    created_containers = []
    created_networks = []
    results = []

    def compose(override_path, *cargs):
        return b.docker('compose', '--env-file', str(ENV), '-p', project,
                        '-f', str(COMPOSE), '-f', str(override_path), *cargs)

    def run_fixture(override_path, action):
        result = b.docker('compose', '--env-file', str(ENV), '-p', project,
                          '-f', str(COMPOSE), '-f', str(override_path),
                          'run', '--rm', '--no-deps', '--pull', 'never',
                          'runner', 'python', '/fixture/backup-fixture.py', action, allow_failure=True)
        if result.returncode:
            error = result.stderr.decode(errors='replace')
            lines = re.findall(r'File "/fixture/(backup-fixture|versions-fixture)\.py", line (\d+)', error)
            kind = next((name for name in ('AssertionError', 'HTTPError', 'URLError', 'TimeoutError', 'PermissionError')
                         if name in error), 'RuntimeError')
            states = b.docker('compose', '--env-file', str(ENV), '-p', project,
                              '-f', str(COMPOSE), '-f', str(override_path),
                              'ps', '--all', '--format', '{{.Service}}={{.State}}', allow_failure=True).stdout.decode(errors='replace').split()
            print(f'FAIL: fixture {action} at {lines} ({kind}); services={states}', flush=True)
            raise RuntimeError('Synthetic fixture failed; sensitive output was withheld.')
        return result.stdout

    def write_override(path, backend_image, frontend_image, prowlarr_image=None):
        services = {
            'backend': {'image': backend_image},
            'frontend': {'image': frontend_image, 'depends_on': ['backend']},
            'source': {'command': ['python', '/fixture/backup-fixture.py', 'serve']},
            'runner': {'volumes': ['payload:/payload:rw']},
        }
        if prowlarr_image:
            services['prowlarr'] = {'image': prowlarr_image}
        path.write_text(json.dumps({'services': services}), encoding='utf-8')

    def build_app_images(install_dir, version, commit, tag_suffix):
        be_tag = f'qlcstream-release-{run_id}-backend:{tag_suffix}'
        fe_tag = f'qlcstream-release-{run_id}-frontend:{tag_suffix}'
        b.docker('build', '--build-arg', f'QLCSTREAM_VERSION={version}',
                 '--build-arg', f'QLCSTREAM_COMMIT={commit}', '-t', be_tag, str(install_dir / 'backend'))
        b.docker('build', '--build-arg', f'QLCSTREAM_VERSION={version}',
                 '--build-arg', f'QLCSTREAM_COMMIT={commit}', '-t', fe_tag, str(install_dir / 'frontend'))
        for img in (be_tag, fe_tag):
            labels = b.inspect('image', img)['Config']['Labels'] or {}
            assert labels.get('org.opencontainers.image.version') == version, labels
            assert labels.get('org.opencontainers.image.revision') == commit, labels
        return be_tag, fe_tag

    override_v1 = scratch / 'override-v1.json'
    override_v2 = scratch / 'override-v2.json'
    override_v3 = scratch / 'override-v3.json'
    active_override = override_v1
    created_images = []

    try:
        # 1. Build and verify v1 candidate package and synthetic v2 upgrade candidate package.
        v1 = r.build_package(ROOT, scratch / 'packages-v1', allow_dirty=args.allow_dirty)
        v1_zip = Path(v1['package'])
        v1_sums = Path(v1['checksums'])
        v1_ver = v1['version']
        v1_commit = v1['commit']
        v1_schema = v1['schemaVersion']
        v1_channel = v1['channel']
        results.append(f'packaged and verified candidate {v1_ver} ({v1["fileCount"]} files, sha256={v1["packageSha256"][:12]})')
        print('PASS: packaged and verified v1 candidate', flush=True)

        v2_ver = next_candidate_version(v1_ver)
        v2 = build_synthetic_next_package(v1_zip, scratch / 'packages-v2', v2_ver)
        v2_zip = Path(v2['package'])
        v2_sums = Path(v2['checksums'])
        v2_commit = v2['commit']
        results.append(f'prepared synthetic upgrade candidate {v2_ver}')
        print('PASS: prepared synthetic v2 upgrade candidate', flush=True)

        # 2. Extract v1 into active install dir and preserved pre-upgrade dir (zero .git in both).
        install_active = scratch / 'install-active'
        install_preserved = scratch / 'install-v1-preserved'
        r.apply_package(v1_zip, install_active, v1_sums)
        r.apply_package(v1_zip, install_preserved, v1_sums)
        assert not (install_active / '.git').exists()
        assert not (install_preserved / '.git').exists()

        # Verify CLI inspect & verify-dir inside the extracted package without .git.
        inspected_v1 = json.loads(run_cli(install_active / 'scripts/qlc-release.py', install_active, 'inspect').stdout.decode())
        assert inspected_v1['version'] == v1_ver and inspected_v1['source'] == 'release-package'
        run_cli(install_active / 'scripts/qlc-release.py', install_active, 'verify-dir')

        # Place user .env and data/ files inside install_active to verify preservation across apply.
        user_env = install_active / '.env'
        user_env.write_text('POSTGRES_DB=versions_test\nMEDIA_HOST_PATH=./data/media\n', encoding='utf-8')
        user_marker = install_active / 'data/owned-host-marker.txt'
        user_marker.parent.mkdir(parents=True, exist_ok=True)
        user_marker.write_bytes(b'preserved across upgrade\n')

        # 3. Build v1 Docker images directly from extracted package and run clean install rehearsal.
        be_v1, fe_v1 = build_app_images(install_active, v1_ver, v1_commit, 'v1')
        write_override(override_v1, be_v1, fe_v1)
        active_override = override_v1

        compose(override_v1, 'run', '--rm', '--no-deps', 'prepare')
        compose(override_v1, 'up', '-d', '--no-build', '--pull', 'missing',
                'database', 'backend', 'frontend', 'prowlarr', 'qbittorrent', 'source')
        run_fixture(override_v1, 'prepare')
        db = b.inspect('container', compose(override_v1, 'ps', '-q', 'database').decode().strip())
        b.sql(db, "INSERT INTO movie(tmdb_id,title) VALUES (313131,'Release upgrade recognizable movie');"
                  "INSERT INTO library_collection(name) VALUES ('Release upgrade collection');"
                  "INSERT INTO local_file(movie_id,storage_root_id,relative_path,file_kind,size_bytes) "
                  "SELECT id,1,'incoming/owned.txt','VIDEO',20 FROM movie WHERE tmdb_id=313131;")
        run_fixture(override_v1, 'snapshot')

        compose_network = project + '_default'
        assert_status_identity(compose_network, v1_ver, v1_channel, v1_schema, v1_commit)
        assert_packaged_version_checker(compose_network, install_active)
        results.append(f'clean extracted package install healthy; frontend+API reported {v1_ver} and pinned integrations matched')
        print('PASS: clean install from extracted package verified', flush=True)

        # 4. Pre-upgrade backup executed FROM the extracted v1 package (no .git!).
        compose(override_v1, 'stop', '--timeout', '60', 'backend', 'frontend', 'prowlarr', 'qbittorrent', 'source')
        vault_v1 = base + '-pre-upgrade'
        key_v1 = base + '-pre-upgrade-key'
        run_cli(install_active / 'scripts/qlc-backup.py', install_active,
                'backup', '--project', project, '--vault', vault_v1, '--key-vault', key_v1, '--include-media')
        run_cli(install_active / 'scripts/qlc-backup.py', install_active,
                'verify', '--vault', vault_v1, '--key-vault', key_v1)
        results.append('pre-upgrade backup and verify succeeded from extracted package without .git')
        print('PASS: pre-upgrade backup from extracted package without .git', flush=True)

        # 5. Apply synthetic v2 candidate package onto install_active, preserving .env and data/.
        applied_v2 = json.loads(run_cli(
            install_active / 'scripts/qlc-release.py', install_active,
            'apply', '--package', str(v2_zip), '--checksums', str(v2_sums), '--target', str(install_active),
        ).stdout.decode())
        assert applied_v2['integrationsChanged'] == [], applied_v2
        assert user_env.read_text(encoding='utf-8') == 'POSTGRES_DB=versions_test\nMEDIA_HOST_PATH=./data/media\n'
        assert user_marker.read_bytes() == b'preserved across upgrade\n'
        inspected_v2 = json.loads(run_cli(install_active / 'scripts/qlc-release.py', install_active, 'inspect').stdout.decode())
        assert inspected_v2['version'] == v2_ver and inspected_v2['commit'] == v2_commit

        # Build v2 images from upgraded package and recreate ONLY backend & frontend
        # (valid because integrationsChanged == []; changed integration digests require pull + recreate).
        be_v2, fe_v2 = build_app_images(install_active, v2_ver, v2_commit, 'v2')
        write_override(override_v2, be_v2, fe_v2)
        active_override = override_v2
        compose(override_v2, 'up', '-d', '--no-build', '--pull', 'never', '--force-recreate', 'backend', 'frontend')
        compose(override_v2, 'start', 'prowlarr', 'qbittorrent', 'source')

        assert_status_identity(compose_network, v2_ver, 'candidate', v1_schema, v2_commit)
        assert_packaged_version_checker(compose_network, install_active)
        for svc in ('database', 'prowlarr', 'qbittorrent'):
            cid = compose(override_v2, 'ps', '-q', svc).decode().strip()
            assert b.inspect('container', cid)['Config']['Image'] == inspected_v2['integrations'][svc], svc
        run_fixture(override_v2, 'check-media')
        results.append(f'upgrade {v1_ver} -> {v2_ver} preserved .env, data, database records, settings, indexer, torrent, media and pinned integration matrix')
        print('PASS: upgrade v1 -> v2 preserved all state and reported new version', flush=True)

        # Confirm v2 extracted package refuses restoring a v1 backup before creating target volumes.
        bad_target = base + '-wrong-ver'
        run_cli(install_active / 'scripts/qlc-backup.py', install_active,
                'restore', '--vault', vault_v1, '--key-vault', key_v1, '--target', bad_target,
                expected_fault='VERSAO_ESQUEMA_INCOMPATIVEL')
        assert not b.exists('volume', bad_target + '-database'), 'incompatible version restore created target volume'
        results.append('restoring v1 backup with v2 package refused before creating target volumes')
        print('PASS: incompatible version restore refused cleanly', flush=True)

        # 5b. Single changed integration digest (v2 -> v3, only prowlarr digest changed):
        # recreate backend, frontend and prowlarr, then explicitly start unchanged qbittorrent and verify both run.
        prowlarr_before_cid = compose(override_v2, 'ps', '-q', 'prowlarr').decode().strip()
        prowlarr_before_img = b.inspect('container', prowlarr_before_cid)['Image']
        qbt_before_cid = compose(override_v2, 'ps', '-q', 'qbittorrent').decode().strip()
        qbt_before_img = b.inspect('container', qbt_before_cid)['Image']

        prowlarr_v3_dir = scratch / 'prowlarr-v3-image'
        prowlarr_v3_dir.mkdir(parents=True, exist_ok=True)
        (prowlarr_v3_dir / 'Dockerfile').write_text(
            f"FROM {inspected_v2['integrations']['prowlarr']}\n"
            f"LABEL org.qlcstream.validation.matrix=prowlarr-updated-{run_id}\n",
            encoding='utf-8',
        )
        prowlarr_v3_tag = f'qlcstream-release-{run_id}-prowlarr:v3'
        b.docker('build', '-t', prowlarr_v3_tag, str(prowlarr_v3_dir))
        created_images.append(prowlarr_v3_tag)
        prowlarr_v3_img_id = b.inspect('image', prowlarr_v3_tag)['Id']
        prowlarr_v3_digest = prowlarr_v3_img_id.split(':', 1)[-1]
        assert prowlarr_v3_img_id != prowlarr_before_img

        v3_ver = next_candidate_version(v2_ver)
        v3 = build_synthetic_next_package(
            v1_zip, scratch / 'packages-v3', v3_ver, new_prowlarr_digest=prowlarr_v3_digest
        )
        v3_zip = Path(v3['package'])
        v3_sums = Path(v3['checksums'])
        v3_commit = v3['commit']

        compose(override_v2, 'stop', '--timeout', '60', 'backend', 'frontend', 'prowlarr', 'qbittorrent', 'source')
        for svc, cid in (('prowlarr', prowlarr_before_cid), ('qbittorrent', qbt_before_cid)):
            assert not b.inspect('container', cid)['State']['Running'], f'{svc} should be stopped before apply'

        applied_v3 = json.loads(run_cli(
            install_active / 'scripts/qlc-release.py', install_active,
            'apply', '--package', str(v3_zip), '--checksums', str(v3_sums), '--target', str(install_active),
        ).stdout.decode())
        assert applied_v3['integrationsChanged'] == ['prowlarr'], applied_v3

        be_v3, fe_v3 = build_app_images(install_active, v3_ver, v3_commit, 'v3')
        created_images.extend([be_v3, fe_v3])
        write_override(override_v3, be_v3, fe_v3, prowlarr_image=prowlarr_v3_tag)
        active_override = override_v3

        changed_integrations = applied_v3['integrationsChanged']
        unchanged_integrations = [svc for svc in ('prowlarr', 'qbittorrent') if svc not in changed_integrations]
        assert unchanged_integrations == ['qbittorrent']
        compose(override_v3, 'up', '-d', '--no-build', '--pull', 'never', '--force-recreate',
                'backend', 'frontend', *changed_integrations)
        # Confirm unchanged integration remained stopped until explicitly resumed per runbook.
        assert not b.inspect('container', qbt_before_cid)['State']['Running'], 'unchanged qbittorrent was unexpectedly running'
        compose(override_v3, 'start', *unchanged_integrations, 'source')

        prowlarr_after_cid = compose(override_v3, 'ps', '-q', 'prowlarr').decode().strip()
        prowlarr_after = b.inspect('container', prowlarr_after_cid)
        qbt_after_cid = compose(override_v3, 'ps', '-q', 'qbittorrent').decode().strip()
        qbt_after = b.inspect('container', qbt_after_cid)
        assert prowlarr_after['State']['Running'], 'recreated prowlarr is not running'
        assert qbt_after['State']['Running'], 'resumed qbittorrent is not running'
        assert prowlarr_after_cid != prowlarr_before_cid, 'prowlarr container was not recreated'
        assert prowlarr_after['Image'] == prowlarr_v3_img_id, 'prowlarr did not switch to updated image'
        assert qbt_after_cid == qbt_before_cid and qbt_after['Image'] == qbt_before_img, 'unchanged qbittorrent was recreated unexpectedly'

        assert_status_identity(compose_network, v3_ver, 'candidate', v1_schema, v3_commit)
        assert_packaged_version_checker(compose_network, install_active)
        run_fixture(override_v3, 'check-media')
        results.append(f'single-digest upgrade {v2_ver} -> {v3_ver} recreated prowlarr and resumed unchanged qbittorrent; both integrations healthy')
        print('PASS: single changed integration digest recreated prowlarr and resumed qbittorrent', flush=True)

        # 6. Controlled upgrade failure on v3 + verified recovery using preserved v1 package & images.
        compose(override_v3, 'stop', '--timeout', '60', 'backend', 'frontend', 'prowlarr', 'qbittorrent', 'source')
        b.sql(db, "DELETE FROM local_file; DELETE FROM movie WHERE tmdb_id = 313131;")

        recovery_target = base + '-recovered'
        run_cli(install_preserved / 'scripts/qlc-backup.py', install_preserved,
                'restore', '--vault', vault_v1, '--key-vault', key_v1, '--target', recovery_target)
        for service in b.SERVICES:
            created_containers.append(recovery_target + '-' + service)
        created_networks.append(recovery_target + '-internal')

        rec_source = recovery_target + '-source'
        b.docker('run', '-d', '--name', rec_source, '--network', recovery_target + '-internal',
                 '--network-alias', 'source', '-v', str(ROOT / 'docs/validation') + ':/fixture:ro',
                 PYTHON, 'python', '/fixture/backup-fixture.py', 'serve')
        created_containers.append(rec_source)
        b.docker('start', *(recovery_target + '-' + s for s in b.SERVICES))

        assert_status_identity(recovery_target + '-internal', v1_ver, v1_channel, v1_schema, v1_commit)
        check = b.docker('run', '--rm', '--network', recovery_target + '-internal', '--pull', 'never',
                         '-v', str(ROOT / 'docs/validation') + ':/fixture:ro',
                         '-v', project + '_payload:/payload:ro', '-v', recovery_target + '-media:/media:ro',
                         PYTHON, 'python', '/fixture/backup-fixture.py', 'check-media')
        print(check.decode(), flush=True)
        b.docker('stop', '--timeout', '60', *(recovery_target + '-' + s for s in b.SERVICES))
        results.append(f'controlled upgrade failure recovered cleanly to {v1_ver} via preserved package without .git')
        print('PASS: controlled upgrade failure recovered to v1 cleanly', flush=True)

        evidence = {
            'status': 'passed',
            'runId': run_id,
            'v1': {'version': v1_ver, 'schemaVersion': v1_schema, 'packageSha256': v1['packageSha256']},
            'v2Synthetic': {'version': v2_ver, 'schemaVersion': v1_schema},
            'v3SingleDigestSynthetic': {'version': v3_ver, 'schemaVersion': v1_schema, 'integrationsChanged': ['prowlarr']},
            'checks': results,
        }
        (scratch / 'result.json').write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
        print('PASS: real synthetic release/upgrade/recovery rehearsal; evidence ' + str(scratch / 'result.json'))
    finally:
        if active_override.is_file():
            compose(active_override, 'down', '--remove-orphans')
        for name in created_containers:
            b.docker('stop', '--timeout', '60', name, allow_failure=True)
            b.docker('rm', name, allow_failure=True)
        for net in created_networks:
            b.docker('network', 'rm', net, allow_failure=True)
        for img in created_images:
            b.docker('rmi', '-f', img, allow_failure=True)
        print('Synthetic volumes retained: ' + base, flush=True)


if __name__ == '__main__':
    main()
