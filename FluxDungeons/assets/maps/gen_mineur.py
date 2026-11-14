#!/usr/bin/env python3
"""Map of the miner's dungeon, La Mine de Cuivre (boss: the Copper T-Rex), modelled in memory (gridlib), checked, then
built by console commands with the RPG look of the dungeons.

A mine worked for centuries inside a mountain (timbering, rails, carts, ore veins, workshops), going deeper at every
step and older as it goes, played from south to north (+z):
  - the mine's yard (start at 0, 64, -24): a hollow at the foot of the cliff, open to the sky (invisible lid), a timber
    portal, a checkpoint hut, a store of barrels, chests, sacks and extracted ore, rails going into the mountain;
  - the entrance tunnel (9 wide, regular, a timber set every 4 blocks);
  - room 1, the Extraction Galleries (floor 63): a main gallery and a junction chamber, three side tunnels ending on
    working faces (coal and iron, copper and gold, redstone and lapis), carts, tools. Corrupted miners, rats, spiders;
  - room 2, the Great Cart Hall (41 x 39, floor 61, 15 high): roof trusses, side rail lanes going into tunnels, a
    loading platform with a crane, a smithy and stores along the east wall; open middle. The Cursed Foreman
    (mini-boss), miners and spiders;
  - room 3, the Forgotten Depths (floor 55): the oldest workings, deepslate coming in: broken timbering, fallen beams,
    abandoned broken rails, an overturned cart, a collapsed side passage, a natural cavity with rare ores, an old
    vertical shaft with its stuck cage; spiders, rats, miners;
  - the arena of the Copper T-Rex (floor 51): the floor plan of the first version kept exactly (35 x 39, the four
    pillars that stop its charge, the five raw copper veins it drains, the only raw copper of the map, walls solid up
    to 3 blocks), then everything above changed: ledges with abandoned workings, trusses, a vault 16 high.

Usage: gen_mineur.py [server] [--check] [--preview DIR]     (labs by default)
"""
import math
import os
import sys

import gridlib
import maplib

F, F2, F3, FA = 63, 61, 55, 51
X0, X1, Y0, Y1, Z0, Z1 = -44, 44, 28, 104, -34, 222
BANDS = ((28, 47, "deepslate"), (48, 52, "tuff"), (53, 95, "stone"), (96, 98, "dirt"), (99, 99, "grass_block"))
g = gridlib.Model((X0, X1, Y0, Y1, Z0, Z1), BANDS, seed=31)
r = g.r
m = maplib.Map()
m.box = (X0, Z0, X1, Z1)
_door = m.door
OPEN = ("air", "water")
ROCK = "rock"
DZ = 164                                                  # the first arena (z 6..44, floor 63) moved to z 170..208
AX0, AX1, AZ0, AZ1, ATOP = -17, 17, 6 + DZ, 44 + DZ, FA + 16
PILLARS = ((8, 15 + DZ), (-10, 15 + DZ), (8, 33 + DZ), (-10, 33 + DZ))
VEINS = (((-14, 12), (-15, 12), (-14, 13), (-14, 12, 1)), ((14, 12), (15, 12), (14, 13), (14, 12, 1)),
         ((-14, 38), (-15, 38), (-14, 37), (-14, 38, 1)), ((14, 38), (15, 38), (14, 37), (14, 38, 1)),
         ((0, 42), (1, 42), (-1, 42), (0, 42, 1)))


def door(id, frm, to, room):
    g.doors.append((tuple(min(a, b) for a, b in zip(frm, to)), tuple(max(a, b) for a, b in zip(frm, to))))
    _door(id, frm, to, room=room)


# ======================================================================== rock and ore

PAL = {
    "upper": [("stone", 60), ("cobblestone", 12), ("andesite", 14), ("tuff", 8), ("gravel", 3), ("granite", 3)],
    "mid": [("stone", 45), ("andesite", 15), ("tuff", 15), ("cobblestone", 12), ("diorite", 5), ("deepslate", 8)],
    "deep": [("deepslate", 45), ("cobbled_deepslate", 20), ("tuff", 15), ("stone", 12), ("basalt[axis=y]", 8)],
}
N = [gridlib.Noise(s) for s in (401, 402, 403)]


def palette(x, y, z):
    zz = z + N[2](x * 0.3, y * 0.3, z * 0.3) * 4
    return "upper" if zz < 64 else "mid" if zz < 112 else "deep"


def paint(x, y, z, pal=None):
    p = PAL[pal or palette(x, y, z)]
    v = (N[0](x * 0.9, y * 1.1, z * 0.9) * 0.75 + N[1](x * 2.3, y * 2.3, z * 2.3) * 0.25 + 1) / 2
    v = min(0.999, max(0.0, v + r.uniform(-0.07, 0.07)))
    tot, acc = sum(w for _, w in p), 0
    for b, w in p:
        acc += w / tot
        if v < acc:
            return b
    return p[-1][0]


ORES = {
    "upper": [("coal_ore", 5), ("iron_ore", 3), ("copper_ore", 1)],
    "mid": [("copper_ore", 3), ("iron_ore", 2), ("gold_ore", 1), ("coal_ore", 2)],
    "deep": [("deepslate_copper_ore", 2), ("deepslate_iron_ore", 2), ("deepslate_gold_ore", 1), ("deepslate_redstone_ore", 1),
             ("deepslate_lapis_ore", 1)],
}


def vein(x, y, z, ore, size):
    """An irregular vein of ore in the rock around (x, y, z): ore cells only where the rock shows."""
    cells = [(x, y, z)]
    for _ in range(size - 1):
        cx, cy, cz = r.choice(cells)
        cells.append((cx + r.randint(-1, 1), cy + r.randint(-1, 1), cz + r.randint(-1, 1)))
    for c in cells:
        b = g.get(*c)
        if b not in OPEN and (b == ROCK or not g.inside(*c) or c not in g.over or b in ("stone", "deepslate", "tuff", "andesite")):
            if any(g.get(c[0] + d[0], c[1] + d[1], c[2] + d[2]) == "air" for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1))):
                g.set(*c, ore)


