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
Fond : un dégradé de la couleur de la rareté du familier (rarity de content/pets.yml, couleurs de config.yml, à côté
du dossier pets), plein en bas et fondu vers le haut, dans un cadre clair qui s'efface de même ; aucun pour un
familier sans rareté. Chaque portrait a aussi sa variante « case » au fond plus large (pack brut fluxpetslot à côté du
dossier influportraits, cf. variante_case).
Après coup : /iareload puis /iazip.
"""
import json
import math
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
SEUIL = 27                           # plus petite opacité dessinée par le jeu pour un objet (0,1)
CASE_SOMBRE = (25, 20, 32)           # fond des cases des menus (ui.WELL)
MARGE_CASE = 2                       # portrait « case » : 1 px d'écran de fond en plus de chaque côté
CASES = os.path.join(os.path.dirname(os.path.normpath(OUT)), "fluxpetslot", "resourcepack", "assets", "fluxpetslot")


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


def raretes():
    """id du familier -> couleur (r, g, b) de sa rareté, lue dans les fichiers de FluxPets."""
    racine = os.path.dirname(os.path.normpath(PETS))
    try:
        config = open(os.path.join(racine, "config.yml"), encoding="utf-8").read()
        pets = open(os.path.join(racine, "content", "pets.yml"), encoding="utf-8").read()
    except OSError:
        return {}
    bloc = re.search(r"^rarities:\n((?:[ #].*\n|\n)*)", config, re.M)
    couleurs = {nom: tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) for nom, h in
                re.findall(r"^  (\w+):\n(?:    .*\n)*?    color: ['\"]?#([0-9A-Fa-f]{6})", bloc.group(1) if bloc else "", re.M)}
    return {pid: couleurs[r] for pid, r in re.findall(r"^\s+(\w+):\n\s+rarity: (\w+)", pets, re.M) if r in couleurs}


def halo(px, couleur, marge=0):
    """Pose le fond de rareté derrière le portrait : dégradé plein en bas, transparent en haut, dans un cadre clair
    de 2 px (1 px à l'écran) qui s'efface en montant ; coins du bas à angle droit. ``marge`` : colonnes ajoutées de
    chaque côté du portrait (fond plus large que lui) ; rend l'image, large de TAILLE + 2 x marge."""
    clair = tuple(round(v + (255 - v) * .4) for v in couleur)
    large = TAILLE + 2 * marge
    fin = TAILLE - 1
    out = [[VIDE] * marge + list(ligne) + [VIDE] * marge for ligne in px]
    for y in range(TAILLE):
        t = y / fin
        for x in range(large):
            if out[y][x][3]:
                continue
            # part du bord haut de la case, sans seuil : aucun trait là où la couleur commence (pente nulle au départ)
            if x < 2 or x >= large - 2 or y >= TAILLE - 2:
                c, a = clair, round(255 * t ** 1.6)
            else:
                c = tuple(round(v + (255 - v) * .3 * t ** 3) for v in couleur)
                a = round(255 * t ** 1.9)
            # le jeu ne dessine pas un pixel d'objet sous 10 % d'opacité (marche visible au départ du dégradé) :
            # en dessous, l'opacité reste au seuil et c'est la couleur qui rejoint le fond sombre des cases
            if a >= SEUIL:
                out[y][x] = c + (a,)
            elif a > 0:
                out[y][x] = tuple(round(f + (v - f) * a / SEUIL) for v, f in zip(c, CASE_SOMBRE)) + (SEUIL,)
    return out


def variante_case(pid, px):
    """Portrait « case » du menu des familiers : même image, fond large de 18 px à l'écran (1 px de plus de chaque
    côté). Un objet ne dépasse sa case qu'avec un modèle oversized_in_gui : pack brut fluxpetslot, modèle d'objet
    fluxpetslot:<id> posé par FluxPets (item_model), l'objet ItemsAdder portrait_<id> restant le portrait carré."""
    for sous in ("items", os.path.join("models", "item"), os.path.join("textures", "item")):
        os.makedirs(os.path.join(CASES, sous), exist_ok=True)
    write(os.path.join(CASES, "textures", "item", pid + ".png"), TAILLE + 2 * MARGE_CASE, TAILLE, px)
    face = {"uv": [0, 0, 16, 16], "texture": "#t"}
    echelle = (TAILLE + 2 * MARGE_CASE) / TAILLE
    with open(os.path.join(CASES, "models", "item", pid + ".json"), "w", encoding="utf-8") as f:
        json.dump({"textures": {"t": f"fluxpetslot:item/{pid}", "particle": f"fluxpetslot:item/{pid}"},
                   "gui_light": "front",
                   "elements": [{"from": [0, 0, 8], "to": [16, 16, 8], "faces": {"south": face, "north": face}}],
                   "display": {"gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [echelle, 1, 1]}}}, f)
    with open(os.path.join(CASES, "items", pid + ".json"), "w", encoding="utf-8") as f:
        json.dump({"model": {"type": "minecraft:model", "model": f"fluxpetslot:item/{pid}"}, "oversized_in_gui": True}, f)


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
RARETES = raretes()
for fichier in ids:
    pid = fichier.lstrip("_")
    m = modele(fichier, pid)
    if m is None:
        print("sans modèle :", pid)
        continue
    nu = portrait(*m)
    px = halo(nu, RARETES[pid]) if pid in RARETES else nu
    variante_case(pid, halo(nu, RARETES[pid], MARGE_CASE) if pid in RARETES
                  else [[VIDE] * MARGE_CASE + list(ligne) + [VIDE] * MARGE_CASE for ligne in nu])
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
                r, g, b, a = px[y][x]
                if a:
                    img[oy + y][ox + x] = tuple((v * a + f * (255 - a)) // 255 for v, f in zip((r, g, b), FOND)) + (255,)
    write(PLANCHE, W, H, img)
