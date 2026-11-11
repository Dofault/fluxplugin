#!/usr/bin/env python3
"""Sand Pharaoh (Pharaon des Sables): boss of the Deblayeur dungeon, STATIC Blockbench model (.bbmodel).

Third version (2026-11-11), rebuilt from scratch to the letter of the brief "Pharaon des Sables, specification de
modelisation 3D, recreation integrale": a standing, massive humanoid pharaoh made of compact sand, sandstone and
terracotta, like an old Egyptian statue come to life; nemes headdress, demonic face, statue proportions.

The 36 volumes of the brief are placed at the brief's own centres, sizes and rotations (blocks converted to units,
1 block = 16). Only local fixes, each marked "FIX" below: pieces that floated (legs under the pelvis, neck under the
torso, sun symbol, collar), eyes sunk under the brows, shoulders and arms brought in a little to stay inside the
2.55-block width. Added on top: head lowered by HEAD_PITCH (chin down, asked by the brief), feet turned out by FOOT_YAW.

Usage: pharaon_sables.py [output folder] [--apercu <sheet.png>]
Front faces north (-z), the model's right side is +x, ground at y = 0, origin between the feet.
"""
import json
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, export, ground, hx  # noqa: E402
from golem_fonderie import rot  # noqa: E402

IDENT = "pharaon_sables"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000050a1e7")
HEAD_PITCH = -4       # degrees, chin slightly down
FOOT_YAW = 4          # degrees, toes turned out (5 at most in the brief)


# --------------------------------------------------------------------------- brief palette, only these hues
SAND, SAND_LIGHT, OCHRE = hx("#D5AC70"), hx("#E7C58E"), hx("#B67C43")
CLAY, EARTH, GOLD, LAPIS, AMBER = hx("#98523A"), hx("#50352A"), hx("#D5A23F"), hx("#263F70"), hx("#FF9918")
# shades of the same hues (one step darker / lighter), for face variation and edges only
SAND_D, OCHRE_D, OCHRE_L = hx("#C49B62"), hx("#A06C39"), hx("#C48E56")
CLAY_D, EARTH_D, GOLD_D, GOLD_L = hx("#83452F"), hx("#3F2A21"), hx("#B88A33"), hx("#E6B85A")
LAPIS_D, LAPIS_L, AMBER_D, AMBER_L = hx("#1B2E54"), hx("#33528A"), hx("#D97A0C"), hx("#FFC66A")

# per material: (up, front, side, back, down, edge/joint) following the brief: top light, front main, sides a little
# darker, bottom terracotta.
STONES = {
    "sand": (SAND_LIGHT, SAND, SAND_D, SAND_D, CLAY, OCHRE_D),
    "sand_light": (SAND_LIGHT, SAND_LIGHT, SAND, SAND, CLAY, SAND_D),
    "ochre": (OCHRE_L, OCHRE, OCHRE_D, OCHRE_D, CLAY_D, CLAY_D),
    "clay": (CLAY, CLAY, CLAY_D, CLAY_D, EARTH, EARTH),
    "earth": (EARTH, EARTH, EARTH_D, EARTH_D, EARTH_D, EARTH_D),
}
FACE_INDEX = {"up": 0, "north": 1, "east": 2, "west": 2, "south": 3, "down": 4}


