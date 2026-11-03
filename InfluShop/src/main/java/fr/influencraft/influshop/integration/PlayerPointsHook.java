package fr.influencraft.influshop.integration;

import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Bukkit;

import java.util.UUID;

/**
 * The Influence currency backend (see docs/notifications/influence.md). Flux keeps Influence since 2026-10-30
 * (currency {@code influence}): when Flux is installed the shop pays through it (account history, origin
 * {@code InfluShop:purchase}), else through PlayerPoints as before (production until Flux goes live). Deliberately
 * NOT Vault: BitBlock is the single Vault economy.
 */
public final class PlayerPointsHook {

    private PlayerPointsHook() {
    }

    private static boolean flux() {
        return Bukkit.getPluginManager().isPluginEnabled("Flux");
    }

    public static boolean isEnabled() {
        return flux() || Bukkit.getPluginManager().isPluginEnabled("PlayerPoints");
    }

    private static PlayerPointsAPI api() {
        return PlayerPoints.getInstance().getAPI();
    }

    public static int balance(UUID uuid) {
        if (flux()) return FluxInfluence.balance(uuid);
        if (!isEnabled()) {
            return 0;
        }
        return api().look(uuid);
    }

    public static boolean take(UUID uuid, int amount) {
        if (amount <= 0) return false;
        if (flux()) return FluxInfluence.take(uuid, amount);
        if (!isEnabled()) {
            return false;
        }
        return api().take(uuid, amount);
    }

    /** Only loaded when Flux is enabled (no Flux class is touched otherwise). */
    private static final class FluxInfluence {
        private static fr.influencraft.flux.api.economy.Currency currency() {
            return fr.influencraft.flux.api.economy.Economy.get("influence");
        }

        static int balance(UUID uuid) {
            var c = currency();
            return c == null ? 0 : (int) c.balance(Bukkit.getOfflinePlayer(uuid));
        }

        static boolean take(UUID uuid, int amount) {
            var c = currency();
            return c != null && c.take(Bukkit.getOfflinePlayer(uuid), amount,
                    fr.influencraft.flux.api.economy.Origin.of("InfluShop", "purchase").verb("spent"));
        }
    }
}
