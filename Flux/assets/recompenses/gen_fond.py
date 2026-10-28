"""Fond du menu Récompenses (/recompenses).

Haut (y < 128) : image retouchée par l'utilisateur le 2026-10-28 (fond_haut_utilisateur.png : fond générique,
grande étoile gravée en filigrane, boîte des 21 récompenses 10-16 / 19-25 / 28-34 avec page précédente et
suivante sur des cases noircies en 18 et 26, Tout récupérer en 40 avec son halo). Bas : fond générique et deux
frises dans une même boîte, connexion (55-61, flèches 54 / 62) et temps de jeu (64-70, flèches 63 / 71).
Les cases de l'inventaire masqué sont 2 px plus haut que dans fond_lib.objet (relevé en jeu par l'utilisateur).

Usage : python gen_fond.py <fichier png> [aperçu agrandi]  (Pillow requis)
"""
import os
import sys
from PIL import Image, ImageDraw

ICI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(ICI))
from fond_lib import poser, tampon_case  # noqa: E402

W, H = 176, 221
COUPE = 128
CONNEXION = list(range(55, 62))
TEMPS = list(range(64, 71))
FLECHES = [54, 62, 63, 71]
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
    haut = Image.open(os.path.join(ICI, "fond_haut_utilisateur.png")).convert("RGBA")
    im.paste(haut.crop((0, 0, W, COUPE)), (0, 0))
    d = ImageDraw.Draw(im)
    case = tampon_case()
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