# --------------------------------------------------------------------------- painters
def stone(kind, joints=True):
    def f(w, h, r, face):
        """Cut sandstone: flat colour per face, a few short tool marks, a stepped joint between two stones on the
        large faces, a lighter top edge. No cracks (compact, solid sand)."""
        cols = STONES[kind]
        base, edge = cols[FACE_INDEX[face]], cols[5]
        px = [[base] * w for _ in range(h)]
        for _ in range(max(1, w * h // 28)):
            x, y = r.randrange(w), r.randrange(h)
            for k in range(r.randrange(1, 3)):
                if x + k < w:
                    px[y][x + k] = edge if face != "down" else cols[4]
        if face not in ("up", "down") and h >= 4:
            px[0] = [cols[0]] * w                                   # light top edge
            px[h - 1] = [edge] * w                                  # dark bottom edge
        if joints and face in ("north", "east", "west", "south") and w >= 9 and h >= 9:
            y = r.randrange(3, h - 3)
            px[y] = [edge] * w
            x = r.randrange(2, w - 2)
            for yy in range(y + 1, h):
                px[yy][x] = edge
        return px
    return f


def nemes_lappet(w, h, r, face):
    """Nemes lappet: one large lapis shape, geometric gold lines every 4 pixels and a gold edge at the bottom."""
    if face in ("up", "down"):
        return [[LAPIS if face == "up" else LAPIS_D] * w for _ in range(h)]
    px = [[LAPIS] * w for _ in range(h)]
    for y in range(h):
        if y % 4 == 2:
            px[y] = [GOLD] * w
    px[h - 1] = [GOLD_D] * w
    if face in ("north", "south"):
        for y in range(h):
            px[y][0] = px[y][w - 1] = LAPIS_D
    return px


def nemes_top(w, h, r, face):
    """Top of the nemes: lapis, gold lines front to back on the top and around the sides."""
    if face == "down":
        return [[LAPIS_D] * w for _ in range(h)]
    px = [[LAPIS_L if face == "up" else LAPIS] * w for _ in range(h)]
    if face == "up":
        for y in range(h):
            for x in range(2, w, 4):
                px[y][x] = GOLD
    else:
        for y in range(h):
            if y % 4 == 2:
                px[y] = [GOLD] * w
        px[h - 1] = [LAPIS_D] * w
    return px


def gold_band(w, h, r, face):
    """Headband: matt gold, small lapis squares in the middle row, darker lower edge."""
    px = [[GOLD_L if face == "up" else GOLD_D if face == "down" else GOLD] * w for _ in range(h)]
    if face == "north" and h >= 3:
        for x in range(1, w - 1, 3):
            px[h // 2][x] = LAPIS
        px[h - 1] = [GOLD_D] * w
    return px


def sun(w, h, r, face):
    """Front symbol (density 2): clay sun disc ringed with gold, small rays on top."""
    if face != "north":
        return [[GOLD_D] * w for _ in range(h)]
    px = [[GOLD] * w for _ in range(h)]
    cx, cy = (w - 1) / 2, (h - 1) / 2 + 0.5
    for y in range(h):
        for x in range(w):
            d = max(abs(x - cx), abs(y - cy))
            if d <= 1.5:
                px[y][x] = CLAY
            elif d <= 2.5:
                px[y][x] = GOLD_L
            if x in (0, w - 1) or y == h - 1:
                px[y][x] = GOLD_D
    for x in range(1, w - 1, 2):
        px[0][x] = GOLD_L
    return px


def collar(w, h, r, face):
    """Pharaoh collar (density 2): horizontal geometric bands, gold and lapis, a row of clay beads."""
    if face in ("up", "down"):
        return [[GOLD if face == "up" else GOLD_D] * w for _ in range(h)]
    rows = [GOLD_L, GOLD, LAPIS, GOLD, CLAY, GOLD, LAPIS_D]
    px = []
    for y in range(h):
        c = rows[min(y * len(rows) // h, len(rows) - 1)]
        px.append([CLAY if x % 2 else GOLD for x in range(w)] if c == CLAY else [c] * w)
    return px


def lapis_band(w, h, r, face):
    """Blue band of the collar (density 2): lapis, small gold squares."""
    px = [[LAPIS] * w for _ in range(h)]
    if face == "north":
        for x in range(1, w - 1, 3):
            px[h // 2][x] = GOLD
    return px


def bracelet(w, h, r, face):
    """Bracelet (density 2), inlaid in the stone: gold, one lapis line, darker edges."""
    px = [[GOLD] * w for _ in range(h)]
    if face == "north" and h >= 3:
        px[h // 2] = [LAPIS if x % 3 else GOLD_L for x in range(w)]
        px[0] = [GOLD_L] * w
        px[h - 1] = [GOLD_D] * w
    return px


def abdomen(w, h, r, face):
    """Clay belly plate: two horizontal grooves and a middle line (statue abdomen), darker than the chest."""
    px = stone("clay", joints=False)(w, h, r, face)
    if face == "north":
        for y in (h // 3, (2 * h) // 3):
            px[y] = [CLAY_D] * w
        for y in range(h):
            px[y][w // 2] = CLAY_D
    return px


def jaw(w, h, r, face):
    """Jaw: dark earth, the open mouth darker across the upper front, a strong chin edge."""
    px = [[EARTH if face not in ("down", "south") else EARTH_D] * w for _ in range(h)]
    if face == "north":
        for y in range(max(1, h // 2)):
            px[y] = [EARTH_D] * w
        px[h - 1] = [OCHRE_D] * w                                   # chin edge
    return px


def face_mid(w, h, r, face):
    """Middle of the face (density 2): ochre, nose ridge lighter, nostrils dark, upper lip line dark."""
    px = [[OCHRE if face != "up" else OCHRE_L] * w for _ in range(h)]
    if face == "north":
        c = w // 2
        for y in range(h - 1):
            px[y][c - 1] = px[y][c] = OCHRE_L
        px[h - 1] = [EARTH] * w
        if h >= 3:
            px[h - 2][c - 2] = px[h - 2][c + 1] = EARTH
    return px


def eye(w, h, r, face):
    """Eye (density 4, emissive): narrow amber slit, light core, darker rim."""
    if face != "north":
        return [[AMBER_D] * w for _ in range(h)]
    px = [[AMBER] * w for _ in range(h)]
    px[0] = [AMBER_D] * w
    px[h - 1] = [AMBER_D] * w
    for x in range(1, w - 1):
        px[h // 2][x] = AMBER_L
    return px


def fist(kind):
    def f(w, h, r, face):
        """Mineral fist, half closed: three grooves split four fingers on the front and below, knuckle row across
        the fingers, thumb line on the sides."""
        cols = STONES[kind]
        px = [[cols[FACE_INDEX[face]]] * w for _ in range(h)]
        if face in ("north", "down"):
            for x in (w // 4, w // 2, (3 * w) // 4):
                for y in range(h // 3 if face == "north" else 0, h):
                    px[y][x] = cols[5]
        if face == "north":
            px[h // 3] = [cols[0] if x % 2 else cols[5] for x in range(w)]
        if face in ("east", "west"):
            for y in range(h // 3, h - 1):
                px[y][1] = cols[5]
        return px
    return f


def foot(kind):
    def f(w, h, r, face):
        """Flat mineral foot: dark sole line, two toe grooves at the front."""
        cols = STONES[kind]
        px = [[cols[FACE_INDEX[face]]] * w for _ in range(h)]
        if face not in ("up", "down"):
            px[h - 1] = [EARTH] * w
        if face == "north":
            for x in (w // 3, (2 * w) // 3):
                for y in range(1, h - 1):
                    px[y][x] = cols[5]
        return px
    return f


MATS = {
    "sand": stone("sand"), "sand_light": stone("sand_light"), "ochre": stone("ochre"),
    "earth": stone("earth", joints=False), "ochre_plain": stone("ochre", joints=False),
    "nemes_lappet": nemes_lappet, "nemes_top": nemes_top, "gold_band": gold_band, "sun": (sun, 2),
    "collar": (collar, 2), "lapis_band": (lapis_band, 2), "bracelet": (bracelet, 2), "abdomen": abdomen,
    "jaw": jaw, "face": (face_mid, 2), "fist_earth": fist("earth"), "fist_sand": fist("sand"),
    "foot_ochre": foot("ochre"), "foot_sand": foot("sand"),
}
GLOW_MATS = {"eye": (eye, 4)}


# --------------------------------------------------------------------------- skeleton (units, 16 = 1 block)
def u(*v):
    """Brief values in blocks to units."""
    return tuple(round(x * 16, 3) for x in v)


NECK = u(0, 2.65, 0)
HEAD_R = rot(HEAD_PITCH, 0, 0)
GROUPS = {
    "Root": ((0, 0, 0), None),
    "Body": (u(0, 1.38, 0), "Root"),
    "Head": (NECK, "Body"),
    "Jaw": (attach(NECK, HEAD_R)(u(0, 2.86, -0.26), (0, 0, 0))[0], "Head"),
    "glow_eyes": (attach(NECK, HEAD_R)(u(0, 3.06, -0.44), (0, 0, 0))[0], "Head"),
    "Arm_right": (u(0.95, 2.30, 0), "Body"),
    "Arm_left": (u(-0.95, 2.30, 0), "Body"),
    "Forearm_right": (u(1.0, 1.52, 0), "Arm_right"),
    "Forearm_left": (u(-1.0, 1.52, 0), "Arm_left"),
    "Leg_right": (u(0.48, 1.15, 0), "Root"),
    "Leg_left": (u(-0.48, 1.15, 0), "Root"),
}


def parts():
    P = []

    def add(g, name, c, size, r, mat):
        """Brief volume: centre and size in blocks, rotation (x, y, z) in degrees."""
        P.append((g, name, u(*c), u(*size), r, mat))

    on_head = attach(NECK, HEAD_R)

    def hd(g, name, c, size, r, mat):
        cc, M = on_head(u(*c), r)
        P.append((g, name, cc, u(*size), M, mat))

    # ---------------------------------------------------------------- A. feet and legs
    add("Leg_left", "pied_gauche", (-0.48, 0.16, -0.12), (0.58, 0.32, 0.72), (0, FOOT_YAW, 0), "foot_ochre")
    add("Leg_right", "pied_droit", (0.48, 0.16, -0.12), (0.58, 0.32, 0.72), (0, -FOOT_YAW, 0), "foot_sand")
    # FIX: legs reach up into the pelvis (brief: top at 1.07, pelvis from 1.18, they floated); same width and depth
    add("Leg_left", "jambe_gauche", (-0.48, 0.725, 0.03), (0.55, 0.87, 0.62), (0, 0, -2), "sand")
    add("Leg_right", "jambe_droite", (0.48, 0.725, 0.03), (0.55, 0.87, 0.62), (0, 0, 2), "ochre")
    add("Leg_left", "genou_gauche", (-0.48, 0.92, -0.30), (0.50, 0.24, 0.10), (0, 0, -3), "sand_light")
    add("Leg_right", "genou_droit", (0.48, 0.92, -0.30), (0.50, 0.24, 0.10), (0, 0, 3), "ochre_plain")

    # ---------------------------------------------------------------- B. pelvis and torso
    add("Body", "bassin", (0, 1.38, 0), (1.32, 0.40, 0.78), (0, 0, 0), "ochre")
    add("Body", "torse", (0, 2.02, 0), (1.48, 0.94, 0.82), (0, 0, 0), "sand")
    add("Body", "poitrine", (0, 2.38, -0.40), (1.30, 0.34, 0.12), (-3, 0, 0), "sand_light")
    add("Body", "plaque_abdominale", (0, 1.87, -0.43), (1.00, 0.40, 0.12), (0, 0, 0), "abdomen")
    add("Body", "plaque_dorsale", (0, 2.03, 0.43), (1.25, 0.70, 0.12), (0, 0, 0), "ochre_plain")

    # ---------------------------------------------------------------- C. shoulders and arms
    # FIX: shoulders and arms 0.035 block closer to the body, else the brief's volumes are 2.61 blocks wide (max 2.55)
    add("Body", "epaule_gauche", (-0.875, 2.42, 0), (0.72, 0.65, 0.76), (0, 0, -7), "sand")
    add("Body", "epaule_droite", (0.875, 2.42, 0), (0.72, 0.65, 0.76), (0, 0, 7), "ochre")
    add("Arm_left", "bras_gauche", (-0.985, 1.90, 0), (0.48, 0.82, 0.55), (0, 0, -4), "ochre")
    add("Arm_right", "bras_droit", (0.985, 1.90, 0), (0.48, 0.82, 0.55), (0, 0, 4), "sand")
    add("Forearm_left", "avant_bras_gauche", (-1.015, 1.30, -0.03), (0.44, 0.55, 0.50), (0, 0, -2), "sand")
    add("Forearm_right", "avant_bras_droit", (1.015, 1.30, -0.03), (0.44, 0.55, 0.50), (0, 0, 2), "ochre")
    # FIX: hands 0.02 higher so they hold to the forearms (they only touched them)
    add("Forearm_left", "main_gauche", (-1.015, 0.85, -0.08), (0.42, 0.40, 0.38), (0, 0, -3), "fist_earth")
    add("Forearm_right", "main_droite", (1.015, 0.85, -0.08), (0.42, 0.40, 0.38), (0, 0, 3), "fist_sand")

    # ---------------------------------------------------------------- D. neck and head
    # FIX: neck goes down into the torso (brief: bottom at 2.50, torso top at 2.49)
    add("Body", "cou", (0, 2.63, 0), (0.55, 0.34, 0.55), (0, 0, 0), "earth")
    hd("Head", "crane", (0, 3.00, -0.02), (0.88, 0.70, 0.66), (0, 0, 0), "sand")
    hd("Jaw", "machoire", (0, 2.80, -0.32), (0.72, 0.24, 0.16), (0, 0, 0), "jaw")
    hd("Head", "visage", (0, 3.04, -0.36), (0.62, 0.25, 0.12), (0, 0, 0), "face")
    hd("Head", "arcade_gauche", (-0.23, 3.17, -0.43), (0.40, 0.13, 0.12), (0, 0, -18), "earth")
    hd("Head", "arcade_droite", (0.23, 3.17, -0.43), (0.40, 0.13, 0.12), (0, 0, 18), "earth")
    # FIX: eyes 0.02 back, so the brows overhang them (sunk under the brows, as asked)
    hd("glow_eyes", "oeil_gauche", (-0.20, 3.06, -0.44), (0.13, 0.075, 0.05), (0, 0, -12), "eye")
    hd("glow_eyes", "oeil_droit", (0.20, 3.06, -0.44), (0.13, 0.075, 0.05), (0, 0, 12), "eye")

    # ---------------------------------------------------------------- E. nemes headdress
    hd("Head", "coiffe", (0, 3.50, 0), (1.12, 0.40, 0.82), (0, 0, 0), "nemes_top")
    hd("Head", "pan_gauche", (-0.53, 3.05, -0.03), (0.25, 0.85, 0.48), (0, 0, -4), "nemes_lappet")
    hd("Head", "pan_droit", (0.53, 3.05, -0.03), (0.25, 0.85, 0.48), (0, 0, 4), "nemes_lappet")
    hd("Head", "bande_frontale", (0, 3.39, -0.43), (1.00, 0.12, 0.08), (0, 0, 0), "gold_band")
    # FIX: symbol 0.03 back, against the headdress (it floated 0.02 in front of it)
    hd("Head", "symbole_frontal", (0, 3.53, -0.44), (0.20, 0.20, 0.08), (0, 0, 0), "sun")

    # ---------------------------------------------------------------- F. royal ornaments
    # FIX: collar as deep as the gap to the neck (0.10 deep at -0.43 it floated in front of the neck)
    add("Body", "collier", (0, 2.70, -0.365), (1.12, 0.20, 0.23), (0, 0, 0), "collar")
    add("Body", "bande_bleue", (0, 2.66, -0.50), (0.78, 0.09, 0.05), (0, 0, 0), "lapis_band")
    add("Forearm_left", "bracelet_gauche", (-1.015, 1.43, -0.28), (0.48, 0.15, 0.08), (0, 0, -2), "bracelet")
    add("Forearm_right", "bracelet_droit", (1.015, 1.43, -0.28), (0.48, 0.15, 0.08), (0, 0, 2), "bracelet")
    return P


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = None
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims = ground(parts(), GROUPS, center=False)
    uv, atlases, sizes = bake(P, MATS, GLOW_MATS)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    print(f"{len(P)} volumes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; "
          f"textures {sizes[0]} + {sizes[1]}")
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
    if prev:
        apercu(data, atlases, sizes, prev, {AMBER, AMBER_D, AMBER_L}, views=((30, 6), (0, 3), (90, 2), (210, 8)),
               size=420)
