"""Rpg hud: a life bar and a mana bar drawn over the hearts and the food (2026-11-11), in the server's one pack.
The bars are opaque and one pixel larger than the vanilla rows on every side: sent in the action bar (drawn after
the hearts and food), they cover them. In the rpg universe the vanilla life, food and saturation stay full, so the
rows underneath never shake nor blink out of the cover. Writes a font of its own into an ItemsAdder pack folder:

    <pack>/resourcepack/assets/fluxdungeons/font/hud.json
    <pack>/resourcepack/assets/fluxdungeons/textures/font/hud_life.png    (one picture per step, STEPS + 1)
    <pack>/resourcepack/assets/fluxdungeons/textures/font/hud_mana.png
    <pack>/resourcepack/assets/fluxdungeons/textures/font/hud_digits.png  (0-9 and /)
    <pack>/resourcepack/assets/fluxdungeons/textures/font/hud_spells.png  (spell slots, hud_spells.py)
    <pack>/resourcepack/assets/fluxdungeons/textures/font/hud_keys.png    (key caps A, F, right click, left click)
    <pack>/resourcepack/assets/fluxdungeons/textures/font/hud_xp.png      (xp bar, full: hud_xp.py)
    <pack>/resourcepack/assets/fluxdungeons/textures/font/hud_xp_empty.png (its empty pieces, 1 to 128 px)

    python3 gen_hud.py <ItemsAdder/contents/fluxdungeons>      (needs Pillow), then /iazip

The action bar's text sits 72 px above the bottom of the screen, the hearts row 39 px; a negative ascent brings the
bars down onto that row, one pixel above it (ASCENT, check in game). Hearts span the 81 px left of the centre (minus
10), food the 81 px right of it: one 83 px bar each from one pixel before, 18 px between them, 184 px in all.

Chars: U+E100 + step = life bar, U+E200 + step = mana bar, U+E300.. = '0'-'9' then '/', U+E400.. spell slots,
U+E480.. key caps (hud_spells.py), U+E431 level plate (hud_level.py), U+E440 xp bar and U+E441.. its empty
pieces (hud_xp.py), U+F001-U+F008 move back 1, 2,
4 ... 128 px, U+F011-U+F018 forward as much. FluxDungeons (rpg/RpgHud.java) writes the same numbers: keep STEPS,
BAR_W and DIGIT_W in line with it. Every digit is padded to 5 px (an almost transparent pixel in its last column),
so each one moves the text by exactly 6 px.
"""
import json
import os
import sys

from PIL import Image

import hud_level
import hud_spells
import hud_xp

STEPS = 40
BAR_W, BAR_H = 83, 11
FILL_X0, FILL_X1, FILL_Y0, FILL_Y1 = 1, 81, 1, 9
# the hotbar's top is 22 px above the bottom: a slot's top there is 72 - 22 = 50 px under the top of the line;
# key caps rise 7 px above the slot (their two last rows lie on its frame)
SPELL_ASCENT = 7 - 50
KEY_ASCENT = SPELL_ASCENT + 7
ASCENT = -25          # top of the hearts row = 72 - 39 = 33 px under the top of the line (ascent 7), minus 1
DIGIT_W, DIGIT_H = 5, 5

OUTLINE = (13, 13, 13, 255)
LIFE_EMPTY = (52, 12, 20, 255)
LIFE = [(255, 160, 160, 255), (255, 130, 130, 255), (255, 110, 110, 255), (255, 92, 92, 255), (247, 78, 82, 255),
        (236, 64, 74, 255), (220, 50, 66, 255), (210, 44, 62, 255), (200, 37, 58, 255)]
MANA_EMPTY = (10, 28, 52, 255)
MANA = [(180, 240, 255, 255), (150, 230, 252, 255), (120, 220, 250, 255), (80, 205, 240, 255), (43, 196, 232, 255),
        (32, 170, 214, 255), (24, 146, 196, 255), (20, 132, 186, 255), (18, 120, 176, 255)]
