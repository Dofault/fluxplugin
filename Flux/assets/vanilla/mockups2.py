"""Round 2 of the vanilla job menu (2026-10-29): top of mockup 11 (ardoise containers, job name in the dark field,
plate title "Métier"), bottom of mockup 10 (ink label, vanilla slots, pager). Variants on the buttons.
Usage: python3 mockups2.py <generic png> <out dir>
"""
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui  # noqa: E402
from ui import (BLACK, WHITE, INK, bar, container, field, flat_button, item, objet, outline_cell, portrait, rgb,  # noqa: E402
                slot, slot_box, title, tone, window, write, ICONS)
from mockups import ART, C, JOBS, LOOT, PETS, inv_row, pager_hotbar, span, frame64  # noqa: E402

GREEN, RED, BLUE = rgb("#3E8E2E"), rgb("#C0392B"), rgb("#3D7FB8")
LOCK = C + "/influitems/textures/lock.png"


# ------------------------------------------------------------------------------------------------ button styles

def _icon(d, box, icon, colour=WHITE, shadow=(0, 0, 0, 140), dy=0):
    x0, y0, x1, y1 = box
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2 - 1 + dy
    pts = []

    class Rec:
        def point(self, p, fill):
            pts.append((p, fill))
    ICONS[icon](Rec(), cx, cy)
    # ICONS draw a shadow then the white shape: recolour
    n = len(pts) // 2
    for (p, _) in pts[:n]:
        if shadow:
            d.point(p, fill=shadow)
    for (p, _) in pts[n:]:
        d.point(p, fill=colour)


def b_reference(d, box, colour, icon):
    """Owner's reference: black outline, bevel, white line under the bottom edge."""
    ui.button(d, box, colour, icon)


def b_vanilla(d, box, colour, icon):
    """Light vanilla button (like the pager), icon in the colour."""
    x0, y0, x1, y1 = box
    d.rectangle(box, fill=BLACK)
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), fill=rgb("#D4D4D4"))
    d.line((x0 + 1, y0 + 1, x1 - 1, y0 + 1), fill=WHITE)
    d.line((x0 + 1, y0 + 1, x0 + 1, y1 - 1), fill=WHITE)
    d.line((x0 + 1, y1 - 1, x1 - 1, y1 - 1), fill=rgb("#8B8B8B"))
    d.line((x1 - 1, y0 + 1, x1 - 1, y1 - 1), fill=rgb("#8B8B8B"))
    _icon(d, box, icon, colour, tone(colour, -0.6))


def b_ink(d, box, colour, icon):
    """Dark button (as the fields), icon in the colour, coloured line at the bottom."""
    x0, y0, x1, y1 = box
    d.rectangle(box, fill=BLACK)
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), fill=rgb("#35363A"))
    d.line((x0 + 1, y0 + 1, x1 - 1, y0 + 1), fill=rgb("#4A4B50"))
    d.line((x0 + 1, y1 - 1, x1 - 1, y1 - 1), fill=colour)
    d.line((x0 + 1, y1 - 2, x1 - 1, y1 - 2), fill=tone(colour, -0.4))
    _icon(d, box, icon, tone(colour, 0.35), BLACK, -1)


def b_thick(d, box, colour, icon):
    """3D key: rounded corners, thick darker base (pressable)."""
    x0, y0, x1, y1 = box
    d.rounded_rectangle(box, radius=2, fill=BLACK)
    d.rounded_rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), radius=1, fill=tone(colour, -0.4))
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 4), fill=colour)
    d.line((x0 + 2, y0 + 1, x1 - 2, y0 + 1), fill=tone(colour, 0.4))
    d.point((x0 + 1, y0 + 1), fill=BLACK)
    d.point((x1 - 1, y0 + 1), fill=BLACK)
    _icon(d, box, icon, WHITE, tone(colour, -0.6), -1)


def b_slot(d, box, colour, icon):
    """Vanilla slot shape (sunken) with a coloured fill: the button looks like a cell."""
    x0, y0, x1, y1 = box
    d.rectangle(box, fill=colour)
    d.line((x0, y0, x1 - 1, y0), fill=tone(colour, -0.45))
    d.line((x0, y0, x0, y1 - 1), fill=tone(colour, -0.45))
    d.line((x0 + 1, y1, x1, y1), fill=tone(colour, 0.6))
    d.line((x1, y0 + 1, x1, y1), fill=tone(colour, 0.6))
    _icon(d, box, icon, WHITE, tone(colour, -0.6))


def b_pastel(d, box, colour, icon):
    """Soft: pale colour, coloured outline instead of black, darker icon."""
    x0, y0, x1, y1 = box
    pale = tone(colour, 0.6)
    d.rectangle(box, fill=tone(colour, -0.2))
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), fill=pale)
    d.line((x0 + 1, y0 + 1, x1 - 1, y0 + 1), fill=tone(colour, 0.8))
    d.line((x0 + 1, y1 + 1, x1 - 1, y1 + 1), fill=WHITE)
    _icon(d, box, icon, tone(colour, -0.35), None)


