package fr.influencraft.fluxreferral.bdd;

import fr.influencraft.fluxreferral.FluxReferralPlugin;

import java.sql.*;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Dépôt gérant les requêtes sur la table des paliers obtenus (anti-doublon de récompenses).
 *
 * @author Entr0pik
 */
public class DepotPalier {

    private final GestionnaireBDD gestionnaireBDD;
    private final String prefixe;

    public DepotPalier(FluxReferralPlugin plugin, GestionnaireBDD gestionnaireBDD) {
        this.gestionnaireBDD = gestionnaireBDD;
        this.prefixe = plugin.getGestionnaireConfig().getPrefixeTables();
    }

    public boolean palierDejaObtenu(UUID parrainUuid, int seuil) throws SQLException {
        String sql = "SELECT 1 FROM `" + prefixe + "paliers` WHERE parrain_uuid = ? AND seuil = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, parrainUuid.toString());
            stmt.setInt(2, seuil);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Enregistre un palier obtenu. INSERT IGNORE (INSERT OR IGNORE en SQLite) évite l'erreur en cas de double déclenchement concurrent.
     */
    public void enregistrerPalier(UUID parrainUuid, int seuil) throws SQLException {
        String sql = gestionnaireBDD.insertIgnore() + " INTO `" + prefixe + "paliers` (parrain_uuid, seuil) VALUES (?, ?)";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, parrainUuid.toString());
            stmt.setInt(2, seuil);
            stmt.executeUpdate();
        }
    }

    /** Seuils déjà obtenus par un parrain (atteints, récupérés ou non). */
    public Set<Integer> paliersObtenus(UUID parrainUuid) throws SQLException {
        String sql = "SELECT seuil FROM `" + prefixe + "paliers` WHERE parrain_uuid = ?";
        Set<Integer> seuils = new HashSet<>();
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, parrainUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) seuils.add(rs.getInt(1));
            }
        }
        return seuils;
    }

    /** Seuils atteints mais dont la récompense n'a pas encore été récupérée (clic joueur). */
    public Set<Integer> paliersEnAttente(UUID parrainUuid) throws SQLException {
        String sql = "SELECT seuil FROM `" + prefixe + "paliers` WHERE parrain_uuid = ? AND recupere = 0";
        Set<Integer> seuils = new HashSet<>();
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, parrainUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) seuils.add(rs.getInt(1));
            }
        }
        return seuils;
    }

    /**
     * Marque un palier comme récupéré. Appelé après exécution des commandes, jamais avant
     * (l'acquittement se fait avant l'exécution côté appelant — cf. ServiceRecuperation).
     */
    public void marquerRecupere(UUID parrainUuid, int seuil) throws SQLException {
        String sql = "UPDATE `" + prefixe + "paliers` SET recupere = 1 WHERE parrain_uuid = ? AND seuil = ?";
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, parrainUuid.toString());
            stmt.setInt(2, seuil);
            stmt.executeUpdate();
        }
    }
}
