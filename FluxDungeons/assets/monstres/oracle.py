#!/usr/bin/env python3
"""Oracle: chibi cube wizard, guide and quest giver, STATIC Blockbench model (.bbmodel).

Rebuilt from scratch on 2026-11-10 from the owner's design sheet ("L'Oracle, modèle 3D en cubes"), proportions read
off the reference views rather than the text: big hat (about 40 % of the height, wide thick brim, gold band with a
large square buckle, stepped cone whose tip bends back and to the viewer's right), big head (6 x 4 x 5) framed by grey
hair, dark tall eyes, rosy cheeks, cube nose, stepped grey beard that falls on the chest, short wide robe with a gold
hem, gold front band, brown belt and gold buckle, wide sleeves with gold cuffs, big hands, dark feet, brown staff in
the character's right hand with a glowing light purple orb held in a brown claw at eye level.

Usage: oracle.py [output folder] [--apercu <sheet.png>]
Front faces north (-z), the character's right side is +x, origin between the feet, ground at y = 0.
Built in units of the sheet (1 unit = 2 drawing pixels), 15 units high.
"""
import json
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, export, ground, hx  # noqa: E402
from golem_fonderie import rot  # noqa: E402

IDENT = "oracle"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000000a7c1")
HEIGHT = 3.2          # blocks in game, hat included
U = 2.0               # drawing pixels per unit of the sheet

# --------------------------------------------------------------------------- sheet palette
PURPLE = hx("#5C3278")
PURPLE_DARK = hx("#3A1F4E")
GOLD = hx("#E5BE34")
GREY = hx("#C4C4C4")
SKIN = hx("#E2C0A7")
BROWN = hx("#5A3825")
ORB = hx("#D194FF")
ORB_LIGHT = hx("#E8C4FF")
FEET = hx("#2C1A3B")          # darker than the robe details, as on the sheet
EYE = hx("#2B1D24")
CHEEK = hx("#E9A79C")


def flat(color):
    def f(w, h, r, face):
        return [[color] * w for _ in range(h)]
    return f


