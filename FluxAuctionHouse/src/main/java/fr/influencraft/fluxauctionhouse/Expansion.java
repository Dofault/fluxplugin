package fr.influencraft.fluxauctionhouse;

import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.ram.ItemNote;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static fr.influencraft.fluxauctionhouse.Fmt.*;

/**
 * Placeholders {@code %influmarche_...%} lus par les menus Flux du marché.
 *
 * <pre>
 *   solde, taxe, emplacements, a_recuperer, a_recuperer_oui, nb_offres
 *   total, page, pages, precedent, suivant, recherche, recherche_oui
 *   source, source_&lt;k&gt; (0-3), categorie, categorie_&lt;k&gt; (0-8), tri, tri_&lt;k&gt; (0-4), filtre_&lt;k&gt; (historique, 0-2)
 *   liste_&lt;n&gt;_existe|brille|l&lt;k&gt;       n-ième entrée de la page (lignes de description déjà mises en forme)
 *   offre_existe|enchere|mienne|en_cours|partiel|retirable|premier|peut|quantite|quantite_max|total|unite
 *   offre_montant|min|hausse|l&lt;k&gt;|moy_l&lt;k&gt;, autre_&lt;n&gt;_existe|l&lt;k&gt;, autres_total
 *   vente_choisi|quantite|max|prix|enchere|type|net|l&lt;k&gt;|moy_l&lt;k&gt;, inv_&lt;case&gt;_existe|choisi
 *   main_l&lt;k&gt;                         prix moyen de l'objet en main
 * </pre>
 */
final class Expansion {

    private static final Pattern LISTE = Pattern.compile("liste_(\\d+)_(.+)");
    private static final Pattern AUTRE = Pattern.compile("autre_(\\d+)_(.+)");
    private static final Pattern INV = Pattern.compile("inv_(\\d+)_(.+)");
    private static final Pattern LIGNE = Pattern.compile("l(\\d+)");

    private final FluxAuctionHouse plugin;

    Expansion(FluxAuctionHouse plugin) {
        this.plugin = plugin;
    }





