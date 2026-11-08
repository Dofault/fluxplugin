#!/usr/bin/env python3
"""Converts a dungeon of an EliteMobs content pack into FluxDungeons files.

Usage:
  python3 -I import_elitemobs.py <pack folder> <world id> <plugin folder> [--level N] [--translations file.yml]

  <pack folder>   unzipped pack (content_packages/, custombosses/, customitems/, powers/, world_blueprints/)
  <world id>      dungeon world of the pack, e.g. em_id_the_mines
  <plugin folder> plugins/FluxDungeons of the server: writes dungeons/<id>.yml, mobs/<id>.yml, items/<id>.yml
  --level         dungeon level: the level its monsters fight at (normalized combat, default 10)
  --translations  YAML {english text: translated text}; texts without a translation are listed in
                  <plugin folder>/untranslated/<id>.yml so they can be translated and the import run again.

What is kept: the map (world folder, used in place), spawn points of every monster, the start place, the bosses to
beat (assets/elitemobs_story_mode.yml, taken from EliteMobs' content packages), monster types, names, life and damage
multipliers, speed, equipment, phases, mounts, block states (doors), scripts (EliteScript, run by our engine), custom
powers (their scripts are merged into the monster's), built-in powers (names, see mob/Powers.java), loot of the
normal difficulty and the items it drops (vanilla enchantments only).
"""
import os
import re
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))

ENCHANTS = {
    "ARROW_FIRE": "flame", "ARROW_DAMAGE": "power", "ARROW_INFINITE": "infinity", "ARROW_KNOCKBACK": "punch",
    "DAMAGE_ALL": "sharpness", "DAMAGE_UNDEAD": "smite", "DAMAGE_ARTHROPODS": "bane_of_arthropods",
    "DURABILITY": "unbreaking", "DIG_SPEED": "efficiency", "LOOT_BONUS_MOBS": "looting", "LOOT_BONUS_BLOCKS": "fortune",
    "PROTECTION_ENVIRONMENTAL": "protection", "PROTECTION_FIRE": "fire_protection", "PROTECTION_FALL": "feather_falling",
    "PROTECTION_EXPLOSIONS": "blast_protection", "PROTECTION_PROJECTILE": "projectile_protection", "OXYGEN": "respiration",
    "WATER_WORKER": "aqua_affinity", "LUCK": "luck_of_the_sea", "FIRE_ASPECT": "fire_aspect", "KNOCKBACK": "knockback",
    "THORNS": "thorns", "SWEEPING_EDGE": "sweeping_edge", "MENDING": "mending", "DEPTH_STRIDER": "depth_strider",
    "FROST_WALKER": "frost_walker", "SOUL_SPEED": "soul_speed", "SWIFT_SNEAK": "swift_sneak", "LOYALTY": "loyalty",
    "IMPALING": "impaling", "RIPTIDE": "riptide", "CHANNELING": "channeling", "MULTISHOT": "multishot",
    "QUICK_CHARGE": "quick_charge", "PIERCING": "piercing", "DENSITY": "density", "BREACH": "breach",
    "WIND_BURST": "wind_burst", "SILK_TOUCH": "silk_touch", "UNBREAKING": "unbreaking",
}
VANILLA = set(ENCHANTS.values())

ENTITY_TYPES = {
    "ZOMBIE", "SKELETON", "SPIDER", "CAVE_SPIDER", "HUSK", "STRAY", "DROWNED", "WITHER_SKELETON", "ZOMBIFIED_PIGLIN",
    "PIGLIN", "PIGLIN_BRUTE", "ZOGLIN", "HOGLIN", "PILLAGER", "VINDICATOR", "EVOKER", "ILLUSIONER", "WITCH", "RAVAGER",
    "BLAZE", "SLIME", "MAGMA_CUBE", "ENDERMAN", "SILVERFISH", "ENDERMITE", "PHANTOM", "VEX", "GHAST", "WOLF",
    "IRON_GOLEM", "RABBIT", "BOGGED", "BREEZE", "CREAKING", "GUARDIAN", "ELDER_GUARDIAN", "SHULKER", "WARDEN",
    "HORSE", "SKELETON_HORSE", "ZOMBIE_HORSE", "SPIDER_JOCKEY", "POLAR_BEAR", "BEE", "CREEPER", "GIANT",
}

