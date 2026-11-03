package fr.influencraft.flux.api.permission;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Group and meta values of a player from the permission plugin (LuckPerms on Influencraft), through Vault's chat
 * service: the suite reads {@code ic_rang}, {@code weight}... without depending on LuckPerms. Values are the
 * permission plugin's cache: cheap, main thread friendly. Empty / default when no permission plugin is hooked.
 */
public final class PlayerMeta {

    private PlayerMeta() {
    }

    private static net.milkbowl.vault.chat.Chat chat() {
        var rsp = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.chat.Chat.class);
        return rsp == null ? null : rsp.getProvider();
    }

    public static boolean available() {
        return chat() != null;
    }

    /** Primary group, lower case ({@code ""} when unknown). */
    public static String group(Player player) {
        var c = chat();
        String g = c == null ? null : c.getPrimaryGroup(player);
        return g == null ? "" : g.toLowerCase(java.util.Locale.ROOT);
    }

    /** A meta value ({@code ""} when unset). */
    public static String meta(Player player, String key) {
        var c = chat();
        String v = c == null ? null : c.getPlayerInfoString(player, key, "");
        return v == null ? "" : v;
    }

    public static int metaInt(Player player, String key, int fallback) {
        String v = meta(player, key).trim();
        if (v.isEmpty()) return fallback;
        try {
            return (int) Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
