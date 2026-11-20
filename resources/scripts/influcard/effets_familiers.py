"""Effets des familiers InfluCard (EcoPets / libreforge + effets codés dans InfluCard), refonte du 2026-10-06.

10 niveaux par familier : chaque valeur va du niveau 1 (min) au niveau 10 (max). Commun, unique et épique : un
bonus propre au métier. Mythique : XP de carrière seulement (+3 % à +30 %). Prestige : XP sur toutes les carrières.

EFFETS[id] = (lignes de description, placeholders, effets libreforge). Utilisé par gen_familiers.py, et appliqué
seul aux fichiers EcoPets existants par appliquer_effets.py.

JAVA[effet] = réglages d'un effet impossible en config libreforge, codé dans InfluPet (ex-InfluCard, paquet
fr.influencraft.influpet.effets). appliquer_effets.py les écrit dans plugins/InfluPet/effets.yml, lu par le
plugin au démarrage et à chaque /influpet reload. Le familier EcoPets correspondant n'a alors que sa description
(effects: []), la valeur affichée et la valeur appliquée viennent du même (min, max) ci-dessous.

Carrière Chimiste désactivée (METIERS_DESACTIVES) : ses familiers, cartes et booster ne sont plus générés.
"""
import re

MAX_LEVEL = 10
METIERS_DESACTIVES = {"chimiste"}
# Prestige resserré le 2026-10-10 : 4 légendaires (Phénix, Licorne Astrale, Astrolabe Céleste, Kitsune des
# Neiges), un effet chacun. Nida et les 2 fabuleux (Léviathan, Faucheur du Néant) sont mis de côté.
FAMILIERS_DESACTIVES = {"fiolin", "chaudronnet", "slime_alchimique", "grand_alchimiste",
                        "nida", "leviathan", "faucheur"}

C_TXT, C_VAL, C_DOT = "&#B376E5", "&f", "&7"


def lerp(a, b):
    """Expression libreforge : a au niveau 1, b au niveau max."""
    if a == b:
        return str(a)
    return f"{a} + (%level% - 1) * {round(b - a, 6)} / {MAX_LEVEL - 1}"


def shown(a, b, decimals=2):
    """Valeur affichée dans la description, arrondie (au centième par défaut)."""
    p = 10 ** decimals
    return f"round(({lerp(a, b)}) * {p}) / {p}"


WRAP = 32  # caractères visibles par ligne (+ 2 de retrait) : calé sur « Grandit uniquement avec ses cartes » (34), la plus longue ligne fixe de l infobulle EcoPets


def _visible(word):
    """Longueur affichée d'un mot : sans codes couleur, un placeholder compte pour sa valeur (~4 car.)."""
    return len(re.sub(r"%[a-z_]+%", "0000", re.sub(r"&#[0-9A-Fa-f]{6}|&[0-9a-fk-or]", "", word)))


def _wrap(words, width):
    rows, cur = [], []
    for word in words:
        if cur and sum(_visible(w) + 1 for w in cur) + _visible(word) > width:
            rows.append(cur)
            cur = []
        cur.append(word)
    return rows + [cur]


def line(name, text):
    """Nom de l'effet en titre, puis le texte en lignes courtes et équilibrées (séparées par « \\n ») :
    le moins de lignes possible sous WRAP caractères, puis la largeur la plus petite qui garde ce nombre."""
    text = raw = text.strip()
    text = text[0].upper() + text[1:] if text[0].isalpha() else text
    words = text.split(" ")
    # tient en entier sur une ligne (« » Nom : texte ») : pas de titre séparé
    single = f"{C_DOT}» {C_VAL}{name}{C_TXT} : {raw}"
    if _visible(single) <= WRAP + 3:
        return single
    count = len(_wrap(words, WRAP))
    width = max(_visible(w) for w in words)
    while len(_wrap(words, width)) > count:
        width += 1
    lines, color = [f"{C_DOT}» {C_VAL}{name}"], C_TXT
    for row in _wrap(words, width):
        # la couleur en cours à la coupure (texte ou valeur) continue sur la ligne suivante
        lines.append(f"  {color}" + " ".join(row))
        for word in row:
            found = re.findall(r"&#[0-9A-Fa-f]{6}", word)
            color = found[-1] if found else color
    return "\n".join(lines)


def note(text):
    """Ligne secondaire (condition, recharge) sous l'effet."""
    return f"  {C_TXT}{text}"


def v(unit="", pid="bonus"):
    """Valeur du placeholder en surbrillance, unité comprise (« % » collé au placeholder)."""
    return f"{C_VAL}%{pid}%{unit}{C_TXT}"


