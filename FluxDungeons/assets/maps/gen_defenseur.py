#!/usr/bin/env python3
"""Map of the defender's dungeon, Le Domaine des Traqueurs, modelled in memory (gridlib, flora), checked, then built by
console commands with the RPG look of the dungeons.

An old hunting domain of a brotherhood of monster hunters, taken back by the creatures they hunted; played from south
to north (+z), ground at y 63 with real relief (hills, a ravine, a valley), never a flat wall of dirt (every steep face
is painted as a cut of ground: rock at the foot, dirt and roots above, grass on top):
  - the way in (start 0, 64, -20): a trail between two rocky outcrops under the hunters' arch of logs and banners;
  - room 1, the Trackers' Camp: a clearing sunk between low hills; a big canvas tent, two small ones (one torn down),
    the central fire with log seats, a tripod of lanterns, a preparation table, weapon racks, a woodpile, a palisade
    partly broken with targets and nets, a rough lookout; signs of a fight (fallen tree, overturned barrels, bones);
  - room 2, the Forest of Predators: a slope up, a forest of oak, spruce, dark oak and birch (old giants, dead trees,
    fallen trunks), a shallow ravine on the west with cut rock walls, roots and little caves, a secondary clearing, the
    old path nearly covered, tracks of big creatures, nests, webs;
  - room 3, the Lands of the Hunt: a valley crossed by the main path, talus and rocks on its sides, two watch towers,
    three observation decks (reachable by stairs: the watchers post there), broken palisades, readable traps (pits
    ringed with stakes, hanging nets, broken cages, jaw traps), cut trunks; the lodge seen over the trees at its end;
  - room 4, the Hunters' Lodge: stone foundations, thick spruce frame, dark oak facade, a jettied upper storey with a
    gallery, a steep roof, a chimney, a round side tower, a porch; inside the great hall (fireplace, tables, trophies,
    weapon racks, banners, chandeliers) and the trophy gallery, one corner broken in by the creatures;
  - room 5, the Skeleton's Sanctuary: an octagonal hunting arena, 33 x 33 of flat floor drawn as a target, walls with
    pillars, arches, niches of trophies, broken columns and statues out of the floor, a stone throne under a great
    skull at the back. The Ossacorne (boss, unchanged) lies in front of the throne; its four columns stay at 10 blocks
    from the middle (its charge crashes into them, they shelter from its bone fragments: docs/technique/boss/ossacorne.md).

Ambience (sounds, smoke, spores, ash) is placed near each feature (map.ambience, dungeon.Ambience of FluxDungeons).
Usage: gen_defenseur.py [server] [--check] [--preview DIR]     (labs by default)
"""
import math
import os
import sys

import flora as floralib
import gridlib
import maplib

F = 63
X0, X1, Y0, Y1, Z0, Z1 = -50, 50, 40, 130, -40, 318
BANDS = ((40, 56, "stone"), (57, 62, "dirt"), (63, 63, "grass_block"))
W = 22                                     # half width of the playable ground (curtains beyond)
DOORS_Z = (38, 112, 198, 263)              # rooms 1..5 are split by these lines
CX, CZ, APO = 0, 280, 16                   # the arena: centre, half width of its octagon of floor
COLUMNS = [(CX + round(10 * math.cos(math.radians(a))), CZ + round(10 * math.sin(math.radians(a)))) for a in (45, 135, 225, 315)]
BOSS = (0, 287)                            # the Ossacorne, facing the entrance
g = gridlib.Model((X0, X1, Y0, Y1, Z0, Z1), BANDS, seed=6060)
r = g.r
fl = floralib.Flora(g, 6061)
m = maplib.Map()
m.box = (X0, Z0, X1, Z1)
_door = m.door
H = {}                                     # ground height above F of each column (x, z)

ROCK = [("stone", 4), ("andesite", 3), ("cobblestone", 2), ("mossy_cobblestone", 2), ("tuff", 2)]
CUT = [("dirt", 3), ("coarse_dirt", 3), ("rooted_dirt", 2), ("packed_mud", 1), ("stone", 2), ("andesite", 1)]
PATH = [("dirt_path", 5), ("coarse_dirt", 3), ("gravel", 2), ("cobblestone", 1), ("mossy_cobblestone", 1), ("stone", 1)]
TOPS = {
    1: [("grass_block", 6), ("coarse_dirt", 2), ("podzol", 1), ("rooted_dirt", 1)],
    2: [("grass_block", 4), ("podzol", 3), ("moss_block", 2), ("coarse_dirt", 1), ("rooted_dirt", 1)],
    3: [("grass_block", 5), ("coarse_dirt", 2), ("packed_mud", 1), ("gravel", 1), ("podzol", 1)],
    4: [("grass_block", 6), ("coarse_dirt", 1), ("gravel", 1)],
    5: [("grass_block", 5), ("podzol", 1), ("moss_block", 1)],
}
UNDER = [("short_grass", 6), ("fern", 4), ("large_fern", 1), ("dead_bush", 1), ("moss_carpet", 1), ("sweet_berry_bush[age=3]", 1),
         ("brown_mushroom", 1), ("red_mushroom", 1), ("poppy", 1), ("dandelion", 1), ("oxeye_daisy", 1)]


def zone_of(x, y, z):
    for i, lim in enumerate(DOORS_Z):
        if z <= lim:
            return i + 1
    return 5


def door(id, frm, to, room):
    g.doors.append((tuple(min(a, b) for a, b in zip(frm, to)), tuple(max(a, b) for a, b in zip(frm, to))))
    _door(id, frm, to, room=room)


def px(z):
    """Middle of the main path."""
    if z < 40:
        return round(1.5 * math.sin(z * 0.12))
    if z < 198:
        return round(5 * math.sin(z * 0.04) + 2 * math.sin(z * 0.11))
    return 0


def near_path(x, z, w=3):
    return abs(x - px(z)) <= w


def light(x, y, z, level=11):
    if g.get(x, y, z) == "air":
        g.set(x, y, z, f"light[level={level}]")


def top(x, z):
    return F + H.get((x, z), 0)


def put(x, y, z, b):
    if g.get(x, y, z) == "air":
        g.set(x, y, z, b)


# ======================================================================== the ground: heights, then painting

def height(x, z):
    """Ground height above F of the playable ground (relief of each room)."""
    n = fl.n(x * 0.13, 1.7, z * 0.13)
    if z < 40:                                       # camp: a clearing sunk between low hills
        if z < -6:                                   # the trail between the outcrops
            return 0 if abs(x - px(z)) <= 4 else min(5, round((abs(x - px(z)) - 4) * 0.9 + 1.2 * n))
        d = math.hypot(x / 13.5, (z - 14) / 16)
        if abs(x - px(z)) <= 3 and z > 26:
            return 0                                 # the way out
        return max(0, min(4, round((d - 1.0) * 5 + 1.2 * n)))
    if z < 112:                                      # forest: a slope up, hills, the ravine
        base = min(3, (z - 40) // 4)
        h = base
        if not near_path(x, z, 4):
            h += max(0, round(1.8 * n + 0.4))
        if -22 <= x <= -8 and 60 <= z <= 98:          # the ravine, west: 3 below the forest floor, ramps at its ends
            depth = 3 * min(1, (z - 60) / 6, (98 - z) / 6) * max(0, min(1, (6 - abs(x + 15)) / 2))
            h = base - round(depth) if abs(x + 15) <= 6 else h
        return h
    if z < 198:                                      # valley: down from the forest, talus on both sides
        base = max(0, 3 - (z - 112) // 3)
        side = max(0, abs(x - px(z)) - 7)
        return max(base, min(5, round(side * 0.45 + 1.4 * n * (side > 0))))
    return 0                                         # lodge grounds and arena


def landscape_height(x, z):
    """Hills beyond the playable ground."""
    out = max(0, abs(x) - W - 1, -30 - z, z - 306)
    if not out:
        return None
    return round(min(24, 2 + out * (0.8 + 0.5 * fl.n(x * 0.1, 0, z * 0.1))))


def ground():
    for x in range(X0, X1 + 1):
        for z in range(Z0, Z1 + 1):
            lh = landscape_height(x, z)
            H[(x, z)] = lh if lh is not None else height(x, z)
    # paint every column: top, then its faces where a neighbour is lower (a cut of ground, never plain dirt)
    for (x, z), h in H.items():
        t = F + h
        zone = zone_of(x, 0, z)
        lo = min(F, t) - 4
        for y in range(lo, t + 1):
            if y > F or y <= t:
                pass
            if y == t:
                b = g.pick(TOPS[zone])
            else:
                exposed = min(H.get((x + dx, z + dz), h) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))) + F < y
                if exposed:
                    b = g.pick(ROCK) if y < t - 2 or y <= F - 1 else g.pick(CUT)
                elif y > F:
                    b = "dirt"
                else:
                    continue
            g.set(x, y, z, b)
        for y in range(t + 1, F + 1):                # dug below the floor (the ravine)
            g.set(x, y, z, "air")
        # roots hang out of high faces
        if h >= 2 and r.random() < 0.08:
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if H.get((x + dx, z + dz), h) <= h - 2:
                    put(x + dx, t - 1, z + dz, "hanging_roots")
                    break


def path(z0, z1, width=2, mats=PATH):
    for z in range(z0, z1 + 1):
        c = px(z)
        w = width + (1 if math.sin(z * 0.37) > 0.55 else 0)
        for x in range(c - w, c + w + 1):
            if abs(x - c) == w and r.random() < 0.35:
                continue                              # ragged edges
            g.set(x, top(x, z), z, g.pick(mats))


def curtains():
    fl.curtain(-W - 1, -30, -W - 1, Z1 - 12, F - 4, Y1)
    fl.curtain(W + 1, -30, W + 1, Z1 - 12, F - 4, Y1)
    fl.curtain(-W - 1, -31, W + 1, -31, F - 4, Y1)


def outcrop(x, z, rx, ry, rz):
    """A rock standing out of the ground, mossy on top."""
    y0 = top(x, z)
    for dx in range(-rx - 1, rx + 2):
        for dz in range(-rz - 1, rz + 2):
            for dy in range(0, ry + 2):
                d = (dx / (rx + 0.5)) ** 2 + (dz / (rz + 0.5)) ** 2 + (dy / (ry + 0.5)) ** 2
                if d <= 1 + 0.25 * fl.n(x + dx, dy, z + dz):
                    b = g.pick(ROCK)
                    if g.get(x + dx, y0 + dy + 1, z + dz) == "air" and r.random() < 0.5:
                        b = "mossy_cobblestone" if r.random() < 0.6 else "moss_block"
                    g.set(x + dx, y0 + dy, z + dz, b)


