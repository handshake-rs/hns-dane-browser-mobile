"""The archival recovery profile never registers devices or an App Store app."""

from __future__ import annotations

import base64
import importlib.util
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
SPEC = importlib.util.spec_from_file_location(
    "provision_ios_legacy_recovery_appstore",
    ROOT / "scripts" / "provision-ios-legacy-recovery-appstore.py",
)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeApi:
    def __init__(self, existing: bool = False):
        self.existing = existing
        self.writes = []

    def list(self, path, *, params=None):
        if path == "/v1/bundleIds":
            return [{"type": "bundleIds", "id": "bundle", "attributes": {
                "identifier": MODULE.BUNDLE_ID, "platform": "IOS",
            }}] if self.existing else []
        if path == "/v1/certificates":
            return [{"type": "certificates", "id": "cert", "attributes": {
                "certificateType": "DISTRIBUTION",
                "certificateContent": base64.b64encode(b"the-certificate").decode(),
            }}]
        if path == "/v1/profiles":
            return [{"type": "profiles", "id": "profile", "attributes": {
                "profileType": "IOS_APP_STORE", "profileState": "ACTIVE",
                "profileContent": base64.b64encode(b"cms-profile").decode(),
            }}] if self.existing else []
        if path == "/v1/profiles/profile/certificates":
            return [{"type": "certificates", "id": "cert"}]
        raise AssertionError(path)

    def request(self, method, path, *, body=None, expected=(200,)):
        if method == "GET" and path == "/v1/profiles/profile/bundleId":
            return {"data": {"type": "bundleIds", "id": "bundle"}}
        self.writes.append((method, path, body))
        if path == "/v1/bundleIds":
            return {"data": {"type": "bundleIds", "id": "bundle", "attributes": {
                "identifier": MODULE.BUNDLE_ID, "platform": "IOS",
            }}}
        if path == "/v1/profiles":
            return {"data": {"type": "profiles", "id": "profile", "attributes": {
                "profileType": "IOS_APP_STORE", "profileState": "ACTIVE",
                "profileContent": base64.b64encode(b"cms-profile").decode(),
            }}}
        raise AssertionError(path)


class ProvisioningTests(unittest.TestCase):
    def test_new_profile_is_app_store_and_has_no_devices(self):
        api = FakeApi()
        raw = MODULE.provision(api, b"the-certificate", "45NQQK3G3S")
        self.assertEqual(raw, b"cms-profile")
        self.assertEqual([item[1] for item in api.writes], ["/v1/bundleIds", "/v1/profiles"])
        profile = api.writes[-1][2]["data"]
        self.assertEqual(profile["attributes"]["profileType"], "IOS_APP_STORE")
        self.assertNotIn("devices", profile["relationships"])

    def test_existing_exact_profile_is_reused(self):
        api = FakeApi(existing=True)
        self.assertEqual(MODULE.provision(api, b"the-certificate", "45NQQK3G3S"), b"cms-profile")
        self.assertEqual(api.writes, [])

    def test_mismatched_certificate_is_rejected(self):
        api = FakeApi()
        with self.assertRaisesRegex(Exception, "matching distribution certificate"):
            MODULE.provision(api, b"other", "45NQQK3G3S")
        self.assertEqual([item[1] for item in api.writes], ["/v1/bundleIds"])


if __name__ == "__main__":
    unittest.main()
