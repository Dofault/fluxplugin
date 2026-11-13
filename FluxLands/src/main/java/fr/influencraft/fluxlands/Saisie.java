package fr.influencraft.fluxlands;

import fr.influencraft.flux.api.dialog.Dialogs;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Typed inputs of the town menus (names, search, invitations; amounts use {@code Dialogs.Form#amount}), as Flux dialog windows instead of Lands'
 * and PlayerWarps' "type your answer in the chat". Cancel, Escape or an empty answer reopen {@code menuRetour}.
 */
final class Saisie {

    private final FluxLands plugin;

    Saisie(FluxLands plugin) {
        this.plugin = plugin;
    }

    /** One-line input; {@code apres} receives the trimmed text on the main thread. */
    void ouvrir(Player p, String titre, String explication, String libelle, String initial, int max,
                String menuRetour, BiConsumer<Player, String> apres) {
        Dialogs.form(p, titre).body(explication).requiredInput("valeur", libelle, initial, max)
                .onSubmit((pl, a) -> apres.accept(pl, a.text("valeur")))
                .onCancel(pl -> plugin.ouvrirMenu(pl, menuRetour))
                .open();
    }

    /**
     * Drop-down list (online players, towns...) plus a free field: the typed text, when any, wins over the list.
     * Empty list: a simple text field.
     */
    void choisir(Player p, String titre, String explication, String libelleListe, List<String> options,
                 String libelleTexte, int max, String menuRetour, BiConsumer<Player, String> apres) {
        if (options.isEmpty()) {
            ouvrir(p, titre, explication, libelleTexte, "", max, menuRetour, apres);
            return;
        }
        Dialogs.form(p, titre).body(explication).choice("choix", libelleListe, options).input("valeur", libelleTexte, "", max)
                .onSubmit((pl, a) -> {
                    String value = !a.text("valeur").isEmpty() ? a.text("valeur") : a.text("choix");
                    if (value.isEmpty()) plugin.ouvrirMenu(pl, menuRetour);
                    else apres.accept(pl, value);
                })
                .onCancel(pl -> plugin.ouvrirMenu(pl, menuRetour))
                .open();
    }

    /** Answer of a "text + check box" input. */
    interface TexteEtCase {
        void accepter(Player p, String texte, boolean coche);
    }

    /** One line and a check box (e.g. plot name + whole height). */
    void ouvrirAvecCase(Player p, String titre, String explication, String libelle, String libelleCase, boolean coche,
                        int max, String menuRetour, TexteEtCase apres) {
        Dialogs.form(p, titre).body(explication).requiredInput("valeur", libelle, "", max).toggle("case", libelleCase, coche)
                .onSubmit((pl, a) -> apres.accepter(pl, a.text("valeur"), a.toggle("case")))
                .onCancel(pl -> plugin.ouvrirMenu(pl, menuRetour))
                .open();
    }

    /** What follows the creation of a plot: nothing (its menu opens), or the Agency window to rent or sell it. */
    enum Ensuite {
        RIEN(Lang.t("saisie.ouvrir-la-parcelle")), LOCATION(Lang.t("saisie.la-mettre-en-location")), VENTE(Lang.t("saisie.la-mettre-en-vente"));

        final String libelle;

        Ensuite(String libelle) {
            this.libelle = libelle;
        }

        static Ensuite de(String libelle) {
            for (Ensuite e : values()) if (e.libelle.equals(libelle)) return e;
            return RIEN;
        }
    }

    /** Answer of the plot window: Y min / Y max are null when left empty. */
    interface ReponseParcelle {
        void accepter(Player p, String nom, boolean solAuCiel, Integer yMin, Integer yMax, Ensuite ensuite);
    }

    /** Plot name, "from ground to sky" box, two optional Y min / Y max fields and what to do once created. */
    void ouvrirParcelle(Player p, String titre, String explication, String nomInitial, boolean solAuCiel,
                        String yMinInitial, String yMaxInitial, String menuRetour, ReponseParcelle apres) {
        Dialogs.form(p, titre).body(explication)
                .requiredInput("valeur", Lang.t("saisie.nom-de-la-parcelle"), nomInitial, 24)
                .toggle("case", Lang.t("saisie.du-sol-au-ciel-toute"), solAuCiel)
                .smallInput("ymin", Lang.t("saisie.y-min-vide-selection"), yMinInitial, 5)
                .smallInput("ymax", Lang.t("saisie.y-max-vide-selection"), yMaxInitial, 5)
                .choice("ensuite", "Ensuite", java.util.Arrays.stream(Ensuite.values()).map(e -> e.libelle).toList())
                .buttons(nomInitial.isEmpty() ? Lang.t("saisie.creer-la-parcelle") : Lang.t("saisie.delimiter"), "Annuler")
                .onSubmit((pl, a) -> apres.accepter(pl, a.text("valeur").replace(' ', '_'), a.toggle("case"),
                        a.integer("ymin"), a.integer("ymax"), Ensuite.de(a.text("ensuite"))))
                .onCancel(pl -> plugin.ouvrirMenu(pl, menuRetour))
                .open();
    }
}