def boulder(x, z, size=1):
    y = top(x, z) + 1
    for dx in range(size):
        for dz in range(size):
            g.set(x + dx, y, z + dz, g.pick([("mossy_cobblestone", 3), ("andesite", 2), ("stone", 2), ("tuff", 1)]))
            if size > 1 and r.random() < 0.5:
                g.set(x + dx, y + 1, z + dz, g.pick([("mossy_cobblestone", 2), ("moss_block", 1)]))
            elif r.random() < 0.4:
                put(x + dx, y + 1, z + dz, "moss_carpet")


def tree_at(x, z, kind, family):
    if g.get(x, top(x, z) + 1, z) == "air":
        fl.tree(x, z, top(x, z) + 1, kind, family)


def undergrowth(z0, z1, n, table=UNDER, avoid=None):
    for _ in range(n):
        x, z = r.randint(-W, W), r.randint(z0, z1)
        if near_path(x, z, 2) or (avoid and avoid(x, z)):
            continue
        fl.plant(x, top(x, z) + 1, z, g.pick(table))


def bones(x, y, z):
    """A few bones on the ground (item displays, nothing to trip on)."""
    for _ in range(r.randint(1, 3)):
        g.item_display(x + r.uniform(0.1, 0.9), y + 0.03, z + r.uniform(0.1, 0.9), "bone", yaw=r.uniform(0, 360), roll=90, scale=0.7)


def bow_rack(x, y, z, facing_yaw):
    """Two posts and a bar, bows and arrows hung on it."""
    g.set(x, y, z, "spruce_fence")
    g.set(x, y + 1, z, "spruce_fence")
    g.item_display(x + 0.5, y + 1.3, z + 0.5, "bow", yaw=facing_yaw, roll=0, scale=0.9)
    g.item_display(x + 0.5, y + 0.6, z + 0.5, "arrow", yaw=facing_yaw, roll=45, scale=0.7)


def net(x, y, z, w=3):
    for k in range(w):
        put(x + k, y, z, "cobweb")
        if r.random() < 0.6:
            put(x + k, y - 1, z, "cobweb")


# ======================================================================== the way in and room 1, the camp

def way_in():
    path(-36, 37, 2)
    g.start = (px(-26), F + 1, -26)
    m.start = [px(-26) + 0.5, float(F + 1), -25.5, 0.0, 0.0]
    for (x, z, rx, ry, rz) in ((-9, -16, 4, 5, 6), (9, -13, 4, 6, 5), (-12, -28, 5, 4, 4), (12, -26, 4, 4, 5)):
        outcrop(x, z, rx, ry, rz)
    # the hunters' arch: two log posts, a beam, antler-like branches, banners and a lantern
    z = -8
    a, b = px(z) - 4, px(z) + 4
    for x in (a, b):
        g.box(x, F + 1, z, x, F + 7, z, "spruce_log")
        g.set(x, F + 1, z - 1, "spruce_stairs[facing=south]")
        g.set(x, F + 1, z + 1, "spruce_stairs[facing=north]")
    g.box(a - 1, F + 8, z, b + 1, F + 8, z, "stripped_spruce_log[axis=x]")
    for x in (a, b):
        g.set(x, F + 9, z, "spruce_fence")
        g.set(x + (1 if x == a else -1), F + 10, z, "spruce_fence")
    for x in (a + 1, b - 1):
        g.set(x, F + 7, z - 1, "brown_wall_banner[facing=north]")
    g.set(px(z), F + 7, z, "iron_chain")
    g.set(px(z), F + 6, z, "lantern[hanging=true]")
    g.item_display(px(z) + 0.5, F + 9.6, z + 0.5, "skeleton_skull", yaw=0, scale=1.3)
    for x, zz in ((-15, -20), (16, -6), (-17, -2), (15, -32)):
        tree_at(x, zz, r.choice(["spruce", "oak"]), r.choice(["conifer", "medium", "big"]))
    undergrowth(-36, -9, 50)
    m.sound(px(-12), F + 3, -12, [("item.elytra.flying", 0.12, 1.4), ("block.azalea_leaves.step", 0.5, 0.8)], radius=16, every=(140, 260))


def tent(x0, z0, w, d, wool, open_side="south", torn=False):
    """A ridge tent along z: canvas in steps of wool and stairs, a ridge pole, posts, an open front."""
    half = w // 2
    cxx = x0 + half
    for z in range(z0, z0 + d):
        for dx in range(-half, half + 1):
            h = half - abs(dx)
            x = cxx + dx
            y = top(cxx, z0) + 1 + h
            if torn and r.random() < 0.25 and h > 0:
                continue
            g.set(x, y, z, wool if (z - z0) % 3 else wool.replace("_wool", "_wool") if False else wool)
            if dx:
                g.set(x, y, z, f"{wool.replace('_wool', '')}_carpet" if False else wool)
    # hollow inside, front open
    for z in range(z0 + (0 if open_side == "south" else 1), z0 + d - (1 if open_side == "south" else 0)):
        for dx in range(-half + 1, half):
            for yy in range(top(cxx, z0) + 1, top(cxx, z0) + 1 + half - abs(dx)):
                g.set(cxx + dx, yy, z, "air")
    for dx in range(-half + 1, half):
        for yy in range(top(cxx, z0) + 1, top(cxx, z0) + 1 + half - abs(dx)):
            g.set(cxx + dx, yy, z0, "air")
    ridge = top(cxx, z0) + 1 + half
    g.box(cxx, ridge + 1, z0 - 1, cxx, ridge + 1, z0 + d, "stripped_spruce_log[axis=z]")
    for z in (z0 - 1, z0 + d):
        g.box(cxx, top(cxx, z0) + 1, z, cxx, ridge, z, "spruce_fence")
    for z in range(z0, z0 + d):
        for dx in (-half - 1, half + 1):
            if r.random() < 0.3:
                put(cxx + dx, top(cxx + dx, z) + 1, z, "spruce_fence")
    return cxx, top(cxx, z0) + 1


