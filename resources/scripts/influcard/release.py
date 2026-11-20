#!/usr/bin/env python3
"""Version publique d'InfluPet (BuiltByBit) : contenu par défaut en anglais, à partir du contenu de Labs.

  release.py defaults   écrit le contenu par défaut dans les ressources du plugin (plugins/fluxplugin/FluxPets/src/main/
                        resources : pets/*.yml + pets/index.txt, content/*.yml, chains.yml), avant la compilation
  release.py package    assemble release/InfluPet-<version>/ et le zip : jar compilé, pack ItemsAdder
                        « fluxpet » (textes en anglais), modèles BetterModel, documentation (docs/release/)

Notre propre configuration (Labs, Semi-Lab, prod) n'est pas touchée : ces fichiers ne sont écrits par le plugin
que sur une installation neuve. Adaptations pour un serveur vierge :
- métiers EcoJobs de chez nous -> métiers par défaut d'EcoJobs (JOBS) ;
- Grue : vitesse « dans Valdor » -> vitesse partout, bonus réduit ; Poissonnet : appât CustomFishing -> poisson
  en plus (vanilla) ; Mimique : chaînes livrées dans chains.yml d'InfluPet ;
- aucun glyphe d'un autre pack ItemsAdder (clics, raretés, boutons) : seuls nos visuels, livrés dans le pack
  ItemsAdder « fluxpet » (même namespace que chez nous depuis le 2026-11-13).
Les identifiants (familiers, cartes, boosters, modèles, textures) restent ceux de chez nous.
"""
import os
import re
import shutil
import sys
import zipfile

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import copy  # noqa: E402

from effets_familiers import CHAINS, EFFETS, JAVA, MAX_LEVEL, lerp, ph, _wrap, _visible  # noqa: E402
from effets_familiers import line as line_fr  # noqa: E402

ROOT = "/home/influencraft"
LABS = f"{ROOT}/serveurs/04_labs/plugins"
PLUGIN = f"{ROOT}/plugins/fluxplugin/FluxPets"
RES = f"{PLUGIN}/src/main/resources"
FR = f"{RES}/i18n/fr_FR"  # version française du contenu livré (même structure que l'anglais)
DOCS = f"{ROOT}/docs/release/influpet"

C_TXT, C_VAL, C_DOT = "&#B376E5", "&f", "&7"

# --------------------------------------------------------------------------- métiers

# carrière chez nous -> (job EcoJobs par défaut, nom affiché de la série)
JOBS = {"mineur": ("miner", "Miner"), "rochiste": ("miner", "Quarrier"), "deblayeur": ("miner", "Digger"),
        "cultivateur": ("farmer", "Farmer"), "herboriste": ("farmer", "Herbalist"),
        "sylviculteur": ("lumberjack", "Forester"), "fondeur": ("smelter", "Smelter"),
        "enchanteur": ("enchanter", "Enchanter"), "pecheur": ("fisherman", "Fisher"),
        "defenseur": ("slayer", "Defender"), "batisseur": ("builder", "Builder")}
JOB_NAMES_FR = {"miner": "Mineur", "farmer": "Fermier", "lumberjack": "Bûcheron", "smelter": "Fondeur",
                "enchanter": "Enchanteur", "fisherman": "Pêcheur", "slayer": "Chasseur", "builder": "Bâtisseur"}
JOB_NAMES = {"miner": "Miner", "farmer": "Farmer", "lumberjack": "Lumberjack", "smelter": "Smelter",
             "enchanter": "Enchanter", "fisherman": "Fisherman", "slayer": "Slayer", "builder": "Builder"}
SERIES = {"Mineur": "Miner", "Rochiste": "Quarrier", "Déblayeur": "Digger", "Cultivateur": "Farmer",
          "Herboriste": "Herbalist", "Bûcheron": "Forester", "Fondeur": "Smelter", "Enchanteur": "Enchanter",
          "Pêcheur": "Fisher", "Défenseur": "Defender", "Bâtisseur": "Builder", "Prestige": "Prestige"}

# --------------------------------------------------------------------------- familiers : nom et histoire

