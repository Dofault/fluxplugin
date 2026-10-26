package fr.influencraft.influmarche;

import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.ram.ItemNote;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static fr.influencraft.influmarche.Fmt.*;

/**
 * Contenu des menus : listes (marché, mes ventes, mes enchères, historique), offre détaillée,
 * brouillon de mise en vente, prix moyen. Chaque entrée de liste porte son objet (affiché par la
 * source FluxMenu {@code marche}) et ses lignes de description déjà mises en forme (style /menu2),
 * lues par les placeholders {@code %influmarche_liste_<n>_l<k>%}.
 */
final class Vues {

    /** Ligne d'une liste : objet affiché, description, brillance, et ce qu'elle désigne (offre, note, vente). */
    record Entree(String id, ItemStack item, List<String> lore, boolean brille, Object cible) {
    }

    static final int LIGNES = 12;

    private final InfluMarche plugin;
    private final Map<UUID, Etat> etats = new ConcurrentHashMap<>();

    Vues(InfluMarche plugin) {
        this.plugin = plugin;
    }

    Etat etat(Player p) {
        return etats.computeIfAbsent(p.getUniqueId(), k -> new Etat());
    }

    void oublier(UUID id) {
        etats.remove(id);
    }

    int jours() {
        return plugin.getConfig().getInt("prix-moyen.jours", 30);
    }

    // ── Listes ──────────────────────────────────────────────────────────────

    /** Liste complète de la vue courante (recalculée au plus chaque seconde). */
    List<Entree> liste(Player p) {
        Etat e = etat(p);
        long now = System.currentTimeMillis();
        if (e.liste == null || now - e.calcul > 1000) {
            e.liste = switch (e.vue) {
                case MARCHE -> marche(p, e);
                case VENTES -> ventes(p);
                case ENCHERES -> encheres(p);
                case HISTORIQUE -> historique(p, e);
            };
            e.calcul = now;
            int pages = pages(e);
            if (e.page() >= pages) e.pages[e.vue.ordinal()] = Math.max(0, pages - 1);
        }
        return e.liste;
    }

    int pages(Etat e) {
        int n = e.liste == null ? 0 : e.liste.size();
        return Math.max(1, (n + Etat.parPage(e.vue) - 1) / Etat.parPage(e.vue));
    }

    /** n-ième entrée (à partir de 1) de la page courante, ou null. */
    Entree entree(Player p, int n) {
        Etat e = etat(p);
        List<Entree> l = liste(p);
        int i = e.page() * Etat.parPage(e.vue) + n - 1;
        return n >= 1 && n <= Etat.parPage(e.vue) && i < l.size() ? l.get(i) : null;
    }

    private List<Entree> marche(Player p, Etat e) {
        List<Entree> l = new ArrayList<>();
        for (Offre o : plugin.catalogue().filtrer(e)) l.add(new Entree(o.id(), o.item(), loreOffre(p, o), p.getUniqueId().equals(o.vendeurId()), o));
        return l;
    }

    List<String> loreOffre(Player p, Offre o) {
        List<String> d = new ArrayList<>();
        d.add(o.type().couleur + "◆ " + libelle(o));
        switch (o.type()) {
            case VENTE -> {
                d.add("Prix : &f" + argent(o.prix()) + " " + BB + quantite(o.quantite()));
                if (o.quantite() > 1) d.add("Soit &f" + unite(o.prixUnite()) + " " + BB + VIOLET + " l'unité");
                d.add("Vendeur : &f" + o.vendeur());
                d.add("Fin dans : &f" + duree(o.finDans()));
            }
            case ENCHERE -> {
                d.add((o.offres() > 0 ? "Offre actuelle : &f" : "Mise de départ : &f") + argent(o.prix()) + " " + BB + quantite(o.quantite()));
                d.add("Offres reçues : &f" + o.offres() + VIOLET + "  Minimum : &f" + argent(o.prochaine()));
                d.add("Vendeur : &f" + o.vendeur());
                d.add("Fin dans : &f" + duree(o.finDans()));
            }
            case ECHOPPE, RACHAT -> {
                boolean r = o.type() == Offre.Type.RACHAT;
                d.add((r ? "Rachète : &f" : "Prix : &f") + argent(o.prix()) + " " + BB + quantite(o.quantite()));
                if (o.quantite() > 1) d.add("Soit &f" + unite(o.prixUnite()) + " " + BB + VIOLET + " l'unité");
                d.add((r ? "Acheteur : &f" : "Vendeur : &f") + o.vendeur());
                d.add((r ? "Place libre : &f" : "Stock : &f") + stock(o.stock()));
                if (o.villeId() != null && o.droitDePlace() > 0) d.add("Droit de place : &f" + taux(o.droitDePlace()) + " %");
            }
        }
        d.addAll(comparaison(o.cle(), o.prixUnite()));
        List<String> a = new ArrayList<>();
        if (o.type().hdv()) {
            boolean mienne = p.getUniqueId().equals(o.vendeurId());
            a.add(GAUCHE + (mienne ? "Gérer mon offre" : o.type() == Offre.Type.VENTE ? "Acheter" : "Enchérir"));
        } else {
            a.add(GAUCHE + "S'y rendre" + (o.type() == Offre.Type.RACHAT ? " pour vendre" : " pour acheter"));
            if (o.villeId() != null) a.add(DROIT + "Fiche de la ville");
        }
        return lore(d, a);
    }

