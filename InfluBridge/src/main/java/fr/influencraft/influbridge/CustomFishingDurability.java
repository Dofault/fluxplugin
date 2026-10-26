package fr.influencraft.influbridge;

import net.momirealms.customfishing.api.BukkitCustomFishingPlugin;
import net.momirealms.customfishing.api.mechanic.item.ItemManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Pont vers la durabilité maison de CustomFishing, utilisé par {@link RepairMenu}.
 *
 * <p>Les cannes CustomFishing (`contents/rod/default.yml`) portent une durabilité
 * <b>custom</b> qui dépasse celle du matériau vanilla (jusqu'à 160 points pour une canne
 * FISHING_ROD plafonnée à 64 côté vanilla). Le plugin la stocke dans le NBT `custom_data`
 * de l'objet, sous le compound `CustomFishing` (clés `max_dur` / `cur_dur`) — donc hors de
 * portée du PersistentDataContainer de Bukkit — et ne se sert de la barre de durabilité
 * vanilla que comme <i>affichage</i>, en y recopiant une valeur mise à l'échelle
 * (confirmé par décompilation de {@code CustomDurabilityItem.damage(int)}).</p>
 *
 * <p>Conséquence : remettre la durabilité vanilla à 0 ne répare pas une canne CustomFishing.
 * La barre se remplit, mais `cur_dur` reste au plancher et la canne se recasse au coup
 * suivant. Il faut passer par l'API du plugin ({@code ItemManager#setDamage}), qui réécrit
 * `cur_dur`, la barre vanilla et la ligne de lore de durabilité d'un seul tenant.</p>
 */
final class CustomFishingDurability {

    private CustomFishingDurability() {
    }

    private static ItemManager itemManager() {
        if (!Bukkit.getPluginManager().isPluginEnabled("CustomFishing")) {
            return null;
        }
        BukkitCustomFishingPlugin plugin = BukkitCustomFishingPlugin.getInstance();
        return plugin == null ? null : plugin.getItemManager();
    }

    /** true si l'objet porte la durabilité maison de CustomFishing (canne, appât outillé...). */
    static boolean isCustomDurabilityItem(ItemStack item) {
        ItemManager manager = itemManager();
        if (manager == null || item == null) {
            return false;
        }
        try {
            return manager.hasCustomMaxDamage(item);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * true si la canne a réellement perdu de la durabilité côté CustomFishing. On lit
     * `cur_dur` plutôt que la barre vanilla : celle-ci est une projection arrondie à
     * l'entier, qui peut rester à 0 alors que la canne a déjà encaissé quelques prises.
     */
    static boolean isDamaged(ItemStack item) {
        ItemManager manager = itemManager();
        if (manager == null) {
            return false;
        }
        try {
            int max = manager.getMaxDamage(item);
            Optional<Object> current = manager.wrap(item).getTag("CustomFishing", "cur_dur");
            if (current.isEmpty() || !(current.get() instanceof Number number)) {
                return false;
            }
            return number.intValue() < max;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Remet la canne à sa durabilité maximale (NBT `cur_dur`, barre vanilla et lore). */
    static boolean restore(Player player, ItemStack item) {
        ItemManager manager = itemManager();
        if (manager == null) {
            return false;
        }
        try {
            manager.setDamage(player, item, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
