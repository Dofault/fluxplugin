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
            Fmt.erreur(p, Lang.t("listings.la-parcelle") + Fmt.v(zone.getName()) + Lang.t("listings.est-occupee"), Lang.t("listings.mets-d-abord-fin-a"));
            return;
        }
        if (!Gestion.delimitee(zone)) {
            Fmt.erreur(p, Lang.t("listings.la-parcelle") + Fmt.v(zone.getName()) + Lang.t("listings.n-est-pas-delimitee"), Lang.t("listings.delimite-la-d-abord-avec"));
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
            form.body(Lang.t("listings.le-locataire-paie-le-loyer"))
                    .body(Lang.t("listings.le-loyer-t-est-verse"));
        } else {
            form.body(Lang.t("listings.l-acheteur-devient-proprietaire-de"))
                    .body(Lang.t("listings.le-prix-t-est-verse"));
        }
        form.body(here ? Lang.t("listings.tu-es-bien-place-l")
                : Lang.t("listings.l-hologramme-apparaitra-la-ou")
                + Lang.t("listings.dans-la-rue-devant-par"));
        // wrong price or lease: Flux opens the window again with the error and what was typed
        form.amount("prix", d.rent() ? Lang.t("listings.loyer-par-periode-bitblock-ex") : Lang.t("listings.prix-de-vente-bitblock-ex"),
                d.price(), 1, 1_000_000_000L);
        if (d.rent()) {
            form.choice("periode", Lang.t("listings.periode-de-paiement"), options(PERIODS, d.periodDays()))
                    .choice("bail", Lang.t("listings.duree-maximale-du-bail"), options(LEASES, d.maxDays()))
                    .check(a -> {
                        int lease = days(a.text("bail"), d.maxDays());
                        return lease > 0 && lease < days(a.text("periode"), d.periodDays())
                                ? Lang.t("listings.la-duree-maximale-du-bail") : null;
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
            Fmt.erreur(p, Lang.t("listings.aucune-annonce-en-attente"), Lang.t("listings.prepare-la-depuis-le-menu"));
            return;
        }
        Area zone = plugin.gestion().parcelle(land, d.plot());
        if (zone == null) {
            e.brouillon = null;
            Fmt.erreur(p, Lang.t("listings.la-parcelle") + Fmt.v(d.plot()) + Lang.t("listings.n-existe-plus"));
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
            Fmt.envoyer(p, Lang.t("listings.annonce-publiee") + Fmt.v(zone.getName()) + (d.rent() ? Lang.t("listings.a-louer")
                    + Fmt.bitblock(d.price()) + " par " + Fmt.v(Gestion.duree(d.periodDays() * 1440)) + "." : Lang.t("listings.a-vendre")
                    + Fmt.bitblock(d.price()) + ".") + Lang.t("listings.elle-apparait-dans-l-agence") + Fmt.cmd("/agence") + ".");
        }, 10L);
    }

    /** Drops the pending draft. */
    void abandon(Player p) {
        plugin.vues().etat(p).brouillon = null;
        Fmt.envoyer(p, Lang.t("listings.annonce-en-attente-abandonnee"));
    }

    /**
     * Why the listing can't be placed where the player stands, or null. Mirrors Lands 9.1.1: the offer block must be
     * in the town and outside the plot; the sign needs a free block on solid ground.
     */
    String spotProblem(Player p, Land land, Area zone) {
        Land here = plugin.villes().a(p.getLocation());
        if (here == null || !Villes.id(here).equals(Villes.id(land))) {
            return Lang.t("listings.tu-n-es-pas-dans") + Fmt.ville(Gestion.nu(land.getName())) + Lang.t("listings.rends-toi-juste-a-cote") + Fmt.v(zone.getName()) + Lang.t("listings.l-hologramme-se-pose-la");
        }
        Area area = land.getArea(p.getLocation());
        if (area != null && !area.isDefault() && area.getName().equalsIgnoreCase(zone.getName())) {
            return Lang.t("listings.sors-de-la-parcelle") + Fmt.v(zone.getName()) + Lang.t("listings.lands-place-l-annonce-juste");
        }
        Block b = p.getLocation().getBlock();
        if (!b.getType().isAir() || !b.getRelative(BlockFace.DOWN).getType().isSolid()) {
            return Lang.t("listings.il-faut-un-sol-plein");
        }
        return null;
    }

    private void link(Player p, Area zone) {
        Fmt.action(p, Lang.t("listings.ton-annonce-pour") + Fmt.v(zone.getName()) + Lang.t("listings.est-prete-une-fois-au")
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
