package fr.influencraft.influlands;

import com.ghostchu.quickshop.api.QuickShopAPI;
import com.ghostchu.quickshop.api.event.economy.ShopPurchaseEvent;
import com.ghostchu.quickshop.api.event.economy.ShopSuccessPurchaseEvent;
import com.ghostchu.quickshop.api.event.management.ShopPermissionCheckEvent;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermission;
import me.angeschossen.lands.api.flags.type.Flags;
import me.angeschossen.lands.api.land.Area;
import me.angeschossen.lands.api.land.Land;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lien avec InfluEchoppe (chargé seulement s'il est présent) :
 * <ul>
 *   <li>index des échoppes et de la ville où se trouve chacune ;</li>
 *   <li>droit de place : à chaque transaction réussie d'une échoppe située dans une ville,
 *       le propriétaire de l'échoppe reverse {@code taux %} du montant à la banque de la
 *       ville (Lands). Sur un rachat (l'échoppe achète), la transaction est refusée avant
 *       coup si le propriétaire ne peut pas payer le montant et le droit.</li>
 * </ul>
 */
final class ModuleQuickShop implements Listener {

    private final InfluLands plugin;
    private final Economy eco;
    private volatile List<Fiches.Echoppe> index = List.of();
    /**
     * Dernier stock connu de chaque échoppe (place libre pour un rachat), relevé quand sa zone est
     * chargée : InfluEchoppe ne lit pas un coffre déchargé. Sert à cacher les échoppes vides ou les
     * rachats pleins des listes publiques (fiches, marché) même loin de tout joueur. Gardé dans
     * stocks.yml d'un démarrage à l'autre.
     */
    private final Map<Long, Integer> stocks = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean stocksModifies;
    private boolean reindexPrevu;

    /** Retenues en attente de résumé : joueur -> ville -> {montant, nombre}. */
    private final Map<UUID, Map<String, double[]>> resumesProprio = new HashMap<>();
    private final Map<String, double[]> resumesVille = new HashMap<>();

    ModuleQuickShop(InfluLands plugin, Economy eco) {
        this.plugin = plugin;
        this.eco = eco;
    }

    List<Fiches.Echoppe> toutes() {
        return index;
    }

    /** Dernier stock connu (Integer.MAX_VALUE = illimitée), -1 si jamais relevé. */
    int stockConnu(long shopId) {
        return stocks.getOrDefault(shopId, -1);
    }

    void chargerStocks() {
        java.io.File f = new java.io.File(plugin.getDataFolder(), "stocks.yml");
        if (!f.exists()) return;
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        for (String k : y.getKeys(false)) {
            try {
                stocks.put(Long.parseLong(k), y.getInt(k));
            } catch (NumberFormatException ignored) {
                // clé illisible : ignorée
            }
        }
    }

    void sauvegarderStocks() {
        if (!stocksModifies) return;
        org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
        y.options().setHeader(List.of("Dernier stock connu de chaque échoppe InfluEchoppe (identifiant: stock), géré par InfluLands.",
                "Une échoppe à 0 (rachat : place libre à 0) est cachée des listes publiques. Ne pas modifier à la main."));
        for (Map.Entry<Long, Integer> e : stocks.entrySet()) y.set(String.valueOf(e.getKey()), e.getValue());
        try {
            y.save(new java.io.File(plugin.getDataFolder(), "stocks.yml"));
            stocksModifies = false;
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("stocks.yml non enregistré : " + e);
        }
    }

    private void noterStock(long shopId, int stock) {
        if (stock < 0) return;
        Integer avant = stocks.put(shopId, stock);
        if (avant == null || avant != stock) stocksModifies = true;
    }

    /** Recalcule l'index (fil principal : API InfluEchoppe et Lands). */
    void indexer() {
        List<Fiches.Echoppe> l = new ArrayList<>();
        java.util.Set<Long> vues = new java.util.HashSet<>();
        for (Shop shop : QuickShopAPI.getInstance().getShopManager().getAllShops()) {
            try {
                Location loc = shop.bukkitLocation();
                if (loc == null || loc.getWorld() == null) continue;
                Land land = plugin.villes().a(loc);
                Optional<UUID> owner = shop.getOwner().getUniqueIdIfRealPlayer();
                UUID proprio = owner.orElse(null);
                String proprioNom = proprio != null ? Villes.nomJoueur(proprio) : shop.getOwner().getDisplay();
                // Stock lu si la zone est chargée (et mémorisé), sinon le dernier connu.
                int stock = stock(shop, loc);
                vues.add(shop.getShopId());
                if (stock >= 0) noterStock(shop.getShopId(), stock);
                else stock = stockConnu(shop.getShopId());
                l.add(new Fiches.Echoppe(shop.getShopId(), loc.clone(), proprio, proprioNom,
                        Fiches.materiau(shop.getItem()), Fiches.nomObjet(shop.getItem()), shop.getItem().getAmount(),
                        shop.getPrice(), shop.isBuying(), stock,
                        land != null ? Villes.id(land) : "", land != null ? land.getName() : ""));
            } catch (RuntimeException e) {
                // échoppe corrompue ou monde absent : ignorée jusqu'au prochain passage
            }
        }
        index = l;
        // Échoppes supprimées : leur stock est oublié.
        if (stocks.keySet().removeIf(id -> !vues.contains(id))) stocksModifies = true;
    }

    /**
     * Après chaque transaction (zone forcément chargée) : stock relevé tout de suite, et index
     * recalculé une seconde plus tard, pour qu'une échoppe vidée disparaisse aussitôt des listes.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void stockApresTransaction(ShopSuccessPurchaseEvent e) {
        Shop shop = e.getShop();
        Location loc = shop.bukkitLocation();
        if (loc != null) noterStock(shop.getShopId(), stock(shop, loc));
        if (reindexPrevu) return;
        reindexPrevu = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            reindexPrevu = false;
            plugin.indexer();
        }, 20L);
    }

    /** Stock (vente) ou place libre (rachat), seulement si le chunk est chargé : -1 sinon. */
    private static int stock(Shop shop, Location loc) {
        World w = loc.getWorld();
        if (shop.isUnlimited()) return Integer.MAX_VALUE;
        if (w == null || !w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return -1;
        try {
            return shop.isBuying() ? shop.getRemainingSpace() : shop.getRemainingStock();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ── Ménage par la ville ─────────────────────────────────────────────────

    /**
     * InfluEchoppe ne laisse casser une échoppe qu'à son propriétaire : on accorde aussi le droit
     * « delete » à la ville (cf. {@link #peutNettoyer}). Couvre la casse du coffre et /echoppe remove.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void droitDeMenage(ShopPermissionCheckEvent e) {
        if (e.hasPermission() || !BuiltInShopPermission.DELETE.getRawNode().equals(e.permissionNode())) return;
        if (!QuickShopAPI.getPluginInstance().getName().equals(e.pluginNamespace())) return;
        Shop shop = e.shop().orElse(null);
        if (shop != null && peutNettoyer(shop, e.playerUUID())) e.hasPermission(true);
    }

    /**
     * Mode {@code echoppes.menage} : « maire » (le maire seulement), « droit-de-casser » (le maire
     * et quiconque a le drapeau Casser de Lands là où est l'échoppe) ou « non ». Le maire ne passe
     * pas outre un contrat : sur une parcelle louée ou vendue au propriétaire de l'échoppe, seul le
     * drapeau Casser compte (le maire retrouve la main une fois le contrat terminé).
     */
    private boolean peutNettoyer(Shop shop, UUID joueur) {
        String mode = plugin.getConfig().getString("echoppes.menage", "droit-de-casser");
        if (joueur == null || "non".equalsIgnoreCase(mode)) return false;
        Location loc = shop.bukkitLocation();
        Land land = plugin.villes().a(loc);
        if (land == null) return false;
        Area zone = land.getArea(loc);
        UUID proprio = shop.getOwner().getUniqueIdIfRealPlayer().orElse(null);
        boolean contrat = zone != null && proprio != null && proprio.equals(zone.getTenant());
        if (Villes.estMaire(land, joueur) && !contrat) return true;
        return "droit-de-casser".equalsIgnoreCase(mode) && zone != null && zone.hasRoleFlag(joueur, Flags.BLOCK_BREAK);
    }

    /** Après la casse d'une échoppe par la ville : prévient les deux joueurs et garde une trace. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void apresMenage(BlockBreakEvent e) {
        Shop shop = QuickShopAPI.getInstance().getShopManager().getShop(e.getBlock().getLocation(), true);
        if (shop == null) return;
        Player p = e.getPlayer();
        UUID proprio = shop.getOwner().getUniqueIdIfRealPlayer().orElse(null);
        if (proprio == null || proprio.equals(p.getUniqueId()) || !peutNettoyer(shop, p.getUniqueId())) return;
        Land land = plugin.villes().a(shop.bukkitLocation());
        String objet = Fiches.nomObjet(shop.getItem());
        String ville = land != null ? land.getName() : "?";
        plugin.getLogger().info(p.getName() + " a retiré l'échoppe " + shop.getShopId() + " (" + objet + ") de "
                + Villes.nomJoueur(proprio) + " à " + ville);
        Fmt.envoyer(p, "&#B376E5Tu as retiré l'échoppe de &f" + Villes.nomJoueur(proprio) + " &#B376E5(&f" + objet
                + "&#B376E5). Son contenu est tombé au sol.");
        Fmt.envoyer(Bukkit.getPlayer(proprio), "&#FF5C5CTon échoppe de &f" + objet + " &#FF5C5Cà &f" + ville
                + " &#FF5C5Ca été retirée par &f" + p.getName() + "&#FF5C5C.");
    }

    // ── Droit de place ──────────────────────────────────────────────────────

    /** Ville qui perçoit le droit pour cette échoppe, ou null (hors ville, illimitée, désactivé...). */
    private Land villeTaxatrice(Shop shop, UUID proprio) {
        if (!plugin.getConfig().getBoolean("droit-de-place.actif", true) || proprio == null) return null;
        if (shop.isUnlimited() && plugin.getConfig().getBoolean("droit-de-place.ignorer-echoppes-illimitees", true)) return null;
        Land land = plugin.villes().a(shop.bukkitLocation());
        if (!plugin.villes().percoit(land)) return null;
        if (plugin.getConfig().getBoolean("droit-de-place.exempter-maire", false) && Villes.estMaire(land, proprio)) return null;
        return land;
    }

    private double part(Land land, double montant) {
        double taux = plugin.registre().taux(Villes.id(land));
        return Math.floor(montant * taux) / 100.0;
    }

    /** Rachat : refuse d'avance si le propriétaire ne peut pas payer le joueur ET le droit de place. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void avantTransaction(ShopPurchaseEvent e) {
        Shop shop = e.getShop();
        if (!shop.isBuying()) return;
        UUID proprio = shop.getOwner().getUniqueIdIfRealPlayer().orElse(null);
        Land land = villeTaxatrice(shop, proprio);
        if (land == null) return;
        double droit = part(land, e.getTotal());
        if (droit <= 0) return;
        OfflinePlayer owner = Bukkit.getOfflinePlayer(proprio);
        if (eco.getBalance(owner) < e.getTotal() + droit) {
            e.setCancelled(true, Fmt.ligne(null, "&#FF5C5CLe propriétaire de cette échoppe ne peut pas payer ce rachat et le droit de place de "
                    + land.getName() + "."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void apresTransaction(ShopSuccessPurchaseEvent e) {
        Shop shop = e.getShop();
        UUID proprio = shop.getOwner().getUniqueIdIfRealPlayer().orElse(null);
        Land land = villeTaxatrice(shop, proprio);
        if (land == null) return;
        double droit = part(land, e.getBalanceWithoutTax());
        if (droit <= 0) return;
        OfflinePlayer owner = Bukkit.getOfflinePlayer(proprio);
        if (!eco.withdrawPlayer(owner, droit).transactionSuccess()) {
            plugin.getLogger().warning("Droit de place non prélevé : " + Villes.nomJoueur(proprio) + " n'a pas " + droit
                    + " (échoppe " + shop.getShopId() + ", ville " + land.getName() + ")");
            return;
        }
        if (!land.modifyBalance(droit)) {
            eco.depositPlayer(owner, droit); // banque refusée : on rend l'argent
            plugin.getLogger().warning("Banque de " + land.getName() + " refusée, droit de place rendu à " + Villes.nomJoueur(proprio));
            return;
        }
        String id = Villes.id(land);
        plugin.registre().encaisser(id, shop.getShopId(), droit);
        noter(proprio, id, droit);
        if (plugin.getConfig().getInt("droit-de-place.resume-secondes", 60) <= 0) envoyerResumes();
    }

    private void noter(UUID proprio, String ville, double droit) {
        double[] a = resumesProprio.computeIfAbsent(proprio, k -> new HashMap<>()).computeIfAbsent(ville, k -> new double[2]);
        a[0] += droit;
        a[1]++;
        double[] v = resumesVille.computeIfAbsent(ville, k -> new double[2]);
        v[0] += droit;
        v[1]++;
    }

    /** Messages regroupés : au propriétaire (perte) et au maire de la ville (gain). Convention docs/notifications/bitblock.md. */
    void envoyerResumes() {
        for (Map.Entry<UUID, Map<String, double[]>> e : resumesProprio.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            for (Map.Entry<String, double[]> v : e.getValue().entrySet()) {
                Land land = plugin.villes().parId(v.getKey());
                String nom = land != null ? land.getName() : "?";
                Fmt.envoyer(p, "&7%img_bitblock% &#B376E5Tu as perdu &f" + Fmt.argent(v.getValue()[0]) + " &#B376E5Bitblock &#FF5C5C▼&7 en droit de place à &f"
                        + nom + " &7(" + (long) v.getValue()[1] + " transaction" + (v.getValue()[1] > 1 ? "s" : "") + ") [ᴅʀᴏɪᴛ ᴅᴇ ᴘʟᴀᴄᴇ]");
            }
        }
        resumesProprio.clear();
        for (Map.Entry<String, double[]> v : resumesVille.entrySet()) {
            Land land = plugin.villes().parId(v.getKey());
            if (land == null) continue;
            Player maire = Bukkit.getPlayer(land.getOwnerUID());
            if (maire == null) continue;
            Fmt.envoyer(maire, "&7%img_bitblock% &#B376E5Ta ville &f" + land.getName() + " &#B376E5a gagné &f" + Fmt.argent(v.getValue()[0])
                    + " &#B376E5Bitblock &#2ECF7B▲&7 en droit de place (" + (long) v.getValue()[1] + " transaction"
                    + (v.getValue()[1] > 1 ? "s" : "") + ") [ᴅʀᴏɪᴛ ᴅᴇ ᴘʟᴀᴄᴇ]");
        }
        resumesVille.clear();
    }
}
