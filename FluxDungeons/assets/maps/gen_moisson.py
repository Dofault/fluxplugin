#!/usr/bin/env python3
"""Map of the harvest dungeon, Le Domaine des Cultivateurs (boss: the Hamster Moissonneur), modelled in memory
(gridlib), checked, then built by console commands with the RPG look of the dungeons.

An open-air farm estate played from south to north (+z), ground at y 63, in a valley of fields, streams and woods
closed by hills and snowy mountains:
  - the farm road (start at 0, 64, -6);
  - room 1, the Scarecrows' Field (35 x 30): wheat and beetroot rows on the sides, decor scarecrows, a gardener's
    shelter, a cart, a wheelbarrow, hay, barrels, a well; open 20 x 20 middle. Scarecrows and field rats;
  - room 2, the Great Harvest Barn (27 x 25 inside): stone base, plank walls, a 1:1 gable roof, sliding doors, lofts
    on both sides over the storage, lanterns on the tie beams; open 19 x 19 middle. Possessed reapers, rats and the
    Great Scarecrow (mini-boss);
  - room 3, the Market Gardeners' Plot (33 x 30): carrots, potatoes, beetroots, wheat, pumpkins and melons in plots
    along the sides, irrigation ditches and ponds, crates of vegetables; a cabin and a greenhouse beyond the fences.
    Raging rabbits, corrupted hens, rats;
  - room 4, the Mill and the Silos (39 x 34): a 13 x 13 windmill 27 high with four sails facing the way in, three
    brick silos, a flour store; the Possessed Miller (mini-boss), reapers and scarecrows;
  - room 5, the Great Hamster's Pen: the boss arena of the first version, copied block for block (its crops, fences,
    lanterns and haystacks: the boss depends on them; nothing added inside its fence), entered by the gap of its
    southern fence, left by a gate in its northern fence when the boss dies; around it food stores, burrows and a
    giant hamster wheel, out of reach.
Between rooms, tall hedges (the next room stays hidden); on the landscape side, low fences under invisible barriers
(the countryside stays in view).

Usage: gen_moisson.py [server] [--check] [--preview DIR]     (labs by default)
"""
import math
import os
import sys

import gridlib
import maplib

