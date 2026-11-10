package fr.influencraft.fluxlands;

import fr.influencraft.flux.api.dialog.Dialogs;
import me.angeschossen.lands.api.land.Area;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.land.rental.offer.base.RentalBase;
import me.angeschossen.lands.api.land.rental.offer.types.RentOffer;
import me.angeschossen.lands.api.land.rental.offer.types.RentalType;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts a plot on the Agency (rent or sale) with one Paper dialog instead of Lands' sign: the mayor fills the price and,
 * for a rent, the payment period and the longest lease, then the listing is placed where they stand (Lands turns the
 * sign into a clickable hologram). Filled far from the plot, the listing waits as a draft and a chat link places it.
 * <p>
 * Lands 9.1.1 rules (read in its sign handler): the offer block must stand in the town but outside the plot; rent line
 * {@code <period> [max]} in minutes, hours or days, max at least the period, max left out = Lands' upper bound.
 */
final class Listings {

    /** A listing being prepared; {@code maxDays} 0 = no limit. */
    record Draft(String plot, boolean rent, long price, int periodDays, int maxDays) {
    }

    /** Payment periods and longest leases offered, in days (0 = no limit). */
    private static final int[] PERIODS = {1, 2, 3, 7, 14, 30};
    private static final int[] LEASES = {7, 14, 30, 60, 90, 180, 365, 0};
    /** Lands' upper bound of a rent time, in minutes (also its value when the max is left out). */
    static final int LANDS_MAX_MINUTES = 1_491_308;

    private final FluxLands plugin;

    Listings(FluxLands plugin) {
        this.plugin = plugin;
    }

    /** Opens the rent or sale window of a plot, prefilled with its current offer, the pending draft or defaults. */
    void open(Player p, Land land, Area zone, boolean rent) {
        RentalBase offer = zone.getRentalOffer();
        if (offer != null && (offer.getType() == RentalType.RENTED || offer.getType() == RentalType.SOLD)) {
            Fmt.erreur(p, "La parcelle " + Fmt.v(zone.getName()) + " est occupée.", "Mets d'abord fin à l'occupation.");
            return;
        }
        if (!Gestion.delimitee(zone)) {
            Fmt.erreur(p, "La parcelle " + Fmt.v(zone.getName()) + " n'est pas délimitée.", "Délimite-la d'abord avec ta sélection.");
            return;
        }
        Draft pending = plugin.vues().etat(p).brouillon;
        Draft initial;
        if (offer instanceof RentOffer r && rent) {
            initial = new Draft(zone.getName(), true, (long) r.getCost(), Math.max(1, r.getMinutes() / 1440),
                    r.getMaxMinutes() >= LANDS_MAX_MINUTES ? 0 : Math.max(1, r.getMaxMinutes() / 1440));
        } else if (offer != null && offer.getType() == RentalType.SELL && !rent) {
            initial = new Draft(zone.getName(), false, (long) offer.getCost(), 0, 0);
        } else if (pending != null && pending.plot().equalsIgnoreCase(zone.getName()) && pending.rent() == rent) {
            initial = pending;
        } else {
            initial = new Draft(zone.getName(), rent, rent ? 5000 : 50000, 7, 30);
        }
        form(p, land, zone, initial);
    }

    private void form(Player p, Land land, Area zone, Draft d) {
        boolean here = spotProblem(p, land, zone) == null;
        Dialogs.Form form = Dialogs.form(p, (d.rent() ? "Louer " : "Vendre ") + zone.getName());
        if (d.rent()) {
            form.body("Le locataire paie le loyer à chaque période et peut renouveler jusqu'à la durée maximale du bail.")
                    .body("Le loyer t'est versé à chaque paiement.");
        } else {
            form.body("L'acheteur devient propriétaire de la parcelle, qui reste dans ta ville.")
                    .body("Le prix t'est versé à l'achat.");
        }
        form.body(here ? "&#2ECF7BTu es bien placé : l'hologramme de l'annonce apparaîtra ici."
                : "&#FFD94DL'hologramme apparaîtra là où tu te tiendras : dans ta ville, juste à côté de la parcelle"
                + " (dans la rue devant, par exemple). Tu pourras le poser après avoir validé.");
        // wrong price or lease: Flux opens the window again with the error and what was typed
        form.amount("prix", d.rent() ? "Loyer par période (BitBlock, ex : 5000, 10k)" : "Prix de vente (BitBlock, ex : 50000, 1,5M)",
                d.price(), 1, 1_000_000_000L);
        if (d.rent()) {
            form.choice("periode", "Période de paiement", options(PERIODS, d.periodDays()))
                    .choice("bail", "Durée maximale du bail", options(LEASES, d.maxDays()))
                    .check(a -> {
                        int lease = days(a.text("bail"), d.maxDays());
                        return lease > 0 && lease < days(a.text("periode"), d.periodDays())
                                ? "La durée maximale du bail doit être au moins égale à la période de paiement." : null;
                    });
        }
        form.buttons(here ? "Publier ici" : "Valider", "Annuler")
                .onCancel(pl -> plugin.ouvrirMenu(pl, "parcelle"))
                .onSubmit((pl, a) -> {
                    int period = d.rent() ? days(a.text("periode"), d.periodDays()) : 0;
                    int lease = d.rent() ? days(a.text("bail"), d.maxDays()) : 0;
                    Draft next = new Draft(zone.getName(), d.rent(), a.amount("prix"), period, lease);
                    plugin.vues().etat(pl).brouillon = next;
                    place(pl, land);
                })
                .open();
    }

