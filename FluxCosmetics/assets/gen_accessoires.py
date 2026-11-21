#!/usr/bin/env python3
"""Body accessories of FluxCosmetics, the collection "Relics of Azureth": 22 cosmetics worn in the chest slot (bags,
capes, wings, weapons on the back, ties, a fox on the shoulder...), drawn in 3D on the torso by FluxArmor.

    <venv with Pillow and numpy>/python3 gen_accessoires.py [--deploy] [preview folder [accessory...]]

Without --deploy nothing leaves the preview folder (views of each accessory on a mannequin, atlas). With --deploy it
writes to Labs, for each accessory <id>:
- plugins/FluxArmor/models/acc_<id>.bbmodel and plugins/FluxArmor/sets/acc_<id>.yml: a FluxArmor set of one piece,
  fluxarmor:acc_<id>_chestplate, whose 3D follows the torso (then /fluxarmor reload, /fluxarmor build, /iazip);
- contents/fluxcosmetics: the inventory icon acc_<id> (item model and texture).
The cosmetics themselves (key, name, rarity) are the entries `cosmetics.<id>` of FluxCosmetics' config.yml.

Everything is painted with the forty colours of accessoires_kit.PALETTE. Frame, grids, faces: FluxArmor's setkit.py and
accessoires_kit.py. A model keeps to about ten cubes; faces lying against the body are not drawn (each face costs a
sixth of a texture layer in the pack), outlines that a box cannot give (feathers, membranes, hems) are cut out of a
picture on a plane.
"""
import os
import sys

from accessoires_kit import (BACK, Cube, both, box, cloth, flat, flip, framed, glow_face, k, leather, metal, mirror,
                             paint, plane, setup, stamp, stitched, turned, v)

NS = "fluxcosmetics"
ASSETS = v.LABS + "/ItemsAdder/contents/" + NS
SETS = v.LABS + "/FluxArmor/sets"

SKIN = 2.3  # in front of the chest, clear of the skin's jacket layer


def strap(name, x0, x1, y0=13, y1=24, grid=None):
    """A leather strap down the front of the chest, 0.6 thick."""
    return box(name, [x0, y0, SKIN], [x1, y1, SKIN + 0.6], "strap", hide="north up down", south=grid)


def straps(y0=13):
    """The two shoulder straps of a bag, a steel buckle painted on each."""
    h = 24 - y0
    grid = paint(2, h, lambda x, y: "A" if y in (h - 4, h - 3) else "CU"[x] if y < h - 4 else "UD"[x])
    return [strap("sangle_droite", -4, -2, y0, grid=grid), strap("sangle_gauche", 2, 4, y0, grid=grid)]


