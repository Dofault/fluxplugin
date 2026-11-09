#!/usr/bin/env python3
"""Calendar menu (/calendrier, FluxCalendar): a real monthly calendar on the vanilla theme of Flux (2026-11-10).

Layout (the grey window starts LIFT px above the chest so the weekday band fits between its plate and the weeks):
  plate              the month and year, engraved like every vanilla title (glyphs placed by FluxCalendar)
  calendar sheet     a red band with the weekday letters L M M J V S D, binder rings, pages piled under it
  chest rows 0 to 5  the weeks, Monday to Sunday in columns 1 to 7, one vanilla slot per date (the day's number is
                     the item count, white on grey as in any inventory); columns 0 and 8 are margins
  nothing else: no back button, no info icon, the hidden inventory is not drawn (decision of 2026-11-10)

    <venv with Pillow>/python3 gen_fond.py <plugins dir of the server> [preview dir]
Writes the ItemsAdder pack « fluxcalendar » (background menu_calendrier, title words mois_<1..12> and chiffre_<0..9>,
day marks jour_recupere / jour_manque / jour_cadeau / jour_bonus, each also with a drawn « 1 » for the 1st of the
month: the game shows no count of 1, jour_un for an empty 1st) and plugins/FluxCalendar/glyphs.yml, then /iazip.
The preview shows October 2026 filled in.
"""
import calendar
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
FLUX = os.path.join(HERE, "..", "..", "..", "Flux", "assets")
sys.path.insert(0, FLUX)
sys.path.insert(0, os.path.join(FLUX, "vanilla"))
from fond_lib import text, text_width  # noqa: E402
import ui  # noqa: E402

W, H = 176, 221
LIFT = 14                     # the window starts this far above the chest (room for the weekday band)
WEEKDAYS = "LMMJVSD"
WHITE = (255, 255, 255, 255)
OUTLINE = (0, 0, 0, 255)
INK = ui.INK                  # engraved text of the plate
RED, RED_DARK, RED_LIGHT = ui.rgb("#C0392B"), ui.rgb("#7E241B"), ui.rgb("#E0645A")
GOLD, GOLD_DARK, GOLD_LIGHT = ui.rgb("#F0A800"), ui.rgb("#8A5A14"), ui.rgb("#FFD94D")
GREEN, GREEN_DARK = ui.rgb("#5DC73A"), ui.rgb("#1F5A16")
CROSS, CROSS_DARK = ui.rgb("#FF5C5C"), ui.rgb("#6E1414")
BLUE, BLUE_LIGHT = ui.rgb("#3D7FB8"), ui.rgb("#7FB2DE")
PAPER, PAPER_HI, PAPER_LO = ui.rgb("#EDEDED"), WHITE, ui.rgb("#9A9A9A")
PILE = [ui.rgb("#D6D6D6"), ui.rgb("#BDBDBD")]
RING, RING_DARK = ui.rgb("#D0D0D0"), ui.rgb("#555555")
TITLE_Y = 5 - LIFT            # window y of the month and year: on the plate (glyphs placed by FluxCalendar)
MONTHS = ["Janvier", "Février", "Mars", "Avril", "Mai", "Juin", "Juillet", "Août", "Septembre", "Octobre",
          "Novembre", "Décembre"]
BAND_TOP, BAND_BOTTOM = 3, 13 # window y of the weekday band
GENERIC = os.path.join(FLUX, "vanilla", "menu_vanilla.png")


