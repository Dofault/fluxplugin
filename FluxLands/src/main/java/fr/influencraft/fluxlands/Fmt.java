package fr.influencraft.fluxlands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Numbers and messages: interface texts in {@code &} colours, chat messages in Lands' theme tokens. */
final class Fmt {

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

    /** Value compared by the menu conditions ("== oui"): fixed, never translated. */
    static String oui(boolean b) {
        return b ? "oui" : "non";
    }

    /** Interface text (dialog option, item name): placeholders (glyphs %img_...%) then {@code &} colours. */
    static Component ligne(Player p, String brut) {
        // the Flux text pipeline (suite placeholders, PlaceholderAPI, glyphs, colours)
        return fr.influencraft.flux.api.text.Texts.parse(brut, p);
    }

    // ── Chat messages in Lands' look ────────────────────────────────────────
    // Written with Lands' theme tokens (Lands/Locale/theme.yml, theme.custom; docs/notifications/villes.md):
    // <t:texte> body, <t:valeur> highlighted value, <t:ville> town name, <t:commande> command... closed by </t>.
    // Prefix and colours therefore follow Lands' own messages whenever its theme changes.

    /** Chat line: Lands' prefix then the text in {@code texte}; {@code [/command]} shortcuts are clickable. */
    static Component message(Player p, String texte) {
        String legacy = Couleurs.legacy(Couleurs.prefixe() + "<t:texte>" + texte + "</t>");
        return fr.influencraft.flux.api.text.Texts.parseClickable(legacy, p);
    }

    static void envoyer(Player p, String texte) {
        if (p != null && p.isOnline()) p.sendMessage(message(p, texte));
    }

    /** Refusal: the whole sentence in Lands' error colour. */
    static void erreur(Player p, String phrase) {
        envoyer(p, "<t:erreur>" + phrase + "</t>");
    }

    /** Refusal as Lands writes it: the refusal in the error colour, then its explanation in the body colour. */
    static void erreur(Player p, String phrase, String explication) {
        envoyer(p, "<t:erreur>" + phrase + "</t> " + explication);
    }

    /** Message followed by Lands' action line {@code ➤ Label [CLIQUER]}, whose click runs {@code commande}. */
    static void action(Player p, String texte, String libelle, String commande) {
        if (p == null || !p.isOnline()) return;
        Component ligne = fr.influencraft.flux.api.text.Texts.parse(Couleurs.legacy(
                "<t:deco>➤</t> <t:texte>" + libelle + "</t> <t:bouton>[CLIQUER]</t>"), p)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(commande))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        fr.influencraft.flux.api.text.Texts.parse(Couleurs.legacy(Lang.t("fmt.clique-pour") + libelle.toLowerCase(Locale.ROOT) + "."), p)));
        p.sendMessage(message(p, texte).append(Component.newline()).append(Component.text("  ")).append(ligne));
    }

    /**
     * Money notification (droit de place): keeps the server-wide BitBlock gain / loss look
     * (docs/notifications/bitblock.md) instead of Lands' one; {@code &} colours.
     */
    static void notification(Player p, String brut) {
        if (p != null && p.isOnline()) p.sendMessage(ligne(p, brut));
    }

    /** Highlighted value (plot, player, duration, coordinates); text from players is escaped. */
    static String v(Object valeur) {
        return "<t:valeur>" + echapper(valeur) + "</t>";
    }

    /** Town or nation name. */
    static String ville(Object nom) {
        return "<t:ville>" + echapper(nom) + "</t>";
    }

    /** Amount of BitBlock with its glyph (white before the glyph, docs/notifications/bitblock.md). */
    static String bitblock(double montant) {
        return "<t:montant>" + argent(montant) + " %img_bitblock%</t>";
    }

    /** Command shown to the player, between brackets (clickable, displayed in the server's command style). */
    static String cmd(String commande) {
        return "<t:commande>[" + commande + "]</t>";
    }

    private static String echapper(Object o) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(String.valueOf(o));
    }

    /** Splits a sentence to the suite's lore width (lores). */
    static java.util.List<String> couper(String texte) {
        if (texte == null || texte.isBlank()) return new java.util.ArrayList<>();
        return new java.util.ArrayList<>(fr.influencraft.flux.api.text.Texts.wrap(texte.trim()));
    }
}
