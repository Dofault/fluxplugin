package fr.influencraft.fluxlands;

import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.nation.Nation;
import me.angeschossen.lands.api.nation.invite.NationInvite;
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

    private final FluxLands plugin;

    Pays(FluxLands plugin) {
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

    // ── Invitations ─────────────────────────────────────────────────────────

    /** Lands' method giving a country's pending invitation of a town (found once by its signature). */
    private static volatile java.lang.reflect.Method inviteOf;
    private static volatile boolean inviteMissing;

    /**
     * Invitations received by a town, by country name. Lands keeps them on each country and its API has no way to list
     * them: the country's public method that returns the {@link NationInvite} of a given town is found by its signature
     * (return type, one town parameter), whatever its obfuscated name; the invitation is then accepted through the API.
     */
    Map<String, NationInvite> invitations(Land land) {
        Map<String, NationInvite> out = new HashMap<>();
        for (Nation n : tous()) {
            if (n.isMember(land)) continue;
            NationInvite invite = invitation(n, land);
            if (invite != null) out.put(n.getName(), invite);
        }
        return out;
    }

    private NationInvite invitation(Nation n, Land land) {
        try {
            java.lang.reflect.Method m = inviteOf;
            if (m == null) {
                for (java.lang.reflect.Method c : n.getClass().getMethods()) {
                    if (c.getReturnType() == NationInvite.class && c.getParameterCount() == 1 && c.getParameterTypes()[0].isInstance(land)) {
                        m = c;
                        break;
                    }
                }
                if (m == null) {
                    if (!inviteMissing) plugin.getLogger().warning("Lands: no method found to read the invitations of a country (Lands update?)");
                    inviteMissing = true;
                    return null;
                }
                inviteOf = m;
            }
            return (NationInvite) m.invoke(n, land);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