def item(slot):
    """Window position of the item of a chest slot."""
    return 8 + 18 * (slot % 9), 18 + 18 * (slot // 9)


def day_slots(year, month):
    """Slot of each day of the month: weeks on rows 0 to 5, Monday in column 1."""
    first, days = calendar.monthrange(year, month)
    return {d: ((first + d - 1) // 7) * 9 + 1 + (first + d - 1) % 7 for d in range(1, days + 1)}


SHEET = (item(1)[0] - 5, BAND_TOP - 1, item(7)[0] + 20, item(45)[1] + 20)   # outline box of the sheet
BOTTOM = SHEET[3] + 4 + 7     # last window row: the piled pages and a grey margin


class Canvas:
    """Draws in window coordinates on an image starting LIFT px above the window."""

    def __init__(self, im):
        self.im = im
        self.d = ImageDraw.Draw(im)

    def rect(self, box, **kw):
        x0, y0, x1, y1 = box
        self.d.rectangle((x0, y0 + LIFT, x1, y1 + LIFT), **kw)

    def line(self, pts, **kw):
        self.d.line([(x, y + LIFT) for x, y in pts], **kw)

    def point(self, x, y, colour):
        if 0 <= x < W and 0 <= y + LIFT < H:
            self.im.putpixel((x, y + LIFT), colour)

    def text(self, s, cx, y, colour=WHITE, shadow=None):
        text(self.im, s, cx, y + LIFT, colour=colour, shadow=shadow)

    def slot(self, slot):
        x, y = item(slot)
        x0, y0, x1, y1 = x - 1, y - 1 + LIFT, x + 16, y + 16 + LIFT
        self.d.rectangle((x0, y0, x1, y1), fill=ui.SLOT)
        self.d.line((x0, y0, x1 - 1, y0), fill=ui.SLOT_DARK)
        self.d.line((x0, y0, x0, y1 - 1), fill=ui.SLOT_DARK)
        self.d.line((x0 + 1, y1, x1, y1), fill=WHITE)
        self.d.line((x1, y0 + 1, x1, y1), fill=WHITE)


def frame():
    """The vanilla window of the theme, from LIFT px above the chest down to BOTTOM (its 3 bottom rows kept)."""
    full = ui.window(GENERIC)
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    last = BOTTOM + LIFT                     # image row of the window's last pixel row
    im.paste(full.crop((0, 0, W, last + 1 - 3)), (0, 0))
    im.paste(full.crop((0, full.height - 3, W, full.height)), (0, last - 2))
    return Canvas(im)


def bevel(c, box, fill, hi, lo):
    x0, y0, x1, y1 = box
    c.rect(box, fill=fill)
    c.line([(x0, y0), (x1 - 1, y0)], fill=hi)
    c.line([(x0, y0), (x0, y1 - 1)], fill=hi)
    c.line([(x0 + 1, y1), (x1, y1)], fill=lo)
    c.line([(x1, y0 + 1), (x1, y1)], fill=lo)


def ring(c, x):
    """A binder ring over the top of the band: a small grey loop going through the sheet."""
    c.rect((x - 1, BAND_TOP - 3, x + 1, BAND_TOP + 3), fill=RING_DARK)
    c.line([(x, BAND_TOP - 2), (x, BAND_TOP + 2)], fill=RING)
    c.point(x, BAND_TOP + 4, RED_DARK)


def background():
    """Wall calendar on the grey window: paper sheet with a 1 px black outline, red weekday band, binder rings,
    two pages piled under it, a vanilla slot per date."""
    c = frame()
    x0, y0, x1, y1 = SHEET
    for k, colour in ((2, PILE[1]), (1, PILE[0])):          # pages piled under, offset down and right
        c.rect((x0 + 1 + k, y1 - 4 + k * 2, x1 + k, y1 + k * 2), fill=colour, outline=OUTLINE)
    c.rect(SHEET, outline=OUTLINE)
    bevel(c, (x0 + 1, y0 + 1, x1 - 1, y1 - 1), PAPER, PAPER_HI, PAPER_LO)
    c.rect((x0 + 1, BAND_TOP, x1 - 1, BAND_BOTTOM), fill=RED)
    c.line([(x0 + 1, BAND_TOP), (x1 - 1, BAND_TOP)], fill=RED_LIGHT)
    c.line([(x0 + 1, BAND_BOTTOM + 1), (x1 - 1, BAND_BOTTOM + 1)], fill=RED_DARK)
    for i, ch in enumerate(WEEKDAYS):
        c.text(ch, item(1 + i)[0] + 8, BAND_TOP + 3, colour=GOLD_LIGHT if i >= 5 else WHITE, shadow=RED_DARK)
    for x in (item(3)[0] - 1, item(6)[0] - 1):   # in the gaps between the letters, symmetric about the centre
        ring(c, x)
    for r in range(6):
        for col in range(1, 8):
            c.slot(r * 9 + col)
    return c.im


# ---------------------------------------------------------------- day marks, title words, preview

def mark(kind):
    """The day marks (16 x 16, readable on a grey vanilla slot): tick (claimed), cross (missed), red gift with a gold
    ribbon (today), small blue gift (a big reward to come)."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if kind == "tick":
        for w, col in ((3, GREEN_DARK), (1, GREEN)):
            d.line([(3, 8), (6, 11), (12, 4)], fill=col, width=w)
    elif kind == "cross":
        for w, col in ((3, CROSS_DARK), (1, CROSS)):
            d.line([(4, 4), (11, 11)], fill=col, width=w)
            d.line([(11, 4), (4, 11)], fill=col, width=w)
    else:
        big = kind == "gift"
        box = (2, 6, 13, 14) if big else (4, 8, 11, 14)
        body, lid = (RED, RED_LIGHT) if big else (BLUE, BLUE_LIGHT)
        d.rectangle(box, fill=body, outline=OUTLINE)
        d.rectangle((box[0], box[1], box[2], box[1] + 2), fill=lid, outline=OUTLINE)
        cx = (box[0] + box[2]) // 2
        d.line([(cx, box[1]), (cx, box[3] - 1)], fill=GOLD_LIGHT)
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
    """A word of the title as an image: QuinqueFive capitals engraved on the plate (ink, no shadow, 5 px high)."""
    im = Image.new("RGBA", (text_width(words) + 1, 6), (0, 0, 0, 0))
    ui.write(im, words, (text_width(words) + 1) // 2, 0, INK)
    return im


def preview(bg, k=3):
    """October 2026 filled in: claimed days (tick), a few missed (cross), today (29, gift), the big days to come
    (small gift), the others empty: only their number."""
    view = Image.new("RGBA", (W, BOTTOM + LIFT + 8), (58, 58, 58, 255))
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
        # ItemsAdder wants y_position <= image height: transparent margin under the word, which stays on top
        padded = Image.new("RGBA", (im.width, max(im.height, 13 - TITLE_Y)), (0, 0, 0, 0))
        padded.alpha_composite(im)
        padded.save(os.path.join(dest, "textures/titre", gid + ".png"))
        lines += glyph(gid, "titre/%s.png" % gid, padded.height, 13 - TITLE_Y)
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
