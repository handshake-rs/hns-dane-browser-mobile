#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from verify_cargo_git_policy import (  # noqa: E402
    ALLOWED_ENGINE_PACKAGES,
    CargoGitPolicyError,
    ENGINE_GIT_URL,
    ENGINE_LOCK_SOURCE,
    ENGINE_REVISION,
    verify_repository,
)


class CargoGitPolicyTests(unittest.TestCase):
    def create_fixture(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        (root / "rust/fuzz").mkdir(parents=True)
        (root / "tools/hns-header-snapshot-exporter").mkdir(parents=True)

        dependencies = "\n".join(
            f'{package} = {{ git = "{ENGINE_GIT_URL}", '
            f'rev = "{ENGINE_REVISION}" }}'
            for package in sorted(ALLOWED_ENGINE_PACKAGES)
        )
        (root / "rust/Cargo.toml").write_text(
            f"[workspace.dependencies]\n{dependencies}\n",
            encoding="utf-8",
        )
        locked_packages = "\n".join(
            "[[package]]\n"
            f'name = "{package}"\n'
            'version = "0.1.0"\n'
            f'source = "{ENGINE_LOCK_SOURCE}"\n'
            for package in sorted(ALLOWED_ENGINE_PACKAGES)
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
            'version = "0.1.0"\n'
            f'source = "{ENGINE_LOCK_SOURCE}"\n',
            encoding="utf-8",
        )
        return temporary, root

    def verify_fixture(self, root: Path) -> None:
        verify_repository(root, [Path("rust/Cargo.toml")])

    def test_accepts_only_the_exact_engine_packages_and_revision(self) -> None:
        temporary, root = self.create_fixture()
        with temporary:
            self.verify_fixture(root)

    def test_rejects_unpinned_manifest_dependency(self) -> None:
        temporary, root = self.create_fixture()
        with temporary:
            manifest = root / "rust/Cargo.toml"
            manifest.write_text(
                manifest.read_text(encoding="utf-8").replace(
                    f', rev = "{ENGINE_REVISION}"', "", 1
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                CargoGitPolicyError, "expected exact Git revision"
            ):
                self.verify_fixture(root)

    def test_rejects_noncanonical_manifest_url(self) -> None:
        temporary, root = self.create_fixture()
        with temporary:
            manifest = root / "rust/Cargo.toml"
            manifest.write_text(
                manifest.read_text(encoding="utf-8").replace(
                    ENGINE_GIT_URL,
                    "https://example.invalid/hns-dane-engine.git",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                CargoGitPolicyError, "expected canonical Git URL"
            ):
                self.verify_fixture(root)

    def test_rejects_wrong_locked_revision(self) -> None:
        temporary, root = self.create_fixture()
        with temporary:
            lockfile = root / "rust/Cargo.lock"
            lockfile.write_text(
                lockfile.read_text(encoding="utf-8").replace(
                    ENGINE_REVISION, "0" * 40, 1
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CargoGitPolicyError, "must lock to"):
                self.verify_fixture(root)

    def test_rejects_wrong_transitive_tool_revision(self) -> None:
        temporary, root = self.create_fixture()
        with temporary:
            lockfile = root / "tools/hns-header-snapshot-exporter/Cargo.lock"
            lockfile.write_text(
                lockfile.read_text(encoding="utf-8").replace(
                    ENGINE_REVISION, "0" * 40, 1
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CargoGitPolicyError, "must lock to"):
                self.verify_fixture(root)

    def test_rejects_other_package_from_the_allowed_repository(self) -> None:
        temporary, root = self.create_fixture()
        with temporary:
            lockfile = root / "rust/Cargo.lock"
            lockfile.write_text(
                lockfile.read_text(encoding="utf-8")
                + "\n[[package]]\n"
                + 'name = "unreviewed-engine-crate"\n'
                + 'version = "0.1.0"\n'
                + f'source = "{ENGINE_LOCK_SOURCE}"\n',
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CargoGitPolicyError, "is not allowed"):
                self.verify_fixture(root)


if __name__ == "__main__":
    unittest.main()
