package fr.influencraft.fluxspawner;

import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.items.ItemLooks;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The items of FluxSpawner, all under the {@code fluxspawner} namespace of Flux's item registry:
 * <ul>
 *   <li>{@code fluxspawner:<type>}: a spawner (level 0 when made by {@code /fluxitems give} or a shop, its own level
 *   and id when picked up). Its data stays in the database; the item carries the id, the type and the level shown;</li>
 *   <li>{@code fluxspawner:<essence>}: a fuel worth fixed hours of running (fuel.essences of config.yml).</li>
 * </ul>
 * Names and descriptions come from the lang file, refreshed on items already handed out after a reload.
 */
final class SpawnerItems implements CustomItems.Source {

    private final FluxSpawner plugin;
    final NamespacedKey typeKey;
    final NamespacedKey levelKey;
    final NamespacedKey idKey;
    final NamespacedKey essenceKey;
    private final NamespacedKey lookKey;
    /** Raised on every reload: items whose look is older are drawn again. */
    private int revision = 1;

    SpawnerItems(FluxSpawner plugin) {
        this.plugin = plugin;
        typeKey = new NamespacedKey(plugin, "type");
        levelKey = new NamespacedKey(plugin, "level");
        idKey = new NamespacedKey(plugin, "id");
        essenceKey = new NamespacedKey(plugin, "essence");
        lookKey = new NamespacedKey(plugin, "look");
    }

    void register() {
        CustomItems.register(plugin, "fluxspawner", this);
        ItemLooks.register(plugin, this::refresh);
    }

    void reloaded() {
        revision++;
        ItemLooks.refreshOnline();
    }

    // ---------------------------------------------------------------- Source

    @Override
    public String id(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return null;
        io.papermc.paper.persistence.PersistentDataContainerView pdc = item.getPersistentDataContainer();
        String type = pdc.get(typeKey, PersistentDataType.STRING);
        if (type != null) return type;
        return pdc.get(essenceKey, PersistentDataType.STRING);
    }

    @Override
    public ItemStack item(String id) {
        SpawnerType type = plugin.settings().type(id);
        if (type != null) return spawner(type, 0, null);
        Settings.Essence e = plugin.settings().essences.get(id);
        return e == null ? null : essence(e);
    }

    @Override
    public Collection<String> ids() {
        List<String> out = new ArrayList<>(plugin.settings().types.keySet());
        out.addAll(plugin.settings().essences.keySet());
        return out;
    }

    // ---------------------------------------------------------------- reading

    /** Type of a spawner item, null for any other item. */
    String typeOf(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return null;
        return item.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
    }

    int levelOf(ItemStack item) {
        Integer l = item.getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER);
        return l == null ? 0 : Math.max(0, l);
    }

    /** Id of the spawner the item stands for, null for a new one (shop, give). */
    UUID idOf(ItemStack item) {
        String s = item.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- making

    /** A spawner item: a new one of this level ({@code from} null) or the item form of a picked-up spawner. */
    ItemStack spawner(SpawnerType type, int level, Spawner from) {
        ItemStack item = new ItemStack(Material.SPAWNER);
        item.editMeta(meta -> {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(typeKey, PersistentDataType.STRING, type.id());
            pdc.set(levelKey, PersistentDataType.INTEGER, from == null ? level : from.level);
            if (from != null) pdc.set(idKey, PersistentDataType.STRING, from.id.toString());
        });
        draw(item, type);
        return item;
    }

    ItemStack essence(Settings.Essence e) {
        ItemStack item = new ItemStack(e.material());
        item.editMeta(meta -> meta.getPersistentDataContainer().set(essenceKey, PersistentDataType.STRING, e.id()));
        drawEssence(item, e);
        return item;
    }

    /** Name and description of a spawner item from what it carries (and the database for a picked-up one). */
    private void draw(ItemStack item, SpawnerType type) {
        int level = levelOf(item);
        UUID id = idOf(item);
        Spawner s = id == null ? null : plugin.spawners().get(id);
        int max = plugin.settings().maxLevel(type);
        String[] vars = {
                "%type%", type.displayName(),
                "%level%", String.valueOf(level),
                "%max%", String.valueOf(max),
                "%rate%", Format.number(Math.round(plugin.settings().mobsPerHour(type, level))),
                "%capacity%", Format.number(plugin.settings().capacity(type, level)),
                "%tank%", Format.duration(Math.round(plugin.settings().tankSeconds(type, level))),
                "%stock%", Format.number(s == null ? 0 : s.stock),
                "%kills%", Format.number(s == null ? 0 : s.kills),
                "%fuel%", Format.duration(Math.round(s == null ? 0 : s.fuel))};
        List<String> lore = new ArrayList<>(plugin.lines("item.lore", vars));
        if (s != null && s.stock > 0) lore.addAll(plugin.lines("item.lore-stock", vars));
        if (s != null && s.fuel >= 1) lore.addAll(plugin.lines("item.lore-fuel", vars));
        lore.addAll(plugin.lines("item.lore-footer", vars));
        item.editMeta(meta -> {
            meta.displayName(Texts.parse(plugin.raw(level >= max ? "item.name-max" : "item.name", vars)));
            meta.lore(Texts.lines(lore, null));
            meta.addItemFlags(ItemFlag.values());
            meta.getPersistentDataContainer().set(lookKey, PersistentDataType.INTEGER, revision);
        });
    }

    private void drawEssence(ItemStack item, Settings.Essence e) {
        String[] vars = {"%time%", Format.duration(Math.round(e.seconds()))};
        String base = "essences." + e.id();
        item.editMeta(meta -> {
            meta.displayName(Texts.parse(plugin.raw(base + ".name", vars)));
            meta.lore(Texts.lines(plugin.lines(base + ".lore", vars), null));
            if (e.glint()) meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(lookKey, PersistentDataType.INTEGER, revision);
        });
    }

    /** Items already handed out follow the lang file and the spawner they stand for. */
    private boolean refresh(String fullId, ItemStack item) {
        if (!fullId.startsWith("fluxspawner:")) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        Integer look = meta.getPersistentDataContainer().get(lookKey, PersistentDataType.INTEGER);
        if (look != null && look == revision) return false;
        String id = fullId.substring("fluxspawner:".length());
        SpawnerType type = plugin.settings().type(id);
        if (type != null && typeOf(item) != null) {
            draw(item, type);
            return true;
        }
        Settings.Essence e = plugin.settings().essences.get(id);
        if (e != null) {
            drawEssence(item, e);
            return true;
        }
        return false;
    }

}
