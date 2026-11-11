#!/usr/bin/env python3
"""Map of the rock dungeon, La Carrière Grondante (boss: the Colosse d'Abîme), built by console commands (RCON).

A worked-out quarry cut into layered rock, played from south to north (+z), floor at y 63 everywhere:
  - road (start at 0, 64, 0): a cutting through the rock, rails and lanterns;
  - room 1, the Working Face (open sky, 37 x 33): terraced walls, scaffolds, blocks waiting for the carts, a crane,
    rock piles to hide behind; stone-cutters and sandstone throwers;
  - room 2, the Frozen Gallery (19 x 33, roofed): a cave where the quarry met a frozen spring: packed ice, blue ice,
    snow, ice columns; frost sentinels shoot along it, stone-cutters rush;
  - room 3, the Granite Hall (round, radius 14, roofed): four polished granite pillars, a magma vein lighting it;
    the granite golem (mini-boss) and its throwers;
  - the Abyss (round, radius 20, open sky, walls 22 high): basalt and blackstone, cracks of magma, a ring of broken
    columns near the wall (cover against the shards, never in the middle: the Cataclysm takes 10 blocks).
Doors (oak gates sinking into the ground) open once every monster of the room before them is dead.

Usage: gen_roche.py [server]       (labs by default) : builds, copies into maps/custom/roche, writes edits/roche.yml.
"""
import math
import random
import sys

import maplib

F = 63  # floor
r = random.Random(23)
m = maplib.Map()
m.box = (-36, -12, 36, 172)
STONES = ["stone", "stone", "andesite", "andesite", "cobblestone", "granite", "diorite", "tuff"]

# ---------------------------------------------------------------- the rock mass, in strata
for z0 in range(-10, 171, 20):
    z1 = min(170, z0 + 19)
    for y0, y1, b in ((50, 58, "deepslate"), (59, 66, "stone"), (67, 70, "andesite"), (71, 73, "tuff"), (74, 77, "stone"),
                      (78, 80, "granite"), (81, 84, "diorite"), (85, 86, "dirt"), (87, 87, "grass_block")):
        m.fill(-34, y0, z0, 34, y1, z1, b)
# seams
for _ in range(500):
    m.set(r.randint(-34, 34), r.randint(F + 1, 86), r.randint(-10, 170),
          r.choice(["gravel", "cobblestone", "calcite", "coal_ore", "iron_ore", "granite", "diorite", "copper_ore"]))


def floor_mix(x0, z0, x1, z1, base, spots):
    m.fill(x0, F, z0, x1, F, z1, base)
    for _ in range(int((x1 - x0 + 1) * (z1 - z0 + 1) * 0.18)):
        m.set(r.randint(x0, x1), F, r.randint(z0, z1), r.choice(spots))


# ---------------------------------------------------------------- road (z -8..9)
m.fill(-3, F + 1, -8, 3, 87, 9, "air")
floor_mix(-3, -8, 3, 9, "gravel", ["cobblestone", "andesite", "coarse_dirt"])
m.fill(-1, F + 1, -6, -1, F + 1, 9, "rail[shape=north_south]")
for z in (-6, 2):
    for x in (-3, 3):
        m.fill(x, F + 1, z, x, F + 3, z, "spruce_log")
        m.set(x, F + 4, z, "lantern")
m.set(2, F + 1, -4, "barrel[facing=up]")
m.set(2, F + 1, -3, "chest[facing=west]")
m.start = [0.5, 64.0, -5.5, 0.0, 0.0]

# ---------------------------------------------------------------- room 1: the working face (x -18..18, z 10..42)
m.fill(-18, F + 1, 10, 18, 87, 42, "air")
floor_mix(-18, 10, 18, 42, "stone", ["andesite", "gravel", "cobblestone", "tuff", "stone_bricks"])
# terraces: the walls step back every 5 blocks (decor, out of reach)
for k, h in enumerate((F + 6, F + 11, F + 16)):
    d = 1 + k * 2
    for side in (-1, 1):
        x = side * (18 + d)
        m.fill(min(x, side * 19), h, 10, max(x, side * 19), 87, 42, "air")
    m.fill(-18 - d, h, 43, 18 + d, 87, 43 + d, "air")
# blocks waiting to be carted, scaffolds, a crane
for x, z in ((-12, 16), (-14, 30), (11, 20), (13, 34), (-6, 36), (5, 27)):
    b = r.choice(["stone", "andesite", "granite", "polished_andesite", "cobblestone"])
    m.fill(x, F + 1, z, x + 1, F + 2, z + 1, b)
    m.set(x, F + 3, z, b)
for x in (-17, 17):
    for z in range(14, 40, 6):
        m.fill(x, F + 1, z, x, F + 5, z, "scaffolding")