def veins_in(x0, y0, z0, x1, y1, z1, n, table):
    cells = []
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            for z in range(z0, z1 + 1):
                b = g.get(x, y, z)
                if b in OPEN or gridlib.passable(b):
                    continue
                if any(g.get(x + d[0], y + d[1], z + d[2]) == "air" for d in ((1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, -1, 0))):
                    if b == ROCK or (x, y, z) not in g.over:
                        cells.append((x, y, z))
    r.shuffle(cells)
    for c in cells[:n]:
        vein(*c, g.pick(table), r.randint(2, 6))


# ======================================================================== mining builders

def tunnel(x0, x1, z0, z1, floor, h, rough=0.0):
    """A mined tunnel: a box of air, its floor of worked rock, rough walls if asked."""
    for z in range(min(z0, z1), max(z0, z1) + 1):
        for x in range(x0, x1 + 1):
            g.set(x, floor, z, paint(x, floor, z))
            for y in range(floor + 1, floor + h + 1):
                g.set(x, y, z, "air")
        if rough:
            for y in range(floor + 1, floor + h + 1):
                for x in (x0 - 1, x1 + 1):
                    if r.random() < rough:
                        g.set(x, y, z, "air")


def timber_set(x0, x1, z, floor, h, broken=False, axis="x"):
    """A timber set across a tunnel: two posts, a cap, braces; a broken one lacks a post or half its cap."""
    def at(a, b):
        return (a, b) if axis == "x" else (b, a)
    post_l = not (broken and r.random() < 0.4)
    post_r = not (broken and r.random() < 0.4)
    for a, ok in ((x0, post_l), (x1, post_r)):
        if ok:
            for y in range(floor + 1, floor + h + 1):
                g.set(*at(a, z)[:1], y, at(a, z)[1], "spruce_log") if False else g.set(at(a, z)[0], y, at(a, z)[1], "spruce_log")
    cap_to = x1 if not broken or r.random() < 0.5 else (x0 + x1) // 2
    for a in range(x0, cap_to + 1):
        g.set(at(a, z)[0], floor + h + 1, at(a, z)[1], "stripped_spruce_log[axis=%s]" % axis)
    if axis == "x":
        if post_l:
            g.set(x0 + 1, floor + h, z, "spruce_stairs[facing=west,half=top]")
        if post_r and cap_to == x1:
            g.set(x1 - 1, floor + h, z, "spruce_stairs[facing=east,half=top]")
    if broken and cap_to != x1:
        # the fallen end of the cap, lying at the foot of the wall
        g.set(x1, floor + 1, z, "stripped_spruce_log[axis=z]")


def rails(x, z0, z1, floor, gaps=0.0):
    for z in range(min(z0, z1), max(z0, z1) + 1):
        if r.random() >= gaps and g.get(x, floor + 1, z) == "air":
            g.set(x, floor + 1, z, "rail[shape=north_south]")


def rails_x(z, x0, x1, floor, gaps=0.0):
    for x in range(min(x0, x1), max(x0, x1) + 1):
        if r.random() >= gaps and g.get(x, floor + 1, z) == "air":
            g.set(x, floor + 1, z, "rail[shape=east_west]")


def cart(x, y, z, load):
    """A chest minecart standing on a rail, loaded with ore (shown on top), that nobody can ride nor break."""
    g.entities.append(f'summon chest_minecart {x + 0.5} {y} {z + 0.5} {{Invulnerable:1b,CustomDisplayTile:1b,'
                      f'DisplayState:{{Name:"minecraft:{load}"}},DisplayOffset:8}}')


def fallen_cart(x, z, floor, facing="east"):
    """An overturned cart: its iron sides on the ground, its load spilled."""
    g.set(x, floor + 1, z, f"iron_trapdoor[facing={facing},open=true,half=bottom]")
    g.set(x, floor + 1, z + 1, f"iron_trapdoor[facing={facing},open=true,half=bottom]")
    for dx, dz in ((1, 0), (1, 1), (2, 0)):
        if g.get(x + dx, floor + 1, z + dz) == "air":
            g.set(x + dx, floor + 1, z + dz, r.choice(["coal_ore", "cobblestone", "iron_ore", "deepslate_coal_ore"]))


def barrels(x, z, floor, n=1):
    for i in range(n):
        g.set(x, floor + 1 + i, z, "barrel[facing=up]")


def crate(x, z, floor, open_=False):
    g.set(x, floor + 1, z, "spruce_planks")
    g.set(x, floor + 2, z, "spruce_trapdoor[half=bottom,facing=north,open=true]" if open_ else "spruce_trapdoor[half=bottom,facing=north]")


def pile(x, z, floor, mats, rad=1.5, h=2):
    for dx in range(-2, 3):
        for dz in range(-2, 3):
            hh = h - (abs(dx) + abs(dz)) * h / (rad * 2 + 0.1)
            for y in range(floor + 1, floor + 1 + max(0, int(round(hh)))):
                if g.get(x + dx, y, z + dz) == "air":
                    g.set(x + dx, y, z + dz, r.choice(mats))


def lantern_under(x, y, z):
    if g.get(x, y, z) == "air" and not gridlib.passable(g.get(x, y + 1, z)):
        g.set(x, y, z, "lantern[hanging=true]")


def tool(x, y, z, item, yaw, roll=35, scale=1.1):
    g.item_display(x, y, z, item, yaw=yaw, roll=roll, scale=scale)


def rough_walls(x0, y0, z0, x1, y1, z1, seed, depth=1.6):
    """Side walls scraped unevenly (traces of the picks): small blobs of air cut into the rock of both long sides,
    never into the end walls (they carry the doors) nor past the room's length."""
    for i in range(int(abs(z1 - z0) * 0.9)):
        x = x0 - 1 if i % 2 == 0 else x1 + 1
        z = r.randint(z0 + 2, z1 - 2)
        y = r.randint(y0 + 1, y1)
        g.blob(x, y, z, depth, depth, depth, (x - 3, y0 + 1, z0 + 1, x + 3, y1, z1 - 1), seed + i, 0.4, block="air",
               where=lambda b: b not in OPEN and not gridlib.passable(b))


# ======================================================================== the yard and the entrance

