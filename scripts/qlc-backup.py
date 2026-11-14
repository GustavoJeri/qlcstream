"""Explicit maintenance backup/isolated restore. Python 3.12+, Docker Linux, no host installs.

Commands never load .env or invoke the daily Compose project. Native output is private.
"""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import subprocess
import sys
import time
import zlib

ROOT = Path(__file__).resolve().parents[1]
HELPER = 'qlcstream-backup-helper:1'
SERVICES = ('database', 'backend', 'frontend', 'prowlarr', 'qbittorrent')
MOUNTS = {'prowlarr': ('prowlarr', '/config'), 'qbittorrent': ('qbittorrent', '/config'),
          'keys': ('backend', '/run/qlcstream-secrets'), 'media': ('backend', '/data')}


def require(condition, code):
    if not condition:
        raise ValueError(code)


def run(*args, data=None, allow_failure=False, environment=None):
    p = subprocess.run(list(args), input=data, capture_output=True, cwd=ROOT,
                       env=None if environment is None else os.environ | environment)
    if p.returncode and not allow_failure:
        # Docker/SQL errors can echo passwords or database contents.
        safe = p.stderr.decode(errors='replace').strip()
        allowed = {'BACKUP_INCOMPLETO', 'BACKUP_CORROMPIDO', 'CHAVE_AUSENTE', 'CHAVE_INCORRETA',
                   'VERSAO_ESQUEMA_INCOMPATIVEL', 'DESTINO_OCUPADO', 'ARQUIVO_INSEGURO',
                   'LINK_OU_TIPO_NAO_SUPORTADO', 'CHAVE_INVALIDA', 'PACOTE_INVALIDO'}
        allowed |= {'PERMISSOES_DIVERGENTES', 'ARQUIVOS_DIVERGENTES'}
        raise ValueError(safe if safe in allowed else 'COMANDO_FALHOU')
    return p.stdout if not allow_failure else p


def docker(*args, **kwargs):
    return run('docker', *args, **kwargs)


def inspect(kind, name):
    return json.loads(docker(kind, 'inspect', name))[0]


def exists(kind, name):
    return docker(kind, 'inspect', name, allow_failure=True).returncode == 0


def compatibility():
    # Code identity covers runtime inputs; documentation/test-only commits remain compatible.
    paths = [ROOT / name for name in run('git', 'ls-files', '-z', 'backend', 'frontend').decode().split('\0')
             if name and '/src/test/' not in name]
    return {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(paths)}


def private_command(action, vault, keys, mounts=()):
    args = ['run', '--rm', '-i', '--network', 'none', '--pull', 'never', '--read-only',
            '--log-driver', 'none',
            '--cap-drop', 'ALL', '--cap-add', 'CHOWN', '--cap-add', 'DAC_OVERRIDE', '--cap-add', 'FOWNER',
            '--cap-add', 'FSETID',
            '--security-opt', 'no-new-privileges', '--tmpfs', '/tmp:rw,noexec,nosuid']
    for source, target, readonly, kind in [(vault, '/vault', action != 'create', 'volume'),
                                         (keys, '/keyvault', action != 'create', 'volume'), *mounts]:
        require(',' not in source, 'MONTAGEM_NAO_SUPORTADA')
        args += ['--mount', f'type={kind},source={source},target={target}' + (',readonly' if readonly else '')]
    return [*args, HELPER, action]


def private(action, vault, keys, payload, mounts=()):
    return docker(*private_command(action, vault, keys, mounts), data=json.dumps(payload).encode())


def safe_name(value):
    require(re.fullmatch(r'qlcbackup-[a-z0-9][a-z0-9-]{2,60}', value) is not None, 'NOME_INVALIDO')
    return value


def reserve(volume, label):
    require(not exists('volume', volume), 'DESTINO_OCUPADO')
    docker('volume', 'create', '--label', 'qlcstream.backup=' + label, volume)


def claim(name):
    # Docker container names are exclusive. Retain this stopped claim after failures,
    # so another invocation cannot mistake a partial target for a fresh destination.
    require(not exists('container', name + '-claim'), 'DESTINO_OCUPADO')
    docker('create', '--name', name + '-claim', '--network', 'none',
           '--label', 'qlcstream.backup=claim', HELPER, 'claim')


