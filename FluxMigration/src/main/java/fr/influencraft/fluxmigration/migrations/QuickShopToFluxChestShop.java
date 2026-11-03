package fr.influencraft.fluxmigration.migrations;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.influencraft.fluxcommerce.api.Shops;
import fr.influencraft.fluxmigration.FluxMigration;
import fr.influencraft.fluxmigration.Migration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 2026-10-31, FluxChestShop is rewritten for the suite (merged into FluxCommerce on 2026-11-01, id kept): the shops of QuickShop-Hikari (production) and of the former
 * QuickShop fork named FluxChestShop (Labs, its folder moved to {@code plugins/FluxChestShop.fork/} by the new plugin)
 * become shops of the new plugin, with their owner, item and bundle, price, selling or buying, unlimited flag, friends
 * (and managers, who may fill the shop too) and options (town shop).
 *
 * <p>Source: the database set in the former plugin's {@code config.yml} ({@code database}: MySQL/MariaDB when
 * {@code mysql: true}, else its H2 file {@code shops.mv.db}, read with the H2 driver kept in its {@code lib/} folder),
 * tables {@code <prefix>shop_map} (world, x, y, z, shop), {@code <prefix>shops} (id, data), {@code <prefix>data}
 * (owner, item YAML, encoded item bytes in Base64, type 0 selling / 1 buying, price, unlimited, permissions JSON
 * {uuid: group}, extra JSON, create_time). Each shop is imported once (source {@code quickshop:<shop id>}); a block
 * that already holds a shop of the new plugin is skipped. Nothing is written in the former database.</p>
 *
 * Remove once production ran it.
 */
public final class QuickShopToFluxChestShop implements Migration {

    private final FluxMigration plugin;

    public QuickShopToFluxChestShop(FluxMigration plugin) {
        this.plugin = plugin;
    }

    /** Data folder of the former plugin: QuickShop-Hikari (production), else the moved fork folder (Labs). */
    private File folder() {
        File plugins = plugin.getDataFolder().getParentFile();
        for (String name : List.of("QuickShop-Hikari", "FluxChestShop.fork")) {
            File f = new File(plugins, name);
            if (new File(f, "config.yml").isFile()) return f;
        }
        return null;
    }

    @Override
    public String id() {
        return "2026-10-31_quickshop_to_fluxchestshop";
    }

    @Override
    public String description() {
        return "QuickShop-Hikari / former FluxChestShop fork shops -> FluxCommerce (chest shops)";
    }

    @Override
    public boolean ready() {
        return folder() != null && Shops.available() && !Bukkit.getPluginManager().isPluginEnabled("QuickShop-Hikari");
    }

