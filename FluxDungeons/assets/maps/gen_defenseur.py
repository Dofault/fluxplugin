#!/usr/bin/env python3
"""Builds the map of the defender's dungeon (the Ossacorne) in a loaded world of a server, by console commands (RCON).

The Bone Pit: an old open-air excavation where the Ossacorne lay buried. A round pit (radius 18, floor at y 63)
dug into layered rock (deepslate, stone, tuff, dirt, grass on top at y 72), open to the sky, reached from the start
(0, 64, 0) by a timbered trench. Floor of packed earth, gravel and coarse dirt with bone blocks and suspicious gravel
showing through; fossil ribs climbing the walls; remains of the dig (spruce scaffolds, lanterns on posts, barrels,
decorated pots) near the entrance.
Gameplay pieces:
  - four fossil vertebra columns (2 x 2 bone blocks, 5 high) at radius 10 around the centre: the Funeral Charge stops
    against them (the boss is stunned, open to a counter-attack), and they hide from the Bone Hunt fragments;
  - room: radius 18 leaves archers 8 blocks or more from the boss (its Bone Hunt answers them), the ultimate's wave
    reaches 9 blocks;
  - the boss lies in the middle, (0.5, 64, 30.5), facing the trench.

Usage: gen_defenseur.py <server> <world>       e.g. gen_defenseur.py labs defenseur_build
then: save-all, copy <world>/region (and entities, poi) into plugins/FluxDungeons/maps/custom/defenseur.
"""
import math
import os
import random
import socket
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.normpath(os.path.join(HERE, *[".."] * 5, "serveurs", "00_scripts")))
import rcon  # noqa: E402

FLOOR = 63
TOP = 72                                  # top of the pit walls (grass)
CX, CZ, R = 0, 30, 18                     # pit centre and radius
BOX = (-26, -9, 26, 56)                    # x0, z0, x1, z1 of the rock mass
COLUMNS = [(round(CX + 10 * math.cos(math.radians(a))), round(CZ + 10 * math.sin(math.radians(a)))) for a in (45, 135, 225, 315)]
STRATA = ((58, 61, "deepslate"), (62, 63, "stone"), (64, 66, "stone"), (67, 68, "tuff"), (69, 69, "andesite"),
          (70, 71, "dirt"), (72, 72, "grass_block"))


