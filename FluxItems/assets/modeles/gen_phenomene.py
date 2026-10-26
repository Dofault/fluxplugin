#!/usr/bin/env python3
"""Set phénomène en 3D (Labs) : pioche FluxItems et armure EcoArmor, modèles, textures et apparence portée.

    <venv avec Pillow et numpy>/python3 gen_phenomene.py <contents/influoutils> [dossier d'aperçus]

Tout le pack s'écrit sous <contents/influoutils>/resourcepack/assets/influoutils/ : ItemsAdder ne fusionne un
dossier resourcepack/ brut à la racine du pack que si le dossier de contenu n'a pas aussi ses propres
textures/ ou models/ (sinon il le recopie tel quel, invisible pour le client).

- textures/item/phenomene/ : textures communes 32 x 32 (or, cuir violet, pierre sombre, veines et gemme
  animées), et copies des textures d'armure pour les icônes (seul l'atlas des objets est lisible par un modèle) ;
- models/item/*.json : pioche (blocs posés en diagonale, affichages de minecraft:item/handheld), casque
  (porté tel quel sur la tête : couronne, ailerons, gemme du front), icônes 3D du plastron, des jambières et
  des bottes (boîtes du joueur, mêmes textures que l'armure portée) ;
- items/*.json : définitions d'objets, cibles du composant item_model ;
- equipment/phenomene.json et textures/entity/equipment/{humanoid,humanoid_leggings,wings}/phenomene.png :
  armure portée (gabarit 64 x 32 du joueur, dessiné en double résolution) et ailes en lames sur le plastron
  (calque wings : géométrie de l'élytre, sans vol) ;
et configs/items.yml : objet ItemsAdder de la pioche, pour l'aperçu de sa caisse et /iagive.

Veines et gemmes des modèles (pioche, casque) : lumineuses (light_emission) et animées. Les textures portées
sur le corps ne peuvent être ni l'un ni l'autre (hors atlas, pas d'émission). Les modèles sont posés par
FluxItems : clé `modele:` de l'outil pour la pioche, FluxItems/apparences.yml pour l'armure.
Le dossier d'aperçus reçoit des rendus PNG (moteur de rendu maison, approximatif) et un GIF.
Relancer après toute modification, puis /iazip sur Labs.
"""
import json
import math
import os
import random
import shutil
import sys

import numpy as np
from PIL import Image

NS = "influoutils"
TEX = NS + ":item/phenomene/"
T = 32  # côté des textures communes : 2 texels par unité du modèle
K = 2   # texels par pixel du gabarit d'armure 64 x 32

# Charte : or chaud, or vif, violets, fond noir.
OR = ["#4A2600", "#7A4300", "#A86A00", "#D98E00", "#F0A800", "#FFC233", "#FFD94D", "#FFF1A8"]
MANCHE = ["#0D0613", "#1E0A3C", "#2E1257", "#3F1C74", "#5B2D9E", "#7B48C2", "#B376E5"]
PIERRE = ["#120A1C", "#24153A", "#2F1C4B", "#3B245E", "#4A2E73", "#5B3A8A", "#6E4AA3"]
FEU = ["#7A1E00", "#B33A00", "#E8590C", "#FF7A1A", "#FFB02E", "#FFD94D", "#FFF1A8", "#FFFBE6"]


def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def palette(couleurs, v):
    """v dans [0, 1] -> teinte de la palette (quantifiée, rendu pixel art)."""
    i = max(0, min(len(couleurs) - 1, int(round(v * (len(couleurs) - 1)))))
    return rgb(couleurs[i]) + (255,)


def bruit(seed, n=T, octaves=((4, 0.6), (8, 0.3), (16, 0.1))):
    """Bruit de valeur raccordable (tuile), dans [0, 1]."""
    r = random.Random(seed)
    out = np.zeros((n, n))
    for cell, w in octaves:
        g = np.array([[r.random() for _ in range(cell)] for _ in range(cell)])
        for y in range(n):
            for x in range(n):
                fx, fy = x * cell / n, y * cell / n
                x0, y0 = int(fx), int(fy)
                tx, ty = fx - x0, fy - y0
                tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
                a = g[y0 % cell][x0 % cell] * (1 - tx) + g[y0 % cell][(x0 + 1) % cell] * tx
                b = g[(y0 + 1) % cell][x0 % cell] * (1 - tx) + g[(y0 + 1) % cell][(x0 + 1) % cell] * tx
                out[y][x] += w * (a * (1 - ty) + b * ty)
    return (out - out.min()) / (out.max() - out.min())


def chemins(seed, n, departs, pas):
    """Fissures en marche aléatoire qui se raccordent d'un bord à l'autre, avec la distance parcourue."""
    r = random.Random(seed)
    pts = {}
    for depart in range(departs):
        x, y = r.randrange(n), r.randrange(n)
        d = r.choice([(1, 0), (0, 1), (1, 1), (1, -1)])
        s = depart * 7.0
        for _ in range(r.randint(*pas)):
            pts.setdefault((x % n, y % n), s)
            if r.random() < 0.3:
                d = r.choice([(1, 0), (0, 1), (1, 1), (1, -1), (-1, 1)])
            if d[0] and d[1]:
                pts.setdefault(((x + d[0]) % n, y % n), s + 0.5)
            x, y, s = x + d[0], y + d[1], s + 1
            if r.random() < 0.08:  # petite branche
                bx, by = x, y
                for k in range(r.randint(3, 6)):
                    bx += r.choice([-1, 0, 1])
                    by += 1
                    pts.setdefault((bx % n, by % n), s + k)
    return pts


VEINES = chemins(31, T, 6, (20, 32))

# --------------------------------------------------------------------------- textures communes (objets)


def tex_or():
    """Or brossé : dégradé clair en haut (lumière du dessus), stries horizontales, quelques éclats."""
    r = random.Random(11)
    n = bruit(12, octaves=((2, 0.5), (32, 0.5)))
    im = Image.new("RGBA", (T, T))
    stries = [r.uniform(-0.12, 0.12) for _ in range(T)]
    for y in range(T):
        for x in range(T):
            v = 0.95 - 0.55 * y / (T - 1) + stries[y] + 0.18 * (n[y][x] - 0.5)
            im.putpixel((x, y), palette(OR[1:7], v))
    for _ in range(10):
        im.putpixel((r.randrange(T), r.randrange(T)), rgb(OR[7]) + (255,))
    return im


