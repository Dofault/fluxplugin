package fr.influencraft.fluxmigration.migrations;

import fr.influencraft.fluxcrate.api.Crates;
import fr.influencraft.fluxmigration.FluxMigration;
import fr.influencraft.fluxmigration.Migration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-10-29, go-live of FluxCrate: the crates of EcoCrates ({@code plugins/EcoCrates}: crates, keys, rewards,
 * placed crates) written as FluxCrate crates. Skipped when FluxCrate already has crates. Keys already handed out
 * stay valid through FluxCrate's compat (they carry {@code ecocrates:key_id}). Remove once production runs FluxCrate.
 */
public final class EcoCratesToFluxCrate implements Migration {

    private static final Pattern NAME_ARG = Pattern.compile("name:\"([^\"]*)\"");
    private static final Pattern GIVE_KEY = Pattern.compile("ecocrates give (\\S+) key (\\S+)(?: (?:physical|virtual))?(?: (\\d+))?");

    private final FluxMigration plugin;

    public EcoCratesToFluxCrate(FluxMigration plugin) {
        this.plugin = plugin;
    }

    private File source(String path) {
        return new File(plugin.getDataFolder().getParentFile(), "EcoCrates/" + path);
    }

    @Override
    public String id() {
        return "2026-10-29_ecocrates_to_fluxcrate";
    }

    @Override
    public String description() {
        return "EcoCrates crates, keys, rewards and placed crates -> FluxCrate";
    }

    @Override
    public boolean ready() {
        return source("crates").isDirectory() && Bukkit.getPluginManager().isPluginEnabled("FluxCrate") && Crates.available();
    }

