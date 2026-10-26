package fr.influencraft.fluxreferral.service;

import fr.influencraft.fluxreferral.bdd.DepotJoueur;
import fr.influencraft.fluxreferral.configuration.GestionnaireConfig;
import fr.influencraft.fluxreferral.modele.JoueurParrainage;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Adresse personnelle de parrainage : {@code <pseudo>.<domaine>} (ex : dofault.play.influencraft.fr).
 *
 * <p>Le client Minecraft envoie l'adresse tapée dans le paquet de handshake ; Velocity (forwarding
 * moderne) la transmet telle quelle au serveur, qui la lit via {@code Player#getVirtualHost()}.
 * Les pseudos peuvent contenir « _ », interdit dans un nom de domaine : il devient « - »
 * (aucun pseudo Minecraft ne contient de « - », la conversion inverse est donc sans ambiguïté).
 * Un pseudo qui donnerait un libellé invalide (« _abc » → « -abc ») utilise le code à la place.</p>
 */
public class ServiceAdresse {

    /** Libellé DNS valide : lettres, chiffres, tirets, ni au début ni à la fin. */
    private static final Pattern LIBELLE = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private final DepotJoueur depotJoueur;
    private final GestionnaireConfig config;

    public ServiceAdresse(DepotJoueur depotJoueur, GestionnaireConfig config) {
        this.depotJoueur = depotJoueur;
        this.config = config;
    }

    /** Adresse à donner aux amis, construite depuis le pseudo (ou le code en secours). */
    public String adressePour(String pseudo, String code) {
        String libelle = pseudo.toLowerCase(Locale.ROOT).replace('_', '-');
        if (!LIBELLE.matcher(libelle).matches()) {
            libelle = code.toLowerCase(Locale.ROOT);
        }
        return libelle + "." + config.getDomaine();
    }

    /**
     * Extrait le libellé personnel d'une adresse de connexion.
     * {@code dofault.play.influencraft.fr} → {@code dofault} ; l'adresse nue, une IP ou un autre
     * domaine → vide.
     */
    public Optional<String> extraireLibelle(String hote) {
        if (hote == null || !config.isAdresseActive()) return Optional.empty();
        String h = hote.toLowerCase(Locale.ROOT).trim();
        // Marqueurs Forge/FML éventuels (« \0FML\0 ») et point final d'un FQDN
        int zero = h.indexOf('\0');
        if (zero >= 0) h = h.substring(0, zero);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);

        String suffixe = "." + config.getDomaine();
        if (!h.endsWith(suffixe)) return Optional.empty();
        String libelle = h.substring(0, h.length() - suffixe.length());
        // Un seul niveau : « www.dofault.play... » n'est pas une adresse de parrainage
        if (!LIBELLE.matcher(libelle).matches()) return Optional.empty();
        return Optional.of(libelle);
    }

    /** Retrouve le parrain désigné par un libellé : pseudo d'abord, puis code de parrainage. */
    public Optional<JoueurParrainage> resoudre(String libelle) throws SQLException {
        Optional<JoueurParrainage> parPseudo = depotJoueur.trouverParPseudo(libelle.replace('-', '_'));
        if (parPseudo.isPresent()) return parPseudo;
        return depotJoueur.trouverParCode(libelle.toUpperCase(Locale.ROOT));
    }
}
