#!/usr/bin/env python3
"""FluxTags: background of /tags, « rubans » chosen 2026-10-29 among 5 proposals (vitrine, rubans, constellation,
écussons, présentoir).

Layout (FluxTags reads TAG_X, the list slots are in menus/tags.yml):
  title strip above the chest (the frame starts LIFT px above the window): title « TAGS »
  row 0       back (0) and menu icon (8), the first tag between them
  rows 0 to 5 one tag per row on a violet ribbon with a notched tail: its button in column 1 (slots 1, 10, 19, 28, 37,
              46), its image (9 px high, the chat size) drawn in the title from x = TAG_X; the ribbon is as long as
              the widest tag
  hidden inv. first row: previous page (57), next page (59), inside the same frame (it reaches down to them)
The image starts LIFT px above the window (its glyph's y_position is 13 + LIFT).

    <venv with Pillow>/python3 gen_fonds.py <plugins dir of the server> <out dir>
gen_tags.py writes the background into the pack; this script alone writes a preview (fond_tags.png, three times
bigger, real tags of the pack placed on it).
"""
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "Flux", "assets"))
from fond_lib import generique_raccourci, poser, tampon_case, title  # noqa: E402

W, H = 176, 221
GENERIC = os.path.join(HERE, "..", "..", "Flux", "assets", "menu_generique.png")
ROWS = 6                   # tag rows, chest rows 0 to 5
LIFT = 9                   # the frame starts half a slot above the window
COLUMN = 1                 # column of the tag buttons
TAG_X = 46                 # window x of the tag images
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
    return item_y(row) + (16 - TAG_H - 1) // 2  # one pixel above the centre: checked in game 2026-10-29


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


def seats(im):
    case = tampon_case()
    for row in range(ROWS):
        poser(im, case, button_x(), item_y(row))


def sparkle(d, x, y, colour=GOLD_LIGHT):
    d.point((x, y), fill=WHITE)
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        d.point((x + dx, y + dy), fill=colour)


def background(maxw):
    """Generic frame from half a slot above the window down to the page arrows, title in the window's title strip,
    one ribbon per row, small gold sparkles around the list. Drawn in window coordinates, then moved down LIFT px."""
    frame = generique_raccourci(GENERIC, 0, INV_Y + 21 + LIFT)
    im = Image.new("RGBA", (W, H - LIFT), (0, 0, 0, 0))
    title(im, "Tags", item_y(-1))
    d = ImageDraw.Draw(im)
    for row in range(ROWS):
        bx0, by0, bx1, by1 = strip_box(maxw, row)
        cy = (by0 + by1) // 2
        top, bot = cy - 6, cy + 6
        d.polygon([(bx0 + 2, top), (bx1 + 6, top), (bx1 + 1, cy), (bx1 + 6, bot), (bx0 + 2, bot)], fill=OUTLINE)
        d.polygon([(bx0 + 3, top + 1), (bx1 + 4, top + 1), (bx1 - 1, cy), (bx1 + 4, bot - 1), (bx0 + 3, bot - 1)], fill=MID)
        d.line((bx0 + 3, top + 1, bx1 + 2, top + 1), fill=RIM)
        d.line((bx0 + 3, bot - 1, bx1 + 2, bot - 1), fill=DARK[1])
        d.rectangle((TAG_X - 3, top + 2, bx1 - 4, bot - 2), fill=DARK[3])
    for x, y in ((14, 52), (156, 52), (14, 104), (154, 96), (148, 74)):
        sparkle(d, x, y)
    seats(im)
    case = tampon_case()
    for s in ARROWS:
        poser(im, case, arrow_x(s), INV_Y)
    frame.alpha_composite(im, (0, LIFT))
    return frame


SAMPLE = [("rang_influenceur", False), ("metier_mineur_6", False), ("metier_sylviculteur_3", False),
          ("beta", False), ("createur", False), ("metier_pecheur_1", True)]


def preview(im, plugins, k=3):
    """The background as in game, k times bigger (the client draws a 9 px glyph from its full-size image, so the
    rank tags stay sharp): tags on the rows (the last one locked, dark), grey squares for items."""
    h = INV_Y + 30 + LIFT
    view = Image.new("RGBA", (W, h), (58, 58, 58, 255))  # the dimmed world behind the window
    view.alpha_composite(im.crop((0, 0, W, h)))
    d = ImageDraw.Draw(view)

    def item(x, y):
        d.rectangle((x + 3, y + LIFT + 3, x + 12, y + LIFT + 12), fill=(150, 150, 150, 255))

    for row in range(ROWS):
        item(button_x(), item_y(row))
    for s in ARROWS:
        item(arrow_x(s), INV_Y)
    for x in (8, 152):
        item(x, item_y(0))
    view = view.resize((W * k, h * k), Image.NEAREST)
    folder = os.path.join(plugins, "ItemsAdder", "contents", "fluxtags", "textures", "tags")
    for row, (gid, locked) in enumerate(SAMPLE):
        tag = Image.open(os.path.join(folder, gid + ".png")).convert("RGBA")
        th = TAG_H * k
        tag = tag.resize((round(tag.width * th / tag.height), th), Image.NEAREST if tag.height <= TAG_H else Image.LANCZOS)
        if locked:
            tag = Image.merge("RGBA", [b.point(lambda v: v * 85 // 255) for b in tag.split()[:3]] + [tag.split()[3]])
        view.alpha_composite(tag, (TAG_X * k, (tag_top(row) + LIFT) * k))
    return view


if __name__ == "__main__":
    plugins, out = sys.argv[1], sys.argv[2]
    maxw = tag_width(plugins)
    preview(background(maxw), plugins).save(os.path.join(out, "fond_tags.png"))
    print("max tag width", maxw, "->", out)
