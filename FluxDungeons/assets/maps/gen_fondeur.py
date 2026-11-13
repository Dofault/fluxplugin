#!/usr/bin/env python3
"""Map of the founder's dungeon, La Forge du Volcan (boss: the Fondeur, model golem_fonderie), modelled in memory
(gridlib), checked, then built by console commands with the RPG look of the dungeons.

An old forge complex dug into a huge volcano, played from south to north (+z), floor at y 63 unless said:
  - outside: an ash valley between cliffs, cooled flows, magma streaks and lava falls into contained pools, the
    volcano's face with its chimneys; the monumental entrance: a blackstone arch between basalt pillars (one broken
    and leaning), iron bands, chains, half buried under rubble;
  - room 1, the Threshold of Ashes: a hall with floors at several heights, blackstone flagstones, glowing cracks of
    magma under copper grates, two statues of smiths, broken pillars, carved inscriptions, lanterns on chains,
    braziers, shroomlight behind bars; first fight. Door: an iron grille in a built wall;
  - room 2, the Fusion Galleries: a deep cavity crossed by a straight bridge 9 wide on blackstone piers (railed),
    lava lakes far below, lava conduits crossing the cavity in barred troughs, lava falls,
    monumental chains, an old crane;
  - room 3, the Abandoned Foundry: a vaulted hall of deepslate bricks with four works: fusion (blast furnaces, crucible
    cauldrons of lava, a chimney), forge (anvils, smithing tables, unfinished blades), cooling (water basins, steam),
    storage (barrels, chests, iron and copper stock); side catwalks out of reach, overhead cranes; the cast-iron colossus (elite);
  - room 4, the Heart of the Volcano: a gigantic cavity over a gulf of lava; a viewpoint balcony over it, then a straight suspended
    bridge 9 wide on chains to the last door, basalt columns rising from the lava, falls, a sunk old arch; the
    rumble of what waits behind the last door;
  - room 5, the Fondeur's Arena: a round, irregular arena (floor about 34 across) under a dome, four pillars of their
    own identity at 8 blocks from the middle (its ultimate: a pillar between it and a player shelters him), a ring of
    lava conduits behind a low wall, braziers, the boss in the middle.
Ambience (sounds, smoke, embers) placed near each feature (map.ambience).

Usage: gen_fondeur.py [server] [--check] [--preview DIR]     (labs by default)
"""
import math
import os
import sys

import gridlib
import maplib

F = 63
X0, X1, Y0, Y1, Z0, Z1 = -46, 46, 40, 140, -52, 304
BANDS = ((40, 140, "deepslate"),)
DOORS_Z = (37, 113, 177, 245)
BW = 4                                    # half width of the bridges over the lava (9 blocks: room to fight)
CZ, AR = 274, 17                          # arena middle and radius of its floor
PILLARS = [(8, CZ + 8), (-8, CZ + 8), (8, CZ - 8), (-8, CZ - 8)]
g = gridlib.Model((X0, X1, Y0, Y1, Z0, Z1), BANDS, seed=3131)
r = g.r
m = maplib.Map()
m.box = (X0, Z0, X1, Z1)
_door = m.door
ROCK = "rock"
LAVA_FALLS = set()                         # lava sources of the falls (they flow out on purpose)
LAVA = "lava"
NOISES = [gridlib.Noise(s) for s in (411, 412, 413)]
PALETTES = {
    "ash": [("smooth_basalt", 20), ("tuff", 18), ("basalt[axis=y]", 18), ("blackstone", 16), ("cobbled_deepslate", 14), ("deepslate", 14)],
    "volcanic": [("deepslate", 28), ("basalt[axis=y]", 20), ("blackstone", 18), ("smooth_basalt", 14), ("tuff", 12), ("magma_block", 4),
                 ("cobbled_deepslate", 6)],
    "deep": [("blackstone", 28), ("basalt[axis=y]", 24), ("smooth_basalt", 14), ("magma_block", 7), ("cobbled_deepslate", 12), ("deepslate", 10),
             ("gilded_blackstone", 2)],
}
BRICKS = [("polished_blackstone_bricks", 6), ("cracked_polished_blackstone_bricks", 2), ("polished_blackstone", 1)]
DEEPB = [("deepslate_bricks", 6), ("cracked_deepslate_bricks", 2), ("deepslate_tiles", 2), ("polished_deepslate", 1)]


def door(id, frm, to, room):
    g.doors.append((tuple(min(a, b) for a, b in zip(frm, to)), tuple(max(a, b) for a, b in zip(frm, to))))
    _door(id, frm, to, room=room)


def zone_of(x, y, z):
    for i, lim in enumerate(DOORS_Z):
        if z <= lim:
            return i + 1
    return 5


def paint(x, y, z):
    pal = PALETTES["ash" if z < 4 else "volcanic" if z < 178 else "deep"]
    v = (NOISES[0](x * 0.8, y * 1.0, z * 0.8) * 0.75 + NOISES[1](x * 2.1, y * 2.1, z * 2.1) * 0.25 + 1) / 2
    v = min(0.999, max(0.0, v + r.uniform(-0.06, 0.06)))
    tot = sum(w for _, w in pal)
    acc = 0
    for b, w in pal:
        acc += w / tot
        if v < acc:
            return b
    return pal[-1][0]


def carve(cx, cy, cz, rx, ry, rz, seed, rough=0.35, clip=None):
    clip = clip or (X0 + 2, Y0 + 2, Z0, X1 - 2, Y1 - 6, Z1 - 2)
    g.blob(cx, cy, cz, rx, ry, rz, clip, seed, rough, block="air", where=lambda b: b not in ("air", LAVA))


def solid_floor(x0, z0, x1, z1, y, mats, inside=None):
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if inside and not inside(x, z):
                continue
            g.set(x, y, z, g.pick(mats) if isinstance(mats, list) else mats)
            for yy in range(y - 3, y):
                if g.get(x, yy, z) == "air":
                    g.set(x, yy, z, ROCK)


