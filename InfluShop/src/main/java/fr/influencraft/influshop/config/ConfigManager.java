package fr.influencraft.influshop.config;

import fr.influencraft.influshop.model.CommandRunAs;
import fr.influencraft.influshop.model.ShopEntry;
import org.bukkit.Material;
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
import java.util.Locale;
import java.util.Map;

/**
 * Loads config.yml (global settings + GUI layout) and shop.yml (the catalog). The catalog
 * lives in its own file, separate from config.yml, because the in-game admin editor rewrites
 * that file on every price change / add / remove and we don't want that to ever risk
 * clobbering hand-written comments in the main settings file (same split as InfluOffrande's
 * config.yml / collections.yml).
 */
public final class ConfigManager {

    private final JavaPlugin plugin;
    private final File shopFile;

    private Settings settings;
    private GuiSettings guiSettings;
    private ConfirmSettings confirmSettings;
    private final Map<String, ShopEntry> entries = new LinkedHashMap<>();

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.shopFile = new File(plugin.getDataFolder(), "shop.yml");
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        loadSettings();
        loadGuiSettings();
        loadConfirmSettings();
        loadShop();
    }

    private void loadSettings() {
        var cfg = plugin.getConfig();
        settings = new Settings(cfg.getInt("settings.price-prompt-timeout-seconds", 30));
    }

    private void loadGuiSettings() {
        var cfg = plugin.getConfig();
        guiSettings = new GuiSettings(
                cfg.getInt("gui.size", 54),
                cfg.getIntegerList("gui.item-slots"),
                cfg.getInt("gui.nav-previous-slot", 45),
                cfg.getInt("gui.nav-next-slot", 53),
                cfg.getInt("gui.sort-slot", 49),
                cfg.getInt("gui.editor-toggle-slot", 0),
                cfg.getInt("gui.balance-slot", 40),
                cfg.getString("gui.open-sound", "minecraft:item.book.page_turn"),
                cfg.getString("gui.purchase-sound", "minecraft:entity.experience_orb.pickup"),
                cfg.getString("gui.error-sound", "minecraft:entity.villager.no"),
                cfg.getString("gui.shop-title-format", "&#FFD94D%img_influence% Boutique Influence"),
                cfg.getString("gui.editor-title-format", "&#FF5C5C[Editeur] &#FFD94D%img_influence% Boutique Influence"),
                cfg.getString("gui.materials.nav-prev", "influitems:btn_prev"),
                cfg.getString("gui.materials.nav-next", "influitems:btn_next"),
                cfg.getString("gui.materials.sort", "influitems:filter"),
                cfg.getString("gui.materials.editor-open", "ANVIL"),
                cfg.getString("gui.materials.editor-close", "BARRIER"),
                cfg.getString("gui.materials.balance", "influitems:influence"),
                cfg.getString("gui.materials.back", "influitems:btn_back")
        );
    }

    private void loadConfirmSettings() {
        var cfg = plugin.getConfig();
        confirmSettings = new ConfirmSettings(
                cfg.getInt("confirm.size", 27),
                cfg.getInt("confirm.item-slot", 13),
                cfg.getInt("confirm.confirm-slot", 11),
                cfg.getInt("confirm.cancel-slot", 15),
                cfg.getString("confirm.title-format", "&#FFD94D%img_influence% Confirmer l'achat"),
                cfg.getString("confirm.materials.confirm", "influitems:accept"),
                cfg.getString("confirm.materials.cancel", "BARRIER")
        );
    }

    private void loadShop() {
        entries.clear();
        if (!shopFile.exists()) {
            plugin.saveResource("shop.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(shopFile);
        ConfigurationSection root = yaml.getConfigurationSection("entries");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            CommandRunAs runAs;
            try {
                runAs = CommandRunAs.valueOf(section.getString("run-as", "CONSOLE").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                runAs = CommandRunAs.CONSOLE;
            }
            ConfigurationSection display = section.getConfigurationSection("display");
            Material material = display != null
                    ? Material.matchMaterial(String.valueOf(display.getString("material", "BARRIER")))
                    : null;
            entries.put(id, new ShopEntry(
                    id,
                    section.getString("command"),
                    runAs,
                    material != null ? material : Material.BARRIER,
                    display != null ? display.getInt("model-data", 0) : 0,
                    display != null ? display.getString("name", id) : id,
                    display != null ? display.getStringList("lore") : List.of(),
                    section.getInt("price", 0)
            ));
        }
    }

    /**
     * Rewrites shop.yml from the in-memory model. Used by the in-game admin editor after
     * every add/remove/price change so progress is never lost on a crash.
     */
    public synchronized void saveShop() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("entries");
        for (ShopEntry entry : entries.values()) {
            ConfigurationSection section = root.createSection(entry.id());
            section.set("command", entry.command());
            section.set("run-as", entry.runAs().name());
            section.set("price", entry.price());
            ConfigurationSection display = section.createSection("display");
            display.set("material", entry.displayMaterial() != null ? entry.displayMaterial().name() : "BARRIER");
            if (entry.displayModelData() > 0) {
                display.set("model-data", entry.displayModelData());
            }
            display.set("name", entry.displayName());
            display.set("lore", entry.displayLore());
        }

        String header = """
                # shop.yml — catalogue de la boutique Influence (InfluShop).
                # Reecrit en entier par l'editeur en jeu (bouton enclume) : evite de compter sur
                # des commentaires ici apres une modif en jeu.
                #
                # Chaque entree : "display" est une PHOTO figee (nom/lore/materiau/model-data)
                # de l'objet reel depose en mode edition au moment de l'ajout — jamais regeneree
                # automatiquement depuis un autre plugin. Si l'objet source change plus tard
                # (description, modele...), il faut re-deposer l'objet a jour pour la mettre a
                # jour ici aussi. "command" (placeholder %player%) est executee a l'achat,
                # "run-as" CONSOLE (defaut, commande admin) ou PLAYER (quand la commande doit
                # etre lancee par le joueur lui-meme, ex: une commande qui verifie sa propre
                # permission).
                """;
        try (Writer writer = Files.newBufferedWriter(shopFile.toPath(), StandardCharsets.UTF_8)) {
            writer.write(header);
            writer.write(yaml.saveToString());
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save shop.yml: " + ex.getMessage());
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

    public ConfirmSettings confirm() {
        return confirmSettings;
    }

    public Map<String, ShopEntry> entries() {
        return entries;
    }

    /**
     * Generates a fresh, unused yaml key for a newly added entry (the id itself is never
     * shown to players — only used internally and as the shop.yml key).
     */
    public String freshEntryId(String hint) {
        String base = hint.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (base.isBlank()) {
            base = "item";
        }
        String candidate = base;
        int suffix = 2;
        while (entries.containsKey(candidate)) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }
}
