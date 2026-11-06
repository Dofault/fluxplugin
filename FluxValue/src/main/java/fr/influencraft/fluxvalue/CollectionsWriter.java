package fr.influencraft.fluxvalue;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Rewrites the tiers of the collections from the weights: last tier = what a player farms in the configured hours,
 * first tier = 1, the rest a geometric climb with a high second tier. Only {@code tier-requirements} changes. A
 * collection counting something without a weight (moves, sentences, fish) keeps its hand-written tiers.
 */
final class CollectionsWriter {

    private final Map<String, Double> weightByBlock = new HashMap<>();
    private final Map<String, Double> weightByTrigger = new HashMap<>();
    private final Model model;
    private final Logger log;

    CollectionsWriter(Model model, Logger log) {
        this.model = model;
        this.log = log;
        for (Model.Job job : model.jobs.values()) {
            for (Model.Source s : job.sources()) {
                for (String b : s.blocks()) weightByBlock.merge(b, s.weight(), Math::max);
                // a table use and a brew are counted one by one: the XP weight is per level or per bottle
                // kills are weighed per mob (mobWeights), never by the heaviest mob of the job
                if (s.blocks().isEmpty() && !s.filters().containsKey("entities")) weightByTrigger.merge(s.trigger(), s.weight(), Math::max);
            }
        }
    }

    int write(File folder) throws IOException {
        if (!folder.isDirectory()) return 0;
        int written = 0;
        List<File> files;
        try (Stream<java.nio.file.Path> walk = Files.walk(folder.toPath())) {
            files = walk.filter(p -> p.toString().endsWith(".yml") && !p.getFileName().toString().startsWith("_")).map(java.nio.file.Path::toFile).toList();
        }
        for (File file : files) {
            double unit = unitPoints(YamlConfiguration.loadConfiguration(file));
            if (unit <= 0) continue;
            if (replaceTiers(file, tiers(unit))) written++;
        }
        return written;
    }

    /** Points of one counted unit: the average of what the collection counts, 0 when it counts nothing with a weight. */
    private double unitPoints(YamlConfiguration yaml) {
        double sum = 0;
        int n = 0;
        for (Map<?, ?> method : yaml.getMapList("count-methods")) {
            String trigger = String.valueOf(method.get("trigger"));
            if (method.get("filters") instanceof Map<?, ?> f) {
                if (f.get("blocks") instanceof List<?> blocks) {
                    for (Object b : blocks) {
                        Double w = weightByBlock.get(b.toString());
                        if (w == null) continue;
                        sum += w;
                        n++;
                    }
                    continue;
                }
                if (f.get("entities") instanceof List<?> entities) {
                    for (Object e : entities) {
                        Double w = model.mobWeights.get(e.toString());
                        if (w == null) continue;
                        sum += w;
                        n++;
                    }
                    continue;
                }
                if (f.containsKey("custom_fish_type")) return 0;
            }
            Double w = weightByTrigger.get(trigger);
            if (w != null && trigger.equals("enchant_item")) {
                sum += w * 30; // collections count table uses; XP counts the level cost, always 30 at the optimal
                n++;
            } else if (w != null && !trigger.equals("catch_fish")) {
                sum += w;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    List<Integer> tiers(double unit) {
        double last = model.collectionHours * 3600 * model.pointsPerSecond() / unit;
        int count = model.collectionTiers;
        List<Integer> out = new ArrayList<>();
        out.add(1);
        double second = Math.pow(last, model.collectionSecondExponent);
        double factor = count > 2 ? Math.pow(last / second, 1.0 / (count - 2)) : 1;
        for (int i = 0; i < count - 1; i++) {
            double v = second * Math.pow(factor, i);
            int tier = (int) significant(v, i == count - 2 ? 3 : 2);
            out.add(Math.max(tier, out.get(out.size() - 1) + 1));
        }
        return out;
    }

    private static double significant(double v, int digits) {
        if (v < 1) return 1;
        double scale = Math.pow(10, Math.floor(Math.log10(v)) - digits + 1);
        return Math.round(v / scale) * scale;
    }

    /** Line by line, so the rest of the file (comments, toasts) is not reformatted. */
    private boolean replaceTiers(File file, List<Integer> tiers) throws IOException {
        List<String> in = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>(in.size());
        boolean found = false;
        for (int i = 0; i < in.size(); i++) {
            String line = in.get(i);
            if (!found && line.startsWith("tier-requirements:")) {
                found = true;
                out.add("tier-requirements:");
                for (int t : tiers) out.add("- " + t);
                while (i + 1 < in.size() && in.get(i + 1).trim().startsWith("- ")) i++;
                continue;
            }
            out.add(line);
        }
        if (!found) {
            log.warning("No tier-requirements in " + file.getName());
            return false;
        }
        if (!out.equals(in)) Files.write(file.toPath(), out, StandardCharsets.UTF_8);
        return true;
    }
}
