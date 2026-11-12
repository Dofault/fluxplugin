"""Spell icons of the rpg hud (2026-11-12): the three skills of the player's class drawn beside the hotbar, the two
key skills (A, F) on its left, the weapon's skill (right click) on its right, each with a small key cap above it.
Imported by gen_hud.py (same font, fluxdungeons:hud); run alone it writes a preview:

    python3 hud_spells.py preview <out.png>

Pixel art on a 16 x 16 grid, one shared palette of short muted ramps (no pure black outline, no gradient: every
shade is a step of a ramp). An icon sits in a 22 x 22 slot, the size of a hotbar slot, so its pixels are as big as
the item icons of the hotbar next to it (a 32 x 32 icon shrunk into a slot would mix two pixel sizes). Each slot
exists twice: ready, and dimmed (not enough mana for it).

Glyphs: U+E400 + index = slot (SPELLS order, then BLANK), U+E400 + DIM + index = the same dimmed, U+E420 key cap A,
U+E421 key cap F, U+E422 right click, U+E423 left click. FluxDungeons (rpg/RpgHud.java) writes the same numbers: keep SPELLS, SLOT and
BADGE_W in line with it.
"""
import math
import sys

from PIL import Image

# keyed by the skill "type" of classes.yml: an icon follows its spell whatever key a class gives it
SPELLS = ["charge", "war_cry", "whirlwind",          # warrior
          "leap_back", "volley", "arrow_rain",       # archer
          "blink", "fireball", "frost_nova",         # mage
          "heal", "blessing", "sanctuary"]           # healer
BLANK = len(SPELLS)          # a slot with no icon (a type with no picture yet)
DIM = BLANK + 1              # offset of the dimmed slots
SLOT = 22
BADGE_W, BADGE_H = 9, 9

PALETTE = {
    # steel
    "1": "#2e2b3d", "2": "#575470", "3": "#8f8ca8", "4": "#cfcde0",
    # wood, bone
    "w": "#4a2d25", "W": "#7a4e33", "b": "#a8763f", "x": "#ece2cc",
    # warrior red
    "r": "#5e1f2b", "R": "#9a3340", "e": "#c96259",
    # fire
    "f": "#7a2e1e", "F": "#c2552c", "o": "#e58a3c", "y": "#f5cd6a",
    # ice
    "i": "#26476e", "I": "#3d7bab", "c": "#74b6dc", "C": "#c6e9f5",
    # archer green
    "g": "#2b4a33", "G": "#427a48", "l": "#73aa5c", "L": "#b6d68c",
    # gold
    "d": "#6b4a17", "D": "#a87826", "u": "#d9aa3f", "U": "#f2da8a",
    # arcane violet
    "v": "#3d2270", "V": "#6c43b0", "p": "#a077dd", "P": "#d6bbf2",
    # healing mint
    "m": "#2a6650", "M": "#4a9c78", "n": "#86cfa8", "N": "#d3f0de",
}

# frame accent per class (the inner bevel of the slot): the class reads at a glance, the frames stay one family
CLASS_ACCENT = {"warrior": "#9a3340", "archer": "#427a48", "mage": "#6c43b0", "healer": "#a87826"}
SPELL_CLASS = {s: c for c, ss in zip(CLASS_ACCENT, [SPELLS[i:i + 3] for i in range(0, 12, 3)]) for s in ss}


def grid():
    return [["."] * 16 for _ in range(16)]


def put(g, x, y, c):
    if 0 <= x < 16 and 0 <= y < 16:
        g[y][x] = c


def rows(*lines):
    assert len(lines) == 16, len(lines)
    for i, l in enumerate(lines):
        assert len(l) == 16, (i, l, len(l))
    return [list(l) for l in lines]


# ---------------------------------------------------------------- warrior

def charge():
    # a shield driven forward, speed lines behind it
    return rows(
        "................",
        "................",
        ".....3444444443.",
        ".....4eRRRRRRr2.",
        ".23..4eRRuuRRr2.",
        ".....4eRRuuRRr2.",
        "2233.4euuuuuur2.",
        ".....4eRRuuRRr2.",
        ".23..3eRRuuRRr2.",
        "......4eRuuRr2..",
        "......3eRuuRr2..",
        ".......3eRRr2...",
        "........3rr2....",
        ".........22.....",
        "................",
        "................")


def war_cry():
    # a war horn, its call spreading from the bell
    return rows(
        "...........e....",
        "..........e..e..",
        "......wwww..e..e",
        ".....wxxxbw..e.e",
        "....wxbwwwbw.e.e",
        "....xbwwwwbW..e.",
        "....xbbwwbbW.e..",
        ".....xbbbbW.....",
        ".....xbbbW......",
        "....xbdbW.......",
        "...xbdDW........",
        "..xbbW..........",
        "..xbW...........",
        ".xbW............",
        ".wW.............",
        "................")


