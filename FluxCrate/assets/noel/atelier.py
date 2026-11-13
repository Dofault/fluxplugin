"""Small workshop for hand-designed item models: elements whose faces are painted by functions of their position in
the model (textures follow from one face to the next), packed into one atlas, written as a Minecraft item model and as
a Blockbench project, and rendered for review (perspective, translucency, anti-aliasing, particles).

Conventions (Minecraft): 16 units = 1 block, y up, the front of a model faces north (-z). Free element rotations
(Minecraft 26.x) on one axis at a time. Corners of a face in UV order: top-left, top-right, bottom-right, bottom-left.
"""
import base64
import io
import json
import math
import uuid

import numpy as np
from PIL import Image

FACES = ("north", "south", "west", "east", "up", "down")
NORMAL = {"up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1), "south": (0, 0, 1),
          "east": (1, 0, 0), "west": (-1, 0, 0)}


def hexa(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], float)


def mix(a, b, t):
    """Blend of two colours (arrays), t a number or an array of the shape of the face."""
    t = np.asarray(t, float)
    return a * (1 - t[..., None]) + b * t[..., None] if t.ndim else a * (1 - t) + b * t


def ramp(stops, t):
    """Colour of a gradient of hex stops at t in [0, 1] (array)."""
    cols = [hexa(s) for s in stops]
    t = np.clip(np.asarray(t, float), 0, 1) * (len(cols) - 1)
    i = np.minimum(np.floor(t).astype(int), len(cols) - 2)
    f = t - i
    a = np.stack(cols)[i]
    b = np.stack(cols)[i + 1]
    return a * (1 - f[..., None]) + b * f[..., None]


def hash3(x, y, z, seed=0):
    """Deterministic white noise in [0, 1) of integer lattice points."""
    n = (np.asarray(x, np.int64) * 73856093) ^ (np.asarray(y, np.int64) * 19349663) ^ \
        (np.asarray(z, np.int64) * 83492791) ^ (seed * 2654435761)
    n = (n ^ (n >> 13)) * 1274126177
    n = n ^ (n >> 16)
    return (n & 0xFFFF) / 65536.0


def smooth_noise(x, y, z, scale, seed=0):
    """Value noise (trilinear) of a world position, features of `scale` units."""
    x, y, z = np.asarray(x) / scale, np.asarray(y) / scale, np.asarray(z) / scale
    xi, yi, zi = np.floor(x), np.floor(y), np.floor(z)
    fx, fy, fz = x - xi, y - yi, z - zi
    fx, fy, fz = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy), fz * fz * (3 - 2 * fz)
    out = 0
    for dx in (0, 1):
        for dy in (0, 1):
            for dz in (0, 1):
                w = (fx if dx else 1 - fx) * (fy if dy else 1 - fy) * (fz if dz else 1 - fz)
                out = out + w * hash3(xi + dx, yi + dy, zi + dz, seed)
    return out


# ---------------------------------------------------------------------------------------------------- elements

class Ctx:
    """What a painter knows of the face it paints: world position of every texel centre (X, Y, Z, in the element's
    own frame, before its rotation), position across the face (u left to right, v top to bottom, in [0, 1]), size in
    texels (W, H), texel indices (i column, j row), face name and element."""

    def __init__(self, el, face, W, H):
        self.el, self.face, self.W, self.H = el, face, W, H
        c = np.array(corners(face, el.frm, el.to), float)
        i, j = np.meshgrid(np.arange(W), np.arange(H))
        self.i, self.j = i, j
        self.u, self.v = (i + 0.5) / W, (j + 0.5) / H
        p = c[0] + self.u[..., None] * (c[1] - c[0]) + self.v[..., None] * (c[3] - c[0])
        self.X, self.Y, self.Z = p[..., 0], p[..., 1], p[..., 2]
        # the same point once the element is rotated (patterns continuous over rotated elements)
        if el.rot:
            R, o = rot3(el.rot[0], el.rot[1]), np.array(el.rot[2], float)
            w = (p - o) @ R.T + o
        else:
            w = p
        self.WX, self.WY, self.WZ = w[..., 0], w[..., 1], w[..., 2]
        self.normal = NORMAL[face]

    @property
    def shape(self):
        return (self.H, self.W)

    def edge(self):
        """Distance in texels to the nearest edge of the face."""
        return np.minimum(np.minimum(self.i, self.W - 1 - self.i), np.minimum(self.j, self.H - 1 - self.j))