F = 63
DZ = 142                         # the former arena (z -4..36) is moved to z 138..178
X0, X1, Y0, Y1, Z0, Z1 = -70, 70, 50, 112, -50, 215
BANDS = ((50, 58, "stone"), (59, 62, "dirt"), (63, 63, "grass_block"))
CURTAIN = F + 12                 # top of the invisible barriers
ARENA = os.path.join(maplib.HERE, "moisson_arena.txt")
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
        g.set(x, y, z, "hay_block[axis=y]" if r.random() < 0.7 else r.choice(["hay_block[axis=x]", "hay_block[axis=z]"]))


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
            if abs(x) <= 23 and -14 <= z <= 186:
                continue
            dx = max(0, abs(x) - 44)
            dz = max(0, -10 - z, z - 190)
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
        d = math.hypot(max(0, abs(x) - 44), max(0, -10 - z, z - 190))
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
        x_lo, x_hi = (26, 42)
        for zb in range(-8, 188, 22):
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
        sx_stream = sx * 44
        for z in range(-8, 189):
            g.set(sx_stream, F, z, "water")
            g.set(sx_stream, F - 1, z, "gravel" if r.random() < 0.5 else "sand")
            if r.random() < 0.08:
                g.set(sx_stream, F + 1, z, "lily_pad")
        # hedgerow along the estate
        for z in range(-12, 186, 5):
            if r.random() < 0.55:
                tree(sx * 24, z + r.randint(0, 3), kind=r.choice(["oak", "birch", "fruit"]))
            else:
                g.box(sx * 24, F + 1, z, sx * 24, F + r.randint(1, 2), z + 1, LEAVES)
    # two small farmsteads out in the fields
    for (bx, bz) in ((-34, 60), (34, 120)):
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
    # edges: fences on the landscape sides, hedges between rooms
    fence_edge(-18, 7, -18, 37)
    fence_edge(18, 7, 18, 37)
    fence_edge(-18, 7, -3, 7)
    fence_edge(3, 7, 18, 7)
    hedge(-18, 38, 18, 40)
    # ground: grass, a dirt and gravel cross of paths, rows of crops on the sides
    g.box(-2, F, 8, 2, F, 37, lambda *a: pick([("dirt_path", 7), ("coarse_dirt", 2), ("gravel", 1)]))
    g.box(-17, F, 22, 17, F, 23, lambda *a: pick([("dirt_path", 5), ("coarse_dirt", 2), ("grass_block", 2)]))
    for sx in (-1, 1):
        for i, (z0, z1) in enumerate(((9, 20), (25, 36))):
            x0, x1 = (11, 16) if sx > 0 else (-16, -11)
            crop_plot(x0, z0, x1, z1, "wheat" if (i + (sx > 0)) % 2 else "beetroots", wild=0.15)
            for x in range(min(x0, x1), max(x0, x1) + 1):
                if (x % 2) == 0:
                    pass
    flowers(-17, 8, 17, 37, 70)
    # decor scarecrows in the crops, an old one over the gate
    for x, z, f in ((-14, 14, "east"), (14, 30, "west"), (-13, 32, "east"), (13, 12, "west")):
        scarecrow(x, z, f)
    # the gardener's shelter (south-west corner)
    g.box(-17, F + 1, 8, -14, F + 3, 10, "spruce_planks")
    g.box(-16, F + 1, 9, -14, F + 2, 9, "air")
    g.box(-17, F + 1, 8, -17, F + 3, 8, "spruce_log")
    g.box(-14, F + 1, 8, -14, F + 3, 8, "spruce_log")
    g.box(-14, F + 1, 10, -14, F + 3, 10, "spruce_log")
    g.box(-17, F + 4, 8, -14, F + 4, 10, "spruce_slab[type=bottom]")
    g.set(-16, F + 1, 9, "composter[level=5]")
    tool(-15.5, F + 1.8, 8.6, "iron_hoe", yaw=180)
    tool(-14.4, F + 1.8, 9.5, "stone_shovel", yaw=90, roll=-30)
    # cart, wheelbarrow, hay, barrels, well
    cart(13, 23, "x", "pumpkin")
    wheelbarrow(-12, 21)
    for x, z, h in ((16, 9, 2), (15, 9, 1), (-16, 36, 2), (16, 36, 1), (-16, 24, 1)):
        hay(x, z, h)
    barrels(16, 21, 2, ["wheat", "carrot"])
    barrels(-16, 21, 1, ["beetroot"])
    well(-12, 27)
    # light
    for x, z in ((-17, 15), (17, 15), (-17, 31), (17, 31)):
        g.set(x, F + 1, z, "spruce_fence")
        g.set(x, F + 2, z, "lantern")
    # the gate to the barn
    g.box(-2, F + 1, 39, 2, F + 5, 40, "air")
    g.box(-2, F, 39, 2, F, 41, "dirt_path")
    g.box(-2, F + 1, 38, 2, F + 3, 38, "spruce_fence")
    g.box(-2, F + 4, 38, 2, F + 4, 38, "spruce_slab[type=bottom]")
    door("porte_champs", (-2, F + 1, 38), (2, F + 4, 38), room="1")
    gate_arch(-2, 2, 37)
    for x, z in ((-8, 13), (5, 15), (-6, 26), (8, 27), (-3, 33), (7, 33)):
        m.mob("moisson_epouvantail", x, z, yaw=180, room="1")
    for x, z in ((-9, 30), (9, 19), (0, 20)):
        m.mob("moisson_rat", x, z, yaw=180, room="1")


# ======================================================================== room 2: the barn

BX0, BX1, BZ0, BZ1 = -13, 13, 42, 66


