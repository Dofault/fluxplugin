#!/usr/bin/env python3
"""Map of the rock dungeon, Les Profondeurs de la Montagne (boss: the Colosse d'Abîme), modelled in memory (gridlib),
checked, then built by console commands with the RPG look of the dungeons.

Natural caves inside a mountain, going down at every step (no mine: no rails, no timber, no straight galleries),
played from south to north (+z):
  - the Gate of the Depths (start at 0, 64, -10): a ravine open to the sky in front of a huge natural arch in the
    cliff, then a first cavern (no fight): stone, andesite and cobblestone, gravel, tuff deposits, hidden lanterns;
  - room 1, the Cavern of Pillars (about 40 x 34, up to 22 high): natural columns of every shape on the sides,
    hanging rocks, ledges, pools, a small waterfall; open middle. Stone-cutters and sandstone throwers;
  - room 2, the Frozen Cavern (floor 60): the rock turns pale (diorite, calcite), then frozen: icicles, frozen falls,
    a frozen pond in a side hollow, snow on the ledges, ice columns on the sides as cover; floor of stone and snow
    (never slippery where one fights). Frost sentinels and stone-cutters;
  - the Crystal Galleries (no fight, going down to 56): geodes in the walls (smooth basalt, calcite, amethyst),
    crystals growing in the cracks and from the ceiling, a rock island splitting the way in two;
  - room 3, the Chasm of the Depths (floor 56): a deepslate plateau and natural bridge over a chasm some 20 deep, a lake
    at its bottom fed by waterfalls, invisible barriers along every edge (the golem throws players up); the granite
    golem (mini-boss), throwers and stone-cutters;
  - the arena of the Colosse (floor 50, combat floor about 40 across: its Cataclysm burns 15 blocks around it, a
    30 x 30 floor would leave no way out of it), deepslate, basalt, tuff and amethyst veins (the boss' violet),
    broken columns at the edge only (cover against its shards), a closed exit opening when it dies.

Usage: gen_roche.py [server] [--check] [--preview DIR]     (labs by default)
"""
import math
import os
import sys

import gridlib
import maplib

F, F2, F3, FA = 63, 60, 56, 50            # floors: room 1, room 2, room 3, arena
X0, X1, Y0, Y1, Z0, Z1 = -44, 44, 28, 104, -22, 244
BANDS = ((28, 47, "deepslate"), (48, 54, "tuff"), (55, 95, "stone"), (96, 98, "dirt"), (99, 99, "grass_block"))
g = gridlib.Model((X0, X1, Y0, Y1, Z0, Z1), BANDS, seed=23)
r = g.r
m = maplib.Map()
m.box = (X0, Z0, X1, Z1)
_door = m.door
OPEN = ("air", "water")


def door(id, frm, to, room):
    g.doors.append((tuple(min(a, b) for a, b in zip(frm, to)), tuple(max(a, b) for a, b in zip(frm, to))))
    _door(id, frm, to, room=room)


# ======================================================================== natural rock painting

PALETTES = {
    "classic": [("stone", 55), ("andesite", 20), ("cobblestone", 10), ("tuff", 10), ("granite", 5)],
    "pale": [("stone", 35), ("diorite", 25), ("calcite", 15), ("andesite", 15), ("tuff", 10)],
    "frozen": [("stone", 30), ("diorite", 18), ("calcite", 14), ("packed_ice", 18), ("snow_block", 10), ("deepslate", 5), ("blue_ice", 5)],
    "crystal": [("calcite", 28), ("tuff", 22), ("smooth_basalt", 18), ("stone", 14), ("diorite", 10), ("deepslate", 8)],
    "deep": [("deepslate", 45), ("cobbled_deepslate", 20), ("tuff", 15), ("basalt[axis=y]", 8), ("stone", 12)],
    "abyss": [("deepslate", 35), ("tuff", 18), ("cobbled_deepslate", 15), ("basalt[axis=y]", 10), ("blackstone", 10), ("smooth_basalt", 12)],
}
NOISES = [gridlib.Noise(s) for s in (301, 302, 303)]


def zone_palette(x, y, z):
    """Palette by depth along the way, blended over a few blocks so no line shows."""
    j = NOISES[2](x * 0.3, y * 0.3, z * 0.3) * 4
    zz = z + j
    if zz < 66:
        return "classic"
    if zz < 72:
        return "pale"
    if zz < 109:
        return "frozen" if y < F2 + 18 + j else "pale"
    if zz < 113:
        return "pale"
    if zz < 137:
        return "crystal"
    if zz < 182:
        return "deep" if y < F3 + 14 + j * 2 else "classic"
    return "abyss"


def paint(x, y, z, palette=None):
    pal = PALETTES[palette or zone_palette(x, y, z)]
    v = (NOISES[0](x * 0.9, y * 1.1, z * 0.9) * 0.75 + NOISES[1](x * 2.3, y * 2.3, z * 2.3) * 0.25 + 1) / 2
    v = min(0.999, max(0.0, v + r.uniform(-0.06, 0.06)))
    tot = sum(w for _, w in pal)
    acc = 0
    for b, w in pal:
        acc += w / tot
        if v < acc:
            return b
    return pal[-1][0]


ROCK = "rock"   # placeholder of natural rock, painted at the end


