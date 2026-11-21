"""Hats and headwear of the cosmetics collection (second part of the list, 2026-11-21): fifteen hats, each with its
own palette and build. Frame and conventions: gen_coiffes.py. On a west face (the player's right) the front of the
head is the last column, on an east face the first one.

A band going round the head below its top is one cube through the head: only its outside shows.
"""
from coiffes_kit import Cube, flat, k, mirror, paint, rows, soft, stamp, walls


def marine():
    """Classic cap: structured navy crown, tall front, curved visor (its wings droop), a stitched front seam."""
    k.setup("marine", {"N": "#1F2F4D", "L": "#2E4468", "H": "#5A6F8E", "D": "#141E33", "S": "#7C8CA6"},
            {"cloth": (soft("N", "L", "D"), "L", "D"), "visor": (flat("N"), "L", "D")})
    cloth = k.FILL["cloth"]
    wing = Cube("visiere_droite", [-5, 30, 5], [-3, 31, 8], "visor", rot=(0, 0, 16), origin=[-3, 30.5, 6.5], north=None,
                east=None)
    return [
        Cube("calotte", [-5, 30, -5], [5, 33, 5], "cloth", south=stamp(cloth(10, 3), 4, 0, ["LL", "LL", "LS"]),
             north=stamp(cloth(10, 3), 3, 1, ["DDDD", "DHHD"])),
        Cube("calotte_haut", [-4, 33, -4], [4, 35, 4], "cloth", down=None, south=stamp(cloth(8, 2), 3, 0, ["LL", "LL"]),
             up=paint(8, 8, lambda x, y: "N" if x in (3, 4) else "L")),
        Cube("bouton", [-1, 35, -1], [1, 36, 1], "visor", down=None, up="H"),
        Cube("visiere", [-3, 30, 5], [3, 31, 9], "visor", north=None, up=["NNNNNN", "NLLLLN", "NLLLLN", "NNNNNN"]),
        wing, mirror(wing),
    ]


def bordeaux():
    """Cap worn backwards: the visor points back and down, the strap opening sits on the brow as a real hole."""
    k.setup("bordeaux", {"W": "#6E1F2E", "L": "#8A2E3C", "H": "#A54A52", "D": "#47121E", "K": "#2E0C14", "G": "#9A8F86"},
            {"cloth": (soft("W", "L", "D"), "L", "D"), "strap": (flat("K"), "K", "K")})
    corner = Cube("front_droit", [-5, 30, 3], [-2, 33, 5], "cloth", north=None)
    return [
        Cube("calotte", [-5, 30, -5], [5, 33, 3], "cloth", south="D"),
        corner, mirror(corner),
        Cube("front_haut", [-2, 32, 3], [2, 33, 5], "cloth", north=None, west=None, east=None, down="D", south="W"),
        Cube("sangle", [-2, 30, 4], [2, 31, 5], "strap", west=None, east=None, south=["KGKK"]),
        Cube("calotte_haut", [-4, 33, -4], [4, 34, 4], "cloth", down=None,
             up=paint(8, 8, lambda x, y: "W" if x == y or x == 7 - y else "L")),
        Cube("bouton", [-1, 34, -1], [1, 35, 1], "cloth", down=None, up="H"),
        Cube("visiere", [-4, 30, -9], [4, 31, -5], "cloth", rot=(-12, 0, 0), origin=[0, 30.5, -5], south=None,
             up=["WWWWWWWW", "WLLLLLLW", "WLLLLLLW", "WWWWWWWW"], down="K"),
    ]


def noisette():
    """Thick wool beanie: ribbed hazel cuff, cream body tightened at the top, a beige pompom."""
    k.setup("noisette", {"C": "#E8DCC2", "B": "#CDBB98", "N": "#8A6A4A", "D": "#6B4F36", "L": "#F3EBD8"},
            {"knit": (lambda w, h: paint(w, h, lambda x, y: "L" if y == 0 and x < w * 0.5 else
                                         ("B" if (x * 3 + y * 5) % 7 == 0 or x == w - 1 else "C")), "L", "B"),
             "rib": (lambda w, h: paint(w, h, lambda x, y: "D" if x % 3 == 2 else "N"), "N", "D"),
             "pompom": (lambda w, h: paint(w, h, lambda x, y: "L" if (x, y) == (0, 0) else ("N" if y == h - 1 else "B")), "C", "N")})
    knit = k.FILL["knit"]
    band = stamp(knit(10, 4), 0, 2, "NNNCNNNNNC")  # one hazel row, two stitches missing
    return [
        Cube("revers", [-5.5, 29, -5.5], [5.5, 31, 5.5], "rib"),
        Cube("corps", [-5, 31, -5], [5, 35, 5], "knit", sides=band, down=None),
        Cube("corps_haut", [-4, 35, -4], [4, 36, 4], "knit", down=None),
        Cube("pompon", [-1.5, 36, -1.5], [1.5, 39, 1.5], "pompom"),
    ]


