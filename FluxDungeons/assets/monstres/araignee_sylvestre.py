#!/usr/bin/env python3
"""Sylvan Spider (L'Araignée Sylvestre, la Tisseuse des Anciens Bois): boss of the lumberjack dungeon, STATIC
Blockbench model (2026-11-14).

A giant spider grown out of an old tree: a stump for cephalothorax (growth rings on its cut top), a pine cone of
overlapping bark plates for abdomen, eight legs that are living branches (thick upper segment, wood knot for knee,
thinner lower segment sinking into the dark, standing on a corner so the tip reads as a root point), six amber eyes
set in the bark, two hooked chelicerae, two pedipalps, moss, two mushrooms, two twigs.

Brief constraints: 30 to 45 boxes (45 here, asserted), Minecraft pixel art at 16 texels per block (1 texel per model
unit, every material), fully opaque, nothing emissive. Moss, cracks, fibres, scales of the flanks and eyes are
painted, never modelled.

Usage: araignee_sylvestre.py [output folder] [--apercu <sheet.png>] [--visage <face.png>]
Front towards north (-z), the spider's right towards +x, 16 units = 1 block.
"""
import json
import math
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, combined, corners, export, flat_elements, ground, hx  # noqa: E402
from golem_fonderie import mul, rot  # noqa: E402

IDENT = "araignee_sylvestre"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000a4a1601")

# ---------------------------------------------------------------- palette (brief)
CEPH = [hx(c) for c in ("#49362C", "#65452F", "#846047", "#A58A67")]
ABDO = [hx(c) for c in ("#60432F", "#795239", "#99704A", "#B0926A")]
LEG = [hx(c) for c in ("#473329", "#62452F", "#896344", "#A0805B")]
MOSS = [hx(c) for c in ("#425B3C", "#617A4B", "#84936A", "#A1A77A")]
ORBIT, IRIS, GLINT = hx("#382B25"), hx("#C18A35"), hx("#E2B75D")
CAP, CAP_D, STEM = hx("#C6AC82"), hx("#927454"), hx("#D8C8A5")

# light from above and slightly from the front
FACE_OFF = {"up": 0.45, "north": 0.1, "west": 0.0, "east": 0.0, "south": -0.3, "down": -1.0}


def tone(ramp, v):
    return ramp[max(0, min(len(ramp) - 1, int(math.floor(v + 0.5))))]


