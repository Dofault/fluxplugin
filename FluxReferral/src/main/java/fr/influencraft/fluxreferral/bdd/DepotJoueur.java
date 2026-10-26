package fr.influencraft.fluxreferral.bdd;

import fr.influencraft.fluxreferral.FluxReferralPlugin;
import fr.influencraft.fluxreferral.modele.JoueurParrainage;

import java.sql.*;
import java.util.*;

/**
 * Dépôt gérant les opérations CRUD sur la table des joueurs (parr_joueurs).
 *
 * @author Entr0pik
 */
public class DepotJoueur {

    private final FluxReferralPlugin plugin;
    private final GestionnaireBDD gestionnaireBDD;
    private final String prefixe;

    public DepotJoueur(FluxReferralPlugin plugin, GestionnaireBDD gestionnaireBDD) {
        this.plugin = plugin;
        this.gestionnaireBDD = gestionnaireBDD;
        this.prefixe = plugin.getGestionnaireConfig().getPrefixeTables();
    }

    public Optional<JoueurParrainage> trouverParUUID(UUID uuid) throws SQLException {
        String sql = "SELECT * FROM `" + prefixe + "joueurs` WHERE uuid = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(construire(rs)) : Optional.empty();
            }
        }
    }

    public Optional<JoueurParrainage> trouverParCode(String code) throws SQLException {
        String sql = "SELECT * FROM `" + prefixe + "joueurs` WHERE code = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, code.toUpperCase());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(construire(rs)) : Optional.empty();
            }
        }
    }

    /** Recherche par pseudo, sans tenir compte de la casse (adresse personnelle). */
    public Optional<JoueurParrainage> trouverParPseudo(String pseudo) throws SQLException {
        String sql = "SELECT * FROM `" + prefixe + "joueurs` WHERE LOWER(pseudo) = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, pseudo.toLowerCase(Locale.ROOT));
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(construire(rs)) : Optional.empty();
            }
        }
    }

    /** Enregistre l'empreinte (salée) de la dernière adresse IP du joueur. */
    public void mettreAJourIp(UUID uuid, String ipHash) throws SQLException {
        String sql = "UPDATE `" + prefixe + "joueurs` SET ip_hash = ? WHERE uuid = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, ipHash);
            stmt.setString(2, uuid.toString());
            stmt.executeUpdate();
        }
    }

    public Optional<String> obtenirIpHash(UUID uuid) throws SQLException {
        String sql = "SELECT ip_hash FROM `" + prefixe + "joueurs` WHERE uuid = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /** Pseudos des derniers filleuls validés d'un parrain, du plus récent au plus ancien. */
    public List<String> listerFilleulsRecents(UUID parrainUuid, int limite) throws SQLException {
        String sql = "SELECT j.pseudo FROM `" + prefixe + "evenements` e"
            + " JOIN `" + prefixe + "joueurs` j ON j.uuid = e.filleul_uuid"
            + " WHERE e.parrain_uuid = ? ORDER BY e.date_validation DESC, e.id DESC LIMIT ?";
        List<String> pseudos = new ArrayList<>();
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, parrainUuid.toString());
            stmt.setInt(2, limite);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) pseudos.add(rs.getString(1));
            }
        }
        return pseudos;
    }

    public boolean existeCode(String code) throws SQLException {
        String sql = "SELECT 1 FROM `" + prefixe + "joueurs` WHERE code = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, code.toUpperCase());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Insère un nouveau joueur avec son code généré. */
    public void insererJoueur(UUID uuid, String pseudo, String code) throws SQLException {
        String sql = "INSERT INTO `" + prefixe + "joueurs` (uuid, pseudo, code) VALUES (?, ?, ?)";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, pseudo);
            stmt.setString(3, code);
            stmt.executeUpdate();
        }
    }

    /** Met à jour le pseudo et la date de dernière connexion d'un joueur existant. */
    public void mettreAJourConnexion(UUID uuid, String pseudo) throws SQLException {
        // CURRENT_TIMESTAMP (et non NOW(), spécifique MySQL) pour rester compatible avec le fallback SQLite
        String sql = "UPDATE `" + prefixe + "joueurs` SET pseudo = ?, derniere_connexion = CURRENT_TIMESTAMP WHERE uuid = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, pseudo);
            stmt.setString(2, uuid.toString());
            stmt.executeUpdate();
        }
    }

    public void mettreAJourCode(UUID uuid, String code) throws SQLException {
        String sql = "UPDATE `" + prefixe + "joueurs` SET code = ? WHERE uuid = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, code);
            stmt.setString(2, uuid.toString());
            stmt.executeUpdate();
        }
    }

    public void mettreAJourParrain(UUID uuid, UUID parrainUuid) throws SQLException {
        String sql = "UPDATE `" + prefixe + "joueurs` SET parrain_uuid = ? WHERE uuid = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (parrainUuid != null) {
                stmt.setString(1, parrainUuid.toString());
            } else {
                stmt.setNull(1, Types.VARCHAR);
            }
            stmt.setString(2, uuid.toString());
            stmt.executeUpdate();
        }
    }

    public void incrementerFilleuls(UUID parrainUuid) throws SQLException {
        String sql = "UPDATE `" + prefixe + "joueurs` SET nb_filleuls = nb_filleuls + 1 WHERE uuid = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, parrainUuid.toString());
            stmt.executeUpdate();
        }
    }

    public int obtenirFilleulsRecompensesDonnees(UUID uuid) throws SQLException {
        String sql = "SELECT filleuls_recompenses_donnees FROM `" + prefixe + "joueurs` WHERE uuid = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Augmente le compteur de récompenses de filleul déjà données (une par clic réussi). */
    public void incrementerFilleulsRecompensesDonnees(UUID uuid, int quantite) throws SQLException {
        String sql = "UPDATE `" + prefixe + "joueurs` SET filleuls_recompenses_donnees = filleuls_recompenses_donnees + ? WHERE uuid = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, quantite);
            stmt.setString(2, uuid.toString());
            stmt.executeUpdate();
        }
    }

    public boolean obtenirFilleulRecompenseRecuperee(UUID uuid) throws SQLException {
        String sql = "SELECT filleul_recompense_recuperee FROM `" + prefixe + "joueurs` WHERE uuid = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) != 0;
            }
        }
    }

    public void marquerFilleulRecompenseRecuperee(UUID uuid) throws SQLException {
        String sql = "UPDATE `" + prefixe + "joueurs` SET filleul_recompense_recuperee = 1 WHERE uuid = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        }
    }

    public int obtenirNbFilleuls(UUID uuid) throws SQLException {
        String sql = "SELECT nb_filleuls FROM `" + prefixe + "joueurs` WHERE uuid = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt("nb_filleuls") : 0;
            }
        }
    }

    /** Supprime toutes les données d'un joueur (cascade sur événements et paliers). */
    public void reinitialiser(UUID uuid) throws SQLException {
        String sql = "DELETE FROM `" + prefixe + "joueurs` WHERE uuid = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        }
    }

    /** Classement trié par nb_filleuls décroissant avec pseudo. */
    public List<Map.Entry<String, Integer>> obtenirClassementAvecPseudos(int limite) throws SQLException {
        String sql = "SELECT pseudo, nb_filleuls FROM `" + prefixe + "joueurs` WHERE nb_filleuls > 0 ORDER BY nb_filleuls DESC LIMIT ?";
        List<Map.Entry<String, Integer>> classement = new ArrayList<>();
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limite);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    classement.add(Map.entry(rs.getString("pseudo"), rs.getInt("nb_filleuls")));
                }
            }
        }
        return classement;
    }

    /** Rang du joueur dans le classement (1-indexé). */
    public int obtenirRang(UUID uuid) throws SQLException {
        String sql = "SELECT COUNT(*) + 1 FROM `" + prefixe + "joueurs` " +
                     "WHERE nb_filleuls > (SELECT nb_filleuls FROM `" + prefixe + "joueurs` WHERE uuid = ?)";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private JoueurParrainage construire(ResultSet rs) throws SQLException {
        String parrainStr = rs.getString("parrain_uuid");
        UUID parrainUuid = parrainStr != null ? UUID.fromString(parrainStr) : null;
        return new JoueurParrainage(
            UUID.fromString(rs.getString("uuid")),
            rs.getString("pseudo"),
            rs.getString("code"),
            parrainUuid,
            rs.getInt("nb_filleuls"),
            rs.getTimestamp("premiere_connexion").toLocalDateTime(),
            rs.getTimestamp("derniere_connexion").toLocalDateTime()
        );
    }
}
