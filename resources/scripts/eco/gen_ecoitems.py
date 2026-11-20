#!/usr/bin/env python3
"""Porte les outils des caisses (ItemsAdder influrewards) vers EcoItems.

Source lue : serveurs/03_semirp/plugins/ItemsAdder/contents/influrewards/config.yml
Sortie : <scratchpad>/out/EcoItems/items/outils/<palier>/<id>.yml
"""
import os
import re
import sys

import yaml

SRC = "/home/influencraft/serveurs/03_semirp/plugins/ItemsAdder/contents/influrewards/config.yml"
OUT = sys.argv[1]

TIERS = {
    "ordinaire": {"color": "&f", "label": "Ordinaire", "repairable": False},
    "tendance": {"color": "&a", "label": "Tendance", "repairable": False},
    "virale": {"color": "&#5A85EA", "label": "Virale", "repairable": True, "repair": "diamant"},
    "phenomene": {"color": "&#F0A800", "label": "Phénomène", "repairable": True},
}
TOOLS = ["pioche", "epee", "pelle", "hache", "houe"]
# id EcoItems -> id ItemsAdder (la pioche ordinaire s'appelait pioche_suffrage)
IA_ID = {"pioche_ordinaire": "pioche_suffrage"}

with open(SRC, encoding="utf-8") as f:
    ia = yaml.safe_load(f)["items"]


def mm_to_legacy(s):
    """MiniMessage simple de influrewards -> codes & (format des sets EcoArmor)."""
    s = s.replace("<gray><italic>", "&7&o")
    s = s.replace("<gray>", "&7").replace("</gray>", "")
    s = s.replace("<white>", "&f").replace("</white>", "")
    s = s.replace("<dark_gray>", "&8")
    s = re.sub(r"<#([0-9A-Fa-f]{6})>", lambda m: "&#" + m.group(1).upper(), s)
    s = re.sub(r"</#[0-9A-Fa-f]{6}>", "", s)
    assert "<" not in s, s
    # pas de tiret cadratin pour séparer des phrases (convention CLAUDE.md)
    s = s.replace(" — sauf", ", sauf").replace(" — creuser", " : creuser").replace(" complet —", " complet,")
    assert "—" not in s, s
    return s


def write(path, data, header):
    full = os.path.join(OUT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8") as f:
        for line in header.strip("\n").split("\n"):
            f.write(f"# {line}".rstrip() + "\n")
        f.write("\n")
        yaml.safe_dump(data, f, allow_unicode=True, sort_keys=False, width=1000)


count = 0
for tier, t in TIERS.items():
    for tool in TOOLS:
        eid = f"{tool}_{tier}"
        src = ia[IA_ID.get(eid, eid)]
        material = src["resource"]["material"].lower()
        name = re.sub(r"</?b>", "", re.sub(r"</?(white|green|#[0-9A-Fa-f]{6})>", "", src["display_name"]))
        lore = [mm_to_legacy(l) for l in src["lore"]]
        item_str = material
        components = {}
        if not t["repairable"]:
            dur = src["durability"]["max_durability"]
            item_str += f" max_damage:{dur}"
            # aucun matériau de réparation à l'enclume (équivalent de repairable: [] côté ItemsAdder) ;
            # la ligne "Irréparable" du lore reste le repère d'AnvilRestrictionListener (InfluBridge)
            components["repairable"] = {"items": []}
        item = {"item": item_str, "name": f"{t['color']}&l{name}"}
        if components:
            item["components"] = components
        item["lore"] = lore
        data = {"item": item, "slot": "mainhand", "effects": [], "conditions": []}
        repair = (
            f"réparable normalement au {t.get('repair', 'lingot de netherite')} (outil {t.get('repair', 'netherite').replace('lingot de ', '')} vanilla)"
            if t["repairable"]
            else "irréparable, durabilité réduite"
        )
        write(
            f"items/outils/{tier}/{eid}.yml",
            data,
            f"""{name} : outil de la caisse {t['label']}, porté depuis ItemsAdder (influrewards:{IA_ID.get(eid, eid)}).
Aucun enchantement ici : les caisses EcoCrates les ajoutent au give (ecoitems:{eid} efficiency:5 ...).
{repair.capitalize()}.""",
        )
        count += 1

# Arc tendance : pas d'item ItemsAdder (la caisse donnait un arc vanilla renommé, donc réparable et sans
# description). Mêmes règles que les autres outils Tendance : composant repairable vide + ligne « Irréparable »
# (repère d'AnvilRestrictionListener), durabilité native de l'arc.
BOW_DURABILITY = 384
write(
    "items/outils/tendance/arc_tendance.yml",
    {
        "item": {
            "item": f"bow max_damage:{BOW_DURABILITY}",
            "name": "&a&lArc tendance",
            "components": {"repairable": {"items": []}},
            "lore": [
                "",
                "&7&oSes tirs sont si propres qu'on jurerait",
                "&7&oqu'il vise tout seul.",
                "",
                "&7Rareté : &#37C44A✦ Unique",
                "&#FF5C5CIrréparable",
                f"&8{BOW_DURABILITY} durabilités",
            ],
        },
        "slot": "mainhand",
        "effects": [],
        "conditions": [],
    },
    """Arc tendance : arc de la caisse Tendance (auparavant un arc vanilla renommé dans la récompense EcoCrates).
Enchantements ajoutés par la caisse au give. Irréparable, durabilité native de l'arc.""",
)
count += 1
print("OK", count, "items")
