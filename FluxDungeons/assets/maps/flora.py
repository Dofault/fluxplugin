#!/usr/bin/env python3
"""Trees, stumps, logs and plants for the open-air dungeon maps built on a gridlib.Model (gen_sylviculteur.py).

Every tree is drawn by hand from its family (small, medium, big with branches, conifer, gnarled, dead, colossal),
never twice the same (heights, crowns and branches drawn from the model's random); leaves are placed persistent (they
would decay in a run's copy) and only into air; lying logs carry their axis.
"""
import math

import gridlib

SPECIES = {
    "oak": ("oak_log", "oak_leaves", "stripped_oak_log"),
    "birch": ("birch_log", "birch_leaves", "stripped_birch_log"),
    "spruce": ("spruce_log", "spruce_leaves", "stripped_spruce_log"),
    "dark_oak": ("dark_oak_log", "dark_oak_leaves", "stripped_dark_oak_log"),
    "jungle": ("jungle_log", "jungle_leaves", "stripped_jungle_log"),
    "acacia": ("acacia_log", "acacia_leaves", "stripped_acacia_log"),
    "cherry": ("cherry_log", "cherry_leaves", "stripped_cherry_log"),
    "pale_oak": ("pale_oak_log", "pale_oak_leaves", "stripped_pale_oak_log"),
    "mangrove": ("mangrove_log", "mangrove_leaves", "stripped_mangrove_log"),
}
SOILS = ("grass_block", "dirt", "coarse_dirt", "rooted_dirt", "podzol", "moss_block", "mud", "farmland", "mycelium",
         "clay", "sand", "red_sand", "pale_moss_block")
TALL = ("lilac", "rose_bush", "peony", "sunflower", "large_fern", "pitcher_plant", "tall_grass")


