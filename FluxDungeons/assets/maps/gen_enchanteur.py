#!/usr/bin/env python3
"""Builds the map of the Enchanter dungeon: the sanctuary of an old order of enchanters, home of the Oversized Evoker.

Five rooms in a straight line towards +z, carved in a mass of deepslate, joined by short passages (7 wide, 8 high,
7 long) closed by two doors each: the way out of a room opens once its monsters are dead, the way into the next one
opens when a player comes near. Free inner sizes (width x depth x height):
  1 Vestibule of the Arcana        31 x 31 x 14   summoning circle, crystal hanging in a gold ring
  2 Forbidden Library              35 x 31 x 15   bookshelves in the walls, parquet, study tables, the forbidden book
  3 Transmutation Laboratory       33 x 33 x 14   three circles, alembic, crystal reactor, containment cage
  4 Sanctuary of Illusions         39 x 35 x 17   concentric circle and eye, four pillars, mirrors, dome
  5 Sanctuary of the Evoker        51 x 51 x 22   29-wide circle, shrink and grow marks, throne, eight pillars, dome
Every floor is flat and its middle free; decoration stays on the walls, in the walls (recessed bays: the stated sizes
are the pilasters' faces, bays go one block deeper) or above head height. Light comes from the building itself (lamps
behind stained glass, froglights, crystals) plus invisible light blocks so that fights stay readable.

Vanilla blocks only (the brief's palette: deepslate, violet, blue, old gold), no RPG retexture.

Usage: gen_enchanteur.py [labs] [--check] [--preview <folder>] [--edits]
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gridlib  # noqa: E402
import maplib  # noqa: E402

DUNGEON = "enchanteur"
F = 63                                                  # floor block; players stand at F + 1
X0, X1, Y0, Y1, Z0, Z1 = -32, 32, 56, 100, -14, 214
g = gridlib.Model((X0, X1, Y0, Y1, Z0, Z1), ((Y0, Y1, "deepslate"),), seed=77)
m = maplib.Map()
m.box = (X0, Z0, X1, Z1)
m.ybox = (Y0, Y1)
S, B = g.set, g.box
DIRS = {(1, 0): "east", (-1, 0): "west", (0, 1): "south", (0, -1): "north"}

# room: inner x0, x1, z0, z1, height
ROOMS = {
    1: (-15, 15, 0, 30, 14),
    2: (-17, 17, 38, 68, 15),
    3: (-16, 16, 76, 108, 14),
    4: (-19, 19, 116, 150, 17),
    5: (-25, 25, 158, 208, 22),
}
# middle of each room kept free up to 3 blocks high (x0, x1, z0, z1)
FREE = {1: (-9, 9, 6, 24), 2: (-10, 10, 45, 61), 3: (-9, 9, 83, 101), 4: (-11, 11, 122, 144), 5: (-16, 16, 167, 199)}

gridlib.COLORS.update({
    "deepslate": (58, 58, 62), "polished_deepslate": (78, 78, 84), "stone_bricks": (125, 125, 125),
    "purple": (120, 60, 170), "purpur": (170, 125, 170), "amethyst": (150, 105, 210), "gold_block": (235, 200, 70),
    "blue": (60, 80, 200), "cyan": (70, 110, 120), "sea_lantern": (190, 230, 230), "pearlescent": (235, 190, 225),
    "dark_oak": (70, 45, 25), "stripped_dark_oak": (95, 70, 45), "bookshelf": (160, 120, 70), "chiseled_bookshelf": (175, 140, 85),
    "polished_blackstone": (48, 44, 52), "waxed": (190, 110, 80), "iron_block": (215, 215, 215), "cut_sandstone": (215, 200, 150),
    "light_blue": (120, 170, 235), "packed_ice": (150, 180, 230), "shroomlight": (240, 160, 80), "iron_chain": (90, 95, 110),
})


# ============================================================================ helpers
def paint(y, x0, x1, z0, z1, f):
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            b = f(x, z)
            if b:
                S(x, y, z, b)


def art(rows, legend, x, y_top, z, ax=1, az=0):
    """A picture on a vertical plane: rows from the top down, columns along (ax, az) from (x, z)."""
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch in legend:
                S(x + ax * i, y_top - j, z + az * i, legend[ch])


class Wall:
    """One wall of a room in its own axes: u along it, y above the floor, d into the wall (0 = its face)."""

    def __init__(self, x, z, ax, az, nx, nz, length):
        self.x, self.z, self.ax, self.az, self.nx, self.nz, self.length = x, z, ax, az, nx, nz, length
        self.left, self.right = DIRS[(-ax, -az)], DIRS[(ax, az)]
        self.inward, self.outward = DIRS[(nx, nz)], DIRS[(-nx, -nz)]

    def at(self, u, d):
        return self.x + self.ax * u - self.nx * d, self.z + self.az * u - self.nz * d

    def set(self, u, y, d, b):
        x, z = self.at(u, d)
        S(x, F + y, z, b)

    def fill(self, u0, y0, u1, y1, d0, d1, b):
        for u in range(u0, u1 + 1):
            for d in range(d0, d1 + 1):
                x, z = self.at(u, d)
                B(x, F + y0, z, x, F + y1, z, b)


def room_walls(n):
    x0, x1, z0, z1, h = ROOMS[n]
    return {"south": Wall(x0, z0 - 1, 1, 0, 0, 1, x1 - x0 + 1), "north": Wall(x0, z1 + 1, 1, 0, 0, -1, x1 - x0 + 1),
            "west": Wall(x0 - 1, z0, 0, 1, 1, 0, z1 - z0 + 1), "east": Wall(x1 + 1, z0, 0, 1, -1, 0, z1 - z0 + 1)}


def carve(n, floor, ceiling):
    x0, x1, z0, z1, h = ROOMS[n]
    B(x0, F + 1, z0, x1, F + h, z1, "air")
    B(x0, F, z0, x1, F, z1, floor)
    B(x0 - 1, F + h + 1, z0 - 1, x1 + 1, F + h + 1, z1 + 1, ceiling)


def bays(w, h, st, skip=(), features=None, hb=None):
    """Relief of a wall: pilasters on its face, bays recessed one block between them. Each bay: dark base, a band,
    a panel, a violet arch, a stained window lit from behind. `skip`: (u0, u1) spans left plain (doorways).
    `features`: {bay index: function(wall, u0, u1)} adds something in a bay. Returns the bays as (u0, u1)."""
    hb = hb or h - 2
    bw, pw = st["bw"], st["pw"]
    w.fill(0, 1, w.length - 1, h, 0, 2, st["wall"])
    w.fill(0, h, w.length - 1, h, 0, 0, st["band"])
    count = (w.length - pw) // (bw + pw)
    start = (w.length - (count * (bw + pw) + pw)) // 2
    ablock, astairs = st["arch"]
    out = []
    for i in range(count + 1):
        u = start + i * (bw + pw)
        # pilaster: base, band, shaft with a violet inlay, gold capital
        if not any(a <= u + pw - 1 and u <= b for a, b in skip):
            w.fill(u, 1, u + pw - 1, 3, 0, 0, st["base"])
            w.fill(u, 3, u + pw - 1, 3, 0, 0, st["band"])
            w.fill(u, 4, u + pw - 1, hb - 1, 0, 0, st["pil"])
            w.fill(u, hb, u + pw - 1, hb, 0, 0, "gold_block")
        if i == count:
            break
        u0, u1 = u + pw, u + pw + bw - 1
        if any(a <= u1 and u0 <= b for a, b in skip):
            continue
        out.append((u0, u1))
        w.fill(u0, 1, u1, hb, 0, 0, "air")
        w.fill(u0, 1, u1, 3, 1, 1, st["base"])
        w.fill(u0, 3, u1, 3, 1, 1, st["band"])
        w.fill(u0, 4, u1, hb, 1, 1, st["panel"])
        k = 2 if bw >= 7 else 1
        for a in range(k):
            y = hb - (k - 1 - a)
            for j in range(a):
                w.set(u0 + j, y, 0, ablock)
                w.set(u1 - j, y, 0, ablock)
            w.set(u0 + a, y, 0, f"{astairs}[half=top,facing={w.left}]")
            w.set(u1 - a, y, 0, f"{astairs}[half=top,facing={w.right}]")
        # window: sill with a gold stone, stained glass, lamps behind it
        low = max(6, hb - st.get("window", 4))
        glass = st["glass"][len(out) % len(st["glass"])]
        w.fill(u0 + 1, low - 1, u1 - 1, low - 1, 1, 1, "cut_sandstone")
        w.set((u0 + u1) // 2, low - 1, 1, "gold_block")
        w.fill(u0 + 1, low, u1 - 1, hb - 1, 1, 1, glass)
        w.fill(u0 + 1, low, u1 - 1, hb - 1, 2, 2, st["lamp"])
        w.fill(u0 + k, hb, u1 - k, hb, 1, 1, glass)
        w.fill(u0 + k, hb, u1 - k, hb, 2, 2, st["lamp"])
        if features and (len(out) - 1) in features:
            features[len(out) - 1](w, u0, u1)
    return out


def niche_crystal(w, u0, u1):
    """A niche in the panel: stained glass at the back, a crystal standing in it."""
    c = (u0 + u1) // 2
    w.fill(c - 1, 2, c + 1, 4, 1, 1, "air")
    w.fill(c - 1, 2, c + 1, 4, 2, 2, "purple_stained_glass")
    w.set(c, 3, 2, "blue_stained_glass")
    w.fill(c - 1, 2, c + 1, 4, 3, 3, "sea_lantern")
    w.set(c, 2, 1, "amethyst_block")
    w.set(c, 3, 1, "amethyst_cluster[facing=up]")


RUNES = (("G.G", ".P.", "GPG", ".P.", "G.G"), (".G.", "GPG", ".G.", "GPG", ".G."), ("GPG", "P.P", ".G.", "P.P", "GPG"))


def rune(index):
    def f(w, u0, u1):
        c = (u0 + u1) // 2
        for j, row in enumerate(RUNES[index % len(RUNES)]):
            for i, ch in enumerate(row):
                if ch != ".":
                    w.set(c - 1 + i, 5 - j + 3, 1, "gold_block" if ch == "G" else "purple_concrete")
    return f


def pillar(cx, cz, h, body="deepslate_bricks", inlay="purple_concrete", lamp="sea_lantern", stairs="deepslate_brick_stairs"):
    """A 3 x 3 pillar: stepped base, violet strip and gold bands on each face, a lamp near the top, a capital."""
    y0, top = F + 1, F + h
    B(cx - 1, y0, cz - 1, cx + 1, top, cz + 1, body)
    for dx, dz in ((1, 1), (1, -1), (-1, 1), (-1, -1)):
        B(cx + dx, y0, cz + dz, cx + dx, top, cz + dz, "polished_deepslate")
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        B(cx + dx, y0 + 3, cz + dz, cx + dx, top - 4, cz + dz, inlay)
        S(cx + dx, y0 + 2, cz + dz, "gold_block")
        S(cx + dx, top - 3, cz + dz, "gold_block")
        S(cx + dx, top - 2, cz + dz, lamp)
    for i in range(-1, 2):
        for (x, z, face) in ((cx + i, cz - 2, "south"), (cx + i, cz + 2, "north"), (cx - 2, cz + i, "east"), (cx + 2, cz + i, "west")):
            if g.get(x, y0, z) == "air":
                S(x, y0, z, f"{stairs}[facing={face}]")
            if g.get(x, top, z) == "air":
                S(x, top, z, f"{stairs}[half=top,facing={face}]")


def crystal(cx, cy, cz, size=2, ring=0, ring_block="gold_block", hang_to=None, small=()):
    """A crystal hanging upright: a diamond of amethyst, clusters on its points, an optional flat ring around it and
    small crystals standing on that ring; hung from `hang_to` by a chain."""
    for dy in range(-size, size + 1):
        rad = (size - abs(dy)) // 2
        for dx in range(-rad, rad + 1):
            for dz in range(-rad, rad + 1):
                if abs(dx) + abs(dz) <= rad:
                    S(cx + dx, cy + dy, cz + dz, "amethyst_block")
    S(cx, cy - size - 1, cz, "amethyst_cluster[facing=down]")
    rad = size // 2
    for dx, dz, face in ((rad + 1, 0, "east"), (-rad - 1, 0, "west"), (0, rad + 1, "south"), (0, -rad - 1, "north")):
        S(cx + dx, cy, cz + dz, f"amethyst_cluster[facing={face}]")
    if ring:
        for dx in range(-ring, ring + 1):
            for dz in range(-ring, ring + 1):
                if round(math.hypot(dx, dz)) == ring:
                    S(cx + dx, cy, cz + dz, ring_block)
        for dx, dz in small:
            S(cx + dx, cy + 1, cz + dz, "amethyst_cluster[facing=up]")
    if hang_to:
        B(cx, cy + size + 1, cz, cx, hang_to, cz, "iron_chain[axis=y]")
    else:
        S(cx, cy + size + 1, cz, "amethyst_cluster[facing=up]")


def lights(n, level=12, step=7, y=5):
    x0, x1, z0, z1, h = ROOMS[n]
    for x in range(x0 + 3, x1, step):
        for z in range(z0 + 3, z1, step):
            if g.get(x, F + y, z) == "air":
                S(x, F + y, z, f"light[level={level}]")


def ring(d, a, b):
    return a <= d < b


# ============================================================================ doors and passages
SYMBOLS = {
    "crystal": ("..G..", ".GAG.", ".GAG.", "GAAAG", ".GAG.", ".GAG.", "..G.."),
    "book": (".....", "G...G", "GG.GG", "GPGPG", "GPGPG", "GG.GG", "....."),
    "alembic": ("..G..", "..G..", ".GGG.", "GBBBG", "GBBBG", ".GGG.", "....."),
    "eye": (".....", ".GGG.", "GBBBG", "GBABG", "GBBBG", ".GGG.", "....."),
    "rune": ("..G..", ".GPG.", "GP.PG", "P.A.P", "GP.PG", ".GPG.", "..G.."),
}
LEGEND = {".": "purpur_block", "G": "gold_block", "A": "amethyst_block", "B": "blue_concrete", "P": "purple_concrete"}


def door(id, z, symbol, room, kills):
    """A monumental door across a passage at z: dark frame with gold corners, a 5 x 7 leaf carrying its symbol."""
    B(-3, F + 1, z, 3, F + 8, z, "polished_deepslate")
    for x in (-3, 3):
        S(x, F + 8, z, "gold_block")
        S(x, F + 1, z, "deepslate_tiles")
    S(0, F + 8, z, "amethyst_block")
    art(SYMBOLS[symbol], LEGEND, -2, F + 7, z)
    frm, to = (-2, F + 1, z), (2, F + 7, z)
    g.doors.append((frm, to))
    m.door(id, frm, to, room=str(room), type="kills" if kills else "proximity", radius=5.0)


def passage(z0, glass="purple_stained_glass"):
    """7 wide, 8 high, 7 long from z0: paved, a violet line, an arch in its middle, runes, a lit ceiling panel."""
    z1 = z0 + 6
    B(-3, F + 1, z0, 3, F + 8, z1, "air")
    B(-4, F, z0, 4, F, z1, "polished_deepslate")
    B(0, F, z0, 0, F, z1, "purple_concrete")
    for x in (-3, 3):
        B(x, F, z0, x, F, z1, "deepslate_tiles")
    B(-4, F + 1, z0, -4, F + 8, z1, "deepslate_bricks")
    B(4, F + 1, z0, 4, F + 8, z1, "deepslate_bricks")
    B(-4, F + 9, z0, 4, F + 9, z1, "deepslate_tiles")
    zc = z0 + 3
    for x, face in ((-3, "west"), (3, "east")):                   # arch rib in the middle
        B(x, F + 1, zc, x, F + 6, zc, "polished_deepslate")
        S(x, F + 7, zc, "gold_block")
        S(x, F + 8, zc, "polished_deepslate")
        S(-2 if x < 0 else 2, F + 8, zc, f"purpur_stairs[half=top,facing={face}]")
    B(-1, F + 9, zc - 1, 1, F + 9, zc + 1, glass)
    B(-1, F + 10, zc - 1, 1, F + 10, zc + 1, "sea_lantern")
    for x in (-4, 4):                                             # a small rune on each side, lit from behind
        for dz in (-2, 2):
            S(x, F + 4, zc + dz, "gold_block")
            S(x, F + 5, zc + dz, "purple_concrete")
            S(x, F + 3, zc + dz, "purple_concrete")
    S(0, F + 4, zc, "light[level=11]")


# ============================================================================ 1. Vestibule of the Arcana
STONE = dict(wall="deepslate_bricks", base="deepslate_bricks", band="deepslate_tiles", panel="stone_bricks",
             pil="polished_deepslate", arch=("purpur_block", "purpur_stairs"), lamp="sea_lantern", bw=5, pw=2,
             glass=("purple_stained_glass", "blue_stained_glass"))


def vestibule():
    x0, x1, z0, z1, h = ROOMS[1]
    cx, cz = 0, 15
    carve(1, "deepslate_tiles", "deepslate_tiles")

    def floor(x, z):
        d = math.hypot(x - cx, z - cz)
        dx, dz = abs(x - cx), abs(z - cz)
        if x in (x0, x1) or z in (z0, z1):
            return "polished_deepslate"
        if d < 0.5:
            return "amethyst_block"
        if d <= 5 and (dx == 0 or dz == 0):
            return "gold_block"                                   # the star: four straight branches
        if d <= 4.3 and dx == dz:
            return "purple_concrete"                              # and four diagonal ones
        if ring(d, 7.5, 8.6):
            return "amethyst_block" if (dx == 0 or dz == 0 or dx == dz) else "gold_block"
        if ring(d, 5.6, 7.5):
            return "purple_concrete"
        if ring(d, 5.0, 5.6):
            return "gold_block"
        if d < 5.0:
            return "polished_deepslate"
        return None
    paint(F, x0, x1, z0, z1, floor)
    W = room_walls(1)
    bays(W["west"], h, STONE, features={0: niche_crystal, 3: niche_crystal, 1: rune(0), 2: rune(1)})
    bays(W["east"], h, STONE, features={0: niche_crystal, 3: niche_crystal, 1: rune(1), 2: rune(2)})
    bays(W["south"], h, STONE, skip=((10, 20),), features={0: rune(2), 1: rune(0)})
    bays(W["north"], h, STONE, skip=((10, 20),), features={0: rune(0), 1: rune(2)})
    # vault: three steps rising towards the middle, a lit violet panel at the top
    for k, inset in enumerate((4, 8, 12)):
        B(x0 + inset, F + h + 1, z0 + inset, x1 - inset, F + h + 1 + k, z1 - inset, "air")
        B(x0 + inset, F + h + 2 + k, z0 + inset, x1 - inset, F + h + 2 + k, z1 - inset, "deepslate_tiles")
        for x in range(x0 + inset - 1, x1 - inset + 2):           # rim of each step
            for z in range(z0 + inset - 1, z1 - inset + 2):
                if x in (x0 + inset - 1, x1 - inset + 1) or z in (z0 + inset - 1, z1 - inset + 1):
                    S(x, F + h + 1 + k, z, "polished_deepslate" if k % 2 == 0 else "purpur_block")
    B(cx - 2, F + h + 4, cz - 2, cx + 2, F + h + 4, cz + 2, "purple_stained_glass")
    B(cx - 2, F + h + 5, cz - 2, cx + 2, F + h + 5, cz + 2, "sea_lantern")
    S(cx, F + h + 4, cz, "gold_block")
    # four great pillars near the corners, two smaller ones framing the way in
    for px in (-11, 11):
        for pz in (4, 26):
            pillar(px, pz, h)
    for px in (-6, 6):
        B(px, F + 1, z0, px, F + 9, z0 + 1, "polished_deepslate")
        B(px, F + 1, z0, px, F + 2, z0 + 1, "deepslate_bricks")
        B(px, F + 5, z0 + 1, px, F + 6, z0 + 1, "purple_concrete")
        S(px, F + 9, z0 + 1, "gold_block")
        S(px, F + 10, z0 + 1, "pearlescent_froglight")
        S(px, F + 10, z0, "polished_deepslate")
    # the crystal held in the air by a gold ring, 7 blocks above the circle
    crystal(cx, F + 9, cz, size=2, ring=3, hang_to=F + h + 3, small=((3, 0), (-3, 0), (0, 3), (0, -3)))
    S(cx, F + 5, cz, "light[level=13]")
    # magic braziers: a carved stone and a blue flame, beside the pillars
    for px, pz in ((-11, 8), (11, 8), (-11, 22), (11, 22)):
        S(px, F + 1, pz, "chiseled_deepslate")
        S(px, F + 2, pz, "soul_lantern")
    lights(1)
    for x, z, mob in ((-7, 9, "ench_apprenti"), (7, 9, "ench_apprenti"), (-9, 21, "ench_eclat"), (9, 21, "ench_eclat"),
                      (0, 25, "ench_scribe")):
        m.mob(mob, x, z, y=F + 1, yaw=180, room="1")
    m.sound(cx, F + 9, cz, [("block.amethyst_block.chime", 0.9, 0.7), ("block.amethyst_block.resonate", 0.8, 0.8)],
            radius=18, every=(80, 180), particle={"type": "ENCHANT", "count": 6, "spread": (1.5, 1.5, 1.5), "speed": 0.4, "every": 10})
    m.sound(cx, F + 3, cz, [("block.beacon.ambient", 0.5, 0.6)], radius=18, every=(160, 260))


# ============================================================================ 2. Forbidden Library
def shelf(u, y):
    """What sits in a cell of a bookcase: mostly books, some carved shelves, a lit crystal now and then."""
    k = (u * 7 + y * 13) % 17
    return "chiseled_bookshelf" if k in (3, 11) else "bookshelf"


def library():
    x0, x1, z0, z1, h = ROOMS[2]
    carve(2, "dark_oak_planks", "deepslate_tiles")
    fx0, fx1, fz0, fz1 = FREE[2]

    def floor(x, z):
        if x in (x0, x1) or z in (z0, z1):
            return "polished_deepslate"
        if fx0 <= x <= fx1 and fz0 <= z <= fz1:
            if x in (fx0, fx1) or z in (fz0, fz1):                # stone border of the parquet
                corner = x in (fx0, fx1) and z in (fz0, fz1)
                mid = x == 0 or z == (fz0 + fz1) // 2
                return "gold_block" if corner else "purple_concrete" if mid else "polished_deepslate"
            ax = "x" if ((x - fx0 - 1) // 3 + (z - fz0 - 1) // 3) % 2 == 0 else "z"
            return f"stripped_dark_oak_log[axis={ax}]"            # parquet: bands turned one way, then the other
        return None
    paint(F, x0, x1, z0, z1, floor)
    W = room_walls(2)
    # side walls: bookcases 9 high set in the wall between dark oak posts, lit windows above
    for name in ("west", "east"):
        w = W[name]
        w.fill(0, 1, w.length - 1, h, 0, 2, "deepslate_bricks")
        for u in range(0, w.length):
            post = u % 6 == 0
            if post:
                w.fill(u, 1, u, 10, 0, 0, "dark_oak_log")
                w.set(u, 11, 0, "gold_block")
                continue
            w.fill(u, 1, u, 9, 0, 0, "air")
            for y in range(1, 10):
                w.set(u, y, 1, shelf(u, y))
            w.set(u, 10, 0, f"stripped_dark_oak_log[axis={'z' if w.az else 'x'}]")
            if u % 6 == 3:
                w.set(u, 5, 1, "sea_lantern")
                w.set(u, 5, 0, "amethyst_cluster[facing=" + w.inward + "]")
            if u % 6 in (2, 3, 4):                                # window above each case
                w.fill(u, 12, u, 14, 0, 0, "air")
                w.fill(u, 12, u, 14, 1, 1, "purple_stained_glass" if (u // 6) % 2 == 0 else "blue_stained_glass")
                w.fill(u, 12, u, 14, 2, 2, "sea_lantern")
        w.fill(0, h, w.length - 1, h, 0, 0, "deepslate_tiles")
    # back wall: the great bookcase on three levels around the way out, two wooden galleries along it
    w = W["north"]
    w.fill(0, 1, w.length - 1, h, 0, 2, "deepslate_bricks")
    for u in range(0, w.length):
        if 13 <= u <= 21:
            continue                                              # the doorway
        post = u % 5 == 1
        for y in range(1, h):
            if y in (5, 10):
                w.set(u, y, 0, "dark_oak_planks")
            elif post:
                w.set(u, y, 0, "dark_oak_log")
            else:
                w.set(u, y, 0, shelf(u, y + 3))
        w.set(u, h, 0, "stripped_dark_oak_log[axis=x]")
        w.set(u, 5, -1, "dark_oak_slab[type=top]")                # galleries: one block out of the wall, above heads
        w.set(u, 10, -1, "dark_oak_slab[type=top]")
        if u % 5 == 1:
            w.set(u, 6, -1, "dark_oak_fence")
            w.set(u, 11, -1, "dark_oak_fence")
        if u % 5 == 3:
            w.set(u, 8, 0, "sea_lantern")
            w.set(u, 13, 0, "pearlescent_froglight")
    w.fill(13, 9, 21, h, 0, 0, "dark_oak_planks")
    w.fill(14, 9, 20, 9, 0, 0, "gold_block")
    for u in (0, w.length - 1):                                   # ladders up the ends: the galleries can be reached
        w.fill(u, 1, u, 10, -1, -1, "ladder[facing=north]")
    # front wall: stone bays like the vestibule's, so that the way in reads as the same building
    wood = dict(STONE, panel="dark_oak_planks", pil="dark_oak_log", arch=("dark_oak_planks", "dark_oak_stairs"))
    bays(W["south"], h, wood, skip=((12, 22),))
    # ceiling: dark oak beams, coffers between them, stained glass in every other coffer
    for z in range(z0, z1 + 1):
        beam = (z - z0) % 5 == 0
        if beam:
            B(x0, F + h + 1, z, x1, F + h + 1, z, "dark_oak_log[axis=x]")
        else:
            B(x0, F + h + 1, z, x1, F + h + 1, z, "air")
            B(x0, F + h + 2, z, x1, F + h + 2, z, "deepslate_tiles")
    for x in (x0 + 8, 0, x1 - 8):
        B(x, F + h + 1, z0, x, F + h + 1, z1, "dark_oak_log[axis=z]")
    for i, zc in enumerate(range(z0 + 2, z1, 5)):
        for xc in (-13, -4, 4, 13):
            if (i + (xc > 0)) % 2 == 0:
                B(xc - 1, F + h + 2, zc, xc + 1, F + h + 2, zc + 1, "purple_stained_glass" if i % 4 < 2 else "blue_stained_glass")
                B(xc - 1, F + h + 3, zc, xc + 1, F + h + 3, zc + 1, "sea_lantern")
    for x, z in ((-8, 46), (8, 46), (-8, 60), (8, 60)):           # four lanterns on chains
        B(x, F + h - 3, z, x, F + h + 1, z, "iron_chain[axis=y]")
        S(x, F + h - 4, z, "soul_lantern[hanging=true]")
    # study tables 5 x 2 between the parquet and the bookcases
    for sx in (-1, 1):
        for zt in (41, 57):
            xa = 13 * sx
            for dz in range(5):
                for dx in (0, sx):
                    end = dz in (0, 4)
                    S(xa + dx, F + 1, zt + dz, "dark_oak_stairs[half=top,facing=" + ("south" if dz == 0 else "north") + "]" if end
                      else "dark_oak_slab[type=top]")
            S(xa, F + 2, zt + 1, "purple_candle[candles=3,lit=true]")
            S(xa + sx, F + 2, zt + 2, "amethyst_cluster[facing=up]")
            S(xa, F + 2, zt + 3, "flower_pot")
            S(xa + sx, F + 2, zt + 4, "candle[candles=2,lit=true]")
        S(13 * sx, F + 1, 48, "enchanting_table" if sx == 1 else "cauldron")
        S(14 * sx, F + 1, 55, "brewing_stand" if sx == 1 else "lectern[facing=" + ("west" if sx == 1 else "east") + "]")
    # the forbidden book: a lectern on a small violet dais at the back, lit from under
    B(8, F, 64, 10, F, 66, "purpur_block")
    S(9, F, 65, "pearlescent_froglight")
    S(9, F + 1, 65, "purple_stained_glass")
    S(9, F + 2, 65, "lectern[facing=south,has_book=true]")
    for dx, dz in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
        S(9 + dx, F + 1, 65 + dz, "purple_candle[candles=2,lit=true]")
    # books that left their shelves: single cases floating near the corners
    for x, y, z in ((-14, 7, 43), (-13, 9, 50), (14, 8, 44), (13, 10, 52), (-14, 8, 62), (14, 7, 61), (-12, 11, 57)):
        S(x, F + y, z, "chiseled_bookshelf")
    lights(2)
    for x, z, mob in ((-6, 49, "ench_apprenti"), (6, 49, "ench_apprenti"), (-9, 58, "ench_scribe"), (9, 58, "ench_scribe"),
                      (0, 60, "ench_gardien"), (0, 47, "ench_eclat")):
        m.mob(mob, x, z, y=F + 1, yaw=180, room="2")
    m.sound(9, F + 3, 65, [("block.enchantment_table.use", 0.6, 0.6), ("item.book.page_turn", 1.0, 0.7)], radius=16,
            every=(100, 200), particle={"type": "ENCHANT", "count": 5, "spread": (0.8, 1.0, 0.8), "speed": 0.5, "every": 10})
    m.sound(0, F + 6, 53, [("item.book.page_turn", 0.8, 0.9), ("block.amethyst_block.chime", 0.5, 1.2)], radius=18, every=(120, 240))


# ============================================================================ 3. Transmutation Laboratory
LAB = dict(wall="deepslate_bricks", base="polished_blackstone", band="waxed_cut_copper", panel="deepslate_bricks",
           pil="polished_blackstone", arch=("polished_deepslate", "polished_deepslate_stairs"), lamp="sea_lantern", bw=5, pw=2,
           glass=("blue_stained_glass", "light_blue_stained_glass"), window=3)


def lab_shelf(items):
    def f(w, u0, u1):
        w.fill(u0, 2, u1, 2, 0, 0, "polished_blackstone_slab[type=top]")
        for i, b in enumerate(items):
            if b:
                w.set(u0 + i, 3, 0, b)
    return f


def unstable(w, u0, u1):
    """A patch of wall eaten by wild magic: violet, cyan and glass in a diagonal weave, glowing from behind."""
    for u in range(u0, u1 + 1):
        for y in range(4, 8):
            k = (u + y) % 3
            w.set(u, y, 1, ("purple_concrete", "blue_stained_glass", "cyan_terracotta")[k])
            if k == 1:
                w.set(u, y, 2, "pearlescent_froglight")


def laboratory():
    x0, x1, z0, z1, h = ROOMS[3]
    cx, cz = 0, 92
    carve(3, "polished_deepslate", "polished_blackstone")
    side = ((-11, cz), (11, cz))

    def floor(x, z):
        if x in (x0, x1) or z in (z0, z1):
            return "polished_blackstone"
        d = math.hypot(x - cx, z - cz)
        dx, dz = abs(x - cx), abs(z - cz)
        if ring(d, 5.6, 6.6):
            return "gold_block" if (dx == 0 or dz == 0 or dx == dz) else "purple_concrete"
        if ring(d, 3.6, 4.6):
            return "cyan_terracotta"
        if d < 0.5:
            return "amethyst_block"
        if d < 3.6 and (dx == dz or dx == 0 or dz == 0):
            return "purple_concrete"
        for sx, sz in side:
            ds = math.hypot(x - sx, z - sz)
            if ring(ds, 2.6, 3.6):
                return "cyan_terracotta" if sx < 0 else "purple_concrete"
            if ds < 2.6 and (x == sx or z == sz):
                return "gold_block"
        return None
    paint(F, x0, x1, z0, z1, floor)
    W = room_walls(3)
    pots = lab_shelf(("decorated_pot", None, "brewing_stand", "amethyst_cluster[facing=up]", "decorated_pot"))
    jars = lab_shelf(("cauldron", "barrel[facing=up]", None, "decorated_pot", "brewing_stand"))
    bays(W["west"], h, LAB, features={0: pots, 1: unstable, 2: jars, 3: pots})
    bays(W["east"], h, LAB, features={0: jars, 1: pots, 2: unstable, 3: jars})
    bays(W["south"], h, LAB, skip=((11, 21),), features={0: pots, 1: jars})
    bays(W["north"], h, LAB, skip=((11, 21),), features={0: unstable, 1: pots})
    # copper conduits running along the walls above the bays, lightning rods coming down the pilasters
    for name, w in W.items():
        w.fill(0, h - 1, w.length - 1, h - 1, 0, 0, "waxed_copper_block")
        for u in range(2, w.length - 2, 7):
            if name in ("south", "north") and 11 <= u <= 21:
                continue
            w.set(u, h - 1, 0, "waxed_oxidized_copper")
    for cxn, czn in ((x0, z0), (x1, z0), (x0, z1), (x1, z1)):
        B(cxn, F + 1, czn, cxn, F + h - 2, czn, "lightning_rod[facing=up]")
    # ceiling: a sunk square edged with copper, a blue light well over each circle
    B(x0 + 5, F + h + 1, z0 + 5, x1 - 5, F + h + 1, z1 - 5, "air")
    B(x0 + 5, F + h + 2, z0 + 5, x1 - 5, F + h + 2, z1 - 5, "deepslate_tiles")
    for x in range(x0 + 4, x1 - 3):
        for z in range(z0 + 4, z1 - 3):
            if x in (x0 + 4, x1 - 4) or z in (z0 + 4, z1 - 4):
                S(x, F + h + 1, z, "waxed_cut_copper")
    for (lx, lz) in ((cx, cz),) + side:
        B(lx - 1, F + h + 2, lz - 1, lx + 1, F + h + 2, lz + 1, "blue_stained_glass")
        B(lx - 1, F + h + 3, lz - 1, lx + 1, F + h + 3, lz + 1, "sea_lantern")
    # central device: a crystal 4 blocks above the circle, two rings around it, chains to the ceiling
    crystal(cx, F + 6, cz, size=1, ring=3, hang_to=F + h + 1)
    for dx in range(-2, 3):
        for dz in range(-2, 3):
            if round(math.hypot(dx, dz)) == 2:
                S(cx + dx, F + 8, cz + dz, "purple_stained_glass")
    for dx, dz in ((3, 0), (-3, 0), (0, 3), (0, -3)):
        B(cx + dx, F + 7, cz + dz, cx + dx, F + h + 1, cz + dz, "iron_chain[axis=y]")
    S(cx, F + 4, cz, "light[level=13]")
    # installation 1, west circle: the arcane alembic
    ax, az = side[0]
    S(ax, F, az, "sea_lantern")
    S(ax, F + 1, az, "water_cauldron[level=3]")
    for dx, dz in ((1, 1), (1, -1), (-1, 1), (-1, -1)):
        S(ax + dx, F + 1, az + dz, "waxed_copper_block")
        S(ax + dx, F + 2, az + dz, "blue_stained_glass")
        S(ax + dx, F + 3, az + dz, "amethyst_cluster[facing=up]")
    S(ax - 1, F + 1, az, "waxed_oxidized_copper")
    B(ax - 1, F + 2, az, ax - 1, F + 6, az, "lightning_rod[facing=up]")
    B(ax - 4, F + 6, az, ax - 2, F + 6, az, "lightning_rod[facing=west]")       # the feed pipe, back to the wall
    S(ax - 1, F + 6, az, "waxed_copper_block")
    # installation 2, east circle: the crystal reactor, 5 high
    rx, rz = side[1]
    S(rx, F, rz, "pearlescent_froglight")
    B(rx, F + 1, rz, rx, F + 4, rz, "amethyst_block")
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        B(rx + dx, F + 2, rz + dz, rx + dx, F + 3, rz + dz, "purple_stained_glass")
    for dx, dz in ((1, 1), (1, -1), (-1, 1), (-1, -1)):
        S(rx + dx, F + 1, rz + dz, "iron_block")
        B(rx + dx, F + 2, rz + dz, rx + dx, F + 4, rz + dz, "iron_bars")
        S(rx + dx, F + 5, rz + dz, "iron_block")
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        S(rx + dx, F + 5, rz + dz, "iron_block")
        S(rx + dx, F + 1, rz + dz, "iron_block")
    S(rx, F + 5, rz, "sea_lantern")
    S(rx, F + 6, rz, "end_rod[facing=up]")
    # installation 3, back east corner: the containment cage, 5 x 5 and 6 high, an unstable crystal inside
    kx, kz = 12, 104
    B(kx - 2, F + 1, kz - 2, kx + 2, F + 6, kz + 2, "blue_stained_glass")
    B(kx - 1, F + 1, kz - 1, kx + 1, F + 5, kz + 1, "air")
    for dx, dz in ((2, 2), (2, -2), (-2, 2), (-2, -2)):
        B(kx + dx, F + 1, kz + dz, kx + dx, F + 6, kz + dz, "iron_block")
    for x in range(kx - 2, kx + 3):
        for z in range(kz - 2, kz + 3):
            if x in (kx - 2, kx + 2) or z in (kz - 2, kz + 2):
                S(x, F + 6, z, "iron_block")
    S(kx, F, kz, "sea_lantern")
    S(kx, F + 3, kz, "budding_amethyst")
    S(kx, F + 4, kz, "amethyst_cluster[facing=up]")
    S(kx, F + 2, kz, "amethyst_cluster[facing=down]")
    S(kx + 1, F + 3, kz, "amethyst_cluster[facing=east]")
    S(kx - 1, F + 3, kz, "amethyst_cluster[facing=west]")
    lights(3)
    for x, z, mob in ((-5, 86, "ench_apprenti"), (5, 86, "ench_apprenti"), (-6, 98, "ench_eclat"), (6, 98, "ench_eclat"),
                      (0, 100, "ench_gardien"), (-8, 92, "ench_scribe"), (8, 84, "ench_invocateur")):
        m.mob(mob, x, z, y=F + 1, yaw=180, room="3")
    m.sound(ax, F + 2, az, [("block.bubble_column.upwards_ambient", 0.7, 1.2), ("block.brewing_stand.brew", 0.8, 0.8)],
            radius=14, every=(60, 140), particle={"type": "BUBBLE_POP", "count": 4, "spread": (0.3, 0.3, 0.3), "speed": 0.02, "every": 8})
    m.sound(rx, F + 4, rz, [("block.amethyst_block.resonate", 0.9, 1.2), ("block.beacon.ambient", 0.6, 1.4)],
            radius=14, every=(70, 150), particle={"type": "ELECTRIC_SPARK", "count": 4, "spread": (0.8, 1.2, 0.8), "speed": 0.05, "every": 8})
    m.sound(cx, F + 6, cz, [("block.amethyst_block.chime", 0.9, 0.6), ("block.respawn_anchor.ambient", 0.5, 1.3)],
            radius=16, every=(90, 190), particle={"type": "WITCH", "count": 3, "spread": (1.5, 0.8, 1.5), "speed": 0.01, "every": 12})


# ============================================================================ 4. Sanctuary of Illusions
GRAND = dict(wall="polished_deepslate", base="deepslate_tiles", band="gold_block", panel="polished_deepslate",
             pil="purpur_pillar", arch=("purpur_block", "purpur_stairs"), lamp="sea_lantern", bw=7, pw=2,
             glass=("purple_stained_glass", "blue_stained_glass"), window=6)


def mirror(w, u0, u1):
    """A dimensional mirror, 5 wide and 7 high: violet glass with a blue lozenge, lit from behind, gold corners."""
    a, b = u0 + 1, u1 - 1
    c = (a + b) // 2
    w.fill(a - 1, 3, b + 1, 11, 1, 1, "polished_deepslate")
    for y in range(4, 11):
        for u in range(a, b + 1):
            blue = abs(u - c) + abs(y - 7) <= 2
            w.set(u, y, 1, "blue_stained_glass" if blue else "purple_stained_glass")
            w.set(u, y, 2, "sea_lantern" if blue else "pearlescent_froglight")
    for u, y in ((a - 1, 3), (b + 1, 3), (a - 1, 11), (b + 1, 11)):
        w.set(u, y, 1, "gold_block")
    w.set(c, 11, 1, "amethyst_block")


def illusions():
    x0, x1, z0, z1, h = ROOMS[4]
    cx, cz = 0, 133
    carve(4, "polished_deepslate", "polished_deepslate")

    def floor(x, z):
        if x in (x0, x1) or z in (z0, z1):
            return "deepslate_tiles"
        d = math.hypot(x - cx, z - cz)
        dx, dz = abs(x - cx), abs(z - cz)
        star = dx == 0 or dz == 0 or dx == dz
        if ring(d, 11.0, 11.6):
            return "gold_block"
        if ring(d, 9.4, 11.0):
            return "amethyst_block" if star and ring(d, 9.8, 10.8) else "purple_concrete"
        if ring(d, 8.8, 9.4):
            return "gold_block"
        if ring(d, 6.8, 8.8):
            return "blue_concrete"
        if ring(d, 6.2, 6.8):
            return "gold_block"
        if d < 6.2:                                               # the eye: lids, iris, pupil
            lid = 3.2 * (1 - (dx / 5.5) ** 2) if dx <= 5 else -1
            if d < 0.5:
                return "amethyst_block"
            if d < 1.6:
                return "blue_concrete"
            if lid >= 0 and abs(dz - lid) < 0.6:
                return "purple_concrete"
            if dz == 0 and dx > 1:
                return "gold_block"
        return None
    paint(F, x0, x1, z0, z1, floor)
    W = room_walls(4)
    bays(W["west"], h, GRAND, features={0: mirror, 2: mirror, 1: rune(2)}, hb=13)
    bays(W["east"], h, GRAND, features={0: mirror, 2: mirror, 1: rune(0)}, hb=13)
    bays(W["south"], h, GRAND, skip=((14, 24),), hb=13)
    bays(W["north"], h, GRAND, skip=((14, 24),), hb=13)
    # dome: rings stepping up to a lit violet eye, the great crystal hanging from its top
    top = F + h + 1
    for k, (r, mat) in enumerate(((15, "purpur_block"), (12, "polished_deepslate"), (9, "purple_stained_glass"), (6, "purpur_block"))):
        for x in range(cx - r, cx + r + 1):
            for z in range(cz - r, cz + r + 1):
                if math.hypot(x - cx, z - cz) <= r + 0.3:
                    B(x, top, z, x, top + k, z, "air")
                    S(x, top + k + 1, z, mat)
                    if mat.endswith("glass"):
                        S(x, top + k + 2, z, "sea_lantern")
    crystal(cx, top + 1, cz, size=3, hang_to=top + 4)
    for dx, dz in ((5, 0), (-5, 0), (0, 5), (0, -5)):             # four small blue crystals on chains
        B(cx + dx, top, cz + dz, cx + dx, top + 2, cz + dz, "iron_chain[axis=y]")
        S(cx + dx, top - 1, cz + dz, "sea_lantern")
        S(cx + dx, top - 2, cz + dz, "blue_stained_glass")
    # four dimensional pillars, 12 high, a crystal floating over each
    for px in (-15, 15):
        for pz in (120, 146):
            pillar(px, pz, 12, body="purpur_block", inlay="purple_concrete", lamp="pearlescent_froglight", stairs="purpur_stairs")
            S(px, F + 14, pz, "amethyst_block")
            S(px, F + 15, pz, "amethyst_cluster[facing=up]")
            S(px, F + 13, pz, "amethyst_cluster[facing=down]")
            m.sound(px, F + 13, pz, [("block.amethyst_block.chime", 0.7, 0.5)], radius=14, every=(120, 260),
                    particle={"type": "ENCHANT", "count": 4, "spread": (1.2, 3.0, 1.2), "speed": 0.3, "every": 10})
    # fragments of stone and crystal drifting near the walls, out of reach
    for x, y, z in ((-17, 9, 127), (17, 10, 129), (-16, 11, 139), (16, 9, 141), (-9, 12, 118), (9, 11, 148)):
        S(x, F + y, z, "deepslate_bricks")
        S(x, F + y + 1, z, "amethyst_block")
        S(x + (1 if x < 0 else -1), F + y, z, "polished_deepslate")
        S(x, F + y + 2, z, "amethyst_cluster[facing=up]")
    lights(4)
    for x, z, mob in ((-7, 125, "ench_spectre"), (7, 125, "ench_spectre"), (-9, 133, "ench_gardien"), (9, 133, "ench_scribe"),
                      (-5, 141, "ench_invocateur"), (5, 141, "ench_scribe"), (0, 137, "ench_spectre"), (0, 128, "ench_eclat")):
        m.mob(mob, x, z, y=F + 1, yaw=180, room="4")
    m.sound(cx, F + 2, cz, [("block.portal.ambient", 0.35, 0.6), ("block.amethyst_block.resonate", 0.8, 0.5)], radius=22,
            every=(120, 240), particle={"type": "WITCH", "count": 4, "spread": (3.0, 0.4, 3.0), "speed": 0.01, "every": 10})
    m.sound(cx, F + 14, cz, [("block.amethyst_block.chime", 1.0, 0.5), ("block.beacon.ambient", 0.5, 0.5)], radius=24, every=(100, 200))


# ============================================================================ 5. Sanctuary of the Oversized Evoker
BOSS = dict(wall="deepslate_bricks", base="deepslate_bricks", band="polished_deepslate", panel="polished_deepslate",
            pil="deepslate_bricks", arch=("purpur_block", "purpur_stairs"), lamp="sea_lantern", bw=7, pw=2,
            glass=("purple_stained_glass", "blue_stained_glass", "purple_stained_glass"), window=9)
# the back wall's emblem, 15 x 11: a figure standing inside the circles of the dimensions
EMBLEM = (
    "....GGGGGGG....",
    "..GG.......GG..",
    ".G....PPP....G.",
    "G..GG.PPP.GG..G",
    "G.G..PPPPP..G.G",
    "G.G.PPPPPPP.G.G",
    "G.G..PPPPP..G.G",
    "G..GG.PPP.GG..G",
    ".G...PPPPP...G.",
    "..GG.PP.PP.GG..",
    "....GGGGGGG....",
)
SHRINK = ("BBBBBBB", "B.....B", "B.BBB.B", "B.BLB.B", "B.BBB.B", "B.....B", "BBBBBBB")
GROW = ("G..G..G", ".G.G.G.", "..GGG..", "GGGLGGG", "..GGG..", ".G.G.G.", "G..G..G")


def throne(cz_back):
    """The ritual throne on its dais (7 x 5, one block high), 5 wide and 7 high, pointed back."""
    z0 = cz_back - 4
    B(-3, F + 1, z0, 3, F + 1, cz_back, "deepslate_bricks")
    for x in range(-3, 4):
        S(x, F + 1, z0, "deepslate_brick_stairs[facing=south]")
    for x in (-3, 3):
        S(x, F + 1, z0, "gold_block")
        S(x, F + 2, z0, "amethyst_cluster[facing=up]")
    B(-2, F + 2, cz_back - 1, 2, F + 2, cz_back, "purpur_block")              # seat block
    S(0, F + 2, cz_back - 1, "purpur_stairs[facing=south]")
    for x in (-2, 2):                                                         # arms
        B(x, F + 3, cz_back - 1, x, F + 3, cz_back, "purpur_pillar")
        S(x, F + 4, cz_back - 1, "gold_block")
    for x, top in ((-2, 6), (-1, 7), (0, 8), (1, 7), (2, 6)):                 # the back, rising to a point
        B(x, F + 3, cz_back, x, F + top, cz_back, "deepslate_bricks" if abs(x) == 2 else "purple_concrete")
        S(x, F + top, cz_back, "gold_block" if x else "amethyst_block")
    S(0, F + 5, cz_back, "amethyst_block")
    S(0, F + 9, cz_back, "amethyst_cluster[facing=up]")


def sanctuary():
    x0, x1, z0, z1, h = ROOMS[5]
    cx, cz = 0, 183
    carve(5, "deepslate_tiles", "deepslate_tiles")

    def floor(x, z):
        if x in (x0, x1) or z in (z0, z1):
            return "polished_deepslate"
        d = math.hypot(x - cx, z - cz)
        dx, dz = abs(x - cx), abs(z - cz)
        star = dx == 0 or dz == 0 or dx == dz
        if ring(d, 14.0, 14.6):
            return "gold_block"
        if ring(d, 11.6, 14.0):                                   # fifth ring: violet and amethyst
            if star and ring(d, 12.4, 13.4):
                return "sea_lantern" if (dx == 0 or dz == 0) else "pearlescent_froglight"
            return "amethyst_block" if int(math.degrees(math.atan2(z - cz, x - cx)) % 45) in (22, 23) else "purple_concrete"
        if ring(d, 8.6, 11.6):                                    # fourth ring: blue
            return "blue_concrete"
        if ring(d, 7.6, 8.6):                                     # third ring: gold lines, cut every eighth
            return "polished_deepslate" if star else "gold_block"
        if ring(d, 5.0, 7.6):                                     # second ring: violet
            return "purple_concrete"
        if d < 5.0:                                               # first ring: stone, the rune in it
            if d < 0.5:
                return "amethyst_block"
            if dx + dz == 4 or dx + dz == 2:
                return "gold_block" if dx + dz == 4 else "purple_concrete"
            return "polished_deepslate"
        if d < 18 and star and d > 15:
            return "purple_concrete"                              # eight rays from the circle towards the pillars
        return None
    paint(F, x0, x1, z0, z1, floor)
    # the two marks, opposite each other on the blue ring: shrinking towards the way in, growing towards the throne
    for rows, zc, legend in ((SHRINK, cz - 10, {"B": "blue_concrete", ".": "light_blue_concrete", "L": "sea_lantern"}),
                             (GROW, cz + 10, {"G": "gold_block", ".": "purple_concrete", "L": "pearlescent_froglight"})):
        for j, row in enumerate(rows):
            for i, ch in enumerate(row):
                S(cx - 3 + i, F, zc - 3 + j, legend[ch])
    W = room_walls(5)
    # every wall its own design on the same frame: tall stained windows on the sides, crystal niches at the entrance
    bays(W["west"], h, BOSS, hb=17, features={0: niche_crystal, 2: rune(0), 4: niche_crystal})
    bays(W["east"], h, BOSS, hb=17, features={0: niche_crystal, 2: rune(1), 4: niche_crystal})
    bays(W["south"], h, dict(BOSS, window=4), hb=14, skip=((20, 30),), features={0: niche_crystal, 1: rune(2)})
    wn = W["north"]
    bays(wn, h, dict(BOSS, window=4), hb=14, skip=((15, 35),))
    # the back wall: the emblem behind the throne, glass lit from behind between its lines
    wn.fill(16, 1, 34, 20, 0, 0, "polished_deepslate")
    wn.fill(17, 8, 33, 20, 0, 0, "deepslate_bricks")
    for j, row in enumerate(EMBLEM):
        for i, ch in enumerate(row):
            u, y = 18 + i, 19 - j
            if ch == "G":
                wn.set(u, y, 0, "gold_block")
            elif ch == "P":
                wn.set(u, y, 0, "purple_concrete")
            else:
                wn.set(u, y, 0, "blue_stained_glass")
                wn.set(u, y, 1, "sea_lantern")
    for u in (16, 34):
        wn.fill(u, 1, u, 20, 0, 0, "purpur_pillar")
        wn.set(u, 21, 0, "gold_block")
    throne(z1)
    # square dome: steps rising to the middle, a lit ring, the great circle of gold and violet glass at the top
    top = F + h + 1
    for k, (inset, mat) in enumerate(((6, "purpur_block"), (10, "deepslate_tiles"), (14, "purple_stained_glass"),
                                      (18, "purpur_block"), (21, "deepslate_tiles"))):
        B(x0 + inset, top, z0 + inset, x1 - inset, top + k, z1 - inset, "air")
        for x in range(x0 + inset, x1 - inset + 1):
            for z in range(z0 + inset, z1 - inset + 1):
                S(x, top + k + 1, z, mat)
                if mat.endswith("glass"):
                    S(x, top + k + 2, z, "sea_lantern")
    for x in range(cx - 4, cx + 5):
        for z in range(cz - 4, cz + 5):
            d = math.hypot(x - cx, z - cz)
            if d <= 4.4:
                S(x, top + 5, z, "gold_block" if ring(d, 3.4, 4.5) or x == cx or z == cz else "purple_stained_glass")
                S(x, top + 6, z, "sea_lantern")
    for sx in (-1, 1):                                            # the corners a little higher
        for sz in (-1, 1):
            ox, oz = cx + sx * 21, cz + sz * 21
            B(ox - 2, top, oz - 2, ox + 2, top + 1, oz + 2, "air")
            B(ox - 2, top + 2, oz - 2, ox + 2, top + 2, oz + 2, "purpur_block")
            S(ox, top + 2, oz, "pearlescent_froglight")
    # eight pillars of power along the sides, outside the fighting ground, a crystal on each
    for px in (-20, 20):
        for pz in (166, 177, 189, 200):
            pillar(px, pz, 14, body="deepslate_bricks", inlay="purpur_pillar", lamp="pearlescent_froglight")
            S(px, F + 15, pz, "amethyst_block")
            S(px, F + 16, pz, "amethyst_cluster[facing=up]")
            for dx, dz, face in ((1, 0, "east"), (-1, 0, "west"), (0, 1, "south"), (0, -1, "north")):
                S(px + dx, F + 15, pz + dz, f"amethyst_cluster[facing={face}]")
    # four great crystals hanging over the edge of the fighting ground, each in its gold ring, well above a giant
    for sx in (-1, 1):
        for sz in (-1, 1):
            hx, hz = cx + sx * 14, cz + sz * 14
            crystal(hx, F + 17, hz, size=3, ring=3, hang_to=top + 1)
            for dx, dz in ((3, 0), (-3, 0), (0, 3), (0, -3)):
                S(hx + dx, F + 16, hz + dz, "purple_stained_glass")
            m.sound(hx, F + 14, hz, [("block.amethyst_block.chime", 0.8, 0.5)], radius=18, every=(140, 300),
                    particle={"type": "WITCH", "count": 3, "spread": (1.5, 2.0, 1.5), "speed": 0.01, "every": 12})
    # ritual niches along the side walls: books, candles, an enchanting table, at the foot of the pilasters
    for i, z in enumerate((161, 172, 183, 194, 205)):
        for sx in (-1, 1):
            x = sx * 25
            S(x, F + 1, z, ("bookshelf", "chiseled_deepslate", "enchanting_table", "chiseled_deepslate", "bookshelf")[i])
            if i != 2:
                S(x, F + 2, z, "purple_candle[candles=3,lit=true]" if i % 2 else "amethyst_cluster[facing=up]")
    lights(5, level=11, step=8, y=6)
    m.mob("evocateur_demesure", 0, z1 - 8, y=F + 1, yaw=180, room="5")
    m.objectives = ["evocateur_demesure"]
    m.sound(cx, F + 3, cz, [("block.beacon.ambient", 0.6, 0.5), ("block.amethyst_block.resonate", 0.9, 0.5),
                            ("block.respawn_anchor.ambient", 0.4, 0.6)], radius=30, every=(120, 240))
    m.sound(cx, F + 12, cz, [("block.amethyst_block.chime", 1.0, 0.5), ("block.bell.resonate", 0.3, 0.5)], radius=30, every=(160, 320))


# ============================================================================ way in, passages, doors
def ways():
    # entrance: a short porch, two dark pillars, an arch with a gold rune, the first door
    B(-3, F + 1, -9, 3, F + 8, -2, "air")
    B(-4, F, -10, 4, F, -1, "polished_deepslate")
    B(0, F, -9, 0, F, -1, "purple_concrete")
    B(-4, F + 1, -10, 4, F + 9, -10, "deepslate_bricks")
    B(-4, F + 9, -9, 4, F + 9, -2, "deepslate_tiles")
    for x in (-4, 4):
        B(x, F + 1, -9, x, F + 8, -2, "deepslate_bricks")
    for x in (-3, 3):
        B(x, F + 1, -3, x, F + 7, -3, "polished_deepslate")
        S(x, F + 8, -3, "gold_block")
        S(x, F + 4, -3, "amethyst_block")
    art(("G.G", ".A.", "G.G"), LEGEND, -1, F + 7, -10)
    S(0, F + 9, -6, "purple_stained_glass")
    S(0, F + 10, -6, "sea_lantern")
    S(0, F + 4, -6, "light[level=12]")
    m.start = [0.5, float(F + 1), -6.5, 0.0, 0.0]
    g.start = (0, F + 1, -7)
    door("vestibule", -1, "crystal", 1, kills=False)
    names = {2: ("bibliotheque", "book"), 3: ("laboratoire", "alembic"), 4: ("illusions", "eye"), 5: ("sanctuaire", "rune")}
    for n in (2, 3, 4, 5):
        z0 = ROOMS[n - 1][3] + 1
        passage(z0)
        name, symbol = names[n]
        door(f"sortie_{n - 1}", z0, symbol, n - 1, kills=True)
        door(name, z0 + 6, symbol, n, kills=False)


def check():
    errors = g.check_basic()
    planes = [a[2] for a, b in g.doors]
    for k in range(len(g.doors) + 1):
        seen = g.reach(g.start, set(range(k)))
        far = max(p[2] for p in seen)
        limit = planes[k] if k < len(planes) else Z1
        if far > limit:
            errors.append(f"{k} doors open: z {far} reachable beyond the door at z {limit}")
        if k and far < planes[k - 1] + 1:
            errors.append(f"{k} doors open: nothing reachable past the door at z {planes[k - 1]}")
    for n, (x0, x1, z0, z1, h) in ROOMS.items():
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if not gridlib.passable(g.get(x, F + h, z)) and not (n in (2,) and g.get(x, F + h, z).startswith("ladder")):
                    pass
        fx0, fx1, fz0, fz1 = FREE[n]
        for x in range(fx0, fx1 + 1):
            for z in range(fz0, fz1 + 1):
                if gridlib.passable(g.get(x, F, z)):
                    errors.append(f"room {n}: hole in the floor at {x} {z}")
                for y in range(1, 5 if n < 5 else 12):
                    if not gridlib.passable(g.get(x, F + y, z)):
                        errors.append(f"room {n}: fighting ground not free at {x} {F + y} {z} ({g.get(x, F + y, z)})")
    for s in m.spawns:
        x, y, z = int(math.floor(s["at"][0])), int(s["at"][1]), int(math.floor(s["at"][2]))
        if not (gridlib.passable(g.get(x, y, z)) and gridlib.passable(g.get(x, y + 1, z)) and not gridlib.passable(g.get(x, y - 1, z))):
            errors.append(f"{s['mob']} cannot stand at {x} {y} {z}")
    return errors


vestibule()
library()
laboratory()
illusions()
sanctuary()
ways()

if __name__ == "__main__":
    args = sys.argv[1:]
    server = args[0] if args and not args[0].startswith("--") else "labs"
    errs = check()
    for e in errs[:40]:
        print("CHECK:", e)
    print(f"{len(errs)} problem(s), {len(g.over)} blocks changed, {len(m.spawns)} monsters, {len(g.doors)} doors")
    if "--preview" in args:
        out = args[args.index("--preview") + 1]
        os.makedirs(out, exist_ok=True)
        g.png_slice(os.path.join(out, "ench_sol.png"), y=F, k=3)
        g.png_slice(os.path.join(out, "ench_y3.png"), y=F + 3, k=3)
        g.png_slice(os.path.join(out, "ench_coupe_x0.png"), x=0, k=3)
        g.png_slice(os.path.join(out, "ench_coupe_x12.png"), x=12, k=3)
    if "--check" in args or "--preview" in args:
        sys.exit(1 if errs else 0)
    if errs:
        sys.exit("checks failed: nothing sent")
    if "--edits" in args:
        maplib.write_edits(m, server, DUNGEON)
        sys.exit(0)
    g.commands(m)
    print(f"{len(m.cmds)} commands")
    maplib.run(m, server, DUNGEON)
