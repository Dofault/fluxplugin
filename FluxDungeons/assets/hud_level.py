"""The class level of the rpg hud (2026-11-12): a plate filling the 18 px between the life and mana bars, one pixel
taller than them, violet hem on top and fuchsia below (the logo's colours); the level is written on it with the bars'
own digits, on their text row. Imported by gen_hud.py (font fluxdungeons:hud).

Heights are counted from the bottom of the screen; a glyph's top lies 65 + ascent px above it. The bars span rows 40
to 30, the plate 41 to 29.

Glyph: U+E431 plate. FluxDungeons (rpg/RpgHud.java) keeps PLATE / PLATE_X in line.
"""
from PIL import Image

PLATE_W, PLATE_H = 18, 13
PLATE_TOP = 41
PLATE_X = 83                   # line x: the line starts 92 px left of the screen's centre, the bars end at 82 / start at 101


def rgba(h, a=255):
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a


def plate():
    im = Image.new("RGBA", (PLATE_W, PLATE_H), (0, 0, 0, 0))
    px = im.load()
    for y in range(PLATE_H):
        for x in range(PLATE_W):
            if x in (0, PLATE_W - 1) or y in (0, PLATE_H - 1):
                px[x, y] = rgba("#0d0d0d")
            elif y == 1:
                px[x, y] = rgba("#a077dd")
            elif y == PLATE_H - 2:
                px[x, y] = rgba("#c8256a")
            else:
                px[x, y] = rgba("#2a1650")
    return im


if __name__ == "__main__":
    print("plate", PLATE_W, "x", PLATE_H, "top", PLATE_TOP, "line x", PLATE_X)
