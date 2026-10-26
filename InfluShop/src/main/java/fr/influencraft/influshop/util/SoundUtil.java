package fr.influencraft.influshop.util;

import org.bukkit.entity.Player;

public final class SoundUtil {

    private SoundUtil() {
    }

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
