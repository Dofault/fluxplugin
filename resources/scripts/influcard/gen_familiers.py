#!/usr/bin/env python3
"""Génère tout le contenu des familiers InfluCard (Labs) :

- modèles BetterModel animés      -> <sortie>/BetterModel/models/familier_<id>.bbmodel
- textures des cartes et boosters  -> <sortie>/ItemsAdder/contents/fluxpet/
- familiers                       -> <sortie>/InfluPet/pets/<id>.yml (ex-EcoPets)
- cartes, familiers et boosters    -> <sortie>/InfluPet/contenu.yml (écrit dans content/ par merge_config.py)

Usage : gen_familiers.py <sortie> [modèle esprit de feu .bbmodel] [carte modèle 32x32] [booster modèle 32x32]
(par défaut : scripts/influcard/sources/)
"""
import base64
import colorsys
import json
import math
import os
import sys

import yaml

sys.path.insert(0, os.path.dirname(__file__))
from png_util import read, write, encode  # noqa: E402
import modeles as M  # noqa: E402
from effets_familiers import EFFETS, FAMILIERS_DESACTIVES, MAX_LEVEL, METIERS_DESACTIVES  # noqa: E402
from familiers_sol import AU_SOL, poser as poser_au_sol  # noqa: E402

_SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sources")
OUT = sys.argv[1]
FIRE_MODEL = sys.argv[2] if len(sys.argv) > 2 else os.path.join(_SRC, "familier_esprit_feu_v2.bbmodel")
CARD_TEMPLATE = sys.argv[3] if len(sys.argv) > 3 else os.path.join(_SRC, "carte_modele.png")
BOOSTER_TEMPLATE = sys.argv[4] if len(sys.argv) > 4 else os.path.join(_SRC, "booster_modele.png")

# --------------------------------------------------------------------------- éléments et raretés

ELEMENTS = {
    # id: (nom, teinte, saturation, luminosité, couleur de texte)
    "feu": ("Feu", 18, 1.0, 1.0, "#FF8A3D"),
    "eau": ("Eau", 205, 1.0, 1.0, "#4FA8FF"),
    "nature": ("Nature", 110, 0.85, 0.95, "#6CD46C"),
    "foudre": ("Foudre", 52, 1.0, 1.05, "#FFE14D"),
    "glace": ("Glace", 190, 0.45, 1.1, "#9FE8F5"),
    "ombre": ("Ombre", 268, 0.7, 0.62, "#8C7BB8"),
    "lumiere": ("Lumière", 46, 0.35, 1.15, "#FFF1C2"),
    "terre": ("Terre", 28, 0.55, 0.78, "#C69A6B"),
}

RARITIES = ["simple", "unique", "elite", "ultimate", "legendary", "fabled"]
# Facteur de puissance des effets et d'XP demandée, par rareté
POWER = {"simple": 1.0, "unique": 1.2, "elite": 1.4, "ultimate": 1.65, "legendary": 1.9, "fabled": 2.3}
XP_BASE = {"simple": 40, "unique": 50, "elite": 60, "ultimate": 75, "legendary": 90, "fabled": 110}
# Probabilité de débloquer le familier en un booster de 5 tirages (son propre booster)
UNLOCK_PER_BOOSTER = {"simple": 0.35, "unique": 0.20, "elite": 0.12, "ultimate": 0.07,
                      "legendary": 0.035, "fabled": 0.01}
# XP donnée au familier par carte utilisée (clic droit)
# Une carte = 1 XP (2026-10-04) : les paliers de niveau sont comptés en cartes. OLD_CARD_XP = ancienne XP
# par carte, sert à convertir la courbe d'origine (XP_BASE * n^1.6) en nombre de cartes sans changer le rythme.
OLD_CARD_XP = {"simple": 150, "unique": 220, "elite": 320, "ultimate": 450, "legendary": 650, "fabled": 1000}
CARD_XP = {r: 1 for r in OLD_CARD_XP}

# Poids des cartes, chances d'adoption et courbes de niveau : tirages.py (partagé avec gen_familiers_boss.py).
from tirages import (SERIES_WEIGHTS, PRESTIGE_WEIGHTS, UNLOCK_TARGET, PRESTIGE_UNLOCK,  # noqa: E402
                     CAREER_DRAWS, PRESTIGE_DRAWS, pet_draw_rate, level_curve, cards_to_max)
from familiers_boss import BY_JOB as BOSS_BY_JOB  # noqa: E402
CARD_WEIGHTS = {
    "ordinaire": (400, 280, 170, 100, 45, 5),
    "tendance": (300, 290, 200, 130, 65, 15),
    "virale": (180, 260, 250, 180, 100, 30),
    "phenomene": (100, 200, 250, 230, 160, 60),
}

def draws(job):
    return PRESTIGE_DRAWS if job == "*" else CAREER_DRAWS


# Couleurs de rareté du serveur (EcoEnchants rarity.yml, tags influtags) : les cadres des cartes en
# dérivent (accent = couleur exacte, puis variantes foncée, claire et contour).
RARITY_COLORS = {"simple": "#AAAAAA", "unique": "#37C44A", "elite": "#3283A8",
                 "ultimate": "#B376E5", "legendary": "#F0A800", "fabled": "#C8256A"}


def _frame(color):
    r, g, b = (int(color[i:i + 2], 16) for i in (1, 3, 5))
    shade = lambda k: "#%02X%02X%02X" % (int(r * k), int(g * k), int(b * k))
    tint = lambda k: "#%02X%02X%02X" % (int(r + (255 - r) * k), int(g + (255 - g) * k), int(b + (255 - b) * k))
    return (shade(0.16), tint(0.55), shade(0.62), color)  # contour, clair, moyen, accent


FRAMES = {r: _frame(c) for r, c in RARITY_COLORS.items()}

# --------------------------------------------------------------------------- couleurs


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def hexs(c):
    return "#%02X%02X%02X" % tuple(max(0, min(255, int(round(v)))) for v in c[:3])


def tone(color, hue, sat, val, ref_hue=22):
    """Ramène une couleur de la palette de feu vers la teinte voulue (écarts de teinte gardés à moitié)."""
    r, g, b = [v / 255 for v in rgb(color)]
    h, s, v = colorsys.rgb_to_hsv(r, g, b)
    dh = ((h * 360 - ref_hue + 180) % 360) - 180
    nh = ((hue + dh * 0.5) % 360) / 360
    r, g, b = colorsys.hsv_to_rgb(nh, min(1, s * sat), min(1, v * val))
    return hexs((r * 255, g * 255, b * 255))


FIRE = {"base": "#FF8A1E", "dark": "#B02C14", "mid": "#E0461C", "light": "#FFD23F", "hi": "#FFF6BE",
        "cheek": "#FF6E60", "mouth": "#6E1A0A"}