def corners(face, f, t):
    (x1, y1, z1), (x2, y2, z2) = f, t
    return {
        "north": [(x2, y2, z1), (x1, y2, z1), (x1, y1, z1), (x2, y1, z1)],
        "south": [(x1, y2, z2), (x2, y2, z2), (x2, y1, z2), (x1, y1, z2)],
        "west": [(x1, y2, z1), (x1, y2, z2), (x1, y1, z2), (x1, y1, z1)],
        "east": [(x2, y2, z2), (x2, y2, z1), (x2, y1, z1), (x2, y1, z2)],
        "up": [(x1, y2, z1), (x2, y2, z1), (x2, y2, z2), (x1, y2, z2)],
        "down": [(x1, y1, z2), (x2, y1, z2), (x2, y1, z1), (x1, y1, z1)],
    }[face]


def face_size(face, f, t):
    dx, dy, dz = (t[k] - f[k] for k in range(3))
    return {"north": (dx, dy), "south": (dx, dy), "west": (dz, dy), "east": (dz, dy),
            "up": (dx, dz), "down": (dx, dz)}[face]


class El:
    """A box of the model.

    mat      painter (Ctx -> RGBA array) or {face: painter}; a face mapped to None is not emitted
    faces    faces emitted (all six by default; flat elements emit the two of their plane)
    rot      (axis, degrees, origin) rotation of the element
    tex      atlas the faces are painted in; or ("fixed", key, uv) to map every face on a fixed texture
    density  texels per unit
    """

    def __init__(self, name, frm, to, mat, faces=None, rot=None, shade=True, tex="main", density=2.0, group=None):
        self.name, self.frm, self.to = name, [float(v) for v in frm], [float(v) for v in to]
        self.mat, self.rot, self.shade, self.tex, self.density, self.group = mat, rot, shade, tex, density, group
        if faces is None:
            flat = [k for k in range(3) if abs(self.to[k] - self.frm[k]) < 1e-9]
            faces = {0: ("west", "east"), 1: ("up", "down"), 2: ("north", "south")}[flat[0]] if flat else FACES
        self.faces = [f for f in faces if self.painter(f) is not None or isinstance(tex, tuple)]
        self.uv = {}

    def painter(self, face):
        return self.mat.get(face) if isinstance(self.mat, dict) else self.mat


def moved(elements, dx=0.0, dy=0.0, dz=0.0):
    """Copies of elements translated (rotation origins too)."""
    out = []
    for e in elements:
        n = El.__new__(El)
        n.__dict__.update(e.__dict__)
        n.frm = [e.frm[0] + dx, e.frm[1] + dy, e.frm[2] + dz]
        n.to = [e.to[0] + dx, e.to[1] + dy, e.to[2] + dz]
        if e.rot:
            a, d, o = e.rot
            n.rot = (a, d, [o[0] + dx, o[1] + dy, o[2] + dz])
        n.uv = dict(e.uv)
        out.append(n)
    return out


