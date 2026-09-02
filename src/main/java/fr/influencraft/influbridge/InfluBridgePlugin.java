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

        // Clés de vote (Ordinaire/Phénomène) réclamées à la main via /votekeys claim
        // (bouton Vote de /menu, clic droit) plutôt que livrées automatiquement — cf.
        // pending_commands.requires_claim côté mineweb (poll.get.ts). Pas de cache
        // d'affichage : le clic droit va toujours chercher la liste en temps réel.
        if (!apiBaseUrl.isBlank() && !apiSecret.isBlank() && !apiSecret.equals("CHANGE_ME")) {
            getCommand("votekeys").setExecutor(
                    new VoteKeysClaimCommand(this, apiBaseUrl, apiSecret));
        } else {
            getLogger().warning(
                    "api-base-url / api-secret non configurés — /votekeys (récupération des "
                            + "clés de vote) est désactivé.");
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

        Bukkit.getPluginManager().registerEvents(new AnvilRestrictionListener(), this);

        // Neutralise l'enchantement vanilla Raccommodage (Mending) : retiré du résultat
        // d'enclume, du loot généré (coffres trésor / structures / bastions) et des prises
        // de pêche. Le livre continue de circuler, il devient juste inutile. Règle de
        // gameplay, enregistré inconditionnellement et silencieux (cf. AnvilRestrictionListener).
        // Voir MendingRestrictionListener pour les 2 voies écartées.
        Bukkit.getPluginManager().registerEvents(new MendingRestrictionListener(), this);

        // Guard packet AE/eco : no-op tant qu'eco n'a pas injecté "eco_packets" pour un joueur
        // donné (cf. EcoDisplayGuardListener), donc sûr à enregistrer inconditionnellement.
        Bukkit.getPluginManager().registerEvents(new EcoDisplayGuardListener(), this);

        if (getConfig().getBoolean("job-xp-bossbar.enabled", true)) {
            if (Bukkit.getPluginManager().isPluginEnabled("EcoJobs")) {
                long hideAfterSeconds = getConfig().getLong("job-xp-bossbar.hide-after-seconds", 4);
                String color = getConfig().getString("job-xp-bossbar.color", "GREEN");
                String overlay = getConfig().getString("job-xp-bossbar.overlay", "NOTCHED_20");
                JobXpBossBarListener bossBarListener =
                        new JobXpBossBarListener(this, hideAfterSeconds, color, overlay);
                Bukkit.getPluginManager().registerEvents(bossBarListener, this);

                // Bouton "Notif. XP métier" de /reglages (DeluxeMenus) : clic gauche
                // "[player] jobxpbar on", clic droit "[player] jobxpbar off" ("toggle" reste
                // accepté sans argument, pour compat avec un éventuel appel externe).
                getCommand("jobxpbar").setExecutor((sender, command, label, args) -> {
                    if (!(sender instanceof org.bukkit.entity.Player player)) {
                        sender.sendMessage("Cette commande est réservée aux joueurs.");
                        return true;
                    }
                    boolean newState;
                    if (args.length == 0 || args[0].equalsIgnoreCase("toggle")) {
                        newState = !bossBarListener.isEnabled(player);
                    } else if (args[0].equalsIgnoreCase("on")) {
                        newState = true;
                    } else if (args[0].equalsIgnoreCase("off")) {
                        newState = false;
                    } else {
                        return false;
                    }
                    bossBarListener.setEnabled(player, newState);
                    String message = newState
                            ? "&#FFD94D&lRéglages &8► &#B376E5Notif. XP métier &#2ECF7Bactivée."
                            : "&#FFD94D&lRéglages &8► &#B376E5Notif. XP métier &#FF5C5Cdésactivée.";
                    player.sendMessage(com.willfp.eco.util.StringUtils.format(message, player));
                    return true;
                });
            } else {
                getLogger().warning(
                        "EcoJobs absent — la boss bar de progression d'XP de métier est désactivée.");
            }
        }

        if (Bukkit.getPluginManager().isPluginEnabled("EcoJobs")) {
            JobChoiceCommand jobChoiceCommand = new JobChoiceCommand();
            getCommand("metier").setExecutor(jobChoiceCommand);
            getCommand("metier").setTabCompleter(jobChoiceCommand);
            Bukkit.getPluginManager().registerEvents(
                    new NativeJobsMenuRedirectListener(this), this);
        } else {
            getLogger().warning(
                    "EcoJobs absent — les commandes de métiers (/metier) sont désactivées.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new InfluBridgeExpansion(this, streakCacheService, votePartyService).register();
        }

        if (getConfig().getBoolean("money-gain-actionbar.enabled", true)) {
            if (Bukkit.getPluginManager().isPluginEnabled("CMI")) {
                double hideAfterSeconds = getConfig().getDouble("money-gain-actionbar.hide-after-seconds", 1.5);
                String color = getConfig().getString("money-gain-actionbar.color", "&#B376E5");

                // DurabilityNotifierSpigot affiche lui aussi ses alertes de casse d'outil en
                // action bar (même déclencheur : miner) — on lit son propre config.yml plutôt
                // que de dupliquer son seuil en dur, pour rester synchro s'il change.
                boolean durabilityGuardEnabled = false;
                int durabilityThreshold = 30;
                if (Bukkit.getPluginManager().isPluginEnabled("DurabilityNotifier")) {
                    java.io.File durabilityConfigFile = new java.io.File(
                            getDataFolder().getParentFile(), "DurabilityNotifier/config.yml");
                    if (durabilityConfigFile.isFile()) {
                        org.bukkit.configuration.file.YamlConfiguration durabilityConfig =
                                org.bukkit.configuration.file.YamlConfiguration
                                        .loadConfiguration(durabilityConfigFile);
                        durabilityGuardEnabled = durabilityConfig
                                .getBoolean("notifications.types.action-bar.enabled", true);
                        durabilityThreshold = durabilityConfig
                                .getInt("notifications.required-durability", 30);
                    }
                }

                MoneyGainActionBarListener moneyGainActionBarListener = new MoneyGainActionBarListener(
                        this, hideAfterSeconds, color, durabilityGuardEnabled, durabilityThreshold);
                Bukkit.getPluginManager().registerEvents(moneyGainActionBarListener, this);

                // Bouton "Notif. gain BitBlock" de /reglages (DeluxeMenus) : clic gauche
                // "[player] bitblocknotif on", clic droit "[player] bitblocknotif off".
                getCommand("bitblocknotif").setExecutor((sender, command, label, args) -> {
                    if (!(sender instanceof org.bukkit.entity.Player player)) {
                        sender.sendMessage("Cette commande est réservée aux joueurs.");
                        return true;
                    }
                    if (args.length != 1
                            || !(args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
                        return false;
                    }
                    boolean newState = args[0].equalsIgnoreCase("on");
                    moneyGainActionBarListener.setEnabled(player, newState);
                    String message = newState
                            ? "&#FFD94D&lRéglages &8► &#B376E5Notif. gain %img_bitblock% &#2ECF7Bactivées."
                            : "&#FFD94D&lRéglages &8► &#B376E5Notif. gain %img_bitblock% &#FF5C5Cdésactivées.";
                    player.sendMessage(com.willfp.eco.util.StringUtils.format(message, player));
                    return true;
                });
            } else {
                getLogger().warning(
                        "CMI absent — l'action bar de gain d'argent cumulatif est désactivée.");
            }
        }

        // Contourne le race condition de démarrage eco/CMIEInjector (cf. LateVaultEconomyBinder) :
        // ServerLoadEvent est garanti de se déclencher après le onEnable de tous les plugins,
        // donc après que CMIEInjector ait fini de hooker CMIEconomy dans Vault.
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onServerLoad(org.bukkit.event.server.ServerLoadEvent event) {
                LateVaultEconomyBinder.bindIfNeeded();
            }
        }, this);

        if (getConfig().getBoolean("fondeur-fonte-xp.enabled", true)) {
            if (Bukkit.getPluginManager().isPluginEnabled("EcoJobs")
                    && Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments")) {
                double xpPerOre = getConfig().getDouble("fondeur-fonte-xp.xp-per-ore", 0.2);
                Bukkit.getPluginManager().registerEvents(new FondeurFonteListener(xpPerOre), this);
            } else {
                getLogger().warning(
                        "EcoJobs et/ou AdvancedEnchantments absent — l'XP fondeur pour l'enchant "
                                + "\"fonte\" est désactivée.");
            }
        }
    }
}
