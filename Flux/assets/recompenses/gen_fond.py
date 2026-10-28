"""Fond du menu Récompenses (/recompenses).

Haut : image retouchée par l'utilisateur le 2026-10-28 (fond_haut_utilisateur.png : grande étoile gravée en
filigrane, boîte des 21 récompenses avec page précédente et suivante sur des cases noircies, Tout récupérer et son
halo), descendue d'une rangée (seuls ses pixels qui diffèrent du fond générique sont reportés, le dégradé reste
celui du générique) : récompenses 19-25 / 28-34 / 37-43, flèches 27 et 35, Tout récupérer 49. Rangée 1 : cases
du retour (9) et de l'explication (17). Bas : deux frises dans une même boîte, connexion (55-61, flèches 54 / 62)
et temps de jeu (64-70, flèches 63 / 71).
Les cases de l'inventaire masqué sont 2 px plus haut que dans fond_lib.objet (relevé en jeu par l'utilisateur).

Usage : python gen_fond.py <fichier png> [aperçu agrandi]  (Pillow requis)
"""
import math
import os
import sys
from PIL import Image, ImageDraw

ICI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(ICI))
from fond_lib import poser, tampon_case  # noqa: E402

W, H = 176, 221
COUPE = 128
DECALAGE = 18
LIMITE = 136  # rien du haut sous cette ligne (la boîte des frises commence juste après)
EN_TETE = [9, 17]
CONNEXION = list(range(55, 62))
TEMPS = list(range(64, 71))
FLECHES = [54, 62, 63, 71]
CONTOUR = (21, 17, 27, 255)
CADRE = (89, 70, 116, 255)
NOIR = (29, 23, 38, 255)
NOIR_JOINT = (37, 29, 48, 255)


def objet(case):
    if case < 54:
        return 8 + 18 * (case % 9), 18 + 18 * (case // 9)
    i = case - 54
    return 8 + 18 * (i % 9), 138 + 18 * (i // 9)


def case_noire(d, n):
    x, y = objet(n)
    d.rectangle([x - 1, y - 1, x + 16, y + 16], NOIR_JOINT)
    d.rectangle([x, y, x + 15, y + 15], NOIR)


def fond():
    im = Image.open(os.path.join(os.path.dirname(ICI), "menu_generique.png")).convert("RGBA")
    generique = im.copy().load()
    haut = Image.open(os.path.join(ICI, "fond_haut_utilisateur.png")).convert("RGBA").load()
    px = im.load()
    for y in range(3, COUPE):
        if y + DECALAGE >= LIMITE:
            break
        for x in range(4, W - 4):
            if haut[x, y] != generique[x, y]:
                px[x, y + DECALAGE] = haut[x, y]
    d = ImageDraw.Draw(im)
    # pointe haute de l'étoile, cachée par le bord dans l'image d'origine et visible une fois descendue
    centre, apex = (88, 80), (88, 6)
    for k in (-54, -126):
        a = math.radians(k)
        bout = (centre[0] + 31.08 * math.cos(a), centre[1] + 31.08 * math.sin(a))
        fin = (apex[0] + (bout[0] - apex[0]) * .3, apex[1] + (bout[1] - apex[1]) * .3)
        d.line([(apex[0], apex[1] + 1), (fin[0], fin[1] + 1)], CADRE)
        d.line([apex, fin], CONTOUR)
    case = tampon_case()
    for n in EN_TETE:
        poser(im, case, *objet(n))
    for n in CONNEXION + TEMPS:
        poser(im, case, *objet(n))
    for n in FLECHES:
        case_noire(d, n)
    return im


if __name__ == "__main__":
    im = fond()
    im.save(sys.argv[1])
    if len(sys.argv) > 2:
        im.resize((W * 3, H * 3), Image.NEAREST).save(sys.argv[2])
