#!/usr/bin/env python3
"""Map of the forester's dungeon, La Forêt des Anciens, modelled in memory (gridlib, flora), checked, then built by
console commands with the RPG look of the dungeons.

An ancestral forest once worked by woodcutters and gone wild, played from south to north (+z), ground at y 63, wood
everywhere (oak and birch at the start, spruce and dark oak in the depths, pale oak and a pocket of cherry at the end):
  - the Forest Gate (start at 0, 64, -9): two massive trunks and a span of branches over the way, roots, stumps;
  - room 1, the Old Forest Track: a winding track, trees of every size, fallen trunks, stumps, piles of logs, a lean-to,
    old fences and signs; woodcutters and stump spiders. Door: a palisade of logs;
  - room 2, the Woodcutters' Ground: a clear-cut full of stumps (fresh and old), felled trees, stacks of logs by kind
    (tidy or slid down), plank platforms, shelters, piles of branches, tracks of mud; log golems join;
  - room 3, the Great Clearing (a colossal oak at its edge, the landmark seen from far) and the Old Sawmill (a long
    open timber hall under a big roof, log stores, a loading deck, a water wheel on its stream);
  - room 4, the Deep Forest (2 x 2 spruces and dark oaks, a canopy almost closed, hills, podzol, mossy rocks) and the
    Graveyard of Giants (monumental fallen trunks, dead trees, giant hollow stumps);
  - room 5, the Sanctuary of Roots (arches of giant roots over the way) and the Ancient Forest (colossal trees, a hollow
    stump, a circular clearing, a cherry pocket);
  - the final clearing: 38 x 38 flat and open, ringed by colossal trees, roots, stumps and fallen trunks; the boss is
    not chosen yet: the Stump Guardian (mini-boss) holds it meanwhile.

Usage: gen_sylviculteur.py [server] [--check] [--preview DIR]     (labs by default)
"""
import math
import os
import sys

import flora as floralib
import gridlib
import maplib

F = 63
X0, X1, Y0, Y1, Z0, Z1 = -48, 48, 40, 130, -34, 400
BANDS = ((40, 56, "stone"), (57, 62, "dirt"), (63, 63, "grass_block"))
AR = (-19, 18, 344, 381)                   # the final clearing (x0, x1, z0, z1)
W = 24                                     # half width of the playable ground (curtains at +-W)
g = gridlib.Model((X0, X1, Y0, Y1, Z0, Z1), BANDS, seed=4242)
r = g.r
fl = floralib.Flora(g)
m = maplib.Map()
m.box = (X0, Z0, X1, Z1)
_door = m.door


def door(id, frm, to, room):
    g.doors.append((tuple(min(a, b) for a, b in zip(frm, to)), tuple(max(a, b) for a, b in zip(frm, to))))
    _door(id, frm, to, room=room)


def path_x(z):
    """Middle of the winding track."""
    return round(4 * math.sin(z * 0.045) + 2 * math.sin(z * 0.13))


def near_path(x, z, w=3):
    return abs(x - path_x(z)) <= w


def light(x, y, z, level=10):
    if g.get(x, y, z) == "air":
        g.set(x, y, z, f"light[level={level}]")


def scatter(z0, z1, n, fn, avoid=None, margin=2):
    """n draws of (x, z) in the zone, away from the track (and an avoid test), each passed to fn."""
    for _ in range(n):
        x, z = r.randint(-W + margin, W - margin), r.randint(z0, z1)
        if near_path(x, z, 4) or (avoid and avoid(x, z)):
            continue
        fn(x, z)


def flowers_and_ferns(z0, z1, n, table):
    scatter(z0, z1, n, lambda x, z: fl.plant(x, F + 1, z, g.pick(table)), margin=1)


FOREST_FLOOR = [("grass_block", 5), ("podzol", 3), ("coarse_dirt", 1), ("moss_block", 1)]
UNDERGROWTH = [("fern", 5), ("short_grass", 5), ("large_fern", 2), ("dead_bush", 1), ("moss_carpet", 2)]


def track(z0, z1, mats, width=2):
    for z in range(z0, z1 + 1):
        c = path_x(z)
        w = width + (1 if math.sin(z * 0.3) > 0.6 else 0)
        for x in range(c - w, c + w + 1):
            g.set(x, F, z, g.pick(mats))


