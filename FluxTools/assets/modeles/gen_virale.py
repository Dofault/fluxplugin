#!/usr/bin/env python3
"""Set viral en 3D (Labs) : pioche, pelle, hache, houe et épée FluxTools, armure EcoArmor, modèles et textures.

    <venv avec Pillow et numpy>/python3 gen_virale.py <contents/influoutils> [dossier d'aperçus]

Même pack que le set phénomène (gen_phenomene.py, dont on reprend le moteur d'aperçu) :
- resourcepack/assets/influoutils/textures/item/virale/ : textures 32 x 32 (acier bleu, acier sombre, argent,
  bois, poignée rubanée), et calques animés et lumineux (cristal, liseré néon, réseau qui se propage) ;
- models/item/<outil>_virale.json : outils dessinés debout puis couchés en diagonale, comme une icône vanilla.
  Minecraft ne tourne un bloc que par pas de 22,5° entre -45° et 45° : chaque bloc est posé avec l'angle total
  (le sien + l'inclinaison de l'outil), ramené dans cet intervalle en échangeant largeur et hauteur ;
- models/item/casque_virale.json : casque porté tel quel sur la tête (grand V, yeux néon, crête, ailerons) ;
  icônes 3D du plastron, des jambières et des bottes (boîtes du joueur, mêmes textures que l'armure portée) ;
- items/<pièce>_virale.json : définitions d'objets, cibles du composant item_model (clé `modele:` de l'outil,
  FluxTools/apparences.yml pour l'armure) ;
- equipment/virale.json et textures/entity/equipment/{humanoid,humanoid_leggings}/virale.png : armure portée
  (gabarit 64 x 32 du joueur, pixels doublés : plaques biseautées et néons en V) : le corps n'a ni animation
  ni émission ;
et configs/virale.yml : objets ItemsAdder pour l'aperçu des caisses et /iagive.
Relancer après toute modification, puis /iazip sur Labs.
"""
import json
import math
import os
import random
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_phenomene import (FACES, K, uv_face, rendre, rot, pose, bruit, palette, rgb, ecrire_json,  # noqa: E402
                           faces_boite, peindre, boite_entite, affichages_objet)

NS = "influoutils"
TEX = NS + ":item/virale/"
T = 32
G = 8.0       # pivot de l'inclinaison
INCL = -45.0  # outil couché en diagonale, tête en haut à droite

# Palier Virale : bleu des caisses (#5A85EA), cristaux cyan, argent froid.
ACIER = ["#0A1233", "#13215A", "#1C3380", "#2848A6", "#3A62C9", "#5A85EA", "#86A9F4", "#BFD3FF"]
SOMBRE = ["#050817", "#0A1230", "#111C46", "#18285C", "#213673", "#2C4690"]
ARGENT = ["#2E3548", "#4A5470", "#6E7A96", "#97A3BD", "#C3CCDD", "#E8EEF7", "#FFFFFF"]
BOIS = ["#1B2030", "#252B40", "#303750", "#3B4361", "#474F72"]
CRISTAL = ["#0B3A78", "#1260B8", "#1F8AE8", "#3DB2FF", "#7FD4FF", "#BFEBFF", "#FFFFFF"]

# --------------------------------------------------------------------------------------- textures


def tex_acier():
    """Acier bleu brossé, plus clair en haut, quelques éclats."""
    r = random.Random(101)
    n = bruit(102, octaves=((2, 0.5), (32, 0.5)))
    stries = [r.uniform(-0.1, 0.1) for _ in range(T)]
    im = Image.new("RGBA", (T, T))
    for y in range(T):
        for x in range(T):
            v = 0.78 - 0.5 * y / (T - 1) + stries[y] + 0.2 * (n[y][x] - 0.5)
            im.putpixel((x, y), palette(ACIER[2:8], v))
    for _ in range(8):
        im.putpixel((r.randrange(T), r.randrange(T)), rgb(ACIER[7]) + (255,))
    return im


def tex_uni(couleurs, seed, expo=1.0):
    n = bruit(seed)
    im = Image.new("RGBA", (T, T))
    for y in range(T):
        for x in range(T):
            im.putpixel((x, y), palette(couleurs, n[y][x] ** expo))
    return im


