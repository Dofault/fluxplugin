#!/usr/bin/env python3
"""The HUD's font (flux:hud) and its panels, shipped in Flux's client pack (resources/pack/), 2026-11-13.

- space provider: negative (U+E010 + n = -2^n px) and positive (U+E020 + n = +2^n px) spaces, n = 0..9;
- U+E000: a tall invisible glyph (2 x 2 picture at alpha 1, height 40) that makes the client draw the HUD's front
  boss bar above its back one (fr.influencraft.flux.hud.HudService);
- the boxes of hud.yml (width following their text): icons 13 px tall in one sheet per family (U+E100 compass
  frames, U+E140 Vote Party, biome, town; ids -> characters in hud_icons.json) and the pieces of their background
  (U+E030 left edge, U+E031 8 px of middle, U+E032 1 px of middle, U+E033 right edge), sources in assets/hud/icons/;
- QuinqueFive (5 px capitals, the font of the vanilla menu theme) for the HUD's small texts, in ONE sheet
  (never one glyph per picture: docs/technique/pack-ressources.md). Cells 7 px tall, ink in the top 5 rows: with
  ascent 7 a glyph's top sits on the line's top, like the game's own letters.

Also writes hud_font_widths.json (advance of each character of that sheet) for the server to measure texts.
No dependency (PNG written with zlib). Usage: gen_hud.py
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
# panels drawn by hud.yml (width, height): the card, the position box, the Vote Party box
PANELS = [(102, 32)]
# icons of other plugins' packs, drawn from their own picture: id -> (texture in the pack, source file, character)
EXTERNAL_ICONS = {
    # the RPG world's icon (hologram of the world's portal), shown instead of the biome in the rpg universe
    "rpg": ("influhologrammes:font/hologrammes/monde_rpg_icone.png",
            "/home/influencraft/serveurs/04_labs/plugins/ItemsAdder/contents/influhologrammes/textures/font/hologrammes/monde_rpg_icone.png",
            "\ue150"),
}
# height of the boxes (position, Vote Party, place) and of their icons
BOX_H = 13


def read_png(path):
    """RGBA rows of an 8-bit PNG (RGBA, RGB or palette)."""
    data = open(path, "rb").read()
    i, idat, plte, trns = 8, b"", None, None
    while i < len(data):
        n = struct.unpack(">I", data[i:i + 4])[0]
        kind, body = data[i + 4:i + 8], data[i + 8:i + 8 + n]
        i += 12 + n
        if kind == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", body[:10])
            assert depth == 8, path
        elif kind == b"IDAT":
            idat += body
        elif kind == b"PLTE":
            plte = body
        elif kind == b"tRNS":
            trns = body
    bpp = {6: 4, 2: 3, 3: 1, 0: 1, 4: 2}[ctype]
    raw, stride, prev, p, rows = zlib.decompress(idat), w * bpp, bytearray(w * bpp), 0, []
    for _ in range(h):
        f = raw[p]
        line = bytearray(raw[p + 1:p + 1 + stride])
        p += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        prev = line
        if ctype == 6:
            rows.append([tuple(line[x * 4:x * 4 + 4]) for x in range(w)])
        elif ctype == 2:
            rows.append([tuple(line[x * 3:x * 3 + 3]) + (255,) for x in range(w)])
        elif ctype == 3:
            rows.append([tuple(plte[k * 3:k * 3 + 3]) + ((trns[k] if trns and k < len(trns) else 255),) for k in line])
        else:
            rows.append([(v, v, v, 255) for v in line])
    return w, h, rows


def ink_width(rows):
    """Width up to the last column holding a pixel (as the game measures a bitmap glyph)."""
    return max((x + 1 for row in rows for x, p in enumerate(row) if p[3]), default=0)


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

    # icons of the boxes (one sheet per family, 16 px pictures drawn 13 px tall) and the pieces of a box's
    # background, which the server repeats to the box's width: left edge, 8 px of middle, 1 px of middle, right edge
    icons, providers = {}, []
    families = [("hud_compass", ["compass/compass_%02d" % i for i in range(32)], 0xE100),
                ("hud_icons", ["voteparty", "biome", "ville"], 0xE140)]
    scale = BOX_H / 16.0
    for sheet, names, first in families:
        cells = [read_png(os.path.join(HERE, "icons", n + ".png"))[2] for n in names]
        pixels = [[p for cell in cells for p in cell[y]] for y in range(16)]
        with open(os.path.join(tex, sheet + ".png"), "wb") as f:
            f.write(png(16 * len(cells), 16, pixels))
        chars = ""
        for k, (n, cell) in enumerate(zip(names, cells)):
            ch = chr(first + k)
            chars += ch
            icons[n.split("/")[-1]] = ch
            widths[ch] = int(0.5 + ink_width(cell) * scale) + 1
        providers.append({"type": "bitmap", "file": "flux:font/%s.png" % sheet, "height": BOX_H, "ascent": BOX_H, "chars": [chars]})
    # icons that are another plugin's picture, used where it is (never copied): id -> (texture, file on Labs)
    for n, (texture, path, ch) in EXTERNAL_ICONS.items():
        picture = read_png(path)[2]
        widths[ch] = int(0.5 + ink_width(picture) * BOX_H / len(picture)) + 1
        icons[n] = ch
        providers.append({"type": "bitmap", "file": texture, "height": BOX_H, "ascent": BOX_H, "chars": [ch]})
    panel_cols = {"\ue030": [2, 1], "\ue031": [0] * 8, "\ue032": [0], "\ue033": [1, 2]}  # rows cut at top and bottom
    pixels = [[(0, 0, 0, 0)] * (8 * len(panel_cols)) for _ in range(BOX_H)]
    for k, (ch, cuts) in enumerate(panel_cols.items()):
        for x, cut in enumerate(cuts):
            for y in range(cut, BOX_H - cut):
                pixels[y][8 * k + x] = (0, 0, 0, 39)
        widths[ch] = len(cuts) + 1
    with open(os.path.join(tex, "hud_panel.png"), "wb") as f:
        f.write(png(8 * len(panel_cols), BOX_H, pixels))
    providers.append({"type": "bitmap", "file": "flux:font/hud_panel.png", "height": BOX_H, "ascent": BOX_H, "chars": ["".join(panel_cols)]})
    with open(os.path.join(RES, "hud_icons.json"), "w", encoding="utf-8") as f:
        json.dump(icons, f, separators=(",", ":"))

    advances = {" ": SPACE}
    for n in range(10):
        advances[chr(0xE010 + n)] = -(1 << n)
        advances[chr(0xE020 + n)] = 1 << n
    font = {"providers": [
        {"type": "space", "advances": advances},
        {"type": "bitmap", "file": "flux:font/hud_spacer.png", "height": 40, "ascent": 7, "chars": [""]},
        {"type": "bitmap", "file": "flux:font/hud_text.png", "height": CELL_H, "ascent": CELL_H, "chars": ["".join(l) for l in rows]},
    ] + providers}
    # backgrounds: the translucent rounded panels of the production HUD (black at alpha 39, corners cut by 2 and 1
    # px), at the exact size hud.yml draws them (a sprite is scaled to its size: same size = crisp)
    sprites = os.path.join(RES, "pack", "assets", "flux", "textures", "gui", "sprites", "hud")
    os.makedirs(sprites, exist_ok=True)
    for pw, ph in PANELS:
        rows = []
        for y in range(ph):
            cut = 2 if y in (0, ph - 1) else 1 if y in (1, ph - 2) else 0
            rows.append([(0, 0, 0, 39) if cut <= x < pw - cut else (0, 0, 0, 0) for x in range(pw)])
        with open(os.path.join(sprites, "panel_%dx%d.png" % (pw, ph)), "wb") as f:
            f.write(png(pw, ph, rows))
    os.makedirs(os.path.join(RES, "pack", "assets", "flux", "font"), exist_ok=True)
    with open(os.path.join(RES, "pack", "assets", "flux", "font", "hud.json"), "w", encoding="utf-8") as f:
        json.dump(font, f, indent=2)
    with open(os.path.join(RES, "hud_font_widths.json"), "w", encoding="utf-8") as f:
        json.dump({str(ord(k)): v for k, v in sorted(widths.items())}, f, separators=(",", ":"))
    print("%d characters, sheet %dx%d" % (len(widths), w, h))


if __name__ == "__main__":
    main()
