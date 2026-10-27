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
 * Currencies of the suite. {@code vault} and {@code playerpoints} are provided by Flux; a plugin adds its own with
 * {@link #register}. The display format of each currency is set in Flux's config ({@code currencies.<id>.format},
 * {@code %amount%} = formatted number), with {@link #main()} = the {@code currencies.main} one.
 *
 * <pre>
 *   Currency money = Economy.main();
 *   if (!money.take(player, price)) { messages.send(player, "not-enough-money", "%price%", money.format(price)); return; }
 * </pre>
 */
public final class Economy {

    private record Owned(Plugin owner, Currency currency) {
    }

    private static final Map<String, Owned> CURRENCIES = new ConcurrentHashMap<>();
    private static volatile String main = "vault";
    private static volatile Function<String, String> formats = id -> "%amount%";

    private Economy() {
    }

    /** Called by Flux from its config. */
    public static void configure(String mainCurrency, Function<String, String> formatOfCurrency) {
        main = mainCurrency.toLowerCase(Locale.ROOT);
        formats = formatOfCurrency;
    }

    public static void register(Plugin owner, Currency currency) {
        CURRENCIES.put(currency.id().toLowerCase(Locale.ROOT), new Owned(owner, currency));
    }

    public static void unregisterAll(Plugin owner) {
        CURRENCIES.values().removeIf(o -> o.owner() == owner);
    }

    /** Currency by identifier, or null when unknown. */
    public static Currency get(String id) {
        Owned o = id == null ? null : CURRENCIES.get(id.toLowerCase(Locale.ROOT));
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
        return formats.apply(currencyId.toLowerCase(Locale.ROOT)).replace("%amount%", shown);
    }

    /** Rounds to the cent, never below 0.01 for a positive amount (money rule of the suite). */
    public static double round(double amount) {
        if (amount <= 0) return 0;
        return Math.max(0.01, Math.round(amount * 100) / 100.0);
    }

    // ---------------------------------------------------------------- built-in currencies

    /** Any Vault economy, looked up on every call (the provider can register late, e.g. CMI). */
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
            return e.has(player, amount) && e.withdrawPlayer(player, amount).transactionSuccess();
        }

        @Override
        public boolean give(OfflinePlayer player, double amount) {
            var e = provider();
            if (e == null) return false;
            return amount <= 0 || e.depositPlayer(player, amount).transactionSuccess();
        }

        @Override
        public String format(double amount) {
            return Economy.format(id(), amount);
        }
    }

    /** PlayerPoints (whole points), through its public API by reflection: no compile dependency. */
    static final class PlayerPointsCurrency implements Currency {
        private Object api;
        private java.lang.reflect.Method look, take, give;

        private boolean resolve() {
            Plugin p = org.bukkit.Bukkit.getPluginManager().getPlugin("PlayerPoints");
            if (p == null || !p.isEnabled()) return false;
            if (api != null) return true;
            try {
                Object instance = p.getClass().getMethod("getAPI").invoke(p);
                look = instance.getClass().getMethod("look", java.util.UUID.class);
                take = instance.getClass().getMethod("take", java.util.UUID.class, int.class);
                give = instance.getClass().getMethod("give", java.util.UUID.class, int.class);
                api = instance;
                return true;
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }

        @Override
        public String id() {
            return "playerpoints";
        }

        @Override
        public boolean available() {
            return resolve();
        }

        @Override
        public double balance(OfflinePlayer player) {
            try {
                return resolve() ? ((Number) look.invoke(api, player.getUniqueId())).doubleValue() : 0;
            } catch (ReflectiveOperationException e) {
                return 0;
            }
        }

        @Override
        public boolean take(OfflinePlayer player, double amount) {
            int points = (int) Math.ceil(amount - 1e-9);
            if (points <= 0) return resolve();
            try {
                return resolve() && balance(player) >= points && (boolean) take.invoke(api, player.getUniqueId(), points);
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }

        @Override
        public boolean give(OfflinePlayer player, double amount) {
            int points = (int) Math.floor(amount + 1e-9);
            if (points <= 0) return resolve();
            try {
                return resolve() && (boolean) give.invoke(api, player.getUniqueId(), points);
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }

        @Override
        public String format(double amount) {
            return Economy.format(id(), amount);
        }
    }

    /** Called by Flux at start-up. The Vault one is only created when Vault is installed. */
    public static void registerBuiltIns(Plugin flux) {
        if (org.bukkit.Bukkit.getPluginManager().getPlugin("Vault") != null) register(flux, new VaultCurrency());
        register(flux, new PlayerPointsCurrency());
    }
}
