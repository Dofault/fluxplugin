"""Job menu mockups in the vanilla theme (2026-10-29): background + simulated items and buttons.
Usage: python3 mockups.py <generic png> <out dir>
"""
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ui import (BLACK, WHITE, bar, button, button_slot, container, field, flat_button, item, objet, outline_cell,  # noqa: E402
                portrait, rgb, slot, slot_box, title, window, write, INK)

C = "/home/influencraft/serveurs/04_labs/plugins/ItemsAdder/contents"
T = C + "/itemshopplus/textures/itemshopplus/"
P = C + "/influportraits/textures/portrait/"
ART = C + "/influmetier/textures/metier/mineur.png"
JOBS = [T + p for p in ("tools/diamond_pickaxe.png", "tools/diamond_axe.png", "tools/diamond_hoe.png", "tools/diamond_shovel.png",
                        "tools/iron_sword.png", "cod.png", "books/enchanted_book.png", "oak_planks.png", "copper_ingot.png",
                        "sweet_berries.png", "glow_berries.png", "copper/copper_block.png")]
PETS = [P + p + ".png" for p in ("pepite", "caillou", "marteau_piqueur", "wagonnet")]
LOOT = [T + p for p in ("diamond.png", "emerald.png", "iron_ingot.png", "gold_ingot.png", "copper_ingot.png",
                        "copper/copper_block.png", "copper/exposed_copper.png", "copper/weathered_copper.png",
                        "copper/oxidized_copper.png", "terra/orange_terracotta.png", "terra/light_gray_terracotta.png",
                        "terra/brown_terracotta.png", "terra/cyan_terracotta.png", "terra/lime_terracotta.png",
                        "logs/oak_log.png", "melon_slice.png", "apple.png", "carrot.png")]
GREEN, RED, BLUE, GOLD = rgb("#3E8E2E"), rgb("#C0392B"), rgb("#3D7FB8"), rgb("#D9A21B")
CHEST = range(9, 54)
INV = range(54, 81)
HOTBAR = range(81, 90)


def row(r, cols=range(9)):
    return [r * 9 + c for c in cols]


def inv_row(i, cols=range(9)):
    return [54 + i * 9 + c for c in cols]


def span(cases, pad=1):
    xs = [objet(c)[0] for c in cases]
    ys = [objet(c)[1] for c in cases]
    return min(xs) - 1 - pad, min(ys) - 1 - pad, max(xs) + 16 + pad, max(ys) + 16 + pad


def header_buttons(d, back=0, info=8, join=None):
    button_slot(d, back, RED, "back")
    button_slot(d, info, BLUE, "info")
    if join is not None:
        button_slot(d, join, GREEN, "plus")


def actions_box(im, d, style, cols=range(9), head="Actions rémunérées", kind="dark", head_kind="strip", accent=None,
                filled=17, pager=False):
    cases = [c for i in range(3) for c in inv_row(i, cols)]
    x0, y0, x1, y1 = span(cases)
    top = 138 if head_kind == "tab" else 127
    container(im, d, (x0, top, x1, y1), style, head, head_kind, head_h=10, accent=accent)
    for c in cases:
        slot(d, c, kind)
    for k, c in enumerate(cases[:filled]):
        item(im, c, LOOT[k % len(LOOT)])


def pager_hotbar(im, d, txt="1/2"):
    l0 = slot_box(81)
    r1 = slot_box(89)
    flat_button(d, (l0[0], 198, slot_box(82)[2], 212), "left")
    field(im, d, (slot_box(84)[0] - 6, 198, slot_box(86)[2] + 6, 212), txt)
    flat_button(d, (slot_box(88)[0], 198, r1[2], 212), "right")


def frame64(d, x, y, style="puits"):
    """Frame around the 64 x 64 illustration."""
    if style == "puits":
        d.rectangle((x - 2, y - 2, x + 65, y + 65), fill=rgb("#373737"))
        d.line((x - 1, y + 66, x + 66, y + 66), fill=WHITE)
        d.line((x + 66, y - 1, x + 66, y + 66), fill=WHITE)
    else:
        d.rectangle((x - 2, y - 2, x + 65, y + 65), fill=BLACK)
        d.line((x - 1, y + 67, x + 64, y + 67), fill=WHITE)


# ------------------------------------------------------------------------------------------------ layout A: current slots

