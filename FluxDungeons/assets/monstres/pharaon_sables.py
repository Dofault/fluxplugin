#!/usr/bin/env python3
"""Sand Pharaoh (Pharaon des Sables): boss of the Deblayeur dungeon, STATIC Blockbench model (.bbmodel), 2026-11-11.

Quadruped creature carved out of the remains of a desert temple and brought to life by a buried energy: light and
ochre sandstone, red clay, dark earth, a few gold and lapis-lazuli inlays, glowing amber eyes. Low stance, massive
shoulders, an oversized demonic head slightly raised, a pharaoh headdress (nemes) built into the skull, a long
mineral tail of broken blocks raised backwards.

Built from the brief "Pharaon des Sables (forme quadrupede)": positions and sizes of the brief converted to units
(1 block = 16), then fixed where the brief left a piece floating (eyes, headdress), hid a piece inside another (teeth)
or went past the 4-block box (tail raised steeper).

Usage: pharaon_sables.py [output folder] [--apercu <sheet.png>]
Front faces north (-z), the monster's right side is +x, ground at y = 0, centred in x and z on its bounding box.
"""
import json
import math
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, corners, export, ground, hx  # noqa: E402
from golem_fonderie import rot  # noqa: E402

IDENT = "pharaon_sables"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000050a1e5")
SCALE = 1.0


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- brief palette (dark, base, light)
SAND = pal("#B8915A", "#D6AD70", "#E6C48C")        # grès clair
OCHRE = pal("#99652F", "#B77C3F", "#CB9257")       # grès ocre
CLAY = pal("#773B27", "#914B32", "#A85E42")        # argile rouge
EARTH = pal("#3F281C", "#63402E", "#7A543F")       # terre sombre
GOLD = pal("#A97F2C", "#D6A441", "#EDC468")        # or antique
LAPIS = pal("#18294D", "#263F70", "#3A5A96")       # lapis-lazuli
AMBER = pal("#D97A0C", "#FF9B19", "#FFC66A")       # ambre lumineux (yeux)
IVORY = pal("#C9B488", "#E8D3A8")
CRACK = hx("#FF9B19")

SHADE = {"up": 2, "north": 1, "south": 1, "east": 1, "west": 1, "down": 0}