    /** Placeholders of the plugin, registered with Flux (resolved without PlaceholderAPI, exposed to it). */
    String onRequest(OfflinePlayer op, String s) {
        if (!(op instanceof Player p) || !p.isOnline()) return "";
        try {
            return valeur(p, s);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String ligne(List<String> l, String champ) {
        Matcher m = LIGNE.matcher(champ);
        if (!m.matches()) return null;
        int k = Integer.parseInt(m.group(1));
        return k >= 1 && k <= l.size() ? l.get(k - 1) : "";
    }

    private String valeur(Player p, String s) {
        Vues v = plugin.vues();
        Etat e = v.etat(p);
        Matcher m;
        if ((m = LISTE.matcher(s)).matches()) {
            Vues.Entree en = v.entree(p, Integer.parseInt(m.group(1)));
            String c = m.group(2);
            if (c.equals("existe")) return oui(en != null);
            if (en == null) return "";
            if (c.equals("brille")) return oui(en.brille());
            String l = ligne(en.lore(), c);
            return l == null ? "" : l;
        }
        if ((m = AUTRE.matcher(s)).matches()) {
            List<Offre> autres = v.autres(p);
            int n = Integer.parseInt(m.group(1));
            String c = m.group(2);
            if (c.equals("existe")) return oui(n >= 1 && n <= autres.size());
            if (n < 1 || n > autres.size()) return "";
            String l = ligne(v.loreOffre(p, autres.get(n - 1)), c);
            return l == null ? "" : l;
        }
        if ((m = INV.matcher(s)).matches()) {
            int slot = Integer.parseInt(m.group(1));
            return switch (m.group(2)) {
                case "existe" -> oui(Vues.inventaire(p, slot) != null);
                case "choisi" -> oui(e.venteSlot == slot && v.objetVente(p) != null);
                default -> "";
            };
        }
        if (s.startsWith("offre_")) return offre(p, e, s.substring(6));
        if (s.startsWith("vente_")) return vente(p, e, s.substring(6));
        if (s.startsWith("main_")) {
            String l = ligne(v.moyenneMain(p), s.substring(5));
            return l == null ? "" : l;
        }
        if (s.startsWith("source_")) return oui(e.source == Integer.parseInt(s.substring(7)));
        if (s.startsWith("categorie_")) return oui(e.categorie == Integer.parseInt(s.substring(10)));
        if (s.startsWith("tri_")) return choix(Catalogue.TRIS, Integer.parseInt(s.substring(4)), e.tri);
        if (s.startsWith("filtre_")) return choix(new String[]{"Tout", "Mes ventes", "Mes achats"}, Integer.parseInt(s.substring(7)), e.filtreHisto);
        return switch (s) {
            case "solde" -> argent(plugin.economie().balance(p));
            case "taxe" -> taux(Hdv.taxe() * 100);
            case "emplacements" -> plugin.hdv().ventesEnCours(p) + "/" + plugin.hdv().emplacements(p);
            case "a_recuperer" -> String.valueOf(plugin.hdv().aRecuperer(p));
            case "a_recuperer_oui" -> oui(plugin.hdv().aRecuperer(p) > 0);
            case "nb_offres" -> String.valueOf(plugin.catalogue().toutes().size());
            case "total" -> String.valueOf(v.liste(p).size());
            case "page" -> String.valueOf(e.page() + 1);
            case "pages" -> {
                v.liste(p);
                yield String.valueOf(v.pages(e));
            }
            case "precedent" -> oui(e.page() > 0);
            case "suivant" -> {
                v.liste(p);
                yield oui(e.page() + 1 < v.pages(e));
            }
            case "recherche" -> e.recherche.isBlank() ? "aucune" : e.recherche;
            case "recherche_oui" -> oui(!e.recherche.isBlank());
            case "source" -> Catalogue.SOURCES[e.source];
            case "categorie" -> Objets.Categorie.values()[e.categorie].nom;
            case "tri" -> Catalogue.TRIS[e.tri];
            case "vente_directe_active" -> oui(SettingManager.BINAuctions);
            case "encheres_actives" -> oui(SettingManager.BIDAuctions);
            default -> "";
        };
    }

    /** Ligne d'un sélecteur : choix courant en blanc avec une flèche, les autres en gris. */
    private static String choix(String[] noms, int k, int courant) {
        if (k < 0 || k >= noms.length) return "";
        return k == courant ? OR + "│ &f▸ " + noms[k] : OR + "│ &7" + noms[k];
    }

    private String offre(Player p, Etat e, String c) {
        Vues v = plugin.vues();
        ItemNote n = v.noteOffre(p);
        if (c.equals("existe")) return oui(n != null);
        if (n == null) return "";
        boolean mienne = n.getPlayerUUID().equals(p.getUniqueId());
        boolean enCours = n.isTheoreticallyOnAuction() && !n.isExpired() && !n.isOnWaitingList();
        switch (c) {
            case "enchere": return oui(n.isBIDAuction());
            case "mienne": return oui(mienne);
            case "en_cours": return oui(enCours);
            case "partiel": return oui(!n.isBIDAuction() && SettingManager.partialSelling && n.getCurrentAmount() > 1);
            case "retirable": return oui(mienne && !n.isSold() && !(n.isBIDAuction() && n.hasBidHistory()));
            case "premier": return oui(Objects.equals(n.getLastBidder(), p.getUniqueId()));
            case "quantite": return String.valueOf(n.isBIDAuction() ? n.getCurrentAmount() : v.quantiteOffre(p, n));
            case "quantite_max": return String.valueOf(n.getCurrentAmount());
            case "total": return argent(Hdv.prixAchat(n, v.quantiteOffre(p, n)));
            case "unite": return unite(n.getPrice() / n.getItem().getAmount());
            case "montant": return argent(v.enchereOffre(p, n));
            case "min": return argent(Hdv.offreMin(n));
            case "hausse": return argent(v.enchereOffre(p, n) - n.getBid(p));
            case "peut": {
                double besoin = n.isBIDAuction() ? v.enchereOffre(p, n) - n.getBid(p) : Hdv.prixAchat(n, v.quantiteOffre(p, n));
                return oui(plugin.economie().balance(p) >= besoin);
            }
            case "autres_total": return String.valueOf(v.autres(p).size());
            default: break;
        }
        if (c.startsWith("moy_")) {
            String l = ligne(lore(v.moyenne(n.getItem(), n.getNoteID().toString()), List.of()), c.substring(4));
            return l == null ? "" : l;
        }
        String l = ligne(v.descriptionOffre(p, n), c);
        return l == null ? "" : l;
    }

    private String vente(Player p, Etat e, String c) {
        Vues v = plugin.vues();
        ItemStack item = v.objetVente(p);
        switch (c) {
            case "choisi": return oui(item != null);
            case "enchere": return oui(e.venteEnchere);
            case "type": return e.venteEnchere ? "Enchère" : "Vente directe";
            case "prix": return argent(e.ventePrix);
            case "net": return argent(Hdv.net(e.ventePrix));
            case "quantite": return String.valueOf(e.venteQuantite);
            case "max": {
                ItemStack s = e.venteSlot >= 0 ? p.getInventory().getItem(e.venteSlot) : null;
                return s == null ? "0" : String.valueOf(s.getAmount());
            }
            default: break;
        }
        if (c.startsWith("moy_")) {
            if (item == null) return "";
            String l = ligne(lore(v.moyenne(item, null), List.of(GAUCHE + "Prix moyen x quantité")), c.substring(4));
            return l == null ? "" : l;
        }
        String l = ligne(v.recapVente(p), c);
        return l == null ? "" : l;
    }
}
