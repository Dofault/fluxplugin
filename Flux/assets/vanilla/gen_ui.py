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
CHARS = (list("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") + list("ÉÈÊÀÂÎÔ") + list("/%.:+-!'·?,()_") + ["\uE120", "\uE121"])
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
                    "(": [".#", "#.", "#.", "#.", ".#"], ")": ["#.", ".#", ".#", ".#", "#."],
                    "_": ["...", "...", "...", "...", "###"]})


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


PATH_BASE = 0xE140             # path pieces: + 8 * piece index + state index (Flux PathRenderer, same order)
PATH_PIECES = ["chemin_h", "chemin_v", "chemin_h_demi", "chemin_v_demi", "chemin_noeud"]
PATH_STATES = ["eteint", "vert", "or", "bleu", "rose", "rouge", "orange"]
PATH_WIDTHS = [19, 3, 10, 3, 5]  # pictures in chemin/, opaque up to their last column (advance = width + 1)
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


# Rank not discovered yet: one badge for all of them, whatever the rank and the cycle (no colour, no wings to give it away).
RANK_HIDDEN_MARK = [".####.", "##..##", "##..##", "...##.", "..##..", "..##..", "......", "..##..", "..##.."]


def rank_hidden_badge():
    im = rank_badge(rgb("#6A6673"), 1, 0, True)
    body = rgb("#6A6673")
    for y in range(4, 14):
        for x in range(3, 13):
            im.putpixel((x, y), body)
    for y, r in enumerate(RANK_HIDDEN_MARK):
        for x, ch in enumerate(r):
            if ch == "#" and (y == 8 or r[x] and RANK_HIDDEN_MARK[y + 1][x] != "#"):
                im.putpixel((5 + x, 5 + y), tone(body, -0.5))
    for y, r in enumerate(RANK_HIDDEN_MARK):
        for x, ch in enumerate(r):
            if ch == "#":
                im.putpixel((5 + x, 4 + y), WHITE)
    return im


# Rank badges of the rank route (FluxOffering RankMenu, "winged badge", chosen on 2026-11-05 out of 5 mock-ups): a badge
# in the rank's colour with the rank's symbol and no number, 16 wide with the axis between columns 7 and 8 so it is centred
# in a slot and strictly symmetrical. Prestige shows in the silhouette, not only in the colour (silver on the grey of
# Inconnu, gold on the yellow of Légende): small silver wings, then big golden wings and a crown. "off" = all greyed.
# Items fluxui:rang_<n>_<cycle>_<on|off> (n from 1, cycle 0-2).
RANK_SYMBOLS = [  # 9 wide, symmetrical; the centre column is doubled to fit the even axis. Order of the ranks.
    ["...###...", "..#####..", "..#####..", "...###...", "....#....", "..#####..", ".#######.", ".#######.", "........."],  # anonymous
    ["....#....", "...###...", "..#####..", ".#######.", "#########", ".#######.", ".###.###.", ".###.###.", "........."],  # house
    ["....#....", "...###...", "..#####..", "..#####..", "..#####..", ".#######.", "#########", ".........", "....#...."],  # bell
    [".##...##.", "####.####", "#########", "#########", ".#######.", "..#####..", "...###...", "....#....", "........."],  # heart
    ["....#....", "..#####..", "#########", ".........", ".#.#.#.#.", ".#.#.#.#.", ".#.#.#.#.", ".........", "#########"],  # temple
    [".#######.", "#########", "##.###.##", "##.###.##", "#########", "###...###", ".##...##.", "..#####..", "...###..."],  # mask
    [".#.....#.", "#..###..#", "#.#.#.#.#", "#..###..#", ".#..#..#.", "....#....", "...###...", "..#####..", "........."],  # antenna
    ["....#....", "....#....", "...###...", "#########", ".#######.", "..#####..", "..#####..", ".###.###.", ".#.....#."],  # star
    ["#...#...#", ".#.....#.", "...###...", "..#####..", "#.#####.#", "..#####..", "...###...", ".#.....#.", "#...#...#"],  # sun
    ["....#....", "...###...", "...###...", "..#####..", ".###.###.", ".##...##.", ".##.#.##.", "..#####..", "...###..."],  # flame
    ["....#....", "...###...", "...###...", "#..###..#", "##.###.##", ".#######.", "...###...", ".#######.", "...###..."],  # fleur-de-lis
    ["....#....", "#...#...#", "#..###..#", "##.###.##", "#########", "#########", ".........", "#########", ".#######."],  # crown
]
BADGE_INK = rgb("#17121F")
BADGE_SILVER, BADGE_SILVER_DARK = rgb("#E4EAF2"), rgb("#8E99A8")
BADGE_GOLD, BADGE_GOLD_DARK = rgb("#FFD94D"), rgb("#C98A00")


