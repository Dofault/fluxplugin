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
# font sprites: U+E100 pager field, U+E101 selected slot (20 x 20), U+E102-E105 pager buttons (left on/off, right on/off)
PAGER_BTN_W = 36               # pager button: 2 slots wide
BUTTON_COLOURS = {"red": rgb("#C0392B"), "green": rgb("#3E8E2E"), "blue": rgb("#3D7FB8"), "grey": rgb("#6E6E6E"),
                  "gold": rgb("#D9A21B")}


# ---------------------------------------------------------------------------------------------------- font

fond = ui.fond_lib
fond._EXTRA.update({"?": ["###", "..#", ".##", "...", ".#."], ",": [".", ".", ".", "#", "#"],
                    "(": [".#", "#.", "#.", "#.", ".#"], ")": ["#.", ".#", ".#", ".#", "#."]})


def glyph_sheet():
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
                if ch == "#":
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

# Icons of the buttons: 8 x 8, symmetric where the shape allows, drawn at (4, 3) so they sit in the middle of the
# button face (x 2-13, y 2-11); white with a 1 px shadow under each column, as the menu font.
ICONS = {
    "close": ["##....##", "###..###", ".######.", "..####..", "..####..", ".######.", "###..###", "##....##"],
    "add": ["...##...", "...##...", "...##...", "########", "########", "...##...", "...##...", "...##..."],
    "remove": ["........", "........", "........", "########", "########", "........", "........", "........"],
    "info": ["...##...", "...##...", "........", "..###...", "...##...", "...##...", "...##...", "..####.."],
    "left": ["...##...", "..##....", ".##.....", "########", "########", ".##.....", "..##....", "...##..."],
    "right": ["...##...", "....##..", ".....##.", "########", "########", ".....##.", "....##..", "...##..."],
    "up": ["...##...", "..####..", ".######.", "##.##.##", "...##...", "...##...", "...##...", "...##..."],
    "down": ["...##...", "...##...", "...##...", "...##...", "##.##.##", ".######.", "..####..", "...##..."],
    "check": ["........", "......##", ".....##.", "##..##..", ".####...", "..##....", "........", "........"],
    "back": ["......##", "......##", "..#...##", ".##...##", "########", "########", ".##.....", "..#....."],
    "lock": ["..####..", ".##..##.", ".#....#.", "########", "###..###", "###..###", "########", "########"],
    "settings": ["...##...", ".######.", ".##..##.", "###..###", "###..###", ".##..##.", ".######.", "...##..."],
    "search": [".####...", "##..##..", "#....#..", "#....#..", "##..##..", ".#####..", ".....##.", "......##"],
    "filter": ["########", ".######.", "..####..", "...##...", "...##...", "...##...", "...##...", "...##..."],
}
ICON_X, ICON_Y = 4, 3
BUTTONS = ["back", "close", "info", "add", "remove", "left", "right", "check", "lock", "settings", "search",
           "filter", "up", "down"]


def draw_icon(d, icon, colour, shadow):
    rows = ICONS[icon]
    for y, row in enumerate(rows):
        for x, c in enumerate(row):
            if c == "#" and (y + 1 >= len(rows) or rows[y + 1][x] != "#"):
                d.point((ICON_X + x, ICON_Y + y + 1), fill=shadow)
    for y, row in enumerate(rows):
        for x, c in enumerate(row):
            if c == "#":
                d.point((ICON_X + x, ICON_Y + y), fill=colour)


def button_png(icon, colour, disabled=False):
    """Button A in a 16 x 16 item picture: 15 high + the white line under it."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    base = rgb("#8B8B8B") if disabled else colour
    # the whole 16 x 16: the white line under a button is drawn by the background, only where no black edge
    # follows (button_line), so a button standing on a container shares its edge
    ui.button(d, (0, 0, 15, 15), base, None)
    draw_icon(d, icon, rgb("#C6C6C6") if disabled else WHITE, tone(base, -0.55))
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
    ui.field(im, d, (26, 18, 128, 33))
    pets = [9, 18, 27, 36]
    grid = [15, 16, 17, 24, 25, 26, 33, 34, 35, 42, 43, 44]
    # containers start 1 px higher than their slots need: their top edge is the bottom edge of the buttons and of
    # the name field above (one 1 px line, not two)
    ui.container(im, d, on_row0(span(pets)), "ardoise")
    for c in pets:
        ui.slot(d, c, "dark")
    d.rectangle((33, 38, 98, 103), fill=rgb("#373737"))     # 1 px around the 64 x 64 illustration at (34, 39)
    d.line((34, 104, 99, 104), fill=WHITE)
    d.line((99, 39, 99, 104), fill=WHITE)
    ui.container(im, d, on_row0(span(grid)), "ardoise")
    for c in grid:
        ui.slot(d, c, "dark")
    bar_frame(d, 6, 109, 169)
    label(im, "Actions rémunérées", 8, 130)
    for i in range(27):
        ui.slot(d, 54 + i)
    return im


def on_row0(box):
    """A box right under row 0: its top edge on the bottom edge of the row 0 buttons (y 33)."""
    return box[0], 33, box[2], box[3]


def button_line(d, case):
    """White line under a button of row/slot ``case``, for a button that does not stand on a black edge."""
    x, y = ui.objet(case)
    d.line((x + 1, y + 16, x + 14, y + 16), fill=WHITE)


def span(cases, pad=1):
    xs = [ui.objet(c)[0] for c in cases]
    ys = [ui.objet(c)[1] for c in cases]
    return min(xs) - 1 - pad, min(ys) - 1 - pad, max(xs) + 16 + pad, max(ys) + 16 + pad


BACKGROUNDS = {"metiers": bg_metiers}


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
    pager_buttons = [("left", True), ("left", False), ("right", True), ("right", False)]
    for direction, on in pager_buttons:
        sprite_pager_button(direction, on).save(os.path.join(tex, f"pager_{direction}_{'on' if on else 'off'}.png"))

    for y in range(0, 221):
        providers = [{"type": "space", "advances": {" ": 4}}]
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
        with open(os.path.join(rp, "font", f"y{y}.json"), "w", encoding="utf-8") as f:
            json.dump({"providers": providers}, f, ensure_ascii=False, separators=(",", ":"))

    lines = ["# Generated by plugins/fluxplugin/Flux/assets/vanilla/gen_ui.py: do not edit by hand.",
             "info:", f"  namespace: {NS}", "", "items:"]
    for icon in BUTTONS:
        variants = [(c, BUTTON_COLOURS[c], False) for c in BUTTON_COLOURS] + [("off", None, True)]
        for cname, colour, off in variants:
            iid = f"btn_{icon}_{cname}"
            button_png(icon, colour, off).save(os.path.join(dest, "textures", "btn", iid + ".png"))
            lines += [f"  {iid}:", "    display_name: ' '", "    resource:", "      material: PAPER",
                      "      generate: true", "      textures:", f"      - btn/{iid}.png"]
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