PETS = {
    "pepite": ("Nugget", "A shard of gold ore", "that decided to walk."),
    "lanterne": ("Lantern", "It lights up the tunnels", "and never goes out."),
    "wagonnet": ("Minecart", "It rolls along the rails,", "its bucket full of treasure."),
    "dragon_gemmes": ("Gem Dragon", "Its scales are diamonds,", "its hoard, the whole mountain."),
    "caillou": ("Pebble", "It does not move much,", "but it knows every stone."),
    "mimique": ("Mimic", "A chest deep in the tunnel?", "Careful, it has teeth."),
    "colosse": ("Basalt Colossus", "Born from cooled lava,", "slow but unshakable."),
    "titan": ("Crystal Titan", "Each crystal on its back", "tells a thousand years."),
    "fourmi": ("Ant", "It digs tunnels", "that never collapse."),
    "cabri": ("Kid Goat", "It leaps from rock to rock", "without ever tripping."),
    "lievre_neiges": ("Snow Hare", "It runs on powder snow", "without leaving a print."),
    "ver_sables": ("Sandworm", "It swims under the dunes", "like a fish under water."),
    "abeille": ("Bee", "It buzzes around the garden,", "and everything grows in its wake."),
    "carottin": ("Carroty", "Pulled from the garden,", "it goes back every night."),
    "ouistiti": ("Marmoset", "Every cocoa pod it pinches,", "it secretly replants."),
    "epouvantail": ("Scarecrow", "Crows flee from it,", "all but one, its best friend."),
    "coquelicot": ("Poppy", "The chattiest flower", "in the whole meadow."),
    "champinou": ("Shroomy", "It grows where no one", "thinks to look."),
    "papillon": ("Butterfly", "It never lands", "twice on the same flower."),
    "dryade": ("Dryad", "Spirit of the glade,", "flowers bloom as it passes."),
    "souche": ("Stump", "Cut down a hundred years ago,", "it kept the axe as a souvenir."),
    "castor": ("Beaver", "A tree? Three bites", "and it is done."),
    "gland": ("Acorn", "One day it will be an oak.", "For now, it rolls."),
    "sylvain": ("Sylvan", "Guardian of the old forests,", "patient as an oak."),
    "flammeche": ("Flicker", "A little flame spirit", "that never leaves the furnace."),
    "soufflet": ("Bellows", "One puff, and the embers", "roar back to life."),
    "salamandre": ("Blazing Salamander", "It sleeps in the embers", "of the blast furnace."),
    "fournaise": ("Furnace", "A furnace that came to life,", "and is always hungry."),
    "lapin_lapis": ("Lapis Bunny", "It dug so deep", "it came out all blue."),
    "orbe_xp": ("Experience Orb", "It has the color of your levels", "and always flies straight to you."),
    "chouette_arcane": ("Arcane Owl", "It reads forbidden grimoires", "in the dark."),
    "table_vivante": ("Living Table", "The enchanting table", "that picks its own spells."),
    "poissonnet": ("Fishlet", "It knows where", "its cousins bite."),
    "crabichon": ("Crabby", "It walks sideways", "but fishes straight."),
    "dauphin": ("Dolphin", "It swims faster than its shadow", "and pulls you in its wake."),
    "kraken": ("Kraken", "Captain of the abyss,", "the fish follow it."),
    "ecuyer": ("Squire", "Small, but its spear", "keeps zombies at bay."),
    "pavois": ("Bulwark", "A great living shield", "that stands before its friends."),
    "chien_garde": ("Guard Dog", "Its armor shines,", "its nose never lies."),
    "chevalier_spectral": ("Spectral Knight", "It swore to protect", "even after death."),
    "marteau_piqueur": ("Jackhammer", "It shakes the roads", "and digs up what they hid."),
    "betonniere": ("Cement Mixer", "It turns, turns, turns,", "and the concrete sets at once."),
    "grue": ("Crane", "It lifts the heaviest", "blocks on the site."),
    "architecte": ("Architect", "It already sees the cathedral", "where you see a vacant lot."),
    "phenix": ("Phoenix", "It always rises", "from its ashes."),
    "astrolabe": ("Celestial Astrolabe", "It spins the planets", "so it never gets bored."),
    "kitsune": ("Snow Kitsune", "One tail per century lived.", "It has nine."),
    "licorne": ("Astral Unicorn", "It leaves stars", "wherever it goes."),
    "nida": ("Nida", "It lost its left arm", "holding the Void's door shut."),
    "leviathan": ("Leviathan", "Sailors never", "speak its name."),
    "faucheur": ("Void Reaper", "It comes from the void", "and always returns to it."),
}


def v(unit=""):
    return f"{C_VAL}%bonus%{unit}{C_TXT}"


