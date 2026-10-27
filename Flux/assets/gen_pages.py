#!/usr/bin/env python3
"""Génère les pages « à chemin » du menu principal Flux (/menu2, Labs).

    python3 gen_pages.py <dossier menus/ de Flux> <config FluxReferral> <config FluxRewards>

Écrit menus/_pages/{parrainage,pass,recompenses,vote}.yml et remplace, dans menus/main.yml, le bloc
entre « # >>> gen_pages » et « # <<< gen_pages » (boutons du bas qui ouvrent ces pages).
À relancer après un changement de paliers (parrainage), de jours / paliers de temps (récompenses)
ou du nombre de paliers du pass. Puis /fluxmenu reload.

Disposition commune (zone de l'inventaire masqué) :
  rangée 54-62 : icône de la page en cours au centre (58, allumée : survol = page où l'on
                 se trouve, clic = retour au menu)
  rangée 63-71 : précédent (63), chemin sur 64-70, suivant (71)
  rangée 72-80 : branches (paliers, piste Prestige du pass)
  barre 81-89  : boutons d'information et d'action (83, 85, 87)
Une page = 7 étapes ; précédent / suivant passent d'une page à l'autre sans animation ([page-swap]).
"""
import os
import re
import sys

PAR_PAGE = 7
CHEMIN = [64 + k for k in range(PAR_PAGE)]
PREV, NEXT, RETOUR = 63, 71, 58
# Boutons d'action alignés dans la barre d'action (une case sur deux, centrés).
A1, A2, A3 = 83, 85, 87

TITRE = '":offset_-8::menu2_anim_5:"'
ANIMATION = """    animation:
      frames:
        - ":offset_-8::menu2_anim_2:"
        - ":offset_-8::menu2_anim_3:"
        - ":offset_-8::menu2_anim_4:"
      interval: 2
"""
OR, VIOLET = "&#FFD94D", "&#B376E5"
CLIC_G, CLIC_D = "&r&7%img_left_click% &f", "&r&7%img_right_click% &f"
# Action d'un palier à récupérer : en vert (remplace l'ancienne ligne « À récupérer ! »).
CLIC_G_OK = "&r&7%img_left_click% &#2ECF7B"
ROUGE = "&#f0716f"   # couleur d'erreur : « Pas encore atteint. »

# Marqueur de ligne « brute » : passée telle quelle à Flux, sans le préfixe │. Sert aux
# listes de longueur variable fournies par un plugin tiers (ex : reward-lore d'EcoBattlepass,
# déjà préfixées de leur propre puce ➤) — une ligne marquée qui contient un placeholder et
# résout vide est automatiquement sautée par Flux (ItemFactory), pas celles qu'on écrit ici.
_RAW = "\x00"


def brut(s):
    return _RAW + s


def q(s):
    return '"' + s.replace('"', "'") + '"'


def lore(lignes, actions=()):
    """Style /menu2 : │ devant la description (sauf lignes brut()), ├ / └ devant les actions."""
    out = [l[len(_RAW):] if l.startswith(_RAW) else f"{OR}│ {VIOLET}{l}" for l in lignes]
    for i, a in enumerate(actions):
        out.append(f"{OR}{'└' if i == len(actions) - 1 else '├'} {a}")
    if not actions and out:
        # Dernière ligne à porter un │ (une ligne brut() en queue, ex : reward-lore dont le
        # dernier index est vide à l'exécution, ne doit jamais rester comme terminaison).
        for i in range(len(out) - 1, -1, -1):
            if "│" in out[i]:
                out[i] = out[i].replace("│", "└", 1)
                break
    return out


def item(cle, material, nom, slot, lignes, actions=(), clics=None, priorite=None, conditions=(),
         glow=False, cacher=False, quantite=None):
    y = [f"      {cle}:", f"        material: {q(material)}"]
    if quantite is not None:
        # Numéro du palier affiché en quantité sur l'icône (1 à 99), quel que soit son état.
        y.append(f'        amount: "{quantite}"')
    if priorite is not None:
        y.append(f"        priority: {priorite}")
    if conditions:
        y.append("        view-requirements:")
        y += [f"          - {q(c)}" for c in conditions]
    if glow:
        y.append("        glow: true")
    if cacher:
        y.append("        hide-tooltip: true")
    y.append(f"        name: {q(OR + nom)}")
    y.append(f"        slots: [{slot}]")
    lo = lore(lignes, actions)
    if lo:
        y.append("        lore:")
        y += [f"          - {q(l)}" for l in lo]
    if clics:
        y.append("        actions:")
        for k, v in clics.items():
            y.append(f"          {k}: [{', '.join(q(a) for a in v)}]")
    return "\n".join(y)


def page(pid, items, chemins, titre=TITRE, animation=True):
    y = [f"  {pid}:", f"    title: {titre}"] + ([ANIMATION.rstrip("\n")] if animation else [])
    if chemins:
        y.append("    paths:")
        for cid, c in chemins.items():
            y.append(f"      {cid}:")
            for k, v in c.items():
                y.append(f"        {k}: {v}")
    y.append("    items:")
    y += ["", *[i + "\n" for i in items]]
    return "\n".join(y)


