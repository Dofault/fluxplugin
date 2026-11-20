package fr.influencraft.fluxlands;

import fr.influencraft.flux.api.dialog.Dialogs;
import me.angeschossen.lands.api.flags.type.NaturalFlag;
import me.angeschossen.lands.api.flags.type.RoleFlag;
import me.angeschossen.lands.api.land.Area;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.land.LandArea;
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
 *   reload | index | taux &lt;ville&gt; &lt;taux&gt;  (flux.admin)
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
    /** Mayors already told that their outline goes out of the town (until they fix it or start again). */
    private final java.util.Set<UUID> warnedOutside = ConcurrentHashMap.newKeySet();

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
            sender.sendMessage(label() + Lang.t("commande.ouvrir-page-filtre-tri-clic"));
            return true;
        }
        String sous = a[0].toLowerCase(Locale.ROOT);
        if (sous.equals("reload") || sous.equals("index") || sous.equals("taux")) {
            if (!fr.influencraft.flux.api.staff.Staff.isAdmin(sender)) {
                sender.sendMessage("Permission flux.admin requise.");
                return true;
            }
            admin(sender, sous, a);
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.t("commande.commande-de-menu-joueur-uniquement"));
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
                e.taille = Math.floorMod(e.taille + (plus(a) ? 1 : -1), Vues.TAILLES);
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
            case "annonce" -> annonce(p, a.length > 1 ? a[1] : "", arg(a, 2));
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
        Fmt.erreur(p, Lang.t("commande.tu-n-es-habitant-d"));
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
                    Fmt.erreur(p, Lang.t("commande.aucune-ville-a-afficher"));
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
                    Fmt.erreur(p, Lang.t("commande.aucun-pays-a-afficher"));
                    return;
                }
                e.paysFiche = Pays.id(n);
                plugin.ouvrirMenu(p, "pays_fiche");
            }
            case "pays_villes" -> {
                if (!option.equals("fiche")) {
                    Nation n = plugin.pays().monPays(p);
                    if (n == null) {
                        Fmt.erreur(p, Lang.t("commande.ta-ville-ne-fait-partie"));
                        return;
                    }
                    e.paysFiche = Pays.id(n);
                }
                liste(p, e, Vues.Type.PAYS_VILLES, 0);
            }
            default -> p.sendMessage(Lang.t("commande.ouvrir-ville-annuaire-commerce-reglages"));
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
                        Fmt.envoyer(p, Lang.t("commande.annonce-de-la-parcelle") + Fmt.v(an.parcelle()) + Lang.t("commande.a") + Fmt.ville(an.ville())
                                + Lang.t("commande.clique-sur-l-hologramme-pour") + (an.location() ? Lang.t("commande.la-louer") : "l'acheter."));
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
                        Fmt.erreur(p, Lang.t("commande.seul-le-chef-du-pays"));
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
            Fmt.erreur(p, Lang.t("commande.cet-endroit-n-est-dans"));
            return;
        }
        fiche(p, e, land);
    }

    /** Téléportation par FluxPW (droit d'entrée, mot de passe et délai gérés par lui). */
    private void allerA(Player p, String nomWarp) {
        if (nomWarp == null || nomWarp.isEmpty()) return;
        p.closeInventory();
        fr.influencraft.fluxpw.api.PlayerWarps.Info warp = fr.influencraft.fluxpw.api.PlayerWarps.byName(nomWarp);
        if (warp == null) Fmt.erreur(p, Lang.t("commande.ce-warp-n-existe-plus"));
        else fr.influencraft.fluxpw.api.PlayerWarps.visit(p, warp.id());
    }

    private void localiser(Player p, Map<String, String> l) {
        String warp = l.get("warp");
        if (warp != null && !warp.isEmpty()) {
            Fiches.Adresse a = null;
            for (Fiches.Adresse x : plugin.adresses()) if (x.titre().equals(warp) && x.villeId().equals(l.get("ville_id"))) a = x;
            if (a != null) {
                Fmt.envoyer(p, Lang.t("commande.l-echoppe-de") + Fmt.v(l.get("proprio")) + Lang.t("commande.est-a") + Fmt.v(l.get("coord"))
                        + Lang.t("commande.warp-le-plus-proche") + Fmt.v(a.titre()) + ".");
                allerA(p, a.nom());
                return;
            }
        }
        Land ville = plugin.villes().parId(l.get("ville_id"));
        if (ville != null && ville.getSpawnPosition() != null) {
            p.closeInventory();
            Fmt.envoyer(p, Lang.t("commande.aucun-warp-pres-de-l") + Fmt.v(l.get("proprio")) + Lang.t("commande.direction-le-spawn-de")
                    + Fmt.ville(ville.getName()) + Lang.t("commande.l-echoppe-est-a") + Fmt.v(l.get("coord")) + ".");
            p.performCommand("lands spawn " + ville.getName());
            return;
        }
        Fmt.erreur(p, Lang.t("commande.impossible-de-rejoindre-cette-echoppe"), Lang.t("commande.aucun-warp-ni-spawn-de")
                + Fmt.v(l.get("coord")) + " (" + Fmt.v(l.get("monde")) + ").");
    }

    private void epingler(Player p, String warpId) {
        Land ma = plugin.villes().maVille(p);
        Fiches.Adresse a = plugin.adresse(warpId);
        if (ma == null || a == null || !Villes.estMaire(ma, p.getUniqueId()) || !Villes.id(ma).equals(a.villeId())) {
            Fmt.erreur(p, Lang.t("commande.seul-le-maire-peut-epingler"));
            return;
        }
        int max = plugin.getConfig().getInt("vitrine.max-adresses", 3);
        if (!plugin.registre().basculerVitrine(Villes.id(ma), warpId, max)) {
            Fmt.erreur(p, Lang.t("commande.la-vitrine-est-pleine") + Fmt.v(max) + " warps).", Lang.t("commande.retires-en-un-d-abord"));
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
            Fmt.erreur(p, Lang.t("commande.tu-n-es-habitant-d"));
            return null;
        }
        if (!Villes.estMaire(ma, p.getUniqueId()) && !fr.influencraft.flux.api.staff.Staff.isAdmin(p)) {
            Fmt.erreur(p, Lang.t("commande.seul-le-maire-de") + Fmt.ville(ma.getName()) + Lang.t("commande.peut-faire-ca"));
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
        Dialogs.form(p, Lang.t("commande.droit-de-place"))
                .body(Lang.t("commande.part-de-chaque-vente-des") + ma.getName() + Lang.t("commande.versee-a-la-banque-de"))
                .body(Lang.t("commande.un-taux-trop-haut-fait"))
                .slider("taux", Lang.t("commande.droit-de-place"), min, max, pas, (float) plugin.registre().taux(id), "%s : %s %%")
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
            Fmt.erreur(p, Lang.t("commande.le-role-de-maire-a"));
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
        Dialogs.form(p, Lang.t("commande.role-de") + l.get("nom"))
                .body(Lang.t("commande.ce-que") + l.get("nom") + Lang.t("commande.a-le-droit-de-faire") + land.getName() + ".")
                .choice("role", Lang.t("commande.role"), new ArrayList<>(roles.keySet()))
                .buttons("Valider", "Annuler")
                .onSubmit((pl, ans) -> {
                    Role r = roles.get(ans.text("role"));
                    if (r != null && r != actuel) {
                        zone.setRole(cible, r);
                        Fmt.envoyer(pl, Fmt.v(l.get("nom")) + Lang.t("commande.est-maintenant") + Fmt.v(Couleurs.nu(r.getName()))
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
        plugin.saisie().ouvrir(p, Lang.t("commande.nouveau-role"), Lang.t("commande.nom-du-role-a-creer") + ma.getName() + Lang.t("commande.regle-ensuite-ses-permissions"),
                Lang.t("commande.nom-du-role"), "", 16, "roles", (pl, txt) -> {
                    try {
                        Role.of(ma.getDefaultArea(), txt.trim());
                        Fmt.envoyer(pl, Lang.t("commande.role-cree") + Fmt.v(txt.trim()) + Lang.t("commande.il-est-place-en-bas"));
                    } catch (RuntimeException ex) {
                        Fmt.erreur(pl, Lang.t("commande.le-role-n-a-pas"), Lang.t("commande.le-nom-est-peut-etre"));
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
            Fmt.erreur(p, Lang.t("commande.ce-role-est-gere-par"));
            return;
        }
        String nom = Couleurs.nu(r.getName());
        Dialogs.Form f = Dialogs.form(p, Lang.t("commande.role-2") + nom).body(Lang.t("commande.renomme-ce-role-ou-supprime"))
                .input("nom", "Nouveau nom", nom, 16).action("renommer", "Renommer");
        if (r.isDeleteable()) f.action("supprimer", Lang.t("commande.supprimer-le-role"));
        f.onSubmit((pl, a) -> {
            if (a.button().equals("supprimer")) {
                Dialogs.confirm(pl, "Supprimer " + nom + " ?", Lang.t("commande.le-role") + nom + Lang.t("commande.disparait-de") + ma.getName() + ".",
                        x -> r.delete().whenComplete((ok, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (err != null) Fmt.erreur(x, Lang.t("commande.le-role-n-a-pas-2"));
                            else Fmt.envoyer(x, Lang.t("commande.role-supprime") + Fmt.v(nom) + "<t:texte>.</t>");
                            e.changer();
                            plugin.ouvrirMenu(x, "roles");
                        })),
                        x -> plugin.ouvrirMenu(x, "roles"));
                return;
            }
            String nouveau = a.text("nom");
            if (!nouveau.isEmpty() && !nouveau.equals(nom)) {
                r.setName(nouveau);
                Fmt.envoyer(pl, Lang.t("commande.role-renomme") + Fmt.v(nouveau) + "<t:texte>.</t>");
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
            Fmt.erreur(p, monter ? Lang.t("commande.ce-role-est-deja-le") : Lang.t("commande.ce-role-est-deja-le-2"));
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
            Fmt.erreur(p, Lang.t("commande.la-ville") + Fmt.ville(Gestion.nu(land.getName())) + Lang.t("commande.n-a-pas-encore-de"),
                    Lang.t("commande.le-maire-le-pose-avec"));
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
            Fmt.erreur(p, Lang.t("commande.tu-n-es-habitant-d"));
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
            Fmt.erreur(p, Lang.t("commande.le-marche-n-est-pas"));
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
            Fmt.erreur(p, Lang.t("commande.tu-n-es-habitant-d"));
            return;
        }
        boolean depot = quoi.equals("deposer") || quoi.equals("depot");
        if (!depot && !quoi.equals("retirer") && !quoi.equals("retrait")) return;
        String retour = menu.isEmpty() ? "ville" : menu;
        var argent = fr.influencraft.flux.api.economy.Economy.main();
        double perso = argent != null ? argent.balance(p) : 0;
        long max = (long) Math.floor(depot ? perso : ma.getBalance());
        if (max < 1) {
            Fmt.erreur(p, depot ? Lang.t("commande.tu-n-as-aucun-bitblock") : Lang.t("commande.la-banque-de") + Fmt.ville(ma.getName()) + Lang.t("commande.est-vide"));
            return;
        }
        Dialogs.form(p, depot ? Lang.t("commande.deposer-a-la-banque") : Lang.t("commande.retirer-de-la-banque"))
                .body(Lang.t("commande.banque-de") + ma.getName() + " : " + Fmt.argent(ma.getBalance()) + " &f%img_bitblock%")
                .body(Lang.t("commande.ton-solde") + Fmt.argent(perso) + " &f%img_bitblock%")
                .body(depot ? Lang.t("commande.elle-paie-les-frais-d") : Lang.t("commande.retirer-demande-le-droit-de"))
                .amount("montant", depot ? Lang.t("commande.montant-a-deposer") : Lang.t("commande.montant-a-retirer"), 0, 1, max)
                .buttons(depot ? Lang.t("commande.deposer") : "Retirer", "Annuler")
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
                Fmt.erreur(p, Lang.t("commande.tu-n-es-dans-aucune") + Fmt.ville(ma.getName()) + ".");
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
            Fmt.erreur(p, Lang.t("commande.cette-parcelle-n-existe-plus"));
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
                // Lands 9.3 deletes a plot through its API (before, only from its own plot list). The menu button
                // already asks for a double click.
                if (gerable(p) == null) return;
                String name = zone.getName();
                if (zone.getRentalOffer() != null) {
                    Fmt.erreur(p, Lang.t("commande.la-parcelle") + Fmt.v(name) + Lang.t("commande.a-une-annonce-ou-un"), Lang.t("commande.retire-d-abord-l-annonce"));
                    return;
                }
                if (!plugin.gestion().deletePlot(zone, p)) {
                    Fmt.erreur(p, Lang.t("commande.la-parcelle") + Fmt.v(name) + Lang.t("commande.plot-not-deleted"), Lang.t("commande.plot-not-deleted-hint"));
                    return;
                }
                e.parcelle = null;
                e.changer();
                Fmt.envoyer(p, Lang.t("commande.plot-deleted") + Fmt.v(name) + "<t:texte>.</t>");
                plugin.ouvrirMenu(p, "parcelles");
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
                            Fmt.erreur(p, Lang.t("commande.tu-n-es-pas-dans") + Fmt.v(zone.getName()) + ".", Lang.t("commande.place-toi-la-ou-les"));
                            return;
                        }
                        plugin.gestion().definirSpawn(zone, p.getLocation());
                        Fmt.envoyer(p, Lang.t("commande.point-d-arrivee-de-la") + Fmt.v(zone.getName()) + Lang.t("commande.defini-ici"));
                    }
                    case "renommer" -> plugin.saisie().ouvrir(p, "Renommer la parcelle", "Nouveau nom de la parcelle " + zone.getName() + " (sans espace).",
                            "Nom", zone.getName(), 24, "parcelle", (pl, txt) -> {
                                String nom = txt.replace(' ', '_');
                                if (Gestion.renommer(zone, nom)) e.parcelle = nom;
                                else Fmt.erreur(pl, Lang.t("commande.nom-refuse-par-lands") + Fmt.v(nom) + ".");
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
                        if (plugin.gestion().retirerAnnonce(zone)) Fmt.envoyer(p, Lang.t("commande.annonce-de-la-parcelle") + Fmt.v(zone.getName()) + Lang.t("commande.retiree-de-l-agence"));
                        else Fmt.erreur(p, Lang.t("commande.aucune-annonce-a-retirer-sur"));
                    }
                    case "terminer" -> {
                        if (plugin.gestion().terminer(zone)) Fmt.envoyer(p, Lang.t("commande.location-ou-vente-de") + Fmt.v(zone.getName()) + Lang.t("commande.terminee"));
                        else Fmt.erreur(p, Lang.t("commande.personne-n-occupe-cette-parcelle"));
                    }
                    default -> p.sendMessage(Lang.t("commande.parcelle-ici-aller-spawn-renommer"));
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
        Dialogs.Form form = Dialogs.form(p, Lang.t("commande.nouvelle-parcelle"))
                .body(Lang.t("commande.comment-veux-tu-delimiter-la"))
                .body(Lang.t("commande.deux-coins-l-outil-de"))
                .body(Lang.t("commande.le-chunk-ou-je-suis"));
        if (!sel.isEmpty()) form.action("actuelle", Lang.t("commande.ma-selection-actuelle") + sel + ")");
        form.action("coins", Lang.t("commande.selectionner-deux-coins")).action("chunk", Lang.t("commande.le-chunk-ou-je-suis-2")).columns(1)
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

    /**
     * Starts a fresh Lands selection through its API: the tool and the particles, without Lands' own status titles
     * (they stayed on screen after a refusal, and only {@code /v selection} again took them off). Our action bar shows
     * the corners instead, and the name window opens once both are set inside the town.
     */
    private void lancerSelection(Player p, Land ma, String cible) {
        p.closeInventory();
        LandPlayer lp = plugin.villes().api().getLandPlayer(p.getUniqueId());
        if (lp == null) return;
        if (lp.getSelection() != null) lp.getSelection().disable();
        Selection.of(lp, true, false, true);
        warnedOutside.remove(p.getUniqueId());
        attenteSelection.put(p.getUniqueId(), System.currentTimeMillis() + 10 * 60_000L);
        attenteParcelle.put(p.getUniqueId(), cible);
        Fmt.envoyer(p, Lang.t("commande.clique-sur-deux-coins-opposes") + (cible.isEmpty() ? Lang.t("commande.ta-parcelle") : Fmt.v(cible))
                + Lang.t("commande.avec-l-outil-la-fenetre"));
    }

    /** Selects the 16 x 16 chunk under the player through Lands' API (no tool), then asks for the name. */
    private void selectionnerChunk(Player p, Land ma) {
        Land ici = plugin.villes().a(p.getLocation());
        if (ici == null || !Villes.id(ici).equals(Villes.id(ma))) {
            Fmt.erreur(p, Lang.t("commande.le-chunk-ou-tu-te") + Fmt.ville(ma.getName()) + ".");
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

    /**
     * Every half second while a mayor outlines a plot: action bar with both corners (a corner out of the town in red),
     * then the name window once both corners are set and the box stays inside the town. A box going out of the town
     * waits for the mayor to click a corner again. Ten minutes without it end the selection.
     */
    void verifierSelections() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> en : attenteSelection.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            Land ma = p != null ? plugin.villes().maVille(p) : null;
            Location[] c = p != null ? plugin.gestion().clickedCorners(p) : null;
            if (p == null || ma == null || c == null || en.getValue() < now) {
                attenteSelection.remove(en.getKey());
                attenteParcelle.remove(en.getKey());
                warnedOutside.remove(en.getKey());
                if (p != null) {
                    plugin.gestion().endSelection(p);
                    if (c != null) Fmt.erreur(p, Lang.t("commande.selection-expired"));
                }
                continue;
            }
            Fmt.barre(p, Lang.t("commande.corners-bar")
                    .replace("{1}", corner(ma, c[0], "commande.corner-left"))
                    .replace("{2}", corner(ma, c[1], "commande.corner-right")));
            if (c[0] == null || c[1] == null) continue;
            if (!Gestion.insideTown(ma, c[0], c[1])) {
                // told once per outline: the mayor fixes it by clicking a corner again
                if (warnedOutside.add(en.getKey())) {
                    fr.influencraft.flux.api.util.Sounds.cue(p, "error");
                    Fmt.erreur(p, Lang.t("gestion.plot-outside") + Fmt.ville(ma.getName()) + ".", Lang.t("commande.click-corner-again"));
                }
                continue;
            }
            attenteSelection.remove(en.getKey());
            warnedOutside.remove(en.getKey());
            String cible = attenteParcelle.remove(en.getKey());
            p.sendActionBar(net.kyori.adventure.text.Component.empty());
            fr.influencraft.flux.api.util.Sounds.cue(p, "success");
            nommerParcelle(p, ma, cible != null ? cible : "");
        }
    }

    /** One corner on the action bar: set (inside or out of the town), or the click that sets it. */
    private static String corner(Land town, Location at, String clickKey) {
        if (at == null) return Lang.t(clickKey);
        return Lang.t(Gestion.insideTown(town, at, null) ? "commande.corner-set" : "commande.corner-outside");
    }

    /** A mayor leaving the server while outlining a plot: his selection ends with him. */
    void forgetSelection(Player p) {
        attenteSelection.remove(p.getUniqueId());
        attenteParcelle.remove(p.getUniqueId());
        warnedOutside.remove(p.getUniqueId());
        plugin.gestion().endSelection(p);
    }

    private void nommerParcelle(Player p, Land ma) {
        nommerParcelle(p, ma, "");
    }

    /** Fenêtre de la parcelle : nom (prérempli pour délimiter une parcelle existante), hauteur, suite (Agence). */
    private void nommerParcelle(Player p, Land ma, String nomInitial) {
        int[] y = plugin.gestion().ySelection(p);
        String yTexte = y != null ? " Hauteur actuelle : Y " + y[0] + Lang.t("commande.a") + y[1] + "." : "";
        plugin.saisie().ouvrirParcelle(p, nomInitial.isEmpty() ? Lang.t("commande.nouvelle-parcelle") : Lang.t("commande.delimiter") + nomInitial,
                Lang.t("commande.selection") + plugin.gestion().selection(p) + "." + yTexte
                        + Lang.t("commande.coche-du-sol-au-ciel"),
                nomInitial, true, "", "", "parcelles",
                (pl, nom, solAuCiel, yMin, yMax, ensuite) -> assigner(pl, ma, nom, solAuCiel, yMin, yMax, ensuite),
                pl -> plugin.gestion().endSelection(pl));
    }

    /**
     * Creates the plot from the mayor's selection through Lands' API ({@link LandArea#of}, Lands 9.3+: the checks of
     * the command, no command typed for the player, no wait for the plot to appear). Height: the whole world height
     * ("Du sol au ciel") or the Y min / Y max typed in the window, set on the corners. An existing plot without an
     * outline still gets it from {@code /lands area <name> assign confirm}: the API cannot change the box of a plot.
     */
    private void assigner(Player p, Land ma, String nom, boolean toutLaHauteur, Integer yMin, Integer yMax, Saisie.Ensuite ensuite) {
        if (!p.isOnline()) return;
        Location[] corners = plugin.gestion().corners(p, toutLaHauteur, yMin, yMax);
        if (corners == null) {
            plugin.gestion().endSelection(p);
            Fmt.erreur(p, Lang.t("commande.ta-selection-n-est-plus"), Lang.t("commande.reselectionne-les-deux-coins-de"));
            plugin.ouvrirMenu(p, "parcelles");
            return;
        }
        p.closeInventory();
        if (!toutLaHauteur && (yMin != null || yMax != null)) {
            Fmt.envoyer(p, Lang.t("commande.hauteur-de-la-selection") + Fmt.v("Y " + corners[0].getBlockY() + Lang.t("commande.a") + corners[1].getBlockY()) + ".");
        }
        if (plugin.gestion().parcelle(ma, nom) != null) {
            editer(p, ma);
            p.performCommand("lands area " + nom + " assign confirm");
            attendreParcelle(p, ma, nom, ensuite, 0);
            return;
        }
        String refusal = plugin.gestion().plotRefusal(ma, corners);
        if (refusal != null) {
            plugin.gestion().endSelection(p);
            Fmt.erreur(p, refusal, Lang.t("commande.reselectionne-les-deux-coins-de"));
            plugin.ouvrirMenu(p, "parcelles");
            return;
        }
        LandPlayer lp = plugin.villes().api().getLandPlayer(p.getUniqueId());
        if (lp == null) return;
        java.util.concurrent.CompletableFuture<?> creation;
        try {
            creation = LandArea.of(ma, nom, corners[0], corners[1], lp);
        } catch (IllegalArgumentException invalidName) {
            plugin.gestion().endSelection(p);
            Fmt.erreur(p, Lang.t("commande.nom-refuse-par-lands") + Fmt.v(nom) + ".");
            plugin.ouvrirMenu(p, "parcelles");
            return;
        }
        // completes off the main thread (Lands saves the plot asynchronously); null = refused, without a reason
        creation.whenComplete((created, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            Area a = created instanceof Area area ? area : plugin.gestion().parcelle(ma, nom);
            plugin.gestion().endSelection(p);
            if (error != null || a == null) {
                if (error != null) plugin.getLogger().warning("Plot " + nom + " of " + ma.getName() + " not created: " + error);
                Fmt.erreur(p, Lang.t("commande.lands-n-a-pas-cree") + Fmt.v(nom) + ".", Lang.t("commande.plot-refused-hint"));
                plugin.ouvrirMenu(p, "parcelles");
                return;
            }
            parcelleCreee(p, ma, a, ensuite);
        }));
    }

    /** Plot ready: tells its size, then the plot's menu or the Agency window the mayor chose. */
    private void parcelleCreee(Player p, Land ma, Area a, Saisie.Ensuite ensuite) {
        plugin.vues().etat(p).parcelle = a.getName();
        Fmt.envoyer(p, Lang.t("commande.parcelle-creee") + Fmt.v(a.getName()) + " (" + Fmt.v(Gestion.dimensions(a)) + ").");
        if (ensuite == Saisie.Ensuite.RIEN) plugin.ouvrirMenu(p, "parcelle");
        else plugin.listings().open(p, ma, a, ensuite == Saisie.Ensuite.LOCATION);
    }

    /**
     * Outline given by the command to an existing plot: Lands saves it asynchronously, we look for it for 2 seconds
     * before concluding.
     */
    private void attendreParcelle(Player p, Land ma, String nom, Saisie.Ensuite ensuite, int essai) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            Area a = plugin.gestion().parcelle(ma, nom);
            if (a != null && Gestion.delimitee(a)) {
                plugin.gestion().endSelection(p);
                parcelleCreee(p, ma, a, ensuite);
            } else if (essai < 7) {
                attendreParcelle(p, ma, nom, ensuite, essai + 1);
            } else {
                plugin.gestion().endSelection(p);
                Fmt.erreur(p, Lang.t("commande.la-parcelle") + Fmt.v(nom) + Lang.t("commande.n-a-pas-recu-ta"), Lang.t("commande.elle-existe-voir-le-message"));
                plugin.ouvrirMenu(p, "parcelle");
            }
        }, 5L);
    }

    // ── Annonce en attente (remplie loin de la parcelle) ────────────────────

    /**
     * {@code annonce publier|abandonner} (buttons and chat link of a pending listing), and the player command
     * {@code /v annonce [plot] [location|vente]} (Interception): without a plot, places the pending listing where the
     * mayor stands, else opens the listing window of the nearest plot of their town; with a plot, opens its window.
     * A plot already listed gets its window prefilled and is placed again where the mayor stands (lost hologram).
     */
    private void annonce(Player p, String quoi, String type) {
        Land ma = gerable(p);
        if (ma == null) return;
        switch (quoi.toLowerCase(Locale.ROOT)) {
            case "publier" -> plugin.listings().place(p, ma);
            case "abandonner" -> plugin.listings().abandon(p);
            default -> annoncer(p, ma, quoi, type);
        }
    }

    /** Distance (blocks) within which {@code /v annonce} without a name finds a plot: the mayor stands in the street. */
    private static final int ANNONCE_PORTEE = 24;

    private void annoncer(Player p, Land ma, String nom, String type) {
        Vues.Etat e = plugin.vues().etat(p);
        if (nom.isEmpty() && e.brouillon != null) {
            plugin.listings().place(p, ma);
            return;
        }
        Area zone = nom.isEmpty() ? plugin.gestion().plusProche(ma, p.getLocation(), ANNONCE_PORTEE) : plugin.gestion().parcelle(ma, nom);
        if (zone == null) {
            if (nom.isEmpty()) {
                Fmt.erreur(p, Lang.t("commande.aucune-parcelle-de") + Fmt.ville(ma.getName()) + Lang.t("commande.a-moins-de") + ANNONCE_PORTEE + " blocs.",
                        Lang.t("commande.place-toi-juste-a-cote") + Fmt.cmd("/v annonce <parcelle>") + ".");
            } else {
                Fmt.erreur(p, Lang.t("commande.aucune-parcelle") + Fmt.v(nom) + " dans " + Fmt.ville(ma.getName()) + ".");
            }
            return;
        }
        e.parcelle = zone.getName();
        var offre = zone.getRentalOffer();
        boolean location = switch (type) {
            case "vente", "vendre", "sell" -> false;
            case "location", "louer", "rent" -> true;
            default -> offre == null || offre.getType() != me.angeschossen.lands.api.land.rental.offer.types.RentalType.SELL;
        };
        plugin.listings().open(p, ma, zone, location);
    }

    // ── Réglages de ma ville ────────────────────────────────────────────────

    private void reglages(Player p, String quoi) {
        Land ma = gerable(p);
        if (ma == null) return;
        switch (quoi) {
            case "renommer" -> plugin.saisie().ouvrir(p, "Renommer la ville", "Nouveau nom de " + ma.getName() + " (sans espace).",
                    Lang.t("commande.nom-de-la-ville"), ma.getName(), 24, "ville", (pl, txt) -> {
                        editer(pl, ma);
                        pl.performCommand("lands rename " + txt.replace(' ', '_'));
                        plugin.ouvrirMenu(pl, "ville");
                    });
            case "accueil" -> plugin.saisie().ouvrir(p, Lang.t("commande.accueil-titre"), Lang.t("commande.accueil-texte") + ma.getName() + ".",
                    Lang.t("commande.accueil-champ"), "", 48, "ville", (pl, txt) -> {
                        ma.setTitleMessage(txt);
                        Fmt.envoyer(pl, Lang.t("commande.message-d-accueil-de") + Fmt.ville(ma.getName()) + Lang.t("commande.mis-a-jour"));
                        plugin.ouvrirMenu(pl, "ville");
                    });
            case "spawn" -> {
                if (!ma.equals(plugin.villes().a(p.getLocation()))) {
                    Fmt.erreur(p, Lang.t("commande.tu-n-es-pas-dans-2") + Fmt.ville(ma.getName()) + ".", Lang.t("commande.place-toi-la-ou-le"));
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

    /**
     * Invites a player into the town through Lands' API (9.3+): same checks as {@code /lands trust}, Lands' messages
     * kept ({@code notify = true}) since they tell the reason of a refusal.
     */
    private void inviteResident(Player p, Land town, String name) {
        org.bukkit.OfflinePlayer target = Bukkit.getPlayerExact(name);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(name);
        LandPlayer lp = plugin.villes().api().getLandPlayer(p.getUniqueId());
        if (target == null || lp == null) {
            Fmt.erreur(p, Lang.t("commande.player-unknown") + Fmt.v(name) + ".");
            plugin.ouvrirMenu(p, "habitants");
            return;
        }
        // Lands' jar has no generics left: the future is raw
        java.util.concurrent.CompletableFuture<?> invite = me.angeschossen.lands.api.player.invite.type.TrustInvite.of(town, target.getUniqueId(), lp, true);
        invite.whenComplete((result, error) -> afterInvite(p, error, "habitants"));
    }

    /** Invites a town into the country through Lands' API (9.3+), Lands' messages kept as for a resident. */
    private void inviteTown(Player p, Nation country, String name) {
        Land town = plugin.villes().api().getLandByName(name.replace(' ', '_'));
        LandPlayer lp = plugin.villes().api().getLandPlayer(p.getUniqueId());
        if (town == null || lp == null) {
            Fmt.erreur(p, Lang.t("commande.town-unknown") + Fmt.v(name) + ".");
            plugin.ouvrirMenu(p, "pays");
            return;
        }
        java.util.concurrent.CompletableFuture<?> invite = me.angeschossen.lands.api.nation.invite.NationInvite.of(country, town, lp, true);
        invite.whenComplete((result, error) -> afterInvite(p, error, "pays"));
    }

    /** Back on the main thread once Lands answered an invitation: reopens the menu (Lands told the result). */
    private void afterInvite(Player p, Throwable error, String menu) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().warning("Invitation by " + p.getName() + " failed: " + error);
                if (p.isOnline()) Fmt.erreur(p, Lang.t("commande.invite-failed"));
            }
            if (p.isOnline()) plugin.ouvrirMenu(p, menu);
        });
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
                    Lang.t("commande.choisis-le-nom-de-ta"),
                    Lang.t("commande.nom-de-la-ville"), "", 24, "ville", (pl, txt) -> {
                        pl.performCommand("lands create " + txt.replace(' ', '_'));
                        plugin.ouvrirMenu(pl, "ville");
                    });
            case "creer_warp", "creer_adresse" -> plugin.saisie().ouvrir(p, Lang.t("commande.creer-un-warp"),
                    Lang.t("commande.un-warp-est-un-point"),
                    Lang.t("commande.nom-du-warp"), "", 16, "warps", (pl, txt) -> {
                        pl.closeInventory();
                        fr.influencraft.fluxpw.api.PlayerWarps.create(pl, txt.replace(' ', '_'));
                    });
            case "inviter" -> {
                if (ma == null) return;
                List<String> enLigne = new ArrayList<>();
                for (Player o : Bukkit.getOnlinePlayers()) if (!ma.isTrusted(o.getUniqueId())) enLigne.add(o.getName());
                enLigne.sort(String.CASE_INSENSITIVE_ORDER);
                plugin.saisie().choisir(p, Lang.t("commande.inviter-un-habitant"), Lang.t("commande.le-joueur-recevra-une-invitation") + ma.getName() + ".",
                        Lang.t("commande.joueur-connecte"), enLigne, Lang.t("commande.ou-tape-un-pseudo"), 16, "habitants",
                        (pl, txt) -> inviteResident(pl, ma, txt));
            }
            default -> p.sendMessage("saisie creer_ville|creer_warp|inviter");
        }
    }

    // ── Administration ──────────────────────────────────────────────────────

    private void admin(CommandSender s, String sous, String[] a) {
        switch (sous) {
            case "reload" -> {
                plugin.recharger();
                s.sendMessage(Lang.t("commande.fluxlands-recharge"));
            }
            case "index" -> {
                plugin.indexer();
                plugin.gestion().invalider();
                s.sendMessage("Index : " + plugin.echoppes().size() + Lang.t("commande.echoppes") + plugin.adresses().size() + " warps, "
                        + plugin.villes().toutes().size() + " villes, " + plugin.gestion().annonces().size() + " annonces.");
            }
            case "taux" -> {
                Land land = plugin.villes().parNom(a.length > 1 ? a[1] : "");
                if (land == null || a.length < 3) {
                    s.sendMessage(label() + Lang.t("commande.taux"));
                    return;
                }
                try {
                    plugin.registre().definirTaux(Villes.id(land), Double.parseDouble(a[2].replace(',', '.')));
                    s.sendMessage(Lang.t("commande.droit-de-place-de") + land.getName() + " : " + Fmt.taux(plugin.registre().taux(Villes.id(land))) + " %");
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
                    Fmt.erreur(p, Lang.t("commande.il-faut-etre-maire-d"), Lang.t("commande.ta-ville-en-sera-la"));
                    return;
                }
                plugin.saisie().ouvrir(p, Lang.t("commande.fonder-un-pays"), ma.getName() + Lang.t("commande.deviendra-la-capitale-du-pays"),
                        Lang.t("commande.nom-du-pays"), "", 24, "pays_accueil", (pl, txt) -> {
                            editer(pl, ma);
                            // Lands: /nations create <capital> <name> (a name alone is read as the capital town)
                            pl.performCommand("nations create " + ma.getName() + " " + txt.replace(' ', '_'));
                            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.ouvrirMenu(pl, plugin.pays().monPays(pl) != null ? "pays" : "pays_accueil"), 5L);
                        });
            }
            case "quitter" -> {
                if (ma == null || mon == null || !Villes.estMaire(ma, p.getUniqueId())) {
                    Fmt.erreur(p, Lang.t("commande.seul-le-maire-peut-faire"));
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
                    Fmt.erreur(p, Lang.t("commande.ta-ville-ne-fait-partie"));
                    return;
                }
                plugin.countryMenus().refresh(p);
                plugin.ouvrirMenu(p, quoi.equals("taxes") ? "pays_taxes" : "pays_effets");
            }
            case "taxe" -> plugin.countryMenus().askTax(p);
            case "effet" -> plugin.countryMenus().toggleEffect(p, option);
            case "accepter" -> {
                if (ma == null || !Villes.estMaire(ma, p.getUniqueId())) {
                    Fmt.erreur(p, Lang.t("commande.seul-le-maire-peut-faire-2"));
                    return;
                }
                // only the countries that really invited this town (read from Lands), accepted through its API
                Map<String, me.angeschossen.lands.api.nation.invite.NationInvite> invitations = plugin.pays().invitations(ma);
                if (invitations.isEmpty()) {
                    Fmt.erreur(p, Lang.t("commande.ta-ville") + Fmt.ville(ma.getName()) + Lang.t("commande.n-a-recu-aucune-invitation"),
                            Lang.t("commande.le-chef-d-un-pays"));
                    return;
                }
                List<String> noms = new ArrayList<>(invitations.keySet());
                noms.sort(String.CASE_INSENSITIVE_ORDER);
                Dialogs.form(p, Lang.t("commande.rejoindre-un-pays"))
                        .body(invitations.size() == 1 ? Lang.t("commande.un-pays-invite-ta-ville") + ma.getName() + Lang.t("commande.a-le-rejoindre")
                                : invitations.size() + Lang.t("commande.pays-invitent-ta-ville") + ma.getName() + Lang.t("commande.a-les-rejoindre"))
                        .body(Lang.t("commande.ta-ville-en-deviendra-membre"))
                        .choice("pays", "Pays qui t'invite", noms)
                        .buttons("Rejoindre", "Annuler")
                        .onSubmit((pl, ans) -> {
                            var invite = invitations.get(ans.text("pays"));
                            if (invite == null || !invite.accept()) {
                                Fmt.erreur(pl, Lang.t("commande.cette-invitation-n-est-plus"), Lang.t("commande.demande-au-chef-du-pays"));
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
                    Fmt.erreur(p, Lang.t("commande.seul-le-chef-du-pays-2"));
                    return;
                }
                editer(p, mon.getCapital() != null ? mon.getCapital() : ma);
                switch (quoi) {
                    case "inviter" -> {
                        List<String> libres = new ArrayList<>();
                        for (Land l : plugin.villes().toutes()) if (l.getNation() == null) libres.add(l.getName());
                        libres.sort(String.CASE_INSENSITIVE_ORDER);
                        plugin.saisie().choisir(p, Lang.t("commande.inviter-une-ville"), Lang.t("commande.la-ville-recevra-une-invitation") + mon.getName() + ".",
                                Lang.t("commande.ville-sans-pays"), libres, Lang.t("commande.ou-tape-un-nom-de"), 24, "pays",
                                (pl, txt) -> inviteTown(pl, mon, txt));
                    }
                    case "capitale" -> {
                        List<String> membres = new ArrayList<>();
                        for (Land l : Pays.villes(mon)) if (mon.getCapital() == null || !Villes.id(l).equals(Villes.id(mon.getCapital()))) membres.add(l.getName());
                        membres.sort(String.CASE_INSENSITIVE_ORDER);
                        if (membres.isEmpty()) {
                            Fmt.erreur(p, Lang.t("commande.aucune-autre-ville-du-pays"));
                            return;
                        }
                        plugin.saisie().choisir(p, Lang.t("commande.changer-de-capitale"), Lang.t("commande.la-capitale-paie-les-frais"),
                                "Nouvelle capitale", membres, Lang.t("commande.ou-tape-un-nom-de"), 24, "pays", (pl, txt) -> {
                                    pl.performCommand("nations member setcapital " + txt);
                                    plugin.ouvrirMenu(pl, "pays");
                                });
                    }
                    case "renommer" -> plugin.saisie().ouvrir(p, "Renommer le pays", "Nouveau nom de " + mon.getName() + " (sans espace).",
                            Lang.t("commande.nom-du-pays"), mon.getName(), 24, "pays", (pl, txt) -> {
                                pl.performCommand("nations rename " + txt.replace(' ', '_'));
                                plugin.ouvrirMenu(pl, "pays");
                            });
                    default -> p.sendMessage(Lang.t("commande.pays-spawn-inviter-capitale-renommer"));
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
