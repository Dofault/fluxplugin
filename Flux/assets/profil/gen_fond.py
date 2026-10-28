"""Background of the profile menu (/profil [player], 2026-10-28): a passport, server theme (violet, gold trims).

6-row chest, rows 0 to 4 used:
  row 0          back (0) and menu icon (8), the title « PROFIL » between them
  photo          columns 0 to 2, rows 1 to 3: the player's head, an item model 3 slots high centred on slot 19
  sections       columns 4 to 7, rows 2 and 3 (slots 22 to 25, 31 to 34): the 8 sections of the profile
  row 4          the passport's machine-readable zone (decoration)
Typography always white. Usage: python gen_fond.py <dir for menu_profil.png> [preview dir]  (Pillow)
"""
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from fond_lib import generique_raccourci, poser, tampon_case, text, title  # noqa: E402

W, H = 176, 221
TOP, BOTTOM = 0, 120
WHITE = (255, 255, 255, 255)
CARD = (46, 34, 66, 255)
CARD_DARK = (32, 24, 46, 255)
GOLD, GOLD_DARK, GOLD_LIGHT = (240, 168, 0, 255), (138, 90, 20, 255), (255, 217, 77, 255)
PHOTO = (21, 16, 30, 255)
SECTIONS = (22, 23, 24, 25, 31, 32, 33, 34)


def objet(case):
    return 8 + 18 * (case % 9), 18 + 18 * (case // 9)


def card(im):
    """The passport page: violet card with a gold double trim, a darker band at the bottom for the MRZ."""
    d = ImageDraw.Draw(im)
    box = (3, 33, 172, 111)
    d.rounded_rectangle(box, radius=5, fill=CARD, outline=GOLD_DARK)
    d.rounded_rectangle((box[0] + 1, box[1] + 1, box[2] - 1, box[3] - 1), radius=4, outline=GOLD)
    d.rounded_rectangle((box[0] + 3, box[1] + 3, box[2] - 3, box[3] - 3), radius=3, outline=GOLD_DARK)
    d.rectangle((box[0] + 4, 93, box[2] - 4, box[3] - 4), fill=CARD_DARK)
    # photo: dark frame with a light rim, slots of columns 0 to 2, rows 1 to 3
    x0, y0 = objet(9)
    x1, y1 = objet(29)
    d.rectangle((x0, y0 - 1, x1 + 16, y1 + 16), fill=PHOTO, outline=GOLD_DARK)
    d.rectangle((x0 + 1, y0, x1 + 15, y1 + 15), outline=(72, 57, 96, 255))


def background():
    im = generique_raccourci(os.path.join(os.path.dirname(HERE), "menu_generique.png"), TOP, BOTTOM)
    title(im, "Profil", objet(0)[1])
    card(im)
    case = tampon_case()
    for slot in SECTIONS:
        poser(im, case, *objet(slot))
    text(im, "Influencraft", 124, 38, colour=WHITE)
    text(im, "P<INFLUENCRAFT<<<<<<<<<<<<", 88, 96, colour=WHITE, shadow=(0, 0, 0, 0))
    text(im, "<<<<SEMI<RP<<<<<<<<<<<<<<", 88, 103, colour=WHITE, shadow=(0, 0, 0, 0))
    return im


if __name__ == "__main__":
    im = background()
    im.save(os.path.join(sys.argv[1], "menu_profil.png"))
    if len(sys.argv) > 2:
        im.crop((0, 0, W, 124)).resize((W * 3, 124 * 3), Image.NEAREST).save(os.path.join(sys.argv[2], "menu_profil_x3.png"))
    print("menu_profil.png ->", sys.argv[1])