def line(name, text):
    """effets_familiers.line, en anglais (« Name: text »)."""
    raw = text.strip()
    text = raw[0].upper() + raw[1:] if raw[0].isalpha() else raw
    words = text.split(" ")
    single = f"{C_DOT}» {C_VAL}{name}{C_TXT}: {raw}"
    if _visible(single) <= 35:
        return [single]
    count = len(_wrap(words, 32))
    width = max(_visible(w) for w in words)
    while len(_wrap(words, width)) > count:
        width += 1
    lines, color = [f"{C_DOT}» {C_VAL}{name}"], C_TXT
    for row in _wrap(words, width):
        lines.append(f"  {color}" + " ".join(row))
        for word in row:
            found = re.findall(r"&#[0-9A-Fa-f]{6}", word)
            color = found[-1] if found else color
    return lines


def note(text):
    return f"  {C_TXT}{text}"


RADIUS = JAVA["livraison-four"]["rayon"]
SNOW_COOLDOWN = JAVA["neige-poudreuse"]["recharge"]

# description de l'effet : (titre, texte, notes)
EFFECTS = {
    "pepite": ("Nuggets", f"{v('%')} chance of an extra nugget when mining iron or gold", []),
    "lanterne": ("Prospecting", f"Shift + right-click with a pickaxe: ores within 8 blocks glow for {v(' s')}",
                 ["Once per minute"]),
    "wagonnet": ("Tunnels", f"speed +{v('%')} below layer 40", []),
    "caillou": ("Shards", f"{v('%')} chance of an experience point per stone block mined",
                ["Area mining included"]),
    "mimique": ("Forgotten Vein", f"{v('%')} chance that mined rock leaves an adventure chest behind", []),
    "colosse": ("Pickaxe Reach", f"+{v()} block", ["Pickaxe in hand"]),
    "fourmi": ("No Cave-in", f"{v('%')} chance to dig without waking the blocks around: sand does not fall, "
                             "water does not flow", ["Area mining included"]),
    "cabri": ("Sure-footed", f"steps up +{v()} block", []),
    "lievre_neiges": ("Snow Steps", f"walk on powder snow for {v(' s')}", [f"Then {SNOW_COOLDOWN} s cooldown"]),
    "abeille": ("Express Growth", f"{v('%')} chance that a planted seed starts half-grown", []),
    "carottin": ("Hoe Reach", f"+{v()} block", ["Hoe in hand"]),
    "ouistiti": ("Cocoa Grower", f"{v('%')} chance to replant the harvested cocoa pod",
                 ["Freshly planted pods", "do not break"]),
    "coquelicot": ("Pollen", f"{v('%')} chance of an experience point per flower picked", []),
    "champinou": ("Mycelium", f"{v('%')} chance to double harvested mushrooms", []),
    "papillon": ("Bouquet", f"{v('%')} chance to double picked flowers", []),
    "souche": ("Axe Reach", f"+{v()} block", ["Axe in hand"]),
    "castor": ("Debarker", f"{v('%')} chance that a cut log drops already stripped", []),
    "gland": ("Sudden Growth", f"{v('%')} chance that a sapling becomes a tree with a single bone meal", []),
    "poissonnet": ("Bonus Catch", f"{v('%')} chance to catch an extra fish", []),
    "crabichon": ("Deep Treasures", f"{v('%')} chance of a sea treasure when fishing with a vanilla rod", []),
    "dauphin": ("Dolphin Swim", f"Dolphin's Grace for {v(' s')} when you start swimming", ["Once every 30 s"]),
    "ecuyer": ("Reach", f"attack reach +{v()} block", []),
    "pavois": ("Rampart", f"Resistance I (-20 % damage) for players within {v()} blocks around you",
               ["Not for yourself"]),
    "chien_garde": ("Rage", f"the lower your health, the harder you hit: up to +{v('%')} damage at 1 health "
                            "point", []),
    "marteau_piqueur": ("Archaeologist", f"{v('%')} chance to find a rare item while digging", []),
    "betonniere": ("Quick Set", f"{v('%')} chance that placed concrete powder hardens at once", []),
    "grue": ("Site Rush", f"speed +{v('%')}", []),
    "soufflet": ("Strong Fire", f"smelting +{v('%')} faster in the furnaces you fill", []),
    "salamandre": ("Ember Skin", f"Fire Resistance for {v(' s')} when touching lava", ["Once per minute"]),
    "fournaise": ("Delivery", f"{v('%')} chance that a smelted item goes straight to your inventory",
                  [f"Furnaces you fill, within {RADIUS} blocks"]),
    "lapin_lapis": ("Lapis Saver", f"{v('%')} chance not to consume lapis", []),
    "orbe_xp": ("Memory", f"{v('%')} chance not to consume levels when enchanting", []),
    "chouette_arcane": ("Wise Wear", f"an item gains a vanilla enchantment level every {v()} blocks mined or "
                                     "hits dealt", ["Vanilla Minecraft enchantments,", "never above their maximum"]),
}
MYTHICS = {"dragon_gemmes", "titan", "ver_sables", "epouvantail", "dryade", "sylvain", "flammeche",
           "table_vivante", "kraken", "chevalier_spectral", "architecte"}