def light(x, y, z, level=11):
    if g.get(x, y, z) == "air":
        g.set(x, y, z, f"light[level={level}]")


def chain(x, y_top, z, length, end=None):
    for k in range(length):
        if g.get(x, y_top - k, z) != "air":
            return
        g.set(x, y_top - k, z, "iron_chain[axis=y]")
    if end and g.get(x, y_top - length, z) == "air":
        g.set(x, y_top - length, z, end)


def ceiling_chain(x, y, z, length, end=None):
    top = g.ceiling(x, y, z, 50)
    if top is not None:
        chain(x, top - 1, z, length, end)


def pool(cx, cz, y, rad, rim=BRICKS):
    """A pool of lava sources at y, rimmed at y and with rock under it."""
    for x in range(int(cx - rad - 1), int(cx + rad + 2)):
        for z in range(int(cz - rad - 1), int(cz + rad + 2)):
            d = math.hypot(x - cx, z - cz)
            if d <= rad:
                g.set(x, y, z, LAVA)
                g.set(x, y - 1, z, "magma_block")
            elif d <= rad + 1.2 and g.get(x, y, z) in ("air",):
                g.set(x, y, z, g.pick(rim))
                g.set(x, y - 1, z, ROCK) if g.get(x, y - 1, z) == "air" else None


