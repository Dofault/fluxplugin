#!/usr/bin/env python3
"""Herbalist Fox (Renard herboriste): forest creature, STATIC Blockbench model (.bbmodel) (2026-11-11).

A real quadruped fox, never humanoid: compact slightly rounded body, short sturdy legs with dark socks, a large
expressive head with a pointed cream snout, tall asymmetric ears, and a very full tail (the signature) rising then
falling, cream at the tip. Herbalist identity carried by a few modelled accessories only: a leather satchel on the
right flank (flap, brass buckle, front pocket with a leaf motif, stitches) held by a strap over the back, a small
leather holder with two vials (green, amber) on the left flank, a cord collar with a wooden amulet (glowing green
core), a leaf and a gold pendant, a small leaf tucked by the left ear and a leaf mark painted on the forehead.

Built in the language of the Sea Otter and the Abyss Colossus: a few large boxes per mass, rounded by smaller boxes
tilted a few degrees; the tail is a chain of overlapping segments, each doubled by a box turned 45 degrees around its
axis (octagonal, fluffy section). Painted in plain pixel art at 1 texel per unit (16 per block): flat palette tones
dithered pixel by pixel, the tone chosen by the face (top lighter, front main, sides and back darker, bottom darkest),
no soft gradient. Only the amulet core glows (emissive texture).

Pose: standing on its four legs, stopped mid-walk (left foreleg a little forward), body leaning slightly forward,
head turned a little to its left and bent towards the ground, ears up, tail raised and curving. Pose baked in the
boxes, bones placed at the joints for a later animation; no animation.

Usage: renard_herboriste.py [output folder] [--apercu <sheet.png>]
Front towards north (-z), the creature's right towards +x, 16 units = 1 block, body centred on the origin.
"""
import json
import math
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, export, ground, hx  # noqa: E402
from chauve_souris import FACE, add_v, along, fur, norm, pick, solid, sub  # noqa: E402
from golem_fonderie import mul, rot  # noqa: E402

IDENT = "renard_herboriste"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000f0c5e11")


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- palettes (brief, dark to light)
ORANGE = pal("#874024", "#A84E28", "#B85B2C", "#C96B32", "#E58A43")
CREAM = pal("#C7AA7E", "#E8D4A8", "#F2E4C5")
TAILC = pal("#E8D4A8", "#F0E4C8", "#FAF4E7")
PAW = pal("#30251F", "#44342D", "#5A463B")
EAR_IN = pal("#A9634D", "#D99A79")
NOSE = hx("#302521")
EYE = {"rim": hx("#3C241B"), "amber": hx("#B87832"), "pupil": hx("#24150F"), "glint": hx("#F5D99A")}
LEATHER = pal("#4C3328", "#765039", "#A8794D")
STITCH = hx("#C6A477")
BRASS = pal("#8C6A2E", "#C99B43", "#E9C66E")
WOOD = pal("#5E3B26", "#8A5939", "#A8744C")
LEAF = pal("#365F35", "#5E984C", "#93B85A")
MAGIC = pal("#285A37", "#5F9848", "#A5D86C", "#D2F49B")
GOLD = pal("#9C7430", "#C99B43", "#E9C66E")
VIAL_G = {"glass": hx("#78A96B"), "liquid": hx("#A6D978"), "deep": hx("#86BC5E"), "shine": hx("#DDF2C4")}
VIAL_A = {"glass": hx("#B77A42"), "liquid": hx("#E5B95D"), "deep": hx("#C99A45"), "shine": hx("#F6E2B0")}
CORK = pal("#8C603B", "#A67B49", "#BF9562")


# --------------------------------------------------------------------------- painters
def two_tone(top, bottom, split, base_t, base_b, spread=0.4):
    """Lower leg: orange fur on the upper rows, dark sock below; the border is broken pixel by pixel."""
    def f(w, h, r, face):
        off = FACE[face] * 0.6
        grid = []
        for y in range(h):
            row = []
            for x in range(w):
                cut = split + r.choice((-1, 0, 0, 1)) / max(1, h)
                low = face == "down" or y / max(1, h) >= cut
                row.append(pick(bottom, base_b + off * 0.5, r, spread) if low else pick(top, base_t + off, r, spread))
            grid.append(row)
        return grid
    return f


