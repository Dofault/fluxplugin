#!/usr/bin/env python3
"""Salamandrex: emerald salamander boss. STATIC Blockbench model (2026-11-11, third version: smooth back, new skin).

A real four-legged salamander whose body is living emerald: wide flat head with a round muzzle, big side eyes under
heavy brow ridges (narrow, angry, vertical slit pupils), long low body with the belly just above the ground, four
short legs sprawled sideways (zigzag: upper segment out and down, marked elbow or knee, lower segment back to the
ground), wide feet with four short round toes, long tail thick at the base, tapering, with a soft S curve seen from
above and staying close to the ground. No crystal, spike, plate or horn anywhere: the back is a smooth line, the
emerald is suggested by the colour, the depth and the polished reflections only.

Same building language as the otter (loutre_marees.py) and the stone turtle (colosse_abime.py): big tilted volumes,
details painted at 1 texel per unit (2 for the eyes and the toes).

Painting: every pixel gets a continuous tone from where it really is (`emerald`): the light of its face (from above,
the front and slightly to the creature's left, LIGHT), a vertical gradient in world space (so stacked cubes share one
gradient instead of each restarting it), soft polished highlights along the top faces, a few low-contrast inclusions.
The tone is then cut into the palette of the brief one step at a time, with a single checkered row where two steps
meet: smooth transitions, no outline, no near-black, the main green #70CC98 dominant.

Pose baked in the cubes, bones at the joints for a future animation; no animation, no glow, no particle.

Usage: salamandrex.py [output folder] [--apercu <sheet.png>] [--table <cubes.md>]   (model + its animations)
Blockbench axes: x = width (+x = the creature's right), y = height (ground at 0), z = length (head towards -z).
"""
import json
import math
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import attach, bake, corners, export, ground, hx, matrix  # noqa: E402
from golem_fonderie import app, euler, rot  # noqa: E402

IDENT = "salamandrex"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000051a1a1")


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- palettes of the brief (dark -> light)
SKIN = pal("#1C624A", "#277D5A", "#389B6C", "#55B982", "#70CC98", "#91DDB0", "#B5EBC5")   # main #70CC98 = 4
BELLY = pal("#55B982", "#70CC98", "#91DDB0", "#B5EBC5")                                   # milky jade, main = 2
EYE = {"iris": hx("#2E9667"), "iris_light": hx("#68C99A"), "iris_dark": hx("#176044"), "pupil": hx("#123D31"),
       "glint": hx("#D8F7E5")}

# light: from above, from the front (-z) and slightly from the creature's left (-x)
LIGHT = (lambda v: [a / math.sqrt(sum(b * b for b in v)) for a in v])([-0.28, 0.82, -0.5])
NORMALS = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0),
           "down": (0, -1, 0)}


def quantize(t, x, y, pal_, low=1):
    """Continuous tone -> palette index, one step at a time; the border between two steps is gently wavy (a slow
    wobble of the tone) so it follows the surface instead of a ruler line or a checker pattern. The darkest colour
    only for real hollows (t < 0.6), never as an outline."""
    t += 0.16 * math.sin(0.7 * x + 0.23 * y + 1.3) + 0.09 * math.sin(0.41 * y - 0.5 * x)
    i = math.floor(t + 0.5)
    lo = 0 if t < 0.6 else low
    return pal_[max(lo, min(len(pal_) - 1, i))]


# --------------------------------------------------------------------------- painters
# A painter receives a `Face`: pixel size, face name, light (cosine with LIGHT) and `at(x, y)`, the world position of a
# pixel centre. It returns a grid of RGB colours.
class Face:
    def __init__(self, w, h, name, light, at, rng):
        self.w, self.h, self.name, self.light, self.at, self.r = w, h, name, light, at, rng


