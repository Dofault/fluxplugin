#!/usr/bin/env python3
"""Shared helpers of the dungeon map scripts (gen_roche.py, gen_pecheur.py, gen_moisson.py...).

A script describes its map with a Map: blocks (fill / setblock, a fill larger than the game allows is cut in slices),
the start, the monsters by room and the doors. `run(map, server, folder, dungeon)` then:
  1. creates a void world on the server (Multiverse), builds the map in it by console commands (RCON),
  2. saves it and copies its region folder into plugins/FluxDungeons/maps/custom/<dungeon> (the old one is replaced),
  3. deletes the temporary world,
  4. writes plugins/FluxDungeons/edits/<dungeon>.yml (start, monsters, rooms, doors, objectives), which wins over the
     points of dungeons/<dungeon>.yml.
Never on production: the scripts are run against Labs (`labs`).
"""
import os
import shutil
import socket
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, *[".."] * 5))
sys.path.insert(0, os.path.join(ROOT, "serveurs", "00_scripts"))
import rcon  # noqa: E402

SERVERS = {"labs": "04_labs", "semilab": "05_semilab"}
MAX_FILL = 32768


class Map:
    def __init__(self):
        self.cmds = []
        self.start = [0.5, 64.0, 0.5, 0.0, 0.0]
        self.spawns = []
        self.doors = []
        self.objectives = []
        self.box = None  # x0, z0, x1, z1 forceloaded while building

    # ------------------------------------------------------------ blocks
    def fill(self, x0, y0, z0, x1, y1, z1, block, mode=""):
        x0, x1 = sorted((x0, x1))
        y0, y1 = sorted((y0, y1))
        z0, z1 = sorted((z0, z1))
        size = (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1)
        if size > MAX_FILL:
            # cut along the longest axis
            dx, dy, dz = x1 - x0, y1 - y0, z1 - z0
            if dz >= dx and dz >= dy:
                m = (z0 + z1) // 2
                self.fill(x0, y0, z0, x1, y1, m, block, mode)
                self.fill(x0, y0, m + 1, x1, y1, z1, block, mode)
            elif dx >= dy:
                m = (x0 + x1) // 2
                self.fill(x0, y0, z0, m, y1, z1, block, mode)
                self.fill(m + 1, y0, z0, x1, y1, z1, block, mode)
            else:
                m = (y0 + y1) // 2
                self.fill(x0, y0, z0, x1, m, z1, block, mode)
                self.fill(x0, m + 1, z0, x1, y1, z1, block, mode)
            return
        self.cmds.append(f"fill {x0} {y0} {z0} {x1} {y1} {z1} {block}{(' ' + mode) if mode else ''}")

    def set(self, x, y, z, block):
        self.cmds.append(f"setblock {x} {y} {z} {block}")

    def replace(self, x0, y0, z0, x1, y1, z1, block, what):
        self.fill(x0, y0, z0, x1, y1, z1, block, f"replace {what}")

    def disc(self, cx, cz, r, y0, y1, block):
        """A vertical cylinder of radius r (one fill per row of z)."""
        import math
        for z in range(int(cz - r), int(cz + r) + 1):
            half = int(math.sqrt(max(0, r * r - (z - cz) ** 2)))
            self.fill(cx - half, y0, z, cx + half, y1, z, block)

    # ------------------------------------------------------------ game points
    def mob(self, mob, x, z, y=64, yaw=0.0, room="1"):
        self.spawns.append({"mob": mob, "at": [x + 0.5, float(y), z + 0.5, float(yaw)], "room": str(room)})

    def door(self, id, frm, to, room, kills=0, type="kills", radius=4.0):
        self.doors.append({"id": id, "type": type, "from": list(frm), "to": list(to), "room": str(room),
                           "kills": kills, "radius": float(radius)})

    # ------------------------------------------------------------ output
    def edits_yaml(self, dungeon):
        out = [f"# Points of the dungeon written by assets/maps/gen_{dungeon}.py (map, monsters, rooms, doors): they",
               f"# replace those of dungeons/{dungeon}.yml. Rerun the script rather than editing by hand; small moves in",
               "# game (/fluxdungeons edit) are kept here until the next run of the script.", "", "map:", "  start:"]
        out += [f"  - {v}" for v in self.start]
        out.append("  objectives:" + ("" if self.objectives else " []"))
        out += [f"  - {o}" for o in self.objectives]
        out.append("  spawns:")
        for s in self.spawns:
            out.append(f"  - mob: {s['mob']}")
            out.append("    at: [" + ", ".join(str(v) for v in s["at"]) + "]")
            out.append(f"    room: '{s['room']}'")
        out.append("  doors:" + ("" if self.doors else " []"))
        for d in self.doors:
            out.append(f"  - id: {d['id']}")
            out.append(f"    type: {d['type']}")
            out.append("    from: [" + ", ".join(str(v) for v in d["from"]) + "]")
            out.append("    to: [" + ", ".join(str(v) for v in d["to"]) + "]")
            out.append(f"    room: '{d['room']}'")
            out.append(f"    kills: {d['kills']}")
            out.append(f"    radius: {d['radius']}")
        return "\n".join(out) + "\n"


