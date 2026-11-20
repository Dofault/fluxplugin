package fr.influencraft.flux.api.client;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The crafting recipe book of the players (the green book of the inventory and of crafting tables) turned into a
 * catalog of a plugin's own (FluxCosmetics: the cosmetics). Since 1.21.2 the book only shows what the server sends,
 * as displays: any item, with its model, name and lore, in one of the four crafting tabs, searchable by name by the
 * client itself.
 *
 * <ul>
 *   <li>While a catalog is {@link #claim claimed}, no crafting recipe reaches the book any more (furnace, smoker and
 *   blast furnace books keep theirs): only the catalog's {@link Entry entries}. Crafting itself is unchanged.</li>
 *   <li>An entry {@code available} is drawn as craftable (the "craftable only" filter keeps it), else as missing
 *   ingredients.</li>
 *   <li>A click on an entry never reaches the server's crafting: it goes to {@link Catalog#clicked}, on the server
 *   thread (shift held or not).</li>
 *   <li>The plugin calls {@link #refresh} when a player's entries changed (and on join).</li>
 * </ul>
 * Java only: Geyser builds the book of a Bedrock player from the server's recipes, never from these packets; give
 * Bedrock players another way to the catalog.
 */
public final class RecipeBook {

    /** The tabs of the crafting book (their icons are drawn by the client: bricks, redstone, axe and sword, lava). */
    public enum Tab { BUILDING, REDSTONE, EQUIPMENT, MISC }

    /**
     * One entry of the book. {@code id}: the plugin's own, 0 to 999 999, given back on a click. {@code fresh}: shown
     * with the "new" highlight (and the client's recipe toast) when it is first sent.
     */
    public record Entry(int id, ItemStack icon, Tab tab, boolean available, boolean fresh) {
        public Entry {
            if (id < 0 || id >= 1_000_000) throw new IllegalArgumentException("entry id out of range: " + id);
        }
    }

    public interface Catalog {
        /** The entries this player sees, in this order (server thread). */
        List<Entry> entries(Player player);

        /** He clicked an entry (server thread). */
        void clicked(Player player, int id, boolean shift);
    }

    public interface Service {
        /** Sends these entries in place of the ones sent before. */
        void show(Player player, List<Entry> entries);
    }

    private record Claim(Plugin plugin, Catalog catalog) {
    }

    private static volatile Service service;
    private static volatile Claim claim;
    /** Entries last sent to each player, read by the network thread when the server sends the whole book again. */
    private static final ConcurrentHashMap<UUID, List<Entry>> SENT = new ConcurrentHashMap<>();

    private RecipeBook() {
    }

    public static void bind(Service s) {
        service = s;
    }

    public static boolean available() {
        return service != null;
    }

    /** The crafting book of every player becomes this catalog (one plugin at a time: the last claim wins). */
    public static void claim(Plugin plugin, Catalog catalog) {
        claim = new Claim(plugin, catalog);
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }

    /** The plugin stops: the book gets the server's recipes again at the players' next join. */
    public static void release(Plugin plugin) {
        Claim c = claim;
        if (c != null && c.plugin().equals(plugin)) {
            claim = null;
            SENT.clear();
        }
    }

    /** True while a catalog holds the crafting book (network thread). */
    public static boolean claimed() {
        return claim != null;
    }

    /** Sends this player's entries again (server thread). Nothing for a Bedrock player. */
    public static void refresh(Player player) {
        Claim c = claim;
        Service s = service;
        if (c == null || s == null || !player.isOnline() || Clients.bedrock(player)) return;
        List<Entry> entries = List.copyOf(c.catalog().entries(player));
        s.show(player, entries);
        SENT.put(player.getUniqueId(), entries);
    }

    /** The entries last sent to this player, empty when none (network thread). */
    public static List<Entry> sent(UUID player) {
        return SENT.getOrDefault(player, List.of());
    }

    /** He clicked an entry of the catalog (server thread, called by the adapter). */
    public static void click(Player player, int id, boolean shift) {
        Claim c = claim;
        if (c != null) c.catalog().clicked(player, id, shift);
    }

    /** He left: forget what he was sent. */
    public static void forget(UUID player) {
        SENT.remove(player);
    }
}
