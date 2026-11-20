#!/usr/bin/env python3
"""Convertit le BattlePass (AdvancedPlugins) du SemiRP en configuration EcoBattlePass.

Sources lues (jamais modifiées) : serveurs/03_semirp/plugins/BattlePass/
Sortie : <scratchpad>/out/EcoBattlepass/
"""
import os
import re
import shutil
import sys

import yaml

SRC = "/home/influencraft/serveurs/03_semirp/plugins/BattlePass"
OUT = sys.argv[1]

PASS_ID = "pass"
SEASON_START = "2026-10-01 18:00"
SEASON_END = "2026-12-31 23:59"
DAILY_START = "2026-10-01 06:00"

GOLD = "&#FFD94D"
LILAC = "&#B376E5"
GREEN = "&#2ECF7B"
RED = "&#FF5C5C"


def load(path):
    with open(os.path.join(SRC, path), encoding="utf-8") as f:
        return yaml.safe_load(f)


def color(s):
    """§x§R§R§G§G§B§B -> &#RRGGBB, puis § -> &."""
    if s is None:
        return s
    s = re.sub(r"§x((?:§[0-9A-Fa-f]){6})", lambda m: "&#" + m.group(1).replace("§", "").upper(), s)
    return s.replace("§", "&")


def color_list(lst):
    return [color(x) for x in lst]


def item_lookup(material, glow=False):
    """Matériau BattlePass -> chaîne de lookup eco."""
    m = material.strip()
    if m.startswith("itemsadder:"):
        _, ns, ident = m.split(":")
        m = f"itemsadder:{ns}__{ident}"
    else:
        m = m.split(":")[0]  # "book:0" -> "book"
    if glow:
        m += " glint"
    return m