def room2():
    # around the barn: hay yard, out of reach (barn walls and hedges)
    hedge(-19, 41, -3, 41, 5)
    hedge(3, 41, 19, 41, 5)
    hedge(-19, 42, -19, 66)
    hedge(19, 42, 19, 66)
    g.box(-18, F, 42, 18, F, 66, lambda *a: pick([("grass_block", 6), ("coarse_dirt", 2), ("dirt", 1)]))
    flowers(-18, 42, -14, 66, 20)
    flowers(14, 42, 18, 66, 20)
    for z in (46, 55, 63):
        hay(-16, z, r.randint(1, 3))
        hay(16, z + 2, r.randint(1, 2))
    cart(15, 49, "z")

    def wall(x, y, z):
        if y <= F + 2:
            return pick([("cobblestone", 5), ("stone_bricks", 3), ("mossy_cobblestone", 1)])
        return "spruce_planks" if (x + z) % 7 else "oak_planks"

    g.box(BX0, F + 1, BZ0, BX1, F + 9, BZ1, wall)
    g.box(BX0 + 1, F + 1, BZ0 + 1, BX1 - 1, F + 9, BZ1 - 1, "air")
    g.box(BX0 + 1, F, BZ0 + 1, BX1 - 1, F, BZ1 - 1,
          lambda x, y, z: pick([("coarse_dirt", 4), ("dirt", 3), ("dirt_path", 2)]) if abs(x) < 10 else "spruce_planks")
    # posts and beams
    for z in range(BZ0, BZ1 + 1, 6):
        for x in (BX0, BX1):
            g.box(x, F + 1, z, x, F + 9, z, "spruce_log")
    for x in range(BX0, BX1 + 1, 6):
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
    # gable roof, 1:1, along z
    for k in range(0, 15):
        y = F + 9 + k
        xw, xe = BX0 - 1 + k, BX1 + 1 - k
        if xw > xe:
            break
        if xw == xe:
            g.box(xw, y, BZ0 - 1, xw, y, BZ1 + 1, "dark_oak_planks")
            break
        g.box(xw, y, BZ0 - 1, xw, y, BZ1 + 1, "dark_oak_stairs[facing=east]")
        g.box(xe, y, BZ0 - 1, xe, y, BZ1 + 1, "dark_oak_stairs[facing=west]")
        for z in (BZ0, BZ1):
            g.box(xw + 1, y, z, xe - 1, y, z, lambda xx, yy, zz: "spruce_planks" if xx % 4 else "spruce_log")
    # hay-loft openings in both gables (light, out of reach)
    for z in (BZ0, BZ1):
        g.box(-2, F + 12, z, 2, F + 15, z, "air")
        g.box(-3, F + 11, z, 3, F + 11, z, "stripped_spruce_log[axis=x]")
    # tie beams with lanterns
    for z in range(BZ0 + 4, BZ1, 6):
        g.box(BX0 + 1, F + 9, z, BX1 - 1, F + 9, z, "stripped_spruce_log[axis=x]")
        for x in (-6, 0, 6):
            g.set(x, F + 8, z, "iron_chain[axis=y]")
            g.set(x, F + 7, z, "lantern[hanging=true]")
    # lofts on both sides, over the storage
    for sx in (-1, 1):
        xa, xb = sorted((10 * sx, 12 * sx))
        g.box(xa, F + 5, BZ0 + 1, xb, F + 5, BZ1 - 1, "spruce_slab[type=top]")
        g.box(10 * sx, F + 6, BZ0 + 1, 10 * sx, F + 6, BZ1 - 1, "spruce_fence")
        for z in range(BZ0 + 3, BZ1, 6):
            g.box(10 * sx, F + 1, z, 10 * sx, F + 4, z, "spruce_log")
        for z in range(BZ0 + 2, BZ1 - 1, 3):
            if r.random() < 0.6:
                g.set(12 * sx, F + 6, z, "hay_block[axis=y]")
                if r.random() < 0.5:
                    g.set(12 * sx, F + 7, z, "hay_block[axis=x]")
            elif r.random() < 0.5:
                g.set(11 * sx, F + 6, z, "white_wool")
        g.box(12 * sx, F + 1, BZ0 + 2, 12 * sx, F + 5, BZ0 + 2,
              f"ladder[facing={'east' if sx < 0 else 'west'}]")
        g.set(12 * sx, F + 5, BZ0 + 2, "air")
        # storage under the lofts
        for z in range(BZ0 + 3, BZ1 - 1):
            k = r.random()
            x = 12 * sx if r.random() < 0.6 else 11 * sx
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
        tool(12 * sx + (0.4 if sx < 0 else 0.6), F + 3.2, BZ0 + 9.5, "trident", yaw=90 if sx < 0 else -90, roll=10)
        tool(12 * sx + (0.4 if sx < 0 else 0.6), F + 3.2, BZ0 + 15.5, "iron_hoe", yaw=90 if sx < 0 else -90)
    for x, z in ((-6, 53), (6, 53), (-6, 61), (6, 61), (0, 47), (0, 57)):
        g.set(x, F + 6, z, "light[level=12]")
    # the big doorway (south) with its sliding doors pushed aside, the gate to the plot (north)
    g.box(-3, F + 1, BZ0, 3, F + 6, BZ0, "air")
    g.box(-3, F + 1, BZ0 - 1, 3, F + 6, BZ0 - 1, "air")
    g.box(-2, F, 39, 2, F, BZ0, "dirt_path")
    for sx in (-1, 1):
        xa, xb = sorted((4 * sx, 9 * sx))
        g.box(xa, F + 1, BZ0 - 1, xb, F + 6, BZ0 - 1, lambda x, y, z: "dark_oak_planks" if (x + y) % 5 else "spruce_log")
    g.box(-10, F + 7, BZ0 - 1, 10, F + 7, BZ0 - 1, "stripped_spruce_log[axis=x]")
    g.box(-2, F + 1, BZ1, 2, F + 5, BZ1, lambda x, y, z: "spruce_log" if abs(x) == 2 else "dark_oak_planks")
    door("porte_grange", (-2, F + 1, BZ1), (2, F + 5, BZ1), room="2")
    m.mob("moisson_grand_epouvantail", 0, 57, yaw=180, room="2")
    for x, z in ((-6, 48), (6, 51), (-5, 62)):
        m.mob("moisson_faucheur", x, z, yaw=180, room="2")
    for x, z in ((4, 47), (7, 62)):
        m.mob("moisson_rat", x, z, yaw=180, room="2")


