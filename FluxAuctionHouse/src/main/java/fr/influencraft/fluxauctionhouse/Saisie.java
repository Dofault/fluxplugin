package fr.influencraft.fluxauctionhouse;

import fr.influencraft.flux.api.dialog.Dialogs;
import org.bukkit.entity.Player;

import java.util.function.BiConsumer;

/** One-line typed input of the market (search, price, amount), as a Flux dialog window. */
final class Saisie {

    private Saisie() {
    }

    /** {@code apres} receives the text on the main thread; Cancel or an empty text reopen {@code menuRetour}. */
    static void ouvrir(FluxAuctionHouse plugin, Player p, String titre, String explication, String libelle, String initial,
                       String menuRetour, BiConsumer<Player, String> apres) {
        Dialogs.form(p, titre).body(explication).requiredInput("valeur", libelle, initial, 48)
                .onSubmit((pl, a) -> apres.accept(pl, a.text("valeur")))
                .onCancel(pl -> plugin.ouvrirMenu(pl, menuRetour))
                .open();
    }
}
