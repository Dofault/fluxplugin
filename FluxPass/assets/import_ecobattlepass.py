#!/usr/bin/env python3
"""One-time import: EcoBattlepass configs of Labs -> FluxPass configs (pass.yml, missions/<category>.yml).

Reads (never changes) EcoBattlepass: battlepasses/pass.yml (season, tiers, xp formula), rewards/standard|prestige
(reward-lore, run_command effects), categories/pass, quests, tasks (xp-gain-methods = libreforge counters).
The mission menu names come from FluxTools' former pass-menu config.

What a mission asks: FluxPass prices it with FluxValue (effort / points of one action). One effort per pass point
for the whole pass (every mission costs play time in proportion to what it pays), calibrated here so that today's
quantities stay the same on average (median over the variants FluxValue can price: breaks, kills, catches).
Each variant keeps its current quantity:
  - as "default-amount" (used only if FluxValue cannot price it in game), for priceable triggers;
  - as a fixed "amount" for actions FluxValue does not value (move, breed, shear...);
  - as a fixed "amount" for ores FluxValue prices at less than a third of the median: an ore is mostly searched
    for, and FluxValue counts the time to break it, not to find it. Listed at the end.

Usage: import_ecobattlepass.py [ecobattlepass dir] [fluxpass dir]
Then /fluxpass reload and /fluxpass preview to check what every variant asks.
"""
import re
import statistics
import sys
from pathlib import Path

import yaml

ECO = Path(sys.argv[1] if len(sys.argv) > 1 else "/home/influencraft/serveurs/04_labs/plugins/EcoBattlepass")
OUT = Path(sys.argv[2] if len(sys.argv) > 2 else "/home/influencraft/serveurs/04_labs/plugins/FluxPass")
FLUXVALUE = Path("/home/influencraft/serveurs/04_labs/plugins/FluxValue/sources.yml")
FLUXTOOLS = Path("/home/influencraft/serveurs/04_labs/plugins/FluxTools/config.yml")

# Base health of the creatures the missions hunt (vanilla 26.2 attributes): FluxValue reads it on the server,
# this script only needs it to calibrate.
HEALTH = {"zombie": 20, "husk": 20, "drowned": 20, "skeleton": 20, "stray": 20, "creeper": 20, "spider": 16,
          "cave_spider": 12, "enderman": 40, "witch": 26, "phantom": 20, "pillager": 24, "vindicator": 24,
          "breeze": 30, "blaze": 20, "wither_skeleton": 20, "ghast": 10, "magma_cube": 16, "slime": 16,
          "zombified_piglin": 20, "piglin": 16, "hoglin": 40, "guardian": 30, "shulker": 30, "evoker": 24}
PRICED = {"mine_block", "kill", "catch_fish", "smelt", "craft", "consume", "brew", "enchant_item", "place_block"}
CODES = re.compile(r"(?i)&#[0-9a-f]{6}|&[0-9a-fk-or]|§[0-9a-fk-or]")

CLES = {"ordinaire": "itemsadder:influkey:common_key", "tendance": "itemsadder:influkey:epic_key",
        "virale": "itemsadder:influkey:rare_key", "phenomene": "itemsadder:influkey:legendary_key"}


def load(p):
    return yaml.safe_load(open(p, encoding="utf-8")) or {}


def plain(t):
    return CODES.sub("", str(t)).strip()


# ---------------------------------------------------------------- values (calibration only)

def action_points():
    s = load(FLUXVALUE)
    blocks, mobs = {}, {}
    for job in (s.get("jobs") or {}).values():
        for sid, src in (job.get("sources") or {}).items():
            trig = src.get("trigger", "mine_block")
            if trig in ("mine_block", "mine_block_cascade"):
                for b in src.get("blocks") or [sid]:
                    blocks.setdefault(b, src["weight"])
    for m, d in (s.get("mobs") or {}).items():
        if m in HEALTH:
            mobs[m] = HEALTH[m] * 4.23 * (d or {}).get("weight", 1)
    for a, d in (s.get("animals") or {}).items():
        mobs[a] = (d or {}).get("weight", 0)
    fish = (s.get("fishing") or {}).get("catch-weight")
    return blocks, mobs, fish


BLOCKS, MOBS, FISH = action_points()