PRESTIGE = {"phenix", "astrolabe", "kitsune", "licorne", "nida", "leviathan", "faucheur"}

# effets propres à notre serveur, remplacés (placeholders, effets)
OVERRIDES = {
    "grue": ([ph(2, 20)], [{"id": "movement_speed_multiplier", "args": {"multiplier": f"1 + ({lerp(2, 20)}) / 100"}}]),
    "poissonnet": ([ph(1, 10)], [{"effects": [{"id": "drop_random_item", "args": {"items": ["cod", "salmon"]},
                                               "mutators": [{"id": "location_to_player"}]}],
                                  "args": {"chance": lerp(1, 10)}, "triggers": ["catch_fish"]}]),
}
MIMIC_MESSAGE = "&#B376E5Your Mimic dug up an &#FFD94Dadventure chest&#B376E5!"
# descriptions françaises des effets remplacés (OVERRIDES)
OVERRIDES_FR = {"grue": ("Chantier", "vitesse +{v}"),
                "poissonnet": ("Belle prise", "{v} de chance de pêcher un poisson en plus")}
GLYPHS_FR = {"&r&7%img_left_click% ": "&7[Clic gauche] ", "&r&7%img_right_click% ": "&7[Clic droit] "}


def no_glyphs_fr(text):
    for a, b in GLYPHS_FR.items():
        text = text.replace(a, b)
    return text


def description_fr(pid, labs, job=None):
    """Description française : celle de Labs, sauf effets remplacés, métiers et notes propres au serveur."""
    if pid in PRESTIGE:
        return [f"{C_DOT}» {C_TXT}XP de métier {C_VAL}+%bonus%%"]
    if pid in MYTHICS:
        return line_fr(f"XP {JOB_NAMES_FR[job]}", f"+{v('%')}").split("\n")
    if pid in OVERRIDES_FR:
        title, text = OVERRIDES_FR[pid]
        return line_fr(title, text.format(v=v("%"))).split("\n")
    return [l.replace("Tranchée comprise", "Minage de zone compris") for l in labs]


class Dumper(yaml.SafeDumper):
    def ignore_aliases(self, data):
        return True


NS = "fluxpet"


def rename_ns(text):
    """Namespace ItemsAdder du pack (fluxpet depuis le 2026-11-13, le même chez nous et dans le pack livré)."""
    return text


def dump(path, data, header):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    body = rename_ns(yaml.dump(data, Dumper=Dumper, allow_unicode=True, sort_keys=False, width=120))
    with open(path, "w", encoding="utf-8") as f:
        f.write(header.rstrip() + "\n\n" + body)


def color_of(text):
    m = re.match(r"((?:&#[0-9A-Fa-f]{6}|&[0-9a-fk-or])*)", text)
    return m.group(1) if m else ""


def enabled_pets():
    return sorted(f[:-4] for f in os.listdir(f"{LABS}/InfluPet/pets") if f.endswith(".yml") and not f.startswith("_"))


def description(pid, job=None):
    if pid in PRESTIGE:
        return [f"{C_DOT}» {C_TXT}Job XP {C_VAL}+%bonus%%"]
    if pid in MYTHICS:
        return line(f"{JOB_NAMES[job]} XP", f"+{v('%')}")
    title, text, notes = EFFECTS[pid]
    return line(title, text) + [note(n) for n in notes]


def map_jobs(node):
    """job_xp_multiplier : métiers de chez nous -> métiers par défaut d'EcoJobs ; renvoie le premier."""
    found = []
    if isinstance(node, dict):
        if node.get("id") == "job_xp_multiplier" and "jobs" in node.get("args", {}):
            node["args"]["jobs"] = sorted({JOBS[j][0] for j in node["args"]["jobs"]})
            found += node["args"]["jobs"]
        for value in node.values():
            found += map_jobs(value)
    elif isinstance(node, list):
        for value in node:
            found += map_jobs(value)
    return found


MIMIC_MESSAGE_FR = "&#B376E5Ta Mimique a déniché un &#FFD94Dcoffre d'aventure&#B376E5 !"


def translate_messages(node, message=MIMIC_MESSAGE):
    if isinstance(node, dict):
        if node.get("id") == "send_message":
            node["args"]["message"] = message
        for value in node.values():
            translate_messages(value, message)
    elif isinstance(node, list):
        for value in node:
            translate_messages(value, message)


