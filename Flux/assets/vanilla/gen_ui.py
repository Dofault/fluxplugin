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
import math
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
# \uE120 / \uE121: closed / open lock (pass milestones), written in a text like any letter
CHARS = (list("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") + list("ÉÈÊÀÂÎÔ") + list("/%.:+-!'·?,()") + ["\uE120", "\uE121"])
CELL_W, CELL_H = 8, 16         # accent rows 0-1, letter rows 2-6, shadow row 7, empty below: a glyph may stand
                               # up to 16 px above the baseline (ascent <= height), so text fits any row, plate too
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
fond._EXTRA.update({"\uE120": ["..#..", ".#.#.", "#####", "##.##", "#####"],
                    "\uE121": ["..#..", ".#.#.", ".#...", "#####", "#####"],
                    "?": ["###", "..#", ".##", "...", ".#."], ",": [".", ".", ".", "#", "#"],
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


FRAME_BASE = 0xE110            # bar frames: + k for the body chunks of CHUNKS widths, + 8 the 1 px edge


def bar_frame_chunk(w, edge=False):
    """Piece of an empty bar frame drawn by Flux (widget bar with frame): black outline, dark inside, white line
    under it; the edge is the black column at both ends."""
    h = BAR_H + 3
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if edge:
        d.line((0, 0, 0, BAR_H + 1), fill=BLACK)
        return im
    d.line((0, 0, w - 1, 0), fill=BLACK)
    d.rectangle((0, 1, w - 1, BAR_H), fill=rgb("#2B2B30"))
    d.line((0, BAR_H + 1, w - 1, BAR_H + 1), fill=BLACK)
    d.line((0, BAR_H + 2, w - 1, BAR_H + 2), fill=WHITE)
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


def tall_button_png(icon, face, rows=2):
    """A button of the theme ``rows`` slots high (pass page arrows over both tracks): same edges and engraving,
    the pictogram in the middle."""
    h = 18 * rows - 2
    im = Image.new("RGBA", (16, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle((1, 1, 14, h - 2), fill=OUTLINE)
    d.rectangle((2, 2, 13, h - 3), fill=face)
    d.line((2, 2, 13, 2), fill=tone(face, 0.8))
    d.line((2, 3, 2, h - 6), fill=tone(face, 0.38)); d.line((13, 3, 13, h - 6), fill=tone(face, 0.38))
    d.line((2, h - 5, 13, h - 5), fill=tone(face, -0.4))
    d.line((2, h - 4, 13, h - 4), fill=tone(face, 0.38))
    d.line((2, h - 3, 13, h - 3), fill=tone(face, -0.4))
    rows_px = ICONS[icon]
    top = (h - 6) // 2 - 3
    for y, row in enumerate(rows_px):
        for x, c in enumerate(row):
            if c == "#" and (y + 1 >= len(rows_px) or rows_px[y + 1][x] != "#"):
                im.putpixel((ICON_X + x, top + y + 1), tone(face, 0.22))
    for y, row in enumerate(rows_px):
        for x, c in enumerate(row):
            if c == "#":
                im.putpixel((ICON_X + x, top + y), tone(face, -0.6))
    return im


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


# Former-theme icons restyled (2026-10-30): every picture of influitems / influville / influmarche drawn on the
# former button template (rounded 14 x 14, engraved pictogram) becomes a button of the theme: same pictogram,
# square corners, the nearest bright colour (the former violet goes blue). Items fluxui:re_<ns>_<id>, and
# plugins/Flux/theme-buttons.yml (former material -> new one), applied by Flux to themed menus.
RESTYLE = ("influitems", "influville", "influmarche")
FORMER_OUTLINE = (21, 17, 27)


def former_button(im):
    """Face colour and pictogram pixels of a picture on the former button template, or None."""
    px = im.load()
    corners = [px[2, 1], px[13, 1], px[1, 2], px[14, 2], px[1, 13], px[2, 14], px[13, 14]]
    if any(c[3] < 255 or c[:3] != FORMER_OUTLINE for c in corners) or px[1, 1][3] != 0:
        return None
    face = px[3, 3]
    lum = lambda c: 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2]
    glyph = [(x, y) for y in range(3, 11) for x in range(3, 13)
             if px[x, y][3] == 255 and lum(px[x, y]) < lum(face) * 0.82]
    return face, glyph


def nearest_colour(face):
    import colorsys
    r, g, b = (c / 255 for c in face[:3])
    h, l, sat = colorsys.rgb_to_hls(r, g, b)
    if sat < 0.18:
        return "grey"
    hue = h * 360
    if hue >= 250 and hue < 300:      # the former violet
        return "blue"
    for name, lo, hi in (("red", 0, 18), ("orange", 18, 38), ("gold", 38, 70), ("green", 70, 170),
                         ("blue", 170, 250), ("red", 300, 361)):
        if lo <= hue < hi:
            return name
    return "grey"


def restyled_png(face_name, glyph):
    face = BUTTON_COLOURS[face_name]
    roles = {"a": OUTLINE, "b": tone(face, 0.8), "c": face, "d": tone(face, 0.38), "e": tone(face, -0.4)}
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = im.load()
    for y, row in enumerate(TEMPLATE):
        for x, r in enumerate(row):
            if r in roles:
                px[x, y] = roles[r]
    cells = set(glyph)
    for x, y in glyph:
        if (x, y + 1) not in cells and y + 1 <= 10:
            px[x, y + 1] = tone(face, 0.22)
    for x, y in glyph:
        px[x, y] = tone(face, -0.6)
    return im


# Rank badges of the rank route (FluxOffering RankMenu, 2026-10-30): a shield in the rank's colour (ranks.yml) with its
# number engraved, gold stars on top for the prestige cycle; "off" greyed for the steps not reached.
# Items fluxui:rang_<n>_<cycle>_<on|off> (n from 1, cycle 0-2).
def rank_badge(colour, n, cycle, on):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c = colour if on else (150, 150, 150, 255)
    if not on:
        g = sum(colour[:3]) // 3
        c = (g, g, g, 255)
    shield = [(2, 3), (13, 3), (13, 9), (8, 14), (7, 14), (2, 9)]
    d.polygon([(1, 2), (14, 2), (14, 9), (8, 15), (7, 15), (1, 9)], fill=OUTLINE)
    d.polygon(shield, fill=c)
    d.line((3, 4, 12, 4), fill=tone(c, 0.5))
    d.line((3, 4, 3, 8), fill=tone(c, 0.35))
    digits = str(n)
    w = sum(len(fond._glyph(ch)[0][0]) + 1 for ch in digits) - 1
    x = 8 - w // 2
    for ch in digits:
        g, _ = fond._glyph(ch)
        for dy, row in enumerate(g):
            for dx, v in enumerate(row):
                if v == "#":
                    if 6 + dy + 1 <= 13:
                        im.putpixel((x + dx, 6 + dy + 1), tone(c, -0.6))
                    im.putpixel((x + dx, 6 + dy), WHITE if on else (225, 225, 225, 255))
        x += len(g[0]) + 1
    star = rgb("#FFD94D") if on else (200, 200, 200, 255)
    for k in range(cycle):
        sx = 8 - 2 * cycle + 4 * k + 1
        for px_, py_ in ((sx, 0), (sx - 1, 1), (sx, 1), (sx + 1, 1), (sx, 2)):
            im.putpixel((px_, py_), star)
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


PASS_GREEN, PASS_GREEN_DARK = rgb("#3E8E2E"), rgb("#2A6420")
PASS_FREE = list(range(37, 44))          # free rewards of the 7 tiers of a page
PASS_PREMIUM = list(range(46, 53))       # premium rewards, right under them
PASS_TABS, PASS_MISSIONS = list(range(65, 70)), list(range(73, 80))   # inventory rows 1-2: grey space above


def bg_pass(generic):
    """Pass menu (Flux pass.yml, validated mockup of 2026-10-30): season card (rows 1-2: big icon fluxui:pass_carte,
    name, time left and bar drawn by Flux), free rewards (row 4, frames by the theme, numbers and locks by Flux
    above them), premium rewards in a green box (row 5), then the missions in a dark box as wide as the premium one
    one row lower with grey space above: header, 5 categories, 7 missions (inventory rows 1-2)."""
    im = bg_window(generic, 0, 8, extra=5)   # grey margin under the missions box
    d = ImageDraw.Draw(im, "RGBA")
    ui.container(im, d, (6, 35, 169, 70), "ardoise")
    px0, py0, px1, py1 = span(PASS_PREMIUM)
    # premium track: black edge on top and bottom only (no left / right edge)
    d.rectangle((px0, py0, px1, py1), fill=PASS_GREEN_DARK)
    d.line((px0, py0, px1, py0), fill=BLACK)
    d.line((px0, py1, px1, py1), fill=BLACK)
    d.line((px0, py0 + 1, px1, py0 + 1), fill=tone(PASS_GREEN_DARK, -0.3))
    for c in PASS_PREMIUM:
        ui.slot(d, c, "colour", PASS_GREEN)
    for c in PASS_FREE:
        ui.slot(d, c, "vanilla")
    # missions one row lower than the tracks, grey space between: header strip right above the categories
    _, ty0, _, _ = span(PASS_TABS, 1)
    _, _, _, my1 = span(PASS_MISSIONS, 3)
    ui.container(im, d, (px0, ty0 - 13, px1, my1), "ardoise", head="Missions", head_kind="strip", head_h=11)
    for c in PASS_TABS + PASS_MISSIONS:
        ui.slot(d, c, "dark")
    return im


def bg_profil(generic):
    """Player card of /profil (2026-10-30): back (18) and menu icon (26) above a light identity card: dark header band
    with « Carte d'identité » on the left and the rank tag on the right (FluxTags, window x 110, y 74), dark photo
    frame behind the bust (36-38, 46), the 8 sections in slots (40-43, 49-52), a signature under the photo and a
    gold chip."""
    im = bg_window(generic, 2, 5, extra=22)
    d = ImageDraw.Draw(im, "RGBA")
    ui.container(im, d, (5, 71, 170, 141), "carte")
    d.rectangle((6, 72, 169, 85), fill=rgb("#35363A"))
    d.line((6, 86, 169, 86), fill=BLACK)
    ui.write(im, "Carte d'identité", 0, 76, WHITE, TEXT_SHADOW, left=10)
    d.rectangle((6, 87, 60, 126), fill=BLACK)                     # photo, behind the bust
    d.rectangle((7, 88, 59, 125), fill=rgb("#4A4B50"))
    for c in (40, 41, 42, 43, 49, 50, 51, 52):
        ui.slot(d, c, "vanilla")
    sig = [(10 + i, 133 + 2 * math.sin(i / 2.2) * (1 - i / 50) + (1 if 18 < i < 26 else 0)) for i in range(0, 44)]
    d.line(sig, fill=rgb("#3D3D6B"), width=1)
    d.line((8, 137, 58, 137), fill=rgb("#9A9A9A"))
    x, y = 150, 127                                               # gold chip
    d.rounded_rectangle((x, y, x + 13, y + 10), radius=2, fill=rgb("#F0A800"), outline=rgb("#8A5A14"))
    for yy in (y + 3, y + 7):
        d.line((x + 1, yy, x + 12, yy), fill=rgb("#C88C14"))
    d.line((x + 6, y + 1, x + 6, y + 9), fill=rgb("#C88C14"))
    return im


def bg_window(generic, first=0, last=9, extra=0):
    """The bare window of the theme, fitted to the rows a menu uses (theme of Flux): it starts ``first`` rows lower
    than a full window (the plate moves with it) and ends 6 px under row ``last`` (0-5 chest, 6-8 inventory,
    9 hotbar), so a menu of few rows is no taller than it needs and stays centred on the screen."""
    full = ui.window(generic)
    if first == 0 and last == 9 and not extra:
        return full
    dy = 18 * first
    # last row of pixels of the window; extra: more grey under the last row (a box drawn down there keeps a margin)
    bottom = min(full.height - 1, ROW_TOPS[last] + 16 + 6 + extra)
    im = Image.new("RGBA", full.size, (0, 0, 0, 0))
    body = bottom + 1 - dy - 3                # rows of the full window kept above its 3 bottom rows
    im.paste(full.crop((0, 0, full.width, body)), (0, dy))
    im.paste(full.crop((0, full.height - 3, full.width, full.height)), (0, bottom - 2))
    return im


BACKGROUNDS = {"metiers": bg_metiers, "window": bg_window, "pass": bg_pass, "profil": bg_profil}
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
    for w in CHUNKS:
        bar_frame_chunk(w).save(os.path.join(tex, f"barframe_{w}.png"))
    bar_frame_chunk(1, edge=True).save(os.path.join(tex, "barframe_edge.png"))
    sprite_slot("vanilla").save(os.path.join(tex, "slot.png"))
    sprite_slot("dark").save(os.path.join(tex, "slot_dark.png"))
    glyph_sheet(shadow=False)[0].save(os.path.join(tex, "text_ink.png"))
    pager_buttons = [("left", True), ("left", False), ("right", True), ("right", False)]
    for direction, on in pager_buttons:
        sprite_pager_button(direction, on).save(os.path.join(tex, f"pager_{direction}_{'on' if on else 'off'}.png"))

    for y in range(0, 221):
        # \uE1F0: 1 px back (slots side by side: a sprite advances by its width + 1)
        providers = [{"type": "space", "advances": {" ": 4, "\uE1F0": -1}}]
        if True:     # text: cell top = letter top - 2 (ascent 15 - y <= 16)
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
        if 13 - y <= BAR_H + 3:
            for k, w in enumerate(CHUNKS):
                providers.append({"type": "bitmap", "file": f"{NS}:font/barframe_{w}.png", "ascent": 13 - y,
                                  "height": BAR_H + 3, "chars": [chr(FRAME_BASE + k)]})
            providers.append({"type": "bitmap", "file": f"{NS}:font/barframe_edge.png", "ascent": 13 - y,
                              "height": BAR_H + 3, "chars": [chr(FRAME_BASE + 8)]})
        if 13 - y <= 18:
            for k, name in enumerate(("slot", "slot_dark")):
                providers.append({"type": "bitmap", "file": f"{NS}:font/{name}.png", "ascent": 13 - y, "height": 18,
                                  "chars": [chr(SPRITE_BASE + 6 + k)]})
        with open(os.path.join(rp, "font", f"y{y}.json"), "w", encoding="utf-8") as f:
            json.dump({"providers": providers}, f, ensure_ascii=False, separators=(",", ":"))
        # n<Y>: the menu font without shadow, engraved dark grey on the light window (Flux tints it)
        ink = [{"type": "space", "advances": {" ": 4}}]
        if True:
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
    contents = os.path.join(plugins, "ItemsAdder", "contents")
    os.makedirs(os.path.join(dest, "textures", "re"), exist_ok=True)
    mapping = {}
    for ns in RESTYLE:
        tdir = os.path.join(contents, ns, "textures")
        cfg = os.path.join(contents, ns, "config.yml")
        if not os.path.isdir(tdir) or not os.path.isfile(cfg):
            continue
        import yaml
        items = (yaml.safe_load(open(cfg, encoding="utf-8")) or {}).get("items") or {}
        for iid, spec in items.items():
            tex = ((spec or {}).get("resource") or {}).get("textures") or []
            if not tex:
                continue
            path = os.path.join(tdir, tex[0] if tex[0].endswith(".png") else tex[0] + ".png")
            if not os.path.isfile(path):
                continue
            pic = Image.open(path).convert("RGBA")
            if pic.size != (16, 16):
                continue
            found = former_button(pic)
            if not found:
                continue
            face, glyph = found
            new_id = f"re_{ns}_{iid}"
            restyled_png(nearest_colour(face), glyph).save(os.path.join(dest, "textures", "re", new_id + ".png"))
            lines += [f"  {new_id}:", "    display_name: ' '", "    resource:", "      material: PAPER",
                      "      generate: true", "      textures:", f"      - re/{new_id}.png"]
            mapping[f"itemsadder:{ns}:{iid}"] = f"itemsadder:{NS}:{new_id}"
    with open(os.path.join(plugins, "Flux", "theme-buttons.yml"), "w", encoding="utf-8") as f:
        f.write("# Generated by Flux/assets/vanilla/gen_ui.py: former-theme pictures and their restyled button\n"
                "# (applied to themed menus; theme.buttons of config.yml wins over this file).\n")
        for k, v in sorted(mapping.items()):
            f.write(f'"{k}": "{v}"\n')
    print(len(mapping), "former icons restyled")
    ranks_yml = os.path.join(plugins, "FluxOffering", "ranks.yml")
    if os.path.isfile(ranks_yml):
        import yaml
        rc = yaml.safe_load(open(ranks_yml, encoding="utf-8")) or {}
        order = [r for t in (rc.get("tiers") or {}).values() for r in (t.get("ranks") or [])]
        os.makedirs(os.path.join(dest, "textures", "rang"), exist_ok=True)
        for n, rid in enumerate(order, 1):
            colour = rgb(str(((rc.get("ranks") or {}).get(rid) or {}).get("colour", "#FFFFFF")))
            for cycle in range(3):
                for on in (True, False):
                    iid = f"rang_{n}_{cycle}_{'on' if on else 'off'}"
                    rank_badge(colour, n, cycle, on).save(os.path.join(dest, "textures", "rang", iid + ".png"))
                    lines += [f"  {iid}:", "    display_name: ' '", "    resource:", "      material: PAPER",
                              "      generate: true", "      textures:", f"      - rang/{iid}.png"]
    lines += ["", "font_images:"]
    generic = os.path.join(HERE, "menu_vanilla.png")
    for name, fn in BACKGROUNDS.items():
        fn(generic).save(os.path.join(dest, "textures", "bg", name + ".png"))
        lines += [f"  bg_{name}:", "    show_in_gui: false", "    suggest_in_command: false",
                  f"    path: bg/{name}.png", "    scale_ratio: 221", "    y_position: 13"]
    # every themed menu without a background of its own: window, slot frames and name in one picture (auto_bg.py)
    import auto_bg
    auto_dir = os.path.join(dest, "textures", "bg", "auto")
    os.makedirs(auto_dir, exist_ok=True)
    for f in os.listdir(auto_dir):
        if f.endswith(".png"):
            os.remove(os.path.join(auto_dir, f))
    autos = auto_bg.build(plugins, bg_window, generic)
    for name, im in autos.items():
        im.save(os.path.join(auto_dir, name + ".png"))
        lines += [f"  bg_{name}:", "    show_in_gui: false", "    suggest_in_command: false",
                  f"    path: bg/auto/{name}.png", "    scale_ratio: 221", "    y_position: 13"]
    # the list Flux reads to know which menus have their picture (ItemsAdder may load them after Flux at startup)
    with open(os.path.join(plugins, "Flux", "theme-pictures.yml"), "w", encoding="utf-8") as f:
        f.write("# Generated by Flux/assets/vanilla/gen_ui.py: pictures of the themed menus (fluxui:bg_<name>).\npictures:\n")
        for name in sorted(autos):
            f.write(f"  - bg_{name}\n")
    print(len(autos), "themed menu backgrounds")
    # pass page arrows over both tracks: real items whose model overflows their slot (item in the lower slot,
    # picture 2 slots high, oversized_in_gui as the profile bust), item model fluxui:<name> in the raw pack
    raw = os.path.dirname(rp)
    for sub in ("items", os.path.join("models", "item"), os.path.join("textures", "item")):
        os.makedirs(os.path.join(rp, sub), exist_ok=True)
    for name, icon in (("pass_prev", "left"), ("pass_next", "right")):
        tall_button_png(icon, BUTTON_COLOURS["orange"]).save(os.path.join(rp, "textures", "item", name + ".png"))
        with open(os.path.join(rp, "items", name + ".json"), "w", encoding="utf-8") as f:
            json.dump({"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}, "oversized_in_gui": True}, f)
        face = {"uv": [0, 0, 16, 16], "texture": "#t"}
        with open(os.path.join(rp, "models", "item", name + ".json"), "w", encoding="utf-8") as f:
            json.dump({"textures": {"t": f"{NS}:item/{name}", "particle": f"{NS}:item/{name}"},
                       "gui_light": "front",
                       "elements": [{"from": [0, 0, 8], "to": [16, 16, 8], "faces": {"south": face, "north": face}}],
                       # 16 x 34 picture: stretched to 34 px high, its bottom on the slot's bottom (centre 8 -> up 9)
                       "display": {"gui": {"rotation": [0, 0, 0], "translation": [0, 9, 0], "scale": [1, 2.125, 1]}}}, f)
    # big icon of the pass season card (32 x 32 at window (10, 37))
    pass_png = os.path.join(contents, "influitems", "textures", "pass.png")
    if os.path.isfile(pass_png):
        Image.open(pass_png).convert("RGBA").resize((32, 32), Image.NEAREST).save(os.path.join(dest, "textures", "bg", "pass_carte.png"))
        lines += ["  pass_carte:", "    show_in_gui: false", "    suggest_in_command: false",
                  "    path: bg/pass_carte.png", "    scale_ratio: 32", "    y_position: %d" % (13 - 37)]
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
