package fr.influencraft.fluxcommerce.trade;

import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.text.ChatHelp;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Experience;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.PlayerSettings;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.fluxcommerce.FluxCommerce;
import fr.influencraft.fluxcommerce.Module;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Trades of FluxCommerce: secure trades between two players, written for the suite (it replaces AxTrade). A player sneaks and right
 * clicks another one (or types {@code /trade <player>}); once the other accepts, both get one window where each puts
 * items (clicked in his own inventory), experience (levels or points) and money. Offered items are held by the plugin,
 * out of both inventories, until the trade ends. Each player validates; any change cancels both validations; once both
 * validated, a short countdown runs, everything is checked again and the trade is made. Completed trades are kept for
 * the staff ({@code /trade admin history}).
 *
 * <pre>
 *   /trade [help] | &lt;player&gt; | accept [player] | deny [player] | toggle [on|off] | admin &lt;history|cancel|reload&gt;
 * </pre>
 */
public final class TradeModule extends Module {

    static final String USE = "fluxcommerce.trade.use", STAFF = "fluxcommerce.trade.staff", ADMIN = "fluxcommerce.trade.admin";
    private static final String SETTING = "fluxtrade:requests";

    private final Trades trades = new Trades(this);
    private final TradeMenu menu = new TradeMenu(this, trades);
    private final TradeHistory history = new TradeHistory(this);

    // settings
    private final Set<GameMode> gamemodes = EnumSet.noneOf(GameMode.class);
    private Set<String> disabledWorlds = Set.of();
    private double maxDistance;

    public TradeModule(FluxCommerce host) {
        super(host, "trade", "trade");
    }

    @Override
    protected void enable() {
        settings();
        history.open();
        menu.register();
        action("echange", menu::click);
        placeholders("fluxtrade", menu::placeholder);
        Bukkit.getPluginManager().registerEvents(new TradeListener(this, trades), host);
        command("trade")
                .description("Trade with another player: items, experience and money.")
                .aliases("echange")
                .executor(this::onCommand)
                .completer(this::complete)
                .register();
        trades.start();
    }

    @Override
    protected void disable() {
        trades.stop();
    }

    @Override
    public void reload() {
        super.reload();
        settings();
    }

