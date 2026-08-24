from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from verify_cargo_git_policy import (  # noqa: E402
    ENGINE_VERSION,
    ENGINE_PATCH_VERSIONS,
    HNS_VERSION,
    ENGINE_REGISTRY,
    ROOT_REGISTRY_DEPENDENCIES,
    WALLET_VERSION,
    CargoSourcePolicyError,
    verify_repository,
)


class CargoSourcePolicyTests(unittest.TestCase):
    def test_release_versions_and_registry_cohort_are_explicit(self) -> None:
        self.assertEqual(HNS_VERSION, "0.3.1")
        self.assertEqual(ENGINE_VERSION, "0.2.2")
        self.assertEqual(ENGINE_PATCH_VERSIONS, {
            "hns-browser-gateway": "0.2.3",
            "hns-namespace-resolution": "0.2.3",
        })
        self.assertEqual(WALLET_VERSION, "0.1.1")
        self.assertIn("hns-wallet-mobile", ROOT_REGISTRY_DEPENDENCIES)
        self.assertIn("hns-browser-chain", ENGINE_REGISTRY)
        self.assertIn("hns-chain", ROOT_REGISTRY_DEPENDENCIES)
        self.assertEqual(ROOT_REGISTRY_DEPENDENCIES["hns-gateway"], "0.2.3")
        self.assertEqual(ROOT_REGISTRY_DEPENDENCIES["hns-namespace-resolution"], "0.2.3")

    def test_rejects_source_patch_in_minimal_fixture(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "rust/fuzz").mkdir(parents=True)
            (root / "tools/hns-header-snapshot-exporter").mkdir(parents=True)
            registry = "\n".join(f'{name} = "={version}"' for name, version in ROOT_REGISTRY_DEPENDENCIES.items())
            (root / "rust/Cargo.toml").write_text(
                f"[workspace.dependencies]\n{registry}\n[patch.crates-io]\nhns-covenants = {{ path = \"x\" }}\n",
                encoding="utf-8",
            )
            for path in ("rust/Cargo.lock", "rust/fuzz/Cargo.lock", "tools/hns-header-snapshot-exporter/Cargo.lock"):
                (root / path).write_text("version = 4\n", encoding="utf-8")
            with self.assertRaisesRegex(CargoSourcePolicyError, "source patches"):
                verify_repository(root, [Path("rust/Cargo.toml")])


if __name__ == "__main__":
    unittest.main()
