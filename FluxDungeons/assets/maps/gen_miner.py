#!/usr/bin/env python3
"""Builds the map of the Miner dungeon (test arena of the Emerald Salamander) in a loaded world, by console commands.

An old abandoned emerald mine: a timbered gallery from the start (0, 64, 0) that bends twice (south-north, then west,
then north again) and opens through a framed arch into the cave. The cave: 35 x 35 blocks of flat floor, 13 high,
carved in deepslate, with veins of emerald ore in the walls, dripstones hanging near the walls, mine frames, a rail,
barrels and crates along the walls only: the middle stays empty, the boss needs room to run, strafe and leap.
Lighting is low but even (light blocks under the ceiling, lanterns on the frames).
The bend of the gallery hides the boss until the players reach the last stretch before the cave: it wakes up
(cinematic) when it sees them coming in.
Boss spawn: (0.5, 64, 34.5), facing the entrance.

Usage: gen_miner.py <server> <world>       e.g. gen_miner.py labs miner_build
then: save-all, copy <world>/region into plugins/FluxDungeons/maps/custom/miner.
"""
import os
import random
import socket
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.normpath(os.path.join(HERE, *[".."] * 5, "serveurs", "00_scripts")))
import rcon  # noqa: E402

FLOOR = 63
X0, X1, Z0, Z1, TOP = -17, 17, 10, 44, 76       # air of the cave (inclusive)
ROCK = (-24, 57, -8, 24, 80, 50)                 # rock mass around everything
FRAMES = (16, 27, 38)                            # mine frames against the side walls (z)


