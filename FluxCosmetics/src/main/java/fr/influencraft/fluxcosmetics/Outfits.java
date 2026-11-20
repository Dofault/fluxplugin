package fr.influencraft.fluxcosmetics;

import fr.influencraft.flux.api.client.Appearance;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The cosmetics and what each player wears. A worn cosmetic is an item drawn in one equipment slot over his real one
 * ({@link Appearance}): the others see it, he sees it too (third person, inventory) and so does the wardrobe's
 * mannequin; his real armour never changes.
 *
 * <p>First version (2026-11-20): the cosmetics are those of the config, "unlocked" for everybody or nobody, what a
 * player wears is kept in memory only (Flux's database later), and they are put on with an admin command until the
 * wardrobe gets its menu (the recipe book of the inventory was tried as a catalog and given up the same day).</p>
 */
final class Outfits implements Listener {

    /** {@code item}: what is worn, a Flux item id ({@code namespace:id}) or a material. */
    record Cosmetic(String key, String name, String item, EquipmentSlot slot, boolean unlocked) {
    }

    private final FluxCosmetics plugin;
    private final Map<String, Cosmetic> cosmetics = new LinkedHashMap<>();
    /** What each player wears, one cosmetic per slot. */
    private final Map<UUID, Map<EquipmentSlot, String>> worn = new HashMap<>();

    Outfits(FluxCosmetics plugin) {
        this.plugin = plugin;
    }

    void load(ConfigurationSection section) {
        cosmetics.clear();
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection c = section.getConfigurationSection(key);
            if (c == null) continue;
            EquipmentSlot slot = switch (c.getString("slot", "head").toLowerCase(Locale.ROOT)) {
                case "chest" -> EquipmentSlot.CHEST;
                case "legs" -> EquipmentSlot.LEGS;
                case "feet" -> EquipmentSlot.FEET;
                default -> EquipmentSlot.HEAD;
            };
            String item = c.getString("item");
            if (item == null || item.isBlank()) {
                plugin.getLogger().warning("Cosmetic " + key + ": no item, left out");
                continue;
            }
            cosmetics.put(key, new Cosmetic(key, c.getString("name", key), item, slot, c.getBoolean("unlocked", true)));
        }
    }

    List<String> keys() {
        return List.copyOf(cosmetics.keySet());
    }

    /** Puts this cosmetic on him, or takes it off when he wears it. False when it does not exist. */
    boolean toggle(Player player, String key) {
        Cosmetic c = cosmetics.get(key);
        if (c == null) return false;
        if (!c.unlocked()) {
            Sounds.cue(player, "error");
            plugin.messages().send(player, "outfits.locked", "%name%", c.name());
            return true;
        }
        Map<EquipmentSlot, String> mine = worn.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        if (key.equals(mine.get(c.slot()))) {
            mine.remove(c.slot());
            Appearance.set(player, c.slot(), null);
            Sounds.cue(player, "toggle-off");
            plugin.messages().send(player, "outfits.taken-off", "%name%", c.name());
        } else {
            mine.put(c.slot(), key);
            Appearance.set(player, c.slot(), item(c, player));
            Sounds.cue(player, "toggle-on");
            plugin.messages().send(player, "outfits.wearing", "%name%", c.name());
        }
        return true;
    }

    private static ItemStack item(Cosmetic c, Player player) {
        String spec = c.item();
        boolean fluxId = spec.indexOf(':') > 0 && !spec.startsWith("itemsadder:") && !spec.startsWith("source:");
        ItemStack item = fluxId ? CustomItems.item(spec) : null;
        return item != null ? item : Items.of(spec, player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        worn.remove(e.getPlayer().getUniqueId());
        Appearance.clear(e.getPlayer());
    }
}
