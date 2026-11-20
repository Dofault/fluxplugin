#!/usr/bin/env python3
"""
Validateur de la configuration EpicAchievements 2.10.0 (Paper 1.21.11).

Confronte chaque succes aux registres REELS du serveur plutot qu'a une liste ecrite a la main :
  - taches et conditions effectivement enregistrees par le plugin (issues du jar decompile) ;
  - compatibilite condition/tache (une condition dont la donnee n'est pas publiee est ignoree
    silencieusement par le plugin, ce qui fait compter le succes sans filtre) ;
  - existence des blocs et des items dans net.minecraft.world.level.block.Blocks et
    net.minecraft.world.item.Items extraits du jar serveur, en distinguant bloc et item ;
  - noms exacts des operateurs PLACEHOLDER, types de recompense, croissance des paliers,
    dossiers sans categorie, identifiants dupliques, motifs a joker sans correspondance.

Voir docs/technique/epicachievements.md.
Usage : python3 scripts/validate_epicachievements.py
"""
import sys, os, subprocess, glob, tempfile

try:
    import yaml
except ImportError:
    sys.exit("PyYAML manquant : pip install pyyaml")

SERVER = "/home/influencraft/serveurs/03_semirp"
BASE   = f"{SERVER}/plugins/EpicAchievements"
CACHE  = os.path.join(tempfile.gettempdir(), "ea_registries")

def _javap(cls, jar, tag):
    """Extrait les constantes publiques statiques d'une classe, avec cache disque.
    Un repertoire de travail par jar : le jar serveur ne contient pas org.bukkit.entity."""
    path = os.path.join(CACHE, f"{cls}.txt")
    if os.path.exists(path):
        return set(open(path).read().split())
    work = os.path.join(CACHE, "cp_" + tag)
    if not os.path.isdir(work):
        os.makedirs(work, exist_ok=True)
        subprocess.run(["unzip", "-o", "-q", jar, "-d", work], check=True)
    out = subprocess.run(["javap", "-cp", work, cls], capture_output=True, text=True).stdout
    names = {ln.strip()[:-1].split()[-1] for ln in out.splitlines()
             if ln.strip().startswith("public static final") and ln.strip().endswith(";")}
    names = {n for n in names if n.replace("_", "").isalnum() and n.isupper()}
    if not names:
        sys.exit(f"extraction vide pour {cls} : verifier {jar}")
    os.makedirs(CACHE, exist_ok=True)
    open(path, "w").write("\n".join(sorted(names)))
    return names

def _mc_version():
    """Version reellement lancee, lue dans version_history.json. Indispensable :
    plusieurs versions coexistent sous versions/ et libraries/, et un tri
    alphabetique choisirait 1.21.8 avant 1.21.11 (le '8' passe avant le '1')."""
    import json, re
    with open(f"{SERVER}/version_history.json") as fh:
        cur = json.load(fh)["currentVersion"]
    m = re.search(r"MC: ([0-9.]+)", cur)
    if not m:
        sys.exit("version introuvable dans version_history.json : " + cur)
    return m.group(1)

def registries():
    mc = _mc_version()
    sv  = glob.glob(f"{SERVER}/versions/{mc}/paper-*.jar")
    api = glob.glob(f"{SERVER}/libraries/io/papermc/paper/paper-api/{mc}-*/paper-api-*.jar")
    if not sv or not api:
        sys.exit(f"jars introuvables pour la version {mc} sous {SERVER}")
    print(f"Registres : Paper {mc}")
    sv, api = sv[0], api[0]
    return (_javap("org.bukkit.Material", api, "api"),
            _javap("org.bukkit.entity.EntityType", api, "api"),
            _javap("net.minecraft.world.item.Items", sv, "srv"),
            _javap("net.minecraft.world.level.block.Blocks", sv, "srv"))

# XMAT : noms Bukkit/XMaterial, seule reference qui fasse foi pour la config.
# NMS_ITEMS / NMS_BLOCKS : uniquement pour distinguer bloc et item. Les noms NMS
# peuvent differer des noms Bukkit apres un renommage (NMS CHAIN vs Bukkit IRON_CHAIN) :
# quand un nom Bukkit n'apparait dans aucun des deux registres NMS, la distinction
# bloc/item n'est pas verifiable et le controle est ignore plutot que faux.
XMAT, ENT, NMS_ITEMS, NMS_BLOCKS = registries()

