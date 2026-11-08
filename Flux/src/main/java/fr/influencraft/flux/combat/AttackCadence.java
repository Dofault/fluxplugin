package fr.influencraft.flux.combat;

import fr.influencraft.flux.api.items.ItemLooks;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.SwingAnimation;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;

/**
 * Attack cadence of the whole server (as Wynncraft): a weapon attacks at its own pace whatever the player clicks.
 * <ul>
 *   <li>Every weapon (any item with an attack speed but mining tools: swords, axes, trident, mace) gets the vanilla item
 *   components {@code minimum_attack_charge} (the client refuses to attack before the weapon is recharged, so spam
 *   clicks do nothing) and {@code swing_animation} lasting the whole recharge (the weapon comes back slowly, slower
 *   for a slow weapon). Given through {@link ItemLooks#registerAny}, so vanilla items get it too.</li>
 *   <li>The server refuses an attack sent before the recharge (modified clients).</li>
 *   <li>The vanilla recharge indicator is hidden by the resource pack (empty sprites, pack {@code fluxui}).</li>
 * </ul>
 * Hits always land at full charge: the damage is the vanilla one of a charged hit. Spears keep their own components.
 * Turned off ({@code combat.attack-cadence.enabled: false}), items lose the components at their next refresh.
 */
public final class AttackCadence implements Listener {

    private boolean enabled = true;
    private float minimumCharge = 1f;
    private boolean slowSwing = true;

    public AttackCadence(Plugin flux) {
        ItemLooks.registerAny(flux, (id, item) -> enabled ? apply(item) : clear(item));
    }

    public void load(ConfigurationSection c) {
        enabled = c == null || c.getBoolean("enabled", true);
        minimumCharge = c == null ? 1f : (float) Math.max(0, Math.min(1, c.getDouble("minimum-charge", 1)));
        slowSwing = c == null || c.getBoolean("slow-swing", true);
        ItemLooks.refreshOnline();
    }

    /** Attack speed of an item (attacks per second, vanilla base 4 plus its modifiers), -1 when it has none. */
    static double attackSpeed(ItemStack item) {
        double add = 0;
        boolean found = false;
        if (item.isDataOverridden(DataComponentTypes.ATTRIBUTE_MODIFIERS)) {
            ItemAttributeModifiers mods = item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
            if (mods != null) {
                for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
                    if (!e.attribute().equals(Attribute.ATTACK_SPEED) || !mainHand(e.getGroup())) continue;
                    if (e.modifier().getOperation() != AttributeModifier.Operation.ADD_NUMBER) continue;
                    add += e.modifier().getAmount();
                    found = true;
                }
            }
        } else {
            for (Map.Entry<Attribute, AttributeModifier> e : item.getType().asItemType().getDefaultAttributeModifiers(EquipmentSlot.HAND).entries()) {
                if (!e.getKey().equals(Attribute.ATTACK_SPEED) || e.getValue().getOperation() != AttributeModifier.Operation.ADD_NUMBER) continue;
                add += e.getValue().getAmount();
                found = true;
            }
        }
        return found ? Math.max(0.1, 4 + add) : -1;
    }

    private static boolean mainHand(EquipmentSlotGroup g) {
        return g == EquipmentSlotGroup.MAINHAND || g == EquipmentSlotGroup.HAND || g == EquipmentSlotGroup.ANY;
    }

    /** Ticks a weapon takes to recharge. */
    static int rechargeTicks(double attackSpeed) {
        return (int) Math.max(4, Math.min(100, Math.round(20 / attackSpeed)));
    }

    /**
     * Mining tools (pickaxe, shovel, hoe) are left alone: their swing also plays while mining, it would look slow.
     * Spears keep their own components.
     */
    private static boolean excluded(ItemStack item) {
        String n = item.getType().name();
        return n.endsWith("_SPEAR") || n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE");
    }

    private boolean apply(ItemStack item) {
        if (excluded(item)) return clear(item);
        double speed = attackSpeed(item);
        if (speed < 0) return clear(item);
        boolean changed = false;
        Float charge = item.isDataOverridden(DataComponentTypes.MINIMUM_ATTACK_CHARGE) ? item.getData(DataComponentTypes.MINIMUM_ATTACK_CHARGE) : null;
        if (charge == null || Math.abs(charge - minimumCharge) > 1e-4) {
            item.setData(DataComponentTypes.MINIMUM_ATTACK_CHARGE, minimumCharge);
            changed = true;
        }
        if (slowSwing) {
            int ticks = rechargeTicks(speed);
            SwingAnimation swing = item.isDataOverridden(DataComponentTypes.SWING_ANIMATION) ? item.getData(DataComponentTypes.SWING_ANIMATION) : null;
            if (swing == null || swing.duration() != ticks || swing.type() != SwingAnimation.Animation.WHACK) {
                item.setData(DataComponentTypes.SWING_ANIMATION, SwingAnimation.swingAnimation().type(SwingAnimation.Animation.WHACK).duration(ticks).build());
                changed = true;
            }
        } else if (item.isDataOverridden(DataComponentTypes.SWING_ANIMATION)) {
            item.resetData(DataComponentTypes.SWING_ANIMATION);
            changed = true;
        }
        return changed;
    }

    /** Removes what {@link #apply} gave (rule turned off, or the item is no longer a weapon). */
    private static boolean clear(ItemStack item) {
        if (item.getType().name().endsWith("_SPEAR")) return false;
        boolean changed = false;
        if (item.isDataOverridden(DataComponentTypes.MINIMUM_ATTACK_CHARGE)) {
            item.resetData(DataComponentTypes.MINIMUM_ATTACK_CHARGE);
            changed = true;
        }
        if (item.isDataOverridden(DataComponentTypes.SWING_ANIMATION)) {
            item.resetData(DataComponentTypes.SWING_ANIMATION);
            changed = true;
        }
        return changed;
    }

    /** A hit sent before the weapon is recharged is refused (the vanilla client never sends one). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        if (!enabled || !e.willAttack()) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.isEmpty() || !hand.isDataOverridden(DataComponentTypes.MINIMUM_ATTACK_CHARGE)) return;
        if (p.getAttackCooldown() + 0.1f < minimumCharge) e.setCancelled(true);
    }
}