class Atlas:
    """Faces of elements painted and packed in one texture (shelf packing, square power of two)."""

    def __init__(self, key, min_size=32):
        self.key, self.min_size, self.image, self.size = key, min_size, None, 0

    def build(self, elements):
        jobs = []
        for e in elements:
            if e.tex != self.key:
                continue
            for f in e.faces:
                w, h = face_size(f, e.frm, e.to)
                W, H = max(1, int(round(w * e.density))), max(1, int(round(h * e.density)))
                jobs.append((e, f, W, H))
        size = self.min_size
        while True:
            placed = self._pack(jobs, size)
            if placed is not None:
                break
            size *= 2
        self.jobs, self.placed, self.size = jobs, placed, size
        for (e, f, W, H), (x0, y0) in zip(jobs, placed):
            e.uv[f] = [x0 * 16 / size, y0 * 16 / size, (x0 + W) * 16 / size, (y0 + H) * 16 / size]
        self.image = self.paint()
        return self

    def paint(self):
        """Paints every face (again: painters may read a global state, such as the frame of an animation)."""
        img = np.zeros((self.size, self.size, 4))
        for (e, f, W, H), (x0, y0) in zip(self.jobs, self.placed):
            img[y0:y0 + H, x0:x0 + W] = np.asarray(e.painter(f)(Ctx(e, f, W, H)), float)
        return np.clip(img, 0, 255).astype(np.uint8)

    @staticmethod
    def _pack(jobs, size):
        order = sorted(range(len(jobs)), key=lambda k: -jobs[k][3])
        out = [None] * len(jobs)
        x = y = row = 0
        for k in order:
            W, H = jobs[k][2], jobs[k][3]
            if W > size:
                return None
            if x + W > size:
                x, y, row = 0, y + row, 0
            if y + H > size:
                return None
            out[k] = (x, y)
            x, row = x + W, max(row, H)
        return out


def fixed_uv(elements):
    for e in elements:
        if isinstance(e.tex, tuple):
            for f in e.faces:
                e.uv[f] = list(e.tex[2])


# ---------------------------------------------------------------------------------------------------- export

def r3(v):
    return [round(float(x), 4) for x in v]


def model_json(elements, textures, display=None):
    """Minecraft item model. textures: {atlas key: resource location}."""
    keys = list(textures)
    out = {"textures": {str(k): textures[key] for k, key in enumerate(keys)}, "elements": []}
    out["textures"]["particle"] = textures[keys[0]]
    for e in elements:
        tkey = e.tex[1] if isinstance(e.tex, tuple) else e.tex
        o = {"name": e.name, "from": r3(e.frm), "to": r3(e.to), "faces": {}}
        if e.rot:
            a, d, origin = e.rot
            o["rotation"] = {"origin": r3(origin), "x": d if a == "x" else 0, "y": d if a == "y" else 0,
                             "z": d if a == "z" else 0}
        if not e.shade:
            o["shade"] = False
        for f in e.faces:
            o["faces"][f] = {"uv": r3(e.uv[f]), "texture": "#" + str(keys.index(tkey))}
        out["elements"].append(o)
    if display:
        out["display"] = display
    return out


def png_bytes(arr):
    b = io.BytesIO()
    Image.fromarray(arr, "RGBA").save(b, "PNG")
    return b.getvalue()


