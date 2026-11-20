#!/usr/bin/env python3
"""Familiers légendaires de boss (2026-11-14) : le boss de chaque donjon de FluxDungeons en familier.

Un par carrière qui a un donjon : adopté en battant le boss (premier passage) ou, 1 fois sur 300, dans un booster
de sa carrière (tirages.UNLOCK_TARGET). Monture dès MOUNT_LEVEL, attaque de monture dès ATTACK_LEVEL (MOUNTS), prend SLOTS emplacements de familier actif.
Lu par gen_familiers_boss.py (modèles, cartes, familiers, boosters) et gen_familiers.py (contenu des boosters).

Champs : id du familier, nom, modèle BetterModel du boss, carrière (EcoJobs), donjon (FluxDungeons), couleur du nom
(celle du boss), lore (2 lignes), animation de marche (le boss sans marche pivote sur place), taille visée.
"""

MOUNT_LEVEL = 1        # montable dès l'adoption (2026-11-15 ; 5 avant) : c'est la vitesse qui grandit avec le niveau
ATTACK_LEVEL = 3       # niveau du familier à partir duquel sa monture attaque
SPEED_START = 0.65     # part de la vitesse finale au niveau 1 (elle monte en ligne droite jusqu'au niveau max)
SLOTS = 3
# Monture = un vrai cheval invisible (FluxPets, 2026-11-15) : vitesse en blocs par tick (0,48 = 9,6 blocs/s, un
# cheval moyen ; pas de sprint à cheval), saut en blocs (2 : passe les clôtures).
# Taille : celle du boss dans son donjon (son propre modèle BetterModel, pas de copie réduite depuis le 2026-11-15).
MOUNT_SPEED, MOUNT_JUMP = 0.48, 2

BOSSES = [
    # id, nom, modèle du boss, carrière, donjon, couleur, lore, marche
    ("trex_cuivre", "T-Rex de Cuivre", "trex_cuivre", "mineur", "mineur", "#E3826C",
     ["Il a dévoré la mine entière.", "Les veines de cuivre, c'était le dessert."], "walk"),
    ("colosse_abime", "Colosse d'Abîme", "colosse_abime", "rochiste", "rochiste", "#7E6FA0",
     ["Il dormait sous la montagne", "depuis qu'elle existe."], "walk"),
    ("taupe_perion", "Roi Taupérion", "taupe_champs", "deblayeur", "deblayeur", "#B49A78",
     ["Sa tête de foreuse creuse", "plus vite que tu ne marches."], "walk"),
    ("hamster_moissonneur", "Hamster Moissonneur", "hamster_moissonneur", "cultivateur", "cultivateur", "#F0A800",
     ["Ses joues contiennent", "la récolte de tout un village."], "walk"),
    ("fleur_devoreuse", "Fleur Dévoreuse", "fleur_devoreuse", "herboriste", "herboriste", "#D9826B",
     ["Elle ne mord plus personne.", "Enfin, presque plus personne."], "idle_lick"),
    ("araignee_sylvestre", "Araignée Sylvestre", "araignee_sylvestre", "sylviculteur", "sylviculteur", "#8C6A3E",
     ["Huit pattes de bois mort", "et un cœur de vieille forêt."], "walk"),
    ("golem_fonderie", "Le Fondeur", "golem_fonderie", "fondeur", "fondeur", "#FF8A1E",
     ["Forgé dans le cœur du volcan,", "il a gardé sa chaleur."], "walk"),
    ("evocateur_demesure", "Évocateur Démesuré", "evocateur_demesure", "enchanteur", "enchanteur", "#B376E5",
     ["Il plie les dimensions", "pour ne pas avoir à marcher."], "walk"),
    ("loutre_marees", "Loutre des Marées", "loutre_marees", "pecheur", "pecheur", "#43B7B0",
     ["Elle brisait la glace des abysses.", "Maintenant, elle te suit partout."], "walk"),
    ("ossacorne", "Ossacorne", "ossacorne", "defenseur", "defenseur", "#E9D8B8",
     ["Les trophées des chasseurs,", "c'est lui qui les gardait."], "walk"),
]

