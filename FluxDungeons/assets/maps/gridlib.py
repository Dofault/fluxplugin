#!/usr/bin/env python3
"""Block model shared by the dungeon map scripts that design their map in memory (gen_pecheur.py, gen_moisson.py).

A `Model` holds every block of the map (vanilla ids with states), so a script can read what it built (ceilings,
floors), check it before anything is sent (doors that cannot be bypassed, water that stays where it is put, gravity
blocks resting on something), draw PNG slices of it, then turn it into merged fill commands for maplib.run.

Checks:
  - `check_basic()`: falling sand/gravel, water flowing where it should not (waterfalls declared in `falls` excepted);
  - `reach(start, opened, blocked)`: every open cell reachable from start, as if flying (caves and closed rooms);
  - `walk(start, opened, blocked)`: the cells a player can really stand in, walking, jumping one block, falling and
    swimming (open-air maps, where flying would go over every wall). Fences, walls and gates count as 1.5 high.
"""
import math
import os
import random
import struct
import zlib
from collections import deque

PASS_WORDS = ("seagrass", "kelp", "coral_fan", "light[", "cobweb", "lichen", "vine", "short_grass", "tall_grass", "fern",
              "dandelion", "poppy", "cornflower", "oxeye_daisy", "azure_bluet", "allium", "red_tulip", "orange_tulip",
              "white_tulip", "pink_tulip", "lily_of_the_valley", "wheat[", "carrots", "potatoes", "beetroots", "sweet_berry",
              "dead_bush", "brown_mushroom", "red_mushroom", "torch", "rail", "lever", "button", "pressure_plate",
              "sugar_cane", "leaf_litter", "wildflowers", "firefly_bush", "bush", "pumpkin_stem", "melon_stem", "sapling",
              "lily_pad", "snow[layers=1]", "carpet", "redstone_wire", "tripwire", "pink_petals", "glow_lichen")
GRAVITY = ("sand", "gravel", "suspicious_sand", "red_sand")
ATTACH = ("pointed_dripstone", "lantern[", "seagrass", "kelp", "coral_fan", "sea_pickle", "iron_chain", "conduit",
          "lightning_rod", "grindstone", "anvil", "cobweb", "wheat[", "carrots", "potatoes", "beetroots", "short_grass",
          "tall_grass", "fern", "dandelion", "poppy", "cornflower", "oxeye_daisy", "azure_bluet", "allium", "_tulip",
          "lily_of_the_valley", "sweet_berry", "bush", "brown_mushroom", "red_mushroom", "torch", "rail", "lily_pad",
          "sugar_cane", "leaf_litter", "wildflowers", "pink_petals", "_stem", "sapling", "carpet", "bell", "flower_pot",
          "potted_", "ladder", "_door", "trapdoor", "_sign", "banner", "candle")
COLORS = {"air": (20, 20, 28), "water": (40, 90, 200), "packed_ice": (150, 180, 230), "blue_ice": (110, 160, 250),
          "dark_prismarine": (40, 90, 80), "prismarine_bricks": (90, 170, 150), "prismarine": (100, 160, 150),
          "spruce_planks": (120, 85, 50), "sea_lantern": (240, 250, 240), "iron_bars": (200, 60, 60), "barrier": (255, 0, 255),
          "grass_block": (90, 150, 60), "farmland": (110, 75, 40), "dirt_path": (150, 120, 70), "oak_leaves": (40, 110, 40),
          "hay_block": (220, 190, 60), "oak_planks": (170, 135, 80), "dark_oak": (70, 45, 25), "cobblestone": (120, 120, 120),
          "stone_bricks": (140, 140, 140), "dirt": (120, 85, 55), "coarse_dirt": (110, 80, 55), "gravel": (130, 125, 120)}


def passable(b):
    return b in ("air", "water") or any(w in b for w in PASS_WORDS) or b.endswith("_coral") or "_coral[" in b


def wet(b):
    return b == "water" or "waterlogged=true" in b or "seagrass" in b or "kelp" in b


def gravity(b):
    return b in GRAVITY or b.endswith("concrete_powder")


def attached(b):
    return any(a in b for a in ATTACH) or b.endswith("_coral") or "_coral[" in b


def tall(b):
    """Fences, walls and closed gates: 1.5 high, a player cannot jump on them from the ground."""
    if "fence_gate" in b:
        return "open=true" not in b
    return "fence" in b or b.endswith("_wall") or "_wall[" in b


