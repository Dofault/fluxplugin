"""Background of the rank menu (museum, chosen on 2026-10-28), from the round-4 proposals (gen_v4.py, variant 1).

Layout (6-row chest, hidden inventory):
  row 0          back (0) and menu icon (8), the ceiling lamps between them
  rows 2 to 4    4 tier banners on a gold rod, 3 rank slots each, weakest on top: columns 1, 3, 5, 7
                 (slots 19/28/37, 21/30/39, 23/32/41, 25/34/43)
  row 5          the path runs through the banner tips (waypoints 46, 48, 50, 52)
  inventory row  reward slot of each tier (64, 66, 68, 70)
The path itself (rank to rank to reward) is drawn by Flux in game (menu paths, coloured per step).

Usage: python gen_fond.py <png> [preview x3]  (Pillow)
"""
import os
import sys
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from fond_lib import generique_raccourci, poser, tampon_case  # noqa: E402

W, H = 176, 221
BOTTOM = 178
FRAME = 10
COLS = (1, 3, 5, 7)
RANK_ROWS = (2, 3, 4)
REWARD_ROW = 6
ROD_Y = 38
TIER_COLOURS = ["9AD29A", "4E72E6", "E65C9F", "E65C5C"]  # middle rank of each tier
RANK_COLOURS = ["C0C0C0", "9AD29A", "6AC7C7", "5CA8E6", "4E72E6", "8A5CE6", "D15CE6", "E65C9F", "FFD94D",
                "E6A85C", "E65C5C", "FF0033"]
CONTOUR = (21, 17, 27, 255)
OR, OR_CLAIR, OR_FONCE = (240, 168, 0, 255), (255, 217, 77, 255), (138, 90, 20, 255)


def c(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


def objet(case):
    """Top-left of a slot's item; hidden-inventory rows sit 2 px higher in game."""
    if case < 54:
        return 8 + 18 * (case % 9), 18 + 18 * (case // 9)
    i = case - 54
    return 8 + 18 * (i % 9), 138 + 18 * (i // 9)


def slot(row, col):
    return row * 9 + col if row < 6 else 54 + (row - 6) * 9 + col


def tint(tampon, colour):
    col = c(colour)
    ramp = [CONTOUR] + [mix(CONTOUR, col, t) for t in (.25, .4, .55, .7, .85)] + [col, mix(col, (255, 255, 255, 255), .35)]
    shades = sorted({v for v in tampon.values()}, key=lambda v: sum(v[:3]))
    table = {v: ramp[round(i * (len(ramp) - 1) / max(1, len(shades) - 1))] for i, v in enumerate(shades)}
    return {k: table[v] for k, v in tampon.items()}


def star(im, x, y, col):
    px = im.load()
    for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
        px[x + dx, y + dy] = col


def spotlight(im, cx, top, bottom, spread, colour=(255, 232, 170), strength=34):
    """Cone of light from a lamp down to the panel bottom, fading, with a soft pool; never over the frame."""
    layer = Image.new("RGBA", im.size, (0, 0, 0, 0))
    px = layer.load()
    for y in range(top, bottom):
        t = (y - top) / max(1, bottom - top)
        half = 3 + spread * t
        alpha = strength * (1 - .55 * t)
        for x in range(int(cx - half), int(cx + half) + 1):
            edge = 1 - abs(x - cx) / half
            px[x, y] = colour + (int(alpha * min(1, edge * 2.2)),)
    pool = Image.new("RGBA", im.size, (0, 0, 0, 0))
    ImageDraw.Draw(pool).ellipse([cx - spread - 4, bottom - 12, cx + spread + 4, bottom + 2], colour + (16,))
    layer.alpha_composite(pool)
    alpha = im.split()[3].load()
    for y in range(im.height):
        for x in range(im.width):
            if px[x, y][3] and (alpha[x, y] == 0 or x < 4 or x > im.width - 5 or y < 3 or y > bottom + 2):
                px[x, y] = (0, 0, 0, 0)
    im.alpha_composite(layer)


def background():
    im = generique_raccourci(os.path.join(os.path.dirname(HERE), "menu_generique.png"), 0, BOTTOM)
    case = tampon_case()
    for col in COLS:
        x, _ = objet(col)
        spotlight(im, x + 8, 6, BOTTOM - FRAME, 20)
    d = ImageDraw.Draw(im)
    for t, col in enumerate(COLS):
        x, _ = objet(col)
        x0, x1 = x - 6, x + 21
        tc = c(TIER_COLOURS[t])
        body_bottom = objet(slot(RANK_ROWS[-1], col))[1] + 20
        for xx in range(x0, x1 + 1):
            k = (xx - x0) / (x1 - x0)
            d.line([(xx, ROD_Y), (xx, body_bottom)], mix(mix(CONTOUR, tc, .55), mix(CONTOUR, tc, .3), k))
        d.line([(x0, ROD_Y), (x0, body_bottom)], mix(CONTOUR, tc, .85))
        d.polygon([(x0, body_bottom), (x1, body_bottom), ((x0 + x1) // 2, body_bottom + 11)], mix(CONTOUR, tc, .45))
    d.line([(4, ROD_Y), (171, ROD_Y)], OR)
    d.line([(4, ROD_Y + 1), (171, ROD_Y + 1)], OR_FONCE)
    for t, col in enumerate(COLS):
        x, _ = objet(col)
        cx = x + 8
        d.rectangle([cx - 3, 3, cx + 3, 6], c("2A1250"))
        d.line([(cx - 3, 6), (cx + 3, 6)], OR)
        d.line([(cx - 2, 7), (cx + 2, 7)], OR_CLAIR)
        for k in range(t + 1):
            star(im, cx - 3 * t + 6 * k, ROD_Y + 7, OR_CLAIR)
        for r, row in enumerate(RANK_ROWS):
            poser(im, tint(case, RANK_COLOURS[t * 3 + r]), *objet(slot(row, col)))
        poser(im, tint(case, "F0A800"), *objet(slot(REWARD_ROW, col)))
    for n in (0, 8):  # back and menu icon, first row of the menu
        poser(im, case, *objet(n))
    return im


if __name__ == "__main__":
    im = background()
    im.save(sys.argv[1])
    if len(sys.argv) > 2:
        im.resize((W * 3, H * 3), Image.NEAREST).save(sys.argv[2])
