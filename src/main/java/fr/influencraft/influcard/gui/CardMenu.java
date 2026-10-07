package fr.influencraft.influcard.gui;

import fr.influencraft.influcard.CardRegistry;
import fr.influencraft.influcard.InfluCard;
import fr.influencraft.influcard.RevealManager;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Édition d'une carte : nom, rareté, série, texture, description. */
public final class CardMenu extends Menu {

    private final String id;

    public CardMenu(InfluCard plugin, String id) {
        super(plugin, 5, "&#FF7A59Carte &#6B7A8F▸ &#EADBC8" + id);
        this.id = id;
    }

    @Override
    protected void build(Player player) {
        var reg = plugin.registry();
        CardRegistry.Card card = reg.card(id);
        if (card == null) {
            back(new CardsMenu(plugin, 0));
            return;
        }

        var preview = reg.cardItem(card);
        preview.editMeta(meta -> {
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>(
                    meta.lore() == null ? List.of() : meta.lore());
            lore.add(fr.influencraft.influcard.Texts.of(""));
            lore.add(fr.influencraft.influcard.Texts.of(left("Recevoir cette carte")));
            meta.lore(lore);
        });
        set(4, preview, (p, c) -> RevealManager.giveOrDrop(p, reg.cardItem(card)));

        set(19, icon("name_tag", "&#FF7A59Nom", "&#6B7A8FActuel : &#FFF4E0" + card.name(), "",
                "&#EADBC8Sans couleur : le nom prend", "&#EADBC8la couleur du format des cartes.",
                "", left("Modifier")), (p, c) ->
                ask(p, "&#EADBC8Nouveau nom de la carte :", answer -> reg.setCard(id, "name", answer)));

        set(20, icon("lime_dye", "&#FF7A59Rareté &r" + card.rarity().tag(), "",
                left("Rareté suivante"), right("Rareté précédente")), (p, c) -> {
            var next = reg.nextRarity(card.rarity(), c.isRightClick() ? -1 : 1);
            reg.setCard(id, "rarity", next.id());
            refresh(p);
        });

        set(21, icon("bookshelf", "&#FF7A59Série", "&#6B7A8FActuelle : &#FFF4E0"
                + (card.series().isEmpty() ? "(aucune)" : card.series()), "", left("Modifier")), (p, c) ->
                ask(p, "&#EADBC8Série de la carte &#6B7A8F(« aucune » pour vider) :",
                        answer -> reg.setCard(id, "series", answer.equalsIgnoreCase("aucune") ? "" : answer)));

        set(23, icon("painting", "&#FF7A59Texture", "&#6B7A8FActuelle : &#EADBC8" + card.itemId(), "",
                "&#EADBC8Identifiant d'un item ItemsAdder", "&#EADBC8(ex : influcards:carte_test).",
                "", left("Modifier")), (p, c) ->
                ask(p, "&#EADBC8Item ItemsAdder de la carte &#6B7A8F(namespace:id) :",
                        answer -> reg.setCard(id, "item", answer.trim())));

        set(25, icon("bone", "&#FF7A59Familier lié", "&#6B7A8FActuel : &#FFF4E0"
                        + (card.pet() == null ? "(aucun)" : reg.petName(card.pet())), "",
                "&#EADBC8Clic droit avec la carte en main :", "&#EADBC8donne &#FFF4E0" + (int) reg.cardXp(card.rarity())
                        + " XP &#EADBC8à ce familier (Maj : toute la pile).", "",
                "&#6B7A8FL'XP par carte se règle par rareté", "&#6B7A8F(card-xp dans config.yml).", "",
                left("Modifier")), (p, c) ->
                ask(p, "&#EADBC8Identifiant EcoPets du familier &#6B7A8F(« aucun » pour délier) :",
                        answer -> reg.setCard(id, "pet", answer.equalsIgnoreCase("aucun") ? "" : answer.trim().toLowerCase())));

        List<String> loreInfo = new ArrayList<>();
        loreInfo.add("&#6B7A8FActuelle :");
        if (card.lore().isEmpty()) {
            loreInfo.add("&#6B7A8F(vide)");
        } else {
            loreInfo.addAll(card.lore());
        }
        loreInfo.add("");
        loreInfo.add("&#6B7A8FUne ligne par « | ».");
        loreInfo.add("");
        loreInfo.add(left("Modifier"));
        set(24, icon("book", "&#FF7A59Description", loreInfo), (p, c) ->
                ask(p, "&#EADBC8Description, lignes séparées par &#FFF4E0| &#EADBC8:",
                        answer -> reg.setCard(id, "lore", Arrays.stream(answer.split("\\|"))
                                .map(String::trim).map(l -> l.startsWith("&") ? l : "&#EADBC8" + l).toList())));

        back(new CardsMenu(plugin, 0));
        set(44, icon("barrier", "&#D9534FSupprimer cette carte",
                "&#EADBC8Elle est aussi retirée des boosters.", "&#EADBC8Les cartes déjà distribuées restent",
                "&#EADBC8en jeu mais ne sont plus reconnues.", "",
                shift("Supprimer (Maj + clic droit)")), (p, c) -> {
            if (c == ClickType.SHIFT_RIGHT) {
                reg.deleteCard(id);
                plugin.send(p, "deleted", "%id%", id);
                new CardsMenu(plugin, 0).open(p);
            }
        });
    }
}
