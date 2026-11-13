package fr.influencraft.fluxshop.managers;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;


import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Cooldown of the teleport to the shop (/supermarket tpshop): the meta "supermarket-tpshop-cooldown" of the player's
 * rank (seconds, read through Flux), else the default of config.yml.
 */
public class TpShopCooldownManager {

    private static final String META_KEY = "supermarket-tpshop-cooldown";

    private final JavaPlugin plugin;
    private final long defaultCooldownSeconds;
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public TpShopCooldownManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.defaultCooldownSeconds = plugin.getConfig().getLong("tpshop-cooldown-seconds", 1800);
    }

    /** The rank's meta "supermarket-tpshop-cooldown" (seconds, through Flux's PlayerMeta), else the config default. */
    public long getCooldownSeconds(UUID uuid) {
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null) return defaultCooldownSeconds;
        return fr.influencraft.flux.api.permission.PlayerMeta.metaInt(player, META_KEY, (int) defaultCooldownSeconds);
    }

    public long getCooldownSeconds(Player player) {
        return getCooldownSeconds(player.getUniqueId());
    }

    public long getRemainingSeconds(UUID uuid) {
        Long last = lastUse.get(uuid);
        if (last == null) {
            return 0;
        }
        long elapsed = (System.currentTimeMillis() - last) / 1000L;
        long remaining = getCooldownSeconds(uuid) - elapsed;
        return Math.max(0, remaining);
    }

    public long getRemainingSeconds(Player player) {
        return getRemainingSeconds(player.getUniqueId());
    }

    public boolean isOnCooldown(Player player) {
        return getRemainingSeconds(player) > 0;
    }

    public void markUsed(Player player) {
        lastUse.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public static String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(hours).append("h ");
        }
        if (hours > 0 || minutes > 0) {
            sb.append(minutes).append("m ");
        }
        sb.append(seconds).append("s");
        return sb.toString();
    }
}