def match(pattern, pool):
    """Reproduit TextUtil.matchString : * en tete = endsWith, * en fin = startsWith,
    * des deux cotes = contains, sinon egalite stricte. Insensible a la casse."""
    p = pattern.lower()
    if p.startswith("*") and p.endswith("*"): f = lambda t: p[1:-1] in t
    elif p.startswith("*"):                   f = lambda t: t.endswith(p[1:])
    elif p.endswith("*"):                     f = lambda t: t.startswith(p[:-1])
    else:                                     f = lambda t: t == p
    return {x for x in pool if f(x.lower())}

# Tâches réellement enregistrées (TaskRegistry) -> données publiées (hors PLAYER/USER, toujours présents)
TASKS = {
 "ADVANCEMENT": set(), "BREAK": {"BLOCK"}, "BREED": {"ENTITY"}, "BREW": {"ITEM","POTION_TYPE"},
 "CHAT": set(), "COMMAND": set(), "CONSUME": {"ITEM"}, "CRAFT": {"ITEM"},
 "DAMAGE": {"ENTITY","DAMAGE_CAUSE"}, "DEATH": {"DAMAGE_CAUSE"}, "DROP": {"ITEM"},
 "ENCHANT": {"ITEM"}, "ENTER_VEHICLE": {"ENTITY"}, "EXIT_VEHICLE": {"ENTITY"},
 "EXPERIENCE": set(), "FISH": {"ITEM"}, "HARVEST": {"BLOCK"}, "INTERACT": {"BLOCK"},
 "JUMP": set(), "KILL": {"ENTITY"}, "KILL_TOTEM": set(), "MILK": set(),
 "PICKUP": {"ITEM"}, "PLACE": {"BLOCK"}, "PLAYTIME": set(), "RAID": set(),
 "RESURRECT": set(), "SHEAR": {"ENTITY"}, "SHOOT": set(), "SLEEP": set(),
 "SMELT": {"ITEM"}, "TAME": {"ENTITY"}, "TRADE": {"ITEM"}, "WALK": set(), "NONE": set(),
 # hooks présents sur ce serveur
 "PLACEHOLDER": set(), "CUSTOM_FISHING": {"ITEM"},
}
NO_MULTI_TIER = {"COMMAND", "ADVANCEMENT"}
NEEDS_SETTINGS = {"COMMAND": "command", "ADVANCEMENT": "advancement", "PLACEHOLDER": "placeholder"}

# Conditions enregistrées -> (donnée requise, clé de liste attendue)
CONDS = {
 "blocks": ("BLOCK","blocks"), "crop_age": ("BLOCK","age"), "damage_causes": ("DAMAGE_CAUSE","causes"),
 "entities": ("ENTITY","entities"), "is_hostile": ("ENTITY","value"), "items": ("ITEM","items"),
 "potion_types": ("POTION_TYPE","types"), "biomes": ("PLAYER","biomes"), "is_flying": ("PLAYER","value"),
 "game_modes": ("PLAYER","game-modes"), "has_equipped": ("PLAYER","items"), "has_held": ("PLAYER","items"),
 "has_item": ("PLAYER","items"), "has_inventory_open": ("PLAYER","value"), "riding_entity": ("PLAYER","entities"),
 "is_sneaking": ("PLAYER","value"), "is_sprinting": ("PLAYER","value"), "worlds": ("PLAYER","worlds"),
 "balance": ("PLAYER","balance"), "placeholder": ("PLAYER","placeholder"), "regions": ("PLAYER","regions"),
 "custom_fishing_loot": ("custom_fishing_loot","loot"),
}
REWARDS = {"points","broadcast","command","experience","experience_levels","message","item","title",
           "action_bar","vault","player_points"}   # ecobits absent (pas d'EcoBits)
OPS = {"EQUALS","EQUALS_IGNORE_CASE","NOT_EQUALS","NOT_EQUALS_IGNORE_CASE","CONTAINS","STARTS_WITH",
       "ENDS_WITH","REGEX","LESS_THAN","LESS_OR_EQUAL_THAN","GREATER_THAN","GREATER_OR_EQUAL_THAN"}
