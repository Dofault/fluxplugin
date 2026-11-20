"""Construction des modèles Blockbench (.bbmodel) des familiers InfluCard.

Style repris de familier_esprit_feu (modèle d'origine) : cubes simples, texture 64x64 en tuiles
(faces 8x8, bandes 4x8, membres 4x4), palette de 5 tons + yeux à reflet, joues et bouche.
Chaque archétype (esprit, créature, volant, champignon/méduse, golem, serpent) pose des os
nommés par rôle, et les 4 animations (idle, walk, happy, sleep) sont générées à partir des rôles.
Orientation Blockbench : le familier regarde le nord (-z), visage sur la face north de h_head.
"""
import base64
import json
import uuid

from png_util import encode

NS = uuid.UUID("6f1c0a52-3b7e-4c2a-9d4e-1f2b3c4d5e6f")

# --------------------------------------------------------------------------- texture

# Tuiles : nom -> (u, v, largeur, hauteur)
TILES = {
    "face": (0, 0, 8, 8),
    "body": (8, 0, 8, 8),
    "body2": (16, 0, 8, 8),
    "accA": (24, 0, 4, 8),
    "accB": (28, 0, 4, 8),
    "accC": (32, 0, 4, 8),
    "limb": (36, 0, 4, 4),
    "dark": (40, 0, 4, 4),
    "glow": (44, 0, 4, 4),
    "white": (48, 0, 4, 4),
}


