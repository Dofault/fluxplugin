"""The class level of the rpg hud (2026-11-12): a violet gem centred over the gap between the life and mana bars,
the level written in it. Imported by gen_hud.py (font fluxdungeons:hud).

Heights are counted from the bottom of the screen; a glyph's top lies 65 + ascent px above it. The gem stands on the
bars (its lowest row just above the xp bar), wide enough for three digits.

Glyphs: U+E431 gem, U+E440.. '0'-'9' at the level's height. FluxDungeons (rpg/RpgHud.java) keeps GEM / GEM_X in line.
"""
from PIL import Image

GEM = 29                       # odd: one centre column, on the screen's centre
GEM_TOP = 58                   # rows 58 down to 30, the xp bar ends at 29
LEVEL_TOP = GEM_TOP - (GEM // 2 - 2)
GEM_X = 92 - GEM // 2          # line x of its left edge: the line starts 92 px left of the screen's centre

VIOLET = ("#3d2270", "#6c43b0", "#a077dd", "#d6bbf2")


def rgba(h, a=255):
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a


def gem(size=GEM, ramp=VIOLET, edge="#0d0d0d", shine="#ffffff"):
    """A pixel diamond lit from the top left: a two pixel bevel around a flat middle the digits are written on."""
    r = size // 2
    g = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gp = g.load()
    dark, mid, light, hi = (rgba(c) for c in ramp)
    for y in range(size):
        for x in range(size):
            d = abs(x - r) + abs(y - r)
            if d > r:
                continue
            if d == r:
                gp[x, y] = rgba(edge)
            elif d >= r - 2:
                gp[x, y] = hi if (x < r and y < r) else light if (x >= r and y < r) or (x < r and y >= r) else dark
            else:
                gp[x, y] = mid if y <= r else dark if d > r - 5 else mid
    gp[r - 7, r - 5] = rgba(shine)
    return g


if __name__ == "__main__":
    print("gem", GEM, "top", GEM_TOP, "level top", LEVEL_TOP, "line x", GEM_X)
