from pathlib import Path
import shutil
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from verify_wallet_source import CHECKSUM_MANIFEST, LOCKFILES, WALLET_SOURCE, verify_repository


class WalletSourceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        for path in (*LOCKFILES, CHECKSUM_MANIFEST, Path("rust/Cargo.toml")):
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

    def test_published_graph_passes(self):
        self.verify()

    def test_changed_manifest_version_fails(self):
        self.edit("rust/Cargo.toml", 'hns-wallet-hns = "=0.4.2"', 'hns-wallet-hns = "=0.4.1"')
        with self.assertRaisesRegex(ValueError, "exact published version"):
            self.verify()

    def test_floating_manifest_version_fails(self):
        self.edit("rust/Cargo.toml", 'hns-wallet-hns = "=0.4.2"', 'hns-wallet-hns = "0.4.2"')
        with self.assertRaisesRegex(ValueError, "exact published version"):
            self.verify()

    def test_local_wallet_override_fails(self):
        path = self.root / "rust/Cargo.toml"
        with path.open("a") as handle:
            handle.write('\n[patch.crates-io]\nhns-wallet-hns = { path = "../../hns-wallet-rs/crates/hns-wallet-hns" }\n')
        with self.assertRaisesRegex(ValueError, "source overrides"):
            self.verify()

    def test_git_wallet_override_fails(self):
        path = self.root / "rust/Cargo.toml"
        with path.open("a") as handle:
            handle.write('\n[patch.crates-io]\nhns-wallet-hns = { git = "https://github.com/handshake-rs/hns-wallet-rs.git", branch = "main" }\n')
        with self.assertRaisesRegex(ValueError, "source overrides"):
            self.verify()

    def test_member_wallet_path_alias_fails(self):
        path = Path("rust/crates/example/Cargo.toml")
        (self.root / path).parent.mkdir(parents=True)
        (self.root / path).write_text('[dependencies]\nwallet = { package = "hns-wallet-hns", version = "=0.4.2", path = "../../wallet" }\n')
        with self.assertRaisesRegex(ValueError, "local wallet paths"):
            verify_repository(self.root, [Path("rust/Cargo.toml"), path])

    def test_git_locked_wallet_source_fails(self):
        self.edit("rust/Cargo.lock", 'name = "hns-wallet-hns"\nversion = "0.4.2"\nsource = "' + WALLET_SOURCE + '"',
                  'name = "hns-wallet-hns"\nversion = "0.4.2"\nsource = "git+https://example.invalid/wallet#deadbeef"')
        with self.assertRaisesRegex(ValueError, "published wallet"):
            self.verify()

    def test_changed_locked_version_fails(self):
        self.edit("rust/Cargo.lock", 'name = "hns-wallet-hns"\nversion = "0.4.2"', 'name = "hns-wallet-hns"\nversion = "0.4.1"')
        with self.assertRaisesRegex(ValueError, "published wallet"):
            self.verify()

    def test_changed_locked_checksum_fails(self):
        checksum = next(line.split()[0] for line in (self.root / CHECKSUM_MANIFEST).read_text().splitlines()
                        if line.endswith("hns-wallet-hns-0.4.2.crate"))
        self.edit("rust/Cargo.lock", f'checksum = "{checksum}"', 'checksum = "' + "0" * 64 + '"')
        with self.assertRaisesRegex(ValueError, "checksum"):
            self.verify()

    def test_incomplete_checksum_manifest_fails(self):
        line = next(line for line in (self.root / CHECKSUM_MANIFEST).read_text().splitlines()
                    if line.endswith("hns-wallet-hns-0.4.2.crate"))
        self.edit(CHECKSUM_MANIFEST, line + "\n", "")
        with self.assertRaisesRegex(ValueError, "missing a reviewed"):
            self.verify()

    def test_unrelated_locked_git_dependency_fails(self):
        path = self.root / "rust/fuzz/Cargo.lock"
        with path.open("a") as handle:
            handle.write('\n[[package]]\nname = "unreviewed"\nversion = "1.0.0"\nsource = "git+https://example.invalid/source#deadbeef"\n')
        with self.assertRaisesRegex(ValueError, "unreviewed Git"):
            self.verify()

    def test_unrelated_manifest_git_dependency_fails(self):
        self.edit("rust/Cargo.toml", 'hex = "0.4"', 'hex = { git = "https://example.invalid/source", branch = "main" }')
        with self.assertRaisesRegex(ValueError, "unreviewed Git"):
            self.verify()

    def test_missing_locked_package_fails(self):
        self.edit("rust/Cargo.lock", 'name = "hns-wallet-hns"', 'name = "missing-wallet"')
        with self.assertRaises(ValueError):
            self.verify()


if __name__ == "__main__":
    unittest.main()
