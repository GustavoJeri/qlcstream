"""Host orchestrator: uniquely named Compose projects, offline synthetic volume copy."""
import hashlib
import json
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[2]
IDENT = uuid.uuid4().hex[:10]
OLD = "qlcstream-versions-old-" + IDENT
NEW = "qlcstream-versions-copy-" + IDENT
COMPOSE = str(ROOT / "docs/validation/versions-compose.yaml")
ENV = str(ROOT / "docs/validation/integrations.env")
VOLUMES = ("database", "engine", "seed", "prowlarr", "payload", "media", "keys")
SCRATCH = ROOT / "data" / ("versions-validation-" + IDENT)
SCRATCH.mkdir(parents=True)


def run(args, expected_error=None, **kwargs):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, **kwargs)
    if expected_error is not None:
        assert result.returncode != 0 and expected_error in result.stderr, "expected fault was not detected"
        return "PASS: rejected " + expected_error
    if result.returncode:
        # Only synthetic fixture commands are passed here. No service logs or personal config.
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout.strip()


def compose(project, *args, expected_error=None):
    files = ["-f", COMPOSE]
    if project == OLD:
        files += ["-f", str(SCRATCH / "previous.yaml")]
    return run(["docker", "compose", "--env-file", ENV, "-p", project, *files, *args], expected_error=expected_error)


def snapshot(project):
    files = compose(project, "run", "--rm", "--no-deps", "inspect", "-c",
                    "import hashlib,json,pathlib; paths=['/keys/integration-settings.key',"
                    "'/media/incoming/preserved.txt','/media/incoming/QLCStream.Owned.2026.1080p.bin',"
                    "'/media/incoming/prowlarr-indexer.json',"
                    "'/prowlarr/config.xml','/engine/qBittorrent/config/qBittorrent.conf']; "
                    "print(json.dumps({p:hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest() for p in paths},sort_keys=True))")
    rows = compose(project, "exec", "-T", "database", "psql", "-U", "versions_test", "-d", "versions_test", "-tAc",
                   "SELECT row_to_json(t) FROM (SELECT * FROM integration_settings ORDER BY integration_id) t")
    return {"files": json.loads(files), "settings": hashlib.sha256(rows.encode()).hexdigest()}


def inspect_identity(project):
    for service in ("qbittorrent", "prowlarr"):
        container = compose(project, "ps", "-q", service)
        configured, actual = json.loads(run(["docker", "inspect", "--format", "{{json .Config.Image}}", container])), run(
            ["docker", "inspect", "--format", "{{.Image}}", container])
        expected = json.loads(run(["docker", "compose", "--env-file", ENV, "-p", project, "-f", COMPOSE,
                                   "config", "--format", "json"]))["services"][service]["image"]
        expected_id = run(["docker", "image", "inspect", "--format", "{{.Id}}", expected])
        assert actual == expected_id, "image identity mismatch"
        if project == NEW:
            assert configured == expected, "image reference mismatch"
        print("PASS: " + service + " reference and running image ID match " + expected)


