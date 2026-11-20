#!/usr/bin/env python3
"""Skins des habitants de Resaac (PNJ Typewriter de la page pnj_ville) : trente passants d'une ville haussmannienne
d'aujourd'hui, dessinés par script en pixel art (format Minecraft 64 x 64, bras de 4 pixels, deuxième couche utilisée
pour le volume : cheveux, couvre-chefs, manteaux, sacs).

Usage : python3 gen_skins.py [dossier de sortie]
Écrit un PNG par habitant (<nn>_<id>.png) et la planche planche.png (chaque habitant de face et de dos, numéroté).

Dessin : chaque matière a une rampe de cinq teintes (ombres tirées vers le bleu, lumières vers le jaune) ; la teinte
d'un pixel dépend de la face (dessus clair, dos et faces intérieures des membres plus sombres), de sa hauteur et d'un
léger grain fixe. Les détails (visage, cols, boutons, rayures) sont posés ensuite, pixel par pixel.
"""
import colorsys
import hashlib
import os
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.normpath(os.path.join(HERE, "..", "..", "influcard")))
import apercu as A  # noqa: E402
from png_util import write  # noqa: E402

# --------------------------------------------------------------------------- skin layout
DIMS = {"head": (8, 8, 8), "body": (8, 12, 4), "armR": (4, 12, 4), "armL": (4, 12, 4), "legR": (4, 12, 4),
        "legL": (4, 12, 4)}
ORIGIN = [{"head": (0, 0), "body": (16, 16), "armR": (40, 16), "legR": (0, 16), "legL": (16, 48), "armL": (32, 48)},
          {"head": (32, 0), "body": (16, 32), "armR": (40, 32), "legR": (0, 32), "legL": (0, 48), "armL": (48, 48)}]
SIDES = ("front", "back", "right", "left")
FACES = SIDES + ("top", "bottom")
INNER = {"armR": "left", "armL": "right", "legR": "left", "legL": "right"}
OUTER = {"armR": "right", "armL": "left", "legR": "right", "legL": "left"}
ARMS, LEGS = ("armR", "armL"), ("legR", "legL")


def face_rect(part, face):
    """(u, v, w, h) of a face inside its part's block of the texture."""
    w, h, d = DIMS[part]
    return {"top": (d, 0, w, d), "bottom": (d + w, 0, w, d), "right": (0, d, d, h), "front": (d, d, w, h),
            "left": (d + w, d, d, h), "back": (2 * d + w, d, w, h)}[face]


def hexc(s):
    return tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def _towards(h, target, f):
    d = ((target - h + 0.5) % 1.0) - 0.5
    return (h + d * f) % 1.0


def ramp(base):
    """Five tones of a colour: two shadows (towards blue violet, more saturated), base, two lights (towards yellow)."""
    if isinstance(base, str):
        base = hexc(base)
    h, s, v = colorsys.rgb_to_hsv(*(c / 255 for c in base))
    out = []
    for k in (-2, -1, 0, 1, 2):
        if k < 0:
            vv = v * (1 + 0.17 * k)
            ss = min(1.0, s * (1 - 0.14 * k) + (0.03 * -k if s > 0.06 else 0))
            hh = _towards(h, 0.70, 0.07 * -k) if s > 0.06 else h
        else:
            vv = min(1.0, v * (1 + 0.08 * k) + 0.025 * k)
            ss = s * (1 - 0.14 * k)
            hh = _towards(h, 0.13, 0.06 * k) if s > 0.06 else h
        out.append(tuple(round(c * 255) for c in colorsys.hsv_to_rgb(hh, max(0, ss), max(0, vv))))
    return out


FACE_LIGHT = {"front": 0.15, "back": -0.3, "right": -0.2, "left": -0.2, "top": 0.75, "bottom": -1.3}


class Skin:
    def __init__(self, seed):
        self.seed = seed
        self.t = [{p: {f: None for f in FACES} for p in DIMS} for _ in (0, 1)]
        for layer in (0, 1):
            for p in DIMS:
                for f in FACES:
                    _, _, w, h = face_rect(p, f)
                    self.t[layer][p][f] = [[None] * w for _ in range(h)]

    def size(self, part, face):
        _, _, w, h = face_rect(part, face)
        return w, h

    def set(self, part, face, x, y, col, layer=0):
        w, h = self.size(part, face)
        if 0 <= x < w and 0 <= y < h:
            self.t[layer][part][face][y][x] = col

    def get(self, part, face, x, y, layer=0):
        return self.t[layer][part][face][y][x]

    def tone(self, part, face, x, y, layer, noise):
        w, h = self.size(part, face)
        t = FACE_LIGHT[face]
        if INNER.get(part) == face:
            t -= 0.55
        if face in SIDES:
            if y == 0:
                t += 0.3
            if y == h - 1:
                t -= 0.35
        n = int.from_bytes(hashlib.blake2b(f"{self.seed}/{layer}/{part}/{face}/{x}/{y}".encode(),
                                           digest_size=4).digest(), "big") / 0xFFFFFFFF
        return t + (n * 2 - 1) * noise

    def paint(self, part, rmp, layer=0, faces=FACES, rows=None, where=None, level=0.0, noise=0.42):
        """Fills faces of a part with a material. rows: rows of the side faces (top painted when row 0 is in,
        bottom when the last row is in); where(face, x, y) restricts further."""
        w0, h0, _ = DIMS[part]
        for f in faces:
            w, h = self.size(part, f)
            if rows is not None:
                if f == "top" and 0 not in rows:
                    continue
                if f == "bottom" and (h0 - 1) not in rows:
                    continue
            for y in range(h):
                if rows is not None and f in SIDES and y not in rows:
                    continue
                for x in range(w):
                    if where and not where(f, x, y):
                        continue
                    i = round(2 + level + self.tone(part, f, x, y, layer, noise))
                    self.t[layer][part][f][y][x] = rmp[max(0, min(4, i))]

    def ring(self, part, y, col, layer=0, faces=SIDES):
        """One row all around a part."""
        for f in faces:
            for x in range(self.size(part, f)[0]):
                self.set(part, f, x, y, col, layer)

    def image(self):
        px = [[(0, 0, 0, 0)] * 64 for _ in range(64)]
        for layer in (0, 1):
            for p in DIMS:
                ou, ov = ORIGIN[layer][p]
                for f in FACES:
                    u, v, w, h = face_rect(p, f)
                    for y in range(h):
                        for x in range(w):
                            c = self.t[layer][p][f][y][x]
                            if c is not None:
                                px[ov + v + y][ou + u + x] = tuple(c) + (255,)
        return px


def fdist(face, x):
    """Distance from the front edge on a side face of the head (right face: its last column touches the front)."""
    return 7 - x if face == "right" else x


# --------------------------------------------------------------------------- hair
def hair_mask(style):
    def short(f, x, y):
        if f == "front":
            return y <= 1 or (y == 2 and x in (0, 7))
        if f in ("right", "left"):
            d = fdist(f, x)
            return y <= 2 or (y == 3 and d >= 2) or (y == 4 and d >= 5)
        if f == "back":
            return y <= 4 or (y == 5 and 1 <= x <= 6)
        return f == "top"

    def part(f, x, y):
        return short(f, x, y) or (f == "front" and y == 2 and x <= 3)

    def fringe(f, x, y):
        if f == "front":
            return y <= 2 or (y == 3 and x in (0, 7))
        if f in ("right", "left"):
            return y <= 3 or (y == 4 and fdist(f, x) >= 3)
        if f == "back":
            return y <= 5
        return f == "top"

    def bob(f, x, y):
        if f == "front":
            return y <= 1 or (y == 2 and (x <= 1 or x >= 5)) or (x in (0, 7) and y <= 6)
        if f in ("right", "left"):
            return y <= 5 or (y == 6 and fdist(f, x) >= 1)
        if f == "back":
            return y <= 6
        return f == "top"

    def bob_fringe(f, x, y):
        return bob(f, x, y) or (f == "front" and y <= 2)

    def long(f, x, y):
        if f == "front":
            return y <= 1 or (y == 2 and (x <= 1 or x >= 6)) or x in (0, 7)
        if f in ("right", "left"):
            return y <= 3 or fdist(f, x) >= 1
        return f in ("back", "top")

    def long_fringe(f, x, y):
        return long(f, x, y) or (f == "front" and y <= 2)

    def buzz(f, x, y):
        if f == "front":
            return y == 0 or (y == 1 and x in (0, 7))
        if f in ("right", "left"):
            return y <= 1 or (y == 2 and fdist(f, x) >= 2)
        if f == "back":
            return y <= 3
        return f == "top"

    def balding(f, x, y):
        if f in ("right", "left"):
            return 2 <= y <= 3 and fdist(f, x) >= 2 or (y == 4 and fdist(f, x) >= 5)
        if f == "back":
            return 2 <= y <= 4
        return False

    def curly(f, x, y):
        if f == "front":
            return y <= 2 or (y == 3 and x in (0, 7))
        if f in ("right", "left"):
            return y <= 3 or (y == 4 and fdist(f, x) >= 2)
        if f == "back":
            return y <= 5
        return f == "top"
    return {"short": short, "part": part, "fringe": fringe, "bob": bob, "bob_fringe": bob_fringe, "long": long,
            "long_fringe": long_fringe, "buzz": buzz, "balding": balding, "curly": curly, "pony": short, "bun": short,
            "bald": lambda f, x, y: False}[style]


