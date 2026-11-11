#!/usr/bin/env python3
"""Pharaon des Sables: static humanoid boss of the Deblayeur dungeon, built from the 2026-11-11 art brief
("Prompt de modélisation 3D, Pharaon des Sables, cahier des charges artistique et technique intégral").

Every volume is written with the brief's values: centre (X width, Y depth, Z height) and size in blocks, the
character looking toward -Y. `box()` converts them to Blockbench units (x = X, y = Z, z = Y, 16 units per block,
front to the north). Local corrections where the brief's own numbers collide are marked FIX and listed in
docs/technique/boss/pharaon-sables.md.

Usage: pharaon_sables.py [out_dir] [--apercu <sheet.png>]
"""
import json
import math
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, export, ground, hx  # noqa: E402
from golem_fonderie import app, mul, rot  # noqa: E402

IDENT = "pharaon_sables"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-0000000fa7a0")
DENSITY = 2          # texels per drawing unit: 32 pixels per block on every face

# --------------------------------------------------------------------------- palette (brief section 4 only)
GRES = [hx(c) for c in ("#392F29", "#594737", "#785335", "#A97D49", "#C9A365", "#E5C88A")]
OR = [hx(c) for c in ("#76501F", "#A97820", "#D9A52E", "#FFD86B", "#E5C88A")]
TURQ = [hx(c) for c in ("#102F42", "#164B5A", "#087F83", "#20BDAE", "#65F0DC")]
IVORY, LINEN, BROWN = hx("#F1DFB8"), hx("#CDB88E"), hx("#94754F")
RED, RED_DARK = hx("#A93630"), hx("#672722")
AMBER = hx("#F3A83B")

SHIFT = {"up": 1, "down": -2, "north": 0, "south": 0, "east": -1, "west": -1}
SIDES = ("north", "south", "east", "west")


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def blank(w, h, c):
    return [[c] * w for _ in range(h)]


