package fr.influencraft.influlands;

import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.nation.Nation;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pays (nations de Lands) : « mon pays » (celui de ma ville), recherche, champs affichés par
 * les menus. Un pays n'a pas de banque propre : Lands prélève ses frais d'entretien sur la
 * banque de sa capitale, d'où le « trésor » affiché = solde de la capitale.
 */
final class Pays {

    private final InfluLands plugin;

    Pays(InfluLands plugin) {
        this.plugin = plugin;
    }

    static String id(Nation n) {
        return n.getULID().toString();
    }

    List<Nation> tous() {
        List<Nation> l = new ArrayList<>();
        for (Object o : plugin.villes().api().getNations()) if (o instanceof Nation n) l.add(n);
        return l;
    }

    Nation parId(String id) {
        if (id == null || id.isEmpty()) return null;
        try {
            return plugin.villes().api().getNationByULID(me.angeschossen.lands.api.applicationframework.util.ULID.fromString(id));
        } catch (RuntimeException e) {
            return null;
        }
    }

    Nation parNom(String nom) {
        return nom == null ? null : plugin.villes().api().getNationByName(nom);
    }

    /** Pays de « ma ville ». */
    Nation monPays(Player p) {
        Land ma = plugin.villes().maVille(p);
        return ma != null ? ma.getNation() : null;
    }

    static List<Land> villes(Nation n) {
        List<Land> l = new ArrayList<>();
        for (Object o : n.getLands()) if (o instanceof Land land) l.add(land);
        return l;
    }

    static int habitants(Nation n) {
        int s = 0;
        for (Land l : villes(n)) s += l.getTrustedPlayers().size();
        return s;
    }

    static int niveau(Nation n) {
        try {
            int pos = n.getLevel().getPosition();
            return Math.max(1, Math.min(4, pos <= 0 ? pos + 1 : pos));
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** Le joueur dirige-t-il ce pays (propriétaire de la nation, maire de la capitale) ? */
    static boolean estChef(Nation n, Player p) {
        if (n == null || p == null) return false;
        if (p.getUniqueId().equals(n.getOwnerUID())) return true;
        Land cap = n.getCapital();
        return cap != null && p.getUniqueId().equals(cap.getOwnerUID());
    }

    Map<String, String> champs(Nation n, Player viewer) {
        Map<String, String> m = new HashMap<>();
        if (n == null) {
            m.put("existe", "non");
            return m;
        }
        Land cap = n.getCapital();
        List<Land> villes = villes(n);
        Land ma = viewer != null ? plugin.villes().maVille(viewer) : null;
        boolean membre = ma != null && n.isMember(ma);
        int chunks = 0;
        for (Land l : villes) chunks += l.getChunksAmount();
        int niveau = niveau(n);
        m.put("existe", "oui");
        m.put("id", id(n));
        m.put("nom", n.getName());
        m.put("nom_couleur", Couleurs.legacy(n.getColorName()));
        String nomNiveau;
        try {
            nomNiveau = Couleurs.legacy(n.getLevel().getName());
        } catch (RuntimeException e) {
            nomNiveau = "?";
        }
        m.put("niveau", nomNiveau);
        m.put("niveau_num", String.valueOf(niveau));
        m.put("icone", "itemsadder:influville:pays_niveau_" + niveau);
        m.put("villes", String.valueOf(villes.size()));
        m.put("habitants", String.valueOf(habitants(n)));
        m.put("en_ligne", String.valueOf(n.getOnlinePlayers().size()));
        m.put("chunks", String.valueOf(chunks));
        m.put("capitale", cap != null ? cap.getName() : "?");
        m.put("capitale_couleur", cap != null ? Couleurs.legacy(cap.getColorName()) : "?");
        m.put("capitale_id", cap != null ? Villes.id(cap) : "");
        m.put("chef", Villes.nomJoueur(n.getOwnerUID()));
        m.put("tresor", membre && cap != null ? Fmt.argent(cap.getBalance()) : "");
        m.put("membre", Fmt.oui(membre));
        m.put("est_chef", Fmt.oui(estChef(n, viewer)));
        m.put("capitale_moi", Fmt.oui(ma != null && cap != null && Villes.id(ma).equals(Villes.id(cap))));
        m.put("maire_ici", Fmt.oui(ma != null && viewer != null && Villes.estMaire(ma, viewer.getUniqueId())));
        m.put("spawn", Fmt.oui(cap != null && cap.getSpawnPosition() != null));
        m.put("creation", new SimpleDateFormat("dd/MM/yyyy").format(new Date(n.getCreationTime())));
        return m;
    }
}
