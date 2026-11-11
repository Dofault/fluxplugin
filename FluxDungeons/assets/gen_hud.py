"""Rpg hud: a life bar and a mana bar drawn exactly where the hearts and the food are (2026-11-11). The hearts, food
and armour themselves are hidden by an overlay pack FluxDungeons sends to players of the rpg universe. Writes a font
of its own into an ItemsAdder pack folder:

    <pack>/resourcepack/assets/fluxdungeons/font/hud.json
    <pack>/resourcepack/assets/fluxdungeons/textures/font/hud_life.png    (one picture per step, STEPS + 1)
    <pack>/resourcepack/assets/fluxdungeons/textures/font/hud_mana.png
    <pack>/resourcepack/assets/fluxdungeons/textures/font/hud_digits.png  (0-9 and /)

    python3 gen_hud.py <ItemsAdder/contents/fluxdungeons>      (needs Pillow), then /iazip

The bars are sent in the action bar: its text sits 68 px above the bottom of the screen, the hearts row 39 px; a
negative ascent brings the bars down onto that row (ASCENT, tuned in game). Hearts span the 81 px left of the centre
(minus 10), food the 81 px right of it: one bar each, a 20 px gap between them, 182 px in all (the hotbar).

Chars: U+E100 + step = life bar, U+E200 + step = mana bar, U+E300.. = '0'-'9' then '/', U+F001-U+F008 move back 1, 2,
4 ... 128 px, U+F011-U+F018 forward as much. FluxDungeons (rpg/RpgHud.java) writes the same numbers: keep STEPS,
BAR_W and DIGIT_W in line with it. Every digit is padded to 5 px (an almost transparent pixel in its last column),
so each one moves the text by exactly 6 px.
"""
import json
import os
import sys

from PIL import Image

STEPS = 40
BAR_W, BAR_H = 81, 9
FILL_X0, FILL_X1, FILL_Y0, FILL_Y1 = 1, 79, 1, 7
ASCENT = -26          # top of the bar on the hearts row (68 - 39 - 7 = 22, plus the 4 px of the action bar line)
DIGIT_W, DIGIT_H = 5, 5

OUTLINE = (13, 13, 13, 255)
LIFE_EMPTY = (52, 12, 20, 255)
LIFE = [(255, 150, 150, 255), (255, 110, 110, 255), (255, 92, 92, 255), (247, 78, 82, 255), (236, 64, 74, 255),
        (220, 50, 66, 255), (200, 37, 58, 255)]
MANA_EMPTY = (10, 28, 52, 255)
MANA = [(170, 236, 255, 255), (120, 220, 250, 255), (80, 205, 240, 255), (43, 196, 232, 255), (32, 170, 214, 255),
        (24, 146, 196, 255), (18, 120, 176, 255)]
DIGIT, DIGIT_SHADOW = (255, 255, 255, 255), (13, 13, 13, 255)

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "..", "..", "Flux", "assets", "metiers", "quinquefive.json")
SLASH = ["....#", "...#.", "..#..", ".#...", "#...."]


def bar(step, empty, fill):
    im = Image.new("RGBA", (BAR_W, BAR_H), (0, 0, 0, 0))
    px = im.load()
    for x in range(BAR_W):
        for y in range(BAR_H):
            if (x in (0, BAR_W - 1)) and (y in (0, BAR_H - 1)):
                continue
            px[x, y] = OUTLINE if x in (0, BAR_W - 1) or y in (0, BAR_H - 1) else empty
    width = FILL_X1 - FILL_X0 + 1
    filled = round(width * step / STEPS)
    for i in range(filled):
        for r, y in enumerate(range(FILL_Y0, FILL_Y1 + 1)):
            px[FILL_X0 + i, y] = fill[r]
    return im


def sheet(empty, fill):
    im = Image.new("RGBA", (BAR_W * (STEPS + 1), BAR_H), (0, 0, 0, 0))
    for s in range(STEPS + 1):
        im.paste(bar(s, empty, fill), (s * BAR_W, 0))
    return im


def digits():
    font = json.load(open(FONT))
    glyphs = [font[c] for c in "0123456789"] + [SLASH]
    im = Image.new("RGBA", (DIGIT_W * len(glyphs), DIGIT_H), (0, 0, 0, 0))
    px = im.load()
    for i, g in enumerate(glyphs):
        for dy, row in enumerate(g):
            for dx, ch in enumerate(row):
                if ch == "#" and dy + 1 < DIGIT_H:
                    px[i * DIGIT_W + dx, dy + 1] = DIGIT_SHADOW
        for dy, row in enumerate(g):
            for dx, ch in enumerate(row):
                if ch == "#":
                    px[i * DIGIT_W + dx, dy] = DIGIT
        # every glyph as wide as the cell: the game measures a glyph by its last visible column
        x = i * DIGIT_W + DIGIT_W - 1
        if px[x, 0][3] == 0:
            px[x, 0] = (0, 0, 0, 1)
    return im, len(glyphs)


def main(pack):
    root = os.path.join(pack, "resourcepack", "assets", "fluxdungeons")
    tex = os.path.join(root, "textures", "font")
    os.makedirs(tex, exist_ok=True)
    os.makedirs(os.path.join(root, "font"), exist_ok=True)
    sheet(LIFE_EMPTY, LIFE).save(os.path.join(tex, "hud_life.png"))
    sheet(MANA_EMPTY, MANA).save(os.path.join(tex, "hud_mana.png"))
    dig, count = digits()
    dig.save(os.path.join(tex, "hud_digits.png"))
    back = {chr(0xF001 + i): -(1 << i) for i in range(8)}
    fwd = {chr(0xF011 + i): (1 << i) for i in range(8)}
    font = {"providers": [
        {"type": "space", "advances": {**back, **fwd}},
        {"type": "bitmap", "file": "fluxdungeons:font/hud_life.png", "ascent": ASCENT, "height": BAR_H,
         "chars": ["".join(chr(0xE100 + s) for s in range(STEPS + 1))]},
        {"type": "bitmap", "file": "fluxdungeons:font/hud_mana.png", "ascent": ASCENT, "height": BAR_H,
         "chars": ["".join(chr(0xE200 + s) for s in range(STEPS + 1))]},
        {"type": "bitmap", "file": "fluxdungeons:font/hud_digits.png", "ascent": ASCENT - 2, "height": DIGIT_H,
         "chars": ["".join(chr(0xE300 + i) for i in range(count))]},
    ]}
    with open(os.path.join(root, "font", "hud.json"), "w", encoding="utf-8") as f:
        json.dump(font, f, ensure_ascii=True, indent=1)
    print("written:", root)


if __name__ == "__main__":
    main(sys.argv[1])