def commands(seed=17):
    r = random.Random(seed)
    c = []

    def fill(x0, y0, z0, x1, y1, z1, block, mode=""):
        c.append(f"fill {x0} {y0} {z0} {x1} {y1} {z1} {block}{(' ' + mode) if mode else ''}")

    def setb(x, y, z, block):
        c.append(f"setblock {x} {y} {z} {block}")

    x0, z0, x1, z1 = BOX
    # rock mass in strata (in slices of z: a fill is limited to 32768 blocks), sky above it
    for za in range(z0, z1 + 1, 12):
        zb = min(z1, za + 11)
        for y0, y1, block in STRATA:
            fill(x0, y0, za, x1, y1, zb, block)
        fill(x0, TOP + 1, za, x1, TOP + 12, zb, "air")
    # a few seams in the walls
    for _ in range(120):
        x, y, z = r.randint(x0, x1), r.randint(FLOOR + 1, TOP - 2), r.randint(z0, z1)
        setb(x, y, z, r.choice(["gravel", "cobblestone", "calcite", "coarse_dirt", "diorite", "bone_block"]))
    # the trench from the start to the pit (5 wide, 5 high), roofed at the start only
    fill(-2, FLOOR + 1, -6, 2, TOP, CZ - R + 2, "air")
    fill(-2, FLOOR, -6, 2, FLOOR, CZ - R + 2, "coarse_dirt")
    # the pit: one disc of air per row of z
    for z in range(CZ - R, CZ + R + 1):
        half = int(math.sqrt(max(0, R * R - (z - CZ) ** 2)))
        fill(CX - half, FLOOR + 1, z, CX + half, TOP, z, "air")
        # floor: packed earth, a ring of loose rock near the walls
        fill(CX - half, FLOOR, z, CX + half, FLOOR, z, "packed_mud")
    for _ in range(420):
        a, d = r.uniform(0, 2 * math.pi), R * math.sqrt(r.random())
        x, z = round(CX + d * math.cos(a)), round(CZ + d * math.sin(a))
        near_wall = d > R - 3
        block = r.choice(["gravel", "coarse_dirt", "coarse_dirt", "rooted_dirt", "mud", "tuff"] + (["cobblestone", "gravel"] if near_wall else []))
        setb(x, FLOOR, z, block)
    for _ in range(40):
        a, d = r.uniform(0, 2 * math.pi), R * math.sqrt(r.random()) * 0.9
        x, z = round(CX + d * math.cos(a)), round(CZ + d * math.sin(a))
        setb(x, FLOOR, z, r.choice(["bone_block[axis=x]", "bone_block[axis=z]", "suspicious_gravel", "calcite"]))
    # the dent where it lay: a shallow hollow of bone dust in the middle
    for dx in range(-3, 4):
        for dz in range(-4, 5):
            if dx * dx / 10 + dz * dz / 17 <= 1:
                setb(CX + dx, FLOOR, CZ + dz, r.choice(["bone_block[axis=y]", "calcite", "gravel", "gravel", "suspicious_gravel"]))
    # fossil ribs climbing the walls (decor), avoiding the trench
    for a in range(20, 360, 34):
        if 250 <= a <= 290:
            continue
        ca, sa = math.cos(math.radians(a)), math.sin(math.radians(a))
        for k, (rad, h0, h1) in enumerate(((R - 0.5, 1, 4), (R - 1.5, 4, 6), (R - 2.5, 6, 7))):
            x, z = round(CX + rad * ca), round(CZ + rad * sa)
            fill(x, FLOOR + h0, z, x, FLOOR + h1, z, "bone_block[axis=y]")
    # fossil vertebra columns (the charge stops against them): 2 x 2, 5 high, a wider disc every 2 blocks
    for x, z in COLUMNS:
        fill(x, FLOOR + 1, z, x + 1, FLOOR + 5, z + 1, "bone_block[axis=y]")
        for y in (FLOOR + 2, FLOOR + 4):
            setb(x - 1, y, z, "bone_block[axis=x]")
            setb(x + 2, y, z + 1, "bone_block[axis=x]")
            setb(x, y, z - 1, "bone_block[axis=z]")
            setb(x + 1, y, z + 2, "bone_block[axis=z]")
        fill(x, FLOOR + 6, z, x + 1, FLOOR + 6, z + 1, "calcite")
        setb(x, FLOOR + 7, z, "bone_block[axis=y]")
        setb(x - 1, FLOOR, z - 1, "coarse_dirt")
    # remains of the dig near the trench: scaffolds, posts with lanterns, barrels, pots, a brush rack
    for x in (-5, 5):
        z = CZ - R + 4
        fill(x, FLOOR + 1, z, x, FLOOR + 4, z, "spruce_fence")
        setb(x, FLOOR + 5, z, "lantern")
    fill(-9, FLOOR + 1, CZ - R + 6, -9, FLOOR + 3, CZ - R + 6, "scaffolding")
    fill(-10, FLOOR + 1, CZ - R + 6, -10, FLOOR + 3, CZ - R + 6, "scaffolding")
    for x, z, block in ((8, CZ - R + 5, "barrel[facing=up]"), (9, CZ - R + 5, "barrel[facing=up]"), (8, CZ - R + 6, "decorated_pot"),
                        (-7, CZ - R + 5, "decorated_pot"), (-8, CZ - R + 5, "chiseled_bookshelf"), (9, CZ - R + 6, "suspicious_sand")):
        setb(x, FLOOR + 1, z, block)
    for z in (-4, 2, 8):
        fill(-2, FLOOR + 1, z, -2, FLOOR + 4, z, "spruce_log")
        fill(2, FLOOR + 1, z, 2, FLOOR + 4, z, "spruce_log")
        fill(-2, FLOOR + 5, z, 2, FLOOR + 5, z, "stripped_spruce_log[axis=x]")
        setb(0, FLOOR + 4, z, "lantern[hanging=true]")
    # lanterns on posts around the pit, light at night
    for a in range(0, 360, 45):
        if a == 270:
            continue
        x, z = round(CX + (R - 1.5) * math.cos(math.radians(a))), round(CZ + (R - 1.5) * math.sin(math.radians(a)))
        fill(x, FLOOR + 1, z, x, FLOOR + 2, z, "spruce_fence")
        setb(x, FLOOR + 3, z, "lantern")
    for x in range(CX - 12, CX + 13, 8):
        for z in range(CZ - 12, CZ + 13, 8):
            setb(x, FLOOR + 6, z, "light[level=12]")
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