def centred(grid, art, dy=0):
    """art stamped in the middle of grid, dy rows lower."""
    return stamp(grid, (len(grid[0]) - len(art[0])) // 2, (len(grid) - len(art)) // 2 + dy, art)


def cut(w, h, inside, colour):
    """Cut-out picture: colour(x, y) where inside(x, y), transparent elsewhere."""
    return paint(w, h, lambda x, y: colour(x, y) if inside(x, y) else ".")


def edged(grid, letter):
    """A cut-out picture with its outline (the pixels next to a transparent one or to the border) in that colour."""
    w, h = len(grid[0]), len(grid)

    def clear(x, y):
        return not (0 <= x < w and 0 <= y < h) or grid[y][x] == "."
    return paint(w, h, lambda x, y: "." if grid[y][x] == "." else letter
                 if clear(x - 1, y) or clear(x + 1, y) or clear(x, y - 1) or clear(x, y + 1) else grid[y][x])


# ---------------------------------------------------------------------------------------- 1. adventurer's backpack


def backpack_classic():
    setup("backpack_classic", {"leather": (leather(), "C", "D"), "dark": (leather("U", "N", "D"), "N", "D"),
                               "strap": (leather("N", "C", "U"), "C", "D")})
    hide = k.FILL["leather"]
    # Flap: dark rim, lighter heart, stitches, the strap of the buckle coming down its middle.
    flap = stamp(framed(flat("C"), "U")(11, 5), 1, 3, "NDNDNDNDN")
    flap = stamp(stamp(flap, 1, 1, "HHHHH"), 5, 0, ["N", "N", "N", "A", "I"])
    pocket = stamp(stamp(hide(6, 4), 0, 0, "UUUUUU"), 2, 1, ["AI"])
    body = stamp(hide(10, 9), 0, 5, "D" * 10)  # the shade under the flap
    side = stamp(k.FILL["dark"](3, 4), 0, 1, "DDD")
    pouch = box("poche_droite", [-7, 13, -6], [-5, 17, -3], "dark", hide="east", west=side)
    return [
        box("corps", [-5, 13, -6.5], [5, 22, BACK], "leather", hide="south", north=body),
        box("rabat", [-5.5, 19, -7.5], [5.5, 24, BACK], "leather", hide="south", north=flap, up="C", down="D"),
        box("poche", [-3, 14, -8], [3, 18, -6.5], "leather", hide="south", north=pocket),
        pouch, mirror(pouch),
    ] + straps()


# ---------------------------------------------------------------------------------------- 2. expedition backpack


def backpack_hiking():
    setup("backpack_hiking", {"canvas": (cloth("B", "Z", "1"), "Z", "1"), "roll": (flat("E"), "Q", "M"),
                              "strap": (leather("N", "C", "U"), "C", "D"), "steel": (metal(), "I", "S")})
    canvas = k.FILL["canvas"]
    body = stamp(stamp(canvas(11, 11), 0, 0, "1" * 11), 5, 0, ["N"] * 11)  # leather strap down the middle
    pocket = stamp(stamp(framed(canvas, "1")(7, 5), 1, 1, "AAAAA"), 3, 2, ["I"])  # zip and its pull
    small = stamp(framed(canvas, "1")(5, 3), 1, 1, "AAA")
    # Bedroll: ivory cloth rolled, two leather bands, the spiral of the roll on its ends.
    roll = paint(13, 3, lambda x, y: "N" if x in (2, 10) else "Q" if y == 0 else "M" if y == 2 else "E")
    end = ["MEM", "EME", "MEM"]
    side = stamp(framed(canvas, "1")(4, 6), 1, 1, "ZZ")
    pouch = box("poche_droite", [-7.5, 12, -7.5], [-5.5, 18, -3.5], "canvas", hide="east", west=side)
    rod = box("armature_droite", [-6.5, 10, -3.5], [-5.5, 25, BACK], "steel", hide="south east down")
    return [
        box("corps", [-5.5, 11, -8.5], [5.5, 22, BACK], "canvas", hide="south", north=body),
        box("haut", [-5, 22, -8], [5, 24, -3], "canvas", hide="south down", up="Z"),
        box("couchage", [-6.5, 24, -8], [6.5, 27, -5], "roll", hide="down", north=roll, south=roll, up=roll, west=end, east=end),
        box("poche", [-3.5, 12, -10], [3.5, 17, -8.5], "canvas", hide="south", north=pocket),
        box("poche_haute", [-2.5, 18, -9.5], [2.5, 21, -8.5], "canvas", hide="south", north=small),
        pouch, mirror(pouch), rod, mirror(rod),
        strap("sternum", -2, 2, 19, 20, grid=["NAAU"]),
    ] + straps(12)


# ---------------------------------------------------------------------------------------- 3 and 22. capes


def cape(prefix, outer, lining, hem_row, star, clasp, pointed):
    """A cape hanging from a mantle on the shoulders: one cut-out panel flaring down, leaning away from the legs.
    outer / lining: painters; hem_row(x, w): letter of the hem; star: the emblem of the back; pointed: one long point
    in the middle of the hem instead of a straight dagged one."""
    w, h = 14, 20

    def inside(x, y):
        inset = max(0, 2 - y // 4)  # ten wide under the mantle, fourteen from the ninth row
        if not inset <= x < w - inset:
            return False
        if pointed:
            return y < h - 5 + min(x, w - 1 - x) * 5 // 6
        return y < h - 1 or x % 4 != 3  # dagged hem

    def side(painter):
        g = painter(w, h)

        def colour(x, y):
            low = (h - 5 + min(x, w - 1 - x) * 5 // 6) if pointed else h
            if y >= low - 2:
                return hem_row(x, y - (low - 2))
            return g[y][x]
        return cut(w, h, inside, colour)
    back = centred(side(outer), star, -2)
    panel = plane(prefix + "_pan", -7, 2, 7, 22, -3.6, "cloth", back, side(lining), thick=0.5, rot=(8, 0, 0),
                  origin=[0, 22, -3])
    return [
        panel,
        box(prefix + "_mantelet", [-5, 21, -4.5], [5, 24, BACK], "cloth", hide="south up", down=None),
        box(prefix + "_col", [-4, 24, -5.5], [4, 26, -4.5], "collar", hide="down"),
        box(prefix + "_attache", [-4.5, 22, SKIN], [4.5, 24, SKIN + 0.6], "cloth", hide="north up down west east", south=clasp),
    ]


def cape_royal():
    setup("cape_royal", {"cloth": (cloth("R", "Y", "2"), "Y", "2"), "collar": (cloth("R", "P", "2"), "P", "2")})
    star = ["..L..", "..O..", "LOLOL", "..O..", "..4.."]  # four-pointed star
    star = [line.replace(".", " ") for line in star]
    return cape("royale", cloth("R", "Y", "2"), cloth("B", "Z", "1"), lambda x, r: "Y2"[(x + r) % 2] if r else "O", star,
                ["RRRROORRR", "22224O222"], False)


def cape_astral():
    setup("cape_astral", {"cloth": (cloth("V", "X", "5"), "X", "5"), "collar": (cloth("5", "V", "5"), "X", "5")})
    moon = [" 6X  ", "6X   ", "6X   ", "6X   ", " 6X6 ", "  66 "]
    cubes = cape("astrale", cloth("V", "X", "5"), _constellation, lambda x, r: "X" if r == 0 else "6" if x % 3 == 0 else "V",
                 moon, ["VVV5&5VVV", "55556&555"], True)
    # the gem of the clasp glows
    cubes.append(glow_face("astrale_gemme", [-0.5, 22.5, SKIN], [0.5, 23.5, SKIN + 0.62], "south", ["&"]))
    return cubes


def _constellation(w, h):
    """Lining of the astral cape: deep violet night, a few stars joined in one constellation."""
    stars = {(3, 3): "6", (6, 5): "&", (9, 3): "6", (10, 8): "G", (5, 10): "6", (8, 13): "&", (4, 15): "G"}
    links = {(4, 4), (5, 4), (7, 4), (8, 4), (10, 5), (10, 6), (9, 9), (7, 10), (6, 11), (7, 12), (6, 14), (5, 14)}
    return paint(w, h, lambda x, y: stars.get((x, y), "V" if (x, y) in links else "5"))


# ---------------------------------------------------------------------------------------- 4. wandering mage's scarf


def scarf_azure():
    def knit(w, h):  # knitted wool: an ordered weave, light on the upper stitches
        return paint(w, h, lambda x, y: "1" if y == h - 1 and h > 2 else "G" if y == 0 and x < w * 0.6 else
                     "Z" if (x + y) % 2 == 0 else "B")
    setup("scarf_azure", {"wool": (knit, "G", "1")})

    def tail(w, h, lit):
        g = knit(w, h)
        end = ["Y" * w, "RER"[:w] if w == 3 else "R" * w, "Y" * w, "2" * w]  # ruby end, an ivory lozenge
        return stamp(g, 0, h - 4, end) if lit else stamp(g, 0, h - 4, ["R" * w, "2" * w, "R" * w, "2" * w])
    return [
        box("tour", [-4, 21, -3], [4, 24, 3], "wool", hide="up down"),
        box("noeud", [0.5, 20, 3], [4.5, 23, 4.2], "wool", hide="north", south=["GZBZ", "ZBZ1", "B1B1"]),
        Cube("pan_avant", [1, 11, 3], [4, 21, 4], "wool", rot=(0, 0, -5), origin=[2.5, 21, 3.5], north=None, up=None,
             south=tail(3, 10, True), west=tail(1, 10, False), east=tail(1, 10, False)),
        box("pan_arriere", [-4, 14, -4], [-1, 21, -3], "wool", hide="south up", north=tail(3, 7, True),
            west=tail(1, 7, False), east=tail(1, 7, False)),
    ]


# ---------------------------------------------------------------------------------------- 5 and 6. tie, bow tie


def _collar():
    """Shirt collar under a tie: two ivory points."""
    grid = ["EEEE..EEEE", ".EEM..MEE."]
    return Cube("col", [-5, 22, SKIN], [5, 24, SKIN + 0.4], "silk", south=grid, north=None, west=None, east=None, up=None, down=None)


def necktie_ruby():
    setup("necktie_ruby", {"silk": (flat("R"), "Y", "2")})
    # Blade: narrow under the knot, wider towards its point; one vertical glint, gold lozenges down the middle.
    rows = [".RR.", ".YR.", ".YR.", ".YO.", "PYR2", "PYO2", "PRR2", "PYO2", "YRR2", "YRO2", ".R2.", ".2.."]
    blade = Cube("lame", [-2, 9, SKIN], [2, 21, SKIN + 0.5], "silk", south=rows, north=None, west=None, east=None, up=None, down=None)
    knot = box("noeud", [-1.5, 21, SKIN], [1.5, 24, SKIN + 1], "silk", hide="north up", south=["PYR", "YR2", "R22"])
    return [_collar(), blade, knot]


def bowtie_velvet():
    def velvet(w, h):  # matt velvet, a lit band under the top edge
        return paint(w, h, lambda x, y: "K" if y == h - 1 else "Z" if y == 1 and 0 < x < w - 1 else "B" if y == 0 else "1")
    setup("bowtie_velvet", {"silk": (velvet, "B", "K"), "gold": (flat("O"), "L", "4")})
    # Each wing pinched at the knot: diagonal folds running to it.
    wing = Cube("aile_droite", [-5, 20, SKIN], [-1, 24, SKIN + 1.2], "silk", rot=(0, 0, 10), origin=[-1, 22, SKIN],
                north=None, east=None, south=["BBB1", "ZZ1B", "11B1", "1KKK"])
    return [_collar(), wing, mirror(wing),
            box("noeud", [-1, 20.5, SKIN], [1, 23.5, SKIN + 1.8], "gold", hide="north", south=["LO", "O4", "44"])]


# ---------------------------------------------------------------------------------------- 7 and 8. straps


def _band(w, h):
    """Leather strap seen flat: lit edge, stitches down its middle."""
    return paint(w, h, lambda x, y: ("C" if x == 0 else "U" if x == w - 1 else "D" if y % 2 == 0 and w >= 3 else "N"))


def shoulder_strap():
    setup("shoulder_strap", {"strap": (_band, "C", "D"), "pouch": (leather(), "C", "D"), "steel": (metal(), "I", "S")})
    tilt = dict(rot=(0, 0, -31), origin=[0, 18, 0])  # from the left shoulder down to the right hip
    return [
        Cube("avant", [-1.5, 11.5, SKIN], [1.5, 24.5, SKIN + 0.6], "strap", north=None, up=None, down=None, **tilt),
        Cube("arriere", [-1.5, 11.5, -SKIN - 0.6], [1.5, 24.5, -SKIN], "strap", south=None, up=None, down=None,
             rot=(0, 0, 31), origin=[0, 18, 0]),  # the same line, seen through the body
        Cube("boucle", [-2, 17, SKIN + 0.6], [2, 20, SKIN + 1.1], "steel", north=None, south=["IWIS", "IDDS", "ASSS"], **tilt),
        box("sacoche", [-5, 10, SKIN], [-1, 14, SKIN + 2], "pouch", hide="north",
            south=stamp(stitched(leather()(4, 4), "D", 0), 1, 1, ["AI"]), up="C"),
    ]


def suspenders_leather():
    setup("suspenders_leather", {"strap": (_band, "C", "D"), "steel": (metal(), "I", "S")})
    front = stamp(_band(2, 12), 0, 9, ["AI", "AS", "SS"])  # steel clip at the waist
    back = dict(south=None, up=None, down=None, origin=[0, 18, 0])
    return [
        strap("avant_droite", -3.5, -1.5, 12, 24, grid=front), strap("avant_gauche", 1.5, 3.5, 12, 24, grid=front),
        # In the back the two straps cross.
        Cube("dos_a", [-1, 11.5, -SKIN - 0.6], [1, 24.5, -SKIN], "strap", rot=(0, 0, 21), **back),
        Cube("dos_b", [-1, 11.5, -SKIN - 0.7], [1, 24.5, -SKIN - 0.1], "strap", rot=(0, 0, -21), **back),
        Cube("croisement", [-1.5, 16.5, -SKIN - 1], [1.5, 19.5, -SKIN - 0.7], "steel", rot=(0, 0, 45), origin=[0, 18, 0],
             north=["IWS", "IAS", "SSS"], south=None, west=None, east=None, up=None, down=None),
    ]


# ---------------------------------------------------------------------------------------- 9. open vest


def vest_open():
    setup("vest_open", {"cloth": (cloth("B", "Z", "1"), "Z", "1")})
    # Right panel as seen from the front (its inner edge on the right): gold border, three buttons, a point at the hem.
    w, h = 3, 13

    def inside(x, y):
        return y < h - 1 or x == 2

    def colour(x, y):
        if x == 2:
            return "O" if y % 4 != 2 else "L"
        if y >= h - 2:
            return "4"
        return "Z" if x == 0 and y < 5 else "B" if x == 0 else "1"
    panel = cut(w, h, inside, colour)
    one = dict(north=None, west=None, east=None, up=None, down=None)
    lapel = Cube("revers_droit", [-4, 19, SKIN + 0.5], [-2, 24, SKIN + 1], "cloth", rot=(0, 0, 8), origin=[-3, 24, SKIN],
                 north=None, up=None, south=["RY", "RY", "RO", "2O", "44"], east="2", west="2", down="4")
    seams = paint(9, 12, lambda x, y: "4" if y == 11 else "1" if x in (2, 6) or x == 8 else "Z" if y == 0 and x < 5 else "B")
    return [
        Cube("pan_droit", [-4.5, 11, SKIN], [-1.5, 24, SKIN + 0.5], "cloth", south=panel, **one),
        Cube("pan_gauche", [1.5, 11, SKIN], [4.5, 24, SKIN + 0.5], "cloth", south=flip(panel), **one),
        lapel, mirror(lapel),
        Cube("dos", [-4.5, 12, -SKIN - 0.5], [4.5, 24, -SKIN], "cloth", north=seams, south=None, west=None, east=None, up=None, down=None),
    ]


# ---------------------------------------------------------------------------------------- 10. parade breastplate


def armor_ornamental():
    setup("armor_ornamental", {"steel": (metal(), "I", "S"), "dark": (metal("S", "A", "K", "I"), "A", "K")})
    steel = k.FILL["steel"]
    # Engraved sun: gold rays around the setting of the gem.
    sun = ["O   O   O", " 4  O  4 ", "  4OOO4  ", "  O   O  ", "OOO   OOO", "  O   O  ", "  4OOO4  ", " 4  O  4 ", "O   O   O"]
    chest = stamp(framed(steel, "S")(9, 10), 0, 0, ["O" * 9])
    chest = stamp(chest, 0, 1, sun)
    belly = stamp(steel(7, 3), 0, 1, "4O4O4O4")
    backplate = paint(8, 8, lambda x, y: "K" if x in (0, 7) or y == 7 else "A" if x in (2, 5) else "S")
    gem = ["6X", "XV"]
    return {"plastron_torse": [
        box("plastron", [-4.5, 14, SKIN], [4.5, 24, SKIN + 1.2], "steel", hide="north up", south=chest),
        box("abdomen", [-3.5, 11, SKIN], [3.5, 14, SKIN + 0.8], "steel", hide="north up", south=belly),
        Cube("gemme", [-1, 18.5, SKIN + 1.2], [1, 20.5, SKIN + 1.25], "glow", True, rot=(0, 0, 45), origin=[0, 19.5, SKIN + 1.2],
             south=gem, north=None, west=None, east=None, up=None, down=None),
        box("dorsale", [-4, 15, -SKIN - 1], [4, 23, -SKIN], "dark", hide="south up", north=backplate),
    ], "plastron_bras_droit": [
        # One pauldron on each arm, leaning outwards, rimmed with gold.
        Cube("epauliere", [-9, 22, -3], [-4, 25, 3], "steel", rot=(0, 0, 10), origin=[-4, 24, 0], east=None,
             south=["IWIAS", "IAAAS", "OOOO4"], north=["IAAAS", "IAAAS", "OOOO4"],
             west=["IWIAAS", "IAAAAS", "OOOOO4"], up=["IIIIA"] + ["IAAAS"] * 4 + ["ASSSS"], down="K"),
    ]}


# ---------------------------------------------------------------------------------------- 11, 12, 13. wings


def _columns(top, bottom, colour, height):
    """Cut-out wing from the rows reached by each column (root first): top[x] <= y < bottom[x]."""
    return cut(len(top), height, lambda x, y: top[x] <= y < bottom[x], colour)


def wings_angel():
    setup("wings_angel", {"feather": (flat("E"), "Q", "M")})
    # Great wing, root on the left: its upper edge climbs to a round shoulder, the long feathers hang at the tip.
    top = [9, 7, 5, 4, 3, 2, 2, 1, 1, 0, 0, 1]
    bottom = [13, 15, 17, 18, 19, 19, 20, 20, 19, 17, 14, 8]

    def feather(x, y):
        d = y - top[x]
        if d < 2:
            return "Q"  # the lit arch of the wing
        if bottom[x] - y <= 4:  # long feathers: one in two parted by a beige line, tips in shade
            return "M" if x % 2 == 0 or bottom[x] - y == 1 else "E"
        if d in (5, 9):  # rows of feathers laid over each other
            return "M" if x % 3 else "E"
        return "Q" if d in (6, 10) and x % 3 == 1 else "E"
    great = _columns(top, bottom, feather, 20)
    # Coverts, over the root: small pearl feathers.
    c_top, c_bottom = [3, 2, 1, 0, 0, 1, 2], [7, 8, 9, 9, 8, 7, 5]
    covert = _columns(c_top, c_bottom, lambda x, y: "M" if c_bottom[x] - y == 1 else "Q" if (x + y) % 3 else "E", 9)
    sweep = (0, -18, -14)  # swept back from the shoulders, tips raised
    pivot = [-1.5, 21, -3]
    right = [
        plane("grande_droite", -13.5, 10, -1.5, 30, -3.6, "feather", flip(great), rot=sweep, origin=pivot),
        plane("couverture_droite", -8.5, 19, -1.5, 28, -4.1, "feather", flip(covert), rot=sweep, origin=pivot),
    ]
    harness = stamp(["QEEM", "EEEM", "EOOM", "EEEM", "MMMM"], 0, 0, "QEEM")
    return both(right) + [box("attache", [-2, 18, -3.8], [2, 23, BACK], "feather", hide="south", north=harness)]


def wings_demon():
    setup("wings_demon", {"skin": (flat("R"), "Y", "2"), "bone": (leather("S", "A", "K"), "A", "K")})
    # Membrane, root on the left: the arm rises to the wrist claw, three fingers end in three points.
    top = [6, 5, 4, 3, 2, 1, 0, 1, 3, 4, 6, 7, 9]
    bottom = [12, 14, 16, 15, 13, 15, 17, 16, 14, 15, 16, 14, 11]
    fingers = {2: 16, 6: 17, 10: 16}  # column of each finger bone: its last row

    def skin(x, y):
        if y - top[x] < 2:
            return "K" if y == top[x] else "S"  # the arm, dark bone
        if x in fingers:
            return "S"
        if bottom[x] - y == 1:
            return "2"
        near = min(abs(x - f) for f in fingers)
        return "#" if near == 1 and y % 3 == 0 else "Y" if near == 2 and y - top[x] < 6 else "R"
    membrane = _columns(top, bottom, skin, 18)
    sweep, pivot = (0, -20, -6), [-1.5, 22, -3]
    right = [
        plane("membrane_droite", -14.5, 9, -1.5, 27, -3.6, "skin", flip(membrane), rot=sweep, origin=pivot),
        # The horn of the wrist, the one volume of the wing: it keeps the silhouette sharp from the side.
        Cube("griffe_droite", [-8.5, 26, -4.4], [-6.5, 30, -3.4], "bone", rot=sweep, origin=pivot, down=None,
             north=["KA", "SA", "SS", "KS"], south=["AK", "AS", "SS", "SK"]),
    ]
    spine = ["SAAK", "KSSK", "SAAK", "KSSK", "SAAK", "KKKK"]
    return both(right) + [box("echine", [-2, 17, -3.8], [2, 23, BACK], "bone", hide="south", north=spine)]


def wings_butterfly():
    setup("wings_butterfly", {"wing": (flat("V"), "X", "5"), "body": (leather("5", "V", "K"), "V", "K")})

    def lobe(top, bottom, eye, height):
        ex, ey = eye

        def colour(x, y):
            d = round(((x - ex) ** 2 + (y - ey) ** 2) ** 0.5)
            if d == 0:
                return "G"
            if d == 1:
                return "6" if (x - ex, y - ey) == (-1, -1) else "X"
            if d == 2:
                return "1"
            return "Z" if (x + y) % 4 == 0 else "B"
        return edged(edged(_columns(top, bottom, colour, height), "V"), "5")  # two rings: deep violet, then royal
    # Upper lobe: tall and round. Lower lobe: smaller, drawn out into a tail.
    upper = lobe([5, 3, 2, 1, 0, 0, 0, 1, 2, 4], [9, 10, 11, 11, 11, 11, 10, 9, 8, 6], (5, 5), 11)
    lower = lobe([0, 0, 0, 1, 1, 2, 3, 5], [5, 7, 8, 9, 10, 10, 9, 7], (3, 4), 10)
    pivot = [-1, 20, -3]
    right = [
        plane("haute_droite", -11, 19, -1, 30, -3.6, "wing", flip(upper), rot=(0, -22, -6), origin=pivot),
        plane("basse_droite", -9, 10, -1, 20, -3.9, "wing", flip(lower), rot=(0, -14, 4), origin=pivot),
    ]
    return both(right) + [box("corps", [-1, 15, -3.8], [1, 23, BACK], "body", hide="south",
                              north=["V5", "X5", "V5", "5K", "V5", "5K", "V5", "5K"])]


# ---------------------------------------------------------------------------------------- 14. hunter's quiver


def quiver_hunter():
    setup("quiver_hunter", {"leather": (leather(), "C", "D"), "rim": (leather("U", "N", "D"), "N", "D"),
                            "strap": (_band, "C", "D")})
    target = [" DDD ", "D   D", "D I D", "D   D", " DDD "]  # painted target
    body = centred(stitched(leather()(5, 12), "D", 0), target, -1)

    def arrows(columns, w):
        """Fletched arrows standing in the quiver: ivory vanes around a wooden shaft."""
        def colour(x, y):
            if x in columns:
                return "N" if y >= 5 else "D" if y == 0 else "E"
            return "M" if y in (1, 2, 3) and (x - 1 in columns or x + 1 in columns) and (y != 1 or x + 1 in columns) else "."
        return paint(w, 7, colour)
    cubes = [
        box("corps", [-2.5, 11, -6.5], [2.5, 23, BACK], "leather", hide="south", north=body),
        box("bord", [-3, 22, -7], [3, 24, -3], "rim", hide="south down", up="D"),
        plane("fleches_a", -3.5, 24, 3.5, 31, -6, "leather", arrows({1, 5}, 7)),
        plane("fleches_b", -3.5, 23, 3.5, 30, -4.6, "leather", arrows({0, 3, 6}, 7)),
    ]
    # its mouth towards the right shoulder
    return turned(cubes, (0, 0, 12), [0, 17, -4]) + [Cube("baudrier", [-1, 11.5, SKIN], [1, 24.5, SKIN + 0.6], "strap", north=None, up=None, down=None,
                         rot=(0, 0, 31), origin=[0, 18, 0])]


# ---------------------------------------------------------------------------------------- 15 and 16. blades


def _baldric(angle):
    return Cube("baudrier", [-1, 11.5, SKIN], [1, 24.5, SKIN + 0.6], "strap", north=None, up=None, down=None,
                rot=(0, 0, angle), origin=[0, 18, 0])


def katana_back():
    setup("katana_back", {"lacquer": (metal("K", "S", "K", "A"), "S", "K"), "wrap": (flat("R"), "Y", "2"),
                          "guard": (metal("4", "O", "S", "L"), "O", "S"), "strap": (_band, "N", "D")})
    # Scabbard: night lacquer, a blue line down its middle, gold cord knotted near the mouth, steel tip.
    scabbard = paint(2, 16, lambda x, y: "O" if y in (2, 3) else "A" if y >= 14 else "1" if x == 0 else "K")
    edge = paint(2, 16, lambda x, y: "4" if y in (2, 3) else "S" if y >= 14 else "K")
    braid = paint(2, 6, lambda x, y: "O" if y == 0 else "Y" if (x + y) % 2 == 0 else "2")  # braided grip, gold cap
    cubes = [
        box("fourreau", [-1, 6, -4.5], [1, 22, BACK], "lacquer", hide="south up", north=scabbard, west=edge, east=edge),
        box("garde", [-2, 22, -5.2], [2, 23, -1.8], "guard"),
        box("poignee", [-1, 23, -4.5], [1, 29, BACK], "wrap", hide="down", north=braid, south=braid, west=braid, east=braid, up="O"),
    ]
    # hilt over the right shoulder, clear of the head
    return turned(cubes, (0, 0, 30), [0, 17, -3.5]) + [_baldric(31)]


def sword_back():
    setup("sword_back", {"steel": (metal(), "I", "S"), "gold": (metal("O", "L", "4", "L"), "L", "4"),
                         "grip": (leather("U", "N", "D"), "N", "D"), "strap": (_band, "C", "D")})
    # Wide blade with a dark fuller, ground to a point.
    blade = paint(3, 12, lambda x, y: "IAS"[x] if y % 5 else "WAS"[x])
    tip = ["IAS", "IAS", ".A."]
    cubes = [
        box("lame", [-1.5, 9, -3.6], [1.5, 21, BACK], "steel", hide="south up down", north=blade),
        plane("pointe", -1.5, 6, 1.5, 9, -3.3, "steel", tip),
        box("garde", [-4, 21, -4.2], [4, 23, -2.2], "gold", north=["4LLOOLL4", "44444444"]),
        box("fusee", [-1, 23, -3.9], [1, 28, BACK], "grip", hide="up down", north=["ND", "UD", "ND", "UD", "ND"],
            south=["ND", "UD", "ND", "UD", "ND"]),
        box("pommeau", [-1.5, 28, -4.2], [1.5, 31, -2.2], "gold", hide="down"),
    ]
    # hilt over the left shoulder: the katana's mirror, clear of the head
    return turned(cubes, (0, 0, -38), [0, 17, -3]) + [_baldric(-31)]


# ---------------------------------------------------------------------------------------- 17. lion shield


def shield_back():
    setup("shield_back", {"wood": (leather("S", "A", "K"), "A", "K"), "strap": (_band, "C", "D")})
    w, h = 12, 17

    def inside(x, y):  # heater shield: straight top, sides curving into a point
        inset = 0 if y < 9 else (y - 8 + 1) * 2 // 3
        return inset <= x < w - inset
    field = cut(w, h, inside, lambda x, y: "Z" if x < 5 and y < 3 else "1" if x >= 9 or y >= 13 else "B")
    face = edged(field, "O")
    face = stamp(face, 1, 0, "L" * 6)  # the lit part of the gold rim
    lion = ["  OL  O ", " OLLO O ", " OEL OO ", "OOLOOO  ", "  LLOO  ", " OLLO O ", " OLLOO  ", "OO  OO  ", "O   O   "]
    face = stamp(face, 2, 3, lion)  # lion rampant, facing the lit side
    return [
        Cube("blason", [-6, 6, -4.8], [6, 23, -4.5], "wood", north=face, south=None, west=None, east=None, up=None, down=None),
        box("planche", [-6, 14, -4.5], [6, 23, BACK], "wood", hide="north south"),
        box("pointe", [-3.5, 8, -4.5], [3.5, 14, BACK], "wood", hide="north south up"),
        _baldric(31),
    ]


# ---------------------------------------------------------------------------------------- 18. arcane jetpack


def jetpack_arcane():
    setup("jetpack_arcane", {"steel": (metal(), "I", "S"), "copper": (leather("N", "C", "U"), "C", "U"),
                             "dark": (metal("S", "A", "K", "I"), "A", "K"), "strap": (leather("N", "C", "U"), "C", "D")})
    # Tank: riveted steel, two copper hoops.
    tank = paint(4, 10, lambda x, y: "NCNU"[x] if y in (2, 7) else "IWAS"[x] if y < 2 else "IAAS"[x] if y < 9 else "SSSK"[x])
    right = [
        box("reservoir_droit", [-6, 12, -7], [-2, 22, -3], "steel", hide="south up", north=tank, west=tank, east=tank),
        box("capuchon_droit", [-5.5, 22, -6.5], [-2.5, 24, -3.5], "copper", hide="south down"),
        box("tuyere_droite", [-5.5, 10, -6.5], [-2.5, 12, -3.5], "dark", hide="south up", down=["K$K", "$%$", "K$K"]),
        glow_face("flamme_droite", [-4.5, 9.98, -5.5], [-3.5, 11, -4.5], "down", ["%"]),
    ]
    core = ["SAAS", "S&&K", "A&GK", "S&&K", "SAAK", "SOOK", "KKKK"]
    return both(right) + [
        box("noyau", [-2, 14, -5.5], [2, 21, -3], "dark", hide="south west east", north=core),
        glow_face("rune", [-1, 17, -5.52], [1, 20, -5], "north", ["&&", "&G", "&&"]),
    ] + straps(13)


# ---------------------------------------------------------------------------------------- 19. shoulder fox


def shoulder_fox():
    def fur(w, h):  # short fur: flat, a darker band low on the flanks
        return paint(w, h, lambda x, y: "8" if y == h - 1 and h > 2 else "7")
    setup("shoulder_fox", {"fur": (fur, "7", "8"), "cream": (flat("9"), "9", "8")})
    face = ["77777", "70707", "79997", "99999"]  # eyes, cream cheeks; the snout carries the nose
    return [
        box("corps", [-9.5, 24, -2], [-5.5, 28, 2], "fur", hide="down up", south=["7997", "7997", "7997", "8998"]),
        box("tete", [-10, 27, -1], [-5, 31, 3], "fur", south=face, down="9"),
        box("museau", [-8.5, 27, 3], [-6.5, 29, 4.5], "cream", hide="north", south=["90", "99"], up="9", down="9",
            west=["90", "99"], east=["09", "99"]),
        box("oreille_droite", [-10, 31, 0.5], [-8, 33, 1.5], "fur", hide="down", south=["00", "77"], north=["00", "78"], west=["0", "7"], east=["0", "8"], up="0"),
        box("oreille_gauche", [-7, 31, 0.5], [-5, 33, 1.5], "fur", hide="down", south=["00", "78"], north=["00", "77"], west=["0", "7"], east=["0", "8"], up="0"),
        # Tail curled behind it, cream at its tip.
        box("queue", [-10.5, 24, -5], [-6.5, 27, -2], "fur", hide="south down", north=["9977", "9777", "9788"],
            west=["779", "779", "889"], up=["9777", "9777", "9777"]),
    ]


# ---------------------------------------------------------------------------------------- 20. dragon plush


def back_plush_dragon():
    setup("back_plush_dragon", {"plush": (flat("B"), "Z", "1"), "horn": (flat("O"), "L", "4"),
                                "strap": (leather("N", "C", "U"), "C", "D")})
    # It looks behind the player: round head, big eyes, pale snout with two nostrils and a smile.
    face = ["ZZZZZZZ", "ZBBBBB1", "B0GBG01", "B0BBB01", "BGGGGG1", "BG1G1G1"]
    belly = ["ZBBBBB1", "BEEEEE1", "BEMEME1", "BEEEEE1", "BEMEME1", "111111 "[:6] + "1"]
    wing = cut(4, 5, lambda x, y: x + y >= 2 and not (y == 4 and x % 2), lambda x, y: "1" if x == 0 or y == 4 else "Z")
    horn = box("corne_droite", [-3, 24, -6.5], [-1, 26, -4.5], "horn", hide="down")
    right_wing = plane("aile_droite", -7.5, 15, -3.5, 20, -4.5, "plush", wing, rot=(0, -25, 0), origin=[-3.5, 17, -4.5])
    return [
        box("corps", [-3.5, 12, -6.5], [3.5, 18, BACK], "plush", hide="south up", north=belly),
        box("tete", [-3.5, 18, -7.5], [3.5, 24, BACK], "plush", hide="south", north=face),
        horn, mirror(horn), right_wing, mirror(right_wing),
        # Tail curled to the left, a gold tuft at its tip.
        box("queue", [3.5, 12, -5.5], [6.5, 14, -3.5], "plush", hide="west south", north=["OBB", "411"], up=["ZZO", "BBO"], east="O"),
    ] + straps(14)


# ---------------------------------------------------------------------------------------- 21. courier's bag


def delivery_bag():
    setup("delivery_bag", {"canvas": (cloth("R", "Y", "2"), "Y", "2"), "leather": (leather(), "C", "D"),
                           "strap": (leather("N", "C", "U"), "C", "D")})
    canvas = k.FILL["canvas"]
    body = stamp(canvas(12, 11), 0, 4, "2" * 12)
    flap = stitched(framed(flat("Y"), "2")(13, 4), "P")
    flap = stamp(stamp(flap, 2, 0, ["N", "N", "N", "A"]), 10, 0, ["N", "N", "N", "A"])  # two leather tabs, steel ends
    envelope = ["OOOOOOOO", "O4LLLL4O", "OL4LL4LO", "OLL44LLO", "OOOOOOOO"]  # the messengers' badge
    side = stamp(leather()(4, 5), 0, 1, "DDDD")
    pouch = box("poche_droite", [-8, 12, -7], [-6, 17, -3], "leather", hide="east", west=side)
    return [
        box("corps", [-6, 11, -8.5], [6, 22, BACK], "canvas", hide="south", north=body),
        box("rabat", [-6.5, 19, -9.5], [6.5, 23, BACK], "canvas", hide="south", north=flap, up="Y"),
        box("insigne", [-4, 12, -10], [4, 17, -8.5], "leather", hide="south", north=envelope, up="O", down="4", west="4", east="4"),
        pouch, mirror(pouch),
        box("poignee", [-2, 23, -7], [2, 24, -5], "leather", hide="down"),
    ] + straps(12)


def bow_tie():
    """AETHERIA's bow tie (collection of 2026-11-21, its own palette): ruby wings pinched at a knot, coral light."""
    k.setup("bow_tie", {"R": "#B94450", "2": "#762D36", "C": "#E87569", "K": "#171923", "I": "#F0E8D8", "P": "#C5C5C9"},
                       {"silk": (lambda w, h: paint(w, h, lambda x, y: "2" if y == h - 1 else "C" if y == 0 and x < w - 1 else "R"), "C", "2")})
    wing = Cube("aile_droite", [-4.5, 20, SKIN], [-1, 23.5, SKIN + 1.2], "silk", rot=(0, 0, 10), origin=[-1, 21.75, SKIN],
                north=None, east=None, south=["CCR2", "CRR2", "RR22", "R222"])
    return [Cube("col", [-5, 22, SKIN], [5, 24, SKIN + 0.4], "silk", south=["IIII..IIII", ".IIP..PII."], north=None,
                 west=None, east=None, up=None, down=None),
            wing, mirror(wing),
            box("noeud", [-1, 20.5, SKIN], [1, 23, SKIN + 1.6], "silk", hide="north", south=["CR", "R2"])]


# ---------------------------------------------------------------------------------------- catalogue

# id: (builder, French name, colour of the name, inventory icon seen from behind)
ACCESSORIES = {
    "backpack_classic": (backpack_classic, "Sac de l'Aventurier", "#BD9064", True),
    "backpack_hiking": (backpack_hiking, "Sac d'Expédition", "#588DB0", True),
    "cape_royal": (cape_royal, "Cape du Voyageur Royal", "#B65C68", True),
    "scarf_azure": (scarf_azure, "Écharpe du Mage Errant", "#A5CCDA", False),
    "necktie_ruby": (necktie_ruby, "Cravate Rubis", "#B65C68", False),
    "bowtie_velvet": (bowtie_velvet, "Nœud Papillon de Velours", "#588DB0", False),
    "shoulder_strap": (shoulder_strap, "Bandoulière de l'Explorateur", "#BD9064", False),
    "suspenders_leather": (suspenders_leather, "Bretelles du Voyageur", "#BD9064", False),
    "vest_open": (vest_open, "Gilet de l'Aventurier", "#588DB0", False),
    "armor_ornamental": (armor_ornamental, "Plastron d'Apparat", "#B6C0C9", False),
    "wings_angel": (wings_angel, "Ailes Célestes", "#F5F0E5", True),
    "wings_demon": (wings_demon, "Ailes Démoniaques", "#A83F48", True),
    "wings_butterfly": (wings_butterfly, "Ailes de Papillon Féerique", "#A18AC4", True),
    "quiver_hunter": (quiver_hunter, "Carquois du Chasseur", "#BD9064", True),
    "katana_back": (katana_back, "Katana du Rônin", "#B65C68", True),
    "sword_back": (sword_back, "Épée du Chevalier", "#B6C0C9", True),
    "shield_back": (shield_back, "Bouclier au Lion", "#588DB0", True),
    "jetpack_arcane": (jetpack_arcane, "Jetpack Arcanique", "#62C8D0", True),
    "shoulder_fox": (shoulder_fox, "Renardeau d'Épaule", "#C78C69", False),
    "back_plush_dragon": (back_plush_dragon, "Peluche Dragonnet", "#588DB0", True),
    "delivery_bag": (delivery_bag, "Sac du Coursier", "#B65C68", True),
    "cape_astral": (cape_astral, "Cape Astrale", "#A18AC4", True),
    "bow_tie": (bow_tie, "Nœud papillon", "#B94450", False),
}
LIMBS = ("plastron_torse", "plastron_bras_droit", "jambieres_torse", "jambieres_jambe_droite", "bottes_jambe_droite")


def groups(built):
    """What a builder returns as limb groups: a list of cubes is the torso's."""
    return built if isinstance(built, dict) else {"plastron_torse": built}


def previews(name, model, folder):
    os.makedirs(folder, exist_ok=True)
    full = {g: model.get(g, []) for g in LIMBS}
    still, walk = k.scene(full, []), k.scene(full, [], 30)
    k.strip([(still, v.rot_y(0), False), (still, v.rot_x(12) @ v.rot_y(-35), False), (still, v.rot_y(-90), False),
             (still, v.rot_x(12) @ v.rot_y(145), False), (still, v.rot_y(180), False), (walk, v.rot_x(20) @ v.rot_y(-150), False)],
            os.path.join(folder, name + "_vues.png"), width=380, size=560, scale=15, centre=(0, 19))
    used = max(r[3] for r in k.MAIN.known.values())
    k.MAIN.image.crop((0, 0, k.ATLAS, used)).resize((k.ATLAS * 6, used * 6), 0).save(os.path.join(folder, name + "_atlas_x6.png"))


def icon(name, model, behind):
    """Inventory icon framed on the accessory itself, seen from the side that shows it best."""
    cubes = model["plastron_torse"] + model.get("plastron_bras_droit", []) + [mirror(c) for c in model.get("plastron_bras_droit", [])]
    corners = [c.rotated((x, y, z)) for c in cubes for x in (c.low[0], c.high[0]) for y in (c.low[1], c.high[1])
               for z in (c.low[2], c.high[2])]
    low, high = [min(p[i] for p in corners) for i in range(3)], [max(p[i] for p in corners) for i in range(3)]
    size = round(min(1.6, 15 / max(1.1 * (high[0] - low[0]), high[1] - low[1] + 1, 1.1 * (high[2] - low[2]))), 2)
    m = v.item_model(v.item_elements(cubes, 1.0, (low[1] + high[1]) / 2),
                     {"rotation": [15, -25 if behind else 155, 0], "translation": [0, 0, 0], "scale": [size, size, size]})
    texture = "%s:item/accessoires/%s" % (NS, name)
    m["credit"] = "Influencraft, generated by plugins/fluxplugin/FluxCosmetics/assets/gen_accessoires.py"
    m["textures"] = {"main": texture, "glow": texture + "_neon", "particle": texture}
    return m


def deploy(name, model, title, colour, behind):
    item = icon(name, model, behind)  # before the atlas is saved: mirrored cubes paint new rectangles
    v.write_json(os.path.join(v.FLUXARMOR_MODELS, "acc_" + name + ".bbmodel"), k.bbmodel("acc_" + name, model))
    os.makedirs(os.path.join(ASSETS, "textures/item/accessoires"), exist_ok=True)
    k.MAIN.image.save(os.path.join(ASSETS, "textures/item/accessoires", name + ".png"))
    k.GLOW.image.save(os.path.join(ASSETS, "textures/item/accessoires", name + "_neon.png"))
    v.write_json(os.path.join(ASSETS, "models/item", "acc_" + name + ".json"), item)
    v.write_json(os.path.join(ASSETS, "items", "acc_" + name + ".json"),
                 {"model": {"type": "minecraft:model", "model": "%s:item/acc_%s" % (NS, name)}})
    with open(os.path.join(SETS, "acc_" + name + ".yml"), "w", encoding="utf-8") as fh:
        fh.write("# Accessoire cosmétique « %s » (collection Les Reliques d'Azuréth) : une seule pièce, dessinée en 3D sur le torse.\n"
                 "# Écrit par plugins/fluxplugin/FluxCosmetics/assets/gen_accessoires.py --deploy, ne pas modifier à la main.\n"
                 "# Porté comme cosmétique (FluxCosmetics, clé %s) : aucun bonus, pas de texture 2D de secours.\n"
                 "model: models/acc_%s.bbmodel\n"
                 "pieces:\n"
                 "  chestplate:\n"
                 "    material: iron_chestplate\n"
                 "    model: %s:acc_%s\n"
                 "    name: '&%s%s'\n" % (title, name, name, NS, name, colour, title.replace("'", "''")))


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--deploy"]
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    only = args[1:]
    for name, (build, title, colour, behind) in ACCESSORIES.items():
        if only and name not in only:
            continue
        model = groups(build())
        cubes = [c for part in model.values() for c in part]
        torso = sum(len(c.faces()) for c in model["plastron_torse"])
        print("  %-20s %2d cubes %3d faces, %d layers" % (name, len(cubes), sum(len(c.faces()) for c in cubes), (torso + 5) // 6))
        if args:
            previews(name, model, args[0])
        if "--deploy" in sys.argv:
            deploy(name, model, title, colour, behind)
    if "--deploy" in sys.argv:
        print("Deployed to Labs: /fluxarmor reload, /fluxarmor build, then /iazip.")
