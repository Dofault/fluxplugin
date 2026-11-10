#!/usr/bin/env python3
"""The Devourer (Le Dévoreur): giant slime boss, STATIC Blockbench model (.bbmodel), 2026-11-10.

An ancient slime that has swallowed monsters all its life: a soft, heavy, almost cubic mass of translucent green gel,
spread under its own weight, with three prisoners visible inside at different depths (a drowning zombie reaching up,
a curled skeleton deeper in, the half-dissolved skull of an old cave beast low at the back), a few loose bones, a
torn rag and some bubbles. Small, empty, dark green eyes under a gel brow; a small dark mouth low on the front.

How the gel works (the core of this model):
- The shell is a union of overlapping axis-aligned or tilted gel volumes. Every gel texel that lies inside another gel
  volume (or doubles a coplanar face) is made fully transparent, so only the OUTER surface of the union is drawn: no
  inner planes seen through the gel, no stacked-brick seams.
- Gel colour is computed from the WORLD position of each texel (height, facing, distance from the centre), so the
  shading runs continuously across neighbouring volumes.
- Alpha varies like density: clearer belly window on the front (~45 %), sides ~68 %, top/back ~78 %, cloudy bottom.
- Minecraft culls back faces, so from the front you would see the world through the back of the slime: a dense inner
  gel core ("Noyau") sits behind the prisoners as a backdrop and gives the mass its depth from every angle.

Usage: devoreur.py [output dir] [--apercu <sheet.png>]
Front faces north (-z), the monster's right side is +x, ground at y = 0, centred on x = z = 0; 16 units = 1 block.
"""
import json
import math
import os
import random
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import attach, export, hx, matrix  # noqa: E402
from golem_fonderie import app, rot  # noqa: E402

IDENT = "devoreur"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000000de70")

# --------------------------------------------------------------------------- palette (brief)
BODY = [hx(c) for c in ("#17452B", "#28643A", "#39864B", "#56A958", "#78C66B", "#A2D987")]
INNER = [hx(c) for c in ("#347F49", "#5AAE66", "#88C985", "#B1E5A0")]
EYE_RIM, EYE_IRIS, EYE_PUPIL = hx("#18351E"), hx("#274B25"), hx("#101B12")
MOUTH, MOUTH_LIP = hx("#0C1E12"), hx("#1F4A2B")
SKIN, SKIN_SHADE, SHIRT, PANTS = hx("#507D49"), hx("#345B37"), hx("#426D75"), hx("#354B69")
SHIRT_SHADE, PANTS_SHADE, ZEYE = hx("#335660"), hx("#283A52"), hx("#1B2B1A")
BONE, BONE_SHADE, CAVITY = hx("#C5BEA4"), hx("#8F8977"), hx("#34352F")
BONE_LIT = hx("#D6D0B8")

GROUPS = {   # bone name: (pivot, parent)
    "Root": ((0, 0, 0), None),
    "Gel": ((0, 0, 0), "Root"),
    "Visage": ((0, 46, -31), "Gel"),
    "Noyau": ((0, 26, 20), "Gel"),
    "Zombie": ((-12, 26, -11), "Gel"),
    "Squelette": ((13, 27, 6), "Gel"),
    "Crane": ((3, 9, 3), "Gel"),
    "Debris": ((0, 30, 0), "Gel"),
    "Bulles": ((0, 30, 0), "Gel"),
}

