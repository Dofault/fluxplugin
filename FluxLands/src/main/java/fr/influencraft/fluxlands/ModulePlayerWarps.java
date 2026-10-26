package fr.influencraft.fluxlands;

import dev.revivalo.playerwarps.PlayerWarpsPlugin;
import dev.revivalo.playerwarps.warp.Warp;
import dev.revivalo.playerwarps.warp.WarpStatus;
import me.angeschossen.lands.api.land.Land;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lien avec InfluPW (chargé seulement s'il est présent) : index des adresses (warps)
 * et de la ville où se trouve chacune, calculée d'après leur position. Rien n'est stocké
 * côté InfluPW : déplacer un warp change sa ville au passage suivant de l'index.
 */
final class ModulePlayerWarps {

    private final FluxLands plugin;
    private volatile List<Fiches.Adresse> index = List.of();
    private volatile Map<String, Fiches.Adresse> parId = Map.of();
    private volatile Map<String, Fiches.Adresse> parNom = Map.of();

    ModulePlayerWarps(FluxLands plugin) {
        this.plugin = plugin;
    }

    List<Fiches.Adresse> toutes() {
        return index;
    }

    Fiches.Adresse parId(String id) {
        return id == null ? null : parId.get(id);
    }

    Fiches.Adresse parNom(String nom) {
        return nom == null ? null : parNom.get(nom.toLowerCase(java.util.Locale.ROOT));
    }

    void indexer() {
        List<Fiches.Adresse> l = new ArrayList<>();
        Map<String, Fiches.Adresse> ids = new HashMap<>();
        Map<String, Fiches.Adresse> noms = new HashMap<>();
        for (Warp w : PlayerWarpsPlugin.getWarpHandler().getWarps()) {
            try {
                if (w.hasUnresolvedLocation()) continue; // monde pas encore chargé
                Location loc = w.getLocation();
                if (loc == null || loc.getWorld() == null) continue;
                Land land = plugin.villes().a(loc);
                String titre = w.getRawDisplayName() != null && !w.getRawDisplayName().isBlank() ? w.getRawDisplayName() : w.getName();
                Fiches.Adresse a = new Fiches.Adresse(w.getWarpID().toString(), w.getName(), titre, w.getOwner(),
                        w.getOwnerName(), loc.clone(), land != null ? Villes.id(land) : "", land != null ? land.getName() : "",
                        w.getVisits(), w.getConvertedRating(), w.getAdmission(), Fiches.materiau(w.getMenuItem()),
                        w.getCategory() != null ? w.getCategory().getDisplayName() : "",
                        w.getStatus() == WarpStatus.OPENED || w.getStatus() == WarpStatus.PASSWORD_PROTECTED,
                        w.isPasswordProtected(), w.getDescription(), w.getDateCreated());
                l.add(a);
                ids.put(a.id(), a);
                noms.put(a.nom().toLowerCase(java.util.Locale.ROOT), a);
            } catch (RuntimeException e) {
                // warp incomplet : ignoré jusqu'au prochain passage
            }
        }
        index = l;
        parId = ids;
        parNom = noms;
    }
}