def tools(kind):
    return [f"{m}_{kind}" for m in ("wooden", "stone", "copper", "iron", "golden", "diamond", "netherite")]


ORES_IRON = ["iron_ore", "deepslate_iron_ore"]
ORES_GOLD = ["gold_ore", "deepslate_gold_ore", "nether_gold_ore"]
ROCKS = ["stone", "cobblestone", "deepslate", "cobbled_deepslate", "andesite", "diorite", "granite", "tuff",
         "calcite", "dripstone_block", "sandstone", "red_sandstone", "terracotta", "white_terracotta",
         "orange_terracotta", "yellow_terracotta", "red_terracotta", "brown_terracotta", "light_gray_terracotta",
         "cyan_terracotta", "light_blue_terracotta", "netherrack", "basalt", "smooth_basalt", "blackstone",
         "end_stone"]
DIGGABLE = ["dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium", "mud", "clay", "gravel",
            "sand", "red_sand", "soul_sand", "soul_soil"]
# graine plantée -> nombre de stades gagnés : « stade 4 » des cultures à 8 stades (0 à 7), la même proportion
# (arrondie, jamais mûre d'un coup) pour les cultures plus courtes : sinon betterave et verrue du Nether
# (0 à 3) seraient récoltables dès la pose.
CROP_AGE = {"wheat": 4, "carrots": 4, "potatoes": 4, "melon_stem": 4, "pumpkin_stem": 4,
            "beetroots": 2, "nether_wart": 2, "pitcher_crop": 2}
FLOWERS = ["dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
           "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley", "torchflower",
           "wither_rose", "sunflower", "lilac", "rose_bush", "peony", "pink_petals", "wildflowers", "cactus_flower",
           "open_eyeblossom", "closed_eyeblossom", "pitcher_plant"]
MUSHROOMS = ["brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus", "brown_mushroom_block",
             "red_mushroom_block"]
STRIPPED = {f"{w}_{k}": f"stripped_{w}_{k}"
            for w in ("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak")
            for k in ("log", "wood")}
STRIPPED.update({f"{w}_{k}": f"stripped_{w}_{k}" for w in ("crimson", "warped") for k in ("stem", "hyphae")})
SHERDS = ["angler_pottery_sherd", "archer_pottery_sherd", "arms_up_pottery_sherd", "blade_pottery_sherd",
          "brewer_pottery_sherd", "burn_pottery_sherd", "danger_pottery_sherd", "explorer_pottery_sherd",
          "friend_pottery_sherd", "heart_pottery_sherd", "miner_pottery_sherd", "mourner_pottery_sherd",
          "plenty_pottery_sherd", "prize_pottery_sherd", "sheaf_pottery_sherd", "shelter_pottery_sherd",
          "skull_pottery_sherd", "snort_pottery_sherd"]

NO_PLAYER_PLACED = {"player_placed": False}

# Lanterne : minerais mis en évidence (couleur du contour = ChatColor)
XRAY_RADIUS, XRAY_COOLDOWN = 8, 60
XRAY_COLORS = {}
for _ore, _color in (("coal", "DARK_GRAY"), ("iron", "GRAY"), ("copper", "GOLD"), ("gold", "YELLOW"),
                     ("redstone", "RED"), ("lapis", "BLUE"), ("diamond", "AQUA"), ("emerald", "GREEN")):
    XRAY_COLORS[f"{_ore}_ore"] = _color
    XRAY_COLORS[f"deepslate_{_ore}_ore"] = _color
XRAY_COLORS.update({"nether_gold_ore": "YELLOW", "nether_quartz_ore": "WHITE", "ancient_debris": "DARK_RED"})


def ph(a, b, pid="bonus", decimals=2):
    return {"id": pid, "value": shown(a, b, decimals)}


def reach(a, b, items, name, what):
    """Portée d'action (minage, pose) avec un outil en main."""
    return ([line(name, f"+{v()} bloc"), note(what)], [ph(a, b)],
            [{"id": "block_reach", "args": {"reach": lerp(a, b)},
              "conditions": [{"id": "in_mainhand", "args": {"items": items}}]}])


def chance_drop(a, b, trigger, blocks, effects, extra_filters=None):
    filters = {"blocks": blocks}
    filters.update(extra_filters or {})
    return {"effects": effects, "args": {"chance": lerp(a, b)}, "triggers": [trigger], "filters": filters}