# --------------------------------------------------------------------------- geometry
GEL = [   # outer mass: name, centre, size, rotation. Overlaps are intended (only the union's surface is drawn).
    # footprint = a rounded square: the core's corners stay recessed behind the four bulges (pixel-art circle)
    ("coeur", (0, 28, 0), (62, 48, 62), (0, 0, 0)),                  # dominant mass, y 4..52
    ("assise_x", (0, 3, 0.5), (70, 6, 56), (0, 0, 0)),               # pressed on the ground, wider than the body,
    ("assise_z", (0, 3, 0.5), (56, 6, 71), (0, 0, 0)),               # corners cut (cross) so it is no plinth
    ("coulee_avant", (0, 10.5, -32.6), (54, 11, 5), (-6, 0, 0)),      # gel sagging forward, tilted down
    ("coulee_arriere", (1, 10, 32.5), (50, 11, 5), (5, 0, 0)),
    ("ventre", (-1, 28, -32.2), (50, 32, 4.4), (0, 0, 0)),           # bulging belly = the window
    ("dos", (2, 28, 32), (48, 34, 4), (0, 0, 0)),
    ("flanc_gauche", (-33, 25, 0), (6, 36, 50), (0, 0, 3)),           # sides widen in the lower half
    ("flanc_droit", (32.6, 24, 3), (5.2, 32, 46), (0, 0, -3)),        # smaller: asymmetry
    ("voute", (-1, 54, 0.5), (56, 6, 56), (0, 0, 1.5)),              # rounded top in three soft tiers
    ("lobe_gauche", (-9, 59, -2), (34, 5, 38), (0, 0, 3)),
    ("lobe_droit", (13, 58.5, 9), (22, 4, 26), (0, 0, -3)),
    ("arcade", (-0.5, 51.0, -31.6), (36, 3.0, 3), (0, 0, -1.5)),      # gel brow: the eyes sit sunken under it
    ("goutte_haut", (-15, 62.3, -7), (10, 3.6, 9), (0, 15, 5)),       # soft growths (no spikes, no horns)
    ("bourrelet_droit", (35.4, 37, -12), (4, 9, 10), (0, 0, -8)),
    ("bourrelet_gauche", (-37.0, 12, 15), (4, 8, 11), (0, 0, 9)),
    ("goutte_dos", (10, 57.5, 22), (12, 4, 9), (8, -12, -5)),
]


def parts():
    P = []

    def add(g, n, c, size, r, mat):
        P.append((g, n, list(c), list(size), matrix(r), mat))

    for n, c, s, r in GEL:
        add("Gel", n, c, s, r, "gel")

    # dense gel core behind the prisoners: backdrop seen through the front, depth seen through the back and top
    add("Noyau", "noyau", (0, 28, 21), (54, 46, 12), (0, 0, 0), "core")

    # face: small, empty, slightly uneven eyes under the brow; small dark mouth low on the front
    add("Visage", "oeil_gauche", (-11, 46.0, -31.15), (6.5, 5, 1), (0, 0, 4), "eye_l")
    add("Visage", "oeil_droit", (10.5, 46.6, -31.15), (5.5, 4.5, 1), (0, 0, -3), "eye_r")
    add("Visage", "bouche", (4, 13, -35.85), (9, 3, 0.8), (-6, 0, -3), "mouth")

    # zombie: drawn standing around its torso, then posed (drowning, tilted, one arm reaching up)
    z = attach((-12, 26, -11), rot(-8, 18, 14))

    def zb(name, c, size, r, mat, joint=None):
        if joint:                       # limb bent around its own joint first
            c, r = attach(joint[0], rot(*joint[1]))(c, r)
        cc, M = z([c[0] - 12, c[1] + 26, c[2] - 11], r)
        add("Zombie", name, cc, size, M, mat)
    zb("z_torse", (0, 0, 0), (7, 9, 3.6), (0, 0, 0), "z_torso")
    zb("z_tete", (0.4, 8.3, -0.2), (6.6, 6.6, 6.6), (10, -12, -18), "z_head")
    zb("z_bras_droit", (5.3, -0.4, 0), (3.4, 9, 3.4), (0, 0, 0), "z_arm", ((5.3, 3.6, 0), (-10, 0, 158)))
    zb("z_bras_gauche", (-5.3, -0.4, 0), (3.4, 9, 3.4), (0, 0, 0), "z_arm", ((-5.3, 3.6, 0), (-40, 0, -38)))
    zb("z_jambe_droite", (1.75, -8.2, 0), (3.3, 7.4, 3.3), (0, 0, 0), "z_leg", ((1.75, -4.5, 0), (28, 0, 12)))
    zb("z_jambe_gauche", (-1.75, -8.2, 0), (3.3, 7.4, 3.3), (0, 0, 0), "z_leg", ((-1.75, -4.5, 0), (-18, 0, -9)))

    # skeleton: deeper, curled as if floating, head thrown back, one arm up; ribs readable through the gel
    s = attach((13, 27, 6), rot(12, -22, -14))

    def sk(name, c, size, r, mat, joint=None):
        if joint:
            c, r = attach(joint[0], rot(*joint[1]))(c, r)
        cc, M = s([c[0] + 13, c[1] + 27, c[2] + 6], r)
        add("Squelette", name, cc, size, M, mat)
    sk("s_cage", (0, 2, 0), (7.4, 7, 3.6), (8, 0, 0), "ribs")
    sk("s_colonne", (0, -3.6, 0.7), (1.6, 4.6, 1.6), (0, 0, 0), "bone")
    sk("s_bassin", (0, -6.6, 0.2), (6, 2.4, 3), (0, 0, 0), "pelvis")
    sk("s_crane", (0.3, 8.6, -0.4), (6.2, 6.2, 6.2), (-16, 14, 10), "skull")
    sk("s_bras_droit", (4.7, 0.6, 0), (1.8, 8.5, 1.8), (0, 0, 0), "bone", ((4.7, 4.8, 0), (-25, 0, 138)))
    sk("s_bras_gauche", (-4.7, 0.6, 0), (1.8, 8.5, 1.8), (0, 0, 0), "bone", ((-4.7, 4.8, 0), (35, 0, -22)))
    sk("s_jambe_droite", (1.8, -11.3, 0.2), (2, 7.6, 2), (0, 0, 0), "bone", ((1.8, -7.5, 0.2), (-48, 0, 10)))
    sk("s_jambe_gauche", (-1.8, -11.3, 0.2), (2, 7.6, 2), (0, 0, 0), "bone", ((-1.8, -7.5, 0.2), (-16, 0, -14)))

    # skull of an old cave beast, low and further back, half sunk and tilted
    k = attach((3, 9, 3), rot(-12, 28, -9))

    def cr(name, c, size, r, mat):
        cc, M = k([c[0] + 3, c[1] + 9, c[2] + 3], r)
        add("Crane", name, cc, size, M, mat)
    cr("c_crane", (0, 2, 0), (14, 9, 12), (0, 0, 0), "beast_skull")
    cr("c_machoire", (0, -3.9, -1.2), (12, 3, 9.5), (14, 0, 0), "beast_jaw")
    cr("c_croc_droit", (3.6, -3.3, -5.2), (2.2, 3.4, 2.2), (6, 0, 6), "fang")
    cr("c_croc_gauche", (-3.4, -3.1, -5.0), (2.2, 3.0, 2.2), (4, 0, -8), "fang")

    # a few loose remains (3), never in front of the eyes or the prisoners
    add("Debris", "os_femur", (22, 41, -4), (1.7, 7.5, 1.7), (20, 30, 62), "loose_bone")
    add("Debris", "os_petit", (-21, 9.5, -17), (1.4, 5, 1.4), (70, 10, 24), "loose_bone")
    add("Debris", "lambeau", (-25, 38, -2), (5, 0.6, 4.2), (25, 30, -18), "rag")

    # bubbles (6), discreet
    for i, (c, d) in enumerate((((24, 47, -20), 2.2), ((-27, 23, -23), 1.6), ((4, 39, -24), 1.6),
                                ((27, 14, -13), 2.4), ((-3, 51, 9), 1.6), ((-24, 49, 15), 2.0))):
        add("Bulles", f"bulle_{i + 1}", c, (d, d, d), (0, 0, 0), "bubble")
    return P


