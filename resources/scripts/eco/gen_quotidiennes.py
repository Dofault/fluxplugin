#!/usr/bin/env python3
"""Missions quotidiennes d'EcoBattlepass (Labs) : 7 familles à variantes.

Chaque famille est une quête (task-amount 1) : une variante tirée au hasard à chaque renouvellement (06h00,
fait par FluxTools, module pass-renewal), la même pour tout le serveur. Les points sont ceux de la famille,
quelle que soit la variante : ce script recopie la ligne « Récompense » de chaque tâche depuis FAMILLES.

Tâches testées en jeu par Entr0pik le 2026-11-11 (lots de test 1 à 10, cf.
docs/technique/eco-items-pets-battlepass.md §Refonte des missions quotidiennes).

Usage : python3 scripts/eco/gen_quotidiennes.py   puis   /ecobattlepass reload
Modifier une famille, une quantité ou des points = modifier FAMILLES ici et relancer, jamais les YAML à la main.
"""
import re
import shutil
from pathlib import Path

ROOT = Path("/home/influencraft/serveurs/04_labs/plugins/EcoBattlepass")
TASKS = ROOT / "tasks/quotidiennes"
QUESTS = ROOT / "quests/quotidiennes"
CATEGORY = ROOT / "categories/pass/quotidiennes.yml"

# id de quête : (nom affiché, icône, points, [(tâche, quantité)])
# Budget : 235 points par jour, comme les 9 anciennes quotidiennes.
FAMILLES = {
    "recolte": ("Récolte du jour", "oak_log", 30, [
        ("b_chene", 32), ("b_bouleau", 32), ("b_sapin", 32), ("b_acajou", 32), ("b_acacia", 32),
        ("b_chene_noir", 32), ("b_paletuvier", 32), ("b_chene_pale", 32), ("d_cerisier_bois", 32),
        ("d_pommes", 1), ("d_cacao", 10), ("d_canne", 32), ("d_miel", 3), ("d_mycelium", 32),
    ]),
    "moisson": ("Moisson du jour", "wheat", 30, [
        ("d_ble", 32), ("c_carotte", 32), ("c_patate", 32), ("c_betterave", 32), ("d_verrues", 16),
        ("d_citrouille", 16), ("d_poudre_os", 10),
    ]),
    "chasse": ("Chasse du jour", "iron_sword", 35, [
        ("h_zombie", 8), ("h_squelette", 6), ("d_creeper", 5), ("d_araignee", 6), ("d_enderman", 3),
        ("d_sorciere", 1), ("d_phantom", 2), ("d_pillards", 5), ("d_breeze", 3), ("d_cube_soufre", 4),
        ("d_hache", 8), ("d_masse", 8), ("d_headshot", 3), ("d_trident", 5),
    ]),
    "mine": ("Mine du jour", "iron_pickaxe", 40, [
        ("m_charbon", 24), ("m_fer", 12), ("m_cuivre", 16), ("m_or", 6), ("m_redstone", 8), ("m_lapis", 6),
        ("d_diamant", 3), ("d_emeraude", 2), ("d_quartz", 16), ("d_soie", 8), ("d_amethyste", 6),
        ("d_pics_soufre", 12),
    ]),
    "elevage": ("Élevage du jour", "wheat_seeds", 30, [
        ("e_vache", 3), ("e_mouton", 3), ("e_cochon", 3), ("e_poule", 3), ("e_lapin", 2), ("d_abeilles", 2),
        ("d_tonte", 8), ("d_moutons_noirs", 3), ("d_dompteur", 1),
    ]),
    "artisanat": ("Artisanat du jour", "crafting_table", 30, [
        ("d_cookies", 16), ("d_carotte_doree", 8), ("d_gateau", 2), ("k_pain", 12), ("k_tarte", 4),
        ("d_verre", 32), ("d_pierre", 64), ("d_rotisseur", 16), ("f_brique", 32), ("f_charbon_bois", 16),
        ("d_potions", 6), ("d_enchanteur", 2), ("d_garniture", 1), ("d_citrouille_sculptee", 4), ("d_baies", 5),
    ]),
    "aventure": ("Aventure du jour", "compass", 40, [
        ("d_perche", 3), ("d_thon", 4), ("d_uniques", 10), ("d_cerisier", 1), ("d_elytres", 1000),
        ("d_cavalier", 300), ("d_profondeurs", 200), ("d_nether", 1), ("d_pots", 3), ("d_usure", 1),
        ("d_niveaux", 5),
    ]),
}