def rank_badge(colour, n, cycle, on):
    def mix(c, t):
        return tuple(max(0, min(255, round(v + (255 - v) * t if t >= 0 else v * (1 + t)))) for v in c[:3]) + (255,)

    cells = {}

    def put(x, y, c):
        if 0 <= y < 16 and 0 <= x < 16:
            cells[(x, y)] = c
            cells[(15 - x, y)] = c

    def row(y, a, b, c):
        for x in range(a, b + 1):
            put(x, y, c)

    # body
    row(2, 3, 13, BADGE_INK)
    for y in range(3, 14):
        row(y, 2, 13, BADGE_INK)
    row(14, 3, 12, BADGE_INK)
    row(15, 5, 10, BADGE_INK)
    for y in range(3, 13):
        row(y, 3, 12, colour)
    row(13, 4, 11, colour)
    row(14, 6, 9, colour)
    row(3, 4, 11, mix(colour, 0.45))
    row(14, 6, 9, mix(colour, -0.3))
    # prestige: wings on columns 0-1, a crown above from the second one
    if cycle == 1:
        for x, y in ((0, 6), (0, 7), (0, 8), (1, 7), (1, 8), (1, 9), (1, 6)):
            put(x, y, BADGE_SILVER)
        put(1, 10, BADGE_SILVER_DARK)
    elif cycle == 2:
        for x, y in ((0, 2), (0, 3), (0, 4), (0, 5), (0, 6), (0, 7), (0, 8), (1, 3), (1, 4), (1, 5), (1, 6), (1, 7), (1, 8), (1, 9), (1, 10)):
            put(x, y, BADGE_GOLD)
        put(0, 9, BADGE_GOLD_DARK)
        put(1, 11, BADGE_GOLD_DARK)
        row(1, 3, 4, BADGE_GOLD)
        row(1, 7, 7, BADGE_GOLD)
        row(0, 7, 7, BADGE_GOLD)
    # symbol: dark on a light rank, white with a shadow on a dark one
    a = colour
    light = (0.2126 * a[0] + 0.7152 * a[1] + 0.0722 * a[2]) / 255 > 0.6
    ink, shadow = (mix(colour, -0.72), None) if light else (WHITE, mix(colour, -0.5))
    glyph = [r[:5] + r[4:] for r in RANK_SYMBOLS[(n - 1) % len(RANK_SYMBOLS)]]
    for y, r in enumerate(glyph):
        for x, ch in enumerate(r):
            if ch == "#" and shadow and y < 8 and glyph[y + 1][x] != "#":
                put(3 + x, 5 + y, shadow)
    for y, r in enumerate(glyph):
        for x, ch in enumerate(r):
            if ch == "#":
                put(3 + x, 4 + y, ink)
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for (x, y), c in cells.items():
        if not on:
            g = round(sum(c[:3]) / 3)
            c = (g, g, g, 255)
        im.putpixel((x, y), c)
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
    """Player card of /profil (2026-10-30): back (18) and menu icon (26), the shown player's name between them (text
    widget of the menu), above a light identity card. No header band since 2026-11-10 (the menu already says it is
    the profile): dark photo frame of 3 x 3 slots (columns 0-2, rows 3-5) behind the bust (36-38, 46), the rank tag
    centred over the sections on row 3 (FluxTags, centre x 112, y 74), the 8 sections in slots (40-43, 49-52), a
    signature under the photo and a gold chip."""
    im = bg_window(generic, 2, 5, extra=22)
    d = ImageDraw.Draw(im, "RGBA")
    ui.container(im, d, (5, 71, 170, 141), "carte")
    d.rectangle((6, 72, 61, 126), fill=BLACK)                     # photo, behind the bust (slots 27-29 to 45-47)
    d.rectangle((7, 72, 60, 125), fill=rgb("#4A4B50"))
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