def carve(cx, cy, cz, rx, ry, rz, clip, seed, rough=0.35):
    g.blob(cx, cy, cz, rx, ry, rz, clip, seed, rough, block="air", where=lambda b: b not in OPEN)


def refill(x0, y0, z0, x1, y1, z1, keep=None):
    """Rock back below a floor (the blobs carve round bottoms)."""
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            for z in range(z0, z1 + 1):
                if g.get(x, y, z) == "air" and (keep is None or not keep(x, y, z)):
                    g.set(x, y, z, ROCK)


def column(cx, cz, y0, y1, r0, r1, seed, mat=ROCK):
    """A natural pillar from y0 to y1, radius r0 at the bottom to r1 at the top, wavy and lumpy."""
    n = gridlib.Noise(seed)
    for y in range(y0, y1 + 1):
        t = (y - y0) / max(1, y1 - y0)
        rad = r0 + (r1 - r0) * t
        rad *= 0.8 + 0.4 * abs(math.sin(t * math.pi * 1.5 + seed))   # waist and bulges
        ox, oz = 0.8 * n(y * 0.3, 0, 0), 0.8 * n(0, y * 0.3, 1)
        for x in range(int(cx - rad - 2), int(cx + rad + 3)):
            for z in range(int(cz - rad - 2), int(cz + rad + 3)):
                d = math.hypot(x - cx - ox, z - cz - oz)
                if d <= rad + 0.6 * n(x * 0.7, y * 0.7, z * 0.7):
                    g.set(x, y, z, mat)


def mound(cx, cz, y, rad, h, seed):
    """A heap of fallen rock on the floor."""
    g.blob(cx, y, cz, rad, h, rad, (cx - rad - 2, y + 1, cz - rad - 2, cx + rad + 2, y + h + 1, cz + rad + 2), seed, 0.4, block=ROCK,
           where=lambda b: b == "air")


def drips(x0, z0, x1, z1, y_from, n, avoid=None, ice=False):
    for _ in range(n):
        x, z = r.randint(x0, x1), r.randint(z0, z1)
        if avoid and avoid(x, z):
            continue
        if g.get(x, y_from, z) != "air":
            continue
        if ice:
            g.stalactite(x, y_from, z, r.randint(2, 5), ["packed_ice", "packed_ice", "ice"], "blue_ice")
        elif r.random() < 0.5:
            g.stalactite(x, y_from, z, r.randint(1, 3), [ROCK, "dripstone_block"])
            top = g.floor_below(x, y_from, z)
            g.dripstone_down(x, y_from, z, r.randint(1, 3))
        else:
            g.dripstone_down(x, y_from, z, r.randint(1, 4))


def stalagmites(x0, z0, x1, z1, y_floor, n, avoid=None):
    for _ in range(n):
        x, z = r.randint(x0, x1), r.randint(z0, z1)
        if avoid and avoid(x, z):
            continue
        if g.get(x, y_floor + 1, z) == "air" and g.get(x, y_floor, z) not in OPEN:
            if r.random() < 0.5:
                g.dripstone_up(x, y_floor + 1, z, r.randint(1, 3))
            else:
                h = r.randint(1, 3)
                for y in range(y_floor + 1, y_floor + 1 + h):
                    g.set(x, y, z, ROCK)
                g.dripstone_up(x, y_floor + 1 + h, z, r.randint(1, 2))


def wall_cells(x0, y0, z0, x1, y1, z1):
    """Solid cells of the box with open air next to them (the visible walls)."""
    out = []
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            for z in range(z0, z1 + 1):
                b = g.get(x, y, z)
                if b in OPEN or gridlib.passable(b):
                    continue
                for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                    if g.get(x + d[0], y + d[1], z + d[2]) == "air":
                        out.append((x, y, z, d))
                        break
    return out


FACING = {(1, 0, 0): "east", (-1, 0, 0): "west", (0, 1, 0): "up", (0, -1, 0): "down", (0, 0, 1): "south", (0, 0, -1): "north"}


def crystals(x0, y0, z0, x1, y1, z1, n, min_y=None):
    """Amethyst growing in cracks: a block in the wall, a cluster on its open face."""
    cells = wall_cells(x0, y0, z0, x1, y1, z1)
    r.shuffle(cells)
    for x, y, z, d in cells[:n]:
        if min_y is not None and y < min_y and d != (0, -1, 0):
            continue
        g.set(x, y, z, "amethyst_block")
        ax, ay, az = x + d[0], y + d[1], z + d[2]
        if g.get(ax, ay, az) == "air":
            g.set(ax, ay, az, f"{r.choice(['amethyst_cluster', 'large_amethyst_bud', 'medium_amethyst_bud'])}[facing={FACING[d]}]")


def lights(points, level=11):
    for x, y, z in points:
        if g.get(x, y, z) == "air":
            g.set(x, y, z, f"light[level={level}]")


def niche_lantern(x, y, z, dx):
    """A lantern set in a small hollow of the wall (x is the wall cell, dx points into the room)."""
    g.set(x, y, z, "air")
    g.set(x, y + 1, z, ROCK)
    g.set(x, y, z, "lantern")


