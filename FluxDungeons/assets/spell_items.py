"""Item icons of the spells (2026-11-16): every class's spells as items of the pack fluxdonjon, shown in the « spells »
line of /donjon (menu.DungeonMenus, rpg.SpellBook names them):

  sort_<type>       a skill (its "type" in classes.yml): the hud's own picture (hud_spells.ART), one drawing for both
  attaque_<style>   a basic attack (ClassDef.Attack.STYLES: melee, arrow, arcane, holy, shard, ember, thorn)
  passif_<class>    the mechanic of an elemental class (cryomancer, pyromancer, druid)

16 x 16 pixel art on the hud's palette (short ramps, no pure black). Imported by menus/gen_menus.py, which writes the
pack; run alone it writes a preview sheet:

    python3 spell_items.py <out.png>
"""
import math
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hud_spells import ART, PALETTE, SPELLS, grid, put, rgba  # noqa: E402

STYLES = ["melee", "arrow", "arcane", "holy", "shard", "ember", "thorn"]
PASSIVES = ["cryomancer", "pyromancer", "druid"]


def line(g, x0, y0, x1, y1, c):
    n = max(abs(x1 - x0), abs(y1 - y0))
    for i in range(n + 1):
        put(g, round(x0 + (x1 - x0) * i / n), round(y0 + (y1 - y0) * i / n), c)


def bolt(g, head, body, rim, trail, r=2.6, hx=11.0, hy=4.5):
    """A round projectile flying up and right, its tail behind it (down and left)."""
    tx, ty = 2.0, 13.5
    dx, dy = tx - hx, ty - hy
    n = math.hypot(dx, dy)
    for y in range(16):
        for x in range(16):
            px, py = x + 0.5 - hx, y + 0.5 - hy
            t = max(0.0, min(1.0, (px * dx + py * dy) / (n * n)))
            d = math.hypot(px - t * dx, py - t * dy)
            rr = r * (1 - t) ** 1.1
            if d > rr or rr < 0.5:
                continue
            k = d / rr
            if t < 0.12:
                c = head if k < 0.45 else body if k < 0.8 else rim
            else:
                c = body if k < 0.4 and t < 0.45 else rim if t < 0.7 else trail
            put(g, x, y, c)
    return g


# ---------------------------------------------------------------- basic attacks

def melee():
    # a sword striking down from the top right, an arc of its swing behind the blade
    g = grid()
    for i in range(16):
        a = math.radians(200 + i * 5.5)
        put(g, round(8 + 6.8 * math.cos(a)), round(8 + 6.8 * math.sin(a)), "3" if i % 3 else "2")
    line(g, 13, 2, 6, 9, "4")
    line(g, 13, 3, 7, 9, "3")
    put(g, 14, 1, "4")
    line(g, 4, 8, 8, 12, "u")
    put(g, 5, 9, "D")
    put(g, 7, 11, "D")
    line(g, 5, 11, 3, 13, "W")
    put(g, 2, 14, "b")
    return g


def arrow():
    # an arrow in flight, feathers behind it
    g = grid()
    line(g, 3, 12, 12, 3, "W")
    line(g, 4, 12, 12, 4, "w")
    for x, y, c in [(13, 2, "4"), (12, 2, "3"), (13, 3, "3"), (11, 2, "2"), (13, 4, "2"), (14, 1, "4")]:
        put(g, x, y, c)
    for x, y in [(2, 11), (1, 12), (3, 13), (2, 14), (1, 11), (3, 14)]:
        put(g, x, y, "l" if (x + y) % 2 else "G")
    put(g, 2, 13, "L")
    return g


def arcane():
    # a violet trait of magic, sparks around it
    g = bolt(grid(), "P", "p", "V", "v", r=2.8)
    for x, y in [(4, 4), (13, 10), (8, 1), (2, 8)]:
        put(g, x, y, "p")
    return g


def holy():
    # a ray of light: a straight beam, a star where it lands
    g = grid()
    line(g, 1, 14, 11, 4, "u")
    line(g, 2, 14, 11, 5, "D")
    line(g, 1, 13, 10, 4, "U")
    for dx, dy, c in [(0, 0, "x"), (1, 0, "U"), (-1, 0, "U"), (0, 1, "U"), (0, -1, "U"), (2, 0, "u"), (-2, 0, "u"),
                      (0, 2, "u"), (0, -2, "u"), (1, 1, "u"), (-1, -1, "u"), (1, -1, "u"), (-1, 1, "u"), (3, 0, "D"),
                      (0, -3, "D")]:
        put(g, 12 + dx, 3 + dy, c)
    return g


def shard():
    # a shard of ice, long and sharp, frost flakes behind it
    g = grid()
    for i in range(12):
        x, y = 3 + i * 0.85, 12.5 - i * 0.85
        w = 2.2 * math.sin(math.pi * (i + 1) / 13) ** 0.7
        for yy in range(16):
            for xx in range(16):
                # distance across the shard's axis (the axis goes up and right)
                across = ((xx + 0.5 - x) + (yy + 0.5 - y)) / math.sqrt(2)
                along = ((xx + 0.5 - x) - (yy + 0.5 - y)) / math.sqrt(2)
                if abs(along) < 0.6 and abs(across) <= w:
                    put(g, xx, yy, "C" if across < -0.4 else "c" if across < 0.6 else "I")
    put(g, 13, 2, "C")
    for x, y in [(2, 9), (5, 14), (1, 13)]:
        put(g, x, y, "c")
    return g


