#!/usr/bin/env python3
"""Portraits des familiers : une « photo » 32 x 32 par familier, pour les menus (à la place des cartes).

    portraits.py <BetterModel/models> <InfluPet/pets> <ItemsAdder/contents/influportraits> [planche.png]

Rendu du modèle BetterModel déployé (celui de entity-texture du familier, familier_<id>.bbmodel par défaut ; un
légendaire de boss a le modèle de son boss : géométrie et texture), vue de trois quarts
avant ombrée (apercu.render, même caméra que les aperçus), fond transparent, contour sombre d'un pixel.
32 x 32 : même densité de pixels que les cartes (un 16 x 16 perd les familiers fins : Licorne, Kitsune).
Écrit le pack ItemsAdder « influportraits » (objets portrait_<id>, PAPER généré) pour chaque familier qui a
un fichier dans InfluPet/pets (désactivés compris, préfixe « _ ») et un modèle. Planche facultative :
tous les portraits sur le fond des cases des menus, pour contrôle.
Après coup : /iareload puis /iazip.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import apercu  # noqa: E402
import bbrender  # noqa: E402
from png_util import write  # noqa: E402

MODELES, PETS, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
PLANCHE = sys.argv[4] if len(sys.argv) > 4 else None
TAILLE = 32
VUE = (35, 20)                       # lacet, tangage : trois quarts avant, légèrement d'en haut
CONTOUR = (21, 17, 27, 255)          # #15111B, contour des menus
VIDE = (0, 0, 0, 0)


def modele(fichier, pid):
    """(éléments, texture) du modèle BetterModel d'un familier (entity-texture: bettermodel:<modèle> de son fichier,
    sinon familier_<id>), None s'il n'existe pas (bbrender : rotations des os et textures multiples, pour les
    légendaires de boss)."""
    texte = open(os.path.join(PETS, fichier + ".yml"), encoding="utf-8").read()
    trouve = re.search(r"^entity-texture:\s*['\"]?bettermodel:([\w.-]+)", texte, re.M)
    nom = trouve.group(1) if trouve else f"familier_{pid}"
    chemin = os.path.join(MODELES, nom + ".bbmodel")
    if not os.path.exists(chemin):
        return None
    return bbrender.load(chemin)


def portrait(elements, tex):
    art = apercu.render(elements, tex, VUE[0], VUE[1], size=TAILLE - 2, bg=None, margin=0)
    px = [[VIDE] * TAILLE for _ in range(TAILLE)]
    for y in range(TAILLE - 2):
        for x in range(TAILLE - 2):
            if art[y][x] is not None:
                px[y + 1][x + 1] = tuple(art[y][x])
    bord = [(x, y) for y in range(TAILLE) for x in range(TAILLE)
            if px[y][x][3] == 0 and any(0 <= y + dy < TAILLE and 0 <= x + dx < TAILLE and px[y + dy][x + dx][3]
                                         for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
    for x, y in bord:
        px[y][x] = CONTOUR
    return px


ids = sorted(os.path.splitext(f)[0] for f in os.listdir(PETS) if f.endswith(".yml"))
os.makedirs(os.path.join(OUT, "textures", "portrait"), exist_ok=True)
lignes = ["info:", "  namespace: influportraits", "",
          "# Généré par scripts/influcard/portraits.py, ne pas éditer à la main.",
          "# portrait_<id> : photo 32x32 du familier (vue de trois quarts du modèle BetterModel),",
          "# utilisée par les menus à la place de la carte.", "", "items:"]
faits = []
for fichier in ids:
    pid = fichier.lstrip("_")
    m = modele(fichier, pid)
    if m is None:
        print("sans modèle :", pid)
        continue
    px = portrait(*m)
    write(os.path.join(OUT, "textures", "portrait", pid + ".png"), TAILLE, TAILLE, px)
    lignes += [f"  portrait_{pid}:", "    display_name: ' '", "    resource:", "      material: PAPER",
               "      generate: true", "      textures:", f"      - portrait/{pid}.png"]
    faits.append((pid, px))
open(os.path.join(OUT, "config.yml"), "w", encoding="utf-8").write("\n".join(lignes) + "\n")
print(len(faits), "portraits")

if PLANCHE:
    # cases 36 px (marge de 2) sur le violet des cases des menus, 8 par ligne
    COLS, CASE, FOND = 8, 36, (0x59, 0x46, 0x74, 255)
    rangs = (len(faits) + COLS - 1) // COLS
    W, H = COLS * CASE, rangs * CASE
    img = [[FOND] * W for _ in range(H)]
    for i, (pid, px) in enumerate(faits):
        ox, oy = (i % COLS) * CASE + 2, (i // COLS) * CASE + 2
        for y in range(TAILLE):
            for x in range(TAILLE):
                if px[y][x][3]:
                    img[oy + y][ox + x] = px[y][x]
    write(PLANCHE, W, H, img)