def tex_manche():
    """Cuir violet enroulé en diagonale, liseré clair sur chaque tour de lanière."""
    n = bruit(21)
    tons = [0.0, 0.2, 0.45, 0.6, 0.7, 0.8, 0.95, 0.5]
    im = Image.new("RGBA", (T, T))
    for y in range(T):
        for x in range(T):
            v = tons[(x + y) % 8] + 0.12 * (n[y][x] - 0.5)
            im.putpixel((x, y), palette(MANCHE, v))
    return im


def tex_pierre():
    """Pierre sombre (obsidienne violacée), sillons plus sombres sous les veines lumineuses."""
    n = bruit(41)
    im = Image.new("RGBA", (T, T))
    for y in range(T):
        for x in range(T):
            im.putpixel((x, y), palette(PIERRE[1:], n[y][x] ** 1.3))
    for (x, y) in VEINES:
        im.putpixel((x, y), rgb(PIERRE[0]) + (255,))
    return im


def etoile(im, cx, cy, force, couleurs=FEU):
    """Étincelle en croix (motif étoile de la charte) : cœur blanc, branches dorées."""
    if force <= 0.05:
        return
    pix = [((0, 0), 1.0), ((1, 0), 0.6), ((-1, 0), 0.6), ((0, 1), 0.6), ((0, -1), 0.6)]
    if force > 0.7:
        pix += [((2, 0), 0.35), ((-2, 0), 0.35), ((0, 2), 0.35), ((0, -2), 0.35)]
    for (dx, dy), k in pix:
        v = force * k
        if v > 0.15:
            im.putpixel(((cx + dx) % T, (cy + dy) % T), palette(couleurs, 0.45 + 0.55 * v))


def tex_veines(frames=16):
    """Calque transparent des veines : l'énergie coule le long des fissures, des étoiles s'allument."""
    r = random.Random(51)
    etoiles = [(r.randrange(T), r.randrange(T), r.random()) for _ in range(5)]
    planche = Image.new("RGBA", (T, T * frames), (0, 0, 0, 0))
    for f in range(frames):
        im = Image.new("RGBA", (T, T), (0, 0, 0, 0))
        t = f / frames
        for (x, y), s in VEINES.items():
            v = 0.5 + 0.5 * math.sin(2 * math.pi * (t - s / 12.0))
            im.putpixel((x, y), palette(FEU[1:7], 0.15 + 0.85 * v))
        for ex, ey, phase in etoiles:
            u = (t - phase) % 1.0
            etoile(im, ex, ey, max(0.0, 1 - abs(u - 0.15) / 0.15))
        planche.paste(im, (0, f * T))
    return planche


def facette(dx, dy):
    """Teinte (0 à 1) d'une gemme taillée vue de face, dx et dy dans [-1, 1] : filet, table, 4 facettes."""
    d = max(abs(dx), abs(dy))
    if d > 0.88:
        return 0.08
    if d > 0.72:
        return 0.62
    if d > 0.34:
        if abs(dy) >= abs(dx):
            return 0.78 if dy < 0 else 0.36
        return 0.58 if dx < 0 else 0.46
    return 0.86


def tex_gemme(frames=20):
    """Gemme taillée orange et or, reflet qui la balaie en diagonale."""
    planche = Image.new("RGBA", (T, T * frames))
    c = (T - 1) / 2
    for f in range(frames):
        im = Image.new("RGBA", (T, T))
        bande = -1.2 + 3.4 * f / frames  # position du reflet le long de la diagonale
        for y in range(T):
            for x in range(T):
                dx, dy = (x - c) / c, (y - c) / c
                v = facette(dx, dy)
                diag = (dx + dy) / 2
                if max(abs(dx), abs(dy)) <= 0.88 and abs(diag - bande) < 0.16:
                    v = min(1.0, v + 0.35 * (1 - abs(diag - bande) / 0.16))
                im.putpixel((x, y), palette(FEU, v))
        etoile(im, int(c - 6), int(c - 6), 0.5 + 0.5 * math.sin(2 * math.pi * f / frames), FEU[3:])
        planche.paste(im, (0, f * T))
    return planche


# ------------------------------------------------------------------ armure portée (gabarit 64 x 32, x2)

NP = bruit(61, n=64)
VEINES_ARMURE = chemins(71, 64, 7, (10, 18))
SOMBRE = rgb(MANCHE[1]) + (255,)
SEMELLE = rgb(MANCHE[0]) + (255,)


def faces_boite(U, V, w, h, d):
    """Rectangles (u, v, largeur, hauteur) des faces d'une boîte d'entité, en pixels du gabarit 64 x 32."""
    return {"top": (U + d, V, w, d), "bottom": (U + d + w, V, w, d), "right": (U, V + d, d, h),
            "front": (U + d, V + d, w, h), "left": (U + d + w, V + d, d, h), "back": (U + 2 * d + w, V + d, w, h)}


def peindre(img, rect, fn):
    """fn(x, y, largeur, hauteur, gx, gy) -> couleur ou None (transparent), en texels de la face."""
    U, V, w, h = rect
    W, H = w * K, h * K
    for y in range(H):
        for x in range(W):
            c = fn(x, y, W, H, U * K + x, V * K + y)
            if c is not None:
                img[V * K + y, U * K + x] = c


def or_(v):
    return palette(OR[2:7], v)


def plaque(gx, gy):
    """Plaque d'armure violette, veines orangées qui courent d'une face à l'autre."""
    if (gx % 64, gy % 64) in VEINES_ARMURE:
        return palette(FEU[2:6], 0.55 + 0.4 * NP[gy % 64][gx % 64])
    return palette(MANCHE[2:6], 0.25 + 0.55 * NP[gy % 64][gx % 64])


def gemme(x, y, cx, cy, r):
    """Gemme carrée centrée en (cx, cy), demi-côté r texels ; None hors de la gemme."""
    dx, dy = (x - cx) / r, (y - cy) / r
    if max(abs(dx), abs(dy)) > 1:
        return None
    return palette(FEU, facette(dx, dy))


def bordure(x, y, W, H):
    return x == 0 or x == W - 1 or y == H - 1