def camp():
    ground_y = F + 1
    # big tent (west), two small ones (east, the second torn down)
    cx1, y1 = tent(-14, 6, 9, 9, "brown_wool")
    g.set(cx1 - 2, y1, 13, "red_bed[facing=south,part=head]")
    g.set(cx1 - 2, y1, 12, "red_bed[facing=south,part=foot]")
    g.set(cx1 + 2, y1, 13, "chest[facing=west]")
    g.set(cx1 + 2, y1, 12, "barrel[facing=up]")
    g.set(cx1 + 1, y1, 13, "cartography_table")
    g.set(cx1 - 3, y1, 9, "crafting_table")
    g.item_display(cx1 + 1.5, y1 + 1.05, 13.5, "filled_map", yaw=0, roll=0, scale=0.6)
    cx2, y2 = tent(7, 2, 7, 6, "white_wool")
    g.set(cx2 - 1, y2, 6, "green_bed[facing=south,part=head]")
    g.set(cx2 - 1, y2, 5, "green_bed[facing=south,part=foot]")
    g.set(cx2 + 1, y2, 6, "barrel[facing=up]")
    tent(8, 19, 7, 6, "green_wool", torn=True)
    g.set(10, F + 1, 22, "barrel[facing=east]")
    g.set(12, F + 1, 24, "barrel[facing=north]")
    # the fire: campfire, log seats, a tripod with lanterns, cauldron, barrels
    fx, fz = 0, 14
    g.set(fx, F, fz, "cobblestone")
    g.set(fx, F + 1, fz, "campfire[lit=true]")
    for (dx, dz, axis) in ((-3, 0, "z"), (3, 0, "z"), (0, -3, "x"), (0, 3, "x")):
        g.set(fx + dx, F + 1, fz + dz, f"stripped_spruce_log[axis={axis}]")
        g.set(fx + dx + (1 if axis == "x" else 0), F + 1, fz + dz + (1 if axis == "z" else 0), f"stripped_spruce_log[axis={axis}]")
    for dx, dz in ((-2, -2), (2, -2), (0, 2)):
        pass
    for dx, dz in ((-2, 2), (2, 2)):
        g.box(fx + dx, F + 1, fz + dz, fx + dx, F + 3, fz + dz, "spruce_fence")
    g.box(fx - 2, F + 4, fz + 2, fx + 2, F + 4, fz + 2, "stripped_spruce_log[axis=x]")
    g.set(fx - 1, F + 3, fz + 2, "lantern[hanging=true]")
    g.set(fx + 1, F + 3, fz + 2, "lantern[hanging=true]")
    g.set(fx + 4, F + 1, fz - 3, "cauldron")
    g.set(fx - 4, F + 1, fz - 3, "barrel[facing=up]")
    for _ in range(14):
        a = r.uniform(0, 2 * math.pi)
        d = r.uniform(1.5, 5)
        g.set(round(fx + d * math.cos(a)), F, round(fz + d * math.sin(a)), g.pick([("coarse_dirt", 3), ("dirt_path", 2), ("packed_mud", 1)]))
    # preparation table and weapon racks next to the big tent
    g.set(-6, F + 1, 4, "smithing_table")
    g.set(-5, F + 1, 4, "crafting_table")
    g.set(-4, F + 1, 4, "grindstone[face=floor,facing=north]")
    g.set(-7, F + 1, 4, "barrel[facing=up]")
    g.item_display(-5.5, F + 2.05, 4.5, "iron_axe", yaw=20, roll=90, scale=0.7)
    for x in (-8, -11):
        bow_rack(x, F + 1, 3, 0)
    # the woodpile and a chopping block
    fl.log_pile(5, 27, F + 1, 3, 2, "spruce", axis="x")
    g.set(3, F + 1, 25, "stripped_oak_log")
    g.item_display(3.5, F + 2.1, 25.5, "iron_axe", yaw=45, roll=60, scale=0.8)
    # the palisade round the west and north edges, broken in places, with targets, nets and trophies
    for z in range(0, 34):
        x = -17 + round(1.2 * math.sin(z * 0.3))
        if 13 <= z <= 16 or 26 <= z <= 27:
            g.set(x, top(x, z) + 1, z, "spruce_log[axis=z]") if z == 14 else None
            continue
        h = 3 + (1 if z % 3 == 0 else 0)
        g.box(x, top(x, z) + 1, z, x, top(x, z) + h, z, "spruce_log")
        if z % 3 == 0:
            put(x, top(x, z) + h + 1, z, "spruce_fence")
    for z in (5, 21):
        x = -16 + round(1.2 * math.sin(z * 0.3))
        g.set(x, top(x, z) + 2, z, "target")
    net(-16, F + 3, 9, 1)
    g.item_display(-15.4, F + 3.5, 18.5, "skeleton_skull", yaw=90, scale=1.0)
    g.set(-16, F + 1, 24, "hay_block")
    g.set(-15, F + 1, 25, "hay_block")
    # the rough lookout (east): four posts, a deck at 4, a ladder, a rail, a slab roof
    lx, lz = 14, 10
    for dx in (0, 3):
        for dz in (0, 3):
            g.box(lx + dx, top(lx + dx, lz + dz) + 1, lz + dz, lx + dx, F + 7, lz + dz, "spruce_log")
    g.box(lx, F + 4, lz, lx + 3, F + 4, lz + 3, "spruce_planks")
    for dx in range(4):
        for dz in range(4):
            if dx in (0, 3) or dz in (0, 3):
                put(lx + dx, F + 5, lz + dz, "spruce_fence")
    g.box(lx - 1, F + 8, lz - 1, lx + 4, F + 8, lz + 4, "spruce_slab[type=bottom]")
    g.box(lx + 1, top(lx + 1, lz - 1) + 1, lz - 1, lx + 1, F + 4, lz - 1, "ladder[facing=north]")
    g.set(lx + 1, F + 5, lz, "air")
    g.set(lx + 2, F + 7, lz + 2, "lantern[hanging=true]")
    # the fight: a fallen tree over the north-west corner, overturned crates, bones, claw marks
    fl.fallen(-12, 30, top(-12, 30) + 1, 8, "x", "oak", size=1)
    for (x, z) in ((5, 8), (-3, 22), (9, 15)):
        bones(x, F + 1, z)
    g.set(4, F + 1, 20, "barrel[facing=south]")
    for z in (11, 12):
        g.set(-17 + round(1.2 * math.sin(z * 0.3)), F + 2, z, "stripped_spruce_log")
    # trees of every size round the clearing, out of the way
    for (x, z, kind, fam) in ((-20, 0, "spruce", "conifer"), (19, 2, "oak", "big"), (-21, 22, "dark_oak", "big"),
                              (20, 26, "spruce", "conifer"), (17, 34, "birch", "medium"), (-19, 34, "oak", "medium"),
                              (21, 16, "oak", "small"), (-21, 8, "birch", "medium")):
        tree_at(x, z, kind, fam)
    for (x, z) in ((-10, 27), (12, 31), (-7, 33)):
        fl.stump(x, z, top(x, z) + 1, "oak", 1)
    for (x, z) in ((6, 31), (-14, 2), (16, 22)):
        fl.bush(x, z, top(x, z) + 1, "oak")
    boulder(-12, 25, 2)
    boulder(11, 33, 1)
    undergrowth(-6, 37, 70, avoid=lambda x, z: math.hypot(x - fx, z - fz) < 5)
    light(fx, F + 5, fz - 4, 12)
    light(-10, F + 5, 12, 10)
    light(10, F + 5, 6, 10)
    light(0, F + 5, 28, 10)
    # the door: the north palisade with its gate
    z = DOORS_Z[0]
    a, b = px(z) - 2, px(z) + 2
    for x in range(-W, W + 1):
        if a <= x <= b:
            continue
        hh = 5 + (1 if x % 3 == 0 else 0)
        g.box(x, top(x, z) + 1, z, x, top(x, z) + hh, z, "spruce_log")
    for x in (a - 1, b + 1):
        g.box(x, F + 1, z, x, F + 7, z, "dark_oak_log")
    g.box(a - 1, F + 7, z, b + 1, F + 7, z, "dark_oak_log[axis=x]")
    g.box(a, F + 1, z, b, F + 5, z, lambda x, y, zz: "spruce_planks" if (x + y) % 2 else "stripped_spruce_log")
    door("camp", (a, F + 1, z), (b, F + 5, z), room="1")
    fl.curtain(a, z, b, z, F + 6, F + 6)
    fl.curtain(-W, z, W, z, F + 8, Y1)
    for x in range(-W, W + 1):
        if not (a - 1 <= x <= b + 1):
            fl.curtain(x, z, x, z, top(x, z) + 7, F + 7)
    g.set(a - 1, F + 6, z - 1, "lantern[hanging=false]") if False else None
    g.set(a - 2, F + 1, z - 1, "lantern")
    g.set(b + 2, F + 1, z - 1, "lantern")
    # monsters: calm, three
    m.mob("def_loup", -9, 26, y=top(-9, 26) + 1, yaw=180, room="1")
    m.mob("def_loup", 2, 32, y=top(2, 32) + 1, yaw=180, room="1")
    m.mob("def_traqueur", -12, 20, y=top(-12, 20) + 1, yaw=90, room="1")
    m.sound(fx, F + 1, fz, [("block.campfire.crackle", 0.9, 1.0), ("block.fire.ambient", 0.6, 1.0)], radius=12, every=(30, 70),
            particle={"type": "CAMPFIRE_COSY_SMOKE", "count": 1, "spread": (0.15, 0.3, 0.15), "speed": 0.01, "every": 20})
    m.sound(-18, F + 8, 20, [("entity.parrot.ambient", 0.25, 1.7), ("entity.parrot.ambient", 0.2, 2.0)], radius=20, every=(120, 260))
    m.sound(15, F + 5, 12, [("block.wood.step", 0.4, 0.6), ("block.ladder.step", 0.4, 0.7)], radius=12, every=(160, 320))
    m.sound(0, F + 6, 20, [("item.elytra.flying", 0.1, 1.5), ("block.azalea_leaves.step", 0.5, 0.9)], radius=22, every=(150, 300))


# ======================================================================== room 2, the forest of predators