def paint_hair(sk, style, colour, skin):
    r = ramp(colour)
    mask = hair_mask(style)
    if style == "buzz":
        r = [mix(c, skin[2], 0.35) for c in r]
    sk.paint("head", r, 0, where=mask, noise=0.75)
    if style in ("bob", "bob_fringe", "long", "long_fringe", "curly", "fringe", "part"):
        # second layer: the same shape without its lowest pixels, a little lighter (volume)
        def vol(f, x, y):
            if not mask(f, x, y):
                return False
            if style == "curly":
                return zlib.crc32(f"{sk.seed}c{f}{x}{y}".encode()) % 5 != 0
            return f == "top" or mask(f, x, y + 1) if y < 7 else True
        sk.paint("head", r, 1, where=vol, noise=0.8, level=0.25)
    if style in ("long", "long_fringe"):
        sk.paint("body", r, 1, faces=("back",), where=lambda f, x, y: y <= 3 or (y == 4 and 1 <= x <= 6), noise=0.75)
    if style == "pony":
        sk.paint("head", r, 1, faces=("back",), where=lambda f, x, y: 3 <= x <= 4 and 2 <= y, noise=0.6, level=0.3)
        sk.set("head", "back", 3, 2, r[0], 1)
        sk.set("head", "back", 4, 2, r[0], 1)
        sk.paint("body", r, 1, faces=("back",), where=lambda f, x, y: 3 <= x <= 4 and y <= 3, noise=0.6, level=0.2)
    if style == "bun":
        sk.paint("head", r, 1, faces=("top",), where=lambda f, x, y: 2 <= x <= 5 and 4 <= y <= 7, noise=0.6, level=0.4)
        sk.paint("head", r, 1, faces=("back",), where=lambda f, x, y: 2 <= x <= 5 and y <= 2, noise=0.6, level=0.3)
        for x in (2, 5):
            sk.set("head", "top", x, 4, r[0], 1)
    return r


# --------------------------------------------------------------------------- face
def paint_face(sk, skin, hair, eyes="#3B2A20", brows=True, lips=None, beard=None, blush=False, old=False):
    eye = hexc(eyes)
    white = (236, 238, 242)
    S = sk.set
    for x, c in ((1, white), (2, eye), (5, eye), (6, white)):
        S("head", "front", x, 4, c)
    if brows:
        b = mix(skin[2], hair[0], 0.6)
        for x in (1, 2, 5, 6):
            S("head", "front", x, 3, b)
    S("head", "front", 3, 5, skin[1])
    S("head", "front", 4, 5, skin[1])
    mouth = hexc(lips) if lips else mix(skin[0], (150, 70, 66), 0.22)
    S("head", "front", 3, 6, mouth)
    S("head", "front", 4, 6, mouth)
    if blush:
        for x in (1, 6):
            S("head", "front", x, 5, mix(skin[2], (235, 120, 130), 0.4))
    if old:                                            # a wrinkle under each eye, deeper cheeks
        for x in (1, 6):
            S("head", "front", x, 5, skin[1])
        S("head", "front", 2, 7, skin[1])
        S("head", "front", 5, 7, skin[1])
    if beard == "stubble":
        st = mix(skin[2], hair[1], 0.35)
        for y in (5, 6, 7):
            for x in range(8):
                if (y == 5 and 2 <= x <= 5) or (y == 6 and 3 <= x <= 4):
                    continue
                S("head", "front", x, y, st if (x + y) % 2 or y == 7 else mix(st, skin[2], 0.4))
        for f in ("right", "left"):
            for y in (5, 6, 7):
                for x in range(8):
                    if fdist(f, x) <= 3:
                        S("head", f, x, y, st)
    if beard in ("full", "short"):
        c0, c1 = hair[1], hair[2]
        cells = [(0, 5), (7, 5), (2, 5), (5, 5), (1, 5), (6, 5)] + [(x, 6) for x in (0, 1, 2, 5, 6, 7)] + \
                [(x, 7) for x in range(8)]
        if beard == "short":
            cells = [(0, 6), (7, 6), (1, 6), (6, 6), (2, 6), (5, 6)] + [(x, 7) for x in range(8)]
        for x, y in cells:
            S("head", "front", x, y, c1 if (x * 3 + y) % 3 else c0)
        for f in ("right", "left"):
            for y in (4, 5, 6, 7):
                for x in range(8):
                    d = fdist(f, x)
                    if d <= (1 if y == 4 else 3 if y < 7 else 4):
                        S("head", f, x, y, c1 if (x + y) % 2 else c0)
        for x in range(1, 7):
            S("head", "bottom", x, 0, c0)
            S("head", "bottom", x, 1, c0)
    if beard == "moustache":
        for x in (2, 3, 4, 5):
            S("head", "front", x, 6, hair[2] if x in (3, 4) else hair[1])


def glasses(sk, colour="#22222A", sun=False):
    c = hexc(colour)
    for x in (0, 3, 4, 7):
        sk.set("head", "front", x, 4, c, 1)
    if sun:
        for x in (1, 2, 5, 6):
            sk.set("head", "front", x, 4, (28, 28, 36), 1)
        sk.set("head", "front", 1, 4, (70, 70, 90), 1)
        sk.set("head", "front", 5, 4, (70, 70, 90), 1)
    for f in ("right", "left"):
        for x in range(8):
            if fdist(f, x) <= 3:
                sk.set("head", f, x, 4, c, 1)


# --------------------------------------------------------------------------- headwear (second layer)
def hat(sk, kind, colour, accent=None):
    r = ramp(colour)
    a = ramp(accent) if accent else r
    P = sk.paint
    if kind == "beanie":
        P("head", r, 1, where=lambda f, x, y: f == "top" or (f in SIDES and y <= 2), noise=0.5)
        for f in SIDES:
            for x in range(8):
                sk.set("head", f, x, 2, a[1] if x % 2 else a[3], 1)
    elif kind in ("cap", "flatcap", "helmet"):
        P("head", r, 1, where=lambda f, x, y: f == "top" or (f in SIDES and y <= 1), noise=0.4,
          level=0.3 if kind == "helmet" else 0)
        for x in range(8):
            sk.set("head", "front", x, 2, a[1] if kind != "helmet" else r[1], 1)
        if kind == "cap":
            sk.set("head", "front", 3, 0, a[3], 1)
            sk.set("head", "front", 4, 0, a[3], 1)
            for x in (3, 4):
                sk.set("head", "back", x, 1, r[0], 1)       # adjustment strap
        if kind == "helmet":
            for y in range(8):
                sk.set("head", "top", 3, y, r[4], 1)
                sk.set("head", "top", 4, y, r[4], 1)
            sk.ring("head", 1, r[1], 1)
        if kind == "flatcap":
            for f in SIDES:
                for x in range(8):
                    if (x + (0 if f in ("front", "back") else 1)) % 2:
                        sk.set("head", f, x, 0, r[1], 1)
    elif kind == "capback":
        P("head", r, 1, where=lambda f, x, y: f == "top" or (f in SIDES and y <= 1), noise=0.4)
        for x in range(8):
            sk.set("head", "back", x, 2, a[1], 1)
        for x in (3, 4):
            sk.set("head", "front", x, 1, None, 1)
    elif kind == "beret":
        P("head", r, 1, where=lambda f, x, y: f == "top" or (f in ("back", "right") and y <= 1)
          or (f == "left" and y == 0) or (f == "front" and (y == 0 or (y == 1 and x <= 4))), noise=0.4)
        sk.set("head", "top", 4, 3, r[0], 1)
    elif kind == "toque":
        P("head", r, 1, where=lambda f, x, y: f == "top" or (f in SIDES and y <= 2), noise=0.3, level=0.5)
        sk.ring("head", 2, r[1], 1)
        for f in SIDES:
            for x in range(1, 8, 2):
                sk.set("head", f, x, 0, r[2], 1)
    elif kind == "straw":
        P("head", r, 1, where=lambda f, x, y: f == "top" or (f in SIDES and y <= 1), noise=0.7)
        sk.ring("head", 1, a[2], 1)
        sk.ring("head", 2, r[1], 1)
    elif kind == "hijab":
        def m(f, x, y):
            if f == "front":
                return y <= 1 or x in (0, 7) or y == 7
            return f != "bottom"
        P("head", r, 1, where=m, noise=0.45)
        P("body", r, 1, rows=range(0, 2), noise=0.45)
        for x in range(1, 7):
            sk.set("head", "front", x, 1, r[1], 1)
    elif kind == "headphones":
        for x in range(8):
            for y in (3, 4):
                sk.set("head", "top", x, y, r[1], 1)
        for f in ("right", "left"):
            for y in (0, 1, 2):
                sk.set("head", f, 3, y, r[1], 1)
                sk.set("head", f, 4, y, r[1], 1)
            for y in (3, 4, 5):
                for x in (2, 3, 4, 5):
                    sk.set("head", f, x, y, r[1] if x in (2, 5) or y == 5 else a[2], 1)


