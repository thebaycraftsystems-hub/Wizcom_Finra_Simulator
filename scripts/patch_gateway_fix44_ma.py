#!/usr/bin/env python3
"""Patch gateway FIX44.xml for CAMA/SPMA/TSMA (1011) and Match Status tags 22027/22028."""
from __future__ import annotations

import argparse
import re
import shutil
import sys
from datetime import datetime
from pathlib import Path

DEFAULT_FIX44 = Path(
    "/home/wizcom/apps/jpm/Trace/trace_UAT/latest/for_14/Test/"
    "trace_gateway_NativeFix/config/FIX44.xml"
)

MA_ENUMS = (
    ("CAMA", "CONFIRMED_MATCH_STATUS"),
    ("SPMA", "CONFIRMED_MATCH_STATUS"),
    ("TSMA", "CONFIRMED_MATCH_STATUS"),
)

MA_FIELDS = (
    ('<field number="22027" name="MatchControlDate" type="LOCALMKTDATE"/>', "22027"),
    ('<field number="22028" name="MatchTradeID" type="STRING"/>', "22028"),
)

ENUM_LINE = '\t\t\t<value enum="{code}" description="{desc}"/>\n'


def patch_1011_enums(content: str) -> tuple[str, list[str]]:
    added: list[str] = []
    for code, desc in MA_ENUMS:
        if f'enum="{code}"' in content:
            continue
        pattern = re.compile(
            r'(<field\s+number="1011"[^>]*>.*?)(</field>)',
            re.DOTALL,
        )
        match = pattern.search(content)
        if not match:
            raise ValueError("FIX44.xml has no field 1011 (MessageEventSource)")
        body, close = match.group(1), match.group(2)
        if not body.endswith("\n"):
            body += "\n"
        body += ENUM_LINE.format(code=code, desc=desc)
        content = content[: match.start()] + body + close + content[match.end() :]
        added.append(code)
    return content, added


def patch_ma_fields(content: str) -> tuple[str, list[str]]:
    added: list[str] = []
    for field_xml, tag in MA_FIELDS:
        if f'number="{tag}"' in content:
            continue
        anchor = '<field number="22012" name="OrigControlDate"'
        idx = content.find(anchor)
        if idx < 0:
            anchor = '<field number="22011" name="ControlDate"'
            idx = content.find(anchor)
        if idx < 0:
            raise ValueError("Could not find anchor near ControlDate fields for 22027/22028")
        line_end = content.find("/>", idx)
        if line_end < 0:
            raise ValueError("Malformed FIX44.xml near ControlDate fields")
        insert_at = line_end + 2
        insert = f"\n\t\t{field_xml}"
        content = content[:insert_at] + insert + content[insert_at:]
        added.append(tag)
    return content, added


def patch_ae_message_fields(content: str) -> tuple[str, list[str]]:
    """Allow 22027/22028 on TradeCaptureReport (35=AE), not only in global fields section."""
    added: list[str] = []
    anchor = '<field name="OrigControlDate" required="N"/>'
    if anchor not in content:
        return content, added
    for name in ("MatchControlDate", "MatchTradeID"):
        msg_field = f'<field name="{name}" required="N"/>'
        if msg_field in content:
            continue
        insert = (
            f'\n\t\t\t<field name="{name}" required="N"/>'
        )
        idx = content.find(anchor)
        line_end = content.find("/>", idx)
        if line_end < 0:
            raise ValueError("Malformed FIX44.xml near OrigControlDate in AE message")
        insert_at = line_end + 2
        content = content[:insert_at] + insert + content[insert_at:]
        added.append(name)
    return content, added


def main() -> int:
    parser = argparse.ArgumentParser(description="Patch gateway FIX44.xml for Match Status (MA)")
    parser.add_argument("fix44", nargs="?", type=Path, default=DEFAULT_FIX44)
    parser.add_argument("--no-backup", action="store_true")
    args = parser.parse_args()
    path: Path = args.fix44

    if not path.is_file():
        print(f"ERROR: file not found: {path}", file=sys.stderr)
        return 1

    content = path.read_text(encoding="utf-8")
    try:
        content, enums = patch_1011_enums(content)
        content, fields = patch_ma_fields(content)
        content, ae_fields = patch_ae_message_fields(content)
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    if not enums and not fields and not ae_fields:
        print(f"OK: {path} already has MA enums, tags 22027/22028, and AE message fields")
        return 0

    if not args.no_backup:
        backup = path.with_suffix(path.suffix + f".bak.{datetime.now():%Y%m%d%H%M%S}")
        shutil.copy2(path, backup)
        print(f"Backup: {backup}")

    path.write_text(content, encoding="utf-8", newline="\n")
    print(f"Updated: {path}")
    if enums:
        print(f"Added 1011 enums: {', '.join(enums)}")
    if fields:
        print(f"Added fields: {', '.join(fields)}")
    if ae_fields:
        print(f"Added to AE message: {', '.join(ae_fields)}")
    print("Restart the gateway so QuickFIX/J reloads the data dictionary.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
