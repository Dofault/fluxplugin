#!/usr/bin/env python3
"""Dungeon menus of FluxDungeons (2026-11-14), on the theme of Flux (inventory style since 2026-11-15): backgrounds, icons, menu files.

    <venv with Pillow>/python3 gen_menus.py <plugins dir of the server> [preview dir]

Writes:
  ItemsAdder/contents/fluxdonjon/   backgrounds fd_menu_liste_p<n> (a page of the line of dungeons), fd_menu_sas,
                                    fd_menu_boutique, the token glyph jeton_donjon (%img_jeton_donjon%), items jeton_donjon, cle_donjon,
                                    potion_soin_<1-4>, potion_mana_<1-4>
  FluxDungeons/menus/               donjons.yml (the line of dungeons, a page per PER_PAGE of them), donjon_boutique.yml
                                    (dungeon shop), donjon_sas.yml (launch screen)
Lists, actions and placeholders are FluxDungeons' (menu.DungeonMenus, dungeon.Lobby). Then /iazip and /fluxmenu reload.

Layouts (window px 176 wide, item of a slot at fond_lib.objet):
  donjons   the dungeons in a line (2026-11-16, owner's layout), rows 0 to 3 and the inventory's three rows: back (0),
            info (8: tokens, class level); the shop in the middle on a green plate (13), his stats (his head) on its
            left (11), the party on its right (15); the « stuff RPG »
            on row 3 (below); his spells in the inventory's first row under « SORTS » (38-42: A, F, the weapon's spell,
            the basic attack, the passive; read only); the line in the inventory's third row: four
            dungeons a page (55, 57, 59, 61), Flux's path through them and on to the arrows (54, 62) that turn the page,
            each dungeon in a frame of its job's colour under the word « DONJONS » (no levels written); the « ? » after the last one
            (the adventure goes on). One picture and one path per page (pages donjons_p<n> of the menu). Under the
            words « Stuff RPG » (row 3), the player's rpg gear as he really has it: weapon (27: his class, a book
            without one), armour (28-31: a click chooses a piece of his rpg inventory, menu rpg_armure), his rpg
            inventory to look at (32, menu rpg_inventaire, read only), potions (33-34), pet (35).
  donjon_familier  the pet taken into dungeons (one, never a boss's): « none » then his pets, 28 a page, Flux's pager.
  rpg_classes  the classes outside the lobby (a plain menu instead of the MC GUI pages): back (0), his class (8), the
            three classes (11, 13, 15) with his level in each; a click chooses one (the lobby's rules).
  donjon_boutique  the dungeon shop (2026-11-16, laid out as the market, rows 0 to 5, no inventory part): back (0),
            info (8); articles in a well (rows 1-4, columns 1-7); left column the job filter (18) and the kind filter
            (27), right column tokens (26) and the search (35); Flux's pager on row 5.
  donjon_boutique_type  the kind of item, chosen in a small menu (rows 0 to 2): back (0), « Tous » (4, on a gold
            plate), info (8); the eighteen kinds on rows 1 and 2 in a well; a click shows that kind in the shop.
  donjon_sas row 0: leave (0), info (8); rows 1-2: one card per member (head, ready or waiting under it); row 3:
            Easy / Normal / Hard (28, 31, 34, coloured cells, no words: the buttons' names say it), the chosen one lit;
            row 4: players ready; row 5: class (45), ready (49), key (53)
"""
import json
import math
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
FLUXPLUGIN = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.join(FLUXPLUGIN, "Flux", "assets"))
sys.path.insert(0, os.path.join(FLUXPLUGIN, "Flux", "assets", "vanilla"))
sys.path.insert(0, os.path.join(FLUXPLUGIN, "..", "..", "scripts", "influcard"))
import ui  # noqa: E402
from gen_ui import bg_window, label, span  # noqa: E402