# HARVEST ne se déclenche QUE pour ces blocs (CaveVines + SweetBerryBushBlock, vérifié dans paper-1.21.11.jar)
HARVEST_BLOCKS = {"SWEET_BERRY_BUSH","CAVE_VINES","CAVE_VINES_PLANT","GLOW_BERRIES"}

errors, warns = [], []
def err(f, a, m): errors.append(f"{f} :: {a} :: {m}")
def warn(f, a, m): warns.append(f"{f} :: {a} :: {m}")

cats = yaml.safe_load(open(f"{BASE}/categories.yml"))
cat_ids = set(cats.keys())
slots = {}
for cid, c in cats.items():
    s = (c.get("item") or {}).get("slot")
    if s in slots: err("categories.yml", cid, f"slot {s} déjà pris par '{slots[s]}'")
    slots[s] = cid

seen_ids, all_ach = {}, {}
folders = sorted(os.listdir(f"{BASE}/achievements"))
for folder in folders:
    fp = f"{BASE}/achievements/{folder}"
    if not os.path.isdir(fp): continue
    if folder not in cat_ids:
        err("categories.yml", folder, f"dossier de succès '{folder}' SANS catégorie -> AUCUN de ses succès n'est chargé")
    for path in sorted(glob.glob(f"{fp}/*.yml")):
        rel = os.path.relpath(path, BASE)
        try: doc = yaml.safe_load(open(path)) or {}
        except Exception as e:
            err(rel, "-", f"YAML illisible: {e}"); continue
        for aid, a in doc.items():
            if not isinstance(a, dict): continue
            all_ach[aid] = (rel, a)
            if aid in seen_ids: err(rel, aid, f"ID DUPLIQUÉ (déjà dans {seen_ids[aid]}) -> non chargé")
            seen_ids[aid] = rel

