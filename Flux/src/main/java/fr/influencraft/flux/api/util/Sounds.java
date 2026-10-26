package fr.influencraft.flux.api.util;

import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Sounds written in a config as {@code "<key> [volume] [pitch]"}, e.g. {@code "minecraft:ui.button.click 1 1.2"}
 * or {@code "entity.player.levelup"}. Any resource pack sound key works. Empty or {@code "none"}: no sound.
 */
public final class Sounds {

    private Sounds() {
    }

    public static void play(Player player, String spec) {
        if (player == null || spec == null) return;
        String[] parts = spec.trim().split("\\s+");
        if (parts[0].isEmpty() || parts[0].equalsIgnoreCase("none")) return;
        float volume = parts.length > 1 ? parse(parts[1], 1f) : 1f;
        float pitch = parts.length > 2 ? parse(parts[2], 1f) : 1f;
        player.playSound(player.getLocation(), parts[0].toLowerCase(Locale.ROOT), SoundCategory.MASTER, volume, pitch);
    }

    private static float parse(String s, float fallback) {
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
