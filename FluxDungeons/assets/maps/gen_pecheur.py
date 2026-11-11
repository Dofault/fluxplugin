#!/usr/bin/env python3
"""Map of the fisher's dungeon, La Piscine des Marées (boss: the Loutre des Marées), built by console commands (RCON).

A sea cave played from south to north (+z), floor at y 63:
  - the pier (start at 0, 64, -4): planks over the water of the cave mouth, nets and barrels;
  - room 1, the Cove (33 x 29, roofed, openings to the sky): sand, shallow pools, wrecked boats, drying nets; drowned
    harpooners and cursed fishermen. At its far end a pool behind a gate: the way down;
  - room 2, the Sunken Passage: a flooded tunnel 7 wide and 5 high, 12 blocks under the cove, 42 long, with two air
    pockets (raised ceiling) to breathe; tide guardians. A gate under the water at its end, then a shaft up;
  - room 3, the Coral Grotto (31 x 29, roofed): the shaft comes up in its pool; dead coral, prismarine, sea lanterns;
    the drowned master fisher (mini-boss) and his crew;
  - the Tide Pool: a closed box of prismarine, 23 x 23 inside, frozen from wall to wall over 5 blocks of water. The
    players drop into it from a ledge 3 blocks above the ice and cannot climb back: no dry ground anywhere once the
    ice breaks, the fight has to be played in the water with the otter. Lights 6 blocks or more above the ice (ice
    melts above 11).

Usage: gen_pecheur.py [server]     (labs by default): builds, copies into maps/custom/pecheur, writes edits/pecheur.yml.
"""
import math
import random
import sys

import maplib

F = 63
r = random.Random(41)
m = maplib.Map()
m.box = (-30, -14, 30, 160)

# ---------------------------------------------------------------- rock mass
for z0 in range(-12, 159, 20):
    z1 = min(158, z0 + 19)
    for y0, y1, b in ((44, 50, "deepslate"), (51, 60, "stone"), (61, 66, "tuff"), (67, 74, "stone"), (75, 79, "andesite"),
                      (80, 80, "dirt"), (81, 81, "grass_block")):
        m.fill(-28, y0, z0, 28, y1, z1, b)
for _ in range(300):
    m.set(r.randint(-28, 28), r.randint(45, 79), r.randint(-12, 158), r.choice(["gravel", "clay", "prismarine", "tuff", "sand", "diorite"]))

# ---------------------------------------------------------------- the pier (z -10..7)
m.fill(-5, F - 3, -10, 5, F + 8, 7, "air")
m.fill(-5, F - 3, -10, 5, F - 3, 7, "sand")
m.fill(-5, F - 2, -10, 5, F - 1, 7, "water")
m.fill(-2, F, -10, 2, F, 7, "spruce_planks")
for z in (-8, -2, 4):
    for x in (-3, 3):
        m.fill(x, F - 2, z, x, F + 2, z, "spruce_log")
        m.set(x, F + 3, z, "lantern")
m.set(-1, F + 1, -7, "barrel[facing=up]")
m.set(1, F + 1, -6, "barrel[facing=up]")
for x in (-5, 5):
    for y in range(F + 2, F + 6):
        m.set(x, y, r.randint(-8, 6), "cobweb")
m.start = [0.5, 64.0, -6.5, 0.0, 0.0]

# ---------------------------------------------------------------- room 1: the cove (x -16..16, z 8..36)
m.fill(-16, F + 1, 8, 16, F + 10, 36, "air")
m.fill(-16, F, 8, 16, F, 36, "sand")
for _ in range(140):
    m.set(r.randint(-16, 16), F, r.randint(8, 36), r.choice(["gravel", "clay", "sandstone", "suspicious_sand", "coarse_dirt"]))
# shallow pools (1 deep) with seagrass
for cx, cz, rad in ((-9, 16, 3), (10, 22, 3.5), (-6, 30, 2.5)):
    for z in range(int(cz - rad), int(cz + rad) + 1):
        half = int(math.sqrt(max(0, rad * rad - (z - cz) ** 2)))
        m.fill(cx - half, F, z, cx + half, F, z, "water")
        m.fill(cx - half, F - 1, z, cx + half, F - 1, z, "sand")
    m.set(cx, F, cz, "seagrass")
# openings to the sky
for x, z in ((-6, 14), (7, 30)):
    m.fill(x - 2, F + 11, z - 2, x + 2, 81, z + 2, "air")
# wrecked boats (hulls of planks), drying nets, crates
for x0, z0 in ((-14, 22), (11, 12)):
    m.fill(x0, F + 1, z0, x0 + 1, F + 1, z0 + 4, "spruce_planks")
    m.fill(x0, F + 2, z0, x0, F + 2, z0 + 4, "spruce_slab")
    m.set(x0 + 1, F + 2, z0 + 1, "spruce_fence")
    m.set(x0 + 1, F + 3, z0 + 1, "white_wool")
