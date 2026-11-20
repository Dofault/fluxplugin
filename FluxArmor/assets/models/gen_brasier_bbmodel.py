#!/usr/bin/env python3
"""Brazier set ("Brasier"), the cosmetic outfit of the fire class: volcanic crust over a glowing core.

    <venv with Pillow and numpy>/python3 gen_brasier_bbmodel.py [--deploy] [preview folder]

Without --deploy nothing leaves the preview folder. With --deploy it writes to Labs:
- plugins/FluxArmor/models/brasier.bbmodel: robe, skirt, boots (then /fluxarmor build, /iazip);
- contents/influoutils: the worn hat casque_brasier and the 3D inventory models of the other pieces, from the same cubes.
Frame and groups are the ones of gen_virale_bbmodel.py (its rasteriser is reused): one skin pixel per unit, feet at
y = 0, +x towards the player's left, +z forwards. No rotated cube.

The four pieces: a volcano hat (stepped stone cone, lava running down from its crater, red cloth on the nape), an
ember red robe under a smith's apron with a live coal set in it and basalt pauldrons, a flared skirt with a gilded
and charred hem, heavy forge boots with a cracked stone toe.

A grid is a list of rows, top row first, left column first as seen when facing the face from outside.
Faces: south = front (+z), north = back, west = the player's right (-x), east = left, up (row 1 at the back), down.
A lower-case letter is the same colour, emissive: those pixels are repeated on a shell a hair above the cube, in the
texture FluxArmor draws at full light.
"""
import os
import sys
import uuid

import numpy as np
from PIL import Image

import gen_virale_bbmodel as v

ATLAS = 128
PALETTE = {"D": "#292321", "V": "#39302B", "S": "#4D423B",  # charcoal, volcanic black, lit stone
           "X": "#56292A", "E": "#71352D", "R": "#A64C36",  # robe: fold, ember red, terracotta
           "U": "#C96B36", "M": "#D99A4D", "C": "#D4BDA0",  # burnt orange, amber, ash cream
           "Y": "#7C6038", "G": "#A9854F"}  # aged gold and its shade
COLOURS = {k: v.rgb(h) for k, h in PALETTE.items()}
COLOURS["."] = (0, 0, 0, 0)
SHELL = 0.02


class Sheet:
    """Atlas of painted grids: identical grids share their rectangle."""

    def __init__(self):
        self.image = Image.new("RGBA", (ATLAS, ATLAS), (0, 0, 0, 0))
        self.x = self.y = self.row = 0
        self.known = {}

    def rect(self, grid):
        key = tuple(grid)
        if key not in self.known:
            w, h = len(grid[0]), len(grid)
            if self.x + w > ATLAS:
                self.x, self.y, self.row = 0, self.y + self.row, 0
            if self.y + h > ATLAS:
                raise SystemExit("Atlas is full: raise ATLAS.")
            for y, line in enumerate(grid):
                for x, code in enumerate(line):
                    self.image.putpixel((self.x + x, self.y + y), COLOURS[code.upper()])
            self.known[key] = (self.x, self.y, self.x + w, self.y + h)
            self.x += w
            self.row = max(self.row, h)
        return self.known[key]


MAIN, GLOW = Sheet(), Sheet()

# ---------------------------------------------------------------------------------------- painters


def stone(w, h):
    """Basalt block: lit top and left edges, shaded bottom and right edges, a calm field."""
    if h == 1:
        return ["S" * (w - 1) + "V"] if w > 1 else ["S"]
    rows = ["S" * (w - 1) + "V"]
    for y in range(1, h - 1):
        rows.append(("S" if y < h * 0.6 else "V") + "V" * (w - 2) + "D" if w > 2 else "V" * w)
    rows.append("V" + "D" * (w - 1))
    return rows


def robe(w, h):
    """Ember red cloth: terracotta where the light falls, straight folds starting at different heights."""
    rows = []
    for y in range(h):
        line = ["E"] * w
        if w >= 3:
            line[-1] = "X"
            if y < h * 0.45:
                line[0] = "R"
            for k, x in enumerate(range(2, w - 2, 3)):
                if y >= (h * 0.3 if k % 2 else h * 0.55):
                    line[x] = "X"
        rows.append("".join(line))
    return rows