def bbmodel(name, groups, textures, resolution=None, display=None):
    """Blockbench project (format « free », like the statues of the server): groups = [(name, origin, elements)],
    textures = [(key, file name, RGBA array, frame time or None)]."""
    tex_index = {key: k for k, (key, _, _, _) in enumerate(textures)}
    elements, outliner = [], []
    for gname, origin, els in groups:
        children = []
        for e in els:
            u = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{name}/{gname}/{e.name}/{e.frm}/{e.to}"))
            tkey = e.tex[1] if isinstance(e.tex, tuple) else e.tex
            arr = textures[tex_index[tkey]][2]
            th, tw = arr.shape[:2]
            frame_h = tw if th > tw else th
            faces = {}
            for f in FACES:
                if f in e.faces:
                    u0, v0, u1, v1 = e.uv[f]
                    faces[f] = {"uv": [u0 * tw / 16, v0 * frame_h / 16, u1 * tw / 16, v1 * frame_h / 16],
                                "texture": tex_index[tkey]}
                else:
                    faces[f] = {"uv": [0, 0, 0, 0], "texture": None}
            rot = [0, 0, 0]
            org = [(e.frm[k] + e.to[k]) / 2 for k in range(3)]
            if e.rot:
                a, d, o = e.rot
                rot["xyz".index(a)] = d
                org = list(o)
            elements.append({"name": e.name, "box_uv": False, "rescale": False, "locked": False,
                             "render_order": "default", "allow_mirror_modeling": True, "from": r3(e.frm),
                             "to": r3(e.to), "autouv": 0, "color": 0, "shade": e.shade, "origin": r3(org),
                             "rotation": rot, "faces": faces, "type": "cube", "uuid": u})
            children.append(u)
        outliner.append({"name": gname, "origin": r3(origin), "color": 0,
                         "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{name}/{gname}")), "export": True,
                         "mirror_uv": False, "isOpen": False, "locked": False, "visibility": True, "autouv": 0,
                         "children": children})
    tex_out = []
    for k, (key, fname, arr, frametime) in enumerate(textures):
        th, tw = arr.shape[:2]
        frame_h = tw if th > tw else th
        tex_out.append({"path": "", "name": fname, "folder": "", "namespace": "", "id": str(k), "group": "",
                        "width": tw, "height": th, "uv_width": tw, "uv_height": frame_h, "particle": k == 0,
                        "use_as_default": False, "layers_enabled": False, "sync_to_project": "",
                        "render_mode": "default", "render_sides": "auto", "pbr_channel": "color",
                        "frame_time": frametime or 1, "frame_order_type": "loop", "frame_order": "",
                        "frame_interpolate": bool(frametime), "visible": True, "internal": True, "saved": False,
                        "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{name}/tex/{fname}")),
                        "source": "data:image/png;base64," + base64.b64encode(png_bytes(arr)).decode()})
    first = textures[0][2]
    out = {"meta": {"format_version": "4.10", "model_format": "free", "box_uv": False}, "name": name,
           "model_identifier": "", "visible_box": [1, 1, 0], "variable_placeholders": "",
           "variable_placeholder_buttons": [], "timeline_setups": [], "unhandled_root_fields": {},
           "resolution": resolution or {"width": first.shape[1], "height": first.shape[1]},
           "elements": elements, "outliner": outliner, "textures": tex_out, "animations": []}
    if display:
        out["display"] = display
    return out


# ---------------------------------------------------------------------------------------------------- render

def rot3(axis, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return {"x": np.array([[1, 0, 0], [0, c, -s], [0, s, c]]),
            "y": np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]]),
            "z": np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])}[axis]


def affine(R=None, t=(0, 0, 0)):
    M = np.eye(4)
    if R is not None:
        M[:3, :3] = R
    M[:3, 3] = t
    return M


def about(R, pivot):
    """Rotation R around a pivot point, as a 4x4 matrix."""
    p = np.asarray(pivot, float)
    return affine(R, p - R @ p)


LIGHTS = [np.array(v) / np.linalg.norm(v) for v in ((0.2, 1.0, -0.7), (-0.2, 1.0, 0.7))]


def item_light(n):
    """Lighting of an item in the world (two directional lights of entities, ambient 0.4)."""
    return min(1.0, 0.4 + 0.6 * sum(max(0.0, float(n @ L)) for L in LIGHTS))


