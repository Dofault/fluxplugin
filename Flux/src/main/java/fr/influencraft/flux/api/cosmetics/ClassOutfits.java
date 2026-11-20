package fr.influencraft.flux.api.cosmetics;

import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * The outfit that stands for an rpg class (its default look), kept by the cosmetics plugin of the suite
 * (FluxCosmetics), used by the others to dress an entity that plays a class (FluxDungeons' class showcase).
 * Nothing is known without a provider: {@link #of} is empty.
 */
public final class ClassOutfits {

    /** What the cosmetics plugin provides. */
    public interface Provider {
        /** The item of each armour slot of that class's outfit (new stacks, 3D looks applied), empty when none. */
        Map<EquipmentSlot, ItemStack> of(String classId);
    }

    private static volatile Provider provider;

    private ClassOutfits() {
    }

    public static void provide(Provider p) {
        provider = p;
    }

    /** The outfit of a class by armour slot, empty when unknown. */
    public static Map<EquipmentSlot, ItemStack> of(String classId) {
        Provider p = provider;
        Map<EquipmentSlot, ItemStack> out = p == null || classId == null ? null : p.of(classId);
        return out == null ? Map.of() : out;
    }

    /** Dresses an entity in the outfit of a class; its other slots are left alone. */
    public static void dress(LivingEntity entity, String classId) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) return;
        of(classId).forEach((slot, item) -> equipment.setItem(slot, item, true));
    }
}
