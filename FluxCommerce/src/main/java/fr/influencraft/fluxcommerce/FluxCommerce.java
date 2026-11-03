package fr.influencraft.fluxcommerce;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.text.ChatHelp;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.fluxcommerce.auctionhouse.AuctionHouseModule;
import fr.influencraft.fluxcommerce.chestshop.ChestShopModule;
import fr.influencraft.fluxcommerce.trade.TradeModule;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * FluxCommerce: everything players buy, sell and exchange between themselves, in one plugin of the suite.
 * <ul>
 *   <li>{@code trade}: secure trades between two players ({@link TradeModule}, {@code /trade});</li>
 *   <li>{@code auctionhouse}: the market, auction house and chest shops listed together ({@link AuctionHouseModule},
 *   {@code /hdv});</li>
 *   <li>{@code chestshop}: the players' chest shops ({@link ChestShopModule}, {@code /echoppe}).</li>
 * </ul>
 * Each part can be switched off ({@code modules} of config.yml) and has its own folder (config, texts, menus). They
 * share one list of forbidden items ({@link ItemRules}) and one list of banned players ({@link Bans}), managed with
 * {@code /commerce}. A part that fails to start is left off; the others run.
 *
 * <pre>
 *   /commerce [help] | ban &lt;player&gt; &lt;duration&gt; &lt;reason&gt; | pardon &lt;player&gt; | bans | blacklist [add [rule]|remove &lt;n&gt;]
 *             | about | reload
 * </pre>
 */
public final class FluxCommerce extends FluxPlugin {

    static final String PERMISSION = "fluxcommerce.";
    /** Bans from commerce (moderators). */
    public static final String STAFF = PERMISSION + "staff";
    /** Forbidden items, reload, and the admin of every part. */
    public static final String ADMIN = PERMISSION + "admin";

    private final ItemRules rules = new ItemRules(this);
    private final Bans bans = new Bans(this);
    private final ChestShopModule chestShops = new ChestShopModule(this);
    private final AuctionHouseModule auctionHouse = new AuctionHouseModule(this);
    private final TradeModule trades = new TradeModule(this);
    /** In starting order: chest shops first (the auction house lists them). */
    private final List<Module> modules = List.of(chestShops, auctionHouse, trades);
    private final List<Module> running = new ArrayList<>();