def one(counter):
    trig = counter.get("trigger")
    f = counter.get("filters") or {}
    if trig == "mine_block" and f.get("blocks"):
        return BLOCKS.get(str(f["blocks"][0]).lower())
    if trig == "kill" and f.get("entities"):
        return MOBS.get(str(f["entities"][0]).lower())
    if trig == "catch_fish":
        return FISH
    return None


# ---------------------------------------------------------------- rewards

def lot(effects):
    """(icon, amount) of the most telling item of a reward, as the former menu generator did."""
    found = []
    for eff in effects or []:
        cmd = str(((eff or {}).get("args") or {}).get("command", "")).split()
        if len(cmd) >= 4 and cmd[0] == "iagive":
            found.append((0, "itemsadder:" + cmd[2], int(cmd[3])))
        elif len(cmd) >= 4 and cmd[0] == "cmi" and cmd[1] == "give":
            found.append((0, cmd[3].upper(), int(cmd[4]) if len(cmd) > 4 else 1))
        elif len(cmd) >= 5 and cmd[0] == "customfishing" and cmd[1] == "items":
            found.append((1, "STRING", int(cmd[5]) if len(cmd) > 5 else 1))
        elif len(cmd) >= 6 and cmd[0] == "ecocrates" and cmd[3] == "key" and cmd[4] in CLES:
            found.append((2, CLES[cmd[4]], int(cmd[6]) if len(cmd) > 6 else 1))
        elif len(cmd) >= 4 and cmd[0] == "points" and cmd[1] == "give":
            found.append((3, "itemsadder:influitems:influence", int(cmd[3])))
    if not found:
        return "itemsadder:influitems:chest", 1
    _, mat, n = min(found, key=lambda x: x[0])
    return mat, max(1, min(64, n))


def reward(track, rid):
    f = ECO / "rewards" / ("standard" if track == "free" else "prestige") / f"{rid}.yml"
    if not f.is_file():
        return None
    r = load(f)
    icon, amount = lot(r.get("effects"))
    return {"icon": icon, "amount": amount,
            "lines": list((r.get("display") or {}).get("reward-lore") or []),
            "commands": [str(e["args"]["command"]) for e in r.get("effects") or []
                         if e.get("id") == "run_command" and (e.get("args") or {}).get("command")]}


def season():
    bp = load(ECO / "battlepasses/pass.yml")
    b = bp["battlepass"]
    tiers = int(b["max-tier"])
    formula = b["xp-formula"]
    per = {}
    for t in range(1, tiers + 1):
        expr = formula.replace("%level%", str(t))
        per[t] = int(eval(re.sub(r"[^0-9+*()<>=. -]", "", expr)))
    ranges, start = {}, 1
    for t in range(2, tiers + 2):
        if t > tiers or per[t] != per[start]:
            ranges[f"{start}" if start == t - 1 else f"{start}-{t - 1}"] = per[start]
            start = t
    rewards = {}
    for tier in bp.get("tiers") or []:
        entry = {}
        for r in tier.get("rewards") or []:
            got = reward(r["tier"], r["id"])
            if got:
                entry[r["tier"]] = got
        rewards[int(tier["tier"])] = entry
    return {"season": {"name": "Saison 4", "start": str(b["battlepass-start"]), "end": str(b["battlepass-end"])},
            "tiers": tiers, "points-per-tier": ranges, "rewards": rewards}


# ---------------------------------------------------------------- missions

def task_file(tid):
    hits = list((ECO / "tasks").rglob(f"{tid}.yml"))
    return load(hits[0]) if hits else None


def description(task, amount):
    lines = []
    for raw in (task.get("display") or {}).get("lore") or []:
        if not plain(raw):
            break
        lines.append(plain(raw))
    text = " ".join(lines).replace("%required_task_xp%", "{amount}")
    if "{amount}" not in text:
        text = re.sub(rf"(?<![\d,.]){amount}(?![\d,.])", "{amount}", text, count=1)
    return text


MENU = (load(FLUXTOOLS).get("modules") or {}).get("pass-menu", {}).get("categories", {})


