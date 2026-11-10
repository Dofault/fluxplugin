#!/usr/bin/env python3
"""Builds the map of the miner's dungeon (Copper T-Rex) in a loaded world of a server, by console commands (RCON).

The mine: a cave carved in deepslate (35 x 39 blocks, 8 high, ceiling of rock), reached by a timbered gallery from the
start (0, 64, 0). Copper ore in the walls, mine frames (spruce posts and beams) with hanging lanterns, invisible light
blocks under the ceiling, a few dripstones hanging, patches of tuff, gravel and cobbled deepslate on the floor.
Gameplay pieces:
  - four solid pillars (deepslate bricks with a band of copper) at x +-9, z 15 and 33: the Drill Charge stops
    against them, or against the walls (the boss is stunned);
  - five veins of raw copper (RAW_COPPER_BLOCK, the only ones in the map: the boss looks for that block) near the
    walls: it drains them to gain Overcharge; players break them at the first hit (`instant` in dungeons/mineur.yml).
Boss spawn: (0.5, 64, 28.5), facing the gallery.

Usage: gen_mineur.py <server> <world>       e.g. gen_mineur.py labs mine_build
then: save-all, copy <world>/region (and entities, poi) into plugins/FluxDungeons/maps/custom/mineur.
"""
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.normpath(os.path.join(HERE, *[".."] * 5, "serveurs", "00_scripts")))
import rcon  # noqa: E402

FLOOR = 63
X0, X1, Z0, Z1, TOP = -17, 17, 6, 44, 71          # air of the cave (inclusive)
PILLARS = ((8, 15), (-10, 15), (8, 33), (-10, 33))  # 2 x 2 pillars: (x, z) of their lowest corner
FRAMES = (10, 22, 40)                               # mine frames along the walls (z)
VEINS = (
    ((-14, 12), (-15, 12), (-14, 13), (-14, 12, 1)),
    ((14, 12), (15, 12), (14, 13), (14, 12, 1)),
    ((-14, 38), (-15, 38), (-14, 37), (-14, 38, 1)),
    ((14, 38), (15, 38), (14, 37), (14, 38, 1)),
    ((0, 42), (1, 42), (-1, 42), (0, 42, 1)),
)


def commands(seed=31):
    r = random.Random(seed)
    c = []

    def fill(x0, y0, z0, x1, y1, z1, block, mode=""):
        c.append(f"fill {x0} {y0} {z0} {x1} {y1} {z1} {block}{(' ' + mode) if mode else ''}")

    def setb(x, y, z, block):
        c.append(f"setblock {x} {y} {z} {block}")

    # rock mass, then the cave and the gallery carved in it
    fill(-22, 58, -8, 22, 74, 19, "deepslate")
    fill(-22, 58, 20, 22, 74, 48, "deepslate")
    fill(X0, FLOOR + 1, Z0, X1, TOP, Z1, "air")
    fill(-3, FLOOR + 1, -5, 3, FLOOR + 5, Z0 - 1, "air")
    # floor: patches of other stones
    for _ in range(170):
        x, z = r.randint(X0, X1), r.randint(-5 if r.random() < 0.15 else Z0, Z1)
        if z < Z0 and abs(x) > 3:
            continue
        block = r.choice(["tuff", "cobbled_deepslate", "cobbled_deepslate", "gravel", "polished_deepslate", "deepslate_tiles"])
        setb(x, FLOOR, z, block)
        if r.random() < 0.4:
            setb(x + r.choice((-1, 1)), FLOOR, z, block)
    # copper ore in the walls (no raw copper blocks: those are the veins the boss looks for)
    for _ in range(140):
        side = r.randrange(4)
        y = r.randint(FLOOR + 1, TOP)
        if side == 0:
            x, z = X0 - 1, r.randint(Z0, Z1)
        elif side == 1:
            x, z = X1 + 1, r.randint(Z0, Z1)
        elif side == 2:
            x, z = r.randint(X0, X1), Z1 + 1
        else:
            x, z = r.randint(X0, X1), Z0 - 1
            if abs(x) <= 3 and y <= FLOOR + 5:
                continue
        setb(x, y, z, r.choice(["deepslate_copper_ore"] * 5 + ["copper_ore", "deepslate_iron_ore", "tuff"]))
    for _ in range(40):
        setb(r.randint(X0, X1), TOP + 1, r.randint(Z0, Z1), r.choice(["deepslate_copper_ore", "tuff", "cobbled_deepslate"]))
    # mine frames: posts against the walls, a beam under the ceiling, lanterns hanging from it
    for z in FRAMES:
        fill(X0, FLOOR + 1, z, X0, TOP - 1, z, "spruce_log")
        fill(X1, FLOOR + 1, z, X1, TOP - 1, z, "spruce_log")
        fill(X0, TOP, z, X1, TOP, z, "stripped_spruce_log[axis=x]")
        for x in (-12, -4, 4, 12):
            setb(x, TOP - 1, z, "lantern[hanging=true]")
    for z in (-3, 2):
        fill(-3, FLOOR + 1, z, -3, FLOOR + 4, z, "spruce_log")
        fill(3, FLOOR + 1, z, 3, FLOOR + 4, z, "spruce_log")
        fill(-3, FLOOR + 5, z, 3, FLOOR + 5, z, "stripped_spruce_log[axis=x]")
        setb(0, FLOOR + 4, z, "lantern[hanging=true]")
    # rails left in the gallery and along the left wall
    fill(-2, FLOOR + 1, -5, -2, FLOOR + 1, Z0 + 2, "rail")
    for z in range(Z0 + 3, Z1 - 3):
        if r.random() < 0.7:
            setb(X0 + 1, FLOOR + 1, z, "rail")
    # pillars (the charge stops against them)
    for x, z in PILLARS:
        fill(x, FLOOR + 1, z, x + 1, TOP, z + 1, "deepslate_bricks")
        fill(x, FLOOR + 2, z, x + 1, FLOOR + 3, z + 1, "waxed_copper_block")
        fill(x, TOP - 1, z, x + 1, TOP - 1, z + 1, "waxed_cut_copper")
    # dripstones hanging from the ceiling, away from the pillars
    for _ in range(26):
        x, z = r.randint(X0 + 1, X1 - 1), r.randint(Z0 + 1, Z1 - 1)
        if z in FRAMES or any(px - 1 <= x <= px + 2 and pz - 1 <= z <= pz + 2 for px, pz in PILLARS):
            continue
        setb(x, TOP, z, "pointed_dripstone[vertical_direction=down,thickness=tip]")
    # veins of raw copper (the boss drains them; players break them at the first hit)
    for vein in VEINS:
        for b in vein:
            x, z = b[0], b[1]
            dy = b[2] if len(b) > 2 else 0
            setb(x, FLOOR + 1 + dy, z, "raw_copper_block")
    # invisible light under the ceiling
    for x in range(X0 + 2, X1, 6):
        for z in range(Z0 + 2, Z1, 6):
            if z in FRAMES:
                continue
            setb(x, TOP - 1, z, "light[level=13]")
    for z in (-4, 0, 4):
        setb(2, FLOOR + 4, z, "light[level=12]")
    return c


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    server, world = sys.argv[1], sys.argv[2]
    p = rcon.props(server)
    import socket
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
            if out and ("Could not" in out or "Unknown" in out or "not loaded" in out or "Too many" in out or "Incorrect" in out):
                errors += 1
                print(cmd, "->", out)
        print(f"{len(cmds)} commands, {errors} errors")
        print(send("forceload remove all"))


if __name__ == "__main__":
    main()
