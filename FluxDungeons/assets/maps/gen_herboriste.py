#!/usr/bin/env python3
"""Map of the herbalist dungeon, Le Sanctuaire de la Sylve (boss: the Sylvan Fox), built by console commands (RCON).

An old sanctuary of herbalists taken back by the plants of its guardian, played from south to north (+z):
  - forecourt and Parvis (start at 0.5, 64, -6.5; floor y 63): a monumental entrance (columns, entablature, vines),
    a ruined hall under a canopy of leaves letting the light through, the herbalists' abandoned stalls; room 1, the
    door (dark oak) opens once its brambles are dead;
  - the Overgrown Gardens (41 x 39, glass roof half fallen): flower beds of a dozen species, two pools with blue
    orchids and lily pads, azalea trees, roots across the stone paths, trellises, composters; the Herbarium altar (a
    composter) asks for three plants described by riddles: a hedge sinks into the ground once they are in;
  - the Forgotten Laboratories (33 x 29, vaulted): preparation tables, shelves, brewing stands, pots, two mushroom
    beds and a corner of cave vines; the Alembic (a water cauldron among copper pipes) takes the tisane's ingredients
    in a set order and gives the whole group the Sylvan Tisane (softer roots in the boss fight); copper door;
  - the Deep Roots: a cave going down 12 blocks under giant roots, darker (glow lichen, spore blossoms, glow
    berries); the Seal of Roots altar (rooted dirt on an old pedestal) asks what the previous zones taught;
  - the Sanctuary: exactly 30 x 30 playable blocks (x -15..14, z 131..160, floor y 51), flat and open: an octagon
    and a leaf drawn in the floor, roots, broken pillars, niches of abandoned installations and plants all kept outside
    the square or flat; the fox waits hidden in the middle (0, 52, 146) facing the entrance (south wall).
Leaves are placed persistent (they would decay in the run's copy), every plant stands on a soil that keeps it.

Usage: gen_herboriste.py [server]       (labs by default) : builds, copies into maps/custom/herboriste, writes
edits/herboriste.yml (start, monsters by room, doors and altars).
"""
import math
import random
import sys

import maplib

