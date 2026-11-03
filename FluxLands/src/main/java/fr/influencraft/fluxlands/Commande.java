package fr.influencraft.fluxlands;

import fr.influencraft.flux.api.dialog.Dialogs;
import me.angeschossen.lands.api.flags.type.NaturalFlag;
import me.angeschossen.lands.api.flags.type.RoleFlag;
import me.angeschossen.lands.api.land.Area;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.nation.Nation;
import me.angeschossen.lands.api.player.LandPlayer;
import me.angeschossen.lands.api.player.Selection;
import me.angeschossen.lands.api.role.Role;
import me.angeschossen.lands.api.role.enums.RoleType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /fluxlands} : navigation des menus (appelée par les boutons Flux, lancée par le
 * joueur) et administration.
 * <pre>
 *   ouvrir ville|annuaire|commerce|reglages|parcelles|roles|parametres|agence
 *   ouvrir warps|echoppes [toutes|ma|fiche|miennes|ventes|achats]
 *   ouvrir habitants|fiche [ma|ici|courante|&lt;ville&gt;]
 *   vue &lt;type&gt;                       liste suivie par un menu ouvert par sa propre commande
 *   page|filtre|tri|budget|taille +|-  recherche | effacer
 *   clic &lt;n&gt; gauche|droit|epingler   ligne n de la page affichée
 *   vitrine &lt;n&gt; gauche|droit          warp épinglé n de la ville consultée
 *   top &lt;n&gt;                          échoppe n du classement de ma ville
 *   droit                              fenêtre du taux du droit de place de ma ville (maire)
 *   banque deposer|retirer [menu]      fenêtre du montant, puis retour au menu (ville par défaut)
 *   parcelle ici|aller|spawn|renommer|location|vente|modifier|retirer|terminer|habitants|nouvelle|delimiter|supprimer
 *   annonce publier|abandonner         pose l'annonce en attente là où se tient le joueur, ou l'oublie
 *   reglages renommer|accueil|spawn
 *   natif [arguments]                  ouvre un menu natif de Lands (sans interception)
 *   natifpays [arguments]              idem pour /nations
 *   retour                             menu d'origine de la liste affichée (option depuis:&lt;menu&gt; de ouvrir)
 *   ouvrir pays|pays_annuaire | pays_fiche [mon|courante|&lt;pays&gt;] | pays_villes [mon|fiche]
 *   pays spawn mon|fiche | inviter | capitale | renommer | creer | quitter | chat | taxes | taxe | effets | effet &lt;effet&gt;
 *   niveau ville|pays|monter|retour   objectifs du niveau suivant (menu ville_niveau)
 *   role creer                         nouveau rôle de ma ville (maire)
 *   spawn ma|fiche | choisir | saisie creer_ville|creer_warp|inviter
 *   reload | index | taux &lt;ville&gt; &lt;taux&gt;  (fluxlands.admin)
 * </pre>
 */
final class Commande implements TabExecutor {

    private static final List<String> SOUS = List.of("ouvrir", "vue", "page", "filtre", "tri", "budget", "taille", "recherche",
            "effacer", "clic", "vitrine", "top", "droit", "banque", "parcelle", "annonce", "reglages", "natif", "spawn", "choisir",
            "saisie", "retour", "pays", "natifpays", "reload", "index", "taux", "niveau", "chunk", "role");

    private final FluxLands plugin;
    /** Joueurs en train de sélectionner une parcelle : fin de l'attente (ms). */
    private final Map<UUID, Long> attenteSelection = new ConcurrentHashMap<>();
    /** Parcelle à délimiter dès que la sélection est complète ("" = nouvelle parcelle à nommer). */
    private final Map<UUID, String> attenteParcelle = new ConcurrentHashMap<>();

    Commande(FluxLands plugin) {
        this.plugin = plugin;
    }

