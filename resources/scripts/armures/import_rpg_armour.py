#!/usr/bin/env python3
"""Convertit l'addon Bedrock « RPG Armour » (.mcaddon) en namespace ItemsAdder « influtenues » (Labs).

L'addon habille le joueur avec des tenues de métier dessinées comme un skin : chaque pièce est la forme du corps
en deux épaisseurs (dessous + dessus), sans relief. En Java, une pièce d'armure n'a qu'une couche : on fusionne
les deux épaisseurs et on replace chaque boîte UV Bedrock (64x64) dans la disposition des couches Java (64x32).
- chapeau -> tête + surcouche de layer_1 ; haut -> corps + bras de layer_1 ; chaussures -> jambes de layer_1
- bas -> ceinture + jambes de layer_2
Les deux bras (et les deux jambes) partagent la même texture en Java : on garde le côté droit, le gauche est
son miroir. Les deux chapeaux à bord plat (fermier, pêcheur) deviennent un casque 3D porté sur la tête.
Tenues à géométrie spéciale non reprises : anti-émeute, scaphandre.

Usage : import_rpg_armour.py <fichier .mcaddon> [--sortie <dossier>] [--apercu <png>]
"""
import argparse
import io
import json
import os
import shutil
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import png_io  # noqa: E402
import rendu  # noqa: E402
from gen_armures import RACINE, boite, PEAU  # noqa: E402

NS = "influtenues"
SORTIE = os.path.join(RACINE, "serveurs/04_labs/plugins/ItemsAdder/contents", NS)
MATERIAU = "DIAMOND"
RP = "RPG Armour (Resource Pack)/"
PIECES = (("hat", "helmet", "HEAD"), ("top", "chestplate", "CHEST"), ("bottom", "leggings", "LEGS"),
          ("shoes", "boots", "FEET"))
BORD_PLAT = ("farmer", "fisherman")

# tenue -> (nom du métier, noms des 4 pièces)
TENUES = {
    "miner": ("Mineur", "Casque", "Veste", "Pantalon", "Bottes"),
    "farmer": ("Fermier", "Chapeau de paille", "Salopette", "Pantalon", "Bottes"),
    "fisherman": ("Pêcheur", "Chapeau", "Gilet", "Pantalon", "Bottes"),
    "lumberjack": ("Bûcheron", "Bonnet", "Chemise", "Pantalon", "Bottes"),
    "construction_worker": ("Ouvrier", "Casque de chantier", "Gilet", "Pantalon", "Chaussures"),
    "engineer": ("Ingénieur", "Casque", "Veste", "Pantalon", "Chaussures"),
    "magician": ("Magicien", "Haut-de-forme", "Costume", "Pantalon", "Chaussures"),
    "chef": ("Cuisinier", "Toque", "Veste", "Pantalon", "Chaussures"),
    "firefighter": ("Pompier", "Casque", "Veste", "Pantalon", "Bottes"),
    "police_officer": ("Policier", "Casquette", "Uniforme", "Pantalon", "Chaussures"),
    "security_guard": ("Vigile", "Casquette", "Uniforme", "Pantalon", "Chaussures"),
    "doctor": ("Médecin", "Miroir frontal", "Blouse", "Pantalon", "Chaussures"),
    "nurse": ("Infirmier", "Coiffe", "Blouse", "Pantalon", "Chaussures"),
    "surgeon": ("Chirurgien", "Calot", "Blouse", "Pantalon", "Chaussures"),
    "paramedic": ("Ambulancier", "Masque", "Veste", "Pantalon", "Chaussures"),
    "pilot": ("Pilote", "Casquette", "Uniforme", "Pantalon", "Chaussures"),
    "bus_driver": ("Chauffeur de bus", "Casquette", "Uniforme", "Pantalon", "Chaussures"),
    "taxi_driver": ("Chauffeur de taxi", "Casquette", "Veste", "Pantalon", "Chaussures"),
    "train_conductor": ("Contrôleur", "Casquette", "Uniforme", "Pantalon", "Chaussures"),
    "business_executive": ("Cadre", "Chapeau", "Costume", "Pantalon", "Chaussures"),
    "spy": ("Espion", "Chapeau", "Manteau", "Pantalon", "Chaussures"),
    "student": ("Étudiant", "Casquette", "Uniforme", "Pantalon", "Chaussures"),
    "teacher": ("Professeur", "Lunettes", "Veste", "Pantalon", "Chaussures"),
}
VIDE = (0, 0, 0, 0)


def bloc(px, u, v, w, h):
    return [row[u:u + w] for row in px[v:v + h]]


