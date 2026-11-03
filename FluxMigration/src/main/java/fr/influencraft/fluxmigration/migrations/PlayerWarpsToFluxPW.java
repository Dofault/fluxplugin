package fr.influencraft.fluxmigration.migrations;

import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.fluxmigration.FluxMigration;
import fr.influencraft.fluxmigration.Migration;
import fr.influencraft.fluxpw.api.PlayerWarps;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-10-30, FluxPW replaces PlayerWarps (production) and its fork InfluPW (Labs): every warp, with its location
 * (kept even when its world is not loaded), icon, description, category, status, password, admission fee, visits,
 * ratings, visitors, blocked players and sponsored place, then the favourites of every player.
 *
 * <p>Source: {@code plugins/PlayerWarps/data.yml}, else {@code plugins/InfluPW/data.yml} ({@code warps.<uuid>}: keys
 * {@code name}, {@code display-name}, {@code owner-id}, {@code owner-name}, {@code loc} (Bukkit location map,
 * {@code world} or {@code world_key}), {@code item} (Bukkit item map), {@code lore}, {@code category} (or the older
 * {@code type}), {@code status}, {@code password}, {@code admission}, {@code visits}, {@code ratings} (sum of stars),
 * {@code reviewers}, {@code unique-visitors}, {@code blocked-players}, {@code date-created}, {@code featured}
 * (end of the sponsored place, ms)), and {@code userdata/<uuid>.yml} ({@code favorites}: warp ids). The file is read
 * as plain YAML: the warp class of the former plugin is no longer there.</p>
 *
 * Runs once the former plugin is removed (its data folder stays until archived). Remove once production ran it.
 */
public final class PlayerWarpsToFluxPW implements Migration {

    private final FluxMigration plugin;

    public PlayerWarpsToFluxPW(FluxMigration plugin) {
        this.plugin = plugin;
    }

    /** Data folder of the former plugin: PlayerWarps (production), else InfluPW (Labs). */
    private File folder() {
        File plugins = plugin.getDataFolder().getParentFile();
        File prod = new File(plugins, "PlayerWarps");
        return new File(prod, "data.yml").isFile() ? prod : new File(plugins, "InfluPW");
    }

    @Override
    public String id() {
        return "2026-10-30_playerwarps_to_fluxpw";
    }

    @Override
    public String description() {
        return "PlayerWarps / InfluPW warps and favourites -> FluxPW";
    }

    @Override
    public boolean ready() {
        return new File(folder(), "data.yml").isFile() && PlayerWarps.available()
                && !Bukkit.getPluginManager().isPluginEnabled("PlayerWarps") && !Bukkit.getPluginManager().isPluginEnabled("InfluPW");
    }

    @Override
    public String run() throws Exception {
        File folder = folder();
        Object root;
        try (Reader in = new FileReader(new File(folder, "data.yml"), StandardCharsets.UTF_8)) {
            root = new Yaml().load(in);
        }
        Map<?, ?> warps = root instanceof Map<?, ?> m && m.get("warps") instanceof Map<?, ?> w ? w : Map.of();
        int imported = 0, kept = 0, broken = 0;
        for (Map.Entry<?, ?> e : warps.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> raw)) {
                broken++;
                continue;
            }
            PlayerWarps.Import warp = warp(String.valueOf(e.getKey()), raw);
            if (warp == null) broken++;
            else if (PlayerWarps.importWarp(warp)) imported++;
            else kept++;
        }
        int favorites = 0;
        File[] users = new File(folder, "userdata").listFiles((dir, name) -> name.endsWith(".yml"));
        if (users != null) for (File user : users) {
            UUID player = uuid(user.getName().substring(0, user.getName().length() - 4));
            if (player == null) continue;
            for (String id : YamlConfiguration.loadConfiguration(user).getStringList("favorites")) {
                UUID warp = uuid(id);
                if (warp != null && PlayerWarps.importFavorite(player, warp)) favorites++;
            }
        }
        return imported + " warp(s), " + kept + " already in FluxPW (same id or name), " + broken + " unreadable, "
                + favorites + " favourite(s) from " + folder.getName();
    }

    private PlayerWarps.Import warp(String key, Map<?, ?> w) {
        UUID id = uuid(text(w.get("uuid")) != null ? text(w.get("uuid")) : key);
        UUID owner = uuid(text(w.get("owner-id")));
        String name = text(w.get("name"));
        if (id == null || owner == null || name == null) return null;
        Map<?, ?> loc = w.get("loc") instanceof Map<?, ?> l ? l : Map.of();
        String world = worldName(loc);
        String category = text(w.get("category"));
        if (category == null) category = text(w.get("type"));
        return new PlayerWarps.Import(id, name, text(w.get("display-name")), owner, text(w.get("owner-name")), world,
                number(loc.get("x")), number(loc.get("y")), number(loc.get("z")), (float) number(loc.get("yaw")),
                (float) number(loc.get("pitch")), icon(w.get("item")), text(w.get("lore")), category, text(w.get("status")),
                text(w.get("password")), (int) number(w.get("admission")), (int) number(w.get("visits")),
                (int) number(w.get("ratings")), (long) number(w.get("date-created")), (long) number(w.get("featured")),
                uuids(w.get("reviewers")), uuids(w.get("unique-visitors")), uuids(w.get("blocked-players")));
    }

    /** World name of a Bukkit location map: {@code world} (name) or {@code world_key} ({@code minecraft:valdor}). */
    private static String worldName(Map<?, ?> loc) {
        String name = text(loc.get("world"));
        if (name != null) return name;
        String key = text(loc.get("world_key"));
        if (key == null) return null;
        NamespacedKey k = NamespacedKey.fromString(key);
        World w = k == null ? null : Bukkit.getWorld(k);
        if (w != null) return w.getName();
        return key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
    }

    /** Icon of the former warp item: its custom item id, else its material; null when none or unreadable. */
    @SuppressWarnings("unchecked")
    private String icon(Object item) {
        if (item instanceof String s) return s.isBlank() ? null : s.trim().toUpperCase(java.util.Locale.ROOT);
        if (!(item instanceof Map<?, ?> m)) return null;
        try {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
            copy.remove("==");
            ItemStack stack = ItemStack.deserialize(copy);
            if (stack.getType().isAir()) return null;
            String custom = CustomItems.id(stack);
            return custom != null ? custom : stack.getType().name();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Warp icon not readable, default icon kept: " + e.getMessage());
            return null;
        }
    }

    /** Text of a value; null for null, empty, or the word "null" (the former plugin wrote missing passwords so). */
    private static String text(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        return s.isBlank() || s.equals("null") ? null : s;
    }

    private static double number(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? 0 : Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<UUID> uuids(Object o) {
        List<UUID> out = new ArrayList<>();
        if (o instanceof Collection<?> c) for (Object v : c) {
            UUID id = uuid(String.valueOf(v));
            if (id != null) out.add(id);
        }
        return out;
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