class Console:
    def __init__(self, server):
        p = rcon.props(server)
        self.s = socket.create_connection(("127.0.0.1", int(p["rcon.port"])), timeout=60)
        self.s.sendall(rcon.packet(1, 3, p["rcon.password"]))
        if rcon.read(self.s)[0] == -1:
            sys.exit("RCON password refused")
        self.n = 2

    def send(self, cmd):
        self.n += 1
        self.s.sendall(rcon.packet(self.n, 2, cmd))
        return rcon.read(self.s)[1]


BAD = ("Could not", "Unknown", "not loaded", "Too many", "Incorrect", "Expected", "Invalid", "No blocks")


def delete_world(c, world):
    import re
    out = c.send(f"mv delete {world}")
    code = re.search(r"mv confirm (\d+)", out)
    print(c.send(f"mv confirm {code.group(1)}") if code else out)


def run(m, server, dungeon, world=None, keep_world=False):
    world = world or f"fdbuild_{dungeon}"
    base = os.path.join(ROOT, "serveurs", SERVERS[server])
    c = Console(server)
    out = c.send(f'mv create {world} normal --world-type flat --no-structures --generator-settings '
                 '{"layers":[{"block":"minecraft:air","height":1}],"biome":"minecraft:plains"}')
    print(out.splitlines()[-1] if out else "(created)")
    time.sleep(2)
    x0, z0, x1, z1 = m.box
    print(c.send(f"execute in minecraft:{world} run forceload add {x0} {z0} {x1} {z1}"))
    for rule in ("doDaylightCycle false", "doMobSpawning false", "doFireTick false", "randomTickSpeed 0", "doTileDrops false"):
        c.send(f"execute in minecraft:{world} run gamerule {rule}")
    c.send(f"execute in minecraft:{world} run time set 6000")
    errors = 0
    t0 = time.time()
    for i, cmd in enumerate(m.cmds):
        o = c.send(f"execute in minecraft:{world} run {cmd}")
        if o and any(b in o for b in BAD):
            errors += 1
            if errors < 40:
                print(cmd, "->", o)
    print(f"{len(m.cmds)} commands, {errors} errors, {time.time() - t0:.0f} s")
    print(c.send(f"execute in minecraft:{world} run forceload remove all"))
    print(c.send("save-all flush"))
    time.sleep(4)
    src = os.path.join(base, "world", "dimensions", "minecraft", world)
    dst = os.path.join(base, "plugins", "FluxDungeons", "maps", "custom", dungeon)
    for sub in ("region", "entities", "poi"):
        if os.path.isdir(os.path.join(src, sub)):
            shutil.rmtree(os.path.join(dst, sub), ignore_errors=True)
            shutil.copytree(os.path.join(src, sub), os.path.join(dst, sub))
    print("copied into", dst)
    if not keep_world:
        delete_world(c, world)
    edits = os.path.join(base, "plugins", "FluxDungeons", "edits", f"{dungeon}.yml")
    with open(edits, "w") as f:
        f.write(m.edits_yaml(dungeon))
    print("written", edits)