try:
    # Existing installation is synthetic, using the images captured before pinning.
    # These are the same application versions: this tests adoption of immutable references,
    # not a downgrade or an unproven database/schema migration.
    (SCRATCH / "previous.yaml").write_text("services:\n  qbittorrent:\n"
        "    image: qbittorrentofficial/qbittorrent-nox@sha256:43b2d352cf76330a7ca34c7320b9b74227d6f31e662e0e123d0457206b09a7ea\n"
        "    environment:\n      QBT_VERSION: 5.1.4\n  prowlarr:\n"
        "    image: lscr.io/linuxserver/prowlarr@sha256:6660787a99b2e58203539583663c973b48979fc00ff2cb78dc0ea17429e1f6ef\n")
    compose(OLD, "build", "backend")
    print(compose(OLD, "run", "--rm", "--no-deps", "prepare"), flush=True)
    compose(OLD, "up", "-d", "database", "qbittorrent", "seeder", "prowlarr", "source", "backend")
    print(compose(OLD, "run", "--rm", "--no-deps", "runner", "python", "/fixture/versions-fixture.py", "test-clean"), flush=True)
    inspect_identity(OLD)
    # Verify physical file sharing from the actual backend identity, with no HTTP inference.
    proof = compose(OLD, "exec", "-T", "backend", "sh", "-c",
                    "id; sha256sum /data/incoming/QLCStream.Owned.2026.1080p.bin; "
                    "stat -c '%u:%g:%a' /data/incoming; echo backend-proof > /data/incoming/backend-proof.txt")
    print("PASS: backend cross-read and write: " + proof, flush=True)
    compose(OLD, "stop", "backend", "qbittorrent", "prowlarr")
    before = snapshot(OLD)
    compose(OLD, "start", "backend", "qbittorrent", "prowlarr")
    print(compose(OLD, "run", "--rm", "--no-deps", "runner"), flush=True)
    compose(OLD, "stop", "backend", "qbittorrent", "prowlarr")
    (SCRATCH / "restart-snapshots.json").write_text(json.dumps({"before": before, "after": snapshot(OLD)}, indent=2))
    assert snapshot(OLD) == before, "restart changed preserved state"
    compose(OLD, "stop")
    # All writers are stopped before a byte-for-byte copy; only this run's known volumes.
    for volume in VOLUMES:
        run(["docker", "volume", "create", NEW + "_" + volume])
        run(["docker", "run", "--rm", "--network", "none", "--pull", "missing",
             "-v", OLD + "_" + volume + ":/source:ro", "-v", NEW + "_" + volume + ":/target",
             "python:3.13-alpine@sha256:9ef1a599f863231c3d91e941389bfdc4a2d6070557243d54df1a4e57015b960e", "python", "-c",
             "import shutil; shutil.copytree('/source','/target',dirs_exist_ok=True,copy_function=shutil.copy2)"])
        # copy2 preserves mode/time, not ownership. Restore the source metadata explicitly.
        run(["docker", "run", "--rm", "--network", "none", "--pull", "never",
             "-v", OLD + "_" + volume + ":/source:ro", "-v", NEW + "_" + volume + ":/target",
             "python:3.13-alpine@sha256:9ef1a599f863231c3d91e941389bfdc4a2d6070557243d54df1a4e57015b960e", "python", "-c",
             "import os,pathlib; roots=[pathlib.Path('/source')]; roots+=list(roots[0].rglob('*')); "
             "[(os.chown('/target/'+str(p.relative_to('/source')),p.lstat().st_uid,p.lstat().st_gid,follow_symlinks=False)) for p in roots]"])
    compose(NEW, "build", "backend")
    compose(NEW, "up", "-d", "database", "qbittorrent", "seeder", "prowlarr", "source", "backend")
    print(compose(NEW, "run", "--rm", "--no-deps", "runner"), flush=True)
    inspect_identity(NEW)
    compose(NEW, "stop", "backend", "qbittorrent", "prowlarr")
    assert snapshot(NEW) == before, "copied update changed preserved state"
    # Fault injection affects only this run's synthetic copy; retain and restore its DB.
    compose(NEW, "run", "--rm", "--no-deps", "inspect", "-c",
            "from pathlib import Path; import shutil; root=Path('/prowlarr'); "
            "backup=root/'fault-backup'; backup.mkdir(); files=list(root.glob('prowlarr.db*')); "
            "assert files; [shutil.move(str(p),str(backup/p.name)) for p in files]")
    compose(NEW, "start", "prowlarr")
    print(compose(NEW, "run", "--rm", "--no-deps", "runner", "python", "/fixture/versions-fixture.py", "check-indexer",
                  expected_error="persisted Prowlarr indexer missing or changed"), flush=True)
    compose(NEW, "stop", "prowlarr")
    compose(NEW, "run", "--rm", "--no-deps", "inspect", "-c",
            "from pathlib import Path; import shutil; root=Path('/prowlarr'); "
            "generated=root/'fault-generated'; generated.mkdir(); "
            "[shutil.move(str(p),str(generated/p.name)) for p in root.glob('prowlarr.db*')]; "
            "[shutil.move(str(p),str(root/p.name)) for p in (root/'fault-backup').iterdir()]")
    compose(NEW, "start", "prowlarr")
    compose(NEW, "run", "--rm", "--no-deps", "runner", "python", "/fixture/versions-fixture.py", "check-indexer")
    compose(NEW, "stop", "prowlarr")
    assert snapshot(NEW) == before, "fault recovery changed preserved state"
    (SCRATCH / "result.json").write_text(json.dumps({"status": "passed", "oldProject": OLD, "copyProject": NEW,
                                                    "missingProwlarrDatabase": "rejected",
                                                    "javaCancellation": "own_save_path_hash_preserved",
                                                    "preserved": before}, indent=2))
    print("PASS: clean install, restart, stopped synthetic volume copy and pinned recreation; files/key/settings unchanged")
finally:
    # Keep all volumes and the local result for review, stop/remove only our containers.
    for project in (OLD, NEW):
        compose(project, "down", "--remove-orphans")
    print("Synthetic volumes retained for review: " + OLD + ", " + NEW)
