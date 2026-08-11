#!/usr/bin/env python3
"""Guarded App Store Connect metadata, screenshot, and review submission client.

The default ``plan`` mode is local-only. ``discover`` makes authenticated GET
requests but cannot mutate App Store Connect. The two mutating modes require an
exact clean ``main`` commit plus release-specific confirmation strings.
"""

from __future__ import annotations

import argparse
import base64
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import stat
import subprocess
import sys
import time
from typing import Any, Callable, Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


API_ORIGIN = "https://api.appstoreconnect.apple.com"
BUNDLE_ID = "com.denuoweb.hnsdane.ios"
LOCALE = "en-US"
SCREENSHOT_DISPLAY_TYPE = "APP_IPHONE_65"
MAX_SCREENSHOTS_PER_SET = 10
EXACT_COMMIT = re.compile(r"^[0-9a-f]{40}$")
MD5_CHECKSUM = re.compile(r"^[0-9a-fA-F]{32}$")
SAFE_RESOURCE_ID = re.compile(r"^[A-Za-z0-9-]+$")
API_KEY_ID = re.compile(r"^[A-Z0-9]{10}$")
ISSUER_ID = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
    r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)
EDITABLE_VERSION_STATES = {"PREPARE_FOR_SUBMISSION"}
ACTIVE_REVIEW_STATES = {
    "READY_FOR_REVIEW",
    "WAITING_FOR_REVIEW",
    "IN_REVIEW",
    "UNRESOLVED_ISSUES",
    "CANCELING",
    "COMPLETING",
}
SUBMITTED_REVIEW_STATES = {
    "WAITING_FOR_REVIEW",
    "IN_REVIEW",
    "UNRESOLVED_ISSUES",
    "COMPLETING",
    "COMPLETE",
}
METADATA_FILES = {
    "name": "name.txt",
    "subtitle": "subtitle.txt",
    "promotionalText": "promotional-text.txt",
    "description": "description.txt",
    "keywords": "keywords.txt",
    "supportUrl": "support-url.txt",
    "marketingUrl": "marketing-url.txt",
    "copyright": "copyright.txt",
    "whatsNew": "whats-new.txt",
    "reviewNotes": "review-notes.txt",
    "privacyPolicyUrl": "privacy-policy-url.txt",
}
VERSION_LOCALIZATION_FIELDS = (
    "description",
    "keywords",
    "marketingUrl",
    "promotionalText",
    "supportUrl",
    "whatsNew",
)
APP_INFO_LOCALIZATION_FIELDS = (
    "name",
    "subtitle",
    "privacyPolicyUrl",
)
REQUIRED_REVIEW_CONTACT_FIELDS = (
    "contactFirstName",
    "contactLastName",
    "contactPhone",
    "contactEmail",
)
RELEASE_AUTOMATION_ALLOWLIST = frozenset(
    {
        ".github/workflows/ios-app-store-submit.yml",
        "docs/ios-app-store-release.md",
        "scripts/app_store_connect_release.py",
        "tests/test_app_store_connect_release.py",
    }
)


class ReleaseError(RuntimeError):
    """A safe, operator-actionable release failure."""


class ScreenshotSetMismatch(ReleaseError):
    """The existing screenshot resources are not the reviewed release prefix."""


class ApiError(ReleaseError):
    def __init__(self, status: int, method: str, path: str, summary: str):
        super().__init__(f"App Store Connect {method} {path} failed ({status}): {summary}")
        self.status = status
        self.method = method
        self.path = path


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        return None


@dataclass(frozen=True)
class LocalRelease:
    root: Path
    expected_commit: str
    artifact_commit: str
    version: str
    build: str
    metadata: dict[str, str]

    @property
    def metadata_confirmation(self) -> str:
        return f"APPLY_METADATA_{self.version}_{self.build}"

    @property
    def submit_confirmation(self) -> str:
        return f"SUBMIT_FOR_REVIEW_{self.version}_{self.build}"

    @property
    def screenshot_replacement_confirmation(self) -> str:
        return f"REPLACE_SCREENSHOTS_{self.version}_{self.build}"


def _json_bytes(value: Any) -> bytes:
    return json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")


def _b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _read_der_length(value: bytes, offset: int) -> tuple[int, int]:
    if offset >= len(value):
        raise ReleaseError("OpenSSL returned a truncated ECDSA signature")
    first = value[offset]
    offset += 1
    if first < 0x80:
        return first, offset
    count = first & 0x7F
    if count == 0 or count > 2 or offset + count > len(value):
        raise ReleaseError("OpenSSL returned an invalid ECDSA DER length")
    length = int.from_bytes(value[offset : offset + count], "big")
    return length, offset + count


def _der_es256_to_raw(value: bytes) -> bytes:
    if not value or value[0] != 0x30:
        raise ReleaseError("OpenSSL did not return an ECDSA sequence")
    sequence_length, offset = _read_der_length(value, 1)
    if offset + sequence_length != len(value):
        raise ReleaseError("OpenSSL returned an invalid ECDSA sequence length")
    integers: list[bytes] = []
    for _ in range(2):
        if offset >= len(value) or value[offset] != 0x02:
            raise ReleaseError("OpenSSL returned an invalid ECDSA integer")
        integer_length, offset = _read_der_length(value, offset + 1)
        integer = value[offset : offset + integer_length]
        offset += integer_length
        if len(integer) != integer_length or not integer:
            raise ReleaseError("OpenSSL returned a truncated ECDSA integer")
        integer = integer.lstrip(b"\0") or b"\0"
        if len(integer) > 32:
            raise ReleaseError("OpenSSL returned an oversized ES256 integer")
        integers.append(integer.rjust(32, b"\0"))
    if offset != len(value):
        raise ReleaseError("OpenSSL returned trailing ECDSA signature bytes")
    return b"".join(integers)