for x in (-15, 15):
    for z in range(10, 35, 3):
        if r.random() < 0.7:
            m.set(x, F + r.randint(2, 6), z, "cobweb")
for x, z in ((-12, 34), (12, 34), (13, 18), (-13, 10)):
    m.set(x, F + 1, z, r.choice(["barrel[facing=up]", "chest[facing=south]", "composter"]))
for x in (-16, 16):
    for z in (12, 20, 28):
        m.set(x, F + 4, z, "sea_lantern")
for x, z in ((-8, 22), (8, 16), (0, 26)):
    m.set(x, F + 9, z, "light[level=13]")
for x, z in ((-8, 14), (6, 18), (-3, 24), (9, 28), (-10, 32)):
    m.mob("pecheur_noye", x, z, yaw=180, room="1")
for x, z in ((3, 12), (-12, 26), (12, 32)):
    m.mob("pecheur_maudit", x, z, yaw=180, room="1")

# the way down: a pool (x -3..3, z 37..41) behind the gate, a shaft to the passage
m.fill(-4, F + 1, 36, 4, F + 6, 42, "air")
m.fill(-4, F, 36, 4, F, 42, "prismarine_bricks")
m.fill(-3, F - 12, 37, 3, F, 41, "water")
m.fill(-4, F - 13, 36, 4, F - 13, 42, "sand")
m.fill(-4, F + 1, 36, 4, F + 6, 36, "dark_prismarine")
m.fill(-3, F + 1, 36, 3, F + 5, 36, "spruce_planks")
m.fill(-3, F + 1, 36, -3, F + 5, 36, "stripped_spruce_log")
m.fill(3, F + 1, 36, 3, F + 5, 36, "stripped_spruce_log")
m.door("porte_crique", (-3, F + 1, 36), (3, F + 5, 36), room="1")
m.set(-4, F + 3, 42, "sea_lantern")
m.set(4, F + 3, 42, "sea_lantern")

# ---------------------------------------------------------------- room 2: the sunken passage (y 51..56, z 37..84)
T0, T1 = F - 12, F - 8          # water of the tunnel (5 high), floor under T0
m.fill(-3, T0, 37, 3, T1, 84, "water")
m.fill(-4, T0 - 1, 36, 4, T0 - 1, 85, "sand")
for _ in range(80):
    m.set(r.randint(-3, 3), T0 - 1, r.randint(38, 83), r.choice(["gravel", "prismarine", "clay"]))
for z in range(36, 86):
    for x in (-4, 4):
        m.fill(x, T0, z, x, T1 + 1, z, "prismarine_bricks" if z % 6 else "sea_lantern")
m.fill(-4, T1 + 1, 42, 4, T1 + 1, 84, "dark_prismarine")
# air pockets: the ceiling raised 2 blocks, the water still at T1
for zc in (54, 70):
    m.fill(-3, T1 + 1, zc - 2, 3, T1 + 2, zc + 2, "air")
    m.fill(-4, T1 + 3, zc - 3, 4, T1 + 3, zc + 3, "dark_prismarine")
    m.set(0, T1 + 2, zc, "light[level=12]")
# kelp and seagrass
for _ in range(36):
    x, z = r.randint(-3, 3), r.randint(40, 82)
    m.set(x, T0, z, r.choice(["seagrass", "seagrass", "kelp_plant", "sea_pickle[pickles=3,waterlogged=true]"]))
for x, z in ((-1, 47), (1, 58), (-2, 64), (2, 76)):
    m.mob("pecheur_gardien", x, z, y=T0 + 1, yaw=180, room="2")
# the gate under the water near the end, then the shaft up (x -3..3, z 79..84) into the grotto's pool
m.fill(-3, T0, 78, 3, T1, 78, "dark_prismarine")
m.door("porte_passage", (-3, T0, 78), (3, T1, 78), room="2")
m.fill(-3, T1 + 1, 79, 3, F, 84, "water")

# ---------------------------------------------------------------- room 3: the coral grotto (x -15..15, z 79..108)
m.fill(-15, F + 1, 79, 15, F + 11, 108, "air")
m.fill(-15, F, 79, 15, F, 108, "sand")
m.fill(-3, F, 79, 3, F, 84, "water")   # the shaft opens here
m.fill(-4, F, 78, 4, F, 78, "prismarine_bricks")
for _ in range(120):
    x, z = r.randint(-15, 15), r.randint(85, 108)
    m.set(x, F, z, r.choice(["prismarine", "dead_brain_coral_block", "dead_tube_coral_block", "gravel", "sandstone"]))