def moss_blobs(g, r, n, top_only=False):
    """Small ragged clumps of moss: a short random walk per clump, dark at the rim, lighter where it piles up."""
    h, w = len(g), len(g[0])
    for _ in range(n):
        x, y = r.randrange(w), r.randrange(max(1, h // 3) if top_only else h)
        for step in range(r.choice((3, 4, 5, 6))):
            if 0 <= x < w and 0 <= y < h:
                g[y][x] = MOSS[r.choice((0, 1, 1, 2) if step < 3 else (1, 2, 2, 3))]
            x, y = x + r.choice((-1, 0, 1)), y + r.choice((-1, 0, 0, 1))
    return g


def wood_grid(w, h, r, face, ramp, base=1.5, fade=0.0, cracks=1.0):
    """Wood: one tone per face (light from above), fibres as whole image columns a touch lighter or darker, a few
    short dark cracks; `fade` sinks the lower rows of the side faces into the dark (leg tips)."""
    off = FACE_OFF[face]
    col = [r.choice((-0.4, 0.0, 0.0, 0.0, 0.4)) for _ in range(w)]
    g = []
    for y in range(h):
        k = y / max(1, h - 1)
        row = []
        for x in range(w):
            v = base + off + col[x] + r.uniform(-0.15, 0.15)
            if fade:
                v -= fade * (1.0 if face == "down" else 0.0 if face == "up" else max(0.0, (k - 0.35) / 0.65))
            row.append(tone(ramp, v))
        g.append(row)
    for _ in range(int(w * h * cracks / 36)):
        x, y = r.randrange(w), r.randrange(h)
        for j in range(r.choice((2, 3))):
            if y + j < h:
                g[y + j][x] = ramp[0]
    return g


def wood(ramp, base=1.5, fade=0.0, moss=0.0, cracks=1.0):
    def f(w, h, r, face):
        g = wood_grid(w, h, r, face, ramp, base, fade, cracks)
        if moss and face != "down":
            n = w * h * moss / 12
            moss_blobs(g, r, int(n) + (r.random() < n - int(n)), top_only=face != "up")
        return g
    return f


def rings(g, ramp, cx, cy, light=3, dark=1):
    """Discreet growth rings: concentric square rings around (cx, cy), every other one a step lighter."""
    for y in range(len(g)):
        for x in range(len(g[0])):
            d = max(abs(x - cx), abs(y - cy))
            g[y][x] = ramp[light] if d % 2 == 0 else ramp[dark + 1]
    return g


# ---------------------------------------------------------------- cephalothorax (stump)
def stump_top(w, h, r, face):
    """Dome of the stump: bark on the sides, the cut of the trunk on top (growth rings in light wood), moss eating
    one corner."""
    g = wood_grid(w, h, r, face, CEPH, 1.6)
    if face == "up":
        inner = [row[1:-1] for row in g[1:-1]]
        rings(inner, CEPH, (w - 2) // 2 + 1, (h - 2) // 2, light=3, dark=1)
        for y in range(1, h - 1):
            g[y][1:-1] = inner[y - 1]
        for y in range(h // 2, h):                      # moss on the rear right corner
            for x in range(w):
                if (w - 1 - x) + (h - 1 - y) < 5 + r.choice((0, 0, 1)):
                    g[y][x] = MOSS[r.choice((0, 1, 1, 2))]
        moss_blobs(g, r, 2)
    return g


EYES_MAIN = ((4, 4), (9, 4))                # top left pixel of the two 2 x 2 central eyes (image of the north face)
EYES_SIDE = ((2, 2), (12, 2), (1, 5), (13, 5))   # four 1 pixel eyes, on an arch around them


def head(w, h, r, face):
    """Front of the head. Six amber eyes on a slight arch, each sunk in a dark orbit; a light brow ridge above."""
    g = wood_grid(w, h, r, face, CEPH, 1.5, cracks=0.4)
    if face != "north":
        return g
    assert (w, h) == (15, 9), (w, h)
    g = [[tone(CEPH, 1.45 + (0.6 if y == 0 else 0.0) - (0.5 if y >= 7 else 0.0) + r.uniform(-0.2, 0.2))
          for _ in range(w)] for y in range(h)]
    for x, y in EYES_MAIN:                   # orbit: one pixel of dark bark all around the eye
        for j in range(-1, 3):
            for i in range(-1, 3):
                if (i in (-1, 2)) + (j in (-1, 2)) < 2:      # corners left to the bark: rounder orbit
                    g[y + j][x + i] = ORBIT
        for j in (0, 1):
            for i in (0, 1):
                g[y + j][x + i] = IRIS
    g[EYES_MAIN[0][1]][EYES_MAIN[0][0]] = GLINT
    g[EYES_MAIN[1][1]][EYES_MAIN[1][0]] = GLINT
    for x, y in EYES_SIDE:
        out = -1 if x < w // 2 else 1
        for i, j in ((out, 0), (0, -1), (out, -1)):          # orbit as a brow over the outer side
            g[y + j][x + i] = ORBIT
        g[y][x] = IRIS
    return g


def fang(w, h, r, face):
    """Chelicera: dark hooked twig, tip (bottom of the side faces) a step lighter."""
    g = wood_grid(w, h, r, face, LEG, 0.2, cracks=0.0)
    if face not in ("up", "down"):
        for x in range(w):
            g[h - 1][x] = LEG[2]
    elif face == "down":
        g = [[LEG[2]] * w for _ in range(h)]
    return g


# ---------------------------------------------------------------- abdomen (pine cone)
def cone(w, h, r, face):
    """Core of the abdomen: on the flanks and the rear, rows of painted scales (staggered, each its own brown, dark
    under its lower lip); dark hollows on top, where the modelled plates lie."""
    if face == "up":
        return [[tone(ABDO, 0.3 + r.uniform(-0.3, 0.3)) for _ in range(w)] for _ in range(h)]
    if face in ("down", "north"):
        return wood_grid(w, h, r, face, ABDO, 1.2)
    tones = {}
    g = []
    for y in range(h):
        band, yo = divmod(y, 4)
        row = []
        for x in range(w):
            cell, xo = divmod(x + (band % 2) * 3, 6)
            t = tones.setdefault((band, cell), r.choice((0.9, 1.3, 1.3, 1.9, 2.3)))
            if (yo == 3 and 1 <= xo <= 4) or (yo == 2 and xo in (0, 5)):
                c = ABDO[0]                                     # shadow under the lip of the scale
            elif xo in (0, 5):
                c = tone(ABDO, t - 0.8)
            elif yo == 0:
                c = tone(ABDO, t + 0.7)                         # light on its upper edge
            else:
                c = tone(ABDO, t + FACE_OFF[face])
            row.append(c)
        g.append(row)
    return moss_blobs(g, r, 2, top_only=True)


def cone_tip(w, h, r, face):
    """Rear cap of the cone: one last scale, growth rings on its end."""
    g = wood_grid(w, h, r, face, ABDO, 1.5)
    if face == "south":
        rings(g, ABDO, w // 2, h // 2, light=2, dark=0)
    return g


def plate(base, moss=0.0):
    """Modelled scale of the cone: its own brown, fibres running to the rear, a dark rim on the sides and the rear
    edge (the hollow between two plates), a lighter band across its swell."""
    def f(w, h, r, face):
        if face == "down":
            return [[ABDO[0]] * w for _ in range(h)]
        if face != "up":
            return [[tone(ABDO, base + FACE_OFF[face] - (0.0 if y == 0 else 1.0 if y == h - 1 else 0.5))
                     for _ in range(w)] for y in range(h)]
        col = [r.choice((-0.4, 0.0, 0.0, 0.4)) for _ in range(w)]
        g = []
        for y in range(h):
            row = []
            for x in range(w):
                v = base + 0.35 + col[x]
                if y in (h // 3, h // 3 + 1):
                    v += 0.5
                if x in (0, w - 1) or y == h - 1:
                    v -= 1.1
                row.append(tone(ABDO, v))
            g.append(row)
        if moss:
            n = w * h * moss / 12
            moss_blobs(g, r, int(n) + (r.random() < n - int(n)))
        return g
    return f


def cap(w, h, r, face):
    if face == "down":
        return [[CAP_D] * w for _ in range(h)]
    if face == "up":
        g = [[CAP] * w for _ in range(h)]
        g[r.randrange(h)][r.randrange(w)] = STEM
        g[h - 1][w - 1] = CAP_D
        return g
    return [[CAP if y < h - 1 else CAP_D for _ in range(w)] for y in range(h)]


def stem(w, h, r, face):
    return [[STEM if (x + y) % 3 else CAP for x in range(w)] for y in range(h)]


MATS = {
    "ceph": wood(CEPH, 1.45, moss=0.25),
    "stump": stump_top,
    "head": head,
    "fang": fang,
    "palp": wood(LEG, 1.3, fade=1.2, cracks=0.0),
    "cone": cone,
    "cone_tip": cone_tip,
    "plate_a": plate(1.1), "plate_b": plate(1.7, moss=0.5), "plate_c": plate(2.3),
    "plate_d": plate(2.0, moss=0.8), "plate_e": plate(1.3, moss=0.3), "plate_f": plate(2.6),
    "leg_up": wood(LEG, 1.7, cracks=0.8),
    "leg_up_moss": wood(LEG, 1.7, cracks=0.8, moss=0.5),
    "leg_low": wood(LEG, 1.55, fade=1.6, cracks=0.6),
    "knot": wood(LEG, 2.5, cracks=0.0),
    "knot_moss": wood(LEG, 2.5, cracks=0.0, moss=0.7),
    "twig": wood(LEG, 1.2, cracks=0.0),
    "cap": cap, "stem": stem,
}
GLOW = {}

# ---------------------------------------------------------------- geometry
BODY_R = rot(-5, 0, 0)                      # stump leaning forward
BODY_PIVOT = (0.0, 15.0, -9.0)
HEAD_PIVOT = (0.0, 16.5, -15.0)
HEAD_R = rot(-22, 0, 0)                     # head looking forward and down
WAIST = (0.0, 17.0, -2.5)
ABDO_R = rot(-13, 0, 0)                     # abdomen raised at the rear

# legs, right side (left ones mirrored): hip z, heading in degrees (positive = forwards), reach of the foot from the
# hip, height of the knee, share of the reach where the knee stands, sideways bend of the knee (towards the front),
# thickness of the upper segment, of the lower one, size of the knot
LEGS = (
    (1, -14.0, 56, 24.5, 32.0, 0.46, 2.0, 6.0, 5.0, 8.0),
    (2, -10.5, 17, 22.5, 27.0, 0.48, 2.5, 5.0, 4.0, 7.0),
    (3, -7.0, -22, 22.0, 25.5, 0.48, -2.0, 5.0, 4.0, 7.0),
    (4, -3.5, -58, 29.0, 26.0, 0.44, -3.0, 6.0, 4.5, 7.5),
)
HIP_X, HIP_Y = 7.5, 15.5
MOSSY_KNOTS = {(1, -1), (2, 1), (4, -1), (3, 1)}      # (leg, side): asymmetric on purpose
MOSSY_LEGS = {(1, 1), (3, -1)}

GROUPS = {"Root": ((0, 0, 0), None), "Body": (BODY_PIVOT, "Root"), "Head": (HEAD_PIVOT, "Body"),
          "Chelicera_right": ((2.5, 11.5, -20.0), "Head"), "Chelicera_left": ((-2.5, 11.5, -20.0), "Head"),
          "Pedipalp_right": ((6.0, 12.0, -18.5), "Head"), "Pedipalp_left": ((-6.0, 12.0, -18.5), "Head"),
          "Abdomen": (WAIST, "Body")}

BUDGET = {"cephalothorax": 3, "abdomen": 8, "legs": 24, "mouth": 4, "mushrooms": 4, "twigs": 2}


def along(a, b, thick, over=(0.0, 0.0), twist=0.0):
    """Box lying along the segment a -> b (its local +y), `over` = extra length before a and past b."""
    d = [b[i] - a[i] for i in range(3)]
    ln = math.sqrt(sum(v * v for v in d))
    ey = [v / ln for v in d]
    ex = [ey[2], 0.0, -ey[0]]                      # horizontal, at right angle to the segment
    n = math.hypot(ex[0], ex[2])
    ex = [v / n for v in ex]
    ez = [ex[1] * ey[2] - ex[2] * ey[1], ex[2] * ey[0] - ex[0] * ey[2], ex[0] * ey[1] - ex[1] * ey[0]]
    M = [[ex[i], ey[i], ez[i]] for i in range(3)]
    if twist:
        M = mul(M, rot(0, twist, 0))
    mid = (ln + over[1] - over[0]) / 2
    c = [a[i] + ey[i] * mid for i in range(3)]
    return c, [thick, round(ln + over[0] + over[1]), thick], M


def parts():
    P = []
    count = {k: 0 for k in BUDGET}

    def add(part, g, name, c, size, r, mat):
        count[part] += 1
        P.append((g, name, list(c), list(size), r, mat))

    body = attach(BODY_PIVOT, BODY_R)
    head_at = attach(HEAD_PIVOT, HEAD_R)
    abdo = attach(WAIST, ABDO_R)

    # ------------------------------------------------ cephalothorax: stump, dome with the cut on top, head
    for name, c, size, mat in (("souche", (0, 15.0, -9.5), (19, 11, 14), "ceph"),
                               ("souche_dessus", (0, 21.5, -8.5), (13, 3, 10), "stump")):
        c, M = body(c, (0, 0, 0))
        add("cephalothorax", "Body", name, c, size, M, mat)
    c, M = head_at((0, 14.5, -18.0), (0, 0, 0))
    add("cephalothorax", "Head", "tete", c, (15, 9, 7), M, "head")

    # ------------------------------------------------ mouth: chelicerae hooked inwards, pedipalps at their sides
    for s, sd, side in ((1, "d", "right"), (-1, "g", "left")):
        c, M = head_at((2.4 * s, 8.6, -20.3), (-14, 0, -14 * s))
        add("mouth", f"Chelicera_{side}", f"chelicere_{sd}", c, (2, 5, 2), M, "fang")
        c, M = head_at((6.3 * s, 10.2, -21.2), (-38, -16 * s, 0))
        add("mouth", f"Pedipalp_{side}", f"pedipalpe_{sd}", c, (2, 2, 6), M, "palp")

    # ------------------------------------------------ abdomen: core, rear cap, six plates lying on each other
    def aadd(part, name, c, size, r, mat, g="Abdomen"):
        c, M = abdo(c, r)
        add(part, g, name, c, size, M, mat)

    aadd("abdomen", "cone", (0, 21.5, 7.5), (19, 14, 20), (0, 0, 0), "cone")
    aadd("abdomen", "cone_pointe", (0, 21.0, 19.0), (13, 10, 5), (0, 0, 0), "cone_tip")
    for name, c, size, r, mat in (
            ("plaque_1", (0.0, 28.9, 0.8), (20, 3, 8), (-9, 0, 0), "plate_a"),
            ("plaque_2", (5.2, 29.7, 7.0), (11, 3, 8), (-14, -5, -11), "plate_c"),
            ("plaque_3", (-5.2, 29.6, 7.4), (11, 3, 8), (-13, 4, 12), "plate_b"),
            ("plaque_4", (0.2, 30.6, 13.2), (18, 3, 8), (-19, 2, 0), "plate_f"),
            ("plaque_5", (4.3, 28.6, 18.6), (9, 3, 7), (-27, -6, -13), "plate_e"),
            ("plaque_6", (-4.3, 28.7, 18.9), (9, 3, 7), (-26, 7, 14), "plate_d")):
        aadd("abdomen", name, c, size, r, mat)

    # ------------------------------------------------ vegetation on the plates: two mushrooms, two twigs
    aadd("mushrooms", "champignon_1_pied", (-6.0, 32.4, 6.6), (2, 3, 2), (0, 0, 12), "stem")
    aadd("mushrooms", "champignon_1_chapeau", (-6.4, 34.4, 6.6), (4, 2, 4), (0, 0, 12), "cap")
    aadd("mushrooms", "champignon_2_pied", (5.6, 33.4, 14.0), (1, 3, 1), (-12, 0, -10), "stem")
    aadd("mushrooms", "champignon_2_chapeau", (5.9, 35.2, 14.4), (3, 2, 3), (-12, 0, -10), "cap")
    aadd("twigs", "branche_1", (1.5, 35.4, 12.0), (2, 6, 2), (-22, 0, -12), "twig")
    aadd("twigs", "branche_2", (3.0, 31.5, 21.5), (1, 5, 1), (-50, 0, -14), "twig")

    # ------------------------------------------------ legs: upper branch, knot, lower branch standing on a corner
    for n, hz, heading, reach, knee_y, frac, bend, t_up, t_low, knot in LEGS:
        for s, sd, side in ((1, "d", "right"), (-1, "g", "left")):
            a = math.radians(heading)
            hx_, hz_ = math.cos(a), -math.sin(a)
            hip = body((HIP_X, HIP_Y, hz), (0, 0, 0))[0]
            knee = [hip[0] + hx_ * reach * frac + math.sin(a) * bend, knee_y, hip[2] + hz_ * reach * frac - math.cos(a) * bend]
            foot = [hip[0] + hx_ * reach, 0.0, hip[2] + hz_ * reach]
            hip, knee, foot = ([p[0] * s, p[1], p[2]] for p in (hip, knee, foot))
            g, low = f"Leg_{n}_{side}", f"Leg_{n}_{side}_low"
            GROUPS[g] = (hip, "Body")
            GROUPS[low] = (knee, g)
            c, size, M = along(hip, knee, t_up, over=(1.0, 0.0))
            add("legs", g, f"patte_{n}_{sd}_haut", c, size, M, "leg_up_moss" if (n, s) in MOSSY_LEGS else "leg_up")
            add("legs", g, f"patte_{n}_{sd}_noeud", knee, (knot, knot, knot), mul(M, rot(0, 45, 0)),
                "knot_moss" if (n, s) in MOSSY_KNOTS else "knot")
            c, size, M = along(foot, knee, t_low)
            dip = min(p[1] for p in corners(c, size, M))        # the lowest corner is the root point: put it on the ground
            foot[1] -= dip
            c, size, M = along(foot, knee, t_low)
            add("legs", low, f"patte_{n}_{sd}_bas", c, size, M, "leg_low")

    assert count == BUDGET, count
    assert len(P) == 45
    return P


def build():
    return ground(parts(), GROUPS, center=True)


def face_closeup(data, atlases, sizes, path, size=420):
    """Head alone, front and three quarters, large: checks the six eyes."""
    import paths  # noqa: F401
    import apercu as A
    from png_util import write
    keep = ("souche", "tete", "chelicere", "pedipalpe")
    els = [e for e in flat_elements(data, sizes) if e["name"].startswith(keep)]
    combo = combined(atlases, sizes)
    bg = (58, 58, 62, 255)
    imgs = [A.render(els, combo, yaw, pitch, size=size, bg=bg, margin=24) for yaw, pitch in ((0, 0), (30, 8))]
    back = ("cone", "plaque", "champignon", "branche")
    els = [e for e in flat_elements(data, sizes) if e["name"].startswith(back)]
    imgs += [A.render(els, combo, yaw, pitch, size=size, bg=bg, margin=24) for yaw, pitch in ((90, 4), (150, 28))]
    write(path, size * 4, size, [sum((im[y] for im in imgs), []) for y in range(size)])


if __name__ == "__main__":
    args = sys.argv[1:]
    opts = {}
    for flag in ("--apercu", "--visage"):
        if flag in args:
            i = args.index(flag)
            opts[flag] = args[i + 1]
            del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims = build()
    uv, atlases, sizes = bake(P, MATS, GLOW)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    print(f"{len(P)} cubes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (large x haut x long) ; "
          f"texture {sizes[0]}")
    if "--apercu" in opts:
        # face, three quarters (presentation view), profile, top, then the silhouette
        apercu(data, atlases, sizes, opts["--apercu"], set(), views=((0, 4), (35, 16), (90, 2), (20, 75)), size=420)
    if "--visage" in opts:
        face_closeup(data, atlases, sizes, opts["--visage"])
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