def forest():
    z0, z1 = 40, 111
    path(z0, z1, 1, [("dirt_path", 2), ("coarse_dirt", 3), ("moss_block", 2), ("grass_block", 2), ("podzol", 2), ("mossy_cobblestone", 1)])
    taken = set()

    def free_spot(x, z, rad):
        return all(math.hypot(x - a, z - b) > rad for a, b in taken)

    # two ancient giants, then trees of every kind and size, a few dead and broken ones
    for (x, z, kind, h, size, crown) in ((16, 58, "dark_oak", 24, 3, 9), (-4 + 18, 96, "spruce", 26, 2, 6)):
        fl.colossal(x, z, top(x, z) + 1, kind, h=h, size=size, crown=crown, roots=4)
        taken.add((x, z))
    for _ in range(140):
        x, z = r.randint(-W + 1, W - 1), r.randint(z0 + 2, z1 - 4)
        if near_path(x, z, 4) or not free_spot(x, z, 4) or (-21 <= x <= -9 and 60 <= z <= 98):
            continue
        kind = g.pick([("oak", 3), ("spruce", 3), ("dark_oak", 2), ("birch", 2)])
        fam = g.pick([("medium", 4), ("big", 2), ("conifer", 3 if kind == "spruce" else 0), ("small", 2), ("dead", 1), ("gnarled", 1)])
        tree_at(x, z, kind, fam)
        taken.add((x, z))
    # the ravine: rock walls (painted by ground()), roots, small caves, a trickle of moss and mud at its bottom
    for z in range(62, 97):
        for x in range(-21, -8):
            if H.get((x, z), 0) < 0:
                g.set(x, top(x, z), z, g.pick([("mud", 2), ("moss_block", 2), ("coarse_dirt", 2), ("gravel", 1), ("rooted_dirt", 1)]))
                if r.random() < 0.06:
                    fl.plant(x, top(x, z) + 1, z, g.pick([("fern", 2), ("brown_mushroom", 1), ("red_mushroom", 1)]))
    for (x, z) in ((-22, 70), (-22, 84)):                 # little caves in the west wall
        for dz in range(-1, 2):
            for dy in range(1, 3):
                g.set(x, F + dy - 0, z + dz, "air")
                g.set(x - 1, F + dy, z + dz, "air")
        g.set(x - 1, F + 1, z, "cobweb")
        bones(x, F + 1, z - 1)
    for z in range(64, 96, 5):
        put(-9, F + 1, z, "hanging_roots") if g.get(-9, F + 2, z) != "air" else None
    # fallen trunks along the way (never across it), stumps, mossy stones, bushes, mushrooms, webs, nests, tracks
    for (x, z, length, axis, kind) in ((6, 47, 7, "x", "oak"), (-19, 102, 6, "z", "spruce"), (9, 82, 8, "x", "dark_oak"),
                                       (-8, 54, 5, "z", "birch")):
        if not any(near_path(xx, zz, 3) for xx, zz in ((x, z), (x + (length if axis == "x" else 0), z + (length if axis == "z" else 0)))):
            fl.fallen(x, z, top(x, z) + 1, length, axis, kind, size=1)
    for _ in range(12):
        x, z = r.randint(-W + 2, W - 2), r.randint(z0 + 3, z1 - 3)
        if not near_path(x, z, 3):
            fl.stump(x, z, top(x, z) + 1, r.choice(["oak", "spruce", "dark_oak"]), r.choice((1, 1, 2)))
    for _ in range(10):
        x, z = r.randint(-W + 2, W - 2), r.randint(z0 + 3, z1 - 3)
        if not near_path(x, z, 3):
            boulder(x, z, r.choice((1, 2)))
    for _ in range(16):
        x, z = r.randint(-W + 2, W - 2), r.randint(z0 + 3, z1 - 3)
        if not near_path(x, z, 3):
            fl.bush(x, z, top(x, z) + 1, r.choice(["oak", "spruce", "dark_oak"]), big=r.random() < 0.3)
    for (x, z) in ((-6, 67), (13, 76), (-2, 104), (18, 47)):
        g.set(x, top(x, z) + 1, z, "cobweb") if g.get(x, top(x, z) + 1, z) == "air" else None
        put(x, top(x, z) + 2, z, "cobweb")
    for (x, z) in ((12, 63), (-6, 88)):                   # nests on low branches: hay and sticks
        y = top(x, z) + 3
        g.set(x, y, z, "hay_block")
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            put(x + dx, y, z + dz, "spruce_fence")
        g.box(x, top(x, z) + 1, z, x, y - 1, z, "dark_oak_log")
    for k in range(9):                                    # tracks of a big creature crossing the path
        z = 70 + k * 2
        x = px(z) - 4 + k
        g.set(x, top(x, z), z, "packed_mud")
        g.set(x + 1, top(x + 1, z + 1), z + 1, "coarse_dirt")
    # the secondary clearing (east), moss and flowers, light falling in
    for x in range(8, 20):
        for z in range(84, 98):
            if math.hypot(x - 14, z - 91) <= 6:
                for y in range(top(x, z) + 1, top(x, z) + 14):
                    if "leaves" in g.get(x, y, z) or "log" in g.get(x, y, z):
                        g.set(x, y, z, "air")
                g.set(x, top(x, z), z, g.pick([("grass_block", 4), ("moss_block", 2)]))
                if r.random() < 0.15:
                    fl.plant(x, top(x, z) + 1, z, g.pick([("poppy", 1), ("cornflower", 1), ("oxeye_daisy", 1), ("short_grass", 3)]))
    light(14, F + 7, 91, 13)
    undergrowth(z0, z1, 260)
    for z in (50, 66, 82, 100):
        light(px(z) + 3, top(px(z), z) + 4, z, 9)
    light(-15, F + 3, 79, 9)
    # the door: a giant fallen trunk across the forest, rocks at its ends, a tangle of roots in the passage
    z = DOORS_Z[1]
    a, b = px(z) - 2, px(z) + 2
    y = F + 3
    for x in range(-W, W + 1):
        if a <= x <= b:
            continue
        g.box(x, top(x, z) + 1, z, x, y + 5, z, "spruce_log[axis=x]" if top(x, z) + 4 >= y + 2 else g.pick(ROCK))
        g.box(x, top(x, z) + 1, z, x, y + 1, z, lambda xx, yy, zz: g.pick(ROCK))
    g.box(a - 1, y + 6, z, b + 1, y + 7, z, "spruce_log[axis=x]")
    g.box(a - 1, y + 1, z, a - 1, y + 5, z, "spruce_log")
    g.box(b + 1, y + 1, z, b + 1, y + 5, z, "spruce_log")
    g.box(a, y + 1, z, b, y + 4, z, lambda xx, yy, zz: "mangrove_roots" if (xx + yy) % 2 else "spruce_log")
    door("foret", (a, y + 1, z), (b, y + 4, z), room="2")
    fl.curtain(a, z, b, z, y + 5, y + 5)
    fl.curtain(-W, z, W, z, y + 8, Y1)
    for x in range(-W, W + 1):
        if not (a - 1 <= x <= b + 1):
            fl.curtain(x, z, x, z, top(x, z) + 1, y + 7)
    # monsters: a pack, stalkers in the bushes, spiders in the dark corners and the ravine
    for (mob, x, z) in (("def_loup", -3, 58), ("def_loup", 3, 60), ("def_loup", 0, 64), ("def_traqueur", 10, 72),
                        ("def_traqueur", -10, 92), ("def_araignee", -15, 80), ("def_araignee", -3, 100), ("def_loup", 14, 91)):
        m.mob(mob, x, z, y=top(x, z) + 1, yaw=180, room="2")
    m.sound(0, F + 8, 60, [("block.azalea_leaves.step", 0.6, 0.8), ("block.wood.break", 0.15, 0.5)], radius=18, every=(100, 220),
            particle={"type": "FALLING_SPORE_BLOSSOM", "count": 3, "spread": (6, 2, 6), "speed": 0, "every": 20})
    m.sound(-15, F + 2, 80, [("entity.wolf.growl", 0.35, 0.7), ("entity.spider.ambient", 0.3, 0.7)], radius=14, every=(160, 300))
    m.sound(10, F + 6, 76, [("entity.fox.screech", 0.2, 0.6), ("block.sweet_berry_bush.place", 0.6, 0.7)], radius=14, every=(200, 380))
    m.sound(14, F + 5, 91, [("entity.parrot.ambient", 0.25, 1.6), ("entity.bee.loop", 0.15, 1.3)], radius=12, every=(140, 260),
            particle={"type": "FIREFLY", "count": 2, "spread": (4, 1.5, 4), "speed": 0, "every": 20})
    m.sound(-2, F + 6, 104, [("entity.polar_bear.warning", 0.18, 0.5), ("block.wood.break", 0.2, 0.6)], radius=16, every=(200, 360))


# ======================================================================== room 3, the lands of the hunt

def tower(x, z, h=9):
    """A watch tower: four legs on stone feet, braces, a deck at h with a rail and a roof, a ladder."""
    y = F + 1
    for dx in (0, 4):
        for dz in (0, 4):
            g.box(x + dx, top(x + dx, z + dz) - 1, z + dz, x + dx, top(x + dx, z + dz), z + dz, "cobblestone")
            g.box(x + dx, top(x + dx, z + dz) + 1, z + dz, x + dx, y + h + 3, z + dz, "spruce_log")
    for k in range(1, 4):
        put(x + k, y + k + 1, z, "spruce_fence")
        put(x + 4 - k, y + k + 1, z + 4, "spruce_fence")
    g.box(x, y + h, z, x + 4, y + h, z + 4, "spruce_planks")
    for dx in range(5):
        for dz in range(5):
            if dx in (0, 4) or dz in (0, 4):
                put(x + dx, y + h + 1, z + dz, "spruce_fence")
    for k in range(4):
        g.box(x - 1 + k, y + h + 4 + k, z - 1 + k, x + 5 - k, y + h + 4 + k, z + 5 - k, "spruce_slab[type=bottom]" if k else "spruce_planks")
    g.set(x + 2, y + h + 3, z + 2, "lantern[hanging=true]")
    g.box(x + 2, top(x + 2, z - 1) + 1, z - 1, x + 2, y + h, z - 1, "ladder[facing=north]")
    g.set(x + 2, y + h + 1, z, "air")
    g.set(x + 2, y + h, z, "spruce_trapdoor[facing=north,half=top,open=false]")


def deck(x, z, w, d, h):
    """An observation deck at h, reached by a stair of planks along its south side (the watchers post here)."""
    base = max(top(x + dx, z + dz) for dx in range(w) for dz in range(d))
    y = base + h
    for dx in (0, w - 1):
        for dz in (0, d - 1):
            g.box(x + dx, top(x + dx, z + dz) + 1, z + dz, x + dx, y, z + dz, "spruce_log")
    g.box(x, y, z, x + w - 1, y, z + d - 1, "spruce_planks")
    for dx in range(w):
        for dz in range(d):
            if (dx in (0, w - 1) or dz == d - 1) and not (dz == 0 and dx in (1, 2)):
                put(x + dx, y + 1, z + dz, "spruce_fence")
    # stairs down from the south edge
    for k in range(1, h + 1):
        sy = y - k + 1
        sz = z - k
        for sx in (x + 1, x + 2):
            g.set(sx, sy, sz, "spruce_stairs[facing=north]")
            g.box(sx, top(sx, sz) + 1, sz, sx, sy - 1, sz, "spruce_planks")
    return y + 1


def pit(x, z):
    """A hunter's pit, plainly shown: a ring of stakes and open trapdoors, two deep, a step to climb out."""
    t = top(x, z)
    for dx in range(-1, 3):
        for dz in range(-1, 3):
            if 0 <= dx <= 1 and 0 <= dz <= 1:
                g.set(x + dx, t, z + dz, "air")
                g.set(x + dx, t - 1, z + dz, "air")
                g.set(x + dx, t - 2, z + dz, g.pick([("coarse_dirt", 2), ("bone_block", 1)]))
            elif r.random() < 0.6:
                put(x + dx, t + 1, z + dz, "spruce_fence")
    g.set(x, t - 1, z, "coarse_dirt")                  # the step
    g.set(x + 1, t, z + 1, "spruce_trapdoor[facing=south,half=top,open=true]")
    bones(x, t - 1, z + 1)


def cage(x, z, broken=True):
    t = top(x, z)
    for dx in range(3):
        for dz in range(3):
            g.set(x + dx, t + 3, z + dz, "spruce_slab[type=bottom]")
            if dx in (0, 2) or dz in (0, 2):
                if broken and r.random() < 0.35:
                    continue
                g.box(x + dx, t + 1, z + dz, x + dx, t + 2, z + dz, "iron_bars")
    bones(x + 1, t + 1, z + 1)


def jaw_trap(x, z):
    t = top(x, z)
    g.set(x, t + 1, z, "iron_trapdoor[facing=north,half=bottom,open=false]")
    put(x + 1, t + 1, z, "iron_chain[axis=x]")


