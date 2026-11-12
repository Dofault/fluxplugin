#!/usr/bin/env python3
"""Ossacorne: boss, static Blockbench model (.bbmodel), 2026-11-11.

Skeleton of a fantasy four-legged predator, built after the brief "squelette quadrupède fantastique": low, stocky,
long, wide at the shoulders and narrower at the hips, leaning forward, ready to charge. Five identity features:
massive skull with two ram horns, low aggressive pose, open rib cage, row of bony plates along the spine, long tail
of single vertebrae raised in a soft S.

Same way of building as the Loutre des Marees and the Colosse d'Abime (bbkit): every bone is its own cuboid or a
chain of cuboids, each segment turned a little more than the previous one to draw a curve. The cage, the pelvis and
the gaps between bones are really empty: you can see through the creature. Pixel art at 1 texel per unit (16 x 16 per
block face), 2 for teeth and claws. Pose baked into the cubes, bones placed at the joints for a later animation.

Display cost: BetterModel cuts each bone into as many pieces as it has distinct cube orientations. Parallel parts
(the ribs of one side, the claws of one foot, the head) share their angles on purpose.

Usage: ossacorne.py [output folder] [--apercu <sheet.png>] [--cubes <table.md>] [--anims <folder>] [--mesures]
Animations: ossacorne_anim.py.
Front to the north (-z), right side of the monster at +x, ground at y = 0. 16 units = 1 block.
"""
import json
import math
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, corners, export, ground, hx, matrix, tone  # noqa: E402
from golem_fonderie import app, euler, rot  # noqa: E402

IDENT = "ossacorne"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-0000000055ac")


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- palettes of the brief (dark to light)
# Bone: 0 cavities, 1 deep shadow, 2 pinkish brown shadow, 3 mid shadow, 4 secondary beige, 5 main colour,
# 6 bone white (lit faces), 7 very light highlight (edges only).
BONE = pal("#382D2B", "#62463F", "#89665C", "#A98770", "#C5A986", "#D8C3A0", "#E9D8B8", "#FFF1D9")
# Horn: 0 deep shadow, 1 shadow, 2 main, 3 base, 4 highlight, 5 worn tips.
HORN = pal("#30221F", "#422D27", "#60412F", "#72503A", "#987052", "#B08A65")

# Light from above and slightly from the front.
LIT = {"up": 1, "north": 0, "south": -1, "east": 0, "west": 0, "down": -2}


# --------------------------------------------------------------------------- painters (width, height, rng, face)
def crack(px, r, t, h, w):
    """Thin broken line of 2 or 3 pixels, one tone darker than the surface (never black)."""
    x, y = r.randrange(w), r.randrange(h)
    for _ in range(r.randint(2, 3)):
        if 0 <= x < w and 0 <= y < h:
            px[y][x] = t
        x += r.choice((-1, 0, 1))
        y += 1


