#!/usr/bin/env python3
"""The rpg lobby, La Place des Dix Destins: a small medieval-fantasy capital around a square, built straight into the
lobby world flux_rpg (no copy: the world is live), modelled in memory first (gridlib, flora) and checked.

  - arrival: the FluxPortal ring `rpg` ends at (0.5, 67, 2.5) facing south (+z); a dais under it (top at 66, the ring
    stands on it), a monumental arch round it, wide steps down to the avenue (the square at 64, feet at 65);
  - the square (42 x 42): paving with a border, rays and circles; the information pavilion in its middle (an empty
    plinth under a dome: nothing written, it is kept for future displays); benches, planters, lamp posts,
    two fountains at its southern corners;
  - two wings of five gates, one per job with a dungeon or one to come, same architecture (pillars, arch, pediment,
    the job's materials and colour, its name above): west Mineur, Rochiste, Déblayeur, Fondeur, Enchanteur; east
    Cultivateur, Herboriste, Sylviculteur, Pêcheur, Défenseur. Walking into a gate opens the dungeon's launch screen
    (their trigger zones, particles and names live in Typewriter: scripts/typewriter/gen_lobby_rpg.py, page lobby_rpg);
  - north of the square: the training yard (place a dummy with /rpgadmin mannequin on its mark), the town hall, the
    bonus court (empty, kept for a future feature);
  - an inn and a watch tower framing the avenue; terraces, trees and hills all round, invisible walls at the edge.

Usage: gen_lobby.py [server] [--check] [--preview DIR]     (labs by default; clears the former lobby first)
"""
import math
import os
import shutil
import sys
import time

import flora as floralib
import gridlib
import maplib

F = 64                                     # floor of the square (feet at 65)
X0, X1, Y0, Y1, Z0, Z1 = -62, 63, 40, 110, -34, 108
PORTAL = (0.5, 69, 2.5)                    # exit of the FluxPortal ring `rpg`: centre (size 4), bottom on the dais (67)
SQ = (-20, 21, 19, 60)                     # the square (x0, x1, z0, z1)
CX, CZ = 0.5, 39.5
OLD = (-86, 291, 50, 200, -20, 316)        # extent of the former lobby (x0, x1, y0, y1, z0, z1)
g = gridlib.Model((X0, X1, Y0, Y1, Z0, Z1), ((Y0, Y0, "air"),), seed=1010, outside="air")
r = g.r
fl = floralib.Flora(g, 1011)
m = maplib.Map()
m.box = (X0, Z0, X1, Z1)

JOBS_WEST = [  # (dungeon id, name, colour, materials of the gate, emblem blocks)
    ("mineur", "Mineur", "#E3826C", ["polished_deepslate", "cut_copper", "deepslate_bricks"], ["copper_ore", "iron_ore", "lantern"]),
    ("roche", "Rochiste", "#B8B2AA", ["polished_andesite", "granite", "stone_bricks"], ["polished_granite", "polished_diorite", "andesite"]),
    ("taupe", "Déblayeur", "#A0855B", ["packed_mud", "mud_bricks", "coarse_dirt"], ["rooted_dirt", "gravel", "podzol"]),
    ("fondeur", "Fondeur", "#FF7A3D", ["bricks", "iron_block", "polished_blackstone_bricks"], ["blast_furnace", "anvil", "magma_block"]),
    ("enchanteur", "Enchanteur", "#B376E5", ["purpur_block", "amethyst_block", "polished_blackstone"], ["bookshelf", "amethyst_cluster", "enchanting_table"]),
]
JOBS_EAST = [
    ("moisson", "Cultivateur", "#F0A800", ["hay_block", "oak_planks", "stripped_oak_log"], ["pumpkin", "melon", "composter"]),
    ("herboriste", "Herboriste", "#83A85A", ["moss_block", "mossy_stone_bricks", "flowering_azalea_leaves[persistent=true]"], ["flowering_azalea", "azalea", "moss_block"]),
    ("sylviculteur", "Sylviculteur", "#8C6A3E", ["spruce_log", "stripped_spruce_log", "spruce_planks"], ["oak_log", "birch_log", "spruce_leaves[persistent=true]"]),
    ("pecheur", "Pêcheur", "#43B7B0", ["prismarine_bricks", "dark_prismarine", "prismarine"], ["sea_lantern", "barrel[facing=up]", "tube_coral_block"]),
    ("defenseur", "Défenseur", "#C8C8C8", ["polished_blackstone_bricks", "bone_block", "chiseled_stone_bricks"], ["bone_block", "iron_bars", "skeleton_skull[rotation=0]"]),
]
GATE_Z = (25, 32, 39, 46, 53)
GATES = []                                 # gate openings (checks; Typewriter places their triggers)
DUMMY = (-13, 71)                          # the training dummy's mark (/rpgadmin mannequin there)
SPAWN = (0.5, 67, 6.5)                     # rpg.spawn: on the dais, in front of the ring

