"""Texture of Flux's head pointer (api.client.Camera#pointer): a mouse arrow, plus one almost transparent 2x2 mark
in each corner (254, 1, 200 + corner, alpha 5) that the item vertex shader (pack/assets/minecraft/shaders/core/
item.vsh) reads to know which corner a vertex is. No dependency: python3 gen_pointer.py writes the png in the jar's
pack resources."""
import os
import struct
import zlib

ARROW = [
    "X",
    "XX",
    "XoX",
    "XooX",
    "XoooX",
    "XooooX",
    "XoooooX",
    "XooooooX",
    "XoooooooX",
    "XooooXXXXX",
    "XooXooX",
    "XoX XooX",
    "XX   XooX",
    "      XX",
]
OUTLINE = (13, 13, 13, 255)
FILL = (255, 217, 77, 255)


def png(path, pixels, w, h):
    raw = b"".join(b"\x00" + bytes(c for px in pixels[y * w:(y + 1) * w] for c in px) for y in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(data)


def main():
    w = h = 16
    px = [(0, 0, 0, 0)] * (w * h)
    # the arrow from texel (2, 2): the model's face shows texels 1 to 14, its top left corner is the tip
    for y, row in enumerate(ARROW):
        for x, c in enumerate(row):
            if c == "X":
                px[(y + 2) * w + x + 2] = OUTLINE
            elif c == "o":
                px[(y + 2) * w + x + 2] = FILL
    for corner, (cx, cy) in enumerate([(0, 0), (14, 0), (0, 14), (14, 14)]):
        for dy in range(2):
            for dx in range(2):
                px[(cy + dy) * w + cx + dx] = (254, 1, 200 + corner, 5)
    here = os.path.dirname(os.path.abspath(__file__))
    out = os.path.join(here, "..", "..", "src", "main", "resources", "pack", "assets", "flux", "textures", "item", "pointer.png")
    png(os.path.normpath(out), px, w, h)
    print("written", os.path.normpath(out))


if __name__ == "__main__":
    main()
