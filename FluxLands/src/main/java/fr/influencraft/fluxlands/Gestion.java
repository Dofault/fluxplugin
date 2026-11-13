package fr.influencraft.fluxlands;

import me.angeschossen.lands.api.blockworks.BoundingBox;
import me.angeschossen.lands.api.framework.blockutil.UnloadedPosition;
import me.angeschossen.lands.api.land.Area;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.land.LandArea;
import me.angeschossen.lands.api.land.block.LandBlock;
import me.angeschossen.lands.api.land.rental.offer.base.AcceptedRentalOfferBase;
import me.angeschossen.lands.api.land.rental.offer.base.RentalBase;
import me.angeschossen.lands.api.land.rental.offer.types.RentOffer;
import me.angeschossen.lands.api.land.rental.offer.types.RentalType;
import me.angeschossen.lands.api.player.LandPlayer;
import me.angeschossen.lands.api.player.Selection;
import me.angeschossen.lands.api.role.Role;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.block.SignChangeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gestion de ma ville sans les menus natifs de Lands : parcelles, annonces de vente et de
 * location (Agence), rôles. Lands n'a pas d'API pour créer une annonce : elle naît d'une
 * pancarte au format « Lands / rent|sell parcelle / durées / prix ». On pose donc la pancarte
 * et on lance nous-mêmes le {@link SignChangeEvent} au nom du joueur, comme s'il l'avait
 * écrite : Lands la valide (droits, format, prix), crée l'offre et, si
 * {@code rent.holograms} est actif, remplace la pancarte par un hologramme cliquable.
 */
final class Gestion {

    /** Annonce disponible (location ou vente) vue par l'Agence. */
    record Annonce(String villeId, String ville, String parcelle, boolean location, double prix, int minutes,
                   int maxMinutes, long taille, Location lieu, String maire) {
    }

    private final FluxLands plugin;
    private List<Annonce> annonces = List.of();
    private long calculees;

    Gestion(FluxLands plugin) {
        this.plugin = plugin;
    }

    /** Couleurs Lands (MiniMessage, §, &) retirées. */
    static String nu(String s) {
        return s == null ? "" : Couleurs.nu(s);
    }

    // ── Parcelles ───────────────────────────────────────────────────────────

    /** Sous-parcelles de la ville (la parcelle par défaut, qui couvre toute la ville, est exclue). */
    List<Area> parcelles(Land land) {
        List<Area> l = new ArrayList<>();
        for (Object o : land.getAllAreas()) if (o instanceof Area a && !a.isDefault()) l.add(a);
        return l;
    }

    Area parcelle(Land land, String nom) {
        if (land == null || nom == null || nom.isEmpty()) return null;
        Area a = land.getArea(nom);
        return a != null && !a.isDefault() ? a : null;
    }

    /** Plot of the town nearest to {@code loc} (horizontal distance to its box), within {@code range} blocks, or null. */
    Area plusProche(Land land, Location loc, int range) {
        if (land == null || loc == null || loc.getWorld() == null) return null;
        Area best = null;
        double bestDist = (double) range * range;
        for (Object o : land.getSubAreas(loc.getWorld())) {
            if (!(o instanceof LandArea la) || la.isDefault() || la.getBoundingBox() == null) continue;
            BoundingBox b = la.getBoundingBox();
            double dx = Math.max(0, Math.max(b.getMin().getX() - loc.getX(), loc.getX() - (b.getMax().getX() + 1)));
            double dz = Math.max(0, Math.max(b.getMin().getZ() - loc.getZ(), loc.getZ() - (b.getMax().getZ() + 1)));
            double d = dx * dx + dz * dz;
            if (d <= bestDist) {
                bestDist = d;
                best = la;
            }
        }
        return best;
    }

    long volume(Area a) {
        if (a instanceof LandArea la && la.getBoundingBox() != null) {
            BoundingBox b = la.getBoundingBox();
            return (long) (b.getMax().getX() - b.getMin().getX() + 1) * (b.getMax().getY() - b.getMin().getY() + 1)
                    * (b.getMax().getZ() - b.getMin().getZ() + 1);
        }
        return 0;
    }

    static String dimensions(Area a) {
        if (a instanceof LandArea la && la.getBoundingBox() != null) {
            BoundingBox b = la.getBoundingBox();
            return (b.getMax().getX() - b.getMin().getX() + 1) + " x " + (b.getMax().getZ() - b.getMin().getZ() + 1)
                    + " (hauteur " + (b.getMax().getY() - b.getMin().getY() + 1) + ")";
        }
        return "?";
    }