    static String libelle(Offre o) {
        return switch (o.type()) {
            case VENTE, ENCHERE -> o.type().nom + " &7(hôtel des ventes)";
            case ECHOPPE -> "Échoppe " + (o.ville() != null ? "à " + o.ville() : "hors ville");
            case RACHAT -> "Rachat " + (o.ville() != null ? "à " + o.ville() : "hors ville");
        };
    }

    private static String quantite(int q) {
        return q > 1 ? " &7(x" + q + ")" : "";
    }

    private static String stock(int s) {
        return s == Integer.MAX_VALUE ? "illimité" : s < 0 ? "pas encore relevé" : String.valueOf(s);
    }

    /** Ligne « prix moyen » et écart d'un prix unitaire à la moyenne. */
    private List<String> comparaison(String cle, double unite) {
        Historique.Moyenne m = plugin.historique().moyenne(cle, jours());
        if (m == null || m.unite() <= 0) return List.of();
        List<String> l = new ArrayList<>();
        l.add("Moyenne " + jours() + " j : &f" + unite(m.unite()) + " " + BB + VIOLET + " l'unité");
        double ecart = (unite - m.unite()) / m.unite() * 100;
        if (ecart <= -10) l.add(VERT + "▼ " + Math.round(-ecart) + " % sous la moyenne");
        else if (ecart >= 10) l.add(ROUGE + "▲ " + Math.round(ecart) + " % au-dessus de la moyenne");
        return l;
    }

    private List<Entree> ventes(Player p) {
        List<Entree> l = new ArrayList<>();
        for (ItemNote n : plugin.hdv().mesVentes(p.getUniqueId())) {
            Hdv.Etat st = Hdv.etat(n);
            ItemStack item = n.getItem();
            item.setAmount(Math.max(1, Math.min(99, n.getCurrentAmount())));
            List<String> d = new ArrayList<>();
            d.add(st.couleur + "◆ " + st.nom);
            d.add("Type : &f" + (n.isBIDAuction() ? "Enchère" : "Vente directe"));
            if (n.isBIDAuction()) {
                d.add((n.hasBidHistory() ? "Offre actuelle : &f" : "Mise de départ : &f") + argent(n.getPrice()) + " " + BB
                        + " &7(" + n.getBidHistoryList().size() + " offres)");
            } else {
                d.add("Prix : &f" + argent(n.getCurrentPrice()) + " " + BB + quantite(n.getCurrentAmount()));
                if (n.getPartiallySoldAmountLeft() > 0) {
                    d.add("Déjà vendus : &f" + (n.getItem().getAmount() - n.getPartiallySoldAmountLeft()) + VIOLET + " sur &f" + n.getItem().getAmount());
                }
            }
            switch (st) {
                case A_ENCAISSER -> d.add("À recevoir : &f" + argent(Hdv.net(n.getSoldPrice())) + " " + BB
                        + " &7(taxe " + taux(Hdv.taxe() * 100) + " %)");
                case RETIREE_ADMIN -> d.add("Motif : &f" + n.getAdminMessage());
                case ATTENTE, EN_VENTE, ENCHERE_EN_COURS -> d.add("Fin dans : &f" + duree(n.getTimeLeft()));
                default -> {
                }
            }
            List<String> a = new ArrayList<>();
            switch (st) {
                case A_ENCAISSER -> a.add(GAUCHE + "Récupérer mes BitBlocks");
                case EXPIREE, RETIREE_ADMIN -> a.add(GAUCHE + "Récupérer l'objet");
                case ENCHERE_EN_COURS -> a.add(GAUCHE + "Voir l'enchère");
                default -> {
                    a.add(GAUCHE + "Voir l'offre");
                    a.add(DROIT + "Retirer (double clic)");
                }
            }
            // Vente partielle encore en cours : on peut aussi la voir.
            if (st == Hdv.Etat.A_ENCAISSER && n.getPartiallySoldAmountLeft() > 0) a.add(DROIT + "Retirer le reste (double clic)");
            boolean brille = st == Hdv.Etat.A_ENCAISSER || st == Hdv.Etat.EXPIREE || st == Hdv.Etat.RETIREE_ADMIN;
            l.add(new Entree(n.getNoteID().toString(), item, lore(d, a), brille, n));
        }
        return l;
    }