m.fill(15, F + 1, 12, 15, F + 9, 12, "spruce_log")
m.fill(9, F + 9, 12, 15, F + 9, 12, "stripped_spruce_log[axis=x]")
m.fill(9, F + 5, 12, 9, F + 8, 12, "iron_chain[axis=y]")
m.set(9, F + 4, 12, "stone")
m.fill(-1, F + 1, 10, -1, F + 1, 40, "rail[shape=north_south]")
for x, z in ((-8, 13), (8, 39), (-15, 23), (14, 27)):
    m.set(x, F + 1, z, r.choice(["barrel[facing=up]", "chest[facing=south]", "stonecutter[facing=north]", "grindstone[face=floor,facing=north]"]))
for x in (-16, 16):
    for z in (16, 28, 40):
        m.fill(x, F + 1, z, x, F + 3, z, "spruce_fence")
        m.set(x, F + 4, z, "lantern")
# room 1: 7 stone-cutters, 3 throwers (on the piles' side)
for x, z in ((-6, 18), (4, 20), (-10, 26), (8, 28), (-2, 32), (10, 36), (-12, 38)):
    m.mob("rochiste_tailleur", x, z, yaw=180, room="1")
for x, z in ((-14, 34), (14, 24), (0, 40)):
    m.mob("rochiste_lanceur", x, z, yaw=180, room="1")
# gate 1 (x -3..3, z 43), then a cut to the gallery
m.fill(-3, F + 1, 43, 3, F + 6, 52, "air")
floor_mix(-3, 43, 3, 52, "gravel", ["cobblestone", "andesite"])
m.fill(-3, F + 1, 44, 3, F + 6, 44, "oak_planks")
m.fill(-3, F + 1, 44, -3, F + 6, 44, "spruce_log")
m.fill(3, F + 1, 44, 3, F + 6, 44, "spruce_log")
m.fill(-3, F + 6, 44, 3, F + 6, 44, "dark_oak_log[axis=x]")
m.door("porte_front", (-3, F + 1, 44), (3, F + 6, 44), room="1")
m.fill(-3, F + 7, 45, 3, F + 9, 52, "stone")  # roof of the cut

# ---------------------------------------------------------------- room 2: the frozen gallery (x -9..9, z 53..85)
m.fill(-9, F + 1, 53, 9, F + 9, 85, "air")
m.fill(-9, F + 10, 53, 9, F + 12, 85, "packed_ice")
floor_mix(-9, 53, 9, 85, "packed_ice", ["snow_block", "blue_ice", "stone", "snow_block"])
for z in range(53, 86):
    for x in (-10, 10):
        if r.random() < 0.6:
            m.fill(x, F + 1, z, x, F + r.randint(3, 9), z, r.choice(["packed_ice", "blue_ice", "packed_ice"]))
# ice columns (cover), snow layers, icicles
for x, z in ((-5, 60), (5, 66), (-4, 74), (4, 80)):
    m.fill(x, F + 1, z, x + 1, F + 9, z + 1, "packed_ice")
    m.set(x - 1, F + 1, z, "blue_ice")
    m.set(x + 2, F + 1, z + 1, "blue_ice")
for _ in range(70):
    x, z = r.randint(-8, 8), r.randint(54, 84)
    m.set(x, F + 1, z, "snow[layers=%d]" % r.randint(1, 2))
for _ in range(40):
    x, z = r.randint(-9, 9), r.randint(53, 85)
    m.set(x, F + 9, z, "pointed_dripstone[thickness=tip,vertical_direction=down]" if r.random() < 0.3 else "blue_ice")
# light under the roof (only packed and blue ice here: they never melt)
for z in range(56, 86, 8):
    for x in (-6, 6):
        m.set(x, F + 9, z, "light[level=10]")
for z in (58, 70, 82):
    m.set(0, F + 9, z, "soul_lantern[hanging=true]")
for x, z in ((-6, 58), (6, 63), (-7, 70), (7, 76), (0, 82)):
    m.mob("rochiste_sentinelle", x, z, yaw=180, room="2")
for x, z in ((-2, 60), (3, 70), (-4, 78), (2, 84)):
    m.mob("rochiste_tailleur", x, z, yaw=180, room="2")
m.fill(-2, F + 1, 86, 2, F + 5, 89, "air")
floor_mix(-2, 86, 2, 89, "stone", ["packed_ice", "gravel"])
m.fill(-2, F + 1, 86, 2, F + 5, 86, "oak_planks")
m.fill(-2, F + 1, 86, -2, F + 5, 86, "spruce_log")
m.fill(2, F + 1, 86, 2, F + 5, 86, "spruce_log")
m.fill(-2, F + 5, 86, 2, F + 5, 86, "dark_oak_log[axis=x]")
m.door("porte_glace", (-2, F + 1, 86), (2, F + 5, 86), room="2")

