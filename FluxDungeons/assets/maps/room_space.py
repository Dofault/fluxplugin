#!/usr/bin/env python3
"""How much room the monsters of each dungeon room have, measured on the built maps (2026-11-14).

    python3 room_space.py <plugins/FluxDungeons> [dungeon ...] [--png <folder>]

For every room of edits/<dungeon>.yml: the floor a monster can walk on from the room's spawn points (flood fill on the
region files of maps/custom/<dungeon>: feet and head free, solid ground, one block up or down per step, never through
water, fences nor closed doors), and its "open" part: the cells with a free 5 x 5 around them, where a fight can
really move (a 3-wide gallery has none). Per monster of the room, against the targets below.

Targets come from the two dungeons the owner called right (Cultivateurs, Enchanteurs): a room needs at least
OPEN_PER_MOB blocks of open floor per monster and a free square of MIN_SQUARE blocks somewhere. A room under them
is printed with "TIGHT". Exit code 1 when any room is tight (a check to run after a map script).
"""
import math
import os
import struct
import sys
import zlib

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gridlib import passable, tall  # noqa: E402
from maplib import _nbt  # noqa: E402

OPEN_PER_MOB = 30
MIN_SQUARE = 15
REACH = 16          # blocks beyond the box of a room's spawn points the flood may run
AIR, SOLID, WATER, FENCE, HARM = 0, 1, 2, 3, 4
HARMFUL = ("lava", "magma_block", "fire", "campfire", "cactus", "sweet_berry", "powder_snow", "cobweb")


def kind(name):
    if name == "water" or name.startswith("water[") or "waterlogged=true" in name or "kelp" in name or "seagrass" in name:
        return WATER
    if any(h in name for h in HARMFUL):
        return HARM
    if tall(name):
        return FENCE
    return AIR if passable(name) else SOLID


