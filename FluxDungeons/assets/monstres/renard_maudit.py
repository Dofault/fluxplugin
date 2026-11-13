#!/usr/bin/env python3
"""Cursed Sylvan Fox (Renard sylvestre maudit): forest dungeon adversary, STATIC Blockbench model (2026-11-11).

An old fox the forest grew into: copper fur, a stalking quadruped pose (body low, shoulders above the hips, head
forward), a huge tail rising in an angular curve and ending in a bunch of dead leaves, a mane of stacked leaves along
the back, moss on the shoulders and on two joints. The face carries the menace: small slanted amber eyes under heavy
brows meeting in a V, a dark tear line, a slightly open mouth with two small ivory fangs.

Brief constraint: exactly 42 boxes, split as trunk 8, head 8, legs 8, tail 8, ears 2, vegetation 8 (asserted).
Every detail of the face is painted, never modelled. Ears and leaves get their angular outline from cut-out
(transparent) pixels, not from extra boxes.

Brief axes -> model axes: brief X (width) = x, brief Y (length, front -Y) = z (front -z), brief Z (height) = y.
One brief unit = 8 model units (half a block): the fox is about 4 blocks long and 2.4 blocks high.
Painted at 2 texels per model unit (32 per block) everywhere, so the density is the same on every part.

Usage: renard_maudit.py [output folder] [--apercu <sheet.png>] [--visage <face.png>]
Front towards north (-z), the fox's right towards +x, 16 units = 1 block.
"""
import json
import math
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, export, flat_elements, combined, ground, hx  # noqa: E402
from golem_fonderie import rot  # noqa: E402

IDENT = "renard_maudit"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000f0e5701")
D = 2  # texels per model unit, every material

# ---------------------------------------------------------------- palette (brief, no other tone except where noted)
FUR, FUR2, FUR_L, FUR_D = hx("#A85F38"), hx("#87482F"), hx("#BF8050"), hx("#59372C")
BEIGE, MUZ_D = hx("#C49A70"), hx("#49332D")
OLIVE, MOSS, SAGE, OCHRE, PBROWN, DMOSS = (hx(c) for c in ("#718044", "#526B3E", "#93A16A", "#B77A42", "#66503A",
                                                           "#405B3A"))
EYE = hx("#D4A53E")
# technical additions the brief asks for without giving a code: darker iris rim, near black pupil, tiny glint, ivory
EYE_RIM, PUPIL, GLINT, IVORY = hx("#9C7430"), hx("#2A1E1A"), hx("#E6C77A"), hx("#E0D0AC")
CLEAR = (0, 0, 0, 0)

FUR_RAMP = [FUR_D, FUR2, FUR, FUR_L]
# light from above and slightly from the front left (the fox's left is -x = west face)
FACE_OFF = {"up": 0.35, "north": 0.05, "west": 0.12, "east": -0.08, "south": -0.2, "down": -1.1}
SWAP = {"east": "west", "west": "east"}


def clamp(i, n):
    return max(0, min(n - 1, i))


def mirrored(p):
    """Painter of the left twin of a right side box: same picture flipped, east and west swapped."""
    def f(w, h, r, face):
        return [row[::-1] for row in p(w, h, r, SWAP.get(face, face))]
    return f


def per_face(default, **faces):
    def f(w, h, r, face):
        return faces.get(face, default)(w, h, r, face)
    return f


# ---------------------------------------------------------------- fur
def fur_grid(w, h, r, face, base=2.0, low=0.6, crest=0.25, streak=0.035):
    """Flat ramp tone chosen by face, little noise; lower rows of the sides sink a step into shadow, upper rows catch
    the light; short strokes of the secondary russet run along the hair (image columns), a few light ones on top."""
    off = FACE_OFF[face]
    side = face not in ("up", "down")
    g = []
    for y in range(h):
        k = y / max(1, h - 1)
        row = []
        for x in range(w):
            v = base + off
            if side:
                if k > 0.72:
                    v -= low * (k - 0.72) / 0.28
                if k < 0.14:
                    v += crest
            v += r.uniform(-0.2, 0.2)
            row.append(FUR_RAMP[clamp(int(math.floor(v + 0.5)), 4)])
        g.append(row)
    if face != "down":
        for _ in range(int(w * h * streak)):
            x, y = r.randrange(w), r.randrange(h)
            c = FUR2 if r.random() < (0.45 if face == "up" else 0.75) else FUR_L
            for j in range(r.choice((2, 2, 3))):
                if y + j < h:
                    g[y + j][x] = c
    return g


