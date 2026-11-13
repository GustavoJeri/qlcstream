"""Run after starting integrations-compose.yaml. Uses fictional fixture credentials only."""
import json
import subprocess
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError

BASE = 'http://127.0.0.1:18087'


def call(path, method='GET', body=None):
    data = None if body is None else json.dumps(body).encode()
    with urlopen(Request(BASE + path, data=data, method=method,
                         headers={'Content-Type': 'application/json'}), timeout=10) as response:
        result = response.read().decode()
        assert 'fictional_candidate' not in result
        assert 'fictional_environment_token' not in result
        return json.loads(result)


def ready():
    for _ in range(60):
        try:
            call('/api/system/integration-settings')
            return
        except Exception:
            time.sleep(1)
    raise AssertionError('Isolated backend did not become ready')


ready()
# Explicitly reset just the fixture settings, making this script repeatable.
for integration in ('tmdb', 'prowlarr', 'qbittorrent'):
    call('/api/system/integration-settings/' + integration, 'PUT', {'source': 'environment'})
before = call('/api/system/integration-settings')
assert all(item['source'] == 'environment' for item in before)
candidate = {'source': 'application', 'baseUrl': 'http://mock:8000',
             'secret': {'action': 'replace', 'value': 'fictional_candidate'}}
assert call('/api/system/integration-settings/tmdb/test', 'POST', candidate)['reason'] == 'ok'
bad = dict(candidate, secret={'action': 'replace', 'value': 'fictional_invalid'})
assert call('/api/system/integration-settings/tmdb/test', 'POST', bad)['reason'] == 'authentication_rejected'
assert before == call('/api/system/integration-settings')
for integration in ('tmdb', 'prowlarr', 'qbittorrent'):
    change = dict(candidate)
    if integration == 'qbittorrent':
        change['username'] = 'fictional_user'
    call('/api/system/integration-settings/' + integration, 'PUT', change)
call('/api/catalog/trending')
assert call('/api/search/releases?query=fictional') == []
call('/api/system/integration-settings/tmdb', 'PUT', {'source': 'application'})
assert call('/api/system/integration-settings/tmdb/test', 'POST', {})['reason'] == 'ok'
for _ in range(30):
    diagnostics = call('/api/system/integrations')
    if all(item['status'] == 'available' for item in diagnostics):
        break
    time.sleep(.25)
else:
    raise AssertionError('Effective integrations did not become available')
saved = call('/api/system/integration-settings')
# Make writes fail in the fixture database; the effective snapshot must be preserved.
def sql(statement):
    subprocess.run(['docker', 'compose', '--env-file', 'docs/validation/integrations.env', '-f', 'docs/validation/integrations-compose.yaml',
                    'exec', '-T', 'database', 'psql', '-U', 'integrations_test', '-d',
                    'integrations_test', '-v', 'ON_ERROR_STOP=1', '-c', statement],
                   check=True, capture_output=True)


sql('ALTER TABLE integration_settings RENAME TO integration_settings_write_failure')
try:
    try:
        call('/api/system/integration-settings/tmdb', 'PUT', bad)
        raise AssertionError('Failed persistence was accepted')
    except HTTPError as failure:
        assert failure.code == 503
    assert saved == call('/api/system/integration-settings')
    assert call('/api/system/integration-settings/tmdb/test', 'POST', {})['reason'] == 'ok'
finally:
    sql('ALTER TABLE integration_settings_write_failure RENAME TO integration_settings')
subprocess.run(['docker', 'compose', '--env-file', 'docs/validation/integrations.env', '-f', 'docs/validation/integrations-compose.yaml',
                'restart', 'backend'], check=True, capture_output=True)
ready()
assert saved == call('/api/system/integration-settings')
assert call('/api/system/integration-settings/tmdb/test', 'POST', {})['reason'] == 'ok'
call('/api/system/integration-settings/tmdb', 'PUT',
     {'source': 'application', 'secret': {'action': 'clear'}})
assert call('/api/system/integration-settings/tmdb/test', 'POST', {})['reason'] == 'missing_configuration'
tmdb = next(item for item in call('/api/system/integrations') if item['id'] == 'tmdb')
assert tmdb['status'] == 'not_configured'
call('/api/system/integration-settings/tmdb', 'PUT', {'source': 'environment'})
assert call('/api/system/integration-settings/tmdb/test', 'POST', {})['reason'] == 'ok'
print('PASS: environment, candidate success/rejection, no persistence from test, live clients,')
print('omitted secret, diagnostics, PostgreSQL restart persistence, explicit clear and environment recovery.')
