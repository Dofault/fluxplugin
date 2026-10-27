package fr.influencraft.fluxlands;

import me.angeschossen.lands.api.flags.enums.RoleFlagCategory;
import me.angeschossen.lands.api.flags.type.NaturalFlag;
import me.angeschossen.lands.api.flags.type.RoleFlag;
import me.angeschossen.lands.api.flags.type.parent.Flag;
import me.angeschossen.lands.api.land.Area;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.player.LandPlayer;
import me.angeschossen.lands.api.role.Role;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * État de navigation de chaque joueur (liste affichée, page, filtre, tri, recherche, ville
 * consultée, brouillons de la banque et des annonces) et calcul des lignes des listes. Une
 * liste est recalculée quand l'état change ou au plus une fois par seconde : les
 * placeholders d'une même page la relisent sans frais.
 */
final class Vues {

    enum Type { VILLES, WARPS, ECHOPPES, HABITANTS, AGENCE, PARCELLES, ROLES, DROITS, PARAMETRES, PAYS, PAYS_VILLES }

    static final Map<Type, List<String>> FILTRES = Map.ofEntries(
            Map.entry(Type.VILLES, List.of("Toutes", "Avec échoppes", "Avec warps", "Ma nation")),
            Map.entry(Type.WARPS, List.of("Tous", "Ma ville", "Ville consultée", "Mes warps")),
            Map.entry(Type.ECHOPPES, List.of("Toutes", "Ma ville", "Ville consultée", "Mes échoppes", "Ventes", "Rachats")),
            Map.entry(Type.HABITANTS, List.of("Tous", "En ligne")),
            Map.entry(Type.AGENCE, List.of("Toutes les annonces", "Locations", "Ventes", "Ma ville")),
            Map.entry(Type.PARCELLES, List.of("Toutes", "Libres", "En annonce", "Occupées")),
            Map.entry(Type.ROLES, List.of("Tous")),
            Map.entry(Type.DROITS, List.of("Actions", "Gestion")),
            Map.entry(Type.PARAMETRES, List.of("Tous", "Activés", "Désactivés")),
            Map.entry(Type.PAYS, List.of("Tous")),
            Map.entry(Type.PAYS_VILLES, List.of("Toutes", "En ligne")));

    static final Map<Type, List<String>> TRIS = Map.ofEntries(
            Map.entry(Type.VILLES, List.of("Niveau", "Habitants", "Commerce", "Nom")),
            Map.entry(Type.WARPS, List.of("Visites", "Note", "Récents", "Nom")),
            Map.entry(Type.ECHOPPES, List.of("Récentes", "Prix croissant", "Prix décroissant", "Droit reversé")),
            Map.entry(Type.HABITANTS, List.of("Rôle", "Nom")),
            Map.entry(Type.AGENCE, List.of("Prix croissant", "Prix décroissant", "Taille croissante", "Taille décroissante")),
            Map.entry(Type.PARCELLES, List.of("Nom", "Taille")),
            Map.entry(Type.ROLES, List.of("Priorité")),
            Map.entry(Type.DROITS, List.of("Nom")),
            Map.entry(Type.PARAMETRES, List.of("Nom")),
            Map.entry(Type.PAYS, List.of("Niveau", "Villes", "Habitants", "Nom")),
            Map.entry(Type.PAYS_VILLES, List.of("Niveau", "Habitants", "Nom")));

    /** Budget maximum de l'Agence (0 = tous). */
    static final double[] BUDGETS = {0, 10_000, 50_000, 200_000, -200_000};
    static final String[] BUDGETS_NOMS = {"Tous les prix", "Jusqu'à 10 000", "Jusqu'à 50 000", "Jusqu'à 200 000", "Plus de 200 000"};
    /** Tailles de l'Agence, en blocs. */
    static final String[] TAILLES_NOMS = {"Toutes les tailles", "Petites (moins de 500 blocs)", "Moyennes (500 à 5 000 blocs)", "Grandes (plus de 5 000 blocs)"};