def hunt_lands():
    z0, z1 = 114, 197
    path(z0, z1, 2)
    fl.ground(-W, z0, W, z1, 0, [("grass_block", 1)], where=lambda x, z: False)
    tower(-17, 136)
    tower(13, 166)
    y_a = deck(-14, 152, 4, 4, 3)
    y_b = deck(11, 128, 4, 4, 3)
    y_c = deck(-13, 178, 4, 4, 3)
    # broken palisades on the talus, old fences, cut trunks, mossy stones
    for (xa, za, n) in ((-21, 122, 9), (12, 140, 7), (-20, 168, 6), (14, 186, 8)):
        for k in range(n):
            if r.random() < 0.3:
                continue
            x = xa + k
            if near_path(x, za, 4):
                continue
            hh = r.randint(1, 4)
            g.box(x, top(x, za) + 1, za, x, top(x, za) + hh, za, "spruce_log")
    for (x, z) in ((-10, 132), (8, 150), (-8, 172), (17, 177), (-18, 190)):
        fl.stump(x, z, top(x, z) + 1, r.choice(["spruce", "oak"]), r.choice((1, 2)), fresh=r.random() < 0.5)
    fl.log_pile(15, 147, top(15, 147) + 1, 3, 2, "spruce", axis="z", collapsed=True)
    for _ in range(12):
        x, z = r.randint(-W + 2, W - 2), r.randint(z0 + 4, z1 - 4)
        if not near_path(x, z, 4):
            boulder(x, z, r.choice((1, 2)))
    for (x, z, rx, ry, rz) in ((-18, 117, 3, 3, 3), (18, 132, 3, 4, 4), (-19, 160, 3, 3, 4), (19, 192, 2, 3, 3)):
        outcrop(x, z, rx, ry, rz)
    # the traps, all readable
    for (x, z) in ((-8, 126), (9, 158), (-9, 186)):
        if not near_path(x, z, 3) and not near_path(x + 1, z, 3):
            pit(x, z)
    for (x, z) in ((7, 120), (-6, 146), (8, 174)):
        cage(x, z)
    for (x, z) in ((4, 139), (-5, 164), (6, 190)):
        if not near_path(x, z, 1):
            jaw_trap(x, z)
    for (xa, z) in ((-12, 144), (9, 182)):              # hanging nets between two posts
        for x in (xa, xa + 4):
            g.box(x, top(x, z) + 1, z, x, top(x, z) + 4, z, "spruce_fence")
        g.box(xa, top(xa, z) + 5, z, xa + 4, top(xa, z) + 5, z, "stripped_spruce_log[axis=x]")
        for x in range(xa + 1, xa + 4):
            put(x, top(xa, z) + 4, z, "cobweb")
            if r.random() < 0.5:
                put(x, top(xa, z) + 3, z, "cobweb")
    # trees on the sides only: the middle of the valley stays open
    for _ in range(60):
        x, z = r.randint(-W + 1, W - 1), r.randint(z0 + 3, z1 - 3)
        if abs(x - px(z)) < 9:
            continue
        tree_at(x, z, g.pick([("spruce", 3), ("oak", 2), ("dark_oak", 1), ("birch", 1)]), g.pick([("medium", 3), ("conifer", 2), ("dead", 1), ("small", 2)]))
    for _ in range(12):
        x, z = r.randint(-W + 2, W - 2), r.randint(z0 + 3, z1 - 3)
        if abs(x - px(z)) >= 6:
            fl.bush(x, z, top(x, z) + 1, r.choice(["oak", "spruce"]))
    undergrowth(z0, z1, 220)
    for (dx0, dz0, yy) in ((-14, 152, y_a), (11, 128, y_b), (-13, 178, y_c)):
        for x in range(dx0 - 1, dx0 + 5):
            for z in range(dz0 - 4, dz0 + 5):
                for y in range(yy - 4, yy + 4):
                    b = g.get(x, y, z)
                    if "leaves" in b or (b.endswith("_log") and y > yy - 1) or b.startswith("spruce_log[") and y > yy:
                        g.set(x, y, z, "air")
    for k, z in enumerate(range(120, 196, 12)):
        light(px(z) + (4 if k % 2 else -4), F + 5, z, 10)
    for (x, z) in ((-12, 154), (13, 130), (-11, 180)):
        light(x, F + 7, z, 11)
    # the door: the lodge's courtyard wall, stone posts, a spruce gate
    z = DOORS_Z[2]
    a, b = -2, 2
    for x in range(-W, W + 1):
        if a <= x <= b:
            continue
        g.box(x, top(x, z) + 1, z, x, top(x, z) + 1, z, g.pick([("cobblestone", 2), ("mossy_cobblestone", 2), ("stone_bricks", 1)]))
        g.box(x, top(x, z) + 2, z, x, top(x, z) + 4 + (1 if x % 4 == 0 else 0), z, "spruce_log")
    for x in (a - 2, a - 1, b + 1, b + 2):
        g.box(x, F + 1, z, x, F + 6, z, "stone_bricks" if x in (a - 1, b + 1) else "mossy_stone_bricks")
        g.set(x, F + 7, z, "stone_brick_wall")
    g.set(a - 1, F + 8, z, "lantern")
    g.set(b + 1, F + 8, z, "lantern")
    g.box(a, F + 1, z, b, F + 4, z, lambda x, y, zz: "spruce_planks" if y % 2 else "stripped_spruce_log[axis=x]")
    door("traque", (a, F + 1, z), (b, F + 4, z), room="3")
    fl.curtain(a, z, b, z, F + 5, F + 5)
    fl.curtain(-W, z, W, z, F + 7, Y1)
    for x in range(-W, W + 1):
        if not (a - 2 <= x <= b + 2):
            fl.curtain(x, z, x, z, top(x, z) + 5, F + 7)
    # monsters: boars on the path, watchers on the decks, the fallen stag in the middle, wolves on the flanks
    for (mob, x, z, y) in (("def_sanglier", px(124), 124, None), ("def_sanglier", px(150) + 2, 150, None),
                           ("def_sanglier", px(176) - 2, 176, None), ("def_guetteur", -12, 153, y_a), ("def_guetteur", 12, 129, y_b),
                           ("def_guetteur", -11, 179, y_c), ("def_cerf", px(162), 162, None), ("def_loup", 10, 140, None),
                           ("def_loup", -9, 166, None)):
        m.mob(mob, x, z, y=y if y else top(x, z) + 1, yaw=180, room="3")
    m.sound(0, F + 9, 130, [("item.elytra.flying", 0.2, 1.2), ("block.wood.break", 0.25, 0.7)], radius=22, every=(90, 200))
    m.sound(9, F + 2, 158, [("block.iron_trapdoor.close", 0.35, 0.6), ("block.chain.step", 0.4, 0.6)], radius=14, every=(180, 340))
    m.sound(px(165), F + 2, 165, [("entity.ravager.step", 0.25, 0.6), ("entity.goat.screaming.ambient", 0.15, 0.6)], radius=18, every=(160, 320))
    m.sound(-15, F + 9, 138, [("block.wooden_door.open", 0.2, 0.5), ("block.ladder.step", 0.3, 0.6)], radius=14, every=(200, 380))
    m.sound(0, F + 6, 186, [("item.elytra.flying", 0.18, 1.0), ("entity.wolf.growl", 0.25, 0.6)], radius=20, every=(140, 280),
            particle={"type": "WHITE_ASH", "count": 6, "spread": (8, 3, 8), "speed": 0, "every": 20})


# ======================================================================== room 4, the hunters' lodge

LX0, LX1, LZ0, LZ1 = -14, 14, 210, 244           # walls of the lodge (outside faces)
LF = F + 1                                        # its floor (feet at LF + 1)


