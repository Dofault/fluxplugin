#!/usr/bin/env python3
"""T-Rex de Cuivre: mine boss, STATIC Blockbench model (.bbmodel), 2026-11-10.

A real Tyrannosaurus whose anatomy is copper grown around a fossil, found deep in a mine: big skull of copper plates,
long jaw with ivory teeth, thick neck, massive body leaning forward over two long and powerful hind legs, tiny arms,
long tail that dips at its base then rises as it thins.
Texture revised twice on 2026-11-10 at the owner's request. Three levels of contrast: inside a surface low (the
vanilla Block of Copper pattern, unoxidised, its own pixels tiled and softened by TEXTURE_CONTRAST); between anatomical
volumes moderate to marked (one shade of copper per zone, see MATS, plus ambient occlusion where parts meet); between
materials marked (stone, teeth, claws, eyes). No patina, no gold, no black outlines, no painted reflections. Kept apart: a few
grey-brown rocks, ivory teeth, dark claws, orange eyes and three tiny amber crystals (both emissive).

Built like the two references (Colosse d'Abime, the rock turtle, and Loutre des Marees): a few dozen volumes of
different sizes, overlapped and tilted for the silhouette; plates and claws are painted (1 texel per
unit, like a 16 x 16 block), never built from tiny cubes. Details on purpose asymmetric (bigger plate over the right
eye, rock on the left of the skull); the anatomy stays symmetric.

Pose baked into the cubes, bones at the joints for a future animation; no animation here.
SCALE sets the overall size at once (parts are drawn at their brief size, 16 units = 1 block).

Usage: trex_cuivre.py [output folder] [--apercu <sheet.png>]
Front to the north (-z), right side of the monster at +x, ground at y = 0, centred in x and z on its bounding box.
"""
import json
import math
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, corners, export, ground, hx, tone  # noqa: E402
from golem_fonderie import app, mul, rot  # noqa: E402

IDENT = "trex_cuivre"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-0000007e3c00")
SCALE = 0.92          # drawn at about 5.4 blocks long: brought to 5 x 3 blocks


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- vanilla Block of Copper
# The copper IS the vanilla block (unoxidised Block of Copper, 16 x 16): its 8 colours and the inside of its texture
# (14 x 14, without the light/dark border that would draw a grid of blocks), tiled at 1 texel per unit, so one pixel
# of the T-Rex is one pixel of the block. Each face starts at a random offset: no two faces show the same pattern.
CU = pal("#C87456", "#C26B4C", "#D67B5B", "#B26247", "#E3826C", "#9A5038", "#A75A40", "#904931")
CU_TILE = (
    "00110024222113", "04300242221433", "13502420211353", "10022200116631", "00222001165613", "02420011656133",
    "24200116561130", "42001365611300", "20013366111002", "00133631110024", "01333311300242", "14363113002430",
    "33531330022351", "65313300222011",
)
ROCK = pal("#4F4842", "#635B54", "#776F67", "#8E867D")          # grey-brown stone, clearly not copper
AMBER = pal("#C98A2A", "#F5B942", "#FFD47A")                     # the three small crystals only
TOOTH, TOOTH_SHADE = hx("#E5D4A6"), hx("#C4B07E")
CLAW = hx("#3F2A22")
EYE_RIM, IRIS, GLINT, PUPIL = hx("#38251D"), hx("#F18A24"), hx("#FFD47A"), hx("#170F0A")


def shade(c, k):
    return tuple(max(0, min(255, round(v * k))) for v in c)


TEXTURE_CONTRAST = 0.7      # contrast inside a surface: the block's own, softened (low to moderate)


def cu(w, h, r, base=CU[0]):
    """The vanilla copper pattern, from a random offset of the tile, recentred on the colour of the anatomical zone:
    each pixel keeps its ratio to the block's main colour (#C87456), softened by TEXTURE_CONTRAST."""
    ox, oy = r.randrange(14), r.randrange(14)
    out = []
    for y in range(h):
        row = []
        for x in range(w):
            c = CU[int(CU_TILE[(y + oy) % 14][(x + ox) % 14])]
            row.append(tuple(max(0, min(255, round(base[i] * (1 + TEXTURE_CONTRAST * (c[i] / CU[0][i] - 1)))))
                             for i in range(3)))
        out.append(row)
    return out


