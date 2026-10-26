#!/usr/bin/env python3
"""Fonds du menu de l'outil (FluxMenu : menus/outil.yml, coffre de 2 rangées, inventaire masqué).

    <venv avec Pillow>/python3 gen_fond.py <menu_generique.png> <dossier de sortie>

Thème des fonds FluxMenu : fond générique (dégradé, damier) sur toute la hauteur utile, case arrondie de
l'utilisateur (FluxMenu/assets/case_arrondie.png) sous chaque case utile, outil dans une case lilas aux coins
dorés. Aucun séparateur dessiné : la séparation coffre / inventaire du client (libellé « Inventaire »)
distingue les deux contenus.
  coffre, rangée 0 : niveau (2), outil (4), statistiques (6) ;
  coffre, rangée 1 : les niveaux, 7 par page (10 à 16), flèches en 9 et 17 ;
  inventaire, rangée 0 : quête du jour (22) ;
  inventaire, rangée 1 : 7 jours de quêtes (28 à 34), flèches en 27 et 35.
Sorties : menu_outil.png (outil à niveaux), menu_outil_contrat_<n>.png (contrats : pas de ligne des niveaux,
n étapes centrées sur la rangée des jours).
"""
import os
import sys

sys.path.insert(0, "/home/influencraft/plugins/FluxMenu/assets")
from fond_lib import generique_raccourci, objet, poser, tampon_case  # noqa: E402

GENERIQUE, SORTIE = sys.argv[1], sys.argv[2]
RANGEES = 2
HAUT, BAS = 8, 110          # du haut du coffre au bas de la 2e rangée d'inventaire
LILAS_CLAIR = (226, 205, 255, 255)
OR = (242, 200, 91, 255)
tampon = tampon_case()


def case(c):
    """Position de l'objet d'une case. fond_lib place l'inventaire 1 px trop bas (18 x rangées + 32 au lieu
    du 103 + (rangées - 4) x 18 du client, que PathRenderer utilise) : corrigé ici."""
    x, y = objet(c, RANGEES)
    return (x, y - 1) if c >= RANGEES * 9 else (x, y)


def rect(px, x0, y0, x1, y1, c):
    for x in range(x0, x1 + 1):
        px[x, y0] = px[x, y1] = c
    for y in range(y0, y1 + 1):
        px[x0, y] = px[x1, y] = c


def coins(px, x0, y0, x1, y1, c, n):
    for i in range(n):
        for j in range(n - i):
            px[x0 + i, y0 + j] = px[x1 - i, y0 + j] = px[x0 + i, y1 - j] = px[x1 - i, y1 - j] = c


def fond(niveaux, quetes):
    im = generique_raccourci(GENERIQUE, HAUT, BAS)
    px = im.load()
    for c in [2, 4, 6] + niveaux + [22] + quetes:
        poser(im, tampon, *case(c))
    x, y = case(4)
    rect(px, x - 1, y - 1, x + 16, y + 16, LILAS_CLAIR)
    coins(px, x, y, x + 15, y + 15, OR, 2)
    return im


os.makedirs(SORTIE, exist_ok=True)
FONDS = {"menu_outil": fond(list(range(10, 17)), list(range(28, 35)))}
for n in (4, 5):
    debut = 28 + (7 - n + 1) // 2
    FONDS["menu_outil_contrat_%d" % n] = fond([], list(range(debut, debut + n)))
for nom, im in FONDS.items():
    im.save(os.path.join(SORTIE, nom + ".png"))
    print(nom, im.getbbox())
