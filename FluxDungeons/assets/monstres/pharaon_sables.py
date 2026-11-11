#!/usr/bin/env python3
"""Sand Pharaoh (Pharaon des Sables): boss of the Deblayeur dungeon, STATIC Blockbench model (.bbmodel).

Second version (2026-11-11), rebuilt from scratch: a standing humanoid pharaoh whose body itself is mineral, cut out
of an old Egyptian temple. Heavy statue proportions (short pillar legs, massive torso, wide shoulders, long arms with
thick forearms and big fists), clay joints, a nemes headdress wrapping the skull and framing the face, a demonic face
(V brows, narrow glowing amber eyes, massive brow, dark mouth), head slightly lowered: he looks down on the players.
Gold and lapis only underline the royal parts.

Built from the brief "Pharaon des Sables, recreation integrale": positions and sizes of the brief converted to units
(1 block = 16), then fixed where the brief hid a piece (collar behind the chest plate), left one floating (loincloth,
bracelets only on the front of the forearm) or went past 3.9 blocks high (upper body lowered by LOWER units).

Usage: pharaon_sables.py [output folder] [--apercu <sheet.png>]
Front faces north (-z), the model's right side is +x, ground at y = 0, origin between the feet.
"""
import json
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, corners, export, ground, hx  # noqa: E402
from golem_fonderie import rot  # noqa: E402

IDENT = "pharaon_sables"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000050a1e6")
SCALE = 1.0
LOWER = 2.0           # units the whole upper body (pelvis and above) is lowered so the headdress stays under 3.9


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- brief palette (dark, base, light)
SAND = pal("#BF9860", "#D5AC70", "#E0BA80")        # main sandstone
OCHRE = pal("#A0703C", "#B98249", "#C99459")       # secondary sandstone
PALE = pal("#D2AE78", "#E5C18A", "#F0D29E")        # pale sandstone
CLAY = pal("#7E4630", "#98563B", "#AA6748")        # clay
EARTH = pal("#3E2920", "#573A2C", "#6C4A39")       # dark earth
GOLD = pal("#AE8233", "#D6A444", "#EAC066")        # antique gold
LAPIS = pal("#1A2C52", "#263E70", "#38568F")       # lapis-lazuli
AMBER = pal("#D97A0C", "#FF9B19", "#FFC66A")       # eyes

SHADE = {"up": 2, "north": 1, "south": 1, "east": 1, "west": 1, "down": 0}