F = 63           # floor of zones 1-3
A = 51           # floor of the arena (zone 4 goes down to it)
r = random.Random(1147)
m = maplib.Map()
m.box = (-34, -16, 34, 172)
LEAVES = "azalea_leaves[persistent=true]"
BLOOM = "flowering_azalea_leaves[persistent=true]"
OAKL = "oak_leaves[persistent=true]"
STONES = ["stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "stone_bricks", "mossy_stone_bricks"]
FLOWERS = ["poppy", "dandelion", "cornflower", "oxeye_daisy", "azure_bluet", "red_tulip", "orange_tulip",
           "white_tulip", "pink_tulip", "allium", "lily_of_the_valley"]


def mix(x0, y0, z0, x1, y1, z1, base, spots, share=0.25):
    m.fill(x0, y0, z0, x1, y1, z1, base)
    vol = (abs(x1 - x0) + 1) * (abs(y1 - y0) + 1) * (abs(z1 - z0) + 1)
    for _ in range(int(vol * share)):
        m.set(r.randint(min(x0, x1), max(x0, x1)), r.randint(min(y0, y1), max(y0, y1)), r.randint(min(z0, z1), max(z0, z1)),
              r.choice(spots))


def column(x, z, y0, y1, broken=False):
    """A 1 x 1 old column: chiseled base and cap, mossy shaft; broken = shorter, rubble at its foot."""
    top = y1 - (r.randint(2, 4) if broken else 0)
    m.set(x, y0, z, "chiseled_stone_bricks")
    m.fill(x, y0 + 1, z, x, top - 1, z, r.choice(["mossy_stone_bricks", "stone_bricks"]))
    m.set(x, top, z, "chiseled_stone_bricks" if not broken else "cracked_stone_bricks")


def tree(x, y, z, h=4, bloom=0.4):
    """An azalea tree: oak trunk, round crown of azalea leaves (some flowering)."""
    m.fill(x, y, z, x, y + h - 1, z, "oak_log")
    for dx in range(-2, 3):
        for dz in range(-2, 3):
            for dy in range(h - 2, h + 1):
                if abs(dx) + abs(dz) + max(0, dy - h + 1) * 2 > 3 or (dx == 0 and dz == 0 and dy < h):
                    continue
                m.set(x + dx, y + dy, z + dz, BLOOM if r.random() < bloom else LEAVES)


def bed(x0, z0, x1, z1, y, flowers):
    """A raised flower bed edged with stripped oak, filled with grass and the given flowers."""
    m.fill(x0, y, z0, x1, y, z1, "stripped_oak_log[axis=x]")
    m.fill(x0 + 1, y, z0 + 1, x1 - 1, y, z1 - 1, "grass_block")
    for x in range(x0 + 1, x1):
        for z in range(z0 + 1, z1):
            if r.random() < 0.85:
                m.set(x, y + 1, z, r.choice(flowers))


def root_line(x0, y0, z0, x1, y1, z1, block="mangrove_roots"):
    """A root running between two points (one block per step)."""
    n = max(abs(x1 - x0), abs(y1 - y0), abs(z1 - z0), 1)
    for i in range(n + 1):
        k = i / n
        m.set(round(x0 + (x1 - x0) * k), round(y0 + (y1 - y0) * k), round(z0 + (z1 - z0) * k), block)


def hang(x, y_top, z, length, kind="vine"):
    """Something hanging from a ceiling block at y_top + 1: vines, cave vines with berries, hanging roots."""
    if kind == "cave":
        for i in range(length - 1):
            m.set(x, y_top - i, z, f"cave_vines_plant[berries={'true' if r.random() < 0.6 else 'false'}]")
        m.set(x, y_top - length + 1, z, "cave_vines[berries=true]")
    elif kind == "roots":
        m.set(x, y_top, z, "hanging_roots")
    else:
        for i in range(length):
            m.set(x, y_top - i, z, "vine[north=true]")


# ================================================================ the rock mass
for z0 in range(-14, 171, 22):
    z1 = min(170, z0 + 21)
    for y0, y1, b in ((38, 47, "deepslate"), (48, 60, "tuff"), (61, 70, "stone"), (71, 80, "stone"), (81, 84, "dirt"),
                      (85, 85, "grass_block")):
        m.fill(-32, y0, z0, 32, y1, z1, b)
for _ in range(700):
    m.set(r.randint(-32, 32), r.randint(40, 84), r.randint(-14, 170),
          r.choice(["mossy_cobblestone", "moss_block", "rooted_dirt", "andesite", "dirt", "cobblestone", "tuff"]))

# ================================================================ zone 1: forecourt (z -12..4) and Parvis (z 5..23)
m.fill(-9, F + 1, -12, 9, 90, 4, "air")                        # open sky
mix(-9, F, -12, 9, F, 4, "moss_block", ["grass_block", "rooted_dirt", "coarse_dirt", "mossy_cobblestone"], 0.3)
m.fill(-2, F, -12, 2, F, 4, "mossy_stone_bricks")             # the old paved way
for z in range(-12, 5):
    for x in (-2, 2):
        if r.random() < 0.4:
            m.set(x, F, z, r.choice(["cracked_stone_bricks", "moss_block", "rooted_dirt"]))
for x in range(-8, 9):
    for z in range(-12, 4):
        if abs(x) > 3 and r.random() < 0.35:
            m.set(x, F + 1, z, r.choice(["short_grass", "fern", "moss_carpet", "poppy", "dandelion"]))
# cliffs on each side covered with leaves and vines
for side in (-1, 1):
    for z in range(-12, 5):
        if r.random() < 0.5:
            m.set(side * 9, r.randint(F + 2, F + 8), z, LEAVES)
tree(-6, F + 1, -8, 5)
tree(6, F + 1, -3, 4)
m.start = [0.5, 64.0, -9.5, 0.0, 0.0]
# the facade (z 5) with its arch, and four columns before it bearing an entablature
m.fill(-12, F, 5, 12, F + 14, 5, "stone_bricks")
mix(-12, F, 5, 12, F + 14, 5, "stone_bricks", ["mossy_stone_bricks", "cracked_stone_bricks", "mossy_stone_bricks"], 0.45)
m.fill(-3, F + 1, 5, 3, F + 7, 5, "air")
m.fill(-3, F + 8, 5, 3, F + 8, 5, "chiseled_stone_bricks")
for x in (-5, -9, 5, 9):
    column(x, 3, F + 1, F + 9)
m.fill(-10, F + 10, 2, 10, F + 10, 4, "stone_brick_slab[type=bottom]")
m.fill(-10, F + 10, 4, 10, F + 10, 4, "mossy_stone_bricks")
for x in range(-11, 12):                                       # vines and leaves falling from the top
    if r.random() < 0.55:
        hang(x, F + 9, 4, r.randint(1, 5))
    if r.random() < 0.5:
        m.set(x, F + 11, 4, LEAVES if r.random() < 0.7 else BLOOM)
for x in range(-12, 13):
    if r.random() < 0.4:
        root_line(x, F + 14, 5, x + r.randint(-1, 1), F + r.randint(3, 9), 5, "mangrove_roots")
# the hall (x -11..11, z 6..22), ceiling at F + 11 with openings closed by leaves (filtered light)
m.fill(-11, F + 1, 6, 11, F + 10, 22, "air")
mix(-11, F, 6, 11, F, 22, "mossy_stone_bricks", ["stone_bricks", "cracked_stone_bricks", "moss_block", "rooted_dirt"], 0.3)
m.fill(-12, F + 11, 5, 12, F + 11, 23, "stone_bricks")
for x0, z0 in ((-7, 9), (2, 9), (-7, 16), (2, 16)):
    m.fill(x0, F + 11, z0, x0 + 4, 90, z0 + 3, "air")
    m.fill(x0, F + 12, z0, x0 + 4, F + 12, z0 + 3, LEAVES)
    for _ in range(5):
        m.set(x0 + r.randint(0, 4), F + 12, z0 + r.randint(0, 3), BLOOM)
    for _ in range(3):
        hang(x0 + r.randint(0, 4), F + 10, z0 + r.randint(0, 3), r.randint(2, 4))
for z in (8, 14, 20):                                          # wall columns and roots climbing the walls
    for x in (-11, 11):
        column(x, z, F + 1, F + 10)
for _ in range(10):
    x = r.choice([-11, 11])
    z = r.randint(7, 22)
    root_line(x, F + 10, z, x, F + 1, z + r.randint(-2, 2))
for x in range(-10, 11):
    for z in range(7, 22):
        if abs(x) > 3 and r.random() < 0.18:
            m.set(x, F + 1, z, r.choice(["moss_carpet", "moss_carpet", "fern", "short_grass"]))
# the herbalists' abandoned stalls: counters, barrels, pots, composters, drying racks
for x0, z0, facing in ((-10, 10, "east"), (-10, 17, "east"), (8, 10, "west"), (8, 17, "west")):
    m.fill(x0, F + 1, z0, x0 + 2, F + 1, z0 + 2, "spruce_planks")
    m.set(x0, F + 2, z0, "barrel[facing=up]")
    m.set(x0 + 1, F + 2, z0, "composter[level=4]")
    m.set(x0 + 2, F + 2, z0 + 1, r.choice(["potted_fern", "potted_allium", "potted_blue_orchid", "potted_red_mushroom"]))
    m.set(x0, F + 2, z0 + 2, "flower_pot")
    m.fill(x0, F + 4, z0, x0 + 2, F + 4, z0, "spruce_fence[east=true,west=true]")
    for x in range(x0, x0 + 3):
        m.set(x, F + 3, z0, LEAVES if r.random() < 0.6 else "air")
    m.set(x0 + 1, F + 6, z0 + 1, "lantern[hanging=true]")
    m.fill(x0 + 1, F + 7, z0 + 1, x0 + 1, F + 10, z0 + 1, "iron_chain[axis=y]")
m.set(-1, F + 1, 12, "lectern[facing=south]")
m.set(1, F + 1, 12, "cauldron")
m.fill(-3, F + 1, 20, -2, F + 2, 20, "bookshelf")
m.fill(2, F + 1, 20, 3, F + 2, 20, "bookshelf")
# room 1 monsters, its door (dark oak, z 23)
for mob, x, z in (("sylve_ronceux", -4, 11), ("sylve_ronceux", 5, 13), ("sylve_ronceux", 0, 18), ("sylve_moisi", -7, 19),
                  ("sylve_moisi", 7, 20)):
    m.mob(mob, x, z, y=F + 1, yaw=180, room="1")
m.fill(-12, F, 23, 12, F + 11, 23, "stone_bricks")
mix(-12, F + 1, 23, 12, F + 11, 23, "stone_bricks", ["mossy_stone_bricks", "cracked_stone_bricks"], 0.35)
m.fill(-3, F + 1, 23, 3, F + 5, 23, "dark_oak_planks")
m.fill(-3, F + 1, 23, -3, F + 5, 23, "stripped_dark_oak_log[axis=y]")
m.fill(3, F + 1, 23, 3, F + 5, 23, "stripped_dark_oak_log[axis=y]")
m.door("parvis", (-3, F + 1, 23), (3, F + 5, 23), room="1", kills=0, type="kills")

# ================================================================ zone 2: the Overgrown Gardens (x -20..20, z 24..62)
m.fill(-20, F + 1, 24, 20, 90, 62, "air")
mix(-20, F, 24, 20, F, 62, "grass_block", ["moss_block", "podzol", "rooted_dirt", "coarse_dirt"], 0.2)
# walls of the greenhouse: stone to F + 9, then a glass frame up to the roof (F + 18), half fallen
m.fill(-21, F, 23, -21, F + 9, 63, "stone_bricks")
m.fill(21, F, 23, 21, F + 9, 63, "stone_bricks")
for z in range(24, 63, 4):
    for x in (-21, 21):
        m.fill(x, F + 10, z, x, F + 18, z, "dark_oak_log")
        m.fill(x, F + 10, z + 1, x, F + 18, z + 3, "glass_pane[north=true,south=true]")
for x in range(-20, 21, 4):
    m.fill(x, F + 19, 24, x, F + 19, 62, "dark_oak_log[axis=z]")
for x in range(-20, 21):
    for z in range(24, 63):
        if x % 4 != 0 and r.random() < 0.45:
            m.set(x, F + 19, z, "glass")
        elif x % 4 != 0 and r.random() < 0.25:
            m.set(x, F + 19, z, LEAVES)
for _ in range(16):
    hang(r.randint(-19, 19), F + 18, r.randint(25, 61), r.randint(3, 8))
# stone paths in a cross, with roots crossing them
m.fill(-2, F, 24, 2, F, 62, "mossy_cobblestone")
m.fill(-20, F, 41, 20, F, 45, "mossy_cobblestone")
for z in range(24, 63):
    if r.random() < 0.3:
        m.set(r.randint(-2, 2), F, z, r.choice(["stone_bricks", "gravel", "mossy_stone_bricks"]))
for z in (30, 37, 52, 57):
    root_line(-6, F + 1, z, 6, F + 1, z + r.randint(-1, 1), "mangrove_roots")
    m.fill(-2, F, z, 2, F, z, "rooted_dirt")
for x in (-12, 12):
    root_line(x, F + 1, 39, x + r.randint(-1, 1), F + 1, 47, "mangrove_roots")
# flower beds: each quarter mixes species (the riddles ask to recognise them)
quarters = ((-18, 26, -5, 38), (5, 26, 18, 38), (-18, 48, -5, 60), (5, 48, 18, 60))
for i, (x0, z0, x1, z1) in enumerate(quarters):
    for k in range(2):
        bz0 = z0 + k * 7
        bed(x0, bz0, x0 + 5, bz0 + 4, F, r.sample(FLOWERS, 3) + (["allium", "lily_of_the_valley"] if k == 0 else []))
        bed(x1 - 5, bz0, x1, bz0 + 4, F, r.sample(FLOWERS, 4))
# two pools with lily pads, blue orchids on their banks (the swamp flower)
for cx, cz in ((-11, 44), (11, 44)):
    m.disc(cx, cz, 3, F - 1, F, "water")
    m.disc(cx, cz, 3, F - 2, F - 2, "clay")
    for _ in range(4):
        a = r.random() * math.tau
        m.set(cx + round(math.cos(a) * 1.5), F + 1, cz + round(math.sin(a) * 1.5), "lily_pad")
    for a in range(0, 360, 30):
        x, z = cx + round(math.cos(math.radians(a)) * 4.2), cz + round(math.sin(math.radians(a)) * 4.2)
        m.set(x, F, z, "mud" if r.random() < 0.3 else "grass_block")
        m.set(x, F + 1, z, "blue_orchid" if r.random() < 0.75 else "fern")
    m.set(cx, F - 1, cz, "sea_lantern")
# azalea trees and bushes, trellises with vines, composters and water troughs
for x, z in ((-16, 43), (16, 43), (-8, 33), (8, 55)):
    tree(x, F + 1, z, r.randint(4, 6), 0.5)
for x, z in ((-3, 28), (3, 34), (-3, 50), (3, 58), (-19, 30), (19, 55)):
    m.set(x, F + 1, z, r.choice(["azalea", "flowering_azalea"]))
for x in (-20, 20):
    for z in range(26, 61, 6):
        m.fill(x + (1 if x < 0 else -1), F + 1, z, x + (1 if x < 0 else -1), F + 4, z, "oak_fence")
        for y in range(F + 1, F + 5):
            if r.random() < 0.6:
                m.set(x + (2 if x < 0 else -2), y, z, LEAVES)
for x, z in ((-4, 40), (4, 46), (-19, 41), (19, 47)):
    m.set(x, F + 1, z, "composter[level=6]")
for x, z in ((-4, 46), (4, 40)):
    m.set(x, F + 1, z, "water_cauldron[level=3]")
m.set(1, F + 1, 25, "lectern[facing=north]")                # the herbarium of the gardens
for x, z in ((-1, 26), (1, 26), (0, 60)):
    m.set(x, F + 1, z, "lantern")
# room 2 monsters
for mob, x, z in (("sylve_ronceux", -10, 32), ("sylve_ronceux", 10, 52), ("sylve_tisseuse", -9, 54),
                  ("sylve_tisseuse", 12, 30), ("sylve_moisi", 0, 43), ("sylve_ronceux", -14, 48)):
    m.mob(mob, x, z, y=F + 1, yaw=180, room="2")
# the Herbarium altar (composter on a mossy pedestal) and its hedge (z 62..63)
m.fill(-21, F, 63, 21, F + 11, 63, "stone_bricks")
mix(-21, F + 1, 63, 21, F + 11, 63, "stone_bricks", ["mossy_stone_bricks", "cracked_stone_bricks"], 0.35)
m.fill(-3, F + 1, 62, 3, F + 5, 63, LEAVES)
for _ in range(14):
    m.set(r.randint(-3, 3), r.randint(F + 1, F + 5), r.choice([62, 63]), BLOOM)
m.set(0, F, 59, "mossy_stone_bricks")
m.set(0, F + 1, 59, "composter[level=3]")
m.set(-1, F + 1, 59, "potted_blue_orchid")
m.set(1, F + 1, 59, "potted_allium")
m.door("herbier", (-3, F + 1, 62), (3, F + 5, 63), room="2", type="offering")
m.doors[-1]["offering"] = {
    "altar": [0, F + 1, 59], "ordered": False, "title": "Herbier du sanctuaire",
    "items": [
        {"item": "blue_orchid", "amount": 2, "hint": "La fleur bleue née au bord de l'eau, qui calme les fièvres"},
        {"item": "lily_of_the_valley", "amount": 2, "hint": "Les clochettes blanches qui empoisonnent les imprudents"},
        {"item": "allium", "amount": 2, "hint": "Le globe pourpre au parfum d'ail"},
    ]}

# ================================================================ zone 3: the Forgotten Laboratories (x -16..16, z 64..92)
m.fill(-16, F + 1, 64, 16, F + 9, 92, "air")
mix(-16, F, 64, 16, F, 92, "polished_andesite", ["stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "tuff_bricks"], 0.3)
m.fill(-17, F + 10, 63, 17, F + 10, 93, "stone_bricks")
for z in range(66, 92, 5):                                       # vault ribs
    m.fill(-16, F + 9, z, 16, F + 9, z, "dark_oak_log[axis=x]")
    for x in (-16, 16):
        m.fill(x, F + 1, z, x, F + 8, z, "stripped_dark_oak_log")
    m.set(0, F + 8, z, "lantern[hanging=true]")
# alcoves along both walls: preparation tables, shelves, brewing stands, pots, containers
for z0 in (66, 76, 85):
    for side in (-1, 1):
        x = side * 15
        inner = side * 12
        m.fill(min(x, inner), F + 1, z0, max(x, inner), F + 1, z0, "spruce_slab[type=top]")
        m.set(inner, F + 1, z0 + 1, "spruce_fence")
        m.set(side * 14, F + 2, z0, "brewing_stand")
        m.set(side * 13, F + 2, z0, r.choice(["decorated_pot", "flower_pot", "potted_brown_mushroom"]))
        m.fill(x, F + 1, z0 + 2, x, F + 4, z0 + 4, "bookshelf")
        m.set(x, F + 2, z0 + 3, "chiseled_bookshelf[facing=" + ("east" if side < 0 else "west") + ",slot_0_occupied=true,slot_3_occupied=true]")
        m.set(side * 14, F + 1, z0 + 4, "barrel[facing=up]")
        m.set(side * 13, F + 1, z0 + 4, "cauldron")
# two mushroom beds (dark corners) and a corner of cave vines with glow berries
for x0 in (-10, 6):
    m.fill(x0, F, 70, x0 + 4, F, 74, "mycelium")
    m.fill(x0 - 1, F + 1, 69, x0 + 5, F + 1, 69, "spruce_trapdoor[half=top,open=true,facing=south]")
    for x in range(x0, x0 + 5):
        for z in range(70, 75):
            if r.random() < 0.7:
                m.set(x, F + 1, z, "brown_mushroom" if (x + z) % 2 else "red_mushroom")
for x in range(-14, -8):
    for z in range(79, 84):
        if r.random() < 0.6:
            hang(x, F + 9, z, r.randint(2, 5), "cave")
# collapsed part of the east wall: roots coming in, rubble, cobwebs
m.fill(15, F + 1, 78, 17, F + 7, 82, "air")
for _ in range(14):
    m.set(r.randint(14, 17), r.randint(F + 1, F + 6), r.randint(77, 83), r.choice(["rooted_dirt", "mangrove_roots", "mossy_cobblestone", "dirt"]))
for _ in range(6):
    m.set(r.randint(-15, 15), F + 8, r.randint(65, 91), "cobweb")
# the Alembic: a water cauldron in the middle of copper pipes and a still
m.fill(-2, F, 86, 2, F, 90, "waxed_oxidized_cut_copper")
m.set(0, F + 1, 88, "water_cauldron[level=3]")
for x, z in ((-2, 86), (2, 86), (-2, 90), (2, 90)):
    m.fill(x, F + 1, z, x, F + 3, z, "waxed_oxidized_copper_grate")
    m.set(x, F + 4, z, "lightning_rod")
m.fill(-2, F + 4, 88, 2, F + 4, 88, "waxed_weathered_cut_copper_slab[type=bottom]")
m.set(0, F + 5, 88, "brewing_stand")
m.set(-1, F + 1, 88, "decorated_pot")
m.set(1, F + 1, 88, "decorated_pot")
for mob, x, z in (("sylve_moisi", -8, 67), ("sylve_moisi", 9, 78), ("sylve_ronceux", -6, 84), ("sylve_ronceux", 6, 72)):
    m.mob(mob, x, z, y=F + 1, yaw=180, room="3")
m.fill(-17, F, 93, 17, F + 10, 93, "stone_bricks")
m.fill(-2, F + 1, 93, 2, F + 4, 93, "waxed_oxidized_cut_copper")
m.door("alambic", (-2, F + 1, 93), (2, F + 4, 93), room="3", type="offering")
m.doors[-1]["offering"] = {
    "altar": [0, F + 1, 88], "ordered": True, "title": "Alambic : la tisane des anciens",
    "grant": "fdgrant_tisane_sylve",
    "grant-message": "&#2ECF7BLa Tisane de sylve est prête : vous la buvez tous. &#B376E5Les racines du gardien vous retiendront moins longtemps.",
    "items": [
        {"item": "brown_mushroom", "amount": 1, "hint": "1. La chair brune du sous-bois"},
        {"item": "red_mushroom", "amount": 1, "hint": "2. Le chapeau rouge tacheté de blanc"},
        {"item": "glow_berries", "amount": 2, "hint": "3. Les baies qui luisent sous la voûte"},
        {"item": "blue_orchid", "amount": 1, "hint": "4. La fleur qui calme les fièvres"},
    ]}

# ================================================================ zone 4: the Deep Roots (z 94..129, down to y A)
for z in range(94, 130):
    k = min(1.0, (z - 94) / 24)
    y = round(F - (F - A) * k)
    half = 6 + round(3 * math.sin(z * 0.35)) + (3 if z > 116 else 0)
    cx = round(2 * math.sin(z * 0.2))
    m.fill(cx - half, y + 1, z, cx + half, y + 8 + round(2 * math.sin(z * 0.5)), z, "air")
    mix(cx - half, y, z, cx + half, y, z, "rooted_dirt", ["moss_block", "dirt", "coarse_dirt", "mud", "tuff"], 0.4)
    for x in range(cx - half, cx + half + 1):
        if r.random() < 0.25:
            m.set(x, y + 1, z, r.choice(["moss_carpet", "moss_carpet", "short_grass", "fern"]))
    for x in (cx - half - 1, cx + half + 1):
        if r.random() < 0.3:
            m.set(x, y + r.randint(1, 5), z, "glow_lichen[" + ("east" if x < cx else "west") + "=true]")
    top = y + 8 + round(2 * math.sin(z * 0.5))
    if r.random() < 0.35:
        hang(cx + r.randint(-half + 1, half - 1), top, z, 1, "roots")
    if r.random() < 0.15:
        hang(cx + r.randint(-half + 1, half - 1), top, z, r.randint(2, 4), "cave")
    if z % 9 == 0:
        m.set(cx + r.randint(-half + 2, half - 2), top + 1, z, "shroomlight")
# low ceilings with spore blossoms (within reach) along the way down
def floor_at(z):
    return round(F - (F - A) * min(1.0, (z - 94) / 24))


for z in (104, 110, 121, 124):
    for x in (-3, 3):
        y = floor_at(z) + 3
        m.fill(x - 1, y + 1, z - 1, x + 1, y + 7, z + 1, "rooted_dirt")
        m.fill(x - 1, y + 1, z - 1, x + 1, y + 1, z + 1, "moss_block")
        m.set(x, y, z, "spore_blossom")
# giant roots crossing the cave
for x0, y0, z0, x1, y1, z1 in ((-10, F + 6, 96, 9, F - 2, 104), (8, F + 4, 101, -9, F - 6, 112),
                               (-9, A + 9, 112, 10, A + 2, 120), (10, A + 8, 118, -11, A + 1, 126)):
    for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
        root_line(x0 + dx, y0 + dy, z0, x1 + dx, y1 + dy, z1, "dark_oak_wood")
    for i in range(0, 20, 3):
        k = i / 19
        root_line(round(x0 + (x1 - x0) * k), round(y0 + (y1 - y0) * k) - 1, round(z0 + (z1 - z0) * k),
                  round(x0 + (x1 - x0) * k) + r.randint(-1, 1), round(y0 + (y1 - y0) * k) - 3, round(z0 + (z1 - z0) * k), "mangrove_roots")
for mob, x, z in (("sylve_tisseuse", -3, 103), ("sylve_tisseuse", 4, 115), ("sylve_ronceux", 0, 109), ("sylve_ronceux", -4, 121)):
    m.mob(mob, x, z, y=floor_at(z) + 1, yaw=180, room="4")
# a small pool and orchids, a few mushrooms (the seal asks what the upper zones taught)
m.disc(-7, 118, 2, A, A, "water")
for x, z in ((-9, 118), (-5, 118), (-7, 116), (-7, 120)):
    m.set(x, A, z, "grass_block")
    m.set(x, A + 1, z, "blue_orchid")
for x, z in ((6, 119), (7, 121), (5, 122)):
    m.set(x, A, z, "mycelium")
    m.set(x, A + 1, z, "red_mushroom")
# the Seal of Roots: rooted dirt on an old pedestal, the wall of roots behind it (z 127)
m.fill(-12, A, 127, 12, A + 12, 127, "rooted_dirt")
mix(-12, A + 1, 127, 12, A + 12, 127, "rooted_dirt", ["mangrove_roots", "muddy_mangrove_roots", "dirt", "moss_block"], 0.5)
m.fill(-2, A + 1, 127, 2, A + 5, 127, "mangrove_roots")
m.fill(-1, A + 2, 127, 1, A + 4, 127, "muddy_mangrove_roots")
m.set(0, A + 1, 124, "chiseled_stone_bricks")
m.set(0, A + 2, 124, "rooted_dirt")
for x, z in ((-1, 124), (1, 124), (0, 123)):
    m.set(x, A + 1, z, "mossy_stone_brick_slab[type=bottom]")
m.door("sceau", (-2, A + 1, 127), (2, A + 5, 127), room="4", type="offering")
m.doors[-1]["offering"] = {
    "altar": [0, A + 2, 124], "ordered": False, "title": "Le Sceau des racines",
    "items": [
        {"item": "spore_blossom", "amount": 1, "hint": "La fleur qui pleut de la voûte"},
        {"item": "glow_berries", "amount": 2, "hint": "Les baies qui luisent dans le noir"},
        {"item": "blue_orchid", "amount": 1, "hint": "La fleur qui calme les fièvres"},
        {"item": "red_mushroom", "amount": 1, "hint": "Le chapeau rouge tacheté de blanc"},
    ]}
# the root tunnel into the sanctuary (z 128..130): giant roots arching over the way
m.fill(-2, A + 1, 128, 2, A + 6, 130, "air")
mix(-2, A, 128, 2, A, 130, "rooted_dirt", ["moss_block", "mossy_stone_bricks"], 0.4)
for z in (128, 130):
    root_line(-3, A + 1, z, -1, A + 7, z, "dark_oak_wood")
    root_line(3, A + 1, z, 1, A + 7, z, "dark_oak_wood")

# ================================================================ zone 5: the Sanctuary (playable x -15..14, z 131..160)
X0, X1, Z0, Z1 = -15, 14, 131, 160
CXa, CZa = 0, 146                         # centre of the square: (0.0, 146.0)
# the cave around it: a dome rising to A + 19, irregular
for x in range(-24, 24):
    for z in range(Z0 - 1, 170):
        d = math.hypot(x + 0.5 - CXa, z + 0.5 - CZa)
        if d > 23:
            continue
        h = round(19 - (d / 23) ** 2 * 10 + 1.5 * math.sin(x * 0.7) * math.cos(z * 0.6))
        m.fill(x, A + 1, z, x, A + max(4, h), z, "air")
# outside the square: walls and alcoves (decor never inside the playable area)
for x in range(-24, 24):
    for z in range(Z0 - 1, 170):
        if X0 <= x <= X1 and Z0 <= z <= Z1:
            continue
        if math.hypot(x + 0.5 - CXa, z + 0.5 - CZa) <= 23:
            m.set(x, A + 1, z, "mossy_stone_bricks")
# the boundary wall (one block outside the square), 5 high, with an entrance in the south wall
for x in range(X0 - 1, X1 + 2):
    for z in (Z0 - 1, Z1 + 1):
        m.fill(x, A + 1, z, x, A + 5, z, r.choice(STONES))
for z in range(Z0 - 1, Z1 + 2):
    for x in (X0 - 1, X1 + 1):
        m.fill(x, A + 1, z, x, A + 5, z, r.choice(STONES))
m.fill(-2, A + 1, Z0 - 1, 2, A + 5, Z0 - 1, "air")
m.fill(-3, A + 6, Z0 - 1, 3, A + 6, Z0 - 1, "chiseled_stone_bricks")
# the floor: worn stone and moss, an octagon of polished stone, a leaf drawn in moss with a froglight heart
mix(X0, A, Z0, X1, A, Z1, "mossy_stone_bricks", ["stone_bricks", "cracked_stone_bricks", "moss_block", "rooted_dirt",
                                                 "mossy_cobblestone"], 0.35)
for i in range(8):
    a0, a1 = math.radians(22.5 + 45 * i), math.radians(22.5 + 45 * (i + 1))
    p0 = (CXa + 12.5 * math.cos(a0), CZa + 12.5 * math.sin(a0))
    p1 = (CXa + 12.5 * math.cos(a1), CZa + 12.5 * math.sin(a1))
    for k in range(30):
        t = k / 29
        m.set(math.floor(p0[0] + (p1[0] - p0[0]) * t - 0.5), A, math.floor(p0[1] + (p1[1] - p0[1]) * t - 0.5),
              "polished_andesite")
for z in range(CZa - 6, CZa + 6):                      # the leaf: midrib and two halves, pointing north
    w = round(4 * math.sin(math.pi * (z - (CZa - 6)) / 12))
    m.fill(-w - 1, A, z, w - 1, A, z, "moss_block")
    m.set(-1, A, z, "green_glazed_terracotta")
    m.set(0, A, z, "green_glazed_terracotta")
for x, z in ((-1, CZa - 1), (0, CZa - 1), (-1, CZa), (0, CZa)):
    m.set(x, A, z, "verdant_froglight")
# flat plants inside the square near its edges (passable), lit plants for the realm
for x in range(X0, X1 + 1):
    for z in range(Z0, Z1 + 1):
        edge = min(x - X0, X1 - x, z - Z0, Z1 - z)
        if edge <= 1 and r.random() < 0.45 and not (-2 <= x <= 2 and z <= Z0 + 1):
            m.set(x, A + 1, z, r.choice(["moss_carpet", "fern", "short_grass", "allium", "lily_of_the_valley", "blue_orchid",
                                         "azure_bluet"]))
for x, z in ((X0, Z0 + 5), (X0, Z1 - 5), (X1, Z0 + 5), (X1, Z1 - 5), (X0 + 7, Z1), (X1 - 7, Z1)):
    m.set(x, A, z, "moss_block")
    m.set(x, A + 1, z, "torchflower")
# roots on the walls, glow lichen, shroomlights, glow berries and hanging roots from the dome
for x in range(X0 - 1, X1 + 2, 3):
    for z in (Z0 - 1, Z1 + 1):
        if r.random() < 0.6:
            root_line(x, A + 5, z, x + r.randint(-1, 1), A + r.randint(1, 3), z, "mangrove_roots")
for z in range(Z0, Z1 + 1, 3):
    for x in (X0 - 1, X1 + 1):
        if r.random() < 0.6:
            root_line(x, A + 5, z, x, A + r.randint(1, 3), z + r.randint(-1, 1), "mangrove_roots")
        if r.random() < 0.4:
            m.set(x, A + r.randint(2, 4), z, "shroomlight")
for _ in range(30):
    x, z = r.randint(-20, 19), r.randint(Z0, 166)
    d = math.hypot(x + 0.5 - CXa, z + 0.5 - CZa)
    if d > 21:
        continue
    top = A + max(4, round(19 - (d / 23) ** 2 * 10)) + 1
    hang(x, top - 1, z, r.randint(2, 6), r.choice(["cave", "roots", "cave"]))
for _ in range(10):
    x, z = r.randint(-18, 17), r.randint(Z0, 164)
    d = math.hypot(x + 0.5 - CXa, z + 0.5 - CZa)
    top = A + max(4, round(19 - (d / 23) ** 2 * 10)) + 1
    m.set(x, top, z, "moss_block")
    m.set(x, top - 1, z, "spore_blossom")
# giant roots arching over the sanctuary (high above the fight)
for x0, z0, x1, z1 in ((-20, 130, 18, 162), (19, 132, -18, 160), (-22, 146, 22, 147)):
    for t in range(41):
        k = t / 40
        y = A + 15 + round(4 * math.sin(math.pi * k))
        x, z = round(x0 + (x1 - x0) * k), round(z0 + (z1 - z0) * k)
        m.fill(x, y, z, x + 1, y + 1, z, "dark_oak_wood")
        if t % 5 == 0:
            hang(x, y - 1, z, 1, "roots")
# broken pillars and abandoned installations in the corners and niches (outside the square)
for x, z in ((X0 - 3, Z0 + 2), (X1 + 3, Z0 + 2), (X0 - 3, Z1 - 2), (X1 + 3, Z1 - 2)):
    column(x, z, A + 1, A + 9, broken=True)
    column(x + (1 if x < 0 else -1), z, A + 1, A + 7, broken=True)
for x, z in ((X0 - 3, CZa), (X1 + 3, CZa)):
    m.fill(x, A + 1, z - 2, x, A + 1, z + 2, "spruce_slab[type=top]")
    m.set(x, A + 2, z - 1, "brewing_stand")
    m.set(x, A + 2, z + 1, "potted_azalea_bush")
    m.set(x, A + 1, z + 3, "composter[level=8]")
    m.set(x, A + 1, z - 3, "barrel[facing=up]")
for x in range(X0 - 4, X1 + 5):
    for z in range(Z0, Z1 + 5):
        if X0 - 1 <= x <= X1 + 1 and Z0 - 1 <= z <= Z1 + 1:
            continue
        if r.random() < 0.12:
            m.set(x, A + 2, z, r.choice([LEAVES, BLOOM, "fern", "moss_carpet"]))

# the boss waits hidden in the middle, facing the entrance (south: yaw 180)
m.spawns.append({"mob": "renard_sylvestre", "at": [0.0, float(A + 1), 146.0, 180.0], "room": "5"})
m.objectives.append("renard_sylvestre")


# ================================================================ output: offering doors need their own YAML
_plain = m.edits_yaml


def edits_yaml(dungeon):
    """maplib writes plain doors; the offering of a door is written under it (read by DungeonDefinition)."""
    text = _plain(dungeon)
    for d in m.doors:
        o = d.get("offering")
        if not o:
            continue
        lines = [f"    offering:", f"      altar: [{', '.join(str(v) for v in o['altar'])}]",
                 f"      ordered: {'true' if o['ordered'] else 'false'}", f"      title: '{o['title']}'"]
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

if __name__ == "__main__":
    server = sys.argv[1] if len(sys.argv) > 1 else "labs"
    if server == "--dry":
        print(f"{len(m.cmds)} commands, {len(m.spawns)} monsters, {len(m.doors)} doors")
        print(m.edits_yaml("herboriste"))
    else:
        maplib.run(m, server, "herboriste")