    static final class Etat {
        Type type = Type.VILLES;
        int page;
        int filtre;
        int tri;
        String recherche = "";
        /** Ville consultée (fiche, habitants, filtre « Ville consultée »). */
        String fiche = "";
        /** Pays consulté (fiche, villes du pays). */
        String paysFiche = "";
        /** Menu ouvert par le bouton Retour des listes (clé de config menus.*). */
        String retour = "";
        /** Parcelle choisie dans ma ville (nom Lands). */
        String parcelle = "";
        /** Rôle choisi dans ma ville (ULID). */
        String role = "";
        /** Agence : tranche de budget et de taille. */
        int budget;
        int taille;
        /** Banque : montant préparé. */
        long montant = 1000;
        /** Brouillon d'annonce de la parcelle choisie. */
        boolean location;
        long prix = 5000;
        int periode = 7;
        int duree = 28;
        List<Map<String, String>> lignes = List.of();
        long calcule;
        boolean sale = true;

        void changer() {
            sale = true;
        }
    }

    private final FluxLands plugin;
    private final Map<UUID, Etat> etats = new ConcurrentHashMap<>();

    Vues(FluxLands plugin) {
        this.plugin = plugin;
    }

    Etat etat(Player p) {
        return etats.computeIfAbsent(p.getUniqueId(), k -> new Etat());
    }

    void oublier(UUID joueur) {
        etats.remove(joueur);
    }

    int taillePage() {
        return Math.max(1, plugin.getConfig().getInt("taille-page", 21));
    }

    /** Lignes de la liste courante (toutes pages). */
    List<Map<String, String>> lignes(Player p) {
        Etat e = etat(p);
        long now = System.currentTimeMillis();
        if (e.sale || now - e.calcule > 1000) {
            e.lignes = calculer(p, e);
            e.calcule = now;
            e.sale = false;
            int pages = pages(e);
            if (e.page >= pages) e.page = Math.max(0, pages - 1);
        }
        return e.lignes;
    }

    int pages(Etat e) {
        return Math.max(1, (e.lignes.size() + taillePage() - 1) / taillePage());
    }

    /** Ligne n (1 à taille de page) de la page affichée, ou null. */
    Map<String, String> ligne(Player p, int n) {
        List<Map<String, String>> l = lignes(p);
        int i = etat(p).page * taillePage() + n - 1;
        return n >= 1 && i >= 0 && i < l.size() ? l.get(i) : null;
    }

    // ── Calcul des listes ───────────────────────────────────────────────────

    private List<Map<String, String>> calculer(Player p, Etat e) {
        try {
            return switch (e.type) {
                case VILLES -> villes(p, e);
                case WARPS -> warps(p, e);
                case ECHOPPES -> echoppes(p, e);
                case HABITANTS -> habitants(p, e);
                case AGENCE -> agence(p, e);
                case PARCELLES -> parcelles(p, e);
                case ROLES -> roles(p);
                case DROITS -> droits(p, e);
                case PARAMETRES -> parametres(p, e);
                case PAYS -> pays(p, e);
                case PAYS_VILLES -> paysVilles(p, e);
            };
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Liste " + e.type + " de " + p.getName() + " en échec : " + ex);
            return List.of();
        }
    }

