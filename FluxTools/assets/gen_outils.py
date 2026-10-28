#!/usr/bin/env python3
"""Génère les outils FluxTools (outils/<id>.yml) et le menu Flux de l'outil (menus/outil.yml).

    python3 plugins/fluxplugin/FluxTools/assets/gen_outils.py [--labs <dossier plugins>]

Sources lues (jamais modifiées) :
  - nom et description de chaque outil : EcoItems/items/outils/<palier>/<id>.yml (Labs) ;
  - XP de chaque ressource : serveurs/03_semirp/plugins/EcoJobs/jobs/<métier>.yml (SemiRP fait foi,
    comme pour /sync-succes).

Calibrage d'une quête : quantité = minutes visées x rythme de la ressource (unités par minute).
Rythme par défaut = rythme de référence du métier (XP/min, table METIERS) / XP d'une unité de la
ressource : dans un métier, l'XP d'une ressource dit à quel point elle est longue à farmer. Quand
l'XP trompe (rareté des minerais, troncs géants...), le rythme est forcé dans GROUPES.
Contrats (Ordinaire, Tendance, irréparables) : la quantité est aussi plafonnée par la durabilité.
"""
import argparse
import math
import os
import yaml

RACINE = "/home/influencraft"
JOBS = RACINE + "/serveurs/03_semirp/plugins/EcoJobs/jobs"

# ─── Rythmes de référence : unités / minute de la ressource de base, avec un bon outil ─────────
METIERS = {
    "rochiste": ("stone", 40),
    "deblayeur": ("dirt", 40),
    "mineur": ("coal_ore", 6),
    "sylviculteur": ("oak_log", 12),
    "cultivateur": ("wheat", 40),
}

