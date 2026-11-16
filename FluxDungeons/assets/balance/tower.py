#!/usr/bin/env python3
"""The dungeon tower (2026-11-16): the one place where the order of the dungeons, their level, the party they are
made for, their difficulty, their tokens and their shop are decided. Nothing of it is written by hand in the dungeon
files: change this script and run it again.

    python3 tower.py <plugins dir of the server> [--write] [--check]

Reads the real monsters of each dungeon (mobs/, spells/, edits/) and the simulator's report of the classes
(FluxDungeons/target/balance-report.json: run `mvn -pl FluxDungeons -am test` first), through dungeon_balance.py.
Without --write it only prints the table. With --write it writes, in FluxDungeons/dungeons/<id>.yml, the keys
floor, level, players.recommended, levels (a single level: no more Easy / Normal / Hard, owner's decision) and
reward (no money nor loot: tokens are the reward), and writes FluxDungeons/shop.yml (the dungeon shop).

THE TOWER. Ten floors, one dungeon each, from level 10 to level 100. A character stops at level 50 (balance.yml), so
above floor 5 nobody out-levels the monsters: what makes a floor possible is the number of players. A floor's
monsters are always of its level, whatever the party's (FluxDungeons#monsterLevel); above 50 their curves stop at 50
and the floor's life and damage multipliers carry the rest.

DIFFICULTY. Each floor has a PRESSURE: how hard its recommended party has it, 1.0 = a level 50 player alone in the
average dungeon at its hardest abilities (the former Normal of the hardest dungeons). A dungeon's pressure is measured
on its real monsters by dungeon_balance.evaluate: the life lost in its worst pull plus a third of the life lost over
the boss fight (that model reads high on bosses, a third puts both on the same scale). The script finds, for each
dungeon, the multiplier k (life x k, damage x k) that gives its floor's pressure to its recommended party at its
level: two dungeons of the same floor are as hard as each other whatever their monsters. A boss the model cannot read
(its powers have no `power`: spider, flower, evoker) counts as the median boss.

    - floors 1 to 4: alone, gentle and rising (0.40 to 0.78); the monsters' abilities of difficulty rank 1, then 2;
    - floor 5 (level 50): alone, pressure 1.0: "from the fifth one, it gets hard" (owner);
    - floors 6 and 7: two players; 8: three; 9 and 10: four, 10 the hardest (1.35).
    A party smaller than the recommended one meets the pressure times about recommended / its size; a bigger one
    has it easier: a floor is never refused, its level says it.

TOKENS. The only reward of a dungeon. A run of floor f gives about RUN_TOKENS(f) to each player (100, then 30 more
a floor), whatever the dungeon's number of monsters: its `reward` multiplies the tokens of every monster.

SHOP. One shop, a page per floor, everything priced in runs of that floor (RUN_TOKENS(f) x runs):
    - the boss's mount: MOUNT_RUNS(f) runs (7 on floor 1 to 16 on floor 10), sold only to those who beat the boss;
    - the job's boosters: a quarter of a run each;
    - the enchanted books of the job (JOB_ENCHANTS: every custom enchantment belongs to one job, the enchanter takes
      those that fit anything), at their highest level, priced by rarity (BOOK_RUNS); books only drawn from mystery
      books (fabled, not enchantable) are not sold (EXCLUSIVE);
    - the dungeon's leather set (gear.py: dyed in its job's colour, its floor's orientation), a run a piece.
"""
import math
import os
import re
import statistics
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import dungeon_balance as B  # noqa: E402

# floor: (dungeon, job, level, recommended players, pressure, abilities rank)
TOWER = {
    1: ("roche", "rochiste", 10, 1, 0.40, 1),
    2: ("moisson", "cultivateur", 20, 1, 0.50, 1),
    3: ("sylviculteur", "sylviculteur", 30, 1, 0.62, 2),
    4: ("taupe", "deblayeur", 40, 1, 0.78, 2),
    5: ("mineur", "mineur", 50, 1, 1.00, 3),
    6: ("herboriste", "herboriste", 60, 2, 1.00, 3),
    7: ("fondeur", "fondeur", 70, 2, 1.15, 3),
    8: ("defenseur", "defenseur", 80, 3, 1.10, 3),
    9: ("pecheur", "pecheur", 90, 4, 1.15, 3),
    10: ("enchanteur", "enchanteur", 100, 4, 1.35, 3),
}
MAX_LEVEL = 50          # balance.yml levels.max: the monsters' curves stop there