def java(desc, a, b, decimals=2):
    """Effet codé dans InfluCard : description seule côté EcoPets."""
    return (desc, [ph(a, b, decimals=decimals)], [])


# --------------------------------------------------------------------------- effets codés dans InfluCard
# familier : id EcoPets ; min / max : valeur au niveau 1 / au niveau max ; le reste : réglages propres à l'effet.
JAVA = {
    # 1 point d'expérience vanilla par bloc de pierre miné, blocs cassés par la Tranchée compris (chaque bloc
    # cassé lève son propre BlockBreakEvent). Pierre posée par un joueur exclue (eco isPlayerPlaced).
    "xp-pierre": {"familier": "caillou", "min": 1, "max": 10, "xp": 1, "blocs": ["stone"]},
    # creuser sans mise à jour de bloc autour (sable/gravier qui ne tombent pas, eau qui ne coule pas) :
    # un seul tirage par coup, appliqué aussi aux blocs cassés par la Tranchée dans le même tick.
    "sans-mise-a-jour": {"familier": "fourmi", "min": 10, "max": 100},
    # marche sur la neige poudreuse pendant la durée (secondes), puis recharge.
    "neige-poudreuse": {"familier": "lievre_neiges", "min": 0.5, "max": 5, "recharge": 30,
                        "message-fin": "&#B376E5Ton Lièvre des Neiges se repose &#FFD94D%secondes% s"},
    # cabosse de cacao mûre cassée : replantée (même orientation, stade 0) contre une fève, prise dans le butin
    # ou à défaut dans l inventaire (rien à payer en créatif).
    "replant-cacao": {"familier": "ouistiti", "min": 10, "max": 100},
    # poudre d'os sur une pousse : arbre d'un seul coup (autant de poudres d'os vanilla qu'il en faut, une
    # seule consommée).
    "poudre-os-instant": {"familier": "gland", "min": 10, "max": 100},
    # Résistance I (= -20 % de dégâts) à tous les joueurs dans le rayon (blocs, min au niveau 1, max au niveau
    # max). Le porteur n'en profite pas lui-même.
    "resistance-allies": {"familier": "pavois", "min": 4, "max": 12, "intervalle-ticks": 40},
    # dégâts infligés x (1 + bonus) : bonus nul à pleine vie, max (%) à 1 point de vie, linéaire entre les deux.
    "rage": {"familier": "chien_garde", "min": 10, "max": 100},
    # poudre de béton posée : béton solide aussitôt (le tick suivant, avant que la poudre ne puisse tomber).
    "beton-instant": {"familier": "betonniere", "min": 10, "max": 100},
    # fours (four, haut fourneau, fumoir) du dernier joueur qui y a mis un objet : cuisson plus rapide (%).
    "cuisson-rapide": {"familier": "soufflet", "min": 10, "max": 100},
    # objet cuit envoyé dans l'inventaire du dernier joueur qui a rempli le four, s'il est à moins de « rayon ».
    "livraison-four": {"familier": "fournaise", "min": 10, "max": 100, "rayon": 16},
    # un objet gagne +1 niveau sur un de ses enchantements VANILLA (jamais au-delà du max ; rien sans
    # enchantement vanilla, les enchantements EcoEnchants ne comptent pas) tous les N blocs
    # minés (avec le bon outil, pas posés par un joueur) ou coups portés à une créature. N = min au niveau 1,
    # max au niveau max.
    # chance qu'un enchantement à la table ne coûte pas de lapis : on rend, le tick suivant, ce que la table a
    # VRAIMENT retiré (dont_consume_lapis_chance de libreforge rend « bouton + 1 » lapis quoi qu'il arrive,
    # même en créatif où rien n'est consommé : lapis gratuits).
    "lapis-econome": {"familier": "lapin_lapis", "min": 10, "max": 100},
    # chance qu'un enchantement ne coûte pas de niveaux (rendus le tick suivant, sauf en créatif où la table n'en
    # retire pas : dont_consume_xp_chance de libreforge les rendait quand même).
    "niveaux-econome": {"familier": "orbe_xp", "min": 1, "max": 10},
    "usure-savante": {"familier": "chouette_arcane", "min": 1500, "max": 500,
                      "message": "&#B376E5Ta Chouette Arcane fait progresser &#FFD94D%enchant%"},
    # prix de vente au magasin en plus (%), bloc de vente et vente automatique. Passe par l'API de
    # VaultSupermarket (SupermarketAPI, bonus de prix de vente) : le magasin ne dépend pas d'InfluPet.
    "money-bonus": {"familier": "licorne", "min": 1, "max": 10},
    # Maj + clic droit, boussole en main : structure la plus proche (nom, distance, direction, coordonnées à
    # copier d'un clic) et la boussole pointe dessus. min / max = recharge en secondes (100 min au niveau 1,
    # 10 min au niveau max), gardée dans les données du joueur. radius en chunks. Recherche sur le fil
    # principal, une par type : pas de mineshaft ni de portail en ruine (partout), ni de manoir (recherche
    # trop lente). names : id de structure -> nom affiché (sinon l'id).
    # Roi Taupérion (légendaire de boss, 2026-11-14) : monté au niveau max, il creuse en 5 x 5 devant lui (sous lui
    # si le cavalier regarde en bas) ; sans BlockBreakEvent (les métiers ne paient pas ces blocs), butin dans
    # l'inventaire, jamais dans un claim dont le cavalier n'est pas membre ni dans les mondes listés.
    "dig": {"familier": "taupe_perion", "min": 0, "max": 0, "from-level": 10, "size": 5, "interval-ticks": 3,
            "speed-factor": 0.5, "reach": 1.6, "down-pitch": 50, "max-hardness": 50,
            "blocked-worlds": ["utopia", "utopia_copie", "resaac", "flux_rpg", "flux_dungeons", "build"]},
    "structure-compass": {
        "familier": "astrolabe", "min": 6000, "max": 600, "item": "compass", "radius": 64, "worlds": [],
        "structure-types": ["jigsaw", "desert_pyramid", "jungle_temple", "swamp_hut", "igloo", "ocean_monument",
                            "shipwreck", "ocean_ruin", "stronghold", "fortress", "end_city"],
        "directions": ["Nord", "Nord-Est", "Est", "Sud-Est", "Sud", "Sud-Ouest", "Ouest", "Nord-Ouest"],
        "found-message": "&#B376E5Ton Astrolabe a repéré &#FFD94D%structure% &#B376E5à &#FFD94D%distance% blocs "
                         "&#B376E5(%direction%) &f%coords%&#B376E5. Ta boussole pointe dessus.",
        "coords-hover": "&#B376E5Clique pour copier les coordonnées",
        "cooldown-message": "&#B376E5Ton Astrolabe se repose encore &#FFD94D%time%",
        "none-message": "&#FF5C5CTon Astrolabe ne repère aucune structure par ici.",
        "world-message": "&#FF5C5CTon Astrolabe ne fonctionne pas dans ce monde.",
        "names": {
            "village_plains": "un Village", "village_desert": "un Village", "village_savanna": "un Village",
            "village_snowy": "un Village", "village_taiga": "un Village",
            "pillager_outpost": "un Avant-poste de pillards", "bastion_remnant": "un Bastion",
            "ancient_city": "une Cité antique", "trail_ruins": "des Ruines de sentier",
            "trial_chambers": "des Chambres des épreuves", "desert_pyramid": "une Pyramide du désert",
            "jungle_pyramid": "un Temple de la jungle", "swamp_hut": "une Cabane de sorcière",
            "igloo": "un Igloo", "monument": "un Monument sous-marin", "shipwreck": "une Épave",
            "shipwreck_beached": "une Épave", "ocean_ruin_cold": "des Ruines sous-marines",
            "ocean_ruin_warm": "des Ruines sous-marines", "stronghold": "un Fort",
            "fortress": "une Forteresse du Nether", "end_city": "une Cité de l'End"}},
}


