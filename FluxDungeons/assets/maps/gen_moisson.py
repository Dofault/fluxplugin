#!/usr/bin/env python3
"""Map of the harvest dungeon, Le Champ du Moissonneur (boss: the Hamster Moissonneur), built by console commands.

Open countryside played from south to north (+z), ground at y 63, walls of hedges (oak leaves, 5 high):
  - the farm road (start at 0, 64, -6): a dirt path between hedges, a cart and a sign of the farm;
  - room 1, the Abandoned Fields (37 x 35): wheat and beetroot plots gone wild, scarecrows, haystacks, a well,
    an apple tree; scarecrows and field rats;
  - room 2, the Barn (25 x 29 under a gable roof): stalls, hay bales, a loft on the sides; possessed reapers, rats
    and the Great Scarecrow (mini-boss);
  - the field of the Hamster: the arena of the first version, copied block for block from the former map (its crops,
    fences, lanterns and haystacks: the boss' mechanics depend on them), entered through a gap in its fence.

Usage: gen_moisson.py [server]    (labs by default): builds, copies into maps/custom/moisson, writes edits/moisson.yml.
The former arena is read from moisson_arena.txt (its blocks, taken once from the first map).
"""
import os
import random
import sys

import maplib

F = 63
DZ = 84          # the former arena (z -4..36) is moved to z 80..120
r = random.Random(7)
m = maplib.Map()
m.box = (-30, -14, 30, 126)
ARENA = os.path.join(maplib.HERE, "moisson_arena.txt")

# ---------------------------------------------------------------- ground
m.fill(-28, F - 4, -12, 28, F - 1, 124, "dirt")
m.fill(-28, F, -12, 28, F, 124, "grass_block")
m.fill(-28, F - 5, -12, 28, F - 5, 124, "stone")


def hedge(x0, z0, x1, z1, h=5):
    m.fill(x0, F + 1, z0, x1, F + h, z1, "oak_leaves[persistent=true]")


# ---------------------------------------------------------------- road (z -10..7)
hedge(-6, -10, -4, 7)
hedge(4, -10, 6, 7)
hedge(-6, -11, 6, -11)
m.fill(-2, F, -10, 2, F, 7, "dirt_path")
for _ in range(12):
    m.set(r.randint(-3, 3), F, r.randint(-10, 7), "coarse_dirt")
m.fill(2, F + 1, -6, 3, F + 1, -4, "spruce_slab[type=bottom]")
m.set(3, F + 1, -3, "hay_block[axis=y]")
m.set(-3, F + 1, -2, "oak_fence")
m.set(-3, F + 2, -2, "lantern")
m.set(3, F + 1, 3, "oak_fence")
m.set(3, F + 2, 3, "lantern")
m.start = [0.5, 64.0, -6.5, 0.0, 0.0]

# ---------------------------------------------------------------- room 1: the abandoned fields (x -18..18, z 8..42)
hedge(-20, 8, -19, 44)
hedge(19, 8, 20, 44)
hedge(-20, 7, -4, 7)
hedge(4, 7, 20, 7)
hedge(-20, 43, -3, 44)
hedge(3, 43, 20, 44)
m.fill(-2, F, 7, 2, F, 44, "dirt_path")
# plots gone wild: farmland with crops (grown and not), weeds on their edges
for x0, z0, w, d, crop in ((-16, 11, 9, 7, "wheat"), (6, 11, 10, 6, "beetroots"), (-15, 24, 8, 9, "wheat"),
                           (7, 22, 9, 8, "wheat"), (-14, 36, 9, 5, "beetroots"), (6, 34, 10, 6, "wheat")):
    m.fill(x0, F, z0, x0 + w - 1, F, z0 + d - 1, "farmland[moisture=7]")
    for x in range(x0, x0 + w):
        for z in range(z0, z0 + d):
            if r.random() < 0.85:
                age = r.choice([3, 5, 7, 7]) if crop == "wheat" else r.choice([1, 2, 3, 3])
                m.set(x, F + 1, z, f"{crop}[age={age}]")
            elif r.random() < 0.5:
                m.set(x, F + 1, z, "short_grass")
