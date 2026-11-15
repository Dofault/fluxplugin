"""Offering menu backgrounds (FluxOffering, menus/offering.yml), one per rank: menu_offrande_<rank>.

Redone from scratch on 2026-11-15 in the menu theme's inventory style (Flux/assets/vanilla: ui.py, gen_ui.py), at the
owner's request (the former ribbon and tinted cells are gone). Every rank gets the same picture; the file per rank
stays because the menu title picks it with %offrandes_current_id%.

  window rows 0 to the hotbar, plate « Offrande »; same principle as the job menu and the market (2026-11-15)
  row 0: back (0) and offering icon (8) on the ground
  9-35: requested items over the full width in a well, 1 px off row 0's buttons and the pager row
  row 4: the theme's pager (drawn by Flux); bottom row centred: 47 scoreboard, 49 Tout déposer, 51 filter, admin
  buttons 45 / 53, all on the ground
  the inventory's separator line, then the player's inventory (shown by this menu) in two wells

Usage: <venv with Pillow>/python3 gen_fonds.py <output folder> [preview folder]
Output: ItemsAdder/contents/influmenu/textures/font/gui/ on the server, then /iazip.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "vanilla"))
from PIL import Image, ImageDraw  # noqa: E402

import ui  # noqa: E402
from gen_ui import bg_window, well_around  # noqa: E402

RANKS = ["citoyen", "repute", "populaire", "eminent", "figure", "influenceur", "icone", "legende", "mythe",
         "souverain", "supreme"]
REQUESTED = list(range(9, 36))


def background():
    im = bg_window(None, 0, 9, title="Offrande")
    d = ImageDraw.Draw(im, "RGBA")
    well_around(d, REQUESTED, top=1, bottom=1)
    ui.separator(im, (ui.objet(53)[1] + 16 + ui.objet(54)[1]) // 2 - 1)
    well_around(d, list(range(54, 81)))
    well_around(d, list(range(81, 90)), pad=1)
    return im


if __name__ == "__main__":
    out = sys.argv[1]
    preview = sys.argv[2] if len(sys.argv) > 2 else None
    os.makedirs(out, exist_ok=True)
    im = background()
    for rank in RANKS:
        im.save(os.path.join(out, "menu_offrande_%s.png" % rank))
    if preview:
        os.makedirs(preview, exist_ok=True)
        im.resize((im.width * 3, im.height * 3), Image.NEAREST).save(os.path.join(preview, "offrande.png"))
    print(len(RANKS), "offering backgrounds ->", out)
