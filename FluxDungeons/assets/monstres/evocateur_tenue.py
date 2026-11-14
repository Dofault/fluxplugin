#!/usr/bin/env python3
"""Outfit of the Oversized Evoker: the ornate enchanter's dress of the first model (2026-11-13), kept at the
owner's request on 2026-11-14 while the head stays the one of the art pass (evocateur_demesure.py).

Deep violet robe, dark indigo coat, royal violet tabard, grey violet sashes, old gold everywhere, painted blue
runes. Signature motif: the same diamond at shrinking sizes (`scale_glyph`), concentric diamonds on the chest and
the back. The cloth carries a few stitches placed by the seeded random of the texture baker: the same on every run.

Only painters live here; the cubes are in evocateur_demesure.py, which takes MATS and GLOW_MATS from this module.
"""
from bbkit import hx, tone


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- palette (dark -> light)
ROBE = pal("#22153A", "#2B1A44", "#34204F", "#40295F")      # deep violet: main robe
ROYAL = pal("#3F2659", "#4A2C68", "#563477", "#69418D")     # royal violet: tabard, mantle, cuffs
INDIGO = pal("#181A33", "#1E203C", "#252747", "#30335A")    # dark indigo: outer coat
BLUE = pal("#3C4FA0", "#536CCB", "#7F93E0")                 # magic blue: painted runes (not emissive)
GOLD = pal("#7E6534", "#B9954F", "#E5C56D", "#F4E3A1")      # shadow, ancient gold, bright gold, glint
GREY = pal("#463D52", "#544A61", "#62566F", "#766A84")      # grey violet secondary cloth: sashes, collar base
BOOT = pal("#15121F", "#1E1A2B", "#2A2439")


# Emissive colours, used by glowing pieces only (the preview recognises them by colour).
GLOW = pal("#5E7BE8", "#8FA6FF", "#D6E0FF")
GLOW_VIOLET = hx("#A678F0")
GLOW_LID = hx("#1B1524")

LIGHT = {"up": 1, "north": 0, "east": 0, "west": 0, "south": -1, "down": -1}   # light from above, a little from the front
SIDES = ("north", "south", "east", "west")


# --------------------------------------------------------------------------- rune stamps
def put(px, x, y, c):
    if 0 <= y < len(px) and 0 <= x < len(px[0]):
        px[y][x] = c


def diamond(px, cx, cy, rad, colour):
    """Outline of a pixel diamond (|dx| + |dy| = rad); rad 0 is one pixel."""
    for dy in range(-rad, rad + 1):
        dx = rad - abs(dy)
        put(px, cx - dx, cy + dy, colour)
        put(px, cx + dx, cy + dy, colour)


def scale_glyph(px, cx, y0, sizes=(2, 1, 0)):
    """The signature rune: the same diamond three times, each smaller, read from top to bottom."""
    y = y0
    for rad in sizes:
        diamond(px, cx, y + rad, rad, GOLD[2])
        if rad >= 2:
            put(px, cx, y + rad, BLUE[1])
        y += 2 * rad + 2


def concentric(px, cx, cy, rings):
    """Concentric diamonds, (radius, colour) from the outside in: space folding on itself."""
    for rad, colour in rings:
        diamond(px, cx, cy, rad, colour)


def frieze(px, y, colour=GOLD[1]):
    """Row of tiny diamonds above a hem."""
    w = len(px[0])
    for cx in range(2, w - 1, 4):
        diamond(px, cx, y, 1, colour)