PAVE = [("stone_bricks", 5), ("polished_andesite", 3), ("smooth_stone", 3), ("andesite", 2), ("cobblestone", 1)]


def put(x, y, z, b):
    if g.get(x, y, z) == "air":
        g.set(x, y, z, b)


def light(x, y, z, lv=12):
    if g.get(x, y, z) == "air":
        g.set(x, y, z, f"light[level={lv}]")


def lamp(x, z, y=F + 1, h=3):
    g.box(x, y, z, x, y + h - 1, z, "dark_oak_fence")
    g.set(x, y + h, z, "lantern")


def bench(x, z, facing):
    g.set(x, F + 1, z, f"spruce_stairs[facing={facing}]")
    side = {"north": (1, 0), "south": (1, 0), "east": (0, 1), "west": (0, 1)}[facing]
    g.set(x + side[0], F + 1, z + side[1], f"spruce_stairs[facing={facing}]")
    for k in (-1, 2):
        put(x + side[0] * k, F + 1, z + side[1] * k, "spruce_trapdoor[half=bottom,facing=north,open=true]" if False else "spruce_slab[type=bottom]")


def planter(x, z, kind="azalea"):
    g.box(x - 1, F + 1, z - 1, x + 1, F + 1, z + 1, "spruce_planks")
    g.box(x, F + 1, z, x, F + 1, z, "grass_block")
    for dx, dz in ((0, 0),):
        g.set(x + dx, F + 2, z + dz, "flowering_azalea" if kind == "azalea" else "azalea")
    for dx in (-1, 1):
        for dz in (-1, 1):
            g.set(x + dx, F + 1, z + dz, "stripped_spruce_log")


def text(x, y, z, label, colour, yaw, scale=1.6):
    g.entities.append(
        f'summon text_display {x} {y} {z} {{text:{{text:"{label}",color:"{colour}",bold:1b}},billboard:"fixed",'
        f'Rotation:[{yaw}f,0f],background:1073741824,shadow:1b,transformation:{{left_rotation:[0f,0f,0f,1f],'
        f'right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[{scale}f,{scale}f,{scale}f]}}}}')


# ======================================================================== ground, terraces, hills

def ground():
    n = gridlib.Noise(77)
    for x in range(X0, X1 + 1):
        for z in range(Z0, Z1 + 1):
            # the playable ground is flat; beyond it the land climbs in terraces then hills
            out = max(0, abs(x - 0.5) - 44, -14 - z, z - 92)
            h = 0 if out == 0 else round(min(22, out * (0.7 + 0.4 * n(x * 0.12, 0, z * 0.12))))
            bottom = Y0 + 4 + round(6 * (1 + n(x * 0.05, 1, z * 0.05)))
            for y in range(bottom, F + h + 1):
                top = F + h
                g.set(x, y, z, "grass_block" if y == top else ("dirt" if y > top - 4 else "stone"))
            if out > 3 and r.random() < 0.05:
                fl.tree(x, z, F + h + 1, r.choice(["oak", "birch", "spruce", "oak"]), r.choice(["medium", "big", "conifer", "small"]))
            elif out and r.random() < 0.12:
                fl.plant(x, F + h + 1, z, r.choice(["short_grass", "fern", "dandelion", "poppy", "oxeye_daisy"]))
    # the playable ground: grass between the paved parts
    for x in range(-44, 46):
        for z in range(-14, 93):
            g.set(x, F, z, "grass_block")
    fl.curtain(-45, -15, -45, 93, F + 1, Y1)
    fl.curtain(46, -15, 46, 93, F + 1, Y1)
    fl.curtain(-45, -15, 46, -15, F + 1, Y1)
    fl.curtain(-45, 93, 46, 93, F + 1, Y1)
    # a low stone retaining wall where the terraces start
    for x in range(-44, 46):
        for z in (-14, 92):
            g.set(x, F + 1, z, "stone_brick_wall")
    for z in range(-14, 93):
        for x in (-44, 45):
            g.set(x, F + 1, z, "stone_brick_wall")


# ======================================================================== arrival: dais, arch, steps, avenue

