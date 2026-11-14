#!/usr/bin/env python3
"""Devouring Flower (La Fleur Dévoreuse): boss of a vegetation / herbalism dungeon, STATIC Blockbench model (2026-11-14).

A giant carnivorous flower, entirely botanical (no eyes, no arms): five big coral petals around a really hollow
throat lined with ivory thorns, a pistil tongue hanging on one side, a collar of five spotted inner petals, five green
sepals behind, an S shaped stem leaning towards the player, two unequal leaves, a scaly bulb and five creeping roots.

Brief: 48 boxes (base 10, stem 3, leaves 4, head 31), about 3.5 x 4.4 x 3.9 blocks. Every outline of a flat piece
(petal, leaf, sepal) comes from cut-out pixels; veins, spots, throat folds, scales and stem thorns are painted.
Painted at 2 texels per model unit (32 per block) everywhere.

Usage: fleur_devoreuse.py [output folder] [--apercu <sheet.png>] [--gueule <mouth.png>]
Front towards north (-z), the flower's right towards +x, 16 units = 1 block.
"""
import json
import math
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, bake, combined, export, flat_elements, ground, hx  # noqa: E402
from golem_fonderie import app, mul, rot  # noqa: E402

IDENT = "fleur_devoreuse"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000f1e0e01")
D = 2  # texels per model unit, every material

# ---------------------------------------------------------------- palette (brief, section 4)
IVORY_PINK, PEACH_L, CORAL, RASP, POWDER, OLD_ROSE = (hx(c) for c in ("#F7DCC8", "#F0B79A", "#D9826B", "#A8475A",
                                                                         "#D8A5A0", "#B07C7E"))
IVORY_W, PEACH = hx("#F3E3C7"), hx("#EDAE8C")
WET, BORDEAUX, PLUM, NIGHT = hx("#C96A78"), hx("#6E2A3C"), hx("#3A1424"), hx("#1F0A14")
TIP, HORN, AMBER = hx("#FBF4E0"), hx("#EFE2C2"), hx("#C9A66B")
SAFFRON, SAFFRON_D = hx("#D9A94A"), hx("#A87A2E")
SAGE_L, SAGE, MOSS, MOSS_D, OLIVE_P = (hx(c) for c in ("#B5C2A0", "#8FA283", "#5F7345", "#3F5030", "#9A9A62"))
OLIVE, BULB, BULB_L, ROOT, EARTH = (hx(c) for c in ("#6B6A3A", "#5C2A35", "#7A3B46", "#5A4F32", "#3A3222"))
CLEAR = (0, 0, 0, 0)


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def shade(c, f):
    return tuple(max(0, min(255, round(v * f))) for v in c[:3])


def grad(stops, t, steps=12):
    """Colour of a ramp of (position, colour) stops at t, quantised so a gradient is a run of close bands."""
    t = max(0.0, min(1.0, round(t * steps) / steps))
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        if t <= p1:
            return mix(c0, c1, 0 if p1 == p0 else (t - p0) / (p1 - p0))
    return stops[-1][1]


def noise(*v):
    """Deterministic value in [0, 1) from integer coordinates (spots, patches: same picture at every bake)."""
    n = 0
    for i, x in enumerate(v):
        n = (n * 73856093) ^ (int(x) * (19349663 + 83492791 * i))
    n = (n ^ (n >> 13)) * 1274126177
    return ((n ^ (n >> 16)) & 0xFFFF) / 65536.0


# ---------------------------------------------------------------- flat pieces (petal, sepal, leaf)
def blade(outline, front, back, v0=0.0, v1=1.0, rim=None, cut=None):
    """Painter of a flat piece grown along +y: outline(v) is its half width (0..1 of the box) at v along the whole
    piece (a piece made of two boxes shares one outline, each box covering v0..v1). north = front, south = back."""
    def f(w, h, r, face):
        g = [[CLEAR] * w for _ in range(h)]
        dv = (v1 - v0) / max(1, h)
        if face in ("north", "south"):
            for j in range(h):
                v = v0 + (v1 - v0) * (1 - (j + 0.5) / h)
                o = outline(v)
                lim = min(o, outline(v + dv), outline(v - dv) if v - dv > 0 else o)
                for k in range(w):
                    x = (1 - 2 * (k + 0.5) / w) if face == "north" else (2 * (k + 0.5) / w - 1)
                    if abs(x) > o or (cut and cut(x, v)):
                        continue
                    edge = abs(x) > lim - 2.0 / w or bool(cut and (cut(x + 2.4 / w, v) or cut(x, v + dv)
                                                               or cut(x, v - dv)))
                    g[j][k] = (front if face == "north" else back)(x, v, edge, o, w)
        elif face in ("east", "west"):
            for j in range(h):
                v = v0 + (v1 - v0) * (1 - (j + 0.5) / h)
                if outline(v) >= 0.94:
                    g[j] = [rim(v) if rim else back(0.9, v, True, 1.0, 32)] * w
        else:
            v = v0 if face == "down" else v1
            o = outline(min(0.999, max(0.001, v)))
            if face == "down" or v1 < 0.999:
                for k in range(w):
                    if abs(2 * (k + 0.5) / w - 1) <= o:
                        for j in range(h):
                            g[j][k] = rim(v) if rim else back(0.0, v, False, 1.0, 32)
        return g
    return f


