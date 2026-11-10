#!/usr/bin/env python3
"""Menus Flux des villes et pays (FluxLands) : écrit menus/territoires.yml, ville*.yml, villes.yml, warps.yml,
agence.yml, pays*.yml (les échoppes sont au marché de FluxAuctionHouse).

    python3 gen_menus_ville.py <plugins/Flux/menus>

Ne pas modifier les menus générés à la main : changer ce script puis le relancer et /fluxmenu reload.
Données : placeholders %fluxlands_...% et commande /fluxlands du plugin FluxLands
(docs/technique/fluxlands.md). Fonds et icônes : pack ItemsAdder « influville » (gen_ville.py).
Lore des boutons : balises du style de Flux (menu_lore.lore, Flux/assets).
"""
import os
import sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../../Flux/assets"))
from menu_lore import lore  # noqa: E402  (button lore in the tags of Flux's style.yml)

SORTIE = sys.argv[1]
OR, VIOLET = "&#FFD94D", "&#B376E5"
GAUCHE, DROIT = "&r&7%img_left_click% &f", "&r&7%img_right_click% &f"
MAJ_GAUCHE = "&r&7Maj + %img_left_click% &f"
MAJ_DROIT = "&r&7Maj + %img_right_click% &f"
BB = "&f%img_bitblock%"
GRILLE = [r * 9 + c for r in range(2, 5) for c in range(1, 8)]
ENTETE = "# Généré par plugins/fluxplugin/FluxLands/assets/ville/gen_menus_ville.py : ne pas modifier à la main.\n"
ANCIENS = ["adresses.yml", "ville_annonce.yml", "ville_banque.yml", "ville_reglages.yml", "echoppes.yml"]  # menus renommés ou retirés, supprimés à la génération


def q(s):
    """Chaîne YAML entre guillemets doubles."""
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def bouton(cle, slot, materiau, nom, lignes, actions=None, conditions=None, priorite=None, extra=None):
    out = ["  %s:" % cle, "    material: %s" % q(materiau), "    name: %s" % q(nom),
           "    slots: [%s]" % (", ".join(str(s) for s in slot) if isinstance(slot, (list, tuple)) else slot),
           "    flags: [HIDE_ATTRIBUTES]"]
    if priorite is not None:
        out.append("    priority: %d" % priorite)
    if conditions:
        out.append("    view-requirements:")
        out += ["      - %s" % q(c) for c in conditions]
    for k, v in (extra or {}).items():
        out.append("    %s: %s" % (k, v))
    if lignes:
        out.append("    lore:")
        out += ["      - %s" % q(l) for l in lignes]
    if actions:
        out.append("    actions:")
        for clic, acts in actions.items():
            out.append("      %s: [%s]" % (clic, ", ".join(q(a) for a in acts)))
    return "\n".join(out) + "\n"


def menu(nom, fond, items, commandes=None, ouverture=(), doc="", widgets="", listes=""):
    out = [ENTETE, "# " + doc + "\n\n" if doc else "\n",
           "title: %s\n" % q(":offset_-8::%s:" % fond), "rows: 6\n"]
    if commandes:
        out.append("commands: [%s]\n" % ", ".join(commandes))
    out += ["player-inventory: hidden\n", "vanilla-background: false\n", "update-interval: 20\n\n", "open-actions:\n"]
    for a in list(ouverture) + ["[sound] @page"]:
        out.append("  - %s\n" % q(a))
    if widgets:
        out.append("\n" + widgets)
    if listes:
        out.append("\n" + listes)
    out.append("\nitems:\n\n")
    out += [i + "\n" for i in items]
    with open(os.path.join(SORTIE, nom + ".yml"), "w") as f:
        f.write("".join(out))


def iv(cmd):
    return "[player] fluxlands " + cmd


SON = "[sound] @click"
SON_PAGE = "[sound] @page"
SON_OK = "[sound] @success"


def retour(slot, cible, libelle, droit=None):
    acts = {"left": cible}
    ac = [(GAUCHE, libelle)]
    if droit:
        acts["right"] = droit[1]
        ac.append((DROIT, droit[0]))
    return bouton("retour", slot, "itemsadder:influitems:btn_back", OR + "Retour", lore([], ac), acts)


def retour_liste():
    """Retour des listes : menu d'où la liste a été ouverte (mémorisé par FluxLands, option depuis:)."""
    return bouton("retour", 0, "itemsadder:influitems:btn_back", OR + "Retour", lore([], [(GAUCHE, "Revenir au menu précédent")]),
                  {"left": [iv("retour")]})


RETOUR_HUB = ("Menu principal", ["[menu] main"])


def m(c):
    return "%fluxlands_ma_" + c + "%"


def mp(c):
    return "%fluxlands_monpays_" + c + "%"


def fp(c):
    return "%fluxlands_paysfiche_" + c + "%"


def f(c):
    return "%fluxlands_fiche_" + c + "%"


def pc(c):
    return "%fluxlands_parcelle_" + c + "%"


def bq(c):
    return "%fluxlands_banque_" + c + "%"


MAIRE = "placeholder: " + m("est_maire") + " == oui"


def banque_bouton(depuis):
    """Banque : clic gauche déposer, clic droit retirer, chacun par une fenêtre de montant (pas de menu tampon)."""
    return bouton("banque", 37, "itemsadder:influville:banque", OR + "Banque",
                  lore(["Paie les frais d'entretien et les agrandissements de la ville.", "",
                        "Banque : &f" + m("solde") + " " + BB, "Ton solde : &f" + bq("perso") + " " + BB,
                        "Frais d'entretien : &f" + m("entretien") + " " + BB + " &7/ jour",
                        "Couverts pendant : &f" + bq("jours") + " jours"],
                       [(GAUCHE, "Déposer"), (DROIT, "Retirer")]),
                  {"left": [iv("banque deposer " + depuis)], "right": [iv("banque retirer " + depuis)]})


# ── Ma ville ───────────────────────────────────────────────────────────────
# Refait le 2026-11-01 : plus de menu Réglages, tout est ici. Case 8 = information sans action. Rangée 2 : niveau, fiche
# publique, pays. Rangée 4 : la vie de la ville. Rangée 5 : l'administration (le maire modifie, tout le monde consulte).
# L'annuaire des villes et l'Agence sont dans Villes et Pays (/menu2), pas ici.
INFO_VILLE = ["Niveau : &f" + m("niveau"), "Maire : &f" + m("maire"), "Ton rôle : &f" + m("role"),
              "Fondation : &f" + m("creation"), "Habitants : &f" + m("habitants") + " &7(" + m("en_ligne") + " en ligne)",
              "Chunks : &f" + m("chunks") + "/" + m("chunks_max"), "Banque : &f" + m("solde") + " " + BB,
              "Frais d'entretien : &f" + m("entretien") + " " + BB + " &7/ jour", "Droit de place : &f" + m("taux") + " %"]