def sauge():
    """Classic bucket hat: soft rounded crown, a wide brim going down and out, stitch lines on the brim."""
    k.setup("sauge", {"G": "#8FA383", "O": "#6F7F5E", "K": "#B9AE8A", "D": "#55634A", "L": "#A8B99C"},
            {"cloth": (soft("G", "L", "O"), "L", "D")})
    cloth = k.FILL["cloth"]

    def ring(n):  # top of a brim: a stitched line one pixel inside its edge
        return paint(n, n, lambda x, y: "K" if min(x, y, n - 1 - x, n - 1 - y) == 1 and (x + y) % 2 == 0 else "G")
    return [
        Cube("calotte", [-5, 31, -5], [5, 34, 5], "cloth", sides=stamp(cloth(10, 3), 0, 2, "KKOKKKOKKO"), down=None),
        Cube("calotte_haut", [-4, 34, -4], [4, 35, 4], "cloth", down=None),
        Cube("bord_haut", [-6.5, 30, -6.5], [6.5, 31, 6.5], "cloth", up="G"),
        Cube("bord_bas", [-8, 29, -8], [8, 30, 8], "cloth", up=ring(16), north="O", south="G"),
    ]


def cognac():
    """Western hat: wide brim dipping in front and behind, sides curled up, pinched crown, band with a bronze buckle."""
    k.setup("cognac", {"C": "#9C5A2E", "A": "#C28A4E", "D": "#5E3418", "K": "#3A2212", "Z": "#A87B3D", "L": "#D6A468"},
            {"hide": (soft("C", "A", "D"), "A", "D")})
    hide = k.FILL["hide"]
    banded = stamp(hide(10, 2), 0, 1, "K" * 10)
    side = Cube("bord_droit", [-10, 30, -8], [-6, 31, 8], "hide", rot=(0, 0, -30), origin=[-6, 30.5, 0], east=None,
                up=["ACCC"] * 16, west="A")
    ridge = Cube("crete_droite", [-4, 36, -4], [-1, 37, 4], "hide", down=None, up=["LAA"] * 8)
    return [
        Cube("bord_centre", [-6, 30, -5], [6, 31, 5], "hide", up="C", north=None, south=None),
        Cube("bord_avant", [-6, 30, 5], [6, 31, 10], "hide", rot=(10, 0, 0), origin=[0, 30.5, 5], north=None,
             up=["C" * 12] * 4 + ["A" * 12]),
        Cube("bord_arriere", [-6, 30, -10], [6, 31, -5], "hide", rot=(-10, 0, 0), origin=[0, 30.5, -5], south=None,
             up=["A" * 12] + ["C" * 12] * 4),
        side, mirror(side),
        Cube("calotte_bas", [-5, 31, -5], [5, 33, 5], "hide", sides=banded, down=None,
             south=stamp(banded, 6, 0, ["LZ", "ZZ"])),  # buckle off-centre, on the wearer's left
        Cube("calotte_haut", [-4, 33, -4], [4, 36, 4], "hide", up="D", down=None),
        ridge, mirror(ridge),
    ]


def charbon():
    """Top hat: tall charcoal cylinder, flat brim, deep burgundy ribbon, one cold sheen down the cloth."""
    k.setup("charbon", {"K": "#26262B", "A": "#3A3B42", "G": "#6A6E7A", "R": "#5A1826", "U": "#7A2A38", "D": "#17171A"},
            {"felt": (flat("K"), "A", "D")})

    def side(w, h):
        def pixel(x, y):
            if y >= h - 2:
                return "U" if x == 2 and y == h - 2 else "R"
            if x == 2 and y < 4:
                return "G" if y < 2 else "A"
            return "D" if x == w - 1 else "K"
        return paint(w, h, pixel)
    return [
        Cube("bord", [-7, 31, -7], [7, 32, 7], "felt",
             up=paint(14, 14, lambda x, y: "A" if min(x, y, 13 - x, 13 - y) == 0 else "K")),
        Cube("calotte", [-5, 32, -5], [5, 39, 5], "felt", sides=side(10, 7), down=None,
             up=paint(10, 10, lambda x, y: "K" if min(x, y, 9 - x, 9 - y) == 0 else "A")),
    ]


