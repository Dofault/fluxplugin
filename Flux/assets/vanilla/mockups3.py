"""Round 3 of the vanilla job menu (2026-10-29): button A (owner's reference) everywhere, variants on everything else.
Usage: python3 mockups3.py <generic png> <out dir>
"""
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui  # noqa: E402
from ui import (BLACK, WHITE, INK, bar, container, field, flat_button, item, objet, outline_cell, portrait, rgb,  # noqa: E402
                slot, slot_box, title, tone, window, write)
from mockups import ART, C, JOBS, LOOT, PETS, inv_row, span, frame64  # noqa: E402

GREEN, RED, BLUE, GOLD = rgb("#3E8E2E"), rgb("#C0392B"), rgb("#3D7FB8"), rgb("#FFD94D")
LIGHT = (230, 230, 230, 255)
LOCK = C + "/influitems/textures/lock.png"
GRID = [15, 16, 17, 24, 25, 26, 33, 34, 35, 42, 43, 44]
PETS4 = [9, 18, 27, 36]


def sbox(case):
    x, y = objet(case)
    return x - 1, y - 1, x + 16, y + 15


def btn(d, case, colour, icon):
    ui.button(d, sbox(case), colour, icon)


def pets(im, d, cases, style="ardoise", kind="dark", box=True):
    if box:
        container(im, d, span(cases), style)
    for k, c in enumerate(cases):
        slot(d, c, kind)
        item(im, c, PETS[k] if k < 3 else LOCK)


def grid(im, d, cases, style="ardoise", kind="dark", select="lit"):
    container(im, d, span(cases), style)
    for k, c in enumerate(cases):
        if k == 0 and select == "lit":
            slot(d, c, "lit")
        elif k == 0 and select == "green":
            slot(d, c, "colour", rgb("#5AA64A"))
        else:
            slot(d, c, kind)
        item(im, c, JOBS[k])
    if select == "lit":
        outline_cell(d, cases[0])
    if select == "frame":
        x0, y0, x1, y1 = slot_box(cases[0])
        d.rectangle((x0 - 1, y0 - 1, x1 + 1, y1 + 1), outline=GOLD)
        d.rectangle((x0, y0, x1, y1), outline=tone(GOLD, -0.3))


def actions(im, d, label_y=130, rows=3, filled=20):
    write(im, "Actions rémunérées", 0, label_y, INK, None, left=8)
    cases = [c for i in range(rows) for c in inv_row(i)]
    for c in cases:
        slot(d, c)
    for k, c in enumerate(cases[:filled]):
        item(im, c, LOOT[k % len(LOOT)])


def pager(im, d, kind="flat"):
    if kind == "flat":
        flat_button(d, (slot_box(81)[0], 198, slot_box(82)[2], 212), "left")
        flat_button(d, (slot_box(88)[0], 198, slot_box(89)[2], 212), "right")
    else:   # button A arrows on the hotbar ends
        ui.button(d, (slot_box(81)[0], 198, slot_box(82)[2], 211), GREEN, "left")
        ui.button(d, (slot_box(88)[0], 198, slot_box(89)[2], 211), GREEN, "right")
    field(im, d, (slot_box(84)[0] - 6, 198, slot_box(86)[2] + 6, 212), "1/2")


