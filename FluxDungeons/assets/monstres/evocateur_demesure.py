#!/usr/bin/env python3
"""The Oversized Evoker ("L'Évocateur Démesuré"): boss, STATIC Blockbench model (.bbmodel), 2026-11-13.

An evoker reworked as an ancient enchanter whose magic changes sizes: he shrinks the players (his vexes and fangs
then look huge) and grows giant himself in his last phase. Recognisable illager (cube head, long nose, heavy brow,
grey skin, narrow eyes) in a layered enchanter's robe.

Signature motif, repeated everywhere: THE SAME SHAPE AT SHRINKING SIZES. Painted as a column of three diamonds
(5 px, 3 px, 1 px: `scale_glyph`), as concentric diamonds (chest, back, cap), and built once in the volume: three
cubes standing on a corner above the raised palm, large, medium, small.

Pose baked in the cubes: right arm forward, palm up under the three cubes; left arm low and apart, fingers spread
towards the ground (the shrinking gesture); head bent down a little and turned to the raised hand (he looks down on
shrunken players). Bones sit on the joints for later animations; no animation here.

The model is drawn at 1 texel per drawing unit then enlarged by SCALE: chunky pixels that stay readable when the
game enlarges the entity for the last phase (entity scale, nothing to change in the model).

Usage: evocateur_demesure.py [output folder] [--apercu <sheet.png>] [--cubes <table.md>]
Front faces north (-z), the boss's right is +x, ground at y = 0, origin between the feet.
"""
import json
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, export, ground, hx, matrix, tone  # noqa: E402
from golem_fonderie import app, euler, rot  # noqa: E402

IDENT = "evocateur_demesure"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-0000000e70ca")
SCALE = 1.5           # 49.5 drawing units tall -> about 4.6 blocks; the only global size setting


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- palette (dark -> light)
ROBE = pal("#22153A", "#2B1A44", "#34204F", "#40295F")      # deep violet: main robe
ROYAL = pal("#3F2659", "#4A2C68", "#563477", "#69418D")     # royal violet: tabard, mantle, cuffs
INDIGO = pal("#181A33", "#1E203C", "#252747", "#30335A")    # dark indigo: outer coat
BLUE = pal("#3C4FA0", "#536CCB", "#7F93E0")                 # magic blue: painted runes (not emissive)
GOLD = pal("#7E6534", "#B9954F", "#E5C56D", "#F4E3A1")      # shadow, ancient gold, bright gold, glint
GREY = pal("#463D52", "#544A61", "#62566F", "#766A84")      # grey violet secondary cloth: sashes, collar base
SKIN = pal("#6B6F6C", "#83877F", "#999C92", "#AEB0A4")      # illager grey beige
EYE_RING, MOUTH = hx("#544B60"), hx("#4A4648")
BROW = pal("#241C2C", "#352A40")
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