def torse(face):
    def fn(x, y, W, H, gx, gy):
        cx = (W - 1) / 2
        if y <= 1:
            return or_(0.95 - 0.25 * y)  # col
        if face == "front":
            g = gemme(x, y, cx, 8.5, 3.5)
            if g:
                return g
            if max(abs(x - cx), abs(y - 8.5)) <= 4.6:
                return or_(0.7)  # sertissure
            if 2 <= y <= 11 and abs(abs(x - cx) - (7.0 - (y - 2) * 0.55)) < 0.75:
                return or_(0.85)  # chevrons vers la gemme
            if y >= 14 and (y - 14) % 3 == 0:
                return SOMBRE  # plaques du ventre
            if y >= 14 and (y - 14) % 3 == 1 and x in (2, W - 3):
                return or_(1.0)  # rivets
        if face == "back":
            g = gemme(x, y, cx, 5.5, 2.5)
            if g:
                return g
            if abs(x - cx) <= 1 and y < H - 1:
                return or_(0.8 - 0.4 * y / H)  # colonne dorée
        if bordure(x, y, W, H):
            return or_(0.55)
        return plaque(gx, gy)
    return fn


def bras(face):
    def fn(x, y, W, H, gx, gy):
        if y <= 8:  # spalière dorée à écailles violettes
            if y == 8:
                return SOMBRE
            if face in ("right", "left") and 2 <= y <= 6:
                g = gemme(x, y, (W - 1) / 2, 4, 2)
                if g:
                    return g
            if (y % 3 == 2) and (x + (y // 3) * 2) % 4 in (1, 2):
                return palette(MANCHE[2:5], 0.5)
            return or_(0.95 - 0.06 * y)
        if y in (18, 19) or y == H - 1:
            return or_(0.6)  # bracelet et poignet
        if x in (0, W - 1):
            return or_(0.45)
        return plaque(gx, gy)
    return fn


def botte(face):
    def fn(x, y, W, H, gx, gy):
        if y < 13:
            return None
        if y in (13, 14):
            if face == "front" and x in (3, 4):
                return palette(FEU, 0.8)
            return or_(0.9)  # revers doré
        if face == "front" and y >= 19 and 1 <= x <= W - 2:
            return or_(0.95 - 0.08 * (y - 19))  # coque de la pointe
        if y == H - 1:
            return or_(0.45)
        return plaque(gx, gy)
    return fn


def ceinture(face):
    def fn(x, y, W, H, gx, gy):
        if y < 16:
            return None
        if face == "front":
            g = gemme(x, y, (W - 1) / 2, 19.5, 2.5)
            if g:
                return g
        if y in (16, 22, 23):
            return or_(0.85 if y == 16 else 0.55)
        return plaque(gx, gy)
    return fn


def jambe(face):
    def fn(x, y, W, H, gx, gy):
        if y == 0:
            return or_(0.85)
        if face == "front":
            g = gemme(x, y, (W - 1) / 2, 11, 1.6)
            if g:
                return g
            if 9 <= y <= 13 and 1 <= x <= W - 2:
                return or_(0.95 - 0.1 * (y - 9))  # genouillère
        if face in ("right", "left") and x in (3, 4):
            return or_(0.7 - 0.3 * y / H)  # bande dorée sur le côté
        if y in (5, 16):
            return SOMBRE
        return plaque(gx, gy)
    return fn


def tex_armure():
    """Calque humanoid : plastron (torse, bras) et bottes (bas des jambes). Tête vide : casque en 3D."""
    img = np.zeros((32 * K, 64 * K, 4), np.uint8)
    for nom, rect in faces_boite(16, 16, 8, 12, 4).items():
        if nom == "top":
            peindre(img, rect, lambda x, y, W, H, gx, gy: or_(0.9))
        elif nom == "bottom":
            peindre(img, rect, lambda x, y, W, H, gx, gy: SOMBRE)
        else:
            peindre(img, rect, torse(nom))
    for nom, rect in faces_boite(40, 16, 4, 12, 4).items():
        if nom == "top":
            peindre(img, rect, lambda x, y, W, H, gx, gy: or_(1.0) if 1 <= x < W - 1 and 1 <= y < H - 1 else or_(0.6))
        elif nom == "bottom":
            peindre(img, rect, lambda x, y, W, H, gx, gy: SOMBRE)
        else:
            peindre(img, rect, bras(nom))
    for nom, rect in faces_boite(0, 16, 4, 12, 4).items():
        if nom == "bottom":
            peindre(img, rect, lambda x, y, W, H, gx, gy: or_(0.45) if bordure(x, 0, W, H) or y in (0, H - 1) else SEMELLE)
        elif nom != "top":
            peindre(img, rect, botte(nom))
    return Image.fromarray(img, "RGBA")


def tex_jambieres():
    """Calque humanoid_leggings : ceinture à boucle gemme et jambières à genouillères."""
    img = np.zeros((32 * K, 64 * K, 4), np.uint8)
    for nom, rect in faces_boite(16, 16, 8, 12, 4).items():
        if nom not in ("top", "bottom"):
            peindre(img, rect, ceinture(nom))
    for nom, rect in faces_boite(0, 16, 4, 12, 4).items():
        if nom == "top":
            peindre(img, rect, lambda x, y, W, H, gx, gy: plaque(gx, gy))
        elif nom != "bottom":
            peindre(img, rect, jambe(nom))
    return Image.fromarray(img, "RGBA")


def bord_aile(s):
    """Bas de l'aile (0 = attache, 1 = plus bas) en fonction de s (0 = bord intérieur, 1 = épaule) : 4 lames."""
    k = min(3, int(s * 4))
    a = s * 4 - k
    return 0.55 + 0.15 * k - 0.2 * abs(2 * a - 1), k, a


def aile(s, t, W, H):
    bas, k, a = bord_aile(s)
    if t > bas:
        return None
    if t < 0.1:
        if s > 0.7:
            g = gemme(s * (W - 1), t * (H - 1), 0.86 * (W - 1), 1.6, 1.8)
            if g:
                return g
        return or_(0.95 - 2 * t)  # attache dorée
    if a < 0.07 or a > 0.93:
        return or_(0.7)  # filets dorés entre les lames
    if bas - t < 0.035:
        return palette(FEU, 0.85)
    if bas - t < 0.08:
        return palette(FEU, 0.62)  # pointe incandescente
    if abs(a - 0.5) < 0.1 and t > 0.16:
        return palette(FEU[2:6], 0.3 + 0.5 * t)  # nervure
    return palette(MANCHE[2:7], 0.95 - 0.75 * t + 0.15 * (NP[int(t * 63)][int(s * 63)] - 0.5))


def tex_ailes():
    """Calque wings (gabarit de l'élytre, aile 10 x 20 x 2) : lames violettes, filets d'or, pointes en feu."""
    img = np.zeros((32 * K, 64 * K, 4), np.uint8)
    f = faces_boite(22, 0, 10, 20, 2)
    # face avant (vers le dos du joueur) : bord intérieur à gauche ; face arrière (vue de derrière) : à droite
    peindre(img, f["front"], lambda x, y, W, H, gx, gy: aile(x / (W - 1), y / (H - 1), W, H))
    peindre(img, f["back"], lambda x, y, W, H, gx, gy: aile(1 - x / (W - 1), y / (H - 1), W, H))
    for nom, s in (("right", 0.0), ("left", 1.0)):
        peindre(img, f[nom], lambda x, y, W, H, gx, gy, s=s: or_(0.7) if y / (H - 1) <= bord_aile(s)[0] else None)
    peindre(img, f["top"], lambda x, y, W, H, gx, gy: or_(0.9))
    peindre(img, f["bottom"], lambda x, y, W, H, gx, gy: palette(FEU, 0.7) if bord_aile(x / (W - 1))[0] > 0.97 else None)
    return Image.fromarray(img, "RGBA")


# ----------------------------------------------------------------------------------------------- modèles

FACES = ("north", "east", "south", "west", "up", "down")


def uv_face(face, f, t, plein):
    """UV d'après la position dans le modèle (les dégradés des textures se suivent d'un bloc à l'autre)."""
    if plein:
        return [0, 0, 16, 16]
    (x1, y1, z1), (x2, y2, z2) = f, t
    if face in ("north", "south"):
        u, v = (x1, x2), (16 - y2, 16 - y1)
    elif face in ("east", "west"):
        u, v = (z1, z2), (16 - y2, 16 - y1)
    else:
        u, v = (x1, x2), (z1, z2)

    def borne(a, b):
        w = b - a
        a = min(max(a, 0), 16 - w)
        return [round(a, 3), round(a + w, 3)]

    (u1, u2), (v1, v2) = borne(*u), borne(*v)
    return [u1, v1, u2, v2]


def element(f, t, tex, lum=False, plein=False, rotation=None):
    e = {"from": [round(v, 3) for v in f], "to": [round(v, 3) for v in t]}
    if rotation:
        e["rotation"] = rotation
    e["faces"] = {fc: {"uv": uv_face(fc, f, t, plein), "texture": "#" + tex} for fc in FACES}
    if lum:
        e["light_emission"] = 15
    return e


class Pieces:
    """Accumulateur de blocs, avec symétrie gauche/droite et calque de veines lumineuses sur la pierre."""

    def __init__(self, rotation=None):
        self.elements = []
        self.rotation = rotation
        self.pierres = []

    def __call__(self, f, t, tex, lum=False, plein=False, veine=True):
        self.elements.append(element(f, t, tex, lum, plein, self.rotation))
        if tex == "pierre" and veine:
            self.pierres.append((f, t))

    def miroir(self, f, t, tex, lum=False, plein=False, veine=True):
        self(f, t, tex, lum, plein, veine)
        self([16 - t[0], f[1], f[2]], [16 - f[0], t[1], t[2]], tex, lum, plein, veine)

    def pointe(self, x, z, base, hauteur, largeur, prof=1.0):
        """Pointe dorée effilée en trois étages (largeur relative, part de la hauteur), centrée en (x, z)."""
        y = base
        for l, h in ((1.0, 0.45), (0.6, 0.35), (0.25, 0.2)):
            demi_x, demi_z = largeur * l / 2, prof * (0.5 + 0.5 * l) / 2
            self([x - demi_x, y, z - demi_z], [x + demi_x, y + hauteur * h, z + demi_z], "or")
            y += hauteur * h

    def veines(self):
        """Calque des veines sur chaque bloc de pierre, à peine gonflé pour ne pas scintiller avec elle."""
        for f, t in self.pierres:
            self.elements.append(element([v - 0.02 for v in f], [v + 0.02 for v in t], "veines", True, False, self.rotation))
        return self.elements


def textures(*noms):
    return {k: TEX + k for k in noms} | {"particle": TEX + "or"}


def pioche():
    """Dessinée debout (manche selon Y, en x = z = 8), puis couchée en diagonale par la rotation commune."""
    p = Pieces({"angle": -45, "axis": "z", "origin": [8, 8, 8]})
    # Manche : pommeau doré et sa gemme, cuir violet, deux bagues, collier sous la tête.
    p([7.3, -2.4, 7.3], [8.7, -1.6, 8.7], "gemme", True, True)
    p([6.8, -1.6, 6.8], [9.2, 0.4, 9.2], "or")
    p([7.25, 0.4, 7.25], [8.75, 10.4, 8.75], "manche")
    p([7.0, 2.8, 7.0], [9.0, 3.6, 9.0], "or")
    p([7.0, 7.4, 7.0], [9.0, 8.2, 9.0], "or")
    p([6.6, 10.2, 6.6], [9.4, 11.6, 9.4], "or")
    # Tête : cœur de pierre, bras en escalier qui plongent vers des pointes dorées.
    p([5.6, 11.4, 6.5], [10.4, 15.0, 9.5], "pierre")
    p.miroir([2.6, 11.8, 7.0], [5.6, 14.6, 9.0], "pierre")
    p.miroir([0.6, 11.0, 7.2], [2.6, 13.6, 8.8], "pierre")
    p.miroir([-0.6, 9.8, 7.4], [0.6, 12.4, 8.6], "pierre")
    p.miroir([-1.4, 8.6, 7.5], [0.2, 10.6, 8.5], "or")
    # Couronne sur la tête : bandeau doré et trois pointes, la plus haute au centre.
    p([5.4, 14.8, 6.6], [10.6, 15.6, 9.4], "or")
    p.miroir([5.8, 15.6, 7.4], [6.6, 16.4, 8.6], "or")
    p([7.6, 15.6, 7.4], [8.4, 16.8, 8.6], "or")
    # Gemmes : grande gemme sertie au centre (traverse la tête), petites gemmes sur les bras.
    p([6.2, 11.7, 6.3], [9.8, 14.5, 9.7], "or")
    p([6.6, 12.0, 6.1], [9.4, 14.2, 9.9], "gemme", True, True)
    p.miroir([1.1, 11.7, 6.9], [2.1, 12.7, 9.1], "gemme", True, True)
    trois = {"translation": [0, 4.0, 0.5], "scale": [0.85, 0.85, 0.85]}
    premiere = {"translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]}
    return {
        "gui_light": "front",
        "textures": textures("or", "manche", "pierre", "veines", "gemme"),
        "elements": p.veines(),
        # Affichages de minecraft:item/handheld (et item/generated pour le sol, la tête, le cadre).
        "display": {
            "thirdperson_righthand": {"rotation": [0, -90, 55]} | trois,
            "thirdperson_lefthand": {"rotation": [0, 90, -55]} | trois,
            "firstperson_righthand": {"rotation": [0, -90, 25]} | premiere,
            "firstperson_lefthand": {"rotation": [0, 90, -25]} | premiere,
            "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
            "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
            "fixed": {"rotation": [0, 180, 0], "scale": [1, 1, 1]},
        },
    }


def affichages_objet(gui):
    """Affichages d'un objet volumineux (comme un bloc), avec l'angle d'inventaire propre à la pièce."""
    return {
        "gui": gui,
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
        "fixed": {"rotation": [0, 180, 0], "scale": [0.5, 0.5, 0.5]},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
        "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
        "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [0.4, 0.4, 0.4]},
        "firstperson_lefthand": {"rotation": [0, 225, 0], "scale": [0.4, 0.4, 0.4]},
    }


def casque():
    """Porté tel quel sur la tête (sans calque d'armure) : la tête du joueur occupe 1,6 à 14,4, face au nord."""
    p = Pieces()
    # Coque de pierre : dessus, nuque, côtés, front, joues (fente du visage au milieu). Sans veines lumineuses :
    # sur de si grandes faces elles noient le dessin, elles restent sur les ailerons.
    p([1.0, 14.4, 1.0], [15.0, 15.2, 15.0], "pierre", veine=False)
    p([1.0, 4.0, 14.4], [15.0, 14.4, 15.0], "pierre", veine=False)
    p.miroir([1.0, 4.0, 1.6], [1.6, 14.4, 14.4], "pierre", veine=False)
    p([1.0, 11.6, 1.0], [15.0, 14.4, 1.6], "pierre", veine=False)
    p.miroir([1.0, 4.0, 1.0], [4.6, 10.8, 1.6], "pierre", veine=False)
    # Crête dorée du front à la nuque.
    p([7.3, 15.2, 2.0], [8.7, 15.8, 14.0], "or")
    # Or : sourcil, bords de la fente, nasal, bord inférieur tout autour.
    p([0.8, 10.8, 0.7], [15.2, 11.6, 1.7], "or")
    p.miroir([4.2, 4.0, 0.8], [4.8, 10.8, 1.7], "or")
    p([7.3, 6.5, 0.4], [8.7, 10.8, 1.3], "or")
    p.miroir([0.8, 3.4, 0.8], [4.8, 4.2, 1.8], "or")
    p.miroir([0.8, 3.4, 1.8], [1.8, 4.2, 14.2], "or")
    p([0.8, 3.4, 14.2], [15.2, 4.2, 15.2], "or")
    # Gemme du front, sertie.
    p([6.2, 11.5, 0.5], [9.8, 14.3, 1.1], "or")
    p([6.6, 11.8, 0.2], [9.4, 14.0, 0.6], "gemme", True, True)
    # Couronne : bandeau et pointes, la plus haute au-dessus du front, une gemme à sa base.
    p([1.0, 15.2, 1.0], [15.0, 16.2, 2.0], "or")
    p([1.0, 15.2, 14.0], [15.0, 16.2, 15.0], "or")
    p.miroir([1.0, 15.2, 2.0], [2.0, 16.2, 14.0], "or")
    p.pointe(8.0, 1.5, 16.2, 4.4, 2.2)
    p([7.4, 16.5, 0.6], [8.6, 17.7, 1.0], "gemme", True, True)
    for x, z, h in ((1.5, 1.5, 2.6), (4.7, 1.5, 2.0), (1.5, 8.0, 2.2), (1.5, 14.5, 1.8)):
        p.pointe(x, z, 16.2, h, 1.4)
        p.pointe(16 - x, z, 16.2, h, 1.4)
    p.pointe(8.0, 14.5, 16.2, 2.0, 1.4)
    # Ailerons sur les tempes : trois lames de pierre veinée qui filent vers l'arrière et le haut, pointes d'or.
    p.miroir([-0.4, 8.6, 4.0], [1.0, 11.4, 10.5], "pierre")
    p.miroir([-0.9, 10.6, 7.5], [0.6, 14.4, 14.0], "pierre")
    p.miroir([-1.4, 13.2, 10.5], [0.2, 17.4, 16.8], "pierre")
    p.miroir([-1.2, 16.8, 14.6], [0.0, 20.2, 17.6], "or")
    p.miroir([-1.6, 14.0, 16.6], [-0.2, 15.2, 18.4], "or")
    p.miroir([-0.8, 9.4, 4.6], [-0.4, 10.6, 5.8], "gemme", True, True)
    m = {"gui_light": "front", "textures": textures("or", "pierre", "veines", "gemme"), "elements": p.veines()}
    m["display"] = affichages_objet({"rotation": [20, 155, 0], "translation": [0, -1.5, 0], "scale": [0.72, 0.72, 0.72]})
    m["display"]["head"] = {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}
    return m


def boite_entite(f, t, U, V, w, h, d, tex, miroir=False, gonfle=0.0, rangs=None, tw=64, th=32, plein_haut=False):
    """Boîte d'entité (gabarit 64 x 32) en bloc de modèle : avant au sud, droite de l'entité vers -X.
    rangs : tranche (haut, bas) de la boîte en pixels du gabarit, pour une boîte coupée (icônes)."""
    r = faces_boite(U, V, w, h, d)
    if rangs:
        r0, r1 = rangs
        for nom in ("front", "back", "right", "left"):
            u, v, ww, _ = r[nom]
            r[nom] = (u, v + r0, ww, r1 - r0)
        if plein_haut:  # dessus d'une boîte coupée : un morceau de la face avant, sinon on verrait à travers
            u, v, ww, _ = r["front"]
            r["top"] = (u, v, ww, d)
    if miroir:
        r["right"], r["left"] = r["left"], r["right"]
    faces = {}
    for nom, mc in (("front", "south"), ("back", "north"), ("right", "west"), ("left", "east"), ("top", "up"), ("bottom", "down")):
        u, v, ww, hh = r[nom]
        u0, u1 = u / tw * 16, (u + ww) / tw * 16
        if miroir:
            u0, u1 = u1, u0
        faces[mc] = {"uv": [round(u0, 4), round(v / th * 16, 4), round(u1, 4), round((v + hh) / th * 16, 4)], "texture": "#" + tex}
    return {"from": [round(a - gonfle, 3) for a in f], "to": [round(b + gonfle, 3) for b in t], "faces": faces}


def ailes_icone():
    """Deux ailes derrière le plastron, écartées vers l'extérieur : leurs lames dépassent sous les bras."""
    els = []
    for x0, x1, angle, miroir in ((12.0, 20.0, 22.5, False), (-4.0, 4.0, -22.5, True)):
        e = boite_entite([x0, -4, 3.0], [x1, 14, 4.0], 22, 0, 10, 20, 2, "ailes", miroir=miroir)
        e["rotation"] = {"angle": angle, "axis": "z", "origin": [x0 if not miroir else x1, 14, 3.5]}
        els.append(e)
    return els


def icone(elements, tex, gui):
    m = {"gui_light": "front", "textures": {k: TEX + k for k in tex} | {"particle": TEX + tex[0]}, "elements": elements}
    m["display"] = affichages_objet(gui)
    return m


def plastron():
    els = [boite_entite([4, 2, 6], [12, 14, 10], 16, 16, 8, 12, 4, "armure", gonfle=1),
           boite_entite([-2, 2, 6], [2, 14, 10], 40, 16, 4, 12, 4, "armure", gonfle=1),
           boite_entite([14, 2, 6], [18, 14, 10], 40, 16, 4, 12, 4, "armure", miroir=True, gonfle=1)]
    return icone(ailes_icone() + els, ["armure", "ailes"], {"rotation": [10, -20, 0], "translation": [0, 1, 0], "scale": [0.5, 0.5, 0.5]})


def jambieres():
    els = [boite_entite([4, 12, 6], [12, 16, 10], 16, 16, 8, 12, 4, "jambieres", gonfle=0.5, rangs=(8, 12)),
           boite_entite([3.9, 0, 6], [7.9, 12, 10], 0, 16, 4, 12, 4, "jambieres", gonfle=0.5),
           boite_entite([8.1, 0, 6], [12.1, 12, 10], 0, 16, 4, 12, 4, "jambieres", miroir=True, gonfle=0.5)]
    return icone(els, ["jambieres"], {"rotation": [10, -25, 0], "translation": [0, 0, 0], "scale": [0.8, 0.8, 0.8]})


def bottes():
    els = [boite_entite([2.5, 3, 6], [6.5, 9, 10], 0, 16, 4, 12, 4, "armure", gonfle=1, rangs=(6, 12), plein_haut=True),
           boite_entite([9.5, 3, 6], [13.5, 9, 10], 0, 16, 4, 12, 4, "armure", miroir=True, gonfle=1, rangs=(6, 12), plein_haut=True)]
    return icone(els, ["armure"], {"rotation": [20, -30, 0], "translation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]})


# ------------------------------------------------------------------------------------- rendu d'aperçu

OMBRE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}
NORMALE = {"up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1), "south": (0, 0, 1),
           "east": (1, 0, 0), "west": (-1, 0, 0)}


def coins(face, f, t):
    """Coins d'une face dans l'ordre des UV (u0v0, u1v0, u1v1, u0v1), comme Minecraft."""
    (x1, y1, z1), (x2, y2, z2) = f, t
    return {
        "north": [(x2, y2, z1), (x1, y2, z1), (x1, y1, z1), (x2, y1, z1)],
        "south": [(x1, y2, z2), (x2, y2, z2), (x2, y1, z2), (x1, y1, z2)],
        "west": [(x1, y2, z1), (x1, y2, z2), (x1, y1, z2), (x1, y1, z1)],
        "east": [(x2, y2, z2), (x2, y2, z1), (x2, y1, z1), (x2, y1, z2)],
        "up": [(x1, y2, z1), (x2, y2, z1), (x2, y2, z2), (x1, y2, z2)],
        "down": [(x1, y1, z2), (x2, y1, z2), (x2, y1, z1), (x1, y1, z1)],
    }[face]


def rot(axe, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return {"x": np.array([[1, 0, 0], [0, c, -s], [0, s, c]]),
            "y": np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]]),
            "z": np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])}[axe]