    /** Point d'arrivée d'une parcelle : son spawn, sinon le centre au sol. */
    static Location centre(Area a) {
        try {
            UnloadedPosition s = a.getSpawn();
            if (s != null && s.getWorld() != null) return s.toLocation();
        } catch (RuntimeException ignored) {
        }
        if (a instanceof LandArea la && la.getBoundingBox() != null && la.getWorld() != null) {
            BoundingBox b = la.getBoundingBox();
            int x = (b.getMax().getX() + b.getMin().getX()) / 2;
            int z = (b.getMax().getZ() + b.getMin().getZ()) / 2;
            return la.getWorld().getHighestBlockAt(x, z).getLocation().add(0.5, 1, 0.5);
        }
        return null;
    }

    Map<String, String> champsParcelle(Area a) {
        Map<String, String> m = new HashMap<>();
        RentalBase offre = a.getRentalOffer();
        RentalType type = offre != null ? offre.getType() : null;
        String etat = "Libre", code = "libre", icone = "itemsadder:influville:parcelle";
        if (!delimitee(a)) {
            etat = Lang.t("gestion.a-delimiter");
            icone = "itemsadder:influitems:unknown";
        }
        if (type == RentalType.RENT) {
            etat = "En location";
            code = "annonce";
            icone = "itemsadder:influville:location";
        } else if (type == RentalType.SELL) {
            etat = "En vente";
            code = "annonce";
            icone = "itemsadder:influville:vente";
        } else if (type == RentalType.RENTED) {
            etat = Lang.t("gestion.louee");
            code = "occupee";
            icone = "itemsadder:influitems:lock";
        } else if (type == RentalType.SOLD) {
            etat = "Vendue";
            code = "occupee";
            icone = "itemsadder:influitems:lock";
        }
        UUID locataire = a.getTenant();
        m.put("existe", "oui");
        m.put("nom", a.getName());
        m.put("taille", dimensions(a));
        m.put("volume", Fmt.argent(volume(a)));
        m.put("etat", etat);
        m.put("etat_code", code);
        m.put("annonce", Fmt.oui(code.equals("annonce")));
        m.put("delimitee", Fmt.oui(delimitee(a)));
        m.put("occupee", Fmt.oui(code.equals("occupee")));
        m.put("icone", icone);
        m.put("locataire", locataire != null ? Villes.nomJoueur(locataire) : "");
        m.put("prix", offre != null ? Fmt.argent(offre.getCost()) : "");
        m.put("periode", offre instanceof RentOffer r ? duree(r.getMinutes()) : "");
        m.put("habitants", String.valueOf(a.getTrustedPlayers().size()));
        // Lignes de lore complètes (glyphe BitBlock brut U+E023, résultat non relu par PlaceholderAPI).
        m.put("annonce_ligne", offre == null ? "&7Aucune annonce"
                : "&f" + etat + "&#B376E5 : &f" + Fmt.argent(offre.getCost()) + " &r&f\uE023"
                + (offre instanceof RentOffer r2 ? "&#B376E5 par &f" + duree(r2.getMinutes()) : ""));
        m.put("locataire_ligne", locataire != null ? (type == RentalType.SOLD ? Lang.t("gestion.proprietaire") : "&#B376E5Locataire : &f")
                + Villes.nomJoueur(locataire) : Lang.t("gestion.personne-n-occupe-la-parcelle"));
        Location c = centre(a);
        m.put("coord", Fiches.coord(c));
        return m;
    }

    static String duree(int minutes) {
        if (minutes <= 0) return Lang.t("gestion.illimitee");
        if (minutes % 1440 == 0) return (minutes / 1440) + " j";
        if (minutes % 60 == 0) return (minutes / 60) + " h";
        return minutes + " min";
    }

    /** Taille de la sélection Lands du joueur, ou "" s'il n'en a pas de complète. */
    String selection(Player p) {
        LandPlayer lp = plugin.villes().api().getLandPlayer(p.getUniqueId());
        Selection s = lp != null ? lp.getSelection() : null;
        if (s == null || !s.isComplete() || s.getPos1() == null || s.getPos2() == null) return "";
        int dx = Math.abs(s.getPos1().getX() - s.getPos2().getX()) + 1;
        int dz = Math.abs(s.getPos1().getZ() - s.getPos2().getZ()) + 1;
        int dy = Math.abs(s.getPos1().getY() - s.getPos2().getY()) + 1;
        return dx + " x " + dz + " (hauteur " + dy + ")";
    }

    /** Y bas et haut de la sélection Lands du joueur, ou null sans sélection complète. */
    int[] ySelection(Player p) {
        LandPlayer lp = plugin.villes().api().getLandPlayer(p.getUniqueId());
        Selection s = lp != null ? lp.getSelection() : null;
        if (s == null || !s.isComplete() || s.getPos1() == null || s.getPos2() == null) return null;
        return new int[]{Math.min(s.getPos1().getY(), s.getPos2().getY()), Math.max(s.getPos1().getY(), s.getPos2().getY())};
    }