def outer_outline(v):
    if v >= 1 or v < 0:
        return 0.0
    if v < 0.32:
        return 0.66 + 0.34 * math.sin(math.pi / 2 * v / 0.32)
    if v < 0.6:
        return 1.0
    return max(0.0, 1 - ((v - 0.6) / 0.4) ** 1.7)


OUTER_FRONT = ((0.0, mix(RASP, BORDEAUX, 0.55)), (0.16, RASP), (0.4, CORAL), (0.7, CORAL), (0.93, PEACH_L),
               (1.0, IVORY_PINK))
OUTER_BACK = ((0.0, OLD_ROSE), (0.3, POWDER), (1.0, mix(POWDER, IVORY_PINK, 0.55)))


def outer_front(x, v, edge, o, w):
    if edge:
        return IVORY_PINK if v > 0.3 else mix(CORAL, PEACH_L, 0.5)
    rel = abs(x) / max(o, 1e-3)
    if v < 0.86 and (abs(x) < 1.1 / w or any(abs(abs(x) - a * o) < 1.0 / w for a in (0.42, 0.76))):
        return grad(OUTER_FRONT, v - 0.2 + 0.05 * rel)                       # veins: one notch deeper than the petal
    # two soft patches (a little more peach, a little more raspberry), never pixel noise
    drift = 0.05 * math.sin(7.0 * v + 2.2 * x) + 0.1 * rel * rel
    return grad(OUTER_FRONT, v + drift)


def outer_back(x, v, edge, o, w):
    if edge:
        return mix(POWDER, IVORY_PINK, 0.6)
    if v < 0.74 and (abs(x) < 1.1 / w or any(abs(abs(x) - a * o) < 1.0 / w for a in (0.42, 0.76))):
        return mix(SAGE, POWDER, 0.25 + 0.5 * v)
    return grad(OUTER_BACK, v + 0.05 * math.sin(6.0 * v - 2.0 * x))


def tear(x, v):
    """V shaped tear on one edge of the 7 o'clock petal."""
    return x > 0 and abs(v - 0.56) < 0.085 and x > outer_outline(v) - 0.42 * (1 - abs(v - 0.56) / 0.085)


def inner_outline(v):
    if v >= 1 or v < 0:
        return 0.0
    if v < 0.55:
        return 0.8 + 0.2 * min(1.0, v / 0.35)
    return math.sqrt(max(0.0, 1 - ((v - 0.55) / 0.45) ** 2))