for aid, (rel, a) in all_ach.items():
    t = a.get("type")
    if t not in TASKS:
        err(rel, aid, f"type de tâche inconnu '{t}'"); continue
    provided = TASKS[t] | {"PLAYER","USER"}
    if not a.get("name"): warn(rel, aid, "pas de 'name'")
    tiers = a.get("tiers")
    if not isinstance(tiers, dict) or not tiers:
        err(rel, aid, "'tiers' manquant ou vide -> NullPointerException au chargement"); continue
    if t in NO_MULTI_TIER and len(tiers) > 1:
        err(rel, aid, f"{t} ne supporte pas plusieurs paliers ({len(tiers)})")
    if t in NEEDS_SETTINGS:
        st = a.get("settings")
        if not isinstance(st, dict) or NEEDS_SETTINGS[t] not in st:
            err(rel, aid, f"{t} exige settings.{NEEDS_SETTINGS[t]}")
        elif t == "PLACEHOLDER":
            if "operator" in st or "value" in st:
                if "operator" not in st or "value" not in st:
                    err(rel, aid, "PLACEHOLDER: 'operator' et 'value' doivent être fournis ensemble")
                elif str(st["operator"]).upper() not in OPS:
                    err(rel, aid, f"opérateur invalide '{st['operator']}' -> le succès ne se débloque JAMAIS "
                                  f"(valides: GREATER_OR_EQUAL_THAN, LESS_OR_EQUAL_THAN, GREATER_THAN, LESS_THAN, EQUALS...)")
                if len(tiers) > 1:
                    err(rel, aid, "PLACEHOLDER avec operator/value ne supporte qu'UN palier")
            else:
                ph = str(st.get("placeholder", ""))
                if "_one_cm" in ph and "/100" not in ph:
                    first = min((tiers[k] or {}).get("required-amount", 1)
                                for k in tiers)
                    if isinstance(first, int) and first < 5000:
                        warn(rel, aid, f"PLACEHOLDER sur '{ph}': statistique en CENTIMETRES non divisee "
                                       f"(1 bloc = 100) et premier palier a {first} (= {first/100:g} blocs) -> "
                                       f"utiliser '%math_0:floor_{{<stat>}}/100%' et des seuils en blocs")
    prev = 0
    for k in sorted(tiers.keys(), key=lambda x: int(x)):
        tr = tiers[k] or {}
        ra = tr.get("required-amount", 1)
        if not isinstance(ra, int) or ra < 1:
            err(rel, aid, f"palier {k}: required-amount invalide ({ra})")
        elif ra <= prev:
            err(rel, aid, f"palier {k}: required-amount {ra} <= palier précédent {prev} -> paliers débloqués ensemble")
        prev = max(prev, ra if isinstance(ra, int) else 0)
        for r in (tr.get("rewards") or []):
            rt = (r or {}).get("type")
            if rt not in REWARDS: err(rel, aid, f"palier {k}: type de récompense inconnu '{rt}'")
    for req in (a.get("requirements") or []):
        rid = str(req).split(":")[0].strip()
        if rid not in all_ach: warn(rel, aid, f"requirement '{rid}' ne correspond à aucun succès")
    for c in (a.get("conditions") or []):
        ct = (c or {}).get("type")
        if ct not in CONDS:
            err(rel, aid, f"condition inconnue '{ct}'"); continue
        need, key = CONDS[ct]
        if need not in provided:
            err(rel, aid, f"condition '{ct}' inapplicable à la tâche {t} (elle exige {need}) -> condition IGNORÉE")
        if key not in c:
            err(rel, aid, f"condition '{ct}': clé '{key}' manquante")
        vals = c.get(key)
        if ct in ("blocks","items","has_held","has_item","has_equipped") and isinstance(vals, list):
            pool  = NMS_BLOCKS if ct == "blocks" else NMS_ITEMS
            other = NMS_ITEMS  if ct == "blocks" else NMS_BLOCKS
            kind = "un BLOC" if ct == "blocks" else "un ITEM"
            if len(vals) != len(set(map(str, vals))):
                dup = [x for x in set(map(str,vals)) if list(map(str,vals)).count(x) > 1]
                warn(rel, aid, f"condition '{ct}': doublons {dup}")
            for v in vals:
                v = str(v)
                if ":" in v: continue
                if "*" in v:
                    if not match(v, XMAT):
                        err(rel, aid, f"condition '{ct}': le motif '{v}' ne correspond a AUCUN bloc/item ({kind}) en 1.21.11")
                    continue
                V = v.upper()
                if V not in XMAT:
                    err(rel, aid, f"condition '{ct}': '{v}' absent de XMaterial (le plugin ne pourra pas le nommer)")
                elif V not in pool and V in other:
                    hint = (" (il existe comme un ITEM)" if ct == "blocks" else " (il existe comme un BLOC)") if V in other else ""
                    err(rel, aid, f"condition '{ct}': '{v}' n'est pas {kind} en 1.21.11{hint}")
        if ct in ("entities","riding_entity") and isinstance(vals, list):
            for v in vals:
                v = str(v)
                if ":" in v or "*" in v: continue
                if v.upper() not in ENT:
                    err(rel, aid, f"condition '{ct}': entité '{v}' inexistante en 1.21.11")
    if t == "INTERACT":
        kinds = {(c or {}).get("type") for c in (a.get("conditions") or [])}
        if "has_held" not in kinds:
            warn(rel, aid, "INTERACT sans condition 'has_held' : un clic droit qui ne consomme pas "
                           "l'interaction est rejoue par Bukkit pour la seconde main -> comptage double")
    if t == "HARVEST":
        bl = []
        for c in (a.get("conditions") or []):
            if (c or {}).get("type") == "blocks": bl += [str(x).upper() for x in (c.get("blocks") or [])]
        bad = [b for b in bl if b not in HARVEST_BLOCKS]
        if bad or not bl:
            err(rel, aid, f"HARVEST ne se déclenche QUE sur baies douces / lianes luisantes "
                          f"(PlayerHarvestBlockEvent n'est appelé que par SweetBerryBushBlock et CaveVines en 1.21.11); "
                          f"blocs configurés: {bl or 'aucun'} -> succès INDÉBLOCABLE")

print(f"Succès analysés : {len(all_ach)}   Catégories : {len(cat_ids)}")
print(f"\n===== ERREURS ({len(errors)}) =====")
for e in errors: print("  ✗", e)
print(f"\n===== AVERTISSEMENTS ({len(warns)}) =====")
for w in warns: print("  !", w)

sys.exit(1 if errors else 0)