# ─── Groupes de ressources ──────────────────────────────────────────────────────────────────────
# id: (libellé « Miner N <libellé> », type, métier ou None, cibles, icône, rythme forcé ou None, jours possibles)
# Jours = étapes du pass où le groupe peut être tiré (1 à 7) : les ressources rares arrivent tard.
P = "miner"
R = "recolter"
T = "tuer"
A = "tirer"
COMMUNS = ["zombie", "husk", "drowned", "skeleton", "stray", "bogged", "spider", "cave_spider", "creeper"]
GROUPES = {
    "pioche": {
        "pierre": ("blocs de pierre", P, "rochiste", ["stone", "cobblestone"], "stone", None, range(1, 5)),
        "roches": ("roches (andésite, diorite, granite, tuf)", P, "rochiste", ["andesite", "diorite", "granite", "tuff"], "andesite", None, range(1, 6)),
        "deepslate": ("blocs de deepslate", P, "rochiste", ["deepslate", "cobbled_deepslate"], "deepslate", None, range(2, 8)),
        "charbon": ("minerais de charbon", P, "mineur", ["coal_ore", "deepslate_coal_ore"], "coal_ore", None, range(1, 4)),
        "cuivre": ("minerais de cuivre", P, "mineur", ["copper_ore", "deepslate_copper_ore"], "copper_ore", None, range(1, 5)),
        "fer": ("minerais de fer", P, "mineur", ["iron_ore", "deepslate_iron_ore"], "iron_ore", 4, range(2, 7)),
        "redstone": ("minerais de redstone", P, "mineur", ["redstone_ore", "deepslate_redstone_ore"], "redstone_ore", None, range(3, 7)),
        "lapis": ("minerais de lapis-lazuli", P, "mineur", ["lapis_ore", "deepslate_lapis_ore"], "lapis_ore", 2, range(3, 8)),
        "or": ("minerais d'or", P, "mineur", ["gold_ore", "deepslate_gold_ore", "nether_gold_ore"], "gold_ore", 1.5, range(4, 8)),
        "quartz": ("minerais de quartz du Nether", P, "mineur", ["nether_quartz_ore"], "nether_quartz_ore", None, range(3, 7)),
        "nether": ("blocs du Nether (netherrack, pierre noire, basalte)", P, None, ["netherrack", "blackstone", "basalt", "smooth_basalt"], "netherrack", 50, range(2, 7)),
        "obsidienne": ("blocs d'obsidienne", P, "rochiste", ["obsidian"], "obsidian", None, range(4, 8)),
        "end": ("blocs de pierre de l'End", P, "rochiste", ["end_stone"], "end_stone", None, range(5, 8)),
        "diamant": ("minerais de diamant", P, "mineur", ["diamond_ore", "deepslate_diamond_ore"], "diamond_ore", 0.5, range(5, 8)),
        "debris": ("débris antiques", P, "mineur", ["ancient_debris"], "ancient_debris", 0.25, range(6, 8)),
    },
    "pelle": {
        "terre": ("blocs de terre", P, "deblayeur", ["dirt", "coarse_dirt"], "dirt", None, range(1, 5)),
        "herbe": ("blocs d'herbe et de podzol", P, "deblayeur", ["grass_block", "podzol"], "grass_block", None, range(1, 5)),
        "sable": ("blocs de sable", P, "deblayeur", ["sand"], "sand", None, range(1, 7)),
        "neige": ("blocs de neige", P, "deblayeur", ["snow_block"], "snow_block", None, range(2, 6)),
        "gravier": ("blocs de gravier", P, "deblayeur", ["gravel"], "gravel", None, range(2, 7)),
        "sable_rouge": ("blocs de sable rouge", P, "deblayeur", ["red_sand"], "red_sand", None, range(3, 8)),
        "argile": ("blocs d'argile", P, "deblayeur", ["clay"], "clay", None, range(3, 8)),
        "ames": ("blocs de sable et de terre des âmes", P, "deblayeur", ["soul_sand", "soul_soil"], "soul_sand", None, range(4, 8)),
        "mycelium": ("blocs de mycélium", P, "deblayeur", ["mycelium"], "mycelium", None, range(5, 8)),
    },
    "hache": {
        "chene": ("bûches de chêne", P, "sylviculteur", ["oak_log"], "oak_log", None, range(1, 5)),
        "bouleau": ("bûches de bouleau", P, "sylviculteur", ["birch_log"], "birch_log", None, range(1, 5)),
        "sapin": ("bûches de sapin", P, "sylviculteur", ["spruce_log"], "spruce_log", 18, range(1, 7)),
        "chene_noir": ("bûches de chêne noir ou pâle", P, "sylviculteur", ["dark_oak_log", "pale_oak_log"], "dark_oak_log", 20, range(2, 7)),
        "cerisier": ("bûches de cerisier", P, "sylviculteur", ["cherry_log"], "cherry_log", 15, range(2, 8)),
        "jungle": ("bûches d'acajou", P, "sylviculteur", ["jungle_log"], "jungle_log", None, range(3, 8)),
        "acacia": ("bûches d'acacia", P, "sylviculteur", ["acacia_log"], "acacia_log", None, range(3, 8)),
        "mangrove": ("bûches de palétuvier", P, "sylviculteur", ["mangrove_log"], "mangrove_log", None, range(4, 8)),
        "nether": ("tiges carmin ou biscornues", P, "sylviculteur", ["crimson_stem", "warped_stem"], "crimson_stem", 6, range(4, 8)),
    },
    "houe": {
        "ble": ("blés mûrs", R, "cultivateur", ["wheat"], "wheat", None, range(1, 6)),
        "carotte": ("carottes mûres", R, "cultivateur", ["carrots"], "carrot", None, range(1, 6)),
        "patate": ("pommes de terre mûres", R, "cultivateur", ["potatoes"], "potato", None, range(1, 6)),
        "betterave": ("betteraves mûres", R, "cultivateur", ["beetroots"], "beetroot", None, range(2, 7)),
        "verrue": ("verrues du Nether mûres", R, "cultivateur", ["nether_wart"], "nether_wart", None, range(3, 8)),
        "cacao": ("cabosses de cacao mûres", R, "cultivateur", ["cocoa"], "cocoa_beans", 8, range(4, 8)),
    },
    # Armes : pas de métier exploitable (Défenseur réglé à la main, 1 XP par monstre), rythmes forcés.
    "epee": {
        "zombies": ("zombies", T, None, ["zombie", "husk", "drowned", "zombie_villager"], "zombie_head", 2, range(1, 5)),
        "squelettes": ("squelettes", T, None, ["skeleton", "stray", "bogged"], "skeleton_skull", 2, range(1, 5)),
        "araignees": ("araignées", T, None, ["spider", "cave_spider"], "spider_eye", 2, range(1, 5)),
        "creepers": ("creepers", T, None, ["creeper"], "creeper_head", 1.5, range(2, 6)),
        "slimes": ("slimes ou cubes de magma", T, None, ["slime", "magma_cube"], "slime_ball", 1.5, range(3, 7)),
        "endermen": ("endermen", T, None, ["enderman"], "ender_pearl", 0.6, range(3, 8)),
        "blazes": ("blazes", T, None, ["blaze"], "blaze_rod", 1.5, range(4, 8)),
        "gardiens": ("gardiens", T, None, ["guardian"], "prismarine_shard", 1.5, range(5, 8)),
        "sorcieres": ("sorcières", T, None, ["witch"], "glass_bottle", 0.3, range(5, 8)),
        "wither": ("wither squelettes", T, None, ["wither_skeleton"], "wither_skeleton_skull", 0.6, range(5, 8)),
    },
    "arc": {
        "squelettes": ("squelettes à l'arc", A, None, ["skeleton", "stray", "bogged"], "skeleton_skull", 1.2, range(1, 8)),
        "zombies": ("zombies à l'arc", A, None, ["zombie", "husk", "drowned"], "zombie_head", 1.5, range(1, 8)),
        "creepers": ("creepers à l'arc", A, None, ["creeper"], "creeper_head", 1.2, range(1, 8)),
        "araignees": ("araignées à l'arc", A, None, ["spider", "cave_spider"], "spider_eye", 1.5, range(1, 8)),
        "blazes": ("blazes à l'arc", A, None, ["blaze"], "blaze_rod", 1, range(3, 8)),
    },
}
GROUPES["masse"] = GROUPES["epee"]

