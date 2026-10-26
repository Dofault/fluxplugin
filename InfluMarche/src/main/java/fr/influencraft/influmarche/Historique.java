package fr.influencraft.influmarche;

import org.bukkit.inventory.ItemStack;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Historique des ventes du marché (base SQLite {@code plugins/InfluMarche/historique.db}) : ventes
 * directes et enchères de l'hôtel des ventes, transactions des échoppes. Sert à {@code /ah history}
 * et au prix moyen ({@code /ah average}, menus).
 *
 * <p>Écritures sur un fil dédié ; lectures sur le fil principal, mises en cache quelques secondes
 * (un menu relit ses placeholders chaque seconde).</p>
 */
final class Historique {

    /** Une vente enregistrée. {@code total} = prix payé par l'acheteur, {@code taxe} = part retenue au vendeur. */
    record Vente(long date, String source, String ref, UUID vendeur, String vendeurNom, UUID acheteur, String acheteurNom,
                 String cle, String nom, int quantite, double total, double taxe, byte[] objet, String ville) {
        double unite() {
            return quantite > 0 ? total / quantite : total;
        }

        ItemStack item() {
            try {
                return objet == null ? null : ItemStack.deserializeBytes(objet);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** Prix observés d'une clé sur la période : moyenne pondérée par la quantité. */
    record Moyenne(int ventes, long quantite, double total, double min, double max, double moyenneHdv, double moyenneEchoppes) {
        double unite() {
            return quantite > 0 ? total / quantite : 0;
        }
    }

    private final Logger log;
    private final Connection db;
    private final ExecutorService ecriture = Executors.newSingleThreadExecutor(r -> new Thread(r, "InfluMarche-historique"));
    private final Map<String, Object[]> cache = new ConcurrentHashMap<>(); // clé -> {expiration, valeur}

    Historique(File dossier, Logger log) throws SQLException {
        this.log = log;
        dossier.mkdirs();
        try {
            Class.forName("org.sqlite.JDBC"); // pilote chargé par plugin.yml (libraries)
        } catch (ClassNotFoundException e) {
            throw new SQLException("pilote SQLite absent", e);
        }
        db = DriverManager.getConnection("jdbc:sqlite:" + new File(dossier, "historique.db").getAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS ventes (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      date INTEGER NOT NULL, source TEXT NOT NULL, ref TEXT,
                      vendeur TEXT, vendeur_nom TEXT, acheteur TEXT, acheteur_nom TEXT,
                      cle TEXT NOT NULL, nom TEXT, quantite INTEGER NOT NULL,
                      total REAL NOT NULL, taxe REAL NOT NULL, objet BLOB, ville TEXT)""");
            s.execute("CREATE INDEX IF NOT EXISTS ventes_cle ON ventes(cle, date)");
            s.execute("CREATE INDEX IF NOT EXISTS ventes_vendeur ON ventes(vendeur, date)");
            s.execute("CREATE INDEX IF NOT EXISTS ventes_acheteur ON ventes(acheteur, date)");
            // Enchères terminées déjà enregistrées (détectées par balayage, une seule fois chacune).
            s.execute("CREATE TABLE IF NOT EXISTS encheres_vues (note TEXT PRIMARY KEY)");
        }
    }

    void fermer() {
        ecriture.shutdown();
        try {
            ecriture.awaitTermination(5, TimeUnit.SECONDS);
            db.close();
        } catch (InterruptedException | SQLException e) {
            log.warning("Fermeture de l'historique : " + e);
        }
    }

    // ── Écriture ────────────────────────────────────────────────────────────

    void enregistrer(Vente v) {
        cache.clear();
        ecriture.execute(() -> {
            synchronized (db) {
                try (PreparedStatement ps = db.prepareStatement("INSERT INTO ventes (date, source, ref, vendeur, vendeur_nom, acheteur, "
                        + "acheteur_nom, cle, nom, quantite, total, taxe, objet, ville) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setLong(1, v.date());
                    ps.setString(2, v.source());
                    ps.setString(3, v.ref());
                    ps.setString(4, v.vendeur() == null ? null : v.vendeur().toString());
                    ps.setString(5, v.vendeurNom());
                    ps.setString(6, v.acheteur() == null ? null : v.acheteur().toString());
                    ps.setString(7, v.acheteurNom());
                    ps.setString(8, v.cle());
                    ps.setString(9, v.nom());
                    ps.setInt(10, v.quantite());
                    ps.setDouble(11, v.total());
                    ps.setDouble(12, v.taxe());
                    ps.setBytes(13, v.objet());
                    ps.setString(14, v.ville());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    log.warning("Vente non enregistrée dans l'historique : " + e);
                }
            }
            cache.clear();
        });
    }

    /** Marque une enchère terminée comme enregistrée ; faux si elle l'était déjà (fil principal). */
    boolean premiereFois(String note) {
        synchronized (db) {
            try (PreparedStatement ps = db.prepareStatement("INSERT OR IGNORE INTO encheres_vues (note) VALUES (?)")) {
                ps.setString(1, note);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                log.warning("Historique des enchères : " + e);
                return false;
            }
        }
    }

    // ── Lecture ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <T> T memo(String cle, long ms, java.util.function.Supplier<T> calcul) {
        Object[] c = cache.get(cle);
        long now = System.currentTimeMillis();
        if (c != null && (long) c[0] > now) return (T) c[1];
        T v = calcul.get();
        cache.put(cle, new Object[]{now + ms, v});
        return v;
    }

    /** Ventes et achats d'un joueur, les plus récents d'abord. filtre : 0 tout, 1 ventes, 2 achats. */
    List<Vente> duJoueur(UUID joueur, int filtre, int limite) {
        return memo("j:" + joueur + ":" + filtre, 5000, () -> {
            String where = switch (filtre) {
                case 1 -> "vendeur = ?";
                case 2 -> "acheteur = ?";
                default -> "(vendeur = ? OR acheteur = ?)";
            };
            List<Vente> l = new ArrayList<>();
            synchronized (db) {
                try (PreparedStatement ps = db.prepareStatement("SELECT * FROM ventes WHERE " + where + " ORDER BY date DESC LIMIT ?")) {
                    int i = 1;
                    ps.setString(i++, joueur.toString());
                    if (filtre == 0) ps.setString(i++, joueur.toString());
                    ps.setInt(i, limite);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) l.add(lire(rs));
                    }
                } catch (SQLException e) {
                    log.warning("Lecture de l'historique : " + e);
                }
            }
            return l;
        });
    }

    /** Prix moyen d'une clé sur les {@code jours} derniers jours (null si aucune vente). */
    Moyenne moyenne(String cle, int jours) {
        Object[] wrap = memo("m:" + cle + ":" + jours, 30000, () -> new Object[]{calculerMoyenne(cle, jours)});
        return (Moyenne) wrap[0];
    }

    private Moyenne calculerMoyenne(String cle, int jours) {
        long depuis = System.currentTimeMillis() - jours * 86_400_000L;
        synchronized (db) {
            try (PreparedStatement ps = db.prepareStatement("""
                    SELECT COUNT(*), SUM(quantite), SUM(total), MIN(total * 1.0 / quantite), MAX(total * 1.0 / quantite),
                      SUM(CASE WHEN source IN ('vente','enchere') THEN total END), SUM(CASE WHEN source IN ('vente','enchere') THEN quantite END),
                      SUM(CASE WHEN source IN ('echoppe','rachat') THEN total END), SUM(CASE WHEN source IN ('echoppe','rachat') THEN quantite END)
                    FROM ventes WHERE cle = ? AND date >= ? AND quantite > 0""")) {
                ps.setString(1, cle);
                ps.setLong(2, depuis);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getInt(1) == 0) return null;
                    double hdvT = rs.getDouble(6), echT = rs.getDouble(8);
                    long hdvQ = rs.getLong(7), echQ = rs.getLong(9);
                    return new Moyenne(rs.getInt(1), rs.getLong(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                            hdvQ > 0 ? hdvT / hdvQ : -1, echQ > 0 ? echT / echQ : -1);
                }
            } catch (SQLException e) {
                log.warning("Prix moyen : " + e);
                return null;
            }
        }
    }

    private static Vente lire(ResultSet rs) throws SQLException {
        String v = rs.getString("vendeur"), a = rs.getString("acheteur");
        return new Vente(rs.getLong("date"), rs.getString("source"), rs.getString("ref"),
                v == null ? null : UUID.fromString(v), rs.getString("vendeur_nom"),
                a == null ? null : UUID.fromString(a), rs.getString("acheteur_nom"),
                rs.getString("cle"), rs.getString("nom"), rs.getInt("quantite"), rs.getDouble("total"),
                rs.getDouble("taxe"), rs.getBytes("objet"), rs.getString("ville"));
    }
}
