#!/usr/bin/env python3
from __future__ import annotations

import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "app_store_connect_release.py"
WORKFLOW = ROOT / ".github" / "workflows" / "ios-app-store-submit.yml"
SPEC = importlib.util.spec_from_file_location("app_store_connect_release", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
release_client = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = release_client
SPEC.loader.exec_module(release_client)


def resource(resource_type: str, resource_id: str, **attributes):
    return {
        "type": resource_type,
        "id": resource_id,
        "attributes": attributes,
    }


class FakeApi:
    def __init__(self, active_state: str | None = None):
        self.active_state = active_state
        self.item_created = active_state is not None
        self.requests: list[tuple[str, str, object, object]] = []

    def list(self, path, *, params=None):
        self.requests.append(("LIST", path, params, None))
        if path == "/v1/apps":
            return [
                resource(
                    "apps",
                    "app",
                    bundleId="com.denuoweb.hnsdane.ios",
                    primaryLocale="en-US",
                )
            ]
        if path == "/v1/apps/app/appStoreVersions":
            return [
                resource(
                    "appStoreVersions",
                    "version",
                    versionString="1.0.3",
                    appStoreState="PREPARE_FOR_SUBMISSION",
                )
            ]
        if path == "/v1/apps/app/reviewSubmissions":
            if self.active_state is None:
                return []
            return [resource("reviewSubmissions", "submission", state=self.active_state)]
        if path == "/v1/reviewSubmissions/submission/items":
            if not self.item_created:
                return []
            item = resource("reviewSubmissionItems", "item", state="READY_FOR_REVIEW")
            item["relationships"] = {
                "appStoreVersion": {
                    "data": {"type": "appStoreVersions", "id": "version"}
                }
            }
            return [item]
        raise AssertionError(f"unexpected list request: {path}")

    def request(self, method, path, *, params=None, body=None, expected=(200,)):
        self.requests.append((method, path, params, body))
        if method == "POST" and path == "/v1/reviewSubmissions":
            self.active_state = "READY_FOR_REVIEW"
            return {"data": resource("reviewSubmissions", "submission", state="READY_FOR_REVIEW")}
        if method == "POST" and path == "/v1/reviewSubmissionItems":
            self.item_created = True
            return {"data": resource("reviewSubmissionItems", "item", state="READY_FOR_REVIEW")}
        if method == "PATCH" and path == "/v1/reviewSubmissions/submission":
            self.active_state = "WAITING_FOR_REVIEW"
            return {"data": resource("reviewSubmissions", "submission", state="WAITING_FOR_REVIEW")}
        raise AssertionError(f"unexpected API request: {method} {path}")


class ScreenshotApi:
    def __init__(self, screenshots):
        self.screenshot_set_resource = resource(
            "appScreenshotSets",
            "iphone-65-set",
            screenshotDisplayType="APP_IPHONE_65",
        )
        self.screenshots = list(screenshots)
        self.other_display_type_resources = [
            resource(
                "appScreenshots",
                "unrelated-67",
                fileName="unrelated.jpg",
                fileSize=1,
                sourceFileChecksum="f" * 32,
                assetDeliveryState={"state": "COMPLETE"},
            )
        ]
        self.events = []

    def list(self, path, *, params=None):
        if path.endswith("/appScreenshotSets"):
            self.events.append(("LIST_SET", params))
            return [self.screenshot_set_resource]
        if path == "/v1/appScreenshotSets/iphone-65-set/appScreenshots":
            self.events.append(
                ("LIST_SCREENSHOTS", tuple(item["id"] for item in self.screenshots))
            )
            return list(self.screenshots)
        raise AssertionError(f"unexpected list request: {path}")

    def request(self, method, path, *, params=None, body=None, expected=(200,)):
        if method == "DELETE" and path.startswith("/v1/appScreenshots/"):
            screenshot_id = path.removeprefix("/v1/appScreenshots/")
            if screenshot_id not in {item["id"] for item in self.screenshots}:
                raise AssertionError(f"unexpected screenshot deletion: {screenshot_id}")
            self.events.append(("DELETE", screenshot_id))
            self.screenshots = [
                item for item in self.screenshots if item["id"] != screenshot_id
            ]
            return None
        raise AssertionError(f"unexpected API request: {method} {path}")


def complete_screenshot(resource_id, filename, contents=b"old screenshot"):
    return resource(
        "appScreenshots",
        resource_id,
        fileName=filename,
        fileSize=len(contents),
        sourceFileChecksum=hashlib.md5(contents).hexdigest(),  # noqa: S324
        assetDeliveryState={"state": "COMPLETE"},
    )


class LocalReleaseSafetyTests(unittest.TestCase):
    def setUp(self):
        self.release = release_client.LocalRelease(
            ROOT,
            "a" * 40,
            "a" * 40,
            "1.0.3",
            "63",
            {},
        )

    def test_plan_is_local_only_and_hashes_metadata_without_printing_values(self):
        release = release_client.load_local_release(
            ROOT,
            "a" * 40,
            "a" * 40,
            "1.0.3",
            "63",
        )
        plan = release_client.local_plan(release)
        self.assertEqual(plan["mode"], "plan")
        self.assertEqual(plan["networkRequests"], 0)
        self.assertEqual(plan["mutations"], 0)
        self.assertEqual(plan["version"], "1.0.3")
        self.assertEqual(plan["build"], "63")
        serialized = json.dumps(plan)
        self.assertNotIn(release.metadata["reviewNotes"], serialized)

    def test_cli_defaults_artifact_commit_to_expected_commit(self):
        arguments = [
            str(SCRIPT),
            "--mode",
            "plan",
            "--expected-commit",
            "a" * 40,
            "--expected-version",
            "1.0.3",
            "--expected-build",
            "63",
        ]
        with (
            mock.patch.object(sys, "argv", arguments),
            mock.patch.object(
                release_client,
                "load_local_release",
                return_value=self.release,
            ) as load,
            mock.patch("builtins.print"),
        ):
            self.assertEqual(release_client.main(), 0)
        load.assert_called_once_with(
            ROOT,
            "a" * 40,
            "a" * 40,
            "1.0.3",
            "63",
        )

    def test_mutations_require_release_specific_confirmation_strings(self):
        with self.assertRaisesRegex(release_client.ReleaseError, "confirm-metadata"):
            release_client.validate_confirmations(
                self.release,
                "apply-metadata",
                None,
                None,
                False,
            )
        with self.assertRaisesRegex(release_client.ReleaseError, "confirm-submit"):
            release_client.validate_confirmations(
                self.release,
                "submit",
                self.release.metadata_confirmation,
                None,
                True,
            )
        with self.assertRaisesRegex(release_client.ReleaseError, "readiness attestation"):
            release_client.validate_confirmations(
                self.release,
                "submit",
                self.release.metadata_confirmation,
                self.release.submit_confirmation,
                False,
            )
        release_client.validate_confirmations(
            self.release,
            "submit",
            self.release.metadata_confirmation,
            self.release.submit_confirmation,
            True,
        )

    def test_screenshot_replacement_confirmation_is_exact_and_mutation_only(self):
        with self.assertRaisesRegex(
            release_client.ReleaseError,
            "confirm-screenshot-replacement",
        ):
            release_client.validate_confirmations(
                self.release,
                "apply-metadata",
                self.release.metadata_confirmation,
                None,
                False,
                "REPLACE_SCREENSHOTS_1.0.3_61",
            )
        with self.assertRaisesRegex(release_client.ReleaseError, "mutating mode"):
            release_client.validate_confirmations(
                self.release,
                "discover",
                None,
                None,
                False,
                self.release.screenshot_replacement_confirmation,
            )
        release_client.validate_confirmations(
            self.release,
            "apply-metadata",
            self.release.metadata_confirmation,
            None,
            False,
            self.release.screenshot_replacement_confirmation,
        )

    def test_release_automation_diff_allows_only_the_pinned_boundary(self):
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)

            def git(*arguments):
                return subprocess.run(
                    ["git", *arguments],
                    cwd=repository,
                    check=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                ).stdout.strip()

            git("init", "-q")
            git("config", "user.name", "Release Test")
            git("config", "user.email", "release-test@example.invalid")
            (repository / "README.md").write_text("release source\n", encoding="utf-8")
            git("add", "README.md")
            git("commit", "-qm", "artifact source")
            artifact_commit = git("rev-parse", "HEAD")

            allowed = repository / "scripts/app_store_connect_release.py"
            allowed.parent.mkdir(parents=True)
            allowed.write_text("automation\n", encoding="utf-8")
            git("add", str(allowed.relative_to(repository)))
            git("commit", "-qm", "automation only")
            automation_commit = git("rev-parse", "HEAD")
            release = release_client.LocalRelease(
                repository,
                automation_commit,
                artifact_commit,
                "1.0.3",
                "63",
                {},
            )
            release_client.verify_release_automation_diff(release)

            (repository / "README.md").write_text("changed source\n", encoding="utf-8")
            git("add", "README.md")
            git("commit", "-qm", "unexpected source change")
            changed_commit = git("rev-parse", "HEAD")
            changed_release = release_client.LocalRelease(
                repository,
                changed_commit,
                artifact_commit,
                "1.0.3",
                "63",
                {},
            )
            with self.assertRaisesRegex(
                release_client.ReleaseError,
                "non-automation files: README.md",
            ):
                release_client.verify_release_automation_diff(changed_release)

    def test_jwt_has_es256_raw_signature_and_short_lifetime(self):
        with tempfile.TemporaryDirectory() as temporary:
            key = Path(temporary) / "AuthKey_ABCDEFGHIJ.p8"
            completed = subprocess.run(
                [
                    "openssl",
                    "ecparam",
                    "-name",
                    "prime256v1",
                    "-genkey",
                    "-noout",
                    "-out",
                    str(key),
                ],
                check=False,
                capture_output=True,
            )
            if completed.returncode != 0:
                self.skipTest("openssl EC support is unavailable")
            key.chmod(0o600)
            token = release_client.create_jwt(
                "ABCDEFGHIJ",
                "12345678-1234-1234-1234-123456789abc",
                key,
                now=1_700_000_000,
            )
        encoded_header, encoded_claims, encoded_signature = token.split(".")

        def decode(value):
            return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))

        self.assertEqual(json.loads(decode(encoded_header)), {
            "alg": "ES256",
            "kid": "ABCDEFGHIJ",
            "typ": "JWT",
        })
        claims = json.loads(decode(encoded_claims))
        self.assertEqual(claims["aud"], "appstoreconnect-v1")
        self.assertEqual(claims["iat"], 1_700_000_000)
        self.assertEqual(claims["exp"], 1_700_000_600)
        self.assertEqual(len(decode(encoded_signature)), 64)

    def test_private_key_permissions_are_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            key = Path(temporary) / "key.p8"
            key.write_text("not used", encoding="utf-8")
            key.chmod(0o644)
            with self.assertRaisesRegex(release_client.ReleaseError, "group/world"):
                release_client.create_jwt(
                    "ABCDEFGHIJ",
                    "12345678-1234-1234-1234-123456789abc",
                    key,
                )

    def test_api_errors_do_not_echo_detail_or_sensitive_values(self):
        raw = json.dumps(
            {
                "errors": [
                    {
                        "code": "ENTITY_ERROR",
                        "title": "Invalid value",
                        "detail": "secret review password appeared here",
                    }
                ]
            }
        ).encode()
        summary = release_client._safe_api_error_summary(raw)
        self.assertEqual(summary, "ENTITY_ERROR/Invalid value")
        self.assertNotIn("password", summary)