for _ in range(60):
    x, z = r.randint(-18, 18), r.randint(8, 42)
    if abs(x) > 2:
        m.set(x, F + 1, z, r.choice(["short_grass", "short_grass", "fern", "dandelion", "poppy"]))
# scarecrows (decor), haystacks, a well, an apple tree
for x, z in ((-11, 19), (11, 30), (-5, 40)):
    m.fill(x, F + 1, z, x, F + 2, z, "oak_fence")
    m.set(x - 1, F + 2, z, "oak_fence")
    m.set(x + 1, F + 2, z, "oak_fence")
    m.set(x, F + 3, z, "carved_pumpkin[facing=south]")
for x, z in ((14, 18), (-16, 33), (15, 40)):
    m.fill(x, F + 1, z, x + 1, F + 1, z + 1, "hay_block[axis=y]")
    m.set(x, F + 2, z, "hay_block[axis=x]")
m.fill(-8, F, 31, -6, F, 33, "stone_bricks")
m.set(-7, F, 32, "water")
m.fill(-8, F + 1, 31, -8, F + 3, 31, "oak_fence")
m.fill(-6, F + 1, 33, -6, F + 3, 33, "oak_fence")
m.fill(-8, F + 4, 31, -6, F + 4, 33, "spruce_slab[type=bottom]")
m.fill(15, F + 1, 26, 15, F + 5, 26, "oak_log")
m.fill(13, F + 5, 24, 17, F + 7, 28, "oak_leaves[persistent=true]")
for x in (-17, 17):
    for z in (14, 26, 38):
        m.set(x, F + 1, z, "oak_fence")
        m.set(x, F + 2, z, "lantern")
for x, z in ((-12, 14), (4, 16), (-8, 26), (10, 26), (-2, 34), (12, 38)):
    m.mob("moisson_epouvantail", x, z, yaw=180, room="1")
for x, z in ((-14, 30), (9, 19), (0, 40)):
    m.mob("moisson_rat", x, z, yaw=180, room="1")
# gate 1 in the hedge (x -2..2, z 43..44)
m.fill(-2, F + 1, 44, 2, F + 4, 44, "spruce_planks")
m.fill(-2, F + 1, 44, -2, F + 4, 44, "stripped_spruce_log")
m.fill(2, F + 1, 44, 2, F + 4, 44, "stripped_spruce_log")
m.door("porte_champs", (-2, F + 1, 44), (2, F + 4, 44), room="1")
m.fill(-2, F + 1, 43, 2, F + 4, 43, "air")

# ---------------------------------------------------------------- room 2: the barn (x -12..12, z 46..74)
BX0, BX1, BZ0, BZ1 = -13, 13, 46, 75
m.fill(-3, F, 45, 3, F, 45, "dirt_path")
hedge(-20, 45, -14, 45)
hedge(14, 45, 20, 45)
m.fill(BX0, F, BZ0, BX1, F, BZ1, "spruce_planks")
m.fill(BX0, F + 1, BZ0, BX1, F + 7, BZ1, "spruce_planks")
m.fill(BX0 + 1, F + 1, BZ0 + 1, BX1 - 1, F + 7, BZ1 - 1, "air")
for x in (BX0, BX1):
    for z in (BZ0, BZ0 + 7, BZ0 + 14, BZ0 + 22, BZ1):
        m.fill(x, F + 1, z, x, F + 7, z, "stripped_spruce_log")
m.fill(BX0, F + 4, BZ0, BX1, F + 4, BZ1, "stripped_spruce_log[axis=x]", "replace spruce_planks")
# gable roof
for k in range(0, 15):
    y = F + 8 + k // 2
    x0, x1 = BX0 - 1 + k, BX1 + 1 - k
    if x0 > x1:
        break
    m.fill(x0, y, BZ0 - 1, x1, y, BZ1 + 1, "dark_oak_planks")
    if x1 - x0 > 2:
        m.fill(x0 + 1, y, BZ0, x1 - 1, y, BZ1, "air")