def inner_front(x, v, edge, o, w):
    if edge:
        return IVORY_W
    j, k = int(v * 40), int((x + 1) * w / 2)
    dens = 0.5 * (1 - v) ** 1.6
    if noise(j // 2, k // 2, 7) < dens and noise(j, k, 3) < 0.62:
        return RASP if v > 0.22 else mix(RASP, BORDEAUX, 0.6)                # tiger spots, dense near the throat
    return grad(((0.0, mix(PEACH, RASP, 0.35)), (0.2, PEACH), (0.6, mix(PEACH, IVORY_W, 0.45)), (1.0, IVORY_W)), v)


def inner_back(x, v, edge, o, w):
    if edge:
        return mix(PEACH, IVORY_W, 0.6)
    if abs(x) < 1.1 / w and v < 0.8:
        return mix(PEACH, OLD_ROSE, 0.6)
    return grad(((0.0, OLD_ROSE), (0.35, mix(PEACH, OLD_ROSE, 0.3)), (1.0, mix(PEACH, IVORY_W, 0.35))), v)


def sepal_outline(v):
    if v >= 1 or v < 0:
        return 0.0
    return 0.75 + 0.25 * v / 0.2 if v < 0.2 else max(0.0, 1 - (v - 0.2) / 0.8) ** 0.85


SEPAL = ((0.0, MOSS_D), (0.18, SAGE), (0.7, SAGE), (0.84, OLIVE), (1.0, BULB_L))


def sepal_side(x, v, edge, o, w):
    if edge:
        return grad(SEPAL, v - 0.1) if v > 0.7 else MOSS
    if abs(x) < 1.1 / w and v < 0.8:
        return SAGE_L
    return grad(SEPAL, v)


def leaf_outline(v):
    if v >= 1 or v < 0:
        return 0.0
    o = max(0.16 if v < 0.12 else 0.0, math.sin(math.pi * v ** 0.72) ** 0.8)
    return o * (1 - 0.07 * (int(v * 26) % 2))                                # faintly toothed edge


def leaf(length_px, bites=False):
    def front(x, v, edge, o, w):
        rel = abs(x) / max(o, 1e-3)
        if bites and x > 0 and rel > 0.72 and noise(int(v * 9), 5) < 0.3 and 0.2 < v < 0.8:
            return mix(ROOT, OLIVE, 0.4)                                     # insect bites, brown
        if edge:
            return BULB_L
        px = abs(x) * w / 2
        if px < 1.0:
            return SAGE_L
        if px < 2.0:
            return MOSS_D                                                    # shadow along the midrib
        if rel < 0.92 and int(v * length_px - px * 0.9) % 8 == 0:
            return SAGE                                                      # secondary veins at 45 degrees
        cell = (int(v * length_px - px * 0.9) % 8) / 8.0
        return mix(mix(MOSS, SAGE, 0.55 * rel * rel), SAGE, 0.16 * math.sin(math.pi * cell))

    def back(x, v, edge, o, w):
        px = abs(x) * w / 2
        if edge:
            return OLIVE
        if px < 1.0 or int(v * length_px - px * 0.9) % 8 == 0:
            return MOSS_D
        return mix(OLIVE_P, OLIVE, 0.25 * (1 - v))
    return front, back


# ---------------------------------------------------------------- throat
FOLDS = (0.16, 0.33, 0.48, 0.61, 0.72, 0.81, 0.89)
THROAT = ((0.0, BORDEAUX), (0.45, PLUM), (1.0, NIGHT))
SKIN = ((0.0, RASP), (1.0, mix(RASP, POWDER, 0.6)))


def throat_px(depth, across):
    """Inner wall: darker towards the bottom of the throat, ring folds tighter and tighter, a wet glint on the
    entrance side of each crest."""
    for f in FOLDS:
        if abs(depth - f) < 0.022:
            return grad(THROAT, depth + 0.3)
        if 0 < f - depth < 0.06 and depth < 0.7 and int(across * 97) % 5 != 0:
            return mix(grad(THROAT, depth), WET, 0.55 - 0.5 * depth)
    return grad(THROAT, depth)


def throat_wall(inner, spines=False):
    """Painter of a throat wall: `inner` is the face turned towards the cavity, the north face is the lip slice."""
    def f(w, h, r, face):
        g = [[grad(SKIN, 0.5)] * w for _ in range(h)]
        for j in range(h):
            for k in range(w):
                s, t = (k + 0.5) / w, (j + 0.5) / h
                if face == inner:
                    depth, across = {"down": (1 - t, s), "up": (t, s), "west": (s, t), "east": (1 - s, t)}[face]
                    g[j][k] = throat_px(depth, across)
                elif face == "north":
                    c = mix(RASP, WET, 0.35) if (t < 0.34 if inner != "down" else t > 0.66) else RASP
                    if spines and int(t * h) % 8 in (2, 3):
                        tip_side = s > 0.5 if inner == "west" else s < 0.5
                        c = HORN if not tip_side else (TIP if int(t * h) % 8 == 2 else c)
                    g[j][k] = c
                else:
                    g[j][k] = grad(SKIN, s if face in ("up", "down") else t)
        return g
    return f


def throat_back(w, h, r, face):
    g = [[NIGHT] * w for _ in range(h)]
    if face != "north":
        return [[RASP] * w for _ in range(h)]
    for j in range(h):
        for k in range(w):
            x, y = 2 * (k + 0.5) / w - 1, 2 * (j + 0.5) / h - 1
            d = math.hypot(x, y * 0.9)
            if d < 0.3:
                g[j][k] = shade(NIGHT, 0.55)                                 # the duct goes on
            elif abs(d - 0.52) < 0.045 or abs(d - 0.8) < 0.04:
                g[j][k] = PLUM
            elif max(abs(x), abs(y)) > 0.9:
                g[j][k] = mix(NIGHT, PLUM, 0.5)
    return g


def tooth(tip, broken=False):
    """Thorn: amber base with a bordeaux gum ring, horn body, ivory point; the last texels are cut to a point."""
    def f(w, h, r, face):
        g = [[CLEAR] * w for _ in range(h)]
        if face in ("up", "down"):
            return g if (face == tip and not broken) else [[AMBER if not broken else mix(AMBER, ROOT, 0.4)] * w
                                                           for _ in range(h)]
        for j in range(h):
            d = j if tip == "down" else h - 1 - j                            # texels from the base
            left = h - 1 - d                                                 # texels from the point
            for k in range(w):
                if not broken and ((left < 2 and not (w // 2 - 1 <= k <= w // 2)) or
                                   (left < 5 and (k == 0 or k == w - 1) and w >= 4)):
                    continue
                if broken and left < 1 + k // 2:
                    continue                                                 # slanted clean break
                if d == 0:
                    c = BORDEAUX
                else:
                    c = grad(((0.0, AMBER), (0.3, HORN), (0.8, HORN), (1.0, TIP)), d / max(1, h - 1), steps=8)
                    if k == w // 2 and 0.15 < d / h < 0.75:
                        c = mix(c, AMBER, 0.35)
                    if left == 3 and k == w // 2 - 1 and not broken:
                        c = TIP
                g[j][k] = c
        return g
    return f


def tongue(v0, v1, pollen=False):
    """Pistil tongue, lying along z: raspberry deep in the throat, peach outside, a saffron pollen sac at the end."""
    stops = ((0.0, RASP), (0.55, mix(RASP, PEACH, 0.6)), (1.0, PEACH))

    def f(w, h, r, face):
        g = [[CLEAR] * w for _ in range(h)]
        for j in range(h):
            for k in range(w):
                s, t = (k + 0.5) / w, (j + 0.5) / h
                if face in ("up", "down"):
                    a, x = (1 - t if face == "up" else t), abs(2 * s - 1)
                elif face in ("east", "west"):
                    a, x = (1 - s if face == "west" else s), 0.6
                else:
                    a, x = (1.0 if face == "north" else 0.0), abs(2 * s - 1)
                v = v0 + (v1 - v0) * a
                c = grad(stops, v)
                if face == "up" and x < 1.2 / w:
                    c = shade(c, 0.8)                                        # central groove
                if face == "down":
                    c = shade(c, 0.82)
                if pollen and a > 0.6:
                    c = SAFFRON if face != "down" else SAFFRON_D
                    if noise(j, k, len(face)) < 0.3:
                        c = SAFFRON_D if face != "down" else shade(SAFFRON_D, 0.8)
                    elif noise(j, k, 11) < 0.12:
                        c = mix(SAFFRON, IVORY_W, 0.6)
                g[j][k] = c
        return g
    return f


# ---------------------------------------------------------------- stem, bulb, roots
FACE_K = {"north": 1.05, "south": 0.86, "east": 0.95, "west": 0.98, "up": 1.05, "down": 0.75}


def stem(stops, thorns=2):
    """Waxy stem grown along +y: vertical gradient, slightly twisted striations, a painted swelling at the top
    joint and a few painted thorns (bordeaux, ivory point) on the sides and the back."""
    def f(w, h, r, face):
        if face in ("up", "down"):
            return [[shade(grad(stops, 1.0 if face == "up" else 0.0), 0.9)] * w for _ in range(h)]
        g = []
        for j in range(h):
            row = []
            for k in range(w):
                c = shade(grad(stops, 1 - (j + 0.5) / h), FACE_K[face])
                st = (k + j // 6) % 4
                c = shade(c, 0.9) if st == 0 else shade(c, 1.07) if st == 2 else c
                if j < 2:
                    c = mix(c, SAGE_L, 0.5)
                elif j < 4:
                    c = shade(c, 0.82)
                row.append(c)
            g.append(row)
        if face != "north":
            for _ in range(thorns):
                j, k = r.randrange(5, max(6, h - 5)), r.randrange(1, max(2, w - 2))
                for dj, c in ((0, BORDEAUX), (1, BULB_L), (2, HORN)):
                    if j + dj < h:
                        g[j + dj][k] = c
                g[j][min(w - 1, k + 1)] = BORDEAUX
        return g
    return f


BULB_RAMP = (hx("#3E1D26"), BULB, BULB_L, hx("#8A4A52"))


def bulb(rows, top=None, moss=False):
    """Fleshy bulb: rows of pointed scales, each one lit on its upper edge and shaded under the next row."""
    def f(w, h, r, face):
        if face == "up":
            g = []
            for j in range(h):
                row = []
                for k in range(w):
                    d = math.hypot(2 * (k + 0.5) / w - 1, 2 * (j + 0.5) / h - 1)
                    row.append(mix(top, BULB, min(1.0, d * 1.1)) if top else shade(BULB, 0.85))
                g.append(row)
            return g
        if face == "down":
            return [[EARTH] * w for _ in range(h)]
        cw = max(8, round(w / max(1, round(w / 12))))
        bh = h / rows
        g = []
        for j in range(h):
            ri = min(rows - 1, int(j / bh))
            cy = (j - ri * bh + 0.5) / bh
            row = []
            for k in range(w):
                kk = (k + (ri % 2) * cw / 2) % cw
                cx = abs(2 * (kk + 0.5) / cw - 1)
                lim = 1 - (1 - cy) ** 2.1
                if cx > lim:
                    c = BULB_RAMP[0]
                elif cx > lim - 2.2 / cw or (cy < 0.2 and cx < 0.25):
                    c = BULB_RAMP[3]
                else:
                    c = BULB_RAMP[2] if cy < 0.45 else BULB_RAMP[1] if cy < 0.82 else BULB_RAMP[0]
                c = shade(c, FACE_K[face])
                if not top and j >= h - 4:
                    c = mix(c, EARTH, (j - (h - 5)) / 5.0)
                if moss and face == "south" and j >= h - 7 and noise(j // 2, k // 3, 4) < 0.4:
                    c = MOSS if noise(j, k, 9) < 0.7 else MOSS_D
                row.append(c)
            g.append(row)
        return g
    return f


def root(stops, tip=False, moss=False):
    """Root grown along +y, north = upper side: long fibres, two or three knots, pale rootlets near the point."""
    def f(w, h, r, face):
        if face in ("up", "down"):
            return [[shade(grad(stops, 1.0 if face == "up" else 0.0), 0.85)] * w for _ in range(h)]
        k_face = {"north": 1.08, "south": 0.68, "east": 0.9, "west": 0.92}[face]
        g = []
        for j in range(h):
            row = []
            for k in range(w):
                v = 1 - (j + 0.5) / h
                c = shade(grad(stops, v), k_face)
                st = (k * 3 + j // 9) % 5
                c = shade(c, 0.86) if st == 0 else shade(c, 1.08) if st == 3 else c
                if tip and v > 0.55 and noise(j // 3, k, 2) < 0.16:
                    c = mix(c, OLIVE_P, 0.7)
                if moss and face == "north" and noise(j // 3, k // 2, 6) < 0.3:
                    c = MOSS if noise(j, k, 1) < 0.65 else MOSS_D
                row.append(c)
            g.append(row)
        for _ in range(2 if h < 26 else 3):
            j, k = r.randrange(2, max(3, h - 3)), r.randrange(0, max(1, w - 2))
            for dj in range(2):
                for dk in range(3):
                    if j + dj < h and k + dk < w:
                        g[j + dj][k + dk] = shade(EARTH, k_face + 0.25) if (dj, dk) != (0, 1) else shade(ROOT, 1.3)
        return g
    return f


# ---------------------------------------------------------------- materials
OUT_SPLIT = 12.8 / (12.8 + 8.8)
LEAF_BIG, LEAF_SMALL = leaf(58), leaf(48, bites=True)
LEAF_BIG_SPLIT, LEAF_SMALL_SPLIT = 17.6 / 28.8, 14.4 / 24.0
STEM_LOW = ((0.0, OLIVE), (0.55, MOSS), (1.0, MOSS))
STEM_HIGH = ((0.0, MOSS), (1.0, mix(MOSS, SAGE, 0.3)))
STEM_NECK = ((0.0, mix(MOSS, SAGE, 0.3)), (1.0, SAGE))
ROOT_A = ((0.0, mix(OLIVE, ROOT, 0.5)), (0.4, ROOT), (1.0, ROOT))
ROOT_B = ((0.0, ROOT), (0.7, mix(ROOT, OLIVE_P, 0.3)), (1.0, mix(ROOT, OLIVE_P, 0.75)))
ROOT_AB = ((0.0, mix(OLIVE, ROOT, 0.5)), (0.35, ROOT), (0.8, ROOT), (1.0, mix(ROOT, OLIVE_P, 0.75)))
RIM_OUT = lambda v: grad(OUTER_FRONT, v)  # noqa: E731

MATS = {name: (p, D) for name, p in {
    "petal_base": blade(outer_outline, outer_front, outer_back, 0.0, OUT_SPLIT, RIM_OUT),
    "petal_tip": blade(outer_outline, outer_front, outer_back, OUT_SPLIT, 1.0, RIM_OUT),
    "petal_low": blade(outer_outline, outer_front, outer_back, 0.0, 1.0, RIM_OUT),
    "petal_torn": blade(outer_outline, outer_front, outer_back, 0.0, 1.0, RIM_OUT, cut=tear),
    "petal_inner": blade(inner_outline, inner_front, inner_back),
    "sepal": blade(sepal_outline, sepal_side, sepal_side),
    "leaf_big_a": blade(leaf_outline, *LEAF_BIG, 0.0, LEAF_BIG_SPLIT),
    "leaf_big_b": blade(leaf_outline, *LEAF_BIG, LEAF_BIG_SPLIT, 1.0),
    "leaf_small_a": blade(leaf_outline, *LEAF_SMALL, 0.0, LEAF_SMALL_SPLIT),
    "leaf_small_b": blade(leaf_outline, *LEAF_SMALL, LEAF_SMALL_SPLIT, 1.0),
    "lip_top": throat_wall("down"), "lip_bottom": throat_wall("up"),
    "wall_right": throat_wall("west", spines=True), "wall_left": throat_wall("east", spines=True),
    "throat_back": throat_back,
    "tooth_down": tooth("down"), "tooth_up": tooth("up"), "tooth_broken": tooth("up", broken=True),
    "tongue_a": tongue(0.0, 0.62), "tongue_b": tongue(0.62, 1.0, pollen=True),
    "stem_low": stem(STEM_LOW, 3), "stem_high": stem(STEM_HIGH, 2), "stem_neck": stem(STEM_NECK, 1),
    "bulb_low": bulb(2, moss=True), "bulb_high": bulb(1, top=OLIVE),
    "root_a": root(ROOT_A), "root_b": root(ROOT_B, tip=True), "root_one": root(ROOT_AB, tip=True),
    "root_a_moss": root(ROOT_A, moss=True), "root_b_moss": root(ROOT_B, tip=True, moss=True),
}.items()}
GLOW = {}

# ---------------------------------------------------------------- geometry
I3 = rot(0, 0, 0)


def vadd(a, b):
    return [a[i] + b[i] for i in range(3)]


def norm(v):
    n = math.sqrt(sum(c * c for c in v))
    return [c / n for c in v]


def cross(a, b):
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


def frame(direction, normal, twist=0.0):
    """Matrix sending local +y onto `direction` and the north face (local -z) towards `normal`; twist turns the piece
    around its own length."""
    y = norm(direction)
    d = sum(normal[i] * y[i] for i in range(3))
    z = norm([-(normal[i] - d * y[i]) for i in range(3)])
    x = cross(y, z)
    return mul([[x[i], y[i], z[i]] for i in range(3)], rot(0, twist, 0))


def polar(azimuth, elevation=0.0):
    """Direction from an azimuth seen from above (0 = straight ahead, -z ; positive towards +x) and an elevation."""
    a, e = math.radians(azimuth), math.radians(elevation)
    return [math.sin(a) * math.cos(e), math.sin(e), -math.cos(a) * math.cos(e)]


BULB_C = (0.0, 0.0, 5.6)
STEM_0, ELBOW_LOW, ELBOW_HIGH = (0.0, 15.0, 5.6), (-2.4, 27.0, 12.0), (0.5, 35.0, 6.5)
THROAT_C = (1.6, 41.0, -11.2)
HEAD_R = rot(-15, 0, -6)                # corolla looking 15 degrees down, rolled 6 degrees towards +x
HEAD_O = vadd(THROAT_C, app(HEAD_R, [0, 0, 8]))      # centre of the back of the throat = origin of the head
NECK_END = vadd(HEAD_O, app(HEAD_R, [0, -6, 1.5]))

OUTER = (0, 72, 144, 216, 288)          # 12 h, 2 h 30, 5 h, 7 h, 9 h 30 (degrees clockwise seen by the player)
INNER = (36, 108, 180, 252, 324)        # 1 h, 3 h 30, 6 h, 8 h 30, 11 h
INNER_R0 = {36: 14.0, 108: 13.4, 180: 11.6, 252: 13.4, 324: 14.0}   # in the thickness of the throat wall, that way
ROOTS = (  # name, azimuth, (length, section) per segment, tip elevation, mossy
    ("1", 35, ((16.0, 6.4), (12.8, 4.5)), 20, False),
    ("2", -50, ((14.4, 6.1), (9.6, 4.2)), 30, False),
    ("3", 170, ((16.0, 6.7), (11.2, 4.8)), 0, True),
    ("4", -115, ((16.0, 5.1),), 0, True),
    ("5", 105, ((12.8, 4.8),), 35, False),
)
BUDGET = {"base": 10, "stem": 3, "leaves": 4, "head": 31}


def parts():
    P, G = [], {"Root": ((0, 0, 0), None), "Bulbe": (BULB_C, "Root")}
    count = {k: 0 for k in BUDGET}

    def add(zone, group, name, c, size, r, mat):
        count[zone] += 1
        P.append((group, name, list(c), list(size), r, mat))

    def segment(zone, group, name, p, q, section, mat, normal, twist=0.0, overlap=1.6):
        d = [q[i] - p[i] for i in range(3)]
        length = math.sqrt(sum(c * c for c in d))
        R = frame(d, normal, twist)
        add(zone, group, name, vadd(p, app(R, [0, (length + overlap) / 2 - overlap / 2, 0])),
            (section, length + overlap, section), R, mat)

    def head(c):
        return vadd(HEAD_O, app(HEAD_R, c))

    def blade_box(zone, group, name, origin, R, width, length, thick, mat, world=False):
        c = vadd(origin, app(R, [0, length / 2, 0]))
        add(zone, group, name, c if world else head(c), (width, length, thick), R if world else mul(HEAD_R, R), mat)

    # ---- bulb and roots
    add("base", "Bulbe", "bulbe_bas", (0, 5.2, 5.6), (24, 10.4, 24), I3, "bulb_low")
    add("base", "Bulbe", "bulbe_haut", (0, 12.4, 5.6), (17.6, 7.2, 17.6), rot(0, 45, 0), "bulb_high")
    for name, az, segs, lift, mossy in ROOTS:
        h, up = polar(az), [0, 1, 0]
        p0 = [BULB_C[0] + h[0] * 4, 6.5, BULB_C[2] + h[2] * 4]
        G[f"Racine_{name}"] = (p0, "Bulbe")
        if len(segs) == 2:
            (l1, s1), (l2, s2) = segs
            p1 = [p0[0] + h[0] * l1 * 0.93, s2 / 2 + 1.6, p0[2] + h[2] * l1 * 0.93]     # knee lifted off the ground
            t = polar(az, lift)
            p2 = [p1[0] + t[0] * l2, max(s2 / 2, p1[1] + t[1] * l2 - (0 if lift else 1.6)), p1[2] + t[2] * l2]
            G[f"Racine_{name}_pointe"] = (p1, f"Racine_{name}")
            segment("base", f"Racine_{name}", f"racine_{name}_a", p0, p1, s1, "root_a_moss" if mossy else "root_a", up)
            segment("base", f"Racine_{name}_pointe", f"racine_{name}_b", p1, p2, s2,
                    "root_b_moss" if mossy else "root_b", up, twist=20)
        else:
            (l1, s1), = segs
            t = polar(az, lift)
            mid = [p0[0] + h[0] * 5, s1 / 2 + 0.4, p0[2] + h[2] * 5]
            G[f"Racine_{name}"] = (mid, "Bulbe")
            segment("base", f"Racine_{name}", f"racine_{name}", mid, vadd(mid, [c * l1 * 0.7 for c in t]), s1,
                    "root_one", up, overlap=9.0)

    # ---- stem: S curve, each segment turned on its own axis so the edges never line up
    front = [0, 0, -1]
    G["Tige_basse"] = (STEM_0, "Bulbe")
    G["Tige_haute"] = (ELBOW_LOW, "Tige_basse")
    G["Cou"] = (ELBOW_HIGH, "Tige_haute")
    segment("stem", "Tige_basse", "tige_basse", STEM_0, ELBOW_LOW, 9.6, "stem_low", front, twist=0, overlap=2.4)
    segment("stem", "Tige_haute", "tige_haute", ELBOW_LOW, ELBOW_HIGH, 8.0, "stem_high", front, twist=15, overlap=2.4)
    segment("stem", "Cou", "cou", ELBOW_HIGH, NECK_END, 7.2, "stem_neck", [0, 1, -0.4], twist=30, overlap=2.4)

    # ---- leaves: big and raised on -x, small, lower and drooping on +x
    for side, at, az, (e1, e2), (wa, la), (wb, lb), m in (
            ("g", (-4.5, 26.5, 11.0), -70, (30, -15), (14.4, 17.6), (14.4, 11.2), "leaf_big"),
            ("d", (3.2, 20.0, 8.5), 115, (5, -50), (12.0, 14.4), (12.0, 9.6), "leaf_small")):
        G[f"Feuille_{side}"] = (at, "Tige_basse")
        up = [0, 1, -0.9]                   # upper side half turned to the front: the leaf is seen from there
        Ra = frame(polar(az, e1), up)
        end = vadd(at, app(Ra, [0, la, 0]))
        G[f"Feuille_{side}_pointe"] = (end, f"Feuille_{side}")
        blade_box("leaves", f"Feuille_{side}", f"feuille_{side}_a", at, Ra, wa, la, 2.0, m + "_a", world=True)
        blade_box("leaves", f"Feuille_{side}_pointe", f"feuille_{side}_b", end, frame(polar(az, e2), up), wb, lb, 2.0,
                  m + "_b", world=True)

    # ---- head: everything below is drawn around the throat axis (local -z), then tilted with HEAD_R
    G["h_Tete"] = (NECK_END, "Cou")
    for i, phi in enumerate(INNER, 1):
        Rz = rot(0, 0, phi)
        G[f"Sepale_{i}"] = (head(app(Rz, [0, 5, 1.5])), "h_Tete")
        blade_box("head", f"Sepale_{i}", f"sepale_{i}", app(Rz, [0, 5, 1.5]), mul(Rz, rot(40, 0, 0)), 7.2, 12.8, 2.0,
                  "sepal")
    # throat: a really hollow box, entrance wider than the bottom, lower lip sticking out
    add("head", "h_Tete", "fond_gorge", head([0, 0, -1.2]), (20.8, 17.6, 2.4), HEAD_R, "throat_back")
    G["Levre_haute"] = (head([0, 10.4, 0]), "h_Tete")
    G["Levre_basse"] = (head([0, -10.4, 0]), "h_Tete")
    add("head", "Levre_haute", "levre_haute", head([0, 10.4, -8]), (27.2, 3.2, 16), HEAD_R, "lip_top")
    add("head", "Levre_basse", "levre_basse", head([0, -10.4, -9.2]), (27.2, 3.2, 18.4), HEAD_R, "lip_bottom")
    for s, n, m in ((1, "d", "wall_right"), (-1, "g", "wall_left")):
        add("head", "h_Tete", f"paroi_{n}", head([s * 13.1, 0, -8]), (3.2, 17.6, 16), mul(HEAD_R, rot(0, -8 * s, 0)), m)
    # thorns: tips leaning 15 degrees into the throat, upper and lower rows staggered
    for i, (x, length) in enumerate(((-6.7, 8.0), (1.6, 11.0), (6.7, 8.0)), 1):
        R = mul(rot(-15, 0, 0), rot(0, 45, 0))
        add("head", "Levre_haute", f"dent_haut_{i}", head(vadd([x, 9.3, -14.6], app(R, [0, -length / 2, 0]))),
            (2.6, length, 2.6), mul(HEAD_R, R), "tooth_down")
    for i, (x, length, m) in enumerate(((6.6, 7.0, "tooth_up"), (-2.6, 9.0, "tooth_up"), (-8.2, 4.6, "tooth_broken")), 1):
        R = mul(rot(15, 0, 0), rot(0, 45, 0))
        add("head", "Levre_basse", f"dent_bas_{i}", head(vadd([x, -9.3, -17.0], app(R, [0, length / 2, 0]))),
            (2.6, length, 2.6), mul(HEAD_R, R), m)
    # pistil tongue: lying on the lower lip on the +x side, then bent down past the edge
    G["Langue"] = (head([3.6, -7.3, -4.0]), "Levre_basse")
    G["Langue_pointe"] = (head([3.6, -7.3, -18.4]), "Langue")
    add("head", "Langue", "langue_a", head([3.6, -7.35, -11.2]), (4.8, 2.9, 14.4), HEAD_R, "tongue_a")
    Rt = rot(-50, -10, 0)
    add("head", "Langue_pointe", "langue_b", head(vadd([3.6, -7.35, -18.0], app(Rt, [0, 0, -4.4]))), (4.8, 2.9, 8.8),
        mul(HEAD_R, Rt), "tongue_b")
    # inner petals: a tight collar hugging the throat, the 6 o'clock one is the hanging lip
    for i, phi in enumerate(INNER, 1):
        Rz = rot(0, 0, phi)
        o = app(Rz, [0, INNER_R0[phi], -12.5])
        G[f"Petale_int_{i}"] = (head(o), "h_Tete")
        blade_box("head", f"Petale_int_{i}", f"petale_int_{i}", o, mul(Rz, rot(-38 + (18 if phi == 180 else 0), 0, 0)),
                  14.4, 14.4 if phi == 180 else 12.0, 1.5, "petal_inner")
    # outer petals: cupped base, recurved tip for the three upper ones; the two lower ones hang in one piece
    for i, phi in enumerate(OUTER, 1):
        Rz = rot(0, 0, phi)
        o = app(Rz, [0, 9.0, 0.5 + 0.06 * i])
        G[f"Petale_ext_{i}"] = (head(o), "h_Tete")
        if phi in (144, 216):
            torn = phi == 216
            blade_box("head", f"Petale_ext_{i}", f"petale_ext_{i}", o, mul(Rz, rot(-10 if torn else -20, 0, 0)), 15.2,
                      14.4, 2.0, "petal_torn" if torn else "petal_low")
            continue
        Rb = mul(Rz, rot(-35, 0, 0))
        end = vadd(o, app(Rb, [0, 12.8, 0]))
        G[f"Petale_ext_{i}_pointe"] = (head(end), f"Petale_ext_{i}")
        blade_box("head", f"Petale_ext_{i}", f"petale_ext_{i}_a", o, Rb, 16.0, 12.8, 2.0, "petal_base")
        blade_box("head", f"Petale_ext_{i}_pointe", f"petale_ext_{i}_b", end, mul(Rb, rot(45, 0, 0)), 16.0, 8.8, 1.5,
                  "petal_tip")

    assert count == BUDGET, count
    assert len(P) == 48
    return P, G


def build():
    P, G = parts()
    return ground(P, G, center=True)


def closeup(data, atlases, sizes, path, size=460):
    """Head alone, from the front and from three quarters, large: checks the mouth."""
    import paths  # noqa: F401
    import apercu as A
    from png_util import write
    keep = ("fond", "levre", "paroi", "dent", "langue", "petale", "sepale")
    els = [e for e in flat_elements(data, sizes) if e["name"].split("_")[0] in keep]
    combo = combined(atlases, sizes)
    bg = (58, 58, 62, 255)
    imgs = [A.render(els, combo, yaw, pitch, size=size, bg=bg, margin=24) for yaw, pitch in ((0, -8), (38, 4))]
    write(path, size * 2, size, [imgs[0][y] + imgs[1][y] for y in range(size)])


if __name__ == "__main__":
    args = sys.argv[1:]
    opts = {}
    for flag in ("--apercu", "--gueule"):
        if flag in args:
            i = args.index(flag)
            opts[flag] = args[i + 1]
            del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims = build()
    uv, atlases, sizes = bake(P, MATS, GLOW)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    print(f"{len(P)} cubes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (large x haut x long) ; "
          f"texture {sizes[0]}")
    if "--apercu" in opts:
        # front, three quarters, profile, back, then the silhouette of the front view
        apercu(data, atlases, sizes, opts["--apercu"], set(), views=((0, 2), (35, 10), (90, 2), (180, 6)), size=440)
    if "--gueule" in opts:
        closeup(data, atlases, sizes, opts["--gueule"])
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