def create_jwt(key_id: str, issuer_id: str, key_path: Path, now: int | None = None) -> str:
    if not API_KEY_ID.fullmatch(key_id):
        raise ReleaseError("the App Store Connect key ID must be 10 uppercase letters/digits")
    if not ISSUER_ID.fullmatch(issuer_id):
        raise ReleaseError("the App Store Connect issuer ID must be a UUID")
    if not key_path.is_file():
        raise ReleaseError("the App Store Connect private key file is missing")
    mode = stat.S_IMODE(key_path.stat().st_mode)
    if mode & 0o077:
        raise ReleaseError("the App Store Connect private key must not be group/world accessible")
    timestamp = int(time.time()) if now is None else now
    header = {"alg": "ES256", "kid": key_id, "typ": "JWT"}
    claims = {
        "aud": "appstoreconnect-v1",
        "exp": timestamp + 600,
        "iat": timestamp,
        "iss": issuer_id,
    }
    signing_input = f"{_b64url(_json_bytes(header))}.{_b64url(_json_bytes(claims))}"
    try:
        completed = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", str(key_path)],
            input=signing_input.encode("ascii"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
    except OSError as error:
        raise ReleaseError("openssl is required to sign App Store Connect JWTs") from error
    if completed.returncode != 0:
        raise ReleaseError("openssl could not sign the App Store Connect JWT")
    signature = _der_es256_to_raw(completed.stdout)
    return f"{signing_input}.{_b64url(signature)}"


class JwtProvider:
    def __init__(self, key_id: str, issuer_id: str, key_path: Path):
        self.key_id = key_id
        self.issuer_id = issuer_id
        self.key_path = key_path
        self.token = ""
        self.expires_at = 0

    def __call__(self) -> str:
        now = int(time.time())
        if not self.token or self.expires_at - now < 90:
            self.token = create_jwt(self.key_id, self.issuer_id, self.key_path, now)
            self.expires_at = now + 600
        return self.token


class AppStoreConnectApi:
    def __init__(
        self,
        token_provider: Callable[[], str],
        *,
        origin: str = API_ORIGIN,
        opener=None,
    ):
        parsed = urlsplit(origin)
        if parsed.scheme != "https" or not parsed.netloc or parsed.path:
            raise ReleaseError("App Store Connect API origin must be one HTTPS origin")
        self.origin = origin.rstrip("/")
        self.token_provider = token_provider
        self.opener = opener or build_opener(NoRedirect())

    def _url(self, path: str, params: dict[str, Any] | None = None) -> str:
        if not path.startswith("/v1/") or ".." in path:
            raise ReleaseError(f"refusing unexpected App Store Connect API path: {path}")
        url = self.origin + path
        if params:
            url += "?" + urlencode(params, doseq=True)
        return url

    def _request_url(
        self,
        method: str,
        url: str,
        *,
        body: Any | None = None,
        expected: Iterable[int] = (200,),
    ) -> Any:
        parsed = urlsplit(url)
        expected_origin = urlsplit(self.origin)
        if (parsed.scheme, parsed.netloc) != (expected_origin.scheme, expected_origin.netloc):
            raise ReleaseError("refusing to send an API bearer token to another origin")
        data = _json_bytes(body) if body is not None else None
        headers = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self.token_provider()}",
            "User-Agent": "hns-dane-browser-release/1",
        }
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=60) as response:
                status = response.status
                raw = response.read(10 * 1024 * 1024 + 1)
        except HTTPError as error:
            raw = error.read(1024 * 1024)
            summary = _safe_api_error_summary(raw)
            raise ApiError(error.code, method, parsed.path, summary) from None
        except (URLError, TimeoutError) as error:
            raise ReleaseError(f"App Store Connect {method} {parsed.path} failed: network error") from error
        if status not in set(expected):
            raise ApiError(status, method, parsed.path, "unexpected response status")
        if len(raw) > 10 * 1024 * 1024:
            raise ReleaseError("App Store Connect returned an unexpectedly large response")
        if not raw:
            return None
        try:
            return json.loads(raw)
        except json.JSONDecodeError as error:
            raise ReleaseError("App Store Connect returned malformed JSON") from error

    def request(
        self,
        method: str,
        path: str,
        *,
        params: dict[str, Any] | None = None,
        body: Any | None = None,
        expected: Iterable[int] = (200,),
    ) -> Any:
        return self._request_url(
            method,
            self._url(path, params),
            body=body,
            expected=expected,
        )

    def list(self, path: str, *, params: dict[str, Any] | None = None) -> list[dict[str, Any]]:
        document = self.request("GET", path, params=params)
        values: list[dict[str, Any]] = []
        while True:
            data = document.get("data") if isinstance(document, dict) else None
            if not isinstance(data, list) or not all(isinstance(item, dict) for item in data):
                raise ReleaseError("App Store Connect returned a malformed collection")
            values.extend(data)
            links = document.get("links", {})
            next_url = links.get("next") if isinstance(links, dict) else None
            if next_url is None:
                return values
            if not isinstance(next_url, str):
                raise ReleaseError("App Store Connect returned a malformed pagination link")
            document = self._request_url("GET", next_url)


def _safe_api_error_summary(raw: bytes) -> str:
    try:
        document = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return "unparseable error response"
    errors = document.get("errors") if isinstance(document, dict) else None
    if not isinstance(errors, list) or not errors:
        return "error response contained no structured error"
    summaries = []
    for item in errors[:3]:
        if not isinstance(item, dict):
            continue
        code = item.get("code")
        title = item.get("title")
        safe_parts = [part for part in (code, title) if isinstance(part, str)]
        if safe_parts:
            summaries.append("/".join(safe_parts))
    return "; ".join(summaries) or "structured error"


def _read_field(path: Path) -> str:
    raw = path.read_text(encoding="utf-8")
    if not raw.endswith("\n") or raw.endswith("\n\n"):
        raise ReleaseError(f"metadata file must end with exactly one newline: {path.name}")
    return raw[:-1]


def _one_regex(value: str, pattern: str, label: str) -> str:
    matches = re.findall(pattern, value, re.MULTILINE)
    if len(matches) != 1:
        raise ReleaseError(f"expected exactly one {label} in ios/project.yml")
    return matches[0]


def load_local_release(
    root: Path,
    expected_commit: str,
    artifact_commit: str,
    expected_version: str,
    expected_build: str,
) -> LocalRelease:
    if not EXACT_COMMIT.fullmatch(expected_commit):
        raise ReleaseError("expected commit must be one lowercase 40-character Git SHA")
    if not EXACT_COMMIT.fullmatch(artifact_commit):
        raise ReleaseError("artifact commit must be one lowercase 40-character Git SHA")
    project = (root / "ios/project.yml").read_text(encoding="utf-8")
    version = _one_regex(project, r"^\s*MARKETING_VERSION:\s*([^\s#]+)\s*$", "version")
    build = _one_regex(project, r"^\s*CURRENT_PROJECT_VERSION:\s*([^\s#]+)\s*$", "build")
    if version != expected_version or build != expected_build:
        raise ReleaseError(
            f"local iOS identity is {version} ({build}), expected "
            f"{expected_version} ({expected_build})"
        )
    with (root / "ios/HnsDaneBrowser/Support/Info.plist").open("rb") as source:
        plist = plistlib.load(source)
    if str(plist.get("CFBundleShortVersionString")) != version:
        raise ReleaseError("Info.plist marketing version does not match ios/project.yml")
    if str(plist.get("CFBundleVersion")) != build:
        raise ReleaseError("Info.plist build number does not match ios/project.yml")
    if plist.get("CFBundleIdentifier") not in (None, BUNDLE_ID, "$(PRODUCT_BUNDLE_IDENTIFIER)"):
        raise ReleaseError("Info.plist contains an unexpected bundle identifier")
    metadata_root = root / "store-assets/app-store/metadata/en-US"
    metadata = {
        key: _read_field(metadata_root / filename)
        for key, filename in METADATA_FILES.items()
    }
    for key in ("name", "description", "keywords", "supportUrl", "copyright", "whatsNew", "reviewNotes", "privacyPolicyUrl"):
        if not metadata[key]:
            raise ReleaseError(f"required App Store metadata field is empty: {key}")
    return LocalRelease(root, expected_commit, artifact_commit, version, build, metadata)