def yard():
    g.blob(0, F + 20, -18, 15, 30, 12, (-17, F + 1, -33, 17, Y1, -7), seed=1, rough=0.25)
    g.box(-18, F + 16, -34, 18, F + 16, -6, "barrier", where=lambda b: b == "air")          # invisible lid
    for x in range(-17, 18):
        for z in range(-33, -6):
            if g.get(x, F + 1, z) == "air":
                for y in range(Y0, F + 1):
                    if g.get(x, y, z) == "air":
                        g.set(x, y, z, ROCK)
                g.set(x, F, z, r.choice(["gravel", "coarse_dirt", "cobblestone", "stone", "coarse_dirt"]))
    g.box(-3, F, -32, 3, F, -7, lambda *a: r.choice(["gravel", "cobblestone", "stone", "gravel"]))
    # the portal in the cliff: heavy timber and iron brackets
    tunnel(-4, 4, -8, 19, F, 6)
    for x in (-5, 5):
        g.box(x, F + 1, -8, x, F + 8, -8, "spruce_log")
        g.set(x, F + 1, -9, "spruce_log")
    g.box(-6, F + 8, -8, 6, F + 8, -8, "stripped_spruce_log[axis=x]")
    g.box(-6, F + 9, -8, 6, F + 9, -8, "dark_oak_planks")
    for x in (-4, 4):
        g.set(x, F + 7, -8, "spruce_stairs[facing=%s,half=top]" % ("west" if x < 0 else "east"))
        g.set(x, F + 8, -9, "iron_bars[east=true,west=true]")
    lantern_under(-3, F + 7, -9)
    lantern_under(3, F + 7, -9)
    g.set(-6, F + 1, -10, "oak_sign[rotation=8]")
    # the checkpoint hut (east)
    hx0, hx1, hz0, hz1 = 7, 13, -26, -19
    g.box(hx0, F + 1, hz0, hx1, F + 4, hz1, lambda x, y, z: "spruce_log" if x in (hx0, hx1) and z in (hz0, hz1) else "spruce_planks")
    g.box(hx0 + 1, F + 1, hz0 + 1, hx1 - 1, F + 4, hz1 - 1, "air")
    g.box(hx0, F + 1, -23, hx0, F + 2, -22, "air")
    g.box(hx1, F + 3, -23, hx1, F + 3, -22, "glass_pane[north=true,south=true]")
    for k in range(4):
        g.box(hx0 - 1 + k, F + 5 + k, hz0 - 1, hx0 - 1 + k, F + 5 + k, hz1 + 1, "spruce_stairs[facing=east]")
        g.box(hx1 + 1 - k, F + 5 + k, hz0 - 1, hx1 + 1 - k, F + 5 + k, hz1 + 1, "spruce_stairs[facing=west]")
    g.box(hx0, F + 5, hz0, hx1, F + 7, hz0, "spruce_planks", where=lambda b: b == "air")
    g.box(hx0, F + 5, hz1, hx1, F + 7, hz1, "spruce_planks", where=lambda b: b == "air")
    g.box(hx0 + 3, F + 8, hz0 - 1, hx0 + 3, F + 8, hz1 + 1, "dark_oak_planks")
    g.set(12, F + 1, -25, "crafting_table")
    g.set(12, F + 1, -20, "chest[facing=west]")
    g.set(8, F + 1, -25, "barrel[facing=up]")
    g.set(10, F + 3, -22, "lantern[hanging=true]")
    tool(12.5, F + 2.1, -24.5, "iron_pickaxe", yaw=0, roll=20)
    # the store (west): barrels, chests, sacks, ore and rubble heaps, an anvil, a furnace
    for x, z in ((-13, -26), (-12, -26), (-13, -25)):
        barrels(x, z, F, r.randint(1, 2))
    g.set(-13, F + 1, -22, "chest[facing=east]")
    g.set(-13, F + 1, -21, "chest[facing=east]")
    g.set(-12, F + 1, -18, "anvil[facing=north]")
    g.set(-13, F + 1, -16, "furnace[facing=east,lit=false]")
    g.set(-13, F + 1, -15, "furnace[facing=east,lit=false]")
    for x, z in ((-10, -24), (-11, -13)):
        g.set(x, F + 1, z, "brown_wool")
        g.set(x, F + 2, z, "brown_carpet")
    pile(-9, -12, F, ["cobblestone", "cobblestone", "gravel" if False else "stone"], h=2)
    pile(-10, -29, F, ["coal_ore", "iron_ore", "stone", "coal_block"], h=2)
    pile(9, -13, F, ["iron_ore", "copper_ore", "stone"], h=1)
    tool(-12.4, F + 1.6, -19.5, "iron_shovel", yaw=-90, roll=30)
    tool(-12.4, F + 1.6, -20.5, "stone_pickaxe", yaw=-90, roll=-30)
    for x, z in ((-5, -28), (5, -28), (-5, -18), (5, -16)):
        g.box(x, F + 1, z, x, F + 3, z, "spruce_fence")
        g.set(x, F + 4, z, "lantern")
    rails(-2, -32, 19, F)
    cart(-2, F + 1, -27, "coal_ore")
    cart(-2, F + 1, -14, "iron_ore")
    # the entrance tunnel: a timber set every 4 blocks, lanterns from the caps
    for z in range(-4, 19, 4):
        timber_set(-4, 4, z, F, 5)
        lantern_under(1, F + 5, z)
    rough_walls(-4, F, -6, 4, F + 6, 18, seed=10, depth=1.2)
    g.start = (0, F + 1, -24)
    m.start = [0.5, float(F + 1), -23.5, 0.0, 0.0]


# ======================================================================== room 1: extraction galleries