# --------------------------------------------------------------------------- painters
def stone(P, cracks=0.0, energy=0.0, joints=True):
    def f(w, h, r, face):
        """Cut sandstone block: flat colour (lighter top, darker bottom), darker lower edge, 1 to 3 pixel horizontal
        tool marks, stepped dark-earth joints and, rarely, an amber energy crack."""
        base = P[SHADE[face]]
        dark = P[max(0, SHADE[face] - 1)]
        px = [[base] * w for _ in range(h)]
        for _ in range(max(1, w * h // 20)):                      # tool marks, never long lines
            x, y = r.randrange(w), r.randrange(h)
            for k in range(r.randrange(1, 4)):
                if x + k < w:
                    px[y][x + k] = dark if r.random() < 0.75 else P[min(2, SHADE[face] + 1)]
        if face not in ("up", "down") and h >= 4:
            px[h - 1] = [dark] * w                                 # darker lower edge
            if face != "south" and r.random() < 0.6:
                px[0] = [P[2] if (x + r.randrange(3)) % 3 else base for x in range(w)]
        if joints and face != "down" and w >= 8 and h >= 6 and r.random() < 0.7:
            y = r.randrange(2, h - 2)                              # joint between two stone blocks, stepped
            px[y] = [EARTH[1]] * w
            x = r.randrange(2, w - 2)
            for yy in range(y + 1, h):
                px[yy][x] = EARTH[1]
        if r.random() < cracks and w >= 5 and h >= 5:              # square dark-earth crack
            x, y = r.randrange(1, w - 2), r.randrange(1, h - 2)
            for k in range(r.randrange(2, 5)):
                yy, xx = min(h - 1, y + k), min(w - 1, x + (k // 2))
                px[yy][xx] = EARTH[0]
        if face in ("east", "west", "north", "up") and r.random() < energy and w >= 6 and h >= 5:
            x, y = r.randrange(2, w - 2), r.randrange(1, h - 2)    # energy crack: amber zigzag
            for k in range(r.randrange(3, 6)):
                yy, xx = min(h - 1, y + k // 2), min(w - 1, max(0, x + (k % 2) - k // 3))
                px[yy][xx] = CRACK
        return px
    return f


def nemes(w, h, r, face):
    """Nemes lappet: lapis-lazuli striped with light sandstone every 3 pixels, plain top."""
    if face in ("up", "down"):
        return [[LAPIS[1 if face == "up" else 0]] * w for _ in range(h)]
    px = [[LAPIS[1]] * w for _ in range(h)]
    for y in range(h):
        if y % 3 == 2:
            px[y] = [SAND[1]] * w
    px[0] = [LAPIS[2]] * w
    return px


def headcloth(w, h, r, face):
    """Top of the headdress: ochre sandstone, thin lapis stripes front to back, lapis front edge."""
    px = stone(OCHRE, joints=False)(w, h, r, face)
    if face == "up":
        for y in range(h):
            for x in range(1, w, 4):
                px[y][x] = LAPIS[1]
    elif face == "north":
        px[h - 1] = [LAPIS[1]] * w
    elif face in ("east", "west"):
        for y in range(h):
            for x in range(1, w, 4):
                px[y][x] = LAPIS[1]
    return px


def sun(w, h, r, face):
    """Sun symbol (density 2): red clay disc ringed with gold, stepped rays, dark gold on the sides."""
    if face != "north":
        return [[GOLD[0]] * w for _ in range(h)]
    px = [[GOLD[1]] * w for _ in range(h)]
    cx, cy = (w - 1) / 2, (h - 1) / 2 + 1
    for y in range(h):
        for x in range(w):
            d = max(abs(x - cx), abs(y - cy) * 1.1)
            if d <= 1.6:
                px[y][x] = CLAY[1]
            elif d <= 2.6:
                px[y][x] = GOLD[2]
            if y == 0 or x in (0, w - 1):
                px[y][x] = GOLD[0]
    for x in range(1, w - 1, 2):                                  # rays on top
        px[1][x] = GOLD[2]
    return px


def chest(w, h, r, face):
    """Chest plate: light sandstone, a geometric winged sun on the front (stepped lapis wings, gold disc)."""
    px = stone(SAND, joints=False)(w, h, r, face)
    if face != "north":
        return px
    cx, cy = w // 2, h // 2 - 1
    for k in range(w // 2 - 1):                                    # wings: shorter row after row
        for row in range(3):
            if k < w // 2 - 1 - row * 2:
                for x in (cx - 1 - k, cx + k):
                    if 0 <= x < w and 0 <= cy + row < h:
                        px[cy + row][x] = LAPIS[1] if row != 1 else LAPIS[2]
    for y in range(cy - 2, cy + 1):                                # sun disc, above the wings
        for x in (cx - 1, cx):
            if 0 <= y < h:
                px[y][x] = GOLD[1]
    if cy - 3 >= 0:
        px[cy - 3][cx - 1] = px[cy - 3][cx] = GOLD[2]
    for x in range(1, w - 1):                                      # bottom border
        px[h - 2][x] = LAPIS[0] if x % 2 else GOLD[0]
    return px


def collar(w, h, r, face):
    """Pharaoh collar (usekh): gold and lapis bands, a row of clay beads."""
    rows = [GOLD[1], LAPIS[1], CLAY[1], LAPIS[1], GOLD[0], LAPIS[0]]
    if face == "up":
        return [[LAPIS[1] if (y % 2) else GOLD[1] for _ in range(w)] for y in range(h)]
    if face == "down":
        return [[EARTH[1]] * w for _ in range(h)]
    px = [[rows[min(y, len(rows) - 1)]] * w for y in range(h)]
    if h >= 3:
        px[2] = [CLAY[1] if x % 2 else GOLD[1] for x in range(w)]
    return px


def bracelet(w, h, r, face):
    """Bracelet: gold, a lapis row in the middle, darker edges."""
    if face in ("up", "down"):
        return [[GOLD[0] if face == "down" else GOLD[2]] * w for _ in range(h)]
    px = [[GOLD[1]] * w for _ in range(h)]
    px[h // 2] = [LAPIS[1] if x % 3 else GOLD[2] for x in range(w)]
    px[h - 1] = [GOLD[0]] * w
    return px


def inlay(colors):
    def f(w, h, r, face):
        """Inlay in the stone: flat colour, frame one shade darker."""
        px = [[colors[1]] * w for _ in range(h)]
        for y in range(h):
            for x in range(w):
                if (x in (0, w - 1) or y in (0, h - 1)) and w >= 3 and h >= 3:
                    px[y][x] = colors[0]
        if face == "up" and w >= 3 and h >= 3:
            px[h // 2][w // 2] = colors[2]
        return px
    return f


def jaw(w, h, r, face):
    """Jaw: dark earth; top and upper front darker (open mouth)."""
    px = stone(EARTH, joints=False)(w, h, r, face)
    if face == "up":
        return [[EARTH[0]] * w for _ in range(h)]
    if face == "north":
        px[0] = [EARTH[0]] * w
    return px


def teeth(w, h, r, face):
    """Mineral teeth (density 2): sand ivory, uneven row, dark-earth gaps."""
    if face != "north":
        return [[IVORY[0]] * w for _ in range(h)]
    px = [[IVORY[1]] * w for _ in range(h)]
    x = 0
    while x < w:
        tw = r.choice((2, 2, 3))
        short = r.random() < 0.35
        for y in range(h):
            for k in range(tw):
                if x + k < w and short and y >= h - 1:
                    px[y][x + k] = EARTH[0]
        if x + tw < w:
            for y in range(h):
                px[y][x + tw] = EARTH[0]
        x += tw + 1
    return px


def eye(w, h, r, face):
    """Eye (density 2, emissive texture): amber, light horizontal slit, darker edges."""
    if face != "north":
        return [[AMBER[0]] * w for _ in range(h)]
    px = [[AMBER[1]] * w for _ in range(h)]
    for x in range(w):
        px[0][x] = AMBER[0]
    mid = h // 2
    for x in range(1, w - 1):
        px[mid][x] = AMBER[2]
    return px


def foot(P):
    def f(w, h, r, face):
        """Heavy foot: sandstone, toes split by two dark-earth grooves on the front and top."""
        px = stone(P, joints=False)(w, h, r, face)
        cols = [w // 3, (2 * w) // 3]
        if face == "north":
            for c in cols:
                for y in range(1, h):
                    px[y][c] = EARTH[1]
        elif face == "up":
            for c in cols:
                for y in range(max(0, h - 4), h):
                    px[y][c] = EARTH[1]
        return px
    return f


def tail_tip(w, h, r, face):
    """Tail tip: dark earth, two gold lines at the base."""
    px = stone(EARTH, joints=False)(w, h, r, face)
    if face in ("up", "east", "west", "down"):
        for y in range(h):
            for x in range(w):
                if (face in ("up", "down") and y in (h - 1, h - 3)) or (face in ("east", "west") and x in (w - 1, w - 3)):
                    px[y][x] = GOLD[1]
    return px


MATS = {
    "sand": stone(SAND, cracks=0.35, energy=0.12), "ochre": stone(OCHRE, cracks=0.35, energy=0.12),
    "sand_plain": stone(SAND, joints=False), "ochre_plain": stone(OCHRE, joints=False),
    "clay": stone(CLAY, cracks=0.25), "earth": stone(EARTH, joints=False),
    "nemes": nemes, "headcloth": headcloth, "sun": (sun, 2), "chest": chest, "collar": (collar, 1),
    "bracelet": bracelet, "lapis": inlay(LAPIS), "gold": inlay(GOLD), "jaw": jaw, "teeth": (teeth, 2),
    "foot_clay": foot(CLAY), "foot_ochre": foot(OCHRE), "tail_tip": tail_tip,
}
GLOW_MATS = {"eye": (eye, 2)}


# --------------------------------------------------------------------------- skeleton (units, 16 = 1 block)
NECK = (0, 40, -12)
HEAD_R = rot(6, 0, 0)                     # head slightly raised, looking straight ahead
TAIL_ROOT = (0, 31, 17)
GROUPS = {
    "Root": ((0, 0, 0), None),
    "Body": ((0, 30, 2), "Root"),
    "Head": (NECK, "Body"),
    "Jaw": (attach(NECK, HEAD_R)((0, 42, -19), (0, 0, 0))[0], "Head"),
    "glow_eyes": (attach(NECK, HEAD_R)((0, 49.4, -25.5), (0, 0, 0))[0], "Head"),
    "Tail": (TAIL_ROOT, "Body"),
    "Tail_tip": ((0, 44, 26.6), "Tail"),          # end of queue_segment_3, see tail_chain
    "Leg_front_right": ((14.5, 27, -11.5), "Root"),
    "Leg_front_left": ((-14.5, 27, -11.5), "Root"),
    "Leg_back_right": ((14, 27, 14.4), "Root"),
    "Leg_back_left": ((-14, 27, 14.4), "Root"),
}

# Tail: (bone, name, size (w, h, length), upward slope in degrees, roll, material), from base to tip. Each block
# starts at the end of the previous one (2 units overlap): it rises almost upright, then the tip bends back and down.
# Steeper than in the brief so the model fits in 4 blocks.
TAIL_SEGMENTS = (
    ("Tail", "queue_base", (6.7, 6.7, 8.8), 32, 0, "ochre_plain"),
    ("Tail", "queue_segment_2", (5.2, 5.2, 7.7), 58, 3, "sand_plain"),
    ("Tail", "queue_segment_3", (4.6, 4.6, 6.7), 80, -2, "clay"),
    ("Tail_tip", "queue_segment_4", (4.0, 4.0, 5.6), 62, 4, "ochre_plain"),
    ("Tail_tip", "queue_pointe", (6.4, 4.4, 5.6), 10, 0, "tail_tip"),
)


def tail_chain(overlap=2.0):
    out, (y, z) = [], (TAIL_ROOT[1], TAIL_ROOT[2])
    for g, name, size, pitch, roll, mat in TAIL_SEGMENTS:
        a = math.radians(pitch)
        dy, dz = math.sin(a), math.cos(a)
        L = size[2]
        cy, cz = y + dy * L / 2, z + dz * L / 2
        out.append((g, name, (0, cy, cz), size, (-pitch, 0, roll), mat))
        y, z = y + dy * (L - overlap), z + dz * (L - overlap)
    return out


def parts():
    P = []

    def add(g, name, c, size, r, mat):
        P.append((g, name, c, size, r, mat))

    on_head = attach(NECK, HEAD_R)

    def hd(g, name, c, size, r, mat):
        cc, M = on_head(c, r)
        add(g, name, cc, size, M, mat)

    # ---------------------------------------------------------------- A. body (slightly trapezoid)
    add("Body", "torse", (0, 29.6, 2.4), (31.2, 18.4, 34.4), (0, 0, 0), "ochre")
    add("Body", "plaque_dorsale", (0, 40.2, 3.5), (26.4, 6, 24.8), (3, 0, 0), "sand")       # higher towards the front
    add("Body", "plaque_abdominale", (0, 20.6, -0.8), (21.6, 7.7, 21.6), (0, 0, 0), "clay")
    add("Body", "flanc_gauche", (-14.7, 31.2, 1.6), (5.6, 13.6, 21.6), (0, -5, -7), "sand")
    add("Body", "flanc_droit", (14.7, 31.2, 1.6), (5.6, 13.6, 21.6), (0, 5, 7), "ochre")
    add("Body", "plaque_poitrine", (0, 31.2, -15.6), (18.4, 13.6, 3.5), (0, 0, 0), "chest")

    # ---------------------------------------------------------------- B. massive shoulders
    add("Body", "epaule_gauche", (-16.8, 37.2, -8), (12, 13.6, 13.6), (0, 0, -12), "sand")
    add("Body", "epaule_droite", (16.8, 37.2, -8), (12, 13.6, 13.6), (0, 0, 12), "ochre")
    add("Body", "plaque_epaule_gauche", (-17.9, 44.2, -8.8), (11.5, 4.5, 10.4), (-12, 0, -8), "clay")
    add("Body", "plaque_epaule_droite", (17.9, 44.2, -8.8), (11.5, 4.5, 10.4), (-12, 0, 8), "sand")

    # ---------------------------------------------------------------- F. body ornaments
    add("Body", "collier", (0, 40.6, -13.4), (21.6, 4.8, 3.6), (0, 0, 0), "collar")
    add("Body", "incrustation_gauche", (-9.9, 43.1, 1.6), (2.9, 4.5, 6.7), (0, 0, -15), "lapis")
    add("Body", "incrustation_droite", (9.9, 43.1, 1.6), (2.9, 4.5, 6.7), (0, 0, 15), "gold")

    # ---------------------------------------------------------------- D. demonic head (drawn straight, then raised)
    hd("Head", "crane", (0, 47.2, -17.9), (21.6, 16.8, 15.2), (0, 0, 0), "sand")
    hd("Jaw", "machoire", (0, 39.6, -23.6), (16.8, 5.2, 10.4), (0, 0, 0), "jaw")
    hd("Head", "dents", (0, 42.6, -28.2), (10.4, 1.9, 1.6), (0, 0, 0), "teeth")
    hd("Head", "museau", (0, 44.9, -26.7), (12.4, 5.4, 6.1), (0, 0, 0), "ochre_plain")
    hd("Head", "front", (0, 55.0, -24.4), (15.2, 4.4, 4.4), (-8, 0, 0), "sand_plain")
    hd("Head", "arcade_gauche", (-6.0, 52.0, -26.3), (8, 2.6, 3.6), (0, 0, -20), "earth")
    hd("Head", "arcade_droite", (6.0, 52.0, -26.3), (8, 2.6, 3.6), (0, 0, 20), "earth")
    hd("glow_eyes", "oeil_gauche", (-5.2, 49.4, -25.75), (3.8, 2.2, 0.8), (0, -12, 0), "eye")
    hd("glow_eyes", "oeil_droit", (5.2, 49.4, -25.75), (3.8, 2.2, 0.8), (0, 12, 0), "eye")

    # ---------------------------------------------------------------- E. pharaoh headdress (nemes)
    hd("Head", "coiffe", (0, 57.3, -16.9), (23.2, 5.6, 16.2), (0, 0, 0), "headcloth")
    hd("Head", "nemes_gauche", (-12.3, 50.4, -16), (4.5, 16, 6.7), (0, 0, -12), "nemes")
    hd("Head", "nemes_droit", (12.3, 50.4, -16), (4.5, 16, 6.7), (0, 0, 12), "nemes")
    hd("Head", "ornement_frontal", (0, 56.0, -26.9), (4.5, 5.1, 1.6), (0, 0, 0), "sun")
    hd("Head", "plaque_tempe_gauche", (-13.6, 42.8, -14.7), (3.5, 9.9, 4.8), (0, 0, -8), "sand_plain")
    hd("Head", "plaque_tempe_droite", (13.6, 42.8, -14.7), (3.5, 9.9, 4.8), (0, 0, 8), "ochre_plain")

    # ---------------------------------------------------------------- G. mineral tail
    for g, name, c, size, r, mat in tail_chain():
        add(g, name, c, size, r, mat)

    # ---------------------------------------------------------------- C. temple-pillar legs
    LEG = {   # bone: (upper, lower, foot, materials, upper tilt, upper size, foot size)
        "Leg_front_left": ((-14.4, 21.0, -11.5), (-14.7, 10.4, -12.5), (-14.7, 3.0, -14.7),
                           ("sand", "ochre_plain", "foot_clay"), (4, 0, -5), (9.3, 13.6, 9.9), (9.9, 5.1, 12.5)),
        "Leg_front_right": ((14.4, 21.0, -11.5), (14.7, 10.4, -12.5), (14.7, 3.0, -14.7),
                            ("ochre", "sand_plain", "foot_ochre"), (4, 0, 5), (9.3, 13.6, 9.9), (9.9, 5.1, 12.5)),
        "Leg_back_left": ((-14.1, 21.0, 14.4), (-14.4, 10.4, 16.3), (-14.4, 3.0, 16.0),
                          ("ochre", "sand_plain", "foot_ochre"), (0, 0, -7), (10.9, 13.6, 11.5), (9.9, 5.1, 11.5)),
        "Leg_back_right": ((14.1, 21.0, 14.4), (14.4, 10.4, 16.3), (14.4, 3.0, 16.0),
                           ("sand", "ochre_plain", "foot_clay"), (0, 0, 7), (10.9, 13.6, 11.5), (9.9, 5.1, 11.5)),
    }
    for g, (up, low, ft, (m1, m2, m3), r_up, s_up, s_ft) in LEG.items():
        front = "front" in g
        side = "gauche" if "left" in g else "droite"
        add(g, f"patte_{'avant' if front else 'arriere'}_{side}_haut", up, s_up, r_up, m1)
        add(g, f"patte_{'avant' if front else 'arriere'}_{side}_bas", low, (6.9 if front else 7.4, 8.8, 7.4),
            (-3 if front else 0, 0, 0), m2)
        add(g, f"pied_{'avant' if front else 'arriere'}_{side}", ft, s_ft, (0, 0, 0), m3)
        if front:
            add(g, f"bracelet_avant_{side}", (up[0] + (-0.3 if "left" in g else 0.3), 14.6, -11.9),
                (10.2, 3.2, 10.6), (0, 0, 0), "bracelet")

    # every leg rests exactly on the ground (y = 0)
    low = {}
    for g, n, c, size, r, m in P:
        if g.startswith("Leg_"):
            low[g] = min([low.get(g, 1e9)] + [q[1] for q in corners(c, size, r)])
    floor = min(low.values())
    return [(g, n, [c[0], c[1] - (low[g] - floor if g in low else 0), c[2]], size, r, m)
            for g, n, c, size, r, m in P]


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = None
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims = ground(parts(), GROUPS, scale=SCALE)
    uv, atlases, sizes = bake(P, MATS, GLOW_MATS)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    print(f"{len(P)} volumes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; "
          f"textures {sizes[0]} + {sizes[1]}")
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
    if prev:
        apercu(data, atlases, sizes, prev, set(AMBER), views=((35, 12), (0, 6), (90, 3), (215, 14)), size=420)
