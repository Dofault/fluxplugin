#!/usr/bin/env python3
"""FluxTags: 5 proposals for the background of /tags (2026-10-29), with the half-size tags.

Layout shared by every variant (FluxTags reads TAG_X, the list slots are in menus/tags.yml):
  row 0       back (0), title « TAGS », menu icon (8)
  rows 1 to 5 one tag per row: its button in column 2 (slots 11, 20, 29, 38, 47), its image (9 px high, the chat
              size) drawn in the title from x = TAG_X; the strip is as wide as the widest tag, centred in the window
  hidden inv. first row: previous page (57), next page (59), on a small plate under the window

    <venv with Pillow>/python3 gen_fonds.py <plugins dir of the server> <out dir>
Writes fond_<n>.png and the comparison sheet variantes_tags.png (a few real tags of the pack placed on each).
"""
import math
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "Flux", "assets"))
from fond_lib import generique_raccourci, poser, tampon_case, text, text_width, title  # noqa: E402

W, H = 176, 221
GENERIC = os.path.join(HERE, "..", "..", "Flux", "assets", "menu_generique.png")
ROWS = 5
COLUMN = 2                 # column of the tag buttons
TAG_X = 64                 # window x of the tag images
TAG_H = 9
INV_Y = 139                # item y of the hidden inventory's first row in game
ARROWS = (57, 59)          # slots of the page arrows (hidden inventory, first row)
OUTLINE, RIM, MID = (21, 17, 27, 255), (127, 102, 163, 255), (89, 70, 116, 255)
DARK = [(63, 50, 83, 255), (52, 41, 66, 255), (44, 35, 57, 255), (35, 28, 46, 255), (27, 21, 39, 255)]
GOLD, GOLD_LIGHT, PINK = (240, 168, 0, 255), (255, 217, 77, 255), (224, 96, 160, 255)
WHITE = (255, 255, 255, 255)


def item_y(row):
    return 18 + 18 * row


def tag_top(row):
    """Window y of the top of a row's tag: centred on the item (16 px)."""
    return item_y(row) + (16 - TAG_H + 1) // 2


def button_x():
    return 8 + 18 * COLUMN


def arrow_x(slot):
    return 8 + 18 * (slot - 54)


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


def tag_width(plugins):
    """Widest tag at the menu size (9 px high)."""
    folder = os.path.join(plugins, "ItemsAdder", "contents", "fluxtags", "textures", "tags")
    best = 0
    for f in os.listdir(folder):
        im = Image.open(os.path.join(folder, f))
        best = max(best, round(im.width * TAG_H / im.height))
    return best


def strip_box(maxw, row, pad=3):
    y = item_y(row)
    return button_x() - 3, y - 2, TAG_X + maxw + pad, y + 17


def base():
    im = generique_raccourci(GENERIC, 0, 131)
    title(im, "Tags", item_y(0))
    return im


def seats(im):
    case = tampon_case()
    for row in range(1, ROWS + 1):
        poser(im, case, button_x(), item_y(row))


def arrow_plate(im, fill=DARK[2], rim=RIM):
    """Small plate under the window holding the page arrows."""
    d = ImageDraw.Draw(im)
    x0, x1 = arrow_x(ARROWS[0]) - 5, arrow_x(ARROWS[1]) + 20
    d.rounded_rectangle((x0, INV_Y - 5, x1, INV_Y + 20), radius=5, fill=OUTLINE)
    d.rounded_rectangle((x0 + 1, INV_Y - 4, x1 - 1, INV_Y + 19), radius=4, fill=rim)
    d.rounded_rectangle((x0 + 2, INV_Y - 3, x1 - 2, INV_Y + 18), radius=3, fill=fill)
    case = tampon_case()
    for s in ARROWS:
        poser(im, case, arrow_x(s), INV_Y)
    cx = (arrow_x(ARROWS[0]) + arrow_x(ARROWS[1]) + 16) // 2
    for dx in (-3, 0, 3):
        d.point((cx + dx, INV_Y + 8), fill=RIM if dx else GOLD_LIGHT)


def star(d, cx, cy, r, colour):
    pts = []
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5
        rr = r if i % 2 == 0 else r * 0.45
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    d.polygon(pts, fill=colour)


def sparkle(d, x, y, colour=GOLD_LIGHT):
    d.point((x, y), fill=WHITE)
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        d.point((x + dx, y + dy), fill=colour)


# ---------------------------------------------------------------- variants

