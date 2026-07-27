#!/usr/bin/env python3
"""Fail when a localized Android resource is missing or changes format tokens."""

from __future__ import annotations

from collections import Counter
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "android" / "app" / "src" / "main" / "res"
FORMAT_TOKEN = re.compile(
    r"%(?:\d+\$)?(?:[-#+ 0,(<]*)?(?:\d+)?(?:\.\d+)?(?:[tT])?[a-zA-Z%]"
)
TRANSLATABLE_KINDS = {"string", "plurals", "string-array"}


def resource_text(element: ET.Element) -> str:
    return "".join(element.itertext())


TokenProfile = frozenset[tuple[str, int]]


def token_profiles(element: ET.Element) -> frozenset[TokenProfile]:
    candidates = list(element) if element.tag in {"plurals", "string-array"} else [element]
    return frozenset(
        frozenset(Counter(FORMAT_TOKEN.findall(resource_text(candidate))).items())
        for candidate in candidates
    )


def resources(
    path: Path, *, base: bool
) -> dict[tuple[str, str], frozenset[TokenProfile]]:
    root = ET.parse(path).getroot()
    found: dict[tuple[str, str], frozenset[TokenProfile]] = {}
    for element in root:
        if element.tag not in TRANSLATABLE_KINDS:
            continue
        name = element.get("name")
        if not name:
            raise ValueError(f"{path}: unnamed <{element.tag}> resource")
        if base and element.get("translatable") == "false":
            continue
        key = (element.tag, name)
        if key in found:
            raise ValueError(f"{path}: duplicate {element.tag} resource {name!r}")
        found[key] = token_profiles(element)
    return found


def verify() -> list[str]:
    base_path = RESOURCES / "values" / "strings.xml"
    base = resources(base_path, base=True)
    errors: list[str] = []
    locale_paths = sorted(RESOURCES.glob("values-*/strings.xml"))
    if not locale_paths:
        return ["no localized strings.xml files found"]

    for path in locale_paths:
        try:
            localized = resources(path, base=False)
        except (ET.ParseError, ValueError) as error:
            errors.append(str(error))
            continue

        for kind, name in sorted(base):
            key = (kind, name)
            if key not in localized:
                errors.append(f"{path}: missing <{kind} name={name!r}>")
                continue
            if not localized[key].issubset(base[key]):
                errors.append(
                    f"{path}: format-token profiles for {kind} {name!r} are "
                    f"{localized[key]}, expected profiles from {base[key]}"
                )

    return errors


def main() -> int:
    errors = verify()
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    locale_count = sum(1 for _ in RESOURCES.glob("values-*/strings.xml"))
    print(f"Android translation coverage and format tokens verified for {locale_count} locales")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