class Renderer:
    """Perspective software renderer of item model elements: opaque and cutout texels with a depth buffer, then
    translucent faces back to front; supersampled. Coordinates in model units."""

    def __init__(self, size=(900, 900), ss=3, eye=(30, 22, -26), target=(8, 7, 8), fov=30,
                 background=("#2A1846", "#0E0718"), glow_bg=None):
        self.W, self.H = size[0] * ss, size[1] * ss
        self.size, self.ss = size, ss
        self.eye, self.target = np.array(eye, float), np.array(target, float)
        f = self.target - self.eye
        self.fwd = f / np.linalg.norm(f)
        r = np.cross(self.fwd, (0, 1, 0))
        self.right = r / np.linalg.norm(r)
        self.up = np.cross(self.right, self.fwd)
        self.focal = (self.H / 2) / math.tan(math.radians(fov) / 2)
        yy = np.linspace(0, 1, self.H)[:, None]
        top, bottom = hexa(background[0]), hexa(background[1])
        self.img = np.ones((self.H, self.W, 3)) * (top * (1 - yy[..., None]) + bottom * yy[..., None])
        if glow_bg:
            cx, cy, rad, col = glow_bg
            Y, X = np.mgrid[0:self.H, 0:self.W]
            d = np.sqrt((X / self.W - cx) ** 2 + (Y / self.H - cy) ** 2) / rad
            self.img += hexa(col) * np.clip(1 - d, 0, 1)[..., None] ** 2
        self.depth = np.full((self.H, self.W), np.inf)
        self.translucent = []

    def project(self, p):
        d = p - self.eye
        z = d @ self.fwd
        x = d @ self.right
        y = d @ self.up
        return np.array([self.W / 2 + self.focal * x / z, self.H / 2 - self.focal * y / z, z])

    def shadow(self, centre, rx, rz, strength=0.55):
        """Soft elliptic shadow on the ground (y of centre)."""
        cx, cy, cz = centre
        pts = []
        for k in range(48):
            a = 2 * math.pi * k / 48
            pts.append(self.project(np.array([cx + rx * math.cos(a), cy, cz + rz * math.sin(a)])))
        pts = np.array(pts)
        c = self.project(np.array(centre, float))
        Y, X = np.mgrid[0:self.H, 0:self.W]
        sx, sy = (pts[:, 0].max() - pts[:, 0].min()) / 2, (pts[:, 1].max() - pts[:, 1].min()) / 2
        d = np.sqrt(((X - c[0]) / max(sx, 1)) ** 2 + ((Y - c[1]) / max(sy, 1)) ** 2)
        a = strength * np.clip(1 - d, 0, 1) ** 1.5
        self.img *= (1 - a[..., None])

    def add(self, elements, textures, M=None, alpha_scale=1.0, dim=1.0):
        """elements with their uv; textures {key: RGBA array (first frame used)}; M model -> world 4x4."""
        M = np.eye(4) if M is None else M
        for e in elements:
            R, o = np.eye(3), np.zeros(3)
            if e.rot:
                R, o = rot3(e.rot[0], e.rot[1]), np.array(e.rot[2], float)
            key = e.tex[1] if isinstance(e.tex, tuple) else e.tex
            tex = textures[key]
            th, tw = tex.shape[:2]
            th = min(th, tw) if th > tw else th
            for f in e.faces:
                n = M[:3, :3] @ R @ np.array(NORMAL[f], float)
                n = n / np.linalg.norm(n)
                pts = []
                for p in corners(f, e.frm, e.to):
                    q = R @ (np.array(p, float) - o) + o
                    pts.append(M[:3, :3] @ q + M[:3, 3])
                centre = sum(pts) / 4
                view = centre - self.eye
                if n @ view >= 0:   # back faces are culled, as in game
                    continue
                light = item_light(n) if e.shade else 1.0
                light *= dim
                u0, v0, u1, v1 = e.uv[f]
                uvs = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
                P = [self.project(p) for p in pts]
                if min(p[2] for p in P) <= 0.1:
                    continue
                face = (P, uvs, tex, tw, th, light, alpha_scale)
                region = tex[int(min(v0, v1) / 16 * th):max(int(min(v0, v1) / 16 * th) + 1, int(math.ceil(max(v0, v1) / 16 * th))),
                             int(min(u0, u1) / 16 * tw):max(int(min(u0, u1) / 16 * tw) + 1, int(math.ceil(max(u0, u1) / 16 * tw))), 3]
                if ((region > 0) & (region < 250)).any() or alpha_scale < 1:
                    self.translucent.append((float(np.linalg.norm(view)), face))
                else:
                    self._raster(face, blend=False)

    def _raster(self, face, blend):
        P, uvs, tex, tw, th, light, alpha_scale = face
        for tri in ((0, 1, 2), (0, 2, 3)):
            A = np.array([P[i] for i in tri])
            UV = np.array([uvs[i] for i in tri])
            xmin, xmax = int(max(0, A[:, 0].min())), int(min(self.W - 1, A[:, 0].max() + 1))
            ymin, ymax = int(max(0, A[:, 1].min())), int(min(self.H - 1, A[:, 1].max() + 1))
            if xmin > xmax or ymin > ymax:
                continue
            xs, ys = np.meshgrid(np.arange(xmin, xmax + 1) + 0.5, np.arange(ymin, ymax + 1) + 0.5)
            (x0, y0), (x1, y1), (x2, y2) = A[0, :2], A[1, :2], A[2, :2]
            den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
            if abs(den) < 1e-9:
                continue
            a = ((y1 - y2) * (xs - x2) + (x2 - x1) * (ys - y2)) / den
            b = ((y2 - y0) * (xs - x2) + (x0 - x2) * (ys - y2)) / den
            c = 1 - a - b
            inside = (a >= -1e-7) & (b >= -1e-7) & (c >= -1e-7)
            # perspective-correct interpolation
            iz = a / A[0, 2] + b / A[1, 2] + c / A[2, 2]
            z = 1 / np.where(iz == 0, 1e-9, iz)
            u = (a * UV[0, 0] / A[0, 2] + b * UV[1, 0] / A[1, 2] + c * UV[2, 0] / A[2, 2]) * z
            v = (a * UV[0, 1] / A[0, 2] + b * UV[1, 1] / A[1, 2] + c * UV[2, 1] / A[2, 2]) * z
            tu = np.clip(np.floor(u / 16 * tw - 1e-6).astype(int), 0, tw - 1)
            tv = np.clip(np.floor(v / 16 * th - 1e-6).astype(int), 0, th - 1)
            col = tex[tv, tu].astype(float)
            dz = self.depth[ymin:ymax + 1, xmin:xmax + 1]
            zone = self.img[ymin:ymax + 1, xmin:xmax + 1]
            alpha = col[..., 3] / 255 * alpha_scale
            if blend:
                ok = inside & (z < dz) & (alpha > 0.01)
                al = alpha[ok][:, None]
                zone[ok] = zone[ok] * (1 - al) + col[ok, :3] * light * al
            else:
                ok = inside & (z < dz) & (alpha > 0.5)
                dz[ok] = z[ok]
                zone[ok] = col[ok, :3] * light

    def particle(self, pos, colour, size=0.35, alpha=1.0, shape="square"):
        """A particle (world position, size in units): flat square, cross sparkle or glow."""
        p = self.project(np.array(pos, float))
        if p[2] <= 0.1:
            return
        r = self.focal * size / p[2] / 2
        x0, x1 = int(max(0, p[0] - 3 * r)), int(min(self.W - 1, p[0] + 3 * r + 1))
        y0, y1 = int(max(0, p[1] - 3 * r)), int(min(self.H - 1, p[1] + 3 * r + 1))
        if x0 > x1 or y0 > y1:
            return
        ys, xs = np.mgrid[y0:y1 + 1, x0:x1 + 1]
        dx, dy = np.abs(xs - p[0]), np.abs(ys - p[1])
        if shape == "square":
            a = ((dx <= r) & (dy <= r)).astype(float)
        elif shape == "sparkle":
            a = (((dx <= r * 0.35) & (dy <= r * 2.2)) | ((dy <= r * 0.35) & (dx <= r * 2.2)) |
                 ((dx <= r * 0.8) & (dy <= r * 0.8))).astype(float)
        else:  # glow
            d = np.sqrt(dx ** 2 + dy ** 2) / (3 * r)
            a = np.clip(1 - d, 0, 1) ** 2
        a = a * alpha
        visible = p[2] < self.depth[y0:y1 + 1, x0:x1 + 1] + 1e-6
        a = a * visible
        zone = self.img[y0:y1 + 1, x0:x1 + 1]
        zone[:] = zone * (1 - a[..., None]) + hexa(colour) * a[..., None]

    def flush(self):
        """Draws the translucent faces (back to front); particles go after."""
        for _, face in sorted(self.translucent, key=lambda t: -t[0]):
            self._raster(face, blend=True)
        self.translucent = []

    def finish(self):
        self.flush()
        img = Image.fromarray(np.clip(self.img, 0, 255).astype(np.uint8), "RGB")
        return img.resize(self.size, Image.LANCZOS)
