"""Base commune des personnages voxel générés par script (orchestre, PNJ) : modèle Blockbench à os, texture
peinte face par face (1 texel par unité, aucune tuile partagée), animations et aperçus.

Conventions : le personnage regarde vers -Z, sa droite est en +X, origine au centre de la base des pieds.
Les os ont une rotation de repos nulle, sauf les os « *_pose » (bras), jamais animés en rotation. Les
rotations fixes sont portées par les cubes. Les animations sont décrites en convention « naturelle » (celle
des rotations de l'outliner) et converties à l'export (Blockbench inverse X et Y en rotation, X en position).
"""
import base64
import math
import os
import random
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "influcard"))
import png_util  # noqa: E402

T = (0, 0, 0, 0)
FPS = 20


def rgb(h):
    return (int(h[1:3], 16), int(h[3:5], 16), int(h[5:7], 16), 255)


def pal(*hexes):
    return [rgb(h) for h in hexes]


def mix(a, b, t):
    return tuple(int(round(x + (y - x) * t)) for x, y in zip(a[:3], b[:3])) + (255,)


def ramp(h, dark=0.72, light=1.22, warm=True):
    """Palette ombre / base / lumière / éclat à partir d'une seule couleur (ombre refroidie, lumière réchauffée)."""
    c = rgb(h)

    def k(f, tint):
        v = [min(255, x * f) for x in c[:3]]
        return mix(tuple(v) + (255,), tint, 0.12)
    cool, hot = (40, 36, 60, 255), (255, 236, 200, 255)
    return [k(dark, cool), c, k(light, hot), k(light * 1.13, hot)]


def key(p, **extra):
    k = {"d": p[0], "m": p[1], "l": p[2], "h": p[3] if len(p) > 3 else p[2], " ": T}
    k.update(extra)
    return k


def stamp(g, rows, k, x0=0, y0=0):
    """Pose un motif ('.' = ne pas toucher, ' ' = transparent) ; ce qui déborde de la face est ignoré."""
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != "." and 0 <= y0 + y < len(g) and 0 <= x0 + x < len(g[0]):
                g[y0 + y][x0 + x] = k[ch]


def fill(face, w, h, p, rnd, top=1, bot=1, noise=0.07):
    """Remplissage peint : lumière en haut, ombre en bas, côté gauche du personnage (-X) et dos plus sombres."""
    d, m, l = p[:3]
    hi = p[3] if len(p) > 3 else l
    if face == "down":
        return [[d] * w for _ in range(h)]
    if face == "up":
        g = [[l] * w for _ in range(h)]
    else:
        g = [[m] * w for _ in range(h)]
        dark = face in ("west", "south")
        b = bot + (1 if dark and h >= 6 else 0)
        for y in range(h):
            for x in range(w):
                if y < top:
                    g[y][x] = m if dark else l
                if y >= h - b:
                    g[y][x] = d
        if face == "north" and w >= 4 and h >= 4:
            for y in range(h):
                if y >= h // 2:
                    g[y][w - 1] = d
                elif top and y <= h // 3:
                    g[y][0] = l
    if w * h < 8:
        return g
    for _ in range(max(1, round(w * h * noise / 1.5))):
        x, y = rnd.randrange(w), rnd.randrange(h)
        cur = g[y][x]
        if cur == m:
            new = l if y < h / 2 else d
        elif cur == l:
            new = hi if rnd.random() < 0.35 else m
        else:
            new = m if rnd.random() < 0.4 else d
        g[y][x] = new
        if x + 1 < w and g[y][x + 1] == cur and rnd.random() < 0.5:
            g[y][x + 1] = new
    return g


# ------------------------------------------------------------------------------------------------- maths
def rot_matrix(rx, ry, rz):
    x, y, z = (math.radians(v) for v in (rx, ry, rz))
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    return mul([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]],
               mul([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]], [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]))


