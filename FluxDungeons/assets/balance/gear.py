#!/usr/bin/env python3
"""The rpg armour of the dungeon tower (2026-11-16): one leather set per floor, dyed in the colour of the floor's job
(tower.DYES), written into equipment.yml. Change this script and run it again, never the armour in equipment.yml.

    python3 gear.py <equipment.yml> [en]      (Labs: plugins/FluxDungeons/rpg/equipment.yml in French;
                                               the jar's src/main/resources/rpg/equipment.yml with "en")

Everything that is not dungeon armour (rarities, class weapons) is kept as it is in the file; the armour and the sets
are written again from here.

LEVELS. A set requires the level of its floor, at most 50 (the rpg's last level): floors 1 to 5 sets of levels 10 to
50, floors 6 to 10 all of level 50.

FIVE ORIENTATIONS (owner's idea): the same five, in the same order, on floors 1-5 and again on floors 6-10, from the
least wanted to the most wanted (the best one comes last on each half of the tower):
    1 Rempart    defenses, tenacity            (floors 1 and 6)
    2 Vitalité   life, its regeneration, heals (floors 2 and 7)
    3 Arcanes    mana, its regeneration, cost  (floors 3 and 8)
    4 Puissance  magic power, penetration, crit (floors 4 and 9)
    5 Célérité   movement and attack speed, dodge (floors 5 and 10)
Every set shares out the same budget as the others of its level (the reference gear of balance.yml, average of the
three elemental classes): an orientation only moves weight from one stat to another (multipliers summing to 5 on the
five core stats) and adds its own secondary stats. Floors 6-10 are the upper version of their orientation: rarer
(epic on 6-7, legendary on 8-10) and stronger (x 1.10 to 1.30 the level 50 budget, secondary stats and set bonuses
x 1.4 more), since the level stops at 50 and the next floors still have to be worth farming.
PIECES: helmet 20 %, chestplate 35 %, leggings 28 %, boots 17 % of a set. Sets: a bonus at 2 and at 4 pieces.
Usable by every class (all three elemental classes fight with magic power).
"""
import os
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from tower import DYES, MAX_LEVEL, TOWER  # noqa: E402

CORE = ("magic_power", "max_health", "physical_defense", "magic_defense", "max_mana")
PIECES = (("helmet", 0.20), ("chestplate", 0.35), ("leggings", 0.28), ("boots", 0.17))
ELEMENTAL = ("cryomancer", "pyromancer", "druid")
UPPER = {6: ("epic", 1.10), 7: ("epic", 1.15), 8: ("legendary", 1.20), 9: ("legendary", 1.25), 10: ("legendary", 1.30)}
UPPER_EXTRA = 1.4

# orientation: (core multipliers in CORE order, secondary stats of a full set at boost 1 (L = its level), bonus at 2
# pieces, bonus at 4 pieces); a value may be a function of the level L and the core budget b of the level
ORIENTATIONS = [
    ("rempart", (0.6, 1.1, 1.5, 1.4, 0.4),
     {"tenacity": 0.12, "damage_reduction": 0.03},
     {"physical_defense": lambda L, b: 0.3 * b["physical_defense"], "magic_defense": lambda L, b: 0.2 * b["magic_defense"]},
     {"damage_reduction": 0.04, "tenacity": 0.10}),
    ("vitalite", (0.7, 1.6, 1.0, 1.0, 0.7),
     {"health_regen": lambda L, b: 0.5 + 0.08 * L, "healing_power": 0.08},
     {"max_health": lambda L, b: 0.15 * b["max_health"]},
     {"health_regen": lambda L, b: 0.3 + 0.05 * L, "lifesteal": 0.03}),
    ("arcanes", (0.9, 0.9, 0.8, 0.8, 1.6),
     {"mana_regen": lambda L, b: 0.5 + 0.04 * L, "cost_reduction": 0.06},
     {"max_mana": lambda L, b: 0.5 * b["max_mana"], "mana_regen": lambda L, b: 0.5 + 0.02 * L},
     {"cost_reduction": 0.06, "manasteal": 0.02}),
    ("puissance", (1.6, 0.9, 0.8, 0.8, 0.9),
     {"magic_penetration": 0.08, "crit_chance": 0.04},
     {"magic_power": lambda L, b: 0.2 * b["magic_power"]},
     {"crit_damage": 0.2, "magic_penetration": 0.06}),
    ("celerite", (1.2, 1.0, 0.9, 0.9, 1.0),
     {"movement_speed": 0.10, "attack_speed": 0.08, "dodge": 0.03},
     {"movement_speed": 0.04},
     {"attack_speed": 0.06, "dodge": 0.03}),
]
FLAT = {"magic_power", "max_health", "physical_defense", "magic_defense", "max_mana", "health_regen", "mana_regen"}

FR = {"pieces": {"helmet": "Casque", "chestplate": "Plastron", "leggings": "Jambières", "boots": "Bottes"},
      "orientations": {"rempart": "Rempart", "vitalite": "Vitalité", "arcanes": "Arcanes", "puissance": "Puissance",
                       "celerite": "Célérité"},
      "desc": {"rempart": "Défenses avant tout.", "vitalite": "Beaucoup de vie, qui revient vite.",
               "arcanes": "Du mana, et des sorts moins chers.", "puissance": "Des sorts qui frappent plus fort.",
               "celerite": "Plus vite : courir, frapper, esquiver."},
      "upper": "Version supérieure, au-delà du niveau 50."}