# --------------------------------------------------------------------------- contenu par défaut


def write_pets(ids):
    out = f"{RES}/pets"
    shutil.rmtree(out, ignore_errors=True)
    shutil.rmtree(f"{FR}/pets", ignore_errors=True)
    for pid in ids:
        data = yaml.safe_load(open(f"{LABS}/InfluPet/pets/{pid}.yml", encoding="utf-8"))
        fr_name, fr_desc, fr_lines = data["name"], data["description"], data["effects-description"][1]
        name, _, _ = PETS[pid]
        color = color_of(data["name"])
        data["name"] = color + name
        data["description"] = f"{C_TXT}Only grows with its cards"
        if pid in OVERRIDES:
            data["level-placeholders"], data["effects"] = OVERRIDES[pid]
        jobs = map_jobs(data["effects"])
        translate_messages(data["effects"])
        desc = description(pid, jobs[0] if jobs else None)
        for key in ("effects-description", "rewards-description", "level-up-messages"):
            data[key] = {1: desc}
        if "spawn-egg" in data:
            data["spawn-egg"]["name"] = data["name"]
        dump(f"{out}/{pid}.yml", data,
             f"# {name}: one of the default InfluPet pets. Grows with its cards (right-click a card).\n"
             f"# Model: BetterModel/models/familier_{pid}.bbmodel, card and plush: ItemsAdder pack \"{NS}\".")
        # même fichier en français : seuls les textes changent
        fr = copy.deepcopy(data)
        fr["name"], fr["description"] = fr_name, fr_desc
        desc = description_fr(pid, fr_lines, jobs[0] if jobs else None)
        for key in ("effects-description", "rewards-description", "level-up-messages"):
            fr[key] = {1: desc}
        if "spawn-egg" in fr:
            fr["spawn-egg"]["name"] = fr_name
        translate_messages(fr["effects"], MIMIC_MESSAGE_FR)
        dump(f"{FR}/pets/{pid}.yml", fr,
             f"# {re.sub(r'&#[0-9A-Fa-f]{{6}}', '', fr_name)} : un des familiers livrés avec InfluPet. Grandit avec ses "
             f"cartes (clic droit sur une carte).\n"
             f"# Modèle : BetterModel/models/familier_{pid}.bbmodel, carte et peluche : pack ItemsAdder \"{NS}\".")
    for folder in (out, f"{FR}/pets"):
        with open(f"{folder}/index.txt", "w", encoding="utf-8") as f:
            f.write("\n".join(f"{pid}.yml" for pid in ids) + "\n")


def tr_booster_line(line_, names):
    m = re.match(r"(&#B376E5Contient &f%cards% &#B376E5cartes de la série )(&#[0-9A-Fa-f]{6})(.+?)(&#B376E5\.)$",
                 line_)
    if m:
        return f"&#B376E5Contains &f%cards% &#B376E5cards from the {m.group(2)}{SERIES[m.group(3)]}&#B376E5 series."
    m = re.match(r"(%adopted:([a-z0-9_]+)% )((?:&#[0-9A-Fa-f]{6})*)(.*)$", line_)
    if m:
        return f"{m.group(1)}{m.group(3)}{names[m.group(2)]}"
    fixed = {
        "&7Maj + clic droit : ouvrir toute la pile": "&7Shift + right-click: open the whole stack",
        "&fTous les familiers": "&fEvery pet",
        "&#B376E5Contient &f%cards% &#B376E5cartes de toutes": "&#B376E5Contains &f%cards% &#B376E5cards from",
        "&#B376E5les séries.": "&#B376E5every series.",
        "&#B376E5Familiers à adopter :": "&#B376E5Pets to adopt:",
        "&r&7%img_right_click% &#FFD94DOuvrir": "&7[Right-click] &#FFD94DOpen",
        "": "",
    }
    if line_.startswith("&#B376E5Palier : "):
        return line_.replace("&#B376E5Palier : ", "&#B376E5Tier: ")
    if line_ not in fixed:
        raise SystemExit(f"booster line not translated: {line_!r}")
    return fixed[line_]


BOOSTER_NAMES = {"Ordinaire": "Ordinary", "Tendance": "Trending", "Viral": "Viral", "Phénomène": "Phenomenal",
                 "Prestige": "Prestige"}


