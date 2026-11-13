package fr.influencraft.fluxshop.api;

import fr.influencraft.fluxshop.autosell.AutoSellExceptionManager;

import java.util.Collection;
import java.util.UUID;

/** The players' own auto-sell exception lists, for imports (FluxMigration). */
public final class AutoSellExceptions {

    private static volatile AutoSellExceptionManager manager;

    private AutoSellExceptions() {
    }

    /** Called by FluxShop. */
    public static void bind(AutoSellExceptionManager m) {
        manager = m;
    }

    public static boolean available() {
        return manager != null;
    }

    /** Whether the player has a list of his own (else he follows the server's default). */
    public static boolean custom(UUID player) {
        AutoSellExceptionManager m = manager;
        return m != null && !m.isDefault(player);
    }

    /** Replaces the player's list. */
    public static void set(UUID player, Collection<String> keys) {
        AutoSellExceptionManager m = manager;
        if (m != null) m.replace(player, keys);
    }
}
