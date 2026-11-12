#!/usr/bin/env python3
"""Map of the herbalist dungeon, Le Sanctuaire des Herboristes (boss: the Sylvan Fox), modelled in memory (gridlib),
checked, then built by console commands with the RPG look of the dungeons.

A sanctuary of plants, nothing made by herbalists (no shop, no laboratory, no cauldron), played from south to north
(+z), ground at y 63, caves at 50, the arena at 44:
  - the Plant Gate (start at 0, 64, -10): a mossy stone arch wrapped in roots, vines and two trees meeting over it;
  - room 1, the Wild Path: an old path taken by the woods (oak, birch, dark oak, azaleas), fallen logs, ponds, mossy
    stones, flowers at the feet of the trees; brambles and vine weavers. Door: a gate of roots;
  - room 2, the Wild Garden: beds of colours (white and pink, yellow and orange, blue and violet with its pools of blue
    orchids), a giant tree, a mossy stump (the Herbarium altar), a waterfall, an arch of branches and vines; the
    Herbarium asks for blue orchids, lilies of the valley and alliums (cornflowers to mislead): a hedge sinks;
  - the Natural Greenhouse: a hollow in the hill open to the sky through a roof of leaves, jungle trees, vines,
    large ferns, spore blossoms;
  - the Passage of Roots: down into the hill (63 to 50) under giant roots, darker, glow lichen, cave vines (glow
    berries); vine weavers;
  - the Coral Basin: a damp cave, shallow pools of corals and seagrass on both sides, small falls, the way dry;
  - room 3 ends in the Mushroom Grotto: giant red and brown mushrooms, crimson and warped fungi, moss, hanging roots,
    mould archers; at its end the Old Bush (flowering azalea, altar) takes, in order, the ingredients of the Sylvan Sap
    (grant fdgrant_tisane_sylve, read by the boss fight: unchanged): a door of mushroom flesh sinks;
  - room 4, the Arid Flora: a sunken basin open to the sky, dunes of sand and red sand, cactus away from the way,
    dead bushes, strata of sandstone and terracotta; thorny husks; then the Threshold: giant trees, monumental roots,
    rare plants (torchflowers, pitcher plants), the Seal of Roots (rooted dirt on a pedestal): a door of roots;
  - the Sanctuary: the fox's square of the first version kept exactly (30 x 30, flat, nothing on it, the fox hidden
    in its middle), at the bottom of a sinkhole open to the sky, crowned by giant trees, roots, vines and flowers all
    round it, out of reach behind invisible walls.

Usage: gen_herboriste.py [server] [--check] [--preview DIR]     (labs by default)
"""
import math
import os
import sys

import gridlib
import maplib

F, C, A = 63, 50, 44
X0, X1, Y0, Y1, Z0, Z1 = -46, 46, 30, 112, -32, 300
BANDS = ((30, 56, "stone"), (57, 62, "dirt"), (63, 63, "grass_block"))
SQ = (-15, 14, 251, 280)                 # the fox's square (x0, x1, z0, z1), its middle (0.0, 266.0)
CURTAIN = 30
g = gridlib.Model((X0, X1, Y0, Y1, Z0, Z1), BANDS, seed=1147)
r = g.r
m = maplib.Map()
m.box = (X0, Z0, X1, Z1)
_door = m.door
N = [gridlib.Noise(s) for s in (501, 502, 503)]

LEAVES = {k: f"{k}_leaves[persistent=true]" for k in ("oak", "birch", "jungle", "dark_oak", "azalea", "flowering_azalea", "mangrove")}
LOGS = {"oak": "oak_log", "birch": "birch_log", "jungle": "jungle_log", "dark_oak": "dark_oak_log", "azalea": "oak_log",
        "mangrove": "mangrove_log"}
PALETTES = {
    "white_pink": [("oxeye_daisy", 3), ("lily_of_the_valley", 3), ("white_tulip", 2), ("pink_tulip", 2), ("azure_bluet", 2),
                   ("peony", 1), ("pink_petals[flower_amount=4,facing=north]", 2)],
    "yellow_orange": [("dandelion", 4), ("orange_tulip", 3), ("torchflower", 1), ("sunflower", 1), ("poppy", 1)],
    "blue_violet": [("cornflower", 3), ("allium", 3), ("blue_orchid", 1), ("lilac", 1), ("azure_bluet", 1)],
    "wild": [("short_grass", 10), ("fern", 4), ("dandelion", 1), ("poppy", 1), ("oxeye_daisy", 1), ("azure_bluet", 1)],
    "shade": [("fern", 5), ("short_grass", 4), ("lily_of_the_valley", 1), ("brown_mushroom", 1), ("large_fern", 2)],
}


def door(id, frm, to, room, offering=None):
    g.doors.append((tuple(min(a, b) for a, b in zip(frm, to)), tuple(max(a, b) for a, b in zip(frm, to))))
    _door(id, frm, to, room=room, type="offering" if offering else "kills")
    if offering:
        m.doors[-1]["offering"] = offering


def put(x, y, z, b, only_air=True):
    if not only_air or g.get(x, y, z) == "air":
        g.set(x, y, z, b)


def plant(x, y, z, b):
    """A plant on the soil at y - 1 (only on soil that keeps it, only in the air); two-block plants get their top."""
    soil = g.get(x, y - 1, z).split("[")[0]
    if soil not in ("grass_block", "dirt", "coarse_dirt", "rooted_dirt", "podzol", "moss_block", "mud", "farmland",
                    "mycelium", "clay", "sand", "red_sand", "crimson_nylium", "warped_nylium") or g.get(x, y, z) != "air":
        return
    base = b.split("[")[0]
    if base in ("lilac", "rose_bush", "peony", "sunflower", "large_fern", "pitcher_plant", "tall_grass"):
        if g.get(x, y + 1, z) != "air":
            return
        g.set(x, y, z, f"{base}[half=lower]")
        g.set(x, y + 1, z, f"{base}[half=upper]")
        return
    if base in ("cactus", "dead_bush") and soil not in ("sand", "red_sand"):
        if base == "cactus":
            return
    g.set(x, y, z, b)


def pick_flower(table):
    return g.pick(table)


def ground(x0, z0, x1, z1, y, table, where=None):
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if where and not where(x, z):
                continue
            v = (N[0](x * 0.4, 0, z * 0.4) + 1) / 2 + r.uniform(-0.12, 0.12)
            tot, acc = sum(w for _, w in table), 0
            for b, w in table:
                acc += w / tot
                if v < acc:
                    g.set(x, y, z, b)
                    break
            else:
                g.set(x, y, z, table[-1][0])


