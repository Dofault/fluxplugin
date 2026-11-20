#!/usr/bin/env python3
"""Dix variantes d'icône « VILLE DU MOIS » dans le style du logo (face dorée en dégradé, épaisseur brune vers le bas,
contour bordeaux), en planche d'aperçu numérotée. Pillow requis.

    python3 variantes_ville_du_mois.py <planche.png>
"""
import math
import sys

from PIL import Image, ImageDraw

GRAD = [(255, 245, 127), (254, 236, 104), (248, 221, 84), (244, 204, 58), (232, 168, 34), (210, 107, 12), (191, 90, 8)]
SIDE, SIDE2 = (102, 31, 13), (75, 22, 12)
OUT = (63, 15, 15)
CREAM, DARK = (255, 239, 203), (87, 43, 38)
RED, RED2 = (200, 37, 106), (150, 24, 78)
VIOLET = (91, 45, 158)
W, H = 20, 22


class Icon:
    def __init__(self):
        self.mask = set()
        self.detail = {}

    def rect(self, x0, y0, x1, y1):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                self.mask.add((x, y))

    def disc(self, cx, cy, r):
        for x in range(int(cx - r - 1), int(cx + r + 2)):
            for y in range(int(cy - r - 1), int(cy + r + 2)):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                    self.mask.add((x, y))

    def poly(self, pts):
        im = Image.new("1", (W, H), 0)
        ImageDraw.Draw(im).polygon(pts, fill=1)
        for x in range(W):
            for y in range(H):
                if im.getpixel((x, y)):
                    self.mask.add((x, y))

    def dot(self, x, y, c):
        self.detail[(x, y)] = c

    def render(self):
        ys = [y for _, y in self.mask]
        top, bot = min(ys), max(ys)
        img = {}
        for (x, y) in self.mask:
            t = (y - top) / max(1, bot - top)
            c = GRAD[min(len(GRAD) - 1, int(t * len(GRAD)))]
            # light on the upper-left edges, as on the letters
            if (x - 1, y) not in self.mask or (x, y - 1) not in self.mask:
                c = tuple(min(255, v + 18) for v in c)
            img[(x, y)] = c
        img.update({p: c for p, c in self.detail.items()})
        # the brown thickness, down and a little right, like the logo
        for (x, y) in sorted(self.mask):
            for d, col in ((1, SIDE), (2, SIDE2)):
                q = (x, y + d)
                if q not in img:
                    img[q] = col
        full = dict(img)
        for (x, y) in list(full):
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, 1), (1, -1), (-1, -1)):
                q = (x + dx, y + dy)
                if q not in full and abs(dx) + abs(dy) == 1:
                    img[q] = OUT
        xs = [x for x, _ in img]
        ys = [y for _, y in img]
        x0, y0 = min(xs), min(ys)
        out = Image.new("RGBA", (max(xs) - x0 + 1, max(ys) - y0 + 1), (0, 0, 0, 0))
        for (x, y), c in img.items():
            out.putpixel((x - x0, y - y0), c + (255,))
        return out


def house(i, cx, base, w=8, h=6, roof=4):
    """A gold house: walls w wide, h high, roof on top; returns the door/window cells for details."""
    x0, x1 = cx - w // 2, cx + w // 2 - 1
    i.rect(x0, base - h + 1, x1, base)
    i.poly([(x0 - 1, base - h + 1), (cx - 0.5, base - h + 1 - roof), (x1 + 1, base - h + 1)])
    i.dot(cx - 1, base, DARK); i.dot(cx, base, DARK); i.dot(cx - 1, base - 1, DARK); i.dot(cx, base - 1, DARK)
    i.dot(x0 + 1, base - h + 2, CREAM); i.dot(x1 - 1, base - h + 2, CREAM)


