package fr.influencraft.flux.api.client;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A player's hotbar used as a menu: nine items shown to him in place of what he really carries, by packets only. The
 * server keeps and handles his real inventory (nothing is taken, nothing can be lost in a crash); his client gets it
 * back when the menu ends. The plugin reads his choice with {@code PlayerItemHeldEvent} (mouse wheel, number keys, a
 * tap on a slot for a Bedrock player) and must cancel what he could do with the real items meanwhile (clicks in his
 * inventory, drop, swap).
 *
 * <p>Works for Java and Bedrock players. Used by FluxDungeons' class showcase: one class per slot.</p>
 */
public final class Hotbar {

    public interface Service {
        void show(Player player, ItemStack[] items);

        void clear(Player player);

        void bars(Player player, boolean visible);
    }

    private static volatile Service service;

    private Hotbar() {
    }

    public static void bind(Service s) {
        service = s;
    }

    public static boolean available() {
        return service != null;
    }

    /**
     * Shows these items (up to nine, null or missing = empty slot) in his hotbar until {@link #clear}. Called again,
     * it replaces them. Whatever the server sends about those slots meanwhile is rewritten on its way.
     */
    public static void show(Player player, ItemStack[] items) {
        Service s = service;
        if (s != null) s.show(player, items);
    }

    /** His real hotbar is back on his screen. */
    public static void clear(Player player) {
        Service s = service;
        if (s != null) s.clear(player);
    }

    /**
     * Takes his life, food and experience bars off his screen ({@code false}) or puts them back: his client is told
     * he plays in creative mode, the server never is, so nothing creative he tries is accepted. Only for a player who
     * cannot move or fight meanwhile (seated in a scene). Java only: nothing changes for a Bedrock player.
     */
    public static void bars(Player player, boolean visible) {
        Service s = service;
        if (s != null) s.bars(player, visible);
    }
}
