package fr.influencraft.flux.api.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * The one database of the suite, configured once in Flux ({@code plugins/Flux/storage.yml}):
 * a SQLite file ({@code plugins/Flux/flux.db}, default) or a MySQL/MariaDB server. Every plugin of the suite keeps its
 * tables there instead of opening its own file or connection.
 *
 * <pre>
 *   try (Connection c = Database.connection();
 *        PreparedStatement ps = c.prepareStatement("SELECT chips FROM " + Database.table("casino_balances") + " WHERE uuid = ?")) {
 *       ...
 *   }
 * </pre>
 *
 * <p>Write SQL both engines understand; {@link #dialect()} gives the few differing pieces (auto increment, insert
 * ignore, upsert, indexed text). Connections are pooled: take one per operation and close it right away (SQLite has
 * one connection only, never ask for a second one while holding the first).</p>
 */
public final class Database {

    public enum Dialect {
        SQLITE, MYSQL;

        /** Auto-increment primary key column type. */
        public String autoId() {
            return this == SQLITE ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT PRIMARY KEY AUTO_INCREMENT";
        }

        /** Text column usable in a key or an index (MySQL needs a length). */
        public String key(int length) {
            return this == SQLITE ? "TEXT" : "VARCHAR(" + length + ")";
        }

        /** {@code INSERT OR IGNORE INTO} / {@code INSERT IGNORE INTO}. */
        public String insertIgnore() {
            return this == SQLITE ? "INSERT OR IGNORE INTO" : "INSERT IGNORE INTO";
        }

        /** Insert or update on the given unique columns. */
        public String upsert(String table, List<String> columns, List<String> keys, List<String> updated) {
            String marks = String.join(", ", columns.stream().map(c -> "?").toList());
            String head = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES (" + marks + ")";
            if (this == SQLITE) {
                return head + " ON CONFLICT(" + String.join(", ", keys) + ") DO UPDATE SET "
                        + String.join(", ", updated.stream().map(c -> c + " = excluded." + c).toList());
            }
            return head + " ON DUPLICATE KEY UPDATE " + String.join(", ", updated.stream().map(c -> c + " = VALUES(" + c + ")").toList());
        }

        /** {@code CREATE INDEX IF NOT EXISTS} (MySQL has no IF NOT EXISTS: errors on an existing index are ignored by {@link #index}). */
        String createIndex(String name, String table, String columns, boolean unique) {
            return "CREATE " + (unique ? "UNIQUE " : "") + "INDEX " + (this == SQLITE ? "IF NOT EXISTS " : "") + name + " ON " + table + " (" + columns + ")";
        }
    }

    private static volatile HikariDataSource source;
    private static volatile Dialect dialect = Dialect.SQLITE;
    private static volatile String prefix = "";
    private static volatile ConfigurationSection settings;
    /** Every table name handed out by {@link #table}: the tables of the suite, recorded in {@code flux_tables}. */
    private static final java.util.Set<String> declared = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static Logger log = Logger.getLogger("Flux");

    private Database() {
    }

    /** Called by Flux. */
    public static void start(Plugin flux, ConfigurationSection config) {
        log = flux.getLogger();
        settings = config;
        dialect = engine(config == null ? "sqlite" : config.getString("type", "sqlite"));
        prefix = config == null ? "" : config.getString("table-prefix", "");
        try {
            source = open(flux, dialect, config);
        } catch (RuntimeException e) {
            // no silent fallback to another engine: the data would be split between two databases
            log.severe("Database of the suite unavailable (" + dialect + "): " + e.getMessage()
                    + ". Plugins storing data will not work until it is fixed (plugins/Flux/storage.yml).");
            return;
        }
        // a change of engine (/fluxmenu database migrate, or type changed by hand): rows copied before any plugin opens its tables
        if (!DatabaseMigration.runPending(flux, config, source, dialect, prefix)) {
            log.severe("The database of the suite stays closed until the migration above succeeds"
                    + " (fix the cause and restart, or see /fluxmenu database status).");
            // closed as is: stop() would record the tables of the suite in the target the copy just cleaned
            HikariDataSource failed = source;
            source = null;
            failed.close();
            return;
        }
        // the registry of the suite's tables was flux_tables before 2026-10-31 (after the copy: it may have brought it)
        try (Connection c = source.getConnection()) {
            renameTable(c, prefix + DatabaseMigration.LEGACY_REGISTRY, prefix + DatabaseMigration.REGISTRY);
        } catch (SQLException e) {
            log.warning("Registry of the tables not renamed: " + e.getMessage());
        }
        log.info("Database of the suite: " + where(flux, dialect, config));
    }

