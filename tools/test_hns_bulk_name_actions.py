import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("hns_bulk_name_actions.py")
SPEC = importlib.util.spec_from_file_location("hns_bulk_name_actions", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class BulkNameActionsTest(unittest.TestCase):
    def test_reads_exact_line_and_json_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "names.txt"
            path.write_text("24hour\nsecond_name\n", encoding="utf-8")
            self.assertEqual(MODULE.read_names(path), ["24hour", "second_name"])
            path.write_text('["24hour","second-name"]', encoding="utf-8")
            self.assertEqual(MODULE.read_names(path), ["24hour", "second-name"])

    def test_rejects_duplicates_and_noncanonical_text(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "names.txt"
            path.write_text("24hour\n24hour\n", encoding="utf-8")
            with self.assertRaises(MODULE.ToolError):
                MODULE.read_names(path)
            path.write_text(" 24hour\n", encoding="utf-8")
            with self.assertRaises(MODULE.ToolError):
                MODULE.read_names(path)

    def test_checkpoint_is_private_and_round_trips(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "checkpoint.json"
            value = {"schemaVersion": 1, "nextIndex": 2}
            MODULE.atomic_checkpoint(path, value)
            self.assertEqual(MODULE.load_checkpoint(path), value)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_fee_parser_is_exact(self):
        self.assertEqual(MODULE.parse_hns("0.05"), 50_000)
        with self.assertRaises(MODULE.ToolError):
            MODULE.parse_hns("0.0000001")

    def test_plan_initializes_resumable_checkpoint(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            names = root / "names.json"
            checkpoint = root / "progress.json"
            names.write_text(json.dumps(["24hour", "another"]), encoding="utf-8")
            result = MODULE.run([
                "transfer", "--names", str(names), "--checkpoint", str(checkpoint),
                "--recipient", "hs1ql5j48gj6jhwn38r075z9qq05n7f6td4uh6dd2f",
                "--max-fee-hns", "0.05",
            ])
            self.assertEqual(result, 0)
            saved = MODULE.load_checkpoint(checkpoint)
            self.assertEqual(saved["nextIndex"], 0)
            self.assertEqual(saved["status"], "ready")


if __name__ == "__main__":
    unittest.main()
