#!/usr/bin/env python3
"""T-Rex de Cuivre: mine boss, STATIC Blockbench model (.bbmodel), 2026-11-10.

A real Tyrannosaurus whose anatomy is copper grown around a fossil, found deep in a mine: big skull of copper plates,
long jaw with ivory teeth, thick neck, massive body leaning forward over two long and powerful hind legs, tiny arms,
long tail that dips at its base then rises as it thins.
Texture revised on 2026-11-10 at the owner's request: the copper is the vanilla Block of Copper (unoxidised), its own
pixels tiled on every copper part, soft and low-contrast; no patina, no gold, no outlines, no painted reflections (the
light is the game's face shading). Plates differ only by geometry and a slightly darker lower edge. Kept apart: a few
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
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, corners, export, ground, hx, tone  # noqa: E402
from golem_fonderie import app, mul, rot  # noqa: E402

IDENT = "trex_cuivre"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-0000007e3c00")
SCALE = 1.0


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
ROCK = pal("#5F544C", "#6E6259", "#7D7066", "#8C8075")          # moderate grey-brown stone
AMBER = pal("#C98A2A", "#F5B942", "#FFD47A")                     # the three small crystals only
TOOTH, TOOTH_SHADE = hx("#E5D4A6"), hx("#C4B07E")
CLAW = hx("#5A3A2E")
EYE_RIM, IRIS, GLINT, PUPIL = hx("#38251D"), hx("#F18A24"), hx("#FFD47A"), hx("#170F0A")


def shade(c, k):
    return tuple(max(0, min(255, round(v * k))) for v in c)


def cu(w, h, r, k=1.0):
    """The vanilla copper surface, from a random offset of the tile, uniformly darkened by k (never below 0.88:
    a part in shadow stays copper)."""
    ox, oy = r.randrange(14), r.randrange(14)
    return [[shade(CU[int(CU_TILE[(y + oy) % 14][(x + ox) % 14])], k) for x in range(w)] for y in range(h)]


def occlude(px, rows=1, k=0.92):
    """Light ambient occlusion on the bottom rows of a side face (where it meets the part below)."""
    h = len(px)
    for y in range(max(0, h - rows), h):
        px[y] = [shade(c, k) for c in px[y]]


# --------------------------------------------------------------------------- painters
def copper(k=1.0):
    def f(w, h, r, face):
        """Copper hide: the vanilla block, faint occlusion at the bottom of the sides. The light (top lighter,
        bottom darker) is the game's face shading, not painted."""
        px = cu(w, h, r, k * (0.96 if face == "down" else 1.0))
        if face not in ("up", "down") and h >= 4:
            occlude(px)
        return px
    return f


def plate(k=1.0):
    def f(w, h, r, face):
        """Copper plate: the same block, its edge only suggested by a slightly darker bottom row and right column
        (the gap under the plate), no black outline, no light border."""
        px = cu(w, h, r, k)
        if w >= 3 and h >= 3:
            px[h - 1] = [shade(c, 0.9) for c in px[h - 1]]
            for y in range(h - 1):
                px[y][w - 1] = shade(px[y][w - 1], 0.93)
        return px
    return f


def snout(w, h, r, face):
    """Snout: the copper block, two nostrils on top of the tip in the block's darkest colour."""
    px = copper()(w, h, r, face)
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
    px = cu(w, h, r, 0.96)
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


# One material, the vanilla block; the names only keep the role of each part (a whisper of shade, at most 6 %).
MATS = {
    "copper": copper(), "copper_light": copper(), "copper_aged": copper(0.97), "copper_dark": copper(0.95),
    "flank": copper(), "hips": copper(), "belly": copper(0.95), "skull": plate(), "snout": snout,
    "plate": plate(), "plate_light": plate(), "plate_aged": plate(0.97), "plate_ox": plate(0.97),
    "plate_crack": plate(), "plate_crystal": plate(), "joint": copper(0.94),
    "tail": copper(), "tail_mid": copper(), "tail_end": copper(0.97),
    "rock": rock(0), "rock_ore": rock(2), "tooth": tooth, "fang_up": fang_up, "claw": claw, "toe": (toe, 2),
}
GLOW = {"eye": (eye, 2), "crystal": crystal}
GLOW_COLOURS = {EYE_RIM, IRIS, GLINT, PUPIL, *AMBER}