def jv(key):
    return JAVA[key]["min"], JAVA[key]["max"]


EFFETS = {
    # ---------------------------------------------------------------- Mineur
    "pepite": ([line("Pépites", f"{v('%')} de chance d'une pépite en plus en minant du fer ou de l'or")],
               [ph(10, 100)],
               [chance_drop(10, 100, "block_item_drop", ores,
                            [{"id": "drop_item", "args": {"item": nugget, "add_to_drops": True}}], NO_PLAYER_PLACED)
                for ores, nugget in ((ORES_IRON, "iron_nugget"), (ORES_GOLD, "gold_nugget"))]),
    # Xray : Maj + clic droit pioche en main (alt_click ne part pas la main vide), les minerais à XRAY_RADIUS
    # blocs brillent (glow_nearby_blocks : shulkers invisibles lumineux, contours visibles par les joueurs
    # proches aussi), durée en ticks. Recharge XRAY_COOLDOWN s.
    "lanterne": ([line("Prospection", f"Maj + clic droit, pioche en main : les minerais à {XRAY_RADIUS} blocs "
                                      f"brillent pendant {v(' s')}"),
                  note("Une fois par minute")], [ph(1, 5, decimals=1)],
                 [{"effects": [{"id": "glow_nearby_blocks",
                                "args": {"radius": XRAY_RADIUS, "duration": lerp(20, 100), "colors": XRAY_COLORS}},
                               {"id": "play_sound", "args": {"sound": "block_amethyst_block_resonate",
                                                              "pitch": 1.4, "volume": 0.7}}],
                   "args": {"cooldown": XRAY_COOLDOWN, "send_cooldown_message": False},
                   "triggers": ["alt_click"],
                   "conditions": [{"id": "is_sneaking"},
                                  {"id": "in_mainhand", "args": {"items": tools("pickaxe")}}]}]),
    "wagonnet": ([line("Galeries", f"vitesse +{v('%')} sous la couche 40")], [ph(2, 20)],
                 [{"id": "movement_speed_multiplier", "args": {"multiplier": f"1 + ({lerp(2, 20)}) / 100"},
                   "conditions": [{"id": "below_y", "args": {"y": 40}}]}]),
    # ---------------------------------------------------------------- Rochiste
    "caillou": java([line("Éclats", f"{v('%')} de chance d'un point d'expérience par bloc de pierre miné"),
                     note("Tranchée comprise")], *jv("xp-pierre")),
    # Coffre d'aventure : la roche minée laisse parfois place à un coffre vanilla dont la table de butin est
    # tirée au hasard (chaîne CHAINS["mimique_coffre"], une structure = une chance). Un tick plus tard
    # (la case doit être vidée), en console : « at %player% » donne la dimension, « keep » ne pose rien
    # si la case n'est pas vide (casse annulée, protection).
    # chance minuscule (0,001 % à 0,01 %) : affichée en « 1 chance sur N », un pourcentage arrondi donnait « 0 % »
    "mimique": ([line("Filon oublié", f"1 chance sur {v()} que la roche minée laisse place à un coffre "
                                         "d'aventure")],
                   [{"id": "bonus", "value": f"round(100 / ({lerp(0.001, 0.01)}))"}],
                   [{"effects": [{"id": "run_chain", "args": {"chain": "mimique_coffre", "run-type": "random",
                                                              "delay": 1}},
                                 {"id": "play_sound", "args": {"sound": "block_chest_open", "pitch": 0.8,
                                                                "volume": 1.0, "delay": 1}},
                                 {"id": "send_message", "args": {
                                     "message": "&#B376E5Ta Mimique a déniché un &#FFD94Dcoffre "
                                                "d'aventure&#B376E5 !", "action_bar": True, "delay": 1}}],
                     "args": {"chance": lerp(0.001, 0.01)}, "triggers": ["mine_block"],
                     "filters": {"blocks": ROCKS, **NO_PLAYER_PLACED}}]),
    "colosse": reach(0.25, 1, tools("pickaxe"), "Portée de pioche", "Pioche en main"),
    # ---------------------------------------------------------------- Déblayeur
    "fourmi": java([line("Sans éboulement", f"{v('%')} de chance de creuser sans réveiller les blocs autour : "
                                           "le sable ne tombe pas, l'eau ne coule pas"),
                   note("Tranchée comprise")], *jv("sans-mise-a-jour")),
    "cabri": ([line("Pied sûr", f"enjambe +{v()} bloc")], [ph(0.2, 0.6)],
                 [{"id": "increase_step_height", "args": {"height": lerp(0.2, 0.6)}}]),
    "lievre_neiges": java([line("Pas de neige", f"marche sur la neige poudreuse pendant {v(' s')}"),
                            note(f"Puis {JAVA['neige-poudreuse']['recharge']} s de recharge")],
                           *jv("neige-poudreuse"), 1),
    # ---------------------------------------------------------------- Cultivateur
    "abeille": ([line("Pousse express", f"{v('%')} de chance qu'une graine plantée pousse directement "
                                        "à mi-croissance")],
                [ph(10, 100)],
                [chance_drop(10, 100, "place_block", [crop], [{"id": "age_crop", "args": {"age": age}}])
                 for crop, age in CROP_AGE.items()]),
    "carottin": reach(0.25, 1, tools("hoe"), "Portée de houe", "Houe en main"),
    "ouistiti": java([line("Cacaoyer", f"{v('%')} de chance de replanter la cabosse de cacao récoltée"),
                          note("Les cabosses tout juste plantées"), note("ne se cassent pas")],
                         *jv("replant-cacao")),
    # ---------------------------------------------------------------- Herboriste
    "coquelicot": ([line("Pollen", f"{v('%')} de chance d'un point d'expérience par fleur cueillie")], [ph(10, 100)],
                   [chance_drop(10, 100, "mine_block", FLOWERS, [{"id": "give_xp", "args": {"amount": 1}}],
                                NO_PLAYER_PLACED)]),
    "champinou": ([line("Mycélium", f"{v('%')} de chance de doubler les champignons récoltés")], [ph(1, 10)],
                  [chance_drop(1, 10, "block_item_drop", MUSHROOMS,
                               [{"id": "multiply_drops", "args": {"multiplier": 2}}], NO_PLAYER_PLACED)]),
    "papillon": ([line("Bouquet", f"{v('%')} de chance de doubler les fleurs cueillies")], [ph(1, 10)],
                 [chance_drop(1, 10, "block_item_drop", FLOWERS,
                              [{"id": "multiply_drops", "args": {"multiplier": 2}}], NO_PLAYER_PLACED)]),
    # ---------------------------------------------------------------- Bûcheron
    "souche": reach(0.25, 1, tools("axe"), "Portée de hache", "Hache en main"),
    "castor": ([line("Écorceur", f"{v('%')} de chance qu'une bûche coupée tombe déjà écorcée")], [ph(10, 100)],
               [chance_drop(10, 100, "block_item_drop", [log],
                            [{"id": "cancel_drops"},
                             {"id": "drop_item", "args": {"item": stripped, "add_to_drops": True}}],
                            NO_PLAYER_PLACED)
                for log, stripped in STRIPPED.items()]),
    "gland": java([line("Croissance éclair", f"{v('%')} de chance qu'une pousse devienne un arbre d'un seul coup "
                                              "de poudre d'os")], *jv("poudre-os-instant")),
    # ---------------------------------------------------------------- Pêcheur
    "poissonnet": ([line("Appâts", f"{v('%')} de chance de récupérer un Appât Simple à chaque prise")], [ph(1, 10)],
                   [{"effects": [{"id": "run_command",
                                  "args": {"command": "customfishing items give %player% simple_bait 1"}}],
                     "args": {"chance": lerp(1, 10)}, "triggers": ["catch_fish"]}]),
    # Canne vanilla seulement : avec CustomFishing installé, catch_fish est le trigger de son intégration
    # libreforge (FishingLootSpawnEvent, texte = id du butin). Seule la canne vanilla tire le butin
    # « vanilla » (loot-conditions.yml, vanilla_rod_loots), d'où le filtre custom_fish_type. Rien n'est
    # changé côté CustomFishing. Objet posé sur le joueur (le butin apparaît au bouchon, dans l'eau).
    "crabichon": ([line("Trésors des fonds", f"{v('%')} de chance d'un trésor marin en pêchant avec une "
                                             "canne vanilla")], [ph(0.2, 2)],
                  [{"effects": [{"id": "drop_random_item",
                                 "args": {"items": ["nautilus_shell", "prismarine_shard", "prismarine_crystals"]},
                                 "mutators": [{"id": "location_to_player"}]}],
                    "args": {"chance": lerp(0.2, 2)}, "triggers": ["catch_fish"],
                    "filters": {"custom_fish_type": ["vanilla"]}}]),
    # Grâce du dauphin quand le joueur se met à nager (trigger start_swimming), durée en ticks.
    "dauphin": ([line("Nage du dauphin", f"Grâce du dauphin pendant {v(' s')} en commençant à nager"),
                note("Une fois toutes les 30 s")], [ph(0.5, 5, decimals=1)],
               [{"effects": [{"id": "potion_effect",
                              "args": {"effect": "dolphins_grace", "level": 1, "duration": lerp(10, 100),
                                       "apply_to_player": True, "particles": False}}],
                 "args": {"cooldown": 30, "send_cooldown_message": False}, "triggers": ["start_swimming"]}]),
    # ---------------------------------------------------------------- Défenseur
    "ecuyer": ([line("Allonge", f"portée d'attaque +{v()} bloc")], [ph(0.25, 1)],
                       [{"id": "entity_reach", "args": {"reach": lerp(0.25, 1)}}]),
    "pavois": java([line("Rempart", f"Résistance I (-20 % de dégâts) aux joueurs à {v()} blocs autour de toi"),
                    note("Pas pour toi-même")], *jv("resistance-allies"), 0),
    "chien_garde": java([line("Rage", f"plus ta vie est basse, plus tu frappes fort : jusqu'à +{v('%')} de "
                                      "dégâts à 1 point de vie")], *jv("rage")),
    # ---------------------------------------------------------------- Bâtisseur
    "marteau_piqueur": ([line("Archéologue", f"{v('%')} de chance de trouver un objet rare en creusant")], [ph(0.1, 1)],
               [chance_drop(0.1, 1, "mine_block", DIGGABLE,
                            [{"id": "drop_weighted_random_item", "args": {"items": [
                                {"weight": 4, "items": SHERDS},
                                {"weight": 3, "items": ["bone", "clay_ball"]},
                                {"weight": 2, "items": ["gold_nugget", "iron_nugget"]}]}}],
                            NO_PLAYER_PLACED)]),
    "betonniere": java([line("Prise rapide", f"{v('%')} de chance que la poudre de béton posée durcisse "
                                               "aussitôt")], *jv("beton-instant")),
    "grue": ([line("Chantier", f"vitesse +{v('%')} dans Valdor")], [ph(10, 100)],
             [{"id": "movement_speed_multiplier", "args": {"multiplier": f"1 + ({lerp(10, 100)}) / 100"},
               "conditions": [{"id": "in_world", "args": {"world": "Valdor"}}]}]),
    # ---------------------------------------------------------------- Fondeur
    "soufflet": java([line("Feu vif", f"cuisson +{v('%')} plus rapide dans les fours que tu remplis")],
                     *jv("cuisson-rapide")),
    # Résistance au feu au contact de la lave (trigger move + condition in_block), durée en ticks. Recharge
    # obligatoire : sans elle, rester dans la lave renouvellerait l'effet à l'infini.
    "salamandre": ([line("Peau de braise", f"Résistance au feu pendant {v(' s')} au contact de la lave"),
                    note("Une fois par minute")], [ph(1, 10, decimals=1)],
                   [{"effects": [{"id": "potion_effect",
                                  "args": {"effect": "fire_resistance", "level": 1, "duration": lerp(20, 200),
                                           "apply_to_player": True, "particles": False}}],
                     "args": {"cooldown": 60, "send_cooldown_message": False}, "triggers": ["move"],
                     "conditions": [{"id": "in_block", "args": {"block": "lava"}}]}]),
    "fournaise": java([line("Livraison", f"{v('%')} de chance qu'un objet cuit arrive directement dans ton "
                                         "inventaire"),
                       note(f"Fours que tu remplis, à {JAVA['livraison-four']['rayon']} blocs au plus")],
                      *jv("livraison-four")),
    # ---------------------------------------------------------------- Enchanteur
    "lapin_lapis": java([line("Lapis économe", f"{v('%')} de chance de ne pas consommer le lapis")],
                    *jv("lapis-econome")),
    "orbe_xp": java([line("Mémoire", f"{v('%')} de chance de ne pas consommer les niveaux en enchantant")],
                     *jv("niveaux-econome")),
    "chouette_arcane": java([line("Usure savante", f"un objet gagne un niveau d'enchantement vanilla tous les "
                                                   f"{v()} blocs minés ou coups portés"),
                             note("Enchantements de Minecraft de base,"), note("sans dépasser leur niveau maximum")], *jv("usure-savante"), 0),
    # ---------------------------------------------------------------- Chimiste : désactivé (2026-10-06)
    # "fiolin", "chaudronnet", "slime_alchimique" : effets à revoir avec la carrière Chimiste.
}