def b_tag(d, box, colour, icon):
    """Grey vanilla button with a coloured band on the left (label tab)."""
    x0, y0, x1, y1 = box
    b_vanilla(d, box, rgb("#555555"), icon)
    d.rectangle((x0 + 1, y0 + 1, x0 + 3, y1 - 1), fill=colour)
    d.line((x0 + 1, y0 + 1, x0 + 3, y0 + 1), fill=tone(colour, 0.4))


def b_gem(d, box, colour, icon):
    """Faceted: diagonal light top-left half, darker bottom-right half."""
    x0, y0, x1, y1 = box
    d.rectangle(box, fill=BLACK)
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), fill=tone(colour, -0.15))
    d.polygon([(x0 + 1, y0 + 1), (x1 - 1, y0 + 1), (x0 + 1, y1 - 1)], fill=colour)
    d.line((x0 + 1, y0 + 1, x1 - 1, y0 + 1), fill=tone(colour, 0.5))
    d.line((x0 + 1, y0 + 1, x0 + 1, y1 - 1), fill=tone(colour, 0.5))
    d.line((x0 + 1, y1 + 1, x1 - 1, y1 + 1), fill=WHITE)
    _icon(d, box, icon, WHITE, tone(colour, -0.6))


BUTTONS = [
    ("a_reference", "Référence (ligne blanche)", b_reference),
    ("b_vanilla", "Vanilla clair, icône colorée", b_vanilla),
    ("c_sombre", "Sombre, liseré coloré", b_ink),
    ("d_touche", "Touche 3D arrondie", b_thick),
    ("e_case", "Case colorée creusée", b_slot),
    ("f_pastel", "Pastel", b_pastel),
    ("g_etiquette", "Gris à bande colorée", b_tag),
    ("h_facette", "Facette", b_gem),
]


def sbox(case):
    x, y = objet(case)
    return x - 1, y - 1, x + 16, y + 15


def menu(im, d, btn, gold_name=False):
    title(im, "Métier")
    btn(d, sbox(0), RED, "back")
    btn(d, sbox(7), GREEN, "plus")
    btn(d, sbox(8), BLUE, "info")
    field(im, d, (26, 18, 128, 33), "Mineur", rgb("#FFD94D") if gold_name else (230, 230, 230, 255))
    container(im, d, span([9, 36]), "ardoise")
    for k, c in enumerate([9, 18, 27, 36]):
        slot(d, c, "dark")
        item(im, c, PETS[k] if k < 3 else LOCK)
    frame64(d, 34, 39)
    portrait(im, ART, 34, 39)
    grid = [15, 16, 17, 24, 25, 26, 33, 34, 35, 42, 43, 44]
    container(im, d, span(grid), "ardoise")
    for k, c in enumerate(grid):
        slot(d, c, "lit" if k == 0 else "dark")
        item(im, c, JOBS[k])
    outline_cell(d, grid[0])
    bar(d, (6, 110, 169, 122), 0.63, GREEN)
    write(im, "Niv. 42 · 63 %", 88, 114, WHITE, BLACK)
    write(im, "Actions rémunérées", 0, 130, INK, None, left=8)
    cases = [c for i in range(3) for c in inv_row(i)]
    for c in cases:
        slot(d, c)
    for k, c in enumerate(cases[:20]):
        item(im, c, LOOT[k % len(LOOT)])
    pager_hotbar(im, d)


if __name__ == "__main__":
    generic, out = sys.argv[1:3]
    os.makedirs(out, exist_ok=True)
    tiles = []
    for key, name, fn in BUTTONS:
        im = window(generic)
        menu(im, ImageDraw.Draw(im, "RGBA"), fn, gold_name=(key == "a_reference"))
        big = im.resize((528, 663), Image.NEAREST)
        big.save(os.path.join(out, key + ".png"))
        tiles.append(big)
    sheet = Image.new("RGBA", (4 * 548 + 20, 2 * 683 + 20), (30, 30, 34, 255))
    for i, p in enumerate(tiles):
        sheet.alpha_composite(p, (20 + (i % 4) * 548, 20 + (i // 4) * 683))
    sheet.save(os.path.join(out, "planche_boutons.png"))
    # close-up of the buttons, x6
    strip = Image.new("RGBA", (8 * 70 + 10, 30), (198, 198, 198, 255))
    dd = ImageDraw.Draw(strip, "RGBA")
    for i, (_, _, fn) in enumerate(BUTTONS):
        for j, (col, ic) in enumerate(((RED, "back"), (GREEN, "plus"), (BLUE, "info"))):
            fn(dd, (6 + i * 70 + j * 21, 6, 6 + i * 70 + j * 21 + 17, 22), col, ic)
    strip.resize((strip.width * 4, strip.height * 4), Image.NEAREST).save(os.path.join(out, "boutons_zoom.png"))
    print(len(tiles), "->", out)
