package fr.influencraft.influlands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Mise en forme des nombres et des messages (couleurs {@code &} et {@code &#RRGGBB}, PlaceholderAPI). */
final class Fmt {

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

    /** Taux en pourcentage sans décimale inutile : « 5 », « 2,5 ». */
    static String taux(double pourcentage) {
        DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.FRANCE);
        return new DecimalFormat("0.#", s).format(pourcentage);
    }

    static String oui(boolean b) {
        return b ? "oui" : "non";
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

    /** Erreur courte (rouge de la charte). */
    static void erreur(Player p, String texte) {
        envoyer(p, "&#FF5C5C" + texte);
    }

    /** Coupe une phrase en lignes d'au plus {@code max} caractères (lores). */
    static java.util.List<String> couper(String texte, int max) {
        java.util.List<String> lignes = new java.util.ArrayList<>();
        if (texte == null || texte.isBlank()) return lignes;
        StringBuilder cur = new StringBuilder();
        for (String mot : texte.trim().split("\\s+")) {
            if (cur.length() > 0 && cur.length() + 1 + mot.length() > max) {
                lignes.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(mot);
        }
        if (cur.length() > 0) lignes.add(cur.toString());
        return lignes;
    }
}
