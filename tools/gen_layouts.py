#!/usr/bin/env python3
"""Generate the hub's keyboard layout tables (hub/src/main/assets/layouts/).

Sources:
  - the firmware's generated keymap (components/keymap/keymap_layouts.c) for the layouts the
    dongle also has, so phone and dongle agree on them;
  - InputStick's MIT-licensed layouts (github.com/inputstick/InputStickAPI-Android,
    InputStickAPI/src/com/inputstick/api/layout) for the rest.

Usage:
    py -3 tools/gen_layouts.py --firmware ../firmware/components/keymap/keymap_layouts.c \
        --inputstick <clone>/InputStickAPI/src/com/inputstick/api/layout

Output format, one file per layout:
    # id <id>
    # name <English name>
    # source <where the table came from>
    <code point hex> <flags hex> <modifiers hex>:<key hex> [<modifiers hex>:<key hex>]
flags bit 0: the last stroke is affected by Caps Lock. Two strokes: dead key, then base key.
"""

import argparse
import re
import unicodedata
from pathlib import Path

SHIFT = 0x02 | 0x20
ENTER, TAB = 0x28, 0x2B

# InputStick layouts skipped because the firmware's table is used instead.
FIRMWARE_IDS = {"en-US", "es-419"}

# Display names; several InputStick layouts share an English name.
NAMES = {
    "en-US": "English (US)",
    "es-419": "Spanish (Latin America)",
    "fr-BE": "French (Belgium)",
    "fr-CA": "French (Canada)",
    "hr-HR": "Croatian",
    "cs-CZ": "Czech",
    "cs-CZ-linux": "Czech (Linux)",
    "cs-CZ-pro": "Czech (Programmers)",
    "da-DK": "Danish",
    "nl-NL": "Dutch",
    "en-DV": "English (Dvorak)",
    "fi-FI": "Finnish",
    "fr-FR": "French (France)",
    "fr-FR-linux": "French (France, Linux)",
    "de-DE": "German",
    "de-DE-mac": "German (macOS)",
    "el-GR": "Greek",
    "he-IL": "Hebrew",
    "hu-HU": "Hungarian",
    "it-IT": "Italian",
    "nb-NO": "Norwegian",
    "pl-PL": "Polish (Programmers)",
    "pl-PL-linux": "Polish (Linux)",
    "pt-BR": "Portuguese (Brazil)",
    "pt-PT": "Portuguese (Portugal)",
    "ru-RU": "Russian",
    "sk-SK": "Slovak",
    "es-ES": "Spanish (Spain)",
    "sv-SE": "Swedish",
    "fr-CH": "French (Switzerland)",
    "de-CH": "German (Switzerland)",
    "en-GB": "English (UK)",
    "en-US-int": "English (US International)",
}


def firmware_layouts(path):
    text = Path(path).read_text(encoding="utf-8")
    names = dict(re.findall(r'\{"([^"]+)", "([^"]+)", s_\w+,', text))
    layouts = []
    for m in re.finditer(r"static const keymap_entry_t s_(\w+)\[\] = \{(.*?)\};", text, re.S):
        entries = {}
        for e in re.finditer(r"\{0x([0-9A-F]+), (0|KEYMAP_FLAG_CAPS), \{((?:\{[^{}]*\},?\s*)+)\}\}", m.group(2)):
            strokes = [(int(a, 16), int(b, 16)) for a, b in re.findall(r"\{0x([0-9A-F]+), 0x([0-9A-F]+)\}", e.group(3))]
            entries[int(e.group(1), 16)] = (strokes, e.group(2) != "0")
        layout_id = next(i for i in names if i.replace("-", "_").lower() == m.group(1))
        layouts.append((layout_id, names[layout_id], "Keyling firmware keymap", entries))
    return layouts


def inputstick_layout(path):
    text = Path(path).read_text(encoding="utf-8")

    def constant(name):
        m = re.search(name + r'\s*=\s*"([^"]*)"', text)
        return m.group(1) if m else ""

    lut = re.search(r"FAST_LUT\s*=\s*\{(.*?)\n\s*\};", text, re.S).group(1)
    rows = []
    for row in re.findall(r"\{([^{}]*)\}", lut):
        values = [int(v) for v in re.findall(r"-?\d+", row)]
        if len(values) == 5 and values[0] > 0:
            rows.append(values)

    keyed = {}
    for c, mod, key, dmod, dkey in rows:
        keyed.setdefault(c, (mod, key, dmod, dkey))

    entries = {}
    for c, (mod, key, dmod, dkey) in keyed.items():
        strokes = ([(dmod, dkey)] if dkey else []) + [(mod, key)]
        ch = chr(c)
        other = ch.swapcase()
        caps = False
        if ch.isalpha() and other != ch and len(other) == 1 and ord(other) in keyed:
            omod, okey, odmod, odkey = keyed[ord(other)]
            caps = okey == key and (odmod, odkey) == (dmod, dkey) and (omod ^ mod) & ~SHIFT == 0 and omod != mod
        entries[c] = (strokes, caps)
    return constant("LOCALE_NAME"), constant("LAYOUT_NAME_ENGLISH"), constant("LAYOUT_VARIANT"), entries


def emit(out_dir, layout_id, name, source, entries):
    name = NAMES.get(layout_id, name)
    entries = dict(entries)
    entries.setdefault(0x0A, ([(0, ENTER)], False))
    entries.setdefault(0x09, ([(0, TAB)], False))
    lines = [f"# id {layout_id}", f"# name {name}", f"# source {source}"]
    for c in sorted(entries):
        strokes, caps = entries[c]
        s = " ".join(f"{m:02x}:{k:02x}" for m, k in strokes)
        lines.append(f"{c:04x} {1 if caps else 0:x} {s}")
    (out_dir / f"{layout_id}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"{layout_id:12} {len(entries):4} characters  {name}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--firmware", required=True)
    ap.add_argument("--inputstick", required=True)
    ap.add_argument("-o", "--output", default=Path(__file__).resolve().parent.parent / "hub/src/main/assets/layouts")
    args = ap.parse_args()

    out_dir = Path(args.output)
    out_dir.mkdir(parents=True, exist_ok=True)
    for old in out_dir.glob("*.txt"):
        old.unlink()

    seen = set()
    for layout_id, name, source, entries in firmware_layouts(args.firmware):
        emit(out_dir, layout_id, name, source, entries)
        seen.add(layout_id)

    found = []
    for path in sorted(Path(args.inputstick).glob("*Layout.java")):
        if path.name == "KeyboardLayout.java":
            continue
        locale, name, variant, entries = inputstick_layout(path)
        found.append((path, locale, name, variant, entries))
    locales = [locale for _, locale, _, _, _ in found]
    for path, locale, name, variant, entries in found:
        if locale in FIRMWARE_IDS:
            continue
        layout_id = locale if locales.count(locale) == 1 else f"{locale}-{re.sub(r'[^a-z0-9]+', '', variant.lower()) or path.stem.lower()}"
        if layout_id in seen:
            raise SystemExit(f"duplicate layout id {layout_id} ({path.name})")
        seen.add(layout_id)
        emit(out_dir, layout_id, name or path.stem, f"InputStick {path.name} (MIT)", entries)


if __name__ == "__main__":
    main()