    /**
     * Places the pending draft where the player stands. Wrong spot: the draft stays and a chat link lets the player
     * place it once they reached the right spot.
     */
    void place(Player p, Land land) {
        Vues.Etat e = plugin.vues().etat(p);
        Draft d = e.brouillon;
        if (d == null) {
            Fmt.erreur(p, "Aucune annonce en attente.", "Prépare-la depuis le menu de la parcelle.");
            return;
        }
        Area zone = plugin.gestion().parcelle(land, d.plot());
        if (zone == null) {
            e.brouillon = null;
            Fmt.erreur(p, "La parcelle " + Fmt.v(d.plot()) + " n'existe plus.");
            return;
        }
        String problem = spotProblem(p, land, zone);
        if (problem != null) {
            p.closeInventory();
            Fmt.envoyer(p, problem);
            link(p, zone);
            return;
        }
        String error = plugin.gestion().publier(p, zone, d);
        if (error != null) {
            Fmt.envoyer(p, error);
            return;
        }
        p.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            RentalBase o = zone.getRentalOffer();
            if (o == null || o.getType() != (d.rent() ? RentalType.RENT : RentalType.SELL)) return;
            if (e.brouillon == d) e.brouillon = null;
            Fmt.envoyer(p, "<t:succes>Annonce publiée :</t> " + Fmt.v(zone.getName()) + (d.rent() ? " à louer "
                    + Fmt.bitblock(d.price()) + " par " + Fmt.v(Gestion.duree(d.periodDays() * 1440)) + "." : " à vendre "
                    + Fmt.bitblock(d.price()) + ".") + " Elle apparaît dans l'Agence " + Fmt.cmd("/agence") + ".");
        }, 10L);
    }

    /** Drops the pending draft. */
    void abandon(Player p) {
        plugin.vues().etat(p).brouillon = null;
        Fmt.envoyer(p, "Annonce en attente abandonnée.");
    }

    /**
     * Why the listing can't be placed where the player stands, or null. Mirrors Lands 9.1.1: the offer block must be
     * in the town and outside the plot; the sign needs a free block on solid ground.
     */
    String spotProblem(Player p, Land land, Area zone) {
        Land here = plugin.villes().a(p.getLocation());
        if (here == null || !Villes.id(here).equals(Villes.id(land))) {
            return "<t:erreur>Tu n'es pas dans</t> " + Fmt.ville(Gestion.nu(land.getName())) + "<t:erreur>.</t> Rends-toi juste à côté de la parcelle " + Fmt.v(zone.getName()) + " : l'hologramme se pose là où tu te tiens.";
        }
        Area area = land.getArea(p.getLocation());
        if (area != null && !area.isDefault() && area.getName().equalsIgnoreCase(zone.getName())) {
            return "<t:erreur>Sors de la parcelle</t> " + Fmt.v(zone.getName()) + "<t:erreur>.</t> Lands place l'annonce juste à côté (dans la rue devant, par exemple), pas dedans.";
        }
        Block b = p.getLocation().getBlock();
        if (!b.getType().isAir() || !b.getRelative(BlockFace.DOWN).getType().isSolid()) {
            return "<t:erreur>Il faut un sol plein, sans bloc à tes pieds.</t> L'hologramme s'affichera là où tu te tiens.";
        }
        return null;
    }

    private void link(Player p, Area zone) {
        Fmt.action(p, "Ton annonce pour " + Fmt.v(zone.getName()) + " est prête. Une fois au bon endroit, clique ou tape "
                        + Fmt.cmd("/v annonce") + " :",
                "Poser l'annonce ici", plugin.commande().label() + " annonce publier");
    }

    /** Labels of a day list, the current value first (a Paper drop-down always starts on its first option). */
    private static List<String> options(int[] values, int current) {
        List<String> l = new ArrayList<>();
        l.add(label(current));
        for (int v : values) if (v != current) l.add(label(v));
        return l;
    }

    private static String label(int days) {
        return days <= 0 ? "Sans limite" : days == 1 ? "1 jour" : days + " jours";
    }

    private static int days(String label, int fallback) {
        if (label.isEmpty()) return fallback;
        if (label.equals("Sans limite")) return 0;
        try {
            return Integer.parseInt(label.split(" ")[0]);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
