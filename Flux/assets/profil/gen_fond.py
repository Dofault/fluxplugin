"""Background of the profile menu (/profil [player]), chosen 2026-10-29 (variant 1 of the second round): the whole menu
IS a player card. Theme colours of the generic background, light paper body with guilloche, gold chip, signature.

6-row chest, the card lowered by SHIFT rows (2026-10-29: 2 rows), drawn below as if it started on row 0:
  row 0          back (0), title « CARTE JOUEUR », menu icon (8), in the dark header band
  photo          columns 0 to 2, rows 1 to 3: the player's bust (list profile_bust, Flux/assets/buste/gen_buste.py)
  rank line      row 1, columns 4 to 8: the player's rank tag (%fluxtags_profile%, FluxTags gen_tags.PROFILE_Y)
  sections       columns 4 to 7, rows 2 and 3: the 8 sections of the profile
  row 4          signature under the photo, chip
Slots in menus/profil.yml are these + 9 * SHIFT.
Typography always white. Usage: <venv with Pillow>/python3 gen_fond.py <dir for menu_profil.png> [preview dir]
"""
import math
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from fond_lib import poser, tampon_case, title  # noqa: E402

W, H = 176, 221
WHITE = (255, 255, 255, 255)
# generic theme (menu_generique.png)
OUTLINE, RIM, MID = (21, 17, 27, 255), (127, 102, 163, 255), (89, 70, 116, 255)
DARK = [(63, 50, 83, 255), (58, 46, 76, 255), (52, 41, 66, 255), (47, 37, 60, 255), (44, 35, 57, 255),
        (40, 31, 51, 255), (35, 28, 46, 255), (31, 24, 40, 255)]
PAPER, PAPER_LINE, PAPER_SHADE = (240, 236, 245, 255), (226, 219, 234, 255), (214, 205, 226, 255)
GOLD, GOLD_DARK, GOLD_LIGHT, PINK = (240, 168, 0, 255), (138, 90, 20, 255), (255, 217, 77, 255), (224, 96, 160, 255)
SECTIONS = (22, 23, 24, 25, 31, 32, 33, 34)
PHOTO = (6, 34, 62, 90)
CARD = (0, 0, 175, 113)
SHIFT = 2   # rows the whole card is lowered by in the window


def objet(case):
    return 8 + 18 * (case % 9), 18 + 18 * (case // 9)


def new():
    return Image.new("RGBA", (W, H), (0, 0, 0, 0))


def card(im, body, radius=8):
    """The menu's own outline: a rounded card, outline + light rim of the theme."""
    d = ImageDraw.Draw(im)
    d.rounded_rectangle(CARD, radius=radius, fill=OUTLINE)
    d.rounded_rectangle((CARD[0] + 1, CARD[1] + 1, CARD[2] - 1, CARD[3] - 1), radius=radius - 1, fill=RIM)
    d.rounded_rectangle((CARD[0] + 2, CARD[1] + 2, CARD[2] - 2, CARD[3] - 2), radius=radius - 2, fill=body)
    return d


def header(im, d, name, h=33):
    """Dark band of the theme across the top (row 0: back, title, icon), cut to the card's rounded corners, with a
    light rim under it."""
    band = Image.new("RGBA", im.size, (0, 0, 0, 0))
    bd = ImageDraw.Draw(band)
    for y in range(2, h):
        bd.line((2, y, CARD[2] - 2, y), fill=DARK[min(len(DARK) - 1, (y - 2) * len(DARK) // max(1, h - 2))])
    bd.line((2, h, CARD[2] - 2, h), fill=RIM)
    bd.line((2, h + 1, CARD[2] - 2, h + 1), fill=OUTLINE)
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((CARD[0] + 2, CARD[1] + 2, CARD[2] - 2, CARD[3] - 2), radius=6, fill=255)
    im.paste(band, (0, 0), Image.composite(band, Image.new("RGBA", im.size, (0, 0, 0, 0)), mask).getchannel("A"))
    title(im, name, objet(0)[1])


def cases(im):
    case = tampon_case()
    for s in SECTIONS:
        poser(im, case, *objet(s))


def guilloche(d, box, colour, step=4, amp=1.6):
    x0, y0, x1, y1 = box
    for y in range(y0 + 2, y1 - 1, step):
        d.line([(x, y + amp * math.sin((x + 2 * y) / 6.0)) for x in range(x0 + 1, x1)], fill=colour)


def signature(d, x, y, colour):
    pts = [(x + i, y + 3 * math.sin(i / 2.2) * (1 - i / 60) + (2 if 18 < i < 26 else 0)) for i in range(0, 46)]
    d.line(pts, fill=colour, width=1)
    d.line((x + 30, y + 5, x + 46, y + 2), fill=colour)


def chip(d, x, y):
    d.rounded_rectangle((x, y, x + 13, y + 10), radius=2, fill=GOLD, outline=GOLD_DARK)
    for yy in (y + 3, y + 7):
        d.line((x + 1, yy, x + 12, yy), fill=(200, 140, 20, 255))
    d.line((x + 6, y + 1, x + 6, y + 9), fill=(200, 140, 20, 255))
    d.rectangle((x + 4, y + 4, x + 8, y + 6), fill=GOLD_LIGHT)


def star(d, cx, cy, r, colour, outline=None):
    pts = []
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5
        rr = r if i % 2 == 0 else r * 0.45
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    d.polygon(pts, fill=colour, outline=outline)


def photo(d, fill, frame=OUTLINE, rim=RIM):
    x0, y0, x1, y1 = PHOTO
    d.rectangle((x0, y0, x1, y1), fill=fill, outline=frame)
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), outline=rim)


RANK_LINE = (78, 51, 166)   # x0, y, x1: the line the rank tag stands on


def background():
    im = new()
    d = card(im, PAPER)
    guilloche(d, (3, 36, 172, 111), PAPER_LINE)
    header(im, d, "Carte joueur")
    photo(d, PAPER_SHADE)
    star(d, 59, 87, 5, GOLD_LIGHT, PINK)
    x0, y, x1 = RANK_LINE
    d.line((x0, y, x1, y), fill=PAPER_SHADE)
    d.line((x0, y + 1, x1, y + 1), fill=PAPER_LINE)
    d.line((8, 106, 60, 106), fill=PAPER_SHADE)
    signature(d, 10, 99, DARK[0])
    chip(d, 154, 94)
    cases(im)
    lowered = new()
    lowered.paste(im.crop((0, 0, W, H - 18 * SHIFT)), (0, 18 * SHIFT))
    return lowered


if __name__ == "__main__":
    im = background()
    im.save(os.path.join(sys.argv[1], "menu_profil.png"))
    if len(sys.argv) > 2:
        im.crop((0, 0, W, 116 + 18 * SHIFT)).resize((W * 3, (116 + 18 * SHIFT) * 3), Image.NEAREST).save(os.path.join(sys.argv[2], "menu_profil_x3.png"))