def tree(x, z, y, kind="oak", h=None):
    """A tree of its kind, never twice the same: trunk height, crown size and shape vary."""
    if kind == "dead":
        h = h or r.randint(5, 8)
        g.box(x, y, z, x, y + h - 1, z, "oak_log")
        for k in range(2):
            dx, dz = r.choice([(1, 0), (-1, 0), (0, 1), (0, -1)])
            for t in range(1, 3):
                put(x + dx * t, y + h - 2 - k * 2, z + dz * t, f"oak_log[axis={'x' if dx else 'z'}]")
        return
    h = h or {"oak": r.randint(5, 7), "birch": r.randint(6, 9), "jungle": r.randint(9, 14), "dark_oak": r.randint(6, 8),
              "azalea": r.randint(3, 4), "mangrove": r.randint(6, 9)}[kind]
    wide = kind in ("dark_oak", "jungle") and h >= 8
    for dx in range(2 if wide else 1):
        for dz in range(2 if wide else 1):
            g.box(x + dx, y, z + dz, x + dx, y + h - 1, z + dz, LOGS[kind])
    rad = {"oak": 3, "birch": 2, "jungle": 4, "dark_oak": 4, "azalea": 2, "mangrove": 3}[kind] + r.choice((0, 0, 1))
    leaves = LEAVES[kind]
    cy = y + h - (1 if kind != "dark_oak" else 0)
    flat = kind in ("dark_oak", "azalea")
    for ddx in range(-rad - 1, rad + 2):
        for ddy in range(-2, 3):
            for ddz in range(-rad - 1, rad + 2):
                d = (ddx / (rad + 0.5)) ** 2 + (ddz / (rad + 0.5)) ** 2 + (ddy / (1.4 if flat else 2.2)) ** 2
                if d <= 1 + 0.15 * N[1](x + ddx, cy + ddy, z + ddz) and r.random() < 0.94:
                    b = leaves
                    if kind == "azalea" and r.random() < 0.35:
                        b = LEAVES["flowering_azalea"]
                    put(x + ddx, cy + ddy, z + ddz, b)
    if kind in ("jungle", "mangrove"):
        for _ in range(rad * 2):
            vx, vz = x + r.randint(-rad, rad), z + r.randint(-rad, rad)
            for yy in range(cy - 2, cy - 2 - r.randint(1, 4), -1):
                if g.get(vx, yy, vz) != "air":
                    break
                g.set(vx, yy, vz, "cave_vines_plant[berries=false]" if False else "vine[north=true]" if g.get(vx, yy, vz - 1) != "air" else "air")