    /** Where the settings live: {@code plugins/Flux/storage.yml}. */
    public static File settingsFile(Plugin flux) {
        return new File(flux.getDataFolder(), "storage.yml");
    }

    /** Reads storage.yml (written from the jar's copy when missing), the jar's values as defaults for missing keys. */
    public static ConfigurationSection loadSettings(Plugin flux) {
        File file = settingsFile(flux);
        if (!file.exists()) flux.saveResource("storage.yml", false);
        org.bukkit.configuration.file.YamlConfiguration settings = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        java.io.InputStream shipped = flux.getResource("storage.yml");
        if (shipped != null) {
            settings.setDefaults(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(shipped, java.nio.charset.StandardCharsets.UTF_8)));
        }
        return settings;
    }

    /** {@code block-joins-on-error} of storage.yml: players refused while the database is closed. */
    public static boolean blockJoinsOnError() {
        ConfigurationSection s = settings;
        return s == null || s.getBoolean("block-joins-on-error", true);
    }

    /** {@code sqlite} (default), {@code mysql} or {@code mariadb}. */
    static Dialect engine(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return t.equals("mysql") || t.equals("mariadb") ? Dialect.MYSQL : Dialect.SQLITE;
    }

    /** A pool on the given engine, with the settings of storage.yml (also used by migrations). */
    static HikariDataSource open(Plugin flux, Dialect engine, ConfigurationSection config) {
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("Flux-" + engine.name().toLowerCase(Locale.ROOT));
        if (engine == Dialect.MYSQL) {
            hc.setJdbcUrl("jdbc:mysql://" + config.getString("host", "localhost") + ":" + config.getInt("port", 3306) + "/"
                    + config.getString("name", "influencraft") + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true");
            hc.setUsername(config.getString("user", "root"));
            hc.setPassword(config.getString("password", ""));
            hc.setMaximumPoolSize(Math.max(2, config.getInt("pool-size", 6)));
        } else {
            File file = sqliteFile(flux, config);
            file.getParentFile().mkdirs();
            // foreign keys on (referral tables), and wait instead of failing when the file is busy
            hc.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath() + "?foreign_keys=on&busy_timeout=10000");
            hc.setDriverClassName("org.sqlite.JDBC");
            // one writer at a time in SQLite: a single connection, shared in turn
            hc.setMaximumPoolSize(1);
        }
        hc.setConnectionTimeout(15000);
        HikariDataSource pool = new HikariDataSource(hc);
        if (engine == Dialect.SQLITE) {
            try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
            } catch (SQLException e) {
                log.warning("SQLite WAL mode not set: " + e.getMessage());
            }
        }
        return pool;
    }

    static File sqliteFile(Plugin flux, ConfigurationSection config) {
        return new File(flux.getDataFolder(), config == null ? "flux.db" : config.getString("file", "flux.db"));
    }

    /** Where the data lives, for logs and {@code /fluxmenu database status}. */
    static String where(Plugin flux, Dialect engine, ConfigurationSection config) {
        return engine == Dialect.MYSQL
                ? "mysql " + config.getString("user", "root") + "@" + config.getString("host", "localhost") + ":"
                + config.getInt("port", 3306) + "/" + config.getString("name", "influencraft")
                : "sqlite " + sqliteFile(flux, config).getPath();
    }

    /** Called by Flux. */
    public static void stop() {
        rememberTables();
        HikariDataSource s = source;
        source = null;
        if (s != null) s.close();
    }

    public static boolean available() {
        return source != null;
    }

    public static DataSource source() {
        HikariDataSource s = source;
        if (s == null) throw new IllegalStateException("The database of Flux is not started");
        return s;
    }

    /** A pooled connection: close it as soon as the operation is done (try-with-resources). */
    public static Connection connection() throws SQLException {
        HikariDataSource s = source;
        if (s == null) throw new SQLException("The database of Flux is not available (see the start-up log)");
        return s.getConnection();
    }

    public static Dialect dialect() {
        return dialect;
    }

    /**
     * Table name with the configured prefix ({@code table-prefix} of storage.yml). Name tables
     * {@code flux_<module>_<category>} ({@code flux_family_couples}, {@code flux_pw_warps}); the name is recorded as a
     * table of the suite, so that {@code /fluxmenu database migrate} moves it with the others.
     */
    public static String table(String name) {
        String full = prefix + name;
        declared.add(full);
        return full;
    }

    static String prefix() {
        return prefix;
    }

    static java.util.Set<String> declaredTables() {
        return java.util.Set.copyOf(declared);
    }

    /** Records tables of the suite named without {@link #table} (their plugin writes the name itself). */
    public static void declare(String... names) {
        for (String n : names) declared.add(n);
    }

    /**
     * Writes the tables of the suite in {@code flux_tables} (Flux calls it once every plugin is enabled, and on stop):
     * the list a migration from MySQL copies, since a MySQL database may hold tables of other applications. On SQLite
     * every table of the file belongs to the suite and is recorded too.
     */
    public static void rememberTables() {
        if (source == null) return;
        try (Connection c = connection()) {
            java.util.Set<String> names = new java.util.TreeSet<>();
            for (String t : declared) if (tableExists(c, t)) names.add(t);
            if (dialect == Dialect.SQLITE) names.addAll(DatabaseMigration.sqliteTables(c));
            DatabaseMigration.record(c, dialect, prefix, names);
            DatabaseMigration.forgetMissing(c, prefix);
        } catch (SQLException e) {
            log.warning("Tables of the suite not recorded: " + e.getMessage());
        }
    }

    /**
     * Gives a table its new name once ({@code fluxpw_warps} → {@code flux_pw_warps}): renamed when the former name
     * exists and the new one does not yet. Call it before {@code CREATE TABLE IF NOT EXISTS}. Names with the prefix.
     */
    public static boolean renameTable(Connection c, String from, String to) throws SQLException {
        return renameTable(c, from, to, new String[0]);
    }

    /**
     * Same, and drops the table's former indexes (names without the prefix, as in {@link #index}) so that the caller
     * makes them again under their new names: SQLite index names are global and keep their former name otherwise.
     */
    public static boolean renameTable(Connection c, String from, String to, String... formerIndexes) throws SQLException {
        if (!tableExists(c, from) || tableExists(c, to)) return false;
        try (Statement st = c.createStatement()) {
            st.executeUpdate(dialect == Dialect.SQLITE ? "ALTER TABLE " + from + " RENAME TO " + to : "RENAME TABLE " + from + " TO " + to);
        }
        for (String index : formerIndexes) dropIndex(c, index, to);
        log.info("Table " + from + " renamed " + to + ".");
        return true;
    }

    /** Drops an index when it exists (an index renamed with its table). Name without the prefix, as in {@link #index}. */
    public static void dropIndex(Connection c, String name, String table) {
        try (Statement st = c.createStatement()) {
            st.execute(dialect == Dialect.SQLITE ? "DROP INDEX IF EXISTS " + prefix + name : "DROP INDEX " + prefix + name + " ON " + table);
        } catch (SQLException ignored) {
            // MySQL: no such index
        }
    }

    /** True when the table exists (name with the prefix). */
    public static boolean tableExists(Connection c, String table) throws SQLException {
        return exists(c, table);
    }

    /** Cancels a planned migration ({@code type} of storage.yml set back). Returns null when done, else why not. */
    public static String cancelMigration(Plugin flux) {
        return DatabaseMigration.cancel(flux);
    }

    /** Status lines for {@code /fluxmenu database status}. */
    public static List<String> status(Plugin flux) {
        List<String> out = new ArrayList<>();
        out.add((source == null ? "CLOSED " : "open ") + where(flux, dialect, settings));
        if (source != null) {
            try (Connection c = connection()) {
                for (String t : DatabaseMigration.suiteTables(c, dialect, prefix)) out.add(t + ": " + DatabaseMigration.count(c, t) + " row(s)");
            } catch (SQLException e) {
                out.add("tables not read: " + e.getMessage());
            }
        }
        String pending = DatabaseMigration.pending(flux);
        if (pending != null) out.add("migration planned at next start: " + pending);
        return out;
    }

    /**
     * Plans the move of the whole database to the other engine ({@code sqlite} or {@code mysql}): checks the target,
     * writes {@code database-migration.yml} and switches {@code type} in storage.yml. The copy runs at the
     * next start, before any plugin opens its tables. Returns null when planned, else why not (English, for staff).
     */
    public static String planMigration(Plugin flux, String target, boolean replace, String by) {
        // storage.yml read again: the target's credentials are usually written while the server runs
        return DatabaseMigration.plan(flux, loadSettings(flux), dialect, engine(target), replace, by);
    }

    /** Creates an index when missing (both engines). */
    public static void index(Connection c, String name, String table, String columns, boolean unique) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(dialect.createIndex(prefix + name, table, columns, unique));
        } catch (SQLException e) {
            if (dialect == Dialect.SQLITE) throw e; // MySQL: the index already exists
        }
    }

    /**
     * Moves the rows of a plugin's former SQLite file into the shared database, once: for each table (old name → new
     * name, pairs), rows are copied when the new table is empty, then the file is renamed {@code *.moved-to-flux}.
     * The new tables must already exist (created by the caller).
     */
    public static void importSqlite(Plugin owner, File oldFile, String... tablePairs) {
        if (!oldFile.isFile() || source == null) return;
        int rows = 0;
        try (Connection old = java.sql.DriverManager.getConnection("jdbc:sqlite:" + oldFile.getAbsolutePath());
             Connection now = connection()) {
            for (int i = 0; i + 1 < tablePairs.length; i += 2) {
                String from = tablePairs[i], to = tablePairs[i + 1];
                if (!exists(old, from) || !empty(now, to)) continue;
                try (Statement st = old.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + from)) {
                    ResultSetMetaData md = rs.getMetaData();
                    List<String> cols = new ArrayList<>();
                    for (int k = 1; k <= md.getColumnCount(); k++) cols.add(md.getColumnName(k));
                    String sql = "INSERT INTO " + to + " (" + String.join(", ", cols) + ") VALUES ("
                            + String.join(", ", cols.stream().map(c -> "?").toList()) + ")";
                    boolean auto = now.getAutoCommit();
                    now.setAutoCommit(false);
                    try (PreparedStatement ps = now.prepareStatement(sql)) {
                        while (rs.next()) {
                            for (int k = 1; k <= cols.size(); k++) ps.setObject(k, rs.getObject(k));
                            ps.addBatch();
                            rows++;
                        }
                        ps.executeBatch();
                        now.commit();
                    } finally {
                        now.setAutoCommit(auto);
                    }
                }
            }
        } catch (SQLException e) {
            owner.getLogger().warning("Former database " + oldFile.getName() + " not moved into Flux: " + e.getMessage());
            return;
        }
        File done = new File(oldFile.getPath() + ".moved-to-flux");
        if (oldFile.renameTo(done)) {
            new File(oldFile.getPath() + "-wal").delete();
            new File(oldFile.getPath() + "-shm").delete();
        }
        owner.getLogger().info(rows + " row(s) of " + oldFile.getName() + " moved into the database of Flux.");
    }

    /** A read-only connection to another plugin's SQLite file (migrations), through the driver Flux ships. */
    public static Connection readSqlite(File file) throws SQLException {
        // read-only through the driver's own option: "?mode=ro" in the URL would become part of the file name
        java.util.Properties options = new java.util.Properties();
        options.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY
        return java.sql.DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath(), options);
    }

    private static boolean exists(Connection c, String table) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        // the current database only: on a MySQL server, a null catalog searches every database of the server
        String escape = md.getSearchStringEscape();
        String pattern = escape == null ? table : table.replace("_", escape + "_").replace("%", escape + "%");
        try (ResultSet rs = md.getTables(c.getCatalog(), null, pattern, new String[]{"TABLE"})) {
            while (rs.next()) if (table.equals(rs.getString("TABLE_NAME"))) return true;
            return false;
        }
    }

    private static boolean empty(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 1 FROM " + table + " LIMIT 1")) {
            return !rs.next();
        }
    }
}