def leather(w, h):
    rows = []
    for y in range(h):
        line = ["V"] * w
        if w >= 3:
            line[-1] = "D"
        rows.append("D" * w if h >= 3 and y == h - 1 else "".join(line))
    return rows


def gold(w, h):
    rows = [["G"] * w for _ in range(h)]
    for y in range(h):
        rows[y][-1] = "Y"
    rows[-1] = ["Y"] * w if h > 1 else rows[-1]
    rows[0][0] = "C"
    return ["".join(r) for r in rows]


def stamp(grid, x, y, text):
    """Rows of grid with text written from (x, y), downwards when text is a list."""
    rows = [list(line) for line in grid]
    for dy, part in enumerate([text] if isinstance(text, str) else text):
        for dx, code in enumerate(part):
            if code != " ":
                rows[y + dy][x + dx] = code
    return ["".join(r) for r in rows]


FILL = {"stone": stone, "robe": robe, "leather": leather, "gold": gold}
TOP = {"stone": "V", "robe": "E", "leather": "V", "gold": "G"}
BOTTOM = {"stone": "D", "robe": "D", "leather": "D", "gold": "Y"}

# ---------------------------------------------------------------------------------------- cubes


class Cube(v.Cube):
    """Box with a material for the faces left alone and a grid for the others; a face set to None is not drawn."""

    def __init__(self, name, low, high, material, glow=False, **grids):
        super().__init__(low, high, name)
        self.material, self.glow, self.grids = material, glow, grids

    def grid(self, face):
        w, h = (max(1, round(s)) for s in self.size(face))
        if face in self.grids:
            g = self.grids[face]
            if g is None:
                return None
            if isinstance(g, str):
                g = [g * w] * h
        elif "sides" in self.grids and face in ("north", "south", "west", "east") and len(self.grids["sides"][0]) == w:
            g = self.grids["sides"]
        elif face == "up":
            g = [TOP[self.material] * w] * h
        elif face == "down":
            g = [BOTTOM[self.material] * w] * h
        else:
            g = FILL[self.material](w, h)
        if len(g) != h or any(len(line) != w for line in g):
            raise SystemExit("%s %s: grid is %dx%d, face is %dx%d" % (self.style, face, len(g[0]), len(g), w, h))
        return g

    def faces(self):
        out = {}
        for face in v.FACES:
            g = self.grid(face)
            if g is not None:
                out[face] = (1 if self.glow else 0, (GLOW if self.glow else MAIN).rect(g))
        return out

    def shell(self):
        """The emissive pixels of this cube on a box a hair larger, or None."""
        grids, any_glow = {}, False
        for face in v.FACES:
            g = self.grid(face)
            if g is None or not any(c.islower() for line in g for c in line):
                grids[face] = None
            else:
                grids[face] = ["".join(c if c.islower() else "." for c in line) for line in g]
                any_glow = True
        if not any_glow or self.glow:
            return None
        return Cube(self.style + "_lueur", [a - SHELL for a in self.low], [a + SHELL for a in self.high], self.material, True, **grids)


def lit(cubes):
    """Cubes followed by the emissive shells of those which glow."""
    return cubes + [s for s in (c.shell() for c in cubes) if s]


def mirror(c):
    """The same cube on the other side of the body, its pictures flipped."""
    grids = {}
    for face in v.FACES:
        g = c.grid({"west": "east", "east": "west"}.get(face, face))
        grids[face] = None if g is None else [line[::-1] for line in g]
    return Cube(c.style, [-c.high[0], c.low[1], c.low[2]], [-c.low[0], c.high[1], c.high[2]], c.material, c.glow, **grids)


# ---------------------------------------------------------------------------------------- pieces


def hat():
    """Volcano: four stone steps, one straight lava run cooling down the middle of each side, red lining and nape cloth."""
    under = ["".join("X" if 2 <= x <= 11 and 2 <= z <= 11 else "E" for x in range(14)) for z in range(14)]
    crater = ["SSSV", "ScmD", "SmuD", "VDDD"]
    return lit([
        Cube("bord", [-7, 30, -7], [7, 31, 7], "stone", sides=["CGGGGGGGGGGGGY"], up=stone(14, 14), down=under),
        Cube("gradin_1", [-6, 31, -6], [6, 33, 6], "stone", sides=stamp(stone(12, 2), 6, 0, ["u", "E"]), up=stone(12, 12)),
        Cube("gradin_2", [-4, 33, -4], [4, 35, 4], "stone", sides=stamp(stone(8, 2), 4, 0, ["m", "u"]), up=stone(8, 8)),
        Cube("cratere", [-2, 35, -2], [2, 37, 2], "stone", sides=stamp(stone(4, 2), 2, 0, ["m", "m"]), up=crater),
        Cube("couvre_nuque", [-4, 25, -5], [4, 30, -4], "robe", north=stamp(robe(8, 5), 0, 4, "GGGGGGGY"),
             south="X", west=["E", "E", "E", "E", "G"], east=["X", "X", "X", "X", "Y"], down="Y"),
    ])


