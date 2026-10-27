package fr.influencraft.fluxauctionhouse;

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
        return fr.influencraft.flux.api.util.Format.number(montant);
    }

    /** Prix unitaire : deux décimales sous 100, entier au-delà (« 12,50 », « 1 250 »). */
    static String unite(double prix) {
        if (prix >= 100) return argent(Math.round(prix));
        return fr.influencraft.flux.api.util.Format.decimal(prix);
    }

    static String taux(double pourcentage) {
        return fr.influencraft.flux.api.util.Format.percent(pourcentage);
    }

    static String oui(boolean b) {
        return fr.influencraft.flux.api.util.Format.yesNo(b);
    }

    /** Durée courte : « 2 j 5 h », « 3 h 12 min », « 45 s ». */
    static String duree(long secondes) {
        return fr.influencraft.flux.api.util.Format.duration(secondes);
    }

    static String date(long ms) {
        return fr.influencraft.flux.api.util.Format.date(ms);
    }

    /** Ancienneté : « il y a 3 h », « il y a 2 j ». */
    static String ilYa(long ms) {
        return fr.influencraft.flux.api.util.Format.ago(ms);
    }

    /** Message de tchat : placeholders (glyphes %img_...%) puis couleurs, sans italique. */
    static Component ligne(Player p, String brut) {
        // the Flux text pipeline (suite placeholders, PlaceholderAPI, glyphs, colours)
        return fr.influencraft.flux.api.text.Texts.parse(brut, p);
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
