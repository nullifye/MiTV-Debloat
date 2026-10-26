#!/usr/bin/env python3
"""Builds tui/catalog-cleanyourtv.toml from cleanyourtv's per-device debloat lists.

    git clone --depth 1 https://github.com/Gathin23/cleanyourtv /tmp/cleanyourtv
    python3 -I tui/tools/import_cleanyourtv.py /tmp/cleanyourtv tui/catalog-cleanyourtv.toml

Every package any list disables is offered in the setup app's "Clean up the TV". It's pre-ticked
("safe") only when it's clearly ads, tracking, promos or demos AND at least two devices' owners
disabled it AND it wasn't one owner's personal choice. Risky system parts are marked "keep".
tui/catalog.toml (this project's own notes) always wins over this file.
"""
import collections, os, re, sys

SAFE = re.compile(r"telemetr|analytic|tracking|tracker|\bads?\b|advert|ad services|promo|sponsor|recommend|"
                  r"retail|demo|store ?mode|feedback|bug ?report|crash|statistic|usage stat|personali[sz]|"
                  r"federated|auto-?install|partner|attribution|\bacr\b|samba|fingerprint|log upload|logkit|"
                  r"upload|survey|consent|disclaimer|agreement|e-manual|user ?guide|instructions|help tile|"
                  r"sticker|push message|\bnags?\b|metrics|device agent|remote support|content rows", re.I)
RISKY = re.compile(r"webview|provider|tvinput|tuner|inputmethod|keyboard|bluetooth|wifi|update|setup|"
                   r"launcher|factory|cast|miracast|mediashell|assistant|voice|katniss|screensaver|dream|"
                   r"backdrop|tts|talkback|accessib|player|browser|music|video|netflix|youtube|prime|"
                   r"disney|spotify|pandora|zee5|hulu|plex|store\b|vending|install|remote\.service|hdmi|cec|input|"
                   r"micmanager|microphone|file ?manager|filemanager|theme", re.I)
KEEP = re.compile(r"webview|providers?\.|tvinput|tuner|inputmethod|keyboard|systemupdater|setupwizard|"
                  r"setupwraith|wifi|settings\.intelligence|factory|bluetooth", re.I)
CHOICE = re.compile(r"owner|by choice|choice|not used in this|disabled by", re.I)
PKG = re.compile(r"^([a-zA-Z][\w]*(?:\.[\w]+)+)\s*(?:#\s*(.*))?$")


GENERIC = {"com", "org", "net", "tv", "app", "apps", "android", "google", "service", "services", "atv", "dtv", "overseas"}


def readable(pkg):
    """A name from the package: com.pandora.android.atv → "Pandora"."""
    parts = [x for x in pkg.split(".") if x.lower() not in GENERIC and len(x) > 2]
    return (parts[-1] if parts else pkg.split(".")[-1]).replace("_", " ")


def clean_section(t):
    t = re.sub(r"^[-\s]*", "", t)
    t = re.sub(r"^(batch\s*\d+\s*[:\-–]\s*|\d+\.\s*)", "", t, flags=re.I)
    return t.strip(" -:").strip()


def main(repo, out_path):
    root = os.path.join(repo, "examples")
    pk = collections.defaultdict(lambda: {"devices": set(), "notes": collections.Counter(), "sections": collections.Counter()})
    for dev in sorted(os.listdir(root)):
        d = os.path.join(root, dev)
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            if not (f.startswith("debloat") and f.endswith(".txt")):
                continue
            section = ""
            for line in open(os.path.join(d, f), encoding="utf-8", errors="replace"):
                line = line.strip()
                if not line:
                    continue
                if line.startswith("#"):
                    t = line.lstrip("#").strip()
                    if t and len(t) < 80 and not re.match(r"(apply|undo|owner|stock|everything|this list|region|android \d|google tv \d|kept)", t, re.I):
                        section = clean_section(t)
                    continue
                m = PKG.match(line)
                if not m:
                    continue
                p, note = m.group(1), (m.group(2) or "").strip()
                # A second "#" is a leftover comment (or a commented-out package): keep the first part.
                note = note.split("#")[0].strip()
                if note.count('"') == 2 and note.startswith('"') and note.endswith('"'):
                    note = note[1:-1]
                if PKG.match(note):
                    note = ""
                e = pk[p]
                e["devices"].add(dev)
                if note:
                    e["notes"][note] += 1
                if section:
                    e["sections"][section] += 1
    rows = []
    for p, e in sorted(pk.items()):
        n = len(e["devices"])
        note = e["notes"].most_common(1)[0][0] if e["notes"] else ""
        section = e["sections"].most_common(1)[0][0] if e["sections"] else "Preloaded apps"
        text = f"{p} {note} {section}"
        if KEEP.search(p) or KEEP.search(note):
            advice = "keep"
        elif (
            n >= 2
            # Judged on the package's own note; the section heading only when there's no note.
            and SAFE.search(f"{p} {note}" if note else text)
            and not RISKY.search(f"{p} {note}")
            and not CHOICE.search(section)
            and "protected" not in note.lower()
        ):
            advice = "safe"
        else:
            advice = "caution"
        label = note.split(";")[0].split(" (")[0].strip() if note else ""
        if not label or len(label) > 48:
            label = readable(p)
        label = label[0].upper() + label[1:]
        desc = note if note else section
        desc = f"{desc[0].upper()}{desc[1:]}" if desc else ""
        desc += f" (disabled on {n} device{'s' if n != 1 else ''} in cleanyourtv)"
        rows.append((p, label, section or "Preloaded apps", advice, desc))
    esc = lambda s: s.replace("\\", "\\\\").replace('"', '\\"')
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("# Generated by tui/tools/import_cleanyourtv.py from https://github.com/Gathin23/cleanyourtv\n")
        f.write("# (examples/*/debloat*.txt). Do not edit by hand: change catalog.toml, which wins over this file.\n#\n")
        f.write("# Data from cleanyourtv, MIT License, Copyright (c) 2026 Gathin.\n")
        f.write("# Permission is hereby granted, free of charge, to any person obtaining a copy of this software and\n")
        f.write("# associated documentation files, to deal in the Software without restriction, subject to including\n")
        f.write("# this copyright notice and permission notice in all copies or substantial portions of the Software.\n")
        f.write(f"# {len(rows)} packages; safe = pre-ticked in Clean up.\n\n")
        for p, label, section, advice, desc in rows:
            f.write(f'[[app]]\npackage = "{esc(p)}"\nlabel = "{esc(label)}"\ncategory = "{esc(section)}"\nadvice = "{advice}"\nnote = "{esc(desc)}"\n\n')
    counts = collections.Counter(r[3] for r in rows)
    print(f"{len(rows)} packages: {dict(counts)}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