PLUGINS = sys.argv[1]
PREVIEW = sys.argv[2] if len(sys.argv) > 2 else None
GENERIC = os.path.join(FLUXPLUGIN, "Flux", "assets", "vanilla", "menu_vanilla.png")
PACK = os.path.join(PLUGINS, "ItemsAdder", "contents", "fluxdonjon")
MENUS = os.path.join(PLUGINS, "FluxDungeons", "menus")
NS = "fluxdonjon"
BLACK, WHITE = (0, 0, 0, 255), (255, 255, 255, 255)
GOLD, GOLD_LIGHT, GOLD_DARK = ui.rgb("#F0A800"), ui.rgb("#FFD94D"), ui.rgb("#8A5A14")
VIOLET, VIOLET_DARK = ui.rgb("#5B2D9E"), ui.rgb("#1E0A3C")

# the line of dungeons (row 3): PER_PAGE places a page (one column out of two), an arrow at each end, the buttons above
# 2026-11-16, owner's layout: party and shop centred on row 1, the « stuff RPG » on row 3 under its words, the line
# of dungeons in the inventory's first row (its levels written in the gap above it)
# 2026-11-16 (later): the inventory's first row holds the player's spells under the word « SORTS », the line of
# dungeons moved two rows down (the inventory's third row), its levels over it
SPELL_SLOTS = [38, 39, 40, 41, 42]      # A, F, weapon's spell, basic attack, passive (rpg.SpellBook order)
LINE_SLOTS = [55, 57, 59, 61]           # same as DungeonMenus.PER_PAGE
LINE_ARROWS = (54, 62)
LINE_BUTTONS = [0, 8, 11, 13, 15]       # back, info, his stats (his head), shop, party (DungeonMenus order)
SHOP_BUTTON = 13                        # the shop in the middle, on a green plate
# the player's rpg gear, in the inventory's first row under the line (the menu has 4 chest rows): weapon (his class),
# helmet, chestplate, leggings, boots, his rpg inventory to look at, health and mana potions, the pet for dungeons
GEAR_SLOTS = [27, 28, 29, 30, 31, 32, 33, 34, 35]
# his rpg inventory, read only (menu rpg_inventaire): 27 slots then the hotbar, as his own inventory
INV_SLOTS = list(range(9, 36)) + list(range(36, 45))
LINE_ROWS = 4
# pet chosen for the dungeons (menu donjon_familier) and the classes outside the lobby (menu rpg_classes)
PET_SLOTS = [r * 9 + c for r in range(1, 5) for c in range(1, 8)]
CLASS_SLOTS = [11, 13, 15]


def line_pages():
    """[[(dungeon, job, level) or None for the « ? »]] page by page: the dungeons in tower.py's order, then the « ? »."""
    places = [(d, job, level) for f, (d, job, level, *_r) in sorted(TOWER.items())] + [None]
    return [places[i:i + len(LINE_SLOTS)] for i in range(0, len(places), len(LINE_SLOTS))]


def line_cells(page_index, pages):
    """Cells of a page's path in DungeonMenus.route's order: the way in (from the page before, or on the first page the
    way to the first dungeon, green, no stub before it: owner, 2026-11-16), each place and the way to the
    next, the way out to the next page."""
    n = len(pages[page_index])
    cells = [LINE_ARROWS[0]]
    for i in range(n):
        cells.append(LINE_SLOTS[i])
        if i < n - 1:
            cells.append(LINE_SLOTS[i] + 1)
    if page_index < len(pages) - 1:
        cells.append(LINE_SLOTS[n - 1] + 1)
    return cells


SHOP_ARTICLES = [r * 9 + c for r in range(1, 5) for c in range(1, 8)]
SHOP_BUTTONS = [0, 8, 18, 27, 26, 35]     # back, info, job filter, kind filter, tokens, search (DungeonShop order)
SHOP_PAGER = (45, 53)                     # Flux's pager under the articles
KIND_ALL = 4                              # « Tous » of the kind menu: middle of the first row, on a gold plate
KIND_SLOTS = list(range(9, 27))           # the eighteen kinds, two full rows
MEMBER_SLOTS, STATE_SLOTS = [11, 12, 13, 14, 15], [20, 21, 22, 23, 24]
SAS_LEVELS = [31]                     # one level per dungeon since 2026-11-16: its floor card


# --------------------------------------------------------------------------------------------------- backgrounds

