#!/usr/bin/env python3
"""Menus Flux du marché (FluxAuctionHouse) : écrit menus/marche.yml, marche_offre.yml, marche_vendre.yml,
marche_ventes.yml, marche_encheres.yml, marche_historique.yml.

    python3 gen_menus_marche.py <plugins/Flux/menus>

Ne pas modifier les menus générés à la main : changer ce script puis le relancer et /fluxmenu reload.
Données : placeholders %influmarche_...% et commande /influmarche du plugin FluxAuctionHouse
(docs/technique/influmarche.md). Objets réels (offres, inventaire) : source Flux « source:marche:... ».
Fonds et icônes : pack ItemsAdder « influmarche » (gen_marche.py). Style des boutons : celui de /menu2.
"""
import os
import sys

SORTIE = sys.argv[1]
OR, VIOLET = "&#FFD94D", "&#B376E5"
GAUCHE, DROIT = "&r&7%img_left_click% &f", "&r&7%img_right_click% &f"
BB = "&f%img_bitblock%"
ENTETE = "# Généré par plugins/fluxplugin/FluxAuctionHouse/assets/marche/gen_menus_marche.py : ne pas modifier à la main.\n"
SON = "[sound] minecraft:ui.button.click 0.6 1.4"
LIGNES = 12  # lignes de description préparées par FluxAuctionHouse (%influmarche_..._l1% à _l12%)
GRILLE_MARCHE = [r * 9 + c for r in range(2, 6) for c in range(1, 8)]
GRILLE_LISTE = [r * 9 + c for r in range(2, 5) for c in range(1, 8)]


