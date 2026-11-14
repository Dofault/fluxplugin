#!/usr/bin/env python3
"""Map of the harvest dungeon, Le Domaine des Cultivateurs (boss: the Hamster Moissonneur), modelled in memory
(gridlib), checked, then built by console commands with the RPG look of the dungeons.

An open-air farm estate played from south to north (+z), ground at y 63, in a valley of fields, streams and woods
closed by hills and snowy mountains. The map of 2026-11-12, enlarged on 2026-11-14 (rooms about twice as large, same
monsters: more room to fight):
  - the farm road (start at 0, 64, -6);
  - room 1, the Scarecrows' Field (45 x 45): wheat and beetroot rows on the sides, decor scarecrows, a gardener's
    shelter, carts, a wheelbarrow, hay, barrels, two wells; open 27 x 45 middle. Scarecrows and field rats;
  - room 2, the Great Harvest Barn (47 x 45 inside, in a 61-wide yard): stone base, plank walls, a low gable roof
    (one block up every two), sliding doors, lofts on both sides over the storage, lanterns on the tie beams; open
    39 x 43 middle. Possessed reapers, rats and the Great Scarecrow (mini-boss);
  - room 3, the Market Gardeners' Plot (51 x 51): carrots, potatoes, beetroots, wheat, pumpkins and melons in plots
    along the sides, irrigation ditches, crates of vegetables; a cabin and a greenhouse beyond the fences. Raging
    rabbits, corrupted hens, rats;
  - room 4, the Mill and the Silos (65 x 60): a 13 x 13 windmill 27 high with four sails facing the way in, four
    brick silos, a flour store; the Possessed Miller (mini-boss), reapers and scarecrows;
  - room 5, the Great Hamster's Pen: the boss arena of the first version (its fences, lanterns and haystacks kept
    where they were), now one potato field from fence to fence, entered by the gap of its
    southern fence, left by a gate in its northern fence when the boss dies; around it food stores, burrows and a
    giant hamster wheel, out of reach.
Between rooms, tall hedges (the next room stays hidden); on the landscape side, low fences under invisible barriers
(the countryside stays in view).

Usage: gen_moisson.py [server] [--check] [--preview DIR] [--edits]     (labs by default)
"""
import math
import os
import sys

import gridlib
import maplib

F = 63
DZ = 226                         # the former arena (z -4..36) is moved to z 222..262
X0, X1, Y0, Y1, Z0, Z1 = -86, 86, 50, 112, -50, 300
BANDS = ((50, 58, "stone"), (59, 62, "dirt"), (63, 63, "grass_block"))
CURTAIN = F + 12                 # top of the invisible barriers
ARENA = os.path.join(maplib.HERE, "moisson_arena.txt")
SOWN = ("grass_block", "coarse_dirt", "dirt_path")     # ground of the pen turned into field
EXIT_GATE = "spruce_fence_gate[facing=south,in_wall=false,open=false]"

g = gridlib.Model((X0, X1, Y0, Y1, Z0, Z1), BANDS, seed=7, outside="barrier")
r = g.r
pick = g.pick
m = maplib.Map()
m.box = (X0, Z0, X1, Z1)
_door = m.door


def door(id, frm, to, room):
    g.doors.append((tuple(min(a, b) for a, b in zip(frm, to)), tuple(max(a, b) for a, b in zip(frm, to))))
    _door(id, frm, to, room=room)


LEAVES = "oak_leaves[persistent=true]"


# ======================================================================== small builders

def hedge(x0, z0, x1, z1, h=5):
    g.box(x0, F + 1, z0, x1, F + h, z1, lambda *a: LEAVES if r.random() < 0.9 else "azalea_leaves[persistent=true]")
    curtain(x0, z0, x1, z1, F + h + 1)


def curtain(x0, z0, x1, z1, y0=F + 2):
    g.box(x0, y0, z0, x1, CURTAIN, z1, "barrier", where=lambda b: b == "air" or gridlib.passable(b))


def fence_edge(x0, z0, x1, z1, kind="oak_fence"):
    """The edge of a room on the landscape side: a low fence, invisible barriers over it."""
    g.box(x0, F + 1, z0, x1, F + 1, z1, kind)
    curtain(x0, z0, x1, z1)


def flowers(x0, z0, x1, z1, n, table=None):
    table = table or [("short_grass", 10), ("fern", 3), ("dandelion", 2), ("poppy", 2), ("cornflower", 1),
                      ("oxeye_daisy", 1), ("azure_bluet", 1)]
    for _ in range(n):
        x, z = r.randint(x0, x1), r.randint(z0, z1)
        if g.get(x, F + 1, z) == "air" and g.get(x, F, z) == "grass_block":
            g.set(x, F + 1, z, pick(table))


def crop_plot(x0, z0, x1, z1, crop, wild=0.0):
    ages = {"wheat": 7, "carrots": 7, "potatoes": 7, "beetroots": 3}
    for x in range(min(x0, x1), max(x0, x1) + 1):
        for z in range(min(z0, z1), max(z0, z1) + 1):
            g.set(x, F, z, "farmland[moisture=7]")
            if r.random() < wild:
                g.set(x, F + 1, z, r.choice(["short_grass", "short_grass", "dead_bush"]))
                g.set(x, F, z, "coarse_dirt")
            else:
                top = ages[crop]
                age = top if r.random() < 0.7 else r.randint(max(0, top - 4), top)
                g.set(x, F + 1, z, f"{crop}[age={age}]")


def gourd_plot(x0, z0, x1, z1, fruit):
    """Pumpkins or melons: rows of stems on farmland, the fruit beside each stem."""
    for z in range(z0, z1 + 1):
        for x in range(x0, x1 + 1):
            g.set(x, F, z, "farmland[moisture=7]" if (x - x0) % 3 == 0 else "dirt")
            if (x - x0) % 3 == 0:
                if r.random() < 0.6 and x + 1 <= x1:
                    g.set(x, F + 1, z, f"attached_{fruit}_stem[facing=east]")
                    g.set(x + 1, F + 1, z, fruit)
                else:
                    g.set(x, F + 1, z, f"{fruit}_stem[age=7]")


def scarecrow(x, z, facing="south"):
    g.box(x, F + 1, z, x, F + 2, z, "spruce_fence")
    g.set(x - 1, F + 2, z, "spruce_fence")
    g.set(x + 1, F + 2, z, "spruce_fence")
    g.set(x, F + 3, z, f"carved_pumpkin[facing={facing}]")
    if r.random() < 0.5:
        g.set(x, F + 4, z, "cobweb")


def hay(x, z, h):
    for y in range(F + 1, F + 1 + h):
        g.set(x, y, z, "hay_block[axis=y]")


def barrels(x, z, n, top_items=None):
    for i in range(n):
        g.set(x, F + 1 + i, z, "barrel[facing=up]")
    if top_items:
        g.item_display(x + 0.5, F + 1 + n + 0.25, z + 0.5, r.choice(top_items), yaw=r.uniform(0, 360), scale=0.7)


def crate(x, z, items=None):
    g.set(x, F + 1, z, "spruce_planks")
    g.set(x, F + 2, z, "spruce_trapdoor[half=bottom,facing=north]")
    if items:
        g.item_display(x + 0.5, F + 2.35, z + 0.5, r.choice(items), yaw=r.uniform(0, 360), scale=0.6)