def navigation(prefixe, n, total, onglet_slot, onglet_mat, onglet_nom, apropos):
    """Bouton d'onglet allumé, précédent / suivant (tous trois sans infobulle), icône de la page."""
    pid = f"{prefixe}_{n}"
    its = [item("onglet", onglet_mat, onglet_nom, onglet_slot, [], clics={"left": [f"[page-toggle] {pid}"]},
                glow=True, cacher=True)]
    if n > 1:
        its.append(item("precedent", "itemsadder:influitems:btn_prev", "Page précédente", PREV, [],
                        clics={"left": [f"[page-swap] {prefixe}_{n - 1}", "[sound] minecraft:item.book.page_turn 1 1"]},
                        cacher=True))
    if n < total:
        its.append(item("suivant", "itemsadder:influitems:btn_next", "Page suivante", NEXT, [],
                        clics={"left": [f"[page-swap] {prefixe}_{n + 1}", "[sound] minecraft:item.book.page_turn 1 1"]},
                        cacher=True))
    its.append(icone_page(onglet_mat, onglet_nom, apropos))
    return its


def icone_page(material, nom, apropos):
    """Case 58 : icône de la page en cours (allumée). « À propos » de la fonctionnalité :
    courte explication pour le joueur, aucune interaction (on referme avec le bouton du bas)."""
    return item("retour", material, nom, RETOUR, list(apropos), glow=True)


APROPOS = {
    "parrainage": ["Invite tes amis sur le serveur.", "Quand ils ont assez joué,", "vous êtes récompensés tous",
                   "les deux, avec des bonus", "à chaque palier."],
    "pass": ["Gagne des points en jouant", "et avec les missions de la", "semaine. Chaque palier donne",
             "une récompense, le Prestige", "ajoute une seconde piste."],
    "recompenses": ["Connecte-toi chaque jour et", "joue pour débloquer des", "récompenses : une par jour",
                    "et des paliers de temps de jeu.", "", "Jour de connexion : &f%recompenses_jour%&7/&f%recompenses_cycle%"],
    "vote": ["Vote pour le serveur chaque", "jour : chaque vote donne des", "tickets à miser sur les lots",
             "de la tombola, sur le site."],
}


def connecteurs(n, total, slots, color, progress, debut, nb, prev=None, nxt=None):
    """Relie visuellement le chemin au bouton précédent / suivant quand ils existent :
    63-64 et 70-71 sont adjacents, sur la même rangée (cf. gabarit de navigation()). Allumé
    seulement si la progression a vraiment atteint ce point (jamais allumé par défaut) :
    - un lien à 2 cases s'allume quand (progression - offset) >= 2 (cf. PathRenderer.collect) ;
    - côté précédent, on veut l'allumer dès que la 1ʳᵉ étape de CETTE page est atteinte
      (comme un « start » : progression brute >= debut+1), d'où offset = debut-1 ;
    - côté suivant, seulement une fois TOUTES les étapes de cette page atteintes (comme un
      « end » : progression brute >= debut+nb), d'où offset = debut+nb-2.
    """
    out = {}
    prev = PREV if prev is None else prev
    nxt = NEXT if nxt is None else nxt
    if n > 1:
        out["nav_prev"] = {"slots": f"[{prev}, {slots[0]}]", "progress": progress,
                           "offset": debut - 1, "color": color, "start": "false", "end": "false"}
    if n < total:
        out["nav_next"] = {"slots": f"[{slots[-1]}, {nxt}]", "progress": progress,
                           "offset": debut + nb - 2, "color": color, "start": "false", "end": "false"}
    return out


def fichier(chemin, contenu, entete):
    os.makedirs(os.path.dirname(chemin), exist_ok=True)
    with open(chemin, "w", encoding="utf-8") as f:
        f.write(f"# Généré par plugins/fluxplugin/Flux/assets/gen_pages.py : ne pas modifier à la main.\n# {entete}\n\npages:\n\n{contenu}\n")


def bouton_principal(cle, material, nom, slot, lignes, action_label, pages):
    """Bouton du bas du menu : ouvre la page qui contient la progression du joueur.
    pages = [(page_id, condition ou None)], la première condition vraie gagne."""
    out = []
    for i, (pid, cond) in enumerate(pages):
        out.append(item(f"{cle}_{i + 1}", material, nom, slot, lignes, [CLIC_G + action_label],
                        {"left": [f"[page-toggle] {pid}"]}, priorite=i + 1,
                        conditions=[cond] if cond else ()))
    return out


# ════════════════════════════════════════════════════════════════════════
# Parrainage
# ════════════════════════════════════════════════════════════════════════

def lire_paliers_parrainage(chemin):
    seuils = [int(m) for m in re.findall(r"^\s*-\s*filleuls:\s*(\d+)", open(chemin, encoding="utf-8").read(), re.M)]
    return sorted(seuils)


