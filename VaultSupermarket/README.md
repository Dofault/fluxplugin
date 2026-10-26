# VaultSupermarket (Paper 26.2)

Plugin maison d'Influencraft : coffres magasins à menu d'achat/vente, blocs de vente, catalogue de prix (`prices.yml`) et objet « Vente Automatique ». Économie via Vault.

Documentation complète : [`docs/technique/supermarket.md`](../../docs/technique/supermarket.md).

## Commandes (`/supermarket`)

| Commande | Rôle |
| -------- | ---- |
| `addchest` / `removechest` | Assigne / désassigne le coffre magasin regardé |
| (accroupi + clic droit) | Éditeur du coffre magasin : ajouter un objet de son inventaire, changer un prix, retirer |
| `addsellblock` / `removesellblock` | Assigne / désassigne le bloc de vente regardé |
| `setprice <item> <achat> <vente>` | Prix d'un objet dans `prices.yml` |
| `checkprices` | Objets de `prices.yml` inconnus sur cette version |
| `tpshop [joueur]` / `settpshop` | Téléportation au magasin / pose du point d'arrivée |
| `autosell [joueur]` | Donne l'objet de vente automatique |
| `reload` | Recharge config, langue, prix et magasins |

## Build

```bash
mvn -o package -DskipTests
```

Le jar est copié automatiquement sur Labs et Semi-Lab, jamais sur la prod. Sur la machine du serveur, compiler dans un cgroup plafonné en mémoire (voir la doc).