class SubmissionSafetyTests(unittest.TestCase):
    def make_manager(self, api, *, allow_screenshot_replacement=False):
        release = release_client.LocalRelease(
            ROOT,
            "b" * 40,
            "b" * 40,
            "1.0.3",
            "63",
            {},
        )
        return release_client.ReleaseManager(
            api,
            release,
            review_contact_source_version="0.5.5",
            allow_screenshot_replacement=allow_screenshot_replacement,
        )

    def test_new_submission_creates_exact_version_item_and_submits_last(self):
        api = FakeApi()
        manager = self.make_manager(api)
        with mock.patch.object(release_client, "verify_exact_current_main") as verify:
            result = manager.submit()
        self.assertEqual(result, {
            "reviewState": "WAITING_FOR_REVIEW",
            "alreadySubmitted": False,
        })
        verify.assert_called_once_with(manager.release)
        mutation_paths = [
            (method, path)
            for method, path, _params, _body in api.requests
            if method in {"POST", "PATCH"}
        ]
        self.assertEqual(
            mutation_paths,
            [
                ("POST", "/v1/reviewSubmissions"),
                ("POST", "/v1/reviewSubmissionItems"),
                ("PATCH", "/v1/reviewSubmissions/submission"),
            ],
        )
        submit_body = api.requests[-1][3]
        self.assertIs(submit_body["data"]["attributes"]["submitted"], True)

    def test_already_submitted_exact_version_is_read_only_and_idempotent(self):
        api = FakeApi(active_state="IN_REVIEW")
        manager = self.make_manager(api)
        with mock.patch.object(manager, "apply_metadata") as apply_metadata:
            result = release_client.execute_authenticated_mode(manager, "submit")
        self.assertEqual(
            result,
            {
                "metadata": {"status": "unchanged-already-submitted"},
                "submission": {
                    "reviewState": "IN_REVIEW",
                    "alreadySubmitted": True,
                },
            },
        )
        apply_metadata.assert_not_called()
        self.assertFalse(
            any(method in {"POST", "PATCH"} for method, *_rest in api.requests)
        )

    def test_existing_screenshot_mismatch_without_confirmation_deletes_nothing(self):
        with tempfile.TemporaryDirectory() as temporary:
            screenshot = Path(temporary) / "01.jpg"
            screenshot.write_bytes(b"exact screenshot")
            api = ScreenshotApi(
                [complete_screenshot("old-one", "different.jpg")]
            )
            manager = self.make_manager(api)
            manager.screenshot_paths = [screenshot]
            with self.assertRaisesRegex(release_client.ReleaseError, "destructive replacement"):
                manager._ensure_screenshots("localization")
            self.assertFalse(any(event[0] == "DELETE" for event in api.events))

    def test_screenshot_readback_reports_exact_api_order_and_attributes(self):
        first = complete_screenshot("screenshot-b", "02-settings.jpg", b"settings")
        second = complete_screenshot("screenshot-a", "01-home.jpg", b"home")
        first["attributes"]["sourceFileChecksum"] = (
            first["attributes"]["sourceFileChecksum"].upper()
        )

        self.assertEqual(
            release_client.ReleaseManager.screenshot_readback([first, second]),
            [
                {
                    "position": 1,
                    "id": "screenshot-b",
                    "fileName": "02-settings.jpg",
                    "fileSize": len(b"settings"),
                    "sourceFileChecksum": hashlib.md5(b"settings").hexdigest().upper(),  # noqa: S324
                    "state": "COMPLETE",
                },
                {
                    "position": 2,
                    "id": "screenshot-a",
                    "fileName": "01-home.jpg",
                    "fileSize": len(b"home"),
                    "sourceFileChecksum": hashlib.md5(b"home").hexdigest(),  # noqa: S324
                    "state": "COMPLETE",
                },
            ],
        )

    def test_confirmed_screenshot_replacement_is_scoped_stable_and_read_back_empty(self):
        with tempfile.TemporaryDirectory() as temporary:
            screenshot = Path(temporary) / "01-home.jpg"
            screenshot.write_bytes(b"exact screenshot")
            api = ScreenshotApi(
                [
                    complete_screenshot("old-b", "02-old.jpg", b"second old"),
                    complete_screenshot("old-a", "01-old.jpg", b"first old"),
                ]
            )
            manager = self.make_manager(
                api,
                allow_screenshot_replacement=True,
            )
            manager.screenshot_paths = [screenshot]

            def upload_exact(_set_id, path):
                self.assertEqual(api.screenshots, [])
                api.events.append(("UPLOAD", path.name))
                api.screenshots.append(
                    complete_screenshot("new-exact", path.name, path.read_bytes())
                )

            with mock.patch.object(
                manager,
                "_upload_screenshot",
                side_effect=upload_exact,
            ):
                screenshot_set_id = manager._ensure_screenshots("localization")

            self.assertEqual(screenshot_set_id, "iphone-65-set")
            self.assertEqual(
                [event for event in api.events if event[0] == "DELETE"],
                [("DELETE", "old-a"), ("DELETE", "old-b")],
            )
            upload_index = api.events.index(("UPLOAD", "01-home.jpg"))
            self.assertEqual(api.events[upload_index - 1], ("LIST_SCREENSHOTS", ()))
            self.assertGreaterEqual(
                api.events[:upload_index].count(
                    ("LIST_SCREENSHOTS", ("old-b", "old-a"))
                ),
                2,
            )
            self.assertEqual(
                [item["id"] for item in api.other_display_type_resources],
                ["unrelated-67"],
            )
            self.assertFalse(
                any(
                    event[0] == "DELETE" and event[1] == "unrelated-67"
                    for event in api.events
                )
            )

    def test_confirmed_replacement_refuses_incomplete_or_unsafe_resources(self):
        with tempfile.TemporaryDirectory() as temporary:
            screenshot = Path(temporary) / "01-home.jpg"
            screenshot.write_bytes(b"exact screenshot")
            incomplete = complete_screenshot("old-one", "old.jpg")
            incomplete["attributes"]["assetDeliveryState"] = {"state": "UPLOAD_COMPLETE"}
            api = ScreenshotApi([incomplete])
            manager = self.make_manager(
                api,
                allow_screenshot_replacement=True,
            )
            manager.screenshot_paths = [screenshot]
            with self.assertRaisesRegex(release_client.ReleaseError, "complete and sane"):
                manager._ensure_screenshots("localization")
            self.assertFalse(any(event[0] == "DELETE" for event in api.events))

            unsafe_api = ScreenshotApi(
                [complete_screenshot("../other-endpoint", "old.jpg")]
            )
            unsafe_manager = self.make_manager(
                unsafe_api,
                allow_screenshot_replacement=True,
            )
            unsafe_manager.screenshot_paths = [screenshot]
            with self.assertRaisesRegex(release_client.ReleaseError, "invalid resource IDs"):
                unsafe_manager._ensure_screenshots("localization")
            self.assertFalse(
                any(event[0] == "DELETE" for event in unsafe_api.events)
            )

    def test_confirmed_replacement_refuses_a_changed_snapshot_before_deleting(self):
        with tempfile.TemporaryDirectory() as temporary:
            screenshot = Path(temporary) / "01-home.jpg"
            screenshot.write_bytes(b"exact screenshot")
            initial = [complete_screenshot("old-one", "old.jpg", b"old")]
            changed = [complete_screenshot("old-one", "changed.jpg", b"changed")]
            api = ScreenshotApi(initial)
            manager = self.make_manager(
                api,
                allow_screenshot_replacement=True,
            )
            manager.screenshot_paths = [screenshot]
            with (
                mock.patch.object(
                    manager,
                    "screenshots",
                    side_effect=[initial, changed],
                ),
                self.assertRaisesRegex(release_client.ReleaseError, "changed"),
            ):
                manager._ensure_screenshots("localization")
            self.assertFalse(any(event[0] == "DELETE" for event in api.events))

    def test_exact_and_prefix_screenshot_sets_never_delete(self):
        with tempfile.TemporaryDirectory() as temporary:
            first = Path(temporary) / "01-home.jpg"
            second = Path(temporary) / "02-settings.jpg"
            first.write_bytes(b"first exact")
            second.write_bytes(b"second exact")

            for initial_count, expected_uploads in ((2, []), (1, [second.name])):
                with self.subTest(initial_count=initial_count):
                    exact_resources = [
                        complete_screenshot("exact-one", first.name, first.read_bytes()),
                        complete_screenshot("exact-two", second.name, second.read_bytes()),
                    ]
                    api = ScreenshotApi(exact_resources[:initial_count])
                    manager = self.make_manager(
                        api,
                        allow_screenshot_replacement=True,
                    )
                    manager.screenshot_paths = [first, second]
                    uploads = []

                    def upload_prefix(_set_id, path):
                        uploads.append(path.name)
                        api.screenshots.append(
                            complete_screenshot("exact-two", path.name, path.read_bytes())
                        )

                    with mock.patch.object(
                        manager,
                        "_upload_screenshot",
                        side_effect=upload_prefix,
                    ):
                        manager._ensure_screenshots("localization")
                    self.assertEqual(uploads, expected_uploads)
                    self.assertFalse(any(event[0] == "DELETE" for event in api.events))

    def test_exact_screenshot_checksum_readback_is_case_insensitive(self):
        with tempfile.TemporaryDirectory() as temporary:
            screenshot = Path(temporary) / "01-home.jpg"
            screenshot.write_bytes(b"exact screenshot")
            resource = complete_screenshot(
                "exact-one",
                screenshot.name,
                screenshot.read_bytes(),
            )
            resource["attributes"]["sourceFileChecksum"] = resource["attributes"][
                "sourceFileChecksum"
            ].upper()
            api = ScreenshotApi([resource])
            manager = self.make_manager(api, allow_screenshot_replacement=True)
            manager.screenshot_paths = [screenshot]

            manager._ensure_screenshots("localization")

            self.assertFalse(any(event[0] == "DELETE" for event in api.events))

    def test_complete_screenshot_readback_waits_for_exact_fields_to_converge(self):
        with tempfile.TemporaryDirectory() as temporary:
            screenshot = Path(temporary) / "01-home.jpg"
            screenshot.write_bytes(b"exact screenshot")
            stale = complete_screenshot(
                "exact-one",
                "stale-name.jpg",
                screenshot.read_bytes(),
            )
            exact = complete_screenshot(
                "exact-one",
                screenshot.name,
                screenshot.read_bytes(),
            )
            manager = self.make_manager(FakeApi())
            manager.screenshot_paths = [screenshot]

            with (
                mock.patch.object(manager, "screenshots", side_effect=[[stale], [exact]]),
                mock.patch.object(release_client.time, "sleep") as sleep,
            ):
                manager._wait_for_screenshots("iphone-65-set")

            sleep.assert_called_once_with(5)

    def test_asset_upload_operations_must_cover_file_and_cannot_set_auth(self):
        with tempfile.TemporaryDirectory() as temporary:
            screenshot = Path(temporary) / "screen.jpg"
            screenshot.write_bytes(b"abcdef")
            manager = self.make_manager(FakeApi())
            with self.assertRaisesRegex(release_client.ReleaseError, "sensitive header"):
                manager._validate_and_upload_operations(
                    screenshot,
                    [
                        {
                            "method": "PUT",
                            "url": "https://store.example.apple.com/upload",
                            "offset": 0,
                            "length": 6,
                            "requestHeaders": [
                                {"name": "Authorization", "value": "do-not-forward"}
                            ],
                        }
                    ],
                )
            with self.assertRaisesRegex(release_client.ReleaseError, "exactly cover"):
                manager._validate_and_upload_operations(
                    screenshot,
                    [
                        {
                            "method": "PUT",
                            "url": "https://store.example.apple.com/upload",
                            "offset": 1,
                            "length": 5,
                            "requestHeaders": [],
                        }
                    ],
                )