    @Override
    public String run() throws Exception {
        File folder = folder();
        ConfigurationSection db = YamlConfiguration.loadConfiguration(new File(folder, "config.yml")).getConfigurationSection("database");
        if (db == null) throw new IllegalStateException("no database section in " + folder.getName() + "/config.yml");
        int imported = 0, kept = 0, broken = 0;
        try (Connection c = connect(folder, db)) {
            String prefix = db.getString("prefix", "");
            Map<String, String> tables = tables(c);
            String map = table(tables, prefix, "shop_map"), shops = table(tables, prefix, "shops"), data = table(tables, prefix, "data");
            if (map == null || shops == null || data == null) {
                return "no shop table in " + folder.getName() + " (prefix '" + prefix + "'): nothing to import";
            }
            Map<String, String> names = names(c, table(tables, prefix, "players"));
            String sql = "SELECT m.world, m.x, m.y, m.z, s.id AS shop_id, d.owner, d.item, d.encoded, d.type, d.price,"
                    + " d.unlimited, d.permissions, d.extra, d.create_time FROM " + map + " m JOIN " + shops
                    + " s ON s.id = m.shop JOIN " + data + " d ON d.id = s.data";
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    Shops.Import shop;
                    try {
                        shop = shop(rs, names);
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("Shop " + rs.getLong("shop_id") + " not readable: " + e.getMessage());
                        shop = null;
                    }
                    if (shop == null) broken++;
                    else if (Shops.importShop(shop)) imported++;
                    else kept++;
                }
            }
        }
        return imported + " shop(s), " + kept + " already in FluxCommerce, " + broken + " unreadable, from " + folder.getName();
    }

    private Shops.Import shop(ResultSet rs, Map<String, String> names) throws SQLException {
        ItemStack item = item(rs.getString("encoded"), rs.getString("item"));
        if (item == null || item.getType().isAir()) return null;
        String rawOwner = rs.getString("owner");
        UUID owner = owner(rawOwner);
        boolean unlimited = rs.getBoolean("unlimited");
        if (owner == null && !unlimited) return null;
        String ownerName = owner == null ? null : names.get(owner.toString());
        if (owner != null && ownerName == null) {
            OfflinePlayer o = Bukkit.getOfflinePlayer(owner);
            ownerName = o.getName() != null ? o.getName() : rawOwner;
        }
        List<UUID> friends = new ArrayList<>();
        JsonObject permissions = json(rs.getString("permissions"));
        if (permissions != null) for (Map.Entry<String, JsonElement> e : permissions.entrySet()) {
            String group = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : "";
            UUID id = uuid(e.getKey());
            if (id != null && (group.endsWith(".friend") || group.endsWith(".staff"))) friends.add(id);
        }
        Set<String> options = new LinkedHashSet<>();
        JsonObject extra = json(rs.getString("extra"));
        if (extra != null && extra.has("fluxchestshop:options")) {
            for (String o : extra.get("fluxchestshop:options").getAsString().split(",")) if (!o.isBlank()) options.add(o.trim());
        }
        Timestamp created = rs.getTimestamp("create_time");
        return new Shops.Import("quickshop:" + rs.getLong("shop_id"), world(rs.getString("world")), rs.getInt("x"), rs.getInt("y"),
                rs.getInt("z"), owner, ownerName, item, rs.getDouble("price"), rs.getInt("type") == 1, unlimited, friends, options,
                created == null ? 0 : created.getTime());
    }

    /** Paper's item bytes (column {@code encoded}), else the Bukkit YAML of column {@code item}. */
    private static ItemStack item(String encoded, String yaml) {
        if (encoded != null && !encoded.isBlank()) {
            try {
                return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded.trim()));
            } catch (RuntimeException ignored) {
                // older shops: YAML only
            }
        }
        if (yaml == null || yaml.isBlank()) return null;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(yaml);
            return y.getItemStack("item");
        } catch (Exception e) {
            return null;
        }
    }

    /** A QuickShop user: a UUID, a player name, or "[name]" for a server account (null). */
    private static UUID owner(String raw) {
        if (raw == null || raw.isBlank() || raw.startsWith("[")) return null;
        UUID id = uuid(raw);
        if (id != null) return id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0 ? null : id;
        OfflinePlayer o = Bukkit.getOfflinePlayerIfCached(raw.trim());
        return o == null ? null : o.getUniqueId();
    }

    /** World names are lower case since Paper 26.2: the stored name, else its lower case form when that one exists. */
    private static String world(String name) {
        if (name == null || Bukkit.getWorld(name) != null) return name;
        String lower = name.toLowerCase(Locale.ROOT);
        return Bukkit.getWorld(lower) != null ? lower : name;
    }

    private static Map<String, String> names(Connection c, String players) {
        Map<String, String> out = new HashMap<>();
        if (players == null) return out;
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, cachedName FROM " + players)) {
            while (rs.next()) out.put(rs.getString(1), rs.getString(2));
        } catch (SQLException ignored) {
            // names read from the server instead
        }
        return out;
    }

    // ---------------------------------------------------------------- database of the former plugin

    private Connection connect(File folder, ConfigurationSection db) throws Exception {
        if (db.getBoolean("mysql", false)) {
            String url = "jdbc:mysql://" + db.getString("host", "127.0.0.1") + ":" + db.getInt("port", 3306) + "/"
                    + db.getString("database", "") + "?useSSL=" + db.getBoolean("usessl", false) + "&characterEncoding=utf8";
            return DriverManager.getConnection(url, db.getString("user", ""), db.getString("password", ""));
        }
        File file = new File(folder, "shops.mv.db");
        if (!file.isFile()) throw new IllegalStateException("no H2 database " + file);
        Path jar = h2Jar(folder);
        if (jar == null) throw new IllegalStateException("H2 driver not found under " + folder.getName() + "/lib");
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
        Driver driver = (Driver) Class.forName("org.h2.Driver", true, loader).getDeclaredConstructor().newInstance();
        String path = new File(folder, "shops").getAbsolutePath();
        Connection c = driver.connect("jdbc:h2:file:" + path + ";MODE=MYSQL", new Properties());
        if (c == null) throw new IllegalStateException("H2 refused " + path);
        return c;
    }

    private static Path h2Jar(File folder) throws java.io.IOException {
        Path lib = new File(folder, "lib/com/h2database/h2").toPath();
        if (!Files.isDirectory(lib)) return null;
        try (Stream<Path> files = Files.walk(lib)) {
            return files.filter(p -> p.getFileName().toString().matches("h2-.*\\.jar")).findFirst().orElse(null);
        }
    }

    /** Tables of the database, lower case name -> real name. */
    private static Map<String, String> tables(Connection c) throws SQLException {
        Map<String, String> out = new HashMap<>();
        DatabaseMetaData meta = c.getMetaData();
        try (ResultSet rs = meta.getTables(c.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rs.next()) out.put(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT), rs.getString("TABLE_NAME"));
        }
        return out;
    }

    /** {@code <prefix><name>}, else {@code <name>} (H2 of QuickShop-Hikari has no prefix). */
    private static String table(Map<String, String> tables, String prefix, String name) {
        String t = tables.get((prefix + name).toLowerCase(Locale.ROOT));
        return t != null ? t : tables.get(name);
    }

    private static JsonObject json(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonElement e = JsonParser.parseString(raw);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