# --------------------------------------------------------------------------- painters (w, h, rnd, face) -> rows
def sandstone(base=4, cracks=0, strata=True, light=True, pal=GRES):
    """Sandstone / ancient stone: 45/25/15/10/5 % around the base tone, light top-left on the front, dark corners,
    horizontal strata every 3 to 5 pixels, optional 1-pixel angular cracks."""
    def paint(w, h, rnd, face):
        sh = SHIFT[face]
        if w * h <= 4:
            return blank(w, h, pal[clamp(base + sh, 0, len(pal) - 1)])
        rows, y = set(), rnd.randint(2, 4)
        while strata and y < h - 1:
            rows.add(y)
            y += rnd.randint(4, 6)
        cells = {}

        def noise(x, y):
            # 2 x 2 pixel grain: the noise reads at 16 px per block, details keep the finer grid
            k = (x // 2, y // 2)
            if k not in cells:
                r = rnd.random()
                cells[k] = 0 if r < .45 else -1 if r < .70 else 1 if r < .85 else -2 if r < .95 else -3
            return cells[k]
        img = []
        for y in range(h):
            row = []
            for x in range(w):
                v = base + noise(x, y) + sh
                if y in rows and face in SIDES and (x // 2) % 4 != 3:
                    v = min(v, base - 1 + sh)
                if light and face == "north" and x < w * .45 and y < h * .45 and (x // 2 + y // 2) % 2 == 0:
                    v += 1
                if x in (0, w - 1) and y in (0, h - 1):
                    v -= 1
                if y == h - 1 and face in SIDES and rnd.random() < .5:
                    v -= 1
                row.append(pal[clamp(v, 0, len(pal) - 1)])
            img.append(row)
        for _ in range(cracks if min(w, h) >= 6 else 0):
            x, y = rnd.randrange(1, w - 1), 0
            for _ in range(int(min(w, h) * .6)):
                img[y][x] = pal[1]
                y += 1
                x = clamp(x + rnd.choice((-1, 0, 0, 1)), 0, w - 1)
                if y >= h:
                    break
        return img
    return paint


def gold(base=2, border="", border_col=OR[0], reflect=True):
    """Stylised metal: base tone, a few darker and lighter pixels, 1-2 px horizontal reflections on the front, lit top
    corners, darker bottom corners; `border` draws edges on the front face (l, r, t, b)."""
    def paint(w, h, rnd, face):
        sh = SHIFT[face]
        b = clamp(base + sh, 0, 4)
        img = []
        for y in range(h):
            row = []
            for x in range(w):
                r = rnd.random()
                v = b - 1 if r < .08 else b + 1 if r < .13 else b
                if y == h - 1 and face in SIDES and rnd.random() < .6:
                    v = b - 1
                row.append(OR[clamp(v, 0, 4)])
            img.append(row)
        if face in SIDES and w >= 3 and h >= 3:
            for x in (0, w - 1):
                img[0][x] = OR[clamp(b + 1, 0, 4)]
                img[h - 1][x] = OR[clamp(b - 1, 0, 4)]
            if reflect and face == "north":
                for ry in sorted({max(1, h // 4), h // 2 + 1} if h >= 8 else {max(1, h // 3)}):
                    span = max(1, int(w * rnd.uniform(.4, .8)))
                    x0 = rnd.randint(1, max(1, w - span - 1))
                    for x in range(x0, min(w - 1, x0 + span)):
                        img[ry][x] = OR[clamp(b + 1, 0, 4)]
        if face == "north":
            for x in range(w):
                if "t" in border:
                    img[0][x] = border_col
                if "b" in border:
                    img[h - 1][x] = border_col
            for y in range(h):
                if "l" in border:
                    img[y][0] = border_col
                if "r" in border:
                    img[y][w - 1] = border_col
        return img
    return paint


def magic(up=4, side=2):
    """Contrasted turquoise: bright pixels in the centre, darker toward the edges, no halo."""
    def paint(w, h, rnd, face):
        if face == "up":
            return [[TURQ[up if 0 < x < w - 1 and 0 < y < h - 1 else up - 1] for x in range(w)] for y in range(h)]
        if face in ("east", "west", "down", "south"):
            return [[TURQ[side if y < h - 1 else side - 1] for x in range(w)] for y in range(h)]
        if w <= 2 or h <= 2:
            return blank(w, h, TURQ[3])
        cx, cy = (w - 1) / 2, (h - 1) / 2
        img = []
        for y in range(h):
            row = []
            for x in range(w):
                d = max(abs(x - cx) / (w / 2), abs(y - cy) / (h / 2))
                row.append(TURQ[4 if d < .35 else 3 if d < .7 else 2 if d < .95 else 1])
            img.append(row)
        return img
    return paint


def turq_band(dots=3):
    """Turquoise bracelet / scarab: scarab blue with a light top row and bright dots."""
    def paint(w, h, rnd, face):
        sh = SHIFT[face]
        img = blank(w, h, TURQ[clamp(2 + min(0, sh), 0, 4)])
        if face in SIDES:
            for x in range(w):
                img[0][x] = TURQ[3]
                if h > 2 and dots and x % dots == 1:
                    img[h // 2][x] = TURQ[4]
        elif face == "up":
            img = blank(w, h, TURQ[3])
        return img
    return paint


def cloth_ivory():
    """Inner skirt: ivory, irregular but symmetric vertical folds in linen with some brown shadows."""
    def paint(w, h, rnd, face):
        if face == "up":
            return blank(w, h, IVORY)
        if face == "down":
            return blank(w, h, LINEN)
        base = IVORY if face in ("north", "south") else LINEN
        fold = LINEN if base == IVORY else BROWN
        img = blank(w, h, base)
        half, x, cols = w // 2, rnd.randint(1, 2), {}
        while x < half:
            cols[x] = rnd.random() < .5
            x += rnd.randint(2, 4)
        for x, shadow in list(cols.items()):
            for xx, s in ((x, shadow), (w - 1 - x, shadow)):
                for y in range(h):
                    img[y][xx] = fold
                    if s and 0 <= xx + (1 if xx < half else -1) < w and y > h * .3:
                        img[y][xx + (1 if xx < half else -1)] = BROWN
        for x in range(w):
            img[h - 1][x] = LINEN if base == IVORY else BROWN
        return img
    return paint


def ivory_flat():
    def paint(w, h, rnd, face):
        img = blank(w, h, IVORY if face != "down" else LINEN)
        if face in SIDES and h > 1:
            for x in range(w):
                img[h - 1][x] = LINEN
        return img
    return paint


def cloth_red():
    """Imperial red panel: gold edges and a gold central band, dark red folds."""
    def paint(w, h, rnd, face):
        if face == "up":
            return blank(w, h, RED)
        if face == "down":
            return blank(w, h, RED_DARK)
        img = blank(w, h, RED if face in ("north", "south") else RED_DARK)
        for y in range(h):
            if face == "north":
                img[y][0] = img[y][w - 1] = OR[2]
                c = w // 2
                img[y][c] = OR[3] if y % 5 else OR[2]
                if w % 2 == 0:
                    img[y][c - 1] = OR[2]
        for x in (1, w - 2):
            if face == "north" and 1 < x < w - 2 or face != "north":
                continue
        if face == "north" and w >= 7:
            for x in (2, w - 3):
                for y in range(int(h * rnd.uniform(.25, .45)), h - 1):
                    img[y][x] = RED_DARK
        for x in range(w):
            img[h - 1][x] = OR[2] if face == "north" else OR[1]
        return img
    return paint


def nemes():
    """Headdress lappets: scarab blue with alternating vertical bands (gold 2 px, light turquoise 1 px, abyss blue
    1 px), gold plate at the bottom."""
    cycle = "GGBLBAB"
    def paint(w, h, rnd, face):
        dark = face in ("east", "west")
        col = {"G": OR[1] if dark else OR[2], "B": TURQ[1] if dark else TURQ[2],
               "L": TURQ[3] if dark else TURQ[4], "A": TURQ[0] if dark else TURQ[1]}
        if face == "up":
            return blank(w, h, OR[3])
        if face == "down":
            return blank(w, h, TURQ[1])
        img = [[col[cycle[x % len(cycle)]] for x in range(w)] for y in range(h)]
        for y in (h - 2, h - 1):
            for x in range(w):
                img[y][x] = OR[2] if y == h - 2 else OR[1]
        for x in range(w):
            img[0][x] = OR[3] if col[cycle[x % len(cycle)]] in (OR[1], OR[2]) else img[0][x]
        return img
    return paint


def band(base, rows=(), motif=None):
    """Horizontal ornament: base colour, coloured rows (index, colour) and a repeated motif
    (row, period, offset, colour) every `period` pixels."""
    def paint(w, h, rnd, face):
        if face == "up":
            return blank(w, h, base)
        if face == "down":
            return blank(w, h, OR[1])
        img = blank(w, h, base)
        for ry, c in rows:
            for x in range(w):
                img[ry % h][x] = c
        for ry, period, off, c in (motif or ()):
            for x in range(off, w, period):
                img[ry % h][x] = c
        if face in ("east", "west"):
            img = [[tuple(int(v * .82) for v in px) for px in row] for row in img]
        return img
    return paint


def glyph(bitmap):
    """Small dedicated plaque: bronze ground, gold rim, a 3x3 turquoise geometric hieroglyph."""
    def paint(w, h, rnd, face):
        img = blank(w, h, OR[0] if face == "north" else OR[1])
        if face != "north":
            return img
        for x in range(w):
            img[0][x] = img[h - 1][x] = OR[1]
        for y in range(h):
            img[y][0] = img[y][w - 1] = OR[1]
        ox, oy = (w - 3) // 2, (h - 3) // 2
        for j, line in enumerate(bitmap):
            for i, ch in enumerate(line):
                if ch != "." and 0 <= oy + j < h and 0 <= ox + i < w:
                    img[oy + j][ox + i] = TURQ[4] if ch == "o" else TURQ[3]
        return img
    return paint


def skirt_front():
    """Gold front panel of the skirt: bronze rim and a geometric turquoise pyramid (base, two diagonals, lozenge)."""
    g = gold(2, "lrtb", OR[1])
    def paint(w, h, rnd, face):
        img = g(w, h, rnd, face)
        if face != "north" or w < 5:
            return img
        inner = w - 2
        base_y = int(h * .62)
        for x in range(1, w - 1):
            img[base_y][x] = TURQ[3]
        half = inner // 2
        for k in range(1, half + 1):
            img[base_y - k][1 + k] = TURQ[3]
            img[base_y - k][w - 2 - k] = TURQ[3]
        apex = base_y - half
        cx = w // 2
        top = apex - 2
        for (dx, dy) in ((0, -1), (-1, 0), (1, 0), (0, 1)):
            if 0 <= top + dy < h:
                img[top + dy][cx + dx] = TURQ[2]
        img[top][cx] = TURQ[4]
        for y in (int(h * .85), int(h * .12)):
            for x in range(2, w - 2):
                img[y][x] = TURQ[2]
        return img
    return paint


def head_mask():
    """Stone mask: light sandstone, sockets shaded around the eyes, nostril shadow, a calm straight dark mouth."""
    s = sandstone(4, strata=False)
    def paint(w, h, rnd, face):
        img = s(w, h, rnd, face)
        if face != "north":
            return img
        def row(z):
            return clamp(round((3.43 - z) / 0.62 * h), 0, h - 1)
        cx = (w - 1) / 2
        ey = row(3.18)
        for x in range(w):
            dx = abs(x - cx)
            if 1.5 <= dx <= 6.5:
                for y in (ey - 1, ey, ey + 1):
                    img[y][x] = GRES[3]
        ny = row(3.00)
        for x in range(w):
            if abs(x - cx) <= 1.5:
                img[ny][x] = GRES[2]
        my = row(2.955)
        for x in range(w):
            if abs(x - cx) <= 2.6:
                img[my][x] = GRES[0]
                img[my + 1][x] = GRES[3]
        for y in (h - 2, h - 1):
            for x in range(w):
                img[y][x] = GRES[3] if y == h - 2 else GRES[2]
        return img
    return paint


def forehead():
    g = gold(2, "lrtb", OR[1])
    def paint(w, h, rnd, face):
        img = g(w, h, rnd, face)
        if face == "north" and w >= 4 and h >= 3:
            cx, cy = w // 2, h // 2
            for x in (cx - 1, cx):
                for y in (cy - 1, cy):
                    img[y][x] = TURQ[4] if (x, y) == (cx - 1, cy - 1) else TURQ[3]
            if w >= 6:
                img[cy][cx - 2] = img[cy][cx + 1] = TURQ[2]
        return img
    return paint


def eyes():
    """Narrow eyes: night-blue ground, turquoise iris, one bright central pixel (emissive)."""
    def paint(w, h, rnd, face):
        if face != "north":
            return blank(w, h, TURQ[0])
        img = []
        for y in range(h):
            row = []
            for x in range(w):
                if x in (0, w - 1):
                    row.append(TURQ[0])
                elif y == 0 and x == w // 2:
                    row.append(TURQ[4])
                else:
                    row.append(TURQ[3])
            img.append(row)
        return img
    return paint


def amber():
    def paint(w, h, rnd, face):
        img = blank(w, h, AMBER)
        if face == "north" and h >= 2:
            img[0] = [OR[3]] * w
        return img
    return paint


def shard(base, crack=None):
    """Floating sandstone fragment; crack='line' draws a turquoise crack on the front, 'dots' two turquoise pixels."""
    s = sandstone(base, strata=False)
    def paint(w, h, rnd, face):
        img = s(w, h, rnd, face)
        if face == "north" and crack == "line":
            x = w // 2
            for y in range(h):
                img[y][x] = TURQ[4] if y == h // 2 else TURQ[3]
                x = clamp(x + (1 if y % 2 else -1) * (y % 3 == 0), 1, w - 2)
        if face == "north" and crack == "dots":
            img[h // 3][w // 3] = TURQ[3]
            img[h // 2][w - 1 - w // 3] = TURQ[4]
        return img
    return paint


def sandal():
    s = sandstone(4, strata=False)
    def paint(w, h, rnd, face):
        img = s(w, h, rnd, face)
        if face in ("up", "north", "south"):
            c = w // 2
            for y in range(h):
                for x in (c - 1, c):
                    img[y][x] = OR[2] if (y + x) % 3 else OR[3]
        return img
    return paint


def foot_top():
    s = sandstone(3, strata=False)
    def paint(w, h, rnd, face):
        img = s(w, h, rnd, face)
        if face == "up":
            for y in (0, 1, h - 2, h - 1):
                for x in range(w):
                    img[y][x] = GRES[4]
        if face in ("north", "south"):
            img = [[GRES[4] if rnd.random() < .7 else GRES[5] for x in range(w)] for y in range(h)]
        return img
    return paint


def wrapped_leg():
    s = sandstone(3, strata=False)
    def paint(w, h, rnd, face):
        img = s(w, h, rnd, face)
        if face in SIDES:
            for y in range(h):
                for x in range(w):
                    k = (y + x // 2) % 4
                    if k == 0:
                        img[y][x] = IVORY
                    elif k == 1:
                        img[y][x] = LINEN
        return img
    return paint


def bronze():
    def paint(w, h, rnd, face):
        img = [[OR[0] if rnd.random() < .8 else GRES[1] for x in range(w)] for y in range(h)]
        if face in SIDES:
            for y in range(h):
                img[y][0] = OR[1]
        if face == "up":
            img = blank(w, h, OR[1])
        return img
    return paint


MATS = {
    "gres": sandstone(4, cracks=0),
    "gres_bras": sandstone(4, cracks=2),
    "gres_main": sandstone(4, strata=False),
    "gres_torse": sandstone(3),
    "gres_cou": sandstone(2, strata=False),
    "gres_sombre": sandstone(2, strata=False),
    "pierre_plaque": sandstone(3, cracks=2, strata=False, pal=GRES),
    "pierre_pointe": sandstone(2, strata=False, light=False),
    "masque": head_mask(),
    "joue": sandstone(4, strata=False),
    "nez": sandstone(3, strata=False, light=False),
    "semelle": bronze(),
    "sandale": sandal(),
    "pied": foot_top(),
    "jambe": wrapped_leg(),
    "or": gold(2),
    "or_ancien": gold(1),
    "or_lumineux": gold(3),
    "or_disque": gold(2, "lrtb", OR[3]),
    "or_fronton": gold(2, "lrtb", OR[0]),
    "plastron_0": gold(2, "lrt", OR[0]),
    "plastron_1": gold(2, "lr", OR[0]),
    "plastron_4": gold(2, "lrb", OR[0]),
    "porte_fond": lambda w, h, rnd, face: blank(w, h, TURQ[0]),
    "manche": bronze(),
    "tissu": cloth_ivory(),
    "bandelette": ivory_flat(),
    "rouge": cloth_red(),
    "jupe_avant": skirt_front(),
    "nemes": nemes(),
    "bandeau": band(OR[2], rows=((0, OR[3]), (-1, OR[1])), motif=((2, 2, 0, TURQ[3]), (3, 2, 1, TURQ[2]))),
    "manteau": band(OR[2], rows=((0, OR[3]), (-1, OR[0])), motif=((1, 2, 0, TURQ[3]),)),
    "collier_haut": band(OR[1], rows=((0, OR[2]),), motif=((-1, 2, 0, OR[0]), (1, 2, 1, OR[2]))),
    "collier_milieu": band(TURQ[3], rows=((0, TURQ[2]),), motif=((-1, 2, 0, TURQ[2]), (1, 2, 1, TURQ[4]))),
    "collier_bas": band(OR[3], rows=((0, OR[2]),), motif=((-1, 2, 0, OR[1]), (-2, 2, 1, OR[2]))),
    "ceinture": sandstone(2, strata=False, light=False),
    "bande_turquoise": lambda w, h, rnd, face: blank(w, h, TURQ[3] if face != "east" else TURQ[2]),
    "bande_rouge": lambda w, h, rnd, face: blank(w, h, RED if face == "north" else RED_DARK),
    "turquoise": magic(),
    "turq_motif": turq_band(3),
    "scarabee": turq_band(2),
    "front": forehead(),
    "glyphe_losange": glyph([".X.", "XoX", ".X."]),
    "glyphe_triangle": glyph([".o.", "X.X", "XXX"]),
    "glyphe_escalier": glyph(["X..", "XX.", "XXo"]),
    "glyphe_soleil": glyph(["X.X", ".o.", "X.X"]),
    "eclat_1": shard(3, "line"),
    "eclat_2": sandstone(2, strata=False, light=False),
    "eclat_3": shard(4, "dots"),
    "sable_clair": lambda w, h, rnd, face: blank(w, h, GRES[5]),
    "sable_dore": lambda w, h, rnd, face: blank(w, h, GRES[4]),
    "sable_ambre": lambda w, h, rnd, face: blank(w, h, AMBER),
}
GLOW = {"yeux": eyes(), "noyau": magic(4, 2), "ambre": amber(), "cristal": magic(4, 3)}

# --------------------------------------------------------------------------- geometry
PARTS = []


def bb(c):
    """Brief point (X, Y, Z) in blocks -> Blockbench units."""
    return [c[0] * 16, c[2] * 16, c[1] * 16]


def brief_rot(rx, ry, rz):
    """Brief rotation about its X (width), Y (depth), Z (height) axes, applied in that order."""
    return mul(rot(0, rz, 0), mul(rot(0, 0, ry), rot(rx, 0, 0)))


def box(bone, name, c, s, mat, r=(0, 0, 0), place=None):
    """c = centre (X, Y, Z), s = size (width X, depth Y, height Z), both in blocks; r in Blockbench degrees or a
    matrix; place = attach() chain putting a straight-drawn piece into a rotated limb."""
    cc, ss = bb(c), [s[0] * 16, s[2] * 16, s[1] * 16]
    if place:
        cc, r = place(cc, r)
    PARTS.append((bone, name, cc, ss, r, mat))


def chain(*places):
    def place(c, r):
        for p in places:
            c, r = p(c, r)
        return c, r
    return place


BONES = {"Root": ((0, 0, 0), None)}


def bone(name, pivot_brief, parent, raw=False):
    BONES[name] = (pivot_brief if raw else bb(pivot_brief), parent)


bone("Body", (0, 0, 1.30), "Root")
bone("Leg_right", (0.20, 0, 0.58), "Root")
bone("Leg_left", (-0.20, 0, 0.58), "Root")

# 1. feet and royal sandals (section 6)
for side, s in (("right", 1), ("left", -1)):
    X, B = 0.23 * s, "Leg_" + side
    box(B, "semelle", (X, 0, 0.03), (0.34, 0.48, 0.06), "semelle")
    box(B, "sandale", (X, 0, 0.095), (0.33, 0.47, 0.07), "sandale")
    box(B, "pied", (X, -0.02, 0.175), (0.28, 0.40, 0.09), "pied")
    for i, y in enumerate((-0.11, 0.05)):
        box(B, f"lanière_{i}", (X, y, 0.175), (0.30, 0.035, 0.10), "or_lumineux", (0, 25 * s, 0))
    # legs under the skirt (section 7 title), wrapped in ritual bands, visible between sandal and hem
    box(B, "jambe", (0.20 * s, 0, 0.40), (0.20, 0.22, 0.36), "jambe")

# 2. ceremonial skirt (section 7): ivory core, red panels, sandstone side plates, gold front panel
box("Body", "jupe_tissu", (0, 0, 0.78), (0.68, 0.40, 0.95), "tissu")
for side, s in (("droite", 1), ("gauche", -1)):
    box("Body", f"panneau_{side}", (0.29 * s, -0.19, 0.78), (0.23, 0.10, 0.88), "rouge", (0, 0, 4 * s))
    box("Body", f"plaque_{side}", (0.40 * s, 0, 0.62), (0.16, 0.12, 0.70), "pierre_plaque")
box("Body", "panneau_royal", (0, -0.225, 0.88), (0.20, 0.07, 0.66), "jupe_avant")

# 3. royal belt (section 8). FIX: depth 0.44 instead of 0.18, which left a waist thinner than a leg in profile
box("Body", "ceinture", (0, -0.02, 1.35), (0.88, 0.44, 0.24), "ceinture")
box("Body", "boucle", (0, -0.265, 1.35), (0.30, 0.07, 0.18), "or")
box("Body", "scarabée_corps", (0, -0.31, 1.34), (0.09, 0.03, 0.11), "scarabee")
for s in (1, -1):
    box("Body", f"scarabée_aile_{'d' if s > 0 else 'g'}", (0.085 * s, -0.305, 1.345), (0.08, 0.02, 0.10), "turq_motif")
box("Body", "scarabée_tête", (0, -0.31, 1.415), (0.05, 0.03, 0.04), "scarabee")

# 4. torso and breastplate (section 9)
box("Body", "torse", (0, 0, 2.00), (0.92, 0.48, 1.12), "gres_torse")
TIERS = 5
for i in range(TIERS):
    top = 2.40 - 0.14 * i
    w = 0.84 - (0.84 - 0.62) * i / (TIERS - 1)
    mat = "plastron_0" if i == 0 else "plastron_4" if i == TIERS - 1 else "plastron_1"
    box("Body", f"plastron_{i}", (0, -0.27, top - 0.07), (w, 0.09, 0.14), mat)
box("Body", "bande_turquoise", (0, -0.322, 2.335), (0.76, 0.02, 0.035), "bande_turquoise")
box("Body", "bande_rouge", (0, -0.322, 1.765), (0.56, 0.02, 0.035), "bande_rouge")
# solar door: gold frame, dark cavity, sun disc. FIX: disc 0.32 instead of 0.18 (a 0.18 disc sits entirely
# behind the 0.20 x 0.26 core placed at the same spot, so it frames the core instead)
box("Body", "porte_fond", (0, -0.318, 2.05), (0.31, 0.01, 0.41), "porte_fond")
for n, c, s in (("haut", (0, 2.2775), (0.40, 0.045)), ("bas", (0, 1.8225), (0.40, 0.045)),
                ("gauche", (-0.1775, 2.05), (0.045, 0.50)), ("droite", (0.1775, 2.05), (0.045, 0.50))):
    box("Body", f"porte_{n}", (c[0], -0.335, c[1]), (s[0], 0.04, s[1]), "or_ancien")
box("Body", "disque_h", (0, -0.327, 2.05), (0.32, 0.02, 0.20), "turquoise")
box("Body", "disque_v", (0, -0.327, 2.05), (0.20, 0.02, 0.32), "turquoise")
for s in (1, -1):
    box("Body", f"ligne_{'d' if s > 0 else 'g'}", (0.07 * s, -0.36, 1.80), (0.03, 0.02, 0.18), "or_lumineux")
for n, (x, z), m in (("losange", (-0.28, 2.18), "glyphe_losange"), ("triangle", (0.28, 2.18), "glyphe_triangle"),
                     ("escalier", (-0.27, 1.92), "glyphe_escalier"), ("soleil", (0.27, 1.92), "glyphe_soleil")):
    box("Body", f"hiéroglyphe_{n}", (x, -0.322, z), (0.13, 0.02, 0.13), m)

# 5. the Buried Sun core (section 10)
bone("glow_core", (0, -0.35, 2.02), "Body")
box("glow_core", "noyau", (0, -0.35, 2.02), (0.20, 0.10, 0.26), "noyau")
box("glow_core", "trait_ambre", (0, -0.36, 2.215), (0.03, 0.03, 0.07), "ambre")
for sx in (1, -1):
    for sz in (1, -1):
        box("Body", f"rivet_{sx}_{sz}", (0.10 * sx, -0.37, 2.02 + 0.13 * sz), (0.045, 0.045, 0.045), "or_lumineux")

# 6. collar (section 14). FIX: centred at Z 2.55 instead of 2.82 (at 2.82 it covers the chin and mouth of the
# head, 2.81 to 3.43); a gold mantle ring on the shoulders carries it all around the neck
box("Body", "manteau", (0, 0, 2.61), (0.78, 0.52, 0.10), "manteau")
for i, (z, w, m) in enumerate(((2.65, 0.62, "collier_haut"), (2.55, 0.72, "collier_milieu"),
                               (2.45, 0.78, "collier_bas"))):
    box("Body", f"collier_{i}", (0, -0.275, z), (w, 0.07, 0.10), m)
box("Body", "pierre_collier", (0, -0.32, 2.43), (0.06, 0.03, 0.06), "turquoise", (0, 0, 45))
box("Body", "cou", (0, 0, 2.70), (0.26, 0.26, 0.30), "gres_cou")

# 7. arms, bracelets, hands (section 11): drawn straight then tilted 12 deg outward at the shoulder, forearm turned
# 15 deg forward at the elbow
HAND = {}
for side, s in (("right", 1), ("left", -1)):
    X = 0.57 * s
    shoulder, elbow = bb((X, 0, 2.57)), bb((X, 0, 2.30))
    p_arm = attach(shoulder, rot(0, 0, 12 * s))
    p_fore = chain(attach(elbow, rot(15, 0, 0)), p_arm)
    bone("Arm_" + side, shoulder, "Body", raw=True)
    bone("Forearm_" + side, p_arm(elbow, (0, 0, 0))[0], "Arm_" + side, raw=True)
    A, F = "Arm_" + side, "Forearm_" + side
    box(A, "bras", (X, 0, 2.48), (0.30, 0.34, 0.36), "gres_bras", place=p_arm)
    box(F, "avant_bras", (X, 0, 2.09), (0.27, 0.30, 0.42), "gres_bras", place=p_fore)
    for i, z in enumerate((2.26, 2.185, 2.11)):
        box(F, f"bandelette_{i}", (X, 0, z), (0.31, 0.32, 0.035), "bandelette", (0, 0, 8 * s), place=p_fore)
    box(F, "bracelet_or", (X, 0, 2.015), (0.34, 0.36, 0.09), "or", place=p_fore)
    box(F, "bracelet_turquoise", (X, 0, 1.925), (0.34, 0.36, 0.09), "turq_motif", place=p_fore)
    if side == "right":
        # closed around the sceptre: palm, fist on the outer side of the shaft, thumb in front
        box(F, "paume", (X, 0, 1.79), (0.16, 0.22, 0.18), "gres_main", place=p_fore)
        box(F, "poing", (X + 0.10, 0, 1.75), (0.08, 0.20, 0.16), "gres_main", place=p_fore)
        box(F, "pouce", (X + 0.04, -0.12, 1.80), (0.06, 0.05, 0.08), "gres_main", place=p_fore)
        HAND[side] = p_fore(bb((X + 0.07, 0, 1.76)), (0, 0, 0))[0]
    else:
        # slightly open, fingers spread outward as if steering the sand
        box(F, "paume", (X, 0, 1.80), (0.16, 0.22, 0.16), "gres_main", place=p_fore)
        knuckle = bb((X, 0, 1.72))
        p_fing = chain(attach(knuckle, rot(0, 0, 20 * s)), p_fore)
        box(F, "doigts", (X, 0, 1.66), (0.14, 0.22, 0.12), "gres_main", place=p_fing)
        box(F, "pouce", (X + 0.05, -0.12, 1.77), (0.05, 0.06, 0.10), "gres_main", place=p_fore)

# 8. sceptre (section 15): drawn upright around the grip, axis tilted 7 deg outward (top away from the body)
grip = HAND["right"]
gX, gZ, gY = grip[0] / 16, grip[1] / 16, grip[2] / 16
bone("Sceptre", grip, "Forearm_right", raw=True)
p_sc = attach(grip, rot(0, 0, -7))
box("Sceptre", "manche", (gX, gY, gZ - 0.225), (0.09, 0.09, 0.95), "manche", place=p_sc)
for i, dz in enumerate((-0.60, -0.25, 0.18)):
    box("Sceptre", f"anneau_{i}", (gX, gY, gZ + dz), (0.13, 0.13, 0.045), "or", place=p_sc)
box("Sceptre", "disque_h", (gX, gY, gZ + 0.30), (0.27, 0.12, 0.17), "or_disque", place=p_sc)
box("Sceptre", "disque_v", (gX, gY, gZ + 0.30), (0.17, 0.12, 0.27), "or_disque", place=p_sc)
box("Sceptre", "centre", (gX, gY - 0.02, gZ + 0.30), (0.11, 0.12, 0.11), "turquoise", place=p_sc)
for s in (1, -1):
    box("Sceptre", f"extension_{s}", (gX + 0.17 * s, gY, gZ + 0.30), (0.07, 0.07, 0.08), "gres", place=p_sc)
box("Sceptre", "pointe", (gX, gY, gZ - 0.685), (0.13, 0.13, 0.09), "pierre_pointe", place=p_sc)
box("Sceptre", "pointe_bout", (gX, gY, gZ - 0.76), (0.07, 0.07, 0.06), "pierre_pointe", place=p_sc)

# 9. head and royal mask (section 12)
bone("Head", (0, 0, 2.83), "Body")
box("Head", "tête", (0, 0, 3.12), (0.48, 0.42, 0.62), "masque")
box("Head", "plaque_front", (0, -0.235, 3.30), (0.16, 0.05, 0.10), "front")
for s in (1, -1):
    box("Head", f"joue_{'d' if s > 0 else 'g'}", (0.15 * s, -0.225, 3.02), (0.12, 0.05, 0.16), "joue", (0, -8 * s, 0))
box("Head", "nez_arête", (0, -0.225, 3.10), (0.06, 0.04, 0.12), "nez")
box("Head", "nez_bout", (0, -0.235, 3.03), (0.08, 0.06, 0.05), "nez")
bone("glow_head", (0, -0.22, 3.18), "Head")
for s in (1, -1):
    box("glow_head", f"œil_{'d' if s > 0 else 'g'}", (0.11 * s, -0.218, 3.18), (0.14, 0.02, 0.045), "yeux", (0, 0, 8 * s))

# 10. pharaonic headdress (section 13): base band, main gold volume, narrower cap, striped lappets
box("Head", "bandeau", (0, 0, 3.47), (0.60, 0.50, 0.18), "bandeau")
box("Head", "coiffe", (0, 0, 3.78), (0.70, 0.60, 0.50), "or")
box("Head", "calotte", (0, 0, 4.115), (0.52, 0.46, 0.17), "or_lumineux")
for side, s in (("droit", 1), ("gauche", -1)):
    box("Head", f"pan_{side}", (0.385 * s, -0.02, 3.09), (0.27, 0.34, 0.72), "nemes", (0, 0, 7 * s))
box("Head", "fronton", (0, -0.315, 4.02), (0.54, 0.07, 0.20), "or_fronton")
box("Head", "disque_solaire", (0, -0.37, 4.02), (0.13, 0.04, 0.13), "turquoise")
for sx in (1, -1):
    for sz in (1, -1):
        box("Head", f"perle_{sx}_{sz}", (0.085 * sx, -0.36, 4.02 + 0.06 * sz), (0.035, 0.035, 0.035), "or_lumineux")
# cobra: FIX placed right under the frontal plate, whose centre already holds the sun disc
box("Head", "cobra_base", (0, -0.34, 3.685), (0.20, 0.08, 0.05), "or_lumineux")
box("Head", "cobra_corps", (0, -0.34, 3.785), (0.06, 0.08, 0.15), "or_lumineux")
for s in (1, -1):
    box("Head", f"cobra_tête_{s}", (0.04 * s, -0.34, 3.875), (0.05, 0.08, 0.05), "or_lumineux")
for i, (w, d) in enumerate(((0.25, 0.20), (0.17, 0.14), (0.09, 0.08))):
    box("Head", f"pyramide_{i}", (0, -0.10, 4.23 + 0.06 * i), (w, d, 0.06), "or")
box("glow_head", "cristal", (0, -0.10, 4.43), (0.07, 0.07, 0.10), "cristal")

# 11. floating fragments and sand (section 16). FIX: shard 3 raised from Z 2.78 to 2.86, at 2.78 it cuts into
# the right shoulder
for name, c, s, r, m in (("Shard_1", (-0.77, 0, 2.92), (0.16, 0.14, 0.18), (12, 0, 18), "eclat_1"),
                         ("Shard_2", (-0.60, -0.02, 1.42), (0.12, 0.12, 0.15), (0, 22, 15), "eclat_2"),
                         ("Shard_3", (0.72, 0.02, 2.86), (0.14, 0.12, 0.16), (-12, 15, -18), "eclat_3")):
    bone(name, c, "Body")
    box(name, "éclat", c, s, m, brief_rot(*r))
bone("Sand", (0, 0, 2.5), "Body")
for i, (c, k, m) in enumerate((((-0.88, -0.06, 3.09), 0.045, "sable_clair"), ((-0.91, 0.05, 2.80), 0.035, "sable_ambre"),
                               ((-0.71, -0.10, 1.56), 0.04, "sable_dore"), ((-0.53, 0.07, 1.30), 0.035, "sable_clair"),
                               ((0.90, -0.04, 3.03), 0.055, "sable_dore"), ((0.62, 0.09, 3.04), 0.035, "sable_ambre"))):
    box("Sand", f"grain_{i}", c, (k, k, k), m, (15 * i, 30 * i, 10 * i))


# --------------------------------------------------------------------------- export
def overlaps(P):
    """Floating pieces (shards, sand) that touch anything else: they must stay visibly detached."""
    from bbkit import corners
    def aabb(p):
        pts = corners(p[2], p[3], p[4])
        return [min(q[i] for q in pts) for i in range(3)], [max(q[i] for q in pts) for i in range(3)]
    floating = [p for p in P if p[0].startswith(("Shard", "Sand"))]
    out = []
    for f in floating:
        a0, a1 = aabb(f)
        for o in P:
            if o is f or o[0] == f[0]:
                continue
            b0, b1 = aabb(o)
            if all(a0[i] < b1[i] and b0[i] < a1[i] for i in range(3)):
                out.append((f[0], f[1], o[0], o[1]))
    return out


def main():
    args = sys.argv[1:]
    prev = None
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    for hit in overlaps(PARTS):
        print("touching:", *hit)
    mats = {k: (v, DENSITY) for k, v in MATS.items()}
    glow = {k: (v, DENSITY) for k, v in GLOW.items()}
    P, groups, dims = ground(PARTS, BONES, center=False)
    uv, atlases, sizes = bake(P, mats, glow)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w") as f:
        json.dump(data, f, ensure_ascii=False)
    print(f"{path}: {len(P)} cubes, {len(groups)} bones, textures {sizes[0]} + {sizes[1]}, "
          f"{dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocks (width x height x depth)")
    if prev:
        apercu(data, atlases, sizes, prev, {TURQ[4], TURQ[3], AMBER, OR[3]},
               views=((0, 4), (30, 10), (90, 4), (180, 4), (-90, 4), (-150, 10)), size=420)
        print("preview:", prev)


if __name__ == "__main__":
    main()
