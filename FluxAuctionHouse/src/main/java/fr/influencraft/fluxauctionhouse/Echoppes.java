package fr.influencraft.fluxauctionhouse;

import com.ghostchu.quickshop.api.QuickShopAPI;
import com.ghostchu.quickshop.api.event.economy.ShopSuccessPurchaseEvent;
import com.ghostchu.quickshop.api.shop.Shop;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lien avec FluxChestShop (chargé seulement s'il est présent) : les échoppes et rachats des
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
        for (Shop shop : QuickShopAPI.getInstance().getShopManager().getAllShops()) {
            try {
                Location loc = shop.bukkitLocation();
                if (loc == null || loc.getWorld() == null) continue;
                ItemStack item = shop.getItem().clone();
                int stock = stock(shop, loc);
                if (stock < 0) stock = Ville.stockConnu(shop.getShopId());
                if (stock == 0) continue;
                boolean rachat = shop.isBuying();
                UUID proprio = shop.getOwner().getUniqueIdIfRealPlayer().orElse(null);
                String proprioNom = shop.getOwner().getUsernameOptional().orElse(shop.getOwner().getDisplay());
                String ville = Ville.nomA(loc);
                l.add(new Offre(rachat ? Offre.Type.RACHAT : Offre.Type.ECHOPPE, "e" + shop.getShopId(), item, Objets.cle(item),
                        Objets.nom(item), Objets.index(item) + " " + Objets.normaliser(proprioNom + " " + (ville == null ? "" : ville)),
                        Objets.categorie(item), shop.getPrice(), Math.max(1, item.getAmount()), proprio, proprioNom, -1, stock,
                        ville, Ville.idA(loc), Ville.droitA(loc), loc.clone(), 0, 0, 0));
            } catch (RuntimeException e) {
                // échoppe corrompue ou monde absent : ignorée jusqu'au passage suivant
            }
        }
        return l;
    }

    /** Stock (vente) ou place libre (rachat), seulement si le chunk est chargé : -1 sinon. */
    private static int stock(Shop shop, Location loc) {
        if (shop.isUnlimited()) return Integer.MAX_VALUE;
        World w = loc.getWorld();
        if (w == null || !w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return -1;
        try {
            return shop.isBuying() ? shop.getRemainingSpace() : shop.getRemainingStock();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Transaction réussie d'une échoppe : enregistrée dans l'historique. Le nombre d'objets est
     * déduit du montant hors taxe et du prix unitaire de l'échoppe (indépendant de la manière dont
     * FluxChestShop compte les lots).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void transaction(ShopSuccessPurchaseEvent e) {
        try {
            Shop shop = e.getShop();
            ItemStack item = shop.getItem().clone();
            double unite = shop.getPrice() / Math.max(1, item.getAmount());
            double total = e.getBalanceWithoutTax();
            int quantite = unite > 0 ? (int) Math.round(total / unite) : e.getAmount();
            if (quantite <= 0) return;
            boolean rachat = shop.isBuying();
            UUID proprio = shop.getOwner().getUniqueIdIfRealPlayer().orElse(null);
            String proprioNom = shop.getOwner().getUsernameOptional().orElse(shop.getOwner().getDisplay());
            UUID client = e.getPurchaser().getUniqueIdIfRealPlayer().orElse(null);
            String clientNom = e.getPurchaser().getUsernameOptional().orElse(e.getPurchaser().getDisplay());
            item.setAmount(Math.min(quantite, item.getMaxStackSize()));
            Location loc = shop.bukkitLocation();
            plugin.historique().enregistrer(new Historique.Vente(System.currentTimeMillis(), rachat ? "rachat" : "echoppe",
                    String.valueOf(shop.getShopId()),
                    rachat ? client : proprio, rachat ? clientNom : proprioNom,
                    rachat ? proprio : client, rachat ? proprioNom : clientNom,
                    Objets.cle(item), Objets.nom(item), quantite, total, e.getTax(), Hdv.bytes(item), Ville.nomA(loc)));
            plugin.catalogue().invalider();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Transaction d'échoppe non enregistrée : " + ex);
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