def arrival():
    px, py, pz = PORTAL
    # the dais under the ring (top 66), the ring stands on it
    for x in range(-6, 8):
        for z in range(-3, 10):
            g.box(x, F, z, x, F + 2, z, "stone_bricks")
            g.set(x, F + 2, z, g.pick([("smooth_stone", 3), ("polished_andesite", 2), ("stone_bricks", 1)]))
    for x in range(-6, 8):
        g.set(x, F + 2, -3, "chiseled_stone_bricks")
    # wide steps down to the avenue (z 10, 11)
    g.box(-6, F + 2, 10, 7, F + 2, 10, "stone_brick_stairs[facing=north]")
    g.box(-6, F + 1, 10, 7, F + 1, 10, "stone_bricks")
    g.box(-6, F + 1, 11, 7, F + 1, 11, "stone_brick_stairs[facing=north]")
    # the monumental arch round the ring: two pillars, a span over it, lanterns, banners
    for x in (-5, 6):
        g.box(x, F + 3, 1, x, F + 11, 3, lambda xx, y, zz: "chiseled_stone_bricks" if y in (F + 3, F + 11) else
              ("quartz_pillar" if zz == 2 else "stone_bricks"))
        g.set(x, F + 12, 2, "lantern")
        g.set(x + (1 if x > 0 else -1), F + 8, 2, "white_wall_banner[facing=" + ("east" if x > 0 else "west") + "]")
    for x in range(-5, 7):
        top = F + 11 + (1 if -2 <= x <= 3 else 0)
        g.box(x, F + 10, 1, x, top, 3, "stone_bricks")
        g.set(x, F + 9, 1, "stone_brick_stairs[facing=south,half=top]")
        g.set(x, F + 9, 3, "stone_brick_stairs[facing=north,half=top]")
    g.box(-2, F + 13, 2, 3, F + 13, 2, "cut_copper_slab[type=bottom]")
    for x in (-3, 4):
        g.set(x, F + 8, 4, "lantern[hanging=true]") if g.get(x, F + 9, 4) != "air" else None
    # behind the ring: the old city wall, two towers, banners (the way the players come from)
    for x in range(-14, 16):
        for z in (-6, -5):
            g.box(x, F + 1, z, x, F + 8 + (1 if x % 2 == 0 and z == -6 else 0), z, g.pick([("stone_bricks", 5), ("mossy_stone_bricks", 1), ("cracked_stone_bricks", 1)]))
    for tx in (-14, 15):
        for y in range(F + 1, F + 16):
            for x in range(tx - 2, tx + 3):
                for z in range(-8, -3):
                    if max(abs(x - tx), abs(z + 6)) == 2 or y == F + 1:
                        g.set(x, y, z, "stone_bricks" if y < F + 14 else "smooth_stone")
        for k in range(4):
            g.box(tx - 3 + k, F + 16 + k, -9 + k, tx + 3 - k, F + 16 + k, -3 - k, "dark_oak_planks")
        g.set(tx, F + 20, -6, "lightning_rod")
    # the avenue (z 12..18), paved, lamp posts, planters
    for x in range(-6, 8):
        for z in range(12, 19):
            g.set(x, F, z, g.pick(PAVE))
    for x in (-7, 8):
        for z in range(12, 19):
            g.set(x, F, z, "polished_andesite")
    for z in (12, 18):
        lamp(-8, z)
        lamp(9, z)
    planter(-10, 15)
    planter(11, 15)
    for x in range(-6, 8):
        for z in range(-3, 10):
            light(x, F + 6, z, 11) if (x + z) % 7 == 0 and not (-3 <= x <= 4 and 1 <= z <= 4) else None
    g.start = (0, F + 3, 5)


# ======================================================================== the square