XP = {"BOSS": 150, "MINIBOSS": 50, "REINFORCEMENT": 4}

texts_seen = {}

# EliteMobs class loot: each player receives one item of his class among these families.
CLASS_FAMILIES = {
    "swords": ["warrior"], "axes": ["warrior"], "maces": ["warrior", "cleric"], "shields": ["warrior", "cleric"],
    "bows": ["ranger"], "crossbows": ["ranger"], "tridents": ["ranger"], "spears": ["ranger"],
    "staves": ["mage"], "wands": ["mage", "cleric"], "hoes": ["cleric"],
    "dps_helmets": ["ranger", "mage"], "dps_chestplates": ["ranger", "mage"], "dps_leggings": ["ranger", "mage"], "dps_boots": ["ranger", "mage"],
    "tank_helmets": ["warrior", "cleric"], "tank_chestplates": ["warrior", "cleric"], "tank_leggings": ["warrior", "cleric"], "tank_boots": ["warrior", "cleric"],
}


def loot_rank(b, boss_type):
    """EliteMobs' class loot rank (TRASH, MINIBOSS, BOSS): its chance of class loot (ClassLootCoverage.rank)."""
    rank = str(b.get("classLootRank", "AUTO")).upper()
    if rank in ("TRASH", "MINIBOSS", "BOSS"):
        return rank
    if boss_type in ("BOSS", "EVENT"):
        return "BOSS"
    if boss_type == "MINIBOSS":
        return "MINIBOSS"
    name = str(b.get("name", ""))
    if "$minibossLevel" in name:
        return "MINIBOSS"
    if "$bossLevel" in name or "$eventBossLevel" in name:
        return "BOSS"
    return "TRASH"


def item_id(dungeon_id, fname):
    name = fname[:-4] if fname.endswith(".yml") else fname
    return name if name.startswith(dungeon_id) else f"{dungeon_id}_{name}"


def class_family(fname):
    for fam in sorted(CLASS_FAMILIES, key=len, reverse=True):
        if re.search(rf"_{fam}_[0-9a-f]+\.yml$", fname):
            return fam
    return None


def tr(text, translations):
    """Text in the target language when translated, else the original (and noted as untranslated)."""
    if not isinstance(text, str) or not text.strip():
        return text
    if text in translations:
        return translations[text]
    if text in translated_values(translations):
        return text
    if re.search(r"[A-Za-z]{2}", re.sub(r"&.|\$\w+|%\w+%", "", text)):
        texts_seen[text] = None
    return text


_values = {}


def translated_values(translations):
    key = id(translations)
    if key not in _values:
        _values[key] = set(translations.values())
    return _values[key]


def walk_texts(obj, translations):
    """Translates the texts of a script (messages, titles) in place."""
    if isinstance(obj, dict):
        for k, v in list(obj.items()):
            if k in ("title", "subtitle") or (k == "sValue" and str(obj.get("action", "")).upper() in ("MESSAGE", "ACTION_BAR_MESSAGE", "BOSS_BAR_MESSAGE")):
                obj[k] = tr(v, translations)
            else:
                walk_texts(v, translations)
    elif isinstance(obj, list):
        for x in obj:
            walk_texts(x, translations)


def clean_name(name):
    name = re.sub(r"\$\w*[Ll]evel\s*", "", str(name or ""))
    name = re.sub(r"\[(\$difficulty|Normal)\]\s*", "", name)
    return re.sub(r"\s+", " ", name).strip()