def giant_tree(x, z, y, h=20, crown=8, leaves="oak", roots=True):
    """A landmark: a 3 x 3 trunk, roots spreading on the ground, a huge crown."""
    g.box(x - 1, y, z - 1, x + 1, y + h, z + 1, "dark_oak_wood")
    if roots:
        for a in range(0, 360, 45):
            for t in range(2, 6):
                rx, rz = x + round(t * math.cos(math.radians(a))), z + round(t * math.sin(math.radians(a)))
                put(rx, y + (1 if t < 4 else 0), rz, "dark_oak_wood" if t < 4 else "mangrove_roots")
    cy = y + h
    for ddx in range(-crown - 1, crown + 2):
        for ddy in range(-3, 5):
            for ddz in range(-crown - 1, crown + 2):
                d = (ddx / crown) ** 2 + (ddz / crown) ** 2 + (ddy / 3.5) ** 2
                if d <= 1 + 0.2 * N[2](x + ddx, cy + ddy, z + ddz) and r.random() < 0.93:
                    put(x + ddx, cy + ddy, z + ddz, LEAVES[leaves] if r.random() < 0.85 else LEAVES["flowering_azalea"])
    for k in range(4):
        a = math.radians(45 + 90 * k)
        for t in range(1, crown - 1):
            put(x + round(t * math.cos(a)), cy - 1 + t // 3, z + round(t * math.sin(a)), "dark_oak_wood", only_air=False)


def bush(x, z, y, leaves="oak", big=False):
    rad = 2 if big else 1
    for dx in range(-rad, rad + 1):
        for dy in range(0, rad + 1):
            for dz in range(-rad, rad + 1):
                if dx * dx + dz * dz + dy * dy * 2 <= rad * rad + 1 and r.random() < 0.85:
                    put(x + dx, y + dy, z + dz, LEAVES[leaves] if r.random() < 0.8 else LEAVES["flowering_azalea"])


def fallen_log(x, z, y, length, axis="x"):
    for t in range(length):
        px, pz = (x + t, z) if axis == "x" else (x, z + t)
        g.set(px, y, pz, f"oak_log[axis={axis}]")
        if r.random() < 0.4:
            put(px, y + 1, pz, "moss_carpet")


def mossy_rock(x, z, y):
    g.blob(x, y, z, 1.6, 1.2, 1.6, (x - 3, y, z - 3, x + 3, y + 2, z + 3), seed=x * 31 + z, rough=0.5,
           block=lambda *a: r.choice(["mossy_cobblestone", "moss_block", "cobblestone", "mossy_cobblestone"]), where=lambda b: b == "air")


def pond(cx, cz, y, rad, orchids=True):
    for x in range(cx - rad - 1, cx + rad + 2):
        for z in range(cz - rad - 1, cz + rad + 2):
            d = math.hypot(x - cx, z - cz) + 0.4 * N[0](x, 0, z)
            if d <= rad:
                g.set(x, y, z, "water")
                g.set(x, y - 1, z, "clay")
                if r.random() < 0.15:
                    put(x, y + 1, z, "lily_pad")
            elif d <= rad + 1.2:
                g.set(x, y, z, "grass_block" if r.random() < 0.6 else "moss_block")
                if orchids and r.random() < 0.6:
                    plant(x, y + 1, z, "blue_orchid")


def flowerbed(cx, cz, y, rad, table, density=0.8):
    for x in range(cx - rad, cx + rad + 1):
        for z in range(cz - rad, cz + rad + 1):
            if math.hypot(x - cx, z - cz) + 0.8 * N[1](x * 0.6, 0, z * 0.6) <= rad and r.random() < density:
                plant(x, y + 1, z, pick_flower(table))


def roots_arch(x0, x1, z, y, h):
    """An arch of giant roots across a way, its crown h above the floor."""
    for x in range(x0, x1 + 1):
        t = (x - x0) / max(1, x1 - x0)
        top = y + round(h * math.sin(math.pi * t))
        for yy in range(top, top + 2):
            g.set(x, yy, z, "mangrove_roots" if (x + yy) % 3 else "dark_oak_wood")
    for x in (x0, x1):
        g.box(x, y + 1, z, x, y + 2, z, "dark_oak_wood")


def hang(x, y, z, length, kind="vine"):
    """Hanging from the block above (x, y + 1, z): vines (on a side), weeping vines, cave vines (berries), roots."""
    for i in range(length):
        yy = y - i
        if g.get(x, yy, z) != "air":
            return
        last = i == length - 1 or g.get(x, yy - 1, z) != "air"
        if kind == "cave_vines":
            g.set(x, yy, z, f"cave_vines{'' if last else '_plant'}[berries={'true' if r.random() < 0.45 else 'false'}]"
                  + ("" if True else ""))
            if last:
                g.set(x, yy, z, f"cave_vines[age=25,berries={'true' if r.random() < 0.5 else 'false'}]")
        elif kind == "weeping":
            g.set(x, yy, z, "weeping_vines[age=25]" if last else "weeping_vines_plant")
        elif kind == "roots":
            g.set(x, yy, z, "hanging_roots")
            return
        if last:
            return


def ceiling_hangs(x0, z0, x1, z1, y_from, n, kinds):
    for _ in range(n):
        x, z = r.randint(x0, x1), r.randint(z0, z1)
        top = g.ceiling(x, y_from, z)
        if top is None or g.get(x, top - 1, z) != "air":
            continue
        kind = r.choice(kinds)
        if kind == "spore_blossom":
            g.set(x, top - 1, z, "spore_blossom")
        else:
            hang(x, top - 1, z, r.randint(1, 4) if kind != "roots" else 1, kind)


def wall_vines(x0, y0, z0, x1, y1, z1, density):
    """Vines on the walls: an air block holding on the wall next to it."""
    face = {(1, 0): "east", (-1, 0): "west", (0, 1): "south", (0, -1): "north"}
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            for z in range(z0, z1 + 1):
                if g.get(x, y, z) != "air" or r.random() > density:
                    continue
                for (dx, dz), f in face.items():
                    nb = g.get(x + dx, y, z + dz)
                    if nb not in ("air", "water", "barrier") and not gridlib.passable(nb) and not gridlib.attached(nb):
                        g.set(x, y, z, f"vine[{f}=true]")
                        break


def giant_mushroom(x, z, y, h, red=True):
    g.box(x, y, z, x, y + h - 1, z, "mushroom_stem")
    rad = 2 if red else 3
    for dx in range(-rad, rad + 1):
        for dz in range(-rad, rad + 1):
            d = math.hypot(dx, dz)
            if d > rad + 0.4:
                continue
            if red:
                put(x + dx, y + h + (0 if d > 1.5 else 1), z + dz, "red_mushroom_block")
                if d > 1.5:
                    put(x + dx, y + h - 1, z + dz, "red_mushroom_block")
            else:
                put(x + dx, y + h, z + dz, "brown_mushroom_block")
    put(x, y + h - 1 if red else y + h - 1, z, "shroomlight", only_air=False) if r.random() < 0.0 else None


def coral(cx, cz, y_bottom, colours):
    """A coral formation in a pool: blocks stacked, fans and corals on top, all under water."""
    for _ in range(5):
        x, z = cx + r.randint(-1, 1), cz + r.randint(-1, 1)
        h = r.randint(1, 2)
        col = r.choice(colours)
        for yy in range(y_bottom, y_bottom + h):
            if g.get(x, yy, z) == "water":
                g.set(x, yy, z, f"{col}_coral_block")
        top = y_bottom + h
        if g.get(x, top, z) == "water":
            g.set(x, top, z, f"{r.choice(colours)}_coral{r.choice(['', '_fan'])}[waterlogged=true]")


def cactus(x, z, y):
    """A cactus on sand, nothing beside it; sometimes a flower on top."""
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        for yy in range(y, y + 4):
            if g.get(x + dx, yy, z + dz) != "air":
                return
    if g.get(x, y - 1, z) not in ("sand", "red_sand"):
        return
    h = r.randint(1, 3)
    g.box(x, y, z, x, y + h - 1, z, "cactus[age=0]")
    if r.random() < 0.5:
        put(x, y + h, z, "cactus_flower")


def light(x, y, z, level=11):
    if g.get(x, y, z) == "air":
        g.set(x, y, z, f"light[level={level}]")


def curtain(x0, z0, x1, z1, y0, y1=None):
    """Invisible walls from y0 up to the top of the map (nobody walks over them from a hill)."""
    g.box(x0, y0, z0, x1, y1 or Y1, z1, "barrier", where=lambda b: b == "air" or gridlib.passable(b) or "leaves" in b)


def cliff(x0, z0, x1, z1, y0, y1, mats, seed):
    """A natural wall of rock and earth, uneven on its face."""
    n = gridlib.Noise(seed)
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            top = y1 + round(3 * n(x * 0.25, 0, z * 0.25))
            for y in range(y0, top + 1):
                if g.get(x, y, z) in ("air", "grass_block", "dirt") or y > F:
                    g.set(x, y, z, "grass_block" if y == top and y >= F else g.pick(mats))


ROCK = [("stone", 4), ("mossy_cobblestone", 2), ("andesite", 1), ("tuff", 1), ("dirt", 2), ("rooted_dirt", 1)]


# ======================================================================== the landscape

def landscape():
    """Wooded hills on both sides and at the ends, a hill over the caves (z 118..206)."""
    for x in range(X0, X1 + 1):
        for z in range(Z0, Z1 + 1):
            side = max(0, abs(x) - 24)
            end = max(0, -18 - z, z - 288)
            hill = 0
            if 118 <= z <= 206:
                hill = 10 + round(2 * N[0](x * 0.2, 0, z * 0.2))
            h = max(hill, round(min(30, (side + end) * (0.8 + 0.4 * N[1](x * 0.1, 0, z * 0.1)))))
            for y in range(F + 1, F + h + 1):
                g.set(x, y, z, "grass_block" if y == F + h else ("dirt" if y > F + h - 4 else "stone"))
            if h and (side + end) > 2 and r.random() < 0.05:
                tree(x, z, F + h + 1, r.choice(["oak", "birch", "dark_oak", "oak"]))
            elif h and r.random() < 0.08:
                plant(x, F + h + 1, z, r.choice(["short_grass", "fern"]))


# ======================================================================== the gate and the wild path (room 1)

def gate_and_path():
    # the forecourt
    ground(-20, -18, 20, 7, F, [("grass_block", 6), ("podzol", 2), ("moss_block", 1), ("coarse_dirt", 1)])
    for z in range(-18, 46):
        ox = round(2.5 * math.sin(z * 0.12))
        for x in range(ox - 2, ox + 3):
            g.set(x, F, z, r.choice(["dirt_path", "dirt_path", "coarse_dirt", "podzol", "moss_block"]))
    curtain(-21, -19, -21, 44, F + 1)
    curtain(21, -19, 21, 44, F + 1)
    curtain(-21, -19, 21, -19, F + 1)
    for x, z in ((-12, -15), (13, -13), (-14, -8), (12, -4), (-9, -16)):
        tree(x, z, F + 1, r.choice(["oak", "birch", "azalea"]))
    for _ in range(30):
        x, z = r.randint(-19, 19), r.randint(-17, 3)
        if abs(x - round(2.5 * math.sin(z * 0.12))) > 3:
            plant(x, F + 1, z, g.pick(PALETTES["wild"]))
    g.start = (0, F + 1, -10)
    m.start = [0.5, float(F + 1), -9.5, 0.0, 0.0]
    # the plant gate: a mossy stone arch, roots round it, vines, two trees meeting over it
    for x in (-5, 5):
        g.box(x - 1, F + 1, 4, x + 1, F + 8, 6, lambda *a: r.choice(["mossy_stone_bricks", "mossy_cobblestone", "cracked_stone_bricks", "moss_block"]))
    for x in range(-6, 7):
        top = F + 9 + round(2 * math.cos(x / 6 * math.pi / 2))
        g.box(x, top, 4, x, top + 1, 6, lambda *a: r.choice(["mossy_stone_bricks", "moss_block", "mossy_cobblestone"]))
    for x in range(-4, 5):
        for z in (4, 6):
            hang(x, F + 8 + (1 if abs(x) < 3 else 0), z, r.randint(1, 3), "weeping" if r.random() < 0.3 else "roots")
    wall_vines(-8, F + 1, 3, 8, F + 11, 7, 0.35)
    for sx in (-1, 1):
        for t in range(3):
            put(sx * (7 + t), F + 1, 5 + r.randint(-1, 1), "mangrove_roots")
            put(sx * (6 + t), F + 2 + t, 5, "mangrove_roots", only_air=False)
        tree(sx * 9, 8, F + 1, "dark_oak", 9)
    flowerbed(-9, 1, F, 2, PALETTES["white_pink"])
    flowerbed(9, 1, F, 2, PALETTES["yellow_orange"])
    # the wild path: woods in layers, fallen logs, ponds, mossy stones
    for z in range(8, 45, 5):
        for sx in (-1, 1):
            x = sx * r.randint(7, 16)
            tree(x, z + r.randint(-1, 2), F + 1, r.choice(["oak", "birch", "dark_oak", "azalea", "oak"]))
    for _ in range(26):
        x, z = r.choice((-1, 1)) * r.randint(5, 19), r.randint(8, 44)
        if g.get(x, F + 1, z) == "air":
            bush(x, z, F + 1, r.choice(["oak", "azalea", "birch"]))
    for _ in range(140):
        x, z = r.randint(-19, 19), r.randint(8, 44)
        if abs(x - round(2.5 * math.sin(z * 0.12))) > 3:
            plant(x, F + 1, z, g.pick(PALETTES["shade"] if abs(x) > 12 else PALETTES["wild"]))
    fallen_log(-16, 18, F + 1, 6, "x")
    fallen_log(13, 30, F + 1, 5, "z")
    pond(-13, 33, F, 2)
    pond(14, 14, F, 2, orchids=False)
    for x, z in ((10, 22), (-10, 25), (16, 40), (-17, 12)):
        mossy_rock(x, z, F + 1)
    for x, z in ((-8, 16), (9, 37)):
        g.box(x, F + 1, z, x, F + r.randint(2, 4), z, lambda *a: r.choice(["mossy_stone_bricks", "cracked_stone_bricks"]))
    for x, z in ((-6, 12), (7, 26), (-7, 38)):
        light(x, F + 3, z, 9)
    for x, z in ((-4, 15), (5, 21), (-3, 29), (4, 35)):
        m.mob("sylve_ronceux", x, z, y=F + 1, yaw=180, room="1")
    for x, z in ((-8, 24), (8, 32)):
        m.mob("sylve_tisseuse", x, z, y=F + 1, yaw=180, room="1")
    # the gate of roots (room 1), between two root pillars, in a wall of woods
    for sx in (-1, 1):
        cliff(sx * 4 if sx > 0 else -21, 45, 21 if sx > 0 else -4, 46, F + 1, F + 7, ROCK, seed=60 + sx)
    g.box(-3, F + 1, 45, 3, F + 5, 45, lambda x, y, z: "dark_oak_wood" if (x + y) % 2 else "mangrove_roots")
    door("parvis", (-3, F + 1, 45), (3, F + 5, 45), room="1")
    roots_arch(-4, 4, 45, F + 5, 2)
    g.box(-3, F + 1, 46, 3, F + 5, 46, "air")


# ======================================================================== the wild garden (room 2)

def garden():
    curtain(-23, 44, -23, 92, F + 1)
    curtain(23, 44, 23, 92, F + 1)
    curtain(-23, 44, -21, 46, F + 1)
    curtain(21, 44, 23, 46, F + 1)
    ground(-22, 47, 22, 90, F, [("grass_block", 7), ("moss_block", 1), ("podzol", 1)])
    for z in range(46, 91):
        ox = round(3 * math.sin(z * 0.1))
        for x in range(ox - 2, ox + 3):
            g.set(x, F, z, r.choice(["dirt_path", "moss_block", "dirt_path", "coarse_dirt"]))
    # colours: white and pink (south-west), yellow and orange (south-east), blue and violet (north)
    for cx, cz, rad, pal in ((-14, 54, 5, "white_pink"), (-8, 62, 3, "white_pink"), (14, 55, 5, "yellow_orange"),
                             (9, 64, 3, "yellow_orange"), (-15, 78, 5, "blue_violet"), (15, 80, 4, "blue_violet"),
                             (-6, 72, 3, "white_pink"), (7, 74, 3, "blue_violet")):
        flowerbed(cx, cz, F, rad, PALETTES[pal])
    # the blue pools with their orchids, a waterfall from a mossy outcrop
    pond(-16, 70, F, 3)
    pond(17, 86, F, 2)
    g.blob(19, F + 4, 70, 4, 5, 4, (14, F + 1, 64, 23, F + 10, 76), seed=71, rough=0.4,
           block=lambda *a: r.choice(["mossy_cobblestone", "moss_block", "stone", "mossy_stone_bricks"]), where=lambda b: b == "air")
    pond(15, 70, F, 2)
    top = F + 7
    g.set(18, top, 70, "water")
    g.falls.add((18, top, 70))
    for dx, dz in ((1, 0), (0, 1), (0, -1)):
        g.set(18 + dx, top, 70 + dz, "mossy_cobblestone")
    g.set(18, top + 1, 70, "mossy_cobblestone")
    g.box(15, F + 1, 70, 17, top, 70, "air", where=lambda b: b != "water")
    g.set(16, F, 70, "water")
    g.set(17, F, 70, "water")
    g.set(16, F - 1, 70, "clay")
    g.set(17, F - 1, 70, "clay")
    for x in range(15, 18):
        for z in (69, 71):
            if g.get(x, F, z) == "water":
                continue
            g.set(x, F, z, "moss_block")
    # waterfall: from (18, top, 70) it flows west one block then falls into the pool at 17
    g.set(17, top, 70, "air")
    # the giant tree (landmark), trees in bloom, the mossy stump, the arch of branches and vines
    giant_tree(-18, 61, F + 1, h=18, crown=7, leaves="oak")
    for x, z, k in ((18, 52, "azalea"), (-20, 86, "birch"), (20, 61, "azalea"), (-11, 49, "birch"), (11, 89, "oak")):
        tree(x, z, F + 1, k)
    for z in (57, 58):
        for x in range(-5, 6):
            top_ = F + 7 + round(1.5 * math.cos(x / 5 * math.pi / 2))
            put(x, top_, z, "oak_log[axis=x]" if abs(x) < 5 else "oak_log")
            if r.random() < 0.5:
                hang(x, top_ - 1, z, r.randint(1, 3), "weeping" if r.random() < 0.3 else "roots")
        for x in (-5, 5):
            g.box(x, F + 1, z, x, F + 6, z, "oak_log")
    wall_vines(-7, F + 1, 55, 7, F + 9, 60, 0.4)
    # stump of a fallen giant: the Herbarium altar
    for dx in range(-1, 2):
        for dz in range(-1, 2):
            g.box(dx, F + 1, 86 + dz, dx, F + (1 if abs(dx) + abs(dz) == 2 else 2), 86 + dz, "oak_wood")
    g.set(0, F + 1, 88, "oak_wood")
    g.set(0, F + 2, 88, "moss_carpet")
    flowerbed(0, 86, F, 4, PALETTES["wild"], 0.5)
    for x, z in ((-8, 52), (8, 60), (-9, 82), (10, 76)):
        light(x, F + 4, z, 9)
    for x, z in ((-5, 55), (6, 66), (-3, 75), (4, 82)):
        m.mob("sylve_ronceux", x, z, y=F + 1, yaw=180, room="2")
    for x, z in ((-10, 68), (11, 70)):
        m.mob("sylve_tisseuse", x, z, y=F + 1, yaw=180, room="2")
    # the hedge that sinks when the Herbarium has its plants
    for sx in (-1, 1):
        g.box(-22 if sx < 0 else 4, F + 1, 91, -4 if sx < 0 else 22, F + 6, 92,
              lambda *a: LEAVES["azalea"] if r.random() < 0.6 else LEAVES["flowering_azalea"])
        curtain(-22 if sx < 0 else 4, 91, -4 if sx < 0 else 22, 92, F + 7)
    g.box(-3, F + 1, 91, 3, F + 5, 92, lambda *a: LEAVES["flowering_azalea"] if r.random() < 0.5 else LEAVES["azalea"])
    door("herbier", (-3, F + 1, 91), (3, F + 5, 92), room="2", offering={
        "altar": [0, F + 1, 88], "ordered": False, "title": "Herbier du sanctuaire",
        "items": [
            {"item": "blue_orchid", "amount": 2, "hint": "La fleur bleue née au bord de l'eau, qui calme les fièvres"},
            {"item": "lily_of_the_valley", "amount": 2, "hint": "Les clochettes blanches qui empoisonnent les imprudents"},
            {"item": "allium", "amount": 2, "hint": "Le globe pourpre au parfum d'ail"},
        ]})
    curtain(-3, 91, 3, 92, F + 6)


# ======================================================================== greenhouse, roots, corals, mushrooms (room 3)

def greenhouse():
    # a hollow in the hill, open to the sky through a roof of leaves
    for z in range(93, 125):
        for x in range(-20, 21):
            edge = abs(x) + 2 * N[0](x * 0.3, 0, z * 0.3)
            if edge <= 17:
                g.box(x, F + 1, z, x, F + 22, z, "air")
    cliff(-24, 93, -18, 124, F + 1, F + 16, ROCK, seed=81)
    cliff(18, 93, 24, 124, F + 1, F + 16, ROCK, seed=82)
    # nobody climbs out by the trees and the roof of leaves: walls up to the sky along the sides and the north
    ground(-17, 93, 17, 124, F, [("grass_block", 5), ("moss_block", 3), ("rooted_dirt", 1), ("podzol", 1)])
    for z in range(93, 125):
        for x in range(-2, 3):
            g.set(x, F, z, r.choice(["moss_block", "dirt_path", "rooted_dirt", "moss_block"]))
    for x, z in ((-12, 98), (12, 103), (-13, 112), (13, 119), (-6, 121), (8, 96)):
        tree(x, z, F + 1, "jungle")
    for x, z in ((-15, 105), (15, 110)):
        tree(x, z, F + 1, "mangrove")
    # the roof of leaves, with gaps of light
    for x in range(-18, 19):
        for z in range(93, 125):
            if N[2](x * 0.25, 0, z * 0.25) > -0.1 and g.get(x, F + 17, z) == "air":
                g.set(x, F + 17, z, LEAVES["jungle"] if r.random() < 0.7 else LEAVES["azalea"])
    ceiling_hangs(-17, 93, 17, 124, F + 10, 70, ["weeping", "spore_blossom", "spore_blossom", "roots"])
    wall_vines(-19, F + 1, 93, 19, F + 16, 124, 0.25)
    for _ in range(160):
        x, z = r.randint(-17, 17), r.randint(93, 124)
        if abs(x) > 3:
            plant(x, F + 1, z, g.pick([("large_fern", 4), ("fern", 4), ("short_grass", 3), ("azalea", 1), ("flowering_azalea", 1),
                                       ("lily_of_the_valley", 1), ("blue_orchid", 1)]))
    for x, z in ((-10, 108), (11, 99)):
        g.set(x, F, z, "moss_block")
        put(x, F + 1, z, "big_dripleaf[facing=south]")
    curtain(-18, 93, -18, 124, F + 1)
    curtain(18, 93, 18, 124, F + 1)
    curtain(-18, 125, 18, 125, F + 9)
    m.mob("sylve_ronceux", -4, 108, y=F + 1, yaw=180, room="3")
    m.mob("sylve_tisseuse", 5, 116, y=F + 1, yaw=180, room="3")


def roots_passage():
    # down into the hill: 63 to 50 over z 125..150, a winding tunnel under giant roots
    for z in range(125, 151):
        f = F - round((F - C) * (z - 125) / 25)
        ox = round(3 * math.sin(z * 0.18))
        w = 3 + (1 if N[0](0, 0, z * 0.4) > 0.2 else 0)
        g.blob(ox, f + 3, z, w + 1.5, 4, 1.5, (ox - w - 3, f + 1, z, ox + w + 3, f + 8, z), seed=900 + z, rough=0.3)
        g.box(ox - w, f + 1, z, ox + w, f + 4, z, "air")
        g.box(ox - w - 3, Y0, z, ox + w + 3, f, z, "rooted_dirt", where=lambda b: b == "air")
        for x in range(ox - w, ox + w + 1):
            g.set(x, f, z, r.choice(["rooted_dirt", "moss_block", "dirt", "packed_mud", "mud"]))
        if z % 4 == 0:
            roots_arch(ox - w - 1, ox + w + 1, z, f + 4, 1)
    for z in range(125, 151):
        for x in range(-12, 13):
            for y in range(C, F + 12):
                b = g.get(x, y, z)
                if b == "dirt" and any(g.get(x + a, y + c, z + d) == "air" for a, c, d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0))):
                    g.set(x, y, z, r.choice(["rooted_dirt", "dirt", "packed_mud", "mud", "mangrove_roots", "moss_block"]))
    ceiling_hangs(-10, 125, 10, 150, C + 3, 80, ["roots", "roots", "cave_vines", "cave_vines", "spore_blossom"])
    wall_vines(-10, C + 1, 125, 10, F + 6, 150, 0.12)
    for z in range(126, 151, 6):
        ox = round(3 * math.sin(z * 0.18))
        f = F - round((F - C) * (z - 125) / 25)
        light(ox + 2, f + 3, z, 7)
    m.mob("sylve_tisseuse", 0, 133, y=F - round((F - C) * 8 / 25) + 1, yaw=180, room="3")
    m.mob("sylve_tisseuse", 2, 144, y=F - round((F - C) * 19 / 25) + 1, yaw=180, room="3")