def whirlwind():
    # a sword spun around: two turning arrows around the blade
    return rows(
        "................",
        ".......4........",
        ".......43..ee...",
        ".......43....e..",
        ".......43.....e.",
        ".......43.....e.",
        ".......43.....e.",
        ".......43....eee",
        ".e.....43.....e.",
        "eee....43.......",
        ".e...uuuuuu.....",
        ".e.....WW.......",
        ".e.....WW.......",
        "..e....uu.......",
        "...ee...........",
        "................")


# ---------------------------------------------------------------- archer

def leap_back():
    # a jump arching backwards
    return rows(
        "................",
        "................",
        ".....LLLLLL.....",
        "....LllllllL....",
        "...Ll......lL...",
        "...lG.......lG..",
        "...lG.......lG..",
        ".LllllG.....lG..",
        "..LllG......lG..",
        "...lG.......lG..",
        "............lG..",
        "............lG..",
        "............lG..",
        "................",
        "................",
        "................")


def arrow_diag(g, hx, hy, length):
    """An arrow flying up and right, head at (hx, hy)."""
    for t in range(1, length):
        put(g, hx - t, hy + t, "W" if t % 2 else "b")
    # head: a V of steel
    put(g, hx, hy, "4")
    put(g, hx - 1, hy, "3")
    put(g, hx, hy + 1, "3")
    put(g, hx - 2, hy, "2")
    put(g, hx, hy + 2, "2")
    # feathers
    tx, ty = hx - length, hy + length
    put(g, tx + 1, ty, "l")
    put(g, tx, ty - 1, "L")
    put(g, tx, ty + 1, "G")
    put(g, tx - 1, ty, "G")


def volley():
    g = grid()
    arrow_diag(g, 8, 1, 7)
    arrow_diag(g, 13, 3, 8)
    arrow_diag(g, 14, 9, 6)
    return g


def arrow_down(g, x, top, tip):
    for y in range(top + 1, tip - 1):
        put(g, x, y, "W" if y % 2 else "b")
    put(g, x - 1, top, "L")
    put(g, x + 1, top, "l")
    put(g, x - 1, top + 1, "G")
    put(g, x + 1, top + 1, "G")
    put(g, x, top, "G")
    put(g, x - 1, tip - 1, "3")
    put(g, x, tip - 1, "4")
    put(g, x + 1, tip - 1, "2")
    put(g, x, tip, "3")


def arrow_rain():
    # arrows falling, staggered
    g = grid()
    arrow_down(g, 2, 1, 8)
    arrow_down(g, 6, 5, 13)
    arrow_down(g, 10, 0, 7)
    arrow_down(g, 13, 6, 14)
    return g


# ---------------------------------------------------------------- mage

STAR = ["....v....",
        "....p....",
        "...vPv...",
        "..vPxPv..",
        "vpPxxxPpv",
        "..vPxPv..",
        "...vPv...",
        "....p....",
        "....v...."]
SPARK = ["..v..",
         ".vpv.",
         "vpPpv",
         ".vpv.",
         "..v.."]
SPARK_DIM = ["..v..",
             ".vVv.",
             "vVpVv",
             ".vVv.",
             "..v.."]