DESC_VILLE = "Le tableau de bord de ta ville. Tout le monde consulte, seul le maire modifie."
FICHE_DESC = ["Ta ville telle que les autres joueurs la voient : ses chiffres et sa vitrine de warps."]
ma_ville = [
    retour(0, ["[menu] territoires"], "Villes et Pays", droit=RETOUR_HUB),
    bouton("info_multi", 8, "itemsadder:influville:ville", m("nom_couleur"),
           lore([DESC_VILLE, "Tu habites &f%fluxlands_nb_villes%" + VIOLET + " villes : change de ville avec la fiche publique.", ""] + INFO_VILLE),
           priorite=-1, conditions=["placeholder: %fluxlands_nb_villes% >= 2"]),
    bouton("info", 8, "itemsadder:influville:ville", m("nom_couleur"), lore([DESC_VILLE, ""] + INFO_VILLE)),
    # Rangée 2
    bouton("niveau", 20, m("icone"), OR + "Niveau &f" + m("niveau_num") + OR + "/7",
           lore([m("niveau"), "Le niveau de la ville augmente ses limites (chunks, habitants)."], [(GAUCHE, "Objectifs du niveau suivant")]),
           {"left": [iv("niveau ville")]}),
    bouton("fiche_multi", 22, "itemsadder:influville:epingle", OR + "Fiche publique",
           lore(FICHE_DESC, [(GAUCHE, "Voir la fiche publique"), (DROIT, "Afficher ma ville suivante")]),
           {"left": [iv("ouvrir fiche ma")], "right": [iv("choisir"), SON, "[refresh]"]},
           priorite=-1, conditions=["placeholder: %fluxlands_nb_villes% >= 2"]),
    bouton("fiche", 22, "itemsadder:influville:epingle", OR + "Fiche publique", lore(FICHE_DESC, [(GAUCHE, "Voir la fiche publique")]),
           {"left": [iv("ouvrir fiche ma")]}),
    bouton("mon_pays", 24, mp("icone"), OR + "Mon pays : " + mp("nom_couleur"),
           lore(["Capitale : &f" + mp("capitale"), "Villes : &f" + mp("villes"), "Niveau : &f" + mp("niveau")], [(GAUCHE, "Ouvrir Mon pays")]),
           {"left": [iv("ouvrir pays")]}, priorite=-1, conditions=["placeholder: " + mp("existe") + " == oui"]),
    bouton("sans_pays", 24, "itemsadder:influville:pays", OR + "Pays",
           lore(["Ta ville n'appartient à aucun pays."], [(GAUCHE, "Fonder ou rejoindre un pays")]),
           {"left": [iv("ouvrir pays")]}),
    # Rangée 4 : la vie de la ville
    banque_bouton("ville"),
    bouton("habitants", 38, "itemsadder:influville:habitants", OR + "Habitants",
           lore([m("habitants") + " habitants, " + m("en_ligne") + " en ligne."], [(GAUCHE, "Voir les habitants"), (DROIT, "Inviter un joueur")]),
           {"left": [iv("ouvrir habitants ma")], "right": [iv("saisie inviter")]}),
    bouton("commerce", 39, "itemsadder:influville:commerce", OR + "Commerce",
           lore(["Part des ventes des échoppes de la ville versée à sa banque.", "",
                 "Droit de place : &f" + m("taux") + " %", "Aujourd'hui : &f" + m("recette_jour") + " " + BB,
                 "7 jours : &f" + m("recette_7j") + " " + BB], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir commerce")]}),
    bouton("echoppes", 40, "itemsadder:influville:echoppe", OR + "Échoppes",
           lore([m("echoppes") + " échoppes dans la ville, au marché."], [(GAUCHE, "Échoppes de la ville"), (DROIT, "Mes échoppes")]),
           {"left": [iv("ouvrir echoppes ma depuis:ville")], "right": [iv("ouvrir echoppes miennes depuis:ville")]},
           conditions=["placeholder: %fluxlands_modules_echoppes% == oui"]),
    bouton("warps", 41, "itemsadder:influville:warp", OR + "Warps",
           lore([m("adresses") + " warps dans la ville."], [(GAUCHE, "Warps de la ville"), (DROIT, "Créer un warp ici")]),
           {"left": [iv("ouvrir warps ma")], "right": [iv("saisie creer_warp")]},
           conditions=["placeholder: %fluxlands_modules_warps% == oui"]),
    bouton("spawn_maire", 42, "itemsadder:influville:spawn", OR + "Spawn de la ville",
           lore(["Point d'arrivée de la ville. Le déplacer pose le spawn là où tu te tiens.", "",
                 "Prix pour le poser : &f" + m("spawn_prix")], [(GAUCHE, "Se téléporter"), (DROIT, "Définir le spawn ici")]),
           {"left": [iv("spawn ma")], "right": [iv("spawn definir"), "[refresh]"]}, priorite=-1, conditions=[MAIRE]),
    bouton("spawn", 42, "itemsadder:influville:spawn", OR + "Spawn de la ville",
           lore(["Point d'arrivée de la ville. Seul le maire peut le déplacer."], [(GAUCHE, "Se téléporter")]),
           {"left": [iv("spawn ma")]}),
    bouton("chunk", 43, "itemsadder:influville:chunks", OR + "Chunk où tu te tiens",
           lore(["Revendiquer ajoute ce chunk à ta ville (coût prélevé sur la banque), l'abandonner le rend à la nature.",
                 "Commandes : &f[/v claim]" + VIOLET + " et &f[/v unclaim]" + VIOLET + "."],
                [(GAUCHE, "Revendiquer ce chunk"), (DROIT, "Abandonner ce chunk")]),
           {"left": [iv("chunk revendiquer")], "right": [iv("chunk abandonner")]}),
    # Rangée 5 : administration
    bouton("parcelles", 47, "itemsadder:influville:parcelle", OR + "Parcelles",
           lore(["Découpe ta ville en parcelles, loue-les ou vends-les."], [(GAUCHE, "Ouvrir")]), {"left": [iv("ouvrir parcelles depuis:ville")]}),
    bouton("roles", 48, "itemsadder:influville:roles", OR + "Rôles et permissions",
           lore(["Ce que chaque rôle a le droit de faire dans la ville."], [(GAUCHE, "Ouvrir")]), {"left": [iv("ouvrir roles depuis:ville")]}),
    bouton("parametres", 49, "itemsadder:influitems:settings", OR + "Paramètres",
           lore(["Explosions, feu, apparition des monstres, PvP..."], [(GAUCHE, "Ouvrir")]), {"left": [iv("ouvrir parametres depuis:ville")]}),
    bouton("renommer", 50, "itemsadder:influville:renommer", OR + "Renommer la ville",
           lore(["Nom actuel : &f" + m("nom")], [(GAUCHE, "Choisir un nom")]), {"left": [iv("reglages renommer")]}),
    bouton("accueil", 51, "itemsadder:influville:message", OR + "Message d'accueil",
           lore(["Titre affiché aux joueurs qui entrent dans la ville."], [(GAUCHE, "Écrire le message")]), {"left": [iv("reglages accueil")]}),
]
# Nom de la ville écrit par Flux au milieu de la rangée du haut (entre Retour et l'information), gravé comme les
# titres : on voit d'un coup d'œil quelle ville on gère ; « ville suivante » le met à jour ([refresh]).
NOM_EN_TETE = """widgets:
  nom:
    type: text
    x: 88
    y: 23
    align: center
    ink: true
    text: "%s"
"""
menu("ville", "menu_ville", ma_ville, widgets=NOM_EN_TETE % m("nom"), doc="Ma ville : tableau de bord et gestion de la ville du joueur (/menu2 > Ville, /lands, /v, /ville).")

# ── Accueil (joueur sans ville) ────────────────────────────────────────────
accueil = [
    retour(0, ["[menu] territoires"], "Villes et Pays", droit=RETOUR_HUB),
    bouton("annuaire", 20, "itemsadder:influville:annuaire", OR + "Annuaire des villes",
           lore(["Trouve une ville qui te plaît", "et demande une invitation", "à son maire."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir annuaire depuis:accueil")]}),
    bouton("fonder", 22, "itemsadder:influitems:add", OR + "Fonder une ville",
           lore(["Crée ta ville à l'endroit", "où tu te tiens.", "", "Serveur en bêta : les règles", "des villes peuvent évoluer."],
                [(GAUCHE, "Choisir un nom")]),
           {"left": [iv("saisie creer_ville")]}),
    bouton("agence", 24, "itemsadder:influville:agence", OR + "Agence",
           lore(["Loue ou achète une parcelle", "dans une ville existante."], [(GAUCHE, "Voir les annonces")]),
           {"left": [iv("ouvrir agence depuis:accueil")]}),
]
menu("ville_accueil", "menu_ville_accueil", accueil, doc="Accueil des villes, pour un joueur qui n'habite aucune ville.")


# ── Fiche publique d'une ville ─────────────────────────────────────────────
def v(n, c):
    return "%%fluxlands_vitrine_%d_%s%%" % (n, c)


fiche = [
    retour(0, [iv("ouvrir annuaire")], "Annuaire", droit=("Ma ville", [iv("ouvrir ville")])),
    bouton("spawn", 8, "itemsadder:influville:spawn", OR + "Spawn de " + f("nom"),
           lore(["Point d'apparition de la ville."], [(GAUCHE, "Se téléporter")]),
           {"left": [iv("spawn fiche")]}, conditions=["placeholder: " + f("spawn") + " == oui"]),
    bouton("blason", 19, f("icone"), f("nom_couleur"),
           lore(["Niveau : &f" + f("niveau"), "Maire : &f" + f("maire"), "Fondée le &f" + f("creation")])),
    bouton("stat_habitants", 20, "itemsadder:influville:habitants", OR + "Habitants &f" + f("habitants"),
           lore(["En ligne : &f" + f("en_ligne")])),
    bouton("stat_chunks", 21, "itemsadder:influville:chunks", OR + "Chunks &f" + f("chunks"), lore(["Taille de la ville."])),
    bouton("stat_echoppes", 22, "itemsadder:influville:echoppe", OR + "Échoppes &f" + f("echoppes"),
           lore(["Échoppes ouvertes dans la ville."])),
    bouton("stat_warps", 23, "itemsadder:influville:warp", OR + "Warps &f" + f("adresses"),
           lore(["Warps publics de la ville."])),
    bouton("stat_droit", 24, "itemsadder:influville:commerce", OR + "Droit de place &f" + f("taux") + " %",
           lore(["Part de chaque vente d'échoppe", "versée à la banque de la ville."])),
    bouton("stat_nation", 25, "itemsadder:influville:nation", OR + "Pays", lore(["&f" + f("nation")])),
]
for n, slot in enumerate((38, 40, 42), start=1):
    fiche.append(bouton("vitrine_%d" % n, slot, v(n, "icone"), OR + v(n, "titre"),
                        lore(["Par &f" + v(n, "proprio"), v(n, "note") + " &7" + v(n, "visites") + " visites",
                              v(n, "prix_ligne"), "&7" + v(n, "desc_1"), "&7" + v(n, "desc_2")],
                             [(GAUCHE, "Y aller")]),
                        {"left": [iv("vitrine %d gauche" % n)]}, priorite=-1,
                        conditions=["placeholder: %s == oui" % v(n, "existe"), "placeholder: " + f("est_maire") + " == non"]))
    fiche.append(bouton("vitrine_%d_maire" % n, slot, v(n, "icone"), OR + v(n, "titre"),
                        lore(["Par &f" + v(n, "proprio"), v(n, "note") + " &7" + v(n, "visites") + " visites",
                              v(n, "prix_ligne")],
                             [(GAUCHE, "Y aller"), (DROIT, "Retirer de la vitrine")]),
                        {"left": [iv("vitrine %d gauche" % n)], "right": [iv("vitrine %d droit" % n), SON, "[refresh]"]},
                        priorite=-2,
                        conditions=["placeholder: %s == oui" % v(n, "existe"), "placeholder: " + f("est_maire") + " == oui"]))
    fiche.append(bouton("vitrine_%d_libre" % n, slot, "itemsadder:influville:epingle_off", OR + "Emplacement libre",
                        lore(["Le maire épingle ici les", "warps phares de sa ville", "(maj + clic droit dans la", "liste des warps)."])))
fiche += [
    bouton("habitants", 47, "itemsadder:influville:habitants", OR + "Habitants",
           lore([f("habitants") + " habitants."], [(GAUCHE, "Voir")]), {"left": [iv("ouvrir habitants fiche")]}),
    bouton("warps", 49, "itemsadder:influville:warp", OR + "Warps de " + f("nom"),
           lore([f("adresses") + " warps."], [(GAUCHE, "Voir")]), {"left": [iv("ouvrir warps fiche")]},
           conditions=["placeholder: %fluxlands_modules_warps% == oui"]),
    bouton("echoppes", 51, "itemsadder:influville:echoppe", OR + "Échoppes de " + f("nom"),
           lore([f("echoppes") + " échoppes."], [(GAUCHE, "Voir")]), {"left": [iv("ouvrir echoppes fiche")]},
           conditions=["placeholder: %fluxlands_modules_echoppes% == oui"]),
]
menu("ville_fiche", "menu_ville_fiche", fiche, doc="Fiche publique d'une ville (annuaire, warps, échoppes).")

# ── Commerce de ma ville ───────────────────────────────────────────────────
commerce = [
    retour(0, [iv("ouvrir ville")], "Ma ville"),
    bouton("info", 8, "itemsadder:influville:commerce", OR + "Droit de place",
           lore(["Chaque vente ou achat d'une", "échoppe située dans ta ville", "verse une part du montant à", "la banque de la ville. Le maire",
                 "en fixe le taux."])),
    bouton("aujourdhui", 19, "itemsadder:influitems:bitblock", OR + "Aujourd'hui",
           lore(["&f" + m("recette_jour") + " " + BB + VIOLET + " perçus."])),
    bouton("taux_maire", 22, "itemsadder:influville:commerce", OR + "Droit de place &f" + m("taux") + " %",
           lore(["Taux appliqué aux échoppes de " + m("nom") + "."], [(GAUCHE, "Changer le taux")]),
           {"left": [iv("droit")]}, priorite=-1, conditions=[MAIRE], extra={"glow": "true"}),
    bouton("taux", 22, "itemsadder:influville:commerce", OR + "Droit de place &f" + m("taux") + " %",
           lore(["Taux appliqué aux échoppes de " + m("nom") + ".", "", "Seul le maire peut le changer."]),
           extra={"glow": "true"}),
    bouton("total", 25, "itemsadder:influitems:bitblock", OR + "Depuis toujours",
           lore(["&f" + m("recette_total") + " " + BB + VIOLET + " perçus", "en &f" + m("transactions") + VIOLET + " transactions."])),
]
for k, slot in enumerate(range(37, 44), start=1):
    j = "%%fluxlands_jour_%d_" % k
    commerce.append(bouton("jour_%d" % k, slot, "itemsadder:influville:barre_" + j + "barre%", OR + j + "nom%",
                           lore(["&f" + j + "montant% " + BB + VIOLET + " perçus."])))
for n, slot in enumerate(range(47, 52), start=1):
    t = "%%fluxlands_top_%d_" % n
    commerce.append(bouton("top_%d" % n, slot, t + "icone%", t + "objet%",
                           lore(["N°%d du commerce de la ville" % n, "Par &f" + t + "proprio%", "Reversé : &f" + t + "reverse% " + BB,
                                 "Prix : &f" + t + "prix% " + BB + " &7(x" + t + "quantite%)"],
                                [(GAUCHE, "Localiser")]),
                           {"left": [iv("top %d" % n)]}, conditions=["placeholder: %sexiste%% == oui" % t]))
commerce.append(bouton("echoppes", 53, "itemsadder:influville:echoppe", OR + "Échoppes de la ville",
                       lore([m("echoppes") + " échoppes."], [(GAUCHE, "Voir")]), {"left": [iv("ouvrir echoppes ma depuis:commerce")]},
                       conditions=["placeholder: %fluxlands_modules_echoppes% == oui"]))
menu("ville_commerce", "menu_ville_commerce", commerce, doc="Commerce de ma ville : droit de place, recettes des 7 derniers jours, meilleures échoppes.")

# ── Listes (annuaire, warps, échoppes, habitants, agence, parcelles, rôles, permissions, paramètres) ──
def l(n, c):
    return "%%fluxlands_liste_%d_%s%%" % (n, c)


def choix_lignes(prefixe, nb):
    return ["@option %%fluxlands_liste_%s_%d%%" % (prefixe, k) for k in range(1, nb + 1)]


def barre_liste(info_icone, info_nom, info_desc, action=None, gauche=None, filtre=True, tri=True, extra=()):
    """Barre du bas : raccourci (45), filtre (47), pages (48, 50), recherche (49), tri (51), action (53).
    gauche=False : pas de raccourci (liste ouverte seulement depuis Ma ville, le Retour y mène déjà)."""
    items = [bouton("info", 8, info_icone, OR + info_nom, lore(info_desc + ["", "Résultats : &f%fluxlands_liste_total%"]))]
    if gauche is not False:
        items.append(gauche or bouton("ma_ville", 45, "itemsadder:influville:ville", OR + "Ma ville", lore([], [(GAUCHE, "Ouvrir")]),
                                      {"left": [iv("ouvrir ville")]}))
    if filtre:
        items.append(bouton("filtre", 47, "itemsadder:influitems:filter", OR + "Filtre",
                            choix_lignes("filtre", 6) + ["@left Suivant", "@right Précédent"],
                            {"left": [iv("filtre +"), SON, "[refresh]"], "right": [iv("filtre -"), SON, "[refresh]"]}))
    items += [
        bouton("precedent", 48, "itemsadder:influitems:btn_prev", OR + "Page précédente", lore([], [(GAUCHE, "Précédente")]),
               {"left": [iv("page -"), SON_PAGE, "[refresh]"]}, conditions=["placeholder: %fluxlands_liste_precedent% == oui"]),
        bouton("recherche", 49, "itemsadder:influitems:search", OR + "Rechercher",
               lore(["Recherche : &f%fluxlands_liste_recherche%"], [(GAUCHE, "Rechercher"), (DROIT, "Effacer")]),
               {"left": [iv("recherche")], "right": [iv("effacer"), SON, "[refresh]"]}),
        bouton("suivant", 50, "itemsadder:influitems:btn_next", OR + "Page suivante", lore([], [(GAUCHE, "Suivante")]),
               {"left": [iv("page +"), SON_PAGE, "[refresh]"]}, conditions=["placeholder: %fluxlands_liste_suivant% == oui"]),
    ]
    if tri:
        items.append(bouton("tri", 51, "itemsadder:influitems:stairs", OR + "Trier par",
                            choix_lignes("tri", 4) + ["@left Suivant", "@right Précédent"],
                            {"left": [iv("tri +"), SON, "[refresh]"], "right": [iv("tri -"), SON, "[refresh]"]}))
    if action:
        items.append(action)
    items += list(extra)
    return items


def vide(icone, nom, desc):
    """Case centrale affichée quand la liste est vide."""
    return bouton("vide", 31, icone, OR + nom, lore(desc), conditions=["placeholder: %fluxlands_liste_total% == 0"])


# Annuaire des villes
annuaire = [retour_liste()]
for n, slot in enumerate(GRILLE, start=1):
    annuaire.append(bouton("ville_%d" % n, slot, l(n, "icone"), l(n, "nom_couleur"),
                           lore(["Niveau : &f" + l(n, "niveau"), "Maire : &f" + l(n, "maire"),
                                 "Habitants : &f" + l(n, "habitants") + " &7(" + l(n, "en_ligne") + " en ligne)",
                                 "Chunks : &f" + l(n, "chunks"),
                                 "Échoppes : &f" + l(n, "echoppes") + VIOLET + "  Warps : &f" + l(n, "adresses"),
                                 "Droit de place : &f" + l(n, "taux") + " %", "Pays : &f" + l(n, "nation")],
                                [(GAUCHE, "Voir la fiche"), (DROIT, "Se téléporter au spawn")]),
                           {"left": [iv("clic %d gauche" % n)], "right": [iv("clic %d droit" % n)]},
                           conditions=["placeholder: %s == oui" % l(n, "existe")]))
annuaire.append(vide("itemsadder:influitems:unknown", "Aucune ville", ["Aucune ville ne correspond", "à ce filtre."]))
annuaire += barre_liste("itemsadder:influville:annuaire", "Annuaire des villes",
                        ["Toutes les villes du serveur,", "de la plus grande à la plus", "petite."],
                        bouton("fonder", 53, "itemsadder:influitems:add", OR + "Fonder une ville",
                               lore(["Crée ta ville à l'endroit", "où tu te tiens."], [(GAUCHE, "Choisir un nom")]),
                               {"left": [iv("saisie creer_ville")]}, conditions=["placeholder: %fluxlands_ma_existe% == non"]))
menu("villes", "menu_villes", annuaire, commandes=["annuaire"],
     ouverture=[iv("vue villes"), "[refresh]"], doc="Annuaire des villes (/annuaire).")

# Warps (FluxPW)
warps = [retour_liste()]
for n, slot in enumerate(GRILLE, start=1):
    desc = ["Ville : &f" + l(n, "ville"), "Par &f" + l(n, "proprio"),
            l(n, "note") + " &7" + l(n, "visites") + " visites", l(n, "prix_ligne"),
            "&7" + l(n, "desc_1"), "&7" + l(n, "desc_2"), "&7" + l(n, "desc_3")]
    warps.append(bouton("warp_%d_maire" % n, slot, l(n, "icone"), OR + l(n, "titre"),
                        lore(desc, [(GAUCHE, "Y aller"), (DROIT, "Fiche de la ville"), (MAJ_DROIT, "Épingler / retirer de la vitrine")]),
                        {"left": [iv("clic %d gauche" % n)], "right": [iv("clic %d droit" % n)],
                         "shift-right": [iv("clic %d epingler" % n), SON, "[refresh]"]},
                        priorite=-1, conditions=["placeholder: %s == oui" % l(n, "existe"), "placeholder: %s == oui" % l(n, "epinglable")]))
    warps.append(bouton("warp_%d" % n, slot, l(n, "icone"), OR + l(n, "titre"),
                        lore(desc, [(GAUCHE, "Y aller"), (DROIT, "Fiche de la ville")]),
                        {"left": [iv("clic %d gauche" % n)], "right": [iv("clic %d droit" % n)]},
                        conditions=["placeholder: %s == oui" % l(n, "existe")]))
warps.append(vide("itemsadder:influitems:unknown", "Aucun warp", ["Aucun warp ne correspond", "à ce filtre."]))
warps += barre_liste("itemsadder:influville:warp", "Warps",
                     ["Les warps des joueurs [/pw],", "rattachés à la ville où", "ils se trouvent."],
                     bouton("creer", 53, "itemsadder:influitems:add", OR + "Créer un warp",
                            lore(["Crée un warp là où tu te", "tiens. Dans une ville, il", "apparaît sur sa fiche."], [(GAUCHE, "Choisir un nom")]),
                            {"left": [iv("saisie creer_warp")]}))
menu("warps", "menu_warps", warps, commandes=["warps"],
     ouverture=[iv("vue warps"), "[refresh]"], doc="Warps (FluxPW) avec leur ville (/warps).")

# Habitants
habitants = [retour_liste()]
for n, slot in enumerate(GRILLE, start=1):
    habitants.append(bouton("habitant_%d_maire" % n, slot, l(n, "tete"), "&f" + l(n, "nom"),
                            lore(["Rôle : " + l(n, "role"), l(n, "statut")], [(GAUCHE, "Changer son rôle")]),
                            {"left": [iv("clic %d gauche" % n)]}, priorite=-1,
                            conditions=["placeholder: %s == oui" % l(n, "existe"), "placeholder: %s == oui" % l(n, "role_modifiable")]))
    habitants.append(bouton("habitant_%d" % n, slot, l(n, "tete"), "&f" + l(n, "nom"),
                            lore(["Rôle : " + l(n, "role"), l(n, "statut")]),
                            conditions=["placeholder: %s == oui" % l(n, "existe")]))
habitants.append(vide("itemsadder:influitems:unknown", "Personne", ["Aucun habitant ne correspond", "à ce filtre."]))
habitants += barre_liste("itemsadder:influville:habitants", "Habitants de " + f("nom"),
                         [f("habitants") + " habitants, " + f("en_ligne") + " en ligne."],
                         bouton("inviter", 53, "itemsadder:influitems:add", OR + "Inviter un joueur",
                                lore(["Le joueur reçoit une invitation", "à rejoindre ta ville."], [(GAUCHE, "Choisir un pseudo")]),
                                {"left": [iv("saisie inviter")]}, conditions=["placeholder: " + f("membre") + " == oui"]))
menu("ville_habitants", "menu_habitants", habitants, ouverture=[iv("vue habitants"), "[refresh]"],
     doc="Habitants d'une ville (têtes, rôles, en ligne).")

# Agence : annonces de location et de vente
agence = [retour_liste()]
for n, slot in enumerate(GRILLE, start=1):
    agence.append(bouton("annonce_%d" % n, slot, l(n, "icone"), OR + l(n, "parcelle"),
                         lore(["&f" + l(n, "type") + VIOLET + " à &f" + l(n, "ville"),
                               "Prix : &f" + l(n, "prix") + " " + BB, l(n, "periode_ligne"),
                               "Taille : &f" + l(n, "taille") + VIOLET + " blocs", "Maire : &f" + l(n, "maire")],
                              [(GAUCHE, "Visiter l'annonce"), (DROIT, "Fiche de la ville")]),
                         {"left": [iv("clic %d gauche" % n)], "right": [iv("clic %d droit" % n)]},
                         conditions=["placeholder: %s == oui" % l(n, "existe")]))
agence.append(vide("itemsadder:influitems:unknown", "Aucune annonce", ["Aucune parcelle à louer ou", "à vendre pour ces filtres."]))
agence += barre_liste(
    "itemsadder:influville:agence", "Agence",
    ["Parcelles à louer ou à vendre", "dans toutes les villes", "[/agence]."],
    bouton("publier", 53, "itemsadder:influitems:add", OR + "Publier une annonce",
           lore(["Mets en location ou en vente", "une parcelle de ta ville."], [(GAUCHE, "Mes parcelles")]),
           {"left": [iv("ouvrir parcelles")]}, conditions=[MAIRE]),
    extra=[
        bouton("budget", 46, "itemsadder:influville:budget", OR + "Budget",
               choix_lignes("budget", 5) + ["@left Suivant", "@right Précédent"],
               {"left": [iv("budget +"), SON, "[refresh]"], "right": [iv("budget -"), SON, "[refresh]"]}),
        bouton("taille", 52, "itemsadder:influville:taille", OR + "Taille",
               choix_lignes("taille", 4) + ["@left Suivant", "@right Précédent"],
               {"left": [iv("taille +"), SON, "[refresh]"], "right": [iv("taille -"), SON, "[refresh]"]}),
    ])
menu("agence", "menu_agence", agence, commandes=["agence"], ouverture=[iv("vue agence"), "[refresh]"],
     doc="Agence : annonces de location et de vente de toutes les villes (/agence, /v rental list).")

# Parcelles de ma ville
parcelles = [retour_liste()]
for n, slot in enumerate(GRILLE, start=1):
    parcelles.append(bouton("parcelle_%d" % n, slot, l(n, "icone"), OR + l(n, "nom"),
                            lore(["État : &f" + l(n, "etat"), "Taille : &f" + l(n, "taille"), l(n, "annonce_ligne"),
                                  l(n, "locataire_ligne"), "Habitants : &f" + l(n, "habitants")],
                                 [(GAUCHE, "Gérer la parcelle")]),
                            {"left": [iv("clic %d gauche" % n)]}, conditions=["placeholder: %s == oui" % l(n, "existe")]))
parcelles.append(vide("itemsadder:influville:parcelle", "Aucune parcelle",
                      ["Découpe une parcelle avec le", "bouton Nouvelle parcelle, en", "bas à droite."]))
parcelles += barre_liste(
    "itemsadder:influville:parcelle", "Parcelles de " + m("nom"),
    ["Sous-zones de la ville, à", "louer ou à vendre."],
    bouton("nouvelle", 53, "itemsadder:influitems:add", OR + "Nouvelle parcelle",
           lore(["Délimite-la par deux coins,", "par le chunk où tu te tiens ou", "par ta sélection, nomme-la, et", "mets-la à l'Agence dans la foulée", "si tu veux."], [(GAUCHE, "Commencer")]),
           {"left": [iv("parcelle nouvelle")]}, conditions=[MAIRE]),
    gauche=bouton("ici", 45, "itemsadder:influville:spawn", OR + "Parcelle où je suis",
                  lore(["Ouvre la parcelle dans", "laquelle tu te tiens."], [(GAUCHE, "Ouvrir")]), {"left": [iv("parcelle ici")]}))
menu("ville_parcelles", "menu_parcelles", parcelles, ouverture=[iv("vue parcelles"), "[refresh]"],
     doc="Parcelles de ma ville (remplace le menu des parcelles de Lands).")

# Une parcelle
parcelle = [
    retour(0, [iv("ouvrir parcelles")], "Parcelles"),
    bouton("info", 8, pc("icone"), OR + pc("nom"),
           lore(["Parcelle de " + m("nom") + ".", "Les boutons de gestion sont", "réservés au maire."])),
    bouton("etat", 19, pc("icone"), OR + pc("nom"), lore(["État : &f" + pc("etat")])),
    bouton("taille", 20, "itemsadder:influville:taille", OR + "Taille", lore(["&f" + pc("taille"), "&f" + pc("volume") + VIOLET + " blocs"])),
    bouton("annonce_info", 21, "itemsadder:influville:agence", OR + "Annonce", lore([pc("annonce_ligne")])),
    bouton("occupant", 22, "itemsadder:influitems:referral", OR + "Occupant", lore([pc("locataire_ligne")])),
    bouton("habitants_info", 23, "itemsadder:influville:habitants", OR + "Habitants &f" + pc("habitants"),
           lore(["Joueurs ajoutés à la parcelle."])),
    bouton("position", 24, "itemsadder:influville:spawn", OR + "Position", lore(["&f" + pc("coord")])),
    bouton("agence_lien", 25, "itemsadder:influville:location", OR + "Agence", lore(["Voir les annonces de la ville."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir agence ma depuis:parcelle")]}),
    bouton("aller", 37, "itemsadder:influville:spawn", OR + "Y aller", lore(["Point d'arrivée de la parcelle."], [(GAUCHE, "Se téléporter")]),
           {"left": [iv("parcelle aller")]}),
    bouton("arrivee", 38, "itemsadder:influitems:home", OR + "Point d'arrivée ici",
           lore(["Place-toi dans la parcelle,", "là où les visiteurs arrivent."], [(GAUCHE, "Définir ici")]),
           {"left": [iv("parcelle spawn"), SON]}, conditions=[MAIRE]),
    bouton("renommer", 39, "itemsadder:influville:renommer", OR + "Renommer", lore(["Nom : &f" + pc("nom")], [(GAUCHE, "Choisir un nom")]),
           {"left": [iv("parcelle renommer")]}, conditions=[MAIRE]),
    bouton("delimiter", 40, "itemsadder:influville:parcelle", OR + "Délimiter avec ma sélection",
           lore(["La parcelle existe mais n'a", "pas encore de zone. Sélectionne", "ses deux coins : elle sera", "délimitée aussitôt."], [(GAUCHE, "Délimiter")]),
           {"left": [iv("parcelle delimiter")]}, priorite=-3,
           conditions=[MAIRE, "placeholder: " + pc("delimitee") + " == non"], extra={"glow": "true"}),
    bouton("poser", 40, "itemsadder:influitems:accept", OR + "Poser l'annonce ici",
           lore(["Ton annonce est prête. Place-toi", "dans ta ville, juste à côté de", "la parcelle (dans la rue devant),", "là où l'hologramme doit apparaître."],
                [(GAUCHE, "Poser l'annonce ici"), (DROIT, "Abandonner l'annonce")]),
           {"left": [iv("annonce publier")], "right": [iv("annonce abandonner"), SON, "[refresh]"]}, priorite=-4,
           conditions=[MAIRE, "placeholder: " + pc("brouillon") + " == oui"], extra={"glow": "true"}),
    bouton("publier", 40, "itemsadder:influitems:add", OR + "Mettre à l'Agence",
           lore(["Loue ou vends la parcelle. Une", "fenêtre demande le prix et les", "durées, puis l'hologramme se pose", "là où tu te tiens, à côté de la", "parcelle (pas dedans)."],
                [(GAUCHE, "Mettre en location"), (DROIT, "Mettre en vente")]),
           {"left": [iv("parcelle location")], "right": [iv("parcelle vente")]},
           conditions=[MAIRE, "placeholder: " + pc("annonce") + " == non", "placeholder: " + pc("occupee") + " == non"],
           extra={"glow": "true"}),
    bouton("annonce_cours", 40, pc("icone"), OR + pc("etat"),
           lore([pc("annonce_ligne"), "Modifier rouvre la fenêtre, puis", "replace l'hologramme là où tu", "te tiens."],
                [(GAUCHE, "Modifier l'annonce"), (DROIT, "Retirer l'annonce")]),
           {"left": [iv("parcelle modifier")], "right": [iv("parcelle retirer"), SON, "[refresh]"]},
           priorite=-1, conditions=[MAIRE, "placeholder: " + pc("annonce") + " == oui"]),
    bouton("terminer", 40, "itemsadder:influitems:lock", OR + "Mettre fin à l'occupation",
           lore([pc("locataire_ligne"), "Lands verse la compensation", "prévue à l'occupant."], [(GAUCHE, "Double clic pour confirmer")]),
           {"left": [iv("parcelle terminer"), SON, "[refresh]"]}, priorite=-2,
           conditions=[MAIRE, "placeholder: " + pc("occupee") + " == oui"],
           extra={"confirm": q("&#FF5C5CClique encore pour mettre fin à l'occupation de " + pc("nom"))}),
    bouton("membres", 41, "itemsadder:influville:habitants", OR + "Habitants de la parcelle",
           lore(["Ajoute ou retire des joueurs", "de cette parcelle."], [(GAUCHE, "Ouvrir")]), {"left": [iv("parcelle habitants")]},
           conditions=[MAIRE]),
    bouton("limites", 42, "itemsadder:influville:parcelle", OR + "Voir les limites",
           lore(["Affiche les bords de la", "parcelle où tu te tiens."], [(GAUCHE, "Afficher")]),
           {"left": ["[close]", "[player] lands view here"]}),
    bouton("liste", 43, "itemsadder:influville:annuaire", OR + "Toutes les parcelles", lore([], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir parcelles")]}),
    bouton("ma_ville", 49, "itemsadder:influville:ville", OR + "Ma ville", lore([], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir ville")]}),
    bouton("supprimer", 53, "itemsadder:influitems:deny", OR + "Supprimer la parcelle",
           lore(["Ouvre la liste des parcelles", "de Lands : survole " + pc("nom") + " et", "appuie sur ta touche Jeter (Q).",
                 "Impossible tant qu'une annonce", "ou un occupant s'y trouve."], [(GAUCHE, "Double clic pour continuer")]),
           {"left": [iv("parcelle supprimer")]}, conditions=[MAIRE],
           extra={"confirm": q("&#FF5C5CClique encore pour supprimer la parcelle " + pc("nom"))}),
]
menu("ville_parcelle", "menu_ville_parcelle", parcelle, widgets=NOM_EN_TETE % pc("nom"), doc="Une parcelle de ma ville : état, annonce, occupant, gestion.")

# Rôles de ma ville
# Le maire gère les rôles d'habitants (2026-11-01) : clic gauche permissions, clic droit renommer ou supprimer,
# maj + clic monter ou descendre dans l'ordre de priorité, « + » pour en créer un. Les rôles de Lands (maire,
# passants, nouveaux arrivants, locataires, compatriotes) n'ont que leurs permissions.
roles = [retour_liste()]
for n, slot in enumerate(GRILLE, start=1):
    base = ["Type : &f" + l(n, "type"), "&f" + l(n, "droits") + VIOLET + " permissions accordées"]
    existe = "placeholder: %s == oui" % l(n, "existe")
    roles.append(bouton("role_%d_gestion" % n, slot, l(n, "icone"), l(n, "nom"),
                        lore(base, [(GAUCHE, "Modifier les permissions"), (DROIT, "Renommer ou supprimer"),
                                    (MAJ_GAUCHE, "Monter dans l'ordre"), (MAJ_DROIT, "Descendre dans l'ordre")]),
                        {"left": [iv("clic %d gauche" % n)], "right": [iv("clic %d droit" % n)],
                         "shift-left": [iv("clic %d monter" % n), SON, "[refresh]"], "shift-right": [iv("clic %d descendre" % n), SON, "[refresh]"]},
                        conditions=[existe, "placeholder: %s == oui" % l(n, "habitant"), MAIRE], priorite=-2))
    roles.append(bouton("role_%d" % n, slot, l(n, "icone"), l(n, "nom"),
                        lore(base, [(GAUCHE, "Modifier les permissions")]),
                        {"left": [iv("clic %d gauche" % n)]},
                        conditions=[existe, "placeholder: %s == oui" % l(n, "modifiable")], priorite=-1))
    roles.append(bouton("role_%d_maire" % n, slot, l(n, "icone"), l(n, "nom"),
                        lore(["Type : &f" + l(n, "type"), "Le maire a toujours", "toutes les permissions."]),
                        conditions=[existe]))
roles += barre_liste("itemsadder:influville:roles", "Rôles de " + m("nom"),
                     ["Du plus important au moins important. Chaque rôle a ses propres permissions."], filtre=False, tri=False,
                     action=bouton("creer", 53, "itemsadder:influitems:add", OR + "Créer un rôle",
                                   lore(["Un nouveau rôle d'habitant, en bas de la liste."], [(GAUCHE, "Choisir un nom")]),
                                   {"left": [iv("role creer")]}, conditions=[MAIRE]),
                     gauche=bouton("habitants", 45, "itemsadder:influville:habitants", OR + "Habitants",
                                   lore(["Le maire change le rôle d'un habitant d'un clic sur sa tête."], [(GAUCHE, "Ouvrir")]),
                                   {"left": [iv("ouvrir habitants ma depuis:roles")]}))
menu("ville_roles", "menu_roles", roles, ouverture=[iv("vue roles"), "[refresh]"], doc="Rôles de ma ville.")

# Permissions d'un rôle
droits = [retour_liste()]
for n, slot in enumerate(GRILLE, start=1):
    d = [l(n, "desc"), "", l(n, "etat")]
    droits.append(bouton("droit_%d_on" % n, slot, l(n, "icone"), "&f" + l(n, "nom"), lore(d, [(GAUCHE, "Interdire")]),
                         {"left": [iv("clic %d gauche" % n), SON, "[refresh]"]}, priorite=-1,
                         conditions=["placeholder: %s == oui" % l(n, "existe"), "placeholder: %s == oui" % l(n, "actif")],
                         extra={"glow": "true"}))
    droits.append(bouton("droit_%d" % n, slot, l(n, "icone"), "&7" + l(n, "nom"), lore(d, [(GAUCHE, "Autoriser")]),
                         {"left": [iv("clic %d gauche" % n), SON, "[refresh]"]},
                         conditions=["placeholder: %s == oui" % l(n, "existe")]))
droits += barre_liste("%fluxlands_role_icone%", "Permissions : %fluxlands_role_nom%",
                      ["Ce que ce rôle peut faire.", "Brillant = autorisé.", "Filtre : actions dans la ville", "ou gestion de la ville."],
                      tri=False)
menu("ville_droits", "menu_droits", droits, ouverture=[iv("vue droits"), "[refresh]"], doc="Permissions d'un rôle de ma ville.")

# Paramètres de ma ville
parametres = [retour_liste()]
for n, slot in enumerate(GRILLE, start=1):
    d = [l(n, "desc"), "", l(n, "etat")]
    parametres.append(bouton("param_%d_on" % n, slot, l(n, "icone"), "&f" + l(n, "nom"), lore(d, [(GAUCHE, "Désactiver")]),
                             {"left": [iv("clic %d gauche" % n), SON, "[refresh]"]}, priorite=-1,
                             conditions=["placeholder: %s == oui" % l(n, "existe"), "placeholder: %s == oui" % l(n, "actif")],
                             extra={"glow": "true"}))
    parametres.append(bouton("param_%d" % n, slot, l(n, "icone"), "&7" + l(n, "nom"), lore(d, [(GAUCHE, "Activer")]),
                             {"left": [iv("clic %d gauche" % n), SON, "[refresh]"]},
                             conditions=["placeholder: %s == oui" % l(n, "existe")]))
parametres += barre_liste("itemsadder:influitems:settings", "Paramètres de " + m("nom"),
                          ["Règles naturelles de la ville", "(explosions, feu, monstres...).", "Brillant = activé."], tri=False,
                          gauche=False)
menu("ville_parametres", "menu_parametres", parametres, ouverture=[iv("vue parametres"), "[refresh]"], doc="Paramètres naturels de ma ville.")

# ── Accueil partagé « Villes et Pays » ─────────────────────────────────────
# Refait le 2026-11-08 (demande de l'utilisateur : plus de texte dans le menu hors titre, plus de cartes sombres),
# boutons en 3 x 3 depuis le 2026-11-10 : Retour (18) et information sans action (26) sur la première rangée dessinée,
# puis deux grands boutons de 3 x 3 cases côte à côte et symétriques (Ma ville colonnes 1-3, Mon pays colonnes 5-7, rangées 3-5 : objets invisibles pour le
# clic et l'infobulle, grande icône dessinée dans le fond fluxui:bg_territoires, Flux/assets/vanilla/gen_ui.py), puis
# Explorer sur la première rangée de l'inventaire (colonnes 2 à 6), sous l'écart entre le coffre et l'inventaire.
INVISIBLE = "itemsadder:influitems:invisible"
CARTE_VILLE = [28, 29, 30, 37, 38, 39, 46, 47, 48]   # garder identique à TERRITORY_BUTTONS de gen_ui.py
CARTE_PAYS = [32, 33, 34, 41, 42, 43, 50, 51, 52]
VISIBLE = {"hide-tooltip": "false"}
AVEC_VILLE = "placeholder: " + m("existe") + " == oui"
AVEC_PAYS = "placeholder: " + mp("existe") + " == oui"

territoires = [
    retour(18, ["[menu] main"], "Menu principal"),
    bouton("info", 26, "itemsadder:influville:ville", OR + "Villes et Pays",
           lore(["Gère ta ville et ton pays, et explore ceux des autres joueurs.", "",
                 "Villes : &f%fluxlands_nb_villes_serveur%", "Pays : &f%fluxlands_nb_pays%",
                 "Annonces à l'Agence : &f%fluxlands_annonces%"])),
    bouton("ma_ville", CARTE_VILLE, INVISIBLE, OR + "Ma ville : " + m("nom_couleur"),
           lore(["Niveau : &f" + m("niveau"), "Ton rôle : &f" + m("role"),
                 "Habitants : &f" + m("habitants") + " &7(" + m("en_ligne") + " en ligne)", "Banque : &f" + m("solde") + " " + BB],
                [(GAUCHE, "Ouvrir Ma ville"), (DROIT, "Fiche publique")]),
           {"left": [iv("ouvrir ville")], "right": [iv("ouvrir fiche ma")]},
           priorite=-1, conditions=[AVEC_VILLE], extra=VISIBLE),
    bouton("sans_ville", CARTE_VILLE, INVISIBLE, OR + "Ville",
           lore(["Tu n'habites aucune ville. Fonde la tienne ou rejoins une ville existante."], [(GAUCHE, "Commencer")]),
           {"left": [iv("ouvrir ville")]}, extra=VISIBLE),
    bouton("mon_pays", CARTE_PAYS, INVISIBLE, OR + "Mon pays : " + mp("nom_couleur"),
           lore(["Niveau : &f" + mp("niveau"), "Capitale : &f" + mp("capitale"), "Villes : &f" + mp("villes"),
                 "Habitants : &f" + mp("habitants")], [(GAUCHE, "Ouvrir Mon pays")]),
           {"left": [iv("ouvrir pays")]}, priorite=-1, conditions=[AVEC_PAYS], extra=VISIBLE),
    bouton("sans_pays", CARTE_PAYS, INVISIBLE, OR + "Pays",
           lore(["Ta ville n'appartient à aucun pays. Un pays réunit plusieurs villes autour d'une capitale."],
                [(GAUCHE, "Découvrir")]),
           {"left": [iv("ouvrir pays")]}, extra=VISIBLE),
    # Explorer (première rangée de l'inventaire) : seulement ce qui n'est ni dans Ma ville ni dans Mon pays.
    bouton("annuaire", 56, "itemsadder:influville:annuaire", OR + "Annuaire des villes", lore(["Toutes les villes du serveur."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir annuaire depuis:territoires")]}),
    bouton("warps", 57, "itemsadder:influville:warp", OR + "Warps", lore(["Les warps des joueurs, classés par ville."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir warps toutes depuis:territoires")]}, conditions=["placeholder: %fluxlands_modules_warps% == oui"]),
    bouton("agence", 58, "itemsadder:influville:agence", OR + "Agence", lore(["Parcelles à louer ou à vendre dans toutes les villes."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir agence depuis:territoires")]}),
    bouton("echoppes", 59, "itemsadder:influville:echoppe", OR + "Échoppes", lore(["Où acheter et vendre dans les villes."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir echoppes toutes depuis:territoires")]}, conditions=["placeholder: %fluxlands_modules_echoppes% == oui"]),
    bouton("annuaire_pays", 60, "itemsadder:influville:nation", OR + "Annuaire des pays", lore(["Tous les pays du serveur."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir pays_annuaire depuis:territoires")]}),
]
menu("territoires", "menu_territoires", territoires,
     doc="Villes et Pays : accueil partagé ouvert par le bouton Ville et Pays de /menu2.")

# ── Mon pays ───────────────────────────────────────────────────────────────
# Refait le 2026-11-01 comme Ma ville : case 8 = information sans action ; rangée 2 : niveau, fiche publique, capitale ;
# rangée 4 : la vie du pays ; rangée 5 : ce que fait le chef (ou le maire d'une ville membre : quitter). Plus d'alliances
# ni de guerre (coupées dans Lands), plus de menu complet de Lands. Taxes et effets : nos menus (2026-11-01).
CHEF = "placeholder: " + mp("est_chef") + " == oui"
INFO_PAYS = ["Niveau : &f" + mp("niveau"), "Chef : &f" + mp("chef"), "Capitale : &f" + mp("capitale"),
             "Fondation : &f" + mp("creation"), "Villes : &f" + mp("villes"),
             "Habitants : &f" + mp("habitants") + " &7(" + mp("en_ligne") + " en ligne)", "Chunks : &f" + mp("chunks"),
             "Trésor : &f" + mp("tresor") + " " + BB]
mon_pays = [
    retour(0, ["[menu] territoires"], "Villes et Pays", droit=RETOUR_HUB),
    bouton("info", 8, "itemsadder:influville:pays", mp("nom_couleur"),
           lore(["Le tableau de bord de ton pays. Le trésor est la banque de la capitale, qui paie les frais d'entretien du pays.", ""]
                + INFO_PAYS)),
    bouton("niveau", 20, mp("icone"), OR + "Niveau &f" + mp("niveau_num") + OR + "/4",
           lore([mp("niveau"), "Le niveau du pays débloque des chunks et des effets pour toutes ses villes."],
                [(GAUCHE, "Objectifs du niveau suivant")]),
           {"left": [iv("niveau pays")]}),
    bouton("fiche", 22, "itemsadder:influville:epingle", OR + "Fiche publique",
           lore(["Ton pays tel que les autres joueurs le voient."], [(GAUCHE, "Voir la fiche publique")]),
           {"left": [iv("ouvrir pays_fiche mon")]}),
    bouton("capitale_fiche", 24, "itemsadder:influville:capitale", OR + "Capitale : " + mp("capitale_couleur"),
           lore(["La capitale paie les frais d'entretien du pays."], [(GAUCHE, "Fiche de la capitale")]),
           {"left": [iv("ouvrir fiche " + mp("capitale"))]}),
    # Rangée 4
    bouton("villes", 38, "itemsadder:influville:ville", OR + "Villes du pays", lore([mp("villes") + " villes membres."], [(GAUCHE, "Voir")]),
           {"left": [iv("ouvrir pays_villes mon")]}),
    bouton("spawn", 39, "itemsadder:influville:spawn", OR + "Spawn du pays", lore(["Spawn de la capitale."], [(GAUCHE, "Se téléporter")]),
           {"left": [iv("pays spawn mon")]}),
    bouton("chat", 40, "itemsadder:influville:message", OR + "Tchat du pays",
           lore(["Parle seulement aux habitants du pays (bascule)."], [(GAUCHE, "Activer / désactiver")]), {"left": [iv("pays chat")]}),
    bouton("taxes", 41, "itemsadder:influville:taxes", OR + "Taxes", lore(["Taxes versées par les villes au pays."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("pays taxes")]}),
    bouton("effets", 42, "itemsadder:influville:effets", OR + "Effets", lore(["Bonus accordés aux habitants du pays."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("pays effets")]}),
    # Rangée 5
    bouton("inviter", 47, "itemsadder:influitems:add", OR + "Inviter une ville",
           lore(["Choisis une ville sans pays dans la liste."], [(GAUCHE, "Choisir")]), {"left": [iv("pays inviter")]}, conditions=[CHEF]),
    bouton("capitale", 49, "itemsadder:influville:capitale", OR + "Changer de capitale",
           lore(["Choisis la nouvelle capitale parmi les villes du pays."], [(GAUCHE, "Choisir")]),
           {"left": [iv("pays capitale")]}, conditions=[CHEF]),
    bouton("quitter", 49, "itemsadder:influville:quitter", OR + "Quitter le pays",
           lore(["Ta ville quitte " + mp("nom") + "."], [(GAUCHE, "Double clic pour confirmer")]), {"left": [iv("pays quitter")]},
           conditions=["placeholder: " + mp("maire_ici") + " == oui", "placeholder: " + mp("capitale_moi") + " == non"],
           extra={"confirm": q("&#FF5C5CClique encore pour faire quitter " + mp("nom") + " à ta ville")}),
    bouton("renommer", 51, "itemsadder:influville:renommer", OR + "Renommer le pays", lore(["Nom actuel : &f" + mp("nom")], [(GAUCHE, "Choisir un nom")]),
           {"left": [iv("pays renommer")]}, conditions=[CHEF]),
]
menu("pays", "menu_pays", mon_pays, widgets=NOM_EN_TETE % mp("nom"), doc="Mon pays : tableau de bord du pays de ma ville (/pays, /p).")

# ── Accueil des pays (ville sans pays) ─────────────────────────────────────
pays_accueil = [
    retour(0, ["[menu] territoires"], "Villes et Pays", droit=RETOUR_HUB),
    bouton("annuaire", 20, "itemsadder:influville:annuaire", OR + "Annuaire des pays",
           lore(["Découvre les pays existants", "et leurs villes."], [(GAUCHE, "Ouvrir")]), {"left": [iv("ouvrir pays_annuaire depuis:pays_accueil")]}),
    bouton("fonder", 22, "itemsadder:influitems:add", OR + "Fonder un pays",
           lore(["Ta ville en devient la capitale.", "Réservé au maire.", "", "Serveur en bêta : les règles", "des pays peuvent évoluer."], [(GAUCHE, "Choisir un nom")]),
           {"left": [iv("pays creer")]}),
    bouton("rejoindre", 24, "itemsadder:influville:inviter_ville", OR + "Rejoindre un pays",
           lore(["Accepte l'invitation qu'un pays a envoyée à ta ville. Le chef d'un pays invite les villes depuis son menu."],
                [(GAUCHE, "Voir les invitations")]), {"left": [iv("pays accepter")]}),
]
menu("pays_accueil", "menu_pays_accueil", pays_accueil, doc="Accueil des pays, pour une ville qui n'appartient à aucun pays.")

# ── Fiche publique d'un pays ───────────────────────────────────────────────
pays_fiche = [
    retour(0, [iv("ouvrir pays_annuaire")], "Annuaire des pays", droit=("Mon pays", [iv("ouvrir pays")])),
    bouton("spawn", 8, "itemsadder:influville:spawn", OR + "Spawn de " + fp("nom"), lore(["Spawn de la capitale."], [(GAUCHE, "Se téléporter")]),
           {"left": [iv("pays spawn fiche")]}, conditions=["placeholder: " + fp("spawn") + " == oui"]),
    bouton("blason", 19, fp("icone"), fp("nom_couleur"), lore(["Niveau : &f" + fp("niveau"), "Chef : &f" + fp("chef"), "Fondé le &f" + fp("creation")])),
    bouton("stat_villes", 20, "itemsadder:influville:ville", OR + "Villes &f" + fp("villes"), lore(["Villes membres."])),
    bouton("stat_habitants", 21, "itemsadder:influville:habitants", OR + "Habitants &f" + fp("habitants"), lore(["En ligne : &f" + fp("en_ligne")])),
    bouton("stat_chunks", 22, "itemsadder:influville:chunks", OR + "Chunks &f" + fp("chunks"), lore(["Territoire du pays."])),
    bouton("stat_niveau", 23, "itemsadder:influville:niveau", OR + "Niveau &f" + fp("niveau_num") + OR + "/4", lore([fp("niveau")])),
    bouton("stat_capitale", 24, "itemsadder:influville:capitale", OR + "Capitale : " + fp("capitale_couleur"), lore([], [(GAUCHE, "Fiche de la capitale")]),
           {"left": [iv("ouvrir fiche " + fp("capitale"))]}),
    bouton("stat_chef", 25, "itemsadder:influville:roles", OR + "Chef : &f" + fp("chef"), lore(["Maire de la capitale."])),
    bouton("villes", 38, "itemsadder:influville:ville", OR + "Villes de " + fp("nom"), lore([fp("villes") + " villes."], [(GAUCHE, "Voir")]),
           {"left": [iv("ouvrir pays_villes fiche")]}),
    bouton("spawn_bas", 40, "itemsadder:influville:spawn", OR + "Visiter", lore(["Spawn de la capitale."], [(GAUCHE, "Se téléporter")]),
           {"left": [iv("pays spawn fiche")]}),
    bouton("annuaire", 42, "itemsadder:influville:annuaire", OR + "Annuaire des pays", lore([], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir pays_annuaire")]}),
]
menu("pays_fiche", "menu_pays_fiche", pays_fiche, doc="Fiche publique d'un pays.")

# ── Annuaire des pays ──────────────────────────────────────────────────────
pays_annuaire = [retour_liste()]
for n, slot in enumerate(GRILLE, start=1):
    pays_annuaire.append(bouton("pays_%d" % n, slot, l(n, "icone"), l(n, "nom_couleur"),
                                lore(["Niveau : &f" + l(n, "niveau"), "Capitale : &f" + l(n, "capitale"), "Chef : &f" + l(n, "chef"),
                                      "Villes : &f" + l(n, "villes") + VIOLET + "  Habitants : &f" + l(n, "habitants"),
                                      "Chunks : &f" + l(n, "chunks")],
                                     [(GAUCHE, "Voir la fiche"), (DROIT, "Se téléporter au spawn")]),
                                {"left": [iv("clic %d gauche" % n)], "right": [iv("clic %d droit" % n)]},
                                conditions=["placeholder: %s == oui" % l(n, "existe")]))
pays_annuaire.append(vide("itemsadder:influville:pays", "Aucun pays", ["Aucun pays n'a encore été", "fondé. Sois le premier !"]))
pays_annuaire += barre_liste("itemsadder:influville:pays", "Annuaire des pays",
                             ["Tous les pays du serveur,", "du plus puissant au plus", "modeste."], filtre=False,
                             action=bouton("fonder", 53, "itemsadder:influitems:add", OR + "Fonder un pays",
                                           lore(["Ta ville en sera la capitale."], [(GAUCHE, "Choisir un nom")]), {"left": [iv("pays creer")]},
                                           conditions=["placeholder: " + mp("existe") + " == non", "placeholder: " + m("est_maire") + " == oui"]),
                             gauche=bouton("mon_pays", 45, "itemsadder:influville:pays", OR + "Mon pays", lore([], [(GAUCHE, "Ouvrir")]),
                                           {"left": [iv("ouvrir pays")]}))
menu("pays_annuaire", "menu_pays_annuaire", pays_annuaire, ouverture=[iv("vue pays"), "[refresh]"], doc="Annuaire des pays.")

# ── Villes d'un pays ───────────────────────────────────────────────────────
pays_villes = [retour_liste()]
for n, slot in enumerate(GRILLE, start=1):
    desc = [l(n, "statut_pays"), "Niveau : &f" + l(n, "niveau"), "Maire : &f" + l(n, "maire"),
            "Habitants : &f" + l(n, "habitants") + " &7(" + l(n, "en_ligne") + " en ligne)", "Chunks : &f" + l(n, "chunks")]
    pays_villes.append(bouton("ville_%d_chef" % n, slot, l(n, "icone"), l(n, "nom_couleur"),
                              lore(desc, [(GAUCHE, "Voir la fiche"), (MAJ_DROIT, "Retirer la ville du pays")]),
                              {"left": [iv("clic %d gauche" % n)], "shift-right": [iv("clic %d retirer" % n), SON, "[refresh]"]},
                              priorite=-1, conditions=["placeholder: %s == oui" % l(n, "existe"), "placeholder: %s == oui" % l(n, "retirable")]))
    pays_villes.append(bouton("ville_%d" % n, slot, l(n, "icone"), l(n, "nom_couleur"), lore(desc, [(GAUCHE, "Voir la fiche")]),
                              {"left": [iv("clic %d gauche" % n)]}, conditions=["placeholder: %s == oui" % l(n, "existe")]))
pays_villes += barre_liste("itemsadder:influville:pays", "Villes de " + fp("nom"),
                           [fp("villes") + " villes, capitale :", fp("capitale_couleur")],
                           action=bouton("inviter", 53, "itemsadder:influitems:add", OR + "Inviter une ville",
                                         lore(["Réservé au chef du pays."], [(GAUCHE, "Choisir une ville")]), {"left": [iv("pays inviter")]},
                                         conditions=["placeholder: " + fp("est_chef") + " == oui"]),
                           gauche=bouton("pays_fiche", 45, "itemsadder:influville:pays", OR + "Fiche du pays", lore([], [(GAUCHE, "Ouvrir")]),
                                         {"left": [iv("ouvrir pays_fiche courante")]}))
menu("pays_villes", "menu_pays_villes", pays_villes, ouverture=[iv("vue pays_villes"), "[refresh]"], doc="Villes membres d'un pays.")

for ancien in ANCIENS:
    p = os.path.join(SORTIE, ancien)
    if os.path.exists(p):
        os.remove(p)
print("menus écrits dans", SORTIE)


# ── Objectifs du niveau suivant (ville ou pays) ────────────────────────────
# Remplace le menu des niveaux de Lands (2026-11-01). Contenu écrit en Java (Levels.java) : listes Flux
# fluxlands_level_info (case 8), fluxlands_level_goals (un bouton par objectif) et fluxlands_level_up (passage).
LISTES_NIVEAU = """lists:
  fluxlands_level_info:
    slots: 8
    item:
      material: entry
  fluxlands_level_goals:
    slots: 20,22,24
    item:
      material: entry
  fluxlands_level_up:
    slots: 49
    item:
      material: entry
      actions:
        left: ["[player] fluxlands niveau monter"]
"""
niveau = [
    bouton("retour", 0, "itemsadder:influitems:btn_back", OR + "Retour", lore([], [(GAUCHE, "Revenir")]),
           {"left": [iv("niveau retour")]}),
]
menu("ville_niveau", "menu_ville", niveau, listes=LISTES_NIVEAU,
     doc="Objectifs du niveau suivant de ma ville ou de mon pays, et passage au niveau suivant (remplace le menu de Lands).")


# ── Taxes du pays ──────────────────────────────────────────────────────────
# Notre menu (2026-11-01, CountryMenus.java) : le chef fixe la taxe par chunk (curseur), la liste montre ce que chaque
# ville membre verse.
LISTES_TAXES = """lists:
  fluxlands_country_tax_info:
    slots: 22
    item:
      material: entry
      actions:
        left: ["[player] fluxlands pays taxe"]
  fluxlands_country_tax_towns:
    slots: 37-43,46-52
    item:
      material: entry
"""
taxes = [
    retour(0, [iv("ouvrir pays")], "Mon pays"),
    bouton("info", 8, "itemsadder:influville:taxes", OR + "Taxes du pays",
           lore(["Chaque ville membre verse au pays une taxe par chunk revendiqué, prélevée avec les frais d'entretien. "
                 "Le chef fixe le montant entre les bornes du serveur.", "", "En dessous : ce que verse chaque ville."])),
]
menu("pays_taxes", "menu_pays", taxes, listes=LISTES_TAXES, doc="Taxes de mon pays (remplace le menu de Lands).")

# ── Effets du pays ─────────────────────────────────────────────────────────
# Notre menu (2026-11-01) : effets débloqués par niveau (CountryEffects.java, config country-effects), le chef
# active ceux qu'il veut dans la limite du niveau. Appliqués aux habitants du pays présents dans ses villes.
LISTES_EFFETS = """lists:
  fluxlands_country_effects_info:
    slots: 8
    item:
      material: entry
  fluxlands_country_effects:
    slots: 20-24,29-33
    item:
      material: entry
      actions:
        left: ["[player] fluxlands pays effet %entry_effect%", "[sound] @click", "[refresh]"]
"""
effets = [retour(0, [iv("ouvrir pays")], "Mon pays")]
menu("pays_effets", "menu_pays", effets, listes=LISTES_EFFETS, doc="Effets de mon pays (remplace le menu de Lands).")
