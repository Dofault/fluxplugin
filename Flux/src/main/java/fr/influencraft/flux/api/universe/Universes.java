package fr.influencraft.flux.api.universe;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.function.Predicate;

/**
 * Universes: groups of worlds whose players have their own, fully separate profile (inventory, armour, off hand,
 * ender chest, experience, life, hunger, effects, game mode). Every world belongs to {@link #VANILLA} unless a universe
 * claims it ({@code plugins/Flux/universes.yml}, or {@link #claim} for worlds a plugin creates, as dungeon instances).
 *
 * <p>Changing universe swaps the whole profile (saved in the database first, then swapped; safe against crashes and
 * disconnects). On top of that, every item made for a universe other than vanilla carries a mark ({@link #tag}) and an
 * item held in the wrong universe is taken away, logged and given back in its own universe (quarantine).</p>
 *
 * <pre>
 *   if (!Universes.allows(player, item)) return;             // a plugin about to hand an item over
 *   Universes.tag(item, "rpg");                                // an item made for the rpg universe
 *   Universes.claim(this, "rpg", w -> w.getName().startsWith("fd_"));
 * </pre>
 */
public final class Universes {

    public static final String VANILLA = "vanilla";
    /** Mark of the universe an item belongs to (absent = vanilla). Never rename: items already made carry it. */
    public static final NamespacedKey ITEM_KEY = new NamespacedKey("flux", "universe");

    /** Implemented by Flux. */
    public interface Backend {
        String of(Player player);

        String ofWorld(World world);

        boolean transitioning(Player player);

        void claim(Plugin plugin, String universe, Predicate<World> worlds);

        Location returnLocation(Player player, String universe);
    }

    private static volatile Backend backend;

    private Universes() {
    }

    public static void bind(Backend flux) {
        backend = flux;
    }

    /** True when Flux runs the universes. */
    public static boolean available() {
        return backend != null;
    }

    /** The universe whose profile the player carries now (vanilla when Flux is missing). */
    public static String of(Player player) {
        Backend b = backend;
        return b == null ? VANILLA : b.of(player);
    }

    /** The universe a world belongs to. */
    public static String of(World world) {
        Backend b = backend;
        return b == null || world == null ? VANILLA : b.ofWorld(world);
    }

    public static boolean isVanilla(Player player) {
        return VANILLA.equals(of(player));
    }

    /**
     * True while the player's profile is being swapped: he can do nothing (no click, no item, no damage) and no
     * plugin should give, take or read his items.
     */
    public static boolean transitioning(Player player) {
        Backend b = backend;
        return b != null && b.transitioning(player);
    }

    /** Declares worlds of a universe created by a plugin (checked on every world, cheap predicates only). */
    public static void claim(Plugin plugin, String universe, Predicate<World> worlds) {
        Backend b = backend;
        if (b != null) b.claim(plugin, universe, worlds);
    }

    /**
     * Where the player stood when he last left that universe (null when unknown). A plugin sending a player back
     * home from another universe uses it.
     */
    public static Location returnLocation(Player player, String universe) {
        Backend b = backend;
        return b == null ? null : b.returnLocation(player, universe);
    }

    /** The universe an item belongs to: its mark, vanilla when it has none. */
    public static String universeOf(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return VANILLA;
        String u = item.getItemMeta().getPersistentDataContainer().get(ITEM_KEY, PersistentDataType.STRING);
        return u == null ? VANILLA : u;
    }

    /** Marks an item as belonging to a universe (vanilla removes the mark). Returns the same item. */
    public static ItemStack tag(ItemStack item, String universe) {
        if (item == null || item.isEmpty()) return item;
        item.editMeta(m -> {
            if (universe == null || VANILLA.equals(universe)) m.getPersistentDataContainer().remove(ITEM_KEY);
            else m.getPersistentDataContainer().set(ITEM_KEY, PersistentDataType.STRING, universe);
        });
        return item;
    }

    /** Whether this item may be held by the player in his current universe (false while he is swapping). */
    public static boolean allows(Player player, ItemStack item) {
        if (item == null || item.isEmpty()) return true;
        if (transitioning(player)) return false;
        return of(player).equals(universeOf(item));
    }
}
