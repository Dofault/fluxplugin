package fr.influencraft.fluxcasino;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.fluxcasino.games.Games;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Casino chips: balance and bet per player (SQLite, every movement logged), exchange against the main currency at
 * the configured prices, the {@code chips} currency of the suite, and the casino games played in Flux menus
 * (Plinko, slot machine, roulette).
 *
 * <pre>
 *   /chips                                balance and bet
 *   /chips bet +N|-N|N|min|max           changes the bet (game buttons, silent)
 *   /chips buy N                          N chips for N x buy-price (when exchange.buy-enabled)
 *   /chips sell N|all                     N chips for N x sell-price
 *   /chips admin give|take|set|history player [N]
 * </pre>
 * French sub-commands (mise, acheter, vendre, tout, historique) are accepted too.
 */
public final class FluxCasino extends FluxPlugin implements Listener {

    private Store store;
    private Games games;

    @Override
    protected List<String> formerNames() {
        return List.of("InfluCasino");
    }

    @Override
    protected void enable() {
        try {
            store = new Store(getDataFolder(), getConfig().getLong("bet.default", 10));
        } catch (Exception e) {
            getLogger().severe("Chip database unavailable, plugin disabled: " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Casino.init(this);
        currency(new Chips());
        placeholders("influcasino", this::placeholder);
        games = Games.enable(this);
        command("chips")
                .description("Casino chips: balance, bet, exchange.")
                .usage(messages().raw("usage"))
                .aliases("jetons", "jeton")
                .permission("influcasino.use")
                .executor(this::onCommand)
                .completer(this::complete)
                .register();
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    protected void disable() {
        if (games != null) games.shutdown();
        if (store != null) store.close();
    }

    Store store() {
        return store;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        store.forget(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ bet

    long betMin() {
        return Math.max(1, getConfig().getLong("bet.min", 10));
    }

    long betMax() {
        return Math.max(betMin(), getConfig().getLong("bet.max", 1000));
    }

    long clampBet(long bet) {
        return Math.max(betMin(), Math.min(betMax(), bet));
    }

    private void changeBet(Player player, String arg) {
        long current = Casino.bet(player);
        long next;
        String a = arg.toLowerCase(Locale.ROOT);
        try {
            next = switch (a) {
                case "min" -> betMin();
                case "max" -> betMax();
                default -> a.startsWith("+") ? current + Long.parseLong(a.substring(1))
                        : a.startsWith("-") ? current - Long.parseLong(a.substring(1)) : Long.parseLong(a);
            };
        } catch (NumberFormatException e) {
            messages().send(player, "invalid-amount");
            return;
        }
        store.setBet(player.getUniqueId(), player.getName(), clampBet(next));
    }

    // ------------------------------------------------------------------ exchange

    private boolean exchangeOpen(Player player) {
        if (!money().available() || !getConfig().getBoolean("exchange.enabled", false)) {
            messages().send(player, "exchange-disabled");
            return false;
        }
        return true;
    }

    private long amount(Player player, String arg, long all) {
        long max = getConfig().getLong("exchange.max-per-operation", 100000);
        long n = arg.equalsIgnoreCase("all") || arg.equalsIgnoreCase("tout") ? Math.min(all, max) : Format.parseAmount(arg);
        if (n <= 0 || n > max) {
            messages().send(player, "invalid-amount");
            return -1;
        }
        return n;
    }

    /** Buying: the money is taken first, then the chips credited (refunded if writing fails). */
    private void buy(Player player, String arg) {
        if (!exchangeOpen(player)) return;
        if (!getConfig().getBoolean("exchange.buy-enabled", false)) {
            messages().send(player, "buy-disabled");
            return;
        }
        long n = amount(player, arg, 0);
        if (n < 0) return;
        double price = n * getConfig().getDouble("exchange.buy-price", 10);
        if (!money().take(player, price)) {
            messages().send(player, "not-enough-money", "%price%", Format.number(price));
            return;
        }
        try {
            store.add(player.getUniqueId(), player.getName(), n, "buy (" + Format.number(price) + ")");
        } catch (RuntimeException e) {
            money().give(player, price);
            throw e;
        }
        messages().send(player, "bought-money", "%price%", Format.number(price));
        messages().send(player, "bought-chips", "%amount%", Format.number(n));
    }

    /** Selling: the chips are taken first, then the money paid (chips given back if paying fails). */
    private void sell(Player player, String arg) {
        if (!exchangeOpen(player)) return;
        long n = amount(player, arg, Casino.chips(player));
        if (n < 0) return;
        double price = n * getConfig().getDouble("exchange.sell-price", 10);
        if (!store.add(player.getUniqueId(), player.getName(), -n, "sell (" + Format.number(price) + ")")) {
            messages().send(player, "not-enough-chips", "%amount%", Format.number(Casino.chips(player)));
            return;
        }
        if (!money().give(player, price)) {
            store.add(player.getUniqueId(), player.getName(), n, "sell cancelled");
            getLogger().warning("Sale of " + n + " chips by " + player.getName() + " cancelled: payment failed");
            return;
        }
        messages().send(player, "sold-chips", "%amount%", Format.number(n));
        messages().send(player, "sold-money", "%price%", Format.number(price));
    }

    // ------------------------------------------------------------------ commands

    private boolean onCommand(CommandSender sender, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("admin")) {
            if (sender.hasPermission("influcasino.admin")) admin(sender, args);
            return true;
        }
        Player player = Players.of(sender);
        if (player == null) return true;
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> messages().send(player, "balance", "%amount%", Format.number(Casino.chips(player)),
                    "%bet%", Format.number(Casino.bet(player)));
            case "bet", "mise" -> {
                if (args.length >= 2) changeBet(player, args[1]);
            }
            case "buy", "acheter" -> {
                if (args.length >= 2) buy(player, args[1]);
                else messages().send(player, "usage");
            }
            case "sell", "vendre" -> {
                if (args.length >= 2) sell(player, args[1]);
                else messages().send(player, "usage");
            }
            default -> messages().send(player, "usage");
        }
        return true;
    }

    private void admin(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages().send(sender, "admin-usage");
            return;
        }
        OfflinePlayer target = Players.find(args[2]);
        if (target == null) {
            messages().send(sender, "player-not-found", "%player%", args[2]);
            return;
        }
        String name = Players.name(target, args[2]);
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("history") || sub.equals("historique")) {
            for (String[] line : store.history(target.getUniqueId(), 15)) {
                messages().send(sender, "admin-history-line", "%date%", Format.date(Long.parseLong(line[3])),
                        "%delta%", (line[0].startsWith("-") ? "" : "+") + line[0], "%balance%", line[1], "%reason%", line[2]);
            }
            return;
        }
        long n = args.length >= 4 ? Format.parseAmount(args[3]) : -1;
        if (n < 0 && !(args.length >= 4 && args[3].equals("0"))) {
            messages().send(sender, "invalid-amount");
            return;
        }
        n = Math.max(0, n);
        long current = store.chips(target.getUniqueId());
        String by = "admin " + sender.getName();
        switch (sub) {
            case "give" -> store.add(target.getUniqueId(), name, n, by);
            case "take" -> store.add(target.getUniqueId(), name, -Math.min(n, current), by);
            case "set" -> store.add(target.getUniqueId(), name, n - current, by);
            default -> {
                messages().send(sender, "admin-usage");
                return;
            }
        }
        messages().send(sender, "admin-done", "%player%", name, "%amount%", Format.number(store.chips(target.getUniqueId())));
    }

    private List<String> complete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("bet", "buy", "sell"));
            if (sender.hasPermission("influcasino.admin")) out.add("admin");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            out.addAll(List.of("give", "take", "set", "history"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("admin")) {
            return Players.complete(args[2]);
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(s -> s.startsWith(last)).toList();
    }

    // ------------------------------------------------------------------ placeholders

    /**
     * {@code %influcasino_<params>%}: jetons (formatted balance), jetons_brut, mise, mise_min, mise_max, mise_pas,
     * mise_grand_pas (bet button steps), prix_achat, prix_vente (main currency per chip), echange (true/false).
     */
    String placeholder(OfflinePlayer player, String params) {
        switch (params) {
            case "mise_min":
                return Format.number(betMin());
            case "mise_max":
                return Format.number(betMax());
            case "mise_pas":
                return String.valueOf(getConfig().getLong("bet.step", 10));
            case "mise_grand_pas":
                return String.valueOf(getConfig().getLong("bet.big-step", 100));
            case "prix_achat":
                return Format.number(getConfig().getDouble("exchange.buy-price", 10));
            case "prix_vente":
                return Format.number(getConfig().getDouble("exchange.sell-price", 10));
            case "echange":
                return String.valueOf(money().available() && getConfig().getBoolean("exchange.enabled", false));
            default:
                break;
        }
        if (player == null) return "";
        return switch (params) {
            case "jetons" -> Format.number(Casino.chips(player));
            case "jetons_brut" -> String.valueOf(Casino.chips(player));
            case "mise" -> Format.number(Casino.bet(player));
            default -> null;
        };
    }

    /** The chips as a currency of the suite (id {@code chips}), usable by shops. */
    private final class Chips implements Currency {
        @Override
        public String id() {
            return "chips";
        }

        @Override
        public boolean available() {
            return isEnabled();
        }

        @Override
        public double balance(OfflinePlayer player) {
            return store.chips(player.getUniqueId());
        }

        @Override
        public boolean take(OfflinePlayer player, double amount) {
            long n = (long) Math.ceil(amount - 1e-9);
            return n <= 0 || store.add(player.getUniqueId(), Players.name(player, "?"), -n, "spent");
        }

        @Override
        public boolean give(OfflinePlayer player, double amount) {
            long n = (long) Math.floor(amount + 1e-9);
            return n <= 0 || store.add(player.getUniqueId(), Players.name(player, "?"), n, "received");
        }

        @Override
        public String format(double amount) {
            return fr.influencraft.flux.api.economy.Economy.format(id(), amount);
        }
    }
}
