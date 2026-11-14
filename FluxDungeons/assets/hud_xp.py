"""The xp bar of the rpg hud (2026-11-14): drawn over the vanilla one, in the line of the life and mana bars and as
wide as both (184 x 6, rows 29 to 24 above the bottom of the screen). Black outline, fill going from the logo's violet
to its fuchsia by steps as it grows, lighter top row. Imported by gen_hud.py (font fluxdungeons:hud).

No picture per step: one glyph is the full bar, and the empty part is laid over it from the right with dark pieces
1, 2, 4 ... 128 px wide, so the bar is exact to the pixel with 9 glyphs.

Glyphs: U+E440 full bar, U+E441 + i = empty piece of 2^i px. FluxDungeons (rpg/RpgHud.java) keeps XP_W in line.
"""
from PIL import Image

XP_W, XP_H = 184, 6
XP_TOP = 29
FILL_W, FILL_H = XP_W - 2, XP_H - 2
PIECES = 8                     # 1 to 128 px
PIECE_CELL = 1 << (PIECES - 1)

OUTLINE = (13, 13, 13, 255)
EMPTY = (26, 11, 48, 255)
STEPS = ["#5b2d9e", "#6a2c96", "#7a2a8c", "#8f2982", "#a62878", "#c8256a", "#e060a0"]


def rgba(h):
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255


def full():
    im = Image.new("RGBA", (XP_W, XP_H), (0, 0, 0, 0))
    px = im.load()
    # fully opaque, corners too: it must hide the vanilla bar under it
    for x in range(XP_W):
        for y in range(XP_H):
            px[x, y] = OUTLINE
    for i in range(FILL_W):
        c = rgba(STEPS[min(len(STEPS) - 1, i * len(STEPS) // FILL_W)])
        light = tuple(min(255, v + 50) for v in c[:3]) + (255,)
        for r in range(FILL_H):
            px[1 + i, 1 + r] = c if r else light
    return im


def pieces():
    """One row per piece, left aligned: the game measures a glyph by its last visible column."""
    im = Image.new("RGBA", (PIECE_CELL, FILL_H * PIECES), (0, 0, 0, 0))
    px = im.load()
    for i in range(PIECES):
        for x in range(1 << i):
            for y in range(FILL_H):
                px[x, i * FILL_H + y] = EMPTY
    return im


if __name__ == "__main__":
    print("xp bar", XP_W, "x", XP_H, "top", XP_TOP)