    private List<Entree> encheres(Player p) {
        List<Entree> l = new ArrayList<>();
        for (ItemNote n : plugin.hdv().mesEncheres(p.getUniqueId())) {
            boolean premier = Objects.equals(n.getLastBidder(), p.getUniqueId());
            List<String> d = new ArrayList<>();
            if (n.isExpired()) d.add(premier && n.getAdminMessage() == null ? VERT + "◆ Enchère remportée" : ROUGE + "◆ Enchère perdue");
            else d.add(premier ? VERT + "◆ Tu as la meilleure offre" : ROUGE + "◆ Ton offre a été dépassée");
            d.add("Ton offre : &f" + argent(n.getBid(p)) + " " + BB);
            d.add("Offre actuelle : &f" + argent(n.getPrice()) + " " + BB);
            d.add("Vendeur : &f" + Hdv.nomJoueur(n.getPlayerUUID(), n.getPlayerName()));
            if (!n.isExpired()) d.add("Fin dans : &f" + duree(n.getTimeLeft()));
            List<String> a = new ArrayList<>();
            if (n.isExpired()) {
                a.add(GAUCHE + (premier && n.getAdminMessage() == null ? "Récupérer l'objet" : "Récupérer mes " + argent(n.getBid(p)) + " BitBlocks"));
            } else {
                a.add(GAUCHE + (premier ? "Voir l'enchère" : "Surenchérir"));
            }
            l.add(new Entree(n.getNoteID().toString(), n.getItem(), lore(d, a), n.isExpired(), n));
        }
        return l;
    }

    private List<Entree> historique(Player p, Etat e) {
        List<Entree> l = new ArrayList<>();
        int i = 0;
        for (Historique.Vente v : plugin.historique().duJoueur(p.getUniqueId(), e.filtreHisto, 500)) {
            boolean vente = p.getUniqueId().equals(v.vendeur());
            ItemStack item = v.item();
            if (item == null) item = new ItemStack(Material.PAPER);
            item.setAmount(Math.max(1, Math.min(99, v.quantite())));
            List<String> d = new ArrayList<>();
            d.add(vente ? VERT + "◆ Vente ▲" : ROUGE + "◆ Achat ▼");
            d.add("Où : &f" + switch (v.source()) {
                case "vente" -> "Hôtel des ventes";
                case "enchere" -> "Hôtel des ventes (enchère)";
                case "rachat" -> "Rachat d'une échoppe" + (v.ville() != null ? " à " + v.ville() : "");
                default -> "Échoppe" + (v.ville() != null ? " à " + v.ville() : "");
            });
            d.add("Quantité : &f" + v.quantite() + VIOLET + "  Unité : &f" + unite(v.unite()) + " " + BB);
            d.add("Total : &f" + argent(v.total()) + " " + BB);
            if (vente && v.taxe() > 0 && (v.source().equals("vente") || v.source().equals("enchere"))) {
                d.add("Taxe : &f" + argent(v.taxe()) + VIOLET + "  Reçu : &f" + argent(v.total() - v.taxe()) + " " + BB);
            }
            String autre = vente ? v.acheteurNom() : v.vendeurNom();
            d.add((vente ? "Acheteur : &f" : "Vendeur : &f") + (autre == null ? "?" : autre));
            d.add("Le &f" + date(v.date()) + " &7(" + ilYa(v.date()) + ")");
            l.add(new Entree("h" + (i++), item, lore(d, List.of(GAUCHE + "Chercher cet objet au marché")), false, v));
        }
        return l;
    }