def palette(main, accent, glow=None, eye="#280C08"):
    """main / accent : (teinte, saturation, luminosité)."""
    p = {k: tone(v, *main) for k, v in FIRE.items()}
    p["eye"] = eye
    a = [tone(c, *accent) for c in (FIRE["light"], FIRE["base"], FIRE["mid"])]
    p["accA"] = a
    p["accB"] = [tone(c, *accent) for c in (FIRE["hi"], FIRE["light"], FIRE["base"])]
    p["accC"] = [tone(c, accent[0], accent[1] * 0.4, 1.2) for c in (FIRE["hi"], FIRE["light"], FIRE["base"])]
    p["glow"] = tone(FIRE["hi"], *(glow or accent))
    return p


def el(e, dh=0, ds=1.0, dv=1.0):
    _, h, s, v, _ = ELEMENTS[e]
    return ((h + dh) % 360, s * ds, v * dv)


# --------------------------------------------------------------------------- familiers


def fmt(x):
    return ("%.4f" % x).rstrip("0").rstrip(".")


# Couleur de chaque série (nom des boosters de carrière, corps du booster : teinte, saturation, luminosité)
JOB_COLORS = {"mineur": ("#4FD6E8", (188, 0.8, 0.85)), "rochiste": ("#A8AEB8", (215, 0.12, 0.8)),
              "deblayeur": ("#D9C27A", (45, 0.5, 0.85)), "cultivateur": ("#E8C547", (48, 0.85, 0.9)),
              "herboriste": ("#6CD46C", (115, 0.75, 0.8)), "sylviculteur": ("#B07A45", (28, 0.65, 0.65)),
              "fondeur": ("#FF8A3D", (18, 1.0, 1.0)), "chimiste": ("#E86CC8", (315, 0.6, 0.85)),
              "enchanteur": ("#8C7BFF", (250, 0.6, 0.85)), "pecheur": ("#4FA8FF", (210, 0.9, 0.9)),
              "defenseur": ("#E04848", (0, 0.85, 0.8)), "batisseur": ("#F2A33A", (35, 0.9, 0.95)),
              "*": ("#F0A800", (45, 1.0, 0.9))}

JOBS = {"mineur": "Mineur", "rochiste": "Rochiste", "deblayeur": "Déblayeur", "cultivateur": "Cultivateur",
        "herboriste": "Herboriste", "sylviculteur": "Bûcheron", "fondeur": "Fondeur", "chimiste": "Chimiste",
        "enchanteur": "Enchanteur", "pecheur": "Pêcheur", "defenseur": "Défenseur", "batisseur": "Bâtisseur"}

# 4 familiers par carrière : commun (simple), unique, épique (elite), mythique (ultimate).
# Bonus d'XP de carrière par niveau (en %), 25 niveaux : +8 / +12 / +16 / +20 % au maximum ;
# prestige (légendaire / fabuleux) : +6 / +10 % sur toutes les carrières.
JOB_BONUS = {"simple": 0.32, "unique": 0.48, "elite": 0.64, "ultimate": 0.8, "legendary": 0.24, "fabled": 0.4}


def unlock(job):
    """Chance d'adoption par booster de sa série : carrière (UNLOCK_TARGET) ou Prestige (PRESTIGE_UNLOCK)."""
    return PRESTIGE_UNLOCK if job == "*" else UNLOCK_TARGET


# Vue de l'illustration de la carte : de face par défaut, de profil (« west ») pour un familier tout en longueur
CARD_VIEWS = {}  # id -> "west" (profil) ou (yaw, pitch) : vue de trois quarts, rendu de apercu.py

# Traînée de particules des familiers légendaires (en rapport avec chacun)
LEGENDARY_TRAILS = {"phenix": "flame", "astrolabe": "end_rod", "licorne": "firework", "kitsune": "snowflake"}