class Flora:
    def __init__(self, g, noise_seed=777):
        self.g = g
        self.r = g.r
        self.n = gridlib.Noise(noise_seed)

    # ------------------------------------------------------------ basics
    def put(self, x, y, z, b):
        if self.g.get(x, y, z) == "air":
            self.g.set(x, y, z, b)

    def leaves(self, kind):
        return f"{SPECIES[kind][1]}[persistent=true]"

    def log(self, kind, axis="y"):
        return SPECIES[kind][0] + ("" if axis == "y" else f"[axis={axis}]")

    def plant(self, x, y, z, b):
        g = self.g
        soil = g.get(x, y - 1, z).split("[")[0]
        if soil not in SOILS or g.get(x, y, z) != "air":
            return
        base = b.split("[")[0]
        if base in TALL:
            if g.get(x, y + 1, z) != "air":
                return
            g.set(x, y, z, f"{base}[half=lower]")
            g.set(x, y + 1, z, f"{base}[half=upper]")
            return
        g.set(x, y, z, b)

    def blob_leaves(self, cx, cy, cz, rx, ry, kind, density=0.92, mix=None):
        for dx in range(-int(rx) - 1, int(rx) + 2):
            for dy in range(-int(ry) - 1, int(ry) + 2):
                for dz in range(-int(rx) - 1, int(rx) + 2):
                    d = (dx / rx) ** 2 + (dz / rx) ** 2 + (dy / ry) ** 2
                    if d <= 1 + 0.18 * self.n(cx + dx, cy + dy, cz + dz) and self.r.random() < density:
                        b = self.leaves(kind)
                        if mix and self.r.random() < mix[1]:
                            b = self.leaves(mix[0])
                        self.put(cx + dx, cy + dy, cz + dz, b)

    def trunk(self, x, z, y0, h, kind, size=1):
        for dx in range(size):
            for dz in range(size):
                self.g.box(x + dx, y0, z + dz, x + dx, y0 + h - 1, z + dz, self.log(kind))

    def branch(self, x, y, z, kind, length, rise=0.35, ang=None):
        """A branch leaving the trunk at (x, y, z): logs along its axis, rising a little, a cluster of leaves at its end."""
        a = ang if ang is not None else self.r.uniform(0, 2 * math.pi)
        dx, dz = math.cos(a), math.sin(a)
        axis = "x" if abs(dx) >= abs(dz) else "z"
        end = (x, y, z)
        for t in range(1, length + 1):
            px, py, pz = round(x + dx * t), round(y + rise * t), round(z + dz * t)
            if self.g.get(px, py, pz) in ("air",) or "leaves" in self.g.get(px, py, pz):
                self.g.set(px, py, pz, self.log(kind, axis))
            end = (px, py, pz)
        return end

    # ------------------------------------------------------------ tree families
    def tree(self, x, z, y, kind="oak", family="medium"):
        r = self.r
        if family == "small":
            h = r.randint(3, 5)
            self.trunk(x, z, y, h, kind)
            self.blob_leaves(x, y + h, z, r.uniform(1.8, 2.6), 1.6, kind)
        elif family == "medium":
            h = r.randint(5, 8)
            self.trunk(x, z, y, h, kind)
            for _ in range(r.randint(0, 2)):
                e = self.branch(x, y + h - r.randint(2, 3), z, kind, r.randint(2, 3))
                self.blob_leaves(e[0], e[1] + 1, e[2], 2.0, 1.4, kind)
            self.blob_leaves(x, y + h, z, r.uniform(2.6, 3.4), 2.0, kind)
        elif family == "big":
            h = r.randint(10, 15)
            self.trunk(x, z, y, h, kind, 2)
            for k in range(r.randint(3, 5)):
                yy = y + h - r.randint(2, 7)
                e = self.branch(x + r.randint(0, 1), yy, z + r.randint(0, 1), kind, r.randint(3, 6), ang=k * 1.6 + r.uniform(-0.4, 0.4))
                self.blob_leaves(e[0], e[1] + 1, e[2], r.uniform(2.5, 3.5), 2.0, kind)
            self.blob_leaves(x, y + h + 1, z, r.uniform(4, 5.5), 2.6, kind)
        elif family == "conifer":
            wide = r.random() < 0.4
            h = r.randint(12, 20) if wide else r.randint(8, 14)
            self.trunk(x, z, y, h, kind, 2 if wide else 1)
            base = y + max(3, h // 4)
            for yy in range(base, y + h + 2):
                t = (yy - base) / max(1, y + h + 1 - base)
                rad = (1 - t) * (4.5 if wide else 3) + 0.6
                if (yy - base) % 3 == 2:
                    rad -= 1
                for dx in range(-int(rad) - 1, int(rad) + 2):
                    for dz in range(-int(rad) - 1, int(rad) + 2):
                        if math.hypot(dx, dz) <= rad and r.random() < 0.9:
                            self.put(x + dx + (1 if wide and dx >= 0 else 0) * 0, yy, z + dz, self.leaves(kind))
            self.put(x, y + h + 2, z, self.leaves(kind))
        elif family == "gnarled":
            h = r.randint(6, 10)
            cx, cz = x, z
            for k in range(h):
                if k > 1 and r.random() < 0.3:
                    step = r.choice([(1, 0), (-1, 0), (0, 1), (0, -1)])
                    self.g.set(cx + step[0], y + k, cz + step[1], self.log(kind, "x" if step[0] else "z"))
                    cx, cz = cx + step[0], cz + step[1]
                self.g.set(cx, y + k, cz, self.log(kind))
            e = self.branch(cx, y + h - 2, cz, kind, r.randint(2, 4))
            self.blob_leaves(e[0], e[1] + 1, e[2], 2.2, 1.5, kind)
            self.blob_leaves(cx, y + h, cz, r.uniform(2.5, 3.5), 1.8, kind)
        elif family == "dead":
            h = r.randint(6, 11)
            self.trunk(x, z, y, h, kind)
            for _ in range(r.randint(2, 4)):
                self.branch(x, y + r.randint(3, h - 1), z, kind, r.randint(1, 3), rise=r.uniform(0, 0.6))
            if r.random() < 0.5:
                self.g.set(x, y + h, z, SPECIES[kind][2])
        self.roots_flare(x, z, y, 1 if family in ("small", "medium", "gnarled", "dead") else 2)

    def roots_flare(self, x, z, y, size):
        """Roots and bare earth at the foot of a tree."""
        for dx, dz in ((-1, 0), (size, 0), (0, -1), (0, size)):
            if self.r.random() < 0.5:
                self.g.set(x + dx, y - 1, z + dz, "rooted_dirt")
                if size > 1 and self.r.random() < 0.5:
                    self.put(x + dx, y, z + dz, "mangrove_roots")

    def colossal(self, x, z, y, kind="oak", h=26, size=4, crown=10, roots=7, hollow=False):
        """A landmark: a trunk size x size, spreading roots, thick branches, a crown seen from far away."""
        g, r = self.g, self.r
        wood = SPECIES[kind][0].replace("_log", "_wood") if "stem" not in SPECIES[kind][0] else SPECIES[kind][0]
        for dx in range(size):
            for dz in range(size):
                edge = dx in (0, size - 1) or dz in (0, size - 1)
                for yy in range(y, y + h):
                    if hollow and not edge and yy < y + 6:
                        g.set(x + dx, yy, z + dz, "air")
                    else:
                        g.set(x + dx, yy, z + dz, wood if edge else SPECIES[kind][0])
        if hollow:
            g.box(x + size // 2, y, z - 0, x + size // 2, y + 3, z, "air")
        cx, cz = x + (size - 1) / 2, z + (size - 1) / 2
        for k in range(8):
            a = k * math.pi / 4 + r.uniform(-0.2, 0.2)
            for t in range(size // 2 + 1, size // 2 + roots):
                px, pz = round(cx + t * math.cos(a)), round(cz + t * math.sin(a))
                hh = max(0, 2 - (t - size // 2) // 2)
                for yy in range(y, y + hh):
                    g.set(px, yy, pz, wood)
                if hh == 0:
                    g.set(px, y - 1, pz, "rooted_dirt")
                    self.put(px, y, pz, "mangrove_roots" if r.random() < 0.4 else "moss_carpet")
        for k in range(5):
            yy = y + h - r.randint(3, 9)
            e = self.branch(round(cx), yy, round(cz), kind, r.randint(5, 8), rise=0.4, ang=k * 1.26 + r.uniform(-0.3, 0.3))
            self.blob_leaves(e[0], e[1] + 1, e[2], r.uniform(3.5, 4.5), 2.4, kind)
        self.blob_leaves(round(cx), y + h + 2, round(cz), crown, 4.0, kind)

    # ------------------------------------------------------------ dead wood and the woodcutters' traces
    def stump(self, x, z, y, kind="oak", size=1, fresh=False):
        r = self.r
        h = r.randint(1, 2) if size == 1 else r.randint(1, 3)
        for dx in range(size):
            for dz in range(size):
                hh = h - (1 if size > 1 and (dx in (0, size - 1) and dz in (0, size - 1)) and r.random() < 0.5 else 0)
                for yy in range(y, y + max(1, hh)):
                    self.g.set(x + dx, yy, z + dz, self.log(kind))
                top = y + max(1, hh)
                if fresh:
                    self.g.set(x + dx, top - 1, z + dz, SPECIES[kind][2])
                elif r.random() < 0.6:
                    self.put(x + dx, top, z + dz, "moss_carpet")

    def fallen(self, x, z, y, length, axis="x", kind="oak", size=1, plate=True):
        """A lying trunk with its root plate at one end and broken branches."""
        g, r = self.g, self.r
        for t in range(length):
            for a in range(size):
                for b in range(size):
                    px, pz = (x + t, z + a) if axis == "x" else (x + a, z + t)
                    g.set(px, y + b, pz, self.log(kind, axis))
            if r.random() < 0.15 and t > 2:
                px, pz = (x + t, z + size) if axis == "x" else (x + size, z + t)
                self.put(px, y, pz, self.log(kind, "z" if axis == "x" else "x"))
            if r.random() < 0.35:
                px, pz = (x + t, z) if axis == "x" else (x, z + t)
                self.put(px, y + size, pz, "moss_carpet")
        if plate:
            for a in range(-1, size + 1):
                for b in range(0, size + 2):
                    px, pz = (x - 1, z + a) if axis == "x" else (x + a, z - 1)
                    g.set(px, y + b - 1, pz, r.choice(["rooted_dirt", "dirt", "mangrove_roots", "coarse_dirt"]))

    def log_pile(self, x, z, y, width, height, kind="oak", axis="x", collapsed=False):
        """Logs stacked in a pyramid (tidy), or slid down on one side (collapsed)."""
        r = self.r
        for row in range(height):
            w = width - row
            off = row // 2
            for k in range(w):
                for t in range(4):
                    px, pz = (x + t, z + off + k) if axis == "x" else (x + off + k, z + t)
                    if collapsed and row > 0 and r.random() < 0.4:
                        continue
                    self.g.set(px, y + row, pz, self.log(kind, axis))
        if collapsed:
            for _ in range(3):
                k = r.randint(0, width)
                px, pz = (x + r.randint(-1, 4), z + width + 1) if axis == "x" else (x + width + 1, z + r.randint(-1, 4))
                self.put(px, y, pz, self.log(kind, "z" if axis == "x" else "x"))

    def branch_pile(self, x, z, y, kind="oak"):
        for _ in range(5):
            dx, dz = self.r.randint(-1, 1), self.r.randint(-1, 1)
            self.put(x + dx, y, z + dz, f"{kind}_fence" if kind not in ("pale_oak",) else "oak_fence")
            if self.r.random() < 0.5:
                self.put(x + dx, y + 1, z + dz, self.leaves(kind))

    def bush(self, x, z, y, kind="oak", big=False):
        self.blob_leaves(x, y + (1 if big else 0), z, 2.2 if big else 1.3, 1.2 if big else 0.8, kind, density=0.85)

    def ground(self, x0, z0, x1, z1, y, table, where=None):
        g = self.g
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if where and not where(x, z):
                    continue
                v = (self.n(x * 0.4, 0, z * 0.4) + 1) / 2 + self.r.uniform(-0.12, 0.12)
                tot, acc = sum(w for _, w in table), 0
                for b, w in table:
                    acc += w / tot
                    if v < acc:
                        g.set(x, y, z, b)
                        break
                else:
                    g.set(x, y, z, table[-1][0])

    def curtain(self, x0, z0, x1, z1, y0, y1):
        """Invisible walls from y0 to y1, through plants and leaves (nobody climbs over by the trees)."""
        self.g.box(x0, y0, z0, x1, y1, z1, "barrier", where=lambda b: b == "air" or gridlib.passable(b) or "leaves" in b)
