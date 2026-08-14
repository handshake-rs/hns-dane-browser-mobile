#!/usr/bin/env python3
from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import tomllib
import unittest


ROOT = Path(__file__).resolve().parents[1]
PLAY_UPLOAD = ROOT / "scripts" / "play-upload-closed-testing.sh"
IOS_UPLOAD = ROOT / "scripts" / "upload-ios-app-store.sh"
UPLOAD_WORKFLOW = ROOT / ".github" / "workflows" / "ios-app-store-upload.yml"
SCREENSHOT_WORKFLOW = ROOT / ".github" / "workflows" / "ios-screenshots.yml"
SCREENSHOT_UI_TEST = (
    ROOT / "ios" / "HnsDaneBrowserScreenshotTests" / "AppStoreScreenshotTests.swift"
)
SCREENSHOT_TOOLS = ROOT / "scripts" / "ios_screenshot_tools.py"
APP_STORE_VALIDATOR = ROOT / "store-assets" / "app-store" / "validate.py"


class ReleaseCandidateMetadataTests(unittest.TestCase):
    def test_0510_platform_identity_and_reviewed_wallet_pin(self) -> None:
        gradle = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertRegex(gradle, r"(?m)^\s*versionName = \"0\.5\.10\"$")
        self.assertRegex(gradle, r"(?m)^\s*versionCode = 51$")

        with (ROOT / "rust/Cargo.toml").open("rb") as source:
            manifest = tomllib.load(source)
        self.assertEqual(manifest["workspace"]["package"]["version"], "0.5.9")
        self.assertFalse(manifest["workspace"]["package"]["publish"])
        wallet = manifest["workspace"]["dependencies"]["hns-wallet-mobile"]
        self.assertEqual(wallet["version"], "=0.1.0")
        self.assertEqual(
            wallet["rev"],
            "49afe81abce3d3f1a9309e26962731e181e43051",
        )

        lockfile = (ROOT / "rust/Cargo.lock").read_text(encoding="utf-8")
        self.assertIn(
            "hns-wallet-rs.git?rev="
            "49afe81abce3d3f1a9309e26962731e181e43051",
            lockfile,
        )
        self.assertIn(
            "hns-rs.git?rev=88ed7c64db52a6fcfce4146a8fc17b1377dfcc8e",
            lockfile,
        )
        self.assertNotIn("f83d42363305de04bfa955f864cb1e9136c4d648", lockfile)
        self.assertNotIn("abf11ff3b16920c08f3c0b6d32d2e1af7cbe37b2", lockfile)
        self.assertNotIn("2229be849557d58a8eb723bcc03349f0f2df9796", lockfile)
        self.assertNotIn("b24b66c382de53330ec21dd3137e056a2bea3e2d", lockfile)

        project = (ROOT / "ios/project.yml").read_text(encoding="utf-8")
        self.assertRegex(project, r"(?m)^\s*MARKETING_VERSION: 0\.5\.10$")
        self.assertRegex(project, r"(?m)^\s*CURRENT_PROJECT_VERSION: 60$")

    def test_unshipped_named_service_market_and_value_closures_stay_absent(self) -> None:
        with (ROOT / "rust/Cargo.lock").open("rb") as source:
            lockfile = tomllib.load(source)
        package_names = {package["name"] for package in lockfile["package"]}
        forbidden_packages = {
            "hns-hnsr-protocol",
            "hns-marketplace-protocol",
            "hns-p2p-experimental",
            "hns-service-authority",
            "hns-wallet-bitcoin-kyoto",
            "hns-wallet-ethereum",
            "hns-wallet-market",
            "hns-wallet-shakedex",
        }
        self.assertTrue(forbidden_packages.isdisjoint(package_names))

        android_protocol = (
            ROOT
            / "android/app/src/main/java/com/denuoweb/hnsdane/wallet/"
            "MobileWalletProviderProtocol.kt"
        ).read_text(encoding="utf-8")
        for gate in (
            "PROVIDER_BRIDGE_RELEASE_QUALIFIED",
            "WALLET_RUNTIME_RELEASE_QUALIFIED",
            "APPROVAL_RUNTIME_RELEASE_QUALIFIED",
            "VALUE_RUNTIME_RELEASE_QUALIFIED",
        ):
            self.assertIn(f"const val {gate} = false", android_protocol)

        ios_protocol = (
            ROOT / "ios/HnsDaneBrowser/Wallet/WalletProviderProtocol.swift"
        ).read_text(encoding="utf-8")
        for gate in (
            "providerBridgeReleaseQualified",
            "walletRuntimeReleaseQualified",
            "approvalRuntimeReleaseQualified",
            "valueRuntimeReleaseQualified",
        ):
            self.assertIn(f"static let {gate} = false", ios_protocol)

        for relative in (
            "android/app/src/main/java/com/denuoweb/hnsdane/wallet/"
            "MobileWalletProviderProtocol.kt",
            "android/app/src/main/java/com/denuoweb/hnsdane/wallet/"
            "AndroidWalletProviderBridge.kt",
            "ios/HnsDaneBrowser/Wallet/WalletProviderProtocol.swift",
            "ios/HnsDaneBrowser/Wallet/WalletWebKitBridge.swift",
        ):
            with self.subTest(provider_boundary=relative):
                source = (ROOT / relative).read_text(encoding="utf-8")
                self.assertNotIn("nameReceiveTarget", source)

    def test_store_and_privacy_copy_describes_fail_closed_native_reads(self) -> None:
        paths = (
            ROOT / "store-assets/play-store/metadata/en-US/full-description.txt",
            ROOT / "store-assets/play-store/metadata/en-US/release-notes.txt",
            ROOT / "store-assets/app-store/metadata/en-US/description.txt",
            ROOT / "store-assets/app-store/metadata/en-US/review-notes.txt",
            ROOT / "store-assets/app-store/metadata/en-US/whats-new.txt",
        )
        for path in paths:
            with self.subTest(path=path):
                value = path.read_text(encoding="utf-8").casefold()
                self.assertIn("read-only", value)
                self.assertIn("unavailable", value)
                self.assertIn("indexed backend", value)
                self.assertNotIn("not a wallet", value)

        privacy = (ROOT / "docs/privacy-policy.md").read_text(
            encoding="utf-8"
        ).casefold()
        for marker in (
            "device-local hns account identity",
            "device-bound database keys",
            "scoped companion credential or indexed wallet backend",
            "no wallet-specific network request is made",
            "hnsa/hnsr service roles",
            "deletes the incomplete wallet database",
            "two destructive confirmations",
            "type `delete` exactly",
            "deletes the device-bound database key before deleting the encrypted database",
            "remaining encrypted orphan cannot be reopened",
            "does not remove a recovery phrase or wallet backup saved elsewhere",
        ):
            self.assertIn(marker, privacy)

        play = paths[0].read_text(encoding="utf-8").casefold()
        app_store = paths[2].read_text(encoding="utf-8").casefold()
        for marker in (
            "balance",
            "receive target",
            "name import",
            "website-provider",
            "settlement",
            "exchange",
            "p2p marketplaces",
        ):
            self.assertIn(marker, play)
            self.assertIn(marker, app_store)
        for marker in (
            "confirmed wallet can be deleted locally",
            "two destructive confirmations",
            "requiring delete",
        ):
            self.assertIn(marker, play)
            self.assertIn(marker, app_store)


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
        capture_index = workflow.index(
            "Capture mandatory live Release App Store screenshots"
        )
        verify_index = workflow.index(
            "Verify mandatory screenshots against the exact upload commit"
        )
        credential_index = workflow.index("Materialize temporary signing credentials")
        upload_index = workflow.index("Create the signed archive and upload it")
        self.assertLess(capture_index, verify_index)
        self.assertLess(verify_index, credential_index)
        self.assertLess(credential_index, upload_index)
        self.assertLess(verify_index, workflow.index("${{ secrets."))
        self.assertLess(
            workflow.index("Recheck current main before using upload credentials"),
            credential_index,
        )
        self.assertNotIn("continue-on-error: true", workflow)
        self.assertNotIn("non-blocking screenshot failure", workflow)
        self.assertIn(
            "if: failure() && steps.live_capture.outcome == 'failure'",
            workflow,
        )
        self.assertIn(
            'scripts/ios_screenshot_tools.py verify-live \\\n'
            "            --directory build/app-store-live-screenshots \\\n"
            '            --expected-commit "$EXPECTED_COMMIT"',
            workflow,
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
            'scripts/ios_screenshot_tools.py verify-live \\\n'
            "            --directory build/app-store-live-screenshots \\\n"
            '            --expected-commit "$EXPECTED_COMMIT"',
            workflow,
        )

    def test_screenshot_evidence_requires_a_visible_native_wallet_row(self) -> None:
        ui_test = SCREENSHOT_UI_TEST.read_text(encoding="utf-8")
        self.assertIn(
            'let walletRowIdentifier = "settings.wallet.native-controls"',
            ui_test,
        )
        self.assertIn(
            "scrollDown(in: table, untilFullyVisible: walletRow)",
            ui_test,
        )
        self.assertIn(
            '"nativeWalletRowIdentifier": walletRowIdentifier',
            ui_test,
        )

        tools = SCREENSHOT_TOOLS.read_text(encoding="utf-8")
        self.assertIn(
            'NATIVE_WALLET_ROW_IDENTIFIER = "settings.wallet.native-controls"',
            tools,
        )
        self.assertIn(
            'document["settings"].get("nativeWalletRowIdentifier")',
            tools,
        )

    def test_app_store_validator_requires_exact_candidate_screenshots(self) -> None:
        validator = APP_STORE_VALIDATOR.read_text(encoding="utf-8")
        self.assertIn('"--expected-commit"', validator)
        self.assertIn("expected_commit=expected_commit", validator)


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
                elif method == "PUT" and "/listings/en-US" in url:
                    response = {"language": "en-US"}
                elif method == "PUT" and "/tracks/" in url:
                    response = {"track": "mock"}
                elif method == "POST" and url.endswith(
                    ":commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW"
                ):
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
        update_listing: bool = False,
        track_name: str | None = "alpha",
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
        if track_name is not None:
            env["PLAY_TRACK"] = track_name
        if expected_version_code is None:
            env.pop("PLAY_EXPECTED_VERSION_CODE", None)
        else:
            env["PLAY_EXPECTED_VERSION_CODE"] = expected_version_code
        if update_listing:
            env["PLAY_UPDATE_LISTING"] = "true"
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
        self.assertTrue(
            any(
                request.endswith(
                    ":commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW"
                )
                for request in requests
            )
        )

    def test_explicit_listing_update_uses_reviewed_en_us_copy(self) -> None:
        result = self.run_upload(
            self.configured_version_code,
            update_listing=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Updated Play listing text: en-US", result.stdout)
        requests = self.request_lines()
        self.assertEqual(len(requests), 5)
        self.assertTrue(any("/listings/en-US" in request for request in requests))
        self.assertTrue(any("/tracks/" in request for request in requests))
        self.assertTrue(
            any(
                request.endswith(
                    ":commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW"
                )
                for request in requests
            )
        )

    def test_mismatch_stops_before_track_assignment_and_commit(self) -> None:
        mismatched = str(int(self.configured_version_code) + 1)
        result = self.run_upload(mismatched)
        self.assertEqual(result.returncode, 1)
        self.assertIn("Refusing to assign or commit bundle versionCode", result.stderr)
        requests = self.request_lines()
        self.assertEqual(len(requests), 2)
        self.assertFalse(any("/tracks/" in request for request in requests))
        self.assertFalse(any(":commit?" in request for request in requests))

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
        self.assertFalse(any(":commit?" in request for request in requests))

    def test_invalid_override_fails_before_any_api_request(self) -> None:
        result = self.run_upload(
            self.configured_version_code,
            expected_version_code="048",
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("Expected Play versionCode is invalid", result.stderr)
        self.assertEqual(self.request_lines(), [])

    def test_missing_track_fails_before_any_api_request(self) -> None:
        result = self.run_upload(
            self.configured_version_code,
            track_name=None,
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("Set PLAY_TRACK explicitly", result.stderr)
        self.assertEqual(self.request_lines(), [])


if __name__ == "__main__":
    unittest.main()
