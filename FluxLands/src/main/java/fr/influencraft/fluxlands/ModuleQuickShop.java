package fr.influencraft.fluxlands;

import fr.influencraft.flux.api.shops.ChestShop;
import fr.influencraft.flux.api.shops.ChestShopPurchaseEvent;
import fr.influencraft.flux.api.shops.ChestShopTradeEvent;
import fr.influencraft.flux.api.shops.ChestShops;
import me.angeschossen.lands.api.flags.type.Flags;
import me.angeschossen.lands.api.land.Area;
import me.angeschossen.lands.api.land.Land;
import fr.influencraft.flux.api.economy.Currency;
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
 * Lien avec FluxChestShop (chargé seulement s'il est présent) :
 * <ul>
 *   <li>index des échoppes et de la ville où se trouve chacune ;</li>
 *   <li>droit de place : à chaque transaction réussie d'une échoppe située dans une ville,
 *       le propriétaire de l'échoppe reverse {@code taux %} du montant à la banque de la
 *       ville (Lands). Sur un rachat (l'échoppe achète), la transaction est refusée avant
 *       coup si le propriétaire ne peut pas payer le montant et le droit.</li>
 * </ul>
 */
final class ModuleQuickShop implements Listener {

    private final FluxLands plugin;
    private final Currency eco;
    private volatile List<Fiches.Echoppe> index = List.of();
    /**
     * Dernier stock connu de chaque échoppe (place libre pour un rachat), relevé quand sa zone est
     * chargée : FluxChestShop ne lit pas un coffre déchargé. Sert à cacher les échoppes vides ou les
     * rachats pleins des listes publiques (fiches, marché) même loin de tout joueur. Gardé dans
     * stocks.yml d'un démarrage à l'autre.
     */
    private final Map<Long, Integer> stocks = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean stocksModifies;
    private boolean reindexPrevu;

    /** Retenues en attente de résumé : joueur -> ville -> {montant, nombre}. */
    private final Map<UUID, Map<String, double[]>> resumesProprio = new HashMap<>();
    private final Map<String, double[]> resumesVille = new HashMap<>();

    ModuleQuickShop(FluxLands plugin, Currency eco) {
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
        y.options().setHeader(List.of("Dernier stock connu de chaque échoppe FluxChestShop (identifiant: stock), géré par FluxLands.",
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

    /** Recalcule l'index (fil principal : API FluxChestShop et Lands). */
    void indexer() {
        List<Fiches.Echoppe> l = new ArrayList<>();
        java.util.Set<Long> vues = new java.util.HashSet<>();
        for (ChestShop shop : ChestShops.all()) {
            try {
                Location loc = shop.location();
                if (loc == null || loc.getWorld() == null) continue;
                Land land = plugin.villes().a(loc);
                UUID proprio = shop.owner();
                String proprioNom = proprio != null ? Villes.nomJoueur(proprio) : shop.ownerName();
                // stock read when the area is loaded (and remembered), else the last known one
                int stock = shop.stock();
                vues.add(shop.id());
                if (stock >= 0) noterStock(shop.id(), stock);
                else stock = stockConnu(shop.id());
                l.add(new Fiches.Echoppe(shop.id(), loc.clone(), proprio, proprioNom,
                        Fiches.materiau(shop.item()), Fiches.nomObjet(shop.item()), shop.item().getAmount(),
                        shop.price(), shop.buying(), stock,
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
    public void stockApresTransaction(ChestShopTradeEvent e) {
        noterStock(e.shop().id(), e.shop().stock());
        if (reindexPrevu) return;
        reindexPrevu = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            reindexPrevu = false;
            plugin.indexer();
        }, 20L);
    }

    // ── Ménage par la ville ─────────────────────────────────────────────────

    /** The shop plugin only lets the owner remove a shop: the town may too (see {@link #peutNettoyer}). */
    void enregistrerMenage() {
        ChestShops.allowRemoval(plugin, this::peutNettoyer);
    }

    /**
     * Mode {@code echoppes.menage} : « maire » (le maire seulement), « droit-de-casser » (le maire
     * et quiconque a le drapeau Casser de Lands là où est l'échoppe) ou « non ». Le maire ne passe
     * pas outre un contrat : sur une parcelle louée ou vendue au propriétaire de l'échoppe, seul le
     * drapeau Casser compte (le maire retrouve la main une fois le contrat terminé).
     */
    private boolean peutNettoyer(ChestShop shop, UUID joueur) {
        String mode = plugin.getConfig().getString("echoppes.menage", "droit-de-casser");
        if (joueur == null || "non".equalsIgnoreCase(mode)) return false;
        Location loc = shop.location();
        Land land = plugin.villes().a(loc);
        if (land == null) return false;
        Area zone = land.getArea(loc);
        UUID proprio = shop.owner();
        boolean contrat = zone != null && proprio != null && proprio.equals(zone.getTenant());
        if (Villes.estMaire(land, joueur) && !contrat) return true;
        return "droit-de-casser".equalsIgnoreCase(mode) && zone != null && zone.hasRoleFlag(joueur, Flags.BLOCK_BREAK);
    }

    /** Après la casse d'une échoppe par la ville : prévient les deux joueurs et garde une trace. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void apresMenage(BlockBreakEvent e) {
        ChestShop shop = ChestShops.at(e.getBlock().getLocation());
        if (shop == null) return;
        Player p = e.getPlayer();
        UUID proprio = shop.owner();
        if (proprio == null || proprio.equals(p.getUniqueId()) || !peutNettoyer(shop, p.getUniqueId())) return;
        Land land = plugin.villes().a(shop.location());
        String objet = Fiches.nomObjet(shop.item());
        String ville = land != null ? land.getName() : "?";
        plugin.getLogger().info(p.getName() + " a retiré l'échoppe " + shop.id() + " (" + objet + ") de "
                + Villes.nomJoueur(proprio) + " à " + ville);
        Fmt.envoyer(p, "&#B376E5Tu as retiré l'échoppe de &f" + Villes.nomJoueur(proprio) + " &#B376E5(&f" + objet
                + "&#B376E5). Son contenu est tombé au sol.");
        Fmt.envoyer(Bukkit.getPlayer(proprio), "&#FF5C5CTon échoppe de &f" + objet + " &#FF5C5Cà &f" + ville
                + " &#FF5C5Ca été retirée par &f" + p.getName() + "&#FF5C5C.");
    }

    // ── Droit de place ──────────────────────────────────────────────────────

    /** Ville qui perçoit le droit pour cette échoppe, ou null (hors ville, illimitée, désactivé...). */
    private Land villeTaxatrice(ChestShop shop) {
        UUID proprio = shop.owner();
        if (!plugin.getConfig().getBoolean("droit-de-place.actif", true) || proprio == null) return null;
        if (shop.unlimited() && plugin.getConfig().getBoolean("droit-de-place.ignorer-echoppes-illimitees", true)) return null;
        Land land = plugin.villes().a(shop.location());
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
    public void avantTransaction(ChestShopPurchaseEvent e) {
        ChestShop shop = e.shop();
        if (!shop.buying()) return;
        Land land = villeTaxatrice(shop);
        if (land == null) return;
        double droit = part(land, e.total());
        if (droit <= 0) return;
        OfflinePlayer owner = Bukkit.getOfflinePlayer(shop.owner());
        if (eco.balance(owner) < e.total() + droit) {
            e.refuse(Fmt.ligne(null, "&#FF5C5CLe propriétaire de cette échoppe ne peut pas payer ce rachat et le droit de place de "
                    + land.getName() + "."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void apresTransaction(ChestShopTradeEvent e) {
        ChestShop shop = e.shop();
        Land land = villeTaxatrice(shop);
        if (land == null) return;
        double droit = part(land, e.total());
        if (droit <= 0) return;
        UUID proprio = shop.owner();
        OfflinePlayer owner = Bukkit.getOfflinePlayer(proprio);
        if (!eco.take(owner, droit)) {
            plugin.getLogger().warning("Droit de place non prélevé : " + Villes.nomJoueur(proprio) + " n'a pas " + droit
                    + " (échoppe " + shop.id() + ", ville " + land.getName() + ")");
            return;
        }
        if (!land.modifyBalance(droit)) {
            eco.give(owner, droit); // banque refusée : on rend l'argent
            plugin.getLogger().warning("Banque de " + land.getName() + " refusée, droit de place rendu à " + Villes.nomJoueur(proprio));
            return;
        }
        String id = Villes.id(land);
        plugin.registre().encaisser(id, shop.id(), droit);
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