# colour of each job (2026-11-16): dye of its dungeon's leather set (gear.py), frame of its floor in the tower menu
# (gen_menus.py); one colour per job, none too close to another
DYES = {"rochiste": "#9A9AA3", "cultivateur": "#F0A800", "sylviculteur": "#7A5230", "deblayeur": "#C9A66B",
        "mineur": "#E3826C", "herboriste": "#6FA34A", "fondeur": "#D9441C", "defenseur": "#A8323A",
        "pecheur": "#3FB7C9", "enchanteur": "#9B59D9"}


def run_tokens(f):
    return 100 + 30 * (f - 1)


def mount_runs(f):
    return 6 + f


BOOSTER_RUNS = 0.25
SET_PIECE_RUNS = 1.0
BOOK_RUNS = {"simple": 0.5, "unique": 1.0, "elite": 1.5, "ultimate": 2.5, "legendary": 4.0}
EXCLUSIVE = {"buche", "celerite", "crocs_de_l_evocateur", "filon", "incassable", "soulbound", "zenkai", "colere_de_thor"}

# every enchantment of EcoEnchants in exactly one job (checked against the enabled files)
JOB_ENCHANTS = {
    "rochiste": ["mangeur_de_cailloux", "tranchee", "assurance_explosion"],
    "cultivateur": ["recolte", "replanteur", "pas_de_plume"],
    "sylviculteur": ["force_brute", "ressorts", "jambes_de_gelee", "chute_libre"],
    "deblayeur": ["hate_express", "respirateur", "oeil_de_chat"],
    "mineur": ["gemmification", "vision_miniere", "reaction_en_chaine", "experience"],
    "herboriste": ["cisaille", "virus", "empoisonne", "devoration"],
    "fondeur": ["fonte", "marcheur_de_lave", "extinction", "retour_de_flamme", "cremation", "solidification"],
    "defenseur": ["aigle_solaire", "ame_hostile", "chasseur", "critique", "frappe_du_ravageur", "immolation",
                  "prise_de_sang", "givre_nocturne", "essaim_nocturne", "casse_toi", "tueur_de_l_end", "tueur_du_nether",
                  "arc_en_sang", "sniper", "tireur_d_elite", "pluie_de_fleche", "fleche_explosif", "distorsion", "foudre",
                  "charge", "fusee", "seisme", "ruse_des_morts_vivants"],
    "pecheur": ["crochet", "maree_d_abondance", "poseidon", "impact", "discorde", "deferlante", "neon_electrique"],
    "enchanteur": ["telepathie", "enclume_de_poche", "curieux", "sauvegarde", "coeur_brillant", "angelique", "trapu",
                   "usain_volt", "gardiens", "esprits", "onde_de_choc"],
}
PIECES = ("helmet", "chestplate", "leggings", "boots")   # every floor has its leather set fluxrpg:<dungeon>_<piece> (gear.py)


def pressure(r, median_boss):
    boss = r["boss"] if r["boss"] > 0 else median_boss
    return r["pull"] + boss / 3


def solve(plugin, dungeon, level, tier, party, target, data, median_boss):
    lo, hi = 0.2, 6.0
    for _ in range(40):
        k = math.sqrt(lo * hi)
        r = B.evaluate(plugin, dungeon, min(level, MAX_LEVEL), tier, k, k, party, data)
        if pressure(r, median_boss(k)) > target:
            hi = k
        else:
            lo = k
    return round(math.sqrt(lo * hi), 3)


def enchants(plugin):
    out = {}
    folder = os.path.join(os.path.dirname(plugin), "EcoEnchants", "enchants")
    for f in sorted(os.listdir(folder)):
        if not f.endswith(".yml") or f.startswith("_"):
            continue
        d = yaml.safe_load(open(os.path.join(folder, f))) or {}
        out[f[:-4]] = (str(d.get("rarity", "simple")), int(d.get("max-level", 1)))
    return out


# ------------------------------------------------------------------------------------------ dungeon files