def pose(elements, A, b):
    """Copie des éléments avec une transformation affine (aperçu seulement) : monde = A . p + b."""
    out = []
    for e in elements:
        e = dict(e)
        A0, b0 = e.get("_A", np.eye(3)), e.get("_b", np.zeros(3))
        e["_A"], e["_b"] = A @ A0, A @ b0 + b
        out.append(e)
    return out


def rendre(elements, textures, vue, taille=512, echelle=None, centre=(0, 0), nuit=False, fond=(139, 139, 139, 255)):
    """Rendu orthographique avec tampon de profondeur ; vue = matrice 3x3 appliquée après la pose."""
    img = np.zeros((taille, taille, 4))
    img[:, :] = fond
    prof = np.full((taille, taille), -1e9)
    ech = echelle or taille / 26.0
    for e in elements:
        R, o = np.eye(3), np.zeros(3)
        if "rotation" in e:
            R, o = rot(e["rotation"]["axis"], e["rotation"]["angle"]), np.array(e["rotation"]["origin"], float)
        A, b = e.get("_A", np.eye(3)), e.get("_b", np.zeros(3))
        lum = e.get("light_emission", 0) > 0
        for fc, d in e["faces"].items():
            n = vue @ A @ R @ np.array(NORMALE[fc], float)
            if n[2] <= 1e-6:
                continue
            n = n / np.linalg.norm(n)
            pts = [vue @ (A @ (R @ (np.array(p, float) - o) + o) + b) for p in coins(fc, e["from"], e["to"])]
            tex = textures[d["texture"][1:]]
            u0, v0, u1, v1 = d["uv"]
            uvs = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
            k = 1.0 if lum else OMBRE[fc] * (0.85 + 0.15 * max(0, n[2]))
            if nuit and not lum:
                k *= 0.18
            for tri in ((0, 1, 2), (0, 2, 3)):
                P = np.array([[(pts[i][0] - centre[0]) * ech + taille / 2, -(pts[i][1] - centre[1]) * ech + taille / 2, pts[i][2]] for i in tri])
                UV = np.array([uvs[i] for i in tri])
                xmin, xmax = int(max(0, P[:, 0].min())), int(min(taille - 1, P[:, 0].max() + 1))
                ymin, ymax = int(max(0, P[:, 1].min())), int(min(taille - 1, P[:, 1].max() + 1))
                if xmin > xmax or ymin > ymax:
                    continue
                xs, ys = np.meshgrid(np.arange(xmin, xmax + 1) + 0.5, np.arange(ymin, ymax + 1) + 0.5)
                (x0, y0), (x1, y1), (x2, y2) = P[0, :2], P[1, :2], P[2, :2]
                den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
                if abs(den) < 1e-9:
                    continue
                a = ((y1 - y2) * (xs - x2) + (x2 - x1) * (ys - y2)) / den
                bb = ((y2 - y0) * (xs - x2) + (x0 - x2) * (ys - y2)) / den
                c = 1 - a - bb
                dedans = (a >= -1e-6) & (bb >= -1e-6) & (c >= -1e-6)
                z = a * P[0, 2] + bb * P[1, 2] + c * P[2, 2]
                u = a * UV[0, 0] + bb * UV[1, 0] + c * UV[2, 0]
                v = a * UV[0, 1] + bb * UV[1, 1] + c * UV[2, 1]
                th, tw = tex.shape[:2]
                tu = np.clip(np.floor(u / 16 * tw).astype(int), 0, tw - 1)
                tv = np.clip(np.floor(v / 16 * th).astype(int), 0, th - 1)
                col = tex[tv, tu]
                sous = prof[ymin:ymax + 1, xmin:xmax + 1]
                ok = dedans & (z > sous) & (col[..., 3] > 0)
                sous[ok] = z[ok]
                zone = img[ymin:ymax + 1, xmin:xmax + 1]
                zone[ok, :3] = col[ok, :3] * k
                zone[ok, 3] = 255
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA")


