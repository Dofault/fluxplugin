package fr.influencraft.influmarche;

import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.ram.ItemNote;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static fr.influencraft.influmarche.Fmt.*;

/**
 * {@code /influmarche} : navigation appelée par les boutons des menus FluxMenu, et entrée joueur
 * {@code /hdv}, {@code /marche}, {@code /ah} (via {@link Interception}).
 *
 * <pre>
 *   ouvrir marche|ventes|encheres|historique|vendre
 *   source &lt;k&gt; | categorie &lt;k&gt; | tri +|- | filtre +|- | page +|- | recherche | effacer
 *   clic &lt;n&gt; gauche|droit          entrée n de la page (selon la liste ouverte)
 *   autre &lt;n&gt; gauche|droit         offre du comparatif
 *   offre quantite|montant &lt;+N|-N|min|max|saisie&gt; | offre acheter|encherir|retirer|retour
 *   vente choisir &lt;case&gt; | vente quantite|prix &lt;+N|-N|min|max|saisie|moyenne&gt; | vente type|confirmer|vider
 *   recuperer | moyenne [chercher] | reload (admin)
 * </pre>
 */
final class Commande implements CommandExecutor, TabCompleter {

    private final InfluMarche plugin;
    /** Double clic de confirmation dans les listes : joueur -> {action, échéance}. */
    private final Map<UUID, Object[]> confirmations = new ConcurrentHashMap<>();

    Commande(InfluMarche plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (a.length == 1 && a[0].equalsIgnoreCase("reload") && sender.hasPermission("influmarche.admin")) {
            plugin.recharger();
            sender.sendMessage(Fmt.ligne(null, VERT + "InfluMarche rechargé."));
            return true;
        }
        if (!(sender instanceof Player p)) return true;
        if (command.getName().equalsIgnoreCase("hdv")) {
            if (!entree(p, a)) p.performCommand("ah " + String.join(" ", a));
            return true;
        }
        try {
            naviguer(p, a);
        } catch (RuntimeException ex) {
            // argument malformé venu d'un bouton : rien à faire
        }
        return true;
    }

    // ── Entrée joueur : /hdv, /marche, /ah ──────────────────────────────────