# --------------------------------------------------------------------------- face geometry
FACES = ("north", "east", "south", "west", "up", "down")
NORMAL = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0),
          "up": (0, 1, 0), "down": (0, -1, 0)}


def face_dims(size, d):
    dx, dy, dz = size
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy), "up": (dx, dz), "down": (dx, dz)}[d]


def face_local(size, d, s, t):
    """Local point on face d for texture coordinates s, t in [0, 1] (same orientation as Blockbench / the preview)."""
    hx_, hy, hz = (v / 2 for v in size)
    return {"north": (hx_ - 2 * hx_ * s, hy - 2 * hy * t, -hz), "south": (-hx_ + 2 * hx_ * s, hy - 2 * hy * t, hz),
            "east": (hx_, hy - 2 * hy * t, hz - 2 * hz * s), "west": (-hx_, hy - 2 * hy * t, -hz + 2 * hz * s),
            "up": (-hx_ + 2 * hx_ * s, hy, -hz + 2 * hz * t), "down": (-hx_ + 2 * hx_ * s, -hy, hz - 2 * hz * t)}[d]


def to_world(piece, local):
    g, n, c, size, M, m = piece
    p = app(M, local)
    return [p[i] + c[i] for i in range(3)]


def inside(piece, q, eps=1e-4):
    g, n, c, size, M, m = piece
    d = [q[i] - c[i] for i in range(3)]
    loc = [sum(M[k][i] * d[k] for k in range(3)) for i in range(3)]       # M transposed
    return all(abs(loc[i]) < size[i] / 2 - eps for i in range(3))


