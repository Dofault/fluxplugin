#!/usr/bin/env python3
"""Welcome robot: the server mascot that greets new players, STATIC Blockbench model (.bbmodel) (2026-11-14).

A small mechanical robot, 2.8 blocks tall: a big boxy head (about 40 % of the height) with one large turquoise lens,
a short yellow antenna, a compact petrol blue body with a dark screen showing a yellow heart, two short two-segment
arms and two short legs on wide feet. 30 cubes, every one with a visual job.

Pose baked in the cubes (head 3 degrees forward, body 2 degrees forward, antenna 8 degrees back, arms 12 degrees
outwards, feet 5 degrees outwards); bones placed at the joints for the later animations (walk, run, wave, head turn,
idle).

Hierarchy: Root > Body > Head > Antenna ; Body > RightArm > RightForearm ; Body > LeftArm > LeftForearm ;
Root > RightLeg ; Root > LeftLeg.

Textures: 1 texel per unit (16 texels per block), the eye and the screen at 2 texels per unit so the lens glint and
the heart stay readable. Lens, antenna tip and energy belt are on the emissive texture.

Animations (idle, walk, talk, greet) come from robot_accueil_anim.py.

Usage: robot_accueil.py [output folder] [--apercu <sheet.png>] [--anims <folder of animation strips>]
Front towards north (-z), the robot's right towards +x, ground at y = 0, 16 units = 1 block.
"""
import json
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.normpath(os.path.join(HERE, "..", "..", "..", "FluxDungeons", "assets", "monstres")))
from bbkit import apercu, attach, bake, export, ground, hx  # noqa: E402
from golem_fonderie import rot  # noqa: E402

IDENT = "robot_accueil"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-0000000b0b07")

# --------------------------------------------------------------------------- palette (imposed by the brief)
GREY, GREY_LIGHT, GREY_DARK, EDGE, SILVER = (hx(c) for c in ("#8299A5", "#B2C4C9", "#526873", "#34434A", "#D0DADD"))
EYE_RIM, EYE_RING, ENERGY, WHITE = (hx(c) for c in ("#26343B", "#C5D5D9", "#35D9C6", "#FFFFFF"))
PETROL, PETROL_LIGHT, PETROL_DARK, DETAIL = (hx(c) for c in ("#356D7A", "#5C9BA0", "#244751", "#A8BBC0"))
YELLOW, YELLOW_DARK = hx("#FFD45C"), hx("#D99A36")
ENERGY_DARK = hx("#1FA596")      # shade of the energy turquoise (belt segments, lens rim)


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


# --------------------------------------------------------------------------- painters
def metal(main, light, shadow, edge=None, rivet=None, seam=None):
    """Hand-made looking metal plate: main colour on about two thirds, light on the top edge and a few pixels under
    it, shadow on the bottom rows and one side column, crisp pixels only (no gradient). `edge`: darker outline on the
    bottom row of large faces; `rivet`: colour of the corner bolts on large side faces; `seam`: colour of a panel
    line across the face."""
    def f(w, h, r, face):
        if face == "down":
            px = [[shadow] * w for _ in range(h)]
            for x in range(w):
                if edge and r.random() < 0.25:
                    px[r.randrange(h)][x] = edge
            return px
        if face == "up":
            px = [[main] * w for _ in range(h)]
            for x in range(w):
                px[0][x] = light                                  # front edge catches the light
            for y in range(h):
                for x in range(w):
                    if y and r.random() < 0.10:
                        px[y][x] = light
                    elif y == h - 1 and h > 2:
                        px[y][x] = shadow if r.random() < 0.6 else main
            return px
        px = []
        low = max(1, round(h * 0.25))
        for y in range(h):
            row = []
            for x in range(w):
                c = main
                if y == 0:
                    c = light if (w < 4 or r.random() < 0.85) else main
                elif y == 1 and h > 5 and r.random() < 0.25:
                    c = light
                elif y >= h - low:
                    deep = (y - (h - low) + 1) / low               # more shadow towards the bottom
                    c = shadow if r.random() < 0.45 + 0.55 * deep else main
                elif x == w - 1 and w > 3:
                    c = shadow
                elif r.random() < 0.04:
                    c = shadow
                row.append(c)
            px.append(row)
        if edge and w >= 6 and h >= 6:
            for x in range(w):
                px[h - 1][x] = edge
        if seam and w >= 6 and h >= 7:
            y = h - low - 1
            for x in range(w):
                px[y][x] = seam
        if rivet and w >= 8 and h >= 8:
            for x, y in ((1, 2), (w - 2, 2), (1, h - 3), (w - 2, h - 3)):
                px[y][x] = rivet
        return px
    return f


