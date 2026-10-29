#!/usr/bin/env python3
"""Calendar menu (/calendrier, FluxCalendar): a real monthly calendar, « mural » chosen 2026-10-29 among 5 proposals.

Layout:
  above the window   the month's name (drawn in the title by FluxCalendar: one glyph per month), the weekday letters
                     L M M J V S D just above the first week (the frame starts LIFT px above the window)
  chest rows 0 to 5  the weeks, Monday to Sunday in columns 1 to 7: one case per day of the month, its number shown
                     by the item's count; back (0) and menu icon (8) on row 0, columns 0 and 8 are margins
  hidden inventory   a welcome banner in the chest/inventory gap, today's reward on a gold case (58), the month's
                     stats on each side (56, 60)

    <venv with Pillow>/python3 gen_fond.py <plugins dir of the server> [preview dir]
Writes the ItemsAdder pack « fluxcalendar » (background menu_calendrier, title words mois_<1..12> and chiffre_<0..9>,
day marks jour_recupere / jour_manque / jour_cadeau / jour_bonus, each also with a drawn « 1 » for the 1st of the
month: the game shows no count of 1, jour_un for an empty 1st) and plugins/FluxCalendar/glyphs.yml, then /iazip.
The preview shows October 2026 filled in.
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
HEADER_TOP = -14              # window y of the calendar's pink header
HEADER_SHADOW = (120, 20, 70, 255)
TITLE_Y = HEADER_TOP + 7      # window y of the month and year (glyphs placed by FluxCalendar)
MONTHS = ["Janvier", "Février", "Mars", "Avril", "Mai", "Juin", "Juillet", "Août", "Septembre", "Octobre",
          "Novembre", "Décembre"]


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


def background():
    """Calendrier mural (chosen 2026-10-29 among 5 proposals): hung on a nail by a string, a pink header (the month
    and year are drawn over it by FluxCalendar), perforations of the torn pages, pages piled under the last week."""
    c = frame()
    x0, x1 = item(1)[0] - 4, item(7)[0] + 19
    top, bottom = HEADER_TOP, item(45)[1] + 19
    c.line([(88, -21), (x0 + 12, top)], fill=GOLD_DARK)
    c.line([(88, -21), (x1 - 12, top)], fill=GOLD_DARK)
    c.ellipse((86, -23, 90, -19), fill=MID, outline=OUTLINE)
    for k in (3, 2, 1):  # pages piled under
        c.rrect((x0 + k, bottom - 3, x1 + k, bottom + k * 2), 2, fill=PAPER_SHADE if k % 2 else PAPER_LINE, outline=MID)
    c.rrect((x0 - 1, top - 1, x1 + 1, bottom + 1), 3, fill=OUTLINE)
    c.rrect((x0, top, x1, bottom), 2, fill=PAPER)
    c.rect((x0, top, x1, 15), fill=PINK_DARK)
    c.rect((x0, top, x1, top + 1), fill=PINK)
    c.line([(x0, 16), (x1, 16)], fill=HEADER_SHADOW)
    for x in range(x0 + 3, x1 - 1, 4):  # perforations
        c.point(x, top + 3, HEADER_SHADOW)
    weekday_letters(c, 7, colour=WHITE, weekend=GOLD_LIGHT, shadow=HEADER_SHADOW)
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


ONE = [".#.", "##.", ".#.", ".#.", ".#.", ".#.", "###"]  # the « 1 » of Minecraft's font


def with_one(im):
    """The item count is not shown for 1: the 1st of the month gets its « 1 » drawn where the game draws counts."""
    out = im.copy()
    for dy, row in enumerate(ONE):
        for dx, ch in enumerate(row):
            if ch == "#":
                out.putpixel((13 + dx, 9 + dy), (62, 62, 62, 255))  # shadow
    for dy, row in enumerate(ONE):
        for dx, ch in enumerate(row):
            if ch == "#":
                out.putpixel((12 + dx, 8 + dy), WHITE)
    return out


def count(im, n, x, y, k):
    """Item count as in game: white digits bottom right of the case, dark shadow."""
    s = str(n)
    lbl = Image.new("RGBA", (text_width(s) + 2, 7), (0, 0, 0, 0))
    text(lbl, s, lbl.width // 2, 0, colour=WHITE, shadow=(30, 30, 30, 255))
    lbl = lbl.resize((lbl.width * k, lbl.height * k), Image.NEAREST)
    im.alpha_composite(lbl, ((x + 17) * k - lbl.width, (y + 16) * k - lbl.height + k))


def label(words):
    """A word of the title as an image: QuinqueFive capitals, white, header-coloured shadow (7 px high)."""
    im = Image.new("RGBA", (text_width(words) + 1, 7), (0, 0, 0, 0))
    text(im, words, (text_width(words) + 1) // 2, 0, colour=WHITE, shadow=HEADER_SHADOW)
    return im


def preview(bg, k=3):
    """October 2026 filled in: claimed days (tick), a few missed (cross), today (29, gift), the big days to come
    (small gift), the others empty: only their number."""
    view = Image.new("RGBA", (W, INV_Y + 30 + LIFT), (58, 58, 58, 255))
    view.alpha_composite(bg.crop((0, 0, W, view.height)))
    title = label("Octobre 2026")
    view.alpha_composite(title, (88 - title.width // 2, TITLE_Y + LIFT))
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


def pack(plugins):
    """Writes the ItemsAdder pack « fluxcalendar » (background, title words, day marks) and FluxCalendar/glyphs.yml."""
    dest = os.path.join(plugins, "ItemsAdder", "contents", "fluxcalendar")
    for sub in ("textures/gui", "textures/titre", "textures/item"):
        os.makedirs(os.path.join(dest, sub), exist_ok=True)
    background().save(os.path.join(dest, "textures/gui/menu_calendrier.png"))

    def glyph(gid, path, h, y):
        return ["  %s:" % gid, "    show_in_gui: false", "    suggest_in_command: false", '    path: "%s"' % path,
                "    scale_ratio: %d" % h, "    y_position: %d" % y]

    lines = ["# Generated by plugins/fluxplugin/FluxCalendar/assets/calendrier/gen_fond.py: do not edit by hand.",
             "info:", "  namespace: fluxcalendar", "", "font_images:"]
    lines += glyph("menu_calendrier", "gui/menu_calendrier.png", H, 13 + LIFT)
    widths = {}
    words = [("mois_%d" % (i + 1), m) for i, m in enumerate(MONTHS)] + [("chiffre_%d" % n, str(n)) for n in range(10)]
    for gid, word in words:
        im = label(word)
        im.save(os.path.join(dest, "textures/titre", gid + ".png"))
        lines += glyph(gid, "titre/%s.png" % gid, im.height, 13 - TITLE_Y)
        widths[gid] = im.width
    lines += ["", "items:"]
    marks = [("jour_recupere", mark("tick"), "Jour récupéré"), ("jour_manque", mark("cross"), "Jour manqué"),
             ("jour_cadeau", mark("gift"), "Récompense du jour"), ("jour_bonus", mark("small_gift"), "Grosse récompense"),
             ("jour", Image.new("RGBA", (16, 16), (0, 0, 0, 0)), "Jour")]
    for gid, im, name in marks:
        for suffix, picture in (("", im), ("_un", with_one(im))):
            if gid == "jour" and not suffix:
                continue  # an empty day is influitems:invisible, its count shows the number
            picture.save(os.path.join(dest, "textures/item", gid + suffix + ".png"))
            lines += ["  %s:" % (gid + suffix), "    display_name: '%s'" % name, "    resource:", "      material: PAPER",
                      "      generate: true", "      textures:", "      - item/%s.png" % (gid + suffix)]
    open(os.path.join(dest, "config.yml"), "w").write("\n".join(lines) + "\n")
    data = os.path.join(plugins, "FluxCalendar")
    os.makedirs(data, exist_ok=True)
    open(os.path.join(data, "glyphs.yml"), "w").write(
        "# Generated by FluxCalendar/assets/calendrier/gen_fond.py: width in px of the title words (month, digits),\n"
        "# window x where the title is centred and space in px between the month and the year.\n"
        "centre: 88\nspace: 4\n" + "".join("%s: %d\n" % kv for kv in widths.items()))
    print("pack fluxcalendar ->", dest)


if __name__ == "__main__":
    pack(sys.argv[1])
    if len(sys.argv) > 2:
        preview(background()).save(os.path.join(sys.argv[2], "calendrier_apercu.png"))