BY_JOB = {b[3]: b for b in BOSSES}
BY_DUNGEON = {b[4]: b for b in BOSSES}

LEGENDARY_COLOR = "#F0A800"   # couleur de la rareté légendaire (RARITY_COLORS de gen_familiers.py)
CARD_NAMESPACE = "fluxpetboss"  # pack ItemsAdder des cartes de boss (gen_familiers_boss.py)


def card_entry(boss, job_label):
    """Carte d'un légendaire de boss pour content/cards.yml (même format que les autres cartes)."""
    pid, name, _, _, _, _, lore, _ = boss
    return {"item": f"{CARD_NAMESPACE}:carte_{pid}", "name": f"&{LEGENDARY_COLOR}{name}", "series": job_label,
            "rarity": "legendary", "pet": pid,
            "lore": [f"&#B376E5{lore[0]}", f"&#B376E5{lore[1]}", "", f"&#2ECF7BSérie &f{job_label}",
                     "&#F0A800Légendaire de boss"]}

# Effets des légendaires de boss : aucun, sauf ceux qui ont été validés (pas de doublon avec un enchantement ou un
# familier existant, utile, sans casser l'économie). Lignes de description ajoutées au niveau indiqué.
EFFECT_LINES = {
    # effet codé « dig » de FluxPets (effects.yml), réglé dans effets_familiers.JAVA
    "taupe_perion": (10, ["&fCreuse en 5 x 5&#B376E5 quand tu le montes", "&#B376E5(vers le bas si tu regardes le sol)."]),
}


