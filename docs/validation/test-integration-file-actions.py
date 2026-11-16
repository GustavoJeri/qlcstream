"""Run only uniquely named, isolated synthetic services; never build/pull/install."""
import argparse
import json
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", required=True, help="Explicitly built runtime image with current JAR and deletion helper")
    parser.add_argument("--frontend-image", required=True, help="Explicitly built frontend image with nginx")
    parser.add_argument("--evidence-dir", required=True, type=Path)
    args = parser.parse_args()
    args.evidence_dir.mkdir(parents=True, exist_ok=True)
    project = "qlc-file-actions-" + uuid.uuid4().hex[:10]
    override = args.evidence_dir / (project + ".json")
    services = {
        "prepare": {"command": ["python", "/fixture/file-actions-fixture.py", "prepare"]},
        "source": {"command": ["python", "/fixture/file-actions-fixture.py", "serve"]},
        "backend": {"image": args.image, "volumes": ["outside:/outside"]},
        "runner": {"volumes": ["outside:/outside"]},
        "frontend": {"image": args.frontend_image, "depends_on": ["backend"],
                     "volumes": [str(ROOT / "frontend/nginx.conf") + ":/etc/nginx/conf.d/default.conf:ro"]},
    }
    override.write_text(json.dumps({"services": services, "volumes": {"outside": {}}}), encoding="utf-8")
    command = ["docker", "compose", "--env-file", str(ROOT / "docs/validation/integrations.env"),
               "-p", project, "-f", str(ROOT / "docs/validation/versions-compose.yaml"), "-f", str(override)]
    transcript = []

    def run(*arguments):
        result = subprocess.run(command + list(arguments), cwd=ROOT, capture_output=True, text=True)
        # Runtime logs and remote error bodies are deliberately never exposed.
        safe = [line for line in result.stdout.splitlines() if line.startswith(("PASS:", "FAIL:"))]
        transcript.extend(safe)
        for line in safe:
            print(line, flush=True)
        if result.returncode:
            raise RuntimeError("synthetic command failed: " + arguments[0])
        return result.stdout.strip()

    def phase(name):
        run("run", "--rm", "--no-deps", "--pull", "never", "runner", "python", "/fixture/file-actions-fixture.py", name)

    try:
        run("run", "--rm", "--no-deps", "--pull", "never", "prepare")
        run("up", "-d", "--no-build", "--pull", "never", "database", "qbittorrent", "seeder", "source", "backend", "frontend")
        phase("cancel")
        run("restart", "backend")
        phase("verify-cancel")
        phase("delete")
        phase("escape-prepare")
        run("exec", "-T", "database", "psql", "-U", "versions_test", "-d", "versions_test", "-v", "ON_ERROR_STOP=1", "-c",
            "INSERT INTO local_file(movie_id,storage_root_id,relative_path,file_kind,size_bytes,series_tmdb_id,series_title) "
            "VALUES(NULL,1,'incoming/escape/owned.mp4','VIDEO',20,202021,'Owned escape fixture')")
        phase("escape-verify")
        run("exec", "-T", "database", "psql", "-U", "versions_test", "-d", "versions_test", "-v", "ON_ERROR_STOP=1", "-c",
            "INSERT INTO download(id,storage_root_id,idempotency_key,engine,media_type,series_tmdb_id,series_title,"
            "release_title,acquisition_ref,relative_directory,status,engine_state,info_hash) "
            "VALUES(gen_random_uuid(),1,gen_random_uuid(),'QBITTORRENT','SERIES',202022,'Owned SSE fixture',"
            "'Owned queue only','fixture:no-transfer','incoming/sse-no-transfer','ERROR','missing',repeat('f',40))")
        phase("sse")
        print("PASS: integration file actions complete; synthetic project " + project, flush=True)
    finally:
        run("stop", "backend", "qbittorrent", "seeder", "source", "frontend", "database")
        (args.evidence_dir / (project + "-results.json")).write_text(
            json.dumps({"project": project, "evidence": transcript, "volumesRetained": True}, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