def write_content(ids):
    names = {pid: PETS[pid][0] for pid in ids}
    src = f"{LABS}/InfluPet/content"
    cards = yaml.safe_load(open(f"{src}/cards.yml", encoding="utf-8"))
    out_cards = {}
    for cid, card in cards["cards"].items():
        if cid not in names:
            continue
        card = dict(card)
        series = SERIES[card["series"]]
        _, l1, l2 = PETS[cid]
        card["name"] = color_of(card["name"]) + names[cid]
        card["series"] = series
        card["lore"] = [f"&#B376E5{l1}", f"&#B376E5{l2}", "", f"&#2ECF7B{series} &fseries"]
        out_cards[cid] = card
    dump(f"{RES}/content/cards.yml", {"card-xp": cards["card-xp"], "cards": out_cards},
         "# Cards (one per pet, linked with \"pet\") and XP given to the pet per card, by rarity: with 1 XP per card,\n"
         "# the level-xp-requirements of each pet are counted in cards. Also edited with /influpet menu.")

    boosters = yaml.safe_load(open(f"{src}/boosters.yml", encoding="utf-8"))["boosters"]
    out_boosters = {}
    for bid, b in boosters.items():
        b = dict(b)
        b["contents"] = [c for c in b.get("contents", []) if c.split(":", 1)[1] in names]
        m = re.match(r"((?:&#[0-9A-Fa-f]{6}|&[0-9a-fk-or])*)Booster (.+)$", b["name"])
        label = SERIES.get(m.group(2)) or BOOSTER_NAMES[m.group(2)]
        b["name"] = f"{m.group(1)}{label} Booster"
        b["lore"] = [tr_booster_line(l, names) for l in b["lore"]]
        out_boosters[bid] = b
    dump(f"{RES}/content/boosters.yml", {"boosters": out_boosters},
         "# Boosters: one per job series (the only way to adopt its pets), a Prestige booster, and 4 tiers that can\n"
         "# give any pet (at most one pet per opening). cards = draws, rarity-weights / pet-rarity-weights = rarity\n"
         "# weights, contents = \"card:<id>\" / \"pet:<id>\" (empty = all obtainable content), pet-chance = % chance\n"
         "# that a draw is a pet, duplicate-plush: false = a duplicate never gives its plush from this booster.\n"
         "# %adopted:<pet id>% in the lore shows ✔ or ✘ depending on whether the viewer adopted that pet.\n"
         "# Also edited with /influpet menu.")

    # français : textes de Labs (sans glyphes), même structure
    fr_cards = {cid: dict(card, lore=list(card["lore"])) for cid, card in cards["cards"].items() if cid in names}
    for cid, card in fr_cards.items():
        card["item"] = out_cards[cid]["item"]
    dump(f"{FR}/content/cards.yml", {"card-xp": cards["card-xp"], "cards": fr_cards},
         "# Cartes (une par familier, liée par « pet ») et XP donnée au familier par carte, selon sa rareté : avec\n"
         "# 1 XP par carte, les paliers de niveau (level-xp-requirements) se comptent en cartes. Modifié aussi par\n"
         "# /influpet menu.")
    fr_boosters = {}
    for bid, b in boosters.items():
        fb = dict(b)
        fb["contents"] = out_boosters[bid]["contents"]
        fb["lore"] = [no_glyphs_fr(l) for l in b["lore"]]
        fr_boosters[bid] = fb
    dump(f"{FR}/content/boosters.yml", {"boosters": fr_boosters},
         "# Boosters : un par série (seul moyen d'adopter ses familiers), un Prestige, et 4 paliers qui peuvent donner\n"
         "# n'importe quel familier (un au plus par ouverture). cards = tirages, rarity-weights / pet-rarity-weights\n"
         "# = poids des raretés, contents = « card:<id> » / « pet:<id> » (vide = tout le contenu obtenable),\n"
         "# pet-chance = % de chance qu'un tirage soit un familier, duplicate-plush: false = un doublon ne donne\n"
         "# jamais sa peluche depuis ce booster. %adopted:<id>% dans la description affiche ✔ ou ✘ selon que le\n"
         "# joueur a adopté ce familier. Modifié aussi par /influpet menu.")

    pets = yaml.safe_load(open(f"{src}/pets.yml", encoding="utf-8"))["pets"]["list"]
    dump(f"{RES}/content/pets.yml", {"pets": {"list": {k: v for k, v in pets.items() if k in names}}},
         "# Pets obtainable from boosters: rarity, enabled (obtainable or not), optional plush and model.\n"
         "# Each pet's definition (levels, effects) is in pets/<id>.yml. Also edited with /influpet menu.")

    dump(f"{RES}/chains.yml", {"chains": [{"id": cid, "effects": eff} for cid, eff in CHAINS.items()]},
         "# libreforge chains used by pets (run_chain), registered by InfluPet before loading the pets: same format\n"
         "# as plugins/libreforge/chains.yml. Reloaded with /influpet reload.\n"
         "# mimique_coffre: the Mimic places an adventure chest with a random vanilla loot table.")


