package fr.influencraft.fluxreferral.bdd;

import fr.influencraft.flux.api.data.Database;

/**
 * Tables of the referral system in Flux's database, named {@code flux_referral_<category>} (before 2026-10-31:
 * {@code parr_<French name>}, renamed at start by {@link GestionnaireBDD}). Columns keep their former names.
 */
public final class Tables {

    public static final String PLAYERS = Database.table("flux_referral_players");
    public static final String EVENTS = Database.table("flux_referral_events");
    public static final String TIERS = Database.table("flux_referral_tiers");
    public static final String INVITATIONS = Database.table("flux_referral_invitations");
    public static final String DISCOVERIES = Database.table("flux_referral_discoveries");

    private Tables() {
    }
}