# --------------------------------------------------------------------------- body helpers
def arms(sk, rmp, rows=range(12), layer=0, **kw):
    for p in ARMS:
        sk.paint(p, rmp, layer, rows=rows, **kw)


def legs(sk, rmp, rows=range(12), layer=0, **kw):
    for p in LEGS:
        sk.paint(p, rmp, layer, rows=rows, **kw)


def neckline(sk, skin, depth=1, wide=False):
    for y in range(depth):
        half = (2 if wide else 1) - (y if depth > 1 else 0)
        for x in range(4 - max(1, half), 4 + max(1, half)):
            sk.set("body", "front", x, y, skin[1] if y == 0 else skin[2])


def collar(sk, colour):
    c = ramp(colour)
    for x in (1, 2, 5, 6):
        sk.set("body", "front", x, 0, c[3])
    sk.set("body", "front", 2, 1, c[2])
    sk.set("body", "front", 5, 1, c[2])
    for x in range(8):
        sk.set("body", "back", x, 0, c[3])
    for f in ("right", "left"):
        for x in range(4):
            sk.set("body", f, x, 0, c[3])


def buttons(sk, colour, rows=(2, 4, 6, 8, 10), x=4, placket=None):
    if placket is not None:
        for y in range(1, 12):
            sk.set("body", "front", x, y, placket)
    for y in rows:
        sk.set("body", "front", x, y, hexc(colour) if isinstance(colour, str) else colour)


def stripes(sk, parts, colour, period=2, offset=1, rows=range(12), layer=0, on=None):
    """Stripes all around the parts, only on the pixels of the garment `on` (its ramp) when given."""
    r = ramp(colour)
    for p in parts:
        for f in SIDES:
            w, h = sk.size(p, f)
            for y in rows:
                if (y - offset) % period == 0:
                    for x in range(w):
                        cur = sk.get(p, f, x, y, layer)
                        if cur is not None and (on is None or cur in on):
                            t = sk.tone(p, f, x, y, layer, 0.4)
                            sk.set(p, f, x, y, r[max(0, min(4, round(2 + t)))], layer)


def belt(sk, colour="#2A2422", buckle="#C9B36A"):
    c = ramp(colour)
    for p in LEGS:
        sk.ring(p, 0, c[2])
        sk.set(p, INNER[p], 0, 0, c[1])
    sk.set("legR", "front", 3, 0, hexc(buckle))
    sk.set("legL", "front", 0, 0, hexc(buckle))


def strap(sk, colour, layer=1, thin=False):
    """Shoulder strap from the right shoulder to the left hip, front and back."""
    c = ramp(colour)
    if thin:
        c = [c[2]] * 5
    for i in range(8):
        y = min(11, round(i * 1.45))
        sk.set("body", "front", i, y, c[2], layer)
        sk.set("body", "back", 7 - i, y, c[2], layer)
        if not thin:
            sk.set("body", "front", i, min(11, y + 1), c[1], layer)
            sk.set("body", "back", 7 - i, min(11, y + 1), c[1], layer)


def satchel(sk, colour):
    strap(sk, colour)
    r = ramp(colour)
    sk.paint("body", r, 1, faces=("left",), rows=range(8, 12), noise=0.4)
    sk.paint("legL", r, 1, faces=("left",), rows=range(0, 3), noise=0.4)
    for x in range(4):
        sk.set("legL", "left", x, 2, r[0], 1)
    sk.set("body", "left", 2, 9, r[4], 1)


def backpack(sk, colour, accent=None, big=False):
    r = ramp(colour)
    a = ramp(accent) if accent else r
    lo, hi = (0, 11) if big else (1, 9)
    sk.paint("body", r, 1, faces=("back",), where=lambda f, x, y: lo <= y <= hi and (big or 1 <= x <= 6), noise=0.4)
    for x in range(2, 6):
        sk.set("body", "back", x, hi - 3, a[1], 1)
        sk.set("body", "back", x, hi - 1, a[1], 1)
    for y in range(0, 8):
        sk.set("body", "front", 1, y, r[1], 1)
        sk.set("body", "front", 6, y, r[1], 1)
    for f in ("right", "left"):
        for x in range(4):
            sk.set("body", f, x, 0, r[1], 1)


def scarf(sk, colour, accent=None, tail=True):
    r = ramp(colour)
    a = ramp(accent) if accent else r
    sk.paint("body", r, 1, rows=range(0, 2), faces=SIDES, noise=0.5)
    if accent:
        for f in SIDES:
            for x in range(0, sk.size("body", f)[0], 2):
                sk.set("body", f, x, 1, a[2], 1)
    if tail:
        for y in range(2, 6):
            sk.set("body", "front", 5, y, r[2] if y < 5 else a[1], 1)
            sk.set("body", "front", 6, y, r[1] if y < 5 else a[1], 1)


def hands(sk, skin):
    for p in ARMS:
        sk.paint(p, skin, rows=range(10, 12), noise=0.35)


def shoes(sk, kind, colour, sole="#F1F1EC"):
    r, s = ramp(colour), ramp(sole)
    if kind == "sneakers":
        legs(sk, r, rows=range(10, 12), noise=0.35)
        for p in LEGS:
            sk.ring(p, 11, s[2])
            sk.set(p, "front", 1, 10, s[3])
            sk.set(p, "front", 2, 10, s[3])
            sk.paint(p, s, faces=("bottom",), noise=0.2, level=-1)
    elif kind == "leather":
        legs(sk, r, rows=range(10, 12), noise=0.3)
        for p in LEGS:
            sk.set(p, "front", 1, 10, r[4])
            sk.ring(p, 11, r[0])
    elif kind == "boots":
        legs(sk, r, rows=range(8, 12), noise=0.4)
        for p in LEGS:
            sk.ring(p, 8, r[3])
            sk.ring(p, 11, r[0])
            sk.set(p, "front", 1, 9, r[0])
            sk.set(p, "front", 2, 10, r[0])
    elif kind == "flats":
        legs(sk, r, rows=range(11, 12), noise=0.3)
        for p in LEGS:
            for x in range(4):
                sk.set(p, "front", x, 10, r[2])


def pants(sk, colour, kind="plain"):
    r = ramp(colour)
    legs(sk, r)
    for p in LEGS:
        o = OUTER[p]
        if kind in ("jeans", "chino"):
            for y in range(1, 10):                      # outer seam
                sk.set(p, o, 1, y, r[1])
            sk.set(p, "front", 0 if p == "legR" else 3, 1, r[1])       # pocket opening
            sk.set(p, "front", 1 if p == "legR" else 2, 2, r[1])
            for x in (1, 2):
                sk.set(p, "back", x, 2, r[1])                          # back pocket
                sk.set(p, "back", x, 4, r[1])
        if kind == "jeans":
            for x in (1, 2):
                sk.set(p, "front", x, 5, r[3])                          # worn knee
                sk.set(p, "front", x, 6, r[4] if x == 1 else r[3])
        if kind == "crease":
            for y in range(1, 10):
                sk.set(p, "front", 1 if p == "legR" else 2, y, r[3])
    return r


# --------------------------------------------------------------------------- tops
def top_tee(sk, skin, colour, sleeve=4, neck=1, motif=None):
    r = ramp(colour)
    sk.paint("body", r)
    arms(sk, r, rows=range(0, sleeve))
    for p in ARMS:
        if sleeve < 10:
            sk.ring(p, sleeve - 1, r[1])
    neckline(sk, skin, neck)
    if motif:
        m = hexc(motif)
        for x, y in ((3, 4), (4, 4), (2, 5), (3, 5), (4, 5), (5, 5), (3, 6), (4, 6)):
            sk.set("body", "front", x, y, m)
    return r


def top_shirt(sk, skin, colour, sleeve=10, button="#E8E8E8", open_neck=True):
    r = ramp(colour)
    sk.paint("body", r)
    arms(sk, r, rows=range(0, sleeve))
    if sleeve >= 10:
        for p in ARMS:
            sk.ring(p, 9, r[1])
    else:
        for p in ARMS:
            sk.ring(p, sleeve - 1, r[4])                # rolled sleeve
    if open_neck:
        neckline(sk, skin, 2)
    collar(sk, colour)
    buttons(sk, button, rows=(3, 5, 7, 9), placket=r[1])
    return r


def top_sweater(sk, skin, colour, under=None, rib=True):
    r = ramp(colour)
    sk.paint("body", r, noise=0.7)
    arms(sk, r, rows=range(0, 10), noise=0.7)
    if rib:
        for p in ("body",) + ARMS:
            y = 11 if p == "body" else 9
            for f in SIDES:
                for x in range(sk.size(p, f)[0]):
                    sk.set(p, f, x, y, r[1] if x % 2 else r[2])
    if under:
        collar(sk, under)
        sk.set("body", "front", 3, 0, ramp(under)[2])
        sk.set("body", "front", 4, 0, ramp(under)[2])
    else:
        neckline(sk, skin, 1, wide=True)
        for x in (1, 6):
            sk.set("body", "front", x, 0, r[1])
    return r