def coral_basin():
    g.blob(0, C + 5, 162, 15, 7, 12, (-17, C + 1, 151, 17, C + 12, 172), seed=161, rough=0.35)
    g.box(-17, Y0, 151, 17, C, 172, "clay", where=lambda b: b == "air")
    for z in range(151, 173):
        for x in range(-16, 17):
            if g.get(x, C + 1, z) != "air":
                continue
            if abs(x) <= 3:
                g.set(x, C, z, r.choice(["moss_block", "clay", "mud", "moss_block"]))
            else:
                g.set(x, C, z, "water")
                g.set(x, C - 1, z, r.choice(["clay", "sand", "gravel"]))
                g.set(x, C - 2, z, "clay")
    # pools contained: their rim at the way
    for z in range(151, 173):
        for x in (-4, 4):
            g.set(x, C, z, "mossy_cobblestone")
    for (cx, cz, cols) in ((-9, 155, ["tube", "brain"]), (10, 158, ["fire", "horn"]), (-11, 166, ["bubble", "tube"]),
                           (9, 168, ["brain", "horn"]), (-7, 161, ["fire", "bubble"])):
        g.box(cx - 2, C, cz - 2, cx + 2, C, cz + 2, "water", where=lambda b: b == "water")
        for x in range(cx - 2, cx + 3):
            for z in range(cz - 2, cz + 3):
                if g.get(x, C, z) == "water":
                    g.set(x, C - 1, z, "water")
                    g.set(x, C - 2, z, "clay")
        coral(cx, cz, C - 1, cols)
    for _ in range(40):
        x, z = r.choice((-1, 1)) * r.randint(5, 15), r.randint(152, 171)
        if g.get(x, C, z) == "water" and g.get(x, C - 1, z) not in ("water",) and r.random() < 0.6:
            g.set(x, C, z, "seagrass")
    # small falls from the walls into the pools
    for x, z in ((-14, 158), (14, 164)):
        top = g.ceiling(x, C + 2, z)
        if top and g.get(x, C, z) in ("water", "seagrass"):
            g.set(x, C, z, "water")
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if gridlib.passable(g.get(x + dx, top, z + dz)):
                    g.set(x + dx, top, z + dz, "clay")
            g.set(x, top, z, "water")
            g.falls.add((x, top, z))
    ceiling_hangs(-15, 151, 15, 172, C + 4, 40, ["roots", "cave_vines", "weeping"])
    for x, z in ((-8, 156), (8, 162), (-6, 170), (6, 153)):
        light(x, C + 6, z, 10)
    for x in range(-3, 4):
        for z in range(151, 173):
            for y in range(C + 1, C + 5):
                if g.get(x, y, z) not in ("air",) and not g.get(x, y, z).startswith("light"):
                    g.set(x, y, z, "air")


