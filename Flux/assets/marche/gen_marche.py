#!/usr/bin/env python3
"""Visuels du marché (FluxAuctionHouse + Flux) : pack ItemsAdder « influmarche ».

    <venv avec Pillow>/python3 gen_marche.py <menu_generique.png> <contents/influmarche> [dossier d'aperçus]

Même trame que les menus Ville (gen_ville.py, dont on réutilise les outils sans lancer son main) : en-tête et
onglet du menu Métiers, fond générique, cadre doré, bandeau illustré sur les rangées 0 et 1, cases arrondies de
l'utilisateur. Les menus du marché qui utilisent la zone de l'inventaire (marché, offre, mise en vente) prennent
la fenêtre entière ; les listes (mes ventes, mes enchères, historique) s'arrêtent au coffre comme les listes Ville.

Écrit dans <contents/influmarche> : textures/icones/*.png (icônes 16 x 16 au gabarit influitems),
textures/font/gui/*.png (fonds 176 x 221), config.yml. Relancer après modification, puis /iareload, /iazip,
/fluxmenu reload.
"""
import math
import os
import random
import sys

ICI = os.path.dirname(os.path.abspath(__file__))
VILLE = os.path.join(ICI, "..", "ville", "gen_ville.py")
GENERIQUE, SORTIE = sys.argv[1], sys.argv[2]
APERCUS = sys.argv[3] if len(sys.argv) > 3 else None

# Outils de gen_ville.py (tuile, icone, bandeau, cadre, entete, onglet, texte...) chargés sans exécuter son main().
_argv = sys.argv
sys.argv = [VILLE, GENERIQUE, "/nonexistent"]
_src = open(VILLE, encoding="utf-8").read().rsplit("\nmain()", 1)[0]
_ns = {"__file__": VILLE, "__name__": "gen_ville"}
exec(compile(_src, VILLE, "exec"), _ns)
sys.argv = _argv
globals().update({k: v for k, v in _ns.items() if not k.startswith("__") and k not in ("main", "SORTIE", "APERCUS", "GENERIQUE")})

from PIL import Image  # noqa: E402

# ── Icônes ─────────────────────────────────────────────────────────────────
PICTOS_MARCHE = {
    "balance": ["...##...", "########", "#..##..#", "##.##.##", "...##...", ".######."],
    "grille": ["###.###.", "###.###.", "........", "###.###.", "###.###.", "........"],
    "rachat": ["..#.....", ".######.", "..#.....", "....###.", "...#.#.#", "....###."],
    "etiquette": ["..######", ".#.....#", "#..##..#", ".#.....#", "..######", "........"],
    "marteau": ["##......", "###.....", ".####...", "..#.##..", ".....##.", "####..##"],
    "horloge": ["..####..", ".#..#.#.", "#...#..#", "#...###.", ".#....#.", "..####.."],
    "courbe": ["#......#", "#.....#.", "#..#.#..", "#.#.#...", "##......", "########"],
    "panier": ["..#..#..", ".#....#.", "########", ".#.##.#.", ".#.##.#.", "..####.."],
}
_ns["PICTOS"].update(PICTOS_MARCHE)

ICONES_MARCHE = {
    "marche": ("balance", "or"),
    "tout": ("grille", "lavande"),
    "rachat": ("rachat", "bleu"),
    "mes_ventes": ("etiquette", "or"),
    "enchere": ("marteau", "rose"),
    "historique": ("horloge", "lavande"),
    "moyenne": ("courbe", "bleu"),
    "acheter": ("panier", "vert"),
}