S, U, E, MY = "simple", "unique", "elite", "ultimate"
# id (= fonction de familiers_design.py), nom, élément (couleur du nom et du fond de carte), palier, carrière, lore
PETS = [
    ("pepite", "Pépite", "terre", S, "mineur", ["Un éclat de minerai d'or", "qui a décidé de marcher."]),
    ("lanterne", "Lanterne", "lumiere", U, "mineur", ["Elle éclaire les galeries", "et ne s'éteint jamais."]),
    ("wagonnet", "Wagonnet", "terre", E, "mineur", ["Il roule sur les rails", "la benne pleine de trésors."]),
    ("dragon_gemmes", "Dragon de Gemmes", "glace", MY, "mineur", ["Ses écailles sont des diamants,", "son trésor, toute la montagne."]),
    ("caillou", "Caillou", "terre", S, "rochiste", ["Il ne bouge pas beaucoup,", "mais il connaît chaque pierre."]),
    ("mimique", "Mimique", "ombre", U, "rochiste", ["Un coffre au fond de la galerie ?", "Méfie-toi, il a des dents."]),
    ("colosse", "Colosse de Basalte", "feu", E, "rochiste", ["Né d'une coulée de lave refroidie,", "lent mais inébranlable."]),
    ("titan", "Titan de Cristal", "terre", MY, "rochiste", ["Chaque cristal sur son dos", "raconte un millénaire."]),
    ("fourmi", "Fourmi", "terre", S, "deblayeur", ["Elle creuse des galeries", "qui ne s'effondrent jamais."]),
    ("cabri", "Cabri", "terre", U, "deblayeur", ["Il saute de rocher en rocher", "sans jamais trébucher."]),
    ("lievre_neiges", "Lièvre des Neiges", "glace", E, "deblayeur", ["Il court sur la poudreuse", "sans y laisser une empreinte."]),
    ("ver_sables", "Ver des Sables", "terre", MY, "deblayeur", ["Il nage sous les dunes", "comme un poisson sous l'eau."]),
    ("abeille", "Abeille", "foudre", S, "cultivateur", ["Elle butine le potager,", "et tout pousse sur son passage."]),
    ("carottin", "Carottin", "feu", U, "cultivateur", ["Arraché du potager,", "il y retourne chaque soir."]),
    ("ouistiti", "Ouistiti", "nature", E, "cultivateur", ["Chaque cabosse qu'il chipe,", "il la replante en cachette."]),
    ("epouvantail", "Épouvantail", "nature", MY, "cultivateur", ["Les corbeaux le fuient,", "sauf un, son meilleur ami."]),
    ("coquelicot", "Coquelicot", "feu", S, "herboriste", ["La fleur la plus bavarde", "de tout le pré."]),
    ("champinou", "Champinou", "nature", U, "herboriste", ["Il pousse là où personne", "ne pense à regarder."]),
    ("papillon", "Papillon", "lumiere", E, "herboriste", ["Il ne se pose jamais", "deux fois sur la même fleur."]),
    ("dryade", "Dryade", "nature", MY, "herboriste", ["Esprit de la clairière,", "les fleurs s'ouvrent à son passage."]),
    ("souche", "Souche", "terre", S, "sylviculteur", ["Coupée il y a cent ans,", "elle a gardé la hache en souvenir."]),
    ("castor", "Castor", "terre", U, "sylviculteur", ["Un arbre ? Trois coups de dents,", "et c'est réglé."]),
    ("gland", "Gland", "nature", E, "sylviculteur", ["Un jour il sera chêne.", "Pour l'instant, il roule."]),
    ("sylvain", "Sylvain", "nature", MY, "sylviculteur", ["Gardien des vieilles forêts,", "patient comme un chêne."]),
    ("flammeche", "Flammèche", "feu", MY, "fondeur", ["Un petit esprit de flamme", "qui ne quitte jamais le four."]),
    ("soufflet", "Soufflet", "feu", S, "fondeur", ["Un souffle, et les braises", "repartent de plus belle."]),
    ("salamandre", "Salamandre Ardente", "feu", U, "fondeur", ["Elle dort dans les braises", "du haut-fourneau."]),
    ("fournaise", "Fournaise", "feu", E, "fondeur", ["Un four qui a pris vie,", "et qui a toujours faim."]),
    ("fiolin", "Fiolin", "eau", S, "chimiste", ["Une fiole qui a pris vie", "au fond d'un alambic."]),
    ("chaudronnet", "Chaudronnet", "nature", U, "chimiste", ["Il bouillonne de joie", "et de potion verte."]),
    ("slime_alchimique", "Slime Alchimique", "nature", E, "chimiste", ["Une potion ratée", "qui a décidé de vivre sa vie."]),
    ("grand_alchimiste", "Grand Alchimiste", "eau", MY, "chimiste", ["Il connaît toutes les recettes,", "même les interdites."]),
    ("lapin_lapis", "Lapis-Lapin", "eau", S, "enchanteur", ["Il a creusé si profond", "qu'il en est ressorti tout bleu."]),
    ("orbe_xp", "Orbe d'Expérience", "nature", U, "enchanteur", ["Il a la couleur de tes niveaux", "et file toujours droit vers toi."]),
    ("chouette_arcane", "Chouette Arcane", "ombre", E, "enchanteur", ["Elle lit dans le noir", "les grimoires interdits."]),
    ("table_vivante", "Table Vivante", "ombre", MY, "enchanteur", ["La table d'enchantement", "qui choisit elle-même ses sorts."]),
    ("poissonnet", "Poissonnet", "eau", S, "pecheur", ["Il sait où mordent", "ses cousins."]),
    ("crabichon", "Crabichon", "feu", U, "pecheur", ["Il marche de travers", "mais pêche tout droit."]),
    ("dauphin", "Dauphin", "eau", E, "pecheur", ["Il nage plus vite que son ombre", "et t'emmène dans son sillage."]),
    ("kraken", "Kraken", "ombre", MY, "pecheur", ["Capitaine des abysses,", "les poissons le suivent."]),
    ("ecuyer", "Écuyer", "eau", S, "defenseur", ["Petit, mais sa lance", "tient les zombies à distance."]),
    ("pavois", "Pavois", "terre", U, "defenseur", ["Un grand bouclier vivant", "qui se dresse devant ses amis."]),
    ("chien_garde", "Chien de Garde", "terre", E, "defenseur", ["Son armure brille,", "son flair ne trompe jamais."]),
    ("chevalier_spectral", "Chevalier Spectral", "glace", MY, "defenseur", ["Il a juré de protéger", "même après sa mort."]),
    ("marteau_piqueur", "Marteau-Piqueur", "foudre", S, "batisseur", ["Il fait trembler les routes", "et déterre ce qu'elles cachaient."]),
    ("betonniere", "Bétonnière", "terre", U, "batisseur", ["Elle tourne, tourne, tourne,", "et le béton prend aussitôt."]),
    ("grue", "Grue", "foudre", E, "batisseur", ["Elle soulève les blocs", "les plus lourds du chantier."]),
    ("architecte", "Architecte", "eau", MY, "batisseur", ["Il voit déjà la cathédrale", "là où tu vois un terrain vague."]),
    ("phenix", "Phénix", "feu", "legendary", "*", ["Il renaît toujours", "de ses cendres."]),
    ("astrolabe", "Astrolabe Céleste", "foudre", "legendary", "*", ["Il fait tourner les planètes", "pour ne jamais s'ennuyer."]),
    ("kitsune", "Kitsune des Neiges", "glace", "legendary", "*", ["Une queue par siècle vécu.", "Elle en a neuf."]),
    ("licorne", "Licorne Astrale", "lumiere", "legendary", "*", ["Elle laisse des étoiles", "là où elle passe."]),
    ("nida", "Nida", "ombre", "legendary", "*", ["Il a perdu son bras gauche", "en retenant la porte du Néant."]),
    ("leviathan", "Léviathan", "eau", "fabled", "*", ["Les marins ne prononcent", "jamais son nom."]),
    ("faucheur", "Faucheur du Néant", "ombre", "fabled", "*", ["Il vient du vide", "et y retourne toujours."]),
]
# carrières désactivées (Chimiste, 2026-10-06) : ni familiers, ni cartes, ni booster
# familiers mis de côté (2026-10-10 : Nida et les 2 fabuleux) : même traitement
PETS = [p for p in PETS if p[4] not in METIERS_DESACTIVES and p[0] not in FAMILIERS_DESACTIVES]
JOBS = {k: v for k, v in JOBS.items() if k not in METIERS_DESACTIVES}

# Couleur de texte du nom d'un familier = couleur de son élément
# --------------------------------------------------------------------------- utilitaires sortie