def head_main(w, h, r, face):
    """Head casing. Front: clearly lighter than the sides, silver bolts in the corners, an anthracite outline at the
    bottom. Back: a service hatch with three vent slots. Sides, top: plain plate (the ear modules cover the middle)."""
    if face == "north":
        px = metal(mix(GREY, GREY_LIGHT, 0.45), GREY_LIGHT, GREY, edge=EDGE, rivet=SILVER)(w, h, r, face)
        for x in range(w):                                         # a silver glint line along the brow
            if 2 <= x < w - 2 and x % 3:
                px[1][x] = SILVER
        return px
    if face == "south":
        px = metal(GREY, GREY_LIGHT, GREY_DARK, edge=EDGE, rivet=SILVER)(w, h, r, face)
        x0, x1, y0, y1 = w // 2 - 4, w // 2 + 4, 4, h - 4
        for x in range(x0, x1 + 1):
            px[y0][x] = px[y1][x] = EDGE
        for y in range(y0, y1 + 1):
            px[y][x0] = px[y][x1] = EDGE
        for y in range(y0 + 2, y1 - 1, 2):
            for x in range(x0 + 2, x1 - 1):
                px[y][x] = GREY_DARK
        return px
    return metal(GREY, GREY_LIGHT, GREY_DARK, edge=EDGE)(w, h, r, face)


def ear(w, h, r, face):
    """Side module of the head: anthracite frame, dark plate, a silver bolt in the middle of the outer face."""
    if face in ("east", "west"):
        px = [[GREY_DARK] * w for _ in range(h)]
        for x in range(w):
            px[0][x], px[h - 1][x] = GREY, EDGE
        for y in range(h):
            px[y][0] = px[y][w - 1] = EDGE
        cx, cy = w // 2, h // 2
        for x, y in ((cx - 1, cy - 1), (cx, cy - 1)):
            px[y][x] = SILVER
        for x, y in ((cx - 1, cy), (cx, cy)):
            px[y][x] = GREY_LIGHT
        return px
    return metal(GREY_DARK, GREY, EDGE)(w, h, r, face)


def grille(w, h, r, face):
    """Small speaker under the eye: anthracite box, silver slots on the front."""
    if face == "north":
        px = [[EDGE] * w for _ in range(h)]
        for y in range(h):
            for x in range(1, w - 1):
                if y % 2 == 0 and y < h - 1:
                    px[y][x] = SILVER if y == 0 else GREY_LIGHT
        return px
    return metal(EDGE, GREY_DARK, EYE_RIM)(w, h, r, face)


def flat(colour, top=None, bottom=None):
    def f(w, h, r, face):
        if face == "up" and top:
            return [[top] * w for _ in range(h)]
        if face == "down" and bottom:
            return [[bottom] * w for _ in range(h)]
        px = [[colour] * w for _ in range(h)]
        if top and face != "down":
            for x in range(w):
                px[0][x] = top
        if bottom and face != "up" and h > 2:
            for x in range(w):
                px[h - 1][x] = bottom
        return px
    return f


