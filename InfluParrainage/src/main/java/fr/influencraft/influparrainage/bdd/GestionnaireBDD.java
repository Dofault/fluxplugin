package fr.influencraft.influparrainage.bdd;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import fr.influencraft.influparrainage.InfluParrainagePlugin;
import fr.influencraft.influparrainage.configuration.GestionnaireConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

/**
 * Gestionnaire du pool de connexions HikariCP et de l'initialisation du schéma SQL.
 * Supporte deux dialectes : MySQL/MariaDB (production) et SQLite (fallback local,
 * utile en environnement de test sans base MariaDB provisionnée, ex : 04_labs).
 *
 * @author Entr0pik
 */
public class GestionnaireBDD {

    private enum Dialecte { MYSQL, SQLITE }

    private final InfluParrainagePlugin plugin;
    private final GestionnaireConfig config;
    private HikariDataSource source;
    private Dialecte dialecte;

    public GestionnaireBDD(InfluParrainagePlugin plugin, GestionnaireConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void initialiser() throws SQLException {
        String mode = config.getModeBDD();

        if ("local".equalsIgnoreCase(mode)) {
            demarrerSqlite();
        } else if ("mysql".equalsIgnoreCase(mode)) {
            demarrerMysql();
        } else {
            // Mode "auto" (par défaut) : MySQL/MariaDB en priorité, fallback SQLite si injoignable
            try {
                demarrerMysql();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                    "Connexion MySQL/MariaDB impossible (" + e.getMessage()
                    + ") — bascule sur le fallback SQLite local.");
                demarrerSqlite();
            }
        }

        creerSchema();
    }

    private void demarrerMysql() throws SQLException {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(
            "jdbc:mysql://" + config.getHoteBDD() + ":" + config.getPortBDD()
            + "/" + config.getNomBDD()
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8"
        );
        hikariConfig.setUsername(config.getUtilisateurBDD());
        hikariConfig.setPassword(config.getMotDePasseBDD());
        hikariConfig.setMaximumPoolSize(config.getTaillePool());
        hikariConfig.setMinimumIdle(2);
        // Court : en mode "auto", on ne doit pas rester bloqué longtemps avant de basculer sur SQLite
        hikariConfig.setConnectionTimeout(5_000);
        hikariConfig.setIdleTimeout(600_000);
        hikariConfig.setMaxLifetime(1_800_000);
        hikariConfig.setPoolName("InfluParrainage-Pool-MySQL");
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
        // Validation de la connexion différée à nous (voir plus bas) plutôt qu'à l'initialisation du pool,
        // pour pouvoir intercepter l'échec proprement et basculer sur SQLite en mode "auto"
        hikariConfig.setInitializationFailTimeout(-1);

        HikariDataSource candidate = new HikariDataSource(hikariConfig);
        try (Connection test = candidate.getConnection()) {
            // Connexion validée
        } catch (SQLException e) {
            candidate.close();
            throw e;
        }

        this.source = candidate;
        this.dialecte = Dialecte.MYSQL;
        plugin.getLogger().info("Connexion au pool de base de données MySQL/MariaDB établie.");
    }

