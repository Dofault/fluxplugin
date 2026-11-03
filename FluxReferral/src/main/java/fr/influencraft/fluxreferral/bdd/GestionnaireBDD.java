package fr.influencraft.fluxreferral.bdd;

import fr.influencraft.flux.api.data.Database;
import fr.influencraft.fluxreferral.FluxReferralPlugin;
import fr.influencraft.fluxreferral.configuration.GestionnaireConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

/**
 * Tables of the referral system in the suite's database (Flux {@link Database}: SQLite file or MySQL/MariaDB,
 * configured once in {@code plugins/Flux/config.yml}). Creates the schema in the dialect of that database and moves
 * the former local file ({@code base-de-donnees.fichier-local}) into it once.
 *
 * @author Entr0pik
 */
public class GestionnaireBDD {

    private enum Dialecte { MYSQL, SQLITE }

    private final FluxReferralPlugin plugin;
    private final GestionnaireConfig config;
    private Dialecte dialecte;

    public GestionnaireBDD(FluxReferralPlugin plugin, GestionnaireConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void initialiser() throws SQLException {
        if (!Database.available()) throw new SQLException("database of Flux unavailable");
        dialecte = Database.dialect() == Database.Dialect.MYSQL ? Dialecte.MYSQL : Dialecte.SQLITE;
        // names before 2026-10-31: <former prefix, parr_ by default><French name>; foreign keys follow the renames
        String former = config.getPrefixeTables();
        try (Connection conn = obtenirConnexion()) {
            Database.renameTable(conn, former + "joueurs", Tables.PLAYERS);
            Database.renameTable(conn, former + "evenements", Tables.EVENTS);
            Database.renameTable(conn, former + "paliers", Tables.TIERS);
            Database.renameTable(conn, former + "invitations", Tables.INVITATIONS);
            Database.renameTable(conn, former + "decouvertes", Tables.DISCOVERIES);
        }
        creerSchema();
        // the plugin's own SQLite file of the first versions, parents first (foreign keys)
        Database.importSqlite(plugin, new File(plugin.getDataFolder(), config.getFichierLocalBDD()),
                former + "joueurs", Tables.PLAYERS, former + "evenements", Tables.EVENTS,
                former + "paliers", Tables.TIERS, former + "invitations", Tables.INVITATIONS, former + "decouvertes", Tables.DISCOVERIES);
    }

    private void creerSchema() throws SQLException {
        if (dialecte == Dialecte.MYSQL) {
            creerSchemaMysql();
        } else {
            creerSchemaSqlite();
        }
        plugin.getLogger().info("Schéma de base de données initialisé avec succès.");
    }

    private void creerSchemaMysql() throws SQLException {
        // base of the constraint names
        String nomBase = "flux_referral";

        try (Connection conn = obtenirConnexion(); Statement stmt = conn.createStatement()) {

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + Tables.PLAYERS + "` (" +
                "  `uuid`               VARCHAR(36)  NOT NULL," +
                "  `pseudo`             VARCHAR(16)  NOT NULL," +
                "  `code`               VARCHAR(8)   NOT NULL," +
                "  `parrain_uuid`       VARCHAR(36)  DEFAULT NULL," +
                "  `nb_filleuls`        INT          NOT NULL DEFAULT 0," +
                "  `premiere_connexion` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  `derniere_connexion` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (`uuid`)," +
                "  UNIQUE KEY `uq_" + nomBase + "_code` (`code`)," +
                "  CONSTRAINT `fk_" + nomBase + "_parrain`" +
                "    FOREIGN KEY (`parrain_uuid`) REFERENCES `" + Tables.PLAYERS + "`(`uuid`) ON DELETE SET NULL" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + Tables.EVENTS + "` (" +
                "  `id`               INT          NOT NULL AUTO_INCREMENT," +
                "  `parrain_uuid`     VARCHAR(36)  NOT NULL," +
                "  `filleul_uuid`     VARCHAR(36)  NOT NULL," +
                "  `date_validation`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (`id`)," +
                "  UNIQUE KEY `uq_" + nomBase + "_filleul` (`filleul_uuid`)," +
                "  CONSTRAINT `fk_evt_" + nomBase + "_parrain`" +
                "    FOREIGN KEY (`parrain_uuid`) REFERENCES `" + Tables.PLAYERS + "`(`uuid`) ON DELETE CASCADE," +
                "  CONSTRAINT `fk_evt_" + nomBase + "_filleul`" +
                "    FOREIGN KEY (`filleul_uuid`) REFERENCES `" + Tables.PLAYERS + "`(`uuid`) ON DELETE CASCADE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + Tables.TIERS + "` (" +
                "  `parrain_uuid`    VARCHAR(36)  NOT NULL," +
                "  `seuil`           INT          NOT NULL," +
                "  `date_obtention`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (`parrain_uuid`, `seuil`)," +
                "  CONSTRAINT `fk_palier_" + nomBase + "_parrain`" +
                "    FOREIGN KEY (`parrain_uuid`) REFERENCES `" + Tables.PLAYERS + "`(`uuid`) ON DELETE CASCADE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + Tables.INVITATIONS + "` (" +
                "  `filleul_uuid`  VARCHAR(36)  NOT NULL," +
                "  `parrain_uuid`  VARCHAR(36)  NOT NULL," +
                "  `hote`          VARCHAR(255) NOT NULL," +
                "  `temps_joue`    BIGINT       NOT NULL DEFAULT 0," +
                "  `date_arrivee`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (`filleul_uuid`)," +
                "  KEY `idx_" + nomBase + "_inv_parrain` (`parrain_uuid`)," +
                "  CONSTRAINT `fk_inv_" + nomBase + "_parrain`" +
                "    FOREIGN KEY (`parrain_uuid`) REFERENCES `" + Tables.PLAYERS + "`(`uuid`) ON DELETE CASCADE," +
                "  CONSTRAINT `fk_inv_" + nomBase + "_filleul`" +
                "    FOREIGN KEY (`filleul_uuid`) REFERENCES `" + Tables.PLAYERS + "`(`uuid`) ON DELETE CASCADE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + Tables.DISCOVERIES + "` (" +
                "  `uuid`            VARCHAR(36)  NOT NULL," +
                "  `source`          VARCHAR(32)  NOT NULL," +
                "  `parrain_uuid`    VARCHAR(36)  DEFAULT NULL," +
                "  `detail`          VARCHAR(64)  DEFAULT NULL," +
                "  `date_decouverte` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (`uuid`)," +
                "  KEY `idx_" + nomBase + "_decouverte_source` (`source`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );
        }
        ajouterColonneSiAbsente(Tables.PLAYERS, "ip_hash", "VARCHAR(64) DEFAULT NULL");
        ajouterColonneSiAbsente(Tables.PLAYERS, "filleuls_recompenses_donnees", "INT NOT NULL DEFAULT 0");
        ajouterColonneSiAbsente(Tables.PLAYERS, "filleul_recompense_recuperee", "TINYINT(1) NOT NULL DEFAULT 0");
        ajouterColonneSiAbsente(Tables.TIERS, "recupere", "TINYINT(1) NOT NULL DEFAULT 0");
    }

    /**
     * Schéma SQLite : pas d'AUTO_INCREMENT/ENGINE/CHARSET (spécifiques MySQL), PRIMARY KEY
     * et UNIQUE déclarés inline. Les FOREIGN KEY restent identiques (activées via
     * PRAGMA foreign_keys, cf. {@link #demarrerSqlite()}).
     */
    private void creerSchemaSqlite() throws SQLException {

        try (Connection conn = obtenirConnexion(); Statement stmt = conn.createStatement()) {

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + Tables.PLAYERS + "` (" +
                "  `uuid`               VARCHAR(36)  NOT NULL PRIMARY KEY," +
                "  `pseudo`             VARCHAR(16)  NOT NULL," +
                "  `code`               VARCHAR(8)   NOT NULL UNIQUE," +
                "  `parrain_uuid`       VARCHAR(36)  DEFAULT NULL," +
                "  `nb_filleuls`        INTEGER      NOT NULL DEFAULT 0," +
                "  `premiere_connexion` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  `derniere_connexion` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  FOREIGN KEY (`parrain_uuid`) REFERENCES `" + Tables.PLAYERS + "`(`uuid`) ON DELETE SET NULL" +
                ")"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + Tables.EVENTS + "` (" +
                "  `id`               INTEGER      PRIMARY KEY AUTOINCREMENT," +
                "  `parrain_uuid`     VARCHAR(36)  NOT NULL," +
                "  `filleul_uuid`     VARCHAR(36)  NOT NULL UNIQUE," +
                "  `date_validation`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  FOREIGN KEY (`parrain_uuid`) REFERENCES `" + Tables.PLAYERS + "`(`uuid`) ON DELETE CASCADE," +
                "  FOREIGN KEY (`filleul_uuid`) REFERENCES `" + Tables.PLAYERS + "`(`uuid`) ON DELETE CASCADE" +
                ")"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + Tables.TIERS + "` (" +
                "  `parrain_uuid`    VARCHAR(36)  NOT NULL," +
                "  `seuil`           INTEGER      NOT NULL," +
                "  `date_obtention`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (`parrain_uuid`, `seuil`)," +
                "  FOREIGN KEY (`parrain_uuid`) REFERENCES `" + Tables.PLAYERS + "`(`uuid`) ON DELETE CASCADE" +
                ")"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + Tables.INVITATIONS + "` (" +
                "  `filleul_uuid`  VARCHAR(36)  NOT NULL PRIMARY KEY," +
                "  `parrain_uuid`  VARCHAR(36)  NOT NULL," +
                "  `hote`          VARCHAR(255) NOT NULL," +
                "  `temps_joue`    INTEGER      NOT NULL DEFAULT 0," +
                "  `date_arrivee`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  FOREIGN KEY (`parrain_uuid`) REFERENCES `" + Tables.PLAYERS + "`(`uuid`) ON DELETE CASCADE," +
                "  FOREIGN KEY (`filleul_uuid`) REFERENCES `" + Tables.PLAYERS + "`(`uuid`) ON DELETE CASCADE" +
                ")"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + Tables.DISCOVERIES + "` (" +
                "  `uuid`            VARCHAR(36)  NOT NULL PRIMARY KEY," +
                "  `source`          VARCHAR(32)  NOT NULL," +
                "  `parrain_uuid`    VARCHAR(36)  DEFAULT NULL," +
                "  `detail`          VARCHAR(64)  DEFAULT NULL," +
                "  `date_decouverte` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );
        }
        ajouterColonneSiAbsente(Tables.PLAYERS, "ip_hash", "VARCHAR(64) DEFAULT NULL");
        ajouterColonneSiAbsente(Tables.PLAYERS, "filleuls_recompenses_donnees", "INTEGER NOT NULL DEFAULT 0");
        ajouterColonneSiAbsente(Tables.PLAYERS, "filleul_recompense_recuperee", "INTEGER NOT NULL DEFAULT 0");
        ajouterColonneSiAbsente(Tables.TIERS, "recupere", "INTEGER NOT NULL DEFAULT 0");
    }

    /**
     * Ajoute une colonne à une table existante si elle n'y est pas encore (migration douce :
     * les bases créées par une version précédente gardent leurs données).
     * Passe par les métadonnées JDBC, MySQL n'ayant pas de ADD COLUMN IF NOT EXISTS.
     */
    private void ajouterColonneSiAbsente(String table, String colonne, String definition) throws SQLException {
        String nomTable = table;
        try (Connection conn = obtenirConnexion()) {
            boolean existe = false;
            for (String candidat : new String[]{nomTable, nomTable.toUpperCase()}) {
                try (ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, candidat, null)) {
                    while (rs.next()) {
                        if (colonne.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) existe = true;
                    }
                }
            }
            if (existe) return;
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("ALTER TABLE `" + nomTable + "` ADD COLUMN `" + colonne + "` " + definition);
            }
            plugin.getLogger().info("Colonne " + colonne + " ajoutée à la table " + nomTable + ".");
        }
    }

    /** {@code INSERT IGNORE} (MySQL) ou {@code INSERT OR IGNORE} (SQLite). */
    public String insertIgnore() {
        return dialecte == Dialecte.SQLITE ? "INSERT OR IGNORE" : "INSERT IGNORE";
    }

    public Connection obtenirConnexion() throws SQLException {
        return Database.connection();
    }

    public void fermer() {
        // the pool belongs to Flux
    }
}
