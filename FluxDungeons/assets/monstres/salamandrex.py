#!/usr/bin/env python3
"""Salamandrex: emerald salamander boss. STATIC Blockbench model (2026-11-11, second version, replaces the bipedal one).

A real four-legged salamander whose body is living emerald: wide flat head with a round muzzle and big side eyes, long
low body with the belly just above the ground, four short legs sprawled sideways (zigzag: upper segment out and down,
marked elbow or knee, lower segment back to the ground), wide feet with four short round toes, long tail thick at the
base, tapering, with a soft S curve seen from above and staying close to the ground. A row of seven cut emerald
crystals follows the spine (the biggest in the middle of the back, a little forward), with three small side crystals,
two small crystals behind the eye ridges and three at the start of the tail. Never a dragon, a lizard or a shell.

Same building language as the otter (loutre_marees.py) and the stone turtle (colosse_abime.py): big tilted volumes,
details painted at 1 texel per unit (2 for the eyes, the toes and the crystals). Skin and crystals are the same
material with two treatments: the skin is polished with discreet facets (large soft patches), the crystals have sharp
planes, strong contrasts and rare near-white pixels on lit edges. Light is computed per face from its real orientation
(LIGHT: from above, the front and slightly to the creature's left), so tilted legs and crystals stay consistent.

Pose baked in the cubes, bones at the joints for a future animation; no animation, no glow, no particle.

Usage: salamandrex.py [output folder] [--apercu <sheet.png>] [--table <cubes.md>]
Blockbench axes: x = width (+x = the creature's right), y = height (ground at 0), z = length (head towards -z).
"""
import json
import math
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import attach, bake, corners, export, ground, hx, matrix, tone  # noqa: E402
from golem_fonderie import app, euler, rot  # noqa: E402

IDENT = "salamandrex"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000051a1a1")


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- palettes of the brief (dark -> light)
SKIN = pal("#032820", "#063B32", "#07543F", "#087A55", "#159B67", "#38C98A", "#70DFA1", "#A5F2B1")   # main = 4
BELLY = pal("#356B4D", "#538957", "#72AD69", "#8FCB7C", "#B6E8A0")                                 # main = 3
CRYSTAL = pal("#033B34", "#075344", "#087D62", "#12A875", "#2AD99A", "#64EFB4", "#A9FFD9", "#D9FFF0")
EYE = {"iris": hx("#D9F5A3"), "ring": hx("#8DDC73"), "pupil": hx("#183F2D"), "shadow": hx("#0C291F"),
       "glint": hx("#F4FFE4"), "glint2": hx("#B9F2A0")}

# light: from above, from the front (-z) and slightly from the creature's left (-x)
LIGHT = (lambda v: [a / math.sqrt(sum(b * b for b in v)) for a in v])([-0.28, 0.82, -0.5])
NORMALS = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0),
           "down": (0, -1, 0)}


def light_step(d):
    """Tone offset of a face from the cosine between its normal and the light."""
    return 1 if d > 0.7 else 0 if d > 0.2 else -1 if d > -0.35 else -2 if d > -0.7 else -3


# --------------------------------------------------------------------------- painters: (w, h, rng, face, step)
def patches(w, h, r, area):
    """Large soft facets: Voronoi cells over the face; a cell high on the face tends lighter, low tends darker (the
    facets follow the volume instead of random noise)."""
    n = max(1, round(w * h / area))
    seeds = [(r.uniform(0, w), r.uniform(0, h)) for _ in range(n)]
    offs = []
    for sx, sy in seeds:
        v = sy / max(1, h)
        offs.append(r.choice((0, 0, 0, 1)) if v < 0.35 else r.choice((0, 0, -1)) if v > 0.7 else r.choice((0, 0, 0, 0, 1, -1)))
    grid = []
    for y in range(h):
        row = []
        for x in range(w):
            k = min(range(n), key=lambda i: (seeds[i][0] - x - 0.5) ** 2 + (seeds[i][1] - y - 0.5) ** 2)
            row.append(offs[k])
        grid.append(row)
    return grid