    private void settings() {
        var c = getConfig();
        gamemodes.clear();
        for (String g : c.getStringList("gamemodes")) {
            try {
                gamemodes.add(GameMode.valueOf(g.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Unknown game mode in gamemodes: " + g);
            }
        }
        if (gamemodes.isEmpty()) {
            getLogger().warning("gamemodes is empty: trades allowed in survival only");
            gamemodes.add(GameMode.SURVIVAL);
        }
        Set<String> worlds = new HashSet<>();
        for (String w : c.getStringList("disabled-worlds")) worlds.add(w.toLowerCase(Locale.ROOT));
        disabledWorlds = worlds;
        maxDistance = c.getDouble("max-distance", 10);
    }

    // ---------------------------------------------------------------- rules

    /** Why {@code from} cannot trade with {@code to} right now (a lang key, {@code %player%} = to), or null. */
    String cannotTrade(Player from, Player to) {
        if (from.equals(to)) return "request-self";
        if (!from.hasPermission(USE)) return "no-permission";
        if (host.bans().of(from.getUniqueId()) != null) return "request-banned-you";
        if (!to.isOnline()) return "request-offline";
        if (trades.of(from) != null) return "request-you-busy";
        if (trades.of(to) != null) return "request-busy";
        if (!gamemodes.contains(from.getGameMode())) return "request-gamemode-you";
        if (!gamemodes.contains(to.getGameMode())) return "request-gamemode";
        if (disabled(from) || disabled(to)) return "request-world";
        if (tooFar(from, to)) return "request-too-far";
        if (!to.hasPermission(USE)) return "request-not-accepting";
        if (host.bans().of(to.getUniqueId()) != null) return "request-banned";
        if (!getConfig().getBoolean("allow-same-ip", true) && sameAddress(from, to)) return "request-same-ip";
        return null;
    }

    /** Why a trade in progress must end (a lang key), or null. Checked every second and before the trade is made. */
    String cannotKeepTrading(Player a, Player b) {
        if (!gamemodes.contains(a.getGameMode()) || !gamemodes.contains(b.getGameMode())) return "aborted-gamemode";
        if (disabled(a) || disabled(b)) return "aborted-world";
        if (tooFar(a, b)) return "aborted-too-far";
        if (host.bans().of(a.getUniqueId()) != null || host.bans().of(b.getUniqueId()) != null) return "aborted-banned";
        return null;
    }

    /** Whether the player still has the experience and money he offers (a lang key when not). */
    String cannotPay(Player p, Trade.Side side) {
        if (side.xp > 0 && Experience.total(p) < side.xp) return "not-enough-xp";
        if (side.money > 0 && (money() == null || !money().has(p, side.money))) return "not-enough-money";
        return null;
    }

    private boolean disabled(Player p) {
        return disabledWorlds.contains(p.getWorld().getName().toLowerCase(Locale.ROOT));
    }

    private boolean tooFar(Player a, Player b) {
        if (maxDistance < 0) return false;
        if (!a.getWorld().equals(b.getWorld())) return true;
        return a.getLocation().distanceSquared(b.getLocation()) > maxDistance * maxDistance;
    }

    private static boolean sameAddress(Player a, Player b) {
        InetSocketAddress x = a.getAddress(), y = b.getAddress();
        return x != null && y != null && x.getAddress() != null && x.getAddress().equals(y.getAddress());
    }

    /** Whether the player accepts trade requests (settings menu, /trade toggle). */
    boolean accepting(Player p) {
        return PlayerSettings.flag(p, SETTING, true);
    }

    /** Refused item: forbidden in all commerce ({@code blacklist} of the main config), or holding one. */
    boolean blacklisted(ItemStack item) {
        return host.rules().forbidden(item);
    }

    /** A sound of the {@code sounds} section. */
    String sound(String key) {
        return getConfig().getString("sounds." + key, "none");
    }

    /** Label players type for the command ({@code trade} unless renamed in Flux's commands.yml). */
    String label() {
        return Commands.label(host.getName(), "trade");
    }

    // ---------------------------------------------------------------- texts

    /** The end of a trade, for one player: what he gave and received (items with their tooltip, experience). */
    void summary(Player to, String partner, List<ItemStack> given, List<ItemStack> received, Trade.Side mine, Trade.Side theirs) {
        messages().send(to, "completed", "%player%", partner);
        for (var e : grouped(given)) line(to, messages().raw("summary-item-given", "%amount%", Format.number(e.getValue())), e.getKey());
        for (var e : grouped(received)) line(to, messages().raw("summary-item-received", "%amount%", Format.number(e.getValue())), e.getKey());
        if (mine.xp > 0) messages().send(to, "summary-xp-given", "%points%", Format.number(mine.xp));
        if (theirs.xp > 0) messages().send(to, "summary-xp-received", "%points%", Format.number(theirs.xp),
                "%levels%", String.valueOf(to.getLevel()));
        if (given.isEmpty() && received.isEmpty() && mine.xp <= 0 && theirs.xp <= 0 && mine.money <= 0 && theirs.money <= 0) {
            messages().send(to, "summary-nothing");
        }
    }

    /** Same items added up (item with an amount of 1, total), so a trade of 9 stacks of stone reads as one line. */
    private static List<Map.Entry<ItemStack, Integer>> grouped(List<ItemStack> items) {
        List<Map.Entry<ItemStack, Integer>> out = new ArrayList<>();
        for (ItemStack item : items) {
            int found = -1;
            for (int i = 0; i < out.size(); i++) if (out.get(i).getKey().isSimilar(item)) found = i;
            if (found >= 0) {
                out.set(found, Map.entry(out.get(found).getKey(), out.get(found).getValue() + item.getAmount()));
            } else {
                ItemStack model = item.clone();
                model.setAmount(1);
                out.add(Map.entry(model, item.getAmount()));
            }
        }
        return out;
    }

    /** A line ({@code !} = without prefix) whose {@code %item%} is the item's name in the reader's language, tooltip on hover. */
    private void line(Player to, String raw, ItemStack item) {
        String prefix = messages().raw("prefix");
        Component c = Texts.parseClickable(raw.startsWith("!") ? raw.substring(1) : prefix + raw, to);
        to.sendMessage(c.replaceText(TextReplacementConfig.builder().matchLiteral("%item%").replacement(itemName(item)).build()));
    }

    /** The item's name as each client writes it (custom name kept), its tooltip on hover. */
    private static Component itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        Component name;
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) name = meta.displayName();
        else if (meta != null && meta.hasItemName()) name = meta.itemName();
        else name = Component.translatable(item.getType().translationKey());
        return name.hoverEvent(item.asHoverEvent());
    }

    // ---------------------------------------------------------------- lists (menu)

    void listOf(String id, Function<Player, List<ListEntry>> entries) {
        list(id, query -> entries.apply(query.viewer()));
    }

    void itemOf(String id, Function<Player, ItemStack> item) {
        Lists.registerItem(host, id, item);
    }

    // ---------------------------------------------------------------- command

    private static final List<String> SUBCOMMANDS = List.of("help", "accept", "deny", "toggle", "admin");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("help", "history", "cancel", "reload");

    private List<ChatHelp.Entry> help() {
        return List.of(entry("", "request", USE), entry("accept", "accept", USE), entry("deny", "deny", USE),
                entry("toggle", "toggle", USE), entry("help", "help", null), entry("admin help", "admin", STAFF));
    }

    private List<ChatHelp.Entry> adminHelp() {
        return List.of(entry("admin history", "admin-history", STAFF), entry("admin cancel", "admin-cancel", STAFF),
                entry("admin reload", "admin-reload", ADMIN), entry("admin help", "admin-help", STAFF));
    }

    /** {@code sub} followed by its arguments as the lang file writes them ({@code help.args.<key>}). */
    private ChatHelp.Entry entry(String sub, String key, String permission) {
        return new ChatHelp.Entry((sub + " " + messages().raw("help.args." + key)).trim(), key, permission);
    }

