from pathlib import Path
import shutil
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from verify_wallet_source import LOCKFILES, WALLET_REVISION, verify_repository


class WalletSourceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        for path in (*LOCKFILES, Path("rust/Cargo.toml")):
            (self.root / path).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / path, self.root / path)

    def tearDown(self):
        self.temporary.cleanup()

    def verify(self):
        verify_repository(self.root, [Path("rust/Cargo.toml")])

    def edit(self, path, old, new):
        source = self.root / path
        self.assertIn(old, source.read_text())
        source.write_text(source.read_text().replace(old, new, 1))

    def test_reviewed_graph_passes(self):
        self.verify()

    def test_changed_manifest_revision_fails(self):
        self.edit("rust/Cargo.toml", WALLET_REVISION, "a" * 40)
        with self.assertRaisesRegex(ValueError, "exact reviewed"):
            self.verify()

    def test_floating_manifest_revision_fails(self):
        self.edit("rust/Cargo.toml", f'rev = "{WALLET_REVISION}"', 'branch = "main"')
        with self.assertRaisesRegex(ValueError, "exact reviewed"):
            self.verify()

    def test_local_wallet_override_fails(self):
        self.edit("rust/Cargo.toml", f'git = "https://github.com/handshake-rs/hns-wallet-rs.git", rev = "{WALLET_REVISION}"', 'path = "../../hns-wallet-rs"')
        with self.assertRaisesRegex(ValueError, "exact reviewed"):
            self.verify()

    def test_mixed_locked_revision_fails(self):
        self.edit("rust/Cargo.lock", WALLET_REVISION, "a" * 40)
        with self.assertRaisesRegex(ValueError, "reviewed wallet source"):
            self.verify()

    def test_changed_locked_version_fails(self):
        self.edit("rust/Cargo.lock", 'name = "hns-wallet-hns"\nversion = "0.4.2"', 'name = "hns-wallet-hns"\nversion = "0.4.1"')
        with self.assertRaisesRegex(ValueError, "reviewed wallet source"):
            self.verify()

    def test_unrelated_locked_git_dependency_fails(self):
        path = self.root / "rust/fuzz/Cargo.lock"
        with path.open("a") as handle:
            handle.write('\n[[package]]\nname = "unreviewed"\nversion = "1.0.0"\nsource = "git+https://example.invalid/source#deadbeef"\n')
        with self.assertRaisesRegex(ValueError, "unreviewed Git"):
            self.verify()

    def test_additional_patch_git_dependency_fails(self):
        path = self.root / "rust/Cargo.toml"
        with path.open("a") as handle:
            handle.write('\n[patch."https://example.invalid/registry"]\nunreviewed = { git = "https://example.invalid/source", branch = "main" }\n')
        with self.assertRaisesRegex(ValueError, "unreviewed Git"):
            self.verify()

    def test_missing_locked_package_fails(self):
        self.edit("rust/Cargo.lock", 'name = "hns-wallet-hns"', 'name = "missing-wallet"')
        with self.assertRaises(ValueError):
            self.verify()


if __name__ == "__main__":
    unittest.main()
