"""Preparation is separate from read-only recovery checks; all data is synthetic."""
import hashlib
import json
from pathlib import Path
import sys
import importlib.util
import signal

spec = importlib.util.spec_from_file_location('versions', '/fixture/versions-fixture.py')
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


def state():
    q, p, b = v.API('http://qbittorrent:8080'), v.API('http://prowlarr:9696', v.API_KEY), v.API('http://backend:8080')
    v.wait(lambda: q.login(), 'engine')
    v.wait(lambda: p.call('/api/v1/system/status'), 'prowlarr')
    v.wait(lambda: b.call('/actuator/health')['status'] == 'UP', 'backend')
    # Identity and effective credentials must work without any repair or PUT/POST.
    managed = b.call('/api/system/integration-settings')
    assert sum(row['source'] == 'application' and row['configured'] for row in managed) == 2
    b.call('/api/system/integrations/check', 'POST', {})
    v.wait(lambda: all(row['reason'] == 'ok' for row in b.call('/api/system/integrations')
                       if row['id'] in ('prowlarr', 'qbittorrent')), 'effective integration credentials')
    # Authentication is also checked directly against each persisted service profile.
    assert any(item['title'] == v.NAME for item in b.call('/api/search/releases?query=Owned&year=2026'))
    torrents = q.call('/api/v2/torrents/info')
    assert len(torrents) == 1 and torrents[0]['hash'] == v.HASH, 'torrent metadata lost'
    assert torrents[0]['save_path'].rstrip('/') == '/downloads/incoming'
    return {'indexer': v.indexer_state(p.call('/api/v1/indexer')),
            'torrent': {k: torrents[0][k] for k in ('hash', 'name', 'save_path', 'total_size')},
            'managed': managed, 'storage': b.call('/api/library/storage'),
            'library': b.call('/api/library')}


def prepare():
    q, p, b = v.API('http://qbittorrent:8080'), v.API('http://prowlarr:9696', v.API_KEY), v.API('http://backend:8080')
    v.wait(lambda: q.login(), 'engine')
    v.wait(lambda: p.call('/api/v1/system/status'), 'prowlarr')
    v.wait(lambda: b.call('/actuator/health')['status'] == 'UP', 'backend')
    assert not b.call('/api/system/integration-settings/key')['ready']
    b.call('/api/system/integration-settings/key', 'POST', {})
    for service, base, secret in (('qbittorrent', q.base, v.PASSWORD), ('prowlarr', p.base, v.API_KEY)):
        b.call('/api/system/integration-settings/' + service, 'PUT', {
            'source': 'application', 'baseUrl': base, 'username': v.USER,
            'secret': {'action': 'replace', 'value': secret}})
    b.call('/api/library/storage', 'PUT', {'downloadDirectory': 'incoming'})
    assert p.call('/api/v1/indexer') == []
    schema = next(i for i in p.call('/api/v1/indexer/schema') if i['implementation'] == 'Torznab')
    schema.update(name='Backup owned indexer', enable=True, appProfileId=1, priority=25)
    for field in schema['fields']:
        if field['name'] == 'baseUrl':
            field['value'] = 'http://source:8000'
        elif field['name'] == 'apiPath':
            field['value'] = '/api'
        elif field['name'] == 'apiKey':
            field['value'] = 'fictional_source_key'
    p.call('/api/v1/indexer', 'POST', schema)
    # Add only metadata; private torrent, no trackers, no seed and no external route.
    q.post('torrents/add', {'urls': 'http://source:8000/owned.torrent', 'savepath': '/downloads/incoming', 'stopped': 'true'})
    v.wait(lambda: q.call('/api/v2/torrents/info'), 'torrent metadata')
    q.post('torrents/stop', {'hashes': v.HASH})
    Path('/media/incoming/owned.txt').write_text('owned issue 21 bytes\n')


action = sys.argv[1]
if action == 'serve':
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    v.ThreadingHTTPServer(('0.0.0.0', 8000), v.Source).serve_forever()
elif action == 'prepare':
    prepare()
elif action == 'snapshot':
    Path('/payload/backup-expected.json').write_text(json.dumps(state(), sort_keys=True))
elif action == 'check-indexer':
    p = v.API('http://prowlarr:9696', v.API_KEY)
    v.wait(lambda: p.call('/api/v1/system/status'), 'prowlarr')
    expected = json.loads(Path('/payload/backup-expected.json').read_text())
    assert v.indexer_state(p.call('/api/v1/indexer')) == expected['indexer'], 'RESTORED_INDEXER_LOST'
elif action in ('check-media', 'check-no-media'):
    expected = json.loads(Path('/payload/backup-expected.json').read_text())
    actual = state()
    assert actual == expected, 'persisted application/profile state lost or changed'
    media = Path('/media/incoming/owned.txt')
    stat = media.parent.stat()
    assert (stat.st_uid, stat.st_gid, stat.st_mode & 0o7777) == (100, 1000, 0o2770), 'incoming permissions changed'
    if action == 'check-media':
        assert media.read_bytes() == b'owned issue 21 bytes\n', 'media lost'
    else:
        assert not media.exists(), 'media unexpectedly included'
    print('PASS: records, settings, original indexer, torrent metadata and media policy')