    /** {@code /<label>} of this command, as named in Flux's commands.yml. */
    String label() {
        return "/" + fr.influencraft.flux.api.command.Commands.label(plugin.getName(), "fluxlands");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] a) {
        if (a.length == 0) {
            sender.sendMessage(label() + " ouvrir|page|filtre|tri|clic|banque|parcelle|annonce... (docs/technique/fluxlands.md)");
            return true;
        }
        String sous = a[0].toLowerCase(Locale.ROOT);
        if (sous.equals("reload") || sous.equals("index") || sous.equals("taux")) {
            if (!sender.hasPermission("fluxlands.admin")) {
                sender.sendMessage("Permission fluxlands.admin requise.");
                return true;
            }
            admin(sender, sous, a);
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Commande de menu : joueur uniquement.");
            return true;
        }
        Vues.Etat e = plugin.vues().etat(p);
        switch (sous) {
            case "ouvrir" -> {
                String depuis = null;
                String option = "";
                for (int i = 2; i < a.length; i++) {
                    if (a[i].toLowerCase(Locale.ROOT).startsWith("depuis:")) depuis = a[i].substring(7).toLowerCase(Locale.ROOT);
                    else if (option.isEmpty()) option = a[i];
                }
                ouvrir(p, e, arg(a, 1), option);
                e.retour = depuis != null ? depuis : retourParDefaut(arg(a, 1), option.toLowerCase(Locale.ROOT));
            }
            case "retour" -> retour(p, e);
            case "pays" -> pays(p, e, arg(a, 1), arg(a, 2));
            case "vue" -> vue(e, arg(a, 1));
            case "page" -> {
                e.page = Math.max(0, e.page + (plus(a) ? 1 : -1));
                e.changer();
            }
            case "filtre" -> {
                int n = Vues.FILTRES.get(e.type).size();
                do {
                    e.filtre = Math.floorMod(e.filtre + (plus(a) ? 1 : -1), n);
                } while (e.filtre == 2 && e.type == Vues.Type.WARPS
                        && plugin.villes().parId(e.fiche) == null);
                e.page = 0;
                e.changer();
            }
            case "tri" -> {
                e.tri = Math.floorMod(e.tri + (plus(a) ? 1 : -1), Vues.TRIS.get(e.type).size());
                e.page = 0;
                e.changer();
            }
            case "budget" -> {
                e.budget = Math.floorMod(e.budget + (plus(a) ? 1 : -1), Vues.BUDGETS.length);
                e.page = 0;
                e.changer();
            }
            case "taille" -> {
                e.taille = Math.floorMod(e.taille + (plus(a) ? 1 : -1), Vues.TAILLES_NOMS.length);
                e.page = 0;
                e.changer();
            }
            case "recherche" -> plugin.saisie().ouvrir(p, "Recherche", "Un nom de ville, de joueur, d'objet, de warp ou de parcelle.",
                    "Rechercher", e.recherche, 32, menuDe(e.type), (pl, txt) -> {
                        e.recherche = txt;
                        e.page = 0;
                        e.changer();
                        plugin.ouvrirMenu(pl, menuDe(e.type));
                    });
            case "effacer" -> {
                e.recherche = "";
                e.page = 0;
                e.changer();
            }
            case "clic" -> clic(p, e, entier(arg(a, 1)), arg(a, 2));
            case "vitrine" -> vitrine(p, e, entier(arg(a, 1)), arg(a, 2));
            case "top" -> top(p, entier(arg(a, 1)));
            case "droit" -> droit(p);
            case "banque" -> banque(p, arg(a, 1), arg(a, 2));
            case "parcelle" -> parcelle(p, e, arg(a, 1));
            case "annonce" -> annonce(p, arg(a, 1));
            case "reglages" -> reglages(p, arg(a, 1));
            case "natif" -> natif(p, "lands", a.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)) : "menu");
            case "natifpays" -> natif(p, "nations", a.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)) : "menu");
            case "spawn" -> spawn(p, e, arg(a, 1));
            case "niveau" -> niveau(p, arg(a, 1));
            case "role" -> {
                if (arg(a, 1).equals("creer")) creerRole(p);
            }
            case "chunk" -> chunk(p, arg(a, 1));
            case "choisir" -> choisir(p);
            case "saisie" -> saisie(p, arg(a, 1));
            default -> sender.sendMessage("Sous-commande inconnue : " + sous);
        }
        return true;
    }

    // ── Ouverture des menus ─────────────────────────────────────────────────

    static String menuDe(Vues.Type t) {
        return switch (t) {
            case VILLES -> "annuaire";
            case WARPS -> "warps";
            case HABITANTS -> "habitants";
            case AGENCE -> "agence";
            case PARCELLES -> "parcelles";
            case ROLES -> "roles";
            case DROITS -> "droits";
            case PARAMETRES -> "parametres";
            case PAYS -> "pays_annuaire";
            case PAYS_VILLES -> "pays_villes";
        };
    }

    /** Menu rejoint par le bouton Retour d'une liste, selon d'où elle a été ouverte. */
    private static String retourParDefaut(String quoi, String option) {
        return switch (quoi) {
            case "warps", "echoppes", "habitants" -> option.equals("fiche") ? "fiche" : "ville";
            case "parcelles", "roles", "parametres" -> "ville";
            case "pays_annuaire" -> "pays";
            case "pays_villes" -> option.equals("fiche") ? "pays_fiche" : "pays";
            default -> "ville";
        };
    }

    private void retour(Player p, Vues.Etat e) {
        String r = e.retour == null || e.retour.isEmpty() ? "ville" : e.retour;
        switch (r) {
            case "fiche" -> ouvrir(p, e, "fiche", "courante");
            case "pays_fiche" -> ouvrir(p, e, "pays_fiche", "courante");
            default -> ouvrir(p, e, r, "");
        }
        e.retour = retourParDefaut(r, "");
    }

    private boolean aUneVille(Player p) {
        if (plugin.villes().maVille(p) != null) return true;
        Fmt.erreur(p, "Tu n'es habitant d'aucune ville.");
        return false;
    }

    private void ouvrir(Player p, Vues.Etat e, String quoi, String brut) {
        String option = brut.toLowerCase(Locale.ROOT);
        switch (quoi) {
            case "ville" -> plugin.ouvrirMenu(p, plugin.villes().maVille(p) != null ? "ville" : "accueil");
            case "commerce", "parcelle" -> {
                if (aUneVille(p)) plugin.ouvrirMenu(p, quoi);
            }
            // the Settings menu is gone (2026-11-01): its buttons are in My town
            case "reglages" -> plugin.ouvrirMenu(p, plugin.villes().maVille(p) != null ? "ville" : "accueil");
            case "territoires", "accueil", "pays_accueil" -> plugin.ouvrirMenu(p, quoi);
            case "annuaire" -> liste(p, e, Vues.Type.VILLES, 0);
            case "agence" -> liste(p, e, Vues.Type.AGENCE, option.equals("ma") ? 3 : 0);
            case "parcelles" -> {
                if (aUneVille(p)) liste(p, e, Vues.Type.PARCELLES, 0);
            }
            case "roles" -> {
                if (aUneVille(p)) liste(p, e, Vues.Type.ROLES, 0);
            }
            case "parametres" -> {
                if (aUneVille(p)) liste(p, e, Vues.Type.PARAMETRES, 0);
            }
            case "warps" -> liste(p, e, Vues.Type.WARPS, switch (option) {
                case "ma" -> 1;
                case "fiche" -> 2;
                case "miens", "miennes" -> 3;
                default -> 0;
            });
            case "echoppes" -> echoppes(p, e, option);
            case "habitants" -> {
                if (!option.equals("fiche")) {
                    Land ma = plugin.villes().maVille(p);
                    if (ma == null) return;
                    e.fiche = Villes.id(ma);
                }
                liste(p, e, Vues.Type.HABITANTS, 0);
            }
            case "fiche" -> {
                Land land = switch (option) {
                    case "", "ma" -> plugin.villes().maVille(p);
                    case "ici" -> plugin.villes().a(p.getLocation());
                    case "courante" -> plugin.villes().parId(e.fiche);
                    default -> plugin.villes().parNom(brut);
                };
                if (land == null) {
                    Fmt.erreur(p, "Aucune ville à afficher.");
                    return;
                }
                fiche(p, e, land);
            }
            case "pays" -> plugin.ouvrirMenu(p, plugin.pays().monPays(p) != null ? "pays" : "pays_accueil");
            case "pays_annuaire" -> liste(p, e, Vues.Type.PAYS, 0);
            case "pays_fiche" -> {
                Nation n = switch (option) {
                    case "", "mon" -> plugin.pays().monPays(p);
                    case "courante" -> plugin.pays().parId(e.paysFiche);
                    default -> plugin.pays().parNom(brut);
                };
                if (n == null) {
                    Fmt.erreur(p, "Aucun pays à afficher.");
                    return;
                }
                e.paysFiche = Pays.id(n);
                plugin.ouvrirMenu(p, "pays_fiche");
            }
            case "pays_villes" -> {
                if (!option.equals("fiche")) {
                    Nation n = plugin.pays().monPays(p);
                    if (n == null) {
                        Fmt.erreur(p, "Ta ville ne fait partie d'aucun pays.");
                        return;
                    }
                    e.paysFiche = Pays.id(n);
                }
                liste(p, e, Vues.Type.PAYS_VILLES, 0);
            }
            default -> p.sendMessage("ouvrir ville|annuaire|commerce|reglages|parcelles|roles|parametres|agence|warps|echoppes|habitants|fiche|pays|pays_annuaire|pays_fiche|pays_villes");
        }
    }

    /**
     * Ouverture d'un menu de liste par sa propre commande Flux (/warps...) : la liste
     * affichée suit le menu, sans toucher au filtre si c'est déjà la bonne.
     */
    private void vue(Vues.Etat e, String type) {
        Vues.Type t;
        try {
            t = Vues.Type.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            t = Vues.Type.VILLES;
        }
        if (e.type == t) return;
        e.type = t;
        e.filtre = 0;
        e.tri = 0;
        e.page = 0;
        e.recherche = "";
        e.changer();
    }

    private void liste(Player p, Vues.Etat e, Vues.Type type, int filtre) {
        if (e.type != type) {
            e.tri = 0;
            e.recherche = "";
        }
        e.type = type;
        e.filtre = filtre;
        e.page = 0;
        e.changer();
        plugin.ouvrirMenu(p, menuDe(type));
    }

    private void fiche(Player p, Vues.Etat e, Land land) {
        e.fiche = Villes.id(land);
        e.changer();
        plugin.ouvrirMenu(p, "fiche");
    }

    // ── Clics sur les listes ────────────────────────────────────────────────

    private void clic(Player p, Vues.Etat e, int n, String bouton) {
        Map<String, String> l = plugin.vues().ligne(p, n);
        if (l == null) return;
        switch (e.type) {
            case VILLES -> {
                Land land = plugin.villes().parId(l.get("id"));
                if (land == null) return;
                if (bouton.equals("droit")) {
                    p.closeInventory();
                    p.performCommand("lands spawn " + land.getName());
                } else {
                    fiche(p, e, land);
                }
            }
            case WARPS -> {
                switch (bouton) {
                    case "droit" -> ficheDe(p, e, l.get("ville_id"));
                    case "epingler" -> epingler(p, l.get("id"));
                    default -> allerA(p, l.get("nom"));
                }
            }
            case HABITANTS -> roleHabitant(p, e, l);
            case AGENCE -> {
                if (bouton.equals("droit")) {
                    ficheDe(p, e, l.get("ville_id"));
                    return;
                }
                for (Gestion.Annonce an : plugin.gestion().annonces()) {
                    if (an.villeId().equals(l.get("ville_id")) && an.parcelle().equals(l.get("parcelle")) && an.lieu() != null) {
                        p.closeInventory();
                        p.teleportAsync(an.lieu());
                        Fmt.envoyer(p, "Annonce de la parcelle " + Fmt.v(an.parcelle()) + " à " + Fmt.ville(an.ville())
                                + " : clique sur l'hologramme pour " + (an.location() ? "la louer." : "l'acheter."));
                        return;
                    }
                }
            }
            case PARCELLES -> {
                e.parcelle = l.get("nom");
                plugin.ouvrirMenu(p, "parcelle");
            }
            case ROLES -> {
                switch (bouton) {
                    case "droit" -> {
                        editerRole(p, e, l.get("id"));
                        return;
                    }
                    case "monter", "descendre" -> {
                        deplacerRole(p, e, l.get("id"), bouton.equals("monter"));
                        return;
                    }
                    default -> {
                    }
                }
                e.role = l.get("id");
                e.type = Vues.Type.DROITS;
                e.filtre = 0;
                e.tri = 0;
                e.page = 0;
                e.recherche = "";
                e.retour = "roles";
                e.changer();
                plugin.ouvrirMenu(p, "droits");
            }
            case DROITS -> basculerDroit(p, e, l.get("id"));
            case PARAMETRES -> basculerParametre(p, l.get("id"));
            case PAYS -> {
                Nation nat = plugin.pays().parId(l.get("id"));
                if (nat == null) return;
                if (bouton.equals("droit")) {
                    p.closeInventory();
                    p.performCommand("nations spawn " + nat.getName());
                } else {
                    e.paysFiche = Pays.id(nat);
                    plugin.ouvrirMenu(p, "pays_fiche");
                }
            }
            case PAYS_VILLES -> {
                Land land = plugin.villes().parId(l.get("id"));
                if (land == null) return;
                if (bouton.equals("retirer")) {
                    Nation nat = plugin.pays().parId(e.paysFiche);
                    if (nat == null || !Pays.estChef(nat, p)) {
                        Fmt.erreur(p, "Seul le chef du pays peut retirer une ville.");
                        return;
                    }
                    p.performCommand("nations untrust " + land.getName());
                    e.changer();
                } else {
                    fiche(p, e, land);
                }
            }
        }
    }

    private void ficheDe(Player p, Vues.Etat e, String villeId) {
        Land land = plugin.villes().parId(villeId);
        if (land == null) {
            Fmt.erreur(p, "Cet endroit n'est dans aucune ville.");
            return;
        }
        fiche(p, e, land);
    }

    /** Téléportation par FluxPW (droit d'entrée, mot de passe et délai gérés par lui). */
    private void allerA(Player p, String nomWarp) {
        if (nomWarp == null || nomWarp.isEmpty()) return;
        p.closeInventory();
        fr.influencraft.fluxpw.api.PlayerWarps.Info warp = fr.influencraft.fluxpw.api.PlayerWarps.byName(nomWarp);
        if (warp == null) Fmt.erreur(p, "Ce warp n'existe plus.");
        else fr.influencraft.fluxpw.api.PlayerWarps.visit(p, warp.id());
    }

    private void localiser(Player p, Map<String, String> l) {
        String warp = l.get("warp");
        if (warp != null && !warp.isEmpty()) {
            Fiches.Adresse a = null;
            for (Fiches.Adresse x : plugin.adresses()) if (x.titre().equals(warp) && x.villeId().equals(l.get("ville_id"))) a = x;
            if (a != null) {
                Fmt.envoyer(p, "L'échoppe de " + Fmt.v(l.get("proprio")) + " est à " + Fmt.v(l.get("coord"))
                        + ". Warp le plus proche : " + Fmt.v(a.titre()) + ".");
                allerA(p, a.nom());
                return;
            }
        }
        Land ville = plugin.villes().parId(l.get("ville_id"));
        if (ville != null && ville.getSpawnPosition() != null) {
            p.closeInventory();
            Fmt.envoyer(p, "Aucun warp près de l'échoppe de " + Fmt.v(l.get("proprio")) + " : direction le spawn de "
                    + Fmt.ville(ville.getName()) + ". L'échoppe est à " + Fmt.v(l.get("coord")) + ".");
            p.performCommand("lands spawn " + ville.getName());
            return;
        }
        Fmt.erreur(p, "Impossible de rejoindre cette échoppe.", "Aucun warp ni spawn de ville à proximité. Elle est à "
                + Fmt.v(l.get("coord")) + " (" + Fmt.v(l.get("monde")) + ").");
    }

    private void epingler(Player p, String warpId) {
        Land ma = plugin.villes().maVille(p);
        Fiches.Adresse a = plugin.adresse(warpId);
        if (ma == null || a == null || !Villes.estMaire(ma, p.getUniqueId()) || !Villes.id(ma).equals(a.villeId())) {
            Fmt.erreur(p, "Seul le maire peut épingler un warp de sa ville.");
            return;
        }
        int max = plugin.getConfig().getInt("vitrine.max-adresses", 3);
        if (!plugin.registre().basculerVitrine(Villes.id(ma), warpId, max)) {
            Fmt.erreur(p, "La vitrine est pleine (" + Fmt.v(max) + " warps).", "Retires-en un d'abord.");
        }
        plugin.vues().etat(p).changer();
    }

    private void vitrine(Player p, Vues.Etat e, int n, String bouton) {
        Land land = plugin.villes().parId(e.fiche);
        if (land == null) return;
        List<String> ids = plugin.registre().vitrine(Villes.id(land));
        if (n < 1 || n > ids.size()) return;
        Fiches.Adresse a = plugin.adresse(ids.get(n - 1));
        if (a == null) return;
        if (bouton.equals("droit")) epingler(p, a.id());
        else allerA(p, a.nom());
    }

    /** Localise une échoppe par son identifiant (API, menu Marché de FluxCommerce). */
    boolean localiserEchoppe(Player p, long shopId) {
        for (Fiches.Echoppe s : plugin.echoppes()) {
            if (s.id() == shopId) {
                localiser(p, plugin.vues().champsEchoppe(s));
                return true;
            }
        }
        return false;
    }

    /** Ouvre la fiche publique de la ville d'identifiant donné (API, menu Marché de FluxCommerce). */
    void ficheVille(Player p, String villeId) {
        ficheDe(p, plugin.vues().etat(p), villeId);
    }

    private void top(Player p, int n) {
        Land ma = plugin.villes().maVille(p);
        if (ma == null) return;
        String id = Villes.id(ma);
        List<Fiches.Echoppe> l = new java.util.ArrayList<>();
        for (Fiches.Echoppe s : plugin.echoppes()) if (id.equals(s.villeId())) l.add(s);
        l.sort((x, y) -> Double.compare(plugin.vues().droitReverse(y), plugin.vues().droitReverse(x)));
        if (n >= 1 && n <= l.size()) localiser(p, plugin.vues().champsEchoppe(l.get(n - 1)));
    }

    // ── Droits du maire ─────────────────────────────────────────────────────

    /** Ma ville si le joueur en est le maire (ou admin), sinon null avec un message. */
    private Land gerable(Player p) {
        Land ma = plugin.villes().maVille(p);
        if (ma == null) {
            Fmt.erreur(p, "Tu n'es habitant d'aucune ville.");
            return null;
        }
        if (!Villes.estMaire(ma, p.getUniqueId()) && !p.hasPermission("fluxlands.admin")) {
            Fmt.erreur(p, "Seul le maire de " + Fmt.ville(ma.getName()) + " peut faire ça.");
            return null;
        }
        return ma;
    }

    /** Taux du droit de place choisi au curseur, entre les bornes de la config, puis retour au menu Commerce. */
    private void droit(Player p) {
        Land ma = gerable(p);
        if (ma == null) return;
        String id = Villes.id(ma);
        float min = (float) plugin.getConfig().getDouble("droit-de-place.taux-min", 0.0);
        float max = (float) plugin.getConfig().getDouble("droit-de-place.taux-max", 15.0);
        float pas = (float) plugin.getConfig().getDouble("droit-de-place.pas", 0.5);
        Dialogs.form(p, "Droit de place")
                .body("Part de chaque vente des échoppes de " + ma.getName() + " versée à la banque de la ville.")
                .body("Un taux trop haut fait fuir les marchands vers d'autres villes.")
                .slider("taux", "Droit de place", min, max, pas, (float) plugin.registre().taux(id), "%s : %s %%")
                .onSubmit((pl, a) -> {
                    Float t = a.number("taux");
                    if (t != null) plugin.registre().definirTaux(id, t);
                    plugin.ouvrirMenu(pl, "commerce");
                })
                .onCancel(pl -> plugin.ouvrirMenu(pl, "commerce"))
                .open();
    }

    private void basculerDroit(Player p, Vues.Etat e, String flagId) {
        Land ma = gerable(p);
        if (ma == null) return;
        Role role = plugin.gestion().role(ma, e.role);
        RoleFlag f = plugin.villes().api().getFlagRegistry().getRole(flagId);
        if (role == null || f == null) return;
        if (role.getType() == RoleType.OWNER) {
            Fmt.erreur(p, "Le rôle de maire a toujours tous les droits.");
            return;
        }
        role.toggleFlag(f);
        e.changer();
    }

    private void basculerParametre(Player p, String flagId) {
        Land ma = gerable(p);
        if (ma == null) return;
        NaturalFlag f = plugin.villes().api().getFlagRegistry().getNatural(flagId);
        if (f == null) return;
        ma.getDefaultArea().toggleNaturalFlag(f);
        plugin.vues().etat(p).changer();
    }

    /**
     * The mayor picks the role of a resident (roles of the town that can be given: Lands' normal and entry roles, not
     * mayor, tenant, visitor, ally or compatriot), set through Lands' API.
     */
    private void roleHabitant(Player p, Vues.Etat e, Map<String, String> l) {
        Land land = plugin.villes().parId(e.fiche);
        if (land == null || !"oui".equals(l.get("role_modifiable"))) return;
        UUID cible = UUID.fromString(l.get("uuid"));
        Area zone = land.getDefaultArea();
        Map<String, Role> roles = new java.util.LinkedHashMap<>();
        Role actuel = zone.getRole(cible);
        List<Role> tous = new ArrayList<>();
        for (Object o : zone.getRoles()) if (o instanceof Role r && (r.getType() == RoleType.NORMAL || r.getType() == RoleType.ENTRY)) tous.add(r);
        tous.sort(java.util.Comparator.comparingInt(Role::getPriority).reversed());
        if (actuel != null && tous.remove(actuel)) tous.add(0, actuel); // current role first: the list starts on it
        for (Role r : tous) roles.put(Couleurs.nu(r.getName()), r);
        if (roles.isEmpty()) return;
        Dialogs.form(p, "Rôle de " + l.get("nom"))
                .body("Ce que " + l.get("nom") + " a le droit de faire dans " + land.getName() + ".")
                .choice("role", "Rôle", new ArrayList<>(roles.keySet()))
                .buttons("Valider", "Annuler")
                .onSubmit((pl, ans) -> {
                    Role r = roles.get(ans.text("role"));
                    if (r != null && r != actuel) {
                        zone.setRole(cible, r);
                        Fmt.envoyer(pl, Fmt.v(l.get("nom")) + " <t:texte>est maintenant</t> " + Fmt.v(Couleurs.nu(r.getName()))
                                + " <t:texte>de</t> " + Fmt.ville(land.getName()) + "<t:texte>.</t>");
                        e.changer();
                    }
                    plugin.ouvrirMenu(pl, "habitants");
                })
                .onCancel(pl -> plugin.ouvrirMenu(pl, "habitants"))
                .open();
    }

    // ── Rôles : créer, renommer, supprimer, ordonner ────────────────────────

    /** {@code role creer}: the mayor adds a role to his town (Lands' API, {@code Role.of}). */
    void creerRole(Player p) {
        Land ma = gerable(p);
        if (ma == null) return;
        plugin.saisie().ouvrir(p, "Nouveau rôle", "Nom du rôle à créer dans " + ma.getName() + ". Règle ensuite ses permissions.",
                "Nom du rôle", "", 16, "roles", (pl, txt) -> {
                    try {
                        Role.of(ma.getDefaultArea(), txt.trim());
                        Fmt.envoyer(pl, "<t:succes>Rôle créé :</t> " + Fmt.v(txt.trim()) + "<t:texte>. Il est placé en bas de la liste.</t>");
                    } catch (RuntimeException ex) {
                        Fmt.erreur(pl, "Le rôle n'a pas pu être créé.", "Le nom est peut-être déjà pris, ou la ville a atteint son nombre de rôles.");
                    }
                    plugin.vues().etat(pl).changer();
                    plugin.ouvrirMenu(pl, "roles");
                });
    }

    /** Rename or delete a residents' role (the mayor). Deleting asks for a confirmation. */
    private void editerRole(Player p, Vues.Etat e, String id) {
        Land ma = gerable(p);
        if (ma == null) return;
        Role r = plugin.gestion().role(ma, id);
        if (r == null || !Gestion.roleHabitant(r)) {
            Fmt.erreur(p, "Ce rôle est géré par Lands : il ne se renomme pas et ne se supprime pas.");
            return;
        }
        String nom = Couleurs.nu(r.getName());
        Dialogs.Form f = Dialogs.form(p, "Rôle " + nom).body("Renomme ce rôle, ou supprime-le : ses habitants prennent alors le rôle juste en dessous.")
                .input("nom", "Nouveau nom", nom, 16).action("renommer", "Renommer");
        if (r.isDeleteable()) f.action("supprimer", "&#FF5C5CSupprimer le rôle");
        f.onSubmit((pl, a) -> {
            if (a.button().equals("supprimer")) {
                Dialogs.confirm(pl, "Supprimer " + nom + " ?", "Le rôle " + nom + " disparaît de " + ma.getName() + ".",
                        x -> r.delete().whenComplete((ok, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (err != null) Fmt.erreur(x, "Le rôle n'a pas pu être supprimé.");
                            else Fmt.envoyer(x, "<t:texte>Rôle supprimé :</t> " + Fmt.v(nom) + "<t:texte>.</t>");
                            e.changer();
                            plugin.ouvrirMenu(x, "roles");
                        })),
                        x -> plugin.ouvrirMenu(x, "roles"));
                return;
            }
            String nouveau = a.text("nom");
            if (!nouveau.isEmpty() && !nouveau.equals(nom)) {
                r.setName(nouveau);
                Fmt.envoyer(pl, "<t:texte>Rôle renommé :</t> " + Fmt.v(nouveau) + "<t:texte>.</t>");
                e.changer();
            }
            plugin.ouvrirMenu(pl, "roles");
        }).onCancel(pl -> plugin.ouvrirMenu(pl, "roles")).open();
    }

    /** Moves a residents' role one step up or down the priority order (swapped with the next residents' role). */
    private void deplacerRole(Player p, Vues.Etat e, String id, boolean monter) {
        Land ma = gerable(p);
        if (ma == null) return;
        Role r = plugin.gestion().role(ma, id);
        if (r == null || !Gestion.roleHabitant(r)) return;
        List<Role> ordre = new ArrayList<>();
        for (Object o : ma.getDefaultArea().getRoles()) if (o instanceof Role x && Gestion.roleHabitant(x)) ordre.add(x);
        ordre.sort(java.util.Comparator.comparingInt(Role::getPriority).reversed());
        int i = ordre.indexOf(r);
        int j = monter ? i - 1 : i + 1;
        if (i < 0 || j < 0 || j >= ordre.size()) {
            Fmt.erreur(p, monter ? "Ce rôle est déjà le plus haut des rôles d'habitants." : "Ce rôle est déjà le plus bas des rôles d'habitants.");
            return;
        }
        r.swapPriority(ordre.get(j));
        e.changer();
    }

    // ── Spawn, niveau, chunk, échoppes ──────────────────────────────────────

    /**
     * {@code spawn ma|fiche}: teleport to the spawn of my town or of the town looked at; {@code spawn definir}: the mayor
     * puts the spawn of his town where he stands (Lands charges its price, shown on the button).
     */
    private void spawn(Player p, Vues.Etat e, String quoi) {
        if (quoi.equals("definir")) {
            reglages(p, "spawn");
            return;
        }
        Land land = quoi.equals("fiche") ? plugin.villes().parId(e.fiche) : plugin.villes().maVille(p);
        if (land == null) return;
        if (land.getSpawnPosition() == null) {
            // said by us: Lands 9.1.1 shows the coloured town name of its own message as raw MiniMessage
            Fmt.erreur(p, "La ville " + Fmt.ville(Gestion.nu(land.getName())) + " n'a pas encore de spawn.",
                    "Le maire le pose avec le clic droit du bouton Spawn de la ville.");
            return;
        }
        p.closeInventory();
        p.performCommand("lands spawn " + land.getName());
    }

    /** {@code niveau ville|pays}: objectives of the next level (our menu); {@code monter}, {@code retour}. */
    private void niveau(Player p, String quoi) {
        switch (quoi) {
            case "pays" -> plugin.levels().open(p, true);
            case "monter" -> plugin.levels().levelUp(p);
            case "retour" -> plugin.levels().back(p);
            default -> plugin.levels().open(p, false);
        }
    }

    /** {@code chunk revendiquer|abandonner}: claim or unclaim the chunk the player stands in, for his town. */
    private void chunk(Player p, String quoi) {
        Land ma = plugin.villes().maVille(p);
        if (ma == null) {
            Fmt.erreur(p, "Tu n'es habitant d'aucune ville.");
            return;
        }
        editer(p, ma);
        p.closeInventory();
        p.performCommand(quoi.equals("abandonner") ? "lands unclaim" : "lands claim");
    }

    /**
     * Shops are listed by the market of FluxCommerce (one search for the auction house and the towns' shops), with
     * the right filter: the shops of my town, of the town looked at, mine, or all of them. Its back button comes back
     * here ({@code /fluxlands retour}).
     */
    private void echoppes(Player p, Vues.Etat e, String option) {
        String recherche = switch (option) {
            case "ma" -> {
                Land ma = plugin.villes().maVille(p);
                yield ma == null ? "" : ma.getName();
            }
            case "fiche" -> {
                Land l = plugin.villes().parId(e.fiche);
                yield l == null ? "" : l.getName();
            }
            case "miennes" -> p.getName();
            default -> "";
        };
        if (!Bukkit.getPluginManager().isPluginEnabled("FluxCommerce")) {
            Fmt.erreur(p, "Le marché n'est pas disponible pour le moment.");
            return;
        }
        // source 2 of the market = towns' shops that sell
        p.performCommand(("influmarche depuis fluxlands+retour 2 " + recherche).trim());
    }

    // ── Banque ──────────────────────────────────────────────────────────────

    /**
     * Dépôt ou retrait directement depuis le bouton Banque : fenêtre du montant (borné par le solde du joueur ou de la
     * banque), puis la commande Lands, qui applique les droits du rôle, et retour au menu {@code menu}.
     */
    private void banque(Player p, String quoi, String menu) {
        Land ma = plugin.villes().maVille(p);
        if (ma == null) {
            Fmt.erreur(p, "Tu n'es habitant d'aucune ville.");
            return;
        }
        boolean depot = quoi.equals("deposer") || quoi.equals("depot");
        if (!depot && !quoi.equals("retirer") && !quoi.equals("retrait")) return;
        String retour = menu.isEmpty() ? "ville" : menu;
        var argent = fr.influencraft.flux.api.economy.Economy.main();
        double perso = argent != null ? argent.balance(p) : 0;
        long max = (long) Math.floor(depot ? perso : ma.getBalance());
        if (max < 1) {
            Fmt.erreur(p, depot ? "Tu n'as aucun BitBlock à déposer." : "La banque de " + Fmt.ville(ma.getName()) + " est vide.");
            return;
        }
        Dialogs.form(p, depot ? "Déposer à la banque" : "Retirer de la banque")
                .body("Banque de " + ma.getName() + " : " + Fmt.argent(ma.getBalance()) + " &f%img_bitblock%")
                .body("Ton solde : " + Fmt.argent(perso) + " &f%img_bitblock%")
                .body(depot ? "Elle paie les frais d'entretien de la ville." : "Retirer demande le droit de banque de ton rôle.")
                .amount("montant", depot ? "Montant à déposer" : "Montant à retirer", 0, 1, max)
                .buttons(depot ? "Déposer" : "Retirer", "Annuler")
                .onSubmit((pl, a) -> {
                    editer(pl, ma);
                    pl.performCommand("lands bank " + (depot ? "deposit " : "withdraw ") + a.amount("montant"));
                    plugin.ouvrirMenu(pl, retour);
                })
                .onCancel(pl -> plugin.ouvrirMenu(pl, retour))
                .open();
    }

    // ── Parcelles ───────────────────────────────────────────────────────────

    private void parcelle(Player p, Vues.Etat e, String quoi) {
        Land ma = plugin.villes().maVille(p);
        if (ma == null) return;
        if (quoi.equals("ici")) {
            Area a = ma.getArea(p.getLocation());
            if (a == null || a.isDefault()) {
                Fmt.erreur(p, "Tu n'es dans aucune parcelle de " + Fmt.ville(ma.getName()) + ".");
                return;
            }
            e.parcelle = a.getName();
            plugin.ouvrirMenu(p, "parcelle");
            return;
        }
        if (quoi.equals("nouvelle")) {
            nouvelleParcelle(p, ma);
            return;
        }
        Area zone = plugin.gestion().parcelle(ma, e.parcelle);
        if (zone == null) {
            Fmt.erreur(p, "Cette parcelle n'existe plus.");
            return;
        }
        switch (quoi) {
            case "aller" -> {
                Location l = Gestion.centre(zone);
                if (l != null) {
                    p.closeInventory();
                    p.teleportAsync(l);
                }
            }
            case "habitants" -> natif(p, "lands", "area " + zone.getName() + " menu");
            case "supprimer" -> {
                // Lands ne supprime une parcelle que depuis son menu natif des parcelles (aucune
                // commande ni API) : on l'ouvre et on explique la touche à utiliser.
                if (gerable(p) == null) return;
                if (zone.getRentalOffer() != null) {
                    Fmt.erreur(p, "La parcelle " + Fmt.v(zone.getName()) + " a une annonce ou un occupant.", "Retire d'abord l'annonce ou mets fin à l'occupation.");
                    return;
                }
                natif(p, "lands", "menu land areas");
                Fmt.envoyer(p, "Survole " + Fmt.v(zone.getName()) + " dans la liste et appuie sur ta touche " + Fmt.v("Jeter") + " <t:deco>(Q par défaut)</t> pour la supprimer.");
            }
            case "delimiter" -> {
                if (gerable(p) == null) return;
                if (plugin.gestion().selection(p).isEmpty()) lancerSelection(p, ma, zone.getName());
                else nommerParcelle(p, ma, zone.getName());
            }
            default -> {
                if (gerable(p) == null) return;
                switch (quoi) {
                    case "spawn" -> {
                        Area ici = ma.getArea(p.getLocation());
                        if (ici == null || !ici.getName().equalsIgnoreCase(zone.getName())) {
                            Fmt.erreur(p, "Tu n'es pas dans la parcelle " + Fmt.v(zone.getName()) + ".", "Place-toi là où les visiteurs doivent arriver.");
                            return;
                        }
                        plugin.gestion().definirSpawn(zone, p.getLocation());
                        Fmt.envoyer(p, "Point d'arrivée de la parcelle " + Fmt.v(zone.getName()) + " défini ici.");
                    }
                    case "renommer" -> plugin.saisie().ouvrir(p, "Renommer la parcelle", "Nouveau nom de la parcelle " + zone.getName() + " (sans espace).",
                            "Nom", zone.getName(), 24, "parcelle", (pl, txt) -> {
                                String nom = txt.replace(' ', '_');
                                if (Gestion.renommer(zone, nom)) e.parcelle = nom;
                                else Fmt.erreur(pl, "Nom refusé par Lands : " + Fmt.v(nom) + ".");
                                plugin.ouvrirMenu(pl, "parcelle");
                            });
                    case "location", "vente" -> plugin.listings().open(p, ma, zone, quoi.equals("location"));
                    case "modifier" -> {
                        // same window as the first publication, prefilled; placed again where the mayor stands
                        var o = zone.getRentalOffer();
                        if (o == null) plugin.listings().open(p, ma, zone, true);
                        else plugin.listings().open(p, ma, zone, o.getType() != me.angeschossen.lands.api.land.rental.offer.types.RentalType.SELL);
                    }
                    case "retirer" -> {
                        if (plugin.gestion().retirerAnnonce(zone)) Fmt.envoyer(p, "Annonce de la parcelle " + Fmt.v(zone.getName()) + " retirée de l'Agence.");
                        else Fmt.erreur(p, "Aucune annonce à retirer sur cette parcelle.");
                    }
                    case "terminer" -> {
                        if (plugin.gestion().terminer(zone)) Fmt.envoyer(p, "Location ou vente de " + Fmt.v(zone.getName()) + " terminée.");
                        else Fmt.erreur(p, "Personne n'occupe cette parcelle.");
                    }
                    default -> p.sendMessage("parcelle ici|aller|spawn|renommer|location|vente|modifier|retirer|terminer|habitants|nouvelle|delimiter|supprimer");
                }
            }
        }
    }

    /**
     * New plot: the mayor picks how to outline it (Lands' two-corner selection tool, the chunk they stand in, or the
     * selection they already have), then names it in one window that can chain to the Agency.
     */
    private void nouvelleParcelle(Player p, Land ma) {
        if (gerable(p) == null) return;
        attenteSelection.remove(p.getUniqueId());
        String sel = plugin.gestion().selection(p);
        Dialogs.Form form = Dialogs.form(p, "Nouvelle parcelle")
                .body("Comment veux-tu délimiter la parcelle ?")
                .body("&#FFD94DDeux coins : &fl'outil de sélection de Lands, un clic sur chaque coin opposé.")
                .body("&#FFD94DLe chunk où je suis : &fle carré de 16 x 16 blocs sous tes pieds, en un clic.");
        if (!sel.isEmpty()) form.action("actuelle", "Ma sélection actuelle (" + sel + ")");
        form.action("coins", "Sélectionner deux coins").action("chunk", "Le chunk où je suis").columns(1)
                .onCancel(pl -> plugin.ouvrirMenu(pl, "parcelles"))
                .onSubmit((pl, a) -> {
                    switch (a.button()) {
                        case "actuelle" -> nommerParcelle(pl, ma);
                        case "chunk" -> selectionnerChunk(pl, ma);
                        default -> lancerSelection(pl, ma, "");
                    }
                })
                .open();
    }

    /** Starts a fresh Lands selection (tool in hand); the name window opens once both corners are set. */
    private void lancerSelection(Player p, Land ma, String cible) {
        p.closeInventory();
        editer(p, ma);
        // /lands selection toggles the mode: drop a former selection first, or the command would end it
        LandPlayer lp = plugin.villes().api().getLandPlayer(p.getUniqueId());
        if (lp != null && lp.getSelection() != null) lp.getSelection().disable();
        p.performCommand("lands selection");
        attenteSelection.put(p.getUniqueId(), System.currentTimeMillis() + 10 * 60_000L);
        attenteParcelle.put(p.getUniqueId(), cible);
        Fmt.envoyer(p, "Clique sur deux coins opposés de " + (cible.isEmpty() ? "ta parcelle" : Fmt.v(cible))
                + " avec l'outil : la fenêtre s'ouvrira toute seule.");
    }

    /** Selects the 16 x 16 chunk under the player through Lands' API (no tool), then asks for the name. */
    private void selectionnerChunk(Player p, Land ma) {
        Land ici = plugin.villes().a(p.getLocation());
        if (ici == null || !Villes.id(ici).equals(Villes.id(ma))) {
            Fmt.erreur(p, "Le chunk où tu te tiens n'appartient pas à " + Fmt.ville(ma.getName()) + ".");
            return;
        }
        LandPlayer lp = plugin.villes().api().getLandPlayer(p.getUniqueId());
        if (lp == null) return;
        if (lp.getSelection() != null) lp.getSelection().disable();
        Selection s = Selection.of(lp, false, false, true);
        org.bukkit.Chunk c = p.getLocation().getChunk();
        int y = p.getLocation().getBlockY();
        s.setPos1(new Location(p.getWorld(), c.getX() << 4, y, c.getZ() << 4));
        s.setPos2(new Location(p.getWorld(), (c.getX() << 4) + 15, y, (c.getZ() << 4) + 15));
        nommerParcelle(p, ma);
    }

    /** Appelé toutes les demi-secondes : ouvre la saisie du nom dès que la sélection est complète. */
    void verifierSelections() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> en : attenteSelection.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || en.getValue() < now) {
                attenteSelection.remove(en.getKey());
                attenteParcelle.remove(en.getKey());
                continue;
            }
            if (plugin.gestion().selection(p).isEmpty()) continue;
            attenteSelection.remove(en.getKey());
            String cible = attenteParcelle.remove(en.getKey());
            Land ma = plugin.villes().maVille(p);
            if (ma == null) continue;
            fr.influencraft.flux.api.util.Sounds.cue(p, "success");
            if (cible != null && !cible.isEmpty()) {
                nommerParcelle(p, ma, cible);
                continue;
            }
            Fmt.action(p, "Sélection prête : " + Fmt.v(plugin.gestion().selection(p)) + ". Fenêtre fermée par erreur ?",
                    "Nommer la parcelle", label() + " parcelle nouvelle");
            nommerParcelle(p, ma);
        }
    }

    private void nommerParcelle(Player p, Land ma) {
        nommerParcelle(p, ma, "");
    }

    /** Fenêtre de la parcelle : nom (prérempli pour délimiter une parcelle existante), hauteur, suite (Agence). */
    private void nommerParcelle(Player p, Land ma, String nomInitial) {
        int[] y = plugin.gestion().ySelection(p);
        String yTexte = y != null ? " Hauteur actuelle : Y " + y[0] + " à " + y[1] + "." : "";
        plugin.saisie().ouvrirParcelle(p, nomInitial.isEmpty() ? "Nouvelle parcelle" : "Délimiter " + nomInitial,
                "Sélection : " + plugin.gestion().selection(p) + "." + yTexte
                        + " Coche « Du sol au ciel » ou indique un Y min et/ou un Y max pour l'étendre en profondeur ou en hauteur.",
                nomInitial, true, "", "", "parcelles",
                (pl, nom, solAuCiel, yMin, yMax, ensuite) -> assigner(pl, ma, nom, solAuCiel, yMin, yMax, ensuite));
    }

    /**
     * Crée la parcelle (si besoin) et lui donne la sélection du joueur, en une seule commande Lands
     * tapable à la main : {@code /lands area <nom> assign confirm} (nom avant la sous-commande ;
     * {@code confirm} passe la question « faible hauteur »). Hauteur : « Du sol au ciel » lance
     * {@code /lands selection expand} ; sinon Y min / Y max sont posés sur la sélection par l'API.
     */
    private void assigner(Player p, Land ma, String nom, boolean toutLaHauteur, Integer yMin, Integer yMax, Saisie.Ensuite ensuite) {
        if (!p.isOnline()) return;
        if (plugin.gestion().selection(p).isEmpty()) {
            Fmt.erreur(p, "Ta sélection n'est plus complète.", "Resélectionne les deux coins de la parcelle.");
            plugin.ouvrirMenu(p, "parcelles");
            return;
        }
        p.closeInventory();
        editer(p, ma);
        if (toutLaHauteur) {
            // expand est un interrupteur : si la hauteur a rétréci, le mode était déjà actif et vient
            // d'être coupé, on le rallume.
            int avant = plugin.gestion().hauteurSelection(p);
            p.performCommand("lands selection expand");
            if (plugin.gestion().hauteurSelection(p) < avant) p.performCommand("lands selection expand");
        } else if (yMin != null || yMax != null) {
            if (plugin.gestion().reglerY(p, yMin, yMax)) {
                int[] y = plugin.gestion().ySelection(p);
                if (y != null) Fmt.envoyer(p, "Hauteur de la sélection : " + Fmt.v("Y " + y[0] + " à " + y[1]) + ".");
            }
        }
        p.performCommand("lands area " + nom + " assign confirm");
        attendreParcelle(p, ma, nom, ensuite, 0);
    }

    /**
     * Lands enregistre la parcelle en tâche asynchrone : on la guette 2 secondes avant de conclure, puis on
     * enchaîne sur la fenêtre de l'Agence si le maire l'a demandé.
     */
    private void attendreParcelle(Player p, Land ma, String nom, Saisie.Ensuite ensuite, int essai) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            Area a = plugin.gestion().parcelle(ma, nom);
            if (a != null && Gestion.delimitee(a)) {
                plugin.vues().etat(p).parcelle = nom;
                Fmt.envoyer(p, "<t:succes>Parcelle créée :</t> " + Fmt.v(nom) + " (" + Fmt.v(Gestion.dimensions(a)) + ").");
                if (ensuite == Saisie.Ensuite.RIEN) plugin.ouvrirMenu(p, "parcelle");
                else plugin.listings().open(p, ma, a, ensuite == Saisie.Ensuite.LOCATION);
            } else if (essai < 7) {
                attendreParcelle(p, ma, nom, ensuite, essai + 1);
            } else if (a != null) {
                plugin.vues().etat(p).parcelle = nom;
                Fmt.erreur(p, "La parcelle " + Fmt.v(nom) + " n'a pas reçu ta sélection.", "Elle existe : voir le message de Lands au-dessus.");
                plugin.ouvrirMenu(p, "parcelle");
            } else {
                Fmt.erreur(p, "Lands n'a pas créé la parcelle " + Fmt.v(nom) + ".", "Voir son message au-dessus.");
                plugin.ouvrirMenu(p, "parcelles");
            }
        }, 5L);
    }

    // ── Annonce en attente (remplie loin de la parcelle) ────────────────────

    private void annonce(Player p, String quoi) {
        Land ma = gerable(p);
        if (ma == null) return;
        switch (quoi) {
            case "publier" -> plugin.listings().place(p, ma);
            case "abandonner" -> plugin.listings().abandon(p);
            default -> p.sendMessage("annonce publier|abandonner");
        }
    }

    // ── Réglages de ma ville ────────────────────────────────────────────────

    private void reglages(Player p, String quoi) {
        Land ma = gerable(p);
        if (ma == null) return;
        switch (quoi) {
            case "renommer" -> plugin.saisie().ouvrir(p, "Renommer la ville", "Nouveau nom de " + ma.getName() + " (sans espace).",
                    "Nom de la ville", ma.getName(), 24, "ville", (pl, txt) -> {
                        editer(pl, ma);
                        pl.performCommand("lands rename " + txt.replace(' ', '_'));
                        plugin.ouvrirMenu(pl, "ville");
                    });
            case "accueil" -> plugin.saisie().ouvrir(p, "Message d'accueil", "Titre affiché aux joueurs qui entrent dans " + ma.getName() + ".",
                    "Message", "", 48, "ville", (pl, txt) -> {
                        ma.setTitleMessage(txt);
                        Fmt.envoyer(pl, "Message d'accueil de " + Fmt.ville(ma.getName()) + " mis à jour.");
                        plugin.ouvrirMenu(pl, "ville");
                    });
            case "spawn" -> {
                if (!ma.equals(plugin.villes().a(p.getLocation()))) {
                    Fmt.erreur(p, "Tu n'es pas dans " + Fmt.ville(ma.getName()) + ".", "Place-toi là où le spawn de la ville doit être.");
                    return;
                }
                editer(p, ma);
                p.performCommand("lands setspawn");
            }
            default -> {
            }
        }
    }

    /** Menu natif de Lands, sans l'interception de FluxLands (fonctions non refaites). */
    private void natif(Player p, String commande, String args) {
        Land ma = plugin.villes().maVille(p);
        if (ma != null) editer(p, ma);
        p.closeInventory();
        plugin.interception().laisserPasser(p);
        p.performCommand(commande + " " + args);
    }

    // ── Divers ──────────────────────────────────────────────────────────────

    private void choisir(Player p) {
        List<Land> siennes = plugin.villes().duJoueur(p);
        if (siennes.size() < 2) return;
        Land actuelle = plugin.villes().maVille(p);
        int i = Math.max(0, siennes.indexOf(actuelle));
        Land suivante = siennes.get((i + 1) % siennes.size());
        plugin.registre().choisirVille(p.getUniqueId(), Villes.id(suivante));
        plugin.vues().etat(p).changer();
    }

    /** Ville éditée par les commandes Lands sans argument de ville (banque, menu). */
    private void editer(Player p, Land land) {
        LandPlayer lp = plugin.villes().api().getLandPlayer(p.getUniqueId());
        if (lp != null) lp.setEditLand(land);
    }

    private void saisie(Player p, String quoi) {
        Land ma = plugin.villes().maVille(p);
        switch (quoi) {
            case "depot", "retrait" -> banque(p, quoi, "ville");
            case "creer_ville" -> plugin.saisie().ouvrir(p, "Fonder une ville",
                    "Choisis le nom de ta ville. Tu dois te tenir à l'endroit de ton premier chunk.",
                    "Nom de la ville", "", 24, "ville", (pl, txt) -> {
                        pl.performCommand("lands create " + txt.replace(' ', '_'));
                        plugin.ouvrirMenu(pl, "ville");
                    });
            case "creer_warp", "creer_adresse" -> plugin.saisie().ouvrir(p, "Créer un warp",
                    "Un warp est un point de téléportation public, créé là où tu te tiens.",
                    "Nom du warp", "", 16, "warps", (pl, txt) -> {
                        pl.closeInventory();
                        fr.influencraft.fluxpw.api.PlayerWarps.create(pl, txt.replace(' ', '_'));
                    });
            case "inviter" -> {
                if (ma == null) return;
                List<String> enLigne = new ArrayList<>();
                for (Player o : Bukkit.getOnlinePlayers()) if (!ma.isTrusted(o.getUniqueId())) enLigne.add(o.getName());
                enLigne.sort(String.CASE_INSENSITIVE_ORDER);
                plugin.saisie().choisir(p, "Inviter un habitant", "Le joueur recevra une invitation à rejoindre " + ma.getName() + ".",
                        "Joueur connecté", enLigne, "Ou tape un pseudo", 16, "habitants", (pl, txt) -> {
                            editer(pl, ma);
                            pl.performCommand("lands trust " + txt);
                            plugin.ouvrirMenu(pl, "habitants");
                        });
            }
            default -> p.sendMessage("saisie creer_ville|creer_warp|inviter");
        }
    }

    // ── Administration ──────────────────────────────────────────────────────

    private void admin(CommandSender s, String sous, String[] a) {
        switch (sous) {
            case "reload" -> {
                plugin.recharger();
                s.sendMessage("FluxLands rechargé.");
            }
            case "index" -> {
                plugin.indexer();
                plugin.gestion().invalider();
                s.sendMessage("Index : " + plugin.echoppes().size() + " échoppes, " + plugin.adresses().size() + " warps, "
                        + plugin.villes().toutes().size() + " villes, " + plugin.gestion().annonces().size() + " annonces.");
            }
            case "taux" -> {
                Land land = plugin.villes().parNom(a.length > 1 ? a[1] : "");
                if (land == null || a.length < 3) {
                    s.sendMessage(label() + " taux <ville> <taux en %>");
                    return;
                }
                try {
                    plugin.registre().definirTaux(Villes.id(land), Double.parseDouble(a[2].replace(',', '.')));
                    s.sendMessage("Droit de place de " + land.getName() + " : " + Fmt.taux(plugin.registre().taux(Villes.id(land))) + " %");
                } catch (NumberFormatException ex) {
                    s.sendMessage("Taux invalide : " + a[2]);
                }
            }
            default -> {
            }
        }
    }

    private static String arg(String[] a, int i) {
        return a.length > i ? a[i].toLowerCase(Locale.ROOT) : "";
    }

    private static boolean plus(String[] a) {
        return !arg(a, 1).startsWith("-");
    }

    private static int entier(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ── Pays ────────────────────────────────────────────────────────────────

    private void pays(Player p, Vues.Etat e, String quoi, String option) {
        Land ma = plugin.villes().maVille(p);
        Nation mon = plugin.pays().monPays(p);
        switch (quoi) {
            case "spawn" -> {
                Nation n = option.equals("fiche") ? plugin.pays().parId(e.paysFiche) : mon;
                if (n == null) return;
                p.closeInventory();
                p.performCommand("nations spawn " + n.getName());
            }
            case "creer" -> {
                if (ma == null || !Villes.estMaire(ma, p.getUniqueId())) {
                    Fmt.erreur(p, "Il faut être maire d'une ville pour fonder un pays.", "Ta ville en sera la capitale.");
                    return;
                }
                plugin.saisie().ouvrir(p, "Fonder un pays", ma.getName() + " deviendra la capitale du pays. Choisis son nom (sans espace).",
                        "Nom du pays", "", 24, "pays_accueil", (pl, txt) -> {
                            editer(pl, ma);
                            // Lands: /nations create <capital> <name> (a name alone is read as the capital town)
                            pl.performCommand("nations create " + ma.getName() + " " + txt.replace(' ', '_'));
                            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.ouvrirMenu(pl, plugin.pays().monPays(pl) != null ? "pays" : "pays_accueil"), 5L);
                        });
            }
            case "quitter" -> {
                if (ma == null || mon == null || !Villes.estMaire(ma, p.getUniqueId())) {
                    Fmt.erreur(p, "Seul le maire peut faire quitter le pays à sa ville.");
                    return;
                }
                editer(p, ma);
                p.closeInventory();
                p.performCommand("lands nation leave");
            }
            case "chat" -> {
                p.closeInventory();
                p.performCommand("nations chat");
            }
            // taxes and effects: our menus (CountryMenus), the head changes them
            case "taxes", "effets" -> {
                if (mon == null) {
                    Fmt.erreur(p, "Ta ville ne fait partie d'aucun pays.");
                    return;
                }
                plugin.countryMenus().refresh(p);
                plugin.ouvrirMenu(p, quoi.equals("taxes") ? "pays_taxes" : "pays_effets");
            }
            case "taxe" -> plugin.countryMenus().askTax(p);
            case "effet" -> plugin.countryMenus().toggleEffect(p, option);
            case "accepter" -> {
                if (ma == null || !Villes.estMaire(ma, p.getUniqueId())) {
                    Fmt.erreur(p, "Seul le maire peut faire rejoindre un pays à sa ville.");
                    return;
                }
                // only the countries that really invited this town (read from Lands), accepted through its API
                Map<String, me.angeschossen.lands.api.nation.invite.NationInvite> invitations = plugin.pays().invitations(ma);
                if (invitations.isEmpty()) {
                    Fmt.erreur(p, "Ta ville " + Fmt.ville(ma.getName()) + " n'a reçu aucune invitation.",
                            "Le chef d'un pays doit d'abord l'inviter, depuis son menu Mon pays.");
                    return;
                }
                List<String> noms = new ArrayList<>(invitations.keySet());
                noms.sort(String.CASE_INSENSITIVE_ORDER);
                Dialogs.form(p, "Rejoindre un pays")
                        .body(invitations.size() == 1 ? "Un pays invite ta ville " + ma.getName() + " à le rejoindre."
                                : invitations.size() + " pays invitent ta ville " + ma.getName() + " à les rejoindre.")
                        .body("Ta ville en deviendra membre : le pays gère ses taxes et ses effets.")
                        .choice("pays", "Pays qui t'invite", noms)
                        .buttons("Rejoindre", "Annuler")
                        .onSubmit((pl, ans) -> {
                            var invite = invitations.get(ans.text("pays"));
                            if (invite == null || !invite.accept()) {
                                Fmt.erreur(pl, "Cette invitation n'est plus valable.", "Demande au chef du pays de t'inviter à nouveau.");
                                plugin.ouvrirMenu(pl, "pays_accueil");
                                return;
                            }
                            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.ouvrirMenu(pl, plugin.pays().monPays(pl) != null ? "pays" : "pays_accueil"), 5L);
                        })
                        .onCancel(pl -> plugin.ouvrirMenu(pl, "pays_accueil"))
                        .open();
            }
            default -> {
                if (mon == null || !Pays.estChef(mon, p)) {
                    Fmt.erreur(p, "Seul le chef du pays peut faire ça.");
                    return;
                }
                editer(p, mon.getCapital() != null ? mon.getCapital() : ma);
                switch (quoi) {
                    case "inviter" -> {
                        List<String> libres = new ArrayList<>();
                        for (Land l : plugin.villes().toutes()) if (l.getNation() == null) libres.add(l.getName());
                        libres.sort(String.CASE_INSENSITIVE_ORDER);
                        plugin.saisie().choisir(p, "Inviter une ville", "La ville recevra une invitation à rejoindre " + mon.getName() + ".",
                                "Ville sans pays", libres, "Ou tape un nom de ville", 24, "pays", (pl, txt) -> {
                                    pl.performCommand("nations trust " + txt);
                                    plugin.ouvrirMenu(pl, "pays");
                                });
                    }
                    case "capitale" -> {
                        List<String> membres = new ArrayList<>();
                        for (Land l : Pays.villes(mon)) if (mon.getCapital() == null || !Villes.id(l).equals(Villes.id(mon.getCapital()))) membres.add(l.getName());
                        membres.sort(String.CASE_INSENSITIVE_ORDER);
                        if (membres.isEmpty()) {
                            Fmt.erreur(p, "Aucune autre ville du pays ne peut devenir capitale.");
                            return;
                        }
                        plugin.saisie().choisir(p, "Changer de capitale", "La capitale paie les frais d'entretien du pays.",
                                "Nouvelle capitale", membres, "Ou tape un nom de ville", 24, "pays", (pl, txt) -> {
                                    pl.performCommand("nations member setcapital " + txt);
                                    plugin.ouvrirMenu(pl, "pays");
                                });
                    }
                    case "renommer" -> plugin.saisie().ouvrir(p, "Renommer le pays", "Nouveau nom de " + mon.getName() + " (sans espace).",
                            "Nom du pays", mon.getName(), 24, "pays", (pl, txt) -> {
                                pl.performCommand("nations rename " + txt.replace(' ', '_'));
                                plugin.ouvrirMenu(pl, "pays");
                            });
                    default -> p.sendMessage("pays spawn|inviter|capitale|renommer|creer|quitter|chat|accepter");
                }
            }
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] a) {
        if (a.length == 1) return SOUS.stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
