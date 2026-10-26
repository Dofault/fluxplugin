package fr.influencraft.fluxlands;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Saisies au clavier par boîte de dialogue vanilla (Paper 26.2) : montant à déposer ou
 * retirer, nom de ville, nom d'adresse, recherche. Remplace le « tape ta réponse dans le
 * tchat » de Lands et InfluPW : un champ, Valider, Annuler.
 */
final class Saisie {

    private final FluxLands plugin;

    Saisie(FluxLands plugin) {
        this.plugin = plugin;
    }

    /**
     * Ouvre une saisie d'une ligne. {@code apres} reçoit le texte validé, sur le fil principal.
     * Annuler (ou Échap) rouvre {@code menuRetour} s'il est donné.
     */
    void ouvrir(Player p, String titre, String explication, String libelle, String initial, int max,
                String menuRetour, BiConsumer<Player, String> apres) {
        DialogInput champ = DialogInput.text("valeur", Fmt.ligne(p, "&#B376E5" + libelle))
                .initial(initial == null ? "" : initial).maxLength(max).width(220).build();
        ClickCallback.Options une = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build();
        ActionButton valider = ActionButton.create(Fmt.ligne(p, "&#2ECF7BValider"), null, 100,
                DialogAction.customClick((vue, audience) -> {
                    String texte = vue.getText("valeur");
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!p.isOnline()) return;
                        if (texte == null || texte.isBlank()) {
                            plugin.ouvrirMenu(p, menuRetour);
                            return;
                        }
                        apres.accept(p, texte.trim());
                    });
                }, une));
        ActionButton annuler = ActionButton.create(Fmt.ligne(p, "&#FF5C5CAnnuler"), null, 100,
                DialogAction.customClick((vue, audience) -> Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.ouvrirMenu(p, menuRetour)), une));
        DialogBase base = DialogBase.create(
                Fmt.ligne(p, "&#FFD94D" + titre), null, true, false, DialogBase.DialogAfterAction.CLOSE,
                List.of(DialogBody.plainMessage(Fmt.ligne(p, "&#B376E5" + explication))),
                List.of(champ));
        p.showDialog(Dialog.create(f -> f.empty().base(base).type(DialogType.confirmation(valider, annuler))));
    }

    /** Montant entier positif, ou -1 (accepte « 10 000 », « 10k », « 2,5k »). */
    static long montant(String s) {
        String t = s.toLowerCase(java.util.Locale.ROOT).replace(" ", "").replace(" ", "").replace(",", ".");
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

    /**
     * Saisie avec liste déroulante (joueurs connectés, villes...) et champ libre : le texte tapé,
     * s'il y en a un, l'emporte sur le choix de la liste. Liste vide : simple champ texte.
     */
    void choisir(Player p, String titre, String explication, String libelleListe, List<String> options,
                 String libelleTexte, int max, String menuRetour, BiConsumer<Player, String> apres) {
        if (options.isEmpty()) {
            ouvrir(p, titre, explication, libelleTexte, "", max, menuRetour, apres);
            return;
        }
        List<io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry> entrees = new java.util.ArrayList<>();
        for (int i = 0; i < options.size() && i < 100; i++) {
            entrees.add(io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry.create(
                    "o" + i, Fmt.ligne(p, "&f" + options.get(i)), i == 0));
        }
        DialogInput liste = DialogInput.singleOption("choix", Fmt.ligne(p, "&#B376E5" + libelleListe), entrees).width(220).build();
        DialogInput champ = DialogInput.text("valeur", Fmt.ligne(p, "&#B376E5" + libelleTexte)).initial("").maxLength(max).width(220).build();
        ClickCallback.Options une = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build();
        ActionButton valider = ActionButton.create(Fmt.ligne(p, "&#2ECF7BValider"), null, 100,
                DialogAction.customClick((vue, audience) -> {
                    String texte = vue.getText("valeur");
                    String choix = vue.getText("choix");
                    String valeur = texte != null && !texte.isBlank() ? texte.trim() : null;
                    if (valeur == null && choix != null && choix.startsWith("o")) {
                        try {
                            valeur = options.get(Integer.parseInt(choix.substring(1)));
                        } catch (RuntimeException ignored) {
                        }
                    }
                    String finale = valeur;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!p.isOnline()) return;
                        if (finale == null || finale.isBlank()) plugin.ouvrirMenu(p, menuRetour);
                        else apres.accept(p, finale);
                    });
                }, une));
        ActionButton annuler = ActionButton.create(Fmt.ligne(p, "&#FF5C5CAnnuler"), null, 100,
                DialogAction.customClick((vue, audience) -> Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.ouvrirMenu(p, menuRetour)), une));
        DialogBase base = DialogBase.create(
                Fmt.ligne(p, "&#FFD94D" + titre), null, true, false, DialogBase.DialogAfterAction.CLOSE,
                List.of(DialogBody.plainMessage(Fmt.ligne(p, "&#B376E5" + explication))),
                List.of(liste, champ));
        p.showDialog(Dialog.create(f -> f.empty().base(base).type(DialogType.confirmation(valider, annuler))));
    }

    /** Réponse d'une saisie « texte + case à cocher ». */
    interface TexteEtCase {
        void accepter(Player p, String texte, boolean coche);
    }

    /** Saisie d'une ligne avec une case à cocher (ex : nom de parcelle + toute la hauteur). */
    void ouvrirAvecCase(Player p, String titre, String explication, String libelle, String libelleCase, boolean coche,
                        int max, String menuRetour, TexteEtCase apres) {
        DialogInput champ = DialogInput.text("valeur", Fmt.ligne(p, "&#B376E5" + libelle)).initial("").maxLength(max).width(220).build();
        DialogInput caseACocher = DialogInput.bool("case", Fmt.ligne(p, "&f" + libelleCase), coche, "oui", "non");
        ClickCallback.Options une = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build();
        ActionButton valider = ActionButton.create(Fmt.ligne(p, "&#2ECF7BValider"), null, 100,
                DialogAction.customClick((vue, audience) -> {
                    String texte = vue.getText("valeur");
                    Boolean b = vue.getBoolean("case");
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!p.isOnline()) return;
                        if (texte == null || texte.isBlank()) plugin.ouvrirMenu(p, menuRetour);
                        else apres.accepter(p, texte.trim(), b == null || b);
                    });
                }, une));
        ActionButton annuler = ActionButton.create(Fmt.ligne(p, "&#FF5C5CAnnuler"), null, 100,
                DialogAction.customClick((vue, audience) -> Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.ouvrirMenu(p, menuRetour)), une));
        DialogBase base = DialogBase.create(
                Fmt.ligne(p, "&#FFD94D" + titre), null, true, false, DialogBase.DialogAfterAction.CLOSE,
                List.of(DialogBody.plainMessage(Fmt.ligne(p, "&#B376E5" + explication))),
                List.of(champ, caseACocher));
        p.showDialog(Dialog.create(f -> f.empty().base(base).type(DialogType.confirmation(valider, annuler))));
    }

    /** Réponse de la fenêtre de parcelle : Y min / Y max null quand le champ est laissé vide. */
    interface ReponseParcelle {
        void accepter(Player p, String nom, boolean solAuCiel, Integer yMin, Integer yMax);
    }

    /** Nom de parcelle, case « Du sol au ciel » et deux champs facultatifs Y min / Y max. */
    void ouvrirParcelle(Player p, String titre, String explication, String nomInitial, boolean solAuCiel,
                        String yMinInitial, String yMaxInitial, String menuRetour, ReponseParcelle apres) {
        DialogInput champ = DialogInput.text("valeur", Fmt.ligne(p, "&#B376E5Nom de la parcelle")).initial(nomInitial).maxLength(24).width(220).build();
        DialogInput caseACocher = DialogInput.bool("case", Fmt.ligne(p, "&fDu sol au ciel (toute la hauteur)"), solAuCiel, "oui", "non");
        DialogInput yMin = DialogInput.text("ymin", Fmt.ligne(p, "&#B376E5Y min (vide = sélection)")).initial(yMinInitial).maxLength(5).width(110).build();
        DialogInput yMax = DialogInput.text("ymax", Fmt.ligne(p, "&#B376E5Y max (vide = sélection)")).initial(yMaxInitial).maxLength(5).width(110).build();
        ClickCallback.Options une = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build();
        ActionButton valider = ActionButton.create(Fmt.ligne(p, "&#2ECF7BValider"), null, 100,
                DialogAction.customClick((vue, audience) -> {
                    String nom = vue.getText("valeur");
                    Boolean b = vue.getBoolean("case");
                    Integer a = entier(vue.getText("ymin"));
                    Integer z = entier(vue.getText("ymax"));
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!p.isOnline()) return;
                        if (nom == null || nom.isBlank()) plugin.ouvrirMenu(p, menuRetour);
                        else apres.accepter(p, nom.trim().replace(' ', '_'), b == null || b, a, z);
                    });
                }, une));
        ActionButton annuler = ActionButton.create(Fmt.ligne(p, "&#FF5C5CAnnuler"), null, 100,
                DialogAction.customClick((vue, audience) -> Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.ouvrirMenu(p, menuRetour)), une));
        DialogBase base = DialogBase.create(
                Fmt.ligne(p, "&#FFD94D" + titre), null, true, false, DialogBase.DialogAfterAction.CLOSE,
                List.of(DialogBody.plainMessage(Fmt.ligne(p, "&#B376E5" + explication))),
                List.of(champ, caseACocher, yMin, yMax));
        p.showDialog(Dialog.create(f -> f.empty().base(base).type(DialogType.confirmation(valider, annuler))));
    }

    private static Integer entier(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