    // ── Offre détaillée ─────────────────────────────────────────────────────

    ItemNote noteOffre(Player p) {
        Etat e = etat(p);
        return e.offre == null ? null : Hdv.note(e.offre);
    }

    /** Quantité choisie bornée à ce qui reste (tout si la vente partielle est désactivée). */
    int quantiteOffre(Player p, ItemNote n) {
        Etat e = etat(p);
        int max = n.getCurrentAmount();
        if (!SettingManager.partialSelling) return max;
        e.quantite = Math.max(1, Math.min(max, e.quantite));
        return e.quantite;
    }

    long enchereOffre(Player p, ItemNote n) {
        Etat e = etat(p);
        long min = (long) Math.ceil(Hdv.offreMin(n));
        if (e.enchere < min) e.enchere = min;
        return e.enchere;
    }

    List<String> descriptionOffre(Player p, ItemNote n) {
        List<String> d = new ArrayList<>();
        boolean mienne = n.getPlayerUUID().equals(p.getUniqueId());
        d.add((n.isBIDAuction() ? Offre.Type.ENCHERE.couleur + "◆ Enchère" : Offre.Type.VENTE.couleur + "◆ Vente directe") + " &7(hôtel des ventes)");
        d.add("Vendeur : &f" + Hdv.nomJoueur(n.getPlayerUUID(), n.getPlayerName()) + (mienne ? " &7(toi)" : ""));
        if (n.isBIDAuction()) {
            d.add((n.hasBidHistory() ? "Offre actuelle : &f" : "Mise de départ : &f") + argent(n.getPrice()) + " " + BB);
            d.add("Offres reçues : &f" + n.getBidHistoryList().size());
            if (n.hasBidHistory()) d.add("Meilleure offre de &f" + Hdv.nomJoueur(n.getLastBidder(), n.getLastBidderName()));
            double mien = n.getBid(p);
            if (mien > 0) d.add("Ton offre : &f" + argent(mien) + " " + BB);
        } else {
            d.add("Prix : &f" + argent(n.getCurrentPrice()) + " " + BB + quantite(n.getCurrentAmount()));
            if (n.getCurrentAmount() > 1) d.add("Soit &f" + unite(n.getCurrentPrice() / n.getCurrentAmount()) + " " + BB + VIOLET + " l'unité");
        }
        if (n.isOnWaitingList()) d.add(BLEU + "Mise en ligne dans quelques secondes");
        d.add(n.isExpired() ? ROUGE + "Terminée" : "Fin dans : &f" + duree(n.getTimeLeft()));
        return lore(d, List.of());
    }

    /** Lignes « prix moyen » pour un objet (offre, mise en vente, objet en main). */
    List<String> moyenne(ItemStack item, String sauf) {
        String cle = Objets.cle(item);
        List<String> d = new ArrayList<>();
        Historique.Moyenne m = plugin.historique().moyenne(cle, jours());
        if (m == null) {
            d.add("Aucune vente ces " + jours() + " derniers jours.");
        } else {
            d.add("Prix moyen : &f" + unite(m.unite()) + " " + BB + VIOLET + " l'unité");
            d.add("Sur &f" + m.ventes() + VIOLET + " ventes (&f" + m.quantite() + VIOLET + " objets), " + jours() + " j");
            d.add("De &f" + unite(m.min()) + VIOLET + " à &f" + unite(m.max()) + " " + BB);
            if (m.moyenneHdv() >= 0) d.add("Hôtel des ventes : &f" + unite(m.moyenneHdv()) + " " + BB);
            if (m.moyenneEchoppes() >= 0) d.add("Échoppes : &f" + unite(m.moyenneEchoppes()) + " " + BB);
        }
        List<Offre> moinsCher = plugin.catalogue().memeObjet(cle, sauf, false, 1);
        if (!moinsCher.isEmpty()) {
            Offre o = moinsCher.getFirst();
            d.add("Moins cher en ce moment : &f" + unite(o.prixUnite()) + " " + BB + " &7(" + (o.type().hdv() ? "hôtel des ventes" : libelle(o)) + ")");
        }
        List<Offre> rachat = plugin.catalogue().memeObjet(cle, sauf, true, 1);
        if (!rachat.isEmpty()) d.add("Meilleur rachat : &f" + unite(rachat.getFirst().prixUnite()) + " " + BB + " &7(" + libelle(rachat.getFirst()) + ")");
        return d;
    }