def square():
    x0, x1, z0, z1 = SQ
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            dx, dz = x + 0.5 - CX, z + 0.5 - CZ
            d = math.hypot(dx, dz)
            a = math.degrees(math.atan2(dz, dx)) % 360
            edge = min(x - x0, x1 - x, z - z0, z1 - z)
            if edge == 0:
                b = "chiseled_stone_bricks" if (x + z) % 4 == 0 else "stone_bricks"
            elif edge == 1:
                b = "polished_andesite"
            elif 13.5 <= d < 14.5 or 8.5 <= d < 9.3:
                b = "smooth_stone"
            elif d < 14 and min(abs(((a + 22.5) % 45) - 22.5), 45) < 3.5 and d > 6:
                b = "polished_andesite"
            elif abs(dx) < 1 or abs(dz) < 1:
                b = "smooth_stone"
            else:
                b = g.pick(PAVE)
            g.set(x, F, z, b)
    for x in range(-6, 8):
        for z in (19,):
            g.set(x, F, z, "smooth_stone")
    # the information pavilion: an octagon of eight pillars, an empty plinth (kept for future displays), a dome
    rad = 5
    for x in range(-6, 8):
        for z in range(33, 47):
            d = math.hypot(x + 0.5 - CX, z + 0.5 - CZ)
            if d <= rad + 0.3:
                g.set(x, F, z, "calcite" if d > rad - 0.8 else ("polished_andesite" if d > 2 else "smooth_stone"))
    g.box(-1, F + 1, 38, 2, F + 1, 41, "polished_andesite")              # the plinth (kept empty for displays)
    g.box(-1, F + 1, 38, -1, F + 1, 38, "chiseled_stone_bricks")
    pillars = []
    for k in range(8):
        a = math.radians(22.5 + 45 * k)
        px, pz = round(CX - 0.5 + rad * math.cos(a)), round(CZ - 0.5 + rad * math.sin(a))
        pillars.append((px, pz))
        g.box(px, F + 1, pz, px, F + 6, pz, lambda xx, y, zz: "chiseled_stone_bricks" if y in (F + 1, F + 6) else "quartz_pillar")
        g.set(px, F + 7, pz, "stone_bricks")
    # the roof: a ring beam, a dome of dark oak, a copper finial
    for y_off, rr in ((7, 6.2), (8, 5.4), (9, 4.4), (10, 3.2), (11, 1.8)):
        for x in range(-7, 9):
            for z in range(32, 48):
                d = math.hypot(x + 0.5 - CX, z + 0.5 - CZ)
                if rr - 1.0 <= d <= rr:
                    g.set(x, F + y_off, z, "dark_oak_planks" if y_off > 7 else "stone_bricks")
    g.box(0, F + 12, 39, 1, F + 12, 40, "cut_copper")
    g.set(0, F + 13, 39, "lightning_rod")
    for k in range(8):
        a = math.radians(45 * k)
        light(round(CX - 0.5 + 3 * math.cos(a)), F + 6, round(CZ - 0.5 + 3 * math.sin(a)), 13)
    g.set(0, F + 10, 39, "lantern[hanging=true]")
    # around the square (out of the middle): benches, planters, lamp posts, two fountains by the avenue
    for z in range(z0 + 3, z1 - 2, 8):
        lamp(x0 + 2, z)
        lamp(x1 - 2, z)
    for x in (-13, 14):
        for z in (z1 - 2,):
            lamp(x, z)
    for (x, z, f) in ((x0 + 3, 29, "east"), (x0 + 3, 49, "east"), (x1 - 3, 29, "west"), (x1 - 3, 49, "west")):
        bench(x, z, f)
    for x, z in ((x0 + 4, 36), (x0 + 4, 43), (x1 - 4, 36), (x1 - 4, 43)):
        planter(x, z)
    for fx, fz in ((-12, 25), (13, 25)):
        for x in range(fx - 3, fx + 4):
            for z in range(fz - 3, fz + 4):
                d = math.hypot(x - fx, z - fz)
                if d <= 2.4:
                    g.set(x, F, z, "water")
                    g.set(x, F - 1, z, "smooth_stone")
                elif d <= 3.3:
                    g.set(x, F + 1, z, "stone_brick_wall")
                    g.set(x, F, z, "polished_andesite")
        g.box(fx, F, fz, fx, F + 2, fz, "chiseled_stone_bricks")
        g.set(fx, F + 3, fz, "water")
        g.falls.add((fx, F + 3, fz))
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            g.set(fx + dx, F + 3, fz + dz, "smooth_stone_slab[type=bottom]")
        g.set(fx, F + 4, fz, "smooth_stone_slab[type=bottom]")


# ======================================================================== the two wings of gates

