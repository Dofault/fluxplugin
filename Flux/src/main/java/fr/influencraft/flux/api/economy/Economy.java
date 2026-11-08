package fr.influencraft.flux.api.economy;

import fr.influencraft.flux.api.util.Format;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Currencies of the suite. Declared in Flux's config ({@code currencies.<id>}): {@code type: vault} is the Vault
 * economy (BitBlock, kept by CMI), {@code type: flux} a currency Flux keeps in its own database (Influence, casino
 * chips). Adding a currency is a config entry; a plugin may still add one in code with {@link #register}. The
 * display format of each currency is {@code currencies.<id>.format} ({@code %amount%} = formatted number), its name
 * {@code money.names.<id>} in Flux's lang, the look of its gain / loss messages {@code money} in style.yml.
 * {@link #main()} = the {@code currencies.main} one.
 *
 * <pre>
 *   Currency money = Economy.main();
 *   if (!money.take(player, price, Origin.of(this, "repair"))) { messages.send(player, "not-enough-money", "%price%", money.format(price)); return; }
 *   player.sendMessage(money.format(money.balance(player)));   // every balance shown through format(): one look everywhere
 * </pre>
 */
public final class Economy {

    private record Owned(Plugin owner, Currency currency) {
    }

    /** Sends the standard gain / loss message (set by Flux). */
    public interface Notifier {
        void notify(OfflinePlayer player, Currency currency, double signedAmount, Origin origin);
    }

    private static final Map<String, Owned> CURRENCIES = new ConcurrentHashMap<>();
    /** Former identifiers still accepted in configs ({@code playerpoints} = {@code influence}). */
    private static final Map<String, String> ALIASES = new ConcurrentHashMap<>();
    private static volatile String main = "vault";
    private static volatile Function<String, String> formats = id -> "%amount%";
    private static volatile Function<String, String> icons = id -> "GOLD_NUGGET";
    private static volatile Function<String, String> names = id -> id;
    private static volatile Notifier notifier = (p, c, a, o) -> {
    };
    /** True when an adapter reports every Vault balance change (CMI): the Vault currency then fires no event itself. */
    private static volatile boolean vaultObserved;

    private Economy() {
    }

    /** Called by Flux from its config. */
    public static void configure(String mainCurrency, Function<String, String> formatOfCurrency) {
        main = mainCurrency.toLowerCase(Locale.ROOT);
        formats = formatOfCurrency;
    }

    /** Called by Flux from its config ({@code currencies.<id>.icon}). */
    public static void configureIcons(Function<String, String> iconOfCurrency) {
        icons = iconOfCurrency;
    }

    /** Called by Flux: names from its lang, the message sender. */
    public static void configureDisplay(Function<String, String> nameOfCurrency, Notifier sender) {
        names = nameOfCurrency;
        notifier = sender;
    }

    /** Called by Flux's providers: an adapter fires a {@link BalanceChangeEvent} for every Vault change. */
    public static void vaultObserved(boolean observed) {
        vaultObserved = observed;
    }

    /** A former identifier of a currency, still accepted by {@link #get}. */
    public static void alias(String former, String id) {
        ALIASES.put(former.toLowerCase(Locale.ROOT), id.toLowerCase(Locale.ROOT));
    }

    /** Menu icon of a currency ({@code currencies.<id>.icon}, Flux material syntax), the same in every plugin. */
    public static String icon(String currencyId) {
        return icons.apply(resolve(currencyId));
    }

    /** Name of a currency as players read it ({@code money.names.<id>}), e.g. "BitBlock". */
    public static String name(String currencyId) {
        return names.apply(resolve(currencyId));
    }

    public static void register(Plugin owner, Currency currency) {
        CURRENCIES.put(currency.id().toLowerCase(Locale.ROOT), new Owned(owner, currency));
    }

    public static void unregisterAll(Plugin owner) {
        CURRENCIES.values().removeIf(o -> o.owner() == owner);
    }

    private static String resolve(String id) {
        String low = id.toLowerCase(Locale.ROOT);
        return CURRENCIES.containsKey(low) ? low : ALIASES.getOrDefault(low, low);
    }

    /** Currency by identifier (or former identifier), or null when unknown. */
    public static Currency get(String id) {
        Owned o = id == null ? null : CURRENCIES.get(resolve(id));
        return o == null ? null : o.currency();
    }

    /** The server's main currency ({@code currencies.main}, Vault by default). Never null. */
    public static Currency main() {
        Currency c = get(main);
        return c != null ? c : get("vault");
    }

    public static Collection<Currency> all() {
        return List.copyOf(CURRENCIES.values().stream().map(Owned::currency).toList());
    }

    /** Applies {@code currencies.<id>.format} to an amount. For implementations of {@link Currency#format}. */
    public static String format(String currencyId, double amount) {
        // whole amounts without decimals, cents kept when there are some
        String shown = Math.abs(amount - Math.rint(amount)) < 0.005 ? Format.number(Math.rint(amount)) : Format.decimal(amount);
        return formats.apply(resolve(currencyId)).replace("%amount%", shown);
    }

    /**
     * Short form of an amount for tight spots (main-menu dashboard, below the name): 950, 12K, 1,2M, 80M, 3,4Md.
     * Rounded down, so a balance never looks bigger than it is; one decimal under 10 of a unit.
     */
    public static String compact(double amount) {
        double a = Math.abs(amount);
        String sign = amount < 0 ? "-" : "";
        String[] units = {"Md", "M", "K"};
        double[] sizes = {1e9, 1e6, 1e3};
        for (int i = 0; i < units.length; i++) {
            if (a < sizes[i]) continue;
            double v = a / sizes[i];
            String shown = v < 10 ? Format.decimal(Math.floor(v * 10) / 10) : Format.number(Math.floor(v));
            if (shown.endsWith(",0") || shown.endsWith(".0")) shown = shown.substring(0, shown.length() - 2);
            return sign + shown + units[i];
        }
        return sign + Format.number(Math.floor(a));
    }

    /** Rounds to the cent, never below 0.01 for a positive amount (money rule of the suite). */
    public static double round(double amount) {
        if (amount <= 0) return 0;
        return Math.max(0.01, Math.round(amount * 100) / 100.0);
    }

    /**
     * Sends the standard gain (amount &gt; 0) or loss message of the suite to the player, when online: the look of
     * style.yml {@code money}, the words of Flux's lang {@code money}, the origin's label. Used by
     * {@link Origin#notifyPlayer()}; call it directly for a movement made outside {@link Currency}.
     */
    public static void notify(OfflinePlayer player, Currency currency, double signedAmount, Origin origin) {
        if (player != null && currency != null && Math.abs(signedAmount) >= 0.005) notifier.notify(player, currency, signedAmount, origin);
    }

    // ---------------------------------------------------------------- built-in currencies

    /**
     * Any Vault economy, looked up on every call (the provider can register late, e.g. CMI). When no adapter reports
     * Vault's balance changes ({@link #vaultObserved}), it fires the {@link BalanceChangeEvent} itself, so the
     * movements made through the suite are still recorded. When one does (CMI, events fired later on another thread),
     * each change is announced to the ledger first ({@link Ledger#expect}), so it keeps the origin of the caller.
     */
    static final class VaultCurrency implements Currency {
        private static net.milkbowl.vault.economy.Economy provider() {
            var rsp = org.bukkit.Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
            return rsp == null ? null : rsp.getProvider();
        }

        @Override
        public String id() {
            return "vault";
        }

        @Override
        public boolean available() {
            return provider() != null;
        }

        @Override
        public double balance(OfflinePlayer player) {
            var e = provider();
            return e == null ? 0 : e.getBalance(player);
        }

        @Override
        public boolean take(OfflinePlayer player, double amount) {
            var e = provider();
            if (e == null) return false;
            if (amount <= 0) return true;
            double before = vaultObserved ? 0 : e.getBalance(player);
            Runnable expected = vaultObserved ? Ledger.expect(player.getUniqueId(), id(), -amount) : null;
            boolean done = e.has(player, amount) && e.withdrawPlayer(player, amount).transactionSuccess();
            if (!done && expected != null) expected.run();
            if (done && !vaultObserved) fire(player, before, e.getBalance(player), false);
            return done;
        }

        @Override
        public boolean give(OfflinePlayer player, double amount) {
            var e = provider();
            if (e == null) return false;
            if (amount <= 0) return true;
            double before = vaultObserved ? 0 : e.getBalance(player);
            Runnable expected = vaultObserved ? Ledger.expect(player.getUniqueId(), id(), amount) : null;
            boolean done = e.depositPlayer(player, amount).transactionSuccess();
            if (!done && expected != null) expected.run();
            if (done && !vaultObserved) fire(player, before, e.getBalance(player), true);
            return done;
        }

        private void fire(OfflinePlayer player, double from, double to, boolean deposit) {
            org.bukkit.Bukkit.getPluginManager().callEvent(new BalanceChangeEvent(player.getUniqueId(), id(), from, to, deposit));
        }

        @Override
        public String format(double amount) {
            return Economy.format(id(), amount);
        }
    }

    /** Called by Flux at start-up. The Vault one is only created when Vault is installed. */
    public static void registerBuiltIns(Plugin flux) {
        if (org.bukkit.Bukkit.getPluginManager().getPlugin("Vault") != null) register(flux, new VaultCurrency());
    }
}
