package fr.influencraft.influbridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class InfluBridgePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String apiBaseUrl = getConfig().getString("api-base-url", "");
        String apiSecret = getConfig().getString("api-secret", "");
        long pollIntervalSeconds = getConfig().getLong("poll-interval-seconds", 30);

        if (apiBaseUrl.isBlank() || apiSecret.isBlank() || apiSecret.equals("CHANGE_ME")) {
            getLogger().warning(
                    "api-base-url / api-secret non configurés dans config.yml — livraison des commandes désactivée.");
        } else {
            CommandDeliveryService commandDeliveryService =
                    new CommandDeliveryService(this, apiBaseUrl, apiSecret);
            long periodTicks = pollIntervalSeconds * 20L;
            Bukkit.getScheduler().runTaskTimerAsynchronously(
                    this, commandDeliveryService::poll, periodTicks, periodTicks);
        }

        String advancementKeyCommand = getConfig().getString("advancement-key-command", "menu");
        if (Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
            new AdvancementRedirectListener(this, advancementKeyCommand).register();
        } else {
            getLogger().warning(
                    "ProtocolLib absent — la redirection de la touche L (succès -> /" + advancementKeyCommand
                            + ") est désactivée.");
        }

        StreakCacheService streakCacheService = null;
        long streakCacheIntervalSeconds = getConfig().getLong("streak-cache-interval-seconds", 30);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI") && !apiBaseUrl.isBlank()) {
            streakCacheService = new StreakCacheService(this, apiBaseUrl);
            long streakPeriodTicks = streakCacheIntervalSeconds * 20L;
            Bukkit.getScheduler().runTaskTimerAsynchronously(
                    this, streakCacheService::refresh, streakPeriodTicks, streakPeriodTicks);
        } else {
            getLogger().warning(
                    "PlaceholderAPI absent ou api-base-url non configuré — les placeholders "
                            + "%influbridge_flame_*% (flamme de vote) sont désactivés.");
        }

        // Vote Party : la source réelle des votes est mineweb (commande console
        // "influvoteparty add" envoyée à chaque vote confirmé, cf. poll.get.ts).
        // Le compteur persiste dans voteparty.yml pour survivre à un redémarrage.
        VotePartyService votePartyService = new VotePartyService(this);
        if (Bukkit.getPluginManager().isPluginEnabled("VotingPlugin")) {
            Bukkit.getPluginManager().registerEvents(votePartyService, this);
        }
        getCommand("influvoteparty").setExecutor((sender, command, label, args) -> {
            if (args.length == 1 && args[0].equalsIgnoreCase("add")) {
                votePartyService.addVote();
                return true;
            }
            return false;
        });

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new InfluBridgeExpansion(this, streakCacheService, votePartyService).register();
        }

        Bukkit.getPluginManager().registerEvents(new AnvilRestrictionListener(), this);

        if (getConfig().getBoolean("job-xp-bossbar.enabled", true)) {
            if (Bukkit.getPluginManager().isPluginEnabled("EcoJobs")) {
                long hideAfterSeconds = getConfig().getLong("job-xp-bossbar.hide-after-seconds", 4);
                String color = getConfig().getString("job-xp-bossbar.color", "GREEN");
                String overlay = getConfig().getString("job-xp-bossbar.overlay", "NOTCHED_20");
                Bukkit.getPluginManager().registerEvents(
                        new JobXpBossBarListener(this, hideAfterSeconds, color, overlay), this);
            } else {
                getLogger().warning(
                        "EcoJobs absent — la boss bar de progression d'XP de métier est désactivée.");
            }
        }
    }
}
