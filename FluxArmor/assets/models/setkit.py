"""What the generators of hand-painted armour sets share: atlas, cubes with a pixel grid per face, emissive shells,
previews, Blockbench files and the deployment to Labs. A set module calls setup(), builds its cubes and ends with run().

Frame and limb groups are the ones of FluxArmor (the rasteriser and item model writer of gen_virale_bbmodel.py are
reused): one skin pixel per unit, feet at y = 0, +x towards the player's left, +z forwards.

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
SHELL = 0.02
BACKGROUND = (70, 76, 88, 255)

NAME = None
COLOURS = {}
FILL, TOP, BOTTOM = {}, {}, {}
MAIN = GLOW = None


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


def setup(name, palette, materials):
    """name: id of the set; palette: {letter: "#hex"}; materials: {name: (painter(w, h), top letter, bottom letter)}."""
    global NAME, MAIN, GLOW
    NAME = name
    COLOURS.clear()
    COLOURS.update({k: v.rgb(h) for k, h in palette.items()})
    COLOURS["."] = (0, 0, 0, 0)
    for store in (FILL, TOP, BOTTOM):
        store.clear()
    for material, (fill, top, bottom) in materials.items():
        FILL[material], TOP[material], BOTTOM[material] = fill, top, bottom
    MAIN, GLOW = Sheet(), Sheet()
    v.ATLAS, v.ATLAS_MAIN, v.ATLAS_GLOW = ATLAS, MAIN, GLOW


def paint(w, h, pixel):
    """Grid of w x h from pixel(x, y)."""
    return ["".join(pixel(x, y) for x in range(w)) for y in range(h)]


def stamp(grid, x, y, text):
    """Rows of grid with text written from (x, y), downwards when text is a list; a space leaves the pixel alone."""
    rows = [list(line) for line in grid]
    for dy, part in enumerate([text] if isinstance(text, str) else text):
        for dx, code in enumerate(part):
            if code != " ":
                rows[y + dy][x + dx] = code
    return ["".join(r) for r in rows]


class Cube(v.Cube):
    """Box with a material for the faces left alone and a grid for the others; a face set to None is not drawn.

    sides = one grid for the four vertical faces of that width. rot = Blockbench rotation (x, y, z) around origin.
    """

    def __init__(self, name, low, high, material, glow=False, rot=(0, 0, 0), origin=None, **grids):
        super().__init__(low, high, name, rot, origin)
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
        return Cube(self.style + "_lueur", [a - SHELL for a in self.low], [a + SHELL for a in self.high], self.material,
                    True, self.rotation, self.origin, **grids)


def lit(cubes):
    """Cubes followed by the emissive shells of those which glow."""
    return cubes + [s for s in (c.shell() for c in cubes) if s]


def mirror(c):
    """The same cube on the other side of the body, its pictures flipped."""
    grids = {}
    for face in v.FACES:
        g = c.grid({"west": "east", "east": "west"}.get(face, face))
        grids[face] = None if g is None else [line[::-1] for line in g]
    return Cube(c.style, [-c.high[0], c.low[1], c.low[2]], [-c.low[0], c.high[1], c.high[2]], c.material, c.glow,
                (c.rotation[0], -c.rotation[1], -c.rotation[2]), [-c.origin[0], c.origin[1], c.origin[2]], **grids)


def left(model, key):
    """Cubes of a left limb: its own group, else the right one mirrored. key = the right limb's group."""
    other = key.replace("_droite", "_gauche").replace("_droit", "_gauche")
    return model[other] if other in model else [mirror(c) for c in model[key]]


# ---------------------------------------------------------------------------------------- outputs


