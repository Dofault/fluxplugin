#!/usr/bin/env python3
"""Oracle: kindly cube wizard, guide and quest giver, STATIC Blockbench model (.bbmodel).

Third version (2026-11-10), built from the owner's design sheet ("L'Oracle, modèle 3D en cubes"): a compact, chibi
mage made only of boxes. 15 units tall (1 unit = 2 drawing pixels): feet 1, robe and body 6, head 4, hat 4. Purple
robe with a gold front band, brown belt with a square gold buckle, gold cuffs, light grey beard in blocks, wide hat
brim with a gold band and buckle, pointed top bending back and to one side, brown staff topped by a light purple orb
held in a brown claw. Flat colours of the sheet only, matte, no emissive texture, no effect.

Usage: oracle.py [output folder] [--apercu <sheet.png>]
Front faces north (-z), the character's right side is +x, origin between the feet, ground at y = 0.
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
HEIGHT = 3.2          # blocks, hat included (same in-game size as the previous Oracle)
U = 2.0               # drawing pixels per unit of the design sheet
STAFF_SIDE = 1        # +1: staff in the character's right hand (as drawn on the sheet), -1: left hand
TIP_SIDE = -1         # hat tip bends towards -x (the viewer's right on the front view, as drawn), +1 the other way

# --------------------------------------------------------------------------- sheet palette
PURPLE = hx("#542783")
PURPLE_DARK = hx("#38204F")
PURPLE_LIGHT = hx("#B86AE8")
GOLD = hx("#E9B52F")
SKIN = hx("#F2C49C")
BEARD = hx("#E5E1E7")
BROWN = hx("#56372E")
BELT = hx("#4A2F27")          # a touch darker than the staff, so belt and staff do not read as one material
DARK = hx("#211A29")          # eyes and shoes


def flat(color):
    def f(w, h, r, face):
        return [[color] * w for _ in range(h)]
    return f


def buckle(center):
    def f(w, h, r, face):
        """Square gold buckle, dark centre on the front face only."""
        px = [[GOLD] * w for _ in range(h)]
        if face == "north":
            bx, by = max(1, w // 3), max(1, h // 3)
            for y in range(by, h - by):
                for x in range(bx, w - bx):
                    px[y][x] = center
        return px
    return f


def head(w, h, r, face):
    """Skin face; grey hair on the back, and on the sides except a skin band at the front of the lower half."""
    if face in ("north", "down"):
        return [[SKIN] * w for _ in range(h)]
    if face in ("south", "up"):
        return [[BEARD] * w for _ in range(h)]
    front = (lambda x: x >= w * 0.6) if face == "east" else (lambda x: x < w * 0.4)    # east u runs back to front
    return [[SKIN if y >= h * 0.45 and front(x) else BEARD for x in range(w)] for y in range(h)]


MATS = {
    "purple": flat(PURPLE), "purple_dark": flat(PURPLE_DARK), "gold": flat(GOLD), "skin": flat(SKIN),
    "beard": flat(BEARD), "brown": flat(BROWN), "belt": flat(BELT), "dark": flat(DARK), "orb": flat(PURPLE_LIGHT),
    "head": head, "belt_buckle": (buckle(DARK), 2), "hat_buckle": (buckle(PURPLE_DARK), 2),
}

# --------------------------------------------------------------------------- bones (units)
S, T = STAFF_SIDE, TIP_SIDE
HAND = (3.55 * S, 3.9, -0.7)
GROUPS = {
    "Root": ((0, 0, 0), None),
    "Body": ((0, 1, 0), "Root"),
    "Head": ((0, 7, 0), "Body"),
    "Beard": ((0, 7.6, -2.3), "Head"),
    "Hat": ((0, 11, 0), "Head"),
    "Right_Arm": ((2.6, 7, 0), "Body"),
    "Left_Arm": ((-2.6, 7, 0), "Body"),
    "Staff": (HAND, "Right_Arm" if S > 0 else "Left_Arm"),
    "Orb": ((HAND[0], 8.5, HAND[2]), "Staff"),
    "Right_Leg": ((1.5, 1, 0), "Root"),
    "Left_Leg": ((-1.5, 1, 0), "Root"),
}


def parts():
    """Every piece in units: (bone, name, centre, size, rotation, material)."""
    P = []

    def add(g, name, c, size, r, mat):
        P.append((g, name, c, size, r, mat))

    def box(g, name, x0, x1, y0, y1, z0, z1, mat, r=(0, 0, 0)):
        add(g, name, ((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2), (x1 - x0, y1 - y0, z1 - z0), r, mat)

    # ---------------------------------------------------------------- feet: small dark blocks peeking out under the robe
    for s, side in ((1, "Right"), (-1, "Left")):
        box(f"{side}_Leg", "pied", 1.5 * s - 1, 1.5 * s + 1, 0, 1.2, -2.6, 0.4, "dark")

    # ---------------------------------------------------------------- robe: wider at the bottom, belt, torso, gold band
    box("Body", "robe_bas", -3, 3, 1, 4.2, -2.2, 2.2, "purple")
    box("Body", "ceinture", -2.7, 2.7, 4.2, 4.9, -2.15, 2.15, "belt")
    box("Body", "boucle_ceinture", -0.7, 0.7, 3.85, 5.25, -2.4, -2.1, "belt_buckle")
    box("Body", "torse", -2.5, 2.5, 4.9, 7, -2, 2, "purple")
    box("Body", "bande_doree", -0.4, 0.4, 1, 6.6, -2.35, -2.05, "gold")

    # ---------------------------------------------------------------- arms: short thick sleeves, slightly out, gold cuffs
    for s, side in ((1, "Right"), (-1, "Left")):
        g = f"{side}_Arm"
        tilt = (0, 0, 7 * s)                       # bottom of the sleeve moves outwards
        place = attach((2.6 * s, 7, 0), rot(*tilt))
        for name, x0, x1, y0, y1, z0, z1, mat in (
                ("manche", 2.5, 4.3, 4.9, 7, -1.3, 1.3, "purple"),
                ("poignet", 2.4, 4.4, 4.3, 4.9, -1.4, 1.4, "gold")):
            c, M = place((s * (x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2), (0, 0, 0))
            add(g, name, c, (x1 - x0, y1 - y0, z1 - z0), M, mat)
        hand_z = HAND[2] if s == S else 0          # the hand holding the staff comes slightly forward
        c, M = place((3.45 * s, 3.75, hand_z), (0, 0, 0))
        add(g, "main", c, (1.2, 1.2, 1.2), M, "skin")

    # ---------------------------------------------------------------- head: compact block, two eyes, nose
    box("Head", "tete", -2.75, 2.75, 7, 11, -2.25, 2.25, "head")
    for s, side in ((1, "droit"), (-1, "gauche")):
        box("Head", f"oeil_{side}", 1.1 * s - 0.3, 1.1 * s + 0.3, 9.1, 10.1, -2.4, -2.2, "dark")
    box("Head", "nez", -0.45, 0.45, 8.5, 9.3, -2.5, -2.2, "skin")

    # ---------------------------------------------------------------- beard: moustache, base, two short sides, long middle
    for s, side in ((1, "droite"), (-1, "gauche")):
        box("Beard", f"moustache_{side}", 0.3 * s if s > 0 else -1.7, 1.7 if s > 0 else -0.3, 7.9, 8.6, -2.75, -2.25,
            "beard")
        box("Beard", f"barbe_{side}", 1.2 * s - 0.8, 1.2 * s + 0.8, 6.1, 7.9, -2.6, -1.9, "beard")
    box("Beard", "barbe_base", -2.4, 2.4, 7.0, 8.2, -2.55, -1.9, "beard")
    box("Beard", "barbe_centre", -1.05, 1.05, 5.5, 7.4, -2.7, -2.0, "beard")

    # ---------------------------------------------------------------- hat: wide flat brim, gold band and buckle, bent tip
    box("Hat", "bord", -4, 4, 10.6, 11.6, -3.5, 3.5, "purple")
    box("Hat", "bande", -2.6, 2.6, 11.6, 12.3, -2.4, 2.4, "gold")
    box("Hat", "boucle", -0.8, 0.8, 11.45, 12.45, -2.65, -2.35, "hat_buckle")
    box("Hat", "cone_bas", -2.3, 2.3, 12.3, 13.2, -2.1, 2.1, "purple")
    # the tip climbs, shifts to one side and back, each block a little more tilted (sheet: 0 / 10-20 / 30 degrees)
    for name, cx, cy, cz, w, h, d, ax, az in (
            ("cone_milieu", 0.35, 13.7, 0.3, 3.2, 1.2, 3.0, 6, 8),
            ("cone_haut", 0.9, 14.4, 0.75, 2.2, 1.0, 2.0, 12, 16),
            ("pointe", 1.5, 14.95, 1.25, 1.3, 0.9, 1.3, 22, 30)):
        add("Hat", name, (cx * T, cy, cz), (w, h, d), (ax, 0, az * T), "purple")

    # ---------------------------------------------------------------- staff: three brown blocks, orb in a brown claw
    g = "Staff"
    lean = attach(HAND, rot(0, 0, -7 * S))          # top of the staff leans outwards
    x, z = HAND[0], HAND[2]
    for name, y0, y1, w in (("baton_bas", 0.2, 3.1, 0.6), ("baton_milieu", 3.1, 5.8, 0.66), ("baton_haut", 5.8, 7.9, 0.6)):
        c, M = lean((x, (y0 + y1) / 2, z), (0, 0, 0))
        add(g, name, c, (w, y1 - y0, w), M, "brown")
    for name, dx, dy, size, mat, grp in (
            ("griffe_base", 0, 7.95, (1.2, 0.4, 1.2), "brown", "Staff"),
            ("orbe", 0, 8.7, (1.0, 1.0, 1.0), "orb", "Orb"),
            ("griffe_droite", 0.65, 8.75, (0.4, 1.2, 0.5), "brown", "Staff"),
            ("griffe_gauche", -0.65, 8.75, (0.4, 1.2, 0.5), "brown", "Staff"),
            ("griffe_haut", 0, 9.4, (0.5, 0.4, 0.5), "brown", "Staff")):
        c, M = lean((x + dx, dy, z), (0, 0, 0))
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
    uv, atlases, sizes = bake(P, MATS, {})
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    print(f"{len(P)} cubes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocks (w x h x d) ; "
          f"{raw[0] * 16 / U:.1f} x {raw[1] * 16 / U:.1f} x {raw[2] * 16 / U:.1f} units ; texture {sizes[0]}")
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("written", path)
    if prev:
        apercu(data, atlases, sizes, prev, set(), views=((0, 2), (90, 2), (180, 2), (32, 10)), size=400)
