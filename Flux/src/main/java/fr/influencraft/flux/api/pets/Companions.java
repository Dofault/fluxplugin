package fr.influencraft.flux.api.pets;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Pets of the players, kept by the plugin of the suite that owns them (FluxPets), used by the others (FluxDungeons
 * takes them into a run as fighting companions). Nothing is known without a provider: {@link #owned} is empty.
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
}