def dump(path, data, header=None):
    full = os.path.join(OUT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8") as f:
        if header:
            for line in header.strip("\n").split("\n"):
                f.write(f"# {line}".rstrip() + "\n")
            f.write("\n")
        yaml.safe_dump(data, f, allow_unicode=True, sort_keys=False, width=1000, default_flow_style=False)


# ---------------------------------------------------------------------------
# Récompenses
# ---------------------------------------------------------------------------
rewards = load("rewards.yml")
free = load("passes/free.yml")
premium = load("passes/premium.yml")

TRACKS = {"free": free, "premium": premium}
PREFIX = {"free": "standard", "premium": "prestige"}


def reward_file(track, tier, old_id):
    r = rewards[str(old_id)]
    assert r["type"] == "command", r
    rid = f"{PREFIX[track]}_{int(tier):02d}"
    data = {
        "display": {
            "name": color(r["name"]),
            "reward-lore": color_list(r.get("lore-addon", [])),
        },
        "effects": [
            {
                "id": "run_command",
                "args": {"command": cmd},
            }
            for cmd in r["commands"]
        ],
    }
    dump(
        f"rewards/{PREFIX[track]}/{rid}.yml",
        data,
        f"Pass {'Standard' if track == 'free' else 'Prestige'}, palier {tier} (ancien BattlePass rewards.yml id {old_id}).",
    )
    return rid


# Lignes de lore BattlePass -> EcoBattlePass, par piste
def convert_lore(lines, track, claimed=False):
    out = []
    for line in lines:
        if "%lore_addon%" in line:
            if claimed:
                ph = "%claimed-free-rewards%" if track == "free" else "%claimed-premium-rewards%"
            else:
                ph = "%free-rewards%" if track == "free" else "%premium-rewards%"
            out.append(line.replace("%lore_addon%", ph))
        else:
            out.append(
                color(line)
                .replace("%points%", "%current_bp_xp%")
                .replace("%required_points%", "%required_bp_xp%")
            )
    return out


def convert_item(it, track, claimed=False):
    return {
        "item": item_lookup(it["material"], it.get("glow", False)),
        "name": color(it["name"]),
        "lore": convert_lore(it.get("lore", []), track, claimed),
    }


# états BattlePass -> états EcoBattlePass (mode split)
STATE_MAP = {
    "locked-tier-item": ["locked", "in-progress"],
    "unlocked-tier-item": ["unlocked"],
    "claimed-tier-item": ["claimed"],
    "doesnt-have-pass-item": ["premium-required"],
}

# ---------------------------------------------------------------------------
# Paliers
# ---------------------------------------------------------------------------
tiers = []
for t in range(1, 51):
    tier_cfg = {"tier": t, "rewards": []}
    display = {}
    for track, data in TRACKS.items():
        tdata = data["tiers"][str(t)]
        for old_id in tdata["rewards"]:
            rid = reward_file(track, t, old_id)
            tier_cfg["rewards"].append({"id": rid, "tier": track})
        overrides = {}
        for old_key, new_keys in STATE_MAP.items():
            if old_key in tdata:
                for nk in new_keys:
                    if track == "free" and nk == "premium-required":
                        continue
                    overrides[nk] = convert_item(tdata[old_key], track, claimed=(nk == "claimed"))
        if overrides:
            display[f"{track}-track"] = overrides
    if display:
        tier_cfg["display"] = display
    tiers.append(tier_cfg)

# Points requis par palier (ancien barème, "use-improved-tier-points") :
# 2-10 : 80 | 11-20 : 120 | 21-30 : 160 | 31-40 : 200 | 41-49 : 250 | 50 : 400.
# Palier 1 = départ, 0 point (atteint dès le premier point gagné).
for t in range(2, 51):
    expected = free["tiers"][str(t)].get("required-points", free["default-points-required"])
    got = (t >= 2) * 80 + (t >= 11) * 40 + (t >= 21) * 40 + (t >= 31) * 40 + (t >= 41) * 50 + (t >= 50) * 150
    assert expected == got, (t, expected, got)

battlepass = {
    "name": f"{GOLD}Pass",
    "battlepass": {
        "xp-formula": "(%level% >= 2) * 80 + (%level% >= 11) * 40 + (%level% >= 21) * 40"
        " + (%level% >= 31) * 40 + (%level% >= 41) * 50 + (%level% >= 50) * 150",
        "max-tier": 50,
        "command": PASS_ID,
        "premium-permission": free_perm if (free_perm := premium.get("required-permission")) else "battlepass.premium",
        "battlepass-start": SEASON_START,
        "battlepass-end": SEASON_END,
    },
    "tiers": tiers,
}
dump(
    f"battlepasses/{PASS_ID}.yml",
    battlepass,
    """Pass Influencraft, Saison 4 : portage du BattlePass (AdvancedPlugins) du SemiRP.
Généré par gen_ecobattlepass.py depuis serveurs/03_semirp/plugins/BattlePass (passes/, rewards.yml).
Piste "free" = Pass Standard, piste "premium" = Pass Prestige (permission battlepass.premium, la même que l'ancien plugin).
Barème des points : 80 (paliers 2-10), 120 (11-20), 160 (21-30), 200 (31-40), 250 (41-49), 400 (50).""",
)

# ---------------------------------------------------------------------------
# Quêtes et tâches
# ---------------------------------------------------------------------------
ALL_SWORDS = ["WOODEN_SWORD", "STONE_SWORD", "COPPER_SWORD", "IRON_SWORD", "GOLDEN_SWORD", "DIAMOND_SWORD", "NETHERITE_SWORD"]


def split_var(v):
    if v is None or str(v).lower() == "none":
        return []
    return [x.strip().upper() for x in str(v).split(" OR ")]


def xp_methods(q):
    """Type de quête BattlePass -> xp-gain-methods libreforge."""
    t = q["type"]
    v = q.get("variable")
    vals = split_var(v) if not isinstance(v, dict) else []
    m = {}
    if t == "block-break":
        m = {"trigger": "mine_block", "value": 1, "filters": {"blocks": vals, "player_placed": False}}
    elif t == "harvest-crops":
        m = {"trigger": "mine_block", "value": 1, "filters": {"blocks": vals, "fully_grown": True}}
    elif t == "block-place":
        m = {"trigger": "place_block", "value": 1, "filters": {"blocks": vals}}
    elif t == "craft":
        if vals == ["___SWORD"]:
            vals = ALL_SWORDS
        m = {"trigger": "craft", "multiplier": 1, "filters": {"items": vals}}
    elif t == "smelt":
        m = {"trigger": "smelt", "multiplier": 1, "filters": {"items": vals}}
    elif t == "smith":
        m = {"trigger": "smith_item", "value": 1, "filters": {"items": vals}}
    elif t == "consume":
        m = {"trigger": "consume", "value": 1, "filters": {"items": vals}}
    elif t == "kill-mob":
        m = {"trigger": "kill", "value": 1, "filters": {"entities": [x.lower() for x in vals]}}
    elif t == "tame":
        m = {"trigger": "tame_animal", "value": 1, "filters": {"entities": [x.lower() for x in vals]}}
    elif t == "breed":
        m = {"trigger": "breed", "value": 1}
    elif t == "fish":
        m = {"trigger": "catch_fish", "value": 1}
    elif t == "shear":
        m = {"trigger": "shear_entity", "value": 1}
    elif t == "brew":
        m = {"trigger": "brew", "value": 1}
    elif t == "gain-experience":
        m = {"trigger": "gain_xp", "multiplier": 1}
    elif t == "throw-projectile":
        m = {"trigger": "projectile_launch", "value": 1}
    elif t == "move":
        m = {"trigger": "move", "multiplier": 1}
    elif t == "sprint":
        m = {"trigger": "move", "multiplier": 1, "conditions": [{"id": "is_sprinting"}]}
    elif t == "swim":
        m = {"trigger": "move", "multiplier": 1, "conditions": [{"id": "is_swimming"}]}
    elif t == "glide":
        m = {"trigger": "move", "multiplier": 1, "conditions": [{"id": "is_gliding"}]}
    elif t == "sneak":
        # toggle_sneak se déclenche à l'entrée ET à la sortie de l'accroupissement
        m = {"trigger": "toggle_sneak", "value": 0.5}
    elif t == "milk":
        m = {
            "trigger": "click_entity",
            "value": 1,
            "filters": {"entities": ["cow", "mooshroom"]},
            "conditions": [{"id": "in_mainhand", "args": {"items": ["bucket"]}}],
        }
    elif t == "job-xp":
        m = {"trigger": "gain_job_xp", "multiplier": 1}
    elif t == "bow-hit":
        m = {"trigger": "bow_attack", "value": 1}
    elif t == "enchant-all":
        m = {"trigger": "enchant_item", "value": 1, "filters": {"enchant": [v["root"].lower()]}}
    else:
        raise ValueError(f"type de quête non géré : {t}")
    if "filters" in m:
        m["filters"] = {k: x for k, x in m["filters"].items() if x != []}
        if not m["filters"]:
            del m["filters"]
    return [m]


def mission(name, typ, var, req, pts, material, desc, premium=False):
    star = "★ " if premium else ""
    lore = [f"{LILAC}{d}" for d in desc]
    if premium:
        lore.append(f"{LILAC}&oExclusif Pass Prestige")
    lore += ["", f"{LILAC}Récompense &8► &f+{pts} points"]
    q = {"name": name, "type": typ, "variable": var, "required-progress": req, "points": pts,
         "item": {"material": material, "name": f"{GOLD}{star}{name}", "lore": lore}}
    if premium:
        q["exclusive"] = "premium"
    return q


# Missions revues (2026-10-03) : sprint, nage, accroupi, marche et lancer de projectiles
# se farmaient en AFK ou au spam, sans rapport avec le serveur. Remplacées par des
# missions liées au jeu (carrières EcoJobs, tir à l'arc, cultures, cuisine).
REPLACED = {
    ("daily", 5): mission("Carriériste", "job-xp", None, 300, 15, "iron_pickaxe",
                          ["Gagne &f300 &#B376E5XP de carrière,", "dans n'importe quelle carrière."]),
    ("daily", 10): mission("Maraîcher", "harvest-crops", "MELON OR PUMPKIN", 20, 15, "melon_slice",
                           ["Récolte &f20 &#B376E5melons ou citrouilles."]),
    ("daily", 15): mission("Archer", "bow-hit", None, 10, 15, "bow",
                           ["Touche &f10 &#B376E5créatures avec une flèche."]),
    ("daily", 17): mission("Cuisinier", "smelt", "cooked_beef OR cooked_porkchop OR cooked_chicken OR cooked_mutton OR baked_potato OR cooked_cod OR cooked_salmon",
                           10, 15, "furnace", ["Cuis &f10 &#B376E5aliments au four ou au fumoir."]),
    (1, 4): mission("Premiers pas en carrière", "job-xp", None, 1000, 50, "iron_pickaxe",
                    ["Gagne &f1000 &#B376E5XP de carrière,", "dans n'importe quelle carrière."]),
    (1, 8): mission("Carrière en vue", "job-xp", None, 3000, 120, "diamond_pickaxe",
                    ["Gagne &f3000 &#B376E5XP de carrière,", "dans n'importe quelle carrière."], premium=True),
    (3, 4): mission("Potager", "harvest-crops", "POTATOES", 50, 75, "potato",
                    ["Récolte &f50 &#B376E5pommes de terre mûres."]),
    (4, 4): mission("Tireur d'élite", "bow-hit", None, 30, 100, "bow",
                    ["Touche &f30 &#B376E5créatures avec une flèche."]),
}


def progress_line():
    return f"{LILAC}Progression &8► &f%current_task_xp%&7/&f%required_task_xp%"


def write_quest(qid, q, tier, category_label, points_override=None, extra_tasks=None):
    """Écrit tasks/<qid>.yml et quests/<qid>.yml. extra_tasks = autres quêtes de la même
    rotation (quotidiennes) : une seule est tirée au hasard à chaque renouvellement."""
    members = [(qid, q)] + (extra_tasks or [])
    task_refs = []
    for tid, tq in members:
        lore = [l for l in tq["item"].get("lore", []) if "%progress_bar%" not in l]
        # retire la ligne "Bonus" (pas de récompense de fin de mission dans EcoBattlePass)
        lore = [l for l in lore if "Bonus" not in l]
        while lore and lore[-1] == "":
            lore.pop()
        if points_override is not None:
            lore = [re.sub(r"\+\d+ points", f"+{points_override} points", l) for l in lore]
        dump(
            f"tasks/{category_label}/{tid}.yml",
            {
                "display": {
                    "display-name": color(tq["item"]["name"]),
                    "lore": color_list(lore) + ["", progress_line()],
                },
                "xp-gain-methods": xp_methods(tq),
            },
            f"Type de mission (format BattlePass) : {tq['type']} / {tq.get('variable')} x{tq['required-progress']}.",
        )
        task_refs.append({"id": tid, "xp": tq["required-progress"]})
    first = q
    quest = {
        "display": {
            "item": item_lookup(first["item"]["material"]) if len(members) == 1 else "itemsadder:influitems__tracking",
            "display-name": color(first["item"]["name"]) if len(members) == 1 else f"{GOLD}Mission quotidienne",
            "description": [],
        },
        "battlepass-points": points_override if points_override is not None else first["points"],
        "battlepass-tier": tier,
        "tasks": task_refs,
        "task-amount": 1,
    }
    dump(f"quests/{category_label}/{qid}.yml", quest)
    return qid


categories = {}

# Semaines 1 à 4
for w in range(1, 5):
    data = load(f"quests/week-{w}-quests.yml")
    ids = []
    for n, q in data["quests"].items():
        q = REPLACED.get((w, int(n)), q)
        qid = f"s{w}_{n}"
        tier = "premium" if q.get("exclusive") == "premium" else "free"
        ids.append(write_quest(qid, q, tier, f"semaine-{w}"))
    categories[f"semaine_{w}"] = (w, ids)

# Quotidiennes : l'ancien plugin tirait 9 missions sur 22 chaque matin à 6h. EcoBattlePass n'a
# pas de tirage de quêtes, mais chaque quête tire au hasard 1 tâche parmi sa liste à chaque
# renouvellement : on fait 9 emplacements, chacun avec un groupe de missions de même valeur.
daily = load("quests/daily-quests.yml")["quests"]
daily = {int(k): REPLACED.get(("daily", int(k)), v) for k, v in daily.items()}
DAILY_SLOTS = [
    ("quotidienne_1", 30, [1, 2, 7]),
    ("quotidienne_2", 30, [8, 16, 20]),
    ("quotidienne_3", 35, [3, 11, 19]),
    ("quotidienne_4", 20, [4, 6, 12]),
    ("quotidienne_5", 20, [13, 18]),
    ("quotidienne_6", 15, [5, 10, 14]),
    ("quotidienne_7", 15, [15, 17]),
    ("quotidienne_8", 25, [9]),
    ("quotidienne_9", 45, [21, 22]),
]
used = sorted(n for _, _, ns in DAILY_SLOTS for n in ns)
assert used == sorted(int(k) for k in daily), used
daily_ids = []
for slot_id, pts, nums in DAILY_SLOTS:
    members = [(f"q{n}", daily[n] if n in daily else daily[str(n)]) for n in nums]
    head_id, head = members[0]
    write_quest(slot_id, head, "free", "quotidiennes", points_override=pts, extra_tasks=members[1:])
    # write_quest a écrit la tâche de tête sous l'id du slot : on la renomme pour garder un id par mission
    os.replace(
        os.path.join(OUT, f"tasks/quotidiennes/{slot_id}.yml"),
        os.path.join(OUT, f"tasks/quotidiennes/{head_id}.yml"),
    )
    qpath = os.path.join(OUT, f"quests/quotidiennes/{slot_id}.yml")
    with open(qpath, encoding="utf-8") as f:
        qd = yaml.safe_load(f)
    qd["tasks"][0]["id"] = head_id
    if len(members) == 1:
        qd["display"]["display-name"] = color(head["item"]["name"])
    dump(f"quests/quotidiennes/{slot_id}.yml", qd)
    daily_ids.append(slot_id)

TIMER_FR = {
    "start": "Débute dans %time%",
    "end": "Se termine dans %time%",
    "reset": "Renouvellement dans %time%",
    "none": "Sans limite de temps",
}

dump(
    f"categories/{PASS_ID}/quotidiennes.yml",
    {
        "battlepass": PASS_ID,
        "priority": 0,
        "gui-title": f"{GOLD}Pass &8► {LILAC}Missions quotidiennes",
        "name": f"&r&7%img_left_click% {GOLD}Quotidiennes &8(&f%time%&8)",
        "item": "clock",
        "lore": [
            "",
            f"{LILAC}Voir les missions quotidiennes.",
            f"{LILAC}Renouvellement à &f06h00 {LILAC}chaque matin.",
            "",
            f"{LILAC}Complétées &8► &f%completed%&7/&f%total%",
        ],
        "start-date": DAILY_START,
        "duration": -1,
        "reset-time": 1440,
        "timer-format": TIMER_FR,
        "quests": [{"id": q} for q in daily_ids],
    },
)

for cat_id, (w, ids) in categories.items():
    start = f"2026-10-{1 + 7 * (w - 1):02d} 18:00"
    dump(
        f"categories/{PASS_ID}/{cat_id}.yml",
        {
            "battlepass": PASS_ID,
            "priority": w,
            "gui-title": f"{GOLD}Pass &8► {LILAC}Semaine {w}",
            "name": f"&r&7%img_left_click% {GOLD}Semaine {w}",
            "item": f"writable_book {w}",
            "lore": [
                "",
                f"{LILAC}Complétées &8► &f%completed%&7/&f%total%",
            ],
            "start-date": start,
            "duration": -1,
            "reset-time": -1,
            "timer-format": TIMER_FR,
            "quests": [{"id": q} for q in ids],
        },
    )

print("OK", OUT)
