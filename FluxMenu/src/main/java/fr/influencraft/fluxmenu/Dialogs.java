package fr.influencraft.fluxmenu;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.logging.Logger;

/**
 * Petites boîtes de dialogue vanilla (Paper 26.2, {@code io.papermc.paper.dialog}) pour copier
 * un texte dans le presse-papier ou ouvrir un lien en un clic, sans passer par un message de
 * tchat qu'il faut retrouver puis cliquer soi-même.
 *
 * <p>Un {@link ClickEvent} ne s'exécute jamais tout seul côté client : il faut toujours un clic
 * sur un élément qui le porte (texte de tchat, livre, pancarte, ou ici un bouton de dialogue).
 * Impossible donc de déclencher la copie depuis le clic sur le bouton du menu lui-même ; le
 * dialogue est l'étape la plus courte possible (un seul bouton, déjà prêt). Pour un lien, le
 * client affiche en plus sa propre confirmation d'ouverture (sécurité vanilla, non contournable,
 * identique en tchat comme en dialogue) : seul le détour par le tchat disparaît.</p>
 */
final class Dialogs {

    private static Logger logger;

    private Dialogs() {
    }

    static void init(Logger log) {
        logger = log;
    }

    /** Dialogue à un bouton qui copie {@code texte} dans le presse-papier du joueur. */
    static void copier(Player player, String titre, String texte, String libelleBouton) {
        show(player, titre, texte, "&#2BC4E8&n" + texte, libelleBouton, ClickEvent.copyToClipboard(texte));
    }

    /** Dialogue à un bouton qui ouvre {@code url} (confirmation vanilla toujours affichée par le client). */
    static void lien(Player player, String titre, String url, String libelleBouton) {
        show(player, titre, url, "&#2BC4E8&n" + url, libelleBouton, ClickEvent.openUrl(url));
    }

    private static void show(Player player, String titre, String brut, String messageAffiche, String libelleBouton, ClickEvent clic) {
        if (brut == null || brut.isBlank()) {
            if (logger != null) logger.warning("[copy]/[link] texte vide pour " + player.getName() + " (placeholder pas encore chargé ?), dialogue non ouvert");
            return;
        }
        ActionButton bouton = ActionButton.create(
            Text.line(player, libelleBouton), null, 200,
            DialogAction.staticAction(clic)
        );
        DialogBase base = DialogBase.create(
            Text.line(player, titre),
            null,
            true,
            false,
            DialogBase.DialogAfterAction.CLOSE,
            List.of(DialogBody.plainMessage(Text.line(player, messageAffiche))),
            List.of()
        );
        Dialog dialog = Dialog.create(factory -> factory.empty().base(base).type(DialogType.notice(bouton)));
        player.showDialog(dialog);
    }
}