def prune():
    """Wizard hat: wide brim, a cone in steps whose top leans back and to the side, a gold moon and worn stars."""
    k.setup("prune", {"P": "#4A2A5E", "I": "#33306B", "N": "#1E2247", "L": "#6A4580", "G": "#B8964A", "E": "#8C6E34"},
            {"cloth": (soft("P", "L", "I"), "L", "N")})
    cloth = k.FILL["cloth"]
    front = stamp(stamp(cloth(10, 3), 0, 2, "N" * 10), 3, 0, [" GG", "G  "])
    front = stamp(front, 3, 2, " GG")  # crescent over the band
    base = stamp(cloth(10, 3), 0, 2, "N" * 10)
    brim = paint(18, 18, lambda x, y: "E" if (x, y) in ((3, 13), (14, 5), (12, 15)) else
                 ("I" if min(x, y, 17 - x, 17 - y) == 0 else "P"))
    return [
        Cube("bord", [-9, 30, -9], [9, 31, 9], "cloth", up=brim, north="I", south="P"),
        Cube("cone_1", [-5, 31, -5], [5, 34, 5], "cloth", sides=base, south=front, down=None),
        Cube("cone_2", [-4, 34, -4], [4, 37, 4], "cloth", down=None, south=stamp(cloth(8, 3), 6, 1, "E"),
             west=stamp(cloth(8, 3), 2, 1, "E")),
        Cube("cone_3", [-3, 37, -3], [3, 39, 3], "cloth", down=None),
        Cube("cone_4", [-1.5, 39, -1.5], [1.5, 42, 1.5], "cloth", rot=(-20, 0, 0), origin=[0, 39, 0], down=None),
        Cube("pointe", [-1, 41.3, -2], [1, 43, 0], "cloth", rot=(-55, 0, 0), origin=[0, 41.8, -1], down=None),
    ]


def royale():
    """Royal crown: thick band, points all round (corners and middles, the front one taller), a dark ruby in front."""
    k.setup("royale", {"O": "#C29A3E", "P": "#E6CB7A", "B": "#8A6524", "R": "#7E1626", "U": "#B03A48", "D": "#5E4416"},
            {"gold": (soft("O", "P", "B"), "P", "D")})
    gold = k.FILL["gold"]
    band = stamp(gold(10, 2), 0, 1, "BOOBOOBOOB")
    side = stamp(stamp(gold(8, 2), 0, 1, "OBOOBOOB"), 3, 1, "RR")
    cubes = walls("bandeau", 30, 32, "gold", stamp(band, 4, 0, ["UR", "RR"]), side, band)
    for sx in (-1, 1):
        for sz in (-1, 1):
            x, z = (-5 if sx < 0 else 4), (-5 if sz < 0 else 4)
            cubes.append(Cube("pointe_coin", [x, 32, z], [x + 1, 34, z + 1], "gold", down=None, sides=["P", "O"]))
    cubes += [
        Cube("pointe_avant", [-1, 32, 4], [1, 35, 5], "gold", down=None, south=["PP", "OO", "OB"]),
        Cube("pointe_arriere", [-1, 32, -5], [1, 34, -4], "gold", down=None),
        Cube("pointe_droite", [-5, 32, -1], [-4, 34, 1], "gold", down=None),
        Cube("pointe_gauche", [4, 32, -1], [5, 34, 1], "gold", down=None),
    ]
    return cubes


def chantier():
    """Hard hat: rounded ochre shell with one rib, a rim all round, a short peak, the harness band under the rim."""
    k.setup("chantier", {"Y": "#D9A62E", "G": "#EFC44E", "O": "#B97A22", "D": "#8F5A18", "K": "#3A3A3A", "J": "#55524E"},
            {"shell": (soft("Y", "G", "O"), "G", "D"), "strap": (flat("K"), "K", "K")})
    return [
        Cube("coque", [-5, 30, -5], [5, 33, 5], "shell", down=None),
        Cube("coque_haut", [-4, 33, -4], [4, 35, 4], "shell", down=None),
        Cube("nervure", [-1, 35, -4], [1, 36, 4], "shell", down=None, up=["GY"] * 8),
        Cube("rebord", [-6, 29, -6], [6, 30, 6], "shell", up="Y", down="D"),
        Cube("visiere", [-4, 29, 6], [4, 30, 8], "shell", north=None, up="G", down="D"),
        Cube("harnais", [-5, 28, -5], [5, 29, 5], "strap", up=None, sides=["KKJKKKKJKK"]),
    ]