# --------------------------------------------------------------------------- skeleton (drawing units)
# Body leans forward over the hips; the hind legs carry it (bones under Root, like the otter), the neck rises to a
# head held a bit higher than the body centre; the tail leaves the back of the pelvis, dips then rises.
HIP_Y = 30
BODY_PIVOT = (0, 31, 6)            # over the hips: the body tips forward around them
NECK = (0, 35, -13)                # base of the neck, on the chest
HEAD = (0, 43, -21)                # back of the skull, top of the neck
HEAD_R = rot(5, 0, 0)              # head slightly raised
JAW_HINGE = (0, 40.5, -23)         # back of the lower jaw, under the temples
JAW_OPEN = rot(-14, 0, 0)          # mouth ajar: teeth visible (negative = front down)
HIPS = {"right": (13, HIP_Y, 5), "left": (-13, HIP_Y, 5)}
SHOULDERS = {"right": (8, 27, -12), "left": (-8, 27, -12)}

# Tail: (bone, cube, size, pitch from the horizontal (positive = down), extra yaw, material), base to tip.
# Five segments, each smaller, overlapping by TAIL_OVERLAP; it dips at the base then rises, and curves gently.
TAIL = (
    ("Tail_1", "queue_base", (16, 14, 10), 14, 0, "tail"),
    ("Tail_2", "queue_milieu_1", (12.5, 10.5, 9), 4, 4, "tail"),
    ("Tail_3", "queue_milieu_2", (9.5, 8, 9), -5, 5, "tail_mid"),
    ("Tail_4", "queue_fin", (7, 6, 8), -10, 5, "tail_mid"),
    ("Tail_5", "queue_pointe", (4.5, 4, 7), -13, 5, "tail_end"),
)
TAIL_START = (0, 31.5, 13)
TAIL_OVERLAP = 2.5


def local(c, M, v):
    """Point v of a piece's frame (centre c, matrix M) in the model frame."""
    q = app(M, v)
    return [c[i] + q[i] for i in range(3)]


