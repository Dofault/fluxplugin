#!/usr/bin/env python3
"""Currency icons redrawn from scratch in 16x16 (2026-10-28), from the owner's reference images:
- influence.png: a square-cut ruby in a dark square frame (« cube »): light top facet, pink left, magenta right,
  dark purple bottom pyramid, light streaks crossing at the centre. Palette taken from the reference.
- bitblock.png: the round gem of the reference, same light / dark layout, recoloured in the BitBlock violet
  (server charter: #5B2D9E, #B376E5).

    <venv with Pillow>/python3 gen_monnaies.py <contents/influemoji/textures/font/emoji> [preview dir]
"""
import os
import sys

from PIL import Image

INFLUENCE = [
    "BBBBBBBBBBBBBBBB",
    "BrrrrrrrrrrrrrrB",
    "BkTTTTTTTTTTTTkB",
    "BkpTTTTTTTTTTmkB",
    "BkppTTTTTTTTmmkB",
    "BkPPpTTTTTTmMmkB",
    "BkPPPptXXtmMMMkB",
    "BkPPPPXXXXMMMMkB",
    "SkPPPXtqqtXMMMkS",
    "SkPPXqqQQqqXMMkS",
    "SkpXqQQQQQQqXmkS",
    "SkqqQQQQQQQQqqkS",
    "SkqQQQQQQQQQQqkS",
    "SkQQQQQQQQQQQQkS",
    "SzzzzzzzzzzzzzzS",
    "SSSSSSSSSSSSSSSS",
]
INFLUENCE_COLOURS = {
    "B": "591447", "S": "3b1431", "r": "a06587", "k": "611d4e", "T": "fcd0da", "t": "f6b4c7", "X": "fbcdd8",
    "P": "f197b3", "p": "eb668e", "M": "de5e87", "m": "c23683", "Q": "7f2068", "q": "a62785", "z": "471039",
}

BITBLOCK = [
    ".......RR.......",
    ".....RRDDRR.....",
    "...RRDDCCDDRR...",
    "..RDDCCCCCCDDR..",
    ".RDCCCWWCCCCCDR.",
    ".RDCCLLLLLLCCDR.",
    "RDCLLLLLLLLLLCDR",
    "RDGLLLLWWLLLLLDR",
    "RDGKKGLLLGGGGGDR",
    "YDGKKKGLGGGGgGDY",
    "YDGGKKGLGGgggGDY",
    ".YDGGKGLGGGGGDY.",
    ".YDKKGGLGGKKGDY.",
    "..YDDKGGGGKDDY..",
    "...YYDDLLDDYY...",
    ".....YYDDYY.....",
]
BITBLOCK_COLOURS = {
    "R": "f6eeff", "Y": "cda9f4", "D": "2b0e52", "C": "ebdcfc", "W": "ffffff",
    "L": "c58bf0", "G": "9a5bdf", "g": "b17bea", "K": "5b2d9e",
}


def draw(art, colours):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(art):
        for x, ch in enumerate(row):
            if ch in colours:
                h = colours[ch]
                im.putpixel((x, y), (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255))
    return im


if __name__ == "__main__":
    dest = sys.argv[1]
    for name, art, colours in (("influence", INFLUENCE, INFLUENCE_COLOURS), ("bitblock", BITBLOCK, BITBLOCK_COLOURS)):
        im = draw(art, colours)
        im.save(os.path.join(dest, name + ".png"))
        if len(sys.argv) > 2:
            big = Image.new("RGBA", (320, 320), (60, 50, 80, 255))
            big.alpha_composite(im.resize((320, 320), Image.NEAREST))
            big.save(os.path.join(sys.argv[2], name + "_x20.png"))
    print("influence.png, bitblock.png ->", dest)