def field(im, d, box, txt=None):
    ui.field(im, d, box)
    if txt:
        label(im, txt, (box[0] + box[2]) // 2, box[1] + 4, centre=True, dark_ground=True)


# floors of the tower: (dungeon id, level, colour of the job) in floor order, read from tower.py (one source)
sys.path.insert(0, os.path.join(HERE, "..", "balance"))
from tower import TOWER  # noqa: E402
from tower import DYES as _DYES  # noqa: E402
sys.path.insert(0, os.path.join(HERE, ".."))
from spell_items import icons as spell_icons  # noqa: E402
JOB_COLOURS = _DYES                 # one colour per job: the dye of its leather set (tower.py)


def bg_line(page):
    """A page of the dungeon menu: the plain window (rows 0 to 3 and the inventory's three rows), the shop on a green
    plate, the words « STUFF RPG » centred over the gear in its well, then the line of dungeons in the inventory's
    first row: a frame per dungeon in its job's colour (the « ? » in the theme's violet), its level written above it in
    the gap over the inventory. The path between them is Flux's, drawn over this picture."""
    im = bg_window(GENERIC, 0, 8, extra=3, rows=LINE_ROWS, title="Donjons")
    d = ImageDraw.Draw(im, "RGBA")
    x, y = ui.objet(SHOP_BUTTON)
    ui.button(d, (x - 3, y - 3, x + 18, y + 17), ui.rgb("#3E8E2E"))
    xs = [ui.objet(c)[0] for c in GEAR_SLOTS]
    gy = ui.objet(GEAR_SLOTS[0])[1]
    label(im, "STUFF RPG", 88, gy - 11, centre=True)
    ui.well(d, (max(6, min(xs) - 2), gy - 2, min(ui.W - 7, max(xs) + 17), gy + 17))   # never against the frame
    # his spells under the word « SORTS », in a well as the gear
    sx = [ui.objet(c, LINE_ROWS)[0] for c in SPELL_SLOTS]
    sy = ui.objet(SPELL_SLOTS[0], LINE_ROWS)[1]
    label(im, "SORTS", 88, sy - 10, centre=True)
    ui.well(d, (min(sx) - 2, sy - 2, max(sx) + 17, sy + 17))
    # the line of dungeons under the word « DONJONS » (no levels written: owner, 2026-11-16)
    ly = ui.objet(LINE_SLOTS[0], LINE_ROWS)[1]
    label(im, "DONJONS", 88, ly - 11, centre=True)
    for place, slot in zip(page, LINE_SLOTS):
        colour = VIOLET if place is None else ui.rgb(JOB_COLOURS[place[1]])
        x, y = ui.objet(slot, LINE_ROWS)
        # one rounded frame, its well rounded too (a square well darkened the frame's corners)
        d.rounded_rectangle((x - 3, y - 3, x + 18, y + 18), radius=3, fill=ui.OUTLINE)
        d.rounded_rectangle((x - 2, y - 2, x + 17, y + 17), radius=3, fill=colour)
        d.rounded_rectangle((x - 1, y - 1, x + 16, y + 16), radius=2, fill=ui.WELL)
    return im


def bg_shop():
    """The dungeon shop (as the market): articles in a well, the window down to the pager's row."""
    im = bg_window(GENERIC, 0, 5, title="Donjon")
    d = ImageDraw.Draw(im, "RGBA")
    x0, y0, x1, y1 = span(SHOP_ARTICLES, 2)
    ui.well(d, (x0, y0, x1, y1 - 2))           # 2 px up from Flux's pager
    return im


def bg_pets():
    """The pet menu: pets in a well (Flux draws the pager under it)."""
    im = bg_window(GENERIC, 0, 5, title="Familier")
    d = ImageDraw.Draw(im, "RGBA")
    x0, y0, x1, y1 = span(PET_SLOTS, 2)
    ui.well(d, (x0, y0, x1, y1 - 2))
    return im


def bg_armour():
    """Choosing a piece of armour: the pieces in a well (Flux draws the pager under it)."""
    im = bg_window(GENERIC, 0, 5, title="Armure")
    d = ImageDraw.Draw(im, "RGBA")
    x0, y0, x1, y1 = span(PET_SLOTS, 2)
    ui.well(d, (x0, y0, x1, y1 - 2))
    return im


def bg_inventory():
    """His rpg inventory, read only: its 27 slots in a well, the hotbar in another one under it."""
    im = bg_window(GENERIC, 0, 4, title="Inventaire RPG")
    d = ImageDraw.Draw(im, "RGBA")
    for group in (INV_SLOTS[:27], INV_SLOTS[27:]):
        x0, y0, x1, y1 = span(group, 0)
        ui.well(d, (max(6, x0 + 1), y0 + 1, min(ui.W - 7, x1 - 1), y1))
    return im


def bg_classes():
    """The classes outside the lobby: each class in a well of its own."""
    im = bg_window(GENERIC, 0, 2, title="Classes")
    d = ImageDraw.Draw(im, "RGBA")
    for c in CLASS_SLOTS:
        x, y = ui.objet(c)
        ui.well(d, (x - 3, y - 3, x + 18, y + 18))
    return im


def bg_kinds():
    """The kind menu: « Tous » on a gold plate, the eighteen kinds in a well under it."""
    im = bg_window(GENERIC, 0, 2, title="Type d'objet")
    d = ImageDraw.Draw(im, "RGBA")
    x, y = ui.objet(KIND_ALL)
    ui.button(d, (x - 2, y - 2, x + 17, y + 16), ui.rgb("#C9930E"))
    # nine slots a row fill the window: the well keeps 1 px inside each slot's edge instead of going round it
    x0, y0, x1, y1 = span(KIND_SLOTS, 0)
    ui.well(d, (x0 + 1, y0 + 1, x1 - 1, y1))
    return im


def bg_sas():
    im = bg_window(GENERIC, 0, 5, extra=4, title="Préparation")
    d = ImageDraw.Draw(im, "RGBA")
    # one card per member: head on top, ready / waiting under it
    for head, state in zip(MEMBER_SLOTS, STATE_SLOTS):
        x0, y0, x1, y1 = span([head, state], pad=2)
        ui.container(im, d, (x0, y0, x1, y1), "carte")
        ui.slot(d, head, "vanilla")
        ui.slot(d, state, "dark")
    # the floor card (one level per dungeon): a small well behind it
    x0, y0, x1, y1 = span(SAS_LEVELS, 2)
    ui.well(d, (x0, y0, x1, y1))
    field(im, d, (58, 97, 118, 104))      # players ready (Flux text widget)
    ui.slot(d, 45, "vanilla")
    ui.slot(d, 53, "vanilla")
    # the ready button: a green plate behind it
    x, y = ui.objet(49)
    ui.button(d, (x - 3, y - 3, x + 18, y + 17), ui.rgb("#3E8E2E"))
    return im


# --------------------------------------------------------------------------------------------------- icons

def coin(size=16):
    """Dungeon token: gold coin with a violet rim, a dungeon gate in its middle."""
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = im.load()
    c = (size - 1) / 2
    for y in range(size):
        for x in range(size):
            r = math.hypot(x - c, y - c)
            if r <= c + 0.2:
                px[x, y] = VIOLET_DARK
            if r <= c - 0.8:
                px[x, y] = GOLD_DARK
            if r <= c - 1.8:
                px[x, y] = GOLD if (x + y) < size else ui.rgb("#D89200")
            if c - 1.8 < r <= c - 0.8 and x + y < size - 3:
                px[x, y] = GOLD_LIGHT
    gate = ["..###..", ".#...#.", "#.....#", "#..#..#", "#..#..#", "#.....#"]
    ox, oy = int(c) - 3, int(c) - 2
    for j, row in enumerate(gate):
        for i, ch in enumerate(row):
            if ch == "#":
                px[ox + i, oy + j] = VIOLET
    px[int(c) - 3, int(c) - 4] = WHITE
    px[int(c) - 2, int(c) - 5] = WHITE
    return im


def key():
    """Dungeon key: a gold key, the bow at the top left, the bit at the bottom right."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse((1, 1, 7, 7), fill=GOLD_DARK)
    d.ellipse((2, 2, 6, 6), fill=GOLD)
    d.ellipse((3, 3, 5, 5), fill=VIOLET_DARK)
    for i in range(6, 14):
        d.point((i, i), fill=GOLD)
        d.point((i + 1, i), fill=GOLD_DARK)
        d.point((i, i - 1), fill=GOLD_LIGHT)
    for (x, y) in ((10, 12), (11, 13), (12, 10), (13, 11)):
        d.point((x, y), fill=GOLD)
    d.point((2, 2), fill=WHITE)
    return im


def potion(kind, tier):
    """Rpg potion: round flask (bigger and brighter with the tier), cork, red (health) or blue (mana) liquid."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    liquid = {"soin": ["#7E1B1B", "#C0392B", "#FF5C5C"], "mana": ["#16336E", "#2B6FD6", "#7FB2FF"]}[kind]
    dark, mid, light = (ui.rgb(c) for c in liquid)
    r = [3.5, 4.0, 4.75, 5.5][tier - 1]     # radius of the flask body
    cx, cy = 7.5, 15 - r
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(20, 20, 26, 255))
    d.ellipse((cx - r + 1, cy - r + 1, cx + r - 1, cy + r - 1), fill=(210, 225, 235, 120))
    d.chord((cx - r + 1, cy - r + 1, cx + r - 1, cy + r - 1), -25, 205, fill=mid)
    d.chord((cx - r + 2, cy - r + 2, cx + r - 2, cy + r - 2), 20, 160, fill=dark)
    neck_top = max(0, int(cy - r) - 3)
    d.rectangle((6, neck_top + 1, 9, int(cy - r) + 1), fill=(20, 20, 26, 255))
    d.rectangle((7, neck_top + 2, 8, max(neck_top + 2, int(cy - r) + 1)), fill=(210, 225, 235, 160))
    d.rectangle((6, neck_top, 9, neck_top + 1), fill=ui.rgb("#8B5A2B"))
    d.point((int(cx - r / 2), int(cy - r / 3)), fill=WHITE)
    d.point((int(cx - r / 2) + 1, int(cy - r / 3) - 1), fill=light)
    if tier >= 3:  # sparkles of the big ones
        d.point((14, 4), fill=GOLD_LIGHT)
        d.point((1, 7), fill=GOLD_LIGHT)
    if tier == 4:
        d.point((13, 1), fill=WHITE)
    return im