def torso():
    front = stamp(robe(9, 12), 3, 0, ["DDD", " D "])
    apron = ["GVVVVG", "SVVVVD", "SVVVVD", "SVuuVD", "SumcuD", "SummuD", "SVuuVD", "SVVVVD", "SVVVVD", "VDDDDD"]
    return lit([
        # No top face: it is under the head.
        Cube("robe", [-4.5, 12, -2.5], [4.5, 24, 2.5], "robe", south=front, up=None),
        # Smith's apron of dark leather with a live coal set in it, the heart of the set.
        Cube("tablier", [-3, 12, 2.5], [3, 22, 3.5], "leather", south=apron, north=None,
             west=["G"] + ["V"] * 8 + ["D"], east=["Y"] + ["D"] * 9),
    ])


def right_arm():
    hem5, hem6 = "GGGGY", "GGGGGY"
    crack = stamp(stone(6, 7), 2, 2, ["u", "m", "u"])
    return lit([
        Cube("manche", [-8.5, 15, -3], [-3.5, 24, 3], "robe", south=stamp(robe(5, 9), 0, 8, hem5),
             north=stamp(robe(5, 9), 0, 8, hem5), west=stamp(robe(6, 9), 0, 8, hem6), east=stamp(robe(6, 9), 0, 8, hem6)),
        Cube("epauliere", [-10, 22, -3.5], [-4, 25, 3.5], "stone", up=crack, west=stamp(stone(7, 3), 3, 0, ["u", "E"])),
    ])


def legs_torso():
    return [
        Cube("ceinture", [-5, 10, -3], [5, 12, 3], "leather", sides=["SVVVVVVVVD", "VDDDDDDDDD"]),
        Cube("boucle", [-2, 10, 3], [2, 12, 4], "gold", south=["CGGG", "GDDY"], north=None),
    ]


def right_leg():
    # The two inner columns of the flare are equal: both flares overlap between the legs.
    flare = ["REEXEE", "REEXEE", "GGGGGG", "DDUDDD"]
    return [
        Cube("jupe_haut", [-4.5, 9, -2.5], [0.5, 12, 2.5], "robe", sides=["REEXX", "REEEX", "REEEX"]),
        Cube("jupe_evasee", [-5, 5, -3], [1, 9, 3], "robe", south=flare, north=["EEXEEX", "EEXEEX", "GGGGGG", "DDDUDD"], west=flare, east=["EEXEEE", "EEXEEE", "YYYYYY", "DDDDDD"]),
    ]


def right_boot():
    shaft = ["SVVVD", "DDGDD", "VVVVD", "VVVVD"]
    return lit([
        Cube("semelle", [-5, 0, -3], [1, 1, 6], "leather", sides="D", up="V"),
        Cube("tige", [-4.5, 1, -2.5], [0.5, 5, 2.5], "leather", sides=shaft),
        # Stone toe cap split by a crack from front to back.
        Cube("coque", [-4.5, 1, 2.5], [0.5, 3, 5.5], "stone", up=["SSuSV", "SVmVD", "SVuDD"], south=["SSuSV", "VDDDD"],
             west=["SSV", "VDD"], east=["SSV", "VDD"]),
    ])


def left_arm():
    return [mirror(c) for c in right_arm()]


GROUPS = {"plastron_torse": torso, "plastron_bras_droit": right_arm, "plastron_bras_gauche": left_arm,
          "jambieres_torse": legs_torso, "jambieres_jambe_droite": right_leg, "bottes_jambe_droite": right_boot}

# ---------------------------------------------------------------------------------------- outputs