def v1(maxw):
    """Vitrine: velvet grooves of a display case, a gold rail under each tag, glass reflections across the list."""
    im = base()
    d = ImageDraw.Draw(im)
    x0, y0, _, _ = strip_box(maxw, 1)
    _, _, x1, y1 = strip_box(maxw, ROWS)
    d.rounded_rectangle((x0 - 3, y0 - 3, x1 + 3, y1 + 3), radius=4, fill=OUTLINE)
    d.rounded_rectangle((x0 - 2, y0 - 2, x1 + 2, y1 + 2), radius=3, fill=GOLD)
    d.rounded_rectangle((x0 - 1, y0 - 1, x1 + 1, y1 + 1), radius=2, fill=DARK[4])
    for row in range(1, ROWS + 1):
        bx0, by0, bx1, by1 = strip_box(maxw, row)
        for y in range(by0, by1 + 1):
            d.line((bx0, y, bx1, y), fill=mix(DARK[1], DARK[4], (y - by0) / (by1 - by0)))
        d.line((TAG_X - 2, by1 - 3, bx1 - 2, by1 - 3), fill=mix(GOLD, OUTLINE, .35))
    for k in range(3):  # glass reflections
        sx = x0 + 30 + k * 34
        d.line((sx, y0, sx - 22, y1), fill=(255, 255, 255, 18) if k != 1 else (255, 255, 255, 28))
    seats(im)
    arrow_plate(im, DARK[3], GOLD)
    return im


def v2(maxw):
    """Rubans: every tag lies on a violet ribbon with a notched tail, small gold sparkles around the list."""
    im = base()
    d = ImageDraw.Draw(im)
    for row in range(1, ROWS + 1):
        bx0, by0, bx1, by1 = strip_box(maxw, row)
        cy = (by0 + by1) // 2
        top, bot = cy - 6, cy + 6
        d.polygon([(bx0 + 2, top), (bx1 + 6, top), (bx1 + 1, cy), (bx1 + 6, bot), (bx0 + 2, bot)], fill=OUTLINE)
        d.polygon([(bx0 + 3, top + 1), (bx1 + 4, top + 1), (bx1 - 1, cy), (bx1 + 4, bot - 1), (bx0 + 3, bot - 1)], fill=MID)
        d.line((bx0 + 3, top + 1, bx1 + 2, top + 1), fill=RIM)
        d.line((bx0 + 3, bot - 1, bx1 + 2, bot - 1), fill=DARK[1])
        d.rectangle((TAG_X - 3, top + 2, bx1 - 4, bot - 2), fill=DARK[3])
    for x, y in ((30, 30), (150, 46), (24, 104), (154, 96), (148, 66)):
        sparkle(d, x, y)
    seats(im)
    arrow_plate(im)
    return im


def v3(maxw):
    """Constellation: the list area is a night sky with small stars, the tags on glassy plates outlined in the
    theme's light violet."""
    im = base()
    d = ImageDraw.Draw(im)
    x0, y0, _, _ = strip_box(maxw, 1)
    _, _, x1, y1 = strip_box(maxw, ROWS)
    sky = (x0 - 8, y0 - 4, x1 + 8, y1 + 4)
    d.rounded_rectangle(sky, radius=5, fill=OUTLINE)
    for y in range(sky[1] + 1, sky[3]):
        d.line((sky[0] + 1, y, sky[2] - 1, y), fill=mix((20, 14, 40, 255), (46, 22, 66, 255), (y - sky[1]) / (sky[3] - sky[1])))
    seed = 7
    for _ in range(46):
        seed = (seed * 1103515245 + 12345) % (1 << 31)
        sx = sky[0] + 2 + seed % (sky[2] - sky[0] - 4)
        seed = (seed * 1103515245 + 12345) % (1 << 31)
        sy = sky[1] + 2 + seed % (sky[3] - sky[1] - 4)
        d.point((sx, sy), fill=(200, 180, 230, 255) if seed % 3 else GOLD_LIGHT)
    for row in range(1, ROWS + 1):
        bx0, by0, bx1, by1 = strip_box(maxw, row)
        d.rounded_rectangle((TAG_X - 3, by0 + 2, bx1, by1 - 2), radius=3, fill=(255, 255, 255, 26), outline=(179, 118, 229, 150))
    star(d, sky[0] + 6, sky[1] + 7, 3, GOLD_LIGHT)
    star(d, sky[2] - 6, sky[3] - 7, 3, GOLD_LIGHT)
    seats(im)
    arrow_plate(im, (20, 14, 40, 255))
    return im


def v4(maxw):
    """Écussons: a fabric board (fine weave) where every tag is sewn in a stitched outline, pink stitches."""
    im = base()
    d = ImageDraw.Draw(im)
    x0, y0, _, _ = strip_box(maxw, 1)
    _, _, x1, y1 = strip_box(maxw, ROWS)
    board = (x0 - 5, y0 - 4, x1 + 5, y1 + 4)
    d.rounded_rectangle(board, radius=4, fill=OUTLINE)
    d.rounded_rectangle((board[0] + 1, board[1] + 1, board[2] - 1, board[3] - 1), radius=3, fill=DARK[1])
    for y in range(board[1] + 2, board[3] - 1):
        for x in range(board[0] + 2, board[2] - 1):
            if (x + y) % 4 == 0:
                d.point((x, y), fill=DARK[0])
            elif (x - y) % 4 == 0:
                d.point((x, y), fill=DARK[2])
    for row in range(1, ROWS + 1):
        bx0, by0, bx1, by1 = strip_box(maxw, row)
        r = (TAG_X - 4, by0 + 2, bx1 - 1, by1 - 2)
        d.rectangle(r, fill=DARK[3])
        for x in range(r[0] + 1, r[2], 3):
            d.point((x, r[1] - 1), fill=PINK)
            d.point((x, r[3] + 1), fill=PINK)
        for y in range(r[1] + 1, r[3], 3):
            d.point((r[0] - 1, y), fill=PINK)
            d.point((r[2] + 1, y), fill=PINK)
    seats(im)
    arrow_plate(im, DARK[1], PINK)
    return im


