package fr.influencraft.flux;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import fr.influencraft.flux.menu.Menu;
import fr.influencraft.flux.session.ChestBackgrounds;
import fr.influencraft.flux.session.InventoryHider;
import fr.influencraft.flux.session.MenuListener;
import fr.influencraft.flux.session.MenuService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Flux : menus maison d'Influencraft. Point clé par rapport à DeluxeMenus : l'inventaire
 * du joueur peut être masqué (et remplacé par des boutons) sans jamais être modifié côté
 * serveur. Voir docs/technique/flux.md.
 */
public final class Flux extends JavaPlugin implements fr.influencraft.flux.api.FluxApi.Backend, org.bukkit.event.Listener {

    private ItemFactory items;
    private MenuRegistry registry;
    private MenuService menus;
    private Actions actions;
    private PacketListenerCommon packetListener;
    private PacketListenerCommon backgroundListener;
    private com.github.retrooper.packetevents.event.PacketListenerCommon hurtShakeListener;
    private com.github.retrooper.packetevents.event.PacketListenerCommon sidebarListener;
    private PacketListenerCommon advancementListener;
    private fr.influencraft.flux.economy.MoneyActionBar moneyBar;
    private ChestBackgrounds backgrounds;
    private PapiBridge papiBridge;
    private int clickCooldown;
    private String openSound = "@page";
    private final CommandManager commandManager = new CommandManager();
    private String prefix;

    @Override
    public void onLoad() {
        LegacyDataFolder.migrate(this, "FluxMenu");
        // our money as Vault's economy before any plugin starts: a plugin looking Vault up in its own enable finds it
        try {
            if (fr.influencraft.flux.economy.CurrencyService.vaultInstalled() && fr.influencraft.flux.economy.CurrencyService.vaultCurrencyId(this) != null) {
                fr.influencraft.flux.economy.VaultProvider.register(this);
            }
        } catch (RuntimeException | LinkageError e) {
            getLogger().warning("Vault economy not registered early: " + e);
        }
        // the pack's text shader written before MC GUI merges it (see pack.ClientPack#early)
        try {
            clientPack = new fr.influencraft.flux.pack.ClientPack(this, getFile());
            clientPack.configure(getConfig().getConfigurationSection("client-pack"));
            fr.influencraft.flux.hud.HudService.earlyShaderPart(this);
            clientPack.early();
        } catch (RuntimeException | LinkageError e) {
            getLogger().warning("Client pack not written early: " + e);
        }
    }