# ---------------------------------------------------------------- room 3: the granite hall (centre 0, 104, r 14)
CX, CZ = 0, 104
m.disc(CX, CZ, 14, F + 1, F + 12, "air")
m.disc(CX, CZ, 14.5, F + 13, F + 14, "granite")
m.disc(CX, CZ, 14, F, F, "polished_granite")
for _ in range(160):
    a, d = r.uniform(0, 6.283), 14 * math.sqrt(r.random())
    m.set(round(CX + d * math.cos(a)), F, round(CZ + d * math.sin(a)), r.choice(["granite", "red_terracotta", "coarse_dirt", "cobblestone"]))
for a in (45, 135, 225, 315):
    x, z = round(CX + 8 * math.cos(math.radians(a))), round(CZ + 8 * math.sin(math.radians(a)))
    m.fill(x, F + 1, z, x + 1, F + 12, z + 1, "polished_granite")
    m.fill(x, F + 1, z, x + 1, F + 1, z + 1, "chiseled_stone_bricks")
    m.set(x, F + 6, z - 1, "light[level=12]")
# a magma vein across the floor (light, no harm: fire is off in dungeons)
for t in range(-12, 13):
    x = round(3 * math.sin(t / 3.0))
    m.set(x, F, CZ + t, "magma_block")
for a in range(0, 360, 30):
    x, z = round(CX + 13 * math.cos(math.radians(a))), round(CZ + 13 * math.sin(math.radians(a)))
    m.set(x, F + 8, z, "light[level=13]")
m.mob("rochiste_golem", 0, 108, yaw=180, room="3")
for x, z in ((-9, 112), (9, 112), (0, 115)):
    m.mob("rochiste_lanceur", x, z, yaw=180, room="3")
for x, z in ((-6, 100), (6, 100)):
    m.mob("rochiste_tailleur", x, z, yaw=180, room="3")
m.fill(-3, F + 1, 117, 3, F + 7, 124, "air")
floor_mix(-3, 117, 3, 124, "cobbled_deepslate", ["deepslate", "gravel", "basalt"])
m.fill(-3, F + 1, 118, 3, F + 7, 118, "oak_planks")
m.fill(-3, F + 1, 118, -3, F + 7, 118, "spruce_log")
m.fill(3, F + 1, 118, 3, F + 7, 118, "spruce_log")
m.fill(-3, F + 7, 118, 3, F + 7, 118, "dark_oak_log[axis=x]")
m.door("porte_granite", (-3, F + 1, 118), (3, F + 7, 118), room="3")
for z in (120, 123):
    for x in (-3, 3):
        m.set(x, F + 5, z, "soul_lantern")

# ---------------------------------------------------------------- the abyss (centre 0, 146, r 20)
BX, BZ, BR = 0, 146, 20
m.disc(BX, BZ, BR, F + 1, 87, "air")
m.disc(BX, BZ, BR, F, F, "smooth_basalt")
for _ in range(500):
    a, d = r.uniform(0, 6.283), BR * math.sqrt(r.random())
    m.set(round(BX + d * math.cos(a)), F, round(BZ + d * math.sin(a)), r.choice(["basalt[axis=y]", "blackstone", "cobbled_deepslate", "tuff", "deepslate"]))
for k in range(6):
    a0 = r.uniform(0, 6.283)
    for s in range(4, 18):
        a = a0 + 0.12 * math.sin(s * 0.7)
        m.set(round(BX + s * math.cos(a)), F, round(BZ + s * math.sin(a)), "magma_block")
# walls: dark strata up to the sky
for z in range(BZ - BR - 2, BZ + BR + 3):
    for x in range(BX - BR - 2, BX + BR + 3):
        d = math.hypot(x - BX, z - BZ)
        if BR < d <= BR + 2.2 and not (-3 <= x <= 3 and z < BZ):
            m.cmds.append(f"fill {x} {F + 1} {z} {x} {F + 14} {z} {'blackstone' if (x + z) % 3 else 'basalt[axis=y]'}")
# broken columns near the wall (cover against the shards), never in the middle (the Cataclysm takes 10 blocks)
for a in (20, 70, 110, 160, 200, 250, 290, 340):
    x, z = round(BX + 16 * math.cos(math.radians(a))), round(BZ + 16 * math.sin(math.radians(a)))
    h = r.randint(3, 6)
    m.fill(x, F + 1, z, x + 1, F + h, z + 1, "polished_basalt[axis=y]")
    m.set(x, F + h + 1, z, "cobbled_deepslate_slab")
for a in range(0, 360, 24):
    x, z = round(BX + (BR - 1) * math.cos(math.radians(a))), round(BZ + (BR - 1) * math.sin(math.radians(a)))
    m.set(x, F + 1, z, "light[level=12]")
m.mob("rochiste_colosse", 0, 156, yaw=180, room="4")
m.objectives = ["rochiste_colosse"]

if __name__ == "__main__":
    maplib.run(m, sys.argv[1] if len(sys.argv) > 1 else "labs", "roche")