def top_hoodie(sk, skin, colour, zipper=None):
    r = ramp(colour)
    sk.paint("body", r, noise=0.65)
    arms(sk, r, rows=range(0, 10), noise=0.65)
    for p in ARMS:
        sk.ring(p, 9, r[1])
    sk.ring("body", 11, r[1])
    neckline(sk, skin, 1)
    for x in range(2, 6):                               # kangaroo pocket
        sk.set("body", "front", x, 7, r[1])
    sk.set("body", "front", 1, 8, r[1])
    sk.set("body", "front", 6, 8, r[1])
    sk.set("body", "front", 1, 9, r[1])
    sk.set("body", "front", 6, 9, r[1])
    for y in (1, 2, 3):                                 # drawstrings
        sk.set("body", "front", 3, y, r[4])
        sk.set("body", "front", 4, y, r[4] if y < 3 else r[2])
    sk.paint("body", r, 1, faces=("back",), where=lambda f, x, y: y <= 2 and (y < 2 or 1 <= x <= 6), level=-0.6,
             noise=0.4)                                 # hood resting on the back
    for f in ("right", "left"):
        for x in range(4):
            sk.set("body", f, x, 0, r[1], 1)
    if zipper:
        for y in range(1, 12):
            sk.set("body", "front", 4, y, hexc(zipper))
    return r


def top_suit(sk, skin, jacket, shirt="#F2F2F0", tie=None, pocket=None):
    j, s = ramp(jacket), ramp(shirt)
    sk.paint("body", j, noise=0.4)
    arms(sk, j, rows=range(0, 10), noise=0.4)
    for p in ARMS:
        sk.ring(p, 9, s[3])
    for x, y in ((2, 0), (3, 0), (4, 0), (5, 0), (3, 1), (4, 1), (3, 2), (4, 2), (3, 3), (4, 3)):
        sk.set("body", "front", x, y, s[3] if y < 2 else s[2])
    for x, y in ((1, 0), (6, 0), (2, 1), (5, 1), (2, 2), (5, 2), (2, 3), (5, 3), (3, 4), (4, 4)):
        sk.set("body", "front", x, y, j[4] if y < 4 else j[3])        # lapels
    if tie:
        t = ramp(tie)
        for y in range(0, 5):
            sk.set("body", "front", 3, y, t[1] if y == 0 else t[2])
            sk.set("body", "front", 4, y, t[1] if y == 0 else t[3])
    else:
        sk.set("body", "front", 3, 0, skin[1])
        sk.set("body", "front", 4, 0, skin[1])
    sk.set("body", "front", 4, 6, j[0])
    sk.set("body", "front", 4, 8, j[0])
    if pocket:
        sk.set("body", "front", 6, 2, hexc(pocket))
    for x in range(8):
        sk.set("body", "back", x, 0, s[3] if 2 <= x <= 5 else j[3])
    for y in range(7, 12):
        sk.set("body", "back", 4, y, j[0])               # back vent
    return j


def top_blazer(sk, skin, jacket, under, under_neck=1):
    j, u = ramp(jacket), ramp(under)
    sk.paint("body", j, noise=0.4)
    arms(sk, j, rows=range(0, 10), noise=0.4)
    for y in range(0, 12):
        for x in (3, 4):
            sk.set("body", "front", x, y, u[2] if y else u[1])
    for y in range(0, 4):
        sk.set("body", "front", 2, y, j[4])
        sk.set("body", "front", 5, y, j[4])
    sk.set("body", "front", 2, 0, u[2])
    sk.set("body", "front", 5, 0, u[2])
    neckline(sk, skin, under_neck)
    sk.set("body", "front", 6, 7, j[1])
    sk.set("body", "front", 1, 7, j[1])
    return j


def top_coat(sk, skin, colour, button="#3A2F28", belt_colour=None, length=5, double=True):
    """Long coat: body and arms, then the top of the legs on the second layer."""
    r = ramp(colour)
    sk.paint("body", r, noise=0.45)
    arms(sk, r, rows=range(0, 10), noise=0.45)
    for p in ARMS:
        sk.ring(p, 8, r[1])
    for p in LEGS:
        sk.paint(p, r, 1, rows=range(0, length), faces=SIDES, noise=0.45)
        sk.ring(p, length - 1, r[1], 1)
    for y in range(0, 12):
        sk.set("body", "front", 4, y, r[1])
    for y in range(length):
        sk.set("legL", "front", 0, y, r[0], 1)
    collar(sk, colour)
    for x, y in ((2, 1), (5, 1), (2, 2), (5, 2)):
        sk.set("body", "front", x, y, r[4])
    neckline(sk, skin, 1)
    b = hexc(button)
    for y in (4, 6, 9):
        sk.set("body", "front", 3, y, b)
        if double:
            sk.set("body", "front", 5, y, b)
    if belt_colour:
        bc = ramp(belt_colour)
        sk.ring("body", 8, bc[2])
        sk.set("body", "front", 3, 8, bc[4])
        sk.set("body", "front", 4, 8, bc[0])
    return r


def top_puffer(sk, skin, colour, zipper="#D8D8D8"):
    r = ramp(colour)
    sk.paint("body", r, noise=0.35, level=0.2)
    arms(sk, r, rows=range(0, 10), noise=0.35, level=0.2)
    for p, ys in (("body", (2, 5, 8, 11)), ("armR", (2, 5, 8)), ("armL", (2, 5, 8))):
        for y in ys:
            sk.ring(p, y, r[0])
            if y + 1 < 12 and not (p != "body" and y + 1 > 9):
                sk.ring(p, y + 1, r[4])
    for p in ARMS:
        sk.ring(p, 9, r[0])
    for y in range(0, 12):
        sk.set("body", "front", 4, y, hexc(zipper) if y % 2 else mix(hexc(zipper), (0, 0, 0), 0.3))
    sk.paint("body", r, 1, rows=range(0, 1), faces=SIDES, noise=0.3, level=0.4)      # padded collar
    return r


def top_apron(sk, colour, skirt=4, bib=True, pocket=True):
    """Apron over what is already painted: bib on the chest, skirt on the legs (second layer), ties at the back."""
    r = ramp(colour)
    if bib:
        sk.paint("body", r, 1, faces=("front",), where=lambda f, x, y: 1 <= x <= 6 and y >= 2, noise=0.35)
        for y in (0, 1):
            sk.set("body", "front", 2, y, r[1], 1)
            sk.set("body", "front", 5, y, r[1], 1)
        for x in range(8):
            sk.set("body", "back", x, 0, r[1], 1) if x in (2, 5) else None
    else:
        sk.paint("body", r, 1, faces=("front",), where=lambda f, x, y: y >= 9, noise=0.35)
    y_tie = 7 if bib else 9
    sk.ring("body", y_tie, r[1], 1, faces=("right", "left", "back"))
    sk.set("body", "back", 3, y_tie + 1, r[2], 1)
    sk.set("body", "back", 4, y_tie + 1, r[2], 1)
    for p in LEGS:
        sk.paint(p, r, 1, faces=("front", OUTER[p]), rows=range(0, skirt), noise=0.35,
                 where=lambda f, x, y, p=p: f == "front" or (x >= 2 if p == "legL" and f == "left" else
                                                              x <= 1 if p == "legL" else
                                                              x >= 2 if f == "right" else True))
        for x in range(4):
            sk.set(p, "front", x, skirt - 1, r[1], 1)
    if pocket and bib:
        for x in (3, 4):
            sk.set("body", "front", x, 9, r[1], 1)
        sk.set("body", "front", 2, 10, r[1], 1)
        sk.set("body", "front", 5, 10, r[1], 1)
    return r


def top_vest(sk, colour, band="#D6D9DC", hivis=True):
    """Sleeveless vest over the top already painted (second layer)."""
    r = ramp(colour)
    sk.paint("body", r, 1, faces=SIDES, where=lambda f, x, y: not (f == "front" and x in (3, 4) and y <= 2),
             noise=0.3, level=0.2 if hivis else 0)
    if hivis:
        b = ramp(band)
        for y in (4, 8):
            sk.ring("body", y, b[3], 1)
        for y in range(0, 4):
            sk.set("body", "front", 1, y, b[3], 1)
            sk.set("body", "front", 6, y, b[3], 1)
            sk.set("body", "back", 1, y, b[3], 1)
            sk.set("body", "back", 6, y, b[3], 1)
    else:
        for y in (5, 7, 9):
            sk.set("body", "front", 4, y, r[0], 1)
        for y in range(3, 12):
            sk.set("body", "front", 3, y, r[1], 1)
    return r


