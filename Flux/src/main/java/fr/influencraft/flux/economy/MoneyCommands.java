package fr.influencraft.flux.economy;

import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Ledger;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.flux.api.text.Messages;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Commands of the server's money (the Flux currency served to Vault), in place of an economy plugin's:
 *
 * <ul>
 *   <li>{@code /money} (balance, bal): own balance; {@code /money <player>} with {@code flux.money.others};
 *   {@code /money give|take|set <player> <amount>} with {@code flux.currency.<id>.admin}: the syntax rewards and other
 *   plugins' configs already run from the console.</li>
 *   <li>{@code /pay <player> <amount>} ({@code flux.money.pay}): transfer to a player, online or not, both sides told
 *   with the standard money line. Settings {@code money.pay} of Flux's config.</li>
 *   <li>{@code /baltop [page]} ({@code flux.money.top}): richest players, read from the table off the main thread.</li>
 * </ul>
 */
final class MoneyCommands {

    private static final Set<String> ADMIN = Set.of("give", "take", "set", "look");
    private static final int PAGE = 10;

    private final JavaPlugin flux;
    private final Supplier<Messages> messages;
    private final CurrencyService service;
    private final StoredCurrency currency;
    private volatile double payMin = 1;
    private volatile boolean payOffline = true;

    MoneyCommands(JavaPlugin flux, Supplier<Messages> messages, CurrencyService service, StoredCurrency currency) {
        this.flux = flux;
        this.messages = messages;
        this.service = service;
        this.currency = currency;
    }

    void configure(ConfigurationSection money) {
        payMin = money == null ? 1 : Math.max(0.01, money.getDouble("pay.minimum", 1));
        payOffline = money == null || money.getBoolean("pay.offline", true);
    }

    void register() {
        Commands.create(flux, "money").description("Your balance.").usage("/money [player] | give|take|set <player> <amount>")
                .aliases("balance", "bal").executor(this::money)
                .completer((sender, args) -> {
                    if (args.length == 1) {
                        List<String> out = new java.util.ArrayList<>();
                        if (sender.hasPermission(adminPermission())) {
                            for (String a : List.of("give", "take", "set")) if (a.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(a);
                        }
                        if (sender.hasPermission("flux.money.others")) out.addAll(Players.complete(args[0]));
                        return out;
                    }
                    return args.length == 2 && sender.hasPermission(adminPermission()) ? Players.complete(args[1]) : List.of();
                }).register();
        Commands.create(flux, "pay").description("Send money to a player.").usage("/pay <player> <amount>")
                .permission("flux.money.pay").executor(this::pay)
                .completer((sender, args) -> args.length == 1 ? Players.complete(args[0]) : List.of()).register();
        Commands.create(flux, "baltop").description("The richest players.").usage("/baltop [page]")
                .permission("flux.money.top").executor(this::top).register();
    }

    private String adminPermission() {
        return "flux.currency." + currency.id() + ".admin";
    }

    // ---------------------------------------------------------------- /money

    private boolean money(CommandSender sender, String label, String[] args) {
        Messages m = messages.get();
        if (args.length == 0) {
            Player p = Players.of(sender);
            if (p == null) return false;
            m.send(p, "money.balance", "%amount%", currency.format(currency.balance(p)), "%name%", currency.displayName());
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (ADMIN.contains(sub) && sender.hasPermission(adminPermission())) return service.currencyCommand(currency, sender, args);
        if (!sender.hasPermission("flux.money.others")) {
            // a player without the right asking for somebody else's balance sees his own, as before
            return money(sender, label, new String[0]);
        }
        OfflinePlayer target = Players.find(args[0]);
        if (target == null) {
            m.send(sender, "money.player-not-found", "%player%", args[0]);
            return true;
        }
        m.send(sender, "money.balance-other", "%player%", Players.name(target, args[0]), "%amount%", currency.format(currency.balance(target)));
        return true;
    }

    // ---------------------------------------------------------------- /pay

    private boolean pay(CommandSender sender, String label, String[] args) {
        Messages m = messages.get();
        Player from = Players.of(sender);
        if (from == null) {
            m.send(sender, "money.pay.players-only");
            return true;
        }
        if (args.length < 2) return false;
        OfflinePlayer to = Players.find(args[0]);
        if (to == null || !payOffline && !to.isOnline()) {
            m.send(from, "money.player-not-found", "%player%", args[0]);
            return true;
        }
        if (to.getUniqueId().equals(from.getUniqueId())) {
            m.send(from, "money.pay.self");
            return true;
        }
        double amount = Format.parseDecimal(args[1]);
        if (amount < payMin) {
            m.send(from, "money.pay.minimum", "%amount%", currency.format(payMin));
            return true;
        }
        String toName = Players.name(to, args[0]);
        Origin out = Origin.of(flux, "transfer").detail(toName).verb("given");
        Origin in = Origin.of(flux, "transfer").detail(from.getName()).verb("received");
        if (!Ledger.within(out, () -> currency.take(from, amount))) {
            m.send(from, "money.pay.not-enough", "%amount%", currency.format(amount), "%balance%", currency.format(currency.balance(from)));
            return true;
        }
        if (!Ledger.within(in, () -> currency.give(to, amount))) {
            // the receiver cannot hold more (balance limit) or the database refused: nothing is lost
            Ledger.within(Origin.of(flux, "transfer").detail(toName).verb("received"), () -> currency.give(from, amount));
            m.send(from, "money.pay.refused", "%player%", toName);
            return true;
        }
        service.notifier().notifyTransfer(from, currency, -amount, out, toName);
        service.notifier().notifyTransfer(to, currency, amount, in, from.getName());
        return true;
    }

    // ---------------------------------------------------------------- /baltop

    private boolean top(CommandSender sender, String label, String[] args) {
        int asked = 1;
        if (args.length >= 1) {
            try {
                asked = Math.max(1, Integer.parseInt(args[0]));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        int page = asked;
        UUID self = sender instanceof Player p ? p.getUniqueId() : null;
        Bukkit.getScheduler().runTaskAsynchronously(flux, () -> {
            try {
                service.flushNow();
                double[] totals = currency.totals();
                int pages = Math.max(1, (int) Math.ceil(totals[0] / PAGE));
                int shownPage = Math.min(page, pages);
                List<StoredCurrency.Rank> ranks = currency.top((shownPage - 1) * PAGE, PAGE);
                Bukkit.getScheduler().runTask(flux, () -> show(sender, self, ranks, shownPage, pages, totals[1]));
            } catch (SQLException | RuntimeException e) {
                flux.getLogger().warning("/baltop: " + e);
            }
        });
        return true;
    }

    private void show(CommandSender sender, UUID self, List<StoredCurrency.Rank> ranks, int page, int pages, double total) {
        Messages m = messages.get();
        m.send(sender, "money.top.header", "%page%", String.valueOf(page), "%pages%", String.valueOf(pages), "%total%", currency.format(total));
        int place = (page - 1) * PAGE;
        for (StoredCurrency.Rank rank : ranks) {
            place++;
            String name = rank.name() != null ? rank.name() : Players.name(Bukkit.getOfflinePlayer(rank.uuid()), "?");
            m.send(sender, rank.uuid().equals(self) ? "money.top.own" : "money.top.line", "%place%", String.valueOf(place),
                    "%player%", name, "%amount%", currency.format(rank.balance()), "%short%", Economy.compact(rank.balance()));
        }
        if (ranks.isEmpty()) m.send(sender, "money.top.empty");
    }
}