    /**
     * Commande joueur de l'hôtel des ventes. Vrai si InfluMarche l'a traitée ; faux pour laisser
     * passer à AuctionHouse (sell/bid avec prix, administration...).
     */
    boolean entree(Player p, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        Etat e = plugin.vues().etat(p);
        switch (sub) {
            case "", "menu", "marche", "market" -> ouvrir(p, Etat.Vue.MARCHE);
            case "history", "historique", "histo" -> ouvrir(p, Etat.Vue.HISTORIQUE);
            case "average", "moyenne", "prix", "price" -> moyenneChat(p);
            case "ventes", "mesventes", "listings" -> ouvrir(p, Etat.Vue.VENTES);
            case "encheres", "bids", "mybids" -> ouvrir(p, Etat.Vue.ENCHERES);
            case "help", "aide" -> aide(p);
            case "search", "chercher", "recherche" -> {
                if (a.length < 2) return saisieRecherche(p);
                e.recherche = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length));
                e.source = 0;
                e.categorie = 0;
                e.pages[Etat.Vue.MARCHE.ordinal()] = 0;
                ouvrir(p, Etat.Vue.MARCHE);
            }
            case "view" -> {
                if (a.length < 2) return true;
                ItemNote n = Hdv.note(a[1]);
                if (n == null) {
                    Fmt.erreur(p, "Cette offre n'existe plus.");
                    return true;
                }
                if (n.getPlayerUUID().equals(p.getUniqueId()) && Hdv.etat(n) != Hdv.Etat.EN_VENTE && Hdv.etat(n) != Hdv.Etat.ATTENTE) ouvrir(p, Etat.Vue.VENTES);
                else if (n.isExpired()) ouvrir(p, Etat.Vue.ENCHERES);
                else ouvrirOffre(p, a[1], "marche");
            }
            case "sell", "vendre", "bid", "encherir" -> {
                if (a.length >= 2) return false; // /ah sell <prix> [quantité] : AuctionHouse s'en charge
                e.venteEnchere = sub.equals("bid") || sub.equals("encherir");
                plugin.vues().choisirVente(p, p.getInventory().getHeldItemSlot());
                plugin.ouvrirMenu(p, "vendre");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void aide(Player p) {
        envoyer(p, "&7&m                                                    &f");
        envoyer(p, OR + "✦ Aide : Marché et hôtel des ventes");
        envoyer(p, "&7&m                                                    &f");
        String[][] l = {
                {"/hdv", "Ouvre le marché (hôtel des ventes et échoppes)"},
                {"/hdv search <objet>", "Cherche un objet partout (ex : diamant)"},
                {"/hdv sell", "Met en vente l'objet en main"},
                {"/hdv sell <prix> [quantité]", "Vente directe rapide de l'objet en main"},
                {"/hdv bid <prix>", "Enchère rapide de l'objet en main"},
                {"/ah history", "Ton historique de ventes et d'achats"},
                {"/ah average", "Prix moyen de l'objet en main"},
        };
        for (String[] c : l) envoyer(p, "   " + VIOLET + c[0] + " &#F0A800- &7" + c[1]);
    }

    void moyenneChat(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (main.getType().isAir()) {
            Fmt.erreur(p, "Prends en main l'objet dont tu veux connaître le prix.");
            return;
        }
        envoyer(p, OR + "✦ Prix de &f" + Objets.nom(main) + " " + SOURCE);
        for (String l : plugin.vues().moyenne(main, null)) envoyer(p, OR + "│ " + VIOLET + l);
        envoyer(p, OR + "└ &7Prix unitaires, ventes de l'hôtel des ventes et des échoppes.");
    }

    // ── Navigation (boutons) ────────────────────────────────────────────────

    void ouvrir(Player p, Etat.Vue vue) {
        Etat e = plugin.vues().etat(p);
        e.vue = vue;
        e.changer();
        plugin.ouvrirMenu(p, switch (vue) {
            case MARCHE -> "marche";
            case VENTES -> "ventes";
            case ENCHERES -> "encheres";
            case HISTORIQUE -> "historique";
        });
    }

    void ouvrirOffre(Player p, String id, String retour) {
        Etat e = plugin.vues().etat(p);
        ItemNote n = Hdv.note(id);
        if (n == null) {
            Fmt.erreur(p, "Cette offre n'existe plus.");
            return;
        }
        e.offre = id;
        e.quantite = n.getCurrentAmount();
        e.enchere = 0;
        e.retourOffre = retour;
        plugin.ouvrirMenu(p, "offre");
    }

    private void naviguer(Player p, String[] a) {
        Vues v = plugin.vues();
        Etat e = v.etat(p);
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "ouvrir" -> {
                switch (a.length > 1 ? a[1] : "marche") {
                    case "ventes" -> ouvrir(p, Etat.Vue.VENTES);
                    case "encheres" -> ouvrir(p, Etat.Vue.ENCHERES);
                    case "historique" -> ouvrir(p, Etat.Vue.HISTORIQUE);
                    case "vendre" -> plugin.ouvrirMenu(p, "vendre");
                    default -> ouvrir(p, Etat.Vue.MARCHE);
                }
            }
            case "vue" -> { // ouverture directe d'un menu par FluxMenu : la liste suit
                e.vue = Etat.Vue.valueOf(a[1].toUpperCase(Locale.ROOT));
                e.changer();
            }
            case "source" -> {
                e.source = Math.floorMod(Integer.parseInt(a[1]), Catalogue.SOURCES.length);
                e.page(0);
            }
            case "categorie" -> {
                e.categorie = Math.floorMod(Integer.parseInt(a[1]), Objets.Categorie.values().length);
                e.page(0);
            }
            case "tri" -> {
                e.tri = Math.floorMod(e.tri + (a[1].equals("-") ? -1 : 1), Catalogue.TRIS.length);
                e.page(0);
            }
            case "filtre" -> {
                e.filtreHisto = Math.floorMod(e.filtreHisto + (a[1].equals("-") ? -1 : 1), 3);
                e.page(0);
            }
            case "page" -> {
                v.liste(p);
                int cible = e.page() + (a[1].equals("-") ? -1 : 1);
                e.page(Math.min(cible, v.pages(e) - 1));
            }
            case "recherche" -> saisieRecherche(p);
            case "effacer" -> {
                e.recherche = "";
                e.page(0);
            }
            case "clic" -> clic(p, Integer.parseInt(a[1]), a.length > 2 ? a[2] : "gauche");
            case "autre" -> {
                List<Offre> l = v.autres(p);
                int n = Integer.parseInt(a[1]);
                if (n < 1 || n > l.size()) return;
                Offre o = l.get(n - 1);
                if (o.type().hdv()) ouvrirOffre(p, o.id(), e.retourOffre);
                else if (a.length > 2 && a[2].equals("droit") && o.villeId() != null) Ville.fiche(p, o.villeId());
                else plugin.echoppes().localiser(p, o);
            }
            case "offre" -> offre(p, e, a);
            case "vente" -> vente(p, e, a);
            case "recuperer" -> {
                int n = plugin.hdv().toutRecuperer(p);
                if (n == 0) Fmt.erreur(p, "Rien à récupérer pour le moment.");
                e.changer();
            }
            case "moyenne" -> {
                if (a.length > 1 && a[1].equals("chercher")) {
                    ItemStack main = p.getInventory().getItemInMainHand();
                    if (main.getType().isAir()) return;
                    chercher(p, Objets.nom(main));
                } else {
                    p.closeInventory();
                    moyenneChat(p);
                }
            }
            default -> p.sendMessage(Fmt.ligne(p, VIOLET + "/influmarche ouvrir marche|ventes|encheres|historique|vendre"));
        }
    }

