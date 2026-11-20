package fr.influencraft.flux.api.pets;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Pets of the players, kept by the plugin of the suite that owns them (FluxPets), used by the others (FluxDungeons
 * hides them during a run, adopts a dungeon boss's pet on victory). Nothing is known without a provider: {@link #owned}
 * is empty.
 */
public final class Companions {

    /** A pet a player owns. */
    public record Companion(String id, String name, ItemStack icon, String model, boolean flying, int level) {
    }

    /** What the pet plugin provides. */
    public interface Provider {
        /** Pets the player adopted, his active ones first (main thread). */
        List<Companion> owned(Player player);

        /** Ids of the player's active pets, in order. */
        List<String> active(Player player);

        /** Hides (true) or shows again the player's pets that follow him (a plugin shows its own instead). */
        void suppress(UUID player, boolean hidden);

        /** The player adopts this pet (level 1, with its reveal); false when he already has it or it is unknown. */
        default boolean adopt(Player player, String pet) {
            return false;
        }

        /** The player owns this pet. */
        default boolean owns(Player player, String pet) {
            return false;
        }

        /** The card of this pet (what a duplicate gives), null when unknown. */
        default ItemStack card(String pet) {
            return null;
        }

        /** Picture of the pet for menus (its portrait), null when unknown. */
        default ItemStack portrait(String pet) {
            return null;
        }

        /** Display name of the pet (& colours), null when unknown. */
        default String name(String pet) {
            return null;
        }

        /** Ids of the pets tied to this job, from the most common to the rarest. */
        default List<String> ofJob(String job) {
            return List.of();
        }

        /**
         * The pet as a menu button for this viewer, the same in every menu: its item (sheet, effects, state) and its
         * click actions in {@code left}, {@code left2}, {@code right}, {@code right2} (empty when it has none, as for
         * a pet he has not adopted). Null when unknown.
         */
        default fr.influencraft.flux.api.list.ListEntry button(Player viewer, String pet) {
            return null;
        }
    }

    private static final List<java.util.function.Consumer<Player>> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Called when a player's pets changed (adopted, activated, level...): a menu showing them drops its cache. */
    public static void onChange(java.util.function.Consumer<Player> listener) {
        LISTENERS.add(listener);
    }

    /** The provider tells a player's pets changed. */
    public static void changed(Player player) {
        for (java.util.function.Consumer<Player> l : LISTENERS) l.accept(player);
    }

    public static List<String> ofJob(String job) {
        Provider p = provider;
        return p == null ? List.of() : p.ofJob(job);
    }

    public static fr.influencraft.flux.api.list.ListEntry button(Player viewer, String pet) {
        Provider p = provider;
        return p == null ? null : p.button(viewer, pet);
    }

    private static volatile Provider provider;

    private Companions() {
    }

    public static void provide(Provider p) {
        provider = p;
    }

    public static boolean available() {
        return provider != null;
    }

    public static List<Companion> owned(Player player) {
        Provider p = provider;
        return p == null ? List.of() : p.owned(player);
    }

    public static List<String> active(Player player) {
        Provider p = provider;
        return p == null ? List.of() : p.active(player);
    }

    public static void suppress(UUID player, boolean hidden) {
        Provider p = provider;
        if (p != null) p.suppress(player, hidden);
    }

    public static boolean adopt(Player player, String pet) {
        Provider p = provider;
        return p != null && p.adopt(player, pet);
    }

    public static boolean owns(Player player, String pet) {
        Provider p = provider;
        return p != null && p.owns(player, pet);
    }

    public static ItemStack card(String pet) {
        Provider p = provider;
        return p == null ? null : p.card(pet);
    }

    public static ItemStack portrait(String pet) {
        Provider p = provider;
        return p == null ? null : p.portrait(pet);
    }

    public static String name(String pet) {
        Provider p = provider;
        return p == null ? null : p.name(pet);
    }
}
