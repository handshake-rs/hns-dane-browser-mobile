"""Exercise the release helper's final upload boundary without Apple tools."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "scripts/upload-ios-app-store.sh"


class ArchiveOnlyTests(unittest.TestCase):
    def run_upload_boundary(self, archive_only, source_is_current=True):
        source = HELPER.read_text()
        boundary = source[source.index('\nif [[ "$ARCHIVE_ONLY" == true ]]; then\n'):]
        with tempfile.TemporaryDirectory() as temporary:
            marker = Path(temporary) / "upload-attempted"
            prefix = (
                "set -eu\n"
                f"verify_exact_current_main() {{ return {0 if source_is_current else 1}; }}\n"
                'xcodebuild() { touch "$UPLOAD_MARKER"; }\n'
                'authentication_args=()\n'
                'archive_path=archive; export_path=export; upload_export_options=options\n'
                'version=1.0.14; build=77\n'
            )
            environment = dict(os.environ, ARCHIVE_ONLY=archive_only,
                               IPA_OUTPUT_PATH="candidate.ipa", UPLOAD_MARKER=str(marker))
            result = subprocess.run(["bash", "-c", prefix + boundary], env=environment,
                                    capture_output=True, text=True)
            return result, marker.exists()

    def test_archive_only_retains_ipa_without_calling_store_upload(self):
        result, uploaded = self.run_upload_boundary("true")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(uploaded)
        self.assertIn("Prepared signed Shakescape", result.stdout)
        self.assertNotIn("Uploaded Shakescape", result.stdout)

    def test_existing_upload_mode_still_calls_store_upload(self):
        result, uploaded = self.run_upload_boundary("false")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(uploaded)

    def test_stale_source_never_reaches_upload_in_either_mode(self):
        for mode in ("true", "false"):
            with self.subTest(mode=mode):
                result, uploaded = self.run_upload_boundary(mode, source_is_current=False)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(uploaded)

    def test_invalid_mode_and_missing_output_fail_before_signing(self):
        for mode, message in (("invalid", "must be true or false"),
                              ("true", "requires HNS_IOS_IPA_OUTPUT_PATH")):
            with self.subTest(mode=mode):
                environment = dict(os.environ, HNS_IOS_ARCHIVE_ONLY=mode,
                                   HNS_IOS_IPA_OUTPUT_PATH="")
                result = subprocess.run(["bash", str(HELPER)], env=environment,
                                        capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)


if __name__ == "__main__":
    unittest.main()