def occlude(px, rows=2, k=0.82):
    """Light ambient occlusion on the bottom rows of a side face (where it meets the part below)."""
    h = len(px)
    for n, y in enumerate(range(max(0, h - rows), h)):
        px[y] = [shade(c, k if n == rows - 1 else (1 + k) / 2) for c in px[y]]


# --------------------------------------------------------------------------- painters
def copper(zone="#C87456"):
    def f(w, h, r, face):
        """Copper hide in the colour of its zone; ambient occlusion at the bottom of the sides (where the part
        meets the one below) and a darker underside."""
        base = hx(zone)
        px = cu(w, h, r, shade(base, 0.9) if face == "down" else base)
        if face not in ("up", "down") and h >= 4:
            occlude(px)
        return px
    return f


def plate(zone="#D08768"):
    def f(w, h, r, face):
        """Copper plate, a shade lighter than the hide it lies on; its edge drawn by the separation colour
        #AD634B on the bottom row (the gap under the plate) and a darker right column, no black outline."""
        px = cu(w, h, r, hx(zone))
        if w >= 3 and h >= 3:
            px[h - 1] = [shade(c, 0.86) for c in px[h - 1]]
            for y in range(h - 1):
                px[y][w - 1] = shade(px[y][w - 1], 0.92)
        return px
    return f


def snout(w, h, r, face):
    """Snout: the copper block, two nostrils on top of the tip in the block's darkest colour."""
    px = copper("#D08768")(w, h, r, face)   # snout: lighter than the skull
    if face == "up" and w >= 6 and h >= 3:
        for x in (1, w - 2):
            px[0][x] = CU[5]
    return px