def robe(w, h, r, face):
    """Robe (density 2): purple, two dark folds on the front and back."""
    px = [[PURPLE] * w for _ in range(h)]
    if face in ("north", "south") and w >= 8:
        for x in (w // 4, w - 1 - w // 4):
            for y in range(h // 3, h):
                px[y][x] = PURPLE_DARK
    return px


def sleeve(w, h, r, face):
    """Sleeve (density 2): purple, a dark fold down the lower half of the outer and front faces."""
    px = [[PURPLE] * w for _ in range(h)]
    if face in ("north", "east", "west") and w >= 3:
        for y in range(h // 2, h):
            px[y][w // 2] = PURPLE_DARK
    return px


def buckle(center):
    def f(w, h, r, face):
        """Square gold buckle with a dark centre on its front face."""
        px = [[GOLD] * w for _ in range(h)]
        if face == "north":
            bx, by = max(1, w // 4), max(1, h // 4)
            for y in range(by, h - by):
                for x in range(bx, w - bx):
                    px[y][x] = center
        return px
    return f


HEAD_W, HEAD_D = 5.2, 4.6     # head size in units (height 4)


def head(w, h, r, face):
    """Head, drawn in units of the sheet. Front: skin framed by grey hair, tall dark eyes, rosy cheeks.
    Sides: grey hair, skin in the lower front corner. Back, top, bottom: grey hair."""
    if face == "north":
        k = w / HEAD_W                                        # texels per unit
        mid = HEAD_W / 2

        def rect(x0, x1, y0, y1, col):                        # x from the face's left edge, y from its top
            for y in range(round(y0 * k), round(y1 * k)):
                for x in range(round(x0 * k), round(x1 * k)):
                    if 0 <= x < w and 0 <= y < h:
                        px[y][x] = col
        px = [[SKIN] * w for _ in range(h)]
        rect(0, 0.55, 0, 4, GREY)                             # hair framing the face
        rect(HEAD_W - 0.55, HEAD_W, 0, 4, GREY)
        rect(0, HEAD_W, 0, 0.35, GREY)                        # hair line under the brim
        for c in (mid - 1.15, mid + 1.15):
            rect(c - 0.45, c + 0.45, 0.6, 1.85, EYE)          # tall eyes, 0.9 x 1.25
            rect(c - 0.5 + (0.3 if c < mid else -0.3), c + 0.5 + (0.3 if c < mid else -0.3), 2.05, 2.4, CHEEK)
        return px
    if face in ("east", "west"):
        k = w / HEAD_D
        front = (lambda x: x >= w - 1.1 * k) if face == "east" else (lambda x: x < 1.1 * k)  # east u runs back to front
        return [[SKIN if y >= h * 0.45 and front(x) else GREY for x in range(w)] for y in range(h)]
    return [[GREY] * w for _ in range(h)]


def orb(w, h, r, face):
    px = [[ORB] * w for _ in range(h)]
    if face != "down" and w >= 2:
        px[0][0] = ORB_LIGHT
    return px


MATS = {
    "purple": flat(PURPLE), "gold": flat(GOLD), "grey": flat(GREY), "skin": flat(SKIN), "brown": flat(BROWN),
    "feet": flat(FEET), "robe": (robe, 2), "sleeve": (sleeve, 2), "head": (head, 4),
    "belt_buckle": (buckle(PURPLE_DARK), 4), "hat_buckle": (buckle(PURPLE_DARK), 4),
}
GLOW_MATS = {"orb": (orb, 4)}

# --------------------------------------------------------------------------- layout (units, y up)
FEET_TOP = 1.0
ROBE_TOP = 6.4          # shoulders
HEAD = (6.4, 10.4)      # 6 x 4 x 5
BRIM = (10.15, 11.15)   # thick brim, overhangs the face
HAND = (3.3, 3.45, -0.9)  # right hand (holds the staff), centre

GROUPS = {
    "Root": ((0, 0, 0), None),
    "Body": ((0, FEET_TOP, 0), "Root"),
    "Head": ((0, HEAD[0], 0), "Body"),
    "Beard": ((0, 7.6, -2.4), "Head"),
    "Hat": ((0, BRIM[0], 0), "Head"),
    "Right_Arm": ((2.5, ROBE_TOP, 0), "Body"),
    "Left_Arm": ((-2.5, ROBE_TOP, 0), "Body"),
    "Staff": (HAND, "Right_Arm"),
    "glow_orb": ((HAND[0], 9.45, HAND[2]), "Staff"),
    "Right_Leg": ((1.3, FEET_TOP, 0), "Root"),
    "Left_Leg": ((-1.3, FEET_TOP, 0), "Root"),
}


def parts():
    """Every piece in units: (bone, name, centre, size, rotation, material)."""
    P = []

    def add(g, name, c, size, r, mat):
        P.append((g, name, list(c), list(size), r, mat))

    def box(g, name, x0, x1, y0, y1, z0, z1, mat, r=(0, 0, 0)):
        add(g, name, ((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2), (x1 - x0, y1 - y0, z1 - z0), r, mat)

    # ---------------------------------------------------------------- feet
    for s, side in ((1, "Right"), (-1, "Left")):
        box(f"{side}_Leg", "pied", 1.3 * s - 1, 1.3 * s + 1, 0, FEET_TOP + 0.4, -2.5, 0.5, "feet")

    # ---------------------------------------------------------------- robe: gold hem, skirt, belt + buckle, chest, band
    box("Body", "ourlet", -3, 3, FEET_TOP, FEET_TOP + 0.5, -2.2, 2.2, "gold")
    box("Body", "robe_bas", -2.9, 2.9, FEET_TOP + 0.5, 3.9, -2.1, 2.1, "robe")
    box("Body", "ceinture", -2.65, 2.65, 3.9, 4.55, -2.05, 2.05, "brown")
    box("Body", "boucle_ceinture", -0.7, 0.7, 3.55, 4.95, -2.3, -2.0, "belt_buckle")
    box("Body", "torse", -2.5, 2.5, 4.55, ROBE_TOP, -2, 2, "robe")
    box("Body", "bande_doree", -0.45, 0.45, FEET_TOP + 0.5, ROBE_TOP, -2.2, -1.95, "gold")

    # ---------------------------------------------------------------- arms: wide sleeves, gold cuffs, big hands
    for s, side in ((1, "Right"), (-1, "Left")):
        g = f"{side}_Arm"
        place = attach((2.5 * s, ROBE_TOP, 0), rot(0, 0, 5 * s))           # the sleeve opens slightly outwards
        holding = s == 1
        zc = -0.5 if holding else 0                                         # the staff arm comes a bit forward
        for name, y0, y1, w, d, mat in (("manche", 4.6, ROBE_TOP + 0.2, 1.9, 2.6, "sleeve"),
                                        ("poignet", 4.0, 4.7, 2.3, 3.0, "gold")):     # bell sleeve
            c, M = place((s * 3.3, (y0 + y1) / 2, zc), (0, 0, 0))
            add(g, name, c, (w, y1 - y0, d), M, mat)
        c, M = place((s * 3.3, HAND[1], HAND[2] if holding else 0), (0, 0, 0))
        add(g, "main", c, (1.3, 1.3, 1.3), M, "skin")

    # ---------------------------------------------------------------- head: face painted, cube nose
    box("Head", "tete", -HEAD_W / 2, HEAD_W / 2, HEAD[0], HEAD[1], -2.4, HEAD_D - 2.4, "head")
    box("Head", "nez", -0.4, 0.4, 7.9, 8.75, -2.75, -2.35, "skin")

    # ---------------------------------------------------------------- beard: moustache, then steps down to the chest
    for s, side in ((1, "droite"), (-1, "gauche")):
        x0, x1 = (0.15, 1.8) if s > 0 else (-1.8, -0.15)
        box("Beard", f"moustache_{side}", x0, x1, 7.35, 7.95, -2.8, -2.3, "grey")
    box("Beard", "barbe_menton", -2.1, 2.1, 6.3, 7.4, -2.65, -1.9, "grey")
    box("Beard", "barbe_milieu", -1.5, 1.5, 5.4, 6.4, -2.6, -1.95, "grey")
    box("Beard", "barbe_pointe", -0.8, 0.8, 4.8, 5.5, -2.5, -2.0, "grey")

    # ---------------------------------------------------------------- hat: thick 8 x 7 brim, band + buckle, stepped cone
    box("Hat", "bord", -4, 4, BRIM[0], BRIM[1], -3.7, 3.3, "purple")
    box("Hat", "cone_bas", -2.5, 2.5, BRIM[1], 12.5, -2.3, 2.3, "purple")
    box("Hat", "bande", -2.6, 2.6, BRIM[1], BRIM[1] + 0.6, -2.4, 2.4, "gold")
    box("Hat", "boucle", -0.8, 0.8, BRIM[1] - 0.05, BRIM[1] + 1.15, -2.6, -2.35, "hat_buckle")
    # the cone narrows step by step and drifts back and to the viewer's right (-x), the last blocks tilt with it
    for name, cx, y0, y1, w, d, cz, ax, az in (
            ("cone_2", -0.2, 12.5, 13.45, 3.8, 3.6, 0.25, 0, 4),
            ("cone_3", -0.7, 13.45, 14.2, 2.8, 2.6, 0.7, 6, 10),
            ("cone_4", -1.35, 14.15, 14.75, 2.0, 1.9, 1.15, 12, 18),
            ("pointe", -2.05, 14.6, 15.15, 1.2, 1.2, 1.6, 18, 30)):
        add("Hat", name, (cx, (y0 + y1) / 2, cz), (w, y1 - y0, d), (ax, 0, az), "purple")

    # ---------------------------------------------------------------- staff: two brown blocks, orb in a brown claw
    lean = attach(HAND, rot(0, 0, -5))              # top leans slightly outwards
    x, z = HAND[0], HAND[2]
    for name, y0, y1, w in (("baton_bas", 0.15, 4.4, 0.75), ("baton_haut", 4.4, 8.55, 0.75)):
        c, M = lean((x, (y0 + y1) / 2, z), (0, 0, 0))
        add("Staff", name, c, (w, y1 - y0, w), M, "brown")
    for name, dx, dy, dz, size, mat, grp in (
            ("griffe_base", 0, 8.75, 0, (1.3, 0.4, 1.3), "brown", "Staff"),
            ("orbe", 0, 9.45, 0, (1.0, 1.0, 1.0), "orb", "glow_orb"),
            ("griffe_droite", 0.65, 9.55, 0, (0.4, 1.4, 0.6), "brown", "Staff"),
            ("griffe_gauche", -0.65, 9.55, 0, (0.4, 1.4, 0.6), "brown", "Staff"),
            ("griffe_arriere", 0, 9.5, 0.6, (0.6, 1.2, 0.35), "brown", "Staff"),
            ("griffe_haut", 0, 10.1, 0, (0.5, 0.4, 0.5), "brown", "Staff")):
        c, M = lean((x + dx, dy, z + dz), (0, 0, 0))
        add(grp, name, c, size, M, mat)
    return P


def drawn():
    """Parts and bones in drawing pixels."""
    P = [(g, n, [v * U for v in c], [v * U for v in size], r, m) for g, n, c, size, r, m in parts()]
    groups = {k: ([v * U for v in pv], parent) for k, (pv, parent) in GROUPS.items()}
    return P, groups


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = None
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    P0, G0 = drawn()
    _, _, raw = ground(P0, G0, center=False)
    P, groups, dims = ground(P0, G0, scale=HEIGHT / raw[1], center=False)   # origin between the feet
    uv, atlases, sizes = bake(P, MATS, GLOW_MATS)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    print(f"{len(P)} cubes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocks (w x h x d) ; "
          f"{raw[0] * 16 / U:.1f} x {raw[1] * 16 / U:.1f} x {raw[2] * 16 / U:.1f} units ; textures {sizes[0]}, {sizes[1]}")
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("written", path)
    if prev:
        apercu(data, atlases, sizes, prev, {ORB, ORB_LIGHT}, views=((0, 4), (90, 4), (180, 4), (-90, 4), (35, 12)),
               size=400)
