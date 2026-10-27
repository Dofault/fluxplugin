package fr.influencraft.fluxlands;

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
        return fr.influencraft.flux.api.util.Format.number(montant);
    }

    /** Taux en pourcentage sans décimale inutile : « 5 », « 2,5 ». */
    static String taux(double pourcentage) {
        return fr.influencraft.flux.api.util.Format.percent(pourcentage);
    }

    static String oui(boolean b) {
        return fr.influencraft.flux.api.util.Format.yesNo(b);
    }

    /** Message de tchat : placeholders (glyphes %img_...%) puis couleurs, sans italique. */
    static Component ligne(Player p, String brut) {
        // the Flux text pipeline (suite placeholders, PlaceholderAPI, glyphs, colours)
        return fr.influencraft.flux.api.text.Texts.parse(brut, p);
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
        if (texte == null || texte.isBlank()) return new java.util.ArrayList<>();
        return new java.util.ArrayList<>(fr.influencraft.flux.api.text.Texts.wrap(texte.trim(), max));
    }
}