class WorkflowSafetyTests(unittest.TestCase):
    def test_protected_workflow_requires_exact_main_upload_evidence_and_confirmations(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("github.ref == 'refs/heads/main'", workflow)
        self.assertIn("DISPATCH_COMMIT: ${{ github.sha }}", workflow)
        self.assertIn('[[ "$DISPATCH_COMMIT" == "$EXPECTED_COMMIT" ]]', workflow)
        self.assertIn("git ls-remote --exit-code origin refs/heads/main", workflow)
        self.assertIn("group: global-ios-app-store-upload-lease", workflow)
        self.assertIn("APPLY_METADATA_1.0.3_63", workflow)
        self.assertIn("REPLACE_SCREENSHOTS_1.0.3_63", workflow)
        self.assertIn("SUBMIT_FOR_REVIEW_1.0.3_63", workflow)
        self.assertIn('[[ "$ACCOUNT_READY" == true ]]', workflow)
        self.assertIn('.path == ".github/workflows/ios-app-store-upload.yml"', workflow)
        self.assertIn("expected_artifact_commit:", workflow)
        self.assertIn("fetch-depth: 0", workflow)
        self.assertIn("git merge-base --is-ancestor", workflow)
        for allowed_path in release_client.RELEASE_AUTOMATION_ALLOWLIST:
            self.assertIn(allowed_path, workflow)
        self.assertIn('.head_sha == $artifact_commit', workflow)
        self.assertIn('.conclusion == "success"', workflow)
        self.assertIn(
            '.name == "Validate, sign, and upload")][0].conclusion == "success"',
            workflow,
        )
        self.assertIn(
            'ipa_artifact="ios-app-store-ipa-${EXPECTED_ARTIFACT_COMMIT}"',
            workflow,
        )
        self.assertIn(".expired == false and .size_in_bytes > 0", workflow)
        self.assertIn("run-id: ${{ inputs.expected_upload_run_id }}", workflow)
        self.assertIn(
            "name: ios-app-store-live-screenshots-"
            "${{ inputs.expected_artifact_commit }}",
            workflow,
        )
        self.assertIn('--expected-commit "$EXPECTED_ARTIFACT_COMMIT"', workflow)
        self.assertIn('test "$(git rev-parse HEAD)" = "$EXPECTED_COMMIT"', workflow)
        self.assertIn("git status --porcelain --untracked-files=all", workflow)
        self.assertLess(
            workflow.index("Recheck current main before materializing the API key"),
            workflow.index("${{ secrets.APP_STORE_CONNECT_API_PRIVATE_KEY }}"),
        )

    def test_discovery_is_default_and_submission_is_not_automatic(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertRegex(workflow, r"mode:\n(?:\s+.*\n){1,5}\s+default: discover")
        self.assertNotIn("pull_request:", workflow)
        self.assertNotIn("push:", workflow)
        self.assertNotIn("schedule:", workflow)


if __name__ == "__main__":
    unittest.main()