def mul(A, B):
    return [[sum(A[i][k] * B[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def apply(M, v):
    return [sum(M[i][k] * v[k] for k in range(3)) for i in range(3)]


def tr(M):
    return [[M[j][i] for j in range(3)] for i in range(3)]


def euler(R):
    """Matrice -> angles (x, y, z) de rot_matrix."""
    ry = math.asin(max(-1, min(1, -R[2][0])))
    return (math.degrees(math.atan2(R[2][1], R[2][2])), math.degrees(ry), math.degrees(math.atan2(R[1][0], R[0][0])))


def aim(pivot, target):
    """Rotation [x, 0, z] d'un membre pendant (vers -Y) pour qu'il pointe du pivot vers la cible."""
    v = [t - p for p, t in zip(pivot, target)]
    n = math.sqrt(sum(c * c for c in v)) or 1
    v = [c / n for c in v]
    return [math.degrees(math.asin(max(-1, min(1, -v[2])))), 0, math.degrees(math.atan2(v[0], -v[1]))]


def dist(a, b):
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


def sw(p, k=1, ph=0):
    return math.sin(2 * math.pi * (k * p + ph))


def bump(p, a, b, c):
    """0 hors de [a, c], montée douce jusqu'à 1 en b, redescente douce (p pris modulo 1)."""
    p = p % 1
    if a < 0 and p > c:
        p -= 1
    if p <= a or p >= c:
        return 0.0
    f = (p - a) / (b - a) if p < b else (c - p) / (c - b)
    return 0.5 - 0.5 * math.cos(math.pi * f)


# ------------------------------------------------------------------------------------------------ modèle
class Model:
    def __init__(self, name):
        self.name = name
        self.elements, self.bones, self.faces, self.anims, self.arm_len = [], {}, [], [], {}
        self.bone("root")

    def uid(self, s):
        return str(uuid.uuid5(uuid.NAMESPACE_DNS, self.name + "/" + s))

    def bone(self, name, origin=(0, 0, 0), parent="root", rotation=(0, 0, 0), cube_rot=None, cube_origin=None):
        """cube_rot : rotation fixe donnée à chaque cube de l'os (autour de cube_origin, défaut : le pivot)."""
        self.bones[name] = {"origin": list(origin), "rotation": list(rotation), "children": [],
                            "parent": None if name == "root" else parent,
                            "cube": (cube_rot, list(cube_origin or origin)) if cube_rot else None}
        if name != "root":
            self.bones[parent]["children"].append(name)

    def box(self, bone, name, frm, to, p, paint=None, rot=None, origin=None, **opts):
        """Cube peint. paint : fonction(face, grille) ou {face: fonction(grille)} pour les retouches."""
        frm, to = [min(a, b) for a, b in zip(frm, to)], [max(a, b) for a, b in zip(frm, to)]
        if rot is None and self.bones[bone].get("cube"):
            rot, origin = self.bones[bone]["cube"]
        size = [b - a for a, b in zip(frm, to)]
        dims = {"north": (0, 1), "south": (0, 1), "east": (2, 1), "west": (2, 1), "up": (0, 2), "down": (0, 2)}
        faces = {}
        for f, (a, b) in dims.items():
            w, h = max(1, int(round(size[a]))), max(1, int(round(size[b])))
            g = fill(f, w, h, p, random.Random(name + f), **opts)
            if callable(paint):
                paint(f, g)
            elif paint and f in paint:
                paint[f](g)
            faces[f] = len(self.faces)
            self.faces.append(g)
        el = {"name": name, "box_uv": False, "rescale": False, "locked": False, "render_order": "default",
              "allow_mirror_modeling": True, "from": frm, "to": to, "autouv": 0, "color": 0,
              "origin": list(origin) if origin else [(a + b) / 2 for a, b in zip(frm, to)], "faces": faces,
              "type": "cube", "uuid": self.uid("el/" + name)}
        if rot and any(rot):
            el["rotation"] = [round(v, 2) for v in rot]
        self.elements.append(el)
        self.bones[bone]["children"].append(el["uuid"])
        el["_bone"] = bone
        return el

    # ------------------------------------------------------------------ texture
    def pack(self):
        order = sorted(range(len(self.faces)), key=lambda i: (-len(self.faces[i]), -len(self.faces[i][0])))
        for size in (64, 128, 256):
            pos, x, y, row_h, ok = {}, 0, 0, 0, True
            for i in order:
                w, h = len(self.faces[i][0]), len(self.faces[i])
                if x + w > size:
                    x, y, row_h = 0, y + row_h, 0
                if y + h > size:
                    ok = False
                    break
                pos[i] = (x, y)
                x, row_h = x + w, max(row_h, h)
            if ok:
                break
        px = [[T] * size for _ in range(size)]
        for i, (u, v) in pos.items():
            for yy, row in enumerate(self.faces[i]):
                px[v + yy][u:u + len(row)] = row
        for el in self.elements:
            for f, i in list(el["faces"].items()):
                u, v = pos[i]
                el["faces"][f] = {"uv": [u, v, u + len(self.faces[i][0]), v + len(self.faces[i])], "texture": 0}
        self.size, self.px = size, px
        return size, px

    # ------------------------------------------------------------------ animations
    def anim(self, name, length, loop="loop"):
        a = Anim(self, name, length, loop)
        self.anims.append(a)
        return a

    def freeze(self, name, source, p=0.0, length=2.0):
        """Animation immobile : la pose de l'animation source à la phase p (instrument tenu, sans jouer)."""
        a = self.anim(name, length)
        for bone, chans in source.tracks.items():
            for ch, f in chans.items():
                v = tuple(f(p))
                a.set(bone, ch, lambda _p, v=v: v)
        return a

    def transform(self, bone, anim=None, p=0.0):
        """(B, c) : monde = B x + c pour un point x exprimé en coordonnées de repos, dans l'os donné."""
        b = self.bones[bone]
        B, c = ([[1, 0, 0], [0, 1, 0], [0, 0, 1]], [0, 0, 0]) if b["parent"] is None \
            else self.transform(b["parent"], anim, p)
        o = b["origin"]
        r = anim.value(bone, "rotation", p) if anim else (0, 0, 0)
        t = anim.value(bone, "position", p) if anim else (0, 0, 0)
        s = anim.value(bone, "scale", p) if anim else (1, 1, 1)
        R = mul(rot_matrix(*r), rot_matrix(*b["rotation"]))
        RS = [[R[i][j] * s[j] for j in range(3)] for i in range(3)]
        Bn = mul(B, RS)
        shift = apply(B, [o[i] + t[i] for i in range(3)])
        return Bn, [shift[i] + c[i] - apply(Bn, o)[i] for i in range(3)]

    def point(self, bone, x, anim=None, p=0.0):
        B, c = self.transform(bone, anim, p)
        return [apply(B, x)[i] + c[i] for i in range(3)]

    def posed(self, anim=None, p=0.0):
        out = []
        cache = {}
        for e in self.elements:
            if e["_bone"] not in cache:
                cache[e["_bone"]] = self.transform(e["_bone"], anim, p)
            B, c = cache[e["_bone"]]
            e2 = dict(e)
            M = rot_matrix(*(e.get("rotation") or [0, 0, 0]))
            eo = e["origin"]
            e2["_M"] = mul(B, M)
            e2["_q"] = [apply(B, eo)[i] + c[i] for i in range(3)]
            out.append(e2)
        return out

    # ------------------------------------------------------------------ export
    def bbmodel(self):
        size, px = self.size, self.px

        def node(n):
            b = self.bones[n]
            return {"name": n, "origin": b["origin"], "rotation": [round(v, 2) for v in b["rotation"]], "color": 0,
                    "uuid": self.uid("bone/" + n), "export": True, "mirror_uv": False, "isOpen": n == "root",
                    "locked": False, "visibility": True, "autouv": 0,
                    "children": [node(c) if c in self.bones else c for c in b["children"]]}
        els = [{k: v for k, v in e.items() if k != "_bone"} for e in self.elements]
        src = "data:image/png;base64," + base64.b64encode(png_util.encode(size, size, px)).decode()
        tex = {"path": "", "name": self.name + ".png", "folder": "", "namespace": "", "id": "0", "group": "",
               "width": size, "height": size, "uv_width": size, "uv_height": size, "particle": False,
               "use_as_default": False, "layers_enabled": False, "sync_to_project": "", "render_mode": "default",
               "render_sides": "auto", "pbr_channel": "color", "frame_time": 1, "frame_order_type": "loop",
               "frame_order": "", "frame_interpolate": False, "visible": True, "internal": True, "saved": False,
               "uuid": self.uid("tex"), "source": src}
        return {"meta": {"format_version": "4.10", "model_format": "free", "box_uv": False}, "name": self.name,
                "model_identifier": self.name, "visible_box": [3, 3, 1], "variable_placeholders": "",
                "variable_placeholder_buttons": [], "timeline_setups": [], "unhandled_root_fields": {},
                "resolution": {"width": size, "height": size}, "elements": els, "outliner": [node("root")],
                "textures": [tex], "animations": [a.export() for a in self.anims]}

    def write(self, out):
        import json
        os.makedirs(out, exist_ok=True)
        self.pack()
        json.dump(self.bbmodel(), open(os.path.join(out, self.name + ".bbmodel"), "w"))
        png_util.write(os.path.join(out, self.name + ".png"), self.size, self.size, self.px)

    # ------------------------------------------------------------------ aperçus
    def sheet(self, path, views=((0, 4), (35, 18), (90, 6), (215, 18), (325, 18)), size=340, anim=None, p=0.0):
        import apercu
        els = self.posed(anim, p)
        imgs = [apercu.render(els, self.px, yaw, pitch, size=size) for yaw, pitch in views]
        png_util.write(path, size * len(imgs), size, [sum((v[y] for v in imgs), []) for y in range(size)])

    def strip(self, path, anim, phases=(0, 0.2, 0.4, 0.6, 0.8), view=(30, 14), size=300):
        """Bande d'images d'une animation, même cadre pour toutes."""
        import apercu
        pts = []
        for p in phases:
            for e in self.posed(anim, p):
                for cx in (e["from"][0], e["to"][0]):
                    for cy in (e["from"][1], e["to"][1]):
                        for cz in (e["from"][2], e["to"][2]):
                            q = apply(e["_M"], [cx - e["origin"][0], cy - e["origin"][1], cz - e["origin"][2]])
                            pts.append([q[i] + e["_q"][i] for i in range(3)])
        imgs = [apercu.render(self.posed(anim, p), self.px, view[0], view[1], size=size, frame_points=pts)
                for p in phases]
        png_util.write(path, size * len(imgs), size, [sum((v[y] for v in imgs), []) for y in range(size)])


class Anim:
    """Animation : pistes en fonctions de la phase p (0..1), échantillonnées à 20 images par seconde."""

    def __init__(self, model, name, length, loop):
        self.m, self.name, self.length, self.loop = model, name, length, loop
        self.tracks = {}

    def set(self, bone, channel, f):
        assert bone in self.m.bones, bone
        self.tracks.setdefault(bone, {})[channel] = f
        return self

    def value(self, bone, channel, p):
        f = self.tracks.get(bone, {}).get(channel)
        return f(p) if f else ((1, 1, 1) if channel == "scale" else (0, 0, 0))

    def at(self, bone, x, p):
        return self.m.point(bone, x, self, p)

    def arm(self, side, target, stretch=(0.85, 1.6)):
        """Bras qui suit une cible monde target(p) : rotation de l'os du bras, étirement de l'os de pose."""
        m = self.m
        bone, pose = "bras_" + side, "bras_" + side + "_pose"
        piv = m.bones[bone]["origin"]
        rest = rot_matrix(*m.bones[pose]["rotation"])
        length = m.arm_len[side]
        parent = m.bones[bone]["parent"]

        def local(p):
            B, c = m.transform(parent, self, p)
            w = target(p)
            return apply(tr(B), [w[i] - c[i] for i in range(3)])

        def rot(p):
            return euler(mul(rot_matrix(*aim(piv, local(p))), tr(rest)))

        def scale(p):
            return (1, max(stretch[0], min(stretch[1], dist(piv, local(p)) / length)), 1)
        self.set(bone, "rotation", rot)
        if stretch:
            self.set(pose, "scale", scale)
        return self

    def export(self):
        n = max(1, int(round(self.length * FPS)))
        conv = {"rotation": lambda v: (-v[0], -v[1], v[2]), "position": lambda v: (-v[0], v[1], v[2]),
                "scale": lambda v: v}
        animators = {}
        for bone, chans in self.tracks.items():
            frames = []
            for ch, f in chans.items():
                prev = None
                for i in range(n + 1):
                    v = list(conv[ch](f(i / n)))
                    if ch == "rotation" and prev:   # pas de saut de 360° entre deux clés
                        v = [x + 360 * round((q - x) / 360) for x, q in zip(v, prev)]
                    prev = v
                    frames.append({"channel": ch, "data_points": [{"x": round(v[0], 3), "y": round(v[1], 3),
                                                                    "z": round(v[2], 3)}],
                                   "uuid": self.m.uid(f"kf/{self.name}/{bone}/{ch}/{i}"),
                                   "time": round(i * self.length / n, 4), "color": -1, "interpolation": "linear"})
                same = [k for k in frames if k["channel"] == ch]
                if all(k["data_points"] == same[0]["data_points"] for k in same):   # piste constante : 2 clés
                    frames = [k for k in frames if k["channel"] != ch] + [same[0], same[-1]]
            animators[self.m.uid("bone/" + bone)] = {"name": bone, "type": "bone", "keyframes": frames}
        return {"uuid": self.m.uid("anim/" + self.name), "name": self.name, "loop": self.loop, "override": False,
                "length": self.length, "snapping": FPS, "selected": False, "anim_time_update": "",
                "blend_weight": "", "start_delay": "", "loop_delay": "", "animators": animators}
