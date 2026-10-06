#!/usr/bin/env python3
"""One-off: pull OpenCommunity's embedded Play TTFs out of its C headers.

The reference client (`E:\\swl\\OpenCommunity`) does not ship font *files*: like
most ImGui integrations it embeds each face as a C byte array in
`deps/imgui/fonts/<face>_<weight>_font.h`, e.g.

    static const unsigned int  play_regular_size = 171084;
    static const unsigned char play_regular_data[171084] = {
        0x00,0x01,0x00,0x00,0x00,0x11,0x01,0x00,/* ... */

This script parses those literals (hex and decimal, whitespace and commas
irrelevant), writes the bytes back out as real `.ttf` files under
`hud/assets/`, and then reads each file's `name` table so the Rust side can
create the face by its *real* family/style rather than by a guessed string.

It also dumps the name table's copyright (ID 0), licence description (ID 13) and
licence URL (ID 14) entries into `hud/assets/FONTS.md`, because the reference
tree carries no licence file of its own and a font must not ship silently.

Run from anywhere:

    python tools/extract_oc_fonts.py

Only the faces the HUD actually draws are extracted. `RenderHook.cpp:530-533`
is the authority: `HUD::SetFonts` gets Play regular + Play bold (+ a
"vape font" loaded from disk, which the port substitutes with Play regular),
while Inter regular/bold go to `Notifications::SetFonts` — a subsystem this
overlay does not have. So: Play regular + Play bold, nothing else.
"""

import os
import re
import struct
import sys

# The reference checkout, and the two faces the HUD uses.
REF_DIR = r"E:\swl\OpenCommunity\deps\imgui\fonts"
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets")

# (header stem, output file name). The stem is also the C identifier prefix.
FACES = [
    ("play_regular", "Play-Regular.ttf"),
    ("play_bold", "Play-Bold.ttf"),
]

# `static const unsigned int play_regular_size = 171084;`
SIZE_RE = re.compile(
    r"static\s+const\s+unsigned\s+int\s+{stem}_size\s*=\s*(\d+)\s*;"
)
# `static const unsigned char play_regular_data[171084] = { ... };`
DATA_RE = re.compile(
    r"static\s+const\s+unsigned\s+char\s+{stem}_data\s*\[\s*\d*\s*\]\s*=\s*\{(.*?)\}\s*;",
    re.DOTALL,
)
# Every integer in the initialiser, hex (`0x00`) or decimal.
NUM_RE = re.compile(r"0[xX][0-9a-fA-F]+|\d+")

# Name-table IDs we care about.
NAME_ID_COPYRIGHT = 0
NAME_ID_FAMILY = 1
NAME_ID_SUBFAMILY = 2
NAME_ID_FULL = 4
NAME_ID_VERSION = 5
NAME_ID_LICENSE = 13
NAME_ID_LICENSE_URL = 14

NAME_IDS = {
    NAME_ID_COPYRIGHT: "copyright",
    NAME_ID_FAMILY: "family",
    NAME_ID_SUBFAMILY: "subfamily",
    NAME_ID_FULL: "full name",
    NAME_ID_VERSION: "version",
    NAME_ID_LICENSE: "licence",
    NAME_ID_LICENSE_URL: "licence URL",
}


def parse_array(header_path, stem):
    """Return the raw bytes of one `<stem>_data` array, checked against `<stem>_size`."""
    with open(header_path, "r", encoding="utf-8", errors="replace") as fh:
        text = fh.read()

    size_match = re.search(SIZE_RE.pattern.replace("{stem}", re.escape(stem)), text)
    if not size_match:
        raise SystemExit(f"{header_path}: no {stem}_size declaration")
    declared = int(size_match.group(1))

    data_match = re.search(
        DATA_RE.pattern.replace("{stem}", re.escape(stem)), text, re.DOTALL
    )
    if not data_match:
        raise SystemExit(f"{header_path}: no {stem}_data array")

    body = data_match.group(1)
    values = [int(tok, 0) for tok in NUM_RE.findall(body)]
    bad = [v for v in values if not 0 <= v <= 0xFF]
    if bad:
        raise SystemExit(f"{header_path}: {len(bad)} value(s) out of byte range")

    raw = bytes(values)
    if len(raw) != declared:
        raise SystemExit(
            f"{header_path}: parsed {len(raw)} bytes but {stem}_size says {declared}"
        )
    return raw