def palisade(z, gap=3):
    """A wall of upright logs across the forest, with the door of the room in it; curtains above."""
    for x in range(-W, W + 1):
        h = 5 + (1 if x % 3 == 0 else 0)
        g.box(x, F + 1, z, x, F + h, z, "spruce_log")
        if x % 3 == 0:
            g.set(x, F + h + 1, z, "spruce_fence")
    fl.curtain(-W, z, W, z, F + 6, Y1)
    c = path_x(z)
    return c - gap, c + gap


# ======================================================================== landscape round the playable ground

def landscape():
    for x in range(X0, X1 + 1):
        for z in range(Z0, Z1 + 1):
            out = max(0, abs(x) - W - 1, -20 - z, z - 392)
            if not out:
                continue
            h = round(min(26, out * (0.7 + 0.5 * fl.n(x * 0.1, 0, z * 0.1))))
            for y in range(F + 1, F + h + 1):
                g.set(x, y, z, "grass_block" if y == F + h else "dirt" if y > F + h - 4 else "stone")
            if out > 1 and r.random() < 0.07:
                kind = "spruce" if z > 170 else r.choice(["oak", "birch", "oak", "spruce"])
                fl.tree(x, z, F + h + 1, kind, r.choice(["medium", "conifer", "big"]) if kind == "spruce" else r.choice(["small", "medium", "big"]))
    fl.curtain(-W - 1, -20, -W - 1, AR[3] + 1, F + 1, Y1)
    fl.curtain(W + 1, -20, W + 1, AR[3] + 1, F + 1, Y1)
    fl.curtain(-W - 1, -21, W + 1, -21, F + 1, Y1)


# ======================================================================== the gate and the old track (room 1)

def gate_and_track():
    fl.ground(-W, -20, W, 50, F, FOREST_FLOOR)
    track(-20, 50, [("dirt_path", 5), ("coarse_dirt", 3), ("podzol", 2), ("gravel", 1)])
    g.start = (0, F + 1, -10)
    m.start = [0.5, float(F + 1), -9.5, 0.0, 0.0]
    # the gate: two massive trunks, a span of branches, roots round them
    for sx in (-1, 1):
        fl.colossal(sx * 6 - (2 if sx < 0 else 0), -1, F + 1, "dark_oak", h=16, size=3, crown=6, roots=4)
    for x in range(-5, 6):
        y = F + 10 + round(1.5 * math.cos(x / 5 * math.pi / 2))
        g.set(x, y, 0, "dark_oak_log[axis=x]")
        g.set(x, y, 1, "dark_oak_log[axis=x]")
        if r.random() < 0.6:
            g.set(x, y - 1, r.choice((0, 1)), "hanging_roots")
    for x in (-3, 3):
        g.set(x, F + 7, 0, "lantern[hanging=false]") if False else None
    for x, z in ((-12, -14), (12, -12), (-15, -4), (14, -2), (-9, -18), (16, -17)):
        fl.tree(x, z, F + 1, r.choice(["oak", "birch"]), r.choice(["medium", "big", "small"]))
    for x, z in ((-7, -8), (8, -15), (-14, 3)):
        fl.stump(x, z, F + 1, "oak", r.choice((1, 2)))
    fl.fallen(10, -7, F + 1, 7, "z", "oak")
    flowers_and_ferns(-20, 6, 60, UNDERGROWTH + [("dandelion", 1), ("poppy", 1)])
    # the old track: trees of every size, fallen trunks, stumps, log piles, a lean-to, old fences and signs
    scatter(8, 48, 34, lambda x, z: fl.tree(x, z, F + 1, r.choice(["oak", "oak", "birch", "birch", "dark_oak"]),
                                             r.choice(["small", "medium", "medium", "big", "gnarled"])))
    scatter(8, 48, 20, lambda x, z: fl.bush(x, z, F + 1, r.choice(["oak", "birch", "azalea"]) if False else r.choice(["oak", "birch"])))
    fl.fallen(-20, 18, F + 1, 9, "x", "oak", plate=True)
    fl.fallen(13, 33, F + 1, 8, "z", "birch")
    for x, z in ((-9, 12), (10, 16), (-12, 29), (9, 40), (-6, 44)):
        fl.stump(x, z, F + 1, r.choice(["oak", "birch"]), r.choice((1, 1, 2)), fresh=r.random() < 0.3)
    fl.log_pile(-17, 37, F + 1, 3, 2, "oak", "x")
    fl.log_pile(15, 21, F + 1, 3, 3, "birch", "z")
    # a lean-to of planks
    g.box(16, F + 1, 42, 16, F + 3, 46, "spruce_log")
    g.box(17, F + 1, 42, 19, F + 1, 46, "spruce_planks")
    for k in range(3):
        g.box(16 + k, F + 4 - k, 42, 16 + k, F + 4 - k, 46, "spruce_stairs[facing=west]")
    g.set(18, F + 2, 44, "barrel[facing=up]")
    for z in (14, 30):
        c = path_x(z)
        g.set(c + 4, F + 1, z, "spruce_fence")
        g.set(c + 4, F + 2, z, "spruce_wall_sign[facing=west]" if False else "oak_sign[rotation=4]")
    for z in range(22, 28):
        if r.random() < 0.7:
            g.set(path_x(z) - 5, F + 1, z, "oak_fence")
    flowers_and_ferns(8, 48, 120, UNDERGROWTH)
    for z in (12, 26, 40):
        light(path_x(z) + 3, F + 3, z, 9)
    for x, z in ((-2, 15), (3, 24), (-3, 33), (2, 42)):
        m.mob("sylvi_bucheron", path_x(z) + x, z, y=F + 1, yaw=180, room="1")
    for x, z in ((-8, 20), (8, 36)):
        m.mob("sylvi_araignee", x, z, y=F + 1, yaw=180, room="1")
    a, b = palisade(51)
    g.box(a, F + 1, 51, b, F + 5, 51, "spruce_log")
    door("piste", (a, F + 1, 51), (b, F + 5, 51), room="1")