    /** Autres offres du même objet que l'offre affichée (comparatif sous l'offre). */
    List<Offre> autres(Player p) {
        ItemNote n = noteOffre(p);
        if (n == null) return List.of();
        return plugin.catalogue().memeObjet(Objets.cle(n.getItem()), n.getNoteID().toString(), false, 7);
    }

    // ── Mise en vente ───────────────────────────────────────────────────────

    /** Objet choisi encore présent dans sa case (sinon le brouillon est vidé). */
    ItemStack objetVente(Player p) {
        Etat e = etat(p);
        if (e.venteSlot < 0 || e.venteObjet == null) return null;
        ItemStack s = p.getInventory().getItem(e.venteSlot);
        if (s == null || !s.isSimilar(e.venteObjet)) {
            e.viderVente();
            return null;
        }
        e.venteQuantite = Math.max(1, Math.min(s.getAmount(), e.venteQuantite));
        ItemStack c = s.clone();
        c.setAmount(e.venteQuantite);
        return c;
    }

    void choisirVente(Player p, int slot) {
        Etat e = etat(p);
        ItemStack s = slot >= 0 && slot < 36 ? p.getInventory().getItem(slot) : null;
        if (s == null || s.getType().isAir()) return;
        boolean meme = e.venteObjet != null && e.venteSlot == slot;
        e.venteSlot = slot;
        e.venteObjet = s.clone();
        if (!meme) {
            e.venteQuantite = s.getAmount();
            // Prix proposé : moyenne observée x quantité, sinon le prix déjà saisi.
            Historique.Moyenne m = plugin.historique().moyenne(Objets.cle(s), jours());
            if (m != null && m.unite() > 0) e.ventePrix = Math.max(1, Math.round(m.unite() * e.venteQuantite));
        }
    }

    List<String> recapVente(Player p) {
        Etat e = etat(p);
        ItemStack item = objetVente(p);
        List<String> d = new ArrayList<>();
        if (item == null) return lore(List.of("Choisis un objet dans", "ton inventaire, en bas."), List.of());
        d.add("Objet : &fx" + e.venteQuantite + " " + Objets.nom(item));
        d.add("Type : &f" + (e.venteEnchere ? "Enchère" : "Vente directe"));
        d.add((e.venteEnchere ? "Mise de départ : &f" : "Prix : &f") + argent(e.ventePrix) + " " + BB);
        if (e.venteQuantite > 1) d.add("Soit &f" + unite((double) e.ventePrix / e.venteQuantite) + " " + BB + VIOLET + " l'unité");
        d.add("Taxe du serveur : &f" + taux(Hdv.taxe() * 100) + " %" + VIOLET + " (" + argent(e.ventePrix - Hdv.net(e.ventePrix)) + ")");
        d.add((e.venteEnchere ? "Tu recevras au moins : &f" : "Tu recevras : &f") + argent(Hdv.net(e.ventePrix)) + " " + BB);
        d.add("Durée : &f" + duree(plugin.hdv().duree(p, e.venteEnchere)));
        d.add("Offres en cours : &f" + plugin.hdv().ventesEnCours(p) + "/" + plugin.hdv().emplacements(p));
        return lore(d, List.of(GAUCHE + "Mettre en vente (double clic)"));
    }

    // ── Objet en main ───────────────────────────────────────────────────────

    List<String> moyenneMain(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (main.getType().isAir()) return lore(List.of("Prends un objet en main pour", "connaître son prix moyen."), List.of());
        List<String> d = new ArrayList<>();
        d.add("Objet : &f" + Objets.nom(main));
        d.addAll(moyenne(main, null));
        return lore(d, List.of(GAUCHE + "Détail dans le tchat", DROIT + "Chercher cet objet"));
    }

    // ── Outils ──────────────────────────────────────────────────────────────

    /** Copie d'objet d'inventaire pour l'affichage dans la zone du bas (menu de vente). */
    static ItemStack inventaire(Player p, int slot) {
        ItemStack s = p.getInventory().getItem(slot);
        return s == null || s.getType().isAir() ? null : s.clone();
    }

    static boolean aUnNom(ItemStack item) {
        ItemMeta m = item.getItemMeta();
        return m != null && m.hasDisplayName();
    }
}