def wing(jobs, side):
    """side -1: west (gates open towards +x), +1: east (towards -x)."""
    face = 22 * side + (1 if side > 0 else 0)            # the plane of the openings: x = -22 or 23
    back = face + side * 5
    # the gallery: a raised walkway of stone, an arcade between the gates, a lean-to roof rising towards the square
    for z in range(SQ[2] + 1, SQ[3]):
        for k in range(0, 9):
            x = face + side * k
            g.set(x, F, z, "stone_bricks" if k == 0 else g.pick(PAVE))
    for z in range(SQ[2] + 1, SQ[3]):
        for k in range(6, 12):
            x = face + side * k
            g.box(x, F + 1, z, x, F + 9, z, g.pick([("stone_bricks", 5), ("smooth_stone", 2), ("polished_andesite", 1)]) if k == 6 else "air")
        # roof over the gallery (k 0..7), sloping down away from the square
        for k in range(-1, 8):
            x = face + side * k
            y = F + 12 - (k + 1) // 2
            g.set(x, y, z, f"spruce_stairs[facing={'west' if side > 0 else 'east'}]")
    for j, (did, name, colour, mats, emblem) in enumerate(jobs):
        zc = GATE_Z[j]
        main, second, third = mats
        # two pillars and the arch, the pediment with the job's emblem, the name above
        for dz in (-2, 2):
            g.box(face, F + 1, zc + dz, face + side, F + 7, zc + dz, lambda x, y, z: main if y in (F + 1, F + 7) else second)
            g.set(face, F + 8, zc + dz, third if "leaves" not in third else main)
        for dz in range(-2, 3):
            g.box(face, F + 6, zc + dz, face + side, F + 8, zc + dz, main if abs(dz) == 2 else second)
        g.set(face, F + 5, zc - 1, f"stone_brick_stairs[facing={'south'},half=top]")
        g.set(face, F + 5, zc + 1, f"stone_brick_stairs[facing={'north'},half=top]")
        for dz, b in zip((-1, 0, 1), emblem):
            g.set(face, F + 9, zc + dz, b if "skull" not in b and "cluster" not in b and "flowering_azalea" != b and "azalea" != b
                  and "lantern" not in b else third if "leaves" not in third else main)
            if "skull" in b or "cluster" in b or b in ("azalea", "flowering_azalea") or "lantern" in b:
                g.set(face, F + 10, zc + dz, b if "cluster" not in b else "amethyst_cluster[facing=up]")
        # the alcove behind the opening (the gate box), its back wall in the job's material, a glow at its foot
        for k in range(1, 4):
            x = face + side * k
            g.box(x, F + 1, zc - 1, x, F + 4, zc + 1, "air")
            g.set(x, F, zc - 1, third if "leaves" not in third else main)
            g.set(x, F, zc + 1, third if "leaves" not in third else main)
            g.set(x, F, zc, "sea_lantern" if k == 2 else main)
            for dz in (-2, 2):
                g.box(x, F + 1, zc + dz, x, F + 5, zc + dz, main)
            g.box(x, F + 5, zc - 1, x, F + 5, zc + 1, second)
        g.box(face + side * 4, F + 1, zc - 2, face + side * 4, F + 5, zc + 2, second)
        # banners and lanterns either side, the name above the arch
        bcol = {"#E3826C": "orange", "#B8B2AA": "light_gray", "#A0855B": "brown", "#FF7A3D": "red", "#B376E5": "purple",
                "#F0A800": "yellow", "#83A85A": "lime", "#8C6A3E": "brown", "#43B7B0": "cyan", "#C8C8C8": "white"}[colour]
        for dz in (-3, 3):
            g.set(face - side, F + 6, zc + dz, f"{bcol}_wall_banner[facing={'east' if side < 0 else 'west'}]") \
                if g.get(face, F + 6, zc + dz) != "air" else None
            g.set(face - side * 0, F + 4, zc + dz, "lantern") if g.get(face, F + 3, zc + dz) != "air" else None
        GATES.append({"dungeon": did, "name": name, "from": [min(face + side, face + 3 * side), F + 1, zc - 1],
                      "to": [max(face + side, face + 3 * side), F + 4, zc + 1], "out": [-side, 0, 0], "color": colour})
        light(face - side * 2, F + 5, zc, 12)
    # lamp posts and planters along the gallery
    for z in (SQ[2] + 3, SQ[3] - 3):
        lamp(face - side * 2, z)


# ======================================================================== north: training yard, town hall, bonus court