def emerald(base=4.0, span=(5.0, 24.0), grad=1.25, gloss=0.0, gloss_at=0.45, inclusions=150, local=False,
            pal_=SKIN, low=1):
    """Living emerald: base tone, + face light, + vertical gradient (world height between span, or the face's own
    height when `local`), + rounded top faces (centre lighter), + a soft elongated polished highlight on the top face
    (`gloss`, centred at `gloss_at` of its length), + rare low-contrast inclusions."""
    def f(F):
        w, h = F.w, F.h
        t0 = base - 0.3 + 1.1 * F.light
        y0, y1 = span
        rows = []
        for y in range(h):
            row = []
            for x in range(w):
                if local:
                    g = grad * (1 - 2 * (y + 0.5) / h) if F.name not in ("up", "down") else 0.3 * grad
                else:
                    wy = F.at(x, y)[1]
                    g = grad * (2 * (wy - y0) / (y1 - y0) - 1)
                t = t0 + g
                if F.name == "up":
                    u = (x + 0.5) / w
                    t += 0.35 * (1 - abs(2 * u - 1)) - 0.15
                    if gloss:
                        v = (y + 0.5) / h
                        t += gloss * math.exp(-(((u - 0.5) / 0.2) ** 2 + ((v - gloss_at) / 0.3) ** 2))
                row.append(t)
            rows.append(row)
        if inclusions and w * h >= 36:
            for _ in range(w * h // inclusions):
                x, y = F.r.randrange(1, w - 1), F.r.randrange(1, h - 1)
                d = F.r.choice((0.55, -0.55))
                for dx, dy in ((0, 0), (1, 0)) if F.r.random() < 0.5 else ((0, 0),):
                    rows[y][x + dx] += d
        return [[quantize(rows[y][x], x, y, pal_, low) for x in range(w)] for y in range(h)]
    return f


def belly(F):
    """Milky jade belly, matte (no highlight): its sides start one tone darker on a wavy line (no straight border
    with the flanks), the rest a soft gradient."""
    w, h = F.w, F.h
    out = []
    for y in range(h):
        row = []
        for x in range(w):
            t = 2.0 + 0.5 * F.light
            if F.name not in ("up", "down"):
                edge = 1.2 + 0.8 * math.sin(x * 0.55)
                if y < edge:
                    t -= 1.0
                t -= 0.5 * (y + 0.5) / h
            row.append(quantize(t, x, y, BELLY, 0))
        out.append(row)
    return out


def jaw(F):
    """Lower jaw: skin, a soft shadow line (#389B6C, not black) on its top edge for the closed mouth, belly below."""
    if F.name == "down":
        return belly(F)
    px = emerald(4.0, (8.0, 26.0))(F)
    if F.name in ("north", "east", "west"):
        px[0] = [SKIN[2]] * F.w
    return px


def muzzle_tip(F):
    """Front of the muzzle (density 2): lighter, two small nostrils in deep green (never black)."""
    px = emerald(4.3, (8.0, 26.0), gloss=0.9)(F)
    if F.name == "north" and F.w >= 10:
        for x in (F.w // 2 - 5, F.w // 2 + 4):
            px[2][x] = SKIN[1]
            px[2][x + 1] = SKIN[2]
    return px


def eye(outer):
    def f(F):
        """Narrow almond eye (density 2) on its outer face: heavy upper lid sloping down towards the snout (anger),
        iris dark under the lid, deep green in the middle, lighter low; narrow vertical pupil a little forward and
        low (it watches its prey); one tiny glint. No black outline: lids in deep greens."""
        w, h = F.w, F.h
        px = [[SKIN[3]] * w for _ in range(h)]
        if F.name != outer:
            return px
        fwd = 1 if outer == "east" else -1                  # u grows towards the front on the east face (face_point)
        cy = h * 0.55
        for x in range(w):
            t = (x + 0.5) / w
            front = t if fwd == 1 else 1 - t                 # 1 at the inner (front) corner
            open_ = math.sqrt(max(0.0, 1 - (2 * t - 1) ** 2))
            top = max(cy - 0.42 * h * open_, h * (0.08 + 0.55 * front))     # lid line: lower towards the snout
            bottom = cy + 0.34 * h * open_
            for y in range(h):
                if top <= y + 0.5 <= bottom:
                    rel = (y + 0.5 - top) / max(0.5, bottom - top)
                    px[y][x] = EYE["iris_dark"] if rel < 0.28 else EYE["iris"] if rel < 0.6 else EYE["iris_light"]
                elif top - 1.8 <= y + 0.5 < top:
                    px[y][x] = SKIN[1]                          # heavy lid edge, deep green
                elif y + 0.5 > bottom and y + 0.5 <= bottom + 1:
                    px[y][x] = SKIN[2]
        pc = int(round(w / 2 - 0.5 + fwd * w * 0.1))
        for y in range(h):
            for x in (pc, pc + 1):
                if px[y][x] in (EYE["iris"], EYE["iris_light"], EYE["iris_dark"]) and y >= h * 0.3:
                    px[y][x] = EYE["pupil"]
        gx = pc - fwd * 2
        for y in range(h):
            if px[y][gx] == EYE["iris"]:
                px[y][gx] = EYE["glint"]
                break
        return px
    return f


def toe(F):
    """Toe (density 2): body greens, rounded end a touch lighter, no claw, no outline."""
    px = emerald(3.8, (0.0, 13.0), inclusions=0)(F)
    if F.name == "north":
        px = [[SKIN[5] if 0 < x < F.w - 1 and 0 < y < F.h - 1 else SKIN[4] for x in range(F.w)] for y in range(F.h)]
    return px


BODY, HEAD, LEG = (5.0, 24.0), (8.0, 27.0), (0.0, 13.0)
PAINTERS = {
    "skin": emerald(4.0, BODY), "back": emerald(4.1, BODY, gloss=1.0), "shoulder": emerald(4.0, BODY, gloss=0.9),
    "flank": emerald(3.8, BODY), "neck": emerald(4.0, HEAD),
    "head": emerald(4.0, HEAD, inclusions=110), "head_top": emerald(4.2, HEAD, gloss=1.1, gloss_at=0.55),
    "muzzle": emerald(4.3, HEAD, gloss=0.8, inclusions=90), "muzzle_tip": (muzzle_tip, 2),
    "brow": emerald(3.9, HEAD, grad=0.5), "lid": emerald(3.6, HEAD, grad=0.3, inclusions=0), "socket": emerald(3.5, HEAD, grad=0.4, inclusions=0),
    "jaw": jaw, "belly": belly,
    "limb": emerald(4.0, LEG, gloss=0.8), "joint": emerald(3.6, LEG, inclusions=0),
    "joint_body": emerald(3.6, BODY, inclusions=0), "toe": (toe, 2),
    "eye_r": (eye("east"), 2), "eye_l": (eye("west"), 2),
}
# tail: the base close to the body colour, a little darker towards the tip, longitudinal highlight on top
for k in range(11):
    PAINTERS[f"tail_{k}"] = emerald(4.0 - 0.08 * k, local=True, grad=1.1, gloss=0.8 - 0.05 * k, gloss_at=0.5)

MATERIAL_TEXT = {
    "skin": "peau d'émeraude (#70CC98, dégradé vertical)", "back": "peau d'émeraude, reflet poli sur le dessus",
    "shoulder": "peau d'émeraude, reflet poli", "flank": "peau d'émeraude un peu plus sombre",
    "neck": "peau d'émeraude", "head": "peau d'émeraude, inclusions discrètes",
    "head_top": "peau d'émeraude claire, reflet poli", "muzzle": "peau d'émeraude claire, reflet poli",
    "muzzle_tip": "peau claire, deux narines vert profond", "brow": "arcade lourde au-dessus de l'œil",
    "socket": "peau plus sombre autour de l'œil", "lid": "paupière (cachée dans l'orbite, sortie par les animations)", "jaw": "peau, ligne d'ombre de la bouche, dessous jade",
    "belly": "ventre jade laiteux (#91DDB0), mat", "limb": "peau d'émeraude, reflet sur le dessus",
    "joint": "peau plus sombre (articulation)", "joint_body": "peau plus sombre (jonction)",
    "toe": "peau, bout arrondi plus clair", "tail": "peau d'émeraude, plus sombre vers le bout, reflet longitudinal",
    "eye_r": "œil plissé (iris #2E9667, pupille fendue #123D31)", "eye_l": "œil plissé (iris #2E9667, pupille fendue #123D31)",
}


# --------------------------------------------------------------------------- geometry helpers
def norm(v):
    n = math.sqrt(sum(a * a for a in v))
    return [a / n for a in v]


def cross(a, b):
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def frame(axis, hint, along):
    """Rotation whose local `along` axis (y or z) is `axis`, the other one as close as possible to `hint`."""
    a = norm(axis)
    o = norm([hint[i] - dot(hint, a) * a[i] for i in range(3)])
    y, z = (a, o) if along == "y" else (o, a)
    x = cross(y, z)
    return [[x[i], y[i], z[i]] for i in range(3)]


def rod(p_from, p_to, w, t, along, hint, ext=0.0):
    """Box from p_from to p_to, lengthened by `ext` at both ends: along='y' for a leg segment (p_from is the top
    end), along='z' for a tail segment or a toe (north face towards p_to)."""
    d = [p_from[i] - p_to[i] for i in range(3)]
    L = math.sqrt(dot(d, d)) + 2 * ext
    c = [(p_from[i] + p_to[i]) / 2 for i in range(3)]
    return c, ((w, L, t) if along == "y" else (w, t, L)), frame(d, hint, along)


def face_point(face, size, u, v):
    """Local point of a cube face at texture coordinates (u, v) in 0..1 (Blockbench face orientation)."""
    sx, sy, sz = (s / 2 for s in size)
    return {
        "north": (sx - 2 * sx * u, sy - 2 * sy * v, -sz), "south": (-sx + 2 * sx * u, sy - 2 * sy * v, sz),
        "east": (sx, sy - 2 * sy * v, sz - 2 * sz * u), "west": (-sx, sy - 2 * sy * v, -sz + 2 * sz * u),
        "up": (-sx + 2 * sx * u, sy, -sz + 2 * sz * v), "down": (-sx + 2 * sx * u, -sy, sz - 2 * sz * v),
    }[face]


# --------------------------------------------------------------------------- skeleton (drawing units, 16 = 1 block)
NECK = (0, 14, -35)
HEAD_UP = rot(10, 0, 0)          # head raised 10 degrees: it watches something in front of it
# Origin of the delivered model (where the creature carrying it stands): between the front shoulders (drawing z), so
# that its body box, its bite reach and every distance of its abilities start at its chest, not at the middle of
# its 8 blocks (half of them are tail).
ORIGIN_Z = -22.0
LID_REST, LID_OUT = -1.6, 2.7    # eyelid centre from the eye centre along its outward normal: hidden, then + LID_OUT


def EYE_AT(s):
    return (13.3 * s, 18.8, -47.8)


def lid_axis(s):
    """Outward normal of an eye in the head's drawing (before the head is raised)."""
    a = math.radians(24)
    return (s * math.cos(a), 0.0, -math.sin(a))


def lid_normal(s):
    """The same normal in the delivered model (head raised): the direction the lid slides to close."""
    return app(HEAD_UP, list(lid_axis(s)))
# legs: (shoulder or hip, elbow or knee, wrist or ankle, foot centre, foot yaw outward in degrees, foot size)
LEGS = {
    "front": ((14.0, 12.0, -22.0), (19.6, 7.6, -23.6), (20.4, 2.6, -25.6), (20.6, 1.4, -27.6), 18, (8.0, 2.8, 6.4)),
    "back": ((12.5, 12.0, 14.0), (18.6, 8.4, 11.2), (20.0, 2.6, 16.0), (20.4, 1.4, 15.0), 26, (8.6, 2.8, 7.0)),
}
TAIL_START = (0, 13.6, 20.5)
# tail: (length, width, height, pitch in degrees (negative = down), heading in degrees (+ = towards the right side))
TAIL = (
    (5.4, 16.0, 12.0, -6, 0), (5.2, 14.4, 11.0, -10, 12), (5.0, 12.8, 10.0, -12, 22), (4.8, 11.4, 9.2, -12, 26),
    (4.6, 10.0, 8.4, -10, 16), (4.4, 8.8, 7.6, -8, 0), (4.2, 7.6, 6.8, -6, -16), (4.0, 6.4, 5.8, -4, -24),
    (3.8, 5.2, 4.8, -2, -18), (3.6, 4.2, 4.0, 0, -8), (3.4, 3.2, 3.2, 0, 0),
)


def parts():
    P = []
    G = {"Root": ((0, 0, 0), None), "Body": ((0, 14, -5), "Root")}

    def add(g, name, c, size, r, mat):
        P.append((g, name, list(c), tuple(size), r, mat))

    # ---------------------------------------------------------------- body: low and long, wider at the front, smooth
    add("Body", "torse_avant", (0, 14.4, -21.0), (31, 13.6, 16), (2, 0, 0), "skin")
    add("Body", "torse_milieu", (0, 14.6, -7.0), (30, 14.0, 16), (0, 0, 0), "skin")
    add("Body", "torse_arriere", (0, 14.2, 5.0), (28, 13.0, 12), (-2, 0, 0), "skin")
    add("Body", "dos_avant", (0, 21.8, -16.0), (21, 2.4, 18), (2, 0, 0), "back")
    add("Body", "dos_arriere", (0, 21.4, 2.0), (19, 2.2, 18), (-2, 0, 0), "back")
    for s, side in ((1, "droit"), (-1, "gauche")):
        add("Body", f"flanc_{side}", (15.2 * s, 13.0, -10.0), (4.4, 10.0, 32), (0, -3 * s, 4 * s), "flank")
        add("Body", f"epaule_{side}", (13.6 * s, 15.0, -22.0), (6.0, 9.0, 11), (0, 0, 8 * s), "shoulder")
    add("Body", "ventre", (0, 7.4, -9.0), (31, 3.2, 40), (0, 0, 0), "belly")
    # neck: short, as wide as the head's back, following the raised head
    add("Body", "cou", (0, 14.6, -32.0), (27, 12.6, 9), (5, 0, 0), "neck")
    add("Body", "gorge", (0, 8.8, -32.0), (23, 3.0, 9), (5, 0, 0), "belly")
    # pelvis: narrower than the chest, start of the hind legs and the tail
    add("Body", "bassin", (0, 13.8, 16.0), (26, 12.0, 13), (0, 0, 0), "skin")
    add("Body", "bassin_dessus", (0, 20.2, 16.0), (18, 2.0, 11), (-3, 0, 0), "back")
    add("Body", "ventre_bassin", (0, 7.6, 15.0), (22, 3.2, 12), (0, 0, 0), "belly")
    for s, side in ((1, "droite"), (-1, "gauche")):
        add("Body", f"hanche_{side}", (12.4 * s, 12.6, 14.0), (5.0, 8.0, 9), (0, 0, 6 * s), "joint_body")

    # ---------------------------------------------------------------- head: wide, flat, round muzzle, raised 10 deg
    G["Head"] = (NECK, "Body")
    on_head = attach(NECK, HEAD_UP)

    def hd(name, c, size, r, mat, g="Head"):
        cc, M = on_head(c, r)
        add(g, name, cc, size, M, mat)

    hd("crane", (0, 17.6, -45.0), (24, 7.6, 17), (0, 0, 0), "head")
    hd("crane_dessus", (0, 21.8, -44.0), (19, 1.4, 13), (0, 0, 0), "head_top")
    hd("museau", (0, 15.8, -55.5), (21, 6.4, 6), (0, 0, 0), "muzzle")
    hd("museau_bout", (0, 15.6, -59.4), (15, 5.4, 2), (0, 0, 0), "muzzle_tip")
    G["Jaw"] = (on_head((0, 12.4, -38.0), (0, 0, 0))[0], "Head")
    hd("machoire", (0, 11.0, -48.0), (23, 4.0, 21), (0, 0, 0), "jaw", "Jaw")
    hd("menton", (0, 11.2, -58.9), (15, 3.2, 2.4), (0, 0, 0), "jaw", "Jaw")
    for s, side in ((1, "droite"), (-1, "gauche")):
        hd(f"joue_{side}", (11.6 * s, 14.6, -45.0), (3.6, 7.4, 14), (0, 10 * s, 0), "head")
        hd(f"orbite_{side}", (12.0 * s, 18.8, -47.4), (2.8, 7.6, 10.0), (0, 24 * s, 0), "socket")
        hd(f"oeil_{side}", EYE_AT(s), (1.4, 6.6, 8.8), (0, 24 * s, 0), "eye_r" if s > 0 else "eye_l")
        # eyelid hidden inside the orbit, slid out over the eye by the animations (closed eyes, blinks): LID_OUT
        # pixels along the eye's normal (lid_normal) from where it rests
        lid = f"Lid_{'right' if s > 0 else 'left'}"
        G[lid] = (on_head(EYE_AT(s), (0, 0, 0))[0], "Head")
        c0 = EYE_AT(s)
        n = lid_axis(s)
        hd(f"paupiere_{side}", [c0[i] + n[i] * (LID_REST) for i in range(3)], (0.8, 7.0, 9.2), (0, 24 * s, 0), "lid", lid)
        # heavy brow ridge right over the eye, lower towards the snout: the angry V seen from the front
        hd(f"arcade_{side}", (12.2 * s, 21.6, -48.4), (4.2, 2.2, 10.0), (-20, 24 * s, 0), "brow")

    # ---------------------------------------------------------------- legs: sprawled zigzag, joints a bit wider
    for kind, (S0, E0, W0, F0, yaw, fsize) in LEGS.items():
        for s, side, bone in ((1, "droite", "right"), (-1, "gauche", "left")):
            S, E, W, F = ([p[0] * s, p[1], p[2]] for p in (S0, E0, W0, F0))
            up_, low = (f"Leg_{kind}_{bone}", f"Forearm_{bone}" if kind == "front" else f"Shin_{bone}")
            G[up_] = (tuple(S), "Root")
            G[low] = (tuple(E), up_)
            u, l_ = ("bras", "avant_bras") if kind == "front" else ("cuisse", "jambe")
            j1, j2, j3 = ("epaule_rotule", "coude", "poignet") if kind == "front" else ("hanche_rotule", "genou", "cheville")
            big = 1.0 if kind == "front" else 1.12          # knees more marked than elbows
            add(up_, f"{j1}_{side}", S, (7.6, 7.6, 7.6), (0, 0, 0), "joint")
            add(up_, f"{u}_{side}", *rod(S, E, 7.0 * big, 7.0, "y", [0, 0, 1], 0.8), "limb")
            add(low, f"{j2}_{side}", E, (7.6 * big, 7.0 * big, 7.6 * big), (0, 0, 8 * s), "joint")
            add(low, f"{l_}_{side}", *rod(E, W, 6.0, 6.0, "y", [0, 0, 1], 0.8), "limb")
            add(low, f"{j3}_{side}", (W[0], W[1], W[2]), (6.8, 3.6, 6.4), (0, -yaw * s, 0), "joint")
            # wide flat foot turned outward, four short round toes fanned out and touching the ground
            foot = attach(F, rot(0, -yaw * s, 0))
            fw, fh, fl = fsize
            pieces = [(f"pied_{side}", F, fsize, (0, 0, 0), "limb")]
            front = F[2] - fl / 2
            for k in range(4):
                t = k / 3 - 0.5
                root = (F[0] + t * (fw - 2.0), 1.1, front + 0.6)
                ang = math.radians(t * 44)
                L = 4.0 if abs(t) < 0.3 else 3.4
                tip = (root[0] + math.sin(ang) * L, 1.0, root[2] - math.cos(ang) * L)
                pieces.append((f"doigt_{kind}_{side}_{k + 1}", *rod(root, tip, 2.0, 2.0, "z", [0, 1, 0]), "toe"))
            for name, c, size, r, mat in pieces:
                cc, M = foot(c, r)
                add(low, name, cc, size, M, mat)

    # ---------------------------------------------------------------- tail: 11 segments, soft S seen from above
    p, parent = list(TAIL_START), "Body"
    for k, (L, w, h, pitch, heading) in enumerate(TAIL):
        a, b = math.radians(pitch), math.radians(heading)
        d = [math.cos(a) * math.sin(b), math.sin(a), math.cos(a) * math.cos(b)]
        q = [p[i] + d[i] * L for i in range(3)]
        g = f"Tail_{k + 1}"
        G[g] = (tuple(p), parent)
        add(g, f"queue_{k + 1}", *rod(p, q, w, h, "z", [0, 1, 0], 1.4 if k == 0 else 0.9), f"tail_{k}")
        parent, p = g, q

    order, done = [], set()
    while len(order) < len(G):
        for k2, (pv, par) in G.items():
            if k2 not in done and (par is None or par in done):
                order.append(k2)
                done.add(k2)
    return P, {k2: G[k2] for k2 in order}


# --------------------------------------------------------------------------- build
def lit(P):
    """One material per piece: its painter receives each face's light and the world position of every pixel."""
    mats, out = {}, []
    for i, (g, n, c, size, r, m, raw) in enumerate(P):
        M = matrix(r)
        p = PAINTERS[m]
        fn, dens = p if isinstance(p, tuple) else (p, 1)

        def painter(w, h, rr, face, fn=fn, M=M, c=c, size=size):
            light = dot(app(M, NORMALS[face]), LIGHT)

            def at(x, y):
                q = app(M, face_point(face, size, (x + 0.5) / w, (y + 0.5) / h))
                return [c[j] + q[j] for j in range(3)]
            return fn(Face(w, h, face, light, at, rr))
        key = f"{m}@{i}"
        mats[key] = (painter, dens)
        out.append((g, n, c, size, r, key, raw))
    return out, mats


def build():
    raw, groups = parts()
    raw = [(g, n, [c[0], c[1], c[2] - ORIGIN_Z], size, r, m) for g, n, c, size, r, m in raw]
    groups = {k: ((pv[0], pv[1], pv[2] - ORIGIN_Z), par) for k, (pv, par) in groups.items()}
    P, groups, dims = ground(raw, groups, scale=1.0, center=False)
    P, mats = lit(P)
    uv, atlases, sizes = bake(P, mats, {})
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    data["textures"] = data["textures"][:1]          # nothing glows: no emissive texture
    return P, groups, dims, data, atlases, sizes


VIEWS = (("face", 0, 4), ("profil droit", 90, 3), ("profil gauche", 270, 3), ("dessus", 0, 89),
         ("arrière", 180, 6), ("trois quarts avant", 35, 16), ("trois quarts arrière", 215, 16))


def sheet(data, atlases, sizes, path, size=420):
    """Two rows of four: the seven views of the brief, then the silhouette."""
    import paths  # noqa: F401  (scripts/influcard on the path)
    import apercu as A
    from png_util import write
    els = data["elements"]
    bg = (58, 58, 62, 255)
    imgs = [A.render(els, atlases[0], yaw, pitch, size=size, bg=bg, margin=20) for _, yaw, pitch in VIEWS]
    imgs.append([[(20, 20, 22, 255) if px != bg else (205, 205, 210, 255) for px in row] for row in imgs[5]])
    rows = [imgs[:4], imgs[4:]]
    write(path, size * 4, size * 2, [sum((im[y] for im in row), []) for row in rows for y in range(size)])


FUNCTIONS = (
    ("torse", "tronc"), ("dos", "ligne du dos"), ("flanc", "flanc arrondi"), ("epaule_rotule", "articulation de l'épaule"),
    ("epaule", "épaule (avant du tronc plus large)"), ("ventre", "ventre"), ("cou", "cou"), ("gorge", "dessous du cou"),
    ("bassin", "bassin"), ("hanche_rotule", "articulation de la hanche"), ("hanche", "hanche"), ("crane", "crâne aplati"),
    ("museau", "museau arrondi"), ("machoire", "mâchoire inférieure"), ("menton", "avant de la mâchoire"),
    ("joue", "joue"), ("arcade", "arcade au-dessus de l'œil (regard en colère)"), ("orbite", "contour de l'œil"),
    ("oeil", "œil"), ("paupiere", "paupière (clignement, yeux fermés)"), ("bras", "segment supérieur de patte avant"), ("coude", "coude"),
    ("avant_bras", "segment inférieur de patte avant"), ("poignet", "poignet"),
    ("cuisse", "segment supérieur de patte arrière"), ("genou", "genou"), ("jambe", "segment inférieur de patte arrière"),
    ("cheville", "cheville"), ("pied", "pied"), ("doigt", "doigt"), ("queue", "segment de queue"),
)


def table(P, groups, path):
    lines = ["| Élément | Fonction | Os (raccordé à) | Centre x, y, z | Taille x, y, z | Rotation x, y, z | Matière |",
             "| ------- | -------- | --------------- | -------------- | -------------- | ---------------- | ------- |"]
    for g, n, c, size, r, m, raw in P:
        fn = next((t for pre, t in FUNCTIONS if n.startswith(pre)), "")
        rr = euler(matrix(r))
        parent = groups[g][1]
        mat = m.split("@")[0]
        lines.append(f"| `{n}` | {fn} | `{g}` ({'`' + parent + '`' if parent else 'racine'}) | "
                     f"{c[0]:.1f}, {c[1]:.1f}, {c[2]:.1f} | {size[0]:.1f}, {size[1]:.1f}, {size[2]:.1f} | "
                     f"{int(round(rr[0]))}, {int(round(rr[1]))}, {int(round(rr[2]))} | "
                     f"{MATERIAL_TEXT['tail' if mat.startswith('tail') else mat]} |")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def palette_share(atlases):
    """Share of each skin colour among the painted texels (check of the brief's proportions)."""
    count = {c: 0 for c in SKIN}
    total = 0
    for row in atlases[0]:
        for px in row:
            if px[3] and tuple(px[:3]) in count:
                count[tuple(px[:3])] += 1
                total += 1
    return {"#%02X%02X%02X" % c: round(100 * n / max(1, total)) for c, n in count.items()}


if __name__ == "__main__":
    args = sys.argv[1:]
    opts = {}
    for flag in ("--apercu", "--table"):
        if flag in args:
            i = args.index(flag)
            opts[flag] = args[i + 1]
            del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims, data, atlases, sizes = build()
    # the animations (salamandrex_anim.py) are part of the delivered model
    import salamandrex_anim
    anims = salamandrex_anim.build(data, lambda name: str(uuid.uuid5(NS, f"{IDENT}/{name}")))
    for anim in anims:
        anim.pop("_values", None)
    data["animations"] = anims
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    body_w = max(q[0] for g, n, c, size, r, m, raw in P if g == "Body" for q in corners(c, size, r)) * 2 / 16
    print(f"{len(P)} volumes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; "
          f"corps {body_w:.2f} de large ; texture {sizes[0]}")
    print("peau (% des texels) :", palette_share(atlases))
    print("écrit", path)
    if "--apercu" in opts:
        sheet(data, atlases, sizes, opts["--apercu"])
    if "--table" in opts:
        table(P, groups, opts["--table"])