def lens(w, h, r, face):
    """Glowing lens: turquoise, a darker rim low and right (depth), a lighter core, one white glint top left."""
    if face != "north":
        return [[ENERGY_DARK] * w for _ in range(h)]
    px = [[ENERGY] * w for _ in range(h)]
    for i in range(w):
        px[h - 1][i] = ENERGY_DARK
    for j in range(h):
        px[j][w - 1] = ENERGY_DARK
    core = mix(ENERGY, WHITE, 0.35)
    for y in range(h // 2 - 1, h // 2 + 1):
        for x in range(w // 2 - 1, w // 2 + 1):
            px[y][x] = core
    px[1][1] = px[1][2] = px[2][1] = WHITE
    return px


def ring(w, h, r, face):
    """Metal ring of the eye: light grey, silver on the top, a grey shadow at the bottom."""
    px = [[EYE_RING] * w for _ in range(h)]
    if face in ("north", "east", "west"):
        for x in range(w):
            px[0][x], px[h - 1][x] = SILVER, GREY
    return px


def screen(w, h, r, face):
    """Chest screen (2 texels per unit): grey frame, near-black glass, a yellow pixel heart with a darker lower
    half, one lit pixel. The frame is part of the cube, so the screen reads as a fitted part, not a sticker."""
    px = [[GREY] * w for _ in range(h)]
    if face != "north":
        for x in range(w):
            px[h - 1][x] = GREY_DARK
        return px
    for y in range(1, h - 1):
        for x in range(1, w - 1):
            px[y][x] = EYE_RIM
    for x in range(w):
        px[0][x] = GREY_LIGHT
        px[h - 1][x] = GREY_DARK
    heart = (".XX.XX.",
             "XXXXXXX",
             "XXXXXXX",
             ".XXXXX.",
             "..XXX..",
             "...X...")
    x0, y0 = (w - 7) // 2, (h - 6) // 2
    for j, line in enumerate(heart):
        for i, ch in enumerate(line):
            if ch == "X":
                px[y0 + j][x0 + i] = YELLOW if j < 3 else YELLOW_DARK
    px[y0 + 1][x0 + 1] = mix(YELLOW, WHITE, 0.6)
    px[2][w - 3] = GREY_DARK                                       # a faint reflection in the corner of the glass
    return px


def belt(w, h, r, face):
    """Energy belt: glowing turquoise segments separated by darker joints."""
    if face in ("up", "down"):
        return [[ENERGY_DARK] * w for _ in range(h)]
    return [[ENERGY_DARK if x % 4 == 3 else ENERGY for x in range(w)] for _ in range(h)]


def neck(w, h, r, face):
    """Neck: stacked dark rings."""
    return [[(GREY_DARK if y % 2 == 0 else EDGE) for _ in range(w)] for y in range(h)]


def pack(w, h, r, face):
    """Battery pack on the back: dark petrol box, grey cooling slots and two small turquoise status pixels."""
    px = metal(PETROL_DARK, PETROL, mix(PETROL_DARK, EDGE, 0.5))(w, h, r, face)
    if face == "south" and w >= 8 and h >= 6:
        for y in (2, 4):
            for x in range(2, w - 2):
                px[y][x] = DETAIL if y == 2 else GREY_DARK
        px[h - 2][2] = px[h - 2][w - 3] = ENERGY
    return px


def torso(w, h, r, face):
    """Torso: petrol blue plate, grey bolts, a seam above the belt; the front keeps a darker recess around the
    screen so the screen sits in the body."""
    px = metal(PETROL, PETROL_LIGHT, PETROL_DARK, rivet=DETAIL)(w, h, r, face)
    if face == "north":
        cx, cy = w // 2, h // 2
        for y in range(cy - 4, cy + 3):
            for x in range(cx - 4, cx + 4):
                if 0 <= y < h and 0 <= x < w:
                    px[y][x] = PETROL_DARK
    return px


def forearm(w, h, r, face):
    """Forearm: grey plate with a petrol blue cuff above the hand."""
    px = metal(GREY, GREY_LIGHT, GREY_DARK)(w, h, r, face)
    if face not in ("up", "down") and h >= 4:
        for x in range(w):
            px[h - 2][x] = PETROL
            px[h - 1][x] = PETROL_DARK
    return px


def hand(w, h, r, face):
    """Hand: darker than the forearm, a small light plate on the outer faces."""
    px = metal(GREY_DARK, GREY, EDGE)(w, h, r, face)
    if face in ("east", "west") and w >= 3 and h >= 3:
        px[1][1] = GREY_LIGHT
        if w >= 4:
            px[1][2] = GREY_LIGHT
    return px


def foot(w, h, r, face):
    """Foot: dark grey, a light toe cap on the front and the front of the top, anthracite sole."""
    px = metal(GREY_DARK, GREY, EDGE)(w, h, r, face)
    if face == "north":
        for x in range(1, w - 1):
            px[0][x] = GREY_LIGHT
        for x in range(w):
            px[h - 1][x] = EDGE
    elif face == "up":
        for x in range(1, w - 1):
            px[0][x] = GREY_LIGHT
            px[1][x] = GREY
    elif face != "down":
        for x in range(w):
            px[h - 1][x] = EDGE
    return px


def leg(w, h, r, face):
    """Leg: dark grey plate with a light knee line."""
    px = metal(GREY_DARK, GREY, EDGE)(w, h, r, face)
    if face == "north" and h >= 4:
        for x in range(1, w - 1):
            px[1][x] = GREY_LIGHT
    return px


def ball(w, h, r, face):
    """Antenna tip: yellow, shaded low and right, one pale pixel."""
    if face == "down":
        return [[YELLOW_DARK] * w for _ in range(h)]
    px = [[YELLOW] * w for _ in range(h)]
    if face != "up":
        for x in range(w):
            px[h - 1][x] = YELLOW_DARK
        px[0][0] = mix(YELLOW, WHITE, 0.55)
    return px


MATS = {
    "head": head_main, "ear": ear, "grille": grille, "cap": metal(GREY_DARK, GREY, EDGE),
    "eye_rim": (flat(EYE_RIM, top=EDGE), 2), "eye_ring": (ring, 2),
    "stem": flat(GREY_DARK, top=GREY),
    "torso": torso, "pelvis": metal(PETROL_DARK, PETROL, mix(PETROL_DARK, EDGE, 0.5)), "neck": neck,
    "screen": (screen, 2), "pack": pack,
    "shoulder": metal(DETAIL, SILVER, GREY_DARK), "arm": metal(GREY, GREY_LIGHT, GREY_DARK), "forearm": forearm,
    "hand": hand, "hip": flat(EDGE, top=GREY_DARK), "leg": leg, "foot": foot,
}
GLOW = {"lens": (lens, 2), "ball": ball, "belt": belt}

# --------------------------------------------------------------------------- skeleton (units, robot standing straight)
HIP_Y = 8.2
BODY_PIVOT = (0, 8.8, 0)                 # the body leans from the hips
NECK_PIVOT = (0, 23.2, 0)                # the head turns on the neck
ANTENNA_PIVOT = (0, 39.2, 0.6)
SHOULDER = {1: (8.2, 19.2, 0), -1: (-8.2, 19.2, 0)}
ELBOW = {1: (9.0, 14.6, 0), -1: (-9.0, 14.6, 0)}
HIP = {1: (3.4, HIP_Y, 0), -1: (-3.4, HIP_Y, 0)}
SIDE = {1: "Right", -1: "Left"}

BODY_TILT = rot(-2, 0, 0)                # 2 degrees forward
HEAD_TILT = rot(-3, 0, 0)                # 3 degrees forward: the robot looks at the players
ANTENNA_TILT = rot(8, 0, 0)              # 8 degrees backwards
ARM_OUT = 12                             # degrees outwards, both arms
FOREARM_BEND = {1: 8, -1: 22}            # the left forearm is bent further forward: natural asymmetry
FOOT_OUT = 5

GROUPS = {}


def parts():
    P = []
    on_body = attach(BODY_PIVOT, BODY_TILT)

    def chain(*frames):
        """Places a piece through nested frames, innermost first."""
        def place(c, r=(0, 0, 0)):
            for f in frames:
                c, r = f(c, r)
            return c, r
        return place

    def add(bone, name, c, size, mat, place=None, r=(0, 0, 0)):
        cc, M = place(c, r) if place else (c, r)
        P.append((bone, name, list(cc), size, M, mat))

    def bone(name, pivot, parent, place=None):
        GROUPS[name] = (tuple(place(pivot)[0]) if place else pivot, parent)

    GROUPS["Root"] = ((0, 0, 0), None)
    body = chain(on_body)
    bone("Body", BODY_PIVOT, "Root")

    # ---------------------------------------------------------------- body: 5 cubes + belt (compact, smaller than the head)
    add("Body", "bassin", (0, 9.6, 0), (10.4, 3.2, 7.2), "pelvis", body)
    add("Body", "ceinture", (0, 11.6, 0), (12.4, 1.6, 8.0), "belt", body)
    add("Body", "torse", (0, 16.6, 0), (13.6, 9.2, 8.4), "torso", body)
    add("Body", "cou", (0, 22.2, 0), (6, 2.8, 5.2), "neck", body)
    add("Body", "ecran", (0, 16.8, -4.6), (6, 5, 1), "screen", body)          # sunk in the chest, 0.9 unit out
    add("Body", "batterie", (0, 16.4, 4.8), (9, 6.4, 2), "pack", body)

    # ---------------------------------------------------------------- head: casing, two side modules, speaker, cap
    head = chain(attach(NECK_PIVOT, HEAD_TILT), on_body)
    bone("Head", NECK_PIVOT, "Body", body)
    add("Head", "tete", (0, 30, 0), (16.8, 14.4, 12), "head", head)
    for s in (1, -1):                                                         # set back, 0.8 unit out of the casing
        add("Head", f"module_{SIDE[s].lower()}", (8.2 * s, 30, 0.8), (2, 6.4, 7.2), "ear", head)
    add("Head", "haut_parleur", (0, 25.6, -5.9), (4, 3, 2), "grille", head)
    add("Head", "calotte", (0, 38.2, 0.4), (11, 2.4, 8), "cap", head)
    # the eye: three stacked layers, each smaller and further out (0.9, 1.5 and 1.9 units), centred above mid-face
    add("Head", "oeil_contour", (0, 31.2, -6.4), (7, 7, 1), "eye_rim", head)
    add("Head", "oeil_anneau", (0, 31.2, -7.0), (5.5, 5.5, 1), "eye_ring", head)
    add("Head", "oeil_lentille", (0, 31.2, -7.4), (4, 4, 1), "lens", head)

    # ---------------------------------------------------------------- antenna
    antenna = chain(attach(ANTENNA_PIVOT, ANTENNA_TILT), attach(NECK_PIVOT, HEAD_TILT), on_body)
    bone("Antenna", ANTENNA_PIVOT, "Head", head)
    add("Antenna", "antenne_tige", (0, 41.0, 0.6), (1.2, 3.6, 1.2), "stem", antenna)
    add("Antenna", "antenne_bout", (0, 43.6, 0.6), (2.4, 2.4, 2.4), "ball", antenna)

    # ---------------------------------------------------------------- arms: shoulder joint, upper arm, forearm, hand
    for s in (1, -1):
        side = SIDE[s]
        arm_bone, fore_bone = f"{side}Arm", f"{side}Forearm"
        on_arm = attach(SHOULDER[s], rot(0, 0, ARM_OUT * s))
        on_fore = attach(ELBOW[s], rot(FOREARM_BEND[s], 0, 0))
        arm = chain(on_arm, on_body)
        fore = chain(on_fore, on_arm, on_body)
        bone(arm_bone, SHOULDER[s], "Body", body)
        bone(fore_bone, ELBOW[s], arm_bone, arm)
        add("Body", f"epaule_{side.lower()}", (7.4 * s, 19, 0), (3.2, 3.6, 3.6), "shoulder", body)
        add(arm_bone, f"bras_{side.lower()}", (9.0 * s, 17.0, 0), (3.5, 5.6, 3.5), "arm", arm)
        add(fore_bone, f"avant_bras_{side.lower()}", (9.0 * s, 12.6, 0), (3, 4.8, 3), "forearm", fore)
        add(fore_bone, f"main_{side.lower()}", (9.0 * s, 9.0, 0), (3.2, 3.2, 3.2), "hand", fore)

    # ---------------------------------------------------------------- legs: hip joint, leg, wide foot turned outwards
    for s in (1, -1):
        side = SIDE[s]
        leg_bone = f"{side}Leg"
        bone(leg_bone, HIP[s], "Root")
        add(leg_bone, f"hanche_{side.lower()}", (3.4 * s, 7.6, 0), (3.2, 2.4, 3.2), "hip")
        add(leg_bone, f"jambe_{side.lower()}", (3.4 * s, 4.6, 0), (4, 4.4, 4), "leg")
        add(leg_bone, f"pied_{side.lower()}", (3.8 * s, 1.3, -0.9), (6, 2.6, 6.8), "foot", r=(0, -FOOT_OUT * s, 0))
    return P


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = anims_dir = None
    if "--anims" in args:
        i = args.index("--anims")
        anims_dir = args[i + 1]
        del args[i:i + 2]
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims = ground(parts(), GROUPS, center=False)
    uv, atlases, sizes = bake(P, MATS, GLOW)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    print(f"{len(P)} cubes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; "
          f"textures {sizes[0]} et {sizes[1]}")
    if prev:
        glow = {c for c in (ENERGY, ENERGY_DARK, YELLOW, YELLOW_DARK, WHITE)}
        apercu(data, atlases, sizes, prev, glow, views=((30, 10), (0, 4), (90, 2), (210, 12)), size=420)
    import robot_accueil_anim as RA
    from taupe_champs_anim import strips
    anims = RA.build(RA.uid_for(NS, IDENT))
    if anims_dir:
        strips(data, anims, atlases, sizes, anims_dir, count=8)
    data["animations"] = RA.finish(anims)
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
