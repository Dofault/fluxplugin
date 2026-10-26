package fr.influencraft.influmarche;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalogue unifié du marché : offres de l'hôtel des ventes et des échoppes, recalculé au plus
 * toutes les {@code catalogue-secondes} (et dès qu'une vente passe par InfluMarche). Une
 * recherche « diamant » ou « DIAMOND » y trouve aussi bien une vente directe qu'une échoppe.
 */
final class Catalogue {

    /** Sources (onglets du menu) : 0 tout ce qui est à vendre, 1 hôtel des ventes, 2 échoppes, 3 rachats. */
    static final String[] SOURCES = {"Tout", "Hôtel des ventes", "Échoppes", "Rachats"};
    static final String[] TRIS = {"Prix unitaire croissant", "Prix unitaire décroissant", "Plus récentes", "Fin la plus proche", "Nom"};

    private final InfluMarche plugin;
    private List<Offre> offres = List.of();
    private Map<String, Offre> parId = Map.of();
    private long calcule;

    Catalogue(InfluMarche plugin) {
        this.plugin = plugin;
    }

    void invalider() {
        calcule = 0;
    }

    List<Offre> toutes() {
        long now = System.currentTimeMillis();
        if (now - calcule > plugin.getConfig().getInt("catalogue-secondes", 2) * 1000L) {
            List<Offre> l = new ArrayList<>(plugin.hdv().offres());
            if (plugin.echoppes() != null) l.addAll(plugin.echoppes().offres());
            Map<String, Offre> m = new HashMap<>();
            for (Offre o : l) m.put(o.id(), o);
            offres = l;
            parId = m;
            calcule = now;
        }
        return offres;
    }

    Offre parId(String id) {
        toutes();
        return parId.get(id);
    }

    /** Offres d'un joueur filtrées et triées selon son état. */
    List<Offre> filtrer(Etat e) {
        List<Offre> l = new ArrayList<>();
        Objets.Categorie cat = Objets.Categorie.values()[e.categorie];
        for (Offre o : toutes()) {
            boolean garde = switch (e.source) {
                case 1 -> o.type().hdv();
                case 2 -> o.type() == Offre.Type.ECHOPPE;
                case 3 -> o.type() == Offre.Type.RACHAT;
                default -> o.type() != Offre.Type.RACHAT;
            };
            if (!garde) continue;
            if (cat != Objets.Categorie.TOUT && o.categorie() != cat) continue;
            if (!Objets.correspond(o.index(), e.recherche)) continue;
            l.add(o);
        }
        l.sort(tri(e));
        return l;
    }

    private static Comparator<Offre> tri(Etat e) {
        Comparator<Offre> prix = Comparator.comparingDouble(Offre::prixUnite);
        return switch (e.tri) {
            // Rachats : le meilleur acheteur (prix le plus haut) d'abord, l'inverse des ventes.
            case 0 -> e.source == 3 ? prix.reversed() : prix;
            case 1 -> e.source == 3 ? prix : prix.reversed();
            case 2 -> Comparator.comparingLong((Offre o) -> o.type().hdv() ? 0 : 1)
                    .thenComparing(Comparator.comparingLong(Offre::finDans).reversed())
                    .thenComparing(Comparator.comparing(Offre::id).reversed());
            case 3 -> Comparator.comparingLong((Offre o) -> o.finDans() < 0 ? Long.MAX_VALUE : o.finDans());
            default -> Comparator.comparing((Offre o) -> Objets.normaliser(o.nom())).thenComparing(prix);
        };
    }

    /** Autres offres du même objet (même clé), les moins chères d'abord, sans l'offre donnée. */
    List<Offre> memeObjet(String cle, String sauf, boolean rachats, int max) {
        List<Offre> l = new ArrayList<>();
        for (Offre o : toutes()) {
            if (!o.cle().equals(cle) || o.id().equals(sauf)) continue;
            if ((o.type() == Offre.Type.RACHAT) != rachats) continue;
            l.add(o);
        }
        Comparator<Offre> c = Comparator.comparingDouble(Offre::prixUnite);
        l.sort(rachats ? c.reversed() : c);
        return l.size() > max ? l.subList(0, max) : l;
    }
}