def fur(base=2.0, **kw):
    return lambda w, h, r, face: fur_grid(w, h, r, face, base, **kw)


# ---------------------------------------------------------------- leaves and moss
LEAF = {"olive": (OLIVE, MOSS, SAGE), "moss": (MOSS, DMOSS, OLIVE), "ochre": (OCHRE, PBROWN, BEIGE),
        "brown": (PBROWN, DMOSS, OCHRE), "sage": (SAGE, OLIVE, SAGE)}


def leaf_grid(w, h, r, mix, gap=DMOSS):
    """Stacked angular leaves (shingles): each leaf a pixel rhombus pointing down the image (backwards on a back,
    towards the tip on a tail), a lighter midrib, a darker lower right edge. Lower rows drawn last overlap upper
    ones, as leaves lying on each other."""
    kinds = [k for k, n in mix for _ in range(n)]
    g = [[gap] * w for _ in range(h)]
    up_, down_, a = 2, 4, 2
    for i, cy in enumerate(range(-2, h + 4, 3)):
        for cx in range(-2 + (i % 2) * 2, w + 3, 4):
            cx2, cy2 = cx + r.choice((-1, 0, 0, 1)), cy + r.choice((0, 0, 1))
            body, shade, vein = LEAF[r.choice(kinds)]
            for dy in range(-up_, down_ + 1):
                span = (dy + up_ + 1) / (up_ + 1) if dy < 0 else (down_ - dy + 1) / (down_ + 1)
                half = int(math.floor(a * min(1.0, span) + 0.5))
                for dx in range(-half, half + 1):
                    x, y = cx2 + dx, cy2 + dy
                    if not (0 <= x < w and 0 <= y < h):
                        continue
                    c = body
                    if dx == 0 and -up_ < dy < down_ - 1:
                        c = vein
                    elif (dx > 0 and dx == half) or dy == down_:
                        c = shade
                    g[y][x] = c
    return g


def carve(g, edges, pattern=(0, 1, 2, 1), phase=0):
    """Saw-tooth cut-out along the given image edges (top, bottom, left, right): leaf tips and fur tufts."""
    h, w = len(g), len(g[0])
    n = len(pattern)
    for x in range(w):
        d = pattern[(x + phase) % n]
        for j in range(d):
            if "top" in edges and j < h:
                g[j][x] = CLEAR
            if "bottom" in edges and h - 1 - j >= 0:
                g[h - 1 - j][x] = CLEAR
    for y in range(h):
        d = pattern[(y + phase) % n]
        for j in range(d):
            if "left" in edges and j < w:
                g[y][j] = CLEAR
            if "right" in edges and w - 1 - j >= 0:
                g[y][w - 1 - j] = CLEAR
    return g


def leaves(mix, sides="top", up="all", down=None, pattern=(0, 1, 2, 1), gap=DMOSS):
    """Leafy box. `sides`: edges cut on the four side faces; `up` / `down`: edges cut on those faces."""
    ALL = ("top", "bottom", "left", "right")

    def f(w, h, r, face):
        g = leaf_grid(w, h, r, mix, gap)
        if face in ("up", "down"):
            e = up if face == "up" else down
        else:
            e = sides
        if e:
            carve(g, ALL if e == "all" else (e,) if isinstance(e, str) else e, pattern, r.randrange(4))
        return g
    return f


