#!/usr/bin/env python3
"""Map of the mole dungeon, Les Galeries de Taupérion (boss: King Taupérion): the way IN, modelled in memory (gridlib),
checked, then built by console commands with the RPG look of the dungeons.

The boss's own ground (its maze of galleries, its nest and the arena above) is not here: FluxDungeons digs it anew for
every run (dungeon/Burrows.java, `burrows` section of dungeons/taupe.yml) north of this map, and joins it to the end of
this map by a tunnel (`burrows.link`: the cell right after the last one of the final tunnel below). This map must
therefore stay south of z = -72 (the lair and its hills cover z -71 to 31).

A burrow under the fields, going down at every step, played from south to north (+z):
  - the hollow (start at 0, 65, -282): a grassy dip open to the sky, molehills, and the mouth of the burrow;
  - the way down, then room 1, the Roots (floor 56): right under the meadow, thick roots from floor to ceiling on the
    sides, roots hanging overhead. Root spiders, pests, lost diggers;
  - room 2, the Larder (floor 54): what the mole stole from the fields, in heaps (hay, melons). Worms, spitters, jellies;
  - room 3, the Diggings (floor 52): harder ground, veins of ore laid bare, heaps of rubble. Diggers and worms around
    the Navvy (mini-boss);
  - the last tunnel, to the galleries of the lair.

Usage: gen_taupe.py [server] [--check] [--preview DIR]     (labs by default)
"""
import math
import os
import random
import sys

import gridlib
import maplib

F0, F1, F2, F3 = 64, 56, 54, 52           # floors: hollow, room 1, room 2, room 3 and the last tunnel
TOP = 72                                  # the meadow
X0, X1, Y0, Y1, Z0, Z1 = -42, 42, 30, 90, -296, -73
BANDS = ((30, 40, "stone"), (41, TOP - 1, "dirt"), (TOP, TOP, "grass_block"))
g = gridlib.Model((X0, X1, Y0, Y1, Z0, Z1), BANDS, seed=47)
r = g.r
m = maplib.Map()
m.box = (X0, Z0, X1, Z1)
m.biome = "plains"
_door = m.door
OPEN = ("air",)
EARTH = "earth"   # placeholder of natural earth, painted at the end

# room centres (x, z): floor ellipse 18 x 19, gate 23 blocks north of the centre, 12 blocks of passage, next room
R1C, R2C, R3C = (0, -230), (0, -172), (0, -114)
RX, RZ = 18, 19


def door(id, frm, to, room):
    g.doors.append((tuple(min(a, b) for a, b in zip(frm, to)), tuple(max(a, b) for a, b in zip(frm, to))))
    _door(id, frm, to, room=room)


PALETTES = {
    "roots": [("dirt", 50), ("coarse_dirt", 25), ("podzol", 15), ("brown_terracotta", 10)],
    "larder": [("dirt", 45), ("coarse_dirt", 20), ("brown_terracotta", 20), ("clay", 8), ("podzol", 7)],
    "dig": [("dirt", 34), ("coarse_dirt", 20), ("stone", 22), ("cobblestone", 12), ("brown_terracotta", 12)],
}
NOISES = [gridlib.Noise(s) for s in (401, 402, 403)]


def zone_palette(x, y, z):
    zz = z + NOISES[2](x * 0.3, y * 0.3, z * 0.3) * 4
    if zz < R1C[1] + 28:
        return "roots"
    if zz < R2C[1] + 28:
        return "larder"
    return "dig"


def paint(x, y, z, palette=None):
    pal = PALETTES[palette or zone_palette(x, y, z)]
    v = (NOISES[0](x * 0.9, y * 1.1, z * 0.9) * 0.75 + NOISES[1](x * 2.3, y * 2.3, z * 2.3) * 0.25 + 1) / 2
    v = min(0.999, max(0.0, v + r.uniform(-0.06, 0.06)))
    tot = sum(w for _, w in pal)
    acc = 0
    for b, w in pal:
        acc += w / tot
        if v < acc:
            return b
    return pal[-1][0]


def carve(cx, cy, cz, rx, ry, rz, clip, seed, rough=0.35):
    g.blob(cx, cy, cz, rx, ry, rz, clip, seed, rough, block="air", where=lambda b: b not in OPEN)


def refill(x0, y0, z0, x1, y1, z1):
    """Earth back below a floor (the blobs carve round bottoms)."""
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            for z in range(z0, z1 + 1):
                if g.get(x, y, z) == "air":
                    g.set(x, y, z, EARTH)


