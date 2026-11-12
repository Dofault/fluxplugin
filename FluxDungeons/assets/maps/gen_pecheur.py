#!/usr/bin/env python3
"""Map of the fisher's dungeon, Le Sanctuaire des Abysses Gelées (boss: the Loutre des Marées), built by console
commands (RCON).

An ancient sanctuary swallowed by a huge cave, played from z -14 to z 200 (+z) and going down at every step:
  - the wet grotto (start at 0, 64, -6): natural cave, puddles, drips, stalactites;
  - corridor 1 (9 wide): wet cave, then an old stone gallery going down three steps;
  - room 1, the Harpooners' Gallery (27 x 25, 13 high, floor 60): two rows of prismarine pillars, arches, statues of
    sea warriors and trident niches in the walls, a collapsed wall and barred windows onto lit cavities, two small
    waterfalls into the side channels; open centre. Gate at the far end (drowned harpooners, cursed fishermen);
  - corridor 2: a glass tunnel through an aquarium (fish, kelp, coral), going down to 56;
  - room 2, the Sentinels' Basin (29 x 29, 16 high): a 17 x 17 pool 5 deep in the middle, a 6-wide walkway around it,
    four sets of steps out of the water, big blue windows onto aquariums, oxidized copper pipes and a copper machine
    hanging from the vault (tide guardians in the pool);
  - corridor 3: a wooden service gallery of the old port (barrels, nets, fishing rods), going down to 52;
  - room 3, the Drowned Fishers' Port (30 x 26): a stone quay along the west side and the south, a basin 6 deep with
    two pontoons, steps into the water, a half-sunk boat, a collapsed hut, winches, nets, underwater caves; the
    drowned master fisher waits on a dais against the west wall;
  - corridor 4: a monumental frozen gallery (9 wide, 11 high) ending on a ledge 3 blocks above the arena;
  - room 4, the Frozen Heart Arena: an octagon 25 across inside a 35 x 35 hall, eight monumental pillars, a vault with
    ice stalactites and a copper ring. Its floor is one sheet of packed ice with a ritual circle (blue ice, dark
    prismarine), over a reservoir 9 deep that also runs under the walls. At half life the otter breaks the whole sheet
    (flood `from` of mobs/pecheur/loutre_marees.yml: ICE, PACKED_ICE, BLUE_ICE, DARK_PRISMARINE, connected): the
    players fall in, nothing dry is left within reach (ledge and exit gate 3 blocks above the water).

The map is first modelled block by block in memory, then checked (doors cannot be bypassed, water stays where it is
put, the ice sheet is whole and nothing dry is left once it breaks), then sent as merged fills.

Usage: gen_pecheur.py [server] [--check] [--preview DIR]
  labs (default): builds, copies into maps/custom/pecheur, writes edits/pecheur.yml.
  --check: only the checks (nothing sent); --preview: PNG slices of the map in DIR.
"""
import math
import os
import random
import struct
import sys
import zlib
from collections import deque

import maplib

F0, F1, F2, F3, FI = 63, 60, 56, 52, 44   # floor blocks: grotto, room 1, room 2, room 3 quay, arena ice
WL3 = F3 - 1                              # top water block of the port basin
AX, ZA = -8, 180                          # arena centre
FROM = ("ice", "packed_ice", "blue_ice", "dark_prismarine")   # what the otter breaks (flood from)
X0, X1, Y0, Y1, Z0, Z1 = -36, 36, 30, 92, -18, 204
BANDS = ((30, 41, "deepslate"), (42, 50, "tuff"), (51, 74, "stone"), (75, 92, "andesite"))

r = random.Random(41)


# ======================================================================== block model

class Grid:
    def __init__(self):
        self.over = {}

    @staticmethod
    def base(y):
        for y0, y1, b in BANDS:
            if y0 <= y <= y1:
                return b
        return "stone"

    @staticmethod
    def inside(x, y, z):
        return X0 <= x <= X1 and Y0 <= y <= Y1 and Z0 <= z <= Z1

    def get(self, x, y, z):
        if not self.inside(x, y, z):
            return "bedrock"
        return self.over.get((x, y, z)) or self.base(y)

    def set(self, x, y, z, b):
        if self.inside(x, y, z):
            self.over[(x, y, z)] = b

    def box(self, x0, y0, z0, x1, y1, z1, b, where=None):
        x0, x1 = sorted((x0, x1))
        y0, y1 = sorted((y0, y1))
        z0, z1 = sorted((z0, z1))
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    if where is None or where(self.get(x, y, z)):
                        self.set(x, y, z, b(x, y, z) if callable(b) else b)

    def rock(self, x, y, z):
        return (x, y, z) not in self.over


g = Grid()

PASS_WORDS = ("seagrass", "kelp", "coral_fan", "light[", "cobweb", "lichen", "vine")


def passable(b):
    return b in ("air", "water") or any(w in b for w in PASS_WORDS) or b.endswith("_coral") or "_coral[" in b


def wet(b):
    return b == "water" or "waterlogged=true" in b or "seagrass" in b or "kelp" in b


GRAVITY = ("sand", "gravel", "suspicious_sand", "red_sand")


def gravity(b):
    return b in GRAVITY or b.endswith("concrete_powder")


def pick(table):
    tot = sum(w for _, w in table)
    v = r.uniform(0, tot)
    for b, w in table:
        v -= w
        if v <= 0:
            return b
    return table[-1][0]


class Noise:
    def __init__(self, seed):
        q = random.Random(seed)
        self.p = [(q.uniform(0.12, 0.42), q.uniform(0.12, 0.42), q.uniform(0.12, 0.42), q.uniform(0, 6.3), q.uniform(0.3, 1))
                  for _ in range(5)]
        self.t = sum(w for *_, w in self.p)

    def __call__(self, x, y, z):
        return sum(w * math.sin(x * a + y * b + z * c + ph) for a, b, c, ph, w in self.p) / self.t


def blob(cx, cy, cz, rx, ry, rz, clip, seed, rough=0.3, block="air", where=None):
    """An irregular ellipsoid, clipped to clip = (x0, y0, z0, x1, y1, z1)."""
    n = Noise(seed)
    cx0, cy0, cz0, cx1, cy1, cz1 = clip
    for x in range(max(cx0, int(cx - rx * 1.4)), min(cx1, int(cx + rx * 1.4)) + 1):
        for y in range(max(cy0, int(cy - ry * 1.4)), min(cy1, int(cy + ry * 1.4)) + 1):
            for z in range(max(cz0, int(cz - rz * 1.4)), min(cz1, int(cz + rz * 1.4)) + 1):
                d = ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 + ((z - cz) / rz) ** 2
                if d <= 1 + rough * n(x, y, z):
                    if where is None or where(g.get(x, y, z)):
                        g.set(x, y, z, block)


def ceiling(x, y, z, limit=30):
    """First solid y above (x, y, z) (which is open)."""
    for yy in range(y, y + limit):
        if not passable(g.get(x, yy, z)):
            return yy
    return None


def floor_below(x, y, z, limit=30):
    for yy in range(y, y - limit, -1):
        if not passable(g.get(x, yy, z)):
            return yy
    return None


def stalactite(x, y, z, length, mats, tip=None):
    """Hanging from the ceiling above (x, y, z): mats from the top down, then the tip block."""
    top = ceiling(x, y, z)
    if top is None:
        return
    yy = top - 1
    for i in range(length):
        if g.get(x, yy, z) != "air":
            return
        last = i == length - 1
        g.set(x, yy, z, tip if (last and tip) else mats[min(i, len(mats) - 1)])
        yy -= 1


def dripstone_down(x, y, z, length):
    top = ceiling(x, y, z)
    if top is None:
        return
    if length == 1:
        parts = ["tip"]
    else:
        parts = ["base"] + ["middle"] * max(0, length - 3) + (["frustum"] if length > 2 else []) + ["tip"]
    yy = top - 1
    for p in parts:
        if g.get(x, yy, z) != "air":
            return
        g.set(x, yy, z, f"pointed_dripstone[thickness={p},vertical_direction=down]")
        yy -= 1


def dripstone_up(x, y, z, length):
    fl = floor_below(x, y, z)
    if fl is None:
        return
    if length == 1:
        parts = ["tip"]
    else:
        parts = ["base"] + ["middle"] * max(0, length - 3) + (["frustum"] if length > 2 else []) + ["tip"]
    yy = fl + 1
    for p in parts:
        if g.get(x, yy, z) != "air":
            return
        g.set(x, yy, z, f"pointed_dripstone[thickness={p},vertical_direction=up]")
        yy += 1


def hang_chain(x, y, z, bottom, end=None):
    """A chain from the ceiling above (x, y, z) down to y bottom, with an end block under it."""
    top = ceiling(x, y, z)
    if top is None:
        return
    for yy in range(top - 1, bottom - 1, -1):
        if g.get(x, yy, z) != "air":
            return
        g.set(x, yy, z, "iron_chain[axis=y]")
    if end and g.get(x, bottom - 1, z) == "air":
        g.set(x, bottom - 1, z, end)


def kelp(x, y0, z, h):
    for i in range(h):
        if g.get(x, y0 + i, z) != "water":
            return
        top = i == h - 1 or g.get(x, y0 + i + 1, z) != "water"
        g.set(x, y0 + i, z, "kelp[age=25]" if top else "kelp_plant")
        if top:
            return


def pool(x, z, y, rad=1):
    """A small pool cut in the floor at y (a rim of rock kept around it)."""
    for dx in range(-rad - 1, rad + 2):
        for dz in range(-rad - 1, rad + 2):
            if abs(dx) <= rad and abs(dz) <= rad and dx * dx + dz * dz <= rad * rad + 1:
                g.set(x + dx, y, z + dz, "water")
                if passable(g.get(x + dx, y - 1, z + dz)):
                    g.set(x + dx, y - 1, z + dz, "stone")
            elif passable(g.get(x + dx, y, z + dz)):
                g.set(x + dx, y, z + dz, "mossy_cobblestone")


FALLS = set()   # water sources allowed to flow (waterfalls)


def waterfall(x, z, y_floor):
    """A thin fall from the ceiling above (x, z) into a 1-block pool at y_floor."""
    pool(x, z, y_floor, 0)
    top = ceiling(x, y_floor + 1, z)
    if top is None:
        return
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        if passable(g.get(x + dx, top, z + dz)):
            g.set(x + dx, top, z + dz, "stone")
    if passable(g.get(x, top + 1, z)):
        g.set(x, top + 1, z, "stone")
    g.set(x, top, z, "water")
    FALLS.add((x, top, z))


ENTITIES = []


