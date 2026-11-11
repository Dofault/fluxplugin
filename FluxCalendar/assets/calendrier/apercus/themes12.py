"""12 art directions of the calendar menu, same structure (narrowed window, plate, sheet, weekday band, 6x7 grid,
welcome ribbon). Preview only: nothing is written to the pack."""
import os, sys, colorsys, random
sys.path.insert(0, sys.argv[1])
import gen_fond as g, ui
from PIL import Image, ImageDraw, ImageFont
from fond_lib import text_width
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from variantes import narrow_frame

W, LIFT = g.W, g.LIFT
H = lambda s: ui.rgb(s)
CUT = 12
X0, Y0, X1, Y1 = g.SHEET
GREYS = {0: "outline", 55: "deep", 85: "shade", 139: "mid", 193: "fill", 198: "fill", 255: "hi"}
TABS = {(254, 255, 125): 0, (255, 213, 66): 1, (233, 134, 28): 2, (189, 89, 17): 3, (140, 51, 12): 3}


# ------------------------------------------------------------------ textures of the window fill
def tex_stone(x, y, c):
    r = (x * 7 + y * 13 + (x * y) % 5) % 11
    return shade(c, -0.10) if r == 0 else shade(c, 0.07) if r == 5 else c

def tex_scan(x, y, c):
    return shade(c, -0.25) if y % 2 else c

def tex_stars(x, y, c):
    return H("#FFE600") if (x * 31 + y * 17) % 97 == 0 else shade(c, -0.08) if (x + y) % 6 == 0 else c

def tex_dither(x, y, c):
    return shade(c, -0.06) if (x + y) % 2 else c

def tex_brushed(x, y, c):
    return shade(c, ((y * 37) % 7 - 3) * 0.025)

