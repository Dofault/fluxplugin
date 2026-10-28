"""FluxTags: the tag styles (2026-10-28), shared by the preview (gen_apercu.py) and the pack (gen_tags.py).

A tag is 9 px high, like the rarity tags of the chat (influtags), with the QuinqueFive capitals of the menu titles.
Every style is a function (name, colour, tier 0-5) -> image; STYLES maps the style names of FluxTags' config.
"""
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "Flux", "assets"))
from fond_lib import text, text_width  # noqa: E402

RARITIES = [("Commun", "9a9a9a"), ("Unique", "3fae4a"), ("Épique", "62b6e8"), ("Mythique", "b45ae6"),
            ("Légendaire", "e8b13a"), ("Fabuleux", "e5484d")]
DARK = (24, 19, 33, 255)
GREY = (200, 200, 205, 255)
WHITE = (255, 255, 255, 255)


def c(h, a=255):
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


def label_width(name, extra=0):
    return text_width(name) + 6 + extra


def bordure(name, colour, tier):
    """Grey name on a dark plate, frame in the rarity colour (2 px sides from Mythique), corner sparks at Fabuleux."""
    col = c(colour)
    w = label_width(name)
    im = Image.new("RGBA", (w, 9), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, w - 1, 8), fill=col)
    side = 1 if tier < 3 else 2  # thicker sides from Mythique, top and bottom stay 1 px (the name needs 7 px)
    d.rectangle((side, 1, w - 1 - side, 7), fill=DARK)
    if tier >= 5:
        for x, y in ((0, 0), (w - 1, 0), (0, 8), (w - 1, 8)):
            im.putpixel((x, y), WHITE)
    text(im, name, w // 2, 2, colour=GREY, shadow=(0, 0, 0, 0))
    return im


def plein(name, colour, tier):
    """Plate in the rarity colour, bevel (light top, dark bottom), white name with a dark shadow: today's tags."""
    col = c(colour)
    w = label_width(name)
    im = Image.new("RGBA", (w, 9), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, w - 1, 8), fill=mix(col, (0, 0, 0, 255), .45))
    d.rectangle((1, 1, w - 2, 7), fill=col)
    d.line((1, 1, w - 2, 1), fill=mix(col, (255, 255, 255, 255), .45))
    d.line((1, 7, w - 2, 7), fill=mix(col, (0, 0, 0, 255), .25))
    text(im, name, w // 2, 2, colour=WHITE, shadow=mix(col, (0, 0, 0, 255), .55))
    return im


GEMS = [  # 5x5 marks growing with the tier
    [".....", ".....", "..#..", ".....", "....."],
    [".....", "..#..", ".###.", "..#..", "....."],
    ["..#..", ".###.", "#####", ".###.", "..#.."],
    ["..#..", ".#.#.", "#.#.#", ".#.#.", "..#.."],
    ["#.#.#", ".###.", "##.##", ".###.", "#.#.#"],
    ["..#..", "#####", ".###.", ".#.#.", "#...#"],
]


def gemme(name, colour, tier):
    """Chosen 2026-10-28: dark plate of « bordure », frame of « plein » (the rarity colour, darkened), white name and a
    rarity gem on the left that grows with the tier."""
    col = c(colour)
    w = label_width(name, 6)
    im = Image.new("RGBA", (w, 9), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, w - 1, 8), fill=mix(col, (0, 0, 0, 255), .45))
    d.rectangle((1, 1, w - 2, 7), fill=DARK)
    for y, row in enumerate(GEMS[tier]):
        for x, ch in enumerate(row):
            if ch == "#":
                im.putpixel((2 + x, 2 + y), col)
    text(im, name, 8 + (w - 8) // 2, 2, colour=WHITE, shadow=(0, 0, 0, 0))
    return im


STYLES = {"bordure": bordure, "plein": plein, "gemme": gemme}
