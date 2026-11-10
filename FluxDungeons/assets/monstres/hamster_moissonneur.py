#!/usr/bin/env python3
"""Harvest Hamster (Le Hamster Moissonneur): farm boss, STATIC Blockbench model (.bbmodel), 2026-11-10.

A giant fantasy hamster from enchanted fields: a very round, compact Syrian hamster body, a small head that melts
into the front of the ball, and two HUGE turquoise cheek pouches stuffed with food (one carrot pokes out of the
mouth, another out of the right cheek, potatoes at the mouth and on the left cheek). Emerald / jade fur, pale green
belly and muzzle, thick dark frowning brows over small slanted lime eyes, a grumbling half-open mouth: an angry boss
guarding its crops. Golden straw heaps wrap its rear and flanks (the "harvest ball" it will roll into later) and a
few slightly enchanted wheat ears stick out of the fur. Tiny tail hidden in the straw, no armour, no tools.

Visual priority (brief): cheeks > frown > round silhouette > green fur > golden wheat > food.

How the roundness works (own approach of this model):
- Body, head, each cheek and the big straw heaps are "rounded boxes": three boxes crossed along x, y and z, each one
  slimmed by about 28 % on its two other axes, so the 12 edges are cut in steps (never a box turned 45 degrees:
  its corners would stick out as spikes through the faces). The steps read as pixel-art roundness.
- Fur and cheeks are lit from the WORLD position of each texel, as if it lay on the ellipsoid of its blob (body,
  head, right cheek, left cheek, leg): all the volumes of one blob shade as one soft ball, with no seam where two
  boxes meet. The pale belly / chest is placed the same way (by position on the body ball), so it runs across boxes.
- Painted, never built: fur tufts, toes, grains, straw stalks and the mouth (1 texel per unit, like a 16 x 16 block).

Pose baked into the cubes; bones placed at the joints for future animation (cheek puff and spit: Cheek_*, Jaw;
ball roll: Straw follows Body; legs separate so the feet stay on the ground).

Usage: hamster_moissonneur.py [output dir] [--apercu <sheet.png>]
Front faces north (-z), the monster's right side is +x, ground at y = 0, origin kept at the body centre (x = z = 0,
brief coordinates); 16 units = 1 block.
"""
import json
import math
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, corners, export, ground, hx, matrix  # noqa: E402
from golem_fonderie import app, mul, rot  # noqa: E402

IDENT = "hamster_moissonneur"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-0000000a3570")
SCALE = 1.0           # drawing units = model units (16 = 1 block); single global size knob


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- palette (second brief, dark -> light)
FUR = pal("#173E32", "#285D43", "#397B50", "#55A66B", "#82C77C")
MAGIC = hx("#B5D86A")                                     # lime glints, kept rare
CHEEK = pal("#23636B", "#328C91", "#58B8AE", "#8BD6BD")
PALE = pal("#789C70", "#A8C98A", "#D0E5A9")               # belly, chest, muzzle, toes
WHEAT = pal("#98712D", "#C99B3C", "#E5BD56", "#F4DB86")
STEM = hx("#8C7435")
CARROT = pal("#A94E26", "#E77D32", "#F5AD55")
LEAF = pal("#365E32", "#578C42")
POTATO = pal("#775536", "#A98254", "#C7A16C")
BROW = pal("#102B26", "#173E32")
IRIS, PUPIL, GLINT = hx("#B5D86A"), hx("#14271D"), hx("#DDF3A0")
TOOTH, TOOTH_SHADE = hx("#E6DEB4"), hx("#BDB58A")
MOUTH = hx("#102B26")

BAYER = ((0, 8, 2, 10), (12, 4, 14, 6), (3, 11, 1, 9), (15, 7, 13, 5))
LIGHT = (lambda v: [c / math.sqrt(sum(x * x for x in v)) for c in v])((-0.3, 0.85, -0.45))   # top, front, a bit left


def q(p, v):
    return p[max(0, min(len(p) - 1, int(round(v))))]


def dith(x, y, k=0.7):
    return (BAYER[y % 4][x % 4] / 16 - 0.47) * k