m.fill(BX0 + 1, F + 8, BZ0, BX1 - 1, F + 8, BZ1, "air")
m.fill(BX0, F + 8, BZ0, BX1, F + 8, BZ0, "spruce_planks")
m.fill(BX0, F + 8, BZ1, BX1, F + 8, BZ1, "spruce_planks")
# doors: the big entrance (south), the way out (north, gate 2)
m.fill(-2, F + 1, BZ0, 2, F + 5, BZ0, "air")
m.fill(-2, F + 1, BZ1, 2, F + 5, BZ1, "dark_oak_planks")
m.door("porte_grange", (-2, F + 1, BZ1), (2, F + 5, BZ1), room="2")
# stalls, hay, lofts (decor along the walls, the middle stays open)
for z in range(BZ0 + 3, BZ1 - 2, 5):
    for x0, x1 in ((BX0 + 1, BX0 + 4), (BX1 - 4, BX1 - 1)):
        m.fill(x0, F + 1, z, x1, F + 1, z, "spruce_fence")
for _ in range(16):
    side = r.choice((-1, 1))
    x = side * r.randint(9, 11)
    z = r.randint(BZ0 + 2, BZ1 - 2)
    m.fill(x, F + 1, z, x, F + r.randint(1, 3), z, "hay_block[axis=y]")
for x0, x1 in ((BX0 + 1, BX0 + 3), (BX1 - 3, BX1 - 1)):
    m.fill(x0, F + 5, BZ0 + 1, x1, F + 5, BZ1 - 1, "spruce_slab[type=bottom]")
for z in range(BZ0 + 4, BZ1, 6):
    m.set(0, F + 7, z, "lantern[hanging=true]")
    m.set(-6, F + 7, z, "light[level=12]")
    m.set(6, F + 7, z, "light[level=12]")
m.set(BX0 + 2, F + 1, BZ1 - 2, "composter")
m.set(BX1 - 2, F + 1, BZ1 - 2, "barrel[facing=up]")
m.set(BX1 - 2, F + 1, BZ0 + 2, "smoker[facing=west]")
m.mob("moisson_grand_epouvantail", 0, 66, yaw=180, room="2")
for x, z in ((-6, 56), (6, 60), (-5, 70)):
    m.mob("moisson_faucheur", x, z, yaw=180, room="2")
for x, z in ((4, 52), (7, 70)):
    m.mob("moisson_rat", x, z, yaw=180, room="2")

# ---------------------------------------------------------------- path to the arena (z 76..79)
hedge(-20, 76, -4, 79)
hedge(4, 76, 20, 79)
m.fill(-2, F, 76, 2, F, 81, "dirt_path")

# ---------------------------------------------------------------- the arena, copied from the first map (z -4..36 -> +DZ)
copied = 0
for line in open(ARENA):
    if line.startswith("#") or not line.strip():
        continue
    x, y, z, b = line.split(maxsplit=3)
    x, y, z = int(x), int(y), int(z)
    if (x, z) in ((-3, 0), (3, 0)):  # the composter and barrel of the former start
        continue
    m.set(x, y, z + DZ, b.strip())
    copied += 1
# a gap in its southern fence (gate in front of the barn), hedges all around, no way out by the fields
m.fill(-2, F + 1, -4 + DZ, 2, F + 2, -4 + DZ, "air")
hedge(-20, 80, -18, 122)
hedge(18, 80, 20, 122)
hedge(-20, 121, 20, 122)
m.mob("hamster_moissonneur", 0, 18 + DZ, yaw=180, room="3")
m.objectives = ["hamster_moissonneur"]

if __name__ == "__main__":
    print(copied, "blocks of the former arena")
    maplib.run(m, sys.argv[1] if len(sys.argv) > 1 else "labs", "moisson")