# --------------------------------------------------------------------------- painters
def stone(P, cracks=0.3, joints=True):
    def f(w, h, r, face):
        """Cut sandstone block: flat colour (lighter top, darker bottom), darker lower edge, short tool marks,
        a stepped dark-earth joint between two stones and sometimes a small crack."""
        base, dark = P[SHADE[face]], P[max(0, SHADE[face] - 1)]
        px = [[base] * w for _ in range(h)]
        for _ in range(max(1, w * h // 20)):                      # tool marks, never long lines
            x, y = r.randrange(w), r.randrange(h)
            for k in range(r.randrange(1, 4)):
                if x + k < w:
                    px[y][x + k] = dark if r.random() < 0.75 else P[min(2, SHADE[face] + 1)]
        if face not in ("up", "down") and h >= 4:
            px[h - 1] = [dark] * w                                 # darker lower edge
            if r.random() < 0.6:
                px[0] = [P[2] if (x + r.randrange(3)) % 3 else base for x in range(w)]
        if joints and face != "down" and w >= 8 and h >= 7 and r.random() < 0.7:
            y = r.randrange(2, h - 2)                              # joint between two stone blocks, stepped
            px[y] = [EARTH[1]] * w
            x = r.randrange(2, w - 2)
            for yy in range(y + 1, h):
                px[yy][x] = EARTH[1]
        if r.random() < cracks and w >= 5 and h >= 5:              # small dark-earth crack
            x, y = r.randrange(1, w - 2), r.randrange(1, h - 2)
            for k in range(r.randrange(2, 5)):
                px[min(h - 1, y + k)][min(w - 1, x + k // 2)] = EARTH[0]
        return px
    return f


def flat(P):
    def f(w, h, r, face):
        """Plain stone with only light, for small parts."""
        px = [[P[SHADE[face]]] * w for _ in range(h)]
        if face not in ("up", "down") and h >= 3:
            px[h - 1] = [P[max(0, SHADE[face] - 1)]] * w
        return px
    return f


def nemes_lappet(w, h, r, face):
    """Nemes lappet: lapis with thin gold stripes every 3 pixels (the classic striped cloth), plain lapis edge."""
    if face in ("up", "down"):
        return [[LAPIS[1 if face == "up" else 0]] * w for _ in range(h)]
    px = [[LAPIS[1]] * w for _ in range(h)]
    for y in range(h):
        if y % 3 == 1:
            px[y] = [GOLD[1] if face in ("east", "west") else PALE[0]] * w
    px[h - 1] = [LAPIS[0]] * w
    return px


def nemes_top(w, h, r, face):
    """Top of the nemes: lapis, stripes front to back on top and sides (pale sandstone, gold on the sides)."""
    px = [[LAPIS[SHADE[face] if face != "up" else 1]] * w for _ in range(h)]
    if face == "up":
        for y in range(h):
            for x in range(1, w, 3):
                px[y][x] = PALE[0]
    elif face in ("east", "west", "south"):
        for y in range(h):
            if y % 3 == 1:
                px[y] = [GOLD[1]] * w
    return px


def sun(w, h, r, face):
    """Sun symbol (density 2): clay disc ringed with gold, stepped rays on top, dark gold sides."""
    if face != "north":
        return [[GOLD[0]] * w for _ in range(h)]
    px = [[GOLD[1]] * w for _ in range(h)]
    cx, cy = (w - 1) / 2, (h - 1) / 2 + 0.5
    for y in range(h):
        for x in range(w):
            d = max(abs(x - cx), abs(y - cy))
            if d <= 1.5:
                px[y][x] = CLAY[1]
            elif d <= 2.5:
                px[y][x] = GOLD[2]
            if x in (0, w - 1) or y == h - 1:
                px[y][x] = GOLD[0]
    for x in range(1, w - 1, 2):
        px[0][x] = GOLD[2]
    return px


def band(w, h, r, face):
    """Gold band with small lapis squares (headband, belt)."""
    if face in ("up", "down"):
        return [[GOLD[0 if face == "down" else 2]] * w for _ in range(h)]
    px = [[GOLD[1]] * w for _ in range(h)]
    if h >= 3:
        for x in range(1, w - 1, 3):
            px[h // 2][x] = LAPIS[1]
    px[h - 1] = [GOLD[0]] * w
    return px


def collar(w, h, r, face):
    """Usekh collar: rows of gold, lapis and clay beads."""
    if face == "down":
        return [[GOLD[0]] * w for _ in range(h)]
    rows = [GOLD[2], LAPIS[1], GOLD[1], CLAY[1], LAPIS[1], GOLD[0]]
    px = [[rows[min(y, len(rows) - 1)]] * w for y in range(h)]
    if h >= 4:
        px[3] = [CLAY[1] if x % 2 else GOLD[1] for x in range(w)]
    return px


def lapis_framed(w, h, r, face):
    """Lapis plate framed with gold (chest ornament, collar plate), a gold chevron in the middle."""
    px = [[LAPIS[1]] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            if x in (0, w - 1) or y in (0, h - 1):
                px[y][x] = GOLD[1]
    if face == "north" and w >= 5 and h >= 5:
        c = w // 2
        for k in range(min(c, h - 2)):                             # chevrons pointing down
            for y in (1 + k, 4 + k):
                if y < h - 1:
                    for x in (c - 1 - k, c + k):
                        if 0 < x < w - 1:
                            px[y][x] = GOLD[2]
    return px


def gold_inlay(w, h, r, face):
    px = [[GOLD[1]] * w for _ in range(h)]
    if face == "north" and h >= 4:
        for y in range(1, h - 1, 2):
            px[y] = [GOLD[2]] * w
    return px


def lapis_inlay(w, h, r, face):
    px = [[LAPIS[1]] * w for _ in range(h)]
    if face == "north" and h >= 4:
        for y in range(1, h - 1, 2):
            px[y] = [LAPIS[2]] * w
    return px


def shin(P, Q):
    def f(w, h, r, face):
        """Shin guard: plate of P with a frame of Q, horizontal bands of Q and a small square of Q between them."""
        px = [[P[1] if face != "down" else P[0]] * w for _ in range(h)]
        if face == "north":
            c = w // 2
            for y in range(h):
                px[y][0] = px[y][w - 1] = Q[0]
            for y in range(2, h - 1, 4):
                px[y] = [Q[0]] + [Q[1]] * (w - 2) + [Q[0]]
                if y + 2 < h - 1:
                    px[y + 2][c - 1] = px[y + 2][c] = Q[1]
            px[0] = px[h - 1] = [Q[0]] * w
        return px
    return f


def bracelet(w, h, r, face):
    """Bracelet: gold, a lapis row in the middle, darker edges."""
    if face in ("up", "down"):
        return [[GOLD[0 if face == "down" else 2]] * w for _ in range(h)]
    px = [[GOLD[1]] * w for _ in range(h)]
    if h >= 3:
        px[h // 2] = [LAPIS[1] if x % 3 else GOLD[2] for x in range(w)]
    px[h - 1] = [GOLD[0]] * w
    return px


def loincloth(w, h, r, face):
    """Front cloth of the shendyt: clay, parallel vertical folds, pale chevrons at the bottom."""
    px = [[CLAY[1 if face != "down" else 0]] * w for _ in range(h)]
    if face == "north":
        for x in range(1, w, 3):
            for y in range(h):
                px[y][x] = CLAY[0]
        for k in range(min(3, h)):
            y = h - 3 + k
            for x in range(w):
                if (x + k) % 4 == 0:
                    px[y][x] = PALE[0]
        px[h - 1] = [CLAY[0]] * w
    return px


def jaw(w, h, r, face):
    """Jaw: dark earth; open dark mouth on the upper front with two short pale fangs, nothing more."""
    px = [[EARTH[SHADE[face] if face != "up" else 0]] * w for _ in range(h)]
    if face == "north":
        for y in range(min(2, h)):
            px[y] = [EARTH[0]] * w
        for x in (w // 2 - 3, w // 2 + 2):
            if 0 <= x < w and h >= 2:
                px[0][x] = PALE[1]
                px[1][x] = PALE[0]
        px[h - 1] = [EARTH[1]] * w
    return px


def face_plate(w, h, r, face):
    """Middle of the face: ochre sandstone, nose ridge one shade lighter, a dark slit under it (upper lip)."""
    px = stone(OCHRE, cracks=0, joints=False)(w, h, r, face)
    if face == "north":
        c = w // 2
        for y in range(h - 2):
            px[y][c - 1] = px[y][c] = OCHRE[2]
        px[h - 1] = [EARTH[0]] * w
        px[h - 2][c - 2] = px[h - 2][c + 1] = EARTH[1]            # nostrils
    return px


def eye(w, h, r, face):
    """Eye (density 2, emissive): amber, a light narrow slit, darker rim."""
    if face != "north":
        return [[AMBER[0]] * w for _ in range(h)]
    px = [[AMBER[1]] * w for _ in range(h)]
    px[0] = [AMBER[0]] * w
    for x in range(1, w - 1):
        px[h // 2][x] = AMBER[2]
    return px


def fist(P):
    def f(w, h, r, face):
        """Mineral fist: four fingers split by dark grooves on the front and below, knuckles lighter on top."""
        px = flat(P)(w, h, r, face)
        if face in ("north", "down"):
            for x in (w // 4, w // 2, (3 * w) // 4):
                for y in range(h // 3 if face == "north" else 0, h):
                    px[y][x] = EARTH[1]
        if face == "north":
            px[h // 3] = [P[2] if x % 2 else P[1] for x in range(w)]
        if face == "up":
            for x in range(0, w, 2):
                px[0][x] = P[2]
        return px
    return f


def foot(P):
    def f(w, h, r, face):
        """Mineral sandal: sole line of dark earth, toes split by grooves at the front."""
        px = flat(P)(w, h, r, face)
        if face not in ("up", "down"):
            px[h - 1] = [EARTH[1]] * w
        if face == "north":
            for x in (w // 3, (2 * w) // 3):
                for y in range(1, h - 1):
                    px[y][x] = EARTH[1]
        if face == "up":
            for y in range(h // 2, h):
                px[y][w // 2] = GOLD[1]                            # sandal strap
        return px
    return f


MATS = {
    "sand": stone(SAND), "ochre": stone(OCHRE), "pale": stone(PALE, cracks=0.2), "clay": stone(CLAY, joints=False),
    "sand_flat": flat(SAND), "ochre_flat": flat(OCHRE), "pale_flat": flat(PALE), "clay_flat": flat(CLAY),
    "earth_flat": flat(EARTH), "nemes_lappet": nemes_lappet, "nemes_top": nemes_top, "sun": (sun, 2),
    "band": band, "collar": collar, "lapis_framed": (lapis_framed, 2), "gold_inlay": gold_inlay,
    "lapis_inlay": lapis_inlay, "shin_gold": shin(GOLD, LAPIS), "shin_lapis": shin(LAPIS, GOLD), "bracelet": bracelet,
    "loincloth": loincloth, "jaw": jaw, "face": face_plate, "fist_clay": fist(CLAY), "fist_sand": fist(SAND),
    "foot_ochre": foot(OCHRE), "foot_sand": foot(SAND),
}
GLOW_MATS = {"eye": (eye, 2)}


# --------------------------------------------------------------------------- skeleton (units, 16 = 1 block)
NECK = (0, 50.5 - LOWER, 0)
HEAD_R = rot(-7, 0, 0)                     # head slightly lowered: he looks down on the players
ARM_R = {1: rot(0, 0, 3), -1: rot(0, 0, -3)}     # arms a little away from the body
GROUPS = {
    "Root": ((0, 0, 0), None),
    "Body": ((0, 26 - LOWER, 0), "Root"),
    "Head": (NECK, "Body"),
    "Jaw": (attach(NECK, HEAD_R)((0, 52 - LOWER, -4.5), (0, 0, 0))[0], "Head"),
    "glow_eyes": (attach(NECK, HEAD_R)((0, 55.5 - LOWER, -7.3), (0, 0, 0))[0], "Head"),
    "Arm_right": ((17.5, 44 - LOWER, 0), "Body"),
    "Arm_left": ((-17.5, 44 - LOWER, 0), "Body"),
    "Forearm_right": ((18.2, 30 - LOWER, 0), "Arm_right"),
    "Forearm_left": ((-18.2, 30 - LOWER, 0), "Arm_left"),
    "Leg_right": ((7.7, 23, 0), "Root"),
    "Leg_left": ((-7.7, 23, 0), "Root"),
}


def parts():
    P = []

    def add(g, name, c, size, r, mat, lower=True):
        if lower:
            c = (c[0], c[1] - LOWER, c[2])
        P.append((g, name, c, size, r, mat))

    on_head = attach(NECK, HEAD_R)

    def hd(g, name, c, size, r, mat):
        cc, M = on_head((c[0], c[1] - LOWER, c[2]), r)
        P.append((g, name, cc, size, M, mat))

    def arm(s, g, name, c, size, r, mat):
        """Arm piece drawn straight, then the whole arm opened a little from the shoulder."""
        side = "Arm_right" if s > 0 else "Arm_left"
        pv = GROUPS[side][0]
        cc, M = attach(pv, ARM_R[s])((c[0], c[1] - LOWER, c[2]), r)
        P.append((g, name, cc, size, M, mat))

    # ---------------------------------------------------------------- A. legs and feet (temple pillars)
    for s, side, m_foot, m_leg, m_knee, m_shin in ((-1, "gauche", "foot_ochre", "sand", "pale_flat", "shin_gold"),
                                                    (1, "droit", "foot_sand", "ochre", "sand_flat", "shin_lapis")):
        g = "Leg_right" if s > 0 else "Leg_left"
        add(g, f"pied_{side}", (7.68 * s, 2.88, -2.4), (11.52, 5.76, 15.2), (0, 0, 0), m_foot, lower=False)
        add(g, f"jambe_{side}", (7.68 * s, 14.4, 0.32), (10.24, 18.0, 10.72), (0, 0, 2 * s), m_leg, lower=False)
        add(g, f"genou_{side}", (7.68 * s, 18.0, -5.0), (10.72, 5.12, 3.2), (0, 0, 2 * s), m_knee, lower=False)
        add(g, f"tibia_{side}", (7.68 * s, 10.0, -5.3), (7.68, 9.6, 1.28), (0, 0, 2 * s), m_shin, lower=False)

    # ---------------------------------------------------------------- B. pelvis and torso
    add("Body", "bassin", (0, 26.4, 0), (24.8, 7.68, 13.6), (0, 0, 0), "ochre")
    add("Body", "torse", (0, 36.8, 0), (28, 16.8, 15.2), (0, 0, 0), "sand")
    add("Body", "taille", (0, 31.0, 0), (25.0, 4.0, 14.4), (0, 0, 0), "clay_flat")        # clay joint at the waist
    add("Body", "poitrine", (0, 42.6, -7.7), (24, 7.2, 2.56), (-3, 0, 0), "pale")
    add("Body", "plaque_abdominale", (0, 33.6, -7.8), (17.92, 6.4, 2.4), (0, 0, 0), "clay")
    add("Body", "plaque_dorsale", (0, 37.6, 8.0), (23.2, 13.12, 2.4), (0, 0, 0), "ochre")
    add("Body", "ornement_poitrine", (0, 39.2, -9.3), (5.12, 7.2, 1.28), (0, 0, 0), "lapis_framed")
    add("Body", "ceinture", (0, 29.0, -7.6), (24.8, 3.2, 2.08), (0, 0, 0), "band")
    add("Body", "pagne", (0, 23.6, -7.4), (6.08, 12.8, 1.92), (0, 0, 0), "loincloth")
    add("Body", "ornement_pagne", (0, 28.9, -8.9), (2.88, 4.0, 0.96), (0, 0, 0), "gold_inlay")
    add("Body", "cou", (0, 47.7, 0), (9.28, 5.6, 9.28), (0, 0, 0), "clay_flat")
    add("Body", "collier", (0, 45.0, -9.4), (19.0, 4.2, 2.0), (-6, 0, 0), "collar")
    add("Body", "plaque_collier", (0, 43.6, -10.5), (6.4, 3.52, 1.28), (-6, 0, 0), "lapis_framed")

    # ---------------------------------------------------------------- C. shoulders and arms
    for s, side, m_sh, m_up, m_fore, m_hand, m_inl in ((-1, "gauche", "sand", "ochre", "sand", "fist_clay", "lapis_inlay"),
                                                       (1, "droite", "ochre", "sand", "ochre", "fist_sand", "gold_inlay")):
        ga = "Arm_right" if s > 0 else "Arm_left"
        gf = "Forearm_right" if s > 0 else "Forearm_left"
        add("Body", f"epaule_{side}", (16.8 * s, 43.5, 0), (11.52, 10.4, 12.16), (0, 0, 8 * s), m_sh)
        add("Body", f"epaulette_{side}", (16.9 * s, 45.9, -6.0), (8.8, 4.48, 2.4), (0, 0, 8 * s), "gold_inlay")
        arm(s, ga, f"bras_{side}", (17.8 * s, 34.4, 0), (8, 13.6, 8.8), (0, 0, 2 * s), m_up)
        arm(s, ga, f"incrustation_bras_{side}", (18.1 * s, 34.4, -4.6), (2.56, 4.8, 1.28), (0, 0, 2 * s), m_inl)
        arm(s, gf, f"coude_{side}", (18.2 * s, 28.4, 0), (7.2, 3.2, 7.6), (0, 0, 0), "clay_flat")
        arm(s, gf, f"avant_bras_{side}", (18.4 * s, 22.4, -0.9), (8.3, 10.4, 8.6), (7, 0, 0), m_fore)
        arm(s, gf, f"bracelet_{side}", (18.4 * s, 19.6, -1.3), (9.0, 2.9, 9.3), (7, 0, 0), "bracelet")
        arm(s, gf, f"main_{side}", (18.6 * s, 13.4, -2.4), (8.4, 7.68, 7.6), (9, 0, 0), m_hand)
        arm(s, gf, f"pouce_{side}", (15.4 * s, 15.0, -5.3), (2.4, 3.6, 2.6), (9, 0, 0), m_hand)

    # ---------------------------------------------------------------- D. demonic head (drawn straight, then lowered)
    hd("Head", "crane", (0, 54.1, -0.3), (14.72, 12.48, 13.12), (0, 0, 0), "sand")
    hd("Head", "tempes", (0, 56.4, 0.4), (16.4, 5.4, 11.0), (0, 0, 0), "sand_flat")      # wider at the temples
    hd("Head", "front", (0, 58.6, -6.6), (13.6, 2.6, 2.6), (-8, 0, 0), "pale_flat")      # massive upper face
    hd("Jaw", "machoire", (0, 50.2, -6.4), (12.16, 4.48, 3.52), (0, 0, 0), "jaw")
    hd("Head", "visage", (0, 53.0, -7.6), (9.6, 3.0, 2.6), (0, 0, 0), "face")
    hd("Head", "arcade_gauche", (-3.9, 57.0, -7.8), (6.4, 2.2, 2.4), (0, 0, -18), "earth_flat")
    hd("Head", "arcade_droite", (3.9, 57.0, -7.8), (6.4, 2.2, 2.4), (0, 0, 18), "earth_flat")
    hd("glow_eyes", "oeil_gauche", (-3.4, 55.5, -7.3), (2.8, 1.4, 0.9), (0, 0, -10), "eye")
    hd("glow_eyes", "oeil_droit", (3.4, 55.5, -7.3), (2.8, 1.4, 0.9), (0, 0, 10), "eye")

    # ---------------------------------------------------------------- E. nemes headdress
    hd("Head", "coiffe", (0, 61.4, 0), (18.4, 3.8, 15.2), (0, 0, 0), "nemes_top")
    hd("Head", "pan_gauche", (-9.0, 52.8, -0.6), (4.6, 15.2, 8.32), (0, 0, -5), "nemes_lappet")
    hd("Head", "pan_droit", (9.0, 52.8, -0.6), (4.6, 15.2, 8.32), (0, 0, 5), "nemes_lappet")
    hd("Head", "nemes_arriere", (0, 52.0, 7.3), (9.6, 11.0, 2.4), (8, 0, 0), "nemes_lappet")  # tail of the cloth
    hd("Head", "bande_frontale", (0, 59.6, -7.4), (16.8, 2.56, 1.92), (0, 0, 0), "band")
    hd("Head", "ornement_solaire", (0, 61.4, -8.4), (4.0, 3.68, 1.6), (0, 0, 0), "sun")

    # every foot rests exactly on the ground (y = 0)
    low = {}
    for g, n, c, size, r, m in P:
        if g.startswith("Leg_"):
            low[g] = min([low.get(g, 1e9)] + [q[1] for q in corners(c, size, r)])
    return [(g, n, [c[0], c[1] - low.get(g, 0), c[2]], size, r, m) for g, n, c, size, r, m in P]


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = None
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims = ground(parts(), GROUPS, scale=SCALE, center=False)
    uv, atlases, sizes = bake(P, MATS, GLOW_MATS)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    print(f"{len(P)} volumes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; "
          f"textures {sizes[0]} + {sizes[1]}")
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
    if prev:
        apercu(data, atlases, sizes, prev, set(AMBER), views=((30, 8), (0, 4), (90, 2), (210, 10)), size=420)
