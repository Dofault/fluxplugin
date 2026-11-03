package fr.influencraft.flux.api.economy;

import org.bukkit.OfflinePlayer;

/**
 * A currency the suite can charge and pay: Vault (BitBlock on Influencraft), or a currency kept by Flux itself
 * (Influence, casino chips: {@code currencies.<id>.type: flux} in Flux's config). Main thread only. Amounts below
 * 0.01 are treated as 0.
 *
 * <p>Always pass an {@link Origin} when the plugin knows what the money is for: it names the line of the player's
 * account history and, with {@link Origin#notifyPlayer()}, sends the standard gain / loss message. Without one the
 * movement is still recorded, under the plugin that made it.</p>
 */
public interface Currency {

    /** Identifier used in configs: {@code vault}, {@code influence}, {@code chips}... */
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

    /** Takes the amount for this origin (account history, optional message). */
    default boolean take(OfflinePlayer player, double amount, Origin origin) {
        boolean done = Ledger.within(origin, () -> take(player, amount));
        if (done && origin != null && origin.announce()) Economy.notify(player, this, -amount, origin);
        return done;
    }

    /** Pays the amount for this origin (account history, optional message). */
    default boolean give(OfflinePlayer player, double amount, Origin origin) {
        boolean done = Ledger.within(origin, () -> give(player, amount));
        if (done && origin != null && origin.announce()) Economy.notify(player, this, amount, origin);
        return done;
    }

    /** Amount as shown to players, e.g. "1 200 %img_bitblock%" (format from Flux's config). */
    String format(double amount);

    /** Name shown to players ({@code money.names.<id>} in Flux's lang), e.g. "BitBlock". */
    default String displayName() {
        return Economy.name(id());
    }
}
