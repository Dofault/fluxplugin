package fr.influencraft.fluxauctionhouse;

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

/** Saisie d'une ligne par boîte de dialogue vanilla (Paper 26.2), comme dans FluxLands : recherche, prix, quantité. */
final class Saisie {

    private Saisie() {
    }

    /** {@code apres} reçoit le texte validé sur le fil principal ; Annuler ou un texte vide rouvrent {@code menuRetour}. */
    static void ouvrir(FluxAuctionHouse plugin, Player p, String titre, String explication, String libelle, String initial,
                       String menuRetour, BiConsumer<Player, String> apres) {
        DialogInput champ = DialogInput.text("valeur", Fmt.ligne(p, Fmt.VIOLET + libelle))
                .initial(initial == null ? "" : initial).maxLength(48).width(220).build();
        ClickCallback.Options une = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build();
        ActionButton valider = ActionButton.create(Fmt.ligne(p, Fmt.VERT + "Valider"), null, 100,
                DialogAction.customClick((vue, audience) -> {
                    String texte = vue.getText("valeur");
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!p.isOnline()) return;
                        if (texte == null || texte.isBlank()) plugin.ouvrirMenu(p, menuRetour);
                        else apres.accept(p, texte.trim());
                    });
                }, une));
        ActionButton annuler = ActionButton.create(Fmt.ligne(p, Fmt.ROUGE + "Annuler"), null, 100,
                DialogAction.customClick((vue, audience) -> Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.ouvrirMenu(p, menuRetour)), une));
        DialogBase base = DialogBase.create(
                Fmt.ligne(p, Fmt.OR + titre), null, true, false, DialogBase.DialogAfterAction.CLOSE,
                List.of(DialogBody.plainMessage(Fmt.ligne(p, Fmt.VIOLET + explication))),
                List.of(champ));
        p.showDialog(Dialog.create(f -> f.empty().base(base).type(DialogType.confirmation(valider, annuler))));
    }
}
