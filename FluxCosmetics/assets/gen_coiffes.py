#!/usr/bin/env python3
"""Head cosmetics of FluxCosmetics: five hats, each one item model worn on the head (cosmetic key `model:`).

    <venv with Pillow and numpy>/python3 gen_coiffes.py [--deploy] [preview folder [hat...]]

Without --deploy nothing leaves the preview folder (six views per hat, atlas). With --deploy it writes the item
models and textures of the namespace fluxcosmetics in the ItemsAdder contents of Labs (then /iazip).

- coursier: courier's cap, teal cloth, leather visor, brass wings on the brow, a rust-tipped feather on the left;
- couchant: show-off sunglasses, gold brow bar, lenses going from night violet to sunset amber;
- cueilleur: gatherer's bucket hat, sand linen, moss band, drooping brim, a sprig with one berry on the right;
- convoyeur: caravan guard's cowboy hat, curled side brims, creased crown, dark band with a brass badge and beads;
- endurant: sport headband, crimson cloth with a cream stripe, knotted at the back with two uneven tails.

Frame, grids and faces: FluxArmor/assets/models/setkit.py (one skin pixel per unit, head from y 24 to 32, +z forwards,
+x towards the player's left). Light comes from the top left. The skin's hat layer ends half a pixel outside the head:
whatever wraps the head goes one pixel out so that layer never shows through.
"""
import os
import sys

from coiffes_kit import Cube, k, mirror, paint, soft, stamp, v
import coiffes_chapeaux

NS = "fluxcosmetics"
# The folder of this namespace has no resourcepack/ level (gen_icone.py): its root is assets/fluxcosmetics.
ASSETS = v.LABS + "/ItemsAdder/contents/" + NS


# ---------------------------------------------------------------------------------------- courier's cap


def coursier():
    k.setup("coursier", {"T": "#2F6166", "S": "#234A4F", "L": "#44807F", "D": "#1A363B", "V": "#4E362B", "U": "#6E4E3B",
                         "B": "#C9A14A", "C": "#E6DCC3", "R": "#B5533C"},
            {"cloth": (soft("T", "L", "S"), "L", "D"), "leather": (soft("V", "U", "V"), "U", "D"),
             "brass": (lambda w, h: ["B" * w] * h, "B", "B"), "feather": (lambda w, h: ["C" * w] * h, "R", "C")})
    cloth = k.FILL["cloth"]
    front = stamp(cloth(10, 3), 2, 0, ["BB  BB", " BCCB "])  # brass wings around a cream heart
    back = stamp(cloth(10, 3), 3, 1, ["DDDD", "VVBV"])  # opening and its strap
    seams = paint(8, 8, lambda x, y: "T" if x in (3, 4) or y in (3, 4) else "L")
    return [
        Cube("calotte", [-5, 30, -5], [5, 33, 5], "cloth", south=front, north=back),
        Cube("calotte_haut", [-4, 33, -4], [4, 34, 4], "cloth", up=seams, down=None),
        Cube("bouton", [-1, 34, -1], [1, 35, 1], "brass", down=None),
        # Visor leaning down, lighter in the middle of its top.
        Cube("visiere", [-4, 30, 5], [4, 31, 9], "leather", rot=(12, 0, 0), origin=[0, 30.5, 5], north=None,
             up=["VVVVVVVV", "VUUUUUUV", "VUUUUUUV", "VVVVVVVV"]),
        # Feather tucked on the left, leaning back.
        Cube("plume", [5, 31, -3], [6, 35, -1], "feather", rot=(-20, 0, 0), origin=[5.5, 31, -2],
             east=["RR", "CR", "CC", "BC"], west=["RR", "RC", "CC", "CB"], north=["R", "C", "C", "C"],
             south=["R", "C", "C", "B"]),
    ]


# ---------------------------------------------------------------------------------------- sunset sunglasses


def couchant():
    k.setup("couchant", {"G": "#D9A93F", "H": "#F2D27A", "O": "#9C722A", "D": "#2A1F3D", "M": "#6B3A5E", "A": "#C96F3A",
                         "W": "#8A5A78"},
            {"gold": (soft("G", "H", "O"), "H", "O"), "lens": (lambda w, h: ["D" * w] * h, "D", "A")})
    lens = ["DWDD", "MMMM", "AAAA"]  # night violet down to amber, one soft glint (never white: it would read as an eye)
    return [
        Cube("barre", [-5, 29, 5], [5, 30, 6], "gold", north=None),
        Cube("verre_droit", [-5, 26, 5], [-1, 29, 6], "lens", south=lens, up=None, west="O", east="O", down="O"),
        Cube("verre_gauche", [1, 26, 5], [5, 29, 6], "lens", south=lens, up=None, west="O", east="O", down="O"),
        Cube("branche_droite", [-5, 29, -2], [-4.5, 30, 5], "gold", east=None, south=None),
        Cube("branche_gauche", [4.5, 29, -2], [5, 30, 5], "gold", west=None, south=None),
    ]


