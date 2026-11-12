package fr.influencraft.flux.adapters.eco;

import com.willfp.eco.core.integrations.economy.EconomyIntegration;
import com.willfp.eco.core.integrations.economy.EconomyManager;
import fr.influencraft.flux.api.economy.Ledger;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;

/**
 * The money of the eco plugins (EcoJobs, EcoCollections, libreforge {@code give_money}) as seen by Flux, bound once the
 * server is up ({@code ServerLoadEvent}, after CMI joined Vault).
 *
 * <ul>
 *   <li>Start-up race (2026-08-26): eco hooks Vault once during its own enable, before CMIEInjector hooks CMI into
 *   Vault: eco then had no economy and every {@code give_money} did nothing, silently. Binding after start fixes it.</li>
 *   <li>Origins (2026-11-12): CMI fires its balance events on another thread, so the account history could not tell
 *   who paid: every job payment was "Autre". Each deposit or withdrawal is announced to the ledger first
 *   ({@link Ledger#expect}) under the origin of the nearest paying plugin of the call stack (EcoJobs...).</li>
 * </ul>
 * The integration is named "Vault": eco's registry is keyed by name, so it replaces eco's own Vault integration.
 */
public final class EcoVaultBridge {

    /** Plugins that only carry the payment (lower case): the payer is the first other plugin up the call stack. */
    private static final java.util.Set<String> CARRIERS = java.util.Set.of("eco", "libreforge", "flux", "vault", "vaultunlocked", "cmi", "cmilib");

    private static final ClassValue<String> PLUGIN_OF = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            try {
                return org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(type).getName();
            } catch (IllegalArgumentException | IllegalStateException e) {
                return "";
            }
        }
    };

    private EcoVaultBridge() {
    }

    /**
     * A payment of an eco plugin, its origin named after the nearest paying plugin (EcoJobs for a job, even when the
     * kill came from FluxDungeons' combat further up the stack), announced before the change.
     */
    private static boolean move(OfflinePlayer player, double delta, java.util.function.BooleanSupplier change) {
        String payer = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
                .map(f -> PLUGIN_OF.get(f.getDeclaringClass()))
                .filter(n -> !n.isEmpty() && !CARRIERS.contains(n.toLowerCase(java.util.Locale.ROOT)))
                .findFirst().orElse(null));
        java.util.function.BooleanSupplier act = () -> {
            Runnable cancel = Ledger.expect(player.getUniqueId(), "vault", delta);
            boolean done = change.getAsBoolean();
            if (!done) cancel.run();
            return done;
        };
        if (payer == null) return act.getAsBoolean();
        return Ledger.within(fr.influencraft.flux.api.economy.Origin.parse(payer), act::getAsBoolean);
    }

    public static void bind() {
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null) return;
        Economy economy = registration.getProvider();
        EconomyManager.register(new EconomyIntegration() {
            @Override
            public String getPluginName() {
                return "Vault";
            }

            @Override
            public boolean hasAmount(OfflinePlayer player, BigDecimal amount) {
                return economy.has(player, amount.doubleValue());
            }

            @Override
            public boolean giveMoney(OfflinePlayer player, BigDecimal amount) {
                return move(player, amount.doubleValue(), () -> economy.depositPlayer(player, amount.doubleValue()).transactionSuccess());
            }

            @Override
            public boolean removeMoney(OfflinePlayer player, BigDecimal amount) {
                return move(player, -amount.doubleValue(), () -> economy.withdrawPlayer(player, amount.doubleValue()).transactionSuccess());
            }

            @Override
            public BigDecimal getExactBalance(OfflinePlayer player) {
                return new BigDecimal(String.valueOf(economy.getBalance(player)));
            }
        });
    }
}