def unknown_floor():
    """The "?" above the tower: a violet question mark in a pale mist."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse((1, 2, 14, 15), fill=(179, 118, 229, 70))
    q = ["..####..", ".##..##.", ".....##.", "....##..", "...##...", "...##...", "........", "...##..."]
    for j, row in enumerate(q):
        for i, ch in enumerate(row):
            if ch == "#":
                d.point((4 + i, 3 + j), fill=WHITE if j < 2 else ui.rgb("#E6D2FF"))
    return im


def shop_icon():
    """The dungeon shop: a violet purse tied in gold, a token on it."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse((2, 5, 13, 15), fill=VIOLET_DARK)
    d.ellipse((3, 6, 12, 14), fill=VIOLET)
    d.polygon([(5, 2), (10, 2), (9, 6), (6, 6)], fill=VIOLET)
    d.line((5, 6, 10, 6), fill=GOLD)
    d.point((4, 1), fill=GOLD_LIGHT)
    d.point((11, 1), fill=GOLD_LIGHT)
    tok = coin(7)
    im.alpha_composite(tok, (8, 8))
    d.point((5, 8), fill=ui.rgb("#9A6AD8"))
    return im


# --------------------------------------------------------------------------------------------------- pack

def pack():
    for sub in ("textures/gui", "textures/item", "textures/font"):
        os.makedirs(os.path.join(PACK, sub), exist_ok=True)
    lines = ["# Generated by plugins/fluxplugin/FluxDungeons/assets/menus/gen_menus.py: do not edit by hand.",
             "info:", f"  namespace: {NS}", "", "font_images:"]

    def glyph(gid, path, h, y):
        return [f"  {gid}:", "    show_in_gui: false", "    suggest_in_command: false", f'    path: "{path}"',
                f"    scale_ratio: {h}", f"    y_position: {y}"]

    backgrounds = {f"fd_menu_liste_p{n}": bg_line(page) for n, page in enumerate(line_pages(), start=1)}
    backgrounds.update({"fd_menu_sas": bg_sas(), "fd_menu_boutique": bg_shop(), "fd_menu_boutique_type": bg_kinds(),
                        "fd_menu_familier": bg_pets(), "fd_menu_classes": bg_classes(),
                        "fd_menu_armure": bg_armour(), "fd_menu_inventaire": bg_inventory()})
    # pictures of the former menus (the tower, the dungeon page and its boss pictures): gone
    import shutil
    shutil.rmtree(os.path.join(PACK, "textures", "boss"), ignore_errors=True)
    for old in ("fd_menu_liste", "fd_menu_donjon"):
        if os.path.exists(os.path.join(PACK, "textures/gui", old + ".png")):
            os.remove(os.path.join(PACK, "textures/gui", old + ".png"))
    for gid, im in backgrounds.items():
        im.save(os.path.join(PACK, "textures/gui", gid + ".png"))
        lines += glyph(gid, f"gui/{gid}.png", im.height, 13)
    coin(16).save(os.path.join(PACK, "textures/font", "jeton_donjon.png"))
    lines += ["  jeton_donjon:", "    show_in_gui: true", "    suggest_in_command: true",
              '    path: "font/jeton_donjon.png"', "    scale_ratio: 9", "    y_position: 8"]
    lines += ["", "items:"]
    icons = {"jeton_donjon": (coin(16), "Jeton de donjon"), "cle_donjon": (key(), "Clé de donjon"),
             "etage_inconnu": (unknown_floor(), "Inconnu"), "boutique": (shop_icon(), "Boutique de donjon")}
    for kind in ("soin", "mana"):
        for tier in range(1, 5):
            icons[f"potion_{kind}_{tier}"] = (potion(kind, tier), "Potion")
    # every class's spells (assets/spell_items.py): sort_<type>, attaque_<style>, passif_<class>
    icons.update(spell_icons())
    for iid, (im, name) in icons.items():
        im.save(os.path.join(PACK, "textures/item", iid + ".png"))
        lines += [f"  {iid}:", f"    display_name: '{name}'", "    resource:", "      material: PAPER",
                  "      generate: true", "      textures:", f"      - item/{iid}.png"]
    open(os.path.join(PACK, "config.yml"), "w", encoding="utf-8").write("\n".join(lines) + "\n")
    if PREVIEW:
        os.makedirs(PREVIEW, exist_ok=True)
        for gid, im in backgrounds.items():
            im.resize((im.width * 3, im.height * 3), Image.NEAREST).save(os.path.join(PREVIEW, gid + ".png"))
        sheet = Image.new("RGBA", (16 * len(icons), 16), (46, 42, 56, 255))
        for i, (im, _) in enumerate(icons.values()):
            sheet.alpha_composite(im, (i * 16, 0))
        sheet.resize((sheet.width * 6, sheet.height * 6), Image.NEAREST).save(os.path.join(PREVIEW, "icons.png"))
    print("pack", NS, "->", PACK)