# Contrats de toute la vie de l'outil, par famille (cibles = union de tous les groupes de la famille).
CONTRAT_ORDINAIRE = {
    "pioche": ("Miner %s blocs de pierre ou de minerai", ["rochiste", "mineur"], "stone"),
    "pelle": ("Creuser %s blocs de terre, sable ou gravier", ["deblayeur"], "dirt"),
    "hache": ("Couper %s bûches", ["sylviculteur"], "oak_log"),
    "houe": ("Récolter %s cultures mûres", None, "wheat"),
    "epee": ("Vaincre %s monstres", None, "zombie_head"),
}
# Étapes du contrat Tendance, dans l'ordre (un groupe par étape).
CONTRAT_TENDANCE = {
    "pioche": ["pierre", "roches", "deepslate", "fer", "nether"],
    "pelle": ["terre", "sable", "gravier", "argile", "ames"],
    "hache": ["chene", "bouleau", "sapin", "acacia", "nether"],
    "houe": ["ble", "carotte", "patate", "betterave", "verrue"],
    "epee": ["zombies", "squelettes", "araignees", "creepers", "endermen"],
    "arc": ["squelettes", "zombies", "araignees", "creepers", "blazes"],
}

# ─── Barèmes ────────────────────────────────────────────────────────────────────────────────────
ORDINAIRE_JALONS = [1 / 3, 2 / 3, 14 / 15]      # 50, 100, 140 sur 150
ORDINAIRE_GAINS = [25, 35, 40, 50]               # jalons puis fin : 150 au total
TENDANCE_MINUTES = [6, 9, 12, 15, 18]
TENDANCE_PARTS = [0.10, 0.15, 0.20, 0.25, 0.30]  # part de la durabilité utilisable par étape
TENDANCE_GAINS = [150, 200, 300, 350, 500]       # 1 500 au total
DURABILITE_UTILE = 0.8                            # marge : coups ratés, blocs hors quête
COUT_PAR_UNITE = {"miner": 1, "recolter": 0, "tuer": 3, "tirer": 2}   # durabilité par unité
# Quêtes bonus des outils à niveaux : une par jour pendant JOURS jours, de plus en plus longues.
JOURS = 30
PASS_MINUTES = {"virale": (10, 60), "phenomene": (15, 90)}        # 1er jour -> dernier jour, linéaire
PASS_GAINS = {"virale": (200, 1100), "phenomene": (600, 3300)}     # BitBlock, idem
PRIME_SEMAINE = 2    # récompense doublée tous les 7 jours et le dernier jour


def lineaire(bornes, jour):
    a, b = bornes
    return a + (b - a) * (jour - 1) / (JOURS - 1)