def item_display(x, y, z, item, yaw=0.0, roll=0.0, scale=1.0):
    s = math.sin(math.radians(roll) / 2)
    c = math.cos(math.radians(roll) / 2)
    ENTITIES.append(
        f'summon item_display {x} {y} {z} {{item:{{id:"minecraft:{item}",count:1}},item_display:"fixed",'
        f'Rotation:[{yaw}f,0f],transformation:{{left_rotation:[0f,0f,{s:.4f}f,{c:.4f}f],right_rotation:[0f,0f,0f,1f],'
        f'translation:[0f,0f,0f],scale:[{scale}f,{scale}f,{scale}f]}}}}')


def fish(x, y, z):
    kind = r.choice(["tropical_fish", "tropical_fish", "tropical_fish", "cod", "salmon", "pufferfish"])
    extra = f",Variant:{r.choice([65536, 917504, 918273, 16778497, 50660352, 67108865, 117441025, 101253888, 117899265, 235340288])}" \
        if kind == "tropical_fish" else ""
    ENTITIES.append(f"summon {kind} {x} {y} {z} {{PersistenceRequired:1b,Silent:1b{extra}}}")


def zone(z):
    if z < 30:
        return "cave"
    if z < 56:
        return "ruin"
    if z < 101:
        return "aqua"
    if z < 144:
        return "port"
    return "ice"


SKINS = {
    "cave": [("stone", 30), ("mossy_cobblestone", 22), ("moss_block", 8), ("cobblestone", 8), ("andesite", 8), ("clay", 6),
             ("gravel", 6), ("dripstone_block", 6), ("tuff", 6)],
    "ruin": [("stone", 25), ("mossy_cobblestone", 18), ("andesite", 12), ("tuff", 10), ("cobblestone", 8),
             ("mossy_stone_bricks", 8), ("prismarine", 6), ("clay", 4), ("dripstone_block", 6)],
    "aqua": [("deepslate", 25), ("cobbled_deepslate", 15), ("tuff", 15), ("prismarine", 12), ("clay", 6),
             ("dark_prismarine", 6), ("calcite", 4), ("smooth_basalt", 6)],
    "port": [("stone", 25), ("tuff", 20), ("andesite", 15), ("cobblestone", 10), ("mossy_cobblestone", 10),
             ("gravel", 8), ("clay", 6), ("dripstone_block", 6)],
    "ice": [("deepslate", 25), ("packed_ice", 20), ("snow_block", 15), ("cobbled_deepslate", 10), ("calcite", 8),
            ("blue_ice", 5), ("smooth_basalt", 6), ("tuff", 6)],
}


# ======================================================================== the wet grotto and corridor 1

def build_grotto():
    blob(0, F0 + 4, -4, 9, 6, 10, (-11, F0 + 1, -16, 11, F0 + 12, 8), seed=1, rough=0.35)
    blob(-8, F0 + 6, -9, 5, 5, 4, (-14, F0 + 3, -16, -4, F0 + 12, 0), seed=2)      # side hollows (out of reach)
    blob(9, F0 + 7, 0, 4, 4, 5, (5, F0 + 4, -6, 14, F0 + 12, 6), seed=3)
    g.box(-4, F0 + 1, -12, 4, F0 + 4, 8, "air")
    for x, z in ((-6, -8), (6, -3), (-5, 4), (5, 6)):
        pool(x, z, F0, 1)
    waterfall(6, -3, F0)
    waterfall(-6, -8, F0)
    for _ in range(26):
        x, z = r.randint(-10, 10), r.randint(-14, 8)
        if g.get(x, F0 + 5, z) == "air":
            dripstone_down(x, F0 + 5, z, r.randint(1, 4))
    for _ in range(12):
        x, z = r.choice([-1, 1]) * r.randint(5, 9), r.randint(-13, 7)
        if g.get(x, F0 + 1, z) == "air":
            dripstone_up(x, F0 + 1, z, r.randint(1, 3))
    for x, z in ((-3, -10), (3, -6), (0, 0), (-3, 5), (3, 7)):
        g.set(x, F0 + 6, z, "light[level=11]")
    for x, z in ((-6, -8), (6, -3), (-5, 4), (5, 6)):
        g.set(x, F0, z, "sea_pickle[pickles=3,waterlogged=true]")


def corridor1():
    n = Noise(11)
    for z in range(9, 19):
        hw = 4 + (1 if n(0, 0, z * 3) > 0.2 else 0)
        h = 7 + int(round((n(z, 1, 0) + 1)))
        g.box(-hw, F0 + 1, z, hw, F0 + h, z, "air")
        if r.random() < 0.6:
            g.set(r.choice([-hw, hw]), F0 + r.randint(4, 6), z, "light[level=9]")
    for _ in range(10):
        x, z = r.randint(-4, 4), r.randint(9, 18)
        dripstone_down(x, F0 + 3, z, r.randint(1, 3))
    pool(4, 13, F0, 0)
    pool(-4, 16, F0, 0)

    def fz(z):
        return F0 if z <= 19 else F0 - 1 if z <= 22 else F0 - 2 if z <= 25 else F1

    wall = [("stone_bricks", 40), ("mossy_stone_bricks", 30), ("cracked_stone_bricks", 20), ("andesite", 5), ("mossy_cobblestone", 5)]
    for z in range(19, 29):
        f = fz(z)
        g.box(-5, f, z, 5, f + 8, z, lambda *a: pick(wall))
        g.box(-4, f + 1, z, 4, f + 7, z, "air")
        g.box(-4, f, z, 4, f, z, lambda *a: pick([("stone_bricks", 50), ("mossy_stone_bricks", 25), ("cracked_stone_bricks", 15), ("polished_andesite", 10)]))
        if fz(z - 1) > f:
            g.box(-4, f + 1, z, 4, f + 1, z, "stone_brick_stairs[facing=north]")
    for z in (20, 24, 28):
        f = fz(z)
        for x in (-4, 4):
            g.box(x, f + 1, z, x, f + 6, z, "chiseled_stone_bricks" if z == 28 else "stone_bricks")
            g.set(x, f + 7, z, "stone_bricks")
        g.set(-3, f + 7, z, "stone_brick_stairs[facing=west,half=top]")
        g.set(3, f + 7, z, "stone_brick_stairs[facing=east,half=top]")
        if z != 28:
            g.box(-4, f + 1, z, 4, f + 1, z, "stone_brick_stairs[facing=north]") if fz(z - 1) > f else None
    for z in (22, 26):
        f = fz(z)
        g.set(-5, f + 4, z, "sea_lantern")
        g.set(5, f + 4, z, "sea_lantern")
        hang_chain(0, f + 5, z, f + 6, "lantern[hanging=true]")
    for z in (21, 25):
        f = fz(z)
        for x in (-4, 4):
            if r.random() < 0.7:
                g.set(x, f + 7, z, "cobweb")


# ======================================================================== room 1: the Harpooners' Gallery

