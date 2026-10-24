"""Produce an immutable CI-source manifest; never include signing files or environment secrets."""
import hashlib
import json
import os
from pathlib import Path

root = Path("dist")
files = sorted(p for p in root.iterdir() if p.is_file())
if not any(p.name.endswith("-arm64-v8a.apk") for p in files):
    raise SystemExit("Missing arm64 APK")
metadata = json.loads(Path("app/build/outputs/apk/release/output-metadata.json").read_text())
version = metadata["elements"][0]
digests = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
manifest = {
    "repository": os.environ["GITHUB_REPOSITORY"],
    "commit": os.environ["GITHUB_SHA"],
    "run_id": os.environ["GITHUB_RUN_ID"],
    "run_attempt": os.environ["GITHUB_RUN_ATTEMPT"],
    "version_name": version["versionName"],
    "version_code": version["versionCode"],
    "sha256": digests,
}
(root / "build-info.json").write_text(json.dumps(manifest, indent=2) + "\n")
(root / "SHA256SUMS.txt").write_text("".join(f"{digest}  {name}\n" for name, digest in digests.items()))