# Main menu (/menu2, Flux menus/main.yml, 2026-11-10): profile row (9, 17, name and balance written by Flux), then the
# main features in one box of 2 rows x 5 (20-24: ranks, jobs, quests, collections, tags; 29-33: market, worlds,
# towns, pets, casino), the shortcut row on chest row 5 (45-53), drawn by the menu's own buttons.
MAIN_GRID = [20, 21, 22, 23, 24, 29, 30, 31, 32, 33]


def bg_main(generic):
    im = bg_window(generic, 1, 5)
    d = ImageDraw.Draw(im, "RGBA")
    ui.container(im, d, span(MAIN_GRID), "ardoise")
    for c in MAIN_GRID:
        ui.slot(d, c, "dark")
    for c in (9, 17):                     # profile head and account button
        ui.slot(d, c, "vanilla")
    return im


# Villes et Pays (FluxLands territoires.yml, redone 2026-11-08, buttons 3 x 3 since 2026-11-10): back (18) and menu
# icon (26) on the first drawn row, then two big buttons of 3 x 3 slots side by side, symmetric (Ma ville columns 1-3,
# Mon pays columns 5-7, rows 3-5): only their icon is drawn here (button of the theme three times its size), invisible
# items over them for the click. No card
# and no text (owner's choice: only the title is written in a menu). Explore buttons on the first inventory row,
# under the chest / inventory gap.
TERRITORY_BUTTONS = {"ville": 28, "pays": 32}   # top-left slot of each 2 x 2 button, as CARTE_* of gen_menus_ville.py
PLUGINS = None                 # plugins dir of the server, set by main (pictures of other packs)


def big_icon(ns, iid, scale=2):
    """A former-theme picture restyled as a button of the theme, ``scale`` times its size, or None."""
    path = os.path.join(PLUGINS or "", "ItemsAdder", "contents", ns, "textures", "icones", iid + ".png")
    if not os.path.isfile(path):
        return None
    found = former_button(Image.open(path).convert("RGBA"))
    if not found:
        return None
    face, glyph = found
    return restyled_png(nearest_colour(face), glyph).resize((16 * scale, 16 * scale), Image.NEAREST)


def bg_territoires(generic):
    im = bg_window(generic, 2, 6)
    for name, case in TERRITORY_BUTTONS.items():
        icon = big_icon("influville", name, 3)
        if icon:
            x, y = ui.objet(case)            # 3 x 3 slots = 52 px square from the top-left item, icon of 48 centred
            im.alpha_composite(icon, (x + 2, y + 2))
    return im


# Trade window of FluxCommerce (menu echange, 2026-11-01): on the first row the quit button (0), the two name fields (1-3,
# 5-7, names written by Flux) and the help button (8); under it one dark box per player (rows 1-5, the middle column
# free between them): his 12 offered stacks (rows 1-3), then his experience (row 4) and money (row 5), icon in a slot
# and amount in a field (written by Flux). Countdown (40) and validate button (49) stand in the middle column, between
# the two boxes; the player's inventory under them. A name field turns green when that player validated: overlays
# bg_echange_pret_moi / bg_echange_pret_lui, as large as the window, shown by the menu.
TRADE_MINE = [9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30]
TRADE_THEIRS = [14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35]
TRADE_ICONS = [36, 45, 44, 53]
TRADE_NAMES = {"moi": (25, 19, 78, 32), "lui": (97, 19, 150, 32)}
TRADE_FIELDS = [(26, 91, 77, 104), (26, 109, 77, 122), (98, 91, 149, 104), (98, 109, 149, 122)]
TRADE_GREEN = rgb("#3E8E2E")


def bg_echange(generic):
    im = ui.window(generic)
    d = ImageDraw.Draw(im, "RGBA")
    for box in TRADE_NAMES.values():
        ui.field(im, d, box)
    for grid, icons in ((TRADE_MINE, (36, 45)), (TRADE_THEIRS, (44, 53))):
        x0, y0, x1, _ = span(grid)
        ui.container(im, d, (x0, y0, x1, ui.objet(45)[1] + 17), "ardoise")
        for c in grid + list(icons):
            ui.slot(d, c, "dark")
    for box in TRADE_FIELDS:
        ui.field(im, d, box)
    # arrows between the offers, engraved in the middle column (x 80-95): mine to him above, his to me below
    for y, way in ((52, 1), (66, -1)):
        tip, tail = (93, 82) if way > 0 else (82, 93)
        d.line((82, y, 93, y), fill=INK)
        d.line((tip - 2 * way, y - 2, tip - 2 * way, y + 2), fill=INK)
        d.line((tip - way, y - 1, tip - way, y + 1), fill=INK)
    label(im, "Inventaire", 8, 130)
    for i in range(36):
        ui.slot(d, 54 + i)
    return im


