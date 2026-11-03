package fr.influencraft.fluxcommerce.api;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Public API of the chest shops of FluxCommerce for other plugins of the suite: today the import of the shops of a former plugin
 * (FluxMigration). Shops are read through Flux's {@code api.shops.ChestShops}, like every chest shop of the suite.
 */
public final class Shops {

    /**
     * A shop of a former plugin.
     *
     * @param source    where it comes from, unique ({@code quickshop:<id>}): a source already imported is skipped
     * @param item      the item traded, its amount being the bundle (items per trade)
     * @param price     price of one bundle
     * @param owner     null for a server shop (then {@code unlimited})
     * @param created   epoch milliseconds, 0 when unknown
     */
    public record Import(String source, String world, int x, int y, int z, UUID owner, String ownerName, ItemStack item,
                         double price, boolean buying, boolean unlimited, List<UUID> friends, Set<String> options, long created) {
    }

    /** Called by FluxCommerce (chest shops). */
    public interface Backend {
        /** @return true when added, false when already there (same source or a shop on that block) */
        boolean importShop(Import shop);
    }

    private static volatile Backend backend;

    private Shops() {
    }

    /** Called by FluxCommerce (chest shops). */
    public static void bind(Backend b) {
        backend = b;
    }

    public static boolean available() {
        return backend != null;
    }

    /** Main thread. */
    public static boolean importShop(Import shop) {
        Backend b = backend;
        if (b == null) throw new IllegalStateException("the chest shops of FluxCommerce are not running");
        return b.importShop(shop);
    }
}