def lavafall(x, z, y_top, y_pool, facing, with_pool=True):
    """A lava fall down a wall: a source in a notch at the top, the flow falls in front, into a pool at y_pool."""
    dx, dz = facing
    g.set(x - dx, y_top, z - dz, LAVA)                     # the source, in the wall
    for side in ((dz, dx), (-dz, -dx)):
        g.set(x - dx + side[0], y_top, z - dz + side[1], ROCK)
    g.set(x - dx, y_top - 1, z - dz, ROCK)
    g.set(x - dx * 2, y_top, z - dz * 2, ROCK)
    LAVA_FALLS.add((x - dx, y_top, z - dz))
    m.sound(x, (y_top + y_pool) // 2, z, [("block.lava.ambient", 0.8, 0.8), ("block.lava.pop", 0.5, 0.7)], radius=14, every=(40, 100))
    if with_pool:
        pool(x, z, y_pool, 2.2)


def conduit(x0, x1, z, y):
    """A lava conduit across a cavity along x: a blackstone trough with barred sides, iron bands, lava inside."""
    for x in range(x0, x1 + 1):
        g.set(x, y - 1, z, "polished_blackstone_bricks")
        g.set(x, y - 1, z - 1, "polished_blackstone_bricks")
        g.set(x, y - 1, z + 1, "polished_blackstone_bricks")
        g.set(x, y, z, LAVA)
        band = x % 5 == 0
        for s in (-1, 1):
            g.set(x, y, z + s, "iron_block" if band else "iron_bars[east=true,west=true]")
        if band:
            g.set(x, y - 2, z, "iron_block")
    g.set(x0 - 1, y, z, ROCK)
    g.set(x1 + 1, y, z, ROCK)


def statue(x, z, y, facing):
    """An old smith carved in stone, hammer on the shoulder."""
    fx, fz = facing
    sx, sz = fz, fx                                         # its side axis
    mat = "polished_basalt[axis=y]"
    g.box(x - 1, y, z - 1, x + 1, y, z + 1, "chiseled_polished_blackstone")
    g.box(x - 1, y + 1, z - 1, x + 1, y + 1, z + 1, "polished_blackstone")
    for k in (-1, 1):
        g.box(x + sx * k, y + 2, z + sz * k, x + sx * k, y + 4, z + sz * k, mat)
    g.box(x - abs(sx), y + 5, z - abs(sz), x + abs(sx), y + 7, z + abs(sz), "polished_blackstone")
    g.set(x, y + 8, z, "chiseled_polished_blackstone")
    g.set(x + fx, y + 8, z + fz, "polished_blackstone_button[face=wall,facing=" + ("south" if fz > 0 else "north" if fz < 0 else "east" if fx > 0 else "west") + "]")
    g.set(x + sx * 2, y + 6, z + sz * 2, "polished_blackstone_wall")
    g.set(x + sx * 2, y + 7, z + sz * 2, "polished_blackstone_wall")
    g.set(x + sx * 2, y + 8, z + sz * 2, "iron_block")
    g.set(x + sx * 3, y + 8, z + sz * 3, "iron_block")


def pillar(x, z, y0, y1, mats, rad=1, broken=None):
    for y in range(y0, y1 + 1):
        if broken and y > broken:
            break
        for dx in range(-rad, rad + 1):
            for dz in range(-rad, rad + 1):
                if rad > 1 and abs(dx) == rad and abs(dz) == rad:
                    continue
                g.set(x + dx, y, z + dz, mats(y) if callable(mats) else g.pick(mats))


def inscription(x0, x1, y, z):
    """A carved band on a wall face (z): chiseled blocks and runes."""
    for x in range(x0, x1 + 1):
        if g.get(x, y, z) not in ("air",):
            g.set(x, y, z, "chiseled_polished_blackstone" if x % 3 == 0 else "chiseled_deepslate" if x % 3 == 1 else "polished_blackstone")


def brazier(x, y, z):
    g.set(x, y, z, "polished_blackstone_wall")
    g.set(x, y + 1, z, "campfire[lit=true]")
    m.sound(x, y + 1, z, [("block.campfire.crackle", 0.7, 1.0)], radius=8, every=(40, 90),
            particle={"type": "SMALL_FLAME", "count": 2, "spread": (0.2, 0.2, 0.2), "speed": 0.01, "every": 10})


def rail_line(x0, z0, x1, z1, y):
    for x in range(min(x0, x1), max(x0, x1) + 1):
        for z in range(min(z0, z1), max(z0, z1) + 1):
            if g.get(x, y, z) == "air" and not gridlib.passable(g.get(x, y - 1, z)):
                g.set(x, y, z, "rail[shape=north_south]" if x0 == x1 else "rail[shape=east_west]")


def guard_edges(x0, z0, x1, z1, y, mat="iron_bars", barrier=True):
    """Fences round every walkable cell at y whose neighbour falls away (nobody walks off into the lava)."""
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if gridlib.passable(g.get(x, y, z)) or not gridlib.passable(g.get(x, y + 1, z)):
                continue
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, nz = x + dx, z + dz
                if not gridlib.passable(g.get(nx, y + 1, nz)):
                    continue                                    # a step up (stairs), not a drop
                if g.get(nx, y, nz) == "air" and g.get(nx, y - 1, nz) == "air":
                    g.set(nx, y + 1, nz, mat)
                    if barrier:
                        for yy in range(y + 2, y + 4):
                            if g.get(nx, yy, nz) == "air":
                                g.set(nx, yy, nz, "barrier")


# ======================================================================== the volcano, outside

def volcano():
    """The mass is solid; above its top line it is sky. The valley in front, the mountain and the cone behind."""
    for x in range(X0, X1 + 1):
        for z in range(Z0, Z1 + 1):
            n = NOISES[2](x * 0.07, 0, z * 0.07)
            if z < -2 and abs(x) <= 30:
                side = max(0, abs(x) - 9 - 3 * n)
                top = F + int(min(60, side ** 1.25 * 0.9))
                if abs(x) <= 9 + 3 * n:
                    top = F + max(0, int(1.4 * n + 0.5))
                top = max(top, F)
            else:
                cone = 135 - 0.32 * math.hypot(x * 1.3, z - 150) + 6 * n
                top = int(max(F + 52 + 4 * n, min(Y1 - 2, cone)))
            for y in range(top + 1, Y1 + 1):
                g.set(x, y, z, "air")
            g.set(x, top, z, ROCK)
    # the crater lip and chimneys on the cone
    for (x, z) in ((-14, 120), (16, 160), (-6, 200)):
        t = next((y for y in range(Y1, Y0, -1) if g.get(x, y, z) != "air"), F)
        g.box(x - 1, t + 1, z - 1, x + 1, min(Y1, t + 6), z + 1, lambda a, b, c: g.pick([("basalt[axis=y]", 3), ("blackstone", 2)]))
        g.set(x, min(Y1, t + 6), z, "magma_block")
    # magma streaks down the valley's cliffs (static, no flow)
    for k in range(10):
        x = r.choice([-1, 1]) * r.randint(12, 26)
        z = r.randint(-46, 0)
        for y in range(Y1, F, -1):
            if g.get(x, y, z) != "air" and g.get(x - (1 if x > 0 else -1), y, z) == "air":
                for yy in range(y, max(F, y - r.randint(6, 16)), -1):
                    if g.get(x, yy, z) != "air":
                        g.set(x, yy, z, "magma_block" if r.random() < 0.8 else "blackstone")
                break


def outside():
    for x in range(-30, 31):
        for y in range(F + 1, Y1 + 1):
            for z in (Z0 + 1,):
                if gridlib.passable(g.get(x, y, z)):
                    g.set(x, y, z, "barrier")
    for z in range(Z0 + 1, -2):
        for x in (-13, 13):
            for y in range(F + 1, Y1 + 1):
                if gridlib.passable(g.get(x, y, z)) and g.get(x, y, z) != "barrier":
                    g.set(x, y, z, "barrier")
    # ash valley floor: ash, cooled flows, cracks of magma (in narrow lines off the way), rubble
    for x in range(-12, 13):
        for z in range(Z0, 4):
            if g.get(x, F + 1, z) == "air":
                g.set(x, F, z, g.pick([("tuff", 3), ("smooth_basalt", 3), ("basalt[axis=y]", 2), ("blackstone", 2), ("gravel", 1), ("coarse_dirt", 1)]))
    for z in range(-46, -2):
        x = round(-6 + 2 * math.sin(z * 0.2))
        if r.random() < 0.7:
            g.set(x, F, z, "magma_block")
        x = round(7 + 2 * math.sin(z * 0.17 + 1))
        if r.random() < 0.7:
            g.set(x, F, z, "magma_block")
    for k, (x, z) in enumerate(((-10, -30), (10, -20), (-11, -10), (11, -38))):
        g.blob(x, F + 1, z, 2.5, 2, 2.5, (x - 4, F + 1, z - 4, x + 4, F + 4, z + 4), 700 + k, 0.5, block=ROCK, where=lambda b: b == "air")
    for (x, z, top) in ((-16, -26, F + 26), (17, -14, F + 30)):
        lavafall(x, z, top, F, (1 if x < 0 else -1, 0))
    g.start = (0, F + 1, -46)
    m.start = [0.5, float(F + 1), -45.5, 0.0, 0.0]
    m.sound(0, F + 8, -30, [("ambient.basalt_deltas.mood", 0.4, 0.7), ("block.lava.ambient", 0.4, 0.6)], radius=24, every=(120, 260),
            particle={"type": "WHITE_ASH", "count": 8, "spread": (10, 4, 10), "speed": 0, "every": 20})
    m.sound(0, F + 20, 2, [("ambient.basalt_deltas.additions", 0.5, 0.6), ("entity.blaze.burn", 0.2, 0.5)], radius=30, every=(160, 300),
            particle={"type": "CAMPFIRE_SIGNAL_SMOKE", "count": 1, "spread": (6, 2, 1), "speed": 0.02, "every": 40})


def entrance():
    """The monumental arch into the volcano, half buried, one pillar broken and leaning."""
    z0, z1 = -2, 9
    for z in range(z0, z1 + 1):
        for x in range(-5, 6):
            h = 11 - int(round(0.12 * x * x))
            for y in range(F + 1, F + h + 1):
                g.set(x, y, z, "air")
            g.set(x, F, z, g.pick(BRICKS))
    # arch frame protruding from the cliff, iron bands, keystone
    for x in range(-7, 8):
        h = 12 - int(round(0.12 * x * x))
        for z in (-4, -3):
            for y in range(F + 1, F + 15):
                if y > F + h or abs(x) >= 6:
                    g.set(x, y, z, g.pick(BRICKS) if y != F + h + 1 else "chiseled_polished_blackstone")
    for x in range(-5, 6):
        g.set(x, F + 13 - int(round(0.12 * x * x)), -5, "polished_blackstone_stairs[facing=north,half=top]")
    g.box(-1, F + 13, -5, 1, F + 15, -4, "gilded_blackstone")
    for y in (F + 4, F + 9):
        for x in (-7, -6, 6, 7):
            g.set(x, y, -5, "iron_block")
    # two basalt pillars, the east one broken and leaning
    pillar(-10, -5, F, F + 17, lambda y: "polished_basalt[axis=y]" if y % 6 else "iron_block", rad=1)
    for y in range(F, F + 9):
        for dx in range(-1, 2):
            for dz in range(-1, 2):
                g.set(10 + dx, y, -5 + dz, "polished_basalt[axis=y]" if y % 6 else "iron_block")
    for k in range(4):
        g.box(11 + k, F + 9 + k, -5, 12 + k, F + 10 + k, -4, "polished_basalt[axis=x]")
    g.box(13, F + 1, -9, 15, F + 2, -7, "polished_basalt[axis=z]")
    # chains from the arch, lanterns
    for x in (-3, 3):
        chain(x, F + 9, 1, 3, "lantern[hanging=true]")
    # rubble heaps half burying the foot of the arch
    for k, (x, z) in enumerate(((-8, -7), (7, -8), (-5, -9))):
        g.blob(x, F + 1, z, 2.2, 1.6, 2, (x - 4, F + 1, z - 4, x + 4, F + 3, z + 4), 720 + k, 0.5, block=ROCK, where=lambda b: b == "air")


# ======================================================================== room 1, the threshold of ashes

def threshold():
    carve(0, F + 7, 23, 15, 10, 13, 801, clip=(-16, F - 2, 10, 16, F + 18, 35))
    for x in range(-15, 16):
        for z in range(10, 37):
            for y in range(F + 1, F + 3):
                if abs(x) <= 13 and g.get(x, y, z) not in ("air",):
                    g.set(x, y, z, "air")
    # floors at several heights: the middle at F, ledges at F+2 on both sides with stairs
    solid_floor(-14, 10, 14, 36, F, [("polished_blackstone", 3), ("polished_blackstone_bricks", 3), ("basalt[axis=y]", 1), ("smooth_basalt", 2)])
    for side in (-1, 1):
        for z in range(14, 33):
            for k in range(9, 14):
                x = side * k
                g.box(x, F + 1, z, x, F + 2, z, ROCK)
                g.set(x, F + 2, z, g.pick([("polished_blackstone_bricks", 3), ("smooth_basalt", 2), ("polished_deepslate", 1)]))
        for z in (20, 27):
            g.set(side * 8, F + 1, z, f"polished_blackstone_stairs[facing={'west' if side > 0 else 'east'}]")
            g.set(side * 9, F + 2, z, f"polished_blackstone_stairs[facing={'west' if side > 0 else 'east'}]")
            g.set(side * 9, F + 1, z, ROCK)
    # glowing cracks: magma under copper grates, in two lines along the way
    for z in range(12, 36):
        for x in (round(-4 + math.sin(z * 0.4)), round(4 + math.sin(z * 0.5 + 1))):
            g.set(x, F, z, "waxed_exposed_copper_grate")
            g.set(x, F - 1, z, "magma_block")
    # statues of smiths, broken pillars, inscriptions, lanterns, braziers, shroomlight behind bars
    statue(-11, 18, F + 3, (1, 0))
    statue(11, 29, F + 3, (-1, 0))
    for (x, z, top, broken) in ((-6, 14, F + 14, F + 6), (6, 14, F + 14, None), (-6, 32, F + 14, None), (6, 32, F + 14, F + 4)):
        pillar(x, z, F + 1, top, lambda y: "polished_basalt[axis=y]" if (y - F) % 5 else "chiseled_polished_blackstone", rad=0, broken=broken)
        if broken:
            g.set(x + 1, F + 1, z + 1, "polished_basalt[axis=x]")
            g.set(x + 2, F + 1, z + 1, "polished_basalt[axis=x]")
    for z in (12, 35):
        inscription(-10, 10, F + 6, z - (1 if z == 12 else -1) * 0)
    for (x, z) in ((-3, 18), (3, 26), (0, 32)):
        ceiling_chain(x, F + 6, z, 4, "lantern[hanging=true]")
    for (x, z) in ((-12, 24), (12, 24)):
        brazier(x, F + 3, z)
    for z in (16, 30):
        for x in (-14, 14):
            g.set(x, F + 4, z, "shroomlight")
            g.set(x + (1 if x < 0 else -1), F + 4, z, "iron_bars[north=true,south=true]")
    # an old mechanism, switched off: gears of iron blocks and a piston against the wall
    g.box(-2, F + 1, 34, 2, F + 3, 34, lambda x, y, z: "piston[facing=up]" if y == F + 1 and x == 0 else "iron_block" if (x + y) % 2 else "polished_blackstone")
    m.sound(0, F + 4, 24, [("ambient.basalt_deltas.mood", 0.3, 0.6), ("block.chain.step", 0.4, 0.5), ("block.lava.pop", 0.3, 0.6)],
            radius=18, every=(100, 220), particle={"type": "SMALL_FLAME", "count": 2, "spread": (8, 0.1, 10), "speed": 0, "every": 20})
    for (mob, x, z, y) in (("fond_calcine", -3, 24, F + 1), ("fond_calcine", 4, 30, F + 1), ("fond_escarbille", 0, 20, F + 4)):
        m.mob(mob, x, z, y=y, yaw=180, room="1")
    light(0, F + 8, 23, 10)
    # door 1: an iron grille in a built wall
    gate(DOORS_Z[0], F, "polished_blackstone_bricks", "1", "seuil")


def gate(z, y, frame, room, id, width=2):
    """A built wall across the way, an arch, the door as an iron grille."""
    for x in range(-14, 15):
        for yy in range(y + 1, y + 12):
            if g.get(x, yy, z) == "air" or abs(x) <= 13:
                g.set(x, yy, z, g.pick(BRICKS) if abs(x) > width + 1 or yy > y + 6 else g.get(x, yy, z))
    for x in range(-width - 1, width + 2):
        for yy in range(y + 1, y + 6):
            g.set(x, yy, z, "iron_bars[east=true,west=true]" if abs(x) <= width else "chiseled_polished_blackstone")
    g.box(-width - 1, y + 6, z, width + 1, y + 6, z, "polished_blackstone_bricks")
    g.box(-width, y + 1, z, width, y + 5, z, "iron_bars[east=true,west=true]")
    door(id, (-width, y + 1, z), (width, y + 5, z), room=room)
    for x in (-width - 2, width + 2):
        g.set(x, y + 4, z - 1, "lantern") if g.get(x, y + 3, z - 1) != "air" else None


# ======================================================================== room 2, the fusion galleries

def galleries():
    z0, z1 = 38, 112
    for k in range(7):
        z = z0 + 6 + k * 10
        carve(r.randint(-3, 3), F + 2, z, 21, 18, 9, 820 + k, 0.3, clip=(-21, F - 13, z0, 21, F + 30, z1 - 1))
    # deep bottom: lava lakes at F-12
    for x in range(-20, 21):
        for z in range(z0 + 1, z1):
            if g.get(x, F - 6, z) == "air" or True:
                for y in range(F - 13, F):
                    g.set(x, y, z, "air")
    for x in range(-21, 22):
        for z in range(z0, z1 + 1):
            if abs(x) == 21 or z in (z0, z1):
                for y in range(F - 13, F + 1):
                    if g.get(x, y, z) == "air":
                        g.set(x, y, z, ROCK)
            g.set(x, F - 13, z, "magma_block")
            g.set(x, F - 14, z, ROCK)
    for x in range(-20, 21):
        for z in range(z0 + 1, z1):
            g.set(x, F - 12, z, LAVA)
    # the end walls stand up to head height but at the way in and the landing
    for x in range(-21, 22):
        for (z, keep) in ((z0, BW), (z1, 7)):
            if abs(x) > keep:
                for y in range(F, F + 5):
                    g.set(x, y, z, ROCK)
    # the bridge: straight to the door, 2 * BW + 1 wide (room to fight), on piers every 9, iron bands, an iron stripe
    for z in range(z0, z1 + 1):
        for x in range(-BW, BW + 1):
            g.set(x, F, z, "iron_block" if x == 0 or (z - z0) % 9 == 0 else g.pick(BRICKS))
            for y in range(F + 1, F + 5):
                if g.get(x, y, z) != "air":
                    g.set(x, y, z, "air")
        if (z - z0) % 9 == 0:
            for y in range(F - 12, F):
                for x in range(-BW, BW + 1):
                    g.set(x, y, z, "polished_blackstone_bricks" if (y - F) % 4 else "iron_block")
    for x in range(-7, 8):
        for z in range(z1 - 4, z1 + 1):
            g.set(x, F, z, g.pick(BRICKS))
            for y in range(F + 1, F + 5):
                if g.get(x, y, z) != "air":
                    g.set(x, y, z, "air")
            for y in range(F - 12, F):
                g.set(x, y, z, "polished_blackstone_bricks" if (y - F) % 4 else "iron_block")
    # lava conduits across the cavity, under and over the walkway
    for (z, y) in ((z0 + 14, F + 10), (z0 + 38, F - 4), (z0 + 62, F + 12)):
        conduit(-20, 20, z, y)
    # lava falls on the walls
    for (x, z, ytop) in ((-19, z0 + 26, F + 14), (19, z0 + 48, F + 12), (-19, z0 + 66, F + 10)):
        lavafall(x, z, ytop, F - 12, (1 if x < 0 else -1, 0), with_pool=False)
    # monumental chains, an old crane, beams
    for (x, z) in ((-10, z0 + 10), (12, z0 + 30), (-8, z0 + 50), (10, z0 + 68)):
        ceiling_chain(x, F + 2, z, 14, "iron_block")
    g.box(-14, F - 12, z0 + 44, -13, F + 16, z0 + 45, lambda x, y, z: "iron_block" if y % 4 == 0 else "polished_blackstone_bricks")
    g.box(-14, F + 16, z0 + 44, -8, F + 16, z0 + 45, "iron_block")
    chain(-9, F + 15, z0 + 44, 6, "anvil[facing=north]")
    guard_edges(-20, z0, 20, z1, F)
    m.sound(0, F - 4, z0 + 20, [("block.lava.ambient", 0.7, 0.7), ("ambient.basalt_deltas.loop", 0.3, 0.6)], radius=20, every=(60, 140),
            particle={"type": "LAVA", "count": 2, "spread": (12, 0.2, 8), "speed": 0, "every": 20})
    m.sound(0, F + 4, z0 + 44, [("block.chain.step", 0.6, 0.5), ("block.piston.extend", 0.3, 0.5), ("block.anvil.place", 0.2, 0.5)],
            radius=18, every=(100, 220))
    m.sound(0, F + 2, z0 + 62, [("ambient.basalt_deltas.mood", 0.4, 0.6), ("block.fire.ambient", 0.5, 0.7)], radius=18, every=(120, 240),
            particle={"type": "SMOKE", "count": 4, "spread": (6, 2, 4), "speed": 0.01, "every": 10})
    # every monster stands on the bridge (the blazes too: never over the lava, out of reach)
    for (mob, dz, x) in (("fond_calcine", 10, -2), ("fond_porteur", 24, 2), ("fond_escarbille", 30, -3),
                         ("fond_calcine", 40, 3), ("fond_calcine", 44, -1), ("fond_escarbille", 52, 3), ("fond_porteur", 64, 0)):
        m.mob(mob, x, z0 + dz, y=F + 1, yaw=180, room="2")
    for z in range(z0 + 6, z1, 12):
        for x in (-BW + 1, BW - 1):
            light(x, F + 5, z, 10)
    gate(DOORS_Z[1], F, "polished_blackstone_bricks", "2", "galeries")


# ======================================================================== room 3, the abandoned foundry

def foundry():
    z0, z1 = 114, 176
    for x in range(-20, 21):
        for z in range(z0, z1 + 1):
            for y in range(F + 1, F + 19):
                vault = F + 19 - int(0.025 * x * x)
                g.set(x, y, z, "air" if y < vault else g.pick(DEEPB))
    solid_floor(-20, z0, 20, z1, F, [("polished_deepslate", 3), ("deepslate_tiles", 3), ("polished_blackstone", 2), ("iron_block", 1)])
    # walls of deepslate bricks, pilasters every 6, recesses, chimney stacks
    for z in range(z0, z1 + 1):
        for x in (-21, 21):
            for y in range(F, F + 18):
                g.set(x, y, z, g.pick(DEEPB))
        if z % 6 == 0:
            for x in (-20, 20):
                g.box(x, F + 1, z, x, F + 15, z, lambda a, b, c: "polished_deepslate" if b % 5 else "iron_block")
    # side catwalks at F+6 with railings, out of reach (decor: no stairs, a single way through the hall)
    for z in range(z0 + 4, z1 - 3):
        for x in (-19, -18, 18, 19):
            g.set(x, F + 6, z, "iron_block" if x in (-18, 18) else "polished_blackstone_bricks")
        for x in (-17, 17):
            g.set(x, F + 7, z, "iron_bars[north=true,south=true]")
    # overhead cranes: beams across the hall, chains with hooks
    for z in (z0 + 20, z0 + 40):
        g.box(-20, F + 15, z, 20, F + 15, z, "iron_block")
        for x in (-8, 6):
            chain(x, F + 14, z, 6, "iron_block")
    # vents in the vault: smoke
    for z in (z0 + 14, z0 + 34, z0 + 54):
        m.sound(0, F + 15, z, [("block.blastfurnace.fire_crackle", 0.2, 0.5)], radius=14, every=(160, 300),
                particle={"type": "CAMPFIRE_COSY_SMOKE", "count": 1, "spread": (4, 0.3, 1), "speed": 0.01, "every": 30})
    for z in range(z0 + 8, z1, 10):
        light(0, F + 8, z, 13)
        light(-10, F + 7, z, 11)
        light(10, F + 7, z, 11)
    for (mob, x, z, y) in (("fond_ouvrier", 0, z0 + 30, F + 1), ("fond_calcine", -4, z0 + 18, F + 1), ("fond_calcine", 5, z0 + 42, F + 1),
                           ("fond_porteur", -6, z0 + 48, F + 1), ("fond_escarbille", 3, z0 + 24, F + 5), ("fond_porteur", 6, z0 + 14, F + 1)):
        m.mob(mob, x, z, y=y, yaw=180, room="3")
    gate(DOORS_Z[2], F, "deepslate_bricks", "3", "fonderie")


# ======================================================================== room 4, the heart of the volcano

def heart():
    z0, z1 = 178, 244
    carve(0, F + 10, 211, 27, 26, 33, 860, 0.25, clip=(-30, F - 16, z0, 30, F + 38, z1))
    for x in range(-27, 28):
        for z in range(z0, z1 + 1):
            for y in range(F - 16, F - 13):
                g.set(x, y, z, ROCK)
            if g.get(x, F - 12, z) == "air":
                g.set(x, F - 13, z, LAVA)
    for x in range(-28, 29):
        for z in range(z0 - 1, z1 + 2):
            if g.get(x, F - 13, z) == LAVA:
                for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    if g.get(x + dx, F - 13, z + dz) == "air":
                        g.set(x + dx, F - 13, z + dz, ROCK)
    # the viewpoint balcony at the entrance, over the gulf, then the bridge
    for x in range(-6, 7):
        for z in range(z0, z0 + 12):
            g.set(x, F, z, g.pick(BRICKS))
            for y in range(F - 4, F):
                g.set(x, y, z, ROCK if z < z0 + 8 or abs(x) > 3 else "polished_blackstone_bricks")
    for x in range(-6, 7):
        for z in range(z1 - 10, z1 + 1):
            g.set(x, F, z, g.pick(BRICKS))
            for y in range(F - 13, F):
                g.set(x, y, z, ROCK)
    for x in range(-7, 8):
        for z in range(z0, z1 + 1):
            if g.get(x, F, z) != "air" and not gridlib.passable(g.get(x, F, z)) and (z < z0 + 12 or z > z1 - 11 or abs(x) <= BW):
                for y in range(F + 1, F + 5):
                    if g.get(x, y, z) != LAVA:
                        g.set(x, y, z, "air")
    # suspended bridge, straight from the balcony to the north landing, chains to the vault along its sides
    for z in range(z0 + 12, z1 - 10):
        for x in range(-BW, BW + 1):
            g.set(x, F, z, "iron_block" if x == 0 else g.pick(BRICKS))
            g.set(x, F - 1, z, "polished_blackstone_bricks")
        if z % 6 == 0:
            for x in (-BW - 1, BW + 1):
                ceiling_chain(x, F + 4, z, 40)
    # basalt columns rising from the lava, falls, a sunk old arch
    for k, (x, z) in enumerate(((12, 200), (16, 222), (-12, 230), (9, 236), (14, 188))):
        pillar(x, z, F - 13, F + 30, lambda y: "basalt[axis=y]" if y % 7 else "magma_block", rad=1)
    for (x, z) in ((22, 196), (22, 226)):
        for xx in range(x, x + 8):
            for yy in range(F + 18, F + 23):
                if g.get(xx, yy, z) == "air" and xx > x:
                    g.set(xx, yy, z, ROCK)
        lavafall(x, z, F + 20, F - 13, (-1, 0), with_pool=False)
    g.box(7, F - 13, 214, 7, F - 3, 214, "polished_blackstone_bricks")
    g.box(13, F - 13, 214, 13, F - 6, 214, "polished_blackstone_bricks")
    g.box(7, F - 3, 214, 11, F - 3, 214, "cracked_polished_blackstone_bricks")
    guard_edges(-8, z0, 8, z1, F)
    m.sound(0, F + 2, z0 + 6, [("ambient.basalt_deltas.mood", 0.6, 0.5), ("block.lava.ambient", 0.8, 0.6)], radius=22, every=(80, 180),
            particle={"type": "LAVA", "count": 3, "spread": (14, 0.2, 14), "speed": 0, "every": 20})
    m.sound(0, F - 8, 212, [("block.lava.pop", 0.8, 0.6), ("ambient.nether_wastes.mood", 0.4, 0.6)], radius=26, every=(60, 140),
            particle={"type": "SMOKE", "count": 6, "spread": (16, 1, 16), "speed": 0.01, "every": 20})
    # what waits behind the last door: hammer blows, a furnace breathing, conduits glowing
    m.sound(0, F + 3, z1 - 3, [("block.anvil.land", 0.3, 0.4), ("entity.iron_golem.step", 0.6, 0.4), ("block.blastfurnace.fire_crackle", 0.5, 0.5)],
            radius=14, every=(60, 140), particle={"type": "FLAME", "count": 3, "spread": (3, 2, 0.3), "speed": 0.01, "every": 10})
    for (mob, x, z, y) in (("fond_escarbille", -2, 200, F + 1), ("fond_calcine", 3, 214, F + 1), ("fond_porteur", -3, 232, F + 1),
                           ("fond_escarbille", 2, 226, F + 1), ("fond_calcine", 0, 240, F + 1)):
        m.mob(mob, x, z, y=y, yaw=180, room="4")
    for z in range(z0 + 6, z1, 12):
        for x in (-BW + 1, BW - 1):
            light(x, F + 4, z, 10)
    gate(DOORS_Z[3], F, "polished_blackstone_bricks", "4", "coeur", width=3)


# ======================================================================== room 5, the arena

PILLAR_LOOKS = [
    lambda y: "gilded_blackstone" if y % 5 == 0 else "polished_blackstone_bricks",
    lambda y: "oxidized_copper" if y % 4 == 0 else "weathered_copper" if y % 2 else "exposed_copper",
    lambda y: "iron_block" if y % 4 == 0 else "deepslate_bricks",
    lambda y: "magma_block" if y % 5 == 0 else "basalt[axis=y]",
]


def arena():
    carve(0, F + 10, CZ, AR + 6, 22, AR + 6, 880, 0.2, clip=(-AR - 9, F - 4, DOORS_Z[3] + 1, AR + 9, F + 32, CZ + AR + 9))
    for x in range(-AR - 8, AR + 9):
        for z in range(DOORS_Z[3] + 1, CZ + AR + 9):
            d = math.hypot(x, z - CZ)
            edge = AR + 0.8 * NOISES[2](x * 0.3, 1, z * 0.3)
            if d <= edge:
                ring = int(d) // 3
                ang = math.degrees(math.atan2(z - CZ, x)) % 30
                if d < 2:
                    b = "gilded_blackstone"
                elif abs(d - round(d / 3) * 3) < 0.45:
                    b = "polished_blackstone" if ring % 2 else "iron_block"
                elif ang < 1.6 and d > 3:
                    b = "copper_block"
                elif r.random() < 0.04 and d > 5:
                    b = "orange_stained_glass"
                    g.set(x, F - 1, z, "shroomlight")
                else:
                    b = g.pick([("polished_blackstone_bricks", 4), ("deepslate_bricks", 2), ("basalt[axis=y]", 1), ("blackstone", 1)])
                g.set(x, F, z, b)
                for y in range(F + 1, F + 4):
                    if g.get(x, y, z) != "air":
                        g.set(x, y, z, "air")
                for y in range(F - 3, F):
                    if g.get(x, y, z) == "air":
                        g.set(x, y, z, ROCK)
            elif d <= edge + 4:
                # the rim: a low wall, then a lava conduit behind bars, then the rock
                for y in range(F - 3, F + 1):
                    g.set(x, y, z, ROCK)
                if d <= edge + 1.2:
                    g.set(x, F + 1, z, "polished_blackstone_wall")
                    for y in range(F + 2, F + 5):
                        g.set(x, y, z, "barrier")
                elif d <= edge + 2.4:
                    g.set(x, F, z, LAVA)
                    g.set(x, F + 1, z, "iron_bars")
                    for y in range(F + 2, F + 5):
                        g.set(x, y, z, "barrier")
                else:
                    for y in range(F + 1, F + 3):
                        g.set(x, y, z, ROCK)
            else:
                for y in range(F - 12, F + 3):
                    if g.get(x, y, z) == "air":
                        g.set(x, y, z, ROCK)
    # the four pillars, each of its own make, up to the dome
    for (px, pz), look in zip(PILLARS, PILLAR_LOOKS):
        top = g.ceiling(px, F + 1, pz, 40) or F + 26
        pillar(px, pz, F + 1, top, look, rad=1)
    # braziers on the rim, chains from the dome, volcanic openings (lava behind bars)
    for k in range(8):
        a = math.radians(22.5 + 45 * k)
        x, z = round(math.cos(a) * (AR - 1)), round(CZ + math.sin(a) * (AR - 1))
        if abs(x) <= 3 and z < CZ:
            continue
        brazier(x, F + 1, z) if g.get(x, F + 1, z) == "air" else None
    for (x, z) in ((-5, CZ - 5), (5, CZ + 5), (-12, CZ + 2), (12, CZ - 2), (0, CZ + 12)):
        ceiling_chain(x, F + 6, z, 6)
    for z in range(CZ - 3, CZ + 4):
        light(0, F + 12, z, 13)
    for k in range(6):
        a = math.radians(60 * k)
        light(round(math.cos(a) * 6), F + 7, round(CZ + math.sin(a) * 6), 12)
    # the way in through the rim
    for x in range(-3, 4):
        for z in range(DOORS_Z[3] + 1, CZ - AR + 2):
            g.set(x, F, z, g.pick(BRICKS))
            for y in range(F + 1, F + 6):
                g.set(x, y, z, "air")
    m.mob("golem_fonderie", 0, CZ, y=F + 1, yaw=180, room="5")
    m.objectives = ["golem_fonderie"]
    m.sound(0, F + 6, CZ, [("ambient.basalt_deltas.mood", 0.5, 0.5), ("block.lava.ambient", 0.6, 0.5), ("block.chain.step", 0.4, 0.4)],
            radius=24, every=(120, 240), particle={"type": "SMALL_FLAME", "count": 3, "spread": (12, 0.2, 12), "speed": 0, "every": 20})


# ======================================================================== finish, checks

def seal_drops(passes=4, depth=3):
    """Invisible walls wherever a walking player could drop more than `depth` blocks (into a gulf, onto the lava)."""
    for _ in range(passes):
        seen = g.walk(g.start, set(range(len(g.doors))), top=Y1)
        put = 0
        for (x, y, z) in seen:
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, nz = x + dx, z + dz
                if not (gridlib.passable(g.get(nx, y, nz)) and gridlib.passable(g.get(nx, y + 1, nz))):
                    continue
                yy = y
                while yy > Y0 and gridlib.passable(g.get(nx, yy - 1, nz)) and y - yy <= depth:
                    yy -= 1
                if y - yy > depth or g.get(nx, yy - 1, nz) == LAVA:
                    for k in range(0, 3):
                        if gridlib.passable(g.get(nx, y + k, nz)) and g.get(nx, y + k, nz) != "water":
                            g.set(nx, y + k, nz, "barrier")
                            put += 1
        if not put:
            break


def paint_all():
    for p, b in list(g.over.items()):
        if b == ROCK:
            g.set(*p, paint(*p))
    for p, b in list(g.over.items()):
        if b != "air" and not b.startswith("light") and b != LAVA:
            continue
        x, y, z = p
        for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            q = (x + d[0], y + d[1], z + d[2])
            if g.inside(*q) and q not in g.over:
                g.set(*q, paint(*q))
    for p, b in list(g.over.items()):
        if gridlib.gravity(b) and gridlib.passable(g.get(p[0], p[1] - 1, p[2])):
            g.set(*p, "basalt[axis=y]")


def check():
    errors = g.check_basic()
    for (x, y, z), b in g.over.items():
        if b != LAVA or (x, y, z) in LAVA_FALLS:
            continue
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            n = g.get(x + dx, y + dy, z + dz)
            if n == "air" or (gridlib.passable(n) and n != "water"):
                errors.append(f"lava leaks at {x} {y} {z} -> {x + dx} {y + dy} {z + dz} ({n})")
                break
    for k in range(len(g.doors) + 1):
        seen = g.walk(g.start, set(range(k)), top=Y1)
        if not seen:
            errors.append("start not standable")
            break
        topz = max(zone_of(*p) for p in seen)
        if topz > k + 1:
            bad = next(p for p in seen if zone_of(*p) > k + 1)
            errors.append(f"doors open {k}: zone {topz} reachable, e.g. {bad}")
        if topz < min(k + 1, 5):
            errors.append(f"doors open {k}: zone {k + 1} not reachable (max {topz})")
    seen = g.walk(g.start, set(range(len(g.doors))), top=Y1)
    for p in seen:
        if g.get(p[0], p[1] - 1, p[2]) == LAVA or g.get(*p) == LAVA:
            errors.append(f"one can walk into the lava at {p}")
            break
    for s in m.spawns:
        x, y, z = int(math.floor(s["at"][0])), int(s["at"][1]), int(math.floor(s["at"][2]))
        if g.get(x, y, z) != "air" and not gridlib.passable(g.get(x, y, z)):
            errors.append(f"{s['mob']} spawns in {g.get(x, y, z)} at {x} {y} {z}")
        elif s["mob"] != "fond_escarbille" and (x, y, z) not in seen and not any((x + a, y, z + b) in seen for a in (-1, 0, 1) for b in (-1, 0, 1)):
            errors.append(f"{s['mob']} at {x} {y} {z} cannot be walked to")
    for x in range(-AR + 2, AR - 1):
        for z in range(CZ - AR + 2, CZ + AR - 1):
            if math.hypot(x, z - CZ) > AR - 2 or any(abs(x - px) <= 1 and abs(z - pz) <= 1 for px, pz in PILLARS):
                continue
            if any(not gridlib.passable(g.get(x, y, z)) and not g.get(x, y, z).startswith("light") for y in (F + 1, F + 2, F + 3)):
                errors.append(f"the arena floor is not free at {x} {z}: {[g.get(x, y, z) for y in (F + 1, F + 2, F + 3)]}")
                break
    return errors


volcano()
outside()
entrance()
threshold()
galleries()
foundry()
heart()
arena()
seal_drops()
paint_all()
m.textures = "rpg"
m.biome = "basalt_deltas"
m.ybox = (Y0, Y1)

if __name__ == "__main__":
    args = sys.argv[1:]
    errs = check()
    for e in errs[:50]:
        print("CHECK:", e)
    print(f"{len(errs)} problem(s), {len(g.over)} blocks changed, {len(g.entities)} entities, {len(m.spawns)} monsters, "
          f"{len(m.ambience)} ambience emitters")
    if "--preview" in args:
        out = args[args.index("--preview") + 1]
        g.png_slice(os.path.join(out, "fond_y64.png"), y=F + 1, k=2)
        g.png_slice(os.path.join(out, "fond_coupe_x0.png"), x=0, k=2)
    if "--check" in args or "--preview" in args:
        sys.exit(1 if errs else 0)
    if errs:
        sys.exit("checks failed: nothing sent")
    g.commands(m)
    print(f"{len(m.cmds)} commands")
    maplib.run(m, args[0] if args and not args[0].startswith("--") else "labs", "fondeur")
