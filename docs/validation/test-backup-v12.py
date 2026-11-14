"""Extend the PR #7 recovery rehearsal with V12 state, using explicitly built local images.

Requires test-backup-restore.py and qlc-backup.py from the complementary integration.
Does not install requirements, build/pull images, or change the daily installation.
"""
import argparse
from pathlib import Path
import re


def source_fixture(backup, env, project, compose_file, override, action):
    result = backup.docker('compose', '--env-file', str(env), '-p', project, '-f', str(compose_file),
                           '-f', str(override), 'run', '--rm', '--no-deps', '--pull', 'never',
                           'runner', 'python', '/fixture/backup-fixture.py', action, allow_failure=True)
    if result.returncode:
        # Only fixture source coordinates and exception type, never remote error bodies.
        error = result.stderr.decode(errors='replace')
        lines = re.findall(r'File "/fixture/(backup-fixture|versions-fixture)\.py", line (\d+)', error)
        kind = next((name for name in ('AssertionError', 'HTTPError', 'URLError', 'TimeoutError', 'PermissionError')
                     if name in error), 'RuntimeError')
        print('FAIL: controlled source fixture ' + action + ' at ' + str(lines[-1:] or []) + ' (' + kind + ')', flush=True)
        raise RuntimeError('Synthetic fixture failed; sensitive output was withheld.')
    return result.stdout


def replace_once(source, before, after):
    if source.count(before) != 1:
        raise RuntimeError("Recovery harness changed; review this extension before executing it.")
    return source.replace(before, after, 1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend-image", required=True)
    parser.add_argument("--frontend-image", required=True)
    args = parser.parse_args()
    original = Path(__file__).with_name("test-backup-restore.py")
    if not original.is_file():
        raise SystemExit("Requires the PR #7 source in an isolated combined checkout.")
    source = original.read_text(encoding="utf-8")
    source = replace_once(source, "return compose('run', '--rm', '--no-deps', 'runner', 'python', '/fixture/backup-fixture.py', action)",
                          "return _source_fixture(b, ENV, OLD, COMPOSE, OVERRIDE, action)")
    source = replace_once(source, "    compose('build', 'backend', 'frontend')", """    b.docker('image', 'inspect', _backend_image, '--format', '{{.Id}}')
    b.docker('image', 'inspect', _frontend_image, '--format', '{{.Id}}')
    b.docker('tag', _backend_image, OLD + '-backend:latest')
    b.docker('tag', _frontend_image, OLD + '-frontend:latest')""")
    source = replace_once(source, "compose('up', '-d', 'database'", "compose('up', '-d', '--no-build', '--pull', 'never', 'database'")
    source = replace_once(source, "    runner('snapshot')", """    b.sql(db, "INSERT INTO download(id,storage_root_id,idempotency_key,engine,media_type,series_tmdb_id,series_title,"
              "release_title,acquisition_ref,relative_directory,status) "
              "VALUES(gen_random_uuid(),1,gen_random_uuid(),'QBITTORRENT','SERIES',212122,'Owned cancellation intent',"
              "'Owned pending URL','fixture:no-transfer','incoming/owned-pending-cancel','CANCELED');"
              "INSERT INTO local_file(movie_id,storage_root_id,relative_path,file_kind,size_bytes,deletion_requested) "
              "SELECT id,1,'incoming/owned-pending-delete.mp4','VIDEO',20,true FROM movie WHERE tmdb_id=212121;")
    runner('snapshot')""")
    anchor = "        print(runner('check-media' if media else 'check-no-media', target).decode(), flush=True)"
    source = replace_once(source, anchor, anchor + """
        restored_db = b.inspect('container', target + '-database')
        restored_snapshot = b.database_snapshot(restored_db)
        for table in ('local_file', 'download'):
            canonical = lambda rows: sorted(rows or [], key=lambda row: str(row.get('id', '')))
            assert canonical(restored_snapshot[table]) == canonical(before[table]), 'V12 state changed: ' + table
        assert any(row['deletion_requested'] for row in restored_snapshot['local_file'])
        assert any(row['status'] == 'CANCELED' for row in restored_snapshot['download'])
        results.append('V12 deletion and cancellation intents restored exactly: ' + ('with media' if media else 'without media'))
        print('PASS: V12 deletion/cancellation intents preserved with ' + ('media' if media else 'no media'), flush=True)""")
    source = replace_once(source, "    # Inject an unsupported link only into this run's media; source bytes are untouched.", """        # Release this iteration's empty network; retain every synthetic volume.
        b.docker('stop', '--timeout', '60', source)
        b.docker('rm', source, *(target + '-' + service for service in b.SERVICES))
        b.docker('network', 'rm', target + '-internal')
    # Inject an unsupported link only into this run's media; source bytes are untouched.""")
    execution = {"__name__": "__main__", "__file__": str(original),
                 "_backend_image": args.backend_image, "_frontend_image": args.frontend_image,
                 "_source_fixture": source_fixture}
    exec(compile(source, str(original), "exec"), execution)


if __name__ == "__main__":
    main()
