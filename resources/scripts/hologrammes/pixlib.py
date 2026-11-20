"""Outils communs des pixel arts d'hologramme (gen_donjon_*.py) : canevas, formes au contour épais façon autocollant,
écriture PNG sans Pillow, logo réduit et config ItemsAdder du namespace influhologrammes.

Un logo de donjon est réduit sous 256 px de large : à 256, la limite des glyphes, il s'affichait décalé sur le côté en
jeu (2026-11-14). Le pixel art est posé au-dessus du logo par scripts/typewriter/gen_lobby_rpg.py (LOGOS).
"""
import struct
import zlib
from pathlib import Path

CLEAR = (0, 0, 0, 0)
DEFAULT_CONTENTS = "/home/influencraft/serveurs/04_labs/plugins/ItemsAdder/contents"


def hexc(h):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


class Canvas:
    """Pixel grid; `ox` shifts everything put (to draw a figure in its own frame inside a wider picture)."""

    def __init__(self, w, h, line):
        self.w, self.h, self.line, self.ox = w, h, line, 0
        self.img = [[CLEAR] * w for _ in range(h)]

    def put(self, x, y, c):
        x += self.ox
        if 0 <= x < self.w and 0 <= y < self.h:
            self.img[y][x] = c

    def ellipse(self, cx, cy, rx, ry):
        return {(x, y) for y in range(self.h) for x in range(self.w)
                if ((x + .5 - cx) / rx) ** 2 + ((y + .5 - cy) / ry) ** 2 <= 1}

    def segment(self, a, b, r):
        """Thick line from a to b (radius r), round ends."""
        (ax, ay), (bx, by) = a, b
        dx, dy = bx - ax, by - ay
        n = dx * dx + dy * dy or 1
        out = set()
        for y in range(int(min(ay, by) - r - 1), int(max(ay, by) + r + 2)):
            for x in range(int(min(ax, bx) - r - 1), int(max(ax, bx) + r + 2)):
                t = max(0, min(1, ((x + .5 - ax) * dx + (y + .5 - ay) * dy) / n))
                if (x + .5 - ax - t * dx) ** 2 + (y + .5 - ay - t * dy) ** 2 <= r * r:
                    out.add((x, y))
        return out

    def shape(self, mask, ramp, outline=True, light=True):
        """Sticker style: 1px outline, then fill shaded from the top-left (ramp dark -> light)."""
        put = self.put
        if outline:
            for (x, y) in mask:
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    if (x + dx, y + dy) not in mask:
                        put(x + dx, y + dy, self.line)
        n = len(ramp)
        for (x, y) in mask:
            c = ramp[min(2, n - 1)] if n >= 3 else ramp[-1]
            if light:
                if (x + 1, y + 2) not in mask or (x + 2, y + 3) not in mask:
                    c = ramp[1] if n > 2 else ramp[0]
                if (x + 1, y + 1) not in mask and n > 2:
                    c = ramp[0]
                if (x - 1, y - 2) not in mask and n > 3:
                    c = ramp[3]
            put(x, y, c)

    def strip(self, cy, thick, top=None):
        """Band under the figure, as wide as the picture, thinning out to a point on both sides (water, grass,
        stone...); `top(x)` lifts its upper edge here and there. Draws the outline, returns the pixels with their
        depth under the band's top (the caller colours them)."""
        w, h = self.w, self.h

        def half(x):
            u = min(1.0, abs((x + .5 - w / 2) / (w / 2 - 1)))
            return thick * (1 - u ** 3) ** .55
        mask = {(x, y) for x in range(w) for y in range(h - 1)
                if half(x) > .6 and max(cy - half(x), (top(x) if top else -1)) <= y <= cy + half(x)}
        for (x, y) in mask:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if (x + dx, y + dy) not in mask:
                    self.put(x + dx, y + dy, self.line)
        tops = {}
        for (x, y) in mask:
            tops[x] = min(tops.get(x, y), y)
        return {(x, y): y - tops[x] for (x, y) in mask}

    def cropped(self):
        pts = [(x, y) for y in range(self.h) for x in range(self.w) if self.img[y][x][3]]
        x0, x1 = min(p[0] for p in pts), max(p[0] for p in pts)
        y0, y1 = min(p[1] for p in pts), max(p[1] for p in pts)
        return [row[x0:x1 + 1] for row in self.img[y0:y1 + 1]]


def write_png(path, pix, scale=1):
    h, w = len(pix), len(pix[0])
    raw = b''
    for row in pix:
        line = b''.join(bytes(c) * scale for c in row)
        raw += (b'\x00' + line) * scale

    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w * scale, h * scale, 8, 6, 0, 0, 0))
    data += chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    Path(path).write_bytes(data)


def args():
    """[dossier ItemsAdder/contents] [--apercu image.png] -> (influhologrammes folder, preview path or None)."""
    import sys
    a = sys.argv[1:]
    preview = None
    if "--apercu" in a:
        i = a.index("--apercu")
        preview = a[i + 1]
        del a[i:i + 2]
    return Path(a[0] if a else DEFAULT_CONTENTS) / "influhologrammes", preview


def publish(out, key, name, art, art_height, logo_src, logo_size, logo_height, art_note, logo_note, script, preview=None):
    """Writes <key>_<name>.png, <key>_logo.png and <key>.yml (its own config file) in the influhologrammes folder."""
    from PIL import Image
    tex = out / "textures" / "font" / "hologrammes"
    tex.mkdir(parents=True, exist_ok=True)
    art_id, logo_id = f"{key}_{name}", f"{key}_logo"
    write_png(tex / f"{art_id}.png", art)
    logo = Image.open(logo_src).convert("RGBA").resize(logo_size, Image.BOX)
    logo.save(tex / f"{logo_id}.png")
    entry = ("  # {note}\n  {id}:\n    suggest_in_command: false\n    show_in_gui: false\n"
             "    path: font/hologrammes/{id}.png\n    scale_ratio: {h}\n    y_position: {y}\n")
    (out / f"{key}.yml").write_text(
        f"# Généré par scripts/hologrammes/{script} : ne pas modifier à la main.\ninfo:\n  namespace: influhologrammes\n"
        "font_images:\n" + entry.format(note=art_note, id=art_id, h=art_height, y=art_height - 2)
        + entry.format(note=logo_note, id=logo_id, h=logo_height, y=logo_height - 2), encoding="utf-8")
    if preview:
        write_png(preview, art, 8)
    print(f"{art_id} : {len(art[0])} x {len(art)}, affichée sur {art_height} px de haut ; "
          f"{logo_id} : {logo.width} x {logo.height}, affiché sur {logo_height} px")


def art_height(art, logo_size, logo_height, word):
    """Displayed height of the picture so that its width equals `word` (fraction of the logo's width)."""
    return round(logo_height * logo_size[0] / logo_size[1] * word * len(art) / len(art[0]))