def poser(dst, src, u, v):
    """Pose src sur dst en (u, v), pixel transparent = on garde le dessous."""
    for y, row in enumerate(src):
        for x, c in enumerate(row):
            if c[3] >= 26:
                dst[v + y][u + x] = (c[0], c[1], c[2], 255)


def couches(z, job):
    lire = lambda p: png_io.decode(z.read(f"{RP}textures/ua/rpg/entity/attachable/{job}_{p}.png"))[2]
    l1 = [[VIDE] * 64 for _ in range(32)]
    l2 = [[VIDE] * 64 for _ in range(32)]
    hat, top, bottom, shoes = (lire(p) for p in ("hat", "top", "bottom", "shoes"))
    poser(l1, bloc(hat, 0, 0, 64, 16), 0, 0)                    # tête + surcouche : même disposition
    for u, v in ((0, 0), (0, 16)):                              # corps : dessous puis dessus
        poser(l1, bloc(top, u, v, 24, 16), 16, 16)
    for u, v in ((24, 0), (24, 16)):                            # bras droit
        poser(l1, bloc(top, u, v, 16, 16), 40, 16)
    for u, v in ((0, 0), (0, 16)):                              # chaussures
        poser(l1, bloc(shoes, u, v, 16, 16), 0, 16)
    poser(l2, bloc(bottom, 16, 16, 24, 16), 16, 16)             # ceinture
    for u, v in ((0, 0), (0, 16)):                              # jambe droite
        poser(l2, bloc(bottom, u, v, 16, 16), 0, 16)
    return l1, l2


def icone(px):
    """Icône ramenée à 16x16 (une de l'addon fait 17x17) : on rogne le vide autour puis on recentre."""
    if len(px) == 16 and len(px[0]) == 16:
        return px
    ys = [y for y, row in enumerate(px) if any(c[3] for c in row)]
    xs = [x for x in range(len(px[0])) if any(row[x][3] for row in px)]
    coupe = [row[xs[0]:xs[-1] + 1][:16] for row in px[ys[0]:ys[-1] + 1][:16]]
    out = [[VIDE] * 16 for _ in range(16)]
    oy, ox = (16 - len(coupe)) // 2, (16 - len(coupe[0])) // 2
    for y, row in enumerate(coupe):
        out[oy + y][ox:ox + len(row)] = row
    return out


def face_boite(u, v, w, h, d):
    k = 16 / 64
    f = {"up": (u + d, v, u + d + w, v + d), "down": (u + d + w, v, u + d + 2 * w, v + d),
         "east": (u, v + d, u + d, v + d + h), "north": (u + d, v + d, u + d + w, v + d + h),
         "west": (u + d + w, v + d, u + 2 * d + w, v + d + h), "south": (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h)}
    return {n: {"uv": [c * k for c in r], "texture": "#0"} for n, r in f.items()}