def commands(seed=17):
    r = random.Random(seed)
    c = []

    def fill(x0, y0, z0, x1, y1, z1, block, mode=""):
        c.append(f"fill {x0} {y0} {z0} {x1} {y1} {z1} {block}{(' ' + mode) if mode else ''}")

    def setb(x, y, z, block):
        c.append(f"setblock {x} {y} {z} {block}")

    # rock mass in slices (a fill is limited to 32768 blocks)
    x0, y0, z0, x1, y1, z1 = ROCK
    for zs in range(z0, z1 + 1, 10):
        fill(x0, y0, zs, x1, y1, min(z1, zs + 9), "deepslate")
    # the cave, then the gallery: south-north from the start, west, north again into the cave's south wall
    for ys in range(FLOOR + 1, TOP + 1, 5):
        fill(X0, ys, Z0, X1, min(TOP, ys + 4), Z1, "air")
    fill(-2, FLOOR + 1, -4, 2, FLOOR + 5, 5, "air")
    fill(-16, FLOOR + 1, 1, 2, FLOOR + 5, 5, "air")
    fill(-16, FLOOR + 1, 1, -12, FLOOR + 5, Z0 - 1, "air")
    # floor of the gallery: planks and gravel; of the cave: flat deepslate with patches of other stones
    fill(-2, FLOOR, -4, 2, FLOOR, 5, "spruce_planks")
    fill(-16, FLOOR, 1, 2, FLOOR, 5, "spruce_planks")
    fill(-16, FLOOR, 1, -12, FLOOR, Z0 - 1, "spruce_planks")
    fill(X0, FLOOR, Z0, X1, FLOOR, Z1, "polished_deepslate")
    for _ in range(260):
        x, z = r.randint(X0, X1), r.randint(Z0, Z1)
        block = r.choice(["cobbled_deepslate", "cobbled_deepslate", "deepslate_tiles", "tuff", "gravel", "deepslate"])
        setb(x, FLOOR, z, block)
        if r.random() < 0.45:
            setb(x + r.choice((-1, 1)), FLOOR, z + r.choice((-1, 0, 1)), block)
    # gallery frames: spruce posts and beams, a lantern under every beam
    for z in (-3, 1):
        fill(-2, FLOOR + 1, z, -2, FLOOR + 4, z, "spruce_log")
        fill(2, FLOOR + 1, z, 2, FLOOR + 4, z, "spruce_log")
        fill(-2, FLOOR + 5, z, 2, FLOOR + 5, z, "stripped_spruce_log[axis=x]")
        setb(0, FLOOR + 4, z, "lantern[hanging=true]")
    for x in (-5, -10):
        fill(x, FLOOR + 1, 1, x, FLOOR + 4, 1, "spruce_log")
        fill(x, FLOOR + 1, 5, x, FLOOR + 4, 5, "spruce_log")
        fill(x, FLOOR + 5, 1, x, FLOOR + 5, 5, "stripped_spruce_log[axis=z]")
        setb(x, FLOOR + 4, 3, "lantern[hanging=true]")
    # the arch into the cave (well marked: dark oak frame, two lanterns, emerald ore around it)
    fill(-17, FLOOR + 1, Z0 - 1, -17, FLOOR + 6, Z0 - 1, "dark_oak_log")
    fill(-11, FLOOR + 1, Z0 - 1, -11, FLOOR + 6, Z0 - 1, "dark_oak_log")
    fill(-17, FLOOR + 6, Z0 - 1, -11, FLOOR + 6, Z0 - 1, "stripped_dark_oak_log[axis=x]")
    for x in (-16, -12):
        setb(x, FLOOR + 5, Z0, "lantern[hanging=true]")
    for x, y in ((-18, 66), (-18, 68), (-10, 67), (-10, 69), (-14, 70), (-15, 70)):
        setb(x, y, Z0 - 1, "deepslate_emerald_ore")
    # emerald veins in the walls: streaks of 3 to 6 blocks, a few raw emerald blocks in the thickest ones
    walls = []
    for z in range(Z0, Z1 + 1):
        walls += [(X0 - 1, z, 1, 0), (X1 + 1, z, -1, 0)]
    for x in range(X0, X1 + 1):
        walls += [(x, Z1 + 1, 0, -1), (x, Z0 - 1, 0, 1)]
    for _ in range(26):
        x, z, nx, nz = r.choice(walls)
        if z == Z0 - 1 and -18 <= x <= -10:
            continue
        y = r.randint(FLOOR + 2, TOP - 2)
        dy = r.choice((-1, 1))
        along = (0, 1) if nx else (1, 0)
        for k in range(r.randint(3, 6)):
            bx, bz, by = x + along[0] * k, z + along[1] * k, y + dy * (k // 2)
            setb(bx, by, bz, "emerald_block" if k == 2 and r.random() < 0.3 else "deepslate_emerald_ore")
    for _ in range(60):
        x, z, nx, nz = r.choice(walls)
        if z == Z0 - 1 and -18 <= x <= -10:
            continue
        setb(x, r.randint(FLOOR + 1, TOP), z, r.choice(["tuff", "cobbled_deepslate", "deepslate_iron_ore", "deepslate_coal_ore"]))
    # ceiling: dripstones hanging near the walls (never over the middle of the floor)
    for _ in range(34):
        side = r.randrange(4)
        if side < 2:
            x, z = (X0 + r.randint(0, 3)) if side == 0 else (X1 - r.randint(0, 3)), r.randint(Z0, Z1)
        else:
            x, z = r.randint(X0, X1), (Z0 + r.randint(0, 3)) if side == 2 else (Z1 - r.randint(0, 3))
        setb(x, TOP + 1, z, "dripstone_block")
        length = r.randint(1, 3)
        for k in range(length):
            part = "tip" if k == length - 1 else ("frustum" if k else "base")
            setb(x, TOP - k, z, f"pointed_dripstone[vertical_direction=down,thickness={part}]")
    # mine frames against the side walls: posts, a short beam, a hanging lantern
    for z in FRAMES:
        for x, inward in ((X0, 1), (X1, -1)):
            fill(x, FLOOR + 1, z, x, FLOOR + 5, z, "spruce_log")
            fill(x, FLOOR + 6, z, x + inward * 2, FLOOR + 6, z, "stripped_spruce_log[axis=x]")
            setb(x + inward * 2, FLOOR + 5, z, "lantern[hanging=true]")
    # a rail along the east wall, broken in places, a few barrels and crates in the corners
    for z in range(Z0 + 2, Z1 - 1):
        if r.random() < 0.85:
            setb(X1 - 1, FLOOR + 1, z, "rail")
    for x, z, block in ((X1, Z0, "barrel[facing=up]"), (X1 - 1, Z0, "barrel[facing=up]"), (X1, Z0 + 1, "chest[facing=west]"),
                        (X0, Z1, "barrel[facing=up]"), (X0 + 1, Z1, "decorated_pot"), (X0, Z1 - 1, "barrel[facing=up]"),
                        (X1, Z1, "cobweb"), (X0, Z0 + 4, "cobweb"), (X1, Z1 - 1, "barrel[facing=up]")):
        setb(x, FLOOR + 1, z, block)
    fill(X0, FLOOR + 1, Z1 - 4, X0, FLOOR + 3, Z1 - 4, "scaffolding")
    fill(X0 + 1, FLOOR + 1, Z1 - 4, X0 + 1, FLOOR + 2, Z1 - 4, "scaffolding")
    # light: low but even under the ceiling, a little brighter in the gallery
    for x in range(X0 + 4, X1, 8):
        for z in range(Z0 + 4, Z1, 8):
            setb(x, TOP, z, "light[level=11]")
    for z in (-1, 3):
        setb(0, FLOOR + 3, z, "light[level=10]")
    return c


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    server, world = sys.argv[1], sys.argv[2]
    p = rcon.props(server)
    with socket.create_connection(("127.0.0.1", int(p["rcon.port"])), timeout=20) as s:
        s.sendall(rcon.packet(1, 3, p["rcon.password"]))
        if rcon.read(s)[0] == -1:
            sys.exit("RCON password refused")
        n = 2

        def send(cmd):
            nonlocal n
            n += 1
            s.sendall(rcon.packet(n, 2, f"execute in minecraft:{world} run {cmd}"))
            return rcon.read(s)[1]
        print(send("forceload add -32 -16 32 64"))
        errors = 0
        cmds = commands()
        for cmd in cmds:
            out = send(cmd)
            if out and ("Could not" in out or "Unknown" in out or "not loaded" in out or "Too many" in out or "Incorrect" in out
                        or "Expected" in out or "Invalid" in out):
                errors += 1
                print(cmd, "->", out)
        print(f"{len(cmds)} commands, {errors} errors")
        print(send("forceload remove all"))


if __name__ == "__main__":
    main()
