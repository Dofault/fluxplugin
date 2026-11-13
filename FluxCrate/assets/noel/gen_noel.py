"""Christmas crate of FluxCrate (crates/noel.yml): an enchanted winter reliquary and its candy cane key.

The reliquary: a casket of mahogany panels in a walnut frame on a moulded plinth, brass corners, feet, bands and
handles, a stepped lid with a gold snowflake medallion around an ice crystal, holly and a little snow. It is cut in
parts that FluxCrate moves one by one (crates/noel.yml, « effects.chest »):

    caisse_noel_corps       the body (plinth, walls, velvet lining, lock plate, hinges)
    caisse_noel_couvercle   the lid, turning on its hinge at the back (y 9.5, z 13)
    caisse_noel_medaillon   the medallion and crystal, turning on the vertical axis through the centre of the model
    caisse_noel_serrure     glow of the keyhole (when a key is used)
    caisse_noel_lueur       light of the inside and of the seam between body and lid
    caisse_noel_rayon       beam of light rising from the open crate (scaled from the cavity floor, y 8)
    caisse_noel             everything closed: the item in hand and in inventories

The key (influkey:cle_noel): a candy cane bent into a key, gold collar and bit, holly, a small star, with a sheen
sliding along it (animated texture).

    <venv>/python3 gen_noel.py <plugins dir of a server> [renders dir]
    then /iazip in game.

Writes contents/influcrates and contents/influkey of ItemsAdder (models, textures, noel.yml), the Blockbench
projects next to this script and in the BBModels folder of each pack, and with a renders dir, the review images.
Never put these items in a pack named « fluxcrate »: FluxCrate owns that namespace in the Flux item registry.
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image

from atelier import (El, Atlas, Renderer, about, affine, bbmodel, fixed_uv, hexa, hash3, mix, model_json, moved,
                     ramp, rot3, smooth_noise)

KEY_NS, CRATE_NS = "influkey", "influcrates"
HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------------------------------------------- palette

RED = ["#2E0A12", "#4E1220", "#641B2A", "#7E2032", "#9E2638", "#B53246", "#C9475A"]
WALNUT = ["#22150D", "#33200F", "#4A2E1A", "#5E3B22", "#77502F", "#8E6640"]
GOLD = ["#4A300C", "#7A5418", "#A57A2C", "#C99A3E", "#D6A747", "#E8C672", "#F5D98B", "#FFF3C9"]
GREEN = ["#0A2419", "#123A29", "#174C35", "#1F5E40", "#28734C", "#3A8C5E"]
SNOW = ["#A9BFD0", "#C6D8E5", "#DCE8F0", "#EAF3F8", "#F7FBFD", "#FFFFFF"]
ICE = ["#2D7FA6", "#4FA9CF", "#7CCDE8", "#A7DFF2", "#D2F1FA", "#FFFFFF"]
CANDY_RED = ["#6E1020", "#9C1A2E", "#C8283C", "#E04A57"]
CANDY_WHITE = ["#CFC6B8", "#E6DED2", "#F4EFE6", "#FFFDF8"]
FRAME = 0  # frame of an animated texture being painted


def solid(c, rgb, a=255.0):
    out = np.zeros(c.shape + (4,))
    out[..., :3] = rgb
    out[..., 3] = a
    return out


def along_across(c):
    if c.face in ("north", "south"):
        return c.X, c.Y
    if c.face in ("east", "west"):
        return c.Z, c.Y
    return c.X, c.Z


def grain(c, seed, stops, base=0.5, contrast=0.2):
    """Wood: long soft streaks along the face, thin darker grain lines, a little texel noise."""
    L, A = along_across(c)
    warp = smooth_noise(L * 0.35, A * 0.5, seed, 1.0, seed + 7)
    n = smooth_noise(L * 0.12, A * 1.6, seed, 1.0, seed)
    line = np.abs(np.sin((A * 1.55 + warp * 1.6) * math.pi)) < 0.13
    noise = hash3(np.floor(c.X * 2), np.floor(c.Y * 2), np.floor(c.Z * 2), seed) - 0.5
    return base + contrast * (n - 0.5) - 0.1 * line + 0.04 * noise


def bevel(t, c, hi=0.12, lo=0.14, min_size=3):
    if c.W >= min_size and c.H >= min_size:
        t = t + hi * (c.j == 0) - lo * (c.j == c.H - 1) + 0.5 * hi * (c.i == 0) - 0.5 * lo * (c.i == c.W - 1)
    return t


def shade_face(t, c, up=0.1, down=-0.2):
    return t + (up if c.face == "up" else down if c.face == "down" else 0)


def frost(c, rgba, amount=1.0, seed=3):
    """A little hoarfrost: a few crystals along the top edge of a side face, rarely a drip under them; a few along
    the rim of a top face."""
    h = hash3(np.floor(c.X * 2), np.floor(c.Y * 2), np.floor(c.Z * 2), seed)
    if c.face in ("north", "south", "east", "west"):
        ends = np.clip(1 - np.minimum(c.i, c.W - 1 - c.i) / max(2.0, c.W * 0.22), 0, 1)
        top = (c.j == 0) & (h > 1 - (0.12 + 0.75 * ends) * amount)
        drip = (c.j == 1) & (hash3(np.floor(c.X * 2), 1, np.floor(c.Z * 2), seed + 1) > 1 - 0.35 * ends * amount)
        drip = drip & np.roll(top, 1, axis=0)
        mask = top | drip
        col = ramp(SNOW, 0.45 + 0.3 * h - 0.25 * drip)
    elif c.face == "up":
        mask = (c.edge() == 0) & (h > 1 - 0.18 * amount)
        col = ramp(SNOW, 0.5 + 0.35 * h)
    else:
        return rgba
    rgba[mask, :3] = col[mask]
    return rgba


def stamp(rgba, pattern, colours, col0, row0, flip=False):
    """Pixel motif: pattern rows of characters, colours {char: rgb or None}, top-left at (col0, row0)."""
    H, W = rgba.shape[:2]
    for r, line in enumerate(pattern):
        for k, ch in enumerate(line[::-1] if flip else line):
            colour = colours.get(ch)
            x, y = col0 + k, row0 + r
            if colour is None or not (0 <= x < W and 0 <= y < H):
                continue
            if isinstance(colour, str) and colour == "clear":
                rgba[y, x, 3] = 0
            else:
                rgba[y, x, :3] = colour
                rgba[y, x, 3] = 255
    return rgba


FLAKE5 = ["x.x.x",
          ".xxx.",
          "xxoxx",
          ".xxx.",
          "x.x.x"]
SPARK3 = [".x.",
          "xox",
          ".x."]
INLAY = {"x": hexa(GOLD[5]), "o": hexa(ICE[4])}
INLAY_DIM = {"x": hexa(GOLD[4]), "o": hexa(GOLD[6])}


# ---------------------------------------------------------------------------------------------------- materials

def mahogany(seed=1, dark=False, frosted=0.0, decal=None, panel=False):
    def paint(c):
        t = grain(c, seed, RED, base=0.42 if dark else 0.6)
        if panel:
            t = bevel(t, c, hi=0.18, lo=0.22, min_size=4)
        else:
            t = bevel(t, c)
        t = shade_face(t, c)
        rgba = solid(c, ramp(RED, t))
        if frosted:
            rgba = frost(c, rgba, frosted, seed)
        if decal:
            rgba = decal(c, rgba)
        return rgba
    return paint


def walnut(seed=2, frosted=0.0, decal=None, filet=False):
    def paint(c):
        t = grain(c, seed, WALNUT, base=0.55)
        t = shade_face(bevel(t, c), c)
        rgba = solid(c, ramp(WALNUT, t))
        if filet and c.face in ("north", "south", "east", "west") and c.H >= 3:
            rgba[c.j == 0, :3] = ramp(GOLD, 0.62 + 0.1 * hash3(c.i, 0, seed, 5))[c.j == 0]
            rgba[c.j == 1, :3] *= 0.7
        if frosted:
            rgba = frost(c, rgba, frosted, seed)
        if decal:
            rgba = decal(c, rgba)
        return rgba
    return paint


def brass(seed=4, polish=0.0, engraved=True, rivets=False, decal=None, dark=0.0):
    def paint(c):
        h = hash3(np.floor(c.X * 2), np.floor(c.Y * 2), np.floor(c.Z * 2), seed)
        if c.face == "up":
            t = 0.74 + polish * 0.12 + 0.06 * (h - 0.5)
        elif c.face == "down":
            t = 0.3
        else:
            t = 0.66 + polish * 0.1 - 0.3 * c.v + 0.06 * (h - 0.5)
            t = t + 0.06 * (c.face in ("north", "south"))
        t = t - dark
        if engraved and c.W >= 3 and c.H >= 3:
            t = t - 0.24 * (c.edge() == 0)
        t = t + 0.1 * (h > 0.96)
        rgba = solid(c, ramp(GOLD, t))
        if rivets and c.face in ("north", "south", "east", "west") and c.W >= 3 and c.H >= 6:
            mid = c.W // 2
            for row in (2, c.H - 3):
                rgba[row, mid, :3] = hexa(GOLD[7])
                rgba[row + 1, mid, :3] = hexa(GOLD[1])
        if decal:
            rgba = decal(c, rgba)
        return rgba
    return paint


def velvet(c):
    a = np.floor((c.X + c.Z) * 2).astype(int)
    b = np.floor((c.Y + (c.X if c.face in ("east", "west") else c.Z)) * 2).astype(int)
    if c.face in ("up", "down"):
        a, b = np.floor((c.X + c.Z) * 2).astype(int), np.floor((c.X - c.Z) * 2).astype(int)
    else:
        L, A = along_across(c)
        a, b = np.floor((L + A) * 2).astype(int), np.floor((L - A) * 2).astype(int)
    seam = (a % 4 == 0) | (b % 4 == 0)
    button = (a % 4 == 0) & (b % 4 == 0)
    t = 0.48 + 0.06 * (hash3(a, b, 0, 9) - 0.5) - 0.16 * seam
    rgba = solid(c, ramp(GREEN, t))
    rgba[button, :3] = hexa(GOLD[5])
    return rgba


def snow(seed=5):
    def paint(c):
        h = hash3(np.floor(c.X * 2), np.floor(c.Y * 2), np.floor(c.Z * 2), seed)
        if c.face == "up":
            t = 0.9 + 0.12 * (h - 0.5)
        elif c.face == "down":
            t = 0.4
        else:
            t = 0.82 - 0.25 * c.v + 0.1 * (h - 0.5)
        return solid(c, ramp(SNOW, t))
    return paint


def holly_leaf(c):
    """Holly leaf (flat element, cutout), its length along u."""
    s, t = c.u, (c.v - 0.5) * 2
    width = np.sin(np.pi * np.clip(s, 0, 1)) ** 0.8
    spikes = 0.78 + 0.22 * np.abs(np.sin(s * np.pi * 3.0))
    inside = np.abs(t) < width * spikes
    vein = np.abs(t) < 0.22
    edge = np.abs(t) > width * spikes - 0.42
    tone = 0.5 + 0.25 * (t < 0) - 0.2 * edge + 0.3 * vein
    rgba = solid(c, ramp(GREEN, tone + 0.1))
    rgba[..., 3] = np.where(inside, 255, 0)
    return rgba


def berry(c):
    rgba = solid(c, hexa("#B5122B") if c.face != "up" else hexa("#E0324A"))
    if c.face in ("north", "south", "east", "west") and c.W >= 2:
        rgba[0, 0, :3] = hexa("#FF8A94")
        rgba[-1, :, :3] = hexa("#7A0A1C")
    return rgba


def glow(stops, alpha=235):
    def paint(c):
        rgba = solid(c, ramp(stops, 0.5 + 0.5 * np.cos((c.u - 0.5) * math.pi) * 0), alpha)
        return rgba
    return paint


def inner_light(c):
    d = np.sqrt(((c.X - 8) / 6.0) ** 2 + ((c.Z - 8) / 3.5) ** 2)
    rgba = solid(c, ramp(["#FFFBEF", "#FFE7A8", "#FFC45E", "#F29A3A"], np.clip(d, 0, 1)))
    rgba[..., 3] = 250 - 50 * np.clip(d, 0, 1)
    h = hash3(np.floor(c.X * 2), 0, np.floor(c.Z * 2), 21)
    rgba[h > 0.93, :3] = hexa("#FFFFFF")
    return rgba


def keyhole_glow(c):
    """Halo of the keyhole (flat element in front of the lock plate), keyhole at x 7.5..8.5, y 5..7."""
    d = np.maximum(keyhole_distance(c.X, c.Y), 0)
    core = d <= 0
    rgba = solid(c, ramp(["#FFFFFF", "#D2F1FA", "#A7DFF2", "#5FB8DC"], np.clip(d / 0.8, 0, 1)))
    rgba[..., 3] = np.where(core, 255, np.clip(1 - d / 0.8, 0, 1) ** 2 * 130)
    return rgba


def beam(c):
    """Beam of light: fades upward and toward its sides, a few brighter rays."""
    h = np.clip((c.Y - 7.5) / 16.0, 0, 1)
    side = np.abs(c.u - 0.5) * 2
    ray = 0.55 + 0.45 * np.abs(np.sin((c.u * 7.3 + 0.37) * math.pi)) ** 3
    a = (1 - h) ** 1.7 * (1 - side ** 2.2) * ray * 150
    rgba = solid(c, ramp(["#FFFBEA", "#FFE3A0", "#FFD27A"], h), a)
    return rgba


def eclat_frames(n=12, size=16):
    """Ice crystal texture, pulsing (animated, interpolated): deep blue edges, bright heart, facets, sparkles."""
    frames = []
    y, x = np.mgrid[0:size, 0:size] + 0.5
    for k in range(n):
        pulse = 0.5 + 0.5 * math.cos(2 * math.pi * k / n)
        core = np.clip(1 - np.abs(x - size / 2) / (size / 2), 0, 1)
        t = 0.18 + 0.55 * core ** 1.4 + 0.12 * (1 - y / size) + 0.22 * pulse * core ** 3
        facet = np.abs(((x - 0.6 * y) % 9) - 4.5) < 0.55
        t = t - 0.12 * facet
        img = np.zeros((size, size, 4))
        img[..., :3] = ramp(ICE, t)
        img[..., 3] = 255
        if pulse > 0.55:
            for (sx, sy) in ((7, 3), (9, 10)):
                img[sy, sx, :3] = 255
        frames.append(img)
    return np.clip(np.concatenate(frames, 0), 0, 255).astype(np.uint8)


# ---------------------------------------------------------------------------------------------------- decals

def panel_inlay(c, rgba):
    """Raised panel: a gold fillet all around, a gold snowflake with an ice heart in the middle."""
    if c.face in ("north", "south", "east", "west") and c.W >= 5 and c.H >= 5:
        border = c.edge() == 0
        rgba[border, :3] = ramp(GOLD, 0.62 - 0.22 * c.v + 0.1 * (c.j == 0))[border]
        if c.W >= 7 and c.H >= 7 and c.W <= 9:
            stamp(rgba, FLAKE5, INLAY, (c.W - 5) // 2, (c.H - 5) // 2)
        elif c.W > 9:
            stamp(rgba, FLAKE5, INLAY, (c.W - 5) // 2, (c.H - 5) // 2)
            for col in (3, c.W - 4):
                stamp(rgba, SPARK3, INLAY_DIM, col - 1, (c.H - 3) // 2)
    return rgba


KEYHOLE = (8.0, 6.7, 0.62, 5.2, 0.22, 0.42)   # x, y of the round head, its radius, bottom of the slot, half widths


def keyhole_distance(x, y):
    """Distance (units) to the keyhole: a round head over a slot widening downward; negative inside."""
    cx, cy, r, bottom, w0, w1 = KEYHOLE
    head = np.sqrt((x - cx) ** 2 + (y - cy) ** 2) - r
    k = np.clip((cy - y) / (cy - bottom), 0, 1)
    half = w0 + (w1 - w0) * k
    slot = np.maximum(np.abs(x - cx) - half, np.maximum(y - cy, bottom - y))
    return np.minimum(head, slot)


def lock_plate(c, rgba):
    """Escutcheon (drawn at 4 texels a unit): engraved double border, arched top, the keyhole, two gold beads."""
    if c.face != "north":
        return rgba
    x = 9.5 - (c.i + 0.5) / 4
    y = 8.5 - (c.j + 0.5) / 4
    e = c.edge()
    rgba[e == 0, :3] = ramp(GOLD, 0.42 - 0.1 * c.v)[e == 0]
    rgba[e == 2, :3] = hexa(GOLD[3])
    d = keyhole_distance(x, y)
    rgba[d < 0, :3] = hexa("#120904")
    rgba[(d < 0) & (y > KEYHOLE[1]), :3] = hexa("#2A1A0B")
    rim = (d >= 0) & (d < 0.25)
    rgba[rim, :3] = ramp(GOLD, 0.35 + 0.3 * (y > KEYHOLE[1]))[rim]
    # arched top and rounded bottom corners, cut out
    corner = ((c.j < 2) & ((c.i < 2 - c.j) | (c.i > c.W - 3 + c.j))) | ((c.j == c.H - 1) & ((c.i == 0) | (c.i == c.W - 1)))
    rgba[corner, 3] = 0
    return rgba


def hasp(c, rgba):
    """Clasp hanging from the lid (4 texels a unit): an ice gem set in gold, a ring at the bottom."""
    if c.face == "north":
        x = 8.75 - (c.i + 0.5) / 4
        y = 10.75 - (c.j + 0.5) / 4
        gem = np.abs(x - 8) / 0.42 + np.abs(y - 9.55) / 0.62 < 1
        rgba[gem, :3] = ramp(ICE, 0.95 - 0.6 * (y < 9.55) - 0.25 * (x > 8))[gem]
        setting = (np.abs(x - 8) / 0.42 + np.abs(y - 9.55) / 0.62 < 1.35) & ~gem
        rgba[setting, :3] = hexa(GOLD[2])
        ring = np.abs(np.sqrt((x - 8) ** 2 + (y - 8.55) ** 2) - 0.2) < 0.1
        rgba[ring, :3] = hexa(GOLD[1])
        rgba[c.H - 1, 0, 3] = 0
        rgba[c.H - 1, c.W - 1, 3] = 0
    return rgba


def lid_front(c, rgba):
    if c.face == "north" and c.W > 20:
        for x in (4.25, 11.75):
            col = int((15.0 - x) * 2) - 1
            stamp(rgba, SPARK3, INLAY, col, 1)
    if c.face == "south" and c.W > 20:
        for x0, x1 in ((3.5, 5.0), (11.0, 12.5)):
            mask = (c.X > x0) & (c.X < x1)
            rgba[mask, :3] = ramp(GOLD, 0.6 - 0.25 * c.v)[mask]
    return rgba


def filet(c, rgba, row=0):
    """Gold fillet along the top edge of a side face."""
    if c.face in ("north", "south", "east", "west") and c.H >= 2:
        m = c.j == row
        rgba[m, :3] = ramp(GOLD, 0.66 + 0.08 * hash3(c.i, row, 3, 5))[m]
    return rgba


def plateau(c, rgba):
    if c.face != "up":
        return rgba
    d = np.sqrt((c.X - 8) ** 2 + (c.Z - 8) ** 2)
    ang = np.arctan2(c.Z - 8, c.X - 8)
    enamel = ramp(GREEN, 0.3 + 0.3 * (1 - np.clip(d / 3.5, 0, 1)) +
                  0.05 * hash3(np.floor(c.X * 2), 0, np.floor(c.Z * 2), 31))
    inside = c.edge() >= 1
    rgba[inside, :3] = enamel[inside]
    return rgba


def chain(*fns):
    def paint(c, rgba):
        for f in fns:
            rgba = f(c, rgba)
        return rgba
    return paint


# ---------------------------------------------------------------------------------------------------- crate

def mirror_x(x0, x1):
    return 16 - x1, 16 - x0


def mirror_z(z0, z1):
    return 16 - z1, 16 - z0


def corners4(name, x, y, z, mat, **kw):
    """The same box at the four corners of the crate (x, z given for the north-west one)."""
    out = []
    for k, (mx, mz) in enumerate(((0, 0), (1, 0), (0, 1), (1, 1))):
        xx = mirror_x(*x) if mx else x
        zz = mirror_z(*z) if mz else z
        out.append(El(f"{name}_{k}", (xx[0], y[0], zz[0]), (xx[1], y[1], zz[1]), mat, **kw))
    return out


HINGE = (9.5, 13.0)       # y, z of the lid's hinge
SPIN = (8.0, 8.0)         # x, z of the medallion's axis (the centre of the model)
DAIS = 13.25              # top of the medallion's dais


def corps():
    E = []
    E += corners4("pied", (0.75, 2.75), (0, 1), (3.25, 5.25), brass(seed=40, dark=0.12, engraved=False))
    E.append(El("plinthe", (0.5, 1, 3.0), (15.5, 2.5, 13.0), walnut(seed=12, filet=True)))
    frame = {f: mahogany(seed=20 + k, dark=True) for k, f in enumerate(("north", "south", "east", "west", "up"))}
    frame["down"] = None
    E.append(El("paroi_avant", (1, 2.5, 3.5), (15, 9.5, 4.5), {**frame, "south": velvet}))
    E.append(El("paroi_arriere", (1, 2.5, 11.5), (15, 9.5, 12.5), {**frame, "north": velvet}))
    E.append(El("paroi_gauche", (1, 2.5, 4.5), (2, 9.5, 11.5), {**frame, "east": velvet, "north": None, "south": None}))
    E.append(El("paroi_droite", (14, 2.5, 4.5), (15, 9.5, 11.5), {**frame, "west": velvet, "north": None, "south": None}))
    E.append(El("fond", (2, 2.5, 4.5), (14, 4.0, 11.5), {"up": velvet}))
    band = brass(seed=41, engraved=False, polish=0.3)
    E.append(El("cerclage_avant", (0.75, 9.0, 3.25), (15.25, 9.5, 3.5), band))
    E.append(El("cerclage_arriere", (0.75, 9.0, 12.5), (15.25, 9.5, 12.75), band))
    E.append(El("cerclage_gauche", (0.75, 9.0, 3.5), (1.0, 9.5, 12.5), band))
    E.append(El("cerclage_droite", (15.0, 9.0, 3.5), (15.25, 9.5, 12.5), band))
    panel = lambda s: mahogany(seed=s, panel=True, decal=panel_inlay)
    E.append(El("panneau_gauche", (2.5, 3.75, 3.25), (6.0, 8.25, 3.5), panel(30)))
    E.append(El("panneau_droit", (10.0, 3.75, 3.25), (13.5, 8.25, 3.5), panel(31)))
    E.append(El("panneau_arriere", (2.5, 3.75, 12.5), (13.5, 8.25, 12.75), panel(32)))
    E.append(El("panneau_flanc_g", (0.75, 3.75, 5.0), (1.0, 8.25, 11.0), panel(33)))
    E.append(El("panneau_flanc_d", (15.0, 3.75, 5.0), (15.25, 8.25, 11.0), panel(34)))
    corner = brass(seed=42, polish=0.1, dark=0.05)
    E += corners4("angle_bas", (0.75, 2.25), (2.5, 4.0), (3.25, 4.75), corner)
    E.append(El("serrure", (6.5, 4.5, 3.0), (9.5, 8.5, 3.5), brass(seed=43, polish=0.5, decal=lock_plate), density=4))
    for side, (x0, x1), (b0, b1) in (("g", (0.5, 0.75), (0.0, 0.5)), ("d", (15.25, 15.5), (15.5, 16.0))):
        E.append(El(f"poignee_platine_{side}", (x0, 5.25, 6.75), (x1, 7.25, 9.25), brass(seed=44)))
        E.append(El(f"poignee_barre_{side}", (b0, 4.5, 6.75), (b1, 5.0, 9.25), brass(seed=45, engraved=False, polish=0.4)))
        E.append(El(f"poignee_bras1_{side}", (b0, 5.0, 6.75), (b1, 6.25, 7.25), brass(seed=46, engraved=False)))
        E.append(El(f"poignee_bras2_{side}", (b0, 5.0, 8.75), (b1, 6.25, 9.25), brass(seed=47, engraved=False)))
    for k, (x0, x1) in enumerate(((3.5, 5.0), (11.0, 12.5))):
        E.append(El(f"charniere_{k}", (x0, 9.0, 12.5), (x1, 10.0, 13.5), brass(seed=48 + k, engraved=False)))
    return E


def couvercle():
    E = []
    band = brass(seed=60, engraved=False, polish=0.3)
    E.append(El("cerclage_avant", (0.75, 9.5, 3.25), (15.25, 10.0, 3.5), band))
    E.append(El("cerclage_arriere", (0.75, 9.5, 12.5), (15.25, 10.0, 12.75), band))
    E.append(El("cerclage_gauche", (0.75, 9.5, 3.5), (1.0, 10.0, 12.5), band))
    E.append(El("cerclage_droite", (15.0, 9.5, 3.5), (15.25, 10.0, 12.5), band))
    lid = {f: mahogany(seed=61 + k, frosted=1.0, decal=lid_front) for k, f in enumerate(("north", "south", "east", "west", "up"))}
    lid["down"] = velvet
    E.append(El("couvercle", (1, 9.5, 3.5), (15, 11.5, 12.5), lid))
    E += corners4("coiffe", (0.75, 2.25), (9.5, 11.5), (3.25, 4.75), brass(seed=66, polish=0.25, dark=0.05))
    roof = {f: mahogany(seed=67 + k, dark=True, frosted=0.6, decal=filet) for k, f in enumerate(("north", "south", "east", "west", "up"))}
    E.append(El("toit", (2, 11.5, 4.5), (14, 12.5, 11.5), roof))
    E.append(El("estrade", (4.5, 12.5, 4.75), (11.5, DAIS, 11.25), brass(seed=68, polish=0.5, decal=plateau)))
    E.append(El("moraillon", (7.25, 8.25, 2.75), (8.75, 10.75, 3.25), brass(seed=69, polish=0.6, decal=hasp), density=4))
    E.append(El("neige_toit", (11.5, 12.5, 9.75), (13.75, 12.7, 11.5), snow(72)))
    E.append(El("neige_toit2", (12.0, 12.7, 10.25), (13.5, 12.85, 11.4), snow(73)))
    E.append(El("neige_toit3", (12.75, 12.85, 10.75), (13.4, 12.95, 11.3), snow(74)))
    E.append(El("houx_1", (10.6, 11.55, 3.2), (13.4, 11.55, 4.6), holly_leaf, rot=("y", -22, [12, 11.55, 3.9]), density=4))
    E.append(El("houx_2", (11.8, 11.5, 4.35), (14.4, 13.0, 4.35), holly_leaf, rot=("x", -28, [13, 11.5, 4.35]), density=4))
    E.append(El("houx_3", (12.2, 11.6, 3.4), (14.6, 11.6, 4.6), holly_leaf, rot=("y", 35, [13.4, 11.6, 4.0]), density=4))
    for k, (x, y, z) in enumerate(((12.0, 11.55, 3.75), (12.7, 11.6, 3.55), (12.4, 12.1, 3.95))):
        E.append(El(f"baie_{k}", (x - 0.35, y, z - 0.35), (x + 0.35, y + 0.7, z + 0.35), berry, density=4))
    return E


def bar(name, cx, cz, angle, length, width, y0, y1, mat):
    """A bar centred at (cx, cz) pointing at `angle` degrees (from +x toward +z), rotations kept within 45°."""
    a = angle % 180
    if a <= 45 or a >= 135:
        rot = -a if a <= 45 else 180 - a
        e = El(name, (cx - length / 2, y0, cz - width / 2), (cx + length / 2, y1, cz + width / 2), mat,
               rot=("y", rot, [cx, y0, cz]) if rot else None)
    else:
        rot = 90 - a
        e = El(name, (cx - width / 2, y0, cz - length / 2), (cx + width / 2, y1, cz + length / 2), mat,
               rot=("y", rot, [cx, y0, cz]) if rot else None)
    return e


def flake_mask(n=13):
    """Snowflake of n x n texels: four arms and four diagonals, a V on each arm, a heart (2) and tips (3)."""
    m = np.zeros((n, n), int)
    c = n // 2
    for dx, dy in ((0, 1), (1, 0), (0, -1), (-1, 0)):
        px, py = -dy, dx
        for t in range(1, c + 1):
            m[c + t * dy, c + t * dx] = 1
        for t, o in ((3, 1), (4, 2)):
            for s in (1, -1):
                m[c + t * dy + s * o * py, c + t * dx + s * o * px] = 1
        m[c + c * dy, c + c * dx] = 3
    for dx, dy in ((1, 1), (1, -1), (-1, 1), (-1, -1)):
        for t in range(1, 4):
            m[c + t * dy, c + t * dx] = 1
        m[c + 3 * dy, c + 3 * dx] = 3
    m[c, c] = 2
    return m


def medal_plane(c):
    """The snowflake on its plane: gold lit from the north-west, ice heart, bright tips."""
    m = flake_mask(c.W)
    if c.face == "down":
        m = m[::-1]
    rgba = solid(c, ramp(GOLD, 0.66 + 0.2 * (1 - (c.i + c.j) / (c.W + c.H))))
    rgba[m == 3, :3] = hexa(GOLD[7])
    rgba[m == 2, :3] = hexa(ICE[4])
    rgba[..., 3] = np.where(m > 0, 255, 0)
    return rgba


def medal_shadow(c):
    m = flake_mask(c.W)
    rgba = solid(c, ramp(GOLD, 0.28))
    rgba[..., 3] = np.where(m > 0, 255, 0)
    return rgba


def medaillon():
    """Gold snowflake lying on the dais (two layers: the flake and its darker thickness) around a cluster of ice
    crystals."""
    E = []
    cx, cz = SPIN
    y0 = DAIS
    half = 3.25
    E.append(El("flocon", (cx - half, y0 + 0.2, cz - half), (cx + half, y0 + 0.2, cz + half), medal_plane,
                faces=("up", "down")))
    E.append(El("moyeu", (7.1, y0, 7.1), (8.9, y0 + 0.6, 8.9), brass(seed=81, polish=0.8), rot=("y", 45, [8, y0, 8])))
    eclat = ("fixed", "eclat", [0, 0, 16, 16])
    sides = ("north", "south", "east", "west")
    top = sides + ("up",)
    E.append(El("cristal", (7.4, y0 + 0.4, 7.4), (8.6, 15.5, 8.6), None, tex=eclat, shade=False, faces=sides,
                rot=("y", 45, [8, y0, 8])))
    E.append(El("cristal_pointe", (7.65, 15.5, 7.65), (8.35, 16.2, 8.35), None, tex=eclat, shade=False, faces=top,
                rot=("y", 45, [8, y0, 8])))
    E.append(El("cristal_bout", (7.88, 16.2, 7.88), (8.12, 16.6, 8.12), None, tex=eclat, shade=False, faces=top,
                rot=("y", 45, [8, y0, 8])))
    E.append(El("cristal_g", (6.9, y0 + 0.3, 7.6), (7.7, 14.9, 8.4), None, tex=eclat, shade=False, faces=top,
                rot=("z", 24, [7.3, y0 + 0.3, 8])))
    E.append(El("cristal_d", (8.3, y0 + 0.3, 7.6), (9.1, 14.5, 8.4), None, tex=eclat, shade=False, faces=top,
                rot=("z", -26, [8.7, y0 + 0.3, 8])))
    E.append(El("cristal_ar", (7.65, y0 + 0.3, 8.4), (8.35, 14.3, 9.1), None, tex=eclat, shade=False, faces=top,
                rot=("x", 24, [8, y0 + 0.3, 8.7])))
    return E


def serrure():
    return [El("serrure_lueur", (6.75, 4.6, 2.97), (9.25, 7.85, 2.97), keyhole_glow, shade=False,
               faces=("north",), density=4)]


def lueur():
    seam = glow(["#FFF6DA", "#FFE3A0"])
    E = [El("joint_avant", (1.0, 9.3, 3.05), (15.0, 9.7, 3.6), seam, shade=False),
         El("joint_arriere", (1.0, 9.3, 12.4), (15.0, 9.7, 12.95), seam, shade=False),
         El("joint_gauche", (0.55, 9.3, 3.5), (1.1, 9.7, 12.5), seam, shade=False),
         El("joint_droite", (14.9, 9.3, 3.5), (15.45, 9.7, 12.5), seam, shade=False),
         El("interieur", (2.0, 7.5, 4.5), (14.0, 7.5, 11.5), inner_light, shade=False, faces=("up",))]
    return E


def rayon():
    E = [El("rayon_x", (3.0, 7.5, 8.0), (13.0, 23.5, 8.0), beam, shade=False, density=1),
         El("rayon_z", (8.0, 7.5, 4.5), (8.0, 23.5, 11.5), beam, shade=False, density=1),
         El("rayon_d1", (3.5, 7.5, 8.0), (12.5, 23.5, 8.0), beam, shade=False, density=1, rot=("y", 45, [8, 7.5, 8])),
         El("rayon_d2", (3.5, 7.5, 8.0), (12.5, 23.5, 8.0), beam, shade=False, density=1, rot=("y", -45, [8, 7.5, 8]))]
    return E


CRATE_DISPLAY = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, -0.75, 0], "scale": [0.6, 0.6, 0.6]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.3, 0.3, 0.3]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, -1, 0], "scale": [0.55, 0.55, 0.55]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 6, 0], "scale": [0.8, 0.8, 0.8]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
    "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
}


def crate_parts():
    parts = {"corps": corps(), "couvercle": couvercle(), "medaillon": medaillon(), "serrure": serrure(),
             "lueur": lueur(), "rayon": rayon()}
    every = [e for p in parts.values() for e in p]
    atlas = Atlas("main", 64).build(every)
    fixed_uv(every)
    return parts, atlas


# ---------------------------------------------------------------------------------------------------- key

CANE_CENTRE, CANE_R, CANE_W = (5.5, 10.5), 2.5, 2.0   # crook: centre (x, y), radius of its middle line, thickness
SHAFT_TOP = 10.5
TAIL_END = 8.5
STRIPE = 4   # texels of a stripe period (2 red, 2 white), at 45°


def cane_path(x, y):
    """Position along the cane (s, in units from the bottom) and offset across it, for a point of the key."""
    cx, cy = CANE_CENTRE
    on_crook = y > SHAFT_TOP
    on_tail = (x < cx) & ~on_crook
    a = np.arctan2(y - cy, x - cx)
    r = np.sqrt((x - cx) ** 2 + (y - cy) ** 2)
    s = np.where(on_crook, SHAFT_TOP + CANE_R * np.clip(a, 0, np.pi),
                 np.where(on_tail, SHAFT_TOP + CANE_R * np.pi + (SHAFT_TOP - y), y))
    across = np.where(on_crook, r - CANE_R, np.where(on_tail, cx - CANE_R - x, x - 8))
    return s, across


def candy(c):
    """Stripes at 45° wrapped around the cane (continuous along the crook), gloss on its upper side, sheen."""
    if getattr(c.el, "arc", None):
        # a segment of the crook: stripes drawn in its own frame, so that they follow its texels
        long_x, s_mid, cx, cy, sign, out, index = c.el.arc
        s = s_mid + sign * ((c.X - cx) if long_x else (c.Y - cy))
        across = out * ((c.Y - cy) if long_x else (c.X - cx))
        band = np.full(c.shape, index % 2 == 0)
    else:
        s, across = cane_path(c.WX, c.WY)
    if c.face in ("north", "south"):
        w = across * (1 if c.face == "south" else -1)
    elif c.face in ("east", "west"):
        w = (c.WZ - 8) * (1 if c.face == "east" else -1) + 2
    else:
        w = (c.WZ - 8) + (2 if c.face == "up" else -2)
    red = band if getattr(c.el, "arc", None) else ((s + w) / 2.0) % 1.0 < 0.5
    edge = np.abs(across) > CANE_W / 2 - 0.5
    t = 0.7 - 0.22 * edge * (c.face in ("north", "south")) - 0.12 * (c.face in ("east", "west", "down"))
    gloss = (np.abs(across + 0.25) < 0.3) & (c.face in ("north", "south"))
    t = t + 0.25 * gloss
    rgb = np.where(red[..., None], ramp(CANDY_RED, t), ramp(CANDY_WHITE, t))
    return sheen(c, solid(c, rgb), s)


def sheen(c, rgba, s):
    """Band of light running up the key during the first frames of the animation (none while painting the atlas)."""
    if FRAME < 10:
        pos = -2 + FRAME * 2.8
        k = np.clip(1 - np.abs(s - pos) / 1.3, 0, 1) ** 2 * 0.6
        rgba[..., :3] = rgba[..., :3] * (1 - k[..., None]) + 255 * k[..., None]
    return rgba


def key_gold(c):
    return sheen(c, brass(seed=90, polish=0.8, engraved=False)(c), c.Y)


def star_plane(c):
    """Five-pointed star (flat element, cutout), gold with a bright heart."""
    x, y = (c.u - 0.5) * 2, (0.5 - c.v) * 2
    r = np.sqrt(x ** 2 + y ** 2)
    a = np.arctan2(x, y)
    lim = 0.42 + 0.58 * (np.abs(((a / (2 * np.pi / 5)) % 1) - 0.5) * 2) ** 1.6
    inside = r < lim * 0.95
    t = 0.68 + 0.25 * (x < 0) - 0.3 * (r > lim * 0.68)
    rgba = solid(c, ramp(GOLD, t))
    rgba[..., 3] = np.where(inside, 255, 0)
    rgba[(r < 0.2), :3] = hexa(GOLD[7])
    return sheen(c, rgba, 9.0)


def cane_segment(name, a_mid, length):
    """Segment of the crook at a_mid degrees (counter-clockwise from +x around CANE_CENTRE), rotation within 45°."""
    cx = CANE_CENTRE[0] + CANE_R * math.cos(math.radians(a_mid))
    cy = CANE_CENTRE[1] + CANE_R * math.sin(math.radians(a_mid))
    w = CANE_W / 2
    s_mid = SHAFT_TOP + CANE_R * math.radians(a_mid)
    if 45 < a_mid < 135:
        e = El(name, (cx - length / 2, cy - w, 7), (cx + length / 2, cy + w, 9), candy,
               rot=("z", a_mid - 90, [cx, cy, 8]))
        e.arc = (True, s_mid, cx, cy, -1, 1, int(name.split("_")[-1]))
        return e
    r = a_mid if a_mid <= 45 else a_mid - 180
    e = El(name, (cx - w, cy - length / 2, 7), (cx + w, cy + length / 2, 9), candy,
           rot=("z", r, [cx, cy, 8]) if r else None)
    e.arc = (False, s_mid, cx, cy, 1 if a_mid <= 45 else -1, 1 if a_mid <= 45 else -1, int(name.split("_")[-1]))
    return e


def key_elements():
    E = []

    def el(name, f, t, mat, **kw):
        e = El(name, f, t, mat, **kw)
        return e

    E.append(el("embout", (7.25, 0, 7.25), (8.75, 0.5, 8.75), key_gold))
    E.append(el("tige_basse", (7, 0.5, 7), (9, 4.5, 9), candy))
    bit = brass(seed=91, polish=0.7)
    E.append(el("panneton", (9, 1.0, 7.6), (10.75, 3.5, 8.4), lambda c: sheen(c, bit(c), c.Y)))
    E.append(el("dent_1", (10.75, 1.0, 7.6), (12.25, 1.5, 8.4), key_gold))
    E.append(el("dent_2", (10.75, 2.0, 7.6), (11.5, 2.5, 8.4), key_gold))
    E.append(el("dent_3", (10.75, 3.0, 7.6), (12.0, 3.5, 8.4), key_gold))
    E.append(el("garde", (9, 3.5, 7.75), (9.75, 4.5, 8.25), key_gold))
    E.append(el("bague", (6.5, 4.5, 6.5), (9.5, 5.5, 9.5), key_gold))
    E.append(el("bague_filet", (6.75, 5.5, 6.75), (9.25, 5.75, 9.25), key_gold))
    E.append(el("tige", (7, 5.75, 7), (9, SHAFT_TOP, 9), candy))
    E.append(el("raccord", (6.75, 9.5, 6.75), (9.25, 10.0, 9.25), key_gold))
    for k in range(8):
        E.append(cane_segment(f"crosse_{k}", 11.25 + 22.5 * k, 1.5))
    E.append(el("bout", (CANE_CENTRE[0] - CANE_R - CANE_W / 2, TAIL_END, 7),
                (CANE_CENTRE[0] - CANE_R + CANE_W / 2, SHAFT_TOP, 9), candy))
    E.append(el("bout_or", (2.25, TAIL_END - 0.5, 7.25), (3.75, TAIL_END, 8.75), key_gold))
    E.append(el("etoile", (1.25, 5.25, 8.0), (4.75, 8.75, 8.0), star_plane, density=4))
    # holly on the collar, in front (south, the side seen in inventories)
    E.append(el("houx_1", (4.6, 4.5, 9.55), (7.1, 5.8, 9.55), holly_leaf, rot=("z", 28, [7, 5, 9.55]), density=4))
    E.append(el("houx_2", (8.9, 4.5, 9.55), (11.4, 5.8, 9.55), holly_leaf, rot=("z", -28, [9, 5, 9.55]), density=4))
    for k, (x, y) in enumerate(((7.6, 5.1), (8.4, 5.1), (8.0, 5.7))):
        E.append(el(f"baie_{k}", (x - 0.35, y - 0.35, 9.5), (x + 0.35, y + 0.35, 10.2), berry, density=4))
    return E


KEY_DISPLAY = {
    "gui": {"rotation": [0, 0, -45], "translation": [0, 0, 0], "scale": [0.82, 0.82, 0.82]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
    "thirdperson_righthand": {"rotation": [0, -90, 10], "translation": [0, 4.0, 0.5], "scale": [0.85, 0.85, 0.85]},
    "thirdperson_lefthand": {"rotation": [0, 90, -100], "translation": [0, 4.0, 0.5], "scale": [0.85, 0.85, 0.85]},
    "firstperson_righthand": {"rotation": [0, -90, -20], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "firstperson_lefthand": {"rotation": [0, 90, -70], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
}
KEY_FRAMES, KEY_FRAMETIME = 24, 2


def key_textures(elements):
    global FRAME
    FRAME = 99
    atlas = Atlas("main", 32).build(elements)
    frames = []
    for k in range(KEY_FRAMES):
        FRAME = k
        frames.append(atlas.paint())
    FRAME = 99
    return atlas, np.concatenate(frames, 0)


# ---------------------------------------------------------------------------------------------------- files

def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1, ensure_ascii=False)


def write_png(path, arr):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(arr, "RGBA").save(path)


def write_anim(path, arr, frametime):
    write_png(path, arr)
    with open(path + ".mcmeta", "w", encoding="utf-8") as f:
        json.dump({"animation": {"frametime": frametime, "interpolate": True}}, f)


HEADER = "# Generated by plugins/fluxplugin/FluxCrate/assets/noel/gen_noel.py: do not edit by hand.\n"
CRATE_ITEMS = {
    "caisse_noel": "Caisse de Noël",
    "caisse_noel_corps": "Caisse de Noël (corps)",
    "caisse_noel_couvercle": "Caisse de Noël (couvercle)",
    "caisse_noel_medaillon": "Caisse de Noël (médaillon)",
    "caisse_noel_serrure": "Caisse de Noël (lueur de la serrure)",
    "caisse_noel_lueur": "Caisse de Noël (lueur intérieure)",
    "caisse_noel_rayon": "Caisse de Noël (rayon)",
}


def yml_crate():
    s = HEADER + ("# Christmas crate (FluxCrate, crates/noel.yml): an enchanted winter reliquary. « caisse_noel » is the\n"
                  "# whole crate (in hand, in inventories); the other items are its parts, shown and moved one by one\n"
                  "# by FluxCrate on a placed crate (body, lid on its hinge, turning medallion, glows, beam).\n"
                  "info:\n  namespace: influcrates\n\nitems:\n")
    for k, name in CRATE_ITEMS.items():
        s += (f"  {k}:\n    display_name: \"&#E8384F&l{name}\"\n    resource:\n      material: PAPER\n"
              f"      generate: false\n      model_path: item/noel/{k}\n")
    return s


def yml_key():
    return HEADER + ("# Key of the Christmas crate (FluxCrate, crates/noel.yml): a candy cane bent into a key.\n"
                     "info:\n  namespace: influkey\n\nitems:\n  cle_noel:\n"
                     "    display_name: \"&#E8384F&lClé de Noël\"\n    resource:\n      material: PAPER\n"
                     "      generate: false\n      model_path: item/noel/cle_noel\n")


def build():
    parts, atlas = crate_parts()
    eclat = eclat_frames()
    key = key_elements()
    key_atlas, key_anim = key_textures(key)
    return parts, atlas, eclat, key, key_atlas, key_anim


def write(plugins, parts, atlas, eclat, key, key_atlas, key_anim):
    ia = os.path.join(plugins, "ItemsAdder", "contents")
    cdir, kdir = os.path.join(ia, CRATE_NS), os.path.join(ia, KEY_NS)
    tex = {"main": f"{CRATE_NS}:item/noel/caisse_noel", "eclat": f"{CRATE_NS}:item/noel/caisse_noel_eclat"}
    write_png(os.path.join(cdir, "textures/item/noel/caisse_noel.png"), atlas.image)
    write_anim(os.path.join(cdir, "textures/item/noel/caisse_noel_eclat.png"), eclat, 3)
    whole = parts["corps"] + parts["couvercle"] + parts["medaillon"]
    write_json(os.path.join(cdir, "models/item/noel/caisse_noel.json"),
               dict(model_json(whole, tex, CRATE_DISPLAY), gui_light="side"))
    for name, els in parts.items():
        used = {k: v for k, v in tex.items() if any((e.tex[1] if isinstance(e.tex, tuple) else e.tex) == k for e in els)}
        write_json(os.path.join(cdir, f"models/item/noel/caisse_noel_{name}.json"), model_json(els, used))
    with open(os.path.join(cdir, "noel.yml"), "w", encoding="utf-8") as f:
        f.write(yml_crate())
    ktex = {"main": f"{KEY_NS}:item/noel/cle_noel"}
    write_anim(os.path.join(kdir, "textures/item/noel/cle_noel.png"), key_anim, KEY_FRAMETIME)
    write_json(os.path.join(kdir, "models/item/noel/cle_noel.json"), dict(model_json(key, ktex, KEY_DISPLAY), gui_light="front"))
    with open(os.path.join(kdir, "noel.yml"), "w", encoding="utf-8") as f:
        f.write(yml_key())
    # Blockbench projects: groups with their pivots (lid on its hinge, medallion on its axis)
    groups = [("corps", [8, 0, 8], parts["corps"]), ("couvercle", [8, HINGE[0], HINGE[1]], parts["couvercle"]),
              ("medaillon", [SPIN[0], 13, SPIN[1]], parts["medaillon"]), ("serrure", [8, 6, 3], parts["serrure"]),
              ("lueur", [8, 9.5, 8], parts["lueur"]), ("rayon", [8, 8, 8], parts["rayon"])]
    bb_crate = bbmodel("caisse_noel", groups, [("main", "caisse_noel.png", atlas.image, None),
                                                ("eclat", "caisse_noel_eclat.png", eclat, 3)], display=CRATE_DISPLAY)
    bb_key = bbmodel("cle_noel", [("cle", [8, 8, 8], key)], [("main", "cle_noel.png", key_anim, KEY_FRAMETIME)],
                     display=KEY_DISPLAY)
    for folder in (HERE, os.path.join(cdir, "BBModels")):
        write_json(os.path.join(folder, "caisse_noel.bbmodel"), bb_crate)
    for folder in (HERE, os.path.join(kdir, "BBModels")):
        write_json(os.path.join(folder, "cle_noel.bbmodel"), bb_key)


# ---------------------------------------------------------------------------------------------------- renders

def pivot_lid(angle):
    """Lid opened by `angle` degrees (front up), around its hinge."""
    return about(rot3("x", angle), (8, HINGE[0], HINGE[1]))


def spin(angle):
    return about(rot3("y", angle), (SPIN[0], 0, SPIN[1]))


def scale_y(s):
    M = np.eye(4)
    M[1, 1] = s
    M[1, 3] = 8 * (1 - s)
    return M


def crate_scene(r, parts, tex, lid=0.0, medal=0.0, keyhole=False, light=False, beam=0.0, dim=1.0):
    r.shadow((8, 0, 8), 9.5, 7.5)
    r.add(parts["corps"], tex, dim=dim)
    L = pivot_lid(lid)
    r.add(parts["couvercle"], tex, L, dim=dim)
    r.add(parts["medaillon"], tex, L @ spin(medal), dim=dim)
    if keyhole:
        r.add(parts["serrure"], tex)
    if light:
        r.add(parts["lueur"], tex)
    if beam > 0:
        r.add(parts["rayon"], tex, scale_y(beam))


def sprinkle(r, seed, n, box, colours, size=(0.25, 0.45), shape="square", alpha=1.0):
    rng = np.random.default_rng(seed)
    (x0, y0, z0), (x1, y1, z1) = box
    for _ in range(n):
        p = (rng.uniform(x0, x1), rng.uniform(y0, y1), rng.uniform(z0, z1))
        r.particle(p, colours[rng.integers(len(colours))], rng.uniform(*size), alpha, shape)


def orbit(r, seed, n, radius, y0, y1, colours, size=0.4):
    rng = np.random.default_rng(seed)
    for k in range(n):
        a = 2 * math.pi * k / n + rng.uniform(-0.2, 0.2)
        y = y0 + (y1 - y0) * (k / n)
        r.particle((8 + radius * math.cos(a), y, 8 + radius * math.sin(a)), colours[k % len(colours)], size, 1.0,
                   "square" if k % 3 else "sparkle")


def item_icon(size=128):
    """A vanilla-like reward icon for the reveal (a red Christmas bauble), drawn flat."""
    img = np.zeros((16, 16, 4))
    y, x = np.mgrid[0:16, 0:16] + 0.5
    d = np.sqrt((x - 8) ** 2 + (y - 9) ** 2)
    ball = d < 5.6
    img[ball, :3] = ramp(["#FF6B7A", "#D42A3F", "#8C1428"], np.clip((x + y - 10) / 14, 0, 1))[ball]
    img[ball, 3] = 255
    img[(d < 1.6) & (np.abs(x - 6.5) < 1) & (np.abs(y - 7) < 1), :3] = 255
    img[1:4, 7:9, :3] = hexa(GOLD[5])
    img[1:4, 7:9, 3] = 255
    return img.astype(np.uint8)


def renders(folder, parts, atlas, eclat, key, key_atlas, key_anim):
    os.makedirs(folder, exist_ok=True)
    tex = {"main": atlas.image, "eclat": eclat[:16]}
    bg = ("#2A1846", "#0E0718")
    views = {
        "1_trois_quarts": dict(eye=(-14, 19, -18), target=(8, 6.5, 8), fov=34),
        "2_face": dict(eye=(8, 10, -26), target=(8, 7.5, 8), fov=32),
        "3_profil": dict(eye=(36, 9, 8), target=(8, 7.5, 8), fov=32),
        "4_dessus": dict(eye=(8.01, 40, -6), target=(8, 8, 8), fov=30),
        "5_dos": dict(eye=(28, 16, 32), target=(8, 7, 8), fov=34),
        "6_detail_serrure": dict(eye=(1, 9, -6), target=(8, 7.5, 3.2), fov=30),
        "7_detail_medaillon": dict(eye=(13, 22, 0), target=(8.5, 12.5, 7), fov=30),
    }
    for name, v in views.items():
        r = Renderer((900, 900), 3, background=bg, glow_bg=(0.5, 0.45, 0.6, "#3B1F5E"), **v)
        crate_scene(r, parts, tex, medal=15)
        r.finish().save(os.path.join(folder, f"caisse_{name}.png"))
    # ambiance: snow and fireflies
    r = Renderer((900, 900), 3, background=("#1B1030", "#07040D"), glow_bg=(0.5, 0.5, 0.5, "#2C1A48"),
                 **views["1_trois_quarts"])
    crate_scene(r, parts, tex, medal=15)
    r.flush()
    sprinkle(r, 2, 26, ((-6, 2, -6), (22, 30, 22)), ["#FFFFFF", "#EAF3F8", "#C6D8E5"], (0.25, 0.4))
    sprinkle(r, 3, 7, ((0, 8, 0), (16, 20, 16)), ["#FFE27A", "#FFD24D"], (0.5, 0.8), "glow")
    sprinkle(r, 4, 5, ((2, 9, 2), (14, 16, 14)), ["#FFF3C9"], (0.3, 0.4), "sparkle")
    r.finish().save(os.path.join(folder, "caisse_8_ambiance.png"))
    # opening sequence
    seq = []
    eye = dict(eye=(-13, 21, -22), target=(8, 10, 8), fov=42)
    steps = [
        ("1 Activation", dict(keyhole=True)),
        ("2 Éveil", dict(keyhole=True, light=True, medal=40)),
        ("3 Ouverture", dict(keyhole=True, light=True, lid=55, medal=80, beam=0.4)),
        ("4 Révélation", dict(keyhole=False, light=True, lid=105, medal=120, beam=1.0)),
    ]
    for k, (label, kw) in enumerate(steps):
        r = Renderer((600, 600), 3, background=("#1B1030", "#07040D"), glow_bg=(0.5, 0.45, 0.55, "#2C1A48"), **eye)
        crate_scene(r, parts, tex, **kw)
        r.flush()
        if k == 0:
            sprinkle(r, 10, 6, ((6.5, 4.5, 1.5), (9.5, 8, 2.8)), ["#F5D98B", "#A7DFF2"], (0.25, 0.35))
        if k == 1:
            orbit(r, 11, 22, 12.5, 2, 15, ["#F5D98B", "#A7DFF2", "#FFFFFF"], 0.45)
        if k == 2:
            orbit(r, 12, 16, 12.5, 6, 17, ["#F5D98B", "#A7DFF2", "#FFFFFF"], 0.4)
            sprinkle(r, 13, 30, ((3, 10, 4), (13, 22, 12)), ["#F5D98B", "#FFFFFF", "#A7DFF2"], (0.3, 0.5))
        if k == 3:
            icon = item_icon()
            el = El("objet", (5.5, 15.5, 8), (10.5, 20.5, 8), None, tex=("fixed", "objet", [0, 0, 16, 16]), shade=False,
                    faces=("north",))
            fixed_uv([el])
            r.add([el], {"objet": icon})
            r.flush()
            sprinkle(r, 14, 18, ((3, 15, 5), (13, 27, 11)), ["#FFF3C9", "#FFE27A"], (0.3, 0.45), "sparkle")
            sprinkle(r, 15, 12, ((1, 4, 1), (15, 22, 15)), ["#FFFFFF", "#EAF3F8"], (0.25, 0.35))
        img = r.finish()
        seq.append((label, img))
    W = sum(i.width for _, i in seq)
    sheet = Image.new("RGB", (W, seq[0][1].height))
    x = 0
    for _, i in seq:
        sheet.paste(i, (x, 0))
        x += i.width
    sheet.save(os.path.join(folder, "caisse_9_sequence.png"))
    # key
    ktex = {"main": key_anim[:key_atlas.size]}
    for name, v in {"face": dict(eye=(8, 8, 34), target=(7.5, 8, 8), fov=34),
                    "trois_quarts": dict(eye=(26, 14, 28), target=(7.5, 8, 8), fov=34)}.items():
        r = Renderer((700, 700), 3, background=bg, glow_bg=(0.5, 0.5, 0.6, "#3B1F5E"), **v)
        r.add(key, ktex)
        r.finish().save(os.path.join(folder, f"cle_{name}.png"))
    # key in inventory (gui: rotated 45°, front lighting), with a frame of its sheen
    r = Renderer((400, 400), 3, eye=(8, 8, 60), target=(8, 8, 8), fov=18, background=("#8B8B8B", "#8B8B8B"))
    M = about(rot3("z", -45), (8, 8, 8))
    r.add(key, {"main": key_anim[key_atlas.size * 4:key_atlas.size * 5]}, M)
    r.finish().save(os.path.join(folder, "cle_inventaire.png"))


def main(plugins, folder=None):
    parts, atlas, eclat, key, key_atlas, key_anim = build()
    if plugins != "-":
        write(plugins, parts, atlas, eclat, key, key_atlas, key_anim)
    if folder:
        renders(folder, parts, atlas, eclat, key, key_atlas, key_anim)
    print("atlas caisse", atlas.size, "| clé", key_atlas.size, "x", KEY_FRAMES, "images")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
