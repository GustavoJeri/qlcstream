"""Real backup and restore, never uses the daily installation or personal .env."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('backup', ROOT / 'scripts/qlc-backup.py')
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
ID = uuid.uuid4().hex[:10]
OLD = 'qlcbackup-source-' + ID
BASE = 'qlcbackup-' + ID
COMPOSE = ROOT / 'docs/validation/versions-compose.yaml'
ENV = ROOT / 'docs/validation/integrations.env'
PYTHON = 'python:3.13-alpine@sha256:9ef1a599f863231c3d91e941389bfdc4a2d6070557243d54df1a4e57015b960e'
SCRATCH = ROOT / 'data' / ('backup-validation-' + ID)
SCRATCH.mkdir(parents=True)
OVERRIDE = SCRATCH / 'fixture.json'
OVERRIDE.write_text(json.dumps({'services': {'frontend': {'build': str(ROOT / 'frontend'), 'depends_on': ['backend']},
                                           'source': {'command': ['python', '/fixture/backup-fixture.py', 'serve']},
                                           'runner': {'volumes': ['payload:/payload:rw']}}}))
created_containers = []
results = []


def compose(*args):
    return b.docker('compose', '--env-file', str(ENV), '-p', OLD, '-f', str(COMPOSE), '-f', str(OVERRIDE), *args)


def command(*args, fault=None):
    p = subprocess.run([sys.executable, str(ROOT / 'scripts/qlc-backup.py'), *args], capture_output=True, cwd=ROOT)
    if fault:
        assert p.returncode and fault.encode() in p.stderr, 'expected rejection: ' + fault
        results.append('rejected ' + fault)
    else:
        assert p.returncode == 0, p.stderr.decode(errors='replace')
        results.append(p.stdout.decode().strip())
    print('PASS: ' + (fault or args[0]), flush=True)


def runner(action, target=None, expected_error=None):
    if not target:
        return compose('run', '--rm', '--no-deps', 'runner', 'python', '/fixture/backup-fixture.py', action)
    result = b.docker('run', '--rm', '--network', target + '-internal', '--pull', 'never',
                    '-v', str(ROOT / 'docs/validation') + ':/fixture:ro',
                    '-v', OLD + '_payload:/payload:ro', '-v', target + '-media:/media:ro',
                    PYTHON, 'python', '/fixture/backup-fixture.py', action, allow_failure=expected_error is not None)
    if expected_error:
        assert result.returncode and expected_error.encode() in result.stderr, 'specific data loss was not detected'
        return b'PASS: expected loss detected'
    return result


def mutate(volume, script):
    return b.docker('run', '--rm', '--network', 'none', '-v', volume + ':/fault', PYTHON, 'python', '-c', script)


try:
    compose('build', 'backend', 'frontend')
    compose('run', '--rm', '--no-deps', 'prepare')
    compose('up', '-d', 'database', 'backend', 'frontend', 'prowlarr', 'qbittorrent', 'source')
    runner('prepare')
    db = b.inspect('container', compose('ps', '-q', 'database').decode().strip())
    b.sql(db, "INSERT INTO movie(tmdb_id,title) VALUES (212121,'Backup recognizable movie');"
          "INSERT INTO library_collection(name) VALUES ('Backup recognizable collection');"
          "INSERT INTO local_file(movie_id,storage_root_id,relative_path,file_kind,size_bytes) "
          "SELECT id,1,'incoming/owned.txt','VIDEO',20 FROM movie WHERE tmdb_id=212121;")
    runner('snapshot')
    compose('stop', '--timeout', '60', 'backend', 'frontend', 'prowlarr', 'qbittorrent', 'source')
    before = b.database_snapshot(db)
    for media in (False, True):
        suffix = '-media' if media else '-config'
        vault, keys, target = BASE + suffix, BASE + suffix + '-key', BASE + suffix + '-restore'
        command('backup', '--project', OLD, '--vault', vault, '--key-vault', keys,
                *(['--include-media'] if media else []))
        assert b.database_snapshot(db) == before, 'source changed'
        command('backup', '--project', OLD, '--vault', vault, '--key-vault', keys, fault='DESTINO_OCUPADO')
        command('verify', '--vault', vault, fault='CHAVE_AUSENTE')
        if media:
            # Wrong/missing key modifies only our key package and is explicitly undone.
            mutate(keys, "from pathlib import Path; p=Path('/fault/key/integration-settings.key'); "
                         "p.rename(p.with_name('saved')); p.write_text('AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=')")
            command('restore', '--vault', vault, '--key-vault', keys, '--target', target, fault='CHAVE_INCORRETA')
            assert not b.exists('volume', target + '-database'), 'wrong key wrote target'
            mutate(keys, "from pathlib import Path; p=Path('/fault/key/integration-settings.key'); p.unlink()")
            command('restore', '--vault', vault, '--key-vault', keys, '--target', target, fault='CHAVE_AUSENTE')
            mutate(keys, "from pathlib import Path; p=Path('/fault/key'); (p/'saved').rename(p/'integration-settings.key')")
            mutate(vault, "from pathlib import Path; p=Path('/fault/package/COMPLETE'); p.rename(p.with_name('saved'))")
            command('verify', '--vault', vault, '--key-vault', keys, fault='BACKUP_INCOMPLETO')
            mutate(vault, "from pathlib import Path; p=Path('/fault/package'); (p/'saved').rename(p/'COMPLETE'); "
                          "d=p/'prowlarr.tar'; d.rename(p/'original.tar'); d.write_bytes(b'broken')")
            command('restore', '--vault', vault, '--key-vault', keys, '--target', target, fault='BACKUP_CORROMPIDO')
            assert not b.exists('volume', target + '-database'), 'invalid backup wrote target'
            mutate(vault, "from pathlib import Path; p=Path('/fault/package'); (p/'prowlarr.tar').unlink(); (p/'original.tar').rename(p/'prowlarr.tar')")
            mutate(vault, "from pathlib import Path; p=Path('/fault/package'); (p/'qbittorrent.tar').rename(p/'saved-qbit.tar')")
            command('verify', '--vault', vault, '--key-vault', keys, fault='BACKUP_CORROMPIDO')
            mutate(vault, "from pathlib import Path; p=Path('/fault/package'); (p/'saved-qbit.tar').rename(p/'qbittorrent.tar')")
            # Valid manifest checksum with incompatible schema still cannot pass.
            mutate(vault, "from pathlib import Path; import json,hashlib; p=Path('/fault/package'); "
                          "m=p/'manifest.json'; (p/'original.json').write_bytes(m.read_bytes()); d=json.loads(m.read_text()); "
                          "d['compatibility']={}; m.write_text(json.dumps(d)); (p/'COMPLETE').write_text(hashlib.sha256(m.read_bytes()).hexdigest())")
            command('restore', '--vault', vault, '--key-vault', keys, '--target', target, fault='VERSAO_ESQUEMA_INCOMPATIVEL')
            mutate(vault, "from pathlib import Path; import hashlib; p=Path('/fault/package'); m=p/'manifest.json'; "
                          "m.write_bytes((p/'original.json').read_bytes()); (p/'original.json').unlink(); "
                          "(p/'COMPLETE').write_text(hashlib.sha256(m.read_bytes()).hexdigest())")
        command('restore', '--vault', vault, '--key-vault', keys, '--target', target)
        command('restore', '--vault', vault, '--key-vault', keys, '--target', target, fault='DESTINO_OCUPADO')
        for service in b.SERVICES:
            created_containers.append(target + '-' + service)
        # Add only our controlled HTTP source to the isolated restoration network.
        source = target + '-source'
        b.docker('run', '-d', '--name', source, '--network', target + '-internal', '--network-alias', 'source',
                 '-v', str(ROOT / 'docs/validation') + ':/fixture:ro', PYTHON,
                 'python', '/fixture/backup-fixture.py', 'serve')
        created_containers.append(source)
        b.docker('start', *(target + '-' + s for s in b.SERVICES))
        print(runner('check-media' if media else 'check-no-media', target).decode(), flush=True)
        b.docker('stop', '--timeout', '60', *(target + '-' + s for s in b.SERVICES))
        # A missing real Prowlarr DB must be detected by the validation, never recreated.
        if media:
            mutate(target + '-prowlarr', "from pathlib import Path; p=Path('/fault'); "
                   "files=list(p.glob('prowlarr.db*')); assert (p/'prowlarr.db') in files; "
                   "[(f.rename(f.with_name('lost-'+f.name))) for f in files]; assert not (p/'prowlarr.db').exists()")
            b.docker('start', *(target + '-' + s for s in b.SERVICES))
            runner('check-indexer', target, expected_error='RESTORED_INDEXER_LOST')
            results.append('missing Prowlarr database detected without repair')
            b.docker('stop', '--timeout', '60', *(target + '-' + s for s in b.SERVICES))
    # Inject an unsupported link only into this run's media; source bytes are untouched.
    mutate(OLD + '_media', "from pathlib import Path; Path('/fault/backup-fault-link').symlink_to('/outside')")
    partial, partial_key = BASE + '-partial', BASE + '-partial-key'
    command('backup', '--project', OLD, '--vault', partial, '--key-vault', partial_key, fault='LINK_OU_TIPO_NAO_SUPORTADO')
    command('verify', '--vault', partial, '--key-vault', partial_key, fault='BACKUP_INCOMPLETO')
    mutate(OLD + '_media', "from pathlib import Path; p=Path('/fault'); "
           "assert (p/'incoming/owned.txt').read_bytes()==b'owned issue 21 bytes\\n'; (p/'backup-fault-link').unlink()")
    assert b.database_snapshot(db) == before, 'failed backup changed source'
    # Original profiles/credentials remain usable after both backups and injected failure.
    compose('start', 'backend', 'frontend', 'prowlarr', 'qbittorrent', 'source')
    runner('check-media')
    results.append('source reopened unchanged; no restoration writes to source')
    (SCRATCH / 'result.json').write_text(json.dumps({'status': 'passed', 'checks': results}, indent=2))
    print('PASS: real synthetic backup/restore; evidence ' + str(SCRATCH / 'result.json'))
finally:
    # Only names created by this execution; never delete any volume.
    compose('down', '--remove-orphans')
    for name in created_containers:
        b.docker('stop', '--timeout', '60', name, allow_failure=True)
        b.docker('rm', name, allow_failure=True)
    print('Synthetic volumes retained: ' + BASE, flush=True)