def bbmodel(model):
    elements, outliner = [], []
    for name, cubes in model.items():
        ids = []
        for c in cubes:
            ident = str(uuid.uuid4())
            ids.append(ident)
            faces = {f: {"uv": list(r), "texture": t} for f, (t, r) in c.faces().items()}
            elements.append({"name": c.style, "type": "cube", "uuid": ident, "from": c.low, "to": c.high,
                             "origin": c.origin, "rotation": [0, 0, 0], "faces": faces})
        outliner.append({"name": name, "uuid": str(uuid.uuid4()), "origin": [0, 0, 0], "children": ids})
    # A texture named *neon* is emissive for FluxArmor.
    textures = [{"name": n, "uuid": str(uuid.uuid4()), "width": ATLAS, "height": ATLAS, "uv_width": ATLAS,
                 "uv_height": ATLAS, "source": v.png64(s.image)} for n, s in (("brasier_armure.png", MAIN), ("brasier_neon.png", GLOW))]
    return {"meta": {"format_version": "4.10", "model_format": "free", "box_uv": False}, "name": "brasier",
            "resolution": {"width": ATLAS, "height": ATLAS}, "elements": elements, "outliner": outliner, "textures": textures}


BACKGROUND = (70, 76, 88, 255)


def scene(model, swing=0.0, pitch=0.0, yaw=0.0):
    """All pieces on a plain mannequin; swing = legs and arms rotation in degrees, pitch / yaw of the head."""
    leg_r = (np.array([-1.9, 12.0, 0]), v.rot_x(swing))
    leg_l = (np.array([1.9, 12.0, 0]), v.rot_x(-swing))
    arm_r = (np.array([-5.0, 22.0, 0]), v.rot_x(-swing))
    arm_l = (np.array([5.0, 22.0, 0]), v.rot_x(swing))
    head = (np.array([0.0, 24.0, 0]), v.rot_y(yaw) @ v.rot_x(pitch))
    skin, grey = (198, 150, 114, 255), (120, 122, 128, 255)
    faces = v.body_box([-4, 24, -4], [4, 32, 4], skin, head) + v.body_box([-4, 12, -2], [4, 24, 2], grey)
    faces += v.body_box([-8, 12, -2], [-4, 24, 2], skin, arm_r) + v.body_box([4, 12, -2], [8, 24, 2], skin, arm_l)
    faces += v.body_box([-3.9, 0, -2], [0.1, 12, 2], grey, leg_r) + v.body_box([-0.1, 0, -2], [3.9, 12, 2], grey, leg_l)
    faces += v.quads(hat(), head) + v.quads(model["plastron_torse"]) + v.quads(model["jambieres_torse"])
    leg, boot = model["jambieres_jambe_droite"], model["bottes_jambe_droite"]
    faces += v.quads(model["plastron_bras_droit"], arm_r) + v.quads(model["plastron_bras_gauche"], arm_l)
    faces += v.quads(leg + boot, leg_r) + v.quads([mirror(c) for c in leg + boot], leg_l)
    return faces


def strip(shots, path, width=400, size=760, scale=16, centre=(0, 19)):
    sheet = Image.new("RGBA", (width * len(shots), size), BACKGROUND)
    left = (size - width) // 2
    for i, (faces, view, night) in enumerate(shots):
        im = v.render(faces, view, size, scale, centre=centre, background=(10, 10, 14, 255) if night else BACKGROUND, night=night)
        sheet.paste(im.crop((left, 0, left + width, size)), (width * i, 0))
    sheet.save(path)


def previews(model, folder):
    os.makedirs(folder, exist_ok=True)
    still = scene(model)
    strip([(still, v.rot_y(0), False), (still, v.rot_x(12) @ v.rot_y(-32), False), (still, v.rot_y(-90), False),
           (still, v.rot_x(12) @ v.rot_y(148), False), (still, v.rot_y(180), False)], os.path.join(folder, "brasier_vues.png"))
    strip([(scene(model, 30), v.rot_x(12) @ v.rot_y(-32), False), (scene(model, 45), v.rot_y(90), False),
           (scene(model, 0, 20, 40), v.rot_x(35) @ v.rot_y(30), False), (still, v.rot_x(12) @ v.rot_y(-32), True)],
          os.path.join(folder, "brasier_poses.png"))
    leg, boot = model["jambieres_jambe_droite"], model["bottes_jambe_droite"]
    pieces = [(hat(), (0, 32), 20), (model["plastron_torse"] + model["plastron_bras_droit"] + model["plastron_bras_gauche"], (0, 18.5), 20),
              (model["jambieres_torse"] + leg + [mirror(c) for c in leg], (0, 8.5), 26), (boot + [mirror(c) for c in boot], (0, 2.5), 30)]
    views = [v.rot_y(0), v.rot_x(22) @ v.rot_y(-30), v.rot_y(-90), v.rot_x(22) @ v.rot_y(150)]
    sheet = Image.new("RGBA", (400 * len(views), 400 * len(pieces)), BACKGROUND)
    for r, (cubes, centre, scale) in enumerate(pieces):
        for i, view in enumerate(views):
            sheet.paste(v.render(v.quads(cubes), view, 400, scale, centre=centre, background=BACKGROUND), (400 * i, 400 * r))
    sheet.save(os.path.join(folder, "brasier_pieces.png"))
    used = max(r[3] for r in MAIN.known.values())
    MAIN.image.crop((0, 0, ATLAS, used)).resize((ATLAS * 6, used * 6), Image.NEAREST).save(os.path.join(folder, "brasier_atlas_x6.png"))


