package fr.influencraft.influoffrande.config;

import fr.influencraft.influoffrande.model.CollectionDefinition;
import fr.influencraft.influoffrande.model.ItemKey;
import fr.influencraft.influoffrande.wiki.WikiLinks;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads config.yml (global settings + GUI layout) and collections.yml (collection
 * definitions). Collections live in their own file, separate from config.yml, because
 * the in-game admin editor rewrites that file on every edit and we don't want that to
 * ever risk clobbering hand-written comments in the main settings file.
 */
public final class ConfigManager {

    private final JavaPlugin plugin;
    private final File collectionsFile;

    private Settings settings;
    private GuiSettings guiSettings;
    private WikiLinks wikiLinks;
    private final Map<String, CollectionDefinition> collections = new LinkedHashMap<>();

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.collectionsFile = new File(plugin.getDataFolder(), "collections.yml");
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        loadSettings();
        loadGuiSettings();
        loadWikiLinks();
        loadCollections();
    }

    private void loadSettings() {
        var cfg = plugin.getConfig();
        settings = new Settings(
                cfg.getBoolean("settings.accept-renamed-items", false),
                cfg.getBoolean("settings.accept-damaged-tools", true),
                cfg.getInt("settings.autosave-interval-minutes", 5)
        );
    }

    private void loadWikiLinks() {
        var cfg = plugin.getConfig();
        Map<String, String> titles = new LinkedHashMap<>();
        ConfigurationSection section = cfg.getConfigurationSection("wiki.titles");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String title = section.getString(key);
                if (title != null && !title.isBlank()) {
                    titles.put(key.toUpperCase(java.util.Locale.ROOT), title);
                }
            }
        }
        wikiLinks = new WikiLinks(
                cfg.getBoolean("wiki.enabled", true),
                cfg.getString("wiki.url-format", "https://fr.minecraft.wiki/w/{title}"),
                titles);
    }

    private void loadGuiSettings() {
        var cfg = plugin.getConfig();
        guiSettings = new GuiSettings(
                cfg.getInt("gui.size", 54),
                cfg.getIntegerList("gui.item-slots"),
                cfg.getInt("gui.nav-previous-slot", 45),
                cfg.getInt("gui.nav-next-slot", 53),
                cfg.getInt("gui.filter-slot", 49),
                cfg.getInt("gui.editor-toggle-slot", 0),
                cfg.getInt("gui.deposit-all-slot", 40),
                cfg.getString("gui.open-sound", "minecraft:item.book.page_turn"),
                cfg.getString("gui.deposit-sound", "minecraft:entity.experience_orb.pickup"),
                cfg.getString("gui.error-sound", "minecraft:entity.villager.no"),
                cfg.getString("gui.collection-complete-sound", "minecraft:entity.player.levelup"),
                cfg.getString("gui.wiki-sound", "minecraft:item.book.page_turn"),
                cfg.getString("gui.collection-title-format", "{displayname}"),
                cfg.getString("gui.editor-title-format", "&#FF5C5C[Editeur] &#FFD94D{displayname}"),
                cfg.getString("gui.materials.nav-prev", "influitems:btn_prev"),
                cfg.getString("gui.materials.nav-next", "influitems:btn_next"),
                cfg.getString("gui.materials.filter", "influitems:filter"),
                cfg.getString("gui.materials.editor-open", "ANVIL"),
                cfg.getString("gui.materials.editor-close", "BARRIER"),
                cfg.getString("gui.materials.deposit-all", "CHEST"),
                cfg.getString("gui.materials.money", "influitems:bitblock"),
                cfg.getString("gui.materials.back", "influitems:btn_back")
        );
    }

    private void loadCollections() {
        collections.clear();
        if (!collectionsFile.exists()) {
            plugin.saveResource("collections.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(collectionsFile);
        ConfigurationSection root = yaml.getConfigurationSection("collections");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            CollectionDefinition def = new CollectionDefinition(id);
            def.displayName(section.getString("displayname", "&#FFD94D" + id));
            def.permission(section.getString("permission", ""));
            def.money(section.getLong("money", 0));

            ConfigurationSection itemsSection = section.getConfigurationSection("items");
            if (itemsSection != null) {
                for (String rawKey : itemsSection.getKeys(false)) {
                    try {
                        ItemKey key = ItemKey.parse(rawKey);
                        int amount = itemsSection.getInt(rawKey);
                        if (amount <= 0) {
                            plugin.getLogger().warning("[" + id + "] item '" + rawKey + "' has a non-positive amount, skipped");
                            continue;
                        }
                        def.items().put(key, amount);
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("[" + id + "] invalid item key '" + rawKey + "': " + ex.getMessage());
                    }
                }
            }

            ConfigurationSection namesSection = section.getConfigurationSection("names");
            if (namesSection != null) {
                for (String rawKey : namesSection.getKeys(false)) {
                    try {
                        ItemKey key = ItemKey.parse(rawKey);
                        def.customNames().put(key, namesSection.getString(rawKey));
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("[" + id + "] invalid item key in names '" + rawKey + "': " + ex.getMessage());
                    }
                }
            }

            collections.put(id, def);
        }
    }

    /**
     * Rewrites collections.yml from the in-memory model. Used by the in-game admin editor
     * after every add/remove/quantity change so progress is never lost on a crash.
     */
    public synchronized void saveCollections() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("collections");
        for (CollectionDefinition def : collections.values()) {
            ConfigurationSection section = root.createSection(def.id());
            section.set("displayname", def.displayName());
            section.set("permission", def.permission() == null ? "" : def.permission());
            section.set("money", def.money());
            ConfigurationSection itemsSection = section.createSection("items");
            for (Map.Entry<ItemKey, Integer> entry : def.items().entrySet()) {
                itemsSection.set(entry.getKey().raw(), entry.getValue());
            }
            if (!def.customNames().isEmpty()) {
                ConfigurationSection namesSection = section.createSection("names");
                for (Map.Entry<ItemKey, String> entry : def.customNames().entrySet()) {
                    namesSection.set(entry.getKey().raw(), entry.getValue());
                }
            }
        }

        String header = """
                # collections.yml — collection definitions for InfluOffrande.
                # This file is rewritten in full whenever an admin uses the in-game editor
                # (right-click the edit button in /offrande <collection>), so avoid relying on
                # comments here surviving an in-game edit. Hand edits are picked up on /influoffrande reload.
                #
                # item keys: a plain Bukkit material name (DIAMOND) or an ItemsAdder namespaced id
                # ("namespace:id", must be quoted because of the colon).
                #
                # money: BitBlock required to complete this offrande, 0 = none requested.
                """;
        try (Writer writer = Files.newBufferedWriter(collectionsFile.toPath(), StandardCharsets.UTF_8)) {
            writer.write(header);
            writer.write(yaml.saveToString());
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save collections.yml: " + ex.getMessage());
        }
    }

    public void reload() {
        load();
    }

    public Settings settings() {
        return settings;
    }

    public GuiSettings gui() {
        return guiSettings;
    }

    public WikiLinks wikiLinks() {
        return wikiLinks;
    }

    public CollectionDefinition getCollection(String id) {
        return collections.get(id);
    }

    public Map<String, CollectionDefinition> collections() {
        return collections;
    }
}