def facing(nw):
    ax = max(range(3), key=lambda i: abs(nw[i]))
    return (("west", "east"), ("down", "up"), ("north", "south"))[ax][nw[ax] > 0]


# --------------------------------------------------------------------------- gel material (world space)
EYES = [((-11, 46.0), (5.6, 4.4)), ((10.5, 46.6), (4.9, 4.0))]
STROKES = {   # pixel highlights (pale green), per facing: 2D segments in the face plane + where they may appear
    "north": ([((-26, 54.5), (-20, 54.5)), ((-26, 54.5), (-26, 51.5)), ((-29.5, 37), (-29.5, 33)),
               ((-20, 39.5), (-16, 39.5))], lambda p: p[2] < -29),
    "up": ([((-22, -12), (-13, -15)), ((-24, -8), (-24, -6)), ((10, 0), (14, -1))], lambda p: p[1] > 50),
    "west": ([((-10, 33), (-10, 27)), ((-12, 35), (-12, 35))], lambda p: p[0] < -32),
}


def seg_dist(p, a, b):
    ax, ay = a
    bx, by = b
    vx, vy = bx - ax, by - ay
    L = vx * vx + vy * vy
    t = 0 if L == 0 else max(0, min(1, ((p[0] - ax) * vx + (p[1] - ay) * vy) / L))
    return math.hypot(p[0] - ax - t * vx, p[1] - ay - t * vy)


def plane2d(p, f):
    return {"north": (p[0], p[1]), "south": (p[0], p[1]), "east": (p[2], p[1]), "west": (p[2], p[1]),
            "up": (p[0], p[2]), "down": (p[0], p[2])}[f]


def pick(pal, v, odd):
    """Palette index from a continuous value: flat bands, a narrow checker-dithered strip at each transition."""
    lo = math.floor(v)
    f = v - lo
    i = lo + (1 if (f > 0.62 or (f > 0.38 and odd)) else 0)
    return pal[max(0, min(len(pal) - 1, i))]


def window(p):
    """0 outside the belly window, up to 1 in its centre."""
    ex, ey = abs(p[0] + 1) / 25, abs(p[1] - 27) / 16
    return max(0.0, 1 - max(ex, ey) ** 2) if ex < 1 and ey < 1 else 0.0


def gel_texel(p, f, r):
    x, y, z = p
    r = (math.floor(x + 1e-3) + math.floor(y + 1e-3) + math.floor(z + 1e-3)) % 2 == 1   # checker parity
    w = window(p) if f == "north" else 0.0
    if w > 0.3:                                 # clearer, less saturated belly: the prisoners show through
        col = pick(INNER, 0.8 + 1.0 * (w - 0.3) / 0.7, r)
        a = 196 - 100 * w
    elif w > 0:                                 # soft rim: body colour, already a little clearer
        col = pick(BODY, 2.35 + (y - 30) / 38, r)
        a = 196 - 100 * w
    else:
        v = 2.35 + (y - 30) / 38
        v += {"up": 0.8, "down": -2.2, "south": -0.6, "east": -0.35, "west": 0.15, "north": 0.0}[f]
        rh = max(abs(x), abs(z)) / 36
        if rh > 0.84 and f != "up":
            v -= (rh - 0.84) * 4.5               # darker outer edges
        col = pick(BODY, v, r)
        a = {"up": 198, "down": 245, "south": 205, "north": 196, "east": 178, "west": 178}[f]
        if f in ("east", "west") and 12 < y < 46 and abs(z) < 26:
            a = 172
    if y < 9 and f != "up":                     # cloudy bottom
        col = pick(BODY, 1.2 + y / 9, r)
        a = max(a, 228)
    if f == "north":                            # sunken, darker sockets around the eyes
        for (ex, ey), (rx, ry) in EYES:
            d = ((x - ex) / rx) ** 2 + ((y - ey) / ry) ** 2
            if d < 1:
                col = BODY[0] if d < 0.55 else BODY[1]
                a = 240
    if f in STROKES:
        segs, where = STROKES[f]
        q = plane2d(p, f)
        if where(p) and any(seg_dist(q, a_, b_) < 0.75 for a_, b_ in segs):
            col = BODY[5]
            a = max(a, 215)
    return tuple(col) + (int(a),)


