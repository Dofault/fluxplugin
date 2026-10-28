#!/usr/bin/env python3
"""Menus Flux des villes et pays (FluxLands) : écrit menus/territoires.yml, ville*.yml, villes.yml, warps.yml,
echoppes.yml, agence.yml, pays*.yml.

    python3 gen_menus_ville.py <plugins/Flux/menus>

Ne pas modifier les menus générés à la main : changer ce script puis le relancer et /fluxmenu reload.
Données : placeholders %influlands_...% et commande /influlands du plugin FluxLands
(docs/technique/influlands.md). Fonds et icônes : pack ItemsAdder « influville » (gen_ville.py).
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
ANCIENS = ["adresses.yml"]  # menus renommés, supprimés à la génération


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


def menu(nom, fond, items, commandes=None, ouverture=(), doc=""):
    out = [ENTETE, "# " + doc + "\n\n" if doc else "\n",
           "title: %s\n" % q(":offset_-8::%s:" % fond), "rows: 6\n"]
    if commandes:
        out.append("commands: [%s]\n" % ", ".join(commandes))
    out += ["player-inventory: hidden\n", "vanilla-background: false\n", "update-interval: 20\n\n", "open-actions:\n"]
    for a in list(ouverture) + ["[sound] @page"]:
        out.append("  - %s\n" % q(a))
    out.append("\nitems:\n\n")
    out += [i + "\n" for i in items]
    with open(os.path.join(SORTIE, nom + ".yml"), "w") as f:
        f.write("".join(out))


def iv(cmd):
    return "[player] influlands " + cmd


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
    return "%influlands_ma_" + c + "%"


def mp(c):
    return "%influlands_monpays_" + c + "%"


def fp(c):
    return "%influlands_paysfiche_" + c + "%"


def f(c):
    return "%influlands_fiche_" + c + "%"


def pc(c):
    return "%influlands_parcelle_" + c + "%"


def an(c):
    return "%influlands_annonce_" + c + "%"


def bq(c):
    return "%influlands_banque_" + c + "%"


MAIRE = "placeholder: " + m("est_maire") + " == oui"


def selecteur(prefixe, unite, affichage, cles=(10000, 1000, 100), extra_aff=None):
    """Rangée 4 (37 à 43) : trois boutons -, l'affichage au centre, trois boutons +."""
    items = []
    for i, v in enumerate(cles):
        lib = "{:,}".format(v).replace(",", " ")
        items.append(bouton("moins_%d" % v, 37 + i, "itemsadder:influitems:remove", OR + "-" + lib + unite,
                            lore([], [(GAUCHE, "Retirer " + lib)]), {"left": [iv(prefixe + " -" + str(v)), SON, "[refresh]"]}))
        items.append(bouton("plus_%d" % v, 43 - i, "itemsadder:influitems:add", OR + "+" + lib + unite,
                            lore([], [(GAUCHE, "Ajouter " + lib)]), {"left": [iv(prefixe + " +" + str(v)), SON, "[refresh]"]}))
    items.append(affichage)
    return items


