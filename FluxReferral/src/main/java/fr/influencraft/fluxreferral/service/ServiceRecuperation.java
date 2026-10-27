package fr.influencraft.fluxreferral.service;

import fr.influencraft.flux.api.reward.ClaimResult;
import fr.influencraft.flux.api.reward.Reward;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.fluxreferral.FluxReferralPlugin;
import fr.influencraft.fluxreferral.bdd.DepotJoueur;
import fr.influencraft.fluxreferral.bdd.DepotPalier;
import fr.influencraft.fluxreferral.configuration.GestionnaireConfig;
import fr.influencraft.fluxreferral.modele.JoueurParrainage;
import fr.influencraft.fluxreferral.modele.Palier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Referral rewards go through Flux's reward manager: every reward earned (welcome reward of a referred player, tier
 * bonus, one reward per validated referred player) is handed to Flux with its own key, then acknowledged here, and
 * claimed with every other reward ({@code /rewards}) or from the referral path of the menu
 * ({@code /parrainage recuperer [filleul|palier <n>]}).
 * <p>
 * Keys: {@code referral:welcome}, {@code referral:tier:<referred players>}, {@code referral:referee:<n>} (n-th
 * referred player). Given to Flux <b>before</b> being acknowledged: after a crash in between, the next transfer gives
 * the same keys again and Flux ignores them.
 * <p>
 * Transfer: when the player joins and before every claim (Flux {@link Rewards#collector}), and on
 * {@code /parrainage recuperer}.
 */
public class ServiceRecuperation {

    public static final String SOURCE = "referral";

    private final FluxReferralPlugin plugin;
    private final DepotJoueur depotJoueur;
    private final DepotPalier depotPalier;
    private final GestionnaireConfig config;
    private final ServiceProfil serviceProfil;

    /** One transfer at a time per player. */
    private final Set<UUID> enCours = ConcurrentHashMap.newKeySet();

    public ServiceRecuperation(FluxReferralPlugin plugin, DepotJoueur depotJoueur, DepotPalier depotPalier,
                               GestionnaireConfig config, ServiceProfil serviceProfil) {
        this.plugin = plugin;
        this.depotJoueur = depotJoueur;
        this.depotPalier = depotPalier;
        this.config = config;
        this.serviceProfil = serviceProfil;
    }

    /** Summary of a claim, for the message to the player. */
    public record Resultat(int total, boolean inventairePlein, boolean dejaEnCours) {
        static Resultat of(ClaimResult r) {
            return new Resultat(r.delivered(), r.remaining() > 0, false);
        }
    }

    public static String cleFilleul(int n) {
        return SOURCE + ":referee:" + n;
    }

    public static String clePalier(int seuil) {
        return SOURCE + ":tier:" + seuil;
    }

    public static final String CLE_ACCUEIL = SOURCE + ":welcome";

    // ====================================================================
    // Claims (command and menu path)
    // ====================================================================

    /** Everything that fits, referral rewards only. */
    public void recuperer(Player joueur, Consumer<Resultat> callback) {
        transferer(joueur, () -> callback.accept(Resultat.of(Rewards.claim(joueur, SOURCE, false))));
    }

    /** The next referred-player reward waiting (oldest first). */
    public void recupererUnFilleul(Player joueur, Consumer<Resultat> callback) {
        transferer(joueur, () -> {
            String cle = Rewards.pending(joueur.getUniqueId()).stream()
                    .map(r -> r.reward().key())
                    .filter(k -> k != null && k.startsWith(SOURCE + ":referee:"))
                    .findFirst().orElse(null);
            callback.accept(cle == null ? new Resultat(0, false, false) : Resultat.of(Rewards.claimKey(joueur, cle, false)));
        });
    }

    /** The bonus of one tier. */
    public void recupererPalier(Player joueur, int seuil, Consumer<Resultat> callback) {
        transferer(joueur, () -> callback.accept(Resultat.of(Rewards.claimKey(joueur, clePalier(seuil), false))));
    }

    // ====================================================================
    // Transfer into Flux
    // ====================================================================

    /** Hands every earned referral reward to Flux; {@code then} runs on the main thread afterwards. */
    public void transferer(Player joueur, Runnable then) {
        UUID uuid = joueur.getUniqueId();
        if (!enCours.add(uuid)) {
            if (then != null) then.run();
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var opt = depotJoueur.trouverParUUID(uuid);
                if (opt.isEmpty()) {
                    finir(uuid, false, then);
                    return;
                }
                JoueurParrainage j = opt.get();
                int dejaDonnes = depotJoueur.obtenirFilleulsRecompensesDonnees(uuid);
                int filleulsEnAttente = Math.max(0, j.nbFilleuls() - dejaDonnes);
                boolean accueil = j.parrainUuid() != null && !depotJoueur.obtenirFilleulRecompenseRecuperee(uuid);
                List<Integer> paliers = new ArrayList<>(depotPalier.paliersEnAttente(uuid));
                paliers.sort(Comparator.naturalOrder());
                boolean change = false;

                if (accueil && donner(joueur, CLE_ACCUEIL, libelle("accueil", 0), config.getCommandesFilleul())) {
                    depotJoueur.marquerFilleulRecompenseRecuperee(uuid);
                    change = true;
                }
                List<Palier> definis = config.getPaliers();
                for (int seuil : paliers) {
                    Palier palier = definis.stream().filter(p -> p.filleuls() == seuil).findFirst().orElse(null);
                    if (palier == null) continue; // tier removed from the config meanwhile
                    if (donner(joueur, clePalier(seuil), libelle("palier", seuil), palier.commandes())) {
                        depotPalier.marquerRecupere(uuid, seuil);
                        change = true;
                    }
                }
                for (int i = 1; i <= filleulsEnAttente; i++) {
                    if (!donner(joueur, cleFilleul(dejaDonnes + i), libelle("filleul", 0), config.getCommandesParrain())) break;
                    depotJoueur.incrementerFilleulsRecompensesDonnees(uuid, 1);
                    change = true;
                }
                finir(uuid, change, then);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur BDD pendant le transfert des récompenses de " + joueur.getName(), e);
                finir(uuid, false, then);
            }
        });
    }

    /** Kept by Flux now, or already there (transfer replayed after a crash). */
    private boolean donner(Player joueur, String cle, String libelle, List<String> commandes) {
        List<String> converties = commandes.stream().map(c -> c.replace("{joueur}", "%player%")).toList();
        Reward reward = Reward.of(SOURCE, libelle).key(cle).slots(1).commands(converties)
                .icon(plugin.getConfig().getString("recompenses.icone", "PLAYER_HEAD"));
        return Rewards.give(joueur, reward) || Rewards.given(joueur.getUniqueId(), cle);
    }

    private String libelle(String type, int seuil) {
        String own = plugin.getGestionnaireLangue().getMessageBrut("recompense-libelle-" + type, java.util.Map.of());
        String label = own != null && !own.isBlank() && !own.contains("recompense-libelle-") ? own : switch (type) {
            case "accueil" -> "&fRécompense d'accueil (parrainage)";
            case "palier" -> "&fBonus de parrainage : {seuil} filleuls";
            default -> "&fRécompense de filleul";
        };
        return label.replace("{seuil}", String.valueOf(seuil));
    }

    private void finir(UUID uuid, boolean change, Runnable then) {
        if (change) serviceProfil.recharger(uuid);
        enCours.remove(uuid);
        if (then != null) Bukkit.getScheduler().runTask(plugin, then);
    }
}