def hexrgb(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


def paint_texture(pal):
    """pal : base, dark, mid, light, hi, eye, cheek, mouth, accA (3 tons), accB, accC, glow."""
    px = [[(0, 0, 0, 0)] * 64 for _ in range(64)]
    C = {k: hexrgb(v) if isinstance(v, str) else tuple(hexrgb(x) for x in v) for k, v in pal.items()}

    def put(x, y, c):
        px[y][x] = c

    def fill(u, v, w, h, c):
        for y in range(v, v + h):
            for x in range(u, u + w):
                put(x, y, c)

    def body_tile(u, base, mid, light):
        fill(u, 0, 8, 8, base)
        for x in range(8):
            put(u + x, 7, mid)
        for y in range(8):
            put(u + 7, y, mid)
        put(u + 1, 1, light)
        put(u + 2, 1, light)
        put(u + 1, 2, light)

    body_tile(TILES["body"][0], C["base"], C["mid"], C["light"])
    body_tile(TILES["body2"][0], C["light"], C["base"], C["hi"])
    # visage : base + yeux 2x3 avec reflet, joues, bouche
    body_tile(TILES["face"][0], C["base"], C["mid"], C["light"])
    fu = TILES["face"][0]
    for ex in (1, 5):
        fill(fu + ex, 2, 2, 3, C["eye"])
        put(fu + ex, 2, (255, 255, 255, 255))
    put(fu + 0, 5, C["cheek"])
    put(fu + 7, 5, C["cheek"])
    put(fu + 3, 5, C["mouth"])
    put(fu + 4, 5, C["mouth"])

    for name in ("accA", "accB", "accC"):
        u, v, w, h = TILES[name]
        light, base, dark = C[name]
        fill(u, 0, w, 3, light)
        fill(u, 3, w, 3, base)
        fill(u, 6, w, 2, dark)
    u = TILES["limb"][0]
    fill(u, 0, 4, 4, C["mid"])
    fill(u, 3, 4, 1, C["dark"])
    fill(TILES["dark"][0], 0, 4, 4, C["dark"])
    u = TILES["glow"][0]
    fill(u, 0, 4, 4, C["glow"])
    put(u, 0, C["hi"])
    fill(TILES["white"][0], 0, 4, 4, C["hi"])
    return px


# --------------------------------------------------------------------------- géométrie

class Model:
    def __init__(self, ident):
        self.ident = ident
        self.elements = []
        self.bones = {}  # nom -> dict (origin, children)
        self.roots = []
        self.roles = {}

    def uid(self, name):
        return str(uuid.uuid5(NS, f"{self.ident}/{name}"))

    def bone(self, name, origin, parent=None):
        b = {"name": name, "origin": list(origin), "children": []}
        self.bones[name] = b
        if parent is None:
            self.roots.append(b)
        else:
            self.bones[parent]["children"].append(b)
        return name

    def cube(self, bone, name, frm, to, mat, rot=None, origin=None, face_north=None):
        frm = [round(v, 3) for v in frm]
        to = [round(v, 3) for v in to]
        if origin is None:
            origin = [(a + b) / 2 for a, b in zip(frm, to)]
        faces = {}
        for d in ("north", "south", "east", "west", "up", "down"):
            tile = face_north if (d == "north" and face_north) else mat
            u, v, w, h = TILES[tile]
            faces[d] = {"uv": [u, v, u + w, v + h], "texture": 0}
        el = {
            "name": name, "box_uv": False, "rescale": False, "locked": False,
            "render_order": "default", "allow_mirror_modeling": True,
            "from": frm, "to": to, "autouv": 0, "color": 0,
            "origin": [round(v, 3) for v in origin], "faces": faces, "type": "cube",
            "uuid": self.uid("el/" + bone + "/" + name + "/" + str(len(self.elements))),
            "_mat": mat, "_north": face_north or mat,
        }
        if rot:
            el["rotation"] = list(rot)
        self.elements.append(el)
        self.bones[bone]["children"].append(el["uuid"])
        return el

    def role(self, role, *bones):
        self.roles.setdefault(role, []).extend(bones)

    # ------------------------------------------------------------------ export

    def outliner(self):
        def conv(b):
            return {
                "name": b["name"], "origin": b["origin"], "color": 0, "uuid": self.uid("bone/" + b["name"]),
                "export": True, "mirror_uv": False, "isOpen": True, "locked": False, "visibility": True,
                "autouv": 0, "rotation": [0, 0, 0],
                "children": [c if isinstance(c, str) else conv(c) for c in b["children"]],
            }
        return [conv(b) for b in self.roots]

    def to_bbmodel(self, texture_px, animations):
        png = encode(64, 64, texture_px)
        tex = {
            "path": "", "name": self.ident + ".png", "folder": "", "namespace": "", "id": "0",
            "width": 64, "height": 64, "uv_width": 64, "uv_height": 64, "particle": False,
            "use_as_default": False, "layers_enabled": False, "sync_to_project": "",
            "render_mode": "default", "render_sides": "auto", "frame_time": 1,
            "frame_order_type": "loop", "frame_order": "", "frame_interpolate": False,
            "visible": True, "internal": True, "saved": False,
            "uuid": self.uid("texture"),
            "source": "data:image/png;base64," + base64.b64encode(png).decode(),
        }
        elements = [{k: v for k, v in e.items() if not k.startswith("_")} for e in self.elements]
        # boîte de collision (comme le modèle d'origine)
        hit = {
            "name": "hitbox", "box_uv": False, "rescale": False, "locked": False, "render_order": "default",
            "allow_mirror_modeling": True, "from": [-5, 0, -5], "to": [5, 16, 5], "autouv": 0, "color": 0,
            "origin": [0, 8, 0], "faces": {d: {"uv": [0, 0, 0, 0], "texture": None}
                                           for d in ("north", "south", "east", "west", "up", "down")},
            "type": "cube", "uuid": self.uid("hitbox"),
        }
        elements.append(hit)
        outliner = self.outliner()
        outliner.append({"name": "hitbox", "origin": [0, 0, 0], "color": 0, "uuid": self.uid("bone/hitbox"),
                         "export": True, "mirror_uv": False, "isOpen": True, "locked": False,
                         "visibility": True, "autouv": 0, "rotation": [0, 0, 0], "children": [hit["uuid"]]})
        return {
            "meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
            "name": self.ident, "model_identifier": self.ident, "visible_box": [1, 1.5, 0],
            "variable_placeholders": "", "variable_placeholder_buttons": [], "timeline_setups": [],
            "unhandled_root_fields": {}, "resolution": {"width": 64, "height": 64},
            "elements": elements, "outliner": outliner, "textures": [tex],
            "animations": animations,
        }


# --------------------------------------------------------------------------- archétypes

def crown(m, kind, top, parent, acc="accA"):
    """Décor posé sur la tête (y = top)."""
    if kind == "flame":
        m.cube(parent, "flame_c", [-1.5, top, -1.5], [1.5, top + 6, 1.5], acc)
        m.cube(parent, "flame_l", [-3.5, top, -1], [-1.5, top + 4, 1], acc, rot=[0, 0, 18], origin=[-2.5, top, 0])
        m.cube(parent, "flame_r", [1.5, top, -1], [3.5, top + 4, 1], acc, rot=[0, 0, -18], origin=[2.5, top, 0])
        m.cube(parent, "flame_t", [-0.6, top + 5, -0.6], [0.6, top + 8, 0.6], "glow")
    elif kind == "drop":
        m.cube(parent, "drop_1", [-2.5, top, -2.5], [2.5, top + 2, 2.5], acc)
        m.cube(parent, "drop_2", [-1.5, top + 2, -1.5], [1.5, top + 4, 1.5], acc)
        m.cube(parent, "drop_3", [-0.5, top + 4, -0.5], [0.5, top + 6, 0.5], "glow")
    elif kind == "leaf":
        m.cube(parent, "stem", [-0.5, top, -0.5], [0.5, top + 3, 0.5], "accB")
        m.cube(parent, "leaf_l", [-4, top + 2, -1], [-0.5, top + 3, 1.5], acc, rot=[0, 0, 22], origin=[-0.5, top + 2.5, 0])
        m.cube(parent, "leaf_r", [0.5, top + 2, -1], [4, top + 3, 1.5], acc, rot=[0, 0, -22], origin=[0.5, top + 2.5, 0])
    elif kind == "zigzag":
        m.cube(parent, "bolt_1", [-1, top, -0.5], [1, top + 3, 0.5], acc)
        m.cube(parent, "bolt_2", [0, top + 2.5, -0.5], [2.5, top + 4, 0.5], acc)
        m.cube(parent, "bolt_3", [1, top + 4, -0.5], [2.5, top + 7, 0.5], "glow")
        m.cube(parent, "bolt_4", [-3, top, -0.5], [-2, top + 3, 0.5], "glow", rot=[0, 0, 15], origin=[-2.5, top, 0])
    elif kind == "crystal":
        m.cube(parent, "crys_c", [-1, top, -1], [1, top + 5, 1], acc, rot=[0, 45, 0])
        m.cube(parent, "crys_l", [-3.5, top - 0.5, -0.75], [-2, top + 3, 0.75], acc, rot=[0, 0, 20], origin=[-2.75, top, 0])
        m.cube(parent, "crys_r", [2, top - 0.5, -0.75], [3.5, top + 3, 0.75], acc, rot=[0, 0, -20], origin=[2.75, top, 0])
        m.cube(parent, "crys_t", [-0.5, top + 4.5, -0.5], [0.5, top + 6, 0.5], "white")
    elif kind == "antenna":
        for side, x in (("l", -2), ("r", 2)):
            m.cube(parent, "ant_" + side, [x - 0.5, top, -0.5], [x + 0.5, top + 4, 0.5], "dark",
                   rot=[0, 0, 15 if x < 0 else -15], origin=[x, top, 0])
            m.cube(parent, "ant_tip_" + side, [x * 1.6 - 1, top + 3.5, -1], [x * 1.6 + 1, top + 5.5, 1], "glow")
    elif kind == "halo":
        y = top + 3
        m.cube(parent, "halo_n", [-3, y, -3], [3, y + 0.75, -2.25], "glow")
        m.cube(parent, "halo_s", [-3, y, 2.25], [3, y + 0.75, 3], "glow")
        m.cube(parent, "halo_e", [2.25, y, -2.25], [3, y + 0.75, 2.25], "glow")
        m.cube(parent, "halo_w", [-3, y, -2.25], [-2.25, y + 0.75, 2.25], "glow")
    elif kind == "hood":
        m.cube(parent, "hood", [-4.5, top - 7, -3], [4.5, top + 1, 4.5], "dark")
        m.cube(parent, "hood_tip", [-1.5, top + 1, 1], [1.5, top + 3, 4], "dark", rot=[-20, 0, 0], origin=[0, top + 1, 2])
    elif kind == "ears":
        for side, x in (("l", -3), ("r", 3)):
            m.cube(parent, "ear_" + side, [x - 1, top, -1], [x + 1, top + 3, 0.5], acc,
                   rot=[0, 0, 12 if x < 0 else -12], origin=[x, top, 0])
    elif kind == "horns":
        for side, x in (("l", -2.5), ("r", 2.5)):
            m.cube(parent, "horn_" + side, [x - 0.75, top, -0.5], [x + 0.75, top + 4, 1], acc,
                   rot=[-25, 0, 15 if x < 0 else -15], origin=[x, top, 0])
    elif kind == "unihorn":
        m.cube(parent, "horn", [-0.75, top, -2.5], [0.75, top + 5, -1], "glow", rot=[25, 0, 0], origin=[0, top, -1.75])
    elif kind == "mane":
        m.cube(parent, "mane", [-1, top - 4, 0], [1, top + 1, 4], acc)
    elif kind == "nugget":  # pépites d'or
        m.cube(parent, "nugget_1", [-2, top, -1.5], [1, top + 2.5, 1.5], "glow", rot=[0, 20, 0])
        m.cube(parent, "nugget_2", [1, top, -1], [3, top + 1.5, 1], acc, rot=[0, -15, 0])
        m.cube(parent, "nugget_3", [-1, top + 2, -0.75], [0.5, top + 3.5, 0.75], "white")
    elif kind == "acorncap":  # chapeau de gland
        m.cube(parent, "cap", [-4.5, top - 1.5, -4.5], [4.5, top + 1.5, 4.5], acc)
        m.cube(parent, "cap_top", [-3, top + 1.5, -3], [3, top + 2.5, 3], acc)
        m.cube(parent, "cap_stem", [-0.5, top + 2.5, -0.5], [0.5, top + 4.5, 0.5], "dark")
    elif kind == "helmet":  # heaume de chevalier
        m.cube(parent, "helm", [-4.5, top - 4, -4.5], [4.5, top + 1, 4.5], "accC")
        m.cube(parent, "helm_visor", [-4.6, top - 2.5, -4.6], [4.6, top - 1.5, -4.5], "dark")
        m.cube(parent, "helm_plume", [-0.5, top + 1, -2], [0.5, top + 4, 3], acc, rot=[-15, 0, 0], origin=[0, top + 1, 0])
    elif kind == "hat":  # chapeau de paille (épouvantail)
        m.cube(parent, "hat_brim", [-5, top, -5], [5, top + 1, 5], acc)
        m.cube(parent, "hat_top", [-2.5, top + 1, -2.5], [2.5, top + 4, 2.5], acc)
        m.cube(parent, "hat_band", [-2.75, top + 1, -2.75], [2.75, top + 1.75, 2.75], "dark")
    elif kind == "hardhat":  # casque de chantier
        m.cube(parent, "helmet", [-3.5, top - 0.5, -3.5], [3.5, top + 2.5, 3.5], "glow")
        m.cube(parent, "helmet_brim", [-3.5, top - 0.5, -5], [3.5, top + 0.5, -3.5], "glow")
        m.cube(parent, "helmet_ridge", [-0.5, top + 2.5, -3], [0.5, top + 3.25, 3], "accA")
    elif kind == "flask":  # col et bouchon de fiole, bulles
        m.cube(parent, "neck", [-1.5, top, -1.5], [1.5, top + 2.5, 1.5], "body2")
        m.cube(parent, "cork", [-1.25, top + 2.5, -1.25], [1.25, top + 4.5, 1.25], "dark")
        m.cube(parent, "bubble_1", [2.5, top + 1, -0.5], [3.5, top + 2, 0.5], "white")
        m.cube(parent, "bubble_2", [-3.5, top + 2.5, 0], [-2.75, top + 3.25, 0.75], "white")
        m.cube(parent, "bubble_3", [1.5, top + 4, 1], [2.25, top + 4.75, 1.75], "glow")
    elif kind == "crest":
        for i, z in enumerate((-1, 0.5, 2)):
            m.cube(parent, f"crest_{i}", [-0.75, top, z], [0.75, top + 4 - i, z + 1.25], acc,
                   rot=[-25, 0, 0], origin=[0, top, z])


def spirit(m, crown_kind, w=8, legs=False, wings=False, arms=True, wisp=True, tail=None, scythe=False,
           sword=False):
    """Esprit : tête-corps cubique flottant (comme l'esprit de feu), décor, bras, volutes."""
    base = 3 if legs else 8
    h = w
    m.bone("familier", [0, base, 0])
    m.bone("h_head", [0, base + h / 2, 0], "familier")
    m.cube("h_head", "body", [-w / 2, base, -w / 2], [w / 2, base + h, w / 2], "body", face_north="face")
    m.bone("crown", [0, base + h, 0], "h_head")
    crown(m, crown_kind, base + h, "crown")
    m.role("root", "familier")
    m.role("head", "h_head")
    m.role("crown", "crown")
    if arms:
        for side, sx in (("L", -1), ("R", 1)):
            name = "arm_" + side
            ox = sx * w / 2
            m.bone(name, [ox, base + 3, 0], "h_head")
            x0, x1 = (ox - 2, ox) if sx < 0 else (ox, ox + 2)
            m.cube(name, name, [x0, base + 2, -1], [x1, base + 4, 1], "limb")
            m.role("arms", name)
        if sword:
            m.cube("arm_R", "sword_blade", [w / 2 + 0.75, base - 1, -0.5], [w / 2 + 1.75, base + 10, 0.5], "accC")
            m.cube("arm_R", "sword_guard", [w / 2 - 0.5, base + 1.5, -0.75], [w / 2 + 3, base + 2.5, 0.75], "glow")
        if scythe:
            m.cube("arm_R", "scythe_stick", [w / 2 + 0.5, base - 4, -0.5], [w / 2 + 1.5, base + 9, 0.5], "dark")
            m.cube("arm_R", "scythe_blade", [w / 2 - 4, base + 7.5, -0.5], [w / 2 + 1.5, base + 9, 0.5], "accC",
                   rot=[0, 0, -12], origin=[w / 2 + 1, base + 8, 0])
    if wings:
        for side, sx in (("L", -1), ("R", 1)):
            name = "wing_" + side
            m.bone(name, [sx * w / 4, base + h - 2, w / 2], "h_head")
            x0, x1 = (sx * 6.5, sx * 0.5) if sx < 0 else (0.5, 6.5)
            m.cube(name, name, [x0, base + h - 3, w / 2], [x1, base + h + 1, w / 2 + 0.75], "glow")
            m.role("wings", name)
    if legs:
        for side, x in (("L", -2), ("R", 2)):
            name = "leg_" + side
            m.bone(name, [x, base, 0], "familier")
            m.cube(name, name, [x - 1, 0, -1], [x + 1, base, 1], "limb")
            m.role("legs", name)
    if wisp and not legs:
        m.bone("wisp1", [0, base, 0], "familier")
        m.cube("wisp1", "wisp1", [-3, base - 2.8, -2.5], [3, base, 2.5], "accA")
        m.bone("wisp2", [0, base - 2.5, 1], "wisp1")
        m.cube("wisp2", "wisp2", [-2, base - 5.2, -0.5], [2, base - 2.5, 3], "accA")
        m.bone("wisp3", [0, base - 5, 2], "wisp2")
        m.cube("wisp3", "wisp3", [-1, base - 7.5, 1.5], [1, base - 5, 3.5], "glow")
        m.role("tail", "wisp1", "wisp2", "wisp3")


def critter(m, head_deco=(), tail="short", long_body=False, size=1.0, teeth=False):
    """Créature à quatre pattes : corps, tête à l'avant, pattes, queue, décor de tête."""
    s = size
    L = 11 if long_body else 8
    m.bone("familier", [0, 3 * s, 0])
    m.cube("familier", "body", [-3 * s, 3 * s, -L / 2 * s], [3 * s, 8 * s, L / 2 * s], "body")
    m.cube("familier", "belly", [-2 * s, 2.5 * s, -L / 2 * s + 1], [2 * s, 3 * s, L / 2 * s - 1], "body2")
    m.bone("h_head", [0, 7 * s, -L / 2 * s], "familier")
    hz = -L / 2 * s
    m.cube("h_head", "head", [-3.5 * s, 6 * s, hz - 6 * s], [3.5 * s, 12.5 * s, hz + 0.5], "body", face_north="face")
    m.cube("h_head", "snout", [-1.5 * s, 6.5 * s, hz - 7 * s], [1.5 * s, 8.5 * s, hz - 6 * s], "body2")
    if teeth:
        m.cube("h_head", "teeth", [-1 * s, 5 * s, hz - 7 * s], [1 * s, 6.5 * s, hz - 6.5 * s], "white")
    m.bone("crown", [0, 12.5 * s, hz - 3 * s], "h_head")
    for deco in head_deco:
        crown(m, deco, 12.5 * s, "crown", acc="accA")
    m.role("root", "familier")
    m.role("head", "h_head")
    m.role("crown", "crown")
    for name, x, z in (("leg_FL", -2, -L / 2 + 1.5), ("leg_FR", 2, -L / 2 + 1.5),
                       ("leg_BL", -2, L / 2 - 1.5), ("leg_BR", 2, L / 2 - 1.5)):
        m.bone(name, [x * s, 3 * s, z * s], "familier")
        m.cube(name, name, [(x - 1) * s, 0, (z - 1) * s], [(x + 1) * s, 3 * s, (z + 1) * s], "limb")
        m.role("legs", name)
    tz = L / 2 * s
    m.bone("tail", [0, 7 * s, tz], "familier")
    if tail == "fluffy":
        m.cube("tail", "tail", [-2 * s, 6 * s, tz], [2 * s, 10 * s, tz + 6 * s], "accA", rot=[-30, 0, 0], origin=[0, 7 * s, tz])
        m.cube("tail", "tail_tip", [-1.5 * s, 9 * s, tz + 4 * s], [1.5 * s, 11.5 * s, tz + 6.5 * s], "white", rot=[-30, 0, 0], origin=[0, 7 * s, tz])
    elif tail == "long":
        m.cube("tail", "tail", [-1.5 * s, 4.5 * s, tz], [1.5 * s, 6.5 * s, tz + 6 * s], "body")
        m.bone("tail2", [0, 5.5 * s, tz + 6 * s], "tail")
        m.cube("tail2", "tail2", [-1 * s, 5 * s, tz + 6 * s], [1 * s, 6.5 * s, tz + 10 * s], "accA")
        m.cube("tail2", "tail_flame", [-1 * s, 6.5 * s, tz + 8.5 * s], [1 * s, 9.5 * s, tz + 10 * s], "glow")
        m.role("tail", "tail2")
    elif tail == "flat":
        m.cube("tail", "tail", [-2.5 * s, 3.5 * s, tz], [2.5 * s, 4.5 * s, tz + 7 * s], "dark", rot=[10, 0, 0], origin=[0, 4 * s, tz])
    elif tail == "mane":
        m.cube("tail", "tail", [-1 * s, 3 * s, tz], [1 * s, 8 * s, tz + 2 * s], "accA", rot=[20, 0, 0], origin=[0, 7 * s, tz])
    else:
        m.cube("tail", "tail", [-1 * s, 6 * s, tz], [1 * s, 8 * s, tz + 3 * s], "accA", rot=[-25, 0, 0], origin=[0, 7 * s, tz])
    m.role("tail", "tail")


def flyer(m, head_deco=(), wing="feather", tail="fan", beak=True, four_wings=False):
    """Volant : corps, tête, bec, ailes battantes, queue, pattes."""
    m.bone("familier", [0, 4, 0])
    m.cube("familier", "body", [-3, 4, -3], [3, 10, 4], "body")
    m.cube("familier", "chest", [-2, 4.5, -3.5], [2, 9, -3], "body2")
    m.bone("h_head", [0, 10, -1], "familier")
    m.cube("h_head", "head", [-2.75, 9, -6], [2.75, 14.5, -0.5], "body", face_north="face")
    if beak:
        m.cube("h_head", "beak", [-1, 10, -7.5], [1, 11.5, -6], "accB")
    m.bone("crown", [0, 14.5, -3], "h_head")
    for deco in head_deco:
        crown(m, deco, 14.5, "crown")
    m.role("root", "familier")
    m.role("head", "h_head")
    m.role("crown", "crown")
    pairs = [("", 9)] + ([("2", 6.5)] if four_wings else [])
    for suffix, y in pairs:
        for side, sx in (("L", -1), ("R", 1)):
            name = f"wing_{side}{suffix}"
            m.bone(name, [sx * 3, y, 0], "familier")
            x0, x1 = (sx * 11, sx * 3) if sx < 0 else (3, 11)
            mat = "accA" if wing == "feather" else "dark"
            m.cube(name, name, [x0, y - 0.5, -2], [x1, y + 0.5, 4], mat)
            tip0, tip1 = (sx * 11, sx * 7) if sx < 0 else (7, 11)
            m.cube(name, name + "_tip", [tip0, y - 0.5, 4], [tip1, y + 0.5, 6], "glow" if wing == "feather" else "accA")
            m.role("wings", name)
    m.bone("tail", [0, 6, 4], "familier")
    if tail == "fan":
        m.cube("tail", "tail", [-2.5, 5.5, 4], [2.5, 6.5, 9], "accA", rot=[-15, 0, 0], origin=[0, 6, 4])
        m.cube("tail", "tail_tip", [-1.5, 5.5, 8], [1.5, 6.5, 11], "glow", rot=[-15, 0, 0], origin=[0, 6, 4])
    else:
        m.cube("tail", "tail", [-1, 5, 4], [1, 7, 10], "body", rot=[-10, 0, 0], origin=[0, 6, 4])
        m.cube("tail", "tail_spike", [-1.5, 5, 9], [1.5, 7.5, 11], "accA", rot=[-10, 0, 0], origin=[0, 6, 4])
    m.role("tail", "tail")
    for side, x in (("L", -1.5), ("R", 1.5)):
        name = "leg_" + side
        m.bone(name, [x, 4, 0], "familier")
        m.cube(name, name, [x - 0.5, 1, -0.5], [x + 0.5, 4, 0.5], "dark")
        m.role("legs", name)


def mushroom(m, kind="mushroom", big=False):
    m.bone("familier", [0, 0, 0])
    m.role("root", "familier")
    if kind == "mushroom":
        m.bone("h_head", [0, 5, 0], "familier")
        m.cube("h_head", "stem", [-3, 2, -3], [3, 9, 3], "body2", face_north="face")
        m.bone("crown", [0, 9, 0], "h_head")
        m.cube("crown", "cap", [-6, 9, -6], [6, 13, 6], "accA")
        m.cube("crown", "cap_top", [-4, 13, -4], [4, 15, 4], "accA")
        for i, (x, z) in enumerate(((-3, -6.25), (3, -6.25), (-6.25, 2), (0, 5.75))):
            m.cube("crown", f"spot_{i}", [x - 1, 11, z], [x + 1, 12.5, z + 0.5], "white")
        for side, x in (("L", -1.5), ("R", 1.5)):
            name = "leg_" + side
            m.bone(name, [x, 2, 0], "familier")
            m.cube(name, name, [x - 1, 0, -1], [x + 1, 2, 1], "limb")
            m.role("legs", name)
    elif big:  # kraken : grosse tête, huit tentacules épaisses
        m.bone("h_head", [0, 13, 0], "familier")
        m.cube("h_head", "bell", [-5.5, 9, -5.5], [5.5, 17, 5.5], "body", face_north="face")
        m.cube("h_head", "bell_top", [-3.5, 17, -3.5], [3.5, 19, 3.5], "accA")
        m.bone("crown", [0, 19, 0], "h_head")
        m.cube("crown", "spot", [-1, 19, -1], [1, 20, 1], "glow")
        for i in range(8):
            a = i * 3.14159 / 4
            x, z = round(4 * __import__("math").cos(a), 2), round(4 * __import__("math").sin(a), 2)
            name = f"tentacle_{i}"
            m.bone(name, [x, 9, z], "familier")
            m.cube(name, name, [x - 0.75, 1, z - 0.75], [x + 0.75, 9, z + 0.75], "accB")
            m.cube(name, name + "_tip", [x - 0.5, 0, z - 0.5], [x + 0.5, 1, z + 0.5], "glow")
            m.role("tail", name)
    else:  # méduse
        m.bone("h_head", [0, 11, 0], "familier")
        m.cube("h_head", "bell", [-4.5, 8, -4.5], [4.5, 14, 4.5], "body", face_north="face")
        m.cube("h_head", "bell_top", [-3, 14, -3], [3, 16, 3], "accA")
        m.bone("crown", [0, 16, 0], "h_head")
        m.cube("crown", "moon", [-1, 16, -1], [1, 17.5, 1], "glow")
        for i, (x, z) in enumerate(((-3, -3), (3, -3), (-3, 3), (3, 3), (0, 0))):
            name = f"tentacle_{i}"
            m.bone(name, [x, 8, z], "familier")
            m.cube(name, name, [x - 0.5, 1, z - 0.5], [x + 0.5, 8, z + 0.5], "accB")
            m.cube(name, name + "_tip", [x - 0.5, 0, z - 0.5], [x + 0.5, 1, z + 0.5], "glow")
            m.role("tail", name)
    m.role("head", "h_head")
    m.role("crown", "crown")


def golem(m, deco="crystal", size="normal", small_deco=None):
    if size == "small":  # caillou : un rocher avec un visage
        m.bone("familier", [0, 0, 0])
        m.bone("h_head", [0, 3, 0], "familier")
        m.cube("h_head", "rock", [-4, 0, -3.5], [4, 6.5, 3.5], "body", face_north="face")
        m.cube("h_head", "rock_top", [-3, 6.5, -2.5], [2, 8, 2.5], "body2")
        m.bone("crown", [0, 8, 0], "h_head")
        if small_deco:
            crown(m, small_deco, 8, "crown")
        else:
            m.cube("crown", "pebble", [1, 7.5, -1], [3, 9, 1], "accA", rot=[0, 30, 0])
            m.cube("crown", "moss", [-3, 8, -1], [0, 8.5, 2], "accB")
        for side, sx in (("L", -1), ("R", 1)):
            name = "arm_" + side
            m.bone(name, [sx * 4, 3, 0], "familier")
            x0, x1 = (-5.5, -4) if sx < 0 else (4, 5.5)
            m.cube(name, name, [x0, 1, -1], [x1, 3.5, 1], "limb")
            m.role("arms", name)
        m.role("root", "familier")
        m.role("head", "h_head")
        m.role("crown", "crown")
        return
    big = size == "big"
    k = 1.25 if big else 1.0
    m.bone("familier", [0, 5 * k, 0])
    m.cube("familier", "torso", [-4 * k, 5 * k, -2.5 * k], [4 * k, 12 * k, 2.5 * k], "body")
    m.cube("familier", "core", [-1.5 * k, 7.5 * k, -2.75 * k], [1.5 * k, 10 * k, -2.5 * k], "glow")
    m.bone("h_head", [0, 12 * k, 0], "familier")
    m.cube("h_head", "head", [-3 * k, 12 * k, -2.75 * k], [3 * k, 17 * k, 2.25 * k], "body2", face_north="face")
    m.bone("crown", [0, 17 * k, 0], "h_head")
    crown(m, deco, 17 * k, "crown")
    for side, sx in (("L", -1), ("R", 1)):
        name = "arm_" + side
        m.bone(name, [sx * 4.5 * k, 11 * k, 0], "familier")
        x0, x1 = (-7 * k, -4 * k) if sx < 0 else (4 * k, 7 * k)
        m.cube(name, name, [x0, 4 * k, -1.5 * k], [x1, 11.5 * k, 1.5 * k], "limb")
        if deco in ("crystal", "leaf"):
            m.cube(name, name + "_spike", [(x0 + x1) / 2 - 0.75, 11.5 * k, -0.75], [(x0 + x1) / 2 + 0.75, 14 * k, 0.75],
                   "accA", rot=[0, 45, 0])
        m.role("arms", name)
    for side, x in (("L", -2), ("R", 2)):
        name = "leg_" + side
        m.bone(name, [x * k, 5 * k, 0], "familier")
        m.cube(name, name, [(x - 1.5) * k, 0, -1.5 * k], [(x + 1.5) * k, 5 * k, 1.5 * k], "dark")
        m.role("legs", name)
    m.role("root", "familier")
    m.role("head", "h_head")
    m.role("crown", "crown")


def grimoire(m):
    """Livre volant : couverture avec visage, pages qui battent comme des ailes, marque-page."""
    m.bone("familier", [0, 8, 0])
    m.bone("h_head", [0, 11, 0], "familier")
    m.cube("h_head", "cover", [-4.5, 5, -2], [4.5, 16, -1], "body", face_north="face")
    m.cube("h_head", "pages", [-4, 5.5, -1], [4, 15.5, 1.5], "white")
    m.cube("h_head", "back", [-4.5, 5, 1.5], [4.5, 16, 2.5], "body")
    m.cube("h_head", "spine", [-4.75, 5, -2], [-4.5, 16, 2.5], "accA")
    m.cube("h_head", "gem", [-1, 13, -2.25], [1, 15, -2], "glow")
    m.bone("crown", [0, 16, 0], "h_head")
    m.cube("crown", "rune", [-0.5, 17, -0.5], [0.5, 18, 0.5], "glow")
    for side, sx in (("L", -1), ("R", 1)):
        name = "wing_" + side
        m.bone(name, [sx * 4.5, 12, 0], "h_head")
        x0, x1 = (-10, -4.5) if sx < 0 else (4.5, 10)
        m.cube(name, name, [x0, 7, -0.25], [x1, 15, 0.25], "white")
        m.role("wings", name)
    m.bone("tail", [1.5, 5, 0], "familier")
    m.cube("tail", "bookmark", [1, 1, -0.25], [2, 5, 0.25], "accB")
    m.role("tail", "tail")
    m.role("root", "familier")
    m.role("head", "h_head")
    m.role("crown", "crown")


def serpent(m, fins="fin"):
    m.bone("familier", [0, 8, 0])
    m.bone("h_head", [0, 11, -4], "familier")
    m.cube("h_head", "head", [-3.5, 8.5, -9], [3.5, 14, -2.5], "body", face_north="face")
    m.cube("h_head", "jaw", [-2.5, 7.5, -10], [2.5, 9.5, -6], "body2")
    m.bone("crown", [0, 14, -5], "h_head")
    for side, x in (("l", -3.5), ("r", 3.5)):
        m.cube("crown", "fin_" + side, [x - 0.5, 12, -6], [x + 0.5, 16, -2], "accA",
               rot=[-20, 0, 18 if x < 0 else -18], origin=[x, 13, -4])
    m.cube("crown", "crest", [-0.5, 14, -7], [0.5, 17, -2], "glow", rot=[-15, 0, 0], origin=[0, 14, -4])
    m.role("root", "familier")
    m.role("head", "h_head")
    m.role("crown", "crown")
    parent, z, y, size = "familier", -2.5, 11, 5.0
    for i in range(5):
        name = f"seg_{i}"
        m.bone(name, [0, y, z], parent)
        m.cube(name, name, [-size / 2, y - size / 2, z], [size / 2, y + size / 2, z + 4], "body")
        m.cube(name, name + "_fin", [-0.5, y + size / 2, z + 0.5], [0.5, y + size / 2 + 1.5, z + 3], "accA")
        m.role("tail", name)
        parent, z, y, size = name, z + 4, y - 1.2, max(2.0, size - 0.6)


# --------------------------------------------------------------------------- animations

def kf(m, channel, time, x, y, z, interp="catmullrom"):
    return {"channel": channel, "data_points": [{"x": f"{x:.3f}", "y": f"{y:.3f}", "z": f"{z:.3f}"}],
            "uuid": m.uid(f"kf/{channel}/{time}/{x}/{y}/{z}/{id(m)}/{kf.n}"), "time": round(time, 4),
            "color": -1, "interpolation": interp}


kf.n = 0


def make_animation(m, name, length, loop, tracks, uuids=None):
    """Animation Blockbench : tracks[os][canal] = [(temps, (x, y, z)), ...]. uuids[os] : uuid de l'os s'il
    existe déjà (modèle chargé d'un fichier), sinon celui de m.uid("bone/<os>")."""
    animators = {}
    for bone, channels in tracks.items():
        frames = []
        for channel, points in channels.items():
            for t, (x, y, z) in points:
                kf.n += 1
                frames.append(kf(m, channel, t, x, y, z))
        animators[(uuids or {}).get(bone) or m.uid("bone/" + bone)] = {"name": bone, "type": "bone",
                                                                         "keyframes": frames}
    return {"uuid": m.uid("anim/" + name), "name": name, "loop": loop, "override": False,
            "length": length, "snapping": 20, "selected": False, "anim_time_update": "",
            "blend_weight": "", "start_delay": "", "loop_delay": "", "animators": animators}


def build_animations(m):
    """idle / walk / happy / sleep à partir des rôles des os."""
    import math

    def anim(name, length, loop, tracks):
        return make_animation(m, name, length, loop, tracks)

    def wave(length, steps, f):
        return [(length * i / steps, f(i / steps)) for i in range(steps + 1)]

    R = m.roles
    root = R.get("root", [])
    head = R.get("head", [])
    arms = R.get("arms", [])
    wings = R.get("wings", [])
    legs = R.get("legs", [])
    tail = R.get("tail", [])
    crown_ = R.get("crown", [])

    def side(bone):
        return -1 if ("_L" in bone or bone.endswith("L") or "_l" in bone) else 1

    out = []
    # idle : flottement doux, tête qui hoche, ailes lentes, queue qui ondule
    t = {}
    for b in root:
        t[b] = {"position": wave(3, 8, lambda p: (0, 1.2 * math.sin(p * 2 * math.pi) ** 2, 0))}
    for b in head:
        t.setdefault(b, {})["rotation"] = wave(3, 4, lambda p: (-3 * math.sin(p * 2 * math.pi), 0, 0))
    for b in arms:
        s = side(b)
        t[b] = {"rotation": wave(3, 4, lambda p, s=s: (0, 0, -s * 8 * math.sin(p * 2 * math.pi)))}
    for b in wings:
        s = side(b)
        t[b] = {"rotation": wave(3, 8, lambda p, s=s: (0, 0, -s * 20 * math.sin(p * 4 * math.pi)))}
    for i, b in enumerate(tail):
        t[b] = {"rotation": wave(3, 8, lambda p, i=i: (0, 12 * math.sin(p * 2 * math.pi + i * 0.8), 0))}
    for b in crown_:
        t[b] = {"rotation": wave(3, 4, lambda p: (0, 6 * math.sin(p * 2 * math.pi), 0))}
    out.append(anim("idle", 3.0, "loop", t))

    # walk : rebonds rapides, pattes alternées, ailes rapides
    t = {}
    for b in root:
        t[b] = {"position": wave(1, 8, lambda p: (0, 1.0 * abs(math.sin(p * 2 * math.pi)), 0)),
                "rotation": [(0, (6, 0, 0)), (1, (6, 0, 0))]}
    for b in arms:
        s = side(b)
        t[b] = {"rotation": wave(1, 4, lambda p, s=s: (25 * s * math.sin(p * 2 * math.pi), 0, 0))}
    for i, b in enumerate(legs):
        ph = 0 if i % 2 == 0 else math.pi
        if len(legs) == 4 and i in (1, 2):
            ph = math.pi
        elif len(legs) == 4:
            ph = 0
        t[b] = {"rotation": wave(1, 4, lambda p, ph=ph: (25 * math.sin(p * 2 * math.pi + ph), 0, 0))}
    for b in wings:
        s = side(b)
        t[b] = {"rotation": wave(1, 8, lambda p, s=s: (0, 0, -s * 35 * math.sin(p * 4 * math.pi)))}
    for i, b in enumerate(tail):
        t[b] = {"rotation": wave(1, 8, lambda p, i=i: (0, 18 * math.sin(p * 2 * math.pi + i * 0.8), 0))}
    out.append(anim("walk", 1.0, "loop", t))

    # happy : saut, tour sur lui-même, bras levés
    t = {}
    for b in root:
        t[b] = {"position": [(0, (0, 0, 0)), (0.3, (0, 5, 0)), (0.6, (0, 0, 0)), (0.8, (0, 2, 0)), (1.2, (0, 0, 0))],
                "rotation": [(0, (0, 0, 0)), (0.6, (0, 360, 0)), (1.2, (0, 360, 0))]}
    for b in arms:
        s = side(b)
        t[b] = {"rotation": [(0, (0, 0, 0)), (0.3, (0, 0, -s * 70)), (0.9, (0, 0, -s * 70)), (1.2, (0, 0, 0))]}
    for b in wings:
        s = side(b)
        t[b] = {"rotation": wave(1.2, 12, lambda p, s=s: (0, 0, -s * 40 * math.sin(p * 6 * math.pi)))}
    for b in crown_:
        t[b] = {"rotation": [(0, (0, 0, 0)), (0.6, (0, 180, 0)), (1.2, (0, 360, 0))]}
    out.append(anim("happy", 1.2, "once", t))

    # sleep : posé plus bas, respiration lente, tête baissée, ailes repliées
    t = {}
    for b in root:
        t[b] = {"position": wave(4, 4, lambda p: (0, -1.5 + 0.4 * math.sin(p * 2 * math.pi), 0))}
    for b in head:
        t.setdefault(b, {})["rotation"] = wave(4, 4, lambda p: (14 + 2 * math.sin(p * 2 * math.pi), 0, 0))
    for b in arms:
        s = side(b)
        t[b] = {"rotation": [(0, (0, 0, s * 10)), (4, (0, 0, s * 10))]}
    for b in wings:
        s = side(b)
        t[b] = {"rotation": [(0, (0, 0, s * 25)), (4, (0, 0, s * 25))]}
    for i, b in enumerate(tail):
        t[b] = {"rotation": wave(4, 4, lambda p, i=i: (0, 5 * math.sin(p * 2 * math.pi + i), 0))}
    for b in crown_:
        t[b] = {"rotation": [(0, (10, 0, 0)), (4, (10, 0, 0))]}
    out.append(anim("sleep", 4.0, "loop", t))
    # animations sur mesure d'un familier (familiers_design : a.anim_overrides[nom] = (durée, boucle, pistes)),
    # à la place des animations génériques du même nom
    for name, (length, loop, tracks) in getattr(m, "anim_overrides", {}).items():
        out = [a for a in out if a["name"] != name] + [anim(name, length, loop, tracks)]
    return out


def render_front(elements, texture_px, width, height, view="north"):
    """Rendu orthographique pour l'illustration des cartes : de face (view « north », vue depuis le nord) ou de
    profil (« west », vue depuis l'ouest, tête à gauche : pour un familier tout en longueur). Ignore les
    rotations et les éléments sans texture (boîte de collision)."""
    side = view == "west"
    ax = 2 if side else 0          # axe horizontal à l'écran
    depth = 0 if side else 2       # axe de profondeur
    els = [e for e in elements if e["faces"][view].get("texture") is not None]
    hs = [v for e in els for v in (e["from"][ax], e["to"][ax])]
    ys = [v for e in els for v in (e["from"][1], e["to"][1])]
    minh, maxh, miny, maxy = min(hs), max(hs), min(ys), max(ys)
    scale = min((width - 1) / (maxh - minh), (height - 1) / (maxy - miny))
    ch = (minh + maxh) / 2
    out = [[None] * width for _ in range(height)]
    order = sorted(els, key=lambda e: e["from"][depth])
    for py in range(height):
        for px_ in range(width):
            # de face : gauche = +x ; de profil : gauche = -z (la tête, au nord)
            off = (px_ + 0.5 - width / 2) / scale
            h = ch + off if side else ch - off
            y = maxy - (py + 0.5 - (height - (maxy - miny) * scale) / 2) / scale
            for e in order:
                (h0, y0), (h1, y1) = (e["from"][ax], e["from"][1]), (e["to"][ax], e["to"][1])
                if h0 <= h < h1 and y0 <= y < y1:
                    u0, v0, u1, v1 = e["faces"][view]["uv"]
                    w, hh = max(1, round(u1 - u0)), max(1, round(v1 - v0))
                    frac = (h - h0) / (h1 - h0) if side else (h1 - h) / (h1 - h0)
                    tu = int(u0) + min(w - 1, int(frac * w))
                    tv = int(v0) + min(hh - 1, int((y1 - y) / (y1 - y0) * hh))
                    c = texture_px[tv][tu]
                    if c[3]:
                        out[py][px_] = c
                        break
    return out


def save_bbmodel(path, data):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
