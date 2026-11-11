package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.fluxdungeons.rpg.core.Balance;
import fr.influencraft.fluxdungeons.rpg.core.Equipment;
import fr.influencraft.fluxdungeons.rpg.core.ClassDef;
import fr.influencraft.fluxdungeons.rpg.core.ConfigException;
import fr.influencraft.fluxdungeons.rpg.core.Node;
import fr.influencraft.fluxdungeons.rpg.core.StatRegistry;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The configs of the rpg ({@code plugins/FluxDungeons/rpg/}: stats.yml, classes.yml, balance.yml, equipment.yml),
 * read together and checked together. A refused set leaves the previous one in use and lists every problem; at start
 * without any valid set, the rpg stays off (the rest of the plugin runs).
 */
public final class RpgConfig {

    /** One valid set of configs. */
    public record Set(StatRegistry stats, Map<String, ClassDef> classes, Balance balance, Equipment equipment) {
    }

    private static final List<String> FILES = List.of("stats.yml", "classes.yml", "balance.yml", "equipment.yml");

    private final JavaPlugin plugin;
    private volatile Set current;

    public RpgConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** The configs in use (null when none was ever valid). */
    public Set get() {
        return current;
    }

    /** Reads the files again; returns the problems (empty = loaded and in use). */
    public List<String> load() {
        File dir = new File(plugin.getDataFolder(), "rpg");
        for (String f : FILES) {
            if (!new File(dir, f).exists()) plugin.saveResource("rpg/" + f, false);
        }
        List<String> problems = new ArrayList<>();
        try {
            StatRegistry stats = StatRegistry.load(node(dir, "stats.yml", problems));
            Map<String, ClassDef> classes = ClassDef.load(node(dir, "classes.yml", new ArrayList<>()), stats);
            Balance balance = Balance.load(node(dir, "balance.yml", new ArrayList<>()), stats, classes.keySet());
            Equipment equipment = Equipment.load(node(dir, "equipment.yml", new ArrayList<>()), stats, classes.keySet());
            current = new Set(stats, classes, balance, equipment);
            return List.of();
        } catch (ConfigException e) {
            problems.addAll(e.problems());
        } catch (IOException | RuntimeException e) {
            problems.add(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        for (String p : problems) plugin.getLogger().severe("rpg config: " + p);
        plugin.getLogger().severe("rpg configs refused, " + (current == null ? "the rpg is off" : "the previous ones stay in use"));
        return problems;
    }

    private static Node node(File dir, String file, List<String> problems) throws IOException {
        try (Reader in = Files.newBufferedReader(new File(dir, file).toPath(), StandardCharsets.UTF_8)) {
            return Node.root(file, new Yaml().load(in), problems);
        }
    }
}
