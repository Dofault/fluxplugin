package fr.influencraft.influmarche;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Mise en forme des nombres, durées et messages (couleurs {@code &} et {@code &#RRGGBB}).
 *
 * <p>Les textes renvoyés par nos placeholders ne sont pas relus par PlaceholderAPI : les glyphes y
 * sont écrits en caractères bruts (clic gauche U+E021, clic droit U+E022, BitBlock U+E023), précédés
 * d'un reset (charte, CLAUDE.md).</p>
 */
final class Fmt {

    static final String OR = "&#FFD94D", VIOLET = "&#B376E5", VERT = "&#2ECF7B", ROUGE = "&#FF5C5C", BLEU = "&#2BC4E8";
    static final String BB = "&r&f";
    static final String GAUCHE = "&r&7 &f", DROIT = "&r&7 &f";
    static final String MAJ_GAUCHE = "&r&7Maj +  &f";
    /** Source des messages de l'hôtel des ventes (petites capitales, convention notifications). */
    static final String SOURCE = "&7[ʜᴅᴠ]";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexCharacter('#').hexColors().build();

    private Fmt() {
    }

    /** Montant entier groupé par milliers à la française : « 12 345 ». */
    static String argent(double montant) {
        DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.FRANCE);
        s.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0", s).format(Math.floor(montant + 1e-9));
    }

    /** Prix unitaire : deux décimales sous 100, entier au-delà (« 12,50 », « 1 250 »). */
    static String unite(double prix) {
        if (prix >= 100) return argent(Math.round(prix));
        DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.FRANCE);
        s.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0.##", s).format(prix);
    }

    static String taux(double pourcentage) {
        return new DecimalFormat("0.#", new DecimalFormatSymbols(Locale.FRANCE)).format(pourcentage);
    }

    static String oui(boolean b) {
        return b ? "oui" : "non";
    }

    /** Durée courte : « 2 j 5 h », « 3 h 12 min », « 45 s ». */
    static String duree(long secondes) {
        if (secondes <= 0) return "terminé";
        long j = secondes / 86400, h = secondes % 86400 / 3600, m = secondes % 3600 / 60;
        if (j > 0) return j + " j " + h + " h";
        if (h > 0) return h + " h " + m + " min";
        if (m > 0) return m + " min";
        return secondes + " s";
    }

    static String date(long ms) {
        return new SimpleDateFormat("dd/MM HH'h'mm", Locale.FRANCE).format(new Date(ms));
    }

    /** Ancienneté : « il y a 3 h », « il y a 2 j ». */
    static String ilYa(long ms) {
        long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
        if (s < 60) return "à l'instant";
        return "il y a " + duree(s);
    }

    /** Message de tchat : placeholders (glyphes %img_...%) puis couleurs, sans italique. */
    static Component ligne(Player p, String brut) {
        String txt = brut;
        if (p != null && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            txt = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, txt);
        }
        return LEGACY.deserialize(txt).decoration(TextDecoration.ITALIC, false);
    }

    static void envoyer(Player p, String brut) {
        if (p != null && p.isOnline()) p.sendMessage(ligne(p, brut));
    }

    static void erreur(Player p, String texte) {
        envoyer(p, ROUGE + texte);
    }

    /** Ligne de notification BitBlock (docs/notifications/bitblock.md) : gain = vert ▲, perte = rouge ▼. */
    static String bitblock(String verbe, double montant, boolean gain, String suite) {
        return "&7 " + VIOLET + verbe + " &f" + argent(montant) + " " + VIOLET + "Bitblock "
                + (gain ? VERT + "▲" : ROUGE + "▼") + "&7" + suite + " " + SOURCE;
    }

    /** Coupe une phrase en lignes d'au plus {@code max} caractères. */
    static List<String> couper(String texte, int max) {
        List<String> lignes = new ArrayList<>();
        if (texte == null || texte.isBlank()) return lignes;
        StringBuilder cur = new StringBuilder();
        for (String mot : texte.trim().split("\\s+")) {
            if (!cur.isEmpty() && cur.length() + 1 + mot.length() > max) {
                lignes.add(cur.toString());
                cur.setLength(0);
            }
            if (!cur.isEmpty()) cur.append(' ');
            cur.append(mot);
        }
        if (!cur.isEmpty()) lignes.add(cur.toString());
        return lignes;
    }

    /** Lore au style de /menu2 : description (│), puis actions (├ ... └). */
    static List<String> lore(List<String> desc, List<String> actions) {
        List<String> l = new ArrayList<>();
        for (String d : desc) l.add(d.isEmpty() ? OR + "│" : OR + "│ " + VIOLET + d);
        for (int i = 0; i < actions.size(); i++) l.add(OR + (i == actions.size() - 1 ? "└ " : "├ ") + actions.get(i));
        if (actions.isEmpty() && !l.isEmpty()) l.set(l.size() - 1, l.getLast().replaceFirst("│", "└"));
        return l;
    }

    /** Montant entier positif saisi, ou -1 (accepte « 10 000 », « 10k », « 2,5k », « 1m »). */
    static long montant(String s) {
        String t = s.toLowerCase(Locale.ROOT).replace(" ", "").replace(" ", "").replace(" ", "").replace(",", ".");
        double mult = 1;
        if (t.endsWith("k")) {
            mult = 1_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            mult = 1_000_000;
            t = t.substring(0, t.length() - 1);
        }
        try {
            double v = Double.parseDouble(t) * mult;
            return v >= 1 ? (long) Math.floor(v) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