RARITY_TAGS_FR = {"simple": "&#AAAAAAꜱɪᴍᴘʟᴇ", "unique": "&#37C44Aᴜɴɪǫᴜᴇ", "elite": "&#3283A8ᴇʟɪᴛᴇ",
                  "ultimate": "&#B376E5ᴜʟᴛɪᴍᴇ", "legendary": "&#F0A800ʟᴇɢᴇɴᴅᴀɪʀᴇ", "fabled": "&#C8256Aꜰᴀʙᴜʟᴇᴜx"}


def write_fr_settings():
    """Menus (textes de Labs sans glyphes), messages des effets et étiquettes de rareté en français."""
    for name in ("pets.yml", "progression.yml"):
        data = yaml.safe_load(open(f"{LABS}/InfluPet/menus/{name}", encoding="utf-8"))
        body = no_glyphs_fr(yaml.dump(data, Dumper=Dumper, allow_unicode=True, sort_keys=False, width=120))
        os.makedirs(f"{FR}/menus", exist_ok=True)
        header = ("# Menu /pets (alias /familiers) : rangées et colonnes à partir de 1, objets en syntaxe eco.\n"
                  if name == "pets.yml" else
                  "# Progression d'un familier niveau par niveau (clic sur un familier actif dans /pets).\n")
        open(f"{FR}/menus/{name}", "w", encoding="utf-8").write(header + "\n" + body)
    text = open(f"{RES}/effects.yml", encoding="utf-8").read()
    for a, b in (("&#B376E5Your Snow Hare is resting &#FFD94D%seconds% s",
                  "&#B376E5Ton Lièvre des Neiges se repose &#FFD94D%seconds% s"),
                 ("&#B376E5Your Arcane Owl improves &#FFD94D%enchant%",
                  "&#B376E5Ta Chouette Arcane fait progresser &#FFD94D%enchant%")):
        assert a in text, a
        text = text.replace(a, b)
    open(f"{FR}/effects.yml", "w", encoding="utf-8").write(text)
    dump(f"{FR}/config.yml", {"rarities": {r: {"tag": t} for r, t in RARITY_TAGS_FR.items()}},
         "# Valeurs françaises de config.yml (étiquettes de rareté), appliquées au changement de langue.")


RARITY_NAMES = {"simple": "Simple", "unique": "Unique", "elite": "Elite", "ultimate": "Ultimate",
                "legendary": "Legendary", "fabled": "Fabled"}


def write_pet_list(ids):
    """docs/release/influpet/PETS.md : tableau des familiers livrés (nom, rareté, série, vol, effet)."""
    cards = yaml.safe_load(open(f"{RES}/content/cards.yml", encoding="utf-8"))["cards"]
    rows = []
    for pid in ids:
        data = yaml.safe_load(open(f"{RES}/pets/{pid}.yml", encoding="utf-8"))
        parts = [re.sub(r"&#[0-9A-Fa-f]{6}|&[0-9a-fk-or]", "", l).strip().lstrip("» ")
                 for l in data["effects-description"][1]]
        desc = parts[0] + (" " if ":" in parts[0] else ": ") + " ".join(parts[1:]) if len(parts) > 1 else parts[0]
        expr = data["level-placeholders"][0]["value"]
        values = [eval(expr.replace("%level%", str(lvl)), {"round": round}) for lvl in (1, MAX_LEVEL)]
        nice = [("%g" % val) for val in values]
        desc = desc.replace("%bonus%", f"[{nice[0]} → {nice[1]}]")
        card = cards[pid]
        rows.append((list(RARITY_NAMES).index(card["rarity"]), card["series"], PETS[pid][0], pid,
                     RARITY_NAMES[card["rarity"]], "flying" if data.get("flying", True) else "ground", desc))
    rows.sort()
    with open(f"{DOCS}/PETS.md", "w", encoding="utf-8") as f:
        f.write("# Default pets\n\n"
                f"InfluPet ships with {len(rows)} pets, each with its BetterModel model, card and plush. Values in\n"
                "brackets go from level 1 to level 10 (see each pet's file in `plugins/InfluPet/pets/`).\n\n"
                "| Pet | Id | Rarity | Series | Model | Effect |\n| --- | --- | --- | --- | --- | --- |\n")
        for _, series, name, pid, rarity, mode, desc in rows:
            f.write(f"| {name} | `{pid}` | {rarity} | {series} | {mode} | {desc} |\n")