def skin(base=4, area=70, crest=False):
    def f(w, h, r, face, step):
        """Polished emerald skin: big discreet facets, a lit top edge with a few turquoise pixels on faces facing
        the light, a darker hollow row low on the sides. `crest`: a faint crystallised line on the top face."""
        b = base + step
        g = patches(w, h, r, area)
        px = [[tone(SKIN, b + g[y][x]) for x in range(w)] for y in range(h)]
        if face not in ("up", "down") and h >= 4:
            px[h - 1] = [tone(SKIN, b - 1)] * w
            if step >= 0:
                px[0] = [tone(SKIN, b + 1)] * w
                for x in range(1, w - 1, 5):
                    px[0][x] = tone(SKIN, b + 2)
        if face == "up" and step >= 0 and w * h >= 30:
            for _ in range(max(1, w * h // 120)):
                px[r.randrange(h)][r.randrange(w)] = tone(SKIN, b + 2)
        if crest and face == "up" and w >= 5:
            c = w // 2
            for y in range(1, h - 1, 4):
                px[y][c] = CRYSTAL[5]
                if y + 1 < h:
                    px[y + 1][c] = CRYSTAL[4]
        return px
    return f


def belly(w, h, r, face, step):
    """Belly: softer greens, very large patches, no crystal facets; shadow on its lowest row."""
    b = 3 + max(-2, min(1, step))
    g = patches(w, h, r, 160)
    px = [[tone(BELLY, b + (g[y][x] if g[y][x] < 1 else 0)) for x in range(w)] for y in range(h)]
    if face not in ("up", "down") and h >= 3:
        px[h - 1] = [tone(BELLY, b - 1)] * w
    return px


def crystal(w, h, r, face, step):
    """Cut emerald (density 2): every face one plane split in two along a diagonal (two sharp tones), a clear edge on
    the lit side, near-white pixels only at the top of lit faces, darker base rows where it leaves the skin."""
    b = {1: 5, 0: 3, -1: 2, -2: 1, -3: 0}[step]
    px = []
    for y in range(h):
        row = []
        for x in range(w):
            above = x * h > y * w if face in ("north", "east") else (w - 1 - x) * h > y * w
            row.append(tone(CRYSTAL, b + (1 if above else 0)))
        px.append(row)
    if step >= 0 and w >= 3:
        for y in range(h):
            px[y][0] = tone(CRYSTAL, b + 2)
        px[0][0] = CRYSTAL[7]
        if h >= 3:
            px[1][0] = CRYSTAL[6]
    if w >= 3:
        for y in range(h):
            px[y][w - 1] = tone(CRYSTAL, b - 1)
    if face not in ("up", "down") and h >= 6:
        px[h - 1] = [CRYSTAL[0]] * w
        px[h - 2] = [tone(CRYSTAL, min(b, 2) - 1)] * w
    return px


def eye(outer):
    def f(w, h, r, face, step):
        """Big slightly oval eye (density 2) on its outer face: dark shadow rim, light green ring, pale iris, round
        pupil a little forward, one main glint high and a small one low: alive and calm."""
        px = [[SKIN[2]] * w for _ in range(h)]
        if face != outer:
            return px
        cx, cy = (w - 1) / 2, (h - 1) / 2
        rx, ry = w / 2, h / 2 * 0.9
        fwd = 1 if outer == "west" else -1        # u towards the front of the head on this face
        for y in range(h):
            for x in range(w):
                d = math.hypot((x - cx) / rx, (y - cy) / ry)
                if d <= 1.0:
                    px[y][x] = EYE["shadow"]
                if d <= 0.82:
                    px[y][x] = EYE["ring"]
                if d <= 0.66:
                    px[y][x] = EYE["iris"]
                if math.hypot((x - cx - fwd * 0.6) / rx, (y - cy) / ry) <= 0.3:
                    px[y][x] = EYE["pupil"]
        gx, gy = int(round(cx + fwd * rx * 0.3)), int(round(cy - ry * 0.4))
        for dx, dy in ((0, 0), (fwd, 0), (0, 1)):
            if 0 <= gx + dx < w and 0 <= gy + dy < h:
                px[gy + dy][gx + dx] = EYE["glint"]
        bx, by = int(round(cx - fwd * rx * 0.35)), int(round(cy + ry * 0.4))
        px[by][bx] = EYE["glint2"]
        return px
    return f


def muzzle(nostrils=False):
    def f(w, h, r, face, step):
        """Muzzle: skin; `nostrils`: two small dark openings high on the front."""
        px = skin(4, 50)(w, h, r, face, step)
        if nostrils and face == "north" and w >= 8 and h >= 3:
            for x in (w // 2 - 4, w // 2 + 3):
                px[1][x] = SKIN[0]
                px[1][x + 1 if x + 1 < w else x] = SKIN[1]
        return px
    return f


def jaw(w, h, r, face, step):
    """Lower jaw: skin, a thin shadow line on its top edge (closed mouth), pale belly green underneath."""
    if face == "down":
        return belly(w, h, r, face, step)
    px = skin(4, 60)(w, h, r, face, step)
    if face in ("north", "east", "west"):
        px[0] = [SKIN[1]] * w
    return px


def joint(light=False):
    def f(w, h, r, face, step):
        """Joint: one tone darker where the limb leaves the body; elbows and knees get a lighter facet on top."""
        px = skin(3 if not light else 4, 40)(w, h, r, face, step)
        if light and face in ("up", "north", "west") and w >= 4 and h >= 4:
            for y in range(1, min(3, h - 1)):
                for x in range(1, w // 2 + 1):
                    px[y][x] = tone(SKIN, 4 + step + 1)
        return px
    return f


def toe(w, h, r, face, step):
    """Toe (density 2): short and round, rounded lighter end, no claw."""
    px = skin(4, 30)(w, h, r, face, step)
    if face == "north":
        px = [[tone(SKIN, 5 + min(0, step)) if 0 < x < w - 1 or 0 < y < h - 1 else tone(SKIN, 3 + step)
               for x in range(w)] for y in range(h)]
    return px


PAINTERS = {
    "skin": skin(4, 45), "back": skin(4, 45, crest=True), "flank": skin(4, 50), "head": skin(4, 45),
    "head_top": skin(5, 45), "limb": skin(4, 40), "tail": skin(4, 45, crest=True), "tail_end": skin(4, 35),
    "belly": belly, "jaw": jaw, "muzzle": muzzle(), "muzzle_tip": (muzzle(True), 2),
    "joint": joint(), "joint_light": joint(True), "toe": (toe, 2), "crystal": (crystal, 2),
    "eye_r": (eye("east"), 2), "eye_l": (eye("west"), 2),
}
MATERIAL_TEXT = {
    "skin": "peau d'émeraude (vert principal #159B67, facettes discrètes)",
    "back": "peau d'émeraude, ligne cristallisée sur le dessus",
    "flank": "peau d'émeraude, grandes facettes", "head": "peau d'émeraude", "head_top": "peau d'émeraude claire",
    "limb": "peau d'émeraude", "tail": "peau d'émeraude, dessus cristallisé", "tail_end": "peau d'émeraude",
    "belly": "ventre vert pâle (#8FCB7C)", "jaw": "peau, ligne d'ombre de la bouche, dessous vert pâle",
    "muzzle": "peau d'émeraude", "muzzle_tip": "peau, deux narines sombres", "joint": "peau plus sombre (jonction)",
    "joint_light": "peau, facette claire sur le dessus", "toe": "peau, bout arrondi plus clair",
    "crystal": "émeraude taillée (facettes nettes, reflets presque blancs)",
    "eye_r": "œil (iris #D9F5A3, pupille #183F2D)", "eye_l": "œil (iris #D9F5A3, pupille #183F2D)",
}


# --------------------------------------------------------------------------- geometry helpers
def norm(v):
    n = math.sqrt(sum(a * a for a in v))
    return [a / n for a in v]


def cross(a, b):
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def frame(axis, hint, along):
    """Rotation whose local `along` axis (y or z) is `axis`, the other one as close as possible to `hint`."""
    a = norm(axis)
    o = norm([hint[i] - dot(hint, a) * a[i] for i in range(3)])
    y, z = (a, o) if along == "y" else (o, a)
    x = cross(y, z)
    return [[x[i], y[i], z[i]] for i in range(3)]


def rod(p_from, p_to, w, t, along, hint, ext=0.0):
    """Box from p_from to p_to, lengthened by `ext` at both ends: along='y' for a leg segment or a crystal (p_from is
    the top end), along='z' for a tail segment or a toe (north face towards p_to)."""
    d = [p_from[i] - p_to[i] for i in range(3)]
    L = math.sqrt(dot(d, d)) + 2 * ext
    c = [(p_from[i] + p_to[i]) / 2 for i in range(3)]
    return c, ((w, L, t) if along == "y" else (w, t, L)), frame(d, hint, along)


def spin(v, axis, deg):
    k, a = norm(axis), math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    kv = cross(k, v)
    return [v[i] * c + kv[i] * s + k[i] * dot(k, v) * (1 - c) for i in range(3)]


def direction(back_deg, out_deg, side):
    """Unit vector leaning backward (+z) by back_deg and outward (side * x) by out_deg from the vertical."""
    return norm([side * math.tan(math.radians(out_deg)), 1, math.tan(math.radians(back_deg))])


def cut_crystal(prefix, base, d, H, W, roll, small=False):
    """Cut emerald growing from `base` along d: wide anchored base, faceted body made of two prisms turned 45 degrees
    from each other (eight planes), narrower upper part, slightly off-centre point. Small ones: base, body, point."""
    side = norm(cross(d, [0, 0, 1])) if abs(d[2]) < 0.99 else [1, 0, 0]

    def at(f, off=0.0):
        return [base[i] + d[i] * f * H + side[i] * off * W for i in range(3)]

    def hint(deg):
        return spin([0, 0, 1], d, roll + deg)
    if small:
        spec = (("base", -0.2, 0.35, 1.1, 1.0, 0), ("corps", 0.2, 0.75, 0.75, 0.7, 45),
                ("pointe", 0.65, 1.0, 0.46, 0.42, 20, 0.1))
    else:
        spec = (("base", -0.15, 0.2, 1.15, 1.0, 0), ("corps", 0.1, 0.7, 0.95, 0.8, 0),
                ("corps_taille", 0.12, 0.66, 0.78, 0.95, 45), ("haut", 0.6, 0.88, 0.66, 0.6, 45, 0.05),
                ("pointe", 0.82, 1.0, 0.42, 0.38, 20, 0.12))
    out = []
    for name, f0, f1, kw, kt, dr, *off in spec:
        o = off[0] if off else 0.0
        out.append((f"{prefix}_{name}", *rod(at(f1, o), at(f0, o * 0.5), W * kw, W * kt, "y", hint(dr))))
    return out


# --------------------------------------------------------------------------- skeleton (drawing units, 16 = 1 block)
NECK = (0, 14, -35)
HEAD_UP = rot(10, 0, 0)          # head raised 10 degrees: it watches something in front of it
# legs: (shoulder or hip, elbow or knee, wrist or ankle, foot centre, foot yaw outward in degrees, foot size)
LEGS = {
    "front": ((14.0, 12.0, -22.0), (19.6, 7.6, -23.6), (20.4, 2.6, -25.6), (20.6, 1.4, -27.6), 18, (8.0, 2.8, 6.4)),
    "back": ((12.5, 12.0, 14.0), (18.6, 8.4, 11.2), (20.0, 2.6, 16.0), (20.4, 1.4, 15.0), 26, (8.6, 2.8, 7.0)),
}
TAIL_START = (0, 13.6, 20.5)
# tail: (length, width, height, pitch in degrees (negative = down), heading in degrees (+ = towards the right side))
TAIL = (
    (5.4, 16.0, 12.0, -6, 0), (5.2, 14.4, 11.0, -10, 12), (5.0, 12.8, 10.0, -12, 22), (4.8, 11.4, 9.2, -12, 26),
    (4.6, 10.0, 8.4, -10, 16), (4.4, 8.8, 7.6, -8, 0), (4.2, 7.6, 6.8, -6, -16), (4.0, 6.4, 5.8, -4, -24),
    (3.8, 5.2, 4.8, -2, -18), (3.6, 4.2, 4.0, 0, -8), (3.4, 3.2, 3.2, 0, 0),
)
# dorsal crystals: (z, height, width, backward tilt, outward tilt, x offset)
DORSAL = (
    (-28.0, 12.8, 4.4, 6, 4, 1.0), (-19.5, 20.8, 5.6, 8, -5, -1.2), (-10.0, 27.2, 7.6, 9, 3, 0.6),
    (0.0, 24.0, 6.6, 11, -5, -1.0), (9.0, 17.6, 5.6, 12, 6, 1.2), (16.5, 12.8, 4.6, 13, -6, -1.0),
    (22.5, 8.0, 3.8, 15, 5, 0.8),
)
SIDE_CRYSTALS = ((7.5, -15.0, 8.0, 3.0, 30), (-7.2, -5.0, 7.0, 2.8, 32), (6.6, 4.5, 5.6, 2.4, 34))


def back_height(z):
    """Top of the back at length z (drawing units), the crystals sink into it."""
    return 22.6 if z < 2 else 22.0 if z < 12 else 21.0 if z < 21 else 19.4


def parts():
    P = []
    G = {"Root": ((0, 0, 0), None), "Body": ((0, 14, -5), "Root")}

    def add(g, name, c, size, r, mat):
        P.append((g, name, list(c), tuple(size), r, mat))

    # ---------------------------------------------------------------- phase 1: body, low and long, wider at the front
    add("Body", "torse_avant", (0, 14.4, -21.0), (31, 13.6, 16), (2, 0, 0), "skin")
    add("Body", "torse_milieu", (0, 14.6, -7.0), (30, 14.0, 16), (0, 0, 0), "skin")
    add("Body", "torse_arriere", (0, 14.2, 5.0), (28, 13.0, 12), (-2, 0, 0), "skin")
    add("Body", "dos_avant", (0, 21.8, -16.0), (21, 2.4, 18), (2, 0, 0), "back")
    add("Body", "dos_arriere", (0, 21.4, 2.0), (19, 2.2, 18), (-2, 0, 0), "back")
    for s, side in ((1, "droit"), (-1, "gauche")):
        add("Body", f"flanc_{side}", (15.2 * s, 13.0, -10.0), (4.4, 10.0, 32), (0, -3 * s, 4 * s), "flank")
        add("Body", f"epaule_{side}", (13.6 * s, 15.0, -22.0), (6.0, 9.0, 11), (0, 0, 8 * s), "skin")
    add("Body", "ventre", (0, 7.4, -9.0), (31, 3.2, 40), (0, 0, 0), "belly")
    # neck: short, as wide as the head's back, following the raised head
    add("Body", "cou", (0, 14.6, -32.0), (27, 12.6, 9), (5, 0, 0), "skin")
    add("Body", "gorge", (0, 8.8, -32.0), (23, 3.0, 9), (5, 0, 0), "belly")
    # pelvis: narrower than the chest, start of the hind legs and the tail
    add("Body", "bassin", (0, 13.8, 16.0), (26, 12.0, 13), (0, 0, 0), "skin")
    add("Body", "bassin_dessus", (0, 20.2, 16.0), (18, 2.0, 11), (-3, 0, 0), "back")
    add("Body", "ventre_bassin", (0, 7.6, 15.0), (22, 3.2, 12), (0, 0, 0), "belly")
    for s, side in ((1, "droite"), (-1, "gauche")):
        add("Body", f"hanche_{side}", (12.4 * s, 12.6, 14.0), (5.0, 8.0, 9), (0, 0, 6 * s), "joint")

    # ---------------------------------------------------------------- head: wide, flat, round muzzle, raised 10 deg
    G["Head"] = (NECK, "Body")
    on_head = attach(NECK, HEAD_UP)

    def hd(name, c, size, r, mat, g="Head"):
        cc, M = on_head(c, r)
        add(g, name, cc, size, M, mat)

    hd("crane", (0, 17.6, -45.0), (24, 7.6, 17), (0, 0, 0), "head")
    hd("crane_dessus", (0, 21.8, -44.0), (19, 1.4, 13), (0, 0, 0), "head_top")
    hd("museau", (0, 15.8, -55.5), (21, 6.4, 6), (0, 0, 0), "muzzle")
    hd("museau_bout", (0, 15.6, -59.4), (15, 5.4, 2), (0, 0, 0), "muzzle_tip")
    G["Jaw"] = (on_head((0, 12.4, -38.0), (0, 0, 0))[0], "Head")
    hd("machoire", (0, 11.0, -48.0), (23, 4.0, 21), (0, 0, 0), "jaw", "Jaw")
    hd("menton", (0, 11.2, -58.9), (15, 3.2, 2.4), (0, 0, 0), "jaw", "Jaw")
    for s, side in ((1, "droite"), (-1, "gauche")):
        hd(f"joue_{side}", (11.6 * s, 14.6, -45.0), (3.6, 7.4, 14), (0, 10 * s, 0), "head")
        hd(f"arcade_{side}", (8.6 * s, 22.4, -46.5), (6.5, 2.2, 8), (0, 0, -8 * s), "head_top")
        hd(f"orbite_{side}", (12.2 * s, 19.4, -47.0), (2.6, 7.8, 7.8), (0, 15 * s, 0), "joint")
        hd(f"oeil_{side}", (13.3 * s, 19.6, -47.2), (1.4, 6.6, 6.6), (0, 15 * s, 0), "eye_r" if s > 0 else "eye_l")

    # ---------------------------------------------------------------- legs: sprawled zigzag, joints a bit wider
    for kind, (S0, E0, W0, F0, yaw, fsize) in LEGS.items():
        for s, side, bone in ((1, "droite", "right"), (-1, "gauche", "left")):
            S, E, W, F = ([p[0] * s, p[1], p[2]] for p in (S0, E0, W0, F0))
            up_, low = (f"Leg_{kind}_{bone}", f"Forearm_{bone}" if kind == "front" else f"Shin_{bone}")
            G[up_] = (tuple(S), "Root")
            G[low] = (tuple(E), up_)
            u, l_ = ("bras", "avant_bras") if kind == "front" else ("cuisse", "jambe")
            j1, j2, j3 = ("epaule_rotule", "coude", "poignet") if kind == "front" else ("hanche_rotule", "genou", "cheville")
            big = 1.0 if kind == "front" else 1.12          # knees more marked than elbows
            add(up_, f"{j1}_{side}", S, (7.6, 7.6, 7.6), (0, 0, 0), "joint")
            add(up_, f"{u}_{side}", *rod(S, E, 7.0 * big, 7.0, "y", [0, 0, 1], 0.8), "limb")
            add(low, f"{j2}_{side}", E, (7.6 * big, 7.0 * big, 7.6 * big), (0, 0, 8 * s), "joint_light")
            add(low, f"{l_}_{side}", *rod(E, W, 6.0, 6.0, "y", [0, 0, 1], 0.8), "limb")
            add(low, f"{j3}_{side}", (W[0], W[1], W[2]), (6.8, 3.6, 6.4), (0, -yaw * s, 0), "joint")
            # wide flat foot turned outward, four short round toes fanned out and touching the ground
            foot = attach(F, rot(0, -yaw * s, 0))
            fw, fh, fl = fsize
            pieces = [(f"pied_{side}", F, fsize, (0, 0, 0), "limb")]
            front = F[2] - fl / 2
            for k in range(4):
                t = k / 3 - 0.5
                root = (F[0] + t * (fw - 2.0), 1.1, front + 0.6)
                ang = math.radians(t * 44)
                L = 4.0 if abs(t) < 0.3 else 3.4
                tip = (root[0] + math.sin(ang) * L, 1.0, root[2] - math.cos(ang) * L)
                pieces.append((f"doigt_{kind}_{side}_{k + 1}", *rod(root, tip, 2.0, 2.0, "z", [0, 1, 0]), "toe"))
            for name, c, size, r, mat in pieces:
                cc, M = foot(c, r)
                add(low, name, cc, size, M, mat)

    # ---------------------------------------------------------------- tail: 11 segments, soft S seen from above
    p, parent = list(TAIL_START), "Body"
    for k, (L, w, h, pitch, heading) in enumerate(TAIL):
        a, b = math.radians(pitch), math.radians(heading)
        d = [math.cos(a) * math.sin(b), math.sin(a), math.cos(a) * math.cos(b)]
        q = [p[i] + d[i] * L for i in range(3)]
        g = f"Tail_{k + 1}"
        G[g] = (tuple(p), parent)
        add(g, f"queue_{k + 1}", *rod(p, q, w, h, "z", [0, 1, 0], 1.4 if k == 0 else 0.9),
            "tail" if k < 7 else "tail_end")
        parent, p = g, q

    # ---------------------------------------------------------------- phase 3: crystals
    for k, (z, H, W, back, out, x) in enumerate(DORSAL):
        base = [x, back_height(z) - 1.6, z]
        side = 1 if out >= 0 else -1
        for name, c, size, M in cut_crystal(f"cristal_dos_{k + 1}", base, direction(back, abs(out), side), H, W,
                                            roll=12 * (k % 3) - 12):
            add("Body", name, c, size, M, "crystal")
    for k, (x, z, H, W, out) in enumerate(SIDE_CRYSTALS):
        base = [x, back_height(z) - 2.2, z]
        for name, c, size, M in cut_crystal(f"cristal_flanc_{k + 1}", base, direction(10, out, 1 if x > 0 else -1),
                                            H, W, roll=20 * k, small=True):
            add("Body", name, c, size, M, "crystal")
    # two small crystals behind the eye ridges, leaning back, never over the eyes
    for s, side in ((1, "droit"), (-1, "gauche")):
        for name, c, size, M in cut_crystal(f"cristal_tete_{side}", [8.4 * s, 22.4, -42.0],
                                            direction(22, 12, s), 5.6, 2.6, roll=15 * s, small=True):
            hd(name, c, size, M, "crystal")
    # three small crystals at the start of the tail, following its axis (leaning back with it)
    for k, (seg, H, W) in enumerate(((1, 6.4, 3.0), (2, 4.8, 2.5), (3, 3.4, 2.0))):
        g, n, c, size, M, m = next(q for q in P if q[1] == f"queue_{seg}")
        Mm = matrix(M)
        top = [c[i] + Mm[i][1] * size[1] / 2 for i in range(3)]
        for name, cc, sz, R in cut_crystal(f"cristal_queue_{k + 1}", [top[0], top[1] - 1.2, top[2]],
                                           direction(32, 0, 1), H, W, roll=10 * k, small=True):
            add(g, name, cc, sz, R, "crystal")

    order, done = [], set()
    while len(order) < len(G):
        for k2, (pv, par) in G.items():
            if k2 not in done and (par is None or par in done):
                order.append(k2)
                done.add(k2)
    return P, {k2: G[k2] for k2 in order}


# --------------------------------------------------------------------------- build
def lit(P):
    """One material per piece, painted with the light of each of its faces (real orientation of the cube)."""
    mats, out = {}, []
    for i, (g, n, c, size, r, m, raw) in enumerate(P):
        M = matrix(r)
        steps = {f: light_step(dot(app(M, v), LIGHT)) for f, v in NORMALS.items()}
        p = PAINTERS[m]
        fn, dens = p if isinstance(p, tuple) else (p, 1)

        def painter(w, h, rr, face, fn=fn, steps=steps):
            return fn(w, h, rr, face, steps[face])
        key = f"{m}@{i}"
        mats[key] = (painter, dens)
        out.append((g, n, c, size, r, key, raw))
    return out, mats


def build():
    raw, groups = parts()
    P, groups, dims = ground(raw, groups, scale=1.0, center=True)
    P, mats = lit(P)
    uv, atlases, sizes = bake(P, mats, {})
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    data["textures"] = data["textures"][:1]          # nothing glows: no emissive texture
    return P, groups, dims, data, atlases, sizes


VIEWS = (("face", 0, 4), ("profil droit", 90, 3), ("profil gauche", 270, 3), ("dessus", 0, 89),
         ("arrière", 180, 6), ("trois quarts avant", 35, 16), ("trois quarts arrière", 215, 16))


def sheet(data, atlases, sizes, path, size=420):
    """Two rows of four: the seven views of the brief, then the silhouette."""
    import paths  # noqa: F401  (scripts/influcard on the path)
    import apercu as A
    from png_util import write
    els = data["elements"]
    bg = (58, 58, 62, 255)
    imgs = [A.render(els, atlases[0], yaw, pitch, size=size, bg=bg, margin=20) for _, yaw, pitch in VIEWS]
    imgs.append([[(20, 20, 22, 255) if px != bg else (205, 205, 210, 255) for px in row] for row in imgs[5]])
    rows = [imgs[:4], imgs[4:]]
    write(path, size * 4, size * 2, [sum((im[y] for im in row), []) for row in rows for y in range(size)])


FUNCTIONS = (
    ("torse", "tronc"), ("dos", "ligne du dos"), ("flanc", "flanc arrondi"), ("epaule_rotule", "articulation de l'épaule"),
    ("epaule", "épaule (avant du tronc plus large)"), ("ventre", "ventre"), ("cou", "cou"), ("gorge", "dessous du cou"),
    ("bassin", "bassin"), ("hanche_rotule", "articulation de la hanche"), ("hanche", "hanche"), ("crane", "crâne aplati"),
    ("museau", "museau arrondi"), ("machoire", "mâchoire inférieure"), ("menton", "avant de la mâchoire"),
    ("joue", "joue"), ("arcade", "arcade oculaire"), ("orbite", "contour de l'œil"), ("oeil", "œil"),
    ("bras", "segment supérieur de patte avant"), ("coude", "coude"), ("avant_bras", "segment inférieur de patte avant"),
    ("poignet", "poignet"), ("cuisse", "segment supérieur de patte arrière"), ("genou", "genou"),
    ("jambe", "segment inférieur de patte arrière"), ("cheville", "cheville"), ("pied", "pied"), ("doigt", "doigt"),
    ("queue", "segment de queue"), ("cristal_dos", "grand cristal dorsal"), ("cristal_flanc", "cristal secondaire du dos"),
    ("cristal_tete", "petit cristal de la tête"), ("cristal_queue", "petit cristal de la queue"),
)


def table(P, groups, path):
    lines = ["| Élément | Fonction | Os (raccordé à) | Centre x, y, z | Taille x, y, z | Rotation x, y, z | Matière |",
             "| ------- | -------- | --------------- | -------------- | -------------- | ---------------- | ------- |"]
    for g, n, c, size, r, m, raw in P:
        fn = next((t for pre, t in FUNCTIONS if n.startswith(pre)), "")
        rr = euler(matrix(r))
        parent = groups[g][1]
        lines.append(f"| `{n}` | {fn} | `{g}` ({'`' + parent + '`' if parent else 'racine'}) | "
                     f"{c[0]:.1f}, {c[1]:.1f}, {c[2]:.1f} | {size[0]:.1f}, {size[1]:.1f}, {size[2]:.1f} | "
                     f"{int(round(rr[0]))}, {int(round(rr[1]))}, {int(round(rr[2]))} | {MATERIAL_TEXT[m.split('@')[0]]} |")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    args = sys.argv[1:]
    opts = {}
    for flag in ("--apercu", "--table"):
        if flag in args:
            i = args.index(flag)
            opts[flag] = args[i + 1]
            del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims, data, atlases, sizes = build()
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    body_w = max(q[0] for g, n, c, size, r, m, raw in P if g == "Body" and "cristal" not in n
                 for q in corners(c, size, r)) * 2 / 16
    print(f"{len(P)} volumes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; "
          f"corps {body_w:.2f} de large ; texture {sizes[0]}")
    print("écrit", path)
    if "--apercu" in opts:
        sheet(data, atlases, sizes, opts["--apercu"])
    if "--table" in opts:
        table(P, groups, opts["--table"])
