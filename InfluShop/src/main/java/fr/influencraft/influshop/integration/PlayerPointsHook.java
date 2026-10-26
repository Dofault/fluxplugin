package fr.influencraft.influshop.integration;

import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Bukkit;

import java.util.UUID;

/**
 * Thin wrapper around PlayerPoints — the Influence currency backend (see
 * docs/notifications/influence.md: "/points" is aliased "/influence"). Deliberately NOT
 * Vault: PlayerPoints is registered with {@code vault: false} on purpose (BitBlock is the
 * single Vault economy), so this plugin talks to PlayerPoints directly instead.
 */
public final class PlayerPointsHook {

    private PlayerPointsHook() {
    }

    public static boolean isEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("PlayerPoints");
    }

    private static PlayerPointsAPI api() {
        return PlayerPoints.getInstance().getAPI();
    }

    public static int balance(UUID uuid) {
        if (!isEnabled()) {
            return 0;
        }
        return api().look(uuid);
    }

    public static boolean take(UUID uuid, int amount) {
        if (!isEnabled() || amount <= 0) {
            return false;
        }
        return api().take(uuid, amount);
    }
}