def parrainage(seuils):
    etapes = max(PAR_PAGE, -(-max(seuils or [1]) // PAR_PAGE) * PAR_PAGE)
    total = etapes // PAR_PAGE
    pages = []
    for n in range(1, total + 1):
        debut = (n - 1) * PAR_PAGE
        its = navigation("parrainage", n, total, 46, "itemsadder:influitems:referral", "Parrainage", APROPOS["parrainage"])
        branches = []
        for k, slot in enumerate(CHEMIN):
            f = debut + k + 1
            pluriel = "s" if f > 1 else ""
            # Rien n'est distribué automatiquement (parrain comme palier) : 3 états, comme pass
            # et récompenses. Chaque case "disponible" récupère SA PROPRE récompense (une par une,
            # jamais tout d'un coup) : filleul -> /parrainage recuperer filleul (une récompense
            # fongible, peu importe la case cliquée), palier -> /parrainage recuperer palier <n>.
            recompense = ["%parrainage_recompense_parrain_1%", "%parrainage_recompense_parrain_2%"]
            etat = f"%parrainage_filleul_{f}_etat%"
            its.append(item(f"filleul{f}_ok", "itemsadder:influitems:checkmark", f"{f} filleul{pluriel}", slot,
                            recompense + ["&#2ECF7BRécupéré."], priorite=1,
                            conditions=[f"placeholder: {etat} == obtenu"], quantite=f))
            its.append(item(f"filleul{f}_dispo", "itemsadder:influitems:chest_item", f"{f} filleul{pluriel}", slot,
                            recompense, [CLIC_G_OK + "Récupérer"],
                            {"left": ["[player] parrainage recuperer filleul", "[sound] minecraft:entity.player.levelup 1 1.4", "[refresh]"]},
                            priorite=2, conditions=[f"placeholder: {etat} == disponible"], quantite=f))
            its.append(item(f"filleul{f}", "itemsadder:influitems:lock",
                            f"%parrainage_filleuls%/{f} filleul{pluriel}", slot,
                            recompense + [ROUGE + "Pas encore atteint."], priorite=3, quantite=f))
            if f in seuils:
                i = seuils.index(f) + 1
                bas = slot + 9
                branches.append(f'"{slot} > {bas}"')
                desc = ["&fBonus de palier :", f"%parrainage_palier_{i}_ligne_1%", f"%parrainage_palier_{i}_ligne_2%"]
                etat_p = f"%parrainage_palier_{i}_etat%"
                its.append(item(f"palier{f}_ok", "itemsadder:influitems:chest_checkmark", f"Palier {f} filleuls", bas,
                                desc + ["&#2ECF7BObtenu, récompense récupérée."], priorite=1,
                                conditions=[f"placeholder: {etat_p} == obtenu"]))
                its.append(item(f"palier{f}_dispo", "itemsadder:influitems:chest_item", f"Palier {f} filleuls", bas,
                                desc, [CLIC_G_OK + "Récupérer"],
                                {"left": [f"[player] parrainage recuperer palier {f}", "[sound] minecraft:entity.player.levelup 1 1.4", "[refresh]"]},
                                priorite=2, conditions=[f"placeholder: {etat_p} == disponible"]))
                its.append(item(f"palier{f}", "itemsadder:influitems:lock", f"Palier {f} filleuls", bas,
                                desc + [ROUGE + "Pas encore atteint."], priorite=3))
        its += parrainage_actions()
        chemin = {"slots": "[" + ", ".join(map(str, CHEMIN)) + "]", "progress": '"%parrainage_filleuls%"',
                  "offset": debut, "color": "vert", "start": "false",
                  "end": "false"}
        if branches:
            chemin["branches"] = "[" + ", ".join(branches) + "]"
        chemins = {"filleuls": chemin,
                   **connecteurs(n, total, CHEMIN, "vert", '"%parrainage_filleuls%"', debut, PAR_PAGE)}
        pages.append(page(f"parrainage_{n}", its, chemins))
    ouverture = [(f"parrainage_{n}", f"placeholder: %parrainage_filleuls% < {n * PAR_PAGE}") for n in range(1, total)]
    ouverture.append((f"parrainage_{total}", None))
    principal = bouton_principal("parrainage", "itemsadder:influitems:referral", "Parrainage", 46,
                                 ["Invite tes amis avec ton", "adresse perso : &f%parrainage_filleuls% " + VIOLET + "filleul(s).",
                                  "À récupérer : &f%parrainage_a_recuperer%"],
                                 "Ouvrir", ouverture)
    return "\n".join(pages), principal


def parrainage_actions():
    # Barre d'action (A1, A2, A3) : l'adresse se copie, le classement s'ouvre, "ton parrain" est
    # une info passive (pas de clic) ; plus de bouton "récupérer tout" (chaque case du chemin
    # récupère la sienne) ni "en attente" (déjà visible dans la progression du chemin).
    return [
        item("adresse", "itemsadder:influitems:copy", "Ton adresse perso", A1,
             ["&f%parrainage_adresse%", "Tes amis l'ajoutent comme", "serveur : après &f%parrainage_temps_requis%" + VIOLET + " de jeu,",
              "vous êtes récompensés."],
             [CLIC_G + "Copier"], {"left": ["[copy] %parrainage_adresse%"]}),
        item("parrain_ok", "itemsadder:influitems:accept", "Ton parrain", A2,
             ["&f%parrainage_parrain%", "Merci de l'avoir rejoint !"], priorite=1,
             conditions=["placeholder: %parrainage_a_parrain% == oui"]),
        item("parrain_attente", "itemsadder:influitems:tracking", "Ton parrain", A2,
             ["&f%parrainage_parrain_attente%", "Joue &f%parrainage_attente_progression%",
              "pour valider ton parrainage."], priorite=2,
             conditions=["placeholder: %parrainage_a_parrain% == attente"]),
        item("classement", "itemsadder:influitems:leaderboard", "Meilleurs parrains", A3,
             ["&#F0A800① &f%parrainage_top_1_pseudo% &8· &f%parrainage_top_1_filleuls%",
              "&#D9D9D9② &f%parrainage_top_2_pseudo% &8· &f%parrainage_top_2_filleuls%",
              "&#C97B3A③ &f%parrainage_top_3_pseudo% &8· &f%parrainage_top_3_filleuls%",
              "Ton rang : &f%parrainage_rang%"],
             [CLIC_G + "Classement complet"], {"left": ["[player] parrainage top", "[close]"]}),
    ]


# ════════════════════════════════════════════════════════════════════════
# Pass (EcoBattlepass, pass « pass »)
# ════════════════════════════════════════════════════════════════════════

# Le pass est un menu à part (menus/pass.yml, sans l'en-tête du menu principal), sans animation.
# Disposition sur toute la fenêtre (indépendante du fond), chemin en créneaux de gauche à droite :
#   rangée 0  :  Retour (0)                         Icône (8)
#   rangée 1  :        P1  .  P3  .  P5  .  P7          (P = Prestige des paliers du haut)
#   rangée 2  :   <     T1 -+  T3 -+  T5 -+  T7   >
#   rangée 3  :         +- T2 -++- T4 -++- T6 -+          (+ = cases de passage vides)
#   rangée 4  :             P2      P4      P6            (Prestige des paliers du bas)
#   inventaire 1re rangée : les 5 catégories de missions (56-60), celle affichée brille
#   inventaire 2e rangée : les missions de la catégorie affichée (9, ou 8 avec un trou au centre)
# Chemin et catégorie sont indépendants : une page par couple (page du chemin, catégorie),
# pass_<n>_<catégorie>. Le menu s'ouvre sur la page du palier du joueur, missions quotidiennes.
TITRE_PASS = '":offset_-8::menu_pass:"'
P_RETOUR, P_ICONE, P_PREV, P_NEXT, P_CATS = 0, 8, 18, 26, 56
# Paliers de la page, dans l'ordre (créneaux : haut, bas, haut...), et la case de passage qui suit
# chaque palier sauf le dernier (virage dans une case vide).
P_PALIERS = [19, 29, 21, 31, 23, 33, 25]
P_PASSAGES = [20, 30, 22, 32, 24, 34]
P_MISSIONS = list(range(63, 72))

# Les 5 catégories de missions du pass (EcoBattlepass/categories/pass/).
# Nombre de missions par catégorie (EcoBattlepass/categories/pass/*.yml), pour la disposition.
NB_MISSIONS = {"quotidiennes": 9, "semaine_1": 8, "semaine_2": 8, "semaine_3": 8, "semaine_4": 8}
MISSIONS = [("quotidiennes", "CLOCK", "Missions quotidiennes", 1),
            ("semaine_1", "WRITABLE_BOOK", "Semaine 1", 1), ("semaine_2", "WRITABLE_BOOK", "Semaine 2", 2),
            ("semaine_3", "WRITABLE_BOOK", "Semaine 3", 3), ("semaine_4", "WRITABLE_BOOK", "Semaine 4", 4)]


def pass_entete(pid_pass, infos):
    return [item("retour", "itemsadder:influitems:btn_back", "Retour", P_RETOUR, ["Revenir au menu."],
                 [CLIC_G + "Menu principal"], {"left": ["[menu] main"]}),
            item("icone", "itemsadder:influitems:pass", "Pass", P_ICONE, infos, glow=True)]


def pass_infos(pid_pass):
    T = f"%ecobattlepass_tier_{pid_pass}%"
    return APROPOS["pass"] + ["", f"Pass : %ecobattlepass_{pid_pass}_pass_type%",
                              f"Palier : &f{T}&7/&f%ecobattlepass_{pid_pass}_max_tiers%",
                              f"Points : &f%ecobattlepass_xp_{pid_pass}%&7/&f%ecobattlepass_xp_required_{pid_pass}%",
                              f"Fin de saison : &f%ecobattlepass_time_to_season_end_{pid_pass}%"]


def pass_chemin(pid_pass, n, total, paliers_max, cid):
    """Chemin de la page n (paliers libres + piste Prestige) et ses boutons précédent / suivant."""
    T = f"%ecobattlepass_tier_{pid_pass}%"
    debut = (n - 1) * PAR_PAGE
    nb = min(PAR_PAGE, paliers_max - debut)
    slots = P_PALIERS[:nb]
    its = []
    if n > 1:
        its.append(item("precedent", "itemsadder:influitems:btn_prev", "Page précédente", P_PREV, [],
                        clics={"left": [f"[page-swap] {pid_pass}_{n - 1}_{cid}", "[sound] minecraft:item.book.page_turn 1 1"]},
                        cacher=True))
    if n < total:
        its.append(item("suivant", "itemsadder:influitems:btn_next", "Page suivante", P_NEXT, [],
                        clics={"left": [f"[page-swap] {pid_pass}_{n + 1}_{cid}", "[sound] minecraft:item.book.page_turn 1 1"]},
                        cacher=True))
    branches = []
    for k, slot in enumerate(slots):
        t = debut + k + 1
        etat = f"%ecobattlepass_tier_state_{pid_pass}_{t}%"
        # Index 0 de tier_..._reward = nom combiné (trop large), jamais affiché ; les suivants sont
        # les lignes de reward-lore (EcoBattlepass/rewards/*.yml, préfixées ➤), sautées si vides.
        libre = [brut(f"%ecobattlepass_tier_free_reward_{pid_pass}_{t}_{i}%") for i in range(1, 6)]
        prest = [brut(f"%ecobattlepass_tier_premium_reward_{pid_pass}_{t}_{i}%") for i in range(1, 6)]
        nom = f"Palier {t}"
        its.append(item(f"libre{t}_ok", "itemsadder:influitems:chest_checkmark", nom, slot, libre + ["&#2ECF7BRécupéré."],
                        priorite=1, conditions=[f"placeholder: {etat} != NOT_RECEIVED", f"placeholder: {etat} != RECEIVED_PREMIUM"], quantite=t))
        its.append(item(f"libre{t}_dispo", "itemsadder:influitems:chest_unlock", nom, slot, libre,
                        [CLIC_G_OK + "Récupérer"],
                        {"left": [f"[player] {pid_pass} claim {t} free", "[sound] minecraft:entity.player.levelup 1 1.4", "[refresh]"]},
                        priorite=2, conditions=[f"placeholder: {T} >= {t}"], quantite=t))
        its.append(item(f"libre{t}", "itemsadder:influitems:lock", nom, slot, libre + [ROUGE + "Pas encore atteint."], priorite=3, quantite=t))
        bas = slot - 9 if slot < 27 else slot + 9   # Prestige vers l'extérieur du créneau
        branches.append(f'"{slot} > {bas}"')
        nomp = f"★ Palier {t} Prestige"
        its.append(item(f"prestige{t}_ok", "itemsadder:influitems:chest_checkmark", nomp, bas, prest + ["&#2ECF7BRécupéré."],
                        priorite=1, conditions=[f"placeholder: {etat} != NOT_RECEIVED", f"placeholder: {etat} != RECEIVED_FREE"]))
        its.append(item(f"prestige{t}_requis", "itemsadder:influitems:lock", nomp, bas, prest + ["&#FF5C5CPass Prestige requis."],
                        priorite=2, conditions=["!permission: battlepass.premium"]))
        its.append(item(f"prestige{t}_dispo", "itemsadder:influitems:chest_unlock", nomp, bas, prest,
                        [CLIC_G_OK + "Récupérer"],
                        {"left": [f"[player] {pid_pass} claim {t} premium", "[sound] minecraft:entity.player.levelup 1 1.4", "[refresh]"]},
                        priorite=3, conditions=[f"placeholder: {T} >= {t}"]))
        its.append(item(f"prestige{t}", "itemsadder:influitems:lock", nomp, bas, prest + [ROUGE + "Pas encore atteint."], priorite=4))
    trace = []
    for k, slot in enumerate(slots):
        trace.append(str(slot))
        if k < len(slots) - 1:
            trace.append(q(f"~{P_PASSAGES[k]}"))
    chemin = {"slots": "[" + ", ".join(trace) + "]", "progress": q(T), "offset": debut, "color": "or",
              "start": "false", "end": "false",
              "branches": "[" + ", ".join(branches) + "]"}
    chemins = {"paliers": chemin, **connecteurs(n, total, slots, "or", q(T), debut, nb, P_PREV, P_NEXT)}
    return its, chemins


def missions_pass(pid_pass, n, affichee):
    """Les 5 catégories (56-60) : la catégorie affichée brille, les autres la remplacent (même page du chemin)."""
    its = []
    for k, (cid, mat, nom, qte) in enumerate(MISSIONS):
        slot = P_CATS + k
        fait = f"&f%ecobattlepass_category_completed_quests_{cid}%&7/&f%ecobattlepass_category_quest_amount_{cid}%"
        debut = f"%ecobattlepass_category_{cid}_start_timer%"
        temps = (["Renouvellement dans", f"&f%ecobattlepass_category_{cid}_reset_timer%"] if cid == "quotidiennes"
                 else [])
        if cid == affichee:
            its.append(item(f"cat_{cid}", mat, nom, slot, [f"Missions terminées : {fait}"] + temps, glow=True))
            continue
        # Semaine pas encore commencée : cadenas, début dans... (le minuteur vaut « Disponible » une fois ouverte).
        its.append(item(f"cat_{cid}_bientot", "itemsadder:influitems:lock", nom, slot,
                        ["Débute dans", f"&f{debut}"], priorite=1,
                        conditions=[f"placeholder: {debut} !contains Disponible"]))
        its.append(item(f"cat_{cid}", mat, nom, slot, [f"Missions terminées : {fait}"] + temps,
                        [CLIC_G + "Voir les missions"],
                        {"left": [f"[page-swap] {pid_pass}_{n}_{cid}", "[sound] minecraft:item.book.page_turn 1 1"]},
                        priorite=2))
    return its


def disposition_missions(c):
    """Cases de c missions sur la rangée P_MISSIONS, centrées : impair = collées, pair =
    symétriques autour de la case du milieu laissée vide (même règle partout dans le menu)."""
    if c % 2:
        cols = list(range(4 - c // 2, 4 - c // 2 + c))
    else:
        cols = [x for x in range(4 - c // 2, 4 + c // 2 + 1) if x != 4]
    return [P_MISSIONS[0] + x for x in cols]


def missions_liste(cid):
    """Missions de la catégorie (63-71), chacune à sa place (disposition centrée fixe) :
    terminée = coche verte, catégorie pas encore ouverte = cadenas, sinon la ressource de la tâche."""
    nb = NB_MISSIONS.get(cid, 9)
    its = []
    for n, slot in enumerate(disposition_missions(nb), 1):
        base = f"%fluxpass_{cid}_{n}"
        details = [brut(f"{base}_line_{k}%") for k in range(1, 7)]
        its.append(item(f"m{n}_faite", "itemsadder:influitems:accept", f"{base}_name%", slot,
                        details + ["&#2ECF7BTerminée."], priorite=1,
                        conditions=[f"placeholder: {base}_done% == oui"]))
        its.append(item(f"m{n}_bientot", "itemsadder:influitems:lock", f"{base}_name%", slot,
                        details + ["Pas encore disponible."], priorite=2,
                        conditions=[f"placeholder: %fluxpass_{cid}_active% != oui"]))
        # En cours : la ressource de la tâche (bloc, objet, œuf...), pour repérer d'un coup d'œil quoi faire.
        its.append(item(f"m{n}", f"{base}_icon%", f"{base}_name%", slot, details + ["En cours."], priorite=3))
    return its

def pass_pages(paliers_max, pid_pass="pass"):
    total = -(-paliers_max // PAR_PAGE)
    T = f"%ecobattlepass_tier_{pid_pass}%"
    infos = pass_infos(pid_pass)
    pages = []
    for n in range(1, total + 1):
        for cid, _, _, _ in MISSIONS:
            chemin, chemins = pass_chemin(pid_pass, n, total, paliers_max, cid)
            its = pass_entete(pid_pass, infos) + chemin + missions_pass(pid_pass, n, cid) + missions_liste(cid)
            pages.append(page(f"{pid_pass}_{n}_{cid}", its, chemins, TITRE_PASS, animation=False))
    principal = [item("pass", "itemsadder:influitems:pass", "Pass", 52,
                      [f"Palier &f{T}" + VIOLET + ", récompenses", "Standard et Prestige."], [CLIC_G + "Ouvrir"],
                      {"left": [f"[menu] {pid_pass}"]})]
    return "\n".join(pages), principal


def menu_pass(pid_pass="pass"):
    """menus/pass.yml : le menu lui-même (ses pages sont dans _pages/pass.yml). Il s'ouvre sur la
    page du palier du joueur (%fluxpass_tierpage_...% fourni par Flux), missions quotidiennes."""
    entete = "\n".join("\n".join(l[4:] for l in b.split("\n")) for b in pass_entete(pid_pass, pass_infos(pid_pass)))
    return f"""# Généré par plugins/fluxplugin/Flux/assets/gen_pages.py : ne pas modifier à la main.
# Menu du pass (EcoBattlepass « {pid_pass} »), à part du menu principal, ouvert par son bouton Pass.

title: {TITRE_PASS}
rows: 6
player-inventory: hidden
vanilla-background: false
update-interval: 20

open-actions:
  - "[page-swap] {pid_pass}_%fluxpass_tierpage_{pid_pass}_{PAR_PAGE}%_quotidiennes"

page-files:
  - _pages/{pid_pass}.yml

# Le temps d'ouvrir la bonne page (un tick) : en-tête seul.
items:
{entete}
"""


# ════════════════════════════════════════════════════════════════════════
# Récompenses (FluxRewards) : connexion quotidienne + temps de jeu
# ════════════════════════════════════════════════════════════════════════

def lire_recompenses(chemin):
    texte = open(chemin, encoding="utf-8").read()
    cycle = int(re.search(r"^\s*cycle:\s*(\d+)", texte, re.M).group(1))
    bloc = texte.split("temps-de-jeu:", 1)[1].split("\nmessages:", 1)[0]
    nb_temps = len(re.findall(r"^    [0-9][0-9hms]*:\s*$", bloc, re.M))
    return cycle, nb_temps


def recompenses(cycle, nb_temps):
    pages = []
    pj = -(-cycle // PAR_PAGE)
    pt = -(-nb_temps // PAR_PAGE)

    def bas_communs(mode):
        """Centre de la barre : bascule entre les jours de connexion et le temps de jeu
        (chaque récompense se récupère une par une sur le chemin, pas de « tout récupérer »)."""
        if mode == "jour":
            return [item("bascule", "CLOCK", "Temps de jeu", A2,
                         ["Tu as joué &f%recompenses_temps_joue%" + VIOLET + ".",
                          "Paliers atteints : &f%recompenses_temps_atteints%&7/&f%recompenses_temps_total%"],
                         [CLIC_G + "Voir les paliers"],
                         {"left": ["[page-swap] recompenses_temps_1", "[sound] minecraft:item.book.page_turn 1 1"]})]
        return [item("bascule", "SUNFLOWER", "Connexion quotidienne", A2,
                     ["Jour &f%recompenses_jour%&7/&f%recompenses_cycle%" + VIOLET + " de connexion."],
                     [CLIC_G + "Voir les jours"],
                     {"left": ["[page-swap] recompenses_jour_1", "[sound] minecraft:item.book.page_turn 1 1"]})]

    # Connexion
    for n in range(1, pj + 1):
        debut = (n - 1) * PAR_PAGE
        nb = min(PAR_PAGE, cycle - debut)
        slots = CHEMIN[:nb]
        its = navigation("recompenses_jour", n, pj, 51, "itemsadder:influitems:reward", "Récompenses", APROPOS["recompenses"])
        # Les pages de temps de jeu ont leur propre bouton d'onglet : même case, même action de fermeture
        for k, slot in enumerate(slots):
            d = debut + k + 1
            nom = f"%recompenses_jour_{d}_nom%"
            desc = [f"%recompenses_jour_{d}_ligne_1%", f"%recompenses_jour_{d}_ligne_2%"]
            e = f"%recompenses_jour_{d}_etat%"
            its.append(item(f"jour{d}_ok", "itemsadder:influitems:chest_checkmark", nom, slot, desc + ["&#2ECF7BRécupéré."],
                            priorite=1, conditions=[f"placeholder: {e} == recupere"], quantite=d))
            its.append(item(f"jour{d}_dispo", "itemsadder:influitems:chest_item", nom, slot, desc,
                            [CLIC_G_OK + "Récupérer"],
                            {"left": ["[rewards] claim source:daily", "[sound] minecraft:entity.player.levelup 1 1.4", "[refresh]"]},
                            priorite=2, conditions=[f"placeholder: {e} == disponible"], quantite=d))
            its.append(item(f"jour{d}_manque", "itemsadder:influitems:deny", nom, slot, desc + ["&#FF5C5CManqué ce jour-là."],
                            priorite=3, conditions=[f"placeholder: {e} == manque"], quantite=d))
            its.append(item(f"jour{d}", "itemsadder:influitems:lock", nom, slot, desc + ["Connecte-toi un autre jour."], priorite=4, quantite=d))
        its += bas_communs("jour")
        chemin = {"slots": "[" + ", ".join(map(str, slots)) + "]", "progress": '"%recompenses_jour%"', "offset": debut,
                  "color": "vert", "start": "false", "end": "false"}
        chemins = {"jours": chemin, **connecteurs(n, pj, slots, "vert", '"%recompenses_jour%"', debut, nb)}
        pages.append(page(f"recompenses_jour_{n}", its, chemins))

    # Temps de jeu
    for n in range(1, pt + 1):
        debut = (n - 1) * PAR_PAGE
        nb = min(PAR_PAGE, nb_temps - debut)
        slots = CHEMIN[:nb]
        its = navigation("recompenses_temps", n, pt, 51, "itemsadder:influitems:reward", "Récompenses", APROPOS["recompenses"])
        for k, slot in enumerate(slots):
            i = debut + k + 1
            nom = f"%recompenses_temps_{i}_nom%"
            desc = [f"%recompenses_temps_{i}_ligne_1%", f"%recompenses_temps_{i}_ligne_2%"]
            e = f"%recompenses_temps_{i}_etat%"
            its.append(item(f"temps{i}_ok", "itemsadder:influitems:chest_checkmark", nom, slot, desc + ["&#2ECF7BRécupéré."],
                            priorite=1, conditions=[f"placeholder: {e} == recupere"], quantite=i))
            its.append(item(f"temps{i}_dispo", "itemsadder:influitems:chest_item", nom, slot, desc,
                            [CLIC_G_OK + "Récupérer"],
                            {"left": [f"[rewards] claim key:%recompenses_temps_{i}_cle%", "[sound] minecraft:entity.player.levelup 1 1.4", "[refresh]"]},
                            priorite=2, conditions=[f"placeholder: {e} == disponible"], quantite=i))
            its.append(item(f"temps{i}", "itemsadder:influitems:lock", nom, slot,
                            desc + [f"Encore &f%recompenses_temps_{i}_restant%" + VIOLET + " de jeu."], priorite=3, quantite=i))
        its += bas_communs("temps")
        chemin = {"slots": "[" + ", ".join(map(str, slots)) + "]", "progress": '"%recompenses_temps_atteints%"',
                  "offset": debut, "color": "bleu", "start": "false",
                  "end": "false"}
        chemins = {"temps": chemin, **connecteurs(n, pt, slots, "bleu", '"%recompenses_temps_atteints%"', debut, nb)}
        pages.append(page(f"recompenses_temps_{n}", its, chemins))

    ouverture = [(f"recompenses_jour_{n}", f"placeholder: %recompenses_jour% <= {n * PAR_PAGE}") for n in range(1, pj)]
    ouverture.append((f"recompenses_jour_{pj}", None))
    principal = bouton_principal("recompense", "itemsadder:influitems:reward", "Récompenses", 51,
                                 ["Jour de connexion : &f%recompenses_jour%&7/&f%recompenses_cycle%",
                                  "À récupérer : &f%recompenses_a_recuperer%"],
                                 "Ouvrir", ouverture)
    return "\n".join(pages), principal


# ════════════════════════════════════════════════════════════════════════
# Vote : tombola uniquement
# ════════════════════════════════════════════════════════════════════════

def vote():
    # Labs : données d'exemple. En prod, remplacer par les placeholders tombola d'InfluBridge
    # (tickets du joueur, lots ouverts, fin du tirage) quand ils existeront.
    lots = [("Livre Légendaire aléatoire", "2j 4h", "&#f0a800"), ("3 clés Virale", "18h", "&#5a85ea"),
            ("%img_bitblock% 25 000", "3j 1h", "&f")]
    its = [item("onglet", "itemsadder:influitems:vote", "Vote", 45, [], clics={"left": ["[page-toggle] vote"]},
                glow=True, cacher=True)]
    its.append(item("tickets", "PAPER", "Tes tickets", 84,
                    ["Chaque vote te donne des", "tickets pour la tombola.", "Tickets à miser : &f12 &8(exemple)"]))
    for k, (titre, fin, couleur) in enumerate(lots):
        its.append(item(f"lot{k + 1}", "itemsadder:influitems:chest", f"Lot {k + 1}", 66 + k,
                        [f"{couleur}{titre}", f"Tirage dans &f{fin}", "Tickets misés : &f148 &8(exemple)"],
                        [CLIC_G + "Miser sur le site"], {"left": ["[player] vote", "[close]"]}))
    its += [
        item("recuperer", "itemsadder:influitems:reward", "Mes lots", 86,
             ["Les lots gagnés t'attendent", "ici, avec tes clés de vote."], [CLIC_G + "Récupérer"],
             {"left": ["[player] voteclaims"]}),
        icone_page("itemsadder:influitems:vote", "Vote", APROPOS["vote"]),
    ]
    principal = [item("vote", "itemsadder:influitems:vote", "Vote", 45,
                      ["Vote et gagne des tickets", "pour la tombola."], [CLIC_G + "Tombola", CLIC_D + "Mes lots"],
                      {"left": ["[page-toggle] vote"], "right": ["[player] voteclaims"]})]
    return page("vote", its, {}), principal


# ════════════════════════════════════════════════════════════════════════

def main():
    menus, conf_parr, conf_rec = sys.argv[1:4]
    seuils = lire_paliers_parrainage(conf_parr)
    cycle, nb_temps = lire_recompenses(conf_rec)
    pass_max = int(sys.argv[4]) if len(sys.argv) > 4 else 50

    principaux = []
    for nom, (contenu, principal) in {
        "parrainage": parrainage(seuils),
        "pass": pass_pages(pass_max),
        "recompenses": recompenses(cycle, nb_temps),
        "vote": vote(),
    }.items():
        fichier(os.path.join(menus, "_pages", nom + ".yml"), contenu, f"Pages « {nom} » du menu main.")
        principaux += principal

    with open(os.path.join(menus, "pass.yml"), "w", encoding="utf-8") as f:
        f.write(menu_pass())

    main_yml = os.path.join(menus, "main.yml")
    texte = open(main_yml, encoding="utf-8").read()
    # Les items du menu sont 4 espaces moins indentés que ceux d'une page
    principaux = ["\n".join(l[4:] for l in b.split("\n")) for b in principaux]
    bloc = "  # >>> gen_pages (boutons générés par assets/gen_pages.py)\n\n" + "\n\n".join(principaux) + "\n\n  # <<< gen_pages\n"
    texte = re.sub(r"  # >>> gen_pages.*?  # <<< gen_pages\n", lambda m: bloc, texte, flags=re.S)
    open(main_yml, "w", encoding="utf-8").write(texte)
    print(f"parrainage : paliers {seuils} ; pass : {pass_max} paliers ; récompenses : cycle {cycle}, {nb_temps} paliers de temps")


if __name__ == "__main__":
    main()