def layout_a(im, d, style, kind, head_kind="strip", accent=None, actions_accent=None, label="ink"):
    pad = 3 if style == "carte" else 1
    """Current slots kept; the levels path and the xp path are dropped: row 5 becomes the level bar, the hotbar a pager."""
    title(im, "Mineur")
    header_buttons(d, 0, 8, 7)
    field(im, d, (26, 18, 128, 33), "Niveau 42", rgb("#FFD94D") if label == "gold" else None or (230, 230, 230, 255))
    container(im, d, span([9, 36], pad), style)
    for k, c in enumerate([9, 18, 27, 36]):
        slot(d, c, kind)
        item(im, c, PETS[k] if k < 3 else C + "/influitems/textures/lock.png")
    frame64(d, 34, 39, "puits" if style != "encre" else "black")
    portrait(im, ART, 34, 39)
    grid = [15, 16, 17, 24, 25, 26, 33, 34, 35, 42, 43, 44]
    container(im, d, span(grid, pad), style)
    for k, c in enumerate(grid):
        slot(d, c, "lit" if k == 0 else kind)
        item(im, c, JOBS[k])
    if kind != "vanilla":
        outline_cell(d, grid[0])
    bar(d, (6, 110, 169, 122), 0.63, GREEN)
    write(im, "63 % vers niv. 43", 88, 114, WHITE, (0, 0, 0, 255))
    actions_box(im, d, style, head_kind=head_kind, accent=actions_accent, kind=kind)
    pager_hotbar(im, d)


# ------------------------------------------------------------------------------------------------ layout B: dock

