"""Frame of the rpg hud (2026-11-12, "arcane circle" look chosen from mockups): a wide magic ring engraved with runes
rising over the life and mana bars, a small ring behind each spell slot, and a violet gem holding the class level.
The hotbar's own transparent slots are never covered: the ring passes above them. Imported by gen_hud.py (font
fluxdungeons:hud).

Coordinates: a canvas of W x H gui pixels whose bottom row is the bottom of the screen and whose centre column CX is
the centre of the screen. The action bar line starts 92 px left of the centre (RpgHud.java), so a canvas column x is
line column x - (CX - 92).

The client puts font glyphs in 256 x 256 sheets: a wider glyph is dropped without a word (and the whole line slides by
its missing advance). The frame is cut in PIECE wide glyphs, each with a nearly transparent pixel in its last column
so the client measures it at exactly its width.

Glyphs: U+E450 + i frame pieces, U+E431 level gem, U+E440.. '0'-'9' at the level's height. FluxDungeons (rpg/RpgHud.java)
keeps FRAME_X / GEM_X in line with frame_box() / GEM: rerun and compare after a change.
"""
import math

from PIL import Image

W, H = 330, 62
CX = W // 2
LINE0 = CX - 92

RING = (179, 118, 229, 210)
RING_FILL = (30, 14, 52, 130)
RUNE = (214, 187, 242, 230)
RUNE_PINK = (224, 96, 160, 200)
SPELL_RING = (179, 118, 229, 150)

# left edges of the spell slots on the canvas (RpgHud: SLOT_A, SLOT_F, SLOT_RIGHT on the line)
SPELL_SLOTS = [LINE0 - 50, LINE0 - 25, LINE0 + 187]
GEM = 19                      # gem size, its top 52 px above the bottom of the screen
GEM_TOP = 52
LEVEL_TOP = GEM_TOP - (GEM // 2 - 2)


def Y(from_bottom):
    return H - from_bottom


def blend(px, x, y, c):
    if not (0 <= x < W and 0 <= y < H):
        return
    r, g, b, a = c
    R, G, B, A = px[x, y]
    na = a + A * (255 - a) // 255
    if na:
        px[x, y] = ((r * a + R * A * (255 - a) // 255) // na, (g * a + G * A * (255 - a) // 255) // na,
                    (b * a + B * A * (255 - a) // 255) // na, na)


def frame():
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    px = im.load()
    cx, cy = CX, Y(20)
    for y in range(Y(24) + 1):
        for x in range(W):
            # an ellipse, wide and low: its top passes over the bars, its ends come down beside the spells
            d = math.hypot((x - cx) / 125, (y - cy) / 30)
            ang = math.degrees(math.atan2(-(y - cy) / 30, (x - cx) / 125))
            if 0.985 <= d < 1.02 or 0.83 <= d < 0.865:
                px[x, y] = RING
            elif 0.865 <= d < 0.985:
                px[x, y] = RING_FILL
                if int(ang) % 6 == 0 and 0.89 <= d <= 0.96:
                    px[x, y] = RUNE
                elif int(ang) % 6 == 3 and 0.91 <= d <= 0.94:
                    px[x, y] = RUNE_PINK
    for x0 in SPELL_SLOTS:
        ccx, ccy = x0 + 11, Y(11)
        for y in range(H):
            for x in range(W):
                if 14.5 <= math.hypot(x - ccx, y - ccy) < 15.5:
                    blend(px, x, y, SPELL_RING)
    # the hotbar and the xp bar stay clear (the action bar is drawn over the items)
    for y in range(Y(29), H):
        for x in range(CX - 91, CX + 91):
            px[x, y] = (0, 0, 0, 0)
    return im


def frame_box():
    """The frame cropped to what it draws: (image, line x of its left edge, gui px of its top above the bottom)."""
    im = frame()
    l, t, r, b = im.getbbox()
    # keep the bottom row on the bottom of the screen so heights stay simple
    return im.crop((l, t, r, H)), l - LINE0, H - t


PIECE = 128


def frame_pieces():
    """The frame cut in glyphs no wider than PIECE: (pieces, line x of the first, top above the bottom, width)."""
    im, x, top = frame_box()
    pieces = []
    for i in range(0, im.width, PIECE):
        p = im.crop((i, 0, min(im.width, i + PIECE), im.height))
        px = p.load()
        last = p.width - 1
        if all(px[last, y][3] == 0 for y in range(p.height)):
            px[last, 0] = (0, 0, 0, 1)
        pieces.append(p)
    return pieces, x, top, im.width


VIOLET = ("#3d2270", "#6c43b0", "#a077dd", "#d6bbf2")


def rgba(h, a=255):
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a


def gem(size=GEM, ramp=VIOLET, edge="#0d0d0d", shine="#ffffff"):
    """A pixel diamond lit from the top left."""
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
                gp[x, y] = mid if y < r + 2 else dark if d > r - 5 and y > r else mid
    gp[r - r // 2 + 1, r - r // 2 + 2] = rgba(shine)
    return g


GEM_X = 92 - GEM // 2         # line x of the gem's left edge (centred on the screen)


if __name__ == "__main__":
    im, x, top = frame_box()
    print("frame", im.size, "line x", x, "top", top, "gem x", GEM_X, "level top", LEVEL_TOP)
