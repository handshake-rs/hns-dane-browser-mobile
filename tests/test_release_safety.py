#!/usr/bin/env python3
from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[1]
PLAY_UPLOAD = ROOT / "scripts" / "play-upload-closed-testing.sh"
IOS_UPLOAD = ROOT / "scripts" / "upload-ios-app-store.sh"
UPLOAD_WORKFLOW = ROOT / ".github" / "workflows" / "ios-app-store-upload.yml"
SCREENSHOT_WORKFLOW = ROOT / ".github" / "workflows" / "ios-screenshots.yml"


class IosReleaseWorkflowSafetyTests(unittest.TestCase):
    def test_both_workflows_pin_and_read_back_a_lowercase_commit(self) -> None:
        for workflow_path in (UPLOAD_WORKFLOW, SCREENSHOT_WORKFLOW):
            with self.subTest(workflow=workflow_path.name):
                workflow = workflow_path.read_text(encoding="utf-8")
                self.assertRegex(
                    workflow,
                    r"expected_commit:\n"
                    r"(?:[ \t]+.*\n){1,4}"
                    r"[ \t]+required: true\n",
                )
                self.assertIn(
                    "grep -Eq '^[0-9a-f]{40}$'",
                    workflow,
                )
                self.assertIn("ref: ${{ inputs.expected_commit }}", workflow)
                self.assertIn(
                    'test "$(git rev-parse HEAD)" = "$EXPECTED_COMMIT"',
                    workflow,
                )
                self.assertIn(
                    "${{ inputs.expected_commit }}",
                    next(
                        line
                        for line in workflow.splitlines()
                        if line.strip().startswith("group:")
                    ),
                )

    def test_upload_requires_current_main_and_sha_keys_every_artifact(self) -> None:
        workflow = UPLOAD_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("github.ref == 'refs/heads/main'", workflow)
        self.assertIn("DISPATCH_COMMIT: ${{ github.sha }}", workflow)
        self.assertIn('[[ "$DISPATCH_COMMIT" == "$EXPECTED_COMMIT" ]]', workflow)
        self.assertIn("git ls-remote --exit-code origin refs/heads/main", workflow)
        self.assertLess(
            workflow.index("Recheck current main before using upload credentials"),
            workflow.index("Materialize temporary signing credentials"),
        )
        self.assertIn("group: global-ios-app-store-upload-lease", workflow)
        self.assertIn(
            "group: sha-${{ inputs.expected_commit }}-ios-app-store-upload",
            workflow,
        )
        self.assertIn(
            "HNS_RELEASE_EXPECTED_COMMIT: ${{ inputs.expected_commit }}",
            workflow,
        )
        self.assertIn(
            "name: ios-app-store-ipa-${{ inputs.expected_commit }}",
            workflow,
        )
        self.assertIn('"sourceCommit": expected_commit', workflow)
        self.assertIn(
            'manifest.get("capture", {}).get("commit") != expected_commit',
            workflow,
        )
        self.assertNotIn("name: ios-app-store-ipa-${{ github.sha }}", workflow)
        self.assertNotIn(
            "name: ios-app-store-live-screenshots-${{ github.sha }}",
            workflow,
        )
        self.assertNotIn(
            "name: ios-app-store-screenshot-diagnostics-${{ github.sha }}",
            workflow,
        )
        self.assertRegex(
            workflow,
            r"(?s)name: ios-app-store-ipa-\$\{\{ inputs\.expected_commit \}\}"
            r".*?path: \|"
            r".*?hns-dane-browser-ios-app-store\.ipa"
            r".*?hns-dane-browser-ios-app-store\.provenance\.json",
        )
        self.assertRegex(
            workflow,
            r"(?s)name: ios-app-store-live-screenshots-"
            r"\$\{\{ inputs\.expected_commit \}\}"
            r".*?path: \|"
            r".*?build/app-store-live-screenshots/\*\.jpg"
            r".*?build/app-store-live-screenshots/manifest\.json",
        )

    def test_upload_script_rechecks_main_immediately_before_apple_upload(self) -> None:
        script = IOS_UPLOAD.read_text(encoding="utf-8")
        self.assertIn(
            'EXPECTED_COMMIT="${HNS_RELEASE_EXPECTED_COMMIT:-}"',
            script,
        )
        self.assertIn(
            'RELEASE_REMOTE_URL="https://github.com/handshake-rs/hns-dane-browser-mobile.git"',
            script,
        )
        self.assertRegex(
            script,
            r"verify_exact_current_main\n"
            r"xcodebuild \\\n"
            r"  -exportArchive \\\n"
            r"(?:.*\n){1,8}"
            r'  -exportOptionsPlist "\$upload_export_options"',
        )

    def test_screenshot_artifacts_and_manifest_use_expected_commit(self) -> None:
        workflow = SCREENSHOT_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn(
            "name: ios-app-store-live-screenshots-${{ inputs.expected_commit }}",
            workflow,
        )
        self.assertIn(
            "name: ios-app-store-screenshot-diagnostics-${{ inputs.expected_commit }}",
            workflow,
        )
        self.assertIn(
            'manifest.get("capture", {}).get("commit") != expected_commit',
            workflow,
        )


class PlayUploadVersionCodeTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.temp_path = Path(self.temporary.name)
        self.bin_path = self.temp_path / "bin"
        self.bin_path.mkdir()
        self.aab_path = self.temp_path / "release.aab"
        self.aab_path.write_bytes(b"mock signed bundle")
        self.request_log = self.temp_path / "requests.log"
        self.mock_curl = self.bin_path / "curl"
        self.mock_curl.write_text(
            textwrap.dedent(
                """\
                #!/usr/bin/env python3
                import json
                import os
                from pathlib import Path
                import sys

                arguments = sys.argv[1:]
                output = Path(arguments[arguments.index("-o") + 1])
                method = arguments[arguments.index("-X") + 1]
                url = arguments[-1]
                with Path(os.environ["MOCK_REQUEST_LOG"]).open("a", encoding="utf-8") as log:
                    log.write(f"{method} {url}\\n")
                if method == "POST" and url.endswith("/edits"):
                    response = {"id": "mock-edit"}
                elif method == "POST" and "/bundles?uploadType=media" in url:
                    response = {"versionCode": os.environ["MOCK_VERSION_CODE"]}
                elif method == "PUT" and "/tracks/" in url:
                    response = {"track": "mock"}
                elif method == "POST" and url.endswith(":commit"):
                    response = {"id": "mock-edit", "expiryTimeSeconds": "1"}
                else:
                    raise SystemExit(f"unexpected mocked request: {method} {url}")
                output.write_text(json.dumps(response), encoding="utf-8")
                print("200", end="")
                """
            ),
            encoding="utf-8",
        )
        self.mock_curl.chmod(0o755)

        gradle = (ROOT / "android" / "app" / "build.gradle.kts").read_text(
            encoding="utf-8"
        )
        matches = re.findall(r"^\s*versionCode = ([0-9]+)", gradle, re.MULTILINE)
        self.assertEqual(len(matches), 1)
        self.configured_version_code = matches[0]

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_upload(
        self,
        api_version_code: str,
        *,
        expected_version_code: str | None = None,
    ) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        for name in tuple(env):
            if name.startswith("PLAY_"):
                env.pop(name)
        env.update(
            {
                "MOCK_REQUEST_LOG": str(self.request_log),
                "MOCK_VERSION_CODE": api_version_code,
                "PATH": f"{self.bin_path}{os.pathsep}{env['PATH']}",
                "PLAY_ACCESS_TOKEN": "mock-access-token",
                "PLAY_RELEASE_STATUS": "draft",
            }
        )
        if expected_version_code is None:
            env.pop("PLAY_EXPECTED_VERSION_CODE", None)
        else:
            env["PLAY_EXPECTED_VERSION_CODE"] = expected_version_code
        return subprocess.run(
            [str(PLAY_UPLOAD), str(self.aab_path)],
            cwd=ROOT,
            env=env,
            text=True,
            capture_output=True,
        )

    def request_lines(self) -> list[str]:
        if not self.request_log.exists():
            return []
        return self.request_log.read_text(encoding="utf-8").splitlines()

    def test_configured_version_code_allows_assignment_and_commit(self) -> None:
        result = self.run_upload(self.configured_version_code)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(
            f"Verified uploaded bundle versionCode {self.configured_version_code}.",
            result.stdout,
        )
        requests = self.request_lines()
        self.assertEqual(len(requests), 4)
        self.assertTrue(any("/tracks/" in request for request in requests))
        self.assertTrue(any(request.endswith(":commit") for request in requests))

    def test_mismatch_stops_before_track_assignment_and_commit(self) -> None:
        mismatched = str(int(self.configured_version_code) + 1)
        result = self.run_upload(mismatched)
        self.assertEqual(result.returncode, 1)
        self.assertIn("Refusing to assign or commit bundle versionCode", result.stderr)
        requests = self.request_lines()
        self.assertEqual(len(requests), 2)
        self.assertFalse(any("/tracks/" in request for request in requests))
        self.assertFalse(any(request.endswith(":commit") for request in requests))

    def test_matching_explicit_expected_version_code_allows_commit(self) -> None:
        overridden = str(int(self.configured_version_code) + 1)
        result = self.run_upload(
            overridden,
            expected_version_code=overridden,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("PLAY_EXPECTED_VERSION_CODE override", result.stdout)

    def test_explicit_override_mismatch_stops_before_assignment_and_commit(self) -> None:
        overridden = str(int(self.configured_version_code) + 1)
        mismatched = str(int(overridden) + 1)
        result = self.run_upload(
            mismatched,
            expected_version_code=overridden,
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("Refusing to assign or commit bundle versionCode", result.stderr)
        requests = self.request_lines()
        self.assertEqual(len(requests), 2)
        self.assertFalse(any("/tracks/" in request for request in requests))
        self.assertFalse(any(request.endswith(":commit") for request in requests))

    def test_invalid_override_fails_before_any_api_request(self) -> None:
        result = self.run_upload(
            self.configured_version_code,
            expected_version_code="048",
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("Expected Play versionCode is invalid", result.stderr)
        self.assertEqual(self.request_lines(), [])


if __name__ == "__main__":
    unittest.main()
