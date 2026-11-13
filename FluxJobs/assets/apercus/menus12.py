"""12 universal art directions for the Flux menus, shown on the job menu (same layout as gen_ui.bg_metiers).
Every component is redrawn per direction: window, plate, panels, slots (normal / selected / empty), buttons, field,
progress bar, labels, decor. Preview only."""
import math, os, random, sys
V = "/home/influencraft/plugins/fluxplugin/Flux/assets/vanilla"
sys.path.insert(0, V); sys.path.insert(0, os.path.dirname(V))
import gen_ui, ui, fond_lib
from PIL import Image, ImageDraw, ImageFont, ImageFilter

MC = sys.argv[1] + "/assets/minecraft/textures/"
OUT = sys.argv[2]
IA = "/home/influencraft/serveurs/04_labs/plugins/ItemsAdder/contents/"
W, HH = 176, 221
K = 3


def C(h, a=255):
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


def tone(c, k):
    return mix(c, (255, 255, 255, 255), k) if k >= 0 else mix(c, (0, 0, 0, 255), -k)


# ---------------------------------------------------------------- layout of the job menu
def sbox(case):
    x, y = ui.objet(case)
    return x - 1, y - 1, x + 16, y + 16


PETS = [9, 18, 27, 36]
GRID = [15, 16, 17, 24, 25, 26, 33, 34, 35, 42, 43, 44]
ACTIONS = list(range(54, 81))
PLATE = (38, 0, 137, 13)
BODY = (0, 7, 175, 220)
FIELD = (26, 19, 128, 32)
PET_BOX, GRID_BOX = gen_ui.span(PETS), gen_ui.span(GRID)
PORTRAIT = (33, 38, 98, 103)
BAR = (6, 109, 169, 120)
LABEL_Y = 130
LABEL_W = fond_lib.text_width("ACTIONS RÉMUNÉRÉES")
BUTTONS = [(0, "back", "a"), (7, "check", "b"), (8, "info", "c")]   # a, b, c: the theme's three button colours


def text(im, s, x, y, col, shadow=None, centre=False):
    ui.write(im, s, x, y, col, shadow=shadow, left=None if centre else x)


def big_text(im, s, cx, y, col, outline=None, shadow=None, k=2):
    """Menu font scaled k times (massive titles): optional 1 px outline and offset shadow."""
    w = fond_lib.text_width(s) + 2
    small = Image.new("RGBA", (w, 7), (0, 0, 0, 0))
    ui.write(small, s, w // 2, 0, col)
    big = small.resize((w * k, 7 * k), Image.NEAREST)
    x0, y0 = cx - big.width // 2, y
    if shadow:
        sh = Image.new("RGBA", big.size, shadow[1]); sh.putalpha(big.getchannel("A"))
        im.alpha_composite(sh, (x0 + shadow[0], y0 + shadow[0]))
    if outline:
        ol = Image.new("RGBA", big.size, outline); ol.putalpha(big.getchannel("A"))
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (1, 1), (-1, 1), (1, -1)):
            im.alpha_composite(ol, (x0 + dx, y0 + dy))
    im.alpha_composite(big, (x0, y0))


def icon(im, name, box, col, k=1, shadow=None):
    rows = gen_ui.ICONS[name]
    x0 = (box[0] + box[2]) // 2 - 4 * k + 1
    y0 = (box[1] + box[3]) // 2 - 3 * k
    for y, r in enumerate(rows):
        for x, ch in enumerate(r):
            if ch == "#":
                for i in range(k):
                    for j in range(k):
                        if shadow:
                            im.putpixel((x0 + x * k + i + 1, y0 + y * k + j + 1), shadow)
                        im.putpixel((x0 + x * k + i, y0 + y * k + j), col)


def gradient(im, box, top, bottom, horizontal=False):
    d = ImageDraw.Draw(im)
    x0, y0, x1, y1 = box
    n = (x1 - x0) if horizontal else (y1 - y0)
    for i in range(n + 1):
        c = mix(top, bottom, i / max(1, n))
        if horizontal:
            d.line([(x0 + i, y0), (x0 + i, y1)], fill=c)
        else:
            d.line([(x0, y0 + i), (x1, y0 + i)], fill=c)


def multi_gradient(stops, t):
    for (a, ca), (b, cb) in zip(stops, stops[1:]):
        if a <= t <= b:
            return mix(ca, cb, (t - a) / max(1e-6, b - a))
    return stops[-1][1]


def layer(im, draw_fn):
    """Draw with alpha on a separate layer, then composite (translucent fills)."""
    lay = Image.new("RGBA", im.size, (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(lay), lay)
    im.alpha_composite(lay)


def hard_shadow(d, box, off, col, radius=0):
    x0, y0, x1, y1 = box
    if radius:
        d.rounded_rectangle((x0 + off, y0 + off, x1 + off, y1 + off), radius, fill=col)
    else:
        d.rectangle((x0 + off, y0 + off, x1 + off, y1 + off), fill=col)


def soft_shadow(im, box, off, col, blur=2, radius=0):
    lay = Image.new("RGBA", im.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    x0, y0, x1, y1 = box
    d.rounded_rectangle((x0 + off, y0 + off, x1 + off, y1 + off), radius, fill=col)
    im.alpha_composite(lay.filter(ImageFilter.GaussianBlur(blur)))


def jitter_poly(box, amp, step, rnd):
    """Polygon of a box with irregular edges (paper, cut-outs, paint)."""
    x0, y0, x1, y1 = box
    pts = []
    for x in range(x0, x1, step):
        pts.append((x, y0 + rnd.randint(-amp, amp)))
    for y in range(y0, y1, step):
        pts.append((x1 + rnd.randint(-amp, amp), y))
    for x in range(x1, x0, -step):
        pts.append((x, y1 + rnd.randint(-amp, amp)))
    for y in range(y1, y0, -step):
        pts.append((x0 + rnd.randint(-amp, amp), y))
    return pts


# ---------------------------------------------------------------- base theme
class Theme:
    name = ""
    bar_ratio = 0.62
    def __init__(self):
        self.r = random.Random(7)
    # each method draws one component; defaults are overridden by the directions
    def bg(self, im, d): pass
    def plate(self, im, d): pass
    def panel(self, im, d, box, kind): pass
    def portrait(self, im, d, box): self.panel(im, d, box, "frame")
    def slot(self, im, d, box, state): pass
    def button(self, im, d, box, name, which): pass
    def field_text(self, im): pass
    def bar(self, im, d, box, ratio): pass
    def label(self, im, d): pass
    def decor(self, im, d): pass
    def selected(self, im, d, box): pass

    def render(self):
        im = Image.new("RGBA", (W, HH), (0, 0, 0, 0))
        d = ImageDraw.Draw(im, "RGBA")
        self.bg(im, d)
        self.plate(im, d)
        self.panel(im, d, FIELD, "field")
        self.field_text(im)
        self.panel(im, d, PET_BOX, "sub")
        self.panel(im, d, GRID_BOX, "main")
        self.portrait(im, d, PORTRAIT)
        for c in PETS:
            self.slot(im, d, sbox(c), "empty")
        for c in GRID:
            self.slot(im, d, sbox(c), "grid")
        self.selected(im, d, sbox(GRID[0]))
        self.bar(im, d, BAR, self.bar_ratio)
        self.label(im, d)
        for c in ACTIONS:
            self.slot(im, d, sbox(c), "normal")
        for case, name, which in BUTTONS:
            self.button(im, d, sbox(case), name, which)
        self.decor(im, d)
        return im


def bar_text(im, col, shadow):
    """Level over the bar, outlined all around so it reads on the filled and on the empty part."""
    s = "NIVEAU 42 · 62 %"
    for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1), (1, 1)):
        ui.write(im, s, 88 + dx, BAR[1] + 3 + dy, shadow)
    ui.write(im, s, 88, BAR[1] + 3, col)


