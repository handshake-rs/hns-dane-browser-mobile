#!/usr/bin/env python3
"""Provision the separate legacy-recovery App ID and archival iOS profile.

This never creates an App Store app record, uploads a build, or registers a device.
"""

from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import os
from pathlib import Path
import plistlib
import sys

from app_store_connect_release import AppStoreConnectApi, JwtProvider, ReleaseError


BUNDLE_ID = "com.denuoweb.hnsdane.ios.legacyrecovery"
PROFILE_NAME = "Shakescape Legacy Recovery App Store"


def one(resources: list[dict], label: str) -> dict:
    if len(resources) != 1:
        raise ReleaseError(f"expected one {label}, found {len(resources)}")
    return resources[0]


def verify_profile_content(content: str, certificate: bytes, team_id: str) -> bytes:
    """Basic API payload checks; the signing script independently verifies CMS."""
    try:
        raw = base64.b64decode(content, validate=True)
    except (ValueError, base64.binascii.Error) as error:
        raise ReleaseError("Apple returned invalid profile content") from error
    if not raw or len(raw) > 1024 * 1024:
        raise ReleaseError("Apple returned an empty or oversized profile")
    # CMS is decoded on macOS by the export script. Never trust the JSON
    # metadata alone as authority for the bundle, team, or certificate.
    if not certificate or len(team_id) != 10:
        raise ReleaseError("invalid signing inputs")
    return raw


def provision(api: AppStoreConnectApi, certificate: bytes, team_id: str) -> bytes:
    bundle_ids = api.list("/v1/bundleIds", params={"filter[identifier]": BUNDLE_ID})
    if bundle_ids:
        bundle = one(bundle_ids, "recovery bundle ID")
    else:
        response = api.request(
            "POST", "/v1/bundleIds", expected=(201,),
            body={"data": {"type": "bundleIds", "attributes": {
                "name": "Shakescape Legacy Recovery",
                "identifier": BUNDLE_ID,
                "platform": "IOS",
            }}},
        )
        bundle = response["data"]
    if bundle.get("type") != "bundleIds" or bundle.get("attributes", {}).get("identifier") != BUNDLE_ID:
        raise ReleaseError("Apple returned an unexpected bundle ID")
    if bundle.get("attributes", {}).get("platform") not in ("IOS", "UNIVERSAL"):
        raise ReleaseError("recovery bundle ID is not registered for iOS")
    bundle_resource_id = bundle.get("id")
    if not isinstance(bundle_resource_id, str) or not bundle_resource_id:
        raise ReleaseError("Apple returned a bundle ID without a resource ID")

    certificates = api.list("/v1/certificates", params={"filter[certificateType]": "DISTRIBUTION"})
    matching = []
    for item in certificates:
        attributes = item.get("attributes", {})
        encoded = attributes.get("certificateContent")
        if not isinstance(encoded, str):
            continue
        try:
            candidate = base64.b64decode(encoded, validate=True)
        except (ValueError, base64.binascii.Error):
            continue
        if candidate == certificate and attributes.get("certificateType") == "DISTRIBUTION":
            matching.append(item)
    cert = one(matching, "matching distribution certificate")
    certificate_resource_id = cert.get("id")
    if not isinstance(certificate_resource_id, str) or not certificate_resource_id:
        raise ReleaseError("Apple returned a certificate without a resource ID")

    profiles = api.list("/v1/profiles", params={"filter[name]": PROFILE_NAME})
    active_profiles = [
        profile for profile in profiles
        if profile.get("attributes", {}).get("profileType") == "IOS_APP_STORE"
        and profile.get("attributes", {}).get("profileState") == "ACTIVE"
    ]
    if active_profiles:
        profile = one(active_profiles, "active recovery profile")
        profile_id = profile.get("id")
        if not isinstance(profile_id, str) or not profile_id:
            raise ReleaseError("Apple returned a profile without a resource ID")
        related_bundle = api.request("GET", f"/v1/profiles/{profile_id}/bundleId")["data"]
        related_certs = api.list(f"/v1/profiles/{profile_id}/certificates")
        if related_bundle.get("id") != bundle_resource_id or [c.get("id") for c in related_certs] != [certificate_resource_id]:
            raise ReleaseError("existing recovery profile does not match this bundle and certificate")
    else:
        if profiles:
            raise ReleaseError("recovery profile name exists but has no active App Store profile")
        response = api.request(
            "POST", "/v1/profiles", expected=(201,),
            body={"data": {
                "type": "profiles",
                "attributes": {"name": PROFILE_NAME, "profileType": "IOS_APP_STORE"},
                "relationships": {
                    "bundleId": {"data": {"type": "bundleIds", "id": bundle_resource_id}},
                    "certificates": {"data": [{"type": "certificates", "id": certificate_resource_id}]},
                },
            }},
        )
        profile = response["data"]
    attributes = profile.get("attributes", {})
    if attributes.get("profileType") != "IOS_APP_STORE" or attributes.get("profileState") != "ACTIVE":
        raise ReleaseError("recovery profile is not an active iOS App Store profile")
    content = attributes.get("profileContent")
    if not isinstance(content, str):
        profile_id = profile.get("id")
        profile = api.request("GET", f"/v1/profiles/{profile_id}")["data"]
        content = profile.get("attributes", {}).get("profileContent")
    if not isinstance(content, str):
        raise ReleaseError("Apple did not provide recovery profile content")
    return verify_profile_content(content, certificate, team_id)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--certificate-der", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--team-id", required=True)
    args = parser.parse_args()
    key_id = os.environ.get("HNS_ASC_API_KEY_ID", "")
    issuer_id = os.environ.get("HNS_ASC_API_KEY_ISSUER_ID", "")
    key_path = Path(os.environ.get("HNS_ASC_API_KEY_PATH", ""))
    if not key_id or not issuer_id or not key_path.is_file():
        raise ReleaseError("App Store Connect API credentials are required")
    if not args.output.parent.is_dir() or args.output.exists():
        raise ReleaseError("output directory must exist and recovery profile must not exist")
    certificate = args.certificate_der.read_bytes()
    api = AppStoreConnectApi(JwtProvider(key_id, issuer_id, key_path))
    raw = provision(api, certificate, args.team_id)
    args.output.write_bytes(raw)
    args.output.chmod(0o600)
    print("Registered or reused the isolated iOS recovery App ID and App Store profile.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ReleaseError, KeyError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
