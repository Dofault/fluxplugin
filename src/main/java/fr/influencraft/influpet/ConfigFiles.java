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
 * - lang/<language>.yml : textes affichés aux joueurs (chemins de LANG) ;
 * - menus/pets.yml, menus/progression.yml : menus des familiers (sous pet-settings.gui / pet-settings.level-gui) ;
 * - content/cards.yml, content/boosters.yml, content/pets.yml : cartes, boosters, familiers obtenables ;
 * - effects.yml : effets de familiers codés dans le plugin (lu par PetEffects).
 * Au chargement, chaque fichier est lu par-dessus sa version par défaut du jar (une clé ajoutée par une mise à
 * jour du plugin existe donc toujours) ; à l'enregistrement (menu d'édition), chaque chemin repart dans son
 * fichier, et seuls les fichiers modifiés sont réécrits (les commentaires de config.yml et de la langue sont
 * gardés). Les anciens formats (config.yml d'un seul tenant, puis clés et fichiers en français jusqu'au
 * 2026-10-08) sont convertis une fois, les anciens fichiers gardés avec le suffixe .legacy.
 */
public final class ConfigFiles {

    /** Chemins rangés dans le fichier de langue (préfixes). */
    static final List<String> LANG = List.of("messages", "editor", "reveal.actionbar", "reveal.subtitle",
            "reveal.back-title", "reveal.actionbar-pet", "reveal.actionbar-pet-duplicate",
            "reveal.actionbar-pet-duplicate-incomplete", "pet-reveal.title", "pet-reveal.subtitle", "card-format",
            "pets.name-format", "pet-settings.pet-entity.name", "pet-settings.level-up.message.message",
            "pet-settings.messages");
    /** Préfixe -> fichier de contenu (le fichier garde le chemin complet). */
    static final Map<String, String> CONTENT = new LinkedHashMap<>();
    /** Préfixe -> fichier de menu (le fichier contient la section elle-même, sans le préfixe). */
    static final Map<String, String> MENUS = new LinkedHashMap<>();
    /** Langues livrées dans le jar. */
    static final List<String> LANGUAGES = List.of("en_US", "fr_FR");
    static final String DEFAULT_LANGUAGE = "en_US";

    static {
        CONTENT.put("card-xp", "content/cards.yml");
        CONTENT.put("cards", "content/cards.yml");
        CONTENT.put("boosters", "content/boosters.yml");
        CONTENT.put("pets.list", "content/pets.yml");
        MENUS.put("pet-settings.gui", "menus/pets.yml");
        MENUS.put("pet-settings.level-gui", "menus/progression.yml");
    }

