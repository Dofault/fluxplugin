"""Atelier de modèles sur mesure pour les familiers InfluCard.

Contrairement aux archétypes de modeles.py, chaque familier y est sculpté à la main : n'importe
quelle couleur, des motifs (minerai, briques, écorce, herbe, liquide, rayures...) et un visage posé
sur la couleur voulue. Texture 64x64 allouée à la volée en tuiles 4x4 (couleurs, motifs) et 8x8
(visages). Les os portent les rôles d'animation (root, head, arms, wings, legs, tail, crown),
réutilisés par modeles.build_animations.
"""
import colorsys

import modeles as M


def _rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def _shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c)


def _mix(a, b, t):
    return tuple(int(x + (y - x) * t) for x, y in zip(a, b))


class Atelier(M.Model):
    """Modèle + texture : self.mat(...) renvoie un nom de tuile utilisable dans box()."""

    def __init__(self, ident):
        super().__init__(ident)
        self.px = [[(0, 0, 0, 0)] * 64 for _ in range(64)]
        self.tiles = {}
        self._next4 = 0   # tuiles 4x4 : colonnes 0..63, lignes 16..63
        self._next8 = 0   # tuiles 8x8 : ligne 0..15

    # ------------------------------------------------------------------ allocation
    def _alloc(self, size):
        if size == 8:
            i = self._next8
            self._next8 += 1
            u, v = (i % 8) * 8, (i // 8) * 8
            if v >= 16:
                raise ValueError("trop de visages")
            return u, v
        i = self._next4
        self._next4 += 1
        u, v = (i % 16) * 4, 16 + (i // 16) * 4
        if v >= 64:
            raise ValueError("trop de matériaux")
        return u, v

    def _put(self, u, v, x, y, c):
        self.px[v + y][u + x] = tuple(c[:3]) + (255,)

    # ------------------------------------------------------------------ matériaux
    def mat(self, color, pattern="flat", color2=None):
        """Matériau 4x4. pattern : flat (dégradé doux), speck (moucheté de color2, minerai),
        brick, bark, stripes, grass (dessus color2), liquid (haut color2), glow (lumineux), checker."""
        key = f"{color}|{pattern}|{color2}"
        if key in self.tiles:
            return key
        u, v = self._alloc(4)
        c = _rgb(color)
        c2 = _rgb(color2) if color2 else _shade(c, 1.35)
        for y in range(4):
            for x in range(4):
                if pattern == "flat":
                    col = _shade(c, 1.12 if y == 0 else (0.88 if y == 3 else 1.0))
                elif pattern == "glow":
                    col = _mix(c, (255, 255, 255), 0.35 if (x + y) % 3 == 0 else 0.1)
                elif pattern == "speck":
                    col = c2 if (x, y) in ((1, 0), (3, 1), (0, 2), (2, 3), (2, 1)) else _shade(c, 1.0 - 0.06 * ((x + y) % 2))
                elif pattern == "brick":
                    mortar = y in (1, 3) or (y == 0 and x == 1) or (y == 2 and x == 3)
                    col = c2 if mortar else _shade(c, 1.0 + 0.08 * ((x + y) % 2))
                elif pattern == "bark":
                    col = _shade(c, 0.75) if x in (1, 3) and y != 2 else c
                elif pattern == "stripes":
                    col = c2 if y % 2 == 0 else c
                elif pattern == "grass":
                    col = c2 if y == 0 or (y == 1 and x % 2 == 0) else c
                elif pattern == "liquid":
                    col = c2 if y >= 2 else _mix(c, (255, 255, 255), 0.25 if (x + y) % 2 else 0.1)
                elif pattern == "checker":
                    col = c2 if (x + y) % 2 else c
                else:
                    col = c
                self._put(u, v, x, y, col)
        self.tiles[key] = (u, v, 4, 4)
        return key

    def face(self, color, eye="#1A1010", cheek=None, mouth=None, eyes="round", glow_eyes=False):
        """Visage 8x8 sur la couleur donnée. eyes : round (2x3 avec reflet), dot (2x2), visor (bande),
        carved (yeux et bouche lumineux, citrouille), sleepy (traits)."""
        key = f"face|{color}|{eye}|{cheek}|{mouth}|{eyes}|{glow_eyes}"
        if key in self.tiles:
            return key
        u, v = self._alloc(8)
        c = _rgb(color)
        for y in range(8):
            for x in range(8):
                self._put(u, v, x, y, _shade(c, 1.1 if y == 0 else (0.88 if y == 7 else 1.0)))
        e = _rgb(eye)
        if eyes == "round":
            for ex in (1, 5):
                for yy in (2, 3, 4):
                    for xx in (ex, ex + 1):
                        self._put(u, v, xx, yy, e)
                self._put(u, v, ex, 2, (255, 255, 255) if not glow_eyes else _mix(e, (255, 255, 255), 0.6))
        elif eyes == "dot":
            for ex in (1, 5):
                for yy in (3, 4):
                    for xx in (ex, ex + 1):
                        self._put(u, v, xx, yy, e)
                self._put(u, v, ex + 1, 3, (255, 255, 255))
        elif eyes == "visor":
            for xx in range(1, 7):
                self._put(u, v, xx, 3, e)
            self._put(u, v, 2, 3, (255, 255, 255))
            self._put(u, v, 5, 3, (255, 255, 255))
        elif eyes == "carved":
            for (xx, yy) in ((1, 2), (2, 2), (1, 3), (5, 2), (6, 2), (6, 3), (2, 5), (3, 5), (4, 5), (5, 5), (3, 6), (4, 6)):
                self._put(u, v, xx, yy, e)
        elif eyes == "sleepy":
            for xx in (1, 2, 5, 6):
                self._put(u, v, xx, 3, e)
        if cheek:
            self._put(u, v, 0, 5, _rgb(cheek))
            self._put(u, v, 7, 5, _rgb(cheek))
        if mouth and eyes != "carved":
            self._put(u, v, 3, 5, _rgb(mouth))
            self._put(u, v, 4, 5, _rgb(mouth))
        self.tiles[key] = (u, v, 8, 8)
        return key

    # ------------------------------------------------------------------ géométrie
    def box(self, bone, name, frm, to, mat, rot=None, origin=None, front=None):
        """Cube : toutes les faces sur `mat`, la face nord (avant) sur `front` si donné."""
        frm, to = ([round(min(a, b), 3) for a, b in zip(frm, to)],
                   [round(max(a, b), 3) for a, b in zip(frm, to)])
        if origin is None:
            origin = [(a + b) / 2 for a, b in zip(frm, to)]
        faces = {}
        for d in ("north", "south", "east", "west", "up", "down"):
            u, v, w, h = self.tiles[front if (d == "north" and front) else mat]
            faces[d] = {"uv": [u, v, u + w, v + h], "texture": 0}
        el = {
            "name": name, "box_uv": False, "rescale": False, "locked": False,
            "render_order": "default", "allow_mirror_modeling": True,
            "from": frm, "to": to, "autouv": 0, "color": 0,
            "origin": [round(v, 3) for v in origin], "faces": faces, "type": "cube",
            "uuid": self.uid("el/" + bone + "/" + name + "/" + str(len(self.elements))),
        }
        if rot:
            el["rotation"] = list(rot)
        self.elements.append(el)
        self.bones[bone]["children"].append(el["uuid"])
        return el

    def export(self):
        return self.to_bbmodel(self.px, M.build_animations(self))