def chevalier():
    """Knight's great helm: closed steel box with a domed top, eye slit, bronze cross on the face, breathing holes."""
    k.setup("chevalier", {"S": "#8E9BAE", "L": "#B9C3D0", "A": "#3E4450", "M": "#6E7A8C", "Z": "#A8783C", "Y": "#C99A58",
                          "K": "#14161A"},
            {"steel": (soft("S", "L", "M"), "L", "A")})

    def plate(w, h, face=False):
        def pixel(x, y):
            if face and x in (4, 5):
                return "Y" if y == 0 else "Z"  # bronze spine of the cross
            if face and y == 5:
                return "K" if 1 <= x <= 8 else "A"  # eye slit
            if face and y == 4:
                return "Z" if 2 <= x <= 7 else "S"  # bronze brow, the arm of the cross
            if face and y == 7 and x in (2, 7):
                return "A"  # breathing holes
            if y == 0:
                return "L" if x < w * 0.6 else "S"
            if y == h - 1 or x == w - 1:
                return "M"
            return "M" if y == 1 and not face and x % 4 == 1 else "S"  # rivets on the other sides
        return paint(w, h, pixel)
    return [
        Cube("heaume", [-5, 24, -5], [5, 33, 5], "steel", sides=plate(10, 9), south=plate(10, 9, True), down="A"),
        Cube("calotte", [-4, 33, -4], [4, 34, 4], "steel", down=None,
             up=paint(8, 8, lambda x, y: "Z" if x in (3, 4) else "L")),
    ]


def beret():
    """Beret: a headband, a wide flat disc falling towards the wearer's right, a softer top, its little stalk."""
    k.setup("beret", {"R": "#7A1F2B", "W": "#93303A", "P": "#4E1A2E", "L": "#AA4A4E"},
            {"wool": (lambda w, h: paint(w, h, lambda x, y: "L" if y == 0 and x < w * 0.4 else
                                         ("P" if y == h - 1 and h > 1 or x == w - 1 and w > 2 else
                                          ("W" if (x * 5 + y * 3) % 8 == 0 else "R"))), "W", "P")})
    tilt = dict(rot=(0, 0, 7), origin=[5, 32, 0])  # seated on the left, drooping on the right
    top = paint(12, 12, lambda x, y: "L" if x + y < 5 else ("R" if x + y > 15 else ("L" if (x * 5 + y * 3) % 9 == 0 else "W")))
    return [
        Cube("tour", [-5, 31, -5], [5, 33, 5], "wool", up=None),
        Cube("galette", [-7, 32, -6], [5, 34, 6], "wool", up=top, **tilt),
        Cube("dessus", [-6, 34, -5], [4, 35, 5], "wool", down=None, **tilt),
        Cube("queue", [-2, 35, -0.5], [-1, 36, 0.5], "wool", down=None, **tilt),
    ]


def petrole():
    """Modern sport headband: one thick elastic band, higher on the brow than on the nape, a seam on the side."""
    k.setup("petrole", {"T": "#4E9A9A", "P": "#2F6470", "L": "#8CC6C8", "D": "#234A54"},
            {"elastic": (flat("T"), "L", "D")})
    band = ["T" * 6 + "TTTT", "L" * 10, "P" * 10]
    return [
        Cube("bandeau", [-5, 28, -5], [5, 31, 5], "elastic", rot=(-8, 0, 0), origin=[0, 29.5, 0],
             south=["LLLTTTTTTT", "LLLLLLLLLL", "PPPPPPPPPD"], north=band,
             west=stamp(band, 6, 0, ["DL", "DL", "DL"]),  # seam and its lighter lip, towards the front
             east=band),
    ]


