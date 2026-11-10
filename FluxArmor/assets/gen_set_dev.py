#!/usr/bin/env python3
"""Writes the staff test set (sets/dev.yml of FluxArmor on Labs, given with /set dev): every piece and the sword carry
every enchantment of production (EcoEnchants of 03_semirp, files starting with "_" are disabled ones) still active
on Labs that targets them, at its max level, plus vanilla enchantments at their max (all protections stacked); unbreakable. Full set bonus:
+75 % damage (the Phenomenon set gives +30 % since 2026-11-10). Run it again when production enchantments change.

Usage: gen_set_dev.py [server root, default /home/influencraft/serveurs]
"""
import glob
import os
import sys

import yaml

ROOT = sys.argv[1] if len(sys.argv) > 1 else "/home/influencraft/serveurs"
PROD = os.path.join(ROOT, "03_semirp/plugins/EcoEnchants/enchants")
LABS = os.path.join(ROOT, "04_labs/plugins/EcoEnchants/enchants")
OUT = os.path.join(ROOT, "04_labs/plugins/FluxArmor/sets/dev.yml")

custom = {}
for f in sorted(glob.glob(PROD + "/*.yml")):
    n = os.path.basename(f)[:-4]
    # disabled in production, or already removed from Labs (an enchantment leaving at go-live: Réflexion)
    if n.startswith("_") or not os.path.exists(os.path.join(LABS, n + ".yml")):
        continue
    d = yaml.safe_load(open(f, encoding="utf-8"))
    custom[n] = (d.get("targets") or [], d.get("max-level", 1))


def custom_for(target):
    return {n: lvl for n, (t, lvl) in custom.items() if target in t or "all" in t}


PROT = {"protection": 4, "fire_protection": 4, "blast_protection": 4, "projectile_protection": 4, "thorns": 3, "unbreaking": 3}
VANILLA = {
    "helmet": {**PROT, "respiration": 3, "aqua_affinity": 1},
    "chestplate": dict(PROT),
    "leggings": {**PROT, "swift_sneak": 3},
    # no frost_walker: it conflicts with depth_strider and is off in dungeons anyway
    "boots": {**PROT, "feather_falling": 4, "depth_strider": 3, "soul_speed": 3},
    "sword": {"sharpness": 5, "smite": 5, "bane_of_arthropods": 5, "fire_aspect": 2, "looting": 3, "sweeping_edge": 3,
              "knockback": 2, "unbreaking": 3},
}
NAMES = {"helmet": "Casque", "chestplate": "Plastron", "leggings": "Jambières", "boots": "Bottes"}
MODELS = {"helmet": ("influoutils:casque_phenomene", "none"),
          "chestplate": ("influoutils:plastron_phenomene", "influoutils:phenomene"),
          "leggings": ("influoutils:jambieres_phenomene", "influoutils:phenomene"),
          "boots": ("influoutils:bottes_phenomene", "influoutils:phenomene")}
BONUS = ["", "&#FF5C5C&lBONUS DU SET DE DEV", "&#FF5C5C✦ &fAugmente les dégâts de &#FF5C5C75%&f.",
         "&7&o(Nécessite les quatre pièces du set de dev.)"]

out = {"effects": [{"id": "damage_multiplier", "args": {"multiplier": 1.75},
                    "triggers": ["melee_attack", "bow_attack", "trident_hit"]}],
       "sounds": {"equip": {"sound": "item.armor.equip_netherite", "volume": 1, "pitch": 1},
                  "unequip": {"sound": "item.armor.equip_generic", "volume": 1, "pitch": 0.8}},
       "pieces": {}, "extras": {}}
for piece in ("helmet", "chestplate", "leggings", "boots"):
    model, equipment = MODELS[piece]
    out["pieces"][piece] = {"material": f"netherite_{piece}", "model": model, "equipment": equipment,
                            "name": f"&#FF5C5C&l{NAMES[piece]} de dev",
                            "lore": ["", "&7&oSet de test du staff : tous les", "&7&oenchantements de la prod au maximum."] + BONUS,
                            "unbreakable": True, "enchantments": {**VANILLA[piece], **custom_for(piece)}}
out["extras"]["sword"] = {"material": "netherite_sword", "model": "influoutils:epee_virale", "name": "&#FF5C5C&lÉpée de dev",
                          "lore": ["", "&7&oÉpée de test du staff : tous les", "&7&oenchantements de la prod au maximum."],
                          "unbreakable": True, "enchantments": {**VANILLA["sword"], **custom_for("sword")}}

HEAD = """# Set de dev (Labs), pour les tests du staff : /set dev. Écrit par FluxArmor/assets/gen_set_dev.py (dépôt fluxplugin)
# à partir des enchantements de la prod : relancer le script plutôt que de retoucher ce fichier à la main.
# Chaque pièce et l'épée portent tous les enchantements de la prod qui les ciblent, au niveau maximum, plus les
# enchantements vanilla au maximum (protections cumulées) ; incassables. Set complet : +75 % de dégâts.
"""
with open(OUT, "w", encoding="utf-8") as f:
    f.write(HEAD + yaml.safe_dump(out, allow_unicode=True, sort_keys=False, width=120))
print("écrit", OUT, {k: len(v["enchantments"]) for k, v in {**out["pieces"], **out["extras"]}.items()})
