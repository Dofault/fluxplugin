package fr.influencraft.influparrainage.bdd;

import fr.influencraft.influparrainage.InfluParrainagePlugin;
import fr.influencraft.influparrainage.modele.Invitation;
import fr.influencraft.influparrainage.modele.Profil;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dépôt des arrivées par adresse personnelle en attente de validation (parr_invitations).
 * Une ligne par filleul : la première adresse utilisée gagne, la ligne disparaît à la validation.
 */
public class DepotInvitation {

    private final GestionnaireBDD gestionnaireBDD;
    private final String prefixe;

    public DepotInvitation(InfluParrainagePlugin plugin, GestionnaireBDD gestionnaireBDD) {
        this.gestionnaireBDD = gestionnaireBDD;
        this.prefixe = plugin.getGestionnaireConfig().getPrefixeTables();
    }

    /** @return true si l'invitation a été créée (false si le filleul en avait déjà une) */
    public boolean inserer(UUID filleulUuid, UUID parrainUuid, String hote) throws SQLException {
        String sql = gestionnaireBDD.insertIgnore() + " INTO `" + prefixe
            + "invitations` (filleul_uuid, parrain_uuid, hote) VALUES (?, ?, ?)";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, filleulUuid.toString());
            stmt.setString(2, parrainUuid.toString());
            stmt.setString(3, hote.length() > 255 ? hote.substring(0, 255) : hote);
            return stmt.executeUpdate() > 0;
        }
    }

    public Optional<Invitation> trouver(UUID filleulUuid) throws SQLException {
        String sql = "SELECT * FROM `" + prefixe + "invitations` WHERE filleul_uuid = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, filleulUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Invitation(
                    UUID.fromString(rs.getString("filleul_uuid")),
                    UUID.fromString(rs.getString("parrain_uuid")),
                    rs.getString("hote"),
                    rs.getLong("temps_joue")));
            }
        }
    }

    public void supprimer(UUID filleulUuid) throws SQLException {
        String sql = "DELETE FROM `" + prefixe + "invitations` WHERE filleul_uuid = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, filleulUuid.toString());
            stmt.executeUpdate();
        }
    }

    public void mettreAJourTempsJoue(UUID filleulUuid, long secondes) throws SQLException {
        String sql = "UPDATE `" + prefixe + "invitations` SET temps_joue = ? WHERE filleul_uuid = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, secondes);
            stmt.setString(2, filleulUuid.toString());
            stmt.executeUpdate();
        }
    }

    /** Filleuls en attente d'un parrain, du plus ancien au plus récent. */
    public List<Profil.Filleul> listerParParrain(UUID parrainUuid) throws SQLException {
        String sql = "SELECT j.pseudo, i.temps_joue FROM `" + prefixe + "invitations` i"
            + " JOIN `" + prefixe + "joueurs` j ON j.uuid = i.filleul_uuid"
            + " WHERE i.parrain_uuid = ? ORDER BY i.date_arrivee ASC";
        List<Profil.Filleul> liste = new ArrayList<>();
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, parrainUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) liste.add(new Profil.Filleul(rs.getString(1), rs.getLong(2)));
            }
        }
        return liste;
    }
}