    /**
     * Règle le Y bas et/ou haut de la sélection Lands (x et z inchangés). {@code setPos1/2} écrit
     * dans la sélection réelle de Lands et la recalcule (vérifié dans le code de Lands 8.8.0).
     * Valeurs bornées aux limites du monde ; renvoie false sans sélection complète.
     */
    boolean reglerY(Player p, Integer yMin, Integer yMax) {
        LandPlayer lp = plugin.villes().api().getLandPlayer(p.getUniqueId());
        Selection s = lp != null ? lp.getSelection() : null;
        if (s == null || !s.isComplete() || s.getPos1() == null || s.getPos2() == null) return false;
        org.bukkit.Location a = s.getPos1().toLocation();
        org.bukkit.Location b = s.getPos2().toLocation();
        org.bukkit.World w = a.getWorld() != null ? a.getWorld() : p.getWorld();
        int bas = Math.min(a.getBlockY(), b.getBlockY());
        int haut = Math.max(a.getBlockY(), b.getBlockY());
        if (yMin != null) bas = yMin;
        if (yMax != null) haut = yMax;
        bas = Math.max(w.getMinHeight(), Math.min(w.getMaxHeight() - 1, bas));
        haut = Math.max(w.getMinHeight(), Math.min(w.getMaxHeight() - 1, haut));
        if (bas > haut) {
            int t = bas;
            bas = haut;
            haut = t;
        }
        a.setY(bas);
        b.setY(haut);
        s.setPos1(a);
        s.setPos2(b);
        return true;
    }

    /** Hauteur de la sélection Lands du joueur (0 sans sélection complète). */
    int hauteurSelection(Player p) {
        LandPlayer lp = plugin.villes().api().getLandPlayer(p.getUniqueId());
        Selection s = lp != null ? lp.getSelection() : null;
        if (s == null || !s.isComplete() || s.getPos1() == null || s.getPos2() == null) return 0;
        return Math.abs(s.getPos1().getY() - s.getPos2().getY()) + 1;
    }

    /** La parcelle a-t-elle une zone (assignée depuis une sélection) ? */
    static boolean delimitee(Area a) {
        return a instanceof LandArea la && la.isSetup() && la.getBoundingBox() != null;
    }

    // ── Rôles ───────────────────────────────────────────────────────────────

    Role role(Land land, String id) {
        if (land == null || id == null || id.isEmpty()) return null;
        for (Object o : land.getDefaultArea().getRoles()) if (o instanceof Role r && r.getULID().toString().equals(id)) return r;
        return null;
    }

    /** A role given to residents (normal ones, role of new residents): the mayor renames and orders them. */
    static boolean roleHabitant(Role r) {
        return r.getType() == me.angeschossen.lands.api.role.enums.RoleType.NORMAL
                || r.getType() == me.angeschossen.lands.api.role.enums.RoleType.ENTRY;
    }

    static String typeRole(Role r) {
        return switch (r.getType()) {
            case OWNER -> "Maire";
            case VISITOR -> "Passants";
            case ENTRY -> "Nouveaux habitants";
            case TENANT -> "Locataires";
            case NATION -> "Compatriote";
            case ALLY -> Lang.t("gestion.allies");
            default -> Lang.t("gestion.role-d-habitant");
        };
    }

    // ── Agence ──────────────────────────────────────────────────────────────

    /** Annonces disponibles de toutes les villes, recalculées au plus toutes les 2 secondes. */
    List<Annonce> annonces() {
        long now = System.currentTimeMillis();
        if (now - calculees < 2000) return annonces;
        List<Annonce> l = new ArrayList<>();
        for (Land land : plugin.villes().toutes()) {
            for (Area a : parcelles(land)) {
                RentalBase o = a.getRentalOffer();
                if (o == null || (o.getType() != RentalType.RENT && o.getType() != RentalType.SELL)) continue;
                Location lieu = lieuOffre(o, a);
                boolean loc = o.getType() == RentalType.RENT;
                l.add(new Annonce(Villes.id(land), land.getName(), a.getName(), loc, o.getCost(),
                        loc && o instanceof RentOffer r ? r.getMinutes() : 0, loc && o instanceof RentOffer r2 ? r2.getMaxMinutes() : 0,
                        o.getSize() > 0 ? o.getSize() : volume(a), lieu, Villes.nomJoueur(land.getOwnerUID())));
            }
        }
        annonces = l;
        calculees = now;
        return l;
    }

