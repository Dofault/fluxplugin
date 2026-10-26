package fr.influencraft.fluxlands;

import me.angeschossen.lands.api.flags.type.NaturalFlag;
import me.angeschossen.lands.api.flags.type.RoleFlag;
import me.angeschossen.lands.api.land.Area;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.nation.Nation;
import me.angeschossen.lands.api.player.LandPlayer;
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
 * {@code /influlands} : navigation des menus (appelée par les boutons Flux, lancée par le
 * joueur) et administration.
 * <pre>
 *   ouvrir ville|annuaire|commerce|banque|reglages|parcelles|roles|parametres|agence
 *   ouvrir warps|echoppes [toutes|ma|fiche|miennes|ventes|rachats]
 *   ouvrir habitants|fiche [ma|ici|courante|&lt;ville&gt;]
 *   vue &lt;type&gt;                       liste suivie par un menu ouvert par sa propre commande
 *   page|filtre|tri|budget|taille +|-  recherche | effacer
 *   clic &lt;n&gt; gauche|droit|epingler   ligne n de la page affichée
 *   vitrine &lt;n&gt; gauche|droit          warp épinglé n de la ville consultée
 *   top &lt;n&gt;                          échoppe n du classement de ma ville
 *   droit +|-|++|--                    taux du droit de place de ma ville (maire)
 *   banque montant &lt;±N|=N&gt; | deposer | retirer | saisir
 *   parcelle ici|aller|spawn|renommer|annonce|retirer|terminer|habitants|nouvelle|delimiter|supprimer
 *   annonce type | prix &lt;±N&gt; | periode &lt;±N&gt; | duree &lt;±N&gt; | saisir | publier
 *   reglages renommer|accueil|spawn
 *   natif [arguments]                  ouvre un menu natif de Lands (sans interception)
 *   natifpays [arguments]              idem pour /nations
 *   retour                             menu d'origine de la liste affichée (option depuis:&lt;menu&gt; de ouvrir)
 *   ouvrir pays|pays_annuaire | pays_fiche [mon|courante|&lt;pays&gt;] | pays_villes [mon|fiche]
 *   pays spawn mon|fiche | inviter | capitale | renommer | creer | quitter | chat
 *   spawn ma|fiche | choisir | saisie creer_ville|creer_warp|inviter
 *   reload | index | taux &lt;ville&gt; &lt;taux&gt;  (influlands.admin)
 * </pre>
 */
final class Commande implements TabExecutor {

    private static final List<String> SOUS = List.of("ouvrir", "vue", "page", "filtre", "tri", "budget", "taille", "recherche",
            "effacer", "clic", "vitrine", "top", "droit", "banque", "parcelle", "annonce", "reglages", "natif", "spawn", "choisir",
            "saisie", "retour", "pays", "natifpays", "reload", "index", "taux");

    private final FluxLands plugin;
    /** Joueurs en train de sélectionner une parcelle : fin de l'attente (ms). */
    private final Map<UUID, Long> attenteSelection = new ConcurrentHashMap<>();
    /** Parcelle à délimiter dès que la sélection est complète ("" = nouvelle parcelle à nommer). */
    private final Map<UUID, String> attenteParcelle = new ConcurrentHashMap<>();