# ======================================================================== the woodcutters' ground (room 2)

def woodcutters():
    fl.ground(-W, 52, W, 98, F, [("coarse_dirt", 4), ("podzol", 3), ("dirt", 2), ("gravel", 1), ("grass_block", 2)])
    track(52, 98, [("mud", 2), ("coarse_dirt", 3), ("packed_mud", 2), ("gravel", 1)], width=3)
    # trees left standing only at the edges
    for z in range(53, 98, 4):
        for sx in (-1, 1):
            if r.random() < 0.7:
                fl.tree(sx * r.randint(19, 23), z, F + 1, r.choice(["oak", "spruce", "birch"]), r.choice(["medium", "big", "conifer"]))
    # stumps everywhere, fresh and old
    for _ in range(46):
        x, z = r.randint(-18, 18), r.randint(53, 97)
        if near_path(x, z, 4) or g.get(x, F + 1, z) != "air":
            continue
        fl.stump(x, z, F + 1, r.choice(["oak", "spruce", "birch"]), r.choice((1, 1, 1, 2)), fresh=r.random() < 0.5)
    # felled trees, stacks of logs by kind (tidy and slid down), platforms, shelters, branch piles
    fl.fallen(-18, 60, F + 1, 11, "x", "spruce", size=1)
    fl.fallen(10, 76, F + 1, 9, "x", "oak")
    fl.fallen(-14, 88, F + 1, 8, "z", "birch")
    for (x, z, kind, axis, coll) in ((-17, 70, "spruce", "z", False), (13, 58, "oak", "x", False), (14, 88, "birch", "z", True),
                                     (-18, 80, "dark_oak", "x", True)):
        fl.log_pile(x, z, F + 1, 4, 3, kind, axis, coll)
    for (x0, z0) in ((-14, 92), (11, 66)):
        for x in (x0, x0 + 4):
            for z in (z0, z0 + 3):
                g.box(x, F + 1, z, x, F + 2, z, "spruce_log")
        g.box(x0, F + 3, z0, x0 + 4, F + 3, z0 + 3, "spruce_slab[type=bottom]")
        g.set(x0 + 2, F + 4, z0 + 1, "barrel[facing=up]")
    for x, z in ((6, 95), (-7, 56), (17, 82)):
        fl.branch_pile(x, z, F + 1, r.choice(["oak", "spruce"]))
    for z in range(60, 96, 9):
        g.set(path_x(z) + 5, F + 1, z, "oak_fence")
        g.set(path_x(z) + 5, F + 2, z, "oak_fence")
    flowers_and_ferns(52, 98, 60, [("fern", 3), ("short_grass", 4), ("dead_bush", 2)])
    for x, z in ((-4, 62), (5, 70), (-6, 84), (4, 92)):
        m.mob("sylvi_bucheron", x, z, y=F + 1, yaw=180, room="2")
    for x, z in ((-9, 74), (9, 86)):
        m.mob("sylvi_rondin", x, z, y=F + 1, yaw=180, room="2")
    m.mob("sylvi_araignee", 2, 78, y=F + 1, yaw=180, room="2")
    a, b = palisade(99)
    g.box(a, F + 1, 99, b, F + 5, 99, "oak_log")
    door("coupe", (a, F + 1, 99), (b, F + 5, 99), room="2")