def _decode_name(raw):
    """Names are UTF-16BE on the Windows platform records, MacRoman otherwise.

    The licence strings in Play's name table contain a bare CR where a space was
    meant ("All\\rrights reserved"), which is a line break in the middle of a
    sentence; the three escape forms are folded to spaces so the record still
    reads as the font wrote it, minus the mangling.
    """
    try:
        text = raw.decode("utf-16-be")
    except UnicodeDecodeError:
        text = raw.decode("latin-1")
    return text.replace("\r\n", " ").replace("\r", " ").replace("\n", " ")


def read_name_table(data):
    """Parse the sfnt `name` table into {nameID: string} (English preferred)."""
    if len(data) < 12:
        raise SystemExit("file is too short to be a font")
    tag_major = data[:4]
    if tag_major == b"ttcf":
        raise SystemExit("collection fonts are not expected here")
    if tag_major not in (b"\x00\x01\x00\x00", b"true", b"OTTO"):
        raise SystemExit(f"unexpected sfnt version {tag_major!r}")

    num_tables = struct.unpack(">H", data[4:6])[0]
    tables = {}
    for i in range(num_tables):
        off = 12 + i * 16
        tag, _checksum, table_off, table_len = struct.unpack(">4sIII", data[off : off + 16])
        tables[tag] = (table_off, table_len)
    if b"name" not in tables:
        raise SystemExit("no name table")

    off, length = tables[b"name"]
    table = data[off : off + length]
    _fmt, count, string_off = struct.unpack(">HHH", table[:6])

    # Prefer the Windows/English record (3,1,0x409), then any Unicode, then anything.
    found = {}
    for i in range(count):
        rec = table[6 + i * 12 : 6 + i * 12 + 12]
        platform, encoding, language, name_id, n_len, n_off = struct.unpack(">HHHHHH", rec)
        raw = table[string_off + n_off : string_off + n_off + n_len]
        text = _decode_name(raw).replace("\x00", "").strip()
        if not text:
            continue
        rank = 0
        if (platform, encoding, language) == (3, 1, 0x409):
            rank = 3
        elif platform == 3:
            rank = 2
        elif platform == 0:
            rank = 1
        if name_id not in found or rank > found[name_id][0]:
            found[name_id] = (rank, text)

    return {name_id: text for name_id, (_rank, text) in found.items()}


def main():
    # The name tables carry non-ASCII (©, ®, é) and a Windows console here is
    # cp936 by default, which would abort the run on the first copyright string.
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass

    out_dir = os.path.normpath(OUT_DIR)
    os.makedirs(out_dir, exist_ok=True)

    summaries = []
    for stem, out_name in FACES:
        header = os.path.join(REF_DIR, f"{stem}_font.h")
        if not os.path.isfile(header):
            raise SystemExit(f"missing reference header: {header}")

        raw = parse_array(header, stem)
        out_path = os.path.join(out_dir, out_name)
        with open(out_path, "wb") as fh:
            fh.write(raw)

        names = read_name_table(raw)
        summaries.append((stem, out_name, len(raw), names, header))
        print(f"{out_name}: {len(raw)} bytes (declared {len(raw)}) -> {out_path}")
        for name_id, label in NAME_IDS.items():
            if name_id in names:
                print(f"    {label:<11} {names[name_id]!r}")

    write_fonts_md(out_dir, summaries)
    print(f"wrote {os.path.join(out_dir, 'FONTS.md')}")


