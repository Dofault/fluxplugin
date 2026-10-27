package fr.influencraft.fluxreferral.service;

import fr.influencraft.flux.api.reward.ClaimResult;
import fr.influencraft.flux.api.reward.PendingReward;
import fr.influencraft.flux.api.reward.RewardSource;
import fr.influencraft.fluxreferral.FluxReferralPlugin;
import fr.influencraft.fluxreferral.modele.Profil;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Referral rewards waiting for a player (welcome reward as a referred player, tier bonuses, one reward per validated
 * referred player) as the {@code referral} source of the suite's rewards. Read from the cached profile, claimed
 * through {@link ServiceRecuperation} (one free slot per reward, nothing dropped).
 */
public final class ReferralRewards implements RewardSource {

    private final FluxReferralPlugin plugin;
    private final ServiceProfil profiles;
    private final ServiceRecuperation claims;

    public ReferralRewards(FluxReferralPlugin plugin, ServiceProfil profiles, ServiceRecuperation claims) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.claims = claims;
    }

    @Override
    public String id() {
        return "referral";
    }

    @Override
    public CompletableFuture<List<PendingReward>> pending(Player player) {
        List<PendingReward> out = new ArrayList<>();
        Profil p = profiles.obtenir(player.getUniqueId());
        if (p == null) return CompletableFuture.completedFuture(out);
        String icon = plugin.getConfig().getString("recompenses.icone", "PLAYER_HEAD");
        if (p.filleulRecompenseEnAttente()) out.add(new PendingReward(id(), label("accueil"), icon, 1, 1));
        for (int tier : p.paliersObtenus()) {
            if (!p.paliersRecuperes().contains(tier)) out.add(new PendingReward(id(), label("palier").replace("{seuil}", String.valueOf(tier)), icon, 1, 1));
        }
        int perReferral = Math.max(0, p.nbFilleuls() - p.filleulsRecompensesDonnees());
        if (perReferral > 0) out.add(new PendingReward(id(), label("filleul"), icon, perReferral, perReferral));
        return CompletableFuture.completedFuture(out);
    }

    private String label(String kind) {
        String own = plugin.getGestionnaireLangue().getMessageBrut("recompense-libelle-" + kind, java.util.Map.of());
        if (own != null && !own.isBlank() && !own.contains("recompense-libelle-")) return own;
        return switch (kind) {
            case "accueil" -> "&fRécompense d'accueil (parrainage)";
            case "palier" -> "&fBonus de parrainage : {seuil} filleuls";
            default -> "&fRécompense de filleul";
        };
    }

    @Override
    public CompletableFuture<ClaimResult> claim(Player player, int freeSlots) {
        CompletableFuture<ClaimResult> result = new CompletableFuture<>();
        claims.recuperer(player, r -> result.complete(new ClaimResult(r.total(), r.inventairePlein() ? 1 : 0)));
        return result;
    }
}
