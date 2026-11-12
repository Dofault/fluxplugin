#!/usr/bin/env python3
"""Pixel-art icons of the tournament kinds (16 x 16), one picture each in the blocks atlas: a text shows them with
<sprite:block/fluxtournament_<kind>> (no font glyph). Written into an ItemsAdder contents folder:

  fluxtournament_icons/resourcepack/assets/minecraft/textures/block/fluxtournament_{job,resource,race,variety}.png

Usage: gen_icons.py [<ItemsAdder contents folder>] [<preview folder>]   (default: Labs, no preview)
"""
import os, struct, sys, zlib

PAL = {
    "K": (13, 26, 43), "B": (43, 196, 232), "L": (166, 228, 245), "W": (255, 255, 255), "Y": (255, 217, 77),
    "O": (240, 168, 0), "N": (139, 90, 43), "n": (92, 58, 26), "G": (154, 165, 177), "g": (94, 107, 120),
    "R": (255, 92, 92), "D": (24, 92, 140),
}
N = 16


def png(path, w, h, px):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    raw = b"".join(b"\x00" + bytes(c for p in px[y * w:(y + 1) * w] for c in p) for y in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


class Icon:
    def __init__(self):
        self.g = [["." for _ in range(N)] for _ in range(N)]

    def put(self, x, y, c):
        if 0 <= x < N and 0 <= y < N:
            self.g[y][x] = c

    def line(self, x0, y0, x1, y1, c):
        steps = max(abs(x1 - x0), abs(y1 - y0))
        for i in range(steps + 1):
            t = i / max(1, steps)
            self.put(round(x0 + (x1 - x0) * t), round(y0 + (y1 - y0) * t), c)

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.put(x, y, c)

    def outline(self):
        """A dark outline round everything drawn (four sides), as Minecraft's own icons."""
        out = [row[:] for row in self.g]
        for y in range(N):
            for x in range(N):
                if self.g[y][x] != ".":
                    continue
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < N and 0 <= ny < N and self.g[ny][nx] not in (".", "K"):
                        out[y][x] = "K"
                        break
        self.g = out

    def pixels(self):
        return [(0, 0, 0, 0) if c == "." else PAL[c] + (255,) for row in self.g for c in row]


def star(ic, cx, cy):
    ic.put(cx, cy, "W")
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        ic.put(cx + dx, cy + dy, "Y")


def job():
    # a pickaxe (curved light-blue head, wooden handle) and a gold star: the whole job counts
    ic = Icon()
    ic.line(2, 13, 9, 6, "N")
    ic.line(3, 13, 9, 7, "n")
    ic.line(6, 3, 12, 9, "L")
    ic.line(5, 4, 11, 10, "B")
    # both ends of the head bend down towards the handle
    for x, y in ((4, 5), (4, 6), (5, 5), (10, 11), (11, 11), (9, 11)):
        ic.put(x, y, "B")
    star(ic, 13, 2)
    ic.outline()
    return ic


def resource():
    # a block with a gold lightning bolt on its corner: one resource, as fast as you can
    ic = Icon()
    ic.rect(1, 7, 9, 14, "D")
    ic.rect(1, 7, 9, 8, "B")
    for x, y in ((3, 10), (6, 12), (3, 13), (7, 10)):
        ic.put(x, y, "L")
    bolt = {1: (11, 13), 2: (10, 12), 3: (9, 11), 4: (8, 13), 5: (11, 12), 6: (10, 11), 7: (9, 10), 8: (8, 9), 9: (8, 8)}
    for y, (x0, x1) in bolt.items():
        for x in range(x0, x1 + 1):
            ic.put(x, y, "O" if x == x1 and y != 4 else "Y")
    ic.outline()
    return ic


def race():
    # a chequered flag: the first ones there win
    ic = Icon()
    ic.line(3, 1, 3, 14, "G")
    ic.put(3, 15, "g")
    for y in range(2, 10):
        for x in range(4, 14):
            ic.put(x, y, "W" if ((x - 4) // 2 + (y - 2) // 2) % 2 == 0 else "K")
    # a little wave on the flag's free edge
    ic.put(13, 2, "."); ic.put(13, 9, ".")
    ic.outline()
    return ic


def variety():
    # a compass: find every resource
    ic = Icon()
    c = 7.5
    for y in range(N):
        for x in range(N):
            d = ((x - c) ** 2 + (y - c) ** 2) ** 0.5
            if d <= 5.2:
                ic.put(x, y, "W" if d <= 3.9 else ("Y" if y < 8 else "O"))
    for i in range(0, 4):
        ic.put(7, 4 + i, "R"); ic.put(8, 4 + i, "R")
    for i in range(0, 4):
        ic.put(7, 8 + i, "B"); ic.put(8, 8 + i, "B")
    ic.put(7, 7, "K"); ic.put(8, 8, "K")
    ic.outline()
    return ic


def main():
    contents = sys.argv[1] if len(sys.argv) > 1 else "/home/influencraft/serveurs/04_labs/plugins/ItemsAdder/contents"
    preview = sys.argv[2] if len(sys.argv) > 2 else None
    out = os.path.join(contents, "fluxtournament_icons/resourcepack/assets/minecraft/textures/block")
    icons = {"job": job(), "resource": resource(), "race": race(), "variety": variety()}
    for kind, ic in icons.items():
        png(os.path.join(out, "fluxtournament_" + kind + ".png"), N, N, ic.pixels())
    if preview:
        # the four side by side, 12x, on a dark background
        S, gap = 12, 4
        W = (N * S + gap * S) * len(icons)
        H = N * S
        px = [(30, 34, 48, 255)] * (W * H)
        for k, ic in enumerate(icons.values()):
            src = ic.pixels()
            for y in range(H):
                for x in range(N * S):
                    p = src[(y // S) * N + x // S]
                    if p[3]:
                        px[y * W + k * (N * S + gap * S) + x] = p
        png(os.path.join(preview, "tournament_icons.png"), W, H, px)
    print("written in", out)


if __name__ == "__main__":
    main()