    void invalider() {
        calculees = 0;
    }

    static Location lieuOffre(RentalBase o, Area a) {
        try {
            LandBlock b = o.getBlock();
            if (b != null && b.getCenter() != null) return b.getCenter().toLocation().add(0.5, 0, 0.5);
        } catch (RuntimeException ignored) {
        }
        return centre(a);
    }

    Map<String, String> champsAnnonce(Annonce a) {
        Map<String, String> m = new HashMap<>();
        m.put("existe", "oui");
        m.put("ville", a.ville());
        m.put("ville_id", a.villeId());
        m.put("parcelle", a.parcelle());
        m.put("type", a.location() ? "Location" : "Vente");
        m.put("location", Fmt.oui(a.location()));
        m.put("prix", Fmt.argent(a.prix()));
        m.put("periode", a.location() ? duree(a.minutes()) : "");
        m.put("max", a.location() ? duree(a.maxMinutes()) : "");
        // Ligne de lore complète pour une location (masquée pour une vente).
        m.put("periode_ligne", a.location() ? Lang.t("gestion.par-periode-de") + duree(a.minutes()) + Lang.t("gestion.au-plus") + duree(a.maxMinutes()) : "");
        m.put("taille", Fmt.argent(a.taille()));
        m.put("maire", a.maire());
        m.put("icone", a.location() ? "itemsadder:influville:location" : "itemsadder:influville:vente");
        m.put("coord", Fiches.coord(a.lieu()));
        m.put("monde", a.lieu() != null && a.lieu().getWorld() != null ? a.lieu().getWorld().getName() : "");
        return m;
    }

    // ── Publication et retrait d'une annonce ────────────────────────────────

    /**
     * Places the plot's listing where the player stands (spot checked before by {@link Listings#spotProblem}).
     * A listing nobody took yet is replaced: Lands removes the former sign or hologram itself. Returns an error
     * message, or null when Lands accepted the sign.
     */
    String publier(Player p, Area zone, Listings.Draft d) {
        RentalBase offre = zone.getRentalOffer();
        if (offre != null && offre.getType() != RentalType.RENT && offre.getType() != RentalType.SELL) {
            return Lang.t("gestion.la-parcelle") + Fmt.v(zone.getName()) + Lang.t("gestion.est-occupee-mets-d-abord");
        }
        Block b = p.getLocation().getBlock();
        // Lands' sign format: "<period> [max]" in days, max left out = no limit (its upper bound)
        List<String> lignes = d.rent()
                ? List.of("Lands", "rent " + zone.getName(), d.periodDays() + "d" + (d.maxDays() > 0 ? " " + d.maxDays() + "d" : ""),
                String.valueOf(d.price()))
                : List.of("Lands", "sell " + zone.getName(), String.valueOf(d.price()), "");
        b.setType(Material.OAK_SIGN, false);
        if (b.getBlockData() instanceof Rotatable rot) {
            rot.setRotation(p.getFacing().getOppositeFace());
            b.setBlockData(rot, false);
        }
        List<Component> comps = new ArrayList<>();
        for (String l : lignes) comps.add(Component.text(l));
        SignChangeEvent ev = new SignChangeEvent(b, p, comps, Side.FRONT);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) {
            if (b.getType() == Material.OAK_SIGN) b.setType(Material.AIR, false);
            return Lang.t("gestion.lands-a-refuse-l-annonce");
        }
        if (b.getState() instanceof Sign sign) {
            List<Component> finales = ev.lines();
            for (int i = 0; i < 4 && i < finales.size(); i++) sign.getSide(Side.FRONT).line(i, finales.get(i));
            sign.update(true, false);
        }
        invalider();
        return null;
    }

    /** Retire l'annonce disponible d'une parcelle (pancarte ou hologramme). */
    boolean retirerAnnonce(Area zone) {
        RentalBase o = zone.getRentalOffer();
        if (o == null || (o.getType() != RentalType.RENT && o.getType() != RentalType.SELL)) return false;
        LandBlock b = o.getBlock();
        if (b == null) return false;
        boolean ok = b.remove(() -> { }, true);
        invalider();
        return ok;
    }

    /** Met fin à une location ou une vente en cours (Lands verse la compensation prévue). */
    boolean terminer(Area zone) {
        RentalBase o = zone.getRentalOffer();
        if (!(o instanceof AcceptedRentalOfferBase acc)) return false;
        boolean ok = acc.expire();
        invalider();
        return ok;
    }

    void definirSpawn(Area zone, Location loc) {
        zone.setSpawn(UnloadedPosition.of(loc));
    }

    static boolean renommer(Area zone, String nom) {
        return zone instanceof LandArea la && la.setName(nom);
    }
}