def tex_argent():
    """Argent poli : bandes de reflet horizontales."""
    n = bruit(111, octaves=((2, 0.4), (16, 0.6)))
    tons = [0.95, 0.8, 0.55, 0.35, 0.5, 0.75, 0.9, 0.65]
    im = Image.new("RGBA", (T, T))
    for y in range(T):
        for x in range(T):
            im.putpixel((x, y), palette(ARGENT[1:], tons[(y // 2) % 8] + 0.15 * (n[y][x] - 0.5)))
    return im


def tex_poignee():
    """Ruban argenté enroulé en marches sur fond bleu nuit, liseré sombre entre deux tours."""
    im = Image.new("RGBA", (T, T))
    for y in range(T):
        for x in range(T):
            k = (y + x // 3) % 6
            c = ARGENT[5] if k < 2 else ARGENT[3] if k == 2 else SOMBRE[1] if k == 5 else ACIER[3]
            im.putpixel((x, y), rgb(c) + (255,))
    return im


def pistes(seed, n, departs, pas):
    """Pistes de circuit (pas droits, virages à 90°) avec leur distance au départ, et plots aux extrémités."""
    r = random.Random(seed)
    pts, plots = {}, []
    for depart in range(departs):
        x, y = r.randrange(n), r.randrange(n)
        d = r.choice([(1, 0), (0, 1), (-1, 0), (0, -1)])
        s = depart * 5.0
        plots.append((x, y, s))
        for i in range(r.randint(*pas)):
            pts.setdefault((x % n, y % n), s)
            if i % 4 == 3 and r.random() < 0.6:
                d = (d[1], d[0]) if r.random() < 0.5 else (-d[1], -d[0])
            x, y, s = x + d[0], y + d[1], s + 1
        plots.append((x, y, s))
    return pts, plots


PISTES, PLOTS = pistes(131, T, 5, (12, 22))


def tex_reseau(frames=16):
    """Calque transparent : une impulsion part de chaque plot et court le long des pistes, comme une contagion."""
    planche = Image.new("RGBA", (T, T * frames), (0, 0, 0, 0))
    for f in range(frames):
        im = Image.new("RGBA", (T, T), (0, 0, 0, 0))
        t = f / frames
        for (x, y), s in PISTES.items():
            u = (t * 24 - s) % 24
            v = max(0.0, 1 - u / 6)
            im.putpixel((x, y), palette(CRISTAL[1:], 0.2 + 0.8 * v))
        for x, y, s in PLOTS:
            v = max(0.0, 1 - ((t * 24 - s) % 24) / 4)
            for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
                im.putpixel(((x + dx) % T, (y + dy) % T), palette(CRISTAL[3:], 0.3 + 0.7 * v))
        planche.paste(im, (0, f * T))
    return planche


def tex_cristal(frames=20):
    """Cristal taillé : anneaux carrés, facettes claires en haut à gauche, éclat qui tourne autour de la table."""
    planche = Image.new("RGBA", (T, T * frames))
    c = (T - 1) / 2
    for f in range(frames):
        im = Image.new("RGBA", (T, T))
        a = 2 * math.pi * f / frames
        ex, ey = c + 7 * math.cos(a), c + 7 * math.sin(a)
        for y in range(T):
            for x in range(T):
                dx, dy = (x - c) / c, (y - c) / c
                d = max(abs(dx), abs(dy))
                if d > 0.86:
                    v = 0.15
                elif d < 0.3:
                    v = 0.85
                else:
                    v = 0.4 + 0.3 * (-(dx + dy) / (2 * d)) + 0.1 * (d < 0.6)
                e = math.hypot(x - ex, y - ey)
                if e < 3.5 and d <= 0.86:
                    v = min(1.0, v + 0.5 * (1 - e / 3.5))
                im.putpixel((x, y), palette(CRISTAL, v))
        planche.paste(im, (0, f * T))
    return planche


def tex_lueur(frames=16):
    """Liseré néon : cyan vif parcouru d'une vague lumineuse."""
    planche = Image.new("RGBA", (T, T * frames))
    for f in range(frames):
        im = Image.new("RGBA", (T, T))
        for y in range(T):
            for x in range(T):
                v = 0.5 + 0.5 * math.sin(2 * math.pi * ((x + y) / T - f / frames))
                im.putpixel((x, y), palette(CRISTAL[2:], 0.3 + 0.7 * v))
        planche.paste(im, (0, f * T))
    return planche


# ---------------------------------------------------------------------------------------- modèles


def tourne(x, y, deg):
    a = math.radians(deg)
    return x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a)


class Outil:
    """Blocs dessinés debout (manche selon Y en x = 8), posés couchés en diagonale ; calque réseau sur l'acier."""

    def __init__(self):
        self.elements = []
        self.acier = []

    def boite(self, cx, cy, w, h, z1, z2, tex, angle=0.0, lum=False, plein=False, sym=False, reseau=True):
        if sym:
            self.boite(16 - cx, cy, w, h, z1, z2, tex, -angle, lum, plein, False, reseau)
        lum = lum or tex in ("cristal", "lueur")
        th = (angle + 90) % 180 - 90 + INCL  # une boîte retournée de 180° est la même boîte
        while th < -45:
            th, w, h = th + 90, h, w
        while th > 45:
            th, w, h = th - 90, h, w
        dx, dy = tourne(cx - G, cy - G, INCL)
        x, y = G + dx, G + dy
        rotation = {"angle": th, "axis": "z", "origin": [round(x, 3), round(y, 3), 8]} if abs(th) > 1e-6 else None
        f, t = [x - w / 2, y - h / 2, z1], [x + w / 2, y + h / 2, z2]
        self.elements.append(element(f, t, tex, lum, plein, rotation))
        if tex == "acier" and reseau:
            self.acier.append((f, t, rotation))

    def bras(self, x, y, pas, z1, z2, tex="acier", sym=True, neon=True, cote=None):
        """Chaîne de segments (direction en degrés, longueur, épaisseur) depuis (x, y) ; liseré néon côté extérieur."""
        for ang, lg, ep in pas:
            ux, uy = math.cos(math.radians(ang)), math.sin(math.radians(ang))
            cx, cy = x + ux * lg / 2, y + uy * lg / 2
            self.boite(cx, cy, lg + 0.5, ep, z1, z2, tex, ang, sym=sym)
            if neon:
                nx, ny = -uy, ux  # normale à gauche du sens de marche : le dessus pour un bras vers la droite
                if cote == "droite" or (cote is None and (ux < -1e-6 or (abs(ux) < 1e-6 and uy < 0))):
                    nx, ny = -nx, -ny  # cote : côté du liseré dans le sens de marche (extérieur d'une courbe)
                o = ep / 2 + 0.18
                self.boite(cx + nx * o, cy + ny * o, lg + 0.3, 0.36, z1 + 0.25, z2 - 0.25, "lueur", ang, lum=True, sym=sym)
            x, y = x + ux * lg, y + uy * lg
        return x, y

    def gemme(self, x, y, taille, z1, z2, cadre=True, angle=45.0, sym=False):
        """Cristal lumineux, serti dans un cadre d'argent un peu plus large et moins épais."""
        if cadre:
            self.boite(x, y, taille * 1.55, taille * 1.55, z1 + 0.25, z2 - 0.25, "argent", angle, sym=sym)
        self.boite(x, y, taille, taille, z1, z2, "cristal", angle, lum=True, plein=True, sym=sym)

    def manche(self, bas, haut_poignee, haut, pompons=None):
        """Pommeau de cristal, poignée rubanée entre deux bagues, bois sombre jusqu'à la tête, pompons à cordon."""
        self.boite(8, bas - 0.2, 1.3, 1.3, 7.35, 8.65, "cristal", 45, lum=True, plein=True)
        self.boite(8, bas + 0.7, 2.6, 1.4, 6.7, 9.3, "argent")
        self.boite(8, bas + 1.45, 2.0, 0.5, 7.0, 9.0, "sombre")
        self.boite(8, (bas + 1.6 + haut_poignee) / 2, 1.7, haut_poignee - bas - 1.6, 7.15, 8.85, "poignee")
        self.boite(8, haut_poignee + 0.35, 2.2, 0.8, 6.9, 9.1, "argent")
        self.boite(8, (haut_poignee + 0.7 + haut) / 2, 1.45, haut - haut_poignee - 0.7, 7.27, 8.73, "bois")
        if pompons:
            y = pompons
            self.boite(9.25, y - 0.9, 0.4, 1.8, 7.8, 8.2, "sombre", sym=True)
            self.boite(9.25, y - 2.1, 0.85, 0.85, 7.58, 8.42, "cristal", 45, lum=True, plein=True, sym=True)

    def fini(self):
        for f, t, rotation in self.acier:
            self.elements.append(element([v - 0.02 for v in f], [v + 0.02 for v in t], "reseau", True, False, rotation))
        return self.elements


def element(f, t, tex, lum=False, plein=False, rotation=None):
    e = {"from": [round(v, 3) for v in f], "to": [round(v, 3) for v in t]}
    if rotation:
        e["rotation"] = rotation
    e["faces"] = {fc: {"uv": uv_face(fc, f, t, plein), "texture": "#" + tex} for fc in FACES}
    if lum:
        e["light_emission"] = 15
    return e


def pioche():
    o = Outil()
    o.manche(-2.4, 4.4, 11.8, pompons=11.6)
    o.boite(8, 11.6, 2.8, 0.9, 6.8, 9.2, "argent")
    # Médaillon octogonal (carré + losange d'acier sombre), cristal serti au centre.
    o.boite(8, 13.5, 4.6, 4.6, 6.3, 9.7, "sombre")
    o.boite(8, 13.5, 4.6, 4.6, 6.3, 9.7, "sombre", 45)
    o.gemme(8, 13.5, 2.5, 5.8, 10.2)
    # Bras : à plat, puis plongent par pas de 22,5°, pointes de cristal et petits cristaux sertis.
    fin = o.bras(10.1, 13.7, [(0, 2.6, 2.2), (-22.5, 3.0, 1.9), (-45, 2.2, 1.3)], 6.8, 9.2)
    o.boite(fin[0] + 0.25, fin[1] - 0.25, 1.0, 1.0, 7.3, 8.7, "cristal", -45, lum=True, plein=True, sym=True)
    o.gemme(14.0, 13.1, 0.9, 6.6, 9.4, cadre=False, angle=-22.5, sym=True)
    # Crête au-dessus du médaillon.
    o.boite(8, 16.3, 1.4, 1.4, 7.2, 8.8, "acier", 45)
    o.boite(8, 17.1, 0.7, 0.7, 7.4, 8.6, "cristal", 45, lum=True, plein=True)
    return o.fini()


def pelle():
    o = Outil()
    o.manche(-2.6, 4.2, 10.0)
    o.boite(8, 10.1, 2.6, 1.0, 6.8, 9.2, "argent")
    o.boite(8, 11.1, 2.0, 1.2, 7.0, 9.0, "sombre")
    # Lame : épaules, plaque, haut rétréci et pointe en losange ; bords néon, cristal au centre.
    o.boite(8, 12.0, 6.6, 1.0, 7.1, 8.9, "acier")
    # Lame arrondie : plaque, puis trois rangs de plus en plus étroits ; bords et sommet néon.
    o.boite(8, 14.4, 6.0, 3.8, 7.5, 8.5, "acier")
    o.boite(8, 16.75, 5.0, 0.9, 7.5, 8.5, "acier")
    o.boite(8, 17.5, 3.6, 0.6, 7.5, 8.5, "acier")
    o.boite(8, 18.05, 2.0, 0.5, 7.5, 8.5, "acier")
    o.boite(11.2, 14.5, 0.36, 4.2, 7.6, 8.4, "lueur", sym=True)
    o.boite(10.7, 16.75, 0.36, 0.9, 7.6, 8.4, "lueur", sym=True)
    o.boite(8, 18.48, 2.0, 0.36, 7.6, 8.4, "lueur")
    o.gemme(8, 14.2, 1.5, 7.0, 9.0)
    # Ergots aux épaules.
    o.boite(11.7, 11.8, 1.2, 1.2, 7.2, 8.8, "acier", 45, sym=True)
    o.boite(11.7, 11.8, 0.6, 0.6, 7.0, 9.0, "cristal", 45, lum=True, plein=True, sym=True)
    return o.fini()


def hache():
    o = Outil()
    o.manche(-2.6, 4.4, 15.6, pompons=10.8)
    o.boite(8, 10.7, 2.6, 0.9, 6.8, 9.2, "argent")
    o.boite(8, 13.2, 2.6, 4.4, 6.8, 9.2, "sombre")
    o.boite(8, 15.7, 2.0, 0.8, 6.9, 9.1, "argent")
    # Lame à gauche en croissant : trois étages de plus en plus hauts, tranchant néon, cornes en losange.
    o.boite(5.3, 13.2, 3.2, 3.8, 7.1, 8.9, "acier")
    o.boite(3.15, 13.2, 1.5, 5.6, 7.25, 8.75, "acier")
    o.boite(1.95, 13.2, 1.1, 7.2, 7.35, 8.65, "acier")
    o.boite(1.2, 13.2, 0.4, 6.8, 7.45, 8.55, "lueur", lum=True)
    o.boite(1.9, 16.9, 1.1, 1.1, 7.35, 8.65, "acier", 45)
    o.boite(1.9, 9.5, 1.1, 1.1, 7.35, 8.65, "acier", 45)
    o.gemme(4.4, 13.2, 1.0, 6.9, 9.1, cadre=False)
    # Pic arrière à droite, pointe de cristal.
    o.boite(10.1, 13.2, 1.8, 2.4, 7.0, 9.0, "acier")
    o.boite(11.5, 13.2, 1.2, 1.4, 7.2, 8.8, "acier")
    o.boite(12.4, 13.2, 0.8, 0.8, 7.35, 8.65, "cristal", 45, lum=True, plein=True)
    o.gemme(8, 13.6, 1.4, 6.3, 9.7)
    return o.fini()


def houe():
    o = Outil()
    o.manche(-2.6, 4.4, 13.0)
    # Douille plus large que le manche, bagues d'argent, bout du manche qui dépasse en bouton, cristal serti.
    o.boite(8, 12.8, 2.6, 0.5, 6.9, 9.1, "argent")
    o.boite(8, 14.5, 2.8, 3.0, 7.0, 9.0, "sombre")
    o.boite(8, 16.2, 2.6, 0.5, 6.9, 9.1, "argent")
    o.boite(8, 16.9, 1.4, 0.9, 7.25, 8.75, "bois")
    o.gemme(8, 14.5, 1.1, 6.6, 9.4)
    # Lame : part de la douille vers la gauche, perpendiculaire au manche et large à la racine, puis se recourbe
    # peu à peu vers le bas en s'effilant jusqu'à une pointe de cristal ; liseré néon sur le dos de la courbe.
    fin = o.bras(6.8, 14.5, [(180, 2.2, 2.6), (202.5, 2.2, 2.1), (225, 2.0, 1.6), (247.5, 1.6, 1.0)], 7.5, 8.5,
                 sym=False, cote="droite")
    o.boite(fin[0] - 0.1, fin[1] - 0.2, 0.6, 0.6, 7.6, 8.4, "cristal", 22.5, plein=True)
    return o.fini()


def epee():
    o = Outil()
    o.manche(-2.4, 2.6, 2.7)
    # Garde : bloc central, quillons relevés en pointe, cristaux aux extrémités.
    o.boite(8, 3.5, 2.6, 1.8, 6.8, 9.2, "sombre")
    fin = o.bras(9.2, 3.5, [(0, 1.3, 1.3), (22.5, 1.1, 1.1), (45, 0.9, 0.9)], 7.0, 9.0)
    o.boite(fin[0] + 0.2, fin[1] + 0.2, 0.8, 0.8, 7.3, 8.7, "cristal", 45, lum=True, plein=True, sym=True)
    o.gemme(8, 3.5, 1.3, 6.5, 9.5)
    # Lame : talon, plaque, haut rétréci, pointe ; gouttière néon, tranchants d'argent.
    o.boite(8, 4.8, 3.4, 0.9, 7.2, 8.8, "sombre")
    o.boite(8, 11.0, 3.0, 11.6, 7.4, 8.6, "acier")
    o.boite(8, 17.5, 2.2, 1.6, 7.4, 8.6, "acier")
    o.boite(8, 18.3, 1.56, 1.56, 7.4, 8.6, "acier", 45)
    o.boite(6.6, 11.2, 0.4, 12.0, 7.5, 8.5, "argent", sym=True)
    o.boite(8, 11.4, 0.6, 12.4, 7.3, 8.7, "lueur", lum=True)
    return o.fini()


OUTILS = {"pioche": pioche, "pelle": pelle, "hache": hache, "houe": houe, "epee": epee}
NOMS = {"pioche": "Pioche virale", "pelle": "Pelle virale", "hache": "Hache virale", "houe": "Houe virale", "epee": "Épée virale"}
MATERIAUX = {"pioche": "DIAMOND_PICKAXE", "pelle": "DIAMOND_SHOVEL", "hache": "DIAMOND_AXE", "houe": "DIAMOND_HOE",
             "epee": "DIAMOND_SWORD"}
CLES = ("acier", "sombre", "argent", "bois", "poignee", "cristal", "lueur", "reseau")


def cadrage(elements):
    """Affichage d'inventaire : réduit et recentré pour tenir dans la case (16 x 16, un peu de marge)."""
    pts = []
    for e in elements:
        (x1, y1, _), (x2, y2, _) = e["from"], e["to"]
        r = e.get("rotation")
        for x, y in ((x1, y1), (x1, y2), (x2, y1), (x2, y2)):
            if r:
                ox, oy = r["origin"][:2]
                dx, dy = tourne(x - ox, y - oy, r["angle"])
                x, y = ox + dx, oy + dy
            pts.append((x, y))
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    s = min(1.0, 16.6 / max(max(xs) - min(xs), max(ys) - min(ys)))
    cx, cy = (max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2
    return {"rotation": [0, 0, 0], "translation": [round(-(cx - 8) * s, 3), round(-(cy - 8) * s, 3), 0],
            "scale": [round(s, 3)] * 3}


def modele(elements):
    trois = {"translation": [0, 4.0, 0.5], "scale": [0.85, 0.85, 0.85]}
    premiere = {"translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]}
    return {
        "gui_light": "front",
        "textures": {k: TEX + k for k in CLES} | {"particle": TEX + "acier"},
        "elements": elements,
        # Affichages de minecraft:item/handheld (et item/generated pour le sol, la tête, le cadre).
        "display": {
            "gui": cadrage(elements),
            "thirdperson_righthand": {"rotation": [0, -90, 55]} | trois,
            "thirdperson_lefthand": {"rotation": [0, 90, -55]} | trois,
            "firstperson_righthand": {"rotation": [0, -90, 25]} | premiere,
            "firstperson_lefthand": {"rotation": [0, 90, -25]} | premiere,
            "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
            "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
            "fixed": {"rotation": [0, 180, 0], "scale": [1, 1, 1]},
        },
    }


def verifier(nom, elements):
    """Contraintes du format de modèle : coordonnées dans [-16, 32], angle par pas de 22,5° dans [-45, 45]."""
    for e in elements:
        for v in e["from"] + e["to"]:
            assert -16 <= v <= 32, (nom, e)
        if "rotation" in e:
            a = e["rotation"]["angle"]
            assert -45 <= a <= 45 and abs(a / 22.5 - round(a / 22.5)) < 1e-6, (nom, a)


# -------------------------------------------------------------- armure portée (gabarit 64 x 32, pixels doublés)

# Plaques biseautées (reflet en haut à gauche, ombre en bas à droite) sur fond de creux sombres, néons en V.
CREUX, OMBRE_P, PLAQUE, CLAIR, REFLET = (rgb(h) + (255,) for h in ("#070B1F", "#1A2C6E", "#2848A6", "#3A62C9", "#7FA2F2"))
NEON = [rgb(h) + (255,) for h in ("#1F8AE8", "#3DB2FF", "#BFEBFF")]  # bord, halo, cœur


class Face:
    """Face d'une boîte du gabarit, en pixels doublés (K = 2) : on y pose des plaques, puis des traits néon."""

    def __init__(self, img, rect, fond=CREUX):
        U, V, w, h = rect
        self.img, self.x0, self.y0, self.W, self.H = img, U * K, V * K, w * K, h * K
        if fond:
            self.img[self.y0:self.y0 + self.H, self.x0:self.x0 + self.W] = fond

    def px(self, x, y, c):
        if 0 <= x < self.W and 0 <= y < self.H:
            self.img[self.y0 + y, self.x0 + x] = c

    def plaque(self, x0, y0, x1, y1, fond=PLAQUE, reflet=REFLET, ombre=OMBRE_P):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                c = reflet if (y == y0 or x == x0) else ombre if (y == y1 or x == x1) else fond
                self.px(x, y, c)

    def trait(self, pts, epais=2):
        """Ligne néon brisée (liste de points) : cœur blanc bleuté, halo cyan juste dessous (et à côté si épais)."""
        for (xa, ya), (xb, yb) in zip(pts, pts[1:]):
            n = max(abs(xb - xa), abs(yb - ya))
            for i in range(n + 1):
                x, y = round(xa + (xb - xa) * i / n), round(ya + (yb - ya) * i / n)
                self.px(x, y + 1, NEON[1])
                if epais > 1:
                    self.px(x, y + 2, NEON[0])
            for i in range(n + 1):
                self.px(round(xa + (xb - xa) * i / n), round(ya + (yb - ya) * i / n), NEON[2])

    def ligne(self, y, c=None, x0=0, x1=None):
        for x in range(x0, (self.W - 1 if x1 is None else x1) + 1):
            self.px(x, y, c or NEON[1])

    def zigzag(self, y):
        """Couronne en W : une dent néon sur deux pixels, posée sur une ligne néon."""
        for x in range(self.W):
            if (x // 2) % 2 == 0:
                self.px(x, y, NEON[2])
            self.px(x, y + 1, NEON[1])


def boite(img, U, V, w, h, d, peintres, fond=CREUX):
    for nom, rect in faces_boite(U, V, w, h, d).items():
        if nom in peintres:
            peintres[nom](Face(img, rect, fond if peintres[nom] is not None else None))


def tex_armure():
    """Calque humanoid : plastron (torse, bras) et bottes (bas des jambes). Tête vide : casque en 3D."""
    img = np.zeros((32 * K, 64 * K, 4), np.uint8)

    def torse_avant(f):
        f.plaque(0, 0, 4, 3, CLAIR)
        f.plaque(11, 0, 15, 3, CLAIR)
        f.plaque(1, 3, 14, 11, CLAIR)  # les V sont en relief (parure_plastron)
        f.plaque(2, 13, 13, 15)
        f.plaque(3, 17, 12, 19)
        f.ligne(21)
        f.ligne(22, NEON[0])
        for x in range(1, 16, 3):
            f.px(x, 21, NEON[2])

    def torse_dos(f):
        f.plaque(0, 0, 15, 3, CLAIR)
        f.plaque(1, 5, 6, 12)
        f.plaque(9, 5, 14, 12)
        f.plaque(7, 4, 8, 19, CLAIR)
        f.trait([(1, 5), (6, 10)], 1)
        f.trait([(14, 5), (9, 10)], 1)
        f.plaque(2, 14, 6, 19)
        f.plaque(9, 14, 13, 19)
        f.ligne(21)
        f.ligne(22, NEON[0])

    def torse_cote(f):
        f.plaque(0, 0, 7, 3, CLAIR)
        f.plaque(1, 5, 6, 12)
        f.plaque(1, 14, 6, 19)
        f.ligne(21)
        f.ligne(22, NEON[0])

    def uni(c):
        return lambda f: f.plaque(0, 0, f.W - 1, f.H - 1, c, c, c)

    def epaule(f, chevron):
        f.plaque(0, 0, 7, 7, CLAIR)
        if chevron:
            f.trait([(1, 1), (3, 4)], 1)
            f.trait([(6, 1), (4, 4)], 1)
        f.ligne(7, NEON[2])
        f.ligne(8, NEON[0])
        f.plaque(1, 10, 6, 15)
        f.plaque(0, 17, 7, 21, CLAIR)
        f.ligne(19, NEON[1])

    def bras_dessus(f):
        f.plaque(0, 0, 7, 7, CLAIR)
        for i in range(8):
            f.px(i, 0, NEON[1])
            f.px(0, i, NEON[1])

    def botte(f):
        f.zigzag(13)
        f.plaque(0, 15, 7, 19)
        f.plaque(0, 20, 7, 21, CLAIR)
        f.ligne(22, CREUX)
        f.ligne(23, CREUX)

    boite(img, 16, 16, 8, 12, 4, {"front": torse_avant, "back": torse_dos, "right": torse_cote, "left": torse_cote,
                                  "top": uni(CLAIR), "bottom": uni(CREUX)})
    boite(img, 40, 16, 4, 12, 4, {"front": lambda f: epaule(f, False), "back": lambda f: epaule(f, False),
                                  "right": lambda f: epaule(f, True), "left": lambda f: epaule(f, True),
                                  "top": bras_dessus, "bottom": uni(CREUX)})
    # Bottes : seulement le bas de la jambe, le haut reste transparent.
    for nom, rect in faces_boite(0, 16, 4, 12, 4).items():
        if nom == "bottom":
            Face(img, rect).plaque(0, 0, 7, 7, CREUX, OMBRE_P, CREUX)
        elif nom != "top":
            f = Face(img, rect, None)
            botte(f)
    return Image.fromarray(img, "RGBA")


def tex_jambieres():
    """Calque humanoid_leggings : ceinture néon à pans en V, jambières à genouillères en chevron."""
    img = np.zeros((32 * K, 64 * K, 4), np.uint8)
    for nom, rect in faces_boite(16, 16, 8, 12, 4).items():
        if nom in ("top", "bottom"):
            continue
        f = Face(img, rect, None)
        for y in range(16, 24):
            f.ligne(y, CREUX)
        f.plaque(0, 16, f.W - 1, 18, CLAIR)
        f.ligne(17, NEON[1])
        if nom == "front":
            f.plaque(1, 19, 6, 23)
            f.plaque(9, 19, 14, 23)
            f.trait([(2, 19), (6, 23)], 1)
            f.trait([(13, 19), (9, 23)], 1)
        else:
            f.plaque(1, 19, f.W - 2, 23)

    def jambe(f, devant):
        f.plaque(0, 0, 7, 5, CLAIR)
        f.plaque(1, 7, 6, 13, CLAIR if devant else PLAQUE)
        if devant:
            f.trait([(1, 8), (3, 11)], 1)
            f.trait([(6, 8), (4, 11)], 1)
        else:
            for y in range(7, 14):
                f.px(3, y, NEON[1])
        f.plaque(1, 15, 6, 22)

    for nom, rect in faces_boite(0, 16, 4, 12, 4).items():
        if nom == "top":
            Face(img, rect).plaque(0, 0, 7, 7, CLAIR)
        elif nom != "bottom":
            jambe(Face(img, rect), nom == "front")
    return Image.fromarray(img, "RGBA")


# Casque : textures 16 x 16 posées en entier sur chaque face (plaques biseautées comme sur le corps).
def tex_biseau(fond, reflet, ombre):
    im = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            im.putpixel((x, y), reflet if (x == 0 or y == 0) else ombre if (x == 15 or y == 15) else fond)
    return im


def tex_casque_neon(frames=12):
    planche = Image.new("RGBA", (16, 16 * frames))
    for f in range(frames):
        for y in range(16):
            for x in range(16):
                v = 0.5 + 0.5 * math.sin(2 * math.pi * ((x + y) / 16 - f / frames))
                planche.putpixel((x, y + 16 * f), palette(CRISTAL[3:], 0.35 + 0.65 * v))
    return planche


class Blocs:
    """Blocs d'un modèle porté (sans inclinaison), symétrie gauche/droite, calque réseau sur l'acier."""

    def __init__(self):
        self.elements, self.acier = [], []

    def __call__(self, f, t, tex, plein=False, rotation=None, reseau=True):
        self.elements.append(element(f, t, tex, tex in ("cristal", "lueur", "c_neon"), plein, rotation))
        if tex == "acier" and reseau:
            self.acier.append((f, t, rotation))

    def miroir(self, f, t, tex, plein=False, rotation=None, reseau=True):
        self(f, t, tex, plein, rotation, reseau)
        r = None
        if rotation:
            r = dict(rotation, origin=[16 - rotation["origin"][0]] + rotation["origin"][1:])
            if r["axis"] != "x":
                r["angle"] = -r["angle"]
        self([16 - t[0], f[1], f[2]], [16 - f[0], t[1], t[2]], tex, plein, r, reseau)

    def fini(self):
        for f, t, rotation in self.acier:
            self.elements.append(element([v - 0.02 for v in f], [v + 0.02 for v in t], "reseau", True, False, rotation))
        return self.elements


def casque():
    """Porté tel quel sur la tête (sans calque d'armure) : la tête du joueur occupe 1,6 à 14,4, face au nord."""
    b = Blocs()
    P, C, N, L = "c_plaque", "c_clair", "c_noir", "c_neon"
    # Coque : dôme en deux étages, nuque, côtés, front ; arcade claire en saillie.
    b([0.8, 14.4, 0.8], [15.2, 15.4, 15.2], P, True)
    b([2.2, 15.4, 2.2], [13.8, 16.0, 13.8], P, True)
    b([0.8, 3.0, 14.4], [15.2, 14.4, 15.2], P, True)
    b.miroir([0.8, 3.0, 1.6], [1.6, 14.4, 14.4], P, True)
    b([0.8, 10.0, 0.8], [15.2, 14.4, 1.6], P, True)
    b([1.2, 10.0, 0.2], [14.8, 11.0, 0.8], C, True)
    # Visage en T : fente des yeux (deux yeux néon) et fente de la bouche, joues en deux épaisseurs.
    b([4.0, 8.0, 1.2], [12.0, 10.0, 1.6], N, True)
    b.miroir([4.6, 8.5, 1.0], [7.0, 9.5, 1.25], L)
    b.miroir([0.8, 8.0, 0.8], [4.0, 10.0, 1.6], P, True)
    b.miroir([0.8, 3.0, 0.8], [6.8, 8.0, 1.6], P, True)
    b.miroir([0.6, 3.0, 0.2], [5.6, 6.8, 0.8], C, True)
    b([6.8, 3.0, 1.2], [9.2, 8.0, 1.6], N, True)
    # Grand V : deux lames claires du front jusqu'au-dessus de la tête, néon sur le devant, joyau à la pointe.
    for cx, a in ((4.3, -45), (11.7, 45)):
        r = {"angle": a, "axis": "z", "origin": [cx, 14.3, 0.2]}
        b([cx - 5.5, 13.3, -0.4], [cx + 5.5, 15.3, 0.8], C, True, r)
        b([cx - 5.0, 14.0, -0.6], [cx + 5.0, 14.6, -0.4], L, rotation=r)
    b([7.0, 10.0, -0.7], [9.0, 12.0, 0.9], L, rotation={"angle": 45, "axis": "z", "origin": [8, 11, 0.1]})
    # Crête du dessus, liseré néon.
    b([7.0, 16.0, 2.5], [9.0, 17.0, 13.5], P, True)
    b([7.5, 17.0, 3.0], [8.5, 17.3, 13.0], L)
    # Cornes sur les tempes : partent vers l'extérieur, se relèvent, pointe néon.
    b.miroir([-2.6, 10.6, 6.0], [1.0, 12.6, 10.0], C, True, {"angle": -22.5, "axis": "z", "origin": [1.0, 11.6, 8.0]})
    r = {"angle": 22.5, "axis": "z", "origin": [-2.4, 12.4, 8.0]}
    b.miroir([-3.3, 12.0, 6.9], [-1.5, 17.2, 9.1], C, True, r)
    b.miroir([-3.0, 17.2, 7.2], [-1.8, 19.0, 8.8], L, rotation=r)
    # Liseré néon au bas du casque, tout autour, et protège-nuque.
    b([0.6, 2.6, 0.6], [15.4, 3.0, 1.0], L)
    b([0.6, 2.6, 15.0], [15.4, 3.0, 15.4], L)
    b.miroir([0.6, 2.6, 1.0], [1.0, 3.0, 15.0], L)
    b([1.6, 1.6, 14.6], [14.4, 2.6, 15.4], P, True)
    m = {"gui_light": "front", "textures": {k: TEX + k for k in CLES_CASQUE} | {"particle": TEX + "c_plaque"},
         "elements": b.fini()}
    m["display"] = affichages_objet({"rotation": [20, 155, 0], "translation": [0, -1.0, 0], "scale": [0.58, 0.58, 0.58]})
    m["display"]["head"] = {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}
    return m


CLES_CASQUE = ("c_plaque", "c_clair", "c_noir", "c_neon")


def icone(elements, tex, gui):
    m = {"gui_light": "front", "textures": {k: TEX + k for k in tex} | {"particle": TEX + tex[0]}, "elements": elements}
    m["display"] = affichages_objet(gui)
    return m


def plastron():
    els = [boite_entite([4, 2, 6], [12, 14, 10], 16, 16, 8, 12, 4, "armure", gonfle=1),
           boite_entite([-2, 2, 6], [2, 14, 10], 40, 16, 4, 12, 4, "armure", gonfle=1),
           boite_entite([14, 2, 6], [18, 14, 10], 40, 16, 4, 12, 4, "armure", miroir=True, gonfle=1)]
    return icone(els, ["armure"], {"rotation": [10, -20, 0], "translation": [0, 1, 0], "scale": [0.55, 0.55, 0.55]})


def jambieres():
    els = [boite_entite([4, 12, 6], [12, 16, 10], 16, 16, 8, 12, 4, "jambieres", gonfle=0.5, rangs=(8, 12)),
           boite_entite([3.9, 0, 6], [7.9, 12, 10], 0, 16, 4, 12, 4, "jambieres", gonfle=0.5),
           boite_entite([8.1, 0, 6], [12.1, 12, 10], 0, 16, 4, 12, 4, "jambieres", miroir=True, gonfle=0.5)]
    return icone(els, ["jambieres"], {"rotation": [10, -25, 0], "translation": [0, 0, 0], "scale": [0.8, 0.8, 0.8]})


def bottes():
    els = [boite_entite([2.5, 3, 6], [6.5, 9, 10], 0, 16, 4, 12, 4, "armure", gonfle=1, rangs=(6, 12), plein_haut=True),
           boite_entite([9.5, 3, 6], [13.5, 9, 10], 0, 16, 4, 12, 4, "armure", miroir=True, gonfle=1, rangs=(6, 12), plein_haut=True)]
    return icone(els, ["armure"], {"rotation": [20, -30, 0], "translation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]})


# ------------------------------------------------------------------ parures (pièces 3D suivies par FluxTools)
# Repère : un pixel du skin = une unité, torse du joueur en x 4..12, y 2..14, z 6..10 (centre 8, 8, 8, celui de
# l'entité d'affichage), devant vers +Z ; bras en x 0..4 et 12..16. L'armure portée est gonflée d'un pixel.


def v_trois(b, apex_y, longueur, epais, z):
    """Chevron en trois épaisseurs (plaque, bande claire, néon) dont la pointe est en bas, en (8, apex_y)."""
    for a, sx in ((45, 1), (-45, -1)):
        cx, cy = 8 + sx * longueur / 2 * 0.7071, apex_y + longueur / 2 * 0.7071
        r = {"angle": a, "axis": "z", "origin": [round(cx, 3), round(cy, 3), z]}
        dz = 1 if z > 8 else -1
        for ep, z0, z1, tex in ((epais, 0.0, 1.6, "c_plaque"), (epais * 0.45, 1.6, 2.2, "c_clair"), (0.4, 2.2, 2.4, "c_neon")):
            za, zb = sorted((z + dz * z0, z + dz * z1))
            b([cx - longueur / 2, cy - ep / 2, za], [cx + longueur / 2, cy + ep / 2, zb], tex, tex != "c_neon", r)


def parure_plastron():
    b = Blocs()
    # Grand V des épaules au sternum, petit V sur le ventre, même chose plus petit dans le dos.
    v_trois(b, 6.4, 9.0, 2.6, 11.0)
    v_trois(b, 2.6, 5.0, 1.8, 11.0)
    v_trois(b, 7.4, 7.0, 2.2, 5.0)
    # Gorgerin sous le menton.
    b([4.6, 13.0, 10.4], [11.4, 15.4, 12.0], "c_plaque", True)
    b([5.4, 14.6, 11.9], [10.6, 15.2, 12.2], "c_neon")
    # Épaulières : trois plaques en escalier qui descendent vers l'extérieur, néon au bas de la dernière.
    for x0, x1, y0, y1, z0, z1, tex in ((10.4, 18.8, 14.4, 16.4, 4.2, 11.8, "c_clair"), (13.0, 19.6, 12.0, 13.8, 4.6, 11.4, "c_plaque"),
                                        (15.2, 20.2, 9.6, 11.2, 5.0, 11.0, "c_plaque")):
        r = {"angle": -22.5, "axis": "z", "origin": [x0, (y0 + y1) / 2, 8]}
        b.miroir([x0, y0, z0], [x1, y1, z1], tex, True, r)
    r = {"angle": -22.5, "axis": "z", "origin": [15.2, 10.4, 8]}
    b.miroir([15.6, 9.2, 5.2], [20.0, 9.6, 10.8], "c_neon", rotation=r)
    return {"textures": {k: TEX + k for k in CLES_CASQUE} | {"particle": TEX + "c_plaque"}, "elements": b.fini()}


def parure_jupe():
    b = Blocs()
    # Ceinture tout autour de la taille, boucle néon.
    b([3.4, 1.0, 10.2], [12.6, 2.8, 11.6], "c_plaque", True)
    b([3.4, 1.0, 4.4], [12.6, 2.8, 5.8], "c_plaque", True)
    b.miroir([3.0, 1.0, 4.4], [4.2, 2.8, 11.6], "c_plaque", True)
    b([7.1, 1.1, 11.4], [8.9, 2.7, 11.9], "c_neon", rotation={"angle": 45, "axis": "z", "origin": [8, 1.9, 11.6]})
    # Pans évasés : deux devant (bande claire au milieu), un sur chaque côté, un derrière ; néon au bas.
    for x0, x1 in ((4.0, 7.8), (8.2, 12.0)):
        r = {"angle": -22.5, "axis": "x", "origin": [8, 1.2, 11.2]}
        b([x0, -6.0, 10.6], [x1, 1.2, 11.8], "c_plaque", True, r)
        b([x0 + 0.8, -4.8, 11.8], [x1 - 0.8, 0.6, 12.2], "c_clair", True, r)
        b([x0, -6.4, 10.7], [x1, -6.0, 11.7], "c_neon", rotation=r)
    r = {"angle": -22.5, "axis": "z", "origin": [3.2, 1.2, 8]}
    b.miroir([2.4, -3.6, 5.2], [3.8, 1.2, 10.8], "c_plaque", True, r)
    b.miroir([2.5, -4.0, 5.3], [3.7, -3.6, 10.7], "c_neon", rotation=r)
    r = {"angle": 22.5, "axis": "x", "origin": [8, 1.2, 4.6]}
    b([4.0, -5.0, 4.0], [12.0, 1.2, 5.2], "c_plaque", True, r)
    b([4.0, -5.4, 4.1], [12.0, -5.0, 5.1], "c_neon", rotation=r)
    return {"textures": {k: TEX + k for k in CLES_CASQUE} | {"particle": TEX + "c_plaque"}, "elements": b.fini()}


PARURES = {"parure_virale_plastron": parure_plastron, "parure_virale_jupe": parure_jupe}


ARMURE = {"casque": casque, "plastron": plastron, "jambieres": jambieres, "bottes": bottes}


def joueur():
    """Joueur debout (pixels, y vers le haut, de face vers +Z, sa droite vers -X), armure portée et casque."""
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
    tete = pose(casque()["elements"], np.diag([-0.625, 0.625, -0.625]), np.array([5.0, 23.0, 5.0]))
    # Parures : repère centré sur le torse (8, 8, 8), comme l'entité d'affichage de FluxTools.
    parures = [e for fn in PARURES.values() for e in pose(fn()["elements"], np.eye(3), np.array([-8.0, 10.0, -8.0]))]
    return corps + armure + tete + parures


# ---------------------------------------------------------------------------------------- aperçus


def apercus(planches, armures, dossier):
    os.makedirs(dossier, exist_ok=True)

    def tex(f=0):
        out = {}
        for k, p in planches.items():
            a = np.array(p, float)
            c = a.shape[1]
            n = a.shape[0] // c
            out[k] = a[(f % n) * c:(f % n + 1) * c]
        a, j = np.array(armures["armure"], float), np.array(armures["jambieres"], float)
        out |= {"armure": a, "jambieres": j, "porte": a, "porte2": j}
        for k, h in (("peau", "#C8956D"), ("chemise", "#3D7A7A"), ("pantalon", "#3B4FA0")):
            out[k] = np.array(Image.new("RGBA", (4, 4), rgb(h) + (255,)), float)
        return out

    # Joueur en armure : de face, trois quarts, de dos, trois quarts dos, et de nuit.
    scene = joueur()
    vues = [rot("y", 0), rot("x", 10) @ rot("y", 35), rot("y", 180), rot("x", 10) @ rot("y", 145)]
    planche = Image.new("RGBA", (400 * 5, 520))
    for i, v in enumerate(vues):
        planche.paste(rendre(scene, tex(4), v, taille=520, echelle=12, centre=(0, 17)).crop((60, 0, 460, 520)), (400 * i, 0))
    planche.paste(rendre(scene, tex(4), vues[1], taille=520, echelle=12, centre=(0, 17), nuit=True, fond=(6, 4, 10, 255)).crop((60, 0, 460, 520)), (1600, 0))
    planche.save(os.path.join(dossier, "set_porte.png"))
    # Icônes de l'armure, et casque de près.
    planche = Image.new("RGBA", (256 * 4, 256))
    for i, fn in enumerate(ARMURE.values()):
        m = fn()
        g = m["display"]["gui"]
        r, s = g["rotation"], g["scale"][0]
        A = rot("x", r[0]) @ rot("y", r[1]) @ rot("z", r[2]) * s
        els = pose(m["elements"], A, np.array(g["translation"], float) - A @ np.array([8.0, 8.0, 8.0]))
        planche.paste(rendre(els, tex(4), np.eye(3), taille=256, echelle=256 / 18), (256 * i, 0))
    planche.save(os.path.join(dossier, "icones_armure.png"))
    c = casque()["elements"]
    planche = Image.new("RGBA", (420 * 3, 420))
    for i, v in enumerate([rot("y", 180), rot("x", 15) @ rot("y", 145), rot("x", 15) @ rot("y", 250)]):
        planche.paste(rendre(pose(c, np.eye(3), -np.array([8.0, 11.0, 8.0])), tex(4), v, taille=420, echelle=420 / 24), (420 * i, 0))
    planche.save(os.path.join(dossier, "casque.png"))

    # Icônes d'inventaire (affichage gui = identité) sur fond d'inventaire, puis de nuit.
    for suffixe, fond, nuit in (("", (139, 139, 139, 255), False), ("_nuit", (8, 10, 22, 255), True)):
        planche = Image.new("RGBA", (256 * len(OUTILS), 256))
        for i, fn in enumerate(OUTILS.values()):
            g = cadrage(fn())
            s = g["scale"][0]
            els = pose(fn(), np.eye(3) * s, np.array(g["translation"], float) - s * np.array([8.0, 8.0, 8.0]))
            im = rendre(els, tex(4), np.eye(3), taille=256, echelle=256 / 18, fond=fond, nuit=nuit)
            cadre = (256 - 256 * 16 / 18) / 2  # contour de la case 16 x 16
            for k in range(256):
                for c in (int(cadre), int(256 - cadre)):
                    im.putpixel((k, c), (255, 210, 80, 255))
                    im.putpixel((c, k), (255, 210, 80, 255))
            planche.paste(im, (256 * i, 0))
        planche.save(os.path.join(dossier, "icones" + suffixe + ".png"))
    # Trois quarts, pour juger du relief.
    planche = Image.new("RGBA", (360 * len(OUTILS), 360))
    for i, fn in enumerate(OUTILS.values()):
        els = pose(fn(), np.eye(3), -np.array([8.0, 8.0, 8.0]))
        planche.paste(rendre(els, tex(4), rot("x", 20) @ rot("y", 35), taille=360, echelle=360 / 22), (360 * i, 0))
    planche.save(os.path.join(dossier, "relief.png"))
    els = pose(pioche(), np.eye(3), -np.array([8.0, 8.0, 8.0]))
    images = [rendre(els, tex(f), rot("y", 20), taille=320, echelle=320 / 20, fond=(14, 18, 40, 255)).convert("RGB")
              for f in range(0, 80, 2)]
    images[0].save(os.path.join(dossier, "pioche_anim.gif"), save_all=True, append_images=images[1:], duration=100, loop=0)


# ------------------------------------------------------------------------------------------- écriture


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    dest = sys.argv[1]
    pack = os.path.join(dest, "resourcepack/assets", NS)
    texdir = os.path.join(pack, "textures/item/virale")
    os.makedirs(texdir, exist_ok=True)

    planches = {"acier": tex_acier(), "sombre": tex_uni(SOMBRE, 121, 1.2), "argent": tex_argent(),
                "bois": tex_uni(BOIS, 141), "poignee": tex_poignee(), "cristal": tex_cristal(),
                "lueur": tex_lueur(), "reseau": tex_reseau(),
                "c_plaque": tex_biseau(PLAQUE, CLAIR, OMBRE_P), "c_clair": tex_biseau(CLAIR, REFLET, PLAQUE),
                "c_noir": tex_biseau(CREUX, CREUX, CREUX), "c_neon": tex_casque_neon()}
    for nom, im in planches.items():
        im.save(os.path.join(texdir, nom + ".png"))
    for nom, tempo in {"cristal": 2, "lueur": 2, "reseau": 2, "c_neon": 2}.items():
        with open(os.path.join(texdir, nom + ".png.mcmeta"), "w") as fh:
            json.dump({"animation": {"frametime": tempo, "interpolate": True}}, fh, indent=2)

    credit = "Influencraft, généré par plugins/fluxplugin/FluxTools/assets/modeles/gen_virale.py"
    lignes = []
    for outil, fn in OUTILS.items():
        nom = outil + "_virale"
        elements = fn()
        verifier(nom, elements)
        ecrire_json(os.path.join(pack, "models/item", nom + ".json"), {"credit": credit} | modele(elements))
        ecrire_json(os.path.join(pack, "items", nom + ".json"), {"model": {"type": "minecraft:model", "model": NS + ":item/" + nom}})
        lignes += ["  " + nom + ":", "    display_name: '&#5A85EA&l" + NOMS[outil] + "'", "    resource:",
                   "      material: " + MATERIAUX[outil], "      generate: false", "      model_path: item/" + nom]

    armures = {"armure": tex_armure(), "jambieres": tex_jambieres()}
    for nom, im in armures.items():
        im.save(os.path.join(texdir, nom + ".png"))
    for calque, nom in (("humanoid", "armure"), ("humanoid_leggings", "jambieres")):
        d = os.path.join(pack, "textures/entity/equipment", calque)
        os.makedirs(d, exist_ok=True)
        armures[nom].save(os.path.join(d, "virale.png"))
    ecrire_json(os.path.join(pack, "equipment/virale.json"), {"layers": {
        "humanoid": [{"texture": NS + ":virale"}], "humanoid_leggings": [{"texture": NS + ":virale"}]}})
    for nom, fn in PARURES.items():
        m = fn()
        verifier(nom, [e for e in m["elements"] if "rotation" in e])
        ecrire_json(os.path.join(pack, "models/item", nom + ".json"), {"credit": credit} | m)
        ecrire_json(os.path.join(pack, "items", nom + ".json"), {"model": {"type": "minecraft:model", "model": NS + ":item/" + nom}})
    for piece, fn in ARMURE.items():
        nom = piece + "_virale"
        m = fn()
        verifier(nom, [e for e in m["elements"] if "rotation" in e])
        ecrire_json(os.path.join(pack, "models/item", nom + ".json"), {"credit": credit} | m)
        ecrire_json(os.path.join(pack, "items", nom + ".json"), {"model": {"type": "minecraft:model", "model": NS + ":item/" + nom}})

    os.makedirs(os.path.join(dest, "configs"), exist_ok=True)
    with open(os.path.join(dest, "configs/virale.yml"), "w") as fh:
        fh.write(
            "# Généré par plugins/fluxplugin/FluxTools/assets/modeles/gen_virale.py : ne pas modifier à la main.\n"
            "# Modèles 3D des outils viraux. Les vrais outils portent le composant item_model (influoutils:<id>),\n"
            "# posé par FluxTools (clé modele:) ; ces objets ItemsAdder servent à l'aperçu des caisses et à /iagive.\n"
            "info:\n"
            "  namespace: " + NS + "\n"
            "items:\n" + "\n".join(lignes) + "\n"
        )

    if len(sys.argv) > 2:
        apercus(planches, armures, sys.argv[2])
    print("Set viral écrit dans", dest)


if __name__ == "__main__":
    main()