def north():
    zr0, zr1 = 62, 80
    for x in range(-20, 22):
        for z in range(61, 62):
            g.set(x, F, z, "polished_andesite")
    # the training yard (west): wooden floor, fences round it, a mark for the dummy, posts and targets on the sides
    tx0, tx1 = -20, -6
    for x in range(tx0, tx1 + 1):
        for z in range(zr0, zr1 + 1):
            edge = x in (tx0, tx1) or z in (zr0, zr1)
            g.set(x, F, z, "stripped_spruce_wood" if edge else ("spruce_planks" if (x + z) % 2 else "oak_planks"))
            if edge and not (z == zr0 and tx0 + 4 <= x <= tx1 - 4):
                g.set(x, F + 1, z, "spruce_fence")
    mx, mz = DUMMY
    g.box(mx - 1, F, mz - 1, mx + 1, F, mz + 1, "chiseled_stone_bricks")           # the dummy's mark (nothing on it)
    for x, z in ((tx0 + 1, zr1 - 1), (tx1 - 1, zr1 - 1)):
        g.box(x, F + 1, z, x, F + 2, z, "spruce_log")
        g.set(x, F + 3, z, "target")
    for x in (tx0, tx1):
        for z in (zr0, zr1):
            g.box(x, F + 1, z, x, F + 3, z, "spruce_log")
            g.set(x, F + 4, z, "lantern")
    g.set(tx0 + 1, F + 1, mz, "barrel[facing=up]")
    g.set(tx0 + 1, F + 1, mz + 1, "barrel[facing=up]")
    # the bonus court (east): an empty stone court, four pillars at its corners, nothing else
    bx0, bx1 = 8, 21
    for x in range(bx0, bx1 + 1):
        for z in range(zr0, zr1 + 1):
            edge = x in (bx0, bx1) or z in (zr0, zr1)
            g.set(x, F, z, "stone_bricks" if edge else ("smooth_stone" if (x + z) % 2 else "polished_andesite"))
    for x in (bx0, bx1):
        for z in (zr0, zr1):
            g.box(x, F + 1, z, x, F + 5, z, lambda xx, y, zz: "chiseled_stone_bricks" if y in (F + 1, F + 5) else "stone_bricks")
            g.set(x, F + 6, z, "lantern")
    # the town hall (north middle): stone ground floor, timber upper floor, a tall roof, a clock tower
    hx0, hx1, hz0, hz1 = -5, 6, 64, 78
    g.box(hx0, F + 1, hz0, hx1, F + 5, hz1, lambda x, y, z: "stone_bricks" if (x in (hx0, hx1) or z in (hz0, hz1)) else "air")
    g.box(hx0, F + 6, hz0, hx1, F + 10, hz1, lambda x, y, z: ("spruce_log" if (x in (hx0, hx1) and z in (hz0, hz1)) or y == F + 6
                                                                else "oak_planks" if (x in (hx0, hx1) or z in (hz0, hz1)) else "air"))
    for x in range(hx0 + 2, hx1 - 1, 3):
        g.box(x, F + 7, hz0, x, F + 8, hz0, "glass_pane[east=true,west=true]")
        g.box(x, F + 2, hz0, x, F + 3, hz0, "glass_pane[east=true,west=true]")
    g.box(0, F + 1, hz0, 1, F + 3, hz0, "air")
    g.set(0, F + 1, hz0, "dark_oak_door[facing=south,half=lower,hinge=left]")
    g.set(0, F + 2, hz0, "dark_oak_door[facing=south,half=upper,hinge=left]")
    g.set(1, F + 1, hz0, "dark_oak_door[facing=south,half=lower,hinge=right]")
    g.set(1, F + 2, hz0, "dark_oak_door[facing=south,half=upper,hinge=right]")
    for k in range(7):
        y = F + 11 + k
        xa, xb = hx0 - 1 + k, hx1 + 1 - k
        if xa > xb:
            break
        g.box(xa, y, hz0 - 1, xa, y, hz1 + 1, "dark_oak_stairs[facing=east]")
        g.box(xb, y, hz0 - 1, xb, y, hz1 + 1, "dark_oak_stairs[facing=west]")
        if xb - xa > 1:
            g.box(xa + 1, y, hz0, xb - 1, y, hz0, "oak_planks")
            g.box(xa + 1, y, hz1, xb - 1, y, hz1, "oak_planks")
    for y in range(F + 11, F + 24):
        for x in range(-1, 3):
            for z in range(hz1 - 3, hz1 + 1):
                if x in (-1, 2) or z in (hz1 - 3, hz1):
                    g.set(x, y, z, "stone_bricks" if y < F + 21 else "smooth_stone")
    for k in range(4):
        g.box(-2 + k, F + 24 + k, hz1 - 4 + k, 3 - k, F + 24 + k, hz1 + 1 - k, "dark_oak_planks")
    g.set(0, F + 18, hz1 - 3, "white_wall_banner[facing=north]")
    g.box(hx0 - 1, F + 5, hz0 - 1, hx1 + 1, F + 5, hz0 - 1, "spruce_slab[type=top]")
    for x in (hx0 - 1, hx1 + 1):
        lamp(x, hz0 - 2)


# ======================================================================== houses framing the avenue