    @Override
    public String run() throws Exception {
        if (Crates.hasCrates()) return "FluxCrate already has crates, nothing imported";
        File[] files = source("crates").listFiles((d, n) -> n.endsWith(".yml") && !n.startsWith("_"));
        int crates = 0, rewards = 0, missing = 0;
        if (files != null) {
            for (File f : files) {
                String id = f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT);
                YamlConfiguration eco = YamlConfiguration.loadConfiguration(f);
                YamlConfiguration out = new YamlConfiguration();
                out.options().setHeader(List.of("Imported from EcoCrates (crates/" + f.getName() + ") on the first start of FluxCrate."));
                out.set("name", eco.getString("name", id));
                YamlConfiguration key = YamlConfiguration.loadConfiguration(source("keys/" + eco.getString("key", id) + ".yml"));
                String keyItem = key.getString("item", "TRIPWIRE_HOOK");
                out.set("key.item", spec(keyItem));
                out.set("key.name", nameArg(keyItem, id));
                out.set("key.lore", key.getStringList("lore"));
                List<?> frames = eco.getList("placed.hologram.frames", List.of());
                if (!frames.isEmpty() && frames.get(0) instanceof Map<?, ?> frame && frame.get("lines") instanceof List<?> lines) {
                    out.set("hologram", lines.stream().map(String::valueOf).toList());
                }
                List<?> particles = eco.getList("placed.particles", List.of());
                if (!particles.isEmpty() && particles.get(0) instanceof Map<?, ?> p && p.get("particle") != null) {
                    Matcher m = Pattern.compile("#[0-9A-Fa-f]{6}").matcher(String.valueOf(p.get("particle")));
                    if (m.find()) out.set("particle", m.group());
                }
                for (String rid : eco.getStringList("rewards")) {
                    File rf = source("rewards/" + rid + ".yml");
                    if (!rf.isFile()) {
                        missing++;
                        continue;
                    }
                    reward(out, rid, YamlConfiguration.loadConfiguration(rf));
                    rewards++;
                }
                Crates.save(id, out);
                crates++;
            }
        }
        int placed = 0;
        ConfigurationSection pc = YamlConfiguration.loadConfiguration(source("placedcrates.yml")).getConfigurationSection("crates");
        if (pc != null) {
            for (String k : pc.getKeys(false)) {
                Location l = location(k);
                if (l == null) continue;
                Crates.place(l, pc.getString(k, "").toLowerCase(Locale.ROOT));
                placed++;
            }
        }
        Crates.reload();
        return crates + " crates, " + rewards + " rewards (" + missing + " missing files), " + placed + " placed crates";
    }

    private static void reward(YamlConfiguration out, String rid, YamlConfiguration r) {
        String base = "rewards." + rid + ".";
        out.set(base + "name", r.getString("name", rid));
        out.set(base + "weight", r.getDouble("weight.value", r.getDouble("weight", 1)));
        String display = r.getString("display.item", "PAPER");
        out.set(base + "display.item", spec(display));
        Matcher model = Pattern.compile("custom_model_data:(\\d+)").matcher(display);
        if (model.find()) out.set(base + "display.model-data", Integer.parseInt(model.group(1)));
        if (!display.toLowerCase(Locale.ROOT).startsWith("eco") && display.matches(".*\\s[a-z_]+:\\d+.*")
                && !display.contains("custom_model_data")) {
            out.set(base + "display.glow", true); // an enchantment argument (« enchanted_book incassable:1 »)
        }
        List<String> lore = new ArrayList<>();
        for (String line : r.getStringList("display.lore")) if (!line.contains("%chance%")) lore.add(line);
        while (!lore.isEmpty() && lore.get(0).isBlank()) lore.remove(0);
        while (!lore.isEmpty() && lore.get(lore.size() - 1).isBlank()) lore.remove(lore.size() - 1);
        out.set(base + "display.lore", lore);
        List<String> commands = new ArrayList<>(), items = new ArrayList<>();
        for (Map<?, ?> effect : r.getMapList("win-effects")) {
            String type = String.valueOf(effect.get("id"));
            Object args = effect.get("args");
            if (!(args instanceof Map<?, ?> a)) continue;
            if (type.equals("run_command") && a.get("command") != null) {
                for (String c : String.valueOf(a.get("command")).split("\n")) commands.add(command(c.trim()));
            } else if (type.equals("give_item") && a.get("item") != null) {
                items.add(itemWithAmount(String.valueOf(a.get("item"))));
            }
        }
        out.set(base + "commands", commands);
        out.set(base + "items", items);
    }

    /** « ecocrates give %player% key tendance physical 2 » -> « fluxcrate give %player% tendance 2 ». */
    private static String command(String c) {
        Matcher m = GIVE_KEY.matcher(c);
        if (!m.find()) return c;
        return m.replaceFirst("fluxcrate give " + Matcher.quoteReplacement(m.group(1)) + " " + m.group(2)
                + " " + (m.group(3) == null ? "1" : m.group(3)));
    }

    /**
     * An item string of EcoCrates as a Flux item spec: ItemsAdder items and materials as Flux writes them (arguments
     * dropped: name given by FluxCrate, model data and shine read apart, the amount kept apart); EcoItems and
     * EcoArmor items kept with their arguments.
     */
    static String spec(String eco) {
        String s = NAME_ARG.matcher(eco).replaceAll("").trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ecoarmor:") || lower.startsWith("ecoitems:")) return s;
        String first = s.split(" ")[0];
        if (first.toLowerCase(Locale.ROOT).startsWith("itemsadder:")) {
            return "itemsadder:" + first.substring(11).replace("__", ":");
        }
        return first.toUpperCase(Locale.ROOT);
    }

    private static String itemWithAmount(String eco) {
        String s = NAME_ARG.matcher(eco).replaceAll("").trim();
        String[] parts = s.split(" ");
        String amount = parts.length > 1 && parts[1].matches("\\d+") ? " " + parts[1] : "";
        return spec(eco) + amount;
    }

    private static String nameArg(String eco, String fallback) {
        Matcher m = NAME_ARG.matcher(eco);
        return m.find() ? m.group(1) : fallback;
    }

    private static Location location(String key) {
        int at = key.lastIndexOf('@');
        if (at < 0) return null;
        String name = key.substring(0, at);
        World w = Bukkit.getWorld(name);
        if (w == null) for (World any : Bukkit.getWorlds()) if (any.getName().equalsIgnoreCase(name)) w = any;
        String[] p = key.substring(at + 1).split(",");
        if (w == null || p.length != 3) return null;
        try {
            return new Location(w, Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
