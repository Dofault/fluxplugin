package fr.influencraft.influmarche;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * InfluMarche : marché unifié d'Influencraft. Interface (menus FluxMenu) de l'hôtel des ventes
 * AuctionHouse, réunie avec les échoppes des villes (InfluEchoppe, InfluLands) dans un seul
 * catalogue avec une seule recherche ; historique des ventes et prix moyen
 * ({@code /ah history}, {@code /ah average}). Voir docs/technique/influmarche.md.
 */
public final class InfluMarche extends JavaPlugin implements Listener {

    private Economy eco;
    private Hdv hdv;
    private Echoppes echoppes;
    private Catalogue catalogue;
    private Historique historique;
    private Vues vues;
    private Commande commande;
    private Interception interception;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().severe("Aucune économie Vault : InfluMarche désactivé.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        eco = rsp.getProvider();
        try {
            historique = new Historique(getDataFolder(), getLogger());
        } catch (java.sql.SQLException e) {
            getLogger().severe("Historique (SQLite) indisponible : " + e + ". InfluMarche désactivé.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        Objets.charger(this);
        hdv = new Hdv(this, eco);
        catalogue = new Catalogue(this);
        vues = new Vues(this);
        commande = new Commande(this);
        interception = new Interception(this);

        if (Bukkit.getPluginManager().isPluginEnabled("InfluEchoppe")) {
            echoppes = new Echoppes(this);
            Bukkit.getPluginManager().registerEvents(echoppes, this);
            getLogger().info("InfluEchoppe trouvé : les échoppes entrent dans le marché.");
        } else {
            getLogger().info("InfluEchoppe absent : marché limité à l'hôtel des ventes.");
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(interception, this);
        for (String c : new String[]{"influmarche", "hdv"}) {
            var pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(commande);
                pc.setTabCompleter(commande);
            }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) new Expansion(this).register();
        else getLogger().warning("PlaceholderAPI absent : les menus ne pourront rien afficher.");
        if (Bukkit.getPluginManager().isPluginEnabled("FluxMenu")) Sources.inscrire(this);
        else getLogger().warning("FluxMenu absent : pas de menus du marché.");
        planifier();
    }

    private void planifier() {
        Bukkit.getScheduler().cancelTasks(this);
        long scan = Math.max(5, getConfig().getInt("encheres-scan-secondes", 30)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, hdv::enregistrerEncheresTerminees, 200L, scan);
    }

    @Override
    public void onDisable() {
        if (Bukkit.getPluginManager().isPluginEnabled("FluxMenu")) {
            try {
                Sources.retirer();
            } catch (LinkageError ignored) {
                // FluxMenu déjà déchargé
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

    /** Ouvre un menu FluxMenu (clé de config {@code menus.*} ou identifiant direct). */
    void ouvrirMenu(Player p, String cleOuId) {
        if (cleOuId == null || cleOuId.isEmpty() || !p.isOnline()) return;
        String id = getConfig().getString("menus." + cleOuId, cleOuId);
        if (Bukkit.getPluginManager().isPluginEnabled("FluxMenu")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fluxmenu open " + id + " " + p.getName());
        } else {
            getLogger().warning("FluxMenu absent : menu « " + id + " » non ouvert");
        }
    }

    @EventHandler
    public void quitter(PlayerQuitEvent e) {
        vues.oublier(e.getPlayer().getUniqueId());
    }

    Economy economie() {
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
