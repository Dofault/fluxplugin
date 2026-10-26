package fr.influencraft.fluxlands;

import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.land.enums.LandType;
import me.angeschossen.lands.api.player.LandPlayer;
import me.angeschossen.lands.api.role.Role;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Accès à Lands : villes, ville d'un lieu, villes d'un joueur, rôles. */
final class Villes {

    private final FluxLands plugin;
    private final LandsIntegration api;
    private Set<LandType> types = EnumSet.of(LandType.LAND);

    Villes(FluxLands plugin) {
        this.plugin = plugin;
        this.api = LandsIntegration.of(plugin);
    }

    LandsIntegration api() {
        return api;
    }

    void recharger() {
        Set<LandType> t = EnumSet.noneOf(LandType.class);
        for (String s : plugin.getConfig().getStringList("droit-de-place.types-de-ville")) {
            try {
                t.add(LandType.valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("types-de-ville : type inconnu « " + s + " » (LAND, ADMIN, CAMP)");
            }
        }
        types = t.isEmpty() ? EnumSet.of(LandType.LAND) : t;
    }

    /** La ville perçoit-elle le droit de place (type de ville retenu dans la config) ? */
    boolean percoit(Land land) {
        return land != null && types.contains(land.getLandType());
    }

    static String id(Land land) {
        return land.getULID().toString();
    }

    /** Villes de joueurs (type LAND), affichées dans l'annuaire. */
    List<Land> toutes() {
        List<Land> l = new ArrayList<>();
        for (Object o : api.getLands()) if (o instanceof Land land && land.getLandType() == LandType.LAND) l.add(land);
        return l;
    }

    Land parId(String id) {
        if (id == null || id.isEmpty()) return null;
        try {
            return api.getLandByULID(me.angeschossen.lands.api.applicationframework.util.ULID.fromString(id));
        } catch (RuntimeException e) {
            return null;
        }
    }

    Land parNom(String nom) {
        return nom == null ? null : api.getLandByName(nom);
    }

    /** Ville du lieu, sans charger le chunk. */
    Land a(Location loc) {
        if (loc == null) return null;
        World w = loc.getWorld();
        if (w == null) return null;
        return api.getLandByUnloadedChunk(w, loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    /** Villes dont le joueur (connecté) est maire ou habitant, la sienne en premier. */
    List<Land> duJoueur(Player p) {
        LandPlayer lp = api.getLandPlayer(p.getUniqueId());
        List<Land> l = new ArrayList<>();
        if (lp == null) return l;
        for (Object o : lp.getLands()) if (o instanceof Land land && land.getLandType() == LandType.LAND) l.add(land);
        l.sort(Comparator.comparing((Land x) -> !p.getUniqueId().equals(x.getOwnerUID())).thenComparing(Land::getName));
        return l;
    }

    /** « Ma ville » : celle choisie parmi les siennes, sinon celle dont il est maire, sinon la première. */
    Land maVille(Player p) {
        List<Land> siennes = duJoueur(p);
        if (siennes.isEmpty()) return null;
        String choix = plugin.registre().villeChoisie(p.getUniqueId());
        if (choix != null) for (Land l : siennes) if (id(l).equals(choix)) return l;
        return siennes.get(0);
    }

    static boolean estMaire(Land land, UUID joueur) {
        return land != null && joueur != null && joueur.equals(land.getOwnerUID());
    }

    static String nomJoueur(UUID uuid) {
        if (uuid == null) return "?";
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        OfflinePlayer o = Bukkit.getOfflinePlayer(uuid);
        return o.getName() != null ? o.getName() : "?";
    }

    /** Rôle du joueur dans la ville (nom coloré Lands), « Maire » pour le propriétaire. */
    static String role(Land land, UUID joueur) {
        if (land == null || joueur == null) return "";
        try {
            Role r = land.getDefaultArea().getRole(joueur);
            if (r != null) return Couleurs.legacy(r.getColorName());
        } catch (RuntimeException ignored) {
        }
        return estMaire(land, joueur) ? "Maire" : "Habitant";
    }

    /** Position du niveau (1 à 7 pour les villes), bornée. */
    static int niveau(Land land) {
        try {
            int pos = land.getLevel().getPosition();
            return Math.max(1, Math.min(7, pos <= 0 ? pos + 1 : pos));
        } catch (RuntimeException e) {
            return 1;
        }
    }

    static String nomNiveau(Land land) {
        try {
            return Couleurs.legacy(land.getLevel().getName());
        } catch (RuntimeException e) {
            return "?";
        }
    }

    static String nation(Land land) {
        try {
            return land.getNation() != null ? Couleurs.legacy(land.getNation().getColorName()) : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Nom lisible d'un joueur pour un lore : nom simple, sans couleurs Lands. */
    static String nomSimple(Land land) {
        return land == null ? "" : land.getName();
    }
}