def bbmodel(name, groups):
    """Blockbench file of limb groups {name: cubes}, cubes sorted in a sub-group per material."""
    elements, outliner = [], []
    for group, cubes in groups.items():
        by_material = {}
        for c in cubes:
            ident = str(uuid.uuid4())
            faces = {f: {"uv": list(r), "texture": t} for f, (t, r) in c.faces().items()}
            elements.append({"name": c.style, "type": "cube", "uuid": ident, "from": c.low, "to": c.high,
                             "origin": c.origin, "rotation": c.rotation, "faces": faces})
            by_material.setdefault("lueur" if c.glow else c.material, []).append(ident)
        children = [{"name": m, "uuid": str(uuid.uuid4()), "origin": [0, 0, 0], "children": ids} for m, ids in by_material.items()]
        outliner.append({"name": group, "uuid": str(uuid.uuid4()), "origin": [0, 0, 0], "children": children})
    # A texture named *neon* is emissive for FluxArmor.
    textures = [{"name": n, "uuid": str(uuid.uuid4()), "width": ATLAS, "height": ATLAS, "uv_width": ATLAS, "uv_height": ATLAS,
                 "source": v.png64(s.image)} for n, s in ((NAME + "_armure.png", MAIN), (NAME + "_neon.png", GLOW))]
    return {"meta": {"format_version": "4.10", "model_format": "free", "box_uv": False}, "name": name,
            "resolution": {"width": ATLAS, "height": ATLAS}, "elements": elements, "outliner": outliner, "textures": textures}


def pieces(model, hat):
    """The four pieces as lists of cubes, both sides of the body: head, chest, legs, feet."""
    leg, boot = "jambieres_jambe_droite", "bottes_jambe_droite"
    return [hat, model["plastron_torse"] + model["plastron_bras_droit"] + left(model, "plastron_bras_droit"),
            model["jambieres_torse"] + model[leg] + left(model, leg), model[boot] + left(model, boot)]


def scene(model, hat, swing=0.0, pitch=0.0, yaw=0.0):
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
    faces += v.quads(hat, head) + v.quads(model["plastron_torse"]) + v.quads(model["jambieres_torse"])
    leg, boot = "jambieres_jambe_droite", "bottes_jambe_droite"
    faces += v.quads(model["plastron_bras_droit"], arm_r) + v.quads(left(model, "plastron_bras_droit"), arm_l)
    faces += v.quads(model[leg] + model[boot], leg_r) + v.quads(left(model, leg) + left(model, boot), leg_l)
    return faces


def strip(shots, path, width=420, size=760, scale=16, centre=(0, 19)):
    sheet = Image.new("RGBA", (width * len(shots), size), BACKGROUND)
    start = (size - width) // 2
    for i, (faces, view, night) in enumerate(shots):
        im = v.render(faces, view, size, scale, centre=centre, background=(10, 10, 14, 255) if night else BACKGROUND, night=night)
        sheet.paste(im.crop((start, 0, start + width, size)), (width * i, 0))
    sheet.save(path)


def previews(model, hat, folder):
    os.makedirs(folder, exist_ok=True)
    still = scene(model, hat)
    strip([(still, v.rot_y(0), False), (still, v.rot_x(12) @ v.rot_y(-32), False), (still, v.rot_y(-90), False),
           (still, v.rot_y(90), False), (still, v.rot_x(12) @ v.rot_y(148), False), (still, v.rot_y(180), False)],
          os.path.join(folder, NAME + "_vues.png"))
    strip([(scene(model, hat, 30), v.rot_x(12) @ v.rot_y(-32), False), (scene(model, hat, 45), v.rot_y(90), False),
           (scene(model, hat, 0, 20, 40), v.rot_x(35) @ v.rot_y(30), False), (still, v.rot_x(12) @ v.rot_y(-32), True)],
          os.path.join(folder, NAME + "_poses.png"))
    views = [v.rot_y(0), v.rot_x(22) @ v.rot_y(-30), v.rot_y(-90), v.rot_x(22) @ v.rot_y(150), v.rot_y(180)]
    frames = [((0, 31), 22), ((0, 18.5), 20), ((0, 8), 28), ((0, 2.5), 32)]
    sheet = Image.new("RGBA", (400 * len(views), 400 * 4), BACKGROUND)
    for r, (cubes, (centre, scale)) in enumerate(zip(pieces(model, hat), frames)):
        for i, view in enumerate(views):
            sheet.paste(v.render(v.quads(cubes), view, 400, scale, centre=centre, background=BACKGROUND), (400 * i, 400 * r))
    sheet.save(os.path.join(folder, NAME + "_pieces.png"))
    used = max(r[3] for r in MAIN.known.values())
    MAIN.image.crop((0, 0, ATLAS, used)).resize((ATLAS * 6, used * 6), Image.NEAREST).save(os.path.join(folder, NAME + "_atlas_x6.png"))