def defaults():
    ids = [pid for pid in enabled_pets() if pid in PETS]
    missing = [pid for pid in enabled_pets() if pid not in PETS]
    if missing:
        raise SystemExit(f"pets without translation: {missing}")
    write_pets(ids)
    write_content(ids)
    write_fr_settings()
    os.makedirs(DOCS, exist_ok=True)
    write_pet_list(ids)
    print(f"{len(ids)} pets, content and chains written to {RES}")


# --------------------------------------------------------------------------- paquet

DISABLED = ("fiolin", "chaudronnet", "slime_alchimique", "grand_alchimiste", "chimiste")


def ia_pack(out, ids):
    src = f"{LABS}/ItemsAdder/contents/fluxpet"
    dst = f"{out}/ItemsAdder/contents/{NS}"
    shutil.copytree(src, dst, ignore=lambda d, files: [f for f in files if any(x in f for x in DISABLED)])
    for base, _, files in os.walk(f"{dst}/models"):
        for f in files:
            path = os.path.join(base, f)
            text = open(path, encoding="utf-8").read()
            open(path, "w", encoding="utf-8").write(rename_ns(text))
    names = {pid: PETS[pid][0] for pid in ids}
    cfg = yaml.safe_load(open(f"{src}/config.yml", encoding="utf-8"))
    items = {}
    for key, item in cfg["items"].items():
        pid = key.split("_", 1)[1]
        if any(x in key for x in DISABLED) or (key.startswith(("carte_", "peluche_")) and pid not in names):
            continue
        if key.startswith("peluche_"):
            color = re.match(r"(<#[0-9A-Fa-f]{6}>)", item["display_name"]).group(1)
            item["display_name"] = f"{color}{names[pid]} Plush"
            item["lore"] = [f"<#EADBC8>A plush of {color}{names[pid]}<#EADBC8>.", "",
                            "<#6B7A8F>Obtained by completing its pet", "<#6B7A8F>series. Can be placed as furniture."]
        items[key] = item
    cfg["items"] = items
    cfg["info"]["namespace"] = NS
    cfg["font_images"] = {k: v for k, v in cfg.get("font_images", {}).items() if not any(x in k for x in DISABLED)}
    dump(f"{dst}/config.yml", cfg,
         "# InfluPet cards, boosters and plushies (ItemsAdder). Names, lore and behaviour of cards and boosters are\n"
         "# in plugins/InfluPet/content/; card and booster items here are textures only.")
    # variante française : noms et descriptions des peluches de Labs
    fr = copy.deepcopy(cfg)
    labs_items = yaml.safe_load(open(f"{src}/config.yml", encoding="utf-8"))["items"]
    for key in fr["items"]:
        if key.startswith("peluche_"):
            fr["items"][key]["display_name"] = labs_items[key]["display_name"]
            fr["items"][key]["lore"] = labs_items[key]["lore"]
    dump(f"{out}/translations/fr_FR/ItemsAdder/contents/{NS}/config.yml", fr,
         "# Version française du config.yml du pack ItemsAdder « influpet » (noms des peluches) : remplacer\n"
         "# plugins/ItemsAdder/contents/influpet/config.yml par ce fichier, puis /iazip.")


def package():
    version = yaml.safe_load(open(f"{RES}/plugin.yml"))["version"]
    jar = f"{PLUGIN}/target/InfluPet.jar"
    if not os.path.exists(jar):
        raise SystemExit("compile the plugin first (target/InfluPet.jar)")
    ids = [pid for pid in enabled_pets() if pid in PETS]
    name = f"InfluPet-{version}"
    out = f"{ROOT}/release/{name}"
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(f"{out}/BetterModel/models")
    shutil.copy(jar, f"{out}/{name}.jar")
    for pid in ids:
        shutil.copy(f"{LABS}/BetterModel/models/familier_{pid}.bbmodel", f"{out}/BetterModel/models/")
    ia_pack(out, ids)
    for f in os.listdir(DOCS):
        shutil.copy(f"{DOCS}/{f}", f"{out}/{f}")
    archive = f"{ROOT}/release/{name}.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
        for base, _, files in os.walk(out):
            for f in sorted(files):
                path = os.path.join(base, f)
                z.write(path, os.path.join(name, os.path.relpath(path, out)))
    print(f"{archive} ({os.path.getsize(archive) // 1024} KB, {len(ids)} pets)")


if __name__ == "__main__":
    {"defaults": defaults, "package": package}[sys.argv[1]]()