def flat_floor(inside, y, mats=None, palette=None):
    """The fighting floor: rock at y, three free blocks above, inside(x, z)."""
    for x in range(X0, X1 + 1):
        for z in range(Z0, Z1 + 1):
            if inside(x, z):
                g.set(x, y, z, r.choice(mats) if mats else paint(x, y, z, palette))
                for yy in range(y + 1, y + 5):
                    if not gridlib.passable(g.get(x, yy, z)) or g.get(x, yy, z) == ROCK:
                        g.set(x, yy, z, "air")


def gate(z, floor, x0, x1, h, mats, frame):
    """An ancient gate in a narrow passage: a plug of fitted stones between two worked pillars and a lintel."""
    g.box(x0 - 1, floor + 1, z, x0 - 1, floor + h + 1, z, frame)
    g.box(x1 + 1, floor + 1, z, x1 + 1, floor + h + 1, z, frame)
    g.box(x0 - 1, floor + h + 1, z, x1 + 1, floor + h + 1, z, frame)
    g.box(x0, floor + 1, z, x1, floor + h, z, lambda *a: r.choice(mats))


# ======================================================================== the way

def entrance():
    # the ravine, open to the sky
    carve(0, F + 20, -9, 8, 30, 8, (-11, F + 1, -21, 11, Y1, -1), seed=1, rough=0.3)
    g.box(-4, F + 1, -18, 4, F + 6, 0, "air")
    refill(-12, Y0, -22, 12, F, 0)
    # an invisible lid over the ravine: the sky stays in view, the cliffs cannot be climbed out of
    g.box(-12, F + 14, -22, 12, F + 14, 0, "barrier", where=lambda b: b == "air")
    # the arch into the mountain and the first cavern (no fight)
    carve(0, F + 7, 3, 7, 9, 5, (-9, F + 1, -1, 9, F + 15, 8), seed=2)
    carve(0, F + 6, 14, 10, 8, 9, (-13, F + 1, 4, 13, F + 15, 24), seed=3)
    carve(-9, F + 5, 12, 4, 5, 4, (-15, F + 3, 6, -5, F + 12, 18), seed=4)          # side hollows, out of reach
    carve(10, F + 7, 17, 4, 4, 4, (5, F + 5, 11, 16, F + 13, 23), seed=5)
    refill(-16, Y0, -1, 16, F, 25)
    flat_floor(lambda x, z: abs(x) <= 3 + (2 if z > 2 else 0) and -18 <= z <= 23 and g.get(x, F + 2, z) == "air", F,
               palette="classic")
    for x, z in ((-6, 9), (6, 12), (-7, 19), (7, 21), (-5, -6), (5, -12)):
        mound(x, z, F, r.uniform(1.2, 2.2), r.randint(1, 2), seed=10 + x * 7 + z)
    drips(-10, 4, 10, 23, F + 4, 18, avoid=lambda x, z: abs(x) <= 3)
    stalagmites(-11, 4, 11, 23, F, 10, avoid=lambda x, z: abs(x) <= 4)
    for x, z in ((-8, 14), (8, 8), (-7, 22), (6, 20)):
        fl = g.floor_below(x, F + 3, z)
        if fl is not None and fl <= F + 2:
            g.set(x, fl + 1, z, "gravel" if g.get(x, fl, z) not in OPEN else "air")
    for x, y, z in ((-6, F + 5, 6), (6, F + 6, 10), (-8, F + 4, 18), (8, F + 5, 22), (0, F + 9, 15)):
        g.set(x, y, z, "light[level=10]")
    for x, z in ((-4, 1), (4, 1)):
        g.set(x, F + 1, z, "lantern")
    # passage to the cavern of pillars (natural, winding a little)
    for z in range(24, 31):
        ox = round(1.5 * math.sin(z * 0.5))
        carve(ox, F + 3, z, 4.5, 4, 2, (ox - 6, F + 1, z, ox + 6, F + 8, z), seed=20 + z, rough=0.25)
        g.box(ox - 3, F + 1, z, ox + 3, F + 4, z, "air")
    refill(-10, Y0, 24, 10, F, 30)
    g.start = (0, F + 1, -10)
    m.start = [0.5, float(F + 1), -9.5, 0.0, 0.0]


R1C = (0, 47)


def in_r1(x, z):
    return ((x - R1C[0]) / 12.5) ** 2 + ((z - R1C[1]) / 13) ** 2 <= 1