def mushroom_grotto():
    g.blob(0, C + 6, 188, 17, 8, 15, (-19, C + 1, 173, 19, C + 13, 202), seed=181, rough=0.35)
    g.box(-19, Y0, 173, 19, C, 202, "dirt", where=lambda b: b == "air")
    g.box(-3, C + 1, 171, 3, C + 4, 175, "air")
    for z in range(173, 203):
        for x in range(-18, 19):
            if g.get(x, C + 1, z) == "air":
                v = N[0](x * 0.3, 0, z * 0.3)
                g.set(x, C, z, "mycelium" if v > 0.3 else "podzol" if v > -0.1 else "moss_block" if v > -0.4 else "crimson_nylium" if x < 0 else "warped_nylium")
    for x, z, h, red in ((-12, 180, 7, True), (13, 185, 9, False), (-14, 195, 6, False), (12, 197, 7, True), (-9, 189, 5, True)):
        giant_mushroom(x, z, C + 1, h, red)
    for _ in range(150):
        x, z = r.randint(-17, 17), r.randint(174, 201)
        if abs(x) > 6:
            soil = g.get(x, C, z)
            if soil == "crimson_nylium":
                plant(x, C + 1, z, r.choice(["crimson_fungus", "crimson_roots"]))
            elif soil == "warped_nylium":
                plant(x, C + 1, z, r.choice(["warped_fungus", "warped_roots", "nether_sprouts"]))
            else:
                plant(x, C + 1, z, r.choice(["brown_mushroom", "red_mushroom", "brown_mushroom", "red_mushroom", "moss_carpet"]))
    ceiling_hangs(-16, 174, 16, 201, C + 5, 60, ["roots", "roots", "cave_vines", "weeping"])
    for x, z in ((-12, 180), (13, 185), (-14, 195), (12, 197)):
        light(x, C + 4, z + 2, 9)
    for x, z in ((-5, 184), (5, 192), (0, 178)):
        light(x, C + 8, z, 8)
    for x, z in ((-5, 181), (6, 186), (-4, 193), (5, 197)):
        m.mob("sylve_moisi", x, z, y=C + 1, yaw=180, room="3")
    # the Old Bush (altar of the Sylvan Sap), on a mound of moss, then the door of mushroom flesh
    g.box(-1, C, 199, 1, C, 201, "moss_block")
    g.set(0, C + 1, 200, "flowering_azalea")
    for x in (-2, 2):
        g.set(x, C + 1, 200, "moss_block")
    g.box(-4, C + 1, 201, 4, C + 5, 202, "air", where=lambda b: b != "flowering_azalea")
    g.box(-4, C, 201, 4, C, 202, "moss_block")
    g.box(-19, C + 1, 203, 19, C + 10, 204, "dirt", where=lambda b: b == "air")
    g.box(-2, C + 1, 203, 2, C + 4, 203, lambda x, y, z: "mushroom_stem" if abs(x) == 2 or y == C + 4 else "brown_mushroom_block")
    door("alambic", (-2, C + 1, 203), (2, C + 4, 203), room="3", offering={
        "altar": [0, C + 1, 200], "ordered": True, "title": "Le Vieux Buisson : la sève des anciens",
        "grant": "fdgrant_tisane_sylve",
        "grant-message": "&#2ECF7BLa sève de sylve coule du Vieux Buisson : vous la goûtez tous. &#B376E5Les racines du gardien vous retiendront moins longtemps.",
        "items": [
            {"item": "brown_mushroom", "amount": 1, "hint": "1. La chair brune du sous-bois"},
            {"item": "red_mushroom", "amount": 1, "hint": "2. Le chapeau rouge tacheté de blanc"},
            {"item": "glow_berries", "amount": 2, "hint": "3. Les baies qui luisent sous la voûte"},
            {"item": "blue_orchid", "amount": 1, "hint": "4. La fleur qui calme les fièvres"},
        ]})
    g.box(-2, C + 1, 204, 2, C + 4, 204, "air")