def read(region_dir, y0, y1):
    """{(x, y, z): kind} of every non-air block between two heights."""
    out = {}
    for fn in sorted(os.listdir(region_dir)):
        if not fn.endswith(".mca"):
            continue
        data = open(os.path.join(region_dir, fn), "rb").read()
        for i in range(1024 if len(data) >= 8192 else 0):
            off = struct.unpack(">I", b"\0" + data[i * 4:i * 4 + 3])[0]
            if off == 0:
                continue
            o = off * 4096
            ln = struct.unpack(">I", data[o:o + 4])[0]
            if data[o + 4] != 2:
                continue
            ch = _nbt(zlib.decompress(data[o + 5:o + 4 + ln]))
            cx, cz = ch["xPos"], ch["zPos"]
            for sec in ch.get("sections", []):
                bs = sec.get("block_states")
                sy = sec["Y"]
                if not bs or sy * 16 > y1 or sy * 16 + 15 < y0:
                    continue
                pal = []
                for p in bs["palette"]:
                    n = p["Name"].replace("minecraft:", "")
                    if p.get("Properties"):
                        n += "[" + ",".join(f"{k}={v}" for k, v in sorted(p["Properties"].items())) + "]"
                    pal.append(AIR if n in ("air", "cave_air", "void_air") else kind(n) or -1)
                if len(pal) == 1:
                    if pal[0] == AIR:
                        continue
                    idx_of = lambda j: 0  # noqa: E731
                else:
                    bits = max(4, (len(pal) - 1).bit_length())
                    per, mask, arr = 64 // bits, (1 << bits) - 1, bs["data"]
                    idx_of = lambda j: ((arr[j // per] & 0xFFFFFFFFFFFFFFFF) >> ((j % per) * bits)) & mask  # noqa: E731
                for j in range(4096):
                    y = sy * 16 + (j >> 8)
                    if y < y0 or y > y1:
                        continue
                    idx = idx_of(j)
                    k = pal[idx] if idx < len(pal) else AIR
                    if k == AIR:
                        continue
                    # -1: a passable block that is not air (grass, flowers, carpets): free to walk through
                    out[(cx * 16 + (j & 15), y, cz * 16 + ((j >> 4) & 15))] = AIR if k == -1 else k
    return out


def measure(plugin, dungeon, png=None):
    edits = yaml.safe_load(open(os.path.join(plugin, "edits", dungeon + ".yml")))["map"]
    spawns, objectives = edits.get("spawns", []), set(edits.get("objectives", []))
    if not spawns:
        return []
    ys = [s["at"][1] for s in spawns]
    blocks = read(os.path.join(plugin, "maps", "custom", dungeon, "region"), int(min(ys)) - 6, int(max(ys)) + 8)
    closed = set()
    for d in edits.get("doors", []):
        (x0, ya, z0), (x1, yb, z1) = d["from"], d["to"]
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(ya, yb), max(ya, yb) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    closed.add((x, y, z))

    def at(x, y, z):
        return SOLID if (x, y, z) in closed else blocks.get((x, y, z), AIR)

    def stand(x, y, z):
        return at(x, y, z) == AIR and at(x, y + 1, z) == AIR and at(x, y - 1, z) == SOLID

    rooms = {}
    for s in spawns:
        rooms.setdefault(str(s["room"]), []).append(s)
    out = []
    for room in sorted(rooms, key=lambda r: (len(r), r)):
        sp = rooms[room]
        boss = all(s["mob"] in objectives for s in sp)
        bx0, bx1 = min(s["at"][0] for s in sp) - REACH, max(s["at"][0] for s in sp) + REACH
        bz0, bz1 = min(s["at"][2] for s in sp) - REACH, max(s["at"][2] for s in sp) + REACH
        seen, todo = set(), []
        for s in sp:
            x, y, z = math.floor(s["at"][0]), int(s["at"][1]), math.floor(s["at"][2])
            for dy in (0, 1, -1, 2, -2):
                if stand(x, y + dy, z):
                    todo.append((x, y + dy, z))
                    break
        while todo:
            c = todo.pop()
            if c in seen:
                continue
            seen.add(c)
            x, y, z = c
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, nz = x + dx, z + dz
                if not (bx0 <= nx <= bx1 and bz0 <= nz <= bz1):
                    continue
                for dy in (0, 1, -1):
                    n = (nx, y + dy, nz)
                    if n not in seen and stand(*n):
                        todo.append(n)
                        break
        floor = {(x, z) for x, _, z in seen}
        open5 = {(x, z) for x, z in floor if all((x + dx, z + dz) in floor for dx in range(-2, 3) for dz in range(-2, 3))}
        # the largest free square: grow around every open cell (cheap enough at these sizes)
        best = 0
        clear = {c: 2 for c in open5}
        frontier = set(open5)
        k = 2
        while frontier:
            best = 2 * k + 1
            k += 1
            nxt = set()
            for x, z in frontier:
                if all((x + dx, z + dz) in floor for dx in range(-k, k + 1) for dz in (-k, k)) and \
                        all((x + dx, z + dz) in floor for dz in range(-k, k + 1) for dx in (-k, k)):
                    nxt.add((x, z))
            frontier = nxt
        n = len(sp)
        per = len(open5) / n
        tight = not boss and (per < OPEN_PER_MOB or best < MIN_SQUARE)
        out.append((room, n, len(floor), len(open5), per, best, boss, tight))
        if png:
            from gridlib import png as write_png
            xs, zs = [c[0] for c in floor], [c[1] for c in floor]
            x0, z0, w, h = min(xs), min(zs), max(xs) - min(xs) + 1, max(zs) - min(zs) + 1
            pix = [(20, 20, 28)] * (w * h)
            for x, z in floor:
                pix[(z - z0) * w + x - x0] = (70, 150, 80) if (x, z) in open5 else (150, 120, 60)
            for s in sp:
                x, z = math.floor(s["at"][0]), math.floor(s["at"][2])
                if x0 <= x < x0 + w and z0 <= z < z0 + h:
                    pix[(z - z0) * w + x - x0] = (230, 60, 60)
            os.makedirs(png, exist_ok=True)
            write_png(os.path.join(png, f"{dungeon}_room{room}.png"), w, h, pix, 4)
    return out


def main():
    args = sys.argv[1:]
    png = None
    if "--png" in args:
        i = args.index("--png")
        png = args[i + 1]
        del args[i:i + 2]
    plugin = args[0]
    dungeons = args[1:] or sorted(f[:-4] for f in os.listdir(os.path.join(plugin, "edits")) if f.endswith(".yml"))
    bad = 0
    for d in dungeons:
        print(f"== {d}")
        for room, n, floor, open5, per, best, boss, tight in measure(plugin, d, png):
            note = "boss" if boss else ("TIGHT" if tight else "")
            print(f"  room {room}: {n:2d} monsters, floor {floor:5d}, open {open5:5d} ({per:5.0f} per monster), "
                  f"largest free square {best:2d}  {note}")
            bad += tight
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