def capuche():
    """Ranger's hood: shell round the head, the face left open under an overhang, a peak falling at the back."""
    k.setup("capuche", {"F": "#2F4A36", "D": "#1E3024", "S": "#6E8B6A", "M": "#3C5E45", "K": "#141E18"},
            {"cloth": (soft("F", "M", "D"), "M", "D")})

    def fold(w, h):
        return paint(w, h, lambda x, y: "M" if y == 0 and x < w * 0.5 else
                     ("D" if x in (2, 7) and y > 3 or y == h - 1 else "F"))
    side = Cube("cote_droit", [-5, 24, -5], [-4, 32, 6], "cloth", west=fold(11, 8), east="K", south=rows(1, *"SSSSSSSS"),
                up=None)
    return [
        side, mirror(side),
        Cube("dessus", [-5, 32, -5], [5, 33, 6], "cloth", south=["SSSSSSSSSS"], down="K"),
        Cube("sommet", [-4, 33, -5], [4, 34, 5], "cloth", down=None),
        Cube("dos", [-4, 24, -5], [4, 32, -4], "cloth", south=None, west=None, east=None, up=None, north=fold(8, 8)),
        Cube("pointe", [-3, 30, -7], [3, 34, -5], "cloth", south=None),
        Cube("pointe_bout", [-2, 28, -8], [2, 31, -7], "cloth", south=None),
    ]


def tricorne():
    """Pirate tricorne: triangular brim seen from above, turned up on its three sides, a pale skull on each front flap."""
    k.setup("tricorne", {"K": "#2B211C", "C": "#4E3626", "B": "#CDBB94", "D": "#1C1512", "L": "#6A4A34"},
            {"felt": (soft("K", "C", "D"), "C", "D")})

    def flap(w, h, skull=None):
        g = paint(w, h, lambda x, y: "L" if y == 0 else ("D" if y == h - 1 else ("C" if x % 5 == 1 and y == 1 else "K")))
        return g if skull is None else stamp(g, skull, 1, ["BBBBB", "BDBDB", " BBB "])
    plate = Cube("pan_droit", [-4.5, 32, -8], [-3.5, 36, 10], "felt", rot=(0, 26.6, 0), origin=[-4, 34, 1],
                 west=flap(18, 4, 10), east=flap(18, 4), up="L")
    return [
        Cube("fond_arriere", [-6, 32, -7], [6, 33, -3], "felt", up="C"),
        Cube("fond_milieu", [-4, 32, -3], [4, 33, 1], "felt", up="C"),
        Cube("fond_avant", [-2, 32, 1], [2, 33, 5], "felt", up="C"),
        Cube("calotte", [-3, 33, -6], [3, 38, 1], "felt", down=None),
        plate, mirror(plate),
        Cube("pan_arriere", [-8, 32, -8], [8, 36, -7], "felt", north=flap(16, 4), south=flap(16, 4), up="L"),
    ]


def moto():
    """Full-face motorcycle helmet: pearl shell, tinted visor band, dark lower part and chin bar, one dark red stripe."""
    k.setup("moto", {"W": "#E9E4D8", "P": "#C9C8C4", "R": "#8A1F28", "K": "#2A2F3A", "V": "#4A5466", "D": "#3A3A40",
                     "S": "#A9A8A6"},
            {"shell": (soft("W", "W", "P"), "W", "D"), "dark": (flat("D"), "D", "D")})

    def shell(w, h, visor=None, stripe=False):
        def pixel(x, y):
            if visor and visor[0] <= x <= visor[1] and 3 <= y <= 5:
                return "V" if y == 3 and x - visor[0] in (1, 2) else "K"
            if stripe and x in (4, 5) and y < 3:
                return "R"
            if y >= 7:
                return "D"
            if y == 6:
                return "S"
            return "P" if x == w - 1 else "W"
        return paint(w, h, pixel)
    return [
        Cube("coque", [-5, 24, -5], [5, 33, 5], "shell", south=shell(10, 9, (1, 8), True), north=shell(10, 9, None, True),
             west=shell(10, 9, (7, 9)), east=shell(10, 9, (0, 2))),
        Cube("coque_haut", [-4, 33, -4], [4, 34, 4], "shell", down=None,
             up=paint(8, 8, lambda x, y: "R" if x in (3, 4) else "W"),
             south=["WWWRRWWW"], north=["WWWRRWWW"]),
        Cube("mentonniere", [-4, 24, 5], [4, 27, 6], "dark", north=None, south=["DDDDDDDD", "DSDSSDSD", "DDDDDDDD"]),
    ]


HATS = {"marine": marine, "bordeaux": bordeaux, "noisette": noisette, "sauge": sauge, "cognac": cognac,
        "charbon": charbon, "prune": prune, "royale": royale, "chantier": chantier, "chevalier": chevalier,
        "beret": beret, "petrole": petrole, "capuche": capuche, "tricorne": tricorne, "moto": moto}