# ---------------------------------------------------------------------------------------- gatherer's bucket hat


def cueilleur():
    k.setup("cueilleur", {"N": "#C9B58A", "S": "#A8946B", "L": "#DDCDA6", "U": "#8A7856", "M": "#5E7348", "K": "#46583A",
                          "F": "#7FA05A", "R": "#B5483F"},
            {"linen": (soft("N", "L", "S"), "L", "U"), "brim": (soft("N", "N", "S"), "N", "U"),
             "leaf": (lambda w, h: ["F" * w] * h, "F", "K")})
    band = stamp(k.FILL["linen"](10, 4), 0, 3, "MMMMMMMMMK")  # moss band at the foot of the crown
    return [
        Cube("calotte", [-5, 31, -5], [5, 35, 5], "linen", sides=band, down=None),
        # Brim drooping over the brow: one step out, then a skirt two pixels tall.
        Cube("bord_haut", [-6, 30, -6], [6, 31, 6], "brim"),
        Cube("bord_bas", [-7, 28, -7], [7, 30, 7], "brim"),
        # Sprig tucked in the band on the right, one berry at its foot.
        Cube("brin", [-6, 31, 0], [-5, 35, 2], "leaf", rot=(-15, 0, 0), origin=[-5.5, 31, 1], east=None,
             west=["FF", "FM", "KF", "KR"], north=["F", "F", "K", "K"], south=["F", "M", "F", "R"]),
    ]


# ---------------------------------------------------------------------------------------- caravan guard's hat


def convoyeur():
    k.setup("convoyeur", {"T": "#9A6B45", "S": "#7A5236", "D": "#563826", "L": "#B98A5E", "N": "#3B2A26", "B": "#C8A24B",
                          "Q": "#4FA6A0", "C": "#D9C3A0"},
            {"hide": (soft("T", "L", "S"), "L", "D")})
    hide = k.FILL["hide"]
    banded = stamp(hide(10, 2), 0, 1, "N" * 10)  # dark band at the foot of the crown
    side = Cube("bord_droit", [-9, 30, -7], [-5, 31, 7], "hide", rot=(0, 0, -25), origin=[-5, 30.5, 0], east=None,
                up=["LTTT"] * 14, west="L")  # curled up, its edge catching the light
    ridge = Cube("crete_droite", [-4, 35, -4], [-2, 36, 4], "hide", down=None)
    return [
        Cube("bord_centre", [-5, 30, -8], [5, 31, 8], "hide", up="T"),
        side, mirror(side),
        Cube("calotte_bas", [-5, 31, -5], [5, 33, 5], "hide", sides=banded, down=None,
             south=stamp(banded, 4, 0, ["BB", "BB"]),  # brass badge over the band
             west=stamp(banded, 2, 1, "Q Q Q")),  # three turquoise beads on the right
        Cube("calotte_haut", [-4, 33, -4], [4, 35, 4], "hide", up="S", down=None),  # the crease lies in shade
        ridge, mirror(ridge),
    ]


# ---------------------------------------------------------------------------------------- sport headband