def room1():
    def wallmat(x, y, z):
        if y <= F1 + 6:
            return pick([("prismarine_bricks", 55), ("prismarine", 20), ("mossy_stone_bricks", 10), ("dark_prismarine", 5), ("cracked_stone_bricks", 10)])
        if y == F1 + 7:
            return "dark_prismarine"
        return pick([("prismarine", 30), ("prismarine_bricks", 25), ("mossy_cobblestone", 15), ("stone", 15), ("cracked_stone_bricks", 15)])

    g.box(-14, F1, 29, 14, F1 + 13, 55, wallmat)
    g.box(-13, F1 + 1, 30, 13, F1 + 13, 54, "air")
    blob(0, F1 + 13, 42, 15, 5, 14, (-13, F1 + 14, 30, 13, F1 + 20, 54), seed=21, rough=0.4)

    # floor: prismarine bricks, dark borders of the nave, a medallion, damp aisles with channels
    def floor(x, y, z):
        if abs(x) == 8:
            return "dark_prismarine"
        if abs(x) > 8:
            return pick([("mossy_stone_bricks", 30), ("prismarine_bricks", 30), ("cracked_stone_bricks", 15), ("prismarine", 15), ("mossy_cobblestone", 10)])
        d = math.hypot(x, z - 42)
        if d < 1:
            return "sea_lantern"
        if 3.5 <= d < 4.5 or 6.5 <= d < 7.2:
            return "dark_prismarine"
        if d < 3.5:
            return "prismarine"
        return pick([("prismarine_bricks", 80), ("prismarine", 12), ("cracked_stone_bricks", 4), ("mossy_stone_bricks", 4)])

    g.box(-13, F1, 30, 13, F1, 54, floor)
    for sx in (-1, 1):
        g.box(12 * sx, F1, 31, 12 * sx, F1, 53, "water")
        for z in range(31, 54, 7):
            g.set(12 * sx, F1, z, "sea_pickle[pickles=2,waterlogged=true]")
    for _ in range(14):
        x, z = r.choice([-1, 1]) * r.randint(11, 13), r.randint(31, 53)
        if g.get(x, F1, z) != "water":
            g.set(x, F1, z, "water")

    # pillars and arches
    rows = (34, 41, 48)
    for sx in (-1, 1):
        for zc in rows:
            for x in (9 * sx, 10 * sx):
                for z in (zc, zc + 1):
                    for y in range(F1 + 1, F1 + 14):
                        b = "dark_prismarine" if y in (F1 + 1, F1 + 11, F1 + 12, F1 + 13) else "prismarine_bricks"
                        if y == F1 + 6 and x == 9 * sx:
                            b = "sea_lantern"
                        g.set(x, y, z, b)
        # arches along the rows
        spans = [(30, rows[0] - 1)] + [(rows[i] + 2, rows[i + 1] - 1) for i in range(len(rows) - 1)] + [(rows[-1] + 2, 54)]
        for z0, z1 in spans:
            for x in (9 * sx, 10 * sx):
                g.box(x, F1 + 13, z0, x, F1 + 13, z1, "prismarine_bricks")
                if z0 != 30:
                    g.set(x, F1 + 12, z0, "prismarine_brick_stairs[facing=north,half=top]")
                if z1 != 54:
                    g.set(x, F1 + 12, z1, "prismarine_brick_stairs[facing=south,half=top]")
        # arches to the outer wall and over the nave
        for zc in rows:
            for z in (zc, zc + 1):
                g.box(11 * sx, F1 + 12, z, 13 * sx, F1 + 12, z, "prismarine_bricks")
                g.set(11 * sx, F1 + 11, z, f"prismarine_brick_stairs[facing={'east' if sx < 0 else 'west'},half=top]")
                g.set(13 * sx, F1 + 11, z, f"prismarine_brick_stairs[facing={'west' if sx < 0 else 'east'},half=top]")
    for zc in rows:
        for z in (zc, zc + 1):
            g.box(-8, F1 + 13, z, 8, F1 + 13, z, "dark_prismarine")
            g.set(-8, F1 + 12, z, "prismarine_brick_stairs[facing=west,half=top]")
            g.set(8, F1 + 12, z, "prismarine_brick_stairs[facing=east,half=top]")

    # statues of sea warriors in niches
    for sx in (-1, 1):
        for z in (32, 52):
            wx, nx, bx = 14 * sx, 15 * sx, 16 * sx
            g.box(bx, F1 + 1, z - 2, bx, F1 + 7, z + 2, "dark_prismarine")
            g.box(nx, F1 + 1, z - 1, nx, F1 + 6, z + 1, "air")
            g.box(wx, F1 + 1, z - 1, wx, F1 + 6, z + 1, "air")
            g.box(wx, F1 + 1, z - 2, wx, F1 + 7, z - 2, "dark_prismarine")
            g.box(wx, F1 + 1, z + 2, wx, F1 + 7, z + 2, "dark_prismarine")
            g.box(wx, F1 + 7, z - 1, wx, F1 + 7, z + 1, "dark_prismarine")
            g.set(wx, F1 + 6, z, "sea_lantern")
            g.set(nx, F1 + 1, z, "dark_prismarine")
            g.set(nx, F1 + 2, z, "prismarine_wall")
            g.set(nx, F1 + 3, z, "prismarine_bricks")
            g.set(nx, F1 + 4, z, "prismarine")
            g.set(nx, F1 + 4, z - 1, "prismarine_brick_stairs[facing=north,half=top]")
            g.set(nx, F1 + 4, z + 1, "prismarine_brick_stairs[facing=south,half=top]")
            g.set(nx, F1 + 5, z, "prismarine_bricks")
            g.set(nx, F1 + 6, z, "prismarine_brick_slab")
            item_display(wx - 0.4 * sx + 0.5, F1 + 4.3, z + 1.5, "trident", yaw=90 if sx < 0 else -90, roll=45, scale=1.6)
        # a niche of tridents, between the statues
        g.box(14 * sx, F1 + 3, 41, 14 * sx, F1 + 5, 43, "air")
        g.box(15 * sx, F1 + 2, 40, 15 * sx, F1 + 6, 44, "dark_prismarine")
        g.box(14 * sx, F1 + 2, 41, 14 * sx, F1 + 2, 43, "dark_prismarine")
        g.box(14 * sx, F1 + 6, 41, 14 * sx, F1 + 6, 43, "dark_prismarine")
        g.set(14 * sx, F1 + 2, 42, "sea_lantern")
        for k, z in enumerate((41, 42, 43)):
            item_display(14 * sx + 0.5, F1 + 4.1, z + 0.5, "trident", yaw=90 if sx < 0 else -90, roll=(-30, 45, 120)[k], scale=1.3)
        g.box(14 * sx, F1 + 7, 41, 14 * sx, F1 + 7, 43, f"iron_bars[north=true,south=true]")

    # waterfalls from the walls into the channels
    for sx, z in ((-1, 37), (1, 47)):
        g.set(14 * sx, F1 + 11, z, "water")
        FALLS.add((14 * sx, F1 + 11, z))
        g.set(13 * sx, F1, z, "water")
        g.set(15 * sx, F1 + 11, z, "prismarine")
        g.set(14 * sx, F1 + 12, z, "mossy_cobblestone")

    # the collapsed east wall onto a lit cavity (barriers in the gap)
    blob(23, F1 + 9, 39, 8, 7, 8, (15, F1 + 1, 30, 32, F1 + 20, 54), seed=22, rough=0.35)
    g.box(14, F1 + 6, 36, 14, F1 + 13, 40, "barrier")
    for x in range(15, 18):
        for z in range(36, 41):
            for y in range(F1 + 6, F1 + 14):
                if not passable(g.get(x, y, z)):
                    g.set(x, y, z, "air")
    for z in range(35, 42):
        for x in range(11, 14):
            h = max(0, 2 - abs(z - 38) // 2 - (13 - x) // 2 + r.randint(-1, 0))
            for y in range(F1 + 1, F1 + 1 + h):
                g.set(x, y, z, pick([("mossy_cobblestone", 3), ("cobblestone", 2), ("prismarine", 2), ("gravel", 1)]))
    # ruins in the east cavity: broken columns, a fall into a pool
    for x, z, h in ((21, 34, 7), (25, 43, 4), (20, 47, 9)):
        fl = floor_below(x, F1 + 8, z)
        if fl:
            for y in range(fl + 1, fl + 1 + h):
                g.set(x, y, z, "prismarine_bricks" if y % 4 else "dark_prismarine")
            g.set(x, fl + 1 + h, z, "prismarine_brick_slab")
    for x, z in ((24, 37), (19, 41)):
        fl = floor_below(x, F1 + 8, z)
        if fl:
            waterfall(x, z, fl)
            g.set(x, fl - 1, z, "sea_lantern")

    # barred windows high in the west wall onto another cavity
    blob(-23, F1 + 9, 46, 8, 7, 7, (-32, F1 + 1, 30, -15, F1 + 20, 54), seed=23, rough=0.35)
    for z0 in (37, 45):
        g.box(-14, F1 + 7, z0, -14, F1 + 10, z0 + 2, "iron_bars[north=true,south=true]")
        g.box(-14, F1 + 7, z0 - 1, -14, F1 + 11, z0 - 1, "dark_prismarine")
        g.box(-14, F1 + 7, z0 + 3, -14, F1 + 11, z0 + 3, "dark_prismarine")
        g.box(-14, F1 + 11, z0, -14, F1 + 11, z0 + 2, "dark_prismarine")
        g.box(-17, F1 + 7, z0, -15, F1 + 10, z0 + 2, "air")
    for x, z, h in ((-22, 40, 6), (-26, 49, 8), (-19, 51, 3)):
        fl = floor_below(x, F1 + 8, z)
        if fl:
            for y in range(fl + 1, fl + 1 + h):
                g.set(x, y, z, "prismarine_bricks" if y % 3 else "sea_lantern")
    fl = floor_below(-23, F1 + 8, 45)
    if fl:
        waterfall(-23, 45, fl)
    for _ in range(14):
        dripstone_down(r.randint(-28, -17), F1 + 9, r.randint(38, 53), r.randint(1, 4))
        dripstone_down(r.randint(17, 29), F1 + 9, r.randint(32, 50), r.randint(1, 4))

    # lights, chains, stalactites
    for sx in (-1, 1):
        for z in (36, 45, 50):
            if g.get(14 * sx, F1 + 3, z) not in ("air", "barrier", "water"):
                g.set(14 * sx, F1 + 3, z, "sea_lantern")
    for x, z in ((-4, 37), (4, 37), (-4, 46), (4, 46), (0, 51), (0, 33)):
        hang_chain(x, F1 + 12, z, F1 + 10, "sea_lantern")
    for sx in (-1, 1):
        for z in (38, 45):
            hang_chain(12 * sx, F1 + 10, z, F1 + 8, "lantern[hanging=true]")
    for _ in range(30):
        x, z = r.randint(-12, 12), r.randint(31, 53)
        if g.get(x, F1 + 13, z) == "air":
            dripstone_down(x, F1 + 13, z, r.randint(1, 3))
    for x, z in ((-5, 35), (5, 35), (-5, 49), (5, 49), (0, 42)):
        g.set(x, F1 + 9, z, "light[level=12]")

    # the entrance arch (south) and the gate (north)
    g.box(-4, F1 + 1, 29, 4, F1 + 8, 29, "dark_prismarine")
    g.box(-3, F1 + 1, 29, 3, F1 + 7, 29, "air")
    g.set(-4, F1 + 8, 29, "sea_lantern")
    g.set(4, F1 + 8, 29, "sea_lantern")
    door_frame(0, 55, F1, north=False, inside_z=54)
    m.door("porte_galerie", (-2, F1 + 1, 55), (2, F1 + 5, 55), room="1")
    for x, z in ((-5, 37), (5, 39), (-3, 46), (4, 48), (0, 51)):
        m.mob("pecheur_noye", x, z, y=F1 + 1, yaw=180, room="1")
    for x, z in ((3, 34), (-7, 43), (7, 44)):
        m.mob("pecheur_maudit", x, z, y=F1 + 1, yaw=180, room="1")


def door_frame(cx, z, f, north=False, inside_z=None):
    """A gate: dark prismarine arch with pillars and sea lanterns, iron bars across the 5 x 5 opening at plane z."""
    g.box(cx - 4, f + 1, z, cx + 4, f + 7, z, "dark_prismarine")
    g.box(cx - 2, f + 1, z, cx + 2, f + 5, z, "iron_bars[east=true,west=true]")
    if inside_z is not None:
        for x in (cx - 4, cx - 3, cx + 3, cx + 4):
            g.box(x, f + 1, inside_z, x, f + 6, inside_z, "dark_prismarine")
        g.box(cx - 4, f + 7, inside_z, cx + 4, f + 7, inside_z, "dark_prismarine")
        g.box(cx - 2, f + 6, inside_z, cx + 2, f + 6, inside_z, "prismarine_brick_slab[type=top]")
        g.set(cx - 3, f + 4, inside_z, "sea_lantern")
        g.set(cx + 3, f + 4, inside_z, "sea_lantern")
        g.set(cx, f + 7, inside_z, "sea_lantern")


# ======================================================================== corridor 2: the glass tunnel

def corridor2():
    def fz(z):
        return F1 if z <= 57 else F1 - 1 if z <= 60 else F1 - 2 if z <= 63 else F1 - 3 if z <= 66 else F2

    g.box(-8, F2 - 2, 57, 8, F1 + 10, 68, "water")
    g.box(-8, F2 - 3, 57, 8, F2 - 3, 68, lambda *a: pick([("sand", 6), ("gravel", 1), ("clay", 1)]))
    tunnel = set()
    for z in range(56, 70):
        f = fz(z)
        g.box(-4, f, z, 4, f, z, "dark_prismarine")
        g.box(-3, f, z, 3, f, z, lambda x, y, zz: "sea_lantern" if x == 0 and zz % 3 == 0 else "prismarine_bricks")
        g.box(-3, f + 1, z, 3, f + 6, z, "air")
        for x in range(-3, 4):
            for y in range(f + 1, f + 7):
                tunnel.add((x, y, z))
        if fz(z - 1) > f:
            g.box(-3, f + 1, z, 3, f + 1, z, "prismarine_brick_stairs[facing=north]")
    glass = ("light_blue_stained_glass", "cyan_stained_glass", "blue_stained_glass")
    for (x, y, z) in list(tunnel):
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0)):
            p = (x + dx, y + dy, z + dz)
            if p not in tunnel and g.get(*p) == "water":
                rib = p[2] in (59, 62, 65)
                g.set(*p, "dark_prismarine" if rib else glass[(p[1] + p[2]) % 3])
    for z in (59, 62, 65):
        g.set(0, fz(z) + 7, z, "sea_lantern")
    for x in range(-8, 9, 4):
        for z in (58, 63, 67):
            if abs(x) >= 5:
                g.set(x, F2 - 3, z, "sea_lantern")
    for _ in range(40):
        x, z = r.choice([-1, 1]) * r.randint(5, 8), r.randint(57, 68)
        if g.get(x, F2 - 2, z) != "water":
            continue
        k = r.random()
        if k < 0.35:
            kelp(x, F2 - 2, z, r.randint(4, 12))
        elif k < 0.6:
            col = r.choice(["tube", "brain", "bubble", "fire", "horn"])
            h = r.randint(1, 3)
            g.box(x, F2 - 2, z, x, F2 - 3 + h, z, f"{col}_coral_block")
            if g.get(x, F2 - 2 + h, z) == "water":
                g.set(x, F2 - 2 + h, z, f"{r.choice(['tube', 'brain', 'bubble', 'fire', 'horn'])}_coral_fan[waterlogged=true]")
        elif k < 0.85:
            g.set(x, F2 - 2, z, "seagrass")
        else:
            g.set(x, F2 - 2, z, f"sea_pickle[pickles={r.randint(1, 4)},waterlogged=true]")
    for _ in range(12):
        fish(r.choice([-1, 1]) * r.uniform(5.3, 8.5), r.uniform(F2, F1 + 8), r.uniform(57.5, 68.5))
    for _ in range(4):
        fish(r.uniform(-2.5, 3.5), F1 + 9.3, r.uniform(57.5, 68.5))


