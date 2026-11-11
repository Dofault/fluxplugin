#!/usr/bin/env python3
"""Harvest Bat (Chauve-souris des Récoltes): farm boss, STATIC Blockbench model (.bbmodel) (2026-11-11).

A real bat, not a humanoid: a small furry body carried by a huge round belly (it emptied the fields night after
night; nothing agricultural is shown), a small head bent towards the ground, two tall pointed ears, a short flat
snout, small glowing red-orange eyes, two wide membrane wings made of long bony fingers, a clawed thumb on each
wrist, two thin hind legs with three hooked claws. Boss touches stay anatomical: reddish marks around the eyes,
dark red marks on the membranes, tufts of fur on the crown, the nape and the shoulders.

Built in the language of the Sea Otter and the Abyss Colossus: a few large boxes per mass, rounded by smaller boxes
tilted a few degrees; every finger is a chain of short tilted segments with a knuckle at each joint; every membrane
is a stack of thin plates laid in the plane of its two fingers (stepped triangle), its trailing edge hollowed between
the finger tips. Painted in plain pixel art at 1 texel per unit (16 per block): flat palette tones dithered pixel by
pixel, the tone chosen by the face (top lighter, front main, sides and back darker, bottom darkest), no soft
gradient, no black outline. Only the eyes glow (emissive texture).

Pose baked in the boxes, bones placed at the joints for a later animation; no animation.

Usage: chauve_souris.py [output folder] [--apercu <sheet.png>]
Front towards north (-z), the monster's right towards +x, 16 units = 1 block. Drawn directly at its brief height:
claw tips at y = 2.4 (0.15 block, the brief keeps it just above the ground), ears up to y = 70.4.
"""
import json
import math
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, export, ground, hx  # noqa: E402
from golem_fonderie import rot  # noqa: E402

IDENT = "chauve_souris"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-0000000c5a75")
FLOAT = 2.4            # claw tips stay 0.15 block above the ground (brief)


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- palettes (brief, dark to light)
BELLY = pal("#573C32", "#80563F", "#A77A56", "#C99C70", "#DAB58C")
THORAX = pal("#35272A", "#4A342E", "#654536", "#806047")
BACK = pal("#29232B", "#382A32", "#49343A", "#60434A")
HEAD = pal("#29232B", "#4A342E", "#654536", "#806047")
MUZZLE = pal("#4B352F", "#72513F", "#9B7354")
NOSE = hx("#211B26")
EAR_OUT = pal("#33252E", "#49303A", "#60404A")
EAR_IN = pal("#743D49", "#A75C60", "#C67B78")
BONE = pal("#362832", "#59424A", "#80606A")
CLAW = pal("#201922", "#49343C", "#8A6264")
MEMBRANE = pal("#301E2B", "#492633", "#652E3B", "#823B49", "#A6535A")
LEG = pal("#201923", "#382832", "#55404A", "#82616A")
TUFT = pal("#33232C", "#4B3039", "#65404A")
MARK = pal("#71333D", "#A44748")
EYE = {"rim": hx("#211A26"), "iris": hx("#C34B35"), "core": hx("#E27A45"), "pupil": hx("#160F18"),
       "glint": hx("#F2C28C")}

# tone shift of each face: light from above, the front holds the main colour
FACE = {"up": 0.85, "north": 0.0, "south": -0.45, "east": -0.5, "west": -0.5, "down": -1.4}


# --------------------------------------------------------------------------- painters (pixel art, dithered tones)
def pick(colours, v, r, spread):
    """Palette tone nearest to v after a random nudge: flat tones mixed pixel by pixel, never a blended colour."""
    i = int(math.floor(v + r.uniform(-spread, spread) + 0.5))
    return colours[max(0, min(len(colours) - 1, i))]


