package fr.influencraft.fluxmigration.migrations;

import fr.influencraft.fluxfamily.api.Family;
import fr.influencraft.fluxmigration.FluxMigration;
import fr.influencraft.fluxmigration.Migration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-30, FluxFamily replaces MarriagePlus: its marriages (with their date and the couple's first home) and its
 * adoptions copied to FluxFamily. Every MarriagePlus couple was married: they stay married, without paying. Couple XP,
 * quests, achievements and love notes are not kept (FluxFamily has none).
 *
 * <p>MarriagePlus keeps one YAML document: {@code plugins/MarriagePlus/data/data.yml}, or with {@code type: MYSQL} in
 * {@code configs/storage.yml}, the same document in the column {@code data_value} of {@code <prefix>data}. Keys read:
 * {@code marriages.<a>: <b>}, {@code marriage-dates.<uuid>} (ms), {@code homes.<uuid>.<name>.world/x/y/z/yaw/pitch},
 * {@code families.<id>.adopted-children} (list), {@code .child-parents.<child>} (list) and {@code .parent-one}.</p>
 *
 * Runs once MarriagePlus is removed. Source: {@code plugins/MarriagePlus/}, or once archived (Labs, 2026-10-30) the
 * newest {@code plugins/.archives/MarriagePlus-<date>/MarriagePlus/}. Remove once production ran it.
 */
public final class MarriagePlusToFluxFamily implements Migration {

    private final FluxMigration plugin;

    public MarriagePlusToFluxFamily(FluxMigration plugin) {
        this.plugin = plugin;
    }

    /** MarriagePlus' data folder: in place, else the newest archived copy. */
    private File folder() {
        File plugins = plugin.getDataFolder().getParentFile();
        File live = new File(plugins, "MarriagePlus");
        if (live.isDirectory()) return live;
        File[] archived = new File(plugins, ".archives").listFiles(f -> f.getName().startsWith("MarriagePlus-")
                && new File(f, "MarriagePlus").isDirectory());
        if (archived == null || archived.length == 0) return live;
        java.util.Arrays.sort(archived, java.util.Comparator.comparing(File::getName));
        return new File(archived[archived.length - 1], "MarriagePlus");
    }

    @Override
    public String id() {
        return "2026-10-30_marriageplus_to_fluxfamily";
    }

    @Override
    public String description() {
        return "MarriagePlus marriages, homes and adoptions -> FluxFamily";
    }

    @Override
    public boolean ready() {
        return new File(folder(), "configs/storage.yml").isFile() && Family.available()
                && !Bukkit.getPluginManager().isPluginEnabled("MarriagePlus");
    }

    /** The data document, from the storage MarriagePlus was using. */
    private YamlConfiguration data() throws Exception {
        YamlConfiguration storage = YamlConfiguration.loadConfiguration(new File(folder(), "configs/storage.yml"));
        if (!"MYSQL".equalsIgnoreCase(storage.getString("type", "YAML"))) {
            return YamlConfiguration.loadConfiguration(new File(folder(), "data/data.yml"));
        }
        ConfigurationSection m = storage.getConfigurationSection("mysql");
        if (m == null) throw new IllegalStateException("storage.yml: no mysql section");
        String url = "jdbc:mysql://" + m.getString("host", "127.0.0.1") + ":" + m.getInt("port", 3306) + "/"
                + m.getString("database") + "?useSSL=" + m.getBoolean("use-ssl", false);
        String table = m.getString("table-prefix", "marriageplus_") + "data";
        try (Connection c = DriverManager.getConnection(url, m.getString("username"), m.getString("password"));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT data_value FROM " + table)) {
            while (rs.next()) {
                YamlConfiguration doc = new YamlConfiguration();
                doc.loadFromString(rs.getString(1));
                if (doc.contains("marriages")) return doc;
            }
        }
        throw new IllegalStateException("no MarriagePlus document in " + table);
    }

    @Override
    public String run() throws Exception {
        YamlConfiguration data = data();
        int couples = 0, homes = 0, kept = 0, adoptions = 0, adoptionsKept = 0;
        ConfigurationSection marriages = data.getConfigurationSection("marriages");
        Set<UUID> done = new HashSet<>();
        if (marriages != null) for (String key : marriages.getKeys(false)) {
            UUID a = uuid(key), b = uuid(marriages.getString(key));
            if (a == null || b == null || done.contains(a) || done.contains(b)) continue;
            done.add(a);
            done.add(b);
            long since = data.getLong("marriage-dates." + a, data.getLong("marriage-dates." + b, System.currentTimeMillis()));
            Location home = home(data, a);
            if (home == null) home = home(data, b);
            if (Family.importCouple(a, b, true, since, home)) {
                couples++;
                if (home != null) homes++;
            } else {
                kept++;
            }
        }
        ConfigurationSection families = data.getConfigurationSection("families");
        if (families != null) for (String id : families.getKeys(false)) {
            ConfigurationSection f = families.getConfigurationSection(id);
            if (f == null) continue;
            for (String childKey : f.getStringList("adopted-children")) {
                UUID child = uuid(childKey);
                List<String> parents = f.getStringList("child-parents." + childKey);
                UUID parent = uuid(parents.isEmpty() ? f.getString("parent-one") : parents.get(0));
                if (child == null || parent == null) continue;
                if (Family.importAdoption(parent, child, f.getLong("created-at", System.currentTimeMillis()))) adoptions++;
                else adoptionsKept++;
            }
        }
        return couples + " couple(s) (" + homes + " home(s)), " + kept + " already in FluxFamily, "
                + adoptions + " adoption(s), " + adoptionsKept + " already with a parent";
    }

    /** First couple home of a player, null when none or its world is missing. */
    private static Location home(YamlConfiguration data, UUID player) {
        ConfigurationSection homes = data.getConfigurationSection("homes." + player);
        if (homes == null) return null;
        for (String name : homes.getKeys(false)) {
            ConfigurationSection h = homes.getConfigurationSection(name);
            World w = h == null ? null : Bukkit.getWorld(h.getString("world", ""));
            if (w != null) return new Location(w, h.getDouble("x"), h.getDouble("y"), h.getDouble("z"),
                    (float) h.getDouble("yaw"), (float) h.getDouble("pitch"));
        }
        return null;
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
