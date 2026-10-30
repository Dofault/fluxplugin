#!/usr/bin/env python3
"""Vanilla theme of the Flux menus (2026-10-29): the ItemsAdder pack "fluxui" and the backgrounds built with it.

    <venv with Pillow>/python3 gen_ui.py <plugins dir of the server> [<Flux resources dir>]

Writes:
  ItemsAdder/contents/fluxui/config.yml          buttons (items fluxui:btn_<icon>_<colour>, _off = disabled) and
                                                 the backgrounds (font images fluxui:bg_<menu>)
  ItemsAdder/contents/fluxui/textures/...        their pictures, and those of the fonts (textures/font)
  ItemsAdder/contents/fluxui_fonts/resourcepack/  fonts fluxui:y<Y> (Y = window y of the top, 0 to 220): the menu
                                                 font (QuinqueFive capitals, white, 1 px shadow under each column),
                                                 progress bar chunks, sprites (pager field, selected slot)
  <Flux resources>/ui/glyphs.yml                 width of every character, read by Flux to place text (UiTitle)
then /iazip. Flux draws text, bars and pagers in the menu titles from these fonts (widgets: of a menu,
docs/technique/flux-ui.md); everything static (window, sub-containers, slots, frames, labels) is in the backgrounds.
"""
import json
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ui  # noqa: E402


