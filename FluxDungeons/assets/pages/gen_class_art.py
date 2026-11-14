#!/usr/bin/env python3
"""Pictures of the elemental classes on the class pages (their weapon, pixel art on the pages' flat grey: the page
makes that grey transparent). Writes src/main/resources/pages/img/<class>.png, 288 x 288 like the other classes.
A file already in the server's pages folder is kept: delete it there to take a new picture.

    python3 gen_class_art.py        (needs Pillow)
"""
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
from hud_spells import PALETTE, rows  # noqa: E402

OUT = os.path.normpath(os.path.join(HERE, "..", "..", "src", "main", "resources", "pages", "img"))
GREY = (139, 139, 139)

ART = {
    # a staff of steel, a crystal of ice at its head
    "cryomancer": rows(
        "............C...",
        "...........CcC..",
        "..........CcIcC.",
        "...........cIc..",
        "..........3cC...",
        ".........32.....",
        "........32......",
        ".......32.......",
        "......32........",
        ".....32.........",
        "....32..........",
        "...32...........",
        "..32............",
        ".12.............",
        ".1..............",
        "................"),
    # a rod of gold, a flame at its head
    "pyromancer": rows(
        "...........y....",
        "..........yoy...",
        ".........yoFoy..",
        "..........oFo.y.",
        "..........uoy...",
        ".........uD.....",
        "........uD......",
        ".......uD.......",
        "......uD........",
        ".....uD.........",
        "....uD..........",
        "...uD...........",
        "..uD............",
        ".dD.............",
        ".d..............",
        "................"),
    # a golden sickle on a wooden handle, a leaf at its foot
    "druid": rows(
        "......uuuu......",
        "....uuUUUuu.....",
        "...uUu...uUu....",
        "..uUu.....uUu...",
        "..uu.......uu...",
        "..u........uD...",
        "...........uD...",
        "..........bD....",
        ".........bW.....",
        "........bW......",
        ".......bW.......",
        "......bW........",
        ".....bW.........",
        "....bW....L.....",
        "...wW....Ll.....",
        "................"),
}

for name, grid in ART.items():
    im = Image.new("RGB", (16, 16), GREY)
    for y in range(16):
        for x in range(16):
            if grid[y][x] != ".":
                h = PALETTE[grid[y][x]].lstrip("#")
                im.putpixel((x, y), (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)))
    im.resize((288, 288), Image.NEAREST).save(os.path.join(OUT, name + ".png"))
    print("written", name)
