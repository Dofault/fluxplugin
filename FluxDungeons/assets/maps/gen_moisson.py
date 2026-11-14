#!/usr/bin/env python3
"""Map of the harvest dungeon, Le Domaine des Cultivateurs (boss: the Hamster Moissonneur), modelled in memory
(gridlib), checked, then built by console commands with the RPG look of the dungeons.

Third version (2026-11-14): an ancient farm estate of five great walled halls in a row towards +z, ground at y 63.
Sizes are the free inside of each hall:
  - the porch (start at 0, 64, -8) and the timber arch of the estate;
  - room 1, the Estate Gate (45 x 45, walls 16): a 7-wide road, eight plots and their ditches, two barns, wells,
    carts; an open timber lattice overhead;
  - room 2, the Golden Fields (61 x 55, walls 19): eleven crop fields to walk and fight through, two windmills, a
    watchtower, sheds, a 2-wide canal with footbridges; the Possessed Miller (mini-boss);
  - room 3, the Old Greenhouse (51 x 51, walls 23): a 31 x 36 glasshouse 20 high standing on pillars (every bay is
    open at ground level: no corridor), jungle trees, raised beds, three basins, canals and two wall cascades;
  - room 4, the Great Harvests (65 x 60, walls 22): fields (one on a terrace), an orchard, workshops, two silos and
    the great barn at the back, whose door is the way on (the Great Scarecrow, mini-boss, stands before it);
  - room 5, the Sanctuary of the Earth (55 x 55, 28 high): the hall of the Great Hamster. The boss fights in a field:
    its pen of the first version is copied block for block in the middle (crops, fences, lanterns, haystacks: its
    spells depend on them), the sanctuary stands around it out of reach (corner pillars, arches, four ritual gardens,
    the giant wheel, a glass dome over the field).
A gatehouse 7 wide and 7 long joins two rooms, closed by a 7 x 6 timber door that opens when the monsters of the room
before it are dead.

Nobody stands more than two blocks above the ground: `seal_heights` puts invisible barriers wherever a player could
climb higher (roofs, lofts, walls). The script builds nothing if a check fails.

Usage: gen_moisson.py [server] [--check] [--preview DIR] [--edits]     (labs by default)
"""
import math
import os
import sys

import flora
import gridlib
import maplib

F = 63
ROOMS = {1: (-22, 22, 1, 45, 16), 2: (-30, 30, 53, 107, 19), 3: (-25, 25, 115, 165, 23), 4: (-32, 32, 173, 232, 22),
         5: (-27, 27, 240, 294, 21)}
X0, X1, Y0, Y1, Z0, Z1 = -40, 40, 56, 96, -22, 302
BANDS = ((56, 60, "stone"), (61, 62, "dirt"), (63, 63, "grass_block"))
DZ = 251                         # the former arena (z -4..36) is moved to z 247..287
PEN = (-16, DZ - 4, 16, DZ + 36)
CURTAIN = F + 12
ARENA = os.path.join(maplib.HERE, "moisson_arena.txt")
EXIT_GATE = "spruce_fence_gate[facing=south,in_wall=false,open=false]"
BARN_DOOR_Z = 216
CROPS = ("carrots", "potatoes", "wheat[", "beetroots")
SOIL = ("grass_block", "dirt", "coarse_dirt", "podzol")

gridlib.COLORS.update({"glass": (170, 215, 225), "lime_stained_glass": (150, 220, 120), "spruce_log": (80, 55, 30),
                       "white_terracotta": (210, 180, 160), "moss_block": (90, 120, 50), "mud": (60, 55, 60),
                       "light[": (20, 20, 28), "pumpkin": (220, 130, 30), "melon": (120, 170, 40), "clay": (150, 160, 175),
                       "dark_oak_planks": (70, 45, 25), "bricks": (150, 90, 70), "jungle_log": (90, 75, 30)})

g = gridlib.Model((X0, X1, Y0, Y1, Z0, Z1), BANDS, seed=11, outside="barrier")
r = g.r
pick = g.pick
B, S = g.box, g.set
fl = flora.Flora(g, 4242)
m = maplib.Map()
m.box = (X0, Z0, X1, Z1)
KEEP = []                        # rectangles (x0, z0, x1, z1) the scattered decor stays out of
FREE = {}                        # room -> fighting grounds checked free


def door(id, frm, to, room):
    g.doors.append((tuple(min(a, b) for a, b in zip(frm, to)), tuple(max(a, b) for a, b in zip(frm, to))))
    m.door(id, frm, to, room=room)


def keep(x0, z0, x1, z1, room=None):
    KEEP.append((min(x0, x1), min(z0, z1), max(x0, x1), max(z0, z1)))
    if room:
        FREE.setdefault(room, []).append(KEEP[-1])


def kept(x, z):
    return any(a <= x <= c and b <= z <= d for a, b, c, d in KEEP)


# ======================================================================== shells, roofs, gatehouses

STONE = [("cobblestone", 5), ("stone_bricks", 3), ("mossy_cobblestone", 2), ("stone", 1)]
BRICK = [("stone_bricks", 6), ("mossy_stone_bricks", 3), ("cracked_stone_bricks", 1)]


def wall_gate(u, h, H):
    if h <= 3:
        return pick(STONE)
    if u % 6 == 0:
        return "spruce_log"
    if h in (7, 11, H):
        return "dark_oak_planks"
    if h in (8, 9, 10) and u % 6 in (2, 3, 4):
        return "glass"
    return "oak_planks" if (u * 7 + h) % 9 else "spruce_planks"


