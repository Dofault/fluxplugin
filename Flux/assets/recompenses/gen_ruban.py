"""Menu Récompenses (/recompenses) : fond générique des menus (menu_generique.png raccourci) et case arrondie de
l'utilisateur (case_arrondie.png), avec un motif propre au menu, le ruban de cadeau (variante 4 retenue le
2026-10-28, cinq déclinaisons). Couleurs du ruban et cases dorées accordées aux teintes du générique.

Disposition (coffre de 6 rangées, inventaire masqué) : récompenses en attente 10-16, 19-25, 28-34 ;
page précédente 36, Tout récupérer 40, page suivante 44 ; retour 54, récompense du jour 58 ;
frise des 7 jours 64-70, semaines 63 et 71.

Usage : python gen_ruban.py <dossier de sortie>  (Pillow requis)
"""
import os
import sys
from PIL import Image, ImageDraw

ICI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(ICI))
from fond_lib import generique_raccourci, objet, poser, tampon_case  # noqa: E402

W, H = 176, 221
BAS = 186
GRILLE = list(range(10, 17)) + list(range(19, 26)) + list(range(28, 35))
NAV = [36, 44]
TOUT = 40
RETOUR, JOUR = 54, 58
FRISE = list(range(64, 71))
SEMAINES = [63, 71]
SEP = 131
CX = objet(13)[0] + 8

# teintes du générique
CONTOUR = (21, 17, 27, 255)
BORD = (127, 102, 163, 255)
CADRE = (89, 70, 116, 255)
CREUX = (44, 35, 57, 255)

CHIFFRES = {
    "1": ["010", "110", "010", "010", "111"], "2": ["110", "001", "010", "100", "111"],
    "3": ["110", "001", "010", "001", "110"], "4": ["101", "101", "111", "001", "001"],
    "5": ["111", "100", "110", "001", "110"], "6": ["011", "100", "110", "101", "010"],
    "7": ["111", "001", "010", "010", "010"],
}


