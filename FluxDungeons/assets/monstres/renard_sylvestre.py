#!/usr/bin/env python3
"""Sylvan Fox (Renard sylvestre), full redesign of the herbalist dungeon boss: STATIC Blockbench model (2026-11-14).

A fox whose anatomy is made of leaves: every volume of the body is a mass of leaves lying like fur (tips towards the
tail on the trunk, towards the tip on the tail, down the legs), with wood showing at the lower legs, the tail root and
one branch along the spine. Stalking quadruped pose: long trunk, deeper chest, narrow loins, head carried low and
forward, long narrow muzzle, big triangular ears, a huge comma shaped tail turning from forest green to autumn copper.

Brief constraints: 20 to 40 boxes (39 here, asserted), palette of the brief only, amber eyes with a slit pupil under
heavy leaf brows, leaves painted as leaves (shape, midrib, light side, clusters of one tone), never noise.
Leaf tips and the ear outline are cut-out (transparent) pixels, not extra boxes.

Bone names follow the rig of the current boss model (renard_feuilles) so its animations can be ported later.

Usage: renard_sylvestre.py [output folder] [--apercu <sheet.png>] [--visage <face.png>]
Front towards north (-z), the fox's right towards +x, 16 units = 1 block, 2 texels per unit (32 per block).
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

IDENT = "renard_sylvestre"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000f0e5714")
D = 2  # texels per model unit, every material
CLEAR = (0, 0, 0, 0)

# ---------------------------------------------------------------- palette (brief)
G_DEEP, G_FOREST, G_MOSS, G_LEAF, G_SAGE, G_YOUNG = (hx(c) for c in ("#193527", "#24472F", "#426B3C", "#628A43",
                                                                     "#91A85C", "#B5B96B"))
A_BROWN, A_TERRA, A_ORANGE, A_COPPER, A_OCHRE = (hx(c) for c in ("#75452D", "#A65C35", "#C9793D", "#D9954F",
                                                                 "#D8B45D"))
W_DARK, W_BARK, W_MAIN, W_LIGHT, W_SHINE = (hx(c) for c in ("#493329", "#63432F", "#80563A", "#A8784C", "#BD9567"))
NOSE, NOSE_2, NOSE_3 = hx("#211F1D"), hx("#383632"), hx("#655D52")
EYE_RIM, IRIS, IRIS_L, PUPIL, GLINT = (hx(c) for c in ("#30271F", "#C68A32", "#E0B34E", "#171513", "#F2D68B"))
CLAW, CLAW_2 = hx("#30251F"), hx("#493329")

# leaf kinds: (outline, shade, body, light, midrib)
KIND = {
    "forest": (G_DEEP, G_DEEP, G_FOREST, G_MOSS, G_LEAF),
    "moss": (G_DEEP, G_FOREST, G_MOSS, G_LEAF, G_SAGE),
    "leaf": (G_FOREST, G_MOSS, G_LEAF, G_SAGE, G_YOUNG),
    "sage": (G_MOSS, G_LEAF, G_SAGE, G_YOUNG, G_YOUNG),
    "terra": (W_DARK, A_BROWN, A_TERRA, A_ORANGE, A_COPPER),
    "orange": (A_BROWN, A_TERRA, A_ORANGE, A_COPPER, A_OCHRE),
    "copper": (A_BROWN, A_ORANGE, A_COPPER, A_OCHRE, A_OCHRE),
    "ochre": (A_TERRA, A_COPPER, A_OCHRE, A_OCHRE, A_COPPER),
}
DARKER = {"forest": "forest", "moss": "forest", "leaf": "moss", "sage": "leaf", "terra": "terra", "orange": "terra",
          "copper": "orange", "ochre": "copper"}

# direction of the leaf tips in the picture of each face, for a box whose leaves point to +z (the tail)
BACK = {"east": (-1, 0), "west": (1, 0), "up": (0, 1), "down": (0, -1), "north": (0, 1), "south": (0, 1)}
DOWN = {"east": (0, 1), "west": (0, 1), "north": (0, 1), "south": (0, 1), "up": (0, 1), "down": (0, -1)}
# flanks: back and a little down
SLANT = dict(BACK, east=(-1, 0.45), west=(1, 0.45))


def pick(weights, n):
    """Weighted choice driven by a clump value n in [0, 1): neighbours sharing n share their tone."""
    total = sum(w for _, w in weights)
    acc = 0.0
    for kind, w in weights:
        acc += w / total
        if n < acc:
            return kind
    return weights[-1][0]


def leaf_field(w, h, r, face, dirs, chooser, L=9.0, W=2.6, ground_tone=G_FOREST, clump=(9, 8)):
    """Leaves lying on each other like roof shingles, tips along dirs[face]. Each leaf: a pointed blade with a dark
    outline, a shaded edge, sometimes a lit half and a midrib. Leaves nearer the root are drawn last, over the next
    ones. Tones come in clumps (one clump value per cell), so the picture reads as groups of leaves."""
    dx, dy = dirs[face]
    n = math.hypot(dx, dy)
    dx, dy = dx / n, dy / n
    qx, qy = -dy, dx
    g = [[ground_tone] * w for _ in range(h)]
    clump = (clump[0] * 2, clump[1] * 2)
    pts = [(x * dx + y * dy, x * qx + y * qy) for x in (0, w) for y in (0, h)]
    a0, a1 = min(p[0] for p in pts) - L, max(p[0] for p in pts) + 2
    c0, c1 = min(p[1] for p in pts) - W - 1, max(p[1] for p in pts) + W + 1
    step_a, step_c = L * 0.55, 2 * W + 1.0
    cells = {}
    leaves = []
    i = 0
    a = a0
    while a < a1:
        c = c0 + (step_c / 2 if i % 2 else 0.0)
        while c < c1:
            la, lc = a + r.uniform(-0.7, 0.7), c + r.uniform(-0.6, 0.6)
            leaves.append((la, lc, r.random(), r.random()))
            c += step_c
        a += step_a
        i += 1
    leaves.sort(key=lambda t: -t[0])
    darker = face == "down"
    for la, lc, v1, v2 in leaves:
        px, py = la * dx + lc * qx, la * dy + lc * qy
        mx, my = px + dx * L / 2, py + dy * L / 2
        u, v = min(1.0, max(0.0, mx / w)), min(1.0, max(0.0, my / h))
        key = (int(mx // clump[0]), int(my // clump[1]))
        if key not in cells:
            cells[key] = r.random()
        kind = chooser(face, u, v, cells[key])
        if kind is None:
            continue
        if darker:
            kind = DARKER[kind]
        shade, body, light = KIND[kind][1:4]
        veined, edged = v2 < 0.5, W >= 2.5
        for y in range(max(0, int(py - L - 2)), min(h, int(py + L + 3))):
            for x in range(max(0, int(px - L - 2)), min(w, int(px + L + 3))):
                ox, oy = x + 0.5 - px, y + 0.5 - py
                al, ac = ox * dx + oy * dy, ox * qx + oy * qy
                t = al / L
                if t < -0.05 or t > 1.12:
                    continue
                tt = min(1.0, max(0.0, t))
                half = W * (tt / 0.3 if tt < 0.3 else 1 - ((tt - 0.3) / 0.7) ** 1.6)
                if abs(ac) > half + 0.8:
                    continue
                if abs(ac) > half or t > 1.0 or t < 0:
                    if t > 0.3:
                        g[y][x] = shade        # edge of the blade, shadow on the leaf below
                    continue
                if veined and abs(ac) < 0.5 and 0.15 < t < 0.7:
                    c = light                  # midrib
                elif edged and ac > half - 1.1:
                    c = shade                  # the side of the blade away from the light
                else:
                    c = body
                g[y][x] = c
    return g


def teeth(g, edges, period=6, depth=3, phase=0):
    """Pointed cut-out along picture edges: leaf tips sticking out of the box outline."""
    h, w = len(g), len(g[0])

    def d(i):
        k = (i + phase) % period
        return int(round(depth * abs(k - period / 2) / (period / 2)))
    for x in range(w):
        for j in range(d(x)):
            if "top" in edges and j < h:
                g[j][x] = CLEAR
            if "bottom" in edges and h - 1 - j >= 0:
                g[h - 1 - j][x] = CLEAR
    for y in range(h):
        for j in range(d(y)):
            if "left" in edges and j < w:
                g[y][j] = CLEAR
            if "right" in edges and w - 1 - j >= 0:
                g[y][w - 1 - j] = CLEAR
    return g


# picture edge that faces the tail (+z) on each face
REAR_EDGE = {"east": "left", "west": "right", "up": "bottom", "down": "top"}


def leafy(dirs, chooser, cut=None, **kw):
    """Leaf covered box. cut: {face: edges} or a function face -> edges, edges cut into leaf tips."""
    def f(w, h, r, face):
        g = leaf_field(w, h, r, face, dirs, chooser, **kw)
        e = cut(face) if callable(cut) else (cut or {}).get(face)
        if e:
            teeth(g, e, phase=r.randrange(6))
        return g
    return f


def mirrored(p):
    swap = {"east": "west", "west": "east"}

    def f(w, h, r, face):
        return [row[::-1] for row in p(w, h, r, swap.get(face, face))]
    return f


def wood_grid(w, h, r, vertical=True, base=2):
    """Bark: long strokes along the grain, three browns, a dark groove now and then, rare light fibre."""
    ramp = [W_DARK, W_BARK, W_MAIN, W_LIGHT, W_SHINE]
    n, m = (w, h) if vertical else (h, w)
    lines = []
    tone = base
    for i in range(n):
        if r.random() < 0.45:
            tone = max(1, min(3, base + r.choice((-1, 0, 0, 1))))
        col = [tone] * m
        if r.random() < 0.22:
            col = [0] * m                                  # groove
        elif r.random() < 0.3:
            s = r.randrange(m)
            for j in range(s, min(m, s + r.choice((3, 4, 5)))):
                col[j] = min(4, tone + 1)                  # light fibre
        lines.append(col)
    if vertical:
        return [[ramp[lines[x][y]] for x in range(w)] for y in range(h)]
    return [[ramp[lines[y][x]] for x in range(w)] for y in range(h)]


def wood(vertical_sides=True, base=2):
    def f(w, h, r, face):
        b = base - 1 if face == "down" else base
        if face in ("up", "down"):
            return wood_grid(w, h, r, True, b)
        return wood_grid(w, h, r, vertical_sides, b)
    return f


def over(g, top):
    """Lay `top` over g wherever top has a colour (None = keep g)."""
    for y in range(len(g)):
        for x in range(len(g[0])):
            if top[y][x] is not None:
                g[y][x] = top[y][x]
    return g


# ---------------------------------------------------------------- tone choosers (face, u, v, clump) -> kind
def c_back(face, u, v, n):
    """Trunk. Spine and upper flanks dark, flanks lighter, autumn leaves in a band on the flanks, shaded belly line."""
    if face == "up":
        mid = abs(u - 0.5)
        if mid < 0.2:
            return pick((("forest", 6), ("moss", 3)), n)
        return pick((("moss", 6), ("forest", 2), ("leaf", 1.2), ("orange", 0.8)), n)
    if face == "down":
        return pick((("moss", 5), ("forest", 5)), n)
    if face in ("east", "west"):
        if v < 0.25:
            return pick((("forest", 4), ("moss", 5)), n)
        if v < 0.72:
            return pick((("moss", 4), ("leaf", 3), ("orange", 1.6), ("terra", 1.2), ("sage", 0.8)), n)
        return pick((("moss", 4), ("forest", 4), ("terra", 1)), n)
    return pick((("moss", 5), ("forest", 4)), n)


def c_loins(face, u, v, n):
    if face in ("east", "west") and 0.2 < v < 0.75:
        return pick((("moss", 5), ("leaf", 3), ("copper", 1.2), ("orange", 1.4), ("sage", 1)), n)
    return c_back(face, u, v, n)


def c_spine(face, u, v, n):
    return pick((("forest", 5), ("moss", 4), ("leaf", 1), ("copper", 0.9)), n)


def c_chest(face, u, v, n):
    return pick((("ochre", 3), ("sage", 4), ("leaf", 2), ("copper", 1)), n)


def c_neck(face, u, v, n):
    if face == "up":
        return pick((("forest", 5), ("moss", 5)), n)
    return pick((("moss", 5), ("forest", 3), ("terra", 1.5)), n)


def c_mane_top(face, u, v, n):
    return pick((("moss", 5), ("forest", 4), ("leaf", 2), ("ochre", 0.7)), n)


def c_mane_side(face, u, v, n):
    return pick((("moss", 3), ("orange", 4), ("terra", 2.5), ("copper", 1.5)), n)


def c_mane_side_l(face, u, v, n):
    return pick((("moss", 3.5), ("orange", 3.5), ("terra", 2), ("ochre", 1.2)), n)


def c_skull(face, u, v, n):
    if face == "up":
        return pick((("forest", 5), ("moss", 5)), n)
    if face in ("east", "west"):
        if v > 0.55:
            return pick((("orange", 4), ("terra", 3), ("moss", 3)), n)
        return pick((("moss", 5), ("forest", 4)), n)
    if face == "north":
        if v > 0.55 and abs(u - 0.5) > 0.28:
            return pick((("orange", 4), ("terra", 3), ("moss", 2)), n)
        return pick((("moss", 5), ("forest", 5)), n)
    return pick((("moss", 5), ("forest", 5)), n)


def c_cheek(face, u, v, n):
    return pick((("orange", 5), ("terra", 3), ("copper", 2)), n)


def c_cheek_l(face, u, v, n):
    return pick((("orange", 4), ("terra", 2), ("copper", 2), ("ochre", 2.5)), n)


def c_muzzle(face, u, v, n):
    if face == "up":
        return pick((("forest", 7), ("moss", 2)), n)
    if face == "down":
        return "forest"
    return pick((("moss", 6), ("forest", 3)), n)


def c_leg(face, u, v, n):
    if v < 0.34:
        return pick((("moss", 5), ("leaf", 4)), n)
    if v < 0.7:
        return pick((("forest", 6), ("moss", 2)), n)
    return None


def c_thigh(face, u, v, n):
    if v < 0.55:
        return pick((("moss", 5), ("leaf", 4), ("orange", 1.2)), n)
    return pick((("forest", 5), ("moss", 3)), n)


def c_shank(face, u, v, n):
    return pick((("forest", 6), ("moss", 2)), n) if v < 0.5 else None


def c_tail(seg):
    def f(face, u, v, n):
        side = face in ("east", "west")
        if seg == 1:
            return pick((("forest", 6), ("moss", 3)), n)
        if seg == 2:
            return pick((("moss", 6), ("forest", 2), ("leaf", 2), ("orange", 1.4 if side else 0.4)), n)
        if seg == 3:
            if face == "west":         # the fox's left: lighter leaves on that side only
                return pick((("copper", 4), ("ochre", 3), ("sage", 3), ("orange", 2)), n)
            if side:
                return pick((("orange", 5), ("terra", 2.5), ("sage", 1.5), ("leaf", 1.5)), n)
            if face == "down":
                return pick((("moss", 4), ("terra", 3), ("orange", 2)), n)
            return pick((("sage", 5), ("leaf", 4), ("moss", 2), ("orange", 1.2)), n)
        if seg == 4:
            return pick((("orange", 5), ("copper", 4), ("terra", 2), ("sage", 1.2)), n)
        return pick((("copper", 5), ("ochre", 5)), n)
    return f


def c_tail_top(face, u, v, n):
    return pick((("sage", 4), ("leaf", 4), ("moss", 2), ("ochre", 1)), n)


# ---------------------------------------------------------------- head
# right eye, columns from the outer edge of the face to the inner corner: narrow, the lid falling towards the muzzle
# (angry), amber iris, slit pupil over its whole height, one glint
EYE = ("RRR....",
       "IGPRR..",
       "IIPIIRR",
       "RIPILLR",
       ".RRRRR.")
EYE_ROW = 6
EYE_TONE = {"R": EYE_RIM, "I": IRIS, "L": IRIS_L, "P": PUPIL, "G": GLINT}


def skull(w, h, r, face):
    """Skull. Front: small tight leaves, both eyes painted (almond, slanted, amber iris, slit pupil, one glint) under
    a dark leaf brow line, a small copper leaf with a gold midrib on the forehead. The outer corner of each eye wraps
    on the side face so the look still reads in profile."""
    g = leaf_field(w, h, r, face, BACK, c_skull, L=7.0, W=2.2, clump=(7, 6))
    if face == "north":
        assert (w, h) == (22, 21), (w, h)
        for y in range(3, 13):                       # dark leaves around the eyes: the amber stands out
            for x in range(w):
                if g[y][x] not in (G_FOREST, G_DEEP):
                    g[y][x] = G_FOREST if g[y][x] == G_MOSS else G_MOSS if g[y][x] == G_LEAF else G_FOREST
        for r_, line in enumerate(EYE):
            for i, ch in enumerate(line):
                if ch != ".":
                    c = 9 - i
                    g[EYE_ROW + r_][10 - c] = g[EYE_ROW + r_][11 + c] = EYE_TONE[ch]
        for i in range(7):                           # brow shadow right above the lid
            c = 9 - i
            top = EYE_ROW + min(r_ for r_, line in enumerate(EYE) if line[i] != ".")
            g[top - 1][10 - c] = g[top - 1][11 + c] = G_DEEP
        # forehead mark: one small copper leaf, gold midrib
        for y, half in ((1, 0), (2, 1), (3, 1), (4, 0)):
            for x in range(11 - half - 1, 11 + half + 1):
                g[y][x] = A_COPPER
        g[2][10] = g[3][10] = A_OCHRE
    elif face in ("east", "west"):
        cols = (w - 1, w - 2) if face == "east" else (0, 1)   # front edge of the picture
        t = EYE_ROW
        g[t - 1][cols[0]] = g[t - 1][cols[1]] = G_DEEP
        g[t][cols[0]] = EYE_RIM
        g[t + 1][cols[0]] = g[t + 2][cols[0]] = IRIS
        g[t + 3][cols[0]] = EYE_RIM
        g[t + 1][cols[1]] = g[t + 2][cols[1]] = EYE_RIM
    elif face == "up":
        # the forehead leaf goes on over the top edge
        for y, half in ((0, 1), (1, 1), (2, 0)):
            for x in range(11 - half - 1, 11 + half + 1):
                g[y][x] = A_COPPER
        g[0][10] = g[1][10] = A_OCHRE
    return g


def brow(w, h, r, face):
    """Brow ridge: two or three dark leaves lying towards the temple, darkest underneath."""
    if face == "down":
        return [[G_DEEP] * w for _ in range(h)]
    g = leaf_field(w, h, r, face, {"north": (-1, 0), "south": (1, 0), "up": (-1, 0), "east": (0, 1), "west": (0, 1)},
                   lambda f_, u, v, n: "forest", L=7.0, W=2.0)
    if face in ("north", "south", "east", "west"):
        for x in range(w):
            g[h - 1][x] = G_DEEP
    return g


def muzzle(tip):
    def f(w, h, r, face):
        g = leaf_field(w, h, r, face, BACK, c_muzzle, L=5.0, W=1.7, clump=(5, 5))
        if face in ("east", "west"):
            for x in range(w):                       # mouth line, shadow below it
                g[h - 3][x] = G_DEEP
                g[h - 2][x] = g[h - 1][x] = G_FOREST if (x // 2) % 2 else G_DEEP
        if face == "north":
            for y in range(h - 3, h):
                for x in range(w):
                    g[y][x] = G_DEEP if y == h - 3 else G_FOREST
            if tip:
                g[h - 4][w // 2 - 1] = g[h - 4][w // 2] = G_DEEP   # philtrum under the nose
        return g
    return f


def nose(w, h, r, face):
    """Small black nose, triangular by cut-out (bottom corners), one dim glint on top."""
    g = [[NOSE] * w for _ in range(h)]
    if face == "up":
        g = [[NOSE_2] * w for _ in range(h)]
        g[0][1] = g[0][2] = NOSE_3
    elif face == "north":
        assert (w, h) == (5, 4), (w, h)
        g[0] = [NOSE_2] * w
        g[2][0] = g[2][4] = CLEAR
        g[3][0] = g[3][1] = g[3][3] = g[3][4] = CLEAR
    elif face in ("east", "west"):
        for x in range(w):
            g[h - 1][x] = CLEAR
    elif face == "down":
        g = [[CLEAR] * w for _ in range(h)]
    return g


def ear(w, h, r, face):
    """Ear = one big pointed leaf standing up. Front (north): autumn inside, green rim. Back: green blade with a
    midrib. Only front and back carry the triangle (a plate), the base box below gives it its thickness."""
    if face not in ("north", "south"):
        return [[CLEAR] * w for _ in range(h)]
    g = []
    for y in range(h):
        half = 0.6 + (y + 1) / h * (w / 2 - 0.6)
        row = []
        for x in range(w):
            d = half - abs(x + 0.5 - w / 2)
            off = x + 0.5 - w / 2
            if d < 0:
                row.append(CLEAR)
            elif face == "north":
                if d < 1.0 or y < 2:
                    row.append(G_FOREST)
                elif d < 2.0:
                    row.append(G_MOSS)
                elif abs(off) < 0.6 and y > 5:
                    row.append(A_COPPER)                      # midrib
                elif y > h * 0.62:
                    row.append(A_TERRA)
                else:
                    row.append(A_ORANGE if off < 0 else A_TERRA if d < 3 else A_ORANGE)
            else:
                if d < 1.0:
                    row.append(G_DEEP)
                elif abs(off) < 0.6 and y > 2:
                    row.append(G_LEAF)
                elif off > 0:
                    row.append(G_FOREST)
                else:
                    row.append(G_MOSS)
            # noqa
        g.append(row)
    return g


def ear_base(w, h, r, face):
    g = leaf_field(w, h, r, face, {**BACK, "north": (0, -1), "south": (0, -1), "east": (0, -1), "west": (0, -1)},
                   lambda f_, u, v, n: pick((("forest", 5), ("moss", 5)), n), L=6.0, W=2.0)
    if face == "north":
        for y in range(1, h):
            for x in range(2, w - 2):
                g[y][x] = A_TERRA if y > 1 else A_BROWN
    return g


# ---------------------------------------------------------------- legs
def leg(chooser, wood_base=2):
    """Leaves at the top, darker ones lower, bare wood at the bottom: the leaves end in tips over the wood."""
    def f(w, h, r, face):
        if face == "down":
            return wood_grid(w, h, r, True, 1)
        g = wood_grid(w, h, r, True, wood_base)
        if face == "up":
            return leaf_field(w, h, r, face, DOWN, lambda *a: "moss", L=6.0, W=2.0)
        top = leaf_field(w, h, r, face, DOWN, chooser, L=7.0, W=2.0, ground_tone=None, clump=(5, 6))
        over(g, top)
        for y in range(int(h * 0.3)):               # nothing but leaves at the top
            for x in range(w):
                if g[y][x] in (W_DARK, W_BARK, W_MAIN, W_LIGHT, W_SHINE):
                    g[y][x] = G_DEEP
        return g
    return f


def paw(w, h, r, face):
    """Small wooden paw: toes parted by dark grooves, a dark claw point at the end of each toe."""
    if face == "down":
        return [[W_DARK] * w for _ in range(h)]
    grooves = [x for x in range(w) if x % 3 == 2 and x < w - 1]
    if face == "up":
        g = [[W_BARK] * w for _ in range(h)]
        for y in range(h):
            for x in range(w):
                if y < 2 and x not in grooves:
                    g[y][x] = W_MAIN                # toes catch the light
                if y < h * 0.6 and x in grooves:
                    g[y][x] = W_DARK
        for x in range(w):
            if x not in grooves and (x % 3 == 0 or w < 6):
                g[0][x] = CLAW
        for x in range(w):                           # ankle shadow
            g[h - 1][x] = W_BARK
        return g
    g = wood_grid(w, h, r, False, 1)
    if face == "north":
        g = [[W_BARK] * w for _ in range(h)]
        for y in range(h):
            for x in grooves:
                g[y][x] = W_DARK
        for x in range(w):
            if x not in grooves:
                g[h - 1][x] = CLAW
                g[h - 2][x] = CLAW_2 if x % 3 == 0 else W_BARK
    for x in range(w):
        if face != "north":
            g[h - 1][x] = W_DARK
    return g


def branch(w, h, r, face):
    return wood_grid(w, h, r, face in ("up", "down"), 2 if face == "up" else 1)


def tail_root(w, h, r, face):
    """First tail segment: bare wood at the root, forest leaves taking over towards the tail."""
    if face in ("north", "south"):
        return wood_grid(w, h, r, True, 1)
    g = wood_grid(w, h, r, face in ("up", "down"), 1 if face == "down" else 2)

    def ch(f_, u, v, n):
        along = {"east": 1 - u, "west": u, "up": v, "down": 1 - v}[f_]
        return pick((("forest", 6), ("moss", 3)), n) if along > 0.3 else None
    return over(g, leaf_field(w, h, r, face, BACK, ch, L=7.0, W=2.2, ground_tone=None))


# ---------------------------------------------------------------- materials
def rear_cut(*more):
    return lambda face: ((REAR_EDGE[face],) + more) if face in REAR_EDGE else None


def plate_cut(face):
    """A lying shingle: rear and both side edges of the top in tips, lower edge of the sides too."""
    if face == "up":
        return ("bottom", "left", "right")
    if face in ("east", "west", "south"):
        return ("bottom",)
    return None


def side_plate_cut(face):
    """A standing shingle (mane sides, tail sides): rear and lower edges in tips."""
    if face in ("east", "west"):
        return (REAR_EDGE[face], "bottom")
    if face in ("up", "down"):
        return (REAR_EDGE[face],)
    return ("bottom",) if face == "south" else None


BIG = dict(L=11.0, W=3.2, clump=(10, 10))
MATS = {name: (p, D) for name, p in {
    "thorax": leafy(SLANT, c_back, L=10.0, W=3.0, clump=(10, 9)),
    "loins": leafy(SLANT, c_loins, L=10.0, W=3.0, clump=(10, 9)),
    "spine": leafy(BACK, c_spine, cut=plate_cut, L=10.0, W=3.0),
    "chest": leafy(DOWN, c_chest, cut={"north": ("bottom",), "east": ("bottom",), "west": ("bottom",)}, L=9.0, W=2.8),
    "neck": leafy(BACK, c_neck, L=8.0, W=2.6),
    "mane_top": leafy(BACK, c_mane_top, cut=plate_cut, L=10.0, W=3.0),
    "mane_r": leafy(SLANT, c_mane_side, cut=side_plate_cut, L=10.0, W=3.0),
    "mane_l": leafy(SLANT, c_mane_side_l, cut=side_plate_cut, L=10.0, W=3.0),
    "skull": skull,
    "cheek_r": leafy(SLANT, c_cheek, cut=side_plate_cut, L=8.0, W=2.6),
    "cheek_l": leafy(SLANT, c_cheek_l, cut=side_plate_cut, L=8.0, W=2.6),
    "brow_r": brow, "brow_l": mirrored(brow),
    "muzzle": muzzle(False), "muzzle_tip": muzzle(True), "nose": nose,
    "ear": ear, "ear_l": mirrored(ear), "ear_base": ear_base,
    "leg_front": leg(c_leg, 1), "thigh": leafy(DOWN, c_thigh, L=8.0, W=2.6), "shank": leg(c_shank, 1), "paw": paw,
    "wood": wood(False), "branch": branch,
    "tail_1": tail_root,
    "tail_2": leafy(BACK, c_tail(2), cut=rear_cut(), **BIG),
    "tail_3": leafy(BACK, c_tail(3), cut=rear_cut(), **BIG),
    "tail_4": leafy(BACK, c_tail(4), cut=rear_cut(), **BIG),
    "tail_5": leafy(BACK, c_tail(5), cut=rear_cut(), L=9.0, W=2.8),
    "tail_top": leafy(BACK, c_tail_top, cut=plate_cut, **BIG),
}.items()}
GLOW = {}

# ---------------------------------------------------------------- geometry
NECK = (0.0, 24.0, -16.5)
HEAD_R = rot(-5, 0, 0)                  # head carried low: 5 degrees nose down
MUZZLE_PIVOT = (0.0, 23.0, -26.0)
MUZZLE_R = rot(-4, 0, 0)                # muzzle 4 degrees further down: 9 degrees to the ground
TAIL_ROOT = (0.0, 21.5, 10.0)
# tail segments: length, rise (degrees above horizontal), yaw, width, height
TAIL = ((6.5, 22, 0, 6.5, 6.5), (8.5, 40, 3, 9.5, 9.0), (10.5, 52, -3, 11.5, 10.5), (8.0, 28, 4, 9.0, 8.5),
        (6.5, -6, 6, 5.5, 5.0))
TAIL_OVERLAP = (1.8, 3.0, 4.0, 3.0)
TAIL_BONES = ("Tail_base", "Tail_mid", "Tail_mid", "Tail_tip", "Tail_tip")

GROUPS = {"Root": ((0, 0, 0), None), "Body": ((0, 19, 0), "Root"), "Neck": ((0, 22.5, -11.5), "Body"),
          "h_Head": (NECK, "Neck"),
          "Ear_right": ((3.3, 29.5, -18.6), "h_Head"), "Ear_left": ((-3.3, 29.5, -18.6), "h_Head"),
          "Leg_front_right": ((4.4, 13.5, -8.6), "Body"), "Leg_front_left": ((-4.4, 13.5, -8.6), "Body"),
          "Paw_front_right": ((4.4, 2.4, -8.6), "Leg_front_right"),
          "Paw_front_left": ((-4.4, 2.4, -8.6), "Leg_front_left"),
          "Leg_back_right": ((4.9, 18.0, 7.5), "Body"), "Leg_back_left": ((-4.9, 18.0, 7.5), "Body"),
          "Paw_back_right": ((4.9, 10.2, 5.6), "Leg_back_right"), "Paw_back_left": ((-4.9, 10.2, 5.6), "Leg_back_left"),
          "Tail_base": (TAIL_ROOT, "Body"), "Tail_mid": (None, "Tail_base"), "Tail_tip": (None, "Tail_mid")}

BUDGET = {"head": 12, "neck": 4, "trunk": 5, "legs": 10, "tail": 6, "wood": 2}


def parts():
    P = []
    count = {k: 0 for k in BUDGET}

    def add(part, g, name, c, size, r, mat):
        count[part] += 1
        P.append((g, name, list(c), list(size), r, mat))

    # ------------------------------------------------ trunk: deep chest, narrower loins dropping a little
    add("trunk", "Body", "thorax", (0, 18.75, -6.25), (13.5, 13.5, 12.5), (0, 0, 0), "thorax")
    add("trunk", "Body", "arriere", (0, 19.3, 5.2), (11.0, 11.0, 12.4), (4, 0, 0), "loins")
    # leaves of the spine: two shingles, rear edge lifted
    add("trunk", "Body", "dos_1", (0, 26.3, -5.4), (9.0, 2.5, 10.0), (-13, 0, 3), "spine")
    add("trunk", "Body", "dos_2", (0, 25.6, 4.4), (7.5, 2.5, 9.5), (-12, 0, -4), "spine")
    add("trunk", "Body", "poitrine", (0, 17.4, -13.0), (8.5, 8.0, 2.5), (-12, 0, 0), "chest")
    # one thin branch along the spine, showing between the two shingles
    add("wood", "Body", "branche", (1.0, 27.1, 0.4), (1.5, 1.5, 11.0), (-9, 7, 0), "branch")

    # ------------------------------------------------ neck and mane
    add("neck", "Neck", "cou", (0, 22.8, -14.3), (7.5, 8.5, 8.0), (15, 0, 0), "neck")
    add("neck", "Neck", "criniere_dessus", (0, 28.0, -12.6), (10.5, 2.5, 9.5), (-24, 0, 0), "mane_top")
    for s, sd in ((1, "d"), (-1, "g")):
        add("neck", "Neck", f"criniere_{sd}", (4.9 * s, 23.4, -12.2), (2.0, 7.5, 8.0), (-26, 14 * s, -6 * s),
            "mane_r" if s == 1 else "mane_l")

    # ------------------------------------------------ head: built level, then leaned around the neck
    head = attach(NECK, HEAD_R)
    snout = attach(MUZZLE_PIVOT, MUZZLE_R)

    def hadd(g, name, c, size, r, mat, muzzle_=False):
        if muzzle_:
            c, r = snout(c, r)
        c, M = head(c, r)
        add("head", g, name, c, size, M, mat)

    hadd("h_Head", "crane", (0, 25.0, -21.5), (11.0, 10.5, 9.5), (0, 0, 0), "skull")
    for s, sd in ((1, "d"), (-1, "g")):
        hadd("h_Head", f"joue_{sd}", (6.0 * s, 22.2, -19.4), (3.0, 6.0, 7.0), (-8, 18 * s, -8 * s),
             "cheek_r" if s == 1 else "cheek_l")
        # brow: inner end lower (the two meet in a V over the muzzle), overhanging the eye
        hadd("h_Head", f"sourcil_{sd}", (3.1 * s, 29.3, -26.2), (6.0, 1.5, 2.5), (0, 0, 12 * s),
             "brow_r" if s == 1 else "brow_l")
    hadd("h_Head", "museau", (0, 22.5, -28.1), (6.0, 4.5, 4.5), (0, 0, 0), "muzzle", True)
    hadd("h_Head", "museau_bout", (0, 22.2, -31.7), (4.5, 3.5, 3.5), (0, 0, 0), "muzzle_tip", True)
    hadd("h_Head", "nez", (0, 23.3, -33.7), (2.5, 2.0, 1.0), (0, 0, 0), "nose", True)
    # ears: a thick base, then the leaf blade; right 8 degrees outwards, left 12 (brief: slight asymmetry)
    hadd("Ear_right", "oreille_base_d", (3.6, 30.4, -18.6), (5.0, 2.5, 3.0), (0, -32, -6), "ear_base")
    hadd("Ear_left", "oreille_base_g", (-3.6, 30.4, -18.6), (5.0, 2.5, 3.0), (0, 32, 9), "ear_base")
    hadd("Ear_right", "oreille_d", (3.9, 34.6, -18.5), (7.0, 8.0, 1.5), (-4, -32, -8), "ear")
    hadd("Ear_left", "oreille_g", (-4.1, 34.5, -18.5), (7.0, 8.0, 1.5), (0, 32, 12), "ear_l")

    # ------------------------------------------------ legs: thin and long
    for s, sd, side in ((1, "d", "right"), (-1, "g", "left")):
        add("legs", f"Leg_front_{side}", f"patte_avant_{sd}", (4.4 * s, 7.6, -8.6), (3.5, 12.0, 4.0), (3, 0, 0),
            "leg_front")
        add("legs", f"Paw_front_{side}", f"pied_avant_{sd}", (4.4 * s, 1.0, -9.8), (4.0, 2.0, 5.5), (0, 0, 0), "paw")
        add("legs", f"Leg_back_{side}", f"cuisse_{sd}", (4.9 * s, 14.2, 6.6), (4.5, 9.5, 7.0), (18, 0, 0), "thigh")
        add("legs", f"Paw_back_{side}", f"jarret_{sd}", (4.9 * s, 6.0, 7.2), (3.0, 9.5, 3.5), (-16, 0, 0), "shank")
        add("legs", f"Paw_back_{side}", f"pied_arriere_{sd}", (4.9 * s, 1.0, 7.4), (4.0, 2.0, 5.5), (0, 0, 0), "paw")
    # one small root curling out of the right hock
    add("wood", "Paw_back_right", "racine_jarret", (6.7, 9.6, 6.4), (1.0, 1.0, 5.0), (38, 24, 12), "wood")

    # ------------------------------------------------ tail: a comma, thin at the root, bushy, tip falling back
    p = list(TAIL_ROOT)
    for i, (ln, rise, yaw, tw, th) in enumerate(TAIL, 1):
        a, b = math.radians(rise), math.radians(yaw)
        d = [math.sin(b) * math.cos(a), math.sin(a), math.cos(b) * math.cos(a)]
        c = [p[k] + d[k] * ln / 2 for k in range(3)]
        bone = TAIL_BONES[i - 1]
        if GROUPS[bone][0] is None:
            GROUPS[bone] = (list(p), GROUPS[bone][1])
        add("tail", bone, f"queue_{i}", c, (tw, th, ln), (-rise, yaw, 0), f"tail_{i}")
        if i == 3:
            # leaf layers of the bush: one lying on top, one standing on each side, rear edges spread out
            bush = attach(c, rot(-rise, yaw, 0))
            for name, lc, size, lr, mat in (
                    ("queue_feuilles_dessus", (0, th / 2 + 0.4, 0.6), (9.0, 2.0, 9.5), (-10, 0, 0), "tail_top"),):
                pc, pr = bush([c[k] + lc[k] for k in range(3)], lr)
                add("tail", bone, name, pc, size, pr, mat)
        if i < len(TAIL):
            end = [p[k] + d[k] * ln for k in range(3)]
            p = [end[k] - d[k] * TAIL_OVERLAP[i - 1] for k in range(3)]

    assert count == BUDGET, count
    assert 20 <= len(P) <= 40, len(P)
    return P


def build():
    return ground(parts(), GROUPS, center=True)


def face_closeup(data, atlases, sizes, path, size=440):
    """Head alone from the front, three quarters and profile, large: checks the expression."""
    import paths  # noqa: F401
    import apercu as A
    from png_util import write
    head = {"crane", "joue", "museau", "oreille", "sourcil", "nez"}
    els = [e for e in flat_elements(data, sizes) if e["name"].split("_")[0] in head]
    combo = combined(atlases, sizes)
    bg = (58, 58, 62, 255)
    imgs = [A.render(els, combo, yaw, pitch, size=size, bg=bg, margin=24) for yaw, pitch in ((0, 4), (35, 10), (90, 2))]
    write(path, size * 3, size, [imgs[0][y] + imgs[1][y] + imgs[2][y] for y in range(size)])


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
    if "--mesures" in args:
        for name, (pv, parent) in groups.items():
            print(f"{name:16} {parent or '-':16} {[round(v, 1) for v in pv]}")
    print(f"{len(P)} cubes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (large x haut x long) ; "
          f"texture {sizes[0]}")
    if "--apercu" in opts:
        # brief: front, profile, back, three quarters (plus rear three quarters), then the silhouette
        apercu(data, atlases, sizes, opts["--apercu"], set(),
               views=((35, 14), (0, 4), (90, 2), (180, 6), (215, 16)), size=440)
    if "--visage" in opts:
        face_closeup(data, atlases, sizes, opts["--visage"])
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
