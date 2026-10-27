package fr.influencraft.flux.api.world;

import org.bukkit.block.Block;

/**
 * Whether a block was placed by a player (natural blocks give XP, rewards...), whatever plugin tracks it (eco today).
 * Without a tracker every block counts as natural.
 */
public final class PlacedBlocks {

    /** What an adapter provides. */
    public interface Provider {
        boolean playerPlaced(Block block);
    }

    private static volatile Provider provider;

    private PlacedBlocks() {
    }

    /** Called by Flux. */
    public static void use(Provider p) {
        provider = p;
    }

    public static boolean available() {
        return provider != null;
    }

    public static boolean playerPlaced(Block block) {
        Provider p = provider;
        try {
            return p != null && block != null && p.playerPlaced(block);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