# Mythiques : XP de carrière seulement, +3 % (niveau 1) à +30 % (niveau max)
MYTHIQUES = {"dragon_gemmes": ("mineur", "Mineur"), "titan": ("rochiste", "Rochiste"),
             "ver_sables": ("deblayeur", "Déblayeur"), "epouvantail": ("cultivateur", "Cultivateur"),
             "dryade": ("herboriste", "Herboriste"), "sylvain": ("sylviculteur", "Bûcheron"),
             "flammeche": ("fondeur", "Fondeur"),
             # "grand_alchimiste": ("chimiste", "Chimiste"),  # Chimiste désactivé (2026-10-06)
             "table_vivante": ("enchanteur", "Enchanteur"), "kraken": ("pecheur", "Pêcheur"),
             "chevalier_spectral": ("defenseur", "Défenseur"), "architecte": ("batisseur", "Bâtisseur")}
for _pid, (_job, _label) in MYTHIQUES.items():
    EFFETS[_pid] = ([line(f"XP {_label}", f"+{v('%')}")], [ph(3, 30)],
                    [{"id": "job_xp_multiplier",
                      "args": {"multiplier": f"1 + ({lerp(3, 30)}) / 100", "jobs": [_job]}}])

# Prestige (2026-10-10) : 4 légendaires, un effet chacun, un dixième du maximum au niveau 1.
# Phénix : XP sur toutes les carrières, +1 % à +10 %.
EFFETS["phenix"] = ([line("Renaissance", f"XP de toutes les carrières +{v('%')}")], [ph(1, 10)],
                    [{"id": "job_xp_multiplier", "args": {"multiplier": f"1 + ({lerp(1, 10)}) / 100"}}])
