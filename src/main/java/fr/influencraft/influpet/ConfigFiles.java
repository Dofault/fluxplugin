package fr.influencraft.influpet;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration répartie en plusieurs fichiers, vue comme une seule config par le reste du plugin
 * (InfluPet#getConfig) :
 * - config.yml : réglages ;
 * - lang/<langue>.yml : textes affichés aux joueurs (chemins de LANG) ;
 * - menus/familiers.yml, menus/progression.yml : menus des familiers (sous familiers.gui / familiers.level-gui) ;
 * - contenu/cartes.yml, contenu/boosters.yml, contenu/familiers.yml : cartes, boosters, familiers obtenables.
 * Au chargement, chaque fichier est lu par-dessus sa version par défaut du jar (une clé ajoutée par une mise à
 * jour du plugin existe donc toujours) ; à l'enregistrement (menu d'édition), chaque chemin repart dans son
 * fichier, et seuls les fichiers modifiés sont réécrits (les commentaires de config.yml et de la langue sont
 * gardés). Un ancien config.yml d'un seul tenant (avant 2026-10-08) est découpé une fois, sauvegardé à côté.
 */
final class ConfigFiles {

    /** Chemins rangés dans le fichier de langue (préfixes). */
    static final List<String> LANG = List.of("messages", "reveal.actionbar", "reveal.subtitle", "reveal.back-title",
            "reveal.actionbar-pet", "reveal.actionbar-pet-duplicate", "reveal.actionbar-pet-duplicate-incomplete",
            "pet-reveal.title", "pet-reveal.subtitle", "card-format", "pets.name-format", "familiers.pet-entity.name",
            "familiers.level-up.message.message", "familiers.messages");
    /** Préfixe -> fichier de contenu (le fichier garde le chemin complet). */
    static final Map<String, String> CONTENT = new LinkedHashMap<>();
    /** Préfixe -> fichier de menu (le fichier contient la section elle-même, sans le préfixe). */
    static final Map<String, String> MENUS = new LinkedHashMap<>();

    static {
        CONTENT.put("card-xp", "contenu/cartes.yml");
        CONTENT.put("cards", "contenu/cartes.yml");
        CONTENT.put("boosters", "contenu/boosters.yml");
        CONTENT.put("pets.list", "contenu/familiers.yml");
        MENUS.put("familiers.gui", "menus/familiers.yml");
        MENUS.put("familiers.level-gui", "menus/progression.yml");
    }

    private final File folder;
    private final java.util.function.Function<String, InputStream> resources;
    private final java.util.logging.Logger logger;
    private String langFile = "lang/fr_FR.yml";

    ConfigFiles(InfluPet plugin) {
        this(plugin.getDataFolder(), plugin::getResource, plugin.getLogger());
    }

    /** Sans serveur (tests) : dossier de données, ressources du jar, journal. */
    ConfigFiles(File folder, java.util.function.Function<String, InputStream> resources, java.util.logging.Logger logger) {
        this.folder = folder;
        this.resources = resources;
        this.logger = logger;
    }

    private File file(String name) {
        return new File(folder, name);
    }

    /** Écrit les fichiers par défaut manquants (installation neuve). */
    void saveDefaults() {
        for (String name : List.of("config.yml", "lang/fr_FR.yml", "menus/familiers.yml", "menus/progression.yml",
                "contenu/cartes.yml", "contenu/boosters.yml", "contenu/familiers.yml", "effets.yml")) {
            if (!file(name).exists()) {
                try (InputStream in = resources.apply(name)) {
                    if (in != null) {
                        Files.createDirectories(file(name).toPath().getParent());
                        Files.copy(in, file(name).toPath());
                    }
                } catch (IOException e) {
                    logger.warning("Fichier par défaut " + name + " impossible à écrire : " + e);
                }
            }
        }
    }

    // ------------------------------------------------------------------ lecture

