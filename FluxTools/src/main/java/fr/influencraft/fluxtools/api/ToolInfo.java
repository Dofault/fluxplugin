package fr.influencraft.fluxtools.api;

/**
 * A FluxTools tool as other plugins see it.
 *
 * @param id       tool id ({@code pioche_virale})
 * @param tier     crate tier ({@code ordinaire}, {@code tendance}, {@code virale}, {@code phenomene})
 * @param family   {@code pioche}, {@code hache}, {@code epee}...
 * @param level    current level (0 to maxLevel)
 * @param maxLevel last level of the tool (20), 0 for a tool without levels
 * @param maxXp    XP needed for the last level
 */
public record ToolInfo(String id, String tier, String family, int level, int maxLevel, double maxXp) {

    public boolean maxed() {
        return maxLevel > 0 && level >= maxLevel;
    }
}