def lodge():
    # the courtyard: training posts, hay, a dead tree, lantern posts, grass and gravel
    fl.ground(-W, 199, W, 262, F, TOPS[4])
    for z in range(199, 210):
        for x in range(-3, 4):
            g.set(x, F, z, g.pick([("gravel", 2), ("cobblestone", 2), ("mossy_cobblestone", 1), ("coarse_dirt", 1)]))
    for (x, z) in ((-12, 203), (12, 204)):
        g.box(x, F + 1, z, x, F + 2, z, "spruce_fence")
        g.set(x, F + 3, z, "target")
    for (x, z) in ((-17, 206), (17, 202)):
        g.set(x, F + 1, z, "hay_block")
        put(x + 1, F + 1, z, "hay_block")
    tree_at(-19, 214, "oak", "dead")
    for x in (-5, 5):
        g.box(x, F + 1, 205, x, F + 3, 205, "dark_oak_fence")
        g.set(x, F + 4, 205, "lantern")
    # foundations: a stone base out of the ground, the floor at LF
    for x in range(LX0, LX1 + 1):
        for z in range(LZ0, LZ1 + 1):
            edge = x in (LX0, LX1) or z in (LZ0, LZ1)
            g.box(x, F - 1, z, x, LF, z, g.pick([("cobblestone", 2), ("stone_bricks", 2), ("mossy_cobblestone", 1)]) if edge else "stone")
            if not edge:
                g.set(x, LF, z, "spruce_planks" if (x + 100) % 5 else "stripped_spruce_log[axis=z]")
    # ground floor walls: a stone course, then a spruce frame with dark oak infill, posts standing out every 5
    for y in range(LF + 1, LF + 7):
        for x in range(LX0, LX1 + 1):
            for z in (LZ0, LZ1):
                g.set(x, y, z, wall_block(x, y, z))
        for z in range(LZ0, LZ1 + 1):
            for x in (LX0, LX1):
                g.set(x, y, z, wall_block(z, y, x))
    for x in range(LX0, LX1 + 1, 5):                      # posts standing out of the facades
        for z in (LZ0 - 1, LZ1 + 1):
            g.box(x, LF - 1, z, x, LF - 1, z, "stone_bricks")
            g.box(x, LF, z, x, LF + 6, z, "spruce_log")
    for z in range(LZ0, LZ1 + 1, 6):
        for x in (LX0 - 1, LX1 + 1):
            g.box(x, LF, z, x, LF + 6, z, "spruce_log")
    # windows, framed in stone, set back in the wall
    for x in range(LX0 + 3, LX1 - 1, 5):
        for z in (LZ0, LZ1):
            if abs(x) <= 3:
                continue
            g.box(x, LF + 3, z, x + 1, LF + 4, z, "glass_pane[east=true,west=true]")
            g.box(x, LF + 2, z, x + 1, LF + 2, z, "stone_brick_slab[type=top]")
    for z in range(LZ0 + 4, LZ1 - 1, 6):
        for x in (LX0, LX1):
            g.box(x, LF + 3, z, x, LF + 4, z + 1, "glass_pane[north=true,south=true]")
    # the jettied upper storey, a gallery on the south facade
    for y in range(LF + 7, LF + 11):
        for x in range(LX0 - 1, LX1 + 2):
            for z in (LZ0 - 1, LZ1 + 1):
                g.set(x, y, z, upper_block(x, y))
        for z in range(LZ0 - 1, LZ1 + 2):
            for x in (LX0 - 1, LX1 + 1):
                g.set(x, y, z, upper_block(z, y))
    g.box(LX0 - 1, LF + 7, LZ0 - 1, LX1 + 1, LF + 7, LZ1 + 1, "stripped_spruce_log[axis=x]", where=lambda b: b != "air")
    for x in range(LX0 + 2, LX1 - 1, 4):
        g.box(x, LF + 8, LZ0 - 1, x + 1, LF + 9, LZ0 - 1, "glass_pane[east=true,west=true]")
        g.box(x, LF + 8, LZ1 + 1, x + 1, LF + 9, LZ1 + 1, "glass_pane[east=true,west=true]")
    for x in range(-8, 9):                                 # the gallery over the porch
        g.set(x, LF + 7, LZ0 - 2, "spruce_slab[type=top]")
        g.set(x, LF + 7, LZ0 - 3, "spruce_slab[type=top]")
        g.set(x, LF + 8, LZ0 - 4, "spruce_fence") if False else g.set(x, LF + 8, LZ0 - 3, "spruce_fence")
    for x in (-8, -4, 4, 8):
        g.set(x, LF + 6, LZ0 - 2, "spruce_stairs[facing=north,half=top]")
    # the roof: steep, overhanging, dark oak stairs in steps, gables of stripped spruce, a broken corner
    ridge_x = 0
    for k in range(0, 9):
        y = LF + 11 + k
        xa, xb = LX0 - 3 + 2 * k, LX1 + 3 - 2 * k
        if xa > xb:
            break
        for z in range(LZ0 - 3, LZ1 + 4):
            g.set(xa, y, z, "dark_oak_stairs[facing=east]")
            g.set(xb, y, z, "dark_oak_stairs[facing=west]")
            if xb - xa > 2:
                g.set(xa + 1, y, z, "dark_oak_planks")
                g.set(xb - 1, y, z, "dark_oak_planks")
                g.set(xa + 1, y + 1, z, "dark_oak_slab[type=bottom]")
                g.set(xb - 1, y + 1, z, "dark_oak_slab[type=bottom]")
        for z in (LZ0 - 1, LZ1 + 1):                       # gables
            for x in range(xa + 2, xb - 1):
                g.set(x, y, z, "stripped_spruce_log" if x % 3 == 0 else "spruce_planks")
    g.box(-1, LF + 16, LZ0 - 1, 1, LF + 17, LZ0 - 1, "glass_pane[east=true,west=true]")
    g.box(-1, LF + 18, LZ0 - 4, 1, LF + 18, LZ0 - 4, "dark_oak_slab[type=bottom]") if False else None
    # the broken corner (north-east): a hole in the roof, cracked walls, missing planks, leaves and vines coming in
    for x in range(6, 14):
        for z in range(232, 245):
            if math.hypot(x - 10, z - 238) < 4.2:
                for y in range(LF + 11, LF + 20):
                    if g.get(x, y, z) != "air":
                        g.set(x, y, z, "air")
    for (x, y, z) in ((LX1, LF + 3, 236), (LX1, LF + 4, 237), (LX1, LF + 5, 238), (12, LF + 6, LZ1)):
        g.set(x, y, z, "air")
    for y in range(LF + 1, LF + 7):
        for z in range(232, 244):
            if g.get(LX1, y, z) in ("stone_bricks", "cobblestone"):
                g.set(LX1, y, z, "cracked_stone_bricks")
    for _ in range(14):
        x, z = r.randint(7, 13), r.randint(233, 243)
        put(x, LF + 1, z, g.pick([("moss_carpet", 3), ("azalea_leaves[persistent=true]", 1), ("short_grass", 0)]))
    for z in range(234, 242):
        put(LX1 - 1, LF + 6, z, "vine[east=true]")
    # chimney (east), its fireplace inside
    g.box(LX1 + 1, F, 224, LX1 + 3, LF + 22, 228, lambda x, y, z: "cobblestone" if (x + y + z) % 4 else "stone_bricks")
    g.set(LX1 + 2, LF + 22, 226, "campfire[lit=true]")
    g.box(LX1, LF + 1, 225, LX1, LF + 3, 227, "air")
    g.set(LX1, LF + 1, 226, "campfire[lit=true]")
    g.box(LX1 - 1, LF + 4, 224, LX1 - 1, LF + 4, 228, "stone_brick_stairs[facing=west,half=top]")
    g.set(LX1 - 1, LF + 5, 226, "chiseled_stone_bricks")
    g.item_display(LX1 - 0.95, LF + 6.2, 226.5, "skeleton_skull", yaw=90, scale=1.6)
    # the round tower (south-west corner), its conical roof
    tx, tz = LX0 - 2, LZ0 - 2
    for y in range(F - 1, LF + 16):
        for x in range(tx - 4, tx + 5):
            for z in range(tz - 4, tz + 5):
                d = math.hypot(x - tx, z - tz)
                if 2.6 < d <= 3.7:
                    g.set(x, y, z, "stone_bricks" if y < LF + 7 or y % 5 else "tuff_bricks")
                elif d <= 2.6 and y in (LF, LF + 8):
                    g.set(x, y, z, "spruce_planks")
    for k in range(7):
        for x in range(tx - 5, tx + 6):
            for z in range(tz - 5, tz + 6):
                d = math.hypot(x - tx, z - tz)
                if 4.6 - k * 0.75 - 1 < d <= 4.6 - k * 0.75:
                    g.set(x, LF + 16 + k, z, "spruce_planks" if k < 6 else "spruce_slab[type=bottom]")
    for y in (LF + 4, LF + 11):
        g.set(tx - 3, y, tz, "glass_pane[north=true,south=true]")
        g.set(tx, y, tz - 3, "glass_pane[east=true,west=true]")
    g.set(tx, LF + 22, tz, "lightning_rod")
    # the porch: steps up, a roof on posts, the main door opening
    for x in range(-3, 4):
        g.set(x, F + 1, LZ0 - 1, "stone_brick_stairs[facing=north]")
        g.set(x, F + 1, LZ0 - 2, "stone_brick_slab[type=bottom]") if False else None
    for x in (-4, 4):
        g.box(x, F + 1, LZ0 - 4, x, LF + 5, LZ0 - 4, "spruce_log")
    g.box(-5, LF + 6, LZ0 - 5, 5, LF + 6, LZ0 - 1, "spruce_slab[type=bottom]")
    g.box(-2, LF + 1, LZ0, 2, LF + 4, LZ0, "air")
    g.box(-3, LF + 5, LZ0, 3, LF + 5, LZ0, "stripped_spruce_log[axis=x]")
    g.set(-3, LF + 4, LZ0 - 1, "lantern") if False else g.set(-3, LF + 5, LZ0 - 1, "lantern[hanging=true]") if False else None
    g.set(-4, LF + 4, LZ0 - 5, "lantern") if False else None
    g.set(0, LF + 6, LZ0 - 3, "air")
    g.set(0, LF + 5, LZ0 - 3, "lantern[hanging=true]")
    g.set(0, LF + 6, LZ0 - 3, "iron_chain")
    for x in (-2, 2):
        g.set(x, LF + 5, LZ0 - 1, "brown_wall_banner[facing=north]")
    great_hall()
    trophy_gallery()
    back_way()


def wall_block(a, y, b):
    if y == LF + 1:
        return g.pick([("cobblestone", 2), ("stone_bricks", 2), ("mossy_cobblestone", 1)])
    if y == LF + 6:
        return "stripped_spruce_log[axis=x]"
    return "dark_oak_planks" if (a + 100) % 5 else "spruce_log"


def upper_block(a, y):
    return "stripped_spruce_log" if (a + 100) % 4 == 0 else ("spruce_planks" if y < LF + 10 else "stripped_spruce_log[axis=x]")


def great_hall():
    x0, x1, z0, z1 = LX0 + 1, LX1 - 1, LZ0 + 1, LZ1 - 1
    # open to the roof, beams across, chandeliers
    for z in range(z0 + 2, z1, 5):
        g.box(LX0, LF + 11, z, LX1, LF + 11, z, "stripped_spruce_log[axis=x]")
        g.box(0, LF + 9, z, 0, LF + 10, z, "iron_chain")
        g.set(0, LF + 8, z, "lantern[hanging=true]") if z % 2 else g.set(0, LF + 8, z, "soul_lantern[hanging=true]")
    # long tables with benches (one overturned), carpets
    for zt in (218, 228):
        for x in range(-6, 7, 2):
            pass
        g.box(-2, LF + 1, zt, -2, LF + 1, zt + 6, "dark_oak_fence")
        g.box(-2, LF + 2, zt, -2, LF + 2, zt + 6, "dark_oak_pressure_plate")
        g.box(-3, LF + 1, zt, -3, LF + 1, zt + 6, "spruce_stairs[facing=east]")
        g.box(-1, LF + 1, zt, -1, LF + 1, zt + 6, "spruce_stairs[facing=west]")
    g.set(-2, LF + 2, 220, "candle[candles=3,lit=true]")
    g.set(-2, LF + 2, 231, "candle[candles=2,lit=true]")
    g.item_display(-1.5, LF + 2.05, 223.5, "filled_map", yaw=0, roll=0, scale=0.7)
    for (x, z) in ((5, 224), (6, 225)):
        g.set(x, LF + 1, z, "spruce_trapdoor[facing=north,half=bottom,open=true]")
    g.set(5, LF + 1, 226, "spruce_stairs[facing=south]")
    for z in range(z0 + 1, z1, 1):
        if 214 <= z <= 240:
            g.set(-6, LF + 1, z, "red_carpet") if g.get(-6, LF + 1, z) == "air" else None
    # trophies along the walls (skulls, horns, bones), weapon racks, banners, shelves of barrels, a preparation table
    for (z, item) in ((216, "skeleton_skull"), (222, "goat_horn"), (234, "skeleton_skull"), (240, "goat_horn")):
        g.item_display(LX0 + 1.05, LF + 4.5, z + 0.5, item, yaw=-90, scale=1.3)
    for (z, item) in ((216, "goat_horn"), (232, "bone"), (240, "skeleton_skull")):
        g.item_display(LX1 - 0.05, LF + 4.5, z + 0.5, item, yaw=90, scale=1.3)
    for z in (219, 237):
        g.set(LX0 + 1, LF + 3, z, "green_wall_banner[facing=east]")
        g.set(LX1 - 1, LF + 3, z, "brown_wall_banner[facing=west]")
    for z in (213, 241):
        bow_rack(LX0 + 1, LF + 1, z, -90)
    for z in range(212, 216):
        g.set(LX1 - 1, LF + 1, z, "barrel[facing=west]")
        g.set(LX1 - 1, LF + 2, z, "barrel[facing=west]") if z % 2 else None
    g.set(LX1 - 2, LF + 1, 242, "smithing_table")
    g.set(LX1 - 3, LF + 1, 242, "crafting_table")
    g.set(LX1 - 4, LF + 1, 242, "chest[facing=north]")
    for x in range(x0, x1 + 1, 7):
        light(x, LF + 6, 222, 12)
        light(x, LF + 6, 236, 12)
    m.mob("def_briseur", 0, 236, y=LF + 1, yaw=180, room="4")
    m.mob("def_guetteur", -9, 241, y=LF + 1, yaw=180, room="4")
    m.mob("def_araignee", 9, 214, y=LF + 1, yaw=180, room="4")
    m.sound(LX1 - 1, LF + 2, 226, [("block.fire.ambient", 0.7, 1.0), ("block.campfire.crackle", 0.8, 0.9)], radius=12, every=(30, 80),
            particle={"type": "CAMPFIRE_COSY_SMOKE", "count": 1, "spread": (0.2, 0.2, 0.2), "speed": 0.01, "every": 20})
    m.sound(0, LF + 10, 228, [("block.wooden_trapdoor.open", 0.2, 0.5), ("block.wood.step", 0.3, 0.5), ("block.chain.step", 0.3, 0.7)],
            radius=16, every=(140, 300))
    m.sound(10, LF + 6, 238, [("item.elytra.flying", 0.2, 1.1), ("block.azalea_leaves.step", 0.5, 0.8)], radius=10, every=(120, 240))
    m.sound(0, LF + 2, 242, [("entity.ravager.roar", 0.12, 0.5), ("block.deepslate.break", 0.4, 0.5)], radius=10, every=(220, 400))


