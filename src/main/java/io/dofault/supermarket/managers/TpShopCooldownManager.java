package io.dofault.supermarket.managers;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gère le cooldown du téléport vers le shop (/supermarket tpshop), avec une
 * durée configurable par grade via la meta LuckPerms "supermarket-tpshop-cooldown"
 * (en secondes), sinon la valeur par défaut de config.yml.
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

    private boolean isLuckPermsAvailable() {
        return plugin.getServer().getPluginManager().getPlugin("LuckPerms") != null;
    }

    public long getCooldownSeconds(UUID uuid) {
        if (isLuckPermsAvailable()) {
            try {
                LuckPerms api = LuckPermsProvider.get();
                User user = api.getUserManager().getUser(uuid);
                if (user != null) {
                    String value = user.getCachedData().getMetaData().getMetaValue(META_KEY);
                    if (value != null) {
                        try {
                            return Long.parseLong(value.trim());
                        } catch (NumberFormatException e) {
                            plugin.getLogger().warning("Meta LuckPerms '" + META_KEY + "' invalide pour "
                                    + uuid + " : " + value);
                        }
                    }
                }
            } catch (IllegalStateException e) {
                // LuckPerms pas encore initialisé, on retombe sur la valeur par défaut.
            }
        }
        return defaultCooldownSeconds;
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
