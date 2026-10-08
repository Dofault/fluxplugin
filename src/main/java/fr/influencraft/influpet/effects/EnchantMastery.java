package fr.influencraft.influpet.effects;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Chouette Arcane : un objet tenu en main gagne +1 niveau sur un de ses enchantements vanilla (espace de noms
 * minecraft, au hasard, malédictions exclues ; EcoEnchants ignorés, rien sans enchantement vanilla) tous les
 * N usages, sans jamais dépasser le niveau maximum de l'enchantement. N = value du niveau (arrondi). Usage = bloc miné avec l'outil adapté (Block#isPreferredTool, blocs posés par un joueur
 * exclus) ou coup porté à une créature (pas les porte-armures). Compteur dans l'objet (PDC influcard:usure) :
 * il suit l'objet, ne compte qu'avec le familier actif et s'arrête quand plus rien ne peut monter.
 */
final class EnchantMastery extends PetEffect {

    private final PlacedBlocks placed;
    private final NamespacedKey counterKey;

    EnchantMastery(PetEffects manager, PlacedBlocks placed) {
        super(manager, "enchant-mastery");
        this.placed = placed;
        this.counterKey = new NamespacedKey(fr.influencraft.influpet.InfluPet.LEGACY_NAMESPACE, "usure");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        if (tool.getEnchantments().isEmpty() || !event.getBlock().isPreferredTool(tool) || placed.wasPlaced(event)) {
            return;
        }
        use(event.getPlayer(), tool);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !(event.getEntity() instanceof LivingEntity)
                || event.getEntity() instanceof ArmorStand) {
            return;
        }
        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (!weapon.getEnchantments().isEmpty()) {
            use(player, weapon);
        }
    }

    private void use(Player player, ItemStack item) {
        int level = level(player);
        if (level <= 0) {
            return;
        }
        List<Enchantment> upgradable = new ArrayList<>();
        for (Map.Entry<Enchantment, Integer> e : item.getEnchantments().entrySet()) {
            // enchantements vanilla seulement (EcoEnchants : autre espace de noms)
            if (e.getKey().getKey().getNamespace().equals("minecraft") && !e.getKey().isCursed()
                    && e.getValue() < e.getKey().getMaxLevel()) {
                upgradable.add(e.getKey());
            }
        }
        if (upgradable.isEmpty()) {
            return;
        }
        int needed = Math.max(1, (int) Math.round(value(level)));
        ItemMeta meta = item.getItemMeta();
        int count = meta.getPersistentDataContainer().getOrDefault(counterKey, PersistentDataType.INTEGER, 0) + 1;
        if (count < needed) {
            meta.getPersistentDataContainer().set(counterKey, PersistentDataType.INTEGER, count);
            item.setItemMeta(meta);
            return;
        }
        Enchantment chosen = upgradable.get(ThreadLocalRandom.current().nextInt(upgradable.size()));
        int newLevel = meta.getEnchantLevel(chosen) + 1;
        meta.addEnchant(chosen, newLevel, true);
        meta.getPersistentDataContainer().remove(counterKey);
        item.setItemMeta(meta);
        actionBar(player, "message", "%enchant%",
                LegacyComponentSerializer.legacyAmpersand().serialize(chosen.displayName(newLevel)));
    }
}