def trophy_gallery():
    """West annex: the trophies of the greatest creatures hunted, some torn down; it tells what waits beyond."""
    ax0, ax1, az0, az1 = LX0 - 8, LX0, 220, 236
    for x in range(ax0, ax1 + 1):
        for z in range(az0, az1 + 1):
            edge = x == ax0 or z in (az0, az1)
            g.box(x, F - 1, z, x, LF, z, "stone_bricks" if edge else "stone")
            if not edge and x != ax1:
                g.set(x, LF, z, "polished_andesite" if (x + z) % 2 else "spruce_planks")
            if edge and x != ax1:
                g.box(x, LF + 1, z, x, LF + 6, z, lambda xx, y, zz: "stone_bricks" if y < LF + 3 else ("spruce_log" if (zz % 4 == 0 or xx % 4 == 0) else "dark_oak_planks"))
    for k in range(4):                                        # lean-to roof towards the west
        g.box(ax0 - 1 + k, LF + 7 + k, az0 - 1, ax0 - 1 + k, LF + 7 + k, az1 + 1, "dark_oak_stairs[facing=east]")
        g.box(ax0 + k, LF + 7 + k, az0, ax1 - 1, LF + 7 + k, az1, "dark_oak_planks") if k == 3 else None
    g.box(ax0, LF + 7, az0, ax1 - 1, LF + 9, az1, "dark_oak_planks", where=lambda b: b == "air")
    g.box(ax0 + 1, LF + 7, az0 + 1, ax1 - 1, LF + 7, az1 - 1, "air")
    g.box(ax1, LF + 1, 226, ax1, LF + 4, 230, "air")                # archway from the hall
    g.box(ax1, LF + 5, 226, ax1, LF + 5, 230, "stone_brick_stairs[facing=west,half=top]")
    # the trophies: skulls and horns on plinths, a giant horned skull of bone, shields, torn banners, broken ones
    for z in range(az0 + 2, az1 - 1, 3):
        g.set(ax0 + 1, LF + 1, z, "polished_andesite")
        g.set(ax0 + 1, LF + 2, z, "chiseled_stone_bricks")
        g.item_display(ax0 + 1.5, LF + 3.3, z + 0.5, r.choice(["skeleton_skull", "goat_horn", "bone"]), yaw=-90, scale=1.2)
    g.box(ax0 + 1, LF + 4, 227, ax0 + 1, LF + 5, 229, "bone_block")
    g.set(ax0 + 1, LF + 6, 226, "bone_block")
    g.set(ax0 + 1, LF + 6, 230, "bone_block")
    g.item_display(ax0 + 1.6, LF + 4.5, 228.5, "skeleton_skull", yaw=-90, scale=2.2)
    for z in (222, 234):
        g.item_display(ax0 + 1.1, LF + 4.2, z + 0.5, "shield", yaw=-90, scale=1.0)
        g.set(ax1 - 1, LF + 4, z, "gray_wall_banner[facing=west]") if False else None
    for (x, z) in ((ax0 + 3, 224), (ax0 + 4, 232)):              # torn down: plinth knocked over, bones
        g.set(x, LF + 1, z, "chiseled_stone_bricks")
        bones(x + 1, LF + 1, z)
    light(ax0 + 4, LF + 5, 228, 11)
    m.mob("def_guetteur", ax0 + 4, 229, y=LF + 1, yaw=90, room="4")


def back_way():
    """North door of the hall, a ruined arcade down to the arena gate."""
    g.box(-2, LF + 1, LZ1, 2, LF + 4, LZ1, "air")
    g.set(0, F + 1, LZ1 + 1, "air")
    for z in range(LZ1 + 1, DOORS_Z[3]):
        for x in range(-4, 5):
            g.set(x, F, z, g.pick([("stone_bricks", 3), ("cracked_stone_bricks", 1), ("mossy_stone_bricks", 1)]) if abs(x) <= 2 else "stone")
            if abs(x) in (3, 4):
                h = 5 if (z % 4 == 0 or abs(x) == 4) else 0
                if abs(x) == 3 and z % 4:
                    continue
                g.box(x, F + 1, z, x, F + 5 + (1 if z % 4 == 0 else 0) - (r.randint(0, 2) if z % 7 == 3 else 0), z,
                      lambda xx, y, zz: g.pick([("stone_bricks", 3), ("mossy_stone_bricks", 1), ("cracked_stone_bricks", 1)]))
        if z % 4 == 0:
            g.box(-3, F + 6, z, 3, F + 6, z, "stone_brick_slab[type=bottom]") if z % 8 == 0 else None
    g.box(-2, LF, LZ1 + 1, 2, LF, LZ1 + 1, "stone_brick_stairs[facing=south]")
    g.set(0, LF + 1, LZ1 + 1, "air")
    for z in range(LZ1 + 3, DOORS_Z[3], 4):
        g.set(3, F + 4, z, "soul_lantern") if g.get(3, F + 3, z) != "air" else None
        light(0, F + 5, z, 9)
    fl.curtain(-4, LZ1 + 1, -4, DOORS_Z[3], F + 1, Y1)
    fl.curtain(4, LZ1 + 1, 4, DOORS_Z[3], F + 1, Y1)
    # the lodge itself bounds room 4: everything north of it but the arcade is closed
    for x in range(-W, W + 1):
        if -4 <= x <= 4:
            continue
        fl.curtain(x, LZ1 + 1, x, LZ1 + 1, F + 1, Y1)


# ======================================================================== room 5, the skeleton's sanctuary

def octo(dx, dz, a):
    return max(abs(dx), abs(dz), (abs(dx) + abs(dz)) / 1.42) <= a + 0.5


