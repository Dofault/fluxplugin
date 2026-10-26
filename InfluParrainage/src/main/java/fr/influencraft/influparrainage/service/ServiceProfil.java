package fr.influencraft.influparrainage.service;

import fr.influencraft.influparrainage.InfluParrainagePlugin;
import fr.influencraft.influparrainage.bdd.DepotInvitation;
import fr.influencraft.influparrainage.bdd.DepotJoueur;
import fr.influencraft.influparrainage.bdd.DepotPalier;
import fr.influencraft.influparrainage.modele.Invitation;
import fr.influencraft.influparrainage.modele.JoueurParrainage;
import fr.influencraft.influparrainage.modele.Profil;
import org.bukkit.Bukkit;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Cache des profils de parrainage des joueurs connectés. Les placeholders (menu FluxMenu
 * rafraîchi chaque seconde) lisent uniquement ce cache ; il est rechargé en asynchrone à la
 * connexion, après chaque événement de parrainage et à chaque passage de la tâche de vérification.
 */
public class ServiceProfil {

    private static final int NB_RECENTS = 5;

    private final InfluParrainagePlugin plugin;
    private final DepotJoueur depotJoueur;
    private final DepotPalier depotPalier;
    private final DepotInvitation depotInvitation;
    private final ServiceAdresse serviceAdresse;

    private final ConcurrentHashMap<UUID, Profil> cache = new ConcurrentHashMap<>();
    private final Set<UUID> enChargement = ConcurrentHashMap.newKeySet();

    public ServiceProfil(InfluParrainagePlugin plugin, DepotJoueur depotJoueur, DepotPalier depotPalier,
                         DepotInvitation depotInvitation, ServiceAdresse serviceAdresse) {
        this.plugin = plugin;
        this.depotJoueur = depotJoueur;
        this.depotPalier = depotPalier;
        this.depotInvitation = depotInvitation;
        this.serviceAdresse = serviceAdresse;
    }

    /** Profil en cache, ou null s'il n'est pas encore chargé (un chargement est alors lancé). */
    public Profil obtenir(UUID uuid) {
        Profil profil = cache.get(uuid);
        if (profil == null) demanderChargement(uuid);
        return profil;
    }

    public void demanderChargement(UUID uuid) {
        if (Bukkit.getPlayer(uuid) == null || !enChargement.add(uuid)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                recharger(uuid);
            } finally {
                enChargement.remove(uuid);
            }
        });
    }

    /**
     * Relit le profil en base. À appeler depuis un thread asynchrone.
     * Les joueurs hors ligne ne sont pas mis en cache.
     */
    public void recharger(UUID uuid) {
        if (Bukkit.getPlayer(uuid) == null) {
            cache.remove(uuid);
            return;
        }
        try {
            Optional<JoueurParrainage> opt = depotJoueur.trouverParUUID(uuid);
            if (opt.isEmpty()) return;
            JoueurParrainage j = opt.get();

            String parrain = null;
            if (j.parrainUuid() != null) {
                parrain = depotJoueur.trouverParUUID(j.parrainUuid()).map(JoueurParrainage::pseudo).orElse(null);
            }
            String parrainAttente = null;
            long tempsJoueAttente = 0;
            Optional<Invitation> invitation = depotInvitation.trouver(uuid);
            if (invitation.isPresent()) {
                parrainAttente = depotJoueur.trouverParUUID(invitation.get().parrainUuid())
                    .map(JoueurParrainage::pseudo).orElse(null);
                tempsJoueAttente = invitation.get().tempsJoue();
            }
            int rang = j.nbFilleuls() > 0 ? depotJoueur.obtenirRang(uuid) : 0;

            Set<Integer> paliersObtenus = depotPalier.paliersObtenus(uuid);
            Set<Integer> paliersEnAttente = depotPalier.paliersEnAttente(uuid);
            Set<Integer> paliersRecuperes = new java.util.HashSet<>(paliersObtenus);
            paliersRecuperes.removeAll(paliersEnAttente);

            Profil profil = new Profil(
                j.code(),
                serviceAdresse.adressePour(j.pseudo(), j.code()),
                j.nbFilleuls(),
                depotJoueur.obtenirFilleulsRecompensesDonnees(uuid),
                rang,
                parrain,
                parrainAttente,
                tempsJoueAttente,
                Set.copyOf(paliersObtenus),
                Set.copyOf(paliersRecuperes),
                j.parrainUuid() != null && !depotJoueur.obtenirFilleulRecompenseRecuperee(uuid),
                List.copyOf(depotInvitation.listerParParrain(uuid)),
                List.copyOf(depotJoueur.listerFilleulsRecents(uuid, NB_RECENTS))
            );
            // Le joueur a pu se déconnecter pendant la lecture
            if (Bukkit.getPlayer(uuid) != null) cache.put(uuid, profil);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Lecture du profil de parrainage impossible pour " + uuid, e);
        }
    }

    public void retirer(UUID uuid) {
        cache.remove(uuid);
    }

    public void vider() {
        cache.clear();
    }
}
