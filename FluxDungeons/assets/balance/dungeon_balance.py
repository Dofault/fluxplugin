#!/usr/bin/env python3
"""How hard each dungeon is, room by room, from the real configs (2026-11-14).

    python3 dungeon_balance.py <plugins/FluxDungeons> [dungeon ...] [--level N] [--party N] [--check]

Reads the monsters (mobs/, spells/), where they stand (edits/<dungeon>.yml), the difficulty levels of the dungeon and
the classes' numbers from the simulator (FluxDungeons/target/balance-report.json, written by BalanceTest: run the
tests first). Plays every room in numbers and prints, per difficulty and per class: how long the room takes and how
much of his life a player loses in its worst pull; then the boss.

The model (its factors are judgment, written here so they can be argued with):
  - a room is fought in PULLS of PULL monsters (the alert radius wakes neighbours, never the whole room), with a rest
    between pulls (out of combat a player gets all his life back in 20 s);
  - a player deals REAL of the simulator's damage (he aims, moves, dodges): its sustained damage on as many monsters
    as the pull has left (dps1 on one, dps6 on six or more);
  - a monster's hit takes attack x coefficient x (1 - defense / (defense + K)); only a share of the hits lands on a
    player who moves (EXPOSURE): melee 30 % (the three classes fight at range and have a movement spell; 50 % from fast
    or frenzied monsters), shots 45 %, spells 45 %, slow telegraphed blows 25 %;
  - a monster casts one spell at a time, CAST_GAP seconds apart at least, among those its difficulty level gives it;
  - monsters of a pull die one after the other: on average half of them are alive.
Targets (--check exits 1 when one is missed), for the frailest class alone, at the dungeon's recommended level:
  worst pull: Easy <= 45 % of his life, Normal <= 75 %, Hard <= 120 % (Hard asks for a party or clean dodging);
  boss: the life lost over the whole fight stays under 90 % in Easy and 150 % in Normal (he has to dodge better than
  the model's average player, or bring a druid), and the fight lasts 1.5 to 5 minutes alone.
"""
import glob
import json
import math
import os
import sys

import yaml

REAL = 0.55
PULL = 6
CAST_GAP = 3.0
EXPOSURE = {"melee": 0.30, "fast": 0.50, "shot": 0.45, "spell": 0.45, "slow": 0.25}
SHOOTERS = ("SKELETON", "STRAY", "BOGGED", "PILLAGER", "BLAZE", "WITCH", "GUARDIAN", "DROWNED_TRIDENT", "PARCHED")
RANKS = {"normal": (1, 1), "elite": (3.5, 1.6), "miniboss": (9, 2.2), "boss": (70, 3)}
PULL_TARGET = [0.45, 0.75, 1.20]
BOSS_TARGET = [0.90, 1.50, 3.00]
HERE = os.path.dirname(os.path.abspath(__file__))


def load(plugin):
    spells, mobs = {}, {}
    for f in glob.glob(plugin + "/spells/**/*.yml", recursive=True):
        spells[os.path.basename(f)[:-4]] = yaml.safe_load(open(f)) or {}
    for f in glob.glob(plugin + "/mobs/**/*.yml", recursive=True):
        d = yaml.safe_load(open(f)) or {}
        if "type" in d:
            mobs[os.path.basename(f)[:-4]] = d
        else:
            mobs.update({k: v for k, v in d.items() if isinstance(v, dict) and "type" in v})
    return spells, mobs


def ranks(plugin):
    b = yaml.safe_load(open(os.path.join(plugin, "rpg", "balance.yml")))
    return {k: (v["health"], v["damage"]) for k, v in b["ranks"].items()}


def abilities(mob, spells):
    out = []
    for a in mob.get("abilities") or []:
        b = dict(spells.get(a.get("spell"), {})) if "spell" in a else {}
        b.update({k: v for k, v in a.items() if k != "spell"})
        b["id"] = a.get("spell", b.get("type"))
        out.append(b)
    return out


def rank(mob):
    return str(mob.get("rank") or ("boss" if mob.get("boss") else "normal")).lower()


def profile(mob, spells, mobs, level_index, rk):
    """(life in normal monsters, coefficient per second that lands on a moving player) at a difficulty (0 = easiest)."""
    st = mob.get("stats") or {}
    r = rk[rank(mob)]
    life = r[0] * st.get("health", 1)
    split = mob.get("split")
    if split and split.get("mob") in mobs:
        life += split.get("count", 2) * profile(mobs[split["mob"]], spells, mobs, level_index, rk)[0]
    power = r[1] * st.get("damage", 1)
    unit = float(mob.get("damage", 1) or 1)
    speed = mob.get("attack-speed", 1)
    walk = mob.get("walk-speed") or (44 * mob["speed"] ** 2 if mob.get("speed") else 2.5)
    threat = 0.0
    boss = rank(mob) == "boss"
    if mob.get("keep-distance") or mob.get("stationary"):
        pass
    elif mob["type"] in SHOOTERS:
        threat += 0.5 * EXPOSURE["shot"]
    else:
        threat += speed * EXPOSURE["fast" if walk >= 3.4 or speed >= 4 or boss else "melee"]
    casts, weight = 0.0, 0.0
    for a in abilities(mob, spells):
        if a.get("from-level", 1) > level_index + 1 or not a.get("power") or not a.get("every"):
            continue
        if a.get("type") in ("rally", "stone_skin", "heal", "shield", "summon", "phase_shift", "entrance"):
            continue
        c = float(a["power"]) / unit
        c *= min(2.0, 1 + 0.25 * (a.get("count", 1) - 1))
        slow = a.get("impact", 0) >= 20 or a.get("type") in ("windup", "cataclysm", "fuse", "aimed_shot", "great_feast", "tidal_breach")
        rate = 1.0 / a["every"] * (0.4 if a.get("below") else 1)
        casts += rate
        weight += rate * c * EXPOSURE["slow" if slow else "spell"]
    if casts > 0:
        threat += weight * min(1.0, (1 / (mob.get("cast-gap") or CAST_GAP)) / casts)
    return life, threat * power


