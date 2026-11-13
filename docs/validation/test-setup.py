"""API counterpart to the visual first-run fixture; fictional data only."""
import json
import subprocess
import time
from urllib.request import Request, urlopen

BASE = 'http://127.0.0.1:18088'
COMPOSE = ['docker', 'compose', '--env-file', 'docs/validation/integrations.env', '-f', 'docs/validation/setup-compose.yaml']


def call(path, method='GET', body=None):
    with urlopen(Request(BASE + path, method=method,
                         data=None if body is None else json.dumps(body).encode(),
                         headers={'Content-Type': 'application/json'}), timeout=15) as response:
        text = response.read().decode()
        assert 'fictional_candidate' not in text
        return json.loads(text)


def wait():
    for _ in range(60):
        try:
            return call('/api/system/integration-settings')
        except Exception:
            time.sleep(1)
    raise AssertionError('Fixture did not become ready')


wait()
for item in ('tmdb', 'prowlarr', 'qbittorrent'):
    call('/api/system/integration-settings/' + item, 'PUT', {'source': 'environment'})
assert not any(item['configured'] for item in call('/api/system/integration-settings'))
assert call('/api/system/integration-settings/key', 'POST', {})['ready']
assert call('/api/system/integration-settings/key', 'POST', {})['ready']
candidate = {'baseUrl': 'http://mock:8000', 'secret': {'action': 'replace', 'value': 'fictional_candidate'}}
assert call('/api/system/integration-settings/tmdb/test', 'POST',
            dict(candidate, secret={'action': 'replace', 'value': 'fictional_invalid'}))['reason'] == 'authentication_rejected'
assert not any(item['configured'] for item in call('/api/system/integration-settings'))
assert call('/api/system/integration-settings/tmdb/test', 'POST', candidate)['reason'] == 'ok'
call('/api/system/integration-settings/tmdb', 'PUT', dict(candidate, source='application'))
call('/api/catalog/trending')
subprocess.run(COMPOSE + ['restart', 'backend'], check=True, capture_output=True)
rows = wait()
assert next(item for item in rows if item['id'] == 'tmdb')['configured']
assert not next(item for item in rows if item['id'] == 'prowlarr')['configured']
for item in ('prowlarr', 'qbittorrent'):
    change = dict(candidate, source='application')
    if item == 'qbittorrent':
        change['username'] = 'fictional_user'
    call('/api/system/integration-settings/' + item, 'PUT', change)
call('/api/library/storage', 'PUT', {'downloadDirectory': 'missing'})
assert call('/api/system/storage/check', 'POST', {})['backend'] == 'missing_directory'
call('/api/library/storage', 'PUT', {'downloadDirectory': 'incoming'})
result = call('/api/system/storage/check', 'POST', {})
assert result['backend'] == 'read_write_verified'
assert result['status'] == 'inconclusive' and result['sharedDirectoryVerified'] is False
before = call('/api/system/integration-settings')
assert all(item['configured'] for item in before)
assert call('/api/system/integration-settings/tmdb/test', 'POST', {})['reason'] == 'ok'
assert before == call('/api/system/integration-settings')
print('PASS: explicit key provisioning, candidate rejection/success, no implicit saves, optional integrations,')
print('catalog access, restart persistence, folder failure/recovery and configured installation preservation.')