def sacks(x, z, n, kind="white_wool"):
    for i in range(n):
        g.set(x, F + 1 + i, z, kind if r.random() < 0.7 else "light_gray_wool")


def cart(x, z, axis="z", load="hay"):
    """A farm cart, 2 x 3, wheels on its sides, a load and its shafts."""
    long = [(x, z + i) if axis == "z" else (x + i, z) for i in range(3)]
    side = (1, 0) if axis == "z" else (0, 1)
    for cx, cz in long:
        for k in (0, 1):
            px, pz = cx + side[0] * k, cz + side[1] * k
            g.set(px, F + 1, pz, "spruce_slab[type=top]")
            if load == "hay" and r.random() < 0.8:
                g.set(px, F + 2, pz, "hay_block[axis=y]")
            elif load == "pumpkin":
                g.set(px, F + 2, pz, r.choice(["pumpkin", "melon", "spruce_slab[type=bottom]"]))
    mid = long[1]
    face_a = "west" if axis == "z" else "north"
    face_b = "east" if axis == "z" else "south"
    g.set(mid[0] - side[0], F + 1, mid[1] - side[1], f"dark_oak_trapdoor[facing={face_a},open=true,half=bottom]")
    g.set(mid[0] + 2 * side[0], F + 1, mid[1] + 2 * side[1], f"dark_oak_trapdoor[facing={face_b},open=true,half=bottom]")
    tip = (long[-1][0] + (0 if axis == "z" else 1), long[-1][1] + (1 if axis == "z" else 0))
    g.set(tip[0], F + 1, tip[1], "spruce_fence")


def wheelbarrow(x, z):
    g.set(x, F + 1, z, "spruce_trapdoor[half=top,facing=north]")
    g.set(x, F + 1, z + 1, "spruce_fence")
    g.item_display(x + 0.5, F + 1.3, z + 0.5, "iron_shovel", yaw=30, roll=60, scale=0.8)


def well(x, z):
    g.box(x - 1, F, z - 1, x + 1, F + 1, z + 1, "cobblestone")
    g.box(x, F - 3, z, x, F + 1, z, "water")
    g.box(x - 1, F - 4, z - 1, x + 1, F - 4, z + 1, "stone")
    for dx, dz in ((-1, -1), (1, 1)):
        g.box(x + dx, F + 2, z + dz, x + dx, F + 3, z + dz, "spruce_fence")
    g.box(x - 1, F + 4, z - 1, x + 1, F + 4, z + 1, "spruce_slab[type=bottom]")
    g.set(x, F + 3, z, "iron_chain[axis=y]")


def tool(x, y, z, item, yaw, roll=35):
    g.item_display(x, y, z, item, yaw=yaw, roll=roll, scale=1.1)


