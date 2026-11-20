#!/usr/bin/env python3
"""Middle click (mouse wheel) glyph (influemoji:middle_click), same 16x16 pixel art and palette as left_click / right_click.

    <venv with Pillow>/python3 gen_middle_click.py <contents/influemoji/textures/font/emoji>
Both buttons are neutral grey, only the wheel is coloured (orange, striped to show its notches):
green = left, red = right, orange = wheel. Used as %img_middle_click% before the action name.
"""
import os
import sys

from PIL import Image

# palette of the click icons: outline, buttons, wheel slot, wheel notches light / dark
O, GREY, SLOT, LIGHT, DARK = (29, 29, 27), (74, 74, 74), (150, 100, 0), (255, 217, 77), (240, 168, 0)
ART = [
    "....OOOOOOOO....",
    "..OOOKKOOKKOOO..",
    ".OOKKKKOOKKKKOO.",
    ".OKKKKKOOKKKKKO.",
    "OOKKKKKOOKKKKKOO",
    "OKKKKKKTTKKKKKKO",
    "OKKKKKOddOKKKKKO",
    "OKKKKKOSSOKKKKKO",
    "OKKKKKOddOKKKKKO",
    "OOKKKKOSSOKKKKOO",
    "OOOOKKOddOKKOOOO",
    "OKOOKKKTTKKKOOKO",
    "OKOOOKKKKKKOOOKO",
    "OKOOOKKKKKKOOOKO",
    "OKKKOKKKKKKOKKKO",
    "OKKKKKKKKKKKKKKO",
]
COLOURS = {"O": O, "K": GREY, "T": SLOT, "d": LIGHT, "S": DARK}


def main(dest):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(ART):
        for x, c in enumerate(row):
            if c in COLOURS:
                im.putpixel((x, y), COLOURS[c] + (255,))
    os.makedirs(dest, exist_ok=True)
    im.save(os.path.join(dest, "middle_click.png"))
    print("middle_click.png ->", dest)


if __name__ == "__main__":
    main(sys.argv[1])
