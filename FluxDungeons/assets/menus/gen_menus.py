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
  donjons   the dungeons in a line (2026-11-16, owner's layout), rows 0 to 3: back (0), info (8); class, party, tokens,
            shop (10, 12, 14, 16: above the line, on its columns; the shop on a green plate); the line on row 3: four
            dungeons a page (28, 30, 32, 34), Flux's path through them and on to the arrows (27, 35) that turn the page,
            each dungeon in a frame of its job's colour with its level written above it; the « ? » after the last one
            (the adventure goes on). One picture and one path per page (pages donjons_p<n> of the menu).
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
LINE_SLOTS = [28, 30, 32, 34]           # same as DungeonMenus.PER_PAGE
LINE_ARROWS = (27, 35)
LINE_BUTTONS = [0, 8, 10, 12, 14, 16]   # back, info, class, party, tokens, shop (DungeonMenus order)
SHOP_BUTTON = 16


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
JOB_COLOURS = _DYES                 # one colour per job: the dye of its leather set (tower.py)


def bg_line(page):
    """A page of the line: the plain window (rows 0 to 3), a frame per dungeon in its job's colour (the « ? » in the
    theme's violet) with its level written above it, the shop on a green plate. The path between them is Flux's,
    drawn over this picture."""
    im = bg_window(GENERIC, 0, 3, title="Donjons")
    d = ImageDraw.Draw(im, "RGBA")
    for place, slot in zip(page, LINE_SLOTS):
        colour = VIOLET if place is None else ui.rgb(JOB_COLOURS[place[1]])
        x, y = ui.objet(slot)
        # one rounded frame, its well rounded too (a square well darkened the frame's corners)
        d.rounded_rectangle((x - 3, y - 3, x + 18, y + 18), radius=3, fill=ui.OUTLINE)
        d.rounded_rectangle((x - 2, y - 2, x + 17, y + 17), radius=3, fill=colour)
        d.rounded_rectangle((x - 1, y - 1, x + 16, y + 16), radius=2, fill=ui.WELL)
        label(im, "?" if place is None else str(place[2]), x + 8, y - 11, centre=True)
    x, y = ui.objet(SHOP_BUTTON)
    ui.button(d, (x - 3, y - 3, x + 18, y + 17), ui.rgb("#3E8E2E"))
    return im


def bg_shop():
    """The dungeon shop (as the market): articles in a well, the window down to the pager's row."""
    im = bg_window(GENERIC, 0, 5, title="Donjon")
    d = ImageDraw.Draw(im, "RGBA")
    x0, y0, x1, y1 = span(SHOP_ARTICLES, 2)
    ui.well(d, (x0, y0, x1, y1 - 2))           # 2 px up from Flux's pager
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
    backgrounds.update({"fd_menu_sas": bg_sas(), "fd_menu_boutique": bg_shop(), "fd_menu_boutique_type": bg_kinds()})
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
