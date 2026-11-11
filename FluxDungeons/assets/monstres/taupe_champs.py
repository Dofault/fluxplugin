#!/usr/bin/env python3
"""Taupe des Champs: farm boss, STATIC Blockbench model (.bbmodel) (2026-11-11).

A real mole first, a boss second: a plump spindle-shaped body low on the ground (the belly is the widest part), a head
that flows straight out of the shoulders with no neck, a long snout tilted down with a small dark nose, tiny eyes and
ears lost in the fur, and two huge shovel forepaws turned outwards with four thick ivory claws each. Hind legs are
small and half hidden under the belly, the tail is a short stub. Built like the Sea Otter and the Abyss Colossus: a
few large boxes per mass, side, top and rear boxes smaller and tilted a few degrees to round the silhouette, every
small detail (fur, mineral marks, claw stripes) painted at 1 texel per unit.

Fantasy stays discreet: ochre mineral marks painted on the shoulders and flanks, four small rock shards along the
spine (tilted backwards, less than 0.3 block high), darker fur around the eyes, a stony shade at the base of the claws.
No food, no tools, no armour, no glow.

Pose baked in the boxes, bones placed at the joints for a later animation; no animation.

Usage: taupe_champs.py [output folder] [--apercu <sheet.png>]
Front towards north (-z), the monster's right towards +x, ground at y = 0, 16 units = 1 block.
"""
import json
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


# --------------------------------------------------------------------------- brief palette (dark -> light)
FUR = pal("#4B3026", "#5A392B", "#67432F", "#76513D", "#91674D", "#A77B5D")   # base = index 3
BELLY = pal("#674735", "#856149", "#A1785A")
SNOUT = pal("#694635", "#8D6651", "#A98268")
NOSE = pal("#211714", "#30201C", "#594039")
PAW = pal("#3C2A22", "#59402F", "#75543C")
CLAW = pal("#A18D70", "#C8B99A", "#E0D2B7")
STONE = pal("#51483F", "#716456", "#968675", "#B3A18A")
OCHRE = pal("#78502C", "#A36F38", "#C09252")
EYE, EYE_GLINT = hx("#1C130F"), hx("#E0D2B7")

SIDE = {"up": 0, "north": 0, "east": 0, "west": 0, "south": -1, "down": -2}   # light from above, a bit from the front

# small irregular mineral marks, read as (dx, dy, tone)
MARKS = (
    ((0, 0, 1), (1, 0, 1), (1, 1, 0), (2, 1, 1), (2, 2, 0)),
    ((0, 1, 0), (1, 0, 1), (1, 1, 2), (2, 1, 1), (1, 2, 0), (3, 2, 0)),
    ((0, 0, 0), (1, 0, 1), (2, 1, 1), (3, 1, 0)),
)


def speckle(px, r, palette, b, density_mid=0.2, density_dark=0.035, density_light=0.025):
    """Dense short fur: base tone, clusters of 1 to 3 pixels one tone darker or lighter (no single hairs drawn)."""
    h, w = len(px), len(px[0])
    for kind, dens, d in (("mid", density_mid, -1), ("dark", density_dark, -2), ("light", density_light, 1)):
        n = int(w * h * dens / 2)
        for _ in range(n):
            x, y = r.randrange(w), r.randrange(h)
            c = tone(palette, b + d)
            px[y][x] = c
            if r.random() < 0.6 and x + 1 < w:
                px[y][x + 1] = c
            if r.random() < 0.3 and y + 1 < h:
                px[y + 1][x] = c