def forehead(w, h, r, face):
    """Forehead plate (2 texels per unit): orange fur; on the front, a small stylised leaf in deep and light green
    pixels (vertical vein, two lobes), discreet, not glowing."""
    grid = fur(ORANGE, 3.1)(w, h, r, face)
    if face == "north" and w >= 5 and h >= 5:
        cx = w // 2
        for (dx, dy), c in (((0, 0), MAGIC[2]), ((0, 1), MAGIC[0]), ((-1, 1), MAGIC[1]), ((1, 1), MAGIC[2]),
                            ((0, 2), MAGIC[0]), ((-1, 2), MAGIC[1]), ((1, 2), MAGIC[1]), ((0, 3), MAGIC[0])):
            y = (h - 4) // 2 + dy
            if 0 <= y < h:
                grid[y][cx + dx] = c
    return grid


def muzzle(w, h, r, face):
    """Cream snout: light front and top, beige shade on the sides and below."""
    v = {"north": 1.8, "up": 1.9, "down": 0.0}.get(face, 1.0)
    return [[pick(CREAM, v, r, 0.45) for _ in range(w)] for _ in range(h)]


def nose(w, h, r, face):
    """Black nose, two darker nostril pixels on the front, one light glint on top."""
    grid = [[NOSE if r.random() < 0.85 else PAW[0] for _ in range(w)] for _ in range(h)]
    if face == "north" and w >= 4 and h >= 2:
        grid[h - 1][1] = grid[h - 1][w - 2] = PAW[0]
        grid[0][w // 2] = PAW[2]
    return grid


def eye(w, h, r, face):
    """Eye (3 texels per unit): very dark brown with an amber ring, a darker pupil, one warm glint high on the
    inner side. Not oversized: the box is 0.15 block wide."""
    if face != "north":
        return [[EYE["rim"]] * w for _ in range(h)]
    grid = []
    for y in range(h):
        row = []
        for x in range(w):
            edge = x in (0, w - 1) or y in (0, h - 1)
            inner = 2 <= x <= w - 3 and 1 <= y <= h - 2
            row.append(EYE["rim"] if edge else (EYE["pupil"] if inner and 2 <= x <= w - 4 and y >= 2
                                               else EYE["amber"] if y >= h - 3 else EYE["rim"]))
        grid.append(row)
    grid[1][2] = EYE["glint"]
    grid[1][3] = EYE["glint"]
    grid[2][2] = EYE["glint"]
    return grid


def ear_inner(w, h, r, face):
    """Inside of the ear: pinkish cream, shadow pixels at the bottom and along the edges."""
    grid = []
    for y in range(h):
        row = []
        for x in range(w):
            v = 1.0 - (0.9 if y > h * 0.7 or x in (0, w - 1) else 0.0)
            row.append(pick(EAR_IN, v, r, 0.35))
        grid.append(row)
    return grid


def leather(base, stitches=True, motif=None):
    """Leather (2 texels per unit): dithered brown, a darker pixel here and there, a dashed light seam one pixel in
    from the border of the large faces; `motif` paints a small botanical leaf on the outer (east) face."""
    def f(w, h, r, face):
        off = FACE[face] * 0.5
        grid = [[pick(LEATHER, base + off - (0.8 if r.random() < 0.05 else 0), r, 0.35) for _ in range(w)]
                for _ in range(h)]
        if stitches and w >= 6 and h >= 5 and face in ("east", "west", "up"):
            for x in range(1, w - 1):
                if x % 2:
                    grid[1][x] = STITCH
                    grid[h - 2][x] = STITCH
            for y in range(1, h - 1):
                if y % 2:
                    grid[y][1] = STITCH
                    grid[y][w - 2] = STITCH
        if motif and face == motif and w >= 7 and h >= 7:
            cx, cy = w // 2, h // 2
            for (dx, dy), c in (((0, -2), LEAF[2]), ((-1, -1), LEAF[1]), ((0, -1), LEAF[2]), ((1, -1), LEAF[1]),
                                ((-1, 0), LEAF[1]), ((0, 0), LEAF[0]), ((1, 0), LEAF[2]), ((0, 1), LEAF[0]),
                                ((0, 2), LEAF[0]), ((-2, 0), LEAF[2]), ((2, -1), LEAF[2])):
                if 0 <= cy + dy < h and 0 <= cx + dx < w:
                    grid[cy + dy][cx + dx] = c
        return grid
    return f


def strap(w, h, r, face):
    """Strap: dark leather, a dashed light seam down the middle of its outer face."""
    grid = [[pick(LEATHER, 0.9 + FACE[face] * 0.3, r, 0.35) for _ in range(w)] for _ in range(h)]
    if face == "up" and w >= 2:
        for y in range(0, h, 3):
            grid[y][w // 2] = STITCH
    return grid


def vial(c):
    def f(w, h, r, face):
        """Vial (3 texels per unit): glass on the top rows and the edges, liquid below, a light vertical shine."""
        if face in ("up", "down"):
            return [[c["glass"] if r.random() < 0.7 else c["deep"] for _ in range(w)] for _ in range(h)]
        grid = []
        for y in range(h):
            row = []
            for x in range(w):
                if y < h * 0.3 or x in (0, w - 1):
                    row.append(c["glass"])
                else:
                    row.append(c["deep"] if y > h * 0.8 else c["liquid"])
            grid.append(row)
        for y in range(1, h - 1):
            grid[y][1] = c["shine"] if y % 3 else c["glass"]
        return grid
    return f


def amulet(w, h, r, face):
    """Wooden amulet (3 texels per unit): carved wood, a darker ring around the hole of the glowing core."""
    grid = [[pick(WOOD, 1.2 + FACE[face] * 0.5, r, 0.4) for _ in range(w)] for _ in range(h)]
    if face == "north" and w >= 5 and h >= 5:
        for x in range(1, w - 1):
            grid[1][x] = grid[h - 2][x] = WOOD[0]
        for y in range(1, h - 1):
            grid[y][1] = grid[y][w - 2] = WOOD[0]
    return grid


def core(w, h, r, face):
    """Glowing core of the amulet (emissive): light green with a pale centre pixel."""
    grid = [[MAGIC[2] if r.random() < 0.75 else MAGIC[1] for _ in range(w)] for _ in range(h)]
    if face == "north":
        grid[h // 2][w // 2] = MAGIC[3]
    return grid


MATS = {
    # fur: back lighter, flanks light on top and shaded towards the belly, belly cream
    "body": fur(ORANGE, 3, belly=True), "body_dark": fur(ORANGE, 2.3, belly=True),
    "back": fur(ORANGE, 3.5, tufts=0.03), "rump": fur(ORANGE, 2.9, tufts=0.03),
    "belly": fur(CREAM, 1.2, spread=0.4, dots=0.02), "bib": fur(CREAM, 1.5, spread=0.4, dots=0.02),
    "head": fur(ORANGE, 3.1), "head_top": fur(ORANGE, 3.6), "forehead": (forehead, 3),
    "cheek": fur(CREAM, 1.3, spread=0.4, dots=0.02), "ruff": fur(CREAM, 1.5, spread=0.45, tufts=0.04),
    "muzzle": muzzle, "muzzle_top": fur(ORANGE, 2.9, dots=0.02), "nose": (nose, 2), "eye": (eye, 3),
    "brow": (solid(ORANGE, 1.0, 0.3), 2),
    "ear": fur(ORANGE, 3.0, spread=0.45, dots=0.03), "ear_dark": solid(ORANGE, 1.2, 0.4, lit=True),
    "ear_in": (ear_inner, 2), "ear_tip": (solid(PAW, 1.2, 0.4), 2),
    "leg": fur(ORANGE, 2.8, dots=0.03), "leg_low": two_tone(ORANGE, PAW, 0.45, 2.4, 1.0),
    "paw": solid(PAW, 1.0, 0.4, lit=True), "toe": (solid(PAW, 1.6, 0.4), 2), "toe_cream": (solid(CREAM, 1.2, 0.3), 2),
    "tail": fur(ORANGE, 3.0, tufts=0.05), "tail_light": fur(ORANGE, 3.4, tufts=0.06),
    "tail_tip": fur(TAILC, 1.2, spread=0.5, dots=0.02, tufts=0.05), "tail_point": fur(TAILC, 1.8, spread=0.4, tufts=0.05),
    "bag": (leather(1.2), 2), "bag_flap": (leather(0.8), 2),
    "pocket": (leather(1.4, stitches=False, motif="east"), 3), "strap": (strap, 2), "holder": (leather(0.7, stitches=False), 2),
    "brass": (solid(BRASS, 1.2, 0.4, lit=True), 2),
    "vial_g": (vial(VIAL_G), 3), "vial_a": (vial(VIAL_A), 3), "cork_g": (solid(CORK, 1.2, 0.4), 3),
    "cork_a": (solid(CORK, 0.4, 0.4), 3),
    "cord": (solid(LEATHER, 0.9, 0.35), 2), "amulet": (amulet, 3), "core": (core, 3),
    "leaf": (solid(LEAF, 1.1, 0.45, lit=True), 2), "leaf_light": (solid(LEAF, 1.8, 0.4, lit=True), 2),
    "gold": (solid(GOLD, 1.2, 0.4, lit=True), 3),
}
GLOW = {"core": (core, 3)}
GLOW_COLOURS = set(MAGIC)


# --------------------------------------------------------------------------- skeleton (drawing units)
BODY_PIVOT = (0, 11.5, 0)
NECK = (0, 15.2, -10.0)
TAIL_ROOT = (0, 13.6, 10.8)
TILT = rot(-2, 0, 0)                 # whole body leans a little forwards
HEAD_POSE = rot(-7, 12, 3)           # bent towards the ground, turned to its left, slight roll

GROUPS = {"Root": ((0, 0, 0), None), "Body": (BODY_PIVOT, "Root")}


def parts():
    P = []
    on_body = attach(BODY_PIVOT, TILT)
    on_head_raw = attach(NECK, HEAD_POSE)

    def add(g, name, c, size, r, mat):
        P.append((g, name, list(c), list(size), r, mat))

    def bd(g, name, c, size, r, mat):
        cc, M = on_body(c, r)
        add(g, name, cc, size, M, mat)

    def on_head(c, r):
        return on_body(*on_head_raw(c, r))

    def hd(name, c, size, r, mat, bone="Head"):
        cc, M = on_head(c, r)
        add(bone, name, cc, size, M, mat)

    def seg_matrix(a, b, hint=(0, 1, 0), spin=0.0):
        M = along(sub(b, a), hint)
        return mul(M, rot(0, 0, spin)) if spin else M

    # ---------------------------------------------------------------- body: chest, waist, hips (rear a bit bigger)
    bd("Body", "poitrine", (0, 11.7, -7.4), (9.6, 8.4, 9.4), (0, 0, 0), "body")
    bd("Body", "taille", (0, 11.9, 0), (9.2, 7.8, 9.0), (0, 0, 0), "body")
    bd("Body", "hanches", (0, 11.7, 7.2), (10.4, 8.8, 8.8), (0, 0, 0), "body")
    # back slightly domed: a lighter ridge and a thin dome on top, rump rounded at the rear
    bd("Body", "dos", (0, 15.9, -0.4), (7.4, 1.4, 21), (0, 0, 0), "back")
    bd("Body", "dos_bombe", (0, 16.8, 1.0), (5.2, 0.8, 11), (0, 0, 0), "back")
    bd("Body", "croupe", (0, 14.4, 11.0), (8.6, 3.4, 3.4), (-38, 0, 0), "rump")
    bd("Body", "garrot", (0, 15.4, -10.2), (8.0, 2.6, 4.0), (22, 0, 0), "back")
    # flanks: shoulder and haunch bulges, the haunch larger (natural anatomy, slightly heavier rear)
    for s in (1, -1):
        sd = "d" if s == 1 else "g"
        bd("Body", f"epaule_{sd}", (4.8 * s, 11.6, -7.6), (1.6, 5.6, 5.6), (0, 0, 4 * s), "body_dark")
        bd("Body", f"cuisse_haute_{sd}", (5.0 * s, 11.9, 7.4), (1.8, 6.6, 6.8), (0, 0, 3 * s), "body")
        bd("Body", f"flanc_bas_{sd}", (3.9 * s, 8.0, 0.4), (2.4, 2.4, 17), (0, 0, -32 * s), "belly")
    # cream underside and chest bib (a fox's white chest)
    bd("Body", "ventre", (0, 7.2, 0.4), (7.0, 1.2, 19), (0, 0, 0), "belly")
    bd("Body", "plastron", (0, 10.8, -12.2), (6.2, 6.6, 1.8), (-10, 0, 0), "bib")
    bd("Body", "plastron_bas", (0, 7.8, -10.8), (6.0, 2.2, 3.0), (30, 0, 0), "bib")

    # ---------------------------------------------------------------- neck and collar
    GROUPS["Neck"] = (on_body(NECK, (0, 0, 0))[0], "Body")
    neck_c = (0, 15.0, -11.0)
    on_neck = attach(neck_c, rot(-28, 0, 0))

    def nk(name, c, size, mat):
        cc, M = on_body(*on_neck(c, (0, 0, 0)))
        add("Neck", name, cc, size, M, mat)
    nk("cou", neck_c, (7.6, 6.4, 5.6), "body")
    nk("cou_gorge", (0, 14.4, -13.6), (5.8, 5.4, 1.2), "bib")
    # cord collar: a thin band hugging the neck, three small pieces hanging at the front
    nk("collier", (0, 13.6, -11.0), (8.0, 0.8, 6.0), "cord")
    nk("collier_noeud", (0, 13.4, -14.15), (1.4, 1.0, 0.5), "cord")
    nk("amulette", (0, 12.0, -14.55), (2.0, 2.4, 0.6), "amulet")
    nk("amulette_coeur", (0, 12.0, -14.9), (0.8, 0.8, 0.3), "core")
    nk("amulette_lien", (0, 13.3, -14.45), (0.4, 0.8, 0.3), "cord")
    nk("feuille_collier", (-1.9, 12.6, -14.3), (1.0, 2.0, 0.4), "leaf")
    nk("feuille_collier_pointe", (-2.2, 11.4, -14.25), (0.6, 0.8, 0.3), "leaf_light")
    nk("pendentif_or", (1.8, 12.9, -14.3), (0.8, 1.0, 0.4), "gold")

    # ---------------------------------------------------------------- head: large, expressive
    GROUPS["Head"] = (on_body(NECK, (0, 0, 0))[0], "Neck")
    hd("crane", (0, 19.6, -14.0), (10.0, 9.0, 10.0), (0, 0, 0), "head")
    hd("crane_dessus", (0, 24.4, -13.6), (8.0, 1.2, 8.2), (0, 0, 0), "head_top")
    hd("crane_arriere", (0, 19.4, -8.6), (8.4, 7.6, 1.2), (0, 0, 0), "head")
    for s in (1, -1):
        sd = "d" if s == 1 else "g"
        hd(f"crane_arrondi_{sd}", (4.6 * s, 23.4, -13.8), (2.4, 2.6, 8.4), (0, 0, -32 * s), "head_top")
        # cheeks: cream, a little wider than the skull, with a fluffy ruff swept backwards
        hd(f"joue_{sd}", (3.9 * s, 16.2, -14.8), (2.6, 4.2, 7.2), (0, 0, 0), "cheek")
        hd(f"collerette_{sd}", (4.5 * s, 15.4, -10.8), (1.6, 3.0, 3.0), (0, -10 * s, -8 * s), "ruff")
    hd("front", (0, 22.3, -19.1), (3.0, 2.4, 0.4), (0, 0, 0), "forehead")
    # snout: orange bridge above, cream sides and jaw, tapering to a small triangular black nose
    hd("museau", (0, 16.4, -21.0), (5.0, 3.6, 4.4), (0, 0, 0), "muzzle")
    hd("museau_arete", (0, 18.5, -20.6), (3.8, 1.4, 4.0), (-4, 0, 0), "muzzle_top")
    hd("museau_bout", (0, 16.8, -24.0), (3.0, 2.6, 2.4), (0, 0, 0), "muzzle")
    hd("museau_arete_bout", (0, 18.2, -23.6), (2.2, 0.9, 2.2), (-8, 0, 0), "muzzle_top")
    hd("machoire", (0, 14.6, -20.4), (3.8, 1.2, 4.4), (0, 0, 0), "cheek")
    hd("nez", (0, 17.8, -25.45), (2.2, 1.4, 1.1), (0, 0, 0), "nose")
    hd("nez_pointe", (0, 17.15, -25.4), (1.0, 1.0, 0.9), (0, 0, 45), "nose")
    # eyes either side of the bridge, discreet brows (left one raised: curiosity)
    for s, brow_y, brow_r in ((1, 21.0, -6), (-1, 21.35, -14)):
        sd = "d" if s == 1 else "g"
        hd(f"oeil_{sd}", (3.0 * s, 19.6, -19.2), (2.4, 2.0, 0.6), (0, 8 * s, 0), "eye")
        hd(f"sourcil_{sd}", (3.0 * s, brow_y, -19.35), (2.4, 0.5, 0.5), (0, 8 * s, brow_r * -s), "brow")

    # ---------------------------------------------------------------- ears: tall, triangular, asymmetric
    # left ear 8 degrees outwards; right ear 12 degrees forwards and a little outwards (brief)
    for s, ear_rot in ((1, rot(-12, 0, -9)), (-1, rot(-4, 0, 13))):
        sd = "d" if s == 1 else "g"
        bone = f"Ear_{'right' if s == 1 else 'left'}"
        base = (3.4 * s, 24.4, -14.4)
        GROUPS[bone] = (on_head(base, (0, 0, 0))[0], "Head")
        on_ear = attach(base, ear_rot)

        def er(name, c, size, mat, r=(0, 0, 0)):
            cc, M = on_head(*on_ear(c, r))
            add(bone, name, cc, size, M, mat)

        # a true triangle: two edges leaning towards each other meet at the point, a fill below, the pinkish
        # inside recessed on the front, a dark point (height 0.37 block, width 0.32 at the base)
        H, W, D, E = 6.0, 2.6, 1.3, 1.4
        x0, y0, z0 = base[0], base[1] - 0.4, base[2]
        L_ = math.hypot(W, H)
        ang = math.degrees(math.atan2(W, H))
        for e, side in ((1, "a"), (-1, "b")):
            # edge from the outer base corner towards the point, stopped short of it (the dark point closes it)
            t = 0.47
            cx = x0 + e * (W * (1 - t) - E / 2 * H / L_)
            cy = y0 + H * t - E / 2 * W / L_
            er(f"oreille_{sd}_arete_{side}", (cx, cy, z0), (E, L_ * 0.94, D), "ear", (0, 0, e * ang))
        er(f"oreille_{sd}_plein", (x0, y0 + H * 0.24, z0), (W * 1.2, H * 0.48, D), "ear")
        er(f"oreille_{sd}_interieur", (x0, y0 + H * 0.24, z0 - D / 2 - 0.1), (W * 0.9, H * 0.36, 0.3), "ear_in")
        er(f"oreille_{sd}_interieur_haut", (x0, y0 + H * 0.48, z0 - D / 2 - 0.1), (W * 0.45, H * 0.26, 0.3), "ear_in")
        er(f"oreille_{sd}_pointe", (x0, y0 + H - 0.7, z0), (1.1, 1.1, D + 0.1), "ear_tip", (0, 0, 45))

    # signature leaf tucked at the base of the left ear, sticking out of its outline
    leaf_root = (-5.6, 24.8, -13.6)
    on_leaf = attach(leaf_root, rot(-10, -20, 38))
    for name, c, size, mat in (("feuille_tige", (-5.6, 24.8, -13.6), (0.5, 1.6, 0.5), "leaf"),
                               ("feuille_limbe", (-5.6, 26.6, -13.6), (1.6, 2.4, 0.5), "leaf"),
                               ("feuille_limbe_clair", (-5.6, 26.9, -13.95), (0.8, 1.8, 0.3), "leaf_light"),
                               ("feuille_pointe", (-5.6, 28.2, -13.6), (0.8, 1.0, 0.5), "leaf_light")):
        cc, M = on_head(*on_leaf(c, (0, 0, 0)))
        add("Ear_left", name, cc, size, M, mat)

    # ---------------------------------------------------------------- tail: the signature volume
    # chain of overlapping segments: base level, middle rising, upper going back, tip falling; drifts a little
    # to the right (the head looks left), thickest towards the end, cream tip
    pts = [(0, 13.4, 10.6), (0.3, 15.2, 14.8), (1.0, 18.6, 19.4), (1.8, 20.4, 24.2), (2.3, 19.6, 27.8),
           (2.5, 17.6, 30.0)]
    specs = (("Tail_base", 5.0, "tail"), ("Tail_mid", 6.8, "tail_light"), ("Tail_mid", 7.2, "tail"),
             ("Tail_tip", 7.0, "tail_tip"), ("Tail_tip", 4.6, "tail_point"))
    GROUPS["Tail_base"] = (on_body(TAIL_ROOT, (0, 0, 0))[0], "Body")
    GROUPS["Tail_mid"] = (on_body(pts[1], (0, 0, 0))[0], "Tail_base")
    GROUPS["Tail_tip"] = (on_body(pts[3], (0, 0, 0))[0], "Tail_mid")
    for k, (bone, w, mat) in enumerate(specs):
        a, b = pts[k], pts[k + 1]
        ln = math.dist(a, b) + 2.2
        mid = [(a[i] + b[i]) / 2 for i in range(3)]
        bd(bone, f"queue_{k + 1}", mid, (w, w * 0.92, ln), seg_matrix(a, b), mat)
        bd(bone, f"queue_{k + 1}_rond", mid, (w * 0.86, w * 0.86, ln - 0.6), seg_matrix(a, b, spin=45), mat)
    # a few tufts of fur on the tip, and a lighter streak on top of the middle
    for name, c, size, r in (("touffe_queue_1", (2.6, 22.4, 25.6), (2.0, 1.6, 2.4), (-20, 0, 12)),
                             ("touffe_queue_2", (5.0, 19.6, 28.0), (1.6, 2.2, 2.0), (0, 20, -24)),
                             ("touffe_queue_3", (2.6, 16.2, 31.2), (2.0, 1.8, 1.8), (30, 0, 8))):
        bd("Tail_tip", name, c, size, r, "tail_point")
    bd("Tail_mid", "queue_crete", (1.0, 22.0, 20.4), (3.4, 1.0, 6.0), seg_matrix(pts[1], pts[3]), "tail_light")

    # ---------------------------------------------------------------- herbalist satchel (right flank)
    GROUPS["Bag"] = (on_body((5.4, 12.6, 0), (0, 0, 0))[0], "Body")
    on_bag = attach((5.6, 9.6, -0.4), rot(0, 0, 6))

    def bg(name, c, size, mat):
        cc, M = on_body(*on_bag(c, (0, 0, 0)))
        add("Bag", name, cc, size, M, mat)
    bg("sac", (5.9, 9.4, -0.4), (2.4, 4.4, 5.6), "bag")
    bg("sac_rabat", (6.15, 11.3, -0.4), (2.4, 1.4, 5.9), "bag_flap")
    bg("sac_rabat_devant", (7.3, 10.6, -0.4), (0.4, 1.4, 5.0), "bag_flap")
    bg("sac_poche", (7.3, 8.6, -0.4), (0.6, 2.6, 3.6), "pocket")
    bg("sac_boucle", (7.6, 10.4, -0.4), (0.4, 0.9, 1.0), "brass")
    bg("sac_fond", (5.8, 7.1, -0.4), (2.0, 0.4, 5.2), "holder")
    # strap over the back to the vial holder on the left flank (follows the curve of the back)
    sp = [(5.0, 12.4, -0.2), (4.4, 15.2, -0.9), (2.0, 17.6, -2.0), (-2.0, 17.6, -3.4), (-4.5, 15.2, -4.6),
          (-5.1, 12.2, -5.4)]
    for k in range(len(sp) - 1):
        a, b = sp[k], sp[k + 1]
        mid = [(a[i] + b[i]) / 2 for i in range(3)]
        nrm = norm(sub(mid, (0, 11.5, mid[2])))
        bd("Bag", f"bandouliere_{k + 1}", mid, (1.2, 0.5, math.dist(a, b) + 0.6), along(sub(b, a), nrm), "strap")
    # vial holder: a leather patch at the end of the strap with two loops, a green and an amber vial
    bd("Bag", "porte_fioles", (-5.2, 10.6, -5.6), (0.6, 2.6, 4.4), (0, 0, 4), "holder")
    for k, (z, mat, cork) in enumerate(((-6.8, "vial_g", "cork_g"), (-4.4, "vial_a", "cork_a"))):
        tilt = (0, 0, 6 + 4 * k)
        bd("Bag", f"fiole_{k + 1}", (-6.05, 11.5, z), (1.4, 2.7, 1.4), tilt, mat)
        bd("Bag", f"fiole_{k + 1}_bouchon", (-6.2, 13.1, z), (0.9, 0.7, 0.9), tilt, cork)
        bd("Bag", f"fiole_{k + 1}_passant", (-5.7, 10.7, z), (0.5, 0.6, 1.5), (0, 0, 4), "strap")
    bd("Bag", "porte_fioles_boucle", (-5.55, 12.2, -5.4), (0.3, 0.8, 0.8), (0, 0, 4), "brass")

    # ---------------------------------------------------------------- legs: short, sturdy, three segments
    def post(bone, name, a, b, w, d, mat):
        """Box standing from joint a down to joint b, its top face kept upwards (light from above)."""
        y = norm(sub(a, b))
        x = norm(sub((1, 0, 0), [c * y[0] for c in y]))
        z = [x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0]]
        M = [[x[i], y[i], z[i]] for i in range(3)]
        add(bone, name, [(a[i] + b[i]) / 2 for i in range(3)], (w, math.dist(a, b) + 1.2, d), M, mat)

    legs = {
        # bone: (top joint, middle joint, ankle, upper w/d, front leg?)
        "Leg_front_right": ((3.3, 10.4, -7.6), (3.5, 5.4, -7.0), (3.4, 1.9, -7.4), (3.4, 3.6), True),
        "Leg_front_left": ((-3.3, 10.4, -7.8), (-3.5, 5.6, -8.6), (-3.4, 1.9, -9.6), (3.4, 3.6), True),
        "Leg_back_right": ((3.6, 11.4, 7.4), (3.8, 6.6, 5.0), (3.7, 1.9, 7.6), (4.0, 4.8), False),
        "Leg_back_left": ((-3.6, 11.4, 7.6), (-3.8, 6.6, 5.6), (-3.7, 1.9, 8.4), (4.0, 4.8), False),
    }
    for bone, (top, knee, ankle, (uw, ud), front) in legs.items():
        sd = ("av" if front else "ar") + ("_d" if top[0] > 0 else "_g")
        top_w = on_body(top, (0, 0, 0))[0]
        GROUPS[bone] = (top_w, "Body")
        post(bone, f"patte_haut_{sd}", top_w, knee, uw, ud, "leg")
        post(bone, f"patte_bas_{sd}", knee, ankle, 2.6, 2.6, "leg_low")
        # wide paw reaching forwards, two small toes and a cream fleck on the front
        foot = add_v(ankle, (0, -1.1, -0.9))
        add(bone, f"pied_{sd}", foot, (3.2, 1.6, 4.2), (0, 0, 0), "paw")
        for t, dx in enumerate((-0.8, 0.8)):
            add(bone, f"orteil_{sd}_{t + 1}", add_v(foot, (dx, -0.25, -2.2)), (1.3, 1.1, 0.8), (0, 0, 0), "toe")
        add(bone, f"pied_creme_{sd}", add_v(foot, (0, 0.85, -1.2)), (1.2, 0.3, 1.2), (0, 0, 0), "toe_cream")
    return P


def build():
    raw = parts()
    return ground(raw, GROUPS, center=False)


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
    print(f"{len(P)} volumes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (larg x haut x long) ; "
          f"textures {sizes[0]} + {sizes[1]}")
    if prev:
        apercu(data, atlases, sizes, prev, GLOW_COLOURS, views=((35, 12), (0, 4), (90, 3), (200, 10)), size=420)
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
