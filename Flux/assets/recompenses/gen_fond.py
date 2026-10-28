"""Fond du menu Récompenses (/recompenses), retenu le 2026-10-28 : fond générique, étoiles gravées sur toute la
bordure, étoile dorée aux coins, immense étoile gravée en filigrane derrière les récompenses.

Disposition (coffre de 6 rangées, inventaire masqué), boîte des récompenses reprise des actions rémunérées du
menu métier : un caisson creusé sur les rangées 1 à 3, 21 récompenses au milieu (10-16, 19-25, 28-34), page
précédente et suivante centrées verticalement à gauche et à droite (18, 26) sur des cases noircies.
Rangée 4 : Tout récupérer (40) avec son halo. Sous la séparation gravée : retour (54), récompense du jour (58), Connexion / Temps de jeu (62),
frise des 7 jours (64-70) et semaines précédente / suivante (63, 71) sur des cases noircies.

Usage : python gen_fond.py <fichier png> [aperçu agrandi]  (Pillow requis)
"""
import os
import sys
from PIL import Image, ImageDraw

ICI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ICI)
from gen_ruban import CADRE, CONTOUR, CX, RAMPE_OR, SEP, W, H, base, objet, separateur_grave, teinte  # noqa: E402
from gen_bordure import bordure, grande_etoile, halo  # noqa: E402
from fond_lib import poser, tampon_case  # noqa: E402

RECOMPENSES = list(range(10, 17)) + list(range(19, 26)) + list(range(28, 35))
PAGES = [18, 26]
TOUT = 40
RETOUR, JOUR, VUE = 54, 58, 62
FRISE = list(range(64, 71))
SEMAINES = [63, 71]

# cases noircies du menu métier (menu_metier.png, boîte des actions)
NOIR = (29, 23, 38, 255)
NOIR_JOINT = (37, 29, 48, 255)


def assombrir(im, box, force):
    px = im.load()
    x0, y0, x1, y1 = box
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            r, g, b, a = px[x, y]
            px[x, y] = (round(r + (21 - r) * force), round(g + (17 - g) * force), round(b + (27 - b) * force), a)


def caisson(im):
    """Boîte creusée des récompenses : fond assombri, ombre en haut et à gauche, lumière en bas et à droite."""
    x0, y0 = objet(9)[0] - 2, objet(9)[1] - 2
    x1, y1 = objet(35)[0] + 17, objet(35)[1] + 17
    assombrir(im, (x0, y0, x1, y1), .35)
    d = ImageDraw.Draw(im)
    d.line([(x0, y0), (x1, y0)], CONTOUR)
    d.line([(x0, y0), (x0, y1)], CONTOUR)
    d.line([(x0, y1 + 1), (x1, y1 + 1)], CADRE)
    d.line([(x1 + 1, y0), (x1 + 1, y1 + 1)], CADRE)


def case_noire(im, n):
    x, y = objet(n)
    d = ImageDraw.Draw(im)
    d.rectangle([x - 1, y - 1, x + 16, y + 16], NOIR_JOINT)
    d.rectangle([x, y, x + 15, y + 15], NOIR)


def fond():
    im = base()
    bordure(im)
    caisson(im)
    gx, gy = CX, objet(19)[1] + 8
    for decal, col in ((1, CADRE), (0, CONTOUR)):
        grande_etoile(im, gx, gy + decal, 74, col)
    case = tampon_case()
    case_or = teinte(case, RAMPE_OR)
    for n in RECOMPENSES + [RETOUR, VUE] + FRISE:
        poser(im, case, *objet(n))
    for n in PAGES + SEMAINES:
        case_noire(im, n)
    for n in (TOUT, JOUR):
        poser(im, case_or, *objet(n))
    halo(im)
    separateur_grave(im, SEP)
    return im


if __name__ == "__main__":
    im = fond()
    im.save(sys.argv[1])
    if len(sys.argv) > 2:
        im.resize((W * 3, H * 3), Image.NEAREST).save(sys.argv[2])