def source(project):
    ids = docker('ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project).decode().split()
    require(ids, 'PROJETO_AUSENTE')
    containers = json.loads(docker('inspect', *ids))
    result = {}
    for c in containers:
        service = c['Config']['Labels'].get('com.docker.compose.service')
        if service in SERVICES:
            require(service not in result, 'REPLICA_NAO_SUPORTADA')
            result[service] = c
        else:
            require(not c['State']['Running'], 'ESCRITORES_ATIVOS')
    require(set(result) == set(SERVICES), 'SERVICO_AUSENTE')
    require(all(not c['State']['Running'] and c['State']['Status'] == 'exited'
                and c['State']['ExitCode'] in ((0, 143) if s == 'backend' else (0,))
                for s, c in result.items() if s != 'database'),
            'PARE_ESCRITORES_NORMALMENTE')
    require(result['database']['State']['Running'], 'BANCO_PRECISA_ESTAR_ATIVO')
    return result


def source_mount(c, destination):
    found = [m for m in c['Mounts'] if posixpath.normpath(m['Destination']) == posixpath.normpath(destination)]
    require(len(found) == 1 and found[0]['Type'] in ('volume', 'bind'), 'MONTAGEM_NAO_SUPORTADA')
    m = found[0]
    options = [item for item in c.get('HostConfig', {}).get('Mounts', []) or []
               if posixpath.normpath(item.get('Target', '')) == posixpath.normpath(destination)]
    require(not any((item.get('VolumeOptions') or {}).get('Subpath')
                    or (item.get('VolumeOptions') or {}).get('SubPath') for item in options)
            and not m.get('SubPath') and not m.get('Subpath'), 'SUBDIRETORIO_DE_VOLUME_NAO_SUPORTADO')
    return m


def mount(c, destination):
    m = source_mount(c, destination)
    return m.get('Name') if m['Type'] == 'volume' else m['Source'], m['Type']


def normalized_source(value):
    # These are paths reported by the daemon, not host paths to open or resolve.
    windows = bool(re.match(r'^[A-Za-z]:[\\/]', value)) or value.startswith('\\\\')
    value = posixpath.normpath(value.replace('\\', '/'))
    return PurePosixPath(value.casefold() if windows else value)


def mounts_overlap(left, right):
    if left['Type'] == right['Type'] == 'volume' and left.get('Name') and left.get('Name') == right.get('Name'):
        return True
    if not left.get('Source') or not right.get('Source'):
        return False
    a, b = normalized_source(left['Source']), normalized_source(right['Source'])
    return a == b or a in b.parents or b in a.parents


def require_idle_mounts(selected):
    ids = docker('ps', '-q').decode().split()
    if not ids:
        return
    # Inspect only mount metadata. Never retrieve other projects' environments/logs.
    lines = docker('inspect', '--format', '{{json .Mounts}}', *ids).decode().splitlines()
    active = [item for line in lines for item in (json.loads(line) or [])]
    require(not any(mounts_overlap(a, b) for a in selected for b in active), 'VOLUME_EM_USO')


def stop_options(config):
    require('stopTimeout' in config and 'stopSignal' in config, 'BACKUP_INCOMPLETO')
    timeout, signal = config['stopTimeout'], config['stopSignal']
    require(timeout is None or type(timeout) is int and timeout >= -1, 'BACKUP_INCOMPLETO')
    require(signal is None or isinstance(signal, str) and '\n' not in signal and '\r' not in signal,
            'BACKUP_INCOMPLETO')
    options = []
    if timeout is not None:
        options += ['--stop-timeout', str(timeout)]
    if signal:
        options += ['--stop-signal', signal]
    return options


def environment(c):
    return dict(item.split('=', 1) for item in c['Config']['Env'])


def sql(container, query):
    env = environment(container)
    return docker('exec', '-i', container['Id'], 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
                  '-U', env['POSTGRES_USER'], '-d', env['POSTGRES_DB'], '-tA', data=query.encode())


def database_snapshot(c):
    tables = json.loads(sql(c, "SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='public'"))
    require(tables and 'flyway_schema_history' in tables and 'integration_settings' in tables, 'ESQUEMA_INVALIDO')
    statements = []
    for table in tables:
        require(re.fullmatch('[a-z_]+', table), 'ESQUEMA_INVALIDO')
        statements.append(f"SELECT '{table}' AS name, coalesce(json_agg(t ORDER BY row_to_json(t)::text),'[]'::json) AS rows "
                          f'FROM public."{table}" t')
    # One SQL statement gives one MVCC snapshot for every public table.
    result = json.loads(sql(c, 'SELECT json_object_agg(name, rows) FROM (' + ' UNION ALL '.join(statements) + ') snapshot'))
    history = result['flyway_schema_history']
    migrations = list((ROOT / 'backend/src/main/resources/db/migration').glob('V*__*.sql'))
    require(all(row['success'] for row in history) and
            {r['script'] for r in history} == {p.name for p in migrations}, 'VERSAO_ESQUEMA_INCOMPATIVEL')
    for row in history:
        migration = next(p for p in migrations if p.name == row['script'])
        checksum = 0
        for line in migration.read_text(encoding='utf-8-sig').splitlines():
            checksum = zlib.crc32(line.encode('utf-8'), checksum)
        signed = checksum if checksum < 2**31 else checksum - 2**32
        require(row['checksum'] == signed, 'VERSAO_ESQUEMA_INCOMPATIVEL')
    return result


def backup(args):
    c = source(args.project)
    db_env, app_env = environment(c['database']), environment(c['backend'])
    require(app_env.get('DB_URL') == 'jdbc:postgresql://database:5432/' + db_env['POSTGRES_DB']
            and app_env.get('DB_USER') == db_env['POSTGRES_USER']
            and app_env.get('DB_PASSWORD') == db_env['POSTGRES_PASSWORD']
            and app_env.get('MEDIA_ROOT') == '/data', 'TOPOLOGIA_NAO_SUPORTADA')
    mount(c['database'], '/var/lib/postgresql')
    require(db_env.get('PGDATA', '').startswith('/var/lib/postgresql/'), 'TOPOLOGIA_NAO_SUPORTADA')
    mounts, selected = [], []
    for component, (service, destination) in MOUNTS.items():
        # Support the fixture's equivalent key path by reading the declared key setting.
        if component == 'keys':
            key_path = environment(c['backend'])['INTEGRATION_SETTINGS_KEY_FILE']
            require(key_path.endswith('/integration-settings.key'), 'CHAVE_CAMINHO_NAO_SUPORTADO')
            destination = str(Path(key_path).parent).replace('\\', '/')
        location, kind = mount(c[service], destination)
        selected.append(source_mount(c[service], destination))
        mounts.append((location, '/source/' + component, True, kind))
    require_idle_mounts(selected)
    require(mount(c['backend'], '/data') == mount(c['qbittorrent'], '/downloads'), 'MIDIA_DIVERGENTE')
    # Only the standard single-database application is supported. No extra runtime mounts.
    for service, expected in {'database': 1, 'backend': 2, 'frontend': 0, 'prowlarr': 1, 'qbittorrent': 2}.items():
        require(len(c[service]['Mounts']) == expected, 'MONTAGEM_EXTRA_NAO_SUPORTADA')
    sessions = int(sql(c['database'], "SELECT count(*) FROM pg_stat_activity WHERE backend_type='client backend' AND pid<>pg_backend_pid()"))
    require(sessions == 0, 'CONEXOES_EXTERNAS_ATIVAS')
    snapshot = database_snapshot(c['database'])
    env = environment(c['database'])
    dump = docker('exec', c['database']['Id'], 'pg_dump', '-Fc', '--no-owner', '--no-acl',
                  '-U', env['POSTGRES_USER'], '-d', env['POSTGRES_DB'])
    require(snapshot == database_snapshot(c['database']), 'BANCO_ALTERADO_DURANTE_BACKUP')
    # Fail before reserving any output if names are already in use.
    require(args.vault != args.key_vault and not exists('volume', args.vault)
            and not exists('volume', args.key_vault), 'DESTINO_OCUPADO')
    images = {}
    services = {}
    for service, container in c.items():
        image = inspect('image', container['Image'])
        images[service] = {'id': image['Id'], 'reference': container['Config']['Image'],
                           'digests': image.get('RepoDigests', []), 'os': image['Os'], 'arch': image['Architecture']}
        services[service] = {'image': image['Id'], 'environment': environment(container),
                             'user': container['Config']['User'], 'group_add': container['HostConfig']['GroupAdd'] or [],
                             'stopTimeout': container['Config'].get('StopTimeout'),
                             'stopSignal': container['Config'].get('StopSignal'),
                             'entrypoint': container['Config']['Entrypoint'], 'command': container['Config']['Cmd']}
    manifest = {'format': 1, 'createdUtc': datetime.now(timezone.utc).isoformat(),
                'commit': run('git', 'rev-parse', 'HEAD').decode().strip(), 'compatibility': compatibility(),
                'images': images, 'services': services, 'snapshot': snapshot,
                'settings': snapshot['integration_settings'], 'flyway': snapshot['flyway_schema_history'],
                'mediaIncluded': args.include_media,
                'excluded': ['cluster roles/ACL/tablespaces', 'other databases', 'host .env and scripts',
                             'image layers and source checkout', 'external services/accounts'] +
                            ([] if args.include_media else ['all media file bytes; directories only'])}
    claim(args.vault)
    reserve(args.vault, 'package-v1')
    reserve(args.key_vault, 'key-v1')
    private('create', args.vault, args.key_vault,
            {'manifest': manifest, 'dump': base64.b64encode(dump).decode()}, mounts)
    print('BACKUP_COMPLETO: pacote e chave em volumes separados; origem permanece no estado anterior.')


def metadata(args):
    require(exists('volume', args.vault), 'BACKUP_AUSENTE')
    require(args.key_vault and exists('volume', args.key_vault), 'CHAVE_AUSENTE')
    m = json.loads(private('metadata', args.vault, args.key_vault, {'compatibility': compatibility()}))
    require(set(m['services']) == set(SERVICES), 'BACKUP_INCOMPLETO')
    for config in m['services'].values():
        stop_options(config)
    return m


def restore(args):
    m = metadata(args)  # All hashes, archive paths, compatibility and key checked before targets.
    for image in m['images'].values():
        require(exists('image', image['id']), 'IMAGEM_ORIGINAL_AUSENTE')
        actual = inspect('image', image['id'])
        require((actual['Os'], actual['Architecture']) == (image['os'], image['arch']), 'PLATAFORMA_INCOMPATIVEL')
    volumes = {name: args.target + '-' + name for name in ('database', 'keys', 'media', 'prowlarr', 'qbittorrent')}
    network = args.target + '-internal'
    names = [args.target + '-' + name for name in SERVICES]
    require(all(not exists('volume', name) for name in volumes.values())
            and not exists('network', network) and all(not exists('container', name) for name in names), 'DESTINO_OCUPADO')
    # Reserve an exclusive claim first. A failed restore retains its claim and partial data.
    claim(args.target)
    reserve(volumes['database'], 'restore-incomplete')
    for name in ('keys', 'media', 'prowlarr', 'qbittorrent'):
        reserve(volumes[name], 'restore-incomplete')
    docker('network', 'create', '--internal', '--label', 'qlcstream.backup=restore', network)
    mounts = [(volumes[name], '/target/' + name, False, 'volume') for name in MOUNTS]
    private('extract', args.vault, args.key_vault, {'compatibility': compatibility()}, mounts)
    targets = {'database': [(volumes['database'], '/var/lib/postgresql')],
               'backend': [(volumes['keys'], '/run/qlcstream-secrets'), (volumes['media'], '/data')],
               'frontend': [], 'prowlarr': [(volumes['prowlarr'], '/config')],
               'qbittorrent': [(volumes['qbittorrent'], '/config'), (volumes['media'], '/downloads')]}
    # No Compose interpolation, bind mounts, published ports, or source restart policy.
    for service in SERVICES:
        config = m['services'][service]
        env = dict(config['environment'])
        if service == 'backend':
            env['INTEGRATION_SETTINGS_KEY_FILE'] = '/run/qlcstream-secrets/integration-settings.key'
        params = ['create', '--name', args.target + '-' + service, '--network', network,
                  '--network-alias', service, '--label', 'qlcstream.backup=restore-incomplete',
                  # Compose builds may bake source project labels into the image.
                  '--label', 'com.docker.compose.project=' + args.target,
                  '--label', 'com.docker.compose.service=' + service]
        params += stop_options(config)
        if config['user']:
            params += ['--user', config['user']]
        for group in config['group_add']:
            params += ['--group-add', group]
        for volume, dest in targets[service]:
            params += ['--mount', f'type=volume,source={volume},target={dest}']
        # --env NAME reads the value from the CLI process environment. Values never
        # appear in argv, a temporary file, stdout or stderr exposed by this tool.
        require(all('\n' not in k + v and '\r' not in k + v for k, v in env.items()), 'AMBIENTE_NAO_SUPORTADO')
        for name in env:
            params += ['--env', name]
        # Container HOME must not redirect the host CLI to /root/.docker on Linux.
        # Routing/TLS variables for the CLI are outside the application contract.
        require(not set(env) & {'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CONFIG', 'DOCKER_TLS',
                                'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH', 'DOCKER_API_VERSION'},
                'AMBIENTE_NAO_SUPORTADO')
        entry = config['entrypoint'] or []
        if entry:
            params += ['--entrypoint', entry[0]]
        params += [config['image'], *entry[1:], *(config['command'] or [])]
        cli_config = os.environ.get('DOCKER_CONFIG', str(Path.home() / '.docker'))
        docker('--config', cli_config, *params, environment=env)
    db = args.target + '-database'
    success = False
    try:
        docker('start', db)
        ready = False
        for _ in range(90):
            env = m['services']['database']['environment']
            if docker('exec', db, 'pg_isready', '-U', env['POSTGRES_USER'], '-d', env['POSTGRES_DB'], allow_failure=True).returncode == 0:
                ready = True
                break
            time.sleep(1)
        require(ready, 'BANCO_NAO_PRONTO')
        dump = private('dump', args.vault, args.key_vault, {'compatibility': compatibility()})
        docker('exec', '-i', db, 'pg_restore', '--exit-on-error', '--single-transaction', '--no-owner', '--no-acl',
               '-U', env['POSTGRES_USER'], '-d', env['POSTGRES_DB'], data=dump)
        observed = database_snapshot(inspect('container', db))
        require(observed == m['snapshot'], 'REGISTROS_RESTAURADOS_DIVERGENTES')
        success = True
    finally:
        docker('stop', '--timeout', '60', db)
    require(success, 'RESTAURACAO_INCOMPLETA')
    print('RESTAURACAO_VERIFICADA: registros comparados; cinco contêineres parados em rede interna sem portas. '
          'Revise endereços externos antes de iniciar. Mídia: ' + ('incluída.' if m['mediaIncluded'] else 'bytes excluídos.'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    for action in ('backup', 'verify', 'restore'):
        p = sub.add_parser(action)
        p.add_argument('--vault', required=True, type=safe_name)
        p.add_argument('--key-vault', type=safe_name, required=action == 'backup')
        if action == 'backup':
            p.add_argument('--project', required=True)
            p.add_argument('--include-media', action='store_true')
        if action == 'restore':
            p.add_argument('--target', required=True, type=safe_name)
    args = parser.parse_args()
    require(exists('image', HELPER), 'CONSTRUA_HELPER_EXPLICITAMENTE')
    if args.action == 'backup':
        backup(args)
    elif args.action == 'restore':
        restore(args)
    else:
        metadata(args)
        print('BACKUP_VERIFICADO')


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
    try:
        main()
    except Exception as error:
        code = str(error) if isinstance(error, ValueError) and str(error).replace('_', '').isupper() else 'OPERACAO_FALHOU'
        print(code + ': origem preservada; não reutilize destino parcial. Consulte docs/backup-restauracao.md.', file=sys.stderr)
        sys.exit(1)
