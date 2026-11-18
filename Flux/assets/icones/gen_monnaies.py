#!/usr/bin/env python3
"""Currency icons redrawn from scratch in 16x16 (2026-10-28), from the owner's reference images:
- influence.png (redrawn 2026-11-18): a six-pointed crystal star, dark magenta outline on the lit side (top left) and
  purple on the shaded side, light yellow / peach bevel inside the outline, white spark at the centre with a yellow
  streak towards the top point, pink facets darkening to magenta / purple at the bottom right.
- bitblock.png: the cube of the reference (seen from a corner), same light / dark layout, recoloured in the BitBlock violet
  (server charter: #5B2D9E, #B376E5).

    <venv with Pillow>/python3 gen_monnaies.py <output dir> [preview dir]

Writes both icons: the BitBlock in contents/influemoji has been changed by hand since, so write into a scratch
directory and copy only influence.png (emoji, influitems item texture).
"""
import os
import sys

from PIL import Image

# influence.png: full 16x16 drawing; bitblock.png: 14x14 drawing placed with a 1 px transparent margin
INFLUENCE = [
    ".......OO.......",
    "......OWYV......",
    ".....OWYPPV.....",
    "..OOOOWYMPVVVV..",
    ".OWWWWYYMPCCYYV.",
    ".OYPPMPYMPPMMCV.",
    "..OMPPYWYMPMCV..",
    "..OMPYWWWYPMCV..",
    "..OMPPYWYMMmPV..",
    "..OYPMPYMmmUPV..",
    ".OYCPMPPMmUUmPV.",
    ".OYYYYCPMmUmYYV.",
    "..VVVVCPMPVVVV..",
    ".....VCPMV......",
    "......VPMV......",
    ".......VV.......",
]
INFLUENCE_COLOURS = {
    "O": "8a1c78", "V": "4f1c80", "W": "fff6ee", "Y": "ffe28a", "C": "ffc8a0", "P": "ff8fae", "p": "f0699e",
    "M": "d23f95", "m": "a8278a", "U": "7a2a9c",
}

# a cube seen from a corner (hexagonal outline): light top face with its bright cross, dark left face, medium
# right face, light vertical edge in the middle; light rim (top) / lilac rim (bottom) around a dark outline
BITBLOCK = [
    "......RR......",
    "....RRDDRR....",
    "..RRDDTTDDRR..",
    ".RDDTTTLTTDDR.",
    "RDTTTTLLLTTTDR",
    "RDTTTLLLLLTTDR",
    "RDAATTTLTTTBDR",
    "RDAAAATEEBBBDR",
    "YDAAAAAEBBBBDY",
    "YDKAAAAEBBBBDY",
    ".YDKAAAEBBBDY.",
    "..YYDDAEBDDYY.",
    "....YYDDYY....",
    "......YY......",
]
BITBLOCK_COLOURS = {
    "R": "f6eeff", "Y": "cda9f4", "D": "2b0e52", "T": "ebdcfc", "L": "c58bf0", "E": "f2e6ff",
    "A": "7b3fcf", "K": "5b2d9e", "B": "a86be8",
}


def draw(art, colours):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    off = (16 - len(art)) // 2
    for y, row in enumerate(art):
        for x, ch in enumerate(row):
            if ch in colours:
                h = colours[ch]
                im.putpixel((x + off, y + off), (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255))
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