DIGIT = (255, 255, 255, 255)

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "..", "..", "Flux", "assets", "metiers", "quinquefive.json")
SLASH = ["....#", "...#.", "..#..", ".#...", "#...."]


def bar(step, empty, fill):
    im = Image.new("RGBA", (BAR_W, BAR_H), (0, 0, 0, 0))
    px = im.load()
    # fully opaque, corners too: it must hide the vanilla row under it
    for x in range(BAR_W):
        for y in range(BAR_H):
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
    # no shadow: a digit fills its 5 px cell, so a shadow one row lower only ever fell inside it (dark pixels in
    # the hollow of the 0, 6, 8, 9, under the top of the 1...), never under it
    for i, g in enumerate(glyphs):
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
    spells, spell_count = hud_spells.slots_sheet()
    spells.save(os.path.join(tex, "hud_spells.png"))
    hud_spells.badges_sheet().save(os.path.join(tex, "hud_keys.png"))
    for old in os.listdir(tex):
        if old.startswith("hud_frame"):
            os.remove(os.path.join(tex, old))
    for old in ("hud_gem.png",):
        if os.path.exists(os.path.join(tex, old)):
            os.remove(os.path.join(tex, old))
    hud_level.plate().save(os.path.join(tex, "hud_level.png"))
    hud_xp.full().save(os.path.join(tex, "hud_xp.png"))
    hud_xp.pieces().save(os.path.join(tex, "hud_xp_empty.png"))
    back = {chr(0xF001 + i): -(1 << i) for i in range(8)}
    fwd = {chr(0xF011 + i): (1 << i) for i in range(8)}
    font = {"providers": [
        {"type": "space", "advances": {**back, **fwd}},
        {"type": "bitmap", "file": "fluxdungeons:font/hud_life.png", "ascent": ASCENT, "height": BAR_H,
         "chars": ["".join(chr(0xE100 + s) for s in range(STEPS + 1))]},
        {"type": "bitmap", "file": "fluxdungeons:font/hud_mana.png", "ascent": ASCENT, "height": BAR_H,
         "chars": ["".join(chr(0xE200 + s) for s in range(STEPS + 1))]},
        {"type": "bitmap", "file": "fluxdungeons:font/hud_digits.png", "ascent": ASCENT - 3, "height": DIGIT_H,
         "chars": ["".join(chr(0xE300 + i) for i in range(count))]},
        {"type": "bitmap", "file": "fluxdungeons:font/hud_spells.png", "ascent": SPELL_ASCENT,
         "height": hud_spells.SLOT, "chars": ["".join(chr(0xE400 + i) for i in range(spell_count))]},
        {"type": "bitmap", "file": "fluxdungeons:font/hud_keys.png", "ascent": KEY_ASCENT,
         "height": hud_spells.BADGE_H, "chars": ["".join(chr(hud_spells.KEYS + i) for i in range(len(hud_spells.BADGES)))]},
        # level plate (hud_level.py): top of a glyph = 65 + ascent px above the bottom of the screen
        {"type": "bitmap", "file": "fluxdungeons:font/hud_level.png", "ascent": hud_level.PLATE_TOP - 65,
         "height": hud_level.PLATE_H, "chars": [chr(0xE431)]},
        # xp bar (hud_xp.py): the full bar, then its empty pieces, one per row, on the fill's rows
        {"type": "bitmap", "file": "fluxdungeons:font/hud_xp.png", "ascent": hud_xp.XP_TOP - 65,
         "height": hud_xp.XP_H, "chars": [chr(0xE440)]},
        {"type": "bitmap", "file": "fluxdungeons:font/hud_xp_empty.png", "ascent": hud_xp.XP_TOP - 66,
         "height": hud_xp.FILL_H, "chars": [chr(0xE441 + i) for i in range(hud_xp.PIECES)]},
    ]}
    with open(os.path.join(root, "font", "hud.json"), "w", encoding="utf-8") as f:
        json.dump(font, f, ensure_ascii=True, indent=1)
    print("written:", root)


if __name__ == "__main__":
    main(sys.argv[1])
