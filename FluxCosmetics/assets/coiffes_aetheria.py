"""AETHERIA, the 48 relics (2026-11-21): 47 head cosmetics (face accessories, ears and horns, fun objects on the head,
premium crowns) and one chest piece (the bow tie, in gen_accessoires.py). Frame and conventions: gen_coiffes.py
(head from y 24 to 32, x and z from -4 to 4, +z forwards, +x towards the player's left; the skin's hat layer ends at
4.5, whatever sits on the face starts past it).

Animations are drawn by the client alone: the texture of an animated hat is a column of frames (.mcmeta), and each
frame shows only the cubes of its pose (Part.phase: the frames a cube is seen in, its pixels are cleared in the others).
So the halo turns, the wings beat and the flames flicker with no packet from the server. ANIM lists them.

Every colour is one of the 47 of the collection (PALETTE). On a west face (the player's right) the front of the head
is the last column, on a south face the left column is the player's right.
"""
import math

from coiffes_kit import k, paint, stamp, v

# The 47 colours of AETHERIA, nothing else is ever painted.
PALETTE = {
    "K": "#171923", "N": "#292D3B", "S": "#484F60", "A": "#8994A6", "W": "#D1D9E5",  # metal
    "E": "#38251F", "U": "#69432F", "F": "#A87950", "H": "#D2B18A",  # leather
    "I": "#F0E8D8", "P": "#C5C5C9",  # cloth, bone
    "1": "#182C49", "B": "#285A88", "Z": "#4D9CCB", "G": "#A6E2F0",  # glass
    "2": "#762D36", "R": "#B94450", "C": "#E87569",  # red
    "4": "#8C642E", "O": "#C49A45", "L": "#E5C978", "Q": "#FFF0AE",  # gold
    "3": "#28513B", "V": "#43865B", "T": "#85B875",  # green
    "5": "#D88EAA", "6": "#F1B9CC",  # pink
    "7": "#543D80", "M": "#8766BC", "X": "#B9A2E1",  # magic
    "8": "#40AFAE", "9": "#83E7E2", "Y": "#F6FAFF",  # crystal
    "$": "#D66C32", "%": "#F3A34E", "&": "#FFE09A",  # fire
    "J": "#E8C84D", "D": "#FFF0A0",  # yellow
    "0": "#256E9B", "@": "#83CBE4",  # water
    "#": "#966039", "!": "#C78A4A", "?": "#E8B96E", "+": "#F5E4B8",  # food
    "=": "#24232B", "<": "#583C82", ">": "#A66BCB",  # wither, ender
}

FRONT = 4.6  # a face accessory starts here, clear of the skin's hat layer


class Part(k.Cube):
    """A cube of setkit whose faces are worked out once. phase: the frames it is seen in (None: all of them); a cube
    of a phase never shares its texture with another one, so it can be cleared from the frames it is not in."""

    def __init__(self, name, low, high, material, glow=False, rot=(0, 0, 0), origin=None, phase=None, **grids):
        super().__init__(name, list(low), list(high), material, glow, rot, origin, **grids)
        self.phase = None if phase is None else frozenset(phase)
        self.density = 1  # texture pixels per pixel of the cube (3 for the letters of the TNT)
        self._faces = None

    def size(self, face):
        return tuple(s * self.density for s in super().size(face))

    def faces(self):
        if self._faces is None:
            out = {}
            for face in v.FACES:
                g = self.grid(face)
                if g is None:
                    continue
                sheet = k.GLOW if self.glow else k.MAIN
                out[face] = (1 if self.glow else 0, sheet.rect(g) if self.phase is None else _alone(sheet, g))
            self._faces = out
        return self._faces


def _alone(sheet, grid):
    """A rectangle of its own for this grid (Sheet.rect shares identical grids)."""
    w, h = len(grid[0]), len(grid)
    if sheet.x + w > k.ATLAS:
        sheet.x, sheet.y, sheet.row = 0, sheet.y + sheet.row, 0
    if sheet.y + h > k.ATLAS:
        raise SystemExit("Atlas is full: raise its size.")
    for y, line in enumerate(grid):
        for x, code in enumerate(line):
            sheet.image.putpixel((sheet.x + x, sheet.y + y), k.COLOURS[code.upper()])
    r = (sheet.x, sheet.y, sheet.x + w, sheet.y + h)
    sheet.known[("alone", len(sheet.known))] = r
    sheet.x += w
    sheet.row = max(sheet.row, h)
    return r


def mirror(c):
    """The same part on the other side of the head, its pictures flipped."""
    grids = {}
    for face in v.FACES:
        g = c.grid({"west": "east", "east": "west"}.get(face, face))
        grids[face] = None if g is None else [line[::-1] for line in g]
    return Part(c.style, [-c.high[0], c.low[1], c.low[2]], [-c.low[0], c.high[1], c.high[2]], c.material, c.glow,
                (c.rotation[0], -c.rotation[1], -c.rotation[2]), [-c.origin[0], c.origin[1], c.origin[2]],
                phase=c.phase, **grids)


def both(*parts):
    out = list(parts)
    return out + [mirror(p) for p in parts]


