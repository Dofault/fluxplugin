#!/usr/bin/env python3
"""Machine à sous (FluxMenu) : fond, rouleaux animés, icônes, thème casino (2026-10-17).

    <venv avec Pillow>/python3 gen_slots.py <menu_casino_generique.png> <ItemsAdder/contents/influcasino>

Tout dans le namespace influcasino, fichier slots.yml à part (comme plinko.yml) :
- menu_slots (fenêtre entière 176 x 221) : fond générique du casino, bandeau JACKPOT, caisse de la machine
  (liseré d'or, bande d'ampoules éteintes), fenêtre des 3 rouleaux, table des gains.
- slots_rouleau_p<POS> / slots_rouleau_flou_p<POS> : la fenêtre d'un rouleau (32 x 84 = 3 cases de 28 px),
  prise dans la bande des 8 symboles à la position POS (px, par pas de 4, période 224). Version nette et
  version floue (flou de mouvement vertical, pour la vitesse). L'ombrage « cylindre » est cuit dedans.
- slots_cadre_a / slots_cadre_b : cadre d'une case gagnante (32 x 28), deux teintes qui clignotent.
- slots_ampoules_a / slots_ampoules_b : ampoules allumées de la caisse, une sur deux (chenillard).
- objets machine_a_sous (icône du jeu dans le menu Casino) et levier (bouton « Lancer »), 16 x 16.

La géométrie (cases, rouleaux, période, ordre des symboles, gains) doit rester identique à
fr.influencraft.fluxmenu.Slots (Java) : CELL_H, REEL_X, WIN_Y, SYMBOLS, PAYS, ONE/TWO.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from symboles import N as SYM, ORDRE, SYMBOLES  # noqa: E402

FOND, PACK = sys.argv[1], sys.argv[2]

CELL_W, CELL_H = 32, 28                 # case d'un rouleau
NSYM = len(ORDRE)
PERIODE = CELL_H * NSYM                 # 224 px : hauteur de la bande
PAS = 4                                 # pas de position des glyphes (px)
WIN_Y, WIN_H = 60, CELL_H * 3           # fenêtre des rouleaux (y de son haut, hauteur)
REEL_X = [36, 72, 108]                  # x du bord gauche de chaque rouleau (écart de 4 px)
CAISSE = (27, 51, 148, 152)             # bord extérieur de la caisse (x0, y0, x1, y1 inclus)
LAMPE_X0, LAMPE_Y0, LAMPE_NX, LAMPE_NY, LAMPE_PAS = 29, 53, 23, 19, 5   # ampoules 3 x 3, pas de 5 px
MULT_TRIPLE = {"sept": "100", "couronne": "80", "diamant": "60", "etoile": "45", "cloche": "30", "prune": "20",
               "citron": "12", "cerise": "7"}
MULT_DEUX, MULT_UNE = "2", "0.5"        # deux cerises, une cerise (n'importe où sur la ligne)


def rgb(h, a=255):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (a,)


def plus_clair(c, f):
    return tuple(min(255, int(v + (255 - v) * f)) for v in c[:3]) + (255,)


def plus_sombre(c, f):
    return tuple(int(v * (1 - f)) for v in c[:3]) + (255,)


CONTOUR, FOND_SOMBRE = rgb("1A0505"), rgb("2A0306")
OR_FONCE, OR, OR_VIF, CREME = rgb("B0681E"), rgb("F0A800"), rgb("FFD94D"), rgb("FFF6E8")

# ------------------------------------------------------------------ petite police 3 x 5
POLICE = {
    "0": ["###", "#.#", "#.#", "#.#", "###"], "1": [".#.", "##.", ".#.", ".#.", "###"],
    "2": ["###", "..#", "###", "#..", "###"], "3": ["###", "..#", ".##", "..#", "###"],
    "4": ["#.#", "#.#", "###", "..#", "..#"], "5": ["###", "#..", "###", "..#", "###"],
    "6": ["###", "#..", "###", "#.#", "###"], "7": ["###", "..#", ".#.", ".#.", ".#."],
    "8": ["###", "#.#", "###", "#.#", "###"], "9": ["###", "#.#", "###", "..#", "###"],
    ".": [".", ".", ".", ".", "#"], "x": ["...", "#.#", ".#.", "#.#", "..."],
    "J": ["..#", "..#", "..#", "#.#", "###"], "A": [".#.", "#.#", "###", "#.#", "#.#"],
    "C": ["###", "#..", "#..", "#..", "###"], "K": ["#.#", "#.#", "##.", "#.#", "#.#"],
    "P": ["###", "#.#", "###", "#..", "#.."], "O": ["###", "#.#", "#.#", "#.#", "###"],
    "T": ["###", ".#.", ".#.", ".#.", ".#."],
}


def texte(im, txt, x, y, couleur, ech=1, ombre=None):
    """Écrit txt à partir de (x, y) ; ech = agrandissement ; ombre = couleur d'une ombre portée (1 px)."""
    for passe in ((ombre, 1), (couleur, 0)) if ombre else ((couleur, 0),):
        col, dec = passe
        px = x
        for c in txt:
            g = POLICE[c]
            for dy, ligne in enumerate(g):
                for dx, ch in enumerate(ligne):
                    if ch == "#":
                        for ex in range(ech):
                            for ey in range(ech):
                                im.putpixel((px + dx * ech + ex + dec, y + dy * ech + ey + dec), col)
            px += (len(g[0]) + 1) * ech
    return px


