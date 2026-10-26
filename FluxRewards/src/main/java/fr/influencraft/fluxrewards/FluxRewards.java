package fr.influencraft.fluxrewards;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;

/**
 * FluxRewards : récompense de connexion quotidienne et paliers de temps de jeu, récupérés
 * depuis le menu Flux (page « Récompenses »). Les commandes servent surtout aux boutons.
 *
 * <pre>
 * /recompenses jour            récupère la récompense du jour
 * /recompenses temps [n]       récupère le palier de temps n (ou tous ceux disponibles)
 * /recompenses admin jour &lt;joueur&gt; &lt;n&gt;   place un joueur au jour n (test)
 * /recompenses admin reset &lt;joueur&gt;
 * /recompenses admin reload
 * </pre>
 */
public final class FluxRewards extends JavaPlugin implements Listener, TabExecutor {

    private ServiceRecompenses service;

    @Override
    public void onLoad() {
        LegacyDataFolder.migrate(this, "InfluRecompenses");
    }

    @Override
    public void onEnable() {
        service = new ServiceRecompenses(this);
        service.charger();
        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("recompenses");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new ExpansionRecompenses(this, service).register();
        }
        // Joueurs déjà connectés (rechargement) et passage de minuit pour ceux qui restent en ligne
        for (Player p : Bukkit.getOnlinePlayers()) arriver(p);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (service.compterJour(p)) annoncerJour(p);
            }
        }, 1200L, 1200L);
    }

    @Override
    public void onDisable() {
        if (service != null) service.sauvegarderTout();
    }

    private void arriver(Player p) {
        service.charger(p.getUniqueId());
        service.compterJour(p);
        service.tempsJoue(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        arriver(p);
        // Rappel quelques secondes après l'arrivée, si quelque chose attend
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && service.aRecuperer(p.getUniqueId()) > 0) {
                service.message(p, "rappel", Map.of("nombre", String.valueOf(service.aRecuperer(p.getUniqueId()))));
            }
        }, 100L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        service.tempsJoue(e.getPlayer().getUniqueId());
        service.decharger(e.getPlayer().getUniqueId());
    }

    private void annoncerJour(Player p) {
        service.message(p, "nouveau-jour", Map.of());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            admin(sender, args);
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Commande réservée aux joueurs.");
            return true;
        }
        String sous = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sous) {
            case "jour" -> service.recupererJour(p);
            case "temps" -> service.recupererTemps(p, args.length > 1 ? entier(args[1]) : 0);
            default -> service.message(p, "aide", Map.of());
        }
        return true;
    }

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("influrecompenses.admin")) {
            sender.sendMessage("§cPermission refusée.");
            return;
        }
        String sous = args.length > 1 ? args[1].toLowerCase() : "";
        if (sous.equals("reload")) {
            service.charger();
            sender.sendMessage("§aFluxRewards rechargé.");
            return;
        }
        Player cible = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : null;
        if (cible == null) {
            sender.sendMessage("§cUsage : /recompenses admin <jour <joueur> <n>|reset <joueur>|reload> (joueur connecté)");
            return;
        }
        switch (sous) {
            case "jour" -> {
                int n = args.length > 3 ? entier(args[3]) : -1;
                if (n < 1) {
                    sender.sendMessage("§cUsage : /recompenses admin jour <joueur> <n>");
                    return;
                }
                service.definirJour(cible, n);
                sender.sendMessage("§a" + cible.getName() + " est au jour " + n + " du cycle.");
            }
            case "reset" -> {
                service.reinitialiser(cible);
                sender.sendMessage("§aRécompenses de " + cible.getName() + " réinitialisées.");
            }
            default -> sender.sendMessage("§cUsage : /recompenses admin <jour|reset|reload>");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> l = sender.hasPermission("influrecompenses.admin")
                    ? List.of("jour", "temps", "admin") : List.of("jour", "temps");
            return l.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return List.of("jour", "reset", "reload").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        return null;
    }

    private static int entier(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
