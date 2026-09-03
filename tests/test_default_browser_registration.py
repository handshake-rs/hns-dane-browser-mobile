#!/usr/bin/env python3
import plistlib
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
IOS_INFO = ROOT / "ios/HnsDaneBrowser/Support/Info.plist"
IOS_PROJECT = ROOT / "ios/HnsDaneBrowser.xcodeproj/project.pbxproj"
ANDROID_MANIFEST = ROOT / "android/app/src/main/AndroidManifest.xml"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


class DefaultBrowserRegistrationTests(unittest.TestCase):
    def test_android_registers_unscoped_web_browser_intents(self) -> None:
        root = ET.parse(ANDROID_MANIFEST).getroot()
        launcher = next(
            activity
            for activity in root.findall("./application/activity")
            if activity.get(f"{ANDROID_NS}name") == ".ui.LauncherActivity"
        )
        filters = launcher.findall("intent-filter")
        web_filters = []
        for intent_filter in filters:
            actions = {
                action.get(f"{ANDROID_NS}name")
                for action in intent_filter.findall("action")
            }
            categories = {
                category.get(f"{ANDROID_NS}name")
                for category in intent_filter.findall("category")
            }
            schemes = {
                data.get(f"{ANDROID_NS}scheme")
                for data in intent_filter.findall("data")
            }
            if "android.intent.action.VIEW" in actions and {"http", "https"} <= schemes:
                web_filters.append((categories, schemes))
        self.assertEqual(len(web_filters), 1)
        self.assertTrue(
            {
                "android.intent.category.DEFAULT",
                "android.intent.category.BROWSABLE",
            }
            <= web_filters[0][0]
        )
        self.assertEqual(web_filters[0][1], {"http", "https"})
        self.assertTrue(
            any(
                "android.intent.action.MAIN"
                in {
                    action.get(f"{ANDROID_NS}name")
                    for action in intent_filter.findall("action")
                }
                and "android.intent.category.APP_BROWSER"
                in {
                    category.get(f"{ANDROID_NS}name")
                    for category in intent_filter.findall("category")
                }
                for intent_filter in filters
            )
        )

    def test_ios_default_browser_activation_stays_dormant_until_approved(self) -> None:
        with IOS_INFO.open("rb") as source:
            info = plistlib.load(source)
        registered_schemes = {
            scheme
            for url_type in info.get("CFBundleURLTypes", [])
            for scheme in url_type.get("CFBundleURLSchemes", [])
        }
        self.assertIn("handshake", registered_schemes)
        self.assertFalse({"http", "https"} & registered_schemes)

        project = IOS_PROJECT.read_text(encoding="utf-8")
        self.assertNotIn("HnsDaneBrowser.entitlements", project)
        self.assertFalse(
            (ROOT / "ios/HnsDaneBrowser/Support/HnsDaneBrowser.entitlements").exists()
        )


if __name__ == "__main__":
    unittest.main()