def crown(i, cx, top, w=8):
    x0 = cx - w // 2
    i.rect(x0, top + 2, x0 + w - 1, top + 3)
    for k in (0, w // 2 - 1, w // 2, w - 1):
        i.rect(x0 + k, top, x0 + k, top + 1)
    i.dot(cx - 1, top + 2, RED); i.dot(cx, top + 2, RED)


def v1():  # coupe trophée, maison gravée
    i = Icon()
    i.poly([(4, 2), (15, 2), (14, 9), (11, 12), (8, 12), (5, 9)])
    i.rect(2, 3, 3, 6); i.rect(16, 3, 17, 6)
    i.rect(9, 12, 10, 15); i.rect(6, 16, 13, 18)
    for (x, y) in ((9, 5), (10, 5), (8, 6), (11, 6), (8, 7), (11, 7), (9, 8), (10, 8)):
        i.dot(x, y, SIDE)
    i.dot(9, 7, DARK); i.dot(10, 7, DARK)
    return i


def v2():  # grande couronne à joyaux
    i = Icon()
    i.poly([(2, 15), (2, 5), (6, 10), (10, 3), (14, 10), (18, 5), (18, 15)])
    i.rect(2, 15, 18, 17)
    for x, c in ((5, RED), (10, VIOLET), (15, RED)):
        i.dot(x, 13, c); i.dot(x, 14, c)
    i.dot(10, 6, CREAM)
    return i


def v3():  # médaille à rubans
    i = Icon()
    i.disc(9.5, 12.5, 6.5)
    for y in range(0, 6):
        for x in (6 + y // 2, 13 - y // 2):
            i.dot(x - 1, y, RED); i.dot(x, y, RED2 if y % 2 else RED)
    i.mask.update({(5, 5), (14, 5)})
    for (x, y) in ((9, 9), (10, 9), (8, 10), (11, 10), (7, 11), (12, 11), (7, 12), (12, 12), (8, 13), (11, 13), (9, 14), (10, 14)):
        i.dot(x, y, SIDE)
    i.dot(9, 12, CREAM); i.dot(10, 12, CREAM)
    return i


def v4():  # silhouette de ville (trois tours) sous une couronne
    i = Icon()
    i.rect(2, 10, 5, 18); i.poly([(2, 10), (3.5, 7), (5, 10)])
    i.rect(7, 7, 12, 18); i.poly([(7, 7), (9.5, 4), (12, 7)])
    i.rect(14, 9, 17, 18); i.poly([(14, 9), (15.5, 6), (17, 9)])
    i.rect(2, 16, 17, 18)
    for (x, y) in ((3, 12), (4, 12), (3, 14), (4, 14), (9, 9), (10, 9), (9, 12), (10, 12), (15, 11), (16, 11), (15, 13), (16, 13)):
        i.dot(x, y, CREAM)
    for (x, y) in ((9, 16), (10, 16), (9, 17), (10, 17), (9, 18), (10, 18)):
        i.dot(x, y, DARK)
    crown(i, 10, 0, w=6)
    return i


def v5():  # tour de château et son drapeau
    i = Icon()
    i.rect(5, 7, 14, 18)
    for x in (5, 8, 11, 14):
        i.rect(x, 5, x, 6)
    i.rect(5, 5, 6, 6); i.rect(13, 5, 14, 6)
    i.rect(9, 0, 9, 5)
    for y in range(0, 3):
        for x in range(10, 14 - y):
            i.dot(x, y, RED if y != 1 else RED2)
        i.mask.update((x, y) for x in range(10, 14 - y))
    i.dot(9, 16, DARK); i.dot(10, 16, DARK); i.dot(9, 17, DARK); i.dot(10, 17, DARK); i.dot(9, 18, DARK); i.dot(10, 18, DARK)
    i.dot(7, 10, CREAM); i.dot(12, 10, CREAM)
    return i


def v6():  # maison dans une couronne de laurier
    i = Icon()
    for k in range(8):
        for s_ in (-1, 1):
            a = math.radians(250 - k * 19) if s_ < 0 else math.radians(-70 + k * 19)
            x, y = 9.5 + 8 * math.cos(a), 11 - 8 * math.sin(a)
            ox, oy = math.cos(a) * 1.1, -math.sin(a) * 1.1
            i.mask.add((round(x), round(y)))
            i.mask.add((round(x + ox), round(y + oy - 0.6)))
    house(i, 10, 15, w=6, h=4, roof=3)
    return i


def v7():  # étoile dorée, fenêtre de maison au cœur
    i = Icon()
    pts = []
    for k in range(10):
        a = math.radians(-90 + k * 36)
        r = 9.5 if k % 2 == 0 else 4
        pts.append((9.5 + r * math.cos(a), 10.5 + r * math.sin(a)))
    i.poly(pts)
    house_cells = [(9, 9), (10, 9), (8, 10), (11, 10), (8, 11), (11, 11), (8, 12), (9, 12), (10, 12), (11, 12)]
    for p in house_cells:
        i.dot(*p, SIDE)
    i.dot(9, 11, CREAM); i.dot(10, 11, CREAM)
    return i


def v8():  # page de calendrier couronnée (« du mois »)
    i = Icon()
    i.rect(3, 6, 16, 18)
    crown(i, 10, 1, w=8)
    for x in (5, 14):
        i.rect(x, 4, x, 7)
        i.dot(x, 4, DARK); i.dot(x, 5, DARK)
    for y in range(9, 18):
        for x in range(4, 16):
            i.dot(x, y, CREAM)
    for y in (10, 13, 16):
        for x in range(5, 15, 3):
            i.dot(x, y, SIDE)
    i.dot(11, 13, RED); i.dot(12, 13, RED)
    return i


def v9():  # écusson et sa maison
    i = Icon()
    i.poly([(2, 2), (17, 2), (17, 11), (9.5, 19), (2, 11)])
    for (x, y) in [(x, y) for x in range(6, 14) for y in range(9, 14)] + [(x, 8) for x in range(7, 13)] + [(x, 7) for x in range(8, 12)] + [(9, 6), (10, 6)]:
        i.dot(x, y, SIDE)
    i.dot(9, 12, DARK); i.dot(10, 12, DARK); i.dot(9, 13, DARK); i.dot(10, 13, DARK)
    i.dot(7, 10, CREAM); i.dot(12, 10, CREAM)
    i.dot(6, 4, CREAM); i.dot(7, 4, CREAM)
    return i


def v10():  # podium, maison sur la première marche
    i = Icon()
    i.rect(1, 15, 6, 20); i.rect(7, 11, 12, 20); i.rect(13, 17, 18, 20)
    house(i, 10, 10, w=6, h=3, roof=3)
    for (x, y) in ((9, 13), (10, 13), (10, 14), (10, 15), (10, 16), (9, 17), (10, 17), (11, 17)):
        i.dot(x, y, SIDE)
    return i


VARIANTS = [v1, v2, v3, v4, v5, v6, v7, v8, v9, v10]
NAMES = ["Coupe", "Couronne", "Médaille", "Ville couronnée", "Tour et drapeau", "Laurier", "Étoile", "Calendrier",
         "Écusson", "Podium"]

if __name__ == "__main__":
    k, cell = 8, 30
    sheet = Image.new("RGBA", (5 * cell * k, 2 * (cell + 6) * k), (30, 26, 40, 255))
    d = ImageDraw.Draw(sheet)
    for n, fn in enumerate(VARIANTS):
        ic = fn().render()
        big = ic.resize((ic.width * k, ic.height * k), Image.NEAREST)
        cx, cy = (n % 5) * cell * k, (n // 5) * (cell + 6) * k
        sheet.alpha_composite(big, (cx + (cell * k - big.width) // 2, cy + (cell * k - big.height) // 2))
        d.text((cx + 12, cy + cell * k + 4), f"{n + 1}. {NAMES[n]}  ({ic.width}x{ic.height})", fill=(230, 220, 200, 255))
    sheet.save(sys.argv[1])
    print("planche :", sys.argv[1])
