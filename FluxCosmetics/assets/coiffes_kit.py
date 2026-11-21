"""What the hat modules of gen_coiffes.py share: the path to FluxArmor's setkit and the common painters."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../FluxArmor/assets/models"))

import gen_virale_bbmodel as v  # noqa: E402,F401
import setkit as k  # noqa: E402
from setkit import Cube, mirror, paint, stamp  # noqa: E402,F401

k.ATLAS = 64


def soft(base, light, shade):
    """Matt material: light along the lit part of the top edge, shade on the far column, flat elsewhere."""
    def painter(w, h):
        def pixel(x, y):
            if y == 0 and x < w * 0.6:
                return light
            if w >= 3 and x == w - 1:
                return shade
            return base
        return paint(w, h, pixel)
    return painter


def flat(letter):
    return lambda w, h: [letter * w] * h


def rows(w, *letters):
    """Grid of full rows, one letter per row."""
    return [c * w for c in letters]


def walls(name, y0, y1, material, front, side, back=None, r=5, **extra):
    """Band round the head as four walls, r pixels from its middle, nothing drawn inside. front and back: 2r wide,
    side: 2r - 2 wide, drawn as seen on the player's right (front last) and flipped on his left. For a band whose top
    would lie in the plane of the head's top (y 32), where one cube through the head would flicker."""
    return [
        Cube(name + "_avant", [-r, y0, r - 1], [r, y1, r], material, north=None, south=front, **extra),
        Cube(name + "_arriere", [-r, y0, -r], [r, y1, 1 - r], material, south=None, north=back or front, **extra),
        Cube(name + "_droit", [-r, y0, 1 - r], [1 - r, y1, r - 1], material, east=None, north=None, south=None, west=side, **extra),
        Cube(name + "_gauche", [r - 1, y0, 1 - r], [r, y1, r - 1], material, west=None, north=None, south=None,
             east=[line[::-1] for line in side], **extra),
    ]