# Licorne Astrale : prix de vente au magasin, +1 % à +10 % (effet codé money-bonus).
EFFETS["licorne"] = java([line("Fortune", f"tes ventes au magasin rapportent +{v('%')}"),
                          note("Vente automatique comprise")], *jv("money-bonus"))
# Kitsune des Neiges : vie maximale, +1 point par niveau (+10 au niveau max), effet libreforge bonus_health.
EFFETS["kitsune"] = ([line("Neuf vies", f"vie maximale +{v()} points")], [ph(1, 10, decimals=0)],
                     [{"id": "bonus_health", "args": {"health": lerp(1, 10)}}])
# Astrolabe Céleste : structure la plus proche (effet codé structure-compass), recharge affichée en minutes.
_cd = JAVA["structure-compass"]
EFFETS["astrolabe"] = ([line("Carte du ciel", "Maj + clic droit, boussole en main : indique la structure la "
                                              "plus proche"),
                        note(f"Une fois toutes les {v(' min')}")],
                       [ph(_cd["min"] / 60, _cd["max"] / 60, decimals=0)], [])

# une entrée de description par ligne affichée
for _pid, (_desc, _ph, _eff) in list(EFFETS.items()):
    EFFETS[_pid] = ([l for d in _desc for l in d.split("\n")], _ph, _eff)