def top_blocks(text):
    """[(key, start, end)] of the top-level keys of a YAML text; the comment lines right above a key belong to it."""
    lines = text.split("\n")
    keys = [(i, m.group(1)) for i, l in enumerate(lines) if (m := re.match(r"^([A-Za-z][\w-]*):", l))]
    out = []
    for n, (i, k) in enumerate(keys):
        start = i
        while start > 0 and lines[start - 1].startswith("#") and (n == 0 or start - 1 > keys[n - 1][0]):
            start -= 1
        end = keys[n + 1][0] if n + 1 < len(keys) else len(lines)
        while n + 1 < len(keys) and end > i + 1 and lines[end - 1].startswith("#"):
            end -= 1
        out.append((k, start, end))
    return lines, out


def set_block(text, key, block, after=None):
    """Replaces a top-level key's block (with its comments) by `block` (None: removed); a missing key is added after
    the block of `after` (else at the end)."""
    lines, blocks = top_blocks(text)
    for k, s, e in blocks:
        if k == key:
            new = lines[:s] + ([] if block is None else block.rstrip("\n").split("\n")) + lines[e:]
            return "\n".join(new)
    if block is None:
        return text
    at = len(lines)
    for k, s, e in blocks:
        if k == after:
            at = e
    return "\n".join(lines[:at] + block.rstrip("\n").split("\n") + lines[at:])


def write_dungeon(plugin, floor, dungeon, level, party, tier, k, reward, players_max):
    path = os.path.join(plugin, "dungeons", dungeon + ".yml")
    text = open(path, encoding="utf-8").read()
    old = yaml.safe_load(text)
    keys_before = set(old)
    text = set_block(text, "recommended-level", None)
    text = set_block(text, "loot", None)
    text = set_block(text, "floor", f"# Tower of the dungeons (2026-11-16): written by plugins/fluxplugin/FluxDungeons/assets/balance/tower.py,\n"
                                    f"# rerun it rather than editing floor, level, players, levels and reward here.\n"
                                    f"floor: {floor}", after="pet")
    text = set_block(text, "level", f"# level of its monsters, whatever the party's\nlevel: {level}", after="floor")
    pmin = (old.get("players") or {}).get("min", 1)
    text = set_block(text, "players", f"players:\n  min: {pmin}\n  max: {players_max}\n  recommended: {party}")
    xp = (old.get("reward") or {}).get("xp", 260)
    text = set_block(text, "reward", f"# tokens of every monster x reward (about {run_tokens(floor)} a run each); xp: rpg experience of a victory\n"
                                     f"reward:\n  tokens: {reward}\n  xp: {xp}")
    text = set_block(text, "levels", "# One level only (no Easy / Normal / Hard any more): life and damage of its monsters, chosen by tower.py\n"
                                     "# for the floor's pressure; tier = the from-level of the abilities that are on (1 to 3).\n"
                                     f"levels:\n- name: Niveau {level}\n  health: {k}\n  damage: {k}\n  reward: {reward}\n  tier: {tier}")
    new = yaml.safe_load(text)
    lost = keys_before - set(new) - {"recommended-level", "loot"}
    if lost:
        sys.exit(f"{dungeon}: keys lost while writing: {lost}")
    open(path, "w", encoding="utf-8").write(text if text.endswith("\n") else text + "\n")


def shop(plugin, rows, rarities):
    pets = {}
    import importlib.util
    spec = importlib.util.spec_from_file_location("familiers_boss", os.path.join(
        os.path.dirname(HERE), "..", "..", "..", "..", "scripts", "influcard", "familiers_boss.py"))
    fb = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fb)
    for b in fb.BOSSES:
        pets[b[4]] = b[0]
    out = ["# The dungeon shop (2026-11-16), written by plugins/fluxplugin/FluxDungeons/assets/balance/tower.py: do not edit",
           "# by hand. One page per floor of the tower; prices in dungeon tokens. kind: mount (the boss's pet, for those who",
           "# beat it), booster (fluxpets:booster_metier_<job>), book (EcoEnchants enchantment at a level), item (Flux item id).",
           "", "floors:"]
    for f, (dungeon, job, level, party, _, _) in sorted(TOWER.items()):
        rt = run_tokens(f)
        out.append(f"  {dungeon}:")
        out.append(f"    job: {job}")
        out.append("    items:")
        if dungeon in pets:
            out.append(f"    - {{kind: mount, pet: {pets[dungeon]}, price: {price(rt * mount_runs(f))}}}")
        out.append(f"    - {{kind: booster, job: {job}, price: {price(rt * BOOSTER_RUNS)}}}")
        for e in JOB_ENCHANTS[job]:
            rarity, top = rarities[e]
            out.append(f"    - {{kind: book, enchant: {e}, level: {top}, price: {price(rt * BOOK_RUNS[rarity])}}}")
        for piece in PIECES:
            out.append(f"    - {{kind: item, item: 'fluxrpg:{dungeon}_{piece}', price: {price(rt * SET_PIECE_RUNS)}}}")
    open(os.path.join(plugin, "shop.yml"), "w", encoding="utf-8").write("\n".join(out) + "\n")


