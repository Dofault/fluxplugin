"""What the accessory modules of gen_accessoires.py share: the one palette of the collection ("Relics of Azureth"),
the painters of each matter and the helpers for boxes and cut-out planes.

Frame of FluxArmor's setkit: one skin pixel per unit, feet at y = 0, torso x -4..4, y 12..24, z -2..2, +x towards the
player's left, +z forwards. The back of the body is the plane z = -2; BACK and FRONT leave room for the skin's jacket
layer. Light comes from the top left.

A grid is a list of rows (top first). "." is a transparent pixel: wings, capes' hems and the fox's tail get their
outline from the picture, on a plane with only its two large faces (plane()).
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../FluxArmor/assets/models"))

import gen_virale_bbmodel as v  # noqa: E402,F401
import setkit as k  # noqa: E402
from setkit import Cube, mirror, paint, stamp  # noqa: E402,F401

k.ATLAS = 64
BACK, FRONT = -2.5, 2.5

# The forty colours of the collection, nothing else is ever painted.
PALETTE = {
    "K": "#252632", "S": "#454958", "A": "#737C8A", "I": "#B6C0C9", "W": "#E0E5E8",  # metal
    "D": "#382A28", "U": "#684535", "N": "#966747", "C": "#BD9064", "H": "#D9B58A",  # leather, wood
    "1": "#263C59", "B": "#385F85", "Z": "#588DB0", "G": "#A5CCDA",  # blue cloth
    "2": "#572E38", "R": "#843F50", "Y": "#B65C68", "P": "#D99A9B",  # red cloth
    "3": "#284C43", "F": "#39705A", "J": "#69A17C", "T": "#A7C69A",  # green
    "E": "#E8DFC8", "Q": "#F5F0E5", "M": "#B7AA92",  # feathers, ivory cloth
    "4": "#A77B39", "O": "#D2A94F", "L": "#F0D68A",  # gold
    "5": "#392D58", "V": "#69518C", "X": "#A18AC4", "6": "#D6B9DF",  # magic
    "7": "#C78C69", "8": "#825541", "9": "#E5CDA5", "0": "#342F32",  # animal
    "$": "#C56B38", "%": "#F0B65B", "&": "#62C8D0", "#": "#A83F48",  # energy, demon
}


def flat(letter):
    return lambda w, h: [letter * w] * h


def leather(base="N", light="C", shade="U"):
    """Flat areas: a lit strip on the left of the top row, shade on the right column and the bottom row."""
    def painter(w, h):
        def pixel(x, y):
            if h >= 3 and y == h - 1 or w >= 3 and x == w - 1:
                return shade
            if y == 0 and x < (w * 6 + 9) // 10:
                return light
            return base
        return paint(w, h, pixel)
    return painter


def cloth(base, light, shade):
    """Thick cloth: straight folds one pixel wide every four, starting at different heights, lit top left."""
    def painter(w, h):
        def pixel(x, y):
            if w >= 4 and x == w - 1 or h >= 4 and y == h - 1:
                return shade
            if w >= 6 and x % 4 == 2 and y >= int(h * (0.3 if (x // 4) % 2 else 0.55)):
                return shade
            if w >= 6 and x % 4 == 1 and y < int(h * 0.5) or y == 0 and x < (w + 1) // 2:
                return light
            return base
        return paint(w, h, pixel)
    return painter


def metal(base="A", light="I", shade="S", glint="W"):
    """Polished metal: one sharp vertical band of light near the left edge, a glint at its top, dark right and bottom."""
    def painter(w, h):
        def pixel(x, y):
            if w >= 3 and x == w - 1 or h >= 3 and y == h - 1:
                return shade
            if w >= 4 and x == 1:
                return glint if y < max(1, h // 4) else light
            if y == 0:
                return light
            return base
        return paint(w, h, pixel)
    return painter


def framed(inner, edge):
    """A painter's grid inside a one pixel border."""
    def painter(w, h):
        g = inner(w, h)
        return paint(w, h, lambda x, y: edge if x in (0, w - 1) or y in (0, h - 1) else g[y][x])
    return painter


def stitched(grid, letter, inset=1):
    """Stitches (one pixel in two) along the four sides of a grid, inset pixels from its edge."""
    w, h = len(grid[0]), len(grid)
    return paint(w, h, lambda x, y: letter if (y in (inset, h - 1 - inset) and inset <= x < w - inset and x % 2 == 0
                                               or x in (inset, w - 1 - inset) and inset <= y < h - inset and y % 2 == 0)
                 else grid[y][x])


def flip(grid):
    return [line[::-1] for line in grid]


def setup(name, materials):
    k.setup(name, PALETTE, materials)


def box(name, low, high, material, hide="", **grids):
    """Cube with the faces named in hide left out (those lying against the body or inside another cube)."""
    for face in hide.split():
        grids[face] = None
    return Cube(name, list(low), list(high), material, **grids)


def plane(name, x0, y0, x1, y1, z, material, back, front=None, thick=0.3, **extra):
    """A cut-out picture standing in the plane z (its back face at z): only its two large faces are drawn. back is
    the grid seen from behind the player (left column = his left), front the one seen from the front (the back's
    mirror when missing)."""
    return Cube(name, [x0, y0, z], [x1, y1, z + thick], material, north=back, south=front or flip(back), west=None,
                east=None, up=None, down=None, **extra)


def glow_face(name, low, high, face, grid):
    """One emissive face (drawn at full light), a hair off the surface it lies on."""
    faces = {f: None for f in v.FACES}
    faces[face] = grid
    return Cube(name, list(low), list(high), "glow", True, **faces)


def turned(cubes, rot, origin):
    """The same rotation around the same pivot for a group of cubes: they move as one piece."""
    for c in cubes:
        c.rotation, c.origin = list(rot), list(origin)
    return cubes


def both(cubes):
    """Cubes and their mirror on the other side of the body."""
    return cubes + [mirror(c) for c in cubes]
