import hashlib
import json
import os
import runpy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("release_manifest.py").resolve()


class ReleaseManifestTest(unittest.TestCase):
    def test_records_only_artifacts_and_matches_ci_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "dist").mkdir()
            apk = root / "dist/rikkahub-arm64-v8a.apk"
            apk.write_bytes(b"signed-ci-package")
            (root / "dist/mapping.txt").write_text("mapping")
            metadata = root / "app/build/outputs/apk/release/output-metadata.json"
            metadata.parent.mkdir(parents=True)
            metadata.write_text(json.dumps({"elements": [{"versionName": "2.5.9", "versionCode": 194}]}))
            (root / "local.properties").write_text("secret-password")
            environment = {"GITHUB_REPOSITORY": "itflash/rikkahub", "GITHUB_SHA": "abc123",
                           "GITHUB_RUN_ID": "99", "GITHUB_RUN_ATTEMPT": "1"}
            previous = Path.cwd()
            try:
                os.chdir(root)
                with patch.dict(os.environ, environment):
                    runpy.run_path(str(SCRIPT), run_name="__main__")
            finally:
                os.chdir(previous)
            info = json.loads((root / "dist/build-info.json").read_text())
            self.assertEqual("abc123", info["commit"])
            self.assertEqual("99", info["run_id"])
            self.assertEqual(194, info["version_code"])
            self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(), info["sha256"][apk.name])
            self.assertEqual({apk.name, "mapping.txt"}, set(info["sha256"]))
            self.assertNotIn("secret-password", (root / "dist/build-info.json").read_text())
            self.assertIn(f"{info['sha256'][apk.name]}  {apk.name}", (root / "dist/SHA256SUMS.txt").read_text())

    def test_missing_apk_refuses_to_create_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "dist").mkdir()
            previous = Path.cwd()
            try:
                os.chdir(root)
                with self.assertRaises(SystemExit):
                    runpy.run_path(str(SCRIPT), run_name="__main__")
            finally:
                os.chdir(previous)
            self.assertFalse((root / "dist/build-info.json").exists())


if __name__ == "__main__":
    unittest.main()
