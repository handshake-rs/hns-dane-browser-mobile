#!/usr/bin/env python3
"""Enforce the narrow, revision-pinned Cargo Git dependency exception."""

from __future__ import annotations

from collections.abc import Iterator, Mapping
from pathlib import Path
import subprocess
import sys
import tomllib
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
ENGINE_GIT_URL = "https://github.com/handshake-rs/hns-dane-engine.git"
ENGINE_REVISION = "fe38e805ba9d8ba26d486c5c7aa67c87c8cf9159"
ENGINE_LOCK_SOURCE = (
    f"git+{ENGINE_GIT_URL}?rev={ENGINE_REVISION}#{ENGINE_REVISION}"
)
ALLOWED_ENGINE_PACKAGES = frozenset(
    {
        "hns-browser-observability",
        "hns-browser-runtime",
        "hns-icann-dane",
        "hns-namespace-resolution",
        "hns-resolution-policy",
    }
)
ROOT_MANIFEST = Path("rust/Cargo.toml")
LOCKFILES = (
    Path("rust/Cargo.lock"),
    Path("rust/fuzz/Cargo.lock"),
    Path("tools/hns-header-snapshot-exporter/Cargo.lock"),
)


class CargoGitPolicyError(RuntimeError):
    """A Cargo manifest or lockfile violates the Git source policy."""


def tracked_cargo_manifests(root: Path) -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=root,
        check=True,
        capture_output=True,
    )
    return sorted(
        Path(raw.decode())
        for raw in result.stdout.split(b"\0")
        if raw and Path(raw.decode()).name == "Cargo.toml"
    )


def git_specs(
    value: Any, path: tuple[str, ...] = ()
) -> Iterator[tuple[tuple[str, ...], Mapping[str, Any]]]:
    if isinstance(value, Mapping):
        if "git" in value:
            yield path, value
        for key, child in value.items():
            yield from git_specs(child, (*path, str(key)))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from git_specs(child, (*path, str(index)))


def load_toml(path: Path) -> dict[str, Any]:
    with path.open("rb") as handle:
        return tomllib.load(handle)


def validate_manifests(root: Path, manifests: list[Path]) -> None:
    declarations: dict[str, int] = {
        package: 0 for package in ALLOWED_ENGINE_PACKAGES
    }

    for relative_path in manifests:
        document = load_toml(root / relative_path)
        for location, specification in git_specs(document):
            dependency = location[-1] if location else "<unknown>"
            rendered_location = ".".join(location) or "<document root>"
            if dependency not in ALLOWED_ENGINE_PACKAGES:
                raise CargoGitPolicyError(
                    f"{relative_path}:{rendered_location}: Cargo Git dependency "
                    f"{dependency!r} is not allowed"
                )
            if relative_path != ROOT_MANIFEST or location != (
                "workspace",
                "dependencies",
                dependency,
            ):
                raise CargoGitPolicyError(
                    f"{relative_path}:{rendered_location}: allowed engine Git "
                    "dependencies must be declared once in "
                    "rust/Cargo.toml [workspace.dependencies]"
                )
            if specification.get("git") != ENGINE_GIT_URL:
                raise CargoGitPolicyError(
                    f"{relative_path}:{rendered_location}: expected canonical "
                    f"Git URL {ENGINE_GIT_URL!r}"
                )
            if specification.get("rev") != ENGINE_REVISION:
                raise CargoGitPolicyError(
                    f"{relative_path}:{rendered_location}: expected exact Git "
                    f"revision {ENGINE_REVISION}"
                )
            if "branch" in specification or "tag" in specification:
                raise CargoGitPolicyError(
                    f"{relative_path}:{rendered_location}: branch and tag Git "
                    "selectors are not allowed"
                )
            package_override = specification.get("package")
            if package_override not in (None, dependency):
                raise CargoGitPolicyError(
                    f"{relative_path}:{rendered_location}: dependency renaming "
                    "is not allowed for the engine Git exception"
                )
            declarations[dependency] += 1

    for dependency, count in sorted(declarations.items()):
        if count != 1:
            raise CargoGitPolicyError(
                f"{ROOT_MANIFEST}: expected exactly one pinned declaration for "
                f"{dependency}, found {count}"
            )


def validate_lockfiles(root: Path) -> None:
    root_packages: dict[str, int] = {
        package: 0 for package in ALLOWED_ENGINE_PACKAGES
    }

    for relative_path in LOCKFILES:
        document = load_toml(root / relative_path)
        for package in document.get("package", []):
            source = package.get("source")
            if not isinstance(source, str) or not source.startswith("git+"):
                continue
            name = package.get("name", "<unknown>")
            if name not in ALLOWED_ENGINE_PACKAGES:
                raise CargoGitPolicyError(
                    f"{relative_path}: locked Cargo Git package {name!r} is "
                    "not allowed"
                )
            if source != ENGINE_LOCK_SOURCE:
                raise CargoGitPolicyError(
                    f"{relative_path}: {name} must lock to "
                    f"{ENGINE_LOCK_SOURCE!r}, found {source!r}"
                )
            # Standalone tools use their own lockfiles while consuming local
            # workspace crates. If one of those crates reaches the shared
            # engine transitively, Cargo must record the same exact canonical
            # package and revision there too. Only the root lock is required
            # to contain all reviewed packages; every lock remains constrained
            # to the same five names and immutable source above.
            if relative_path == Path("rust/Cargo.lock"):
                root_packages[name] += 1

    for package, count in sorted(root_packages.items()):
        if count != 1:
            raise CargoGitPolicyError(
                "rust/Cargo.lock: expected exactly one locked package for "
                f"{package}, found {count}"
            )


def verify_repository(
    root: Path = ROOT, manifests: list[Path] | None = None
) -> None:
    validate_manifests(
        root,
        tracked_cargo_manifests(root) if manifests is None else manifests,
    )
    validate_lockfiles(root)


def main() -> int:
    try:
        verify_repository()
    except (
        CargoGitPolicyError,
        OSError,
        subprocess.CalledProcessError,
        tomllib.TOMLDecodeError,
    ) as error:
        print(f"Cargo Git dependency policy failed: {error}", file=sys.stderr)
        return 1
    print(
        "Cargo Git dependency policy permits only the five canonical "
        f"hns-dane-engine packages at {ENGINE_REVISION}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
