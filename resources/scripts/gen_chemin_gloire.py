#!/usr/bin/env python3
"""Génère EpicAchievements/rewards.yml : une section par niveau (60) du Chemin de la Gloire."""
import sys

OUT = sys.argv[1]

KEYS = {"tendance": "&aClé Tendance", "ordinaire": "&fClé Ordinaire"}

# Livres des niveaux normaux (position 2 de chaque bloc de 4) : (rareté, quantité) par tranche de 5 niveaux
BOOK_BY_BLOCK = [
    ("SIMPLE", 2), ("SIMPLE", 3), ("UNIQUE", 2), ("UNIQUE", 3),
    ("ELITE", 2), ("ELITE", 3), ("ULTIMATE", 2), ("ULTIMATE", 3),
    ("LEGENDARY", 1), ("LEGENDARY", 2), ("LEGENDARY", 2), ("LEGENDARY", 3),
]

# Grands paliers (tous les 5 niveaux) : clé Tendance + livre. FABLED uniquement à 30/40/50/60.
SPECIAL = {
    5: (1, "UNIQUE", 1), 10: (2, "UNIQUE", 2), 15: (2, "ELITE", 1), 20: (2, "ELITE", 2),
    25: (3, "ULTIMATE", 1), 30: (3, "FABLED", 1), 35: (3, "ULTIMATE", 2), 40: (3, "FABLED", 1),
    45: (3, "LEGENDARY", 2), 50: (3, "FABLED", 1), 55: (3, "LEGENDARY", 3), 60: (3, "FABLED", 1),
}


def key(kid, n):
    return [
        f'      - type: command',
        f'        command: "excellentcrates key give %player_name% {kid} {n}"',
        f'        description: "{KEYS[kid]} &8(x{n})"',
    ]


def book(group, n):
    return [
        f'      - type: command',
        f'        command: "ae givercbook {group} %player_name% {n}"',
        f'        description: "&r&f%img_{group.lower()}% &fEnchantement Aléatoire &8(x{n})"',
    ]


def vault(n):
    return [f'      - type: vault', f'        amount: "{n}"']


def influence(n):
    return [f'      - type: player_points', f'        amount: "{n}"']


def normal_extra(level):
    block, pos = divmod(level, 5)  # pos 1..4 (0 = grand palier)
    if pos == 1:
        return key("tendance", 1)
    if pos == 2:
        return book(*BOOK_BY_BLOCK[block])
    if pos == 3:
        return key("ordinaire", 3)
    return key("tendance", 2 if level > 30 else 1)


NORMAL_ITEMS = '''    locked:
      material: "itemsadder:lock"
      name: "#E71D36Récompense &8— #EB7100Niveau {level} &8[#E71D36Verrouillée&8]"
      lore:
        - ""
        - "&7%img_impact% #B376E5Couronne requise &8► #D92626{points}"
        - ""
        - "#B376E5Récompenses &8►"
        - "{rewards}"
        - ""
        - "#E71D36La récompense est verrouillée."
        - ""
    claimed:
      material: "itemsadder:checkmark"
      name: "&aNiveau {level} &8— #B376E5Récompense &8[&2&l✔&8]"
      lore:
        - ""
        - "&7%img_impact% #B376E5Couronne requise &8► #D92626{points}"
        - ""
        - "#B376E5Récompenses &8►"
        - "{rewards}"
        - ""
        - "&aTu as déjà récupéré cette récompense."
        - ""
    unclaimed:
      material: "itemsadder:unlock"
      name: "#FFD94DNiveau {level} &8— #B376E5Récompense"
      lore:
        - ""
        - "&7%img_impact% #B376E5Couronne requise &8► #D92626{points}"
        - ""
        - "#B376E5Récompenses &8►"
        - "{rewards}"
        - ""
        - "&7%img_left_click% &7Récupérer la récompense"
        - ""
      glow: true'''

SPECIAL_ITEMS = '''    locked:
      material: "itemsadder:chest"
      name: "#E71D36Récompense Spéciale &8— #EB7100Niveau {level} &8[#E71D36Verrouillée&8]"
      lore:
        - ""
        - "&7%img_impact% #B376E5Couronne requise &8► #D92626{points}"
        - ""
        - "#B376E5Récompenses &8►"
        - "{rewards}"
        - ""
        - "#E71D36La récompense est verrouillée."
        - ""
    claimed:
      material: "itemsadder:chest_checkmark"
      name: "&aNiveau {level} &8— #FFD94DRécompense Spéciale &8[&2&l✔&8]"
      lore:
        - ""
        - "&7%img_impact% #B376E5Couronne requise &8► #D92626{points}"
        - ""
        - "#B376E5Récompenses &8►"
        - "{rewards}"
        - ""
        - "&aTu as déjà récupéré cette récompense."
        - ""
    unclaimed:
      material: "itemsadder:chest_item"
      name: "#FFD94DNiveau {level} &8— #FFD94DRécompense Spéciale"
      lore:
        - ""
        - "&7%img_impact% #B376E5Couronne requise &8► #D92626{points}"
        - ""
        - "#B376E5Récompenses &8►"
        - "{rewards}"
        - ""
        - "&7%img_left_click% &7Récupérer la récompense"
        - ""
      glow: true'''

HEADER = '''# Chemin de la Gloire — rework 2026-09-11
#
# Une section par niveau : le plugin ne permet des récompenses différentes que par
# groupe de niveaux (levels.from/to/step), d'où 60 sections. Fichier GÉNÉRÉ par
# scripts/gen_chemin_gloire.py : modifier le script plutôt que 60 blocs à la main.
#
# BitBlock (vault) inchangé : normal 2n² + 10n + 25 | spécial 6n² + 40n + 100
# Influence (player_points) = montant BitBlock du niveau / 3, arrondi (valeur écrite en dur).
# Normal : BitBlock + Influence + UNE récompense (cycle par bloc de 4 niveaux) :
#   Clé Tendance x1 | livres aléatoires (rareté croissante) | Clé Ordinaire x3 | Clé Tendance x1 (x2 après 30)
# Spécial : BitBlock + Influence + Clé Tendance (x1..x3) + livre. Livre FABLED uniquement aux
#   niveaux 30/40/50/60, jamais plus de 3 livres par niveau.

reward-descriptions:
  points: "&7%img_impact% #D92626+{amount}"
  vault: "&7%img_bitblock% &f+{amount} BitBlock"
  experience: "&7+&e {amount} &7Expérience"
  experience_levels: "&7+&e {amount} &7Niveaux d'expérience"
  player_points: "&7%img_influence% #FFD94D+{amount} Influence"

rewards:'''

lines = [HEADER]
for lvl in range(1, 61):
    special = lvl % 5 == 0
    lines.append(f"  niveau_{lvl}:")
    lines.append(f"    levels:\n      from: {lvl}\n      to: {lvl}")
    lines.append('    required-points: "25 * {level} * {level}"')
    lines.append("    rewards:")
    if special:
        nk, group, nb = SPECIAL[lvl]
        bb = 6 * lvl * lvl + 40 * lvl + 100
        body = vault(bb) + influence(round(bb / 3)) + key("tendance", nk) + book(group, nb)
    else:
        bb = 2 * lvl * lvl + 10 * lvl + 25
        body = vault(bb) + influence(round(bb / 3)) + normal_extra(lvl)
    lines += body
    lines.append(SPECIAL_ITEMS if special else NORMAL_ITEMS)
    lines.append("")

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
