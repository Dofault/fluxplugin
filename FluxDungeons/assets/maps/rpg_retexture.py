#!/usr/bin/env python3
"""RPG look for a dungeon map already built (by an older script or in game): opens a copy of maps/custom/<dungeon>
in a temporary void world of the server, swaps its vanilla blocks for the RPG note blocks (maplib.retexture: strict,
upright logs and hay only), saves and copies the map back. The scripts that build through maplib.run with
`m.textures = "rpg"` do not need it.

Usage: rpg_retexture.py <dungeon> [server]      (labs by default)
"""
import os
import shutil
import sys
import time

import maplib


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    dungeon = sys.argv[1]
    server = sys.argv[2] if len(sys.argv) > 2 else "labs"
    base = os.path.join(maplib.ROOT, "serveurs", maplib.SERVERS[server])
    folder = os.path.join(base, "plugins", "FluxDungeons", "maps", "custom", dungeon)
    blocks = maplib.read_blocks(os.path.join(folder, "region"))
    if not blocks:
        sys.exit(f"no block in {folder}")
    used = {b.split("[")[0] for b in blocks.values()}
    xs = [p[0] for p in blocks]
    ys = [p[1] for p in blocks]
    zs = [p[2] for p in blocks]
    m = maplib.Map()
    m.box = (min(xs), min(zs), max(xs), max(zs))
    m.ybox = (min(ys), max(ys))
    table = maplib.rpg_blocks(server)
    print(f"{len(blocks)} blocks, {len(used & set(table))} kinds to swap: {', '.join(sorted(used & set(table)))}")
    c = maplib.Console(server)
    world = f"fdrpg_{dungeon}"
    out = c.send(f'mv create {world} normal --world-type flat --no-structures --generator-settings '
                 '{"layers":[{"block":"minecraft:air","height":1}],"biome":"minecraft:plains"}')
    print(out.splitlines()[-1] if out else "(created)")
    time.sleep(2)
    c.send(f"mv unload {world}")
    time.sleep(3)
    wdir = os.path.join(base, "world", "dimensions", "minecraft", world)
    for sub in ("region", "entities", "poi"):
        shutil.rmtree(os.path.join(wdir, sub), ignore_errors=True)
        if os.path.isdir(os.path.join(folder, sub)):
            shutil.copytree(os.path.join(folder, sub), os.path.join(wdir, sub))
    print(c.send(f"mv load {world}"))
    time.sleep(2)
    for rule in ("random_tick_speed 0", "spawn_mobs false", "advance_time false"):
        c.send(f"execute in minecraft:{world} run gamerule {rule}")
    x0, z0, x1, z1 = m.box
    print(c.send(f"execute in minecraft:{world} run forceload add {x0} {z0} {x1} {z1}"))
    bad = 0
    swap = maplib.retexture(m, server, used)
    for cmd in swap:
        o = c.send(f"execute in minecraft:{world} run {cmd}")
        if o and any(b in o for b in maplib.BAD) and "No blocks" not in o and "Could not set" not in o:
            bad += 1
            if bad < 10:
                print(cmd, "->", o)
    print(f"{len(swap)} swap commands, {bad} errors")
    print(c.send(f"execute in minecraft:{world} run forceload remove all"))
    print(c.send("save-all flush"))
    time.sleep(4)
    for sub in ("region", "entities", "poi"):
        if os.path.isdir(os.path.join(wdir, sub)):
            shutil.rmtree(os.path.join(folder, sub), ignore_errors=True)
            shutil.copytree(os.path.join(wdir, sub), os.path.join(folder, sub))
    print("copied into", folder)
    maplib.delete_world(c, world)
    after = maplib.read_blocks(os.path.join(folder, "region"))
    print("note blocks now:", sum(1 for b in after.values() if b.startswith("note_block")))


if __name__ == "__main__":
    main()
