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
BUILD_IOS = ROOT / "scripts" / "build-ios.sh"
RUN_IOS_GATE = ROOT / "scripts" / "run-ios-gate.sh"
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
UPLOAD_WORKFLOW = ROOT / ".github" / "workflows" / "ios-app-store-upload.yml"
SCREENSHOT_WORKFLOW = ROOT / ".github" / "workflows" / "ios-screenshots.yml"
BROWSER_RUNTIME_CONTROL_TESTS = (
    ROOT / "ios" / "HnsDaneBrowserTests" / "BrowserRuntimeControlTests.swift"
)
SCREENSHOT_UI_TEST = (
    ROOT / "ios" / "HnsDaneBrowserScreenshotTests" / "AppStoreScreenshotTests.swift"
)
SCREENSHOT_TOOLS = ROOT / "scripts" / "ios_screenshot_tools.py"
APP_STORE_VALIDATOR = ROOT / "store-assets" / "app-store" / "validate.py"


class ReleaseCandidateMetadataTests(unittest.TestCase):
    def test_100_platform_identity_and_reviewed_wallet_source_pin(self) -> None:
        gradle = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertRegex(gradle, r"(?m)^\s*versionName = \"1\.0\.1\"$")
        self.assertRegex(gradle, r"(?m)^\s*versionCode = 53$")

        with (ROOT / "rust/Cargo.toml").open("rb") as source:
            manifest = tomllib.load(source)
        self.assertEqual(manifest["workspace"]["package"]["version"], "1.0.0")
        self.assertFalse(manifest["workspace"]["package"]["publish"])
        wallet = manifest["workspace"]["dependencies"]["hns-wallet-mobile"]
        self.assertEqual(wallet, "=0.2.1")

        lockfile = (ROOT / "rust/Cargo.lock").read_text(encoding="utf-8")
        self.assertIn(
            'name = "hns-wallet-mobile"\nversion = "0.2.1"\nsource = "registry+https://github.com/rust-lang/crates.io-index"',
            lockfile,
        )
        self.assertIn(
            'name = "hns-header-consensus"\nversion = "0.4.1"\nsource = "registry+https://github.com/rust-lang/crates.io-index"',
            lockfile,
        )
        for package in (
            "hns-light-chain",
            "hns-light-p2p",
            "hns-light-sync",
            "hns-light-wallet",
        ):
            self.assertIn(
                f'name = "{package}"\nversion = "0.2.3"\nsource = "registry+https://github.com/rust-lang/crates.io-index"',
                lockfile,
            )
        self.assertNotIn("f83d42363305de04bfa955f864cb1e9136c4d648", lockfile)
        self.assertNotIn("abf11ff3b16920c08f3c0b6d32d2e1af7cbe37b2", lockfile)
        self.assertNotIn("2229be849557d58a8eb723bcc03349f0f2df9796", lockfile)
        self.assertNotIn("b24b66c382de53330ec21dd3137e056a2bea3e2d", lockfile)

        project = (ROOT / "ios/project.yml").read_text(encoding="utf-8")
        self.assertRegex(project, r"(?m)^\s*MARKETING_VERSION: 1\.0\.0$")
        self.assertRegex(project, r"(?m)^\s*CURRENT_PROJECT_VERSION: 61$")

    def test_unshipped_named_service_market_and_value_closures_stay_absent(self) -> None:
        with (ROOT / "rust/Cargo.lock").open("rb") as source:
            lockfile = tomllib.load(source)
        package_names = {package["name"] for package in lockfile["package"]}
        forbidden_packages = {
            "hns-hnsr-protocol",
            "hns-service-authority",
        }
        self.assertTrue(forbidden_packages.isdisjoint(package_names))

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
                self.assertNotIn("importHnsNameExactText", source)
                self.assertNotIn("HNWI", source)

    def test_wallet_sync_copy_and_debug_capture_policy_match_platforms(self) -> None:
        android_strings = (
            ROOT / "android/app/src/main/res/values/strings.xml"
        ).read_text(encoding="utf-8")
        self.assertIn(
            "Verifying direct peer headers at height %1$d.", android_strings
        )
        self.assertNotIn("round %1$d of %2$d", android_strings)

        android_wallet = (
            ROOT
            / "android/app/src/main/java/com/denuoweb/hnsdane/ui/WalletActivity.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("if (!BuildConfig.DEBUG)", android_wallet)
        self.assertIn("window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)", android_wallet)

        ios_wallet = (
            ROOT / "ios/HnsDaneBrowser/Wallet/WalletViewController.swift"
        ).read_text(encoding="utf-8")
        self.assertIn(
            'readStatusLabel.text = "Verifying direct peer headers at height '
            '\\(progress.verifiedHeaderHeight)."',
            ios_wallet,
        )
        self.assertIn("#if DEBUG\n        false\n        #else", ios_wallet)
        self.assertIn("#if !DEBUG", ios_wallet)

    def test_store_and_privacy_copy_describes_direct_native_wallet(self) -> None:
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
                self.assertIn("wallet", value)
                self.assertNotIn("indexed backend", value)
                self.assertNotIn("sending or value movement", value)
                self.assertNotIn("no payment flow", value)
                self.assertNotIn("not a wallet", value)

        full_listing = "\n".join(
            path.read_text(encoding="utf-8").casefold()
            for path in (
                ROOT / "store-assets/play-store/metadata/en-US/full-description.txt",
                ROOT / "store-assets/app-store/metadata/en-US/description.txt",
                ROOT / "store-assets/app-store/metadata/en-US/review-notes.txt",
            )
        )
        for marker in (
            "directly with handshake peers",
            "guarded hns send",
            "websites cannot",
            "processed on the device",
            "not expose the unfinished bitcoin",
        ):
            self.assertIn(marker, full_listing)

        privacy = (ROOT / "docs/privacy-policy.md").read_text(
            encoding="utf-8"
        ).casefold()
        for marker in (
            "native device-local hns wallet",
            "device-bound database key",
            "directly to handshake peers",
            "broadcast transaction bytes only after native review",
            "camera access is requested only after you tap the scanner",
            "websites cannot invoke wallet operations",
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
            "payment qr code",
            "guarded hns send",
            "websites cannot invoke wallet",
            "unfinished bitcoin",
            "exchange service",
        ):
            self.assertIn(marker, play)
            self.assertIn(marker, app_store)
        for marker in (
            "protected confirmation flow",
            "recovery phrase",
        ):
            self.assertIn(marker, play)
            self.assertIn(marker, app_store)


class IosReleaseWorkflowSafetyTests(unittest.TestCase):
    def test_ios_gate_result_bundle_is_bounded_and_never_overwritten(self) -> None:
        build_script = BUILD_IOS.read_text(encoding="utf-8")
        for contract in (
            'RESULT_BUNDLE_INPUT="${HNS_IOS_RESULT_BUNDLE_PATH:-}"',
            'root = Path(sys.argv[1]).resolve(strict=False)',
            'build_root = (root / "build").resolve(strict=False)',
            'candidate = requested.resolve(strict=False)',
            "candidate.relative_to(build_root)",
            'candidate.suffix != ".xcresult"',
            '[[ -e "$result_bundle_requested" || -L "$result_bundle_requested" ]]',
            '[[ -e "$result_bundle_path" || -L "$result_bundle_path" ]]',
            'xcodebuild_args+=( -resultBundlePath "$result_bundle_path" )',
            'xcodebuild "${xcodebuild_args[@]}"',
        ):
            with self.subTest(contract=contract):
                self.assertIn(contract, build_script)
        self.assertLess(
            build_script.index('if [[ -n "$RESULT_BUNDLE_INPUT" ]]'),
            build_script.index('if [[ "$REUSE_XCFRAMEWORK" == "1" ]]'),
        )

        gate = RUN_IOS_GATE.read_text(encoding="utf-8")
        for contract in (
            'IOS_GATE_DIAGNOSTICS_DIR="$ROOT_DIR/build/ios-gate-diagnostics"',
            'IOS_GATE_RESULT_BUNDLE="$IOS_GATE_DIAGNOSTICS_DIR/'
            'HnsDaneBrowserTests.xcresult"',
            'IOS_GATE_PHASE_FILE="$IOS_GATE_DIAGNOSTICS_DIR/phase.txt"',
            '[[ "$IOS_GATE_DIAGNOSTICS_DIR" == "$expected_dir" ]]',
            '[[ ! -L "$build_dir" ]]',
            'rm -rf -- "$IOS_GATE_DIAGNOSTICS_DIR"',
            "unset HNS_IOS_RESULT_BUNDLE_PATH",
            'HNS_IOS_RESULT_BUNDLE_PATH="$IOS_GATE_RESULT_BUNDLE"',
        ):
            with self.subTest(contract=contract):
                self.assertIn(contract, gate)
        self.assertEqual(gate.count("HNS_IOS_RESULT_BUNDLE_PATH="), 1)
        simulator_phase = gate.index("record_ios_gate_phase simulator-test")
        result_handoff = gate.index(
            'HNS_IOS_RESULT_BUNDLE_PATH="$IOS_GATE_RESULT_BUNDLE"'
        )
        device_phase = gate.index("record_ios_gate_phase unsigned-device-link")
        self.assertLess(simulator_phase, result_handoff)
        self.assertLess(result_handoff, device_phase)
        for phase in (
            "preflight",
            "rust-abi",
            "simulator-selection",
            "simulator-test",
            "unsigned-device-link",
            "complete",
        ):
            with self.subTest(phase=phase):
                self.assertIn(f"record_ios_gate_phase {phase}", gate)

    def test_ios_gate_failures_retain_attempt_scoped_diagnostics(self) -> None:
        ci_workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        upload_workflow = UPLOAD_WORKFLOW.read_text(encoding="utf-8")
        for workflow, artifact_name in (
            (
                ci_workflow,
                "ios-gate-diagnostics-${{ github.sha }}-attempt-"
                "${{ github.run_attempt }}",
            ),
            (
                upload_workflow,
                "ios-gate-diagnostics-${{ inputs.expected_commit }}-attempt-"
                "${{ github.run_attempt }}",
            ),
        ):
            with self.subTest(artifact=artifact_name):
                gate_run = workflow.index("run: ./scripts/run-ios-gate.sh")
                gate_step_start = workflow.rfind("\n      - name:", 0, gate_run)
                gate_step_end = workflow.find("\n      - name:", gate_run)
                gate_step = workflow[gate_step_start:gate_step_end]
                self.assertIn("id: ios_gate", gate_step)

                artifact_marker = f"name: {artifact_name}"
                artifact_index = workflow.index(artifact_marker)
                artifact_step_start = workflow.rfind(
                    "\n      - name:", 0, artifact_index
                )
                artifact_step_end = workflow.find("\n      - name:", artifact_index)
                if artifact_step_end == -1:
                    artifact_step_end = len(workflow)
                artifact_step = workflow[artifact_step_start:artifact_step_end]
                for contract in (
                    "if: failure() && steps.ios_gate.outcome == 'failure'",
                    "uses: actions/upload-artifact@"
                    "ea165f8d65b6e75b540449e92b4886f43607fa02",
                    artifact_marker,
                    "path: build/ios-gate-diagnostics",
                    "if-no-files-found: warn",
                    "retention-days: 7",
                ):
                    with self.subTest(step_contract=contract):
                        self.assertIn(contract, artifact_step)

    def test_process_close_regression_has_deterministic_phases(self) -> None:
        source = BROWSER_RUNTIME_CONTROL_TESTS.read_text(encoding="utf-8")
        start = source.index(
            "func testClosingProcessDropsQueuedSyncMaintenanceSafePoint()"
        )
        end = source.index("\n    }\n}", start)
        regression = source[start:end]

        self.assertIn("let hostedTimeout = 10.0", regression)
        self.assertEqual(regression.count("timeout: hostedTimeout"), 4)
        self.assertNotIn("Task.yield", regression)
        for phase in ("phase 1:", "phase 2:", "phase 3:", "phase 4:"):
            with self.subTest(phase=phase):
                self.assertIn(phase, regression)
        for contract in (
            "var processClosed = false",
            "var syncGateReleased = false",
            "var syncResult: Result<BrowserSyncSummary, Error>?",
            ".runtimeUnavailable(\"process is closed\")",
            "XCTAssertFalse(\n            runtime.isClosed",
            "XCTAssertTrue(\n            runtime.isClosed",
        ):
            with self.subTest(contract=contract):
                self.assertIn(contract, regression)

        defer_start = regression.index("defer {")
        defer_end = regression.index("\n        }\n\n        let preparationCompleted")
        cleanup = regression[defer_start:defer_end]
        self.assertLess(cleanup.index("process.close()"), cleanup.index("signal()"))

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
            'let walletRowIdentifier = "settings.destination.wallet"',
            ui_test,
        )
        self.assertIn(
            'walletTitle.waitForExistence(timeout: timeout)',
            ui_test,
        )
        self.assertIn('XCTAssertEqual(walletRowLabel, "Wallet")', ui_test)
        self.assertIn(
            '"nativeWalletRowIdentifier": walletRowIdentifier',
            ui_test,
        )

        tools = SCREENSHOT_TOOLS.read_text(encoding="utf-8")
        self.assertIn(
            'NATIVE_WALLET_ROW_IDENTIFIER = "settings.destination.wallet"',
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
