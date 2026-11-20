"""Aperçu 3D sans dépendance d'un modèle d'objet Minecraft (JSON « elements ») : rendu orthographique par
lancer de rayons, avec la rotation de chaque cube et une texture par face. Sert à contrôler une armure avant
de la livrer."""
import math

SHADE = {"up": 1.0, "down": 0.55, "north": 0.85, "south": 0.7, "east": 0.78, "west": 0.92}
AXES = (("west", "east"), ("down", "up"), ("north", "south"))


def _rot(axis, angle):
    a = math.radians(angle)
    c, s = math.cos(a), math.sin(a)
    if axis == "x":
        return [[1, 0, 0], [0, c, -s], [0, s, c]]
    if axis == "y":
        return [[c, 0, s], [0, 1, 0], [-s, 0, c]]
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]]


def _mul(M, v):
    return [sum(M[i][k] * v[k] for k in range(3)) for i in range(3)]


def _matmul(A, B):
    return [[sum(A[i][k] * B[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _tr(M):
    return [[M[j][i] for j in range(3)] for i in range(3)]


def cubes(model, textures, offset=(0, 0, 0)):
    """Prépare les cubes d'un modèle. textures : {clé de texture du modèle: grille RGBA}."""
    out = []
    for e in model["elements"]:
        r = e.get("rotation") or {}
        M = _rot(r.get("axis", "y"), r.get("angle", 0))
        o = r.get("origin", [8, 8, 8])
        faces = {}
        for name, f in e.get("faces", {}).items():
            px = textures.get(f["texture"].lstrip("#"))
            if px is not None:
                faces[name] = (f["uv"], px, f.get("rotation", 0))
        out.append((e["from"], e["to"], M, _tr(M), o, faces, offset))
    return out


def render(scene, yaw, pitch, size=200, bg=(46, 42, 56, 255), margin=14, frame=None):
    """scene : liste issue de cubes(). yaw 0 = œil au nord, qui regarde vers le sud."""
    yw, pt = math.radians(yaw), math.radians(pitch)
    d = [-math.sin(yw) * math.cos(pt), -math.sin(pt), math.cos(yw) * math.cos(pt)]
    right = [-math.cos(yw), 0, -math.sin(yw)]
    up = [d[1] * right[2] - d[2] * right[1], d[2] * right[0] - d[0] * right[2], d[0] * right[1] - d[1] * right[0]]
    if up[1] < 0:
        up = [-c for c in up]
    prep, pts = [], []
    for (a, b, M, Mt, o, faces, off) in scene:
        cs = []
        for cx in (a[0], b[0]):
            for cy in (a[1], b[1]):
                for cz in (a[2], b[2]):
                    p = _mul(M, [cx - o[0], cy - o[1], cz - o[2]])
                    p = [p[i] + o[i] + off[i] for i in range(3)]
                    cs.append((sum(p[i] * right[i] for i in range(3)), sum(p[i] * up[i] for i in range(3))))
        pts += cs
        prep.append((a, b, Mt, o, faces, off, _mul(Mt, d), cs))
    if frame:
        pts = [(sum(p[i] * right[i] for i in range(3)), sum(p[i] * up[i] for i in range(3))) for p in frame]
    minu, maxu = min(p[0] for p in pts), max(p[0] for p in pts)
    minv, maxv = min(p[1] for p in pts), max(p[1] for p in pts)
    scale = (size - margin) / max(maxu - minu, maxv - minv, 1)
    cu, cv = (minu + maxu) / 2, (minv + maxv) / 2
    boxes = []
    for c in prep:
        us, vs = [k[0] for k in c[7]], [k[1] for k in c[7]]
        boxes.append((c, (min(us) - cu) * scale + size / 2 - 1, (max(us) - cu) * scale + size / 2 + 1,
                      (cv - max(vs)) * scale + size / 2 - 1, (cv - min(vs)) * scale + size / 2 + 1))
    out = [[bg] * size for _ in range(size)]
    for py in range(size):
        row = [b for b in boxes if b[3] <= py <= b[4]]
        for qx in range(size):
            u = cu + (qx + 0.5 - size / 2) / scale
            v = cv - (py + 0.5 - size / 2) / scale
            origin = [right[i] * u + up[i] * v - d[i] * 300.0 for i in range(3)]
            best, col = None, None
            for (a, b, Mt, o, faces, off, rd, _), x_lo, x_hi, _, _ in row:
                if not x_lo <= qx <= x_hi:
                    continue
                ro = _mul(Mt, [origin[i] - o[i] - off[i] for i in range(3)])
                ro = [ro[i] + o[i] for i in range(3)]
                t0, t1, face, ok = -1e9, 1e9, None, True
                for ax, (neg, pos) in enumerate(AXES):
                    lo, hi = a[ax], b[ax]
                    if abs(rd[ax]) < 1e-9:
                        if ro[ax] < lo or ro[ax] > hi:
                            ok = False
                            break
                        continue
                    ta, tb = (lo - ro[ax]) / rd[ax], (hi - ro[ax]) / rd[ax]
                    fa = neg if rd[ax] > 0 else pos
                    if ta > tb:
                        ta, tb = tb, ta
                    if ta > t0:
                        t0, face = ta, fa
                    t1 = min(t1, tb)
                    if t0 > t1:
                        ok = False
                        break
                if not ok or face not in faces or (best is not None and t0 >= best):
                    continue
                x, y, z = (ro[i] + rd[i] * t0 for i in range(3))

                def fr(val, lo, hi):
                    return 0.5 if hi - lo < 1e-6 else min(0.999, max(0.0, (val - lo) / (hi - lo)))
                if face == "north":
                    s, t = 1 - fr(x, a[0], b[0]), 1 - fr(y, a[1], b[1])
                elif face == "south":
                    s, t = fr(x, a[0], b[0]), 1 - fr(y, a[1], b[1])
                elif face == "east":
                    s, t = 1 - fr(z, a[2], b[2]), 1 - fr(y, a[1], b[1])
                elif face == "west":
                    s, t = fr(z, a[2], b[2]), 1 - fr(y, a[1], b[1])
                elif face == "up":
                    s, t = fr(x, a[0], b[0]), fr(z, a[2], b[2])
                else:
                    s, t = fr(x, a[0], b[0]), 1 - fr(z, a[2], b[2])
                (u0, v0, u1, v1), px, frot = faces[face]
                for _ in range((frot // 90) % 4):
                    s, t = t, 1 - s
                k = len(px[0]) / 16.0
                tu = min(len(px[0]) - 1, max(0, int((u0 + s * (u1 - u0)) * k)))
                tv = min(len(px) - 1, max(0, int((v0 + t * (v1 - v0)) * k)))
                c = px[tv][tu]
                if c[3] < 26:
                    continue
                sh = SHADE[face]
                best, col = t0, tuple(min(255, int(c[i] * sh)) for i in range(3)) + (255,)
            if col:
                out[py][qx] = col
    return out