def game_objet(case, rows=6):
    """Item position of a raw slot as drawn in game (inventory 1 px higher than fond_lib.objet computes)."""
    top = rows * 9
    if case < top:
        return 8 + 18 * (case % 9), 18 + 18 * (case // 9)
    i = case - top
    if i < 27:
        return 8 + 18 * (i % 9), 103 + (rows - 4) * 18 + 18 * (i // 9)
    return 8 + 18 * (i - 27), 161 + (rows - 4) * 18


ui.objet = game_objet
ui.fond_lib.objet = game_objet
from ui import BLACK, WHITE, rgb, tone  # noqa: E402

NS = "fluxui"
TEXT_SHADOW = (40, 40, 40, 255)
# characters of the menu font (lower case is drawn upper case by Flux)
CHARS = (list("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") + list("ÉÈÊÀÂÎÔ") + list("/%.:+-!'·?,()"))
CELL_W, CELL_H = 8, 8          # accent rows 0-1, letter rows 2-6, shadow row 7
COLS = 16
BAR_H = 10                     # inner height of a progress bar (its outline is in the background)
CHUNKS = [1, 2, 4, 8, 16, 32, 64, 128]
BAR_COLOURS = {"green": rgb("#3E8E2E"), "gold": rgb("#D9A21B"), "blue": rgb("#3D7FB8"), "red": rgb("#C0392B"),
               "purple": rgb("#7A48C0")}
CHUNK_BASE = 0xE000            # + 16 * colour index + chunk index
SPRITE_BASE = 0xE100
PAGER_W, PAGER_H = 70, 16      # pager field: 15 high + the white line under it
# font sprites: U+E100 pager field, U+E101 selected slot (20 x 20), U+E102-E105 pager buttons (left on/off, right
# on/off), U+E106 vanilla slot, U+E107 dark slot (18 x 18)
PAGER_BTN_W = 36               # pager button: 2 slots wide
# button faces of the vanilla theme: bright colours on the grey window (not the Influencraft violet palette)
BUTTON_COLOURS = {"red": rgb("#C0392B"), "green": rgb("#3E8E2E"), "blue": rgb("#3D7FB8"), "orange": rgb("#E07B24"),
                  "purple": rgb("#7A48C0"), "grey": rgb("#8B8B8B"), "gold": rgb("#D9A21B")}


# ---------------------------------------------------------------------------------------------------- font

fond = ui.fond_lib
fond._EXTRA.update({"?": ["###", "..#", ".##", "...", ".#."], ",": [".", ".", ".", "#", "#"],
                    "(": [".#", "#.", "#.", "#.", ".#"], ")": ["#.", ".#", ".#", ".#", "#."]})


def glyph_sheet(shadow=True):
    rows = (len(CHARS) + COLS - 1) // COLS
    sheet = Image.new("RGBA", (COLS * CELL_W, rows * CELL_H), (0, 0, 0, 0))
    px = sheet.load()
    widths = {}
    for i, c in enumerate(CHARS):
        g, extra = fond._glyph(c)
        ox, oy = (i % COLS) * CELL_W, (i // COLS) * CELL_H
        w = len(g[0])
        for dy, row in enumerate(g):            # shadow first, one pixel under each column
            for dx, ch in enumerate(row):
                if ch == "#" and shadow:
                    px[ox + dx, oy + 2 + dy + 1] = TEXT_SHADOW
        for dy, row in enumerate(g):
            for dx, ch in enumerate(row):
                if ch == "#":
                    px[ox + dx, oy + 2 + dy] = WHITE
        for dx, dy in extra:
            px[ox + dx, oy + 2 + dy] = WHITE
        widths[c] = w
    grid = ["".join(CHARS[r * COLS:(r + 1) * COLS]).ljust(COLS, "\u0000") for r in range(rows)]
    return sheet, widths, grid


def chunk(colour, w):
    im = Image.new("RGBA", (w, BAR_H), colour)
    d = ImageDraw.Draw(im)
    d.line((0, 0, w - 1, 0), fill=tone(colour, 0.4))
    d.line((0, BAR_H - 1, w - 1, BAR_H - 1), fill=tone(colour, -0.3))
    return im


def sprite_pager():
    im = Image.new("RGBA", (PAGER_W, PAGER_H), (0, 0, 0, 0))
    ui.field(im, ImageDraw.Draw(im), (0, 0, PAGER_W - 1, PAGER_H - 2))
    ImageDraw.Draw(im).line((1, PAGER_H - 1, PAGER_W - 2, PAGER_H - 1), fill=WHITE)
    return im


def sprite_pager_button(direction, on):
    """Light pager button of the shop reference, 2 slots wide; greyed arrow when there is no page that way."""
    im = Image.new("RGBA", (PAGER_BTN_W, PAGER_H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    ui.flat_button(d, (0, 0, PAGER_BTN_W - 1, PAGER_H - 2), direction)
    d.line((1, PAGER_H - 1, PAGER_BTN_W - 2, PAGER_H - 1), fill=WHITE)
    if not on:
        px = im.load()
        for y in range(PAGER_H):
            for x in range(PAGER_BTN_W):
                if px[x, y] == rgb("#6B6B6B"):
                    px[x, y] = rgb("#B4B4B4")
    return im


def sprite_slot(kind):
    """A slot of the theme, 18 x 18 from (slot x - 1, slot y - 1): vanilla (light window) or dark (containers)."""
    im = Image.new("RGBA", (18, 18), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    saved = ui.objet
    ui.objet = lambda case, rows=6: (1, 1)
    ui.slot(d, 0, kind)
    ui.objet = saved
    return im


def sprite_lit():
    """Selected slot: light raised cell with a white outline, 20 x 20 from (slot x - 2, slot y - 2)."""
    im = Image.new("RGBA", (20, 20), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, 19, 19), outline=WHITE)
    d.rectangle((1, 1, 18, 18), fill=rgb("#D9D9D9"))
    d.line((1, 1, 17, 1), fill=WHITE)
    d.line((1, 1, 1, 17), fill=WHITE)
    d.line((2, 18, 18, 18), fill=rgb("#6E6E6E"))
    d.line((18, 2, 18, 18), fill=rgb("#6E6E6E"))
    return im


# ---------------------------------------------------------------------------------------------------- buttons

# Buttons in the style of the former theme (influitems back, add, deny...) with square corners (2026-10-30):
# 14 x 14 in the 16 x 16 picture, outline, light top line and sides, darker band at the bottom, icon engraved in a
# dark tone of the face with a light pixel under it. Roles: a outline, b top light, c face, d side light, e dark.
TEMPLATE = ["................", ".aaaaaaaaaaaaaa.", ".abbbbbbbbbbbba.", ".abccccccccccba."] + \
           [".adccccccccccda."] * 7 + \
           [".adeeeeeeeeeeda.", ".adddddddddddda.", ".aeeeeeeeeeeeea.", ".aaaaaaaaaaaaaa.", "................"]
OUTLINE = (21, 17, 27, 255)
# Icons: 8 x 6, symmetric where the shape allows, at (4, 4): the middle of the face (x 3-12, y 3-10).
ICONS = {
    "close": [".#....#.", "..#..#..", "...##...", "...##...", "..#..#..", ".#....#."],
    "add": ["...##...", "...##...", ".######.", ".######.", "...##...", "...##..."],
    "remove": ["........", "........", ".######.", ".######.", "........", "........"],
    "info": ["...##...", "........", "...##...", "...##...", "...##...", "..####.."],
    "left": ["...#....", "..##....", ".######.", ".######.", "..##....", "...#...."],
    "right": ["....#...", "....##..", ".######.", ".######.", "....##..", "....#..."],
    "up": ["...##...", "..####..", ".######.", "...##...", "...##...", "...##..."],
    "down": ["...##...", "...##...", "...##...", ".######.", "..####..", "...##..."],
    "check": ["......#.", ".....##.", ".#..##..", ".####...", "..##....", "........"],
    "back": ["......#.", "......#.", "..#...#.", ".######.", "..#.....", "........"],
    "lock": ["..####..", ".#....#.", ".######.", ".##..##.", ".##..##.", ".######."],
    "settings": ["...##...", ".######.", ".##..##.", ".##..##.", ".######.", "...##..."],
    "search": [".###....", "#...#...", "#...#...", ".####...", "....##..", ".....##."],
    "filter": [".######.", "..####..", "...##...", "...##...", "...##...", "...##..."],
}
ICON_X, ICON_Y = 4, 4
BUTTONS = ["back", "close", "info", "add", "remove", "left", "right", "check", "lock", "settings", "search",
           "filter", "up", "down"]


def button_png(icon, face, disabled=False):
    """A button of the former theme, recoloured: ``face`` is its main colour; disabled = grey, faint icon."""
    if disabled:
        face = rgb("#8B8B8B")
    roles = {"a": OUTLINE, "b": tone(face, 0.8), "c": face, "d": tone(face, 0.38), "e": tone(face, -0.4)}
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = im.load()
    for y, row in enumerate(TEMPLATE):
        for x, r in enumerate(row):
            if r in roles:
                px[x, y] = roles[r]
    dark = tone(face, -0.15) if disabled else tone(face, -0.6)
    light = face if disabled else tone(face, 0.22)
    rows = ICONS[icon]
    for y, row in enumerate(rows):
        for x, c in enumerate(row):
            if c == "#" and (y + 1 >= len(rows) or rows[y + 1][x] != "#"):
                px[ICON_X + x, ICON_Y + y + 1] = light
    for y, row in enumerate(rows):
        for x, c in enumerate(row):
            if c == "#":
                px[ICON_X + x, ICON_Y + y] = dark
    return im


# World icons of the worlds menu (provisional, 2026-10-30): a small planet per world, greyed with a lock when the
# player has no access (fluxui:monde_<id>, fluxui:monde_<id>_lock).
WORLDS = {"utopia": ("#5DADE2", "#F4F1E8"), "valdor": ("#58B347", "#2E7D32"), "atlas": ("#8D6E4A", "#6B8E3A"),
          "nether": ("#B83A2E", "#F0A04B"), "end": ("#E8E3A6", "#7A48C0"), "stratum": ("#D9A21B", "#5B3C11")}


def world_png(base, accent, locked=False):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    b, a = rgb(base), rgb(accent)
    if locked:
        grey = lambda c: (sum(c[:3]) // 3,) * 3 + (255,)
        b, a = grey(b), grey(a)
    d.ellipse((1, 1, 14, 14), fill=OUTLINE)
    d.ellipse((2, 2, 13, 13), fill=b)
    d.ellipse((3, 8, 9, 12), fill=a)                 # a continent / feature
    d.ellipse((8, 4, 12, 7), fill=a)
    d.point((5, 4), fill=tone(b, 0.6))               # light
    d.point((4, 5), fill=tone(b, 0.6))
    d.arc((2, 2, 13, 13), 20, 160, fill=tone(b, -0.35))
    if locked:
        for x, y in ((9, 10), (10, 10), (11, 10), (12, 10), (9, 11), (12, 11), (9, 12), (10, 12), (11, 12), (12, 12),
                     (9, 13), (10, 13), (11, 13), (12, 13), (10, 8), (11, 8), (9, 9), (12, 9)):
            d.point((x, y), fill=OUTLINE)
        for x, y in ((10, 11), (11, 11)):
            d.point((x, y), fill=rgb("#D9A21B"))
    return im


# ---------------------------------------------------------------------------------------------------- backgrounds

INK = rgb("#404040")


def label(im, txt, x, y, centre=False, dark_ground=False):
    """Static menu text in the menu font: engraved dark grey without shadow on the light window (as vanilla
    titles), white with its 1 px shadow on dark grounds (fields, bars)."""
    colour, shadow = (WHITE, TEXT_SHADOW) if dark_ground else (INK, None)
    ui.write(im, txt, x if centre else 0, y, colour, shadow, left=None if centre else x)


def bar_frame(d, x0, y0, x1):
    """Empty progress bar: black outline, dark inside, white line under (fill drawn by Flux at x0 + 1, y0 + 1)."""
    d.rectangle((x0, y0, x1, y0 + BAR_H + 1), fill=BLACK)
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y0 + BAR_H), fill=rgb("#2B2B30"))
    d.line((x0 + 1, y0 + BAR_H + 2, x1 - 1, y0 + BAR_H + 2), fill=WHITE)


def bg_metiers(generic):
    """Job menu (FluxTools metiers.yml): title "Métier", job name field (Flux text), pets, portrait frame, 12 jobs,
    level bar (row 5), paid actions in the inventory, pager on the hotbar (drawn by Flux only when needed)."""
    im = ui.window(generic)
    d = ImageDraw.Draw(im, "RGBA")
    label(im, "Métier", 88, 5, centre=True)
    ui.field(im, d, (26, 19, 128, 32))     # as high as the buttons beside it (14 px)
    pets = [9, 18, 27, 36]
    grid = [15, 16, 17, 24, 25, 26, 33, 34, 35, 42, 43, 44]
    ui.container(im, d, span(pets), "ardoise")
    for c in pets:
        ui.slot(d, c, "dark")
    d.rectangle((33, 38, 98, 103), fill=rgb("#373737"))     # 1 px around the 64 x 64 illustration at (34, 39)
    d.line((34, 104, 99, 104), fill=WHITE)
    d.line((99, 39, 99, 104), fill=WHITE)
    ui.container(im, d, span(grid), "ardoise")
    for c in grid:
        ui.slot(d, c, "dark")
    bar_frame(d, 6, 109, 169)
    label(im, "Actions rémunérées", 8, 130)
    for i in range(27):
        ui.slot(d, 54 + i)
    return im


def span(cases, pad=1):
    xs = [ui.objet(c)[0] for c in cases]
    ys = [ui.objet(c)[1] for c in cases]
    return min(xs) - 1 - pad, min(ys) - 1 - pad, max(xs) + 16 + pad, max(ys) + 16 + pad


ROW_TOPS = [18 + 18 * r for r in range(6)] + [139, 157, 175, 197]   # item y of chest rows 0-5, inventory, hotbar


def bg_window(generic, first=0, last=9):
    """The bare window of the theme, fitted to the rows a menu uses (theme of Flux): it starts ``first`` rows lower
    than a full window (the plate moves with it) and ends 6 px under row ``last`` (0-5 chest, 6-8 inventory,
    9 hotbar), so a menu of few rows is no taller than it needs and stays centred on the screen."""
    full = ui.window(generic)
    if first == 0 and last == 9:
        return full
    dy = 18 * first
    bottom = ROW_TOPS[last] + 16 + 6          # last row of pixels of the window
    im = Image.new("RGBA", full.size, (0, 0, 0, 0))
    body = bottom + 1 - dy - 3                # rows of the full window kept above its 3 bottom rows
    im.paste(full.crop((0, 0, full.width, body)), (0, dy))
    im.paste(full.crop((0, full.height - 3, full.width, full.height)), (0, bottom - 2))
    return im


BACKGROUNDS = {"metiers": bg_metiers, "window": bg_window}
for _first in range(0, 6):
    for _last in range(_first, 10):
        if (_first, _last) != (0, 9):
            BACKGROUNDS[f"window_{_first}_{_last}"] = (lambda f, l: lambda generic: bg_window(generic, f, l))(_first, _last)


# ---------------------------------------------------------------------------------------------------- main

def main(plugins, resources=None):
    dest = os.path.join(plugins, "ItemsAdder", "contents", NS)
    # Pictures in the pack's textures/ (ItemsAdder copies it to assets/fluxui/textures/). Font files in a raw pack
    # of their own, contents/fluxui_fonts/resourcepack/assets/fluxui/font/: ItemsAdder copies a resourcepack/ folder
    # as it is only in a contents folder without config.yml (in contents/fluxui it would land under
    # assets/fluxui/resourcepack/, where the client never looks).
    import shutil
    rp = os.path.join(plugins, "ItemsAdder", "contents", NS + "_fonts", "resourcepack", "assets", NS)
    os.makedirs(os.path.join(rp, "font"), exist_ok=True)
    for sub in ("textures/btn", "textures/bg", "textures/font"):
        os.makedirs(os.path.join(dest, sub), exist_ok=True)
    stale = os.path.join(dest, "resourcepack")
    if os.path.isdir(stale):
        shutil.rmtree(stale)
    tex = os.path.join(dest, "textures", "font")

    sheet, widths, grid = glyph_sheet()
    sheet.save(os.path.join(tex, "text.png"))
    for ci, (cname, colour) in enumerate(BAR_COLOURS.items()):
        for k, w in enumerate(CHUNKS):
            chunk(colour, w).save(os.path.join(tex, f"bar_{cname}_{w}.png"))
    sprite_pager().save(os.path.join(tex, "pager.png"))
    sprite_lit().save(os.path.join(tex, "lit.png"))
    sprite_slot("vanilla").save(os.path.join(tex, "slot.png"))
    sprite_slot("dark").save(os.path.join(tex, "slot_dark.png"))
    glyph_sheet(shadow=False)[0].save(os.path.join(tex, "text_ink.png"))
    pager_buttons = [("left", True), ("left", False), ("right", True), ("right", False)]
    for direction, on in pager_buttons:
        sprite_pager_button(direction, on).save(os.path.join(tex, f"pager_{direction}_{'on' if on else 'off'}.png"))

    for y in range(0, 221):
        # \uE1F0: 1 px back (slots side by side: a sprite advances by its width + 1)
        providers = [{"type": "space", "advances": {" ": 4, "\uE1F0": -1}}]
        if y >= 7:   # text: cell top = letter top - 2, ascent <= height
            providers.append({"type": "bitmap", "file": f"{NS}:font/text.png", "ascent": 13 - (y - 2),
                              "height": CELL_H, "chars": grid})
        if 13 - y <= BAR_H:
            for ci, cname in enumerate(BAR_COLOURS):
                for k, w in enumerate(CHUNKS):
                    providers.append({"type": "bitmap", "file": f"{NS}:font/bar_{cname}_{w}.png", "ascent": 13 - y,
                                      "height": BAR_H, "chars": [chr(CHUNK_BASE + 16 * ci + k)]})
        if 13 - y <= PAGER_H:
            providers.append({"type": "bitmap", "file": f"{NS}:font/pager.png", "ascent": 13 - y, "height": PAGER_H,
                              "chars": [chr(SPRITE_BASE)]})
        if 13 - y <= PAGER_H:
            for k, (direction, on) in enumerate(pager_buttons):
                providers.append({"type": "bitmap", "file": f"{NS}:font/pager_{direction}_{'on' if on else 'off'}.png",
                                  "ascent": 13 - y, "height": PAGER_H, "chars": [chr(SPRITE_BASE + 2 + k)]})
        if 13 - y <= 20:
            providers.append({"type": "bitmap", "file": f"{NS}:font/lit.png", "ascent": 13 - y, "height": 20,
                              "chars": [chr(SPRITE_BASE + 1)]})
        if 13 - y <= 18:
            for k, name in enumerate(("slot", "slot_dark")):
                providers.append({"type": "bitmap", "file": f"{NS}:font/{name}.png", "ascent": 13 - y, "height": 18,
                                  "chars": [chr(SPRITE_BASE + 6 + k)]})
        with open(os.path.join(rp, "font", f"y{y}.json"), "w", encoding="utf-8") as f:
            json.dump({"providers": providers}, f, ensure_ascii=False, separators=(",", ":"))
        # n<Y>: the menu font without shadow, engraved dark grey on the light window (Flux tints it)
        ink = [{"type": "space", "advances": {" ": 4}}]
        if y >= 7:
            ink.append({"type": "bitmap", "file": f"{NS}:font/text_ink.png", "ascent": 13 - (y - 2), "height": CELL_H,
                        "chars": grid})
        with open(os.path.join(rp, "font", f"n{y}.json"), "w", encoding="utf-8") as f:
            json.dump({"providers": ink}, f, ensure_ascii=False, separators=(",", ":"))

    lines = ["# Generated by plugins/fluxplugin/Flux/assets/vanilla/gen_ui.py: do not edit by hand.",
             "info:", f"  namespace: {NS}", "", "items:"]
    for icon in BUTTONS:
        variants = [(c, BUTTON_COLOURS[c], False) for c in BUTTON_COLOURS] + [("off", None, True)]
        for cname, colour, off in variants:
            iid = f"btn_{icon}_{cname}"
            button_png(icon, colour, off).save(os.path.join(dest, "textures", "btn", iid + ".png"))
            lines += [f"  {iid}:", "    display_name: ' '", "    resource:", "      material: PAPER",
                      "      generate: true", "      textures:", f"      - btn/{iid}.png"]
    os.makedirs(os.path.join(dest, "textures", "monde"), exist_ok=True)
    for wid, (base, accent) in WORLDS.items():
        for locked in (False, True):
            iid = f"monde_{wid}" + ("_lock" if locked else "")
            world_png(base, accent, locked).save(os.path.join(dest, "textures", "monde", iid + ".png"))
            lines += [f"  {iid}:", "    display_name: ' '", "    resource:", "      material: PAPER",
                      "      generate: true", "      textures:", f"      - monde/{iid}.png"]
    lines += ["", "font_images:"]
    generic = os.path.join(HERE, "menu_vanilla.png")
    for name, fn in BACKGROUNDS.items():
        fn(generic).save(os.path.join(dest, "textures", "bg", name + ".png"))
        lines += [f"  bg_{name}:", "    show_in_gui: false", "    suggest_in_command: false",
                  f"    path: bg/{name}.png", "    scale_ratio: 221", "    y_position: 13"]
    open(os.path.join(dest, "config.yml"), "w", encoding="utf-8").write("\n".join(lines) + "\n")

    if resources:
        os.makedirs(os.path.join(resources, "ui"), exist_ok=True)
        out = ["# Generated by Flux/assets/vanilla/gen_ui.py: width in px of each character of the menu font.",
               "chars:"]
        out += [f"  {json.dumps(c, ensure_ascii=False)}: {w}" for c, w in widths.items()]
        out += ["chunks: [" + ", ".join(map(str, CHUNKS)) + "]",
                "bar-colours: [" + ", ".join(BAR_COLOURS) + "]",
                f"bar-height: {BAR_H}", f"pager-width: {PAGER_W}", f"pager-button-width: {PAGER_BTN_W}"]
        open(os.path.join(resources, "ui", "glyphs.yml"), "w", encoding="utf-8").write("\n".join(out) + "\n")
    print(f"{NS}: {len(BUTTONS) * (len(BUTTON_COLOURS) + 1)} buttons, 221 fonts, {len(BACKGROUNDS)} backgrounds -> {dest}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