class Noise:
    def __init__(self, seed):
        q = random.Random(seed)
        self.p = [(q.uniform(0.12, 0.42), q.uniform(0.12, 0.42), q.uniform(0.12, 0.42), q.uniform(0, 6.3), q.uniform(0.3, 1))
                  for _ in range(5)]
        self.t = sum(w for *_, w in self.p)

    def __call__(self, x, y, z):
        return sum(w * math.sin(x * a + y * b + z * c + ph) for a, b, c, ph, w in self.p) / self.t


class Model:
    def __init__(self, bounds, bands, seed=0, outside="bedrock"):
        self.X0, self.X1, self.Y0, self.Y1, self.Z0, self.Z1 = bounds
        self.bands = bands
        self.r = random.Random(seed)
        self.over = {}
        self.entities = []
        self.falls = set()       # water sources allowed to flow (waterfalls)
        self.doors = []          # ((x0, y0, z0), (x1, y1, z1)) in the order of maplib.Map.door
        self.outside = outside

    # ------------------------------------------------------------ blocks
    def base(self, y):
        for y0, y1, b in self.bands:
            if y0 <= y <= y1:
                return b
        return "air" if y > max(b[1] for b in self.bands) else "stone"

    def inside(self, x, y, z):
        return self.X0 <= x <= self.X1 and self.Y0 <= y <= self.Y1 and self.Z0 <= z <= self.Z1

    def get(self, x, y, z):
        if not self.inside(x, y, z):
            return self.outside
        return self.over.get((x, y, z)) or self.base(y)

    def set(self, x, y, z, b):
        if self.inside(x, y, z):
            self.over[(x, y, z)] = b

    def box(self, x0, y0, z0, x1, y1, z1, b, where=None):
        x0, x1 = sorted((x0, x1))
        y0, y1 = sorted((y0, y1))
        z0, z1 = sorted((z0, z1))
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    if where is None or where(self.get(x, y, z)):
                        self.set(x, y, z, b(x, y, z) if callable(b) else b)

    def rock(self, x, y, z):
        return (x, y, z) not in self.over

    def pick(self, table):
        tot = sum(w for _, w in table)
        v = self.r.uniform(0, tot)
        for b, w in table:
            v -= w
            if v <= 0:
                return b
        return table[-1][0]

    def blob(self, cx, cy, cz, rx, ry, rz, clip, seed, rough=0.3, block="air", where=None):
        """An irregular ellipsoid, clipped to clip = (x0, y0, z0, x1, y1, z1)."""
        n = Noise(seed)
        cx0, cy0, cz0, cx1, cy1, cz1 = clip
        for x in range(max(cx0, int(cx - rx * 1.4)), min(cx1, int(cx + rx * 1.4)) + 1):
            for y in range(max(cy0, int(cy - ry * 1.4)), min(cy1, int(cy + ry * 1.4)) + 1):
                for z in range(max(cz0, int(cz - rz * 1.4)), min(cz1, int(cz + rz * 1.4)) + 1):
                    d = ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 + ((z - cz) / rz) ** 2
                    if d <= 1 + rough * n(x, y, z):
                        if where is None or where(self.get(x, y, z)):
                            self.set(x, y, z, block(x, y, z) if callable(block) else block)

    def ceiling(self, x, y, z, limit=30):
        """First solid y above (x, y, z) (which is open)."""
        for yy in range(y, y + limit):
            if not passable(self.get(x, yy, z)):
                return yy
        return None

    def floor_below(self, x, y, z, limit=30):
        for yy in range(y, y - limit, -1):
            if not passable(self.get(x, yy, z)):
                return yy
        return None

    def stalactite(self, x, y, z, length, mats, tip=None):
        """Hanging from the ceiling above (x, y, z): mats from the top down, then the tip block."""
        top = self.ceiling(x, y, z)
        if top is None:
            return
        yy = top - 1
        for i in range(length):
            if self.get(x, yy, z) != "air":
                return
            last = i == length - 1
            self.set(x, yy, z, tip if (last and tip) else mats[min(i, len(mats) - 1)])
            yy -= 1

    @staticmethod
    def _drip(length):
        if length == 1:
            return ["tip"]
        return ["base"] + ["middle"] * max(0, length - 3) + (["frustum"] if length > 2 else []) + ["tip"]

    def dripstone_down(self, x, y, z, length):
        top = self.ceiling(x, y, z)
        if top is None:
            return
        yy = top - 1
        for p in self._drip(length):
            if self.get(x, yy, z) != "air":
                return
            self.set(x, yy, z, f"pointed_dripstone[thickness={p},vertical_direction=down]")
            yy -= 1

    def dripstone_up(self, x, y, z, length):
        fl = self.floor_below(x, y, z)
        if fl is None:
            return
        yy = fl + 1
        for p in self._drip(length):
            if self.get(x, yy, z) != "air":
                return
            self.set(x, yy, z, f"pointed_dripstone[thickness={p},vertical_direction=up]")
            yy += 1

    def hang_chain(self, x, y, z, bottom, end=None):
        """A chain from the ceiling above (x, y, z) down to y bottom, with an end block under it."""
        top = self.ceiling(x, y, z)
        if top is None:
            return
        for yy in range(top - 1, bottom - 1, -1):
            if self.get(x, yy, z) != "air":
                return
            self.set(x, yy, z, "iron_chain[axis=y]")
        if end and self.get(x, bottom - 1, z) == "air":
            self.set(x, bottom - 1, z, end)

    def kelp(self, x, y0, z, h):
        for i in range(h):
            if self.get(x, y0 + i, z) != "water":
                return
            top = i == h - 1 or self.get(x, y0 + i + 1, z) != "water"
            self.set(x, y0 + i, z, "kelp[age=25]" if top else "kelp_plant")
            if top:
                return

    def pool(self, x, z, y, rad=1, rim="mossy_cobblestone", bed="stone"):
        """A small pool cut in the floor at y (a rim of rock kept around it)."""
        for dx in range(-rad - 1, rad + 2):
            for dz in range(-rad - 1, rad + 2):
                if abs(dx) <= rad and abs(dz) <= rad and dx * dx + dz * dz <= rad * rad + 1:
                    self.set(x + dx, y, z + dz, "water")
                    if passable(self.get(x + dx, y - 1, z + dz)):
                        self.set(x + dx, y - 1, z + dz, bed)
                elif passable(self.get(x + dx, y, z + dz)):
                    self.set(x + dx, y, z + dz, rim)

    def waterfall(self, x, z, y_floor):
        """A thin fall from the ceiling above (x, z) into a 1-block pool at y_floor."""
        self.pool(x, z, y_floor, 0)
        top = self.ceiling(x, y_floor + 1, z)
        if top is None:
            return
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if passable(self.get(x + dx, top, z + dz)):
                self.set(x + dx, top, z + dz, "stone")
        if passable(self.get(x, top + 1, z)):
            self.set(x, top + 1, z, "stone")
        self.set(x, top, z, "water")
        self.falls.add((x, top, z))

    # ------------------------------------------------------------ entities
    def item_display(self, x, y, z, item, yaw=0.0, roll=0.0, scale=1.0):
        s = math.sin(math.radians(roll) / 2)
        c = math.cos(math.radians(roll) / 2)
        self.entities.append(
            f'summon item_display {x} {y} {z} {{item:{{id:"minecraft:{item}",count:1}},item_display:"fixed",'
            f'Rotation:[{yaw}f,0f],transformation:{{left_rotation:[0f,0f,{s:.4f}f,{c:.4f}f],right_rotation:[0f,0f,0f,1f],'
            f'translation:[0f,0f,0f],scale:[{scale}f,{scale}f,{scale}f]}}}}')

    def fish(self, x, y, z):
        r = self.r
        kind = r.choice(["tropical_fish", "tropical_fish", "tropical_fish", "cod", "salmon", "pufferfish"])
        extra = f",Variant:{r.choice([65536, 917504, 918273, 16778497, 50660352, 67108865, 117441025, 101253888, 117899265, 235340288])}" \
            if kind == "tropical_fish" else ""
        self.entities.append(f"summon {kind} {x} {y} {z} {{PersistenceRequired:1b,Silent:1b{extra}}}")

    # ------------------------------------------------------------ checks
    def check_basic(self):
        errors = []
        for p in self.falls:
            if self.get(*p) != "water":
                errors.append(f"waterfall source overwritten at {p}: {self.get(*p)}")
        for (x, y, z), b in self.over.items():
            if gravity(b) and (passable(self.get(x, y - 1, z)) or attached(self.get(x, y - 1, z))):
                errors.append(f"falling {b} at {x} {y} {z}")
        for (x, y, z), b in self.over.items():
            if not wet(b) or (x, y, z) in self.falls:
                continue
            for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                n = self.get(x + dx, y + dy, z + dz)
                if n == "air" or (passable(n) and not wet(n) and n != "water"):
                    errors.append(f"water leaks at {x} {y} {z} -> {x + dx} {y + dy} {z + dz} ({n})")
                    break
        return errors

    def door_cells(self, open_doors):
        blocked, opened = set(), set()
        for i, (a, b2) in enumerate(self.doors):
            for x in range(a[0], b2[0] + 1):
                for y in range(a[1], b2[1] + 1):
                    for z in range(a[2], b2[2] + 1):
                        (opened if i in open_doors else blocked).add((x, y, z))
        return opened, blocked

    def reach(self, start, open_doors):
        opened, blocked = self.door_cells(open_doors)
        seen = {start}
        q = deque([start])
        while q:
            x, y, z = q.popleft()
            for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                p = (x + dx, y + dy, z + dz)
                if p in seen or p in blocked or not self.inside(*p):
                    continue
                if passable(self.get(*p)) or p in opened:
                    seen.add(p)
                    q.append(p)
        return seen

    def walk(self, start, open_doors, top=None):
        """Feet cells a walking player reaches. A cell is free when passable (or an open door); a player stands in a
        free cell with a free cell above and support under (any non-free block, or water). Moves: sideways to the same
        height, one block up (a 1.5-high fence or wall counts as 2), any drop down, and up through water."""
        opened, blocked = self.door_cells(open_doors)
        top = top if top is not None else self.Y1

        def free(p):
            if p in blocked:
                return False
            return p in opened or (self.inside(*p) and passable(self.get(*p)))

        def height(x, y, z):
            """Top of the support under feet cell (x, y, z): y (block), y + 0.5 (a tall block one lower)."""
            under = self.get(x, y - 1, z)
            if (x, y - 1, z) in opened:
                under = "air"
            if under == "water" or wet(under):
                return y
            if not passable(under) and (x, y - 1, z) not in opened:
                return y
            low = self.get(x, y - 2, z)
            if tall(low) and (x, y - 2, z) not in opened:
                return y - 0.5
            return None

        def can_stand(x, y, z):
            return free((x, y, z)) and free((x, y + 1, z)) and (height(x, y, z) is not None or self.get(x, y, z) == "water")

        def settle(x, y, z):
            """Fall from (x, y, z) down to the first place to stand."""
            for yy in range(y, self.Y0 - 1, -1):
                if not free((x, yy, z)):
                    return None
                if can_stand(x, yy, z):
                    return yy
            return None

        s = settle(*start)
        if s is None:
            return set()
        start = (start[0], s, start[2])
        seen = {start}
        q = deque([start])
        while q:
            x, y, z = q.popleft()
            h = height(x, y, z)
            h = y if h is None else h
            swim = self.get(x, y, z) == "water"
            nxt = []
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, nz = x + dx, z + dz
                # same level or a drop
                if free((nx, y, nz)) and free((nx, y + 1, nz)):
                    yy = settle(nx, y, nz)
                    if yy is not None:
                        nxt.append((nx, yy, nz))
                # a jump: needs room over our head
                if free((x, y + 2, z)) or swim:
                    for up in (1,):
                        ty = y + up
                        if can_stand(nx, ty, nz):
                            th = height(nx, ty, nz)
                            th = ty if th is None else th
                            if th - h <= 1.25 or swim:
                                nxt.append((nx, ty, nz))
                    # stand on a tall block (fence top) one lower: its top is at y + 1.5
            if swim and can_stand(x, y + 1, z):
                nxt.append((x, y + 1, z))
            if swim and free((x, y + 1, z)) and self.get(x, y + 1, z) == "water":
                nxt.append((x, y + 1, z))
            for p in nxt:
                if p not in seen and p[1] <= top:
                    seen.add(p)
                    q.append(p)
        return seen

    def settle_ground(self):
        """Grass, dirt paths and farmland under a solid block turn to dirt in the game (at once or at the next tick):
        do it in the model, or the change would come after the RPG swap and update the note block above."""
        unstable = ("grass_block", "dirt_path", "farmland", "mycelium", "podzol")
        for (x, y, z), b in list(self.over.items()):
            if passable(b) or attached(b) or "leaves" in b:
                continue
            below = self.get(x, y - 1, z)
            if below.split("[")[0] in unstable:
                self.set(x, y - 1, z, "dirt")

    # ------------------------------------------------------------ output
    def commands(self, m):
        """The model as merged fills (m.cmds): base bands, then the changed blocks by boxes, then attached blocks one
        by one (hanging ones from the top down), then the entities."""
        self.settle_ground()
        cmds = [(self.X0, y0, self.Z0, self.X1, y1, self.Z1, b) for y0, y1, b in self.bands]
        first, second = {}, []
        for p, b in self.over.items():
            if b == self.base(p[1]):
                continue
            if attached(b):
                second.append((p, b))
                first[p] = "water" if wet(b) else "air"
            else:
                first[p] = b
        layers = {}
        for (x, y, z), b in first.items():
            layers.setdefault(y, {}).setdefault(z, {})[x] = b
        boxes = {}
        open_boxes = {}
        for y in sorted(layers):
            rects = []
            active = {}
            rows = layers[y]
            for z in sorted(rows):
                row = rows[z]
                runs = []
                xs = sorted(row)
                i = 0
                while i < len(xs):
                    j = i
                    while j + 1 < len(xs) and xs[j + 1] == xs[j] + 1 and row[xs[j + 1]] == row[xs[i]]:
                        j += 1
                    runs.append((xs[i], xs[j], row[xs[i]]))
                    i = j + 1
                new_active = {}
                for run in runs:
                    if run in active and active[run][1] == z - 1:
                        new_active[run] = (active[run][0], z)
                    else:
                        new_active[run] = (z, z)
                for run, (z0, z1) in active.items():
                    if run not in new_active or new_active[run][0] != z0:
                        rects.append((run[0], z0, run[1], z1, run[2]))
                active = new_active
            for run, (z0, z1) in active.items():
                rects.append((run[0], z0, run[1], z1, run[2]))
            nxt = {}
            for rc in rects:
                if rc in open_boxes and open_boxes[rc][1] == y - 1:
                    nxt[rc] = (open_boxes[rc][0], y)
                else:
                    nxt[rc] = (y, y)
            for rc, (ya, yb) in open_boxes.items():
                if rc not in nxt or nxt[rc][0] != ya:
                    boxes[(rc, ya)] = yb
            open_boxes = nxt
        for rc, (ya, yb) in open_boxes.items():
            boxes[(rc, ya)] = yb
        out = list(cmds)
        for ((x0, z0, x1, z1, b), ya), yb in sorted(boxes.items(), key=lambda kv: kv[0][1]):
            out.append((x0, ya, z0, x1, yb, z1, b))
        m.cmds = []
        for x0, y0, z0, x1, y1, z1, b in out:
            if (x0, y0, z0) == (x1, y1, z1):
                m.cmds.append(f"setblock {x0} {y0} {z0} {b}")
            else:
                m.fill(x0, y0, z0, x1, y1, z1, b)
        hanging = sorted([s for s in second if "vertical_direction=down" in s[1] or "hanging=true" in s[1] or "chain" in s[1]],
                         key=lambda s: -s[0][1])
        rest = sorted([s for s in second if s not in hanging], key=lambda s: s[0][1])
        for (x, y, z), b in hanging + rest:
            m.cmds.append(f"setblock {x} {y} {z} {b}")
        m.cmds += self.entities

    # ------------------------------------------------------------ preview
    @staticmethod
    def color(b):
        if passable(b) and b not in ("air", "water"):
            return (60, 140, 60)
        for k, c in COLORS.items():
            if b.startswith(k):
                return c
        return (110, 110, 110)

    def png_slice(self, path, y=None, x=None, k=4, top_down=False):
        """A horizontal slice at y, a vertical one at x, or (top_down) the first block seen from the sky."""
        if x is None:
            w, h = self.X1 - self.X0 + 1, self.Z1 - self.Z0 + 1
            pix = []
            for z in range(self.Z0, self.Z1 + 1):
                for xx in range(self.X1, self.X0 - 1, -1):
                    if top_down:
                        b = "air"
                        for yy in range(self.Y1, self.Y0 - 1, -1):
                            b = self.get(xx, yy, z)
                            if b != "air" and not b.startswith("light") and b != "barrier":
                                break
                        pix.append(self.color(b))
                    else:
                        pix.append(self.color(self.get(xx, y, z)))
        else:
            w, h = self.Z1 - self.Z0 + 1, self.Y1 - self.Y0 + 1
            pix = [self.color(self.get(x, yy, z)) for yy in range(self.Y1, self.Y0 - 1, -1) for z in range(self.Z0, self.Z1 + 1)]
        png(path, w, h, pix, k)


def png(path, w, h, pix, k=4):
    pix = [pix[(row // k) * w + col // k] for row in range(h * k) for col in range(w * k)]
    w, h = w * k, h * k
    raw = b"".join(b"\x00" + bytes(c for px in pix[row * w:(row + 1) * w] for c in px) for row in range(h))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
