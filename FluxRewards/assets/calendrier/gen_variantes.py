#!/usr/bin/env python3
"""Loyalty menu (/fidelite) as a real monthly calendar: 5 proposals (2026-10-29).

Layout shared by every variant:
  above the window   the month's name (drawn in the title by FluxRewards: one glyph per month), the weekday letters
                     L M M J V S D just above the first week (the frame starts LIFT px above the window)
  chest rows 0 to 5  the weeks, Monday to Sunday in columns 1 to 7: one case per day of the month, its number shown
                     by the item's count; back (0) and menu icon (8) on row 0, columns 0 and 8 are margins
  hidden inventory   a welcome banner in the chest/inventory gap, today's reward on a gold case (58), the month's
                     stats on each side (56, 60)

    <venv with Pillow>/python3 gen_variantes.py <out dir>
Writes fond_<n>.png and variantes_calendrier.png (each one with October 2026 filled in: days claimed, missed, today).
"""
import calendar
import math
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
FLUX = os.path.join(HERE, "..", "..", "..", "Flux", "assets")
sys.path.insert(0, FLUX)
from fond_lib import generique_raccourci, poser, tampon_case, text, text_width  # noqa: E402

W, H = 176, 221
LIFT = 22                     # the frame starts this far above the window (month name, weekday letters)
INV_Y = 139                   # item y of the hidden inventory's first row in game
TODAY_SLOT, STAT_SLOTS = 58, (56, 60)
WEEKDAYS = "LMMJVSD"
WHITE, SHADOW = (255, 255, 255, 255), (40, 30, 60, 255)
OUTLINE, RIM, MID = (21, 17, 27, 255), (127, 102, 163, 255), (89, 70, 116, 255)
DARK = [(63, 50, 83, 255), (52, 41, 66, 255), (44, 35, 57, 255), (35, 28, 46, 255), (27, 21, 39, 255)]
VIOLET, VIOLET_LIGHT = (91, 45, 158, 255), (179, 118, 229, 255)
GOLD, GOLD_DARK, GOLD_LIGHT = (240, 168, 0, 255), (138, 90, 20, 255), (255, 217, 77, 255)
PINK, PINK_DARK = (224, 96, 160, 255), (200, 37, 106, 255)
PAPER, PAPER_LINE, PAPER_SHADE = (246, 242, 250, 255), (226, 219, 234, 255), (205, 195, 220, 255)


