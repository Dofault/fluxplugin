package fr.influencraft.fluxoffering.util;

import org.bukkit.entity.Player;

public final class SoundUtil {

    private SoundUtil() {
    }

    /**
     * Accepts a raw sound key ("minecraft:item.book.page_turn" or "entity.experience_orb.pickup",
     * with or without the "minecraft:" namespace) so admins can configure it freely in config.yml.
     */
    public static void play(Player player, String soundKey) {
        if (soundKey == null || soundKey.isBlank()) {
            return;
        }
        try {
            player.playSound(player.getLocation(), soundKey, 1f, 1f);
        } catch (Exception ignored) {
            // Invalid sound key in config — fail silently rather than break the GUI interaction.
        }
    }
}
