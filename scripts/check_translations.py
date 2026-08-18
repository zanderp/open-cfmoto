#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Compare each values-*/strings.xml against English values/strings.xml.

Android falls back to English for missing keys, so English is the source of
truth. Extra keys in a locale are orphans, not missing English strings.
"""

from __future__ import annotations

import argparse
import os
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class LocaleKeys:
    tag: str
    path: Path
    strings: set[str] = field(default_factory=set)
    plurals: set[str] = field(default_factory=set)


@dataclass
class LocaleDiff:
    missing_strings: list[str]
    missing_plurals: list[str]
    orphan_strings: list[str]
    orphan_plurals: list[str]


def discover_english(res_dir: Path) -> LocaleKeys:
    path = res_dir / "values" / "strings.xml"
    if not path.is_file():
        raise SystemExit(f"English source of truth not found: {path}")
    return LocaleKeys("en", path)


def discover_translations(res_dir: Path) -> list[LocaleKeys]:
    locales: list[LocaleKeys] = []
    for path in sorted(res_dir.glob("values-*/strings.xml")):
        tag = path.parent.name.removeprefix("values-")
        locales.append(LocaleKeys(tag, path))
    if not locales:
        raise SystemExit(f"No values-*/strings.xml files found under {res_dir}")
    return locales


def load_keys(locale: LocaleKeys) -> None:
    try:
        root = ET.parse(locale.path).getroot()
    except ET.ParseError as exc:
        raise SystemExit(f"{locale.path}: XML parse error: {exc}") from exc

    for element in root:
        name = element.attrib.get("name")
        if not name:
            continue
        if element.tag == "string":
            locale.strings.add(name)
        elif element.tag == "plurals":
            locale.plurals.add(name)


def diff_against_english(english: LocaleKeys, locale: LocaleKeys) -> LocaleDiff:
    return LocaleDiff(
        missing_strings=sorted(english.strings - locale.strings),
        missing_plurals=sorted(english.plurals - locale.plurals),
        orphan_strings=sorted(locale.strings - english.strings),
        orphan_plurals=sorted(locale.plurals - english.plurals),
    )


def write_github_summary(lines: list[str]) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        return
    with open(summary_path, "a", encoding="utf-8") as handle:
        handle.write("\n".join(lines))
        handle.write("\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--res-dir",
        type=Path,
        default=Path("app/src/main/res"),
        help="Android res/ directory (default: app/src/main/res)",
    )
    args = parser.parse_args()

    english = discover_english(args.res_dir)
    load_keys(english)

    locales = discover_translations(args.res_dir)
    for locale in locales:
        load_keys(locale)

    diffs: dict[str, LocaleDiff] = {}
    for locale in locales:
        diff = diff_against_english(english, locale)
        if (
            diff.missing_strings
            or diff.missing_plurals
            or diff.orphan_strings
            or diff.orphan_plurals
        ):
            diffs[locale.tag] = diff

    summary: list[str] = [
        "## Translation key parity",
        "",
        "Compared each `values-*/strings.xml` against English `values/strings.xml` "
        f"({len(english.strings)} strings, {len(english.plurals)} plurals).",
        "",
        "| Locale | File | Missing strings | Missing plurals | Orphan strings | Orphan plurals |",
        "|--------|------|-----------------|-----------------|----------------|----------------|",
    ]

    for locale in locales:
        diff = diffs.get(
            locale.tag,
            LocaleDiff([], [], [], []),
        )
        summary.append(
            f"| `{locale.tag}` | `{locale.path}` | "
            f"{len(diff.missing_strings)} | {len(diff.missing_plurals)} | "
            f"{len(diff.orphan_strings)} | {len(diff.orphan_plurals)} |"
        )

    if diffs:
        summary.extend(["", "### Details", ""])
        for locale in locales:
            diff = diffs.get(locale.tag)
            if not diff:
                continue
            summary.append(f"#### `{locale.tag}` (`{locale.path}`)")
            if diff.missing_strings:
                summary.append("")
                summary.append(f"**Missing strings ({len(diff.missing_strings)}):**")
                summary.extend(f"- `{name}`" for name in diff.missing_strings)
            if diff.missing_plurals:
                summary.append("")
                summary.append(f"**Missing plurals ({len(diff.missing_plurals)}):**")
                summary.extend(f"- `{name}`" for name in diff.missing_plurals)
            if diff.orphan_strings:
                summary.append("")
                summary.append(
                    f"**Orphan strings ({len(diff.orphan_strings)}) — not in English:**"
                )
                summary.extend(f"- `{name}`" for name in diff.orphan_strings)
            if diff.orphan_plurals:
                summary.append("")
                summary.append(
                    f"**Orphan plurals ({len(diff.orphan_plurals)}) — not in English:**"
                )
                summary.extend(f"- `{name}`" for name in diff.orphan_plurals)
            summary.append("")

    write_github_summary(summary)

    if not diffs:
        print(
            f"OK: all {len(locales)} locale files match English "
            f"({len(english.strings)} strings, {len(english.plurals)} plurals)."
        )
        return 0

    print("Translation key parity check completed with notices.\n", file=sys.stderr)
    missing_locales = 0
    missing_total = 0
    orphan_total = 0
    for locale in locales:
        diff = diffs.get(locale.tag)
        if not diff:
            continue
        rel = locale.path
        print(f"[{locale.tag}] {rel}", file=sys.stderr)
        for name in diff.missing_strings:
            print(f"  ::notice file={rel}::missing string `{name}`", file=sys.stderr)
        for name in diff.missing_plurals:
            print(f"  ::notice file={rel}::missing plural `{name}`", file=sys.stderr)
        for name in diff.orphan_strings:
            print(
                f"  ::notice file={rel}::orphan string `{name}` (not in English)",
                file=sys.stderr,
            )
        for name in diff.orphan_plurals:
            print(
                f"  ::notice file={rel}::orphan plural `{name}` (not in English)",
                file=sys.stderr,
            )
        print(file=sys.stderr)
        missing = len(diff.missing_strings) + len(diff.missing_plurals)
        orphans = len(diff.orphan_strings) + len(diff.orphan_plurals)
        if missing:
            missing_locales += 1
        missing_total += missing
        orphan_total += orphans

    print(
        f"{missing_locales} locale(s) missing keys vs English; "
        f"{missing_total} missing, {orphan_total} orphan key(s).",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
