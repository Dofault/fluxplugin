#!/usr/bin/env python3
"""Hamster Moissonneur: farm boss, STATIC Blockbench model (.bbmodel), second version (2026-11-10).

A real golden hamster first, a boss second: small (about 3 x 3 x 3.3 blocks), low and round, big head, stuffed cheek
pouches (it ate the harvest; no food is shown), small round ears, short cream muzzle, small dark eyes, four short legs
under the body. Built like the Sea Otter and the Abyss Colossus: a few large boxes per mass, side and top boxes a
little smaller and tilted 5 to 12 degrees to round the silhouette, everything else painted at 1 texel per unit.

Fantasy stays discreet: golden flecks along the spine, a small glowing golden diamond on the forehead and three tiny
ones along the back (emissive texture).

Usage: hamster_moissonneur.py [output folder] [--apercu <sheet.png>]
Front towards north (-z), the monster's right towards +x, ground at y = 0, 16 units = 1 block.
"""
import json
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, export, ground, hx, tone  # noqa: E402
from golem_fonderie import mul, rot  # noqa: E402

IDENT = "hamster_moissonneur"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-0000000a3517")
SCALE = 1.14         # global size (parts are drawn at about their brief size, 16 units = 1 block)


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- brief palette (dark to light)
FUR = pal("#713B25", "#A95727", "#C26A2C", "#D98232", "#E99A42", "#F0AE58")   # 3 = main coat
CHEEK = pal("#D9984F", "#E6A75F", "#F0B76F", "#F5C788")
BELLY = pal("#D8B07C", "#EBCB99", "#F2D8B0")
MUZZLE = pal("#EBCB99", "#F5DDB5", "#FAE9CF")
EAR_IN = pal("#B8705F", "#D98D79")
PAW = pal("#96522A", "#B96832", "#C97C44")
TOE = pal("#D9A08A", "#E8B6A0")
NOSE, EYE, GLINT = hx("#38251D"), hx("#241711"), hx("#E9D9C4")
GOLD = pal("#E9B04A", "#F7CF6A")
GLOW = pal("#F0B43C", "#FFD94D", "#FFF0A8")

LIGHT = {"up": 1, "north": 0, "east": 0, "west": 0, "south": -1, "down": -2}


