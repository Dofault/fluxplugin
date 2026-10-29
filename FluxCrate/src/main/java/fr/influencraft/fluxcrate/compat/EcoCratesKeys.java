// COMPAT 2026-10-29: keys handed out by EcoCrates (vote, pass, shop...) stay valid - remove when no EcoCrates key
// can be left in the worlds (never sure: keep it as long as the key ids exist).
package fr.influencraft.fluxcrate.compat;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Keys made by EcoCrates carry {@code ecocrates:key_id = <key id>} (confirmed by decompiling EcoCrates 2026.38,
 * {@code CrateKeyKt.KEY_ID}). Their key ids are the crate ids of FluxCrate (ordinaire, tendance, virale,
 * phenomene), so such a key opens the same crate.
 */
public final class EcoCratesKeys {

    private static final NamespacedKey KEY_ID = new NamespacedKey("ecocrates", "key_id");

    private EcoCratesKeys() {
    }

    public static String crateOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(KEY_ID, PersistentDataType.STRING);
    }
}
