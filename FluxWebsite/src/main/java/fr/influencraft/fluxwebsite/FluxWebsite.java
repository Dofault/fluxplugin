package fr.influencraft.fluxwebsite;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bridge between the website (mineweb) and the game server: delivers pending commands (shop purchases),
 * vote rewards claimed with /voteclaims, the Vote Party counter, the periodic vote reminder and the vote
 * streak placeholders. Formerly InfluBridge, whose game-only features moved to FluxTools modules.
 */
public final class FluxWebsite extends JavaPlugin {

    // services reloaded live by /influbridge reload
    private VoteReminderService voteReminderService;
    private VotePartyService votePartyService;

    @Override
    public void onLoad() {
        LegacyDataFolder.migrate(this, "InfluBridge");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String apiBaseUrl = getConfig().getString("api-base-url", "");
        String apiSecret = getConfig().getString("api-secret", "");
        boolean apiReady = !apiBaseUrl.isBlank() && !apiSecret.isBlank() && !apiSecret.equals("CHANGE_ME");

        if (apiReady) {
            CommandDeliveryService delivery = new CommandDeliveryService(this, apiBaseUrl, apiSecret);
            long period = getConfig().getLong("poll-interval-seconds", 30) * 20L;
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, delivery::poll, period, period);
        } else {
            getLogger().warning("api-base-url / api-secret not set in config.yml: command delivery is disabled.");
        }

        StreakCacheService streakCache = null;
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI") && !apiBaseUrl.isBlank()) {
            streakCache = new StreakCacheService(this, apiBaseUrl);
            long period = getConfig().getLong("streak-cache-interval-seconds", 30) * 20L;
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, streakCache::refresh, period, period);
        } else {
            getLogger().warning("PlaceholderAPI missing or api-base-url not set: %influbridge_flame_*% placeholders are disabled.");
        }

        // The website sends "influvoteparty add" from the console for every confirmed vote;
        // the counter persists in voteparty.yml.
        votePartyService = new VotePartyService(this, apiBaseUrl, apiSecret);

        // Every vote reward (keys, streak bonus, monthly top 3, goal, raffle, Vote Party) waits on the website
        // (pending_commands.requires_claim) until the player claims it with /voteclaims.
        if (apiReady) {
            getCommand("voteclaims").setExecutor(new VoteClaimsCommand(this, votePartyService, apiBaseUrl, apiSecret));

            // Always built, even when switched off in the config, so that /influbridge reload can start it.
            voteReminderService = new VoteReminderService(this, votePartyService, apiBaseUrl, apiSecret);
            voteReminderService.start();

            // "Notif. de vote" button of the settings menu
            getCommand("votenotif").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Cette commande est réservée aux joueurs.");
                    return true;
                }
                if (args.length != 1 || !(args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
                    return false;
                }
                boolean state = args[0].equalsIgnoreCase("on");
                voteReminderService.setEnabled(player, state);
                String message = state
                        ? "&#FFD94D&lRéglages &8► &#B376E5Notif. de vote &#2ECF7Bactivées."
                        : "&#FFD94D&lRéglages &8► &#B376E5Notif. de vote &#FF5C5Cdésactivées.";
                player.sendMessage(com.willfp.eco.util.StringUtils.format(message, player));
                return true;
            });
        } else {
            getLogger().warning("api-base-url / api-secret not set: /voteclaims is disabled.");
        }

        getCommand("influvoteparty").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("influbridge.voteparty.admin")) {
                sender.sendMessage(ChatColor.RED + "Tu n'as pas la permission d'effectuer cette action.");
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("add")) {
                votePartyService.addVote();
                return true;
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
                int amount;
                try {
                    amount = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Montant invalide : " + args[1]);
                    return true;
                }
                if (amount <= 0) {
                    sender.sendMessage(ChatColor.RED + "Le montant doit être supérieur à 0.");
                    return true;
                }
                if (args[0].equalsIgnoreCase("add")) {
                    votePartyService.addVote(amount);
                } else {
                    votePartyService.removeVote(amount);
                }
                sender.sendMessage(ChatColor.GREEN + "Compteur Vote Party : "
                        + votePartyService.getCurrent() + "/" + votePartyService.getVotesRequired());
                return true;
            }
            return false;
        });

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new WebsiteExpansion(streakCache, votePartyService).register();
        }

        // /voteinfo: the vote info block on demand, with live numbers (target of the /vote alias in commands.yml)
        getCommand("voteinfo").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Cette commande est réservée aux joueurs.");
                return true;
            }
            if (voteReminderService == null) {
                player.sendMessage(com.willfp.eco.util.StringUtils.format(
                        "&#FFD94D&lVote &8► &#B376E5Tu peux voter sur &#2BC4E8&nhttps://influencraft.fr/vote", player));
                return true;
            }
            voteReminderService.showTo(player);
            return true;
        });

        // /influbridge reload: re-reads config.yml and applies what can be applied live (classes cannot be reloaded)
        getCommand("influbridge").setExecutor((sender, command, label, args) -> {
            if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
                return false;
            }
            if (!sender.hasPermission("influbridge.admin")) {
                sender.sendMessage(ChatColor.RED + "Tu n'as pas la permission d'effectuer cette action.");
                return true;
            }
            reloadConfig();
            votePartyService.reloadConfig();
            StringBuilder report = new StringBuilder()
                    .append(ChatColor.GREEN).append("FluxWebsite : config.yml rechargé.\n")
                    .append(ChatColor.GRAY).append("  Vote Party : seuil ").append(votePartyService.getVotesRequired())
                    .append(" (compteur ").append(votePartyService.getCurrent()).append(" inchangé)");
            if (voteReminderService != null) {
                voteReminderService.reload();
                report.append('\n').append(ChatColor.GRAY).append("  Rappel de vote : ")
                        .append(voteReminderService.isGloballyEnabled()
                                ? "actif, toutes les " + voteReminderService.getIntervalSeconds() + "s"
                                : "désactivé");
            }
            report.append('\n').append(ChatColor.YELLOW)
                    .append("  Demandent encore un redémarrage : intervalles de poll (site, streak) et tout nouveau jar.");
            sender.sendMessage(report.toString());
            return true;
        });
    }
}