# Monture de chaque boss (FluxPets, section mount du fichier du familier, 2026-11-15).
#   attack : (nom, animation du modèle, durée en ticks, ticks d'impact, portée en blocs, demi-angle en degrés
#            (180 = tout autour), dégâts au niveau ATTACK_LEVEL, dégâts par niveau en plus, recharge en secondes,
#            son au départ, son à l'impact, particule). L'attaque la plus spectaculaire de chaque boss qui tienne en
#            2 à 3 secondes (choix du propriétaire : « les plus impressionnantes », un coût en ressources viendra
#            plus tard) ; animations et ticks d'impact : ceux du boss dans son donjon (docs/technique/boss/).
#   fx     : effet de l'attaque (FluxPets MountKit). melee (défaut) : un coup devant. wave : une onde qui s'étend
#            autour du point d'impact (forward : blocs devant la monture), débris du bloc item. volley : count
#            projectiles affichés comme l'objet item, écartés de spread degrés, qui volent sur range blocs.
#   step   : sons d'un pas, joués à tour de rôle à chaque appui (« son|volume|hauteur », plusieurs ensemble avec « + »)
#   walk / run : (durée du cycle de l'animation en ticks, ticks où un pied se pose) : le son tombe sur l'appui
#   voice  : cris ou bruits joués de temps en temps en marche, (min, max) secondes entre deux
#   jump   : hauteur des sons de saut et de réception (communs à toutes : ceux du cheval)
#   swim   : la loutre seule : animations de nage (en mouvement, à l'arrêt), sons et cadence des brasses
# Sons du jeu seulement (aucun fichier son à nous) : chaque bête a sa matière (cuivre, pierre, os, bois, fonte...).
MOUNTS = {
    "trex_cuivre": {
        # Balayage tectonique : torsion 0-14, la queue fouette 14-22
        "attack": ("Balayage tectonique", "tail_sweep", 52, [18], 3.8, 180, 8, 1.5, 7,
                   "entity.ravager.roar|0.6|1.3", "entity.generic.explode|0.5|0.9", "crit"),
        "fx": {"effect": "wave", "item": "copper_ore"},
        "step": ["entity.ravager.step|0.9|0.55 + block.copper.step|0.9|0.6"],
        "walk": (28, [0, 14]), "run": (14, [0, 7]),
        "voice": (["entity.ravager.ambient|0.45|0.5"], 7, 15), "jump": 0.7},
    "colosse_abime": {
        # Piétinement : la patte avant droite retombe au tick 20, onde de choc circulaire
        "attack": ("Piétinement", "attack_stomp", 46, [20], 4.5, 180, 9, 1.5, 8,
                   "entity.warden.attack_impact|0.9|0.6", "entity.generic.explode|0.6|0.6", "explosion"),
        "fx": {"effect": "wave", "item": "deepslate"},
        "step": ["entity.warden.step|1|0.55 + block.deepslate.break|0.5|0.5"],
        "walk": (56, [0, 28]), "run": (16, [0, 8]),
        "voice": (["block.grindstone.use|0.3|0.4"], 5, 11), "jump": 0.6},
    "taupe_perion": {
        # Balayage foreur : la foreuse balaie de sa droite à sa gauche entre les ticks 22 et 32
        "attack": ("Balayage foreur", "sweep", 46, [24, 27, 30], 2.8, 80, 3, 0.6, 7,
                   "block.grindstone.use|0.9|0.6", "entity.player.attack.sweep|1|0.7", "sweep_attack"),
        "step": ["block.gravel.break|0.7|0.8", "block.rooted_dirt.break|0.7|0.7"],
        "walk": (24, [0, 12]), "run": (8, [0, 4]),
        "voice": (["block.grindstone.use|0.4|1.7"], 1.5, 3), "jump": 0.7},
    "hamster_moissonneur": {
        # Crachat de récolte : il inspire 0-12, trois salves aux ticks 14, 18, 22, joues vides au 24
        "attack": ("Crachat de récolte", "harvest_spit", 46, [14, 18, 22], 16, 180, 3, 0.6, 7,
                   "entity.fox.sniff|1|0.6", "entity.llama.spit|1|1.1", "crit"),
        "fx": {"effect": "volley", "item": "carrot", "count": 3, "spread": 12},
        "step": ["entity.rabbit.jump|0.6|1.4", "block.grass.step|0.7|1.5"],
        "walk": (12, [0, 6]), "run": (7, [0, 4]),
        "voice": (["entity.rabbit.ambient|0.6|1.2"], 5, 12), "jump": 1.1},
    "fleur_devoreuse": {
        # Épines : elle se cabre 0-22, trois crachats aux ticks 26, 34, 42
        "attack": ("Épines", "thorns", 60, [26, 34, 42], 16, 180, 4, 0.8, 8,
                   "block.big_dripleaf.tilt_up|1|0.5", "entity.llama.spit|1|0.6", "crit"),
        "fx": {"effect": "volley", "item": "pointed_dripstone", "count": 2, "spread": 8},
        "step": ["block.roots.step|0.9|0.6 + block.big_dripleaf.tilt_down|0.5|0.6",
                 "block.moss.step|0.9|0.6 + block.vine.step|0.6|0.7"],
        "walk": (9, [0]), "run": (9, [0]),
        "voice": (["block.azalea_leaves.break|0.6|0.5", "block.sweet_berry_bush.pick_berries|0.5|0.6"], 4, 9),
        "jump": 0.7},
    "araignee_sylvestre": {
        # Aiguilles de la forêt : abdomen dressé 0-22, une aiguille part aux ticks 22, 28, 34
        "attack": ("Aiguilles de la forêt", "volley", 56, [22, 28, 34], 20, 180, 5, 1.0, 7,
                   "block.wood.break|0.8|0.5", "entity.arrow.shoot|1|0.7", "crit"),
        "fx": {"effect": "volley", "item": "stick", "count": 2, "spread": 6},
        "step": ["entity.spider.step|0.8|1.3", "block.wood.step|0.7|1.6", "entity.spider.step|0.8|1.6",
                 "block.bamboo_wood.step|0.7|1.3"],
        "walk": (24, [0, 6, 12, 18]), "run": (11, [0, 3, 6, 8]),
        "voice": (["entity.spider.ambient|0.5|0.6", "block.wood.break|0.4|0.5"], 5, 11), "jump": 1.1},
    "golem_fonderie": {
        # Frappe sismique : marteau très haut 0-18, sommet qui tremble, impact au tick 30, onde au sol
        "attack": ("Frappe sismique", "slam", 52, [30], 5.0, 180, 10, 1.6, 9,
                   "entity.iron_golem.attack|1|0.5", "entity.generic.explode|0.7|0.6 + block.anvil.land|0.5|0.5", "lava"),
        "fx": {"effect": "wave", "item": "magma_block"},
        "step": ["entity.iron_golem.step|1|0.6 + block.anvil.place|0.2|0.5"],
        "walk": (36, [9, 27]), "run": (18, [0, 9]),
        "voice": (["block.lava.pop|0.7|0.6", "block.fire.ambient|0.7|0.7"], 3, 7), "jump": 0.6},
    "evocateur_demesure": {
        # Expansion forcée : la sphère grandit entre ses mains 9-23, libérée au tick 26
        "attack": ("Expansion forcée", "attack_expand", 46, [26], 3.2, 180, 9, 1.5, 8,
                   "entity.evoker.prepare_attack|1|0.7", "entity.enderman.teleport|0.8|0.5 + entity.generic.explode|0.4|1.4", "witch"),
        "fx": {"effect": "wave", "item": "amethyst_block", "forward": 5},
        "step": ["block.amethyst_block.step|1|0.6", "block.amethyst_block.chime|0.7|0.5"],
        "walk": (20, [0, 10]), "run": (12, [0, 6]),
        "voice": (["entity.evoker.ambient|0.5|0.6", "block.portal.ambient|0.25|1.4"], 6, 13), "jump": 0.8},
    "loutre_marees": {
        # Coup de queue en arc : elle bondit en tournant 20-26, la queue s'abat au tick 28
        "attack": ("Coup de queue", "attack_tail", 44, [28], 3.0, 180, 7, 1.4, 6,
                   "entity.fox.aggro|0.8|1.3", "entity.player.splash.high_speed|0.7|1.2", "splash"),
        "fx": {"effect": "wave", "item": "blue_ice"},
        "step": ["block.mud.step|0.9|1.3", "entity.frog.step|0.9|1.1"],
        "walk": (16, [0, 8]), "run": (10, [0, 3]),
        "voice": (["entity.dolphin.ambient|0.35|1.7", "entity.fox.sniff|0.6|1.4"], 5, 12), "jump": 1.1,
        # pas d'animation d'arrêt : swim_idle la fait flotter sur le dos (à l'envers sous un cavalier), elle nage sur place
        "swim": ("swim", None, ["entity.dolphin.swim|0.5|1.3", "entity.player.swim|0.4|1.4"], (24, [0, 12]))},
    "ossacorne": {
        # Volée d'os : trois éclats s'arrachent des plaques (6, 9, 12), verrouillés au 15, tirés aux ticks 20, 24, 28
        "attack": ("Volée d'os", "bone_volley", 44, [20, 24, 28], 24, 180, 5, 1.0, 7,
                   "entity.skeleton_horse.ambient|0.8|0.6", "entity.skeleton.shoot|1|0.6", "crit"),
        "fx": {"effect": "volley", "item": "bone", "count": 1, "spread": 0},
        "step": ["entity.skeleton.step|1|0.6 + block.bone_block.hit|0.5|0.7", "block.bone_block.step|1|0.6"],
        "walk": (16, [0, 8]), "run": (12, [0, 1, 4, 5]),
        "voice": (["entity.skeleton_horse.ambient|0.6|0.6"], 6, 13), "jump": 0.7},
}