for _ in range(40):
    x, z = r.randint(-14, 14), r.randint(86, 107)
    h = r.randint(1, 4)
    m.fill(x, F + 1, z, x, F + h, z, r.choice(["dead_fire_coral_block", "dead_horn_coral_block", "dead_bubble_coral_block", "prismarine"]))
    m.set(x, F + h + 1, z, r.choice(["dead_fire_coral_fan", "dead_horn_coral", "dead_brain_coral_fan", "sea_pickle[pickles=4,waterlogged=false]"]))
for cx, cz in ((-9, 96), (9, 100)):
    for z in range(cz - 2, cz + 3):
        m.fill(cx - 2, F, z, cx + 2, F, z, "water")
    m.set(cx, F - 1, cz, "sea_lantern")
for x in (-15, 15):
    for z in (86, 96, 106):
        m.set(x, F + 5, z, "sea_lantern")
for x, z in ((0, 92), (-7, 102), (7, 88)):
    m.set(x, F + 10, z, "light[level=13]")
m.mob("pecheur_maitre", 0, 100, yaw=180, room="3")
for x, z in ((-8, 92), (8, 94)):
    m.mob("pecheur_noye", x, z, yaw=180, room="3")
for x, z in ((-5, 104), (5, 104)):
    m.mob("pecheur_maudit", x, z, yaw=180, room="3")
m.fill(-2, F + 1, 109, 2, F + 5, 109, "spruce_planks")
m.fill(-2, F + 1, 109, -2, F + 5, 109, "stripped_spruce_log")
m.fill(2, F + 1, 109, 2, F + 5, 109, "stripped_spruce_log")
m.door("porte_grotte", (-2, F + 1, 109), (2, F + 5, 109), room="3")

# ---------------------------------------------------------------- the ledge: up three steps to the drop (z 110..120)
m.fill(-2, F + 1, 110, 2, F + 8, 120, "air")
m.fill(-2, F, 110, 2, F, 120, "prismarine_bricks")
for k, z in enumerate((113, 114, 115)):
    m.fill(-2, F + 1, z, 2, F + 1 + k, z, "prismarine_bricks")
m.fill(-2, F + 1, 116, 2, F + 3, 120, "prismarine_bricks")   # ledge floor at F+3: players stand at F+4
for x in (-2, 2):
    m.set(x, F + 7, 118, "sea_lantern")

# ---------------------------------------------------------------- the tide pool: 23 x 23 inside, x -11..11, z 121..143
P0, P1, Q0, Q1 = -11, 11, 121, 143
m.fill(P0 - 1, F - 8, Q0 - 1, P1 + 1, F + 14, Q1 + 1, "prismarine_bricks")   # the box, walls 1 thick
m.fill(P0, F - 6, Q0, P1, F + 14, Q1, "air")
m.fill(P0, F - 6, Q0, P1, F - 6, Q1, "packed_ice")           # bottom
m.fill(P0, F - 5, Q0, P1, F - 1, Q1, "water")                # 5 blocks of water
m.fill(P0, F, Q0, P1, F, Q1, "ice")                          # frozen from wall to wall
for x in range(P0 + 2, P1 - 1, 5):
    for z in range(Q0 + 2, Q1 - 1, 5):
        m.set(x, F - 6, z, "sea_lantern")                    # 6 blocks under the ice
# the drop: the box wall opens under the ledge (x -2..2), the ledge floor at F+3 overhangs the ice by nothing
m.fill(-2, F + 4, Q0 - 1, 2, F + 8, Q0 - 1, "air")
# walls: dark bands, lanterns 6 blocks above the ice, barriers over the top
m.fill(P0 - 1, F + 5, Q0 - 1, P1 + 1, F + 5, Q1 + 1, "dark_prismarine", "replace prismarine_bricks")
for z in range(Q0 + 1, Q1, 4):
    m.set(P0 - 1, F + 7, z, "sea_lantern")
    m.set(P1 + 1, F + 7, z, "sea_lantern")
for x in range(P0 + 1, P1, 4):
    m.set(x, F + 7, Q1 + 1, "sea_lantern")
m.fill(P0 - 1, F + 15, Q0 - 1, P1 + 1, F + 15, Q1 + 1, "barrier")
m.fill(P0, F + 14, Q0, P1, F + 14, Q1, "air")
m.mob("loutre_marees", 0, 132, yaw=180, room="4")
m.objectives = ["loutre_marees"]

if __name__ == "__main__":
    maplib.run(m, sys.argv[1] if len(sys.argv) > 1 else "labs", "pecheur")