def echange_ready(side):
    """A name field of the trade window in green (the player validated), alone on a window-sized picture."""
    im = Image.new("RGBA", (ui.W, ui.H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im, "RGBA")
    x0, y0, x1, y1 = TRADE_NAMES[side]
    d.rectangle((x0, y0, x1, y1), fill=BLACK)
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), fill=TRADE_GREEN)
    d.line((x0 + 1, y0 + 1, x1 - 1, y0 + 1), fill=tone(TRADE_GREEN, -0.4))
    d.line((x0 + 1, y1 - 1, x1 - 1, y1 - 1), fill=tone(TRADE_GREEN, 0.25))
    # a glyph advances up to its last non-transparent column: without this the picture of the left field advanced
    # ~80 px instead of the window's 176 and every widget drawn after it slid left. Alpha 8 is discarded by the
    # text shader (< 0.1), so the pixel never shows but still counts in the width.
    im.putpixel((ui.W - 1, y0), (0, 0, 0, 8))
    return im


def bg_window(generic, first=0, last=9, extra=0, rows=6):
    """The bare window of the theme, fitted to the rows a menu uses (theme of Flux): it starts ``first`` rows lower
    than a full window (the plate moves with it) and ends 6 px under row ``last`` (0-5 chest, 6-8 inventory,
    9 hotbar), so a menu of few rows is no taller than it needs and stays centred on the screen. ``rows``: chest rows
    of the menu, the inventory of a smaller chest is higher in game (18 px a row)."""
    full = ui.window(generic)
    if first == 0 and last == 9 and not extra and rows == 6:
        return full
    dy = 18 * first
    lift = 18 * (6 - rows) if last >= 6 else 0
    # last row of pixels of the window; extra: more grey under the last row (a box drawn down there keeps a margin)
    bottom = min(full.height - 1, ROW_TOPS[last] - lift + 16 + 6 + extra)
    im = Image.new("RGBA", full.size, (0, 0, 0, 0))
    body = bottom + 1 - dy - 3                # rows of the full window kept above its 3 bottom rows
    im.paste(full.crop((0, 0, full.width, body)), (0, dy))
    im.paste(full.crop((0, full.height - 3, full.width, full.height)), (0, bottom - 2))
    return im


BACKGROUNDS = {"metiers": bg_metiers, "main": bg_main, "window": bg_window, "pass": bg_pass, "profil": bg_profil,
               "territoires": bg_territoires, "echange": bg_echange,
               "echange_pret_moi": lambda generic: echange_ready("moi"),
               "echange_pret_lui": lambda generic: echange_ready("lui")}
for _first in range(0, 6):
    for _last in range(_first, 10):
        if (_first, _last) != (0, 9):
            BACKGROUNDS[f"window_{_first}_{_last}"] = (lambda f, l: lambda generic: bg_window(generic, f, l))(_first, _last)


# ---------------------------------------------------------------------------------------------------- main

SHARED_BASE = 0xE180


def registered_sprites():
    """[(id, picture)] of sprites.yml, in file order (their character is SHARED_BASE + index)."""
    import yaml
    root = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))  # plugins/fluxplugin
    entries = yaml.safe_load(open(os.path.join(HERE, "sprites.yml"), encoding="utf-8")) or {}
    out = []
    for sid, rel in entries.items():
        im = Image.open(os.path.join(root, rel)).convert("RGBA")
        if im.height > 20:
            raise SystemExit(f"sprites.yml: {sid} is {im.height} px high (20 at most)")
        out.append((sid, im))
    return out


def visible_width(im):
    """Width the client gives the glyph: up to its last column holding a pixel (alpha > 0)."""
    box = im.getchannel("A").getbbox()
    return box[2] if box else 0