def column(cx, cz, y0, y1, r0, r1, seed, mat):
    """A thick root (or a pillar of earth) from y0 to y1, wavy."""
    n = gridlib.Noise(seed)
    for y in range(y0, y1 + 1):
        t = (y - y0) / max(1, y1 - y0)
        rad = (r0 + (r1 - r0) * t) * (0.8 + 0.4 * abs(math.sin(t * math.pi * 1.5 + seed)))
        ox, oz = 0.9 * n(y * 0.3, 0, 0), 0.9 * n(0, y * 0.3, 1)
        for x in range(int(cx - rad - 2), int(cx + rad + 3)):
            for z in range(int(cz - rad - 2), int(cz + rad + 3)):
                if math.hypot(x - cx - ox, z - cz - oz) <= rad + 0.5 * n(x * 0.7, y * 0.7, z * 0.7):
                    g.set(x, y, z, mat)


def mound(cx, cz, y, rad, h, seed, mat=EARTH):
    """A heap on the floor; mat may be a function (x, y, z) -> block."""
    g.blob(cx, y, cz, rad, h, rad, (int(cx - rad - 2), y + 1, int(cz - rad - 2), int(cx + rad + 2), y + h + 1, int(cz + rad + 2)), seed, 0.4,
           block=mat, where=lambda b: b == "air")


def lights(points, level=11):
    for x, y, z in points:
        if g.get(x, y, z) == "air":
            g.set(x, y, z, f"light[level={level}]")


def flat_floor(inside, y, x0, z0, x1, z1):
    """The fighting floor: earth at y, four free blocks above, where inside(x, z)."""
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if inside(x, z):
                g.set(x, y, z, EARTH)
                for yy in range(y + 1, y + 5):
                    g.set(x, yy, z, "air")


def gate(z, floor, x0, x1, h):
    """A plug of packed earth and roots across a narrow passage, between two thick roots and a root lintel."""
    g.box(x0 - 1, floor + 1, z, x0 - 1, floor + h + 1, z, "dark_oak_log")
    g.box(x1 + 1, floor + 1, z, x1 + 1, floor + h + 1, z, "dark_oak_log")
    g.box(x0 - 1, floor + h + 1, z, x1 + 1, floor + h + 1, z, "dark_oak_log[axis=x]")
    g.box(x0, floor + 1, z, x1, floor + h, z, lambda *a: r.choice(["coarse_dirt", "coarse_dirt", "brown_terracotta", "podzol", "dirt"]))


