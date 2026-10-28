"""Menu Récompenses (/recompenses) : base « couronne d'étoiles » retenue le 2026-10-28 (étoiles gravées sur toute
la bordure, sans étoiles sous les jours ni étoiles dorées en tête) et quatre déclinaisons.

Usage : python gen_bordure.py <dossier de sortie>  (Pillow requis)
"""
import math
import os
import random
import sys
from PIL import Image, ImageDraw

ICI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ICI)
from gen_ruban import BAS, BORD, CADRE, CONTOUR, CREUX, CX, RAMPE_OR, SEP, TOUT, W, H, base, c, cases, objet, separateur_grave, teinte  # noqa: E402
from gen_etoiles import BLANC, ORF, ORS, ORV, ETOILE_5, ETOILE_7, ETOILE_9, etoile, etoile_gravee, libre, motif, scintille  # noqa: E402
from fond_lib import tampon_case  # noqa: E402


def bordure(im, dorees=()):
    """Étoiles gravées tout autour du panneau ; celles d'indice dans dorees sont allumées."""
    pos = [(x, 5) for x in range(14, W - 14, 10)] + [(W - 5, y) for y in range(18, BAS - 10, 10)] + \
          [(x, BAS - 5) for x in range(W - 15, 13, -10)] + [(4, y) for y in range(BAS - 12, 17, -10)]
    for i, (x, y) in enumerate(pos):
        if i in dorees:
            etoile(im, x, y, 5, ORV)
        else:
            etoile_gravee(im, x, y, 5)
    for x, y in [(6, 6), (W - 7, 6), (6, BAS - 6), (W - 7, BAS - 6)]:
        etoile(im, x, y, 5, ORV)
    return pos


def halo(im):
    tx, ty = objet(TOUT)
    for a in range(0, 360, 45):
        r = math.radians(a)
        x, y = round(tx + 7 + 15 * math.cos(r)), round(ty + 7 + 11 * math.sin(r))
        if libre(x, y, 0):
            scintille(im, x, y, ORV, a % 90 == 0)


def grande_etoile(im, cx, cy, rayon, col):
    """Contour d'étoile à 5 branches tracé au trait."""
    d = ImageDraw.Draw(im)
    pts = []
    for k in range(10):
        r = rayon if k % 2 == 0 else rayon * .42
        a = math.radians(-90 + 36 * k)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    d.polygon(pts, outline=col)
    return pts


# ------------------------------------------------------------------ variantes

def v1_base(case, case_or):
    """1. La base : étoiles gravées sur toute la bordure, étoile dorée aux coins, halo autour de Tout récupérer."""
    im = base()
    bordure(im)
    cases(im, case, case_or)
    halo(im)
    separateur_grave(im, SEP)
    return im


def v2_filigrane(case, case_or):
    """2. Filigrane : une immense étoile gravée en creux derrière les récompenses, ses pointes débordent de la
    grille ; la bordure reste la même."""
    im = base()
    bordure(im)
    gx, gy = CX, objet(19)[1] + 8
    for decal, col in ((1, CADRE), (0, CONTOUR)):
        grande_etoile(im, gx, gy + decal, 74, col)
    cases(im, case, case_or)
    halo(im)
    separateur_grave(im, SEP)
    return im


def v3_medaillon(case, case_or):
    """3. Médaillon : un médaillon doré à étoile accroché au milieu de la bordure du haut, comme une décoration ;
    deux rubans gravés en pendent derrière le titre."""
    im = base()
    d = ImageDraw.Draw(im)
    bordure(im)
    # rubans gravés du médaillon
    for sens in (-1, 1):
        d.polygon([(CX + sens * 2, 10), (CX + sens * 8, 10), (CX + sens * 10, 18), (CX + sens * 6, 16), (CX + sens * 4, 19)],
                  CREUX)
    d.ellipse([CX - 7, 0, CX + 6, 13], fill=ORF, outline=ORS)
    d.ellipse([CX - 5, 2, CX + 4, 11], fill=c("D4A23A"))
    motif(im, CX - 3, 4, ETOILE_5, BLANC, ORS)
    cases(im, case, case_or)
    halo(im)
    separateur_grave(im, SEP)
    return im


def v4_guirlande(case, case_or):
    """4. Guirlande : une étoile sur trois de la bordure est allumée, comme une guirlande lumineuse ; la séparation
    entre les récompenses et les jours est une rangée de petites étoiles gravées."""
    im = base()
    pos = bordure(im)
    for i, (x, y) in enumerate(pos):
        if i % 3 == 0:
            etoile(im, x, y, 5, ORV)
            scintille(im, x, y + (4 if y < 20 else -4) if y in (5, BAS - 5) else y, BLANC) if False else None
    cases(im, case, case_or)
    halo(im)
    for x in range(12, W - 10, 9):
        etoile_gravee(im, x, SEP, 5)
    return im


def v5_comete(case, case_or):
    """5. Comète : une comète traverse l'en-tête de gauche à droite en ondulant sous la bordure, sa traînée allume
    au passage les étoiles de la bordure qu'elle a déjà longées."""
    im = base()
    d = ImageDraw.Draw(im)
    pos = bordure(im, dorees=set(range(0, 11)))
    pts = [(10, 11), (50, 13), (90, 11), (125, 13)]
    for k in range(len(pts) - 1):
        (x0, y0), (x1, y1) = pts[k], pts[k + 1]
        n = max(abs(x1 - x0), abs(y1 - y0))
        for s in range(n):
            t = s / n
            x, y = round(x0 + (x1 - x0) * t), round(y0 + (y1 - y0) * t)
            g = (k + t) / (len(pts) - 1)
            if libre(x, y, 1):
                d.point((x, y), CADRE if g < .3 else (ORF if g < .7 else ORV))
    cases(im, case, case_or)
    etoile(im, 128, 13, 7, ORV)
    scintille(im, 136, 9, BLANC)
    halo(im)
    separateur_grave(im, SEP)
    return im


VARIANTES = [("1_base", v1_base), ("2_filigrane", v2_filigrane), ("3_medaillon", v3_medaillon),
             ("4_guirlande", v4_guirlande), ("5_comete", v5_comete)]

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
        im.save(os.path.join(out, "bordure_" + nom + ".png"))
        x = 24 + i * (W * z + 24)
        planche.alpha_composite(im.resize((W * z, H * z), Image.NEAREST), (x, 34))
        dp.text((x, 10), nom.replace("_", " "), fill=c("FFFFFF"))
    planche.save(os.path.join(out, "planche_bordure.png"))