# --------------------------------------------------------------------------- painters
def cloth(shades, base=2, hem=False, folds=True, edges=(), rune=None, rune_faces=SIDES):
    """Fabric in flat tones: a few darker stitches, vertical fold lines on the lower part with a lit pixel beside
    them, top edge caught by the light, bottom row in shadow or a two-row gold hem. `edges`: gold border on these
    columns of the north face ("first", "last"). `rune(px, w, h)` paints the motif on `rune_faces`."""
    def f(w, h, r, face):
        b = base + LIGHT[face]
        px = [[tone(shades, b)] * w for _ in range(h)]
        if face in ("up", "down"):
            for _ in range(max(1, w * h // 20)):
                px[r.randrange(h)][r.randrange(w)] = tone(shades, b - 1)
            return px
        for _ in range(max(1, w * h // 28)):
            px[r.randrange(h)][r.randrange(w)] = tone(shades, b - 1)
        if folds and h >= 8 and w >= 4:
            x = 1 + r.randrange(2)
            while x < w - 1:
                y0 = int(h * (0.35 + 0.3 * r.random()))
                for y in range(y0, h):
                    px[y][x] = tone(shades, b - 1)
                    if x + 1 < w and y > y0 and y % 3 == 0:
                        px[y][x + 1] = tone(shades, b + 1)
                x += 3 + r.randrange(3)
        for x in range(w):
            if r.random() < 0.3:
                px[0][x] = tone(shades, b + 1)
        if hem and h >= 3:
            px[h - 1] = [GOLD[0]] * w
            px[h - 2] = [GOLD[2] if x % 3 == 1 else GOLD[1] for x in range(w)]
        else:
            px[h - 1] = [tone(shades, b - 1)] * w
        if face == "north":
            for e in edges:
                x = 0 if e == "first" else w - 1
                for y in range(h):
                    px[y][x] = GOLD[2] if y % 4 == 1 else GOLD[1]
        if rune and face in rune_faces:
            rune(px, w, h)
        return px
    return f


def metal(base=1):
    """Old gold: flat tone, lit top row, shadowed bottom row, a rivet every third pixel on long faces."""
    def f(w, h, r, face):
        b = base + (1 if face == "up" else -1 if face in ("down", "south") else 0)
        px = [[tone(GOLD, b)] * w for _ in range(h)]
        if face in SIDES and h >= 2:
            px[0] = [tone(GOLD, b + 1)] * w
            px[h - 1] = [tone(GOLD, b - 1)] * w
            if h >= 3 and w >= 6:
                for x in range(1, w, 3):
                    px[h // 2][x] = tone(GOLD, b + 1)
        return px
    return f


def boss(w, h, r, face):
    """Gold diamond plate (buckle, circlet jewel mount): bright centre, darker rim."""
    px = [[GOLD[2]] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            if x in (0, w - 1) or y in (0, h - 1):
                px[y][x] = GOLD[1]
    return px


def cuff(w, h, r, face):
    """Wide sleeve end: gold top and bottom rows, a blue zigzag between them; dark opening underneath."""
    if face == "down":                                       # lining, darker towards the arm
        return [[ROYAL[0] if x in (0, w - 1) or y in (0, h - 1) else INDIGO[0] for x in range(w)] for y in range(h)]
    b = 2 + LIGHT[face]
    px = [[tone(ROYAL, b)] * w for _ in range(h)]
    if face in SIDES and h >= 4:
        px[0] = [GOLD[1]] * w
        px[h - 1] = [GOLD[2] if x % 3 == 1 else GOLD[1] for x in range(w)]
        for x in range(w):
            px[1 + (x % 2)][x] = BLUE[1] if x % 4 < 2 else BLUE[0]
    return px


def plate_top(shades, base, jewel):
    """Shoulder plate: gold outline on the top face, gold bottom row on the sides, optional blue diamond."""
    def f(w, h, r, face):
        b = base + LIGHT[face]
        px = [[tone(shades, b)] * w for _ in range(h)]
        if face == "up":
            for y in range(h):
                for x in range(w):
                    if x in (0, w - 1) or y in (0, h - 1):
                        px[y][x] = GOLD[1]
            if jewel and w >= 5 and h >= 5:
                diamond(px, w // 2, h // 2, 1, BLUE[1])
                put(px, w // 2, h // 2, GOLD[2])
        elif face in SIDES:
            px[h - 1] = [GOLD[1]] * w
        return px
    return f


def collar(w, h, r, face):
    """High collar: indigo outside, royal violet lining, gold top row."""
    shades = ROYAL if face == "north" else INDIGO
    px = [[tone(shades, 2 + LIGHT[face])] * w for _ in range(h)]
    if face in SIDES:
        px[0] = [GOLD[2] if x % 3 == 1 else GOLD[1] for x in range(w)]
        if face == "south" and w >= 5 and h >= 7:
            scale_glyph(px, w // 2, 2, sizes=(1, 0))
    return px


def sash(w, h, r, face):
    """Grey violet sash: gold band near the end, a blue line above it, small glyph on the broad faces."""
    px = cloth(GREY, 2, folds=False)(w, h, r, face)
    if face in SIDES and h >= 8:
        px[h - 3] = [GOLD[1]] * w
        px[h - 5] = [BLUE[0] if x % 2 else BLUE[1] for x in range(w)]
        if face in ("east", "west") and w >= 4:
            scale_glyph(px, w // 2, 1, sizes=(1, 0))
    return px


def boot(w, h, r, face):
    px = [[BOOT[2 if face == "up" else 1]] * w for _ in range(h)]
    if face == "north":
        px[0] = [GOLD[1]] * w                                # gold toe cap
    elif face == "up":
        px[0] = [GOLD[1]] * w
    elif face in SIDES:
        px[h - 1] = [BOOT[0]] * w
    return px


def jewel(w, h, r, face):
    px = [[GLOW[1]] * w for _ in range(h)]
    if w >= 2 and h >= 2:
        px[h // 2][w // 2] = GLOW[2]
        px[0][0] = GLOW[0]
    return px


def focus(w, h, r, face):
    """Cube of the palm: pale edges, blue faces, a violet heart; the same painting at the three sizes."""
    px = [[GLOW[0]] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            if x in (0, w - 1) or y in (0, h - 1):
                px[y][x] = GLOW[2] if (x in (0, w - 1) and y in (0, h - 1)) else GLOW[1]
    if w >= 3 and h >= 3:
        px[h // 2][w // 2] = GLOW_VIOLET
    return px


def tabard_up(px, w, h):
    concentric(px, w // 2, 4, ((2, GOLD[2]),))               # ring around the glowing chest stone
    for y in range(8, h - 1):
        if y % 2 == 0:
            put(px, w // 2, y, BLUE[1])                       # vertical dashed line down to the belt


def back_rune(px, w, h):
    concentric(px, w // 2, 7, ((5, GOLD[1]), (3, BLUE[1]), (1, GOLD[2])))


MATS = {
    "robe": cloth(ROBE, 2), "robe_hem": cloth(ROBE, 2, hem=True),
    "skirt_back": cloth(ROBE, 2, rune=lambda px, w, h: frieze(px, h - 3), rune_faces=("south",)),
    "coat": cloth(INDIGO, 2, folds=False), "coat_hem": cloth(INDIGO, 2, hem=True, folds=False),
    "coat_side": cloth(INDIGO, 2, rune=lambda px, w, h: scale_glyph(px, w // 2, 3), rune_faces=("east", "west")),
    "coat_back": cloth(INDIGO, 2, folds=False, rune=back_rune, rune_faces=("south",)),
    "flap_right": cloth(INDIGO, 2, edges=("last",), folds=False,
                        rune=lambda px, w, h: diamond(px, 1, h - 4, 1, GOLD[1]), rune_faces=("north",)),
    "flap_left": cloth(INDIGO, 2, edges=("first",), folds=False,
                       rune=lambda px, w, h: diamond(px, w - 2, h - 4, 1, GOLD[1]), rune_faces=("north",)),
    "lapel_right": cloth(INDIGO, 3, folds=False), "lapel_left": cloth(INDIGO, 3, folds=False),
    "tabard_up": cloth(ROYAL, 2, edges=("first", "last"), folds=False, rune=tabard_up, rune_faces=("north",)),
    "tabard": cloth(ROYAL, 2, edges=("first", "last"), folds=False,
                    rune=lambda px, w, h: scale_glyph(px, w // 2, 2), rune_faces=("north",)),
    "tabard_tip": cloth(ROYAL, 2, hem=True, folds=False),
    "sash": sash, "belt": metal(1), "boss": boss, "fin": metal(1),
    "mantle": plate_top(ROYAL, 2, False), "pauldron": plate_top(ROYAL, 3, False),
    "pauldron_top": plate_top(INDIGO, 2, True), "collar": collar, "collar_base": cloth(GREY, 2, folds=False),
    "sleeve": cloth(ROBE, 3, folds=False), "sleeve_low": cloth(ROBE, 2, folds=False), "cuff": cuff,
    "trouser": cloth(INDIGO, 1, folds=False), "boot": boot,
}
GLOW_MATS = {"jewel": (jewel, 2), "focus": focus}
GLOW_COLOURS = set(GLOW) | {GLOW_VIOLET}
