#!/usr/bin/env python3
"""Salamandrex: boss, a fantasy salamander standing on its hind legs. STATIC Blockbench model (2026-11-11).

A real salamander that learnt to stand, never a dragon, a dinosaur or a lizard-man: wide flat head with big side eyes
and a short round snout, short thick neck, compact torso leaning forward, pale pink belly, two small forelimbs held in
front of it, short sturdy hind legs (thigh, knee, shin, wide flat foot), long tail leaving the back of the pelvis,
dipping then rising to a glowing crystal tip. Four small crystals grow on the skull (two pairs, the front pair bigger).

Same building language as the otter (loutre_marees.py) and the stone turtle (colosse_abime.py): big tilted volumes,
details painted at 1 texel per unit (2 for eyes, crystals, fingers and toes), smooth satin skin, no scales, no spikes.
The tail tip is on the emissive texture, in the `glow_tail_tip` bone (full brightness in BetterModel).

The pose is baked in the cubes; bones sit at the joints for a future animation. The whole model is scaled so that its
height (crystals included) is HEIGHT blocks; every size below is in drawing units (16 = 1 block before scaling).

Usage: salamandrex.py [output folder] [--apercu <sheet.png>]
Front faces north (-z), the monster's right side is +x, ground at y = 0, origin between the feet.
"""
import json
import math
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, corners, export, ground, hx, tone  # noqa: E402
from golem_fonderie import rot  # noqa: E402

IDENT = "salamandrex"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000051a1a0")
HEIGHT = 4.5          # blocks, crystals included: the only global size setting


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- palettes of the brief (dark -> light)
SKIN = pal("#39204F", "#512C78", "#713D9B", "#8250AA", "#9966BC", "#B184CE")        # principal = 2
BELLY = pal("#A9688D", "#C985A5", "#D99FB6", "#E8B8C9", "#F4D0DA", "#F9E1E6")       # principal = 3
TAIL = pal("#492B68", "#713D9B", "#8250AA", "#9966BC", "#B184CE")                   # principal = 1
LIMB = pal("#4D2C70", "#713D9B", "#8953AC", "#A77BC4")                              # principal = 1
CRYSTAL = pal("#6436A8", "#8952D1", "#A86BE8", "#C99BFF", "#E7D2FF", "#F4E9FF")     # main face = 2
TIP = pal("#8537B1", "#A64CCB", "#D45DEB", "#E987F5", "#F5B5F6", "#FFF0FF")         # centre = 4-5
EYE_RIM, IRIS, IRIS_2, PUPIL = hx("#382044"), hx("#D7A0E8"), hx("#B66BD5"), hx("#24142E")
GLINT, GLINT_2, NOSTRIL = hx("#FFF0FF"), hx("#E6C4FA"), hx("#392044")
INCLUSION = pal("#A86BE8", "#C99BFF", "#E7D2FF")

# light from above: top faces lighter, sides as the front (their low rows darker), undersides in deep shadow
SHADE = {"up": 1, "north": 0, "south": 0, "east": 0, "west": 0, "down": -2}


