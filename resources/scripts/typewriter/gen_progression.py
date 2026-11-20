#!/usr/bin/env python3
"""Génère les pages Typewriter de la progression (tutoriel + grades + scoreboard) pour Labs.

Usage : python3 gen_progression.py [dossier plugins du serveur]     (Labs par défaut), SERVEUR ARRÊTÉ

Organisation reprise de la prod (dossiers du panel = « chapter ») :
  global                      fact (faits communs : grade, monde, métiers, donjons...), sequence (son d'étape,
                              animation de grade, suivi de quête) ; le scoreboard est celui de Flux
  batiments.bureaucitoyen     bureau_citoyen : PNJ du Bureau des citoyens (même PNJ que la prod) et son enseigne
  quete.<grade>               fact (faits du grade), quest (quête, objectifs, boussoles, déclencheurs), reward
                              (passage au grade suivant)
  quete.<grade>.step<grade>   1, 2, 3... : une page par étape (messages, actions, réponses du PNJ)

Qui décide quoi (2026-11-19) : Flux expose l'état du joueur en placeholders et Typewriter les observe.
  %flux_spawn%                    groupe de spawn (tutoriel : point d'arrivée, fanfare, familiers des métiers)
  %fluxdungeons_has_class%        classe RPG choisie
  %fluxdungeons_beaten_<donjon>%  donjon réussi au moins une fois
  %fluxshop_player_near|buy|sell% magasin vu, achat, vente
Typewriter ne change Flux que par une commande, quand une étape le demande : `fluxspawn set <joueur> none` quand la
récolte du tutoriel est faite (le prochain /spawn mène au vrai spawn, la fanfare et les familiers disparaissent).

Les identifiants sont stables (hash du nom logique) : relancer le script réécrit les mêmes entrées. ATTENTION : toute
modification faite ensuite dans le panel sur ces pages est écrasée par une régénération, sauf la page
bureau_citoyen, écrite une seule fois (position et skin du PNJ se règlent ensuite dans le panel).
Le script écrit pages/ et staging/ : Typewriter garde le staging en mémoire et le réécrit à l'arrêt, d'où le serveur
arrêté. Chaque node est complété avec les valeurs par défaut des blueprints des extensions.
Détail : docs/technique/typewriter-progression.md.
"""
import copy
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

import yaml

PLUGINS = Path(sys.argv[1] if len(sys.argv) > 1 else "/home/influencraft/serveurs/04_labs/plugins")
TW = PLUGINS / "Typewriter"
FOLDERS = [TW / "pages", TW / "staging"]
VERSION = "0.9.0-beta-176"

# Pages des versions précédentes du script, supprimées à chaque génération.
OLD_PAGES = ["progression_facts", "progression_quetes", "progression_actions", "progression_declencheurs",
             "bureau_inscriptions", "hud_scoreboard"]

# --------------------------------------------------------------------------------------------- blueprints

BLUEPRINTS = {}
for jar in sorted((TW / "extensions").glob("*.jar")):
    with zipfile.ZipFile(jar) as z:
        if "extension.json" in z.namelist():
            for e in json.loads(z.read("extension.json"))["entries"]:
                BLUEPRINTS.setdefault(e["id"], e["dataBlueprint"])


def default(b):
    if "default" in b:
        return copy.deepcopy(b["default"])
    k = b.get("kind")
    if k == "object":
        return {n: default(v) for n, v in b["fields"].items()}
    if k == "list":
        return []
    if k == "map":
        return {}
    if k == "enum":
        return b["values"][0]
    if k == "primitive":
        return {"string": "", "integer": 0, "double": 0.0, "boolean": False}.get(b["type"], None)
    if k == "custom":
        ed = b.get("editor")
        if ed == "ref":
            return ""
        if ed == "optional":
            return {"enabled": False, "value": default(b["shape"]["fields"]["value"]) if b["shape"].get("kind") == "object" and "value" in b["shape"].get("fields", {}) else default(b["shape"])}
        return default(b["shape"]) if "shape" in b else None
    if k == "algebraic":
        case = next(iter(b["cases"]))
        return {"case": case, "value": default(b["cases"][case])}
    return None


def ident(key):
    alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    n = int(hashlib.sha256(("influ-progression:" + key).encode()).hexdigest(), 16)
    out = ""
    for _ in range(15):
        n, r = divmod(n, len(alphabet))
        out += alphabet[r]
    return out


PAGES_WRITTEN = []