def skull(w, h, r, face):
    """Illager head. Front: forehead mark, brow shadow, darker pigment around the eyes, a lit pixel at each outer
    eye corner, hollow under the eyes, thin frowning mouth. Sides: a small scale glyph on each temple."""
    b = 2 + LIGHT[face]
    px = [[tone(SKIN, b)] * w for _ in range(h)]
    if face == "north" and w >= 10 and h >= 11:
        px[3][4] = px[3][5] = BLUE[0]                       # forehead mark, just under the circlet jewel
        for x in range(1, w - 1):
            px[4][x] = SKIN[0]                              # shadow cast by the brow
        for x in (1, 2, 3, 6, 7, 8):
            px[5][x] = px[6][x] = EYE_RING                  # darker pigment around the eyes
            px[7][x] = SKIN[1]                              # hollow under the eyes
        px[6][0] = px[6][w - 1] = BLUE[2]                   # light escaping at the outer corners
        for x in range(2, 8):
            px[9][x] = MOUTH
        px[10][1] = px[10][8] = MOUTH                       # corners pulled down: contempt
        px[10][0] = px[10][9] = SKIN[1]
    elif face in ("east", "west"):
        cx = w // 2
        diamond(px, cx, 5, 1, BLUE[1])
        put(px, cx, 8, BLUE[1])
        put(px, cx, 10, BLUE[0])
        px[h - 1] = [SKIN[1] if x != cx else BLUE[0] for x in range(w)]
    else:
        for _ in range(max(1, w * h // 14)):
            px[r.randrange(h)][r.randrange(w)] = tone(SKIN, b - 1)
    return px


def flesh(base=2, knuckles=False):
    def f(w, h, r, face):
        b = base + LIGHT[face]
        px = [[tone(SKIN, b)] * w for _ in range(h)]
        if face in SIDES and h >= 2:
            px[h - 1] = [tone(SKIN, b - 1)] * w
            if knuckles:
                for x in range(0, w, 2):
                    px[h - 1][x] = tone(SKIN, b - 2)
        return px
    return f


def brow(w, h, r, face):
    px = [[BROW[1 if face == "up" else 0]] * w for _ in range(h)]
    if face == "north":
        px[0] = [BROW[1]] * w
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


def cap(w, h, r, face):
    px = [[tone(ROYAL, 2 + LIGHT[face])] * w for _ in range(h)]
    if face == "up" and w >= 7 and h >= 7:
        concentric(px, w // 2, h // 2, ((3, GOLD[1]), (1, BLUE[1])))
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


def eye(inner_last):
    def f(w, h, r, face):
        """Narrow glowing eye (density 2, 6 x 3): heavy upper lid that drops towards the nose, pale glow, blue iris
        looking down. `inner_last`: the inner corner (nose side) is the last column of the texture."""
        if face != "north":
            return [[GLOW_LID] * w for _ in range(h)]
        px = [[GLOW[2]] * w for _ in range(h)]
        for x in range(w):
            t = x / (w - 1) if inner_last else 1 - x / (w - 1)
            if t > 0.35:
                px[0][x] = GLOW_LID
            if t > 0.85:
                px[1][x] = GLOW_LID
        mid = w // 2 - (0 if inner_last else 1)
        for x in (mid, mid + 1):
            px[1][x] = GLOW[0]
            px[h - 1][x] = GLOW[0]
        px[h - 1][0 if inner_last else w - 1] = GLOW[1]
        return px
    return f


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
    "lapel_right": cloth(INDIGO, 3, folds=False),
    "lapel_left": cloth(INDIGO, 3, folds=False),
    "tabard_up": cloth(ROYAL, 2, edges=("first", "last"), folds=False, rune=tabard_up, rune_faces=("north",)),
    "tabard": cloth(ROYAL, 2, edges=("first", "last"), folds=False,
                    rune=lambda px, w, h: scale_glyph(px, w // 2, 2), rune_faces=("north",)),
    "tabard_tip": cloth(ROYAL, 2, hem=True, folds=False),
    "sash": sash, "belt": metal(1), "boss": boss, "gold": metal(1), "gold_bright": metal(2),
    "mantle": plate_top(ROYAL, 2, False), "pauldron": plate_top(ROYAL, 3, False),
    "pauldron_top": plate_top(INDIGO, 2, True), "collar": collar, "collar_base": cloth(GREY, 2, folds=False),
    "sleeve": cloth(ROBE, 3, folds=False), "sleeve_low": cloth(ROBE, 2, folds=False), "cuff": cuff,
    "hand": flesh(2, knuckles=True), "finger": flesh(1), "skull": skull, "nose": flesh(1), "neck": flesh(0),
    "brow": brow, "cap": cap, "trouser": cloth(INDIGO, 1, folds=False), "boot": boot,
}
GLOW_MATS = {"eye_right": (eye(True), 2), "eye_left": (eye(False), 2), "jewel": (jewel, 2), "focus": focus}
GLOW_COLOURS = set(GLOW) | {GLOW_VIOLET, GLOW_LID}

# --------------------------------------------------------------------------- skeleton and pose (drawing units)
HIPS = (0, 21, 0)
NECK = (0, 35.5, 0)
HEAD_R = rot(-6, -9, 2)             # bent down a little, turned to his raised right hand
SHOULDER = {1: (7.4, 33, 0), -1: (-7.4, 33, 0)}
# arm: (shoulder rotation, extra elbow bend, finger curl, finger fan)
ARMS = {
    1: (rot(28, -30, 26), rot(72, 0, 0), 35, 6),    # right: forward and out, forearm rising, palm under the three cubes
    -1: (rot(-8, 0, -15), rot(20, 0, -4), -10, 15),  # left: low and apart, fingers spread towards the ground
}
FOCUS = ((3.0, 5.2), (1.7, 10.0), (0.9, 12.9))        # cubes above the palm: (size, height above the hand)
CORNER = rot(35.26, 0, 45)                            # a cube standing on one corner


def parts():
    P, G = [], {"Root": ((0, 0, 0), None), "Body": (HIPS, "Root"), "Skirt": (HIPS, "Body"), "Torso": (HIPS, "Body"),
                "Head": (NECK, "Torso")}

    def add(g, name, c, size, r, mat):
        P.append((g, name, list(c), tuple(size), r, mat))

    def panel(g, name, top, size, r, mat, tabs=(), tab_mat=None, axis=0):
        """Cloth panel hanging from the middle of its top edge, tilted by r around that point. `tabs`: stepped
        hem, each (offset along the wide axis, width, height) hangs under the panel and tilts with it."""
        M = rot(*r)

        def at(off):
            q = app(M, off)
            return [top[i] + q[i] for i in range(3)]
        add(g, name, at((0, -size[1] / 2, 0)), size, M, mat)
        for k, (d, w, h) in enumerate(tabs):
            off, sz = [0, -size[1] - h / 2, 0], list(size)
            off[axis], sz[axis], sz[1] = d, w, h
            add(g, f"{name}_tab_{k + 1}", at(off), sz, M, tab_mat or mat)

    # ---------------------------------------------------------------- legs: almost hidden, they carry the stance
    for s, side, yaw, z in ((1, "right", -8, -0.6), (-1, "left", 12, -1.8)):
        G[f"Leg_{side}"] = ((2.5 * s, 14, 0.3), "Root")
        add(f"Leg_{side}", "leg", (2.5 * s, 8, 0.3), (4, 12, 4), (0, 0, 0), "trouser")
        add(f"Leg_{side}", "boot", (2.7 * s, 1.25, z), (4.6, 2.5, 7.2), (0, yaw, 0), "boot")

    # ---------------------------------------------------------------- robe, layer 1: deep violet skirt
    add("Skirt", "skirt_core", (0, 12.2, 0.4), (10.6, 18, 7.4), (0, 0, 0), "robe")
    panel("Skirt", "skirt_front", (0, 21, -3.6), (10, 16.5, 1.4), (5, 0, 0), "robe",
          tabs=((-3.4, 3.2, 1.6), (3.4, 3.2, 1.6)), tab_mat="robe_hem")
    G["Skirt_back"] = ((0, 21, 3.8), "Skirt")
    panel("Skirt_back", "skirt_back", (0, 21, 3.8), (11, 16.5, 1.5), (-10, 0, 0), "skirt_back",
          tabs=((-3.9, 3.2, 1.8), (0, 3.4, 3.4), (3.9, 3.2, 1.8)), tab_mat="robe_hem")

    # ---------------------------------------------------------------- robe, layer 2: darker open coat, uneven sides
    G["Coat_right"] = ((5.7, 21.5, 0.2), "Skirt")
    panel("Coat_right", "coat_right", (5.7, 21.5, 0.2), (1.6, 15.5, 9), (0, 0, 8), "coat_side",
          tabs=((-2.6, 3.4, 1.6), (2.4, 4.0, 2.6)), tab_mat="coat_hem", axis=2)
    G["Coat_left"] = ((-5.7, 21.5, 0.8), "Skirt")
    panel("Coat_left", "coat_left", (-5.7, 21.5, 0.8), (1.6, 16.5, 9), (-3, 0, -11), "coat_side",
          tabs=((-2.4, 4.0, 1.4), (2.6, 3.4, 2.8)), tab_mat="coat_hem", axis=2)
    G["Flap_right"] = ((4.4, 21.5, -4.3), "Skirt")
    panel("Flap_right", "flap_right", (4.4, 21.5, -4.3), (3.4, 14.5, 1.3), (8, -7, 5), "flap_right",
          tabs=((0.6, 2.2, 2.2),), tab_mat="coat_hem")
    G["Flap_left"] = ((-4.4, 21.5, -4.4), "Skirt")
    panel("Flap_left", "flap_left", (-4.4, 21.5, -4.4), (3.4, 16, 1.3), (6, 8, -7), "flap_left",
          tabs=((-0.6, 2.2, 1.6),), tab_mat="coat_hem")

    # ---------------------------------------------------------------- tabard: royal violet strip, the rune column
    G["Tabard"] = ((0, 20.5, -4.6), "Skirt")
    panel("Tabard", "tabard_low", (0, 20.5, -4.6), (7, 14.5, 0.9), (9, 0, 0), "tabard",
          tabs=((0, 5, 1.2),), tab_mat="tabard_tip")
    panel("Tabard", "tabard_point", (0, 20.5, -4.6), (3, 16.9, 0.88), (9, 0, 0), "tabard_tip")
    panel("Tabard", "tabard_end", (0, 20.5, -4.6), (1, 18.0, 0.86), (9, 0, 0), "tabard_tip")

    # ---------------------------------------------------------------- sashes: grey violet, one short, one long
    G["Sash_right"] = ((6.9, 20.6, -1.6), "Skirt")
    panel("Sash_right", "sash_right", (6.9, 20.6, -1.6), (1.1, 9, 4), (5, -30, 15), "sash")
    G["Sash_left"] = ((-7.0, 20.6, 0.6), "Skirt")
    panel("Sash_left", "sash_left", (-7.0, 20.6, 0.6), (1.1, 12.5, 4.6), (-7, 35, -19), "sash",
          tabs=((-1.4, 1.6, 1.6), (1.4, 1.6, 1.6)), axis=2)

    # ---------------------------------------------------------------- torso
    add("Torso", "torso", (0, 28, 0), (11, 14.5, 7), (0, 0, 0), "robe")
    add("Torso", "belt", (0, 20.9, 0), (12.2, 2.4, 8.4), (0, 0, 0), "belt")
    add("Torso", "buckle", (0, 20.9, -4.4), (3.4, 3.4, 0.9), (0, 0, 45), "boss")
    add("Torso", "tabard_up", (0, 27.5, -3.85), (7, 12, 0.8), (0, 0, 0), "tabard_up")
    add("Torso", "lapel_right", (4.55, 27.6, -3.8), (2.6, 12, 0.9), (0, 0, -4), "lapel_right")
    add("Torso", "lapel_left", (-4.55, 27.6, -3.8), (2.6, 12, 0.9), (0, 0, 4), "lapel_left")
    add("Torso", "coat_back", (0, 28, 3.9), (11, 14, 1), (0, 0, 0), "coat_back")
    for s, side in ((1, "right"), (-1, "left")):
        add("Torso", f"coat_flank_{side}", (5.75 * s, 28, 0.4), (0.9, 14, 8.1), (0, 0, 0), "coat")
    add("Torso", "neck", (0, 35.6, 0.2), (5, 1.8, 5), (0, 0, 0), "neck")
    add("Torso", "collar_base", (0, 35.6, 0.4), (8.6, 1.4, 8), (0, 0, 0), "collar_base")
    # mantle and shoulders: wider than a vanilla evoker, two tilted layers and a gold fin (taller on the left, to
    # balance the raised right arm)
    add("Torso", "mantle", (0, 34.0, 0.3), (16, 2.6, 9), (0, 0, 0), "mantle")
    for s, side, fin in ((1, "right", (0.8, 3.2, 4.6)), (-1, "left", (0.8, 5.0, 5.6))):
        add("Torso", f"pauldron_{side}", (8.7 * s, 34.6, 0.3), (6.6, 2.4, 9.8), (0, 0, -16 * s), "pauldron")
        add("Torso", f"pauldron_top_{side}", (8.0 * s, 36.2, 0.3), (4.6, 1.6, 7.6), (0, 0, -24 * s), "pauldron_top")
        add("Torso", f"fin_{side}", (10.6 * s, 36.4 + fin[1] / 2 - 1.2, 0.3), fin, (0, 0, -28 * s), "gold")
    # high collar behind the head: three panels opening towards the front
    add("Torso", "collar_back", (0, 39.6, 5.6), (5, 8.5, 1), (-10, 0, 0), "collar")
    for s, side in ((1, "right"), (-1, "left")):
        add("Torso", f"collar_{side}", (4.3 * s, 39.2, 4.2), (5, 7.6, 1), (-10, 32 * s, -8 * s), "collar")
    # glowing stones set in the robe, each in its own full-bright bone
    G["glow_chest"] = ((0, 29.5, -4.3), "Torso")
    add("glow_chest", "chest_stone", (0, 29.5, -4.35), (1.5, 1.5, 0.4), (0, 0, 45), "jewel")
    G["glow_buckle"] = ((0, 20.9, -4.9), "Torso")
    add("glow_buckle", "buckle_stone", (0, 20.9, -4.95), (1.4, 1.4, 0.4), (0, 0, 45), "jewel")
    G["glow_back"] = ((0, 27.5, 4.4), "Torso")
    add("glow_back", "back_stone", (0, 27.5, 4.5), (1.5, 1.5, 0.4), (0, 0, 45), "jewel")

    # ---------------------------------------------------------------- head
    on_head = attach(NECK, HEAD_R)

    def hd(g, name, c, size, r, mat):
        cc, M = on_head(c, r)
        add(g, name, cc, size, M, mat)

    hd("Head", "skull", (0, 41.5, -0.3), (10, 11, 9), (0, 0, 0), "skull")
    hd("Head", "nose", (0, 38.5, -5.6), (2, 4.6, 2), (-6, 0, 0), "nose")
    hd("Head", "brow_right", (2.5, 42.7, -5.0), (4.8, 1.5, 1.3), (-10, 0, 13), "brow")
    hd("Head", "brow_left", (-2.5, 42.7, -5.0), (4.8, 1.5, 1.3), (-10, 0, -13), "brow")
    hd("Head", "cap", (0, 47.3, -0.3), (9, 0.9, 8), (0, 0, 0), "cap")
    hd("Head", "circlet_front", (0, 45.3, -4.95), (10.8, 1.4, 0.7), (0, 0, 0), "gold")
    hd("Head", "circlet_back", (0, 45.3, 4.35), (10.8, 1.4, 0.7), (0, 0, 0), "gold")
    for s, side in ((1, "right"), (-1, "left")):
        hd("Head", f"circlet_{side}", (5.15 * s, 45.3, -0.3), (0.7, 1.4, 9.4), (0, 0, 0), "gold")
        hd("Head", f"prong_{side}", (4.7 * s, 47.4, -4.7), (0.9, 3.4, 0.9), (0, 0, -14 * s), "gold_bright")
    hd("Head", "crest_large", (0, 46.4, -5.3), (2.8, 2.8, 0.8), (0, 0, 45), "boss")
    hd("Head", "crest_small", (0, 48.9, -5.3), (1.3, 1.3, 0.7), (0, 0, 45), "gold_bright")
    G["glow_eyes"] = (on_head((0, 40.9, -4.8), (0, 0, 0))[0], "Head")
    hd("glow_eyes", "eye_right", (2.5, 40.9, -4.9), (2.6, 1.4, 0.3), (0, 0, 0), "eye_right")
    hd("glow_eyes", "eye_left", (-2.5, 40.9, -4.9), (2.6, 1.4, 0.3), (0, 0, 0), "eye_left")
    G["glow_circlet"] = (on_head((0, 46.4, -5.7), (0, 0, 0))[0], "Head")
    hd("glow_circlet", "circlet_stone", (0, 46.4, -5.8), (1.2, 1.2, 0.4), (0, 0, 45), "jewel")

    # ---------------------------------------------------------------- arms: thin, wide cuffs, free hands
    for s, side in ((1, "right"), (-1, "left")):
        R_sh, R_el, curl, fan = ARMS[s]
        sh, el, wr = SHOULDER[s], (8.0 * s, 24.8, 0), (8.0 * s, 15.6, 0)
        upper = attach(sh, R_sh)
        bend = attach(el, R_el)

        def lower(c, r, upper=upper, bend=bend):
            return upper(*bend(c, r))
        ag, fg, hg = f"Arm_{side}", f"Forearm_{side}", f"Hand_{side}"
        G[ag] = (sh, "Torso")
        G[fg] = (upper(el, (0, 0, 0))[0], ag)
        G[hg] = (lower(wr, (0, 0, 0))[0], fg)
        for g, name, c, size, r, mat, place in (
            (ag, "sleeve_upper", (8.0 * s, 29.0, 0), (4.2, 9, 4.6), (0, 0, 0), "sleeve", upper),
            (fg, "sleeve_lower", (8.0 * s, 21.8, 0), (5, 6.4, 5.4), (0, 0, 0), "sleeve_low", lower),
            (fg, "cuff", (8.0 * s, 17.3, 0), (6.6, 4, 7), (0, 0, 0), "cuff", lower),
            (hg, "hand", (8.0 * s, 14.1, 0), (3.2, 3.2, 3.6), (0, 0, 0), "hand", lower),
        ):
            cc, M = place(c, r)
            add(g, name, cc, size, M, mat)
        for k, dx in enumerate((-1.1, 0, 1.1)):               # three fingers hinged on the knuckles
            knuckle = (8.0 * s + dx, 12.6, 0.6)
            c, M = attach(knuckle, rot(curl, 0, fan * dx / 1.1))((knuckle[0], knuckle[1] - 1.0, knuckle[2]), (0, 0, 0))
            cc, M = lower(c, M)
            add(hg, f"finger_{k + 1}", cc, (0.9, 2.2, 0.9), M, "finger")
        knuckle = (8.0 * s - 1.2 * s, 13.4, -1.7)             # thumb on the inner side, pointing forward
        c, M = attach(knuckle, rot(curl + 30, 0, -20 * s))((knuckle[0], knuckle[1] - 0.8, knuckle[2]), (0, 0, 0))
        cc, M = lower(c, M)
        add(hg, "thumb", cc, (0.9, 1.8, 0.9), M, "finger")
        if s == 1:                                            # the same cube at three sizes, above the palm
            palm = lower((8.0, 14.1, 0), (0, 0, 0))[0]
            G["glow_focus"] = ((palm[0], palm[1] + 5.2, palm[2]), hg)
            for k, (size, up) in enumerate(FOCUS):
                add("glow_focus", f"focus_{k + 1}", (palm[0], palm[1] + up, palm[2]), (size,) * 3, CORNER, "focus")

    order, done = [], set()                                   # every parent before its children
    while len(order) < len(G):
        for k, (pv, par) in G.items():
            if k not in done and (par is None or par in done):
                order.append(k)
                done.add(k)
    return P, {k: G[k] for k in order}


def cube_table(P, groups, dims):
    """Markdown table of the delivered model: one line per cube, pixels of the model (16 = 1 block)."""
    def n(v):
        return f"{v:.2f}".rstrip("0").rstrip(".") or "0"
    rows = [f"# {IDENT} : cubes", "",
            f"{len(P)} cubes, {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p). "
            "Centre, taille et rotation (x, y, z en degrés, autour du centre du cube) en pixels du modèle livré.", "",
            "| Os | Cube | Taille (x, y, z) | Centre (x, y, z) | Rotation | Matériau |", "| -- | -- | -- | -- | -- | -- |"]
    for g, name, c, size, r, m, raw in P:
        rr = euler(matrix(r))
        rows.append(f"| `{g}` | `{name}` | {', '.join(n(v) for v in size)} | {', '.join(n(v) for v in c)} | "
                    f"{', '.join(n(v) for v in rr)} | `{m}` |")
    rows += ["", "## Os", "", "| Os | Parent | Pivot (x, y, z) |", "| -- | -- | -- |"]
    rows += [f"| `{k}` | {'`' + par + '`' if par else ''} | {', '.join(n(v) for v in pv)} |" for k, (pv, par) in groups.items()]
    return "\n".join(rows) + "\n"


if __name__ == "__main__":
    args = sys.argv[1:]
    opts = {}
    for flag in ("--apercu", "--cubes"):
        if flag in args:
            i = args.index(flag)
            opts[flag] = args[i + 1]
            del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims = ground(*parts(), scale=SCALE, center=False)
    uv, atlases, sizes = bake(P, MATS, GLOW_MATS)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    print(f"{len(P)} cubes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; "
          f"textures {sizes[0]} et {sizes[1]}")
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
    if "--apercu" in opts:
        apercu(data, atlases, sizes, opts["--apercu"], GLOW_COLOURS,
               views=((0, 4), (35, 12), (90, 3), (180, 6), (-40, 10)), size=440)
    if "--cubes" in opts:
        with open(opts["--cubes"], "w", encoding="utf-8") as f:
            f.write(cube_table(P, groups, dims))
        print("écrit", opts["--cubes"])