# --------------------------------------------------------------------------- painters
def gloss(px, r, pal_, b, face, amount=60):
    """Wet satin skin: a few short horizontal streaks two tones lighter, on top faces and high on the sides."""
    h, w = len(px), len(px[0])
    if face == "down":
        return
    for _ in range(max(1, w * h // amount)):
        y = r.randrange(h) if face == "up" else r.randrange(max(1, h // 3))
        x = r.randrange(w)
        for k in range(r.choice((1, 2, 2, 3))):
            if x + k < w:
                px[y][x + k] = tone(pal_, b + (2 if r.random() < 0.6 else 1))


def skin(base=2, pal_=SKIN, shine=60, marks=False, noise=34):
    def f(w, h, r, face):
        """Smooth violet skin: flat principal tone, a few pixels of the next tones (no spots, no scales), lit top
        edge, darker low rows on the sides, glossy streaks. `marks`: three to five small crystal inclusions on the
        back face (south), set into the skin, never spikes."""
        b = base + SHADE[face]
        px = [[tone(pal_, b)] * w for _ in range(h)]
        for _ in range(max(1, w * h // noise)):
            x, y = r.randrange(w), r.randrange(h)
            px[y][x] = tone(pal_, b + (1 if r.random() < 0.5 else -1))
        if face in ("north", "south", "east", "west") and h >= 4:
            px[h - 1] = [tone(pal_, b - 1)] * w
            px[h - 2] = [tone(pal_, b - 1) if (x + r.randrange(2)) % 2 else px[h - 2][x] for x in range(w)]
            px[0] = [tone(pal_, b + 1) if r.random() < 0.5 else px[0][x] for x in range(w)]
        gloss(px, r, pal_, b, face, shine)
        if marks and face == "south" and w >= 8 and h >= 10:
            spots = ((0.3, 0.18), (0.68, 0.26), (0.5, 0.45), (0.28, 0.62), (0.72, 0.7))
            for k, (fx, fy) in enumerate(spots[:3 + (w * h > 160) + (w * h > 220)]):
                cx, cy = int(fx * w), int(fy * h)
                for dx, dy, t in (((0, 0, 1), (1, 0, 0), (0, -1, 2)) if k % 2 else ((0, 0, 0), (0, -1, 1))):
                    if 0 <= cx + dx < w and 0 <= cy + dy < h:
                        px[cy + dy][cx + dx] = INCLUSION[t]
        return px
    return f


def belly(w, h, r, face):
    """Pale pink belly, rounded by the painting: lit upper centre, softer edges, shadow under it and at the sides
    (joins with the violet), a few very light reflections. Hard pixel steps, never a smooth gradient."""
    if face == "down":
        return [[BELLY[0]] * w for _ in range(h)]
    if face == "up":
        return [[BELLY[4] if (x + y) % 3 else BELLY[3] for x in range(w)] for y in range(h)]
    if face != "north":
        px = [[BELLY[2]] * w for _ in range(h)]
        px[h - 1] = [BELLY[1]] * w
        if h >= 6:
            px[h - 2] = [BELLY[1] if x % 2 else BELLY[2] for x in range(w)]
        return px
    px = []
    for y in range(h):
        row = []
        for x in range(w):
            nx, ny = (x + 0.5) / w * 2 - 1, (y + 0.5) / h * 2 - 1
            d = nx * nx + 0.7 * ny * ny + (0.25 * ny if ny > 0 else 0)
            t = 3
            if d > 0.7:
                t = 2 if (x + y) % 2 or d > 0.85 else 3   # pixel dithering on the edge of the dome, not a gradient
            if d > 1.15:
                t = 2
            if ny < -0.15 and abs(nx) < 0.38 and d < 0.4:
                t = 4
            row.append(BELLY[t])
        px.append(row)
    px[h - 1] = [BELLY[1]] * w
    for _ in range(max(1, w * h // 70)):
        x, y = r.randrange(w // 4, max(w // 4 + 1, 3 * w // 4)), r.randrange(max(1, h // 2))
        px[y][x] = BELLY[5]
    return px


def belly_side(w, h, r, face):
    """Pink around the central dome (lower, upper and side volumes of the belly): flat secondary pink, a few main-tone
    pixels, only the very bottom in shadow: no frame, so that the belly reads as one rounded mass."""
    if face == "down":
        return [[BELLY[0]] * w for _ in range(h)]
    b = 3 if face == "up" else 2
    px = [[BELLY[b] if (x * 7 + y * 3 + r.randrange(3)) % 5 else BELLY[b + 1] for x in range(w)] for y in range(h)]
    if face == "north" and h >= 5:
        px[h - 1] = [BELLY[1]] * w
    elif face in ("east", "west"):
        px[h - 1] = [BELLY[1]] * w
    return px


def throat(w, h, r, face):
    """Under the jaw: the pink of the belly comes up under the head; violet on the sides."""
    if face == "down":
        return belly(w, h, r, "north")
    return skin(2)(w, h, r, face)


def jaw(w, h, r, face):
    """Lower head: violet, mouth slit (dark line) along its top edge on the front and the sides, pink under it."""
    if face == "down":
        return belly(w, h, r, "north")
    px = skin(2, shine=40)(w, h, r, face)
    if face in ("north", "east", "west"):
        px[0] = [NOSTRIL] * w
        px[1] = [SKIN[1] if x % 3 == 0 else px[1][x] for x in range(w)]
    return px


def crystal(w, h, r, face):
    """Head crystal (density 2): every face its own light, a brighter diagonal facet band, almost white edge on the
    lit faces, sparkle at the top, darker bottom rows where it comes out of the skin."""
    b = {"up": 3, "north": 2, "west": 3, "east": 1, "south": 1, "down": 0}[face]
    px = [[tone(CRYSTAL, b + (1 if (x - y) % 5 == 0 else 0)) for x in range(w)] for y in range(h)]
    if face in ("north", "west", "up"):
        for y in range(h):
            px[y][0] = tone(CRYSTAL, 5 if y % 3 else 4)
        if h >= 2 and w >= 3:
            px[1][1] = CRYSTAL[5]
    if w >= 2:
        for y in range(h):
            px[y][w - 1] = tone(CRYSTAL, b - 1)
    if face not in ("up", "down") and h >= 4:
        px[h - 1] = [CRYSTAL[0]] * w
        px[h - 2] = [tone(CRYSTAL, b - 1)] * w
    return px


def tip_glow(w, h, r, face):
    """Tail tip crystal (emissive, density 2): light centre, magenta edges, side faces darker, near-white edge pixels;
    the light stays inside the crystal (no halo). The pieces lie along their local z: on the side faces the length runs
    along u, so the centre / edge shading follows v there (lengthwise bands, never stripes across the crystal)."""
    b = {"north": 4, "up": 4, "south": 3, "east": 3, "west": 2, "down": 1}[face]
    px = []
    for y in range(h):
        row = []
        for x in range(w):
            n = abs(((y + 0.5) / h if face in ("east", "west") else (x + 0.5) / w) * 2 - 1)
            row.append(tone(TIP, b + (1 if n < 0.34 else -1 if n > 0.7 else 0)))
        px.append(row)
    if face in ("up", "east") and h >= 3:
        for x in range(w):
            px[0][x] = TIP[5] if x % 3 else TIP[4]
    return px


def tip_base(w, h, r, face):
    """Joint of the tail and the crystal (emissive): magenta with a few very bright pink pixels around it."""
    px = [[TIP[1]] * w for _ in range(h)]
    for _ in range(max(2, w * h // 6)):
        px[r.randrange(h)][r.randrange(w)] = TIP[r.choice((2, 3, 4))]
    return px


def eye(outer):
    def f(w, h, r, face):
        """Big round side eye (density 2) on its outer face: dark ring, lilac iris (deeper on its lower half), round
        pupil, a big glint up front and a small one low behind: curious, a bit unsettling, never angry."""
        if face != outer:
            return [[SKIN[1]] * w for _ in range(h)]
        px = [[SKIN[1]] * w for _ in range(h)]
        cx, cy, R = (w - 1) / 2, (h - 1) / 2, min(w, h) / 2
        for y in range(h):
            for x in range(w):
                d = math.hypot(x - cx, y - cy)
                if d <= R - 0.2:
                    px[y][x] = EYE_RIM
                if d <= R - 1.2:
                    px[y][x] = IRIS_2 if y > cy + 0.6 else IRIS
                if d <= R * 0.42:
                    px[y][x] = PUPIL
        # u = 0 is the back of the head on the east face, the front on the west face
        step = -1 if outer == "east" else 1           # towards the front of the head, in u
        gx, gy = int(round(cx + step * R * 0.3)), int(round(cy - R * 0.35))
        for dx, dy in ((0, 0), (step, 0), (0, 1), (step, 1)):
            if 0 <= gx + dx < w and 0 <= gy + dy < h:
                px[gy + dy][gx + dx] = GLINT
        bx, by = int(round(cx - step * R * 0.35)), int(round(cy + R * 0.4))
        if 0 <= bx < w and 0 <= by < h:
            px[by][bx] = GLINT_2
        return px
    return f


def socket(w, h, r, face):
    """Rim around the eye: a darker violet, as a natural shadow (no make-up, no pattern)."""
    px = skin(1)(w, h, r, face)
    if face in ("east", "west"):
        for x in range(w):
            px[0][x] = SKIN[3]
    return px


def muzzle(tip=False):
    def f(w, h, r, face):
        """Snout: a violet one tone lighter than the head; mouth line low on the sides and the front; `tip`: two
        small dark nostrils high on the front."""
        px = skin(3)(w, h, r, face)
        if face in ("east", "west", "north") and h >= 4:
            y = h - 2
            px[y] = [NOSTRIL] * w
            px[h - 1] = [SKIN[2]] * w
        if tip and face == "north" and w >= 6:
            for x in (w // 2 - 3, w // 2 + 2):
                px[1][x] = NOSTRIL
                px[1][x + 1 if x + 1 < w else x] = NOSTRIL
        return px
    return f


def digit(w, h, r, face):
    """Finger or toe (density 2): short and fleshy, rounded lighter end, no claw."""
    b = {"up": 2, "down": 0}.get(face, 1)
    px = [[LIMB[b]] * w for _ in range(h)]
    if face == "north":
        px = [[LIMB[3] if 0 < x < w - 1 or 0 < y < h - 1 else LIMB[2] for x in range(w)] for y in range(h)]
    elif face == "up" and h >= 2:
        px[0] = [LIMB[3]] * w
    elif face in ("east", "west"):
        px[h - 1] = [LIMB[0]] * w
    return px


def limb(base=1):
    def f(w, h, r, face):
        px = skin(base + 1, LIMB + [LIMB[3]], shine=50)(w, h, r, face)
        return px
    return f


MATS = {
    "skin": skin(2), "flank": skin(2, shine=40), "back": skin(1, shine=160, marks=True, noise=70), "pelvis": skin(1),
    "shoulder": skin(2, shine=30), "neck": skin(2), "head": skin(2, shine=40), "head_top": skin(3, shine=30),
    "cheek": skin(2, shine=35), "jaw": jaw, "socket": socket, "throat": throat,
    "muzzle": muzzle(), "muzzle_tip": (muzzle(True), 2),
    "belly": belly, "belly_side": belly_side, "eye_r": (eye("east"), 2), "eye_l": (eye("west"), 2),
    "crystal": (crystal, 2),
    "arm": limb(1), "leg": skin(2, shine=45), "shin": skin(1, shine=45), "foot": limb(1), "digit": (digit, 2),
    "tail": skin(1, TAIL, shine=40), "tail_end": skin(1, TAIL, shine=30),
}
GLOW_MATS = {"tip": (tip_glow, 2), "tip_base": (tip_base, 2)}


# --------------------------------------------------------------------------- geometry helpers
def norm(v):
    n = math.sqrt(sum(a * a for a in v))
    return [a / n for a in v]


def cross(a, b):
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def frame(axis, hint, along):
    """Rotation matrix whose local `along` axis (y or z) is `axis`, the other one as close as possible to `hint`."""
    a = norm(axis)
    o = norm([hint[i] - dot(hint, a) * a[i] for i in range(3)])
    y, z = (a, o) if along == "y" else (o, a)
    x = cross(y, z)
    return [[x[i], y[i], z[i]] for i in range(3)]


def rod(p_from, p_to, w, t, along, hint, ext=0.0):
    """Box going from p_from to p_to (lengthened by `ext` at each end so joints overlap): along='y' for a limb
    (p_from is the upper end, north face forward when hint is +z), along='z' for a tail segment, finger or toe
    (north face towards p_to, the end; hint = up)."""
    d = [p_from[i] - p_to[i] for i in range(3)]
    L = math.sqrt(dot(d, d)) + 2 * ext
    c = [(p_from[i] + p_to[i]) / 2 for i in range(3)]
    M = frame(d, hint, along)
    size = (w, L, t) if along == "y" else (w, t, L)
    return c, size, M


def spin(hint, axis, deg):
    """Hint vector turned by deg around axis (gives a crystal piece its own facet orientation)."""
    k, a = norm(axis), math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    kx = cross(k, hint)
    return [hint[i] * c + kx[i] * s + k[i] * dot(k, hint) * (1 - c) for i in range(3)]


# --------------------------------------------------------------------------- skeleton (drawing units)
LEAN_PIVOT = (0, 20, 4)       # the upper body leans forward around the top of the pelvis
NECK = (0, 46.5, -5.0)
HEAD_TILT = rot(-5, 0, 0)     # head a little lowered
SHOULDER = (7.4, 40, -1.0)
HIP = (5.0, 19.6, 3.4)
KNEE = (7.4, 13.0, -0.6)
ANKLE = (6.2, 5.8, 1.8)
TAIL_START = (0, 18.6, 7.4)
# tail: (length, width, height, pitch in degrees, positive = up, yaw added in degrees, towards the monster's right)
TAIL = (
    (6.4, 9.6, 9.0, -8, 2, "tail"),
    (6.0, 8.0, 7.8, -17, 3, "tail"),
    (5.6, 6.6, 6.8, -21, 4, "tail"),
    (5.4, 5.4, 5.8, -12, 4, "tail"),
    (5.0, 4.4, 5.0, 0, 3, "tail"),
    (4.8, 3.8, 4.4, 10, 2, "tail"),
    (4.6, 3.2, 3.8, 20, 1, "tail_end"),
    (4.4, 2.8, 3.2, 25, 0, "tail_end"),
    (4.0, 2.4, 2.6, 15, -1, "tail_end"),
)
TAIL_LENGTH = 0.9            # stretches every segment: the brief asks 2.8 to 3 blocks of tail before the tip
# head crystals: (side x, base z, height, outward tilt, forward tilt (negative = backward), width)
CRYSTALS = (
    ("avant", 3.7, -9.4, 7.2, 12, 8, 3.4),
    ("arriere", 5.2, -4.8, 5.6, 10, -12, 2.9),
)


def parts():
    P = []
    G = {"Root": ((0, 0, 0), None), "Body": ((0, 19, 4), "Root")}

    def add(g, name, c, size, r, mat):
        P.append((g, name, list(c), size, r, mat))

    def lean(deg):
        return attach(LEAN_PIVOT, rot(-deg, 0, 0))

    def leaned(deg, g, name, c, size, r, mat):
        cc, M = lean(deg)(c, r)
        add(g, name, cc, size, M, mat)

    # ---------------------------------------------------------------- pelvis: wide, behind the torso, start of all
    add("Body", "bassin", (0, 19.5, 4.0), (13.6, 8, 12.8), (0, 0, 0), "pelvis")
    add("Body", "bassin_haut", (0, 23.2, 5.2), (11.2, 2.4, 9.6), (4, 0, 0), "back")
    for s, side in ((1, "droite"), (-1, "gauche")):
        add("Body", f"hanche_{side}", (6.0 * s, 18.4, 3.6), (3.6, 6.4, 6.8), (0, 0, 6 * s), "pelvis")

    # ---------------------------------------------------------------- torso: compact, wider low, leaning forward
    # Each volume leans by its own angle (more towards the top): the lean comes from the volumes, not one rotation.
    G["Torso"] = (LEAN_PIVOT, "Body")
    leaned(4, "Torso", "torse_bas", (0, 24.2, 2.6), (13.6, 6.4, 12.0), (0, 0, 0), "flank")
    leaned(7, "Torso", "torse", (0, 31.6, 1.8), (12.8, 19.2, 13.2), (0, 0, 0), "back")
    leaned(10, "Torso", "torse_haut", (0, 41.6, 1.0), (12.0, 8.8, 11.2), (0, 0, 0), "back")
    for s, side in ((1, "droit"), (-1, "gauche")):
        leaned(7, "Torso", f"flanc_{side}", (6.6 * s, 31.0, 2.0), (4.0, 12.0, 10.4), (0, 0, 4 * s), "flank")
        leaned(10, "Torso", f"epaule_{side}", (6.0 * s, 41.0, 0.6), (3.6, 5.2, 8.4), (0, 0, -6 * s), "shoulder")

    # ---------------------------------------------------------------- belly: pale pink, bulging, an upright oval
    leaned(5, "Torso", "ventre_bas", (0, 21.0, -3.6), (12.8, 6.4, 6.0), (0, 0, 0), "belly_side")
    leaned(7, "Torso", "ventre", (0, 28.4, -4.6), (11.2, 15.2, 5.6), (0, 0, 0), "belly")
    leaned(7, "Torso", "ventre_bombe", (0, 27.4, -7.0), (8.0, 10.4, 2.4), (0, 0, 0), "belly")
    leaned(10, "Torso", "ventre_haut", (0, 37.0, -3.4), (10.4, 5.6, 5.0), (0, 0, 0), "belly_side")
    for s, side in ((1, "droit"), (-1, "gauche")):
        leaned(7, "Torso", f"ventre_{side}", (5.6 * s, 28.6, -3.8), (3.2, 10.4, 4.8), (0, 14 * s, 0), "belly_side")

    # ---------------------------------------------------------------- neck and head
    G["Head"] = (NECK, "Torso")
    add("Torso", "cou", (0, 45.8, -4.8), (13.6, 7.6, 11.2), (-14, 0, 0), "neck")
    on_head = attach(NECK, HEAD_TILT)

    def hd(name, c, size, r, mat, g="Head"):
        cc, M = on_head(c, r)
        add(g, name, cc, size, M, mat)

    # wide, flat, a little triangular from above: wide back of the skull, cheeks closing in, short round snout
    # lower head = widest part (jaw line, mouth slit painted on its top edge), skull narrower on top of it
    hd("machoire", (0, 48.4, -10.0), (20, 4.4, 14.0), (0, 0, 0), "jaw")
    hd("crane", (0, 53.4, -7.4), (16.4, 6.6, 9.2), (0, 0, 0), "head")
    hd("crane_dessus", (0, 57.1, -8.2), (13, 1.2, 9.0), (0, 0, 0), "head_top")
    hd("chanfrein", (0, 52.4, -13.4), (13, 5.0, 4.4), (-6, 0, 0), "head")
    hd("museau", (0, 50.6, -16.4), (12, 4.6, 3.6), (-4, 0, 0), "muzzle")
    hd("museau_bout", (0, 50.4, -18.8), (8.4, 3.6, 1.6), (-4, 0, 0), "muzzle_tip")
    for s, side in ((1, "droite"), (-1, "gauche")):
        hd(f"joue_{side}", (9.6 * s, 49.4, -8.4), (2.4, 5.2, 9.0), (0, 14 * s, 0), "cheek")
        # big round side eyes, set high on the skull, turned a little forward
        hd(f"orbite_{side}", (8.2 * s, 54.6, -10.0), (2.4, 5.8, 5.8), (0, 15 * s, 0), "socket")
        hd(f"oeil_{side}", (9.3 * s, 54.8, -10.3), (1.4, 4.8, 4.8), (0, 15 * s, 0), "eye_r" if s > 0 else "eye_l")

    # four crystals: two pairs, front pair bigger and leaning forward, back pair leaning backward, all outward
    top = 57.7
    for kind, x, z, H, out, fwd, W in CRYSTALS:
        for s, side in ((1, "droit"), (-1, "gauche")):
            d = norm([s * math.tan(math.radians(out)), 1, -math.tan(math.radians(fwd))])
            base = [x * s, top - 1.0, z]

            def at(f, bend=(0, 0, 0)):
                return [base[i] + d[i] * f * H + bend[i] for i in range(3)]
            # base (wide), middle (narrower, turned), tip (thin, bent further out): the crystal grows unevenly
            tip_d = norm([d[0] + 0.12 * s, d[1], d[2] + (-0.06 if fwd > 0 else 0.06)])
            pieces = (
                ("base", at(-0.12), at(0.42), W, W * 0.85, 0),
                ("milieu", at(0.32), at(0.74, (0.2 * s, 0, 0)), W * 0.7, W * 0.62, 22),
                ("pointe", at(0.66, (0.2 * s, 0, 0)),
                 [at(0.66, (0.2 * s, 0, 0))[i] + tip_d[i] * 0.34 * H for i in range(3)], W * 0.4, W * 0.36, -18),
            )
            for pname, a, b, pw, pt, roll in pieces:
                c, size, M = rod(b, a, pw, pt, "y", spin([0, 0, 1], [b[i] - a[i] for i in range(3)], roll + 45 * s))
                cc, MM = on_head(c, M)
                add("Head", f"cristal_{kind}_{side}_{pname}", cc, size, MM, "crystal")

    # ---------------------------------------------------------------- forelimbs: short, bent, held in front
    for s, side, bone in ((1, "droit", "right"), (-1, "gauche", "left")):
        S = (SHOULDER[0] * s, SHOULDER[1], SHOULDER[2])
        E = (9.8 * s, 35.4, 0.8)                 # elbow out and a little back
        W = (8.8 * s, 32.4, -6.6)                # wrist forward
        Hc = (8.6 * s, 31.2, -8.8)               # palm
        up_, fo_ = f"Arm_{bone}", f"Forearm_{bone}"
        G[up_] = (lean(10)(S, (0, 0, 0))[0], "Torso")
        G[fo_] = (lean(10)(E, (0, 0, 0))[0], up_)
        pieces = [
            (up_, f"epaule_ronde_{side}", S, (3.8, 3.8, 3.8), (0, 0, 0), "arm"),
            (up_, f"bras_{side}", *rod(S, E, 3.2, 3.4, "y", [0, 0, 1], 1.0), "arm"),
            (fo_, f"coude_{side}", E, (3.0, 3.0, 3.0), (0, 0, 0), "arm"),
            (fo_, f"avant_bras_{side}", *rod(E, W, 2.8, 2.8, "y", [0, 0, 1], 0.9), "arm"),
        ]
        hand_dir = norm([W[i] - E[i] for i in range(3)])
        palm = rod(W, [Hc[i] + hand_dir[i] * 0.6 for i in range(3)], 3.8, 1.6, "z", [0, 1, 0], 1.2)
        pieces.append((fo_, f"main_{side}", *palm, "foot"))
        # four short round fingers fanned from the front edge of the palm
        Mp = palm[2]
        fwd = [-Mp[0][2], -Mp[1][2], -Mp[2][2]]          # local -z of the palm, world
        rgt = [Mp[0][0], Mp[1][0], Mp[2][0]]
        front = [palm[0][i] + fwd[i] * palm[1][2] / 2 for i in range(3)]
        for k in range(4):
            t = k / 3 - 0.5
            root = [front[i] + rgt[i] * t * 3.0 - fwd[i] * 0.4 for i in range(3)]
            fd = norm([fwd[i] + rgt[i] * t * 0.7 + (0, -0.35, 0)[i] for i in range(3)])
            L = 1.8 if abs(t) > 0.3 else 2.2
            tipp = [root[i] + fd[i] * L for i in range(3)]
            pieces.append((fo_, f"doigt_{side}_{k + 1}", *rod(root, tipp, 0.9, 1.0, "z", [0, 1, 0]), "digit"))
        for g, name, c, size, r, mat in pieces:
            cc, M = lean(10)(c, r)
            add(g, name, cc, size, M, mat)

    # ---------------------------------------------------------------- hind legs: short, sturdy, bent, feet wide
    for s, side, bone in ((1, "droite", "right"), (-1, "gauche", "left")):
        Hp = (HIP[0] * s, HIP[1], HIP[2])
        K = (KNEE[0] * s, KNEE[1], KNEE[2])
        A = (ANKLE[0] * s, ANKLE[1], ANKLE[2])
        lg, sh = f"Leg_{bone}", f"Shin_{bone}"
        G[lg] = (Hp, "Root")
        G[sh] = (K, lg)
        add(lg, f"cuisse_{side}", *rod(Hp, K, 7.2, 7.8, "y", [0, 0, 1], 1.8), "leg")
        add(lg, f"cuisse_muscle_{side}", *rod((Hp[0] + 0.8 * s, Hp[1] - 1, Hp[2] + 1.2),
                                              (K[0] + 0.4 * s, K[1] + 1.5, K[2] + 1.6), 4.6, 4.4, "y", [0, 0, 1]),
            "leg")
        add(sh, f"genou_{side}", (K[0], K[1], K[2] - 0.8), (5.6, 4.6, 5.2), (-10, 0, 4 * s), "leg")
        add(sh, f"tibia_{side}", *rod(K, A, 4.8, 5.0, "y", [0, 0, 1], 1.2), "shin")
        add(sh, f"talon_{side}", (A[0], 3.2, A[2] + 0.4), (5.2, 3.4, 4.6), (0, 0, 0), "shin")
        # flat wide foot turned a little outward, four short round toes
        fc = (A[0] + 0.3 * s, 1.2, A[2] - 2.0)
        foot = attach(fc, rot(0, -10 * s, 0))
        fparts = [(f"pied_{side}", fc, (7.0, 2.4, 5.2), (0, 0, 0), "foot")]
        fx = fc[2] - 2.4
        for k in range(4):
            t = k / 3 - 0.5
            root = (fc[0] + t * 4.8, 1.0, fx + 0.5)
            ang = math.radians(t * 36)
            L = 2.4 if abs(t) > 0.3 else 2.9
            tipp = (root[0] + math.sin(ang) * L, 0.75, root[2] - math.cos(ang) * L)
            fparts.append((f"orteil_{side}_{k + 1}", *rod(root, tipp, 1.4, 1.6, "z", [0, 1, 0])))
            fparts[-1] = fparts[-1] + ("digit",)
        for name, c, size, r, mat in fparts:
            cc, M = foot(c, r)
            add(sh, name, cc, size, M, mat)

    # ---------------------------------------------------------------- tail: 9 segments, dips then rises
    p = list(TAIL_START)
    yaw = 0.0
    parent = "Body"
    for k, (L, w, h, pitch, dyaw, mat) in enumerate(TAIL):
        yaw += dyaw
        a, b = math.radians(pitch), math.radians(yaw)
        d = [math.cos(a) * math.sin(b), math.sin(a), math.cos(a) * math.cos(b)]
        L *= TAIL_LENGTH
        q = [p[i] + d[i] * L for i in range(3)]
        g = f"Tail_{k + 1}"
        G[g] = (tuple(p), parent)
        add(g, f"queue_{k + 1}", *rod(p, q, w, h, "z", [0, 1, 0], 0.9 if k else 1.6), mat)
        parent, p = g, q
    # glowing crystal tip: narrow collar, wider middle, long sharp point (faceted: turned 45 degrees on its axis)
    a, b = math.radians(18), math.radians(yaw)
    d = [math.cos(a) * math.sin(b), math.sin(a), math.cos(a) * math.cos(b)]

    def along(f):
        return [p[i] + d[i] * f for i in range(3)]
    G["glow_tail_tip"] = (tuple(p), parent)
    up45 = spin([0, 1, 0], d, 45)
    for name, f0, f1, w, h, hint, mat in (
            ("pointe_collier", -0.6, 1.4, 2.6, 2.6, [0, 1, 0], "tip_base"),
            ("pointe_coeur", 1.0, 4.8, 3.6, 3.6, up45, "tip"),
            ("pointe_haute", 4.2, 7.2, 2.4, 2.4, [0, 1, 0], "tip"),
            ("pointe_fine", 6.8, 8.8, 1.2, 1.2, up45, "tip")):
        add("glow_tail_tip", name, *rod(along(f0), along(f1), w, h, "z", hint), mat)

    order, done = [], set()
    while len(order) < len(G):
        for k2, (pv, par) in G.items():
            if k2 not in done and (par is None or par in done):
                order.append(k2)
                done.add(k2)
    return P, {k2: G[k2] for k2 in order}


def _corners(raw):
    return [q for g, n, c, size, r, m in raw for q in corners(c, size, r)]


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = None
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    raw, groups = parts()
    _, _, dims = ground(raw, groups, scale=1.0, center=False)
    P, groups, dims = ground(raw, groups, scale=HEIGHT / dims[1], center=False)
    uv, atlases, sizes = bake(P, MATS, GLOW_MATS)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print(f"{len(P)} volumes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; "
          f"textures {sizes[0]} + {sizes[1]}")
    k = HEIGHT * 16 / (max(c[1] for c in _corners(raw)) - min(c[1] for c in _corners(raw)))
    print(f"queue {sum(t[0] for t in TAIL) * TAIL_LENGTH * k / 16:.2f} blocs hors pointe, pointe {8.8 * k / 16:.2f}")
    print("écrit", path)
    if prev:
        apercu(data, atlases, sizes, prev, set(TIP), views=((35, 10), (0, 4), (90, 2), (215, 12), (180, 8)), size=420)