def head(im, d, name="Mineur", colour=LIGHT, join=True, extra=None):
    title(im, "Métier")
    btn(d, 0, RED, "back")
    if join:
        btn(d, 7, GREEN, "plus")
    btn(d, 8, BLUE, "info")
    right = 128 if join else 146
    field(im, d, (26, 18, right, 33), "")
    if extra:   # name left, extra right
        write(im, name, 0, 24, colour, None, left=31)
        w = ui.fond_lib.text_width(extra)
        write(im, extra, 0, 24, GOLD, None, left=right - 5 - w)
    else:
        write(im, name, (26 + right) // 2 + 1, 24, colour)


def xp_bar(im, d, y=110, txt="Niv. 42 · 63 %", box=(6, 169)):
    bar(d, (box[0], y, box[1], y + 12), 0.63, GREEN)
    if txt:
        write(im, txt, (box[0] + box[1]) // 2, y + 4, WHITE, BLACK)


# ------------------------------------------------------------------------------------------------ variants

def v01(im, d):
    """Base: the chosen mix, button A, white name."""
    head(im, d)
    pets(im, d, PETS4)
    frame64(d, 34, 39)
    portrait(im, ART, 34, 39)
    grid(im, d, GRID)
    xp_bar(im, d)
    actions(im, d)
    pager(im, d)


def v02(im, d):
    """Gold name, level on the right of the name field, thin xp bar without text."""
    head(im, d, colour=GOLD, extra="Niv. 42")
    pets(im, d, PETS4)
    frame64(d, 34, 39)
    portrait(im, ART, 34, 39)
    grid(im, d, GRID, select="green")
    bar(d, (6, 113, 169, 119), 0.63, GREEN)
    actions(im, d)
    pager(im, d, "a")


def v03(im, d):
    """One ardoise panel for the whole top (pets, portrait, jobs), bar inside it."""
    head(im, d)
    container(im, d, (5, 34, 170, 125), "ardoise")
    pets(im, d, PETS4, box=False)
    frame64(d, 34, 39, "black")
    portrait(im, ART, 34, 39)
    for k, c in enumerate(GRID):
        slot(d, c, "lit" if k == 0 else "dark")
        item(im, c, JOBS[k])
    outline_cell(d, GRID[0])
    xp_bar(im, d, 110, box=(8, 167))
    actions(im, d)
    pager(im, d)


def v04(im, d):
    """Mirror: jobs on the left, portrait on the right, pets in a row under the bar... pets on the right column."""
    head(im, d)
    g = [9, 10, 11, 18, 19, 20, 27, 28, 29, 36, 37, 38]
    grid(im, d, g)
    frame64(d, 76, 39)
    portrait(im, ART, 76, 39)
    pets(im, d, [17, 26, 35, 44])
    xp_bar(im, d)
    actions(im, d)
    pager(im, d)


def v05(im, d):
    """Portrait on the left, the 12 jobs on 3 rows on the right, pets in a row under them with their label."""
    head(im, d, extra="Niv. 42")
    frame64(d, 16, 39)
    portrait(im, ART, 16, 39)
    jobs = [14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35]
    grid(im, d, jobs)
    pets(im, d, [41, 42, 43, 44])
    xp_bar(im, d)
    actions(im, d)
    pager(im, d)


def v06(im, d):
    """Encre (black) panels, gold name: stronger contrast."""
    head(im, d, colour=GOLD)
    pets(im, d, PETS4, "encre", "ink")
    frame64(d, 34, 39, "black")
    portrait(im, ART, 34, 39)
    grid(im, d, GRID, "encre", "ink", select="frame")
    xp_bar(im, d)
    actions(im, d)
    pager(im, d, "a")


def v07(im, d):
    """Vanilla wells (no black outline) on top too: the sobrest."""
    head(im, d)
    pets(im, d, PETS4, "puits", "vanilla")
    frame64(d, 34, 39)
    portrait(im, ART, 34, 39)
    grid(im, d, GRID, "puits", "vanilla", select="green")
    xp_bar(im, d)
    actions(im, d)
    pager(im, d)


def v08(im, d):
    """Labels everywhere (Familiers, Métiers, Actions): readable, like vanilla chest titles; bar under the portrait."""
    head(im, d)
    write(im, "Métiers", 0, 0, INK, None, left=8) if False else None
    pets(im, d, PETS4)
    frame64(d, 34, 39)
    portrait(im, ART, 34, 39)
    bar(d, (32, 108, 99, 116), 0.63, GREEN)
    write(im, "42", 0, 119, INK, None, left=32)
    write(im, "63 %", 0, 119, INK, None, left=84)
    grid(im, d, GRID)
    write(im, "Familiers · Métiers", 0, 119, INK, None, left=112) if False else None
    actions(im, d)
    pager(im, d)


VARIANTS = [("01_base", v01), ("02_or_niveau", v02), ("03_panneau", v03), ("04_miroir", v04),
            ("05_familiers_bas", v05), ("06_encre_or", v06), ("07_puits", v07), ("08_barre_portrait", v08)]

if __name__ == "__main__":
    generic, out = sys.argv[1:3]
    os.makedirs(out, exist_ok=True)
    tiles = []
    for key, fn in VARIANTS:
        im = window(generic)
        fn(im, ImageDraw.Draw(im, "RGBA"))
        big = im.resize((528, 663), Image.NEAREST)
        big.save(os.path.join(out, key + ".png"))
        tiles.append(big)
    sheet = Image.new("RGBA", (4 * 548 + 20, 2 * 683 + 20), (30, 30, 34, 255))
    for i, p in enumerate(tiles):
        sheet.alpha_composite(p, (20 + (i % 4) * 548, 20 + (i // 4) * 683))
    sheet.save(os.path.join(out, "planche.png"))
    print(len(tiles), "->", out)