def largeur_texte(txt, ech=1):
    return sum((len(POLICE[c][0]) + 1) * ech for c in txt) - ech


# ------------------------------------------------------------------ symboles et bande
sym = {nom: SYMBOLES[nom]() for nom in ORDRE}


def cellule(nom):
    """Une case de rouleau : fond crème légèrement dégradé, filet sombre en bas, symbole centré."""
    im = Image.new("RGBA", (CELL_W, CELL_H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for y in range(CELL_H):
        f = abs(y - (CELL_H - 1) / 2) / (CELL_H / 2)
        c = tuple(int(v * (1 - 0.08 * f)) for v in CREME[:3]) + (255,)
        d.line([(0, y), (CELL_W - 1, y)], fill=c)
    # liseré de séparation des cases (haut) très discret
    d.line([(0, 0), (CELL_W - 1, 0)], fill=rgb("E6D0A8"))
    im.alpha_composite(sym[nom], ((CELL_W - SYM) // 2, (CELL_H - SYM) // 2))
    return im


bande = Image.new("RGBA", (CELL_W, PERIODE), (0, 0, 0, 255))
for i, nom in enumerate(ORDRE):
    bande.paste(cellule(nom), (0, i * CELL_H))


def flou_vertical(im, rayon):
    """Flou de mouvement vertical (moyenne glissante sur 2 * rayon + 1 px) d'une bande répétée."""
    w, h = im.size
    out = Image.new("RGBA", (w, h))
    src = im.load()
    po = out.load()
    for x in range(w):
        for y in range(h):
            r = g = b = 0
            for k in range(-rayon, rayon + 1):
                p = src[x, (y + k) % h]
                r += p[0]
                g += p[1]
                b += p[2]
            n = 2 * rayon + 1
            po[x, y] = (r // n, g // n, b // n, 255)
    return out


bande_floue = flou_vertical(bande, 12)
# la vitesse éclaircit et unifie : on tire vers le crème pour que le flou reste lisible
bande_floue = Image.blend(bande_floue, Image.new("RGBA", bande_floue.size, CREME), 0.25)


def ombrage():
    """Ombre « cylindre » du rouleau (haut et bas sombres), appliquée à chaque fenêtre."""
    om = Image.new("RGBA", (CELL_W, WIN_H), (0, 0, 0, 0))
    d = ImageDraw.Draw(om)
    for y in range(WIN_H):
        e = min(y, WIN_H - 1 - y)
        a = max(0, int(150 * (1 - e / 17) ** 1.7)) if e < 17 else 0
        d.line([(0, y), (CELL_W - 1, y)], fill=(26, 5, 5, a))
    for x in (0, CELL_W - 1):               # bords latéraux : un peu de rondeur
        d.line([(x, 0), (x, WIN_H)], fill=(26, 5, 5, 70))
    return om


OMBRE = ombrage()


def fenetre(source, pos):
    double = Image.new("RGBA", (CELL_W, PERIODE * 2))
    double.paste(source, (0, 0))
    double.paste(source, (0, PERIODE))
    f = double.crop((0, pos, CELL_W, pos + WIN_H))
    f.alpha_composite(OMBRE)
    return f


# ------------------------------------------------------------------ fond
fond = Image.open(FOND).convert("RGBA")
d = ImageDraw.Draw(fond)
x0, y0, x1, y1 = CAISSE
wx0, wx1 = REEL_X[0], REEL_X[-1] + CELL_W - 1
wy0, wy1 = WIN_Y, WIN_Y + WIN_H - 1

# caisse : liseré sombre, filet d'or, bande rouge, filet d'or, ombre intérieure
d.rectangle([x0, y0, x1, y1], fill=CONTOUR)
d.rectangle([x0 + 1, y0 + 1, x1 - 1, y1 - 1], fill=OR)
d.rectangle([x0 + 2, y0 + 2, x1 - 2, y1 - 2], fill=rgb("5A0B18"))
d.rectangle([wx0 - 3, wy0 - 3, wx1 + 3, wy1 + 3], fill=OR)
d.rectangle([wx0 - 2, wy0 - 2, wx1 + 2, wy1 + 2], fill=CONTOUR)
# reflet haut/gauche et ombre bas/droite du filet extérieur
d.line([(x0 + 1, y0 + 1), (x1 - 1, y0 + 1)], fill=OR_VIF)
d.line([(x0 + 1, y0 + 1), (x0 + 1, y1 - 1)], fill=OR_VIF)
d.line([(x0 + 1, y1 - 1), (x1 - 1, y1 - 1)], fill=OR_FONCE)
d.line([(x1 - 1, y0 + 1), (x1 - 1, y1 - 1)], fill=OR_FONCE)
# ampoules éteintes : cœur sombre cerclé d'or
ring = []
for i in range(LAMPE_NX + 1):
    ring.append((LAMPE_X0 + i * LAMPE_PAS, LAMPE_Y0))
for j in range(1, LAMPE_NY + 1):
    ring.append((LAMPE_X0 + LAMPE_NX * LAMPE_PAS, LAMPE_Y0 + j * LAMPE_PAS))
for i in range(LAMPE_NX - 1, -1, -1):
    ring.append((LAMPE_X0 + i * LAMPE_PAS, LAMPE_Y0 + LAMPE_NY * LAMPE_PAS))
for j in range(LAMPE_NY - 1, 0, -1):
    ring.append((LAMPE_X0, LAMPE_Y0 + j * LAMPE_PAS))
for lx, ly in ring:
    for dx in range(3):
        for dy in range(3):
            fond.putpixel((lx + dx, ly + dy), rgb("7A2A10"))
    fond.putpixel((lx + 1, ly + 1), rgb("C07820"))
# fond de la fenêtre : un rouleau est posé dessus (les écarts de 4 px restent sombres)
d.rectangle([wx0, wy0, wx1, wy1], fill=rgb("12020A"))
for x in REEL_X:
    fond.paste(fenetre(bande, 0), (x, WIN_Y))   # image de repos (le titre pose les rouleaux par-dessus)
# flèches de la ligne de gain, de chaque côté de la case du milieu
ym = WIN_Y + CELL_H + CELL_H // 2
for k in range(6):
    for dy in range(-(5 - k), 6 - k):
        fond.putpixel((x0 - 7 + k, ym + dy), OR_VIF if abs(dy) < 5 - k else OR_FONCE)
        fond.putpixel((x1 + 7 - k, ym + dy), OR_VIF if abs(dy) < 5 - k else OR_FONCE)

# bandeau : JACKPOT et deux étoiles
ETOILE = ["..#..", ".###.", "#####", ".###.", "#.#.#"]
for cx in (30, 140):
    for dy, ligne in enumerate(ETOILE):
        for dx, ch in enumerate(ligne):
            if ch == "#":
                fond.putpixel((cx + dx, 41 + dy), OR_VIF)
largeur = largeur_texte("JACKPOT", 2)
texte(fond, "JACKPOT", (176 - largeur) // 2, 39, OR_VIF, 2, ombre=rgb("5A1608"))

# table des gains
PX0, PY0, PX1, PY1 = 12, 156, 163, 212
d.rectangle([PX0, PY0, PX1, PY1], fill=OR_FONCE)
d.rectangle([PX0 + 1, PY0 + 1, PX1 - 1, PY1 - 1], fill=rgb("1A0308"))
d.line([(PX0 + 1, PY0 + 1), (PX1 - 1, PY0 + 1)], fill=OR)
mini = {nom: im.resize((10, 10), Image.LANCZOS) for nom, im in
        ((n, sym[n].crop((1, 1, SYM - 1, SYM - 1))) for n in sym)}
for nom in mini:   # seuil d'alpha : pas de bord à moitié transparent
    px = mini[nom].load()
    for yy in range(10):
        for xx in range(10):
            r, g, b, a = px[xx, yy]
            px[xx, yy] = (r, g, b, 255) if a >= 128 else (0, 0, 0, 0)

LIGNES = [  # (symboles affichés, multiplicateur), colonne gauche puis droite
    ([("sept", 3)], "100"), ([("couronne", 3)], "80"), ([("diamant", 3)], "60"), ([("etoile", 3)], "45"),
    ([("cloche", 3)], "30"),
    ([("prune", 3)], "20"), ([("citron", 3)], "12"), ([("cerise", 3)], "7"), ([("cerise", 2)], MULT_DEUX),
    ([("cerise", 1)], MULT_UNE),
]
for i, (syms, mult) in enumerate(LIGNES):
    col, rang = divmod(i, 5)
    ox, oy = PX0 + 6 + col * 77, PY0 + 5 + rang * 10
    x = ox
    for nom, n in syms:
        for _ in range(n):
            fond.alpha_composite(mini[nom], (x, oy))
            x += 10
    x = ox + 33
    x = texte(fond, "x" + mult, x, oy + 3, OR_VIF)
os.makedirs(os.path.join(PACK, "textures", "font"), exist_ok=True)
fond.save(os.path.join(PACK, "textures", "font", "menu_slots.png"))

# ------------------------------------------------------------------ sprites
sp = os.path.join(PACK, "textures", "slots")
os.makedirs(sp, exist_ok=True)
POSITIONS = list(range(0, PERIODE, PAS))
for pos in POSITIONS:
    fenetre(bande, pos).save(os.path.join(sp, f"rouleau_p{pos}.png"))
    fenetre(bande_floue, pos).save(os.path.join(sp, f"rouleau_flou_p{pos}.png"))

for nom, couleur in (("a", rgb("FFFFFF")), ("b", rgb("FFD94D"))):
    cadre = Image.new("RGBA", (CELL_W, CELL_H), (0, 0, 0, 0))
    dc = ImageDraw.Draw(cadre)
    dc.rectangle([0, 0, CELL_W - 1, CELL_H - 1], outline=couleur)
    dc.rectangle([1, 1, CELL_W - 2, CELL_H - 2], outline=couleur[:3] + (150,))
    dc.rectangle([2, 2, CELL_W - 3, CELL_H - 3], outline=couleur[:3] + (60,))
    cadre.save(os.path.join(sp, f"cadre_{nom}.png"))

LARGEUR_C, HAUTEUR_C = x1 - x0 + 1, y1 - y0 + 1
for nom, parite in (("a", 0), ("b", 1)):
    am = Image.new("RGBA", (LARGEUR_C, HAUTEUR_C), (0, 0, 0, 0))
    for i, (lx, ly) in enumerate(ring):
        if i % 2 != parite:
            continue
        for dx in range(3):
            for dy in range(3):
                am.putpixel((lx - x0 + dx, ly - y0 + dy), rgb("FFC860"))
        am.putpixel((lx - x0 + 1, ly - y0 + 1), rgb("FFFFFF"))
        for dx, dy in ((-1, 1), (3, 1), (1, -1), (1, 3)):     # halo orangé en croix
            xx, yy = lx - x0 + dx, ly - y0 + dy
            if 0 <= xx < LARGEUR_C and 0 <= yy < HAUTEUR_C:
                am.putpixel((xx, yy), rgb("E0602C", 200))
    am.save(os.path.join(sp, f"ampoules_{nom}.png"))

# ------------------------------------------------------------------ icônes d'objets (16 x 16)
os.makedirs(os.path.join(PACK, "textures", "item"), exist_ok=True)


def icone_machine():
    """Machine à sous vue de face : caisse rouge à liseré d'or, 3 rouleaux, fente à jetons, levier à droite."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([1, 0, 12, 15], fill=CONTOUR)
    d.rectangle([2, 1, 11, 14], fill=rgb("C8141F"))
    d.line([(2, 1), (11, 1)], fill=OR_VIF)
    d.line([(2, 14), (11, 14)], fill=rgb("8A0A14"))
    d.rectangle([2, 3, 11, 9], fill=CONTOUR)
    for x in (3, 6, 9):                      # trois rouleaux crème, un symbole chacun
        d.rectangle([x, 4, x + 1, 8], fill=CREME)
    d.rectangle([3, 5, 4, 6], fill=rgb("D41A28"))
    d.rectangle([6, 6, 7, 7], fill=OR)
    d.rectangle([9, 5, 10, 6], fill=rgb("D41A28"))
    d.rectangle([4, 11, 9, 12], fill=CONTOUR)
    d.rectangle([5, 11, 8, 11], fill=OR)
    # levier : tige grise, boule rouge
    d.rectangle([14, 4, 14, 9], fill=rgb("C0C0C8"))
    d.rectangle([13, 4, 13, 9], fill=CONTOUR)
    d.rectangle([15, 4, 15, 9], fill=CONTOUR)
    d.rectangle([13, 1, 15, 3], fill=rgb("D41A28"))
    d.point((13, 1), fill=rgb("FFB0A8"))
    d.point((15, 3), fill=rgb("8A0A14"))
    return im


def icone_levier():
    """Levier de machine à sous incliné, boule rouge, socle d'or."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([2, 12, 13, 15], fill=CONTOUR)
    d.rectangle([3, 12, 12, 14], fill=OR)
    d.line([(3, 12), (12, 12)], fill=OR_VIF)
    d.line([(3, 14), (12, 14)], fill=OR_FONCE)
    d.line([(6, 11), (10, 5)], fill=CONTOUR, width=4)
    d.line([(6, 11), (10, 5)], fill=rgb("D0D0D8"), width=2)
    d.ellipse([8, 0, 14, 6], fill=CONTOUR)
    d.ellipse([9, 1, 13, 5], fill=rgb("D41A28"))
    d.point((10, 2), fill=rgb("FFB0A8"))
    d.point((11, 2), fill=rgb("FF7A70"))
    d.point((12, 4), fill=rgb("8A0A14"))
    return im


icone_machine().save(os.path.join(PACK, "textures", "item", "machine_a_sous.png"))
icone_levier().save(os.path.join(PACK, "textures", "item", "levier.png"))

# ------------------------------------------------------------------ config ItemsAdder
L = ["info:", "  namespace: influcasino", "",
     "# Généré par plugins/FluxMenu/assets/slots/gen_slots.py, ne pas éditer à la main.",
     "# Machine à sous : fond, rouleaux (fenêtre de 3 cases à une position de la bande, nette ou floue), cadres",
     "# de gain, ampoules, icônes. Glyphes posés au pixel près dans le titre (y_position = 13 - haut).", "",
     "items:",
     "  machine_a_sous:", "    display_name: ' '", "    resource:", "      material: PAPER", "      generate: true",
     "      textures:", "      - item/machine_a_sous.png",
     "  levier:", "    display_name: ' '", "    resource:", "      material: PAPER", "      generate: true",
     "      textures:", "      - item/levier.png", "",
     "font_images:",
     "  menu_slots:", "    suggest_in_command: false", "    show_in_gui: false", "    path: font/menu_slots.png",
     "    scale_ratio: 221", "    y_position: 13"]


def glyphe(nom, chemin, h, haut):
    L.extend([f"  {nom}:", "    suggest_in_command: false", "    show_in_gui: false",
              f"    path: {chemin}", f"    scale_ratio: {h}", f"    y_position: {13 - haut}"])


for pos in POSITIONS:
    glyphe(f"slots_rouleau_p{pos}", f"slots/rouleau_p{pos}.png", WIN_H, WIN_Y)
    glyphe(f"slots_rouleau_flou_p{pos}", f"slots/rouleau_flou_p{pos}.png", WIN_H, WIN_Y)
for nom in "ab":
    glyphe(f"slots_cadre_{nom}", f"slots/cadre_{nom}.png", CELL_H, WIN_Y + CELL_H)
    glyphe(f"slots_ampoules_{nom}", f"slots/ampoules_{nom}.png", HAUTEUR_C, y0)
open(os.path.join(PACK, "slots.yml"), "w", encoding="utf-8").write("\n".join(L) + "\n")
print(len(POSITIONS) * 2 + 4, "glyphes,", len(ring), "ampoules")