def seg(name, p0, p1, w, material, z=0.0, d=None, **kw):
    """A bar from p0 to p1 (x, y) in the plane z, w wide and d deep: its top face is at p1 (the tip)."""
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    length = math.hypot(dx, dy)
    cx, cy = (p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2
    d = w if d is None else d
    return Part(name, [cx - w / 2, cy - length / 2, z - d / 2], [cx + w / 2, cy + length / 2, z + d / 2], material,
                kw.pop("glow", False), (0, 0, round(math.degrees(math.atan2(-dx, dy)), 2)), [cx, cy, z], **kw)


def band(name, y0, y1, material, front, side, left=None, back=None, r=5, inner=True, **kw):
    """A band round the head as four walls one pixel thick, r pixels from its middle. front and back are 2r wide,
    side (the player's right, front in its last column) and left (his left, front in its first column) 2r - 2 wide.
    A grid set to None leaves that wall out. inner: the inside of the walls is painted too (seen through cut-outs)."""
    left = left if left is not None else (None if side is None else flip(side))
    back = back if back is not None else (None if front is None else flip(front))
    out = []

    def wall(suffix, low, high, outer, outer_face, inner_face, ends):
        if outer is None:
            return
        grids = {f: None for f in v.FACES}
        grids[outer_face] = outer
        if inner:
            grids[inner_face] = flip(outer)
        for f in ends:
            grids[f] = [line[0 if f in ("west", "north") else -1] for line in outer] if ends[f] else None
        out.append(Part(name + suffix, low, high, material, **{**kw, **grids}))
    wall("_avant", [-r, y0, r - 1], [r, y1, r], front, "south", "north", {"west": True, "east": True})
    wall("_arriere", [-r, y0, -r], [r, y1, 1 - r], back, "north", "south", {"west": True, "east": True})
    wall("_droit", [-r, y0, 1 - r], [1 - r, y1, r - 1], side, "west", "east", {})
    wall("_gauche", [r - 1, y0, 1 - r], [r, y1, r - 1], left, "east", "west", {})
    return out


def flip(grid):
    return None if grid is None else [line[::-1] for line in grid]


# ---------------------------------------------------------------------------------------- painters


def flat(letter):
    return lambda w, h: [letter * w] * h


def soft(base, light, shade):
    """Matt surface lit from the top left: light along the left of the top row, shade on the right column and the
    bottom row."""
    def painter(w, h):
        def pixel(x, y):
            if w >= 3 and x == w - 1 or h >= 3 and y == h - 1:
                return shade
            if y == 0 and x < (w * 6 + 9) // 10:
                return light
            return base
        return paint(w, h, pixel)
    return painter


def metal(base, light, shade, glint):
    """Hard metal: a sharp band of light by the left edge, a glint at its top, dark right column and bottom row."""
    def painter(w, h):
        def pixel(x, y):
            if w >= 3 and x == w - 1 or h >= 3 and y == h - 1:
                return shade
            if w >= 4 and x == 1:
                return glint if y < max(1, h // 3) else light
            if y == 0:
                return light
            return base
        return paint(w, h, pixel)
    return painter


def facets(base, light, shade, glint):
    """Crystal: diagonal facets, the light ones running from the top left."""
    def painter(w, h):
        def pixel(x, y):
            if (x, y) == (0, 0):
                return glint
            if (x + y) % 3 == 0:
                return light
            if (x - y) % 4 == 0 or x == w - 1 and w >= 3:
                return shade
            return base
        return paint(w, h, pixel)
    return painter


def grad(*letters):
    """Bands from the top row down: letters[0] at the top (the tip of a seg)."""
    def painter(w, h):
        return paint(w, h, lambda x, y: letters[min(len(letters) - 1, y * len(letters) // max(1, h))])
    return painter


def sphere(glint, light, base, shade):
    """A round thing lit from the top left: glint in the corner, light on the upper left, shade on the lower right."""
    def painter(w, h):
        def pixel(x, y):
            t = (x + 0.5) / w + (y + 0.5) / h
            if x == 0 and y == 0 and w > 1:
                return glint
            return light if t < 0.75 else shade if t > 1.45 else base
        return paint(w, h, pixel)
    return painter


def setup(name, materials, atlas=64):
    k.ATLAS = atlas
    k.setup(name, PALETTE, materials)


# ======================================================================================== I. face


def spectacles(name, lens, frame, lw=4, y0=26.5, temples=True, lens_glow=False, depth=0.7):
    """Two lenses lw wide in front of the eyes, a thin bridge, temples along the head. lens: grid of the right lens
    seen from the front (outer column first); frame: letter of the frame's edges."""
    setup(name, {"frame": (flat(frame), frame, frame), "lens": (flat(frame), frame, frame)})
    lh = len(lens)
    z0, z1 = FRONT - 0.5, FRONT - 0.5 + depth
    right = Part("verre_droit", [-1 - lw, y0, z0], [-1, y0 + lh, z1], "lens", lens_glow, south=lens,
                 north=[frame * lw] * lh)
    out = both(right) + [Part("pont", [-1, y0 + lh - 1, z0 + 0.1], [1, y0 + lh - 0.4, z1 - 0.2], "frame")]
    if temples:
        out += both(Part("branche_droite", [-5, y0 + lh - 0.8, -2.5], [-4.6, y0 + lh - 0.2, z0], "frame", east=None))
    return out


def glasses_sunglasses():
    return spectacles("glasses_sunglasses", ["KKKK", "KG1B", "KBG1"], "K")


def glasses_round():
    return spectacles("glasses_round", [".AA.", "AGZA", "AZBA", ".AA."], "A", y0=26)


def glasses_regular():
    return spectacles("glasses_regular", ["AWAA", "AYGA", "AAAA"], "A")


def glasses_pixel():
    cubes = spectacles("glasses_pixel", ["KKKK", "KNKS", "NKSK"], "K")
    # the 8-bit bar across the top of both lenses
    cubes.append(Part("barre", [-5, 29.5, FRONT - 0.5], [5, 30, FRONT + 0.2], "frame", south="K"))
    return cubes


def monocle():
    setup("monocle", {"gold": (metal("O", "L", "4", "Q"), "L", "4"), "lens": (flat("O"), "L", "4")})
    ring = [".LO.", "LGZO", "OZB4", ".44."]
    return [
        Part("anneau", [-4.6, 26, FRONT - 0.5], [-0.6, 30, FRONT + 0.1], "lens", south=ring, north=flip(ring)),
        # The chain hangs from the ring and goes back along the cheek to the temple: never in the air.
        Part("chaine_bas", [-4.6, 24.4, FRONT - 0.6], [-4.2, 26, FRONT - 0.2], "gold", south=["O", "4"], north=["4", "O"],
             west=["O", "4"], east=["4", "O"]),
        Part("chaine_cote", [-5, 24.6, 0.5], [-4.6, 25.2, FRONT - 0.2], "gold", west=["O4O4"], east=["4O4O"], up="O",
             down="4"),
    ]


def mask_surgical():
    setup("mask_surgical", {"cloth": (soft("Z", "G", "B"), "G", "B"), "band": (flat("I"), "I", "P")})
    face = ["GGGGGZZZZ", "BBBBBBBBB", "ZZZZZZZZB"]  # three pleats
    return [Part("masque", [-4.5, 24.5, FRONT - 0.4], [4.5, 27.5, FRONT + 0.3], "cloth", south=face, north=flip(face))] + \
        both(Part("elastique_droit", [-5, 26.5, -1.5], [-4.6, 27, FRONT - 0.4], "band", east=None))


def mask_ninja():
    setup("mask_ninja", {"cloth": (soft("K", "N", "K"), "N", "K")})
    front = ["SNNNNNNNNK", "NKKNKKKNKK", "KKKKKKKKKK"]
    side = ["NNNNNNNN", "KKNKKKNK", "KKKKKKKK"]
    left = ["NNN2NNNN", "KK2KKKNK", "K2KKKKKK"]  # a dark red seam running down on his left
    cubes = band("masque", 24, 27, "cloth", front, side, left=left, back=["NNNNNNNNNN", "KNKKKNKKKK", "KKKKKKKKKK"])
    return cubes + [
        Part("noeud", [-1, 24.5, -5.8], [1, 26.5, -5], "cloth", north=["NK", "KK"]),
        Part("pan", [-1.5, 21, -5.6], [0.5, 24.5, -5.1], "cloth", rot=(18, 0, 0), origin=[-0.5, 24.5, -5.3],
             north=["NK", "KK", "NK", "K."], south=["KN", "KK", "KN", ".K"]),
    ]


def mask_ski():
    setup("mask_ski", {"frame": (flat("S"), "A", "N"), "strap": (soft("K", "N", "K"), "N", "K")})
    lens = ["SAAAAAAAS", "SG9ZZBB1S", "S9ZZBB11S", "SSSSSSSSS"]
    strap = ["NNNNNNNN", "KKKKKKKK"]
    return [Part("lentille", [-4.5, 26, FRONT], [4.5, 30, FRONT + 0.9], "frame", south=lens, north=["S" * 9] * 4)] + \
        band("sangle", 27.5, 29.5, "strap", None, strap, back=["NNNNNNNNNN", "KKKKKKKKKK"])


def mask_pirate():
    setup("mask_pirate", {"leather": (soft("K", "N", "K"), "N", "K")})
    # On his left eye, a small ivory skull painted on it; the strap crosses his brow up to his right temple.
    patch = [".NN.", "NIIN", "KNIK", ".KK."]
    strap = ["NNNNNNNN"]
    return [Part("cache", [0.4, 26, FRONT - 0.4], [4.4, 30, FRONT + 0.4], "leather", south=patch, north=flip(patch))] + \
        band("laniere", 28.4, 29, "leather", ["NNNNNNNNNN"], strap, rot=(0, 0, -14), origin=[0, 28.7, 0])


def mask_masquerade():
    setup("mask_masquerade", {"velvet": (soft("7", "M", "K"), "M", "K"), "gold": (metal("O", "L", "4", "Q"), "L", "4")})
    face = ["KO77777OK", "7..7O7..7", "K7O777O7K"]  # eye holes, gold arabesques
    tip = Part("pointe_droite", [-6.6, 29, FRONT - 0.3], [-4.6, 31, FRONT + 0.3], "velvet", rot=(0, 0, 25),
               origin=[-4.6, 29.5, FRONT], south=["M.", "7O"], north=[".M", "O7"])
    return [
        Part("demi_masque", [-4.5, 27, FRONT - 0.3], [4.5, 30, FRONT + 0.4], "velvet", south=face, north=flip(face)),
        Part("ornement", [-1, 30, FRONT - 0.2], [1, 31.2, FRONT + 0.5], "gold", south=["LO"]),
    ] + both(tip)


def clown_nose():
    setup("clown_nose", {"nose": (sphere("6", "C", "R", "2"), "C", "2")})
    return [Part("nez", [-1.5, 25.5, FRONT - 0.5], [1.5, 28.5, FRONT + 2], "nose")]


# ======================================================================================== II. misc


def cat_ears():
    setup("cat_ears", {"fur": (flat("K"), "N", "K")})
    ear = Part("oreille_droite", [-4, 32, -1], [-1, 35.5, 0], "fur", rot=(0, 0, 12), origin=[-2.5, 32, -0.5],
               south=[".K.", "K5K", "K65", "KKK"], north=[".K.", "KNK", "NKK", "KKK"],
               west=[".", "K", "K", "K"], east=[".", "K", "K", "K"], up=[".K."])
    return both(ear)


def bunny_ears():
    setup("bunny_ears", {"fur": (flat("I"), "I", "P")})
    ear = Part("oreille_droite", [-3.5, 32, -0.5], [-1.5, 38, 0.5], "fur", rot=(0, 0, 12), origin=[-2.5, 32, 0],
               south=[".I", "I6", "I6", "I5", "I5", "PP"], north=["I.", "IP", "II", "PI", "II", "PP"],
               west=[".", "I", "I", "I", "P", "P"], east=[".", "I", "I", "I", "P", "P"], up=["I."])
    return both(ear)


def demon_horns():
    setup("demon_horns", {"horn": (grad("K", "2", "R", "2"), "C", "2")})
    return both(
        seg("base_droite", (-3, 31.5), (-4, 34), 2, "horn", d=2),
        seg("corne_droite", (-4, 34), (-3.4, 36.6), 1.4, "horn", d=1.4),
    )


def ring(name, y, r, t, material, top, rot=0.0, phase=None, glow=False):
    """A flat ring of four sides at height y, r out, t thick (an octagon: its corners are left open)."""
    out = []
    for i in range(4):
        a = math.radians(rot + 90 * i)
        cx, cz = math.sin(a) * (r - t / 2), math.cos(a) * (r - t / 2)
        part = Part("%s_%d" % (name, i), [cx - (r - t), y, cz - t / 2], [cx + (r - t), y + t, cz + t / 2], material, glow,
                    (0, round(rot + 90 * i, 3), 0), [cx, y + t / 2, cz], phase=phase, up=[top * round(2 * (r - t))] * max(1, round(t)))
        out.append(part)
    return out


def angel_halo():
    setup("angel_halo", {"gold": (metal("O", "L", "4", "Q"), "Q", "4")})
    return ring("anneau", 34.6, 4.5, 1, "gold", "Q")


def mini_head_wings():
    setup("mini_head_wings", {"feather": (flat("I"), "Y", "P")})
    # seen from his right, the front of the head in the last column: root above the ear, feathers fanning back and up
    wing = ["YI.....", "IIYI...", ".PIIIY.", "..PPIII", "...PPII", ".....PP"]
    right = Part("aile_droite", [-5, 29, -4], [-4.6, 35, 3], "feather", rot=(0, 35, 0), origin=[-4.8, 31, 1.5],
                 west=wing, east=flip(wing), north=None, south=None, up=None, down=None)
    return both(right)


def deer_antlers():
    setup("deer_antlers", {"bone": (grad("I", "H", "F", "E"), "I", "E")})
    return both(
        seg("base_droite", (-2.5, 31.5), (-3.4, 34), 1.6, "bone", d=1.6),
        seg("merrain_droit", (-3.4, 34), (-5.6, 38.6), 1.1, "bone"),
        seg("andouiller_avant", (-4.1, 35.3), (-2.9, 38), 0.9, "bone"),
        seg("andouiller_haut", (-5.0, 37.2), (-7.2, 38.6), 0.9, "bone"),
    )


def tiara():
    setup("tiara", {"gold": (metal("O", "L", "4", "Q"), "L", "4")})
    return [Part("bande", [-5, 30, FRONT], [5, 31, FRONT + 0.4], "gold", north=None, south=["LLOOOOOOO4"])] + \
        both(Part("bande_droite", [-5, 30, 0.5], [-4.6, 31, FRONT], "gold", east=None)) + [
        Part("pointe", [-1.5, 31, FRONT - 0.2], [1.5, 34, FRONT + 0.4], "gold", south=[".L.", "LXO", "OM4"],
             north=[".O.", "OOO", "O44"], west=[".", "L", "O"], east=[".", "O", "4"], up=[".L."]),
    ]


def hair_ribbon():
    setup("hair_ribbon", {"silk": (soft("5", "6", "R"), "6", "R")})
    x0, x1 = -5.5, -4.6
    front_loop = Part("boucle_avant", [x0, 30, 0.6], [x1, 33, 3.6], "silk", rot=(-15, 0, 0), origin=[-5, 31, 0.6],
                      west=["665", "656", ".55"], east=["566", "656", "55."])
    back_loop = Part("boucle_arriere", [x0, 30, -3.6], [x1, 33, -0.6], "silk", rot=(15, 0, 0), origin=[-5, 31, -0.6],
                     west=["566", "656", "55."], east=["665", "656", ".55"])
    return [
        front_loop, back_loop,
        Part("noeud", [-5.8, 30, -1], [-4.5, 32, 1], "silk", west=["6R", "5R"], east=None),
        Part("pan_avant", [x0, 26.4, 0], [x1, 30.4, 2], "silk", rot=(-12, 0, 0), origin=[-5, 30.4, 0.7],
             west=["55", "5R", "55", "5."], east=["55", "R5", "55", ".5"]),
        Part("pan_arriere", [x0, 27.4, -2], [x1, 30.4, 0], "silk", rot=(12, 0, 0), origin=[-5, 30.4, -0.7],
             west=["R5", "55", ".5"], east=["5R", "55", "5."]),
    ]


def hair_flower():
    setup("hair_flower", {"petal": (flat("5"), "6", "5"), "heart": (sphere("D", "J", "J", "4"), "D", "4"),
                          "stem": (flat("V"), "T", "3")})

    def petals(w, h):
        def pixel(x, y):
            dx, dy = x - (w - 1) / 2, y - (h - 1) / 2
            r, a = math.hypot(dx, dy), math.atan2(dy, dx)
            if r > 3.6 or r > 2.2 and math.cos(5 * (a + math.pi / 2)) < -0.2:
                return "."
            return "6" if dx + dy < -1 else "R" if r > 3.1 and dx + dy > 2 else "5"
        return paint(w, h, pixel)
    face = petals(8, 8)
    return [
        Part("fleur", [4.6, 29, -2], [5.1, 37, 6], "petal", rot=(0, -35, 0), origin=[4.8, 33, 2],
             east=[line for line in face], west=flip(face), north=None, south=None, up=None, down=None),
        Part("coeur", [4.7, 32, 1], [6.2, 34, 3], "heart", rot=(0, -35, 0), origin=[4.8, 33, 2]),
        Part("tige", [4.6, 27, 2.5], [5.0, 29.5, 3.5], "stem", rot=(0, -35, 15), origin=[4.8, 29.5, 3]),
    ]


def feather_hat():
    setup("feather_hat", {"vane": (flat("Z"), "Z", "B"), "leather": (soft("U", "F", "E"), "F", "E")})
    vane = ["..Z.", ".ZG.", ".ZAB", "ZZAB", "ZBAB", "BZA1", "BBA1", ".BA.", "..A."]  # silver quill down the middle
    return [
        Part("socle", [-4.2, 31, -3], [-2.4, 33, -1], "leather"),
        Part("plume", [-3.6, 32, -4], [-3.0, 41, 0], "vane", rot=(-30, 0, -8), origin=[-3.3, 32.5, -2],
             west=vane, east=flip(vane), north=["B"] * 9, south=["Z"] * 9, up="Z", down="A"),
    ]


def alien_antennae():
    setup("alien_antennae", {"stalk": (grad("T", "V", "V", "3"), "T", "3"), "orb": (sphere("Y", "9", "8", "0"), "9", "0")})
    return both(
        seg("tige_droite", (-2, 31.6), (-3.4, 35), 0.9, "stalk"),
        seg("tige_droite_haut", (-3.4, 35), (-3.0, 37.2), 0.9, "stalk"),
        Part("boule_droite", [-4.2, 37, -1], [-2.2, 39, 1], "orb", True),
    )


def star_grid():
    return ["...L...", "..LQL..", "LLLQOO4", ".LQOO4.", "..OOO..", ".OO.44.", ".O...4."]


def floating_star():
    setup("floating_star", {"gold": (flat("O"), "Q", "4")})
    star = star_grid()
    # Two crossed planes: the star reads from the front, the side and behind.
    return [
        Part("etoile", [-3.5, 34.5, -0.25], [3.5, 41.5, 0.25], "gold", south=star, north=flip(star), west=None, east=None,
             up=None, down=None),
        Part("etoile_profil", [-0.25, 34.5, -3.5], [0.25, 41.5, 3.5], "gold", west=star, east=flip(star), north=None,
             south=None, up=None, down=None),
    ]


def flower_crown():
    setup("flower_crown", {"leaf": (lambda w, h: paint(w, h, lambda x, y: "T" if (x + y) % 3 == 0 else "3" if (x * 2 + y) % 5 == 0 else "V"),
                                    "T", "3"), "petal": (flat("5"), "6", "5")})
    flower = [".6.", "6J5", ".5."]
    out = [Part("couronne_" + n, lo, hi, "leaf", **g) for n, lo, hi, g in (
        ("avant", [-5, 30.6, 4], [5, 31.8, 5], {"north": None}),
        ("arriere", [-5, 30.6, -5], [5, 31.8, -4], {"south": None}),
        ("droit", [-5, 30.6, -4], [-4, 31.8, 4], {"east": None, "north": None, "south": None}),
        ("gauche", [4, 30.6, -4], [5, 31.8, 4], {"west": None, "north": None, "south": None}))]
    for i, x in enumerate((-4.5, -1.5, 1.5)):
        out.append(Part("fleur_%d" % i, [x, 30.2, 4.8], [x + 3, 33.2, 5.5], "petal",
                        south=flower, north=flip(flower), west=[".", "6", "."], east=[".", "5", "."], up=[".6."], down=[".5."]))
    for z in (-2, 1):
        out += both(Part("fleur_cote_%d" % z, [-5.6, 30.2, z], [-4.9, 33.2, z + 3], "petal", west=flower, east=flip(flower),
                         north=[".", "5", "."], south=[".", "6", "."], up=[".", "6", "."], down=[".", "5", "."]))
    return out


# ======================================================================================== III. fun


def duck_hat():
    setup("duck_hat", {"down": (soft("J", "D", "4"), "D", "4"), "beak": (soft("%", "&", "$"), "&", "$")})
    return [
        Part("corps", [-2, 32, -2.5], [2, 35, 2], "down", down=None),
        Part("queue", [-1, 34, -3.5], [1, 35.5, -2.5], "down", rot=(25, 0, 0), origin=[0, 34.5, -2.5]),
        Part("tete", [-1.5, 35, 0], [1.5, 38, 3], "down", south=["JDJ", "KJK", "JJJ"], west=["JJJ", "JKJ", "JJJ"],
             east=["JJJ", "JKJ", "JJJ"]),
        Part("bec", [-1, 35.6, 3], [1, 36.6, 4.6], "beak"),
    ] + both(Part("aile_droite", [-2.5, 32.6, -1.6], [-2, 34.6, 1.4], "down", rot=(0, 0, -10), origin=[-2, 34.6, 0],
                  west=["JJ4", "J44"], east=None))


def bread_hat():
    setup("bread_hat", {"crust": (lambda w, h: paint(w, h, lambda x, y: "#" if y == h - 1 else "?" if y == 0 else
                                                     ("#" if (x * 7 + y * 3) % 11 == 0 else "!")), "?", "#")})
    top = ["?+??+??+", "??+??+?#", "?#?+??+?"]  # three diagonal scores
    return [
        Part("baguette", [-4, 32, -1.5], [4, 34.5, 1.5], "crust", up=top),
    ] + both(Part("bout_droit", [-5.5, 32.2, -1.2], [-4, 34, 1.2], "crust", rot=(0, 0, -10), origin=[-4, 33, 0],
                  east=None, west=["?!", "!#"], up=["??", "?!"]))


def watermelon_hat():
    setup("watermelon_hat", {"rind": (flat("V"), "T", "3"), "white": (flat("I"), "I", "P"), "flesh": (flat("C"), "C", "2")})
    out = [
        Part("croute", [-3.5, 32, -1], [3.5, 33, 1], "rind", south=["VTVVTVV"], north=["3V3V33V"]),
        Part("blanc", [-3.5, 33, -1], [3.5, 33.6, 1], "white"),
        Part("chair_bas", [-3, 33.6, -1], [3, 35, 1], "flesh", south=["CKRRKC"], north=["RKCCKR"]),
        Part("chair_milieu", [-2, 35, -1], [2, 36.4, 1], "flesh", south=["CRKC"], north=["CKRC"]),
        Part("chair_haut", [-1, 36.4, -1], [1, 37.6, 1], "flesh", south=["CR"], north=["RC"]),
    ]
    for c in out:
        c.rotation, c.origin = [0, 0, -10], [0, 32, 0]
    return out


def mini_water_bucket():
    setup("mini_water_bucket", {"iron": (metal("A", "W", "S", "W"), "W", "S"), "water": (flat("0"), "@", "0")})
    water = ["AAAAA", "A@0@A", "A0@0A", "A0B0A", "AAAAA"]
    out = [
        Part("seau", [-2, 32, -2], [2, 35, 2], "iron"),
        Part("bord", [-2.5, 35, -2.5], [2.5, 35.8, 2.5], "iron", up=water),
        Part("anse", [-2.5, 37, -0.25], [2.5, 37.5, 0.25], "iron", down=None),
    ] + both(Part("anse_droite", [-2.75, 35.8, -0.25], [-2.25, 37.5, 0.25], "iron"))
    for c in out:  # tipped forwards: the water shows from the front
        c.rotation, c.origin = [18, 0, 0], [0, 32, 0]
    return out


def mini_creeper():
    setup("mini_creeper", {"skin": (lambda w, h: paint(w, h, lambda x, y: "T" if (x * 3 + y * 5) % 7 == 0 else
                                                       "3" if (x + y * 2) % 5 == 0 else "V"), "T", "3")})
    face = ["VTVV", "KVVK", "VKKV", "KVVK"]
    return [
        Part("tete", [-2, 36, -2], [2, 40, 2], "skin", south=face),
        Part("corps", [-1.5, 33.5, -1], [1.5, 36, 1], "skin"),
    ] + both(Part("patte_av_droite", [-1.6, 32, 0.5], [-0.4, 33.5, 1.6], "skin"),
             Part("patte_ar_droite", [-1.6, 32, -1.6], [-0.4, 33.5, -0.5], "skin"))


def mini_tnt():
    setup("mini_tnt", {"block": (flat("R"), "R", "2")}, atlas=128)
    # 3 pixels of texture per pixel of the block: "TNT" stays readable.
    side = ["R2RRR2RRR2RR"] * 3 + ["IIIIIIIIIIII", "KKK.K..K.KKK", ".K..KK.K..K.", ".K..K.KK..K.", ".K..K..K..K.",
                                   "IIIIIIIIIIII"] + ["R2RRR2RRR2RR"] * 3
    side = [line.replace(".", "I") for line in side]
    top = ["RRRRRRRRRRRR", "R2RRRRRRRR2R", "RR2RRRRRR2RR", "RRRIIIIIIRRR", "RRRIKKKKIRRR", "RRRIK%%KIRRR",
           "RRRIK%%KIRRR", "RRRIKKKKIRRR", "RRRIIIIIIRRR", "RR2RRRRRR2RR", "R2RRRRRRRR2R", "RRRRRRRRRRRR"]
    c = Part("tnt", [-2, 32, -2], [2, 36, 2], "block", south=side, north=side, west=side, east=side, up=top,
             down=["2" * 12] * 12)
    c.density = 3
    return [c]


def piston_hat():
    setup("piston_hat", {"stone": (lambda w, h: paint(w, h, lambda x, y: "A" if y == 1 else "N" if (x + y) % 3 == 0 else "S"),
                                   "A", "N"),
                         "wood": (lambda w, h: paint(w, h, lambda x, y: "#" if x % 2 == 1 else "!"), "?", "#"),
                         "rod": (flat("!"), "?", "#")})
    return [
        Part("socle", [-2.5, 32, -2.5], [2.5, 34.5, 2.5], "stone", up=["SSSSS", "SN#NS", "S#!#S", "SN#NS", "SSSSS"]),
        Part("tige", [-0.75, 34.5, -0.75], [0.75, 35.5, 0.75], "rod"),
        Part("tete", [-2.5, 35.5, -2.5], [2.5, 36.8, 2.5], "wood", up=["?!?!?", "!?!?!", "?!?!?", "!?!?!", "?!?!?"],
             down=["#####", "#SSS#", "#S!S#", "#SSS#", "#####"]),
    ]


def torch_hat():
    setup("torch_hat", {"stick": (grad("!", "!", "#", "E"), "?", "E"), "flame": (flat("%"), "&", "$")})
    flame = ["&%", "%$"]
    out = [
        Part("manche", [-0.75, 32, -0.75], [0.75, 36.5, 0.75], "stick"),
        Part("flamme", [-1.25, 36.5, -1.25], [1.25, 38.5, 1.25], "flame", True, sides=["&%", "%$"],
             south=flame, north=flame, west=flame, east=flame, up=["&&", "&%"], down=["$$", "$2"]),
        Part("pointe", [-0.5, 38.5, -0.5], [0.5, 39.6, 0.5], "flame", True, sides=["&"], up=["&"], down=["%"]),
    ]
    for c in out:
        c.rotation, c.origin = [0, 0, -5], [0, 32, 0]
    return out


def mushroom_hat():
    setup("mushroom_hat", {"stem": (soft("+", "I", "H"), "I", "H"), "cap": (lambda w, h: paint(
        w, h, lambda x, y: "I" if (x * 2 + y * 3) % 5 == 0 and y < h - 1 else "2" if y == h - 1 else "C" if y == 0 and x < w // 2 else "R"),
        "C", "2")})
    return [
        Part("pied", [-1, 32, -1], [1, 34.5, 1], "stem"),
        Part("chapeau", [-3, 34.5, -3], [3, 36.5, 3], "cap", up=["CCIRRR", "CRRRIR", "IRRRRR", "RRIRR2", "RRRRIR", "RR2RRR"],
             down=["HHHHHH", "HFHFHH", "HHEEHF", "HFEEHH", "HHFHFH", "HHHHHH"]),
        Part("sommet", [-2, 36.5, -2], [2, 37.5, 2], "cap", up=["CIRR", "CRRI", "RRRR", "IRR2"], down=None),
    ]


def fish_hat():
    setup("fish_hat", {"scale": (lambda w, h: paint(w, h, lambda x, y: "@" if y == 0 else "I" if y == h - 1 else
                                                    "B" if (x + y) % 2 == 0 else "0"), "@", "I"), "fin": (flat("B"), "Z", "1")})
    side = ["@@@@@", "K0B0B", "I0B0I"]  # eye at the head end (on the player's right)
    return [
        Part("corps", [-2.5, 33.5, -1], [2.5, 36.5, 1], "scale", south=side, north=flip(side), west=["@@", "BB", "II"]),
        Part("queue", [2.5, 33.5, -0.25], [4.5, 36.5, 0.25], "fin", rot=(0, 0, 12), origin=[2.5, 35, 0],
             south=["00", "0.", "00"], north=["00", ".0", "00"], west=None, east=None, up=None, down=None),
        Part("dorsale", [-1, 36.5, -0.25], [1, 37.5, 0.25], "fin", south=["B."], north=[".B"], west=None, east=None, down=None),
        Part("ventrale", [-1, 33, -0.25], [1, 33.5, 0.25], "fin", south=["1B"], north=["B1"], west=None, east=None, up=None),
    ]


def fried_egg_hat():
    setup("fried_egg_hat", {"white": (flat("I"), "Y", "P"), "yolk": (sphere("D", "D", "J", "4"), "D", "4")})
    white = ["..IIII", ".IIIIP", "IIYIIP", "IIIIIP", ".IPIP.", "..PP.."]
    return [
        Part("blanc", [-3, 32.55, -3], [3, 33.15, 3], "white", up=white, down=flip(white),
             south=["PIIPP."], north=[".PPIIP"], west=[".IIPI."], east=[".IPII."]),
        Part("jaune", [-1, 33.15, -1], [1, 34.15, 1], "yolk", up=["DJ", "J4"]),
    ]


def toilet_paper_hat():
    setup("toilet_paper_hat", {"paper": (lambda w, h: paint(w, h, lambda x, y: "P" if y % 2 == 1 and x % 2 == 0 else "I"),
                                         "I", "P")})
    return [
        Part("rouleau", [-2, 32, -2], [2, 36, 2], "paper", up=["IPPI", "PUEP", "PEUP", "IPPI"]),
        Part("bande_dessus", [0, 32.55, 2], [2, 32.95, 4.7], "paper", down=None, up=["II", "PP", "II"]),
        Part("bande_front", [0, 30.95, FRONT + 0.1], [2, 32.95, FRONT + 0.5], "paper", north=None,
             south=["II", "PP"]),
    ]


def mini_tv():
    setup("mini_tv", {"case": (soft("S", "A", "N"), "A", "N"), "rod": (flat("A"), "W", "S")}, atlas=128)
    screen = ["NNNNNNNNNN", "N7777777NN", "N77M7777NO", "N7MYM777N4", "N77M777MNN", "N777777MNO",
              "N7777777N4", "NNNNNNNNNN"]  # violet screen, a pixel star, two gold knobs
    tv = Part("poste", [-2.5, 32.5, -1.5], [2.5, 36.5, 1.5], "case", south=screen)
    tv.density = 2
    return [tv] + both(
        seg("antenne_droite", (-0.5, 36.4), (-2.4, 39.4), 0.5, "rod"),
        Part("pied_droit", [-2, 32, -1], [-1, 32.5, 1], "case"),
    )


# ======================================================================================== IV. premium


def crown_grid(w, h, peaks, colour):
    """A crown band w wide and h tall: the points are cut out of its top (peaks: height of each column's point above
    the band, the band being h - max(peaks) rows); colour(x, y, in_band) paints the rest."""
    top = max(peaks)
    return paint(w, h, lambda x, y: "." if y < top - peaks[x] else colour(x, y, y >= top))


def crown(name, h, front_peaks, side_peaks, colour, gem, gem_material="gem", glow_gem=False, gem_phase=None, extra=()):
    front = crown_grid(10, h, front_peaks, colour)
    side = crown_grid(8, h, side_peaks, colour)
    cubes = band(name, 30.6, 30.6 + h, "band", front, side, up=None, down=None)
    if gem:
        cubes += [Part("gemme", [-1, 31, 4.9], [1, 33, 5.6], gem_material, glow_gem, south=gem, phase=gem_phase)]
    return cubes + list(extra)


def diamond_crown():
    setup("diamond_crown", {"band": (flat("8"), "Y", "B"), "gem": (facets("9", "Y", "8", "Y"), "Y", "B")})

    def colour(x, y, inband):
        if inband:
            return "B" if y == 4 else "Y" if (x + y) % 4 == 0 else "9" if x % 2 else "8"
        return "Y" if (x - y) % 3 == 0 else "9" if x % 2 == 0 else "8"
    return crown("couronne", 5, [1, 2, 1, 0, 3, 3, 0, 1, 2, 1], [1, 2, 1, 0, 0, 1, 2, 1], colour, ["YG", "98"])


def netherite_crown():
    setup("netherite_crown", {"band": (flat("N"), "S", "K"), "gem": (sphere("C", "R", "R", "2"), "C", "2")})

    def colour(x, y, inband):
        if inband:
            return "K" if y == 4 else "A" if (x, y) in ((0, 3), (1, 3)) else "S" if (x + y) % 5 == 0 else "N"
        return "A" if x % 3 == 0 and y < 2 else "S" if x % 2 == 0 else "N"
    return crown("couronne", 5, [2, 2, 0, 2, 3, 3, 2, 0, 2, 2], [2, 2, 0, 1, 1, 0, 2, 2], colour, ["CR", "R2"])


def ice_crown():
    setup("ice_crown", {"band": (flat("Z"), "Y", "B"), "gem": (facets("G", "Y", "Z", "Y"), "Y", "B")})

    def colour(x, y, inband):
        if inband:
            return "B" if y == 5 else "Y" if (x * 3 + y) % 7 == 0 else "G" if x % 2 else "Z"
        return "Y" if (x + y) % 4 == 0 else "G" if (x + 2 * y) % 3 else "Z"
    gem = ["YG", "GZ", "ZB"]
    return crown("couronne", 6, [1, 3, 0, 2, 4, 3, 1, 3, 0, 2], [2, 0, 3, 1, 0, 2, 3, 1], colour, None,
                 extra=[Part("cristal", [-1, 31, 4.9], [1, 34, 5.6], "gem", south=gem)])


def ender_dragon_crown():
    setup("ender_dragon_crown", {"band": (flat("K"), "<", "K"), "gem": (flat(">"), "Y", "<")})

    def colour(x, y, inband):
        if inband:
            return "7" if y == 6 else "<" if (x + y) % 4 == 0 else "K"
        return ">" if x % 3 == 1 and y == 0 else "<" if x % 2 == 0 else "K"
    bright, dim = ["Y>", "><"], ["><", "<7"]
    return crown("couronne", 7, [3, 4, 1, 0, 5, 5, 0, 1, 4, 3], [3, 1, 0, 4, 4, 0, 1, 3], colour, bright,
                 glow_gem=True, gem_phase={0}, extra=[Part("gemme_sombre", [-1, 31, 4.9], [1, 33, 5.6], "gem", True,
                                                           south=dim, phase={1})])


def animated_halo():
    """Violet ring turning slowly, three crystal shards orbiting it, a soft glow every 1.8 seconds. 24 frames of 15
    degrees (3 ticks each): the ring repeats every quarter turn, the shards every third, so the loop is seamless."""
    setup("animated_halo", {"ring": (flat("M"), "X", "7"), "bright": (flat("X"), "Y", "M"), "shard": (facets("M", "X", "7", "Y"), "Y", "7")},
          atlas=128)
    out = []
    for f in range(24):
        a = 15 * f
        out += ring("anneau_%d" % f, 34.8, 4.5, 1, "bright" if f % 12 in (0, 1) else "ring", "Y" if f % 12 in (0, 1) else "X",
                    rot=a, phase={f}, glow=True)
        for i in range(3):
            b = math.radians(a + 120 * i)
            x, z = math.sin(b) * 5.6, math.cos(b) * 5.6
            out.append(Part("eclat_%d_%d" % (f, i), [x - 0.8, 35.8, z - 0.8], [x + 0.8, 37.4, z + 0.8], "shard", True,
                            (0, (2 * a) % 360, 0), [x, 36.6, z], phase={f}))
    return out


def laser_glasses():
    cubes = spectacles("laser_glasses", ["K%RK", "KR2K"], "K", y0=27, lens_glow=True)
    setup_keep = k.FILL
    setup_keep["beam"], k.TOP["beam"], k.BOTTOM["beam"] = flat("C"), "D", "C"
    setup_keep["beam_dim"], k.TOP["beam_dim"], k.BOTTOM["beam_dim"] = flat("R"), "C", "R"
    on = Part("faisceau_droit", [-3.5, 27.5, FRONT + 0.2], [-2.5, 28.5, FRONT + 4.2], "beam", True,
              west=["DCDC"], east=["CDCD"], up=["D", "C", "D", "C"], down=["C", "C", "C", "C"],
              south=["D"], north=None, phase={0})
    off = Part("faisceau_droit_faible", [-3.25, 27.75, FRONT + 0.2], [-2.75, 28.25, FRONT + 3.2], "beam_dim", True,
               west=["CRR"], east=["RRC"], up=["C", "R", "R"], down=["R", "R", "R"], south=["C"], north=None, phase={1})
    return cubes + both(on, off)


def flaming_horns():
    setup("flaming_horns", {"horn": (grad("K", "K", "2", "2"), "N", "K"), "fire": (flat("%"), "&", "$")})
    out = both(
        seg("base_droite", (-3, 31.4), (-4.2, 34), 2, "horn", d=2),
        seg("corne_droite", (-4.2, 34), (-4.8, 36.8), 1.6, "horn", d=1.6),
        seg("pointe_droite", (-4.8, 36.8), (-3.8, 39), 1.1, "horn", d=1.1),
    )
    shapes = [["&", "%"], ["&", "%", "$"], ["%", "&"]]
    for f, rows in enumerate(shapes):
        h = len(rows)
        flame = paint(2, len(rows), lambda x, y, rows=rows: rows[y] if (x + y + f) % 3 else ("$" if rows[y] != "&" else "%"))
        out += both(Part("flamme_droite_%d" % f, [-4.6, 37.6, -0.9], [-3.0, 37.6 + h, 0.9], "fire", True,
                         rot=(0, 30 * f, -12), origin=[-3.8, 37.6, 0], south=flame, north=flame, west=flame, east=flame,
                         up=["&&", "&%"], down=None, phase={f}))
    return out


def dragon_wings():
    """Small dragon wings at the back of the head beating slowly: four poses, played 0 1 2 3 2 1 (8 ticks each)."""
    setup("dragon_wings", {"bone": (flat("S"), "A", "N"), "skin": (flat("M"), "X", "7")}, atlas=128)
    wing = ["WAASS.", ".MS7M.", ".MS7MM", "..7MM7", "...7.7", "......"]
    wing = [line[::-1] for line in wing]  # root on the right of the picture: seen from behind, the player's right wing
    out = both(Part("racine_droite", [-4.6, 30, -4.9], [-3, 32, -4.1], "bone"))
    for f, angle in enumerate((-4, 8, 20, 32)):
        right = Part("aile_droite_%d" % f, [-10, 29, -4.8], [-4, 35, -4.4], "skin", rot=(0, 10, angle),
                     origin=[-4, 31, -4.6], north=flip(wing), south=wing, west=None, east=None, up=None, down=None, phase={f})
        out += both(right)
    return out


def wither_mask():
    setup("wither_mask", {"bone": (flat("="), "S", "K"), "brow": (soft("N", "S", "="), "A", "=")})
    face = ["=NNNNNNN=", "NSSNSNSSN", "N7K=N=K7N", "=KK=S=KK=", "NN=NSN=NN", "=NNKKKNN=", "N=AKAKA=N", "=N=====N="]
    return [
        Part("visage", [-4.5, 24.2, FRONT - 0.3], [4.5, 32.2, FRONT + 0.3], "bone", south=face, north=flip(face),
             west=["N"] * 8, east=["N"] * 8),
        Part("arcade", [-4.5, 30, FRONT + 0.3], [4.5, 31.4, FRONT + 1], "brow", south=["ASSSNSSSN"]),
        Part("machoire", [-3.5, 23.6, FRONT + 0.2], [3.5, 25.4, FRONT + 1], "brow", south=["AKAKAKA", "N=N=N=N"]),
        Part("crete", [-2, 32, FRONT - 0.6], [2, 33.6, FRONT + 0.3], "brow", south=["SSSN", "N==N"]),
    ] + both(Part("pointe_droite", [-3.6, 22, FRONT + 0.1], [-2.6, 24, FRONT + 0.8], "brow", south=["N", "="]))


def cosmic_hat():
    setup("cosmic_hat", {"felt": (lambda w, h: paint(w, h, lambda x, y: "X" if y == 0 and x < w // 2 else
                                                     "K" if x == w - 1 and w > 2 else "7"), "X", "K"),
                         "band": (lambda w, h: paint(w, h, lambda x, y: "O" if x % 4 == 1 else "B"), "Z", "1"),
                         "star": (flat("Y"), "Y", "X")}, atlas=128)
    brim_top = paint(12, 12, lambda x, y: "Y" if (x * 5 + y * 3) % 23 == 0 else "X" if x + y < 6 else "7")
    out = [
        Part("bord", [-6, 31, -6], [6, 32, 6], "felt", up=brim_top, down=["K" * 12] * 12),
        Part("base", [-4.6, 32, -4.6], [4.6, 34, 4.6], "band", down=None),
        Part("milieu", [-3.5, 34, -3.5], [3.5, 37, 3.5], "felt", down=None, rot=(0, 0, -8), origin=[0, 34, 0]),
        Part("pointe", [-2.2, 36.6, -2.2], [1.8, 39.6, 1.8], "felt", down=None, rot=(0, 0, -16), origin=[0, 36.6, 0]),
        Part("bout", [-1.4, 39.2, -0.9], [0.4, 41.2, 0.9], "felt", down=None, rot=(0, 0, -28), origin=[0, 39.2, 0]),
    ]
    star_on = [".Y.", "YYY", ".Y."]
    star_off = [".X.", "XYX", ".X."]
    out.append(Part("etoile", [-1.5, 34.4, 3.55], [1.5, 37.4, 3.85], "star", True, rot=(0, 0, -8), origin=[0, 34, 0],
                    south=star_on, north=None, west=None, east=None, up=None, down=None, phase={0}))
    out.append(Part("etoile_faible", [-1.5, 34.4, 3.55], [1.5, 37.4, 3.85], "star", True, rot=(0, 0, -8), origin=[0, 34, 0],
                    south=star_off, north=None, west=None, east=None, up=None, down=None, phase={1}))
    return out


# name: builder, in the order of the collection (the bow tie, 20, is a chest piece: gen_accessoires.py)
HATS = {
    "glasses_sunglasses": glasses_sunglasses, "glasses_round": glasses_round, "glasses_regular": glasses_regular,
    "glasses_pixel": glasses_pixel, "monocle": monocle, "mask_surgical": mask_surgical, "mask_ninja": mask_ninja,
    "mask_ski": mask_ski, "mask_pirate": mask_pirate, "mask_masquerade": mask_masquerade, "clown_nose": clown_nose,
    "cat_ears": cat_ears, "bunny_ears": bunny_ears, "demon_horns": demon_horns, "angel_halo": angel_halo,
    "mini_head_wings": mini_head_wings, "deer_antlers": deer_antlers, "tiara": tiara, "hair_ribbon": hair_ribbon,
    "hair_flower": hair_flower, "feather_hat": feather_hat, "alien_antennae": alien_antennae,
    "floating_star": floating_star, "flower_crown": flower_crown,
    "duck_hat": duck_hat, "bread_hat": bread_hat, "watermelon_hat": watermelon_hat, "mini_water_bucket": mini_water_bucket,
    "mini_creeper": mini_creeper, "mini_tnt": mini_tnt, "piston_hat": piston_hat, "torch_hat": torch_hat,
    "mushroom_hat": mushroom_hat, "fish_hat": fish_hat, "fried_egg_hat": fried_egg_hat,
    "toilet_paper_hat": toilet_paper_hat, "mini_tv": mini_tv,
    "diamond_crown": diamond_crown, "netherite_crown": netherite_crown, "animated_halo": animated_halo,
    "laser_glasses": laser_glasses, "flaming_horns": flaming_horns, "ice_crown": ice_crown, "dragon_wings": dragon_wings,
    "wither_mask": wither_mask, "ender_dragon_crown": ender_dragon_crown, "cosmic_hat": cosmic_hat,
}

# Animated hats: frames, ticks per frame, order of the frames (None: 0, 1, 2...).
ANIM = {
    "animated_halo": (24, 3, None),
    "laser_glasses": (2, 8, None),
    "flaming_horns": (3, 3, [0, 1, 2, 1]),
    "dragon_wings": (4, 8, [0, 1, 2, 3, 2, 1]),
    "ender_dragon_crown": (2, 10, None),
    "cosmic_hat": (2, 12, None),
}