def ember():
    # a burning ember, flames trailing behind it
    g = bolt(grid(), "y", "o", "F", "f", r=3.0)
    for x, y in [(3, 9), (7, 14), (1, 11)]:
        put(g, x, y, "F")
    return g


def thorn():
    # a thorn flying, a leaf on its stem
    g = grid()
    line(g, 2, 13, 13, 2, "G")
    line(g, 3, 13, 13, 3, "g")
    line(g, 9, 6, 13, 2, "l")
    put(g, 14, 1, "L")
    # the leaf below the stem
    for x, y, c in [(6, 11, "l"), (7, 12, "G"), (6, 12, "l"), (5, 12, "L"), (7, 11, "G"), (8, 12, "g"), (6, 13, "G")]:
        put(g, x, y, c)
    # little barbs
    put(g, 6, 8, "G")
    put(g, 9, 5, "G")
    put(g, 11, 6, "g")
    return g


# ---------------------------------------------------------------- passives

def passive_cryomancer():
    # a block of ice cracking open (frost then shatter)
    g = grid()
    for y in range(3, 14):
        for x in range(3, 14):
            edge = x in (3, 13) or y in (3, 13)
            c = "i" if edge else "C" if x + y < 12 else "c" if x + y < 20 else "I"
            put(g, x, y, c)
    for x, y in [(8, 3), (8, 4), (7, 5), (8, 6), (9, 7), (8, 8), (7, 9), (6, 10), (9, 8), (10, 9), (11, 9), (5, 11), (6, 12)]:
        put(g, x, y, "i")
    for x, y in [(1, 2), (14, 1), (15, 6), (1, 14), (14, 14), (2, 8)]:
        put(g, x, y, "c")
    return g


def passive_pyromancer():
    # a flame rising, hottest at its heart, a spark flying off it
    from hud_spells import rows
    return rows(
        "..........F.....",
        ".......f........",
        ".......ff.......",
        "......fFf.......",
        "......fFFf..f...",
        ".....fFoFf..ff..",
        "..f..fFoFFf.fF..",
        "..ff.fFooFFfFF..",
        "..fFfFoyooFFFf..",
        "..fFFoyyyoFoFf..",
        "..fFooyyyyooFf..",
        "...fFoyxxyoFf...",
        "...fFoyxxyoFf...",
        "....fFoyyoFf....",
        ".....ffFFff.....",
        "................")


def passive_druid():
    # a flower blooming from a seed
    g = grid()
    line(g, 8, 8, 8, 13, "G")
    for x, y, c in [(9, 11, "l"), (10, 10, "l"), (10, 11, "G"), (6, 12, "l"), (5, 11, "l"), (6, 11, "G")]:
        put(g, x, y, c)
    for x, y, c in [(7, 14, "w"), (8, 14, "W"), (9, 14, "w"), (8, 15, "w")]:
        put(g, x, y, c)
    petals = [(8, 2), (8, 3), (11, 5), (12, 5), (5, 5), (4, 5), (10, 8), (6, 8), (7, 3), (9, 3), (11, 6), (5, 6)]
    for x, y in petals:
        put(g, x, y, "P")
    for x, y in [(7, 4), (9, 4), (10, 5), (6, 5), (10, 7), (6, 7), (7, 7), (9, 7)]:
        put(g, x, y, "p")
    for x, y in [(8, 5), (7, 5), (9, 5), (8, 6), (7, 6), (9, 6), (8, 4), (8, 7)]:
        put(g, x, y, "u")
    put(g, 8, 5, "U")
    return g


EXTRA = {"attaque_" + s: f for s, f in zip(STYLES, [melee, arrow, arcane, holy, shard, ember, thorn])}
EXTRA.update({"passif_" + c: f for c, f in zip(PASSIVES, [passive_cryomancer, passive_pyromancer, passive_druid])})


# ---------------------------------------------------------------- rendering

def picture(g):
    """16 x 16 icon: the drawing, a soft dark outline around it so it reads on any slot."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = im.load()
    for y in range(16):
        for x in range(16):
            if g[y][x] != ".":
                px[x, y] = rgba(PALETTE[g[y][x]])
    out = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    o = out.load()
    for y in range(16):
        for x in range(16):
            if px[x, y][3]:
                continue
            if any(0 <= x + dx < 16 and 0 <= y + dy < 16 and px[x + dx, y + dy][3]
                   for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                o[x, y] = (24, 14, 40, 170)
    out.alpha_composite(im)
    return out


def icons():
    """{item id: (picture, display name)}: every skill, basic attack and passive."""
    out = {}
    for s in SPELLS:
        if s in ART:
            out["sort_" + s] = (picture(ART[s]()), "Sort")
    for iid, f in EXTRA.items():
        out[iid] = (picture(f()), "Sort")
    return out


if __name__ == "__main__":
    all_icons = icons()
    cols = 10
    rows_n = (len(all_icons) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * 20, rows_n * 20), (139, 139, 139, 255))
    for i, (im, _) in enumerate(all_icons.values()):
        sheet.alpha_composite(im, ((i % cols) * 20 + 2, (i // cols) * 20 + 2))
    sheet.resize((sheet.width * 6, sheet.height * 6), Image.NEAREST).save(sys.argv[1])
    print(len(all_icons), "icons:", ", ".join(all_icons))