def room1():
    cx, cz = R1C
    carve(cx, F + 9, cz, 21, 14, 17, (-23, F + 1, 31, 23, F + 24, 63), seed=31, rough=0.35)
    for i, (x, z, rr) in enumerate(((-19, 36, 5), (20, 54, 5), (-18, 58, 4), (17, 34, 4))):
        carve(x, F + 8, z, rr, 5, rr, (-27, F + 3, 31, 27, F + 18, 63), seed=40 + i)   # recesses, ledges
    refill(-25, Y0, 31, 25, F, 63)
    # natural pillars on the sides, of all shapes, one fallen block hanging
    for i, (x, z, r0, r1) in enumerate(((-15, 40, 3.5, 2.5), (15, 44, 4.5, 3.5), (-14, 55, 2.5, 4.0), (13, 57, 2.0, 1.6),
                                         (-17, 48, 2.0, 2.4), (18, 39, 2.5, 1.8))):
        top = g.ceiling(x, F + 1, z) or F + 18
        column(x, z, F + 1, top + 1, r0, r1, seed=50 + i)
    for i, (x, z) in enumerate(((-6, 40), (7, 52), (0, 58), (-8, 54))):
        top = g.ceiling(x, F + 10, z)
        if top:
            g.blob(x, top - 1, z, 2.2, 2.5, 2.2, (x - 4, top - 5, z - 4, x + 4, top, z + 4), seed=60 + i, rough=0.5, block=ROCK,
                   where=lambda b: b == "air")
    flat_floor(in_r1, F, palette="classic")
    for x, z in ((-19, 44), (19, 49), (-11, 33), (11, 61), (-20, 52), (20, 41)):
        mound(x, z, F, r.uniform(1.5, 2.8), r.randint(1, 3), seed=70 + x + z)
    # floor variety: gravel and tuff deposits off the middle
    for _ in range(90):
        x, z = r.randint(-20, 20), r.randint(32, 62)
        if not in_r1(x, z) and g.get(x, F, z) not in OPEN and g.get(x, F + 1, z) == "air":
            g.set(x, F, z, r.choice(["gravel", "tuff", "cobblestone", "andesite"]))
    # a pool and a waterfall on the east side, a puddle on the west
    g.waterfall(16, 50, F)
    g.pool(-15, 51, F, 1, rim=ROCK, bed=ROCK)
    drips(-20, 32, 20, 62, F + 12, 60, avoid=lambda x, z: in_r1(x, z) and r.random() < 0.6)
    stalagmites(-21, 32, 21, 62, F, 25, avoid=in_r1)
    crystals(-22, F + 6, 32, 22, F + 20, 62, 10)
    lights([(x, F + 9, z) for x, z in ((-8, 40), (8, 40), (-8, 54), (8, 54), (0, 47))], 12)
    lights([(-18, F + 4, 37), (19, F + 4, 53), (-18, F + 4, 58), (17, F + 4, 36)], 13)
    for x, z in ((-6, 41), (5, 43), (-9, 49), (8, 51), (-3, 56), (9, 57), (-11, 58)):
        m.mob("rochiste_tailleur", x, z, y=F + 1, yaw=180, room="1")
    for x, z in ((-13, 45), (13, 47), (0, 60)):
        m.mob("rochiste_lanceur", x, z, y=F + 1, yaw=180, room="1")
    # the gate, then the cold way down
    g.box(-3, F + 1, 62, 3, F + 6, 65, "air")
    gate(64, F, -3, 3, 5, ["cobblestone", "stone_bricks", "cracked_stone_bricks", "mossy_cobblestone"], "chiseled_stone_bricks")
    door("porte_piliers", (-3, F + 1, 64), (3, F + 5, 64), room="1")
    for z in range(65, 76):
        fz = F - min(3, (z - 65) // 3)
        ox = round(2 * math.sin(z * 0.35))
        carve(ox, fz + 4, z, 5, 4.5, 2, (ox - 7, fz + 1, z, ox + 7, fz + 9, z), seed=80 + z, rough=0.25)
        g.box(ox - 3, fz + 1, z, ox + 3, fz + 5, z, "air")
        g.box(ox - 4, Y0, z, ox + 4, fz, z, ROCK, where=lambda b: b == "air")
        g.box(ox - 3, fz, z, ox + 3, fz, z, lambda x, y, zz: paint(x, y, zz, "pale"))
        if (z - 65) % 3 == 0 and z > 65:
            g.box(ox - 3, fz + 1, z, ox + 3, fz + 1, z, "air")


R2C = (0, 92)


def in_r2(x, z):
    return ((x - R2C[0]) / 10.5) ** 2 + ((z - R2C[1]) / 13.5) ** 2 <= 1


def room2():
    cx, cz = R2C
    carve(cx, F2 + 8, cz, 17, 12, 16, (-20, F2 + 1, 76, 20, F2 + 21, 108), seed=91, rough=0.35)
    carve(-15, F2 + 2, 98, 5, 3, 5, (-22, F2 - 3, 90, -9, F2 + 7, 106), seed=92)   # the hollow of the frozen pond
    carve(15, F2 + 7, 84, 4, 5, 4, (9, F2 + 3, 78, 21, F2 + 15, 92), seed=93)
    refill(-23, Y0, 76, 23, F2, 108, keep=lambda x, y, z: -20 <= x <= -11 and 93 <= z <= 103 and y >= F2 - 1)
    # where the way down from the cavern of pillars comes in
    carve(0, F2 + 3, 77, 4.5, 4, 3, (-6, F2 + 1, 74, 6, F2 + 8, 81), seed=94, rough=0.2)
    g.box(-3, F2 + 1, 74, 3, F2 + 5, 80, "air")
    g.box(-3, F2, 74, 3, F2, 80, lambda x, y, z: paint(x, y, z, "pale"))
    # the frozen pond, sunk one block, in its hollow
    for x in range(-20, -10):
        for z in range(93, 104):
            if g.get(x, F2, z) == "air" or g.get(x, F2 - 1, z) == "air":
                g.set(x, F2 - 1, z, "packed_ice")
                g.set(x, F2 - 2, z, ROCK)
                if g.get(x, F2, z) == "air":
                    g.set(x, F2, z, "ice" if r.random() < 0.0 else "packed_ice")
    flat_floor(in_r2, F2, palette=None)
    # the fighting floor: stone and snow, no ice under the feet
    for x in range(-12, 13):
        for z in range(77, 107):
            if in_r2(x, z):
                g.set(x, F2, z, r.choice(["stone", "snow_block", "snow_block", "andesite", "diorite"]))
    # ice columns as cover on the sides, frozen falls on the walls, icicles
    for x, z in ((-8, 84), (8, 90), (-7, 99), (7, 101)):
        top = g.ceiling(x, F2 + 1, z) or F2 + 14
        column(x, z, F2 + 1, top + 1, 1.3, 1.1, seed=100 + x + z, mat="packed_ice")
        g.set(x, F2 + 1, z + 2, "blue_ice") if g.get(x, F2 + 1, z + 2) == "air" else None
    for x0, z0 in ((-16, 85), (16, 97), (-10, 106)):
        for dz in range(-1, 2):
            for y in range(F2 + 1, F2 + 16):
                for xx in range(x0 - 3, x0 + 4):
                    b = g.get(xx, y, z0 + dz)
                    if b not in OPEN and g.get(xx + (1 if x0 < 0 else -1), y, z0 + dz) == "air":
                        g.set(xx, y, z0 + dz, "blue_ice" if (y + dz) % 3 else "packed_ice")
                        break
    drips(-17, 77, 17, 107, F2 + 10, 70, ice=True)
    # snow on every ledge and the floor's edge
    for (x, y, z, d) in wall_cells(-20, F2, 76, 20, F2 + 18, 108):
        if d == (0, 1, 0) and not in_r2(x, z) and g.get(x, y, z) not in ("packed_ice", "blue_ice", "ice"):
            g.set(x, y, z, "snow_block")
    lights([(x, F2 + 9, z) for x, z in ((-6, 85), (6, 85), (-6, 99), (6, 99), (0, 92))], 11)
    lights([(-14, F2 + 3, 97), (14, F2 + 8, 84), (0, F2 + 3, 77)], 12)
    for x, z in ((-6, 86), (6, 88), (-5, 97), (6, 99), (0, 104)):
        m.mob("rochiste_sentinelle", x, z, y=F2 + 1, yaw=180, room="2")
    for x, z in ((-2, 84), (3, 92), (-4, 101), (2, 104)):
        m.mob("rochiste_tailleur", x, z, y=F2 + 1, yaw=180, room="2")
    g.box(-3, F2 + 1, 106, 3, F2 + 6, 110, "air")
    g.box(-4, Y0, 106, 4, F2, 110, ROCK, where=lambda b: b == "air")
    g.box(-3, F2, 106, 3, F2, 110, lambda x, y, z: paint(x, y, z, "pale"))
    gate(109, F2, -3, 3, 5, ["cobblestone", "stone_bricks", "cracked_stone_bricks", "diorite"], "polished_diorite")
    door("porte_glace", (-3, F2 + 1, 109), (3, F2 + 5, 109), room="2")


def galleries():
    """The crystal galleries: down from 60 to 56, a rock island splitting the way, geodes in the walls."""
    def fz(z):
        return F2 - min(4, max(0, (z - 112) // 5))

    for z in range(110, 138):
        f = fz(z)
        w = 5 if 117 <= z <= 130 else 4.5
        carve(0, f + 4, z, 9 if 117 <= z <= 130 else w, 5, 2, (-12, f + 1, z, 12, f + 10, z), seed=120 + z, rough=0.3)
        g.box(-11, Y0, z, 11, f, z, ROCK, where=lambda b: b == "air")
        if 117 <= z <= 130:
            g.box(-8, f + 1, z, -4, f + 5, z, "air")
            g.box(4, f + 1, z, 8, f + 5, z, "air")
        else:
            g.box(-3, f + 1, z, 3, f + 5, z, "air")
        g.box(-9, f, z, 9, f, z, lambda x, y, zz: paint(x, y, zz, "crystal"), where=lambda b: b == ROCK or b not in OPEN)
        if fz(z - 1) > f:
            g.box(-9, f + 1, z, 9, f + 1, z, "air")
    # the island between both ways (a geode cut open)
    column(0, 124, fz(124) + 1, F2 + 10, 3.2, 2.4, seed=140)
    for y in range(fz(124) + 2, fz(124) + 6):
        for z in (121, 127):
            g.set(0, y, z, "amethyst_block")
    # geodes in the walls: smooth basalt shell, calcite, amethyst lining, crystals
    for (gx, gz, side) in ((-11, 114, -1), (11, 121, 1), (-12, 129, -1), (11, 134, 1)):
        f = fz(gz)
        for x in range(gx - 4, gx + 5):
            for y in range(f, f + 8):
                for z in range(gz - 4, gz + 5):
                    d = math.sqrt((x - gx) ** 2 + ((y - f - 3.5) * 1.2) ** 2 + (z - gz) ** 2)
                    if d <= 2.2 and side * (x - gx) <= 1:
                        g.set(x, y, z, "air")
                    elif d <= 3.0:
                        if g.get(x, y, z) not in OPEN:
                            g.set(x, y, z, "amethyst_block")
                    elif d <= 3.8:
                        if g.get(x, y, z) not in OPEN:
                            g.set(x, y, z, "calcite")
                    elif d <= 4.6:
                        if g.get(x, y, z) not in OPEN:
                            g.set(x, y, z, "smooth_basalt")
        crystals(gx - 3, f, gz - 3, gx + 3, f + 7, gz + 3, 14)
        g.set(gx, f + 1, gz, "light[level=9]") if g.get(gx, f + 1, gz) == "air" else None
    crystals(-12, F2 - 4, 110, 12, F2 + 10, 137, 40)
    drips(-10, 110, 10, 137, F2 + 4, 25, avoid=lambda x, z: abs(x) <= 3)
    lights([(x, fz(z) + 5, z) for x, z in ((0, 112), (-6, 120), (6, 126), (0, 135))], 9)


R3C = (0, 158)


def on_plateau(x, z):
    """The fighting ground over the chasm: a round plateau and the natural bridges to it."""
    return (math.hypot(x - R3C[0], (z - R3C[1]) * 0.95) <= 12.5) or (abs(x) <= 4 and 138 <= z <= 178)


def room3():
    cx, cz = R3C
    carve(cx, F3 + 6, cz, 24, 22, 20, (-28, F3 - 22, 139, 28, F3 + 24, 177), seed=161, rough=0.3)
    carve(0, F3 + 3, 139, 5, 4, 2, (-6, F3 + 1, 137, 6, F3 + 8, 141), seed=162)
    # the plateau and the bridges: a huge natural pillar under them, down to the lake
    n = gridlib.Noise(163)
    for x in range(-14, 15):
        for z in range(138, 179):
            if on_plateau(x, z):
                bottom = F3 - 21 + int(4 * n(x * 0.3, 0, z * 0.3)) if math.hypot(x, z - cz) <= 8 else F3 - 4 - int(
                    3 * (1 + n(x * 0.5, 1, z * 0.5)))
                for y in range(bottom, F3 + 1):
                    g.set(x, y, z, ROCK)
    flat_floor(on_plateau, F3, palette="deep")
    for x in range(-14, 15):
        for z in range(138, 179):
            if on_plateau(x, z):
                edge = any(not on_plateau(x + a, z + b) for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                g.set(x, F3, z, "polished_deepslate" if edge else r.choice(["deepslate", "cobbled_deepslate", "tuff", "deepslate"]))
    # the lake at the bottom of the chasm, waterfalls from the walls into it
    for x in range(-26, 27):
        for z in range(140, 177):
            if g.get(x, F3 - 18, z) == "air":
                for y in range(F3 - 22, F3 - 17):
                    if g.get(x, y, z) == "air":
                        g.set(x, y, z, "water")
    for x, z in ((-22, 150), (21, 166), (-19, 170)):
        fl = g.floor_below(x, F3 + 4, z)
        top = g.ceiling(x, F3 + 4, z)
        if top:
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if g.get(x + dx, top, z + dz) == "air":
                    g.set(x + dx, top, z + dz, ROCK)
            g.set(x, top, z, "water")
            g.falls.add((x, top, z))
    # barriers along every edge of the plateau and the bridges, up to the vault (the golem throws players up)
    for x in range(-16, 17):
        for z in range(138, 179):
            if on_plateau(x, z):
                continue
            if any(on_plateau(x + a, z + b) for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, -1), (1, -1), (-1, 1))):
                for y in range(F3 + 1, F3 + 26):
                    if g.get(x, y, z) in ("air", "water"):
                        g.set(x, y, z, "barrier")
    # ledges, hanging rocks, deepslate crystals, stalactites over the void
    for i, (x, z) in enumerate(((-20, 146), (20, 150), (-18, 168), (19, 172))):
        g.blob(x, F3 - 2, z, 3, 1.5, 4, (x - 5, F3 - 4, z - 6, x + 5, F3, z + 6), seed=170 + i, block=ROCK, where=lambda b: b == "air")
    drips(-24, 140, 24, 176, F3 + 10, 80, avoid=lambda x, z: on_plateau(x, z) and r.random() < 0.7)
    crystals(-27, F3 - 20, 139, 27, F3 + 20, 177, 45)
    lights([(x, F3 + 8, z) for x, z in ((-6, 152), (6, 152), (-6, 164), (6, 164), (0, 145), (0, 172))], 11)
    lights([(x, F3 - 14, z) for x, z in ((-16, 150), (16, 160), (-12, 172), (14, 145))], 12)
    m.mob("rochiste_golem", 0, 162, y=F3 + 1, yaw=180, room="3")
    for x, z in ((-8, 166), (8, 166), (0, 170)):
        m.mob("rochiste_lanceur", x, z, y=F3 + 1, yaw=180, room="3")
    for x, z in ((-5, 152), (5, 152)):
        m.mob("rochiste_tailleur", x, z, y=F3 + 1, yaw=180, room="3")
    # the gate into the old way down
    g.box(-3, F3 + 1, 177, 3, F3 + 6, 179, "air")
    gate(178, F3, -3, 3, 5, ["cobbled_deepslate", "deepslate_bricks", "cracked_deepslate_bricks", "deepslate_tiles"],
         "polished_deepslate")
    door("porte_gouffre", (-3, F3 + 1, 178), (3, F3 + 5, 178), room="3")
    for z in range(179, 193):
        f = F3 - min(6, (z - 179) // 2)
        carve(0, f + 4, z, 5, 4.5, 2, (-7, f + 1, z, 7, f + 9, z), seed=180 + z, rough=0.25)
        g.box(-3, f + 1, z, 3, f + 5, z, "air")
        g.box(-6, Y0, z, 6, f, z, ROCK, where=lambda b: b == "air")
        g.box(-3, f, z, 3, f, z, lambda x, y, zz: r.choice(["deepslate_tiles", "cobbled_deepslate", "deepslate"]))
        if (z - 179) % 2 == 0 and z > 179:
            g.box(-3, f + 1, z, 3, f + 1, z, "air")


AC, AR = (0, 214), 20


def in_arena(x, z):
    return math.hypot(x - AC[0], z - AC[1]) <= AR - 0.5


def arena():
    cx, cz = AC
    carve(cx, FA + 11, cz, AR + 3, 16, AR + 3, (-25, FA + 1, 193, 25, FA + 30, 235), seed=201, rough=0.25)
    refill(-26, Y0, 193, 26, FA, 237)
    flat_floor(in_arena, FA, palette="abyss")
    n = gridlib.Noise(202)
    for x in range(-20, 21):
        for z in range(194, 235):
            if in_arena(x, z):
                v = n(x * 0.35, 0, z * 0.35)
                g.set(x, FA, z, "stone" if v > 0.45 else "andesite" if v > 0.2 else "tuff" if v > -0.15 else
                      "deepslate" if v > -0.5 else "cobblestone")
    # walls: the cave closes round the floor (no ledge reachable), amethyst veins (the boss' violet)
    for x in range(-25, 26):
        for z in range(193, 237):
            if not in_arena(x, z) and g.get(x, FA + 1, z) == "air" and math.hypot(x, z - cz) > AR + 0.5:
                g.box(x, FA + 1, z, x, FA + 3, z, ROCK, where=lambda b: b == "air")
    for k in range(9):
        a0, y0 = r.uniform(0, 6.283), FA + r.randint(4, 14)
        for s in range(-6, 7):
            a = a0 + s * 0.035
            y = y0 + round(s * 0.6 + 1.5 * math.sin(s))
            rad = AR + 1
            while rad < AR + 8 and g.get(round(cx + rad * math.cos(a)), y, round(cz + rad * math.sin(a))) == "air":
                rad += 1
            x, z = round(cx + rad * math.cos(a)), round(cz + rad * math.sin(a))
            if g.get(x, y, z) not in OPEN:
                g.set(x, y, z, "amethyst_block")
    crystals(-25, FA + 4, 193, 25, FA + 28, 237, 60)
    # broken columns near the wall (cover against the shards), never inside 15 blocks of the middle
    for i, a in enumerate((20, 70, 110, 160, 200, 250, 290, 340)):
        x, z = round(cx + 18 * math.cos(math.radians(a))), round(cz + 18 * math.sin(math.radians(a)))
        h = r.randint(3, 7)
        column(x, z, FA + 1, FA + h, 1.6, 1.0, seed=210 + i, mat="polished_basalt[axis=y]" if i % 2 else ROCK)
        g.set(x, FA + h + 1, z, "cobbled_deepslate_slab") if g.get(x, FA + h + 1, z) == "air" else None
    drips(-22, 195, 22, 235, FA + 18, 60, avoid=lambda x, z: math.hypot(x, z - cz) < 9)
    lights([(round(cx + (AR - 1) * math.cos(math.radians(a))), FA + 2, round(cz + (AR - 1) * math.sin(math.radians(a))))
            for a in range(0, 360, 24)], 11)
    lights([(round(cx + 9 * math.cos(math.radians(a))), FA + 14, round(cz + 9 * math.sin(math.radians(a)))) for a in range(0, 360, 60)], 10)
    m.mob("rochiste_colosse", 0, 224, y=FA + 1, yaw=180, room="4")
    m.objectives = ["rochiste_colosse"]
    # the closed way out (north), opened when the Colosse dies
    g.box(-2, FA + 1, 234, 2, FA + 5, 241, "air")
    g.box(-3, Y0, 234, 3, FA, 241, ROCK, where=lambda b: b == "air")
    gate(236, FA, -2, 2, 4, ["cobbled_deepslate", "deepslate_bricks", "cracked_deepslate_bricks"], "polished_deepslate")
    door("porte_sortie", (-2, FA + 1, 236), (2, FA + 4, 236), room="4")
    g.set(0, FA + 3, 240, "lantern")


# ======================================================================== paint, checks, main

def paint_all():
    """Every placeholder rock and every visible face of the base rock gets its palette."""
    for p, b in list(g.over.items()):
        if b == ROCK:
            g.set(*p, paint(*p))
    for p, b in list(g.over.items()):
        if b not in OPEN and not b.startswith("light"):
            continue
        x, y, z = p
        for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            q = (x + d[0], y + d[1], z + d[2])
            if g.inside(*q) and q not in g.over and q[1] <= 95:
                g.set(*q, paint(*q))
    for p, b in list(g.over.items()):
        if gridlib.gravity(b) and gridlib.passable(g.get(p[0], p[1] - 1, p[2])):
            g.set(*p, "stone")


def zone_of(x, y, z):
    if z <= 64:
        return 1
    if z <= 109:
        return 2
    if z <= 178:
        return 3
    if z <= 236:
        return 4
    return 5


def check():
    errors = g.check_basic()
    for k in range(len(g.doors) + 1):
        seen = g.reach(g.start, set(range(k)))
        top = max(zone_of(*p) for p in seen)
        if top > k + 1:
            bad = next(p for p in seen if zone_of(*p) > k + 1)
            errors.append(f"doors open {k}: zone {top} reachable, e.g. {bad}")
        if top < min(k + 1, 5):
            errors.append(f"doors open {k}: zone {k + 1} not reachable (max {top})")
    # nothing of the decor inside 15 blocks of the arena's middle, the floor flat and free
    for x in range(-20, 21):
        for z in range(194, 235):
            if in_arena(x, z):
                if g.get(x, FA, z) in OPEN or not all(g.get(x, y, z) == "air" or g.get(x, y, z).startswith("light")
                                                      for y in (FA + 1, FA + 2, FA + 3)):
                    if math.hypot(x, z - AC[1]) < 15:
                        errors.append(f"arena floor not free at {x} {z}")
                        break
    for (x, z) in [(x, z) for x in range(-20, 21) for z in range(194, 235) if in_arena(x, z)]:
        if g.get(x, FA, z) in OPEN:
            errors.append(f"hole in the arena floor at {x} {z}")
            break
    return errors


entrance()
room1()
room2()
galleries()
room3()
arena()
paint_all()
m.textures = "rpg"
m.ybox = (Y0, Y1)


# Troop roles (2026-11-13): about twice the monsters of the first version, half of them with a role (gelées that
# leap and split, fast pests, brutes, spitters, snipers, kamikazes, supports), placed next to those above.
REINFORCE = {
    "1": ['rochiste_gelee', 'rochiste_gelee', 'rochiste_gelee', 'rochiste_galopin', 'rochiste_galopin', 'rochiste_galopin', 'rochiste_frondeur', 'rochiste_frondeur', 'rochiste_brise_roc', 'rochiste_brise_roc'],
    "2": ['rochiste_frondeur', 'rochiste_frondeur', 'rochiste_gelee', 'rochiste_gelee', 'rochiste_galopin', 'rochiste_galopin', 'rochiste_galopin', 'rochiste_brise_roc', 'rochiste_brise_roc'],
    "3": ['rochiste_gelee', 'rochiste_gelee', 'rochiste_galopin', 'rochiste_galopin', 'rochiste_frondeur', 'rochiste_frondeur', 'rochiste_brise_roc'],
}


# Room for the monsters (2026-11-14: twice the monsters of the first version in the same caves). The caves are made
# larger by mirroring a slice of them (gridlib.stretch; coordinates as written above): every cave south of the arena
# gets 16 blocks wider (the cut runs the whole way down to the corridor before the arena, so no edge of the chasm is
# left open), the Cavern of Pillars and the plateau of the Chasm 10 blocks longer, the Frozen Cavern 18. The arena of the Colosse is untouched.
STRETCH = [
    {"axis": "z", "at": 52, "n": 5, "mode": "mirror"},                                # cavern of pillars
    {"axis": "z", "at": 96, "n": 9, "mode": "mirror"},                                # frozen cavern
    {"axis": "z", "at": 160, "n": 5, "mode": "mirror"},                               # plateau of the chasm
    {"axis": "x", "at": 10, "n": 4, "mode": "mirror", "span": (Z0, 192)},
    {"axis": "x", "at": -10, "n": 4, "dir": -1, "mode": "mirror", "span": (Z0, 192)},
]


if __name__ == "__main__":
    args = sys.argv[1:]
    maplib.reinforce(m, g, REINFORCE, seed=101)
    errs = check()
    for e in errs[:50]:
        print("CHECK:", e)
    print(f"{len(errs)} problem(s), {len(g.over)} blocks changed, {len(g.entities)} entities")
    if not errs:
        maplib.widen(g, m, STRETCH, "roche", args[args.index("--preview") + 1] if "--preview" in args else None, apart={"1": 1.4, "2": 1.6, "3": 1.5})
    if "--preview" in args:
        out = args[args.index("--preview") + 1]
        for y in (F + 1, F2 + 1, F3 + 1, FA + 1, F3 - 18):
            g.png_slice(os.path.join(out, f"roche_y{y}.png"), y=y, k=3)
        g.png_slice(os.path.join(out, "roche_coupe_x0.png"), x=0, k=3)
        g.png_slice(os.path.join(out, "roche_coupe_x12.png"), x=12, k=3)
    if "--check" in args or "--preview" in args:
        sys.exit(1 if errs else 0)
    if errs:
        sys.exit("checks failed: nothing sent")
    if "--edits" in args:
        maplib.write_edits(m, args[0] if args and not args[0].startswith("--") else "labs", "roche")
        sys.exit(0)
    g.commands(m)
    print(f"{len(m.cmds)} commands")
    maplib.run(m, args[0] if args and not args[0].startswith("--") else "labs", "roche")