    private void demarrerSqlite() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        File fichier = new File(plugin.getDataFolder(), config.getFichierLocalBDD());

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + fichier.getAbsolutePath());
        // SQLite ne supporte qu'un seul writer à la fois : une seule connexion dans le pool
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setPoolName("InfluParrainage-Pool-SQLite");
        // Nécessaire à chaque connexion : SQLite n'active pas les clés étrangères par défaut
        hikariConfig.setConnectionInitSql("PRAGMA foreign_keys = ON");

        this.source = new HikariDataSource(hikariConfig);
        this.dialecte = Dialecte.SQLITE;
        plugin.getLogger().info("Connexion au fallback SQLite local établie (" + fichier.getAbsolutePath() + ").");
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
        String p = config.getPrefixeTables();
        // Identifiant de base pour les noms de contraintes (sans underscore terminal)
        String nomBase = p.endsWith("_") ? p.substring(0, p.length() - 1) : p;

        try (Connection conn = obtenirConnexion(); Statement stmt = conn.createStatement()) {

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + p + "joueurs` (" +
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
                "    FOREIGN KEY (`parrain_uuid`) REFERENCES `" + p + "joueurs`(`uuid`) ON DELETE SET NULL" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + p + "evenements` (" +
                "  `id`               INT          NOT NULL AUTO_INCREMENT," +
                "  `parrain_uuid`     VARCHAR(36)  NOT NULL," +
                "  `filleul_uuid`     VARCHAR(36)  NOT NULL," +
                "  `date_validation`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (`id`)," +
                "  UNIQUE KEY `uq_" + nomBase + "_filleul` (`filleul_uuid`)," +
                "  CONSTRAINT `fk_evt_" + nomBase + "_parrain`" +
                "    FOREIGN KEY (`parrain_uuid`) REFERENCES `" + p + "joueurs`(`uuid`) ON DELETE CASCADE," +
                "  CONSTRAINT `fk_evt_" + nomBase + "_filleul`" +
                "    FOREIGN KEY (`filleul_uuid`) REFERENCES `" + p + "joueurs`(`uuid`) ON DELETE CASCADE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + p + "paliers` (" +
                "  `parrain_uuid`    VARCHAR(36)  NOT NULL," +
                "  `seuil`           INT          NOT NULL," +
                "  `date_obtention`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (`parrain_uuid`, `seuil`)," +
                "  CONSTRAINT `fk_palier_" + nomBase + "_parrain`" +
                "    FOREIGN KEY (`parrain_uuid`) REFERENCES `" + p + "joueurs`(`uuid`) ON DELETE CASCADE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + p + "invitations` (" +
                "  `filleul_uuid`  VARCHAR(36)  NOT NULL," +
                "  `parrain_uuid`  VARCHAR(36)  NOT NULL," +
                "  `hote`          VARCHAR(255) NOT NULL," +
                "  `temps_joue`    BIGINT       NOT NULL DEFAULT 0," +
                "  `date_arrivee`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (`filleul_uuid`)," +
                "  KEY `idx_" + nomBase + "_inv_parrain` (`parrain_uuid`)," +
                "  CONSTRAINT `fk_inv_" + nomBase + "_parrain`" +
                "    FOREIGN KEY (`parrain_uuid`) REFERENCES `" + p + "joueurs`(`uuid`) ON DELETE CASCADE," +
                "  CONSTRAINT `fk_inv_" + nomBase + "_filleul`" +
                "    FOREIGN KEY (`filleul_uuid`) REFERENCES `" + p + "joueurs`(`uuid`) ON DELETE CASCADE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );
        }
        ajouterColonneSiAbsente("joueurs", "ip_hash", "VARCHAR(64) DEFAULT NULL");
        ajouterColonneSiAbsente("joueurs", "filleuls_recompenses_donnees", "INT NOT NULL DEFAULT 0");
        ajouterColonneSiAbsente("joueurs", "filleul_recompense_recuperee", "TINYINT(1) NOT NULL DEFAULT 0");
        ajouterColonneSiAbsente("paliers", "recupere", "TINYINT(1) NOT NULL DEFAULT 0");
    }

    /**
     * Schéma SQLite : pas d'AUTO_INCREMENT/ENGINE/CHARSET (spécifiques MySQL), PRIMARY KEY
     * et UNIQUE déclarés inline. Les FOREIGN KEY restent identiques (activées via
     * PRAGMA foreign_keys, cf. {@link #demarrerSqlite()}).
     */
    private void creerSchemaSqlite() throws SQLException {
        String p = config.getPrefixeTables();

        try (Connection conn = obtenirConnexion(); Statement stmt = conn.createStatement()) {

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + p + "joueurs` (" +
                "  `uuid`               VARCHAR(36)  NOT NULL PRIMARY KEY," +
                "  `pseudo`             VARCHAR(16)  NOT NULL," +
                "  `code`               VARCHAR(8)   NOT NULL UNIQUE," +
                "  `parrain_uuid`       VARCHAR(36)  DEFAULT NULL," +
                "  `nb_filleuls`        INTEGER      NOT NULL DEFAULT 0," +
                "  `premiere_connexion` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  `derniere_connexion` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  FOREIGN KEY (`parrain_uuid`) REFERENCES `" + p + "joueurs`(`uuid`) ON DELETE SET NULL" +
                ")"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + p + "evenements` (" +
                "  `id`               INTEGER      PRIMARY KEY AUTOINCREMENT," +
                "  `parrain_uuid`     VARCHAR(36)  NOT NULL," +
                "  `filleul_uuid`     VARCHAR(36)  NOT NULL UNIQUE," +
                "  `date_validation`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  FOREIGN KEY (`parrain_uuid`) REFERENCES `" + p + "joueurs`(`uuid`) ON DELETE CASCADE," +
                "  FOREIGN KEY (`filleul_uuid`) REFERENCES `" + p + "joueurs`(`uuid`) ON DELETE CASCADE" +
                ")"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + p + "paliers` (" +
                "  `parrain_uuid`    VARCHAR(36)  NOT NULL," +
                "  `seuil`           INTEGER      NOT NULL," +
                "  `date_obtention`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (`parrain_uuid`, `seuil`)," +
                "  FOREIGN KEY (`parrain_uuid`) REFERENCES `" + p + "joueurs`(`uuid`) ON DELETE CASCADE" +
                ")"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + p + "invitations` (" +
                "  `filleul_uuid`  VARCHAR(36)  NOT NULL PRIMARY KEY," +
                "  `parrain_uuid`  VARCHAR(36)  NOT NULL," +
                "  `hote`          VARCHAR(255) NOT NULL," +
                "  `temps_joue`    INTEGER      NOT NULL DEFAULT 0," +
                "  `date_arrivee`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  FOREIGN KEY (`parrain_uuid`) REFERENCES `" + p + "joueurs`(`uuid`) ON DELETE CASCADE," +
                "  FOREIGN KEY (`filleul_uuid`) REFERENCES `" + p + "joueurs`(`uuid`) ON DELETE CASCADE" +
                ")"
            );
        }
        ajouterColonneSiAbsente("joueurs", "ip_hash", "VARCHAR(64) DEFAULT NULL");
        ajouterColonneSiAbsente("joueurs", "filleuls_recompenses_donnees", "INTEGER NOT NULL DEFAULT 0");
        ajouterColonneSiAbsente("joueurs", "filleul_recompense_recuperee", "INTEGER NOT NULL DEFAULT 0");
        ajouterColonneSiAbsente("paliers", "recupere", "INTEGER NOT NULL DEFAULT 0");
    }

    /**
     * Ajoute une colonne à une table existante si elle n'y est pas encore (migration douce :
     * les bases créées par une version précédente gardent leurs données).
     * Passe par les métadonnées JDBC, MySQL n'ayant pas de ADD COLUMN IF NOT EXISTS.
     */
    private void ajouterColonneSiAbsente(String table, String colonne, String definition) throws SQLException {
        String nomTable = config.getPrefixeTables() + table;
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
        return source.getConnection();
    }

    public void fermer() {
        if (source != null && !source.isClosed()) {
            source.close();
            plugin.getLogger().info("Pool de connexions fermé proprement.");
        }
    }
}