# ─── Niveaux d'outil (Virale, Phénomène) ────────────────────────────────────────────────────────
# 20 niveaux, indépendants des quêtes : l'XP d'outil vient de l'usage (1 XP = 1 seconde de farm de la
# ressource, rythme ci-dessus), les quêtes en donnent en bonus. Courbe en n^EXPOSANT : les premiers
# niveaux tombent en quelques minutes, les derniers demandent des heures.
NIVEAUX = 20
EXPOSANT = 1.6
HEURES_NIVEAU_MAX = {"virale": 30, "phenomene": 40}
BONUS_QUETE = 0.5           # XP bonus d'une quête = 50 % du temps qu'elle demande
# Déblocages : niveau d'outil -> niveau de l'enchantement. Tous les enchantements des outils à niveaux
# partent de 0 (aucun enchantement à la sortie de caisse).
PALIERS_PRINCIPAL = {1: 1, 3: 2, 6: 3, 9: 4, 12: 5, 16: 6, 20: 7}   # Efficacité, Tranchant, Puissance, Densité
PALIERS_SECONDAIRE = {4: 1, 10: 2, 17: 3}                            # Fortune, Butin
PALIERS_SOLIDITE = {2: 1, 8: 2, 14: 3}
NIVEAU_CAPACITE = [5, 11]   # niveau de déblocage de la 1re capacité, puis de la 2e (masse)
METIERS_FAMILLE = {"pioche": ["rochiste", "mineur"], "pelle": ["deblayeur"], "hache": ["sylviculteur"], "houe": ["cultivateur"]}

# ─── Enchantements ──────────────────────────────────────────────────────────────────────────────
NIVEAU = {   # famille -> (enchantement de niveau, libellé)
    "pioche": ("efficiency", "Efficacité"), "pelle": ("efficiency", "Efficacité"),
    "hache": ("efficiency", "Efficacité"), "houe": ("efficiency", "Efficacité"),
    "epee": ("sharpness", "Tranchant"), "arc": ("power", "Puissance"), "masse": ("density", "Densité"),
}
FIXES_CONTRAT = {
    "pioche": {"efficiency": 5, "fortune": 3}, "pelle": {"efficiency": 5, "fortune": 3},
    "hache": {"efficiency": 5, "fortune": 3}, "houe": {"efficiency": 5, "fortune": 3},
    "epee": {"sharpness": 5, "looting": 3}, "arc": {"power": 5, "flame": 1},
}
FIXES_PASS = {
    "pioche": {"fortune": 3, "unbreaking": 3}, "pelle": {"fortune": 3, "unbreaking": 3},
    "hache": {"fortune": 3, "unbreaking": 3}, "houe": {"fortune": 3, "unbreaking": 3},
    "epee": {"looting": 3, "unbreaking": 3}, "masse": {"unbreaking": 3},
}
# Capacité signature (EcoEnchants), une par outil : le reste des enchantements custom disparaît.
CAPACITES = {
    "pioche_virale": {"filon": 1}, "pioche_phenomene": {"tranchee": 1},
    "pelle_virale": {"tranchee": 1}, "hache_virale": {"tranchee": 1}, "hache_phenomene": {"buche": 3},
    "houe_virale": {"recolte": 9}, "epee_virale": {"immolation": 4}, "epee_phenomene": {"frappe_du_ravageur": 3},
    "masse_phenomene": {"colere_de_thor": 3, "seisme": 2},
}
# Modèles 3D du pack (composant item_model), générés par assets/modeles/gen_<outil>.py. Absent : apparence vanilla.
MODELES = {"pioche_phenomene": "influoutils:pioche_phenomene"} | {
    o + "_virale": "influoutils:" + o + "_virale" for o in ("pioche", "pelle", "hache", "houe", "epee")}
# Outils donnés par les caisses (pelle et houe Phénomène existent dans EcoItems mais ne sont pas données).
OUTILS = {
    "ordinaire": ["pioche", "pelle", "hache", "houe", "epee"],
    "tendance": ["pioche", "pelle", "hache", "houe", "epee", "arc"],
    "virale": ["pioche", "pelle", "hache", "houe", "epee"],
    "phenomene": ["pioche", "hache", "epee", "masse"],
}
PREFIXE_RARETE = "&7Rareté : "


def xp_metier(job):
    """{bloc: XP} du métier (xp-gain-methods)."""
    d = yaml.safe_load(open(os.path.join(JOBS, job + ".yml")))
    xp = {}
    for m in d.get("xp-gain-methods", []) or []:
        v = float(m["value"] if m.get("value") is not None else m.get("multiplier", 1))
        for b in (m.get("filters") or {}).get("blocks", []) or []:
            xp.setdefault(b, v)
    return xp


XP = {j: xp_metier(j) for j in METIERS}


def rythme(groupe):
    _, _, job, cibles, _, force, _ = groupe
    if force:
        return force
    base, par_min = METIERS[job]
    xp_min = par_min * XP[job][base]
    xp_unite = sum(XP[job][c] for c in cibles if c in XP[job]) / max(1, sum(1 for c in cibles if c in XP[job]))
    return xp_min / xp_unite