def ensure(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    return path


class NoAlias(yaml.SafeDumper):
    """Pas d'ancres YAML (&id001) : les listes répétées sont écrites en entier."""

    def ignore_aliases(self, data):
        return True


def dump_yaml(path, data, header=None):
    with open(ensure(path), "w", encoding="utf-8") as f:
        if header:
            for line in header.strip().split("\n"):
                f.write(("# " + line).rstrip() + "\n")
            f.write("\n")
        yaml.dump(data, f, Dumper=NoAlias, allow_unicode=True, sort_keys=False, width=1000)


# --------------------------------------------------------------------------- modèles

from familiers_design import DESIGNS  # noqa: E402
from atelier import Atelier  # noqa: E402

models = {}  # id -> (elements, texture_px)
for pid, name, elem, rarity, job, lore in PETS:
    ident = f"familier_{pid}"
    path = ensure(os.path.join(OUT, "BetterModel", "models", ident + ".bbmodel"))
    if pid == "flammeche":
        # modèle d'origine (esprit de feu) : renommé, inchangé
        with open(FIRE_MODEL, encoding="utf-8") as f:
            data = json.load(f)
        data["name"] = ident
        data["model_identifier"] = ident
        src = data["textures"][0]["source"].split(",", 1)[1]
        tmp = os.path.join(OUT, ".tmp_fire.png")
        with open(ensure(tmp), "wb") as f:
            f.write(base64.b64decode(src))
        _, _, tex = read(tmp)
        os.remove(tmp)
        M.save_bbmodel(path, data)
        models[pid] = (data["elements"], tex)
        continue
    a = Atelier(ident)
    DESIGNS[pid](a)
    # familier au sol (familiers_sol.AU_SOL) : posé au ras du sol, animations sans flottement
    data = poser_au_sol(a.export(), pid)
    M.save_bbmodel(path, data)
    models[pid] = (data["elements"], a.px)

# --------------------------------------------------------------------------- textures des cartes

_, _, card_tpl = read(CARD_TEMPLATE)
FIRE_FRAME = {"2a1210": 0, "ffe28c": 1, "b0681e": 2, "f2b33d": 3}  # contour, clair, moyen, accent

ELEMENT_ICONS = {  # motif 3x3 (1 = couleur claire, 2 = couleur de l'élément)
    "feu": ["020", "121", "222"], "eau": ["020", "212", "222"], "nature": ["022", "212", "200"],
    "foudre": ["022", "120", "200"], "glace": ["202", "020", "202"], "ombre": ["220", "202", "220"],
    "lumiere": ["202", "010", "202"], "terre": ["000", "212", "222"],
}


def card_texture(pid, elem, rarity):
    elements, tex = models[pid]
    frame = [rgb(c) + (255,) for c in FRAMES[rarity]]
    _, h, s, v, txt = ELEMENTS[elem]
    bg = rgb(tone("#300E1E", h, min(1, s * 1.1), 0.9 * max(0.8, v)))
    bg2 = rgb(tone("#4E1624", h, min(1, s * 1.1), 0.95 * max(0.8, v)))
    bar = rgb(tone("#220E0E", h, s, 0.9))
    out = [[(0, 0, 0, 0)] * 32 for _ in range(32)]
    for y in range(32):
        for x in range(32):
            p = card_tpl[y][x]
            key = p.hex()[:6]
            if p[3] == 0:
                continue
            if 25 <= y <= 28 and 7 <= x <= 24:
                out[y][x] = bar + (255,)
            elif key in FIRE_FRAME:
                c = frame[FIRE_FRAME[key]]
                if rarity == "fabled" and FIRE_FRAME[key] == 1 and (x + y) % 3 == 0:
                    c = (150, 240, 255, 255)
                out[y][x] = c
            elif 3 <= y <= 22 and 7 <= x <= 24:
                out[y][x] = bg + (255,)
            elif 25 <= y <= 28 and 7 <= x <= 24:
                out[y][x] = bar + (255,)
            else:
                out[y][x] = p
    # étoiles de fond
    for (x, y) in ((8, 6), (22, 4), (9, 18), (23, 14), (16, 3)):
        out[y][x] = bg2 + (255,)
    # illustration : rendu de face du modèle, centré dans la fenêtre 18x20
    view = CARD_VIEWS.get(pid, "north")
    if isinstance(view, tuple):
        # trois quarts : rendu ombré d'apercu.py en 20x20, recadré à 18 de large
        import apercu
        sq = apercu.render(elements, tex, view[0], view[1], size=20, bg=None, margin=1)
        art = [row[1:19] for row in sq]
    else:
        art = M.render_front(elements, tex, 18, 20, view)
    for y in range(20):
        for x in range(18):
            if art[y][x] is not None:
                out[3 + y][7 + x] = tuple(art[y][x])
    # icône d'élément (bandeau, à gauche)
    ec = rgb(txt) + (255,)
    light = (255, 246, 220, 255)
    for j, row in enumerate(ELEMENT_ICONS[elem]):
        for i, ch in enumerate(row):
            if ch != "0":
                out[25 + j][8 + i] = light if ch == "1" else ec
    # pastilles de rareté (1 à 6)
    level = RARITIES.index(rarity) + 1
    for i in range(6):
        x = 13 + i * 2
        c = frame[3] if i < level else (frame[0][0] + 30, frame[0][1] + 30, frame[0][2] + 30, 255)
        out[26][x] = c
        out[27][x] = c
    return out


def _in_poly(x, y, pts):
    inside = False
    for (x1, y1), (x2, y2) in zip(pts, pts[1:] + pts[:1]):
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            inside = not inside
    return inside


def card_back():
    """Dos commun des cartes, inspiré du logo du serveur : fond violet sombre, liseré néon magenta,
    cube violet en perspective coiffé d'une couronne dorée, étincelles. Même silhouette que la face."""
    C = {k: rgb(v) + (255,) for k, v in {
        "outline": "#0D0D0D", "neon": "#C8256A", "neon_light": "#E060A0", "bg_top": "#1E0A3C",
        "bg_bottom": "#2C0B3E", "cube_top": "#8A4FD8", "cube_left": "#5B2D9E", "cube_right": "#3E1C7A",
        "cube_edge": "#E060A0", "gold": "#F0A800", "gold_light": "#FFD94D", "gold_dark": "#B87400",
        "gem": "#5B2D9E", "spark": "#FFD94D", "spark_pink": "#E060A0"}.items()}
    out = [[(0, 0, 0, 0)] * 32 for _ in range(32)]
    for y in range(32):
        for x in range(32):
            p = card_tpl[y][x]
            if p[3] == 0:
                continue
            if FIRE_FRAME.get(p.hex()[:6]) == 0 and (x in (4, 27) or y in (0, 31)):
                c = C["outline"]
            elif x in (4, 5, 26, 27) or y in (0, 1, 30, 31):
                c = C["neon"]
            elif x in (6, 25) or y in (2, 29):
                c = C["neon_light"]
            else:
                c = C["bg_top"] if y < 18 else C["bg_bottom"]
            out[y][x] = c
    # panneau hexagonal derrière le cube (rappel du logo)
    hexa = [(16, 5.5), (24.5, 10), (24.5, 25), (16, 29.5), (7.5, 25), (7.5, 10)]
    for y in range(3, 29):
        for x in range(7, 25):
            if _in_poly(x + 0.5, y + 0.5, hexa):
                out[y][x] = rgb("#2E1060") + (255,)
    # couronne et cube dessinés à la main (x 10..21)
    crown = ["Y....YY....Y",
             "YY..YGGY..YY",
             "GGY.GGGG.YGG",
             "GGGGGPPGGGGG",
             "GGGGGGGGGGGG",
             "DDDDDDDDDDDD"]
    cube = [".....EE.....",
            "...EETTEE...",
            ".EETTTTTTEE.",
            "ETTTTTTTTTTE",
            "LEETTTTTTEER",
            "LLLEETTEERRR",
            "LLLLLEERRRRR",
            "LLLLLlRRRRRR",
            "LLLLLlRRRRRR",
            "LLLLLlRRRRRR",
            "LLLLLlRRRRRR",
            "LLLLLlRRRRRR",
            "..LLLlRRRR..",
            "....LlRR....",
            ]
    pal = {"Y": C["gold_light"], "G": C["gold"], "D": C["gold_dark"], "P": C["gem"],
           "E": C["cube_edge"], "T": C["cube_top"], "L": C["cube_left"], "R": C["cube_right"],
           "l": rgb("#7A44C8") + (255,)}
    for r, row in enumerate(cube):
        for c, ch in enumerate(row):
            if ch != ".":
                out[11 + r][10 + c] = pal[ch]
    for r, row in enumerate(crown):
        for c, ch in enumerate(row):
            if ch != ".":
                out[5 + r][10 + c] = pal[ch]
    # étincelles (petites étoiles à 4 branches)
    for (x, y, col) in ((8, 6, "spark"), (23, 6, "spark"), (8, 16, "spark_pink"), (23, 16, "spark_pink"),
                        (8, 26, "spark"), (23, 26, "spark")):
        for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            out[y + dy][x + dx] = C[col]
    return out


def card_model(pid):
    """Modèle de carte fine : face avant au sud, dos au nord (visible quand la carte tourne)."""
    front, back = f"fluxpet:item/carte_{pid}", "fluxpet:item/carte_dos"
    edge = {"uv": [2, 0, 2.5, 16], "texture": "#front"}
    return {
        "textures": {"front": front, "back": back, "particle": front},
        "elements": [{
            "from": [2, 0, 7.75], "to": [14, 16, 8.25],
            "faces": {"south": {"uv": [2, 0, 14, 16], "texture": "#front"},
                      "north": {"uv": [14, 0, 2, 16], "texture": "#back"},
                      "east": edge, "west": edge,
                      "up": {"uv": [2, 0, 14, 0.5], "texture": "#front"},
                      "down": {"uv": [2, 15.5, 14, 16], "texture": "#front"}},
        }],
        "display": {
            "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.55, 0.55, 0.55]},
            "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.55, 0.55, 0.55]},
            "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
            "firstperson_lefthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
            "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
            "fixed": {"rotation": [0, 180, 0], "scale": [1, 1, 1]},
            "gui": {"rotation": [0, 0, 0], "scale": [1, 1, 1]},
        },
    }


# --------------------------------------------------------------------------- peluches (meubles statiques)

def plush_model(pid):
    """Modèle d'objet vanilla tiré du modèle 3D du familier, à sa taille réelle : pieds en y = 0,
    centré en x/z. Si la géométrie sort de la limite -16..32 d'un modèle, elle est réduite de moitié
    et le meuble l'agrandit x2 (renvoyé avec le modèle). Rotations libres multi-axes (depuis 1.21.11)."""
    elements, tex = models[pid]
    tw, th = len(tex[0]), len(tex)
    els = [e for e in elements if all(f.get("texture") is not None for f in e["faces"].values())]
    lo = [min(min(e["from"][i], e["to"][i]) for e in els) for i in range(3)]
    hi = [max(max(e["from"][i], e["to"][i]) for e in els) for i in range(3)]
    mid = [(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2]
    off = [8, 0, 8]
    size = max(hi[i] - lo[i] for i in range(3))
    fits = all(-16 <= (lo[i] - mid[i]) + off[i] and (hi[i] - mid[i]) + off[i] <= 32 for i in range(3))
    k = 1 if fits else 0.5
    view = 12 / size / k  # objet en main / inventaire : ramené à la taille d'un objet ordinaire

    def tp(v):
        return [round((v[i] - mid[i]) * k + off[i], 4) for i in range(3)]

    out = []
    for e in els:
        inf = e.get("inflate", 0)
        o = {"from": tp([e["from"][i] - inf for i in range(3)]), "to": tp([e["to"][i] + inf for i in range(3)])}
        rot = e.get("rotation", [0, 0, 0])
        if any(rot):
            o["rotation"] = {"origin": tp(e["origin"]), "x": rot[0], "y": rot[1], "z": rot[2]}
        faces = {}
        for fn, f in e["faces"].items():
            u = f["uv"]
            fo = {"uv": [round(u[0] * 16 / tw, 4), round(u[1] * 16 / th, 4), round(u[2] * 16 / tw, 4),
                         round(u[3] * 16 / th, 4)], "texture": "#0"}
            if f.get("rotation"):
                fo["rotation"] = f["rotation"]
            faces[fn] = fo
        o["faces"] = faces
        out.append(o)
    tex_id = f"fluxpet:item/peluche/{pid}"
    r = lambda v: round(v, 3)  # noqa: E731
    return {
        "textures": {"0": tex_id, "particle": tex_id},
        "elements": out,
        "display": {
            "gui": {"rotation": [30, 225, 0], "translation": [0, 1.5, 0], "scale": [r(0.85 * view)] * 3},
            "ground": {"translation": [0, 2, 0], "scale": [r(0.4 * view)] * 3},
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [r(0.4 * view)] * 3},
            "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [r(0.4 * view)] * 3},
            "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 2, 0], "scale": [r(0.4 * view)] * 3},
            "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 2, 0], "scale": [r(0.4 * view)] * 3},
            "head": {"translation": [0, 14.5, 0], "scale": [r(0.8 * view)] * 3},
        },
    }, 1 / k


# --------------------------------------------------------------------------- textures des boosters

_, _, booster_tpl = read(BOOSTER_TEMPLATE)
B_BODY = {"c44034", "961e1c", "e66e50", "78161e", "581020"}
B_EMBLEM = {"ff8a1e", "e0461c", "ffd23f", "ff6e60", "6e1a0a", "280c08"}
B_FOIL = {"c8c8d2", "646473", "9696a5"}


def booster_texture(body_left, body_right=None, emblem=None, foil="silver", portrait=None, tier=0):
    """body_* / emblem : (teinte, saturation, luminosité). foil : silver, gold, prism."""
    out = [[(0, 0, 0, 0)] * 32 for _ in range(32)]
    for y in range(32):
        for x in range(32):
            p = booster_tpl[y][x]
            if p[3] == 0:
                continue
            key = p.hex()[:6]
            c = "#" + key
            if key in B_BODY:
                body = body_left if (body_right is None or x < 16) else body_right
                c = tone(c, *body, ref_hue=4)
            elif key in B_EMBLEM and emblem:
                c = tone(c, *emblem)
            elif key in B_FOIL:
                if foil == "gold":
                    c = tone(c, 45, 4.0, 1.05, ref_hue=240)
                elif foil == "prism":
                    c = tone(c, (x * 22 + y * 8) % 360, 5.0, 1.1, ref_hue=240)
            out[y][x] = rgb(c) + (255,)
    if tier:
        _tier_marks(out, tier)
    if portrait:
        # portrait du familier à la place de la flamme, dans le médaillon
        elements, tex = models[portrait]
        inner = rgb(tone("#581020", *body_left, ref_hue=4)) + (255,)
        for y in range(14, 24):
            for x in range(11, 21):
                if booster_tpl[y][x].hex()[:6] in B_EMBLEM | {"581020", "ffffff", "fff6be"}:
                    out[y][x] = inner
        art = M.render_front(elements, tex, 8, 9)
        for y in range(9):
            for x in range(8):
                if art[y][x] is not None:
                    out[14 + y][12 + x] = tuple(art[y][x])
    return out


TIER_CARD = {1: [(200, 205, 215)], 2: [(70, 200, 90), (85, 255, 85)],
             3: [(60, 100, 200), (90, 133, 234), (140, 175, 255)],
             4: [(200, 130, 0), (240, 168, 0), (255, 210, 90)]}
BAND = {"b0681e", "f2b33d", "ffe28c", "ff8a1e", "e0461c"}


def _tier_marks(out, tier):
    """Gamme : bandeau (argent / or / clair) avec 1 à 3 étoiles, éventail de 1 à 3 cartes au centre."""
    band_tone = {1: (215, 0.05, 1.08), 2: (115, 0.55, 1.05), 3: (220, 0.55, 1.08), 4: None}[tier]
    for y in range(6, 11):
        for x in range(6, 26):
            key = booster_tpl[y][x].hex()[:6]
            if key in BAND:
                base = "#" + key
                if key in ("ff8a1e", "e0461c"):  # anciennes marques : effacées
                    base = "#ffe28c" if y == 8 else "#f2b33d"
                out[y][x] = rgb(tone(base, *band_tone, ref_hue=40) if band_tone else base) + (255,)
    star = (255, 255, 255, 255)
    for i in range(tier):
        cx = 16 + (i - (tier - 1) / 2) * 4
        cx = int(round(cx - 0.5))
        for dx, dy in ((0, 0), (-1, 0), (1, 0), (0, -1), (0, 1)):
            out[8 + dy][cx + dx] = star
    # médaillon : on retire la flamme, puis un éventail de cartes
    inner = out[16][12]
    for y in range(14, 24):
        for x in range(11, 21):
            if booster_tpl[y][x].hex()[:6] in B_EMBLEM | {"581020", "ffffff", "fff6be"}:
                out[y][x] = (40, 34, 52, 255)
    spots = {1: [(13, 15)], 2: [(12, 16), (15, 15)], 3: [(11, 16), (13, 15), (16, 16)],
             4: [(11, 16), (13, 15), (16, 16)]}[tier]
    for (x0, y0), col in zip(spots, TIER_CARD[tier]):
        for y in range(y0, y0 + 7):
            for x in range(x0, x0 + 5):
                edge = x in (x0, x0 + 4) or y in (y0, y0 + 6)
                out[y][x] = (255, 244, 224, 255) if edge else col + (255,)
        out[y0 + 3][x0 + 2] = (255, 255, 255, 255)


# --------------------------------------------------------------------------- écriture

ia_items = {}
ia_dir = os.path.join(OUT, "ItemsAdder", "contents", "fluxpet")
cards, pets_cfg = {}, {}
boosters = {}


def ia_item(item_id, texture_px):
    write(ensure(os.path.join(ia_dir, "textures", "item", item_id + ".png")), 32, 32, texture_px)
    ia_items[item_id] = {"display_name": " ", "resource": {"material": "PAPER", "generate": True,
                                                           "textures": [f"item/{item_id}.png"]}}


write(ensure(os.path.join(ia_dir, "textures", "item", "carte_dos.png")), 32, 32, card_back())
# verso des cartes en glyphe : petit pour le tchat (%img_carte_verso% ou :carte_verso:), grand pour le titre
# affiché juste avant chaque carte révélée (%img_carte_verso_grand%)
write(ensure(os.path.join(ia_dir, "textures", "font", "carte_dos.png")), 32, 32, card_back())
font_images = {
    "carte_verso": {"show_in_gui": True, "suggest_in_command": True, "path": "font/carte_dos.png",
                    "scale_ratio": 12, "y_position": 10},
    "carte_verso_grand": {"show_in_gui": False, "suggest_in_command": False, "path": "font/carte_dos.png",
                          "scale_ratio": 31, "y_position": 18},
}

# Particules du totem rendues transparentes : l'animation du totem (révélation des cartes) les fait
# apparaître côté client sans moyen de les couper depuis le serveur. Touche aussi les vrais totems.
for _i in range(8):
    write(ensure(os.path.join(OUT, "ItemsAdder", "contents", "influparticules", "resourcepack", "assets", "minecraft",
                              "textures", "particle", f"glitter_{_i}.png")),
          8, 8, [[(0, 0, 0, 0)] * 8 for _ in range(8)])


for pid, name, elem, rarity, job, lore in PETS:
    ename, eh, es, ev, _ = ELEMENTS[elem]
    txt = RARITY_COLORS[rarity]  # nom du familier (carte, peluche, EcoPets) à la couleur de sa rareté
    f = POWER[rarity]
    job_label = "toutes les carrières" if job == "*" else JOBS[job]
    # ---- carte : face (texture) + modèle fin avec dos commun
    ia_item(f"carte_{pid}", card_texture(pid, elem, rarity))
    model_file = ensure(os.path.join(ia_dir, "models", "item", f"carte_{pid}.json"))
    with open(model_file, "w", encoding="utf-8") as mf:
        json.dump(card_model(pid), mf, indent=2)
    ia_items[f"carte_{pid}"]["resource"] = {"material": "PAPER", "generate": False,
                                             "model_path": f"item/carte_{pid}"}
    # ---- peluche : meuble statique, donnée à chaque fois que le familier sort d'un booster
    write(ensure(os.path.join(ia_dir, "textures", "item", "peluche", pid + ".png")), len(models[pid][1][0]),
          len(models[pid][1]), [[tuple(c) for c in row] for row in models[pid][1]])
    with open(ensure(os.path.join(ia_dir, "models", "item", f"peluche_{pid}.json")), "w", encoding="utf-8") as mf:
        plush, plush_scale = plush_model(pid)
        json.dump(plush, mf, separators=(",", ":"))
    ia_items[f"peluche_{pid}"] = {
        # ItemsAdder : couleurs hexadécimales en balises <#RRGGBB>, pas en &#RRGGBB
        "display_name": f"<{txt}>Peluche {name}",
        "lore": [f"<#EADBC8>Une peluche à l'effigie de <{txt}>{name}<#EADBC8>.", "",
                 "<#6B7A8F>Obtenue en complétant sa série", "<#6B7A8F>de familiers. Se pose comme un meuble."],
        "resource": {"material": "PAPER", "generate": False, "model_path": f"item/peluche_{pid}"},
        "behaviours": {"furniture": {
            "entity": "item_display", "opposite_direction": True,
            "display_transformation": {"transform": "FIXED", "translation": {"x": 0, "y": 0.5 * plush_scale, "z": 0},
                                       "scale": {"x": plush_scale, "y": plush_scale, "z": plush_scale}},
            "solid": False, "gravity": False, "fixed_rotation": False,
            "placeable_on": {"floor": True, "walls": False, "ceiling": False},
            "hitbox": {"width": 1, "length": 1,
                       "height": max(1, math.ceil(max(v for e in plush["elements"] for v in (e["from"][1], e["to"][1]))
                                                  * plush_scale / 16))},
            "sound": {"place": {"name": "block.wool.place"}, "break": {"name": "block.wool.break"}},
        }},
    }
    cards[pid] = {"item": f"fluxpet:carte_{pid}", "name": f"&{txt}{name}",
                  "series": "Prestige" if job == "*" else JOBS[job],
                  "rarity": rarity, "pet": pid,
                  "lore": [f"&#B376E5{lore[0]}", f"&#B376E5{lore[1]}", "",
                           ("&#2ECF7BFamilier de prestige" if job == "*" else f"&#2ECF7BSérie &f{job_label}")]}
    pets_cfg[pid] = {"rarity": rarity, "enabled": True}

    # ---- familier (InfluPet, ex-EcoPets)
    per_level = JOB_BONUS[rarity] * 25 / MAX_LEVEL  # JOB_BONUS : % par niveau calibré pour 25 niveaux
    xp_effect = {"id": "job_xp_multiplier", "args": {"multiplier": f"1 + %level% * {fmt(per_level / 100)}"}}
    if job != "*":
        xp_effect["args"]["jobs"] = [job]
    all_effects = [xp_effect]
    placeholders = [{"id": "bonus", "value": f"%level% * {fmt(per_level)}"}]
    desc = [f"&#B376E5XP de carrière &f+%bonus%%" + ("" if job == "*" else f" &#B376E5({job_label})")]
    # familiers de carrière : effets du tableau validé (effets_familiers.py), XP seule pour les mythiques
    if pid in EFFETS:
        desc, placeholders, all_effects = EFFETS[pid]
    # chance par tirage de la carte de ce familier dans son booster (carrière ou Prestige)
    weights = PRESTIGE_WEIGHTS if job == "*" else SERIES_WEIGHTS
    # la série compte aussi le légendaire de boss de la carrière (familiers_boss.py)
    series = [x[3] for x in PETS if x[4] == job] + (["legendary"] if job in BOSS_BY_JOB else [])
    reqs = level_curve(cards_to_max(series, rarity, weights, draws(job), unlock(job)))
    # aucune source d'XP passive : seules les cartes (clic droit, InfluCard) font grandir un familier
    xp_gain = []
    pet_yml = {
        "name": f"&{txt}{name}",
        # une seule ligne dans EcoPets : la suite « (clic droit sur une de ses cartes) » est dans les
        # modèles d'infobulle de InfluPet/familiers-reglages.yml, sous %description%
        "description": "&#B376E5Grandit uniquement avec ses cartes",
        "entity-texture": f"bettermodel:familier_{pid}",
        "icon": f"itemsadder:influcards__carte_{pid}",
        "flying": pid not in AU_SOL,  # InfluPet : flotte, ou marche au sol (familiers_sol.py)
        "trail": ({"enabled": True, "particle": LEGENDARY_TRAILS[pid], "gap": 2, "amount": 2, "y-offset": 0.3}
                  if pid in LEGENDARY_TRAILS else
                  {"enabled": False, "particle": "flame", "gap": 2, "amount": 1, "y-offset": 0.0}),
        "level-xp-requirements": reqs,
        "xp-gain-methods": xp_gain,
        "level-placeholders": placeholders,
        "effects-description": {1: desc},
        "rewards-description": {1: desc},
        "level-up-messages": {1: desc},
        "level-up-effects": [],
        "effects": all_effects,
        "conditions": [],
        "activate-conditions": [],
        "spawn-egg": {"enabled": False, "item": "paper", "name": f"&{txt}{name}", "lore": [],
                      "craftable": False, "recipe": [], "withdrawable": False},
    }
    dump_yaml(os.path.join(OUT, "InfluPet", "pets", pid + ".yml"), pet_yml,
              f"{name} ({ename}, rareté {rarity}, carrière : {job_label}) : familier InfluCard,\n"
              f"généré par scripts/influcard/gen_familiers.py.\n"
              f"Ne se débloque qu'en ouvrant son booster (booster_{pid} dans InfluCard).")


# Légendaires de boss (familiers_boss.py) : leurs cartes et leur place dans pets.list, le reste (modèle, texture,
# fichier du familier) par gen_familiers_boss.py
from familiers_boss import BOSSES, card_entry  # noqa: E402
for _b in BOSSES:
    if _b[3] in JOBS:
        cards[_b[0]] = card_entry(_b, JOBS[_b[3]])
        pets_cfg[_b[0]] = {"rarity": "legendary", "enabled": True}

# Boosters de série : un par carrière (cartes de la série + seul moyen d'adopter ses familiers),
# un Prestige (familiers légendaires et fabuleux). Chance d'adoption visée par booster de 5 tirages
# selon la rareté du familier (UNLOCK_PER_BOOSTER) ; elle ne grimpe pas quand un familier est déjà adopté.
for job in list(JOBS) + ["*"]:
    members = [p for p in PETS if p[4] == job]
    portrait = max(members, key=lambda p: RARITIES.index(p[3]))[0]
    if job in BOSS_BY_JOB:
        # légendaire de boss (familiers_boss.py) : modèle, carte et fichier par gen_familiers_boss.py
        b = BOSS_BY_JOB[job]
        members = members + [(b[0], b[1], None, "legendary", job, b[6])]
    color, body = JOB_COLORS[job]
    label = "Prestige" if job == "*" else JOBS[job]
    bid = "prestige" if job == "*" else f"metier_{job}"
    # Chance de déblocage par booster selon le palier (UNLOCK_TARGET). Par tirage : q = 1 - (1 - P)^(1/5),
    # et la part « familier » baisse quand l'un est exclu, donc chaque chance reste exacte.
    ranked = sorted(members, key=lambda p: RARITIES.index(p[3]))
    target = {p[0]: unlock(job)[p[3]] for p in members}
    per_draw = pet_draw_rate(target, draws(job))
    pet_weights = {r: 0 for r in RARITIES}
    for p in members:
        pet_weights[p[3]] += int(round(target[p[0]] * 1_000_000))
    ia_item(f"booster_{bid}", booster_texture(body, emblem=(body[0], min(1, body[1] * 1.2), 1.05),
                                              foil="gold" if job == "*" else "silver", portrait=portrait))
    # %adopted:<id>% : ✔ / ✘ selon que le joueur qui tient le booster a déjà adopté ce familier
    adoptables = [f"%adopted:{p[0]}% &{RARITY_COLORS[p[3]]}{p[1]}" for p in ranked]
    boosters[bid] = {
        "item": f"fluxpet:booster_{bid}",
        "name": f"&{color}&lBooster {label}",
        # description only: how to open it (right click, shift for the stack) and each pet's adoption chance are
        # added by FluxPets (Flux's style, chances from the booster's own settings), 2026-11-16
        "lore": [f"&#B376E5Contient &f%cards% &#B376E5cartes de la série &{color}{label}&#B376E5.",
                 "", "&#B376E5Familiers à adopter :"] + adoptables,
        "cards": draws(job),
        "rarity-weights": {r: w for r, w in zip(RARITIES, PRESTIGE_WEIGHTS if job == "*" else SERIES_WEIGHTS)},
        "contents": [f"pet:{p[0]}" for p in members] + [f"card:{p[0]}" for p in members],
        "pet-chance": round(per_draw * 100, 4),
        "pet-rarity-weights": pet_weights,
    }

# 4 paliers (comme les caisses) : 8, 12, 16 puis 20 cartes (2026-10-04, 5/5/6/7 avant)
TIERS = [("ordinaire", "&f&lBooster Ordinaire", 8, (215, 0.05, 0.92), "silver", None),
         ("tendance", "&#55FF55&lBooster Tendance", 12, (120, 0.75, 0.75), "silver", (110, 0.9, 1.1)),
         ("virale", "&#5A85EA&lBooster Viral", 16, (222, 0.75, 0.9), "silver", (215, 0.8, 1.15)),
         ("phenomene", "&#F0A800&lBooster Phénomène", 20, (40, 1.0, 0.85), "gold", (45, 1.0, 1.1))]
# Boosters de palier : peuvent faire adopter tous les familiers, un seul au maximum par ouverture.
# TIER_ANY_UNLOCK : chance par ouverture d'adopter un familier de chaque rareté, n'importe lequel,
# répartie entre les familiers de cette rareté. Viral 5x plus dur que Phénomène.
TIER_ANY_UNLOCK = {
    "ordinaire": {"simple": 0.00333, "unique": 0.00333, "elite": 0.00333, "ultimate": 0.00167, "legendary": 0.00083,
                  "fabled": 0.00033},
    "tendance": {"simple": 0.004, "unique": 0.004, "elite": 0.004, "ultimate": 0.002, "legendary": 0.001,
                 "fabled": 0.0004},
    # Phénomène : un familier dans 50 % des ouvertures ; légendaire 5 % et fabuleux 1 % fixés, le reste au
    # prorata de l'ancienne répartition (30 / 12 / 8 / 4)
    "phenomene": {"legendary": 0.05, "fabled": 0.01},
}
_rest = {"simple": 0.30, "unique": 0.12, "elite": 0.08, "ultimate": 0.04}
TIER_ANY_UNLOCK["phenomene"].update({r: v * (0.50 - 0.06) / sum(_rest.values()) for r, v in _rest.items()})
TIER_ANY_UNLOCK["virale"] = {r: v / 5 for r, v in TIER_ANY_UNLOCK["phenomene"].items()}
RARITY_COUNT = {r: sum(1 for p in PETS if p[3] == r) for r in RARITIES}

for level, (tid, tname, ncards, body, foil, emblem) in enumerate(TIERS, start=1):
    ia_item(f"booster_{tid}", booster_texture(body, emblem=emblem, foil=foil, tier=level))
    # une rareté sans familier (fabuleux, 2026-10-10) ne pèse rien
    per_pet = {r: TIER_ANY_UNLOCK[tid][r] / RARITY_COUNT[r] for r in RARITIES if RARITY_COUNT[r]}
    tier_target = {p[0]: per_pet[p[3]] for p in PETS}
    tier_rate = pet_draw_rate(tier_target, ncards)
    tier_pet_weights = {r: 0 for r in RARITIES}
    for p in PETS:
        tier_pet_weights[p[3]] += int(round(tier_target[p[0]] * 1_000_000))
    boosters[tid] = {
        "item": f"fluxpet:booster_{tid}",
        "name": tname,
        "lore": ["&#B376E5Palier : &f" + "★" * level + "&7" + "☆" * (4 - level),
                 "&#B376E5Contient &f%cards% &#B376E5cartes de toutes", "&#B376E5les séries.", "",
                 "&#B376E5Familiers à adopter :", "&fTous les familiers"],
        "cards": ncards,
        "rarity-weights": {r: w for r, w in zip(RARITIES, CARD_WEIGHTS[tid])},
        "contents": [f"familier:{x[0]}" for x in PETS] + [f"carte:{x[0]}" for x in PETS],
        "pet-chance": round(tier_rate * 100, 4),
        "pets-prefer-new": True,  # familier tiré parmi ceux que le joueur n'a pas encore (dans la rareté)
        "duplicate-plush": False,  # doublon : sa carte, jamais sa peluche (2026-10-06)
        "pet-rarity-weights": tier_pet_weights,
    }

# ItemsAdder : textures des cartes et boosters
# image de chaque booster en grand glyphe : affichée en titre à la place du verso pour la première
# carte d'un booster (on « ouvre » le booster), %img_booster_<id>_grand%
import shutil  # noqa: E402
for _bid in [k for k in ia_items if k.startswith("booster_")]:
    shutil.copyfile(os.path.join(ia_dir, "textures", "item", _bid + ".png"),
                    ensure(os.path.join(ia_dir, "textures", "font", _bid + ".png")))
    font_images[_bid + "_grand"] = {"show_in_gui": False, "suggest_in_command": False,
                                     "path": f"font/{_bid}.png", "scale_ratio": 31, "y_position": 18}

dump_yaml(os.path.join(ia_dir, "config.yml"), {"info": {"namespace": "fluxpet"}, "items": ia_items,
                                                 "font_images": font_images},
          "Cartes et boosters InfluCard (textures seules), généré par scripts/influcard/gen_familiers.py.\n"
          "Nom, lore et comportement : plugins/InfluPet/config.yml.")

# InfluCard : contenu à fusionner dans config.yml
rarities_xp = {r: CARD_XP[r] for r in RARITIES}
dump_yaml(os.path.join(OUT, "InfluPet", "contenu.yml"),
          {"card-xp": rarities_xp, "cards": cards, "pets": {"list": pets_cfg}, "boosters": boosters},
          "Contenu généré par scripts/influcard/gen_familiers.py (cartes, familiers, boosters).")

print(f"{len(PETS)} familiers, {len(cards)} cartes, {len(boosters)} boosters, {len(ia_items)} textures.")