    YamlConfiguration load() {
        YamlConfiguration merged = new YamlConfiguration();
        overlay(merged, "", resource("config.yml"));
        YamlConfiguration config = read(file("config.yml"));
        overlay(merged, "", config);
        langFile = "lang/" + merged.getString("langue", "fr_FR") + ".yml";
        overlay(merged, "", resource("lang/fr_FR.yml"));
        overlay(merged, "", resource(langFile));
        overlay(merged, "", read(file(langFile)));
        for (Map.Entry<String, String> e : MENUS.entrySet()) {
            overlay(merged, e.getKey(), resource(e.getValue()));
            overlay(merged, e.getKey(), read(file(e.getValue())));
        }
        for (String name : new java.util.LinkedHashSet<>(CONTENT.values())) {
            overlay(merged, "", read(file(name)));
        }
        return merged;
    }

    private YamlConfiguration resource(String name) {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = resources.apply(name)) {
            if (in != null) {
                yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException | InvalidConfigurationException e) {
            logger.warning("Ressource " + name + " illisible : " + e);
        }
        return yaml;
    }

    private YamlConfiguration read(File f) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (f.exists()) {
            try {
                yaml.load(f);
            } catch (IOException | InvalidConfigurationException e) {
                logger.severe(f.getName() + " illisible (" + e.getMessage() + ") : valeurs par défaut.");
            }
        }
        return yaml;
    }

    /** Copie chaque valeur (et chaque section vide) de from dans into, sous le préfixe donné. */
    private static void overlay(YamlConfiguration into, String prefix, ConfigurationSection from) {
        for (String key : from.getKeys(true)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (from.isConfigurationSection(key)) {
                if (from.getConfigurationSection(key).getKeys(false).isEmpty() && !into.isConfigurationSection(path)) {
                    into.createSection(path);
                }
            } else {
                into.set(path, from.get(key));
            }
        }
    }

    // ------------------------------------------------------------------ écriture

    /** Fichier d'un chemin : contenu, menu, langue, sinon config.yml. */
    private String target(String path) {
        for (String p : CONTENT.keySet()) {
            if (matches(path, p)) {
                return CONTENT.get(p);
            }
        }
        for (String p : MENUS.keySet()) {
            if (matches(path, p)) {
                return MENUS.get(p);
            }
        }
        for (String p : LANG) {
            if (matches(path, p)) {
                return langFile;
            }
        }
        return "config.yml";
    }

    private static boolean matches(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + ".");
    }

    /** Range chaque valeur de la vue dans son fichier et réécrit les fichiers modifiés. */
    void save(YamlConfiguration merged) {
        save(merged, false);
    }

    private void save(YamlConfiguration merged, boolean force) {
        // contenu et menus : reconstruits entièrement (suppressions comprises), en-tête du fichier gardé
        Map<String, YamlConfiguration> rebuilt = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : CONTENT.entrySet()) {
            YamlConfiguration y = rebuilt.computeIfAbsent(e.getValue(), n -> withHeader(n));
            if (merged.isConfigurationSection(e.getKey())) {
                copy(merged.getConfigurationSection(e.getKey()), y, e.getKey());
            } else if (merged.contains(e.getKey())) {
                y.set(e.getKey(), merged.get(e.getKey()));
            }
        }
        for (Map.Entry<String, String> e : MENUS.entrySet()) {
            YamlConfiguration y = withHeader(e.getValue());
            ConfigurationSection s = merged.getConfigurationSection(e.getKey());
            if (s != null) {
                copy(s, y, "");
            }
            rebuilt.put(e.getValue(), y);
        }
        for (Map.Entry<String, YamlConfiguration> e : rebuilt.entrySet()) {
            write(e.getKey(), e.getValue(), force);
        }
        // config.yml et langue : valeurs posées sur le fichier existant (commentaires gardés)
        YamlConfiguration config = existingOrDefault("config.yml");
        YamlConfiguration lang = existingOrDefault(langFile);
        for (String key : merged.getKeys(true)) {
            if (merged.isConfigurationSection(key)) {
                continue;
            }
            String t = target(key);
            if (t.equals("config.yml")) {
                setIfChanged(config, key, merged.get(key));
            } else if (t.equals(langFile)) {
                setIfChanged(lang, key, merged.get(key));
            }
        }
        write("config.yml", config, force);
        write(langFile, lang, force);
    }

    private YamlConfiguration existingOrDefault(String name) {
        return file(name).exists() ? read(file(name)) : resource(name);
    }

    private YamlConfiguration withHeader(String name) {
        YamlConfiguration y = new YamlConfiguration();
        YamlConfiguration current = file(name).exists() ? read(file(name)) : resource(name);
        y.options().setHeader(current.options().getHeader());
        return y;
    }

    private static void copy(ConfigurationSection from, YamlConfiguration into, String prefix) {
        if (from.getKeys(false).isEmpty() && !prefix.isEmpty()) {
            into.createSection(prefix);
            return;
        }
        for (String key : from.getKeys(true)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (from.isConfigurationSection(key)) {
                if (from.getConfigurationSection(key).getKeys(false).isEmpty()) {
                    into.createSection(path);
                }
            } else {
                into.set(path, from.get(key));
            }
        }
    }

    private static void setIfChanged(YamlConfiguration y, String key, Object value) {
        Object current = y.get(key);
        if (current == null ? value != null : !current.equals(value)) {
            y.set(key, value);
        }
    }

    private void write(String name, YamlConfiguration yaml, boolean force) {
        File f = file(name);
        String text = yaml.saveToString();
        try {
            if (!force && f.exists() && text.equals(read(f).saveToString())) {
                return;
            }
            Files.createDirectories(f.toPath().getParent());
            Files.writeString(f.toPath(), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.severe("Écriture de " + name + " impossible : " + e);
        }
    }

    // ------------------------------------------------------------------ découpage de l'ancien format

    /**
     * Ancien format (config.yml d'un seul tenant, familiers-reglages.yml, familiers.yml) : découpé une fois dans
     * les nouveaux fichiers. Les anciens sont gardés avec le suffixe .avant-decoupage.
     */
    void migrate() {
        File config = file("config.yml");
        File reglages = file("familiers-reglages.yml");
        File effets = file("familiers.yml");
        if (effets.exists() && !file("effets.yml").exists()) {
            rename(effets, file("effets.yml"));
        }
        YamlConfiguration old = read(config);
        boolean oldConfig = config.exists() && !file("contenu/boosters.yml").exists()
                && (old.contains("cards") || old.contains("boosters") || old.contains("messages"));
        if (!oldConfig && !reglages.exists()) {
            return;
        }
        YamlConfiguration merged = new YamlConfiguration();
        overlay(merged, "", resource("config.yml"));
        overlay(merged, "", resource("lang/fr_FR.yml"));
        for (Map.Entry<String, String> e : MENUS.entrySet()) {
            overlay(merged, e.getKey(), resource(e.getValue()));
        }
        if (oldConfig) {
            overlay(merged, "", old);
            // anciennes clés sans équivalent : retirées de la vue
            merged.set("powder-snow-pet", null);
        }
        if (reglages.exists()) {
            YamlConfiguration r = read(reglages);
            for (String key : r.getKeys(false)) {
                String to = switch (key) {
                    case "gui" -> "familiers.gui";
                    case "level-gui" -> "familiers.level-gui";
                    default -> "familiers." + key;
                };
                if (r.isConfigurationSection(key)) {
                    overlay(merged, to, r.getConfigurationSection(key));
                } else {
                    merged.set(to, r.get(key));
                }
            }
        }
        // config.yml et langue repartent des fichiers par défaut commentés, avec les valeurs de l'ancien format
        if (oldConfig) {
            rename(config, file("config.yml.avant-decoupage"));
        }
        if (reglages.exists()) {
            rename(reglages, file("familiers-reglages.yml.avant-decoupage"));
        }
        langFile = "lang/" + merged.getString("langue", "fr_FR") + ".yml";
        save(merged, true);
        logger.info("Configuration découpée (config.yml, lang/, menus/, contenu/) ; anciens fichiers "
                + "gardés en .avant-decoupage.");
    }

    private void rename(File from, File to) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warning("Renommage de " + from.getName() + " impossible : " + e);
        }
    }
}
