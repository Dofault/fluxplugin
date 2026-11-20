"""Palette and painters shared by the two cryomancer sets (gen_hivers_bbmodel.py, gen_givre_bbmodel.py).

Light comes from the top left. Each material has its own way of being painted: matt cloth with straight folds, leather
with one lit corner, forged silver with a short glint on the top edge, crystal cut in three flat facets.
"""
from setkit import paint

PALETTE = {"N": "#202B3B", "A": "#344657", "E": "#506A7C", "F": "#718D9E",  # night, slate, steel blue, cold blue
           "G": "#8EBCCB", "Y": "#A8D7DC", "W": "#D7E9E8", "X": "#EDF3EE",  # glacier, pale cyan, frost, snow
           "D": "#485565", "S": "#8295A5", "L": "#BBCBD0", "P": "#D5DEDA",  # silver: dark, cold, light, platinum
           "C": "#414B59", "R": "#596775", "M": "#536C79"}  # blue grey leather, cold leather, iced stone


def fabric(w, h):
    """Slate cloth of the coats: straight folds starting at different heights, a lit strip on the left."""
    def pixel(x, y):
        fold = w >= 4 and x % 4 == 2
        start = int(h * 0.3) + ((x // 4) % 2) * max(1, int(h * 0.25))
        if fold and start <= y < h - (x // 4) % 2:
            return "N"
        if w >= 3 and x == w - 1:
            return "N"
        if w >= 3 and x == 0 and y < h * 0.4:
            return "E"
        return "A"
    return paint(w, h, pixel)


def dark(w, h):
    """Night blue cloth: collars and trousers."""
    return paint(w, h, lambda x, y: "A" if w >= 3 and (x == 0 and y < h * 0.5 or x == 1 and y == 0) else "N")


def panel(w, h):
    """Tabard cloth: silver border on the lit side, shade on the other, frost gathering on the end."""
    def pixel(x, y):
        if y == h - 1:
            return "W" if x == 1 and w >= 3 else "F"
        if w >= 3 and x == 0:
            return "S"
        if w >= 3 and x == w - 1:
            return "A"
        return "E"
    return paint(w, h, pixel)


def leather(w, h):
    def pixel(x, y):
        if h >= 3 and y == h - 1 or w >= 3 and x == w - 1:
            return "N"
        if w >= 3 and h >= 2 and y == 0 and x <= 1:
            return "R"
        return "C"
    return paint(w, h, pixel)


def hide(w, h):
    """Cold leather of the reinforcements, lighter than the straps."""
    def pixel(x, y):
        if h >= 3 and y == h - 1 or w >= 3 and x == w - 1:
            return "C"
        if w >= 3 and h >= 2 and y == 0 and x <= 1:
            return "F"
        return "R"
    return paint(w, h, pixel)


def silver(w, h):
    """Forged silver: a glint on the left of the top edge, shade below and on the right, never an outline."""
    def pixel(x, y):
        if h >= 3 and y == h - 1 or w >= 3 and x == w - 1:
            return "D"
        if y == 0 and x < (w + 1) // 2:
            return "P" if x == 1 and w >= 3 else "L"
        return "S"
    return paint(w, h, pixel)


def steel(w, h):
    """Dark silver, the metal in the shade."""
    def pixel(x, y):
        if h >= 3 and y == h - 1:
            return "N"
        if y == 0 and w >= 3 and h >= 2 and x < (w + 1) // 2:
            return "L" if x == 1 else "S"
        return "D"
    return paint(w, h, pixel)


def crystal(w, h):
    """Cut ice, solid: a pale facet towards the light, the glacier body, a steel blue facet in the shade."""
    def pixel(x, y):
        t = (x + 0.5) / w + (y + 0.5) / h
        if (x, y) == (0, 0) and w * h >= 2:
            return "W"
        if t < 0.75:
            return "Y"
        return "E" if t > 1.4 else "G"
    return paint(w, h, pixel)


def frost(w, h):
    """Rimed trim."""
    return paint(w, h, lambda x, y: "W" if y == 0 and x % 3 == 1 else "F")


MATERIALS = {"fabric": (fabric, "E", "N"), "dark": (dark, "A", "N"), "panel": (panel, "F", "A"),
             "leather": (leather, "R", "N"), "hide": (hide, "F", "C"), "silver": (silver, "L", "D"),
             "steel": (steel, "S", "N"), "crystal": (crystal, "Y", "E"), "frost": (frost, "F", "A")}

# Heart of a crystal that glows: the pale facet and its spark.
CORE = ["wy", "yG"]
# Silver lozenge mount: a frame around a dark bed, the crystal sits on it.
MOUNT = ["PLLS", "LDDS", "LDDD", "SSDD"]