def tree(x, z, y0=F + 1, kind="oak"):
    if kind == "spruce":
        h = r.randint(7, 10)
        g.box(x, y0, z, x, y0 + h - 1, z, "spruce_log")
        for i in range(h - 2):
            rad = max(0, (h - 2 - i) // 3 + (1 if i % 2 == 0 else 0))
            yy = y0 + 2 + i
            for dx in range(-rad, rad + 1):
                for dz in range(-rad, rad + 1):
                    if abs(dx) + abs(dz) <= rad + 1 and g.get(x + dx, yy, z + dz) == "air":
                        g.set(x + dx, yy, z + dz, "spruce_leaves[persistent=true]")
        g.set(x, y0 + h, z, "spruce_leaves[persistent=true]")
        return
    log, leaves = {"oak": ("oak_log", LEAVES), "birch": ("birch_log", "birch_leaves[persistent=true]"),
                   "fruit": ("oak_log", "flowering_azalea_leaves[persistent=true]"),
                   "dark_oak": ("dark_oak_log", "dark_oak_leaves[persistent=true]")}[kind]
    h = r.randint(4, 6) if kind != "fruit" else 3
    g.box(x, y0, z, x, y0 + h - 1, z, log)
    rad = 2 if kind != "dark_oak" else 3
    cy = y0 + h
    for dx in range(-rad, rad + 1):
        for dy in range(-1, 3):
            for dz in range(-rad, rad + 1):
                d = dx * dx + dz * dz + (dy * 1.6) ** 2
                if d <= rad * rad + 1.5 and r.random() < 0.92 and g.get(x + dx, cy + dy, z + dz) == "air":
                    g.set(x + dx, cy + dy, z + dz, leaves)


def gate_arch(x0, x1, z, h=5):
    """The timber arch over a gate: two posts, a beam, a lantern."""
    g.box(x0 - 1, F + 1, z, x0 - 1, F + h, z, "spruce_log")
    g.box(x1 + 1, F + 1, z, x1 + 1, F + h, z, "spruce_log")
    g.box(x0 - 1, F + h + 1, z, x1 + 1, F + h + 1, z, "stripped_spruce_log[axis=x]")
    g.set((x0 + x1) // 2, F + h, z, "lantern[hanging=true]")


# ======================================================================== the landscape around the estate

def terrain():
    n1, n2 = gridlib.Noise(101), gridlib.Noise(202)
    heights = {}
    for x in range(X0, X1 + 1):
        for z in range(Z0, Z1 + 1):
            if abs(x) <= 37 and -14 <= z <= 270:
                continue
            dx = max(0, abs(x) - 58)
            dz = max(0, -10 - z, z - 274)
            d = math.hypot(dx, dz)
            if d == 0:
                continue
            hills = d * 0.45 * (1 + 0.5 * n1(x * 0.6, 0, z * 0.6))
            mount = max(0.0, d - 9) * 1.5 * (0.75 + 0.5 * n2(x * 0.35, 0, z * 0.35))
            h = F + int(min(48, hills + mount))
            heights[(x, z)] = h
    for (x, z), h in heights.items():
        steep = max(abs(h - heights.get((x + a, z + b), h)) for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        for y in range(F, h + 1):
            depth = h - y
            if h >= F + 33:
                top = "snow_block" if h >= F + 36 or r.random() < 0.6 else "stone"
                b = top if depth == 0 else pick([("stone", 6), ("andesite", 2), ("cobblestone", 1)])
            elif h >= F + 20 or steep >= 3:
                b = pick([("stone", 6), ("andesite", 2), ("cobblestone", 1), ("gravel", 1)]) if depth else \
                    pick([("stone", 5), ("andesite", 2), ("mossy_cobblestone", 1)])
                if b == "gravel":
                    b = "stone"
            else:
                b = "grass_block" if depth == 0 else ("dirt" if depth < 4 else "stone")
            g.set(x, y, z, b)
    # trees and woods on the slopes, forests further
    for (x, z), h in heights.items():
        d = math.hypot(max(0, abs(x) - 58), max(0, -10 - z, z - 274))
        if h >= F + 22 or g.get(x, h, z) != "grass_block":
            continue
        dens = 0.03 if d < 6 else (0.11 if d < 22 else 0.05)
        if r.random() < dens:
            kind = r.choice(["oak", "birch", "oak"]) if d < 8 else r.choice(["spruce", "spruce", "dark_oak", "oak"])
            tree(x, z, h + 1, kind)
        elif r.random() < 0.05:
            g.set(x, h + 1, z, r.choice(["short_grass", "fern", "sweet_berry_bush[age=3]"]))


def fields():
    """Fields, a stream and hedgerows in the flat bands either side of the estate (out of reach)."""
    crops = ["wheat", "wheat", "carrots", "potatoes", "beetroots"]
    for sx in (-1, 1):
        x_lo, x_hi = (40, 56)
        for zb in range(-8, 272, 22):
            for xb in range(x_lo, x_hi - 4, 7):
                c = r.choice(crops)
                xa, xb2 = sx * xb, sx * (xb + 5)
                crop_plot(min(xa, xb2), zb, max(xa, xb2), zb + 18, c, wild=0.04)
            for xx in range(x_lo, x_hi + 1):
                g.set(sx * xx, F, zb + 20, "dirt_path")
            if r.random() < 0.6:
                hx = sx * r.randint(x_lo, x_hi - 2)
                hay(hx, zb + 20 + 1 if False else zb + 19, r.randint(1, 2))
        # the stream between the fields and the hills
        sx_stream = sx * 58
        for z in range(-8, 273):
            g.set(sx_stream, F, z, "water")
            g.set(sx_stream, F - 1, z, "gravel" if r.random() < 0.5 else "sand")
            if r.random() < 0.08:
                g.set(sx_stream, F + 1, z, "lily_pad")
        # hedgerow along the estate
        for z in range(-12, 270, 5):
            if r.random() < 0.55:
                tree(sx * 38, z + r.randint(0, 3), kind=r.choice(["oak", "birch", "fruit"]))
            else:
                g.box(sx * 38, F + 1, z, sx * 38, F + r.randint(1, 2), z + 1, LEAVES)
    # two small farmsteads out in the fields
    for (bx, bz) in ((-48, 80), (48, 190)):
        g.box(bx - 3, F + 1, bz - 3, bx + 3, F + 4, bz + 3, "spruce_planks")
        g.box(bx - 2, F + 1, bz - 2, bx + 2, F + 4, bz + 2, "air")
        for k in range(4):
            g.box(bx - 4 + k, F + 5 + k, bz - 4, bx - 4 + k, F + 5 + k, bz + 4, "dark_oak_stairs[facing=east]")
            g.box(bx + 4 - k, F + 5 + k, bz - 4, bx + 4 - k, F + 5 + k, bz + 4, "dark_oak_stairs[facing=west]")
        g.box(bx, F + 8, bz - 4, bx, F + 8, bz + 4, "dark_oak_planks")
        g.box(bx - 3, F + 5, bz - 3, bx + 3, F + 7, bz - 3, "spruce_planks", where=lambda b: b == "air")
        g.box(bx - 3, F + 5, bz + 3, bx + 3, F + 7, bz + 3, "spruce_planks", where=lambda b: b == "air")
        g.box(bx, F + 1, bz - 3, bx, F + 2, bz - 3, "air")
        hay(bx + 5, bz, 2)


# ======================================================================== the road and room 1

def road():
    g.box(-2, F, -12, 2, F, 7, lambda *a: "dirt_path" if r.random() < 0.85 else "coarse_dirt")
    fence_edge(-3, -12, -3, 6)
    fence_edge(3, -12, 3, 6)
    hedge(-6, -13, 6, -13, 4)
    flowers(-9, -12, -4, 6, 25)
    flowers(4, -12, 9, 6, 25)
    cart(5, -6, "z", "hay")
    g.set(-5, F + 1, -2, "spruce_fence")
    g.set(-5, F + 2, -2, "lantern")
    g.set(5, F + 1, 3, "spruce_fence")
    g.set(5, F + 2, 3, "lantern")
    g.set(-5, F + 1, 3, "oak_sign[rotation=4]")
    m.start = [0.5, 64.0, -6.5, 0.0, 0.0]


def room1():
    """The Scarecrows' Field, 45 x 45 (x -22..22, z 8..52), open 27 x 45 middle."""
    fence_edge(-23, 7, -23, 52)
    fence_edge(23, 7, 23, 52)
    fence_edge(-23, 7, -3, 7)
    fence_edge(3, 7, 23, 7)
    hedge(-23, 53, 23, 55)
    # ground: grass, a dirt and gravel cross of paths, rows of crops on the sides
    g.box(-2, F, 8, 2, F, 52, lambda *a: pick([("dirt_path", 7), ("coarse_dirt", 2), ("gravel", 1)]))
    g.box(-22, F, 29, 22, F, 30, lambda *a: pick([("dirt_path", 5), ("coarse_dirt", 2), ("grass_block", 2)]))
    for sx in (-1, 1):
        for i, (z0, z1) in enumerate(((12, 26), (33, 50))):
            x0, x1 = (14, 21) if sx > 0 else (-21, -14)
            crop_plot(x0, z0, x1, z1, "wheat" if (i + (sx > 0)) % 2 else "beetroots", wild=0.15)
    flowers(-22, 8, 22, 52, 140)
    # decor scarecrows in the crops
    for x, z, f in ((-18, 18, "east"), (18, 42, "west"), (-17, 44, "east"), (17, 16, "west")):
        scarecrow(x, z, f)
    # the gardener's shelter (south-west corner)
    g.box(-22, F + 1, 8, -19, F + 3, 10, "spruce_planks")
    g.box(-21, F + 1, 9, -19, F + 2, 9, "air")
    g.box(-22, F + 1, 8, -22, F + 3, 8, "spruce_log")
    g.box(-19, F + 1, 8, -19, F + 3, 8, "spruce_log")
    g.box(-19, F + 1, 10, -19, F + 3, 10, "spruce_log")
    g.box(-22, F + 4, 8, -19, F + 4, 10, "spruce_slab[type=bottom]")
    g.set(-21, F + 1, 9, "composter[level=5]")
    tool(-20.5, F + 1.8, 8.6, "iron_hoe", yaw=180)
    tool(-19.4, F + 1.8, 9.5, "stone_shovel", yaw=90, roll=-30)
    # carts, wheelbarrow, hay, barrels, wells
    cart(18, 31, "x", "pumpkin")
    cart(-12, 9, "x", "hay")
    wheelbarrow(-17, 27)
    for x, z, h in ((21, 9, 2), (20, 9, 1), (-21, 51, 2), (21, 51, 1), (-21, 31, 1), (13, 51, 2), (12, 51, 1), (-13, 51, 1)):
        hay(x, z, h)
    barrels(21, 28, 2, ["wheat", "carrot"])
    barrels(-21, 28, 1, ["beetroot"])
    barrels(13, 9, 2, ["wheat", "potato"])
    well(-12, 36)
    well(12, 22)
    # light
    for x, z in ((-22, 18), (22, 18), (-22, 42), (22, 42), (-22, 30), (22, 30)):
        g.set(x, F + 1, z, "spruce_fence")
        g.set(x, F + 2, z, "lantern")
    # the gate to the barn
    g.box(-2, F + 1, 54, 2, F + 5, 55, "air")
    g.box(-2, F, 54, 2, F, 57, "dirt_path")
    g.box(-2, F + 1, 53, 2, F + 3, 53, "spruce_fence")
    g.box(-2, F + 4, 53, 2, F + 4, 53, "spruce_slab[type=bottom]")
    door("porte_champs", (-2, F + 1, 53), (2, F + 4, 53), room="1")
    gate_arch(-2, 2, 52)
    for x, z in ((-10, 16), (7, 19), (-8, 34), (10, 36), (-4, 45), (9, 45)):
        m.mob("moisson_epouvantail", x, z, yaw=180, room="1")
    for x, z in ((-11, 41), (11, 26), (0, 27)):
        m.mob("moisson_rat", x, z, yaw=180, room="1")
    m.sound(0, F + 6, 30, [("item.elytra.flying", 0.1, 1.5), ("block.azalea_leaves.step", 0.5, 0.9)], radius=26, every=(150, 300),
            particle={"type": "FALLING_SPORE_BLOSSOM", "count": 3, "spread": (10, 2, 10), "speed": 0, "every": 20})
    m.sound(-18, F + 3, 40, [("entity.parrot.ambient", 0.2, 0.5), ("block.grass.step", 0.5, 0.6)], radius=16, every=(200, 380))


# ======================================================================== room 2: the barn

BX0, BX1, BZ0, BZ1 = -24, 24, 57, 103      # 47 x 45 inside


def roof_y(x):
    """Height of the barn roof over column x: one block up every two (slabs)."""
    return F + 10 + (min(x - (BX0 - 1), (BX1 + 1) - x)) // 2


def room2():
    """The Great Harvest Barn, 47 x 45 inside in a 61 x 47 yard (out of reach), open 39 x 43 middle."""
    hedge(-31, 56, -3, 56, 5)
    hedge(3, 56, 31, 56, 5)
    hedge(-31, 57, -31, 103)
    hedge(31, 57, 31, 103)
    g.box(-30, F, 57, 30, F, 103, lambda *a: pick([("grass_block", 6), ("coarse_dirt", 2), ("dirt", 1)]))
    flowers(-30, 57, -26, 103, 40)
    flowers(26, 57, 30, 103, 40)
    for z in (61, 70, 79, 88, 97):
        hay(-28, z, r.randint(1, 3))
        hay(28, z + 2, r.randint(1, 2))
    cart(27, 64, "z")
    cart(-29, 92, "z", "pumpkin")

    def wall(x, y, z):
        if y <= F + 2:
            return pick([("cobblestone", 5), ("stone_bricks", 3), ("mossy_cobblestone", 1)])
        return "spruce_planks" if (x + z) % 7 else "oak_planks"

    g.box(BX0, F + 1, BZ0, BX1, F + 9, BZ1, wall)
    g.box(BX0 + 1, F + 1, BZ0 + 1, BX1 - 1, F + 9, BZ1 - 1, "air")
    g.box(BX0 + 1, F, BZ0 + 1, BX1 - 1, F, BZ1 - 1,
          lambda x, y, z: pick([("coarse_dirt", 4), ("dirt", 3), ("dirt_path", 2)]) if abs(x) < 20 else "spruce_planks")
    # posts and beams
    zs = list(range(BZ0, BZ1, 6)) + [BZ1]
    xs = list(range(BX0, BX1 + 1, 6))
    for z in zs:
        for x in (BX0, BX1):
            g.box(x, F + 1, z, x, F + 9, z, "spruce_log")
    for x in xs:
        for z in (BZ0, BZ1):
            g.box(x, F + 1, z, x, F + 9, z, "spruce_log")
    for z in (BZ0, BZ1):
        g.box(BX0, F + 9, z, BX1, F + 9, z, "stripped_spruce_log[axis=x]")
    for x in (BX0, BX1):
        g.box(x, F + 9, BZ0, x, F + 9, BZ1, "stripped_spruce_log[axis=z]")
    # windows
    for z in range(BZ0 + 3, BZ1 - 1, 6):
        for x in (BX0, BX1):
            g.box(x, F + 5, z, x, F + 6, z + 1, "glass_pane[north=true,south=true]")
    # gable roof along z, one block up every two (a 1:1 roof over 49 blocks would be 25 high)
    for x in range(BX0 - 1, BX1 + 2):
        i = min(x - (BX0 - 1), (BX1 + 1) - x)
        y = roof_y(x)
        if x == 0:
            g.box(x, y, BZ0 - 1, x, y, BZ1 + 1, "dark_oak_planks")
        else:
            g.box(x, y, BZ0 - 1, x, y, BZ1 + 1, f"dark_oak_slab[type={'bottom' if i % 2 == 0 else 'top'}]")
        for z in (BZ0, BZ1):
            if BX0 < x < BX1 and y - 1 >= F + 10:
                g.box(x, F + 10, z, x, y - 1, z, "spruce_log" if x % 4 == 0 else "spruce_planks")
    # hay-loft openings in both gables (light, out of reach)
    for z in (BZ0, BZ1):
        g.box(-2, F + 13, z, 2, F + 17, z, "air")
        g.box(-3, F + 12, z, 3, F + 12, z, "stripped_spruce_log[axis=x]")
    # tie beams with lanterns, king posts up to the ridge
    for z in range(BZ0 + 4, BZ1, 6):
        g.box(BX0 + 1, F + 9, z, BX1 - 1, F + 9, z, "stripped_spruce_log[axis=x]")
        g.box(0, F + 10, z, 0, roof_y(0) - 1, z, "spruce_fence")
        for x in (-14, -7, 0, 7, 14):
            g.set(x, F + 8, z, "iron_chain[axis=y]")
            g.set(x, F + 7, z, "lantern[hanging=true]")
    # lofts on both sides, over the storage
    for sx in (-1, 1):
        xa, xb = sorted((20 * sx, 23 * sx))
        g.box(xa, F + 5, BZ0 + 1, xb, F + 5, BZ1 - 1, "spruce_slab[type=top]")
        g.box(20 * sx, F + 6, BZ0 + 1, 20 * sx, F + 6, BZ1 - 1, "spruce_fence")
        for z in range(BZ0 + 3, BZ1, 6):
            g.box(20 * sx, F + 1, z, 20 * sx, F + 4, z, "spruce_log")
        for z in range(BZ0 + 2, BZ1 - 1, 2):
            if r.random() < 0.6:
                g.set(23 * sx, F + 6, z, "hay_block[axis=y]")
                if r.random() < 0.5:
                    g.set(23 * sx, F + 7, z, "hay_block[axis=y]")
            elif r.random() < 0.5:
                g.set(22 * sx, F + 6, z, "white_wool")
        g.box(23 * sx, F + 1, BZ0 + 2, 23 * sx, F + 5, BZ0 + 2,
              f"ladder[facing={'east' if sx < 0 else 'west'}]")
        g.set(23 * sx, F + 5, BZ0 + 2, "air")
        # storage under the lofts
        for z in range(BZ0 + 3, BZ1 - 1):
            k = r.random()
            x = r.choice((23, 23, 22, 21)) * sx
            if g.get(x, F + 1, z) != "air":
                continue
            if k < 0.35:
                hay(x, z, r.randint(1, 3))
            elif k < 0.5:
                barrels(x, z, r.randint(1, 2), ["wheat", "potato", "carrot"])
            elif k < 0.65:
                sacks(x, z, r.randint(1, 2))
            elif k < 0.75:
                crate(x, z, ["apple", "carrot", "beetroot"])
        for dz in (9, 21, 33):
            tool(23 * sx + (0.4 if sx < 0 else 0.6), F + 3.2, BZ0 + dz + 0.5, "trident" if dz == 21 else "iron_hoe",
                 yaw=90 if sx < 0 else -90, roll=10 if dz == 21 else 35)
    for x in range(-16, 17, 8):
        for z in range(BZ0 + 5, BZ1, 8):
            g.set(x, F + 6, z, "light[level=13]")
    # the big doorway (south) with its sliding doors pushed aside, the gate to the plot (north)
    g.box(-3, F + 1, BZ0, 3, F + 6, BZ0, "air")
    g.box(-3, F + 1, BZ0 - 1, 3, F + 6, BZ0 - 1, "air")
    g.box(-2, F, 54, 2, F, BZ0, "dirt_path")
    for sx in (-1, 1):
        xa, xb = sorted((4 * sx, 9 * sx))
        g.box(xa, F + 1, BZ0 - 1, xb, F + 6, BZ0 - 1, lambda x, y, z: "dark_oak_planks" if (x + y) % 5 else "spruce_log")
        curtain(xa, BZ0 - 1, xb, BZ0 - 1, F + 7)
    g.box(-10, F + 7, BZ0 - 1, 10, F + 7, BZ0 - 1, "stripped_spruce_log[axis=x]")
    g.box(-2, F + 1, BZ1, 2, F + 5, BZ1, lambda x, y, z: "spruce_log" if abs(x) == 2 else "dark_oak_planks")
    door("porte_grange", (-2, F + 1, BZ1), (2, F + 5, BZ1), room="2")
    m.mob("moisson_grand_epouvantail", 0, 86, yaw=180, room="2")
    for x, z in ((-11, 68), (11, 74), (-9, 95)):
        m.mob("moisson_faucheur", x, z, yaw=180, room="2")
    for x, z in ((7, 66), (13, 93)):
        m.mob("moisson_rat", x, z, yaw=180, room="2")
    m.sound(0, F + 7, 80, [("block.wooden_door.close", 0.3, 0.5), ("block.chest.open", 0.3, 0.5), ("block.wood.step", 0.5, 0.5)],
            radius=26, every=(140, 280), particle={"type": "WHITE_ASH", "count": 8, "spread": (12, 3, 12), "speed": 0, "every": 20})
    m.sound(-21, F + 2, 70, [("entity.chicken.ambient", 0.3, 1.0), ("entity.cow.ambient", 0.25, 0.9)], radius=14, every=(180, 340))


# ======================================================================== room 3: the market garden

def room3():
    """The Market Gardeners' Plot, 51 x 51 (x -25..25, z 107..157), open 31 x 51 middle."""
    hedge(-31, 104, -3, 106)
    hedge(3, 104, 31, 106)
    g.box(-2, F, 104, 2, F, 107, "dirt_path")
    fence_edge(-26, 107, -26, 157)
    fence_edge(26, 107, 26, 157)
    hedge(-33, 158, 33, 159)
    # paths: the way through and a cross
    g.box(-2, F, 107, 2, F, 157, lambda *a: pick([("dirt_path", 8), ("coarse_dirt", 1), ("gravel", 1)]))
    g.box(-15, F, 131, 15, F, 132, lambda *a: pick([("dirt_path", 8), ("coarse_dirt", 2)]))
    # plots on the sides, a ditch between each
    plots_w = (("carrots", 109, 122), ("potatoes", 125, 138), ("beetroots", 141, 154))
    plots_e = (("wheat", 109, 122), ("pumpkin", 125, 138), ("melon", 141, 154))
    for plots, x0, x1 in ((plots_w, -24, -16), (plots_e, 16, 24)):
        for crop, z0, z1 in plots:
            if crop in ("pumpkin", "melon"):
                gourd_plot(x0, z0, x1, z1, crop)
            else:
                crop_plot(x0, z0, x1, z1, crop)
        for zc in (123, 139):
            g.box(x0, F, zc, x1, F, zc, "water")
            g.box(x0, F - 1, zc, x1, F - 1, zc, "clay")
        for zp in (124, 140, 108, 155):
            g.box(x0, F, zp, x1, F, zp, "dirt_path")
    for x, z in ((-14, 109), (14, 155)):
        g.set(x, F, z, "water")
        g.set(x, F - 1, z, "clay")
        g.set(x, F + 1, z, "lily_pad")
    # crates of vegetables, composters, wheelbarrow, tools at the plot corners
    for (x, z), items in (((-15, 123), ["carrot"]), ((-15, 139), ["potato", "baked_potato"]), ((15, 123), ["wheat", "bread"]),
                          ((15, 139), ["pumpkin_pie", "melon_slice"])):
        crate(x, z, items)
    g.set(-15, F + 1, 156, "composter[level=7]")
    g.set(15, F + 1, 108, "composter[level=3]")
    wheelbarrow(-15, 146)
    tool(-14.6, F + 1.6, 127.5, "iron_hoe", yaw=-90, roll=40)
    tool(14.6, F + 1.6, 135.5, "stone_shovel", yaw=90, roll=-40)
    for x, z in ((-25, 107), (25, 107), (-25, 157), (25, 157), (-25, 132), (25, 132)):
        g.set(x, F + 1, z, "spruce_fence")
        g.set(x, F + 2, z, "lantern")
    flowers(-14, 107, 14, 157, 110)
    # beyond the fences: the gardener's cabin (west), the greenhouse (east), fruit trees
    cx, cz = -32, 132
    g.box(cx - 3, F + 1, cz - 3, cx + 3, F + 4, cz + 3, lambda x, y, z: "oak_log" if (abs(x - cx) == 3 and abs(z - cz) == 3) else "oak_planks")
    g.box(cx - 2, F + 1, cz - 2, cx + 2, F + 4, cz + 2, "air")
    g.box(cx + 3, F + 1, cz, cx + 3, F + 2, cz, "air")
    g.set(cx + 3, F + 3, cz, "oak_planks")
    for k in range(4):
        g.box(cx - 4 + k, F + 5 + k, cz - 4, cx - 4 + k, F + 5 + k, cz + 4, "spruce_stairs[facing=east]")
        g.box(cx + 4 - k, F + 5 + k, cz - 4, cx + 4 - k, F + 5 + k, cz + 4, "spruce_stairs[facing=west]")
    g.box(cx - 3, F + 5, cz - 3, cx + 3, F + 7, cz - 3, "oak_planks", where=lambda b: b == "air")
    g.box(cx - 3, F + 5, cz + 3, cx + 3, F + 7, cz + 3, "oak_planks", where=lambda b: b == "air")
    g.box(cx, F + 8, cz - 4, cx, F + 8, cz + 4, "spruce_planks")
    g.set(cx + 3, F + 3, cz + 1, "lantern")
    g.set(cx + 4, F + 1, cz - 2, "barrel[facing=up]")
    gx0, gx1, gz0, gz1 = 29, 36, 124, 141
    g.box(gx0, F + 1, gz0, gx1, F + 5, gz1, "glass")
    g.box(gx0 + 1, F + 1, gz0 + 1, gx1 - 1, F + 5, gz1 - 1, "air")
    for x in (gx0, gx1):
        for z in range(gz0, gz1 + 1, 3):
            g.box(x, F + 1, z, x, F + 5, z, "oak_log")
    g.box(gx0, F + 6, gz0, gx1, F + 6, gz1, "glass")
    g.box(gx0 + 1, F + 7, gz0, gx1 - 1, F + 7, gz1, "glass")
    g.box(gx0 + 1, F, gz0 + 1, gx1 - 1, F, gz1 - 1, "farmland[moisture=7]")
    for x in range(gx0 + 1, gx1):
        for z in range(gz0 + 1, gz1):
            g.set(x, F + 1, z, r.choice(["carrots[age=7]", "potatoes[age=7]", "sweet_berry_bush[age=3]", "beetroots[age=3]"]))
    for x, z in ((-30, 110), (-31, 150), (30, 111), (30, 152)):
        tree(x, z, kind="fruit")
    for x, z in ((-9, 116), (9, 122), (0, 141)):
        m.mob("moisson_lapin", x, z, yaw=180, room="3")
    for x, z in ((-10, 136), (10, 114), (6, 147)):
        m.mob("moisson_poule", x, z, yaw=180, room="3")
    for x, z in ((-4, 127), (8, 138)):
        m.mob("moisson_rat", x, z, yaw=180, room="3")
    # the gate to the mill
    g.box(-2, F + 1, 158, 2, F + 5, 158, "air")
    g.box(-2, F, 158, 2, F, 160, "dirt_path")
    g.box(-2, F + 1, 159, 2, F + 3, 159, lambda x, y, z: "spruce_log" if abs(x) == 2 else "spruce_planks")
    g.box(-2, F + 4, 159, 2, F + 4, 159, "spruce_slab[type=bottom]")
    door("porte_potager", (-2, F + 1, 159), (2, F + 4, 159), room="3")
    gate_arch(-2, 2, 157)
    m.sound(-20, F + 1, 123, [("block.water.ambient", 0.5, 1.2)], radius=10, every=(60, 140))
    m.sound(20, F + 1, 139, [("block.water.ambient", 0.5, 1.1), ("entity.frog.ambient", 0.25, 1.0)], radius=10, every=(70, 160))
    m.sound(0, F + 5, 132, [("entity.bee.loop", 0.12, 1.3), ("block.azalea_leaves.step", 0.5, 0.9)], radius=22, every=(140, 280),
            particle={"type": "SPORE_BLOSSOM_AIR", "count": 5, "spread": (10, 2, 10), "speed": 0, "every": 20})


# ======================================================================== room 4: the mill and the silos

def disc(cx, cz, rad, y, block, hollow=False):
    for x in range(int(cx - rad - 1), int(cx + rad + 2)):
        for z in range(int(cz - rad - 1), int(cz + rad + 2)):
            d = math.hypot(x - cx, z - cz)
            if d <= rad + 0.35 and (not hollow or d > rad - 0.75):
                g.set(x, y, z, block(x, y, z) if callable(block) else block)


def room4():
    """The Mill and the Silos, 65 x 60 (x -32..32, z 160..219)."""
    fence_edge(-33, 160, -33, 219)
    fence_edge(33, 160, 33, 219)
    g.box(-2, F, 160, 2, F, 219, lambda *a: pick([("dirt_path", 8), ("coarse_dirt", 2), ("gravel", 1)]))
    g.box(2, F, 186, 22, F, 187, lambda *a: pick([("dirt_path", 6), ("gravel", 2)]))
    g.box(-21, F, 177, -2, F, 178, lambda *a: pick([("dirt_path", 6), ("gravel", 2)]))
    g.box(21, F, 187, 22, F, 200, lambda *a: pick([("dirt_path", 6), ("gravel", 2)]))
    flowers(-32, 160, 32, 219, 150)
    # the windmill: stone base, timber tower narrowing, a cone roof, sails on the south face
    cx, cz = 22, 207
    for y in range(F + 1, F + 28):
        if y <= F + 8:
            rad, mat = 6, lambda *a: pick([("cobblestone", 5), ("stone_bricks", 3), ("mossy_cobblestone", 1), ("stone", 1)])
        elif y <= F + 14:
            rad, mat = 5, lambda x, y, z: "spruce_log" if (x - cx) * (z - cz) == 0 and max(abs(x - cx), abs(z - cz)) >= 4 else "spruce_planks"
        elif y <= F + 19:
            rad, mat = 4, lambda x, y, z: "oak_planks" if (x + y) % 6 else "spruce_planks"
        elif y <= F + 22:
            rad, mat = 3, lambda *a: "spruce_planks"
        else:
            rad, mat = max(0, 3 - (y - F - 22)), lambda *a: "dark_oak_planks"
        disc(cx, cz, rad, y, mat)
    disc(cx, cz, 6.6, F + 9, "spruce_slab[type=bottom]", hollow=True)       # the gallery at the top of the base
    g.box(cx - 1, F + 1, cz - 6, cx + 1, F + 3, cz - 6, "spruce_planks")    # the (closed) door
    g.set(cx, F + 4, cz - 6, "spruce_log")
    if g.get(cx, F + 3, cz - 7) == "air":
        g.set(cx, F + 3, cz - 7, "lantern")
        g.set(cx, F + 2, cz - 7, "spruce_fence")
        g.set(cx, F + 1, cz - 7, "spruce_fence")
    for y in (F + 5, F + 12, F + 17):
        g.set(cx, y, cz - (6 if y < F + 9 else 5 if y < F + 15 else 4), "glass_pane[east=true,west=true]")
    g.box(cx, F + 17, cz - 6, cx, F + 17, cz - 5, "stripped_spruce_log[axis=z]")
    g.set(cx, F + 17, cz - 6, "spruce_log")
    for ang in (45, 135, 225, 315):
        a = math.radians(ang)
        for t in range(1, 13):
            x = cx + round(math.cos(a) * t)
            y = F + 17 + round(math.sin(a) * t)
            if y <= F + 6 or abs(x) > 32:
                break
            g.set(x, y, cz - 6, "stripped_spruce_log[axis=y]" if t % 3 else "spruce_fence")
            if t >= 3:
                px = x + (1 if math.sin(a) > 0 else -1) * (1 if math.cos(a) > 0 else -1)
                if g.get(px, y, cz - 6) == "air" and abs(px) <= 32:
                    g.set(px, y, cz - 6, "white_wool")
    # four brick silos with cone roofs (west side)
    for sz, h in ((167, 15), (181, 17), (195, 14), (209, 16)):
        for y in range(F + 1, F + h + 1):
            disc(-27, sz, 4, y, lambda x, yy, z: "stone_bricks" if yy <= F + 2 else ("bricks" if (yy % 5) else "spruce_planks"))
        for k in range(5):
            disc(-27, sz, 4 - k, F + h + 1 + k, "dark_oak_planks", hollow=k < 4)
        g.box(-27, F + h + 6, sz, -27, F + h + 7, sz, "spruce_fence")
        g.box(-22, F + 5, sz, -22, F + h, sz, "ladder[facing=east]")
    # the flour store along the east fence
    for z in range(161, 196):
        k = r.random()
        x = r.choice((31, 32))
        if g.get(x, F + 1, z) != "air":
            continue
        if k < 0.35:
            sacks(x, z, r.randint(1, 3))
        elif k < 0.55:
            barrels(x, z, r.randint(1, 2), ["wheat", "bread"])
        elif k < 0.7:
            hay(x, z, r.randint(1, 2))
        elif k < 0.8:
            crate(x, z, ["bread", "wheat"])
    cart(27, 163, "z", "hay")
    cart(-16, 213, "x", "hay")
    cart(10, 215, "x", "pumpkin")
    for x, z, h in ((-20, 162, 2), (-19, 162, 1), (-20, 189, 2), (12, 172, 1), (13, 172, 2), (-8, 205, 1), (30, 217, 2), (-31, 218, 1)):
        hay(x, z, h)
    well(-12, 194)
    g.box(24, F + 1, 178, 24, F + 2, 178, "spruce_fence")
    g.set(24, F + 3, 178, "lantern")
    for x, z in ((-32, 160), (-21, 174), (-21, 188), (-21, 202), (-32, 219), (32, 160), (10, 219), (-10, 219), (32, 196)):
        if g.get(x, F + 1, z) == "air":
            g.set(x, F + 1, z, "spruce_fence")
            g.set(x, F + 2, z, "lantern")
    for x, z in ((-13, 163), (-10, 215), (14, 162)):
        tree(x, z, kind="oak")
    hedge(-33, 220, -3, 221)
    hedge(3, 220, 33, 221)
    m.mob("moisson_meunier", 10, 198, yaw=180, room="4")
    for x, z in ((-10, 170), (7, 176), (-13, 200)):
        m.mob("moisson_faucheur", x, z, yaw=180, room="4")
    for x, z in ((15, 167), (-5, 190)):
        m.mob("moisson_epouvantail", x, z, yaw=180, room="4")
    # the gate to the pen
    g.box(-2, F, 219, 2, F, 221, "dirt_path")
    g.box(-2, F + 1, 221, 2, F + 5, 221, "air")
    g.box(-2, F + 1, 220, 2, F + 3, 220, "spruce_fence")
    g.box(-2, F + 4, 220, 2, F + 4, 220, "spruce_slab[type=bottom]")
    door("porte_moulin", (-2, F + 1, 220), (2, F + 4, 220), room="4")
    gate_arch(-2, 2, 219)
    m.sound(22, F + 16, 200, [("block.wooden_trapdoor.open", 0.5, 0.5), ("block.wood.step", 0.5, 0.5), ("block.wooden_door.close", 0.3, 0.5)],
            radius=24, every=(50, 110))
    m.sound(0, F + 8, 185, [("item.elytra.flying", 0.16, 1.1), ("block.grass.step", 0.5, 0.6)], radius=30, every=(120, 240),
            particle={"type": "FALLING_SPORE_BLOSSOM", "count": 4, "spread": (12, 3, 12), "speed": 0, "every": 20})


# ======================================================================== room 5: the pen of the Great Hamster

def room5():
    arena = {}
    for line in open(ARENA):
        if line.startswith("#") or not line.strip():
            continue
        x, y, z, b = line.split(maxsplit=3)
        x, y, z = int(x), int(y), int(z)
        if (x, z) in ((-3, 0), (3, 0)):  # the composter and barrel of the former start
            continue
        arena[(x, y, z + DZ)] = b.strip()
    # its ground first (the copy sets its own grass, farmland and paths), then the copy itself
    g.box(-16, F + 1, -4 + DZ, 16, F + 4, 36 + DZ, "air")
    for p, b in arena.items():
        g.set(*p, b)
    g.box(-2, F + 1, -4 + DZ, 2, F + 2, -4 + DZ, "air")          # the gap in its southern fence
    # the whole pen is one potato field (2026-11-14: the first version had crops on half of it only, bare grass
    # elsewhere): every free cell inside the fence is sown, its obstacles (haystacks, lanterns) stay where they were
    for x in range(-15, 16):
        for z in range(-3 + DZ, 36 + DZ):
            if g.get(x, F + 1, z) == "air" and g.get(x, F, z).split("[")[0] in SOWN:
                g.set(x, F, z, "farmland[moisture=7]")
                g.set(x, F + 1, z, "potatoes[age=7]")
    # invisible barriers over its fence: nothing to climb out by (the hay of the field is next to it)
    for x in range(-16, 17):
        for z in range(-4 + DZ, 37 + DZ):
            if abs(x) == 16 or z in (-4 + DZ, 36 + DZ):
                if z == -4 + DZ and abs(x) <= 2:
                    continue
                for y in range(F + 2, CURTAIN + 1):
                    if g.get(x, y, z) == "air":
                        g.set(x, y, z, "barrier")
    # the way out: a gate in its northern fence, opened when the boss dies
    g.box(-2, F + 1, 36 + DZ, 2, F + 1, 36 + DZ, EXIT_GATE)
    door("porte_sortie", (-2, F + 1, 36 + DZ), (2, F + 1, 36 + DZ), room="5")
    # around the pen (out of reach): food stores, burrows, crates, the wheel
    z0, z1 = -4 + DZ, 36 + DZ
    for sx in (-1, 1):
        for z in range(z0, z1 + 1, 4):
            x = sx * r.randint(18, 20)
            k = r.random()
            if k < 0.3:
                hay(x, z, r.randint(1, 3))
            elif k < 0.5:
                barrels(x, z, r.randint(1, 2), ["carrot", "wheat", "potato", "beetroot"])
            elif k < 0.65:
                sacks(x, z, 2)
            elif k < 0.8:
                crate(x, z, ["carrot", "golden_carrot", "potato"])
        # burrows: grassy mounds with a dark hole facing the pen
        for bz in (z0 + 8, z0 + 26):
            bx = sx * 20
            for dx in range(-2, 3):
                for dz in range(-3, 4):
                    hgt = 3 - (abs(dx) + abs(dz)) // 2
                    for y in range(F + 1, F + 1 + max(0, hgt)):
                        g.set(bx + dx, y, bz + dz, "grass_block" if y == F + hgt else "dirt")
            hole_x = bx - sx * 2
            g.box(hole_x, F + 1, bz, bx, F + 1, bz, "air")
            g.set(hole_x, F + 2, bz, "coarse_dirt")
            g.set(bx, F + 1, bz, "black_wool")
    fence_edge(-22, z0 - 2, -22, z1 + 6)
    fence_edge(22, z0 - 2, 22, z1 + 6)
    fence_edge(-22, z1 + 6, 22, z1 + 6)
    flowers(-21, z0, -17, z1 + 5, 30)
    flowers(17, z0, 21, z1 + 5, 30)
    flowers(-16, z1 + 1, 16, z1 + 5, 20)
    # a long feeding trough on the north side
    g.box(-8, F + 1, z1 + 3, 8, F + 1, z1 + 3, "spruce_slab[type=bottom]")
    g.box(-8, F + 1, z1 + 2, 8, F + 1, z1 + 2, "spruce_trapdoor[half=bottom,facing=north,open=true]")
    for x in range(-7, 8, 2):
        g.item_display(x + 0.5, F + 1.6, z1 + 3.5, r.choice(["carrot", "wheat", "potato", "beetroot"]), yaw=r.uniform(0, 360), scale=0.7)
    # the giant hamster wheel, east, beyond the fence
    wc, wy, wz = 27, F + 8, (z0 + z1) // 2
    for y in range(wy - 8, wy + 9):
        for z in range(wz - 8, wz + 9):
            d = math.hypot(y - wy, z - wz)
            if 6.4 <= d < 7.4:
                for x in (wc - 1, wc + 1):
                    g.set(x, y, z, "spruce_planks")
                a = math.degrees(math.atan2(y - wy, z - wz)) % 360
                if int(a // 15) % 2 == 0:
                    g.set(wc, y, z, "stripped_spruce_log[axis=x]")
            elif d < 6.4 and (abs(y - wy) <= 0 or abs(z - wz) <= 0 or abs(abs(y - wy) - abs(z - wz)) <= 0) and d > 1:
                g.set(wc - 1, y, z, "spruce_fence")
    g.box(wc - 3, wy, wz, wc + 3, wy, wz, "spruce_log[axis=x]")
    for x in (wc - 3, wc + 3):
        g.box(x, F + 1, wz, x, wy - 1, wz, "spruce_log")
        g.box(x, F + 1, wz - 2, x, F + 2, wz - 2, "spruce_fence")
        g.box(x, F + 1, wz + 2, x, F + 2, wz + 2, "spruce_fence")
    m.mob("hamster_moissonneur", 0, 18 + DZ, yaw=180, room="5")
    m.objectives = ["hamster_moissonneur"]


# ======================================================================== checks

def zone_of(x, y, z):
    if z <= 53:
        return 1
    if z <= BZ1:
        return 2
    if z <= 159:
        return 3
    if z <= 221:
        return 4
    if z <= 36 + DZ - 1 and abs(x) <= 16:
        return 5
    return 6


def check():
    errors = g.check_basic()
    start = (0, 64, -7)
    for k in range(len(g.doors) + 1):
        seen = g.walk(start, set(range(k)), top=CURTAIN)
        if not seen:
            errors.append("start is not standable")
            break
        top = max(zone_of(*p) for p in seen)
        allowed = k + 1
        if top > allowed:
            bad = next(p for p in seen if zone_of(*p) > allowed)
            errors.append(f"doors open {k}: zone {top} reachable, e.g. {bad}")
        if top < min(allowed, 5):
            errors.append(f"doors open {k}: zone {allowed} not reachable (max {top})")
    # the arena copied exactly, apart from the gap, the exit gate and the barriers over the fence
    for line in open(ARENA):
        if line.startswith("#") or not line.strip():
            continue
        x, y, z, b = line.split(maxsplit=3)
        x, y, z, b = int(x), int(y), int(z) + DZ, b.strip()
        if (x, z - DZ) in ((-3, 0), (3, 0)):
            continue
        got = g.get(x, y, z)
        sown = abs(x) <= 15 and b.split("[")[0] in SOWN and got.startswith("farmland")
        if got != b and not sown and not (abs(x) <= 2 and z in (-4 + DZ, 36 + DZ)):
            errors.append(f"arena changed at {x} {y} {z}: {b} -> {got}")
    # crops outside the pen kept apart from its field (the boss eats the crops joined to its spawn)
    arena_crops = [(x, z) for (x, y, z), b in g.over.items() if abs(x) <= 16 and DZ - 4 <= z <= DZ + 36
                   and any(c in b for c in ("carrots", "potatoes", "wheat[", "beetroots"))]
    for (x, y, z), b in g.over.items():
        if (abs(x) > 16 or not DZ - 4 <= z <= DZ + 36) and any(c in b for c in ("carrots", "potatoes", "wheat[", "beetroots")):
            if any(abs(x - ax) <= 2 and abs(z - az) <= 2 for ax, az in arena_crops):
                errors.append(f"crop at {x} {y} {z} joins the hamster's field")
                break
    return errors


# ======================================================================== main

terrain()
fields()
road()
room1()
room2()
room3()
room4()
room5()
# the meadow between the rooms and the fields (out of reach): wild flowers, a few trees and haystacks
flowers(-37, -12, 37, 270, 900)
for x, z, kind in ((-30, 20, "oak"), (29, 44, "birch"), (30, 14, "fruit"), (-29, 46, "fruit"), (-35, 75, "oak"), (35, 95, "oak"),
                   (35, 66, "fruit"), (-35, 98, "birch"), (-30, 118, "birch"), (31, 150, "oak"), (-28, 240, "oak"), (29, 250, "birch")):
    if g.get(x, F + 1, z) == "air":
        tree(x, z, kind=kind)
for x, z, h in ((27, 30, 2), (28, 30, 1), (-27, 12, 1), (-34, 60, 2), (34, 85, 1), (-30, 142, 2), (-25, 232, 1), (31, 236, 2)):
    if g.get(x, F + 1, z) == "air":
        hay(x, z, h)
m.textures = "rpg"
m.biome = "plains"           # RPG grass and leaves painted in that colour, biome set on the map
m.ybox = (Y0, Y1)


# Troop roles (2026-11-13): about twice the monsters of the first version, half of them with a role (gelées that
# leap and split, fast pests, brutes, spitters, snipers, kamikazes, supports), placed next to those above.
REINFORCE = {
    "1": ['moisson_limace', 'moisson_limace', 'moisson_limace', 'moisson_marmot', 'moisson_marmot', 'moisson_marmot', 'moisson_semeur', 'moisson_semeur', 'moisson_citrouille'],
    "2": ['moisson_taureau', 'moisson_taureau', 'moisson_marmot', 'moisson_marmot', 'moisson_semeur', 'moisson_semeur'],
    "3": ['moisson_limace', 'moisson_limace', 'moisson_limace', 'moisson_citrouille', 'moisson_citrouille', 'moisson_semeur', 'moisson_semeur', 'moisson_taureau'],
    "4": ['moisson_taureau', 'moisson_taureau', 'moisson_semeur', 'moisson_semeur', 'moisson_marmot', 'moisson_marmot', 'moisson_citrouille'],
}


if __name__ == "__main__":
    args = sys.argv[1:]
    maplib.reinforce(m, g, REINFORCE, seed=103)
    errs = check()
    for e in errs[:50]:
        print("CHECK:", e)
    print(f"{len(errs)} problem(s), {len(g.over)} blocks changed, {len(g.entities)} entities")
    if "--preview" in args:
        out = args[args.index("--preview") + 1]
        g.png_slice(os.path.join(out, "moisson_ciel.png"), top_down=True, k=3)
        g.png_slice(os.path.join(out, "moisson_y64.png"), y=F + 1, k=3)
        g.png_slice(os.path.join(out, "moisson_coupe_x0.png"), x=0, k=3)
        g.png_slice(os.path.join(out, "moisson_coupe_x22.png"), x=22, k=3)
    if "--check" in args or "--preview" in args:
        sys.exit(1 if errs else 0)
    if errs:
        sys.exit("checks failed: nothing sent")
    if "--edits" in args:
        maplib.write_edits(m, args[0] if args and not args[0].startswith("--") else "labs", "moisson")
        sys.exit(0)
    g.commands(m)
    print(f"{len(m.cmds)} commands")
    maplib.run(m, args[0] if args and not args[0].startswith("--") else "labs", "moisson")