    private boolean cherche(Etat e, String... champs) {
        if (e.recherche.isEmpty()) return true;
        String q = e.recherche.toLowerCase(Locale.ROOT);
        for (String c : champs) if (c != null && c.toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    private List<Map<String, String>> villes(Player p, Etat e) {
        Map<String, int[]> compte = plugin.comptes();
        Land maVille = plugin.villes().maVille(p);
        String maNation = maVille != null && maVille.getNation() != null ? maVille.getNation().getName() : null;
        List<Land> liste = new ArrayList<>();
        for (Land land : plugin.villes().toutes()) {
            String id = Villes.id(land);
            int[] c = compte.getOrDefault(id, new int[2]);
            boolean garde = switch (e.filtre) {
                case 1 -> c[0] > 0;
                case 2 -> c[1] > 0;
                case 3 -> maNation != null && land.getNation() != null && maNation.equals(land.getNation().getName());
                default -> true;
            };
            if (garde && cherche(e, land.getName(), Villes.nomJoueur(land.getOwnerUID()))) liste.add(land);
        }
        Comparator<Land> cmp = switch (e.tri) {
            case 1 -> Comparator.comparingInt((Land l) -> l.getTrustedPlayers().size()).reversed();
            case 2 -> Comparator.comparingDouble((Land l) -> plugin.registre().recette(Villes.id(l)).depuis(7)).reversed();
            case 3 -> Comparator.comparing((Land l) -> l.getName().toLowerCase(Locale.ROOT));
            default -> Comparator.comparingInt(Villes::niveau).reversed()
                    .thenComparing(Comparator.comparingInt((Land l) -> l.getTrustedPlayers().size()).reversed());
        };
        liste.sort(cmp);
        List<Map<String, String>> l = new ArrayList<>(liste.size());
        for (Land land : liste) l.add(plugin.champsVille(land, p));
        return l;
    }

    private List<Map<String, String>> warps(Player p, Etat e) {
        List<Fiches.Adresse> src = plugin.adresses();
        Land ma = plugin.villes().maVille(p);
        String maId = ma != null ? Villes.id(ma) : "-";
        List<Fiches.Adresse> liste = new ArrayList<>();
        for (Fiches.Adresse a : src) {
            boolean mienne = p.getUniqueId().equals(a.proprio());
            if (!a.ouverte() && !mienne) continue;
            boolean garde = switch (e.filtre) {
                case 1 -> maId.equals(a.villeId());
                case 2 -> e.fiche.equals(a.villeId());
                case 3 -> mienne;
                default -> true;
            };
            if (garde && cherche(e, a.nom(), a.titre(), a.proprioNom(), a.villeNom(), a.categorie())) liste.add(a);
        }
        Comparator<Fiches.Adresse> cmp = switch (e.tri) {
            case 1 -> Comparator.comparingDouble((Fiches.Adresse a) -> a.note()).reversed();
            case 2 -> Comparator.comparingLong((Fiches.Adresse a) -> a.creation()).reversed();
            case 3 -> Comparator.comparing((Fiches.Adresse a) -> a.titre().toLowerCase(Locale.ROOT));
            default -> Comparator.comparingInt((Fiches.Adresse a) -> a.visites()).reversed();
        };
        liste.sort(cmp);
        List<String> vitrine = ma != null ? plugin.registre().vitrine(maId) : List.of();
        List<Map<String, String>> l = new ArrayList<>(liste.size());
        for (Fiches.Adresse a : liste) l.add(champsWarp(a, p, vitrine.contains(a.id()), ma));
        return l;
    }

    Map<String, String> champsWarp(Fiches.Adresse a, Player p, boolean epinglee, Land ma) {
        Map<String, String> m = new HashMap<>();
        m.put("existe", "oui");
        m.put("id", a.id());
        m.put("nom", a.nom());
        m.put("titre", a.titre());
        m.put("proprio", a.proprioNom() != null ? a.proprioNom() : Villes.nomJoueur(a.proprio()));
        m.put("ville", a.villeNom().isEmpty() ? "Hors ville" : a.villeNom());
        m.put("en_ville", Fmt.oui(!a.villeId().isEmpty()));
        m.put("ville_id", a.villeId());
        m.put("visites", Fmt.argent(a.visites()));
        m.put("note", Fiches.etoiles(a.note()));
        m.put("prix", a.prix() > 0 ? Fmt.argent(a.prix()) : "");
        m.put("payante", Fmt.oui(a.prix() > 0));
        // Ligne de lore complète (masquée par Flux si vide) : glyphe BitBlock brut U+E023, le
        // résultat d'un placeholder n'étant pas relu par PlaceholderAPI.
        m.put("prix_ligne", a.prix() > 0 ? "&#B376E5Entrée : &f" + Fmt.argent(a.prix()) + " &r&f" : "");
        m.put("icone", a.icone());
        m.put("categorie", a.categorie() == null ? "" : a.categorie());
        m.put("mot_de_passe", Fmt.oui(a.motDePasse()));
        m.put("epinglee", Fmt.oui(epinglee));
        m.put("mienne", Fmt.oui(p.getUniqueId().equals(a.proprio())));
        m.put("coord", Fiches.coord(a.lieu()));
        m.put("monde", a.lieu() != null && a.lieu().getWorld() != null ? a.lieu().getWorld().getName() : "");
        // Le maire peut épingler un warp de sa ville sur la fiche de la ville.
        m.put("epinglable", Fmt.oui(ma != null && Villes.estMaire(ma, p.getUniqueId()) && Villes.id(ma).equals(a.villeId())));
        List<String> desc = Fmt.couper(a.description());
        for (int i = 0; i < 3; i++) m.put("desc_" + (i + 1), i < desc.size() ? desc.get(i) : "");
        return m;
    }

    private List<Map<String, String>> echoppes(Player p, Etat e) {
        List<Fiches.Echoppe> src = plugin.echoppes();
        Land ma = plugin.villes().maVille(p);
        String maId = ma != null ? Villes.id(ma) : "-";
        List<Fiches.Echoppe> liste = new ArrayList<>();
        for (Fiches.Echoppe s : src) {
            // Échoppes vides / rachats pleins : visibles seulement dans « mes échoppes » (pour les remplir).
            if (!s.active() && e.filtre != 3) continue;
            boolean garde = switch (e.filtre) {
                case 1 -> maId.equals(s.villeId());
                case 2 -> e.fiche.equals(s.villeId());
                case 3 -> p.getUniqueId().equals(s.proprio());
                case 4 -> !s.rachat();
                case 5 -> s.rachat();
                default -> true;
            };
            if (garde && cherche(e, s.objet(), s.proprioNom(), s.villeNom())) liste.add(s);
        }
        Comparator<Fiches.Echoppe> cmp = switch (e.tri) {
            case 1 -> Comparator.comparingDouble(Fiches.Echoppe::prix);
            case 2 -> Comparator.comparingDouble(Fiches.Echoppe::prix).reversed();
            case 3 -> Comparator.comparingDouble((Fiches.Echoppe s) -> droitReverse(s)).reversed();
            default -> Comparator.comparingLong(Fiches.Echoppe::id).reversed();
        };
        liste.sort(cmp);
        List<Map<String, String>> l = new ArrayList<>(liste.size());
        for (Fiches.Echoppe s : liste) l.add(champsEchoppe(s));
        return l;
    }

    double droitReverse(Fiches.Echoppe s) {
        if (s.villeId().isEmpty()) return 0;
        return plugin.registre().recette(s.villeId()).parEchoppe.getOrDefault(s.id(), 0.0);
    }

    Map<String, String> champsEchoppe(Fiches.Echoppe s) {
        Map<String, String> m = new HashMap<>();
        m.put("existe", "oui");
        m.put("id", String.valueOf(s.id()));
        m.put("icone", s.icone());
        m.put("objet", s.objet());
        m.put("quantite", String.valueOf(s.quantite()));
        m.put("prix", Fmt.argent(s.prix()));
        m.put("prix_unite", Fmt.argent(s.quantite() > 0 ? s.prix() / s.quantite() : s.prix()));
        m.put("type", s.rachat() ? "Rachat" : "Vente");
        m.put("rachat", Fmt.oui(s.rachat()));
        m.put("stock", s.stock() == Integer.MAX_VALUE ? "illimité" : s.stock() < 0 ? "?" : String.valueOf(s.stock()));
        m.put("vide", Fmt.oui(s.stock() == 0));
        // Ligne masquée (vide) tant que l'échoppe est active ; visible du propriétaire dans « mes échoppes ».
        m.put("statut", s.active() ? "" : "&#FF5C5C" + (s.rachat() ? "Pleine" : "Vide") + " : cachée du public");
        m.put("proprio", s.proprioNom() == null ? "?" : s.proprioNom());
        m.put("ville", s.villeId().isEmpty() ? "Hors ville" : s.villeNom());
        m.put("en_ville", Fmt.oui(!s.villeId().isEmpty()));
        m.put("ville_id", s.villeId());
        m.put("taux", s.villeId().isEmpty() ? "0" : Fmt.taux(plugin.registre().taux(s.villeId())));
        m.put("reverse", Fmt.argent(droitReverse(s)));
        m.put("coord", Fiches.coord(s.lieu()));
        m.put("monde", s.lieu() != null && s.lieu().getWorld() != null ? s.lieu().getWorld().getName() : "");
        Fiches.Adresse proche = plugin.adresseProche(s);
        m.put("warp", proche != null ? proche.titre() : "");
        return m;
    }

    private List<Map<String, String>> habitants(Player p, Etat e) {
        Land land = plugin.villes().parId(e.fiche);
        if (land == null) return List.of();
        List<UUID> liste = new ArrayList<>();
        for (Object o : land.getTrustedPlayers()) {
            UUID u = (UUID) o;
            if (e.filtre == 1 && Bukkit.getPlayer(u) == null) continue;
            if (cherche(e, Villes.nomJoueur(u))) liste.add(u);
        }
        Comparator<UUID> parNom = Comparator.comparing((UUID u) -> Villes.nomJoueur(u).toLowerCase(Locale.ROOT));
        if (e.tri == 1) liste.sort(parNom);
        else liste.sort(Comparator.comparing((UUID u) -> !Villes.estMaire(land, u))
                .thenComparing((UUID u) -> Villes.role(land, u)).thenComparing(parNom));
        List<Map<String, String>> l = new ArrayList<>(liste.size());
        for (UUID u : liste) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("existe", "oui");
            m.put("nom", Villes.nomJoueur(u));
            m.put("tete", plugin.tetes().materiau(u));
            m.put("role", Villes.role(land, u));
            m.put("maire", Fmt.oui(Villes.estMaire(land, u)));
            m.put("en_ligne", Fmt.oui(Bukkit.getPlayer(u) != null));
            m.put("statut", Bukkit.getPlayer(u) != null ? "&#2ECF7B● En ligne" : "&7● Hors ligne");
            l.add(m);
        }
        return l;
    }

    // ── Agence : annonces de location et de vente de toutes les villes ──────

    private List<Map<String, String>> agence(Player p, Etat e) {
        Land ma = plugin.villes().maVille(p);
        List<Gestion.Annonce> liste = new ArrayList<>();
        for (Gestion.Annonce a : plugin.gestion().annonces()) {
            boolean garde = switch (e.filtre) {
                case 1 -> a.location();
                case 2 -> !a.location();
                case 3 -> ma != null && Villes.id(ma).equals(a.villeId());
                default -> true;
            };
            double b = BUDGETS[e.budget];
            if (b > 0 && a.prix() > b) garde = false;
            if (b < 0 && a.prix() <= -b) garde = false;
            garde &= switch (e.taille) {
                case 1 -> a.taille() < 500;
                case 2 -> a.taille() >= 500 && a.taille() <= 5000;
                case 3 -> a.taille() > 5000;
                default -> true;
            };
            if (garde && cherche(e, a.ville(), a.parcelle(), a.maire())) liste.add(a);
        }
        Comparator<Gestion.Annonce> cmp = switch (e.tri) {
            case 1 -> Comparator.comparingDouble(Gestion.Annonce::prix).reversed();
            case 2 -> Comparator.comparingLong(Gestion.Annonce::taille);
            case 3 -> Comparator.comparingLong(Gestion.Annonce::taille).reversed();
            default -> Comparator.comparingDouble(Gestion.Annonce::prix);
        };
        liste.sort(cmp);
        List<Map<String, String>> l = new ArrayList<>(liste.size());
        for (Gestion.Annonce a : liste) l.add(plugin.gestion().champsAnnonce(a));
        return l;
    }

    // ── Gestion de ma ville : parcelles, rôles, permissions, paramètres ─────

    private List<Map<String, String>> parcelles(Player p, Etat e) {
        Land ma = plugin.villes().maVille(p);
        if (ma == null) return List.of();
        List<Area> liste = new ArrayList<>();
        for (Area a : plugin.gestion().parcelles(ma)) {
            Map<String, String> c = plugin.gestion().champsParcelle(a);
            boolean garde = switch (e.filtre) {
                case 1 -> c.get("etat_code").equals("libre");
                case 2 -> c.get("etat_code").equals("annonce");
                case 3 -> c.get("etat_code").equals("occupee");
                default -> true;
            };
            if (garde && cherche(e, a.getName())) liste.add(a);
        }
        if (e.tri == 1) liste.sort(Comparator.comparingLong((Area a) -> plugin.gestion().volume(a)).reversed());
        else liste.sort(Comparator.comparing((Area a) -> a.getName().toLowerCase(Locale.ROOT)));
        List<Map<String, String>> l = new ArrayList<>(liste.size());
        for (Area a : liste) l.add(plugin.gestion().champsParcelle(a));
        return l;
    }

    private List<Map<String, String>> roles(Player p) {
        Land ma = plugin.villes().maVille(p);
        if (ma == null) return List.of();
        List<Role> liste = new ArrayList<>();
        for (Object o : ma.getDefaultArea().getRoles()) if (o instanceof Role r) liste.add(r);
        liste.sort(Comparator.comparingInt(Role::getPriority).reversed());
        List<Map<String, String>> l = new ArrayList<>(liste.size());
        for (Role r : liste) {
            Map<String, String> m = new HashMap<>();
            m.put("existe", "oui");
            m.put("id", r.getULID().toString());
            m.put("nom", Couleurs.legacy(r.getColorName()));
            m.put("nom_brut", r.getName());
            m.put("icone", Fiches.materiau(r.getIcon()));
            m.put("type", Gestion.typeRole(r));
            m.put("priorite", String.valueOf(r.getPriority()));
            m.put("droits", String.valueOf(r.getActionFlags().size() + r.getManagementFlags().size()));
            m.put("modifiable", Fmt.oui(r.getType() != me.angeschossen.lands.api.role.enums.RoleType.OWNER));
            l.add(m);
        }
        return l;
    }

    private List<Map<String, String>> droits(Player p, Etat e) {
        Land ma = plugin.villes().maVille(p);
        Role role = plugin.gestion().role(ma, e.role);
        if (role == null) return List.of();
        LandPlayer lp = plugin.villes().api().getLandPlayer(p.getUniqueId());
        RoleFlagCategory cat = e.filtre == 1 ? RoleFlagCategory.MANAGEMENT : RoleFlagCategory.ACTION;
        List<RoleFlag> liste = new ArrayList<>();
        for (Object o : plugin.villes().api().getFlagRegistry().getRoleFlags(cat)) {
            if (o instanceof RoleFlag f && visible(f, ma.getDefaultArea(), lp) && cherche(e, f.getDisplayName(), f.getName())) liste.add(f);
        }
        liste.sort(Comparator.comparing((RoleFlag f) -> Gestion.nu(f.getDisplayName()).toLowerCase(Locale.ROOT)));
        List<Map<String, String>> l = new ArrayList<>(liste.size());
        for (RoleFlag f : liste) l.add(champsDrapeau(f, role.hasFlag(f)));
        return l;
    }

    private List<Map<String, String>> parametres(Player p, Etat e) {
        Land ma = plugin.villes().maVille(p);
        if (ma == null) return List.of();
        Area zone = ma.getDefaultArea();
        LandPlayer lp = plugin.villes().api().getLandPlayer(p.getUniqueId());
        List<NaturalFlag> liste = new ArrayList<>();
        for (Object o : plugin.villes().api().getFlagRegistry().getNaturalFlags()) {
            if (!(o instanceof NaturalFlag f) || !visible(f, zone, lp)) continue;
            boolean actif = zone.hasNaturalFlag(f);
            if ((e.filtre == 1 && !actif) || (e.filtre == 2 && actif)) continue;
            if (cherche(e, f.getDisplayName(), f.getName())) liste.add(f);
        }
        liste.sort(Comparator.comparing((NaturalFlag f) -> Gestion.nu(f.getDisplayName()).toLowerCase(Locale.ROOT)));
        List<Map<String, String>> l = new ArrayList<>(liste.size());
        for (NaturalFlag f : liste) l.add(champsDrapeau(f, zone.hasNaturalFlag(f)));
        return l;
    }

    private static boolean visible(Flag f, Area zone, LandPlayer lp) {
        try {
            return f.isDisplay() && (lp == null || f.shouldDisplay(zone, lp));
        } catch (RuntimeException ex) {
            return f.isDisplay();
        }
    }

    private static Map<String, String> champsDrapeau(Flag f, boolean actif) {
        Map<String, String> m = new HashMap<>();
        m.put("existe", "oui");
        m.put("id", f.getName());
        m.put("nom", Gestion.nu(f.getDisplayName()));
        m.put("icone", Fiches.materiau(f.getIcon()));
        m.put("actif", Fmt.oui(actif));
        m.put("etat", actif ? "&#2ECF7B✔ Autorisé" : "&#FF5C5C✖ Interdit");
        List<String> desc = new ArrayList<>();
        try {
            for (Object o : f.getDescription()) desc.addAll(Fmt.couper(Gestion.nu(String.valueOf(o))));
        } catch (RuntimeException ignored) {
        }
        for (int i = 0; i < 4; i++) m.put("desc_" + (i + 1), i < desc.size() ? desc.get(i) : "");
        return m;
    }

    // ── Pays ────────────────────────────────────────────────────────────────

    private List<Map<String, String>> pays(Player p, Etat e) {
        List<me.angeschossen.lands.api.nation.Nation> liste = new ArrayList<>();
        for (me.angeschossen.lands.api.nation.Nation n : plugin.pays().tous()) {
            if (cherche(e, n.getName(), Villes.nomJoueur(n.getOwnerUID()),
                    n.getCapital() != null ? n.getCapital().getName() : "")) liste.add(n);
        }
        Comparator<me.angeschossen.lands.api.nation.Nation> cmp = switch (e.tri) {
            case 1 -> Comparator.comparingInt((me.angeschossen.lands.api.nation.Nation n) -> Pays.villes(n).size()).reversed();
            case 2 -> Comparator.comparingInt((me.angeschossen.lands.api.nation.Nation n) -> Pays.habitants(n)).reversed();
            case 3 -> Comparator.comparing((me.angeschossen.lands.api.nation.Nation n) -> n.getName().toLowerCase(Locale.ROOT));
            default -> Comparator.comparingInt(Pays::niveau).reversed()
                    .thenComparing(Comparator.comparingInt((me.angeschossen.lands.api.nation.Nation n) -> Pays.villes(n).size()).reversed());
        };
        liste.sort(cmp);
        List<Map<String, String>> l = new ArrayList<>(liste.size());
        for (me.angeschossen.lands.api.nation.Nation n : liste) l.add(plugin.pays().champs(n, p));
        return l;
    }

    private List<Map<String, String>> paysVilles(Player p, Etat e) {
        me.angeschossen.lands.api.nation.Nation n = plugin.pays().parId(e.paysFiche);
        if (n == null) return List.of();
        Land cap = n.getCapital();
        List<Land> liste = new ArrayList<>();
        for (Land land : Pays.villes(n)) {
            if (e.filtre == 1 && land.getOnlinePlayers().isEmpty()) continue;
            if (cherche(e, land.getName(), Villes.nomJoueur(land.getOwnerUID()))) liste.add(land);
        }
        Comparator<Land> cmp = switch (e.tri) {
            case 1 -> Comparator.comparingInt((Land l) -> l.getTrustedPlayers().size()).reversed();
            case 2 -> Comparator.comparing((Land l) -> l.getName().toLowerCase(Locale.ROOT));
            default -> Comparator.comparingInt(Villes::niveau).reversed();
        };
        liste.sort(Comparator.comparing((Land l) -> cap == null || !Villes.id(l).equals(Villes.id(cap))).thenComparing(cmp));
        boolean chef = Pays.estChef(n, p);
        List<Map<String, String>> l = new ArrayList<>(liste.size());
        for (Land land : liste) {
            Map<String, String> m = plugin.champsVille(land, p);
            boolean capitale = cap != null && Villes.id(land).equals(Villes.id(cap));
            m.put("capitale", Fmt.oui(capitale));
            m.put("statut_pays", capitale ? "&#F0A800✦ Capitale" : "&#B376E5Ville membre");
            m.put("retirable", Fmt.oui(chef && !capitale));
            l.add(m);
        }
        return l;
    }
}