# ======================================================================== room 3: the market garden

def room3():
    hedge(-19, 67, -3, 69)
    hedge(3, 67, 19, 69)
    g.box(-2, F, 67, 2, F, 70, "dirt_path")
    fence_edge(-17, 70, -17, 99)
    fence_edge(17, 70, 17, 99)
    hedge(-19, 100, 19, 101)
    # paths: a ring round the middle and a cross
    g.box(-2, F, 70, 2, F, 99, lambda *a: pick([("dirt_path", 8), ("coarse_dirt", 1), ("gravel", 1)]))
    g.box(-10, F, 84, 10, F, 85, lambda *a: pick([("dirt_path", 8), ("coarse_dirt", 2)]))
    # plots on the sides, a ditch between each
    plots_w = (("carrots", 71, 78), ("potatoes", 81, 88), ("beetroots", 91, 98))
    plots_e = (("wheat", 71, 78), ("pumpkin", 81, 88), ("melon", 91, 98))
    for plots, x0, x1 in ((plots_w, -16, -11), (plots_e, 11, 16)):
        for crop, z0, z1 in plots:
            if crop in ("pumpkin", "melon"):
                gourd_plot(x0, z0, x1, z1, crop)
            else:
                crop_plot(x0, z0, x1, z1, crop)
        for zc in (79, 89):
            g.box(x0, F, zc, x1, F, zc, "water")
            g.box(x0, F - 1, zc, x1, F - 1, zc, "clay")
        g.box(x0, F, 80, x1, F, 80, "dirt_path")
        g.box(x0, F, 90, x1, F, 90, "dirt_path")
        # low fences on the outer edge of the plots, boards along the ditches
        for z in (70, 99):
            g.box(x0, F, z, x1, F, z, "dirt_path")
    # two ponds in the corners, reeds round them
    for (px, pz) in ((-14, 70), (14, 99)):
        pass
    for x, z in ((-10, 72), (10, 97)):
        g.set(x, F, z, "water")
        g.set(x, F - 1, z, "clay")
        g.set(x, F + 1, z, "lily_pad")
    # crates of vegetables, composters, wheelbarrow, tools at the plot corners
    for x, z, items in ((-10, 79, ["carrot"]), (-10, 89, ["potato", "baked_potato"]), (10, 79, ["wheat", "bread"]),
                        (10, 89, ["pumpkin_pie", "melon_slice"])):
        crate(x, z, items)
    g.set(-10, F + 1, 99, "composter[level=7]")
    g.set(10, F + 1, 70, "composter[level=3]")
    wheelbarrow(-10, 93)
    tool(-9.6, F + 1.6, 81.5, "iron_hoe", yaw=-90, roll=40)
    tool(9.6, F + 1.6, 87.5, "stone_shovel", yaw=90, roll=-40)
    for x, z in ((-16, 70), (16, 70), (-16, 99), (16, 99)):
        g.set(x, F + 1, z, "spruce_fence")
        g.set(x, F + 2, z, "lantern")
    # beyond the fences: the gardener's cabin (west), the greenhouse (east), fruit trees
    cx, cz = -24, 82
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
    gx0, gx1, gz0, gz1 = 20, 27, 78, 91
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
    for x, z in ((-21, 72), (-22, 94), (21, 73), (21, 96)):
        tree(x, z, kind="fruit")
    for x, z in ((-6, 76), (6, 80), (0, 92)):
        m.mob("moisson_lapin", x, z, yaw=180, room="3")
    for x, z in ((-7, 88), (7, 75), (4, 95)):
        m.mob("moisson_poule", x, z, yaw=180, room="3")
    for x, z in ((-3, 83), (5, 90)):
        m.mob("moisson_rat", x, z, yaw=180, room="3")
    # the gate to the mill
    g.box(-2, F + 1, 100, 2, F + 5, 100, "air")
    g.box(-2, F, 100, 2, F, 102, "dirt_path")
    g.box(-2, F + 1, 101, 2, F + 3, 101, lambda x, y, z: "spruce_log" if abs(x) == 2 else "spruce_planks")
    g.box(-2, F + 4, 101, 2, F + 4, 101, "spruce_slab[type=bottom]")
    door("porte_potager", (-2, F + 1, 101), (2, F + 4, 101), room="3")
    gate_arch(-2, 2, 99)