# --------------------------------------------------------------------------- painters
def fur(base=3, marks=0, belly_edge=0, top_light=True):
    def f(w, h, r, face):
        """Fur: main brown everywhere, lighter on top faces (back, shoulders, skull), shaded lower rows on the sides,
        `belly_edge` rows at the bottom fading irregularly into the belly brown, `marks` ochre mineral marks on the
        side faces (east / west)."""
        b = base + SIDE[face] if (top_light or face != "up") else base
        if face == "down":
            px = [[BELLY[0]] * w for _ in range(h)]
            speckle(px, r, BELLY, 0, 0.15, 0, 0.05)
            return px
        px = [[tone(FUR, b)] * w for _ in range(h)]
        speckle(px, r, FUR, b, density_light=0.16 if (face == "up" and top_light) else 0.025)
        if face in ("east", "west", "north", "south") and h >= 4:
            for x in range(w):                                     # light catching the top edge
                if r.random() < 0.35:
                    px[0][x] = tone(FUR, b + 1)
            px[h - 1] = [tone(FUR, b - 1) if r.random() < 0.8 else tone(FUR, b - 2) for _ in range(w)]
            for k in range(belly_edge):                            # soft irregular fade into the belly
                y = h - 1 - k
                p = 0.85 - 0.25 * k
                px[y] = [tone(BELLY, 1 if r.random() < 0.7 else 0) if r.random() < p else px[y][x] for x in range(w)]
        if marks and face in ("east", "west") and w >= 8 and h >= 6:
            for k in range(marks):
                m = MARKS[r.randrange(len(MARKS))]
                x0 = int((k + 0.5) * w / marks) - 2 + r.randrange(-2, 3)
                y0 = 1 + r.randrange(max(1, h // 2 - 1))
                for dx, dy, t in m:
                    x, y = x0 + dx, y0 + dy
                    if 0 <= x < w and 0 <= y < h:
                        px[y][x] = OCHRE[t]
        if marks and face == "up" and w >= 8 and h >= 8:
            m = MARKS[r.randrange(len(MARKS))]
            x0, y0 = r.randrange(1, w - 4), r.randrange(1, h - 3)
            for dx, dy, t in m:
                px[y0 + dy][x0 + dx] = OCHRE[t]
        return px
    return f


def belly(w, h, r, face):
    """Belly: just a softer, warmer brown than the body (no big beige patch), shaded towards the ground."""
    b = {"up": 2, "down": 0}.get(face, 1)
    px = [[BELLY[b]] * w for _ in range(h)]
    speckle(px, r, BELLY, b, 0.2, 0.0, 0.05)
    if face in ("east", "west", "north", "south"):
        for x in range(w):                                         # irregular top edge: fur reaching down
            for y in range(min(h, 1 + r.randrange(3))):
                px[y][x] = tone(FUR, 2 if r.random() < 0.6 else 3)
        if h >= 3:
            px[h - 1] = [BELLY[0]] * w
    return px


def snout(base=1):
    def f(w, h, r, face):
        """Snout: warmer and lighter than the skull, top a tone lighter, underside shaded."""
        b = base + {"up": 1, "down": -1}.get(face, 0)
        px = [[tone(SNOUT, b)] * w for _ in range(h)]
        speckle(px, r, SNOUT, b, 0.18, 0.0, 0.04)
        if face in ("east", "west", "north", "south") and h >= 3:
            px[h - 1] = [tone(SNOUT, b - 1)] * w
        return px
    return f


def nose(w, h, r, face):
    """Nose: very dark brown, two nostrils on the front, a one-pixel highlight on the top edge."""
    px = [[NOSE[1]] * w for _ in range(h)]
    if face == "north":
        if w >= 4 and h >= 2:
            px[h - 1][1] = px[h - 1][w - 2] = NOSE[0]
            if h >= 3:
                px[h - 2][1] = px[h - 2][w - 2] = NOSE[0]
        px[0][w // 2] = NOSE[2]
    elif face == "up":
        px[h - 1][w // 2] = NOSE[2]
    elif face == "down":
        px = [[NOSE[0]] * w for _ in range(h)]
    return px


def eye(w, h, r, face):
    """Tiny eye (density 2): almost black brown, one cream glint pixel."""
    px = [[EYE] * w for _ in range(h)]
    if face in ("east", "west") and w >= 2 and h >= 2:
        px[0][1 if face == "east" else w - 2] = EYE_GLINT
    return px


def eye_ring(w, h, r, face):
    """Darker fur around the eye (not a full mask)."""
    px = [[FUR[0] if r.random() < 0.7 else FUR[1]] * 1 for _ in range(h)]
    return [[FUR[0] if r.random() < 0.65 else FUR[1] for _ in range(w)] for _ in range(h)]


def ear(w, h, r, face):
    """Ear opening: dark brown inside, lighter brown rim, lost in the fur."""
    px = [[FUR[3]] * w for _ in range(h)]
    if face in ("east", "west") and w >= 3 and h >= 3:
        for y in range(1, h - 1):
            for x in range(1, w - 1):
                px[y][x] = FUR[0]
        px[0] = [FUR[4]] * w
    return px


def paw(base=1):
    def f(w, h, r, face):
        """Forelimb and hind feet: darker brown than the body (the paws stand out from the belly), knuckle bumps."""
        b = base + {"up": 1, "down": -1}.get(face, 0)
        px = [[tone(PAW, b)] * w for _ in range(h)]
        for _ in range(max(1, w * h // 7)):
            x, y = r.randrange(w), r.randrange(h)
            px[y][x] = tone(PAW, b - 1 if r.random() < 0.7 else b + 1)
        if face in ("east", "west", "north", "south") and h >= 3:
            px[h - 1] = [PAW[0]] * w
        return px
    return f


def shovel(w, h, r, face):
    """Shovel palm: dark leathery brown, lighter ridges of the fingers running towards the claws on the broad faces."""
    b = {"up": 2, "down": 0}.get(face, 1)
    px = [[PAW[b]] * w for _ in range(h)]
    for _ in range(max(1, w * h // 8)):
        px[r.randrange(h)][r.randrange(w)] = PAW[max(0, b - 1)]
    if face in ("up", "down") and w >= 8:
        for k in range(4):
            x = round((k + 0.5) * w / 4 - 0.5)
            for y in range(h):
                if r.random() < 0.8:
                    px[y][x] = PAW[min(2, b + 1)] if face == "up" else PAW[0]
    if face == "north" and w >= 8:
        for k in range(4):
            x = round((k + 0.5) * w / 4 - 0.5)
            px[h - 1][x] = STONE[0]
    return px


def claw(tip=False):
    def f(w, h, r, face):
        """Claw: dirty ivory, light along the top, slight lengthwise stripes, mineral grey at the base (the root end
        is the south face of a claw drawn towards -z)."""
        b = {"up": 2, "down": 0}.get(face, 1)
        px = [[CLAW[b]] * w for _ in range(h)]
        if face in ("east", "west", "up", "down"):
            for y in range(h):
                for x in range(w):
                    if (y if face in ("east", "west") else x) % 2 == 1 and r.random() < 0.5:
                        px[y][x] = CLAW[max(0, b - 1)]
            if not tip:
                # mineral base: the rear columns (east / west) or rows (up / down) towards the paw
                if face in ("east", "west"):
                    cols = range(w - 2, w) if face == "east" else range(0, 2)
                    for y in range(h):
                        for x in cols:
                            if 0 <= x < w:
                                px[y][x] = STONE[1] if r.random() < 0.6 else STONE[0]
                else:
                    rows = range(h - 2, h) if face == "up" else range(0, 2)
                    for y in rows:
                        if 0 <= y < h:
                            px[y] = [STONE[1] if r.random() < 0.6 else STONE[0] for _ in range(w)]
        elif face == "south" and not tip:
            px = [[STONE[0]] * w for _ in range(h)]
        return px
    return f


def stone(w, h, r, face):
    """Rock shard: contrasted greys and browns of compacted earth and stone, highlight on top."""
    b = {"up": 2, "down": 0, "south": 1}.get(face, 1)
    px = [[STONE[b]] * w for _ in range(h)]
    for _ in range(max(1, w * h // 3)):
        x, y = r.randrange(w), r.randrange(h)
        px[y][x] = STONE[max(0, min(3, b + r.choice((-1, -1, 1))))]
    if face in ("east", "west", "north", "south"):
        px[0] = [STONE[min(3, b + 1)] if r.random() < 0.6 else px[0][x] for x in range(w)]
        if h >= 3:
            px[h - 1] = [FUR[1]] * w                     # where the shard sinks into the fur
    if face == "up":
        px[r.randrange(h)][r.randrange(w)] = STONE[3]
    return px


MATS = {
    "body": fur(3, belly_edge=2), "flank": fur(3, marks=1, belly_edge=3), "shoulder": fur(3, marks=1),
    "back": fur(3, marks=1), "rump": fur(3, belly_edge=2), "head": fur(3), "head_low": fur(2),
    "chest": fur(2, top_light=False), "belly": belly, "snout": snout(1), "snout_tip": snout(1),
    "nose": (nose, 2), "eye": (eye, 2), "eye_ring": (eye_ring, 2), "ear": (ear, 2),
    "arm": paw(1), "forearm": paw(1), "shovel": shovel, "claw": (claw(False), 2), "claw_tip": (claw(True), 2),
    "foot": paw(1), "tail": fur(2), "stone": stone,
}


# --------------------------------------------------------------------------- skeleton (drawing units)
BODY_C = (0, 17, 6)            # centre of gravity
NECK = (0, 20, -19)            # head joint, inside the shoulders (no visible neck)
TAIL_ROOT = (0, 14, 33)
SHOULDERS = {1: (19, 17, -12), -1: (-19, 17, -12)}
WRISTS = {1: (21.5, 7.5, -22), -1: (-22, 7.5, -21)}
HIPS = {1: (15, 9, 24), -1: (-15, 9, 24)}

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

    # ---------------------------------------------------------------- head: straight out of the shoulders
    on_head = attach(NECK, rot(-6, 0, 0))

    def hd(name, c, size, r, mat):
        cc, M = on_head(c, r)
        add("Head", name, cc, size, M, mat)

    hd("nuque", (0, 27, -18), (28, 8, 10), (-14, 0, 0), "head")          # skull continues the back line
    hd("crane", (0, 20, -22.5), (28, 20, 11), (0, 0, 0), "head")
    hd("joues", (0, 15, -24), (30, 10, 9), (0, 0, 0), "head_low")         # cheeks melt into the shoulders
    hd("tete_avant", (0, 18.5, -30), (20, 16, 8), (-4, 0, 0), "head")
    hd("menton", (0, 11.5, -29), (15, 4, 9), (0, 0, 0), "head_low")
    hd("museau", (0, 15.5, -37), (11, 9, 9), (-9, 0, 0), "snout")
    hd("museau_dessus", (0, 20, -36), (8, 2.5, 8), (-11, 0, 0), "snout")  # rounded top of the snout
    hd("museau_bout", (0, 13.6, -43.8), (7, 6, 7), (-12, 0, 0), "snout_tip")
    hd("nez", (0, 14.6, -47.6), (4.6, 3.2, 2.2), (-12, 0, 0), "nose")
    for s, side in ((1, "droit"), (-1, "gauche")):
        hd(f"cerne_{side}", (10.1 * s, 20.6, -30.5), (0.4, 3.5, 3.5), (0, 0, 0), "eye_ring")
        hd(f"oeil_{side}", (10.3 * s, 20.6, -30.6), (0.5, 1.5, 1.5), (0, 0, 0), "eye")
        hd(f"oreille_{side}", (14.15 * s, 23, -20), (0.6, 2.5, 3), (0, 0, 0), "ear")

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
    prev = None
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims = ground(parts(), GROUPS, scale=SCALE)
    uv, atlases, sizes = bake(P, MATS, {})
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    data["textures"] = data["textures"][:1]          # nothing glows: no emissive texture
    print(f"{len(P)} volumes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; texture {sizes[0]}")
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
    if prev:
        apercu(data, atlases, sizes, prev, set(), views=((35, 12), (0, 6), (90, 3), (0, 88)), size=420)