def framing_buildings():
    # the inn (west of the avenue): stone and timber, two floors, a sign post, a chimney, an awning
    ix0, ix1, iz0, iz1 = -22, -11, -2, 12
    g.box(ix0, F + 1, iz0, ix1, F + 4, iz1, lambda x, y, z: "cobblestone" if (x in (ix0, ix1) or z in (iz0, iz1)) and y == F + 1 else
          ("stone_bricks" if (x in (ix0, ix1) or z in (iz0, iz1)) else "air"))
    g.box(ix0 - 1, F + 5, iz0 - 1, ix1 + 1, F + 8, iz1 + 1, lambda x, y, z: (
        "spruce_log" if (x in (ix0 - 1, ix1 + 1) and z in (iz0 - 1, iz1 + 1)) or y == F + 5 else
        "spruce_planks" if (x in (ix0 - 1, ix1 + 1) or z in (iz0 - 1, iz1 + 1)) else "air"))
    for z in range(iz0 + 2, iz1 - 1, 3):
        g.set(ix1, F + 2, z, "glass_pane[north=true,south=true]")
        g.set(ix1 + 1, F + 7, z, "glass_pane[north=true,south=true]")
    for k in range(7):
        y = F + 9 + k
        za, zb = iz0 - 2 + k, iz1 + 2 - k
        if za > zb:
            break
        g.box(ix0 - 2, y, za, ix1 + 2, y, za, "spruce_stairs[facing=south]")
        g.box(ix0 - 2, y, zb, ix1 + 2, y, zb, "spruce_stairs[facing=north]")
        if zb - za > 1:
            g.box(ix0 - 1, y, za + 1, ix0 - 1, y, zb - 1, "spruce_planks")
            g.box(ix1 + 1, y, za + 1, ix1 + 1, y, zb - 1, "spruce_planks")
    g.box(ix0 + 2, F + 9, iz1 - 3, ix0 + 3, F + 17, iz1 - 2, "bricks")
    g.box(ix1 + 1, F + 4, iz0 + 3, ix1 + 2, F + 4, iz1 - 3, "dark_oak_slab[type=top]")
    g.box(ix1 + 3, F + 1, iz0 + 5, ix1 + 3, F + 4, iz0 + 5, "dark_oak_fence")
    g.set(ix1 + 3, F + 4, iz0 + 6, "dark_oak_fence")
    g.set(ix1 + 3, F + 3, iz0 + 6, "lantern[hanging=true]")
    for z in (iz0 + 3, iz1 - 3):
        g.set(ix1 + 1, F + 1, z, "barrel[facing=up]")
    # the watch tower (east of the avenue): round, tall, a roof of dark oak, a balcony
    tx, tz = 17, 4
    for y in range(F + 1, F + 22):
        for x in range(tx - 4, tx + 5):
            for z in range(tz - 4, tz + 5):
                d = math.hypot(x - tx, z - tz)
                if 2.8 < d <= 3.8:
                    g.set(x, y, z, "stone_bricks" if (y % 6) else "smooth_stone")
    for x in range(tx - 5, tx + 6):
        for z in range(tz - 5, tz + 6):
            d = math.hypot(x - tx, z - tz)
            if 3.8 < d <= 5.0:
                g.set(x, F + 16, z, "spruce_slab[type=top]")
                if d > 4.4:
                    g.set(x, F + 17, z, "spruce_fence")
    for k in range(6):
        for x in range(tx - 5, tx + 6):
            for z in range(tz - 5, tz + 6):
                d = math.hypot(x - tx, z - tz)
                if 4.5 - k * 0.85 - 0.9 < d <= 4.5 - k * 0.85:
                    g.set(x, F + 22 + k, z, "dark_oak_planks")
    for y in (F + 6, F + 12):
        g.set(tx - 4, y, tz, "glass_pane[north=true,south=true]") if False else g.set(tx - 3, y, tz, "glass_pane[north=true,south=true]")
    g.set(tx, F + 28, tz, "lightning_rod")
    # gardens and trees in the corners, out of the views
    for x, z in ((-34, 0), (35, 2), (-36, 70), (37, 72), (-30, 86), (32, 86), (-38, 30), (39, 45)):
        fl.tree(x, z, F + 1, r.choice(["oak", "birch"]), r.choice(["medium", "big"]))
    for x, z in ((-27, 14), (28, 16), (-26, 66), (27, 68), (-14, 86), (16, 86)):
        fl.bush(x, z, F + 1, r.choice(["oak", "azalea" if False else "birch"]))
    for _ in range(160):
        x, z = r.randint(-43, 44), r.randint(-13, 91)
        if g.get(x, F, z) == "grass_block" and g.get(x, F + 1, z) == "air":
            fl.plant(x, F + 1, z, g.pick([("short_grass", 6), ("fern", 2), ("dandelion", 1), ("poppy", 1), ("cornflower", 1),
                                          ("oxeye_daisy", 1), ("azure_bluet", 1)]))


# ======================================================================== checks, output

def check():
    errors = g.check_basic()
    px, py, pz = 0, 67, 2
    for x in range(-2, 4):
        for y in range(67, 72):
            for z in (2, 3):
                if g.get(x, y, z) != "air":
                    errors.append(f"the portal ring is not free at {x} {y} {z}: {g.get(x, y, z)}")
    seen = g.walk((0, 67, 4), set(), top=Y1)
    for gate in GATES:
        x0, y0, z0 = gate["from"]
        x1, y1, z1 = gate["to"]
        ox = x0 - 1 if gate["out"][0] < 0 else x1 + 1
        if not any((ox, F + 1, z) in seen for z in range(z0, z1 + 1)):
            errors.append(f"gate {gate['name']} cannot be walked to")
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    if g.get(x, y, z) != "air" and not g.get(x, y, z).startswith("light"):
                        errors.append(f"gate {gate['name']} not open at {x} {y} {z}")
                        break
    for p in ((-13, F + 1, 71), (14, F + 1, 71), (0, F + 1, 39)):
        if p not in seen and (p[0], p[1] + 1, p[2]) not in seen:
            errors.append(f"{p} cannot be walked to")
    if any(p[0] < -45 or p[0] > 46 or p[2] < -15 or p[2] > 93 for p in seen):
        errors.append("the lobby can be walked out of")
    return errors


