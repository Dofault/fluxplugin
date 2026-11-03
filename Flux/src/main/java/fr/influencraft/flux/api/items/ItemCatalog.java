package fr.influencraft.flux.api.items;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Items a plugin defines in its config ({@link ItemDefinition}), under its own namespace. The plugin keeps the
 * definitions and the behaviour; the catalog makes the items, recognises them ({@link CustomItems#id}, through the data
 * key {@code flux:item}) and keeps items already handed out in line with the config ({@link ItemLooks} moments).
 *
 * <pre>
 *   catalog = ItemCatalog.create(this, "myplugin");
 *   catalog.load(getConfig().getConfigurationSection("items"));   // again on every reload
 * </pre>
 */
public final class ItemCatalog {

    private static final NamespacedKey ITEM = new NamespacedKey("flux", "item");
    private static final NamespacedKey REVISION = new NamespacedKey("flux", "item_revision");

    private final String namespace;
    private final Map<String, ItemDefinition> definitions = new LinkedHashMap<>();

    private ItemCatalog(String namespace) {
        this.namespace = namespace;
    }

    public static ItemCatalog create(Plugin owner, String namespace) {
        ItemCatalog catalog = new ItemCatalog(namespace);
        CustomItems.register(owner, namespace, catalog.new Items());
        ItemLooks.register(owner, catalog::refresh);
        return catalog;
    }

    /** Replaces the definitions by the sections of {@code items} (id = key). */
    public void load(ConfigurationSection items) {
        definitions.clear();
        if (items != null) for (String id : items.getKeys(false)) add(id, items.getConfigurationSection(id));
    }

    /** Adds one definition; the owner may build the section itself (a piece of an armour set...). */
    public void add(String id, ConfigurationSection section) {
        if (section != null) definitions.put(id, ItemDefinition.read(section));
    }

    public void clear() {
        definitions.clear();
    }

    public boolean has(String id) {
        return definitions.containsKey(id);
    }

    /** Marks {@code item} as this catalog's {@code id} and writes the definition on it, keeping what it carries. */
    public void adopt(ItemStack item, String id) {
        ItemDefinition definition = definitions.get(id);
        if (definition != null) write(item, id, definition);
    }

    private boolean refresh(String fullId, ItemStack item) {
        String prefix = namespace + ":";
        if (!fullId.startsWith(prefix)) return false;
        String id = fullId.substring(prefix.length());
        ItemDefinition definition = definitions.get(id);
        if (definition == null) return false;
        Integer revision = item.getPersistentDataContainer().get(REVISION, PersistentDataType.INTEGER);
        if (revision != null && revision == definition.revision) return false;
        write(item, id, definition);
        return true;
    }

    private void write(ItemStack item, String id, ItemDefinition definition) {
        definition.applyTo(item);
        item.editMeta(meta -> {
            meta.getPersistentDataContainer().set(ITEM, PersistentDataType.STRING, namespace + ":" + id);
            meta.getPersistentDataContainer().set(REVISION, PersistentDataType.INTEGER, definition.revision);
        });
    }

    private final class Items implements CustomItems.Source {

        @Override
        public String id(ItemStack item) {
            String full = item.getPersistentDataContainer().get(ITEM, PersistentDataType.STRING);
            return full != null && full.startsWith(namespace + ":") ? full.substring(namespace.length() + 1) : null;
        }

        @Override
        public ItemStack item(String id) {
            ItemDefinition definition = definitions.get(id);
            if (definition == null) return null;
            ItemStack item = definition.create();
            write(item, id, definition);
            return item;
        }

        @Override
        public Collection<String> ids() {
            return definitions.keySet();
        }
    }
}