def verify_release_automation_diff(release: LocalRelease) -> None:
    artifact = subprocess.run(
        ["git", "cat-file", "-e", f"{release.artifact_commit}^{{commit}}"],
        cwd=release.root,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if artifact.returncode != 0:
        raise ReleaseError("the pinned signed-artifact commit is not available locally")
    ancestry = subprocess.run(
        [
            "git",
            "merge-base",
            "--is-ancestor",
            release.artifact_commit,
            release.expected_commit,
        ],
        cwd=release.root,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if ancestry.returncode != 0:
        raise ReleaseError(
            "the pinned signed-artifact commit is not an ancestor of current main"
        )
    changed = subprocess.run(
        [
            "git",
            "diff",
            "--name-only",
            "-z",
            f"{release.artifact_commit}..{release.expected_commit}",
            "--",
        ],
        cwd=release.root,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ).stdout
    try:
        paths = {
            value.decode("utf-8")
            for value in changed.split(b"\0")
            if value
        }
    except UnicodeDecodeError as error:
        raise ReleaseError("the release commit diff contains a non-UTF-8 path") from error
    unexpected = sorted(paths - RELEASE_AUTOMATION_ALLOWLIST)
    if unexpected:
        raise ReleaseError(
            "the signed-artifact-to-main diff changes non-automation files: "
            + ", ".join(unexpected)
        )


def verify_exact_current_main(release: LocalRelease) -> None:
    head = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=release.root,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    ).stdout.strip()
    if head != release.expected_commit:
        raise ReleaseError("local HEAD does not equal the expected release commit")
    status_output = subprocess.run(
        ["git", "status", "--porcelain", "--untracked-files=all"],
        cwd=release.root,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    ).stdout
    if status_output:
        raise ReleaseError("the release checkout is not clean")
    verify_release_automation_diff(release)
    remote = subprocess.run(
        ["git", "ls-remote", "--exit-code", "origin", "refs/heads/main"],
        cwd=release.root,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    fields = remote.stdout.split()
    if remote.returncode != 0 or len(fields) != 2 or not EXACT_COMMIT.fullmatch(fields[0]):
        raise ReleaseError("could not resolve one exact current origin/main commit")
    if fields[0] != release.expected_commit:
        raise ReleaseError("origin/main moved; qualify and dispatch the new commit")


def validate_confirmations(
    release: LocalRelease,
    mode: str,
    metadata_confirmation: str | None,
    submit_confirmation: str | None,
    account_readiness: bool,
    screenshot_replacement_confirmation: str | None = None,
) -> None:
    if screenshot_replacement_confirmation:
        if mode not in {"apply-metadata", "submit"}:
            raise ReleaseError(
                "screenshot replacement confirmation is only valid for a mutating mode"
            )
        if screenshot_replacement_confirmation != release.screenshot_replacement_confirmation:
            raise ReleaseError(
                "screenshot replacement requires --confirm-screenshot-replacement "
                f"{release.screenshot_replacement_confirmation}"
            )
    if mode in {"apply-metadata", "submit"}:
        if metadata_confirmation != release.metadata_confirmation:
            raise ReleaseError(
                f"mutating metadata requires --confirm-metadata {release.metadata_confirmation}"
            )
    if mode == "submit":
        if submit_confirmation != release.submit_confirmation:
            raise ReleaseError(
                f"review submission requires --confirm-submit {release.submit_confirmation}"
            )
        if not account_readiness:
            raise ReleaseError(
                "review submission requires an explicit account-level readiness attestation"
            )


def _resource_attributes(resource: dict[str, Any]) -> dict[str, Any]:
    attributes = resource.get("attributes")
    if not isinstance(attributes, dict):
        raise ReleaseError("App Store Connect returned a resource without attributes")
    return attributes


def _resource_id(resource: dict[str, Any], expected_type: str) -> str:
    if resource.get("type") != expected_type or not isinstance(resource.get("id"), str):
        raise ReleaseError(f"App Store Connect returned a malformed {expected_type} resource")
    return resource["id"]


def _relationship_id(resource: dict[str, Any], name: str, expected_type: str) -> str | None:
    relationships = resource.get("relationships")
    if not isinstance(relationships, dict):
        return None
    relationship = relationships.get(name)
    if not isinstance(relationship, dict):
        return None
    data = relationship.get("data")
    if data is None:
        return None
    if not isinstance(data, dict) or data.get("type") != expected_type or not isinstance(data.get("id"), str):
        raise ReleaseError(f"App Store Connect returned malformed {name} relationship data")
    return data["id"]


def _data_resource(document: Any, expected_type: str) -> dict[str, Any]:
    data = document.get("data") if isinstance(document, dict) else None
    if not isinstance(data, dict):
        raise ReleaseError("App Store Connect returned malformed resource data")
    _resource_id(data, expected_type)
    return data


def _only(resources: list[dict[str, Any]], label: str, *, allow_zero: bool = False) -> dict[str, Any] | None:
    if not resources and allow_zero:
        return None
    if len(resources) != 1:
        raise ReleaseError(f"expected exactly one {label}; found {len(resources)}")
    return resources[0]


class ReleaseManager:
    def __init__(
        self,
        api: AppStoreConnectApi,
        release: LocalRelease,
        *,
        review_contact_source_version: str,
        screenshot_paths: list[Path] | None = None,
        allow_screenshot_replacement: bool = False,
        asset_timeout_seconds: int = 300,
    ):
        self.api = api
        self.release = release
        self.review_contact_source_version = review_contact_source_version
        self.screenshot_paths = screenshot_paths
        self.allow_screenshot_replacement = allow_screenshot_replacement
        self.asset_timeout_seconds = asset_timeout_seconds

    def find_app(self) -> dict[str, Any]:
        apps = self.api.list(
            "/v1/apps",
            params={"filter[bundleId]": BUNDLE_ID, "limit": 2},
        )
        app = _only(apps, f"app for {BUNDLE_ID}")
        assert app is not None
        attributes = _resource_attributes(app)
        if attributes.get("bundleId") != BUNDLE_ID:
            raise ReleaseError("App Store Connect app readback has the wrong bundle ID")
        if attributes.get("primaryLocale") != LOCALE:
            raise ReleaseError("App Store Connect app primary locale is not en-US")
        return app

    def versions(self, app_id: str) -> list[dict[str, Any]]:
        return self.api.list(
            f"/v1/apps/{app_id}/appStoreVersions",
            params={"filter[platform]": "IOS", "limit": 200},
        )

    def find_version(self, app_id: str, version: str) -> dict[str, Any] | None:
        matches = [
            item
            for item in self.versions(app_id)
            if _resource_attributes(item).get("versionString") == version
        ]
        return _only(matches, f"iOS version {version}", allow_zero=True)

    def find_build(self, app_id: str) -> dict[str, Any] | None:
        builds = self.api.list(
            "/v1/builds",
            params={
                "filter[app]": app_id,
                "filter[version]": self.release.build,
                "limit": 2,
            },
        )
        return _only(builds, f"build {self.release.build}", allow_zero=True)

    def version_localization(self, version_id: str) -> dict[str, Any] | None:
        values = self.api.list(
            f"/v1/appStoreVersions/{version_id}/appStoreVersionLocalizations",
            params={"filter[locale]": LOCALE, "limit": 2},
        )
        return _only(values, f"{LOCALE} version localization", allow_zero=True)

    def attached_build_id(self, version_id: str) -> str | None:
        document = self.api.request(
            "GET", f"/v1/appStoreVersions/{version_id}/relationships/build"
        )
        data = document.get("data") if isinstance(document, dict) else None
        if data is None:
            return None
        if not isinstance(data, dict) or data.get("type") != "builds" or not isinstance(data.get("id"), str):
            raise ReleaseError("App Store Connect returned malformed build linkage")
        return data["id"]

    def app_info(self, app_id: str) -> dict[str, Any] | None:
        values = self.api.list(f"/v1/apps/{app_id}/appInfos", params={"limit": 200})
        editable = [
            value
            for value in values
            if _resource_attributes(value).get("state") == "PREPARE_FOR_SUBMISSION"
        ]
        return _only(editable, "editable app info", allow_zero=True)

    def app_info_localization(self, app_info_id: str) -> dict[str, Any] | None:
        values = self.api.list(
            f"/v1/appInfos/{app_info_id}/appInfoLocalizations",
            params={"filter[locale]": LOCALE, "limit": 2},
        )
        return _only(values, f"{LOCALE} app info localization", allow_zero=True)

    def review_detail(self, version_id: str) -> dict[str, Any] | None:
        try:
            document = self.api.request(
                "GET", f"/v1/appStoreVersions/{version_id}/appStoreReviewDetail"
            )
        except ApiError as error:
            if error.status == 404:
                return None
            raise
        return _data_resource(document, "appStoreReviewDetails")

    def screenshot_set(self, localization_id: str) -> dict[str, Any] | None:
        values = self.api.list(
            f"/v1/appStoreVersionLocalizations/{localization_id}/appScreenshotSets",
            params={"filter[screenshotDisplayType]": SCREENSHOT_DISPLAY_TYPE, "limit": 2},
        )
        return _only(values, f"{SCREENSHOT_DISPLAY_TYPE} screenshot set", allow_zero=True)

    def screenshots(self, screenshot_set_id: str) -> list[dict[str, Any]]:
        return self.api.list(
            f"/v1/appScreenshotSets/{screenshot_set_id}/appScreenshots",
            params={
                "fields[appScreenshots]": (
                    "fileSize,fileName,sourceFileChecksum,uploadOperations,"
                    "assetDeliveryState"
                ),
                "limit": 200,
            },
        )

    @staticmethod
    def screenshot_readback(resources: list[dict[str, Any]]) -> list[dict[str, Any]]:
        """Return non-sensitive ordered screenshot attributes for GET-only diagnosis."""
        readback: list[dict[str, Any]] = []
        for position, resource in enumerate(resources, start=1):
            attributes = _resource_attributes(resource)
            delivery = attributes.get("assetDeliveryState")
            state = delivery.get("state") if isinstance(delivery, dict) else None
            readback.append(
                {
                    "position": position,
                    "id": _resource_id(resource, "appScreenshots"),
                    "fileName": attributes.get("fileName"),
                    "fileSize": attributes.get("fileSize"),
                    "sourceFileChecksum": attributes.get("sourceFileChecksum"),
                    "state": state,
                }
            )
        return readback

    def active_review_submissions(self, app_id: str) -> list[dict[str, Any]]:
        values = self.api.list(
            f"/v1/apps/{app_id}/reviewSubmissions",
            params={"filter[platform]": "IOS", "limit": 200},
        )
        return [
            value
            for value in values
            if _resource_attributes(value).get("state") in ACTIVE_REVIEW_STATES
        ]

    def submission_items(self, submission_id: str) -> list[dict[str, Any]]:
        return self.api.list(
            f"/v1/reviewSubmissions/{submission_id}/items",
            params={"include": "appStoreVersion", "limit": 50},
        )

    def discover(self) -> dict[str, Any]:
        app = self.find_app()
        app_id = _resource_id(app, "apps")
        version = self.find_version(app_id, self.release.version)
        build = self.find_build(app_id)
        result: dict[str, Any] = {
            "appFound": True,
            "build": None,
            "version": None,
            "activeReviewSubmissions": [],
        }
        if build is not None:
            attrs = _resource_attributes(build)
            result["build"] = {
                "expired": attrs.get("expired"),
                "processingState": attrs.get("processingState"),
                "usesNonExemptEncryption": attrs.get("usesNonExemptEncryption"),
                "version": attrs.get("version"),
            }
        if version is not None:
            version_id = _resource_id(version, "appStoreVersions")
            attrs = _resource_attributes(version)
            localization = self.version_localization(version_id)
            review_detail = self.review_detail(version_id)
            screenshot_summary = None
            if localization is not None:
                localization_id = _resource_id(localization, "appStoreVersionLocalizations")
                screenshot_set = self.screenshot_set(localization_id)
                if screenshot_set is not None:
                    screenshots = self.screenshots(_resource_id(screenshot_set, "appScreenshotSets"))
                    screenshot_readback = self.screenshot_readback(screenshots)
                    screenshot_summary = {
                        "count": len(screenshots),
                        "states": [item["state"] for item in screenshot_readback],
                        "resources": screenshot_readback,
                    }
            result["version"] = {
                "appStoreState": attrs.get("appStoreState"),
                "appVersionState": attrs.get("appVersionState"),
                "attachedBuild": self.attached_build_id(version_id) is not None,
                "hasLocalization": localization is not None,
                "hasReviewDetail": review_detail is not None,
                "screenshots": screenshot_summary,
                "usesIdfa": attrs.get("usesIdfa"),
                "versionString": attrs.get("versionString"),
            }
        for submission in self.active_review_submissions(app_id):
            result["activeReviewSubmissions"].append(
                {
                    "state": _resource_attributes(submission).get("state"),
                    "itemCount": len(self.submission_items(_resource_id(submission, "reviewSubmissions"))),
                }
            )
        return result

    def apply_metadata(self) -> dict[str, Any]:
        if not self.screenshot_paths:
            raise ReleaseError("mutating metadata requires verified exact-commit screenshots")
        app = self.find_app()
        app_id = _resource_id(app, "apps")
        version = self.find_version(app_id, self.release.version)
        if version is None:
            document = self.api.request(
                "POST",
                "/v1/appStoreVersions",
                body={
                    "data": {
                        "type": "appStoreVersions",
                        "attributes": {
                            "copyright": self.release.metadata["copyright"],
                            "platform": "IOS",
                            "releaseType": "MANUAL",
                            "reviewType": "APP_STORE",
                            "usesIdfa": False,
                            "versionString": self.release.version,
                        },
                        "relationships": {
                            "app": {"data": {"type": "apps", "id": app_id}}
                        },
                    }
                },
                expected=(201,),
            )
            version = _data_resource(document, "appStoreVersions")
        else:
            self._assert_version_editable(version)
            version_id = _resource_id(version, "appStoreVersions")
            document = self.api.request(
                "PATCH",
                f"/v1/appStoreVersions/{version_id}",
                body={
                    "data": {
                        "type": "appStoreVersions",
                        "id": version_id,
                        "attributes": {
                            "copyright": self.release.metadata["copyright"],
                            "releaseType": "MANUAL",
                            "reviewType": "APP_STORE",
                            "usesIdfa": False,
                        },
                    }
                },
            )
            version = _data_resource(document, "appStoreVersions")
        self._assert_version_editable(version)
        version_id = _resource_id(version, "appStoreVersions")

        build = self.find_build(app_id)
        if build is None:
            raise ReleaseError(
                f"processed App Store Connect build {self.release.build} is not available"
            )
        build_id = _resource_id(build, "builds")
        build_attributes = _resource_attributes(build)
        if build_attributes.get("version") != self.release.build:
            raise ReleaseError("App Store Connect returned the wrong build number")
        if build_attributes.get("processingState") != "VALID":
            raise ReleaseError("the exact App Store Connect build is not VALID")
        if build_attributes.get("expired") is not False:
            raise ReleaseError("the exact App Store Connect build is expired or expiry is unknown")
        if build_attributes.get("usesNonExemptEncryption") is not False:
            raise ReleaseError(
                "the exact build's export-compliance value is not the reviewed false declaration"
            )
        if self.attached_build_id(version_id) != build_id:
            self.api.request(
                "PATCH",
                f"/v1/appStoreVersions/{version_id}/relationships/build",
                body={"data": {"type": "builds", "id": build_id}},
                expected=(204,),
            )

        localization = self._upsert_version_localization(version_id)
        localization_id = _resource_id(localization, "appStoreVersionLocalizations")
        self._upsert_app_info_localization(app_id)
        self._upsert_review_detail(app_id, version_id)
        screenshot_set = self._ensure_screenshots(localization_id)
        self._verify_readback(app_id, version_id, build_id, localization_id, screenshot_set)
        return {
            "version": self.release.version,
            "build": self.release.build,
            "metadataReadback": "verified",
            "screenshotCount": len(self.screenshot_paths),
            "releaseType": "MANUAL",
        }

    def _assert_version_editable(self, version: dict[str, Any]) -> None:
        attrs = _resource_attributes(version)
        states = {attrs.get("appStoreState"), attrs.get("appVersionState")}
        known = {state for state in states if isinstance(state, str)}
        if not known.intersection(EDITABLE_VERSION_STATES):
            raise ReleaseError(
                "the exact App Store version is not in PREPARE_FOR_SUBMISSION"
            )

    def _upsert_version_localization(self, version_id: str) -> dict[str, Any]:
        attributes = {
            field: self.release.metadata[field]
            for field in VERSION_LOCALIZATION_FIELDS
        }
        localization = self.version_localization(version_id)
        if localization is None:
            document = self.api.request(
                "POST",
                "/v1/appStoreVersionLocalizations",
                body={
                    "data": {
                        "type": "appStoreVersionLocalizations",
                        "attributes": {"locale": LOCALE, **attributes},
                        "relationships": {
                            "appStoreVersion": {
                                "data": {"type": "appStoreVersions", "id": version_id}
                            }
                        },
                    }
                },
                expected=(201,),
            )
        else:
            localization_id = _resource_id(localization, "appStoreVersionLocalizations")
            document = self.api.request(
                "PATCH",
                f"/v1/appStoreVersionLocalizations/{localization_id}",
                body={
                    "data": {
                        "type": "appStoreVersionLocalizations",
                        "id": localization_id,
                        "attributes": attributes,
                    }
                },
            )
        return _data_resource(document, "appStoreVersionLocalizations")

    def _upsert_app_info_localization(self, app_id: str) -> None:
        app_info = self.app_info(app_id)
        if app_info is None:
            raise ReleaseError(
                "App Store Connect has no single PREPARE_FOR_SUBMISSION app-info record"
            )
        app_info_id = _resource_id(app_info, "appInfos")
        attributes = {
            field: self.release.metadata[field]
            for field in APP_INFO_LOCALIZATION_FIELDS
        }
        localization = self.app_info_localization(app_info_id)
        if localization is None:
            document = self.api.request(
                "POST",
                "/v1/appInfoLocalizations",
                body={
                    "data": {
                        "type": "appInfoLocalizations",
                        "attributes": {"locale": LOCALE, **attributes},
                        "relationships": {
                            "appInfo": {"data": {"type": "appInfos", "id": app_info_id}}
                        },
                    }
                },
                expected=(201,),
            )
        else:
            localization_id = _resource_id(localization, "appInfoLocalizations")
            document = self.api.request(
                "PATCH",
                f"/v1/appInfoLocalizations/{localization_id}",
                body={
                    "data": {
                        "type": "appInfoLocalizations",
                        "id": localization_id,
                        "attributes": attributes,
                    }
                },
            )
        readback = _data_resource(document, "appInfoLocalizations")
        readback_attributes = _resource_attributes(readback)
        for field, expected in attributes.items():
            if readback_attributes.get(field) != expected:
                raise ReleaseError(f"App Store app-info readback differs for {field}")

    def _upsert_review_detail(self, app_id: str, version_id: str) -> None:
        current = self.review_detail(version_id)
        if current is not None:
            current_id = _resource_id(current, "appStoreReviewDetails")
            current_attributes = _resource_attributes(current)
            self._validate_review_contact(current_attributes)
            document = self.api.request(
                "PATCH",
                f"/v1/appStoreReviewDetails/{current_id}",
                body={
                    "data": {
                        "type": "appStoreReviewDetails",
                        "id": current_id,
                        "attributes": {"notes": self.release.metadata["reviewNotes"]},
                    }
                },
            )
        else:
            source_version = self.find_version(app_id, self.review_contact_source_version)
            if source_version is None:
                raise ReleaseError(
                    "the requested prior version for copying review contact information was not found"
                )
            source = self.review_detail(_resource_id(source_version, "appStoreVersions"))
            if source is None:
                raise ReleaseError("the prior version has no App Review contact information")
            source_attributes = _resource_attributes(source)
            self._validate_review_contact(source_attributes)
            copied = {
                field: source_attributes.get(field)
                for field in REQUIRED_REVIEW_CONTACT_FIELDS + ("demoAccountRequired",)
            }
            if source_attributes["demoAccountRequired"]:
                copied["demoAccountName"] = source_attributes["demoAccountName"]
                copied["demoAccountPassword"] = source_attributes["demoAccountPassword"]
            copied["notes"] = self.release.metadata["reviewNotes"]
            document = self.api.request(
                "POST",
                "/v1/appStoreReviewDetails",
                body={
                    "data": {
                        "type": "appStoreReviewDetails",
                        "attributes": copied,
                        "relationships": {
                            "appStoreVersion": {
                                "data": {"type": "appStoreVersions", "id": version_id}
                            }
                        },
                    }
                },
                expected=(201,),
            )
        detail = _data_resource(document, "appStoreReviewDetails")
        attributes = _resource_attributes(detail)
        self._validate_review_contact(attributes)
        if attributes.get("notes") != self.release.metadata["reviewNotes"]:
            raise ReleaseError("App Review notes readback differs from the reviewed source")

    @staticmethod
    def _validate_review_contact(attributes: dict[str, Any]) -> None:
        missing = [
            field
            for field in REQUIRED_REVIEW_CONTACT_FIELDS
            if not isinstance(attributes.get(field), str) or not attributes[field].strip()
        ]
        if missing:
            raise ReleaseError(
                "App Review contact information is incomplete; missing " + ", ".join(missing)
            )
        if not isinstance(attributes.get("demoAccountRequired"), bool):
            raise ReleaseError("App Review demo-account requirement is missing")
        if attributes["demoAccountRequired"]:
            for field in ("demoAccountName", "demoAccountPassword"):
                if not isinstance(attributes.get(field), str) or not attributes[field]:
                    raise ReleaseError(
                        "App Review requires a demo account but its credentials are incomplete"
                    )

    def _ensure_screenshots(self, localization_id: str) -> str:
        screenshot_set = self.screenshot_set(localization_id)
        if screenshot_set is None:
            document = self.api.request(
                "POST",
                "/v1/appScreenshotSets",
                body={
                    "data": {
                        "type": "appScreenshotSets",
                        "attributes": {"screenshotDisplayType": SCREENSHOT_DISPLAY_TYPE},
                        "relationships": {
                            "appStoreVersionLocalization": {
                                "data": {
                                    "type": "appStoreVersionLocalizations",
                                    "id": localization_id,
                                }
                            }
                        },
                    }
                },
                expected=(201,),
            )
            screenshot_set = _data_resource(document, "appScreenshotSets")
        screenshot_set_id = self._validated_screenshot_set_id(screenshot_set)
        existing = self.screenshots(screenshot_set_id)
        if existing:
            try:
                self._resume_exact_screenshot_prefix(existing)
            except ScreenshotSetMismatch:
                if not self.allow_screenshot_replacement:
                    raise
                self._replace_mismatching_screenshots(screenshot_set, existing)
                existing = []
        for path in (self.screenshot_paths or [])[len(existing) :]:
            self._upload_screenshot(screenshot_set_id, path)
        self._wait_for_screenshots(screenshot_set_id)
        self._verify_screenshot_resources(self.screenshots(screenshot_set_id))
        return screenshot_set_id

    @staticmethod
    def _validated_screenshot_set_id(screenshot_set: dict[str, Any]) -> str:
        screenshot_set_id = _resource_id(screenshot_set, "appScreenshotSets")
        if not SAFE_RESOURCE_ID.fullmatch(screenshot_set_id):
            raise ReleaseError("the App Store screenshot set has an unsafe resource ID")
        attributes = _resource_attributes(screenshot_set)
        if attributes.get("screenshotDisplayType") != SCREENSHOT_DISPLAY_TYPE:
            raise ReleaseError(
                f"refusing to mutate a screenshot set other than {SCREENSHOT_DISPLAY_TYPE}"
            )
        return screenshot_set_id

    @staticmethod
    def _screenshot_snapshot(
        resources: list[dict[str, Any]],
    ) -> tuple[tuple[str, str, int, str, str], ...]:
        if not resources or len(resources) > MAX_SCREENSHOTS_PER_SET:
            raise ReleaseError(
                "the mismatching App Store screenshot set is not a sane non-empty set"
            )
        records: list[tuple[str, str, int, str, str]] = []
        ids: set[str] = set()
        for resource in resources:
            screenshot_id = _resource_id(resource, "appScreenshots")
            if (
                not SAFE_RESOURCE_ID.fullmatch(screenshot_id)
                or screenshot_id in ids
            ):
                raise ReleaseError(
                    "the mismatching App Store screenshot set has invalid resource IDs"
                )
            ids.add(screenshot_id)
            attributes = _resource_attributes(resource)
            filename = attributes.get("fileName")
            file_size = attributes.get("fileSize")
            checksum = attributes.get("sourceFileChecksum")
            delivery = attributes.get("assetDeliveryState")
            state = delivery.get("state") if isinstance(delivery, dict) else None
            if (
                not isinstance(filename, str)
                or not filename
                or filename != Path(filename).name
                or len(filename) > 255
                or not isinstance(file_size, int)
                or isinstance(file_size, bool)
                or file_size <= 0
                or not isinstance(checksum, str)
                or not MD5_CHECKSUM.fullmatch(checksum)
                or state != "COMPLETE"
            ):
                raise ReleaseError(
                    "the mismatching App Store screenshot set is not complete and sane; "
                    "refusing deletion"
                )
            records.append(
                (screenshot_id, filename, file_size, checksum.casefold(), state)
            )
        return tuple(sorted(records))

    def _replace_mismatching_screenshots(
        self,
        screenshot_set: dict[str, Any],
        resources: list[dict[str, Any]],
    ) -> None:
        screenshot_set_id = self._validated_screenshot_set_id(screenshot_set)
        expected_snapshot = self._screenshot_snapshot(resources)
        refreshed = self.screenshots(screenshot_set_id)
        if self._screenshot_snapshot(refreshed) != expected_snapshot:
            raise ReleaseError(
                "the App Store screenshot set changed during replacement validation"
            )
        screenshot_ids = {record[0] for record in expected_snapshot}
        for screenshot_id in sorted(screenshot_ids):
            self.api.request(
                "DELETE",
                f"/v1/appScreenshots/{screenshot_id}",
                expected=(204,),
            )
        self._wait_for_empty_screenshot_set(screenshot_set_id, screenshot_ids)

    def _wait_for_empty_screenshot_set(
        self,
        screenshot_set_id: str,
        deleted_ids: set[str],
    ) -> None:
        deadline = time.monotonic() + self.asset_timeout_seconds
        while True:
            resources = self.screenshots(screenshot_set_id)
            if not resources:
                return
            remaining_ids = {
                _resource_id(resource, "appScreenshots") for resource in resources
            }
            if not remaining_ids.issubset(deleted_ids):
                raise ReleaseError(
                    "an unexpected screenshot appeared while verifying replacement deletion"
                )
            if time.monotonic() >= deadline:
                raise ReleaseError(
                    "timed out waiting for the replaced screenshot set to become empty"
                )
            time.sleep(5)

    def _resume_exact_screenshot_prefix(self, resources: list[dict[str, Any]]) -> None:
        paths = self.screenshot_paths or []
        if len(resources) > len(paths):
            raise ScreenshotSetMismatch(
                "the existing App Store screenshot set differs from the exact release set; "
                "refusing destructive replacement without its release-specific confirmation"
            )
        resumable: list[tuple[dict[str, Any], Path, str, str]] = []
        for resource, path in zip(resources, paths):
            attributes = _resource_attributes(resource)
            if (
                attributes.get("fileName") != path.name
                or attributes.get("fileSize") != path.stat().st_size
            ):
                raise ScreenshotSetMismatch(
                    "the existing App Store screenshot set differs from the exact release set; "
                    "refusing destructive replacement without its release-specific confirmation"
                )
            delivery = attributes.get("assetDeliveryState")
            state = delivery.get("state") if isinstance(delivery, dict) else None
            checksum = hashlib.md5(path.read_bytes()).hexdigest()  # noqa: S324 - Apple API contract
            if state == "COMPLETE":
                if attributes.get("sourceFileChecksum") != checksum:
                    raise ScreenshotSetMismatch(
                        "the existing App Store screenshot set differs from the exact release set; "
                        "refusing destructive replacement without its release-specific confirmation"
                    )
            elif state not in {"AWAITING_UPLOAD", "UPLOAD_COMPLETE"}:
                raise ReleaseError(
                    "the existing App Store screenshot reservation cannot be resumed safely"
                )
            resumable.append((resource, path, checksum, state))
        for resource, path, checksum, state in resumable:
            if state == "AWAITING_UPLOAD":
                attributes = _resource_attributes(resource)
                self._validate_and_upload_operations(path, attributes.get("uploadOperations"))
                screenshot_id = _resource_id(resource, "appScreenshots")
                self.api.request(
                    "PATCH",
                    f"/v1/appScreenshots/{screenshot_id}",
                    body={
                        "data": {
                            "type": "appScreenshots",
                            "id": screenshot_id,
                            "attributes": {
                                "uploaded": True,
                                "sourceFileChecksum": checksum,
                            },
                        }
                    },
                )

    def _verify_screenshot_resources(self, resources: list[dict[str, Any]]) -> None:
        paths = self.screenshot_paths or []
        if len(resources) != len(paths):
            raise ScreenshotSetMismatch(
                "the existing App Store screenshot set differs from the exact release set; "
                "refusing destructive replacement without its release-specific confirmation"
            )
        for resource, path in zip(resources, paths):
            attributes = _resource_attributes(resource)
            delivery = attributes.get("assetDeliveryState")
            state = delivery.get("state") if isinstance(delivery, dict) else None
            checksum = hashlib.md5(path.read_bytes()).hexdigest()  # noqa: S324 - Apple API contract
            if (
                attributes.get("fileName") != path.name
                or attributes.get("fileSize") != path.stat().st_size
                or attributes.get("sourceFileChecksum") != checksum
                or state != "COMPLETE"
            ):
                raise ScreenshotSetMismatch(
                    "the existing App Store screenshot set differs from the exact release set; "
                    "refusing destructive replacement without its release-specific confirmation"
                )

    def _upload_screenshot(self, screenshot_set_id: str, path: Path) -> None:
        document = self.api.request(
            "POST",
            "/v1/appScreenshots",
            body={
                "data": {
                    "type": "appScreenshots",
                    "attributes": {"fileName": path.name, "fileSize": path.stat().st_size},
                    "relationships": {
                        "appScreenshotSet": {
                            "data": {"type": "appScreenshotSets", "id": screenshot_set_id}
                        }
                    },
                }
            },
            expected=(201,),
        )
        screenshot = _data_resource(document, "appScreenshots")
        screenshot_id = _resource_id(screenshot, "appScreenshots")
        operations = _resource_attributes(screenshot).get("uploadOperations")
        self._validate_and_upload_operations(path, operations)
        checksum = hashlib.md5(path.read_bytes()).hexdigest()  # noqa: S324 - Apple API contract
        self.api.request(
            "PATCH",
            f"/v1/appScreenshots/{screenshot_id}",
            body={
                "data": {
                    "type": "appScreenshots",
                    "id": screenshot_id,
                    "attributes": {"uploaded": True, "sourceFileChecksum": checksum},
                }
            },
        )

    def _validate_and_upload_operations(self, path: Path, operations: Any) -> None:
        if not isinstance(operations, list) or not operations:
            raise ReleaseError("App Store Connect returned no screenshot upload operations")
        normalized: list[tuple[int, int, str, str, dict[str, str]]] = []
        for operation in operations:
            if not isinstance(operation, dict):
                raise ReleaseError("App Store Connect returned a malformed upload operation")
            method = operation.get("method")
            url = operation.get("url")
            offset = operation.get("offset")
            length = operation.get("length")
            headers_list = operation.get("requestHeaders")
            if method != "PUT" or not isinstance(url, str):
                raise ReleaseError("refusing an unexpected screenshot upload method or URL")
            parsed = urlsplit(url)
            if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
                raise ReleaseError("refusing a non-HTTPS screenshot upload URL")
            hostname = (parsed.hostname or "").casefold()
            if hostname != "apple.com" and not hostname.endswith(".apple.com"):
                raise ReleaseError("refusing a screenshot upload URL outside apple.com")
            if not isinstance(offset, int) or not isinstance(length, int) or offset < 0 or length <= 0:
                raise ReleaseError("App Store Connect returned an invalid upload byte range")
            if not isinstance(headers_list, list):
                raise ReleaseError("App Store Connect returned malformed upload headers")
            headers: dict[str, str] = {}
            for header in headers_list:
                if not isinstance(header, dict):
                    raise ReleaseError("App Store Connect returned a malformed upload header")
                name, value = header.get("name"), header.get("value")
                if not isinstance(name, str) or not isinstance(value, str):
                    raise ReleaseError("App Store Connect returned a malformed upload header")
                if name.casefold() in {"authorization", "cookie", "proxy-authorization"}:
                    raise ReleaseError("refusing a sensitive header in an asset upload operation")
                headers[name] = value
            normalized.append((offset, length, method, url, headers))
        ranges = sorted((offset, offset + length) for offset, length, *_ in normalized)
        cursor = 0
        for start, end in ranges:
            if start != cursor or end > path.stat().st_size:
                raise ReleaseError("screenshot upload operations do not exactly cover the source file")
            cursor = end
        if cursor != path.stat().st_size:
            raise ReleaseError("screenshot upload operations do not exactly cover the source file")
        with path.open("rb") as source:
            for offset, length, method, url, headers in normalized:
                source.seek(offset)
                data = source.read(length)
                request = Request(url, data=data, headers=headers, method=method)
                try:
                    with build_opener(NoRedirect()).open(request, timeout=120) as response:
                        if response.status < 200 or response.status >= 300:
                            raise ReleaseError("Apple screenshot byte upload was not accepted")
                except (HTTPError, URLError, TimeoutError) as error:
                    raise ReleaseError("Apple screenshot byte upload failed") from error

    def _wait_for_screenshots(self, screenshot_set_id: str) -> None:
        deadline = time.monotonic() + self.asset_timeout_seconds
        while True:
            resources = self.screenshots(screenshot_set_id)
            states = []
            for resource in resources:
                delivery = _resource_attributes(resource).get("assetDeliveryState")
                states.append(delivery.get("state") if isinstance(delivery, dict) else None)
            if len(states) == len(self.screenshot_paths or []) and states and all(
                state == "COMPLETE" for state in states
            ):
                return
            if any(state == "FAILED" for state in states):
                raise ReleaseError("App Store Connect rejected a screenshot during processing")
            if time.monotonic() >= deadline:
                raise ReleaseError("timed out waiting for App Store screenshots to process")
            time.sleep(5)

    def _verify_readback(
        self,
        app_id: str,
        version_id: str,
        build_id: str,
        localization_id: str,
        screenshot_set_id: str,
    ) -> None:
        version_document = self.api.request("GET", f"/v1/appStoreVersions/{version_id}")
        version = _data_resource(version_document, "appStoreVersions")
        attributes = _resource_attributes(version)
        if attributes.get("versionString") != self.release.version:
            raise ReleaseError("App Store version readback differs from the exact release")
        if attributes.get("releaseType") != "MANUAL":
            raise ReleaseError("App Store release type readback is not MANUAL")
        if attributes.get("reviewType") != "APP_STORE":
            raise ReleaseError("App Store review type readback is not APP_STORE")
        if attributes.get("usesIdfa") is not False:
            raise ReleaseError("App Store IDFA declaration readback is not false")
        if attributes.get("copyright") != self.release.metadata["copyright"]:
            raise ReleaseError("App Store copyright readback differs")
        if self.attached_build_id(version_id) != build_id:
            raise ReleaseError("App Store build relationship readback differs")
        localization_document = self.api.request(
            "GET", f"/v1/appStoreVersionLocalizations/{localization_id}"
        )
        localization = _data_resource(localization_document, "appStoreVersionLocalizations")
        localization_attributes = _resource_attributes(localization)
        for field in VERSION_LOCALIZATION_FIELDS:
            if localization_attributes.get(field) != self.release.metadata[field]:
                raise ReleaseError(f"App Store version localization readback differs for {field}")
        self._verify_screenshot_resources(self.screenshots(screenshot_set_id))
        detail = self.review_detail(version_id)
        if detail is None or _resource_attributes(detail).get("notes") != self.release.metadata["reviewNotes"]:
            raise ReleaseError("App Review notes readback differs")
        app_info = self.app_info(app_id)
        if app_info is None:
            raise ReleaseError("editable App Store app-info record disappeared during readback")
        app_info_localization = self.app_info_localization(_resource_id(app_info, "appInfos"))
        if app_info_localization is None:
            raise ReleaseError("App Store app-info localization disappeared during readback")
        app_info_attributes = _resource_attributes(app_info_localization)
        for field in APP_INFO_LOCALIZATION_FIELDS:
            if app_info_attributes.get(field) != self.release.metadata[field]:
                raise ReleaseError(f"App Store app-info readback differs for {field}")

    def submit(self) -> dict[str, Any]:
        app = self.find_app()
        app_id = _resource_id(app, "apps")
        version = self.find_version(app_id, self.release.version)
        if version is None:
            raise ReleaseError("the exact App Store version does not exist after metadata apply")
        version_id = _resource_id(version, "appStoreVersions")
        active = self.active_review_submissions(app_id)
        if len(active) > 1:
            raise ReleaseError("multiple active App Review submissions exist; resolve them manually")
        submission: dict[str, Any]
        items: list[dict[str, Any]]
        if active:
            submission = active[0]
            submission_id = _resource_id(submission, "reviewSubmissions")
            items = self.submission_items(submission_id)
            item_versions = {
                value
                for item in items
                for value in [_relationship_id(item, "appStoreVersion", "appStoreVersions")]
                if value is not None
            }
            state = _resource_attributes(submission).get("state")
            if state in SUBMITTED_REVIEW_STATES and item_versions == {version_id}:
                return {"reviewState": state, "alreadySubmitted": True}
            if state != "READY_FOR_REVIEW":
                raise ReleaseError("another App Review submission is active")
            if item_versions not in (set(), {version_id}) or len(items) > 1:
                raise ReleaseError("the READY_FOR_REVIEW submission contains unrelated items")
        else:
            document = self.api.request(
                "POST",
                "/v1/reviewSubmissions",
                body={
                    "data": {
                        "type": "reviewSubmissions",
                        "attributes": {"platform": "IOS"},
                        "relationships": {
                            "app": {"data": {"type": "apps", "id": app_id}}
                        },
                    }
                },
                expected=(201,),
            )
            submission = _data_resource(document, "reviewSubmissions")
            submission_id = _resource_id(submission, "reviewSubmissions")
            items = []
        if not items:
            self.api.request(
                "POST",
                "/v1/reviewSubmissionItems",
                body={
                    "data": {
                        "type": "reviewSubmissionItems",
                        "relationships": {
                            "appStoreVersion": {
                                "data": {"type": "appStoreVersions", "id": version_id}
                            },
                            "reviewSubmission": {
                                "data": {"type": "reviewSubmissions", "id": submission_id}
                            },
                        },
                    }
                },
                expected=(201,),
            )
        items = self.submission_items(submission_id)
        if len(items) != 1 or _relationship_id(
            items[0], "appStoreVersion", "appStoreVersions"
        ) != version_id:
            raise ReleaseError("App Review submission item readback differs from the exact version")
        verify_exact_current_main(self.release)
        document = self.api.request(
            "PATCH",
            f"/v1/reviewSubmissions/{submission_id}",
            body={
                "data": {
                    "type": "reviewSubmissions",
                    "id": submission_id,
                    "attributes": {"submitted": True},
                }
            },
        )
        submission = _data_resource(document, "reviewSubmissions")
        state = _resource_attributes(submission).get("state")
        if state not in SUBMITTED_REVIEW_STATES:
            raise ReleaseError(
                "App Store Connect accepted the submit request but did not return a submitted state"
            )
        return {"reviewState": state, "alreadySubmitted": False}

    def existing_submission_status(self) -> dict[str, Any] | None:
        """Return a read-only exact-version submission result, if one exists."""
        app = self.find_app()
        app_id = _resource_id(app, "apps")
        version = self.find_version(app_id, self.release.version)
        if version is None:
            return None
        version_id = _resource_id(version, "appStoreVersions")
        active = self.active_review_submissions(app_id)
        if len(active) > 1:
            raise ReleaseError("multiple active App Review submissions exist; resolve them manually")
        if not active:
            return None
        submission = active[0]
        state = _resource_attributes(submission).get("state")
        if state not in SUBMITTED_REVIEW_STATES:
            return None
        items = self.submission_items(_resource_id(submission, "reviewSubmissions"))
        item_versions = {
            value
            for item in items
            for value in [_relationship_id(item, "appStoreVersion", "appStoreVersions")]
            if value is not None
        }
        if len(items) == 1 and item_versions == {version_id}:
            return {"reviewState": state, "alreadySubmitted": True}
        raise ReleaseError("another App Review submission is active")


def local_plan(release: LocalRelease) -> dict[str, Any]:
    digests = {
        METADATA_FILES[key]: hashlib.sha256(value.encode("utf-8")).hexdigest()
        for key, value in sorted(release.metadata.items())
    }
    return {
        "mode": "plan",
        "networkRequests": 0,
        "mutations": 0,
        "bundleId": BUNDLE_ID,
        "version": release.version,
        "build": release.build,
        "expectedCommit": release.expected_commit,
        "artifactCommit": release.artifact_commit,
        "metadataSha256": digests,
        "requiredMetadataConfirmation": release.metadata_confirmation,
        "requiredScreenshotReplacementConfirmation": (
            release.screenshot_replacement_confirmation
        ),
        "requiredSubmitConfirmation": release.submit_confirmation,
    }


def execute_authenticated_mode(manager: ReleaseManager, mode: str) -> dict[str, Any]:
    if mode == "discover":
        return manager.discover()
    if mode == "submit":
        existing = manager.existing_submission_status()
        if existing is not None:
            return {
                "metadata": {"status": "unchanged-already-submitted"},
                "submission": existing,
            }
    result = {"metadata": manager.apply_metadata()}
    if mode == "submit":
        result["submission"] = manager.submit()
    return result


def verified_screenshot_paths(root: Path, directory: Path, expected_commit: str) -> list[Path]:
    try:
        sys.path.insert(0, str(root / "scripts"))
        from ios_screenshot_tools import load_json, verify_live_set

        return verify_live_set(
            directory,
            load_json(str(directory / "manifest.json")),
            expected_commit=expected_commit,
        )
    except (ImportError, OSError, ValueError) as error:
        raise ReleaseError(f"exact-commit App Store screenshots failed validation: {error}") from error


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--mode",
        choices=("plan", "discover", "apply-metadata", "submit"),
        default="plan",
    )
    parser.add_argument("--expected-commit", required=True)
    parser.add_argument("--expected-artifact-commit")
    parser.add_argument("--expected-version", required=True)
    parser.add_argument("--expected-build", required=True)
    parser.add_argument("--review-contact-source-version", default="0.5.5")
    parser.add_argument("--screenshots-dir", default="build/app-store-live-screenshots")
    parser.add_argument("--asset-timeout-seconds", type=int, default=300)
    parser.add_argument("--confirm-metadata")
    parser.add_argument("--confirm-screenshot-replacement")
    parser.add_argument("--confirm-submit")
    parser.add_argument("--confirm-account-readiness", action="store_true")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    root = Path(__file__).resolve().parents[1]
    try:
        release = load_local_release(
            root,
            args.expected_commit,
            args.expected_artifact_commit or args.expected_commit,
            args.expected_version,
            args.expected_build,
        )
        validate_confirmations(
            release,
            args.mode,
            args.confirm_metadata,
            args.confirm_submit,
            args.confirm_account_readiness,
            args.confirm_screenshot_replacement,
        )
        if args.mode == "plan":
            print(json.dumps(local_plan(release), indent=2, sort_keys=True))
            return 0
        key_id = os.environ.get("HNS_ASC_API_KEY_ID", "")
        issuer_id = os.environ.get("HNS_ASC_API_KEY_ISSUER_ID", "")
        key_path_value = os.environ.get("HNS_ASC_API_KEY_PATH", "")
        if not key_path_value:
            raise ReleaseError("HNS_ASC_API_KEY_PATH is required for authenticated modes")
        api = AppStoreConnectApi(JwtProvider(key_id, issuer_id, Path(key_path_value)))
        screenshot_paths = None
        if args.mode in {"apply-metadata", "submit"}:
            verify_exact_current_main(release)
            screenshot_paths = verified_screenshot_paths(
                root, (root / args.screenshots_dir).resolve(), release.artifact_commit
            )
        manager = ReleaseManager(
            api,
            release,
            review_contact_source_version=args.review_contact_source_version,
            screenshot_paths=screenshot_paths,
            allow_screenshot_replacement=(
                args.confirm_screenshot_replacement
                == release.screenshot_replacement_confirmation
            ),
            asset_timeout_seconds=args.asset_timeout_seconds,
        )
        result = execute_authenticated_mode(manager, args.mode)
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (ReleaseError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
