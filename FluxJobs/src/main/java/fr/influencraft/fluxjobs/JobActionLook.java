package fr.influencraft.fluxjobs;

import fr.influencraft.flux.api.fishing.Fishing;
import fr.influencraft.flux.api.jobs.JobAction;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Icon and name of a paid action of a job, shared by every menu that lists them ({@link JobMenu},
 * {@link JobBlockBreakdownMenu}): a block shows the block, a mob its spawn egg, a tier of fish one of its fish (the carp
 * when the tier has one), anything else the icon of its trigger. Names are sent to be translated by each client.
 */
final class JobActionLook {

    /** Icon (to be dressed by the menu) and name of a line. */
    record Look(ItemStack icon, String name) {
    }

    private static final Map<String, Material> TRIGGER_ICONS = Map.ofEntries(
            Map.entry("mine_block", Material.STONE_PICKAXE), Map.entry("mine_block_cascade", Material.STONE_PICKAXE),
            Map.entry("catch_fish", Material.FISHING_ROD), Map.entry("kill", Material.IRON_SWORD),
            Map.entry("place_block", Material.BRICKS), Map.entry("smelt", Material.FURNACE),
            Map.entry("brew", Material.BREWING_STAND), Map.entry("enchant_item", Material.ENCHANTED_BOOK));

    // crop blocks whose block id is not an item ("POTATOES isn't an item")
    private static final Map<String, Material> CROP_BLOCK_TO_ITEM = Map.of(
            "potatoes", Material.POTATO, "carrots", Material.CARROT, "beetroots", Material.BEETROOT, "cocoa", Material.COCOA_BEANS);

    private JobActionLook() {
    }

    static Look of(Player player, JobAction a) {
        String label = a.label() != null ? a.label() : Lang.triggerLabel(a.trigger());
        if (a.targets().isEmpty()) return new Look(new ItemStack(TRIGGER_ICONS.getOrDefault(a.trigger(), Material.PAPER)), label);
        if (a.trigger().equals("catch_fish")) return new Look(fish(player, a.targets()), label);
        if (a.trigger().equals("kill") && a.targets().size() == 1) {
            EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(a.targets().get(0).toLowerCase(Locale.ROOT)));
            if (type != null) return new Look(new ItemStack(egg(type)), "<lang:" + type.translationKey() + ">");
        }
        Material block = block(a.targets().get(0));
        return block == Material.BARRIER ? new Look(new ItemStack(block), a.targets().get(0))
                : new Look(new ItemStack(block), "<lang:" + block.translationKey() + ">");
    }

    /** One fish of the tier: its carp when it has one ("common_carp_fish"), else its first fish, else a rod. */
    private static ItemStack fish(Player player, List<String> fish) {
        String id = fish.stream().filter(f -> f.contains("_carp_")).findFirst().orElse(fish.get(0));
        ItemStack item = Fishing.item(player, id);
        if (item != null) return item.clone();
        Material vanilla = Material.matchMaterial(id); // vanilla catches (cod, salmon...)
        return new ItemStack(vanilla != null && vanilla.isItem() ? vanilla : Material.FISHING_ROD);
    }

    /** Spawn egg of the mob, the trigger's sword for a mob without one (illusioner). */
    private static Material egg(EntityType type) {
        Material egg = Material.matchMaterial(type.getKey().getKey() + "_spawn_egg");
        return egg != null && egg.isItem() ? egg : TRIGGER_ICONS.get("kill");
    }

    private static Material block(String blockId) {
        String id = blockId.toLowerCase(Locale.ROOT);
        Material mapped = CROP_BLOCK_TO_ITEM.get(id);
        if (mapped != null) return mapped;
        Material material = Material.matchMaterial(id);
        return material == null || !material.isItem() ? Material.BARRIER : material;
    }
}