def arrondi(n):
    """Quantité lisible : 2 chiffres significatifs, minimum 1."""
    if n < 10:
        return max(1, round(n))
    e = 10 ** (int(math.log10(n)) - 1)
    return int(round(n / e) * e)


def verbe(type_):
    return {"miner": "Miner", "recolter": "Récolter", "tuer": "Vaincre", "tirer": "Abattre"}[type_]


def quete(gid, g, quantite):
    libelle, type_, _, cibles, icone, _, _ = g
    return {"id": gid, "libelle": "%s %s %s" % (verbe(type_), "{:,}".format(quantite).replace(",", " "), libelle),
            "type": type_, "cibles": list(cibles), "quantite": quantite, "icone": icone}


def lire_ecoitems(labs, palier, id_):
    d = yaml.safe_load(open(os.path.join(labs, "EcoItems/items/outils", palier, id_ + ".yml")))["item"]
    item = d["item"].split()
    duree = 0
    for t in item[1:]:
        if t.startswith("max_damage:"):
            duree = int(t.split(":")[1])
    lore = [l for l in d["lore"] if l not in ("&#FF5C5CIrréparable",) and not l.endswith("durabilités")]
    return item[0], d["name"], lore, duree


def contrat_ordinaire(famille, duree):
    libelle, jobs, icone = CONTRAT_ORDINAIRE[famille]
    if famille == "houe":
        cibles = sorted({c for g in GROUPES["houe"].values() for c in g[3]})
        type_ = R
    elif famille == "epee":
        cibles = sorted({c for g in GROUPES["epee"].values() for c in g[3]})
        type_ = T
    else:
        cibles = sorted({b for j in jobs for b in XP[j]} if famille != "hache" else
                        {c for g in GROUPES["hache"].values() for c in g[3]})
        type_ = P
    total = duree if type_ in (P, R) else int(duree / COUT_PAR_UNITE[type_] * 0.9)
    jalons = [[max(1, round(total * f)), g] for f, g in zip(ORDINAIRE_JALONS, ORDINAIRE_GAINS)]
    q = {"id": "contrat", "libelle": libelle % total, "type": type_, "cibles": cibles, "quantite": total, "icone": icone}
    return [{"recompense": ORDINAIRE_GAINS[-1], "jalons": jalons, "quetes": [q]}]


def contrat_tendance(famille, duree):
    etapes = []
    for i, gid in enumerate(CONTRAT_TENDANCE[famille]):
        g = GROUPES[famille][gid]
        n = TENDANCE_MINUTES[i] * rythme(g)
        cout = COUT_PAR_UNITE[g[1]]
        if cout:
            n = min(n, duree * DURABILITE_UTILE * TENDANCE_PARTS[i] / cout)
        etapes.append({"recompense": TENDANCE_GAINS[i], "quetes": [quete(gid, g, arrondi(n))]})
    return etapes


def pass_(famille, palier):
    etapes = []
    for jour in range(1, JOURS + 1):
        minutes = lineaire(PASS_MINUTES[palier], jour)
        stade = 1 + (jour - 1) * 7 // JOURS        # jours possibles des groupes : 1 à 7
        pool = [quete(gid, g, arrondi(minutes * rythme(g))) for gid, g in GROUPES[famille].items() if stade in g[6]]
        gain = arrondi(lineaire(PASS_GAINS[palier], jour))
        if jour % 7 == 0 or jour == JOURS:
            gain *= PRIME_SEMAINE
        etapes.append({"recompense": gain, "xp": int(minutes * 60 * BONUS_QUETE), "quetes": pool})
    return etapes


def seuils(palier):
    poids = [n ** EXPOSANT for n in range(1, NIVEAUX + 1)]
    a = HEURES_NIVEAU_MAX[palier] * 3600 / sum(poids)
    cumul, total = [], 0
    for w in poids:
        total += a * w
        cumul.append(int(round(total)))
    return cumul


def points(famille):
    """XP d'outil par unité = secondes de farm (60 / rythme), seulement pour les groupes à rythme forcé
    (rareté des minerais, monstres des armes...). Les autres blocs sont calculés par FluxTools au chargement
    depuis les métiers EcoJobs du serveur (xp-metiers de sa config, depuis le 2026-10-28) : ils suivent EcoJobs
    sans relancer ce script."""
    pts = {}
    for g in GROUPES[famille].values():
        if not g[5]:
            continue
        for c in g[3]:
            pts[c] = round(60 / rythme(g), 2)
    return dict(sorted(pts.items()))


