"""Background of the profile menu (/profil, 2026-10-28): the player's card.

Layout (6-row chest, hidden inventory):
  row 0          back (0) and menu icon (8), the title « PROFIL » between them
  row 1          BitBlock (11), the player's head (13), Influence (15)
  row 2          label « CLASSEMENTS »
  row 3          7 leaderboards (28 to 34): the player's value and rank in each
  row 4          label « ACTIVITÉ »
  row 5          4 activity tiles (46, 48, 50, 52)
  gap            label « RACCOURCIS » in the chest / inventory gap
  inventory row  4 shortcuts (55, 57, 59, 61)

Usage: python gen_fond.py <png> [preview x3]  (Pillow)
"""
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from fond_lib import generique_raccourci, poser, tampon_case, text, title  # noqa: E402

W, H = 176, 221
TOP, BOTTOM = 0, 178
CONTOUR = (21, 17, 27, 255)
GOLD, PURPLE, BLUE, GREY = "FFD94D", "B376E5", "2BC4E8", "A89BA0"
LABEL = (179, 118, 229, 255)

TILES = {11: GOLD, 13: GOLD, 15: PURPLE}
TILES.update({s: GOLD for s in range(28, 35)})
TILES.update({s: BLUE for s in (46, 48, 50, 52)})
TILES.update({s: PURPLE for s in (55, 57, 59, 61)})


def c(h):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


def objet(case):
    """Top-left of a slot's item; hidden-inventory rows start at y 139 in game."""
    if case < 54:
        return 8 + 18 * (case % 9), 18 + 18 * (case // 9)
    i = case - 54
    return 8 + 18 * (i % 9), 139 + 18 * (i // 9)


def tint(tampon, colour):
    """The rounded case in a colour: its fill (the most used, lightest shade) dark, its rims in the colour."""
    col = c(colour)
    shades = sorted({v for v in tampon.values()}, key=lambda v: sum(v[:3]))
    fill = max(shades, key=lambda v: list(tampon.values()).count(v))
    rims = [v for v in shades if v != fill]
    table = {fill: mix(CONTOUR, col, .22)}
    for i, v in enumerate(rims):
        table[v] = mix(CONTOUR, col, i / max(1, len(rims) - 1))
    return {k: table[v] for k, v in tampon.items()}


def background():
    im = generique_raccourci(os.path.join(os.path.dirname(HERE), "menu_generique.png"), TOP, BOTTOM)
    case = tampon_case()
    for slot, colour in TILES.items():
        x, y = objet(slot)
        poser(im, tint(case, colour), x, y)
    title(im, "Profil", objet(0)[1])
    text(im, "Classements", 88, objet(19)[1] + 6, colour=LABEL)
    text(im, "Activité", 88, objet(37)[1] + 6, colour=LABEL)
    text(im, "Raccourcis", 88, 128, colour=LABEL)
    return im


if __name__ == "__main__":
    im = background()
    im.save(sys.argv[1])
    if len(sys.argv) > 2:
        im.resize((W * 3, H * 3), Image.NEAREST).save(sys.argv[2])
