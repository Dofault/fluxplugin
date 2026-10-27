package fr.influencraft.fluxtools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Mise en forme des nombres, durées et textes (couleurs {@code &} et {@code &#RRGGBB}). */
final class Fmt {

    /**
     * Palette FluxTools (« rubis »), propre au plugin, pour les messages, la description des outils et le
     * menu : rubis (titres), rose pâle (texte), grenat (filets, secondaire), menthe (réussi), gris rosé (pas atteint).
     */
    static final String TITRE = "&#FF4D6D", TEXTE = "&#FFD6DE", FILET = "&#A33A50", OK = "&#7BE0A1", KO = "&#A89BA0";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexCharacter('#').hexColors().build();
    private static final String[] ROMAIN = {"0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private static final java.util.Map<String, String> VANILLA = java.util.Map.of(
            "efficiency", "Efficacité", "fortune", "Fortune", "unbreaking", "Solidité", "sharpness", "Tranchant",
            "looting", "Butin", "power", "Puissance", "density", "Densité", "flame", "Flamme", "silk_touch", "Toucher de soie");

    private Fmt() {
    }

    /** Texte d'objet (nom, description) : sans l'italique que Minecraft ajoute par défaut. */
    static Component objet(String s) {
        return LEGACY.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    static Component texte(String s) {
        return LEGACY.deserialize(s);
    }

    /** Entier groupé par milliers à la française : « 12 345 ». */
    static String nombre(double n) {
        DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.FRANCE);
        s.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0", s).format(Math.floor(n + 1e-9));
    }

    /** Nom français d'un enchantement pour un texte de menu (vanilla : table, EcoEnchants : son nom affiché). */
    static String nomEnchantement(String id) {
        String v = VANILLA.get(id);
        if (v != null) return v;
        org.bukkit.enchantments.Enchantment e = Outil.enchantement(id);
        if (e == null) return id;
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(e.description()).replace("✦", "").trim();
    }

    static String romain(int n) {
        return n >= 0 && n < ROMAIN.length ? ROMAIN[n] : String.valueOf(n);
    }

    static int pourcent(int avance, int total) {
        return total <= 0 ? 100 : (int) Math.min(100, Math.floor(avance * 100.0 / total));
    }

    /** Largeur maximale (caractères visibles) d'une ligne de description ou de menu. */
    static final int LARGEUR = 30;

    /** Coupe un texte coloré en lignes d'au plus {@code largeur} caractères visibles, couleur reportée. */
    static java.util.List<String> couper(String texte, int largeur) {
        // the suite's lore wrapping (colours carried over to the next line)
        return new java.util.ArrayList<>(fr.influencraft.flux.api.text.Texts.wrap(texte, largeur));
    }

    /** Barre de 20 traits : partie faite en vert, reste en gris. */
    static String barre(int avance, int total) {
        int plein = total <= 0 ? 20 : (int) Math.min(20, Math.floor(avance * 20.0 / total));
        return TITRE + "|".repeat(plein) + "&8" + "|".repeat(20 - plein);
    }

    /** Durée courte : « 2 j 5 h », « 3 h 12 min », « 45 s ». */
    static String duree(long secondes) {
        if (secondes <= 0) return "maintenant";
        long j = secondes / 86400, h = secondes % 86400 / 3600, m = secondes % 3600 / 60;
        if (j > 0) return j + " j " + h + " h";
        if (h > 0) return h + " h " + m + " min";
        if (m > 0) return m + " min";
        return secondes + " s";
    }
}