def uni(h):
    return np.array(Image.new("RGBA", (4, 4), rgb(h) + (255,)), float)


def joueur():
    """Joueur debout (pixels, y vers le haut, de face vers +Z, sa droite vers -X) et son armure portée."""
    corps = [element([-4, 24, -4], [4, 32, 4], "peau", plein=True), element([-4, 12, -2], [4, 24, 2], "chemise", plein=True),
             element([-8, 12, -2], [-4, 24, 2], "chemise", plein=True), element([4, 12, -2], [8, 24, 2], "chemise", plein=True),
             element([-4, 0, -2], [0, 12, 2], "pantalon", plein=True), element([0, 0, -2], [4, 12, 2], "pantalon", plein=True)]
    armure = [boite_entite([-4, 12, -2], [4, 24, 2], 16, 16, 8, 12, 4, "porte", gonfle=1),
              boite_entite([-8, 12, -2], [-4, 24, 2], 40, 16, 4, 12, 4, "porte", gonfle=1),
              boite_entite([4, 12, -2], [8, 24, 2], 40, 16, 4, 12, 4, "porte", miroir=True, gonfle=1),
              boite_entite([-4, 0, -2], [0, 12, 2], 0, 16, 4, 12, 4, "porte", gonfle=1),
              boite_entite([0, 0, -2], [4, 12, 2], 0, 16, 4, 12, 4, "porte", miroir=True, gonfle=1),
              boite_entite([-4, 12, -2], [4, 24, 2], 16, 16, 8, 12, 4, "porte2", gonfle=0.5),
              boite_entite([-4, 0, -2], [0, 12, 2], 0, 16, 4, 12, 4, "porte2", gonfle=0.5),
              boite_entite([0, 0, -2], [4, 12, 2], 0, 16, 4, 12, 4, "porte2", miroir=True, gonfle=0.5)]
    # Casque : rendu d'objet sur la tête (échelle 0,625, demi-tour, centre du modèle au centre de la tête).
    tete = pose(casque()["elements"], np.diag([-0.625, 0.625, -0.625]), np.array([5.0, 23.0, 5.0]))
    # Ailes : modèle de l'élytre au repos (15° vers l'arrière, 15° vers l'extérieur), 2 pixels derrière le dos.
    M = np.diag([1.0, -1.0, -1.0])
    ailes = []
    for x0, x1, tx, zr, miroir in ((-10, 0, 5, -15, False), (0, 10, -5, 15, True)):
        e = boite_entite([x0, -20, -2], [x1, 0, 0], 22, 0, 10, 20, 2, "ailes_p", miroir=miroir)
        R = rot("z", zr) @ rot("x", 15)
        ailes += pose([e], M @ R @ M, M @ np.array([tx, 0.0, 2.0]) + np.array([0, 24.0, 0]))
    return corps + armure + tete + ailes


