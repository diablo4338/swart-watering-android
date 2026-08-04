import argparse
import hashlib
import json
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Publish an APK for the local public API")
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--releases-dir", required=True, type=Path)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    return parser.parse_args()


def git_commit() -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "local"


def write_json_atomic(path: Path, payload: dict[str, object]) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def main() -> None:
    args = parse_args()
    apk = args.apk.resolve()
    if not apk.is_file():
        raise SystemExit(f"APK does not exist: {apk}")
    if args.version_code <= 0:
        raise SystemExit("version code must be positive")

    releases_dir = args.releases_dir.resolve()
    version_dir = releases_dir / str(args.version_code)
    version_dir.mkdir(parents=True, exist_ok=True)
    filename = f"smart-watering-{args.version_name}-{args.version_code}-debug.apk"
    published_apk = version_dir / filename
    shutil.copy2(apk, published_apk)

    payload: dict[str, object] = {
        "version_name": args.version_name,
        "version_code": args.version_code,
        "filename": filename,
        "sha256": hashlib.sha256(published_apk.read_bytes()).hexdigest(),
        "size": published_apk.stat().st_size,
        "published_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "git_commit": git_commit(),
    }
    write_json_atomic(version_dir / "manifest.json", payload)
    write_json_atomic(releases_dir / "latest.json", payload)


if __name__ == "__main__":
    main()