# ======================================================================== room 2: the Sentinels' Basin

def room2():
    def wallmat(x, y, z):
        if y == F2 + 1 or y == F2 + 12:
            return "dark_prismarine"
        return pick([("prismarine_bricks", 50), ("prismarine", 30), ("dark_prismarine", 8), ("deepslate_tiles", 12)])

    g.box(-15, F2, 70, 15, F2 + 16, 100, wallmat)
    g.box(-14, F2 + 1, 71, 14, F2 + 16, 99, "air")
    blob(0, F2 + 16, 85, 15, 6, 15, (-14, F2 + 17, 71, 14, F2 + 24, 99), seed=31, rough=0.4)

    # walkway and pool (17 x 17, 5 deep)
    def floor(x, y, z):
        if -9 <= x <= 9 and 76 <= z <= 94:
            return "dark_prismarine"
        return "prismarine_bricks" if (x + z) % 5 else "prismarine"

    g.box(-14, F2, 71, 14, F2, 99, floor)
    g.box(-9, F2 - 6, 76, 9, F2 - 1, 94, "prismarine_bricks")
    g.box(-8, F2 - 5, 77, 8, F2, 93, "water")
    g.box(-8, F2 - 6, 77, 8, F2 - 6, 93, lambda x, y, z: "sea_lantern" if x % 4 == 0 and (z - 77) % 4 == 0 and abs(x) < 8 and 77 < z < 93
          else ("dark_prismarine" if (x + z) % 2 else "prismarine"))
    # four sets of steps out of the water
    for i in range(4):
        y = F2 - 1 - i
        for x in range(-1, 2):
            g.set(x, y, 77 + i, "prismarine_brick_stairs[facing=north,waterlogged=true]")
            g.box(x, F2 - 5, 77 + i, x, y - 1, 77 + i, "prismarine_bricks")
            g.set(x, y, 93 - i, "prismarine_brick_stairs[facing=south,waterlogged=true]")
            g.box(x, F2 - 5, 93 - i, x, y - 1, 93 - i, "prismarine_bricks")
        for z in range(84, 87):
            g.set(-8 + i, y, z, "prismarine_brick_stairs[facing=west,waterlogged=true]")
            g.box(-8 + i, F2 - 5, z, -8 + i, y - 1, z, "prismarine_bricks")
            g.set(8 - i, y, z, "prismarine_brick_stairs[facing=east,waterlogged=true]")
            g.box(8 - i, F2 - 5, z, 8 - i, y - 1, z, "prismarine_bricks")
    # coral, seagrass, kelp along the pool walls (the middle stays open)
    for _ in range(60):
        x, z = r.randint(-8, 8), r.randint(77, 93)
        if max(abs(x), abs(z - 85)) < 6 or g.get(x, F2 - 5, z) != "water":
            continue
        k = r.random()
        if k < 0.3:
            kelp(x, F2 - 5, z, r.randint(2, 4))
        elif k < 0.55:
            g.set(x, F2 - 5, z, f"{r.choice(['tube', 'brain', 'bubble', 'fire', 'horn'])}_coral_block")
            g.set(x, F2 - 4, z, f"{r.choice(['tube', 'brain', 'bubble', 'fire', 'horn'])}_coral_fan[waterlogged=true]")
        elif k < 0.85:
            g.set(x, F2 - 5, z, "seagrass")
        else:
            g.set(x, F2 - 5, z, f"sea_pickle[pickles={r.randint(2, 4)},waterlogged=true]")

    # four massive pillars at the corners of the walkway, an arcade between them
    P = [(-11, 74), (11, 74), (-11, 96), (11, 96)]
    for px, pz in P:
        for x in range(px - 1, px + 2):
            for z in range(pz - 1, pz + 2):
                for y in range(F2 + 1, F2 + 17):
                    corner = x != px and z != pz
                    b = "dark_prismarine" if corner or y in (F2 + 1, F2 + 15, F2 + 16) else "prismarine_bricks"
                    if y in (F2 + 6, F2 + 11) and not corner:
                        b = "sea_lantern"
                    if y == F2 + 9:
                        b = "oxidized_cut_copper"
                    g.set(x, y, z, b)
    for sx in (-1, 1):
        g.box(11 * sx, F2 + 16, 76, 11 * sx, F2 + 16, 94, "prismarine_bricks")
        g.set(11 * sx, F2 + 15, 76, "prismarine_brick_stairs[facing=north,half=top]")
        g.set(11 * sx, F2 + 15, 94, "prismarine_brick_stairs[facing=south,half=top]")
    for z in (74, 96):
        g.box(-9, F2 + 16, z, 9, F2 + 16, z, "prismarine_bricks")
        g.set(-9, F2 + 15, z, "prismarine_brick_stairs[facing=west,half=top]")
        g.set(9, F2 + 15, z, "prismarine_brick_stairs[facing=east,half=top]")

    # big blue windows onto aquariums
    for sx in (-1, 1):
        wx = 15 * sx
        g.box(16 * sx, F2 - 1, 75, 21 * sx, F2 + 14, 95, "water")
        g.box(16 * sx, F2 - 2, 75, 21 * sx, F2 - 2, 95, lambda *a: pick([("sand", 5), ("gravel", 1)]))
        for z in range(76, 95):
            for y in range(F2 + 2, F2 + 13):
                frame = z in (76, 82, 88, 94) or y in (F2 + 2, F2 + 7, F2 + 12)
                g.set(wx, y, z, "dark_prismarine" if frame else "blue_stained_glass")
        for z in (72, 98):
            g.set(wx, F2 + 6, z, "sea_lantern")
        for _ in range(45):
            x, z = sx * r.randint(16, 21), r.randint(75, 95)
            if g.get(x, F2 - 1, z) != "water":
                continue
            k = r.random()
            if k < 0.3:
                kelp(x, F2 - 1, z, r.randint(5, 14))
            elif k < 0.65:
                col = r.choice(["tube", "brain", "bubble", "fire", "horn"])
                h = r.randint(1, 5)
                g.box(x, F2 - 1, z, x, F2 - 2 + h, z, f"{col}_coral_block")
                if g.get(x, F2 - 1 + h, z) == "water":
                    g.set(x, F2 - 1 + h, z, f"{r.choice(['tube', 'brain', 'bubble', 'fire', 'horn'])}_coral{r.choice(['', '_fan'])}[waterlogged=true]")
            elif k < 0.85:
                g.set(x, F2 - 1, z, "seagrass")
            else:
                g.set(x, F2 - 1, z, f"sea_pickle[pickles=4,waterlogged=true]")
        for z in (78, 86, 92):
            g.set(21 * sx, F2 - 2, z, "sea_lantern")
            g.set(21 * sx, F2 + 8, z, "sea_lantern")
        for x, z, h in ((19 * sx, 80, 8), (18 * sx, 90, 5)):
            g.box(x, F2 - 1, z, x, F2 - 2 + h, z, "prismarine_bricks")
            g.set(x, F2 - 1 + h, z, "prismarine_brick_slab[type=bottom,waterlogged=true]")
        for _ in range(9):
            fish(sx * r.uniform(16.3, 21.7), r.uniform(F2 + 1, F2 + 13), r.uniform(75.5, 95.5))

    # oxidized copper pipes along the walls, bulbs, and down the corners
    for z in (71, 99):
        for x in range(-13, 14):
            g.set(x, F2 + 13, z, "oxidized_copper_bulb[lit=true]" if x % 8 == 0 else
                  ("oxidized_copper_grate" if x % 4 == 0 else "oxidized_cut_copper"))
    for sx in (-1, 1):
        for z in range(71, 100):
            if 75 <= z <= 95:
                continue
            g.set(14 * sx, F2 + 13, z, "oxidized_cut_copper")
        for z in (71, 99):
            g.box(14 * sx, F2 + 1, z, 14 * sx, F2 + 12, z, lambda x, y, zz: "oxidized_copper_grate" if y % 4 == 0 else "oxidized_copper")
    # the machine hanging from the vault over the pool
    cy = F2 + 11
    for x in range(-9, 10):
        for z in range(76, 95):
            d = math.hypot(x, z - 85)
            a = math.degrees(math.atan2(z - 85, x)) % 360
            if 6.5 <= d < 7.5:
                g.set(x, cy, z, "oxidized_cut_copper")
            elif 7.5 <= d < 8.4 and int(a // 15) % 2 == 0:
                g.set(x, cy, z, "oxidized_copper")
            elif 3 <= d < 6.5 and min(abs(a - k) for k in (45, 135, 225, 315)) < 8:
                g.set(x, cy, z, "oxidized_cut_copper_slab[type=top]")
            elif 2.5 <= d < 3.5:
                g.set(x, cy, z, "dark_prismarine")
            elif d < 2.5:
                g.box(x, cy - 1, z, x, cy + 1, z, "oxidized_copper")
    g.set(0, cy - 2, 85, "conduit[waterlogged=false]")
    for a in range(0, 360, 90):
        x, z = round(7 * math.cos(math.radians(a + 45))), 85 + round(7 * math.sin(math.radians(a + 45)))
        g.set(x, cy, z, "sea_lantern")
        g.set(x, cy - 1, z, "oxidized_lightning_rod[facing=down]")
        hang_chain(x, cy + 1, z, cy + 1)
    for x, z in ((0, 85), (1, 85), (-1, 85), (0, 84), (0, 86)):
        top = ceiling(x, cy + 2, z)
        if top:
            g.box(x, cy + 2, z, x, top - 1, z, "oxidized_copper" if (x, z) == (0, 85) else "iron_chain[axis=y]")
    for _ in range(24):
        x, z = r.randint(-13, 13), r.randint(72, 98)
        if math.hypot(x, z - 85) > 9:
            dripstone_down(x, F2 + 16, z, r.randint(1, 3))
    for x, z in ((-5, 74), (5, 74), (-5, 96), (5, 96), (-12, 85), (12, 85)):
        g.set(x, F2 + 8, z, "light[level=13]")

    # entrance (south) and gate (north)
    g.box(-4, F2 + 1, 70, 4, F2 + 8, 70, "dark_prismarine")
    g.box(-3, F2 + 1, 70, 3, F2 + 6, 70, "air")
    g.set(0, F2 + 7, 70, "sea_lantern")
    door_frame(0, 100, F2, inside_z=99)
    m.door("porte_bassin", (-2, F2 + 1, 100), (2, F2 + 5, 100), room="2")
    for x, z in ((-4, 81), (4, 81), (-4, 89), (4, 89)):
        m.mob("pecheur_gardien", x, z, y=F2 - 2, yaw=180, room="2")


# ======================================================================== corridor 3: the service gallery

def corridor3():
    def fz(z):
        return F2 if z <= 102 else F2 - 1 if z <= 105 else F2 - 2 if z <= 108 else F2 - 3 if z <= 111 else F3

    for z in range(101, 116):
        f = fz(z)
        g.box(-5, f, z, 5, f + 8, z, lambda *a: pick([t for t in SKINS["port"] if not gravity(t[0])]))
        g.box(-4, f + 1, z, 4, f + 7, z, "air")
        g.box(-3, f, z, 3, f, z, "spruce_planks")
        g.set(-4, f, z, "cobblestone")
        g.set(4, f, z, "cobblestone")
        g.box(-5, f + 1, z, -5, f + 3, z, "spruce_planks")
        g.box(5, f + 1, z, 5, f + 3, z, "spruce_planks")
        if fz(z - 1) > f:
            g.box(-4, f + 1, z, 4, f + 1, z, "spruce_stairs[facing=north]")
    for z in (104, 108, 112):
        f = fz(z)
        g.box(-4, f + 1, z, -4, f + 6, z, "spruce_log")
        g.box(4, f + 1, z, 4, f + 6, z, "spruce_log")
        g.box(-4, f + 7, z, 4, f + 7, z, "stripped_spruce_log[axis=x]")
        g.set(0, f + 6, z, "lantern[hanging=true]")
        for x in (-3, 3):
            g.set(x, f + 7 - 1, z, "cobweb") if r.random() < 0.5 else None
    for x, z, h in ((-4, 102, 2), (4, 106, 1), (-4, 110, 2), (4, 113, 2), (-4, 114, 1)):
        f = fz(z)
        for y in range(f + 1, f + 1 + h):
            g.set(x, y, z, r.choice(["barrel[facing=up]", "barrel[facing=north]", "spruce_planks"]))
    for x, z in ((-4, 105), (4, 110), (-4, 113), (4, 103)):
        f = fz(z)
        for y in (f + 5, f + 6):
            g.set(x, y, z, "cobweb")
    for x, z in ((-4.4, 107), (4.4, 111), (-4.4, 103)):
        item_display(x + 0.5, fz(int(z)) + 3.5, z + 0.5, "fishing_rod", yaw=90 if x < 0 else -90, roll=30, scale=1.2)
    for z in (106, 110):
        g.set(-5, fz(z) + 5, z, "lantern")
        g.set(5, fz(z) + 5, z, "lantern")


# ======================================================================== room 3: the Drowned Fishers' Port

def room3():
    g.box(-15, F3 + 1, 117, 14, F3 + 13, 142, "air")
    g.box(-5, F3 + 1, 116, 5, F3 + 9, 116, "dark_prismarine")     # entrance arch from the service gallery
    g.box(-4, F3 + 1, 116, 4, F3 + 7, 116, "air")
    g.box(-4, F3 + 1, 116, -4, F3 + 6, 116, "stripped_spruce_log")
    g.box(4, F3 + 1, 116, 4, F3 + 6, 116, "stripped_spruce_log")
    g.box(-4, F3 + 7, 116, 4, F3 + 7, 116, "stripped_spruce_log[axis=x]")
    g.set(-5, F3 + 8, 116, "sea_lantern")
    g.set(5, F3 + 8, 116, "sea_lantern")
    blob(0, F3 + 13, 130, 16, 5, 14, (-15, F3 + 14, 117, 14, F3 + 19, 142), seed=41, rough=0.4)
    for i, (x, y, z) in enumerate(((-16, F3 + 7, 121), (15, F3 + 9, 136), (-16, F3 + 10, 140), (6, F3 + 9, 143), (15, F3 + 6, 119))):
        blob(x, y, z, 3, 3, 3, (-19, F3 + 5, 117, 18, F3 + 16, 142), seed=42 + i)
    # quay (west and south): stone wharf, spruce deck
    g.box(-15, F3 - 7, 117, 14, F3 - 1, 142, lambda *a: pick([("stone_bricks", 4), ("mossy_stone_bricks", 3), ("cobblestone", 2)]))
    g.box(-15, F3, 117, 14, F3, 142, "spruce_planks")
    # basin, 6 deep
    g.box(-2, F3 - 6, 122, 14, WL3, 142, "water")
    g.box(-2, F3 - 7, 122, 14, F3 - 7, 142, lambda x, y, z: "sea_lantern" if x % 5 == 0 and z % 5 == 0 else pick([("gravel", 3), ("sand", 3), ("clay", 2), ("tuff", 1)]))
    g.box(-2, F3, 122, 14, F3, 142, "air")
    g.box(-3, F3, 122, -3, F3, 142, "stripped_spruce_log[axis=z]")
    g.box(-3, F3, 121, 14, F3, 121, "stripped_spruce_log[axis=x]")
    # rocky shore in the north-east corner, with the collapsed hut
    for x in range(9, 15):
        for z in range(136, 143):
            if (x - 9) + (142 - z) >= 2 or x >= 11:
                g.box(x, F3 - 7, z, x, F3, z, lambda *a: pick([("stone", 3), ("tuff", 2), ("gravel", 1), ("cobblestone", 1)]))
    g.box(11, F3, 137, 14, F3, 142, "spruce_planks")
    for x, z in ((11, 137), (14, 137), (11, 142)):
        g.box(x, F3 + 1, z, x, F3 + (3 if (x, z) != (11, 137) else 1), z, "stripped_spruce_log")
    g.box(12, F3 + 1, 137, 13, F3 + 2, 137, "spruce_planks")
    g.box(11, F3 + 1, 139, 11, F3 + 1, 141, "spruce_planks")
    g.box(14, F3 + 1, 138, 14, F3 + 3, 141, "spruce_planks")
    g.box(12, F3 + 4, 141, 13, F3 + 4, 142, "spruce_stairs[facing=south]")
    g.box(12, F3 + 3, 140, 13, F3 + 3, 140, "spruce_stairs[facing=south]")
    g.box(12, F3 + 1, 138, 13, F3 + 1, 139, "spruce_slab[type=bottom]")
    g.set(13, F3 + 1, 141, "barrel[facing=up]")
    g.set(12, F3 + 1, 141, "lantern")
    g.set(14, F3 + 4, 139, "cobweb")
    g.set(11, F3 + 2, 140, "cobweb")
    item_display(13.5, F3 + 2.3, 141.9, "fishing_rod", yaw=180, roll=-40, scale=1.2)

    # two pontoons on posts
    for z0, x1 in ((126, 7), (135, 5)):
        g.box(-2, F3, z0, x1, F3, z0 + 2, "spruce_planks")
        g.box(-2, F3, z0 + 1, x1, F3, z0 + 1, "stripped_spruce_log[axis=x]")
        for x in range(0, x1 + 1, 3):
            for z in (z0, z0 + 2):
                g.box(x, F3 - 6, z, x, WL3, z, "spruce_log")
        g.set(x1, F3 + 1, z0, "spruce_fence")
        g.set(x1, F3 + 2, z0, "lantern")
    # steps into the water
    for i in range(4):
        for x in range(4, 7):
            g.set(x, WL3 - i, 122 + i, "stone_brick_stairs[facing=north,waterlogged=true]")
            g.box(x, F3 - 6, 122 + i, x, WL3 - i - 1, 122 + i, "stone_bricks")
        for z in range(139, 142):
            g.set(-2 + i, WL3 - i, z, "stone_brick_stairs[facing=west,waterlogged=true]")
            g.box(-2 + i, F3 - 6, z, -2 + i, WL3 - i - 1, z, "stone_bricks")
    # a half-sunk boat
    for i, z in enumerate(range(124, 132)):
        y = F3 - 4 + i // 2
        wl = "true" if y <= WL3 else "false"
        g.box(8, y, z, 10, y, z, "spruce_planks")
        for x in (7, 11):
            g.set(x, y + 1, z, f"spruce_slab[type=bottom,waterlogged={'true' if y + 1 <= WL3 else 'false'}]")
        if z == 128:
            for yy in range(y + 1, y + 4):
                g.set(9, yy, z, f"spruce_fence[waterlogged={'true' if yy <= WL3 else 'false'}]")
    g.set(9, F3, 132, "spruce_stairs[facing=south]")
    g.set(9, F3 + 1, 128, "spruce_fence")
    g.set(9, F3 + 2, 128, "white_wool")
    # winches: a drum on posts, a crank, a jib over the water and its chain
    for (px, pz, jx, jz) in ((-4, 131, 0, 131), (9, 119, 9, 124)):
        g.box(px, F3 + 1, pz - 1, px, F3 + 1, pz + 1, "spruce_fence")
        g.box(px, F3 + 2, pz - 1, px, F3 + 2, pz + 1, "stripped_spruce_log[axis=z]")
        g.set(px, F3 + 2, pz + 2, "grindstone[face=wall,facing=south]")
        post = (-3, pz) if px == -4 else (px, 121)
        g.box(post[0], F3 + 1, post[1], post[0], F3 + 4, post[1], "spruce_log")
        if px == -4:
            g.box(-2, F3 + 4, pz, jx, F3 + 4, pz, "stripped_spruce_log[axis=x]")
        else:
            g.box(px, F3 + 4, 122, px, F3 + 4, jz, "stripped_spruce_log[axis=z]")
        g.box(jx, F3 + 1, jz, jx, F3 + 3, jz, "iron_chain[axis=y]")
        g.set(jx, WL3, jz, "iron_chain[axis=y,waterlogged=true]")
        g.set(jx, WL3 - 1, jz, "barrel[facing=up]")
    # nets on the walls (above head height), barrels and crates in the corners
    for (x0, z0, x1, z1) in ((-15, 118, -15, 125), (-12, 117, -6, 117), (14, 120, 14, 124)):
        g.box(x0, F3 + 3, z0, x1, F3 + 6, z1, lambda x, y, z: "cobweb" if (x + y + z) % 3 else "air")
        g.box(x0, F3 + 7, z0, x1, F3 + 7, z1, "spruce_fence")
    for x, z, h in ((-15, 117, 3), (-14, 117, 2), (-15, 127, 2), (13, 117, 2), (14, 117, 3), (14, 118, 1), (-15, 141, 2), (-14, 142, 1)):
        for y in range(F3 + 1, F3 + 1 + h):
            g.set(x, y, z, r.choice(["barrel[facing=up]", "barrel[facing=east]", "spruce_planks", "stripped_spruce_wood"]))
    # the master fisher's dais against the west wall
    for x in range(-15, -9):
        for z in range(131, 138):
            d = math.hypot(x + 12, z - 134)
            if d < 1:
                g.set(x, F3, z, "sea_lantern")
            elif d < 2.6:
                g.set(x, F3, z, "dark_oak_planks")
            elif d < 3.4:
                g.set(x, F3, z, "stripped_dark_oak_log")
    g.box(-17, F3 + 1, 131, -16, F3 + 8, 137, "dark_prismarine")
    g.box(-16, F3 + 1, 132, -16, F3 + 7, 136, "air")
    g.box(-17, F3 + 2, 132, -17, F3 + 6, 136, "deepslate_tiles")
    g.set(-17, F3 + 1, 134, "anvil[facing=north]")
    g.box(-17, F3 + 2, 134, -17, F3 + 5, 134, "iron_bars[north=true,south=true]")
    g.set(-17, F3 + 6, 134, "iron_chain[axis=y]")
    g.set(-17, F3 + 2, 133, "iron_bars[north=true,south=true]")
    g.set(-17, F3 + 2, 135, "iron_bars[north=true,south=true]")
    for z in (132, 136):
        g.set(-16, F3 + 7, z, "iron_chain[axis=y]")
        g.set(-16, F3 + 6, z, "soul_lantern[hanging=true]")
    for z in (131, 137):
        g.box(-15, F3 + 1, z, -15, F3 + 2, z, "spruce_fence")
        g.set(-15, F3 + 3, z, "lantern")
    # lamp posts on the quay edge, hanging lanterns, light
    for x, z in ((-3, 123), (-3, 134), (0, 121), (12, 121)):
        g.box(x, F3 + 1, z, x, F3 + 2, z, "spruce_fence")
        g.set(x, F3 + 3, z, "lantern")
    for x, z in ((4, 130), (8, 138), (-8, 126), (-8, 139), (3, 120)):
        hang_chain(x, F3 + 12, z, F3 + 9, "lantern[hanging=true]")
    for _ in range(24):
        x, z = r.randint(-14, 13), r.randint(118, 141)
        dripstone_down(x, F3 + 12, z, r.randint(1, 3))
    for x, z in ((-9, 122), (-9, 132), (5, 131), (5, 140), (-9, 140)):
        g.set(x, F3 + 8, z, "light[level=12]")
    # seagrass and kelp in the basin
    for _ in range(50):
        x, z = r.randint(-2, 14), r.randint(122, 142)
        if g.get(x, F3 - 6, z) != "water":
            continue
        k = r.random()
        if k < 0.4:
            kelp(x, F3 - 6, z, r.randint(2, 5))
        elif k < 0.75:
            g.set(x, F3 - 6, z, "seagrass")
        elif k < 0.9:
            g.set(x, F3 - 6, z, f"{r.choice(['tube', 'brain', 'horn'])}_coral_block")
            g.set(x, F3 - 5, z, f"{r.choice(['tube', 'brain', 'horn'])}_coral_fan[waterlogged=true]")
        else:
            g.set(x, F3 - 6, z, "sea_pickle[pickles=4,waterlogged=true]")
    # underwater caves (north and east), dead ends lit by sea pickles
    for x0, x1, z0, z1, y1 in ((1, 5, 143, 150, F3 + 1), (16, 22, 125, 131, F3 + 6)):
        g.box(x0, F3 - 6, z0, x1, WL3, z1, "water")
        g.box(x0, F3, z0, x1, y1, z1, "air")
        for _ in range(8):
            x, z = r.randint(x0, x1), r.randint(z0, z1)
            g.set(x, F3 - 6, z, r.choice(["sea_pickle[pickles=4,waterlogged=true]", "seagrass", "kelp_plant"]))
            if g.get(x, F3 - 6, z) == "kelp_plant":
                g.set(x, F3 - 6, z, "seagrass")
        g.set((x0 + x1) // 2, F3 - 7, (z0 + z1) // 2, "sea_lantern")
    g.box(1, F3, 143, 5, F3 + 1, 143, "air")
    g.set(3, F3 + 2, 143, "dark_prismarine")
    g.box(15, F3, 126, 15, F3 + 3, 130, "air")
    fl = WL3
    g.set(19, F3 + 6, 128, "water")
    g.set(19, F3 + 7, 128, "stone")
    FALLS.add((19, F3 + 6, 128))
    g.set(22, F3 + 3, 125, "soul_lantern")
    g.set(22, F3 + 2, 125, "stone")

    door_frame(AX, 143, F3, inside_z=142)
    m.door("porte_port", (AX - 2, F3 + 1, 143), (AX + 2, F3 + 5, 143), room="3")
    m.mob("pecheur_maitre", -12, 134, y=F3 + 1, yaw=180, room="3")
    for x, z in ((3, 127), (2, 136)):
        m.mob("pecheur_noye", x, z, y=F3 + 1, yaw=180, room="3")
    for x, z in ((-11, 124), (-5, 125)):
        m.mob("pecheur_maudit", x, z, y=F3 + 1, yaw=180, room="3")


# ======================================================================== corridor 4: the frozen gallery

def corridor4_floor(z):
    return F3 if z <= 147 else F3 - 1 if z <= 151 else F3 - 2 if z <= 155 else F3 - 3 if z <= 159 else F3 - 4 if z <= 163 else FI + 3


def corridor4():
    for z in range(144, 168):
        f = corridor4_floor(z)

        def wall(x, y, zz):
            if y <= f + 2:
                return "dark_prismarine"
            if y >= f + 10:
                return pick([("packed_ice", 4), ("snow_block", 3), ("blue_ice", 1)])
            return pick([("packed_ice", 5), ("prismarine_bricks", 3), ("snow_block", 2), ("blue_ice", 1)])

        g.box(AX - 5, f, z, AX + 5, f + 11, z, wall)
        g.box(AX - 4, f + 1, z, AX + 4, f + 10, z, "air")
        g.box(AX - 4, f, z, AX + 4, f, z, lambda x, y, zz: "snow_block" if abs(x - AX) == 4 else
              ("dark_prismarine" if abs(x - AX) == 3 else ("polished_deepslate" if (x + zz) % 2 else "deepslate_tiles")))
        if corridor4_floor(z - 1) > f:
            g.box(AX - 4, f + 1, z, AX + 4, f + 1, z, "deepslate_tile_stairs[facing=north]")
    for z in (146, 150, 154, 158, 162):
        f = corridor4_floor(z)
        for x in (AX - 4, AX + 4):
            g.box(x, f + 1, z, x, f + 8, z, lambda xx, y, zz: "sea_lantern" if y == f + 5 else "dark_prismarine")
            g.set(x, f + 9, z, "prismarine_bricks")
        g.set(AX - 3, f + 9, z, "prismarine_brick_stairs[facing=west,half=top]")
        g.set(AX + 3, f + 9, z, "prismarine_brick_stairs[facing=east,half=top]")
        g.box(AX - 4, f + 10, z, AX + 4, f + 10, z, "prismarine_bricks")
        g.set(AX, f + 10, z, "sea_lantern")
    for z in (148, 152, 156, 160):
        f = corridor4_floor(z)
        for x in (AX - 4, AX + 4):
            g.set(x, f + 1, z, "blue_ice")
            if r.random() < 0.6:
                g.set(x, f + 1, z + 1, "packed_ice")
    for _ in range(26):
        x, z = AX + r.randint(-3, 3), r.randint(145, 166)
        if z in (146, 150, 154, 158, 162):
            continue
        stalactite(x, corridor4_floor(z) + 7, z, r.randint(1, 3), ["packed_ice", "packed_ice"], "blue_ice")


# ======================================================================== room 4: the Frozen Heart Arena

def octagon(dx, dz, half=12, cut=17):
    return abs(dx) <= half and abs(dz) <= half and abs(dx) + abs(dz) <= cut


def rim(dx, dz):
    return octagon(dx, dz, 14, 20)


PILLARS = [(12, 5), (12, -5), (-12, 5), (-12, -5), (5, 12), (-5, 12), (5, -12), (-5, -12)]
TOP = FI + 20     # last air layer of the arena before the vault


def arena():
    def wall(x, y, z):
        if y < FI:
            return pick([("prismarine_bricks", 5), ("dark_prismarine", 2), ("prismarine", 2)])
        if y == FI:
            return "prismarine_bricks"
        if y <= FI + 3:
            return "dark_prismarine" if y != FI + 3 else "prismarine_bricks"
        if y <= FI + 12:
            return pick([("prismarine_bricks", 5), ("packed_ice", 3), ("dark_prismarine", 1)])
        return pick([("packed_ice", 4), ("snow_block", 3), ("blue_ice", 1), ("deepslate", 2)])

    g.box(AX - 17, FI - 10, ZA - 17, AX + 17, TOP + 2, ZA + 17, wall)
    for dx in range(-15, 16):
        for dz in range(-15, 16):
            x, z = AX + dx, ZA + dz
            if rim(dx, dz):
                g.set(x, FI - 9, z, "sea_lantern" if dx % 5 == 0 and dz % 5 == 0 else ("dark_prismarine" if (dx + dz) % 2 else "prismarine"))
                g.box(x, FI - 8, z, x, FI - 2, z, "water")
            if octagon(dx, dz):
                g.set(x, FI - 1, z, "water")
                g.box(x, FI + 1, z, x, TOP, z, "air")
                d = math.hypot(dx, dz)
                a = math.degrees(math.atan2(dz, dx)) % 360
                ray = min(abs(((a + 22.5) % 45) - 22.5), 45) < 7 and 2 <= d < 9.5
                if d < 1.6 or 6 <= d < 7:
                    b = "dark_prismarine"
                elif 9.5 <= d < 10.5 or ray:
                    b = "blue_ice"
                else:
                    b = "packed_ice"
                g.set(x, FI, z, b)
            elif rim(dx, dz):
                if (dx + 3 * dz) % 7 == 0:
                    g.set(x, FI - 1, z, "sea_lantern")
    blob(AX, TOP, ZA, 16, 7, 16, (AX - 15, TOP + 1, ZA - 15, AX + 15, TOP + 10, ZA + 15), seed=51, rough=0.35)
    # blue-ice veins in the sheet (part of the floor that breaks)
    for _ in range(14):
        a = r.uniform(0, 2 * math.pi)
        rr = r.uniform(10.5, 12)
        for k in range(r.randint(2, 4)):
            dx, dz = round(math.cos(a) * (rr - k)), round(math.sin(a) * (rr - k))
            if octagon(dx, dz) and g.get(AX + dx, FI, ZA + dz) == "packed_ice":
                g.set(AX + dx, FI, ZA + dz, "blue_ice")

    # eight monumental pillars at the corners of the octagon
    for vx, vz in PILLARS:
        for dx in range(vx - 1, vx + 2):
            for dz in range(vz - 1, vz + 2):
                corner = dx != vx and dz != vz
                for y in range(FI - 9, TOP + 6):
                    if y < FI:
                        b = "dark_prismarine" if corner else "prismarine_bricks"
                    elif y == FI:
                        b = "prismarine_bricks"
                    elif y <= FI + 2 or y >= TOP - 1:
                        b = "dark_prismarine"
                    elif y in (FI + 7, FI + 15) and not corner:
                        b = "sea_lantern"
                    elif y == FI + 11:
                        b = "oxidized_cut_copper"
                    elif corner:
                        b = "dark_prismarine"
                    else:
                        b = "blue_ice" if (dx == vx or dz == vz) and (dx, dz) != (vx, vz) else "prismarine_bricks"
                    g.set(AX + dx, y, ZA + dz, b)
        # a capital flaring out high up
        for dx in range(vx - 2, vx + 3):
            for dz in range(vz - 2, vz + 3):
                if abs(dx - vx) == 2 or abs(dz - vz) == 2:
                    g.set(AX + dx, TOP - 1, ZA + dz, "dark_prismarine")
                    g.set(AX + dx, TOP, ZA + dz, "prismarine_bricks")
        g.set(AX + vx, TOP - 2, ZA + vz, g.get(AX + vx, TOP - 2, ZA + vz))
    # frozen falls flush with the walls between the pillars
    for dx in range(-13, 14):
        for dz in range(-13, 14):
            if octagon(dx, dz) or not octagon(dx, dz, 13, 18):
                continue
            if any(abs(dx - vx) <= 2 and abs(dz - vz) <= 2 for vx, vz in PILLARS):
                continue
            x, z = AX + dx, ZA + dz
            if not any(octagon(dx + ex, dz + ez) for ex, ez in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                continue
            n = (dx * 7 + dz * 13) % 5
            low = FI + 5 + n
            for y in range(low, TOP + 1):
                if g.get(x, y, z) in ("air", "water"):
                    continue
                g.set(x, y, z, "blue_ice" if (y + n) % 4 else "packed_ice")
    # under the water: coral and ice along the rim, the middle stays open
    for dx in range(-14, 15):
        for dz in range(-14, 15):
            if not rim(dx, dz) or octagon(dx, dz, 9, 13):
                continue
            x, z = AX + dx, ZA + dz
            if g.get(x, FI - 8, z) != "water":
                continue
            k = r.random()
            if k < 0.12:
                col = r.choice(["tube", "brain", "bubble", "fire", "horn"])
                h = r.randint(1, 2)
                g.box(x, FI - 8, z, x, FI - 9 + h, z, f"{col}_coral_block")
                g.set(x, FI - 8 + h, z, f"{r.choice(['tube', 'brain', 'bubble', 'fire', 'horn'])}_coral_fan[waterlogged=true]")
            elif k < 0.2:
                h = r.randint(1, 4)
                g.box(x, FI - 8, z, x, FI - 9 + h, z, lambda *a: r.choice(["packed_ice", "blue_ice"]))
            elif k < 0.32:
                kelp(x, FI - 8, z, r.randint(2, 6))
            elif k < 0.45:
                g.set(x, FI - 8, z, "seagrass")
    # sunken arches between the pillars, under the walls
    for vx, vz in PILLARS:
        ox, oz = (2 if vx > 0 else -2) if abs(vx) == 12 else 0, (2 if vz > 0 else -2) if abs(vz) == 12 else 0
        g.box(AX + vx + ox - (0 if ox else 1), FI - 8, ZA + vz + oz - (0 if oz else 1),
              AX + vx + ox + (0 if ox else 1), FI - 2, ZA + vz + oz + (0 if oz else 1),
              lambda x, y, z: "sea_lantern" if y == FI - 5 else "dark_prismarine")
    # vault: ice stalactites, rock, chains, the copper ring
    for _ in range(70):
        dx, dz = r.randint(-15, 15), r.randint(-15, 15)
        d = math.hypot(dx, dz)
        if 6.5 < d < 10.5 and r.random() < 0.8:
            continue
        mats = ["packed_ice", "packed_ice", "blue_ice"] if r.random() < 0.7 else ["deepslate", "cobbled_deepslate", "packed_ice"]
        stalactite(AX + dx, TOP, ZA + dz, r.randint(2, 6), mats, "blue_ice")
    cy = FI + 16
    for dx in range(-10, 11):
        for dz in range(-10, 11):
            d = math.hypot(dx, dz)
            a = math.degrees(math.atan2(dz, dx)) % 360
            x, z = AX + dx, ZA + dz
            if 7.5 <= d < 8.5:
                g.set(x, cy, z, "oxidized_cut_copper")
            elif 8.5 <= d < 9.4 and int(a // 12) % 2 == 0:
                g.set(x, cy, z, "oxidized_copper")
            elif 3.5 <= d < 4.5:
                g.set(x, cy, z, "dark_prismarine")
            elif 4.5 <= d < 7.5 and min(abs(a - k) for k in (0, 90, 180, 270, 360)) < 6:
                g.set(x, cy, z, "oxidized_cut_copper_slab[type=top]")
            elif d < 1.6:
                g.box(x, cy - 1, z, x, cy + 1, z, "blue_ice")
    g.set(AX, cy - 2, ZA, "conduit[waterlogged=false]")
    for a in range(0, 360, 45):
        x, z = AX + round(8 * math.cos(math.radians(a))), ZA + round(8 * math.sin(math.radians(a)))
        g.set(x, cy, z, "sea_lantern")
        g.set(x, cy - 1, z, "oxidized_lightning_rod[facing=down]")
        hang_chain(x, cy + 1, z, cy + 1)
    for dx, dz in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
        top = ceiling(AX + dx, cy + 2, ZA + dz)
        if top:
            g.box(AX + dx, cy + 2, ZA + dz, AX + dx, top - 1, ZA + dz, "oxidized_copper" if (dx, dz) == (0, 0) else "iron_chain[axis=y]")
    for _ in range(14):
        dx, dz = r.randint(-12, 12), r.randint(-12, 12)
        if octagon(dx, dz) and math.hypot(dx, dz) > 10:
            hang_chain(AX + dx, TOP, ZA + dz, FI + 13, "soul_lantern[hanging=true]" if r.random() < 0.5 else None)
    for dx, dz in ((-6, -6), (6, -6), (-6, 6), (6, 6), (0, -10), (0, 10), (-10, 0), (10, 0)):
        g.set(AX + dx, FI + 12, ZA + dz, "light[level=12]")
    # high niches out of reach: ice statues of the sanctuary's guardians
    for sx in (-1, 1):
        x = AX + 13 * sx
        g.box(x, FI + 8, ZA - 1, x + sx, FI + 13, ZA + 1, "air")
        g.box(x + 2 * sx, FI + 7, ZA - 2, x + 2 * sx, FI + 14, ZA + 2, "dark_prismarine")
        g.box(x, FI + 7, ZA - 1, x + sx, FI + 7, ZA + 1, "dark_prismarine")
        g.set(x + sx, FI + 8, ZA, "packed_ice")
        g.set(x + sx, FI + 9, ZA, "blue_ice")
        g.set(x + sx, FI + 10, ZA, "blue_ice")
        g.set(x + sx, FI + 11, ZA, "packed_ice")
        g.set(x + sx, FI + 10, ZA - 1, "packed_ice")
        g.set(x + sx, FI + 10, ZA + 1, "packed_ice")
        g.set(x + sx, FI + 12, ZA, "sea_lantern")
        item_display(x + sx * 0.6 + 0.5, FI + 10.2, ZA + 1.4, "trident", yaw=90 if sx < 0 else -90, roll=45, scale=2.2)

    # the entry ledge frame and the exit gate (opens when the otter dies, out of reach above the water)
    z = ZA - 13
    g.box(AX - 6, FI + 4, z, AX - 5, FI + 15, z, "dark_prismarine")
    g.box(AX + 5, FI + 4, z, AX + 6, FI + 15, z, "dark_prismarine")
    g.box(AX - 6, FI + 14, z, AX + 6, FI + 15, z, "dark_prismarine")
    g.set(AX - 5, FI + 13, z, "sea_lantern")
    g.set(AX + 5, FI + 13, z, "sea_lantern")
    g.set(AX, FI + 15, z, "sea_lantern")
    z = ZA + 13
    g.box(AX - 4, FI + 3, z, AX + 4, FI + 10, z, "dark_prismarine")
    g.box(AX - 2, FI + 4, z, AX + 2, FI + 8, z, "iron_bars[east=true,west=true]")
    g.set(AX - 3, FI + 7, z, "sea_lantern")
    g.set(AX + 3, FI + 7, z, "sea_lantern")
    g.set(AX, FI + 10, z, "sea_lantern")
    g.box(AX - 2, FI + 3, z + 1, AX + 2, FI + 3, z + 6, "dark_prismarine")
    g.box(AX - 2, FI + 4, z + 1, AX + 2, FI + 8, z + 6, "air")
    g.set(AX, FI + 6, z + 6, "sea_lantern")
    m.door("porte_sortie", (AX - 2, FI + 4, z), (AX + 2, FI + 8, z), room="4")
    m.mob("loutre_marees", AX, ZA, y=FI + 1, yaw=180, room="4")
    m.objectives = ["loutre_marees"]


# ======================================================================== skin of the rock

def skin():
    open_cells = [p for p, b in g.over.items() if passable(b)]
    for (x, y, z) in open_cells:
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            p = (x + dx, y + dy, z + dz)
            if not g.inside(*p) or p in g.over or r.random() < 0.35:
                continue
            table = SKINS[zone(p[2])]
            b = pick(table)
            under = g.get(p[0], p[1] - 1, p[2])
            if gravity(b) and (passable(under) or attached(under)):
                b = table[0][0]
            g.set(*p, b)


# ======================================================================== checks

DOORS = []


def check():
    errors = []
    # 1. gravity blocks resting on something
    for (x, y, z), b in g.over.items():
        if gravity(b) and (passable(g.get(x, y - 1, z)) or attached(g.get(x, y - 1, z))):
            errors.append(f"falling {b} at {x} {y} {z}")
    # 2. water stays put (only the waterfalls flow)
    for (x, y, z), b in g.over.items():
        if not wet(b) or (x, y, z) in FALLS:
            continue
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            n = g.get(x + dx, y + dy, z + dz)
            if n == "air" or n.startswith("light[") and "waterlogged=true" not in n:
                errors.append(f"water leaks at {x} {y} {z} -> {x + dx} {y + dy} {z + dz} ({n})")
                break
    # 3. the doors cannot be bypassed: with doors 0..k-1 open, only zones up to k+1 are reachable
    def zone_of(x, y, z):
        if z <= 55:
            return 1
        if z <= 100:
            return 2
        if z <= 143 or (x >= 0 and z <= 152):   # the port's underwater caves run north of its wall
            return 3
        if z >= ZA + 14 and FI + 3 <= y <= FI + 9 and abs(x - AX) <= 2:
            return 5                     # behind the exit gate
        return 4

    def reach(open_doors):
        blocked, opened = set(), set()
        for i, (a, b2) in enumerate(DOORS):
            for x in range(a[0], b2[0] + 1):
                for y in range(a[1], b2[1] + 1):
                    for z in range(a[2], b2[2] + 1):
                        (opened if i in open_doors else blocked).add((x, y, z))
        start = (int(math.floor(m.start[0])), int(m.start[1]), int(math.floor(m.start[2])))
        seen = {start}
        q = deque([start])
        while q:
            x, y, z = q.popleft()
            for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                p = (x + dx, y + dy, z + dz)
                if p in seen or p in blocked or not g.inside(*p):
                    continue
                if passable(g.get(*p)) or p in opened:
                    seen.add(p)
                    q.append(p)
        return seen

    for k in range(len(DOORS) + 1):
        seen = reach(set(range(k)))
        top = max(zone_of(*p) for p in seen)
        allowed = min(k + 1, 5)
        if top > allowed:
            bad = next(p for p in seen if zone_of(*p) > allowed)
            errors.append(f"doors open {k}: zone {top} reachable, e.g. {bad}")
        if top < min(k + 1, 4 if k < 4 else 5):
            errors.append(f"doors open {k}: zone {min(k + 1, 5)} not reachable (max {top})")
    # 4. the ice sheet: whole, walkable, in the flood list, and nothing of the list around it on its layer
    sheet = set()
    for dx in range(-17, 18):
        for dz in range(-17, 18):
            x, z = AX + dx, ZA + dz
            b = g.get(x, FI, z)
            if octagon(dx, dz) and not any(abs(dx - vx) <= 1 and abs(dz - vz) <= 1 for vx, vz in PILLARS):
                if b not in FROM:
                    errors.append(f"sheet hole at {x} {FI} {z}: {b}")
                if g.get(x, FI + 1, z) != "air" or g.get(x, FI + 2, z) != "air":
                    errors.append(f"something on the ice at {x} {FI + 1} {z}")
                sheet.add((x, FI, z))
            elif b in FROM:
                errors.append(f"{b} next to the sheet at {x} {FI} {z} (would break with it)")
    # 5. once broken: nothing to stand on near the water surface
    saved = {p: g.over[p] for p in sheet}
    for p in sheet:
        g.over[p] = "water"
    seen = set()
    q = deque([(AX, FI, ZA)])
    seen.add((AX, FI, ZA))
    while q:
        x, y, z = q.popleft()
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            p = (x + dx, y + dy, z + dz)
            if p in seen or abs(p[0] - AX) > 20 or abs(p[2] - ZA) > 20 or p[1] > FI + 3:
                continue
            if passable(g.get(*p)):
                seen.add(p)
                q.append(p)
    for (x, y, z) in seen:
        below = g.get(x, y - 1, z)
        if y - 1 >= FI - 1 and not passable(below) and passable(g.get(x, y + 1, z)):
            errors.append(f"dry footing after the break at {x} {y} {z} (on {below})")
    g.over.update(saved)
    return errors


# ======================================================================== output

ATTACH = ("pointed_dripstone", "lantern[", "seagrass", "kelp", "coral_fan", "sea_pickle", "iron_chain", "conduit",
          "lightning_rod", "grindstone", "anvil", "cobweb")


def attached(b):
    return any(a in b for a in ATTACH) or b.endswith("_coral") or "_coral[" in b


def commands():
    cmds = []
    for y0, y1, b in BANDS:
        cmds.append((X0, y0, Z0, X1, y1, Z1, b))
    first, second = {}, []
    for p, b in g.over.items():
        if b == g.base(p[1]):
            continue
        if attached(b):
            second.append((p, b))
            first[p] = "water" if wet(b) else "air"
        else:
            first[p] = b
    # greedy merge: runs along x, rectangles over z, boxes over y
    layers = {}
    for (x, y, z), b in first.items():
        layers.setdefault(y, {}).setdefault(z, {})[x] = b
    boxes = {}
    open_boxes = {}
    for y in sorted(layers):
        rects = []
        active = {}
        rows = layers[y]
        for z in sorted(rows):
            row = rows[z]
            runs = []
            xs = sorted(row)
            i = 0
            while i < len(xs):
                j = i
                while j + 1 < len(xs) and xs[j + 1] == xs[j] + 1 and row[xs[j + 1]] == row[xs[i]]:
                    j += 1
                runs.append((xs[i], xs[j], row[xs[i]]))
                i = j + 1
            new_active = {}
            for run in runs:
                if run in active and active[run][1] == z - 1:
                    new_active[run] = (active[run][0], z)
                else:
                    new_active[run] = (z, z)
            for run, (z0, z1) in active.items():
                if run not in new_active or new_active[run][0] != z0:
                    rects.append((run[0], z0, run[1], z1, run[2]))
            active = new_active
        for run, (z0, z1) in active.items():
            rects.append((run[0], z0, run[1], z1, run[2]))
        nxt = {}
        for rc in rects:
            if rc in open_boxes and open_boxes[rc][1] == y - 1:
                nxt[rc] = (open_boxes[rc][0], y)
            else:
                nxt[rc] = (y, y)
        for rc, (ya, yb) in open_boxes.items():
            if rc not in nxt or nxt[rc][0] != ya:
                boxes[(rc, ya)] = yb
        open_boxes = nxt
    for rc, (ya, yb) in open_boxes.items():
        boxes[(rc, ya)] = yb
    out = []
    for c in cmds:
        out.append(c)
    for ((x0, z0, x1, z1, b), ya), yb in sorted(boxes.items(), key=lambda kv: kv[0][1]):
        out.append((x0, ya, z0, x1, yb, z1, b))
    m.cmds = []
    for x0, y0, z0, x1, y1, z1, b in out:
        if (x0, y0, z0) == (x1, y1, z1):
            m.cmds.append(f"setblock {x0} {y0} {z0} {b}")
        else:
            m.fill(x0, y0, z0, x1, y1, z1, b)
    hanging = sorted([s for s in second if "vertical_direction=down" in s[1] or "hanging=true" in s[1] or "chain" in s[1]],
                     key=lambda s: -s[0][1])
    rest = sorted([s for s in second if s not in hanging], key=lambda s: s[0][1])
    for (x, y, z), b in hanging + rest:
        m.cmds.append(f"setblock {x} {y} {z} {b}")
    m.cmds += ENTITIES


def png(path, w, h, pix, k=4):
    pix = [pix[(row // k) * w + col // k] for row in range(h * k) for col in range(w * k)]
    w, h = w * k, h * k
    raw = b"".join(b"\x00" + bytes(c for px in pix[row * w:(row + 1) * w] for c in px) for row in range(h))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


COLORS = {"air": (20, 20, 28), "water": (40, 90, 200), "packed_ice": (150, 180, 230), "blue_ice": (110, 160, 250),
          "dark_prismarine": (40, 90, 80), "prismarine_bricks": (90, 170, 150), "prismarine": (100, 160, 150),
          "spruce_planks": (120, 85, 50), "sea_lantern": (240, 250, 240), "iron_bars": (200, 60, 60), "barrier": (255, 0, 255)}


def color(b):
    if passable(b) and b not in ("air", "water"):
        return (60, 140, 60)
    for k, c in COLORS.items():
        if b.startswith(k):
            return c
    return (110, 110, 110)


def preview(folder):
    os.makedirs(folder, exist_ok=True)
    w, h = X1 - X0 + 1, Z1 - Z0 + 1
    for name, ys in (("grotte", [F0 + 1]), ("salle1", [F1 + 1, F1 + 8]), ("salle2", [F2, F2 + 1, F2 + 8]), ("port", [WL3, F3 + 1]),
                     ("arene", [FI, FI + 1, FI - 4, FI + 16])):
        for y in ys:
            pix = []
            for z in range(Z0, Z1 + 1):
                for x in range(X1, X0 - 1, -1):
                    pix.append(color(g.get(x, y, z)))
            png(os.path.join(folder, f"{name}_y{y}.png"), w, h, pix)
    # side view: first open block from the top at x = 0 and x = AX
    for xs in (0, AX):
        pix = []
        for y in range(Y1, Y0 - 1, -1):
            for z in range(Z0, Z1 + 1):
                pix.append(color(g.get(xs, y, z)))
        png(os.path.join(folder, f"coupe_x{xs}.png"), h, Y1 - Y0 + 1, pix)


# ======================================================================== main

m = maplib.Map()
m.box = (X0, Z0, X1, Z1)
_door = m.door


def _track_door(id, frm, to, room, **kw):
    DOORS.append((tuple(min(a, b) for a, b in zip(frm, to)), tuple(max(a, b) for a, b in zip(frm, to))))
    _door(id, frm, to, room, **kw)


m.door = _track_door

build_grotto()
corridor1()
room1()
corridor2()
room2()
corridor3()
room3()
arena()
corridor4()
skin()
m.start = [0.5, float(F0 + 1), -6.5, 0.0, 0.0]

if __name__ == "__main__":
    args = sys.argv[1:]
    errs = check()
    for e in errs[:60]:
        print("CHECK:", e)
    print(f"{len(errs)} problem(s), {len(g.over)} blocks changed, {len(ENTITIES)} entities")
    if "--preview" in args:
        preview(args[args.index("--preview") + 1])
    if "--check" in args or "--preview" in args:
        sys.exit(1 if errs else 0)
    if errs:
        sys.exit("checks failed: nothing sent")
    commands()
    print(f"{len(m.cmds)} commands")
    maplib.run(m, args[0] if args and not args[0].startswith("--") else "labs", "pecheur")