def load(path):
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def main():
    args = sys.argv[1:]
    if len(args) < 3:
        print(__doc__)
        sys.exit(1)
    pack, world, plugin = args[0], args[1], args[2]
    level = 10
    translations = {}
    if "--level" in args:
        level = int(args[args.index("--level") + 1])
    file_id = None
    job = None
    if "--id" in args:
        file_id = args[args.index("--id") + 1]
    if "--job" in args:
        job = args[args.index("--job") + 1]
    if "--translations" in args:
        translations = load(args[args.index("--translations") + 1])

    packages = load(os.path.join(HERE, "elitemobs_story_mode.yml"))
    info = packages.get(world)
    if not info:
        sys.exit(f"{world} is not in assets/elitemobs_story_mode.yml")
    dungeon_id = world.replace("em_id_", "")

    powers_dir = os.path.join(pack, "powers")
    custom_powers = {}
    for root, _, files in os.walk(powers_dir):
        for f in files:
            if f.endswith(".yml"):
                custom_powers[f] = load(os.path.join(root, f))
    models = {os.path.splitext(f)[0] for f in os.listdir(os.path.join(pack, "models"))} if os.path.isdir(os.path.join(pack, "models")) else set()

    boss_dir = os.path.join(pack, "custombosses", world)
    objectives = [b.replace(".yml", "") for b in info["bosses"]]
    mobs, spawns, loot_files = {}, [], set()
    for f in sorted(os.listdir(boss_dir)):
        if not f.endswith(".yml"):
            continue
        mob_id = f[:-4]
        b = load(os.path.join(boss_dir, f))
        if b.get("isEnabled") is False:
            continue
        etype = str(b.get("entityType", "ZOMBIE")).upper()
        disguise = str(b.get("disguise", "")).upper()
        if disguise in ENTITY_TYPES:
            etype = disguise
        boss_type = str(b.get("bossType", "NORMAL")).upper()
        # normalized combat (FluxDungeons mob/Combat.java): EliteMobs' multipliers kept, counted in hits at the dungeon's level
        m = {
            "type": etype,
            "name": tr(clean_name(b.get("name", mob_id)), translations),
            "level": level,
            "health-multiplier": float(b.get("healthMultiplier", 1)),
            "damage-multiplier": float(b.get("damageMultiplier", 1)),
            "xp": XP.get(boss_type, 8),
            "rank": loot_rank(b, boss_type),
        }
        if b.get("movementSpeedAttribute") is not None:
            m["speed"] = float(b["movementSpeedAttribute"])
        if boss_type == "BOSS" or mob_id in objectives:
            m["boss"] = True
        if b.get("scale"):
            m["scale"] = float(b["scale"])
        if b.get("isBaby"):
            m["baby"] = True
        if b.get("frozen"):
            m["frozen"] = True
        if b.get("followDistance"):
            m["follow-range"] = float(b["followDistance"])
        if b.get("leashRadius"):
            m["leash"] = float(b["leashRadius"])
        model = str(b.get("customModel", "") or "")
        if model in models:
            m["model"] = model
        eq = {}
        for key, slot in (("mainHand", "hand"), ("offHand", "offhand"), ("helmet", "head"), ("chestplate", "chest"),
                          ("leggings", "legs"), ("boots", "feet")):
            v = b.get(key)
            if isinstance(v, str) and re.fullmatch(r"[A-Z_]+", v):
                eq[slot] = v
        if eq:
            m["equipment"] = eq
        scripts = dict(b.get("eliteScript") or {})
        builtin = []
        for p in b.get("powers") or []:
            name = p if isinstance(p, str) else (p.get("filename") if isinstance(p, dict) else None)
            if not name:
                continue
            if name in custom_powers:
                for sname, sbody in (custom_powers[name].get("eliteScript") or {}).items():
                    scripts.setdefault(sname, sbody)
            elif name.endswith(".yml"):
                builtin.append(name[:-4])
        if builtin:
            m["powers"] = builtin
        if scripts:
            walk_texts(scripts, translations)
            m["scripts"] = scripts
        phases = []
        for p in b.get("phases") or []:
            fname, _, below = str(p).rpartition(":")
            phases.append({"mob": fname.replace(".yml", ""), "below": float(below)})
        if phases:
            m["phases"] = phases
        if b.get("mountedEntity"):
            m["mount"] = str(b["mountedEntity"]).replace(".yml", "")
        loot = []
        for l in b.get("uniqueLootList") or []:
            if not isinstance(l, dict) or int(l.get("difficultyID", 0)) != 0:
                continue
            fname = str(l.get("filename", ""))
            loot_files.add(fname)
            entry = {"item": f"fluxdungeons:{item_id(dungeon_id, fname)}", "chance": float(l.get("chance", 0.1))}
            fam = class_family(fname)
            if fam:
                entry["group"] = "class"
                entry["classes"] = CLASS_FAMILIES[fam]
            loot.append(entry)
        if loot:
            m["loot"] = loot
        msgs = {}
        for key, out in (("deathMessages", "death"), ("onDamageMessages", "hit"), ("onDamagedMessages", "hurt")):
            if b.get(key):
                msgs[out] = [tr(x, translations) for x in b[key]]
        if msgs:
            m["messages"] = msgs
        blocks = {}
        if b.get("onSpawnBlockStates"):
            blocks["spawn"] = list(b["onSpawnBlockStates"])
        if b.get("onRemoveBlockStates"):
            blocks["remove"] = list(b["onRemoveBlockStates"])
        if blocks:
            m["blocks"] = blocks
        mobs[mob_id] = m
        for s in b.get("spawnLocations") or []:
            parts = str(s).split(":")[0].split(",")
            if len(parts) >= 4:
                at = [float(parts[1]), float(parts[2]), float(parts[3])]
                if len(parts) >= 5:
                    at.append(float(parts[4]))
                spawns.append({"mob": mob_id, "at": at})

    # items dropped (normal difficulty)
    items = {}
    item_root = os.path.join(pack, "customitems", world)
    item_paths = {}
    for root, _, files in os.walk(item_root):
        for f in files:
            item_paths[f] = os.path.join(root, f)
    for fname in sorted(loot_files):
        path = item_paths.get(fname)
        if not path:
            continue
        it = load(path)
        material = str(it.get("material", "PAPER")).upper()
        d = {"material": material, "name": tr(clean_name(it.get("name", fname)), translations)}
        lore = [tr(x, translations) for x in it.get("lore") or []]
        lore.append(tr("&7Dungeon gear, only usable in dungeons.", translations))
        d["lore"] = lore
        ench = {}
        for e in it.get("enchantments") or []:
            k, _, n = str(e).partition(",")
            key = ENCHANTS.get(k.upper(), k.lower())
            if key in VANILLA:
                ench[key] = int(n or 1)
        if ench:
            d["enchantments"] = ench
        items[item_id(dungeon_id, fname)] = d

    start = info["start"].split(",")
    dungeon = {
        "name": tr(re.sub(r"&.\[[^\]]*\]\s*", "", info["name"]).strip(), translations),
        "icon": "IRON_PICKAXE",
        "description": [],
        "players": {"min": 1, "max": 5},
        "recommended-level": level,
        "time-limit": "40m",
        "lives": 5,
        "reward": {"minutes": 15, "xp": 400},
        "loot": [],
        "map": {
            "folder": f"maps/story-mode/world_blueprints/{world}/{world}",
            "start": [float(x) for x in start[1:6]],
            "objectives": objectives,
            "spawns": spawns,
        },
    }
    for sub in ("dungeons", "mobs", "items", "untranslated"):
        os.makedirs(os.path.join(plugin, sub), exist_ok=True)
    header = f"# Imported from EliteMobs ({world}) by FluxDungeons/assets/import_elitemobs.py: run the import again rather than editing.\n"
    out_id = file_id or dungeon_id
    if job:
        dungeon["job"] = job
    dungeon_path = os.path.join(plugin, "dungeons", f"{out_id}.yml")
    if os.path.exists(dungeon_path):
        # keep what the staff tuned (name, rewards, lives...), refresh the map
        old = load(dungeon_path)
        for k in ("name", "icon", "description", "players", "recommended-level", "time-limit", "lives", "reward", "loot"):
            if k in old:
                dungeon[k] = old[k]
    for path, data in ((dungeon_path, dungeon),
                       (os.path.join(plugin, "mobs", f"{dungeon_id}.yml"), mobs),
                       (os.path.join(plugin, "items", f"{dungeon_id}.yml"), items)):
        if path != dungeon_path and file_id and os.path.exists(os.path.join(plugin, "dungeons", f"{dungeon_id}.yml")) and dungeon_id != file_id:
            os.remove(os.path.join(plugin, "dungeons", f"{dungeon_id}.yml"))
        with open(path, "w", encoding="utf-8") as out:
            out.write(header)
            yaml.safe_dump(data, out, allow_unicode=True, sort_keys=False, width=160)
    with open(os.path.join(plugin, "untranslated", f"{dungeon_id}.yml"), "w", encoding="utf-8") as out:
        yaml.safe_dump({k: "" for k in texts_seen}, out, allow_unicode=True, sort_keys=False, width=200)
    print(f"{dungeon_id}: {len(mobs)} monsters, {len(spawns)} spawn points, {len(objectives)} objectives, "
          f"{len(items)} items, {len(texts_seen)} untranslated texts")


if __name__ == "__main__":
    main()