def apercus(planches, armures, dossier):
    os.makedirs(dossier, exist_ok=True)

    def image(nom, f=0):
        p = np.array(planches[nom], float)
        return p[f * T:(f + 1) * T] if p.shape[0] > T else p

    def tex(f=4):
        return {"or": image("or"), "manche": image("manche"), "pierre": image("pierre"), "veines": image("veines", f % 16),
                "gemme": image("gemme", f % 20), "armure": np.array(armures["armure"], float),
                "jambieres": np.array(armures["jambieres"], float), "ailes": np.array(armures["ailes"], float),
                "porte": np.array(armures["armure"], float), "porte2": np.array(armures["jambieres"], float),
                "ailes_p": np.array(armures["ailes"], float), "peau": uni("#C8956D"), "chemise": uni("#3D7A7A"),
                "pantalon": uni("#3B4FA0")}

    # Joueur en armure : de face, trois quarts, de dos, trois quarts dos, et de nuit.
    scene = joueur()
    vues = [rot("y", 0), rot("x", 10) @ rot("y", 35), rot("y", 180), rot("x", 10) @ rot("y", 145)]
    planche = Image.new("RGBA", (400 * 5, 520))
    for i, v in enumerate(vues):
        planche.paste(rendre(scene, tex(), v, taille=520, echelle=12, centre=(0, 17)).crop((60, 0, 460, 520)), (400 * i, 0))
    planche.paste(rendre(scene, tex(), vues[1], taille=520, echelle=12, centre=(0, 17), nuit=True, fond=(6, 4, 10, 255)).crop((60, 0, 460, 520)), (1600, 0))
    planche.save(os.path.join(dossier, "set_porte.png"))

    # Icônes d'inventaire : affichage gui de chaque modèle (centre 8, 8, 8, translation, rotation, échelle).
    modeles = {"pioche": pioche(), "casque": casque(), "plastron": plastron(), "jambieres": jambieres(), "bottes": bottes()}
    planche = Image.new("RGBA", (256 * 5, 256))
    for i, (nom, m) in enumerate(modeles.items()):
        g = m["display"].get("gui", {})
        r = g.get("rotation", [0, 0, 0])
        s = g.get("scale", [1, 1, 1])[0]
        A = rot("x", r[0]) @ rot("y", r[1]) @ rot("z", r[2]) * s
        els = pose(m["elements"], A, np.array(g.get("translation", [0, 0, 0]), float) - A @ np.array([8.0, 8.0, 8.0]))
        im = rendre(els, tex(), np.eye(3), taille=256, echelle=256 / 18, fond=(139, 139, 139, 255))
        planche.paste(im, (256 * i, 0))
    planche.save(os.path.join(dossier, "icones.png"))

    # Casque seul, de près, et animation.
    c = casque()["elements"]
    planche = Image.new("RGBA", (420 * 3, 420))
    for i, v in enumerate([rot("y", 180), rot("x", 15) @ rot("y", 145), rot("x", 15) @ rot("y", 230)]):
        planche.paste(rendre(pose(c, np.eye(3), -np.array([8.0, 11.0, 8.0])), tex(), v, taille=420, echelle=420 / 24), (420 * i, 0))
    planche.save(os.path.join(dossier, "casque.png"))
    images = [rendre(pose(c, np.eye(3), -np.array([8.0, 11.0, 8.0])), tex(f), rot("x", 15) @ rot("y", 155), taille=320, echelle=320 / 24).convert("RGB")
              for f in range(0, 80, 2)]
    images[0].save(os.path.join(dossier, "casque_anim.gif"), save_all=True, append_images=images[1:], duration=100, loop=0)
    for nom, p in list(planches.items()) + list(armures.items()):
        p.resize((p.width * 6, p.height * 6), Image.NEAREST).save(os.path.join(dossier, "tex_" + nom + ".png"))