def q(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def lore(desc, actions=()):
    lignes = [OR + "│ " + VIOLET + d if d else OR + "│" for d in desc]
    for i, (icone, lib) in enumerate(actions):
        lignes.append(OR + ("└ " if i == len(actions) - 1 else "├ ") + icone + lib)
    if not actions and lignes:
        lignes[-1] = lignes[-1].replace("│", "└", 1)
    return lignes


def pm(c):
    return "%influmarche_" + c + "%"


def lignes(prefixe):
    """Lignes de description déjà mises en forme par FluxAuctionHouse (masquées par Flux quand elles sont vides)."""
    return [pm("%s_l%d" % (prefixe, k)) for k in range(1, LIGNES + 1)]


def oui(c):
    return "placeholder: %s == oui" % pm(c)


def non(c):
    return "placeholder: %s != oui" % pm(c)


def bouton(cle, slot, materiau, nom, lignes_, actions=None, conditions=None, priorite=None, extra=None):
    out = ["  %s:" % cle, "    material: %s" % q(materiau)]
    if nom is not None:
        out.append("    name: %s" % q(nom))
    out += ["    slots: [%s]" % (", ".join(str(s) for s in slot) if isinstance(slot, (list, tuple)) else slot),
           ]
    if not materiau.startswith("source:"):  # objets réels : leurs attributs (dégâts, armure) restent visibles
        out.append("    flags: [HIDE_ATTRIBUTES]")
    if priorite is not None:
        # Ici « priorite » plus grande = passe devant. Dans Flux c'est l'inverse (la priorité la plus
        # basse l'emporte sur une même case) : on écrit donc l'opposé.
        out.append("    priority: %d" % -priorite)
    if conditions:
        out.append("    view-requirements:")
        out += ["      - %s" % q(c) for c in conditions]
    for k, v in (extra or {}).items():
        out.append("    %s: %s" % (k, v))
    if lignes_:
        out.append("    lore:")
        out += ["      - %s" % q(l) for l in lignes_]
    if actions:
        out.append("    actions:")
        for clic, acts in actions.items():
            out.append("      %s: [%s]" % (clic, ", ".join(q(a) for a in acts)))
    return "\n".join(out) + "\n"


def menu(nom, fond, items, ouverture=(), doc=""):
    out = [ENTETE, "# " + doc + "\n\n" if doc else "\n",
           "title: %s\n" % q(":offset_-8::%s:" % fond), "rows: 6\n",
           "player-inventory: hidden\n", "vanilla-background: false\n", "update-interval: 20\n\n", "open-actions:\n"]
    for a in list(ouverture) + ["[sound] minecraft:item.book.page_turn 1 1"]:
        out.append("  - %s\n" % q(a))
    out.append("\nitems:\n\n")
    out += [i + "\n" for i in items]
    with open(os.path.join(SORTIE, nom + ".yml"), "w") as f:
        f.write("".join(out))


def im(cmd):
    return "[player] influmarche " + cmd


def act(*cmds, refresh=True):
    l = [im(c) for c in cmds] + [SON]
    return l + (["[refresh]"] if refresh else [])


def retour_marche():
    return bouton("retour", 0, "itemsadder:influitems:btn_back", OR + "Retour", lore([], [(GAUCHE, "Marché"), (DROIT, "Menu principal")]),
                  {"left": [im("ouvrir marche")], "right": ["[menu] main"]})


def entrees(grille, prefixe="liste"):
    """Une entrée de liste par case : objet réel, description de FluxAuctionHouse ; variante brillante prioritaire."""
    items = []
    for n, slot in enumerate(grille, start=1):
        base = "%s_%d" % (prefixe, n)
        actions = {"left": [im("clic %d gauche" % n), SON, "[refresh]"], "right": [im("clic %d droit" % n), SON, "[refresh]"]}
        items.append(bouton(base + "_brille", slot, "source:marche:liste:%d" % n, None, lignes(base), actions,
                            conditions=[oui(base + "_existe"), oui(base + "_brille")], priorite=1, extra={"glow": "true"}))
        items.append(bouton(base, slot, "source:marche:liste:%d" % n, None, lignes(base), actions,
                            conditions=[oui(base + "_existe")]))
    return items


def pages(prec, suiv):
    return [
        bouton("precedent", prec, "itemsadder:influitems:btn_prev", OR + "Page précédente",
               lore(["Page &f" + pm("page") + VIOLET + " sur &f" + pm("pages")], [(GAUCHE, "Précédente")]),
               {"left": act("page -")}, conditions=[oui("precedent")]),
        bouton("suivant", suiv, "itemsadder:influitems:btn_next", OR + "Page suivante",
               lore(["Page &f" + pm("page") + VIOLET + " sur &f" + pm("pages")], [(GAUCHE, "Suivante")]),
               {"left": act("page +")}, conditions=[oui("suivant")]),
    ]


def selecteur(cases, prefixe, pas, affichage, extremes=("min", "max"), libelles=None, unite=""):
    """Sept cases : minimum, trois -, affichage, trois + (ou maximum). pas = (petit, moyen, grand)."""
    items = []
    p1, p2, p3 = pas
    gauche = [(extremes[0], "Minimum", "itemsadder:influitems:remove")] if extremes[0] else [("-%d" % p3, "-" + fmt(p3), "itemsadder:influitems:remove")]
    gauche += [("-%d" % p2, "-" + fmt(p2), "itemsadder:influitems:remove"), ("-%d" % p1, "-" + fmt(p1), "itemsadder:influitems:remove")]
    droite = [("+%d" % p1, "+" + fmt(p1), "itemsadder:influitems:add"), ("+%d" % p2, "+" + fmt(p2), "itemsadder:influitems:add")]
    droite += [(extremes[1], "Maximum", "itemsadder:influitems:add")] if extremes[1] else [("+%d" % p3, "+" + fmt(p3), "itemsadder:influitems:add")]
    for choix, slot in zip(gauche + [None] + droite, cases):
        if choix is not None:  # la case du milieu est l'affichage, déclaré à part
            items.append((slot,) + choix)
    return items


def pile(arg):
    """Bouton de pas de quantité (+16, -1...) : la valeur du pas affichée en nombre d'objets sur l'icône."""
    n = arg.lstrip("+-")
    return {"amount": q(n)} if n.isdigit() and 1 < int(n) <= 99 else None


def fmt(v):
    return "{:,}".format(v).replace(",", " ")


# ── Marché ─────────────────────────────────────────────────────────────────
SOURCES = [
    (18, "itemsadder:influmarche:tout", "Tout", ["Ventes directes, enchères", "et échoppes des villes."]),
    (27, "itemsadder:influmarche:marche", "Hôtel des ventes", ["Ventes directes et enchères :", "achat depuis ce menu, livré", "dans ton inventaire."]),
    (36, "itemsadder:influville:echoppe", "Échoppes", ["Coffres de vente des villes :", "rends-toi sur place pour", "acheter."]),
    (45, "itemsadder:influmarche:rachat", "Rachats", ["Échoppes qui achètent :", "rends-toi sur place pour", "leur vendre. Meilleur prix", "en premier."]),
]
CATEGORIES = [("CHEST", "Tout"), ("BRICKS", "Construction"), ("IRON_INGOT", "Ressources"), ("DIAMOND_PICKAXE", "Outils"),
              ("DIAMOND_SWORD", "Combat et armures"), ("BREAD", "Nourriture et cultures"), ("ENCHANTED_BOOK", "Alchimie et magie"),
              ("NETHER_STAR", "Objets uniques"), ("BUNDLE", "Divers")]

marche = [
    bouton("retour", 0, "itemsadder:influitems:btn_back", OR + "Retour", lore([], [(GAUCHE, "Menu principal")]), {"left": ["[menu] main"]}),
    bouton("info", 8, "itemsadder:influmarche:marche", OR + "Marché",
           lore(["L'hôtel des ventes et les", "échoppes des villes réunis :", "cherche un objet, tu vois",
                 "toutes ses offres.", "",
                 "Hôtel des ventes : taxe de &f" + pm("taxe") + " %", VIOLET + "prélevée au vendeur.",
                 "Échoppes : droit de place", "de la ville, sans taxe.", "",
                 "Offres : &f" + pm("nb_offres"), "Ton solde : &f" + pm("solde") + " " + BB,
                 "", "&7Serveur en bêta : retours", "&7bienvenus sur Discord."])),
    bouton("vendre", 9, "itemsadder:influitems:add", OR + "Vendre",
           lore(["Mets un objet de ton", "inventaire en vente directe", "ou aux enchères.",
                 "Offres en cours : &f" + pm("emplacements")], [(GAUCHE, "Choisir un objet")]),
           {"left": [im("ouvrir vendre")]}),
    bouton("mes_ventes_recup", 17, "itemsadder:influitems:chest_item", OR + "Mes ventes",
           lore(["&f" + pm("a_recuperer") + VIOLET + " à récupérer (BitBlocks", "ou objets).", "Offres en cours : &f" + pm("emplacements")],
                [(GAUCHE, "Ouvrir"), (DROIT, "Tout récupérer")]),
           {"left": [im("ouvrir ventes")], "right": act("recuperer")}, conditions=[oui("a_recuperer_oui")], priorite=1,
           extra={"glow": "true"}),
    bouton("mes_ventes", 17, "itemsadder:influmarche:mes_ventes", OR + "Mes ventes",
           lore(["Tes offres à l'hôtel des", "ventes et leur état.", "Offres en cours : &f" + pm("emplacements")], [(GAUCHE, "Ouvrir")]),
           {"left": [im("ouvrir ventes")]}),
    bouton("recherche", 26, "itemsadder:influitems:search", OR + "Rechercher",
           lore(["Recherche : &f" + pm("recherche"), "", "Français ou anglais : diamant,", "DIAMOND, épée, tranchant...",
                 "Aussi un vendeur ou une ville."], [(GAUCHE, "Rechercher"), (DROIT, "Effacer")]),
           {"left": [im("recherche")], "right": act("effacer")}),
    bouton("tri", 35, "itemsadder:influitems:stairs", OR + "Trier par",
           [pm("tri_%d" % k) for k in range(5)] + [OR + "├ " + GAUCHE + "Suivant", OR + "└ " + DROIT + "Précédent"],
           {"left": act("tri +"), "right": act("tri -")}),
    bouton("vide", 31, "itemsadder:influitems:unknown", OR + "Aucune offre",
           lore(["Rien ne correspond à cette", "recherche ici. Essaie une", "autre catégorie ou la", "source « Tout »."]),
           conditions=["placeholder: %s == 0" % pm("total")], priorite=-1),
]
marche += pages(44, 53)
for k, (slot, mat, nom, desc) in enumerate(SOURCES):
    choisi = oui("source_%d" % k)
    marche.append(bouton("source_%d_choisie" % k, slot, mat, OR + nom, lore(desc + ["", "&fAffiché : " + pm("total") + " offres"]),
                         conditions=[choisi], priorite=1, extra={"glow": "true"}))
    marche.append(bouton("source_%d" % k, slot, mat, OR + nom, lore(desc, [(GAUCHE, "Afficher")]), {"left": act("source %d" % k)}))
for k, (mat, nom) in enumerate(CATEGORIES):
    marche.append(bouton("categorie_%d_choisie" % k, 54 + k, mat, OR + nom, lore(["Catégorie affichée."]),
                         conditions=[oui("categorie_%d" % k)], priorite=1, extra={"glow": "true"}))
    marche.append(bouton("categorie_%d" % k, 54 + k, mat, OR + nom, lore([], [(GAUCHE, "Afficher")]), {"left": act("categorie %d" % k)}))
marche += [
    bouton("encheres", 64, "itemsadder:influmarche:enchere", OR + "Mes enchères",
           lore(["Enchères où tu as fait une", "offre : suivi, objets gagnés,", "BitBlocks à récupérer."], [(GAUCHE, "Ouvrir")]),
           {"left": [im("ouvrir encheres")]}),
    bouton("historique", 66, "itemsadder:influmarche:historique", OR + "Historique",
           lore(["Tes ventes et achats, à", "l'hôtel des ventes et dans", "les échoppes (/ah history)."], [(GAUCHE, "Ouvrir")]),
           {"left": [im("ouvrir historique")]}),
    bouton("moyenne", 68, "itemsadder:influmarche:moyenne", OR + "Prix moyen de l'objet en main", lignes("main"),
           {"left": [im("moyenne")], "right": [im("moyenne chercher")]}),
    bouton("echoppe", 70, "itemsadder:influitems:add", OR + "Ouvrir une échoppe",
           lore(["Pose un coffre dans ta ville,", "frappe-le avec l'objet et", "indique le prix dans le tchat.",
                 "Pas de taxe du serveur : la", "ville prend son droit de place", "(ici : &f%influlands_taux_ici% %" + VIOLET + ")."],
                [(GAUCHE, "Mes échoppes")]),
           {"left": ["[player] influlands ouvrir echoppes miennes"]}),
]
marche += entrees(GRILLE_MARCHE)
menu("marche", "menu_marche", marche, ouverture=[im("vue MARCHE"), "[refresh]"],
     doc="Marché : hôtel des ventes (AuctionHouse) et échoppes (FluxChestShop) réunis (/hdv, /marche, /ah).")

# ── Offre de l'hôtel des ventes ────────────────────────────────────────────
VENTE_ACHAT = [oui("offre_existe"), non("offre_enchere"), non("offre_mienne"), oui("offre_en_cours")]
ENCHERE = [oui("offre_existe"), oui("offre_enchere"), non("offre_mienne"), oui("offre_en_cours")]
offre = [
    bouton("retour", 0, "itemsadder:influitems:btn_back", OR + "Retour", lore([], [(GAUCHE, "Revenir à la liste")]),
           {"left": [im("offre retour")]}),
    bouton("info", 8, "itemsadder:influmarche:marche", OR + "Hôtel des ventes",
           lore(["Vente directe : choisis la", "quantité puis achète. L'objet", "arrive dans ton inventaire.", "",
                 "Enchère : ton offre est", "prélevée tout de suite, et", "rendue si quelqu'un fait", "mieux. Le meilleur à la fin",
                 "remporte l'objet (Mes enchères)."])),
    bouton("objet", 22, "source:marche:offre", None, lignes("offre"), conditions=[oui("offre_existe")]),
    bouton("solde", 20, "itemsadder:influitems:bitblock", OR + "Ton solde", lore(["&f" + pm("solde") + " " + BB])),
    bouton("moyenne", 24, "itemsadder:influmarche:moyenne", OR + "Prix du marché", lignes("offre_moy"), conditions=[oui("offre_existe")]),
    bouton("toutes", 38, "itemsadder:influitems:search", OR + "Toutes les offres de cet objet",
           lore(["Hôtel des ventes et échoppes."], [(GAUCHE, "Rechercher")]), {"left": [im("offre chercher")]}, conditions=[oui("offre_existe")]),
    bouton("vendeur", 42, "itemsadder:influmarche:mes_ventes", OR + "Autres offres du vendeur",
           lore([], [(GAUCHE, "Rechercher")]), {"left": [im("offre vendeur")]}, conditions=[oui("offre_existe"), non("offre_mienne")]),
    bouton("vide", 58, "itemsadder:influitems:unknown", OR + "Aucune autre offre",
           lore(["Personne d'autre ne vend", "cet objet en ce moment."]), conditions=["placeholder: %s == 0" % pm("offre_autres_total")], priorite=-1),
]
# Quantité (vente directe, vente partielle permise)
for slot, arg, lib, mat in selecteur(range(28, 35), "quantite", (1, 10, 64), None):
    offre.append(bouton("quantite_%s" % arg.replace("+", "p").replace("-", "m"), slot, mat, OR + lib,
                        lore([], [(GAUCHE, lib)]), {"left": act("offre quantite " + arg)}, conditions=VENTE_ACHAT + [oui("offre_partiel")],
                        extra=pile(arg)))
# Affichage : l'objet lui-même, empilé à la quantité choisie (lisible sans ouvrir la description).
offre.append(bouton("quantite", 31, "source:marche:offre", OR + "Quantité : &f" + pm("offre_quantite") + OR + "/" + pm("offre_quantite_max"),
                    lore(["Total : &f" + pm("offre_total") + " " + BB, "Unité : &f" + pm("offre_unite") + " " + BB], [(GAUCHE, "Saisir une quantité")]),
                    {"left": [im("offre quantite saisie")]}, conditions=VENTE_ACHAT + [oui("offre_partiel")]))
# Montant (enchère)
for slot, arg, lib, mat in selecteur(range(28, 35), "montant", (100, 1000, 10000), None, extremes=("min", None)):
    offre.append(bouton("montant_%s" % arg.replace("+", "p").replace("-", "m"), slot, mat, OR + lib,
                        lore([], [(GAUCHE, lib)]), {"left": act("offre montant " + arg)}, conditions=ENCHERE + [non("offre_premier")]))
offre.append(bouton("montant", 31, "itemsadder:influitems:bitblock", OR + "Ton offre : &f" + pm("offre_montant") + " " + BB,
                    lore(["Minimum : &f" + pm("offre_min") + " " + BB, "Prélevé maintenant : &f" + pm("offre_hausse") + " " + BB],
                         [(GAUCHE, "Saisir un montant")]),
                    {"left": [im("offre montant saisie")]}, conditions=ENCHERE + [non("offre_premier")]))
# Action principale (case 40), de la plus particulière à la plus générale
CONFIRM = "&#FFD94DClique encore pour confirmer"
offre += [
    bouton("retirer", 40, "itemsadder:influitems:deny", OR + "Retirer mon offre",
           lore(["L'objet revient dans", "ton inventaire."], [(GAUCHE, "Retirer (double clic)")]),
           {"left": [im("offre retirer"), "[refresh]"]}, conditions=[oui("offre_existe"), oui("offre_mienne"), oui("offre_retirable")],
           priorite=6, extra={"confirm": q(CONFIRM)}),
    bouton("mienne", 40, "itemsadder:influitems:checkmark", OR + "Ton enchère",
           lore(["Elle a reçu des offres : elle", "ne peut plus être retirée."]),
           conditions=[oui("offre_existe"), oui("offre_mienne")], priorite=5),
    bouton("terminee", 40, "itemsadder:influitems:lock", OR + "Offre indisponible",
           lore(["Vendue, terminée ou pas", "encore en ligne."]), conditions=[oui("offre_existe"), non("offre_en_cours")], priorite=4),
    bouton("premier", 40, "itemsadder:influitems:checkmark", OR + "Tu as la meilleure offre",
           lore(["Si personne ne fait mieux,", "l'objet est à toi à la fin."]), conditions=ENCHERE + [oui("offre_premier")], priorite=3),
    bouton("acheter", 40, "itemsadder:influmarche:acheter", OR + "Acheter",
           lore(["&fx" + pm("offre_quantite") + VIOLET + " pour &f" + pm("offre_total") + " " + BB], [(GAUCHE, "Acheter (double clic)")]),
           {"left": [im("offre acheter"), "[refresh]"]}, conditions=VENTE_ACHAT + [oui("offre_peut")], priorite=2,
           extra={"confirm": q(CONFIRM)}),
    bouton("encherir", 40, "itemsadder:influmarche:enchere", OR + "Enchérir",
           lore(["Ton offre : &f" + pm("offre_montant") + " " + BB, "Prélevé maintenant : &f" + pm("offre_hausse") + " " + BB],
                [(GAUCHE, "Enchérir (double clic)")]),
           {"left": [im("offre encherir"), "[refresh]"]}, conditions=ENCHERE + [oui("offre_peut")], priorite=2,
           extra={"confirm": q(CONFIRM)}),
    bouton("pauvre", 40, "itemsadder:influitems:deny", OR + "Solde insuffisant",
           lore(["Ton solde : &f" + pm("solde") + " " + BB]), conditions=[oui("offre_existe"), non("offre_mienne"), oui("offre_en_cours")], priorite=1),
]
for n, slot in enumerate(range(55, 62), start=1):
    base = "autre_%d" % n
    offre.append(bouton(base, slot, "source:marche:autre:%d" % n, None, lignes(base),
                        {"left": [im("autre %d gauche" % n)], "right": [im("autre %d droit" % n)]}, conditions=[oui(base + "_existe")]))
menu("marche_offre", "menu_marche_offre", offre, ouverture=["[refresh]"],
     doc="Offre de l'hôtel des ventes : achat (quantité), enchère (montant), retrait ; comparatif des autres offres du même objet.")

# ── Mise en vente ──────────────────────────────────────────────────────────
CHOISI = [oui("vente_choisi")]
vendre = [
    retour_marche(),
    bouton("info", 8, "itemsadder:influitems:add", OR + "Mettre en vente",
           lore(["1. Choisis un objet en bas.", "2. Règle la quantité et le prix.", "3. Mets en vente (double clic).", "",
                 "Taxe du serveur : &f" + pm("taxe") + " %" + VIOLET + ",", "prélevée au moment de la vente.",
                 "Offres en cours : &f" + pm("emplacements") + VIOLET + " (ton rang).", "",
                 "Sans taxe du serveur : ouvre", "une échoppe dans une ville."])),
    bouton("type_enchere", 19, "itemsadder:influmarche:enchere", OR + "Type : Enchère",
           lore(["Mise de départ, puis le plus", "offrant l'emporte à la fin."], [(GAUCHE, "Passer en vente directe")]),
           {"left": act("vente type")}, conditions=[oui("vente_enchere")], priorite=1, extra={"glow": "true"}),
    bouton("type_vente", 19, "itemsadder:influmarche:mes_ventes", OR + "Type : Vente directe",
           lore(["Prix fixe, le premier qui", "paie l'emporte (achat d'une", "partie possible)."], [(GAUCHE, "Passer aux enchères")]),
           {"left": act("vente type")}, conditions=[oui("encheres_actives")]),
    bouton("objet", 22, "source:marche:vente", None, lignes("vente"), {"left": [im("vente confirmer")]}, conditions=CHOISI, priorite=1,
           extra={"confirm": q(CONFIRM)}),
    bouton("objet_vide", 22, "itemsadder:influitems:add", OR + "Choisis un objet",
           lore(["Clique sur un objet de ton", "inventaire, en bas."])),
    bouton("moyenne", 25, "itemsadder:influmarche:moyenne", OR + "Prix du marché", lignes("vente_moy"),
           {"left": act("vente prix moyenne")}, conditions=CHOISI),
    # Affichage : l'objet lui-même, empilé à la quantité choisie.
    bouton("quantite", 31, "source:marche:vente", OR + "Quantité : &f" + pm("vente_quantite") + OR + "/" + pm("vente_max"),
           lore([], [(GAUCHE, "Saisir une quantité")]), {"left": [im("vente quantite saisie")]}, conditions=CHOISI),
    bouton("prix", 40, "itemsadder:influitems:bitblock", OR + "Prix : &f" + pm("vente_prix") + " " + BB,
           lore(["Prix total de l'offre.", "Tu recevras : &f" + pm("vente_net") + " " + BB], [(GAUCHE, "Saisir un prix")]),
           {"left": [im("vente prix saisie")]}, conditions=CHOISI),
    bouton("vider", 45, "itemsadder:influitems:deny", OR + "Annuler", lore(["Vide le formulaire."], [(GAUCHE, "Annuler")]),
           {"left": act("vente vider")}, conditions=CHOISI),
    bouton("confirmer", 49, "itemsadder:influitems:accept", OR + "Mettre en vente", lignes("vente"),
           {"left": [im("vente confirmer")]}, conditions=CHOISI, extra={"confirm": q(CONFIRM)}),
    bouton("mes_ventes", 53, "itemsadder:influmarche:mes_ventes", OR + "Mes ventes",
           lore(["Offres en cours : &f" + pm("emplacements")], [(GAUCHE, "Ouvrir")]), {"left": [im("ouvrir ventes")]}),
]
for slot, arg, lib, mat in selecteur(range(28, 35), "quantite", (1, 16, 64), None):
    vendre.append(bouton("quantite_%s" % arg.replace("+", "p").replace("-", "m"), slot, mat, OR + lib,
                         lore([], [(GAUCHE, lib)]), {"left": act("vente quantite " + arg)}, conditions=CHOISI, extra=pile(arg)))
for slot, arg, lib, mat in selecteur(range(37, 44), "prix", (100, 1000, 10000), None, extremes=(None, None)):
    vendre.append(bouton("prix_%s" % arg.replace("+", "p").replace("-", "m"), slot, mat, OR + lib,
                         lore([], [(GAUCHE, lib + " " + BB)]), {"left": act("vente prix " + arg)}, conditions=CHOISI))
# Inventaire du joueur dans la zone du bas : 54-80 = cases 9-35, 81-89 = barre d'action 0-8.
for slot in range(54, 90):
    case = slot - 45 if slot < 81 else slot - 81
    base = "inv_%d" % case
    vendre.append(bouton(base + "_choisi", slot, "source:marche:inv:%d" % case, None, [OR + "└ " + VIOLET + "Objet choisi"],
                         conditions=[oui(base + "_choisi")], priorite=1, extra={"glow": "true"}))
    vendre.append(bouton(base, slot, "source:marche:inv:%d" % case, None, [OR + "└ " + GAUCHE + "Choisir"],
                         {"left": act("vente choisir %d" % case)}, conditions=[oui(base + "_existe")]))
menu("marche_vendre", "menu_marche_vendre", vendre, ouverture=["[refresh]"],
     doc="Mise en vente à l'hôtel des ventes : objet choisi dans l'inventaire (zone du bas), quantité, prix, type.")


# ── Listes : mes ventes, mes enchères, historique ──────────────────────────
def liste(nom, fond, vue, info, barre, vide, doc):
    items = [retour_marche(), info] + entrees(GRILLE_LISTE) + pages(48, 50) + barre
    items.append(bouton("vide", 31, "itemsadder:influitems:unknown", OR + vide[0], lore(vide[1]),
                        conditions=["placeholder: %s == 0" % pm("total")], priorite=-1))
    menu(nom, fond, items, ouverture=[im("vue " + vue), "[refresh]"], doc=doc)


RECUP = [
    bouton("recuperer", 45, "itemsadder:influitems:chest_item", OR + "Tout récupérer",
           lore(["&f" + pm("a_recuperer") + VIOLET + " ventes ou enchères", "terminées à récupérer."], [(GAUCHE, "Tout récupérer")]),
           {"left": act("recuperer")}, conditions=[oui("a_recuperer_oui")], priorite=1, extra={"glow": "true"}),
    bouton("recuperer_rien", 45, "itemsadder:influitems:chest", OR + "Rien à récupérer", lore(["Tout est à jour."])),
]
liste("marche_ventes", "menu_marche_ventes", "VENTES",
      bouton("info", 8, "itemsadder:influmarche:mes_ventes", OR + "Mes ventes",
             lore(["Tes offres à l'hôtel des ventes.", "Offres en cours : &f" + pm("emplacements"),
                   "Taxe à la vente : &f" + pm("taxe") + " %", "", "Une offre expirée ou vendue", "brille : récupère-la."])),
      RECUP + [
          bouton("vendre", 47, "itemsadder:influitems:add", OR + "Vendre un objet", lore([], [(GAUCHE, "Choisir un objet")]),
                 {"left": [im("ouvrir vendre")]}),
          bouton("historique", 51, "itemsadder:influmarche:historique", OR + "Historique", lore([], [(GAUCHE, "Ouvrir")]),
                 {"left": [im("ouvrir historique")]}),
          bouton("encheres", 53, "itemsadder:influmarche:enchere", OR + "Mes enchères", lore([], [(GAUCHE, "Ouvrir")]),
                 {"left": [im("ouvrir encheres")]}),
      ],
      ("Aucune offre", ["Tu ne vends rien pour", "le moment."]),
      "Mes ventes à l'hôtel des ventes : état, récupération, retrait (double clic).")
liste("marche_encheres", "menu_marche_encheres", "ENCHERES",
      bouton("info", 8, "itemsadder:influmarche:enchere", OR + "Mes enchères",
             lore(["Les enchères où tu as fait", "une offre. Perdue : ton offre", "t'est rendue ici. Gagnée :", "l'objet t'attend ici."])),
      RECUP + [
          bouton("voir", 47, "itemsadder:influmarche:marche", OR + "Enchères en cours", lore(["Hôtel des ventes."], [(GAUCHE, "Voir")]),
                 {"left": [im("source 1"), im("ouvrir marche")]}),
          bouton("historique", 51, "itemsadder:influmarche:historique", OR + "Historique", lore([], [(GAUCHE, "Ouvrir")]),
                 {"left": [im("ouvrir historique")]}),
          bouton("ventes", 53, "itemsadder:influmarche:mes_ventes", OR + "Mes ventes", lore([], [(GAUCHE, "Ouvrir")]),
                 {"left": [im("ouvrir ventes")]}),
      ],
      ("Aucune enchère", ["Tu n'as fait aucune offre", "en cours."]),
      "Mes enchères : suivi, objet remporté ou offre rendue.")
liste("marche_historique", "menu_marche_historique", "HISTORIQUE",
      bouton("info", 8, "itemsadder:influmarche:historique", OR + "Historique",
             lore(["Tes ventes et achats, à", "l'hôtel des ventes et dans", "les échoppes.", "Entrées : &f" + pm("total")])),
      [
          bouton("moyenne", 45, "itemsadder:influmarche:moyenne", OR + "Prix moyen de l'objet en main", lignes("main"),
                 {"left": [im("moyenne")], "right": [im("moyenne chercher")]}),
          bouton("filtre", 47, "itemsadder:influitems:filter", OR + "Afficher",
                 [pm("filtre_%d" % k) for k in range(3)] + [OR + "├ " + GAUCHE + "Suivant", OR + "└ " + DROIT + "Précédent"],
                 {"left": act("filtre +"), "right": act("filtre -")}),
          bouton("ventes", 51, "itemsadder:influmarche:mes_ventes", OR + "Mes ventes", lore([], [(GAUCHE, "Ouvrir")]),
                 {"left": [im("ouvrir ventes")]}),
          bouton("encheres", 53, "itemsadder:influmarche:enchere", OR + "Mes enchères", lore([], [(GAUCHE, "Ouvrir")]),
                 {"left": [im("ouvrir encheres")]}),
      ],
      ("Historique vide", ["Aucune vente ni aucun achat", "enregistré pour l'instant."]),
      "Historique des ventes et achats du joueur, hôtel des ventes et échoppes (/ah history).")
print("6 menus ->", SORTIE)