def fur(colours, base, spread=0.42, dots=0.05, front=0.0, belly=False, tufts=0.0):
    def f(w, h, r, face):
        """Fur: main tone by face, dithered; a few darker pixels and short vertical lighter strokes (hair), never a
        horizontal line. `belly`: lower rows of the sides and front sink into shadow, upper rows catch the light."""
        off = FACE[face] + (front if face == "north" else 0.0)
        grid = []
        for y in range(h):
            k = y / max(1, h - 1)
            row = []
            for x in range(w):
                v = base + off
                if belly and face not in ("up", "down"):
                    v += 0.55 if k < 0.22 else (-0.7 * (k - 0.6) / 0.4 if k > 0.6 else 0.0)
                if r.random() < dots:
                    v -= 1.0
                row.append(pick(colours, v, r, spread))
            grid.append(row)
        if face != "down":
            for _ in range(int(w * h * (0.05 + tufts))):
                x, y = r.randrange(w), r.randrange(h)
                lit = pick(colours, base + off + 0.9, r, 0.2)
                for j in range(r.randrange(1, 3)):
                    if y + j < h:
                        grid[y + j][x] = lit
        return grid
    return f


def solid(colours, base, spread=0.35, lit=False):
    """Bone, claw, toe: dithered tone, a light ridge pixel here and there. Segments are turned in every direction,
    so their faces get no tone shift (lit=False): the light of the game shades them."""
    def f(w, h, r, face):
        off = FACE[face] * 0.5 if lit else (-0.5 if face == "down" else 0.0)
        return [[pick(colours, base + off + (0.8 if r.random() < 0.06 else 0), r, spread) for _ in range(w)]
                for _ in range(h)]
    return f