def burrow(z_from, z_to, f_from, f_to, wave=1.5, seed=0, end_x=None):
    """A winding burrow 5 wide and 4 high between two floors (steps of one block every other row)."""
    n = z_to - z_from
    for z in range(z_from, z_to + 1):
        k = z - z_from
        fz = f_from - min(f_from - f_to, k // 2)
        ox = round(wave * math.sin(k * 0.45))
        if end_x is not None and z_to - z <= 3:
            ox = end_x
        carve(ox, fz + 3, z, 3.6, 3.2, 2, (ox - 5, fz + 1, z, ox + 5, fz + 6, z), seed=seed + z, rough=0.25)
        g.box(ox - 2, fz + 1, z, ox + 2, fz + 4, z, "air")
        g.box(ox - 4, Y0, z, ox + 4, fz, z, EARTH, where=lambda b: b == "air")
        g.box(ox - 2, fz, z, ox + 2, fz, z, EARTH)
        if k % 5 == 2:
            lights([(ox, fz + 3, z)], 9)
    return n


def in_room(c):
    return lambda x, z: ((x - c[0]) / RX) ** 2 + ((z - c[1]) / RZ) ** 2 <= 1


def cave(c, F, seed):
    """The hollow of a room: a low wide vault under the meadow, recesses on the sides, a flat floor."""
    cx, cz = c
    clip = (cx - 28, F + 1, cz - 22, cx + 28, F + 11, cz + 22)
    carve(cx, F + 4, cz, 22, 8, 22, clip, seed=seed, rough=0.3)
    for i, (dx, dz, rr) in enumerate(((-20, -9, 5), (21, 8, 5), (-19, 12, 4), (19, -11, 4))):
        carve(cx + dx, F + 4, cz + dz, rr, 4, rr, clip, seed=seed + 10 + i)
    refill(cx - 29, Y0, cz - 22, cx + 29, F, cz + 22)
    flat_floor(in_room(c), F, cx - RX, cz - RZ, cx + RX, cz + RZ)
    # the way in from the burrow, through the tip of the vault
    g.box(cx - 2, F + 1, cz - 22, cx + 2, F + 4, cz - 16, "air")
    g.box(cx - 2, F, cz - 22, cx + 2, F, cz - 16, EARTH)
    # dim light all over, brighter near the walls where the eye looks for the way
    lights([(cx + dx, F + 6, cz + dz) for dx in (-12, -4, 4, 12) for dz in (-13, -4, 5, 14)], 11)
    lights([(cx + dx, F + 3, cz + dz) for dx, dz in ((-17, 0), (17, 0), (0, -17), (0, 17))], 12)


def exit_gate(c, F, id, room):
    cz = c[1] + 23
    g.box(-3, F + 1, cz - 7, 3, F + 5, cz + 1, "air")
    g.box(-3, F, cz - 7, 3, F, cz + 1, EARTH)
    gate(cz, F, -3, 3, 4)
    door(id, (-3, F + 1, cz), (3, F + 4, cz), room=room)
    return cz


def populate(room, c, F, mobs, seed, apart=3.4, keep=None):
    """Monsters on free floor of the room, apart from one another, never in the first third (where the party walks
    in): the room is fought in a few pulls, not all at once."""
    rnd = random.Random(seed)
    cx, cz = c
    cells = [(x, z) for x in range(cx - RX + 2, cx + RX - 1) for z in range(cz - 6, cz + RZ - 1)
             if ((x - cx) / (RX - 2)) ** 2 + ((z - cz) / (RZ - 2)) ** 2 <= 1
             and g.get(x, F, z) not in OPEN and g.get(x, F + 1, z) == "air" and g.get(x, F + 2, z) == "air"
             and (keep is None or keep(x, z))]
    rnd.shuffle(cells)
    placed = []
    for mob in mobs:
        spot = next((p for p in cells if all(math.hypot(p[0] - q[0], p[1] - q[1]) >= apart for q in placed)), None)
        if spot is None:
            sys.exit(f"room {room}: no free place left for {mob}")
        placed.append(spot)
        m.mob(mob, spot[0], spot[1], y=F + 1, yaw=180, room=room)


# ======================================================================== the way

def hollow():
    cz = -282
    carve(0, F0 + 9, cz, 9, 13, 9, (-11, F0 + 1, cz - 11, 11, Y1, cz + 10), seed=1, rough=0.25)
    refill(-12, Y0, cz - 12, 12, F0, cz + 11)
    for x in range(-11, 12):
        for z in range(cz - 11, cz + 11):
            if g.get(x, F0 + 1, z) == "air" and g.get(x, F0, z) not in OPEN:
                g.set(x, F0, z, "grass_block")
                k = r.random()
                if k < 0.22:
                    g.set(x, F0 + 1, z, "short_grass")
                elif k < 0.26:
                    g.set(x, F0 + 1, z, r.choice(["dandelion", "poppy", "oxeye_daisy"]))
    # the sky stays in view, the slopes cannot be climbed out of
    g.box(-12, TOP, cz - 12, 12, TOP, cz + 11, "barrier", where=lambda b: b == "air")
    # molehills, what is left of a field
    for i, (x, z, rad, h) in enumerate(((-5, cz - 3, 1.8, 2), (5, cz + 1, 1.5, 1), (-3, cz + 5, 1.3, 1), (4, cz - 6, 1.2, 1))):
        mound(x, z, F0, rad, h, seed=20 + i, mat="coarse_dirt")
    for x, z in ((6, cz - 2), (6, cz - 3)):
        if g.get(x, F0 + 1, z) in ("air", "short_grass"):
            g.set(x, F0 + 1, z, "hay_block")
    # the mouth of the burrow, in the northern slope, between two heaps of fresh earth
    g.box(-3, F0 + 1, cz + 7, 3, F0 + 4, cz + 11, "air")
    g.box(-3, F0, cz + 7, 3, F0, cz + 11, "coarse_dirt")
    for i, x in enumerate((-5, 5)):
        mound(x, cz + 8, F0, 1.8, 2, seed=30 + i, mat="coarse_dirt")
    g.start = (0, F0 + 1, cz)
    m.start = [0.5, float(F0 + 1), cz + 0.5, 0.0, 0.0]
    burrow(cz + 11, R1C[1] - 23, F0, F1, seed=100)


def roots():
    cx, cz = R1C
    cave(R1C, F1, seed=31)
    # thick roots from floor to ceiling on the sides, thin ones hanging over the middle
    for i, (dx, dz, r0, r1) in enumerate(((-15, -8, 1.8, 1.1), (15, -5, 2.0, 1.2), (-14, 8, 1.5, 1.0), (14, 10, 1.7, 1.1),
                                          (-17, 1, 1.3, 0.9), (17, 3, 1.4, 0.9), (-9, 16, 1.3, 0.9), (9, -16, 1.2, 0.8))):
        column(cx + dx, cz + dz, F1 + 1, (g.ceiling(cx + dx, F1 + 1, cz + dz) or F1 + 10) + 1, r0, r1, seed=50 + i, mat="dark_oak_log")
    for _ in range(46):
        x, z = r.randint(cx - 19, cx + 19), r.randint(cz - 19, cz + 19)
        top = g.ceiling(x, F1 + 5, z)
        if top and top - F1 >= 8 and g.get(x, F1 + 5, z) == "air":
            g.stalactite(x, top - 1, z, r.randint(1, 3), ["dark_oak_log"], "hanging_roots")
    for i, (dx, dz) in enumerate(((-19, 9), (19, -9), (-12, -17), (12, 17))):
        mound(cx + dx, cz + dz, F1, r.uniform(1.6, 2.6), r.randint(1, 2), seed=70 + i)
    populate("1", R1C, F1, ["taupe_araignee"] * 5 + ["taupe_galopin"] * 4 + ["taupe_fouisseur"] * 3 + ["taupe_gelee"], seed=1)
    z = exit_gate(R1C, F1, "porte_racines", "1")
    burrow(z + 1, R2C[1] - 23, F1, F2, seed=200)


def larder():
    cx, cz = R2C
    cave(R2C, F2, seed=33)
    # the heaps of the larder, off the middle
    stock = lambda x, y, z: r.choice(["hay_block", "hay_block", "hay_block", "melon", "coarse_dirt"])
    for i, (dx, dz, rad, h) in enumerate(((-15, -7, 3.0, 3), (15, 4, 3.4, 3), (-14, 10, 2.4, 2), (13, -12, 2.6, 2),
                                          (-6, 17, 2.2, 2), (7, 16, 2.0, 2), (-18, 2, 2.0, 2))):
        mound(cx + dx, cz + dz, F2, rad, h, seed=80 + i, mat=stock)
    for i, (dx, dz) in enumerate(((-19, -3), (19, -4), (17, 12))):
        column(cx + dx, cz + dz, F2 + 1, (g.ceiling(cx + dx, F2 + 1, cz + dz) or F2 + 9) + 1, 1.5, 1.0, seed=90 + i, mat="dark_oak_log")
    for _ in range(40):
        x, z = r.randint(cx - 19, cx + 19), r.randint(cz - 19, cz + 19)
        if not in_room(R2C)(x, z) and g.get(x, F2 + 1, z) == "air" and g.get(x, F2, z) not in OPEN and g.get(x, F2, z) != "hay_block":
            g.set(x, F2 + 1, z, r.choice(["brown_mushroom", "brown_mushroom", "red_mushroom"]))
    populate("2", R2C, F2, ["taupe_ver"] * 5 + ["taupe_cracheur"] * 4 + ["taupe_gelee"] * 3 + ["taupe_galopin"] * 2, seed=2)
    z = exit_gate(R2C, F2, "porte_garde_manger", "2")
    burrow(z + 1, R3C[1] - 23, F2, F3, seed=300)


def diggings():
    cx, cz = R3C
    cave(R3C, F3, seed=35)
    # rubble at the edges, two pillars left standing, ore laid bare in the walls
    rubble = lambda x, y, z: r.choice(["cobblestone", "cobblestone", "gravel", "stone", "coarse_dirt"])
    for i, (dx, dz, rad, h) in enumerate(((-16, -6, 2.8, 2), (16, 7, 3.0, 3), (-13, 13, 2.4, 2), (14, -11, 2.2, 2), (0, 18, 2.4, 2))):
        mound(cx + dx, cz + dz, F3, rad, h, seed=110 + i, mat=rubble)
    for i, (dx, dz) in enumerate(((-18, 3), (18, -3))):
        column(cx + dx, cz + dz, F3 + 1, (g.ceiling(cx + dx, F3 + 1, cz + dz) or F3 + 9) + 1, 2.2, 1.5, seed=120 + i, mat=EARTH)
    walls = []
    for x in range(cx - 27, cx + 28):
        for y in range(F3 + 1, F3 + 9):
            for z in range(cz - 22, cz + 23):
                if g.get(x, y, z) in OPEN:
                    continue
                if any(g.get(x + a, y + b, z + c) == "air" for a, b, c in ((1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, -1, 0))):
                    walls.append((x, y, z))
    r.shuffle(walls)
    for x, y, z in walls[:60]:
        if g.get(x, y, z) in (EARTH, "dirt"):
            g.set(x, y, z, r.choice(["coal_ore", "coal_ore", "iron_ore"]))
    populate("3", R3C, F3, ["taupe_fouisseur"] * 4 + ["taupe_ver"] * 3 + ["taupe_cracheur"] * 2 + ["taupe_araignee"] * 2, seed=3,
             keep=lambda x, z: math.hypot(x - cx, z - (cz + 9)) > 5)
    m.mob("taupe_terrassier", cx, cz + 9, y=F3 + 1, yaw=180, room="3")
    z = exit_gate(R3C, F3, "porte_chantier", "3")
    # the last tunnel, flat, ending in the middle of the map's northern side: the lair's tunnel starts right after
    burrow(z + 1, Z1, F3, F3, seed=400, end_x=0)


def paint_all():
    """Every placeholder and every visible face of the base earth gets its palette; nothing loose over a void."""
    for p, b in list(g.over.items()):
        if b == EARTH:
            g.set(*p, paint(*p))
    for p, b in list(g.over.items()):
        if b not in OPEN and not b.startswith("light"):
            continue
        x, y, z = p
        for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            q = (x + d[0], y + d[1], z + d[2])
            if g.inside(*q) and q not in g.over and q[1] < TOP:
                g.set(*q, paint(*q))
    for p, b in list(g.over.items()):
        if gridlib.gravity(b) and gridlib.passable(g.get(p[0], p[1] - 1, p[2])):
            g.set(*p, "coarse_dirt")


GATES = [c[1] + 23 for c in (R1C, R2C, R3C)]


def zone_of(x, y, z):
    return 1 + sum(1 for gz in GATES if z > gz)


def check():
    errors = g.check_basic()
    for k in range(len(g.doors) + 1):
        seen = g.reach(g.start, set(range(k)))
        top = max(zone_of(*p) for p in seen)
        if top > k + 1:
            bad = next(p for p in seen if zone_of(*p) > k + 1)
            errors.append(f"doors open {k}: zone {top} reachable, e.g. {bad}")
        if top < k + 1:
            errors.append(f"doors open {k}: zone {k + 1} not reachable (max {top})")
    # the end of the last tunnel is open, on its floor, where the lair's tunnel starts (burrows.link)
    if not ((0, F3 + 1, Z1) in g.reach(g.start, {0, 1, 2}) and g.get(0, F3, Z1) not in OPEN):
        errors.append("the last tunnel does not end open at 0, %d, %d" % (F3 + 1, Z1))
    return errors


hollow()
roots()
larder()
diggings()
paint_all()
m.textures = "rpg"
m.ybox = (Y0, Y1)

if __name__ == "__main__":
    args = sys.argv[1:]
    errs = check()
    for e in errs[:50]:
        print("CHECK:", e)
    print(f"{len(errs)} problem(s), {len(g.over)} blocks changed, {len(m.spawns)} monsters, link: [0, {F3 + 1}, {Z1 + 1}]")
    if not errs:
        maplib.widen(g, m, [], "taupe", args[args.index("--preview") + 1] if "--preview" in args else None)
    if "--preview" in args:
        out = args[args.index("--preview") + 1]
        for y in (F0 + 1, F1 + 1, F2 + 1, F3 + 1):
            g.png_slice(os.path.join(out, f"taupe_y{y}.png"), y=y, k=3)
        g.png_slice(os.path.join(out, "taupe_coupe_x0.png"), x=0, k=3)
    if "--check" in args or "--preview" in args:
        sys.exit(1 if errs else 0)
    if errs:
        sys.exit("checks failed: nothing sent")
    if "--edits" in args:
        maplib.write_edits(m, args[0] if args and not args[0].startswith("--") else "labs", "taupe")
        sys.exit(0)
    g.commands(m)
    print(f"{len(m.cmds)} commands")
    maplib.run(m, args[0] if args and not args[0].startswith("--") else "labs", "taupe")