def main(plugins, resources=None):
    global PLUGINS
    PLUGINS = plugins
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
    glyph_sheet(shadow=False)[0].save(os.path.join(tex, "text_ink.png"))
    pager_buttons = [("left", True), ("left", False), ("right", True), ("right", False)]
    # Sprites packed in two sheets, one bitmap provider each: the client reads and decodes the picture of every
    # provider of every font again (no cache between providers), and each of the 221 y fonts used to carry ~57 of
    # them. A glyph advances up to its last non-transparent column and its top sits at the ascent, so a sprite
    # padded with transparency on the right and at the bottom of a larger cell draws exactly as on its own.
    bars = [[(chr(CHUNK_BASE + 16 * ci + k), chunk(colour, w)) for k, w in enumerate(CHUNKS)]
            for ci, colour in enumerate(BAR_COLOURS.values())]
    bars.append([(chr(FRAME_BASE + k), bar_frame_chunk(w)) for k, w in enumerate(CHUNKS)]
                + [(chr(FRAME_BASE + 8), bar_frame_chunk(1, edge=True))])
    misc = [[(chr(SPRITE_BASE), sprite_pager()), (chr(SPRITE_BASE + 1), sprite_lit())]
            + [(chr(SPRITE_BASE + 2 + k), sprite_pager_button(d, on)) for k, (d, on) in enumerate(pager_buttons)]
            + [(chr(SPRITE_BASE + 6), sprite_slot("vanilla")), (chr(SPRITE_BASE + 7), sprite_slot("dark"))]]
    # progression path pieces (PathRenderer): one row per piece, one column per state
    misc += [[(chr(PATH_BASE + 8 * pi + si), Image.open(os.path.join(HERE, "chemin", f"{piece}_{state}.png")).convert("RGBA"))
              for si, state in enumerate(PATH_STATES)] for pi, piece in enumerate(PATH_PIECES)]
    # shared sprites of the other plugins (sprites.yml): drawn at any height, rows of 8 after the path pieces
    shared = registered_sprites()
    for k in range(0, len(shared), 8):
        misc.append([(chr(SHARED_BASE + j), im) for j, (_, im) in enumerate(shared[k:k + 8], start=k)])
    sheets = []
    for name, rows in (("sprites_bar", bars), ("sprites_misc", misc)):
        cw = max(im.width for row in rows for _, im in row)
        ch = max(im.height for row in rows for _, im in row)
        cols = max(len(row) for row in rows)
        im_sheet = Image.new("RGBA", (cw * cols, ch * len(rows)), (0, 0, 0, 0))
        chars = []
        for r, row in enumerate(rows):
            for c, (_, im) in enumerate(row):
                im_sheet.paste(im, (c * cw, r * ch))
            chars.append("".join(cp for cp, _ in row).ljust(cols, "\u0000"))
        im_sheet.save(os.path.join(tex, name + ".png"))
        sheets.append((name, ch, chars))
    for old in os.listdir(tex):     # single sprites of the former layout
        if old.startswith(("bar_", "barframe_", "pager", "lit", "slot")):
            os.remove(os.path.join(tex, old))

    for y in range(0, 221):
        # \uE1F0: 1 px back (slots side by side: a sprite advances by its width + 1)
        # \uE1F1 + k: back over path piece k (its width + 1), so a piece leaves the cursor where it found it
        providers = [{"type": "space", "advances": {" ": 4, "\uE1F0": -1,
                                                    **{chr(0xE1F1 + k): -(w + 1) for k, w in enumerate(PATH_WIDTHS)}}}]
        if True:     # text: cell top = letter top - 2 (ascent 15 - y <= 16)
            providers.append({"type": "bitmap", "file": f"{NS}:font/text.png", "ascent": 13 - (y - 2),
                              "height": CELL_H, "chars": grid})
        for name, ch, chars in sheets:
            if 13 - y <= ch:
                providers.append({"type": "bitmap", "file": f"{NS}:font/{name}.png", "ascent": 13 - y,
                                  "height": ch, "chars": chars})
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
        rank_hidden_badge().save(os.path.join(dest, "textures", "rang", "rang_hidden.png"))
        lines += ["  rang_hidden:", "    display_name: ' '", "    resource:", "      material: PAPER",
                  "      generate: true", "      textures:", "      - rang/rang_hidden.png"]
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
        sprites = ["# Generated by Flux/assets/vanilla/gen_ui.py from sprites.yml: id: [character, width in px]."]
        sprites += [f"{sid}: [{SHARED_BASE + k}, {visible_width(im)}]" for k, (sid, im) in enumerate(registered_sprites())]
        open(os.path.join(resources, "ui", "sprites.yml"), "w", encoding="utf-8").write("\n".join(sprites) + "\n")
    print(f"{NS}: {len(BUTTONS) * (len(BUTTON_COLOURS) + 1)} buttons, 221 fonts, {len(BACKGROUNDS)} backgrounds -> {dest}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
