package fr.influencraft.influbridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class InfluBridgePlugin extends JavaPlugin {

    // Services rechargeables à chaud par /influbridge reload.
    private VoteReminderService voteReminderService;
    private VotePartyService votePartyService;

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
        votePartyService = new VotePartyService(this, apiBaseUrl, apiSecret);

        // Toutes les récompenses de vote (clés, flamme, top 3, objectif, tombola, Vote
        // Party) se réclament à la main via /voteclaims (alias /votekeys ; bouton Vote
        // de /menu, clic droit) plutôt que livrées automatiquement : cf.
        // pending_commands.requires_claim côté mineweb. Pas de cache d'affichage :
        // la liste est toujours lue en temps réel.
        if (!apiBaseUrl.isBlank() && !apiSecret.isBlank() && !apiSecret.equals("CHANGE_ME")) {
            getCommand("voteclaims").setExecutor(
                    new VoteClaimsCommand(this, votePartyService, apiBaseUrl, apiSecret));

            // Rappel de vote périodique : n'est envoyé qu'aux joueurs ayant au moins
            // un site de vote disponible (les récompenses en attente ne déclenchent
            // rien par elles-mêmes, cf. VoteReminderService). Le service est toujours
            // construit, même interrupteur global à false : start() ne lance alors
            // aucune tâche, mais /influbridge reload peut l'allumer sans redémarrage.
            voteReminderService = new VoteReminderService(this, votePartyService, apiBaseUrl, apiSecret);
            voteReminderService.start();

            // Bouton "Notif. de vote" de /reglages (DeluxeMenus) : clic gauche
                // "[player] votenotif on", clic droit "[player] votenotif off".
            getCommand("votenotif").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage("Cette commande est réservée aux joueurs.");
                    return true;
                }
                if (args.length != 1
                        || !(args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
                    return false;
                }
                boolean newState = args[0].equalsIgnoreCase("on");
                voteReminderService.setEnabled(player, newState);
                String message = newState
                        ? "&#FFD94D&lRéglages &8► &#B376E5Notif. de vote &#2ECF7Bactivées."
                        : "&#FFD94D&lRéglages &8► &#B376E5Notif. de vote &#FF5C5Cdésactivées.";
                player.sendMessage(com.willfp.eco.util.StringUtils.format(message, player));
                return true;
            });
        } else {
            getLogger().warning(
                    "api-base-url / api-secret non configurés — /voteclaims (récupération des "
                            + "récompenses de vote) est désactivé.");
        }

        // Vote Party (service construit plus haut, avant /voteclaims et le rappel de vote).
        getCommand("influvoteparty").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("influbridge.voteparty.admin")) {
                sender.sendMessage(org.bukkit.ChatColor.RED + "Tu n'as pas la permission d'effectuer cette action.");
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
                    sender.sendMessage(org.bukkit.ChatColor.RED + "Montant invalide : " + args[1]);
                    return true;
                }
                if (amount <= 0) {
                    sender.sendMessage(org.bukkit.ChatColor.RED + "Le montant doit être supérieur à 0.");
                    return true;
                }

                if (args[0].equalsIgnoreCase("add")) {
                    votePartyService.addVote(amount);
                } else {
                    votePartyService.removeVote(amount);
                }
                sender.sendMessage(org.bukkit.ChatColor.GREEN + "Compteur Vote Party : "
                        + votePartyService.getCurrent() + "/" + votePartyService.getVotesRequired());
                return true;
            }

            return false;
        });

        Bukkit.getPluginManager().registerEvents(new AnvilRestrictionListener(), this);

        // /repair : menu de réparation maison, en remplacement de l'alias CMI du même nom
        // (désactivé dans CMI/Settings/Alias.yml pour que celui-ci gagne la commande).
        // Prix unique lu à chaud dans repair.cost, durabilité + coût d'enclume remis à
        // neuf. Voir RepairMenu.
        if (getConfig().getBoolean("repair.enabled", true)) {
            getCommand("repair").setExecutor(new RepairMenu(this));
        }

        // Fioles d'expérience : /bottlexp stocke l'XP par tranches de 1395 points (niveaux
        // 0 → 30) dans des bouteilles vanilla marquées en PDC, rendues au clic droit sans
        // lancer. Voir BottleXpService.
        BottleXpService bottleXpService = new BottleXpService(this);
        getCommand("bottlexp").setExecutor(bottleXpService);
        getCommand("bottlexp").setTabCompleter(bottleXpService);
        Bukkit.getPluginManager().registerEvents(bottleXpService, this);

        // Neutralise l'enchantement vanilla Raccommodage (Mending) : retiré du résultat
        // d'enclume, du loot généré (coffres trésor / structures / bastions) et des prises
        // de pêche. Le livre continue de circuler, il devient juste inutile. Règle de
        // gameplay : raccommodage.enabled reste à true en production, l'interrupteur
        // existe pour les tests sur Labs. raccommodage.log active une trace console à
        // chaque neutralisation. Voir MendingRestrictionListener pour les 2 voies écartées.
        if (getConfig().getBoolean("raccommodage.enabled", true)) {
            Bukkit.getPluginManager().registerEvents(
                    new MendingRestrictionListener(
                            getLogger(), getConfig().getBoolean("raccommodage.log", false)),
                    this);
        } else {
            getLogger().warning("raccommodage.enabled = false : l'enchantement Raccommodage"
                    + " n'est plus neutralisé, il redevient applicable normalement.");
        }

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

        // /voteinfo : le bloc d'infos de vote à la demande, avec les vrais chiffres.
        // C'est la cible de l'alias "vote" de commands.yml, qui pointait avant sur la
        // fiche statique CMI (cmi ctext vote) — même gabarit, mais un seul texte à
        // maintenir (vote-reminder.message) et des valeurs réelles.
        getCommand("voteinfo").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage("Cette commande est réservée aux joueurs.");
                return true;
            }
            if (voteReminderService == null) {
                player.sendMessage(com.willfp.eco.util.StringUtils.format(
                        "&#FFD94D&lVote &8► &#B376E5Tu peux voter sur "
                                + "&#2BC4E8&nhttps://influencraft.fr/vote", player));
                return true;
            }
            voteReminderService.showTo(player);
            return true;
        });

        // /influbridge reload : relit config.yml et réapplique ce qui peut l'être à
        // chaud. Recharger les CLASSES d'un plugin Java n'est pas faisable proprement
        // — la commande le dit donc explicitement plutôt que de laisser croire qu'un
        // nouveau jar serait pris en compte.
        getCommand("influbridge").setExecutor((sender, command, label, args) -> {
            if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
                return false;
            }
            if (!sender.hasPermission("influbridge.admin")) {
                sender.sendMessage(org.bukkit.ChatColor.RED
                        + "Tu n'as pas la permission d'effectuer cette action.");
                return true;
            }

            reloadConfig();
            votePartyService.reloadConfig();

            StringBuilder report = new StringBuilder();
            report.append(org.bukkit.ChatColor.GREEN)
                    .append("InfluBridge : config.yml rechargé.")
                    .append('\n')
                    .append(org.bukkit.ChatColor.GRAY)
                    .append("  Vote Party : seuil ")
                    .append(votePartyService.getVotesRequired())
                    .append(" (compteur ")
                    .append(votePartyService.getCurrent())
                    .append(" inchangé)");

            if (voteReminderService != null) {
                voteReminderService.reload();
                report.append('\n')
                        .append(org.bukkit.ChatColor.GRAY)
                        .append("  Rappel de vote : ")
                        .append(voteReminderService.isGloballyEnabled()
                                ? "actif, toutes les " + voteReminderService.getIntervalSeconds() + "s"
                                : "désactivé");
            }

            if (getConfig().getBoolean("repair.enabled", true)) {
                report.append('\n')
                        .append(org.bukkit.ChatColor.GRAY)
                        .append("  Réparation : ")
                        .append(getConfig().getDouble("repair.cost", 500.0))
                        .append(" BitBlock par objet");
            }

            report.append('\n')
                    .append(org.bukkit.ChatColor.YELLOW)
                    .append("  Demandent encore un redémarrage : intervalles de poll (site, streak), ")
                    .append("boss bar XP métier, action bar BitBlock, raccommodage, ")
                    .append("touche des succès — et tout nouveau jar du plugin.");

            sender.sendMessage(report.toString());
            return true;
        });
    }
}