# ======================================================================== room 4: the mill and the silos

def disc(cx, cz, rad, y, block, hollow=False):
    for x in range(int(cx - rad - 1), int(cx + rad + 2)):
        for z in range(int(cz - rad - 1), int(cz + rad + 2)):
            d = math.hypot(x - cx, z - cz)
            if d <= rad + 0.35 and (not hollow or d > rad - 0.75):
                g.set(x, y, z, block(x, y, z) if callable(block) else block)


def room4():
    fence_edge(-20, 102, -20, 135)
    fence_edge(20, 102, 20, 135)
    g.box(-2, F, 102, 2, F, 135, lambda *a: pick([("dirt_path", 8), ("coarse_dirt", 2), ("gravel", 1)]))
    g.box(2, F, 117, 13, F, 118, lambda *a: pick([("dirt_path", 6), ("gravel", 2)]))
    g.box(-12, F, 112, -2, F, 113, lambda *a: pick([("dirt_path", 6), ("gravel", 2)]))
    flowers(-19, 102, 19, 135, 60)
    # the windmill: stone base, timber tower narrowing, a cone roof, sails on the south face
    cx, cz = 13, 129
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
    g.box(cx - 6, F + 9, cz - 6, cx + 6, F + 9, cz + 6, "air", where=lambda b: b in ("cobblestone",))
    disc(cx, cz, 6.6, F + 9, "spruce_slab[type=bottom]", hollow=True)       # the gallery at the top of the base
    g.box(cx - 1, F + 1, cz - 6, cx + 1, F + 3, cz - 6, "spruce_planks")    # the (closed) door
    g.set(cx, F + 4, cz - 6, "spruce_log")
    g.set(cx, F + 3, cz - 7, "lantern[hanging=false]") if g.get(cx, F + 3, cz - 7) == "air" else None
    for y in (F + 5, F + 12, F + 17):
        g.set(cx, y, cz - (6 if y < F + 9 else 5 if y < F + 15 else 4), "glass_pane[east=true,west=true]")
    hub = (cx, F + 17, cz - 6)
    g.box(cx, F + 17, cz - 6, cx, F + 17, cz - 5, "stripped_spruce_log[axis=z]")
    g.set(*hub, "spruce_log")
    for ang in (45, 135, 225, 315):
        a = math.radians(ang)
        for t in range(1, 11):
            x = cx + round(math.cos(a) * t)
            y = F + 17 + round(math.sin(a) * t)
            if y <= F + 6:
                break
            g.set(x, y, cz - 6, "stripped_spruce_log[axis=y]" if t % 3 else "spruce_fence")
            if t >= 3:
                # the sail cloth, beside the spar
                px = x + (1 if math.sin(a) > 0 else -1) * (1 if math.cos(a) > 0 else -1)
                if g.get(px, y, cz - 6) == "air":
                    g.set(px, y, cz - 6, "white_wool")
    # three brick silos with cone roofs (west side)
    for sz, h in ((108, 15), (117, 17), (127, 14)):
        for y in range(F + 1, F + h + 1):
            disc(-15, sz, 3, y, lambda x, yy, z: "stone_bricks" if yy <= F + 2 else ("bricks" if (yy % 5) else "spruce_planks"), hollow=False)
        for k in range(4):
            disc(-15, sz, 3 - k, F + h + 1 + k, "dark_oak_planks", hollow=k < 3)
        g.box(-15, F + h + 5, sz, -15, F + h + 6, sz, "spruce_fence")
        g.box(-12, F + 5, sz, -12, F + h, sz, "ladder[facing=east]")
    g.box(-12, F + 5, 117, -11, F + 5, 117, "air")
    # the flour store along the east fence
    for z in range(103, 121):
        k = r.random()
        x = r.choice((18, 19))
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
    cart(16, 104, "z", "hay")
    g.box(14, F + 1, 112, 14, F + 2, 112, "spruce_fence")
    g.set(14, F + 3, 112, "lantern")
    for x, z in ((-19, 102), (-19, 120), (-19, 135), (19, 102), (6, 135)):
        g.set(x, F + 1, z, "spruce_fence")
        g.set(x, F + 2, z, "lantern")
    for x, z in ((-8, 104), (-6, 133)):
        tree(x, z, kind="oak")
    # beyond the east fence (z 123..135 is the windmill), fields go on
    hedge(-22, 136, -3, 137)
    hedge(3, 136, 22, 137)
    m.mob("moisson_meunier", 6, 124, yaw=180, room="4")
    for x, z in ((-6, 108), (4, 111), (-8, 125)):
        m.mob("moisson_faucheur", x, z, yaw=180, room="4")
    for x, z in ((9, 106), (-3, 121)):
        m.mob("moisson_epouvantail", x, z, yaw=180, room="4")
    # the gate to the pen
    g.box(-2, F, 135, 2, F, 137, "dirt_path")
    g.box(-2, F + 1, 137, 2, F + 5, 137, "air")
    g.box(-2, F + 1, 136, 2, F + 3, 136, "spruce_fence")
    g.box(-2, F + 4, 136, 2, F + 4, 136, "spruce_slab[type=bottom]")
    door("porte_moulin", (-2, F + 1, 136), (2, F + 4, 136), room="4")
    gate_arch(-2, 2, 135)


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
    if z <= 38:
        return 1
    if z <= 66:
        return 2
    if z <= 101:
        return 3
    if z <= 137:
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
        if got != b and not (abs(x) <= 2 and z in (-4 + DZ, 36 + DZ)):
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
m.textures = "rpg"
m.ybox = (Y0, Y1)

if __name__ == "__main__":
    args = sys.argv[1:]
    errs = check()
    for e in errs[:50]:
        print("CHECK:", e)
    print(f"{len(errs)} problem(s), {len(g.over)} blocks changed, {len(g.entities)} entities")
    if "--preview" in args:
        out = args[args.index("--preview") + 1]
        g.png_slice(os.path.join(out, "moisson_ciel.png"), top_down=True, k=3)
        g.png_slice(os.path.join(out, "moisson_y64.png"), y=F + 1, k=3)
        g.png_slice(os.path.join(out, "moisson_coupe_x0.png"), x=0, k=3)
        g.png_slice(os.path.join(out, "moisson_coupe_x13.png"), x=13, k=3)
    if "--check" in args or "--preview" in args:
        sys.exit(1 if errs else 0)
    if errs:
        sys.exit("checks failed: nothing sent")
    g.commands(m)
    print(f"{len(m.cmds)} commands")
    maplib.run(m, args[0] if args and not args[0].startswith("--") else "labs", "moisson")