def bone(base=5, cracks=0.5, ends=True):
    def f(w, h, r, face):
        """Old bone: main beige, lit face bone white with a few highlights, under face in mid shadow, sides with a
        darker lower row and a lit upper edge, grain one tone down, rare thin cracks; `ends`: the two end faces of a
        segment (north/south) are joints, in pinkish brown."""
        b = base + LIT[face]
        if ends and face in ("north", "south"):
            b = 3
        px = [[tone(BONE, b)] * w for _ in range(h)]
        for _ in range(max(1, w * h // 14)):
            px[r.randrange(h)][r.randrange(w)] = tone(BONE, b - 1)
        if face in ("east", "west", "north", "south") and h >= 3:
            px[h - 1] = [tone(BONE, b - 1) if r.random() < 0.8 else tone(BONE, b - 2) for _ in range(w)]
            for x in range(w):
                if r.random() < 0.3:
                    px[0][x] = tone(BONE, b + 1)
        if face == "up":
            for _ in range(max(1, w * h // 22)):
                px[r.randrange(h)][r.randrange(w)] = tone(BONE, b + 1)
        if ends and face in ("east", "west") and w >= 4:  # shade where the segment meets its neighbours
            for y in range(h):
                if r.random() < 0.4:
                    px[y][0] = tone(BONE, b - 1)
                if r.random() < 0.4:
                    px[y][w - 1] = tone(BONE, b - 1)
        if w * h >= 20 and r.random() < cracks * min(1.0, w * h / 50):
            crack(px, r, tone(BONE, b - 2), h, w)
        return px
    return f


def joint(w, h, r, face):
    """Joint: thicker, secondary beige, pinkish brown and deep shadow in the hollows."""
    b = 4 + (1 if face == "up" else -1 if face == "down" else 0)
    px = [[tone(BONE, b)] * w for _ in range(h)]
    for _ in range(max(1, w * h // 12)):
        px[r.randrange(h)][r.randrange(w)] = tone(BONE, 3 if r.random() < 0.6 else 2)
    for _ in range(max(1, w * h // 18)):
        px[r.randrange(h)][r.randrange(w)] = tone(BONE, b + 1)
    if face not in ("up", "down") and h >= 3:
        px[h - 1] = [tone(BONE, 2)] * w
    return px


def cavity(rim=True):
    def f(w, h, r, face):
        """Eye socket, nostril: the darkest colour, a deep shadow rim on the front face."""
        px = [[BONE[0]] * w for _ in range(h)]
        if rim and face == "north" and w >= 3 and h >= 3:
            for x in range(w):
                px[0][x] = BONE[1]
            for y in range(h):
                px[y][0] = px[y][w - 1] = BONE[1]
        return px
    return f


def plate(level):
    def f(w, h, r, face):
        """Dorsal plate, 0 base, 1 middle, 2 tip: light beige blade with lighter edges, the base darker at both ends
        of its axis (the end in contact with the spine is the visible one), the tip lighter, small cracks and
        chipped edge pixels."""
        b = (3, 5, 6)[level] + (1 if face == "up" else -1 if face == "down" else 0)
        px = [[tone(BONE, b)] * w for _ in range(h)]
        for _ in range(max(1, w * h // 12)):
            px[r.randrange(h)][r.randrange(w)] = tone(BONE, b - 1)
        if face in ("east", "west", "up", "down"):
            for y in range(h):
                for x in range(w):
                    edge = (x in (0, w - 1)) if face in ("up", "down") else (y in (0, h - 1))
                    if edge and r.random() < 0.75:
                        px[y][x] = tone(BONE, b + 1)
            if level == 0:                               # junction with the spine: shadowed
                px = [[tone(BONE, 2 if r.random() < 0.55 else 3) for _ in range(w)] for _ in range(h)]
            if level == 2:
                for _ in range(max(1, w * h // 8)):
                    px[r.randrange(h)][r.randrange(w)] = BONE[7]
            if w * h >= 18:
                crack(px, r, tone(BONE, b - 2), h, w)
            for _ in range(2):                           # chipped edge
                y = r.randrange(h)
                px[y][0 if r.random() < 0.5 else w - 1] = tone(BONE, 3)
        return px
    return f


def horn(level):
    def f(w, h, r, face):
        """Ram horn segment, 0 base (a little lighter), 1 middle, 2 tip (worn, lighter): growth rings across the
        horn at irregular steps, broken here and there, a few streaks along it, highlights on top."""
        b = (3, 2, 4)[level] + (1 if face == "up" else -1 if face == "down" else 0)
        px = [[tone(HORN, b)] * w for _ in range(h)]
        if face in ("north", "south"):                 # end caps: concentric rings
            for y in range(h):
                for x in range(w):
                    d = min(x, y, w - 1 - x, h - 1 - y)
                    px[y][x] = tone(HORN, b - 1 if d % 2 == 0 else b)
            return px
        along = w if face in ("east", "west") else h
        across = h if face in ("east", "west") else w

        def put(a, c, col):
            if face in ("east", "west"):
                px[c][a] = col
            else:
                px[a][c] = col
        a = r.randrange(2)
        while a < along:                                 # growth rings across the horn
            for c in range(across):
                if r.random() < 0.85:
                    put(a, c, tone(HORN, b - 1))
            a += r.choice((2, 2, 3))
        for _ in range(max(1, across // 3)):             # streaks along it
            c = r.randrange(across)
            for a in range(along):
                if r.random() < 0.55:
                    put(a, c, tone(HORN, b - 1 if r.random() < 0.6 else b + 1))
        if face == "up":
            for _ in range(max(1, w * h // 10)):
                px[r.randrange(h)][r.randrange(w)] = tone(HORN, b + 1)
        if level == 2:
            for _ in range(max(1, w * h // 6)):
                px[r.randrange(h)][r.randrange(w)] = HORN[5]
        return px
    return f


def tooth(down=True):
    def f(w, h, r, face):
        """Tooth (density 2): cleaner and lighter than the bones, beige root, ivory body, almost white point."""
        if face in ("up", "down"):
            root = (face == "up") == down
            return [[BONE[4] if root else BONE[7]] * w for _ in range(h)]
        px = []
        for y in range(h):
            t = y / max(1, h - 1)
            if not down:
                t = 1 - t
            px.append([BONE[4] if t < 0.25 else BONE[6] if t < 0.75 else BONE[7]] * w)
        if face in ("east", "west") and w >= 2:
            for y in range(h):
                px[y][w - 1] = BONE[5] if px[y][w - 1] != BONE[7] else BONE[6]
        return px
    return f


def claw(tip):
    def f(w, h, r, face):
        """Claw (density 2): ivory phalanx shaded underneath, almost white curved point."""
        b = (7 if tip else 6) + (0 if face == "up" else -2 if face == "down" else -1 if face == "south" else 0)
        px = [[tone(BONE, b)] * w for _ in range(h)]
        if face in ("east", "west", "north") and h >= 2:
            px[h - 1] = [tone(BONE, b - 1)] * w
        if tip and face in ("east", "west") and w >= 2:
            for y in range(h):
                px[y][0] = px[y][w - 1] = BONE[6]
        return px
    return f


MATS = {
    "bone": bone(5), "bone_light": bone(6, 0.3), "bone_shade": bone(4, 0.6),
    "skull": bone(5, 1.0, ends=False), "skull_light": bone(6, 0.8, ends=False), "skull_shade": bone(4, 0.7, ends=False),
    "joint": joint, "cavity": cavity(True), "cavity_flat": cavity(False), "hitbox": cavity(False),
    "plate_base": plate(0), "plate_mid": plate(1), "plate_tip": plate(2),
    "horn_base": horn(0), "horn": horn(1), "horn_tip": horn(2),
    "tooth_down": (tooth(True), 2), "tooth_up": (tooth(False), 2),
    "claw": (claw(False), 2), "claw_tip": (claw(True), 2),
}


# --------------------------------------------------------------------------- geometry helpers
def direction(rx, ry):
    """Unit vector of the local z axis of a cube turned by rot(rx, ry, 0)."""
    a, b = math.radians(rx), math.radians(ry)
    return [math.sin(b) * math.cos(a), -math.sin(a), math.cos(b) * math.cos(a)]


def angles(v):
    """(rx, ry) in whole degrees that point the local z axis of a cube along v (whole degrees: parts drawn along
    the same vector share one orientation, so one BetterModel piece)."""
    n = math.sqrt(sum(c * c for c in v))
    return round(-math.degrees(math.asin(v[1] / n))), round(math.degrees(math.atan2(v[0], v[2])))


def chain(start, specs, overlap=0.0):
    """Bone drawn as a chain of segments. specs: (rx, ry, length, width, height[, (dx, dy) local offset]); each
    segment starts where the previous one ends (minus `overlap` x its width, which closes the elbow). Returns
    [(centre, size, rotation)] and the end point."""
    out, p = [], list(start)
    for s in specs:
        rx, ry, L, w, h = s[:5]
        off = s[5] if len(s) > 5 else (0, 0)
        d = direction(rx, ry)
        R = rot(rx, ry, 0)
        shift = app(R, [off[0], off[1], 0])
        c = [p[i] + d[i] * L / 2 + shift[i] for i in range(3)]
        out.append((c, (w, h, L), (rx, ry, 0)))
        back = min(overlap * w, L * 0.45)
        p = [p[i] + d[i] * (L - back) for i in range(3)]
    return out, p


def towards(a, b, w, h):
    """One cube from point a to point b (length |ab|), section w x h."""
    v = [b[i] - a[i] for i in range(3)]
    rx, ry = angles(v)
    L = math.sqrt(sum(c * c for c in v))
    return [(a[i] + b[i]) / 2 for i in range(3)], (w, h, L), (rx, ry, 0)


def local(c, r, v):
    """Point v of a cube's frame (centre c, rotation r) in model space."""
    q = app(matrix(r), v)
    return [c[i] + q[i] for i in range(3)]


def lerp_y(points, z):
    """Height of the spine at z (piecewise linear through `points`)."""
    for (z0, y0), (z1, y1) in zip(points, points[1:]):
        if z0 <= z <= z1:
            return y0 + (y1 - y0) * (z - z0) / (z1 - z0)
    return points[0][1] if z < points[0][0] else points[-1][1]


# --------------------------------------------------------------------------- skeleton plan (drawing units)
# Length budget along z (front = -z): skull and snout -64..-40.5, neck -40.5..-28, rib cage -28..8, pelvis 8..27,
# tail 27.. (about 2.6 blocks along its curve). Spine: highest at the withers, going down to the sacrum.
SPINE = [(-41, 30.0), (-28, 31.8), (-22, 33.0), (-14, 33.2), (-6, 32.6), (2, 31.4), (10, 30.2), (18, 29.2),
         (27, 28.2)]
BODY_PIVOT = (0, 32.0, -8)          # middle of the back
HIPS_PIVOT = (0, 30.0, 8.5)         # chest / lumbar joint
NECK_PIVOT = (0, 31.8, -28)         # base of the neck, in front of the withers
HEAD_PIVOT = (0, 30.5, -41)         # occipital condyles
HEAD_R = rot(-22, -5, 2)            # head lowered 22 degrees, a little turned to its right (towards the 3/4 view)
JAW_HINGE = (0, 27.5, -48.5)
JAW_OPEN = rot(-13, 0, 0)           # jaw opened 13 degrees
HORN_LENGTH = 1.15                  # length of every horn segment (the coil reads better in profile)
SHOULDER = (18.0, 21.5, -21.0)      # right shoulder joint (left: mirrored)
HIP = (11.5, 22.0, 22.0)            # right hip joint
TAIL_START = (0, 27.6, 27.2)
ORIGIN_SHIFT = 15.4                 # entity origin this many units in front of the middle of the legs (chest)
SCALE = 0.85                        # whole model (85 % of the drawing since the 2026-11-11 play test)
HITBOX = ("b_skull", "b_chest", "b_hips")

# Ribs: one template per side, every rib a scaled copy (same angles, so one orientation per segment and side).
# (rx, length) of each segment with ry = +-90: up-out, out-down, down, down-in, in, in to the sternum.
RIB = ((-17, 5.0), (30, 7.0), (58, 7.0), (100, 6.0), (145, 5.0), (167, 9.5))
RIB_SWEEP = rot(-15, 0, 0)          # ribs sweep backwards as they go down
# (z, scale, segments kept, width): 4 true ribs to the sternum, 1 short, 2 floating ribs; widest above the shoulders.
RIBS = ((-24, 0.88, 6, 2.5), (-19, 0.94, 6, 2.5), (-14, 0.94, 6, 2.4), (-9, 0.91, 6, 2.3), (-4, 0.87, 5, 2.2),
        (1, 0.82, 4, 2.0), (6, 0.74, 4, 1.8))

# Dorsal plates: (z, height, blade length, thickness, backward tilt, sideways lean, yaw) ; largest above the
# shoulders, then smaller towards the pelvis, no two alike.
PLATES = ((-22.5, 17.0, 9.0, 5.0, 13, 3, -2), (-16.0, 18.0, 9.5, 5.0, 17, -2, 3), (-9.5, 15.0, 8.5, 4.6, 14, 2, -3),
          (-3.0, 12.5, 7.5, 4.2, 19, -3, 2), (3.5, 10.5, 7.0, 4.0, 15, 1, -2), (10.0, 8.5, 6.0, 3.6, 20, -2, 1),
          (16.0, 7.0, 5.0, 3.4, 13, 2, -1), (22.0, 5.5, 4.5, 3.2, 21, -1, 2))

# Tail: (backward pitch, sideways yaw, length) from the base to the tip: back and slightly down, then a long rise,
# the tip raised and curled back a little (soft S, never a scorpion sting).
TAIL = ((12, 0, 4.6), (6, 1, 4.4), (-2, 2, 4.2), (-12, 3, 4.0), (-24, 4, 3.8), (-36, 5, 3.6), (-47, 6, 3.4),
        (-56, 7, 3.2), (-63, 7, 3.0), (-67, 7, 2.8), (-66, 6, 2.6), (-60, 5, 2.4))


def parts():
    P = []
    G = {"Root": ((0, 0, 0), None), "Body": (BODY_PIVOT, "Root"), "Chest": (BODY_PIVOT, "Body"),
         "Hips": (HIPS_PIVOT, "Body"), "Neck": (NECK_PIVOT, "Chest"), "Head": (HEAD_PIVOT, "Neck")}

    def add(g, name, c, size, r, mat):
        P.append((g, name, list(c), tuple(size), r, mat))

    def on(g, name, c, r, off, size, mat):
        """Piece placed in the frame of another cube (centre c, rotation r), offset `off`, same orientation."""
        add(g, name, local(c, r, off), size, r, mat)

    Y = lambda z: lerp_y(SPINE, z)  # noqa: E731

    # ---------------------------------------------------------------- 1. spine: neck, back, lumbar, sacrum
    for i, z in enumerate((-24, -19, -14, -9, -4, 1, 6)):
        c, r = (0, Y(z), z), (4, 0, 0)
        add("Chest", f"vertebre_dos_{i + 1}", c, (6.2, 4.8, 3.8), r, "bone")
        sp = 5.0 - i * 0.25
        add("Chest", f"apophyse_dos_{i + 1}", (0, Y(z) + 2.4 + sp / 2 - 0.4, z + 0.5), (2.0, sp, 2.6), (14, 0, 0),
            "bone_light")
        for s, side in ((1, "droite"), (-1, "gauche")):
            add("Chest", f"transverse_dos_{i + 1}_{side}", (s * 4.2, Y(z) - 0.2, z), (2.4, 1.6, 2.2), r, "bone_shade")
    for i, z in enumerate((11, 16)):
        c, r = (0, Y(z), z), (6, 0, 0)
        add("Hips", f"vertebre_lombaire_{i + 1}", c, (6.4, 5.0, 4.2), r, "bone")
        add("Hips", f"apophyse_lombaire_{i + 1}", (0, Y(z) + 3.6, z + 0.5), (2.0, 3.6, 2.6), (14, 0, 0), "bone_light")
        for s, side in ((1, "droite"), (-1, "gauche")):
            # long lumbar processes, angled down and out: the small bony spurs of the brief
            add("Hips", f"transverse_lombaire_{i + 1}_{side}", (s * 5.6, Y(z) - 0.8, z), (4.6, 1.3, 2.4),
                (6, 0, -18 * s), "bone_shade")
    add("Hips", "sacrum", (0, Y(23) - 0.2, 23), (7.0, 4.2, 10.5), (6, 0, 0), "bone")
    for i, z in enumerate((19.5, 23, 26.3)):
        add("Hips", f"crete_sacrum_{i + 1}", (0, Y(z) + 2.9, z), (1.8, 2.6, 2.2), (14, 0, 0), "bone_light")

    # neck: three chunky vertebrae rising from the skull to the withers, and the winged atlas behind the skull
    for i, z in enumerate((-30.5, -34.6)):
        c, r = (0, Y(z) - 0.2, z), (-8, 0, 0)
        add("Neck", f"vertebre_cou_{i + 1}", c, (6.2, 5.6, 3.6), r, "bone")
        add("Neck", f"apophyse_cou_{i + 1}", (0, Y(z) + 3.0, z + 0.4), (2.2, 2.6, 2.4), (14, 0, 0), "bone_light")
        for s, side in ((1, "droite"), (-1, "gauche")):
            add("Neck", f"transverse_cou_{i + 1}_{side}", (s * 4.0, Y(z) - 1.0, z), (2.2, 2.0, 2.6), r, "bone_shade")
    add("Neck", "axis", (0, Y(-38) - 0.2, -38), (6.6, 5.0, 3.2), (-8, 0, 0), "bone")
    add("Neck", "atlas", (0, Y(-40) + 0.3, -40.2), (10.5, 3.2, 2.4), (-8, 0, 0), "joint")

    # ---------------------------------------------------------------- 2. rib cage: 7 pairs of ribs and the sternum
    sternum_points = []
    for i, (z, k, keep, w) in enumerate(RIBS):
        for s, side in ((1, "droite"), (-1, "gauche")):
            head = (s * 4.6, Y(z) - 0.6, z)
            specs = [(rx, 90 * s, L * k, w, 1.6) for rx, L in RIB[:keep]]
            segs, end = chain(head, specs, overlap=0.35)
            sweep = attach(head, RIB_SWEEP)
            for j, (c, size, r) in enumerate(segs):
                cc, M = sweep(c, r)
                add("Chest", f"cote_{i + 1}_{side}_{j + 1}", cc, size, M, "bone")
            if keep == 6 and s == 1:
                d = app(RIB_SWEEP, [end[q] - head[q] for q in range(3)])
                sternum_points.append((head[1] + d[1], head[2] + d[2]))
    # sternum: small sternebrae from the front of the cage, under the ends of the true ribs
    pts = sternum_points
    rx_st = angles([0, pts[-1][0] - pts[0][0], pts[-1][1] - pts[0][1]])[0]
    for j, (y, z) in enumerate(pts):
        add("Chest", f"sternebre_{j + 1}", (0, y - 0.4, z), (3.6, 2.0, 3.6), (rx_st, 0, 0), "bone")
    y0, z0 = pts[0]
    add("Chest", "manubrium", (0, y0 + 1.2, z0 - 3.6), (3.0, 2.2, 4.2), (rx_st - 28, 0, 0), "bone_light")

    # shoulder blades: wide flat bones over the front ribs, from the shoulder joint up to the withers
    for s, side in ((1, "droite"), (-1, "gauche")):
        a = (s * (SHOULDER[0] - 0.8), SHOULDER[1] + 2.0, SHOULDER[2] + 0.5)
        b = (s * 15.8, 31.5, -15.5)
        c, size, r = towards(a, b, 1.8, 7.0)
        add("Chest", f"omoplate_{side}", c, size, r, "bone")
        on("Chest", f"epine_omoplate_{side}", c, r, (s * 1.2, 0, 0), (1.2, 1.4, size[2] * 0.9), "bone_light")

    # ---------------------------------------------------------------- 3. pelvis
    for s, side in ((1, "droite"), (-1, "gauche")):
        hip = (s * HIP[0], HIP[1], HIP[2])
        c, size, r = towards((s * 3.5, 29.2, 15.5), (s * 10.6, 23.6, 21.0), 5.0, 2.2)
        add("Hips", f"ilion_{side}", c, size, r, "bone")
        add("Hips", f"aile_ilion_{side}", (s * 6.6, 29.8, 15.2), (6.5, 2.0, 5.0), (0, 0, -14 * s), "bone_light")
        add("Hips", f"cotyle_{side}", (s * (HIP[0] - 0.6), HIP[1], HIP[2]), (5.0, 5.4, 5.4), (0, 0, 0), "joint")
        c, size, r = towards((s * 10.2, 20.8, 23.8), (s * 6.4, 17.6, 30.2), 2.6, 3.0)
        add("Hips", f"ischion_{side}", c, size, r, "bone")
        add("Hips", f"tuberosite_ischion_{side}", (s * 6.2, 17.6, 30.6), (3.0, 3.0, 2.8), (10, 0, 0), "joint")
        c, size, r = towards((s * 9.8, 20.0, 21.2), (s * 1.0, 17.2, 21.8), 2.4, 2.4)
        add("Hips", f"pubis_{side}", c, size, r, "bone")
        del hip
    add("Hips", "symphyse", (0, 16.9, 21.8), (3.0, 2.4, 3.2), (0, 0, 0), "joint")

    # ---------------------------------------------------------------- 4. legs (children of Root: feet stay down)
    legs = {}

    def foot(g, prefix, at, yaw, plate_size, claw_len, spread):
        """Wide foot plate and three parallel claws (phalanx then curved point), turned by `yaw`."""
        place = attach(at, rot(0, yaw, 0))
        fw, fh, fd = plate_size
        pieces = [(f"{prefix}_plante", (at[0], at[1] - 0.3, at[2] - fd / 2 + 1.2), plate_size, (0, 0, 0), "bone")]
        front = at[2] - fd + 1.2
        for n, dx in enumerate((-spread, 0, spread)):
            longer = 0.6 if dx == 0 else 0.0
            ph = (at[0] + dx, at[1] - 0.6, front - 1.5)
            pieces.append((f"{prefix}_phalange_{n + 1}", ph, (1.9, 1.8, 3.2), (0, 0, 0), "claw"))
            (c, size, r), = chain((ph[0], ph[1] + 0.1, front - 2.6), [(38, 180, claw_len + longer, 1.4, 1.6)])[0]
            pieces.append((f"{prefix}_griffe_{n + 1}", c, size, r, "claw_tip"))
        for n, c, size, r, m in pieces:
            cc, M = place(c, r)
            add(g, n, cc, size, M, m)

    for s, side in ((1, "right"), (-1, "left")):
        fr = "droite" if s == 1 else "gauche"
        # front leg: big shoulder joint, short powerful humerus going down and forward, elbow, forearm of two
        # parallel bones going down and back, wrist, short metacarpus, wide foot
        up, mid, low = f"Leg_front_{side}", f"Shin_front_{side}", f"Foot_front_{side}"
        top = (s * SHOULDER[0], SHOULDER[1], SHOULDER[2])
        add(up, f"epaule_{fr}", top, (6.0, 6.0, 6.0), (0, 0, 0), "joint")
        hum = angles([-0.06 * s, -0.9, -0.43])
        segs, elbow = chain(top, [(hum[0], hum[1], 9.4, 5.6, 5.6)], overlap=0)
        (c, size, r), = segs
        add(up, f"humerus_{fr}", c, size, r, "bone")
        on(up, f"crete_humerus_{fr}", c, r, (0, 2.6, -1.5), (2.0, 1.6, 4.5), "bone_light")
        add(mid, f"coude_{fr}", elbow, (5.8, 5.0, 5.6), (0, 0, 0), "joint")
        fa = angles([0, -0.95, 0.3])
        segs, wrist = chain(elbow, [(fa[0], fa[1], 7.8, 2.4, 2.6)], overlap=0)
        (c, size, r), = segs
        on(mid, f"radius_{fr}", c, r, (1.4 * s, 0, 0), (2.6, 2.6, 7.8), "bone")
        on(mid, f"cubitus_{fr}", c, r, (-1.4 * s, 0, 0.3), (2.2, 2.4, 7.8), "bone_shade")
        on(mid, f"olecrane_{fr}", local(elbow, (0, 0, 0), (0, 0, 0)), (fa[0], fa[1], 0), (-1.0 * s, 1.2, -2.8),
           (2.6, 3.2, 2.6), "joint")
        add(low, f"poignet_{fr}", wrist, (5.2, 3.4, 4.8), (0, 0, 0), "joint")
        mc = angles([0, -0.75, -0.66])
        segs, paw = chain(wrist, [(mc[0], mc[1], 3.4, 5.6, 2.6)], overlap=0)
        (c, size, r), = segs
        add(low, f"metacarpe_{fr}", c, size, r, "bone")
        foot(low, f"main_{fr}", paw, 9 * s, (7.0, 2.0, 6.2), 2.8, 2.25)
        legs[side + "_front"] = (up, mid, low)
        G[up] = (top, "Root")
        G[mid] = (elbow, up)
        G[low] = (wrist, mid)

        # back leg: hip ball, longer femur going down and forward, knee and kneecap, tibia and fibula going down
        # and back, hock with the heel bone, metatarsus coming forward again, foot pushed slightly backwards
        up, mid, low = f"Leg_back_{side}", f"Shin_back_{side}", f"Foot_back_{side}"
        top = (s * HIP[0], HIP[1], HIP[2])
        add(up, f"tete_femur_{fr}", top, (4.6, 4.6, 4.6), (0, 0, 0), "joint")
        fe = angles([0.12 * s, -0.86, -0.42])
        segs, knee = chain(top, [(fe[0], fe[1], 10.6, 4.8, 4.8)], overlap=0)
        (c, size, r), = segs
        add(up, f"femur_{fr}", c, size, r, "bone")
        on(up, f"trochanter_{fr}", c, r, (2.4 * s, 0, 3.8), (2.0, 2.4, 2.6), "bone_shade")
        add(mid, f"genou_{fr}", knee, (5.2, 5.0, 5.0), (0, 0, 0), "joint")
        add(mid, f"rotule_{fr}", (knee[0], knee[1] + 0.4, knee[2] - 2.6), (2.6, 3.0, 1.6), (-10, 0, 0), "bone_light")
        ti = angles([0.04 * s, -0.66, 0.75])
        segs, hock = chain(knee, [(ti[0], ti[1], 8.4, 4.0, 4.0)], overlap=0)
        (c, size, r), = segs
        add(mid, f"tibia_{fr}", c, size, r, "bone")
        on(mid, f"perone_{fr}", c, r, (2.4 * s, -0.4, 0), (1.5, 1.5, 7.6), "bone_shade")
        add(low, f"jarret_{fr}", hock, (4.8, 4.0, 5.0), (0, 0, 0), "joint")
        heel = angles([0, 0.5, 0.86])
        segs, _ = chain(hock, [(heel[0], heel[1], 4.4, 2.6, 2.6)], overlap=0)
        (c, size, r), = segs
        add(low, f"calcaneum_{fr}", c, size, r, "bone_light")
        mt = angles([0, -0.9, -0.42])
        segs, paw = chain(hock, [(mt[0], mt[1], 4.3, 5.2, 2.8)], overlap=0)
        (c, size, r), = segs
        add(low, f"metatarse_{fr}", c, size, r, "bone")
        foot(low, f"pied_{fr}", paw, 13 * s, (6.6, 2.0, 5.8), 2.5, 2.1)
        legs[side + "_back"] = (up, mid, low)
        G[up] = (top, "Root")
        G[mid] = (knee, up)
        G[low] = (hock, mid)

    # ---------------------------------------------------------------- 5. skull (drawn level, then lowered)
    on_head = attach(HEAD_PIVOT, HEAD_R)

    def hd(g, name, c, size, r, mat):
        cc, M = on_head(c, r)
        add(g, name, cc, size, M, mat)

    # braincase: wide and rounded at the back, crests on top and behind
    hd("Head", "boite_cranienne", (0, 33.6, -46.2), (14.5, 9.6, 10.4), (0, 0, 0), "skull")
    hd("Head", "voute", (0, 39.0, -46.4), (11.0, 1.6, 8.6), (0, 0, 0), "skull_light")
    hd("Head", "crete_sagittale", (0, 40.6, -45.6), (2.0, 1.8, 8.4), (0, 0, 0), "skull_light")
    hd("Head", "occiput", (0, 34.6, -41.0), (11.0, 6.4, 1.6), (0, 0, 0), "skull_shade")
    hd("Head", "crete_occipitale", (0, 39.6, -41.3), (6.0, 2.6, 2.2), (-30, 0, 0), "skull_light")
    # cheekbones: the widest part of the skull, arches standing away from the braincase (a gap shows under them)
    for s, side in ((1, "droite"), (-1, "gauche")):
        hd("Head", f"arcade_zygomatique_{side}", (s * 9.3, 30.6, -50.0), (2.6, 3.4, 11.5), (0, 6 * s, 0), "skull")
        hd("Head", f"barre_postorbitaire_{side}", (s * 8.2, 34.2, -51.4), (2.2, 6.0, 2.0), (0, 0, 0), "skull")
        hd("Head", f"plancher_orbite_{side}", (s * 5.8, 31.0, -54.3), (6.4, 2.4, 6.0), (0, 0, 0), "skull")
        # deep eye sockets: dark recessed blocks framed by bone
        hd("Head", f"orbite_{side}", (s * 5.4, 34.4, -53.0), (5.0, 4.6, 4.6), (0, 0, 0), "cavity")
        # brow ridges over the eyes, inner end lower: a frown
        hd("Head", f"arcade_sourciliere_{side}", (s * 5.4, 37.6, -53.6), (6.8, 2.6, 4.2), (-8, 0, 13 * s), "skull_light")
    hd("Head", "pont_interorbitaire", (0, 35.0, -54.4), (4.0, 5.4, 7.6), (0, 0, 0), "skull")
    hd("Head", "front", (0, 37.6, -49.8), (10.0, 2.4, 6.0), (0, 0, 0), "skull")
    # short, wide, slightly flat snout
    hd("Head", "maxillaire", (0, 30.4, -58.6), (12.0, 6.6, 8.6), (0, 0, 0), "skull")
    hd("Head", "nasal", (0, 34.4, -59.2), (7.2, 1.8, 9.4), (0, 0, 0), "skull_light")
    hd("Head", "premaxillaire", (0, 29.8, -64.4), (9.6, 5.2, 3.4), (0, 0, 0), "skull")
    for s, side in ((1, "droite"), (-1, "gauche")):
        hd("Head", f"narine_{side}", (s * 2.2, 31.2, -66.0), (2.4, 2.4, 0.6), (0, 0, 0), "cavity_flat")
        hd("Head", f"bord_maxillaire_{side}", (s * 6.4, 28.0, -57.0), (1.2, 1.2, 9.0), (0, 0, 0), "skull_shade")
    # upper teeth: two big canines (base and point, turned a little inwards), irregular side teeth, incisors
    for s, side in ((1, "droite"), (-1, "gauche")):
        hd("Head", f"croc_superieur_{side}", (s * 5.0, 25.8, -62.4), (1.9, 3.4, 1.9), (0, 0, 7 * s), "tooth_down")
        hd("Head", f"pointe_croc_superieur_{side}", (s * 4.75, 22.9, -62.4), (1.2, 2.6, 1.2), (0, 0, 7 * s),
           "tooth_down")
        for n, (z, hgt) in enumerate(((-59.6, 2.2), (-57.6, 1.6), (-55.6, 2.0), (-53.6, 1.4), (-51.8, 1.7))):
            hd("Head", f"dent_superieure_{side}_{n + 1}", (s * (6.0 - n * 0.12), 27.1 - hgt / 2, z), (1.1, hgt, 1.2),
               (0, 0, 0), "tooth_down")
    for n, (x, hgt) in enumerate(((-2.7, 1.6), (-0.9, 2.0), (0.9, 1.8), (2.7, 1.4))):
        hd("Head", f"incisive_superieure_{n + 1}", (x, 27.0 - hgt / 2, -65.2), (1.1, hgt, 1.0), (0, 0, 0), "tooth_down")

    # ---------------------------------------------------------------- 6. jaw: two branches and a chin, opened
    G["Jaw"] = (on_head(JAW_HINGE, (0, 0, 0))[0], "Head")
    jaw = attach(JAW_HINGE, JAW_OPEN)

    def jw(name, c, size, r, mat):
        cc, M = jaw(c, r)
        hd("Jaw", name, cc, size, M, mat)

    for s, side in ((1, "droite"), (-1, "gauche")):
        jw(f"branche_mandibule_{side}", (s * 6.1, 24.8, -55.0), (2.6, 3.6, 15.0), (0, 10 * s, 0), "skull")
        jw(f"apophyse_coronoide_{side}", (s * 7.2, 27.6, -49.4), (2.0, 4.2, 3.0), (12, 0, 0), "skull_shade")
        jw(f"condyle_{side}", (s * 7.6, 26.8, -47.6), (2.6, 2.2, 2.6), (0, 0, 0), "joint")
        jw(f"croc_inferieur_{side}", (s * 3.0, 27.6, -61.6), (1.6, 3.4, 1.6), (0, 0, -6 * s), "tooth_up")
        for n, (z, hgt) in enumerate(((-59.0, 1.5), (-56.8, 1.9), (-54.6, 1.3), (-52.6, 1.6))):
            jw(f"dent_inferieure_{side}_{n + 1}", (s * (4.7 + n * 0.35), 26.5 + hgt / 2, z), (1.0, hgt, 1.1),
               (0, 0, 0), "tooth_up")
    jw("menton", (0, 24.6, -61.8), (7.0, 3.4, 4.2), (0, 0, 0), "skull")
    for n, x in enumerate((-1.1, 1.1)):
        jw(f"incisive_inferieure_{n + 1}", (x, 26.9, -63.2), (1.0, 1.4, 1.0), (0, 0, 0), "tooth_up")

    # ---------------------------------------------------------------- 7. ram horns: 8 segments, out, back, down, forward, up
    # direction (in the head's frame), length, thickness; the left horn mirrored then turned a few degrees more.
    HORN_SEGS = (((0.95, 0.30, 0.05), 5.6, 5.6, "horn_base"), ((0.75, 0.12, 0.65), 5.2, 5.0, "horn_base"),
                 ((0.40, -0.40, 0.82), 4.8, 4.4, "horn"), ((0.20, -0.92, 0.33), 4.5, 3.8, "horn"),
                 ((0.08, -0.70, -0.71), 4.1, 3.2, "horn"), ((-0.10, -0.05, -0.99), 3.6, 2.6, "horn"),
                 ((-0.25, 0.45, -0.86), 2.8, 2.0, "horn_tip"), ((-0.35, 0.75, -0.56), 1.9, 1.4, "horn_tip"))
    for s, side, bone_name, twist in ((1, "droite", "Horn_right", rot(0, 0, 0)),
                                      (-1, "gauche", "Horn_left", rot(5, -4, -3))):
        base = (s * 8.6, 37.8, -45.6)
        specs = []
        for (dx, dy, dz), L, th, mat in HORN_SEGS:
            rx, ry = angles([dx * s, dy, dz])
            specs.append((rx, ry, L * HORN_LENGTH, th, th))
        segs, _ = chain(base, specs, overlap=0.3)
        tw = attach(base, twist)
        for n, ((c, size, r), seg) in enumerate(zip(segs, HORN_SEGS)):
            cc, M = tw(c, r)
            hd(bone_name, f"corne_{side}_{n + 1}", cc, size, M, seg[3])
        G[bone_name] = (on_head(base, (0, 0, 0))[0], "Head")

    # ---------------------------------------------------------------- 8. dorsal plates: three-piece bony blades
    for i, (z, H, L, th, tilt, lean, yaw) in enumerate(PLATES):
        g = f"Plate_{i + 1}"
        par = "Chest" if z < HIPS_PIVOT[2] else "Hips"
        b = (0, Y(z) + 4.2, z)
        specs = [(-(90 - tilt), 0, H * 0.34, th, L, (0, 0)),
                 (-(90 - tilt - 6), 0, H * 0.28, th * 0.82, L * 0.70, (0, -L * 0.10)),
                 (-(90 - tilt - 13), 0, H * 0.24, th * 0.62, L * 0.42, (0, -L * 0.20)),
                 (-(90 - tilt - 22), 0, H * 0.22, th * 0.45, L * 0.18, (0, -L * 0.26))]
        segs, _ = chain(b, specs, overlap=0.0)
        tw = attach(b, rot(0, yaw, lean))
        names = ("base", "milieu", "haut", "pointe")
        for n, ((c, size, r), mat) in enumerate(zip(segs, ("plate_mid", "plate_mid", "plate_mid", "plate_tip"))):
            cc, M = tw(c, r)
            add(g, f"plaque_{i + 1}_{names[n]}", cc, size, M, mat)
        c, size, r = segs[0]                               # dark junction with the spine
        cc, M = tw(local(c, r, (0, 0, -size[2] / 2 + 0.6)), r)
        add(g, f"plaque_{i + 1}_racine", cc, (th * 1.12, L * 1.04, 1.6), M, "plate_base")
        if i in (1, 3):                                   # a small barb on the front edge of two plates
            c, size, r = segs[1]
            cc, M = tw(local(c, r, (0, size[1] / 2 + 0.6, -size[2] * 0.1)), r)
            add(g, f"plaque_{i + 1}_eperon", cc, (th * 0.5, 1.6, 2.2), M, "plate_mid")
        G[g] = (b, par)

    # ---------------------------------------------------------------- 9. tail: 12 single vertebrae, one bone each
    p, parent = list(TAIL_START), "Hips"
    n = len(TAIL)
    for i, (rx, ry, L) in enumerate(TAIL):
        t = i / (n - 1)
        w = 5.4 - 3.8 * t
        g = f"Tail_{i + 1}"
        G[g] = (tuple(p), parent)
        d = direction(rx, ry)
        c = [p[q] + d[q] * L / 2 for q in range(3)]
        r = (rx, ry, 0)
        add(g, f"vertebre_queue_{i + 1}", c, (w, w * 0.9, L - 0.9), r, "bone" if i < 8 else "bone_light")
        if i < 7:
            on(g, f"apophyse_queue_{i + 1}", c, r, (0, w * 0.45 + w * 0.16, 0.3), (1.0, w * 0.38, 1.6), "bone_light")
        if i < 4:
            for s, side in ((1, "droite"), (-1, "gauche")):
                on(g, f"transverse_queue_{i + 1}_{side}", c, r, (s * (w * 0.5 + w * 0.17), -0.3, 0),
                   (w * 0.34, 0.8, 1.4), "bone_shade")
        if 1 <= i <= 5:
            on(g, f"chevron_queue_{i + 1}", c, r, (0, -(w * 0.45 + w * 0.13), 0.4), (1.0, w * 0.26, 1.2), "bone_shade")
        p = [p[q] + d[q] * L for q in range(3)]
        parent = g

    # ---------------------------------------------------------------- 10. hit boxes (BetterModel tag "b_")
    # One invisible box per part a player aims at, carried by its bone (it follows the animations): the skull, the
    # rib cage, the pelvis. Square seen from above, so that they stay right whichever way the boss turns (BetterModel
    # keeps them axis aligned). Before the 2026-11-11 play test the only box was the polar bear's, under the chest:
    # a click on the skull or the hips went through.
    # the skull box reaches 3 units past the snout (a hit on the tip of the muzzle lands)
    for g, par, c, size in (("b_skull", "Head", on_head((0, 31.5, -56), (0, 0, 0))[0], (26, 19, 26)),
                            ("b_chest", "Chest", (0, 24, -11), (30, 21, 30)),
                            ("b_hips", "Hips", (0, 23.5, 20), (22, 15, 20))):
        add(g, "boite_" + g[2:], c, size, (0, 0, 0), "hitbox")
        G[g] = (tuple(c), par)

    # ---------------------------------------------------------------- each leg set exactly on the ground (y = 0)
    shift = {}
    for bones in legs.values():
        low = min(q[1] for g, _, c, size, r, m in P if g in bones for q in corners(c, size, r))
        for b in bones:
            shift[b] = low
    P = [(g, nm, [c[0], c[1] - shift.get(g, 0), c[2]], size, r, m) for g, nm, c, size, r, m in P]
    G = {k: ((pv[0], pv[1] - shift.get(k, 0), pv[2]), par) for k, (pv, par) in G.items()}
    order, done = [], set()
    while len(order) < len(G):
        for k, (pv, par) in G.items():
            if k not in done and (par is None or par in done):
                order.append(k)
                done.add(k)
    return P, {k: G[k] for k in order}, {k: round(v, 2) for k, v in shift.items() if k.startswith("Foot")}


def pieces(P):
    """BetterModel pieces: distinct cube orientations per bone."""
    seen = {}
    for g, n, c, size, r, m, raw in P:
        if g in HITBOX:
            continue
        seen.setdefault(g, set()).add(tuple(round(v, 1) for v in euler(matrix(r))))
    return sum(len(v) for v in seen.values()), seen


def table(P, path):
    """Every cube: bone, name, centre, size, Blockbench rotation, material (model as delivered, units)."""
    rows = ["| Os | Volume | Centre (x, y, z) | Taille (x, y, z) | Rotation (x, y, z) | Matériau |",
            "| -- | ------ | ---------------- | ---------------- | ------------------ | -------- |"]
    f = lambda v: ", ".join(f"{x:.2f}".replace(".", ",") for x in v)  # noqa: E731
    for g, n, c, size, r, m, raw in P:
        rows.append(f"| `{g}` | {n} | {f(c)} | {f(size)} | {f(euler(matrix(r)))} | {m} |")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(rows) + "\n")


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = cubes = None
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    anims_dir = None
    show = "--mesures" in args
    if show:
        args.remove("--mesures")
    if "--anims" in args:
        i = args.index("--anims")
        anims_dir = args[i + 1]
        del args[i:i + 2]
    if "--cubes" in args:
        i = args.index("--cubes")
        cubes = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    raw, groups, shifts = parts()
    P, groups, dims = ground(raw, groups, scale=SCALE, center=True)
    # the entity stands under the front of the rib cage, not in the middle of the bounding box (tail included): its
    # hit box covers the chest, the skull is 2.8 blocks in front of it, the tail tip 4.6 behind
    dz = -(groups["Leg_front_right"][0][2] + groups["Leg_back_right"][0][2]) / 2 + ORIGIN_SHIFT * SCALE
    P = [(g, n, [c[0], c[1], c[2] + dz], size, r, m, rw) for g, n, c, size, r, m, rw in P]
    groups = {k: ((pv[0], pv[1], pv[2] + dz), par) for k, (pv, par) in groups.items()}
    uv, atlases, sizes = bake(P, MATS, {})
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    data["textures"] = data["textures"][:1]          # nothing glows: no emissive texture
    # hit boxes have no texture on any face: BetterModel draws nothing for them (as the familiars' "hitbox" bone)
    for e, q in zip(data["elements"], P):
        if q[0] in HITBOX:
            e["faces"] = {d: {"uv": [0, 0, 0, 0], "texture": None} for d in ("north", "east", "south", "west", "up", "down")}
    import ossacorne_anim as OA
    hidden = {e["uuid"] for e, q in zip(data["elements"], P) if q[0] in HITBOX}
    seen = dict(data, elements=[e for e in data["elements"] if e["uuid"] not in hidden])   # what players see
    anims = OA.build(seen, lambda name: str(uuid.uuid5(NS, f"{IDENT}/{name}")), scale=SCALE)
    data["animations"] = anims
    if anims_dir:
        OA.strips(seen, anims, atlases, sizes, anims_dir)
    if show:
        for name, rows in OA.measure(seen, anims).items():
            print(f"--- {name}: plus bas {min(r['low'] for r in rows):.2f}, corps {min(r['body_low'] for r in rows):.2f}, "
                  f"plus haut {max(r['high'] for r in rows):.2f}")
            for r in rows[::4]:
                print(f"  tick {r['tick']:3d}  bas {r['low']:5.2f}  corps {r['body_low']:5.2f}  pieds {r['feet']}  museau {r['snout']}")
    data["animations"] = OA.finish(anims)
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False)
    count, _ = pieces(P)
    for g in HITBOX:
        q = next(q for q in P if q[0] == g)
        print(f"  {g} : {q[3][0] / 16:.2f} x {q[3][1] / 16:.2f} x {q[3][2] / 16:.2f} blocs, centre "
              f"{', '.join(f'{v / 16:.2f}' for v in q[2])}")
    print(f"{len(P) - len(HITBOX)} volumes, {len(groups)} os, {count} morceaux BetterModel ; "
          f"{dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; texture {sizes[0]}")
    print("pattes posées au sol (décalage en unités) :", shifts)
    print("écrit", path)
    if cubes:
        table(P, cubes)
        print("écrit", cubes)
    if prev:
        apercu(seen, atlases, sizes, prev, set(),
               views=((35, 12), (0, 4), (90, 2), (270, 2), (180, 6), (215, 14)), size=420)
        print("écrit", prev)