class Page:
    def __init__(self, file, name, kind, chapter):
        self.file, self.name, self.kind, self.chapter = file, name, kind, chapter
        self.entries = []
        PAGES_WRITTEN.append(self)

    def add(self, type_, key, **fields):
        bp = BLUEPRINTS.get(type_)
        if bp is None:
            raise SystemExit(f"type inconnu (extension absente ?) : {type_}")
        entry = default(bp)
        entry["id"] = ident(key)
        entry["name"] = key.split("/")[-1]
        for f, v in fields.items():
            if f not in bp["fields"]:
                raise SystemExit(f"{type_}.{f} n'existe pas")
            entry[f] = v
        entry["type"] = type_
        entry["_genericBlueprint"] = None
        self.entries.append(entry)
        return entry["id"]

    def write(self):
        data = {"id": ident("page/" + self.file), "name": self.name, "type": self.kind, "entries": self.entries,
                "chapter": self.chapter, "priority": 0, "version": VERSION}
        for folder in FOLDERS:
            folder.mkdir(parents=True, exist_ok=True)
            (folder / f"{self.file}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
        print(f"  {self.chapter} / {self.name} ({self.file}.json) : {len(self.entries)} entrées")


def c(fact, op, value):
    return {"fact": fact, "operator": op, "value": value}


m = c


def opt(value):
    return {"enabled": True, "value": value}


def sound(sound_id, volume=1.0, pitch=1.0):
    s = default(BLUEPRINTS["play_sound"]["fields"]["sound"]["shape"])
    s.update({"soundId": {"type": "default", "value": sound_id}, "volume": volume, "pitch": pitch})
    return s


# --------------------------------------------------------------------------------------------- données

RANKS = yaml.safe_load((PLUGINS / "FluxOffering" / "ranks.yml").read_text())["ranks"]
ORDER = ["inconnu", "citoyen", "repute", "populaire", "eminent", "figure", "influenceur", "icone", "legende",
         "mythe", "souverain", "supreme"]
GRADE = {r: i + 1 for i, r in enumerate(ORDER)}           # valeur du fact « grade »

# Donjon à réussir à chaque grade pour passer au suivant (un par grade, du niveau 10 au niveau 100), avec le nom du
# métier affiché. Souverain et Suprême n'en ont pas (il n'y a que 10 donjons).
DONJONS = {
    "inconnu": ("rochiste", "Rochiste"), "citoyen": ("cultivateur", "Cultivateur"), "repute": ("sylviculteur", "Bûcheron"),
    "populaire": ("deblayeur", "Déblayeur"), "eminent": ("mineur", "Mineur"), "figure": ("herboriste", "Herboriste"),
    "influenceur": ("fondeur", "Fondeur"), "icone": ("defenseur", "Défenseur"), "legende": ("pecheur", "Pêcheur"),
    "mythe": ("enchanteur", "Enchanteur"),
}
for _id, _ in DONJONS.values():
    if not (PLUGINS / "FluxDungeons" / "dungeons" / f"{_id}.yml").exists():
        raise SystemExit(f"donjon inconnu : {_id} (FluxDungeons/dungeons/)")

# Monde du spawn sur Labs : resaac (futur spawn de la prod, cf. flux-mise-en-prod.md). Le Bureau des citoyens et le
# magasin y sont. Groupe de spawn du tutoriel : plugins/Flux/spawns.yml.
SPAWN_WORLD = "resaac"
SPAWN_GROUP = "tutoriel"
BUREAU_POS = {"world": SPAWN_WORLD, "x": 39.5, "y": -2.0, "z": 16.5, "yaw": 0.0, "pitch": 0.0}   # regarde le sud
MAGASIN = dict(label="Magasin", x=-36.5, y=-2, z=60.5, world=SPAWN_WORLD)
PORTAIL_RPG = dict(label="Monde RPG", x=16.5, y=4, z=53, world=SPAWN_WORLD)

RECOLTE_CIBLE = 32

# Noms des quêtes : ceux de la prod (d'Inconnu à Icône), puis inventés dans le même esprit pour les grades que la prod
# n'avait pas (2026-11-19).
QUEST_NAMES = {
    "inconnu": "Citoyen sous registre", "citoyen": "Réputé de Façade", "repute": "Populaire au port",
    "populaire": "Éminent sous pression", "eminent": "Figure de style", "figure": "Figure d'influence",
    "influenceur": "Forgeur d’Icône", "icone": "Quatrième Légende",
    "legende": "Mythe en devenir", "mythe": "Souverain sans couronne", "souverain": "L'ascension suprême",
    "supreme": "Au sommet",
}

# Sous-étapes de chaque grade (demande du 2026-11-19 : un scoreboard léger, une tâche à la fois). Dans l'ordre :
#   1. « Choisis un <N>e métier » (N = « Métiers simultanés » du grade dans FluxOffering/ranks.yml) ;
#   2. les découvertes du grade ci-dessous, une par une (Citoyen : puis le magasin, découvrir / acheter / vendre) ;
#   3. l'offrande ET le donjon du grade, ensemble : le seul moment à deux tâches. Le grade suivant vient quand les deux
#      sont faits.
# Une tâche faite d'avance (home déjà posé, métier déjà choisi) passe aussitôt. Découverte : (clé, texte, déclencheur),
#   ("cmd", regex)   commande tapée (regex sur la ligne entière sans « / », Typewriter on_detect_command_ran)
#   ("monde", regex) premier passage dans un monde (change_world_event)
# Pas de virgule dans un texte d'objectif.
Y = "<#4FC3C9>"
YE = "</#4FC3C9>"
DECOUVERTES = {
    "citoyen": [
        ("sethome", f"Pose ton premier home {Y}/sethome{YE}", ("cmd", r"(?i)(cmi )?sethome( .*)?")),
    ],
    "repute": [
        ("nether", f"Entre dans le {Y}Nether{YE} {Y}/monde{YE}", ("monde", r"(?i).*nether.*")),
        ("bottlexp", f"Mets ton XP en fiole {Y}/bottlexp{YE}", ("cmd", r"(?i)bottlexp( .*)?")),
    ],
    "populaire": [
        ("end", f"Entre dans l'{Y}End{YE} {Y}/monde{YE}", ("monde", r"(?i).*the_end.*|.*_end")),
        ("pw", f"Découvre les warps {Y}/pw{YE}", ("cmd", r"(?i)(pw|warps)( .*)?")),
        ("pets", f"Ouvre tes familiers {Y}/pets{YE}", ("cmd", r"(?i)pets( .*)?")),
    ],
    "eminent": [
        ("repair", f"Répare un objet {Y}/repair{YE}", ("cmd", r"(?i)(cmi )?repair( .*)?")),
        ("etabli", f"Ouvre un établi à distance {Y}/etabli{YE}", ("cmd", r"(?i)(etabli|carto)( .*)?")),
    ],
    "figure": [
        ("hat", f"Mets un bloc sur ta tête {Y}/hat{YE}", ("cmd", r"(?i)(cmi )?hat( .*)?")),
        ("coffre", f"Ouvre ton coffre personnel {Y}/coffre{YE}", ("cmd", r"(?i)coffre( .*)?")),
    ],
    "influenceur": [
        ("pweather", f"Change ta météo {Y}/pweather{YE}", ("cmd", r"(?i)(cmi )?pweather( .*)?")),
        ("tissage", f"Ouvre un métier à tisser {Y}/tissage{YE}", ("cmd", r"(?i)(tissage|decoupe)( .*)?")),
    ],
    "icone": [
        ("stratum", f"Entre dans {Y}Stratum{YE} {Y}/monde{YE}", ("monde", r"(?i)stratum")),
        ("pays", f"Découvre les pays {Y}/pays{YE}", ("cmd", r"(?i)(pays|p)( .*)?")),
    ],
    "legende": [
        ("feed", f"Nourris-toi {Y}/feed{YE}", ("cmd", r"(?i)(cmi )?feed( .*)?")),
        ("condense", f"Compacte tes ressources {Y}/condense{YE}", ("cmd", r"(?i)(cmi )?condense( .*)?")),
    ],
    "mythe": [],
    "souverain": [],
    "supreme": [
        ("heal", f"Soigne-toi {Y}/heal{YE}", ("cmd", r"(?i)(cmi )?heal( .*)?")),
        ("nick", f"Change ton pseudo affiché {Y}/nick{YE}", ("cmd", r"(?i)(cmi )?nick( .*)?")),
    ],
}


def job_slots(rank):
    """« Métiers simultanés » du grade dans ranks.yml (le premier nombre), 0 sans cette ligne."""
    for p in RANKS[rank].get("perks", []):
        if p.lower().startswith("métiers simultanés"):
            found = re.search(r"\d+", p.partition("|")[2])
            return int(found.group()) if found else 0
    return 0


# Son par grade, repris des commandes de rang CMI de la prod (CMI/Settings/Ranks.yml).
RANK_SOUND = {"citoyen": "block.note_block.pling", "repute": "block.note_block.bell", "populaire": "entity.witch.celebrate"}


def legacy_to_mini(text):
    """&7 / &#RRGGBB / &f... des perks de ranks.yml en MiniMessage."""
    names = {"0": "black", "1": "dark_blue", "2": "dark_green", "3": "dark_aqua", "4": "dark_red", "5": "dark_purple",
             "6": "gold", "7": "gray", "8": "dark_gray", "9": "blue", "a": "green", "b": "aqua", "c": "red",
             "d": "light_purple", "e": "yellow", "f": "white", "l": "bold", "o": "italic", "n": "u", "r": "reset"}
    text = re.sub(r"&#([0-9A-Fa-f]{6})", r"<#\1>", text)
    return re.sub(r"&([0-9a-fk-orA-FK-OR])", lambda mm: f"<{names.get(mm.group(1).lower(), 'reset')}>", text)


SMALL = dict(zip("abcdefghijklmnopqrstuvwxyz", "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ"))


def smallcaps(text):
    """« Salaire » ➜ « Sᴀʟᴀɪʀᴇ » : première lettre gardée, la suite en petites capitales (accents gardés). Une
    commande (« /action ») reste telle quelle."""
    if text.startswith("/"):
        return text
    return text[:1] + "".join(SMALL.get(ch, ch) for ch in text[1:])


def perks(rank):
    """Avantages du grade (ranks.yml, « Clé | Valeur ») en lignes de la carte, au format de l'annonce de métier."""
    out = []
    for p in RANKS[rank].get("perks", []):
        if "&m" in p:
            continue
        key, sep, value = p.partition("|")
        if sep:
            out.append(legacy_to_mini(f"<dark_gray>•</dark_gray> <gray>{smallcaps(key.strip())} :</gray> <white>{value.strip()}</white>"))
        else:
            out.append(legacy_to_mini(f"<dark_gray>•</dark_gray> <white>{p.strip()}</white>"))
    return out


def chapter(r):
    return f"quete.{r}"


def steps(r):
    return f"quete.{r}.step{r}"


# ============================================================================================= global / fact

facts = Page("global_fact", "fact", "static", "global")
F_GRADE = facts.add("value_placeholder", "facts/grade",
                    comment="Grade sur le parcours LuckPerms « standard » (1 Inconnu ... 12 Suprême). Toute la progression en découle.",
                    placeholder="%luckperms_current_group_on_track_standard%",
                    values={**{r: GRADE[r] for r in ORDER}, "default": 1, "Inconnu": 1})
F_MONDE = facts.add("value_placeholder", "facts/monde",
                    comment="Monde du joueur : 1 Atlas (le monde principal world sur Labs ; ancré pour que atlas_nether ne compte pas), 2 spawn (Resaac, Utopia), 3 Valdor, 4 Nether, 5 End, 6 Stratum, 7 lobby RPG, 0 autre.",
                    placeholder="%player_world%",
                    values={"(?i)^(atlas|world|overworld)$": 1, "(?i)resaac|utopia(_copie)?": 2, "(?i)valdor": 3,
                            "(?i).*nether.*": 4, "(?i).*the_end.*|.*_end": 5, "(?i)stratum": 6, "(?i)flux_rpg": 7,
                            ".*": 0})
F_METIERS = facts.add("number_placeholder", "facts/metiers", comment="Métiers actifs (EcoJobs).",
                      placeholder="%ecojobs_in_jobs%")
F_VILLES = facts.add("number_placeholder", "facts/villes", comment="Villes dont le joueur est habitant (Lands).",
                     placeholder="%lands_lands_amount%")
F_SPAWN = facts.add("value_placeholder", "facts/spawn_tutoriel",
                    comment=f"1 tant que le joueur est dans le groupe de spawn « {SPAWN_GROUP} » de Flux (point d'arrivée du tutoriel, fanfare, familiers des métiers). Lu de %flux_spawn%.",
                    placeholder="%flux_spawn%", values={SPAWN_GROUP: 1})
F_CLASSE = facts.add("value_placeholder", "facts/classe_rpg", comment="1 quand le joueur a choisi sa classe RPG (FluxDungeons).",
                     placeholder="%fluxdungeons_has_class%", values={"true": 1})
F_DONJON = {}
for r, (d, job) in DONJONS.items():
    F_DONJON[r] = facts.add("value_placeholder", f"facts/donjon_{d}",
                            comment=f"1 quand le donjon {job} ({d}) a été réussi au moins une fois (FluxDungeons).",
                            placeholder=f"%fluxdungeons_beaten_{d}%", values={"true": 1})
F_OFFRANDE = {}
for r in ORDER[1:-1]:
    F_OFFRANDE[r] = facts.add("value_placeholder", f"facts/offrande_{r}",
                              comment=f"Offrande {RANKS[r]['name']} complète (FluxOffering).",
                              placeholder=f"%offrandes_{r}%", values={"true": 1})
V_POSITION = facts.add("relative_position_variable", "facts/position_joueur")

# ============================================================================================= global / sequence

seq = Page("global_sequence", "sequence", "sequence", "global")
S_ETAPE = seq.add("play_sound", "commun/son_etape", sound=sound("ui.cartography_table.take_result"))
FIREWORK_LOC = {"_kind": "backed", "ref": V_POSITION,
                "data": {"coordinate": {"x": 0, "y": 0, "z": 0, "yaw": 0, "pitch": 0}, "absolute": []}}
S_FW = seq.add("firework", "commun/feu_artifice", location=FIREWORK_LOC, flightDuration=300,
               effects=[{"type": "BALL_LARGE", "flicker": False, "trail": False, "colors": [4294957389], "fadeColors": []},
                        {"type": "STAR", "flicker": True, "trail": True, "colors": [4289951461], "fadeColors": []}])
S_LVL = seq.add("play_sound", "commun/son_niveau", sound=sound("entity.player.levelup"))
S_FW_DELAI = seq.add("delayed_action", "commun/delai_feu", duration=600, triggers=[S_FW])
S_PARTICULES = seq.add("spawn_particles", "commun/particules", particle="TOTEM_OF_UNDYING", count=60,
                       offset={"x": 0.6, "y": 1.0, "z": 0.6}, speed=0.4)
S_ANIM = seq.add("play_sound", "commun/animation_grade", sound=sound("ui.toast.challenge_complete"),
                 triggers=[S_LVL, S_FW_DELAI, S_PARTICULES])
# Sortie du tutoriel : le prochain /spawn mène au vrai spawn, fanfare et familiers disparaissent (groupe de Flux).
S_FIN_TUTO = seq.add("console_run_command", "commun/quitter_spawn_tutoriel", criteria=[c(F_SPAWN, "==", 1)],
                     command="fluxspawn set %player_name% none")

# Suivi de quête : la quête suivie est toujours celle du grade. Rejoué à la connexion et à chaque changement de grade.
QUEST = {r: ident(f"quete/{r}") for r in ORDER}
track = [seq.add("track_quest", f"suivi/suivre_{r}", criteria=[c(F_GRADE, "==", GRADE[r])], quest=QUEST[r]) for r in ORDER]
S_TRACK = seq.add("simple_action", "suivi/suivre_quete_du_grade", triggers=track)
S_TRACK_DELAI = seq.add("delayed_action", "suivi/attendre_chargement", duration=1500, triggers=[S_TRACK])
seq.add("on_player_join", "suivi/connexion", triggers=[S_TRACK_DELAI])
seq.add("fact_change_event", "suivi/grade_change", fact=F_GRADE, triggers=[S_TRACK, S_FIN_TUTO])


def broadcast(rank):
    col = RANKS[rank]["colour"]
    parts = ["", {"text": "✦ ", "color": "#FFD94D"}, {"text": "%player_name%", "color": "white"},
             {"text": " est désormais ", "color": "gray"}, {"text": RANKS[rank]["name"], "color": col},
             {"text": " sur le serveur !", "color": "gray"}]
    return "tellraw @a " + json.dumps(parts, ensure_ascii=False)


def card(rank):
    """Carte « Nouveau grade » dans le tchat (2026-11-19) : même présentation que l'annonce de niveau de métier
    (FluxJobs, level-up.*), aux couleurs du grade."""
    col = RANKS[rank]["colour"]
    bar = " " * 24
    lines = [f"<{col}><strikethrough>{bar}</strikethrough> ɴᴏᴜᴠᴇᴀᴜ ɢʀᴀᴅᴇ <strikethrough>{bar}</strikethrough></{col}>",
             "",
             f" <dark_gray>『</dark_gray> <reset>%img_{rank}% <{col}><bold>{RANKS[rank]['name']}</bold></{col}> <dark_gray>』</dark_gray>",
             ""]
    lines += [f"  <reset>{p}" for p in perks(rank)]
    lines += ["", f"<{col}><strikethrough>{' ' * 60}</strikethrough></{col}>"]
    return "\n".join(lines)


def rankup(page, r, crit):
    """Passage au grade r : grade LuckPerms, CMI aligné sans ses commandes, annonce, titre, carte, animation."""
    title = page.add("show_title", f"grade/{r}_titre", title="<#FFD94D>Nouveau grade</#FFD94D>",
                     subtitle=f"<reset>%img_{r}%", durations=opt({"fadeIn": 300, "stay": 4000, "fadeOut": 800}))
    card_msg = page.add("simple_message", f"grade/{r}_message", message=card(r))
    snd = RANK_SOUND.get(r, "block.note_block.chime")
    commands = "\n".join([
        f"lp user %player_name% parent settrack standard {r}",
        f"cmi rankset %player_name% {r}",
        broadcast(r),
        f"execute as @a at @s run playsound minecraft:{snd} master @s ~ ~ ~ 0.3 1",
        f"discord broadcast ⭐ **%player_name%** vient d'atteindre le rang **{RANKS[r]['name']}** !",
    ])
    return page.add("console_run_command", f"grade/{r}_passage", criteria=crit, command=commands,
                    triggers=[title, card_msg, S_ANIM])


def auto(page, key, criteria, action, children):
    """Audience à critères + trigger_audience : l'action part dès que les critères sont vrais, et à chaque connexion
    où ils le sont encore (rattrapage après une coupure)."""
    trig = page.add("trigger_audience", f"{key}_declencheur", onEnter=action, onExit="")
    children.append(page.add("criteria_audience", key, criteria=criteria, inverted=False, children=[trig]))


def step_action(page, key, grade, fact, frm, to, extra=()):
    """Étape suivante : le fact d'étape passe de frm à to (une seule fois) et le son d'étape. Aucun message dans le
    tchat : l'objectif de l'étape est dans le scoreboard (demande du 2026-11-19)."""
    trig = [S_ETAPE, *extra]
    return page.add("simple_action", key, criteria=[c(F_GRADE, "==", GRADE[grade]), c(fact, "==", frm)],
                    modifiers=[m(fact, "=", to)], triggers=trig)


# ============================================================================================= Bureau des citoyens

BUREAU_NPC = ident("bureau/pnj_definition")
# Comme en prod : PNJ, clics et objectifs pointent sur la définition du PNJ (un npc_instance n'accepte qu'une
# npc_definition : avec l'ancienne définition « indicateur », Typewriter refusait de faire apparaître le PNJ).
BUREAU = BUREAU_NPC
if not (TW / "pages" / "bureau_citoyen.json").exists():
    # Même PNJ que la prod (Simone Brel, Bureau des citoyens) : nom, skin et voix repris de sa définition.
    prod = Path("/home/influencraft/serveurs/03_semirp/plugins/Typewriter/pages")
    prod_def = {"displayName": "<white><bold>Simone Brel", "skin": {"texture": "", "signature": ""},
                "sound": sound("entity.evoker.celebrate", 1.0, 1.5)}
    for f in prod.glob("*.json"):
        for e in json.loads(f.read_text())["entries"]:
            if e["id"] == "PUr16zig5rtYpeY":
                prod_def.update(displayName=e["displayName"], skin=e["skin"])
                prod_def["sound"] = {**prod_def["sound"], **e["sound"]}
    bureau = Page("bureau_citoyen", "bureau_citoyen", "manifest", "batiments.bureaucitoyen")
    bureau.add("npc_definition", "bureau/pnj_definition", **prod_def)
    look = bureau.add("look_close_activity", "bureau/regarde_joueur")
    bureau.add("npc_instance", "bureau/simone_brel", definition=BUREAU, spawnLocation=BUREAU_POS, activity=look)
    bureau.add("hologram", "bureau/enseigne",
               text="<#9AD29A><bold>BUREAU DES CITOYENS</bold></#9AD29A>\n<#B376E5>Deviens Citoyen ici</#B376E5>",
               spawnLocation={**BUREAU_POS, "y": BUREAU_POS["y"] + 2.6}, billboardConstraint="CENTER",
               shadow=True, opacity=-1, lineWidth=300, textAlignment="CENTER", backgroundColor=0)
else:
    print("  batiments.bureaucitoyen / bureau_citoyen : déjà présente, laissée telle quelle (réglée dans le panel)")

# ============================================================================================= quête Inconnu (tutoriel)

R = "inconnu"
fi = Page("quete_inconnu_fact", "fact", "static", chapter(R))
E = fi.add("permanent_fact", "inconnu/etape",
           comment="Tutoriel : 0 métier, 1 Atlas, 2 récolte, 3 retour au spawn, 4 monde RPG, 5 classe, 6 donjon Rochiste, 7 Bureau, 8 ville, 9 retour au Bureau.")
F_RECOLTE = fi.add("permanent_fact", "inconnu/recolte", comment="Blocs cassés pendant l'étape récolte du tutoriel.")

qi = Page("quete_inconnu_quest", "quest", "manifest", chapter(R))
ri = Page("quete_inconnu_reward", "reward", "sequence", chapter(R))
RANKUP_CITOYEN = rankup(ri, "citoyen", [c(F_GRADE, "==", 1), c(E, ">=", 7), c(F_VILLES, ">=", 1)])

TUTO = {}     # n -> page de l'étape n (1 à 10, l'étape n correspond à E == n - 1)
for n in range(1, 11):
    TUTO[n] = Page(f"quete_inconnu_step_{n}", str(n), "sequence", steps(R))

ROCHISTE = DONJONS["inconnu"][1]
# Aucun message d'objectif dans le tchat : le scoreboard les montre. Seul le PNJ du Bureau parle.
# Étape 1 : choisir un métier (clic droit sur un familier du point d'arrivée). Familier offert : le familier commun du
# métier choisi, adopté (avec sa révélation) puis actif (FluxPets, starter-pets).
T_FAMILIER = TUTO[1].add("console_run_command", "inconnu/1_familier_offert", command="influpet pet starter %player_name%")
T2 = step_action(TUTO[1], "inconnu/1_metier_choisi", R, E, 0, 1, extra=[T_FAMILIER])
T3 = step_action(TUTO[2], "inconnu/2_dans_atlas", R, E, 1, 2)
# Étape 3 : récolte, puis le spawn du tutoriel est quitté (le prochain /spawn mène au vrai spawn)
T4 = step_action(TUTO[3], "inconnu/3_recolte_faite", R, E, 2, 3, extra=[S_FIN_TUTO])
T5 = step_action(TUTO[4], "inconnu/4_retour_spawn", R, E, 3, 4)
# Étape 5 : monde RPG (le choix de la classe s'ouvre seul à la première visite)
T6 = step_action(TUTO[5], "inconnu/5_monde_rpg", R, E, 4, 5)
T7 = step_action(TUTO[6], "inconnu/6_classe", R, E, 5, 6)
T8 = step_action(TUTO[7], "inconnu/7_donjon", R, E, 6, 7)
# Étapes 8 à 10 : Bureau, ville, retour au Bureau. Le PNJ répond par messages instantanés (jamais de dialogue
# bloquant) : l'état ne change que par des actions à critères, un joueur qui se déconnecte n'est jamais bloqué.
S_TAMPON = TUTO[10].add("play_sound", "inconnu/10_tampon", sound=sound("block.vault.close_shutter"),
                        triggers=[TUTO[10].add("play_sound", "inconnu/10_papier", sound=sound("item.book.page_turn"))])
g_ok = TUTO[10].add("send_message", "inconnu/10_inscription", speaker=BUREAU,
                    criteria=[c(F_GRADE, "==", 1), c(E, ">=", 7), c(F_VILLES, ">=", 1)],
                    message="<gray><i>*Simone tamponne ton passeport*</i></gray> Tu as une adresse : je t'inscris au registre. Bienvenue, <bold><#9AD29A>Citoyen</#9AD29A></bold>.",
                    triggers=[S_TAMPON, RANKUP_CITOYEN])
E8 = TUTO[8].add("simple_action", "inconnu/8_demande_ville_etape", criteria=[c(E, "==", 7)], modifiers=[m(E, "=", 8)],
                 triggers=[S_ETAPE])
g_ville = TUTO[8].add("send_message", "inconnu/8_demande_ville", speaker=BUREAU,
                      criteria=[c(F_GRADE, "==", 1), c(E, "==", 7), c(F_VILLES, "==", 0)],
                      message=f"Si tu souhaites devenir %img_citoyen% il te faudra une adresse. Pour cela trouve-toi une ville : loue une parcelle avec {Y}/agence{YE} ou cherche dans {Y}/annuaire{YE}. Quand tu reviendras avec une appartenance je t'enregistrerai.",
                      triggers=[E8])
g_rappel = TUTO[9].add("send_message", "inconnu/9_rappel_ville", speaker=BUREAU,
                       criteria=[c(F_GRADE, "==", 1), c(E, "==", 8), c(F_VILLES, "==", 0)],
                       message=f"Il te faut encore une ville : {Y}/agence{YE} ou {Y}/annuaire{YE}.")
T10 = step_action(TUTO[9], "inconnu/9_ville_rejointe", R, E, 8, 9)
g_tot = TUTO[8].add("send_message", "inconnu/8_trop_tot", speaker=BUREAU, criteria=[c(F_GRADE, "==", 1), c(E, "<", 7)],
                    message="Reviens me voir quand tu auras réussi ton premier donjon. Le tableau à droite te guide.")
g_info = TUTO[8].add("send_message", "inconnu/8_deja_citoyen", speaker=BUREAU, criteria=[c(F_GRADE, ">=", 2)],
                     message=f"Ton inscription est à jour. Les grades et leurs avantages sont dans {Y}/grade{YE}.")
# ordre important : l'inscription et le rappel sont lus avant que la demande ne passe l'étape à 8
TUTO[8].add("entity_interact_event", "inconnu/8_clic_bureau", definition=BUREAU, shiftType="ANY", interactionType="ALL",
            triggers=[g_ok, g_rappel, g_ville, g_tot, g_info])

# Déclencheurs du tutoriel (rattrapage à la connexion), imbriqués sous « grade Inconnu »
kids = []
auto(qi, "inconnu/declencheur_metier", [c(E, "==", 0), c(F_METIERS, ">=", 1)], T2, kids)
auto(qi, "inconnu/declencheur_atlas", [c(E, "==", 1), c(F_MONDE, "==", 1)], T3, kids)
auto(qi, "inconnu/declencheur_recolte", [c(E, "==", 2), c(F_RECOLTE, ">=", RECOLTE_CIBLE)], T4, kids)
auto(qi, "inconnu/declencheur_spawn", [c(E, "==", 3), c(F_MONDE, "==", 2)], T5, kids)
auto(qi, "inconnu/declencheur_rpg", [c(E, "==", 4), c(F_MONDE, "==", 7)], T6, kids)
auto(qi, "inconnu/declencheur_classe", [c(E, "==", 5), c(F_CLASSE, "==", 1)], T7, kids)
auto(qi, "inconnu/declencheur_donjon", [c(E, "==", 6), c(F_DONJON[R], "==", 1)], T8, kids)
auto(qi, "inconnu/declencheur_ville", [c(E, "==", 8), c(F_VILLES, ">=", 1)], T10, kids)
# récolte faite mais encore dans le groupe du tutoriel (coupure au mauvais moment) : on le sort
auto(qi, "inconnu/declencheur_sortie_spawn", [c(E, ">=", 3), c(F_SPAWN, "==", 1)], S_FIN_TUTO, kids)
qi.add("criteria_audience", "inconnu/grade_inconnu", criteria=[c(F_GRADE, "==", 1)], inverted=False, children=kids)
# un joueur passé Citoyen autrement (staff) ne garde pas le spawn du tutoriel
grade_kids = []
auto(qi, "inconnu/declencheur_hors_tutoriel", [c(F_SPAWN, "==", 1)], S_FIN_TUTO, grade_kids)
qi.add("criteria_audience", "inconnu/grade_superieur", criteria=[c(F_GRADE, ">=", 2)], inverted=False, children=grade_kids)

# Quête et objectifs (un objectif visible par étape)
QI = QUEST[R]
qi.add("quest", "quete/inconnu", displayName=QUEST_NAMES["inconnu"],
       activeCriteria=[c(F_GRADE, "==", 1)], completedCriteria=[c(F_GRADE, ">=", 2)])


FLIP = {"==": "!=", "!=": "==", ">=": "<", "<": ">=", ">": "<=", "<=": ">"}


def task(page, key, quest, shown, done, display, prio, children=()):
    """Un objectif qui disparaît du scoreboard dès qu'il est fait (demande du 2026-11-19) : visible tant que ses
    critères de réussite ne sont pas tous remplis (un seul critère par tâche, retourné)."""
    assert len(done) == 1
    d = done[0]
    return page.add("objective", key, quest=quest, criteria=[*shown, c(d["fact"], FLIP[d["operator"]], d["value"])],
                    display=display, priorityOverride=opt(prio), children=list(children))


def waypoint(page, key, wp):
    return page.add("waypoint_manifest", key, **wp)


WP_BUREAU = dict(label="Bureau des citoyens", x=BUREAU_POS["x"], y=BUREAU_POS["y"], z=BUREAU_POS["z"], world=SPAWN_WORLD)
qi.add("objective", "inconnu/obj_1_metier", quest=QI, criteria=[c(E, "==", 0)],
       display=f"Choisis ton premier métier et le familier qui va avec ({Y}/metier{YE})")
qi.add("objective", "inconnu/obj_2_atlas", quest=QI, criteria=[c(E, "==", 1)],
       display=f"Rejoins le monde ressource {Y}Atlas{YE} : {Y}/monde{YE}")
qi.add("break_block_objective", "inconnu/obj_3_recolte", quest=QI, criteria=[c(E, "==", 2)],
       block={"enabled": False, "value": "AIR"},
       progressTracking={"value": F_RECOLTE, "operator": ">=", "target": RECOLTE_CIBLE},
       display="Récolte des ressources <value>/<target>", completionTriggers=[T4])
qi.add("objective", "inconnu/obj_4_spawn", quest=QI, criteria=[c(E, "==", 3)], display=f"Reviens au spawn {Y}/spawn{YE}")
qi.add("objective", "inconnu/obj_5_rpg", quest=QI, criteria=[c(E, "==", 4)],
       display=f"Rejoins le monde RPG {Y}/rpg{YE}", children=[waypoint(qi, "inconnu/boussole_rpg", PORTAIL_RPG)])
qi.add("objective", "inconnu/obj_6_classe", quest=QI, criteria=[c(E, "==", 5)], display=f"Choisis ta classe {Y}/classe{YE}")
qi.add("objective", "inconnu/obj_7_donjon", quest=QI, criteria=[c(E, "==", 6)],
       display=f"Termine le donjon {ROCHISTE} {Y}/donjon{YE} <gray>(entrée gratuite)</gray>")
qi.add("objective", "inconnu/obj_8_bureau", quest=QI, criteria=[c(E, "==", 7)],
       display=f"Va au {Y}Bureau des citoyens{YE}", children=[waypoint(qi, "inconnu/boussole_bureau", WP_BUREAU)])
qi.add("objective", "inconnu/obj_9_ville", quest=QI, criteria=[c(E, "==", 8)],
       display=f"Rejoins une ville {Y}/agence{YE} ou {Y}/annuaire{YE}")
qi.add("objective", "inconnu/obj_10_bureau", quest=QI, criteria=[c(E, "==", 9)],
       display=f"Retourne au {Y}Bureau des citoyens{YE}", children=[waypoint(qi, "inconnu/boussole_bureau_retour", WP_BUREAU)])

# Étape de la quête dans le titre du scoreboard de Flux (« Chapitre I (3/10) ») : pour chaque grade, son fact
# d'étape (compté depuis 0) et le nombre d'étapes, écrits dans plugins/Flux/scoreboard_counters.yml ({counter:quest_step}).
STEP_COUNTERS = [(["inconnu", "default"], E, 10)]

# ============================================================================================= quêtes Citoyen à Suprême


for r in ORDER[1:]:
    g = GRADE[r]
    nxt = ORDER[g] if g < len(ORDER) else None
    fr = Page(f"quete_{r}_fact", "fact", "static", chapter(r))
    q = Page(f"quete_{r}_quest", "quest", "manifest", chapter(r))
    q.add("quest", f"quete/{r}", displayName=QUEST_NAMES[r],
          activeCriteria=[c(F_GRADE, "==", g)], completedCriteria=[c(F_GRADE, ">", g)])
    ET = fr.add("permanent_fact", f"{r}/etape",
                comment=f"Sous-étape du grade {RANKS[r]['name']} (0 = la première ; la dernière = offrande et donjon).")
    kids = []
    # (clé, texte de l'objectif, critère de réussite, boussole, page d'étape à compléter)
    todo = []
    slots, before = job_slots(r), job_slots(ORDER[g - 2])
    if slots > before:
        todo.append((f"metier{slots}", f"Choisis un {slots}e métier {Y}/metiers{YE}", c(F_METIERS, ">=", slots), None))
    for key, text, trig in DECOUVERTES[r]:
        dec = fr.add("permanent_fact", f"{r}/decouverte_{key}", comment=f"Découverte du grade {RANKS[r]['name']} faite.")
        todo.append((key, text, c(dec, "==", 1), (dec, trig)))
    if r == "citoyen":
        # le magasin, comme en prod : découvrir, acheter, vendre (FluxShop dit ce que le joueur a fait depuis sa connexion)
        near = fr.add("value_placeholder", "citoyen/magasin_proche", comment="Près d'un coffre du magasin (FluxShop).",
                      placeholder="%fluxshop_player_near%", values={"true": 1})
        buy = fr.add("value_placeholder", "citoyen/magasin_achat", comment="A acheté au magasin depuis sa connexion.",
                     placeholder="%fluxshop_player_buy%", values={"true": 1})
        sell = fr.add("value_placeholder", "citoyen/magasin_vente", comment="A vendu au magasin depuis sa connexion.",
                      placeholder="%fluxshop_player_sell%", values={"true": 1})
        todo += [("magasin", f"Découvre le {Y}magasin{YE}", c(near, "==", 1), "boussole"),
                 ("acheter", "Achète un article au magasin", c(buy, "==", 1), "boussole"),
                 ("vendre", "Vends un article au magasin", c(sell, "==", 1), "boussole")]
    for n, (key, text, done, extra) in enumerate(todo):
        sp = Page(f"quete_{r}_step_{n + 1}", str(n + 1), "sequence", steps(r))
        if isinstance(extra, tuple):
            # découverte : retenue dès qu'elle est faite (même avant son tour), l'étape passe quand vient son tour
            dec, trig = extra
            act = sp.add("simple_action", f"{r}/{n + 1}_decouverte_{key}", criteria=[c(F_GRADE, ">=", g), c(dec, "==", 0)],
                         modifiers=[m(dec, "=", 1)])
            if trig[0] == "cmd":
                sp.add("on_detect_command_ran", f"{r}/{n + 1}_decouverte_{key}_commande", command=trig[1], cancel=False, triggers=[act])
            else:
                sp.add("change_world_event", f"{r}/{n + 1}_decouverte_{key}_monde", toWorld=opt(trig[1]), cancel=False, triggers=[act])
        advance = sp.add("simple_action", f"{r}/{n + 1}_{key}_faite", criteria=[c(F_GRADE, "==", g), c(ET, "==", n), done],
                         modifiers=[m(ET, "=", n + 1)], triggers=[S_ETAPE])
        auto(q, f"{r}/declencheur_etape_{n + 1}", [c(ET, "==", n), done], advance, kids)
        children = [waypoint(q, f"{r}/boussole_{key}", MAGASIN)] if extra == "boussole" else []
        task(q, f"{r}/obj_{key}", QUEST[r], [c(ET, "==", n)], [done], text, 30, children=children)
    # dernière sous-étape : l'offrande et le donjon ensemble, puis le grade suivant
    STEP_COUNTERS.append(([r], ET, len(todo) + (1 if (r in F_OFFRANDE or r in DONJONS) else 0)))
    last = [c(ET, ">=", len(todo))]
    gate = list(last)
    if r in F_OFFRANDE:
        task(q, f"{r}/obj_offrande", QUEST[r], last, [c(F_OFFRANDE[r], "==", 1)],
             f"<#E06C9F>/offrande</#E06C9F>\u00a0<white>(%offrandes_{r}_progress%)</white>", 25)
        gate.append(c(F_OFFRANDE[r], "==", 1))
    if r in DONJONS:
        task(q, f"{r}/obj_donjon", QUEST[r], last, [c(F_DONJON[r], "==", 1)],
             f"Termine le donjon {DONJONS[r][1]} {Y}/donjon{YE}", 24)
        gate.append(c(F_DONJON[r], "==", 1))
    if nxt and len(gate) > 1:
        rw = Page(f"quete_{r}_reward", "reward", "sequence", chapter(r))
        up = rankup(rw, nxt, [c(F_GRADE, "==", g), *gate])
        auto(q, f"{r}/declencheur_grade_suivant", gate, up, kids)
    if kids:
        q.add("criteria_audience", f"{r}/grade_{r}", criteria=[c(F_GRADE, "==", g)], inverted=False, children=kids)

COUNTERS_FILE = PLUGINS / "Flux" / "scoreboard_counters.yml"
_lines = ["# Écrit par scripts/typewriter/gen_progression.py, ne pas modifier à la main : étape de la quête du grade",
          "# ({counter:quest_step} dans scoreboard.yml), fact d'étape de Typewriter + 1 sur le nombre d'étapes du grade.",
          "counters:", "  quest_step:", "    text: \"({n}/{total})\"", "    entries:"]
for groups, fact, total in STEP_COUNTERS:
    for grp in groups:
        _lines.append(f"      - {{requires: \"%luckperms_current_group_on_track_standard% == {grp}\", value: \"%typewriter_{fact}%\", add: 1, total: {total}}}")
COUNTERS_FILE.write_text("\n".join(_lines) + "\n", encoding="utf-8")
print(f"Étapes par grade : {COUNTERS_FILE}")

# Le scoreboard est celui de Flux (plugins/Flux/scoreboard.yml, 2026-11-19) : il lit la quête suivie
# (%typewriter_tracked_quest%), ses objectifs (%typewriter_tracked_objectives%) et la position du point de passage
# (%waypoint_x%... de l'extension CompassWaypoint). Typewriter n'a plus de page de scoreboard.

# ============================================================================================= écriture

pages_left = [p for p in PAGES_WRITTEN if p.entries]
written = {p.file for p in pages_left}
for folder in FOLDERS:
    # pages de ce script devenues vides (étape supprimée...) : retirées, sinon Typewriter garderait l'ancienne
    for f in list(folder.glob("quete_*.json")) + list(folder.glob("global_*.json")):
        if f.stem not in written:
            f.unlink()
            print(f"  supprimée (vide) : {folder.name}/{f.name}")
    for old in OLD_PAGES:
        if (folder / f"{old}.json").exists():
            (folder / f"{old}.json").unlink()
            print(f"  supprimée : {folder.name}/{old}.json")
print(f"Pages écrites dans {', '.join(str(f) for f in FOLDERS)} :")
for page in pages_left:
    page.write()

# contrôle : toute référence pointe sur une entrée existante, aucun identifiant en double
ids = {}
for f in (TW / "pages").glob("*.json"):
    for e in json.loads(f.read_text())["entries"]:
        if e["id"] in ids and ids[e["id"]] != f.name:
            print(f"IDENTIFIANT EN DOUBLE : {e['id']} ({ids[e['id']]} et {f.name})")
            sys.exit(1)
        ids[e["id"]] = f.name


def check(o, where):
    bad = []
    if isinstance(o, dict):
        for k, v in o.items():
            if k in ("triggers", "children", "completionTriggers", "lines") and isinstance(v, list):
                bad += [(where, k, x) for x in v if isinstance(x, str) and x and x not in ids]
            elif k in ("fact", "quest", "definition", "entity", "onEnter", "onExit", "speaker", "activity") and isinstance(v, str) and v and v not in ids:
                bad.append((where, k, v))
            bad += check(v, where)
    elif isinstance(o, list):
        for x in o:
            bad += check(x, where)
    return bad


problems = []
for page in pages_left:
    for e in page.entries:
        problems += check({k: v for k, v in e.items() if k != "id"}, e["name"])
if problems:
    print("RÉFÉRENCES CASSÉES :")
    for p in problems:
        print("  ", p)
    sys.exit(1)
print("Références vérifiées.")