def item(slot):
    """Window position of the item of a slot (chest, then hidden inventory)."""
    if slot < 54:
        return 8 + 18 * (slot % 9), 18 + 18 * (slot // 9)
    return 8 + 18 * ((slot - 54) % 9), INV_Y + 18 * ((slot - 54) // 9)


def day_slots(year, month):
    """Slot of each day of the month: weeks on rows 0 to 5, Monday in column 1."""
    first, days = calendar.monthrange(year, month)
    return {d: ((first + d - 1) // 7) * 9 + 1 + (first + d - 1) % 7 for d in range(1, days + 1)}


def grid_slots():
    return [r * 9 + c for r in range(6) for c in range(1, 8)]


class Canvas:
    """Draws in window coordinates on an image starting LIFT px above the window."""

    def __init__(self, im=None):
        self.im = im or Image.new("RGBA", (W, H), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.im)

    def rect(self, box, **kw):
        x0, y0, x1, y1 = box
        self.d.rectangle((x0, y0 + LIFT, x1, y1 + LIFT), **kw)

    def rrect(self, box, radius, **kw):
        x0, y0, x1, y1 = box
        self.d.rounded_rectangle((x0, y0 + LIFT, x1, y1 + LIFT), radius=radius, **kw)

    def line(self, pts, **kw):
        self.d.line([(x, y + LIFT) for x, y in pts], **kw)

    def polygon(self, pts, **kw):
        self.d.polygon([(x, y + LIFT) for x, y in pts], **kw)

    def ellipse(self, box, **kw):
        x0, y0, x1, y1 = box
        self.d.ellipse((x0, y0 + LIFT, x1, y1 + LIFT), **kw)

    def point(self, x, y, colour):
        if 0 <= x < W and 0 <= y + LIFT < H:
            self.im.putpixel((x, y + LIFT), colour)

    def text(self, s, cx, y, colour=WHITE, shadow=SHADOW):
        text(self.im, s, cx, y + LIFT, colour=colour, shadow=shadow)

    def case(self, slot, stamp=None):
        poser(self.im, stamp or tampon_case(), item(slot)[0], item(slot)[1] + LIFT)


def frame(bottom=INV_Y + 21):
    """Generic frame of the theme from LIFT px above the window down to the inventory row."""
    return Canvas(generique_raccourci(os.path.join(FLUX, "menu_generique.png"), 0, bottom + LIFT))


def weekday_letters(c, y, colour=WHITE, weekend=PINK, shadow=SHADOW):
    for i, ch in enumerate(WEEKDAYS):
        c.text(ch, item(1 + i)[0] + 8, y, colour=weekend if i >= 5 else colour, shadow=shadow)


def star(c, cx, cy, r, colour):
    pts = []
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5
        rr = r if i % 2 == 0 else r * 0.45
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    c.polygon(pts, fill=colour)


def sparkle(c, x, y, colour=GOLD_LIGHT):
    c.point(x, y, WHITE)
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        c.point(x + dx, y + dy, colour)


def gold_case(c, slot):
    x, y = item(slot)
    c.rrect((x - 3, y - 3, x + 18, y + 18), 4, fill=GOLD_DARK)
    c.rrect((x - 2, y - 2, x + 17, y + 17), 3, fill=GOLD)
    c.rrect((x - 1, y - 1, x + 16, y + 16), 3, fill=GOLD_LIGHT)
    c.rrect((x, y, x + 15, y + 15), 2, fill=GOLD)
    for dx, dy in ((-6, 2), (21, 4), (-5, 13), (22, 14)):
        sparkle(c, x + dx, y + dy)


def welcome(c, fill, edge, words="Bienvenue !", colour=WHITE):
    """Banner in the chest/inventory gap, under the last week."""
    y = 126
    w = text_width(words) + 16
    x0 = 88 - w // 2
    c.polygon([(x0 - 6, y), (x0, y), (x0, y + 9), (x0 - 6, y + 9), (x0 - 3, y + 4)], fill=edge)
    c.polygon([(x0 + w + 6, y), (x0 + w, y), (x0 + w, y + 9), (x0 + w + 6, y + 9), (x0 + w + 3, y + 4)], fill=edge)
    c.rect((x0, y - 1, x0 + w, y + 9), fill=fill, outline=edge)
    c.text(words, 88, y + 2, colour=colour)


def inventory_cases(c):
    for s in STAT_SLOTS:
        c.case(s)
    gold_case(c, TODAY_SLOT)


# ---------------------------------------------------------------- variants

def v1():
    """Éphéméride: a paper desk calendar. Violet cover band with gold spiral rings, cream page printed with a thin
    grid, weekend columns tinted pink, the page curling at the bottom right."""
    c = frame()
    x0, x1 = item(1)[0] - 3, item(7)[0] + 18
    top, bottom = -16, item(45)[1] + 19
    c.rrect((x0 - 1, top - 1, x1 + 1, bottom + 1), 4, fill=OUTLINE)
    c.rrect((x0, top, x1, bottom), 3, fill=PAPER)
    c.rrect((x0, top, x1, 2), 3, fill=VIOLET)
    c.rect((x0, -1, x1, 2), fill=VIOLET)
    c.line([(x0, 3), (x1, 3)], fill=(60, 28, 110, 255))
    for i in range(9):  # spiral rings
        rx = x0 + 7 + i * (x1 - x0 - 14) // 8
        c.rrect((rx - 1, top - 6, rx + 1, top + 3), 1, fill=GOLD_LIGHT, outline=GOLD_DARK)
    c.text("Octobre", 88, top + 7, colour=WHITE, shadow=(60, 28, 110, 255))
    for col in (6, 7):
        cx = item(col)[0]
        c.rect((cx - 1, 4, cx + 16, bottom - 1), fill=(250, 230, 240, 255))
    weekday_letters(c, 7, colour=VIOLET, weekend=PINK_DARK, shadow=PAPER_LINE)
    for r in range(6):
        for col in range(1, 8):
            x, y = item(r * 9 + col)
            c.rect((x - 1, y - 1, x + 16, y + 16), outline=PAPER_SHADE)
    c.polygon([(x1 - 9, bottom), (x1, bottom - 9), (x1, bottom)], fill=PAPER_SHADE)
    c.line([(x1 - 9, bottom), (x1, bottom - 9)], fill=MID)
    c.case(0)
    c.case(8)
    welcome(c, VIOLET, OUTLINE)
    inventory_cases(c)
    return c.im


def v2():
    """Agenda: the dark theme of the other menus, gold month, the weeks on the theme's cases with weekends on pink
    cases, a light violet line under each week."""
    c = frame()
    c.text("Octobre", 88, -13, colour=GOLD_LIGHT, shadow=GOLD_DARK)
    for dx in (-30, 30):
        star(c, 88 + dx, -11, 3, GOLD)
    weekday_letters(c, 7, colour=VIOLET_LIGHT)
    pink = {k: (min(255, v[0] + 50), v[1], min(255, v[2] + 10), v[3]) for k, v in tampon_case().items()}
    for r in range(6):
        for col in range(1, 8):
            c.case(r * 9 + col, pink if col >= 6 else None)
    c.case(0)
    c.case(8)
    welcome(c, DARK[3], GOLD, colour=GOLD_LIGHT)
    inventory_cases(c)
    return c.im


def v3():
    """Calendrier mural: hung on a nail by a string, a pink header with the month, perforations of the torn pages
    above it, pages piled under the last week."""
    c = frame()
    x0, x1 = item(1)[0] - 4, item(7)[0] + 19
    top, bottom = -14, item(45)[1] + 19
    c.line([(88, -21), (x0 + 12, top)], fill=GOLD_DARK)
    c.line([(88, -21), (x1 - 12, top)], fill=GOLD_DARK)
    c.ellipse((86, -23, 90, -19), fill=MID, outline=OUTLINE)
    for k in (3, 2, 1):  # pages piled under
        c.rrect((x0 + k, bottom - 3, x1 + k, bottom + k * 2), 2, fill=PAPER_SHADE if k % 2 else PAPER_LINE, outline=MID)
    c.rrect((x0 - 1, top - 1, x1 + 1, bottom + 1), 3, fill=OUTLINE)
    c.rrect((x0, top, x1, bottom), 2, fill=PAPER)
    c.rect((x0, top, x1, 15), fill=PINK_DARK)
    c.rect((x0, top, x1, top + 1), fill=PINK)
    c.line([(x0, 16), (x1, 16)], fill=(120, 20, 70, 255))
    for x in range(x0 + 3, x1 - 1, 4):  # perforations
        c.point(x, top + 3, (120, 20, 70, 255))
    c.text("Octobre", 88, top + 7, colour=WHITE, shadow=(120, 20, 70, 255))
    weekday_letters(c, 7, colour=WHITE, weekend=GOLD_LIGHT, shadow=(120, 20, 70, 255))
    for r in range(6):
        for col in range(1, 8):
            x, y = item(r * 9 + col)
            c.rrect((x - 1, y - 1, x + 16, y + 16), 2, fill=(236, 228, 246, 255) if (r + col) % 2 else PAPER,
                    outline=PAPER_SHADE)
    c.case(0)
    c.case(8)
    welcome(c, PINK_DARK, OUTLINE)
    inventory_cases(c)
    return c.im


def v4():
    """Nuit étoilée: a night sky behind the month, each day a round seat with a faint halo, gold crescent moon and
    stars in the margins (the charter's stars)."""
    c = frame()
    x0, x1 = item(1)[0] - 4, item(7)[0] + 19
    top, bottom = -18, item(45)[1] + 19
    c.rrect((x0 - 1, top - 1, x1 + 1, bottom + 1), 5, fill=OUTLINE)
    for y in range(top, bottom + 1):
        t = (y - top) / (bottom - top)
        col = (int(18 + 30 * t), int(14 + 10 * t), int(46 + 30 * t), 255)
        c.line([(x0, y), (x1, y)], fill=col)
    seed = 11
    for _ in range(70):
        seed = (seed * 1103515245 + 12345) % (1 << 31)
        sx = x0 + 2 + seed % (x1 - x0 - 4)
        seed = (seed * 1103515245 + 12345) % (1 << 31)
        sy = top + 2 + seed % (bottom - top - 4)
        c.point(sx, sy, (210, 190, 240, 255) if seed % 4 else GOLD_LIGHT)
    c.text("Octobre", 88, top + 5, colour=GOLD_LIGHT, shadow=(10, 6, 24, 255))
    c.ellipse((x0 + 8, top + 2, x0 + 18, top + 12), fill=GOLD_LIGHT)
    c.ellipse((x0 + 11, top + 1, x0 + 21, top + 11), fill=(18, 14, 46, 255))
    star(c, x1 - 12, top + 7, 4, GOLD_LIGHT)
    weekday_letters(c, 7, colour=VIOLET_LIGHT, weekend=PINK, shadow=(10, 6, 24, 255))
    for r in range(6):
        for col in range(1, 8):
            x, y = item(r * 9 + col)
            c.ellipse((x - 2, y - 2, x + 17, y + 17), fill=(60, 40, 100, 120))
            c.ellipse((x, y, x + 15, y + 15), fill=(14, 10, 34, 255), outline=(150, 110, 210, 255))
    c.case(0)
    c.case(8)
    welcome(c, (18, 14, 46, 255), VIOLET_LIGHT, colour=GOLD_LIGHT)
    inventory_cases(c)
    return c.im


def v5():
    """Calendrier de l'Avent: every day is a little door of a colour of the charter (violet, pink, gold, blue,
    green) with a ribbon, a bow on the header, confetti in the margins."""
    c = frame()
    x0, x1 = item(1)[0] - 4, item(7)[0] + 19
    top, bottom = -18, item(45)[1] + 19
    c.rrect((x0 - 1, top - 1, x1 + 1, bottom + 1), 4, fill=OUTLINE)
    c.rrect((x0, top, x1, bottom), 3, fill=(58, 30, 98, 255))
    c.rrect((x0 + 2, top + 2, x1 - 2, 3), 2, fill=PINK_DARK)
    c.text("Octobre", 88, top + 6, colour=WHITE, shadow=(110, 15, 60, 255))
    c.polygon([(88, top + 1), (80, top - 4), (80, top + 5)], fill=GOLD)  # bow
    c.polygon([(88, top + 1), (96, top - 4), (96, top + 5)], fill=GOLD)
    c.rect((86, top - 1, 90, top + 3), fill=GOLD_LIGHT)
    weekday_letters(c, 7, colour=WHITE, weekend=GOLD_LIGHT, shadow=(30, 12, 60, 255))
    doors = [VIOLET, PINK_DARK, GOLD_DARK, (43, 140, 190, 255), (46, 160, 100, 255)]
    for r in range(6):
        for col in range(1, 8):
            x, y = item(r * 9 + col)
            base = doors[(r * 3 + col) % len(doors)]
            light = tuple(min(255, v + 40) for v in base[:3]) + (255,)
            c.rrect((x - 1, y - 1, x + 16, y + 16), 2, fill=OUTLINE)
            c.rrect((x, y, x + 15, y + 15), 2, fill=base)
            c.line([(x + 1, y + 1), (x + 14, y + 1)], fill=light)
            c.line([(x + 7, y), (x + 7, y + 15)], fill=GOLD_LIGHT)
    for x, y in ((14, 30), (160, 44), (13, 74), (161, 96), (15, 112), (158, 120)):
        c.rect((x, y, x + 1, y + 1), fill=[PINK, GOLD_LIGHT, VIOLET_LIGHT][x % 3])
    c.case(0)
    c.case(8)
    welcome(c, PINK_DARK, OUTLINE)
    inventory_cases(c)
    return c.im


VARIANTS = [("1 ephemeride", v1), ("2 agenda", v2), ("3 mural", v3), ("4 nuit etoilee", v4), ("5 avent", v5)]


# ---------------------------------------------------------------- preview

def icon(name):
    p = os.path.join("/home/influencraft/serveurs/04_labs/plugins/ItemsAdder/contents/influitems/textures", name + ".png")
    return Image.open(p).convert("RGBA").resize((16, 16), Image.NEAREST)


def mark(kind):
    """The light day marks to create in the pack (16 x 16): tick (claimed), cross (missed), gift (today, big day)."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if kind == "tick":
        for w, col in ((3, (20, 90, 50, 255)), (1, (46, 207, 123, 255))):
            d.line([(3, 8), (6, 11), (12, 4)], fill=col, width=w)
    elif kind == "cross":
        for w, col in ((3, (110, 20, 30, 255)), (1, (255, 92, 92, 255))):
            d.line([(4, 4), (11, 11)], fill=col, width=w)
            d.line([(11, 4), (4, 11)], fill=col, width=w)
    else:
        big = kind == "gift"
        box = (2, 6, 13, 14) if big else (4, 8, 11, 14)
        d.rectangle(box, fill=PINK_DARK if big else VIOLET_LIGHT, outline=OUTLINE)
        d.rectangle((box[0], box[1], box[2], box[1] + 2), fill=PINK if big else (200, 160, 240, 255), outline=OUTLINE)
        cx = (box[0] + box[2]) // 2
        d.line([(cx, box[1]), (cx, box[3])], fill=GOLD_LIGHT)
        d.polygon([(cx, box[1]), (cx - 3, box[1] - 3), (cx - 1, box[1])], fill=GOLD)
        d.polygon([(cx, box[1]), (cx + 3, box[1] - 3), (cx + 1, box[1])], fill=GOLD)
    return im


def count(im, n, x, y, k):
    """Item count as in game: white digits bottom right of the case, dark shadow."""
    s = str(n)
    lbl = Image.new("RGBA", (text_width(s) + 2, 7), (0, 0, 0, 0))
    text(lbl, s, lbl.width // 2, 0, colour=WHITE, shadow=(30, 30, 30, 255))
    lbl = lbl.resize((lbl.width * k, lbl.height * k), Image.NEAREST)
    im.alpha_composite(lbl, ((x + 17) * k - lbl.width, (y + 16) * k - lbl.height + k))


def preview(bg, k=3):
    """October 2026 filled in: claimed days (tick), a few missed (cross), today (29, gift), the big days to come
    (small gift), the others empty: only their number."""
    view = Image.new("RGBA", (W, INV_Y + 30 + LIFT), (58, 58, 58, 255))
    view.alpha_composite(bg.crop((0, 0, W, view.height)))
    view = view.resize((W * k, view.height * k), Image.NEAREST)
    today, missed = 29, {6, 13, 14, 22}
    icons = {"ok": mark("tick"), "ko": mark("cross"), "today": mark("gift"), "big": mark("small_gift")}
    for d, slot in day_slots(2026, 10).items():
        x, y = item(slot)
        y += LIFT
        if d < today:
            ic = icons["ko" if d in missed else "ok"]
        elif d == today:
            ic = icons["today"]
        else:
            ic = icons["big"] if d % 7 == 0 or d == 31 else None
        if ic is not None:
            view.alpha_composite(ic.resize((16 * k, 16 * k), Image.NEAREST), (x * k, y * k))
        count(view, d, x, y, k)
    for s, ic in ((0, icon("back")), (8, icon("reward")), (TODAY_SLOT, mark("gift")),
                  (STAT_SLOTS[0], mark("tick")), (STAT_SLOTS[1], mark("small_gift"))):
        x, y = item(s)
        view.alpha_composite(ic.resize((16 * k, 16 * k), Image.NEAREST), (x * k, (y + LIFT) * k))
    return view


if __name__ == "__main__":
    out = sys.argv[1]
    tiles = []
    for name, fn in VARIANTS:
        im = fn()
        im.save(os.path.join(out, "fond_%s.png" % name.split()[0]))
        tiles.append((name, preview(im)))
    tw, th = tiles[0][1].size
    sheet = Image.new("RGBA", (tw * 3 + 60, (th + 40) * 2), (20, 16, 28, 255))
    for i, (name, t) in enumerate(tiles):
        x, y = (i % 3) * (tw + 30), (i // 3) * (th + 40)
        sheet.alpha_composite(t, (x, y + 30))
        lbl = Image.new("RGBA", (text_width(name) + 2, 7), (0, 0, 0, 0))
        text(lbl, name, lbl.width // 2, 0, colour=WHITE)
        sheet.alpha_composite(lbl.resize((lbl.width * 3, lbl.height * 3), Image.NEAREST), (x + 6, y + 4))
    sheet.save(os.path.join(out, "variantes_calendrier.png"))
    print("->", out)
