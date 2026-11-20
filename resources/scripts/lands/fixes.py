"""Hand fixes after nation_to_pays.py: agreement (pays is masculine), pronouns, English lines translated."""
import sys
import yaml

FIX = {
 "fr-FR.yml": [
  ("<t:regular>Have <t:regular_value><v:req></t> members in the lands of the pays. Command: <t:cmd>/lands trust</t>",
   "<t:regular>Avoir <t:regular_value><v:req></t> habitants dans les villes du pays. Commande : <t:cmd>/lands trust</t>"),
  ("<t:regular>Have <t:regular_value><v:req></t> claims in the lands of the pays.",
   "<t:regular>Avoir <t:regular_value><v:req></t> chunks revendiqués dans les villes du pays."),
  ("<t:regular>The pays exists for at least <t:regular_value><v:req></t>.",
   "<t:regular>Le pays existe depuis au moins <t:regular_value><v:req></t>."),
  ("<t:regular>Have <t:regular_value><v:req></t> lands in the pays, besides the capital. Command: <t:cmd>/pays trust</t>",
   "<t:regular>Avoir <t:regular_value><v:req></t> villes dans le pays, en plus de la capitale. Commande : <t:cmd>/pays trust</t>"),
  ("<t:regular>A land counts after <t:time><v:time></t> in the pays.",
   "<t:regular>Une ville compte après <t:time><v:time></t> dans le pays."),
  ("<t:regular> counts for the level requirements of </t><t:regular_value><v:nation></t><t:regular> after </t><t:time><v:time></t><t:regular> in the pays.</t>",
   "<t:regular> comptera pour les objectifs de niveau de </t><t:regular_value><v:nation></t><t:regular> après </t><t:time><v:time></t><t:regular> dans le pays.</t>"),
  ("<t:erreur>Aucune ville ou pays nommée<t:valeur> <v:object></t>.", "<t:erreur>Aucune ville ni aucun pays nommé<t:valeur> <v:object></t>."),
  ("par une autre ville ou pays.", "par une autre ville ou un autre pays."),
  ("<t:erreur>Il n'existe aucune ville ou pays nommée<t:valeur>", "<t:erreur>Il n'existe aucune ville ni aucun pays nommé<t:valeur>"),
  ("frais d'entretien est supprimée.", "frais d'entretien est supprimé."),
 ],
 "fr-FR_dialogs.yml": [
  ("<t:regular>Leave the pays <t:entity_nation><v:nation></t> with the land <t:entity_land><v:land></t>?",
   "<t:regular>Quitter le pays <t:entity_nation><v:nation></t> avec la ville <t:entity_land><v:land></t> ?"),
  ("<t:regular>Delete the pays <t:entity_nation><v:nation></t>? This can't be undone.",
   "<t:regular>Supprimer le pays <t:entity_nation><v:nation></t> ? C'est définitif."),
  ("<t:regular>Make the land <t:entity_land><v:land></t> the new capital of the pays <t:entity_nation><v:nation></t>?",
   "<t:regular>Faire de la ville <t:entity_land><v:land></t> la nouvelle capitale du pays <t:entity_nation><v:nation></t> ?"),
  ("<t:regular>Declare independence from the pays <t:entity_nation><v:nation></t>? The land <t:entity_land><v:land></t> leaves it and a war against it starts.",
   "<t:regular>Déclarer l'indépendance face au pays <t:entity_nation><v:nation></t> ? La ville <t:entity_land><v:land></t> le quitte et une guerre contre lui commence."),
 ],
 "Nations_fr-FR.yml": [
  ("<t:texte>Pays<t:nation> <v:0></t> créée.", "<t:texte>Pays<t:nation> <v:0></t> créé."),
  ("<t:succes>fondée, avec</t>", "<t:succes>fondé, avec</t>"),
  ("depuis le solde de sa capitale, et a été supprimée.", "depuis le solde de sa capitale, et a été supprimé."),
  ("<t:texte>Le pays</t> <t:nation><v:nation></t> <t:texte>a été supprimée.", "<t:texte>Le pays</t> <t:nation><v:nation></t> <t:texte>a été supprimé."),
  ("Fondée le", "Fondé le"),
  ("Aucun pays trouvée pour le tchat de pays.", "Aucun pays trouvé pour le tchat de pays."),
  ("Lister toutes les pays", "Lister tous les pays"),
  ("Ajoute des villes et pays comme alliées ou ennemies.", "Ajoute des villes et des pays comme alliés ou ennemis."),
  ("Ajoute des villes et pays comme alliées.", "Ajoute des villes et des pays comme alliés."),
  ("Ajoute une ville ou un pays comme alliée.", "Ajoute une ville ou un pays comme allié."),
  ("Ajoute des villes et pays comme ennemies.", "Ajoute des villes et des pays comme ennemis."),
  ("Déclare une ville ou un pays comme ennemie.", "Déclare une ville ou un pays comme ennemi."),
  ("<t:regular>Pays administration</click>", "<t:regular>Administration des pays</click>"),
  ("<t:regular>Manage a pays</click>", "<t:regular>Gérer un pays</click>"),
  ("<t:regular>Set the level of a pays</click>", "<t:regular>Régler le niveau d'un pays</click>"),
  ("<t:regular>Manage the war shield of a pays</click>", "<t:regular>Gérer le bouclier de guerre d'un pays</click>"),
  ("Clique pour déclarer le pays ennemie.", "Clique pour déclarer le pays ennemi."),
  ("Déclarer son pays ennemie", "Déclarer son pays ennemi"),
  ("de la même pays", "du même pays"),
  ("Seule le pays peut la libérer.", "Seul le pays peut la libérer."),
  ("Quitte-la d'abord avec", "Quitte-le d'abord avec"),
  ("Elle doit d'abord la quitter avec", "Elle doit d'abord le quitter avec"),
  ("à la rejoindre.", "à le rejoindre."),
  ("elle ne peut pas la quitter.", "elle ne peut pas le quitter."),
 ],
 "Nations_fr-FR_gui.yml": [
  ("<t:regular_heading>Rôle de pays", "<t:regular_heading>Rôle Compatriote"),
  ("Ton pays n'est engagée", "Ton pays n'est engagé"),
 ],
 "fr-FR_gui.yml": [
  ("Toutes les pays", "Tous les pays"),
  ("Voir toutes les pays", "Voir tous les pays"),
  ("Seule le pays peut", "Seul le pays peut"),
 ],
}
missing = 0
for f, pairs in FIX.items():
    s = open(f, encoding="utf-8").read()
    for a, b in pairs:
        n = s.count(a)
        if n == 0:
            print("MISSING", f, a[:70])
            missing += 1
            continue
        s = s.replace(a, b)
        if n > 1:
            print("x%d" % n, f, a[:50])
    yaml.safe_load(s)
    open(f, "w", encoding="utf-8").write(s)
sys.exit(1 if missing else 0)
# applied by hand after this script (2026-10-31): Nations/fr-FR_gui.yml « engagée dans une guerre » -> « engagé »,
# fr-FR_gui.yml « Possédées » of the « Tes pays » button -> « Possédés »
