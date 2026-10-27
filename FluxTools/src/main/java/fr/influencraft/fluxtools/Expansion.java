package fr.influencraft.fluxtools;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static fr.influencraft.fluxtools.Fmt.*;

/**
 * Placeholders {@code %fluxitems_...%}, lus sur l'outil tenu en main (menu de l'outil, scoreboard).
 *
 * <pre>
 *   a (oui/non), page (outil_pass_&lt;page niveaux&gt;_&lt;semaine&gt;, outil_&lt;nœuds&gt; ou outil_aucun), semaine, nom, palier, famille, mode (pass/contrat)
 *   niveau (0 à niveau_max, XP d'outil), niveau_max, maitrise (oui/non, niveau max atteint)
 *   xp, xp_requis, xp_barre (vers le niveau d'outil suivant)
 *   quete_l&lt;k&gt;, noeud_&lt;n&gt;_quete_l&lt;k&gt; (texte coupé à {@link Fmt#LARGEUR} caractères, ligne de menu préfixée)
 *   niveau_&lt;n&gt;_etat (fait/verrou), _xp (XP cumulée requise), _l&lt;k&gt; (k-ième enchantement débloqué à ce niveau, ligne de menu)
 *   etape (réussies), etapes, etat (en_cours/attente/termine), attente (durée)
 *   quete, avance, quantite, pourcent, barre, icone, recompense
 *   blocs, tues, quetes, gagne, atteints (nœuds atteints : progression du chemin du menu)
 *   noeud_&lt;n&gt;_etat (fait/en_cours/attente/verrou), _titre, _quete, _recompense, _gain (bonus d'XP), _ligne_gain
 * </pre>
 */
final class Expansion {

    private static final Pattern QUETE_LIGNE = Pattern.compile("(quete|noeud_\\d+_quete)_l(\\d+)");
    private static final Pattern NIVEAU = Pattern.compile("niveau_(\\d+)_(.+)");
    private static final Pattern NOEUD = Pattern.compile("noeud_(\\d+)_(.+)");

    private final FluxTools plugin;

    Expansion(FluxTools plugin) {
        this.plugin = plugin;
    }

    /** {@code %fluxitems_<param>%}, registered with Flux (resolved without PlaceholderAPI, exposed to it). */
    String request(Player player, String param) {
        return onRequest(player, param);
    }

    String onRequest(OfflinePlayer joueur, String param) {
        if (!(joueur instanceof Player p)) return "";
        ItemStack item = p.getInventory().getItemInMainHand();
        Outil o = plugin.outil(item);
        if (o == null) {
            return switch (param) {
                case "a", "maitrise" -> "non";
                case "page" -> "outil_aucun";
                default -> "";
            };
        }
        Etat e = Etat.lire(item);
        Outil.Quete q = e.termine(o) ? null : o.etapes.get(e.etape).quete(e.quete);
        Matcher ql = QUETE_LIGNE.matcher(param);
        if (ql.matches()) {
            // Texte de quête coupé en lignes de menu (une ligne vide est masquée par Flux).
            String texte = onRequest(joueur, ql.group(1));
            List<String> lignes = texte == null ? List.of() : couper(texte, LARGEUR);
            int k = Integer.parseInt(ql.group(2));
            return k <= lignes.size() ? "&#A33A50│ &#FFD6DE" + lignes.get(k - 1) : "";
        }
        Matcher nv = NIVEAU.matcher(param);
        if (nv.matches()) return niveau(o, e, Integer.parseInt(nv.group(1)), nv.group(2));
        Matcher m = NOEUD.matcher(param);
        if (m.matches()) return noeud(o, e, q, Integer.parseInt(m.group(1)), m.group(2));
        return switch (param) {
            case "a" -> "oui";
            case "page" -> o.aNiveaux() ? "outil_pass_" + pageNiveau(o, e) + "_" + semaine(o, e) : "outil_" + o.noeuds();
            case "semaine" -> String.valueOf(semaine(o, e));
            case "nom" -> o.nom;
            case "palier" -> o.palier;
            case "famille" -> o.famille;
            case "mode" -> o.mode == Outil.Mode.PASS ? "pass" : "contrat";
            case "niveau" -> String.valueOf(o.niveau(e.xp));
            case "niveau_max" -> String.valueOf(o.niveauMax());
            case "maitrise" -> o.aNiveaux() && o.niveau(e.xp) >= o.niveauMax() ? "oui" : "non";
            case "xp" -> nombre(e.xp - o.seuil(o.niveau(e.xp)));
            case "xp_requis" -> o.niveau(e.xp) >= o.niveauMax() ? "0"
                    : nombre(o.seuil(o.niveau(e.xp) + 1) - o.seuil(o.niveau(e.xp)));
            case "xp_barre" -> {
                int n = o.niveau(e.xp);
                yield n >= o.niveauMax() ? barre(1, 1)
                        : barre((int) (e.xp - o.seuil(n)), (int) (o.seuil(n + 1) - o.seuil(n)));
            }
            case "etape" -> String.valueOf(e.etape);
            case "etapes" -> String.valueOf(o.etapes.size());
            case "etat" -> e.termine(o) ? "termine" : e.enAttente() ? "attente" : "en_cours";
            case "attente" -> e.enAttente() ? duree((e.dispo - System.currentTimeMillis()) / 1000) : "";
            case "quete" -> q == null ? "" : e.enAttente() ? "Quête du jour à venir" : q.libelle();
            case "avance" -> nombre(e.avance);
            case "quantite" -> q == null ? "0" : nombre(q.quantite());
            case "pourcent" -> q == null ? "100" : String.valueOf(pourcent(e.avance, q.quantite()));
            case "barre" -> q == null ? barre(1, 1) : barre(e.avance, q.quantite());
            case "icone" -> q == null ? "paper" : q.icone();
            case "recompense" -> e.termine(o) ? "0" : nombre(o.etapes.get(e.etape).recompense());
            case "blocs" -> nombre(e.blocs);
            case "tues" -> nombre(e.tues);
            case "quetes" -> String.valueOf(e.quetes);
            case "gagne" -> nombre(e.gagne);
            case "atteints" -> String.valueOf(atteints(o, e));
            default -> null;
        };
    }