def room1():
    tunnel(-5, 5, 19, 61, F, 7)
    tunnel(-9, 9, 33, 49, F, 9)                         # the junction chamber
    # side tunnels to working faces (dead ends)
    for zc, sx, length, ores in ((37, -1, 13, [("coal_ore", 3), ("iron_ore", 2)]),
                                 (45, 1, 13, [("copper_ore", 3), ("gold_ore", 1)]),
                                 (25, -1, 9, [("redstone_ore", 2), ("lapis_ore", 2)])):
        for k in range(length):
            x = sx * (6 + k if zc != 37 or True else 0)
            if zc == 37 or zc == 45:
                x = sx * (10 + k)
            dz = round(1.2 * math.sin(k * 0.5))
            tunnel(min(x, x), max(x, x), zc - 2 + dz, zc + 2 + dz, F, 5)
        tip = sx * ((10 if zc != 25 else 6) + length)
        g.blob(tip, F + 3, zc, 2.5, 3, 3, (tip - 4, F + 1, zc - 4, tip + 4, F + 6, zc + 4), seed=20 + zc, rough=0.4, block="air",
               where=lambda b: b not in OPEN)
        veins_in(tip - 4, F, zc - 4, tip + 4, F + 7, zc + 4, 7, ores)
        for k in range(0, length, 4):
            x = sx * ((10 if zc != 25 else 6) + k)
            timber_set(zc - 2, zc + 2, x, F, 4, axis="z")
        rails(0, 0, -1, F)                         # (no-op, keeps a rhythm in the random draws)
        mid = sx * ((10 if zc != 25 else 6) + length // 2)
        rails_x(zc, mid - sx * 4, tip - sx, F)
        cart(tip - sx * 2, F + 1, zc, ores[0][0])
        tool(tip - sx * 0.6 + 0.5, F + 1.7, zc + 1.5, "iron_pickaxe", yaw=90 if sx < 0 else -90, roll=50)
        lantern_under(mid, F + 4, zc)
    rough_walls(-5, F, 19, 5, F + 7, 61, seed=30)
    rough_walls(-9, F, 33, 9, F + 9, 49, seed=31)
    veins_in(-10, F, 19, 10, F + 9, 62, 18, ORES["upper"])
    for z in range(21, 62, 4):
        if 33 <= z <= 49:
            continue
        timber_set(-5, 5, z, F, 6)
        lantern_under(2, F + 6, z)
    for z in (35, 41, 47):
        timber_set(-9, 9, z, F, 8)
        lantern_under(-4, F + 8, z)
        lantern_under(4, F + 8, z)
    rails(-4, 19, 62, F)
    cart(-4, F + 1, 52, "coal_ore")
    for x, z, n in ((8, 34, 2), (8, 48, 1), (-8, 48, 1)):
        barrels(x, z, F, n)
    crate(-8, 34, F, open_=True)
    tool(8.4, F + 2.6, 40.5, "iron_shovel", yaw=-90, roll=-25)
    for x, z in ((-2, 24), (2, 28), (-5, 38), (4, 42), (-2, 46), (3, 55)):
        m.mob("mineur_corrompu", x, z, y=F + 1, yaw=180, room="1")
    for x, z in ((6, 36), (-6, 44), (0, 58)):
        m.mob("mineur_rat", x, z, y=F + 1, yaw=180, room="1")
    for x, z in ((-3, 50), (2, 60)):
        m.mob("mineur_araignee", x, z, y=F + 1, yaw=180, room="1")
    # the reinforced wooden door, two steps down to the hall
    tunnel(-3, 3, 63, 66, F2, 6)
    g.box(-3, F2 + 1, 63, 3, F2 + 1, 64, "air")
    g.box(-3, F, 62, 3, F, 62, lambda x, y, z: paint(x, y, z))
    g.box(-3, F2 + 2, 63, 3, F2 + 2, 63, "cobblestone_stairs[facing=north]")
    g.box(-3, F2 + 1, 64, 3, F2 + 1, 64, "cobblestone_stairs[facing=north]")
    g.box(-4, F + 1, 62, -4, F + 7, 62, "spruce_log")
    g.box(4, F + 1, 62, 4, F + 7, 62, "spruce_log")
    g.box(-4, F + 7, 62, 4, F + 7, 62, "stripped_spruce_log[axis=x]")
    g.box(-3, F + 1, 62, 3, F + 6, 62, lambda x, y, z: "iron_block" if y == F + 3 else ("spruce_log" if x == 0 else "spruce_planks"))
    door("porte_galeries", (-3, F + 1, 62), (3, F + 6, 62), room="1")


# ======================================================================== room 2: the great cart hall

HX, HZ0, HZ1 = 20, 67, 105


def room2():
    tunnel(-HX, HX, HZ0, HZ1 - 1, F2, 14)
    g.blob(0, F2 + 14, (HZ0 + HZ1) // 2, 22, 5, 20, (-HX, F2 + 15, HZ0, HX, F2 + 20, HZ1 - 2), seed=50, rough=0.4, block="air",
           where=lambda b: b not in OPEN)
    rough_walls(-HX, F2, HZ0, HX, F2 + 14, HZ1 - 1, seed=51)
    veins_in(-22, F2, HZ0, 22, F2 + 16, HZ1, 22, ORES["mid"])
    # roof trusses across the hall, posts against the walls
    for z in range(HZ0 + 2, HZ1 - 1, 6):
        for x in (-HX, HX):
            g.box(x, F2 + 1, z, x, F2 + 11, z, "spruce_log")
        g.box(-HX, F2 + 12, z, HX, F2 + 12, z, "stripped_spruce_log[axis=x]")
        g.box(0, F2 + 13, z, 0, F2 + 15, z, "spruce_log")
        for k in range(1, 4):
            for sx in (-1, 1):
                g.set(sx * k, F2 + 12 + k, z, f"spruce_stairs[facing={'east' if sx < 0 else 'west'},half=bottom]") \
                    if g.get(sx * k, F2 + 12 + k, z) == "air" else None
        for x in (-12, -4, 4, 12):
            g.set(x, F2 + 11, z, "iron_chain[axis=y]")
            g.set(x, F2 + 10, z, "lantern[hanging=true]")
    for x in (-10, 10):
        g.box(x, F2 + 12, HZ0, x, F2 + 12, HZ1 - 1, "stripped_spruce_log[axis=z]")
    # side rail lanes, into small tunnels in the walls
    for x in (-17, 17):
        rails(x, HZ0 + 1, HZ1 - 2, F2)
    for x, z in ((-21, 74), (-21, 96), (21, 80), (21, 99)):
        sx = 1 if x > 0 else -1
        for k in range(0, 6):
            for dz in (-1, 0, 1):
                for y in range(F2 + 1, F2 + 4):
                    g.set(x + sx * k, y, z + dz, "air")
            g.set(x + sx * k, F2, z, paint(x + sx * k, F2, z))
        rails_x(z, x - sx * 3, x + sx * 5, F2)
        timber_set(z - 1, z + 1, x, F2, 3, axis="z")
    # the loading platform (west) and its crane
    g.box(-HX + 1, F2 + 4, 72, -15, F2 + 4, 98, "spruce_planks")
    g.box(-15, F2 + 5, 72, -15, F2 + 5, 98, "spruce_fence")
    for z in range(72, 99, 5):
        g.box(-15, F2 + 1, z, -15, F2 + 3, z, "spruce_log")
    g.box(-15, F2 + 5, 96, -15, F2 + 5, 98, "air")
    for k in range(4):
        g.box(-14 + k, F2 + 4 - k, 96, -14 + k, F2 + 4 - k, 98, "spruce_stairs[facing=west]")
    g.box(-18, F2 + 5, 85, -18, F2 + 13, 85, "spruce_log")
    g.box(-18, F2 + 13, 85, -11, F2 + 13, 85, "stripped_spruce_log[axis=x]")
    g.box(-11, F2 + 8, 85, -11, F2 + 12, 85, "iron_chain[axis=y]")
    g.set(-11, F2 + 7, 85, "iron_block")
    g.set(-17, F2 + 5, 86, "grindstone[face=floor,facing=east]")
    for z in range(74, 95, 3):
        k = r.random()
        if k < 0.4:
            barrels(-19, z, F2 + 4, r.randint(1, 2))
        elif k < 0.7:
            crate(-18, z, F2 + 4)
        else:
            g.set(-19, F2 + 5, z, "coal_block")
    # the smithy and the stores (east)
    for z in range(70, 102):
        k = r.random()
        if g.get(19, F2 + 1, z) != "air":
            continue
        if 70 <= z <= 76:
            g.set(19, F2 + 1, z, r.choice(["furnace[facing=west,lit=false]", "blast_furnace[facing=west,lit=false]", "smoker[facing=west,lit=false]"]))
        elif z in (78, 81):
            g.set(17 if z == 78 else 18, F2 + 1, z, "anvil[facing=north]")
        elif z == 79:
            g.set(19, F2 + 1, z, "crafting_table")
        elif 84 <= z <= 92:
            pile(18, z, F2, ["coal_block", "coal_ore", "coal_block"], h=2) if z == 87 else None
        elif k < 0.4:
            g.box(19, F2 + 1, z, 19, F2 + r.randint(1, 2), z, "iron_block" if r.random() < 0.4 else "barrel[facing=up]")
        elif k < 0.7:
            crate(19, z, F2, open_=r.random() < 0.3)
        else:
            g.set(19, F2 + 1, z, "chest[facing=west]")
    g.box(19, F2 + 2, 72, 19, F2 + 14, 72, "cobblestone")               # the smithy's chimney
    tool(18.5, F2 + 2.2, 80.5, "iron_pickaxe", yaw=-90, roll=15)
    tool(18.5, F2 + 2.2, 82.5, "iron_axe", yaw=-90, roll=-15)
    # carts on the lanes, a catwalk high across (out of reach)
    for x, z, load in ((-17, 70, "coal_ore"), (-17, 92, "iron_ore"), (17, 76, "copper_ore"), (17, 100, "gold_ore")):
        cart(x, F2 + 1, z, load)
    g.box(-14, F2 + 9, 88, 14, F2 + 9, 89, "spruce_slab[type=top]")
    g.box(-14, F2 + 10, 88, 14, F2 + 10, 88, "spruce_fence")
    for x in (-8, 0, 8):
        g.box(x, F2 + 10, 89, x, F2 + 11, 89, "iron_chain[axis=y]")
    g.box(-HX, F2 + 1, HZ0, HX, F2 + 1, HZ0, "air")
    for x, z in ((-8, 72), (8, 75), (-4, 86), (5, 90)):
        m.mob("mineur_corrompu", x, z, y=F2 + 1, yaw=180, room="2")
    for x, z in ((-10, 95), (10, 84), (0, 79)):
        m.mob("mineur_araignee", x, z, y=F2 + 1, yaw=180, room="2")
    m.mob("mineur_contremaitre", 0, 96, y=F2 + 1, yaw=180, room="2")
    # the iron grille and the way down
    g.box(-4, F2 + 1, HZ1, 4, F2 + 7, HZ1, "stone_bricks")
    g.box(-3, F2 + 1, HZ1, 3, F2 + 5, HZ1, "iron_bars[east=true,west=true]")
    g.box(-4, F2 + 6, HZ1 - 1, 4, F2 + 6, HZ1 - 1, "chiseled_stone_bricks")
    door("porte_wagonnets", (-3, F2 + 1, HZ1), (3, F2 + 5, HZ1), room="2")
    for z in range(HZ1 + 1, 116):
        f = F2 - min(6, (z - HZ1 - 1))
        tunnel(-3, 3, z, z, f, 5)
        if f < F2 - 0 and g.get(0, f + 1, z) == "air" and z > HZ1 + 1:
            pass
        g.box(-3, f + 1, z, 3, f + 1, z, "cobbled_deepslate_stairs[facing=north]" if (z - HZ1) % 1 == 0 and f > F3 else "air")
        if f <= F3:
            g.box(-3, f + 1, z, 3, f + 1, z, "air")
        if (z - HZ1) % 4 == 0:
            timber_set(-3, 3, z, f, 4, broken=True)


# ======================================================================== room 3: the forgotten depths

def room3():
    tunnel(-13, 13, 116, 146, F3, 8)
    g.blob(0, F3 + 8, 131, 15, 4, 16, (-13, F3 + 9, 116, 13, F3 + 12, 146), seed=70, rough=0.5, block="air", where=lambda b: b not in OPEN)
    rough_walls(-13, F3, 116, 13, F3 + 8, 146, seed=71, depth=2.0)
    veins_in(-16, F3, 114, 16, F3 + 12, 148, 14, ORES["deep"])
    # broken timbering, fallen beams
    for z in range(118, 146, 5):
        timber_set(-13, 13, z, F3, 7, broken=True)
    for z, x in ((122, -11), (133, 11), (141, -10)):
        g.box(x, F3 + 1, z - 2, x, F3 + 1, z + 2, "stripped_spruce_log[axis=z]")
    # abandoned broken rails, an overturned cart, forgotten stores, cobwebs
    rails(10, 116, 146, F3, gaps=0.35)
    fallen_cart(8, 127, F3)
    for x, z in ((-12, 118), (12, 144), (-12, 139)):
        g.set(x, F3 + 1, z, "barrel[facing=north]")
        g.set(x + (1 if x < 0 else -1), F3 + 1, z, "spruce_trapdoor[half=bottom,facing=north]")
    for x, y, z in ((-13, F3 + 7, 117), (13, F3 + 7, 145), (-12, F3 + 6, 131), (12, F3 + 7, 120)):
        if g.get(x, y, z) == "air":
            g.set(x, y, z, "cobweb")
    # the collapsed passage (west): a tunnel mouth choked with rubble
    for k in range(1, 8):
        tunnel(-13 - k, -13 - k, 124, 128, F3, 5)
    for k in range(3, 8):
        for z in range(124, 129):
            for y in range(F3 + 1, F3 + 1 + min(5, k - 1)):
                g.set(-13 - k, y, z, r.choice(["cobbled_deepslate", "cobblestone", "tuff", "stone"]))
    timber_set(124, 128, -14, F3, 4, broken=True, axis="z")
    # the natural cavity (east) with rare ores
    g.blob(19, F3 + 4, 136, 6, 4, 5, (14, F3 + 1, 130, 26, F3 + 9, 142), seed=72, rough=0.45, block="air", where=lambda b: b not in OPEN)
    tunnel(13, 15, 134, 138, F3, 4)
    veins_in(14, F3, 130, 26, F3 + 9, 142, 6, [("deepslate_diamond_ore", 2), ("deepslate_emerald_ore", 1), ("deepslate_gold_ore", 2)])
    crystals = [(x, y, z) for x in range(14, 26) for y in range(F3 + 4, F3 + 9) for z in range(131, 142)
                if g.get(x, y, z) not in OPEN and g.get(x, y - 1, z) == "air"]
    r.shuffle(crystals)
    for x, y, z in crystals[:5]:
        g.set(x, y, z, "amethyst_block")
        g.set(x, y - 1, z, "amethyst_cluster[facing=down]")
    # the old vertical shaft (west wall), its cage stuck halfway up, fenced off
    sx0, sz0 = -19, 139
    for y in range(F3 + 1, F3 + 30):
        for x in range(sx0, sx0 + 4):
            for z in range(sz0, sz0 + 4):
                g.set(x, y, z, "air")
    tunnel(-15, -14, 140, 141, F3, 4)
    g.box(-15, F3 + 1, 140, -15, F3 + 1, 141, "spruce_fence")
    g.box(-15, F3 + 2, 140, -15, F3 + 5, 141, "barrier")
    g.box(sx0, F3, sz0, sx0 + 3, F3, sz0 + 3, "cobbled_deepslate")
    g.box(sx0, F3 + 9, sz0, sx0 + 3, F3 + 9, sz0 + 3, "spruce_slab[type=bottom]")
    g.box(sx0, F3 + 10, sz0, sx0 + 3, F3 + 10, sz0, "spruce_fence")
    for x, z in ((sx0, sz0), (sx0 + 3, sz0 + 3)):
        g.box(x, F3 + 10, z, x, F3 + 29, z, "iron_chain[axis=y]")
    g.box(sx0 + 3, F3 + 12, sz0 + 1, sx0 + 3, F3 + 28, sz0 + 1, "ladder[facing=west]")
    g.set(sx0 + 1, F3 + 20, sz0 + 1, "light[level=8]")
    for x, z in ((-6, 125), (6, 137), (0, 131)):
        g.set(x, F3 + 7, z, "soul_lantern[hanging=true]") if not gridlib.passable(g.get(x, F3 + 8, z)) else g.set(x, F3 + 6, z, "light[level=9]")
    for x, z in ((-8, 121), (8, 121), (-8, 141), (8, 141), (0, 131)):
        g.set(x, F3 + 5, z, "light[level=10]")
    for x, z in ((-7, 121), (7, 124), (-5, 136), (6, 140)):
        m.mob("mineur_araignee", x, z, y=F3 + 1, yaw=180, room="3")
    for x, z in ((-3, 129), (4, 132), (0, 143)):
        m.mob("mineur_rat", x, z, y=F3 + 1, yaw=180, room="3")
    for x, z in ((-9, 133), (9, 128), (-2, 138)):
        m.mob("mineur_corrompu", x, z, y=F3 + 1, yaw=180, room="3")
    # the passage blocked by debris (the door), then the way down to the arena
    tunnel(-3, 3, 147, 149, F3, 5)
    g.box(-3, F3 + 1, 147, 3, F3 + 5, 147, lambda *a: r.choice(["cobbled_deepslate", "cobblestone", "tuff", "deepslate", "spruce_planks"]))
    timber_set(-3, 3, 148, F3, 5, broken=False)
    door("porte_profondeurs", (-3, F3 + 1, 147), (3, F3 + 5, 147), room="3")
    for z in range(150, AZ0):
        f = max(FA, F3 - (z - 150) // 3)
        tunnel(-3, 3, z, z, f, 5)
        if z % 4 == 0:
            timber_set(-3, 3, z, f, 4, broken=r.random() < 0.5)
            lantern_under(0, f + 4, z)
        if g.get(0, f, z - 1) not in OPEN and (z - 150) % 3 == 0 and z > 150 and f > FA:
            pass
    rails(2, 150, AZ0 - 1, FA, gaps=0.5)
    for z in range(150, AZ0):
        f = max(FA, F3 - (z - 150) // 3)
        if g.get(2, f + 1, z).startswith("rail") and g.get(2, f, z) in OPEN:
            g.set(2, f + 1, z, "air")


# ======================================================================== the arena of the Copper T-Rex

def arena():
    # the floor plan of the first version: air 35 x 39, walls solid up to FA+3
    for x in range(AX0, AX1 + 1):
        for z in range(AZ0, AZ1 + 1):
            g.set(x, FA, z, r.choice(["deepslate", "cobbled_deepslate", "cobbled_deepslate", "tuff", "polished_deepslate", "deepslate_tiles", "stone"]))
            for y in range(FA + 1, ATOP + 1):
                g.set(x, y, z, "air")
    g.box(-3, FA + 1, AZ0 - 1, 3, FA + 5, AZ0 - 1, "air")                 # the way in (as in the first version)
    # above the solid band: ledges of old workings on all sides, out of reach
    for y in range(FA + 4, ATOP + 1):
        for x in range(AX0 - 4, AX1 + 5):
            for z in range(AZ0 - 4, AZ1 + 5):
                if not (AX0 <= x <= AX1 and AZ0 <= z <= AZ1):
                    edge = min(abs(x - AX0 + 1), abs(x - AX1 - 1), abs(z - AZ0 + 1), abs(z - AZ1 - 1))
                    if y >= FA + 5 and (AX0 - 3 <= x <= AX1 + 3 and AZ0 - 3 <= z <= AZ1 + 3):
                        g.set(x, y, z, "air")
    for x in range(AX0 - 3, AX1 + 4):
        for z in range(AZ0 - 3, AZ1 + 4):
            if not (AX0 <= x <= AX1 and AZ0 <= z <= AZ1):
                g.set(x, FA + 4, z, "spruce_planks" if (x + z) % 5 else "spruce_log")
                if (abs(x - AX0 + 1) == 0 or abs(x - AX1 - 1) == 0 or abs(z - AZ0 + 1) == 0 or abs(z - AZ1 - 1) == 0):
                    g.set(x, FA + 5, z, "spruce_fence")
    g.box(-3, FA + 5, AZ0 - 1, 3, FA + 5, AZ0 - 1, "air")
    g.blob(0, ATOP, (AZ0 + AZ1) // 2, 21, 5, 22, (AX0 - 3, ATOP + 1, AZ0 - 3, AX1 + 3, ATOP + 6, AZ1 + 3), seed=90, rough=0.4,
           block="air", where=lambda b: b not in OPEN)
    rough_walls(AX0 - 3, FA + 5, AZ0 - 3, AX1 + 3, ATOP, AZ1 + 3, seed=91, depth=1.8)
    veins_in(AX0 - 6, FA, AZ0 - 6, AX1 + 6, ATOP + 4, AZ1 + 6, 30, [("deepslate_copper_ore", 6), ("deepslate_iron_ore", 2), ("copper_ore", 2)])
    # the ledges' workings: rails, overturned carts, ore heaps, crates, lanterns
    for x in (AX0 - 2, AX1 + 2):
        rails(x, AZ0, AZ1, FA + 4, gaps=0.25)
    for z in (AZ0 - 2, AZ1 + 2):
        rails_x(z, AX0, AX1, FA + 4, gaps=0.3)
    fallen_cart(AX0 - 3, AZ0 + 8, FA + 4, "west")
    fallen_cart(AX1 + 1, AZ1 - 10, FA + 4, "east")
    for x, z in ((AX0 - 3, AZ0 + 20), (AX1 + 3, AZ0 + 5), (AX1 + 3, AZ1 - 3), (-8, AZ1 + 3), (8, AZ0 - 3)):
        pile(x, z, FA + 4, ["deepslate_copper_ore", "cobbled_deepslate", "copper_ore", "deepslate_coal_ore"], h=2)
    for x, z in ((AX0 - 3, AZ0 + 28), (AX1 + 3, AZ0 + 17), (0, AZ1 + 3)):
        crate(x, z, FA + 4, open_=True)
    cart(AX0 - 2, FA + 5, AZ0 + 14, "deepslate_copper_ore")
    cart(AX1 + 2, FA + 5, AZ0 + 24, "deepslate_iron_ore")
    # trusses high across, lanterns from them, posts on the ledges
    for z in range(AZ0 + 1, AZ1, 6):
        g.box(AX0 - 3, FA + 13, z, AX1 + 3, FA + 13, z, "stripped_spruce_log[axis=x]")
        for x in (AX0 - 3, AX1 + 3):
            g.box(x, FA + 5, z, x, FA + 12, z, "spruce_log")
        for x in (-12, -4, 4, 12):
            g.set(x, FA + 12, z, "iron_chain[axis=y]")
            g.set(x, FA + 11, z, "lantern[hanging=true]")
    # the four pillars (the charge stops on them): to the vault, copper band as in the first version
    for x, z in PILLARS:
        g.box(x, FA + 1, z, x + 1, ATOP + 3, z + 1, "deepslate_bricks")
        g.box(x, FA + 2, z, x + 1, FA + 3, z + 1, "waxed_copper_block")
        g.box(x, FA + 8, z, x + 1, FA + 8, z + 1, "waxed_cut_copper")
        g.set(x, FA + 6, z - 1, "waxed_copper_bulb[lit=true]") if g.get(x, FA + 6, z - 1) == "air" else None
    # the five raw copper veins (the only raw copper of the map)
    for v in VEINS:
        for b in v:
            g.set(b[0], FA + 1 + (b[2] if len(b) > 2 else 0), b[1] + DZ, "raw_copper_block")
    for x in range(AX0 + 2, AX1, 6):
        for z in range(AZ0 + 2, AZ1, 6):
            if g.get(x, FA + 9, z) == "air":
                g.set(x, FA + 9, z, "light[level=12]")
    for x, z in ((AX0 - 1, AZ0 + 10), (AX1 + 1, AZ0 + 20), (AX0 - 1, AZ1 - 6), (AX1 + 1, AZ1 - 12)):
        g.set(x, FA + 7, z, "magma_block")                                    # glowing cracks, high on the walls
    m.mob("trex_cuivre", 0, 28 + DZ, y=FA + 1, yaw=180, room="4")
    m.objectives = ["trex_cuivre"]
    # the way out (north), opened when it dies
    g.box(-2, FA + 1, AZ1 + 1, 2, FA + 3, AZ1 + 7, "air")
    g.box(-2, FA, AZ1 + 1, 2, FA, AZ1 + 7, "cobbled_deepslate")
    g.box(-2, FA + 1, AZ1 + 1, 2, FA + 3, AZ1 + 1, "iron_bars[east=true,west=true]")
    door("porte_sortie", (-2, FA + 1, AZ1 + 1), (2, FA + 3, AZ1 + 1), room="4")
    lantern_under(0, FA + 4, AZ1 + 5)


# ======================================================================== paint, checks, main

def paint_all():
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
        if gridlib.gravity(b) and (gridlib.passable(g.get(p[0], p[1] - 1, p[2])) or gridlib.attached(g.get(p[0], p[1] - 1, p[2]))):
            g.set(*p, "stone")


def zone_of(x, y, z):
    if z <= 62:
        return 1
    if z <= HZ1:
        return 2
    if z <= 147:
        return 3
    if z <= AZ1 + 1 or y > FA + 4:
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
    # the arena of the first version: floor free but for the pillars and the veins, walls solid up to FA+3
    pillar = {(x + a, z + b) for x, z in PILLARS for a in (0, 1) for b in (0, 1)}
    raw = [p for p, b in g.over.items() if b == "raw_copper_block"]
    if len(raw) != 20:
        errors.append(f"{len(raw)} raw copper blocks (the 5 veins have 20)")
    for x in range(AX0, AX1 + 1):
        for z in range(AZ0, AZ1 + 1):
            if (x, z) in pillar:
                continue
            for y in (FA + 1, FA + 2, FA + 3):
                b = g.get(x, y, z)
                if b != "air" and b != "raw_copper_block" and not b.startswith("light"):
                    errors.append(f"arena not free at {x} {y} {z}: {b}")
                    break
    for x in range(AX0 - 1, AX1 + 2):
        for z in range(AZ0 - 1, AZ1 + 2):
            if AX0 <= x <= AX1 and AZ0 <= z <= AZ1:
                continue
            if z == AZ0 - 1 and abs(x) <= 3 or z == AZ1 + 1 and abs(x) <= 2:
                continue
            for y in (FA + 1, FA + 2, FA + 3):
                if gridlib.passable(g.get(x, y, z)):
                    errors.append(f"arena wall open at {x} {y} {z}")
                    break
    return errors


yard()
room1()
room2()
room3()
arena()
paint_all()
m.textures = "rpg"
m.ybox = (Y0, Y1)


# Troop roles (2026-11-13): about twice the monsters of the first version, half of them with a role (gelées that
# leap and split, fast pests, brutes, spitters, snipers, kamikazes, supports), placed next to those above.
REINFORCE = {
    "1": ['mineur_gelee', 'mineur_gelee', 'mineur_gelee', 'mineur_galopin', 'mineur_galopin', 'mineur_galopin', 'mineur_dynamiteur', 'mineur_dynamiteur', 'mineur_dynamiteur', 'mineur_artificier', 'mineur_artificier'],
    "2": ['mineur_dynamiteur', 'mineur_dynamiteur', 'mineur_dynamiteur', 'mineur_artificier', 'mineur_artificier', 'mineur_galopin', 'mineur_galopin', 'mineur_gelee'],
    "3": ['mineur_gelee', 'mineur_gelee', 'mineur_gelee', 'mineur_galopin', 'mineur_galopin', 'mineur_galopin', 'mineur_dynamiteur', 'mineur_dynamiteur', 'mineur_artificier', 'mineur_artificier'],
}


# Room for the monsters (2026-11-14: twice the monsters of the first version in the same mine). Rooms are made larger
# by mirroring a slice of them (gridlib.stretch; coordinates as written above): the extraction gallery becomes a hall
# 31 wide instead of 11 and 10 longer (its side galleries move out with its walls), the wagon hall gets 6 blocks longer, the
# forgotten depths 20 wider and 10 longer. Corridors, doors and the arena of the T-Rex are untouched.
STRETCH = [
    {"axis": "z", "at": 16, "n": 5, "mode": "mirror"},                                       # extraction gallery
    {"axis": "z", "at": 86, "n": 3, "mode": "mirror"},                                       # wagon hall
    {"axis": "z", "at": 131, "n": 5, "mode": "mirror"},                                      # forgotten depths
    {"axis": "x", "at": 5, "n": 5, "mode": "mirror", "span": (1, 61), "ragged": 3},                       # extraction gallery
    {"axis": "x", "at": -5, "n": 5, "dir": -1, "mode": "mirror", "span": (1, 61), "ragged": 3},
    {"axis": "x", "at": 5, "n": 5, "mode": "mirror", "span": (116, 146)},                    # forgotten depths
    {"axis": "x", "at": -5, "n": 5, "dir": -1, "mode": "mirror", "span": (116, 146)},
]


if __name__ == "__main__":
    args = sys.argv[1:]
    maplib.reinforce(m, g, REINFORCE, seed=104)
    errs = check()
    for e in errs[:50]:
        print("CHECK:", e)
    print(f"{len(errs)} problem(s), {len(g.over)} blocks changed, {len(g.entities)} entities")
    if not errs:
        maplib.widen(g, m, STRETCH, "mineur", args[args.index("--preview") + 1] if "--preview" in args else None, apart={"1": 1.8, "3": 1.5})
    if "--preview" in args:
        out = args[args.index("--preview") + 1]
        for y in (F + 1, F2 + 1, F3 + 1, FA + 1, FA + 5):
            g.png_slice(os.path.join(out, f"mineur_y{y}.png"), y=y, k=3)
        g.png_slice(os.path.join(out, "mineur_coupe_x0.png"), x=0, k=3)
    if "--check" in args or "--preview" in args:
        sys.exit(1 if errs else 0)
    if errs:
        sys.exit("checks failed: nothing sent")
    if "--edits" in args:
        maplib.write_edits(m, args[0] if args and not args[0].startswith("--") else "labs", "mineur")
        sys.exit(0)
    g.commands(m)
    print(f"{len(m.cmds)} commands")
    maplib.run(m, args[0] if args and not args[0].startswith("--") else "labs", "mineur")
