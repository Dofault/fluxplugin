package fr.influencraft.fluxreferral.bdd;

import fr.influencraft.fluxreferral.FluxReferralPlugin;

import java.sql.*;
import java.util.Optional;
import java.util.UUID;

/**
 * Dépôt gérant les insertions et lectures sur la table des événements de parrainage.
 *
 * @author Entr0pik
 */
public class DepotEvenement {

    private final GestionnaireBDD gestionnaireBDD;
    private final String prefixe;

    public DepotEvenement(FluxReferralPlugin plugin, GestionnaireBDD gestionnaireBDD) {
        this.gestionnaireBDD = gestionnaireBDD;
        this.prefixe = plugin.getGestionnaireConfig().getPrefixeTables();
    }

    public void insererEvenement(UUID parrainUuid, UUID filleulUuid) throws SQLException {
        String sql = "INSERT INTO `" + prefixe + "evenements` (parrain_uuid, filleul_uuid) VALUES (?, ?)";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, parrainUuid.toString());
            stmt.setString(2, filleulUuid.toString());
            stmt.executeUpdate();
        }
    }

    /**
     * Supprime le lien de parrainage d'un filleul de façon atomique.
     * Dans une seule transaction : supprime la ligne dans parr_evenements,
     * efface parrain_uuid du filleul, et décrémente nb_filleuls du parrain.
     *
     * @return UUID du parrain décrémenté, ou Optional.empty() si aucun lien n'existait
     */
    public Optional<UUID> supprimerLienParrainage(UUID filleulUuid) throws SQLException {
        try (Connection conn = gestionnaireBDD.obtenirConnexion()) {
            conn.setAutoCommit(false);
            try {
                // 1. Récupérer le parrain actuel du filleul
                UUID parrainUuid = null;
                String sqlSelect = "SELECT parrain_uuid FROM `" + prefixe + "joueurs` WHERE uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sqlSelect)) {
                    stmt.setString(1, filleulUuid.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            String v = rs.getString("parrain_uuid");
                            if (v != null) parrainUuid = UUID.fromString(v);
                        }
                    }
                }

                if (parrainUuid == null) {
                    conn.rollback();
                    return Optional.empty();
                }

                // 2. Supprimer l'événement
                String sqlDelEvt = "DELETE FROM `" + prefixe + "evenements` WHERE filleul_uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sqlDelEvt)) {
                    stmt.setString(1, filleulUuid.toString());
                    stmt.executeUpdate();
                }

                // 3. Effacer parrain_uuid du filleul
                String sqlNullParrain = "UPDATE `" + prefixe + "joueurs` SET parrain_uuid = NULL WHERE uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sqlNullParrain)) {
                    stmt.setString(1, filleulUuid.toString());
                    stmt.executeUpdate();
                }

                // 4. Décrémenter nb_filleuls du parrain (jamais en dessous de 0)
                // CASE WHEN plutôt que GREATEST() (absent de SQLite) pour rester compatible avec le fallback local
                String sqlDecr = "UPDATE `" + prefixe + "joueurs` SET nb_filleuls = CASE WHEN nb_filleuls - 1 < 0 THEN 0 ELSE nb_filleuls - 1 END WHERE uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sqlDecr)) {
                    stmt.setString(1, parrainUuid.toString());
                    stmt.executeUpdate();
                }

                conn.commit();
                return Optional.of(parrainUuid);

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public boolean estDejaFilleul(UUID filleulUuid) throws SQLException {
        String sql = "SELECT 1 FROM `" + prefixe + "evenements` WHERE filleul_uuid = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, filleulUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }
}
