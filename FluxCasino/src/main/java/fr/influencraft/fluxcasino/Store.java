package fr.influencraft.fluxcasino;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Soldes et mises en base SQLite (plugins/FluxCasino/casino.db, pilote fourni par Paper), écrits tout de
 * suite à chaque mouvement (un jeton vaut de l'argent : pas de sauvegarde différée). Chaque mouvement de
 * solde est aussi inscrit au journal (ledger : joueur, variation, solde après, motif, date), pour pouvoir
 * retracer un litige ou un abus. Soldes et mises gardés en cache (lus par les placeholders à chaque rendu).
 * Tout se passe sur le fil principal.
 */
final class Store {

    private final Connection db;
    private final Map<UUID, long[]> cache = new ConcurrentHashMap<>(); // {jetons, mise}
    private final long defaultBet;

    Store(File folder, long defaultBet) throws SQLException {
        this.defaultBet = defaultBet;
        folder.mkdirs();
        db = DriverManager.getConnection("jdbc:sqlite:" + new File(folder, "casino.db").getAbsolutePath());
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS balances (uuid TEXT PRIMARY KEY, name TEXT, chips INTEGER NOT NULL DEFAULT 0,"
                    + " bet INTEGER NOT NULL, updated INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS ledger (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL,"
                    + " delta INTEGER NOT NULL, balance INTEGER NOT NULL, reason TEXT NOT NULL, at INTEGER NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS ledger_uuid ON ledger (uuid, id)");
        }
    }

    void close() {
        try {
            db.close();
        } catch (SQLException ignored) {
            // arrêt du serveur
        }
    }

    private long[] row(UUID uuid) {
        return cache.computeIfAbsent(uuid, u -> {
            try (PreparedStatement ps = db.prepareStatement("SELECT chips, bet FROM balances WHERE uuid = ?")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : new long[]{0, defaultBet};
                }
            } catch (SQLException e) {
                throw new IllegalStateException("lecture du solde de " + u, e);
            }
        });
    }

    long chips(UUID uuid) {
        return row(uuid)[0];
    }

    long bet(UUID uuid) {
        return row(uuid)[1];
    }

    void forget(UUID uuid) {
        cache.remove(uuid);
    }

    /**
     * Ajoute delta (négatif = retrait) au solde, journal compris, dans une transaction. Refusé (false) si le
     * solde deviendrait négatif. Le cache n'est mis à jour qu'une fois l'écriture validée.
     */
    boolean add(UUID uuid, String name, long delta, String reason) {
        long[] r = row(uuid);
        long after = r[0] + delta;
        if (after < 0) return false;
        try {
            db.setAutoCommit(false);
            write(uuid, name, after, r[1]);
            try (PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO ledger (uuid, delta, balance, reason, at) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, delta);
                ps.setLong(3, after);
                ps.setString(4, reason);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            db.commit();
            r[0] = after;
            return true;
        } catch (SQLException e) {
            try {
                db.rollback();
            } catch (SQLException ignored) {
                // la connexion est déjà en défaut
            }
            throw new IllegalStateException("écriture du solde de " + uuid, e);
        } finally {
            try {
                db.setAutoCommit(true);
            } catch (SQLException ignored) {
                // idem
            }
        }
    }

    void setBet(UUID uuid, String name, long bet) {
        long[] r = row(uuid);
        try {
            write(uuid, name, r[0], bet);
            r[1] = bet;
        } catch (SQLException e) {
            throw new IllegalStateException("écriture de la mise de " + uuid, e);
        }
    }

    private void write(UUID uuid, String name, long chips, long bet) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO balances (uuid, name, chips, bet, updated) VALUES (?, ?, ?, ?, ?)"
                + " ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, chips = excluded.chips, bet = excluded.bet,"
                + " updated = excluded.updated")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, chips);
            ps.setLong(4, bet);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** Derniers mouvements d'un joueur (le plus récent d'abord) : « variation solde motif date ». */
    List<String[]> history(UUID uuid, int limit) {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT delta, balance, reason, at FROM ledger WHERE uuid = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{String.valueOf(rs.getLong(1)), String.valueOf(rs.getLong(2)), rs.getString(3),
                            String.valueOf(rs.getLong(4))});
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("lecture du journal de " + uuid, e);
        }
        return out;
    }
}