def v5(maxw):
    """Présentoir: back wall with light vertical stripes, every tag on a small shelf with gold brackets."""
    im = base()
    d = ImageDraw.Draw(im)
    x0, y0, _, _ = strip_box(maxw, 1)
    _, _, x1, y1 = strip_box(maxw, ROWS)
    wall = (x0 - 4, y0 - 4, x1 + 4, y1 + 4)
    d.rounded_rectangle(wall, radius=3, fill=OUTLINE)
    for x in range(wall[0] + 1, wall[2]):
        d.line((x, wall[1] + 1, x, wall[3] - 1), fill=DARK[2] if (x // 4) % 2 else DARK[3])
    for row in range(1, ROWS + 1):
        bx0, by0, bx1, by1 = strip_box(maxw, row)
        shelf = by1 - 3
        d.rectangle((TAG_X - 4, shelf, bx1, shelf + 1), fill=MID)
        d.line((TAG_X - 4, shelf, bx1, shelf), fill=RIM)
        d.line((TAG_X - 4, shelf + 2, bx1, shelf + 2), fill=OUTLINE)
        for bx in (TAG_X + 2, bx1 - 6):
            d.line((bx, shelf + 3, bx + 2, shelf + 3), fill=GOLD)
            d.point((bx + 1, shelf + 4), fill=GOLD)
    star(d, (wall[0] + wall[2]) // 2, wall[3] + 1, 2, GOLD_LIGHT)
    seats(im)
    arrow_plate(im, DARK[2], MID)
    return im


VARIANTS = [("1 vitrine", v1), ("2 rubans", v2), ("3 constellation", v3), ("4 ecussons", v4), ("5 presentoir", v5)]
SAMPLE = [("rang_influenceur", False), ("metier_mineur_6", False), ("metier_sylviculteur_3", False),
          ("beta", False), ("metier_pecheur_1", True)]


def preview(im, plugins, k=3):
    """The background as in game, k times bigger (the client draws a 9 px glyph from its full-size image, so the
    rank tags stay sharp): tags on the rows (the last one locked, dark), grey squares for items."""
    view = Image.new("RGBA", (W, H - 40), (58, 58, 58, 255))
    view.alpha_composite(im.crop((0, 0, W, H - 40)))
    d = ImageDraw.Draw(view)
    for row in range(1, ROWS + 1):
        d.rectangle((button_x() + 3, item_y(row) + 3, button_x() + 12, item_y(row) + 12), fill=(150, 150, 150, 255))
    for s in ARROWS:
        d.rectangle((arrow_x(s) + 4, INV_Y + 4, arrow_x(s) + 11, INV_Y + 11), fill=(150, 150, 150, 255))
    for x in (8, 152):
        d.rectangle((x + 3, 21, x + 12, 30), fill=(150, 150, 150, 255))
    view = view.resize((W * k, (H - 40) * k), Image.NEAREST)
    folder = os.path.join(plugins, "ItemsAdder", "contents", "fluxtags", "textures", "tags")
    for row, (gid, locked) in enumerate(SAMPLE, start=1):
        tag = Image.open(os.path.join(folder, gid + ".png")).convert("RGBA")
        h = TAG_H * k
        tag = tag.resize((round(tag.width * h / tag.height), h), Image.NEAREST if tag.height <= TAG_H else Image.LANCZOS)
        if locked:
            tag = Image.merge("RGBA", [b.point(lambda v: v * 85 // 255) for b in tag.split()[:3]] + [tag.split()[3]])
        view.alpha_composite(tag, (TAG_X * k, tag_top(row) * k))
    return view


if __name__ == "__main__":
    plugins, out = sys.argv[1], sys.argv[2]
    maxw = tag_width(plugins)
    tiles = []
    for name, fn in VARIANTS:
        im = fn(maxw)
        im.save(os.path.join(out, "fond_%s.png" % name.split()[0]))
        tiles.append((name, preview(im, plugins)))
    th = (H - 40) * 3
    sheet = Image.new("RGBA", (W * 3 * 3 + 60, (th + 40) * 2), (20, 16, 28, 255))
    for i, (name, t) in enumerate(tiles):
        x, y = (i % 3) * (W * 3 + 30), (i // 3) * (th + 40)
        sheet.alpha_composite(t, (x, y + 30))
        lbl = Image.new("RGBA", (text_width(name) + 2, 7), (0, 0, 0, 0))
        text(lbl, name, lbl.width // 2, 0, colour=WHITE)
        sheet.alpha_composite(lbl.resize((lbl.width * 3, lbl.height * 3), Image.NEAREST), (x + 6, y + 4))
    sheet.save(os.path.join(out, "variantes_tags.png"))
    print("max tag width", maxw, "->", out)
