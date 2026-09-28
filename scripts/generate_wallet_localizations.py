#!/usr/bin/env python3
"""Generate controlled wallet locale resources and the Apple string catalog."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    "critical": ROOT / "localization" / "wallet-critical.json",
    "operations": ROOT / "localization" / "wallet-operations.json",
}
ANDROID_RES = ROOT / "android" / "app" / "src" / "main" / "res"
ANDROID_BASE = ANDROID_RES / "values" / "strings.xml"
APPLE_CATALOG = (
    ROOT / "ios" / "HnsDaneBrowser" / "Support" / "Wallet.xcstrings"
)
APPLE_WALLET_SOURCES = ROOT / "ios" / "HnsDaneBrowser" / "Wallet"
ANDROID_TO_APPLE_LOCALE = {
    "ar": "ar",
    "b+he": "he",
    "b+id": "id",
    "b+zh+Hans": "zh-Hans",
    "b+zh+Hant": "zh-Hant",
    "de": "de",
    "es": "es",
    "fa": "fa",
    "fr": "fr",
    "hi": "hi",
    "it": "it",
    "ja": "ja",
    "ko": "ko",
    "nl": "nl",
    "pl": "pl",
    "pt": "pt",
    "ru": "ru",
    "tr": "tr",
    "uk": "uk",
    "vi": "vi",
}
ANDROID_FORMAT = re.compile(r"%(\d+)\$s")
APPLE_COPY_CALL = re.compile(r'WalletCopy\.(?:text|format)\(\s*"([^"]+)"')


def resource_text(element: ET.Element) -> str:
    return "".join(element.itertext())


def android_base_strings() -> dict[str, ET.Element]:
    root = ET.parse(ANDROID_BASE).getroot()
    return {
        element.attrib["name"]: element
        for element in root
        if element.tag == "string" and element.attrib.get("name")
    }


def load_source(source: Path) -> tuple[list[str], dict[str, dict[str, str]]]:
    document = json.loads(source.read_text(encoding="utf-8"))
    if document.get("schema") != 1:
        raise ValueError(f"{source.name}: wallet localization schema must be 1")
    keys = document.get("keys")
    translation_rows = document.get("translations")
    if not isinstance(keys, list) or not keys or len(keys) != len(set(keys)):
        raise ValueError(
            f"{source.name}: wallet localization keys must be a unique non-empty list"
        )
    if set(translation_rows or {}) != set(ANDROID_TO_APPLE_LOCALE):
        raise ValueError(
            f"{source.name}: translation locales do not match the supported locale set"
        )
    translations: dict[str, dict[str, str]] = {}
    for locale, row in translation_rows.items():
        if not isinstance(row, dict) or set(row) != set(keys):
            missing = sorted(set(keys) - set(row if isinstance(row, dict) else ()))
            extra = sorted(set(row if isinstance(row, dict) else ()) - set(keys))
            raise ValueError(
                f"{source.name}/{locale}: translation keys differ; "
                f"missing={missing}, extra={extra}"
            )
        if any(not isinstance(row[key], str) or not row[key] for key in keys):
            raise ValueError(
                f"{source.name}/{locale}: every localized value must be non-empty text"
            )
        translations[locale] = {key: row[key] for key in keys}
    return keys, translations


def validate_source(
    keys: list[str], translations: dict[str, dict[str, str]]
) -> dict[str, str]:
    base = android_base_strings()
    missing = [key for key in keys if key not in base]
    if missing:
        raise ValueError(f"canonical Android strings are missing: {missing}")
    still_blocked = [
        key for key in keys if base[key].attrib.get("translatable") == "false"
    ]
    if still_blocked:
        raise ValueError(
            f"controlled wallet strings are still non-translatable: {still_blocked}"
        )
    english = {key: resource_text(base[key]).replace(r"\n", "\n") for key in keys}
    token = re.compile(r"%(?:\d+\$)?[a-zA-Z%]")
    for locale, localized in translations.items():
        for key in keys:
            if sorted(token.findall(localized[key])) != sorted(token.findall(english[key])):
                raise ValueError(f"{locale}/{key}: format tokens differ from canonical English")
            expected_line_breaks = english[key].count("\n")
            if localized[key].count("\n") != expected_line_breaks:
                raise ValueError(
                    f"{locale}/{key}: expected {expected_line_breaks} line breaks"
                )
            if r"\n" in localized[key]:
                raise ValueError(
                    f"{locale}/{key}: translations must use JSON line breaks, not Android escapes"
                )
    return english


def validate_apple_usage(keys: list[str]) -> None:
    source = "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted(APPLE_WALLET_SOURCES.glob("*.swift"))
    )
    unknown = sorted(set(APPLE_COPY_CALL.findall(source)) - set(keys))
    if unknown:
        raise ValueError(f"Apple wallet source uses unknown localization keys: {unknown}")


def android_xml(keys: list[str], localized: dict[str, str]) -> str:
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- Generated by scripts/generate_wallet_localizations.py. -->",
        "<resources>",
    ]
    for key in keys:
        element = ET.Element("string", {"name": key})
        # Android parses a second layer of resource-string escapes after XML.
        # Protect translation punctuation before encoding actual line breaks.
        element.text = (
            localized[key]
            .replace("\\", r"\\")
            .replace("'", r"\'")
            .replace('"', r'\"')
            .replace("\n", r"\n")
        )
        encoded = ET.tostring(element, encoding="unicode", short_empty_elements=False)
        lines.append(f"    {encoded}")
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def apple_format(value: str) -> str:
    return ANDROID_FORMAT.sub(lambda match: f"%{match.group(1)}$@", value)


def apple_catalog(
    keys: list[str],
    english: dict[str, str],
    translations: dict[str, dict[str, str]],
) -> str:
    strings: dict[str, object] = {}
    for key in keys:
        localizations = {
            "en": {"stringUnit": {"state": "translated", "value": apple_format(english[key])}}
        }
        for android_locale, apple_locale in ANDROID_TO_APPLE_LOCALE.items():
            localizations[apple_locale] = {
                "stringUnit": {
                    "state": "translated",
                    "value": apple_format(translations[android_locale][key]),
                }
            }
        strings[key] = {
            "extractionState": "manual",
            "localizations": localizations,
        }
    document = {
        "sourceLanguage": "en",
        "strings": strings,
        "version": "1.0",
    }
    return json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def outputs() -> dict[Path, str]:
    generated: dict[Path, str] = {}
    all_keys: list[str] = []
    all_english: dict[str, str] = {}
    all_translations = {locale: {} for locale in ANDROID_TO_APPLE_LOCALE}
    for cohort, source in SOURCES.items():
        keys, translations = load_source(source)
        overlap = sorted(set(all_keys) & set(keys))
        if overlap:
            raise ValueError(f"{source.name}: duplicate keys across cohorts: {overlap}")
        english = validate_source(keys, translations)
        all_keys.extend(keys)
        all_english.update(english)
        for locale in ANDROID_TO_APPLE_LOCALE:
            all_translations[locale].update(translations[locale])
            generated[
                ANDROID_RES / f"values-{locale}" / f"wallet_{cohort}.xml"
            ] = android_xml(keys, translations[locale])
    validate_apple_usage(all_keys)
    generated[APPLE_CATALOG] = apple_catalog(
        all_keys, all_english, all_translations
    )
    return generated


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        generated = outputs()
    except (ET.ParseError, OSError, ValueError, json.JSONDecodeError) as error:
        print(error, file=sys.stderr)
        return 1
    stale: list[Path] = []
    for path, content in generated.items():
        if args.check:
            if not path.exists() or path.read_text(encoding="utf-8") != content:
                stale.append(path.relative_to(ROOT))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
    if stale:
        print("stale generated wallet localization files:", file=sys.stderr)
        print("\n".join(map(str, stale)), file=sys.stderr)
        return 1
    action = "verified" if args.check else "generated"
    print(
        f"Wallet localizations {action} for "
        f"{len(ANDROID_TO_APPLE_LOCALE)} locales across {len(SOURCES)} cohorts"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