def wall_fields(u, h, H):
    if h <= 4:
        return pick(BRICK + [("cobblestone", 3)])
    if u % 5 == 0:
        return "spruce_log"
    if h in (5, 12, 17, H):
        return "dark_oak_planks"
    if 13 <= h <= 16 and u % 5 in (2, 3):
        return "glass"
    if 6 <= h <= 11 and (u % 5) == ((h - 6) % 6 if (u // 5) % 2 else (11 - h) % 6):
        return "dark_oak_planks"
    return "white_terracotta"


def wall_green(u, h, H):
    if u % 8 == 0:
        return "polished_andesite" if h % 6 else "chiseled_stone_bricks"
    if u % 8 in (3, 4, 5) and 6 <= h <= 18:
        k = r.random()
        return "air" if k < 0.05 else ("lime_stained_glass" if k < 0.3 else "glass")
    if h in (5, 19):
        return "stone_bricks"
    return pick([("mossy_stone_bricks", 5), ("stone_bricks", 3), ("mossy_cobblestone", 2), ("cracked_stone_bricks", 1)])


def wall_harvest(u, h, H):
    if h <= 3:
        return pick(STONE)
    if u % 6 == 0:
        return "dark_oak_log"
    if h in (4, 10, 16, H):
        return "spruce_log" if h == 10 else "dark_oak_planks"
    if h in (12, 13) and u % 6 in (2, 3, 4) and (u // 6) % 2 == 0:
        return "glass"
    return "spruce_planks" if (u + h * 3) % 11 else "dark_oak_planks"


def wall_sanct(u, h, H):
    if u % 9 == 0:
        return "polished_andesite"
    if h in (10, 20):
        return "chiseled_stone_bricks"
    if h in (1, 11, H):
        return "stone_bricks"
    return pick([("stone_bricks", 6), ("mossy_stone_bricks", 3), ("cracked_stone_bricks", 1), ("mossy_cobblestone", 1)])


def shell(n, mat, cap="dark_oak_planks"):
    x0, x1, z0, z1, H = ROOMS[n]
    for y in range(F + 1, F + H + 2):
        h = y - F
        for x in range(x0 - 1, x1 + 2):
            for z in (z0 - 1, z1 + 1):
                S(x, y, z, cap if h == H + 1 else mat(x - x0, h, H))
        for z in range(z0, z1 + 1):
            for x in (x0 - 1, x1 + 1):
                S(x, y, z, cap if h == H + 1 else mat(z - z0, h, H))


def lattice(n, beam="spruce_planks", step=6, xs=(), leaves=0.0, lanterns=()):
    """An open timber roof: beams across the hall, purlins along it, tufts of leaves, lanterns on chains."""
    x0, x1, z0, z1, H = ROOMS[n]
    y = F + H + 1
    for z in range(z0 + 2, z1, step):
        B(x0, y, z, x1, y, z, beam)
        if leaves:
            for x in range(x0, x1 + 1):
                if r.random() < leaves:
                    S(x, y - 1, z, "oak_leaves[persistent=true]")
                    if r.random() < 0.4:
                        S(x, y - 2, z, "oak_leaves[persistent=true]")
    for x in xs:
        B(x, y, z0, x, y, z1, beam)
    for x, z, drop in lanterns:
        B(x, y - drop, z, x, y - 1, z, "iron_chain[axis=y]")
        S(x, y - drop - 1, z, "lantern[hanging=true]")
        if g.get(x, y, z) == "air":
            S(x, y, z, beam)


def lights(n, y=6, step=6, level=13):
    x0, x1, z0, z1, _ = ROOMS[n]
    for x in range(x0 + 2, x1, step):
        for z in range(z0 + 2, z1, step):
            if g.get(x, F + y, z) == "air":
                S(x, F + y, z, f"light[level={level}]")


def door_block(x, y, z):
    if abs(x) == 3 or y in (F + 2, F + 5):
        return "dark_oak_planks"
    return "spruce_planks"


def gateway(z, room=None, door_id=None, post="spruce_log", mass=STONE):
    """The gatehouse between two halls: a 7 x 6 tunnel, 7 long, from wall plane z to z + 6, its door in the middle."""
    B(-6, F + 1, z, 6, F + 9, z + 6, lambda *a: pick(mass))
    B(-3, F + 1, z, 3, F + 6, z + 6, "air")
    B(-3, F, z, 3, F, z + 6, lambda *a: pick([("dirt_path", 6), ("coarse_dirt", 2), ("stone_bricks", 1)]))
    for zz in (z, z + 6):
        for sx in (-1, 1):
            B(4 * sx, F + 1, zz, 4 * sx, F + 7, zz, post)
            S(3 * sx, F + 6, zz, f"spruce_stairs[half=top,facing={'west' if sx < 0 else 'east'}]")
        B(-4, F + 8, zz, 4, F + 8, zz, "dark_oak_planks")
        B(-3, F + 7, zz, 3, F + 7, zz, "dark_oak_planks")
    B(-3, F + 7, z + 1, 3, F + 7, z + 5, lambda x, y, zz: "spruce_planks" if (zz - z) % 2 else "dark_oak_planks")
    for zz in (z + 1, z + 5):
        for x in (-2, 2):
            S(x, F + 6, zz, "lantern[hanging=true]")
    if door_id:
        B(-3, F + 1, z + 3, 3, F + 6, z + 3, door_block)
        door(door_id, (-3, F + 1, z + 3), (3, F + 6, z + 3), room=room)
    keep(-4, z - 4, 4, z + 10)


def frame_arch(z, face, leaves=True):
    """Decor round a gate on the hall side: thick posts, a beam, leaves and lanterns. face: -1 south side, 1 north."""
    zz = z + face
    for sx in (-1, 1):
        B(5 * sx, F + 1, zz, 5 * sx, F + 8, zz, "dark_oak_log")
        S(5 * sx, F + 9, zz, "lantern")
        S(4 * sx, F + 7, zz, f"dark_oak_stairs[half=top,facing={'west' if sx < 0 else 'east'}]")
    B(-5, F + 8, zz, 5, F + 8, zz, "dark_oak_planks", where=lambda b: b == "air")
    if leaves:
        for x in range(-6, 7):
            if r.random() < 0.6:
                S(x, F + 9, zz, "oak_leaves[persistent=true]") if g.get(x, F + 9, zz) == "air" else None
            if abs(x) >= 4 and r.random() < 0.35 and g.get(x, F + 7, zz) == "air":
                S(x, F + 7, zz, "oak_leaves[persistent=true]")


# ======================================================================== small builders

def path(x0, z0, x1, z1, table=None):
    table = table or [("dirt_path", 8), ("coarse_dirt", 2), ("gravel", 1)]
    B(x0, F, z0, x1, F, z1, lambda *a: pick(table))


def crop_plot(x0, z0, x1, z1, crop, wild=0.0, y=F, ages=None):
    top = {"wheat": 7, "carrots": 7, "potatoes": 7, "beetroots": 3}[crop]
    for x in range(min(x0, x1), max(x0, x1) + 1):
        for z in range(min(z0, z1), max(z0, z1) + 1):
            if r.random() < wild:
                S(x, y, z, "coarse_dirt")
                S(x, y + 1, z, r.choice(["short_grass", "short_grass", "dead_bush", "fern", "air"]))
                continue
            S(x, y, z, "farmland[moisture=7]")
            if ages:
                age = r.randint(*ages)
            else:
                age = top if r.random() < 0.7 else r.randint(max(0, top - 4), top)
            S(x, y + 1, z, f"{crop}[age={age}]")


def gourd_plot(x0, z0, x1, z1, fruit):
    """Pumpkins or melons: a few stems on farmland with their fruit, grass and leaves between (easy to cross)."""
    for z in range(z0, z1 + 1):
        for x in range(x0, x1 + 1):
            S(x, F, z, pick([("coarse_dirt", 3), ("grass_block", 2), ("dirt", 2)]))
            if (x - x0) % 3 == 0 and (z - z0) % 2 == 0:
                S(x, F, z, "farmland[moisture=7]")
                if r.random() < 0.55 and x + 1 <= x1:
                    S(x, F + 1, z, f"attached_{fruit}_stem[facing=east]")
                    S(x + 1, F + 1, z, fruit if r.random() < 0.85 or fruit == "melon" else "carved_pumpkin[facing=south]")
                else:
                    S(x, F + 1, z, f"{fruit}_stem[age={r.randint(3, 7)}]")
            elif r.random() < 0.2 and g.get(x, F + 1, z) == "air" and g.get(x, F, z) != "farmland[moisture=7]":
                S(x, F + 1, z, "short_grass")


def canal(x0, z0, x1, z1, bridges=()):
    """A shallow ditch (one block of water, flush with the ground), plank footbridges flush across it."""
    for x in range(min(x0, x1), max(x0, x1) + 1):
        for z in range(min(z0, z1), max(z0, z1) + 1):
            S(x, F, z, "water")
            S(x, F - 1, z, "clay" if r.random() < 0.7 else "gravel")
            S(x, F - 2, z, "dirt")
            S(x, F + 1, z, "lily_pad" if r.random() < 0.07 else "air")
    for a, b, c, d in bridges:
        B(a, F, b, c, F, d, "spruce_slab[type=top]")
        B(a, F + 1, b, c, F + 1, d, "air")
        keep(a - 1, b - 1, c + 1, d + 1)


def basin(x0, z0, x1, z1, rim=None):
    for x in range(x0 - 1, x1 + 2):
        for z in range(z0 - 1, z1 + 2):
            if x0 <= x <= x1 and z0 <= z <= z1:
                S(x, F, z, "water")
                S(x, F - 1, z, "clay")
                S(x, F - 2, z, "stone")
                S(x, F + 1, z, "lily_pad" if r.random() < 0.18 else "air")
            elif g.get(x, F, z) != "water":
                S(x, F, z, rim or pick(BRICK))


def scarecrow(x, z, facing="south"):
    B(x, F + 1, z, x, F + 2, z, "spruce_fence")
    S(x - 1, F + 2, z, "spruce_fence")
    S(x + 1, F + 2, z, "spruce_fence")
    S(x, F + 3, z, f"carved_pumpkin[facing={facing}]")


def hay(x, z, h, y=F + 1):
    for yy in range(y, y + h):
        S(x, yy, z, "hay_block[axis=y]")


def barrels(x, z, n, top_items=None, y=F + 1):
    for i in range(n):
        S(x, y + i, z, "barrel[facing=up]")
    if top_items:
        g.item_display(x + 0.5, y + n + 0.25, z + 0.5, r.choice(top_items), yaw=r.uniform(0, 360), scale=0.7)


def crate(x, z, items=None, y=F + 1):
    S(x, y, z, "spruce_planks")
    S(x, y + 1, z, "spruce_trapdoor[half=bottom,facing=north]")
    if items:
        g.item_display(x + 0.5, y + 1.35, z + 0.5, r.choice(items), yaw=r.uniform(0, 360), scale=0.6)


def sacks(x, z, n, y=F + 1):
    for i in range(n):
        S(x, y + i, z, pick([("white_wool", 5), ("light_gray_wool", 2), ("brown_terracotta", 2), ("yellow_terracotta", 1)]))


def cart(x, z, axis="z", load="hay"):
    """A farm cart, 2 x 3, wheels on its sides, a load and its shaft."""
    long = [(x, z + i) if axis == "z" else (x + i, z) for i in range(3)]
    side = (1, 0) if axis == "z" else (0, 1)
    for cx, cz in long:
        for k in (0, 1):
            px, pz = cx + side[0] * k, cz + side[1] * k
            S(px, F + 1, pz, "spruce_slab[type=top]")
            if load == "hay" and r.random() < 0.8:
                S(px, F + 2, pz, "hay_block[axis=y]")
            elif load == "pumpkin":
                S(px, F + 2, pz, r.choice(["pumpkin", "melon", "spruce_slab[type=bottom]"]))
            elif load == "sacks" and r.random() < 0.7:
                S(px, F + 2, pz, r.choice(["white_wool", "brown_terracotta"]))
    mid = long[1]
    fa, fb = ("west", "east") if axis == "z" else ("north", "south")
    S(mid[0] - side[0], F + 1, mid[1] - side[1], f"dark_oak_trapdoor[facing={fa},open=true,half=bottom]")
    S(mid[0] + 2 * side[0], F + 1, mid[1] + 2 * side[1], f"dark_oak_trapdoor[facing={fb},open=true,half=bottom]")
    tip = (long[-1][0] + (0 if axis == "z" else 1), long[-1][1] + (1 if axis == "z" else 0))
    S(tip[0], F + 1, tip[1], "spruce_fence")


def well(x, z):
    B(x - 1, F, z - 1, x + 1, F + 1, z + 1, lambda *a: pick(STONE))
    B(x, F - 3, z, x, F + 1, z, "water")
    B(x - 1, F - 4, z - 1, x + 1, F - 4, z + 1, "stone")
    for dx, dz in ((-1, -1), (1, 1), (-1, 1), (1, -1)):
        B(x + dx, F + 2, z + dz, x + dx, F + 3, z + dz, "spruce_fence")
    B(x - 1, F + 4, z - 1, x + 1, F + 4, z + 1, "spruce_slab[type=bottom]")
    S(x, F + 4, z, "spruce_planks")
    S(x, F + 3, z, "iron_chain[axis=y]")


def tool(x, y, z, item, yaw, roll=35):
    g.item_display(x, y, z, item, yaw=yaw, roll=roll, scale=1.1)


def lamp(x, z, h=2, y=F + 1):
    B(x, y, z, x, y + h - 1, z, "spruce_fence")
    S(x, y + h, z, "lantern")


def fruit_tree(x, z, kind="oak", family="small"):
    fl.tree(x, z, F + 1, kind, family)
    for _ in range(3):
        dx, dz = r.choice([(-1, 0), (1, 0), (0, 1), (0, -1), (1, 1), (-1, -1)])
        g.item_display(x + dx + 0.5, F + 3.4 + r.random(), z + dz + 0.5, "apple", yaw=r.uniform(0, 360), scale=0.5)


def plants(x0, z0, x1, z1, n, table=None):
    table = table or [("short_grass", 10), ("fern", 3), ("dandelion", 2), ("poppy", 2), ("cornflower", 1),
                      ("oxeye_daisy", 1), ("azure_bluet", 1)]
    for _ in range(n):
        x, z = r.randint(x0, x1), r.randint(z0, z1)
        if g.get(x, F + 1, z) == "air" and g.get(x, F, z) in SOIL:
            fl.plant(x, F + 1, z, pick(table))


def spot_free(x, z, w=1, d=1, margin=1):
    for xx in range(x - margin, x + w + margin):
        for zz in range(z - margin, z + d + margin):
            inner = x <= xx < x + w and z <= zz < z + d
            b, fb = g.get(xx, F + 1, zz), g.get(xx, F, zz)
            if inner and (b != "air" or fb == "water" or "farmland" in fb or kept(xx, zz)):
                return False
            if not inner and not gridlib.passable(b):
                return False
    return True


def scatter(rect, n, fn, w=1, d=1, margin=1):
    """Calls fn(x, z) on n free spots of rect (x0, z0, x1, z1): off the roads, the fields and the fighting grounds."""
    x0, z0, x1, z1 = rect
    done = 0
    for _ in range(4000):
        if done >= n:
            return
        x, z = r.randint(x0, x1 - w + 1), r.randint(z0, z1 - d + 1)
        if spot_free(x, z, w, d, margin):
            fn(x, z)
            done += 1
    sys.exit(f"scatter: only {done}/{n} spots in {rect}")


def shed(x0, z0, x1, z1, h, ridge="x", door="south", walls="oak_planks", posts="spruce_log", roof="dark_oak",
         base=2, hayloft=True):
    """A closed farm building (decor): stone base, timber walls, a gable roof with eaves, a shut double door, a loft
    opening full of hay over it, windows."""
    def wall(x, y, z):
        if (x in (x0, x1)) and (z in (z0, z1)):
            return posts
        if y <= F + base:
            return pick(STONE)
        return walls if (x + z + y) % 7 else "spruce_planks"
    B(x0, F + 1, z0, x1, F + h, z1, wall)
    B(x0, F + h, z0, x1, F + h, z1, lambda x, y, z: "dark_oak_planks" if x in (x0, x1) or z in (z0, z1) else "hay_block[axis=y]")
    # the roof
    span = (z1 - z0 if ridge == "x" else x1 - x0) + 3
    for k in range(span // 2 + 1):
        y = F + h + 1 + k
        if ridge == "x":
            za, zb = z0 - 1 + k, z1 + 1 - k
            if za > zb:
                break
            if za == zb:
                B(x0 - 1, y, za, x1 + 1, y, za, f"{roof}_slab[type=bottom]")
                break
            B(x0 - 1, y, za, x1 + 1, y, za, f"{roof}_stairs[facing=south]")
            B(x0 - 1, y, zb, x1 + 1, y, zb, f"{roof}_stairs[facing=north]")
            for x in (x0, x1):
                B(x, y, za + 1, x, y, zb - 1, lambda *a: walls)
        else:
            xa, xb = x0 - 1 + k, x1 + 1 - k
            if xa > xb:
                break
            if xa == xb:
                B(xa, y, z0 - 1, xa, y, z1 + 1, f"{roof}_slab[type=bottom]")
                break
            B(xa, y, z0 - 1, xa, y, z1 + 1, f"{roof}_stairs[facing=east]")
            B(xb, y, z0 - 1, xb, y, z1 + 1, f"{roof}_stairs[facing=west]")
            for z in (z0, z1):
                B(xa + 1, y, z, xb - 1, y, z, lambda *a: walls)
    # the door, its lantern and the loft opening over it
    if door in ("south", "north"):
        dz = z0 if door == "south" else z1
        cx = (x0 + x1) // 2
        B(cx - 1, F + 1, dz, cx + 1, F + 3, dz, lambda x, y, z: "dark_oak_planks" if (x + y) % 2 else "spruce_planks")
        B(cx - 2, F + 4, dz, cx + 2, F + 4, dz, "dark_oak_planks")
        out = dz + (-1 if door == "south" else 1)
        S(cx + 2, F + 3, out, "lantern[hanging=true]") if h >= 4 else None
        S(cx + 2, F + 4, out, "spruce_fence")
        if hayloft and h >= 6:
            B(cx - 1, F + 5, dz, cx + 1, F + 6, dz, "hay_block[axis=y]")
    else:
        dx = x0 if door == "west" else x1
        cz = (z0 + z1) // 2
        B(dx, F + 1, cz - 1, dx, F + 3, cz + 1, lambda x, y, z: "dark_oak_planks" if (z + y) % 2 else "spruce_planks")
        B(dx, F + 4, cz - 2, dx, F + 4, cz + 2, "dark_oak_planks")
        out = dx + (-1 if door == "west" else 1)
        S(out, F + 4, cz + 2, "spruce_fence")
        S(out, F + 3, cz + 2, "lantern[hanging=true]")
        if hayloft and h >= 6:
            B(dx, F + 5, cz - 1, dx, F + 6, cz + 1, "hay_block[axis=y]")
    keep(x0 - 1, z0 - 1, x1 + 1, z1 + 1)


def disc(cx, cz, rad, y, block, hollow=False):
    for x in range(int(cx - rad - 1), int(cx + rad + 2)):
        for z in range(int(cz - rad - 1), int(cz + rad + 2)):
            d = math.hypot(x - cx, z - cz)
            if d <= rad + 0.35 and (not hollow or d > rad - 0.75):
                S(x, y, z, block(x, y, z) if callable(block) else block)


def mobs(room, mob, pts):
    for x, z in pts:
        best = None
        for rad in range(0, 5):
            for dx in range(-rad, rad + 1):
                for dz in range(-rad, rad + 1):
                    if max(abs(dx), abs(dz)) != rad or best:
                        continue
                    px, pz = x + dx, z + dz
                    if (all(gridlib.passable(g.get(px, F + k, pz)) and g.get(px, F + k, pz) != "water" for k in (1, 2, 3))
                            and not gridlib.passable(g.get(px, F, pz))):
                        best = (px, pz)
            if best:
                break
        if not best:
            sys.exit(f"no ground for {mob} near {x} {z}")
        m.mob(mob, best[0], best[1], yaw=180, room=str(room))


# ======================================================================== the porch and room 1: the Estate Gate

def porch():
    B(-9, F + 1, -15, 9, F + 5, -1, lambda *a: "oak_leaves[persistent=true]" if r.random() < 0.9 else "oak_leaves[persistent=true]")
    B(-8, F + 1, -14, 8, F + 6, -1, "air")
    fl.ground(-8, -14, 8, -1, F, [("grass_block", 5), ("coarse_dirt", 3), ("dirt_path", 2)])
    path(-3, -14, 3, 0)
    plants(-8, -14, -4, -1, 30)
    plants(4, -14, 8, -1, 30)
    cart(5, -11, "z", "hay")
    lamp(-5, -4)
    lamp(5, -4)
    S(-5, F + 1, -9, "oak_sign[rotation=4]")
    hay(-7, -12, 2)
    hay(-6, -12, 1)
    barrels(7, -3, 2, ["wheat", "carrot"])
    # the timber arch of the estate, in the south wall of room 1
    B(-4, F + 1, 0, 4, F + 7, 0, "air")
    for sx in (-1, 1):
        B(5 * sx, F + 1, -1, 5 * sx, F + 9, 0, "dark_oak_log")
        B(4 * sx, F + 7, 0, 4 * sx, F + 7, 0, f"dark_oak_stairs[half=top,facing={'west' if sx < 0 else 'east'}]")
        S(5 * sx, F + 10, -1, "lantern")
    B(-5, F + 8, -1, 5, F + 9, 0, "dark_oak_planks")
    for x in range(-7, 8):
        for zz in (-1, 1):
            if r.random() < 0.55 and g.get(x, F + 10, zz - (1 if zz > 0 else 0)) == "air":
                S(x, F + 10, zz - (1 if zz > 0 else 0), "oak_leaves[persistent=true]")
        if abs(x) >= 5 and r.random() < 0.5 and g.get(x, F + 7, -1) == "air":
            S(x, F + 7, -1, "oak_leaves[persistent=true]")
    S(0, F + 7, 0, "lantern[hanging=true]")
    m.start = [0.5, 64.0, -8.5, 0.0, 0.0]


def room1():
    x0, x1, z0, z1, H = ROOMS[1]
    fl.ground(x0, z0, x1, z1, F, [("coarse_dirt", 45), ("podzol", 9), ("gravel", 6), ("grass_block", 12), ("dirt", 8),
                                  ("mossy_stone_bricks", 6), ("stone_bricks", 4)])
    path(-3, z0, 3, z1)
    path(x0, 21, x1, 24, [("dirt_path", 6), ("coarse_dirt", 3), ("podzol", 1)])
    keep(-3, z0, 3, z1, room=1)
    keep(x0, 21, x1, 24, room=1)
    # eight plots, a ditch through each side
    crop_plot(-20, 4, -13, 10, "wheat")
    crop_plot(-11, 4, -6, 10, "carrots")
    crop_plot(-20, 15, -13, 19, "potatoes")
    crop_plot(-11, 15, -6, 19, "wheat", wild=0.7)
    crop_plot(13, 4, 20, 10, "beetroots")
    crop_plot(6, 4, 11, 10, "wheat", wild=0.1)
    gourd_plot(13, 15, 20, 19, "pumpkin")
    gourd_plot(6, 15, 11, 19, "melon")
    canal(-21, 12, -6, 13, bridges=[(-12, 12, -10, 13)])
    canal(6, 12, 21, 13, bridges=[(10, 12, 12, 13)])
    for a, b, c, d in ((-21, 3, -5, 20), (5, 3, 21, 20)):
        keep(a, b, c, d)
    scarecrow(-16, 7, "east")
    scarecrow(16, 7, "west")
    # two barns facing each other across the road
    shed(-21, 28, -10, 37, 6, ridge="x", door="east")
    shed(10, 28, 21, 37, 6, ridge="x", door="west")
    for sx in (-1, 1):
        hay(9 * sx, 28, 2)
        hay(9 * sx, 29, 1)
        barrels(9 * sx, 37, 2, ["wheat", "potato"])
        S(9 * sx, F + 1, 35, "composter[level=5]")
    # wells, carts, hay, barrels, composters, crates, fruit trees in the corners
    well(-8, 41)
    well(8, 41)
    well(-17, 42)
    cart(-9, 26, "x", "hay")
    cart(6, 26, "x", "pumpkin")
    cart(14, 40, "z", "sacks")
    cart(-21, 39, "z", "hay")
    for x, z in ((-21, 44), (21, 44), (-21, 2), (21, 2)):
        fruit_tree(x, z)
    north = (x0, 38, x1, z1)
    scatter(north, 4, lambda x, z: hay(x, z, r.randint(1, 2)))
    scatter(north, 3, lambda x, z: barrels(x, z, r.randint(1, 2), ["apple", "carrot", "beetroot"]))
    scatter(north, 2, lambda x, z: S(x, F + 1, z, f"composter[level={r.randint(2, 7)}]"))
    scatter(north, 3, lambda x, z: crate(x, z, ["carrot", "potato", "beetroot", "apple"]))
    scatter((x0, 25, x1, 27), 2, lambda x, z: crate(x, z, ["wheat", "bread"]))
    tool(-9.4, F + 1.8, 31.5, "iron_hoe", yaw=90)
    tool(9.4, F + 1.8, 33.5, "stone_shovel", yaw=-90, roll=-30)
    plants(x0, z0, x1, z1, 150, [("short_grass", 10), ("fern", 2), ("dandelion", 2), ("poppy", 2), ("dead_bush", 1)])
    for z in (6, 17, 30, 40):
        lamp(-4, z)
        lamp(4, z)
    lattice(1, xs=(-12, 0, 12), leaves=0.12,
            lanterns=[(-12, 9, 5), (12, 9, 6), (0, 15, 4), (-12, 27, 7), (12, 27, 5), (0, 33, 6), (-12, 39, 4), (12, 39, 7)])
    lights(1)
    mobs(1, "moisson_epouvantail", ((-7, 8), (8, 17), (-6, 31), (6, 36)))
    mobs(1, "moisson_rat", ((0, 22), (2, 41)))
    m.sound(0, F + 6, 10, [("item.elytra.flying", 0.1, 1.5), ("block.azalea_leaves.step", 0.5, 0.9)], radius=22, every=(150, 300),
            particle={"type": "FALLING_SPORE_BLOSSOM", "count": 3, "spread": (8, 2, 8), "speed": 0, "every": 20})
    m.sound(-14, F + 1, 13, [("block.water.ambient", 0.5, 1.2)], radius=10, every=(60, 140))
    m.sound(14, F + 1, 13, [("block.water.ambient", 0.5, 1.1)], radius=10, every=(60, 140))
    m.sound(-15, F + 3, 32, [("entity.chicken.ambient", 0.3, 1.0), ("entity.cow.ambient", 0.25, 0.9), ("block.wood.step", 0.4, 0.6)],
            radius=14, every=(160, 320))
    m.sound(15, F + 3, 32, [("entity.sheep.ambient", 0.3, 1.0), ("entity.pig.ambient", 0.25, 0.9)], radius=14, every=(180, 340))


# ======================================================================== room 2: the Golden Fields

def windmill(cx, cz):
    """9 x 9 at the base, 17 high, four sails on its south face."""
    for y in range(F + 1, F + 17):
        if y <= F + 6:
            rad, mat = 4, lambda *a: pick(STONE)
        elif y <= F + 12:
            rad, mat = 3, lambda x, yy, z: "spruce_log" if (x - cx) * (z - cz) == 0 and max(abs(x - cx), abs(z - cz)) >= 3 \
                else ("oak_planks" if (x + yy) % 5 else "spruce_planks")
        else:
            rad, mat = max(0, 3 - (y - F - 13)), lambda *a: "dark_oak_planks"
        disc(cx, cz, rad, y, mat)
    disc(cx, cz, 4.6, F + 7, "spruce_slab[type=bottom]", hollow=True)
    S(cx, F + 17, cz, "spruce_fence")
    B(cx - 1, F + 1, cz - 4, cx + 1, F + 3, cz - 4, lambda x, y, z: "dark_oak_planks" if (x + y) % 2 else "spruce_planks")
    S(cx, F + 4, cz - 4, "spruce_planks")
    S(cx + 2, F + 3, cz - 5, "lantern[hanging=true]")
    S(cx + 2, F + 4, cz - 5, "spruce_fence")
    for y in (F + 9, F + 11):
        S(cx - 3, y, cz, "glass")
        S(cx + 3, y, cz, "glass")
    B(cx, F + 10, cz - 5, cx, F + 10, cz - 4, "spruce_planks")
    for ang in (45, 135, 225, 315):
        a = math.radians(ang)
        for t in range(1, 8):
            x, y = cx + round(math.cos(a) * t), F + 10 + round(math.sin(a) * t)
            S(x, y, cz - 5, "spruce_fence" if t % 3 == 0 else "spruce_planks")
            if t >= 2:
                px = x + (1 if math.sin(a) > 0 else -1) * (1 if math.cos(a) > 0 else -1)
                if g.get(px, y, cz - 5) == "air":
                    S(px, y, cz - 5, "white_wool")
    keep(cx - 5, cz - 5, cx + 5, cz + 5)


def watchtower(x0, z0):
    """7 x 7, 13 high, on four legs (open underneath)."""
    x1, z1 = x0 + 6, z0 + 6
    for x in (x0, x1):
        for z in (z0, z1):
            B(x, F + 1, z, x, F + 11, z, "spruce_log")
    B(x0, F + 8, z0, x1, F + 8, z1, "spruce_planks")
    B(x0, F + 9, z0, x1, F + 9, z1, "spruce_fence")
    B(x0 + 1, F + 9, z0 + 1, x1 - 1, F + 9, z1 - 1, "air")
    for k in range(4):
        B(x0 - 1 + k, F + 12 + k // 2, z0 - 1 + k, x1 + 1 - k, F + 12 + k // 2, z1 + 1 - k,
          f"dark_oak_slab[type={'bottom' if k % 2 == 0 else 'top'}]")
    for x in (x0, x1):
        B(x, F + 5, z0 + 1, x, F + 5, z1 - 1, "spruce_fence")
    for z in (z0, z1):
        B(x0 + 1, F + 5, z, x1 - 1, F + 5, z, "spruce_fence")
    S(x0 + 3, F + 11, z0 + 3, "lantern[hanging=true]")
    hay(x0 + 1, z0 + 1, 1, F + 9)
    S(x0 + 5, F + 9, z0 + 5, "barrel[facing=up]")
    for x in (x0, x1):
        for z in (z0, z1):
            keep(x - 1, z - 1, x + 1, z + 1)


def broken_fence(x0, z0, x1, z1, kind="oak_fence"):
    for x in range(min(x0, x1), max(x0, x1) + 1):
        for z in range(min(z0, z1), max(z0, z1) + 1):
            if r.random() < 0.45 and g.get(x, F + 1, z) == "air" and not kept(x, z):
                S(x, F + 1, z, kind)


def room2():
    x0, x1, z0, z1, H = ROOMS[2]
    fl.ground(x0, z0, x1, z1, F, [("grass_block", 40), ("coarse_dirt", 25), ("dirt_path", 15), ("dirt", 10), ("podzol", 5)])
    path(-2, z0, 2, z1)
    keep(-2, z0, 2, z1)
    keep(-6, 54, 6, 65, room=2)
    keep(-6, 94, 6, 106, room=2)
    fields = (("wheat", -28, 61, -17, 74, 0.02), ("wheat", 17, 61, 28, 74, 0.0), ("wheat", -28, 79, -17, 92, 0.0),
              ("wheat", 17, 79, 28, 92, 0.04), ("carrots", -13, 66, -5, 77, 0.0), ("potatoes", 5, 66, 13, 77, 0.0),
              ("potatoes", -13, 81, -5, 92, 0.03), ("carrots", 5, 81, 13, 92, 0.0), ("beetroots", -29, 54, -18, 59, 0.05))
    for crop, a, b, c, d, wild in fields:
        crop_plot(a, b, c, d, crop, wild=wild)
        keep(a, b, c, d)
    gourd_plot(-15, 95, -8, 100, "pumpkin")
    gourd_plot(8, 95, 15, 100, "melon")
    keep(-15, 95, -8, 100)
    keep(8, 95, 15, 100)
    # paths between the fields
    for xa, xb in ((-16, -14), (14, 16)):
        path(xa, 60, xb, 93, [("dirt_path", 7), ("coarse_dirt", 3)])
        keep(xa, 60, xb, 93)
    for za, zb in ((93, 94), (78, 80)):
        path(-13, za, 13, zb if za == 93 else 80, [("dirt_path", 6), ("coarse_dirt", 3), ("grass_block", 1)])
    keep(-13, 78, 13, 80)
    # the canal between the wheat fields, footbridges over it
    canal(-30, 76, -17, 77, bridges=[(-24, 76, -22, 77)])
    canal(17, 76, 30, 77, bridges=[(22, 76, 24, 77)])
    path(-30, 75, -17, 75, [("stone_bricks", 3), ("mossy_stone_bricks", 2), ("cobblestone", 2)])
    path(17, 78, 30, 78, [("stone_bricks", 3), ("mossy_stone_bricks", 2), ("cobblestone", 2)])
    for sx in (-1, 1):
        keep(min(17 * sx, 30 * sx), 75, max(17 * sx, 30 * sx), 78)
    # two windmills in the far corners, a shed beside each, the watchtower by the way in
    windmill(-23, 101)
    windmill(23, 101)
    shed(-14, 103, -9, 107, 4, ridge="x", door="south", hayloft=False)
    shed(9, 103, 14, 107, 4, ridge="x", door="south", walls="spruce_planks", hayloft=False)
    watchtower(24, 53)
    # scarecrows, broken fences, signs, carved pumpkins, straw, crates, trees, wild flowers
    for x, z, f in ((-22, 67, "east"), (22, 68, "west"), (-23, 86, "east"), (23, 85, "west"), (-9, 71, "south"), (9, 87, "north")):
        scarecrow(x, z, f)
    broken_fence(-29, 60, -17, 60)
    broken_fence(17, 60, 28, 60)
    broken_fence(-29, 93, -17, 93)
    broken_fence(17, 93, 29, 93)
    broken_fence(-14, 65, -5, 65)
    broken_fence(5, 65, 14, 65)
    for x, z, rot in ((-7, 62, 6), (4, 79, 10), (-16, 59, 2), (16, 94, 12)):
        if g.get(x, F + 1, z) == "air":
            S(x, F + 1, z, f"oak_sign[rotation={rot}]")
    whole = (x0, z0, x1, z1)
    scatter(whole, 7, lambda x, z: hay(x, z, r.randint(1, 2)))
    scatter(whole, 4, lambda x, z: crate(x, z, ["wheat", "carrot", "potato", "bread"]))
    scatter(whole, 4, lambda x, z: S(x, F + 1, z, f"carved_pumpkin[facing={r.choice(['south', 'east', 'west'])}]"))
    scatter(whole, 3, lambda x, z: barrels(x, z, r.randint(1, 2), ["wheat", "beetroot"]))
    cart(-11, 56, "x", "hay")
    cart(9, 57, "x", "sacks")
    fl.tree(-29, 96, F + 1, "oak", "medium")
    fl.tree(29, 61, F + 1, "oak", "medium")
    fl.tree(-12, 55, F + 1, "birch", "medium")
    for x, z in ((14, 54), (-29, 78), (29, 95)):
        fruit_tree(x, z)
    plants(x0, z0, x1, z1, 420, [("short_grass", 10), ("fern", 2), ("dandelion", 3), ("poppy", 3), ("cornflower", 2),
                                 ("oxeye_daisy", 2), ("azure_bluet", 1), ("allium", 1)])
    for x, z in ((7, 60), (4, 72), (4, 84), (7, 96)):
        lamp(-x, z)
        lamp(x, z)
    for x, z in ((-16, 59), (16, 59), (-16, 94), (16, 94)):
        if g.get(x, F + 1, z) == "air":
            lamp(x, z, 3)
    lattice(2, xs=(-20, -10, 0, 10, 20), leaves=0.06,
            lanterns=[(-10, 55, 7), (10, 55, 8), (0, 67, 6), (-20, 67, 8), (20, 67, 7), (0, 85, 7), (-10, 79, 8),
                      (10, 79, 6), (-10, 91, 7), (10, 91, 8), (0, 103, 6)])
    lights(2)
    mobs(2, "moisson_meunier", ((0, 99),))
    mobs(2, "moisson_faucheur", ((-9, 60), (9, 62), (-21, 84), (21, 70)))
    mobs(2, "moisson_epouvantail", ((-22, 70), (9, 86), (-9, 73)))
    mobs(2, "moisson_rat", ((0, 79), (15, 88)))
    m.sound(0, F + 8, 70, [("item.elytra.flying", 0.16, 1.2), ("block.azalea_leaves.step", 0.5, 0.8)], radius=30, every=(110, 220),
            particle={"type": "FALLING_SPORE_BLOSSOM", "count": 4, "spread": (12, 3, 12), "speed": 0, "every": 20})
    m.sound(0, F + 3, 86, [("item.elytra.flying", 0.12, 1.0), ("block.grass.step", 0.5, 0.6)], radius=26, every=(140, 260),
            particle={"type": "SPORE_BLOSSOM_AIR", "count": 5, "spread": (12, 2, 10), "speed": 0, "every": 20})
    for sx in (-1, 1):
        m.sound(23 * sx, F + 10, 96, [("block.wooden_trapdoor.open", 0.5, 0.5), ("block.wood.step", 0.5, 0.5),
                                     ("block.wooden_door.close", 0.3, 0.5)], radius=18, every=(50, 110))
        m.sound(23 * sx, F + 1, 77, [("block.water.ambient", 0.5, 1.1)], radius=10, every=(60, 140))
    m.sound(0, F + 4, 100, [("entity.parrot.ambient", 0.2, 0.5), ("block.wood.break", 0.15, 0.5)],
            radius=18, every=(200, 380))


# ======================================================================== room 3: the Old Greenhouse

GH = (-15, 122, 15, 157)         # the glasshouse: 31 wide, 36 long, eaves 12, ridge 20


def greenhouse():
    gx0, gz0, gx1, gz1 = GH
    eave = F + 12

    def roof_y(x):
        return eave + 1 + (15 - abs(x)) // 2

    def glass():
        k = r.random()
        return "air" if k < 0.07 else ("lime_stained_glass" if k < 0.3 else ("green_stained_glass" if k < 0.36 else "glass"))

    # pillars every 5 blocks, glazing from 6 blocks up (every bay stays open underneath)
    for x in range(gx0, gx1 + 1):
        for z in range(gz0, gz1 + 1):
            edge_x, edge_z = x in (gx0, gx1), z in (gz0, gz1)
            if not (edge_x or edge_z):
                continue
            pillar = ((x - gx0) % 5 == 0 and edge_z) or ((z - gz0) % 5 == 0 and edge_x)
            top = eave if edge_x else roof_y(x) - 1
            for y in range(F + 1, top + 1):
                if pillar:
                    S(x, y, z, "oak_planks" if y > F + 5 and y % 4 == 0 else pick(BRICK) if y <= F + 5 else pick(
                        [("stone_bricks", 4), ("mossy_stone_bricks", 2), ("polished_andesite", 1)]))
                elif y == F + 6:
                    S(x, y, z, "stone_brick_slab[type=top]")
                elif y > F + 6:
                    S(x, y, z, glass())
            if pillar:
                S(x, F, z, "stone_bricks")
    # the pitched glass roof, a rib at each pillar line, the ridge beam
    for x in range(gx0 - 1, gx1 + 2):
        for z in range(gz0 - 1, gz1 + 2):
            y = roof_y(max(-15, min(15, x))) - (1 if abs(x) == 16 else 0)
            rib = (z - gz0) % 5 == 0 and gz0 <= z <= gz1
            if x == 0:
                S(x, y, z, "oak_planks")
            elif rib:
                S(x, y, z, "oak_planks")
            else:
                b = glass()
                if b != "air":
                    S(x, y, z, b)
    for z in range(gz0 + 5, gz1, 5):
        for x in (-8, 8):
            y = roof_y(x)
            B(x, y - 3, z, x, y - 1, z, "iron_chain[axis=y]")
            S(x, y - 4, z, "lantern[hanging=true]")
    for x in range(gx0, gx1 + 1, 5):
        for z in range(gz0, gz1 + 1, 5):
            if x in (gx0, gx1) or z in (gz0, gz1):
                keep(x, z, x, z)


def planter(x0, z0, x1, z1, table):
    """A raised bed: a border of stone and timber, rich soil a block above the ground, rare plants."""
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if x in (x0, x1) or z in (z0, z1):
                S(x, F + 1, z, pick([("stone_bricks", 3), ("mossy_stone_bricks", 3), ("spruce_planks", 2)]))
            else:
                S(x, F + 1, z, pick([("podzol", 3), ("dirt", 1), ("grass_block", 1)]))
                if r.random() < 0.8:
                    fl.plant(x, F + 2, z, pick(table))
    keep(x0 - 1, z0 - 1, x1 + 1, z1 + 1)


def room3():
    x0, x1, z0, z1, H = ROOMS[3]
    fl.ground(x0, z0, x1, z1, F, [("grass_block", 30), ("dirt", 14), ("podzol", 12), ("coarse_dirt", 12),
                                  ("mossy_cobblestone", 10), ("mossy_stone_bricks", 8), ("clay", 4), ("gravel", 10)])
    path(-2, z0, 2, z1, [("dirt_path", 6), ("coarse_dirt", 2), ("mossy_cobblestone", 2)])
    for xa, xb in ((-10, -7), (7, 10)):
        path(xa, 119, xb, 160, [("dirt_path", 6), ("coarse_dirt", 2), ("mossy_cobblestone", 2)])
        keep(xa, 119, xb, 160)
    for za, zb in ((117, 120), (159, 162)):
        path(-10, za, 10, zb, [("dirt_path", 6), ("coarse_dirt", 2), ("mossy_cobblestone", 2)])
    keep(-2, z0, 2, z1)
    keep(-5, 124, 5, 135, room=3)
    keep(-5, 143, 5, 155, room=3)
    greenhouse()
    # the basins: one in the middle, one against each side wall under a cascade, canals between them
    basin(-4, 136, 4, 142)
    S(0, F, 139, "mossy_stone_bricks")
    S(0, F + 1, 139, "mossy_stone_brick_wall")
    S(0, F + 2, 139, "flowering_azalea_leaves[persistent=true]")
    keep(-6, 135, 6, 143)
    for sx in (-1, 1):
        a, b = sorted((25 * sx, 21 * sx))
        basin(a, 137, b, 141)
        keep(a - 1, 136, b + 1, 142)
        ca, cb = sorted((20 * sx, 6 * sx))
        canal(ca + (1 if sx > 0 else 0), 139, cb - (1 if sx < 0 else 0), 139, bridges=[(min(7 * sx, 10 * sx), 139, max(7 * sx, 10 * sx), 139),
                                                                                    (min(16 * sx, 18 * sx), 139, max(16 * sx, 18 * sx), 139)])
        # the cascade: a spout high in the wall, falling into the basin
        wx = 26 * sx
        B(wx, F + 9, 138, wx, F + 11, 140, "mossy_stone_bricks")
        S(wx, F + 10, 139, "dark_oak_trapdoor[half=top,facing=north]")
        S(25 * sx, F + 10, 139, "water")
        g.falls.add((25 * sx, F + 10, 139))
        S(25 * sx, F + 11, 139, "mossy_stone_brick_slab[type=bottom]")
        for dz in (-1, 1):
            S(25 * sx, F + 10, 139 + dz, "mossy_stone_brick_wall")
        m.sound(24 * sx, F + 3, 139, [("block.water.ambient", 0.9, 1.0), ("block.water.ambient", 0.6, 0.8)],
                radius=14, every=(30, 70))
    # jungle trees along the sides of the glasshouse (8 to 12 high), taller ones in the corners of the hall
    for x, z in ((-12, 125), (12, 126), (-12, 153), (12, 154)):
        fl.tree(x, z, F + 1, "jungle", "medium")
        keep(x - 1, z - 1, x + 1, z + 1)
    for x, z in ((-22, 118), (21, 118), (-22, 161), (21, 161)):
        fl.tree(x, z, F + 1, "jungle", "big")
        keep(x - 1, z - 1, x + 2, z + 2)
    for x, z in ((-20, 129), (20, 150)):
        fruit_tree(x, z, "oak", "medium")
    for x, z in ((20, 128), (-20, 151)):
        fl.bush(x, z, F + 1, "jungle", big=True)
        S(x, F + 1, z, "flowering_azalea_leaves[persistent=true]")
    # raised beds of rare plants inside, plots of odd crops outside
    rare = [("torchflower", 2), ("pitcher_plant", 1), ("blue_orchid", 3), ("allium", 2), ("lily_of_the_valley", 2),
            ("large_fern", 2), ("fern", 2), ("pink_tulip", 2), ("red_tulip", 1)]
    herbs = [("fern", 4), ("large_fern", 2), ("poppy", 2), ("azure_bluet", 2), ("oxeye_daisy", 2),
             ("orange_tulip", 2), ("white_tulip", 2), ("cornflower", 2)]
    planter(-14, 129, -11, 134, rare)
    planter(11, 129, 14, 134, herbs)
    planter(-14, 144, -11, 149, herbs)
    planter(11, 144, 14, 149, rare)
    crop_plot(-23, 145, -18, 148, "beetroots")
    crop_plot(18, 131, 23, 134, "carrots", wild=0.15)
    crop_plot(-23, 131, -18, 134, "potatoes", wild=0.3)
    crop_plot(18, 145, 23, 148, "wheat", ages=(2, 7))
    for a, b, c, d in ((-23, 145, -18, 148), (18, 131, 23, 134), (-23, 131, -18, 134), (18, 145, 23, 148)):
        keep(a, b, c, d)
    # shelves of flower pots on the walls, gardening tables, composters, barrels of compost
    pots = ["potted_fern", "potted_allium", "potted_blue_orchid", "potted_red_tulip", "potted_azalea_bush",
            "potted_flowering_azalea_bush", "potted_dandelion", "potted_oxeye_daisy"]
    for z in list(range(117, 134, 2)) + list(range(146, 164, 2)):
        for sx in (-1, 1):
            if r.random() < 0.75:
                x = 25 * sx
                S(x, F + 3, z, f"spruce_trapdoor[half=top,facing={'east' if sx < 0 else 'west'},open=false]")
                S(x, F + 4, z, r.choice(pots))
    for x in range(-23, 24, 2):
        for z in (115, 165):
            if abs(x) > 6 and r.random() < 0.6:
                S(x, F + 3, z, "spruce_trapdoor[half=top,facing=north,open=false]")
                S(x, F + 4, z, r.choice(pots))
    for x, z in ((-24, 122), (24, 123), (-24, 156), (24, 157)):
        S(x, F + 1, z, "spruce_fence")
        S(x, F + 2, z, "spruce_pressure_plate")
        S(x, F + 1, z + 1, "composter[level=6]")
        barrels(x, z + 2, 2, ["bone_meal", "wheat_seeds"])
        tool(x + (0.6 if x < 0 else 0.4), F + 1.7, z - 0.6, "iron_shovel", yaw=0, roll=40)
    ring = (x0, z0, x1, z1)
    scatter(ring, 3, lambda x, z: barrels(x, z, 1, ["bone_meal", "wheat_seeds", "beetroot_seeds"]))
    scatter(ring, 3, lambda x, z: S(x, F + 1, z, f"composter[level={r.randint(3, 8)}]"))
    scatter(ring, 8, lambda x, z: (S(x, F, z, "grass_block"), S(x, F + 1, z, r.choice(["azalea", "flowering_azalea", "mangrove_roots"]))))
    # roots through the walls, moss and leaves hanging high
    for _ in range(26):
        sx = r.choice((-1, 1))
        along_x = r.random() < 0.5
        u = r.randint(-22, 22) if along_x else r.randint(117, 163)
        y = F + r.randint(1, 3)
        for k in range(r.randint(2, 4)):
            p = (u + k * r.choice((0, 1)), y + k, (114 if sx < 0 else 166)) if along_x else ((-26 if sx < 0 else 26), y + k, u)
            S(*p, "mangrove_roots")
    for _ in range(60):
        x, z = r.randint(x0, x1), r.choice((z0, z1)) if r.random() < 0.5 else r.randint(z0, z1)
        if z not in (z0, z1):
            x = r.choice((x0, x1))
        y = F + r.randint(9, 20)
        if g.get(x, y, z) == "air":
            S(x, y, z, r.choice(["jungle_leaves[persistent=true]", "oak_leaves[persistent=true]", "mossy_cobblestone"]))
    plants(x0, z0, x1, z1, 500, [("short_grass", 8), ("fern", 6), ("large_fern", 3), ("tall_grass", 2), ("blue_orchid", 1),
                                 ("allium", 1), ("pink_tulip", 1), ("azure_bluet", 1), ("moss_carpet", 3)])
    lattice(3, beam="oak_planks", step=12, xs=(-20, 20), leaves=0.2, lanterns=[(-20, 117, 9), (20, 129, 10), (-20, 153, 9), (20, 141, 8)])
    lights(3)
    mobs(3, "moisson_lapin", ((-3, 127), (4, 131), (-8, 147)))
    mobs(3, "moisson_poule", ((8, 126), (3, 150), (-4, 153)))
    mobs(3, "moisson_rat", ((-19, 140), (19, 143)))
    m.sound(0, F + 8, 130, [("block.azalea_leaves.step", 0.6, 0.8), ("entity.parrot.ambient", 0.2, 1.7), ("block.moss.step", 0.5, 0.7)],
            radius=24, every=(110, 230), particle={"type": "SPORE_BLOSSOM_AIR", "count": 6, "spread": (10, 4, 10), "speed": 0, "every": 20})
    m.sound(0, F + 8, 150, [("block.azalea_leaves.step", 0.6, 0.7), ("entity.bee.loop", 0.12, 1.3), ("entity.parrot.ambient", 0.2, 2.0)],
            radius=24, every=(120, 250), particle={"type": "FALLING_SPORE_BLOSSOM", "count": 5, "spread": (10, 3, 10), "speed": 0, "every": 20})
    m.sound(0, F + 1, 139, [("block.water.ambient", 0.6, 1.2), ("entity.frog.ambient", 0.25, 1.0)], radius=12, every=(70, 160))


# ======================================================================== room 4: the Great Harvests

def great_barn():
    """23 x 17, ridge 21: the way on goes through it (its front door is the door of the room)."""
    bx0, bx1, bz0, bz1 = -11, 11, BARN_DOOR_Z, 232

    def wall(x, y, z):
        if y <= F + 3:
            return pick(STONE)
        return "spruce_planks" if (x + z + y) % 6 else "oak_planks"
    B(bx0, F + 1, bz0, bx1, F + 8, bz1, wall)
    B(bx0 + 1, F + 1, bz0 + 1, bx1 - 1, F + 20, bz1, "air")
    B(bx0 + 1, F, bz0, bx1 - 1, F, bz1, lambda x, y, z: pick([("dirt_path", 5), ("coarse_dirt", 3), ("dirt", 1)]) if abs(x) <= 6 else "spruce_planks")
    for z in range(bz0, bz1 + 1, 4):
        for x in (bx0, bx1):
            B(x, F + 1, z, x, F + 8, z, "spruce_log")
    for x in range(bx0, bx1 + 1, 4):
        B(x, F + 1, bz0, x, F + 8, bz0, "spruce_log") if abs(x) > 4 else None
    # the gable roof along z, the front gable with its loft door
    for k in range(13):
        y = F + 9 + k
        xw, xe = bx0 - 1 + k, bx1 + 1 - k
        if xw >= xe:
            B(xw, y, bz0 - 1, xw, y, bz1, "dark_oak_slab[type=bottom]")
            break
        B(xw, y, bz0 - 1, xw, y, bz1, "dark_oak_stairs[facing=east]")
        B(xe, y, bz0 - 1, xe, y, bz1, "dark_oak_stairs[facing=west]")
        B(xw + 1, y, bz0, xe - 1, y, bz0, lambda xx, yy, zz: "spruce_log" if xx % 4 == 0 else ("spruce_planks" if (xx + yy) % 5 else "oak_planks"))
    B(bx0, F + 9, bz0, bx1, F + 9, bz0, "spruce_planks")
    B(-1, F + 11, bz0, 1, F + 13, bz0, "hay_block[axis=y]")
    B(-2, F + 14, bz0, 2, F + 14, bz0, "spruce_planks")
    S(0, F + 15, bz0 - 1, "spruce_fence")
    S(0, F + 14, bz0 - 1, "iron_chain[axis=y]")
    S(0, F + 13, bz0 - 1, "lantern[hanging=true]")
    # tie beams and lanterns, lofts over the storage on both sides
    for z in range(bz0 + 4, bz1, 4):
        B(bx0 + 1, F + 9, z, bx1 - 1, F + 9, z, "spruce_planks")
        for x in (-5, 5):
            S(x, F + 8, z, "iron_chain[axis=y]")
            S(x, F + 7, z, "lantern[hanging=true]")
    for sx in (-1, 1):
        xa, xb = sorted((7 * sx, 10 * sx))
        B(xa, F + 5, bz0 + 1, xb, F + 5, bz1, "spruce_slab[type=top]")
        B(7 * sx, F + 6, bz0 + 1, 7 * sx, F + 6, bz1, "spruce_fence")
        for z in range(bz0 + 4, bz1, 4):
            B(7 * sx, F + 1, z, 7 * sx, F + 4, z, "spruce_log")
        for z in range(bz0 + 1, bz1 + 1):
            if r.random() < 0.7:
                hay(r.choice((9, 10)) * sx, z, r.randint(1, 2), F + 6)
            x = r.choice((8, 9, 10)) * sx
            k = r.random()
            if g.get(x, F + 1, z) != "air":
                continue
            if k < 0.3:
                hay(x, z, r.randint(1, 3))
            elif k < 0.45:
                barrels(x, z, r.randint(1, 2), ["wheat", "potato", "carrot"])
            elif k < 0.6:
                sacks(x, z, r.randint(1, 3))
            elif k < 0.7:
                crate(x, z, ["apple", "carrot", "beetroot", "bread"])
        tool(10 * sx + (0.4 if sx < 0 else 0.6), F + 3.2, bz0 + 6.5, "iron_hoe", yaw=90 if sx < 0 else -90)
        tool(10 * sx + (0.4 if sx < 0 else 0.6), F + 3.2, bz0 + 10.5, "trident", yaw=90 if sx < 0 else -90, roll=10)
    for z in (bz0 + 3, bz0 + 9, bz0 + 14):
        for x in (-4, 4):
            S(x, F + 5, z, "light[level=13]")
    # the great door
    B(-3, F + 1, bz0, 3, F + 6, bz0, door_block)
    B(-4, F + 1, bz0, -4, F + 7, bz0, "dark_oak_log")
    B(4, F + 1, bz0, 4, F + 7, bz0, "dark_oak_log")
    B(-4, F + 7, bz0, 4, F + 7, bz0, "dark_oak_planks")
    door("porte_recoltes", (-3, F + 1, bz0), (3, F + 6, bz0), room="4")
    for sx in (-1, 1):
        S(5 * sx, F + 5, bz0 - 1, "spruce_fence")
        S(5 * sx, F + 4, bz0 - 1, "lantern[hanging=true]")
    keep(bx0 - 1, bz0 - 2, bx1 + 1, bz1)


def silo(cx, cz, h=14):
    for y in range(F + 1, F + h - 2):
        disc(cx, cz, 3, y, lambda x, yy, z: pick(BRICK) if yy <= F + 2 else ("iron_block" if (yy - F) % 4 == 0 else
                                                                               ("spruce_planks" if (x + z) % 2 else "oak_planks")))
    for k in range(3):
        disc(cx, cz, 3 - k, F + h - 2 + k, "dark_oak_planks" if k else "dark_oak_slab[type=bottom]")
    disc(cx, cz, 3.6, F + h - 2, "dark_oak_slab[type=bottom]", hollow=True)
    S(cx, F + h + 1, cz, "spruce_fence")
    B(cx, F + 1, cz - 3, cx, F + 2, cz - 3, "dark_oak_planks")
    B(cx + 3, F + 5, cz, cx + 3, F + h - 3, cz, "iron_bars")
    keep(cx - 4, cz - 4, cx + 4, cz + 4)


def workshop(x0, z0, x1, z1, open_side="west"):
    """A lean-to open on one side: posts, a slab roof, benches and tools under it."""
    for x in (x0, x1):
        for z in (z0, z1):
            B(x, F + 1, z, x, F + 3, z, "spruce_log")
    B(x0 - 1, F + 4, z0 - 1, x1 + 1, F + 4, z1 + 1, "spruce_slab[type=bottom]")
    B(x0, F + 4, z0, x1, F + 4, z1, "dark_oak_slab[type=bottom]")
    back = x1 if open_side == "west" else x0
    B(back, F + 1, z0, back, F + 3, z1, lambda x, y, z: "spruce_planks" if y != F + 2 or (z - z0) % 2 else "oak_planks")
    row = back + (-1 if open_side == "west" else 1)
    kit = ["crafting_table", "smithing_table", "barrel[facing=up]", "fletching_table", "composter[level=4]", "grindstone[face=floor]",
           "cartography_table", "hay_block[axis=y]"]
    for z in range(z0 + 1, z1):
        S(row, F + 1, z, kit[(z - z0 - 1) % len(kit)])
    tool(row + 0.5, F + 2.4, z0 + 1.5, "iron_axe", yaw=90 if open_side == "west" else -90, roll=20)
    tool(row + 0.5, F + 2.4, z1 - 0.5, "shears", yaw=90 if open_side == "west" else -90, roll=0)
    S((x0 + x1) // 2, F + 3, (z0 + z1) // 2, "lantern[hanging=true]")
    keep(x0 - 1, z0 - 1, x1 + 1, z1 + 1)


def room4():
    x0, x1, z0, z1, H = ROOMS[4]
    fl.ground(x0, z0, x1, z1, F, [("coarse_dirt", 30), ("grass_block", 28), ("dirt", 12), ("dirt_path", 12), ("podzol", 8),
                                  ("gravel", 6), ("mossy_cobblestone", 4)])
    path(-3, z0, 3, BARN_DOOR_Z)
    path(x0, 189, x1, 191, [("dirt_path", 6), ("coarse_dirt", 3), ("gravel", 1)])
    path(-19, 192, -17, 215, [("dirt_path", 6), ("coarse_dirt", 3)])
    path(17, 192, 19, 215, [("dirt_path", 6), ("coarse_dirt", 3)])
    path(-19, 203, 19, 205, [("dirt_path", 5), ("coarse_dirt", 3), ("podzol", 1)])
    keep(-3, z0, 3, BARN_DOOR_Z - 1, room=4)
    keep(x0, 189, x1, 191)
    keep(-19, 192, -17, 215)
    keep(17, 192, 19, 215)
    keep(-15, 203, 15, 214, room=4)
    great_barn()
    silo(-17, 224)
    silo(17, 224)
    # the fields: wheat (the eastern one on a terrace), carrots, potatoes, pumpkins, beetroots
    crop_plot(-30, 177, -17, 188, "wheat")
    keep(-30, 177, -17, 188)
    B(16, F + 1, 176, 31, F + 1, 189, lambda *a: pick([("cobblestone", 4), ("mossy_cobblestone", 3), ("stone_bricks", 2)]))
    B(17, F + 1, 177, 30, F + 1, 188, "dirt")
    crop_plot(17, 177, 30, 188, "wheat", y=F + 1)
    B(15, F + 1, 181, 15, F + 1, 184, "cobblestone_stairs[facing=east]")
    B(21, F + 1, 190, 24, F + 1, 190, "cobblestone_stairs[facing=north]")
    B(21, F + 1, 175, 24, F + 1, 175, "cobblestone_stairs[facing=south]")
    keep(14, 174, 32, 191)
    crop_plot(-30, 193, -21, 202, "carrots", wild=0.03)
    crop_plot(21, 193, 30, 202, "potatoes")
    gourd_plot(-15, 193, -7, 200, "pumpkin")
    crop_plot(7, 193, 15, 200, "beetroots", wild=0.05)
    for a, b, c, d in ((-30, 193, -21, 202), (21, 193, 30, 202), (-15, 193, -7, 200), (7, 193, 15, 200)):
        keep(a, b, c, d)
    for x, z, f in ((-24, 182, "east"), (24, 183, "west"), (-26, 197, "south"), (11, 196, "south")):
        if f == "west":
            B(x, F + 2, z, x, F + 3, z, "spruce_fence")
            S(x - 1, F + 3, z, "spruce_fence")
            S(x + 1, F + 3, z, "spruce_fence")
            S(x, F + 4, z, "carved_pumpkin[facing=west]")
        else:
            scarecrow(x, z, f)
    # the orchard (north-west), the workshops and the well (north-east)
    for x, z in ((-29, 207), (-24, 210), (-29, 214), (-24, 218), (-29, 222), (-24, 227), (-29, 230)):
        fruit_tree(x, z, "oak", "small" if (x + z) % 2 else "medium")
        keep(x - 1, z - 1, x + 1, z + 1)
    scatter((-31, 206, -21, 231), 3, lambda x, z: crate(x, z, ["apple", "apple", "golden_apple"]))
    workshop(27, 207, 31, 213, "west")
    workshop(27, 219, 31, 226, "west")
    well(23, 216)
    well(-12, 176)
    keep(21, 214, 25, 218)
    keep(-14, 174, -10, 178)
    # small mounds of earth at the edges
    for mx, mz in ((-31, 174), (31, 204), (-9, 231), (9, 231), (31, 231), (-31, 204)):
        for dx in range(-2, 3):
            for dz in range(-2, 3):
                if abs(dx) + abs(dz) <= 2 and x0 <= mx + dx <= x1 and z0 <= mz + dz <= z1 and g.get(mx + dx, F + 1, mz + dz) == "air" \
                        and not kept(mx + dx, mz + dz):
                    S(mx + dx, F + 1, mz + dz, "grass_block" if abs(dx) + abs(dz) < 2 else "coarse_dirt")
                    if abs(dx) + abs(dz) == 0:
                        S(mx, F + 2, mz, "grass_block")
    # hay, carts, crates, stacked barrels, composters, sacks, heaps of harvest
    cart(-9, 180, "x", "hay")
    cart(8, 178, "z", "pumpkin")
    cart(21, 206, "x", "sacks")
    cart(-21, 204, "z", "hay")
    whole = (x0, z0, x1, 215)
    scatter(whole, 13, lambda x, z: hay(x, z, r.randint(1, 2)))
    scatter(whole, 5, lambda x, z: crate(x, z, ["wheat", "carrot", "potato", "beetroot", "bread", "pumpkin_pie"]))
    scatter(whole, 4, lambda x, z: (barrels(x, z, 2, ["wheat", "apple"]), barrels(x + 1, z, 1)), w=2)
    scatter(whole, 4, lambda x, z: S(x, F + 1, z, f"composter[level={r.randint(2, 8)}]"))
    scatter(whole, 5, lambda x, z: (sacks(x, z, 2), sacks(x + 1, z, 1)), w=2)
    scatter(whole, 4, lambda x, z: (S(x, F + 1, z, r.choice(["pumpkin", "melon"])), S(x + 1, F + 1, z, "hay_block[axis=y]"),
                                    g.item_display(x + 0.5, F + 2.3, z + 0.5, r.choice(["carrot", "beetroot", "potato"]), yaw=r.uniform(0, 360), scale=0.7)), w=2)
    scatter((12, 217, 31, 231), 3, lambda x, z: sacks(x, z, r.randint(1, 3)))
    plants(x0, z0, x1, z1, 380, [("short_grass", 10), ("fern", 3), ("dead_bush", 2), ("dandelion", 1), ("poppy", 2)])
    for z in (178, 187, 196):
        lamp(-4, z)
        lamp(4, z)
    for x, z in ((-16, 206), (16, 206), (-20, 190), (20, 192)):
        if g.get(x, F + 1, z) == "air":
            lamp(x, z, 3)
    # the roof: a lattice, boarded over along the walls (a darker hall)
    lattice(4, beam="dark_oak_planks", xs=(-22, -11, 0, 11, 22),
            lanterns=[(-11, 175, 9), (11, 181, 10), (0, 187, 8), (-22, 193, 10), (22, 199, 9), (0, 199, 9), (-11, 205, 10), (11, 211, 9)])
    y = F + H + 2
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = min(x - x0, x1 - x, z - z0, z1 - z)
            if edge <= 8 and r.random() < (0.97 if edge <= 6 else 0.5) and g.get(x, y, z) == "air":
                S(x, y, z, "dark_oak_slab[type=bottom]" if r.random() < 0.8 else "spruce_slab[type=bottom]")
    lights(4, level=12)
    mobs(4, "moisson_grand_epouvantail", ((0, 209),))
    mobs(4, "moisson_faucheur", ((-10, 185), (10, 191), (-13, 207), (13, 208)))
    mobs(4, "moisson_epouvantail", ((-23, 190), (22, 191), (0, 182)))
    mobs(4, "moisson_rat", ((-5, 197), (18, 212)))
    m.sound(0, F + 9, 185, [("item.elytra.flying", 0.14, 0.9), ("block.wood.break", 0.2, 0.5)], radius=28, every=(120, 240),
            particle={"type": "WHITE_ASH", "count": 8, "spread": (12, 4, 12), "speed": 0, "every": 20})
    m.sound(0, F + 6, 212, [("block.wooden_door.close", 0.3, 0.5), ("block.chest.open", 0.3, 0.5), ("block.wood.step", 0.5, 0.5)],
            radius=18, every=(140, 280))
    m.sound(-24, F + 3, 196, [("item.hoe.till", 0.5, 0.7), ("block.crop.break", 0.6, 0.8), ("block.grass.break", 0.4, 0.7)],
            radius=14, every=(120, 260))
    m.sound(24, F + 3, 184, [("item.hoe.till", 0.5, 0.6), ("block.crop.break", 0.6, 0.7)], radius=14, every=(140, 280),
            particle={"type": "FALLING_SPORE_BLOSSOM", "count": 3, "spread": (6, 2, 6), "speed": 0, "every": 20})
    m.sound(-26, F + 5, 218, [("block.azalea_leaves.step", 0.5, 0.8), ("entity.bee.loop", 0.1, 1.2)], radius=14, every=(150, 300))


# ======================================================================== room 5: the Sanctuary of the Earth

def ritual_garden(x0, z0, stage):
    """9 x 9, a stone border flush with the ground, four posts with lanterns: one stage of what grows."""
    x1, z1 = x0 + 8, z0 + 8
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            S(x, F, z, pick(BRICK) if edge else "grass_block")
    for x in (x0, x1):
        for z in (z0, z1):
            S(x, F + 1, z, "mossy_stone_brick_wall")
            S(x, F + 2, z, "lantern")
    ix0, iz0, ix1, iz1 = x0 + 1, z0 + 1, x1 - 1, z1 - 1
    cx, cz = x0 + 4, z0 + 4
    if stage == "germination":
        for x in range(ix0, ix1 + 1):
            for z in range(iz0, iz1 + 1):
                S(x, F, z, "farmland[moisture=7]")
                S(x, F + 1, z, r.choice(["wheat[age=0]", "wheat[age=1]", "beetroots[age=0]", "carrots[age=1]", "air"]))
        S(cx, F, cz, "water")
        S(cx, F - 1, cz, "clay")
        S(cx, F + 1, cz, "lily_pad")
        for dx, dz in ((-2, -2), (2, 2)):
            S(cx + dx, F, cz + dz, "grass_block")
            S(cx + dx, F + 1, cz + dz, "oak_sapling")
    elif stage == "growth":
        for x in range(ix0, ix1 + 1):
            for z in range(iz0, iz1 + 1):
                if (x + z) % 2:
                    S(x, F, z, "farmland[moisture=7]")
                    S(x, F + 1, z, r.choice(["wheat[age=3]", "wheat[age=4]", "potatoes[age=3]", "carrots[age=3]"]))
                else:
                    fl.plant(x, F + 1, z, r.choice(["fern", "short_grass", "sweet_berry_bush[age=1]", "large_fern"]))
        S(cx, F, cz, "grass_block")
        S(cx, F + 1, cz, "air")
        fl.tree(cx, cz, F + 1, "oak", "small")
    elif stage == "flowering":
        table = ["poppy", "dandelion", "allium", "pink_tulip", "red_tulip", "orange_tulip", "white_tulip", "cornflower",
                 "oxeye_daisy", "lilac", "peony", "rose_bush", "blue_orchid", "sunflower"]
        for x in range(ix0, ix1 + 1):
            for z in range(iz0, iz1 + 1):
                if r.random() < 0.85:
                    fl.plant(x, F + 1, z, r.choice(table))
        S(cx, F, cz, "water")
        S(cx, F - 1, cz, "clay")
        S(cx, F + 1, cz, "lily_pad")
        for dx, dz in ((-2, 2), (2, -2)):
            S(cx + dx, F + 1, cz + dz, "flowering_azalea")
    else:
        for x in range(ix0, ix1 + 1):
            for z in range(iz0, iz1 + 1):
                S(x, F, z, "farmland[moisture=7]")
                S(x, F + 1, z, "wheat[age=7]")
        for dx, dz, b in ((0, 0, "hay_block[axis=y]"), (1, 0, "hay_block[axis=y]"), (0, 1, "pumpkin"), (-2, -2, "melon"),
                          (2, 2, "composter[level=8]"), (-2, 2, "barrel[facing=up]"), (2, -2, "pumpkin")):
            S(cx + dx, F, cz + dz, "dirt")
            S(cx + dx, F + 1, cz + dz, b)
        S(cx, F + 2, cz, "hay_block[axis=y]")
        g.item_display(cx - 1.5, F + 2.3, cz + 2.5, "bread", yaw=40, scale=0.7)


def room5():
    x0, x1, z0, z1, H = ROOMS[5]
    cz = (z0 + z1) // 2
    fl.ground(x0, z0, x1, z1, F, [("stone_bricks", 22), ("mossy_stone_bricks", 14), ("grass_block", 26), ("coarse_dirt", 16),
                                  ("mossy_cobblestone", 10), ("cracked_stone_bricks", 6), ("dirt", 6)])
    # a second skin behind the walls, for the niches
    B(x0 - 2, F + 1, z0 - 1, x0 - 2, F + 8, z1 + 1, "stone_bricks")
    B(x1 + 2, F + 1, z0 - 1, x1 + 2, F + 8, z1 + 1, "stone_bricks")
    B(x0 - 1, F + 1, z1 + 2, x1 + 1, F + 16, z1 + 2, "stone_bricks")
    niche_items = ["iron_hoe", "wheat", "golden_carrot", "iron_shovel", "bread", "shears", "wheat_seeds", "beetroot"]
    for sx in (-1, 1):
        wx = x0 - 1 if sx < 0 else x1 + 1
        for i, z in enumerate(range(z0 + 9, z1 - 6, 9)):
            B(wx, F + 2, z - 1, wx, F + 5, z + 1, "air")
            B(wx, F + 1, z - 1, wx, F + 1, z + 1, "chiseled_stone_bricks")
            S(wx, F + 6, z, "chiseled_stone_bricks")
            kind = i % 4
            if kind == 0:
                S(wx, F + 2, z, "lantern")
                S(wx, F + 2, z - 1, "potted_poppy")
                S(wx, F + 2, z + 1, "potted_dandelion")
            elif kind == 1:
                S(wx, F + 2, z, "hay_block[axis=y]")
                g.item_display(wx + 0.5, F + 3.4, z + 0.5, r.choice(niche_items), yaw=90 * sx, scale=0.9)
                S(wx, F + 5, z, "lantern[hanging=true]")
            elif kind == 2:
                S(wx, F + 2, z - 1, "barrel[facing=up]")
                S(wx, F + 2, z + 1, "composter[level=7]")
                tool(wx + 0.5, F + 3.3, z + 0.5, "iron_hoe", yaw=90 * sx)
                S(wx, F + 5, z, "lantern[hanging=true]")
            else:
                S(wx, F + 2, z, "farmland[moisture=7]")
                S(wx, F + 3, z, "wheat[age=7]")
                S(wx, F + 2, z - 1, "lantern")
                S(wx, F + 2, z + 1, "lantern")
    # four great pillars in the corners, roots and leaves round them, arches between them along the walls
    corners = [(x0, z0), (x1 - 4, z0), (x0, z1 - 4), (x1 - 4, z1 - 4)]
    for px, pz in corners:
        B(px, F + 1, pz, px + 4, F + H - 1, pz + 4,
          lambda x, y, z: "chiseled_stone_bricks" if (y - F) in (1, 12, H - 1) else pick(BRICK))
        for _ in range(26):
            x, z = px + r.randint(-1, 5), pz + r.randint(-1, 5)
            if not (x0 <= x <= x1 and z0 <= z <= z1) or (px <= x <= px + 4 and pz <= z <= pz + 4):
                continue
            hgt = r.randint(1, 4)
            for y in range(F + 1, F + 1 + hgt):
                if g.get(x, y, z) == "air":
                    S(x, y, z, "mangrove_roots" if y < F + hgt else r.choice(["mangrove_roots", "oak_leaves[persistent=true]"]))
        for _ in range(30):
            x, z = px + r.randint(-1, 5), pz + r.randint(-1, 5)
            y = F + r.randint(8, H - 2)
            if x0 <= x <= x1 and z0 <= z <= z1 and g.get(x, y, z) == "air":
                S(x, y, z, r.choice(["oak_leaves[persistent=true]", "mossy_cobblestone", "oak_leaves[persistent=true]"]))
    for u in range(5, 50):
        d = min(u - 4, 50 - u)
        bottom = F + 13 + min(d, 5) + (1 if d > 12 else 0)
        for x, z in ((x0 + u, z0), (x0 + u, z1), (x0, z0 + u), (x1, z0 + u)):
            B(x, bottom, z, x, F + H - 1, z, lambda xx, y, zz: "chiseled_stone_bricks" if y == bottom else pick(BRICK))
    # the walk from the gate to the gap of the pen, between two low walls
    path(-3, z0, 3, PEN[1] - 1, [("dirt_path", 5), ("stone_bricks", 3), ("mossy_stone_bricks", 2)])
    for sx in (-1, 1):
        B(4 * sx, F + 1, z0, 4 * sx, F + 1, PEN[1] - 1, "mossy_stone_brick_wall")
        B(4 * sx, F + 2, z0, 4 * sx, CURTAIN, PEN[1] - 1, "barrier")
        for z in (z0 + 1, z0 + 5):
            S(4 * sx, F + 2, z, "lantern")
    # the pen of the first version, block for block
    arena = {}
    for line in open(ARENA):
        if line.startswith("#") or not line.strip():
            continue
        x, y, z, b = line.split(maxsplit=3)
        x, y, z = int(x), int(y), int(z)
        if (x, z) in ((-3, 0), (3, 0)):  # the composter and barrel of the former start
            continue
        arena[(x, y, z + DZ)] = b.strip()
    B(PEN[0], F + 1, PEN[1], PEN[2], F + 5, PEN[3], "air")
    for p, b in arena.items():
        S(*p, b)
    B(-2, F + 1, PEN[1], 2, F + 2, PEN[1], "air")          # the gap in its southern fence
    for x in range(PEN[0], PEN[2] + 1):
        for z in range(PEN[1], PEN[3] + 1):
            if abs(x) == 16 or z in (PEN[1], PEN[3]):
                if z == PEN[1] and abs(x) <= 2:
                    continue
                for y in range(F + 2, CURTAIN + 1):
                    if g.get(x, y, z) == "air":
                        S(x, y, z, "barrier")
    B(-2, F + 1, PEN[3], 2, F + 1, PEN[3], EXIT_GATE)
    door("porte_sortie", (-2, F + 1, PEN[3]), (2, F + 1, PEN[3]), room="5")
    # four ritual gardens round the field: germination, growth, flowering, harvest
    ritual_garden(-26, 250, "germination")
    ritual_garden(18, 250, "growth")
    ritual_garden(-26, 276, "flowering")
    ritual_garden(18, 276, "harvest")
    # west, between the gardens: the burrow and the food stores; east: the giant wheel
    bx, bz = -23, cz
    for dx in range(-3, 4):
        for dz in range(-5, 6):
            hgt = 4 - (abs(dx) * 2 + abs(dz)) // 2
            for y in range(F + 1, F + 1 + max(0, hgt)):
                S(bx + dx, y, bz + dz, "grass_block" if y == F + hgt else "dirt")
    B(bx + 1, F + 1, bz - 1, bx + 3, F + 2, bz + 1, "air")
    B(bx, F + 1, bz - 1, bx, F + 2, bz + 1, "black_wool")
    for z in range(bz - 7, bz + 8, 2):
        k = r.random()
        if g.get(-19, F + 1, z) != "air":
            continue
        if k < 0.35:
            hay(-19, z, r.randint(1, 3))
        elif k < 0.6:
            barrels(-19, z, r.randint(1, 2), ["carrot", "golden_carrot", "potato"])
        elif k < 0.8:
            sacks(-19, z, 2)
        else:
            crate(-19, z, ["carrot", "golden_carrot", "potato"])
    wc, wy, wz = 22, F + 8, cz
    for y in range(wy - 8, wy + 9):
        for z in range(wz - 8, wz + 9):
            d = math.hypot(y - wy, z - wz)
            if 6.4 <= d < 7.4:
                for x in (wc - 1, wc + 1):
                    S(x, y, z, "spruce_planks")
                a = math.degrees(math.atan2(y - wy, z - wz)) % 360
                if int(a // 15) % 2 == 0:
                    S(wc, y, z, "spruce_planks")
            elif 1 < d < 6.4 and (y == wy or z == wz or abs(y - wy) == abs(z - wz)):
                S(wc - 1, y, z, "spruce_fence")
    B(wc - 3, wy, wz, wc + 3, wy, wz, "spruce_planks")
    for x in (wc - 3, wc + 3):
        B(x, F + 1, wz, x, wy - 1, wz, "spruce_log")
        B(x, F + 1, wz - 2, x, F + 2, wz - 2, "spruce_fence")
        B(x, F + 1, wz + 2, x, F + 2, wz + 2, "spruce_fence")
    # north: the feeding trough, and on the back wall the emblem of the estate (a sprouting seed)
    tz = PEN[3] + 3
    B(-8, F + 1, tz, 8, F + 1, tz, "spruce_slab[type=bottom]")
    B(-8, F + 1, tz - 1, 8, F + 1, tz - 1, "spruce_trapdoor[half=bottom,facing=north,open=true]")
    for x in range(-7, 8, 2):
        g.item_display(x + 0.5, F + 1.6, tz + 0.5, r.choice(["carrot", "wheat", "potato", "beetroot"]), yaw=r.uniform(0, 360), scale=0.7)
    emblem = ["....G....", "...GGG...", "..GG.GG..", ".GG...GG.", "....G....", "....G....", "...YYY...", "..YBBBY..", ".YBBBBBY.",
              ".YBBBBBY.", "..YBBBY..", "...YYY..."]
    for row, line in enumerate(emblem):
        for col, ch in enumerate(line):
            y = F + 15 - row
            x = -4 + col
            S(x, y, z1 + 1, {"G": "green_terracotta", "Y": "yellow_terracotta", "B": "brown_terracotta", ".": "smooth_stone"}[ch])
    B(-6, F + 3, z1 + 1, 6, F + 3, z1 + 1, "chiseled_stone_bricks")
    B(-6, F + 16, z1 + 1, 6, F + 16, z1 + 1, "chiseled_stone_bricks")
    for sx in (-1, 1):
        B(6 * sx, F + 3, z1 + 1, 6 * sx, F + 16, z1 + 1, "polished_andesite")
        S(7 * sx, F + 2, z1, "mossy_stone_brick_wall")
        S(7 * sx, F + 3, z1, "lantern")
    plants(x0, z0, x1, z1, 260, [("short_grass", 8), ("fern", 4), ("moss_carpet", 3), ("poppy", 1), ("dandelion", 1), ("large_fern", 1)])
    # the roof: great beams, boards, and a glass dome over the field
    y = F + H + 1
    B(x0, y, z0, x1, y, z1, lambda xx, yy, zz: "oak_planks" if (xx + zz) % 9 == 0 else "spruce_planks")
    for z in range(z0 + 3, z1, 6):
        B(x0, y - 1, z, x1, y - 1, z, "dark_oak_planks")
    for x in (-18, 18):
        B(x, y - 1, z0, x, y - 1, z1, "dark_oak_planks")
    rad = 12.5
    for x in range(-14, 15):
        for z in range(cz - 14, cz + 15):
            d = math.hypot(x, z - cz)
            if d > rad + 1:
                continue
            for yy in (y - 1, y):
                S(x, yy, z, "air")
            if d > rad:
                S(x, y, z, "chiseled_stone_bricks")
                S(x, y - 1, z, "dark_oak_planks")
                continue
            dy = int(round(math.sqrt(max(0.0, rad * rad - d * d)) * 0.5))
            ring = int(d // 2.5)
            S(x, y + dy, z, ("yellow_stained_glass", "glass", "lime_stained_glass", "glass", "yellow_stained_glass")[ring % 5])
    # lanterns on chains round the field, light everywhere
    for x, z in ((-21, 247), (21, 247), (-21, 262), (21, 259), (-21, 273), (-21, 288), (21, 288), (0, 291), (-10, 243), (10, 243)):
        zz = z0 + 3 + 6 * round((z - z0 - 3) / 6)
        B(x, y - 6, zz, x, y - 2, zz, "iron_chain[axis=y]", where=lambda b: b == "air")
        if g.get(x, y - 7, zz) == "air":
            S(x, y - 7, zz, "lantern[hanging=true]")
    lights(5, y=8, step=6, level=14)
    m.mob("hamster_moissonneur", 0, 18 + DZ, yaw=180, room="5")
    m.objectives = ["hamster_moissonneur"]
    m.sound(0, F + 14, cz, [("item.elytra.flying", 0.2, 0.6), ("block.wood.break", 0.2, 0.5), ("ambient.cave", 0.3, 0.8)],
            radius=34, every=(140, 300), particle={"type": "WHITE_ASH", "count": 10, "spread": (12, 6, 12), "speed": 0, "every": 20})
    m.sound(0, F + 2, cz, [("block.rooted_dirt.break", 0.5, 0.5), ("entity.warden.heartbeat", 0.35, 0.6), ("block.azalea_leaves.step", 0.5, 0.6)],
            radius=28, every=(160, 320), particle={"type": "FALLING_SPORE_BLOSSOM", "count": 6, "spread": (10, 4, 10), "speed": 0, "every": 20})
    m.sound(wc, wy, wz, [("block.wooden_trapdoor.open", 0.4, 0.5), ("block.wood.step", 0.4, 0.5)], radius=16, every=(120, 260))


# ======================================================================== heights and checks

def seal_heights():
    """Nobody stands more than two blocks above the ground: invisible barriers where a walking player could get
    higher (stacked hay next to a roof, lofts, wall tops)."""
    start = (0, F + 1, -9)
    total = 0
    for _ in range(12):
        seen = g.walk(start, set(range(len(g.doors))))
        high = [p for p in seen if p[1] >= F + 4]
        if not high:
            return total
        for x, y, z in high:
            for yy in (y, y + 1):
                if gridlib.passable(g.get(x, yy, z)):
                    S(x, yy, z, "barrier")
                    total += 1
    sys.exit("seal_heights: still a way up after 12 passes")


def zone_of(x, y, z):
    """Room a cell belongs to: the doors passed to get there (the fourth is the front of the great barn)."""
    n = 1 + sum(1 for a, _ in g.doors[:3] if z > a[2])
    if (z > BARN_DOOR_Z and abs(x) <= 10) or z >= 233:
        n = 5
    return n


def check():
    errors = g.check_basic()
    start = (0, F + 1, -9)
    for k in range(len(g.doors) + 1):
        seen = g.walk(start, set(range(k)))
        if not seen:
            errors.append("start is not standable")
            break
        top = max(zone_of(*p) for p in seen)
        if top > min(k + 1, 5):
            bad = next(p for p in seen if zone_of(*p) > min(k + 1, 5))
            errors.append(f"doors open {k}: zone {top} reachable, e.g. {bad}")
        if top < min(k + 1, 5):
            errors.append(f"doors open {k}: zone {k + 1} not reachable (max {top})")
        if any(p[1] >= F + 4 for p in seen):
            errors.append(f"doors open {k}: a player can stand at {max(seen, key=lambda p: p[1])}")
        if k == len(g.doors) - 1:
            out = [p for p in seen if p[2] >= PEN[1] and not (PEN[0] < p[0] < PEN[2] and PEN[1] <= p[2] < PEN[3])]
            if out:
                errors.append(f"the surroundings of the pen are reachable before the boss dies, e.g. {out[0]}")
            if (0, F + 1, 18 + DZ) not in seen:
                errors.append("the boss cannot be reached")
            for s in m.spawns:
                p = (math.floor(s["at"][0]), int(s["at"][1]), math.floor(s["at"][2]))
                if p not in seen:
                    errors.append(f"{s['mob']} at {p} is not on ground a player reaches")
    # fighting grounds: flat, dry, nothing solid on them
    for n, rects in FREE.items():
        for a, b, c, d in rects:
            for x in range(a, c + 1):
                for z in range(b, d + 1):
                    fb = g.get(x, F, z)
                    if gridlib.passable(fb):
                        errors.append(f"room {n}: fighting ground open or wet at {x} {z} ({fb})")
                    for y in (F + 1, F + 2, F + 3):
                        if not gridlib.passable(g.get(x, y, z)):
                            errors.append(f"room {n}: fighting ground blocked at {x} {y} {z} ({g.get(x, y, z)})")
    # the arena copied exactly, apart from the gap, the exit gate and the barriers over the fence
    for line in open(ARENA):
        if line.startswith("#") or not line.strip():
            continue
        x, y, z, b = line.split(maxsplit=3)
        x, y, z, b = int(x), int(y), int(z) + DZ, b.strip()
        if (x, z - DZ) in ((-3, 0), (3, 0)):
            continue
        got = g.get(x, y, z)
        if got != b and not (abs(x) <= 2 and z in (PEN[1], PEN[3])):
            errors.append(f"arena changed at {x} {y} {z}: {b} -> {got}")
    for x in range(PEN[0] + 1, PEN[2]):
        for z in range(PEN[1] + 1, PEN[3]):
            for y in range(F + 3, F + 13):
                b = g.get(x, y, z)
                if b != "air" and not b.startswith("light["):
                    errors.append(f"something over the boss's field at {x} {y} {z}: {b}")
    # crops outside the pen kept apart from its field (the boss eats the crops joined to its spawn)
    inside = lambda x, z: PEN[0] <= x <= PEN[2] and PEN[1] <= z <= PEN[3]
    arena_crops = [(x, z) for (x, y, z), b in g.over.items() if inside(x, z) and any(c in b for c in CROPS)]
    for (x, y, z), b in g.over.items():
        if z > PEN[1] - 8 and not inside(x, z) and any(c in b for c in CROPS):
            if any(abs(x - ax) <= 2 and abs(z - az) <= 2 for ax, az in arena_crops):
                errors.append(f"crop at {x} {y} {z} joins the hamster's field")
                break
    return errors


# ======================================================================== main

shell(1, wall_gate)
shell(2, wall_fields)
shell(3, wall_green, cap="stone_bricks")
shell(4, wall_harvest)
shell(5, wall_sanct, cap="stone_bricks")
gateway(46, room="1", door_id="porte_domaine")
gateway(108, room="2", door_id="porte_champs")
gateway(166, room="3", door_id="porte_serre", post="oak_log", mass=BRICK)
gateway(233, post="dark_oak_log", mass=BRICK)
porch()
room1()
room2()
room3()
room4()
room5()
for z, face in ((46, -1), (52, 1), (108, -1), (114, 1), (166, -1), (172, 1), (239, 1)):
    frame_arch(z, face, leaves=z not in (239,))
# the doors must be registered in the order they open
order = ["porte_domaine", "porte_champs", "porte_serre", "porte_recoltes", "porte_sortie"]
pairs = sorted(zip(m.doors, g.doors), key=lambda p: order.index(p[0]["id"]))
m.doors, g.doors = [p[0] for p in pairs], [p[1] for p in pairs]
m.textures = "rpg"
m.biome = "plains"           # RPG grass and leaves painted in that colour, biome set on the map
m.ybox = (Y0, Y1)


# Troop roles (2026-11-13): half the monsters of a room have a role (gelées that leap and split, fast pests, brutes,
# spitters, kamikazes), placed next to those above. In all: 12, 17, 14 and 19 monsters for halls of 45 x 45, 61 x 55,
# 51 x 51 and 65 x 60.
REINFORCE = {
    "1": ['moisson_limace', 'moisson_limace', 'moisson_marmot', 'moisson_marmot', 'moisson_semeur', 'moisson_citrouille'],
    "2": ['moisson_taureau', 'moisson_taureau', 'moisson_semeur', 'moisson_semeur', 'moisson_marmot', 'moisson_marmot',
          'moisson_citrouille'],
    "3": ['moisson_limace', 'moisson_limace', 'moisson_limace', 'moisson_citrouille', 'moisson_semeur', 'moisson_semeur'],
    "4": ['moisson_taureau', 'moisson_taureau', 'moisson_taureau', 'moisson_semeur', 'moisson_semeur', 'moisson_marmot',
          'moisson_marmot', 'moisson_citrouille', 'moisson_citrouille'],
}


if __name__ == "__main__":
    args = sys.argv[1:]
    server = args[0] if args and not args[0].startswith("--") else "labs"
    maplib.reinforce(m, g, REINFORCE, seed=103, near=(3, 7))
    print("invisible blocks against climbing:", seal_heights())
    errs = check()
    for e in errs[:50]:
        print("CHECK:", e)
    print(f"{len(errs)} problem(s), {len(g.over)} blocks changed, {len(g.entities)} entities, {len(m.spawns)} monsters")
    if "--preview" in args:
        out = args[args.index("--preview") + 1]
        g.png_slice(os.path.join(out, "moisson_sol.png"), y=F, k=4)
        g.png_slice(os.path.join(out, "moisson_y64.png"), y=F + 1, k=4)
        g.png_slice(os.path.join(out, "moisson_y70.png"), y=F + 7, k=4)
        g.png_slice(os.path.join(out, "moisson_coupe_x0.png"), x=0, k=4)
        g.png_slice(os.path.join(out, "moisson_coupe_x23.png"), x=23, k=4)
    if "--check" in args or "--preview" in args:
        sys.exit(1 if errs else 0)
    if errs:
        sys.exit("checks failed: nothing sent")
    if "--edits" in args:
        maplib.write_edits(m, server, "moisson")
        sys.exit(0)
    g.commands(m)
    print(f"{len(m.cmds)} commands")
    maplib.run(m, server, "moisson")
