#!/usr/bin/env python3
"""Background of the loyalty menu (/fidelite, 2026-10-29), PROVISIONAL: the owner sends the menu it is inspired
from (a « calendar » of 7 days, the 7th a big reward, with a line and branches), the final drawing follows it.

Layout: row 0 back (0), title « FIDÉLITÉ », menu icon (8); the week of the login cycle in row 2 (19 to 25, the 7th
on a gold case), previous / next week in 18 / 26; the playtime tiers on the hidden inventory's first row (55 to 61),
previous / next page in 54 / 62. One frame from the top down to the playtime tiers.

    <venv with Pillow>/python3 gen_fond.py <dir for menu_fidelite.png> [preview png]
"""
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
FLUX = os.path.join(HERE, "..", "..", "Flux", "assets")
sys.path.insert(0, FLUX)
from fond_lib import generique_raccourci, poser, tampon_case, title  # noqa: E402

W = 176
INV_Y = 139                      # item y of the hidden inventory's first row in game
DAYS = list(range(19, 26))
PLAYTIME = list(range(55, 62))
ARROWS = [18, 26, 54, 62]
CORNERS = [0, 8]                 # back, menu icon
GOLD, GOLD_DARK = (240, 168, 0, 255), (138, 90, 20, 255)


def objet(slot):
    if slot < 54:
        return 8 + 18 * (slot % 9), 18 + 18 * (slot // 9)
    return 8 + 18 * ((slot - 54) % 9), INV_Y + 18 * ((slot - 54) // 9)


def background():
    im = generique_raccourci(os.path.join(FLUX, "menu_generique.png"), 0, INV_Y + 21)
    title(im, "Fidélité", 18)
    case = tampon_case()
    for s in DAYS[:-1] + PLAYTIME + ARROWS + CORNERS:
        poser(im, case, *objet(s))
    x, y = objet(DAYS[-1])
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((x - 2, y - 2, x + 17, y + 17), radius=3, fill=GOLD, outline=GOLD_DARK)
    return im


if __name__ == "__main__":
    im = background()
    im.save(os.path.join(sys.argv[1], "menu_fidelite.png"))
    if len(sys.argv) > 2:
        view = Image.new("RGBA", im.size, (60, 60, 60, 255))
        view.alpha_composite(im)
        view.resize((W * 3, im.height * 3), Image.NEAREST).save(sys.argv[2])