# ======================================================================== the great clearing and the old sawmill (room 3)

def clearing_and_sawmill():
    fl.ground(-W, 100, W, 176, F, [("grass_block", 7), ("podzol", 1), ("moss_block", 1)])
    track(100, 176, [("dirt_path", 4), ("coarse_dirt", 2), ("podzol", 1)])
    # the clearing: tall trees all round, a colossal oak at its north-east edge, an open middle
    for z in range(101, 138, 3):
        for sx in (-1, 1):
            fl.tree(sx * r.randint(18, 23), z, F + 1, r.choice(["oak", "spruce", "oak"]), r.choice(["big", "conifer"]))
    fl.colossal(13, 125, F + 1, "oak", h=26, size=4, crown=10, roots=7)
    for x, z in ((-12, 108), (-14, 130), (8, 106)):
        fl.stump(x, z, F + 1, "oak", 2)
    fl.fallen(-20, 120, F + 1, 7, "x", "oak")
    for x, z in ((-15, 115), (16, 110)):
        g.blob(x, F + 1, z, 1.8, 1.2, 1.8, (x - 3, F + 1, z - 3, x + 3, F + 3, z + 3), seed=x * 7 + z, rough=0.5,
               block=lambda *a: r.choice(["mossy_cobblestone", "moss_block", "cobblestone"]), where=lambda b: b == "air")
    flowers_and_ferns(100, 138, 110, [("short_grass", 6), ("fern", 3), ("dandelion", 2), ("poppy", 2), ("oxeye_daisy", 2),
                                      ("cornflower", 1), ("large_fern", 1)])
    for x, z in ((-6, 112), (6, 118), (-4, 128), (5, 132)):
        m.mob("sylvi_rondin" if x < 0 else "sylvi_bucheron", x, z, y=F + 1, yaw=180, room="3")
    # the old sawmill: a long open timber hall over the track, its stream and water wheel on the east
    hx0, hx1, hz0, hz1 = -13, 7, 145, 167
    for x in range(hx0, hx1 + 1):
        for z in range(hz0, hz1 + 1):
            g.set(x, F, z, "spruce_planks" if (x + z) % 5 else "oak_planks")
    for x in (hx0, hx1):
        for z in range(hz0, hz1 + 1, 4):
            g.box(x, F + 1, z, x, F + 7, z, "spruce_log")
    for z in range(hz0, hz1 + 1, 4):
        g.box(hx0, F + 8, z, hx1, F + 8, z, "stripped_spruce_log[axis=x]")
    for x in (hx0, hx1):
        g.box(x, F + 8, hz0, x, F + 8, hz1, "stripped_spruce_log[axis=z]")
    for k in range(0, 12):
        y = F + 9 + k // 1
        xw, xe = hx0 - 1 + k, hx1 + 1 - k
        if xw > xe:
            break
        if xw == xe:
            g.box(xw, y, hz0 - 1, xw, y, hz1 + 1, "dark_oak_planks")
            break
        g.box(xw, y, hz0 - 1, xw, y, hz1 + 1, "dark_oak_stairs[facing=east]")
        g.box(xe, y, hz0 - 1, xe, y, hz1 + 1, "dark_oak_stairs[facing=west]")
    # inside: a saw bench, log stores along the sides, a loading deck, barrels; the middle stays open
    g.box(hx0 + 1, F + 1, hz0 + 2, hx0 + 1, F + 1, hz1 - 2, "stripped_oak_log[axis=z]")
    for z in (hz0 + 3, hz1 - 3):
        g.set(hx0 + 1, F + 1, z, "spruce_fence")
    fl.log_pile(hx0 + 2, hz0 + 6, F + 1, 3, 2, "spruce", "z")
    fl.log_pile(hx1 - 4, hz0 + 10, F + 1, 3, 3, "oak", "z", collapsed=True)
    for z in range(hz0 + 1, hz1, 5):
        g.set(hx1 - 1, F + 1, z, "barrel[facing=up]")
    g.box(hx1 + 1, F + 1, hz0 + 4, hx1 + 3, F + 1, hz0 + 9, "spruce_slab[type=top]")
    for z in range(hz0 + 2, hz1, 5):
        g.set(round((hx0 + hx1) / 2), F + 7, z, "lantern[hanging=true]")
        light(round((hx0 + hx1) / 2), F + 5, z, 11)
    # the stream and its water wheel
    for z in range(138, 177):
        for x in (12, 13):
            g.set(x, F, z, "water")
            g.set(x, F - 1, z, "gravel")
        g.set(11, F, z, "mossy_cobblestone")
        g.set(14, F, z, "mossy_cobblestone")
    for z in (138, 176):
        g.box(11, F, z, 14, F, z, "mossy_cobblestone")
    g.box(11, F + 1, 150, 14, F + 1, 151, "spruce_slab[type=bottom]")
    wc, wy = 156, F + 4
    for y in range(wy - 5, wy + 6):
        for z in range(wc - 5, wc + 6):
            d = math.hypot(y - wy, z - wc)
            if 3.6 <= d < 4.6 and y > F:
                g.set(15, y, z, "spruce_planks")
                g.set(17, y, z, "spruce_planks")
                if int(math.degrees(math.atan2(y - wy, z - wc)) % 360 // 30) % 2 == 0:
                    g.set(16, y, z, "stripped_spruce_log[axis=x]")
            elif d < 3.6 and (y == wy or z == wc) and d > 0.5 and y > F:
                g.set(16, y, z, "spruce_fence")
    g.box(14, wy, wc, 18, wy, wc, "spruce_log[axis=x]")
    g.box(18, F + 1, wc, 18, wy - 1, wc, "spruce_log")
    for x, z in ((-6, 150), (2, 156), (-3, 162)):
        m.mob("sylvi_bucheron", x, z, y=F + 1, yaw=180, room="3")
    m.mob("sylvi_araignee", 4, 172, y=F + 1, yaw=180, room="3")
    flowers_and_ferns(140, 176, 40, UNDERGROWTH)
    for sx in (-1, 1):
        for z in range(140, 177, 5):
            if r.random() < 0.6:
                fl.tree(sx * r.randint(18, 23), z, F + 1, r.choice(["spruce", "oak"]), r.choice(["medium", "conifer"]))
    a, b = palisade(177)
    g.box(a, F + 1, 177, b, F + 5, 177, "dark_oak_log")
    door("scierie", (a, F + 1, 177), (b, F + 5, 177), room="3")


# ======================================================================== deep forest and the graveyard of giants (room 4)

def deep_forest():
    fl.ground(-W, 178, W, 258, F, [("podzol", 4), ("coarse_dirt", 2), ("moss_block", 2), ("grass_block", 1), ("rooted_dirt", 1)])
    track(178, 258, [("podzol", 3), ("coarse_dirt", 3), ("rooted_dirt", 1), ("moss_block", 1)])
    # hills on the sides, mossy rocks
    for x in range(-W, W + 1):
        for z in range(178, 259):
            if abs(x - path_x(z)) > 7:
                h = max(0, round(2.5 * (fl.n(x * 0.15, 0, z * 0.15) + 0.3) * min(1, (abs(x - path_x(z)) - 7) / 5)))
                for y in range(F + 1, F + h + 1):
                    g.set(x, y, z, "podzol" if y == F + h else "dirt")
    def ground_y(x, z):
        y = g.floor_below(x, F + 6, z)
        return (y if y is not None else F) + 1
    # dense wood: spruce and dark oak, 2 x 2, the canopy almost closed
    for _ in range(70):
        x, z = r.randint(-W + 2, W - 2), r.randint(179, 220)
        if near_path(x, z, 4) or g.get(x, ground_y(x, z), z) != "air":
            continue
        fl.tree(x, z, ground_y(x, z), r.choice(["spruce", "spruce", "dark_oak"]), r.choice(["big", "conifer", "conifer", "medium"]))
    for z in range(182, 220, 2):
        for x in range(path_x(z) - 3, path_x(z) + 4):
            if r.random() < 0.55:
                fl.put(x, F + 15 + r.randint(0, 1), z, fl.leaves(r.choice(["spruce", "dark_oak"])))
    for x, z in ((-16, 190), (15, 205), (-11, 214)):
        g.blob(x, ground_y(x, z), z, 2, 1.4, 2, (x - 3, F, z - 3, x + 3, F + 5, z + 3), seed=x + z, rough=0.5,
               block=lambda *a: r.choice(["mossy_cobblestone", "moss_block", "tuff"]), where=lambda b: b == "air")
    for _ in range(140):
        x, z = r.randint(-W + 1, W - 1), r.randint(179, 220)
        if not near_path(x, z, 2):
            fl.plant(x, ground_y(x, z), z, g.pick(UNDERGROWTH))
    for z in range(184, 220, 9):
        light(path_x(z) + 2, F + 4, z, 8)
    for x, z in ((-3, 190), (3, 200), (-2, 212)):
        m.mob("sylvi_rondin", path_x(z) + x, z, y=F + 1, yaw=180, room="4")
    for x, z in ((-7, 196), (7, 208)):
        m.mob("sylvi_araignee", x, z, y=ground_y(x, z), yaw=180, room="4")
    # the graveyard of giants: monumental fallen trunks on the sides, dead trees, giant hollow stumps
    for (x, z, axis, kind, size, length) in ((-23, 226, "x", "dark_oak", 3, 15), (8, 238, "x", "spruce", 3, 16),
                                             (-21, 246, "z", "oak", 2, 11), (17, 222, "z", "dark_oak", 2, 12)):
        fl.fallen(x, z, ground_y(x, z) - 0, length, axis, kind, size=size)
    for (x, z) in ((-12, 232), (14, 250), (-15, 252)):
        fl.colossal(x, z, ground_y(x, z), "oak", h=4, size=5, crown=0.1, roots=4, hollow=True)
    for _ in range(9):
        x, z = r.randint(-W + 2, W - 2), r.randint(222, 256)
        if not near_path(x, z, 4) and g.get(x, ground_y(x, z), z) == "air":
            fl.tree(x, z, ground_y(x, z), r.choice(["oak", "spruce", "dark_oak"]), "dead")
    for _ in range(50):
        x, z = r.randint(-W + 1, W - 1), r.randint(222, 257)
        if not near_path(x, z, 2):
            fl.plant(x, ground_y(x, z), z, g.pick([("dead_bush", 2), ("fern", 3), ("moss_carpet", 3)]))
    for z in (228, 244):
        light(path_x(z) - 3, F + 4, z, 7)
    for x, z in ((-4, 230), (4, 236), (-3, 248), (5, 252)):
        m.mob("sylvi_rondin" if x > 0 else "sylvi_bucheron", path_x(z) + x, z, y=F + 1, yaw=180, room="4")
    a, b = palisade(259)
    g.box(a, F + 1, 259, b, F + 5, 259, "dark_oak_log")
    door("geants", (a, F + 1, 259), (b, F + 5, 259), room="4")


# ======================================================================== sanctuary of roots and the ancient forest (room 5)

def ancient_forest():
    fl.ground(-W, 260, W, 343, F, [("moss_block", 3), ("podzol", 3), ("rooted_dirt", 2), ("grass_block", 1)])
    track(260, 343, [("moss_block", 3), ("rooted_dirt", 3), ("podzol", 2)])
    # the sanctuary of roots: arches of giant roots over the way, roots on the sides
    for z in range(262, 286, 3):
        c = path_x(z)
        for x in range(c - 7, c + 8):
            t = (x - (c - 7)) / 14
            top = F + 2 + round(7 * math.sin(math.pi * t))
            for yy in (top, top + 1):
                g.set(x, yy, z, "mangrove_roots" if (x + yy) % 3 else "dark_oak_wood")
            if x in (c - 7, c + 7):
                g.box(x, F + 1, z, x, top, z, "dark_oak_wood")
        for x in range(c - 6, c + 7):
            if r.random() < 0.3:
                fl.put(x, F + 1 + round(7 * math.sin(math.pi * ((x - (c - 7)) / 14))), z, "hanging_roots")
    for _ in range(40):
        x, z = r.randint(-W + 1, W - 1), r.randint(261, 286)
        if not near_path(x, z, 8):
            g.box(x, F + 1, z, x, F + r.randint(1, 4), z, r.choice(["mangrove_roots", "rooted_dirt", "dark_oak_wood"]))
    for z in range(264, 286, 6):
        light(path_x(z), F + 6, z, 9)
    # the ancient forest: colossal trees, a hollow stump, a circular clearing, a cherry pocket
    for (x, z, kind, h, size, crown) in ((-15, 292, "dark_oak", 28, 4, 10), (14, 305, "pale_oak", 24, 3, 9),
                                         (-16, 322, "spruce", 30, 3, 7), (15, 330, "dark_oak", 26, 4, 10)):
        fl.colossal(x, z, F + 1, kind, h=h, size=size, crown=crown, roots=6)
    fl.colossal(9, 290, F + 1, "oak", h=5, size=6, crown=0.1, roots=4, hollow=True)
    for _ in range(14):
        x, z = r.randint(-W + 2, -10), r.randint(300, 318)
        if g.get(x, F + 1, z) == "air":
            fl.tree(x, z, F + 1, "cherry", r.choice(["medium", "gnarled"]))
            g.set(x, F, z, "grass_block")
    for _ in range(40):
        x, z = r.randint(-W + 1, -9), r.randint(298, 320)
        fl.plant(x, F + 1, z, g.pick([("pink_petals[flower_amount=4,facing=north]", 4), ("short_grass", 2), ("allium", 1)]))
    for _ in range(26):
        x, z = r.randint(-W + 2, W - 2), r.randint(286, 341)
        cx, cz = path_x(z), z
        if near_path(x, z, 5) or math.hypot(x - path_x(312), z - 312) < 10 or g.get(x, F + 1, z) != "air":
            continue
        fl.tree(x, z, F + 1, r.choice(["dark_oak", "spruce", "pale_oak"]), r.choice(["big", "conifer", "gnarled"]))
    for x in range(-9, 10):
        for z in range(303, 322):
            if math.hypot(x, z - 312) <= 9:
                g.set(x + path_x(312), F, z, "moss_block" if math.hypot(x, z - 312) > 7.5 else g.pick([("grass_block", 3), ("moss_block", 1)]))
    for _ in range(90):
        x, z = r.randint(-W + 1, W - 1), r.randint(286, 341)
        if not near_path(x, z, 2) and math.hypot(x - path_x(312), z - 312) > 9:
            fl.plant(x, F + 1, z, g.pick(UNDERGROWTH))
    for z in (296, 312, 328, 338):
        light(path_x(z) + 4, F + 5, z, 9)
    for x, z in ((-3, 268), (3, 280)):
        m.mob("sylvi_araignee", path_x(z) + x, z, y=F + 1, yaw=180, room="5")
    for x, z in ((-4, 308), (4, 315), (0, 320)):
        m.mob("sylvi_rondin" if x else "sylvi_bucheron", path_x(312) + x, z, y=F + 1, yaw=180, room="5")
    m.mob("sylvi_bucheron", path_x(334) + 2, 334, y=F + 1, yaw=180, room="5")
    # the approach: a gate of two colossal trunks and roots into the final clearing
    a, b = path_x(343) - 3, path_x(343) + 3
    for x in range(-W, W + 1):
        if a - 1 <= x <= b + 1:
            continue
        g.box(x, F + 1, 343, x, F + 6, 343, "mangrove_roots" if x % 2 else "dark_oak_wood")
    fl.curtain(-W, 343, W, 343, F + 7, Y1)
    g.box(a - 1, F + 1, 343, a - 1, F + 9, 343, "dark_oak_wood")
    g.box(b + 1, F + 1, 343, b + 1, F + 9, 343, "dark_oak_wood")
    g.box(a - 1, F + 9, 343, b + 1, F + 9, 343, "mangrove_roots")
    g.box(a, F + 1, 343, b, F + 5, 343, lambda x, y, z: "mangrove_roots" if (x + y) % 2 else "dark_oak_wood")
    door("anciens", (a, F + 1, 343), (b, F + 5, 343), room="5")
    fl.curtain(a, 343, b, 343, F + 6, F + 8)


# ======================================================================== the final clearing

def final_clearing():
    x0, x1, z0, z1 = AR
    cx, cz = (x0 + x1 + 1) / 2, (z0 + z1 + 1) / 2
    fl.ground(-W, z0, W, z1 + 10, F, [("grass_block", 5), ("moss_block", 2), ("podzol", 2), ("rooted_dirt", 1)])
    # ring of colossal trees, roots, stumps and fallen trunks, all out of the clearing
    for (x, z, kind) in ((x0 - 4, z0 + 2, "dark_oak"), (x1 + 2, z0 + 6, "oak"), (x0 - 4, z1 - 6, "spruce"), (x1 + 2, z1 - 3, "dark_oak"),
                         (-2, z1 + 4, "oak")):
        fl.colossal(x, z, F + 1, kind, h=r.randint(26, 32), size=4 if kind != "spruce" else 3, crown=11 if kind != "spruce" else 7, roots=5)
    fl.fallen(x0 - 5, z0 + 18, F + 1, 7, "z", "oak", size=2)
    fl.fallen(x1 + 2, z0 + 20, F + 1, 8, "z", "spruce", size=2)
    for x, z in ((x0 - 3, z1 + 3), (x1 + 3, z0 - 0), (x1 + 4, z1 + 4)):
        fl.stump(x, z, F + 1, "oak", 3)
    for _ in range(60):
        x, z = r.randint(-W, W), r.randint(z0, z1 + 9)
        if not (x0 - 1 <= x <= x1 + 1 and z0 - 1 <= z <= z1 + 1):
            fl.plant(x, F + 1, z, g.pick(UNDERGROWTH + [("lily_of_the_valley", 1)]))
    # the clearing itself: flat, open, nothing on it
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            g.set(x, F, z, g.pick([("grass_block", 6), ("moss_block", 1), ("podzol", 1), ("coarse_dirt", 1)]))
            g.box(x, F + 1, z, x, F + 8, z, "air")
    # invisible walls round it, the gate at its south side
    for x in range(x0 - 1, x1 + 2):
        for z in range(z0 - 1, z1 + 2):
            if x in (x0 - 1, x1 + 1) or z in (z0 - 1, z1 + 1):
                if z == z0 - 1:
                    continue
                for y in range(F + 1, Y1 + 1):
                    b = g.get(x, y, z)
                    if b == "air" or gridlib.passable(b) or "leaves" in b:
                        g.set(x, y, z, "barrier")
    for x in range(x0, x1 + 1, 6):
        for z in (z0, z1):
            light(x, F + 6, z, 12)
    m.mob("sylvi_gardien", round(cx), round(cz), y=F + 1, yaw=180, room="6")
    m.objectives = ["sylvi_gardien"]


# ======================================================================== checks, main

def zone_of(x, y, z):
    for i, lim in enumerate((51, 99, 177, 259, 343)):
        if z <= lim:
            return i + 1
    return 6


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
        if top < min(k + 1, 6):
            errors.append(f"doors open {k}: zone {k + 1} not reachable (max {top})")
    x0, x1, z0, z1 = AR
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if any(g.get(x, y, z) != "air" for y in (F + 1, F + 2, F + 3)):
                errors.append(f"the final clearing is not free at {x} {z}")
                break
    return errors


landscape()
gate_and_track()
woodcutters()
clearing_and_sawmill()
deep_forest()
ancient_forest()
final_clearing()
m.textures = "rpg"
m.biome = "dark_forest"      # RPG grass and leaves painted in that colour, biome set on the map
m.ybox = (Y0, Y1)

if __name__ == "__main__":
    args = sys.argv[1:]
    errs = check()
    for e in errs[:50]:
        print("CHECK:", e)
    print(f"{len(errs)} problem(s), {len(g.over)} blocks changed, {len(g.entities)} entities")
    if "--preview" in args:
        out = args[args.index("--preview") + 1]
        g.png_slice(os.path.join(out, "sylvi_ciel.png"), top_down=True, k=2)
        g.png_slice(os.path.join(out, "sylvi_coupe_x0.png"), x=0, k=2)
    if "--check" in args or "--preview" in args:
        sys.exit(1 if errs else 0)
    if errs:
        sys.exit("checks failed: nothing sent")
    g.commands(m)
    print(f"{len(m.cmds)} commands")
    maplib.run(m, args[0] if args and not args[0].startswith("--") else "labs", "sylviculteur")