# --------------------------------------------------------------------------- other materials (face-local pixel art)
def flat(c, a=255):
    return [tuple(c) + (a,)]


def solid_painter(fn):
    """fn(w, h, r, face) -> rows of RGB (or RGBA) tuples."""
    def p(w, h, r, face, piece):
        out = fn(w, h, r, face)
        return [[tuple(px) + ((255,) if len(px) == 3 else ()) for px in row] for row in out]
    return p


def speckle(base, alt, n):
    def f(w, h, r, face):
        px = [[base] * w for _ in range(h)]
        for _ in range(max(1, w * h // n)):
            px[r.randrange(h)][r.randrange(w)] = alt
        return px
    return f


def core(w, h, r, face):
    px = []
    for j in range(h):
        row = []
        for i in range(w):
            v = 0.4 + 0.9 * (1 - j / max(1, h - 1)) if face != "down" else 0.2
            edge = min(i + 0.5, w - i - 0.5, j + 0.5, h - j - 0.5)
            a = 230 if edge > 4 else int(110 + 30 * edge)
            row.append(tuple(pick([INNER[0], INNER[1], INNER[2]], v, (i + j) % 2)) + (a,))
        px.append(row)
    return px


def eye(w, h, r, face):
    if face != "north":
        return [[EYE_RIM] * w for _ in range(h)]
    px = [[EYE_RIM if i in (0, w - 1) or j in (0, h - 1) else EYE_IRIS for i in range(w)] for j in range(h)]
    cx, cy = w // 2, h // 2                      # empty stare, pupil a little low: watching its prey below
    for j in (cy, min(h - 2, cy + 1)):
        for i in (cx - 1, cx):
            if 0 < i < w - 1:
                px[j][i] = EYE_PUPIL
    return px


def mouth(w, h, r, face):
    if face != "north":
        return [[MOUTH_LIP + (255,)] * w for _ in range(h)]
    px = [[MOUTH + (255,) for _ in range(w)] for _ in range(h)]
    for (i, j) in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1), (w - 2, h - 1), (2, 0)):
        if 0 <= i < w and 0 <= j < h:
            px[j][i] = (0, 0, 0, 0)               # uneven outline
    px[0][w // 2] = MOUTH_LIP + (255,)
    return px


def z_head(w, h, r, face):
    px = speckle(SKIN, SKIN_SHADE, 7)(w, h, r, face)
    if face == "north" and w >= 6 and h >= 6:
        for i in (1, 2, w - 3, w - 2):          # hollow eyes, zombie style
            px[3][i] = ZEYE
        px[4][w // 2 - 1] = SKIN_SHADE
        px[4][w // 2] = SKIN_SHADE
        for i in range(2, w - 2):               # slack mouth
            px[h - 2][i] = SKIN_SHADE
    if face == "up":
        px = [[SKIN_SHADE if (i + j) % 3 == 0 else c for i, c in enumerate(row)] for j, row in enumerate(px)]
    return px


def z_torso(w, h, r, face):
    px = speckle(SHIRT, SHIRT_SHADE, 6)(w, h, r, face)
    if face in ("north", "south"):
        px[0] = [SKIN if w // 2 - 2 <= i <= w // 2 + 1 else c for i, c in enumerate(px[0])]   # collar
        for j in range(h - 3, h):                                                           # torn hem
            px[j] = [SKIN_SHADE if (i * 7 + j) % 4 == 0 else c for i, c in enumerate(px[j])]
    return px


def z_arm(w, h, r, face):
    return [[SHIRT if j < 3 else (SKIN_SHADE if (r.random() < 0.15 or face == "down") else SKIN)
             for _ in range(w)] for j in range(h)]


def z_leg(w, h, r, face):
    return [[PANTS_SHADE if j >= h - 2 or r.random() < 0.12 else PANTS for _ in range(w)] for j in range(h)]


def bone(w, h, r, face):
    return [[BONE_LIT if (j == 0 or j == h - 1) and face not in ("up", "down")
             else (BONE_SHADE if r.random() < 0.12 or face == "down" else BONE) for _ in range(w)] for j in range(h)]


def ribs(w, h, r, face):
    """Rib cage: bone bars on dark gaps, sternum in the middle of the front, readable at distance."""
    if face in ("up", "down"):
        return [[BONE_SHADE if face == "down" else BONE] * w for _ in range(h)]
    px = []
    for j in range(h):
        bar = j % 2 == 0
        row = []
        for i in range(w):
            edge = face in ("north", "south") and i in (0, w - 1)
            sternum = face == "north" and i in (w // 2,) and j < h - 1
            spine = face == "south" and i == w // 2
            row.append(BONE if (bar or sternum or spine) else (BONE_SHADE if edge else CAVITY))
        px.append(row)
    return px


def pelvis(w, h, r, face):
    px = [[BONE] * w for _ in range(h)]
    if face == "north" and w >= 4:
        px[h - 1][w // 2] = CAVITY
        px[h - 1][w // 2 - 1] = CAVITY
    px[h - 1] = [BONE_SHADE if c == BONE else c for c in px[h - 1]]
    return px


def skull(w, h, r, face):
    px = [[BONE_SHADE if face == "down" else BONE] * w for _ in range(h)]
    if face == "north":
        for j in (2, 3):
            for i in (1, 2, w - 3, w - 2):
                px[j][i] = CAVITY
        px[4][w // 2] = CAVITY
        for i in range(1, w - 1, 2):
            px[h - 1][i] = BONE_SHADE
    return px


def beast_skull(w, h, r, face):
    px = [[BONE_SHADE if (face == "down" or r.random() < 0.1) else BONE] * 1 for _ in range(h) for _ in range(1)]
    px = [[BONE_SHADE if (face == "down" or r.random() < 0.1) else BONE for _ in range(w)] for _ in range(h)]
    if face == "north":
        for j in range(2, 6):                   # two big dark sockets
            for i in list(range(2, 5)) + list(range(w - 5, w - 2)):
                px[j][i] = CAVITY
        px[1] = [BONE_SHADE if 1 <= i <= w - 2 else c for i, c in enumerate(px[1])]   # heavy brow
        for j in (6, 7):                        # nasal cavity
            px[j][w // 2 - 1] = CAVITY
            px[j][w // 2] = CAVITY
        for i in range(1, w - 1):               # upper teeth row
            px[h - 1][i] = BONE_LIT if i % 3 else CAVITY
    if face == "up":                            # old crack
        for k in range(min(w, h) - 2):
            px[1 + k][2 + k] = BONE_SHADE
    return px


def beast_jaw(w, h, r, face):
    px = [[BONE_SHADE if face == "down" else BONE for _ in range(w)] for _ in range(h)]
    if face == "north":
        for i in range(1, w - 1):
            px[0][i] = BONE_LIT if i % 3 else CAVITY
    return px


def rag(w, h, r, face):
    return [[(0, 0, 0, 0) if (i + j * 2) % 5 == 0 and (i in (0, w - 1) or j in (0, h - 1))
             else tuple(SHIRT if r.random() > 0.2 else SHIRT_SHADE) + (255,) for i in range(w)] for j in range(h)]


def bubble(w, h, r, face):
    px = [[tuple(INNER[2]) + (150,) for _ in range(w)] for _ in range(h)]
    if face in ("north", "up", "west"):
        px[0][0] = tuple(INNER[3]) + (210,)
    return px


MATS = {"core": core, "eye_l": eye, "eye_r": eye, "z_head": z_head, "z_torso": z_torso, "z_arm": z_arm,
        "z_leg": z_leg, "bone": bone, "loose_bone": bone, "ribs": ribs, "pelvis": pelvis, "skull": skull,
        "beast_skull": beast_skull, "beast_jaw": beast_jaw, "fang": bone}
RGBA_MATS = {"mouth": mouth, "rag": rag, "bubble": bubble}


# --------------------------------------------------------------------------- baking (single atlas, 1 texel per unit)
def gel_face(P, i, d, w, h, fw, fh, r):
    piece = P[i]
    M = piece[4]
    nw = app(M, NORMAL[d])
    f = facing(nw)
    gels = [(j, q) for j, q in enumerate(P) if q[5] == "gel" and j != i]
    out = []
    for jy in range(h):
        row = []
        for ix in range(w):
            s, t = min(1.0, (ix + 0.5) / fw), min(1.0, (jy + 0.5) / fh)
            p = to_world(piece, face_local(piece[3], d, s, t))
            q_out = [p[k] + nw[k] * 0.05 for k in range(3)]
            q_in = [p[k] - nw[k] * 0.05 for k in range(3)]
            hidden = any(inside(q, q_out) for _, q in gels) or any(inside(q, q_in) for j, q in gels if j < i)
            row.append((0, 0, 0, 0) if hidden else gel_texel(p, f, r))
        out.append(row)
    return out


def bake(P, seed=6604):
    reqs = []
    for i, piece in enumerate(P):
        for d in FACES:
            fw, fh = face_dims(piece[3], d)
            reqs.append((i, d, fw, fh))
    reqs.sort(key=lambda q: (-math.ceil(q[3]), -math.ceil(q[2])))
    for size in (64, 128, 256, 512, 1024):
        x = y = shelf = 0
        place, ok = [], True
        for q in reqs:
            w, h = max(1, math.ceil(q[2] - 1e-6)), max(1, math.ceil(q[3] - 1e-6))
            if x + w > size:
                x, y, shelf = 0, y + shelf, 0
            if y + h > size:
                ok = False
                break
            place.append((x, y, w, h))
            x, shelf = x + w, max(shelf, h)
        if ok:
            break
    atlas = [[(0, 0, 0, 0)] * size for _ in range(size)]
    uv = {}
    rnd = random.Random(seed)
    for (i, d, fw, fh), (x, y, w, h) in zip(reqs, place):
        r = random.Random(rnd.random())
        m = P[i][5]
        if m == "gel":
            img = gel_face(P, i, d, w, h, fw, fh, r)
        elif m in RGBA_MATS:
            img = RGBA_MATS[m](w, h, r, d)
        else:
            img = [[tuple(c) + ((255,) if len(c) == 3 else ()) for c in row] for row in MATS[m](w, h, r, d)]
        for j in range(h):
            for k in range(w):
                atlas[y + j][x + k] = img[j][k]
        uv[(i, d)] = {"uv": [x, y, round(x + fw, 3), round(y + fh, 3)], "texture": 0}
    return uv, atlas, size


# --------------------------------------------------------------------------- preview with real alpha blending
SHADE = {"up": 1.0, "down": 0.55, "north": 0.85, "south": 0.7, "east": 0.78, "west": 0.92}


def render(elements, px, yaw, pitch, size, bg):
    """Orthographic ray caster: every front-facing hit along the ray, blended front to back (back faces culled,
    like Minecraft); texels with alpha 0 are holes."""
    import apercu as A
    yw, pt = math.radians(yaw), math.radians(pitch)
    d = [-math.sin(yw) * math.cos(pt), -math.sin(pt), math.cos(yw) * math.cos(pt)]
    right = [-math.cos(yw), 0, -math.sin(yw)]
    up = [d[1] * right[2] - d[2] * right[1], d[2] * right[0] - d[0] * right[2], d[0] * right[1] - d[1] * right[0]]
    if up[1] < 0:
        up = [-c for c in up]
    cubes = []
    for e in elements:
        M = A.rot_matrix(*(e.get("rotation") or [0, 0, 0]))
        o = e["origin"]
        cs = []
        for cx in (e["from"][0], e["to"][0]):
            for cy in (e["from"][1], e["to"][1]):
                for cz in (e["from"][2], e["to"][2]):
                    p = A.apply(M, [cx - o[0], cy - o[1], cz - o[2]])
                    p = [p[i] + o[i] for i in range(3)]
                    cs.append((sum(p[i] * right[i] for i in range(3)), sum(p[i] * up[i] for i in range(3))))
        cubes.append((e, A.transpose(M), o, A.apply(A.transpose(M), d), cs))
    pts = [c for cube in cubes for c in cube[4]]
    minu, maxu = min(p[0] for p in pts), max(p[0] for p in pts)
    minv, maxv = min(p[1] for p in pts), max(p[1] for p in pts)
    scale = (size - 24) / max(maxu - minu, maxv - minv, 1)
    cu, cv = (minu + maxu) / 2, (minv + maxv) / 2
    boxes = []
    for cube in cubes:
        us, vs = [c[0] for c in cube[4]], [c[1] for c in cube[4]]
        boxes.append((cube, (min(us) - cu) * scale + size / 2 - 1, (max(us) - cu) * scale + size / 2 + 1,
                      (cv - max(vs)) * scale + size / 2 - 1, (cv - min(vs)) * scale + size / 2 + 1))
    out = [[bg] * size for _ in range(size)]
    far = 400.0
    for py in range(size):
        row = [b for b in boxes if b[3] <= py <= b[4]]
        for qx in range(size):
            u = cu + (qx + 0.5 - size / 2) / scale
            v = cv - (py + 0.5 - size / 2) / scale
            origin = [right[i] * u + up[i] * v - d[i] * far for i in range(3)]
            hits = []
            for (e, Mt, o, rd, _), x_lo, x_hi, _, _ in row:
                if not x_lo <= qx <= x_hi:
                    continue
                ro = A.apply(Mt, [origin[i] - o[i] for i in range(3)])
                ro = [ro[i] + o[i] for i in range(3)]
                t0, t1, face, ok = -1e9, 1e9, None, True
                for ax, (neg, pos) in enumerate((("west", "east"), ("down", "up"), ("north", "south"))):
                    lo, hi = e["from"][ax], e["to"][ax]
                    if abs(rd[ax]) < 1e-9:
                        if ro[ax] < lo or ro[ax] > hi:
                            ok = False
                            break
                        continue
                    ta, tb = (lo - ro[ax]) / rd[ax], (hi - ro[ax]) / rd[ax]
                    fa = neg if rd[ax] > 0 else pos
                    if ta > tb:
                        ta, tb = tb, ta
                    if ta > t0:
                        t0, face = ta, fa
                    t1 = min(t1, tb)
                    if t0 > t1:
                        ok = False
                        break
                if not ok or face is None:
                    continue
                x, y, z = (ro[i] + rd[i] * t0 for i in range(3))
                (x0, y0, z0), (x1, y1, z1) = e["from"], e["to"]

                def fr(a, lo, hi):
                    return 0.5 if hi - lo < 1e-6 else min(0.999, max(0.0, (a - lo) / (hi - lo)))
                s, t = {"north": (1 - fr(x, x0, x1), 1 - fr(y, y0, y1)), "south": (fr(x, x0, x1), 1 - fr(y, y0, y1)),
                        "east": (1 - fr(z, z0, z1), 1 - fr(y, y0, y1)), "west": (fr(z, z0, z1), 1 - fr(y, y0, y1)),
                        "up": (fr(x, x0, x1), fr(z, z0, z1)), "down": (fr(x, x0, x1), 1 - fr(z, z0, z1))}[face]
                u0, v0, u1, v1 = e["faces"][face]["uv"]
                c = px[int(v0 + min(0.999, t) * (v1 - v0))][int(u0 + min(0.999, s) * (u1 - u0))]
                if c[3]:
                    hits.append((t0, c, SHADE[face]))
            if not hits:
                continue
            hits.sort(key=lambda h_: h_[0])
            col, acc = [0.0, 0.0, 0.0], 0.0
            for _, c, k in hits:
                a = c[3] / 255 * (1 - acc)
                for i in range(3):
                    col[i] += a * min(255, c[i] * k)
                acc += a
                if acc > 0.995:
                    break
            out[py][qx] = tuple(int(col[i] + (1 - acc) * bg[i]) for i in range(3)) + (255,)
    return out


def apercu(data, atlas, path, views, size):
    import paths  # noqa: F401  (scripts/influcard on the path)
    from png_util import write
    neutral, sky = (58, 58, 62, 255), (150, 175, 205, 255)   # the last view on a bright sky: gel must read on both
    imgs = [render(data["elements"], atlas, yaw, pitch, size, sky if k == len(views) - 1 else neutral)
            for k, (yaw, pitch) in enumerate(views)]
    write(path, size * len(imgs), size, [sum((im[y] for im in imgs), []) for y in range(size)])


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = None
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    P = parts()
    uv, atlas, size = bake(P)
    P7 = [(g, n, c, s, M, m, s) for g, n, c, s, M, m in P]
    data = export(IDENT, NS, P7, GROUPS, uv, {0: atlas, 1: [[(0, 0, 0, 0)] * 16 for _ in range(16)]}, {0: size, 1: 16})
    data["textures"] = data["textures"][:1]          # no glowing part: no emissive texture
    pts = []
    for g, n, c, s, M, m in P:
        for sx in (-0.5, 0.5):
            for sy in (-0.5, 0.5):
                for sz in (-0.5, 0.5):
                    pts.append(to_world((g, n, c, s, M, m), (sx * s[0], sy * s[1], sz * s[2])))
    dims = [(max(p[i] for p in pts) - min(p[i] for p in pts)) / 16 for i in range(3)]
    print(f"{len(P)} volumes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; "
          f"bas {min(p[1] for p in pts):.2f} ; texture {size}")
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
    if prev:
        apercu(data, atlas, prev, ((30, 12), (0, 5), (90, 3), (210, 14), (30, 12)), 300)
