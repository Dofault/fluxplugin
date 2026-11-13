#!/usr/bin/env python3
"""Checks the special attacks of every monster of some dungeons at each difficulty level (from-level of its spells).

Rule of the owner (2026-11-11): a troop monster has 2 special attacks on Facile, 3 on Normal, 4 on Difficile; a
mini-boss or a boss at least as many. Abilities that are not attacks (phase changes, first moves, guards) do not count.
Usage: check_abilities.py <plugins/FluxDungeons> <mob folder>...      e.g. check_abilities.py ... roche pecheur
"""
import os
import sys

import yaml

NOT_ATTACKS = {"stone_skin", "shield", "heal", "enrage", "leap", "forge_overheat", "phase_shift", "entrance", "mole_phase", "copper_phase",
               "copper_roar", "sala_frenzy", "sala_awaken", "rally"}
WANTED = {1: 2, 2: 3, 3: 4}


def main():
    base, folders = sys.argv[1], sys.argv[2:]
    spells = {}
    for root, _, files in os.walk(os.path.join(base, "spells")):
        for f in files:
            if f.endswith(".yml"):
                spells[f[:-4]] = yaml.safe_load(open(os.path.join(root, f))) or {}
    bad = 0
    for folder in folders:
        d = os.path.join(base, "mobs", folder)
        for f in sorted(os.listdir(d)):
            mob = yaml.safe_load(open(os.path.join(d, f))) or {}
            counts = {}
            for lvl in (1, 2, 3):
                n = 0
                for ab in mob.get("abilities", []):
                    spec = dict(spells.get(ab.get("spell"), {})) if "spell" in ab else {}
                    spec.update(ab)
                    if spec.get("type") is None:
                        print(f"  {folder}/{f}: unknown spell {ab.get('spell')}")
                        bad += 1
                        continue
                    if spec.get("type") in NOT_ATTACKS or spec.get("opening") or spec.get("from-level", 1) > lvl:
                        continue
                    n += 1
                counts[lvl] = n
            ok = all(counts[l] >= WANTED[l] for l in WANTED)
            bad += not ok
            print(f"{'ok ' if ok else 'BAD'} {folder}/{f[:-4]:28} " + "  ".join(f"{l}:{counts[l]}" for l in counts))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