def outil(labs, palier, famille):
    id_ = "%s_%s" % (famille, palier)
    materiau, nom, lore, duree = lire_ecoitems(labs, palier, id_)
    pass_mode = palier in ("virale", "phenomene")
    o = {
        "nom": nom,
        "description": lore,
        "palier": palier,
        "famille": famille,
        "materiau": materiau,
        **({"modele": MODELES[id_]} if id_ in MODELES else {}),
        "durabilite": duree,
        "reparable": pass_mode,
        "mode": "pass" if pass_mode else "contrat",
        "quotidien": pass_mode,
        "se-brise": palier == "ordinaire",
    }
    if pass_mode:
        ench = {NIVEAU[famille][0]: dict(PALIERS_PRINCIPAL)}
        for e in FIXES_PASS[famille]:
            ench[e] = dict(PALIERS_SOLIDITE if e == "unbreaking" else PALIERS_SECONDAIRE)
        for i, (e, v) in enumerate(CAPACITES.get(id_, {}).items()):
            ench[e] = {NIVEAU_CAPACITE[min(i, len(NIVEAU_CAPACITE) - 1)]: v}
        o["enchantements"] = ench
        o["niveau"] = {"seuils": seuils(palier)}
        o["points"] = points(famille)
        o["etapes"] = pass_(famille, palier)
    else:
        o["enchantements"] = dict(FIXES_CONTRAT[famille])
        o["etapes"] = contrat_ordinaire(famille, duree) if palier == "ordinaire" else contrat_tendance(famille, duree)
    return id_, o


ENTETE = ("# Généré par plugins/fluxplugin/FluxTools/assets/gen_outils.py : ne pas modifier à la main.\n"
          "# Barèmes, rythmes et capacités : en tête du script. Puis /fluxitems reload.\n")


def ecrire(chemin, donnees, entete=ENTETE):
    os.makedirs(os.path.dirname(chemin), exist_ok=True)
    texte = entete + yaml.safe_dump(donnees, allow_unicode=True, sort_keys=False, width=200)
    if os.path.exists(chemin) and open(chemin).read() == texte:
        return False
    open(chemin, "w").write(texte)
    return True


# ─── Menu Flux ──────────────────────────────────────────────────────────────────────────────
# Palette FluxTools (« rubis ») : rubis (noms), rose pâle (texte), grenat (filets), menthe, gris rosé.
TITRE, TEXTE, FILET, OK, KO = "&#FF4D6D", "&#FFD6DE", "&#A33A50", "&#7BE0A1", "&#A89BA0"
L = FILET + "│ " + TEXTE
FIN = FILET + "└ " + TEXTE


def noeud_quete(i, case):
    """Boutons (4 états) de la i-ème étape/quête, posés sur une case. Texte de la quête coupé en
    3 lignes au plus par le plugin (%fluxitems_noeud_<i>_quete_l<k>%, lignes vides masquées)."""
    pre = "%%fluxitems_noeud_%d_" % i
    base = {"amount": str(i), "slots": [case], "name": TITRE + pre + "titre%"}
    recompense = L + "Récompense : &f%img_bitblock% " + pre + "recompense%"
    gain = "%%fluxitems_noeud_%d_ligne_gain%%" % i   # bonus d'XP, ligne masquée pour un contrat
    return {
        "q%d_fait" % i: dict(base, material="itemsadder:influitems:accept", priority=1,
                             **{"view-requirements": ["placeholder: " + pre + "etat% == fait"],
                                "lore": [pre + "quete_l1%", pre + "quete_l2%", pre + "quete_l3%", FILET + "└ " + OK + "Réussie."]}),
        "q%d_cours" % i: dict(base, material="%fluxitems_icone%", priority=2, glow=True,
                              **{"view-requirements": ["placeholder: " + pre + "etat% == en_cours"],
                                 "lore": [pre + "quete_l1%", pre + "quete_l2%", pre + "quete_l3%",
                                          L + "%fluxitems_barre% &f%fluxitems_avance%&7/&f%fluxitems_quantite%",
                                          recompense, gain, FIN + "En cours."]}),
        "q%d_attente" % i: dict(base, material="clock", priority=3,
                                **{"view-requirements": ["placeholder: " + pre + "etat% == attente"],
                                   "lore": [pre + "quete_l1%", pre + "quete_l2%", pre + "quete_l3%", recompense, gain, FIN + "Une quête par jour."]}),
        "q%d" % i: dict(base, material="itemsadder:influitems:lock", priority=4,
                        lore=[pre + "quete_l1%", pre + "quete_l2%", pre + "quete_l3%", recompense, gain, FILET + "└ " + KO + "Pas encore atteinte."]),
    }