    private void chercher(Player p, String texte) {
        Etat e = plugin.vues().etat(p);
        e.recherche = texte;
        e.source = 0;
        e.categorie = 0;
        e.pages[Etat.Vue.MARCHE.ordinal()] = 0;
        ouvrir(p, Etat.Vue.MARCHE);
    }

    private boolean saisieRecherche(Player p) {
        Etat e = plugin.vues().etat(p);
        Saisie.ouvrir(plugin, p, "Rechercher", "Un objet (français ou anglais : diamant, DIAMOND), un vendeur ou une ville.",
                "Recherche", e.recherche, "marche", this::chercher);
        return true;
    }

    /** Double clic : vrai si la même action a été demandée il y a moins de 3 secondes. */
    private boolean confirme(Player p, String action, String message) {
        Object[] c = confirmations.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (c != null && c[0].equals(action) && (long) c[1] >= now) {
            confirmations.remove(p.getUniqueId());
            return true;
        }
        confirmations.put(p.getUniqueId(), new Object[]{action, now + 3000});
        p.sendActionBar(Fmt.ligne(p, OR + message));
        p.playSound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.6f);
        return false;
    }

    private void clic(Player p, int n, String bouton) {
        Vues v = plugin.vues();
        Etat e = v.etat(p);
        Vues.Entree en = v.entree(p, n);
        if (en == null) return;
        switch (e.vue) {
            case MARCHE -> {
                Offre o = (Offre) en.cible();
                if (o.type().hdv()) ouvrirOffre(p, o.id(), "marche");
                else if (bouton.equals("droit") && o.villeId() != null) Ville.fiche(p, o.villeId());
                else plugin.echoppes().localiser(p, o);
            }
            case VENTES -> {
                ItemNote note = (ItemNote) en.cible();
                String id = note.getNoteID().toString();
                Hdv.Etat st = Hdv.etat(note);
                if (bouton.equals("droit") && (st == Hdv.Etat.EN_VENTE || st == Hdv.Etat.ATTENTE
                        || st == Hdv.Etat.A_ENCAISSER && note.getPartiallySoldAmountLeft() > 0)) {
                    if (confirme(p, "retirer " + id, "Clique encore pour retirer cette offre.")) plugin.hdv().retirer(p, id);
                } else if (st == Hdv.Etat.A_ENCAISSER || st == Hdv.Etat.EXPIREE || st == Hdv.Etat.RETIREE_ADMIN) {
                    plugin.hdv().recuperer(p, id);
                } else {
                    ouvrirOffre(p, id, "ventes");
                    return;
                }
                e.changer();
            }
            case ENCHERES -> {
                ItemNote note = (ItemNote) en.cible();
                if (note.isExpired()) {
                    plugin.hdv().recupererEnchere(p, note.getNoteID().toString());
                    e.changer();
                } else {
                    ouvrirOffre(p, note.getNoteID().toString(), "encheres");
                }
            }
            case HISTORIQUE -> {
                Historique.Vente h = (Historique.Vente) en.cible();
                chercher(p, h.nom() == null ? "" : h.nom());
            }
        }
    }

    // ── Offre détaillée ─────────────────────────────────────────────────────

    private void offre(Player p, Etat e, String[] a) {
        ItemNote n = plugin.vues().noteOffre(p);
        String act = a.length > 1 ? a[1] : "";
        if (act.equals("retour") || n == null) {
            if (n == null && !act.equals("retour")) Fmt.erreur(p, "Cette offre n'existe plus.");
            switch (e.retourOffre) {
                case "ventes" -> ouvrir(p, Etat.Vue.VENTES);
                case "encheres" -> ouvrir(p, Etat.Vue.ENCHERES);
                default -> ouvrir(p, Etat.Vue.MARCHE);
            }
            return;
        }
        String arg = a.length > 2 ? a[2] : "";
        switch (act) {
            case "quantite" -> {
                if (arg.equals("saisie")) {
                    Saisie.ouvrir(plugin, p, "Quantité", "Combien en veux-tu ? (1 à " + n.getCurrentAmount() + ")", "Quantité",
                            String.valueOf(e.quantite), "offre", (pl, t) -> {
                                long q = Fmt.montant(t);
                                if (q > 0) e.quantite = (int) Math.min(q, n.getCurrentAmount());
                                plugin.ouvrirMenu(pl, "offre");
                            });
                    return;
                }
                e.quantite = borne(arg, e.quantite, 1, n.getCurrentAmount());
            }
            case "montant" -> {
                long min = (long) Math.ceil(Hdv.offreMin(n));
                if (arg.equals("saisie")) {
                    Saisie.ouvrir(plugin, p, "Ton offre", "Offre minimale : " + Fmt.argent(min) + " BitBlocks.", "Montant",
                            String.valueOf(Math.max(min, e.enchere)), "offre", (pl, t) -> {
                                long m = Fmt.montant(t);
                                if (m > 0) e.enchere = Math.max(min, m);
                                plugin.ouvrirMenu(pl, "offre");
                            });
                    return;
                }
                e.enchere = Math.max(min, arg.equals("min") ? min : e.enchere + Long.parseLong(arg.replace("+", "")));
            }
            case "acheter" -> {
                if (plugin.hdv().acheter(p, e.offre, plugin.vues().quantiteOffre(p, n))) {
                    plugin.catalogue().invalider();
                    ItemNote reste = Hdv.note(e.offre);
                    if (reste == null || !reste.isTheoreticallyOnAuction() || reste.getCurrentAmount() <= 0) offre(p, e, new String[]{"offre", "retour"});
                }
            }
            case "encherir" -> {
                if (plugin.hdv().encherir(p, e.offre, plugin.vues().enchereOffre(p, n))) plugin.catalogue().invalider();
            }
            case "chercher" -> chercher(p, Objets.nom(n.getItem()));
            case "vendeur" -> chercher(p, Hdv.nomJoueur(n.getPlayerUUID(), n.getPlayerName()));
            case "retirer" -> {
                if (plugin.hdv().retirer(p, e.offre)) {
                    plugin.catalogue().invalider();
                    e.retourOffre = "ventes";
                    offre(p, e, new String[]{"offre", "retour"});
                }
            }
            default -> {
            }
        }
    }

    /** « +10 », « -1 », « min », « max » appliqués à une valeur bornée. */
    private static int borne(String arg, int valeur, int min, int max) {
        int v = switch (arg) {
            case "min" -> min;
            case "max" -> max;
            default -> valeur + Integer.parseInt(arg.replace("+", ""));
        };
        return Math.max(min, Math.min(max, v));
    }

    // ── Mise en vente ───────────────────────────────────────────────────────

    private void vente(Player p, Etat e, String[] a) {
        Vues v = plugin.vues();
        String act = a.length > 1 ? a[1] : "";
        String arg = a.length > 2 ? a[2] : "";
        switch (act) {
            case "choisir" -> v.choisirVente(p, Integer.parseInt(arg));
            case "type" -> {
                if (!e.venteEnchere && !SettingManager.BIDAuctions) Fmt.erreur(p, "Les enchères sont désactivées.");
                else e.venteEnchere = !e.venteEnchere;
            }
            case "vider" -> e.viderVente();
            case "quantite" -> {
                ItemStack s = v.objetVente(p);
                if (s == null) return;
                int max = p.getInventory().getItem(e.venteSlot).getAmount();
                if (arg.equals("saisie")) {
                    Saisie.ouvrir(plugin, p, "Quantité", "Combien en vendre ? (1 à " + max + ")", "Quantité",
                            String.valueOf(e.venteQuantite), "vendre", (pl, t) -> {
                                long q = Fmt.montant(t);
                                if (q > 0) e.venteQuantite = (int) Math.min(q, max);
                                plugin.ouvrirMenu(pl, "vendre");
                            });
                    return;
                }
                e.venteQuantite = borne(arg, e.venteQuantite, 1, max);
            }
            case "prix" -> {
                if (arg.equals("saisie")) {
                    Saisie.ouvrir(plugin, p, e.venteEnchere ? "Mise de départ" : "Prix de vente",
                            "Prix total pour " + e.venteQuantite + " objet(s), en BitBlocks (10k = 10 000). Taxe du serveur : "
                                    + Fmt.taux(Hdv.taxe() * 100) + " %.", "Prix", e.ventePrix > 0 ? String.valueOf(e.ventePrix) : "", "vendre", (pl, t) -> {
                                long m = Fmt.montant(t);
                                if (m > 0) e.ventePrix = m;
                                else Fmt.erreur(pl, "Prix invalide.");
                                plugin.ouvrirMenu(pl, "vendre");
                            });
                    return;
                }
                if (arg.equals("moyenne")) {
                    ItemStack s = v.objetVente(p);
                    Historique.Moyenne m = s == null ? null : plugin.historique().moyenne(Objets.cle(s), v.jours());
                    if (m == null || m.unite() <= 0) {
                        Fmt.erreur(p, "Pas encore de prix moyen pour cet objet.");
                        return;
                    }
                    e.ventePrix = Math.max(1, Math.round(m.unite() * e.venteQuantite));
                    return;
                }
                e.ventePrix = Math.max(0, e.ventePrix + Long.parseLong(arg.replace("+", "")));
            }
            case "confirmer" -> {
                ItemStack s = v.objetVente(p);
                if (s == null) {
                    Fmt.erreur(p, "Choisis d'abord un objet dans ton inventaire, en bas.");
                    return;
                }
                if (e.ventePrix <= 0) {
                    Fmt.erreur(p, "Indique un prix.");
                    return;
                }
                if (plugin.hdv().vendre(p, e.venteSlot, e.venteObjet, e.venteQuantite, e.ventePrix, e.venteEnchere)) {
                    e.viderVente();
                    plugin.catalogue().invalider();
                    ouvrir(p, Etat.Vue.VENTES);
                }
            }
            default -> {
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("hdv") && a.length == 1) {
            for (String s : List.of("search", "sell", "bid", "history", "average", "ventes", "encheres", "help")) {
                if (s.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        }
        return out;
    }
}
