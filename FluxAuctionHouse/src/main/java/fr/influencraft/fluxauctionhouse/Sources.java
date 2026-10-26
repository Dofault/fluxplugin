package fr.influencraft.fluxauctionhouse;

import fr.influencraft.flux.api.FluxApi;
import me.elaineqheart.auctionHouse.data.ram.ItemNote;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Source d'objets Flux {@code marche} : les menus affichent les vrais objets (enchantements,
 * nom, objets ItemsAdder...) avec {@code material: "source:marche:<argument>"}.
 *
 * <pre>
 *   liste:&lt;n&gt;   n-ième entrée de la page de la liste courante
 *   offre       objet de l'offre détaillée (quantité choisie pour une vente directe)
 *   autre:&lt;n&gt;   n-ième autre offre du même objet (comparatif)
 *   vente       objet choisi pour la mise en vente (quantité choisie)
 *   inv:&lt;case&gt; case de l'inventaire du joueur (0-8 barre d'action, 9-35 inventaire)
 * </pre>
 * Les objets sont des copies marquées par Flux : ils ne peuvent pas finir dans un inventaire.
 */
final class Sources {

    private Sources() {
    }

    static void inscrire(FluxAuctionHouse plugin) {
        FluxApi.registerItemSource(plugin, "marche", (p, arg) -> objet(plugin, p, arg));
    }

    static void retirer() {
        FluxApi.unregisterItemSource("marche");
    }

    private static ItemStack objet(FluxAuctionHouse plugin, Player p, String arg) {
        Vues v = plugin.vues();
        String[] a = arg.split(":");
        switch (a[0]) {
            case "liste": {
                Vues.Entree e = v.entree(p, Integer.parseInt(a[1]));
                return e == null ? null : e.item();
            }
            case "offre": {
                ItemNote n = v.noteOffre(p);
                if (n == null) return null;
                ItemStack item = n.getItem();
                item.setAmount(Math.min(99, n.isBIDAuction() ? n.getCurrentAmount() : v.quantiteOffre(p, n)));
                return item;
            }
            case "autre": {
                List<Offre> l = v.autres(p);
                int n = Integer.parseInt(a[1]);
                return n >= 1 && n <= l.size() ? l.get(n - 1).item() : null;
            }
            case "vente":
                return v.objetVente(p);
            case "inv":
                return Vues.inventaire(p, Integer.parseInt(a[1]));
            default:
                return null;
        }
    }
}