def item_models(model, hat, gui):
    """Worn hat and inventory icons, as item models of influoutils. gui = {piece: (translation y, scale)}."""
    head, chest, legs, feet = pieces(model, hat)
    credit = "Influencraft, generated by plugins/fluxplugin/FluxArmor/assets/models/gen_%s_bbmodel.py" % NAME
    texture = "%s:item/%s/armure3d" % (v.NS, NAME)
    out = {}
    # On the head one skin pixel is 1.6 units: head centre at (8, 8, 8).
    for piece, cubes, scale, centre_y in (("casque", head, 1.6, 28), ("plastron", chest, 1.0, 18.5),
                                          ("jambieres", legs, 1.0, 8.5), ("bottes", feet, 1.0, 2.5)):
        shift, size = gui[piece]
        m = v.item_model(v.item_elements(cubes, scale, centre_y),
                         {"rotation": [15, 155, 0], "translation": [0, shift, 0], "scale": [size, size, size]}, piece == "casque")
        m["credit"] = credit
        m["textures"] = {"main": texture, "glow": texture + "_neon", "particle": texture}
        out[piece] = m
    return out


def deploy(model, hat, gui):
    assets = v.INFLUOUTILS
    items = item_models(model, hat, gui)  # before the atlas is saved: mirrored cubes paint new rectangles
    v.write_json(os.path.join(v.FLUXARMOR_MODELS, NAME + ".bbmodel"), bbmodel(NAME, model))
    os.makedirs(os.path.join(assets, "textures/item", NAME), exist_ok=True)
    MAIN.image.save(os.path.join(assets, "textures/item", NAME, "armure3d.png"))
    GLOW.image.save(os.path.join(assets, "textures/item", NAME, "armure3d_neon.png"))
    for piece, m in items.items():
        name = piece + "_" + NAME
        v.write_json(os.path.join(assets, "models/item", name + ".json"), m)
        v.write_json(os.path.join(assets, "items", name + ".json"), {"model": {"type": "minecraft:model", "model": "%s:item/%s" % (v.NS, name)}})
    print("Deployed to Labs: run /fluxarmor build, then /iazip.")


def listing(model, hat, path):
    """Markdown table of every cube: group, material, corners, rotation and its pivot."""
    lines = ["| Group | Cube | Material | From | To | Rotation (x, y, z) | Pivot |", "|---|---|---|---|---|---|---|"]
    for group, cubes in [("tete", hat)] + list(model.items()):
        for c in cubes:
            fmt = lambda p: " / ".join("%g" % round(a, 3) for a in p)
            turned = any(c.rotation)
            lines.append("| %s | %s | %s | %s | %s | %s | %s |" % (group, c.style, "lueur" if c.glow else c.material, fmt(c.low),
                                                              fmt(c.high), fmt(c.rotation) if turned else "", fmt(c.origin) if turned else ""))
    with open(path, "w") as fh:
        fh.write("\n".join(lines) + "\n")


def run(doc, groups, hat, gui, sources=None):
    """groups: {limb group: function}; hat: function; sources: {file name: group prefix} of editable files per piece."""
    args = [a for a in sys.argv[1:] if a != "--deploy"]
    if len(sys.argv) < 2:
        raise SystemExit(doc)
    model = {k: fn() for k, fn in groups.items()}
    head = hat()
    for name, cubes in list(model.items()) + [("hat (item model)", head)]:
        print("  %-26s %2d cubes %3d faces" % (name, len(cubes), sum(len(c.faces()) for c in cubes)))
    if args:
        previews(model, head, args[0])
        for file, prefix in (sources or {}).items():
            part = {"tete": head} if prefix == "tete" else {k: c for k, c in model.items() if k.startswith(prefix)}
            v.write_json(os.path.join(args[0], file + ".bbmodel"), bbmodel(file, part))
        v.write_json(os.path.join(args[0], NAME + ".bbmodel"), bbmodel(NAME, model))
        listing(model, head, os.path.join(args[0], NAME + "_cubes.md"))
        MAIN.image.save(os.path.join(args[0], NAME + "_armure.png"))
        GLOW.image.save(os.path.join(args[0], NAME + "_neon.png"))
    if "--deploy" in sys.argv:
        deploy(model, head, gui)