def stamp(g, art, cx, cy):
    h, w = len(art), len(art[0])
    for dy, row in enumerate(art):
        for dx, c in enumerate(row):
            if c != ".":
                put(g, cx - w // 2 + dx, cy - h // 2 + dy, c)


def blink():
    # a star landing where the mage reappears, fading copies where he was
    g = grid()
    stamp(g, SPARK_DIM, 2, 13)
    stamp(g, SPARK, 5, 10)
    stamp(g, STAR, 10, 5)
    return g


def fireball():
    # a ball of fire falling down and left, its flames trailing behind
    g = grid()
    cx, cy, tx, ty = 5.0, 10.5, 14.5, 1.0
    dx, dy = tx - cx, ty - cy
    n = math.hypot(dx, dy)
    for y in range(16):
        for x in range(16):
            px, py = x + 0.5 - cx, y + 0.5 - cy
            t = (px * dx + py * dy) / (n * n)
            if t < 0:
                d, r = math.hypot(px, py), 4.2
            else:
                t = min(t, 1)
                d = math.hypot(px - t * dx, py - t * dy)
                r = 4.2 * (1 - t) ** 1.3
            if d > r or r < 0.45:
                continue
            k = d / r
            put(g, x, y, "y" if k < 0.38 and t < 0.35 else "o" if k < 0.62 else "F" if k < 0.86 else "f")
    return g


def frost_nova():
    # an eight armed ice crystal
    g = grid()
    c = 7
    for i in range(-6, 7):
        put(g, c + i, c, "c")
        put(g, c, c + i, "c")
    for i in range(-4, 5):
        put(g, c + i, c + i, "I")
        put(g, c + i, c - i, "I")
    for sx, sy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        put(g, c + 6 * sx, c + 6 * sy, "C")
        # branches near each tip
        bx, by = c + 4 * sx, c + 4 * sy
        put(g, bx + sy + sx, by + sx + sy, "c")
        put(g, bx - sy + sx, by - sx + sy, "c")
    for sx, sy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
        put(g, c + 4 * sx, c + 4 * sy, "c")
    for x, y in ((c - 1, c), (c + 1, c), (c, c - 1), (c, c + 1)):
        put(g, x, y, "C")
    put(g, c, c, "N")
    put(g, c - 1, c - 1, "c")
    put(g, c + 1, c + 1, "i")
    put(g, c + 1, c - 1, "c")
    put(g, c - 1, c + 1, "i")
    return g


# ---------------------------------------------------------------- healer

def heal():
    # a cross of healing, a spark of light on its corner
    g = grid()
    inside = lambda x, y: (6 <= x <= 9 and 2 <= y <= 13) or (2 <= x <= 13 and 6 <= y <= 9)
    for y in range(16):
        for x in range(16):
            if not inside(x, y):
                continue
            top, left = not inside(x, y - 1), not inside(x - 1, y)
            bottom, right = not inside(x, y + 1), not inside(x + 1, y)
            put(g, x, y, "m" if bottom or right else "N" if top or left else "n")
    for x, y in ((7, 3), (7, 4), (3, 7), (4, 7)):
        put(g, x, y, "N")
    stamp(g, ["..U..", ".UxU.", "..U.."], 13, 2)
    return g


def blessing():
    # a halo over rising chevrons: strength given to the allies
    return rows(
        "................",
        ".....DUUUUD.....",
        "...Du......uD...",
        "....dDu..uDd....",
        "......dDDd......",
        "................",
        ".......UU.......",
        "......UuuU......",
        ".....Uu..uU.....",
        "....Du....uD....",
        "...Dd......dD...",
        ".......UU.......",
        "......UuuU......",
        ".....Uu..uU.....",
        "....Du....uD....",
        "...Dd......dD...")


def sanctuary():
    # a column of light rising from a ring on the ground, small crosses of healing around it
    return rows(
        "................",
        ".......nn.......",
        "..n....Nx....n..",
        ".nNn...Nx...nNn.",
        "..n...nNxn...n..",
        "......nNxn......",
        "......nNxn......",
        ".....MnNxnM.....",
        ".....MnNxnM.....",
        ".....MnNxnM.....",
        "...MMMnNxnMMM...",
        "..Mn.MnNxnM.nM..",
        ".Mn.........NnM.",
        ".mM.........Mm..",
        "..mmMMMMMMMMmm..",
        "................")


ART = {"charge": charge, "war_cry": war_cry, "whirlwind": whirlwind, "leap_back": leap_back, "volley": volley,
       "arrow_rain": arrow_rain, "blink": blink, "fireball": fireball, "frost_nova": frost_nova, "heal": heal,
       "blessing": blessing, "sanctuary": sanctuary}


# ---------------------------------------------------------------- rendering

def rgba(h, a=255):
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a


OUTLINE = (13, 13, 13, 220)
FILL = rgba("#1e0a3c", 205)
SHADE = rgba("#140828", 205)


def dim(c):
    """Not enough mana: grey, darker, a touch of the violet fill kept."""
    r, g, b, a = c
    lum = 0.3 * r + 0.59 * g + 0.11 * b
    v = int(lum * 0.5)
    return v + 6, v + 2, v + 14, a


def slot(spell, dimmed=False):
    im = Image.new("RGBA", (SLOT, SLOT), (0, 0, 0, 0))
    px = im.load()
    accent = rgba(CLASS_ACCENT.get(SPELL_CLASS.get(spell), "#5b2d9e"))
    dark = tuple(int(v * 0.55) for v in accent[:3]) + (255,)
    for y in range(SLOT):
        for x in range(SLOT):
            if x in (0, SLOT - 1) or y in (0, SLOT - 1):
                c = OUTLINE
            elif (x == 1 or y == 1) and x < SLOT - 2 and y < SLOT - 2:
                c = accent
            elif x in (1, SLOT - 2) or y in (1, SLOT - 2):
                c = dark
            else:
                # the lower right half one step darker: the slot is lit from the top left like the icons
                c = SHADE if x + y > SLOT + 4 else FILL
            px[x, y] = dim(c) if dimmed and c not in (OUTLINE,) else c
    if spell in ART:
        g = ART[spell]()
        for y in range(16):
            for x in range(16):
                ch = g[y][x]
                if ch != ".":
                    c = rgba(PALETTE[ch])
                    px[3 + x, 3 + y] = dim(c) if dimmed else c
    return im


# a mouse, its right button lit (6 rows: one more than a letter, drawn from the cap's lit row down)
MOUSE = [".WWA.",
         "W.WAA",
         "WWWWW",
         "W...W",
         "W...W",
         ".WWW."]
# the same, left button lit (the archer's weapon skill, basic attack on the right click)
MOUSE_LEFT = [".AWW.",
              "AAW.W",
              "WWWWW",
              "W...W",
              "W...W",
              ".WWW."]


def badge(slot_id):
    from_font = __import__("json").load(open(QUINQUE))
    im = Image.new("RGBA", (BADGE_W, BADGE_H), (0, 0, 0, 0))
    px = im.load()
    for y in range(BADGE_H):
        for x in range(BADGE_W):
            if x in (0, BADGE_W - 1) or y in (0, BADGE_H - 1):
                px[x, y] = (13, 13, 13, 255)
            else:
                px[x, y] = rgba("#5b2d9e") if y == 1 else rgba("#2a1650")
    art = {"right": MOUSE, "left": MOUSE_LEFT}.get(slot_id) or from_font[slot_id.upper()]
    top = 2 if len(art) == 5 else 1
    if top == 1:
        for x in range(1, BADGE_W - 1):
            px[x, 1] = rgba("#2a1650")
    for dy, row in enumerate(art):
        for dx, ch in enumerate(row):
            if ch in "#W":
                px[2 + dx, top + dy] = rgba("#f2eef7")
            elif ch == "A":
                px[2 + dx, top + dy] = rgba("#ffd94d")
    return im


import os
QUINQUE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "Flux", "assets", "metiers",
                       "quinquefive.json")