# --------------------------------------------------------------------------- painters
def fur(base=3, belly_edge=False, gold=False):
    def f(w, h, r, face):
        """Coat: flat main tone, short darker tufts (2 pixels high), lighter flecks on top, top edge caught by the
        light, bottom row in shade; `belly_edge` melts the bottom of the sides into the cream belly (no hard line);
        `gold` adds a few golden flecks on the top face (fantasy, very discreet)."""
        b = base + LIGHT[face]
        px = [[tone(FUR, b)] * w for _ in range(h)]
        for _ in range(max(1, w * h // 22)):
            x, y = r.randrange(w), r.randrange(h)
            px[y][x] = tone(FUR, b - 1)
            if y + 1 < h and r.random() < 0.5:
                px[y + 1][x] = tone(FUR, b - 1)
        for _ in range(max(1, w * h // 34)):
            px[r.randrange(h)][r.randrange(w)] = tone(FUR, b + 1)
        if face in ("east", "west", "north", "south") and h >= 4:
            for x in range(w):
                if r.random() < 0.35:
                    px[0][x] = tone(FUR, b + 1)
            if belly_edge and h >= 6:
                px[h - 1] = [BELLY[1]] * w
                px[h - 2] = [BELLY[1] if r.random() < 0.6 else BELLY[0] for _ in range(w)]
                px[h - 3] = [BELLY[0] if r.random() < 0.35 else px[h - 3][x] for x in range(w)]
            else:
                px[h - 1] = [tone(FUR, b - 1)] * w
        if gold and face == "up":
            for _ in range(max(1, w * h // 45)):
                px[r.randrange(h)][r.randrange(w)] = GOLD[r.randrange(2)]
        return px
    return f


def back(w, h, r, face):
    """Spine: one tone darker than the sides (golden hamster), golden flecks in a loose line down the middle."""
    px = fur(2)(w, h, r, face)
    if face == "up" and w >= 5:
        c = w // 2
        for y in range(1, h - 1):
            if r.random() < 0.45:
                px[y][c + r.choice((-1, 0, 0, 1))] = GOLD[0 if r.random() < 0.7 else 1]
    return px


def cream(palette, base=1):
    def f(w, h, r, face):
        """Cheeks, belly, muzzle: soft cream, a few pixels a tone darker or lighter, bottom row in shade."""
        b = base + LIGHT[face] + (0 if face != "up" else 0)
        px = [[tone(palette, b)] * w for _ in range(h)]
        for _ in range(max(1, w * h // 14)):
            px[r.randrange(h)][r.randrange(w)] = tone(palette, b - 1 if r.random() < 0.6 else b + 1)
        if face in ("east", "west", "north", "south") and h >= 4:
            px[h - 1] = [tone(palette, b - 1)] * w
        return px
    return f


def cheek(w, h, r, face):
    """Cheek pouch: fur on the upper part, melting into orange cream below (full, well fed)."""
    if face == "down":
        return cream(CHEEK, 1)(w, h, r, face)
    px = cream(CHEEK, 2)(w, h, r, face)
    if face in ("east", "west", "north", "south", "up"):
        cut = h * (0.3 if face != "up" else 0.0)
        for y in range(h):
            for x in range(w):
                if face == "up" or y < cut or (y < cut + 2 and r.random() < 0.5):
                    if face != "up" or r.random() < 0.55:
                        px[y][x] = tone(FUR, 4 if r.random() < 0.7 else 3)
    return px


def muzzle(w, h, r, face):
    """Muzzle: lightest cream; on the front, the rodent 'Y' under the nose and a small closed mouth."""
    px = cream(MUZZLE, 1)(w, h, r, face)
    if face == "north" and w >= 5 and h >= 4:
        c = w // 2
        px[0][c] = MUZZLE[0]
        for y in range(1, h - 2):
            px[y][c] = FUR[1]
        px[h - 2][c - 1] = FUR[1]
        px[h - 2][c + 1] = FUR[1]
        if w >= 7:
            px[h - 2][c - 2] = MUZZLE[0]
            px[h - 2][c + 2] = MUZZLE[0]
    return px


def nose(w, h, r, face):
    """Nose (density 2): almost black brown, top edge a little lighter, two nostrils."""
    px = [[NOSE] * w for _ in range(h)]
    if face in ("north", "up"):
        px[0] = [FUR[0]] * w
    if face == "north" and w >= 4:
        px[h - 1][1] = EYE
        px[h - 1][w - 2] = EYE
    return px


def eye_painter(glint_right):
    def f(w, h, r, face):
        """Eye (density 2): small, very dark brown, one light glint in the upper corner towards the outside."""
        px = [[EYE] * w for _ in range(h)]
        if face == "north":
            gx = w - 2 if glint_right else 1
            px[1][gx] = GLINT
            for x in range(w):
                px[h - 1][x] = FUR[0]
        return px
    return f


def ear(w, h, r, face):
    """Ear: fur outside, salmon pink inside (front face), framed by a one pixel rim of coat."""
    b = 3 + LIGHT[face]
    px = [[tone(FUR, b)] * w for _ in range(h)]
    if face == "north" and w >= 3 and h >= 3:
        for y in range(1, h):
            for x in range(1, w - 1):
                px[y][x] = EAR_IN[1] if y < h - 2 or x in (1, w - 2) else EAR_IN[0]
    return px


def leg(w, h, r, face):
    """Leg: darker orange brown, lighter on top, bottom row in shade."""
    b = 1 + LIGHT[face]
    px = [[tone(PAW, b)] * w for _ in range(h)]
    for _ in range(max(1, w * h // 12)):
        px[r.randrange(h)][r.randrange(w)] = tone(PAW, b - 1)
    if face not in ("up", "down") and h >= 3:
        px[0] = [tone(FUR, 3)] * w
    return px


def paw(toes):
    def f(w, h, r, face):
        """Foot: pinkish beige, toes split by darker lines on the front and the top, no claws."""
        b = 1 if face in ("up", "north") else 0
        px = [[TOE[b]] * w for _ in range(h)]
        step = max(1, w // toes)
        if face in ("north", "up"):
            for x in range(step, w - 1, step):
                for y in range(h if face == "north" else min(h, 3)):
                    px[y][x] = TOE[0] if face == "up" else PAW[2]
        if face == "down":
            px = [[PAW[0]] * w for _ in range(h)]
        return px
    return f


def tail(w, h, r, face):
    return [[FUR[2 if face != "up" else 3]] * w for _ in range(h)]


def mouth(w, h, r, face):
    """Inside of the mouth: almost black brown, a darker back."""
    return [[NOSE if (x + y) % 3 else EYE for x in range(w)] for y in range(h)]


def tooth(w, h, r, face):
    """Incisor (density 2): warm ivory, a shade darker at the tip."""
    px = [[hx("#F2E3BF")] * w for _ in range(h)]
    px[h - 1] = [hx("#D9C69C")] * w
    return px


def glow(w, h, r, face):
    """Glowing golden mark: bright gold, light edge on top."""
    px = [[GLOW[1]] * w for _ in range(h)]
    if face in ("up", "north"):
        px[0][0] = GLOW[2]
    return px


MATS = {
    "fur": fur(3), "fur_light": fur(4), "flank": fur(3, belly_edge=True), "back": back, "back_gold": fur(3, gold=True),
    "head": fur(3), "head_top": fur(4, gold=True), "cheek": cheek, "belly": cream(BELLY, 1), "chest": cream(CHEEK, 1),
    "muzzle": muzzle, "chin": cream(MUZZLE, 0), "nose": (nose, 2), "eye_r": (eye_painter(True), 2),
    "eye_l": (eye_painter(False), 2), "ear": (ear, 2), "leg": leg, "front_paw": (paw(3), 2), "back_paw": (paw(4), 2),
    "tail": tail, "mouth": mouth, "tooth": (tooth, 2), "lid": fur(3),
}
GLOW_MATS = {"glow": glow}


# --------------------------------------------------------------------------- skeleton (drawing units)
# One bone per part that moves on its own (hamster_moissonneur_anim.py). Pivots at the joints: neck at the base of the
# nape, head where it meets the neck, jaw at the back of the chin, ears at their base, legs at the top of each leg.
# The legs are children of Body: they follow it when it rolls into a ball, leaps or falls on its side (the animations
# keep the planted feet on the ground). Two sets of cheeks: Cheek_* (full, the usual look) and Cheek_*_empty (the same
# cheeks reduced to EMPTY_CHEEK, tucked into the face); FluxDungeons shows one set and hides the other.
EMPTY_CHEEK = 0.62
GROUPS = {
    "Root": ((0, 0, 0), None),
    "Body": ((0, 15, 5), "Root"),
    "Tail": ((0, 12.5, 18.5), "Body"),
    "glow_back": ((0, 29, 7), "Body"),
    "Neck": ((0, 24, -2), "Body"),
    "Head": ((0, 26, -8), "Neck"),
    "glow_head": ((0, 37, -26), "Head"),
    "Muzzle": ((0, 28, -23.5), "Head"),
    "Nose": ((0, 29, -28.2), "Muzzle"),
    "Jaw": ((0, 24.9, -22.6), "Head"),
    "Eye_right": ((8.2, 33.2, -25.1), "Head"),
    "Eye_left": ((-8.2, 33.2, -25.1), "Head"),
    "Eyelid_right": ((8.2, 34.75, -25.3), "Head"),
    "Eyelid_left": ((-8.2, 34.75, -25.3), "Head"),
    "Ear_right": ((8.6, 40.2, -13.5), "Head"),
    "Ear_left": ((-8.6, 40.2, -13.5), "Head"),
    "Cheek_right": ((8.5, 25.5, -21), "Head"),
    "Cheek_left": ((-8.5, 25.5, -21), "Head"),
    "Cheek_right_empty": ((8.5, 25.5, -21), "Head"),
    "Cheek_left_empty": ((-8.5, 25.5, -21), "Head"),
    "Leg_front_right": ((10.5, 8, -9), "Body"),
    "Leg_front_left": ((-10.5, 8, -9), "Body"),
    "Leg_back_right": ((12, 8, 16), "Body"),
    "Leg_back_left": ((-12, 8, 16), "Body"),
}


def _under(bone, root):
    while bone is not None:
        if bone == root:
            return True
        bone = GROUPS[bone][1]
    return False


HEAD_DROP = 5        # head drawn on top of the body, then lowered into it: a hamster has almost no neck
BODY_DROP = 1.5      # body and head lowered onto the legs: short legs, low centre of gravity
HEAD_BACK = 2        # and pushed a little back into the shoulders
HEAD_BONES = {k for k in GROUPS if _under(k, "Neck")}
LEG_BONES = {k for k in GROUPS if k.startswith("Leg_")}
GROUPS = {k: ((pv[0], pv[1] - HEAD_DROP, pv[2] + HEAD_BACK) if k in HEAD_BONES else pv, par) for k, (pv, par) in GROUPS.items()}
GROUPS = {k: ((pv[0], pv[1] - BODY_DROP, pv[2]) if k != "Root" and k not in LEG_BONES else pv, par)
          for k, (pv, par) in GROUPS.items()}


def parts():
    P = []

    def add(g, name, c, size, r, mat):
        if g in HEAD_BONES:
            c = (c[0], c[1] - HEAD_DROP, c[2] + HEAD_BACK)
        if g not in LEG_BONES:
            c = (c[0], c[1] - BODY_DROP, c[2])
        P.append((g, name, c, size, r, mat))

    # ---------------------------------------------------------------- body: short, round, low (1.7 x 1.3 x 1.9 blocks)
    # highest point of the back behind the head, rear rounded down to the ground: an oval seen from above
    add("Body", "corps", (0, 15, 5), (26, 18, 21), (0, 0, 0), "flank")
    add("Body", "dos", (0, 25, 4), (22, 4, 17), (-3, 0, 0), "back")
    add("Body", "dos_haut", (0, 27.6, 7.5), (15, 2.4, 13), (-6, 0, 0), "back")
    # flanks bulging out, top leaning in: no box edge on the silhouette seen from the front
    for s, side in ((1, "droit"), (-1, "gauche")):
        add("Body", f"flanc_{side}", (13.2 * s, 14.5, 4.5), (4, 13, 18), (0, 0, 7 * s), "flank")
        # round haunch at the back of each flank (hamsters sit on big round hips)
        add("Body", f"hanche_{side}", (11 * s, 14, 14), (7, 12, 9), (-8, -6 * s, 6 * s), "fur")
    # rounded rear: cap leaning forward, a smaller one cutting the top corner, a tiny stub of a tail
    add("Body", "croupe", (0, 14, 16.5), (22, 14, 5), (-12, 0, 0), "flank")
    add("Body", "croupe_haut", (0, 22.6, 13.6), (20, 7, 6), (-38, 0, 0), "fur")
    add("Tail", "queue", (0, 12, 19.2), (2.5, 2, 2), (20, 0, 0), "tail")
    # cream belly underneath and a small chest under the head (no large white area)
    add("Body", "ventre", (0, 6, 4), (20, 3, 20), (0, 0, 0), "belly")
    add("Body", "poitrail", (0, 11.5, -5.6), (15, 7, 3), (8, 0, 0), "chest")

    # ---------------------------------------------------------------- head: wide, a little flat, made of several boxes
    add("Neck", "nuque", (0, 29.5, -5.5), (21, 13, 6), (12, 0, 0), "head")
    add("Head", "crane", (0, 30, -16), (25, 18, 18), (0, 0, 0), "head")
    add("Head", "crane_haut", (0, 40.0, -15.5), (21, 2.6, 15), (0, 0, 0), "head_top")
    add("Head", "crane_sommet", (0, 41.8, -15), (14, 1.4, 10), (0, 0, 0), "head_top")
    # front of the forehead, a little rounded over the eyes
    add("Head", "front", (0, 35.5, -25.2), (15, 6, 2.5), (6, 0, 0), "head")
    # muzzle (tilted 5 degrees down), lighter, with the nose, two small incisors under it, and a small chin (the jaw);
    # the dark inside of the mouth shows when the jaw opens
    add("Muzzle", "museau", (0, 27.2, -26.0), (9, 5.6, 5.6), (-5, 0, 0), "muzzle")
    add("Muzzle", "arete", (0, 30.6, -25.8), (6, 2.4, 3.4), (-10, 0, 0), "muzzle")
    for s in (1, -1):
        add("Muzzle", f"incisive_{'droite' if s > 0 else 'gauche'}", (0.75 * s, 24.0, -27.9), (1.2, 1.8, 0.8), (-5, 0, 0), "tooth")
    add("Nose", "nez", (0, 29.0, -28.8), (3, 2, 1.2), (-5, 0, 0), "nose")
    add("Head", "gueule", (0, 24.5, -24.6), (6.5, 2.4, 4.5), (0, 0, 0), "mouth")
    add("Jaw", "menton", (0, 23.6, -24.6), (7, 2.6, 4), (6, 0, 0), "chin")
    for s, side, eye, bone in ((1, "droit", "eye_r", "right"), (-1, "gauche", "eye_l", "left")):
        add(f"Eye_{bone}", f"oeil_{side}", (8.2 * s, 33.2, -25.1), (3, 3, 0.8), (0, -14 * s, 0), eye)
        # eyelid: a thin strip of fur over the eye, scaled down over it to close the eye (scale y up to LID_CLOSED)
        add(f"Eyelid_{bone}", f"paupiere_{side}", (8.2 * s, 34.55, -25.3), (3.4, 0.4, 0.9), (0, -14 * s, 0), "lid")

    # ---------------------------------------------------------------- cheeks: three levels each, the signature
    for s, side, bone in ((1, "droite", "Cheek_right"), (-1, "gauche", "Cheek_left")):
        full = [
            (f"joue_{side}", (12.4 * s, 25.2, -20.8), (10, 10.5, 11.5), (0, -6 * s, -4 * s)),
            (f"joue_{side}_bas", (12.8 * s, 21.2, -20.0), (8.5, 5.5, 9), (0, -4 * s, 5 * s)),
            (f"joue_{side}_haut", (11.0 * s, 30.2, -21.2), (7, 3.5, 8), (0, -5 * s, -10 * s)),
            # front of the pouch, joining the muzzle (part of the face, not two balls stuck on it)
            (f"joue_{side}_avant", (7.6 * s, 24.8, -25.6), (7, 8.5, 4), (0, -8 * s, 0)),
        ]
        for name, c, size, r in full:
            add(bone, name, c, size, r, "cheek")
        # empty cheeks: the main volume and the front, reduced towards the attach point of the cheek
        pv = (8.5 * s, 25.5, -21)
        for name, c, size, r in (full[0], full[3]):
            k = EMPTY_CHEEK
            add(bone + "_empty", name + "_vide", tuple(pv[i] + k * (c[i] - pv[i]) for i in range(3)),
                tuple(v * k for v in size), r, "cheek")

    # ---------------------------------------------------------------- ears: small, round, leaning out
    for s, side, bone in ((1, "droite", "Ear_right"), (-1, "gauche", "Ear_left")):
        add(bone, f"oreille_{side}", (9.2 * s, 43.0, -13.5), (5.6, 5.6, 3.6), (-6, -10 * s, -12 * s), "ear")

    # ---------------------------------------------------------------- glowing marks (emissive)
    add("glow_head", "marque_front", (0, 37.0, -26.55), (2.2, 2.2, 0.3), (6, 0, 45), "glow")
    for i, z in enumerate((2.0, 7.0, 12.0)):
        y = 28.95 + (z - 7.5) * 0.105            # on the top of dos_haut, which rises towards the rear
        add("glow_back", f"marque_dos_{i + 1}", (0, y, z), (1.6, 0.3, 1.6), mul(rot(-6, 0, 0), rot(0, 45, 0)),
            "glow")

    # ---------------------------------------------------------------- legs: short, thick, under the body
    for s, side in ((1, "right"), (-1, "left")):
        fg, bg = f"Leg_front_{side}", f"Leg_back_{side}"
        add(fg, "patte", (10.5 * s, 4.4, -9), (5.6, 7.5, 6.4), (0, 0, -5 * s), "leg")
        add(fg, "pied", (10.9 * s, 1.0, -11.2), (5.8, 2, 4.5), (0, -4 * s, -5 * s), "front_paw")
        add(bg, "patte", (12.2 * s, 4.6, 17), (6, 7.5, 6.6), (0, 0, -8 * s), "leg")
        add(bg, "pied", (12.8 * s, 1.0, 14.6), (6.2, 2, 7), (0, -8 * s, -8 * s), "back_paw")
    return P


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = anims_dir = None
    show = "--mesures" in args
    if show:
        args.remove("--mesures")
    only = None
    if "--only" in args:
        i = args.index("--only")
        only = set(args[i + 1].split(","))
        del args[i:i + 2]
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
    uv, atlases, sizes = bake(P, MATS, GLOW_MATS)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    import hamster_moissonneur_anim as HA
    anims = HA.build(data, HA.uid_for(NS, IDENT))
    print(f"{len(P)} volumes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; "
          f"textures {sizes[0]} et {sizes[1]} ; {len(anims)} animations")
    if anims_dir:
        HA.strips(data, anims, atlases, sizes, anims_dir, only=only)
    if show:
        for name, rows in HA.measure(data, anims).items():
            if only and name not in only:
                continue
            print(f"--- {name}")
            for r in rows:
                print(f"  tick {r['tick']:3d}  bas {r['low']:6.2f}  haut {r['high']:5.2f}  bouche {r['mouth']}")
    if prev:
        apercu(data, atlases, sizes, prev, set(GLOW), views=((35, 12), (0, 4), (90, 2), (0, 80), (215, 14)), size=380)
    data["animations"] = HA.finish(anims)
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path, f"({os.path.getsize(path) // 1024} Ko)")