def arena():
    zg = DOORS_Z[3]
    # floor of the whole arena area, then the octagon drawn as a target
    for x in range(-W - 6, W + 7):
        for z in range(zg, Z1 - 6):
            dx, dz = x - CX, z - CZ
            if octo(dx, dz, APO + 6):
                g.set(x, F, z, "polished_deepslate")
                g.box(x, F + 1, z, x, F + 22, z, "air")
                g.box(x, F - 3, z, x, F - 1, z, "deepslate")
    for x in range(CX - APO, CX + APO + 1):
        for z in range(CZ - APO, CZ + APO + 1):
            dx, dz = x - CX, z - CZ
            if not octo(dx, dz, APO):
                continue
            d = math.hypot(dx, dz)
            ring = int(d) // 3
            ang = math.degrees(math.atan2(dz, dx)) % 45
            if d < 1.5:
                b = "chiseled_tuff_bricks"
            elif abs(d - round(d / 3) * 3) < 0.5 and d < 15.5:
                b = "polished_deepslate" if ring % 2 else "deepslate_tiles"
            elif (ang < 2.5 or ang > 42.5) and d > 2:
                b = "polished_andesite"
            elif d > 14.5:
                b = g.pick([("mossy_stone_bricks", 2), ("stone_bricks", 2), ("cracked_stone_bricks", 1)])
            else:
                b = g.pick([("stone_bricks", 4), ("tuff_bricks", 3), ("andesite", 1), ("cracked_stone_bricks", 1)])
            g.set(x, F, z, b)
    # the boss' four columns (gameplay of the Ossacorne): 2 x 2, 5 high, statues' plinths gone dark
    for (cx, cz) in COLUMNS:
        for dx in range(2):
            for dz in range(2):
                for y in range(F + 1, F + 6):
                    b = "chiseled_tuff_bricks" if y == F + 1 else ("polished_deepslate" if y != F + 4 else "tuff_bricks")
                    g.set(cx + dx, y, cz + dz, b)
        g.set(cx, F + 6, cz, "skeleton_skull[rotation=0]")
        g.set(cx + 1, F + 6, cz + 1, "soul_lantern")
    # the walls: a solid ring, pillars standing out, niches, arches, broken parts, ledges of trophies
    for x in range(CX - APO - 6, CX + APO + 7):
        for z in range(CZ - APO - 6, CZ + APO + 7):
            dx, dz = x - CX, z - CZ
            if octo(dx, dz, APO):
                continue
            inner = octo(dx, dz, APO + 1)
            outer = octo(dx, dz, APO + 4)
            if not outer:
                continue
            h = 13 + round(2 * fl.n(x * 0.3, 2, z * 0.3))
            if inner:                                   # a low border ring: one step of tuff, readable limit
                g.set(x, F + 1, z, "polished_tuff")
                continue
            for y in range(F + 1, F + h):
                g.set(x, y, z, arena_wall(x, y, z, dx, dz))
    # pillars standing into the border every ~45 degrees, niches between them
    for k in range(16):
        a = math.radians(11.25 + 22.5 * k)
        rr = APO + 2.2
        x, z = round(CX + rr * math.cos(a)), round(CZ + rr * math.sin(a))
        if abs(x - CX) <= 3 and z < CZ:
            continue                                     # the gate
        if k % 2 == 0:
            g.box(x, F + 1, z, x, F + 14, z, lambda xx, y, zz: "chiseled_deepslate" if y in (F + 1, F + 14) else "polished_deepslate")
            g.set(x, F + 9, z, "soul_lantern") if False else None
        else:
            # a niche: carved into the wall, a trophy on a ledge, some empty or broken
            for y in range(F + 3, F + 6):
                g.set(x, y, z, "air")
            g.set(x, F + 2, z, "polished_deepslate_slab[type=top]")
            item = r.choice(["skeleton_skull", "bone", "goat_horn", None])
            if item:
                g.item_display(x + 0.5, F + 3.3, z + 0.5, item, yaw=math.degrees(-a) + 90, scale=1.1)
    # arches over the border between pillars (an arcade round the arena)
    for x in range(CX - APO - 3, CX + APO + 4):
        for z in range(CZ - APO - 3, CZ + APO + 4):
            dx, dz = x - CX, z - CZ
            if octo(dx, dz, APO + 1) and not octo(dx, dz, APO):
                g.set(x, F + 11, z, "deepslate_tile_slab[type=top]") if (x + z) % 3 else g.set(x, F + 11, z, "polished_deepslate_stairs[half=top]")
    # the gate (south), its portcullis is the door of room 4
    zg0 = CZ - APO - 5
    for x in range(-2, 3):
        for z in range(zg0, CZ - APO + 1):
            g.set(x, F, z, "deepslate_tiles")
            g.box(x, F + 1, z, x, F + 5, z, "air")
    for x in (-3, 3):
        g.box(x, F + 1, zg0, x, F + 8, zg0, "polished_deepslate")
    g.box(-3, F + 6, zg0, 3, F + 8, zg0, "deepslate_bricks")
    g.box(-2, F + 6, zg0, 2, F + 6, zg0, "polished_deepslate_stairs[facing=north,half=top]")
    g.box(-2, F + 1, zg, 2, F + 5, zg, "iron_bars[east=true,west=true]")
    door("pavillon", (-2, F + 1, zg), (2, F + 5, zg), room="4")
    for z in range(zg + 1, zg0):
        for x in range(-2, 3):
            g.set(x, F, z, "deepslate_tiles")
            g.box(x, F + 1, z, x, F + 5, z, "air")
        for x in (-3, 3):
            g.box(x, F + 1, z, x, F + 7, z, "deepslate_bricks")
        g.box(-2, F + 6, z, 2, F + 7, z, "deepslate_bricks")
    g.set(-2, F + 4, zg0 + 1, "soul_lantern[hanging=true]") if False else None
    # the throne at the back (north) under a great skull, banners, chains, cages
    tz = CZ + APO + 1
    for x in range(-4, 5):
        for z in range(tz, tz + 5):
            g.box(x, F + 1, z, x, F + 1, z, "polished_blackstone")
            g.box(x, F + 2, z, x, F + 14, z, "air")
    g.box(-2, F + 2, tz + 2, 2, F + 2, tz + 3, "polished_blackstone")
    g.set(0, F + 3, tz + 2, "polished_blackstone_stairs[facing=south]")
    g.box(-1, F + 3, tz + 2, -1, F + 4, tz + 2, "polished_blackstone_wall")
    g.box(1, F + 3, tz + 2, 1, F + 4, tz + 2, "polished_blackstone_wall")
    g.box(-1, F + 3, tz + 3, 1, F + 6, tz + 3, "polished_blackstone_bricks")
    g.set(0, F + 7, tz + 3, "chiseled_polished_blackstone")
    for x in range(-4, 5):                                # the skull: bone and deepslate, two dark orbits, horns
        for y in range(F + 8, F + 14):
            dxs, dys = x / 4.2, (y - (F + 11)) / 3.2
            if dxs * dxs + dys * dys <= 1:
                g.set(x, y, tz + 4, "bone_block[axis=y]" if not (y in (F + 11, F + 12) and abs(x) == 2) else "polished_blackstone")
    for x in range(-2, 3):
        g.set(x, F + 8, tz + 3, "bone_block[axis=x]" if x % 2 == 0 else "polished_blackstone")
    for sx in (-1, 1):
        for k, (dx, dy) in enumerate(((5, 12), (6, 13), (7, 13), (8, 12), (8, 11))):
            g.set(sx * dx, F + dy, tz + 4, "stripped_dark_oak_log[axis=x]" if k < 3 else "stripped_dark_oak_log")
    for x in (-4, 4):
        g.box(x, F + 2, tz, x, F + 7, tz, "polished_deepslate")
        g.set(x, F + 8, tz, "soul_lantern")
        g.set(x + (1 if x < 0 else -1), F + 6, tz, "gray_wall_banner[facing=south]")
    # broken columns and fallen statues of hunters out of the floor, along the walls
    for k, a in enumerate((60, 120, 200, 340)):
        rr = APO + 3
        x, z = round(CX + rr * math.cos(math.radians(a))), round(CZ + rr * math.sin(math.radians(a)))
        g.box(x, F + 1, z, x, F + 3 + k % 2, z, "cracked_stone_bricks")
    # light: soul lanterns on chains, light inside the walls, a cold glow
    for k in range(8):
        a = math.radians(22.5 + 45 * k)
        x, z = round(CX + (APO + 1.5) * math.cos(a)), round(CZ + (APO + 1.5) * math.sin(a))
        if abs(x) <= 3 and z < CZ:
            continue
        light(x, F + 8, z, 13)
        put(x, F + 10, z, "iron_chain")
        put(x, F + 9, z, "soul_lantern[hanging=true]")
    for x in range(CX - 12, CX + 13, 8):
        for z in range(CZ - 12, CZ + 13, 8):
            light(x, F + 12, z, 9)
    # nothing above the walls: invisible walls round the arena up to the sky
    for x in range(CX - APO - 6, CX + APO + 7):
        for z in range(CZ - APO - 6, CZ + APO + 7):
            dx, dz = x - CX, z - CZ
            if octo(dx, dz, APO + 4) and not octo(dx, dz, APO + 3) and not (abs(x) <= 3 and z < CZ):
                fl.curtain(x, z, x, z, F + 1, Y1)
    m.mob("ossacorne", BOSS[0], BOSS[1], y=F + 1, yaw=180, room="5")
    m.objectives = ["ossacorne"]
    m.sound(CX, F + 4, CZ, [("ambient.soul_sand_valley.mood", 0.35, 0.8), ("block.deepslate.break", 0.3, 0.4),
                            ("entity.skeleton.step", 0.4, 0.5)], radius=24, every=(160, 320),
            particle={"type": "ASH", "count": 10, "spread": (12, 4, 12), "speed": 0, "every": 20})
    m.sound(0, F + 6, CZ + APO + 3, [("block.bone_block.break", 0.4, 0.6), ("entity.skeleton_horse.ambient", 0.2, 0.5)],
            radius=18, every=(200, 360), particle={"type": "SOUL", "count": 2, "spread": (2, 2, 0.5), "speed": 0.01, "every": 20})
    m.sound(0, F + 3, zg + 2, [("block.chain.step", 0.4, 0.6), ("ambient.basalt_deltas.mood", 0.2, 0.6)], radius=8, every=(200, 360))


def arena_wall(x, y, z, dx, dz):
    if y == F + 1:
        return g.pick([("cobbled_deepslate", 2), ("tuff", 1)])
    if y in (F + 7, F + 12):
        return "polished_deepslate"
    return g.pick([("deepslate_bricks", 5), ("cracked_deepslate_bricks", 1), ("deepslate_tiles", 2), ("tuff_bricks", 2), ("mossy_stone_bricks", 1)])


# ======================================================================== checks, main

def check():
    errors = g.check_basic()
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
    for s in m.spawns:
        x, y, z = int(math.floor(s["at"][0])), int(s["at"][1]), int(math.floor(s["at"][2]))
        if g.get(x, y, z) != "air" and not gridlib.passable(g.get(x, y, z)):
            errors.append(f"{s['mob']} spawns in {g.get(x, y, z)} at {x} {y} {z}")
        elif (x, y, z) not in seen and not any((x + a, y, z + b) in seen for a in (-1, 0, 1) for b in (-1, 0, 1)):
            errors.append(f"{s['mob']} at {x} {y} {z} cannot be walked to")
    for x in range(CX - APO, CX + APO + 1):
        for z in range(CZ - APO, CZ + APO + 1):
            if not octo(x - CX, z - CZ, APO) or any(c[0] <= x <= c[0] + 1 and c[1] <= z <= c[1] + 1 for c in COLUMNS):
                continue
            if any(g.get(x, y, z) not in ("air",) and not g.get(x, y, z).startswith("light") for y in (F + 1, F + 2, F + 3)):
                errors.append(f"the arena floor is not free at {x} {z}: {[g.get(x, y, z) for y in (F + 1, F + 2, F + 3)]}")
                break
    return errors


ground()
curtains()
way_in()
camp()
forest()
hunt_lands()
lodge()
arena()
m.textures = "rpg"
m.biome = "forest"           # RPG grass and leaves painted in that colour, biome set on the map
m.ybox = (Y0, Y1)


# Troop roles (2026-11-13): about twice the monsters of the first version, half of them with a role (gelées that
# leap and split, fast pests, brutes, spitters, snipers, kamikazes, supports), placed next to those above.
REINFORCE = {
    "1": ['def_louveteau', 'def_louveteau', 'def_braconnier'],
    "2": ['def_gelee', 'def_gelee', 'def_louveteau', 'def_louveteau', 'def_louveteau', 'def_braconnier', 'def_braconnier', 'def_chamane'],
    "3": ['def_braconnier', 'def_braconnier', 'def_gelee', 'def_gelee', 'def_gelee', 'def_louveteau', 'def_louveteau', 'def_louveteau', 'def_chamane'],
    "4": ['def_louveteau', 'def_louveteau', 'def_braconnier', 'def_chamane'],
}


if __name__ == "__main__":
    args = sys.argv[1:]
    maplib.reinforce(m, g, REINFORCE, seed=105)
    errs = check()
    for e in errs[:50]:
        print("CHECK:", e)
    print(f"{len(errs)} problem(s), {len(g.over)} blocks changed, {len(g.entities)} entities, {len(m.spawns)} monsters, "
          f"{len(m.ambience)} ambience emitters")
    if "--preview" in args:
        out = args[args.index("--preview") + 1]
        g.png_slice(os.path.join(out, "def_ciel.png"), top_down=True, k=2)
        g.png_slice(os.path.join(out, "def_coupe_x0.png"), x=0, k=2)
    if "--check" in args or "--preview" in args:
        sys.exit(1 if errs else 0)
    if errs:
        sys.exit("checks failed: nothing sent")
    if "--edits" in args:
        maplib.write_edits(m, args[0] if args and not args[0].startswith("--") else "labs", "defenseur")
        sys.exit(0)
    g.commands(m)
    print(f"{len(m.cmds)} commands")
    maplib.run(m, args[0] if args and not args[0].startswith("--") else "labs", "defenseur")
