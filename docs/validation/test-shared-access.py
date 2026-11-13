"""Verifies actual container identities against disposable volumes; never runs torrents."""
import subprocess
import uuid

PROJECT = 'qlcstream-access-test-' + uuid.uuid4().hex[:8]
COMPOSE = ['docker', 'compose', '--env-file', 'docs/validation/integrations.env',
           '-p', PROJECT, '-f', 'docs/validation/access-compose.yaml']


def execute(*args):
    result = subprocess.run(COMPOSE + list(args), capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout.strip()


def inspect(script):
    return execute('run', '--rm', '--no-deps', 'inspect', '-c', 'set -eu; ' + script)


try:
    execute('build', 'backend')
    execute('run', '--rm', '--no-deps', 'prepare')
    assert inspect("stat -c '%u:%g:%a' /media/incoming") == '100:1000:2770'
    backend = execute('run', '--rm', '--no-deps', 'backend', '-c', '''set -eu
test "$(id -u)" = 100
id -G | tr ' ' '\n' | grep -qx 1000
test -w /data/incoming
umask 022
echo synthetic-backend > /data/incoming/backend.txt
mkdir /data/incoming/backend-directory
echo synthetic-nested-backend > /data/incoming/backend-directory/backend.txt
id
''')
    engine = execute('run', '--rm', '--no-deps', 'engine', '''exec doas -u qbtUser sh -c 'set -eu
test "$(id -u)" = 1000
test -w /downloads/incoming
test "$(cat /downloads/incoming/backend.txt)" = synthetic-backend
umask 022
echo synthetic-engine > /downloads/incoming/engine.txt
test "$(cat /downloads/incoming/backend-directory/backend.txt)" = synthetic-nested-backend
mkdir /downloads/incoming/engine-directory
echo synthetic-nested > /downloads/incoming/engine-directory/nested.txt
id'
''')
    execute('run', '--rm', '--no-deps', 'backend', '-c', '''set -eu
test "$(cat /data/incoming/engine.txt)" = synthetic-engine
test "$(cat /data/incoming/engine-directory/nested.txt)" = synthetic-nested
''')
    # Existing media and keys must retain bytes and metadata on subsequent preparation.
    inspect('echo synthetic-key > /secrets/existing.key; chmod 600 /secrets/existing.key; '
            'chown 100:101 /secrets/existing.key; echo synthetic-existing > /media/existing.txt; '
            'chown 1234:2345 /media/incoming; chmod 750 /media/incoming')
    snapshot = "stat -c '%u:%g:%a' /media/incoming /secrets/existing.key; sha256sum /media/existing.txt /media/incoming/*.txt /secrets/existing.key"
    before = inspect(snapshot)
    execute('run', '--rm', '--no-deps', 'prepare')
    assert inspect(snapshot) == before
    # An existing link is likewise left alone, rather than changing its target's permissions.
    inspect('mv /media/incoming /media/original; ln -s original /media/incoming')
    before_link = inspect("readlink /media/incoming; stat -c '%u:%g:%a' /media/original")
    execute('run', '--rm', '--no-deps', 'prepare')
    assert inspect("readlink /media/incoming; stat -c '%u:%g:%a' /media/original") == before_link
    print('PASS: backend (' + backend + '), engine (' + engine + ')')
    print('Both identities wrote, cross-read and traversed synthetic files; existing media, key and link preserved.')
finally:
    # Only this randomly named test project and its newly created disposable volumes.
    subprocess.run(COMPOSE + ['down', '--volumes'], check=True, capture_output=True)
