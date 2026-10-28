package fr.influencraft.fluxenchant;

import org.bukkit.Color;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Rarity, display name and weight of the custom enchantments, read in EcoEnchants' files ({@code rarity:},
 * {@code display-name:}, file name = id; a file starting with « _ » is disabled). No dependency on EcoEnchants' code.
 */
final class Rarities {

    record Info(String id, String name, String rarity, int weight) {
    }

    private final Map<String, Info> byId = new HashMap<>();
    /** Colour of each rarity: first {@code &#RRGGBB} of its display name in EcoEnchants' rarity.yml. */
    private final Map<String, Color> colors = new HashMap<>();

    void loadColors(File rarityFile) {
        colors.clear();
        if (!rarityFile.isFile()) return;
        for (Map<?, ?> r : YamlConfiguration.loadConfiguration(rarityFile).getMapList("rarities")) {
            Object id = r.get("id"), name = r.get("display-name");
            if (id == null || name == null) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#([0-9a-fA-F]{6})").matcher(name.toString());
            if (m.find()) colors.put(id.toString().toLowerCase(Locale.ROOT), Color.fromRGB(Integer.parseInt(m.group(1), 16)));
        }
    }

    /** Colour of a rarity, white when unknown. */
    Color color(String rarity) {
        return colors.getOrDefault(rarity == null ? "" : rarity.toLowerCase(Locale.ROOT), Color.WHITE);
    }

    int load(File folder, ConfigurationSection weights) {
        byId.clear();
        if (!folder.isDirectory()) return 0;
        try (Stream<Path> files = Files.walk(folder.toPath())) {
            files.filter(p -> p.toString().endsWith(".yml") && !p.getFileName().toString().startsWith("_")).forEach(p -> {
                String id = p.getFileName().toString().replaceFirst("\\.yml$", "").toLowerCase(Locale.ROOT);
                YamlConfiguration c = YamlConfiguration.loadConfiguration(p.toFile());
                String rarity = c.getString("rarity", "").toLowerCase(Locale.ROOT).trim();
                String name = c.getString("display-name", id);
                byId.put(id, new Info(id, name, rarity, weights == null ? 0 : Math.max(0, weights.getInt(rarity, 0))));
            });
        } catch (IOException e) {
            return byId.size();
        }
        return byId.size();
    }

    /** The custom enchantment, or null (vanilla or unknown). */
    Info info(Enchantment enchantment) {
        return enchantment == null ? null : byId.get(enchantment.getKey().getKey());
    }

    Info info(String id) {
        return byId.get(id);
    }

    int weight(Enchantment enchantment) {
        Info i = info(enchantment);
        return i == null ? 0 : i.weight();
    }
}
