#!/usr/bin/env python3
"""Taupe des Champs (Roi Taupérion): farm boss, STATIC Blockbench model (.bbmodel) (2026-11-11).

A real mole body, a boss head: a plump spindle-shaped body low on the ground (the belly is the widest part), two huge
shovel forepaws turned outwards with four thick claws each, small hind legs half hidden under the belly, a short
stub tail. In place of a head (2026-11-11, second version) a living drill grows out of a fur collar: a faceted cone of
stone plates, each turned 22.5 degrees further (reads as a spiral), bands of living flesh between them, one cutting
spiral of blades, a hardened point, four rock fangs on the collar leaning in around its base. No eyes, snout or
ears. Built like the Sea Otter and the Abyss Colossus: a few large boxes per mass, side, top and rear boxes smaller and
tilted a few degrees to round the silhouette, every small detail painted at 1 texel per unit.

Painting (third version, 2026-11-11): semi-realistic in pixel art. Every painter works on a continuous value blended
between the palette colours (soft gradients, soft brush blotches, the same light from above on every part), fur drawn
as short strands along the body, hard edges only on claws and rock outlines. Dark brown fur, warm grey rocks with
small dark violet crystals (shoulders, flanks, rocks, drill), dark rose bare skin (shovel palms, drill flesh, tail),
claws from dark brown to warm grey. No black shadow, no white highlight, no glow.

Pose baked in the boxes, bones placed at the joints for a later animation (the `Drill` bone can spin the drill alone);
no animation.

Usage: taupe_champs.py [output folder] [--apercu <sheet.png>]
Front towards north (-z), the monster's right towards +x, ground at y = 0, 16 units = 1 block.
"""
import json
import math
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, export, ground, hx, tone  # noqa: E402
from golem_fonderie import rot  # noqa: E402

IDENT = "taupe_champs"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-0000000a7a0e")
SCALE = 1.0          # parts are drawn at their brief size (16 units = 1 block)


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- palette (2026-11-11, third version)
# Earthy browns, warm greys, dark violet crystals, dark rose for bare skin. Painters work on a continuous value and
# blend between these colours (soft gradients), never on flat tones; hard edges only on claws and rock outlines.
FUR = pal("#382A25", "#594033", "#765642", "#96765A", "#B49A78")   # shadow, main, mid, lit, warm beige highlight
ROCK = pal("#382A25", "#514D4A", "#77716B")                         # rock shadow (dark brown, never black), plates, relief
CRYSTAL = pal("#51356F", "#7950A0")
SKIN = pal("#382A25", "#98605D", "#B49A78")                         # bare skin: shadow, dark rose, light


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def ramp(colours, v):
    """Colour at value v along a palette (0 = first colour, len - 1 = last), blended between neighbours."""
    v = max(0.0, min(len(colours) - 1.0, v))
    i = min(int(v), len(colours) - 2)
    return mix(colours[i], colours[i + 1], v - i)


def soft_noise(w, h, r, cell=4):
    """Soft brush: random values on a coarse grid, smoothly interpolated (large blotches, no pixel noise)."""
    gw, gh = w // cell + 2, h // cell + 2
    g = [[r.random() - 0.5 for _ in range(gw)] for _ in range(gh)]
    out = []
    for y in range(h):
        fy = y / cell
        y0 = int(fy)
        ty = fy - y0
        ty = ty * ty * (3 - 2 * ty)
        row = []
        for x in range(w):
            fx = x / cell
            x0 = int(fx)
            tx = fx - x0
            tx = tx * tx * (3 - 2 * tx)
            a = g[y0][x0] + (g[y0][x0 + 1] - g[y0][x0]) * tx
            b = g[y0 + 1][x0] + (g[y0 + 1][x0 + 1] - g[y0 + 1][x0]) * tx
            row.append(a + (b - a) * ty)
        out.append(row)
    return out