def apply(server):
    """Clears the former lobby (keeping the portal's entities), builds the new one in flux_rpg."""
    base = os.path.join(maplib.ROOT, "serveurs", maplib.SERVERS[server])
    wdir = os.path.join(base, "world", "dimensions", "minecraft", "flux_rpg")
    c = maplib.Console(server)
    print(c.send("save-all flush"))
    time.sleep(4)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    dest = os.path.join(maplib.ROOT, "backups", f"flux_rpg_lobby_{stamp}")
    for sub in ("region", "entities", "poi"):
        if os.path.isdir(os.path.join(wdir, sub)):
            shutil.copytree(os.path.join(wdir, sub), os.path.join(dest, sub))
    print("former lobby saved in", dest)
    W = "execute in minecraft:flux_rpg run "

    def run(cmds):
        bad = 0
        for cmd in cmds:
            o = c.send(W + cmd)
            if o and any(b in o for b in maplib.BAD) and "No blocks" not in o and "Could not set" not in o:
                bad += 1
                if bad < 15:
                    print(cmd, "->", o)
        return bad

    # 1. the former lobby, tile by tile (at most 256 chunks loaded at once), and its loose entities
    ox0, ox1, oy0, oy1, oz0, oz1 = OLD
    for tx in range(ox0, ox1 + 1, 192):
        for tz in range(oz0, oz1 + 1, 192):
            x1, z1 = min(ox1, tx + 191), min(oz1, tz + 191)
            c.send(W + f"forceload add {tx} {tz} {x1} {z1}")
            time.sleep(3)                                   # entities load after the chunks
            tile = maplib.Map()
            tile.fill(tx, oy0, tz, x1, oy1, z1, "air")
            run(tile.cmds)
            # decor entities only: the portal's displays and the training dummies (husks) stay
            for kind in ("item_frame", "glow_item_frame", "painting", "armor_stand", "item"):
                c.send(W + f"minecraft:kill @e[type=minecraft:{kind},x={tx},y={oy0},z={tz},dx={x1 - tx},dy={oy1 - oy0},dz={z1 - tz}]")
            c.send(W + "forceload remove all")
    print("former lobby cleared")
    # 2. the new one
    c.send(W + f"forceload add {X0} {Z0} {X1} {Z1}")
    g.commands(m)
    bad = run(m.cmds)
    swap = maplib.retexture(m, server) + list(m.after) + maplib.biome_cmds(m)
    bad += run(swap)
    print(f"{len(m.cmds)} commands, {len(swap)} swap commands, {bad} errors")
    # the training dummies already placed go to their mark in the yard
    print(c.send(W + f"tp @e[tag=fluxrpg_dummy] {DUMMY[0] + 0.5} {F + 1} {DUMMY[1] + 0.5} 180 0"))
    print(c.send(W + "forceload remove all"))
    print(c.send("save-all flush"))
    # rpg.spawn on the dais (the former one, at 65, is now inside it), then the plugin rereads its config
    import re
    cfg = os.path.join(base, "plugins", "FluxDungeons", "config.yml")
    text_ = open(cfg).read()
    spawn = (f"  spawn:\n    x: {SPAWN[0]}\n    y: {float(SPAWN[1])}\n    z: {SPAWN[2]}\n    yaw: 0.0\n    pitch: 0.0\n")
    new_text, n = re.subn(r"  spawn:\n(    [a-z]+: [-0-9.eE]+\n){5}", spawn, text_, count=1)
    if n == 1:
        open(cfg, "w").write(new_text)
        print("rpg.spawn set to", SPAWN)
    else:
        print("rpg.spawn not found in", cfg, ": set it with /rpgadmin setspawn on the dais")
    print(c.send("fluxdungeons reload"))


ground()
arrival()
square()
wing(JOBS_WEST, -1)
wing(JOBS_EAST, 1)
north()
framing_buildings()
m.textures = "rpg"
m.biome = "plains"           # RPG grass and leaves painted in that colour, biome set on the map
m.ybox = (Y0, Y1)

if __name__ == "__main__":
    args = sys.argv[1:]
    errs = check()
    for e in errs[:40]:
        print("CHECK:", e)
    print(f"{len(errs)} problem(s), {len(g.over)} blocks changed, {len(g.entities)} entities, {len(GATES)} gates")
    if "--preview" in args:
        out = args[args.index("--preview") + 1]
        g.png_slice(os.path.join(out, "lobby_ciel.png"), top_down=True, k=3)
        g.png_slice(os.path.join(out, "lobby_y65.png"), y=F + 1, k=3)
    if "--check" in args or "--preview" in args:
        sys.exit(1 if errs else 0)
    if errs:
        sys.exit("checks failed: nothing sent")
    apply(args[0] if args and not args[0].startswith("--") else "labs")