def write_fonts_md(out_dir, summaries):
    import hashlib

    lines = [
        "# Fonts bundled with the HUD overlay",
        "",
        "**Do not ship this directory without reading this file.**",
        "",
        "These two `.ttf` files are not ours: they are the typefaces embedded in the",
        "reference OpenCommunity client, recovered from its C byte arrays by",
        "`tools/extract_oc_fonts.py`. The reference tree carries **no font licence",
        "file of its own** (`E:\\swl\\OpenCommunity` has a single project `LICENSE` and",
        "no `OFL.txt`), so the copyright and licence strings below were read straight",
        "out of each font's own `name` table — that is the licence the font itself",
        "declares, and it is reproduced here verbatim rather than summarised.",
        "",
        "## Both faces are under the SIL Open Font License, Version 1.1",
        "",
        "* **Play** — Copyright (c) 2011, Jonas Hecksher / Playtype, with Reserved Font",
        "  Name *Play*. Licensed under the SIL Open Font License, Version 1.1.",
        "* **Inter** (not bundled here; see below) — Copyright (c) 2016 The Inter",
        "  Project Authors, under the same licence.",
        "",
        "The OFL permits redistribution and embedding, including in a binary, provided",
        "the copyright notice and the licence travel with the font and the font is not",
        "sold on its own. `tools/extract_oc_fonts.py` prints the exact `name`-table",
        "strings the fonts declare; they are also recorded below.",
        "",
        "The canonical licence text is at <https://scripts.sil.org/OFL> (OFL-1.1).",
        "It is not reproduced in full here because this repository is not the",
        "licensor's distribution channel and the reference checkout did not carry a",
        "copy to include; if this overlay is ever distributed on its own, add the",
        "verbatim `OFL.txt` from the upstream projects",
        "(<https://github.com/google/fonts/tree/main/ofl/play>,",
        "<https://github.com/rsms/inter>) alongside these files.",
        "",
        "## What is bundled, and why only this",
        "",
        "| file | source header | bytes | sha256 | used for |",
        "| ---- | ------------- | ----- | ------ | -------- |",
    ]

    for stem, out_name, size, _names, header in summaries:
        with open(os.path.join(out_dir, out_name), "rb") as fh:
            digest = hashlib.sha256(fh.read()).hexdigest()
        role = "module names + watermark title (bold)" if stem.endswith("bold") else "tags, values and everything else (regular)"
        lines.append(
            f"| `{out_name}` | `deps/imgui/fonts/{stem}_font.h` | {size} | `{digest}` | {role} |"
        )

    lines += [
        "",
        "The reference also embeds face headers that this overlay deliberately does",
        "**not** bundle: `inter_regular` / `inter_bold` (they go to",
        "`Notifications::SetFonts`, a subsystem this HUD does not have —",
        "`RenderHook.cpp:530-533`), plus Open Sans, SF UI Display and the",
        "compressed variants the reference's wider UI uses. Bundling them would cost",
        "roughly 1 MB of executable for glyphs nothing draws.",
        "",
        "## What each font says about itself",
        "",
    ]

    for stem, out_name, _size, names, header in summaries:
        lines.append(f"### `{out_name}`")
        lines.append("")
        lines.append(f"Recovered from `{header}`.")
        lines.append("")
        for name_id in (NAME_ID_COPYRIGHT, NAME_ID_FAMILY, NAME_ID_SUBFAMILY,
                        NAME_ID_FULL, NAME_ID_VERSION, NAME_ID_LICENSE,
                        NAME_ID_LICENSE_URL):
            if name_id in names:
                lines.append(f"* **{NAME_IDS[name_id]}** — {names[name_id]}")
        lines.append("")
        lines.append(
            "**Family / style the Rust side must use with `CreateFontW`:** "
            f"`{names.get(NAME_ID_FAMILY, '?')}` / `{names.get(NAME_ID_SUBFAMILY, '?')}`"
        )
        lines.append("")

    with open(os.path.join(out_dir, "FONTS.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    sys.exit(main())
