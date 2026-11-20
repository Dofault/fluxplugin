package fr.influencraft.fluxcosmetics;

import fr.influencraft.flux.api.client.RecipeBook;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The cosmetics catalog, shown in the recipe book of the inventory ({@link RecipeBook}): the green book button opens
 * the cosmetics instead of the recipes. One entry per cosmetic of the config, in the tab of its kind, drawn craftable
 * when the player has it. A click wears it (or takes it off), shift + click shows it on him in the wardrobe.
 *
 * <p>First version (2026-11-20): the cosmetics are those of the config, "unlocked" for everybody or nobody, and what
 * a player wears is kept in memory only. Ownership and worn cosmetics go to Flux's database with the outfits drawn
 * over the armour.</p>
 */
final class Catalog implements RecipeBook.Catalog, Listener {

    record Cosmetic(String key, String name, String description, String icon, RecipeBook.Tab tab, boolean unlocked) {
    }

    private final FluxCosmetics plugin;
    private final List<Cosmetic> cosmetics = new ArrayList<>();
    /** What each player wears, one cosmetic per tab (memory only, first version). */
    private final Map<UUID, Map<RecipeBook.Tab, String>> worn = new HashMap<>();

    Catalog(FluxCosmetics plugin) {
        this.plugin = plugin;
    }

    void load(ConfigurationSection section) {
        cosmetics.clear();
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection c = section.getConfigurationSection(key);
            if (c == null) continue;
            RecipeBook.Tab tab;
            try {
                tab = RecipeBook.Tab.valueOf(c.getString("tab", "equipment").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Cosmetic " + key + ": unknown tab " + c.getString("tab") + ", put in equipment");
                tab = RecipeBook.Tab.EQUIPMENT;
            }
            cosmetics.add(new Cosmetic(key, c.getString("name", key), c.getString("description", ""),
                    c.getString("icon", "LEATHER_HELMET"), tab, c.getBoolean("unlocked", true)));
        }
        if (cosmetics.isEmpty()) plugin.getLogger().warning("No cosmetics in config.yml: the recipe book will be empty");
    }

    @Override
    public List<RecipeBook.Entry> entries(Player player) {
        Map<RecipeBook.Tab, String> mine = worn.getOrDefault(player.getUniqueId(), Map.of());
        List<RecipeBook.Entry> out = new ArrayList<>(cosmetics.size());
        for (int i = 0; i < cosmetics.size(); i++) {
            Cosmetic c = cosmetics.get(i);
            boolean wearing = c.key().equals(mine.get(c.tab()));
            out.add(new RecipeBook.Entry(i, icon(player, c, wearing), c.tab(), c.unlocked(), false));
        }
        return out;
    }

    private ItemStack icon(Player player, Cosmetic c, boolean wearing) {
        var m = plugin.messages();
        Lore lore = Lore.create();
        if (!c.description().isBlank()) lore.description(c.description());
        if (wearing) lore.ok(m.raw("catalog.worn")).left(m.raw("catalog.take-off"));
        else if (c.unlocked()) lore.left(m.raw("catalog.wear"));
        else lore.ko(m.raw("catalog.locked"));
        lore.shiftLeft(m.raw("catalog.preview"));
        ItemStack item = Items.dress(Items.of(c.icon(), player), c.name(), lore.tags(), player);
        if (wearing) item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return item;
    }

    @Override
    public void clicked(Player player, int id, boolean shift) {
        if (id < 0 || id >= cosmetics.size()) return;
        Cosmetic c = cosmetics.get(id);
        if (shift) {
            player.closeInventory();
            if (!plugin.wardrobe().active(player)) plugin.wardrobe().open(player);
            return;
        }
        if (!c.unlocked()) {
            Sounds.cue(player, "error");
            plugin.messages().send(player, "catalog.locked-message", "%name%", c.name());
            return;
        }
        Map<RecipeBook.Tab, String> mine = worn.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        if (c.key().equals(mine.get(c.tab()))) {
            mine.remove(c.tab());
            Sounds.cue(player, "toggle-off");
            plugin.messages().send(player, "catalog.taken-off", "%name%", c.name());
        } else {
            mine.put(c.tab(), c.key());
            Sounds.cue(player, "toggle-on");
            plugin.messages().send(player, "catalog.wearing", "%name%", c.name());
        }
        RecipeBook.refresh(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> RecipeBook.refresh(p), 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        worn.remove(e.getPlayer().getUniqueId());
    }
}