    @Override
    protected void enable() {
        if (money() == null) {
            getLogger().severe("No currency in Flux (currencies.main): FluxCommerce disabled.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        try {
            bans.open();
        } catch (SQLException e) {
            getLogger().severe("Database of Flux unavailable: " + e.getMessage() + ". FluxCommerce disabled.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        rules.load();
        for (Module m : modules) {
            if (!getConfig().getBoolean("modules." + m.id(), true)) {
                getLogger().info("Part " + m.id() + " switched off (modules." + m.id() + " in config.yml).");
                continue;
            }
            try {
                m.start();
                running.add(m);
            } catch (RuntimeException | LinkageError e) {
                getLogger().log(java.util.logging.Level.SEVERE, "Part " + m.id() + " could not start, left off", e);
            }
        }
        command("commerce")
                .description("Commerce administration: bans and forbidden items for trades, market and shops.")
                .aliases("fluxcommerce")
                .executor(this::onCommand)
                .completer(this::complete)
                .register();
        getLogger().info("Parts running: " + String.join(", ", running.stream().map(Module::id).toList()) + ".");
    }

    @Override
    protected void disable() {
        for (int i = running.size() - 1; i >= 0; i--) {
            Module m = running.get(i);
            try {
                m.disable();
            } catch (RuntimeException | LinkageError e) {
                getLogger().log(java.util.logging.Level.SEVERE, "Part " + m.id() + " did not stop cleanly", e);
            }
        }
        running.clear();
        bans.close();
    }

    /** Main config and texts, the forbidden items, then every running part. */
    @Override
    public void reload() {
        super.reload();
        rules.load();
        for (Module m : running) m.reload();
    }

    // ---------------------------------------------------------------- shared rules

    public ItemRules rules() {
        return rules;
    }

    public Bans bans() {
        return bans;
    }

    /** Whether this part is running. */
    public boolean running(String moduleId) {
        for (Module m : running) if (m.id().equals(moduleId)) return true;
        return false;
    }

    // ---------------------------------------------------------------- /commerce

    private static final List<String> SUBCOMMANDS = List.of("help", "ban", "pardon", "bans", "blacklist", "about", "reload");
    private static final List<ChatHelp.Entry> HELP = List.of(
            new ChatHelp.Entry("ban <joueur> <durée> <motif>", "ban", STAFF),
            new ChatHelp.Entry("pardon <joueur>", "pardon", STAFF),
            new ChatHelp.Entry("bans", "bans", STAFF),
            new ChatHelp.Entry("blacklist [add [règle]|remove <n>]", "blacklist", ADMIN),
            new ChatHelp.Entry("about", "about", STAFF),
            new ChatHelp.Entry("reload", "reload", ADMIN),
            new ChatHelp.Entry("help [page]", "help", STAFF));

    private boolean onCommand(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(STAFF)) {
            messages().send(sender, "no-permission");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!shared(sender, label, args, 0)) {
            switch (sub) {
                case "reload" -> {
                    if (!sender.hasPermission(ADMIN)) {
                        messages().send(sender, "no-permission");
                        return true;
                    }
                    reload();
                    messages().send(sender, "reloaded");
                }
                case "about" -> messages().send(sender, "about", "%version%", getPluginMeta().getVersion(),
                        "%authors%", String.join(", ", getPluginMeta().getAuthors()),
                        "%modules%", String.join(", ", running.stream().map(Module::id).toList()),
                        "%bans%", String.valueOf(bans.all().size()), "%rules%", String.valueOf(rules.rules().size()));
                default -> {
                    int page = 1;
                    if (args.length > 1) {
                        try {
                            page = Integer.parseInt(args[1]);
                        } catch (NumberFormatException ignored) {
                            // first page
                        }
                    }
                    ChatHelp.show(sender, messages(), label, HELP, "help", true, page, sender::hasPermission);
                }
            }
        }
        return true;
    }

    /**
     * The commands every part shares, from {@code /commerce} or from a part's own admin command ({@code /hdv admin ban}
     * ...): {@code args[from]} is the sub-command. False when it is not one of them.
     */
    public boolean shared(CommandSender sender, String label, String[] args, int from) {
        String sub = args.length > from ? args[from].toLowerCase(Locale.ROOT) : "";
        String[] rest = Arrays.copyOfRange(args, Math.min(args.length, from + 1), args.length);
        switch (sub) {
            case "ban" -> {
                if (allowed(sender, STAFF)) ban(sender, rest);
            }
            case "pardon" -> {
                if (!allowed(sender, STAFF)) return true;
                OfflinePlayer target = rest.length > 0 ? Players.find(rest[0]) : null;
                if (target == null || !bans.pardon(target.getUniqueId())) messages().send(sender, "not-banned");
                else messages().send(sender, "pardon-done", "%player%", rest[0]);
            }
            case "bans" -> {
                if (!allowed(sender, STAFF)) return true;
                var all = bans.all();
                if (all.isEmpty()) messages().send(sender, "bans-none");
                for (Bans.Ban b : all) {
                    messages().send(sender, "bans-line", "%player%", b.name() == null ? b.player().toString() : b.name(),
                            "%duration%", Format.duration((b.until() - System.currentTimeMillis()) / 1000),
                            "%reason%", b.reason() == null ? "-" : b.reason(), "%by%", b.by() == null ? "?" : b.by());
                }
            }
            case "blacklist" -> {
                if (allowed(sender, ADMIN)) blacklist(sender, rest);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        messages().send(sender, "no-permission");
        return false;
    }

    /** {@code ban <player> <duration> <reason>}. */
    private void ban(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages().send(sender, "ban-usage");
            return;
        }
        OfflinePlayer target = Players.find(args[0]);
        long seconds = Format.parseDuration(args[1]);
        if (target == null) {
            messages().send(sender, "player-unknown", "%player%", args[0]);
            return;
        }
        if (seconds <= 0) {
            messages().send(sender, "bad-duration");
            return;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        bans.ban(target.getUniqueId(), target.getName() == null ? args[0] : target.getName(), seconds, reason, sender.getName());
        messages().send(sender, "ban-done", "%player%", args[0], "%duration%", Format.duration(seconds), "%reason%", reason);
        Player online = target.getPlayer();
        if (online != null) messages().send(online, "banned", "%duration%", Format.duration(seconds), "%reason%", reason);
    }

    /** {@code blacklist}: list; {@code add}: the item in hand (custom id or material) or a typed rule; {@code remove <n>}. */
    private void blacklist(CommandSender sender, String[] args) {
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "add" -> {
                String rule;
                if (args.length > 1) rule = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                else if (sender instanceof Player p && !p.getInventory().getItemInMainHand().getType().isAir()) {
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    String id = ItemRules.customId(hand);
                    rule = id != null ? id : hand.getType().name();
                } else {
                    messages().send(sender, "hold-item");
                    return;
                }
                rules.add(rule);
                messages().send(sender, "blacklist-added", "%rule%", rule);
            }
            case "remove" -> {
                int n;
                try {
                    n = Integer.parseInt(args.length > 1 ? args[1] : "") - 1;
                } catch (NumberFormatException e) {
                    n = -1;
                }
                String removed = rules.remove(n);
                if (removed == null) messages().send(sender, "blacklist-bad-index");
                else messages().send(sender, "blacklist-removed", "%rule%", removed);
            }
            default -> {
                List<String> all = rules.rules();
                if (all.isEmpty()) messages().send(sender, "blacklist-empty");
                for (int i = 0; i < all.size(); i++) messages().send(sender, "blacklist-line", "%n%", String.valueOf(i + 1), "%rule%", all.get(i));
            }
        }
    }

    /** Completion of the shared sub-commands, {@code args[from]} being the sub-command (also used by the parts). */
    public List<String> completeShared(CommandSender sender, String[] args, int from) {
        List<String> options = new ArrayList<>();
        if (args.length == from + 2) {
            switch (args[from].toLowerCase(Locale.ROOT)) {
                case "ban", "pardon" -> {
                    if (sender.hasPermission(STAFF)) options.addAll(Players.complete(args[from + 1]));
                }
                case "blacklist" -> {
                    if (sender.hasPermission(ADMIN)) options.addAll(List.of("list", "add", "remove"));
                }
                default -> {
                }
            }
        }
        return options;
    }

    private List<String> complete(CommandSender sender, String[] args) {
        List<String> options = new ArrayList<>();
        if (!sender.hasPermission(STAFF)) return options;
        if (args.length == 1) {
            options.addAll(SUBCOMMANDS);
            if (!sender.hasPermission(ADMIN)) options.removeAll(List.of("blacklist", "reload"));
        } else {
            options.addAll(completeShared(sender, args, 0));
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }
}