# --------------------------------------------------------------------------- traînées des légendaires
# Traînée de particules autour du modèle qui suit le joueur (InfluPet, section trail du fichier du familier,
# écrite par appliquer_effets.py).
TRAINEES = {pid: {"particule": particle, "nombre": 2, "intervalle": 2, "hauteur": 0.3}
            for pid, particle in {"phenix": "flame", "astrolabe": "end_rod", "licorne": "firework",
                                  "kitsune": "snowflake"}.items()}


# --------------------------------------------------------------------------- chaînes libreforge
# Fusionnées dans plugins/libreforge/chains.yml par appliquer_effets.py (ids préfixés par le familier).
# Coffres d'aventure de la Mimique : un coffre par structure, les villages regroupés dans une sous-chaîne
# (sinon leurs 16 tables pèseraient plus d'un tiers des tirages). Exclus : distributeur du temple de la
# jungle, coffre bonus d'apparition, tonneau et récompenses de coffre-fort des chambres d'épreuve.
COFFRES = ["abandoned_mineshaft", "ancient_city", "ancient_city_ice_box", "bastion_bridge", "bastion_hoglin_stable",
           "bastion_other", "bastion_treasure", "buried_treasure", "desert_pyramid", "end_city_treasure",
           "igloo_chest", "jungle_temple", "nether_bridge", "pillager_outpost", "ruined_portal", "shipwreck_map",
           "shipwreck_supply", "shipwreck_treasure", "simple_dungeon", "stronghold_corridor", "stronghold_crossing",
           "stronghold_library", "trial_chambers/corridor", "trial_chambers/entrance",
           "trial_chambers/intersection", "trial_chambers/supply", "underwater_ruin_big", "underwater_ruin_small",
           "woodland_mansion"]
VILLAGE = ["armorer", "butcher", "cartographer", "desert_house", "fisher", "fletcher", "mason", "plains_house",
           "savanna_house", "shepherd", "snowy_house", "taiga_house", "tannery", "temple", "toolsmith",
           "weaponsmith"]


def _coffre(table):
    return {"id": "run_command", "args": {
        "command": "execute as %player% at %player% run setblock %location_block_x% %location_block_y% "
                   f"%location_block_z% minecraft:chest{{LootTable:\"minecraft:chests/{table}\"}} keep"}}


CHAINS = {
    "mimique_coffre": [_coffre(t) for t in COFFRES]
                         + [{"id": "run_chain", "args": {"chain": "mimique_coffre_village", "run-type": "random"}}],
    "mimique_coffre_village": [_coffre(f"village/village_{v}") for v in VILLAGE],
}