# ── Bandeaux ───────────────────────────────────────────────────────────────
def fanions(t, w, y0, rnd):
    """Guirlande de fanions aux couleurs de la charte (or, rose, violet clair) d'un bord à l'autre."""
    couleurs = [OR_VIF, hexa("e060a0"), hexa("b376e5"), OR_CHAUD]
    for x in range(w):
        y = y0 + int(2.5 * math.sin(math.pi * (x % 42) / 42))
        t.px(x, y, hexa("2a2040"))
        if x % 6 == 2:
            c = couleurs[(x // 6) % len(couleurs)]
            for k in range(3):
                t.rect(x - (2 - k) // 2, y + 1 + k, x + (2 - k) // 2, y + 1 + k, c)


def halle(t, w, h, rnd):
    """Halle couverte façon Baltard au centre : verrière en arc, arcades éclairées, horloge dorée."""
    sol = h - 1
    cx, lw = w // 2, 62
    x0 = cx - lw // 2
    verre, verre_clair, fer = hexa("6b5a8a"), hexa("9b86c6"), hexa("140f1f")
    haut_mur = 11
    t.rect(x0, sol - haut_mur, x0 + lw - 1, sol, fer)
    # verrière en demi-ellipse au-dessus des murs
    for x in range(x0, x0 + lw):
        k = (x - cx) / (lw / 2)
        hy = int(13 * math.sqrt(max(0, 1 - k * k)))
        for y in range(sol - haut_mur - hy, sol - haut_mur):
            c = verre_clair if (x + y) % 5 == 0 else verre
            t.px(x, y, c)
        t.px(x, sol - haut_mur - hy - 1, fer)
    for x in range(x0 + 4, x0 + lw - 3, 7):  # nervures de la verrière
        k = (x - cx) / (lw / 2)
        hy = int(13 * math.sqrt(max(0, 1 - k * k)))
        t.rect(x, sol - haut_mur - hy, x, sol - haut_mur, fer)
    # arcades éclairées (lumière chaude du marché)
    for ax in range(x0 + 3, x0 + lw - 6, 8):
        t.rect(ax, sol - 7, ax + 4, sol, hexa("ffd94d", 230))
        t.rect(ax + 1, sol - 8, ax + 3, sol - 8, hexa("ffd94d", 230))
        t.rect(ax + 1, sol - 2, ax + 3, sol, hexa("f0a800"))
    # horloge dorée au fronton
    t.rect(cx - 2, sol - haut_mur - 17, cx + 2, sol - haut_mur - 13, OR_CHAUD)
    t.px(cx, sol - haut_mur - 15, hexa("2a2040"))
    t.px(cx + 1, sol - haut_mur - 15, hexa("2a2040"))


def etiquettes_suspendues(t, w, rnd, y0=6):
    """Étiquettes de prix dorées pendues à un fil."""
    for x in range(w):
        t.px(x, y0 + int(1.5 * math.sin(x / 9)), hexa("e2cdff", 160))
    for x in range(8, w - 8, rnd.randrange(16, 22)):
        y = y0 + int(1.5 * math.sin(x / 9))
        t.rect(x, y + 1, x, y + 2, hexa("e2cdff", 160))
        t.rect(x - 2, y + 3, x + 3, y + 7, OR_CHAUD)
        t.rect(x - 1, y + 3, x + 2, y + 3, OR_VIF)
        t.px(x, y + 4, hexa("45365f"))
        t.rect(x - 1, y + 6, x + 2, y + 6, hexa("8a5a14"))


def foule(t, w, h, rnd):
    """Silhouettes d'enchérisseurs au premier plan, quelques mains levées (pancartes roses)."""
    sol = h - 1
    for x in range(-2, w, 5):
        hx = rnd.randrange(5, 8)
        c = hexa("140f1f")
        t.rect(x, sol - hx + 2, x + 3, sol, c)
        t.rect(x + 1, sol - hx, x + 2, sol - hx + 1, c)
        if rnd.random() < 0.3:
            t.rect(x + 3, sol - hx - 4, x + 3, sol - hx + 1, c)
            t.rect(x + 2, sol - hx - 7, x + 5, sol - hx - 5, hexa("e060a0"))


def marteau_geant(t, cx, y):
    """Marteau d'enchères doré au-dessus de la foule."""
    for i in range(10):
        t.rect(cx - 6 + i, y + 6 + i // 2, cx - 5 + i, y + 6 + i // 2, hexa("8a5a14"))
    t.rect(cx - 10, y, cx - 3, y + 4, OR_CHAUD)
    t.rect(cx - 10, y, cx - 3, y, OR_VIF)
    t.rect(cx + 2, y + 13, cx + 10, y + 15, hexa("2a2040"))


def vitrine(t, w, h, rnd):
    """Vitrine : rideaux de velours fuchsia sur les côtés, faisceau de lumière sur un socle central."""
    sol = h - 1
    cx = w // 2
    for y in range(h):
        larg = 6 + y // 3
        for x in range(cx - larg, cx + larg + 1):
            if 0 <= x < w and (x + y) % 2 == 0:
                t.px(x, y, hexa("ffd94d", 70))
    t.rect(cx - 12, sol - 5, cx + 12, sol, hexa("140f1f"))
    t.rect(cx - 10, sol - 7, cx + 10, sol - 6, hexa("2a2040"))
    t.rect(cx - 10, sol - 7, cx + 10, sol - 7, OR_CHAUD)
    for cote in (0, 1):
        for x in range(26):
            xx = x if cote == 0 else w - 1 - x
            pli = hexa("c8256a") if (x // 3) % 2 == 0 else hexa("7a2d58")
            bas = int(h * (0.95 - 0.6 * (x / 26) ** 1.4))
            t.rect(xx, 0, xx, bas, pli)
        for x in range(26):
            xx = x if cote == 0 else w - 1 - x
            t.px(xx, 0, OR_CHAUD)
            t.px(xx, 1, OR_VIF if x % 4 == 0 else OR_CHAUD)


def ciel(w, h, graine):
    """Ciel du bandeau Ville seul (dégradé tramé, étoiles), sans immeubles."""
    im = bandeau(w, h, graine, "ville")
    rnd = random.Random(graine)
    t = Toile(w, h)
    t.im = Image.new("RGBA", (w, h))
    for y in range(h):
        k = y / (h - 1)
        base = melange(CIEL_HAUT, CIEL_BAS, min(1, k * 1.2))
        if k > 0.4:
            base = melange(base, ROSE, (k - 0.4) * 0.75)
        alt = melange(base, CIEL_HAUT, 0.12)
        for x in range(w):
            t.px(x, y, base if (x + y) % 2 else alt)
    for _ in range(w // 8):
        t.px(rnd.randrange(w), rnd.randrange(int(h * 0.6)), OR_VIF if rnd.random() < 0.4 else hexa("e8e0f5"))
    return t


def bandeau_marche(w, h, graine, theme):
    rnd = random.Random(graine)
    if theme in ("marche", "vendre", "ventes"):
        t = Toile(w, h)
        t.im = bandeau(w, h, graine, "echoppes")
        if theme == "marche":
            halle(t, w, h, rnd)
            fanions(t, w, 3, rnd)
        else:
            etiquettes_suspendues(t, w, rnd)
        return t.im
    if theme == "historique":
        return bandeau(w, h, graine, "commerce")
    t = ciel(w, h, graine)
    if theme == "encheres":
        marteau_geant(t, w // 2, 4)
        foule(t, w, h, rnd)
    elif theme == "offre":
        vitrine(t, w, h, rnd)
    return t.im


# ── Fonds ──────────────────────────────────────────────────────────────────
GRILLE_MARCHE = [r * 9 + c for r in range(2, 6) for c in range(1, 8)]
GRILLE_LISTE = [r * 9 + c for r in range(2, 5) for c in range(1, 8)]
INVENTAIRE = list(range(54, 90))


def tampon_dore():
    """Case arrondie de l'utilisateur teintée d'or (objet mis en avant : offre, objet à vendre). Un vrai cadre
    déborderait sur le bandeau, juste au-dessus."""
    sombre, clair = hexa("5a3a0c"), hexa("f2c85b")
    t = {}
    for (dx, dy), (r, g, b, a) in tampon_case().items():
        k = min(1.0, (0.299 * r + 0.587 * g + 0.114 * b) / 140)
        t[(dx, dy)] = melange(sombre, clair, k)[:3] + (a,)
    return t


def cadre_case(im, case):
    poser(im, tampon_dore(), *objet(case))


def fond(titre, theme, graine, cases, plein=True, etiquettes=None, cadres=()):
    im = Image.open(GENERIQUE).convert("RGBA") if plein else generique_raccourci(GENERIQUE, HAUT, BAS)
    entete(im)
    x0, y0, x1, y1 = BANDEAU
    im.alpha_composite(bandeau_marche(x1 - x0 + 1, y1 - y0 + 1, graine, theme), (x0, y0))
    cadre(im, x0, y0, x1, y1)
    onglet(im, titre, 88)
    tampon = tampon_case()
    for c in cases:
        poser(im, tampon, *objet(c))
    for c in cadres:
        cadre_case(im, c)
    for rangee, txt in (etiquettes or {}).items():
        etiquette(im, txt, rangee)
    return im


BARRE = [45, 47, 48, 49, 50, 51, 53]
FONDS = {
    # Marché : sources (colonne 0), grille 7 x 4, outils (colonne 8), catégories et raccourcis dans l'inventaire.
    "menu_marche": lambda: fond("MARCHÉ", "marche", 211, [0, 8, 9, 17, 18, 27, 36, 45, 26, 35, 44, 53] + GRILLE_MARCHE
                                + list(range(54, 63)) + [64, 66, 68, 70]),
    "menu_marche_offre": lambda: fond("OFFRE", "offre", 223, [0, 8, 20, 22, 24] + list(range(28, 35)) + [38, 40, 42]
                                      + list(range(55, 62)), etiquettes={5: "AILLEURS SUR LE MARCHÉ"}, cadres=[22]),
    "menu_marche_vendre": lambda: fond("VENDRE", "vendre", 227, [0, 8, 19, 22, 25] + list(range(28, 35)) + list(range(37, 44))
                                       + [45, 49, 53] + INVENTAIRE, cadres=[22]),
    "menu_marche_ventes": lambda: fond("MES VENTES", "ventes", 229, [0, 8] + GRILLE_LISTE + BARRE, plein=False),
    "menu_marche_encheres": lambda: fond("MES ENCHÈRES", "encheres", 233, [0, 8] + GRILLE_LISTE + BARRE, plein=False),
    "menu_marche_historique": lambda: fond("HISTORIQUE", "historique", 239, [0, 8] + GRILLE_LISTE + BARRE, plein=False),
}


def main():
    ico = os.path.join(SORTIE, "textures", "icones")
    gui = os.path.join(SORTIE, "textures", "font", "gui")
    os.makedirs(ico, exist_ok=True)
    os.makedirs(gui, exist_ok=True)
    images = {nom: icone(picto, pal) for nom, (picto, pal) in ICONES_MARCHE.items()}
    for nom, im in images.items():
        im.save(os.path.join(ico, nom + ".png"))
    fonds = {nom: f() for nom, f in FONDS.items()}
    for nom, im in fonds.items():
        im.save(os.path.join(gui, nom + ".png"))

    lignes = [
        "# Généré par plugins/fluxplugin/Flux/assets/marche/gen_marche.py : ne pas modifier à la main.",
        "# Marché (FluxAuctionHouse + Flux, Labs) : icônes de boutons et fonds de menus.",
        "info:",
        "  namespace: influmarche",
        "items:",
    ]
    for nom in images:
        lignes += ["  %s:" % nom, "    display_name: ' '", "    resource:", "      material: PAPER", "      generate: true",
                   "      textures:", "      - icones/%s.png" % nom]
    lignes.append("font_images:")
    for nom in fonds:
        lignes += ["  # Fond plein écran 176 x 221.", "  %s:" % nom, "    suggest_in_command: false", "    show_in_gui: false",
                   "    path: font/gui/%s.png" % nom, "    scale_ratio: 221", "    y_position: 13"]
    with open(os.path.join(SORTIE, "config.yml"), "w") as f:
        f.write("\n".join(lignes) + "\n")

    if APERCUS:
        os.makedirs(APERCUS, exist_ok=True)
        planche = Image.new("RGBA", (len(images) * 20 + 4, 24), hexa("1e1828"))
        for i, im in enumerate(images.values()):
            planche.alpha_composite(im, (4 + i * 20, 4))
        planche.resize((planche.width * 6, planche.height * 6), Image.NEAREST).save(os.path.join(APERCUS, "icones.png"))
        for nom, im in fonds.items():
            im.resize((176 * 3, 221 * 3), Image.NEAREST).save(os.path.join(APERCUS, nom + ".png"))
    print(len(images), "icônes,", len(fonds), "fonds ->", SORTIE)


main()