    @Override
    public void onEnable() {
        // every class read from the jar now: a jar replaced while the server runs would otherwise make the first use
        // of a class fail (NoClassDefFoundError, e.g. HurtShake on the first hurt packet) and kick the player
        preloadClasses();
        saveDefaultConfig();
        File menusDir = new File(getDataFolder(), "menus");
        if (!menusDir.isDirectory()) saveResource("menus/main.yml", false);
        shipOwnMenu("rewards");
        shipOwnMenu("compte");
        shipOwnMenu("trade");
        shipOwnMenu("trade_sell");

        Text.init(getLogger());
        PathRenderer.init(getLogger());
        fr.influencraft.flux.menu.ListRenderer.init(getLogger());
        fr.influencraft.flux.api.command.Commands.init(this);
        // database of the suite: plugins/Flux/storage.yml (the database section of config.yml before 2026-10-31)
        fr.influencraft.flux.compat.StorageConfigCompat.move(this);
        fr.influencraft.flux.api.data.Database.start(this, fr.influencraft.flux.api.data.Database.loadSettings(this));
        fr.influencraft.flux.providers.Providers.start(this, getConfig().getConfigurationSection("providers"));
        fr.influencraft.flux.api.util.PlayerSettings.open(this);
        // money of the suite: Flux's own currencies, account history, gain / loss messages (configured by readSettings)
        currencies = new fr.influencraft.flux.economy.CurrencyService(this, () -> messages);
        items = new ItemFactory(this);
        actions = new Actions(this);
        menus = new MenuService(this, items);
        registry = new MenuRegistry(this);
        moneyBar = new fr.influencraft.flux.economy.MoneyActionBar(this, () -> messages);
        readSettings();
        currencies.start();
        rewards = fr.influencraft.flux.rewards.RewardService.start(this, () -> messages);
        // universes (plugins/Flux/universes.yml): separate profiles per group of worlds, items kept in their universe
        universes = fr.influencraft.flux.universe.UniverseService.start(this, () -> messages);
        // buying / selling window of one item (shop chests, chest shops): api.trade.TradeWindow
        trades = fr.influencraft.flux.trade.TradeService.start(this, () -> messages);
        if (rewards != null) rewards.importFormerMailbox(getDataFolder().getParentFile());
        leaderboards = new fr.influencraft.flux.leaderboard.LeaderboardService(this);
        leaderboards.start(getConfig().getConfigurationSection("leaderboards"));
        profile = new fr.influencraft.flux.profile.ProfileService(this);
        profile.start(getConfig().getString("profile.bust-model", "influmenu:buste_"));
        int warnings = registry.load();
        getLogger().info(registry.all().size() + " menu(s) chargé(s)" + (warnings > 0 ? ", " + warnings + " avertissement(s)" : ""));
        // menus read again once the custom items are loaded: themed menus pick their generated picture
        // (bg_auto_<menu>) only when ItemsAdder knows it, which is after Flux starts
        fr.influencraft.flux.api.items.CustomItems.onLoaded(this, () -> {
            PathRenderer.reset();
            int w = registry.load();
            getLogger().info(registry.all().size() + " menu(s) rechargé(s) après le chargement des objets personnalisés"
                    + (w > 0 ? ", " + w + " avertissement(s)" : ""));
        });

        getServer().getPluginManager().registerEvents(new MenuListener(this, menus), this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(commandManager, this);
        fr.influencraft.flux.items.ItemsCommand itemsCommand = new fr.influencraft.flux.items.ItemsCommand(() -> messages);
        fr.influencraft.flux.api.command.Commands.create(this, "fluxitems").description("Items of the suite: give, list, id.")
                .permission("flux.items.admin").suggest(false).executor(itemsCommand::run).completer(itemsCommand::complete).register();
        fr.influencraft.flux.api.command.Commands.create(this, "commands").aliases("commandes")
                .description("Liste des commandes").executor(commandManager::help).register();
        // running total of money gained in the action bar (money-actionbar), /moneynotif on|off
        getServer().getPluginManager().registerEvents(moneyBar, this);
        fr.influencraft.flux.api.command.Commands.create(this, "moneynotif").aliases("bitblocknotif")
                .description("Money gained in the action bar: on, off.").executor(moneyBar::command).register();
        // help window (/aide and the pause menu) and settings window (/parametres): plugins/Flux/help.yml, settings.yml
        help = new fr.influencraft.flux.help.HelpService(this);
        help.start();
        settings = new fr.influencraft.flux.settings.SettingsService(this);
        settings.start();
        // screens shown when a player joins (questionnaire, calendar...), one after another once the pack is loaded
        fr.influencraft.flux.welcome.JoinScreenService joinScreens = new fr.influencraft.flux.welcome.JoinScreenService(this);
        getServer().getPluginManager().registerEvents(joinScreens, this);
        fr.influencraft.flux.api.welcome.JoinScreens.bind(joinScreens);
        // middle click on a custom block picks its item (the vanilla block it is drawn with otherwise)
        getServer().getPluginManager().registerEvents(new fr.influencraft.flux.items.CustomBlockPick(), this);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) papiBridge = new PapiBridge(this);
        fr.influencraft.flux.api.FluxApi.bind(this);
        fr.influencraft.flux.api.FluxApi.registerPlaceholders(this, "flux", this::ownPlaceholder);
        packetListener = PacketEvents.getAPI().getEventManager()
                .registerListener(new InventoryHider(menus), PacketListenerPriority.HIGH);
        // players' screens shake at most as often as a plugin allows (api.client.HurtShake)
        hurtShakeListener = PacketEvents.getAPI().getEventManager()
                .registerListener(new fr.influencraft.flux.adapters.packetevents.HurtShakeFilter(this));
        // a player's sidebar taken off his screen while a plugin needs it (api.client.Sidebar)
        var sidebar = new fr.influencraft.flux.adapters.packetevents.SidebarFilter();
        sidebarListener = PacketEvents.getAPI().getEventManager().registerListener(sidebar);
        fr.influencraft.flux.api.client.Sidebar.bind(sidebar);
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
                fr.influencraft.flux.api.client.Sidebar.forget(e.getPlayer().getUniqueId());
            }
        }, this);
        backgrounds = new ChestBackgrounds(menus, getLogger());
        backgroundListener = PacketEvents.getAPI().getEventManager()
                .registerListener(backgrounds, PacketListenerPriority.HIGH);
        // tab list (plugins/Flux/tab.yml), in place of the TAB plugin; nametags and the line below the name as packets
        tab = new fr.influencraft.flux.tab.TabService(this, getServer().getPluginManager().isPluginEnabled("packetevents")
                ? new fr.influencraft.flux.adapters.packetevents.PacketEventsNametags() : null);
        tab.start();
        // HUD (plugins/Flux/hud.yml) in place of MythicHUD, drawn by the text shader of Flux's client pack
        hud = new fr.influencraft.flux.hud.HudService(this);
        hud.start();
        // the advancements key opens our menu (advancement-redirect.command)
        advancementListener = PacketEvents.getAPI().getEventManager()
                .registerListener(new fr.influencraft.flux.adapters.packetevents.AdvancementRedirect(this,
                        () -> getConfig().getBoolean("advancement-redirect.enabled", true)
                                ? getConfig().getString("advancement-redirect.command", "menu") : null));
        bossBarListener = PacketEvents.getAPI().getEventManager()
                .registerListener(new fr.influencraft.flux.adapters.packetevents.BossBarOrder(this, hud));
        // a view through another entity, the mouse as a pointer (api.client.Camera, FluxTopDown)
        var camera = new fr.influencraft.flux.adapters.packetevents.PacketEventsCamera(this);
        cameraListener = PacketEvents.getAPI().getEventManager().registerListener(camera);
        fr.influencraft.flux.api.client.Camera.bind(camera);
        // Flux's part of the resource pack: the one text shader of the suite (api.client.TextShader), HUD pictures
        if (clientPack == null) clientPack = new fr.influencraft.flux.pack.ClientPack(this, getFile());
        clientPack.configure(getConfig().getConfigurationSection("client-pack"));
        clientPack.start();
        // once every plugin is enabled: PlayerPoints may load after us
        getServer().getScheduler().runTask(this, () -> fr.influencraft.flux.compat.PlayerPointsCompat.start(this, currencies));
        getServer().getScheduler().runTask(this, currencies::checkVault);
        // every plugin has opened its tables: record them (what /fluxmenu database migrate moves)
        getServer().getScheduler().runTaskAsynchronously(this, fr.influencraft.flux.api.data.Database::rememberTables);
    }

    @Override
    public void onDisable() {
        fr.influencraft.flux.api.FluxApi.bind(null);
        if (rewards != null) rewards.stop();
        if (universes != null) universes.stop();
        if (trades != null) trades.stop();
        if (leaderboards != null) leaderboards.stop();
        if (currencies != null) currencies.stop();
        if (tab != null) tab.stop();
        if (hud != null) hud.stop();
        if (clientPack != null) clientPack.stop();
        if (bossBarListener != null) PacketEvents.getAPI().getEventManager().unregisterListener(bossBarListener);
        if (cameraListener != null) PacketEvents.getAPI().getEventManager().unregisterListener(cameraListener);
        fr.influencraft.flux.api.util.PlayerSettings.close();
        fr.influencraft.flux.api.data.Database.stop();
        if (papiBridge != null) papiBridge.shutdown();
        if (menus != null) menus.closeAll();
        if (packetListener != null) PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        if (backgroundListener != null) PacketEvents.getAPI().getEventManager().unregisterListener(backgroundListener);
        if (hurtShakeListener != null) PacketEvents.getAPI().getEventManager().unregisterListener(hurtShakeListener);
        if (sidebarListener != null) PacketEvents.getAPI().getEventManager().unregisterListener(sidebarListener);
        if (advancementListener != null) PacketEvents.getAPI().getEventManager().unregisterListener(advancementListener);
        if (registry != null) registry.unregisterCommands();
    }

    private fr.influencraft.flux.api.text.Messages messages;
    private fr.influencraft.flux.rewards.RewardService rewards;
    private fr.influencraft.flux.universe.UniverseService universes;
    private fr.influencraft.flux.trade.TradeService trades;
    private fr.influencraft.flux.economy.CurrencyService currencies;
    private fr.influencraft.flux.tab.TabService tab;
    private fr.influencraft.flux.hud.HudService hud;
    private fr.influencraft.flux.pack.ClientPack clientPack;
    private com.github.retrooper.packetevents.event.PacketListenerCommon bossBarListener;
    private com.github.retrooper.packetevents.event.PacketListenerCommon cameraListener;
    private fr.influencraft.flux.leaderboard.LeaderboardService leaderboards;
    private fr.influencraft.flux.profile.ProfileService profile;
    private fr.influencraft.flux.help.HelpService help;
    private fr.influencraft.flux.settings.SettingsService settings;

    /** Copies one of Flux's own menus when missing (translation under i18n/<language>/ when there is one). */
    private void shipOwnMenu(String id) {
        File target = new File(getDataFolder(), "menus/" + id + ".yml");
        if (target.exists()) return;
        String language = getConfig().getString("language", "en_US");
        String source = getResource("i18n/" + language + "/menus/" + id + ".yml") != null ? "i18n/" + language + "/menus/" + id + ".yml" : "menus/" + id + ".yml";
        try (java.io.InputStream in = getResource(source)) {
            if (in == null) return;
            target.getParentFile().mkdirs();
            java.nio.file.Files.copy(in, target.toPath());
        } catch (java.io.IOException e) {
            getLogger().warning("Could not write menus/" + id + ".yml: " + e.getMessage());
        }
    }

    private void readSettings() {
        reloadConfig();
        clickCooldown = Math.max(0, getConfig().getInt("click-cooldown", 4));
        openSound = getConfig().getString("menu-open-sound", "@page");
        prefix = getConfig().getString("prefix", "&#FFD94D&lFlux &8► ");
        Text.configureLore(getConfig().getInt("lore.max-line-length", 40), getConfig().getBoolean("lore.repeat-prefix", true));
        fr.influencraft.flux.api.util.Sounds.configure(getConfig().getConfigurationSection("sounds"));
        // look of every button (lore frame, colours, actions, filters): plugins/Flux/style.yml
        java.io.File styleFile = new java.io.File(getDataFolder(), "style.yml");
        if (!styleFile.exists()) saveResource("style.yml", false);
        org.bukkit.configuration.file.YamlConfiguration style = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(styleFile);
        fr.influencraft.flux.api.text.Style.configure(style);
        fr.influencraft.flux.api.staff.Staff.configure(getConfig().getConfigurationSection("staff"));
        fr.influencraft.flux.session.MenuSession.bedrockHidden = getConfig().getBoolean("bedrock.hidden-inventory", true);
        if (leaderboards != null) leaderboards.configure(getConfig().getConfigurationSection("leaderboards"));
        commandManager.configure(getConfig().getConfigurationSection("command-suggestions"));
        // suite-wide settings: language, currencies, dialogs
        messages = fr.influencraft.flux.api.text.Messages.load(this, getConfig().getString("language", "en_US"));
        fr.influencraft.flux.api.text.Translations.load(this, messages.language());
        fr.influencraft.flux.api.text.ItemSprites.load(this);
        java.util.Locale numbers = java.util.Locale.forLanguageTag(messages.language().replace('_', '-'));
        fr.influencraft.flux.api.util.Format.configure(key -> {
            String w = messages.raw("format." + key);
            return w.isEmpty() ? key : w;
        }, numbers);
        fr.influencraft.flux.api.economy.Economy.configure(getConfig().getString("currencies.main", "vault"),
                id -> getConfig().getString("currencies." + id + ".format", "%amount%"));
        fr.influencraft.flux.api.economy.Economy.configureIcons(id -> getConfig().getString("currencies." + id + ".icon", "GOLD_NUGGET"));
        if (currencies != null) currencies.configure(getConfig(), style.getConfigurationSection("money"));
        if (moneyBar != null) moneyBar.configure(getConfig().getConfigurationSection("money-actionbar"), style.getConfigurationSection("money"));
        java.util.Map<String, String> dialog = new java.util.HashMap<>();
        var colors = getConfig().getConfigurationSection("dialogs");
        if (colors != null) for (String k : colors.getKeys(false)) dialog.put(k, colors.getString(k, ""));
        for (String k : java.util.List.of("confirm-label", "cancel-label", "copy-label", "link-label", "amount-invalid", "amount-min", "amount-max")) {
            String v = messages.raw("dialogs." + k);
            if (!v.isEmpty()) dialog.put(k, v);
        }
        fr.influencraft.flux.api.dialog.Dialogs.configure(dialog);
        java.util.Map<String, String> themeButtons = new java.util.HashMap<>();
        // former-theme pictures restyled by gen_ui.py, then theme.buttons of the config (wins)
        java.io.File restyled = new java.io.File(getDataFolder(), "theme-buttons.yml");
        if (restyled.isFile()) {
            var r = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(restyled);
            for (String k : r.getKeys(false)) themeButtons.put(k.toLowerCase(java.util.Locale.ROOT), r.getString(k));
        }
        var tb = getConfig().getConfigurationSection("theme.buttons");
        if (tb != null) for (String k : tb.getKeys(false)) themeButtons.put(k.toLowerCase(java.util.Locale.ROOT), tb.getString(k));
        fr.influencraft.flux.menu.MenuLoader.configureTheme(themeButtons, getConfig().getConfigurationSection("theme.menus"),
                getConfig().getStringList("theme.content-namespaces"));
        // after the dialog colours: the pause menu window is written with them
        if (help != null) {
            help.load();
            help.writeDataPack();
        }
        if (settings != null) settings.load();
    }

    /** Suite-wide language ({@code language} in Flux's config), default of every plugin of the suite. */
    @Override
    public String language() {
        return messages == null ? "en_US" : messages.language();
    }

    /**
     * {@code %flux_cmd_<plugin>_<id>%}: configured label of a command of the suite;
     * {@code %flux_list_<id>_<field>%}: state of a list (see {@link fr.influencraft.flux.api.list.Lists});
     * {@code %flux_top_<board>_...%}: leaderboards (see {@link fr.influencraft.flux.api.leaderboard.Leaderboards});
     * {@code %flux_smallcaps_<text>%}: the text in small caps, {@code {placeholders}} inside resolved first;
     * {@code %flux_empty_slots%}: empty slots among the 36 storage slots (PlaceholderAPI's own counts armour too).
     */
    private String ownPlaceholder(Player player, String params) {
        if (params.startsWith("balance_") && currencies != null) return currencies.placeholder(player, params.substring(8));
        if (params.startsWith("rewards_") && rewards != null) return rewards.placeholder(player, params.substring(8));
        if (params.equals("trade_title") && trades != null) return trades.title(player);
        if (params.startsWith("top_") && leaderboards != null) return leaderboards.placeholder(player, params.substring(4));
        if (params.startsWith("profile_") && profile != null) return profile.placeholder(player, params.substring(8));
        if (params.equals("empty_slots")) {
            if (player == null) return "";
            int empty = 0;
            for (org.bukkit.inventory.ItemStack i : player.getInventory().getStorageContents()) if (i == null || i.isEmpty()) empty++;
            return String.valueOf(empty);
        }
        if (params.startsWith("smallcaps_")) {
            // %flux_smallcaps_({fluxpass_tier}/{fluxpass_max_tiers})%: small caps for plugins outside Flux (Typewriter)
            String text = params.substring(10);
            if (text.indexOf('{') >= 0 && getServer().getPluginManager().isPluginEnabled("PlaceholderAPI"))
                text = me.clip.placeholderapi.PlaceholderAPI.setBracketPlaceholders(player, text);
            return fr.influencraft.flux.api.text.Style.smallCaps(text);
        }
        if (params.startsWith("cmd_")) {
            String rest = params.substring(4);
            int sep = rest.indexOf('_');
            return sep < 0 ? null : fr.influencraft.flux.api.command.Commands.label(rest.substring(0, sep), rest.substring(sep + 1));
        }
        if (params.startsWith("list_") && player != null) {
            String rest = params.substring(5);
            for (String field : java.util.List.of("has_next", "has_prev", "pages", "page", "count", "filter", "sort", "search")) {
                if (rest.endsWith("_" + field)) {
                    String id = rest.substring(0, rest.length() - field.length() - 1);
                    var menu = menus.currentMenu(player.getUniqueId());
                    var spec = menu == null ? null : menu.list(id);
                    return fr.influencraft.flux.api.list.Lists.placeholder(player, id, field, spec == null ? 21 : spec.pageSize());
                }
            }
        }
        return null;
    }

    public MenuService menus() {
        return menus;
    }

    public MenuRegistry registry() {
        return registry;
    }

    // ---------------------------------------------------------------- FluxApi backend

    @Override
    public boolean open(Player player, String menuId) {
        Menu menu = registry.get(menuId);
        if (menu == null) {
            getLogger().warning("Menu inconnu « " + menuId + " » (FluxApi.open)");
            return false;
        }
        return menus.open(player, menu);
    }

    @Override
    public fr.influencraft.flux.api.MenuView view(Player player) {
        var session = menus.session(player.getUniqueId());
        return session == null || !session.isOpen() ? null : new FluxView(this, player, session);
    }

    @Override
    public boolean hasMenu(String menuId) {
        return registry.get(menuId) != null;
    }

    @Override
    public void menuFoldersChanged() {
        int warnings = registry.load();
        getLogger().info(registry.all().size() + " menu(s) chargé(s) après l'ajout d'un dossier de menus"
                + (warnings > 0 ? ", " + warnings + " avertissement(s)" : ""));
    }

    @Override
    public void placeholdersChanged() {
        if (papiBridge != null) papiBridge.sync();
    }

    /** A plugin of the suite stops: its actions, placeholders, item sources and menus go with it. */
    @org.bukkit.event.EventHandler
    public void onPluginDisable(org.bukkit.event.server.PluginDisableEvent event) {
        if (event.getPlugin() == this) return;
        boolean hadMenus = fr.influencraft.flux.api.FluxApi.unregisterAll(event.getPlugin());
        fr.influencraft.flux.api.list.Lists.unregisterAll(event.getPlugin());
        fr.influencraft.flux.api.command.Commands.unregisterAll(event.getPlugin());
        if (universes != null) universes.unclaimAll(event.getPlugin());
        fr.influencraft.flux.api.economy.Economy.unregisterAll(event.getPlugin());
        fr.influencraft.flux.api.tab.TabList.unregisterAll(event.getPlugin());
        fr.influencraft.flux.api.hud.Hud.unregisterAll(event.getPlugin());
        fr.influencraft.flux.api.client.TextShader.unregisterAll(event.getPlugin());
        fr.influencraft.flux.api.reward.Rewards.unregisterAll(event.getPlugin());
        fr.influencraft.flux.api.items.CustomItems.unregisterAll(event.getPlugin());
        fr.influencraft.flux.api.effects.Effects.unregisterAll(event.getPlugin());
        fr.influencraft.flux.api.shops.ChestShops.unregisterAll(event.getPlugin());
        placeholdersChanged();
        if (hadMenus) menuFoldersChanged();
    }



    public Actions actions() {
        return actions;
    }

    /** Sound of a menu opening when its open-actions play none ("@cue" or "<sound> [volume] [pitch]", "none"). */
    public String openSound() {
        return openSound;
    }

    public int clickCooldown() {
        return clickCooldown;
    }

    public String prefix() {
        return prefix;
    }

    private void msg(CommandSender sender, String text) {
        sender.sendMessage(Text.line(sender instanceof Player p ? p : null, prefix + text));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("flux.admin")) {
            msg(sender, "&#FF5C5CPermission manquante.");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "open" -> {
                if (args.length < 2) {
                    msg(sender, "&#B376E5/" + label + " open <menu> [joueur]");
                    return true;
                }
                Menu menu = registry.get(args[1]);
                if (menu == null) {
                    msg(sender, "&#FF5C5CMenu inconnu : &f" + args[1]);
                    return true;
                }
                Player target;
                if (args.length >= 3) {
                    target = Bukkit.getPlayerExact(args[2]);
                    if (target == null) {
                        msg(sender, "&#FF5C5CJoueur introuvable : &f" + args[2]);
                        return true;
                    }
                } else if (sender instanceof Player p) {
                    target = p;
                } else {
                    msg(sender, "&#FF5C5CPrécise un joueur depuis la console.");
                    return true;
                }
                menus.open(target, menu);
            }
            case "reload" -> {
                menus.closeAll();
                readSettings();
                items.clearCache();
                Text.resetHooks();
                backgrounds.reset();
                PathRenderer.reset();
                if (tab != null) tab.load();
                if (hud != null) hud.load();
                int warnings = registry.load();
                msg(sender, "&#2ECF7B" + registry.all().size() + " menu(s) rechargé(s)"
                        + (warnings > 0 ? " &#FF5C5C(" + warnings + " avertissement(s), voir la console)" : "") + ".");
            }
            case "list" -> {
                for (Menu m : registry.all()) {
                    msg(sender, "&#FFD94D" + m.id() + " &8- &#B376E5" + m.rows() + " rangées, inventaire "
                            + m.inventoryMode().name().toLowerCase(Locale.ROOT) + ", " + m.buttons().size() + " boutons"
                            + (m.commands().isEmpty() ? "" : ", /" + String.join(" /", m.commands())));
                }
            }
            case "database" -> database(sender, label, args);
            default -> msg(sender, "&#B376E5/" + label + " open <menu> [joueur] &8| &#B376E5reload &8| &#B376E5list &8| &#B376E5database");
        }
        return true;
    }

    /** {@code /fluxmenu database [status | migrate <sqlite|mysql> [replace] | cancel]}: where the suite keeps its data. */
    private void database(CommandSender sender, String label, String[] args) {
        String action = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "migrate" -> {
                if (args.length < 3 || !List.of("sqlite", "mysql", "mariadb").contains(args[2].toLowerCase(Locale.ROOT))) {
                    messages.send(sender, "database.usage", "%label%", label);
                    return;
                }
                boolean replace = args.length >= 4 && args[3].equalsIgnoreCase("replace");
                String target = args[2].toLowerCase(Locale.ROOT);
                messages.send(sender, "database.checking", "%target%", target);
                // the target is reached over the network: never on the main thread
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    String error = fr.influencraft.flux.api.data.Database.planMigration(this, target, replace, sender.getName());
                    if (error == null) messages.send(sender, "database.planned", "%target%", target);
                    else messages.send(sender, "database.refused", "%reason%", error);
                });
            }
            case "cancel" -> {
                String error = fr.influencraft.flux.api.data.Database.cancelMigration(this);
                if (error == null) messages.send(sender, "database.cancelled");
                else messages.send(sender, "database.refused", "%reason%", error);
            }
            default -> getServer().getScheduler().runTaskAsynchronously(this, () -> {
                messages.send(sender, "database.status-title");
                for (String line : fr.influencraft.flux.api.data.Database.status(this)) messages.send(sender, "database.status-line", "%line%", line);
            });
        }
    }

    /** The database is closed (server unreachable, failed migration): players wait outside instead of losing progress. */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void blockJoinWithoutDatabase(org.bukkit.event.player.AsyncPlayerPreLoginEvent event) {
        if (fr.influencraft.flux.api.data.Database.available() || !fr.influencraft.flux.api.data.Database.blockJoinsOnError()) return;
        if (getServer().getOfflinePlayer(event.getUniqueId()).isOp()) return;
        event.disallow(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get("database.join-blocked", null));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("flux.admin")) return out;
        if (args.length == 1) {
            for (String s : List.of("open", "reload", "list", "database")) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("database")) {
            for (String s : List.of("status", "migrate", "cancel")) if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("database") && args[1].equalsIgnoreCase("migrate")) {
            for (String s : List.of("sqlite", "mysql")) if (s.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 4 && args[0].equalsIgnoreCase("database") && args[1].equalsIgnoreCase("migrate")) {
            if ("replace".startsWith(args[3].toLowerCase(Locale.ROOT))) out.add("replace");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
            for (Menu m : registry.all()) if (m.id().startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(m.id());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("open")) {
            return null; // noms des joueurs
        }
        return out;
    }

    /** Loads every class of Flux's jar (see onEnable; plugins/fluxplugin/CLAUDE.md, known pitfalls). */
    private void preloadClasses() {
        int count = 0;
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(getFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith("fr/influencraft/flux/") || !name.endsWith(".class")) continue;
                try {
                    Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false, getClassLoader());
                    count++;
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // a class that cannot load now (an adapter of an absent plugin) would not load later either
                }
            }
        } catch (java.io.IOException e) {
            getLogger().warning("Could not preload the classes of Flux: " + e.getMessage());
        }
        getLogger().info(count + " classes preloaded.");
    }
}
