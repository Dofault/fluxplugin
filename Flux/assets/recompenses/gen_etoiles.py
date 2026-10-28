"""Menu Récompenses (/recompenses) : cinq variantes étoilées (2026-10-28, suite de l'idée « gloire »), sur le
fond générique des menus et la case arrondie de l'utilisateur.

Usage : python gen_etoiles.py <dossier de sortie>  (Pillow requis)
"""
import math
import os
import random
import sys
from PIL import Image, ImageDraw

ICI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ICI)
from gen_ruban import (BAS, BORD, CADRE, CHIFFRES, CONTOUR, CREUX, CX, FRISE, GRILLE, JOUR, NAV, RAMPE_OR, RETOUR,  # noqa: E402
                       SEMAINES, SEP, TOUT, W, H, base, c, cases, numeros, objet, separateur_grave, teinte)
from fond_lib import tampon_case  # noqa: E402

ORV = c("F0C860")
ORF = c("B8882A")
ORS = c("7A5410")
BLANC = c("FFF4D0")

ETOILE_5 = ["00100", "00100", "11111", "01110", "01010"]
ETOILE_7 = ["0001000", "0001000", "0011100", "1111111", "0111110", "0110110", "0100010"]
ETOILE_9 = ["000010000", "000010000", "000111000", "000111000", "111111111", "011111110", "001111100",
            "011101110", "010000010"]


def motif(im, x, y, m, col, ombre=None):
    px = im.load()
    for dy, ligne in enumerate(m):
        for dx, b in enumerate(ligne):
            if b == "1":
                if ombre:
                    px[x + dx, y + dy + 1] = ombre
    for dy, ligne in enumerate(m):
        for dx, b in enumerate(ligne):
            if b == "1":
                px[x + dx, y + dy] = col