def difficulty(plugin, dungeon):
    d = yaml.safe_load(open(os.path.join(plugin, "dungeons", dungeon + ".yml")))
    return d.get("recommended-level", 1), [(l["name"], l.get("health", 1), l.get("damage", 1)) for l in d.get("levels", [])]


def main():
    args = sys.argv[1:]
    check = "--check" in args
    args = [a for a in args if a != "--check"]
    level = party = None
    for flag in ("--level", "--party"):
        if flag in args:
            i = args.index(flag)
            v = int(args[i + 1])
            del args[i:i + 2]
            if flag == "--level":
                level = v
            else:
                party = v
    party = party or 1
    plugin = args[0]
    report = json.load(open(os.path.join(HERE, "..", "..", "target", "balance-report.json")))
    spells, mobs = load(plugin)
    rk = ranks(plugin)
    dungeons = args[1:] or sorted(f[:-4] for f in os.listdir(os.path.join(plugin, "edits")) if f.endswith(".yml"))
    bad = 0
    for dj in dungeons:
        rec, levels = difficulty(plugin, dj)
        lv = level or rec
        key = str(min((int(k) for k in report), key=lambda k: abs(k - lv)))
        rep = report[key]
        mon = rep["monster"]
        classes = [c for c in rep if c != "monster"]
        edits = yaml.safe_load(open(os.path.join(plugin, "edits", dj + ".yml")))["map"]
        objectives = set(edits.get("objectives", []))
        rooms = {}
        for s in edits.get("spawns", []):
            rooms.setdefault(str(s["room"]), []).append(s["mob"])
        print(f"== {dj} (level {key}, {party} player{'s' if party > 1 else ''})")
        for li, (name, hm, dm) in enumerate(levels):
            total = 0.0
            line = []
            worst_all = 0.0
            boss_lines = []
            for room in sorted(rooms, key=lambda r: (len(r), r)):
                ids = [m for m in rooms[room] if m in mobs]
                bosses = [m for m in ids if m in objectives or rank(mobs[m]) == "boss"]
                troops = [m for m in ids if m not in bosses]
                time_room, worst = 0.0, {c: 0.0 for c in classes}
                for i in range(0, len(troops), PULL):
                    pull = [profile(mobs[m], spells, mobs, li, rk) for m in troops[i:i + PULL]]
                    life = sum(p[0] for p in pull) * mon["health"] * hm
                    n = len(pull)
                    for c in classes:
                        me = rep[c]
                        dps = (me["dps1"] + (me["dps6"] - me["dps1"]) * (min(n, 6) - 1) / 5) * REAL * party
                        t = life / dps
                        hit = mon["attack"] * dm * (1 - me["defense"] / (me["defense"] + mon["k"]))
                        taken = sum(p[1] for p in pull) * hit * t * 0.5 / party
                        healed = (me["heal"] + me["regen"]) * t
                        worst[c] = max(worst[c], max(0.0, taken - healed) / me["health"])
                        if c == classes[0]:
                            time_room += t
                total += time_room
                if troops:
                    w = max(worst.values())
                    worst_all = max(worst_all, w)
                    line.append(f"r{room} {len(troops)}m {time_room:4.0f}s " + "/".join(f"{worst[c] * 100:3.0f}" for c in classes) + "%")
                for b in bosses:
                    life_units, threat = profile(mobs[b], spells, mobs, li, rk)
                    # a boss's life is for four players: each one present brings a quarter of it
                    life = life_units * mon["health"] * hm * (min(4, party) / 4 if rank(mobs[b]) == "boss" else 1)
                    parts = []
                    for c in classes:
                        me = rep[c]
                        t = life / (me["dps1"] * REAL * party)
                        hit = mon["attack"] * dm * (1 - me["defense"] / (me["defense"] + mon["k"]))
                        lost = max(0.0, threat * hit * t / party - (me["heal"] + me["regen"]) * t) / me["health"]
                        parts.append((t, lost))
                    boss_lines.append((b, parts))
            print(f"  {name:9s} trash {total / 60:4.1f} min | " + " | ".join(line) + f"   ({'/'.join(c[:4] for c in classes)})")
            if worst_all > PULL_TARGET[min(li, 2)]:
                print(f"            ! worst pull {worst_all * 100:.0f} % of a life (target {PULL_TARGET[min(li, 2)] * 100:.0f} %)")
                bad += 1
            for b, parts in boss_lines:
                t = max(p[0] for p in parts)
                lost = max(p[1] for p in parts)
                flag = ""
                if rank(mobs[b]) == "boss" and (lost > BOSS_TARGET[min(li, 2)] or not 90 <= t <= 300):
                    flag = "  !"
                    bad += 1
                print(f"            {b}: " + ", ".join(f"{p[0]:4.0f} s {p[1] * 100:3.0f}%" for p in parts) + flag)
    sys.exit(1 if check and bad else 0)


if __name__ == "__main__":
    main()
