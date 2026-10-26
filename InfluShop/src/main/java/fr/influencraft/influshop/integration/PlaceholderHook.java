package fr.influencraft.influshop.integration;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * Guards every PlaceholderAPI call behind a presence check so the plugin keeps working
 * (placeholders simply left untouched) when PlaceholderAPI is absent.
 */
public final class PlaceholderHook {

    private PlaceholderHook() {
    }

    public static boolean isEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    public static String apply(String text, OfflinePlayer player) {
        if (text == null || text.isEmpty() || !isEnabled()) {
            return text;
        }
        return PlaceholderAPI.setPlaceholders(player, text);
    }
}