# ------------------------------------------------------------------------------------------- écriture

def ecrire_json(chemin, donnees):
    os.makedirs(os.path.dirname(chemin), exist_ok=True)
    with open(chemin, "w") as fh:
        json.dump(donnees, fh, indent=1, ensure_ascii=False)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    dest = sys.argv[1]
    pack = os.path.join(dest, "resourcepack/assets", NS)
    texdir = os.path.join(pack, "textures/item/phenomene")
    ancien = os.path.join(pack, "textures/item/pioche_phenomene")  # avant le set (2026-10-25)
    if os.path.isdir(ancien):
        shutil.rmtree(ancien)
    os.makedirs(texdir, exist_ok=True)

    planches = {"or": tex_or(), "manche": tex_manche(), "pierre": tex_pierre(), "veines": tex_veines(), "gemme": tex_gemme()}
    armures = {"armure": tex_armure(), "jambieres": tex_jambieres(), "ailes": tex_ailes()}
    for nom, im in list(planches.items()) + list(armures.items()):
        im.save(os.path.join(texdir, nom + ".png"))
    for nom, tempo in {"veines": 3, "gemme": 2}.items():
        with open(os.path.join(texdir, nom + ".png.mcmeta"), "w") as fh:
            json.dump({"animation": {"frametime": tempo, "interpolate": True}}, fh, indent=2)
    for calque, nom in (("humanoid", "armure"), ("humanoid_leggings", "jambieres"), ("wings", "ailes")):
        d = os.path.join(pack, "textures/entity/equipment", calque)
        os.makedirs(d, exist_ok=True)
        armures[nom].save(os.path.join(d, "phenomene.png"))
    ecrire_json(os.path.join(pack, "equipment/phenomene.json"), {"layers": {
        "humanoid": [{"texture": NS + ":phenomene"}],
        "humanoid_leggings": [{"texture": NS + ":phenomene"}],
        "wings": [{"texture": NS + ":phenomene"}]}})

    credit = "Influencraft, généré par plugins/FluxItems/assets/modeles/gen_phenomene.py"
    modeles = {"pioche_phenomene": pioche(), "casque_phenomene": casque(), "plastron_phenomene": plastron(),
               "jambieres_phenomene": jambieres(), "bottes_phenomene": bottes()}
    for nom, m in modeles.items():
        ecrire_json(os.path.join(pack, "models/item", nom + ".json"), {"credit": credit} | m)
        ecrire_json(os.path.join(pack, "items", nom + ".json"), {"model": {"type": "minecraft:model", "model": NS + ":item/" + nom}})

    os.makedirs(os.path.join(dest, "configs"), exist_ok=True)
    with open(os.path.join(dest, "configs/items.yml"), "w") as fh:
        fh.write(
            "# Généré par plugins/FluxItems/assets/modeles/gen_phenomene.py : ne pas modifier à la main.\n"
            "# Modèles 3D du set phénomène. Les vrais objets portent le composant item_model (influoutils:<id>,\n"
            "# cf. resourcepack/assets/influoutils/items), posé par FluxItems ; l'objet ItemsAdder ci-dessous\n"
            "# reprend le modèle de la pioche pour l'aperçu de sa caisse (itemsadder:influoutils__<id>) et /iagive.\n"
            "info:\n"
            "  namespace: " + NS + "\n"
            "items:\n"
            "  pioche_phenomene:\n"
            "    display_name: '&#F0A800&lPioche phénomène'\n"
            "    resource:\n"
            "      material: NETHERITE_PICKAXE\n"
            "      generate: false\n"
            "      model_path: item/pioche_phenomene\n"
        )

    if len(sys.argv) > 2:
        apercus(planches, armures, sys.argv[2])
    print("Set phénomène écrit dans", dest)


if __name__ == "__main__":
    main()
