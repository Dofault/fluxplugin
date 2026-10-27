package fr.influencraft.fluxauctionhouse;

import fr.influencraft.flux.api.shops.ChestShop;
import fr.influencraft.flux.api.shops.ChestShopTradeEvent;
import fr.influencraft.flux.api.shops.ChestShops;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Chest shops of the players (Flux chest shops service, FluxChestShop today) : les échoppes et rachats des
 * villes entrent dans le catalogue du marché, et chaque transaction réussie est écrite dans
 * l'historique (prix moyen commun à l'hôtel des ventes et aux échoppes).
 *
 * <p>La ville et le droit de place viennent de FluxLands ({@link Ville}) quand il est là.</p>
 */
final class Echoppes implements Listener {

    private final FluxAuctionHouse plugin;

    Echoppes(FluxAuctionHouse plugin) {
        this.plugin = plugin;
    }

    /**
     * Échoppes et rachats actifs (fil principal). Échoppe vide ou rachat plein : écartés, y compris
     * quand sa zone n'est pas chargée, grâce au dernier stock connu relevé par FluxLands.
     */
    List<Offre> offres() {
        List<Offre> l = new ArrayList<>();
        for (ChestShop shop : ChestShops.all()) {
            try {
                Location loc = shop.location();
                if (loc == null || loc.getWorld() == null) continue;
                ItemStack item = shop.item().clone();
                int stock = shop.stock();
                if (stock < 0) stock = Ville.stockConnu(shop.id());
                if (stock == 0) continue;
                boolean rachat = shop.buying();
                String ville = Ville.nomA(loc);
                l.add(new Offre(rachat ? Offre.Type.RACHAT : Offre.Type.ECHOPPE, "e" + shop.id(), item, Objets.cle(item),
                        Objets.nom(item), Objets.index(item) + " " + Objets.normaliser(shop.ownerName() + " " + (ville == null ? "" : ville)),
                        Objets.categorie(item), shop.price(), Math.max(1, item.getAmount()), shop.owner(), shop.ownerName(), -1, stock,
                        ville, Ville.idA(loc), Ville.droitA(loc), loc.clone(), 0, 0, 0));
            } catch (RuntimeException e) {
                // broken shop or missing world: skipped until the next pass
            }
        }
        return l;
    }

    /**
     * A successful chest shop trade goes to the history. The item count comes from the money (tax excluded) and the
     * unit price, whatever way the shop plugin counts bundles.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void transaction(ChestShopTradeEvent e) {
        try {
            ChestShop shop = e.shop();
            ItemStack item = shop.item().clone();
            double unite = shop.price() / Math.max(1, item.getAmount());
            double total = e.total();
            int quantite = unite > 0 ? (int) Math.round(total / unite) : e.amount();
            if (quantite <= 0) return;
            boolean rachat = shop.buying();
            item.setAmount(Math.min(quantite, item.getMaxStackSize()));
            Location loc = shop.location();
            plugin.historique().enregistrer(new Historique.Vente(System.currentTimeMillis(), rachat ? "rachat" : "echoppe",
                    String.valueOf(shop.id()),
                    rachat ? e.customer() : shop.owner(), rachat ? e.customerName() : shop.ownerName(),
                    rachat ? shop.owner() : e.customer(), rachat ? shop.ownerName() : e.customerName(),
                    Objets.cle(item), Objets.nom(item), quantite, total, e.tax(), Hdv.bytes(item), Ville.nomA(loc)));
            plugin.catalogue().invalider();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Chest shop trade not recorded: " + ex);
        }
    }

    /** Téléportation vers une échoppe : par FluxLands (warp le plus proche, spawn de la ville), sinon coordonnées. */
    void localiser(Player p, Offre o) {
        p.closeInventory();
        if (Ville.localiser(p, o.shopId())) return;
        Location l = o.lieu();
        Fmt.envoyer(p, Fmt.VIOLET + "L'échoppe de &f" + o.vendeur() + Fmt.VIOLET + " est en &f" + l.getBlockX() + " " + l.getBlockY() + " "
                + l.getBlockZ() + Fmt.VIOLET + " (" + l.getWorld().getName() + ").");
    }
}
