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
 * The one database of the suite, configured once in Flux ({@code database} section of {@code plugins/Flux/config.yml}):
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
    private static Logger log = Logger.getLogger("Flux");

    private Database() {
    }

    /** Called by Flux. */
    public static void start(Plugin flux, ConfigurationSection config) {
        log = flux.getLogger();
        String type = config == null ? "sqlite" : config.getString("type", "sqlite").toLowerCase(Locale.ROOT);
        prefix = config == null ? "" : config.getString("table-prefix", "");
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("Flux");
        if (type.equals("mysql") || type.equals("mariadb")) {
            dialect = Dialect.MYSQL;
            hc.setJdbcUrl("jdbc:mysql://" + config.getString("host", "localhost") + ":" + config.getInt("port", 3306) + "/"
                    + config.getString("name", "influencraft") + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true");
            hc.setUsername(config.getString("user", "root"));
            hc.setPassword(config.getString("password", ""));
            hc.setMaximumPoolSize(Math.max(2, config.getInt("pool-size", 6)));
        } else {
            dialect = Dialect.SQLITE;
            File file = new File(flux.getDataFolder(), config == null ? "flux.db" : config.getString("file", "flux.db"));
            file.getParentFile().mkdirs();
            // foreign keys on (referral tables), and wait instead of failing when the file is busy
            hc.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath() + "?foreign_keys=on&busy_timeout=10000");
            hc.setDriverClassName("org.sqlite.JDBC");
            // one writer at a time in SQLite: a single connection, shared in turn
            hc.setMaximumPoolSize(1);
        }
        hc.setConnectionTimeout(15000);
        try {
            source = new HikariDataSource(hc);
        } catch (RuntimeException e) {
            // no silent fallback to another engine: the data would be split between two databases
            log.severe("Database of the suite unavailable (" + dialect + "): " + e.getMessage()
                    + ". Plugins storing data will not work until it is fixed (database section of config.yml).");
            return;
        }
        if (dialect == Dialect.SQLITE) {
            try (Connection c = source.getConnection(); Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
            } catch (SQLException e) {
                log.warning("SQLite WAL mode not set: " + e.getMessage());
            }
        }
        log.info("Database of the suite: " + dialect.name().toLowerCase(Locale.ROOT));
    }

    /** Called by Flux. */
    public static void stop() {
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

    /** Table name with the configured prefix ({@code database.table-prefix}). */
    public static String table(String name) {
        return prefix + name;
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

    private static boolean exists(Connection c, String table) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    private static boolean empty(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 1 FROM " + table + " LIMIT 1")) {
            return !rs.next();
        }
    }
}
