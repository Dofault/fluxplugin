package fr.influencraft.flux.api.data;

import com.zaxxer.hikari.HikariDataSource;
import fr.influencraft.flux.api.data.Database.Dialect;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * Moves the database of the suite from one engine to the other (SQLite file ⇄ MySQL/MariaDB server).
 *
 * <p>{@code /fluxmenu database migrate <engine>} checks the target, switches {@code type} in storage.yml and
 * writes {@code database-migration.yml}; the copy itself runs at the next start, inside {@link Database#start}, before
 * any plugin of the suite opens its tables and before players can join: nothing is written meanwhile, nothing is lost.
 * Changing {@code type} from sqlite to mysql by hand does the same when the MySQL database holds nothing of the suite
 * yet. The other way (MySQL to a local file) only on request: a test server must never pull the rows of a shared
 * server by itself.</p>
 *
 * <p>Tables are created on the target from the source's own description (columns, primary key, indexes), so every
 * table of the suite moves, whichever plugin made it. The source is never deleted: the SQLite file is renamed
 * {@code flux.db.migrated-<date>}, MySQL tables stay where they are.</p>
 */
final class DatabaseMigration {

    /** Tables of the suite, recorded by {@link Database#rememberTables}: what a migration from MySQL copies. */
    static final String REGISTRY = "flux_core_tables";
    /** Name of the registry before 2026-10-31 (a MySQL source not yet renamed). */
    static final String LEGACY_REGISTRY = "flux_tables";
    static final String FILE = "database-migration.yml";
    private static final int BATCH = 500;
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DatabaseMigration() {
    }

    // ---------------------------------------------------------------- planning (command)

    static String plan(Plugin flux, ConfigurationSection config, Dialect current, Dialect target, boolean replace, String by) {
        if (config == null) return "storage.yml not read";
        if (target == current) return "the suite already runs on " + name(target);
        if (!Database.available()) return "the current database is closed: fix it first (a migration copies from it)";
        List<String> tables;
        try (Connection c = Database.connection()) {
            tables = suiteTables(c, current, Database.prefix());
        } catch (SQLException e) {
            return "current database not read: " + e.getMessage();
        }
        try (HikariDataSource pool = Database.open(flux, target, config); Connection c = pool.getConnection()) {
            List<String> full = new ArrayList<>();
            for (String t : tables) if (Database.tableExists(c, t) && count(c, t) > 0) full.add(t);
            if (!full.isEmpty() && !replace) {
                return "the target already holds rows in " + String.join(", ", full) + ": add 'replace' to overwrite them";
            }
        } catch (RuntimeException | SQLException e) {
            return "target " + Database.where(flux, target, config) + " unreachable: " + e.getMessage();
        }
        try {
            if (!switchType(Database.settingsFile(flux), name(target))) {
                return "type not found in storage.yml: set it to " + name(target) + " by hand, then plan again";
            }
            YamlConfiguration plan = new YamlConfiguration();
            plan.options().setHeader(List.of("Planned by /fluxmenu database migrate: the copy runs at the next start.",
                    "Delete this file (and set type back in storage.yml) to cancel, or use /fluxmenu database cancel."));
            plan.set("from", name(current));
            plan.set("to", name(target));
            plan.set("replace", replace);
            plan.set("requested", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            plan.set("by", by);
            plan.save(new File(flux.getDataFolder(), FILE));
        } catch (IOException e) {
            return "plan not written: " + e.getMessage();
        }
        return null;
    }

    /** Cancels a planned migration: type set back, plan deleted. Null when done, else why not. */
    static String cancel(Plugin flux) {
        File file = new File(flux.getDataFolder(), FILE);
        if (!file.isFile()) return "no migration planned";
        String from = YamlConfiguration.loadConfiguration(file).getString("from", "sqlite");
        try {
            switchType(Database.settingsFile(flux), from);
        } catch (IOException e) {
            return "storage.yml not changed: " + e.getMessage();
        }
        return file.delete() ? null : "could not delete " + file.getName();
    }

    /** "sqlite → mysql (replace)" when a migration waits for the next start. */
    static String pending(Plugin flux) {
        File file = new File(flux.getDataFolder(), FILE);
        if (!file.isFile()) return null;
        YamlConfiguration plan = YamlConfiguration.loadConfiguration(file);
        return plan.getString("from") + " -> " + plan.getString("to") + (plan.getBoolean("replace") ? " (replace)" : "")
                + ", asked by " + plan.getString("by") + " on " + plan.getString("requested");
    }

    /** Sets the top-level {@code type:} of storage.yml, keeping every other line (and comment) as it is. */
    private static boolean switchType(File storageFile, String type) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(storageFile.toPath(), StandardCharsets.UTF_8));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches("^type:.*")) {
                lines.set(i, "type: " + type);
                Files.write(storageFile.toPath(), lines, StandardCharsets.UTF_8);
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- copy (start-up)

    /**
     * Runs a planned migration into {@code target}, or the implicit one (type changed by hand from sqlite to mysql).
     * False when a copy was due and failed: the database must then stay closed.
     */
    static boolean runPending(Plugin flux, ConfigurationSection config, HikariDataSource target, Dialect to, String prefix) {
        Logger log = flux.getLogger();
        File planFile = new File(flux.getDataFolder(), FILE);
        Dialect from;
        boolean replace = false;
        if (planFile.isFile()) {
            YamlConfiguration plan = YamlConfiguration.loadConfiguration(planFile);
            from = Database.engine(plan.getString("from"));
            if (Database.engine(plan.getString("to")) != to || from == to) {
                log.warning("Planned migration " + plan.getString("from") + " -> " + plan.getString("to")
                        + " ignored: type is " + name(to) + " in storage.yml" + ". Plan set aside (" + FILE + ".cancelled).");
                planFile.renameTo(new File(planFile.getPath() + ".cancelled"));
                return true;
            }
            replace = plan.getBoolean("replace");
        } else {
            if (to != Dialect.MYSQL || config == null) return true;
            File file = Database.sqliteFile(flux, config);
            if (!file.isFile()) return true;
            try (Connection c = target.getConnection()) {
                if (Database.tableExists(c, prefix + REGISTRY) || Database.tableExists(c, prefix + LEGACY_REGISTRY)) {
                    return true; // the suite already lives here
                }
            } catch (SQLException e) {
                return true; // Database.start reports a dead target on its own
            }
            from = Dialect.SQLITE;
            log.warning("type is now mysql in storage.yml and " + file.getName() + " still holds the data of the suite: copying it.");
        }

        long started = System.currentTimeMillis();
        List<String> created = new ArrayList<>();
        List<String> filled = new ArrayList<>();
        try (HikariDataSource source = Database.open(flux, from, config)) {
            int rows = 0, tables = 0;
            try (Connection in = source.getConnection(); Connection out = target.getConnection()) {
                List<String> names = suiteTables(in, from, prefix);
                // the registry is written again on the target (record below): the former one (flux_tables) never moves
                names.remove(prefix + LEGACY_REGISTRY);
                if (from == Dialect.MYSQL && names.isEmpty()) {
                    throw new SQLException("no table of the suite recorded in " + prefix + REGISTRY + " of the MySQL database");
                }
                List<String> full = new ArrayList<>();
                for (String t : names) if (Database.tableExists(out, t) && count(out, t) > 0) full.add(t);
                if (!full.isEmpty() && !replace) {
                    throw new SQLException("the target already holds rows in " + String.join(", ", full)
                            + " (plan again with 'replace' to overwrite them)");
                }
                // foreign keys (referral) checked once everything is there: tables are copied in name order
                foreignKeys(out, to, false);
                try {
                    for (String t : names) {
                        boolean existed = Database.tableExists(out, t);
                        if (existed) {
                            try (Statement st = out.createStatement()) {
                                st.executeUpdate("DELETE FROM " + q(to, t));
                            }
                        }
                        int n = copyTable(in, out, from, to, t, existed);
                        (existed ? filled : created).add(t);
                        rows += n;
                        tables++;
                        log.info("  " + t + ": " + n + " row(s)");
                    }
                } finally {
                    foreignKeys(out, to, true);
                }
                // every copied table is a table of the suite on the target too (some are named without Database.table)
                record(out, to, prefix, names);
            }
            log.info("Migration " + name(from) + " -> " + name(to) + " done: " + tables + " table(s), " + rows + " row(s) in "
                    + (System.currentTimeMillis() - started) + " ms.");
        } catch (RuntimeException | SQLException e) {
            log.severe("Migration " + name(from) + " -> " + name(to) + " failed: " + e.getMessage());
            undo(target, created, filled, log);
            return false;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        if (from == Dialect.SQLITE) {
            // the source pool is closed (WAL checkpointed): the file is kept aside, never deleted
            File file = Database.sqliteFile(flux, config);
            File kept = new File(file.getPath() + ".migrated-" + stamp);
            if (file.renameTo(kept)) {
                new File(file.getPath() + "-wal").delete();
                new File(file.getPath() + "-shm").delete();
                log.info("Former SQLite file kept as " + kept.getName() + ".");
            }
        } else {
            log.info("The tables stay in the MySQL database (copy kept as a backup): drop them by hand once checked.");
        }
        if (planFile.isFile()) planFile.renameTo(new File(flux.getDataFolder(), "database-migration.done-" + stamp + ".yml"));
        return true;
    }

    /** A failed copy leaves the target as it was: tables made by the copy dropped, tables it filled emptied. */
    private static void undo(HikariDataSource target, List<String> created, List<String> filled, Logger log) {
        try (Connection c = target.getConnection(); Statement st = c.createStatement()) {
            Dialect engine = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("sqlite") ? Dialect.SQLITE : Dialect.MYSQL;
            foreignKeys(c, engine, false);
            try {
                for (String t : created) st.executeUpdate("DROP TABLE IF EXISTS " + t);
                for (String t : filled) st.executeUpdate("DELETE FROM " + t);
            } finally {
                foreignKeys(c, engine, true);
            }
        } catch (SQLException e) {
            log.warning("Target not cleaned after the failed migration: " + e.getMessage());
        }
    }

    /** Foreign key checks of this connection on or off (pooled connection: always switched back on). */
    private static void foreignKeys(Connection c, Dialect engine, boolean on) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(engine == Dialect.MYSQL ? "SET FOREIGN_KEY_CHECKS = " + (on ? 1 : 0) : "PRAGMA foreign_keys = " + (on ? "ON" : "OFF"));
        }
    }

    /** Copies one table: created on the target unless it exists (already emptied), then every row. Returns the row count. */
    private static int copyTable(Connection in, Connection out, Dialect from, Dialect to, String table, boolean exists) throws SQLException {
        Shape shape = Shape.read(in, from, table);
        if (!exists) {
            try (Statement st = out.createStatement()) {
                st.executeUpdate(shape.create(to, table));
                for (String index : shape.indexes(to, table)) st.executeUpdate(index);
            }
        }
        List<String> cols = new ArrayList<>(shape.columns.keySet());
        String insert = "INSERT INTO " + q(to, table) + " (" + String.join(", ", cols.stream().map(c -> q(to, c)).toList())
                + ") VALUES (" + String.join(", ", cols.stream().map(c -> "?").toList()) + ")";
        String select = "SELECT " + String.join(", ", cols.stream().map(c -> q(from, c)).toList()) + " FROM " + q(from, table);
        int rows = 0;
        boolean auto = out.getAutoCommit();
        out.setAutoCommit(false);
        try (Statement st = in.createStatement(); ResultSet rs = st.executeQuery(select);
             PreparedStatement ps = out.prepareStatement(insert)) {
            while (rs.next()) {
                for (int i = 1; i <= cols.size(); i++) ps.setObject(i, value(rs.getObject(i), to));
                ps.addBatch();
                if (++rows % BATCH == 0) ps.executeBatch();
            }
            ps.executeBatch();
            out.commit();
        } catch (SQLException e) {
            out.rollback();
            throw new SQLException(table + ": " + e.getMessage(), e);
        } finally {
            out.setAutoCommit(auto);
        }
        return rows;
    }

    /** A value read on one engine, as the other one stores it (dates as SQLite's text, booleans as 0/1). */
    private static Object value(Object v, Dialect to) {
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v instanceof BigInteger b) return b.longValue();
        if (v instanceof BigDecimal b) return b.scale() <= 0 ? (Object) b.longValue() : (Object) b.doubleValue();
        if (to == Dialect.SQLITE) {
            if (v instanceof Timestamp t) return t.toLocalDateTime().format(SQL_TIME);
            if (v instanceof LocalDateTime t) return t.format(SQL_TIME);
            if (v instanceof java.sql.Date || v instanceof java.sql.Time || v instanceof java.time.temporal.TemporalAccessor) return v.toString();
        } else if (v instanceof LocalDateTime t) {
            return Timestamp.valueOf(t);
        }
        return v;
    }

    // ---------------------------------------------------------------- tables of the suite

    /** Tables of the suite on this engine: every table of a SQLite file; on MySQL, those recorded in {@link #REGISTRY}. */
    static List<String> suiteTables(Connection c, Dialect engine, String prefix) throws SQLException {
        Set<String> out = new TreeSet<>();
        if (engine == Dialect.SQLITE) {
            out.addAll(sqliteTables(c));
        } else {
            String registry = Database.tableExists(c, prefix + REGISTRY) ? prefix + REGISTRY
                    : Database.tableExists(c, prefix + LEGACY_REGISTRY) ? prefix + LEGACY_REGISTRY : null;
            if (registry != null) {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT name FROM " + registry)) {
                    while (rs.next()) out.add(rs.getString(1));
                }
                out.add(registry);
            }
        }
        for (String t : Database.declaredTables()) out.add(t);
        List<String> existing = new ArrayList<>();
        for (String t : out) if (Database.tableExists(c, t)) existing.add(t);
        return existing;
    }

    /** Records tables in {@link #REGISTRY} (created when missing). */
    static void record(Connection c, Dialect engine, String prefix, java.util.Collection<String> names) throws SQLException {
        String registry = prefix + REGISTRY;
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + registry + " (name " + engine.key(191) + " PRIMARY KEY, seen BIGINT NOT NULL)");
        }
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(true);
        try (PreparedStatement ps = c.prepareStatement(engine.upsert(registry, List.of("name", "seen"), List.of("name"), List.of("seen")))) {
            long now = System.currentTimeMillis();
            for (String n : names) {
                if (n.equals(registry)) continue;
                ps.setString(1, n);
                ps.setLong(2, now);
                ps.addBatch();
            }
            ps.executeBatch();
        } finally {
            c.setAutoCommit(auto);
        }
    }

    /** Drops from the registry the tables that no longer exist (renamed or dropped). */
    static void forgetMissing(Connection c, String prefix) throws SQLException {
        String registry = prefix + REGISTRY;
        List<String> gone = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT name FROM " + registry)) {
            while (rs.next()) gone.add(rs.getString(1));
        }
        gone.removeIf(t -> {
            try {
                return Database.tableExists(c, t);
            } catch (SQLException e) {
                return true;
            }
        });
        if (gone.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + registry + " WHERE name = ?")) {
            for (String t : gone) {
                ps.setString(1, t);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static List<String> sqliteTables(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY name")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    static long count(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static String name(Dialect d) {
        return d == Dialect.MYSQL ? "mysql" : "sqlite";
    }

    private static String q(Dialect d, String identifier) {
        return d == Dialect.MYSQL ? "`" + identifier + "`" : "\"" + identifier + "\"";
    }

    // ---------------------------------------------------------------- table shape

    /** What a table looks like (read from the source's metadata), written again for the other engine. */
    private static final class Shape {
        record Column(String type, int size, boolean nullable, String def, boolean auto) {
        }

        record Index(String name, boolean unique, List<String> columns) {
        }

        record ForeignKey(String table, List<String> columns, List<String> referenced, short onDelete, short onUpdate) {
        }

        final Map<String, Column> columns = new LinkedHashMap<>();
        final List<String> primaryKey = new ArrayList<>();
        final List<Index> indexes = new ArrayList<>();
        final List<ForeignKey> foreignKeys = new ArrayList<>();

        static Shape read(Connection c, Dialect engine, String table) throws SQLException {
            Shape s = new Shape();
            DatabaseMetaData md = c.getMetaData();
            String catalog = c.getCatalog();
            String escape = md.getSearchStringEscape();
            String pattern = escape == null ? table : table.replace("_", escape + "_").replace("%", escape + "%");
            try (ResultSet rs = md.getColumns(catalog, null, pattern, null)) {
                while (rs.next()) {
                    if (!table.equals(rs.getString("TABLE_NAME"))) continue;
                    String auto = rs.getString("IS_AUTOINCREMENT");
                    s.columns.put(rs.getString("COLUMN_NAME"), new Column(rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"),
                            rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, rs.getString("COLUMN_DEF"), "YES".equalsIgnoreCase(auto)));
                }
            }
            if (s.columns.isEmpty()) throw new SQLException("no column read for " + table);
            Map<Integer, String> pk = new java.util.TreeMap<>();
            try (ResultSet rs = md.getPrimaryKeys(catalog, null, table)) {
                while (rs.next()) pk.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
            s.primaryKey.addAll(pk.values());
            Map<String, Index> byName = new LinkedHashMap<>();
            Map<String, Map<Integer, String>> columnsOf = new LinkedHashMap<>();
            try (ResultSet rs = md.getIndexInfo(catalog, null, table, false, false)) {
                while (rs.next()) {
                    String name = rs.getString("INDEX_NAME"), column = rs.getString("COLUMN_NAME");
                    if (name == null || column == null || rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                    byName.putIfAbsent(name, new Index(name, !rs.getBoolean("NON_UNIQUE"), new ArrayList<>()));
                    columnsOf.computeIfAbsent(name, k -> new java.util.TreeMap<>()).put((int) rs.getShort("ORDINAL_POSITION"), column);
                }
            }
            for (Index idx : byName.values()) {
                idx.columns().addAll(columnsOf.get(idx.name()).values());
                // the primary key is written with the table
                if (idx.name().equals("PRIMARY") || idx.columns().equals(s.primaryKey)) continue;
                s.indexes.add(idx);
            }
            try (ResultSet rs = md.getImportedKeys(catalog, null, table)) {
                ForeignKey current = null;
                while (rs.next()) {
                    String parent = rs.getString("PKTABLE_NAME");
                    // a new key starts at its first column (or when the referenced table changes)
                    if (current == null || rs.getShort("KEY_SEQ") <= 1 || !current.table().equals(parent)) {
                        current = new ForeignKey(parent, new ArrayList<>(), new ArrayList<>(), rs.getShort("DELETE_RULE"), rs.getShort("UPDATE_RULE"));
                        s.foreignKeys.add(current);
                    }
                    current.columns().add(rs.getString("FKCOLUMN_NAME"));
                    current.referenced().add(rs.getString("PKCOLUMN_NAME"));
                }
            }
            // SQLite: a lone INTEGER PRIMARY KEY is the row id, filled by the engine
            if (engine == Dialect.SQLITE && s.primaryKey.size() == 1) {
                Column id = s.columns.get(s.primaryKey.getFirst());
                if (id != null && id.type() != null && id.type().toUpperCase(Locale.ROOT).startsWith("INT") && !id.auto()) {
                    s.columns.put(s.primaryKey.getFirst(), new Column(id.type(), id.size(), id.nullable(), id.def(), true));
                }
            }
            return s;
        }

        /** Columns used by a key or an index: MySQL needs a bounded text type there. */
        private Set<String> keyed() {
            Set<String> out = new LinkedHashSet<>(primaryKey);
            for (Index i : indexes) out.addAll(i.columns());
            for (ForeignKey k : foreignKeys) out.addAll(k.columns());
            return out;
        }

        String create(Dialect to, String table) {
            Set<String> keyed = keyed();
            List<String> parts = new ArrayList<>();
            boolean inlinePk = false;
            for (Map.Entry<String, Column> e : columns.entrySet()) {
                Column col = e.getValue();
                if (col.auto() && primaryKey.size() == 1 && primaryKey.getFirst().equals(e.getKey())) {
                    parts.add(q(to, e.getKey()) + " " + to.autoId());
                    inlinePk = true;
                    continue;
                }
                StringBuilder sb = new StringBuilder(q(to, e.getKey())).append(' ').append(type(to, col, keyed.contains(e.getKey())));
                if (!col.nullable()) sb.append(" NOT NULL");
                String def = defaultValue(col.def());
                if (def != null) sb.append(" DEFAULT ").append(def);
                parts.add(sb.toString());
            }
            if (!inlinePk && !primaryKey.isEmpty()) {
                parts.add("PRIMARY KEY (" + String.join(", ", primaryKey.stream().map(c -> q(to, c)).toList()) + ")");
            }
            for (ForeignKey k : foreignKeys) {
                parts.add("FOREIGN KEY (" + String.join(", ", k.columns().stream().map(c -> q(to, c)).toList()) + ") REFERENCES "
                        + q(to, k.table()) + " (" + String.join(", ", k.referenced().stream().map(c -> q(to, c)).toList()) + ")"
                        + " ON DELETE " + rule(k.onDelete()) + " ON UPDATE " + rule(k.onUpdate()));
            }
            return "CREATE TABLE " + q(to, table) + " (" + String.join(", ", parts) + ")"
                    + (to == Dialect.MYSQL ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "");
        }

        List<String> indexes(Dialect to, String table) {
            List<String> out = new ArrayList<>();
            for (Index i : indexes) {
                // an index keeps its name when it already starts with its table's; otherwise (engine's own names,
                // names of a former table name) it is named after its table and columns: SQLite index names are
                // global to the file, and a name that tells its table never misleads
                String name = i.name().startsWith(table) ? i.name()
                        : table + "_" + String.join("_", i.columns()) + (i.unique() ? "_unique" : "");
                if (name.length() > 64) name = name.substring(0, 64); // MySQL identifier limit
                out.add("CREATE " + (i.unique() ? "UNIQUE " : "") + "INDEX " + q(to, name) + " ON " + q(to, table) + " ("
                        + String.join(", ", i.columns().stream().map(c -> q(to, c)).toList()) + ")");
            }
            return out;
        }

        private static String type(Dialect to, Column col, boolean key) {
            String t = col.type() == null ? "" : col.type().toUpperCase(Locale.ROOT);
            boolean mysql = to == Dialect.MYSQL;
            if (t.contains("INT") || t.startsWith("BIT") || t.startsWith("BOOL")) return mysql ? "BIGINT" : "INTEGER";
            if (t.contains("DOUBLE") || t.contains("REAL") || t.contains("FLOAT") || t.contains("DEC") || t.contains("NUMERIC")) return "DOUBLE";
            if (t.contains("BLOB") || t.contains("BINARY")) return mysql ? "LONGBLOB" : "BLOB";
            if (t.contains("TIMESTAMP") || t.contains("DATE") || t.contains("TIME")) return mysql ? "DATETIME" : "TIMESTAMP";
            // text: bounded in a key or an index (MySQL), large otherwise (SQLite text has no limit)
            if (!mysql) return "TEXT";
            if (key) return "VARCHAR(" + (col.size() > 0 && col.size() <= 191 ? col.size() : 191) + ")";
            return t.startsWith("VARCHAR") && col.size() > 0 && col.size() <= 255 ? "VARCHAR(" + col.size() + ")" : "MEDIUMTEXT";
        }

        private static String rule(short rule) {
            return switch (rule) {
                case DatabaseMetaData.importedKeyCascade -> "CASCADE";
                case DatabaseMetaData.importedKeySetNull -> "SET NULL";
                case DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT";
                case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
                default -> "NO ACTION";
            };
        }

        /** A default both engines read the same way: numbers, quoted text, CURRENT_TIMESTAMP. */
        private static String defaultValue(String def) {
            if (def == null) return null;
            String d = def.trim();
            if (d.isEmpty() || d.equalsIgnoreCase("NULL")) return null;
            if (d.toUpperCase(Locale.ROOT).startsWith("CURRENT_TIMESTAMP")) return "CURRENT_TIMESTAMP";
            if (d.matches("-?\\d+(\\.\\d+)?")) return d;
            if (d.startsWith("'") && d.endsWith("'") && d.length() >= 2) return d;
            return null;
        }
    }
}
