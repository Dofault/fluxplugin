#!/usr/bin/env python3
"""Shift key glyph (influemoji:shift), same 16x16 pixel art and palette as left_click / right_click.

    <venv with Pillow>/python3 gen_shift.py <contents/influemoji/textures/font/emoji>
Shown before the click icon of the shift actions: %img_shift%+%img_left_click% (the + is a character, Flux style.yml).
The arrow is 2 / 4 / 6 / 8 px wide (even) so it sits exactly in the middle of the 14 px key.
"""
import os
import sys

from PIL import Image

# palette of the click icons: outline, key sides, key top, light top edge, symbol
O, SIDE, TOP, EDGE, SYM, SYM_DARK = (29, 29, 27), (74, 74, 74), (102, 102, 102), (140, 140, 140), (120, 205, 255), (40, 95, 140)
ART = [
    "................",
    "..OOOOOOOOOOOO..",
    ".OEEEEEEEEEEEEO.",
    ".OTTTTTSSTTTTTO.",
    ".OTTTTSSSSTTTTO.",
    ".OTTTSSSSSSTTTO.",
    ".OTTSSSSSSSSTTO.",
    ".OTTddSSSSddTTO.",
    ".OTTTTSSSSTTTTO.",
    ".OTTTTSSSSTTTTO.",
    ".OTTTTSSSSTTTTO.",
    ".OTTTTddddTTTTO.",
    ".OKKKKKKKKKKKKO.",
    ".OKKKKKKKKKKKKO.",
    "..OOOOOOOOOOOO..",
    "................",
]
COLOURS = {"O": O, "E": EDGE, "T": TOP, "K": SIDE, "S": SYM, "d": SYM_DARK}


def main(dest):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(ART):
        for x, c in enumerate(row):
            if c in COLOURS:
                im.putpixel((x, y), COLOURS[c] + (255,))
    os.makedirs(dest, exist_ok=True)
    im.save(os.path.join(dest, "shift.png"))
    print("shift.png ->", dest)


if __name__ == "__main__":
    main(sys.argv[1])