def top_dress(sk, skin, colour, sleeve=2, skirt=6, dots=None, neck=2):
    r = ramp(colour)
    sk.paint("body", r, noise=0.5)
    if sleeve:
        arms(sk, r, rows=range(0, sleeve))
    else:
        for x in (0, 7):
            sk.set("body", "front", x, 0, skin[2])
    neckline(sk, skin, neck, wide=True)
    sk.ring("body", 7, r[1])
    for p in LEGS:
        sk.paint(p, r, 0, rows=range(0, skirt - 1), noise=0.5)
        sk.paint(p, r, 1, rows=range(0, skirt), faces=SIDES, noise=0.5)
        for f in SIDES:
            for x in range(4):
                if x % 2:
                    for y in range(1, skirt):
                        sk.set(p, f, x, y, r[1], 1)          # pleats
        sk.ring(p, skirt - 1, r[1], 1)
    if dots:
        d = hexc(dots)
        for p, f in (("body", "front"), ("body", "back"), ("legR", "front"), ("legL", "front"), ("legR", "back"),
                     ("legL", "back")):
            w, h = sk.size(p, f)
            for y in range(1, 12 if p == "body" else skirt - 1):
                for x in range(w):
                    if (x * 2 + y * 3) % 5 == 0 and (p != "body" or not (y < 2 and 2 <= x <= 5)):
                        sk.set(p, f, x, y, d, 1 if p != "body" else 0)
    return r


def skirt(sk, colour, length=5, pleats=True):
    r = ramp(colour)
    for p in LEGS:
        sk.paint(p, r, 0, rows=range(0, length - 1), noise=0.45)
        sk.paint(p, r, 1, rows=range(0, length), faces=SIDES, noise=0.45)
        if pleats:
            for f in SIDES:
                for x in range(1, 4, 2):
                    for y in range(1, length):
                        sk.set(p, f, x, y, r[1], 1)
        sk.ring(p, length - 1, r[1], 1)
        sk.ring(p, 0, r[1], 1)
    return r