def category(path):
    c = load(path)
    cid = path.stem
    shown = MENU.get(cid, {})
    if cid == "quotidiennes":
        renewal = {"type": "daily", "time": "06:00"}
    elif cid.startswith("semaine_"):
        renewal = {"type": "monthly", "day": 1, "time": "18:00", "offset-days": 7 * (int(cid.split("_")[1]) - 1)}
    else:
        renewal = {"type": "none"}
    missions, ratios = {}, []
    for q in c.get("quests") or []:
        qf = next((ECO / "quests").rglob(f"{q['id']}.yml"), None)
        if qf is None:
            continue
        quest = load(qf)
        points = int(quest.get("battlepass-points", 10))
        variants = {}
        for t in quest.get("tasks") or []:
            task = task_file(t["id"])
            if not task:
                continue
            amount = int(t.get("xp", 1))
            counters = task.get("xp-gain-methods") or []
            v = {"name": plain((task.get("display") or {}).get("display-name", "")),
                 "description": description(task, amount), "counters": counters}
            pts = one(counters[0]) if counters else None
            if pts:
                ratios.append((amount * pts / points, cid, q["id"], t["id"]))
            v["_amount"], v["_points_of_one"], v["_trigger"] = amount, pts, (counters[0].get("trigger") if counters else None)
            variants[t["id"]] = v
        icon = str((quest.get("display") or {}).get("item", "paper")).split()[0].upper()
        missions[q["id"]] = {"name": plain((quest.get("display") or {}).get("display-name", q["id"])), "icon": icon,
                             "points": points, "premium": quest.get("battlepass-tier") == "premium", "variants": variants}
    return cid, {"name": shown.get("name", plain(c.get("name", cid))), "icon": shown.get("icon", "PAPER"),
                 "amount": shown.get("amount", 1), "order": c.get("priority", 0), "renewal": renewal}, missions, ratios


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "missions").mkdir(exist_ok=True)
    s = season()
    head = ("# Saison du pass (importée d'EcoBattlepass par FluxPass/assets/import_ecobattlepass.py, éditée à la main ensuite).\n"
            "# Paliers, points par palier, récompenses des deux pistes (free = Pass Standard, premium = Pass Prestige).\n")
    (OUT / "pass.yml").write_text(head + yaml.safe_dump(s, allow_unicode=True, sort_keys=False, width=200), encoding="utf-8")
    fixed_rare = []
    read = [category(p) for p in sorted((ECO / "categories/pass").glob("*.yml")) if not p.name.startswith("_")]
    all_ratios = [r[0] for _, _, _, ratios in read for r in ratios]
    epp = round(statistics.median(all_ratios), 2) if all_ratios else 20
    print(f"effort-per-point (whole pass): {epp}, over {len(all_ratios)} priced variants")
    for cid, meta, missions, ratios in read:
        out = dict(meta)
        out["effort-per-point"] = epp
        out["missions"] = {}
        for mid, m in missions.items():
            variants = {}
            for vid, v in m["variants"].items():
                amount, pts, trig = v.pop("_amount"), v.pop("_points_of_one"), v.pop("_trigger")
                target = str((((v["counters"] or [{}])[0].get("filters") or {}).get("blocks") or [""])[0]).lower()
                ore = trig == "mine_block" and (target.endswith("_ore") or target == "ancient_debris")
                if pts and ore and amount * pts / m["points"] < epp / 3:
                    v["amount"] = amount
                    fixed_rare.append(f"{cid}/{mid}/{vid} ({trig}, x{amount}, FluxValue en demanderait "
                                      f"{round(epp * m['points'] / pts)})")
                elif trig in PRICED:
                    v["default-amount"] = amount
                else:
                    v["amount"] = amount
                variants[vid] = v
            entry = {"name": m["name"], "icon": m["icon"], "points": m["points"]}
            if m["premium"]:
                entry["premium"] = True
            if len(variants) == 1:
                (vid, v), = variants.items()
                entry.update({k: val for k, val in v.items() if k != "name"})
            else:
                entry["variants"] = variants
            out["missions"][mid] = entry
        head = (f"# Catégorie « {meta['name']} » (importée d'EcoBattlepass, éditée à la main ensuite).\n"
                "# Quantité d'une variante = effort / points d'une action (FluxValue), effort = points de la mission x effort-per-point.\n"
                "# amount = quantité fixe (action que FluxValue ne valorise pas, ou cible rare) ; default-amount = secours si FluxValue ne sait pas.\n")
        (OUT / "missions" / f"{cid}.yml").write_text(head + yaml.safe_dump(out, allow_unicode=True, sort_keys=False, width=200),
                                                   encoding="utf-8")
        print(f"{cid}: {len(missions)} missions")
    if fixed_rare:
        print("Minerais gardés en quantité fixe (FluxValue ne compte pas le temps de recherche) :")
        for line in fixed_rare:
            print("  " + line)


if __name__ == "__main__":
    main()