# ======================================================================== arid flora and threshold (room 4)

def arid_and_threshold():
    # the arid basin: open to the sky, cliffs of sandstone and terracotta strata
    for z in range(205, 231):
        for x in range(-20, 21):
            if abs(x) + 2 * N[1](x * 0.3, 0, z * 0.3) <= 17:
                g.box(x, C + 1, z, x, Y1, z, "air")
                v = N[0](x * 0.2, 0, z * 0.2)
                dune = 0 if abs(x) <= 6 else max(0, round(2 * (v + 0.5)))
                for y in range(C, C + dune + 1):
                    g.set(x, y, z, "red_sand" if v > 0.35 else "sand")
                g.box(x, C - 3, z, x, C - 1, z, "sandstone")
    for z in range(205, 231):
        for x in range(-24, 25):
            if g.get(x, C + 1, z) == "air":
                continue
            for y in range(C + 1, F + 3):
                b = g.get(x, y, z)
                if b not in ("air",) and any(g.get(x + a, y, z + d) == "air" for a, d in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    band = (y + round(2 * N[2](x * 0.2, 0, z * 0.2))) % 7
                    g.set(x, y, z, ["sandstone", "red_sandstone", "terracotta", "orange_terracotta", "sandstone", "smooth_sandstone",
                                    "red_terracotta"][band])
    for x in range(-2, 3):
        for z in range(205, 231):
            g.set(x, C, z, r.choice(["sandstone", "smooth_sandstone", "sand"]))
            g.box(x, C + 1, z, x, C + 4, z, "air")
    for _ in range(70):
        x, z = r.choice((-1, 1)) * r.randint(8, 16), r.randint(206, 229)
        y = g.floor_below(x, C + 5, z)
        if y is None:
            continue
        if r.random() < 0.35:
            cactus(x, z, y + 1)
        else:
            plant(x, y + 1, z, r.choice(["dead_bush", "dead_bush", "short_dry_grass" if False else "dead_bush"]))
    for x, z in ((-12, 212), (13, 224)):
        y = g.floor_below(x, C + 5, z)
        if y is not None:
            g.box(x, y + 1, z, x, y + 3, z, "sandstone")
            g.set(x, y + 4, z, "sandstone_slab[type=bottom]")
    for x, z in ((-14, 220), (14, 210)):
        y = g.floor_below(x, C + 5, z)
        if y is not None:
            g.set(x, y, z, "moss_block")
            tree(x, z, y + 1, "azalea")
    curtain(-21, 205, -21, 249, C + 1, F + 14)
    curtain(21, 205, 21, 249, C + 1, F + 14)
    g.box(-25, C + 1, 204, 25, F + 8, 204, "sandstone", where=lambda b: b == "air" and False)
    for x, z in ((-6, 210), (6, 216), (-5, 224), (5, 228)):
        m.mob("sylve_epineux", x, z, y=C + 1, yaw=180, room="4")
    # the threshold: down to the arena (50 to 44), giant trees, monumental roots, rare plants
    for z in range(231, 251):
        f = C - round((C - A) * (z - 231) / 19)
        for x in range(-20, 21):
            if abs(x) + 2 * N[1](x * 0.3, 0, z * 0.3) <= 17:
                g.box(x, f + 1, z, x, Y1, z, "air")
                g.set(x, f, z, r.choice(["grass_block", "moss_block", "rooted_dirt", "podzol", "grass_block"]))
                g.box(x, Y0, z, x, f - 1, z, "dirt", where=lambda b: b == "air")
    for z in range(231, 251):
        f = C - round((C - A) * (z - 231) / 19)
        for x in range(-2, 3):
            g.set(x, f, z, r.choice(["moss_block", "dirt_path", "rooted_dirt"]))
    for sx, z in ((-1, 236), (1, 243)):
        f = C - round((C - A) * (z - 231) / 19)
        giant_tree(sx * 12, z, f + 1, h=17, crown=7, leaves="dark_oak")
    for z in (238, 246):
        f = C - round((C - A) * (z - 231) / 19)
        roots_arch(-6, 6, z, f + 5, 3)
    for _ in range(90):
        x, z = r.choice((-1, 1)) * r.randint(4, 16), r.randint(232, 249)
        f = g.floor_below(x, C + 3, z)
        if f is not None:
            plant(x, f + 1, z, g.pick([("torchflower", 2), ("pitcher_plant", 2), ("peony", 1), ("rose_bush", 1), ("lilac", 1),
                                       ("large_fern", 3), ("fern", 3)]))
    wall_vines(-20, A + 1, 231, 20, F + 8, 250, 0.2)
    for x, z in ((-7, 240), (7, 247)):
        m.mob("sylve_ronceux", x, z, y=C - round((C - A) * (z - 231) / 19) + 1, yaw=180, room="4")
    # the Seal of Roots: rooted dirt on an old mossy pedestal; the door of roots into the sanctuary
    g.box(-1, A + 1, 246, 1, A + 1, 248, "mossy_stone_bricks")
    g.set(0, A + 2, 247, "rooted_dirt")
    g.box(-22, A + 1, 250, 22, F + 14, 250, "dirt", where=lambda b: b == "air" or gridlib.passable(b))
    g.box(-2, A + 1, 250, 2, A + 5, 250, lambda x, y, z: "mangrove_roots" if (x + y) % 2 else "rooted_dirt")
    door("sceau", (-2, A + 1, 250), (2, A + 5, 250), room="4", offering={
        "altar": [0, A + 2, 247], "ordered": False, "title": "Le Sceau des racines",
        "items": [
            {"item": "spore_blossom", "amount": 1, "hint": "La fleur qui pleut de la voûte"},
            {"item": "glow_berries", "amount": 2, "hint": "Les baies qui luisent dans le noir"},
            {"item": "blue_orchid", "amount": 1, "hint": "La fleur qui calme les fièvres"},
            {"item": "red_mushroom", "amount": 1, "hint": "Le chapeau rouge tacheté de blanc"},
        ]})


# ======================================================================== the sanctuary (the fox's square)

def sanctuary():
    x0, x1, z0, z1 = SQ
    cx, cz = 0, (z0 + z1 + 1) // 2
    # the sinkhole: open to the sky, walls of earth and roots, a band of plants all round the square
    for x in range(x0 - 9, x1 + 10):
        for z in range(z0, z1 + 10):
            if max(x0 - x, x - x1, z0 - z, z - z1) <= 8 - round(2 * N[0](x * 0.2, 0, z * 0.2)):
                g.box(x, A + 1, z, x, Y1, z, "air")
                g.set(x, A, z, r.choice(["grass_block", "moss_block", "rooted_dirt", "podzol"]))
    g.box(-2, A + 1, 250, 2, A + 5, 250, "air", where=lambda b: False)
    # the band round it: monumental roots, flower massifs, ferns, moss, dead trunks, rare plants
    band = [(x, z) for x in range(x0 - 8, x1 + 9) for z in range(z0 - 1, z1 + 9)
            if not (x0 - 1 <= x <= x1 + 1 and z0 - 1 <= z <= z1 + 1) and g.get(x, A + 1, z) == "air"]
    r.shuffle(band)
    for x, z in band[:260]:
        k = r.random()
        if k < 0.35:
            plant(x, A + 1, z, g.pick([("fern", 3), ("large_fern", 2), ("short_grass", 2), ("peony", 1), ("lilac", 1),
                                       ("rose_bush", 1), ("torchflower", 1), ("allium", 1), ("lily_of_the_valley", 1)]))
        elif k < 0.42:
            bush(x, z, A + 1, r.choice(["azalea", "oak", "dark_oak"]))
    for (x, z) in ((x0 - 5, z0 + 4), (x1 + 5, z0 + 10), (x0 - 4, z1 - 3), (x1 + 4, z1 - 6), (0, z1 + 6)):
        giant_tree(x, z, A + 1, h=22, crown=9, leaves=r.choice(["oak", "dark_oak", "jungle"]))
    for (x, z) in ((x0 - 6, z0 + 16), (x1 + 6, z1 + 4)):
        tree(x, z, A + 1, "dead")
    for k in range(6):
        sxz = [(x0 - 2, z0 + 3 + k * 5, 0), (x1 + 2, z0 + 2 + k * 5, 1)]
        for x, z, side in sxz:
            for t in range(4):
                put(x + (-t if side == 0 else t), A + 1 + (t // 2), z, "mangrove_roots" if t else "dark_oak_wood")
    # the rim: wooded, vines hanging into the sinkhole
    wall_vines(x0 - 10, A + 3, z0 - 2, x1 + 10, F + 6, z1 + 10, 0.18)
    # last: the square, flat and empty (nothing of the decor on it), a leaf drawn flat in its floor
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            d = math.hypot(x + 0.5 - cx, z + 0.5 - cz)
            v = N[1](x * 0.3, 0, z * 0.3)
            b = "grass_block" if v > -0.2 else ("moss_block" if v > -0.5 else "rooted_dirt")
            if 11.5 <= d < 12.5:
                b = "moss_block"
            if abs((x + 0.5 - cx) * 0.7 - (z + 0.5 - cz) * 0.0) < 0.6 and abs(z + 0.5 - cz) < 9:
                b = "podzol"
            g.set(x, A, z, b)
            g.box(x, A + 1, z, x, A + 6, z, "air")
    # invisible walls round the square (the boss and the players stay in it)
    for x in range(x0 - 1, x1 + 2):
        for z in range(z0 - 1, z1 + 2):
            if x in (x0 - 1, x1 + 1) or z in (z0 - 1, z1 + 1):
                if z == z0 - 1 and -2 <= x <= 2:
                    continue
                for y in range(A + 1, A + 30):
                    if g.get(x, y, z) == "air" or gridlib.passable(g.get(x, y, z)):
                        g.set(x, y, z, "barrier")
    m.mob("renard_sylvestre", 0, cz, y=A + 1, yaw=180, room="5")
    m.spawns[-1]["at"] = [0.0, float(A + 1), float(cz), 180.0]          # the middle of the square, as in the first version
    m.objectives = ["renard_sylvestre"]


# ======================================================================== checks, output

def zone_of(x, y, z):
    if z <= 45:
        return 1
    if z <= 92:
        return 2
    if z <= 203:
        return 3
    if z <= 250:
        return 4
    return 5


def check():
    errors = g.check_basic()
    for k in range(len(g.doors) + 1):
        seen = g.walk(g.start, set(range(k)), top=Y1)
        if not seen:
            errors.append("start not standable")
            break
        top = max(zone_of(*p) for p in seen)
        if top > k + 1:
            bad = next(p for p in seen if zone_of(*p) > k + 1)
            errors.append(f"doors open {k}: zone {top} reachable, e.g. {bad}")
        if top < min(k + 1, 5):
            errors.append(f"doors open {k}: zone {k + 1} not reachable (max {top})")
    x0, x1, z0, z1 = SQ
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if g.get(x, A, z) in ("air", "water") or any(g.get(x, y, z) != "air" for y in (A + 1, A + 2, A + 3)):
                errors.append(f"the fox's square is not flat and free at {x} {z}")
                break
    for p in [(0, F + 1, 88), (0, C + 1, 200), (0, A + 2, 247)]:
        if gridlib.passable(g.get(*p)) and g.get(*p) != "flowering_azalea":
            errors.append(f"altar missing at {p}: {g.get(*p)}")
    return errors


_plain = m.edits_yaml


def edits_yaml(dungeon):
    """maplib writes plain doors; the offering of a door is written under it (read by DungeonDefinition)."""
    text = _plain(dungeon)
    for d in m.doors:
        o = d.get("offering")
        if not o:
            continue
        lines = ["    offering:", f"      altar: [{', '.join(str(v) for v in o['altar'])}]",
                 f"      ordered: {'true' if o['ordered'] else 'false'}", "      title: '" + o['title'].replace("'", "''") + "'"]
        if o.get("grant"):
            lines.append(f"      grant: {o['grant']}")
            lines.append("      grant-message: '" + o["grant-message"].replace("'", "''") + "'")
        lines.append("      items:")
        for it in o["items"]:
            lines.append(f"      - {{item: {it['item']}, amount: {it['amount']}, hint: '" + it["hint"].replace("'", "''") + "'}")
        anchor = f"  - id: {d['id']}\n"
        i = text.index(anchor)
        j = text.index("    radius:", i)
        j = text.index("\n", j) + 1
        text = text[:j] + "\n".join(lines) + "\n" + text[j:]
    return text


m.edits_yaml = edits_yaml

landscape()
gate_and_path()
garden()
greenhouse()
roots_passage()
coral_basin()
mushroom_grotto()
arid_and_threshold()
sanctuary()
m.textures = "rpg"
m.biome = "forest"           # RPG grass and leaves painted in that colour, biome set on the map
m.ybox = (Y0, Y1)

if __name__ == "__main__":
    args = sys.argv[1:]
    errs = check()
    for e in errs[:50]:
        print("CHECK:", e)
    print(f"{len(errs)} problem(s), {len(g.over)} blocks changed, {len(g.entities)} entities")
    if "--preview" in args:
        out = args[args.index("--preview") + 1]
        g.png_slice(os.path.join(out, "herbo_ciel.png"), top_down=True, k=2)
        g.png_slice(os.path.join(out, "herbo_coupe_x0.png"), x=0, k=2)
        g.png_slice(os.path.join(out, "herbo_y51.png"), y=C + 1, k=2)
    if "--check" in args or "--preview" in args:
        sys.exit(1 if errs else 0)
    if errs:
        sys.exit("checks failed: nothing sent")
    g.commands(m)
    print(f"{len(m.cmds)} commands")
    maplib.run(m, args[0] if args and not args[0].startswith("--") else "labs", "herboriste")