def c(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def teinte(tampon, rampe):
    """Recolore la case : ses couleurs classées du plus sombre au plus clair prennent celles de la rampe."""
    couleurs = sorted({v for v in tampon.values()}, key=lambda v: sum(v[:3]))
    table = {v: rampe[round(i * (len(rampe) - 1) / max(1, len(couleurs) - 1))] for i, v in enumerate(couleurs)}
    return {k: table[v] for k, v in tampon.items()}


ROSE = dict(ruban=c("B8407E"), clair=c("E07AAE"), fonce=c("6E1E48"))
OR = dict(ruban=c("D49A2A"), clair=c("F2CC6A"), fonce=c("7A5410"))
RAMPE_OR = [CONTOUR, c("3A2A10"), c("5A4014"), c("7A5818"), c("9A7020"), c("B8882A"), c("D4A23A"), c("F0C860")]
RAMPE_ROSE = [CONTOUR, c("2E1424"), c("4A1E38"), c("62284A"), c("7A305C"), c("94406E"), c("B05484"), c("D070A0")]


def ruban_v(d, r, x, y0, y1, liseré=None):
    d.rectangle([x - 3, y0, x + 2, y1], r["ruban"])
    d.line([(x - 3, y0), (x - 3, y1)], liseré or r["clair"])
    d.line([(x + 2, y0), (x + 2, y1)], liseré or r["fonce"])


def ruban_h(d, r, y, x0, x1, liseré=None):
    d.rectangle([x0, y - 3, x1, y + 2], r["ruban"])
    d.line([(x0, y - 3), (x1, y - 3)], liseré or r["clair"])
    d.line([(x0, y + 2), (x1, y + 2)], liseré or r["fonce"])


def noeud(d, r, cx, cy, s, pans=True):
    col, fonce, clair = r["ruban"], r["fonce"], r["clair"]
    for sens in (-1, 1):
        b = [(cx, cy), (cx + sens * (s + 4), cy - s), (cx + sens * (s + 6), cy - s + 2),
             (cx + sens * (s + 6), cy + s - 3), (cx + sens * (s + 4), cy + s - 1)]
        d.polygon(b, col)
        d.line([b[1], b[2]], clair)
        d.line([b[3], b[4], b[0]], fonce)
        if pans:
            d.polygon([(cx + sens, cy + 1), (cx + sens * (s // 2 + 3), cy + s + 3), (cx + sens * (s // 2), cy + s + 4)], fonce)
    d.rectangle([cx - 2, cy - 2, cx + 2, cy + 2], col)
    d.line([(cx - 2, cy - 2), (cx + 2, cy - 2)], clair)
    d.line([(cx - 2, cy + 2), (cx + 2, cy + 2)], fonce)


def grave(im, x, y, motif, creux=CONTOUR, lumiere=CADRE):
    """Gravure : le tracé en creux, une lumière d'un pixel dessous."""
    px = im.load()
    for dy, ligne in enumerate(motif):
        for dx, b in enumerate(ligne):
            if b == "1":
                px[x + dx, y + dy + 1] = lumiere
    for dy, ligne in enumerate(motif):
        for dx, b in enumerate(ligne):
            if b == "1":
                px[x + dx, y + dy] = creux


CADEAU = ["0101010", "0011100", "1111111", "1001001", "1001001", "1111111"]


def base():
    return generique_raccourci(os.path.join(os.path.dirname(ICI), "menu_generique.png"), 0, BAS)


def cases(im, case, dore, tout_jour=True):
    for n in GRILLE + NAV + [RETOUR] + FRISE + SEMAINES:
        poser(im, case, *objet(n))
    for n in ([TOUT, JOUR] if tout_jour else []):
        poser(im, dore, *objet(n))


def numeros(im):
    for i, n in enumerate(FRISE):
        x, y = objet(n)
        grave(im, x + 6, y + 18, CHIFFRES[str(i + 1)])


def separateur_grave(im, y):
    d = ImageDraw.Draw(im)
    d.line([(6, y), (W - 7, y)], CREUX)
    d.line([(6, y + 1), (W - 7, y + 1)], CADRE)


# ------------------------------------------------------------------ variantes

def v1_ruban_croise(case, case_or):
    """1. Ruban rose croisé : nœud en tête, ruban vertical derrière la colonne du milieu, ruban horizontal noué
    entre les récompenses et les jours."""
    im = base()
    d = ImageDraw.Draw(im)
    ruban_v(d, ROSE, CX, 3, SEP)
    ruban_h(d, ROSE, SEP, 2, W - 3)
    cases(im, case, case_or)
    noeud(d, ROSE, CX, 8, 6)
    noeud(d, ROSE, CX, SEP, 5, pans=False)
    numeros(im)
    return im


def v2_ruban_horizontal(case, case_or):
    """2. Ruban rose horizontal seul, noué au centre, nœud en tête relié par un pan court : la grille reste nette,
    le ruban marque seulement la séparation."""
    im = base()
    d = ImageDraw.Draw(im)
    ruban_h(d, ROSE, SEP, 2, W - 3)
    cases(im, case, case_or)
    noeud(d, ROSE, CX, 8, 6)
    noeud(d, ROSE, CX, SEP, 6)
    for x in (24, W - 25):
        grave(im, x - 3, 6, CADEAU)
    numeros(im)
    return im


def v3_ruban_or(case, case_or):
    """3. Ruban or croisé et cases roses : l'inverse de la 1, plus festif."""
    im = base()
    d = ImageDraw.Draw(im)
    ruban_v(d, OR, CX, 3, SEP)
    ruban_h(d, OR, SEP, 2, W - 3)
    case_rose = teinte(case_or, RAMPE_ROSE)
    cases(im, case, case_rose)
    noeud(d, OR, CX, 8, 6)
    noeud(d, OR, CX, SEP, 5, pans=False)
    numeros(im)
    return im


def v4_ruban_lisere(case, case_or):
    """4. Ruban rose liseré d'or, gros nœud en tête, petits cadeaux gravés aux coins de l'en-tête et trait gravé
    sous la frise des jours."""
    im = base()
    d = ImageDraw.Draw(im)
    ruban_v(d, ROSE, CX, 3, SEP, OR["ruban"])
    ruban_h(d, ROSE, SEP, 2, W - 3, OR["ruban"])
    cases(im, case, case_or)
    noeud(d, ROSE, CX, 9, 7)
    noeud(d, ROSE, CX, SEP, 5, pans=False)
    for x in (12, W - 19):
        grave(im, x, 6, CADEAU)
    numeros(im)
    return im


def v5_couvercle(case, case_or):
    """5. Couvercle : le ruban fait le tour de l'en-tête comme sur un couvercle de boîte, nœud au centre ;
    séparation gravée avec un petit nœud, frise des jours sous un ruban fin."""
    im = base()
    d = ImageDraw.Draw(im)
    ruban_h(d, ROSE, 13, 2, W - 3)
    separateur_grave(im, SEP)
    cases(im, case, case_or)
    noeud(d, ROSE, CX, 9, 7)
    noeud(d, ROSE, CX, SEP, 4, pans=False)
    numeros(im)
    return im


VARIANTES = [("1_ruban_croise", v1_ruban_croise), ("2_ruban_horizontal", v2_ruban_horizontal),
             ("3_ruban_or", v3_ruban_or), ("4_ruban_lisere_or", v4_ruban_lisere), ("5_couvercle", v5_couvercle)]

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
        im.save(os.path.join(out, nom + ".png"))
        x = 24 + i * (W * z + 24)
        planche.alpha_composite(im.resize((W * z, H * z), Image.NEAREST), (x, 34))
        dp.text((x, 10), nom.replace("_", " "), fill=c("FFFFFF"))
    planche.save(os.path.join(out, "planche_generique.png"))