def layout_b(im, d, style, kind, head_kind="strip", accent=None):
    """Portrait and job card on top, actions in the inventory, the 12 jobs in a dock on the hotbar (7 + arrows)."""
    title(im, "Mineur")
    header_buttons(d, 0, 8)
    field(im, d, (26, 18, 146, 33), "Métiers actifs 2/3")
    left = span([9, 39], 1)
    container(im, d, left, style)
    frame64(d, 10, 39, "black" if style == "encre" else "puits")
    portrait(im, ART, 10, 39)
    right = span([13, 44], 1)
    container(im, d, right, style, "Familiers", "strip", head_h=10, accent=accent)
    for k, c in enumerate([23, 24, 25, 26]):
        slot(d, c, kind)
        item(im, c, PETS[k] if k < 3 else C + "/influitems/textures/lock.png")
    write(im, "", 0, 0, WHITE)
    bar(d, (right[0] + 4, 75, right[2] - 4, 86), 0.63, GREEN)
    write(im, "Niv. 42 · 63 %", (right[0] + right[2]) // 2, 78, WHITE, (0, 0, 0, 255))
    button(d, (slot_box(40)[0] + 2, 90, slot_box(44)[2] - 2, 104), GREEN, None, "Rejoindre", im)
    lv = list(range(46, 53))
    container(im, d, span(lv, 1), style)
    for k, c in enumerate(lv):
        slot(d, c, "colour", rgb("#5AA64A")) if k < 3 else slot(d, c, kind)
        item(im, c, LOOT[(k * 3) % len(LOOT)] if k >= 3 else C + "/influitems/textures/checkmark.png")
    button_slot(d, 45, GREEN, "left")
    button_slot(d, 53, GREEN, "right")
    actions_box(im, d, style, head_kind=head_kind, accent=accent, kind=kind, filled=21)
    x0, _, x1, _ = span(list(HOTBAR))
    container(im, d, (x0, 196, x1, 214), "encre" if style != "carte" else "ardoise")
    button_slot(d, 81, GREEN, "left")
    button_slot(d, 89, GREEN, "right")
    for k, c in enumerate(range(82, 89)):
        slot(d, c, "lit" if k == 0 else "ink")
        item(im, c, JOBS[k])


# ------------------------------------------------------------------------------------------------ layout C: tabs

def layout_c(im, d, style, kind, accent=None):
    """Shop reference: jobs as tabs on row 1 above one big container (portrait, level, pets, join), pager on the hotbar."""
    title(im, "Mineur")
    header_buttons(d, 0, 8)
    field(im, d, (26, 18, 146, 33), "Niveau 42 · 63 %")
    big = (6, 53, 169, 125)
    container(im, d, big, style)
    for k, c in enumerate(range(10, 17)):                   # tabs
        x0, y0, x1, y1 = slot_box(c)
        sel = k == 0
        d.rectangle((x0 - 1, y0 - 1, x1 + 1, y1 + 2 if sel else y1 + 1), fill=BLACK)
        fill = rgb("#585B5F") if sel and style == "ardoise" else (rgb("#232327") if sel and style == "encre" else
                                                                  (rgb("#9A9A9A") if not sel else rgb("#B5B5B5")))
        d.rectangle((x0, y0, x1, y1 + 2 if sel else y1), fill=fill)
        d.line((x0, y0, x1, y0), fill=WHITE if not sel else rgb("#8E9095"))
        item(im, c, JOBS[k])
    button_slot(d, 9, GREEN, "left")
    button_slot(d, 17, GREEN, "right")
    frame64(d, 10, 57, "black")
    portrait(im, ART, 10, 57)
    bar(d, (80, 58, 166, 68), 0.63, GREEN)
    write(im, "Niveau 42", 0, 72, (230, 230, 230, 255), None, left=81)
    write(im, "63 %", 0, 72, rgb("#7BD94F"), None, left=146)
    write(im, "Familiers", 0, 82, (160, 160, 165, 255), None, left=81)
    for k, c in enumerate([40, 41, 42, 43]):
        slot(d, c, kind)
        item(im, c, PETS[k] if k < 3 else C + "/influitems/textures/lock.png")
    button(d, (slot_box(49)[0], 108, slot_box(53)[2], 122), GREEN, None, "Rejoindre", im)
    actions_box(im, d, style, kind=kind, accent=accent, filled=20)
    pager_hotbar(im, d)


# ------------------------------------------------------------------------------------------------ layout D: vanilla pure

def layout_d(im, d):
    """Closest to vanilla: no sub-container, vanilla slots, ink labels as chest titles."""
    title(im, "Mineur")
    header_buttons(d, 0, 8, 7)
    write(im, "Niveau 42 · 63 %", 0, 22, INK, None, left=28)
    for k, c in enumerate([9, 18, 27, 36]):
        slot(d, c)
        item(im, c, PETS[k] if k < 3 else C + "/influitems/textures/lock.png")
    frame64(d, 34, 39)
    portrait(im, ART, 34, 39)
    grid = [15, 16, 17, 24, 25, 26, 33, 34, 35, 42, 43, 44]
    for k, c in enumerate(grid):
        slot(d, c, "colour", rgb("#5AA64A")) if k == 0 else slot(d, c)
        item(im, c, JOBS[k])
    bar(d, (6, 110, 169, 121), 0.63, GREEN)
    write(im, "Actions rémunérées", 0, 130, INK, None, left=8)
    for c in [c for i in range(3) for c in inv_row(i)]:
        slot(d, c)
    for k, c in enumerate([c for i in range(3) for c in inv_row(i)][:20]):
        item(im, c, LOOT[k % len(LOOT)])
    pager_hotbar(im, d)


VARIANTS = [
    ("01_ardoise", "Ardoise (réf. boutique)", lambda im, d: layout_a(im, d, "ardoise", "dark")),
    ("02_encre", "Encre (contraste fort)", lambda im, d: layout_a(im, d, "encre", "ink")),
    ("03_puits", "Puits vanilla", lambda im, d: layout_a(im, d, "puits", "vanilla")),
    ("04_onglet", "Onglet dossier", lambda im, d: layout_a(im, d, "ardoise", "dark", head_kind="tab", actions_accent=rgb("#35363A"))),
    ("05_dock", "Dock des métiers", lambda im, d: layout_b(im, d, "ardoise", "dark")),
    ("06_dock_encre", "Dock, encre + vert", lambda im, d: layout_b(im, d, "encre", "ink", accent=rgb("#2E6B26"))),
    ("07_onglets", "Onglets (réf. boutique)", lambda im, d: layout_c(im, d, "ardoise", "dark")),
    ("08_onglets_encre", "Onglets, encre", lambda im, d: layout_c(im, d, "encre", "ink")),
    ("09_carte", "Carte blanche", lambda im, d: layout_a(im, d, "carte", "dark")),
    ("10_vanilla", "Vanilla pur", layout_d),
    ("11_couleur", "Ardoise + en-têtes colorés", lambda im, d: layout_a(im, d, "ardoise", "dark", actions_accent=rgb("#2E6B26"))),
    ("12_verre", "Verre gris", lambda im, d: layout_b(im, d, "verre", "vanilla")),
]

if __name__ == "__main__":
    generic, out = sys.argv[1:3]
    os.makedirs(out, exist_ok=True)
    tiles = []
    only = sys.argv[3:]
    for key, name, fn in VARIANTS:
        if only and key not in only:
            continue
        im = window(generic)
        fn(im, ImageDraw.Draw(im, "RGBA"))
        big = im.resize((W3 := 176 * 3, 221 * 3), Image.NEAREST)
        big.save(os.path.join(out, key + ".png"))
        tiles.append(big)
    cols = 4
    for start in range(0, len(tiles), 8):
        part = tiles[start:start + 8]
        rows = (len(part) + cols - 1) // cols
        sheet = Image.new("RGBA", (cols * (528 + 20) + 20, rows * (663 + 20) + 20), (30, 30, 34, 255))
        for i, p in enumerate(part):
            sheet.alpha_composite(p, (20 + (i % cols) * 548, 20 + (i // cols) * 683))
        sheet.save(os.path.join(out, "planche_%d.png" % (start // 8 + 1)))
    print(len(tiles), "mockups ->", out)
