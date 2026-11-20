# Lands : « nation » devient « pays » dans les textes joueur

Conversion faite sur Labs le 2026-10-31 (convention : `CLAUDE.md` racine, « Pays prime sur nation »). À rejouer sur la prod le jour de la bascule.

1. Copier les 6 fichiers de `plugins/Lands/Locale/` (`fr-FR.yml`, `fr-FR_gui.yml`, `fr-FR_dialogs.yml`, `Nations/fr-FR.yml`, `Nations/fr-FR_gui.yml`, `Nations/fr-FR_dialogs.yml`) dans un dossier de travail, sous les noms `fr-FR.yml`, `fr-FR_gui.yml`, `fr-FR_dialogs.yml`, `Nations_fr-FR.yml`, `Nations_fr-FR_gui.yml`, `Nations_fr-FR_dialogs.yml` (sauvegarde des originaux d'abord).
2. Dans ce dossier : `python3 nation_to_pays.py <original> <nom de travail>` pour chacun (mots du texte accordés au masculin, `/n` et `/nations` en `/pays` ; clés, `<v:...>`, `<t:...>`, `%...%`, commentaires et sous-commandes de Lands intacts).
3. `python3 fixes.py` dans le même dossier : accords, pronoms et lignes restées en anglais. Une ligne `MISSING` veut dire que la prod diffère de Labs : relire à la main. Puis les deux corrections notées en fin de `fixes.py`.
4. Relire le résultat, le recopier dans `Locale/`, puis `python3 charte_aide.py Locale/fr-FR.yml Locale/Nations/fr-FR.yml` (11 lignes d'aide restées dans le style d'origine de Lands, dont l'aide `/pays admin` : description grise de la charte au lieu du violet, textes anglais traduits ; ajouté le 2026-11-01). Ensuite, faire à la main `levels.yml` (niveau 2 « État », annonces), `roles.yml` (rôle « Compatriote ») et `config.yml` (`alias.nations_list: [pays, p]`), comme sur Labs.
