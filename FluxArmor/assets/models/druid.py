"""Palette and painters shared by the two druid sets (gen_sylve_bbmodel.py, gen_ancien_bbmodel.py).

Light comes from the top left. Each material has its own way of being painted: matt cloth with lit folds, bark with a
broken vertical grain and cracks, smoother old wood, dark fibrous roots carrying a little moss, leaves lit on one
side, braided roots for the belts.
"""
from setkit import paint

PALETTE = {"D": "#263D30", "M": "#405B3E", "F": "#58754A", "S": "#839477", "O": "#73764A",  # greens, deep to olive
           "T": "#A2B88A", "P": "#BDCAA0", "I": "#D9D8B5", "J": "#C3B77A",  # tender, pale, plant ivory, pollen
           "R": "#352C25", "B": "#514034", "W": "#73583D", "C": "#9A7953",  # root, bark, old wood, light wood
           "U": "#514236", "L": "#A89D7E", "G": "#59634A"}  # earthy leather, linen, mossy stone


def fabric(w, h):
    """Deep forest cloth: folds caught by the light, starting at different heights."""
    def pixel(x, y):
        fold = w >= 4 and x % 4 == 1
        start = int(h * 0.25) + ((x // 4) % 2) * max(1, int(h * 0.25))
        if fold and start <= y < h - 1 - (x // 4) % 2:
            return "M"
        if w >= 3 and x == 0 and y < h * 0.35:
            return "M"
        return "D"
    return paint(w, h, pixel)


def moss(w, h):
    """Moss green cloth of the mantles and panels: lit strip on the left, shade on the right and at the bottom."""
    def pixel(x, y):
        if w >= 3 and x == w - 1 or h >= 3 and y == h - 1:
            return "D"
        if w >= 3 and x == 0 and y < h * 0.5:
            return "F"
        return "M"
    return paint(w, h, pixel)


def panel(w, h):
    """Tabard panel: moss cloth, linen border on the lit side, an uneven end."""
    def pixel(x, y):
        if y == h - 1:
            return "L" if x % 2 == 0 else "D"
        if w >= 3 and x == 0:
            return "L"
        if w >= 3 and x == w - 1:
            return "D"
        return "M"
    return paint(w, h, pixel)


def linen(w, h):
    return paint(w, h, lambda x, y: "O" if h >= 2 and y == h - 1 or w >= 3 and x == w - 1 else ("I" if (x, y) == (0, 0) else "L"))


def bark(w, h):
    """Bark: lit ridges and dark cracks running down, broken at different heights so no stripe repeats."""
    def pixel(x, y):
        if x % 3 == 0 and (y + 2 * x) % 5 != 4:
            return "C" if y == 0 and x == 0 else "W"
        if x % 3 == 2 and (2 * y + x) % 4 == 0:
            return "R"
        return "B"
    return paint(w, h, pixel)


def wood(w, h):
    """Old wood, smoother than bark: a few light veins, shade below and on the right."""
    def pixel(x, y):
        if h >= 3 and y == h - 1 or w >= 3 and x == w - 1:
            return "B"
        if (x + 3 * y) % 5 == 0:
            return "C"
        return "W"
    return paint(w, h, pixel)


def root(w, h):
    """Root: dark and fibrous, lit along its top, a little moss on it."""
    def pixel(x, y):
        if w * h >= 6 and (2 * x + 3 * y) % 11 == 5:
            return "M"
        if y == 0:
            return "W" if x == 0 and w >= 2 else "B"
        if w == 1 and y < h * 0.4:
            return "B"
        return "R"
    return paint(w, h, pixel)


def braid(w, h):
    """Braided roots of the belts: lit above, dark below, a strand crossing every third pixel."""
    return paint(w, h, lambda x, y: "B" if (x + y) % 3 == 0 else ("W" if y == 0 else "R"))


def leaf(w, h):
    """Leaf: lit towards the top left, a darker edge, one tender pixel."""
    def pixel(x, y):
        if (x, y) == (0, 0):
            return "T"
        if x + y <= 1:
            return "S"
        if w >= 2 and x == w - 1 and y == h - 1:
            return "D"
        return "M" if (w >= 3 and x == w - 1 or h >= 3 and y == h - 1) else "F"
    return paint(w, h, pixel)


def sprout(w, h):
    """Young shoot."""
    return paint(w, h, lambda x, y: "P" if (x, y) == (0, 0) else ("F" if w * h >= 2 and x == w - 1 and y == h - 1 else "T"))


def leather(w, h):
    def pixel(x, y):
        if h >= 3 and y == h - 1 or w >= 3 and x == w - 1:
            return "R"
        if w >= 3 and h >= 2 and y == 0 and x <= 1:
            return "W"
        return "U"
    return paint(w, h, pixel)


def seed(w, h):
    return paint(w, h, lambda x, y: "I" if (x, y) == (0, 0) else ("O" if x == w - 1 and y == h - 1 and w * h >= 4 else "J"))


MATERIALS = {"fabric": (fabric, "M", "D"), "moss": (moss, "F", "D"), "panel": (panel, "F", "D"), "linen": (linen, "I", "O"),
             "bark": (bark, "W", "R"), "wood": (wood, "C", "B"), "root": (root, "B", "R"), "braid": (braid, "B", "R"),
             "leaf": (leaf, "S", "M"), "sprout": (sprout, "P", "F"), "leather": (leather, "W", "R"), "seed": (seed, "I", "O")}

# Old flower seen from the front: four ivory petals around a pollen heart, nothing in the corners.
FLOWER = [".I.", "IJI", ".I."]
# The seed at the heart of the emblem, alive.
SEED = ["ij", "jJ"]