# Grille du menu (coffre de 2 rangées, fonds de assets/gen_fond.py) : dans le coffre, en-tête niveau (2),
# outil (4), statistiques (6) et les niveaux (10 à 16, 7 par page) ; dans l'inventaire masqué, la quête du
# jour (22) et les jours (28 à 34). La séparation coffre / inventaire du client sépare les deux contenus.
NIVEAUX_CASES = list(range(10, 17))
QUETE_DU_JOUR = 22
QUETES_CASES = list(range(28, 35))
PAGE = 7


def titre(fond):
    return ":offset_-8::%s:" % fond


def fleche(nom, case, materiau, libelle, cible):
    return {nom: {"material": "itemsadder:influitems:" + materiau, "slots": [case], "name": TITRE + libelle,
                  "actions": {"left": ["[page-swap] " + cible, "[sound] @page"]}}}


def page_outil(n):
    """Contrat (Ordinaire, Tendance) : étapes ou jalons centrés sur la rangée des quêtes."""
    debut = QUETES_CASES[0] + (7 - n + 1) // 2
    cases = list(range(debut, debut + n))
    items = {}
    for i, case in enumerate(cases, 1):
        items.update(noeud_quete(i, case))
    return {"title": titre("menu_outil_contrat_%d" % n),
            "paths": {"etapes": {"slots": cases, "progress": "%fluxitems_atteints%", "color": "or", "start": False, "end": False}},
            "items": items}


def page_pass(l, q, nl, nq):
    """Outil à niveaux : page l des niveaux (7 par page) et page q des quêtes (7 jours par page)."""
    d_niv, d_jour = PAGE * (l - 1), PAGE * (q - 1)
    niveaux = list(range(d_niv + 1, min(d_niv + PAGE, NIVEAUX) + 1))
    jours = list(range(d_jour + 1, min(d_jour + PAGE, JOURS) + 1))
    items = {}
    for n, case in zip(niveaux, NIVEAUX_CASES):
        pre = "%%fluxitems_niveau_%d_" % n
        lignes = [pre + "l%d%%" % k for k in (1, 2, 3)]
        base = {"amount": str(n), "slots": [case], "name": TITRE + "Niveau %d" % n}
        items["niv%d_fait" % n] = dict(base, material="itemsadder:influitems:accept", priority=1,
                                       **{"view-requirements": ["placeholder: " + pre + "etat% == fait"],
                                          "lore": lignes + [FILET + "└ " + OK + "Atteint."]})
        items["niv%d" % n] = dict(base, material="itemsadder:influitems:lock", priority=2,
                                  lore=lignes + [FIN + "XP requise : &f" + pre + "xp%"])
    for j, case in zip(jours, QUETES_CASES):
        items.update(noeud_quete(j, case))
    if l > 1:
        items.update(fleche("niv_prec", 9, "btn_prev", "Niveaux %d à %d" % (d_niv - PAGE + 1, d_niv), "outil_pass_%d_%d" % (l - 1, q)))
    if l < nl:
        items.update(fleche("niv_suiv", 17, "btn_next", "Niveaux %d à %d" % (d_niv + PAGE + 1, min(d_niv + 2 * PAGE, NIVEAUX)),
                            "outil_pass_%d_%d" % (l + 1, q)))
    if q > 1:
        items.update(fleche("q_prec", QUETES_CASES[0] - 1, "btn_prev", "Jours %d à %d" % (d_jour - PAGE + 1, d_jour), "outil_pass_%d_%d" % (l, q - 1)))
    if q < nq:
        items.update(fleche("q_suiv", QUETES_CASES[-1] + 1, "btn_next", "Jours %d à %d" % (d_jour + PAGE + 1, min(d_jour + 2 * PAGE, JOURS)),
                            "outil_pass_%d_%d" % (l, q + 1)))
    return {"title": titre("menu_outil"), "paths": {
        "niveaux": {"slots": NIVEAUX_CASES[:len(niveaux)], "progress": "%fluxitems_niveau%", "offset": d_niv,
                    "color": "or", "start": False, "end": False},
        "quetes": {"slots": QUETES_CASES[:len(jours)], "progress": "%fluxitems_atteints%", "offset": d_jour,
                   "color": "vert", "start": False, "end": False}},
        "items": items}


