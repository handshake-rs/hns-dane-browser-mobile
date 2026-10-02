#!/usr/bin/env python3
"""Qualify unpublished wallet patches without replacing the registry lockfile."""

import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import tomllib

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("wallet_source", type=Path)
parser.add_argument("mode", choices=("check", "test", "clippy"), default="check", nargs="?")
args = parser.parse_args()
wallet_source = args.wallet_source.resolve(strict=True)
mobile_root = Path(__file__).resolve().parent.parent
lockfile = mobile_root / "rust/Cargo.lock"
original_lock = lockfile.read_bytes()
patches = []
for name in (wallet_source / "release/public-crates.txt").read_text().splitlines():
    name = name.strip()
    if not name or name.startswith("#") or name in ("hns-wallet-ethereum", "hns-wallet-testkit"):
        continue
    package_path = wallet_source / "crates" / name
    metadata = tomllib.loads((package_path / "Cargo.toml").read_text())
    if metadata["package"]["name"] != name:
        raise SystemExit(f"wallet package identity mismatch: {name}")
    expected = "0.4.2" if name in ("hns-wallet-hns", "hns-wallet-market") else "0.4.1"
    if metadata["package"]["version"] != expected:
        raise SystemExit(f"unexpected candidate version for {name}: expected {expected}")
    patches.append(f"{name} = {{ path = {json.dumps(str(package_path))} }}")

with tempfile.TemporaryDirectory(prefix="wallet-candidate-") as temporary:
    config = Path(temporary) / "patches.toml"
    config.write_text("[patch.crates-io]\n" + "\n".join(patches) + "\n")
    common = ["--manifest-path", "rust/Cargo.toml", "--workspace", "--all-targets", "--config", str(config)]
    cargo = ["cargo", "+1.98.1"]
    try:
        # Resolve candidate sources locally first; the saved registry lockfile
        # intentionally cannot name checksums for unpublished package versions.
        subprocess.run(cargo + ["check"] + common, cwd=mobile_root, check=True)
        if args.mode != "check":
            command = cargo + [args.mode] + common + ["--locked"]
            if args.mode == "clippy":
                command += ["--", "-D", "warnings"]
            subprocess.run(command, cwd=mobile_root, check=True)
    finally:
        lockfile.write_bytes(original_lock)