def endurant():
    k.setup("endurant", {"R": "#A63A3A", "S": "#7E2B30", "L": "#C4544B", "C": "#EADFC8", "G": "#D4A84A"},
            {"cloth": (soft("R", "L", "S"), "L", "S")})

    def band(w):
        return ["L" * (w * 6 // 10) + "R" * (w - w * 6 // 10), "C" * w, "R" * (w - 1) + "S"]

    tilt_a = dict(rot=(10, 0, -12), origin=[-1, 28, -5.5])
    tilt_b = dict(rot=(10, 0, 14), origin=[1, 28, -5.5])
    return [
        Cube("avant", [-5, 28, 4], [5, 31, 5], "cloth", north=None, south=stamp(band(10), 4, 1, "GG")),
        Cube("arriere", [-5, 28, -5], [5, 31, -4], "cloth", south=None, north=band(10)),
        Cube("cote_droit", [-5, 28, -4], [-4, 31, 4], "cloth", east=None, north=None, south=None, west=band(8)),
        Cube("cote_gauche", [4, 28, -4], [5, 31, 4], "cloth", west=None, north=None, south=None, east=band(8)),
        # Knot and its two tails, the right one longer.
        Cube("noeud", [-1.5, 28, -6], [1.5, 31, -5], "cloth", south=None, north=["LRR", "RSR", "RRS"]),
        Cube("pan_long", [-2, 23, -6], [0, 28, -5], "cloth", north=["LR", "RR", "RS", "CC", "RS"],
             south=["RS", "RS", "RS", "CC", "SS"], **tilt_a),
        Cube("pan_court", [0, 25, -6], [2, 28, -5], "cloth", north=["RR", "CC", "RS"], south=["RS", "CC", "SS"], **tilt_b),
    ]


# name: builder. One module per part of the collection, the first five hats above.
HATS = {"coursier": coursier, "couchant": couchant, "cueilleur": cueilleur, "convoyeur": convoyeur, "endurant": endurant}
HATS.update(coiffes_chapeaux.HATS)
EMPTY = {g: [] for g in ("plastron_torse", "jambieres_torse", "plastron_bras_droit", "jambieres_jambe_droite",
                         "bottes_jambe_droite")}


def previews(name, cubes, folder):
    os.makedirs(folder, exist_ok=True)
    still, nod = k.scene(EMPTY, cubes), k.scene(EMPTY, cubes, 0, 20, 40)
    k.strip([(still, v.rot_y(0), False), (still, v.rot_x(12) @ v.rot_y(-32), False), (still, v.rot_y(-90), False),
             (still, v.rot_y(90), False), (still, v.rot_x(12) @ v.rot_y(148), False), (still, v.rot_y(180), False),
             (still, v.rot_x(55) @ v.rot_y(-25), False), (nod, v.rot_x(30) @ v.rot_y(30), False)],
            os.path.join(folder, name + "_vues.png"), width=420, size=420, scale=17, centre=(0, 31))
    used = max(r[3] for r in k.MAIN.known.values())
    k.MAIN.image.crop((0, 0, k.ATLAS, used)).resize((k.ATLAS * 6, used * 6), 0).save(os.path.join(folder, name + "_atlas_x6.png"))


def gui(cubes):
    """Inventory icon framed on the hat itself: translation y and scale from the box of its cubes."""
    low = [min(c.low[i] for c in cubes) for i in range(3)]
    high = [max(c.high[i] for c in cubes) for i in range(3)]
    size = min(1.0, 13 / (1.6 * max(1.15 * (high[0] - low[0]), high[1] - low[1] + 2, 1.15 * (high[2] - low[2]))))
    return round(-((low[1] + high[1]) / 2 - 28) * 1.6 * size, 2), round(size, 2)


def deploy(name, cubes):
    shift, size = gui(cubes)
    model = v.item_model(v.item_elements(cubes, 1.6, 28),
                         {"rotation": [15, 155, 0], "translation": [0, shift, 0], "scale": [size, size, size]}, True)
    texture = "%s:item/coiffes/%s" % (NS, name)
    model["credit"] = "Influencraft, generated by plugins/fluxplugin/FluxCosmetics/assets/gen_coiffes.py"
    model["textures"] = {"main": texture, "particle": texture}
    os.makedirs(os.path.join(ASSETS, "textures/item/coiffes"), exist_ok=True)
    k.MAIN.image.save(os.path.join(ASSETS, "textures/item/coiffes", name + ".png"))
    v.write_json(os.path.join(ASSETS, "models/item", "coiffe_" + name + ".json"), model)
    v.write_json(os.path.join(ASSETS, "items", "coiffe_" + name + ".json"),
                 {"model": {"type": "minecraft:model", "model": "%s:item/coiffe_%s" % (NS, name)}})


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--deploy"]
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    only = [a for a in args[1:]]
    for name, build in HATS.items():
        if only and name not in only:
            continue
        cubes = build()
        print("  %-10s %2d cubes %3d faces" % (name, len(cubes), sum(len(c.faces()) for c in cubes)))
        if args:
            previews(name, cubes, args[0])
        if "--deploy" in sys.argv:
            deploy(name, cubes)
    if "--deploy" in sys.argv:
        print("Deployed to Labs: run /iazip.")