def chapeau_3d(job, px):
    """Tête (boîte 8 gonflée de 1) + bord plat de 14x14, centre de la tête en (8, 8, 8) : 1 unité = 1 pixel.
    La bande de texture du bord est en ligne 10 dans la géométrie, mais dessinée en ligne 11 pour le fermier."""
    ligne = next(v for v in (10, 11) if px[v][40][3] >= 26)
    bande = [10, ligne / 4, 12, (ligne + 1) / 4]
    bord = {n: {"uv": bande, "texture": "#0"} for n in ("north", "south", "east", "west", "up", "down")}
    tex = f"{NS}:item/{job}_hat_3d"
    return {"texture_size": [64, 64], "textures": {"0": tex, "particle": tex},
            "elements": [{"from": [3, 3, 3], "to": [13, 13, 13], "faces": face_boite(0, 0, 8, 8, 8)},
                         {"from": [1, 9, 1], "to": [15, 10, 15], "faces": bord}],
            "display": {"head": {"scale": [1.6, 1.6, 1.6]},
                        "gui": {"rotation": [25, 145, 0], "scale": [0.8, 0.8, 0.8]},
                        "ground": {"translation": [0, 2, 0], "scale": [0.45, 0.45, 0.45]},
                        "fixed": {"rotation": [0, 180, 0], "scale": [0.9, 0.9, 0.9]},
                        "thirdperson_righthand": {"translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
                        "firstperson_righthand": {"rotation": [0, 160, 0], "translation": [1, 3, 0], "scale": [0.5, 0.5, 0.5]}}}


def config():
    y = ["# Fichier généré par scripts/armures/import_rpg_armour.py : ne pas modifier à la main.",
         "info:", f"  namespace: {NS}", "", "categories:", "  tenues_metier:", "    enabled: true",
         f'    icon: "{NS}:miner_helmet"', '    name: "Tenues de métier (RPG Armour)"',
         '    permission: "ia.menu.seecategory.tenues_metier"', "    items:"]
    y += [f'      - "{NS}:{job}_{p}"' for job in TENUES for _, p, _ in PIECES]
    y += ["", "equipments:"]
    for job in TENUES:
        y += [f"  {job}:", "    type: armor", f"    layer_1: armor/{job}_layer_1", f"    layer_2: armor/{job}_layer_2"]
    y += ["", "items:"]
    for job, noms in TENUES.items():
        for n, (src, p, slot) in enumerate(PIECES):
            plat = src == "hat" and job in BORD_PLAT
            y += [f"  {job}_{p}:", f'    display_name: "<white>{noms[n + 1]} ({noms[0]})"', "    resource:",
                  f"      material: {MATERIAU}_{p.upper()}"]
            if plat:
                y += ["      generate: false", f"      model_path: item/{job}_hat_3d"]
            else:
                y += ["      generate: true", "      textures:", f"        - item/{job}_{src}"]
            y += ["    equipment:"] + ([] if plat else [f"      id: {NS}:{job}"]) + [f"      slot: {slot}", ""]
    return "\n".join(y)


def apercu(path, donnees, jobs, taille=300):
    images = []
    for job in jobs:
        l1, l2, modele, tex = donnees[job]
        carre = lambda px: px + [[VIDE] * 64 for _ in range(32)]
        l1, l2 = carre(l1), carre(l2)
        peau = [[PEAU] * 64 for _ in range(64)]
        parts = [("tete", (-4, 24, -4), (4, 32, 4)), ("corps", (-4, 12, -2), (4, 24, 2)),
                 ("bras", (-8, 12, -2), (-4, 24, 2)), ("bras", (4, 12, -2), (8, 24, 2)),
                 ("jambe", (-4, 0, -2), (0, 12, 2)), ("jambe", (0, 0, -2), (4, 12, 2))]
        scene = []
        for n, (nom, a, b) in enumerate(parts):
            calques = [(peau, 0.0)]
            if nom != "tete" or not modele:
                calques.append((l1, 1.0))
            if nom in ("corps", "jambe"):
                calques.insert(1, (l2, 0.5))
            for t, g in calques:
                e, t = boite(nom, a, b, t, g, n in (3, 5))
                scene += rendu.cubes({"elements": [e]}, {"t": t})
        if modele:
            for (a, b, M, Mt, o, faces, _) in rendu.cubes(modele, {"0": tex}):
                f = lambda p: [p[i] - 8 + (0, 28, 0)[i] for i in range(3)]
                scene.append((f(a), f(b), M, Mt, f(o), faces, (0, 0, 0)))
        cadre = [[x, yy, zz] for x in (-10, 10) for yy in (0, 36) for zz in (-8, 8)]
        images += [rendu.render(scene, 30, 12, size=taille, frame=cadre),
                   rendu.render(scene, 210, 12, size=taille, frame=cadre)]
    png_io.write(path, png_io.sheet(images, 8))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("mcaddon")
    ap.add_argument("--sortie", default=SORTIE)
    ap.add_argument("--apercu")
    a = ap.parse_args()
    z = zipfile.ZipFile(a.mcaddon)
    for d in ("models", "textures"):
        shutil.rmtree(os.path.join(a.sortie, d), ignore_errors=True)
    for d in ("models/item", "textures/item", "textures/armor"):
        os.makedirs(os.path.join(a.sortie, d))
    donnees = {}
    for job in TENUES:
        l1, l2 = couches(z, job)
        png_io.write(os.path.join(a.sortie, "textures/armor", f"{job}_layer_1.png"), l1)
        png_io.write(os.path.join(a.sortie, "textures/armor", f"{job}_layer_2.png"), l2)
        for src, _, _ in PIECES:
            png_io.write(os.path.join(a.sortie, "textures/item", f"{job}_{src}.png"),
                         icone(png_io.decode(z.read(f"{RP}textures/ua/rpg/items/{job}_{src}.png"))[2]))
        modele = tex = None
        if job in BORD_PLAT:
            raw = z.read(f"{RP}textures/ua/rpg/entity/attachable/{job}_hat.png")
            tex = png_io.decode(raw)[2]
            png_io.write(os.path.join(a.sortie, "textures/item", f"{job}_hat_3d.png"), tex)
            modele = chapeau_3d(job, tex)
            json.dump(modele, open(os.path.join(a.sortie, "models/item", f"{job}_hat_3d.json"), "w"), indent=1)
        donnees[job] = (l1, l2, modele, tex)
    open(os.path.join(a.sortie, "config.yml"), "w").write(config())
    print(f"{len(TENUES)} tenues, {len(TENUES) * 4} objets -> {a.sortie}")
    if a.apercu:
        apercu(a.apercu, donnees, list(TENUES))