    /** Paged help (Flux's ChatHelp, the look of Lands' help); {@code pageArg}: index of the page number. */
    private void help(CommandSender sender, String label, String[] args, boolean admin, int pageArg) {
        int page = 1;
        if (args.length > pageArg) {
            try {
                page = Integer.parseInt(args[pageArg]);
            } catch (NumberFormatException ignored) {
                // first page
            }
        }
        ChatHelp.show(sender, messages(), label, admin ? adminHelp() : help(), admin ? "admin help" : "help", admin, page,
                sender::hasPermission);
    }

    private boolean onCommand(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("admin")) return admin(sender, label, args);
        if (sub.equals("help") || sub.equals("aide")) {
            help(sender, label, args, false, 1);
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only (console: /" + label + " admin ...).");
            return true;
        }
        if (!p.hasPermission(USE)) {
            messages().send(p, "no-permission");
            return true;
        }
        String name = args.length > 1 ? args[1] : null;
        switch (sub) {
            case "accept", "accepter" -> trades.accept(p, name);
            case "deny", "refuser" -> trades.deny(p, name);
            case "toggle" -> toggle(p, name);
            default -> {
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null || !p.canSee(target)) {
                    messages().send(p, "request-offline", "%player%", args[0]);
                    Sounds.cue(p, "error");
                } else {
                    trades.request(p, target);
                }
            }
        }
        return true;
    }

    private void toggle(Player p, String state) {
        boolean on = state == null ? !accepting(p) : state.equalsIgnoreCase("on") || state.equalsIgnoreCase("true");
        PlayerSettings.set(p, SETTING, on);
        messages().send(p, on ? "toggle-on" : "toggle-off", "%cmd%", label());
        Sounds.cue(p, on ? "toggle-on" : "toggle-off");
    }

    private boolean admin(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(STAFF)) {
            messages().send(sender, "no-permission");
            return true;
        }
        String sub = args.length < 2 ? "help" : args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission(ADMIN)) {
                    messages().send(sender, "no-permission");
                    return true;
                }
                host.reload();
                messages().send(sender, "reloaded");
            }
            case "cancel" -> {
                Player target = args.length < 3 ? null : Bukkit.getPlayerExact(args[2]);
                Trade t = trades.of(target);
                if (t == null) {
                    messages().send(sender, "admin-no-trade", "%player%", args.length < 3 ? "?" : args[2]);
                } else {
                    trades.abort(t, "aborted-staff");
                    messages().send(sender, "admin-cancelled", "%player%", t.a.name, "%other%", t.b.name);
                }
            }
            case "history" -> history(sender, label, args);
            default -> help(sender, label, args, true, 2);
        }
        return true;
    }

    private static final int HISTORY_PAGE = 5;

    private void history(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            messages().send(sender, "admin-history-usage", "%cmd%", label);
            return;
        }
        OfflinePlayer target = Players.find(args[2]);
        if (target == null) {
            messages().send(sender, "admin-unknown-player", "%player%", args[2]);
            return;
        }
        int page = 1;
        if (args.length > 3) {
            try {
                page = Math.max(1, Integer.parseInt(args[3]));
            } catch (NumberFormatException ignored) {
                // first page
            }
        }
        int shown = page;
        String name = Players.name(target, args[2]);
        history.of(target.getUniqueId(), page - 1, HISTORY_PAGE, entries -> {
            if (entries.isEmpty()) {
                messages().send(sender, shown == 1 ? "admin-history-none" : "admin-history-end", "%player%", name);
                return;
            }
            messages().send(sender, "admin-history-header", "%player%", name, "%page%", String.valueOf(shown));
            for (TradeHistory.Entry e : entries) {
                messages().send(sender, "admin-history-line", "%date%", Format.date(e.at()), "%a%", e.aName(), "%b%", e.bName(),
                        "%a_gave%", e.aGave(), "%b_gave%", e.bGave());
            }
            if (entries.size() == HISTORY_PAGE) {
                messages().send(sender, "admin-history-next", "%cmd%", label, "%player%", name, "%page%", String.valueOf(shown + 1));
            }
        });
    }

    private List<String> complete(CommandSender sender, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(SUBCOMMANDS);
            if (!sender.hasPermission(STAFF)) options.remove("admin");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.equals(sender) && (!(sender instanceof Player s) || s.canSee(p))) options.add(p.getName());
            }
        } else if (args.length == 2 && sender instanceof Player p
                && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny"))) {
            options.addAll(trades.requesters(p));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("toggle")) {
            options.addAll(List.of("on", "off"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("admin") && sender.hasPermission(STAFF)) {
            options.addAll(ADMIN_SUBCOMMANDS);
            if (!sender.hasPermission(ADMIN)) options.remove("reload");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("admin") && sender.hasPermission(STAFF)
                && (args[1].equalsIgnoreCase("history") || args[1].equalsIgnoreCase("cancel"))) {
            for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }

    // ---------------------------------------------------------------- accessors

    TradeHistory history() {
        return history;
    }
}
