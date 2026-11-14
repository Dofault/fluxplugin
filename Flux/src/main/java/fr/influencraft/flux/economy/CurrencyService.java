package fr.influencraft.flux.economy;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Ledger;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.ListProvider;
import fr.influencraft.flux.api.list.ListQuery;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.text.Messages;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The money of the suite, owned by Flux: the currencies kept by Flux ({@code currencies.<id>.type: flux}, their
 * admin command; the one marked {@code vault: true} is the server's Vault economy, see {@link VaultProvider} and
 * {@link MoneyCommands}), the account history ({@link LedgerService}), the standard gain / loss message
 * ({@link MoneyNotifier}), the account menu ({@code /compte}, menu {@code compte}, lists {@code ledger},
 * {@code ledger_summary} and the main-menu button {@code account_button}) and placeholders
 * {@code %flux_balance_<currency>%} (formatted) / {@code %flux_balance_<currency>_raw%}.
 */
public final class CurrencyService implements Listener {

    public static final String MENU = "compte";
    public static final String LIST = "ledger";

    private final JavaPlugin flux;
    private final Supplier<Messages> messages;
    private final LedgerService ledger;
    private final MoneyNotifier notifier;
    private final OriginResolver commandOrigins = new OriginResolver();
    private final Map<String, StoredCurrency> stored = new HashMap<>();
    /** Whose history a viewer looks at (staff looking at another player), else the viewer himself. */
    private final Map<UUID, UUID> subjects = new HashMap<>();
    private List<String> shown = List.of("vault", "influence", "chips");
    /** The Flux currency served to Vault (the server's money), or null. */
    private StoredCurrency vault;
    private MoneyCommands money;
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Flux-balances");
        t.setDaemon(true);
        return t;
    });

    public CurrencyService(JavaPlugin flux, Supplier<Messages> messages) {
        this.flux = flux;
        this.messages = messages;
        this.ledger = new LedgerService(flux);
        this.notifier = new MoneyNotifier(messages);
    }

    public void start() {
        try {
            StoredCurrency.createTable();
        } catch (SQLException e) {
            flux.getLogger().severe("Currencies kept by Flux unavailable (table flux_balances): " + e.getMessage());
        }
        ConfigurationSection currencies = flux.getConfig().getConfigurationSection("currencies");
        String vaultId = vaultCurrencyId(flux);
        if (currencies != null) {
            for (String id : currencies.getKeys(false)) {
                ConfigurationSection c = currencies.getConfigurationSection(id);
                if (c == null || !"flux".equalsIgnoreCase(c.getString("type", ""))) continue;
                StoredCurrency currency = new StoredCurrency(id.toLowerCase(Locale.ROOT), c.getInt("decimals", 0),
                        "batched".equalsIgnoreCase(c.getString("save", "immediate")), c.getDouble("start-balance", 0), c.getDouble("max-balance", 0));
                stored.put(currency.id(), currency);
                Economy.register(flux, currency);
                if (currency.id().equals(vaultId)) {
                    // the server's money: served to Vault, its commands are /money, /pay and /baltop
                    if (vaultInstalled()) VaultProvider.register(flux);
                    Economy.alias("vault", currency.id());
                    vault = currency;
                    money = new MoneyCommands(flux, messages, this, currency);
                    money.register();
                } else if (c.getBoolean("command", true)) {
                    registerCommand(currency);
                }
            }
        }
        // no money of our own: whatever economy another plugin gives to Vault is the "vault" currency
        if (vault == null && vaultInstalled()) Economy.registerBuiltIns(flux);
        bindVault();
        if (money != null) money.configure(flux.getConfig().getConfigurationSection("money"));
        writer.scheduleWithFixedDelay(this::flush, 1, 1, TimeUnit.SECONDS);
        Economy.configureDisplay(notifier::name, notifier);
        ledger.start();
        Bukkit.getPluginManager().registerEvents(this, flux);
        Lists.register(flux, LIST, new History());
        Lists.registerItem(flux, "ledger_summary", this::summary);
        Lists.registerItem(flux, "account_button", this::accountButton);
        Lists.registerItem(flux, "ledger_kind", this::kindButton);
        Commands.create(flux, "account").description("Your account: balances and history of your money.")
                .usage("/account [player]").executor(this::accountCommand)
                .completer((s, a) -> a.length == 1 && s.hasPermission("flux.account.others") ? Players.complete(a[0]) : List.of())
                .register();
        for (Player p : Bukkit.getOnlinePlayers()) preload(p.getUniqueId());
    }

    /**
     * Id of the Flux currency that is the server's money ({@code currencies.<id>.vault: true}, type flux): served to
     * Vault when Vault is installed, with the commands /money, /pay and /baltop. Null when the money is another
     * plugin's. Read from the config, usable before Flux starts.
     */
    public static String vaultCurrencyId(JavaPlugin flux) {
        ConfigurationSection currencies = flux.getConfig().getConfigurationSection("currencies");
        if (currencies == null) return null;
        for (String id : currencies.getKeys(false)) {
            ConfigurationSection c = currencies.getConfigurationSection(id);
            if (c != null && "flux".equalsIgnoreCase(c.getString("type", "")) && c.getBoolean("vault", false)) return id.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /** The Vault plugin is there: only then may {@link VaultProvider} (which implements its interface) be loaded. */
    public static boolean vaultInstalled() {
        return Bukkit.getPluginManager().getPlugin("Vault") != null;
    }

    /** How Vault presents our money to other plugins (re-read on reload). */
    private void bindVault() {
        if (vault == null || !vaultInstalled()) return;
        ConfigurationSection c = flux.getConfig().getConfigurationSection("currencies." + vault.id());
        String name = notifier.name(vault.id());
        VaultProvider.bind(vault, c == null ? null : c.getString("vault-format"), name, name);
    }

    /** Once every plugin is enabled: says so loudly when another plugin took Vault's economy over ours. */
    public void checkVault() {
        if (vault == null || !vaultInstalled()) return;
        if (VaultProvider.active()) {
            flux.getLogger().info("Vault economy: Flux (currency " + vault.id() + ")");
        } else {
            flux.getLogger().severe("Vault's economy is served by " + VaultProvider.activeProviderName() + ", not by Flux: third-party plugins pay into "
                    + "another money than /money shows. Turn that plugin's economy off (CMI: Economy.Enabled: false in its config.yml).");
        }
    }

    /** Database thread: batched balances written (every second). */
    private void flush() {
        for (StoredCurrency c : stored.values()) {
            try {
                c.flush();
            } catch (RuntimeException e) {
                flux.getLogger().warning("Balances of " + c.id() + " not written: " + e);
            }
        }
    }

    /** Writes the batched balances now (before reading the table: leaderboard, migration check). Blocks. */
    public void flushNow() {
        try {
            writer.submit(this::flush).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | java.util.concurrent.RejectedExecutionException e) {
            flux.getLogger().warning("Balances not written on request: " + e);
        }
    }

    public void configure(ConfigurationSection config, ConfigurationSection style) {
        ConfigurationSection l = config.getConfigurationSection("ledger");
        ledger.configure(l);
        commandOrigins.configure(l);
        if (l != null && l.isList("currencies")) shown = List.copyOf(l.getStringList("currencies"));
        notifier.configure(style);
        bindVault();
        if (money != null) money.configure(config.getConfigurationSection("money"));
    }

    public void stop() {
        // last balances written before the database closes; the writer thread first, so nothing runs beside it
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) flux.getLogger().warning("Balances: the writer did not stop in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flush();
        if (vaultInstalled()) VaultProvider.unbind();
        ledger.stop();
    }

    public LedgerService ledger() {
        return ledger;
    }

    public MoneyNotifier notifier() {
        return notifier;
    }

    // ------------------------------------------------------------------ balances cache

    private void preload(UUID uuid) {
        for (StoredCurrency c : stored.values()) {
            try {
                c.preload(uuid);
            } catch (RuntimeException e) {
                flux.getLogger().warning(e.getMessage());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) preload(event.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (StoredCurrency c : stored.values()) c.forget(id);
        UUID subject = subjects.remove(id);
        if (subject != null) ledger.release(subject);
    }

    // ------------------------------------------------------------------ admin command of a Flux currency

    /**
     * {@code /<currency>}: own balance; {@code look <player>}, {@code give|take|set <player> <amount>} with
     * {@code flux.currency.<id>.admin}. The same syntax as PlayerPoints' {@code /points}, kept by the compat command.
     */
    private void registerCommand(StoredCurrency currency) {
        Commands.create(flux, currency.id()).description("Your " + currency.id() + " balance.")
                .usage("/" + currency.id() + " [look|give|take|set] [player] [amount]")
                .executor((sender, label, args) -> currencyCommand(currency, sender, args))
                .completer((sender, args) -> currencyComplete(currency, sender, args))
                .register();
    }

    public boolean currencyCommand(StoredCurrency currency, CommandSender sender, String[] args) {
        Messages m = messages.get();
        String sub = args.length == 0 ? "me" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("me") || !sender.hasPermission("flux.currency." + currency.id() + ".admin")) {
            Player p = Players.of(sender);
            if (p != null) m.send(p, "money.balance", "%amount%", currency.format(currency.balance(p)), "%name%", notifier.name(currency.id()));
            return true;
        }
        OfflinePlayer target = args.length >= 2 ? Players.find(args[1]) : null;
        if (target == null) {
            m.send(sender, "money.usage", "%command%", currency.id());
            return true;
        }
        String name = Players.name(target, args[1]);
        if (sub.equals("look")) {
            m.send(sender, "money.balance-other", "%player%", name, "%amount%", currency.format(currency.balance(target)));
            return true;
        }
        double amount = args.length >= 3 ? parse(args[2]) : -1;
        if (amount < 0) {
            m.send(sender, "money.usage", "%command%", currency.id());
            return true;
        }
        // the plugin that dispatched the command (a pass reward, a crate) is the origin, else the staff command
        Origin origin = Ledger.current() != null ? Ledger.current() : commandOrigins.resolve();
        if (origin.unknown()) origin = Origin.of(flux, "command").detail(sender.getName());
        boolean done = Ledger.within(origin, () -> switch (sub) {
            case "give" -> currency.give(target, amount);
            case "take" -> currency.take(target, Math.min(amount, currency.balance(target)));
            case "set" -> currency.set(target, amount);
            default -> false;
        });
        if (!done && !List.of("give", "take", "set").contains(sub)) {
            m.send(sender, "money.usage", "%command%", currency.id());
            return true;
        }
        if (done && !sub.equals("set") && origin.plugin().equals(flux.getName()) && origin.key().equals("command")) {
            // typed by a staff member: the player sees what happened (a reward announces itself)
            Economy.notify(target, currency, sub.equals("give") ? amount : -amount, origin);
        }
        m.send(sender, done ? "money.admin-done" : "money.admin-failed", "%player%", name, "%amount%", currency.format(currency.balance(target)));
        return true;
    }

    static double parse(String text) {
        try {
            double v = Double.parseDouble(text.replace(',', '.'));
            return v < 0 || Double.isNaN(v) ? -1 : v;
        } catch (NumberFormatException e) {
            long n = Format.parseAmount(text);
            return n < 0 ? -1 : n;
        }
    }

    public List<String> currencyComplete(StoredCurrency currency, CommandSender sender, String[] args) {
        if (!sender.hasPermission("flux.currency." + currency.id() + ".admin")) return List.of();
        if (args.length == 1) return filter(List.of("give", "take", "set", "look"), args[0]);
        if (args.length == 2) return Players.complete(args[1]);
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(p)).toList();
    }

    public StoredCurrency stored(String id) {
        return stored.get(id);
    }

    // ------------------------------------------------------------------ account menu

    private boolean accountCommand(CommandSender sender, String label, String[] args) {
        Player viewer = Players.of(sender);
        if (viewer == null) return true;
        UUID subject = viewer.getUniqueId();
        if (args.length >= 1 && viewer.hasPermission("flux.account.others")) {
            OfflinePlayer other = Players.find(args[0]);
            if (other == null) {
                messages.get().send(viewer, "money.player-not-found", "%player%", args[0]);
                return true;
            }
            subject = other.getUniqueId();
        }
        open(viewer, subject);
        return true;
    }

    /** Opens the account menu on a player's history (loaded first when not in memory). */
    public void open(Player viewer, UUID subject) {
        UUID previous = subjects.put(viewer.getUniqueId(), subject);
        if (previous != null && !previous.equals(subject)) ledger.release(previous);
        Lists.reset(viewer, LIST);
        ledger.history(subject).thenRun(() -> Bukkit.getScheduler().runTask(flux, () -> {
            if (!viewer.isOnline()) return;
            if (FluxApi.hasMenu(MENU)) FluxApi.open(viewer, MENU);
            else messages.get().send(viewer, "money.no-menu");
        }));
    }

    private UUID subject(Player viewer) {
        return subjects.getOrDefault(viewer.getUniqueId(), viewer.getUniqueId());
    }

    private List<Currency> shownCurrencies() {
        List<Currency> out = new ArrayList<>();
        for (String id : shown) {
            Currency c = Economy.get(id);
            if (c != null && c.available() && !out.contains(c)) out.add(c);
        }
        return out;
    }

    /** List {@code ledger}: filter = currency, sort = all / gains / losses. */
    private final class History implements ListProvider {
        @Override
        public List<ListEntry> entries(ListQuery query) {
            List<Currency> currencies = shownCurrencies();
            if (currencies.isEmpty()) return List.of();
            Currency currency = currencies.get(Math.floorMod(query.filter(), currencies.size()));
            List<Ledger.Line> lines = ledger.cached(subject(query.viewer()));
            if (lines == null) return List.of();
            List<ListEntry> out = new ArrayList<>();
            for (Ledger.Line line : lines) {
                if (!line.currency().equals(currency.id())) continue;
                if (query.sort() == 1 && !line.gain() || query.sort() == 2 && line.gain()) continue;
                out.add(entry(query.viewer(), currency, line));
            }
            return out;
        }

        @Override
        public List<String> filters() {
            return shownCurrencies().stream().map(c -> notifier.name(c.id())).toList();
        }

        @Override
        public List<String> sorts() {
            Messages m = messages.get();
            return List.of(m.raw("money.menu.all"), m.raw("money.menu.gains"), m.raw("money.menu.losses"));
        }
    }

    private ListEntry entry(Player viewer, Currency currency, Ledger.Line line) {
        Messages m = messages.get();
        String sign = line.gain() ? "&#2ECF7B+" : "&#FF5C5C-";
        String title = sign + MoneyNotifier.number(line.amount()) + " " + notifier.color(currency.id()) + notifier.name(currency.id());
        Lore lore = Lore.create().kv(m.raw("money.menu.origin"), Ledger.label(line.origin()));
        if (line.origin().detail() != null) lore.kv(m.raw("money.menu.detail"), line.origin().detail());
        if (line.count() > 1) lore.kv(m.raw("money.menu.count"), Format.number(line.count()));
        String from = Format.date(line.first()), to = Format.date(line.last());
        if (ledger.hourly(line.origin())) {
            // jobs pay on every action: one line per clock hour, shown as that hour ("12/11 à 18h")
            lore.kv(m.raw("money.menu.hour"), new java.text.SimpleDateFormat(m.raw("money.menu.hour-format"), java.util.Locale.FRANCE)
                    .format(new java.util.Date(line.first())));
        } else if (!from.equals(to)) {
            lore.kv(m.raw("money.menu.period"), from + " → " + to);
        } else {
            lore.kv(m.raw("money.menu.date"), Format.date(line.last()));
        }
        lore.kv(m.raw("money.menu.balance"), currency.format(line.balance()));
        // the icon is the currency's, whatever the origin: an origin is only a label
        ItemStack item = Items.of(Economy.icon(currency.id()), title, lore.build(), viewer);
        return ListEntry.of(String.valueOf(line.id())).item(item)
                .set("origin", Ledger.label(line.origin())).set("detail", line.origin().detail());
    }

    /** Icon of the account menu: balances, and the last 7 days of the chosen currency (in, out, main source). */
    private ItemStack summary(Player viewer) {
        Messages m = messages.get();
        UUID subject = subject(viewer);
        OfflinePlayer who = Bukkit.getOfflinePlayer(subject);
        Lore lore = Lore.create().description(m.raw(subject.equals(viewer.getUniqueId()) ? "money.menu.summary" : "money.menu.summary-other",
                "%player%", Players.name(who, "?")));
        List<Currency> currencies = shownCurrencies();
        for (Currency c : currencies) lore.kv(notifier.name(c.id()), c.format(c.balance(who)));
        if (!currencies.isEmpty()) {
            Currency current = currencies.get(Math.floorMod(Lists.state(viewer, LIST).filter(), currencies.size()));
            long since = System.currentTimeMillis() - 7 * 86_400_000L;
            double in = 0, out = 0;
            Map<String, Double> bySource = new HashMap<>();
            List<Ledger.Line> lines = ledger.cached(subject);
            if (lines != null) {
                for (Ledger.Line l : lines) {
                    if (!l.currency().equals(current.id()) || l.last() < since) continue;
                    if (l.gain()) {
                        in += l.amount();
                        bySource.merge(Ledger.label(l.origin()), l.amount(), Double::sum);
                    } else {
                        out -= l.amount();
                    }
                }
            }
            lore.info(m.raw("money.menu.week", "%name%", notifier.name(current.id())));
            lore.kv(m.raw("money.menu.week-in"), "&#2ECF7B+" + MoneyNotifier.number(in));
            lore.kv(m.raw("money.menu.week-out"), "&#FF5C5C-" + MoneyNotifier.number(out));
            bySource.entrySet().stream().max(Map.Entry.comparingByValue())
                    .ifPresent(best -> lore.kv(m.raw("money.menu.week-best"), best.getKey()));
        }
        return Items.of("itemsadder:influitems:bitblock", m.raw("money.menu.title"), lore.build(), viewer);
    }

    /** Gains / expenses button: the sorts of the list in the filter format (left = next, right = previous). */
    private ItemStack kindButton(Player viewer) {
        Messages m = messages.get();
        List<String> kinds = new History().sorts();
        // filter() adds the next / previous actions itself
        Lore lore = Lore.create().filter(kinds, Math.floorMod(Lists.state(viewer, LIST).sort(), kinds.size()));
        return Items.of("itemsadder:influitems:filter", m.raw("money.menu.kind"), lore.build(), viewer);
    }

    /** Button of the main menu (top right): balances, opens the account. */
    private ItemStack accountButton(Player viewer) {
        Messages m = messages.get();
        Lore lore = Lore.create().description(m.raw("money.button.description"));
        for (Currency c : shownCurrencies()) lore.kv(notifier.name(c.id()), c.format(c.balance(viewer)));
        lore.left(m.raw("money.button.open"));
        return Items.of(Economy.icon(Economy.main().id()), m.raw("money.button.name"), lore.build(), viewer);
    }

    /** {@code balance_<id>}, {@code balance_<id>_raw} (plain number), {@code balance_<id>_short} (80M, 1,2K). */
    public String placeholder(OfflinePlayer player, String params) {
        if (player == null) return "";
        if (params.endsWith("_short")) {
            Currency c = Economy.get(params.substring(0, params.length() - 6));
            return c == null ? null : Economy.compact(c.balance(player));
        }
        boolean raw = params.endsWith("_raw");
        String id = raw ? params.substring(0, params.length() - 4) : params;
        Currency c = Economy.get(id);
        if (c == null) return null;
        double v = c.balance(player);
        if (!raw) return c.format(v);
        return Math.abs(v - Math.rint(v)) < 0.005 ? String.valueOf((long) Math.rint(v)) : String.valueOf(Math.round(v * 100) / 100.0);
    }
}