# ================================================================ 01 Memphis
class Memphis(Theme):
    name = "01 Memphis Pop"
    T, P, Y, B, Vv, K_ = C("#00D9C0"), C("#FF3B9D"), C("#F8F000"), C("#3448FF"), C("#8C38FF"), C("#111111")
    def bg(self, im, d):
        d.rectangle(BODY, fill=C("#FFF4E2"), outline=self.K_)
        d.rectangle((BODY[0] + 1, BODY[1] + 1, BODY[2] - 1, BODY[1] + 1), fill=self.K_)
        r = random.Random(3)
        for _ in range(26):                      # confetti: circles, triangles, zigzags, dots
            x, y = r.randint(4, 170), r.randint(10, 216)
            col = r.choice([self.T, self.P, self.Y, self.B, self.Vv])
            k = r.randint(0, 3)
            if k == 0:
                d.ellipse((x, y, x + 5, y + 5), outline=col, width=2)
            elif k == 1:
                d.polygon([(x, y + 5), (x + 3, y), (x + 6, y + 5)], fill=col)
            elif k == 2:
                d.line([(x, y), (x + 2, y + 2), (x + 4, y), (x + 6, y + 2), (x + 8, y)], fill=col, width=1)
            else:
                d.rectangle((x, y, x + 1, y + 1), fill=self.K_)
        d.rectangle((BODY[0], BODY[3] - 1, BODY[2], BODY[3]), fill=self.K_)
        d.rectangle((BODY[2] - 1, BODY[1], BODY[2], BODY[3]), fill=self.K_)
    def plate(self, im, d):
        x0, y0, x1, y1 = PLATE
        hard_shadow(d, (x0, y0, x1, y1 + 2), 3, self.K_)
        d.rectangle((x0, y0, x1, y1 + 2), fill=self.P, outline=self.K_, width=2)
        d.polygon([(x1 - 14, y0 + 2), (x1 - 2, y0 + 2), (x1 - 2, y1)], fill=self.Y)
        big_text(im, "MÉTIER", 86, 2, C("#FFFFFF"), outline=self.K_)
    def panel(self, im, d, box, kind):
        col = {"field": self.B, "sub": self.Vv, "main": self.T, "frame": self.Y}[kind]
        hard_shadow(d, box, 3, self.K_)
        d.rectangle(box, fill=col, outline=self.K_, width=2)
        if kind == "main":                       # zigzag along the top edge
            x0, y0, x1, _ = box
            d.line([(x, y0 + 2 + (x // 3) % 2) for x in range(x0 + 2, x1 - 1)], fill=self.P)
    def field_text(self, im):
        ui.write(im, "MINEUR", 77, 23, C("#FFFFFF"), shadow=self.K_)
    def slot(self, im, d, box, state):
        fill = {"empty": tone(self.Vv, 0.35), "grid": C("#FFFFFF"), "normal": C("#FFFFFF")}[state]
        d.rectangle(box, fill=fill, outline=self.K_)
    def selected(self, im, d, box):
        d.rectangle(box, fill=self.Y, outline=self.K_)
        d.rectangle((box[0] - 1, box[1] - 1, box[2] + 1, box[3] + 1), outline=self.P)
    def button(self, im, d, box, name, which):
        col = {"a": self.P, "b": self.T, "c": self.B}[which]
        x0, y0, x1, y1 = box
        d.ellipse((x0 + 2, y0 + 2, x1 + 2, y1 + 2), fill=self.K_)
        if which == "b":
            d.polygon([(x0, y1), ((x0 + x1) // 2, y0 - 1), (x1, y1)], fill=col, outline=self.K_)
        else:
            d.ellipse(box, fill=col, outline=self.K_, width=2)
        icon(im, name, box, C("#FFFFFF"), shadow=self.K_)
    def bar(self, im, d, box, ratio):
        hard_shadow(d, box, 3, self.K_)
        d.rectangle(box, fill=C("#FFFFFF"), outline=self.K_, width=2)
        x0, y0, x1, y1 = box
        fx = x0 + 2 + int((x1 - x0 - 4) * ratio)
        d.rectangle((x0 + 2, y0 + 2, fx, y1 - 2), fill=self.P)
        for x in range(x0 + 2, fx, 6):
            d.line([(x, y1 - 2), (x + 3, y0 + 2)], fill=self.Y, width=2)
        bar_text(im, C("#FFFFFF"), self.K_)
    def label(self, im, d):
        d.rectangle((6, LABEL_Y - 2, 12 + LABEL_W, LABEL_Y + 6), fill=self.Y, outline=self.K_)
        text(im, "ACTIONS RÉMUNÉRÉES", 9, LABEL_Y, self.K_)


# ================================================================ 02 Comic book
class Comic(Theme):
    name = "02 Comic Book / Pop Art"
    R, Y, B, Cy, K_ = C("#FF3030"), C("#FFE500"), C("#1769FF"), C("#00D9FF"), C("#171717")
    def bg(self, im, d):
        d.rectangle(BODY, fill=self.Y, outline=self.K_, width=2)
        for y in range(BODY[1] + 3, BODY[3] - 2, 4):          # Ben-Day dots
            for x in range(BODY[0] + 3 + (y // 4 % 2) * 2, BODY[2] - 2, 4):
                d.point((x, y), fill=tone(self.R, 0.2))
        r = random.Random(5)
        for _ in range(500):                                 # print grain
            d.point((r.randint(2, 173), r.randint(9, 218)), fill=(0, 0, 0, 40))
        # action lines from the bottom right corner
        for a in range(0, 90, 9):
            t = math.radians(180 + a)
            d.line([(175, 220), (175 + 90 * math.cos(t), 220 + 90 * math.sin(t))], fill=(255, 255, 255, 90))
    def plate(self, im, d):
        cx, cy = 88, 7
        pts = []
        for i in range(20):                      # explosion burst
            rad = 52 if i % 2 == 0 else 38
            t = math.pi * 2 * i / 20
            pts.append((cx + rad * math.cos(t), cy + rad * 0.2 * math.sin(t)))
        d.polygon([(x + 2, y + 2) for x, y in pts], fill=self.K_)
        d.polygon(pts, fill=self.R, outline=self.K_)
        big_text(im, "MÉTIER", 88, 1, self.Y, outline=self.K_)
    def panel(self, im, d, box, kind):
        hard_shadow(d, box, 3, self.K_)
        fill = {"field": self.B, "sub": C("#FFFFFF"), "main": C("#FFFFFF"), "frame": self.K_}[kind]
        d.rectangle(box, fill=fill, outline=self.K_, width=2)
        if kind in ("sub", "main"):              # halftone shading in the bottom right
            x0, y0, x1, y1 = box
            for y in range(y0 + 3, y1 - 1, 3):
                for x in range(x0 + 3, x1 - 1, 3):
                    if (x - x0) + (y - y0) > (x1 - x0) * 0.9:
                        d.point((x, y), fill=self.Cy)
    def field_text(self, im):
        ui.write(im, "MINEUR", 77, 23, C("#FFFFFF"), shadow=self.K_)
    def slot(self, im, d, box, state):
        fill = {"empty": C("#E8E8E8"), "grid": C("#FFFFFF"), "normal": C("#FFFFFF")}[state]
        d.rectangle(box, fill=fill, outline=self.K_, width=1)
        d.line([(box[0] + 1, box[3] - 1), (box[2] - 1, box[3] - 1)], fill=C("#BDBDBD"))
    def selected(self, im, d, box):
        d.rectangle(box, fill=self.Cy, outline=self.K_, width=2)
    def button(self, im, d, box, name, which):
        col = {"a": self.R, "b": C("#2FD24A"), "c": self.B}[which]
        x0, y0, x1, y1 = box
        d.rounded_rectangle((x0 + 2, y0 + 2, x1 + 2, y1 + 2), 4, fill=self.K_)
        d.rounded_rectangle(box, 4, fill=col, outline=self.K_, width=2)
        d.polygon([(x0 + 3, y1), (x0 + 1, y1 + 3), (x0 + 6, y1)], fill=self.K_)   # speech-bubble tail
        icon(im, name, box, C("#FFFFFF"), shadow=self.K_)
    def bar(self, im, d, box, ratio):
        hard_shadow(d, box, 2, self.K_)
        d.rectangle(box, fill=C("#FFFFFF"), outline=self.K_, width=2)
        x0, y0, x1, y1 = box
        fx = x0 + 2 + int((x1 - x0 - 4) * ratio)
        d.rectangle((x0 + 2, y0 + 2, fx, y1 - 2), fill=self.R)
        for y in range(y0 + 3, y1 - 1, 2):
            for x in range(x0 + 3 + (y % 4), fx, 4):
                d.point((x, y), fill=tone(self.R, 0.35))
        bar_text(im, C("#FFFFFF"), self.K_)
    def label(self, im, d):
        d.polygon([(6, LABEL_Y - 3), (18 + LABEL_W, LABEL_Y - 3), (14 + LABEL_W, LABEL_Y + 7), (4, LABEL_Y + 7)], fill=C("#FFFFFF"),
                  outline=self.K_)
        text(im, "ACTIONS RÉMUNÉRÉES", 10, LABEL_Y, self.K_)


# ================================================================ 03 Vaporwave
class Vaporwave(Theme):
    name = "03 Vaporwave Dream"
    P, Cy, Vv, N, L = C("#FF4FD8"), C("#00F0FF"), C("#7928CA"), C("#171044"), C("#C4A6FF")
    def bg(self, im, d):
        x0, y0, x1, y1 = BODY
        for y in range(y0, y1 + 1):
            t = (y - y0) / (y1 - y0)
            d.line([(x0, y), (x1, y)], fill=multi_gradient([(0, self.P), (0.45, self.Vv), (1, self.N)], t))
        # sun with stripes behind the empty bottom, perspective grid
        cx, cy, rad = 88, 205, 30
        for y in range(cy - rad, cy):
            if (y // 3) % 2 == 0 or y < cy - rad + 12:
                w = int(math.sqrt(max(0, rad * rad - (y - cy) ** 2)))
                d.line([(cx - w, y), (cx + w, y)], fill=mix(C("#FFD36E"), self.P, (y - cy + rad) / rad))
        for i in range(-8, 9):
            d.line([(88 + i * 5, 205), (88 + i * 30, 221)], fill=(0, 240, 255, 150))
        for k, y in enumerate((206, 208, 211, 215, 220)):
            d.line([(1, y), (174, y)], fill=(0, 240, 255, 150))
        for y in range(y0, y1, 2):
            d.line([(x0, y), (x1, y)], fill=(255, 255, 255, 14))
        d.rectangle(BODY, outline=self.Cy)
        d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), outline=(255, 79, 216, 120))
    def plate(self, im, d):
        x0, y0, x1, y1 = PLATE
        layer(im, lambda ld, l: ld.rectangle((x0, y0, x1, y1), fill=(23, 16, 68, 200), outline=self.Cy))
        ui.write(im, "MÉTIER", 88, 4, self.Cy, shadow=self.P)
    def panel(self, im, d, box, kind):
        x0, y0, x1, y1 = box
        layer(im, lambda ld, l: ld.rectangle(box, fill=(196, 166, 255, 70) if kind != "field" else (23, 16, 68, 190)))
        d.rectangle((x0 - 1, y0 - 1, x1 + 1, y1 + 1), outline=(0, 240, 255, 90))
        d.rectangle(box, outline=self.Cy if kind != "sub" else self.P)
        d.line([(x0 + 1, y0 + 1), (x1 - 1, y0 + 1)], fill=(255, 255, 255, 140))
    def field_text(self, im):
        ui.write(im, "MINEUR", 77, 23, C("#FFFFFF"), shadow=self.P)
    def slot(self, im, d, box, state):
        a = 120 if state == "empty" else 190
        layer(im, lambda ld, l: ld.rectangle(box, fill=(23, 16, 68, a)))
        d.rectangle(box, outline=(255, 79, 216, 200))
    def selected(self, im, d, box):
        x0, y0, x1, y1 = box
        d.rectangle((x0 - 1, y0 - 1, x1 + 1, y1 + 1), outline=(0, 240, 255, 110))
        d.rectangle(box, outline=self.Cy)
    def button(self, im, d, box, name, which):
        col = {"a": self.P, "b": self.Cy, "c": self.L}[which]
        x0, y0, x1, y1 = box
        layer(im, lambda ld, l: ld.rounded_rectangle((x0 - 1, y0 - 1, x1 + 1, y1 + 1), 4, fill=col[:3] + (70,)))
        d.rounded_rectangle(box, 3, fill=self.N, outline=col)
        icon(im, name, box, col)
    def bar(self, im, d, box, ratio):
        x0, y0, x1, y1 = box
        layer(im, lambda ld, l: ld.rectangle(box, fill=(23, 16, 68, 200)))
        fx = x0 + 1 + int((x1 - x0 - 2) * ratio)
        gradient(im, (x0 + 1, y0 + 1, fx, y1 - 1), self.P, self.Cy, horizontal=True)
        d.line([(x0 + 1, y0 + 1), (fx, y0 + 1)], fill=(255, 255, 255, 160))
        d.rectangle(box, outline=self.Cy)
        bar_text(im, C("#FFFFFF"), self.Vv)
    def label(self, im, d):
        ui.write(im, "ACTIONS RÉMUNÉRÉES", 0, LABEL_Y, self.Cy, shadow=self.P, left=8)


# ================================================================ 04 Acid rave
class Acid(Theme):
    name = "04 Acid Graphic / Rave"
    G, O, M, B, K_ = C("#C8FF00"), C("#FF5A00"), C("#FF00A8"), C("#2400FF"), C("#111111")
    def bg(self, im, d):
        d.rectangle(BODY, fill=self.K_)
        r = random.Random(11)
        for _ in range(4):                       # organic acid blobs
            cx, cy = r.randint(0, 176), r.randint(20, 220)
            pts = [(cx + (14 + r.randint(-6, 8)) * math.cos(t / 9 * math.tau),
                    cy + (12 + r.randint(-6, 8)) * math.sin(t / 9 * math.tau)) for t in range(9)]
            d.polygon(pts, fill=r.choice([self.B, self.M]))
        for y in range(12, 220, 8):              # repeated + pattern
            for x in range(4 + (y // 8 % 2) * 4, 172, 8):
                d.point((x, y), fill=self.G); d.point((x - 1, y), fill=self.G); d.point((x + 1, y), fill=self.G)
                d.point((x, y - 1), fill=self.G); d.point((x, y + 1), fill=self.G)
        d.rectangle(BODY, outline=self.G, width=2)
    def plate(self, im, d):
        x0, y0, x1, y1 = PLATE
        d.polygon(jitter_poly((x0, y0, x1, y1 + 3), 2, 6, self.r), fill=self.G, outline=self.K_)
        big_text(im, "MÉTIER", 90, 2, self.M)       # chromatic split
        big_text(im, "MÉTIER", 87, 1, self.K_)
    def panel(self, im, d, box, kind):
        col = {"field": self.M, "sub": self.O, "main": self.G, "frame": self.B}[kind]
        d.polygon(jitter_poly(box, 2, 5, self.r), fill=col, outline=self.K_)
        x0, y0, x1, y1 = box
        if kind in ("main", "sub"):
            d.rectangle((x0 + 2, y0 + 2, x1 - 2, y1 - 2), fill=self.K_)
    def field_text(self, im):
        ui.write(im, "MINEUR", 78, 24, self.B)
        ui.write(im, "MINEUR", 77, 23, C("#FFFFFF"))
    def slot(self, im, d, box, state):
        col = {"empty": self.O, "grid": self.G, "normal": self.G}[state]
        d.rectangle(box, fill=C("#1E1E1E"), outline=col)
        if state == "normal":
            d.point((box[0] + 1, box[1] + 1), fill=self.M)
    def selected(self, im, d, box):
        d.rectangle(box, fill=self.M, outline=self.G, width=2)
    def button(self, im, d, box, name, which):
        col = {"a": self.O, "b": self.G, "c": self.M}[which]
        d.polygon(jitter_poly(box, 1, 4, self.r), fill=col, outline=self.K_)
        icon(im, name, box, self.K_)
    def bar(self, im, d, box, ratio):
        x0, y0, x1, y1 = box
        d.rectangle(box, fill=self.K_, outline=self.G)
        fx = x0 + 1 + int((x1 - x0 - 2) * ratio)
        for x in range(x0 + 1, fx):
            for y in range(y0 + 1, y1):
                d.point((x, y), fill=self.O if ((x // 3) + (y // 3)) % 2 else self.M)
        bar_text(im, C("#FFFFFF"), self.K_)
    def label(self, im, d):
        ui.write(im, "ACTIONS RÉMUNÉRÉES", 0, LABEL_Y + 1, self.M, left=9)
        ui.write(im, "ACTIONS RÉMUNÉRÉES", 0, LABEL_Y, self.G, left=8)


# ================================================================ 05 Neo-brutalism
class Brutal(Theme):
    name = "05 Neo-Brutalism Color"
    O, B, Y, Wt, K_ = C("#FF5C35"), C("#304CFF"), C("#FFD928"), C("#F5F0E8"), C("#171717")
    def bg(self, im, d):
        d.rectangle(BODY, fill=self.Wt, outline=self.K_, width=2)
        d.rectangle((2, 196, 173, 218), fill=self.B)               # heavy colour block at the bottom
        d.line([(2, 195), (173, 195)], fill=self.K_, width=2)
    def plate(self, im, d):
        x0, y0, x1, y1 = PLATE
        hard_shadow(d, (x0, y0, x1, y1 + 3), 3, self.K_)
        d.rectangle((x0, y0, x1, y1 + 3), fill=self.Y, outline=self.K_, width=2)
        big_text(im, "MÉTIER", 88, 2, self.K_)
    def panel(self, im, d, box, kind):
        col = {"field": self.K_, "sub": self.B, "main": self.O, "frame": self.K_}[kind]
        hard_shadow(d, box, 4, self.K_)
        d.rectangle(box, fill=col, outline=self.K_, width=2)
    def field_text(self, im):
        ui.write(im, "MINEUR", 77, 23, self.Y)
    def slot(self, im, d, box, state):
        fill = {"empty": tone(self.B, 0.5), "grid": self.Wt, "normal": self.Wt}[state]
        d.rectangle(box, fill=fill, outline=self.K_)
    def selected(self, im, d, box):
        hard_shadow(d, box, 2, self.K_)
        d.rectangle(box, fill=self.Y, outline=self.K_, width=2)
    def button(self, im, d, box, name, which):
        col = {"a": self.O, "b": self.Y, "c": self.B}[which]
        hard_shadow(d, box, 3, self.K_)
        d.rectangle(box, fill=col, outline=self.K_, width=2)
        icon(im, name, box, self.K_ if which == "b" else self.Wt)
    def bar(self, im, d, box, ratio):
        hard_shadow(d, box, 3, self.K_)
        d.rectangle(box, fill=self.Wt, outline=self.K_, width=2)
        x0, y0, x1, y1 = box
        fx = x0 + 2 + int((x1 - x0 - 4) * ratio)
        d.rectangle((x0 + 2, y0 + 2, fx, y1 - 2), fill=self.B)
        d.line([(fx + 1, y0 + 2), (fx + 1, y1 - 2)], fill=self.K_)
        bar_text(im, C("#FFFFFF"), self.K_)
    def label(self, im, d):
        d.rectangle((6, LABEL_Y - 3, 12 + LABEL_W, LABEL_Y + 7), fill=self.K_)
        text(im, "ACTIONS RÉMUNÉRÉES", 9, LABEL_Y, self.Wt)


# ================================================================ 06 Candy 3D
class Candy(Theme):
    name = "06 Candy 3D / Toy Design"
    Pk, Bl, Y, O, Vv = C("#FF79C6"), C("#51C8FF"), C("#FFE66D"), C("#FF944D"), C("#A78BFA")
    def blob(self, im, d, box, col, r=4, depth=2):
        x0, y0, x1, y1 = box
        soft_shadow(im, box, 2, (80, 30, 90, 90), blur=1.5, radius=r)
        d.rounded_rectangle((x0, y0, x1, y1 + depth), r, fill=tone(col, -0.3))      # rim
        d.rounded_rectangle(box, r, fill=col)
        layer(im, lambda ld, l: ld.rounded_rectangle((x0 + 2, y0 + 1, x1 - 2, y0 + max(2, (y1 - y0) // 3)), r,
                                                    fill=(255, 255, 255, 110)))      # specular band
        d.point((x0 + 3, y0 + 2), fill=(255, 255, 255, 255))
    def bg(self, im, d):
        x0, y0, x1, y1 = BODY
        gradient(im, (x0, y0, x1, y1), C("#FFE3F3"), C("#E3E0FF"))
        layer(im, lambda ld, l: [ld.ellipse((x - 9, y - 9, x + 9, y + 9), fill=(255, 255, 255, 70))
                                 for x, y in ((20, 200), (150, 190), (100, 214))])
        d.rounded_rectangle(BODY, 8, outline=tone(self.Vv, -0.2), width=2)
    def plate(self, im, d):
        self.blob(im, d, (40, 0, 135, 13), self.Vv, r=6)
        ui.write(im, "MÉTIER", 88, 4, C("#FFFFFF"), shadow=tone(self.Vv, -0.45))
    def panel(self, im, d, box, kind):
        col = {"field": self.Vv, "sub": self.Bl, "main": self.Pk, "frame": self.Y}[kind]
        self.blob(im, d, box, col, r=5)
    def field_text(self, im):
        ui.write(im, "MINEUR", 77, 23, C("#FFFFFF"), shadow=tone(self.Vv, -0.45))
    def slot(self, im, d, box, state):
        x0, y0, x1, y1 = box
        fill = (255, 255, 255, 120) if state == "empty" else (255, 255, 255, 215)
        layer(im, lambda ld, l: ld.rounded_rectangle(box, 3, fill=fill))
        d.rounded_rectangle(box, 3, outline=(120, 70, 140, 90))
        d.line([(x0 + 2, y0 + 1), (x1 - 2, y0 + 1)], fill=(120, 70, 140, 60))      # inset shade
    def selected(self, im, d, box):
        self.blob(im, d, box, self.Y, r=3, depth=1)
    def button(self, im, d, box, name, which):
        col = {"a": self.O, "b": C("#5BD98A"), "c": self.Bl}[which]
        x0, y0, x1, y1 = box
        soft_shadow(im, box, 2, (80, 30, 90, 110), blur=1.2, radius=8)
        d.ellipse((x0, y0 + 1, x1, y1 + 1), fill=tone(col, -0.3))
        d.ellipse(box, fill=col)
        layer(im, lambda ld, l: ld.ellipse((x0 + 3, y0 + 2, x1 - 5, y0 + 6), fill=(255, 255, 255, 150)))
        icon(im, name, box, C("#FFFFFF"), shadow=tone(col, -0.45))
    def bar(self, im, d, box, ratio):
        x0, y0, x1, y1 = box
        layer(im, lambda ld, l: ld.rounded_rectangle(box, 5, fill=(255, 255, 255, 170)))
        fx = x0 + 1 + int((x1 - x0 - 2) * ratio)
        d.rounded_rectangle((x0 + 1, y0 + 2, fx, y1), 5, fill=tone(self.O, -0.25))
        d.rounded_rectangle((x0 + 1, y0 + 1, fx, y1 - 1), 5, fill=self.O)
        layer(im, lambda ld, l: ld.rounded_rectangle((x0 + 3, y0 + 2, fx - 2, y0 + 4), 2, fill=(255, 255, 255, 150)))
        bar_text(im, C("#FFFFFF"), tone(self.O, -0.5))
    def label(self, im, d):
        ui.write(im, "ACTIONS RÉMUNÉRÉES", 0, LABEL_Y, tone(self.Vv, -0.45), left=8)


# ================================================================ 07 Stained glass
class Glass(Theme):
    name = "07 Stained Glass"
    R, B, G, A, Vv, L = C("#E82D54"), C("#2454DB"), C("#00A878"), C("#FFB000"), C("#8539D4"), C("#15131C")
    def bg(self, im, d):
        r = random.Random(4)
        seeds = [(r.randint(0, 176), r.randint(7, 221), r.choice([self.R, self.B, self.G, self.A, self.Vv]))
                 for _ in range(34)]
        px = im.load()
        for y in range(BODY[1], BODY[3] + 1):
            for x in range(BODY[0], BODY[2] + 1):
                dist = sorted(((x - sx) ** 2 + (y - sy) ** 2, i) for i, (sx, sy, _) in enumerate(seeds))
                (d1, i1), (d2, _) = dist[0], dist[1]
                if math.sqrt(d2) - math.sqrt(d1) < 1.0:
                    px[x, y] = self.L                               # lead lines
                else:
                    c = seeds[i1][2]
                    light = 0.35 - 0.5 * (y - 7) / 214                 # light coming from the top
                    c = tone(c, max(-0.45, light))
                    if (x + y * 2) % 29 < 2:
                        c = tone(c, 0.25)                             # glass streaks
                    px[x, y] = c
        d.rectangle(BODY, outline=self.L, width=2)
    def plate(self, im, d):
        x0, y0, x1, y1 = PLATE
        d.rectangle((x0, y0, x1, y1), fill=tone(self.A, 0.1), outline=self.L, width=2)
        d.line([(x0 + 30, y0), (x0 + 22, y1)], fill=self.L); d.line([(x1 - 30, y0), (x1 - 22, y1)], fill=self.L)
        ui.write(im, "MÉTIER", 88, 4, self.L)
    def panel(self, im, d, box, kind):
        x0, y0, x1, y1 = box
        col = {"field": self.L, "sub": self.L, "main": self.L, "frame": self.L}[kind]
        layer(im, lambda ld, l: ld.rectangle(box, fill=(21, 19, 28, 205)))
        d.rectangle((x0 - 1, y0 - 1, x1 + 1, y1 + 1), outline=self.L)
        d.rectangle(box, outline=tone(self.A, -0.2))
        for cx, cy in ((x0, y0), (x1, y0), (x0, y1), (x1, y1)):   # jewel at each corner
            d.rectangle((cx - 1, cy - 1, cx + 1, cy + 1), fill=self.A, outline=self.L)
    def field_text(self, im):
        ui.write(im, "MINEUR", 77, 23, tone(self.A, 0.4))
    def slot(self, im, d, box, state):
        x0, y0, x1, y1 = box
        fill = {"empty": (60, 40, 90, 170), "grid": (36, 84, 219, 110), "normal": (255, 255, 255, 70)}[state]
        layer(im, lambda ld, l: ld.rectangle(box, fill=fill))
        d.rectangle(box, outline=self.L)
        d.line([(x0 + 2, y1 - 2), (x0 + 6, y0 + 2)], fill=(255, 255, 255, 70))
    def selected(self, im, d, box):
        layer(im, lambda ld, l: ld.rectangle(box, fill=(255, 176, 0, 150)))
        d.rectangle(box, outline=self.L)
        d.rectangle((box[0] - 1, box[1] - 1, box[2] + 1, box[3] + 1), outline=self.A)
    def button(self, im, d, box, name, which):
        col = {"a": self.R, "b": self.G, "c": self.B}[which]
        x0, y0, x1, y1 = box
        cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
        d.polygon([(cx, y0 - 1), (x1 + 1, cy), (cx, y1 + 1), (x0 - 1, cy)], fill=col, outline=self.L)
        d.line([(cx, y0), (cx, y1)], fill=tone(col, -0.3))
        icon(im, name, box, C("#FFFFFF"), shadow=self.L)
    def bar(self, im, d, box, ratio):
        x0, y0, x1, y1 = box
        d.rectangle(box, fill=self.L)
        fx = x0 + int((x1 - x0) * ratio)
        cols = [self.R, self.A, self.G, self.B, self.Vv]
        for i, x in enumerate(range(x0 + 1, x1, 12)):
            col = cols[i % 5] if x < fx else C("#3A3646")
            d.rectangle((x, y0 + 1, min(x + 10, x1 - 1), y1 - 1), fill=col)
            if x < fx:
                d.line([(x + 1, y0 + 2), (x + 4, y0 + 2)], fill=tone(col, 0.5))
        bar_text(im, C("#FFFFFF"), self.L)
    def label(self, im, d):
        layer(im, lambda ld, l: ld.rectangle((6, LABEL_Y - 3, 12 + LABEL_W, LABEL_Y + 7), fill=(21, 19, 28, 220)))
        ui.write(im, "ACTIONS RÉMUNÉRÉES", 0, LABEL_Y, tone(self.A, 0.3), left=9)


# ================================================================ 08 Street art
class Street(Theme):
    name = "08 Street Art / Graffiti"
    O, Cy, Vv, Pk, K_ = C("#FF5B22"), C("#00D5E8"), C("#8A35FF"), C("#FF2E93"), C("#191919")
    def bg(self, im, d):
        d.rectangle(BODY, fill=C("#3A3A3C"))
        for y in range(BODY[1], BODY[3], 6):                 # bricks
            d.line([(0, y), (175, y)], fill=C("#2B2B2D"))
            for x in range((y // 6 % 2) * 8, 176, 16):
                d.line([(x, y), (x, y + 5)], fill=C("#2B2B2D"))
        r = random.Random(9)
        for _ in range(7):                                    # spray clouds and drips
            cx, cy = r.randint(5, 170), r.randint(12, 215)
            col = r.choice([self.O, self.Cy, self.Vv, self.Pk])
            for _ in range(120):
                a, rad = r.random() * math.tau, abs(r.gauss(0, 7))
                d.point((cx + rad * math.cos(a), cy + rad * math.sin(a)), fill=col[:3] + (r.randint(90, 220),))
            for _ in range(2):
                x = cx + r.randint(-5, 5)
                d.line([(x, cy), (x, cy + r.randint(6, 16))], fill=col)
        d.rectangle(BODY, outline=self.K_, width=2)
    def plate(self, im, d):
        x0, y0, x1, y1 = PLATE
        d.polygon(jitter_poly((x0, y0, x1, y1 + 2), 2, 5, self.r), fill=self.K_)
        big_text(im, "MÉTIER", 88, 2, self.Cy, outline=self.Pk, shadow=(2, self.K_))
    def panel(self, im, d, box, kind):
        col = {"field": self.Vv, "sub": self.Pk, "main": self.O, "frame": self.Cy}[kind]
        x0, y0, x1, y1 = box
        d.polygon(jitter_poly((x0 - 2, y0 - 2, x1 + 2, y1 + 2), 1, 4, self.r), fill=col)    # paint stroke behind
        d.rectangle(box, fill=self.K_)
        for i in range(x0 + 2, x1 - 1, 6):                    # stencil stripes on the top edge
            d.line([(i, y0 + 1), (i + 2, y0 + 1)], fill=col)
    def field_text(self, im):
        ui.write(im, "MINEUR", 77, 23, self.Cy, shadow=self.Pk)
    def slot(self, im, d, box, state):
        col = {"empty": self.Vv, "grid": self.O, "normal": C("#8A8A8A")}[state]
        d.rectangle(box, fill=C("#242426"), outline=col)
    def selected(self, im, d, box):
        x0, y0, x1, y1 = box
        d.rectangle(box, fill=C("#242426"), outline=self.Cy, width=2)
        d.line([(x1 - 2, y1), (x1 - 2, y1 + 5)], fill=self.Cy)
    def button(self, im, d, box, name, which):
        col = {"a": self.O, "b": self.Cy, "c": self.Vv}[which]
        x0, y0, x1, y1 = box
        d.polygon(jitter_poly(box, 1, 3, self.r), fill=col, outline=self.K_)
        d.line([(x1 - 3, y1), (x1 - 3, y1 + 4)], fill=col)
        icon(im, name, box, self.K_)
    def bar(self, im, d, box, ratio):
        x0, y0, x1, y1 = box
        d.rectangle(box, fill=self.K_)
        fx = x0 + 1 + int((x1 - x0 - 2) * ratio)
        gradient(im, (x0 + 1, y0 + 1, fx, y1 - 1), self.Pk, self.O, horizontal=True)
        for x in range(x0 + 8, fx, 17):
            d.line([(x, y1), (x, y1 + 3 + x % 4)], fill=self.O)
        bar_text(im, C("#FFFFFF"), self.K_)
    def label(self, im, d):
        big_text(im, "ACTIONS RÉMUNÉRÉES", 60, LABEL_Y - 3, C("#FFFFFF"), outline=self.K_, k=1)


# ================================================================ 09 Synthwave
class Synth(Theme):
    name = "09 Synthwave Chromatic"
    M, O, Cy, Vv, N = C("#FF00C8"), C("#FF7A00"), C("#00E5FF"), C("#3510A8"), C("#090D35")
    def bg(self, im, d):
        x0, y0, x1, y1 = BODY
        gradient(im, (x0, y0, x1, 160), self.N, self.Vv)
        d.rectangle((x0, 160, x1, y1), fill=self.N)
        for i in range(-10, 11):                              # perspective grid
            d.line([(88 + i * 6, 162), (88 + i * 26, 221)], fill=(255, 0, 200, 140))
        y = 162
        step = 2
        while y < 221:
            d.line([(x0, y), (x1, y)], fill=(255, 0, 200, 140)); y += step; step += 1
        d.line([(x0, 161), (x1, 161)], fill=self.Cy)
        r = random.Random(2)
        for _ in range(30):
            d.point((r.randint(2, 173), r.randint(9, 150)), fill=(255, 255, 255, r.randint(80, 220)))
        d.rectangle(BODY, outline=self.M)
        d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), outline=(255, 0, 200, 90))
    def plate(self, im, d):
        x0, y0, x1, y1 = PLATE
        d.rectangle((x0, y0, x1, y1), fill=self.N, outline=self.Cy)
        d.rectangle((x0 - 1, y0, x1 + 1, y1 + 1), outline=(0, 229, 255, 90))
        small = Image.new("RGBA", (W, 8), (0, 0, 0, 0))
        ui.write(small, "MÉTIER", 88, 0, C("#FFFFFF"))
        grad = Image.new("RGBA", small.size)
        gradient(grad, (0, 0, W - 1, 7), C("#FFD36E"), self.M)
        grad.putalpha(small.getchannel("A"))
        im.alpha_composite(grad, (0, 4))
    def panel(self, im, d, box, kind):
        x0, y0, x1, y1 = box
        col = {"field": self.M, "sub": self.M, "main": self.Cy, "frame": self.O}[kind]
        layer(im, lambda ld, l: ld.rectangle(box, fill=(9, 13, 53, 225)))
        layer(im, lambda ld, l: ld.rectangle((x0 - 1, y0 - 1, x1 + 1, y1 + 1), outline=col[:3] + (90,)))
        d.rectangle(box, outline=col)
    def field_text(self, im):
        ui.write(im, "MINEUR", 77, 23, self.Cy, shadow=self.Vv)
    def slot(self, im, d, box, state):
        fill = {"empty": C("#1A1450"), "grid": C("#160F4A"), "normal": C("#1E1660")}[state]
        d.rectangle(box, fill=fill, outline=(255, 0, 200, 160))
    def selected(self, im, d, box):
        x0, y0, x1, y1 = box
        layer(im, lambda ld, l: ld.rectangle((x0 - 2, y0 - 2, x1 + 2, y1 + 2), outline=(0, 229, 255, 100)))
        d.rectangle(box, fill=C("#2A1C7A"), outline=self.Cy)
    def button(self, im, d, box, name, which):
        col = {"a": self.O, "b": self.Cy, "c": self.M}[which]
        x0, y0, x1, y1 = box
        layer(im, lambda ld, l: ld.rectangle((x0 - 1, y0 - 1, x1 + 1, y1 + 1), fill=col[:3] + (60,)))
        d.polygon([(x0 + 3, y0), (x1, y0), (x1 - 3, y1), (x0, y1)], fill=self.N, outline=col)
        icon(im, name, box, col)
    def bar(self, im, d, box, ratio):
        x0, y0, x1, y1 = box
        d.rectangle(box, fill=self.N, outline=self.Cy)
        fx = x0 + 1 + int((x1 - x0 - 2) * ratio)
        for y in range(y0 + 1, y1):
            t = (y - y0) / (y1 - y0)
            d.line([(x0 + 1, y), (fx, y)], fill=mix(C("#FFD36E"), self.M, t))
        layer(im, lambda ld, l: ld.rectangle((x0 + 1, y0 - 1, fx, y1 + 1), outline=(255, 0, 200, 70)))
        bar_text(im, C("#FFFFFF"), self.Vv)
    def label(self, im, d):
        ui.write(im, "ACTIONS RÉMUNÉRÉES", 0, LABEL_Y, self.Cy, shadow=self.M, left=8)


# ================================================================ 10 Paper cut
class Paper(Theme):
    name = "10 Paper Cut / Collage"
    Co, Mu, Az, Mi, Vv, Cr = C("#FF695E"), C("#F5C842"), C("#369BFA"), C("#50D6A5"), C("#A66BDE"), C("#FFF1D6")
    def card(self, im, d, box, col, amp=1):
        pts = jitter_poly(box, amp, 4, self.r)
        lay = Image.new("RGBA", im.size, (0, 0, 0, 0))
        ImageDraw.Draw(lay).polygon([(x + 2, y + 2) for x, y in pts], fill=(90, 50, 30, 110))
        im.alpha_composite(lay.filter(ImageFilter.GaussianBlur(1)))
        d.polygon(pts, fill=col)
        r = random.Random(box[0] * 7 + box[1])
        for _ in range((box[2] - box[0]) * (box[3] - box[1]) // 12):   # paper fibres
            x, y = r.randint(box[0], box[2]), r.randint(box[1], box[3])
            d.point((x, y), fill=tone(col, r.choice([-0.06, 0.06])))
    def bg(self, im, d):
        self.card(im, d, BODY, self.Cr, amp=1)
        for col, y, amp in ((self.Mi, 186, 6), (self.Az, 198, 5), (self.Co, 209, 4)):   # layered paper hills
            pts = [(0, 221)] + [(x, y + amp * math.sin(x / 13 + y)) for x in range(0, 177, 4)] + [(176, 221)]
            lay = Image.new("RGBA", im.size, (0, 0, 0, 0))
            ImageDraw.Draw(lay).polygon([(x, yy - 2) for x, yy in pts], fill=(90, 50, 30, 90))
            im.alpha_composite(lay.filter(ImageFilter.GaussianBlur(1.2)))
            d.polygon(pts, fill=col)
        d.ellipse((150, 12, 168, 30), fill=self.Mu)                                     # paper sun
    def plate(self, im, d):
        self.card(im, d, (40, 0, 135, 14), self.Co, amp=1)
        ui.write(im, "MÉTIER", 88, 4, self.Cr, shadow=tone(self.Co, -0.4))
    def panel(self, im, d, box, kind):
        col = {"field": self.Vv, "sub": self.Az, "main": self.Mi, "frame": self.Mu}[kind]
        self.card(im, d, box, col)
    def field_text(self, im):
        ui.write(im, "MINEUR", 77, 23, self.Cr, shadow=tone(self.Vv, -0.4))
    def slot(self, im, d, box, state):
        fill = {"empty": tone(self.Az, 0.45), "grid": self.Cr, "normal": self.Cr}[state]
        x0, y0, x1, y1 = box
        d.rectangle(box, fill=fill)
        d.line([(x0, y0), (x1, y0)], fill=tone(fill, -0.18)); d.line([(x0, y0), (x0, y1)], fill=tone(fill, -0.18))
    def selected(self, im, d, box):
        self.card(im, d, (box[0] - 1, box[1] - 1, box[2] + 1, box[3] + 1), self.Mu)
    def button(self, im, d, box, name, which):
        col = {"a": self.Co, "b": self.Mi, "c": self.Az}[which]
        x0, y0, x1, y1 = box
        lay = Image.new("RGBA", im.size, (0, 0, 0, 0))
        ImageDraw.Draw(lay).ellipse((x0 + 1, y0 + 2, x1 + 1, y1 + 2), fill=(90, 50, 30, 120))
        im.alpha_composite(lay.filter(ImageFilter.GaussianBlur(1)))
        d.ellipse(box, fill=col)
        icon(im, name, box, self.Cr)
    def bar(self, im, d, box, ratio):
        x0, y0, x1, y1 = box
        self.card(im, d, box, tone(self.Cr, -0.12))
        fx = x0 + 1 + int((x1 - x0 - 2) * ratio)
        self.card(im, d, (x0 + 1, y0 + 1, fx, y1 - 1), self.Co)
        bar_text(im, self.Cr, tone(self.Co, -0.5))
    def label(self, im, d):
        self.card(im, d, (6, LABEL_Y - 3, 12 + LABEL_W, LABEL_Y + 7), self.Mu)
        text(im, "ACTIONS RÉMUNÉRÉES", 9, LABEL_Y, tone(self.Mu, -0.6))


# ================================================================ 11 Liquid chrome
class Chrome(Theme):
    name = "11 Liquid Chrome"
    B, Vv, T, Pk = C("#247BFF"), C("#9A42FF"), C("#00DCCB"), C("#FF54B8")
    STOPS = [(0, C("#F4F6FA")), (0.3, C("#9AA3B2")), (0.48, C("#3B4250")), (0.52, C("#C9CFDA")), (0.75, C("#6C7482")),
             (1, C("#E6E9EF"))]
    def chrome(self, im, box, phase=0.0):
        x0, y0, x1, y1 = box
        d = ImageDraw.Draw(im)
        for y in range(y0, y1 + 1):
            t = ((y - y0) / max(1, y1 - y0) + phase) % 1
            d.line([(x0, y), (x1, y)], fill=multi_gradient(self.STOPS, t))
    def bg(self, im, d):
        x0, y0, x1, y1 = BODY
        px = im.load()
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                t = (y / 70 + 0.18 * math.sin(x / 17 + y / 31)) % 1          # liquid bands
                c = multi_gradient(self.STOPS, t)
                c = mix(c, self.Vv if x > 88 else self.B, 0.12 + 0.1 * math.sin(y / 23))
                px[x, y] = tone(c, -0.35)
        d.rounded_rectangle(BODY, 6, outline=C("#E6E9EF"), width=1)
    def plate(self, im, d):
        x0, y0, x1, y1 = PLATE
        self.chrome(im, (x0, y0, x1, y1))
        d.rounded_rectangle((x0, y0, x1, y1), 5, outline=C("#2A2F3A"))
        ui.write(im, "MÉTIER", 88, 4, C("#1A1E26"), shadow=C("#FFFFFF"))
    def panel(self, im, d, box, kind):
        x0, y0, x1, y1 = box
        self.chrome(im, (x0 - 2, y0 - 2, x1 + 2, y1 + 2), phase=0.15)
        d.rounded_rectangle((x0 - 2, y0 - 2, x1 + 2, y1 + 2), 3, outline=C("#2A2F3A"))
        gradient(im, box, C("#151923"), C("#232A3A"))
        col = {"field": self.T, "sub": self.Pk, "main": self.B, "frame": self.Vv}[kind]
        d.line([(x0 + 1, y1), (x1 - 1, y1)], fill=col)
    def field_text(self, im):
        ui.write(im, "MINEUR", 77, 23, C("#E6E9EF"), shadow=self.B)
    def slot(self, im, d, box, state):
        x0, y0, x1, y1 = box
        d.rectangle(box, fill=C("#1B2030") if state != "empty" else C("#14182A"))
        d.line([(x0, y0), (x1, y0)], fill=C("#0C0F18")); d.line([(x0, y0), (x0, y1)], fill=C("#0C0F18"))
        d.line([(x0 + 1, y1), (x1, y1)], fill=C("#9AA3B2")); d.line([(x1, y0 + 1), (x1, y1)], fill=C("#9AA3B2"))
    def selected(self, im, d, box):
        x0, y0, x1, y1 = box
        for i, col in enumerate((self.B, self.Vv, self.Pk)):
            d.rectangle((x0 - 1 - i, y0 - 1 - i, x1 + 1 + i, y1 + 1 + i), outline=col[:3] + (220 - i * 60,))
    def button(self, im, d, box, name, which):
        col = {"a": self.Pk, "b": self.T, "c": self.B}[which]
        x0, y0, x1, y1 = box
        lay = Image.new("RGBA", im.size, (0, 0, 0, 0))
        self.chrome(lay, box)
        mask = Image.new("L", im.size, 0)
        ImageDraw.Draw(mask).ellipse(box, fill=255)
        im.paste(lay, (0, 0), mask)
        d.ellipse(box, outline=C("#2A2F3A"))
        d.ellipse((x0 + 3, y0 + 3, x1 - 3, y1 - 3), fill=col)
        icon(im, name, box, C("#FFFFFF"))
    def bar(self, im, d, box, ratio):
        x0, y0, x1, y1 = box
        self.chrome(im, (x0 - 1, y0 - 1, x1 + 1, y1 + 1))
        d.rectangle(box, fill=C("#151923"))
        fx = x0 + 1 + int((x1 - x0 - 2) * ratio)
        for x in range(x0 + 1, fx + 1):
            t = (x - x0) / (x1 - x0)
            d.line([(x, y0 + 1), (x, y1 - 1)], fill=multi_gradient([(0, self.T), (0.4, self.B), (0.7, self.Vv), (1, self.Pk)], t))
        d.line([(x0 + 2, y0 + 2), (fx - 1, y0 + 2)], fill=(255, 255, 255, 170))
        bar_text(im, C("#FFFFFF"), C("#151923"))
    def label(self, im, d):
        ui.write(im, "ACTIONS RÉMUNÉRÉES", 0, LABEL_Y, C("#E6E9EF"), shadow=C("#151923"), left=8)


# ================================================================ 12 Isometric
class Iso(Theme):
    name = "12 Isometric Color World"
    B, O, G, Y, Pk, Vv = C("#3155D9"), C("#FF833D"), C("#42CF88"), C("#FFD84A"), C("#F45C9B"), C("#8755D9")
    def block(self, d, box, col, h=3):
        """Raised block: front face, light top face, dark right side (light from the top left)."""
        x0, y0, x1, y1 = box
        d.polygon([(x0, y0), (x0 + h, y0 - h), (x1 + h, y0 - h), (x1, y0)], fill=tone(col, 0.3))
        d.polygon([(x1, y0), (x1 + h, y0 - h), (x1 + h, y1 - h), (x1, y1)], fill=tone(col, -0.35))
        d.rectangle(box, fill=col)
        d.line([(x0, y0), (x1, y0)], fill=tone(col, 0.5))
    def bg(self, im, d):
        d.rectangle(BODY, fill=C("#DDE6FF"))
        for k in range(-60, 260, 8):                       # isometric floor tiles
            d.line([(k, 7), (k + 107, 221)], fill=C("#C9D5FA"))
            d.line([(k, 7), (k - 107, 221)], fill=C("#C9D5FA"))
        self.block(d, (4, 200, 40, 214), self.Y, 5)       # little architecture in the free space
        self.block(d, (44, 205, 70, 214), self.G, 4)
        self.block(d, (140, 196, 170, 214), self.Pk, 6)
        d.rectangle(BODY, outline=C("#1C2A6B"))
    def plate(self, im, d):
        self.block(d, (40, 3, 134, 14), self.B, 3)
        ui.write(im, "MÉTIER", 87, 6, C("#FFFFFF"), shadow=tone(self.B, -0.5))
    def panel(self, im, d, box, kind):
        col = {"field": self.Vv, "sub": self.Pk, "main": self.B, "frame": self.O}[kind]
        self.block(d, box, col, 3)
    def field_text(self, im):
        ui.write(im, "MINEUR", 77, 23, C("#FFFFFF"), shadow=tone(self.Vv, -0.5))
    def slot(self, im, d, box, state):
        x0, y0, x1, y1 = box
        base = {"empty": tone(self.Pk, 0.4), "grid": C("#E8EEFF"), "normal": C("#F2F5FF")}[state]
        d.rectangle(box, fill=base)
        d.line([(x0, y0), (x1, y0)], fill=tone(base, -0.3)); d.line([(x0, y0), (x0, y1)], fill=tone(base, -0.3))
    def selected(self, im, d, box):
        self.block(d, (box[0], box[1] + 1, box[2] - 1, box[3]), self.Y, 2)
    def button(self, im, d, box, name, which):
        col = {"a": self.O, "b": self.G, "c": self.Vv}[which]
        x0, y0, x1, y1 = box
        self.block(d, (x0, y0 + 2, x1 - 2, y1), col, 2)
        icon(im, name, (x0, y0 + 2, x1 - 2, y1), C("#FFFFFF"), shadow=tone(col, -0.5))
    def bar(self, im, d, box, ratio):
        x0, y0, x1, y1 = box
        self.block(d, box, C("#B8C4EE"), 3)
        fx = x0 + 1 + int((x1 - x0 - 2) * ratio)
        self.block(d, (x0 + 1, y0 + 2, fx, y1 - 1), self.G, 1)
        bar_text(im, C("#FFFFFF"), tone(self.G, -0.6))
    def label(self, im, d):
        ui.write(im, "ACTIONS RÉMUNÉRÉES", 0, LABEL_Y, C("#1C2A6B"), left=8)


# ---------------------------------------------------------------- content and board
def tex(path):
    im = Image.open(MC + path + ".png").convert("RGBA")
    return im.crop((0, 0, 16, 16)) if im.height > 16 else im


JOBS = ["item/diamond_pickaxe", "block/cobblestone", "item/iron_shovel", "item/wheat", "block/poppy", "item/iron_axe",
        "block/furnace_front", "item/brewing_stand", "block/enchanting_table_top", "item/fishing_rod", "item/iron_sword",
        "block/bricks"]
ORES = ["block/coal_ore", "block/copper_ore", "block/iron_ore", "block/gold_ore", "block/redstone_ore", "block/lapis_ore",
        "block/diamond_ore", "block/emerald_ore", "block/deepslate_coal_ore", "block/deepslate_iron_ore",
        "block/deepslate_gold_ore", "block/deepslate_diamond_ore", "block/nether_gold_ore", "block/nether_quartz_ore",
        "block/ancient_debris_side", "block/raw_iron_block"]


def finish(bg):
    view = bg.resize((W * K, HH * K), Image.NEAREST)
    portrait = Image.open(IA + "influmetier/textures/metier/mineur.png").convert("RGBA").resize((64 * K, 64 * K), Image.NEAREST)
    view.alpha_composite(portrait, (34 * K, 39 * K))
    def put(img, case):
        x, y = ui.objet(case)
        view.alpha_composite(img.resize((16 * K, 16 * K), Image.NEAREST), (x * K, y * K))
    for case, t in zip(GRID, JOBS):
        put(tex(t), case)
    for case, t in zip(ACTIONS, ORES):
        put(tex(t), case)
    return view


THEMES = [Memphis, Comic, Vaporwave, Acid, Brutal, Candy, Glass, Street, Synth, Paper, Chrome, Iso]
os.makedirs(OUT, exist_ok=True)
shots = []
for cls in THEMES:
    t = cls()
    view = finish(t.render())
    view.save(os.path.join(OUT, t.name.split()[0] + ".png"))
    shots.append((t.name, view.resize((W * 2, HH * 2), Image.NEAREST)))
pw, ph = shots[0][1].size
cols, gap, lab = 4, 26, 28
board = Image.new("RGBA", (cols * pw + (cols + 1) * gap, 3 * (ph + lab) + 4 * gap), (36, 36, 40, 255))
dr = ImageDraw.Draw(board)
font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 17)
for i, (name, p) in enumerate(shots):
    x = gap + (i % cols) * (pw + gap); y = gap + (i // cols) * (ph + lab + gap)
    dr.text((x, y), name, fill=(240, 240, 240, 255), font=font)
    board.alpha_composite(p, (x, y + lab))
board.save(os.path.join(OUT, "planche_menus_12.png"))
print("ok", board.size)