def parts():
    """Pieces and bones (name: (pivot, parent)), in drawing units, before grounding."""
    P = []
    G = {"Root": ((0, 0, 0), None), "Body": (BODY_PIVOT, "Root"), "glow_crystals": (BODY_PIVOT, "Body"),
         "Neck": (NECK, "Body"), "Head": (HEAD, "Neck")}

    def add(g, name, c, size, r, mat):
        P.append((g, name, list(c), size, r, mat))

    # ---------------------------------------------------------------- body: chest, shoulders, pelvis, belly, back
    # Torso in several boxes: higher, rounder back; narrower belly; pelvis wider than the chest toward the legs.
    add("Body", "torse", (0, 31.5, 2), (28, 18, 16), (-4, 0, 0), "flank")
    add("Body", "torse_avant", (0, 30, -7.5), (24, 17, 10), (-9, 0, 0), "flank")         # narrower chest
    add("Body", "torse_haut", (0, 39.5, -1), (22, 4, 20), (-4, 0, 0), "copper_light")      # rounded back
    add("Body", "poitrail", (0, 26.5, -12), (17, 12, 6), (-16, 0, 0), "copper_aged")
    add("Body", "epaule_droite", (10.5, 33, -8), (6, 10, 10), (-8, 0, 6), "copper")
    add("Body", "epaule_gauche", (-10.5, 33, -8), (6, 10, 10), (-8, 0, -6), "copper")
    add("Body", "bassin", (0, 32, 9), (30, 17, 13), (5, 0, 0), "hips")
    add("Body", "croupe", (0, 39.5, 10), (20, 4, 12), (8, 0, 0), "copper_light")
    add("Body", "ventre", (0, 22.5, 0), (20, 4, 22), (-4, 0, 0), "belly")
    # plates on the flanks and shoulders, following the body, not aligned with each other
    add("Body", "plaque_flanc_droit", (14.6, 32, -1), (1.6, 9, 13), (-4, 3, 4), "plate_crack")
    add("Body", "plaque_flanc_gauche", (-14.6, 31, 1), (1.6, 10, 12), (-6, -2, -3), "plate_aged")
    add("Body", "plaque_epaule_droite", (11.5, 38.2, -8), (6, 1.8, 9), (-8, 0, 22), "plate_crystal")
    add("Body", "plaque_epaule_gauche", (-11.5, 37.8, -7.5), (6, 1.8, 8), (-6, 0, -20), "plate")
    # dorsal plates along the spine, small and leaning back (mineral plates, not dragon spikes)
    for i, (z, w, h, ang, mat) in enumerate(((-9, 6, 2.5, 14, "plate"), (-3, 7, 3, 12, "plate_crystal"),
                                             (3.5, 7, 3.2, 10, "plate_light"), (10, 6.5, 2.8, 8, "plate_crystal"),
                                             (15.5, 5.5, 2.4, 10, "plate"))):
        y = 41.6 + 0.08 * z + h / 2 - 0.8
        add("Body", f"plaque_dos_{i + 1}", (0, y, z), (w, h, 5), (ang, 0, 0), mat)
    # rocks grown into the copper: right hip (with copper ore), left shoulder
    add("Body", "roche_hanche", (14.5, 35.5, 10), (4, 5, 7), (10, 0, 18), "rock_ore")
    add("Body", "roche_epaule", (-12.5, 37.5, -5), (5, 3.5, 5), (-10, 20, -16), "rock")
    # small amber crystals (emissive) in the back plates and the left hip rock
    add("glow_crystals", "cristal_dos", (1.2, 45.6, -3.5), (1.6, 3.5, 1.6), (-12, 30, 14), "crystal")
    add("glow_crystals", "cristal_dos_2", (-1.0, 45.0, 9.8), (1.3, 2.6, 1.3), (14, 20, -16), "crystal")
    add("glow_crystals", "cristal_hanche", (16.2, 38.5, 10.5), (1.3, 2.8, 1.3), (6, 0, -24), "crystal")

    # ---------------------------------------------------------------- neck: thick, leaning forward, plated
    add("Neck", "cou", (0, 38, -17), (17, 14, 12), (22, 0, 0), "copper")
    add("Neck", "gorge", (0, 31.5, -16.5), (14, 8, 10), (30, 0, 0), "belly")
    add("Neck", "plaque_nuque", (0, 45.3, -15.5), (11, 2, 10), (24, 0, 0), "plate_aged")
    add("Neck", "plaque_cou_droite", (8.8, 38.5, -16.5), (1.6, 9, 9), (22, 0, 6), "plate")
    add("Neck", "plaque_cou_gauche", (-8.8, 38, -17), (1.6, 8, 9), (20, 0, -8), "plate_ox")

    # ---------------------------------------------------------------- head: big skull, long snout, brows
    on_head = attach(HEAD, HEAD_R)

    def hd(g, name, c, size, r, mat):
        cc, M = on_head(c, r)
        P.append((g, name, cc, size, M, mat))
        return cc, M

    hd("Head", "crane", (0, 47, -27), (21, 9, 13), (1, 0, 0), "skull")
    hd("Head", "crane_dome", (0, 52, -26.5), (15, 2, 10), (2, 0, 0), "plate_light")
    hd("Head", "arriere_crane", (0, 45.5, -21.5), (23, 11, 5), (-6, 0, 0), "copper_dark")
    hd("Head", "joue_droite", (8.5, 41.5, -25.5), (5.5, 7, 9), (0, 6, 0), "copper_aged")
    hd("Head", "joue_gauche", (-8.5, 41.5, -25.5), (5.5, 7, 9), (0, -6, 0), "copper_aged")
    mc, mM = hd("Head", "museau", (0, 45, -38), (14, 8, 12), (-5, 0, 0), "snout")
    tc, tM = hd("Head", "museau_bout", (0, 44.3, -44.6), (11, 6.5, 3), (-7, 0, 0), "snout")
    hd("Head", "arete_nasale", (0, 49.4, -37), (6, 1.5, 11), (-5, 0, 0), "plate")
    # brows: they overhang the eyes; asymmetric details (bigger plate over the right eye, patina on the left one)
    hd("Head", "arcade_droite", (8.4, 51.6, -31.5), (6.5, 2.6, 6), (-7, 0, 7), "plate_crack")
    hd("Head", "plaque_arcade_droite", (8.0, 53.2, -30.5), (5, 1.2, 4.5), (-10, 0, 10), "plate_light")
    hd("Head", "arcade_gauche", (-8.4, 51.4, -31.5), (6, 2.4, 6), (-7, 0, -7), "plate_ox")
    hd("Head", "roche_crane", (-6.5, 53.0, -25), (4, 1.8, 4), (0, 25, -6), "rock_ore")
    # eyes on the sides of the skull, a little turned forward, under the brows
    G["glow_eyes"] = (on_head((0, 49, -31), (0, 0, 0))[0], "Head")
    hd("glow_eyes", "oeil_droit", (10.6, 48.6, -31.4), (0.8, 3, 3), (0, -14, 0), "eye")
    hd("glow_eyes", "oeil_gauche", (-10.6, 48.6, -31.4), (0.8, 3, 3), (0, 14, 0), "eye")
    # upper teeth along the snout, irregular, longer at the front, leaning back
    teeth = [(-4.5, 0.5), (-2.5, 1.2), (-0.5, 0.6), (1.5, 0.9), (3.6, 0.4)]   # (z along the snout, extra length)
    for s, side in ((1, "d"), (-1, "g")):
        for k, (dz, extra) in enumerate(teeth):
            base = local(mc, mM, (s * 6.0, -4, dz))
            ln = 1.6 + extra + (0.3 if (k + (s > 0)) % 2 else 0)
            hd_c = [base[0], base[1] - ln / 2 + 0.4, base[2]]
            P.append(("Head", f"dent_haut_{side}{k + 1}", hd_c, (1.0, ln, 1.0), mul(mM, rot(-10, 0, 0)), "tooth"))
        base = local(tc, tM, (s * 3.4, -3.25, -0.4))
        P.append(("Head", f"croc_avant_{side}", [base[0], base[1] - 1.3, base[2]], (1.2, 3.0, 1.2),
                  mul(tM, rot(-8, 0, 0)), "tooth"))

    # lower jaw: a distinct piece hinged under the temples, opened a little
    hinge = on_head(JAW_HINGE, (0, 0, 0))[0]
    G["Jaw"] = (hinge, "Head")
    on_jaw = attach(JAW_HINGE, JAW_OPEN)

    def jw(name, c, size, r, mat):
        c1, r1 = on_jaw(c, r)
        cc, M = on_head(c1, r1)
        P.append(("Jaw", name, cc, size, M, mat))
        return cc, M

    jc, jM = jw("machoire", (0, 38.6, -33), (14, 5.5, 16), (-4, 0, 0), "copper_aged")
    jw("menton", (0, 38.7, -42.5), (10, 5, 4), (-6, 0, 0), "copper_aged")
    jw("plaque_machoire", (0, 35.6, -32), (10, 1.5, 11), (-4, 0, 0), "plate_aged")
    for s, side in ((1, "droite"), (-1, "gauche")):
        jw(f"articulation_{side}", (s * 7.4, 40.3, -23.5), (1.6, 3, 3), (0, 0, 0), "joint")
        for k, dz in enumerate((-5.5, -2.5, 0.5, 3.5)):
            top = local(jc, jM, (s * 5.6, 2.75, dz))
            ln = 1.4 + (0.6 if k == 0 else 0.2 * (k % 2))
            P.append(("Jaw", f"dent_bas_{side[0]}{k + 1}", [top[0], top[1] + ln / 2 - 0.4, top[2]], (1.0, ln, 1.0),
                      mul(jM, rot(8, 0, 0)), "fang_up"))

    # ---------------------------------------------------------------- tiny arms: very short, close to the chest
    for s, side in ((1, "right"), (-1, "left")):
        bone = f"Arm_{side}"
        G[bone] = (SHOULDERS[side], "Body")
        sx, sy, sz = SHOULDERS[side]
        add(bone, "bras", (sx + 1.2 * s, sy - 2.2, sz - 1.4), (3.5, 5, 4), (30, 0, -6 * s), "copper_aged")
        add(bone, "avant_bras", (sx + 1.6 * s, sy - 5.6, sz - 3.6), (3, 4, 3), (-25, 0, -4 * s), "copper_dark")
        for k, dx in enumerate((-0.7, 0.7)):
            add(bone, f"doigt_{k + 1}", (sx + 1.6 * s + dx, sy - 7.9, sz - 5.0), (0.9, 0.9, 2.4), (40, 0, 0), "toe")

    # ---------------------------------------------------------------- hind legs: big thigh, thinner shin, 3-toed foot
    legs = {}
    for s, side in ((1, "right"), (-1, "left")):
        up, low = f"Leg_{side}", f"Shin_{side}"
        hx_, hy, hz = HIPS[side]
        G[up] = ((hx_, hy, hz), "Root")
        add(up, "cuisse", (hx_ + 1.2 * s, hy - 6.5, hz - 1.5), (12, 17, 14), (14, 0, 0), "copper")
        add(up, "cuisse_muscle", (hx_ + 3.6 * s, hy - 4, hz - 0.5), (5, 10, 11), (14, 0, 5 * s), "copper_light")
        add(up, "plaque_cuisse", (hx_ + 6.6 * s, hy - 6, hz - 1), (1.5, 9, 9), (14, 0, 2 * s), "plate_crack" if s > 0 else "plate")
        knee = (hx_ + 1.2 * s, hy - 14, hz - 4.5)
        G[low] = (knee, up)
        add(low, "genou", (knee[0], knee[1] + 0.5, knee[2]), (7.5, 5, 7), (0, 0, 0), "joint")
        add(low, "roche_genou", (knee[0] + 3.4 * s, knee[1] + 1, knee[2] + 0.5), (2.2, 3.5, 4), (0, 0, 8 * s), "rock")
        add(low, "jambe", (knee[0], knee[1] - 5.5, knee[2] + 2.5), (6, 11, 6), (-22, 0, 4 * s), "copper_aged")
        add(low, "plaque_jambe", (knee[0], knee[1] - 5.5, knee[2] - 0.8), (4.5, 7, 1.2), (-22, 0, 0), "plate_ox")
        ankle = (knee[0], knee[1] - 11, knee[2] + 4.5)
        add(low, "cheville", ankle, (4.5, 3.5, 4.5), (0, 0, 0), "copper_dark")
        foot = attach(ankle, rot(0, -4 * s, 0))                           # foot turned slightly outward
        for name, c, size, r, mat in (
            ("pied", (ankle[0], 1.8, ankle[2] - 2.5), (8, 3.6, 7), (0, 0, 0), "copper_dark"),
            ("doigt_1", (ankle[0] - 2.8, 1.3, ankle[2] - 7.5), (2.2, 2.6, 5.5), (0, 14, 0), "toe"),
            ("doigt_2", (ankle[0], 1.4, ankle[2] - 8.3), (2.4, 2.8, 6.5), (0, 0, 0), "toe"),
            ("doigt_3", (ankle[0] + 2.8, 1.3, ankle[2] - 7.5), (2.2, 2.6, 5.5), (0, -14, 0), "toe"),
            ("griffe_1", (ankle[0] - 4.2, 0.9, ankle[2] - 10.3), (1.2, 1.8, 1.6), (20, 14, 0), "claw"),
            ("griffe_2", (ankle[0], 1.0, ankle[2] - 11.9), (1.3, 2.0, 1.7), (20, 0, 0), "claw"),
            ("griffe_3", (ankle[0] + 4.2, 0.9, ankle[2] - 10.3), (1.2, 1.8, 1.6), (20, -14, 0), "claw"),
        ):
            cc, M = foot(c, r)
            add(low, name, cc, size, M, mat)
        legs[up] = (up, low)

    # ---------------------------------------------------------------- tail: one bone per segment, pivot at its front
    start, yaw, parent = list(TAIL_START), 0.0, "Body"
    for g, name, size, pitch, dyaw, mat in TAIL:
        yaw += dyaw
        R = rot(pitch, yaw, 0)
        d = app(R, [0, 0, 1])
        c = [start[k] + d[k] * size[2] / 2 for k in range(3)]
        G[g] = (list(start), parent)
        add(g, name, c, size, R, mat)
        if g in ("Tail_1", "Tail_2", "Tail_3"):                    # mineral plates on the top of the tail
            pc = local(c, R, (0.3, size[1] / 2 + 0.3, -size[2] * 0.08))
            add(g, f"plaque_{name}", pc, (size[0] * 0.5, 1.4, size[2] * 0.55), mul(R, rot(8, 0, 0)),
                "plate_aged" if g != "Tail_3" else "plate_ox")
        start = [start[k] + d[k] * (size[2] - TAIL_OVERLAP) for k in range(3)]
        parent = g

    # each leg exactly on the ground; parents written before their children
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
    prev = None
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims = ground(*parts(), scale=SCALE)
    mats = dict(MATS)
    uv, atlases, sizes = bake(P, {**mats, **GLOW}, GLOW)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print(f"{len(P)} volumes ; {len(groups)} bones ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ;"
          f" textures {sizes[0]} + {sizes[1]}")
    print("written", path)
    if prev:
        apercu(data, atlases, sizes, prev, GLOW_COLOURS, views=((35, 12), (0, 6), (90, 3), (205, 14)), size=420)
