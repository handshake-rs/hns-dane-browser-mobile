from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from verify_cargo_git_policy import (  # noqa: E402
    CRATES_IO_SOURCE,
    ENGINE_PACKAGES,
    ENGINE_REQUIREMENT,
    ENGINE_VERSION,
    CargoSourcePolicyError,
    verify_repository,
)


class CargoSourcePolicyTests(unittest.TestCase):
    def test_qualified_engine_package_set_is_explicit(self) -> None:
        self.assertEqual(
            ENGINE_PACKAGES,
            {
                "hns-browser-observability",
                "hns-browser-runtime",
                "hns-icann-dane",
                "hns-namespace-resolution",
                "hns-resolution-policy",
            },
        )

    def create_fixture(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        (root / "rust/fuzz").mkdir(parents=True)
        (root / "tools/hns-header-snapshot-exporter").mkdir(parents=True)

        dependencies = "\n".join(
            f'{package} = "{ENGINE_REQUIREMENT}"'
            for package in sorted(ENGINE_PACKAGES)
        )
        (root / "rust/Cargo.toml").write_text(
            f"[workspace.dependencies]\n{dependencies}\n",
            encoding="utf-8",
        )
        locked_packages = "\n".join(
            "[[package]]\n"
            f'name = "{package}"\n'
            f'version = "{ENGINE_VERSION}"\n'
            f'source = "{CRATES_IO_SOURCE}"\n'
            f'checksum = "{"a" * 64}"\n'
            for package in sorted(ENGINE_PACKAGES)
        )
        (root / "rust/Cargo.lock").write_text(
            f"version = 4\n\n{locked_packages}",
            encoding="utf-8",
        )
        (root / "rust/fuzz/Cargo.lock").write_text(
            "version = 4\n", encoding="utf-8"
        )
        (root / "tools/hns-header-snapshot-exporter/Cargo.lock").write_text(
            "version = 4\n\n"
            "[[package]]\n"
            'name = "hns-namespace-resolution"\n'
            f'version = "{ENGINE_VERSION}"\n'
            f'source = "{CRATES_IO_SOURCE}"\n'
            f'checksum = "{"b" * 64}"\n',
            encoding="utf-8",
        )
        return temporary, root

    def verify_fixture(self, root: Path) -> None:
        verify_repository(root, [Path("rust/Cargo.toml")])

    def test_accepts_exact_registry_engine_packages(self) -> None:
        temporary, root = self.create_fixture()
        with temporary:
            self.verify_fixture(root)

    def test_rejects_git_manifest_dependency(self) -> None:
        temporary, root = self.create_fixture()
        with temporary:
            manifest = root / "rust/Cargo.toml"
            manifest.write_text(
                manifest.read_text(encoding="utf-8").replace(
                    f'"{ENGINE_REQUIREMENT}"',
                    '{ git = "https://example.invalid/engine.git" }',
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                CargoSourcePolicyError, "Git dependencies are not allowed"
            ):
                self.verify_fixture(root)

    def test_rejects_moving_engine_requirement(self) -> None:
        temporary, root = self.create_fixture()
        with temporary:
            manifest = root / "rust/Cargo.toml"
            manifest.write_text(
                manifest.read_text(encoding="utf-8").replace(
                    ENGINE_REQUIREMENT, ENGINE_VERSION, 1
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CargoSourcePolicyError, "must be pinned"):
                self.verify_fixture(root)

    def test_rejects_wrong_locked_version(self) -> None:
        temporary, root = self.create_fixture()
        with temporary:
            lockfile = root / "rust/Cargo.lock"
            lockfile.write_text(
                lockfile.read_text(encoding="utf-8").replace(
                    f'version = "{ENGINE_VERSION}"',
                    'version = "0.1.1"',
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CargoSourcePolicyError, "must lock to"):
                self.verify_fixture(root)

    def test_rejects_non_registry_engine_source(self) -> None:
        temporary, root = self.create_fixture()
        with temporary:
            lockfile = root / "rust/Cargo.lock"
            lockfile.write_text(
                lockfile.read_text(encoding="utf-8").replace(
                    CRATES_IO_SOURCE,
                    "registry+https://example.invalid/index",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CargoSourcePolicyError, "crates.io"):
                self.verify_fixture(root)

    def test_rejects_any_locked_git_package(self) -> None:
        temporary, root = self.create_fixture()
        with temporary:
            lockfile = root / "rust/fuzz/Cargo.lock"
            lockfile.write_text(
                lockfile.read_text(encoding="utf-8")
                + "\n[[package]]\n"
                + 'name = "unreviewed-git-crate"\n'
                + 'version = "1.0.0"\n'
                + 'source = "git+https://example.invalid/crate#deadbeef"\n',
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CargoSourcePolicyError, "not allowed"):
                self.verify_fixture(root)


if __name__ == "__main__":
    unittest.main()
