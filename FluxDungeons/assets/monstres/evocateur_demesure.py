#!/usr/bin/env python3
"""The Oversized Evoker ("L'Évocateur Démesuré"): boss, STATIC Blockbench model (.bbmodel), 2026-11-13.

An evoker reworked as an ancient enchanter whose magic changes sizes: he shrinks the players (his vexes and fangs
then look huge) and grows giant himself in his last phase. Recognisable illager (cube head, long nose, heavy brow,
grey skin, narrow eyes) in a layered enchanter's robe.

Few cubes, careful painting (art pass of 2026-11-13): one palette per material, nothing random, every pixel placed.
Cloth is matt with vertical fold shadows, gold is plain and rare (tabard edges, hems, cuffs, shoulders, crown),
skin is natural, crystal is the only emissive material (crown, chest, back, palm). His power shows in one place in
the volume, three crystal cubes standing on a corner above the raised palm (large, medium, small), and in the back
emblem (two frames closing on a crystal).

Pose baked in the cubes: right arm forward, palm up under the three cubes; left arm low and apart, fingers spread
towards the ground (the shrinking gesture); head bent down a little and turned to the raised hand (he looks down on
shrunken players). Bones sit on the joints; animations in evocateur_demesure_anim.py.

The model is drawn at 1 texel per drawing unit then enlarged by SCALE: chunky pixels that stay readable when the
game enlarges the entity for the last phase (entity scale, nothing to change in the model).

Usage: evocateur_demesure.py [output folder] [--apercu <sheet.png>] [--cubes <table.md>]
       [--anims <folder> [--only a,b] [--count n]] (one strip of frames per animation, cf. evocateur_demesure_anim.py)
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


# --------------------------------------------------------------------------- palettes (one per material)
# Cloth: one violet ramp for every garment; a garment picks its level on it (coat darker, tabard lighter), so all
# layers belong to the same fabric family. Index: 0 contact, 1 deep shadow, 2 shadow, 3 main, 4 lit, 5 glint.
CLOTH = pal("#1D172A", "#261B36", "#352447", "#493064", "#5B3D79", "#72538C")
SASH = pal("#3C3446", "#4E4459", "#62566F", "#766A84")      # grey violet secondary cloth: deep, shadow, main, lit
GOLD = pal("#725329", "#B18B43", "#D2B66D", "#E4CD8A")      # old gold: dark, main, light, glint (crown and trims)
SKIN = pal("#4B4E49", "#666960", "#85877F", "#A3A398", "#B6B5A8")   # deep shadow, shadow, main, light, brightest
EYE_WHITE, EYE_SHADE, IRIS, PUPIL, EYE_LINE = (hx(c) for c in ("#E3E0D0", "#BDBBAF", "#536D9A", "#252735", "#41443F"))
BROW = pal("#302A2D", "#463A3A")
BOOT, BOOT_TRIM = hx("#1D172A"), hx("#9D793D")
# Crystal: the only emissive material (the preview recognises it by colour).
CRYSTAL = pal("#5264A6", "#8198E8", "#B8C8FF", "#E1E7FF")

SIDES = ("north", "south", "east", "west")


def put(px, x, y, c):
    if 0 <= y < len(px) and 0 <= x < len(px[0]):
        px[y][x] = c


def diamond(px, cx, cy, rad, colour):
    """Outline of a pixel diamond (|dx| + |dy| = rad)."""
    for dy in range(-rad, rad + 1):
        dx = rad - abs(dy)
        put(px, cx - dx, cy + dy, colour)
        put(px, cx + dx, cy + dy, colour)


# --------------------------------------------------------------------------- painters (nothing random: every pixel is placed)
def cloth(level=3, folds=(), edges=(), hem=False, art=None, art_face="north", shades=CLOTH):
    """Thick matt fabric. Top face one tone lighter, front in the main tone, the other sides one tone darker,
    underside deepest. `folds`: (x as a share of the width, start as a share of the height): a one-pixel shadow
    line running down to the hem, two pixels wide on its last third, a lit line on its left on the front.
    Bottom row in shadow (contact) or one row of gold (`hem`). `edges`: gold column on the front ("first", "last")."""
    def f(w, h, r, face):
        if face == "up":
            return [[tone(shades, level + 1)] * w for _ in range(h)]
        if face == "down":
            return [[tone(shades, level - 2)] * w for _ in range(h)]
        b = level if face in ("north", "south") else level - 1
        px = [[tone(shades, b)] * w for _ in range(h)]
        if h >= 6 and w >= 4:
            for fx, fy in folds:
                x, y0 = round(fx * (w - 1)), round(fy * h)
                y1 = y0 + (h - y0) * 2 // 3
                for y in range(y0, h):
                    px[y][x] = tone(shades, b - 1)
                    if y >= y1 and x + 1 < w:
                        px[y][x + 1] = tone(shades, b - 1)
                    if face == "north" and y0 < y < y1 and x >= 1:
                        px[y][x - 1] = tone(shades, b + 1)
        if h >= 3:
            px[h - 1] = [GOLD[1] if hem else tone(shades, b - 1)] * w
        if face == "north":
            for e in edges:
                for y in range(h):
                    px[y][0 if e == "first" else w - 1] = GOLD[1]
        if art and face == art_face:
            art(px, w, h)
        return px
    return f


def metal(w, h, r, face):
    """Old gold, plain: lit top face and top row, main tone on the front, darker sides and bottom row."""
    if face == "up":
        return [[GOLD[2]] * w for _ in range(h)]
    if face == "down":
        return [[GOLD[0]] * w for _ in range(h)]
    px = [[GOLD[1]] * w for _ in range(h)]
    if h >= 2:
        px[h - 1] = [GOLD[0]] * w
    if face == "north" and h >= 3:
        px[0] = [GOLD[2]] * w
    return px


def mount(w, h, r, face):
    """Gold plate turned 45 degrees (crown jewel mount, belt buckle): main tone, lit upper edges, dark lower edges."""
    px = [[GOLD[1]] * w for _ in range(h)]
    if face == "north":
        for i in range(w):
            px[0][i] = GOLD[2]
            px[h - 1][i] = GOLD[0]
        for j in range(h):
            px[j][0] = GOLD[2] if j < h - 1 else GOLD[0]
            px[j][w - 1] = GOLD[0] if j else GOLD[2]
    return px


def crystal(w, h, r, face):
    """Blue violet crystal: main tone, deep lower right edge, light upper left edge, one glint pixel."""
    px = [[CRYSTAL[1]] * w for _ in range(h)]
    if w >= 2 and h >= 2:
        for i in range(w):
            px[h - 1][i] = CRYSTAL[0]
            px[0][i] = CRYSTAL[2]
        for j in range(h):
            px[j][w - 1] = CRYSTAL[0]
            px[j][0] = CRYSTAL[2]
        px[h - 1][0] = px[0][w - 1] = CRYSTAL[1]
        px[0][0] = CRYSTAL[3]
        if w >= 4 and h >= 4:
            px[1][1] = CRYSTAL[3]
    return px


def skull(w, h, r, face):
    """Illager head, matt skin. Front: lit centre (forehead, bridge of the nose), sides of the face one tone
    darker, shadow under the brows, cheekbones, thin straight mouth, shadowed chin. No magic colour on the skin."""
    if face == "up":
        return [[SKIN[3]] * w for _ in range(h)]
    if face == "down":
        return [[SKIN[0]] * w for _ in range(h)]
    if face != "north" or w < 10 or h < 11:
        px = [[SKIN[1]] * w for _ in range(h)]
        px[h - 1] = [SKIN[0]] * w
        return px
    px = [[SKIN[2]] * w for _ in range(h)]
    for y in range(h):
        px[y][0] = px[y][w - 1] = SKIN[1]                  # the face turns away on both sides
    for y in (3, 4):
        for x in (3, 4, 5, 6):
            px[y][x] = SKIN[3]                              # forehead
    px[3][4] = px[3][5] = SKIN[4]
    for x in (1, 2, 3, 6, 7, 8):
        px[5][x] = SKIN[1]                                  # shadow between brow and eye: keeps them apart
    for y in (5, 6):
        px[y][4] = px[y][5] = SKIN[3]                       # bridge of the nose
    for x in (1, 2, 7, 8):
        px[7][x] = SKIN[3]                                  # cheekbones, right under the eyes
    for x in range(1, 9):
        px[9][x] = SKIN[0]                                  # mouth: one thin straight line, closed
    px[10] = [SKIN[1]] * w                                  # chin
    return px


def nose(w, h, r, face):
    if face == "down":
        return [[SKIN[0]] * w for _ in range(h)]
    px = [[SKIN[3 if face in ("north", "up") else 1]] * w for _ in range(h)]
    if face == "north":
        px[0] = [SKIN[4]] * w
        px[h - 1] = [SKIN[2]] * w
    else:
        px[h - 1] = [SKIN[0]] * w
    return px


def flesh(w, h, r, face):
    if face == "down":
        return [[SKIN[0]] * w for _ in range(h)]
    px = [[SKIN[3 if face == "up" else 2 if face == "north" else 1]] * w for _ in range(h)]
    return px


def brow(w, h, r, face):
    return [[BROW[1 if face == "up" else 0]] * w for _ in range(h)]


def eye(inner_last):
    def f(w, h, r, face):
        """Eye, density 2 (6 x 3). Top row: the lid, a clean dark line. Then the white, shaded at the outer end,
        a 2 x 2 blue grey iris set a little towards the nose, its pupil low on the inner side: a focused look,
        from above. `inner_last`: the nose side is the last column."""
        if face != "north" or w < 6 or h < 3:
            return [[EYE_LINE] * w for _ in range(h)]
        rows = [[EYE_LINE] * 6,
                [EYE_SHADE, EYE_WHITE, EYE_WHITE, IRIS, IRIS, EYE_WHITE],
                [EYE_SHADE, EYE_WHITE, EYE_WHITE, IRIS, PUPIL, EYE_SHADE]]
        return [row if inner_last else row[::-1] for row in rows]
    return f


def cuff(w, h, r, face):
    """Wide sleeve end: lit violet band closed by one gold row; dark lining underneath."""
    if face == "down":
        return [[CLOTH[2] if x in (0, w - 1) or y in (0, h - 1) else CLOTH[0] for x in range(w)] for y in range(h)]
    px = cloth(4)(w, h, r, face)
    if face in SIDES:
        px[h - 1] = [GOLD[1]] * w
    return px


def pauldron(w, h, r, face):
    """Reinforced cloth shoulder: lit top, one fine gold row along the bottom of its sides."""
    px = cloth(3)(w, h, r, face)
    if face in SIDES:
        px[h - 1] = [GOLD[1]] * w
    return px


def collar(w, h, r, face):
    """High collar: dark coat cloth outside, lit lining inside."""
    return cloth(4 if face == "north" else 3)(w, h, r, "north" if face in ("north", "south") else face)


def sash(w, h, r, face):
    px = cloth(2, shades=SASH)(w, h, r, "north" if face in ("east", "west") else "east" if face in SIDES else face)
    if face in SIDES and h >= 8:
        px[h - 3] = [GOLD[1]] * w
    return px


def belt(w, h, r, face):
    """Dark violet band between torso and skirt."""
    px = [[CLOTH[2 if face == "up" else 1]] * w for _ in range(h)]
    if face in SIDES:
        px[h - 1] = [CLOTH[0]] * w
    return px


def boot(w, h, r, face):
    px = [[BOOT] * w for _ in range(h)]
    if face == "up":
        px = [[CLOTH[1]] * w for _ in range(h)]
        px[0] = [BOOT_TRIM] * w
    elif face == "north":
        px[0] = [BOOT_TRIM] * w                              # one gold line on the toe
    return px


def tabard_up(px, w, h):
    """Chest motif: one vertical gold line carrying two small diamonds, the upper one around the crystal."""
    cx = w // 2
    for y in range(1, h - 1):
        px[y][cx] = GOLD[1]
    for cy in (4, 9):
        px[cy][cx] = px[1][1]                                 # open centre: cloth, or the crystal on the upper one
        diamond(px, cx, cy, 1, GOLD[2])


def emblem(px, w, h):
    """Back emblem: two square frames around the central diamond crystal, space closing on one point."""
    cx, cy = w // 2, 7
    for rad in (2, 4):                                        # plain square frames: straight lines, no stair steps
        for d in range(-rad, rad + 1):
            for x, y in ((cx + d, cy - rad), (cx + d, cy + rad), (cx - rad, cy + d), (cx + rad, cy + d)):
                put(px, x, y, GOLD[1])


MATS = {
    "robe": cloth(3, folds=((0.3, 0.5), (0.75, 0.35))), "robe_hem": cloth(3, hem=True),
    "skirt_back": cloth(3, folds=((0.2, 0.3), (0.5, 0.15), (0.8, 0.4))),
    "coat": cloth(2), "coat_hem": cloth(2, hem=True),
    "coat_side": cloth(2, folds=((0.3, 0.3), (0.7, 0.5))),
    "coat_back": cloth(2, art=emblem, art_face="south"),
    "flap": cloth(2, folds=((0.5, 0.45),)), "lapel": cloth(2),
    "tabard_up": cloth(4, edges=("first", "last"), art=tabard_up),
    "tabard": cloth(4, edges=("first", "last")), "tabard_tip": cloth(4, hem=True),
    "sash": sash, "belt": belt, "mount": mount, "gold": metal,
    "mantle": cloth(3), "pauldron": pauldron, "pauldron_top": cloth(4), "collar": collar, "collar_base": cloth(2),
    "sleeve": cloth(3, folds=((0.6, 0.3),)), "sleeve_low": cloth(3, folds=((0.35, 0.2),)), "cuff": cuff,
    "hand": flesh, "finger": flesh, "skull": skull, "nose": nose, "neck": flesh, "brow": brow,
    "eye_right": (eye(True), 2), "eye_left": (eye(False), 2),
    "cap": cloth(3), "trouser": cloth(1), "boot": boot,
}
GLOW_MATS = {"crystal": (crystal, 2), "focus": crystal}
GLOW_COLOURS = set(CRYSTAL)
# The outfit is the first model's ornate dress (evocateur_tenue.py), kept on 2026-11-14; the head, the hands and
# the crown crystal stay the art pass's, painted above. The plain cloth painters above still dress the Oracle.
import evocateur_tenue as TENUE  # noqa: E402
MATS.update(TENUE.MATS)
GLOW_MATS.update(TENUE.GLOW_MATS)
GLOW_COLOURS |= TENUE.GLOW_COLOURS

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
        add("Torso", f"fin_{side}", (10.6 * s, 36.4 + fin[1] / 2 - 1.2, 0.3), fin, (0, 0, -28 * s), "fin")
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
    # brows: two separate thin bars sloping to the nose, one clear row of skin above the eyes
    hd("Head", "brow_right", (2.7, 42.75, -5.0), (3.4, 1.0, 1.0), (0, 0, 10), "brow")
    hd("Head", "brow_left", (-2.7, 42.75, -5.0), (3.4, 1.0, 1.0), (0, 0, -10), "brow")
    hd("Head", "eye_right", (2.6, 40.75, -4.85), (3, 1.5, 0.2), (0, 0, 0), "eye_right")
    hd("Head", "eye_left", (-2.6, 40.75, -4.85), (3, 1.5, 0.2), (0, 0, 0), "eye_left")
    hd("Head", "cap", (0, 47.2, -0.3), (8.6, 0.5, 7.6), (0, 0, 0), "cap")
    # crown: a low band fitted to the skull, two short rises over the temples, one jewel mount on the forehead
    hd("Head", "crown_front", (0, 45.6, -4.95), (10.8, 1.8, 0.7), (0, 0, 0), "gold")
    hd("Head", "crown_back", (0, 45.6, 4.35), (10.8, 1.8, 0.7), (0, 0, 0), "gold")
    for s, side in ((1, "right"), (-1, "left")):
        hd("Head", f"crown_{side}", (5.05 * s, 45.6, -0.3), (0.7, 1.8, 8.6), (0, 0, 0), "gold")
        hd("Head", f"crown_rise_{side}", (5.05 * s, 46.9, -1.3), (0.7, 0.9, 4.2), (0, 0, 0), "gold")
    hd("Head", "crown_mount", (0, 46.0, -5.3), (2.4, 2.4, 0.7), (0, 0, 45), "mount")
    G["glow_crown"] = (on_head((0, 46.0, -5.6), (0, 0, 0))[0], "Head")
    hd("glow_crown", "crown_crystal", (0, 46.0, -5.7), (1.0, 1.0, 0.4), (0, 0, 45), "crystal")

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
        # three fingers hinged on the knuckles, in a bone of their own (they close together), and the thumb in
        # another: the pinch, the closing fist
        fb, tb = f"Fingers_{side}", f"Thumb_{side}"
        G[fb] = (lower((8.0 * s, 12.6, 0.6), (0, 0, 0))[0], hg)
        for k, dx in enumerate((-1.1, 0, 1.1)):
            knuckle = (8.0 * s + dx, 12.6, 0.6)
            c, M = attach(knuckle, rot(curl, 0, fan * dx / 1.1))((knuckle[0], knuckle[1] - 1.0, knuckle[2]), (0, 0, 0))
            cc, M = lower(c, M)
            add(fb, f"finger_{k + 1}", cc, (0.9, 2.2, 0.9), M, "finger")
        knuckle = (8.0 * s - 1.2 * s, 13.4, -1.7)             # thumb on the inner side, pointing forward
        G[tb] = (lower(knuckle, (0, 0, 0))[0], hg)
        c, M = attach(knuckle, rot(curl + 30, 0, -20 * s))((knuckle[0], knuckle[1] - 0.8, knuckle[2]), (0, 0, 0))
        cc, M = lower(c, M)
        add(tb, "thumb", cc, (0.9, 1.8, 0.9), M, "finger")
        if s == 1:                                            # the same cube at three sizes, above the palm
            palm = lower((8.0, 14.1, 0), (0, 0, 0))[0]
            for k, (size, up) in enumerate(FOCUS):            # one bone each, pivot in its centre: spin, hide
                c = (palm[0], palm[1] + up, palm[2])
                G[f"glow_focus_{k + 1}"] = (c, hg)
                add(f"glow_focus_{k + 1}", f"focus_{k + 1}", c, (size,) * 3, CORNER, "focus")

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
    for flag in ("--apercu", "--cubes", "--anims", "--only", "--count"):
        if flag in args:
            i = args.index(flag)
            opts[flag] = args[i + 1]
            del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims = ground(*parts(), scale=SCALE, center=False)
    uv, atlases, sizes = bake(P, MATS, GLOW_MATS)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    import evocateur_demesure_anim as EA
    anims = EA.build(data, EA.uid_for(NS, IDENT))
    print(f"{len(P)} cubes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; "
          f"textures {sizes[0]} et {sizes[1]} ; {len(anims)} animations")
    if "--anims" in opts:
        EA.strips(data, anims, atlases, sizes, opts["--anims"], count=int(opts.get("--count", 10)),
                  only=opts["--only"].split(",") if "--only" in opts else None)
    data["animations"] = EA.finish(anims)
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
