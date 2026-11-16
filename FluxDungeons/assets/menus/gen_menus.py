#!/usr/bin/env python3
"""Dungeon menus of FluxDungeons (2026-11-14), on the theme of Flux (inventory style since 2026-11-15): backgrounds, icons, menu files.

    <venv with Pillow>/python3 gen_menus.py <plugins dir of the server> [preview dir]

Writes:
  ItemsAdder/contents/fluxdonjon/   backgrounds fd_menu_liste / fd_menu_donjon / fd_menu_sas, the big picture of each
                                    dungeon's boss (fd_boss_<dungeon>, 64 x 64, rendered from its BetterModel model),
                                    the token glyph jeton_donjon (%img_jeton_donjon%), items jeton_donjon, cle_donjon,
                                    potion_soin_<1-4>, potion_mana_<1-4>
  FluxDungeons/menus/               donjons.yml (list), donjon.yml + _pages/donjon.yml (one page per dungeon: its job and
                                    boss picture), donjon_sas.yml (launch screen)
Lists, actions and placeholders are FluxDungeons' (menu.DungeonMenus, dungeon.Lobby). Then /iazip and /fluxmenu reload.

Layouts (window px 176 wide, item of a slot at fond_lib.objet):
  donjons   the dungeon tower (2026-11-16, plain window since the owner's feedback of the same day: no bricks), the
            whole window (chest and hidden inventory, rows 0 to 9): floors climbing in switchbacks from floor 1
            (hotbar row, left) to floor 10 (row 1, right), two a storey, each in a frame of its job's colour with its
            level beside it, joined by Flux's path pieces (the connectors of the ranks menu): green up to the last
            floor beaten, orange to the floor to play, off above; the path ends on the "?" (slot 6, above floor 10:
            the tower goes on), lit once the tower is beaten. Row 0: back (0), "?" (6), info (8); beside the floors: class (36), party (44), tokens (72),
            shop (80, on a green plate). A floor beaten is lit by Flux (won_<floor>).
  donjon_boutique  the dungeon shop (2026-11-16, rows 0 to 8): back (0), the page's name in a field, info (8);
            articles in a well (rows 1-4, columns 1-7: 28, the largest page has 25); tokens (49); the ten floors as
            tabs in the inventory, floors 1-5 on the lower row, 6-10 above (the tower again), the page shown lit.
  donjon    row 0: back (0), job of the dungeon in a dark field, info (8); rows 1-4 left: the boss picture in a gold
            frame; rows 2-4 right: possible loot (12 slots, header « Butin »); row 5: the key (49, on a green plate:
            click = launch screen), tokens (53). The difficulty is chosen on the launch screen only.
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
IMAGE_X, IMAGE_TOP = 9, 38           # boss picture of the dungeon page (64 x 64)

# dungeon id -> (job id, job name, boss model) ; the boss pets and their jobs are in scripts/influcard/familiers_boss.py
from familiers_boss import BOSSES  # noqa: E402
DUNGEONS = [(b[4], b[3], b[2], b[0]) for b in BOSSES]   # (dungeon, job, model, pet)
JOB_NAMES = {"mineur": "Mineur", "rochiste": "Rochiste", "deblayeur": "Déblayeur", "cultivateur": "Cultivateur",
             "herboriste": "Herboriste", "sylviculteur": "Bûcheron", "fondeur": "Fondeur",
             "enchanteur": "Enchanteur", "pecheur": "Pêcheur", "defenseur": "Défenseur"}

# the tower: floor n at TOWER_SLOTS[n - 1] (switchbacks, two floors a storey, from the hotbar row up)
TOWER_SLOTS = [83, 87, 69, 65, 47, 51, 33, 29, 11, 15]
TOWER_BUTTONS = [0, 6, 8, 36, 44, 72, 80]     # back, "?", info, class, party, tokens, shop (DungeonMenus order)
UNKNOWN, SHOP_BUTTON = 6, 80


def tower_route():
    """Cells of the path from floor 1 to floor 10 (each next to the one before, as Flux's paths want) and the place
    of every floor in it: straight between the two floors of a storey, one cell up between storeys."""
    cells = [TOWER_SLOTS[0]]
    for a, b in zip(TOWER_SLOTS, TOWER_SLOTS[1:]):
        ra, ca, rb, cb = a // 9, a % 9, b // 9, b % 9
        if ra == rb:
            step = 1 if cb > ca else -1
            cells += [ra * 9 + c for c in range(ca + step, cb + step, step)]
        else:
            cells += [r * 9 + ca for r in range(ra - 1, rb - 1, -1)]
    # the "?" is the path's last cell, above floor 10: off until the tower is beaten
    return cells + [UNKNOWN], [cells.index(s) for s in TOWER_SLOTS]
SHOP_ARTICLES = [r * 9 + c for r in range(1, 5) for c in range(1, 8)]
SHOP_FLOORS = [74, 75, 76, 77, 78, 65, 66, 67, 68, 69]   # floors 1-5 on the lower row, 6-10 above
SHOP_BUTTONS = [0, 8, 49]
LOOT_SLOTS = [23, 24, 25, 26, 32, 33, 34, 35, 41, 42, 43, 44]
MEMBER_SLOTS, STATE_SLOTS = [11, 12, 13, 14, 15], [20, 21, 22, 23, 24]
SAS_LEVELS = [31]                     # one level per dungeon since 2026-11-16: its floor card
DETAIL_KEY = 49


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


def bg_list():
    """The dungeon tower on the theme's plain window: a frame per floor in the colour of its job, its level beside
    it (left of a left floor, right of a right one), the shop on a green plate (the "?" above the top has no frame:
    it would touch floor 10's). The road between the floors is Flux's path, drawn over this picture."""
    im = bg_window(GENERIC, 0, 9, title="Donjons")
    d = ImageDraw.Draw(im, "RGBA")

    def frame(slot, colour):
        x, y = ui.objet(slot)
        d.rounded_rectangle((x - 3, y - 3, x + 18, y + 18), radius=3, fill=ui.tone(colour, -0.55), outline=ui.OUTLINE)
        d.rounded_rectangle((x - 2, y - 2, x + 17, y + 17), radius=3, outline=colour)
        d.rectangle((x - 1, y - 1, x + 16, y + 16), fill=ui.WELL)

    for floor, (dungeon, job, level, party, _, _) in sorted(TOWER.items()):
        slot = TOWER_SLOTS[floor - 1]
        x, y = ui.objet(slot)
        frame(slot, ui.rgb(JOB_COLOURS[job]))
        txt = f"{level}"
        if slot % 9 <= 4:
            label(im, txt, x - 6 - 4 * len(txt), y + 5)
        else:
            label(im, txt, x + 21, y + 5)
    x, y = ui.objet(SHOP_BUTTON)
    ui.button(d, (x - 3, y - 3, x + 18, y + 17), ui.rgb("#3E8E2E"))
    return im


def bg_shop():
    """The dungeon shop: the page's name in a field, articles in a well, tokens, the floors as tabs in the
    inventory (a little tower: floors 1-5 below, 6-10 above) in a well headed « Étages »."""
    im = bg_window(GENERIC, 0, 8, title="Boutique")
    d = ImageDraw.Draw(im, "RGBA")
    field(im, d, (26, 19, 150, 32))
    x0, y0, x1, y1 = span(SHOP_ARTICLES, 2)
    ui.well(d, (x0, y0 + 2, x1, y1))           # 1 px of air under the name field
    fx0, fy0, fx1, fy1 = span(SHOP_FLOORS, 2)
    ui.well(d, (fx0, fy0, fx1, fy1))
    label(im, "DONJONS", (fx0 + fx1) // 2, fy0 - 9, centre=True)
    return im


def bg_detail():
    im = bg_window(GENERIC, 0, 5, title="Donjon")
    d = ImageDraw.Draw(im, "RGBA")
    field(im, d, (26, 19, 150, 32))
    # gold frame of the boss picture (rows 1 to 4, left)
    x0, y0, x1, y1 = IMAGE_X - 2, IMAGE_TOP - 2, IMAGE_X + 65, IMAGE_TOP + 65
    d.rectangle((x0, y0, x1, y1), fill=BLACK)
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), fill=GOLD)
    d.line((x0 + 1, y0 + 1, x1 - 1, y0 + 1), fill=GOLD_LIGHT)
    d.line((x0 + 1, y0 + 1, x0 + 1, y1 - 1), fill=GOLD_LIGHT)
    d.rectangle((x0 + 2, y0 + 2, x1 - 2, y1 - 2), fill=VIOLET_DARK)
    d.line((x0 + 1, y1 + 1, x1 - 1, y1 + 1), fill=ui.SHADE_LINE)
    # possible loot
    bx0, by0, bx1, by1 = span(LOOT_SLOTS)
    ui.container(im, d, (bx0, by0 - 11, bx1, by1), "ardoise", head="Butin", accent=GOLD_DARK)
    for c in LOOT_SLOTS:
        ui.slot(d, c, "dark")
    # the shop (53) on a gold plate, the key (49): launch screen of the dungeon, on a green plate
    x, y = ui.objet(53)
    ui.button(d, (x - 3, y - 3, x + 18, y + 17), ui.rgb("#C9930E"))
    x, y = ui.objet(DETAIL_KEY)
    ui.button(d, (x - 3, y - 3, x + 18, y + 17), ui.rgb("#3E8E2E"))
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


def boss_picture(model):
    """64 x 64 three-quarter render of a boss model (dark outline), for the dungeon page."""
    import apercu
    import bbrender
    path = os.path.join(PLUGINS, "BetterModel", "models", model + ".bbmodel")
    elements, tex = bbrender.load(path)
    art = apercu.render(elements, tex, 35, 18, size=62, bg=None, margin=2)
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    px = im.load()
    for y in range(62):
        for x in range(62):
            if art[y][x] is not None:
                px[x + 1, y + 1] = tuple(art[y][x])
    outline = []
    for y in range(64):
        for x in range(64):
            if px[x, y][3] == 0 and any(0 <= x + dx < 64 and 0 <= y + dy < 64 and px[x + dx, y + dy][3]
                                        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                outline.append((x, y))
    for x, y in outline:
        px[x, y] = (13, 13, 13, 255)
    return im


# --------------------------------------------------------------------------------------------------- pack

def pack():
    for sub in ("textures/gui", "textures/item", "textures/font", "textures/boss"):
        os.makedirs(os.path.join(PACK, sub), exist_ok=True)
    lines = ["# Generated by plugins/fluxplugin/FluxDungeons/assets/menus/gen_menus.py: do not edit by hand.",
             "info:", f"  namespace: {NS}", "", "font_images:"]

    def glyph(gid, path, h, y):
        return [f"  {gid}:", "    show_in_gui: false", "    suggest_in_command: false", f'    path: "{path}"',
                f"    scale_ratio: {h}", f"    y_position: {y}"]

    backgrounds = {"fd_menu_liste": bg_list(), "fd_menu_donjon": bg_detail(), "fd_menu_sas": bg_sas(),
                   "fd_menu_boutique": bg_shop()}
    for gid, im in backgrounds.items():
        im.save(os.path.join(PACK, "textures/gui", gid + ".png"))
        lines += glyph(gid, f"gui/{gid}.png", im.height, 13)
    for dungeon, job, model, pet in DUNGEONS:
        try:
            pic = boss_picture(model)
        except FileNotFoundError:
            print("no model for", dungeon, model)
            continue
        pic.save(os.path.join(PACK, "textures/boss", dungeon + ".png"))
        lines += glyph(f"fd_boss_{dungeon}", f"boss/{dungeon}.png", 64, 13 - IMAGE_TOP)
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
    os.makedirs(os.path.join(MENUS, "_pages"), exist_ok=True)
    head = "# Generated by plugins/fluxplugin/FluxDungeons/assets/menus/gen_menus.py: do not edit by hand.\n"
    # every button is built by FluxDungeons (lists): the files only place them
    common = "player-inventory: hidden\nvanilla-background: false\n"
    cells, at = tower_route()
    open(os.path.join(MENUS, "donjons.yml"), "w", encoding="utf-8").write(head + f"""# The dungeon tower (/donjon, 2026-11-16): a floor per dungeon from level 10 at the bottom to 100 at the top, a
# "?" above. Left click: the dungeon (menu donjon); right click: its page of the dungeon shop.

title: ":offset_-8::fd_menu_liste:"
rows: 6
{common}update-interval: 20

lists:

{entries("donjons_boutons", TOWER_BUTTONS, left=["%entry_left%", "%entry_left2%"])}
{entries("donjons_liste", TOWER_SLOTS, left=["[fluxdungeon] view %entry_id%", "[menu] donjon"], right=["%entry_right%"])}

# the road between the floors (Flux's paths, as the ranks menu): one colour per cell from DungeonMenus, green up to
# the last floor beaten, orange to the floor to play; the "?" (last cell) lights once floor 10 is beaten
paths:
  tour:
    slots: [{", ".join(map(str, cells))}]
    progress: "{len(cells)}"
    states: "%fluxdungeons_route_{'_'.join(map(str, at))}_{len(cells)}%"
    color: vert
    start: false
    end: false
""")
    lit = "\n".join(f"""  etage_{f}:
    type: lit
    slot: {slot}
    view-requirements:
      - "placeholder: %fluxdungeons_shop_floor% == {f}\"""" for f, slot in enumerate(SHOP_FLOORS, start=1))
    open(os.path.join(MENUS, "donjon_boutique.yml"), "w", encoding="utf-8").write(head + f"""# The dungeon shop (reward.DungeonShop, 2026-11-16): a page per floor of the tower, articles bought in Flux's
# buying window. Contents and prices: shop.yml (assets/balance/tower.py).

title: ":offset_-8::fd_menu_boutique:"
rows: 6
{common}update-interval: 20

lists:

{entries("boutique_boutons", SHOP_BUTTONS, left=["%entry_left%"])}
{entries("boutique_articles", SHOP_ARTICLES, left=["%entry_left%"])}
{entries("boutique_etages", SHOP_FLOORS, left=["%entry_left%", "[refresh]"])}

widgets:
  page:
    type: text
    x: 88
    y: 23
    align: center
    text: "%fluxdungeons_shop_title%"
{lit}
""")
    open(os.path.join(MENUS, "donjon.yml"), "w", encoding="utf-8").write(head + f"""# A dungeon (opened from donjons): its boss, the possible loot, its key (click: launch screen).
# One page per dungeon (_pages/donjon.yml): its job in the field and its boss picture.

title: ":offset_-8::fd_menu_donjon:"
rows: 6
{common}update-interval: 20

open-actions:
  - "[page-swap] donjon_%fluxdungeons_selected%"

page-files:
  - _pages/donjon.yml

lists:

{entries("donjon_boutons", [0, 8, DETAIL_KEY, 53], left=["%entry_left%", "%entry_left2%"])}
{entries("donjon_butin", LOOT_SLOTS, left=["%entry_left%"])}
""")
    pages = [head, "# Pages of the dungeon menu, one per dungeon: its job and its boss.\n\npages:\n"]
    for dungeon, job, model, pet in DUNGEONS:
        pages.append(f"""
  donjon_{dungeon}:
    title: ":offset_-8::fd_menu_donjon:"
    widgets:
      nom:
        type: text
        x: 88
        y: 23
        align: center
        text: {q("Donjon " + JOB_NAMES[job])}
      boss:
        type: image
        glyph: "fd_boss_{dungeon}"
        x: {IMAGE_X}
        width: 64
""")
    open(os.path.join(MENUS, "_pages", "donjon.yml"), "w", encoding="utf-8").write("".join(pages))
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