    /** Page de 7 jours de quêtes où se trouve la quête en cours (1, 2...). */
    static int semaine(Outil o, Etat e) {
        return Math.min(e.etape, o.etapes.size() - 1) / 7 + 1;
    }

    /** Page de 7 niveaux où se trouve le prochain niveau à atteindre (1, 2, 3). */
    static int pageNiveau(Outil o, Etat e) {
        return Math.min(o.niveau(e.xp), o.niveauMax() - 1) / 7 + 1;
    }

    static int atteints(Outil o, Etat e) {
        if (!o.noeudsJalons()) return e.etape;
        return e.termine(o) ? o.noeuds() : e.jalons;
    }


    /** Timeline des niveaux du menu : état du niveau n et enchantements qu'il débloque. */
    private static String niveau(Outil o, Etat e, int n, String champ) {
        if (n < 1 || n > o.niveauMax()) return "";
        if (champ.equals("etat")) return o.niveau(e.xp) >= n ? "fait" : "verrou";
        if (champ.equals("xp")) return nombre(o.seuil(n));
        if (!champ.startsWith("l")) return null;
        int k = Integer.parseInt(champ.substring(1)), i = 0;
        for (var en : o.progressifs.entrySet()) {
            Integer v = en.getValue().get(n);
            if (v == null || ++i != k) continue;
            return "&#A33A50│ &#FFD6DE" + Fmt.nomEnchantement(en.getKey()) + " " + romain(v);
        }
        return "";
    }

    private String noeud(Outil o, Etat e, Outil.Quete q, int n, String champ) {
        if (n < 1 || n > o.noeuds()) return "";
        int atteints = atteints(o, e);
        boolean jalons = o.noeudsJalons();
        String etat;
        if (n <= atteints) etat = "fait";
        else if (n == atteints + 1 && !e.termine(o)) etat = !jalons && e.enAttente() ? "attente" : "en_cours";
        else etat = "verrou";

        Outil.Etape etape = o.etapes.get(jalons ? 0 : n - 1);
        return switch (champ) {
            case "etat" -> etat;
            case "titre" -> jalons ? (n == o.noeuds() ? "Fin du contrat" : "Palier " + n) : (o.mode == Outil.Mode.PASS ? "Jour " : "Étape ") + n;
            case "quete" -> {
                if (jalons) {
                    int quantite = n == o.noeuds() ? (q == null ? 0 : q.quantite()) : etape.jalons().get(n - 1).quantite();
                    yield nombre(Math.min(e.avance, quantite)) + "/" + nombre(quantite) + " " + (q == null ? "" : q.libelle());
                }
                yield switch (etat) {
                    case "fait" -> "Quête réussie.";
                    case "en_cours" -> q == null ? "" : q.libelle();
                    case "attente" -> "Disponible dans " + duree((e.dispo - System.currentTimeMillis()) / 1000) + ".";
                    default -> "Quête encore inconnue.";
                };
            }
            case "recompense" -> nombre(jalons ? (n == o.noeuds() ? etape.recompense() : etape.jalons().get(n - 1).recompense())
                    : etape.recompense());
            case "gain" -> o.aNiveaux() && etape.xp() > 0 ? "+" + nombre(etape.xp()) + " XP d'outil" : "";
            case "ligne_gain" -> o.aNiveaux() && etape.xp() > 0 && !jalons
                    ? "&#A33A50│ &#FFD6DEBonus : &f+" + nombre(etape.xp()) + " XP d'outil" : "";
            default -> null;
        };
    }
}
