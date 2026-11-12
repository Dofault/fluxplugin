#!/usr/bin/env python3
"""The HUD's font (flux:hud), shipped in Flux's client pack (resources/pack/), 2026-11-13.

- space provider: negative (U+E010 + n = -2^n px) and positive (U+E020 + n = +2^n px) spaces, n = 0..9;
- U+E000: a tall invisible glyph (2 x 2 picture at alpha 1, height 40) that makes the client draw the HUD's front
  boss bar above its back one (fr.influencraft.flux.hud.HudService);
- QuinqueFive (5 px capitals, the font of the vanilla menu theme) for the HUD's small texts, in ONE sheet
  (never one glyph per picture: docs/technique/pack-ressources.md). Cells 7 px tall, ink in the top 5 rows: with
  ascent 7 a glyph's top sits on the line's top, like the game's own letters.

Also writes hud_font_widths.json (advance of each character of that sheet) for the server to measure texts.
No dependency (PNG written with zlib). Usage: gen_hud_font.py
"""
import json
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "src", "main", "resources")
FONT = json.load(open(os.path.join(HERE, "..", "metiers", "quinquefive.json")))
# characters missing from QuinqueFive, drawn in its 5 px grid (as fond_lib.py)
EXTRA = {
    ",": [".", ".", ".", "#", "#"], ".": [".", ".", ".", ".", "#"], ":": [".", "#", ".", "#", "."],
    "-": ["...", "...", "###", "...", "..."], "+": ["...", ".#.", "###", ".#.", "..."],
    "/": ["..#", "..#", ".#.", "#..", "#.."], "(": [".#", "#.", "#.", "#.", ".#"], ")": ["#.", ".#", ".#", ".#", "#."],
    "'": ["#", "#", ".", ".", "."], "!": ["#", "#", "#", ".", "#"], "%": ["#..#", "...#", "..#.", ".#..", "#..#"],
    "?": ["###", "..#", ".##", "...", ".#."],
}
SPACE = 3  # advance of a space in that font
CELL_W, CELL_H, COLS = 5, 7, 16


def png(w, h, pixels):
    raw = b"".join(b"\x00" + bytes(c for p in row for c in p) for row in pixels)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def main():
    glyphs = dict(FONT)
    glyphs.update(EXTRA)
    chars = sorted(glyphs)
    rows = [chars[i:i + COLS] for i in range(0, len(chars), COLS)]
    rows[-1] += ["\u0000"] * (COLS - len(rows[-1]))
    w, h = COLS * CELL_W, len(rows) * CELL_H
    pixels = [[(0, 0, 0, 0)] * w for _ in range(h)]
    widths = {}
    for r, line in enumerate(rows):
        for c, ch in enumerate(line):
            if ch == "\u0000":
                continue
            g = glyphs[ch]
            for y, bits in enumerate(g):
                for x, b in enumerate(bits):
                    if b == "#":
                        pixels[r * CELL_H + y][c * CELL_W + x] = (255, 255, 255, 255)
            ink = max((x + 1 for bits in g for x, b in enumerate(bits) if b == "#"), default=0)
            widths[ch] = ink + 1
    widths[" "] = SPACE

    tex = os.path.join(RES, "pack", "assets", "flux", "textures", "font")
    os.makedirs(tex, exist_ok=True)
    with open(os.path.join(tex, "hud_text.png"), "wb") as f:
        f.write(png(w, h, pixels))
    with open(os.path.join(tex, "hud_spacer.png"), "wb") as f:
        f.write(png(2, 2, [[(255, 255, 255, 1)] * 2] * 2))

    advances = {" ": SPACE}
    for n in range(10):
        advances[chr(0xE010 + n)] = -(1 << n)
        advances[chr(0xE020 + n)] = 1 << n
    font = {"providers": [
        {"type": "space", "advances": advances},
        {"type": "bitmap", "file": "flux:font/hud_spacer.png", "height": 40, "ascent": 7, "chars": [""]},
        {"type": "bitmap", "file": "flux:font/hud_text.png", "height": CELL_H, "ascent": CELL_H, "chars": ["".join(l) for l in rows]},
    ]}
    os.makedirs(os.path.join(RES, "pack", "assets", "flux", "font"), exist_ok=True)
    with open(os.path.join(RES, "pack", "assets", "flux", "font", "hud.json"), "w", encoding="utf-8") as f:
        json.dump(font, f, indent=2)
    with open(os.path.join(RES, "hud_font_widths.json"), "w", encoding="utf-8") as f:
        json.dump({str(ord(k)): v for k, v in sorted(widths.items())}, f, separators=(",", ":"))
    print("%d characters, sheet %dx%d" % (len(widths), w, h))


if __name__ == "__main__":
    main()