# Testées puis écartées : gardées pour mémoire (une mission de semaine pourrait les reprendre), jamais tirées.
ECARTEES = {
    "d_perles": "lancer des perles en l'air en boucle remplit la mission sans effort",
    "d_totem": "consomme un totem d'immortalité, objet rare que la plupart des joueurs n'ont pas (plutôt en mission de semaine)",
}


def find(task):
    hits = list(TASKS.rglob(f"{task}.yml"))
    if len(hits) != 1:
        raise SystemExit(f"tâche {task} : {len(hits)} fichier(s) trouvé(s)")
    return hits[0]


def main():
    used = {}
    for fam, (_, _, pts, tasks) in FAMILLES.items():
        for task, _ in tasks:
            if task in used:
                raise SystemExit(f"{task} est dans deux familles ({used[task]}, {fam})")
            used[task] = fam

    # tâches : rangées par famille, ligne « Récompense » alignée sur les points de la famille
    for fam, (_, _, pts, tasks) in FAMILLES.items():
        (TASKS / fam).mkdir(exist_ok=True)
        for task, _ in tasks:
            src = find(task)
            text = re.sub(r"Récompense &8► &f\+\d+ points", f"Récompense &8► &f+{pts} points", src.read_text())
            dst = TASKS / fam / f"{task}.yml"
            dst.write_text(text)
            if src != dst:
                src.unlink()
    (TASKS / "ecartees").mkdir(exist_ok=True)
    for task, why in ECARTEES.items():
        src = find(task)
        text = src.read_text()
        if not text.startswith("# ÉCARTÉE"):
            text = f"# ÉCARTÉE (jamais tirée) : {why}.\n" + text
        dst = TASKS / "ecartees" / f"{task}.yml"
        dst.write_text(text)
        if src != dst:
            src.unlink()

    # quêtes : une par famille, plus rien d'autre dans le dossier
    for old in QUESTS.rglob("*.yml"):
        old.unlink()
    for sub in sorted(QUESTS.rglob("*"), reverse=True):
        if sub.is_dir():
            sub.rmdir()
    for fam, (name, icon, pts, tasks) in FAMILLES.items():
        body = "".join(f"- id: {t}\n  xp: {n}\n" for t, n in tasks)
        (QUESTS / f"{fam}.yml").write_text(
            f"# Généré par scripts/eco/gen_quotidiennes.py : famille « {name} », une variante tirée au hasard\n"
            f"# chaque jour à 06h00 (FluxTools pass-renewal), la même pour tout le serveur. Ne pas éditer à la main.\n"
            f"display:\n  item: {icon}\n  display-name: '&#FFD94D{name}'\n  description: []\n"
            f"battlepass-points: {pts}\nbattlepass-tier: free\ntasks:\n{body}task-amount: 1\n")

    # catégorie : liste des 7 familles
    text = CATEGORY.read_text()
    head = text[:text.index("\nquests:")]
    CATEGORY.write_text(head + "\nquests:\n# Généré par scripts/eco/gen_quotidiennes.py (7 familles à variantes).\n"
                        + "".join(f"- id: {fam}\n" for fam in FAMILLES))

    # tâches quotidiennes plus utilisées (anciennes q1 à q22, dossiers de lots vidés)
    keep = set(used) | set(ECARTEES)
    for f in TASKS.rglob("*.yml"):
        if f.stem not in keep:
            f.unlink()
    for sub in sorted(TASKS.rglob("*"), reverse=True):
        if sub.is_dir() and not any(sub.iterdir()):
            sub.rmdir()

    total = sum(p for _, _, p, _ in FAMILLES.values())
    print(f"{len(FAMILLES)} familles, {len(used)} variantes, {len(ECARTEES)} écartées, {total} points par jour")


if __name__ == "__main__":
    main()
