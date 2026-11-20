#!/usr/bin/env python3
"""Input glyphs of influemoji, same pixel art and palette as left_click / right_click / shift.

    <venv with Pillow>/python3 gen_inputs.py <contents/influemoji/textures/font/emoji>

- middle_click: grey mouse, orange striped wheel (the wheel: zoom, middle click).
- mouse_move: grey mouse and an orange up / down arrow beside it (move the mouse forward / backward).
- move_keys: the four movement keys (forward on top, left / back / right below) with arrows, not letters,
  so it reads the same on AZERTY (ZQSD) and QWERTY (WASD).
Green = left click, red = right click, orange = wheel and keys.
"""
import os
import sys

from PIL import Image

O, GREY, WHEEL = (29, 29, 27), (74, 74, 74), (102, 102, 102)
SLOT, LIGHT, ORANGE = (150, 100, 0), (255, 217, 77), (240, 168, 0)
KEY_EDGE, KEY_TOP = (140, 140, 140), (102, 102, 102)

MOUSE = [
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
ARROW_UP_DOWN = [
    "................",
    "...O............",
    "..OSO...........",
    ".OSSSO..........",
    "OSSSSSO.........",
    "OOOSOOO.........",
    "..OSO...........",
    "..OSO...........",
    "..OSO...........",
    "..OSO...........",
    "OOOSOOO.........",
    "OSSSSSO.........",
    ".OSSSO..........",
    "..OSO...........",
    "...O............",
    "................",
]
KEY = [
    ".OOOOOOO.",
    "OEEEEEEEO",
    "OTTTTTTTO",
    "OTTTTTTTO",
    "OTTTTTTTO",
    "OTTTTTTTO",
    "OKKKKKKKO",
    ".OOOOOOO.",
]
# arrow of each key, drawn on the 7x4 top of the key (rows 2 to 5)
SYMBOLS = {
    "up": ["...S...", "..SSS..", ".SSSSS.", "......."],
    "down": [".......", ".SSSSS.", "..SSS..", "...S..."],
    "left": ["..S....", ".SSSSS.", ".SSSSS.", "..S...."],
    "right": ["....S..", ".SSSSS.", ".SSSSS.", "....S.."],
}
COLOURS = {"O": O, "K": GREY, "T": SLOT, "d": LIGHT, "S": ORANGE, "W": WHEEL}
KEY_COLOURS = {"O": O, "E": KEY_EDGE, "T": KEY_TOP, "K": GREY, "S": ORANGE}


def paint(im, art, x0, y0, colours):
    for y, row in enumerate(art):
        for x, c in enumerate(row):
            if c in colours:
                im.putpixel((x0 + x, y0 + y), colours[c] + (255,))


def middle_click():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    paint(im, MOUSE, 0, 0, COLOURS)
    return im


def mouse_move():
    # grey wheel: the arrow is the move of the whole mouse
    grey = [row.replace("T", "O").replace("d", "W").replace("S", "W") for row in MOUSE]
    im = Image.new("RGBA", (24, 16), (0, 0, 0, 0))
    paint(im, grey, 0, 0, COLOURS)
    paint(im, ARROW_UP_DOWN, 17, 0, COLOURS)
    return im


def move_keys():
    im = Image.new("RGBA", (29, 16), (0, 0, 0, 0))
    for (x, y, symbol) in [(10, 0, "up"), (0, 8, "left"), (10, 8, "down"), (20, 8, "right")]:
        paint(im, KEY, x, y, KEY_COLOURS)
        paint(im, SYMBOLS[symbol], x + 1, y + 2, KEY_COLOURS)
    return im


def main(dest):
    os.makedirs(dest, exist_ok=True)
    for name, make in [("middle_click", middle_click), ("mouse_move", mouse_move), ("move_keys", move_keys)]:
        make().save(os.path.join(dest, name + ".png"))
        print(name + ".png ->", dest)


if __name__ == "__main__":
    main(sys.argv[1])
