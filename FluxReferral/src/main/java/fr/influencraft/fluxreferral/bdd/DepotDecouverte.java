package fr.influencraft.fluxreferral.bdd;

import fr.influencraft.fluxreferral.FluxReferralPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * How each player discovered the server (table {@code <prefix>decouvertes}): source ({@code adresse}, {@code joueur},
 * or a source of the questionnaire such as {@code tiktok}, {@code vote}), the player who brought him if any, a detail
 * (address typed, "questionnaire", "code") and the date. The first answer stays.
 */
public class DepotDecouverte {

    /** One discovery. */
    public record Decouverte(String source, UUID parrain, String detail, Timestamp date) {
    }

    private final GestionnaireBDD gestionnaireBDD;
    private final String table;

    public DepotDecouverte(FluxReferralPlugin plugin, GestionnaireBDD gestionnaireBDD) {
        this.gestionnaireBDD = gestionnaireBDD;
        this.table = plugin.getGestionnaireConfig().getPrefixeTables() + "decouvertes";
    }

    /** @return true when recorded (false: the player already had one) */
    public boolean enregistrer(UUID joueur, String source, UUID parrain, String detail) throws SQLException {
        String sql = gestionnaireBDD.insertIgnore() + " INTO `" + table + "` (uuid, source, parrain_uuid, detail) VALUES (?, ?, ?, ?)";
        try (Connection conn = gestionnaireBDD.obtenirConnexion(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, joueur.toString());
            stmt.setString(2, source);
            stmt.setString(3, parrain == null ? null : parrain.toString());
            stmt.setString(4, detail == null ? null : detail.length() > 64 ? detail.substring(0, 64) : detail);
            return stmt.executeUpdate() > 0;
        }
    }

    public Optional<Decouverte> trouver(UUID joueur) throws SQLException {
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement("SELECT source, parrain_uuid, detail, date_decouverte FROM `" + table + "` WHERE uuid = ?")) {
            stmt.setString(1, joueur.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String parrain = rs.getString(2);
                return Optional.of(new Decouverte(rs.getString(1), parrain == null ? null : UUID.fromString(parrain), rs.getString(3), rs.getTimestamp(4)));
            }
        }
    }

    /** Players per source, most common first. */
    public Map<String, Integer> compter() throws SQLException {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (Connection conn = gestionnaireBDD.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement("SELECT source, COUNT(*) FROM `" + table + "` GROUP BY source ORDER BY COUNT(*) DESC");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
        }
        return out;
    }
}
