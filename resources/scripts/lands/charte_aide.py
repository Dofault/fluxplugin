"""Help lines of Lands left in Lands' own style (<t:regular>, <t:cmd>, « Click to copy. ») put in the server's help
style, as every other help line: hover <t:survol>, command <t:commande>, dash <t:deco>, grey <t:aide_description>
(theme.yml). English descriptions translated (land = ville, area = parcelle). Edits the given files in place.

    python3 charte_aide.py Locale/fr-FR.yml Locale/Nations/fr-FR.yml
"""
import re
import sys

import yaml

LINE = re.compile(r'<hover:show_text:"<t:regular>Click to copy\."><click:suggest_command:"/<v:fullcmd>">'
                  r'<t:cmd><v:parent> <v:subcmd> <v:args> </t><t:decoration>- </t><t:regular>([^<]*)</click></hover>')
FRENCH = {
    "Assign selection to a new or existing area": "Assigner la sélection à une parcelle, nouvelle ou existante",
    "Buy an area or a land that's for sale": "Acheter une parcelle ou une ville en vente",
    "Set the level of a land": "Régler le niveau d'une ville",
    "Land expiration": "Expiration des villes",
    "Lands that expire soon": "Villes qui vont bientôt expirer",
    "Check inactive lands now": "Vérifier maintenant les villes inactives",
    "Set the war shield": "Régler le bouclier de guerre",
    "Add (+) or remove (-) war shield time": "Ajouter (+) ou retirer (-) du temps de bouclier de guerre",
}


def styled(m):
    text = FRENCH.get(m.group(1), m.group(1))
    return ('<hover:show_text:"<t:survol>Clique pour copier la commande."><click:suggest_command:"/<v:fullcmd>">'
            '<t:commande><v:parent> <v:subcmd> <v:args></t> <t:deco>-</t> <t:aide_description>' + text + '</click></hover>')


for path in sys.argv[1:]:
    s = open(path, encoding="utf-8").read()
    out, n = LINE.subn(styled, s)
    yaml.safe_load(out)
    if n:
        open(path, "w", encoding="utf-8").write(out)
    print(path, n, "lines")
