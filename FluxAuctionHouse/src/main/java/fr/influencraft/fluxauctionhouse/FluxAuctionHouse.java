package fr.influencraft.fluxauctionhouse;

import fr.influencraft.flux.api.economy.Currency;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * FluxAuctionHouse : marché unifié d'Influencraft. Interface (menus Flux) de l'hôtel des ventes
 * AuctionHouse, réunie avec les échoppes des villes (FluxChestShop, FluxLands) dans un seul
 * catalogue avec une seule recherche ; historique des ventes et prix moyen
 * ({@code /ah history}, {@code /ah average}). Voir docs/technique/influmarche.md.
 */
public final class FluxAuctionHouse extends JavaPlugin implements Listener {

    private Currency eco;
    private Hdv hdv;
    private Echoppes echoppes;
    private Catalogue catalogue;
    private Historique historique;
    private Vues vues;
    private Commande commande;
    private Interception interception;

    @Override
    public void onLoad() {
        fr.influencraft.flux.api.util.DataFolders.migrate(this, "InfluMarche");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // the main currency of the suite (Flux, currencies.main)
        eco = fr.influencraft.flux.api.economy.Economy.main();
        if (eco == null) {
            getLogger().severe("No currency available in Flux (Vault missing?): FluxAuctionHouse disabled.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        try {
            historique = new Historique(this, getDataFolder(), getLogger());
        } catch (java.sql.SQLException e) {
            getLogger().severe("Historique (SQLite) indisponible : " + e + ". FluxAuctionHouse désactivé.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        Objets.charger(this);
        hdv = new Hdv(this, eco);
        catalogue = new Catalogue(this);
        vues = new Vues(this);
        commande = new Commande(this);
        interception = new Interception(this);

        if (fr.influencraft.flux.api.shops.ChestShops.available()) {
            echoppes = new Echoppes(this);
            Bukkit.getPluginManager().registerEvents(echoppes, this);
            getLogger().info("Chest shops (" + fr.influencraft.flux.api.shops.ChestShops.providerName() + ") join the market.");
        } else {
            getLogger().info("No chest shop plugin in Flux: market limited to the auction house.");
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(interception, this);
        fr.influencraft.flux.api.command.Commands.create(this, "fluxauctionhouse").aliases("influmarche")
                .description("Market menus (buttons).").bukkit(commande, commande).register();
        fr.influencraft.flux.api.command.Commands.create(this, "market").aliases("hdv", "marche")
                .description("Market and auction house.").bukkit(commande, commande).register();
        // %influmarche_*%: resolved by Flux in menus, exposed to PlaceholderAPI by Flux
        fr.influencraft.flux.api.FluxApi.registerPlaceholders(this, "influmarche", new Expansion(this)::onRequest);
        // the market menus live in plugins/FluxAuctionHouse/menus/
        fr.influencraft.flux.api.util.Resources.shipMenus(this, fr.influencraft.flux.api.FluxApi.language());
        Sources.inscrire(this);
        planifier();
    }

    private void planifier() {
        Bukkit.getScheduler().cancelTasks(this);
        long scan = Math.max(5, getConfig().getInt("encheres-scan-secondes", 30)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, hdv::enregistrerEncheresTerminees, 200L, scan);
    }

    @Override
    public void onDisable() {
        if (Bukkit.getPluginManager().isPluginEnabled("Flux")) {
            try {
                Sources.retirer();
            } catch (LinkageError ignored) {
                // Flux déjà déchargé
            }
        }
        if (historique != null) historique.fermer();
    }

    void recharger() {
        reloadConfig();
        interception.recharger();
        catalogue.invalider();
        planifier();
    }

    /** Ouvre un menu Flux (clé de config {@code menus.*} ou identifiant direct). */
    void ouvrirMenu(Player p, String cleOuId) {
        if (cleOuId == null || cleOuId.isEmpty() || !p.isOnline()) return;
        String id = getConfig().getString("menus." + cleOuId, cleOuId);
        if (!fr.influencraft.flux.api.FluxApi.open(p, id)) getLogger().warning("Menu « " + id + " » not opened (Flux missing or unknown menu)");
    }

    @EventHandler
    public void quitter(PlayerQuitEvent e) {
        vues.oublier(e.getPlayer().getUniqueId());
    }

    Currency economie() {
        return eco;
    }

    Hdv hdv() {
        return hdv;
    }

    Echoppes echoppes() {
        return echoppes;
    }

    Catalogue catalogue() {
        return catalogue;
    }

    Historique historique() {
        return historique;
    }

    Vues vues() {
        return vues;
    }

    Commande commande() {
        return commande;
    }
}