# ── Ma ville ───────────────────────────────────────────────────────────────
ma_ville = [
    retour(0, ["[menu] territoires"], "Villes et Pays", droit=RETOUR_HUB),
    bouton("fiche_multi", 8, m("icone"), m("nom_couleur"),
           lore(["Niveau : &f" + m("niveau"), "Maire : &f" + m("maire"), "Ton rôle : &f" + m("role"),
                 "Fondée le &f" + m("creation"), "", "Tu habites &f%influlands_nb_villes%" + VIOLET + " villes."],
                [(GAUCHE, "Voir la fiche publique"), (DROIT, "Afficher ma ville suivante")]),
           {"left": [iv("ouvrir fiche ma")], "right": [iv("choisir"), SON, "[refresh]"]},
           priorite=-1, conditions=["placeholder: %influlands_nb_villes% >= 2"]),
    bouton("fiche", 8, m("icone"), m("nom_couleur"),
           lore(["Niveau : &f" + m("niveau"), "Maire : &f" + m("maire"), "Ton rôle : &f" + m("role"),
                 "Fondée le &f" + m("creation")],
                [(GAUCHE, "Voir la fiche publique")]),
           {"left": [iv("ouvrir fiche ma")]}),
    # Statistiques (rangée 2)
    bouton("stat_niveau", 19, m("icone"), OR + "Niveau &f" + m("niveau_num") + OR + "/7",
           lore([m("niveau"), "Le niveau de la ville augmente", "ses limites (chunks, habitants)."], [(GAUCHE, "Objectifs du niveau suivant")]),
           {"left": [iv("natif menu land level")]}),
    bouton("stat_habitants", 20, "itemsadder:influville:habitants", OR + "Habitants &f" + m("habitants"),
           lore(["En ligne : &f" + m("en_ligne")])),
    bouton("stat_chunks", 21, "itemsadder:influville:chunks", OR + "Chunks &f" + m("chunks") + OR + "/&f" + m("chunks_max"),
           lore(["Chunks revendiqués par la ville,", "sur le maximum de son niveau."])),
    bouton("stat_banque", 22, "itemsadder:influitems:bitblock", OR + "Banque",
           lore(["Solde : &f" + m("solde") + " " + BB, "Paie les frais d'entretien", "et les agrandissements."])),
    bouton("stat_entretien", 23, "itemsadder:influville:entretien", OR + "Frais d'entretien",
           lore(["&f" + m("entretien") + " " + BB + VIOLET + " par jour,", "prélevés sur la banque."])),
    bouton("stat_droit", 24, "itemsadder:influville:commerce", OR + "Droit de place &f" + m("taux") + " %",
           lore(["Part des ventes des échoppes", "de la ville versée à sa banque.", "",
                 "Aujourd'hui : &f" + m("recette_jour") + " " + BB, "7 jours : &f" + m("recette_7j") + " " + BB])),
    bouton("mon_pays", 25, mp("icone"), OR + "Mon pays : " + mp("nom_couleur"),
           lore(["Capitale : &f" + mp("capitale"), "Villes : &f" + mp("villes") + VIOLET + "  Niveau : &f" + mp("niveau")], [(GAUCHE, "Ouvrir Mon pays")]),
           {"left": [iv("ouvrir pays")]}, priorite=-1, conditions=["placeholder: " + mp("existe") + " == oui"]),
    bouton("sans_pays", 25, "itemsadder:influville:pays", OR + "Pays",
           lore(["Ta ville n'appartient à", "aucun pays."], [(GAUCHE, "Fonder ou rejoindre un pays")]),
           {"left": [iv("ouvrir pays")]}),
    # Gestion (rangée 4)
    bouton("banque", 37, "itemsadder:influville:banque", OR + "Banque",
           lore(["Solde : &f" + m("solde") + " " + BB, "Dépose ou retire avec", "les boutons + et -."], [(GAUCHE, "Ouvrir la banque")]),
           {"left": [iv("ouvrir banque")]}),
    bouton("habitants", 38, "itemsadder:influville:habitants", OR + "Habitants",
           lore([m("habitants") + " habitants, " + m("en_ligne") + " en ligne."], [(GAUCHE, "Voir les habitants"), (DROIT, "Inviter un joueur")]),
           {"left": [iv("ouvrir habitants ma")], "right": [iv("saisie inviter")]}),
    bouton("echoppes", 39, "itemsadder:influville:echoppe", OR + "Échoppes",
           lore([m("echoppes") + " échoppes dans la ville."], [(GAUCHE, "Échoppes de la ville"), (DROIT, "Mes échoppes")]),
           {"left": [iv("ouvrir echoppes ma")], "right": [iv("ouvrir echoppes miennes")]},
           conditions=["placeholder: %influlands_modules_echoppes% == oui"]),
    bouton("spawn", 40, "itemsadder:influville:spawn", OR + "Spawn de la ville",
           lore(["Retourne au point d'apparition", "de ta ville."], [(GAUCHE, "Se téléporter")]),
           {"left": [iv("spawn ma")]}),
    bouton("warps", 41, "itemsadder:influville:warp", OR + "Warps",
           lore([m("adresses") + " warps dans la ville."], [(GAUCHE, "Warps de la ville"), (DROIT, "Créer un warp ici")]),
           {"left": [iv("ouvrir warps ma")], "right": [iv("saisie creer_warp")]},
           conditions=["placeholder: %influlands_modules_warps% == oui"]),
    bouton("commerce", 42, "itemsadder:influville:commerce", OR + "Commerce",
           lore(["Droit de place, recettes", "et meilleures échoppes."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir commerce")]}),
    bouton("annuaire", 43, "itemsadder:influville:annuaire", OR + "Annuaire des villes",
           lore(["Toutes les villes du serveur."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir annuaire")]}),
    bouton("reglages", 45, "itemsadder:influitems:settings", OR + "Réglages",
           lore(["Parcelles, rôles et permissions,", "paramètres, nom, spawn."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir reglages")]}),
    bouton("agence", 49, "itemsadder:influville:agence", OR + "Agence",
           lore(["Parcelles à louer ou à vendre", "dans toutes les villes."], [(GAUCHE, "Toutes les annonces"), (DROIT, "Annonces de ma ville")]),
           {"left": [iv("ouvrir agence")], "right": [iv("ouvrir agence ma")]}),
    bouton("revendiquer", 53, "itemsadder:influville:chunks", OR + "Revendiquer ce chunk",
           lore(["Ajoute le chunk où tu te tiens", "à ta ville (coût prélevé", "sur la banque)."], [(GAUCHE, "Revendiquer")]),
           {"left": ["[close]", "[player] lands claim"]}),
]
menu("ville", "menu_ville", ma_ville, doc="Ma ville : tableau de bord de la ville du joueur (/menu2 > Ville, /lands, /v, /ville).")

# ── Accueil (joueur sans ville) ────────────────────────────────────────────
accueil = [
    retour(0, ["[menu] territoires"], "Villes et Pays", droit=RETOUR_HUB),
    bouton("annuaire", 29, "itemsadder:influville:annuaire", OR + "Annuaire des villes",
           lore(["Trouve une ville qui te plaît", "et demande une invitation", "à son maire."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir annuaire depuis:accueil")]}),
    bouton("fonder", 31, "itemsadder:influitems:add", OR + "Fonder une ville",
           lore(["Crée ta ville à l'endroit", "où tu te tiens.", "", "Serveur en bêta : les règles", "des villes peuvent évoluer."],
                [(GAUCHE, "Choisir un nom")]),
           {"left": [iv("saisie creer_ville")]}),
    bouton("agence", 33, "itemsadder:influville:agence", OR + "Agence",
           lore(["Loue ou achète une parcelle", "dans une ville existante."], [(GAUCHE, "Voir les annonces")]),
           {"left": [iv("ouvrir agence depuis:accueil")]}),
]
menu("ville_accueil", "menu_ville_accueil", accueil, doc="Accueil des villes, pour un joueur qui n'habite aucune ville.")


# ── Fiche publique d'une ville ─────────────────────────────────────────────
def v(n, c):
    return "%%influlands_vitrine_%d_%s%%" % (n, c)


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
    bouton("stat_nation", 25, "itemsadder:influville:nation", OR + "Nation", lore(["&f" + f("nation")])),
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
           conditions=["placeholder: %influlands_modules_warps% == oui"]),
    bouton("echoppes", 51, "itemsadder:influville:echoppe", OR + "Échoppes de " + f("nom"),
           lore([f("echoppes") + " échoppes."], [(GAUCHE, "Voir")]), {"left": [iv("ouvrir echoppes fiche")]},
           conditions=["placeholder: %influlands_modules_echoppes% == oui"]),
]
menu("ville_fiche", "menu_ville_fiche", fiche, doc="Fiche publique d'une ville (annuaire, warps, échoppes).")

# ── Commerce de ma ville ───────────────────────────────────────────────────
commerce = [
    retour(0, [iv("ouvrir ville")], "Ma ville"),
    bouton("info", 8, "itemsadder:influville:commerce", OR + "Droit de place",
           lore(["Chaque vente ou rachat d'une", "échoppe située dans ta ville", "verse une part du montant à", "la banque de la ville. Le maire",
                 "en fixe le taux."])),
    bouton("aujourdhui", 19, "itemsadder:influitems:bitblock", OR + "Aujourd'hui",
           lore(["&f" + m("recette_jour") + " " + BB + VIOLET + " perçus."])),
    bouton("moins", 21, "itemsadder:influitems:remove", OR + "Baisser le taux",
           lore([], [(GAUCHE, "-0,5 point"), (MAJ_GAUCHE, "-2,5 points")]),
           {"left": [iv("droit -"), SON, "[refresh]"], "shift-left": [iv("droit --"), SON, "[refresh]"]},
           conditions=[MAIRE]),
    bouton("taux", 22, "itemsadder:influville:commerce", OR + "Droit de place &f" + m("taux") + " %",
           lore(["Taux appliqué aux échoppes", "de " + m("nom") + ".", "", "Seul le maire peut le changer."]),
           extra={"glow": "true"}),
    bouton("plus", 23, "itemsadder:influitems:add", OR + "Monter le taux",
           lore([], [(GAUCHE, "+0,5 point"), (MAJ_GAUCHE, "+2,5 points")]),
           {"left": [iv("droit +"), SON, "[refresh]"], "shift-left": [iv("droit ++"), SON, "[refresh]"]},
           conditions=[MAIRE]),
    bouton("total", 25, "itemsadder:influitems:bitblock", OR + "Depuis toujours",
           lore(["&f" + m("recette_total") + " " + BB + VIOLET + " perçus", "en &f" + m("transactions") + VIOLET + " transactions."])),
]
for k, slot in enumerate(range(37, 44), start=1):
    j = "%%influlands_jour_%d_" % k
    commerce.append(bouton("jour_%d" % k, slot, "itemsadder:influville:barre_" + j + "barre%", OR + j + "nom%",
                           lore(["&f" + j + "montant% " + BB + VIOLET + " perçus."])))
for n, slot in enumerate(range(47, 52), start=1):
    t = "%%influlands_top_%d_" % n
    commerce.append(bouton("top_%d" % n, slot, t + "icone%", t + "objet%",
                           lore(["N°%d du commerce de la ville" % n, "Par &f" + t + "proprio%", "Reversé : &f" + t + "reverse% " + BB,
                                 "Prix : &f" + t + "prix% " + BB + " &7(x" + t + "quantite%)"],
                                [(GAUCHE, "Localiser")]),
                           {"left": [iv("top %d" % n)]}, conditions=["placeholder: %sexiste%% == oui" % t]))
commerce.append(bouton("echoppes", 53, "itemsadder:influville:echoppe", OR + "Échoppes de la ville",
                       lore([m("echoppes") + " échoppes."], [(GAUCHE, "Voir")]), {"left": [iv("ouvrir echoppes ma depuis:commerce")]},
                       conditions=["placeholder: %influlands_modules_echoppes% == oui"]))
menu("ville_commerce", "menu_ville_commerce", commerce, doc="Commerce de ma ville : droit de place, recettes des 7 derniers jours, meilleures échoppes.")

# ── Banque de ma ville ─────────────────────────────────────────────────────
banque = [
    retour(0, [iv("ouvrir ville")], "Ma ville"),
    bouton("info", 8, "itemsadder:influville:banque", OR + "Banque de " + m("nom"),
           lore(["Règle le montant avec les", "boutons + et -, puis dépose", "ou retire. Retirer demande le", "droit de banque de ton rôle."])),
    bouton("perso", 20, "itemsadder:influitems:bitblock", OR + "Ton solde", lore(["&f" + bq("perso") + " " + BB])),
    bouton("ville", 22, "itemsadder:influville:banque", OR + "Banque de la ville", lore(["&f" + bq("solde") + " " + BB]),
           extra={"glow": "true"}),
    bouton("entretien", 24, "itemsadder:influville:entretien", OR + "Frais d'entretien",
           lore(["&f" + m("entretien") + " " + BB + VIOLET + " par jour.", "Couverts pour &f" + bq("jours") + VIOLET + " jours."])),
]
banque += selecteur("banque montant", "", bouton("montant", 40, "itemsadder:influville:budget", OR + "Montant : &f" + bq("montant") + " " + BB,
                                                   lore([], [(GAUCHE, "Saisir un montant"), (DROIT, "Remettre à 1 000")]),
                                                   {"left": [iv("banque saisir")], "right": [iv("banque montant =1000"), SON, "[refresh]"]},
                                                   extra={"glow": "true"}))
banque += [
    bouton("deposer", 47, "itemsadder:influville:deposer", OR + "Déposer &f" + bq("montant") + " " + BB,
           lore(["De ton solde vers la banque."], [(GAUCHE, "Déposer")]), {"left": [iv("banque deposer"), SON_OK, "[refresh]"]}),
    bouton("saisir", 49, "itemsadder:influville:renommer", OR + "Saisir un montant",
           lore(["Tape directement le montant", "(5000, 10k, 2,5k...)."], [(GAUCHE, "Saisir")]), {"left": [iv("banque saisir")]}),
    bouton("retirer", 51, "itemsadder:influville:retirer", OR + "Retirer &f" + bq("montant") + " " + BB,
           lore(["De la banque vers ton solde."], [(GAUCHE, "Retirer")]), {"left": [iv("banque retirer"), SON_OK, "[refresh]"]}),
]
menu("ville_banque", "menu_ville_banque", banque, doc="Banque de ma ville : montant réglé par boutons + et -, dépôt et retrait (/v bank).")

# ── Réglages de ma ville ───────────────────────────────────────────────────
reglages = [
    retour(0, [iv("ouvrir ville")], "Ma ville"),
    bouton("info", 8, "itemsadder:influitems:settings", OR + "Réglages de " + m("nom"),
           lore(["Tout le monde peut consulter,", "seul le maire modifie.", "", "Ton rôle : &f" + m("role")])),
    bouton("parcelles", 20, "itemsadder:influville:parcelle", OR + "Parcelles",
           lore(["Découpe ta ville en parcelles,", "loue-les ou vends-les."], [(GAUCHE, "Ouvrir")]), {"left": [iv("ouvrir parcelles")]}),
    bouton("roles", 22, "itemsadder:influville:roles", OR + "Rôles et permissions",
           lore(["Ce que chaque rôle a le droit", "de faire dans la ville."], [(GAUCHE, "Ouvrir")]), {"left": [iv("ouvrir roles")]}),
    bouton("parametres", 24, "itemsadder:influville:parametres", OR + "Paramètres",
           lore(["Explosions, feu, apparition", "des monstres, PvP..."], [(GAUCHE, "Ouvrir")]), {"left": [iv("ouvrir parametres")]}),
    bouton("banque", 37, "itemsadder:influville:banque", OR + "Banque", lore(["Solde : &f" + m("solde") + " " + BB], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir banque")]}),
    bouton("habitants", 38, "itemsadder:influville:habitants", OR + "Habitants",
           lore([m("habitants") + " habitants."], [(GAUCHE, "Voir"), (DROIT, "Inviter un joueur")]),
           {"left": [iv("ouvrir habitants ma depuis:reglages")], "right": [iv("saisie inviter")]}),
    bouton("spawn", 39, "itemsadder:influville:spawn", OR + "Spawn ici",
           lore(["Place le spawn de la ville", "là où tu te tiens."], [(GAUCHE, "Définir ici")]),
           {"left": [iv("reglages spawn"), SON, "[refresh]"]}),
    bouton("renommer", 40, "itemsadder:influville:renommer", OR + "Renommer la ville",
           lore(["Nom actuel : &f" + m("nom")], [(GAUCHE, "Choisir un nom")]), {"left": [iv("reglages renommer")]}),
    bouton("accueil", 41, "itemsadder:influville:message", OR + "Message d'accueil",
           lore(["Titre affiché aux joueurs", "qui entrent dans la ville."], [(GAUCHE, "Écrire le message")]),
           {"left": [iv("reglages accueil")]}),
    bouton("agence", 42, "itemsadder:influville:agence", OR + "Annonces de la ville",
           lore(["Parcelles de " + m("nom"), "à louer ou à vendre."], [(GAUCHE, "Ouvrir l'Agence")]), {"left": [iv("ouvrir agence ma depuis:reglages")]}),
    bouton("niveau", 43, "itemsadder:influville:niveau", OR + "Niveau de la ville",
           lore(["Objectifs pour passer", "au niveau suivant."], [(GAUCHE, "Voir les objectifs")]),
           {"left": [iv("natif menu land level")]}),
    bouton("relations", 45, "itemsadder:influville:lier", OR + "Relations",
           lore(["Alliés et ennemis de la ville."], [(GAUCHE, "Ouvrir")]), {"left": [iv("natif menu land relations")]}),
    bouton("complet", 53, "itemsadder:influitems:unknown", OR + "Menu complet",
           lore(["Toutes les options de Lands,", "dans leur menu d'origine."], [(GAUCHE, "Ouvrir")]), {"left": [iv("natif menu")]}),
]
menu("ville_reglages", "menu_ville_reglages", reglages, doc="Réglages de ma ville (remplace le menu natif de Lands).")


# ── Listes (annuaire, warps, échoppes, habitants, agence, parcelles, rôles, permissions, paramètres) ──
def l(n, c):
    return "%%influlands_liste_%d_%s%%" % (n, c)


def choix_lignes(prefixe, nb):
    return ["@option %%influlands_liste_%s_%d%%" % (prefixe, k) for k in range(1, nb + 1)]


def barre_liste(info_icone, info_nom, info_desc, action=None, gauche=None, filtre=True, tri=True, extra=()):
    """Barre du bas : raccourci (45), filtre (47), pages (48, 50), recherche (49), tri (51), action (53)."""
    items = [bouton("info", 8, info_icone, OR + info_nom, lore(info_desc + ["", "Résultats : &f%influlands_liste_total%"]))]
    items.append(gauche or bouton("ma_ville", 45, "itemsadder:influville:ville", OR + "Ma ville", lore([], [(GAUCHE, "Ouvrir")]),
                                  {"left": [iv("ouvrir ville")]}))
    if filtre:
        items.append(bouton("filtre", 47, "itemsadder:influitems:filter", OR + "Filtre",
                            choix_lignes("filtre", 6) + ["@left Suivant", "@right Précédent"],
                            {"left": [iv("filtre +"), SON, "[refresh]"], "right": [iv("filtre -"), SON, "[refresh]"]}))
    items += [
        bouton("precedent", 48, "itemsadder:influitems:btn_prev", OR + "Page précédente", lore([], [(GAUCHE, "Précédente")]),
               {"left": [iv("page -"), SON_PAGE, "[refresh]"]}, conditions=["placeholder: %influlands_liste_precedent% == oui"]),
        bouton("recherche", 49, "itemsadder:influitems:search", OR + "Rechercher",
               lore(["Recherche : &f%influlands_liste_recherche%"], [(GAUCHE, "Rechercher"), (DROIT, "Effacer")]),
               {"left": [iv("recherche")], "right": [iv("effacer"), SON, "[refresh]"]}),
        bouton("suivant", 50, "itemsadder:influitems:btn_next", OR + "Page suivante", lore([], [(GAUCHE, "Suivante")]),
               {"left": [iv("page +"), SON_PAGE, "[refresh]"]}, conditions=["placeholder: %influlands_liste_suivant% == oui"]),
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
    return bouton("vide", 31, icone, OR + nom, lore(desc), conditions=["placeholder: %influlands_liste_total% == 0"])


# Annuaire des villes
annuaire = [retour_liste()]
for n, slot in enumerate(GRILLE, start=1):
    annuaire.append(bouton("ville_%d" % n, slot, l(n, "icone"), l(n, "nom_couleur"),
                           lore(["Niveau : &f" + l(n, "niveau"), "Maire : &f" + l(n, "maire"),
                                 "Habitants : &f" + l(n, "habitants") + " &7(" + l(n, "en_ligne") + " en ligne)",
                                 "Chunks : &f" + l(n, "chunks"),
                                 "Échoppes : &f" + l(n, "echoppes") + VIOLET + "  Warps : &f" + l(n, "adresses"),
                                 "Droit de place : &f" + l(n, "taux") + " %", "Nation : &f" + l(n, "nation")],
                                [(GAUCHE, "Voir la fiche"), (DROIT, "Se téléporter au spawn")]),
                           {"left": [iv("clic %d gauche" % n)], "right": [iv("clic %d droit" % n)]},
                           conditions=["placeholder: %s == oui" % l(n, "existe")]))
annuaire.append(vide("itemsadder:influitems:unknown", "Aucune ville", ["Aucune ville ne correspond", "à ce filtre."]))
annuaire += barre_liste("itemsadder:influville:annuaire", "Annuaire des villes",
                        ["Toutes les villes du serveur,", "de la plus grande à la plus", "petite."],
                        bouton("fonder", 53, "itemsadder:influitems:add", OR + "Fonder une ville",
                               lore(["Crée ta ville à l'endroit", "où tu te tiens."], [(GAUCHE, "Choisir un nom")]),
                               {"left": [iv("saisie creer_ville")]}, conditions=["placeholder: %influlands_ma_existe% == non"]))
menu("villes", "menu_villes", annuaire, commandes=["annuaire"],
     ouverture=[iv("vue villes"), "[refresh]"], doc="Annuaire des villes (/annuaire).")

# Warps (InfluPW)
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
                     ["Les warps des joueurs (/pw),", "rattachés à la ville où", "ils se trouvent."],
                     bouton("creer", 53, "itemsadder:influitems:add", OR + "Créer un warp",
                            lore(["Crée un warp là où tu te", "tiens. Dans une ville, il", "apparaît sur sa fiche."], [(GAUCHE, "Choisir un nom")]),
                            {"left": [iv("saisie creer_warp")]}))
menu("warps", "menu_warps", warps, commandes=["warps"],
     ouverture=[iv("vue warps"), "[refresh]"], doc="Warps (InfluPW) avec leur ville (/warps).")

# Échoppes (FluxChestShop)
echoppes = [retour_liste()]
for n, slot in enumerate(GRILLE, start=1):
    echoppes.append(bouton("echoppe_%d" % n, slot, l(n, "icone"), l(n, "objet"),
                           lore(["&f" + l(n, "type") + VIOLET + " par &f" + l(n, "proprio"),
                                 "Prix : &f" + l(n, "prix") + " " + BB + " &7(x" + l(n, "quantite") + ")",
                                 "Stock : &f" + l(n, "stock"),
                                 "Ville : &f" + l(n, "ville") + " &7(droit " + l(n, "taux") + " %)",
                                 "Reversé à la ville : &f" + l(n, "reverse") + " " + BB, l(n, "statut")],
                                [(GAUCHE, "Localiser"), (DROIT, "Fiche de la ville")]),
                           {"left": [iv("clic %d gauche" % n)], "right": [iv("clic %d droit" % n)]},
                           conditions=["placeholder: %s == oui" % l(n, "existe")],
                           extra={"amount": q(l(n, "quantite"))}))
echoppes.append(vide("itemsadder:influitems:unknown", "Aucune échoppe", ["Aucune échoppe ne correspond", "à ce filtre."]))
echoppes += barre_liste("itemsadder:influville:echoppe", "Échoppes",
                        ["Coffres de vente et de rachat", "des joueurs. Dans une ville,", "une part de chaque vente va", "à sa banque (droit de place)."],
                        bouton("ouvrir_echoppe", 53, "itemsadder:influitems:add", OR + "Ouvrir une échoppe",
                               lore(["Pose un coffre, puis frappe-le", "avec l'objet à vendre et", "indique le prix dans le tchat.", "",
                                     "Droit de place ici : &f%influlands_taux_ici% %"])))
menu("echoppes", "menu_echoppes", echoppes, commandes=["echoppes"],
     ouverture=[iv("vue echoppes"), "[refresh]"], doc="Échoppes (FluxChestShop) avec leur ville et le droit de place (/echoppes).")

# Habitants
habitants = [retour_liste()]
for n, slot in enumerate(GRILLE, start=1):
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
    ["Parcelles à louer ou à vendre", "dans toutes les villes", "(/v rental list)."],
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
           lore(["1. Sélectionne les deux coins", "   de la parcelle.", "2. La fenêtre du nom s'ouvre", "   toute seule.", "",
                 "Sélection : &f%influlands_selection%"], [(GAUCHE, "Commencer")]),
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
    bouton("publier", 40, "itemsadder:influitems:add", OR + "Mettre en annonce",
           lore(["Location ou vente, prix et", "durée réglés par boutons.", "L'annonce s'affiche en", "hologramme dans la parcelle."], [(GAUCHE, "Préparer l'annonce")]),
           {"left": [iv("parcelle annonce")]}, conditions=[MAIRE, "placeholder: " + pc("annonce") + " == non",
                                                            "placeholder: " + pc("occupee") + " == non"], extra={"glow": "true"}),
    bouton("retirer", 40, "itemsadder:influitems:deny", OR + "Retirer l'annonce",
           lore([pc("annonce_ligne")], [(GAUCHE, "Retirer")]), {"left": [iv("parcelle retirer"), SON, "[refresh]"]},
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
    bouton("reglages", 49, "itemsadder:influitems:settings", OR + "Réglages de la ville", lore([], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir reglages")]}),
    bouton("supprimer", 53, "itemsadder:influitems:deny", OR + "Supprimer la parcelle",
           lore(["Ouvre la liste des parcelles", "de Lands : survole " + pc("nom") + " et", "appuie sur ta touche Jeter (Q).",
                 "Impossible tant qu'une annonce", "ou un occupant s'y trouve."], [(GAUCHE, "Double clic pour continuer")]),
           {"left": [iv("parcelle supprimer")]}, conditions=[MAIRE],
           extra={"confirm": q("&#FF5C5CClique encore pour supprimer la parcelle " + pc("nom"))}),
]
menu("ville_parcelle", "menu_ville_parcelle", parcelle, doc="Une parcelle de ma ville : état, annonce, occupant, gestion.")

# Préparer une annonce
LOC = "placeholder: " + an("location") + " == oui"
annonce = [
    retour(0, ["[menu] ville_parcelle"], "Parcelle"),
    bouton("info", 8, "itemsadder:influville:agence", OR + "Publier une annonce",
           lore(["1. Choisis vente ou location.", "2. Règle le prix (et les durées).", "3. Place-toi dans la parcelle,",
                 "   à l'endroit de l'hologramme.", "4. Publie."])),
    bouton("vente_on", 20, "itemsadder:influville:vente", OR + "Vente", lore(["Le joueur devient propriétaire", "de la parcelle."]),
           priorite=-1, conditions=["placeholder: " + an("location") + " == non"], extra={"glow": "true"}),
    bouton("vente", 20, "itemsadder:influville:vente", OR + "Vente", lore(["Le joueur devient propriétaire", "de la parcelle."], [(GAUCHE, "Choisir")]),
           {"left": [iv("annonce type"), SON, "[refresh]"]}),
    bouton("parcelle", 22, "itemsadder:influville:parcelle", OR + an("parcelle"),
           lore(["Type : &f" + an("type"), "Prix : &f" + an("prix") + " " + BB])),
    bouton("location_on", 24, "itemsadder:influville:location", OR + "Location", lore(["Le joueur loue la parcelle par", "période, jusqu'à une durée max."]),
           priorite=-1, conditions=[LOC], extra={"glow": "true"}),
    bouton("location", 24, "itemsadder:influville:location", OR + "Location",
           lore(["Le joueur loue la parcelle par", "période, jusqu'à une durée max."], [(GAUCHE, "Choisir")]),
           {"left": [iv("annonce type"), SON, "[refresh]"]}),
]
annonce += selecteur("annonce prix", "", bouton("prix", 40, "itemsadder:influville:budget", OR + "Prix : &f" + an("prix") + " " + BB,
                                                  lore(["Par période pour une location."], [(GAUCHE, "Saisir un prix")]),
                                                  {"left": [iv("annonce saisir")]}, extra={"glow": "true"}))
annonce += [
    bouton("periode_moins", 45, "itemsadder:influitems:remove", OR + "-1 jour", lore([], [(GAUCHE, "Raccourcir la période")]),
           {"left": [iv("annonce periode -1"), SON, "[refresh]"]}, conditions=[LOC]),
    bouton("periode", 46, "itemsadder:influville:periode", OR + "Période : &f" + an("periode"),
           lore(["Durée payée à chaque", "renouvellement."]), conditions=[LOC]),
    bouton("periode_plus", 47, "itemsadder:influitems:add", OR + "+1 jour", lore([], [(GAUCHE, "Allonger la période")]),
           {"left": [iv("annonce periode +1"), SON, "[refresh]"]}, conditions=[LOC]),
    bouton("publier", 49, "itemsadder:influitems:accept", OR + "Publier ici",
           lore(["Pose l'annonce là où tu te", "tiens (dans la parcelle,", "pieds sur un sol plein)."], [(GAUCHE, "Publier")]),
           {"left": [iv("annonce publier")]}, extra={"glow": "true"}),
    bouton("duree_moins", 51, "itemsadder:influitems:remove", OR + "-7 jours", lore([], [(GAUCHE, "Réduire la durée maximale")]),
           {"left": [iv("annonce duree -7"), SON, "[refresh]"]}, conditions=[LOC]),
    bouton("duree", 52, "itemsadder:influville:periode", OR + "Durée max : &f" + an("duree"),
           lore(["Durée totale qu'un locataire", "peut atteindre en renouvelant."]), conditions=[LOC]),
    bouton("duree_plus", 53, "itemsadder:influitems:add", OR + "+7 jours", lore([], [(GAUCHE, "Augmenter la durée maximale")]),
           {"left": [iv("annonce duree +7"), SON, "[refresh]"]}, conditions=[LOC]),
]
menu("ville_annonce", "menu_ville_annonce", annonce, doc="Préparer et publier l'annonce (vente ou location, hologramme) d'une parcelle.")

# Rôles de ma ville
roles = [retour_liste()]
for n, slot in enumerate(GRILLE, start=1):
    roles.append(bouton("role_%d" % n, slot, l(n, "icone"), l(n, "nom"),
                        lore(["Type : &f" + l(n, "type"), "Priorité : &f" + l(n, "priorite"), "&f" + l(n, "droits") + VIOLET + " permissions accordées"],
                             [(GAUCHE, "Modifier les permissions")]),
                        {"left": [iv("clic %d gauche" % n)]},
                        conditions=["placeholder: %s == oui" % l(n, "existe"), "placeholder: %s == oui" % l(n, "modifiable")], priorite=-1))
    roles.append(bouton("role_%d_maire" % n, slot, l(n, "icone"), l(n, "nom"),
                        lore(["Type : &f" + l(n, "type"), "Le maire a toujours", "toutes les permissions."]),
                        conditions=["placeholder: %s == oui" % l(n, "existe")]))
roles += barre_liste("itemsadder:influville:roles", "Rôles de " + m("nom"),
                     ["Du plus important au moins", "important. Chaque rôle a ses", "propres permissions."], filtre=False, tri=False,
                     gauche=bouton("habitants", 45, "itemsadder:influville:habitants", OR + "Habitants",
                                   lore(["Change le rôle d'un habitant", "depuis le menu complet."], [(GAUCHE, "Ouvrir")]),
                                   {"left": [iv("natif member menu")]}))
menu("ville_roles", "menu_roles", roles, ouverture=[iv("vue roles"), "[refresh]"], doc="Rôles de ma ville.")

# Permissions d'un rôle
droits = [retour_liste()]
for n, slot in enumerate(GRILLE, start=1):
    d = ["&7" + l(n, "desc_%d" % k) for k in range(1, 5)] + ["", l(n, "etat")]
    droits.append(bouton("droit_%d_on" % n, slot, l(n, "icone"), "&f" + l(n, "nom"), lore(d, [(GAUCHE, "Interdire")]),
                         {"left": [iv("clic %d gauche" % n), SON, "[refresh]"]}, priorite=-1,
                         conditions=["placeholder: %s == oui" % l(n, "existe"), "placeholder: %s == oui" % l(n, "actif")],
                         extra={"glow": "true"}))
    droits.append(bouton("droit_%d" % n, slot, l(n, "icone"), "&7" + l(n, "nom"), lore(d, [(GAUCHE, "Autoriser")]),
                         {"left": [iv("clic %d gauche" % n), SON, "[refresh]"]},
                         conditions=["placeholder: %s == oui" % l(n, "existe")]))
droits += barre_liste("%influlands_role_icone%", "Permissions : %influlands_role_nom%",
                      ["Ce que ce rôle peut faire.", "Brillant = autorisé.", "Filtre : actions dans la ville", "ou gestion de la ville."],
                      tri=False, gauche=bouton("reglages", 45, "itemsadder:influitems:settings", OR + "Réglages", lore([], [(GAUCHE, "Ouvrir")]),
                                               {"left": [iv("ouvrir reglages")]}))
menu("ville_droits", "menu_droits", droits, ouverture=[iv("vue droits"), "[refresh]"], doc="Permissions d'un rôle de ma ville.")

# Paramètres de ma ville
parametres = [retour_liste()]
for n, slot in enumerate(GRILLE, start=1):
    d = ["&7" + l(n, "desc_%d" % k) for k in range(1, 5)] + ["", l(n, "etat")]
    parametres.append(bouton("param_%d_on" % n, slot, l(n, "icone"), "&f" + l(n, "nom"), lore(d, [(GAUCHE, "Désactiver")]),
                             {"left": [iv("clic %d gauche" % n), SON, "[refresh]"]}, priorite=-1,
                             conditions=["placeholder: %s == oui" % l(n, "existe"), "placeholder: %s == oui" % l(n, "actif")],
                             extra={"glow": "true"}))
    parametres.append(bouton("param_%d" % n, slot, l(n, "icone"), "&7" + l(n, "nom"), lore(d, [(GAUCHE, "Activer")]),
                             {"left": [iv("clic %d gauche" % n), SON, "[refresh]"]},
                             conditions=["placeholder: %s == oui" % l(n, "existe")]))
parametres += barre_liste("itemsadder:influville:parametres", "Paramètres de " + m("nom"),
                          ["Règles naturelles de la ville", "(explosions, feu, monstres...).", "Brillant = activé."], tri=False,
                          gauche=bouton("reglages", 45, "itemsadder:influitems:settings", OR + "Réglages", lore([], [(GAUCHE, "Ouvrir")]),
                                        {"left": [iv("ouvrir reglages")]}))
menu("ville_parametres", "menu_parametres", parametres, ouverture=[iv("vue parametres"), "[refresh]"], doc="Paramètres naturels de ma ville.")

# ── Accueil partagé « Villes et Pays » ─────────────────────────────────────
INVISIBLE = "itemsadder:influitems:invisible"
territoires = [
    bouton("panneau_ville_membre", [0, 1, 2, 3, 9, 10, 11, 12, 18, 19, 20, 21], INVISIBLE, m("nom_couleur"),
           lore(["Niveau : &f" + m("niveau"), "Habitants : &f" + m("habitants") + VIOLET + "  Chunks : &f" + m("chunks"),
                 "Banque : &f" + m("solde") + " " + BB], [(GAUCHE, "Ouvrir Ma ville")]),
           {"left": [iv("ouvrir ville")]}, priorite=-1, conditions=["placeholder: " + m("existe") + " == oui"], extra={"hide-tooltip": "false"}),
    bouton("panneau_ville", [0, 1, 2, 3, 9, 10, 11, 12, 18, 19, 20, 21], INVISIBLE, OR + "Villes",
           lore(["Tu n'habites aucune ville.", "Fonde la tienne ou rejoins", "une ville existante."], [(GAUCHE, "Commencer")]),
           {"left": [iv("ouvrir ville")]}),
    bouton("panneau_pays_membre", [5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26], INVISIBLE, mp("nom_couleur"),
           lore(["Niveau : &f" + mp("niveau"), "Capitale : &f" + mp("capitale"), "Villes : &f" + mp("villes") + VIOLET + "  Habitants : &f" + mp("habitants")],
                [(GAUCHE, "Ouvrir Mon pays")]),
           {"left": [iv("ouvrir pays")]}, priorite=-1, conditions=["placeholder: " + mp("existe") + " == oui"]),
    bouton("panneau_pays", [5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26], INVISIBLE, OR + "Pays",
           lore(["Ta ville n'appartient à aucun", "pays. Un pays réunit plusieurs", "villes autour d'une capitale."], [(GAUCHE, "Découvrir")]),
           {"left": [iv("ouvrir pays")]}),
    # Explorer : seulement ce qui n'est ni dans Ma ville ni dans Mon pays (allégé le 2026-10-19).
    bouton("annuaire", 38, "itemsadder:influville:annuaire", OR + "Annuaire des villes", lore(["Toutes les villes du serveur."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir annuaire depuis:territoires")]}),
    bouton("warps", 39, "itemsadder:influville:warp", OR + "Warps", lore(["Les warps des joueurs,", "classés par ville."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir warps toutes depuis:territoires")]}, conditions=["placeholder: %influlands_modules_warps% == oui"]),
    bouton("agence", 40, "itemsadder:influville:agence", OR + "Agence", lore(["Parcelles à louer ou à vendre", "dans toutes les villes."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir agence depuis:territoires")]}),
    bouton("echoppes", 41, "itemsadder:influville:echoppe", OR + "Échoppes", lore(["Où acheter et vendre", "dans les villes."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir echoppes toutes depuis:territoires")]}, conditions=["placeholder: %influlands_modules_echoppes% == oui"]),
    bouton("annuaire_pays", 42, "itemsadder:influville:pays", OR + "Annuaire des pays", lore(["Tous les pays du serveur."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir pays_annuaire depuis:territoires")]}),
    retour(49, ["[menu] main"], "Menu principal"),
]
menu("territoires", "menu_territoires", territoires, doc="Villes et Pays : accueil partagé ouvert par le bouton Ville et Pays de /menu2.")

# ── Mon pays ───────────────────────────────────────────────────────────────
CHEF = "placeholder: " + mp("est_chef") + " == oui"
mon_pays = [
    retour(0, ["[menu] territoires"], "Villes et Pays", droit=RETOUR_HUB),
    bouton("fiche", 8, mp("icone"), mp("nom_couleur"),
           lore(["Niveau : &f" + mp("niveau"), "Chef : &f" + mp("chef"), "Fondé le &f" + mp("creation")], [(GAUCHE, "Voir la fiche publique")]),
           {"left": [iv("ouvrir pays_fiche mon")]}),
    bouton("stat_niveau", 19, mp("icone"), OR + "Niveau &f" + mp("niveau_num") + OR + "/4",
           lore([mp("niveau"), "Le niveau du pays augmente", "avec ses villes membres."], [(GAUCHE, "Objectifs du niveau suivant")]),
           {"left": [iv("natifpays menu nation level")]}),
    bouton("stat_villes", 20, "itemsadder:influville:ville", OR + "Villes &f" + mp("villes"), lore(["Villes membres du pays."], [(GAUCHE, "Voir")]),
           {"left": [iv("ouvrir pays_villes mon")]}),
    bouton("stat_habitants", 21, "itemsadder:influville:habitants", OR + "Habitants &f" + mp("habitants"), lore(["En ligne : &f" + mp("en_ligne")])),
    bouton("stat_chunks", 22, "itemsadder:influville:chunks", OR + "Chunks &f" + mp("chunks"), lore(["Territoire de toutes", "les villes du pays."])),
    bouton("stat_tresor", 23, "itemsadder:influitems:bitblock", OR + "Trésor",
           lore(["&f" + mp("tresor") + " " + BB, "Banque de la capitale, qui", "paie les frais d'entretien", "du pays."])),
    bouton("stat_capitale", 24, "itemsadder:influville:capitale", OR + "Capitale : " + mp("capitale_couleur"),
           lore([], [(GAUCHE, "Fiche de la capitale")]), {"left": [iv("ouvrir fiche " + mp("capitale"))]}),
    bouton("stat_chef", 25, "itemsadder:influville:roles", OR + "Chef : &f" + mp("chef"), lore(["Maire de la capitale."])),
    bouton("villes", 37, "itemsadder:influville:ville", OR + "Villes du pays", lore([mp("villes") + " villes membres."], [(GAUCHE, "Voir")]),
           {"left": [iv("ouvrir pays_villes mon")]}),
    bouton("inviter", 38, "itemsadder:influitems:add", OR + "Inviter une ville",
           lore(["Choisis une ville sans pays", "dans la liste."], [(GAUCHE, "Choisir")]), {"left": [iv("pays inviter")]}, conditions=[CHEF]),
    bouton("spawn", 39, "itemsadder:influville:spawn", OR + "Spawn du pays", lore(["Spawn de la capitale."], [(GAUCHE, "Se téléporter")]),
           {"left": [iv("pays spawn mon")]}),
    bouton("relations", 40, "itemsadder:influville:lier", OR + "Relations", lore(["Alliés et ennemis du pays."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("natifpays menu nation relations")]}),
    bouton("taxes", 41, "itemsadder:influville:taxes", OR + "Taxes", lore(["Taxes versées par les villes", "au pays."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("natifpays menu nation taxes")]}),
    bouton("effets", 42, "itemsadder:influville:effets", OR + "Effets", lore(["Bonus accordés aux habitants", "du pays."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("natifpays menu nation effects")]}),
    bouton("annuaire", 43, "itemsadder:influville:annuaire", OR + "Annuaire des pays", lore(["Tous les pays du serveur."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("ouvrir pays_annuaire")]}),
    bouton("guerre", 45, "itemsadder:influville:guerre", OR + "Guerre", lore(["Déclarations et guerres", "en cours."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("natifpays war menu")]}),
    bouton("capitale", 46, "itemsadder:influville:capitale", OR + "Changer de capitale", lore(["Choisis la nouvelle capitale", "parmi les villes du pays."], [(GAUCHE, "Choisir")]),
           {"left": [iv("pays capitale")]}, conditions=[CHEF]),
    bouton("renommer", 47, "itemsadder:influville:renommer", OR + "Renommer le pays", lore(["Nom actuel : &f" + mp("nom")], [(GAUCHE, "Choisir un nom")]),
           {"left": [iv("pays renommer")]}, conditions=[CHEF]),
    bouton("chat", 49, "itemsadder:influville:message", OR + "Tchat du pays", lore(["Parle seulement aux habitants", "du pays (bascule)."], [(GAUCHE, "Activer / désactiver")]),
           {"left": [iv("pays chat")]}),
    bouton("quitter", 51, "itemsadder:influville:quitter", OR + "Quitter le pays",
           lore(["Ta ville quitte " + mp("nom") + "."], [(GAUCHE, "Double clic pour confirmer")]), {"left": [iv("pays quitter")]},
           conditions=["placeholder: " + mp("maire_ici") + " == oui", "placeholder: " + mp("capitale_moi") + " == non"],
           extra={"confirm": q("&#FF5C5CClique encore pour faire quitter " + mp("nom") + " à ta ville")}),
    bouton("niveau", 52, "itemsadder:influville:niveau", OR + "Niveau du pays", lore(["Objectifs pour passer", "au niveau suivant."], [(GAUCHE, "Voir")]),
           {"left": [iv("natifpays menu nation level")]}),
    bouton("complet", 53, "itemsadder:influitems:unknown", OR + "Menu complet", lore(["Toutes les options des pays,", "dans leur menu d'origine."], [(GAUCHE, "Ouvrir")]),
           {"left": [iv("natifpays menu")]}),
]
menu("pays", "menu_pays", mon_pays, doc="Mon pays : tableau de bord du pays de ma ville (/nations, /n).")

# ── Accueil des pays (ville sans pays) ─────────────────────────────────────
pays_accueil = [
    retour(0, ["[menu] territoires"], "Villes et Pays", droit=RETOUR_HUB),
    bouton("annuaire", 29, "itemsadder:influville:annuaire", OR + "Annuaire des pays",
           lore(["Découvre les pays existants", "et leurs villes."], [(GAUCHE, "Ouvrir")]), {"left": [iv("ouvrir pays_annuaire depuis:pays_accueil")]}),
    bouton("fonder", 31, "itemsadder:influitems:add", OR + "Fonder un pays",
           lore(["Ta ville en devient la capitale.", "Réservé au maire.", "", "Serveur en bêta : les règles", "des pays peuvent évoluer."], [(GAUCHE, "Choisir un nom")]),
           {"left": [iv("pays creer")]}),
    bouton("rejoindre", 33, "itemsadder:influville:inviter_ville", OR + "Rejoindre un pays",
           lore(["Accepte l'invitation qu'un", "pays a envoyée à ta ville."], [(GAUCHE, "Choisir le pays")]), {"left": [iv("pays accepter")]}),
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