def rock(ore_spots=0):
    def f(w, h, r, face):
        """Stone grown into the copper: moderate grey-brown in small groups, a few copper spots of the block."""
        px = [[ROCK[1]] * w for _ in range(h)]
        for _ in range(max(1, w * h // 6)):
            x, y = r.randrange(w), r.randrange(h)
            c = ROCK[r.choice((0, 2, 2, 3))]
            for dx, dy in ((0, 0), (1, 0), (0, 1)):
                if x + dx < w and y + dy < h and r.random() < 0.7:
                    px[y + dy][x + dx] = c
        for _ in range(ore_spots):
            x, y = r.randrange(w), r.randrange(h)
            px[y][x] = CU[0]
            if x + 1 < w:
                px[y][x + 1] = CU[2]
        return px
    return f


def tooth(w, h, r, face):
    px = [[TOOTH] * w for _ in range(h)]
    px[h - 1] = [TOOTH_SHADE] * w
    return px


def fang_up(w, h, r, face):
    px = [[TOOTH] * w for _ in range(h)]
    px[0] = [TOOTH_SHADE] * w
    return px


def claw(w, h, r, face):
    return [[CLAW] * w for _ in range(h)]


def toe(w, h, r, face):
    """Toe (density 2): copper, claw colour at the front tip (u = 0 is the front on the sides)."""
    px = cu(w, h, r, hx("#9A533E"))
    if face == "north":
        px = [[CLAW] * w for _ in range(h)]
    elif face == "up":
        px[0] = [CLAW] * w
    elif face in ("east", "west"):
        for y in range(h):
            px[y][0] = CLAW
    return px


def eye(w, h, r, face):
    """Eye (density 2, painted on the outer side): dark rim, glowing orange iris, slit pupil, one glint."""
    if face not in ("east", "west"):
        return [[EYE_RIM] * w for _ in range(h)]
    px = [[EYE_RIM] * w for _ in range(h)]
    for y in range(2, h - 1):
        for x in range(1, w - 1):
            px[y][x] = IRIS
    c = w // 2
    for y in range(2, h - 1):
        px[y][c] = PUPIL
    px[2][c + 1 if face == "east" else c - 1] = GLINT
    return px


def crystal(w, h, r, face):
    """Amber crystal (emissive, three small ones in all): light core, darker edge."""
    px = [[AMBER[1]] * w for _ in range(h)]
    for y in range(h):
        px[y][0] = AMBER[0]
    if face in ("north", "east", "up"):
        px[0] = [AMBER[2]] * w
    return px


# One copper (the vanilla block pattern), one shade per anatomical zone from the owner's palette, each with a role:
# lighter where the light falls (back, top of the skull, snout, plates), main colour on the flanks, darker under
# the body, under the neck (separates head from torso), on the lower jaw (separates it from the snout), on the joints.
MATS = {
    "copper_light": copper("#D58C70"),     # back, croup
    "flank": copper("#C87456"), "hips": copper("#C87456"), "copper": copper("#C87456"),   # flanks, shoulders
    "thigh": copper("#B8694E"),             # thighs: a step under the flanks, the leg reads as its own limb
    "muscle": copper("#C87456"),            # front of the thigh, catching the light
    "neck": copper("#B4664B"),              # neck: darker than torso and skull
    "copper_aged": copper("#AA5F46"),      # chest, cheeks, arms, shins
    "jaw": copper("#9F563F"),               # lower jaw, clearly darker than the snout
    "copper_dark": copper("#9A533E"),      # back of the skull, forearms, ankles, feet
    "belly": copper("#934E3A"),             # belly and throat: separates head from torso, body from legs
    "joint": copper("#874733"),             # knees, jaw hinges
    "skull": plate("#C87456"), "snout": snout,
    "plate": plate("#D08768"), "plate_light": plate("#D58C70"), "plate_aged": plate("#BD6C50"),
    "plate_ox": plate("#BD6C50"), "plate_crack": plate("#D08768"), "plate_crystal": plate("#D08768"),
    "plate_dark": plate("#A35A43"),
    "tail": copper("#C87456"), "tail_b": copper("#B4664B"), "tail_end": copper("#A85E46"),   # segments alternate
    "rock": rock(0), "rock_ore": rock(2), "tooth": tooth, "fang_up": fang_up, "claw": claw, "toe": (toe, 2),
}
GLOW = {"eye": (eye, 2), "crystal": crystal}
GLOW_COLOURS = {EYE_RIM, IRIS, GLINT, PUPIL, *AMBER}


# --------------------------------------------------------------------------- skeleton (drawing units)
# Silhouette rebuilt on 2026-11-10 (owner: "too big, too squat"): a long, narrow, horizontal body; a long head pushed
# forward, deep jaw, no cheeks; tall hind legs in three segments (thigh forward, shin back, metatarsus forward), feet
# under the pelvis; tiny arms; a long tail thinning to a point. Targets: 5 x 3 blocks, torso 1.5 wide, head 1.1 x 1.3,
# tail 2, legs 1.3 under the body.
HIP = {"right": (10, 28, 6), "left": (-10, 28, 6)}
KNEE_Z, KNEE_Y = -0.5, 16           # knee forward of the hip
ANKLE_Z, ANKLE_Y = 7, 6             # ankle high and behind (digitigrade)
BALL_Z = 2.5                        # metatarsus comes forward down to the toes
NECK = (0, 33, -12)                 # base of the neck, on the chest
HEAD = (0, 40, -20)                 # back of the skull, top of the neck
HEAD_R = rot(2, 0, 0)               # head held level, a little raised
JAW_HINGE = (0, 38.5, -21.5)        # back of the lower jaw
JAW_OPEN = rot(-13, 0, 0)           # mouth ajar (negative = front down)
SHOULDERS = {"right": (8.5, 27.5, -12), "left": (-8.5, 27.5, -12)}

# Tail: (bone, cube, size, pitch (positive = down), extra yaw, material), base to tip; overlap TAIL_OVERLAP.
TAIL = (
    ("Tail_1", "queue_base", (15, 12, 10), 9, 0, "tail"),
    ("Tail_2", "queue_milieu_1", (11.5, 9, 9), 4, 3, "tail_b"),
    ("Tail_3", "queue_milieu_2", (8.5, 7, 9), -2, 4, "tail"),
    ("Tail_4", "queue_fin", (6, 5, 8), -6, 4, "tail_b"),
    ("Tail_5", "queue_pointe", (3.5, 3.2, 7), -8, 4, "tail_end"),
)
TAIL_START = (0, 33, 10.5)
TAIL_OVERLAP = 2.5


def local(c, M, v):
    """Point v of a piece's frame (centre c, matrix M) in the model frame."""
    q = app(M, v)
    return [c[i] + q[i] for i in range(3)]


def seg(a, b, width, depth, extra=2.0):
    """A limb cube from joint a to joint b (in the y-z plane): centre, size, pitch; `extra` overlaps the joints."""
    dy, dz = b[1] - a[1], b[2] - a[2]
    length = math.hypot(dy, dz) + extra
    pitch = math.degrees(math.atan2(-dz, -dy))      # positive = bottom toward -z (front)
    return [(a[i] + b[i]) / 2 for i in range(3)], (width, length, depth), (pitch, 0, 0)


def parts():
    """Pieces and bones (name: (pivot, parent)), in drawing units, before grounding."""
    P = []
    G = {"Root": ((0, 0, 0), None), "Body": ((0, 31, 6), "Root"), "glow_crystals": ((0, 31, 6), "Body"),
         "Neck": (NECK, "Body"), "Head": (HEAD, "Neck")}

    def add(g, name, c, size, r, mat):
        P.append((g, name, list(c), size, r, mat))

    # ---------------------------------------------------------------- body: long and narrow, horizontal
    add("Body", "poitrail", (0, 29.5, -8.5), (19, 14, 12), (-8, 0, 0), "copper_aged")       # chest, a bit lower
    add("Body", "torse", (0, 31.5, 2), (21, 14, 12), (-3, 0, 0), "flank")
    add("Body", "bassin", (0, 32.5, 10), (22, 13, 9), (6, 0, 0), "hips")                   # carries the legs
    add("Body", "bas_bassin", (0, 25.5, 10.5), (10, 5, 9), (10, 0, 0), "belly")     # between the thighs, seen from behind
    add("Body", "dos", (0, 39, 0), (14, 2.5, 26), (-2, 0, 0), "copper_light")              # gently arched back
    add("Body", "ventre", (0, 23.6, -2), (14, 2.5, 14), (-5, 0, 0), "belly")               # flat, not a ball
    add("Body", "plaque_flanc_droit", (10.8, 31.5, 1), (1.4, 8, 11), (-3, 2, 3), "plate")
    add("Body", "plaque_flanc_gauche", (-10.8, 31, 2), (1.4, 9, 10), (-4, -2, -3), "plate_aged")
    add("Body", "plaque_epaule_droite", (8.5, 36.5, -8), (5, 1.6, 7), (-8, 0, 20), "plate_crystal")
    add("Body", "plaque_epaule_gauche", (-8.5, 36.2, -7.5), (5, 1.6, 6), (-8, 0, -18), "plate")
    for i, (z, w, h, ang, mat) in enumerate(((-9, 5, 2.2, 14, "plate"), (-3, 6, 2.6, 12, "plate_crystal"),
                                             (3, 6, 2.8, 10, "plate_light"), (9, 5.5, 2.5, 8, "plate_crystal"),
                                             (14.5, 4.5, 2.2, 10, "plate"))):
        add("Body", f"plaque_dos_{i + 1}", (0, 39.6 + h / 2 - 0.6 - 0.04 * z, z), (w, h, 4.5), (ang, 0, 0), mat)
    add("Body", "roche_hanche", (10.5, 36.5, 10), (3.5, 4.5, 6), (10, 0, 18), "rock_ore")
    add("Body", "roche_epaule", (-9, 35.5, -4), (4, 3, 4.5), (-10, 20, -16), "rock")
    add("glow_crystals", "cristal_dos", (1.0, 42.8, -3.2), (1.5, 3.2, 1.5), (-12, 30, 14), "crystal")
    add("glow_crystals", "cristal_dos_2", (-0.9, 42.0, 9.2), (1.2, 2.4, 1.2), (14, 20, -16), "crystal")
    add("glow_crystals", "cristal_hanche", (12.0, 39.2, 10.5), (1.2, 2.6, 1.2), (6, 0, -24), "crystal")

    # ---------------------------------------------------------------- neck: thick, rising forward, darker
    add("Neck", "cou", (0, 36, -16), (13, 11, 11), (24, 0, 0), "neck")
    add("Neck", "gorge", (0, 30.8, -15.5), (11, 6, 9), (30, 0, 0), "belly")
    add("Neck", "plaque_nuque", (0, 41.6, -15), (9, 1.8, 9), (24, 0, 0), "plate_aged")
    add("Neck", "plaque_cou_droite", (6.8, 36.5, -16), (1.4, 7.5, 8), (24, 0, 6), "plate")

    # ---------------------------------------------------------------- head: long skull, deep jaw, snout forward
    on_head = attach(HEAD, HEAD_R)

    def hd(g, name, c, size, r, mat):
        cc, M = on_head(c, r)
        P.append((g, name, cc, size, M, mat))
        return cc, M

    hd("Head", "crane", (0, 44, -25), (17, 9, 11), (1, 0, 0), "skull")                  # wide back of the skull
    hd("Head", "arriere_crane", (0, 41.5, -20.3), (17.6, 10, 3.5), (-6, 0, 0), "copper_dark")
    hd("Head", "crane_dome", (0, 48.8, -24.5), (12, 1.6, 8), (2, 0, 0), "plate_light")
    mc, mM = hd("Head", "museau", (0, 43, -35), (12, 6.5, 13), (-3, 0, 0), "snout")    # long, horizontal
    lc, lM = hd("Head", "levre", (0, 38.3, -33), (12.6, 3.4, 15), (-3, 0, 0), "copper_aged")  # deep upper jaw
    hd("Head", "museau_bout", (0, 41.6, -42.2), (10, 8, 2.5), (-4, 0, 0), "snout")
    hd("Head", "arete_nasale", (0, 46.8, -35), (5, 1.4, 12), (-3, 0, 0), "plate")
    hd("Head", "machoire_muscle_droit", (7.6, 39.5, -24.5), (2.4, 6, 7), (0, 0, 0), "copper_aged")
    hd("Head", "machoire_muscle_gauche", (-7.6, 39.5, -24.5), (2.4, 6, 7), (0, 0, 0), "copper_aged")
    # brows over the eyes (asymmetric detail: bigger plate on the right), eyes on the sides, turned forward
    hd("Head", "arcade_droite", (6.6, 49.2, -28.5), (5.4, 2.3, 5), (-7, 0, 8), "plate")
    hd("Head", "plaque_arcade_droite", (6.4, 50.6, -27.8), (4, 1.0, 3.6), (-10, 0, 10), "plate_light")
    hd("Head", "arcade_gauche", (-6.6, 49.0, -28.5), (5, 2.1, 5), (-7, 0, -8), "plate_aged")
    hd("Head", "roche_crane", (-4.8, 49.6, -22.5), (3.4, 1.6, 3.4), (0, 25, -6), "rock_ore")
    G["glow_eyes"] = (on_head((0, 46, -28.5), (0, 0, 0))[0], "Head")
    hd("glow_eyes", "oeil_droit", (8.6, 46.0, -28.6), (0.8, 2.8, 2.8), (0, -14, 0), "eye")
    hd("glow_eyes", "oeil_gauche", (-8.6, 46.0, -28.6), (0.8, 2.8, 2.8), (0, 14, 0), "eye")
    # upper teeth under the deep upper jaw, irregular, longer at the front, leaning back
    for s, side in ((1, "d"), (-1, "g")):
        for k, (dz, extra) in enumerate(((-6, 0.6), (-3, 0.3), (0, 0.8), (3, 0.2), (6, 0.5))):
            base = local(lc, lM, (s * 5.3, -1.7, dz))
            ln = 1.5 + extra
            P.append(("Head", f"dent_haut_{side}{k + 1}", [base[0], base[1] - ln / 2 + 0.3, base[2]],
                      (0.9, ln, 0.9), mul(lM, rot(-10, 0, 0)), "tooth"))
        base = local(lc, lM, (s * 3.2, -1.7, -7.0))
        P.append(("Head", f"croc_avant_{side}", [base[0], base[1] - 1.3, base[2]], (1.1, 2.8, 1.1),
                  mul(lM, rot(-8, 0, 0)), "tooth"))

    # lower jaw: long and deep, clearly separate (darker, opened)
    G["Jaw"] = (on_head(JAW_HINGE, (0, 0, 0))[0], "Head")
    on_jaw = attach(JAW_HINGE, JAW_OPEN)

    def jw(name, c, size, r, mat):
        c1, r1 = on_jaw(c, r)
        cc, M = on_head(c1, r1)
        P.append(("Jaw", name, cc, size, M, mat))
        return cc, M

    jc, jM = jw("machoire", (0, 35.2, -31), (11.5, 5, 18), (-3, 0, 0), "jaw")
    jw("menton", (0, 35.6, -40.5), (8.5, 4.5, 3), (-4, 0, 0), "jaw")
    jw("plaque_machoire", (0, 32.5, -30), (8, 1.4, 11), (-3, 0, 0), "plate_dark")
    for s, side in ((1, "droite"), (-1, "gauche")):
        jw(f"articulation_{side}", (s * 6.2, 38, -22.5), (1.4, 2.8, 2.8), (0, 0, 0), "joint")
        for k, dz in enumerate((-6.5, -3.5, -0.5, 2.5)):
            top = local(jc, jM, (s * 4.6, 2.5, dz))
            ln = 1.3 + (0.6 if k == 0 else 0.2 * (k % 2))
            P.append(("Jaw", f"dent_bas_{side[0]}{k + 1}", [top[0], top[1] + ln / 2 - 0.3, top[2]], (0.9, ln, 0.9),
                      mul(jM, rot(8, 0, 0)), "fang_up"))

    # ---------------------------------------------------------------- tiny arms on the sides of the upper chest
    for s, side in ((1, "right"), (-1, "left")):
        bone = f"Arm_{side}"
        G[bone] = (SHOULDERS[side], "Body")
        S_ = SHOULDERS[side]
        E = (S_[0] + 0.6 * s, S_[1] - 3.6, S_[2] - 1.2)         # elbow
        W = (S_[0] + 0.7 * s, S_[1] - 4.8, S_[2] - 4.6)         # wrist: the hand sticks out in front of the chest
        c, size, r = seg(S_, E, 2.6, 2.8, extra=1.5)
        add(bone, "bras", c, size, r, "copper_aged")
        c, size, r = seg(E, W, 2.2, 2.2, extra=1.5)
        add(bone, "avant_bras", c, size, r, "copper_dark")
        for k, dx in enumerate((-0.55, 0.55)):                  # two clawed fingers, curled down
            add(bone, f"doigt_{k + 1}", (W[0] + dx, W[1] - 1.0, W[2] - 0.6), (0.8, 2.2, 0.8), (35, 0, 0), "claw")

    # ---------------------------------------------------------------- hind legs: thigh, shin, metatarsus, 3-toed foot
    for s, side in ((1, "right"), (-1, "left")):
        up, low = f"Leg_{side}", f"Shin_{side}"
        hx_, hy, hz = HIP[side]
        H = (hx_, hy, hz)
        K = (hx_ + 0.5 * s, KNEE_Y, KNEE_Z)
        A = (hx_ + 1.0 * s, ANKLE_Y, ANKLE_Z)
        B = (hx_ + 1.0 * s, 2.5, BALL_Z)
        G[up] = (H, "Root")
        c, size, r = seg(H, K, 8, 11, extra=4)
        add(up, "cuisse", c, size, r, "thigh")                                       # big, muscular
        # upper thigh wider and set a bit inward: a drumstick joined to the pelvis, not a straight column
        add(up, "cuisse_haut", (hx_ - 0.6 * s, hy - 4.5, hz - 1.2), (10.5, 9, 11.5), r, "thigh")
        add(up, "cuisse_muscle", (c[0] + 2.6 * s, c[1] + 1.5, c[2] - 1), (3.5, 10, 9), (r[0], 0, 4 * s), "muscle")
        add(up, "plaque_cuisse", (c[0] + 4.3 * s, c[1] + 1, c[2]), (1.3, 8, 8), (r[0], 0, 2 * s),
            "plate_crack" if s > 0 else "plate")
        G[low] = (K, up)
        add(low, "genou", K, (6.5, 4.5, 6), (0, 0, 0), "joint")
        c, size, r = seg(K, A, 5.5, 5.5, extra=2)
        add(low, "tibia", c, size, r, "copper_aged")                                 # distinct shin, going back
        add(low, "roche_genou", (K[0] + 3.0 * s, K[1] - 1, K[2] + 1.5), (1.8, 3.2, 3.5), (r[0], 0, 8 * s), "rock")
        add(low, "cheville", A, (4.2, 3.6, 4.2), (0, 0, 0), "joint")
        c, size, r = seg(A, B, 4, 4, extra=1.5)
        add(low, "metatarse", c, size, r, "copper_dark")
        foot = attach(B, rot(0, -4 * s, 0))                                          # foot turned slightly out
        for name, cc, sz, rr, mat in (
            ("pied", (B[0], 1.4, B[2] - 0.5), (7, 2.8, 6), (0, 0, 0), "copper_dark"),
            ("doigt_1", (B[0] - 2.5, 1.1, B[2] - 5), (2, 2.2, 5), (0, 14, 0), "toe"),
            ("doigt_2", (B[0], 1.2, B[2] - 5.6), (2.2, 2.4, 6), (0, 0, 0), "toe"),
            ("doigt_3", (B[0] + 2.5, 1.1, B[2] - 5), (2, 2.2, 5), (0, -14, 0), "toe"),
            ("griffe_1", (B[0] - 3.7, 0.8, B[2] - 7.5), (1.1, 1.6, 1.5), (20, 14, 0), "claw"),
            ("griffe_2", (B[0], 0.9, B[2] - 9.0), (1.2, 1.8, 1.6), (20, 0, 0), "claw"),
            ("griffe_3", (B[0] + 3.7, 0.8, B[2] - 7.5), (1.1, 1.6, 1.5), (20, -14, 0), "claw"),
        ):
            pc, M = foot(cc, rr)
            add(low, name, pc, sz, M, mat)

    # ---------------------------------------------------------------- tail: one bone per segment, pivot at its front
    start, yaw, parent = list(TAIL_START), 0.0, "Body"
    for g, name, size, pitch, dyaw, mat in TAIL:
        yaw += dyaw
        R = rot(pitch, yaw, 0)
        d = app(R, [0, 0, 1])
        c = [start[k] + d[k] * size[2] / 2 for k in range(3)]
        G[g] = (list(start), parent)
        add(g, name, c, size, R, mat)
        if g in ("Tail_1", "Tail_2", "Tail_3"):
            pc = local(c, R, (0.3, size[1] / 2 + 0.3, -size[2] * 0.08))
            add(g, f"plaque_{name}", pc, (size[0] * 0.45, 1.3, size[2] * 0.5), mul(R, rot(8, 0, 0)), "plate_aged")
        start = [start[k] + d[k] * (size[2] - TAIL_OVERLAP) for k in range(3)]
        parent = g

    # on the ground; parents written before their children
    low = min(q[1] for g, n, c, size, r, m in P for q in corners(c, size, r))
    P = [(g, n, [c[0], c[1] - low, c[2]], size, r, m) for g, n, c, size, r, m in P]
    G = {k: ((pv[0], pv[1] - (0 if k == "Root" else low), pv[2]), par) for k, (pv, par) in G.items()}
    order, done = [], set()
    while len(order) < len(G):
        for k, (pv, par) in G.items():
            if k not in done and (par is None or par in done):
                order.append(k)
                done.add(k)
    return P, {k: G[k] for k in order}


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = anims_dir = only = None
    show = "--mesures" in args
    static = "--statique" in args
    for flag in ("--mesures", "--statique"):
        if flag in args:
            args.remove(flag)
    for key in ("--apercu", "--anims", "--only"):
        if key in args:
            i = args.index(key)
            val = args[i + 1]
            del args[i:i + 2]
            if key == "--apercu":
                prev = val
            elif key == "--anims":
                anims_dir = val
            else:
                only = set(val.split(","))
    out = args[0] if args else HERE
    P, groups, dims = ground(*parts(), scale=SCALE)
    uv, atlases, sizes = bake(P, {**MATS, **GLOW}, GLOW)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    anims = []
    if not static:
        import trex_cuivre_anim as TA
        anims = TA.build(data, TA.uid_for(NS, IDENT))
        if anims_dir:
            TA.strips(data, anims, atlases, sizes, anims_dir, only=only)
        if show:
            for name, rows in TA.measure(data, anims).items():
                if only and name not in only:
                    continue
                print(f"--- {name}")
                for r in rows:
                    print(f"  tick {r['tick']:3d}  bas {r['low']:6.2f}  museau {r['snout']}")
        data["animations"] = TA.finish(anims)
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print(f"{len(P)} volumes ; {len(groups)} bones ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ;"
          f" textures {sizes[0]} + {sizes[1]} ; {len(anims)} animations")
    print("written", path, f"({os.path.getsize(path) // 1024} Ko)")
    if prev:
        apercu(data, atlases, sizes, prev, GLOW_COLOURS, views=((35, 12), (0, 6), (90, 3), (205, 14)), size=420)
