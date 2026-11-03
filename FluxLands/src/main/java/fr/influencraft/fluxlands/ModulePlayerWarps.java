package fr.influencraft.fluxlands;

import fr.influencraft.fluxpw.api.PlayerWarps;
import me.angeschossen.lands.api.land.Land;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lien avec FluxPW (chargé seulement s'il est présent) : index des adresses (warps)
 * et de la ville où se trouve chacune, calculée d'après leur position. Rien n'est stocké
 * côté FluxPW : déplacer un warp change sa ville au passage suivant de l'index.
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
        for (PlayerWarps.Info w : PlayerWarps.all()) {
            try {
                Location loc = w.location();
                if (loc == null || loc.getWorld() == null) continue; // monde pas encore chargé
                Land land = plugin.villes().a(loc);
                Fiches.Adresse a = new Fiches.Adresse(w.id(), w.name(), w.displayName(), w.owner(),
                        w.ownerName(), loc.clone(), land != null ? Villes.id(land) : "", land != null ? land.getName() : "",
                        w.visits(), (float) w.rating(), w.admission(), w.icon(), w.category(), w.open(),
                        w.password(), w.description(), w.created());
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
