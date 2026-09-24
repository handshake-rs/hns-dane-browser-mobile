"""The side-by-side iOS recovery app must not inherit browser URL handlers."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import plistlib
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "prepare_ios_legacy_recovery_plist",
    ROOT / "scripts" / "prepare-ios-legacy-recovery-plist.py",
)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class LegacyRecoveryPlistTests(unittest.TestCase):
    def test_recovery_plist_has_isolated_identity_without_browser_handlers(self) -> None:
        source = ROOT / "ios/HnsDaneBrowser/Support/Info.plist"
        with tempfile.TemporaryDirectory() as directory:
            result = Path(directory) / "LegacyRecovery-Info.plist"
            MODULE.prepare(source, result)
            with result.open("rb") as handle:
                info = plistlib.load(handle)
        self.assertEqual(info["CFBundleDisplayName"], "Shakescape Legacy Recovery")
        self.assertEqual(info["CFBundleURLTypes"], [])
        self.assertIs(info["HNSLegacyRecoveryBuild"], True)
        self.assertEqual(info["CFBundleIdentifier"], "$(PRODUCT_BUNDLE_IDENTIFIER)")
        self.assertEqual(info["CFBundleShortVersionString"], "1.0.7")

    def test_unexpected_source_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.plist"
            result = Path(directory) / "result.plist"
            with source.open("wb") as handle:
                plistlib.dump({"CFBundleDisplayName": "Other"}, handle)
            with self.assertRaisesRegex(ValueError, "display name"):
                MODULE.prepare(source, result)
            self.assertFalse(result.exists())


if __name__ == "__main__":
    unittest.main()