# --------------------------------------------------------------------------- world position of a texel
def texel_world(part, face, u, v):
    """World point of the texel at (u, v) in [0, 1] on `face` of `part` (Blockbench face orientation: side faces
    have their top row at +y; north is seen from the front, so its left edge is +x)."""
    _, _, c, size, r, _ = part
    hx_, hy, hz = (s / 2 for s in size)
    a, b = u * 2 - 1, v * 2 - 1
    local = {"north": (-a * hx_, -b * hy, -hz), "south": (a * hx_, -b * hy, hz),
             "east": (hx_, -b * hy, -a * hz), "west": (-hx_, -b * hy, a * hz),
             "up": (a * hx_, hy, b * hz), "down": (a * hx_, -hy, -b * hz)}[face]
    d = app(matrix(r), list(local))
    return [c[i] + d[i] for i in range(3)]


def lit(blob, p):
    """Lambert light and normal of point p seen as lying on the ellipsoid `blob` = (centre, radii)."""
    C, R = blob
    n = [(p[i] - C[i]) / (R[i] * R[i]) for i in range(3)]
    ln = math.sqrt(sum(x * x for x in n)) or 1
    n = [x / ln for x in n]
    return sum(n[i] * LIGHT[i] for i in range(3)), n


def world_painter(part, blob, kind):
    """Painter of a fur / cheek / straw-free volume, shaded on its blob. kind: 'body' (green, pale belly and chest by
    position, darker back), 'head', 'cheek', 'leg', 'tuft'."""
    def f(w, h, r, face):
        px = []
        for y in range(h):
            row = []
            for x in range(w):
                p = texel_world(part, face, (x + 0.5) / w, (y + 0.5) / h)
                L, n = lit(blob, p)
                ao = max(0.0, (10 - p[1]) / 10) * 1.2                  # contact shadow near the ground
                d = dith(x, y)
                if kind == "cheek":
                    row.append(q(CHEEK, 1.05 + 1.7 * L - ao + d))
                    continue
                if kind == "body" and (n[1] < -0.8 or (n[2] < -0.6 and 9 < p[1] < 25 and abs(p[0]) < 3 + (25 - p[1]) * 0.42)):
                    row.append(q(PALE, 0.4 + 1.6 * max(L, -0.2) - ao + d))   # belly and chest
                    continue
                base = {"body": 1.75, "head": 2.0, "leg": 1.4, "tuft": 2.4}[kind]
                if kind == "body" and n[1] > 0.55 and n[2] > -0.2:
                    base -= 0.35                                       # back a little darker (brief)
                row.append(q(FUR, base + 2.0 * L - ao + d))
            px.append(row)
        # fur tufts: little 1 x 2 strokes one tone off, only on the green
        if kind != "cheek":
            for _ in range(max(1, w * h // 34)):
                x, y = r.randrange(w), r.randrange(max(1, h - 1))
                if px[y][x] in FUR:
                    i = FUR.index(px[y][x])
                    c = FUR[min(4, i + 1)] if r.random() < 0.55 else FUR[max(0, i - 1)]
                    px[y][x] = c
                    if y + 1 < h and px[y + 1][x] in FUR:
                        px[y + 1][x] = c
            for _ in range(max(0, w * h // 260)):                     # rare lime glints on the lit fur
                x, y = r.randrange(w), r.randrange(h)
                if px[y][x] == FUR[4]:
                    px[y][x] = MAGIC
        else:
            for _ in range(max(1, w * h // 50)):                      # food pressing the skin: small light/dark arcs
                x, y = r.randrange(1, max(2, w - 2)), r.randrange(max(1, h - 1))
                if px[y][x] in CHEEK and CHEEK.index(px[y][x]) >= 1:
                    px[y][x] = CHEEK[min(3, CHEEK.index(px[y][x]) + 1)]
                    px[y + 1][x] = CHEEK[max(0, CHEEK.index(px[y + 1][x]) - 1)] if px[y + 1][x] in CHEEK else px[y + 1][x]
        return px
    return f


# --------------------------------------------------------------------------- painters of small pieces
def muzzle(w, h, r, face):
    """Muzzle: pale green, lighter on top; on the front the rodent split under the nose and a frown (corners pulled
    down), drawn thick enough to read from afar."""
    px = [[q(PALE, (2 if face == "up" else 1.5 - 1.2 * (y + 0.5) / h) + dith(x, y)) for x in range(w)]
          for y in range(h)]
    if face == "north":
        c = (w - 1) / 2
        split = int(h * 0.4)
        for y in range(split):
            for x in {math.floor(c), math.ceil(c)}:
                px[y][x] = PALE[0]
        for k in range(h - split):
            for x in (int(math.floor(c - k)), int(math.ceil(c + k))):
                if 0 <= x < w:
                    px[split + k][x] = MOUTH
                    if x + (1 if x > c else -1) in range(w) and k >= 2:
                        px[split + k][x + (1 if x > c else -1)] = MOUTH
    return px


def mouth(w, h, r, face):
    px = [[MOUTH] * w for _ in range(h)]
    if face == "north":
        px[0] = [FUR[0]] * w
    return px


def chin(w, h, r, face):
    return [[q(PALE, (1.6 if face == "up" else 1.0 - (y + 0.5) / h) + dith(x, y)) for x in range(w)] for y in range(h)]


def brow(w, h, r, face):
    """Brow: dense dark fur, jagged hair tips on the front top edge, a lighter hair on the upper face."""
    px = [[BROW[1] if face == "up" else BROW[0]] * w for _ in range(h)]
    if face == "north":
        for x in range(w):
            px[0][x] = BROW[1] if (x * 7 + r.randrange(3)) % 3 else FUR[1]
    elif face == "up":
        for x in range(0, w, 3):
            px[r.randrange(h)][x] = FUR[1]
    return px


def eye(mirror):
    def f(w, h, r, face):
        """Small flat eye (density 2): lime iris, dark pupil pushed to the inner side, lid thicker towards the nose
        (slanted, angry), one tiny glint."""
        if face != "north":
            return [[BROW[0]] * w for _ in range(h)]
        px = [[IRIS] * w for _ in range(h)]
        for x in range(w):
            for y in range(min(h, 1 + (3 * x) // w)):
                px[y][x] = BROW[0]
        for y in range(h):
            px[y][0] = px[y][w - 1] = BROW[0]
        px[h - 1] = [FUR[1]] * w
        for y in range(2, h - 1):
            for x in range(w - 5, w - 2):
                px[y][x] = PUPIL
        px[3][w - 4] = GLINT
        return [row[::-1] for row in px] if mirror else px
    return f


def nose(w, h, r, face):
    """Small triangular nose (density 2): dark, wide on top, pointed below, a lighter ridge."""
    px = [[FUR[1]] * w for _ in range(h)]
    if face == "north":
        for y in range(h):
            inset = int(y * (w / 2) / h)
            for x in range(inset, w - inset):
                px[y][x] = BROW[0]
        px[0] = [FUR[2]] * w
    elif face == "up":
        px = [[BROW[0]] * w for _ in range(h)]
    return px


def ear(w, h, r, face):
    """Small round ear: green outside, turquoise inside (front), corners cut by colour."""
    px = [[FUR[3] if face == "up" else FUR[2]] * w for _ in range(h)]
    if face in ("north", "south") and w >= 5 and h >= 5:
        cx, cy = (w - 1) / 2, h * 0.62
        for y in range(h):
            for x in range(w):
                dx, dy = (x - cx) / (w / 2), (y - cy) / (h / 2)
                if dx * dx + dy * dy > 1.05:
                    px[y][x] = FUR[1]                                   # rounded silhouette by colour
                elif face == "north" and ((x - cx) / (w / 2 - 1.6)) ** 2 + ((y - cy - 0.6) / (h / 2 - 1.4)) ** 2 < 1:
                    px[y][x] = CHEEK[1] if y > cy else CHEEK[2]
    return px


def tooth(w, h, r, face):
    if face != "north":
        return [[TOOTH_SHADE] * w for _ in range(h)]
    px = [[TOOTH] * w for _ in range(h)]
    px[0] = [TOOTH_SHADE] * w
    return px


def whisker(w, h, r, face):
    return [[PALE[2] if x < w - 2 else PALE[0] for x in range(w)] for _ in range(h)]


def foot(w, h, r, face):
    """Foot: dark fur, 4 pale toes on the front and the top."""
    px = [[FUR[1] if face != "down" else FUR[0]] * w for _ in range(h)]
    toes = [round((i + 0.5) * w / 4 - 0.5) for i in range(4)]
    if face == "north":
        for t in toes:
            for x in (t, t + 1):
                if x < w:
                    for y in range(h):
                        px[y][x] = PALE[1] if y < h - 1 else PALE[0]
    elif face == "up":
        for t in toes:
            for y in range(min(4, h)):
                for x in (t, t + 1):
                    if x < w:
                        px[y][x] = PALE[1] if y < 3 else FUR[2]
    return px


def straw(w, h, r, face):
    """Hay heap: stalks of 3 to 8 px drawn at random angles around a main slant (never parallel), over a dark ochre
    base; small faces show cut stalk ends (dots); a few golden grains. Lower faces darker."""
    shade = -1 if face in ("down", "south") else 0
    px = [[WHEAT[0]] * w for _ in range(h)]
    for _ in range(max(1, w * h // 6)):
        x, y = r.random() * w, r.random() * h
        ang = 0.5 + r.uniform(-0.6, 0.6) + (0 if r.random() < 0.7 else 1.4)
        ln = r.randint(3, 8)
        t = max(0, min(3, r.choice((1, 1, 2, 2, 3)) + shade + (1 if face == "up" else 0)))
        for k in range(ln):
            xx, yy = int(x + math.cos(ang) * k), int(y + math.sin(ang) * k)
            if 0 <= xx < w and 0 <= yy < h:
                px[yy][xx] = WHEAT[t]
    if min(w, h) <= 12 and face in ("east", "west", "north", "south"):
        for _ in range(max(1, w * h // 14)):                         # cut ends
            x, y = r.randrange(w), r.randrange(h)
            px[y][x] = STEM
    for _ in range(max(1, w * h // 70)):
        x, y = r.randrange(w), r.randrange(h)
        px[y][x] = WHEAT[3]
    # ragged rim: dark gaps between stalk ends, deeper on the edges so the heap does not read as a crate
    for x in range(w):
        for y in (0, h - 1):
            if r.random() < 0.55:
                px[y][x] = WHEAT[0] if r.random() < 0.7 else STEM
    for y in range(h):
        for x in (0, w - 1):
            if r.random() < 0.55:
                px[y][x] = WHEAT[0] if r.random() < 0.7 else STEM
    return px


def stalk(w, h, r, face):
    return [[STEM if (y // 2) % 2 else WHEAT[0] for x in range(w)] for y in range(h)]


def wheat_head(magic):
    def f(w, h, r, face):
        """Wheat ear: grains in a checker of light / dark, awns lighter on top; enchanted ears get lime-gold grain
        tips (colour only, no light)."""
        px = [[WHEAT[0]] * w for _ in range(h)]
        for y in range(h):
            for x in range(w):
                if (x + y) % 2 == 0:
                    px[y][x] = WHEAT[2] if y < h * 0.6 else WHEAT[1]
                elif face == "up":
                    px[y][x] = WHEAT[3]
        if magic and face != "down":
            for y in range(min(2, h)):
                for x in range(w):
                    if (x + y) % 2 == 0:
                        px[y][x] = MAGIC
        return px
    return f


def carrot(w, h, r, face):
    """Carrot: orange, light top, darker rings across the length."""
    px = [[CARROT[2] if face == "up" else CARROT[0] if face == "down" else CARROT[1]] * w for _ in range(h)]
    long_x = w >= h
    for i in range(2, w if long_x else h, 3):
        if long_x:
            for y in range(h):
                if face != "up" or y % 2:
                    px[y][i] = CARROT[0]
        else:
            px[i] = [CARROT[0]] * w
    return px


def leaves(w, h, r, face):
    return [[LEAF[1] if (x + y + r.randrange(2)) % 3 else LEAF[0] for x in range(w)] for y in range(h)]


def potato(w, h, r, face):
    """Potato: lumpy tan, darker lower half, 2 or 3 dark 'eyes'."""
    px = [[q(POTATO, (2.0 if face == "up" else 1.6 - (y + 0.5) / h) + dith(x, y, 0.8)) for x in range(w)]
          for y in range(h)]
    for _ in range(2 + (w * h > 20)):
        px[r.randrange(h)][r.randrange(w)] = POTATO[0]
    return px


SMALL = {
    "muzzle": muzzle, "mouth": mouth, "chin": chin, "brow": brow, "eye_r": (eye(False), 2),
    "eye_l": (eye(True), 2), "nose": (nose, 2), "ear": ear, "tooth": tooth, "whisker": whisker, "foot": foot,
    "straw": straw, "stalk": stalk, "wheat": wheat_head(False), "wheat_magic": wheat_head(True), "carrot": carrot,
    "leaves": leaves, "potato": potato,
}


# --------------------------------------------------------------------------- skeleton (drawing units, brief axes)
NECK = (0, 36, -18)
HEAD_R = rot(6, -5, 0)            # head raised (positive x lifts the front) and turned a touch: it watches you
on_head = attach(NECK, HEAD_R)
JAW_HINGE = (0, 27, -36)
CHEEK_CENTRES = {1: (18, 30.4, -35), -1: (-18, 31.8, -35)}     # right pouch hangs lower (brief asymmetry)
GROUPS = {
    "Root": ((0, 0, 0), None),
    "Body": ((0, 30, 0), "Root"),
    "Straw": ((0, 30, 14), "Body"),
    "Head": (NECK, "Body"),
    "Cheek_right": (on_head((13, 33, -29), (0, 0, 0))[0], "Head"),
    "Cheek_left": (on_head((-13, 34, -29), (0, 0, 0))[0], "Head"),
    "Jaw": (on_head(JAW_HINGE, (0, 0, 0))[0], "Head"),
    "Leg_front_right": ((19, 12, -17), "Root"),
    "Leg_front_left": ((-19, 12, -17), "Root"),
    "Leg_back_right": ((22, 13, 16), "Root"),
    "Leg_back_left": ((-22, 13, 16), "Root"),
}
LEGS = {
    "Leg_front_right": rot(6, 0, 8), "Leg_front_left": rot(4, 0, -6),
    "Leg_back_right": rot(-4, 0, 6), "Leg_back_left": rot(-6, 0, -8),
}
# Blobs: (centre, radii) of the ellipsoid each volume is lit on.
BLOBS = {
    "body": ((0, 30, 3), (30, 29, 33)),
    "head": (on_head((0, 39, -30), (0, 0, 0))[0], (17, 15, 14)),
    "cheek_r": (on_head(CHEEK_CENTRES[1], (0, 0, 0))[0], (13, 12, 12)),
    "cheek_l": (on_head(CHEEK_CENTRES[-1], (0, 0, 0))[0], (13, 12, 12)),
}


def along(start, r, length, axis=2):
    """Centre of a volume of given length along its own axis (0 x, 1 y, 2 z), starting at `start`, turned by r:
    food and wheat are placed by their root point whatever the angles."""
    a = [0, 0, 0]
    a[axis] = length / 2
    d = app(matrix(r), a)
    return [start[i] + d[i] for i in range(3)]


def rounded_box(add, g, name, c, size, r, mat, k=0.72, axes=(0, 1, 2)):
    """Three boxes crossed along x, y, z (only the given axes), each full size on its own axis and k times the size on
    the other two: a box with its edges cut."""
    for a in axes:
        sz = [size[i] if i == a else size[i] * k for i in range(3)]
        add(g, f"{name}_{'xyz'[a]}", c, sz, r, mat)


def parts():
    """Pieces (bone, name, centre, size, rotation, material); material = painter name, or ('lit', blob, kind)."""
    P = []

    def add(g, name, c, size, r, mat):
        P.append((g, name, list(c), size, r, mat))

    def hd(g, name, c, size, r, mat):
        cc, M = on_head(c, r)
        add(g, name, cc, size, M, mat)

    def hr(r):
        return mul(HEAD_R, matrix(r))

    body = ("lit", "body", "body")
    # ---------------------------------------------------------------- body: box sphere, a bit longer than wide
    rounded_box(add, "Body", "tronc", (0, 30, 1), (50, 44, 52), (0, 0, 0), body)  # 50 x 44 x 52 (brief 53-58)
    add("Body", "dos", (0, 53, 4), (32, 9, 36), (-4, 0, 1.5), body)                # dome: one more step on top
    add("Body", "ventre", (0, 8.5, 1), (38, 11, 40), (0, 0, 0), body)               # belly close to the ground
    add("Body", "arriere", (0, 27, 23), (34, 30, 16), (8, 0, 0), body)              # full rump, one more step back
    add("Body", "queue", (1, 15, 33.5), (4, 3.5, 3), (-20, 10, 0), ("lit", "body", "leg"))   # tiny stub

    # ---------------------------------------------------------------- head: small, melted into the ball
    head = ("lit", "head", "head")
    rounded_box(hd, "Head", "crane", (0, 39, -28), (28, 24, 22), (0, 0, 0), head, k=0.74)
    hd("Head", "meche_front", (0, 51.4, -29), (8, 3, 7), (-12, 18, 0), ("lit", "head", "tuft"))
    hd("Head", "museau", (0, 33, -41.5), (12, 10, 7), (0, 0, 0), "muzzle")
    hd("Head", "nez", (0, 37.1, -45.4), (4, 2.6, 1.6), (-10, 0, 0), "nose")
    hd("Head", "incisive_droite", (1.2, 26.5, -45.0), (2.2, 4.4, 1.2), (6, 0, 3), "tooth")
    hd("Head", "incisive_gauche", (-1.2, 26.5, -45.0), (2.2, 4.4, 1.2), (6, 0, -3), "tooth")
    # small flat eyes sunk under heavy brows whose inner ends drop towards the nose
    hd("Head", "oeil_droit", (7.6, 43.6, -39.4), (6, 3, 0.8), (0, 0, 10), "eye_r")
    hd("Head", "oeil_gauche", (-7.6, 43.8, -39.4), (6, 3, 0.8), (0, 0, -10), "eye_l")
    hd("Head", "sourcil_droit", (7.4, 48.3, -40.2), (11, 3.6, 5), (-12, -6, 24), "brow")
    hd("Head", "sourcil_gauche", (-7.4, 48.5, -40.2), (11, 3.6, 5), (-12, 6, -24), "brow")
    hd("Head", "oreille_droite", (12.5, 52, -29.5), (8, 8, 3.5), (-12, 22, -14), "ear")
    hd("Head", "oreille_gauche", (-12.5, 52.4, -30), (8, 8, 3.5), (-8, -16, 16), "ear")
    for s, side in ((1, "droite"), (-1, "gauche")):
        hd("Head", f"moustache_{side}_haut", (11.5 * s, 34.5, -44.4), (9, 0.6, 0.6), (0, -16 * s, 8 * s), "whisker")
        hd("Head", f"moustache_{side}_bas", (11 * s, 32.2, -44), (8, 0.6, 0.6), (0, -9 * s, -7 * s), "whisker")

    # ---------------------------------------------------------------- mouth (jaw bone): grumbling, half open
    hd("Jaw", "gueule", (0, 27.6, -42), (8, 2.4, 6), (0, 0, 0), "mouth")
    hd("Jaw", "menton", (0, 25.2, -40), (9, 4.5, 8), (-8, 0, 0), "chin")

    # ---------------------------------------------------------------- cheeks: box spheres, turned slightly forward
    for s, side, bone, blob in ((1, "droite", "Cheek_right", "cheek_r"), (-1, "gauche", "Cheek_left", "cheek_l")):
        cx, cy, cz = CHEEK_CENTRES[s]
        m = ("lit", blob, "cheek")
        rounded_box(hd, bone, f"joue_{side}", (cx, cy, cz), (23, 21, 20), (3, 14 * s, 4 * s), m, k=0.7)

    # ---------------------------------------------------------------- stored food
    r1 = (28, 30, 0)                                     # carrot out of the left mouth corner, 25-30 deg down
    b1 = on_head((-3.8, 27.4, -42.5), (0, 0, 0))[0]
    add("Jaw", "carotte_bouche", along(b1, hr(r1), -8), (3.4, 3.4, 8), hr(r1), "carrot")
    add("Jaw", "carotte_bouche_pointe", along(b1, hr(r1), -8 * 2 - 3.4 + 0.5), (2, 2, 3.4), hr(r1), "carrot")
    r2 = (-28, -62, 0)                                   # carrot out of the right cheek, outwards and up
    b2 = on_head((25.5, 39, -36), (0, 0, 0))[0]
    add("Cheek_right", "carotte_joue", along(b2, hr(r2), -7), (3.4, 3.4, 7), hr(r2), "carrot")
    add("Cheek_right", "carotte_joue_fanes", along(b2, hr(r2), -2 * 7 - 2.2), (4.5, 1.6, 5), hr((r2[0], r2[1], 25)),
        "leaves")
    hd("Jaw", "patate_bouche", (4.8, 27.4, -43.8), (5, 4, 4), (14, -22, 9), "potato")
    hd("Cheek_left", "patate_joue", (-21, 25.5, -45.2), (6, 5, 5), (-10, 30, -14), "potato")

    # ---------------------------------------------------------------- harvest ball: hay heaps hugging the rear
    # heaps = rolls of hay: long axis full length, section with cut corners (2 crossed boxes)
    rounded_box(add, "Straw", "paille_arriere_gauche", (-19.5, 27, 18), (25, 19, 16), (8, -38, -25), "straw",
                axes=(1, 2))
    rounded_box(add, "Straw", "paille_arriere_droite", (19.5, 25, 19), (24, 18, 15), (-6, 36, 20), "straw",
                axes=(1, 2))
    rounded_box(add, "Straw", "paille_dessus", (0, 54.5, 16), (30, 11, 18), (24, 6, -3), "straw", axes=(1, 2))
    add("Straw", "paille_flanc", (-30.5, 25, 3), (19, 13, 11), (0, -18, -10), "straw")
    add("Straw", "paille_croupe", (0, 20, 34), (30, 15, 8), (-14, 0, 5), "straw")

    # ---------------------------------------------------------------- wheat ears in the fur (stem + ear)
    ears = (   # bone, name, root, rotation (stem along +y), stem length, enchanted
        ("Straw", "epi_dos_1", (-6, 62, 9), (-28, 0, 18), 6, True),
        ("Straw", "epi_dos_2", (8, 62.5, 16), (22, 30, -24), 5, False),
        ("Straw", "epi_croupe", (-9, 27, 36), (62, 0, 14), 5, True),
        ("Body", "epi_epaule_gauche", (-21, 48, -12), (-14, 0, 38), 4, False),
        ("Body", "epi_epaule_droite", (18, 51, -11), (-24, 0, -30), 4, True),
    )
    for bone, name, root, rr, ln, magic in ears:
        add(bone, name + "_tige", along(root, rr, ln, axis=1), (0.9, ln, 0.9), rr, "stalk")
        tip = along(root, rr, ln * 2, axis=1)
        add(bone, name, along(tip, rr, 6, axis=1), (2.2, 6, 2.2), rr, "wheat_magic" if magic else "wheat")

    # ---------------------------------------------------------------- legs: short, spread, half hidden
    for s, side in ((1, "right"), (-1, "left")):
        fg, bg = f"Leg_front_{side}", f"Leg_back_{side}"
        lf = attach(GROUPS[fg][0], LEGS[fg])
        for name, c, size, rr, mat in (
                ("bras", (19 * s, 7.5, -17), (10, 11, 10), (-8, 0, 0), ("lit", None, "leg")),
                ("main", (19.5 * s, 1.75, -20), (10, 3.5, 10), (0, -8 * s, 0), "foot")):
            cc, M = lf(c, rr)
            add(fg, name, cc, size, M, mat)
        lb = attach(GROUPS[bg][0], LEGS[bg])
        for name, c, size, rr, mat in (
                ("cuisse", (22 * s, 8.5, 16), (12, 13, 14), (10, 0, 0), ("lit", None, "leg")),
                ("pied", (22.5 * s, 1.75, 12.5), (11, 3.5, 13), (0, -6 * s, 0), "foot")):
            cc, M = lb(c, rr)
            add(bg, name, cc, size, M, mat)

    low = {}
    for g, n, c, size, rr, m in P:
        if g.startswith("Leg_"):
            low[g] = min([low.get(g, 1e9)] + [p[1] for p in corners(c, size, rr)])
    return [(g, n, [c[0], c[1] - low.get(g, 0), c[2]], size, rr, m) for g, n, c, size, rr, m in P]


def materials(P):
    """Gives each lit volume its own painter (bound to its position and blob); returns the parts with material keys
    and the material table for bbkit.bake."""
    mats, out = dict(SMALL), []
    for i, (g, n, c, size, r, m) in enumerate(P):
        if isinstance(m, tuple):
            _, blob, kind = m
            b = BLOBS[blob] if blob else (c, [s * 0.75 for s in size])     # a leg is lit on its own box
            key = f"lit#{i}"
            mats[key] = world_painter((g, n, c, size, r, m), b, kind)
            m = key
        out.append((g, n, c, size, r, m))
    return out, mats


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = None
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    raw, mats = materials(parts())              # lighting computed in drawing space, before grounding (same frame)
    P, groups, dims = ground(raw, GROUPS, scale=SCALE, center=False)
    uv, atlases, sizes = bake(P, mats, {})
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    data["textures"] = data["textures"][:1]          # no glowing part: no emissive texture
    print(f"{len(P)} volumes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; texture {sizes[0]}")
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
    if prev:
        apercu(data, atlases, sizes, prev, set(), views=((35, 12), (0, 6), (90, 3), (215, 14)), size=420)
