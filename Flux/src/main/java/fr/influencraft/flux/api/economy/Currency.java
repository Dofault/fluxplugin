package fr.influencraft.flux.api.economy;

import org.bukkit.OfflinePlayer;

/**
 * A currency the suite can charge and pay: Vault (BitBlock on Influencraft), PlayerPoints (Influence), or any
 * currency registered by a plugin (FluxCasino chips). Main thread only. Amounts below 0.01 are treated as 0.
 */
public interface Currency {

    /** Identifier used in configs: {@code vault}, {@code playerpoints}, {@code chips}... */
    String id();

    /** False when the backing plugin is missing: every operation then fails. */
    boolean available();

    double balance(OfflinePlayer player);

    default boolean has(OfflinePlayer player, double amount) {
        return amount <= 0 || balance(player) + 1e-9 >= amount;
    }

    /** Takes the amount; false (nothing taken) when the balance is too low or the operation fails. */
    boolean take(OfflinePlayer player, double amount);

    /** Pays the amount; false when the operation fails. */
    boolean give(OfflinePlayer player, double amount);

    /** Amount as shown to players, e.g. "1 200 %img_bitblock%" (format from Flux's config). */
    String format(double amount);
}