BADGES = ["a", "f", "right", "left"]


def slots_sheet():
    """One row: every spell, the blank slot, then the same dimmed."""
    order = SPELLS + [None]
    im = Image.new("RGBA", (SLOT * len(order) * 2, SLOT), (0, 0, 0, 0))
    for i, s in enumerate(order):
        im.paste(slot(s), (i * SLOT, 0))
        im.paste(slot(s, True), ((len(order) + i) * SLOT, 0))
    return im, len(order) * 2


def badges_sheet():
    im = Image.new("RGBA", (BADGE_W * len(BADGES), BADGE_H), (0, 0, 0, 0))
    for i, b in enumerate(BADGES):
        im.paste(badge(b), (i * BADGE_W, 0))
    return im


def preview(out):
    """Every class's slots placed around a drawn hotbar, scale 4, plus the dimmed ones."""
    scale, w = 4, 320
    classes = [SPELLS[i:i + 3] for i in range(0, 12, 3)]
    im = Image.new("RGBA", (w, 46 * len(classes) + 30), (92, 120, 150, 255))
    for k, (right, a, f) in enumerate(classes):
        base = 46 * k + 40
        cx = w // 2
        hb = cx - 91
        # hotbar stand-in
        for x in range(182):
            for y in range(22):
                edge = x in (0, 181) or y in (0, 21) or x % 20 in (0, 1)
                im.putpixel((hb + x, base - 22 + y), (40, 40, 40, 255) if edge else (110, 110, 110, 255))
        lefts = [(hb - 4 - 22 - 3 - 22, a, "a"), (hb - 4 - 22, f, "f"), (hb + 182 + 4, right, "right")]
        for x0, s, key in lefts:
            sl = slot(s, dimmed=(k == 3 and key == "f"))
            im.alpha_composite(sl, (x0, base - 22))
            im.alpha_composite(badge(key), (x0 + 7, base - 29))
    im = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
    im.save(out)
    # all icons alone, bigger
    sheet = Image.new("RGBA", (SLOT * 12 + 11 * 4, SLOT * 2 + 4), (60, 60, 70, 255))
    for i, s in enumerate(SPELLS):
        sheet.alpha_composite(slot(s), (i * (SLOT + 4), 0))
        sheet.alpha_composite(slot(s, True), (i * (SLOT + 4), SLOT + 4))
    sheet.resize((sheet.width * 6, sheet.height * 6), Image.NEAREST).save(out.replace(".png", "_icons.png"))


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "preview":
        preview(sys.argv[2])
    else:
        print(__doc__)
