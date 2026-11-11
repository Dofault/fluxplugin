#!/usr/bin/env python3
"""Leaf Fox (Renard de feuilles): the Herbalist Fox turned into a living plant, STATIC model (2026-11-11).

Same geometry and bones as renard_herboriste.py (imported, never copied), without the satchel (bone Bag: bag,
strap, vial holder and vials dropped); the collar and the leaf by the ear stay. Only the matter changes. Every surface that was fur is painted as overlapping leaves: small angular
leaves (wide base, pointed tip, discreet midrib, lit half and shaded half) laid like shingles, each casting a dark
pixel shadow on the leaves below it, drawn from downstream to upstream so the leaf nearer the root covers the next.
Leaves follow the body: backwards on the back, down and backwards on the flanks, downwards on the legs, backwards
around the skull and on the cheeks, along the tail. Tones come from one green family, darker on the back and the base
of the tail, lighter on the flanks, the cheeks, the belly and the tail tip; light from above and the front left.

Face reworked to look menacing: eyes 18 % less tall, upper lid slanting down to the inner corner, amber iris with a
vertical slit pupil; thin dark leaf brows frowning (inner end down on the eye, outer end up); head turned less
(gaze straight at the intruder). These three are the only geometry changes.

Usage: renard_feuilles.py [output folder] [--apercu <sheet.png>]
Writes renard_feuilles.bbmodel and its two textures as PNG (renard_feuilles.png, renard_feuilles_lumiere.png).
"""
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import renard_herboriste as R  # noqa: E402
from bbkit import apercu, attach, bake, export, ground, hx  # noqa: E402
from chauve_souris import pick  # noqa: E402
from golem_fonderie import rot  # noqa: E402
from png_util import write  # noqa: E402

IDENT = "renard_feuilles"
NS = R.NS


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- palette (brief)
P = pal("#263F2E", "#35563A", "#496B3F", "#64864A", "#83A85A", "#A9C878")
YOUNG, MATURE, VEIN, OLD = hx("#A5C96B"), hx("#71844B"), hx("#B3C77B"), hx("#65704A")
IRIS, IRIS_DARK, PUPIL = hx("#D4A53E"), hx("#A8782F"), hx("#202016")
NOSE = pal("#1E1712", "#2B2119", "#3D2F24")

# tone shift by face: light from above and the front left (the fox's left is -x, the west face)
SHIFT = {"up": 0.7, "north": 0.25, "west": 0.3, "east": -0.15, "south": -0.35, "down": -1.1}

# leaf direction in texture space (u right, v down) per face. Back/flanks: backwards and down; the back is +v on
# top faces, on the east face +z is towards -u, on the west face towards +u (checked on the preview).
BACK_DOWN = {"up": (0, 1), "down": (0, 1), "north": (0, 1), "south": (0, 1), "east": (-0.55, 1), "west": (0.55, 1)}
DOWN = {"up": (0, 1), "down": (0, 1), "north": (0, 1), "south": (0, 1), "east": (0, 1), "west": (0, 1)}
ALONG = {"up": (0, 1), "down": (0, 1), "north": (0, 1), "south": (0, 1), "east": (-1, 0.35), "west": (1, 0.35)}
HEAD = {"up": (0, 1), "down": (0, 1), "north": (0, -1), "south": (0, 1), "east": (-1, 0.45), "west": (1, 0.45)}