    Commande(FluxLands plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] a) {
        if (a.length == 0) {
            sender.sendMessage("/influlands ouvrir|page|filtre|tri|clic|banque|parcelle|annonce... (docs/technique/influlands.md)");
            return true;
        }
        String sous = a[0].toLowerCase(Locale.ROOT);
        if (sous.equals("reload") || sous.equals("index") || sous.equals("taux")) {
            if (!sender.hasPermission("influlands.admin")) {
                sender.sendMessage("Permission influlands.admin requise.");
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
                } while (e.filtre == 2 && (e.type == Vues.Type.WARPS || e.type == Vues.Type.ECHOPPES)
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
            case "droit" -> droit(p, arg(a, 1));
            case "banque" -> banque(p, e, arg(a, 1), arg(a, 2));
            case "parcelle" -> parcelle(p, e, arg(a, 1));
            case "annonce" -> annonce(p, e, arg(a, 1), arg(a, 2));
            case "reglages" -> reglages(p, arg(a, 1));
            case "natif" -> natif(p, "lands", a.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)) : "menu");
            case "natifpays" -> natif(p, "nations", a.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)) : "menu");
            case "spawn" -> {
                Land land = arg(a, 1).equals("fiche") ? plugin.villes().parId(e.fiche) : plugin.villes().maVille(p);
                if (land != null) {
                    p.closeInventory();
                    p.performCommand("lands spawn " + land.getName());
                }
            }
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
            case ECHOPPES -> "echoppes";
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
            case "parcelles", "roles", "parametres", "banque" -> "reglages";
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
            case "commerce", "banque", "reglages", "parcelle" -> {
                if (aUneVille(p)) plugin.ouvrirMenu(p, quoi);
            }
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
            case "echoppes" -> liste(p, e, Vues.Type.ECHOPPES, switch (option) {
                case "ma" -> 1;
                case "fiche" -> 2;
                case "miennes" -> 3;
                case "ventes" -> 4;
                case "rachats" -> 5;
                default -> 0;
            });
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
            default -> p.sendMessage("ouvrir ville|annuaire|commerce|banque|reglages|parcelles|roles|parametres|agence|warps|echoppes|habitants|fiche|pays|pays_annuaire|pays_fiche|pays_villes");
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
            case ECHOPPES -> {
                if (bouton.equals("droit")) ficheDe(p, e, l.get("ville_id"));
                else localiser(p, l);
            }
            case HABITANTS -> {
                // rien : la tête et le rôle suffisent
            }
            case AGENCE -> {
                if (bouton.equals("droit")) {
                    ficheDe(p, e, l.get("ville_id"));
                    return;
                }
                for (Gestion.Annonce an : plugin.gestion().annonces()) {
                    if (an.villeId().equals(l.get("ville_id")) && an.parcelle().equals(l.get("parcelle")) && an.lieu() != null) {
                        p.closeInventory();
                        p.teleportAsync(an.lieu());
                        Fmt.envoyer(p, "&#B376E5Annonce de la parcelle &f" + an.parcelle() + " &#B376E5à &f" + an.ville()
                                + "&#B376E5 : clique sur l'hologramme pour " + (an.location() ? "la louer." : "l'acheter."));
                        return;
                    }
                }
            }
            case PARCELLES -> {
                e.parcelle = l.get("nom");
                plugin.ouvrirMenu(p, "parcelle");
            }
            case ROLES -> {
                if (bouton.equals("droit")) return;
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

    /** Téléportation par InfluPW (droit d'entrée, mot de passe et délai gérés par lui). */
    private void allerA(Player p, String nomWarp) {
        if (nomWarp == null || nomWarp.isEmpty()) return;
        p.closeInventory();
        p.performCommand("pwarp " + nomWarp);
    }

    private void localiser(Player p, Map<String, String> l) {
        String warp = l.get("warp");
        if (warp != null && !warp.isEmpty()) {
            Fiches.Adresse a = null;
            for (Fiches.Adresse x : plugin.adresses()) if (x.titre().equals(warp) && x.villeId().equals(l.get("ville_id"))) a = x;
            if (a != null) {
                Fmt.envoyer(p, "&#B376E5L'échoppe de &f" + l.get("proprio") + " &#B376E5est à &f" + l.get("coord")
                        + "&#B376E5. Warp le plus proche : &f" + a.titre() + "&#B376E5.");
                allerA(p, a.nom());
                return;
            }
        }
        Land ville = plugin.villes().parId(l.get("ville_id"));
        if (ville != null && ville.getSpawnPosition() != null) {
            p.closeInventory();
            Fmt.envoyer(p, "&#B376E5Aucun warp près de l'échoppe de &f" + l.get("proprio") + "&#B376E5 : direction le spawn de &f"
                    + ville.getName() + "&#B376E5. L'échoppe est à &f" + l.get("coord") + "&#B376E5.");
            p.performCommand("lands spawn " + ville.getName());
            return;
        }
        Fmt.erreur(p, "Impossible de rejoindre cette échoppe : aucun warp ni spawn de ville à proximité. Elle est à "
                + l.get("coord") + " (" + l.get("monde") + ").");
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
            Fmt.erreur(p, "La vitrine est pleine (" + max + " warps) : retires-en un d'abord.");
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

    /** Localise une échoppe par son identifiant FluxChestShop (API, menu Marché de FluxAuctionHouse). */
    boolean localiserEchoppe(Player p, long shopId) {
        for (Fiches.Echoppe s : plugin.echoppes()) {
            if (s.id() == shopId) {
                localiser(p, plugin.vues().champsEchoppe(s));
                return true;
            }
        }
        return false;
    }

    /** Ouvre la fiche publique de la ville d'identifiant donné (API, menu Marché de FluxAuctionHouse). */
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
        if (!Villes.estMaire(ma, p.getUniqueId()) && !p.hasPermission("influlands.admin")) {
            Fmt.erreur(p, "Seul le maire de " + ma.getName() + " peut faire ça.");
            return null;
        }
        return ma;
    }

    private void droit(Player p, String sens) {
        Land ma = gerable(p);
        if (ma == null) return;
        double pas = plugin.getConfig().getDouble("droit-de-place.pas", 0.5);
        double rapide = plugin.getConfig().getDouble("droit-de-place.pas-rapide", 2.5);
        double delta = switch (sens) {
            case "+" -> pas;
            case "-" -> -pas;
            case "++" -> rapide;
            case "--" -> -rapide;
            default -> 0;
        };
        String id = Villes.id(ma);
        plugin.registre().definirTaux(id, plugin.registre().taux(id) + delta);
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

    // ── Banque ──────────────────────────────────────────────────────────────

    private void banque(Player p, Vues.Etat e, String quoi, String valeur) {
        Land ma = plugin.villes().maVille(p);
        if (ma == null) return;
        switch (quoi) {
            case "montant" -> {
                long v = Saisie.montant(valeur.replace("+", "").replace("=", "").replace("-", ""));
                if (v < 0) return;
                if (valeur.startsWith("=")) e.montant = v;
                else if (valeur.startsWith("-")) e.montant = Math.max(1, e.montant - v);
                else e.montant = Math.min(1_000_000_000L, e.montant + v);
            }
            case "deposer", "retirer" -> {
                editer(p, ma);
                p.performCommand("lands bank " + (quoi.equals("deposer") ? "deposit " : "withdraw ") + e.montant);
            }
            case "saisir" -> plugin.saisie().ouvrir(p, "Montant", "Montant à déposer ou à retirer de la banque de " + ma.getName() + ".",
                    "Montant (ex : 5000, 10k)", String.valueOf(e.montant), 12, "banque", (pl, txt) -> {
                        long m = Saisie.montant(txt);
                        if (m > 0) e.montant = m;
                        else Fmt.erreur(pl, "Montant invalide : " + txt);
                        plugin.ouvrirMenu(pl, "banque");
                    });
            default -> {
            }
        }
    }

    // ── Parcelles ───────────────────────────────────────────────────────────

    private void parcelle(Player p, Vues.Etat e, String quoi) {
        Land ma = plugin.villes().maVille(p);
        if (ma == null) return;
        if (quoi.equals("ici")) {
            Area a = ma.getArea(p.getLocation());
            if (a == null || a.isDefault()) {
                Fmt.erreur(p, "Tu n'es dans aucune parcelle de " + ma.getName() + ".");
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
                    Fmt.erreur(p, "Retire d'abord l'annonce ou mets fin à l'occupation de " + zone.getName() + ".");
                    return;
                }
                natif(p, "lands", "menu land areas");
                Fmt.envoyer(p, "&#B376E5Survole &f" + zone.getName() + "&#B376E5 dans la liste et appuie sur ta touche &fJeter &7(Q par défaut)&#B376E5 pour la supprimer.");
            }
            case "delimiter" -> {
                if (gerable(p) == null) return;
                if (plugin.gestion().selection(p).isEmpty()) {
                    p.closeInventory();
                    editer(p, ma);
                    p.performCommand("lands selection");
                    attenteSelection.put(p.getUniqueId(), System.currentTimeMillis() + 10 * 60_000L);
                    attenteParcelle.put(p.getUniqueId(), zone.getName());
                    Fmt.envoyer(p, "&#B376E5Sélectionne les deux coins de &f" + zone.getName() + "&#B376E5 : elle sera délimitée dès que ce sera fait.");
                } else {
                    nommerParcelle(p, ma, zone.getName());
                }
            }
            default -> {
                if (gerable(p) == null) return;
                switch (quoi) {
                    case "spawn" -> {
                        Area ici = ma.getArea(p.getLocation());
                        if (ici == null || !ici.getName().equalsIgnoreCase(zone.getName())) {
                            Fmt.erreur(p, "Place-toi dans la parcelle " + zone.getName() + " pour y poser son point d'arrivée.");
                            return;
                        }
                        plugin.gestion().definirSpawn(zone, p.getLocation());
                        Fmt.envoyer(p, "&#2ECF7BPoint d'arrivée de la parcelle " + zone.getName() + " défini ici.");
                    }
                    case "renommer" -> plugin.saisie().ouvrir(p, "Renommer la parcelle", "Nouveau nom de la parcelle " + zone.getName() + " (sans espace).",
                            "Nom", zone.getName(), 24, "parcelle", (pl, txt) -> {
                                String nom = txt.replace(' ', '_');
                                if (Gestion.renommer(zone, nom)) e.parcelle = nom;
                                else Fmt.erreur(pl, "Nom refusé par Lands : " + nom);
                                plugin.ouvrirMenu(pl, "parcelle");
                            });
                    case "annonce" -> {
                        if (zone.getRentalOffer() != null) {
                            Fmt.erreur(p, "Cette parcelle a déjà une annonce ou un occupant.");
                            return;
                        }
                        plugin.ouvrirMenu(p, "annonce");
                    }
                    case "retirer" -> {
                        if (plugin.gestion().retirerAnnonce(zone)) Fmt.envoyer(p, "&#2ECF7BAnnonce de la parcelle " + zone.getName() + " retirée.");
                        else Fmt.erreur(p, "Aucune annonce à retirer sur cette parcelle.");
                    }
                    case "terminer" -> {
                        if (plugin.gestion().terminer(zone)) Fmt.envoyer(p, "&#2ECF7BLocation ou vente de " + zone.getName() + " terminée.");
                        else Fmt.erreur(p, "Personne n'occupe cette parcelle.");
                    }
                    default -> p.sendMessage("parcelle ici|aller|spawn|renommer|annonce|retirer|terminer|habitants|nouvelle|delimiter|supprimer");
                }
            }
        }
    }

    /**
     * Création d'une parcelle : Lands la découpe dans la sélection du joueur. Sans sélection
     * complète, on lance le mode sélection de Lands ; avec, on demande le nom.
     */
    private void nouvelleParcelle(Player p, Land ma) {
        if (gerable(p) == null) return;
        if (plugin.gestion().selection(p).isEmpty()) {
            p.closeInventory();
            editer(p, ma);
            p.performCommand("lands selection");
            attenteSelection.put(p.getUniqueId(), System.currentTimeMillis() + 10 * 60_000L);
            attenteParcelle.put(p.getUniqueId(), "");
            Fmt.envoyer(p, "&#B376E5Sélectionne les deux coins de ta parcelle : la fenêtre du nom s'ouvrira toute seule.");
            return;
        }
        attenteSelection.remove(p.getUniqueId());
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
            if (cible != null && !cible.isEmpty()) {
                p.playSound(p.getLocation(), "minecraft:entity.experience_orb.pickup", 0.7f, 1.2f);
                nommerParcelle(p, ma, cible);
                continue;
            }
            p.playSound(p.getLocation(), "minecraft:entity.experience_orb.pickup", 0.7f, 1.2f);
            p.sendMessage(Fmt.ligne(p, "&#2ECF7BSélection prête (" + plugin.gestion().selection(p) + "). &#B376E5Fenêtre fermée par erreur ? ")
                    .append(Fmt.ligne(p, "&#FFD94D&n[Nommer la parcelle]")
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/influlands parcelle nouvelle"))));
            nommerParcelle(p, ma);
        }
    }

    private void nommerParcelle(Player p, Land ma) {
        nommerParcelle(p, ma, "");
    }

    /** Fenêtre de la parcelle : nom (prérempli pour délimiter une parcelle existante), hauteur. */
    private void nommerParcelle(Player p, Land ma, String nomInitial) {
        int[] y = plugin.gestion().ySelection(p);
        String yTexte = y != null ? " Hauteur actuelle : Y " + y[0] + " à " + y[1] + "." : "";
        plugin.saisie().ouvrirParcelle(p, nomInitial.isEmpty() ? "Nouvelle parcelle" : "Délimiter " + nomInitial,
                "Sélection : " + plugin.gestion().selection(p) + "." + yTexte
                        + " Coche « Du sol au ciel » ou indique un Y min et/ou un Y max pour l'étendre en profondeur ou en hauteur.",
                nomInitial, true, "", "", "parcelles",
                (pl, nom, solAuCiel, yMin, yMax) -> assigner(pl, ma, nom, solAuCiel, yMin, yMax));
    }

    /**
     * Crée la parcelle (si besoin) et lui donne la sélection du joueur, en une seule commande Lands
     * tapable à la main : {@code /lands area <nom> assign confirm} (nom avant la sous-commande ;
     * {@code confirm} passe la question « faible hauteur »). Hauteur : « Du sol au ciel » lance
     * {@code /lands selection expand} ; sinon Y min / Y max sont posés sur la sélection par l'API.
     */
    private void assigner(Player p, Land ma, String nom, boolean toutLaHauteur, Integer yMin, Integer yMax) {
        if (!p.isOnline()) return;
        if (plugin.gestion().selection(p).isEmpty()) {
            Fmt.erreur(p, "Ta sélection n'est plus complète : resélectionne les deux coins de la parcelle.");
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
                if (y != null) Fmt.envoyer(p, "&#B376E5Hauteur de la sélection : &fY " + y[0] + " à " + y[1] + "&#B376E5.");
            }
        }
        p.performCommand("lands area " + nom + " assign confirm");
        attendreParcelle(p, ma, nom, 0);
    }

    /** Lands peut enregistrer la parcelle avec un léger délai : on la guette 2 secondes avant de conclure. */
    private void attendreParcelle(Player p, Land ma, String nom, int essai) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            me.angeschossen.lands.api.land.Area a = plugin.gestion().parcelle(ma, nom);
            if (a != null && Gestion.delimitee(a)) {
                plugin.vues().etat(p).parcelle = nom;
                Fmt.envoyer(p, "&#2ECF7BParcelle " + nom + " créée : &f" + Gestion.dimensions(a) + "&#2ECF7B.");
                plugin.ouvrirMenu(p, "parcelle");
            } else if (essai < 7) {
                attendreParcelle(p, ma, nom, essai + 1);
            } else if (a != null) {
                plugin.vues().etat(p).parcelle = nom;
                Fmt.erreur(p, "La parcelle " + nom + " existe mais n'a pas reçu ta sélection (voir le message de Lands au-dessus).");
                plugin.ouvrirMenu(p, "parcelle");
            } else {
                Fmt.erreur(p, "Lands n'a pas créé la parcelle " + nom + " (voir son message au-dessus).");
                plugin.ouvrirMenu(p, "parcelles");
            }
        }, 5L);
    }

    // ── Annonce (brouillon puis publication) ────────────────────────────────

    private void annonce(Player p, Vues.Etat e, String quoi, String valeur) {
        Land ma = gerable(p);
        if (ma == null) return;
        long delta = Saisie.montant(valeur.replace("+", "").replace("-", ""));
        boolean moins = valeur.startsWith("-");
        switch (quoi) {
            case "type" -> e.location = !e.location;
            case "prix" -> {
                if (delta > 0) e.prix = Math.max(1, Math.min(1_000_000_000L, e.prix + (moins ? -delta : delta)));
            }
            case "periode" -> {
                if (delta > 0) e.periode = (int) Math.max(1, Math.min(e.duree, e.periode + (moins ? -delta : delta)));
            }
            case "duree" -> {
                if (delta > 0) e.duree = (int) Math.max(e.periode, Math.min(365, e.duree + (moins ? -delta : delta)));
            }
            case "saisir" -> plugin.saisie().ouvrir(p, "Prix de l'annonce", e.location ? "Prix de chaque période de location." : "Prix de vente de la parcelle.",
                    "Prix (ex : 5000, 10k)", String.valueOf(e.prix), 12, "annonce", (pl, txt) -> {
                        long m = Saisie.montant(txt);
                        if (m > 0) e.prix = m;
                        else Fmt.erreur(pl, "Prix invalide : " + txt);
                        plugin.ouvrirMenu(pl, "annonce");
                    });
            case "publier" -> {
                Area zone = plugin.gestion().parcelle(ma, e.parcelle);
                if (zone == null) return;
                String erreur = plugin.gestion().publier(p, ma, zone, e);
                if (erreur != null) {
                    Fmt.erreur(p, erreur);
                    return;
                }
                p.closeInventory();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (zone.getRentalOffer() != null) {
                        Fmt.envoyer(p, "&#2ECF7BAnnonce publiée : " + zone.getName() + (e.location ? " en location." : " en vente.")
                                + " Elle apparaît dans l'Agence.");
                    }
                }, 10L);
            }
            default -> {
            }
        }
    }

    // ── Réglages de ma ville ────────────────────────────────────────────────

    private void reglages(Player p, String quoi) {
        Land ma = gerable(p);
        if (ma == null) return;
        switch (quoi) {
            case "renommer" -> plugin.saisie().ouvrir(p, "Renommer la ville", "Nouveau nom de " + ma.getName() + " (sans espace).",
                    "Nom de la ville", ma.getName(), 24, "reglages", (pl, txt) -> {
                        editer(pl, ma);
                        pl.performCommand("lands rename " + txt.replace(' ', '_'));
                        plugin.ouvrirMenu(pl, "reglages");
                    });
            case "accueil" -> plugin.saisie().ouvrir(p, "Message d'accueil", "Titre affiché aux joueurs qui entrent dans " + ma.getName() + ".",
                    "Message", "", 48, "reglages", (pl, txt) -> {
                        ma.setTitleMessage(txt);
                        Fmt.envoyer(pl, "&#2ECF7BMessage d'accueil de " + ma.getName() + " mis à jour.");
                        plugin.ouvrirMenu(pl, "reglages");
                    });
            case "spawn" -> {
                if (!ma.equals(plugin.villes().a(p.getLocation()))) {
                    Fmt.erreur(p, "Place-toi dans " + ma.getName() + " pour y poser son spawn.");
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
            case "depot", "retrait" -> {
                if (ma != null) plugin.ouvrirMenu(p, "banque");
            }
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
                        pl.performCommand("pwarp create " + txt.replace(' ', '_'));
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
                    s.sendMessage("/influlands taux <ville> <taux en %>");
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
                    Fmt.erreur(p, "Il faut être maire d'une ville pour fonder un pays : ta ville en sera la capitale.");
                    return;
                }
                plugin.saisie().ouvrir(p, "Fonder un pays", ma.getName() + " deviendra la capitale du pays. Choisis son nom (sans espace).",
                        "Nom du pays", "", 24, "pays_accueil", (pl, txt) -> {
                            editer(pl, ma);
                            pl.performCommand("nations create " + txt.replace(' ', '_'));
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
            case "accepter" -> {
                if (ma == null || !Villes.estMaire(ma, p.getUniqueId())) {
                    Fmt.erreur(p, "Seul le maire peut faire rejoindre un pays à sa ville.");
                    return;
                }
                List<String> noms = new ArrayList<>();
                for (Nation n : plugin.pays().tous()) noms.add(n.getName());
                noms.sort(String.CASE_INSENSITIVE_ORDER);
                plugin.saisie().choisir(p, "Rejoindre un pays", "Accepte l'invitation reçue par " + ma.getName() + ".",
                        "Pays qui t'a invité", noms, "Ou tape un nom de pays", 24, "pays_accueil", (pl, txt) -> {
                            editer(pl, ma);
                            pl.performCommand("lands nation accept " + txt);
                            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.ouvrirMenu(pl, plugin.pays().monPays(pl) != null ? "pays" : "pays_accueil"), 5L);
                        });
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
