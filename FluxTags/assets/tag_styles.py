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
    """Chosen 2026-10-28, plate retinted 2026-10-30 (the black plate was too dark): frame of « plein » (the rarity
    colour, darkened), plate a deep shade of the rarity colour with a lighter top line, white name with a shadow of
    that colour, and a rarity gem on the left that grows with the tier."""
    col = c(colour)
    w = label_width(name, 6)
    im = Image.new("RGBA", (w, 9), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, w - 1, 8), fill=mix(col, (0, 0, 0, 255), .45))
    d.rectangle((1, 1, w - 2, 7), fill=mix(col, (0, 0, 0, 255), .62))
    d.line((1, 1, w - 2, 1), fill=mix(col, (0, 0, 0, 255), .47))
    gem = mix(col, (255, 255, 255, 255), .25)  # a touch lighter, to stand out of the tinted plate
    for y, row in enumerate(GEMS[tier]):
        for x, ch in enumerate(row):
            if ch == "#":
                im.putpixel((2 + x, 2 + y), gem)
    text(im, name, 8 + (w - 8) // 2, 2, colour=WHITE, shadow=mix(col, (0, 0, 0, 255), .8))
    return im


STYLES = {"bordure": bordure, "plein": plein, "gemme": gemme}


def outlined_text(im, name, x, y, colour, outline):
    """QuinqueFive capitals, top left at (x, y), with a 1 px outline on all 8 sides (accents included)."""
    from fond_lib import _glyph
    pts = []
    for ch in name.upper():
        g, extra = _glyph(ch)
        if not g:
            x += 4
            continue
        pts += [(x + dx, y + dy) for dy, r in enumerate(g) for dx, v in enumerate(r) if v == "#"]
        pts += [(x + dx, y + dy) for dx, dy in extra]
        x += len(g[0]) + 1
    for a, b in pts:
        for ox in (-1, 0, 1):
            for oy in (-1, 0, 1):
                im.putpixel((a + ox, b + oy), outline)
    for p in pts:
        im.putpixel(p, colour)


def plaque(name, colour, tier=None):
    """14 px tag from the one drawn by the owner on 2026-10-30 (« Éminent », 16 px), made 2 px lower the same day (one
    line less above the name, one less in the lower band): outline, light inner border, plate in two bands, a dark
    line below the plate, white name outlined in a dark shade, 5 px of plate each side of the name.
    tier (0-5) = an evolving tag: its rarity gem on the left, drawn as the letters (None = no icon)."""
    col = c(colour)
    gem = 8 if tier is not None else 0
    w = text_width(name) + 10 + gem
    im = Image.new("RGBA", (w, 14), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, w - 1, 12), fill=mix(col, (0, 0, 0, 255), .65))      # outline
    d.rectangle((1, 1, w - 2, 11), fill=mix(col, (255, 255, 255, 255), .4))  # light inner border
    d.rectangle((2, 2, w - 3, 6), fill=col)                                  # upper band
    d.rectangle((2, 7, w - 3, 10), fill=mix(col, (0, 0, 0, 255), .15))      # lower band
    d.line((2, 11, w - 2, 11), fill=mix(col, (0, 0, 0, 255), .3))           # bottom edge of the plate
    d.line((0, 13, w - 1, 13), fill=mix(col, (0, 0, 0, 255), .755))        # dark line below
    dark = mix(col, (0, 0, 0, 255), .7)
    if tier is not None:  # drawn as the letters (light, dark outline): it stays visible on a plate of its colour
        pts = [(5 + x, 4 + y) for y, row in enumerate(GEMS[tier]) for x, ch in enumerate(row) if ch == "#"]
        for a, b in pts:
            for ox in (-1, 0, 1):
                for oy in (-1, 0, 1):
                    im.putpixel((a + ox, b + oy), dark)
        for p in pts:
            im.putpixel(p, mix(c(RARITIES[tier][1]), (255, 255, 255, 255), .6))
    outlined_text(im, name, 5 + gem, 4, WHITE, dark)
    return im


STYLES["plaque"] = plaque


# the 5 rows of a capital drawn on 7: rows 1 and 3 doubled (taller letters, same width)
TALL_ROWS = (0, 1, 1, 2, 3, 3, 4)


# letters whose thickened shape fills in: drawn fat by hand (8 px wide, every stroke 2 px like the others)
FAT = {"M": ["##....##", "###..###", "########", "##.##.##", "##....##"],
       "W": ["##....##", "##.##.##", "########", "###..###", "##....##"]}


def fat_points(name):
    """QuinqueFive capitals made 2 px thick and 7 px high, 2 px between letters, accents in the 2 rows above:
    the lit pixels (x, y) in a 9 px high box, and the width."""
    from fond_lib import _glyph
    pts, x = set(), 0
    for ch in name.upper():
        g, extra = _glyph(ch)
        if not g:
            x += 5
            continue
        fat = FAT.get(ch)
        for row, src in enumerate(TALL_ROWS):
            for dx, v in enumerate((fat or g)[src]):
                if v == "#":
                    pts.add((x + dx, 2 + row))
                    if not fat:
                        pts.add((x + dx + 1, 2 + row))
        for dx, dy in extra:
            pts.update(((x + dx, 2 + dy), (x + dx + 1, 2 + dy)))
        x += (len(fat[0]) if fat else len(g[0]) + 1) + 2
    return pts, x - 2


def rails(name, colour, tier=None):
    """19 px tag chosen on 2026-11-21 (tried in game on the « Opérateur » tag): a darker panel between two glossy
    rails of the colour, dark outline with the 4 corner pixels off, a 2 px base under it, fat capitals (fat_points)
    in white tinted 20 % with the colour, no outline nor shadow, 2 px of panel each side of the name.
    tier (0-5) = an evolving tag: its rarity gem on the left of the name (None = no icon).
    Shown 11 px high in the chat (gen_tags.CHAT_H): taller letters in a taller image keep the tag narrow."""
    col = c(colour)
    black, white = (0, 0, 0, 255), (255, 255, 255, 255)
    pts, tw = fat_points(name)
    gem = 8 if tier is not None else 0
    w, h = tw + 10 + gem, 17
    im = Image.new("RGBA", (w, h + 2), (0, 0, 0, 0))
    corners = {(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)}
    base = mix(col, black, .7)
    for x in range(w):  # base: the plate's shape 1 and 2 px lower
        for y in range(h):
            if (x, y) not in corners:
                im.putpixel((x, y + 1), base)
                im.putpixel((x, y + 2), base)
    outline, panel = mix(col, black, .75), mix(col, black, .28)
    for y in range(h):
        for x in range(w):
            if (x, y) in corners:
                continue
            if x in (0, w - 1) or y in (0, h - 1):
                px = outline
            elif y == 1:
                px = mix(col, white, .55)                    # highlight of the upper rail
            elif y == 2:
                px = mix(col, white, .2)
            elif y <= 13:
                px = panel
            else:
                px = mix(col, black, .3 * min(1, (y - 8.5) / 6.5))   # lower rail, in the shade
            im.putpixel((x, y), px)
    if tier is not None:
        light = mix(c(RARITIES[tier][1]), white, .6)
        for y, row in enumerate(GEMS[tier]):
            for x, ch in enumerate(row):
                if ch == "#":
                    im.putpixel((5 + x, 7 + y), light)
    ink = mix(white, col, .2)
    for x, y in pts:
        im.putpixel((5 + gem + x, 4 + y), ink)
    return im


STYLES["rails"] = rails