def light(face, y, h, top=0.0):
    """Light from above, coherent on every part: up faces lit, sides lit at the top and shaded towards the ground,
    the back of a part a little darker, down faces in shadow (but never black)."""
    if face == "up":
        return 0.5 + top
    if face == "down":
        return -0.75
    k = 1 - y / max(1, h - 1)                         # 1 at the top row, 0 at the bottom
    side = {"north": 0.05, "south": -0.12}.get(face, 0.0)
    return -0.45 + 0.85 * k + side + top * k * 0.5


def field(w, h, r, face, base, top=0.0, blotch=0.35, cell=4, k=1.0):
    """Value of every pixel: base value + light (scaled by k) + soft blotches."""
    n = soft_noise(w, h, r, cell)
    return [[base + k * light(face, y, h, top) + blotch * n[y][x] for x in range(w)] for y in range(h)]


def strands(v, r, face, count, strength=0.45, light_bias=0.0):
    """Fur strands drawn along the shape: horizontal on the sides (along the body, towards the rear), falling on the
    front and back faces, along the length on the top. Short irregular strokes (2 to 4 pixels), half lighter, half
    darker, so the fur reads dense and directed without being drawn hair by hair."""
    h, w = len(v), len(v[0])
    for _ in range(count):
        x, y = r.randrange(w), r.randrange(h)
        d = strength if r.random() < 0.5 + light_bias else -strength
        ln = r.randrange(2, 5)
        for i in range(ln):
            if not (0 <= x < w and 0 <= y < h):
                break
            v[y][x] += d * (1 - i / (ln + 1))
            if face in ("east", "west"):
                x += 1
                y += 1 if r.random() < 0.25 else 0
            elif face in ("up", "down"):
                y += 1
                x += r.choice((0, 0, 1, -1))
            else:
                y += 1
                x += 1 if r.random() < 0.2 else 0
    return v


def paint(v, colours):
    return [[ramp(colours, x) for x in row] for row in v]