def membrane(base, marks=0.0):
    def f(w, h, r, face):
        """Membrane: deep red-brown, dithered; thin darker veins running from the wrist to the trailing edge (the
        plates are laid across the fan, so veins run along v), a few dark red marks in small irregular clusters."""
        off = 0.0 if face in ("north", "south") else -0.6
        grid = [[pick(MEMBRANE, base + off, r, 0.38) for _ in range(w)] for _ in range(h)]
        if face in ("north", "south") and w >= 4:
            for _ in range(max(1, w // 9)):
                x = r.randrange(w)
                for y in range(h):
                    if r.random() < 0.8:
                        grid[y][x] = pick(MEMBRANE, base - 1.2, r, 0.2)
                    x = max(0, min(w - 1, x + r.choice((0, 0, 0, 1, -1))))
            for _ in range(int(w * h * marks / 6)):
                x, y = r.randrange(w), r.randrange(h)
                for _ in range(r.randrange(2, 5)):
                    if 0 <= x < w and 0 <= y < h:
                        grid[y][x] = MARK[0] if r.random() < 0.6 else MEMBRANE[3]
                    x, y = x + r.choice((-1, 0, 1)), y + r.choice((0, 1))
        return grid
    return f


def ear_inner(w, h, r, face):
    """Inside of the ear: pinkish red-brown, lighter at the front, small dark pixel patterns (living membrane)."""
    grid = [[pick(EAR_IN, 1 + (0.3 if face == "north" else -0.7), r, 0.45) for _ in range(w)] for _ in range(h)]
    for _ in range(max(1, w * h // 7)):
        x, y = r.randrange(w), r.randrange(h)
        grid[y][x] = EAR_IN[0] if r.random() < 0.7 else EAR_OUT[1]
    return grid


def muzzle(w, h, r, face):
    """Snout: lighter front, darker sides, a short dark mouth line in the middle of the bottom row of the front."""
    v = {"north": 2, "up": 1.6, "down": 0.2}.get(face, 0.4)
    grid = [[pick(MUZZLE, v, r, 0.4) for _ in range(w)] for _ in range(h)]
    if face == "north" and w >= 4 and h >= 3:
        for x in range(w // 2 - 1, w // 2 + 1):
            grid[h - 2][x] = NOSE
    return grid


def nose(w, h, r, face):
    """Nose: dark violet black, two nostril pixels on the front."""
    grid = [[NOSE if r.random() < 0.85 else EAR_OUT[0] for _ in range(w)] for _ in range(h)]
    if face == "north" and w >= 4:
        grid[h - 1][1] = grid[h - 1][w - 2] = EYE["pupil"]
    return grid


def eye(w, h, r, face):
    """Eye (painted at 3 texels per unit): dark rim, red-orange iris, lighter core beside a vertical black pupil,
    one tiny light glint. Glows (emissive texture)."""
    if face != "north":
        return [[EYE["rim"]] * w for _ in range(h)]
    grid = []
    cx = w // 2
    for y in range(h):
        row = []
        for x in range(w):
            if x in (0, w - 1) or y in (0, h - 1):
                c = EYE["rim"]
            elif x == cx and 1 <= y <= h - 2:
                c = EYE["pupil"]
            elif abs(x - cx) == 1 and 1 < y < h - 2:
                c = EYE["core"]
            else:
                c = EYE["iris"]
            row.append(c)
        grid.append(row)
    grid[1][1] = EYE["glint"]
    return grid


def eye_mark(w, h, r, face):
    """Reddish area around the eye, melting into the head fur (sparse red pixels on head fur)."""
    grid = fur(HEAD, 1.6)(w, h, r, face)
    for y in range(h):
        for x in range(w):
            if r.random() < 0.45:
                grid[y][x] = MARK[0] if r.random() < 0.65 else MARK[1]
    return grid


MATS = {
    # belly: lightest, main tone on ~65 % of the surface, shade below and on the sides, light on the top front
    "belly": fur(BELLY, 2, front=0.2, belly=True), "belly_low": fur(BELLY, 1.75, belly=True),
    "belly_top": fur(BELLY, 2.1, front=0.1), "belly_side": fur(BELLY, 1.7, belly=True),
    "thorax": fur(THORAX, 2), "back": fur(BACK, 1.2, tufts=0.03), "back_low": fur(BACK, 0.9),
    "shoulder": fur(BACK, 2.1, tufts=0.06), "nape": fur(BACK, 1.8, tufts=0.06),
    "head": fur(HEAD, 1.9), "head_top": fur(HEAD, 2.1), "cheek": fur(HEAD, 1.6),
    "muzzle": muzzle, "nose": nose, "eye": (eye, 3), "eye_mark": (eye_mark, 2),
    "ear": fur(EAR_OUT, 1, spread=0.5, dots=0.03), "ear_in": ear_inner,
    "tuft": solid(TUFT, 1.2, 0.5, lit=True),
    "bone": solid(BONE, 1.35, 0.4), "bone_fine": (solid(BONE, 1.45, 0.4), 2), "knuckle": (solid(BONE, 1.6, 0.4), 2),
    "claw": (solid(CLAW, 0.5, 0.45), 2),
    "membrane_root": membrane(1.3, 0.05), "membrane": membrane(2, 0.12), "membrane_tip": membrane(2.6, 0.18),
    "leg": fur(LEG, 1.2, spread=0.4, dots=0.02), "toe": (solid(LEG, 2), 2), "toe_claw": (solid(LEG, 0.3, 0.3), 2),
}
GLOW = {"eye": (eye, 3)}
GLOW_COLOURS = set(EYE.values())


# --------------------------------------------------------------------------- vector helpers
def sub(a, b):
    return [a[i] - b[i] for i in range(3)]


def add_v(a, b, k=1.0):
    return [a[i] + b[i] * k for i in range(3)]


def norm(a):
    n = math.sqrt(sum(v * v for v in a))
    return [v / n for v in a]


def cross(a, b):
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


def dot(a, b):
    return sum(a[i] * b[i] for i in range(3))


def frame(u, v):
    """Rotation whose local x runs along u and local y along v (made orthogonal to u); local z = x cross y."""
    u = norm(u)
    v = norm(sub(v, [c * dot(u, v) for c in u]))
    z = cross(u, v)
    return [[u[i], v[i], z[i]] for i in range(3)]


def along(d, hint=(0, 0, 1)):
    """Rotation whose local z runs along d (a segment drawn as a box (w, h, length))."""
    d = norm(d)
    ref = list(hint) if abs(dot(norm(list(hint)), d)) < 0.95 else [0, 1, 0]
    x = norm(cross(ref, d))
    y = cross(d, x)
    return [[x[i], y[i], d[i]] for i in range(3)]


def polar(origin, length, up_deg, back=0.0):
    """Point reached from origin going outwards (+x) at up_deg above the horizontal, `back` units towards +z."""
    a = math.radians(up_deg)
    return [origin[0] + length * math.cos(a), origin[1] + length * math.sin(a), origin[2] + back]


# --------------------------------------------------------------------------- skeleton (drawing units, right side)
NECK = (0, 44, 0)
SHOULDER = (8.5, 42.4, 2.5)          # wing root, Y = 2.65 blocks (brief)
HIP = (5.2, 13.5, 2.0)

GROUPS = {"Root": ((0, 0, 0), None), "Body": ((0, 24, 0), "Root"), "Head": (NECK, "Body")}


def wing_points():
    """Right wing joints and finger polylines (brief angles; mirrored for the left wing). The arm goes out and a
    little backwards, the upper finger climbs and curls up at the tip, the three lower fingers fan downwards, each
    bending a little more towards its tip."""
    S = list(SHOULDER)
    E = polar(S, 9.5, 15, back=2.5)                      # humerus, 15 degrees
    W = polar(E, 11.0, 25, back=1.5)                     # forearm, 25 degrees (arm = 1.3 block)
    fingers = {}

    def chain(start, specs):
        pts, p = [start], start
        for length, up, back in specs:
            p = polar(p, length, up, back)
            pts.append(p)
        return pts
    fingers["upper"] = chain(W, ((11, 30, -0.8), (10.5, 34, -0.8), (7.5, 37, -0.6), (3.6, 44, -0.2)))   # 2.1 blocks
    fingers["f1"] = chain(W, ((11, -10, 0.8), (10.5, -15, 0.8), (9, -21, 0.7)))                           # 1.9
    fingers["f2"] = chain(W, ((9.5, -25, 1.0), (9, -30, 1.0), (7.9, -37, 0.8)))                           # 1.65
    fingers["f3"] = chain(W, ((8, -40, 1.0), (7.5, -45, 1.0), (6.1, -53, 0.8)))                           # 1.35
    flank = [9.6, 25.0, 3.5]                             # where the wing membrane meets the body side
    return S, E, W, fingers, flank


def parts():
    P = []

    def add(g, name, c, size, r, mat):
        P.append((g, name, list(c), list(size), r, mat))

    def seg(g, name, a, b, w, h, mat, hint=(0, 0, 1), over=0.8):
        d = sub(b, a)
        ln = math.sqrt(dot(d, d))
        add(g, name, [(a[i] + b[i]) / 2 for i in range(3)], (w, h, ln + over), along(d, hint), mat)

    # ---------------------------------------------------------------- belly: the dominant volume (y 10.4 to 36)
    # Vertical egg built from five boxes slightly offset and tilted; leans forward (+x rotation pushes the bottom
    # to the front), bulges to the front and to the sides, widest in its middle and lower part.
    add("Body", "ventre", (0, 22, -2.6), (12.8, 16, 16.8), (6, 0, 0), "belly")
    add("Body", "ventre_bas", (0, 15.0, -2.4), (14.4, 8.8, 15.2), (8, 0, 0), "belly_low")
    add("Body", "ventre_haut", (0, 31.2, -1.4), (12, 7.2, 13.6), (2, 0, 0), "belly_top")
    for s in (1, -1):
        add("Body", f"ventre_cote_{'d' if s == 1 else 'g'}", (7.6 * s, 20.5, -1.2), (5.6, 12, 12.8),
            (5, 7 * s, 5 * s), "belly_side")
        add("Body", f"ventre_arrondi_{'d' if s == 1 else 'g'}", (5.8 * s, 12.6, -2.2), (5, 4.4, 11.5),
            (8, 0, -22 * s), "belly_low")
    # rounding: front corners cut at 45 degrees, front edges at the bottom and top bevelled, a strong front bulge
    for s in (1, -1):
        sd = "d" if s == 1 else "g"
        add("Body", f"ventre_coin_{sd}", (6.4 * s, 20.0, -7.6), (6, 13, 6), (6, 45, 0), "belly")
        add("Body", f"ventre_coin_bas_{sd}", (5.6 * s, 14.2, -5.6), (4.0, 4.6, 8.0), (30, 20 * s, -20 * s), "belly_low")
        add("Body", f"ventre_coin_haut_{sd}", (5.0 * s, 29.6, -6.6), (4.4, 5.0, 7.0), (-24, -16 * s, 22 * s), "belly_top")
    add("Body", "ventre_avant", (0, 20.0, -11.6), (10, 12.5, 3.2), (8, 0, 0), "belly")      # front bulge
    add("Body", "ventre_avant_2", (0, 19.0, -13.4), (6.8, 8.5, 1.6), (8, 0, 0), "belly")
    add("Body", "ventre_avant_bas", (0, 12.6, -9.0), (10.5, 4.6, 5), (48, 0, 0), "belly_low")
    add("Body", "ventre_avant_haut", (0, 29.2, -8.6), (9.5, 4.6, 4.6), (-38, 0, 0), "belly_top")
    add("Body", "ventre_dessous", (0, 11.2, -2.4), (10, 1.6, 11.5), (8, 0, 0), "belly_low")

    # ---------------------------------------------------------------- thorax, back, shoulders
    add("Body", "thorax", (0, 38.2, 0.6), (13.6, 13.6, 13.6), (-4, 0, 0), "thorax")
    add("Body", "poitrail", (0, 34.5, -5.2), (10, 6, 3), (10, 0, 0), "thorax")               # blends into belly
    # back slightly domed: three dark layers, the middle one bulging out
    add("Body", "dos", (0, 31, 7.4), (13.5, 16, 5), (6, 0, 0), "back")
    add("Body", "dos_bombe", (0, 31.5, 9.6), (10, 11, 2.4), (8, 0, 0), "back")
    add("Body", "dos_bas", (0, 17.5, 7.0), (12.5, 12, 5), (-4, 0, 0), "back_low")
    for s in (1, -1):
        sd = "d" if s == 1 else "g"
        add("Body", f"dos_arrondi_{sd}", (6.4 * s, 28.0, 7.4), (3.6, 18, 4.4), (4, -30 * s, 0), "back")
    add("Body", "nuque", (0, 44.2, 5.6), (11, 6, 5), (-14, 0, 0), "nape")
    for s in (1, -1):
        sd = "d" if s == 1 else "g"
        add("Body", f"epaule_{sd}", (7.4 * s, 41.6, 2.2), (5.2, 6, 7.5), (0, 0, -14 * s), "shoulder")

    # ---------------------------------------------------------------- head: small, bent down and forwards
    on_head = attach(NECK, rot(-13, 0, 0))

    def hd(name, c, size, r, mat, bone="Head"):
        cc, M = on_head(c, r)
        add(bone, name, cc, size, M, mat)

    hd("crane", (0, 49.2, -4.6), (12, 11, 10), (0, 0, 0), "head")
    hd("joues", (0, 46.6, -4.2), (13.6, 6, 8.5), (0, 0, 0), "cheek")              # wider at the cheeks
    hd("crane_dessus", (0, 55.0, -4.2), (9.5, 1.8, 8), (0, 0, 0), "head_top")
    for s in (1, -1):
        sd = "d" if s == 1 else "g"
        hd(f"crane_arrondi_{sd}", (5.0 * s, 53.6, -4.4), (3.4, 3.6, 8.6), (0, 0, -30 * s), "head_top")
        hd(f"joue_avant_{sd}", (4.6 * s, 46.0, -9.2), (3.4, 5, 2.4), (0, 22 * s, 0), "cheek")
    hd("front", (0, 50.4, -9.8), (8.4, 6.4, 1.4), (0, 0, 0), "head")              # narrower towards the snout
    # snout: short, flat, at the front and bottom of the face; nose = flat bar + diamond (reads as a triangle)
    hd("museau", (0, 45.8, -11.0), (6.6, 4.4, 3.4), (0, 0, 0), "muzzle")
    hd("nez", (0, 47.6, -12.95), (3.8, 1.3, 1.2), (0, 0, 0), "nose")
    hd("nez_pointe", (0, 46.9, -12.85), (2.0, 2.0, 1.0), (0, 0, 45), "nose")
    # eyes: small, either side of the snout, turned slightly outwards, on a reddish mark
    for s in (1, -1):
        sd = "d" if s == 1 else "g"
        hd(f"marque_oeil_{sd}", (3.6 * s, 49.6, -9.95), (4.0, 3.4, 0.6), (0, 14 * s, 0), "eye_mark")
        hd(f"oeil_{sd}", (3.6 * s, 49.6, -10.3), (2.4, 2.0, 0.8), (0, 14 * s, 0), "eye")

    # ears: tall, pointed, spreading; right ear 15 degrees out, left 12 (brief), a little tipped back
    for s, tilt in ((1, 15), (-1, 12)):
        sd = "d" if s == 1 else "g"
        bone = f"Ear_{'right' if s == 1 else 'left'}"
        base = (3.4 * s, 54.2, -3.4)
        GROUPS[bone] = (on_head(base, (0, 0, 0))[0], "Head")
        on_ear = attach(base, rot(6, 0, -tilt * s))
        # tiers from the wide thick base to the point: (height, width, depth); inner membrane on the front
        # tiers from the wide base to the point: (height, width); each is a thin back plate with a dark rim on both
        # edges standing forwards, the pink inner membrane recessed between the rims (a cupped leaf, not a cone)
        tiers = ((4.4, 7.6), (4.2, 6.4), (3.4, 4.6), (2.4, 2.6))
        y = base[1]
        for k, (th, tw) in enumerate(tiers):
            z0 = base[2] + 0.15 * k
            parts_ = [(f"oreille_{sd}_{k + 1}", (base[0], y + th / 2, z0 + 0.3), (tw, th + 0.5, 1.4), "ear")]
            if k < 3:
                for e_, sg in (("ext", 1), ("int", -1)):
                    parts_.append((f"oreille_{sd}_bord_{e_}_{k + 1}", (base[0] + sg * (tw / 2 - 0.5), y + th / 2, z0 - 0.75),
                                   (1.0, th + 0.5, 1.4), "ear"))
                parts_.append((f"oreille_{sd}_membrane_{k + 1}", (base[0], y + th / 2 + 0.2, z0 - 0.55),
                               (tw - 1.9, th + 0.9, 0.4), "ear_in"))
            for name, c, size, mat in parts_:
                cc, M = on_head(*on_ear(c, (0, 0, 0)))
                add(bone, name, cc, size, M, mat)
            y += th
        tip = (base[0], y + 0.5, base[2] + 0.75)
        cc, M = on_head(*on_ear(tip, (0, 0, 45)))
        add(bone, f"oreille_{sd}_pointe", cc, (1.6, 1.6, 1.0), M, "ear")

    # tufts: crown (head), nape and shoulders (body); small tilted cubes
    for name, c, size, r in (("touffe_crane_1", (-1.6, 56.4, -3.2), (2, 2.6, 2), (14, 0, 18)),
                             ("touffe_crane_2", (1.4, 56.5, -1.8), (1.8, 2.4, 1.8), (-12, 10, -16)),
                             ("touffe_crane_3", (0.2, 56.0, 0.2), (2.2, 2.0, 2.2), (-28, 0, 4))):
        hd(name, c, size, r, "tuft")
    for name, c, size, r in (("touffe_nuque_1", (-2.4, 47.2, 7.6), (2.4, 2.6, 2.2), (-30, 0, 16)),
                             ("touffe_nuque_2", (1.8, 46.8, 8.2), (2.2, 2.4, 2.2), (-36, 0, -14)),
                             ("touffe_nuque_3", (0, 45.0, 9.0), (2.6, 2.2, 2.0), (-44, 0, 0)),
                             ("touffe_epaule_d1", (8.6, 45.0, 1.4), (2.0, 2.4, 2.0), (0, 10, -28)),
                             ("touffe_epaule_d2", (9.6, 44.0, 4.2), (1.8, 2.0, 1.8), (-18, 0, -36)),
                             ("touffe_epaule_g1", (-8.4, 45.0, 2.0), (2.0, 2.6, 2.0), (0, -12, 24)),
                             ("touffe_epaule_g2", (-9.4, 43.8, 4.8), (1.8, 2.0, 1.8), (-20, 0, 34))):
        add("Body", name, c, size, r, "tuft")

    # ---------------------------------------------------------------- wings
    S, E, W, fingers, flank = wing_points()
    for s in (1, -1):
        sd = "d" if s == 1 else "g"
        side = "right" if s == 1 else "left"

        def m(p):
            return [p[0] * s, p[1], p[2]]
        wing, hand = f"Wing_{side}", f"Hand_{side}"
        GROUPS[wing] = (m(S), "Body")
        GROUPS[hand] = (m(W), wing)
        Sm, Em, Wm, Fm = m(S), m(E), m(W), {k: [m(p) for p in v] for k, v in fingers.items()}
        # bones: local +y faces the front on both wings, so the mirrored wing is lit the same way
        n_wing = (0, 0, -1)

        # arm: two thin segments with an elbow knuckle, thicker near the shoulder
        seg(wing, f"bras_{sd}", Sm, Em, 2.6, 2.4, "bone", n_wing)
        seg(wing, f"avant_bras_{sd}", Em, Wm, 2.2, 2.0, "bone", n_wing)
        add(wing, f"coude_{sd}", Em, (2.8, 2.8, 2.8), along(sub(Wm, Em), n_wing), "knuckle")
        add(hand, f"poignet_{sd}", Wm, (3.2, 3.0, 3.2), along(sub(Fm["upper"][1], Wm), n_wing), "knuckle")

        # fingers: chains of short tilted segments tapering to the tip, a knuckle at each joint
        widths = {"upper": 2.2, "f1": 2.0, "f2": 1.9, "f3": 1.8}
        for key, pts in Fm.items():
            for j in range(len(pts) - 1):
                w = widths[key] * (1 - 0.13 * j)
                seg(hand, f"doigt_{key}_{sd}_{j + 1}", pts[j], pts[j + 1], w, w, "bone_fine", n_wing, over=0.5)
                if 0 < j:
                    add(hand, f"phalange_{key}_{sd}_{j}", pts[j], (w + 0.5, w + 0.5, w + 0.5),
                        along(sub(pts[j + 1], pts[j]), n_wing), "knuckle")

        # thumb: up and slightly forwards from the wrist, ending in a dark hooked claw
        t1 = add_v(Wm, [0.35 * s, 0.82, -0.45], 4.0)
        t2 = add_v(t1, [0.2 * s, 0.9, -0.4], 3.0)
        seg(hand, f"pouce_{sd}", Wm, t1, 1.6, 1.6, "bone_fine", n_wing, over=0.4)
        seg(hand, f"pouce_{sd}_2", t1, t2, 1.4, 1.4, "bone_fine", n_wing, over=0.4)
        c1 = add_v(t2, [0, 0.55, -0.85], 1.6)
        c2 = add_v(c1, [0, -0.35, -0.95], 1.4)
        seg(hand, f"pouce_griffe_{sd}", t2, c1, 1.2, 1.2, "claw", n_wing, over=0.3)
        seg(hand, f"pouce_griffe_{sd}_pointe", c1, c2, 0.8, 0.8, "claw", n_wing, over=0.2)

        # membranes: stepped triangles between two edges leaving the same apex, hollowed between the tips
        def web(name, bone, apex, q, r, mats, steps=None, hollow=True, thick=0.6, inset=0.94):
            n = norm(cross(sub(q, apex), sub(r, apex)))
            mid = [(q[i] + r[i]) / 2 for i in range(3)]
            u = sub(r, q)
            chord = math.sqrt(dot(u, u))
            # height of the triangle from the apex to the chord
            ap = sub(mid, apex)
            v_dir = cross(n, norm(u))
            height = abs(dot(ap, v_dir))
            steps = steps or max(3, math.ceil(height / 3.4))
            last = steps - 1 if hollow else steps
            for k in range(steps):
                t0, t1 = k / steps * inset, (k + 1) / steps * inset
                tm = (t0 + t1) / 2
                c = add_v(apex, ap, tm)
                mat = mats[min(len(mats) - 1, int(tm * len(mats)))]
                width = height * (t1 - t0) + 1.3
                R = frame(u, v_dir)
                if k < last:
                    add(bone, f"{name}_{k + 1}", c, (chord * (t0 + 0.3 * (t1 - t0)), width, thick), R, mat)
                else:
                    # trailing edge: two pieces along the fingers, the middle left open (concave scallop)
                    for side_k, sgn in ((1, -1), (2, 1)):
                        ln = chord * tm * 0.33
                        cc = add_v(c, norm(u), sgn * (chord * tm / 2 - ln / 2))
                        add(bone, f"{name}_{k + 1}_{side_k}", cc, (ln, width, thick), R, mat)

        grad = ("membrane_root", "membrane", "membrane_tip")
        up_, f1, f2, f3 = (Fm[k] for k in ("upper", "f1", "f2", "f3"))
        web(f"membrane_haut_{sd}", hand, Wm, up_[-2], f1[-1], grad)
        web(f"membrane_milieu_{sd}", hand, Wm, f1[-1], f2[-1], grad)
        web(f"membrane_bas_{sd}", hand, Wm, f2[-1], f3[-1], grad)
        flank_m = m(flank)
        web(f"membrane_flanc_{sd}", wing, Wm, f3[-1], flank_m, ("membrane_root", "membrane"), inset=0.97)
        web(f"membrane_bras_{sd}", wing, flank_m, Wm, Sm, ("membrane_root",), hollow=False, inset=1.0)
        front = add_v([(Sm[i] + Wm[i]) / 2 for i in range(3)], [0, 1, -0.2], 3.0)
        web(f"membrane_avant_{sd}", wing, Sm, front, Wm, ("membrane_root",), hollow=False, inset=1.0, steps=2)

    # ---------------------------------------------------------------- hind legs: thin, folded, three hooked claws
    for s in (1, -1):
        sd = "d" if s == 1 else "g"
        bone = f"Leg_{'right' if s == 1 else 'left'}"
        hip = [HIP[0] * s, HIP[1], HIP[2]]
        GROUPS[bone] = (hip, "Body")
        knee = add_v(hip, [2.2 * s, -4.2, -2.2])               # short upper part, out and forwards
        ankle = add_v(knee, [0.6 * s, -5.6, 1.2])              # longer lower part, folded back
        seg(bone, f"cuisse_{sd}", hip, knee, 2.6, 2.6, "leg", over=1.0)
        seg(bone, f"jambe_{sd}", knee, ankle, 1.9, 1.9, "leg", over=0.8)
        add(bone, f"genou_{sd}", knee, (2.6, 2.6, 2.6), along(sub(ankle, knee)), "leg")
        add(bone, f"pied_{sd}", add_v(ankle, [0, -0.4, -0.6]), (2.8, 1.4, 2.6), (0, -12 * s, 0), "leg")
        for k in range(3):
            spread = (k - 1) * 0.9
            root = add_v(ankle, [spread + 0.3 * s, -0.6, -1.6])
            a = add_v(root, [spread * 0.4, -0.35, -0.95], 1.8)     # toe: forwards and down
            b = add_v(a, [-spread * 0.1, -0.85, 0.35], 1.6)        # claw: down, hooking back inwards
            c = add_v(b, [0, -0.25, 0.95], 1.1)
            seg(bone, f"orteil_{sd}_{k + 1}", root, a, 0.9, 0.9, "toe", over=0.3)
            seg(bone, f"griffe_{sd}_{k + 1}", a, b, 0.8, 0.8, "toe_claw", over=0.3)
            seg(bone, f"griffe_{sd}_{k + 1}_pointe", b, c, 0.6, 0.6, "toe_claw", over=0.2)
    return P


def build():
    raw = parts()
    P, groups, dims = ground(raw, GROUPS, center=False)
    P = [(g, n, [c[0], c[1] + FLOAT, c[2]], size, r, m, rs) for g, n, c, size, r, m, rs in P]
    groups = {k: ([pv[0], pv[1] + FLOAT, pv[2]], parent) for k, (pv, parent) in groups.items()}
    return P, groups, dims


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = None
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims = build()
    uv, atlases, sizes = bake(P, MATS, GLOW)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    ys = [e["from"][1] for e in data["elements"]] + [e["to"][1] for e in data["elements"]]
    print(f"{len(P)} volumes ; {dims[0]:.2f} x {dims[1] + FLOAT / 16:.2f} x {dims[2]:.2f} blocs (envergure x haut x prof) ; "
          f"textures {sizes[0]} + {sizes[1]}")
    if prev:
        apercu(data, atlases, sizes, prev, GLOW_COLOURS, views=((35, 12), (0, 4), (90, 3), (200, 10)), size=420)
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
