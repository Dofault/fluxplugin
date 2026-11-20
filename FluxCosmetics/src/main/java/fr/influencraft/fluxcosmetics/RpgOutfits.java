package fr.influencraft.fluxcosmetics;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.PlaceholderProvider;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.items.ItemLooks;
import fr.influencraft.flux.api.staff.Staff;
import fr.influencraft.flux.api.universe.UniverseEnterEvent;
import fr.influencraft.flux.api.universe.Universes;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The cosmetics of the rpg universe, apart from the vanilla ones: each one is a whole outfit (the four armour slots),
 * and a player there is never bare: he wears the outfit he chose, else the one of his class ({@code rpg-class-outfits}),
 * which he always owns. Vanilla cosmetics never show in the rpg universe and rpg outfits never outside it
 * ({@link Outfits#show}).
 *
 * <p>Linked with FluxDungeons through Flux only: the universe from {@link Universes}, the class from the placeholder
 * {@code %fluxrpg_class_id%}. Entering a universe draws the right looks at once ({@link UniverseEnterEvent}); a class
 * change is seen within two seconds.</p>
 */
final class RpgOutfits implements Listener {

    /** An rpg outfit: the item of each armour slot it covers (a slot it has none for shows the real piece). */
    record Outfit(String key, String name, Outfits.Rarity rarity, Map<EquipmentSlot, String> pieces, boolean free) {
    }

    private static final String[] PIECES = {"helmet", "chestplate", "leggings", "boots"};

    private final FluxCosmetics plugin;
    private final Outfits outfits;
    private final Map<String, Outfit> catalog = new LinkedHashMap<>();
    private final Map<String, String> byClass = new HashMap<>();
    private String universe = "rpg";
    /** Class each rpg player was last drawn with: a change is drawn again. */
    private final Map<UUID, String> drawnClass = new HashMap<>();

    RpgOutfits(FluxCosmetics plugin, Outfits outfits) {
        this.plugin = plugin;
        this.outfits = outfits;
    }

    void load(ConfigurationSection config) {
        universe = config.getString("rpg-universe", "rpg");
        catalog.clear();
        ConfigurationSection s = config.getConfigurationSection("rpg-outfits");
        if (s != null) for (String key : s.getKeys(false)) {
            ConfigurationSection o = s.getConfigurationSection(key);
            if (o == null) continue;
            Map<EquipmentSlot, String> pieces = new EnumMap<>(EquipmentSlot.class);
            String set = o.getString("set");
            for (int i = 0; i < PIECES.length; i++) {
                String item = o.getString("pieces." + PIECES[i], set == null ? null : "fluxarmor:" + set + "_" + PIECES[i]);
                if (item != null && !item.isBlank()) pieces.put(Outfits.ARMOUR[i], item);
            }
            catalog.put(key, new Outfit(key, o.getString("name", key), outfits.rarity(o.getString("rarity")), Map.copyOf(pieces),
                    o.getBoolean("free", false)));
        }
        byClass.clear();
        ConfigurationSection c = config.getConfigurationSection("rpg-class-outfits");
        if (c != null) for (String cls : c.getKeys(false)) {
            String key = c.getString(cls);
            if (catalog.containsKey(key)) byClass.put(cls, key);
            else plugin.getLogger().warning("rpg-class-outfits." + cls + ": no rpg outfit " + key);
        }
        drawnClass.clear();
    }

    /** Starts the class watch (a class change shows the new class's outfit). */
    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!in(p) || !outfits.ready(p)) continue;
                String cls = classOf(p);
                if (!cls.equals(drawnClass.get(p.getUniqueId()))) {
                    drawnClass.put(p.getUniqueId(), cls);
                    outfits.show(p);
                }
            }
        }, 40L, 40L);
    }

    /** Id of the rpg universe (Flux's universes.yml). */
    String universe() {
        return universe;
    }

    /** Whether he is in the rpg universe now. */
    boolean in(Player player) {
        return universe.equals(Universes.of(player));
    }

    Outfit get(String key) {
        return key == null ? null : catalog.get(key);
    }

    /** Every rpg outfit, the most common first. */
    List<Outfit> all() {
        List<Outfit> out = new ArrayList<>(catalog.values());
        out.sort(Comparator.comparingInt(o -> o.rarity().rank()));
        return out;
    }

    List<String> keys() {
        return List.copyOf(catalog.keySet());
    }

    /** His rpg class id, "" when none (read from FluxDungeons through Flux). */
    String classOf(Player player) {
        PlaceholderProvider rpg = FluxApi.placeholders("fluxrpg");
        String id = rpg == null ? null : rpg.request(player, "class_id");
        return id == null ? "" : id;
    }

    /** The outfit of his class, never null while any outfit exists ({@code default} for a class without one). */
    Outfit classOutfit(Player player) {
        Outfit o = get(byClass.get(classOf(player)));
        if (o == null) o = get(byClass.get("default"));
        return o != null ? o : catalog.values().stream().findFirst().orElse(null);
    }

    /** The items of the outfit of a class id by armour slot ({@code default} for a class without one), for Flux's ClassOutfits. */
    Map<EquipmentSlot, ItemStack> classItems(String classId) {
        Outfit o = get(byClass.get(classId));
        if (o == null) o = get(byClass.get("default"));
        Map<EquipmentSlot, ItemStack> out = new EnumMap<>(EquipmentSlot.class);
        if (o != null) for (EquipmentSlot slot : Outfits.ARMOUR) {
            ItemStack item = item(o, slot);
            if (item != null) out.put(slot, item);
        }
        return out;
    }

    /** Whether he may wear it: his class's, free, given to him, or he is an admin. */
    boolean owns(Player player, Outfit o) {
        if (o == null) return false;
        if (o.free() || Staff.isAdmin(player) || o.equals(classOutfit(player))) return true;
        return outfits.given(player, o.key());
    }

    /** What he wears in the rpg universe: his choice when it is still his, else his class's. */
    Outfit active(Player player, String choice) {
        Outfit chosen = get(choice);
        return chosen != null && owns(player, chosen) ? chosen : classOutfit(player);
    }

    /** The item of one slot of an outfit with the suite's looks (3D), null when it has none there. */
    ItemStack item(Outfit o, EquipmentSlot slot) {
        String id = o.pieces().get(slot);
        ItemStack item = id == null ? null : CustomItems.item(id);
        if (item != null) ItemLooks.apply(item);
        return item;
    }

    /** He just carries the profile of a universe: his looks for it at once. */
    @EventHandler
    public void onUniverse(UniverseEnterEvent e) {
        drawnClass.remove(e.getPlayer().getUniqueId());
        outfits.show(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        drawnClass.remove(e.getPlayer().getUniqueId());
    }
}
