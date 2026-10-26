package fr.influencraft.flux.api.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Player lookups shared by the commands of the suite. */
public final class Players {

    private Players() {
    }

    /** Online player with this exact name, else a player who already joined (cached), else null. Never a web lookup. */
    public static OfflinePlayer find(String name) {
        if (name == null || name.isBlank()) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    /** The player behind a sender, following {@code execute as <player> run ...}; null for the console. */
    public static Player of(CommandSender sender) {
        if (sender instanceof ProxiedCommandSender proxied) sender = proxied.getCallee();
        return sender instanceof Player p ? p : null;
    }

    /** Names of online players starting with {@code prefix}, for tab completion. */
    public static List<String> complete(String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(p)) out.add(player.getName());
        }
        return out;
    }

    /** Name of a player, falling back to the given text when the server never saw one. */
    public static String name(OfflinePlayer player, String fallback) {
        String name = player == null ? null : player.getName();
        return name == null ? fallback : name;
    }
}