def price(v):
    """Prices people remember: 2 significant digits, then rounded to 5."""
    if v < 50:
        return int(round(v))
    mag = 10 ** (len(str(int(v))) - 2)
    return int(round(v / mag) * mag)


def main():
    args = sys.argv[1:]
    plugin = args[0]
    data = B.load_all(plugin)
    rarities = enchants(plugin)
    # every enabled enchantment in one job, apart from the mystery-book exclusives
    placed = [e for es in JOB_ENCHANTS.values() for e in es]
    dup = {e for e in placed if placed.count(e) > 1}
    missing = set(rarities) - set(placed) - EXCLUSIVE
    unknown = set(placed) - set(rarities)
    if dup or missing or unknown:
        sys.exit(f"enchantments: twice {sorted(dup)}, in no job {sorted(missing)}, unknown {sorted(unknown)}")
    # reference: a level 50 player alone, hardest abilities, the median dungeon
    ref = {d: B.evaluate(plugin, d, MAX_LEVEL, 3, 1, 1, 1, data) for d, *_ in TOWER.values()}

    def median_boss_at(level, tier, party):
        def at(k):
            vals = [B.evaluate(plugin, d, min(level, MAX_LEVEL), tier, k, k, party, data)["boss"] for d, *_ in TOWER.values()]
            vals = [v for v in vals if v > 0]
            return statistics.median(vals) if vals else 0
        return at

    mb_ref = statistics.median([r["boss"] for r in ref.values() if r["boss"] > 0])
    m_ref = statistics.median(pressure(r, mb_ref) for r in ref.values())
    print(f"reference pressure (level 50 alone, rank 3, median dungeon): {m_ref:.2f}\n")
    print("floor dungeon       lvl party press.   k      tokens/run reward | trash  pull  boss  boss s | alone at floor level")
    results = []
    for f, (dungeon, job, level, party, target, tier) in sorted(TOWER.items()):
        mb = median_boss_at(level, tier, party)
        k = solve(plugin, dungeon, level, tier, party, target * m_ref, data, mb)
        r = B.evaluate(plugin, dungeon, min(level, MAX_LEVEL), tier, k, k, party, data)
        solo = B.evaluate(plugin, dungeon, min(level, MAX_LEVEL), tier, k, k, 1, data)
        # the recommended party meets more monsters (scaling.extra-mobs-per-player): each of them gets RUN_TOKENS
        reward = round(run_tokens(f) / max(1.0, r["tokens"] * (1 + 0.5 * (party - 1))), 2)
        p = pressure(r, mb(k)) / m_ref
        ps = pressure(solo, mb(k)) / m_ref
        results.append((f, dungeon, level, party, tier, k, reward))
        print(f"{f:5d} {dungeon:13s} {level:3d} {party:5d} {target:6.2f} {k:6.2f}   {run_tokens(f):7d}  {reward:6.2f} | "
              f"{r['trash'] / 60:4.1f}m {r['pull']:5.2f} {r['boss'] if r['boss'] else float('nan'):5.2f} {r['boss_time']:5.0f}s | "
              f"pressure {p:4.2f} (alone {ps:4.2f})")
    if "--write" in args:
        for f, dungeon, level, party, tier, k, reward in results:
            players_max = 4
            write_dungeon(plugin, f, dungeon, level, party, tier, k, reward, players_max)
        shop(plugin, results, rarities)
        print("\nwritten: dungeons/*.yml (floor, level, players, levels, reward), shop.yml")


if __name__ == "__main__":
    main()