def tex_wood(x, y, c):
    v = (y * 3 + (x // 9) * 5) % 8
    return shade(c, -0.14) if v == 0 else shade(c, 0.06) if v == 4 else c

def tex_wave(x, y, c):          # seigaiha-like arcs, very light
    cx, cy = (x % 12) - 6, (y % 6)
    return shade(c, -0.07) if abs(cx * cx + (cy + 3) ** 2 - 25) < 3 else c

def tex_holo(x, y, c):
    h = ((x + y) % 48) / 48
    r, gg, b = colorsys.hsv_to_rgb(h, 0.18, 0.86)
    return (int(r * 255), int(gg * 255), int(b * 255), 255)

def tex_leather(x, y, c):
    r = (x * 5 + y * 11 + (x * y) % 7) % 9
    return shade(c, -0.12) if r == 0 else shade(c, 0.05) if r == 4 else c


def shade(c, k):
    if k >= 0:
        return tuple(int(c[i] + (255 - c[i]) * k) for i in range(3)) + (255,)
    return tuple(int(c[i] * (1 + k)) for i in range(3)) + (255,)


def themed_frame(t):
    c = narrow_frame(CUT)
    px = c.im.load()
    for y in range(c.im.height):
        for x in range(c.im.width):
            p = px[x, y]
            if p[3] < 255:
                continue
            if p[0] == p[1] == p[2] and p[0] in GREYS:
                role = GREYS[p[0]]
                col = t["frame"][role]
                if role == "fill" and t.get("texture"):
                    col = t["texture"](x, y, col)
                px[x, y] = col
            elif p[:3] in TABS:
                px[x, y] = t["tabs"][TABS[p[:3]]]
    return c


# ------------------------------------------------------------------ parts
def slot(c, s, t):
    fill, dark, light = t["slot"]
    x, y = g.item(s)
    x0, y0, x1, y1 = x - 1, y - 1, x + 16, y + 16
    c.rect((x0, y0, x1, y1), fill=fill)
    c.line([(x0, y0), (x1 - 1, y0)], fill=dark)
    c.line([(x0, y0), (x0, y1 - 1)], fill=dark)
    c.line([(x0 + 1, y1), (x1, y1)], fill=light)
    c.line([(x1, y0 + 1), (x1, y1)], fill=light)


def star(c, x, y, a, b):
    c.point(x, y, a)
    for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        c.point(x + dx, y + dy, b)


def band_colour(t, x):
    if callable(t["band"]):
        return t["band"](x)
    return t["band"]


def sheet(c, t):
    o = t["outline"]
    if t.get("shadow"):              # hard drop shadow (arcade, swiss)
        dx, dy, col = t["shadow"]
        c.rect((X0 + dx, Y0 + dy, X1 + dx, Y1 + dy), fill=col)
    if t.get("piles"):
        for k, colour in ((2, t["piles"][1]), (1, t["piles"][0])):
            c.rect((X0 + 1 + k, Y1 - 4 + k * 2, X1 + k, Y1 + k * 2), fill=colour, outline=o)
    if t.get("glow"):                # neon: a 1 px halo around the sheet
        c.rect((X0 - 1, Y0 - 1, X1 + 1, Y1 + 1), outline=t["glow"])
    c.rect(g.SHEET, outline=o)
    pa, hi, lo = t["paper"]
    g.bevel(c, (X0 + 1, Y0 + 1, X1 - 1, Y1 - 1), pa, hi, lo)
    if t.get("inner"):               # double border (luxury, gothic)
        c.rect((X0 + 2, g.BAND_BOTTOM + 3, X1 - 2, Y1 - 2), outline=t["inner"])
    if t.get("paper_tex"):
        for y in range(g.BAND_BOTTOM + 2, Y1):
            for x in range(X0 + 2, X1 - 1):
                col = t["paper_tex"](x, y)
                if col:
                    c.point(x, y, col)
    for x in range(X0 + 1, X1):
        c.line([(x, g.BAND_TOP), (x, g.BAND_BOTTOM)], fill=band_colour(t, x))
    bh, bl = t["band_edges"]
    c.line([(X0 + 1, g.BAND_TOP), (X1 - 1, g.BAND_TOP)], fill=bh)
    c.line([(X0 + 1, g.BAND_BOTTOM + 1), (X1 - 1, g.BAND_BOTTOM + 1)], fill=bl)
    for i, ch in enumerate(g.WEEKDAYS):
        cx = g.item(1 + i)[0] + 8
        col = t["weekend"] if i >= 5 else t["letters"]
        if t.get("letter_shadow"):
            c.text(ch, cx, g.BAND_TOP + 3, colour=col, shadow=t["letter_shadow"])
        else:
            ui.write(c.im, ch, cx, g.BAND_TOP + 3 + LIFT, col)
    for deco in t.get("decor", []):
        deco(c, t)
    for r in range(6):
        for col in range(1, 8):
            slot(c, r * 9 + col, t)


def ribbon(c, t, words="Bienvenue !"):
    r = t["ribbon"]
    y, h = g.BANNER_Y, 9
    w = text_width(words) + 14
    x0, x1 = 88 - w // 2, 88 + (w + 1) // 2
    o = r.get("outline", g.OUTLINE)
    if r.get("tails", True):
        for sign, edge in ((-1, x0), (1, x1)):
            far = edge + sign * 9
            tail = [(edge, y + 2), (far, y + 2), (far - sign * 3, y + 2 + (h + 1) // 2), (far, y + h + 2), (edge, y + h + 2)]
            c.d.polygon([(x, yy + LIFT) for x, yy in tail], fill=r["dark"], outline=o)
    if r.get("stripes"):             # hazard tails (industrial)
        for x in range(x0 - 10, x1 + 11):
            if x0 <= x <= x1:
                continue
            for yy in range(y + 1, y + h):
                c.point(x, yy, r["stripes"][((x + yy) // 3) % 2])
        c.rect((x0 - 10, y, x1 + 10, y + h), outline=o)
    c.rect((x0, y, x1, y + h), fill=r["body"], outline=o)
    c.line([(x0 + 1, y + 1), (x1 - 1, y + 1)], fill=r["light"])
    c.line([(x0 + 1, y + h - 1), (x1 - 1, y + h - 1)], fill=r["dark"])
    if r.get("spark"):
        for x in (x0 + 3, x1 - 3):
            star(c, x, y + 4, *r["spark"])
    if r.get("text_shadow"):
        c.text(words, 88, y + 2, colour=r["text"], shadow=r["text_shadow"])
    else:
        ui.write(c.im, words, 88, y + 2 + LIFT, r["text"])


# ------------------------------------------------------------------ decorations
def rings(kind):
    def draw(c, t):
        a, b = t["ring"]
        xs = [g.item(3)[0] - 1, g.item(6)[0] - 1]
        if kind == "spiral":
            xs = list(range(X0 + 6, X1 - 3, 9))
        for x in xs:
            if kind in ("rings", "spiral"):
                c.rect((x - 1, g.BAND_TOP - 3, x + 1, g.BAND_TOP + 3), fill=b)
                c.line([(x, g.BAND_TOP - 2), (x, g.BAND_TOP + 2)], fill=a)
            elif kind == "bolts":
                c.rect((x - 1, g.BAND_TOP - 3, x + 1, g.BAND_TOP - 1), fill=a, outline=b)
                c.point(x, g.BAND_TOP - 2, b)
            elif kind == "cords":
                c.line([(x, g.BAND_TOP - 4), (x, g.BAND_TOP)], fill=a)
                c.rect((x - 1, g.BAND_TOP - 1, x + 1, g.BAND_TOP + 1), fill=b)
            elif kind == "pins":
                c.point(x, g.BAND_TOP - 2, a); c.point(x, g.BAND_TOP - 1, b)
                c.rect((x - 1, g.BAND_TOP - 4, x + 1, g.BAND_TOP - 3), fill=a)
    return draw


def corner_studs(c, t):
    a, b = t["ring"]
    for x, y in ((X0 + 3, Y1 - 3), (X1 - 3, Y1 - 3)):
        c.rect((x - 1, y - 1, x + 1, y + 1), fill=b)
        c.point(x, y, a)


def gems(c, t):
    for x in (X0 + 5, X1 - 5):
        y = g.BAND_TOP + 5
        c.point(x, y - 2, t["gem"][0])
        c.line([(x - 1, y - 1), (x + 1, y - 1)], fill=t["gem"][0])
        c.line([(x - 2, y), (x + 2, y)], fill=t["gem"][1])
        c.line([(x - 1, y + 1), (x + 1, y + 1)], fill=t["gem"][2])
        c.point(x, y + 2, t["gem"][2])


def band_stars(c, t):
    for x in (X0 + 4, X1 - 4):
        star(c, x, g.BAND_TOP + 5, *t["stars"])


def gold_brackets(c, t):
    col = t["inner"]
    for x, y, sx, sy in ((X0 - 2, Y0 - 2, 1, 1), (X1 + 2, Y0 - 2, -1, 1), (X0 - 2, Y1 + 2, 1, -1), (X1 + 2, Y1 + 2, -1, -1)):
        c.line([(x, y), (x + 5 * sx, y)], fill=col)
        c.line([(x, y), (x, y + 5 * sy)], fill=col)


def leaves(c, t):
    lf, ld = t["leaf"]
    def leaf(x, y, d):
        for k in range(4):
            c.point(x + d * k, y - k // 2, lf)
            c.point(x + d * k, y - k // 2 + 1, ld)
        c.point(x + d * 1, y - 1, lf)
    for x, d in ((X0 + 3, 1), (X1 - 3, -1)):
        leaf(x, g.BAND_TOP + 6, d)
    for x, d in ((X0 - 1, 1), (X1 + 1, -1)):       # vines down the sides of the sheet
        for y in range(g.BAND_BOTTOM + 6, Y1 - 4, 14):
            c.point(x, y, ld); c.point(x + d, y + 1, lf); c.point(x + d, y + 2, lf)


def hazard(c, t):
    for x in range(X0 + 1, X0 + 8):
        for y in range(g.BAND_TOP + 1, g.BAND_BOTTOM + 1):
            if ((x + y) // 3) % 2:
                c.point(x, y, H("#101214"))
    for x in range(X1 - 7, X1):
        for y in range(g.BAND_TOP + 1, g.BAND_BOTTOM + 1):
            if ((x + y) // 3) % 2:
                c.point(x, y, H("#101214"))


def rivets(c, t):
    for x in (X0 + 2, X1 - 2):
        for y in range(g.BAND_BOTTOM + 4, Y1 - 1, 18):
            c.point(x, y, H("#D6DCE1")); c.point(x + 1, y + 1, H("#3C4146"))


def sun(c, t):
    cx, cy = X1 - 4, g.BAND_TOP + 5
    for dx in range(-3, 4):
        for dy in range(-3, 4):
            if dx * dx + dy * dy <= 6:
                c.point(cx + dx, cy + dy, H("#F4ECDA"))
    for dx in range(-2, 3):
        for dy in range(-2, 3):
            if dx * dx + dy * dy <= 2:
                c.point(cx + dx, cy + dy, H("#D9381E"))
    for k, x in enumerate(range(X0 + 2, X0 + 7, 4)):      # gold waves on the left end
        c.line([(x, g.BAND_TOP + 7), (x + 1, g.BAND_TOP + 6), (x + 2, g.BAND_TOP + 7)], fill=H("#E3B23C"))


def neon_corners(c, t):
    for x, y in ((X0, Y0), (X1, Y0), (X0, Y1), (X1, Y1)):
        c.point(x, y, H("#FF4FD8"))


def filigree(c, t):
    col = t["ring"][0]
    for x, y, sx, sy in ((X0 + 3, g.BAND_BOTTOM + 4, 1, 1), (X1 - 3, g.BAND_BOTTOM + 4, -1, 1),
                         (X0 + 3, Y1 - 3, 1, -1), (X1 - 3, Y1 - 3, -1, -1)):
        c.point(x, y, col); c.point(x + sx, y, col); c.point(x, y + sy, col); c.point(x + 2 * sx, y + sy, col)


def stains(x, y):
    r = (x * 73 + y * 151 + (x * y) % 13) % 211
    return H("#C2A974") if r < 3 else None


def holo_band(x):
    h = 0.48 + 0.35 * (x - X0) / (X1 - X0)
    r, gg, b = colorsys.hsv_to_rgb(h % 1, 0.55, 0.92)
    return (int(r * 255), int(gg * 255), int(b * 255), 255)


def holo_sheen(x, y):
    return H("#EEF2FF") if (x - y) % 23 == 0 else None


# ------------------------------------------------------------------ the 12 themes
def frame(o, d, s, m, f, h):
    return dict(outline=H(o), deep=H(d), shade=H(s), mid=H(m), fill=H(f), hi=H(h))

def tabs(*c):
    return [H(x) for x in c]

THEMES = [
    ("01 Pixel Art RPG Fantasy", dict(
        frame=frame("#1E1814", "#2E2620", "#4F4A45", "#6E675E", "#8A8379", "#B9B2A6"), texture=tex_stone,
        tabs=tabs("#F0C850", "#9B1B30", "#6E1020", "#3B0A12"), ink=H("#1E1814"),
        outline=H("#3B2412"), paper=(H("#E8D5A3"), H("#F7EBC8"), H("#A88A4E")), piles=[H("#D2BC88"), H("#B49A62")],
        band=H("#9B1B30"), band_edges=(H("#D0455A"), H("#4E0B16")), letters=H("#F0C850"), weekend=H("#FFE9A0"),
        letter_shadow=H("#4E0B16"), ring=(H("#C9C2B5"), H("#3A342F")), gem=(H("#FFE9A0"), H("#F0C850"), H("#8A5A14")),
        decor=[rings("bolts"), gems, corner_studs], slot=(H("#5A534C"), H("#3A342F"), H("#8F877C")),
        ribbon=dict(body=H("#9B1B30"), light=H("#D0455A"), dark=H("#4E0B16"), text=H("#FFE9A0"),
                    text_shadow=H("#4E0B16"), spark=(H("#FFE9A0"), H("#F0C850"))))),
    ("02 Cyberpunk Neon", dict(
        frame=frame("#000000", "#05040A", "#0E0B18", "#14111F", "#1A1726", "#3A2F6A"), texture=tex_scan,
        tabs=tabs("#B8FFFF", "#00E5FF", "#FF4FD8", "#7A00FF"), ink=H("#5FF6FF"),
        outline=H("#00E5FF"), glow=H("#7A00FF"), paper=(H("#14121E"), H("#2A2440"), H("#08070D")),
        band=H("#5A00D6"), band_edges=(H("#C27BFF"), H("#2A0055")), letters=H("#5FF6FF"), weekend=H("#FF4FD8"),
        letter_shadow=H("#12002E"), ring=(H("#FF4FD8"), H("#2A0055")), decor=[rings("pins"), neon_corners],
        slot=(H("#221E36"), H("#0B0914"), H("#6B4DFF")),
        ribbon=dict(body=H("#0B0914"), light=H("#FF4FD8"), dark=H("#7A00FF"), outline=H("#FF4FD8"), text=H("#5FF6FF"),
                    text_shadow=H("#12002E"), spark=(H("#FFFFFF"), H("#00E5FF"))))),
    ("03 Retro Arcade 90s", dict(
        frame=frame("#000000", "#06125A", "#0A1F8F", "#1530C8", "#1E40FF", "#7F98FF"), texture=tex_stars,
        tabs=tabs("#FFF6A0", "#FFE600", "#E81C1C", "#7A0000"), ink=H("#FFE600"),
        outline=H("#000000"), shadow=(3, 3, H("#000000")), paper=(H("#111111"), H("#FFE600"), H("#B39F00")),
        band=H("#E81C1C"), band_edges=(H("#FF7A7A"), H("#7A0000")), letters=H("#FFE600"), weekend=H("#FFFFFF"),
        letter_shadow=H("#000000"), ring=(H("#FFFFFF"), H("#000000")), decor=[rings("pins")],
        slot=(H("#2C2C2C"), H("#000000"), H("#5C5C5C")),
        ribbon=dict(body=H("#FFE600"), light=H("#FFF6A0"), dark=H("#E81C1C"), text=H("#000000"),
                    spark=(H("#FFFFFF"), H("#E81C1C"))))),
    ("04 Monochrome Pixel", dict(
        frame=frame("#0E0E0E", "#262626", "#3A3A3A", "#6A6A6A", "#9A9A9A", "#E6E6E6"), texture=tex_dither,
        tabs=tabs("#E6E6E6", "#F0A800", "#6A6A6A", "#262626"), ink=H("#1A1A1A"),
        outline=H("#0E0E0E"), paper=(H("#E8E6E1"), H("#FFFFFF"), H("#8C8C8C")), piles=[H("#BDBDBD"), H("#8C8C8C")],
        inner=H("#B5B3AE"), band=H("#2E2E2E"), band_edges=(H("#6A6A6A"), H("#0E0E0E")), letters=H("#E8E6E1"),
        weekend=H("#F0A800"), letter_shadow=H("#0E0E0E"), ring=(H("#D0D0D0"), H("#3A3A3A")), decor=[rings("rings")],
        slot=(H("#8B8B8B"), H("#373737"), H("#FFFFFF")),
        ribbon=dict(body=H("#2E2E2E"), light=H("#6A6A6A"), dark=H("#0E0E0E"), text=H("#E8E6E1"),
                    text_shadow=H("#0E0E0E"), spark=(H("#FFE9A0"), H("#F0A800"))))),
    ("05 Luxury Gold", dict(
        frame=frame("#000000", "#050505", "#0B0B0B", "#111010", "#161514", "#3A352C"),
        tabs=tabs("#F2E2B5", "#C9A961", "#8A6F35", "#4A3A18"), ink=H("#E6CF9A"),
        outline=H("#C9A961"), inner=H("#C9A961"), paper=(H("#F2EBDD"), H("#FFFFFF"), H("#B8A57A")),
        piles=[H("#D9CFB8"), H("#B8A57A")], band=H("#0E0E0E"), band_edges=(H("#C9A961"), H("#6E5A2E")),
        letters=H("#E6CF9A"), weekend=H("#FFFFFF"), letter_shadow=H("#000000"),
        ring=(H("#F2E2B5"), H("#8A6F35")), decor=[rings("rings"), gold_brackets],
        slot=(H("#2A2622"), H("#121110"), H("#6E5A2E")),
        ribbon=dict(body=H("#0E0E0E"), light=H("#C9A961"), dark=H("#000000"), outline=H("#C9A961"),
                    text=H("#E6CF9A"), spark=(H("#FFFFFF"), H("#C9A961"))))),
    ("06 Minimalist Swiss Design", dict(
        frame=frame("#1C1C1C", "#9E9E9E", "#BDBDBD", "#D0CEC9", "#E9E7E2", "#FFFFFF"),
        tabs=tabs("#FF5A63", "#E30613", "#A0040D", "#5E0207"), ink=H("#1C1C1C"),
        outline=H("#1C1C1C"), shadow=(2, 2, H("#BDBDBD")), paper=(H("#FAFAF8"), H("#FAFAF8"), H("#FAFAF8")),
        band=lambda x: H("#E30613") if x < X0 + 10 else H("#2B2B2B"), band_edges=(H("#2B2B2B"), H("#1C1C1C")),
        letters=H("#FFFFFF"), weekend=H("#E30613"), decor=[],
        slot=(H("#8E8E8E"), H("#5E5E5E"), H("#8E8E8E")),
        ribbon=dict(body=H("#E30613"), light=H("#E30613"), dark=H("#A0040D"), outline=H("#1C1C1C"), text=H("#FFFFFF"),
                    tails=False))),
    ("07 Industrial Metallic", dict(
        frame=frame("#101214", "#22262A", "#3C4146", "#5E656B", "#7D858C", "#B5BDC4"), texture=tex_brushed,
        tabs=tabs("#FFB060", "#FF7A00", "#B35500", "#5E2C00"), ink=H("#101214"),
        outline=H("#101214"), paper=(H("#A9B0B6"), H("#D6DCE1"), H("#5E656B")),
        band=H("#FF7A00"), band_edges=(H("#FFB060"), H("#7A3A00")), letters=H("#FFFFFF"), weekend=H("#101214"),
        letter_shadow=H("#7A3A00"), ring=(H("#D6DCE1"), H("#22262A")), decor=[hazard, rings("bolts"), rivets],
        slot=(H("#4A5056"), H("#22262A"), H("#8A9298")),
        ribbon=dict(body=H("#3C4146"), light=H("#7D858C"), dark=H("#22262A"), text=H("#FFFFFF"),
                    text_shadow=H("#101214"), tails=False, stripes=(H("#FF7A00"), H("#101214"))))),
    ("08 Soft Pastel", dict(
        frame=frame("#6E6680", "#8E86A3", "#B8B0C8", "#D3CCE2", "#E9E4F2", "#FFFFFF"),
        tabs=tabs("#FFDCE4", "#F2B8C6", "#8EC9F0", "#5A8DB5"), ink=H("#6E6680"),
        outline=H("#8E7FA6"), paper=(H("#FFF8EE"), H("#FFFFFF"), H("#D9C9D6")), piles=[H("#DCD3F0"), H("#BFE0F5")],
        band=H("#F2B8C6"), band_edges=(H("#FFDCE4"), H("#C98A9C")), letters=H("#FFFFFF"), weekend=H("#5A8DB5"),
        letter_shadow=H("#C98A9C"), ring=(H("#FFFFFF"), H("#B8B0C8")), stars=(H("#FFFFFF"), H("#8EC9F0")),
        decor=[rings("rings"), band_stars], slot=(H("#A79CC0"), H("#8679A3"), H("#E6E0F2")),
        ribbon=dict(body=H("#8EC9F0"), light=H("#BFE0F5"), dark=H("#5A8DB5"), outline=H("#6E6680"), text=H("#FFFFFF"),
                    text_shadow=H("#5A8DB5"), spark=(H("#FFFFFF"), H("#F2B8C6"))))),
    ("09 Botanical Nature", dict(
        frame=frame("#2A1D10", "#3E2C18", "#5C4227", "#76583A", "#8B6B45", "#B08B5E"), texture=tex_wood,
        tabs=tabs("#C8E0A8", "#5E8F55", "#2F5D34", "#1A3320"), ink=H("#2A1D10"),
        outline=H("#2F3D24"), paper=(H("#EADFC8"), H("#F7F0E0"), H("#A89878")), piles=[H("#D8CCAE"), H("#BBAE8C")],
        band=H("#2F5D34"), band_edges=(H("#5E8F55"), H("#1A3320")), letters=H("#F3EBD6"), weekend=H("#C8E0A8"),
        letter_shadow=H("#1A3320"), ring=(H("#B08B5E"), H("#3E2C18")), leaf=(H("#7FB86A"), H("#2F5D34")),
        decor=[rings("cords"), leaves], slot=(H("#7F8F74"), H("#55634C"), H("#B5C2A8")),
        ribbon=dict(body=H("#2F5D34"), light=H("#5E8F55"), dark=H("#1A3320"), text=H("#F3EBD6"),
                    text_shadow=H("#1A3320"), spark=(H("#F3EBD6"), H("#7FB86A"))))),
    ("10 Japanese Retro", dict(
        frame=frame("#120F0E", "#3A3230", "#B8A88A", "#D4C6A8", "#E9DEC6", "#FBF6EA"), texture=tex_wave,
        tabs=tabs("#F06A4E", "#D9381E", "#7A1A0C", "#120F0E"), ink=H("#120F0E"),
        outline=H("#120F0E"), paper=(H("#F4ECDA"), H("#FFFFFF"), H("#C9B994")),
        band=H("#D9381E"), band_edges=(H("#F06A4E"), H("#7A1A0C")), letters=H("#F4ECDA"), weekend=H("#E3B23C"),
        letter_shadow=H("#7A1A0C"), ring=(H("#D9381E"), H("#120F0E")), decor=[rings("cords"), sun],
        slot=(H("#5E5652"), H("#2E2826"), H("#9A918A")),
        ribbon=dict(body=H("#120F0E"), light=H("#D9381E"), dark=H("#3A3230"), outline=H("#D9381E"),
                    text=H("#E3B23C"), spark=(H("#F4ECDA"), H("#D9381E"))))),
    ("11 Holographic Future", dict(
        frame=frame("#1A1830", "#33365A", "#4B4E6E", "#8A93B8", "#A9B4D0", "#E9EEFF"), texture=tex_holo,
        tabs=tabs("#E9EEFF", "#7FE8FF", "#B48CFF", "#4B4E6E"), ink=H("#33365A"),
        outline=H("#6A5AE0"), glow=H("#B8C6FF"), paper=(H("#D7DDF0"), H("#F4F6FF"), H("#9AA3C8")),
        paper_tex=holo_sheen, band=holo_band, band_edges=(H("#E9EEFF"), H("#3A2A7A")), letters=H("#FFFFFF"),
        weekend=H("#FFF4A8"), letter_shadow=H("#3A2A7A"), ring=(H("#E9EEFF"), H("#4B4E6E")),
        stars=(H("#FFFFFF"), H("#7FE8FF")), decor=[rings("rings"), band_stars],
        slot=(H("#5B5F86"), H("#33365A"), H("#B8C6FF")),
        ribbon=dict(body=H("#7A6AF0"), light=H("#B8C6FF"), dark=H("#3A2A7A"), outline=H("#33365A"), text=H("#FFFFFF"),
                    text_shadow=H("#3A2A7A"), spark=(H("#FFFFFF"), H("#7FE8FF"))))),
    ("12 Dark Academia Gothic", dict(
        frame=frame("#0B0A08", "#160F0B", "#2A1F17", "#33251B", "#3E2B20", "#6E4A30"), texture=tex_leather,
        tabs=tabs("#D08A4A", "#8E2A3C", "#5E1424", "#2A0810"), ink=H("#D08A4A"),
        outline=H("#1B120C"), inner=H("#8F7647"), paper=(H("#D9C49A"), H("#EEDDB5"), H("#8F7647")),
        paper_tex=stains, piles=[H("#BFA878"), H("#9C8558")], band=H("#5E1424"), band_edges=(H("#8E2A3C"), H("#2A0810")),
        letters=H("#D08A4A"), weekend=H("#EEDDB5"), letter_shadow=H("#2A0810"), ring=(H("#D08A4A"), H("#5A3418")),
        decor=[rings("rings"), filigree], slot=(H("#2F3B32"), H("#18201A"), H("#5A6B5C")),
        ribbon=dict(body=H("#24402F"), light=H("#3F6B4A"), dark=H("#10201A"), outline=H("#D08A4A"), text=H("#EEDDB5"),
                    text_shadow=H("#10201A"), spark=(H("#EEDDB5"), H("#D08A4A"))))),
]


def render(t):
    c = themed_frame(t)
    sheet(c, t)
    ribbon(c, t)
    return c.im


def preview(bg, t, k):
    view = Image.new("RGBA", (W, g.BOTTOM + LIFT + 8), (0, 0, 0, 0))
    view.alpha_composite(bg.crop((0, 0, W, view.height)))
    title = Image.new("RGBA", (text_width("OCTOBRE 2026") + 1, 6), (0, 0, 0, 0))
    ui.write(title, "Octobre 2026", (title.width) // 2, 0, t["ink"])
    view.alpha_composite(title, (88 - title.width // 2, g.TITLE_Y + LIFT))
    view = view.resize((W * k, view.height * k), Image.NEAREST)
    today, missed = 29, {6, 13, 14, 22}
    icons = {"ok": g.mark("tick"), "ko": g.mark("cross"), "today": g.mark("gift"), "big": g.mark("small_gift")}
    for d, s in g.day_slots(2026, 10).items():
        x, y = g.item(s)
        y += LIFT
        ic = icons["ko" if d in missed else "ok"] if d < today else icons["today"] if d == today else \
            (icons["big"] if d % 7 == 0 or d == 31 else None)
        if ic is not None:
            view.alpha_composite(ic.resize((16 * k, 16 * k), Image.NEAREST), (x * k, y * k))
        g.count(view, d, x, y, k)
    return view


out = sys.argv[2]
os.makedirs(out, exist_ok=True)
shots = []
for name, t in THEMES:
    bg = render(t)
    p3 = preview(bg, t, 3)
    p3.save(os.path.join(out, name.split()[0] + "_" + "_".join(name.split()[1:]).lower() + ".png"))
    shots.append((name, preview(bg, t, 2)))
pw, ph = shots[0][1].size
cols, gap, lab = 4, 24, 26
board = Image.new("RGBA", (cols * pw + (cols + 1) * gap, 3 * (ph + lab) + 4 * gap), (44, 44, 48, 255))
d = ImageDraw.Draw(board)
try:
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 16)
except OSError:
    font = ImageFont.load_default()
for i, (name, p) in enumerate(shots):
    x = gap + (i % cols) * (pw + gap)
    y = gap + (i // cols) * (ph + lab + gap)
    d.text((x, y), name, fill=(235, 235, 235, 255), font=font)
    board.alpha_composite(p, (x, y + lab))
board.save(os.path.join(out, "planche_12.png"))
print("ok", board.size)