def menu(tailles):
    entete = {
        "fermer": {"material": "itemsadder:influitems:close", "name": TITRE + "Fermer", "slots": [0],
                   "lore": [FILET + "└ &r&7%img_left_click% &fFermer"], "actions": {"left": ["[close]"]}},
        "niveau": {"material": "experience_bottle", "slots": [2],
                   "name": TITRE + "Niveau %fluxitems_niveau%&7/&f%fluxitems_niveau_max%",
                   "view-requirements": ["placeholder: %fluxitems_mode% == pass"],
                   "lore": [L + "L'outil gagne de l'XP à chaque",
                            L + "ressource utile. Ses enchantements",
                            L + "montent avec son niveau.",
                            FIN + "%fluxitems_xp_barre% &f%fluxitems_xp%&7/&f%fluxitems_xp_requis%"]},
        "outil": {"material": "source:fluxitems:main", "slots": [4]},
        "stats": {"material": "itemsadder:influitems:stairs", "name": TITRE + "Statistiques", "slots": [6],
                  "lore": [L + "Blocs : &f%fluxitems_blocs%",
                           L + "Monstres vaincus : &f%fluxitems_tues%",
                           L + "Quêtes réussies : &f%fluxitems_quetes%",
                           FIN + "Gagné : &f%img_bitblock% %fluxitems_gagne%"]},
        "quete": {"material": "writable_book", "slots": [QUETE_DU_JOUR], "name": TITRE + "Quête du jour", "glow": True,
                  "view-requirements": ["placeholder: %fluxitems_etat% == en_cours"],
                  "lore": ["%fluxitems_quete_l1%", "%fluxitems_quete_l2%", "%fluxitems_quete_l3%",
                           L + "%fluxitems_barre% &f%fluxitems_avance%&7/&f%fluxitems_quantite%",
                           FIN + "Récompense : &f%img_bitblock% %fluxitems_recompense%"]},
        "quete_attente": {"material": "clock", "slots": [QUETE_DU_JOUR], "name": TITRE + "Prochaine quête",
                          "view-requirements": ["placeholder: %fluxitems_etat% == attente"],
                          "lore": [FIN + "Disponible dans &f%fluxitems_attente%"]},
        "quete_finie": {"material": "itemsadder:influitems:checkmark", "slots": [QUETE_DU_JOUR], "name": TITRE + "Quêtes terminées",
                        "view-requirements": ["placeholder: %fluxitems_etat% == termine"],
                        "lore": [FILET + "└ " + OK + "Toutes les quêtes sont réussies."]},
    }
    pages = {"outil_aucun": {"title": titre("menu_outil_contrat_5"), "items": {
        "aucun": {"material": "itemsadder:influitems:unknown", "slots": [4], "name": TITRE + "Aucun outil",
                  "lore": [FIN + "Tiens un outil de caisse en main."]}}}}
    for n in sorted(tailles):
        pages["outil_%d" % n] = page_outil(n)
    nl, nq = (NIVEAUX + PAGE - 1) // PAGE, (JOURS + PAGE - 1) // PAGE
    for l in range(1, nl + 1):
        for q in range(1, nq + 1):
            pages["outil_pass_%d_%d" % (l, q)] = page_pass(l, q, nl, nq)
    return {"title": titre("menu_outil"), "rows": 2, "player-inventory": "hidden",
            "vanilla-background": False, "update-interval": 20,
            "open-actions": ["[page-swap] %fluxitems_page%"], "items": entete, "pages": pages}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--labs", default=RACINE + "/serveurs/04_labs/plugins")
    a = ap.parse_args()
    tailles, ecrits = set(), 0
    for palier, familles in OUTILS.items():
        for famille in familles:
            id_, o = outil(a.labs, palier, famille)
            if o["mode"] == "contrat":
                n = len(o["etapes"]) if not o["etapes"][0].get("jalons") or len(o["etapes"]) > 1 else len(o["etapes"][0]["jalons"]) + 1
                tailles.add(n)
            ecrits += ecrire(os.path.join(a.labs, "FluxTools/outils", id_ + ".yml"), o)
            resume = " | ".join("%s" % ", ".join("%s %d" % (q["id"], q["quantite"]) for q in e["quetes"][:4]) for e in o["etapes"])
            print("%-18s %s" % (id_, resume[:200]))
    ecrits += ecrire(os.path.join(a.labs, "Flux/menus/outil.yml"), menu(tailles),
                     "# Généré par plugins/fluxplugin/FluxTools/assets/gen_outils.py : ne pas modifier à la main.\n"
                     "# Menu de l'outil en main (accroupi + clic droit, /outil).\n")
    print("%d fichier(s) écrit(s)." % ecrits)


if __name__ == "__main__":
    main()