def item_models(model):
    """Worn hat and inventory icons, as item models of influoutils."""
    def piece(cubes, scale, centre_y, gui, head=False):
        m = v.item_model(v.item_elements(cubes, scale, centre_y), gui, head)
        texture = v.NS + ":item/brasier/armure3d"
        m["credit"] = "Influencraft, generated by plugins/fluxplugin/FluxArmor/assets/models/gen_brasier_bbmodel.py"
        m["textures"] = {"main": texture, "glow": texture + "_neon", "particle": texture}
        return m

    leg, boot = model["jambieres_jambe_droite"], model["bottes_jambe_droite"]
    turn = [15, 155, 0]
    return {
        # On the head one skin pixel is 1.6 units: head centre at (8, 8, 8).
        "casque": piece(hat(), 1.6, 28, {"rotation": turn, "translation": [0, -5.0, 0], "scale": [0.42, 0.42, 0.42]}, True),
        "plastron": piece(model["plastron_torse"] + model["plastron_bras_droit"] + model["plastron_bras_gauche"], 1.0, 18.5,
                          {"rotation": turn, "translation": [0, 0, 0], "scale": [0.6, 0.6, 0.6]}),
        "jambieres": piece(model["jambieres_torse"] + leg + [mirror(c) for c in leg], 1.0, 8.5,
                           {"rotation": turn, "translation": [0, 0, 0], "scale": [0.95, 0.95, 0.95]}),
        "bottes": piece(boot + [mirror(c) for c in boot], 1.0, 2.5, {"rotation": turn, "translation": [0, 0, 0], "scale": [0.95, 0.95, 0.95]}),
    }


def deploy(model, bb):
    assets = v.INFLUOUTILS
    items = item_models(model)  # before the atlas is saved: mirrored cubes paint new rectangles
    v.write_json(os.path.join(v.FLUXARMOR_MODELS, "brasier.bbmodel"), bb)
    os.makedirs(os.path.join(assets, "textures/item/brasier"), exist_ok=True)
    MAIN.image.save(os.path.join(assets, "textures/item/brasier/armure3d.png"))
    GLOW.image.save(os.path.join(assets, "textures/item/brasier/armure3d_neon.png"))
    for piece, m in items.items():
        name = piece + "_brasier"
        v.write_json(os.path.join(assets, "models/item", name + ".json"), m)
        v.write_json(os.path.join(assets, "items", name + ".json"), {"model": {"type": "minecraft:model", "model": "%s:item/%s" % (v.NS, name)}})
    print("Deployed to Labs: run /fluxarmor build, then /iazip.")


def main():
    args = [a for a in sys.argv[1:] if a != "--deploy"]
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    v.ATLAS, v.ATLAS_MAIN, v.ATLAS_GLOW = ATLAS, MAIN, GLOW
    model = {k: fn() for k, fn in GROUPS.items()}
    bb = bbmodel(model)
    for name, cubes in list(model.items()) + [("hat (item model)", hat())]:
        print("  %-26s %2d cubes %3d faces" % (name, len(cubes), sum(len(c.faces()) for c in cubes)))
    if args:
        previews(model, args[0])
        v.write_json(os.path.join(args[0], "brasier.bbmodel"), bb)
    if "--deploy" in sys.argv:
        deploy(model, bb)


if __name__ == "__main__":
    main()
