#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Report missing locale string/plural keys as GitHub notices without failing."""

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


def discover_locales(res_dir: Path) -> list[LocaleKeys]:
    locales: list[LocaleKeys] = []
    base = res_dir / "values" / "strings.xml"
    if base.is_file():
        locales.append(LocaleKeys("en", base))

    for path in sorted(res_dir.glob("values-*/strings.xml")):
        tag = path.parent.name.removeprefix("values-")
        locales.append(LocaleKeys(tag, path))

    if not locales:
        raise SystemExit(f"No strings.xml files found under {res_dir}")
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

    locales = discover_locales(args.res_dir)
    for locale in locales:
        load_keys(locale)

    all_strings = set().union(*(loc.strings for loc in locales))
    all_plurals = set().union(*(loc.plurals for loc in locales))

    missing_by_locale: dict[str, dict[str, list[str]]] = {}
    for locale in locales:
        missing_strings = sorted(all_strings - locale.strings)
        missing_plurals = sorted(all_plurals - locale.plurals)
        if missing_strings or missing_plurals:
            missing_by_locale[locale.tag] = {
                "strings": missing_strings,
                "plurals": missing_plurals,
            }

    summary: list[str] = [
        "## Translation key parity",
        "",
        f"Checked **{len(locales)}** locale file(s); "
        f"**{len(all_strings)}** string keys and **{len(all_plurals)}** plural keys required in each.",
        "",
        "| Locale | File | Missing strings | Missing plurals |",
        "|--------|------|-----------------|-----------------|",
    ]

    for locale in locales:
        miss = missing_by_locale.get(locale.tag, {"strings": [], "plurals": []})
        summary.append(
            f"| `{locale.tag}` | `{locale.path}` | {len(miss['strings'])} | {len(miss['plurals'])} |"
        )

    if missing_by_locale:
        summary.extend(["", "### Missing keys", ""])
        for locale in locales:
            miss = missing_by_locale.get(locale.tag)
            if not miss:
                continue
            summary.append(f"#### `{locale.tag}` (`{locale.path}`)")
            if miss["strings"]:
                summary.append("")
                summary.append(f"**Strings ({len(miss['strings'])}):**")
                summary.extend(f"- `{name}`" for name in miss["strings"])
            if miss["plurals"]:
                summary.append("")
                summary.append(f"**Plurals ({len(miss['plurals'])}):**")
                summary.extend(f"- `{name}`" for name in miss["plurals"])
            summary.append("")

    write_github_summary(summary)

    if not missing_by_locale:
        print(
            f"OK: all {len(locales)} locale files contain "
            f"{len(all_strings)} strings and {len(all_plurals)} plurals."
        )
        return 0

    print("Translation key parity check completed with notices.\n", file=sys.stderr)
    for locale in locales:
        miss = missing_by_locale.get(locale.tag)
        if not miss:
            continue
        rel = locale.path
        print(f"[{locale.tag}] {rel}", file=sys.stderr)
        for name in miss["strings"]:
            print(f"  ::notice file={rel}::missing string `{name}`", file=sys.stderr)
        for name in miss["plurals"]:
            print(f"  ::notice file={rel}::missing plural `{name}`", file=sys.stderr)
        print(file=sys.stderr)

    total = sum(
        len(m["strings"]) + len(m["plurals"]) for m in missing_by_locale.values()
    )
    print(
        f"{len(missing_by_locale)} locale(s) incomplete; {total} missing key entries overall.",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())