"""Backgrounds of the profile menus (2026-10-28, layout modelled on the owner's reference screenshot, server theme).

menu_profil (/profil), 6-row chest, rows 0 to 4 used:
  row 0          back (0) and menu icon (8), the title « PROFIL » between them
  left panel     columns 0 to 3, rows 1 to 4: the player's 3D bust, drawn by Flux in the title
                 (%flux_avatar_43_107%: bottom centre of this panel)
  right panel    columns 5 to 8, rows 1 to 4: 8 buttons on rows 2 and 3 (slots 23 to 26, 32 to 35)
menu_classements (/profil -> Classements): title « CLASSEMENTS », 7 boards on row 2 (slots 19 to 25) in one panel.

Typography always white. Usage: python gen_fond.py <dir for the pngs> [preview dir]  (Pillow)
"""
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from fond_lib import generique_raccourci, poser, tampon_case, title  # noqa: E402

W, H = 176, 221
TOP, BOTTOM = 0, 120
CONTOUR = (21, 17, 27, 255)
PANEL = (27, 21, 39, 255)
PANEL_EDGE = (13, 10, 18, 255)
PANEL_LIGHT = (72, 57, 96, 255)
AVATAR_BOTTOM = (43, 107)


def objet(case):
    return 8 + 18 * (case % 9), 18 + 18 * (case // 9)


def panel(im, cols, rows, pad=2):
    """Rounded dark panel around a block of slots (columns and rows inclusive), sunken: dark edge, light inner rim."""
    x0, y0 = objet(rows[0] * 9 + cols[0])
    x1, y1 = objet(rows[1] * 9 + cols[1])
    box = (x0 - 1 - pad, y0 - 1 - pad, x1 + 16 + pad, y1 + 16 + pad)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle(box, radius=4, fill=PANEL, outline=PANEL_EDGE)
    d.rounded_rectangle((box[0] + 1, box[1] + 1, box[2] - 1, box[3] - 1), radius=3, outline=PANEL_LIGHT)
    d.rounded_rectangle((box[0] + 2, box[1] + 2, box[2] - 2, box[3] - 2), radius=2, outline=PANEL)


def base(name):
    im = generique_raccourci(os.path.join(os.path.dirname(HERE), "menu_generique.png"), TOP, BOTTOM)
    title(im, name, objet(0)[1])
    return im


def profil():
    im = base("Profil")
    panel(im, (0, 3), (1, 4))
    panel(im, (5, 8), (1, 4))
    case = tampon_case()
    for slot in (23, 24, 25, 26, 32, 33, 34, 35):
        poser(im, case, *objet(slot))
    return im


def classements():
    im = base("Classements")
    panel(im, (1, 7), (1, 3))
    case = tampon_case()
    for slot in range(19, 26):
        poser(im, case, *objet(slot))
    return im


if __name__ == "__main__":
    out = sys.argv[1]
    for name, im in (("menu_profil", profil()), ("menu_classements", classements())):
        im.save(os.path.join(out, name + ".png"))
        if len(sys.argv) > 2:
            im.resize((W * 3, H * 3), Image.NEAREST).save(os.path.join(sys.argv[2], name + "_x3.png"))
    print("menu_profil.png, menu_classements.png ->", out)
