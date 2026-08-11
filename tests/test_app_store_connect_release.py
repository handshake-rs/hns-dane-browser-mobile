#!/usr/bin/env python3
from __future__ import annotations

import base64
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
                    versionString="0.5.10",
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


class LocalReleaseSafetyTests(unittest.TestCase):
    def setUp(self):
        self.release = release_client.LocalRelease(
            ROOT,
            "a" * 40,
            "0.5.10",
            "60",
            {},
        )

    def test_plan_is_local_only_and_hashes_metadata_without_printing_values(self):
        release = release_client.load_local_release(ROOT, "a" * 40, "0.5.10", "60")
        plan = release_client.local_plan(release)
        self.assertEqual(plan["mode"], "plan")
        self.assertEqual(plan["networkRequests"], 0)
        self.assertEqual(plan["mutations"], 0)
        self.assertEqual(plan["version"], "0.5.10")
        self.assertEqual(plan["build"], "60")
        serialized = json.dumps(plan)
        self.assertNotIn(release.metadata["reviewNotes"], serialized)

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
    def make_manager(self, api):
        release = release_client.LocalRelease(
            ROOT,
            "b" * 40,
            "0.5.10",
            "60",
            {},
        )
        return release_client.ReleaseManager(
            api,
            release,
            review_contact_source_version="0.5.5",
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

    def test_existing_screenshot_mismatch_refuses_destructive_replacement(self):
        with tempfile.TemporaryDirectory() as temporary:
            screenshot = Path(temporary) / "01.jpg"
            screenshot.write_bytes(b"exact screenshot")
            api_resource = resource(
                "appScreenshots",
                "screenshot",
                fileName="different.jpg",
                fileSize=screenshot.stat().st_size,
                sourceFileChecksum="wrong",
                assetDeliveryState={"state": "COMPLETE"},
            )
            manager = self.make_manager(FakeApi())
            manager.screenshot_paths = [screenshot]
            with self.assertRaisesRegex(release_client.ReleaseError, "destructive replacement"):
                manager._verify_screenshot_resources([api_resource])

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
        self.assertIn("APPLY_METADATA_0.5.10_60", workflow)
        self.assertIn("SUBMIT_FOR_REVIEW_0.5.10_60", workflow)
        self.assertIn('[[ "$ACCOUNT_READY" == true ]]', workflow)
        self.assertIn('.path == ".github/workflows/ios-app-store-upload.yml"', workflow)
        self.assertIn('.head_sha == $expected_commit', workflow)
        self.assertIn('.conclusion == "success"', workflow)
        self.assertIn(
            '.name == "Validate, sign, and upload")][0].conclusion == "success"',
            workflow,
        )
        self.assertIn('ipa_artifact="ios-app-store-ipa-${EXPECTED_COMMIT}"', workflow)
        self.assertIn(".expired == false and .size_in_bytes > 0", workflow)
        self.assertIn("run-id: ${{ inputs.expected_upload_run_id }}", workflow)
        self.assertIn(
            "name: ios-app-store-live-screenshots-${{ inputs.expected_commit }}",
            workflow,
        )
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
