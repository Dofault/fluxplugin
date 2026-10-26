package fr.influencraft.influoffrande.util;

import fr.influencraft.influoffrande.data.DataManager;
import fr.influencraft.influoffrande.model.CollectionDefinition;
import fr.influencraft.influoffrande.model.PlayerData;
import org.bukkit.entity.Player;

/**
 * A collection is visible/openable when: the player is an InfluOffrande admin, or the
 * collection has no permission requirement, or the player holds that permission
 * (typically granted per-grade via LuckPerms), or the player already completed it in the
 * past (so a later demotion never hides finished progress).
 */
public final class AccessUtil {

    private AccessUtil() {
    }

    public static boolean hasAccess(Player player, CollectionDefinition collection, DataManager dataManager) {
        if (player.hasPermission("offrandes.admin")) {
            return true;
        }
        if (!collection.hasPermissionRequirement()) {
            return true;
        }
        if (player.hasPermission(collection.permission())) {
            return true;
        }
        if (collection.items().isEmpty()) {
            // A collection with no items yet is vacuously "complete" (nothing left unmet),
            // but there's nothing the player could have actually finished — don't let that
            // leak access before the admin has added any items.
            return false;
        }
        PlayerData data = dataManager.get(player);
        return ProgressCalculator.isComplete(data, collection);
    }
}
