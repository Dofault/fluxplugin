"""Tags of FluxCosmetics: the style of every tag, « rails » (2026-11-21), used by gen_tags.py.

A tag is drawn in a 19 px high image, shown 11 px high in the chat (gen_tags.CHAT_H): fat QuinqueFive capitals,
7 px high. Former styles (« plaque » 2026-10-30, « gemme », « plein », « bordure ») and their previews were removed
with FluxTags (2026-11-21): git history keeps them.
"""
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "Flux", "assets"))

RARITIES = [("Commun", "9a9a9a"), ("Unique", "3fae4a"), ("Épique", "62b6e8"), ("Mythique", "b45ae6"),
            ("Légendaire", "e8b13a"), ("Fabuleux", "e5484d")]


def c(h, a=255):
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


GEMS = [  # 5x5 marks growing with the tier
    [".....", ".....", "..#..", ".....", "....."],
    [".....", "..#..", ".###.", "..#..", "....."],
    ["..#..", ".###.", "#####", ".###.", "..#.."],
    ["..#..", ".#.#.", "#.#.#", ".#.#.", "..#.."],
    ["#.#.#", ".###.", "##.##", ".###.", "#.#.#"],
    ["..#..", "#####", ".###.", ".#.#.", "#...#"],
]


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