EN = {"pieces": {"helmet": "Helmet", "chestplate": "Chestplate", "leggings": "Leggings", "boots": "Boots"},
      "orientations": {"rempart": "Rampart", "vitalite": "Vitality", "arcanes": "Arcana", "puissance": "Power",
                       "celerite": "Swiftness"},
      "desc": {"rempart": "Defenses first.", "vitalite": "Plenty of life, quick to come back.",
               "arcanes": "Mana, and cheaper spells.", "puissance": "Spells that hit harder.",
               "celerite": "Faster: running, hitting, dodging."},
      "upper": "Upper version, beyond level 50."}
JOB_NAMES = {"rochiste": "Rochiste", "cultivateur": "Cultivateur", "sylviculteur": "Bûcheron", "deblayeur": "Déblayeur",
             "mineur": "Mineur", "herboriste": "Herboriste", "fondeur": "Fondeur", "defenseur": "Défenseur",
             "pecheur": "Pêcheur", "enchanteur": "Enchanteur"}


def of(job, lang):
    """« du Rochiste », « de l'Herboriste » / "of the Rochiste"."""
    name = JOB_NAMES[job]
    if lang == "en":
        return f"of the {name}"
    return f"de l'{name}" if name[0] in "AEIOUÉÈHaeiou" else f"du {name}"


def budget(balance, level):
    """Core stats of a full rare set of that level: the reference gear, average of the elemental classes."""
    ref = balance["reference-gear"]
    out = {}
    for stat in CORE:
        vals = []
        for c in ELEMENTAL:
            cur = ref[c].get(stat)
            vals.append(0 if cur is None else cur["base"] + cur.get("per-level", 0) * (level - 1))
        out[stat] = sum(vals) / len(vals)
    return out


def value(v, L, b):
    return v(L, b) if callable(v) else v


def rnd(stat, v):
    return int(round(v)) if stat in FLAT else round(round(v / 0.005) * 0.005, 3)


def build(balance, lang):
    t = FR if lang != "en" else EN
    items, sets = {}, {}
    for f, (dungeon, job, level, *_rest) in sorted(TOWER.items()):
        L = min(level, MAX_LEVEL)
        o = ORIENTATIONS[(f - 1) % 5]
        oid, mults, extra, two, four = o
        rarity, boost = UPPER.get(f, ("rare", 1.0))
        xb = boost * (UPPER_EXTRA if f > 5 else 1.0)
        b = budget(balance, L)
        full = {stat: b[stat] * m * boost for stat, m in zip(CORE, mults)}
        for stat, v in extra.items():
            full[stat] = value(v, L, b) * xb
        set_name = f"{t['orientations'][oid]} {of(job, lang)}"
        sets[dungeon] = {"name": set_name, "bonuses": {
            2: {k: rnd(k, value(v, L, b) * (boost if k in FLAT else xb)) for k, v in two.items()},
            4: {k: rnd(k, value(v, L, b) * xb) for k, v in four.items()}}}
        for piece, share in PIECES:
            stats = {k: rnd(k, v * share) for k, v in full.items()}
            stats = {k: v for k, v in stats.items() if v}
            desc = [t["desc"][oid]] + ([t["upper"]] if f > 5 else [])
            items[f"{dungeon}_{piece}"] = {
                "slot": piece, "level": L, "rarity": rarity, "set": dungeon, "stats": stats, "description": desc,
                "look": {"material": f"LEATHER_{piece.upper()}", "name": f"{t['pieces'][piece]} {of(job, lang)}",
                         "color": DYES[job]}}
    return items, sets


def main():
    path = sys.argv[1]
    lang = sys.argv[2] if len(sys.argv) > 2 else "fr"
    balance_path = os.path.join(os.path.dirname(path), "balance.yml")
    balance = yaml.safe_load(open(balance_path, encoding="utf-8"))
    old = yaml.safe_load(open(path, encoding="utf-8"))
    kept = {k: v for k, v in old["items"].items() if v.get("slot") == "weapon"}
    items, sets = build(balance, lang)
    head = ("# Objets de l'univers RPG (docs/technique/rpg.md §7). Armures des donjons écrites par\n"
            "# plugins/fluxplugin/FluxDungeons/assets/balance/gear.py (tour des donjons, 2026-11-16) : relancer le script\n"
            "# plutôt que de les modifier ici. Armes de classe : gardées telles quelles par le script.\n") if lang != "en" else \
           ("# Items of the rpg universe (docs/technique/rpg.md §7). Dungeon armour written by\n"
            "# FluxDungeons/assets/balance/gear.py (the dungeon tower, 2026-11-16): run it again rather than editing it here.\n"
            "# Class weapons are kept as they are by the script.\n")
    head += ("# slot: weapon, helmet, chestplate, leggings, boots. classes: who may use it (empty = everyone). level: required.\n"
             "# Stats are added as they are (stats.yml ids). look: material, name, model, color (dye of a leather piece).\n\n")
    data = {"rarities": old["rarities"], "items": {**kept, **items}, "sets": sets}
    text = yaml.safe_dump(data, allow_unicode=True, sort_keys=False, width=120, default_flow_style=None)
    open(path, "w", encoding="utf-8").write(head + text)
    print(f"{len(items)} pieces, {len(sets)} sets -> {path}")
    for f, (dungeon, *_r) in sorted(TOWER.items()):
        s = sets[dungeon]
        tot = {}
        for piece, _ in PIECES:
            for k, v in items[f"{dungeon}_{piece}"]["stats"].items():
                tot[k] = round(tot.get(k, 0) + v, 3)
        print(f"  {f:2d} {dungeon:13s} lvl {items[dungeon + '_helmet']['level']:2d} {items[dungeon + '_helmet']['rarity']:9s} "
              f"{s['name']}: {tot} | 2: {s['bonuses'][2]} 4: {s['bonuses'][4]}")


if __name__ == "__main__":
    main()