# --------------------------------------------------------------------------- leaf coat painter
def leafcoat(ladder, centre, dirs, spread=0.3, length=(7, 9), width=(5, 7), grad=0.0, extra=0.04,
             veins=0.3, mouth=False):
    """Overlapping leaves in the tones of `ladder` (dark to light). `centre`: mean tone index of a leaf; `grad`:
    tone added from the top row to the bottom row of side faces (flank lighter towards the belly, leg darker
    towards the paw); `extra`: share of leaves taking a secondary colour (young, mature, old)."""
    def f(w, h, r, face):
        dx, dy = dirs.get(face, (0, 1))
        n_ = math.hypot(dx, dy)
        d = (dx / n_, dy / n_)
        nv = (-d[1], d[0])
        shift = SHIFT[face]
        # ground between the leaves: the darkest tone of the zone, flat
        grid = [[ladder[0]] * w for _ in range(h)]
        L_mean = sum(length) / 2
        W_mean = sum(width) / 2
        step_s, step_t = L_mean * 0.75, W_mean * 1.0
        corners = [(0, 0), (w, 0), (0, h), (w, h)]
        ps = [x * d[0] + y * d[1] for x, y in corners]
        pt = [x * nv[0] + y * nv[1] for x, y in corners]
        leaves = []
        a = math.floor((min(ps) - L_mean) / step_s)
        while a * step_s <= max(ps) + L_mean:
            b = math.floor((min(pt) - W_mean) / step_t)
            while b * step_t <= max(pt) + W_mean:
                s0 = a * step_s + (step_s / 2 if b % 2 else 0) + r.uniform(-0.6, 0.6)
                t0 = b * step_t + r.uniform(-0.5, 0.5)
                cx, cy = s0 * d[0] + t0 * nv[0], s0 * d[1] + t0 * nv[1]
                leaves.append((s0, cx, cy))
                b += 1
            a += 1
        leaves.sort(key=lambda q: -q[0])          # downstream first: the leaf nearer the root covers the next one
        side = face not in ("up", "down")
        for s0, cx, cy in leaves:
            L = r.randint(*length)
            W = r.randint(*width)
            k = min(1.0, max(0.0, cy / max(1, h))) if side else 0.5
            v = centre + shift + (grad * (k - 0.5) if side else 0) + r.gauss(0, spread)
            i = max(0, min(len(ladder) - 1, int(math.floor(v + 0.5))))
            main = ladder[i]
            lit = ladder[min(len(ladder) - 1, i + 1)]
            dark = ladder[max(0, i - 1)] if i > 0 else P[0]
            vein = lit if i + 1 < len(ladder) - 1 else VEIN
            if r.random() < extra:
                main = r.choice((MATURE, OLD, YOUNG) if i >= 2 else (MATURE, OLD))
            shadow = P[0] if ladder[0] in (P[0], P[1]) else ladder[0]
            bx, by = cx - d[0] * L / 2, cy - d[1] * L / 2           # base of the leaf
            sm = 0.42 * L
            has_vein = L >= 6 and r.random() < veins

            def inside(s, t):
                if s < 0 or s > L:
                    return False
                hw = W / 2 * (0.5 + 0.5 * s / sm) if s < sm else W / 2 * (L - s) / (L - sm)
                return abs(t) <= hw + 0.2
            rng = int(L + W) + 2
            x0, y0 = int(cx) - rng, int(cy) - rng
            # shadow first: the same leaf one pixel downstream, in the zone's darkest tone (separates the layers)
            for py in range(max(0, y0), min(h, y0 + 2 * rng)):
                for px in range(max(0, x0), min(w, x0 + 2 * rng)):
                    qx, qy = px + 0.5 - bx - d[0] * 1.0 + nv[0] * 0.6, py + 0.5 - by - d[1] * 1.0 + nv[1] * 0.6
                    if inside(qx * d[0] + qy * d[1], qx * nv[0] + qy * nv[1]):
                        grid[py][px] = shadow
            for py in range(max(0, y0), min(h, y0 + 2 * rng)):
                for px in range(max(0, x0), min(w, x0 + 2 * rng)):
                    qx, qy = px + 0.5 - bx, py + 0.5 - by
                    s, t = qx * d[0] + qy * d[1], qx * nv[0] + qy * nv[1]
                    if not inside(s, t):
                        continue
                    hw = W / 2 * (0.5 + 0.5 * s / sm) if s < sm else W / 2 * (L - s) / (L - sm)
                    if s < 1.0:
                        c = dark                                     # base tucked under the previous leaf
                    elif has_vein and abs(t) < 0.5 and 1.5 < s < 0.72 * L:
                        c = vein                                     # discreet midrib
                    elif s > L - 1.6 or (t > hw - 0.7 and s > 0.4 * L):
                        c = lit                                      # tip and a touch of the lit edge
                    else:
                        c = main
                    grid[py][px] = c
        if mouth and face == "north" and w >= 6 and h >= 4:
            for x in range(w // 2 - 2, w // 2 + 2):
                grid[h - 2][x] = P[0]
        if mouth and face in ("east", "west") and h >= 4:
            for x in range(w):
                grid[h - 2][x] = P[0] if r.random() < 0.8 else ladder[0]
        return grid
    return f


def ear_inner(w, h, r, face):
    """Inside of the ear: mature olive and light green leaves pointing up, two thin pale veins."""
    grid = [[pick([MATURE, P[3], P[4]], 1.0, r, 0.55) for _ in range(w)] for _ in range(h)]
    if face == "north" and w >= 4:
        for x in (w // 3, 2 * w // 3):
            for y in range(1, h - 1):
                if r.random() < 0.8:
                    grid[y][x] = VEIN if r.random() < 0.6 else P[5]
    for y in range(h):
        grid[y][0] = grid[y][w - 1] = P[1]
    return grid


def eye(inner_left):
    def f(w, h, r, face):
        """Menacing eye (3 texels per unit): almond, the upper lid slanting down to the inner corner, warm amber
        iris darker below, vertical slit pupil looking straight ahead; no glint (not a light source)."""
        if face != "north":
            return [[P[0]] * w for _ in range(h)]
        lid = PUPIL
        grid = [[IRIS] * w for _ in range(h)]
        for x in range(w):
            grid[0][x] = lid
            grid[h - 1][x] = lid
            if h >= 5:
                grid[h - 2][x] = IRIS_DARK
        inner = range(0, 3) if inner_left else range(w - 3, w)
        corner = 0 if inner_left else w - 1
        outer = w - 1 if inner_left else 0
        for x in inner:
            grid[1][x] = lid                                   # lid slanting down to the inner corner
        for y in range(h):
            grid[y][corner] = lid
        grid[1][outer] = lid
        grid[h - 2][outer] = lid
        mid = w // 2 - (0 if inner_left else 1)
        for y in range(1, h - 1):
            grid[y][mid] = PUPIL
        grid[h - 2][mid - 1 if inner_left else mid + 1] = IRIS_DARK
        return grid
    return f


def nose(w, h, r, face):
    grid = [[pick(NOSE, 1, r, 0.4) for _ in range(w)] for _ in range(h)]
    if face == "north" and w >= 4 and h >= 2:
        grid[h - 1][1] = grid[h - 1][w - 2] = NOSE[0]
    return grid


def solid_tones(tones, base, spread=0.4):
    def f(w, h, r, face):
        return [[pick(tones, base + SHIFT[face] * 0.5, r, spread) for _ in range(w)] for _ in range(h)]
    return f


BACK_L = [P[0], P[1], P[2], P[3]]
FLANK_L = [P[1], P[2], P[3], P[4]]
BELLY_L = [MATURE, P[4], P[5]]
LEG_L = [P[0], P[1], P[2], MATURE]
HEAD_L = [P[0], P[1], P[2], P[3]]
CHEEK_L = [P[3], P[4], P[5], YOUNG]
SMALL = dict(length=(5, 7), width=(4, 5))
BIG = dict(length=(8, 10), width=(6, 8))

LEAF_MATS = {
    "body": (leafcoat(FLANK_L, 1.2, BACK_DOWN, grad=1.4), 2),
    "body_dark": (leafcoat(FLANK_L, 0.9, BACK_DOWN, grad=1.2), 2),
    "back": (leafcoat(BACK_L, 1.3, BACK_DOWN), 2),
    "rump": (leafcoat(BACK_L, 1.2, BACK_DOWN), 2),
    "belly": (leafcoat(BELLY_L, 1.2, BACK_DOWN, extra=0.04, **SMALL), 2),
    "bib": (leafcoat(BELLY_L, 1.3, DOWN, extra=0.04, **SMALL), 2),
    "head": (leafcoat(HEAD_L, 1.6, HEAD, grad=0.6, **SMALL), 2),
    "head_top": (leafcoat(BACK_L, 1.2, HEAD, **SMALL), 2),
    "forehead": (leafcoat(BACK_L, 1.2, HEAD, **SMALL), 3),
    "cheek": (leafcoat(CHEEK_L, 0.9, HEAD, **SMALL), 2),
    "ruff": (leafcoat(CHEEK_L, 0.7, HEAD, **SMALL), 2),
    "muzzle": (leafcoat([P[3], P[4], P[5], YOUNG], 1.0, HEAD, length=(4, 6), width=(3, 4), veins=0.2,
                        mouth=True), 2),
    "muzzle_top": (leafcoat(HEAD_L, 1.4, HEAD, length=(4, 6), width=(3, 4)), 2),
    "ear": (leafcoat([P[0], P[1], P[2]], 1.2, {f: (0, -1) for f in SHIFT}, length=(5, 7), width=(3, 4)), 3),
    "ear_dark": (leafcoat([P[0], P[1], P[2]], 0.8, {f: (0, -1) for f in SHIFT}, **SMALL), 3),
    "ear_in": (ear_inner, 3),
    "ear_tip": (solid_tones([P[0], P[1], OLD], 0.6), 3),
    "brow": (solid_tones([P[0], P[1]], 0.3, 0.35), 3),
    "leg": (leafcoat(LEG_L, 1.6, DOWN, **SMALL), 2),
    "leg_low": (leafcoat(LEG_L, 1.3, DOWN, grad=-1.4, **SMALL), 2),
    "paw": (leafcoat([P[0], OLD, P[1]], 0.9, DOWN, length=(4, 6), width=(3, 4), veins=0), 2),
    "toe": (solid_tones([P[0], OLD, P[1]], 0.8), 2),
    "toe_cream": (solid_tones([MATURE, P[3]], 0.6), 2),
    "eye_r": (eye(inner_left=False), 3), "eye_l": (eye(inner_left=True), 3),
    "nose": (nose, 2),
    # tail: dark at the base, middle greens, light tip (brief progression), large leaves along the tail
    "tail_1": (leafcoat([P[0], P[1], P[2]], 0.7, ALONG, **BIG), 2),
    "tail_2": (leafcoat([P[0], P[1], P[2], P[3]], 1.4, ALONG, **BIG), 2),
    "tail_3": (leafcoat([P[1], P[2], P[3], P[4]], 1.2, ALONG, **BIG), 2),
    "tail_4": (leafcoat([P[2], P[3], P[4], P[5]], 1.3, ALONG, **BIG), 2),
    "tail_5": (leafcoat([P[3], P[4], P[5], YOUNG], 1.0, ALONG, **BIG), 2),
}
# accessories (satchel, vials, collar, leaf by the ear) keep the materials of the herbalist fox
MATS = {**{k: v for k, v in R.MATS.items() if k not in LEAF_MATS}, **LEAF_MATS}
GLOW = R.GLOW
GLOW_COLOURS = R.GLOW_COLOURS

# --------------------------------------------------------------------------- geometry changes (face only)
R.HEAD_POSE = rot(-4, 4, 2)          # was (-7, 12, 3): gaze straight at the intruder


def tail_mat(name):
    for k in range(1, 6):
        if name.startswith(f"queue_{k}"):
            return f"tail_{k}"
    return "tail_5" if name.startswith("touffe_queue") else "tail_3" if name == "queue_crete" else None


def parts():
    raw = R.parts()
    on_body = attach(R.BODY_PIVOT, R.TILT)
    on_head_raw = attach(R.NECK, R.HEAD_POSE)

    def on_head(c, r):
        return on_body(*on_head_raw(c, r))
    out = []
    for g, n, c, size, r, m in raw:
        if g == "Bag":
            continue                     # no satchel, strap or vials: a wild creature, not the herbalist
        if n.startswith("oeil_"):
            s = 1 if n.endswith("_d") else -1
            # 18 % less tall, a little wider, lowered by a hair so the brow sits on it
            c, r = on_head((3.0 * s, 19.5, -19.2), (0, 8 * s, 0))
            size, m = [2.6, 1.65, 0.6], "eye_r" if s == 1 else "eye_l"
        elif n.startswith("sourcil_"):
            s = 1 if n.endswith("_d") else -1
            # thin, angular, frowning: inner end down onto the eye, outer end up
            c, r = on_head((3.05 * s, 20.6, -19.45), (0, 8 * s, 20 * s))
            size = [2.9, 0.5, 0.5]
        elif tail_mat(n):
            m = tail_mat(n)
        out.append((g, n, c, size, r, m))
    return out


def build():
    raw = parts()
    groups = {k: v for k, v in R.GROUPS.items() if k != "Bag"}
    return ground(raw, groups, center=False)


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = None
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    P_, groups, dims = build()
    uv, atlases, sizes = bake(P_, MATS, GLOW, seed=7313)
    data = export(IDENT, NS, P_, groups, uv, atlases, sizes)
    print(f"{len(P_)} volumes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (larg x haut x long) ; "
          f"textures {sizes[0]} + {sizes[1]}")
    if prev:
        apercu(data, atlases, sizes, prev, GLOW_COLOURS, views=((35, 12), (0, 4), (90, 3), (200, 10)), size=420)
    for t, suffix in ((0, ""), (1, "_lumiere")):
        write(os.path.join(out, f"{IDENT}{suffix}.png"), sizes[t], sizes[t], atlases[t])
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