# ---------------------------------------------------------------- reading a map (copy of an existing build)

def _nbt(buf):
    import io
    import struct
    b = io.BytesIO(buf)

    def rd(n):
        return b.read(n)

    def payload(t):
        if t == 1: return struct.unpack('>b', rd(1))[0]
        if t == 2: return struct.unpack('>h', rd(2))[0]
        if t == 3: return struct.unpack('>i', rd(4))[0]
        if t == 4: return struct.unpack('>q', rd(8))[0]
        if t == 5: return struct.unpack('>f', rd(4))[0]
        if t == 6: return struct.unpack('>d', rd(8))[0]
        if t == 7: return rd(struct.unpack('>i', rd(4))[0])
        if t == 8: return rd(struct.unpack('>H', rd(2))[0]).decode('utf-8', 'replace')
        if t == 9:
            et = rd(1)[0]
            return [payload(et) for _ in range(struct.unpack('>i', rd(4))[0])]
        if t == 10:
            d = {}
            while True:
                tt = rd(1)[0]
                if tt == 0:
                    return d
                name = rd(struct.unpack('>H', rd(2))[0]).decode('utf-8', 'replace')
                d[name] = payload(tt)
        if t == 11:
            n = struct.unpack('>i', rd(4))[0]
            return list(struct.unpack('>%di' % n, rd(4 * n)))
        if t == 12:
            n = struct.unpack('>i', rd(4))[0]
            return list(struct.unpack('>%dq' % n, rd(8 * n)))
        raise ValueError(t)
    t = rd(1)[0]
    rd(struct.unpack('>H', rd(2))[0])
    return payload(t)


def read_blocks(region_dir):
    """Every non-air block of a map folder's region files: {(x, y, z): 'name[prop=value,...]'}."""
    import struct
    import zlib
    out = {}
    for fn in os.listdir(region_dir):
        if not fn.endswith('.mca'):
            continue
        data = open(os.path.join(region_dir, fn), 'rb').read()
        for i in range(1024 if len(data) >= 8192 else 0):
            off = struct.unpack('>I', b'\0' + data[i * 4:i * 4 + 3])[0]
            if off == 0:
                continue
            o = off * 4096
            ln = struct.unpack('>I', data[o:o + 4])[0]
            if data[o + 4] != 2:
                continue
            ch = _nbt(zlib.decompress(data[o + 5:o + 4 + ln]))
            cx, cz = ch['xPos'], ch['zPos']
            for sec in ch.get('sections', []):
                bs = sec.get('block_states')
                if not bs:
                    continue
                pal = []
                for p in bs['palette']:
                    n = p['Name'].replace('minecraft:', '')
                    if p.get('Properties'):
                        n += '[' + ','.join(f"{k}={v}" for k, v in sorted(p['Properties'].items())) + ']'
                    pal.append(n)
                sy = sec['Y']
                if len(pal) == 1:
                    if pal[0] in ('air', 'cave_air', 'void_air'):
                        continue
                    for j in range(4096):
                        out[(cx * 16 + (j & 15), sy * 16 + (j >> 8), cz * 16 + ((j >> 4) & 15))] = pal[0]
                    continue
                bits = max(4, (len(pal) - 1).bit_length())
                per = 64 // bits
                mask = (1 << bits) - 1
                arr = bs['data']
                for j in range(4096):
                    idx = ((arr[j // per] & 0xFFFFFFFFFFFFFFFF) >> ((j % per) * bits)) & mask
                    n = pal[idx] if idx < len(pal) else 'air'
                    if n in ('air', 'cave_air', 'void_air'):
                        continue
                    out[(cx * 16 + (j & 15), sy * 16 + (j >> 8), cz * 16 + ((j >> 4) & 15))] = n
    return out