def etoile(im, cx, cy, taille, col, ombre=ORS):
    m = {5: ETOILE_5, 7: ETOILE_7, 9: ETOILE_9}[taille]
    motif(im, cx - taille // 2, cy - taille // 2, m, col, ombre)


def etoile_gravee(im, cx, cy, taille):
    m = {5: ETOILE_5, 7: ETOILE_7, 9: ETOILE_9}[taille]
    motif(im, cx - taille // 2, cy - taille // 2, m, CONTOUR, CADRE)


def scintille(im, x, y, col, grande=False):
    px = im.load()
    for dx, dy in [(0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)] + ([(2, 0), (-2, 0), (0, 2), (0, -2)] if grande else []):
        if 0 <= x + dx < W and 0 <= y + dy < H:
            px[x + dx, y + dy] = col


def zones(marge=2):
    out = []
    for n in GRILLE + NAV + [TOUT, RETOUR, JOUR] + FRISE + SEMAINES:
        x, y = objet(n)
        out.append((x - marge, y - marge, x + 15 + marge, y + 15 + marge))
    return out


def libre(x, y, marge=2):
    return 5 < x < W - 6 and 4 < y < BAS - 4 and not any(a - marge <= x <= b + marge and e - marge <= y <= f + marge
                                                          for a, e, b, f in zones())


def ciel(im, graine, n, gravees=True, dorees=0):
    r = random.Random(graine)
    px = im.load()
    for _ in range(n):
        x, y = r.randint(6, W - 7), r.randint(5, BAS - 5)
        if libre(x, y):
            px[x, y] = CADRE if gravees else BORD
    for _ in range(dorees):
        x, y = r.randint(8, W - 9), r.randint(6, BAS - 6)
        if libre(x, y, 3):
            scintille(im, x, y, ORV if r.random() < .7 else BLANC, r.random() < .3)


def jours_etoiles(im, faits=3, taille=7, arc=0):
    """Une étoile sous chaque jour, dorée si récupéré (aperçu : les 3 premiers), gravée sinon, reliées."""
    d = ImageDraw.Draw(im)
    pts = []
    for i, n in enumerate(FRISE):
        x, y = objet(n)
        dy = round(arc * math.sin(math.pi * i / 6))
        pts.append((x + 7, y + 20 - dy))
    d.line(pts, CADRE)
    for i, (x, y) in enumerate(pts):
        if i < faits:
            etoile(im, x, y, taille, ORV)
        else:
            etoile_gravee(im, x, y, taille)


def entete_etoile(im, taille=9):
    etoile(im, CX, 8, taille, ORV)
    for dx in (-14, 14):
        scintille(im, CX + dx, 8, ORV)
    for dx in (-24, 24):
        scintille(im, CX + dx, 6, BORD)


# ------------------------------------------------------------------ variantes

def v1_nuit(case, case_or):
    """1. Nuit étoilée : un ciel de petites étoiles gravées ton sur ton dans tout le panneau, quelques étincelles
    dorées, une grande étoile en tête ; les jours sont des étoiles qui s'allument."""
    im = base()
    ciel(im, 1, 260, dorees=14)
    cases(im, case, case_or)
    entete_etoile(im)
    separateur_grave(im, SEP)
    jours_etoiles(im)
    return im


def v2_filante(case, case_or):
    """2. Étoile filante : elle traverse l'en-tête en diagonale, sa traînée dorée retombe en poussière d'étoiles
    jusqu'à « Tout récupérer »."""
    im = base()
    d = ImageDraw.Draw(im)
    ciel(im, 2, 90)
    # traînée
    x0, y0, x1, y1 = 20, 3, CX + 18, 12
    for k in range(0, 60):
        t = k / 59
        x, y = round(x0 + (x1 - x0) * t), round(y0 + (y1 - y0) * t)
        col = CADRE if t < .3 else (ORF if t < .75 else ORV)
        d.point((x, y), col)
        if t > .5:
            d.point((x, y + 1), ORF)
    r = random.Random(4)
    tx, ty = objet(TOUT)
    for _ in range(22):
        t = r.random()
        x = round(x1 + (tx + 8 - x1) * t + r.randint(-10, 10))
        y = round(y1 + (ty - 4 - y1) * t)
        if libre(x, y, 2):
            scintille(im, x, y, ORV if r.random() < .5 else BLANC, r.random() < .2)
    cases(im, case, case_or)
    etoile(im, x1, y1, 9, ORV)
    separateur_grave(im, SEP)
    jours_etoiles(im)
    return im


def v3_constellation(case, case_or):
    """3. Constellation : les cases des récompenses sont reliées par des traits gravés comme des étoiles d'une
    constellation, une étoile gravée à chaque croisement ; grande étoile en tête."""
    im = base()
    d = ImageDraw.Draw(im)
    # traits entre les cases (dans les interstices) et étoiles aux croisements
    xs = [objet(n)[0] for n in range(10, 17)]
    ys = [objet(n)[1] for n in (10, 19, 28)]
    for y in ys + [ys[-1] + 18]:
        d.line([(xs[0] - 6, y - 2), (xs[-1] + 21, y - 2)], BORD)
    for x in xs + [xs[-1] + 18]:
        d.line([(x - 2, ys[0] - 6), (x - 2, ys[-1] + 21)], BORD)
    cases(im, case, case_or)
    for x in [xx - 2 for xx in xs] + [xs[-1] + 16]:
        for y in [yy - 2 for yy in ys] + [ys[-1] + 16]:
            bord = x in (xs[0] - 2, xs[-1] + 16) and y in (ys[0] - 2, ys[-1] + 16)
            scintille(im, x, y, ORV if bord else BLANC, bord)
    ciel(im, 3, 70)
    entete_etoile(im)
    separateur_grave(im, SEP)
    jours_etoiles(im)
    return im


def v4_pluie(case, case_or):
    """4. Pluie d'étoiles : des étoiles dorées tombent de l'en-tête, avec leurs traînées gravées, et s'amassent
    autour de « Tout récupérer » ; les jours montent en arc vers le septième."""
    im = base()
    d = ImageDraw.Draw(im)
    r = random.Random(5)
    for _ in range(16):
        x = r.randint(10, W - 11)
        y = r.randint(20, 120)
        if libre(x, y, 3):
            for k in range(1, r.randint(5, 10)):
                if libre(x - k // 3, y - k, 1):
                    d.point((x - k // 3, y - k), CADRE)
            etoile(im, x, y, 5, ORV) if r.random() < .5 and libre(x, y, 5) else scintille(im, x, y, ORV)
    tx, ty = objet(TOUT)
    for dx, dy in [(-14, 4), (20, 6), (-20, 12), (26, 12), (-8, 18), (14, 18)]:
        scintille(im, tx + 8 + dx, ty + dy, ORV, dy > 10)
    cases(im, case, case_or)
    entete_etoile(im)
    separateur_grave(im, SEP)
    jours_etoiles(im, arc=0)
    etoile(im, objet(FRISE[-1])[0] + 7, objet(FRISE[-1])[1] + 21, 9, ORV)
    return im


def v5_couronne(case, case_or):
    """5. Couronne d'étoiles : une frise d'étoiles gravées court tout autour du panneau, une étoile dorée à chaque
    coin, trois étoiles dorées en tête ; « Tout récupérer » entouré d'un halo d'étoiles."""
    im = base()
    for x in range(14, W - 14, 10):
        for y in (5, BAS - 5):
            etoile_gravee(im, x, y, 5)
    for y in range(18, BAS - 10, 10):
        for x in (4, W - 5):
            if libre(x, y, 0) or True:
                etoile_gravee(im, x, y, 5) if 3 < x < W - 3 else None
    for x, y in [(6, 6), (W - 7, 6), (6, BAS - 6), (W - 7, BAS - 6)]:
        etoile(im, x, y, 5, ORV)
    cases(im, case, case_or)
    for dx, t in [(-12, 5), (0, 9), (12, 5)]:
        etoile(im, CX + dx, 8 if t == 9 else 9, t, ORV)
    tx, ty = objet(TOUT)
    for a in range(0, 360, 45):
        r = math.radians(a)
        x, y = round(tx + 7 + 15 * math.cos(r)), round(ty + 7 + 11 * math.sin(r))
        if libre(x, y, 0):
            scintille(im, x, y, ORV, a % 90 == 0)
    separateur_grave(im, SEP)
    jours_etoiles(im)
    return im


VARIANTES = [("1_nuit_etoilee", v1_nuit), ("2_etoile_filante", v2_filante), ("3_constellation", v3_constellation),
             ("4_pluie_d_etoiles", v4_pluie), ("5_couronne_d_etoiles", v5_couronne)]

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    case = tampon_case()
    case_or = teinte(case, RAMPE_OR)
    z = 3
    planche = Image.new("RGBA", ((W * z + 24) * len(VARIANTES) + 24, H * z + 50), c("101010"))
    dp = ImageDraw.Draw(planche)
    for i, (nom, f) in enumerate(VARIANTES):
        im = f(case, case_or)
        im.save(os.path.join(out, "etoiles_" + nom + ".png"))
        x = 24 + i * (W * z + 24)
        planche.alpha_composite(im.resize((W * z, H * z), Image.NEAREST), (x, 34))
        dp.text((x, 10), nom.replace("_", " "), fill=c("FFFFFF"))
    planche.save(os.path.join(out, "planche_etoiles.png"))