    private final File folder;
    private final java.util.function.Function<String, InputStream> resources;
    private final java.util.logging.Logger logger;
    private String langFile = "lang/" + DEFAULT_LANGUAGE + ".yml";

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
        List<String> names = new java.util.ArrayList<>(List.of("config.yml", "menus/pets.yml", "menus/progression.yml",
                "content/cards.yml", "content/boosters.yml", "content/pets.yml", "effects.yml", "chains.yml"));
        LANGUAGES.forEach(l -> names.add("lang/" + l + ".yml"));
        for (String name : names) {
            if (!file(name).exists()) {
                try (InputStream in = resources.apply(name)) {
                    if (in != null) {
                        Files.createDirectories(file(name).toPath().getParent());
                        Files.copy(in, file(name).toPath());
                    }
                } catch (IOException e) {
                    logger.warning("Could not write default file " + name + ": " + e);
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
        langFile = "lang/" + merged.getString("language", DEFAULT_LANGUAGE) + ".yml";
        overlay(merged, "", resource("lang/" + DEFAULT_LANGUAGE + ".yml"));
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
            logger.warning("Unreadable resource " + name + ": " + e);
        }
        return yaml;
    }

    private YamlConfiguration read(File f) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (f.exists()) {
            try {
                yaml.load(f);
            } catch (IOException | InvalidConfigurationException e) {
                logger.severe(f.getName() + " is unreadable (" + e.getMessage() + "): using defaults.");
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
            logger.severe("Could not write " + name + ": " + e);
        }
    }

    // ------------------------------------------------------------------ anciens formats

    /** Ancien chemin (français) -> nouveau ; null : clé abandonnée. Préfixes, le plus long d'abord. */
    private static final Map<String, String> RENAMES = new LinkedHashMap<>();

    static {
        RENAMES.put("langue", "language");
        RENAMES.put("powder-snow-pet", null);
        RENAMES.put("familiers.stockage-local", "pet-settings.local-storage");
        RENAMES.put("familiers.desactiver-si-conditions-echouent", "pet-settings.deactivate-on-failed-conditions");
        RENAMES.put("familiers.montures.actives", "pet-settings.mounts.enabled");
        RENAMES.put("familiers.actifs.defaut", "pet-settings.active-pets.default");
        RENAMES.put("familiers.actifs.maximum", "pet-settings.active-pets.maximum");
        // ancienne case unique du familier actif : remplacée par les emplacements pet-slots
        RENAMES.put("familiers.gui.pet-info.active", "pet-settings.gui.pet-slots.active");
        RENAMES.put("familiers.gui.pet-info.no-active", "pet-settings.gui.pet-slots.empty");
        RENAMES.put("familiers.gui.pet-info", null);
        RENAMES.put("familiers", "pet-settings");
        RENAMES.put("familier-suivi.hauteur-nom", "pet-follow.name-height");
        RENAMES.put("familier-suivi.hauteur", "pet-follow.height");
        RENAMES.put("familier-suivi.distance-min", "pet-follow.min-distance");
        RENAMES.put("familier-suivi.distance-max", "pet-follow.max-distance");
        RENAMES.put("familier-suivi.lissage", "pet-follow.smoothing");
        RENAMES.put("familier-suivi.vitesse-min", "pet-follow.min-speed");
        RENAMES.put("familier-suivi.flottement", "pet-follow.bob-amplitude");
        RENAMES.put("familier-suivi.ecart-file-min", "pet-follow.queue-gap-min");
        RENAMES.put("familier-suivi.ecart-file-max", "pet-follow.queue-gap-max");
        RENAMES.put("familier-suivi", "pet-follow");
    }

    /** Nouveau chemin d'un ancien chemin (inchangé s'il n'est pas concerné), null s'il est abandonné. */
    static String rename(String path) {
        for (Map.Entry<String, String> e : RENAMES.entrySet()) {
            if (matches(path, e.getKey())) {
                return e.getValue() == null ? null : e.getValue() + path.substring(e.getKey().length());
            }
        }
        return path;
    }

    /** Valeurs d'un ancien format : préfixes de contenu de booster et placeholder %adopte:<id>% en anglais. */
    private static Object renameValue(String path, Object value) {
        if (value instanceof List<?> list && path.startsWith("boosters.")) {
            return list.stream().map(v -> v instanceof String str ? legacyText(str) : v).toList();
        }
        return value instanceof String str ? legacyText(str) : value;
    }

    private static String legacyText(String text) {
        return text.replaceFirst("^carte:", "card:").replaceFirst("^familier:", "pet:").replace("%adopte:", "%adopted:");
    }

    /**
     * Convertit une fois les anciens formats dans les fichiers actuels :
     * - config.yml d'un seul tenant et familiers-reglages.yml (avant le découpage) ;
     * - clés et fichiers en français (contenu/, menus/familiers.yml, effets.yml, clés langue, familiers,
     *   familier-suivi... jusqu'au 2026-10-08).
     * Les anciens fichiers sont gardés avec le suffixe .legacy.
     */
    void migrate() {
        migrateEffects();
        migratePetFiles(file("pets"), logger);
        File config = file("config.yml");
        YamlConfiguration current = read(config);
        boolean monolithic = config.exists() && !file("contenu/boosters.yml").exists()
                && !file("content/boosters.yml").exists()
                && (current.contains("cards") || current.contains("boosters") || current.contains("messages"));
        boolean french = current.contains("langue") || current.contains("familiers") || current.contains("familier-suivi")
                || file("contenu").isDirectory() || file("menus/familiers.yml").exists();
        File reglages = file("familiers-reglages.yml");
        if (!monolithic && !french && !reglages.exists()) {
            return;
        }
        // ancienne vue d'ensemble, chemins d'origine
        YamlConfiguration legacy = new YamlConfiguration();
        overlay(legacy, "", current);
        String language = current.getString("langue", current.getString("language", "fr_FR"));
        File oldLang = file("lang/" + language + ".yml");
        overlay(legacy, "", read(oldLang));
        overlay(legacy, "familiers.gui", read(file("menus/familiers.yml")));
        overlay(legacy, "familiers.level-gui", read(file("menus/progression.yml")));
        for (String name : List.of("contenu/cartes.yml", "contenu/boosters.yml", "contenu/familiers.yml")) {
            overlay(legacy, "", read(file(name)));
        }
        if (reglages.exists()) {
            YamlConfiguration r = read(reglages);
            for (String key : r.getKeys(false)) {
                if (r.isConfigurationSection(key)) {
                    overlay(legacy, "familiers." + key, r.getConfigurationSection(key));
                } else {
                    legacy.set("familiers." + key, r.get(key));
                }
            }
        }
        // nouvelle vue : valeurs par défaut du jar, puis les anciennes valeurs sous leur nouveau chemin
        YamlConfiguration merged = new YamlConfiguration();
        overlay(merged, "", resource("config.yml"));
        overlay(merged, "", resource("lang/" + DEFAULT_LANGUAGE + ".yml"));
        overlay(merged, "", resource("lang/" + language + ".yml"));
        for (Map.Entry<String, String> e : MENUS.entrySet()) {
            overlay(merged, e.getKey(), resource(e.getValue()));
        }
        for (String key : legacy.getKeys(true)) {
            String to = rename(key);
            if (to == null) {
                continue;
            }
            if (legacy.isConfigurationSection(key)) {
                if (legacy.getConfigurationSection(key).getKeys(false).isEmpty() && !merged.isConfigurationSection(to)) {
                    merged.createSection(to);
                }
            } else {
                merged.set(to, renameValue(to, legacy.get(key)));
            }
        }
        merged.set("language", language);
        // anciens fichiers mis de côté : les nouveaux repartent des fichiers par défaut commentés
        for (String name : List.of("config.yml", "familiers-reglages.yml", "menus/familiers.yml",
                "menus/progression.yml", "contenu", "lang/" + language + ".yml")) {
            if (file(name).exists()) {
                rename(file(name), file(name + ".legacy"));
            }
        }
        langFile = "lang/" + language + ".yml";
        save(merged, true);
        logger.info("Legacy configuration converted (config.yml, lang/, menus/, content/); old files kept as .legacy.");
    }

    /** Effets codés : effets.yml (ou familiers.yml, plus ancien) en français -> effects.yml. */
    private void migrateEffects() {
        File old = file("effets.yml").exists() ? file("effets.yml") : file("familiers.yml");
        if (!old.exists() || file("effects.yml").exists()) {
            return;
        }
        YamlConfiguration from = read(old);
        if (!from.contains("effets") && !from.contains("niveau-max")) {
            return;
        }
        Map<String, String> ids = Map.ofEntries(Map.entry("xp-pierre", "stone-xp"),
                Map.entry("sans-mise-a-jour", "no-block-update"), Map.entry("replant-cacao", "cocoa-replant"),
                Map.entry("poudre-os-instant", "instant-bone-meal"), Map.entry("beton-instant", "instant-concrete"),
                Map.entry("cuisson-rapide", "fast-smelting"), Map.entry("livraison-four", "furnace-delivery"),
                Map.entry("usure-savante", "enchant-mastery"), Map.entry("lapis-econome", "lapis-saver"),
                Map.entry("niveaux-econome", "level-saver"), Map.entry("resistance-allies", "ally-resistance"),
                Map.entry("neige-poudreuse", "powder-snow"), Map.entry("rage", "rage"));
        Map<String, String> keys = Map.of("familier", "pet", "blocs", "blocks", "recharge", "cooldown",
                "message-fin", "end-message", "intervalle-ticks", "interval-ticks", "rayon", "radius");
        YamlConfiguration to = new YamlConfiguration();
        to.options().setHeader(resource("effects.yml").options().getHeader());
        to.set("max-level", from.getInt("niveau-max", 10));
        to.createSection("effects");
        ConfigurationSection effects = from.getConfigurationSection("effets");
        for (String id : effects == null ? java.util.Set.<String>of() : effects.getKeys(false)) {
            ConfigurationSection e = effects.getConfigurationSection(id);
            if (e == null) {
                continue;
            }
            String base = "effects." + ids.getOrDefault(id, id);
            for (String k : e.getKeys(false)) {
                Object v = e.get(k);
                to.set(base + "." + keys.getOrDefault(k, k), v instanceof String str ? str.replace("%secondes%", "%seconds%") : v);
            }
        }
        try {
            Files.writeString(file("effects.yml").toPath(), to.saveToString(), StandardCharsets.UTF_8);
            rename(old, file(old.getName() + ".legacy"));
            logger.info("Coded pet effects converted: " + old.getName() + " -> effects.yml.");
        } catch (IOException e) {
            logger.severe("Could not convert " + old.getName() + ": " + e);
        }
    }

    /** Fichiers de familiers : clés vol / montable (avant 2026-10-08) -> flying / mountable, sur place. */
    public static void migratePetFiles(File dir, java.util.logging.Logger logger) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        int count = 0;
        for (File f : files == null ? new File[0] : files) {
            try {
                String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                String converted = text.replaceAll("(?m)^vol:", "flying:").replaceAll("(?m)^montable:", "mountable:");
                if (!converted.equals(text)) {
                    Files.writeString(f.toPath(), converted, StandardCharsets.UTF_8);
                    count++;
                }
            } catch (IOException e) {
                logger.warning("Could not convert " + f.getName() + ": " + e);
            }
        }
        if (count > 0) {
            logger.info(count + " pet files converted (vol/montable -> flying/mountable).");
        }
    }

    private void rename(File from, File to) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warning("Could not rename " + from.getName() + ": " + e);
        }
    }
}