def crystals(px, r, count, size=(2, 4)):
    """Small violet crystal clusters: dark violet edge, medium violet core, a lighter top pixel (slightly luminous,
    blended with the medium violet, never white)."""
    h, w = len(px), len(px[0])
    glint = mix(CRYSTAL[1], FUR[4], 0.35)
    for _ in range(count):
        if w < 4 or h < 4:
            return px
        x0, y0 = r.randrange(0, w - 2), r.randrange(0, h - 2)
        n = r.randrange(*size)
        cells = [(x0, y0)]
        for _ in range(n):
            cx, cy = cells[-1]
            cells.append((cx + r.choice((0, 1)), cy + r.choice((0, 1, -1))))
        for i, (x, y) in enumerate(cells):
            if 0 <= x < w and 0 <= y < h:
                px[y][x] = CRYSTAL[1] if i % 2 else CRYSTAL[0]
        x, y = cells[len(cells) // 2]
        if 0 <= x < w and 0 <= y < h:
            px[y][x] = glint
    return px


# --------------------------------------------------------------------------- painters
def fur(base=1.0, top=0.0, crystal=0, belly_fade=0.0):
    def f(w, h, r, face):
        """Fur: dark brown main colour, lit progressively towards warm brown on the top of the back, the shoulders and
        the head; strands along the body; `belly_fade` softens the lower rows towards the (slightly lighter) belly;
        `crystal` small violet crystal clusters on the side faces."""
        v = field(w, h, r, face, base, top)
        strands(v, r, face, max(2, w * h // 4), light_bias=0.1 if face == "up" else 0.0)
        if belly_fade and face in ("east", "west", "north", "south"):
            for y in range(h):
                k = y / max(1, h - 1)
                if k > 0.7:
                    for x in range(w):
                        v[y][x] += belly_fade * (k - 0.7) / 0.3
        px = paint(v, FUR)
        if crystal and face in ("east", "west", "up"):
            crystals(px, r, crystal)
        return px
    return f


def belly(w, h, r, face):
    """Belly: the same brown, a touch warmer and softer, fewer strands; no separate beige patch."""
    v = field(w, h, r, face, 1.15, blotch=0.25, cell=5)
    strands(v, r, face, max(1, w * h // 9), strength=0.2)
    return paint(v, FUR)


def limb(base=0.85):
    def f(w, h, r, face):
        """Forelimbs, hind legs: the fur a little darker (they stand out from the body), short strands."""
        v = field(w, h, r, face, base, blotch=0.3, cell=3)
        strands(v, r, face, max(2, w * h // 5), strength=0.28)
        return paint(v, FUR)
    return f


def skin(base=1.0, wrinkles=True):
    def f(w, h, r, face):
        """Bare skin (shovel palms, living flesh of the drill): dark rose, shaded towards dark brown, soft wrinkles
        across the palm."""
        v = field(w, h, r, face, base, blotch=0.25, cell=3, k=0.55)
        if wrinkles:
            for y in range(1, h, 3):
                for x in range(w):
                    if r.random() < 0.7:
                        v[y][x] -= 0.22
        return paint(v, SKIN)
    return f


def claw(tip=False):
    def f(w, h, r, face):
        """Claw (hard brush): dark brown at the root to warm grey at the point, a crisp light ridge on top, a crisp
        darker underside; no white, no black."""
        if face == "down":
            return [[mix(ROCK[0], ROCK[1], 0.4)] * w for _ in range(h)]
        if face == "south":
            return [[ROCK[0]] * w for _ in range(h)]
        px = []
        for y in range(h):
            row = []
            for x in range(w):
                # along the claw: east / west run along u, up runs along v (the root is the far end)
                along = (x / max(1, w - 1)) if face == "west" else (1 - x / max(1, w - 1)) if face == "east" else \
                    (1 - y / max(1, h - 1)) if face == "up" else 0.5
                along = 1.0 if tip else along * 0.75
                v = 0.6 + 1.1 * along
                if face in ("east", "west", "north"):
                    v += 0.35 if y == 0 else (-0.35 if y == h - 1 else 0)
                if face == "up":
                    v += 0.3
                row.append(ramp(ROCK, v))
            px.append(row)
        return px
    return f


def rock(base=1.0, crystal=1, k=0.55):
    def f(w, h, r, face):
        """Rock (hard edges): warm greys with subtle gradient for the volume, a crisp outline one tone darker (dark
        brown-grey, not black), a few cracks, violet crystal clusters."""
        v = field(w, h, r, face, base, blotch=0.35, cell=3, k=k)
        px = paint(v, ROCK)
        for _ in range(max(1, (w + h) // 7)):
            x, y = r.randrange(w), r.randrange(h)
            for _ in range(r.randrange(2, 4)):
                if 0 <= x < w and 0 <= y < h:
                    px[y][x] = mix(ROCK[0], ROCK[1], 0.5)
                x, y = x + r.choice((-1, 0, 1)), y + 1
        if face not in ("down",) and w >= 3 and h >= 3:
            edge = mix(ROCK[0], ROCK[1], 0.55)
            for i in range(w):
                px[h - 1][i] = mix(edge, px[h - 1][i], 0.3)
            for j in range(h):
                px[j][0] = mix(edge, px[j][0], 0.55)
                px[j][w - 1] = mix(edge, px[j][w - 1], 0.55)
            for i in range(1, w - 1):
                px[0][i] = mix(px[0][i], ROCK[2], 0.35)       # light catching the upper edge
        if crystal and face != "down":
            crystals(px, r, crystal if w * h >= 30 else 0)
        return px
    return f


def drill_root(w, h, r, face):
    """Where the drill grows out of the body: fur on the outer ring blending into rock towards the middle."""
    fur_px = fur(1.0)(w, h, r, face)
    if face not in ("north", "south"):
        return fur_px
    rock_px = rock(0.9, crystal=0)(w, h, r, face)
    cx, cy = (w - 1) / 2, (h - 1) / 2
    out = []
    for y in range(h):
        row = []
        for x in range(w):
            d = max(abs(x - cx) / max(cx, 1), abs(y - cy) / max(cy, 1))
            t = max(0.0, min(1.0, (0.9 - d) / 0.3 + (r.random() - 0.5) * 0.4))
            row.append(mix(fur_px[y][x], rock_px[y][x], t))
        out.append(row)
    return out


MATS = {
    "body": fur(0.95, belly_fade=0.2), "flank": fur(0.95, crystal=1, belly_fade=0.25), "shoulder": fur(1.0, 0.2, crystal=1),
    "back": fur(1.0, 0.3), "rump": fur(0.95, belly_fade=0.2), "head": fur(1.0, 0.25), "head_low": fur(0.8),
    "chest": fur(0.7), "belly": belly,
    # drill rings are turned around the drill axis: their faces get an even light (the renderer shades the volume)
    "drill": rock(1.3, 1, k=0.25), "drill_light": rock(1.45, 2, k=0.25), "drill_flesh": skin(1.0, wrinkles=False),
    "drill_root": drill_root, "drill_tip": rock(1.15, 0, k=0.25),
    "arm": limb(0.85), "forearm": limb(0.75), "shovel": skin(0.95), "claw": (claw(False), 2), "claw_tip": (claw(True), 2),
    "foot": limb(0.75), "tail": skin(0.9, wrinkles=True), "stone": rock(1.2, 2),
}


# --------------------------------------------------------------------------- skeleton (drawing units)
BODY_C = (0, 17, 6)            # centre of gravity
NECK = (0, 20, -19)            # head joint, inside the shoulders (no visible neck)
TAIL_ROOT = (0, 14, 33)
SHOULDERS = {1: (19, 17, -12), -1: (-19, 17, -12)}
WRISTS = {1: (21.5, 7.5, -22), -1: (-22, 7.5, -21)}
HIPS = {1: (15, 9, 24), -1: (-15, 9, 24)}

def look(d):
    """Rotation matrix turning a box drawn along -z so that it points along d."""
    n = math.sqrt(sum(v * v for v in d))
    z = [-v / n for v in d]
    up = [0, 1, 0]
    x = [up[1] * z[2] - up[2] * z[1], up[2] * z[0] - up[0] * z[2], up[0] * z[1] - up[1] * z[0]]
    m = math.sqrt(sum(v * v for v in x))
    x = [v / m for v in x]
    y = [z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0]]
    return [[x[i], y[i], z[i]] for i in range(3)]


GROUPS = {"Root": ((0, 0, 0), None), "Body": (BODY_C, "Root"), "Head": (NECK, "Body"), "Tail": (TAIL_ROOT, "Body")}


def parts():
    P = []

    def add(g, name, c, size, r, mat):
        P.append((g, name, list(c), size, r, mat))

    # ---------------------------------------------------------------- body: plump horizontal spindle
    # Core about 60 % of the mass; the belly bulges are the widest part; the back is a gentle dome; the rear narrows
    # and slopes down towards the tail; the front (shoulders) is a little narrower and flows into the head.
    add("Body", "tronc", (0, 17, 5.5), (36, 28, 31), (1, 0, 0), "body")
    add("Body", "hanches", (0, 20, 21.5), (33, 22, 10), (6, 0, 0), "rump")
    add("Body", "ventre", (0, 5.5, 6), (34, 6, 32), (0, 0, 0), "belly")
    add("Body", "flanc_droit", (16.8, 13, 7.5), (9, 18, 27), (0, 2, 9), "flank")
    add("Body", "flanc_gauche", (-16.8, 13, 6.5), (9, 18, 27), (0, -3, -9), "flank")
    add("Body", "dos", (0, 31.5, 6), (28, 6, 28), (-2, 0, 0), "back")
    add("Body", "dos_crete", (0, 34.5, 7), (18, 2.5, 18), (-3, 0, 0), "back")
    # bevels along the long edges: the spindle reads round from the front, not as a box
    for s in (1, -1):
        add("Body", f"arrondi_haut_{s}", (14.5 * s, 28.5, 7), (9, 7, 30), (2, 0, -32 * s), "back")
        add("Body", f"arrondi_bas_{s}", (15 * s, 6.5, 7), (8, 6, 28), (0, 0, 28 * s), "belly")
        add("Body", f"arrondi_epaule_{s}", (13.5 * s, 28.5, -9), (9, 6, 12), (-8, 0, -30 * s), "shoulder")
    add("Body", "epaules", (0, 21, -10.5), (34, 24, 12), (-5, 0, 0), "shoulder")
    add("Body", "epaules_dessus", (0, 30, -8), (26, 6, 12), (-12, 0, 0), "back")
    add("Body", "poitrail", (0, 9.5, -12), (24, 11, 10), (0, 0, 0), "chest")
    add("Body", "croupe", (0, 16, 26.5), (30, 23, 12), (10, 0, 0), "rump")
    add("Body", "croupe_dessus", (0, 25.5, 28), (22, 8, 10), (24, 0, 0), "rump")
    add("Body", "croupe_bas", (0, 7.5, 28), (24, 8, 10), (-6, 0, 0), "belly")

    # rock shards along the spine, tilted backwards, irregular sizes (sunk 1.5 units into the fur)
    for i, (z, sz, ry, rz) in enumerate(((-4, (4, 4.5, 4.5), 8, -4), (3.5, (5, 5.5, 5), -10, 5),
                                         (11, (4, 4.2, 4), 14, -3), (18, (3, 3.2, 3), -6, 4))):
        top = 35.8 - 0.06 * abs(z - 7)
        add("Body", f"eclat_{i + 1}", (0.6 * (-1) ** i, top + sz[1] / 2 - 1.5, z), sz, (13, ry, rz), "stone")

    # ---------------------------------------------------------------- head: a living drill instead of a head
    # No skull, snout, eyes nor ears: the fur of the shoulders closes into a collar, from which a drill of stone grows.
    # The drill is a faceted cone of square rings, each turned 22.5 degrees more than the previous one (reads as a
    # spiral), stone rings alternating with bands of living flesh, two spiral cutting blades per ring, a hardened
    # point. Four rock fangs on the collar lean in around the base like the jaws of a digging tool. The rings are in
    # the `Drill` bone, pivot on the axis: an animation can spin the drill without moving the collar.
    on_head = attach(NECK, rot(-6, 0, 0))
    axis = (0, 21, -25)
    on_drill = attach(axis, rot(-3, 0, 0))                       # drill aims a little more towards the ground

    def hd(name, c, size, r, mat, bone="Head", drilled=False):
        cc, M = on_drill(c, r) if drilled else (c, r)
        cc, M = on_head(cc, M)
        add(bone, name, cc, size, M, mat)

    GROUPS["Drill"] = (on_head(*on_drill(axis, (0, 0, 0)))[0], "Head")
    hd("nuque", (0, 27, -18), (28, 8, 10), (-14, 0, 0), "head")          # the back line runs on into the collar
    hd("col", (0, 20, -20.5), (30, 22, 8), (0, 0, 0), "head")
    hd("col_bas", (0, 11, -20.5), (25, 6, 8), (0, 0, 0), "head_low")
    # base of the drill: two squares turned 45 degrees from each other (an octagon), growing out of the collar
    hd("racine", (0, axis[1], -25.2), (26, 26, 3.6), (0, 0, 0), "drill_root", drilled=True)
    hd("racine_biais", (0, axis[1], -25.4), (22, 22, 3.4), (0, 0, 45), "drill_root", drilled=True)

    # stone plates (each a little smaller and turned further) with thinner bands of living flesh between them
    rings = ((25, "drill", 5.2), (20, "drill_flesh", 3.2), (20.5, "drill_light", 5), (15.5, "drill_flesh", 3),
             (16, "drill", 4.8), (11, "drill_flesh", 2.8), (11.5, "drill_light", 4.6), (7, "drill", 4.2))
    z = axis[2] - 1.0
    for k, (side, mat, depth) in enumerate(rings):
        z -= depth / 2
        hd(f"anneau_{k + 1}", (0, axis[1], z), (side, side, depth), (0, 0, 22.5 * k), mat, "Drill", True)
        if mat != "drill_flesh" and side >= 9:                   # one continuous cutting spiral around the plates
            a = math.radians(40 * k + 30)
            rad = side * 0.5 + 1.2
            c = (rad * math.cos(a), axis[1] + rad * math.sin(a), z)
            blade = (2.4, 5.6 - 0.3 * k, 6.4)
            hd(f"lame_{k + 1}", c, blade, (0, 28, math.degrees(a) - 90), "claw", "Drill", True)
        z -= depth / 2 - 0.4                                     # rings overlap a little: no gap in the cone
    tip_z = z - 1.2
    hd("pointe", (0, axis[1], tip_z + 0.8), (4.6, 4.6, 4.2), (0, 0, 45), "drill_tip", "Drill", True)
    hd("pointe_bout", (0, axis[1], tip_z - 2.6), (2.4, 2.4, 3.6), (0, 0, 0), "claw_tip", "Drill", True)

    # rock fangs on the collar, leaning in towards the drill
    for j, ang in enumerate((35, 145, 215, 325)):
        a = math.radians(ang)
        base = (13 * math.cos(a), axis[1] + 13 * math.sin(a), axis[2] - 1.5)
        d = [-0.32 * math.cos(a), -0.32 * math.sin(a), -1]
        hd(f"croc_{j + 1}", (base[0] + d[0] * 3.5, base[1] + d[1] * 3.5, base[2] + d[2] * 3.5), (4, 4, 9),
           look(d), "claw", "Head", True)

    # ---------------------------------------------------------------- tail: short thick stub
    on_tail = attach(TAIL_ROOT, rot(16, 0, 0))
    for name, c, size, mat in (("queue", (0, 14, 35.5), (6, 6, 6), "tail"), ("queue_bout", (0, 14, 40), (4, 4, 4), "tail")):
        cc, M = on_tail(c, (0, 0, 0))
        add("Tail", name, cc, size, M, mat)

    # ---------------------------------------------------------------- front legs: shoulder, forearm, shovel, claws
    def hand(s, yaw, roll, pitch, claw_len):
        """Shovel drawn flat in the wrist frame (palm down, front -z), then turned outwards (yaw), tilted so the
        outer edge rises (roll) and lowered to the front (pitch). Four claws leave the front edge, pointing forwards
        and curving down to the ground."""
        out = [("pelle", (0, 0, -5.5), (14, 4.5, 11), (0, 0, 0), "shovel"),
               ("pelle_exterieur", (6.8 * s, -0.4, -4.5), (4, 4, 9.5), (0, 6 * s, 0), "shovel"),
               ("pelle_interieur", (-6.6 * s, -0.4, -5), (3.5, 4, 9), (0, -5 * s, 0), "shovel"),
               ("pelle_avant", (0, -0.6, -11.2), (16.5, 3.8, 4), (0, 0, 0), "shovel")]
        for k in range(4):
            x = -5.4 + 3.6 * k
            ln = claw_len[k]
            base = (x, -0.9, -12.8)
            spread = (k - 1.5) * 2.5                      # claws fan out slightly
            cc, M = attach(base, rot(-14, -spread, 0))((x, -0.9, -12.8 - ln / 2), (0, 0, 0))
            out.append((f"griffe_{k + 1}", cc, (3.3, 3.0, ln), M, "claw"))
            tip_base = (x, -0.9, -12.8 - ln + 0.6)
            tc, TM = attach(base, rot(-14, -spread, 0))(*attach(tip_base, rot(-32, 0, 0))(
                (x, -1.1, tip_base[2] - 1.6), (0, 0, 0)))
            out.append((f"griffe_{k + 1}_pointe", tc, (2.2, 2.0, 3.4), TM, "claw_tip"))
        R = rot(pitch, yaw, roll)
        return out, R

    for s, side in ((1, "right"), (-1, "left")):
        arm, paw_bone = f"Arm_front_{side}", f"Paw_front_{side}"
        GROUPS[arm] = (SHOULDERS[s], "Root")
        GROUPS[paw_bone] = (WRISTS[s], arm)
        sx, sy, sz = SHOULDERS[s]
        wx, wy, wz = WRISTS[s]
        fr = "droite" if s == 1 else "gauche"
        # shoulder: massive, half inside the front of the body
        add(arm, f"epaule_{fr}", (sx, sy, sz), (13, 17, 15), (0, -6 * s, -10 * s), "shoulder")
        add(arm, f"bras_{fr}", ((sx + wx) / 2 + 0.5 * s, (sy + wy) / 2 + 1, (sz + wz) / 2), (11, 11, 13),
            (-24, -14 * s, -8 * s), "arm")
        add(paw_bone, f"poignet_{fr}", (wx, wy + 0.5, wz + 1), (10, 8, 8), (-12, -16 * s, 0), "forearm")
        # the two paws are not identical: the left one is a bit further forward and more open
        yaw, roll, pitch = (-16 * s, 36 * s, -6) if s == 1 else (-20 * s, 40 * s, -9)
        lens = (6.0, 7.2, 7.0, 5.6) if s == 1 else (5.6, 7.0, 7.2, 6.0)
        pieces, R = hand(s, yaw, roll, pitch, lens)
        place = attach((0, 0, 0), R)
        for name, c, size, r, mat in pieces:
            cc, M = place(c, r)
            add(paw_bone, f"{name}_{fr}", (cc[0] + wx, cc[1] + wy - 1.5, cc[2] + wz), size, M, mat)

    # ---------------------------------------------------------------- hind legs: small, splayed, under the belly
    for s, side in ((1, "right"), (-1, "left")):
        bone = f"Leg_back_{side}"
        GROUPS[bone] = (HIPS[s], "Root")
        hx_, hy, hz = HIPS[s]
        fr = "droite" if s == 1 else "gauche"
        add(bone, f"cuisse_{fr}", (hx_ + 1 * s, 6, hz), (8, 9, 9), (8, 0, -6 * s), "arm")
        add(bone, f"pied_{fr}", (hx_ + 4.5 * s, 1.5, hz - 3.5), (7, 3, 9), (0, -14 * s, 0), "foot")
        for k in range(3):
            add(bone, f"doigt_{fr}_{k + 1}", (hx_ + 4.5 * s + (k - 1) * 2.2 - 1.2 * s, 0.9, hz - 8.4 - (0.6 if k == 1 else 0)),
                (1.4, 1.6, 2.6), (-10, -14 * s, 0), "claw")
    return P


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = anims_dir = None
    show = "--mesures" in args
    if show:
        args.remove("--mesures")
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    if "--anims" in args:
        i = args.index("--anims")
        anims_dir = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims = ground(parts(), GROUPS, scale=SCALE)
    uv, atlases, sizes = bake(P, MATS, {})
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    data["textures"] = data["textures"][:1]          # nothing glows: no emissive texture
    import taupe_champs_anim as TA
    anims = TA.build(data, TA.uid_for(NS, IDENT))
    print(f"{len(P)} volumes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; texture {sizes[0]} ; "
          f"{len(anims)} animations")
    if anims_dir:
        TA.strips(data, anims, atlases, sizes, anims_dir)
    if show:
        for name, rows in TA.measure(data, anims).items():
            print(f"--- {name}")
            for r in rows:
                print(f"  tick {r['tick']:3d}  bas {r['low']:6.2f}  haut {r['high']:5.2f}  pointe {r['tip']}")
    if prev:
        apercu(data, atlases, sizes, prev, set(), views=((35, 12), (0, 6), (90, 3), (0, 88)), size=420)
    data["animations"] = TA.finish(anims)
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