# --------------------------------------------------------------------------------------------------- menus

def q(text):
    return '"' + str(text).replace("\\", "\\\\").replace('"', '\\"') + '"'


def entries(lid, slots, left=None, right=None, extra=()):
    out = [f"  {lid}:", f"    slots: {', '.join(map(str, slots))}", "    item:", "      material: entry"]
    out += [f"      {line}" for line in extra]
    if left or right:
        out.append("      actions:")
        if left:
            out.append(f"        left: [{', '.join(q(a) for a in left)}]")
        if right:
            out.append(f"        right: [{', '.join(q(a) for a in right)}]")
    return "\n".join(out)


def menus():
    # the former dungeon page (and its page file) is gone: every dungeon is prepared from the line
    for old in (os.path.join(MENUS, "donjon.yml"), os.path.join(MENUS, "_pages", "donjon.yml")):
        if os.path.exists(old):
            os.remove(old)
    head = "# Generated by plugins/fluxplugin/FluxDungeons/assets/menus/gen_menus.py: do not edit by hand.\n"
    # every button is built by FluxDungeons (lists): the files only place them
    common = "player-inventory: hidden\nvanilla-background: false\n"
    pages = line_pages()
    page_blocks = []
    for n, page in enumerate(pages, start=1):
        cells = line_cells(n - 1, pages)
        arrows = []
        if n > 1:
            arrows.append(f"""      precedent:
        material: "itemsadder:fluxui:btn_left_purple"
        slots: [{LINE_ARROWS[0]}]
        hide-tooltip: true
        actions:
          left: ["[list] donjons_liste prev", "[page-swap] donjons_p%flux_list_donjons_liste_page%"]""")
        if n < len(pages):
            arrows.append(f"""      suivant:
        material: "itemsadder:fluxui:btn_right_purple"
        slots: [{LINE_ARROWS[1]}]
        hide-tooltip: true
        actions:
          left: ["[list] donjons_liste next", "[page-swap] donjons_p%flux_list_donjons_liste_page%"]""")
        page_blocks.append(f"""  donjons_p{n}:
    title: ":offset_-8::fd_menu_liste_p{n}:"
    paths:
      ligne:
        slots: [{", ".join(map(str, cells))}]
        progress: "{len(cells)}"
        states: "%fluxdungeons_tl_route_{n}%"
        color: vert
        start: false
        end: false
    items:
""" + "\n".join(arrows))
    open(os.path.join(MENUS, "donjons.yml"), "w", encoding="utf-8").write(head + f"""# The dungeons in a line (/donjon, 2026-11-16): a page shows {len(LINE_SLOTS)} of them, from level 10 to 100, then a « ? ».
# Left click: prepare the dungeon (its launch screen); right click: its page of the dungeon shop. Opens on the page of
# the dungeon to play. Each page has its picture (frames, levels) and its path (Flux's, coloured by DungeonMenus).

title: ":offset_-8::fd_menu_liste_p1:"
rows: 4
{common}update-interval: 20

# Flux pages the line (the page sound of every menu; /donjon sets it on the dungeon to play, silently, before
# opening); the page of the menu (its picture and path) follows it
open-actions:
  - "[page-swap] donjons_p%flux_list_donjons_liste_page%"

lists:

{entries("donjons_boutons", LINE_BUTTONS, left=["%entry_left%", "%entry_left2%"])}
{entries("donjons_liste", LINE_SLOTS, left=["%entry_left%"], right=["%entry_right%"])}
{entries("donjons_equipement", GEAR_SLOTS, left=["%entry_left%"], right=["%entry_right%"])}
{entries("donjons_sorts", SPELL_SLOTS, left=["%entry_left%"])}

pages:
""" + "\n".join(page_blocks) + "\n")
    open(os.path.join(MENUS, "donjon_boutique.yml"), "w", encoding="utf-8").write(head + f"""# The dungeon shop (reward.DungeonShop, 2026-11-16), laid out as the market: articles bought in Flux's buying window,
# filtered by job and by kind (two filter buttons), searched, paged by Flux. Contents and prices: shop.yml
# (assets/balance/tower.py).

title: ":offset_-8::fd_menu_boutique:"
rows: 6
{common}update-interval: 20

lists:

{entries("boutique_boutons", SHOP_BUTTONS, left=["%entry_left%"], right=["%entry_right%"])}
  boutique_articles:
    slots: "{", ".join(map(str, SHOP_ARTICLES))}"
    item:
      material: entry
      actions:
        left: ["%entry_left%"]
    empty:
      material: "itemsadder:influitems:unknown"
      name: "Rien ici"
      lore:
        - "@desc Rien ne correspond. Essaie un autre filtre, une autre recherche ou « Tous »."

widgets:
  pages:
    type: pager
    list: boutique_articles
    prev: {SHOP_PAGER[0]}
    next: {SHOP_PAGER[1]}
""")
    open(os.path.join(MENUS, "donjon_boutique_type.yml"), "w", encoding="utf-8").write(head + f"""# The kind of item of the dungeon shop (reward.DungeonShop), chosen here: « Tous » apart, then the eighteen kinds.
# A click shows that kind in the shop (Flux's kind filter of its list).

title: ":offset_-8::fd_menu_boutique_type:"
rows: 3
{common}update-interval: 20

lists:

{entries("boutique_type_boutons", [0, 8], left=["%entry_left%"])}
{entries("boutique_types", [KIND_ALL] + KIND_SLOTS, left=["%entry_left%", "[menu] donjon_boutique"])}
""")
    open(os.path.join(MENUS, "donjon_familier.yml"), "w", encoding="utf-8").write(head + f"""# The pet a player takes into the dungeons (menu.DungeonMenus): one, never a boss's; « none » first.

title: ":offset_-8::fd_menu_familier:"
rows: 6
{common}update-interval: 20

lists:

{entries("donjon_familier_boutons", [0, 8], left=["%entry_left%"])}
  donjon_familiers:
    slots: "{", ".join(map(str, PET_SLOTS))}"
    item:
      material: entry
      actions:
        left: ["%entry_left%", "[menu] donjons"]

widgets:
  pages:
    type: pager
    list: donjon_familiers
    prev: 45
    next: 53
""")
    open(os.path.join(MENUS, "rpg_armure.yml"), "w", encoding="utf-8").write(head + f"""# Choosing a piece of armour among the player's rpg inventory (menu.DungeonMenus): the worn piece, then the others
# of that slot; a click puts one on (rpg universe only, his real inventory).

title: ":offset_-8::fd_menu_armure:"
rows: 6
{common}update-interval: 20

lists:

{entries("rpg_armure_boutons", [0, 8], left=["%entry_left%"])}
  rpg_armures:
    slots: "{", ".join(map(str, PET_SLOTS))}"
    item:
      material: entry
      actions:
        left: ["%entry_left%"]

widgets:
  pages:
    type: pager
    list: rpg_armures
    prev: 45
    next: 53
""")
    open(os.path.join(MENUS, "rpg_inventaire.yml"), "w", encoding="utf-8").write(head + f"""# The player's rpg inventory, read only (menu.DungeonMenus, Flux's Universes.contents: copies). Nothing can be taken.

title: ":offset_-8::fd_menu_inventaire:"
rows: 5
{common}update-interval: 20

lists:

{entries("rpg_inventaire_boutons", [0, 8], left=["%entry_left%"])}
{entries("rpg_inventaire_objets", INV_SLOTS)}
""")
    open(os.path.join(MENUS, "rpg_classes.yml"), "w", encoding="utf-8").write(head + f"""# The classes outside the rpg lobby (rpg.ClassMenu): a plain menu (the lobby keeps its showcase). His level in each
# class; a click chooses it, with the rules of /classe (a change from the lobby only).

title: ":offset_-8::fd_menu_classes:"
rows: 3
{common}update-interval: 20

lists:

{entries("rpg_classes_boutons", [0, 8], left=["%entry_left%"])}
{entries("rpg_classes_liste", CLASS_SLOTS, left=["%entry_left%"])}
""")
    lit = ""
    open(os.path.join(MENUS, "donjon_sas.yml"), "w", encoding="utf-8").write(head + f"""# Launch screen of a dungeon (dungeon.Lobby): who comes, the difficulty chosen by the leader, everybody ready.

title: ":offset_-8::fd_menu_sas:"
rows: 6
{common}update-interval: 20

lists:

{entries("donjon_sas_boutons", [0, 8, 45, 53], left=["%entry_left%", "%entry_left2%"])}
{entries("donjon_sas_membres", MEMBER_SLOTS)}
{entries("donjon_sas_etats", STATE_SLOTS)}
{entries("donjon_sas_niveaux", SAS_LEVELS, left=["[fluxdungeon] sas level %entry_id%"],
         right=["[fluxdungeon] sas level %entry_id%"])}
{entries("donjon_sas_pret", [49], left=["[fluxdungeon] sas ready"])}

widgets:
  prets:
    type: text
    x: 88
    y: 98
    align: center
    text: "%fluxdungeons_sas_ready%"
{lit}
""")
    print("menus ->", MENUS)


if __name__ == "__main__":
    pack()
    menus()