def moss(w, h, r, face):
    """Moss: deep green, clumps of moss green, rare olive points, ragged outline (cut on every edge, shallow)."""
    g = []
    cells = {}
    for y in range(h):
        row = []
        for x in range(w):
            v = cells.setdefault((x // 2, y // 2), r.random())
            row.append(MOSS if v < 0.38 else (OLIVE if v > 0.93 else DMOSS))
        g.append(row)
    if face == "up":
        for _ in range(max(1, w * h // 14)):
            g[r.randrange(h)][r.randrange(w)] = SAGE
    return carve(g, ("top", "bottom", "left", "right"), (0, 1, 0, 0, 1, 1), r.randrange(6))


def creep(g, leaf, weight, r, cell=3):
    """Leaves taking over fur in clumps: a pixel turns leaf where its clump's random value is below weight(x, y)."""
    cells = {}
    for y in range(len(g)):
        for x in range(len(g[0])):
            if cells.setdefault((x // cell, y // cell), r.random()) < weight(x, y):
                g[y][x] = leaf[y][x]
    return g


# ---------------------------------------------------------------- trunk and tail
def trunk(base=2.0):
    """Right half of the trunk: fur, moss creeping out from under the mane along the spine (inner edge of the up
    face, u = 0 on the right half)."""
    def f(w, h, r, face):
        g = fur_grid(w, h, r, face, base)
        if face == "up":
            leaf = leaf_grid(w, h, r, (("moss", 3), ("olive", 2)))
            creep(g, leaf, lambda x, y: 0.85 if x < w * 0.4 else max(0.0, 0.85 - (x - w * 0.4) / (w * 0.3)), r)
        return g
    return f


def tail(leafy):
    """Tail segment: fur, leaves taking over towards the tip (local +z) by the given share."""
    def tipness(face, x, y, w, h):
        return {"south": 1.0, "north": 0.05, "east": 1 - x / max(1, w - 1), "west": x / max(1, w - 1),
                "up": y / max(1, h - 1), "down": 1 - y / max(1, h - 1)}[face]

    def f(w, h, r, face):
        g = fur_grid(w, h, r, face, 2.0, low=0.4, streak=0.09)
        if leafy:
            leaf = leaf_grid(w, h, r, (("ochre", 3), ("olive", 3), ("moss", 2), ("brown", 1)))
            creep(g, leaf, lambda x, y: leafy * (0.25 + 0.95 * tipness(face, x, y, w, h)), r)
        return g
    return f


# ---------------------------------------------------------------- legs
def leg_low(front):
    """Lower leg and paw: russet fading into dark brown socks, toes marked on the front, dark sole."""
    def f(w, h, r, face):
        if face == "down":
            return [[MUZ_D] * w for _ in range(h)]
        g = fur_grid(w, h, r, face, 1.6, low=0.0, streak=0.04)
        if face == "up":
            return g
        start = int(h * 0.45)
        for y in range(start, h):
            k = (y - start) / max(1, h - start - 1)
            for x in range(w):
                g[y][x] = MUZ_D if r.random() < 0.25 + 0.6 * k else FUR_D
        if face == "north":
            for x in (w // 3, 2 * w // 3):
                for y in range(h - 3, h):
                    g[y][x] = PUPIL
        return g
    return f


# ---------------------------------------------------------------- head
def brow_row(c):
    """Upper row of the brow at column c of the right front face (col 0 = outer edge, col 13 = middle)."""
    return int(3.6 + (c - 1) * 0.36 + 0.5)


def face_right(w, h, r, face):
    """Front of the skull, right half (the left half is its mirror). The menace is painted here: a heavy dark brow
    descending towards the muzzle (the two halves meet in a V), a small almond eye slanted under it (inner corner
    lower), hooded by a rim of dark amber, a vertical slit pupil, one glint texel, a dark tear line running from the
    inner corner to the muzzle, a light beige cheek below."""
    if face == "north":
        assert (w, h) == (14, 21), (w, h)
        g = fur_grid(w, h, r, "north", 2.05, low=0.0, crest=0.35, streak=0.03)
        # beige cheek under the eye, outer side (the muzzle covers the inner side below row 12)
        for y in range(11, h):
            for x in range(0, 7):
                if y > 11 or x < 4:
                    g[y][x] = BEIGE if r.random() < 0.7 else FUR_L
        for x in range(7, w):
            for y in range(12, h):
                g[y][x] = FUR2
        # brow: two dark rows, light ridge above (light from the top)
        for c in range(1, w):
            yb = brow_row(c)
            if 2 <= c <= 10:
                g[yb - 1][c] = FUR_L
            g[yb][c] = MUZ_D
            g[yb + 1][c] = MUZ_D if c >= 3 else FUR_D
        # eye: columns 3..9, top row right under the brow
        for c in range(3, 10):
            et = brow_row(c) + 2
            end = c in (3, 9)
            g[et][c] = FUR_D if end else EYE_RIM
            g[et + 1][c] = EYE_RIM if end else EYE
            if end:
                g[et + 2][c] = MUZ_D
            else:
                g[et + 2][c] = EYE_RIM if c == 8 else EYE
                g[et + 3][c] = MUZ_D
        pc = 6
        et = brow_row(pc) + 2
        for y in range(et, et + 3):
            g[y][pc] = PUPIL
        g[et + 1][pc - 1] = GLINT
        # tear line: from the inner corner down to the muzzle bridge
        y0 = brow_row(9) + 4
        x = 10
        for y in range(y0, 12):
            g[y][x] = MUZ_D
            if y == y0:
                g[y][x - 1] = FUR_D
            if (y - y0) % 2 == 1 and x < w - 1:
                x += 1
        # bridge between the eyes slightly darker (frown)
        for y in range(brow_row(13) + 2, 12):
            g[y][w - 1] = FUR2
        return g
    g = fur_grid(w, h, r, face, 2.05)
    if face == "east":
        # brow ridge and cheek seen from the side (front edge = last columns)
        for x in (w - 2, w - 1):
            g[4][x] = MUZ_D
            g[5][x] = FUR_D if x == w - 2 else MUZ_D
            g[3][x] = FUR_L
        for y in range(12, h):
            for x in range(w - 6, w):
                g[y][x] = BEIGE if r.random() < 0.65 else FUR_L
    return g


def skull(w, h, r, face):
    g = fur_grid(w, h, r, face, 2.0)
    if face == "south":
        for y in range(h):
            for x in range(w):
                if r.random() < 0.25:
                    g[y][x] = FUR2
    return g


def cheek(w, h, r, face):
    """Cheek ruff: beige fluff with copper light, russet on top, tufts cut along the bottom edge."""
    if face == "up":
        return fur_grid(w, h, r, face, 2.0)
    g = [[BEIGE if r.random() < 0.62 else (FUR_L if r.random() < 0.8 else FUR) for _ in range(w)] for _ in range(h)]
    if face != "down":
        for y in range(min(3, h)):
            for x in range(w):
                if r.random() < 0.7 - y * 0.2:
                    g[y][x] = FUR_L if r.random() < 0.6 else FUR
        return carve(g, ("bottom",), (0, 1, 2, 3, 2, 1), r.randrange(6))
    return carve(g, ("top", "bottom", "left", "right"), (0, 1, 2, 1), r.randrange(4))


def muzzle_side(w, h, r, mouth, gap_from, fang=False, nose=False):
    """Side of the muzzle (east picture: back at u = 0, front at u = w - 1): light russet above, beige below, a dark
    mouth line, the gap of the slightly open mouth opening towards the front."""
    g = []
    for y in range(h):
        row = []
        for x in range(w):
            if y < mouth - 2:
                row.append(FUR_L if r.random() < 0.85 else FUR)
            else:
                row.append(BEIGE if r.random() < 0.85 else FUR_L)
        g.append(row)
    for x in range(w):
        g[mouth][x] = MUZ_D
        if x / max(1, w - 1) >= gap_from:
            g[mouth + 1][x] = FUR_D
    if fang:
        g[mouth][w - 1] = g[mouth + 1][w - 1] = IVORY
    if nose:
        g[0][w - 1] = g[1][w - 1] = PUPIL
    return g


def muzzle_base(w, h, r, face):
    if face in ("east", "west"):
        g = muzzle_side(w, h, r, 6, 0.4)
        return g if face == "east" else [row[::-1] for row in g]
    if face == "up":
        return [[FUR_L if r.random() < 0.85 else FUR for _ in range(w)] for _ in range(h)]
    if face == "down":
        return [[BEIGE if r.random() < 0.8 else FUR2 for _ in range(w)] for _ in range(h)]
    return [[(FUR_L if y < 4 else BEIGE) for _ in range(w)] for y in range(h)]


def muzzle_tip(w, h, r, face):
    if face in ("east", "west"):
        g = muzzle_side(w, h, r, 5, 0.0, fang=True, nose=True)
        return g if face == "east" else [row[::-1] for row in g]
    if face == "up":
        g = [[FUR_L if r.random() < 0.85 else FUR for _ in range(w)] for _ in range(h)]
        for y in (0, 1):
            for x in range(3, w - 3):
                g[y][x] = PUPIL
        return g
    if face == "down":
        return [[BEIGE] * w for _ in range(h)]
    if face == "north":
        assert (w, h) == (10, 8), (w, h)
        g = [[FUR_L] * w for _ in range(2)] + [[BEIGE] * w for _ in range(h - 2)]
        for y in range(3):                      # nose
            for x in range(2, 8):
                edge = x in (2, 7) and y != 1
                g[y][x] = MUZ_D if edge else PUPIL
        g[2][3] = g[2][6] = MUZ_D
        g[3][4] = g[3][5] = MUZ_D               # philtrum
        for x in range(w):
            g[5][x] = MUZ_D                     # mouth line
            g[6][x] = FUR_D                     # open gap
        for x in (1, w - 2):                    # two small fangs
            g[5][x] = g[6][x] = IVORY
        return g
    return [[FUR] * w for _ in range(h)]


def ear(w, h, r, face):
    """Upright ear, triangular by cut-out: russet outside (south), dark brown inside (north) with a russet border,
    dark tip on both; sides cut into a narrow triangle too, so the ear still reads in profile."""
    if face not in ("north", "south"):
        return [[CLEAR] * w for _ in range(h)]   # a plate: only front and back carry the triangle
    g = []
    tip = int(h * 0.22)
    for y in range(h):
        half = (y + 1) / h * w / 2
        row = []
        for x in range(w):
            d = half - abs(x + 0.5 - w / 2)
            if d < 0:
                row.append(CLEAR)
            elif y < tip:
                row.append(MUZ_D)
            elif face == "north":
                row.append(FUR if d < 1.6 else (FUR_D if r.random() < 0.85 else MUZ_D))
            else:
                row.append(FUR if r.random() < 0.8 else FUR2)
        g.append(row)
    return g


# ---------------------------------------------------------------- materials
MANE_MIX = (("olive", 4), ("moss", 3), ("ochre", 2), ("brown", 1))
TIP_MIX = (("ochre", 6), ("brown", 2), ("olive", 2), ("moss", 1))
MATS = {name: (p, D) for name, p in {
    "trunk_r": trunk(), "trunk_l": mirrored(trunk()),
    "shoulder_r": trunk(2.15), "shoulder_l": mirrored(trunk(2.15)),
    "skull": skull, "face_r": face_right, "face_l": mirrored(face_right),
    "cheek_r": cheek, "cheek_l": mirrored(cheek),
    "muzzle": muzzle_base, "muzzle_tip": muzzle_tip,
    "ear_r": ear, "ear_l": mirrored(ear),
    "leg_front": fur(1.75, low=0.3), "leg_back": fur(1.65, low=0.3),
    "paw_front": leg_low(True), "paw_back": leg_low(False),
    "tail_1": tail(0.0), "tail_2": tail(0.0), "tail_3": tail(0.08), "tail_4": tail(0.3), "tail_5": tail(0.65),
    "tail_leaves": leaves(TIP_MIX, sides="all", up="all", down="all", gap=PBROWN),
    "mane": leaves(MANE_MIX, sides="top", up="all"),
    "moss": moss,
}.items()}
GLOW = {}

# ---------------------------------------------------------------- geometry
NECK = (0.0, 25.0, -16.0)
HEAD_R = rot(-10, 0, 0)                 # head leaning 10 degrees forward (nose down)
MUZZLE_PIVOT = (0.0, 22.5, -28.0)
MUZZLE_R = rot(5, 0, 0)                 # muzzle tilted back up 5 degrees in the head: 5 degrees down in the world
TAIL_ROOT = (0.0, 18.0, 11.0)
# tail segments: length, rise (degrees above horizontal), yaw (sway), thickness
TAIL = ((5.5, 18, 0, 10.0), (5.5, 34, 5, 10.5), (5.5, 48, -4, 9.0), (5.0, 60, 6, 7.5), (4.5, 68, 2, 8.0))
TAIL_OVERLAP = 1.5

GROUPS = {"Root": ((0, 0, 0), None), "Body": ((0, 18, 0), "Root"), "h_Head": (NECK, "Body"),
          "Leg_front_right": ((4.5, 13.5, -12.5), "Body"), "Leg_front_left": ((-4.5, 13.5, -12.5), "Body"),
          "Leg_back_right": ((4.5, 13.0, 9.5), "Body"), "Leg_back_left": ((-4.5, 13.0, 9.5), "Body"),
          "Tail": (TAIL_ROOT, "Body"), "Tail_tip": (None, "Tail"), "Mane": ((0, 24, 0), "Body")}

# brief split of the 42 boxes
BUDGET = {"trunk": 8, "head": 8, "legs": 8, "tail": 8, "ears": 2, "vegetation": 8}


def parts():
    P = []
    count = {k: 0 for k in BUDGET}

    def add(part, g, name, c, size, r, mat):
        count[part] += 1
        P.append((g, name, list(c), list(size), r, mat))

    # ------------------------------------------------ trunk: 2 wide x 3 long, shoulders a step above the hips
    for name, z0, z1, y0, y1 in (("poitrail", -14, -5, 11.5, 24.5), ("ventre", -5, 4, 13.0, 23.6),
                                 ("hanches", 4, 13, 11.5, 22.5)):
        for s, sd in ((1, "d"), (-1, "g")):
            add("trunk", "Body", f"{name}_{sd}", (3.75 * s, (y0 + y1) / 2, (z0 + z1) / 2),
                (7.5, y1 - y0, z1 - z0), (0, 0, 0), "trunk_r" if s == 1 else "trunk_l")
    for s, sd in ((1, "d"), (-1, "g")):     # shoulders raised towards the neck
        add("trunk", "Body", f"epaule_{sd}", (3.9 * s, 25.0, -11.0), (7.5, 6.0, 9.0), (8, 0, 0),
            "shoulder_r" if s == 1 else "shoulder_l")

    # ------------------------------------------------ head: built level, then leaned around the neck
    head = attach(NECK, HEAD_R)
    snout = attach(MUZZLE_PIVOT, MUZZLE_R)

    def hadd(part, name, c, size, r, mat, muzzle=False):
        if muzzle:
            c, r = snout(c, r)
        c, M = head(c, r)
        add(part, "h_Head", name, c, size, M, mat)

    for s, sd in ((1, "d"), (-1, "g")):
        hadd("head", f"crane_arriere_{sd}", (4.0 * s, 26.0, -18.5), (8.0, 11.0, 7.0), (0, 0, 0), "skull")
        hadd("head", f"crane_avant_{sd}", (3.5 * s, 25.75, -25.0), (7.0, 10.5, 6.0), (0, 0, 0),
             "face_r" if s == 1 else "face_l")
        hadd("head", f"joue_{sd}", (7.0 * s, 22.5, -22.0), (4.5, 5.5, 7.0), (0, 15 * s, -8 * s),
             "cheek_r" if s == 1 else "cheek_l")
    hadd("head", "museau", (0, 22.5, -30.25), (7.0, 5.0, 4.5), (0, 0, 0), "muzzle", muzzle=True)
    hadd("head", "museau_bout", (0, 22.25, -34.25), (5.0, 4.0, 3.5), (0, 0, 0), "muzzle_tip", muzzle=True)
    # ears: left 10 degrees outwards, right 5 degrees forwards (brief), both leaning with the head
    hadd("ears", "oreille_d", (4.5, 34.6, -20.5), (6.5, 9.0, 1.5), (-5, -22, 0), "ear_r")
    hadd("ears", "oreille_g", (-4.5, 34.6, -20.5), (6.5, 9.0, 1.5), (0, 22, 10), "ear_l")

    # ------------------------------------------------ legs: short, slightly bent, front ones thicker
    for s, sd, side in ((1, "d", "right"), (-1, "g", "left")):
        g = f"Leg_front_{side}"
        add("legs", g, f"patte_avant_{sd}", (4.5 * s, 10.0, -12.5), (4.5, 7.0, 4.5), (8, 0, 0), "leg_front")
        add("legs", g, f"pied_avant_{sd}", (4.5 * s, 3.4, -14.0), (4.0, 7.0, 5.0), (-4, 0, 0), "paw_front")
        g = f"Leg_back_{side}"
        add("legs", g, f"patte_arriere_{sd}", (4.5 * s, 9.2, 9.8), (4.2, 7.0, 5.4), (-18, 0, 0), "leg_back")
        add("legs", g, f"pied_arriere_{sd}", (4.5 * s, 3.2, 11.6), (3.6, 6.6, 4.4), (8, 0, 0), "paw_back")

    # ------------------------------------------------ tail: angular rising curve, thick, thinner, wider again
    p = list(TAIL_ROOT)
    end, last = None, None
    for i, (ln, rise, yaw, t) in enumerate(TAIL, 1):
        a, b = math.radians(rise), math.radians(yaw)
        d = [math.sin(b) * math.cos(a), math.sin(a), math.cos(b) * math.cos(a)]
        c = [p[k] + d[k] * ln / 2 for k in range(3)]
        add("tail", "Tail", f"queue_{i}", c, (t, t, ln), (-rise, yaw, 0), f"tail_{i}")
        end = [p[k] + d[k] * ln for k in range(3)]
        p = [end[k] - d[k] * TAIL_OVERLAP for k in range(3)]
        last = d
    GROUPS["Tail_tip"] = (end, "Tail")
    # bunch of dead leaves around the tip, each turned its own way, slightly wider than the last segment
    for name, off, size, r in (("queue_feuilles_1", (0.0, 1.6, 0.8), (9.0, 8.0, 8.0), (-58, 14, 10)),
                               ("queue_feuilles_2", (3.2, -0.6, 3.4), (7.0, 6.5, 7.0), (-40, -18, -12)),
                               ("queue_feuilles_3", (-2.8, 2.6, 2.8), (6.5, 7.0, 6.5), (-80, 8, 6))):
        add("tail", "Tail_tip", name, [end[k] + off[k] for k in range(3)], size, r, "tail_leaves")

    # ------------------------------------------------ vegetation
    # mane: four shingles along the back, back edge lifted (raised hackles), rolled alternately (brief: x 10-20,
    # roll around the length 10-25, yaw up to 15 degrees)
    for i, (c, size, r) in enumerate((((0, 28.6, -10.0), (7.0, 3.5, 8.0), (-16, 6, 14)),
                                      ((0.3, 26.3, -3.0), (6.5, 3.5, 7.5), (-13, -9, -18)),
                                      ((-0.3, 25.6, 3.8), (6.0, 3.0, 7.0), (-15, 11, 12)),
                                      ((0.2, 24.9, 10.0), (5.0, 3.0, 6.5), (-18, -5, -22))), 1):
        add("vegetation", "Mane", f"criniere_{i}", c, size, r, "mane")
    # shoulders: moss swept backwards, top leaning out
    for s, sd in ((1, "d"), (-1, "g")):
        add("vegetation", "Body", f"mousse_epaule_{sd}", (8.4 * s, 24.6, -9.0), (3.0, 5.0, 8.0),
            (-12, 12 * s, -18 * s), "moss")
    # two joints only (asymmetric): left elbow, right knee
    add("vegetation", "Leg_front_left", "mousse_coude_g", (-6.9, 11.6, -12.0), (2.5, 3.5, 4.0), (12, 0, 12), "moss")
    add("vegetation", "Leg_back_right", "mousse_genou_d", (6.8, 10.6, 9.6), (2.5, 3.5, 4.5), (-14, 0, -12), "moss")

    assert count == BUDGET, count
    assert len(P) == 42
    return P


def build():
    return ground(parts(), GROUPS, center=True)


def face_closeup(data, atlases, sizes, path, size=420):
    """Head alone seen from the front and from three quarters, large: checks the expression."""
    import paths  # noqa: F401
    import apercu as A
    from png_util import write
    head = {"crane", "joue", "museau", "oreille"}
    els = [e for e in flat_elements(data, sizes) if e["name"].split("_")[0] in head]
    combo = combined(atlases, sizes)
    bg = (58, 58, 62, 255)
    imgs = [A.render(els, combo, yaw, pitch, size=size, bg=bg, margin=24) for yaw, pitch in ((0, 6), (30, 10))]
    write(path, size * 2, size, [imgs[0][y] + imgs[1][y] for y in range(size)])


if __name__ == "__main__":
    args = sys.argv[1:]
    opts = {}
    for flag in ("--apercu", "--visage"):
        if flag in args:
            i = args.index(flag)
            opts[flag] = args[i + 1]
            del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims = build()
    uv, atlases, sizes = bake(P, MATS, GLOW)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    print(f"{len(P)} cubes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (large x haut x long) "
          f"= {dims[0] * 2:.1f} x {dims[1] * 2:.1f} x {dims[2] * 2:.1f} unités du brief ; texture {sizes[0]}")
    if "--apercu" in opts:
        # face, three quarters (presentation view), profile, top, then the silhouette
        apercu(data, atlases, sizes, opts["--apercu"], set(), views=((0, 4), (35, 14), (90, 2), (20, 75)), size=420)
    if "--visage" in opts:
        face_closeup(data, atlases, sizes, opts["--visage"])
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