def overalls(sk, colour, button="#C9B36A"):
    """Work overalls over the top already painted: legs, bib and straps."""
    r = pants(sk, colour, "chino")
    sk.paint("body", r, 0, faces=SIDES, rows=range(8, 12), noise=0.5)
    sk.paint("body", r, 0, faces=("front",), where=lambda f, x, y: 1 <= x <= 6 and y >= 3, noise=0.5)
    for y in range(0, 3):
        sk.set("body", "front", 1, y, r[1])
        sk.set("body", "front", 6, y, r[1])
    for y in range(0, 8):
        sk.set("body", "back", 1 + (y // 3), y, r[1])
        sk.set("body", "back", 6 - (y // 3), y, r[1])
    sk.set("body", "front", 1, 3, hexc(button))
    sk.set("body", "front", 6, 3, hexc(button))
    for x in (3, 4):
        sk.set("body", "front", x, 5, r[1])
        sk.set("body", "front", x, 7, r[1])
    return r


# --------------------------------------------------------------------------- the thirty inhabitants
SKIN = {"porcelaine": "#F3D3BC", "clair": "#E8B998", "miel": "#D49F78", "hale": "#BD8660", "brun": "#946244",
        "ebene": "#6A4332"}
HAIR = {"noir": "#27222A", "brun": "#4A3024", "chatain": "#7A5234", "blond": "#D6AE62", "roux": "#B5522A",
        "auburn": "#7C3324", "gris": "#9FA0A6", "blanc": "#DADADF", "platine": "#E9DDB8", "bleu_nuit": "#2B3350"}
JEAN, JEAN_DARK, JEAN_LIGHT = "#3F5F8F", "#2B3D5E", "#7C9CC2"


def base(seed, tone, hair_style, hair_colour, **face):
    sk = Skin(seed)
    skin = ramp(SKIN[tone])
    for p in DIMS:
        sk.paint(p, skin, noise=0.28)
    h = paint_hair(sk, hair_style, HAIR[hair_colour], skin)
    paint_face(sk, skin, h, **face)
    return sk, skin


def boulangere(n):
    sk, skin = base(n, "clair", "bun", "chatain", blush=True, lips="#C06A66")
    r = top_shirt(sk, skin, "#E9EEF4", sleeve=6, button="#B9C6D6")
    stripes(sk, ("body",) + ARMS, "#7FA3CC", period=2, rows=range(1, 12), on=r)
    hands(sk, skin)
    pants(sk, "#3A3F4C")
    top_apron(sk, "#F4F1EA", skirt=5)
    shoes(sk, "sneakers", "#EDEDED")
    hat(sk, "toque", "#F6F4EE")
    return sk


def serveur(n):
    sk, skin = base(n, "miel", "part", "noir", beard="moustache")
    top_shirt(sk, skin, "#F1F1EE", open_neck=False)
    hands(sk, skin)
    pants(sk, "#1F1F25", "crease")
    top_vest(sk, "#1E1E24", hivis=False)
    for x, c in ((2, "#16161A"), (3, "#2A2A30"), (4, "#2A2A30"), (5, "#16161A")):     # bow tie
        sk.set("body", "front", x, 0, hexc(c), 1)
    top_apron(sk, "#17171B", skirt=7, bib=False)
    shoes(sk, "leather", "#1A1512")
    return sk


def homme_affaires(n):
    sk, skin = base(n, "clair", "part", "brun")
    top_suit(sk, skin, "#27324A", tie="#9E2B33", pocket="#F2F2F0")
    hands(sk, skin)
    pants(sk, "#27324A", "crease")
    belt(sk)
    shoes(sk, "leather", "#3A2416")
    return sk


def femme_affaires(n):
    sk, skin = base(n, "porcelaine", "bun", "blond", lips="#B8484E")
    top_blazer(sk, skin, "#6F7480", "#F4F2EE", under_neck=2)
    hands(sk, skin)
    pants(sk, "#22232B", "crease")
    shoes(sk, "flats", "#1B1B20")
    glasses(sk, "#B08A4A")
    sk.set("body", "front", 3, 2, hexc("#D9B45A"))                                       # pendant
    return sk


def etudiant(n):
    sk, skin = base(n, "ebene", "curly", "noir")
    top_hoodie(sk, skin, "#8F949C")
    hands(sk, skin)
    pants(sk, JEAN_DARK, "jeans")
    shoes(sk, "sneakers", "#C8423A")
    backpack(sk, "#B73A36", "#7E2422")
    hat(sk, "headphones", "#26262C", "#D94F45")
    return sk


def etudiante(n):
    sk, skin = base(n, "clair", "long_fringe", "auburn", eyes="#3F6B4E", lips="#C26864", blush=True)
    r = top_tee(sk, skin, "#F3F1EA", sleeve=6, neck=1)
    stripes(sk, ("body",) + ARMS, "#26375E", period=2, rows=range(2, 12), on=r)
    pants(sk, JEAN_LIGHT, "jeans")
    for p in LEGS:
        sk.ring(p, 9, ramp(JEAN_LIGHT)[4])                                               # turned-up hem
    shoes(sk, "sneakers", "#F2F2F0", sole="#DCDCD6")
    satchel(sk, "#C9A66B")
    return sk


def peintre(n):
    sk, skin = base(n, "clair", "fringe", "gris", beard="short", old=True)
    r = top_shirt(sk, skin, "#D9CDB4", sleeve=7, button="#8A7B62")
    for (x, y), c in zip(((1, 5), (6, 3), (2, 9), (5, 7), (6, 10), (1, 2)),
                         ("#D9483B", "#3F7FC4", "#E8B83A", "#4FA060", "#D9483B", "#8A4FB0")):
        sk.set("body", "front", x, y, hexc(c))
    sk.set("armR", "front", 1, 4, hexc("#3F7FC4"))
    sk.set("armL", "front", 2, 2, hexc("#E8B83A"))
    hands(sk, skin)
    pants(sk, "#4A4038", "chino")
    sk.set("legR", "front", 1, 4, hexc("#D9483B"))
    sk.set("legL", "front", 2, 7, hexc("#F2F2F0"))
    shoes(sk, "leather", "#5A3A26")
    scarf(sk, "#B5322F", tail=False)
    hat(sk, "beret", "#1F1F26")
    return sk


def retraite(n):
    sk, skin = base(n, "clair", "balding", "blanc", beard="moustache", old=True, brows=True)
    top_shirt(sk, skin, "#DCE6F0", open_neck=False, button="#9AA9BC")
    j = ramp("#4E6B4A")
    sk.paint("body", j, 1, faces=SIDES, where=lambda f, x, y: not (f == "front" and x in (3, 4)), noise=0.7)
    arms(sk, j, rows=range(0, 10), layer=1, noise=0.7)
    for y in (4, 7, 10):
        sk.set("body", "front", 5, y, hexc("#D9C9A0"), 1)
    for p in ARMS:
        sk.ring(p, 9, j[1], 1)
    hands(sk, skin)
    pants(sk, "#B9A27C", "chino")
    belt(sk, "#5A3A26")
    shoes(sk, "leather", "#4A2E1C")
    glasses(sk, "#6B5A3A")
    hat(sk, "flatcap", "#6A6258")
    return sk


def retraitee(n):
    sk, skin = base(n, "porcelaine", "curly", "blanc", old=True, lips="#B8626A", blush=True)
    top_sweater(sk, skin, "#B79AD0", rib=True)
    for x in (2, 3, 4, 5):                                                              # pearl necklace
        sk.set("body", "front", x, 1, hexc("#F4F0E6"))
    hands(sk, skin)
    legs(sk, ramp("#C9B8A8"), rows=range(0, 10), noise=0.3)                               # stockings
    skirt(sk, "#5B4A66", length=8)
    shoes(sk, "flats", "#3A2A30")
    glasses(sk, "#A8324A")
    return sk


def joggeuse(n):
    sk, skin = base(n, "hale", "pony", "blond", lips="#C0625A")
    r = ramp("#E8457A")
    sk.paint("body", r, rows=range(0, 9), noise=0.4)
    neckline(sk, skin, 2, wide=True)
    for x in (0, 7):
        sk.set("body", "front", x, 0, skin[2])
        sk.set("body", "back", x, 0, skin[2])
    sk.ring("body", 8, r[4])
    for y in range(1, 8):
        sk.set("body", "right", 1, y, hexc("#F4F4F4"))
        sk.set("body", "left", 2, y, hexc("#F4F4F4"))
    legs(sk, ramp("#22232B"), rows=range(0, 9), noise=0.3)
    for p in LEGS:
        for y in range(0, 9):
            sk.set(p, OUTER[p], 1, y, hexc("#E8457A"))
        sk.ring(p, 0, ramp("#22232B")[4])
        sk.ring(p, 9, hexc("#F4F4F4"))                                                   # sock
    shoes(sk, "sneakers", "#49C3D8")
    for f in ("front", OUTER["armL"], "back", INNER["armL"]):                          # phone armband
        for x in range(4):
            sk.set("armL", f, x, 3, hexc("#26262C"), 1)
            sk.set("armL", f, x, 4, hexc("#26262C"), 1)
    sk.set("armL", "left", 1, 3, hexc("#7FD4E8"), 1)
    sk.set("armL", "left", 2, 3, hexc("#7FD4E8"), 1)
    for x in range(8):                                                                  # headband
        for f in SIDES:
            sk.set("head", f, x, 1, hexc("#F4F4F4"), 1)
    return sk


def livreur(n):
    sk, skin = base(n, "brun", "short", "noir", beard="stubble")
    r = top_puffer(sk, skin, "#19A7A0")
    hands(sk, skin)
    legs(sk, ramp("#2B2D36"), rows=range(0, 6), noise=0.4)
    for p in LEGS:
        sk.ring(p, 5, ramp("#2B2D36")[1])
        sk.ring(p, 9, hexc("#E9E9E4"))
        sk.ring(p, 8, hexc("#E9E9E4"))
    shoes(sk, "sneakers", "#2B2D36")
    backpack(sk, "#12807A", "#F4F4F0", big=True)
    b = ramp("#12807A")
    sk.paint("body", b, 1, faces=("right", "left"), where=lambda f, x, y: (x <= 1 if f == "right" else x >= 2),
             noise=0.3)
    hat(sk, "helmet", "#2A2C34")
    return sk


def fleuriste(n):
    sk, skin = base(n, "porcelaine", "long", "roux", eyes="#4A7A4E", lips="#C2605C", blush=True)
    top_tee(sk, skin, "#F2EDE2", sleeve=5, neck=1)
    pants(sk, JEAN, "jeans")
    r = top_apron(sk, "#4F7D4C", skirt=6)
    for (x, y), c in (((2, 4), "#E86A8A"), ((5, 5), "#F1C84B"), ((3, 6), "#F4F1EA")):      # embroidered flowers
        sk.set("body", "front", x, y, hexc(c), 1)
    sk.set("armR", "front", 1, 6, hexc("#E86A8A"), 1)
    shoes(sk, "boots", "#7A5234")
    sk.set("head", "right", 6, 2, hexc("#E86A8A"), 1)                                     # flower in the hair
    sk.set("head", "right", 5, 2, hexc("#F1C84B"), 1)
    return sk


def ouvrier(n):
    sk, skin = base(n, "hale", "short", "brun", beard="stubble")
    top_tee(sk, skin, "#7C8088", sleeve=4)
    pants(sk, "#4B5563", "chino")
    for p in LEGS:                                                                       # knee patches, side pocket
        for x in (1, 2):
            sk.set(p, "front", x, 5, ramp("#4B5563")[0])
            sk.set(p, "front", x, 6, ramp("#4B5563")[0])
        for y in (3, 4):
            sk.set(p, OUTER[p], 2, y, ramp("#4B5563")[0])
    shoes(sk, "boots", "#8A5A2B")
    top_vest(sk, "#F28A1E")
    for p in ARMS:                                                                       # work gloves
        sk.paint(p, ramp("#C9A25A"), rows=range(9, 12), noise=0.4)
    hat(sk, "helmet", "#F1C232")
    return sk


def touriste(n):
    sk, skin = base(n, "clair", "short", "blond")
    r = top_shirt(sk, skin, "#D9483B", sleeve=4, button="#F4E9D0")
    for p, f in (("body", "front"), ("body", "back"), ("armR", "front"), ("armL", "front"), ("body", "right"),
                 ("body", "left")):
        w, h = sk.size(p, f)
        for y in range(1, 12 if p == "body" else 4):
            for x in range(w):
                k = (x * 3 + y * 5) % 7
                if k == 0 and not (f == "front" and p == "body" and x == 4):
                    sk.set(p, f, x, y, hexc("#F4E9D0"))
                elif k == 3 and not (f == "front" and p == "body" and x == 4):
                    sk.set(p, f, x, y, hexc("#3FA06A"))
    legs(sk, ramp("#C9B48A"), rows=range(0, 6), noise=0.4)
    for p in LEGS:
        sk.ring(p, 5, ramp("#C9B48A")[1])
        sk.ring(p, 9, hexc("#F2F2EE"))
    shoes(sk, "sneakers", "#8A6A4A", sole="#E4D9C0")
    strap(sk, "#2A2A30", thin=True)
    for x, y, c in ((3, 6, "#2A2A30"), (4, 6, "#2A2A30"), (3, 7, "#2A2A30"), (4, 7, "#8FB4D9"), (5, 6, "#55555E")):
        sk.set("body", "front", x, y, hexc(c), 1)                                         # camera
    glasses(sk, "#1E1E24", sun=True)
    hat(sk, "cap", "#F2EFE6", "#D9483B")
    return sk


def facteur(n):
    sk, skin = base(n, "miel", "short", "chatain")
    r = top_shirt(sk, skin, "#2F4C8A", sleeve=10, button="#F1C232", open_neck=False)
    y_ = ramp("#F1C232")
    sk.ring("body", 3, y_[2])
    for p in ARMS:
        sk.ring(p, 3, y_[2])
    sk.set("body", "front", 6, 1, y_[3])
    hands(sk, skin)
    pants(sk, "#243A66", "crease")
    belt(sk, "#1D1D22")
    shoes(sk, "leather", "#1D1D22")
    satchel(sk, "#8A5A2B")
    sk.set("body", "left", 1, 8, hexc("#F4F1EA"), 1)                                       # letters sticking out
    sk.set("body", "left", 2, 8, hexc("#E4DCC8"), 1)
    hat(sk, "cap", "#2F4C8A", "#F1C232")
    return sk


def infirmiere(n):
    sk, skin = base(n, "brun", "bun", "noir", lips="#9A4A48")
    r = top_tee(sk, skin, "#7FC4C8", sleeve=5, neck=2)
    for x, y in ((2, 0), (5, 0), (3, 2), (4, 2)):
        sk.set("body", "front", x, y, r[1])
    for x in (5, 6):
        sk.set("body", "front", x, 3, r[1])
        sk.set("body", "front", x, 5, r[1])
    sk.set("body", "front", 6, 4, hexc("#D9483B"))                                         # pen in the pocket
    for x in (1, 2):
        sk.set("body", "front", x, 3, hexc("#F4F4F0"))                                     # badge
    pants(sk, "#7FC4C8")
    for p in LEGS:
        sk.ring(p, 0, r[1])
    shoes(sk, "sneakers", "#F4F4F0", sole="#D9D9D4")
    for x, y in ((2, 1), (1, 2), (1, 3), (5, 1), (6, 2), (6, 3), (5, 4), (4, 5)):          # stethoscope
        sk.set("body", "front", x, y, hexc("#2A2A30"), 1)
    sk.set("body", "front", 4, 6, hexc("#C9CCD2"), 1)
    return sk


def chef(n):
    sk, skin = base(n, "hale", "buzz", "noir", beard="short")
    r = ramp("#F5F3EE")
    sk.paint("body", r, noise=0.3)
    arms(sk, r, rows=range(0, 8), noise=0.3)
    for p in ARMS:
        sk.ring(p, 7, r[1])
    collar(sk, "#F5F3EE")
    for y in range(1, 12):
        sk.set("body", "front", 5, y, r[1])
    for y in (2, 4, 6, 8):
        sk.set("body", "front", 2, y, hexc("#2A2A30"))
        sk.set("body", "front", 4, y, hexc("#2A2A30"))
    a, b = ramp("#2B2D36"), ramp("#E9E9E4")
    for p in LEGS:                                                                        # houndstooth trousers
        for f in FACES:
            w, h = sk.size(p, f)
            for y in range(h):
                for x in range(w):
                    sk.set(p, f, x, y, (a if (x + y) % 2 else b)[1 if INNER[p] == f else 2])
    top_apron(sk, "#F5F3EE", skirt=5, bib=False)
    shoes(sk, "leather", "#1D1D22")
    sk.paint("body", ramp("#C8423A"), 1, rows=range(0, 1), faces=SIDES, noise=0.3)          # neckerchief
    sk.set("body", "front", 3, 1, ramp("#C8423A")[1], 1)
    hat(sk, "toque", "#F8F6F1")
    return sk


def hipster(n):
    sk, skin = base(n, "clair", "short", "roux", beard="full")
    r = top_shirt(sk, skin, "#B5322F", sleeve=7, button="#1D1D22")
    k = ramp("#1F1F26")
    for p in ("body",) + ARMS:                                                            # plaid
        for f in SIDES:
            w, h = sk.size(p, f)
            for y in range(h):
                for x in range(w):
                    if sk.get(p, f, x, y) in r:
                        if y % 3 == 1 and x % 3 == 1:
                            sk.set(p, f, x, y, k[2])
                        elif y % 3 == 1 or x % 3 == 1:
                            sk.set(p, f, x, y, mix(r[1], k[2], 0.45))
    hands(sk, skin)
    pants(sk, JEAN_DARK, "jeans")
    for p in LEGS:
        sk.ring(p, 7, ramp(JEAN_DARK)[4])                                               # turn-ups
    shoes(sk, "boots", "#6A4426")
    belt(sk, "#4A2E1C")
    glasses(sk, "#1D1D22")
    hat(sk, "beanie", "#D9A441", "#B8842C")
    return sk


def parisienne(n):
    sk, skin = base(n, "porcelaine", "bob_fringe", "noir", lips="#B3262E")
    top_coat(sk, skin, "#C9AE82", belt_colour="#A88C60", length=6)
    hands(sk, skin)
    pants(sk, "#1F1F26", "crease")
    shoes(sk, "boots", "#1D1A1C")
    scarf(sk, "#F2EEE4", "#B3262E", tail=False)
    sk.paint("body", ramp("#C9AE82"), 0, rows=range(0, 1), faces=("front",), where=lambda f, x, y: False)
    hat(sk, "beret", "#B3262E")
    return sk


def skateur(n):
    sk, skin = base(n, "miel", "fringe", "chatain")
    top_tee(sk, skin, "#F1C232", sleeve=6, neck=1, motif="#1F1F26")
    for p in ARMS:                                                                        # long sleeves under the tee
        sk.paint(p, ramp("#F4F4F0"), rows=range(6, 10), noise=0.3)
    hands(sk, skin)
    pants(sk, "#6E8FB5", "jeans")
    for p in LEGS:
        sk.paint(p, ramp("#6E8FB5"), 1, rows=range(3, 10), faces=SIDES, noise=0.5)       # baggy
    shoes(sk, "sneakers", "#1F1F26")
    for x in range(1, 4):                                                                 # wallet chain
        sk.set("legR", "front", x - 1, 1 + x // 2, hexc("#C9CCD2"), 1)
    hat(sk, "capback", "#1F1F26", "#C8423A")
    return sk


def maman(n):
    sk, skin = base(n, "hale", "bob", "brun", lips="#B55A58")
    r = top_tee(sk, skin, "#F3F1EA", neck=2)
    stripes(sk, ("body",), "#C8423A", period=3, rows=range(2, 12), on=r)
    d = ramp("#5C7FAE")
    sk.paint("body", d, 1, faces=SIDES, where=lambda f, x, y: not (f == "front" and 2 <= x <= 5), noise=0.6)
    arms(sk, d, rows=range(0, 10), layer=1, noise=0.6)
    for y in range(0, 12):
        sk.set("body", "front", 1, y, d[1] if y not in (3, 4) else d[4], 1)
        sk.set("body", "front", 6, y, d[1] if y not in (3, 4) else d[4], 1)
    for p in ARMS:
        sk.ring(p, 9, d[4], 1)
    sk.ring("body", 11, d[4], 1, faces=("back", "right", "left"))
    for x in range(8):
        sk.set("body", "back", x, 3, d[1], 1)
    hands(sk, skin)
    pants(sk, "#2A2B33")
    shoes(sk, "sneakers", "#F2F2F0", sole="#DADAD4")
    satchel(sk, "#8A4A2A")
    for f in ("right", "left"):                                                           # earrings
        sk.set("head", f, 7 if f == "right" else 0, 5, hexc("#E8C25A"), 1)
    return sk


def doudoune(n):
    sk, skin = base(n, "ebene", "buzz", "noir", beard="short")
    top_puffer(sk, skin, "#2D3F6B")
    hands(sk, skin)
    pants(sk, "#55565E", "chino")
    shoes(sk, "sneakers", "#F2F2F0")
    scarf(sk, "#B9B4A8", "#7C776C", tail=False)
    hat(sk, "beanie", "#3A3A42", "#55565E")
    return sk


def voilee(n):
    sk, skin = base(n, "miel", "bald", "noir", lips="#B0605C", blush=True)
    top_coat(sk, skin, "#6B7A52", button="#3D4630", length=7, double=False)
    hands(sk, skin)
    pants(sk, JEAN, "jeans")
    shoes(sk, "sneakers", "#F2F2F0", sole="#DADAD4")
    hat(sk, "hijab", "#D9A5A0")
    satchel(sk, "#3A2A26")
    return sk


def musicien(n):
    sk, skin = base(n, "clair", "long", "brun", beard="stubble")
    top_tee(sk, skin, "#F1EFEA", neck=2)
    for x, y in ((3, 4), (4, 4), (2, 5), (5, 5), (3, 6), (4, 6)):
        sk.set("body", "front", x, y, hexc("#1F1F26"))
    j = ramp("#26242A")
    sk.paint("body", j, 1, faces=SIDES, where=lambda f, x, y: not (f == "front" and 2 <= x <= 5), noise=0.4)
    arms(sk, j, rows=range(0, 10), layer=1, noise=0.4)
    for y in range(0, 5):
        sk.set("body", "front", 1, y, j[4], 1)
        sk.set("body", "front", 6, y, j[4], 1)
    sk.set("body", "front", 6, 7, hexc("#C9CCD2"), 1)
    sk.set("body", "front", 1, 7, hexc("#C9CCD2"), 1)
    for p in ARMS:
        sk.ring(p, 9, j[0], 1)
        sk.set(p, OUTER[p], 1, 8, hexc("#C9CCD2"), 1)
    hands(sk, skin)
    pants(sk, "#1F1F26", "jeans")
    shoes(sk, "boots", "#1A1718")
    g = ramp("#B8743A")                                                                   # guitar on the back
    sk.paint("body", g, 1, faces=("back",), where=lambda f, x, y: 2 <= x <= 6 and 5 <= y <= 11, noise=0.3)
    for y in range(0, 6):
        sk.set("body", "back", 4, y, hexc("#4A2E1C"), 1)
    sk.set("body", "back", 4, 8, hexc("#1F1F26"), 1)
    strap(sk, "#7A2E2A")
    return sk


def libraire(n):
    sk, skin = base(n, "porcelaine", "part", "roux", eyes="#3F6B8A")
    top_sweater(sk, skin, "#7A4A2E", under="#EDE6D6")
    hands(sk, skin)
    pants(sk, "#6A6C74", "crease")
    belt(sk, "#3A2416")
    shoes(sk, "leather", "#4A2E1C")
    glasses(sk, "#8A6A2E")
    for x, y, c in ((0, 6, "#2F4C8A"), (1, 6, "#2F4C8A"), (2, 6, "#2F4C8A"), (0, 7, "#F1EBDD"), (1, 7, "#F1EBDD"),
                    (2, 7, "#F1EBDD"), (0, 8, "#C9953A"), (1, 8, "#C9953A"), (2, 8, "#C9953A")):
        sk.set("armL", "front", x, y - 1, hexc(c), 1)                                    # books under the arm
    return sk


def mecanicien(n):
    sk, skin = base(n, "brun", "short", "noir", beard="moustache")
    top_tee(sk, skin, "#E9E6DF", sleeve=4)
    overalls(sk, "#35568F")
    sk.set("body", "front", 5, 5, hexc("#D9483B"))
    for (p, f, x, y) in (("body", "front", 2, 9), ("legR", "front", 1, 3), ("legL", "front", 2, 6),
                         ("armR", "front", 1, 6), ("body", "back", 5, 9)):
        sk.set(p, f, x, y, hexc("#23252B"))                                              # grease
    sk.paint("legR", ramp("#C9CCD2"), 1, faces=("right",), where=lambda f, x, y: x == 2 and 1 <= y <= 4, noise=0.2)
    shoes(sk, "boots", "#2A2422")
    hat(sk, "cap", "#35568F", "#D9483B")
    return sk


def barista(n):
    sk, skin = base(n, "miel", "bun", "noir", beard="short")
    top_tee(sk, skin, "#2B2D36", sleeve=4, neck=1)
    for x, y in ((0, 5), (1, 5), (2, 6), (1, 7), (0, 8), (2, 8)):                          # forearm tattoo
        sk.set("armR", "front", x, y, mix(skin[2], (40, 60, 70), 0.55))
    pants(sk, "#8A7A62", "chino")
    for p in LEGS:
        sk.ring(p, 8, ramp("#8A7A62")[4])
    top_apron(sk, "#6A4A32", skirt=6)
    for y in (0, 1):
        sk.set("body", "front", 2, y, hexc("#C9A66B"), 1)
        sk.set("body", "front", 5, y, hexc("#C9A66B"), 1)
    shoes(sk, "sneakers", "#F2F2F0", sole="#DADAD4")
    return sk


def agent_proprete(n):
    sk, skin = base(n, "ebene", "short", "noir")
    r = top_shirt(sk, skin, "#2F8F5A", sleeve=10, button="#1F5F3C", open_neck=False)
    b = ramp("#F1E04A")
    for p in ("body",) + ARMS:
        sk.ring(p, 4, b[2])
        sk.ring(p, 5, hexc("#D6D9DC"))
    hands(sk, skin)
    pants(sk, "#2F8F5A", "chino")
    for p in LEGS:
        sk.ring(p, 6, b[2])
        sk.ring(p, 7, hexc("#D6D9DC"))
    shoes(sk, "boots", "#23252B")
    for p in ARMS:
        sk.paint(p, ramp("#E8873A"), rows=range(9, 12), noise=0.4)                        # gloves
    hat(sk, "cap", "#2F8F5A", "#F1E04A")
    return sk


def robe_ete(n):
    sk, skin = base(n, "hale", "long", "noir", lips="#C0504E", blush=True)
    top_dress(sk, skin, "#F1C84B", sleeve=0, skirt=7, dots="#FFF6DC")
    for y in (0, 1):                                                                      # straps
        sk.set("body", "front", 1, y, ramp("#F1C84B")[1])
        sk.set("body", "front", 6, y, ramp("#F1C84B")[1])
    shoes(sk, "flats", "#B5694A")
    for p in LEGS:
        sk.ring(p, 9, hexc("#B5694A"))                                                   # ankle strap
    hat(sk, "straw", "#E4CF9A", "#B3262E")
    sk.set("armR", "front", 1, 8, hexc("#E8C25A"), 1)                                     # bracelet
    sk.set("armR", "front", 2, 8, hexc("#E8C25A"), 1)
    return sk


def footballeur(n):
    sk, skin = base(n, "clair", "fringe", "blond", blush=True)
    r = top_tee(sk, skin, "#2F5FB5", sleeve=4, neck=1)
    w = hexc("#F4F4F0")
    for p in ARMS:
        sk.ring(p, 3, w)
    for x in (2, 5):
        sk.set("body", "front", x, 0, w)
    for x, y in ((3, 3), (4, 3), (4, 4), (3, 5), (4, 5), (3, 6), (3, 7), (4, 7)):          # number 2... a "2"
        sk.set("body", "front", x, y, w)
    for x, y in ((2, 3), (3, 3), (4, 3), (5, 3), (5, 4), (4, 5), (3, 6), (3, 7)):          # a "7" on the back
        sk.set("body", "back", x, y, w)
    legs(sk, ramp("#F4F4F0"), rows=range(0, 5), noise=0.3)
    for p in LEGS:
        sk.ring(p, 4, ramp("#F4F4F0")[1])
        sk.set(p, OUTER[p], 1, 2, hexc("#2F5FB5"))
        sk.paint(p, ramp("#2F5FB5"), rows=range(7, 10), noise=0.3)                        # long socks
        sk.ring(p, 7, w)
    shoes(sk, "sneakers", "#23252B", sole="#D94F45")
    return sk


PEOPLE = [
    ("boulangere", "Boulangère", boulangere), ("serveur", "Garçon de café", serveur),
    ("homme_affaires", "Homme d'affaires", homme_affaires), ("femme_affaires", "Femme d'affaires", femme_affaires),
    ("etudiant", "Étudiant au casque", etudiant), ("etudiante", "Étudiante en marinière", etudiante),
    ("peintre", "Peintre", peintre), ("retraite", "Retraité à casquette", retraite),
    ("retraitee", "Retraitée", retraitee), ("joggeuse", "Joggeuse", joggeuse),
    ("livreur", "Livreur à vélo", livreur), ("fleuriste", "Fleuriste", fleuriste),
    ("ouvrier", "Ouvrier de chantier", ouvrier), ("touriste", "Touriste", touriste),
    ("facteur", "Facteur", facteur), ("infirmiere", "Infirmière", infirmiere),
    ("chef", "Chef cuisinier", chef), ("hipster", "Barbu à chemise à carreaux", hipster),
    ("parisienne", "Élégante au trench", parisienne), ("skateur", "Skateur", skateur),
    ("maman", "Passante en veste en jean", maman), ("doudoune", "Homme en doudoune", doudoune),
    ("voilee", "Passante au foulard", voilee), ("musicien", "Musicien de rue", musicien),
    ("libraire", "Libraire", libraire), ("mecanicien", "Mécanicien", mecanicien),
    ("barista", "Barista", barista), ("agent_proprete", "Agent de propreté", agent_proprete),
    ("robe_ete", "Passante en robe d'été", robe_ete), ("footballeur", "Jeune footballeur", footballeur),
]


# --------------------------------------------------------------------------- preview (3D, front and back)
def elements():
    out = []

    def box(part, x0, y0, z0, layer):
        w, h, d = DIMS[part]
        g = 0.0 if layer == 0 else (0.5 if part == "head" else 0.25)
        ou, ov = ORIGIN[layer][part]
        faces = {}
        for name, f in (("north", "front"), ("south", "back"), ("east", "right"), ("west", "left"), ("up", "top"),
                        ("down", "bottom")):
            u, v, fw, fh = face_rect(part, f)
            faces[name] = {"uv": [ou + u, ov + v, ou + u + fw, ov + v + fh], "texture": 0}
        e = {"from": [x0 - g, y0 - g, z0 - g], "to": [x0 + w + g, y0 + h + g, z0 + d + g],
             "origin": [x0 + w / 2, y0 + h, z0 + d / 2], "faces": faces}
        if part in ARMS:
            s = 1 if part == "armR" else -1
            e["rotation"] = [10 * s, 0, 4 * s]
        if part in LEGS:
            e["rotation"] = [-6 if part == "legR" else 6, 0, 0]
        out.append(e)
    for layer in (0, 1):
        box("legR", 0, 0, -2, layer)
        box("legL", -4, 0, -2, layer)
        box("body", -4, 12, -2, layer)
        box("armR", 4, 12, -2, layer)
        box("armL", -8, 12, -2, layer)
        box("head", -4, 24, -4, layer)
    return out


DIGITS = {"0": "111101101101111", "1": "010110010010111", "2": "111001111100111", "3": "111001111001111",
          "4": "101101111001001", "5": "111100111001111", "6": "111100111101111", "7": "111001010010010",
          "8": "111101111101111", "9": "111101111001111"}


def number(img, x, y, n, k=3, col=(255, 217, 77, 255)):
    for i, ch in enumerate(f"{n:02d}"):
        bits = DIGITS[ch]
        for j, b in enumerate(bits):
            if b == "1":
                for dy in range(k):
                    for dx in range(k):
                        img[y + (j // 3) * k + dy][x + i * 4 * k + (j % 3) * k + dx] = col


def sheet(skins, path, cols=6, big=190, small=130, first=1):
    els = elements()
    pad = 8
    cw, chh = big + small + pad, big + pad
    rows = (len(skins) + cols - 1) // cols
    bg = (44, 42, 52, 255)
    img = [[bg] * (cols * cw) for _ in range(rows * chh)]
    frame = [[-9, 0, -5], [9, 0, 5], [-9, 33, -5], [9, 33, 5], [-9, 0, 5], [9, 33, -5]]
    for i, px in enumerate(skins):
        ox, oy = (i % cols) * cw, (i // cols) * chh
        a = A.render(els, px, 28, 8, size=big, bg=bg, margin=14, frame_points=frame)
        b = A.render(els, px, 205, 8, size=small, bg=bg, margin=10, frame_points=frame)
        for y in range(big):
            for x in range(big):
                if a[y][x] != (255, 0, 0, 255):
                    img[oy + y][ox + x] = a[y][x]
        for y in range(small):
            for x in range(small):
                if b[y][x] != (255, 0, 0, 255):
                    img[oy + big - small + y][ox + big + x] = b[y][x]
        number(img, ox + big + 8, oy + 10, first[i] if isinstance(first, list) else i + first)
    write(path, cols * cw, rows * chh, img)


if __name__ == "__main__":
    if "--zoom" in sys.argv:                      # big views of a few inhabitants: --zoom 7,23 <file.png>
        i = sys.argv.index("--zoom")
        ids = [int(v) for v in sys.argv[i + 1].split(",")]
        sheet([PEOPLE[n - 1][2](n).image() for n in ids], sys.argv[i + 2], cols=3, big=380, small=260, first=ids)
        sys.exit()
    out = sys.argv[1] if len(sys.argv) > 1 else HERE
    os.makedirs(out, exist_ok=True)
    skins = []
    for n, (ident, label, make) in enumerate(PEOPLE, 1):
        px = make(n).image()
        write(os.path.join(out, f"{n:02d}_{ident}.png"), 64, 64, px)
        skins.append(px)
    sheet(skins, os.path.join(out, "planche.png"), big=270, small=180)
    print(f"{len(skins)} skins, planche.png")
