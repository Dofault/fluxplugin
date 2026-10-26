# FluxChestShop : fork Influencraft de QuickShop-Hikari

- Origine : https://github.com/QuickShop-Community/QuickShop-Hikari, tag `6.3.0.1` (licence GPLv3, voir `LICENSE` ; contributeurs d'origine dans l'historique du dépôt amont).
- Repris le 2026-10-19 par Dofault et Entr0pik sous le nom InfluEchoppe, renommé **FluxChestShop** le 2026-10-26 (suite Flux, `provides: [QuickShop-Hikari, InfluEchoppe]`, ancien dossier `plugins/InfluEchoppe/` recopié au premier démarrage). Doc : `docs/technique/influechoppe.md`.
- Toutes les modifications portent un commentaire `FluxChestShop` (rechercher ce mot pour les réappliquer sur une nouvelle version amont) :
  - `plugin.yml` : nom `FluxChestShop`, `provides: [QuickShop-Hikari, InfluEchoppe]`, `api-version: '26.2'`, auteurs, description ;
  - `QuickShopAPI` : identité de données figée `DATA_NAME`, `DATA_NAMESPACE`, `dataName(plugin)`, utilisée par `Shop`, `BuiltInShopPermission(Group)`, `InventoryWrapperRegistry`, `ContainerShop`, `SimpleShopManager`, `SimpleShopPermissionManager`, `InventoryPreview`, `AbstractDisplayItem`, `DisplayEntityItemManager`, `ShopProtectionListener` ;
  - `QuickShop` : commande `echoppe` (préfixe `fluxchestshop`), ligne de crédit du fork au démarrage ; `QuickShopBukkit` : nom du logger ; `config.yml` : `custom-commands` ;
  - build : `paperApiVersion` (gradle.properties, 26.2 par défaut), toolchain Java 25, jar `FluxChestShop-<version>.jar`, modules `addon/*` et `compatibility/*` retirés.
- 2026-10-19 : création rapide par dialogue (`CreateDialog`, comportement `CREATE_DIALOG` sur `SNEAKING_RIGHT_CLICK_CONTAINER`), vérifications de `Util.createShop` extraites dans `Util.prepareShopCreation`, `SimpleShopManager#actionCreate` surchargée avec le type d'échoppe, `config.yml` : `shop.quick-create-dialog` et pancartes / cadres dans `blacklist`.
