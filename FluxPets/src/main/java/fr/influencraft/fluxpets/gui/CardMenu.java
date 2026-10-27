package fr.influencraft.fluxpets.gui;

import fr.influencraft.fluxpets.CardRegistry;
import fr.influencraft.fluxpets.FluxPets;
import fr.influencraft.fluxpets.RevealManager;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Édition d'une carte : nom, rareté, série, texture, description. */
public final class CardMenu extends Menu {

    private final String id;

    public CardMenu(FluxPets plugin, String id) {
        super(plugin, 5, plugin.text("editor.card.title", "%id%", id));
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
            lore.add(fr.influencraft.fluxpets.Texts.of(""));
            lore.add(fr.influencraft.fluxpets.Texts.of(left(t("card.receive"))));
            meta.lore(fr.influencraft.flux.api.text.Texts.wrapLore(lore));
        });
        set(4, preview, (p, c) -> RevealManager.giveOrDrop(p, reg.cardItem(card)));

        set(19, icon("name_tag", t("card.name.name"),
                join(lines("card.name.lore", "%value%", card.name()), List.of(left(t("action.edit"))))), (p, c) ->
                ask(p, t("card.name.prompt"), answer -> reg.setCard(id, "name", answer)));

        set(20, icon("lime_dye", t("card.rarity.name", "%rarity%", card.rarity().tag()),
                List.of("", left(t("action.next-rarity")), right(t("action.previous-rarity")))), (p, c) -> {
            var next = reg.nextRarity(card.rarity(), c.isRightClick() ? -1 : 1);
            reg.setCard(id, "rarity", next.id());
            refresh(p);
        });

        String none = t("card.none");
        set(21, icon("bookshelf", t("card.series.name"),
                join(lines("card.series.lore", "%value%", card.series().isEmpty() ? t("card.series.empty") : card.series()),
                        List.of(left(t("action.edit"))))), (p, c) ->
                ask(p, t("card.series.prompt", "%none%", none),
                        answer -> reg.setCard(id, "series", answer.equalsIgnoreCase(none) ? "" : answer)));

        set(23, icon("painting", t("texture.name"),
                join(lines("texture.lore", "%value%", card.itemId(), "%example%", "influpet:carte_caillou"),
                        List.of(left(t("action.edit"))))), (p, c) ->
                ask(p, t("texture.prompt"), answer -> reg.setCard(id, "item", answer.trim())));

        set(25, icon("bone", t("card.pet.name"),
                join(lines("card.pet.lore", "%value%", card.pet() == null ? t("card.pet.empty") : reg.petName(card.pet()),
                                "%xp%", String.valueOf((int) reg.cardXp(card.rarity()))),
                        List.of(left(t("action.edit"))))), (p, c) ->
                ask(p, t("card.pet.prompt", "%none%", none),
                        answer -> reg.setCard(id, "pet", answer.equalsIgnoreCase(none) ? "" : answer.trim().toLowerCase())));

        set(24, icon("book", t("description.name"),
                BoosterMenu.withHeader(this, card.lore(), lines("card.description-hint"), left(t("action.edit")))), (p, c) ->
                ask(p, t("description.prompt"), answer -> reg.setCard(id, "lore", Arrays.stream(answer.split("\\|"))
                        .map(String::trim).map(l -> l.startsWith("&") ? l : "&#EADBC8" + l).toList())));

        back(new CardsMenu(plugin, 0));
        set(44, icon("barrier", t("card.delete.name"),
                join(lines("card.delete.lore"), List.of(shift(t("action.delete"))))), (p, c) -> {
            if (c == ClickType.SHIFT_RIGHT) {
                reg.deleteCard(id);
                plugin.send(p, "deleted", "%id%", id);
                new CardsMenu(plugin, 0).open(p);
            }
        });
    }
}
