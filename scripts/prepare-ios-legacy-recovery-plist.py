#!/usr/bin/env python3
"""Derive the isolated iOS recovery Info.plist from the regular app's plist."""

from __future__ import annotations

import argparse
from pathlib import Path
import plistlib


RECOVERY_DISPLAY_NAME = "Shakescape Legacy Recovery"


def prepare(source: Path, destination: Path) -> None:
    with source.open("rb") as handle:
        info = plistlib.load(handle)
    if info.get("CFBundleDisplayName") != "Shakescape":
        raise ValueError("unexpected source app display name")
    schemes = {
        scheme
        for entry in info.get("CFBundleURLTypes", [])
        for scheme in entry.get("CFBundleURLSchemes", [])
    }
    if not {"http", "https", "handshake"}.issubset(schemes):
        raise ValueError("unexpected source URL handlers")
    if info.get("CFBundleIdentifier") != "$(PRODUCT_BUNDLE_IDENTIFIER)":
        raise ValueError("source bundle ID must follow the Xcode build setting")

    info["CFBundleDisplayName"] = RECOVERY_DISPLAY_NAME
    info["CFBundleURLTypes"] = []
    info["HNSLegacyRecoveryBuild"] = True
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("wb") as handle:
        plistlib.dump(info, handle, sort_keys=False)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    prepare(args.source, args.destination)


if __name__ == "__main__":
    main()
