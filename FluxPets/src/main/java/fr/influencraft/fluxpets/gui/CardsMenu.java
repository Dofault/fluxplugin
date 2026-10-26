package fr.influencraft.fluxpets.gui;

import fr.influencraft.fluxpets.CardRegistry;
import fr.influencraft.fluxpets.FluxPets;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Liste des cartes, création d'une carte. */
public final class CardsMenu extends Menu {

    private final int page;

    public CardsMenu(FluxPets plugin, int page) {
        super(plugin, 6, plugin.text("editor.cards.title"));
        this.page = page;
    }

    @Override
    protected void build(Player player) {
        var reg = plugin.registry();
        List<CardRegistry.Card> cards = new ArrayList<>(reg.cards());
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            int index = page * CONTENT_SLOTS.length + i;
            if (index >= cards.size()) {
                break;
            }
            CardRegistry.Card card = cards.get(index);
            ItemStack item = reg.cardItem(card);
            item.editMeta(meta -> {
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>(
                        meta.lore() == null ? List.of() : meta.lore());
                lore.add(fr.influencraft.fluxpets.Texts.of(""));
                lore.add(fr.influencraft.fluxpets.Texts.of("&#6B7A8F" + card.id()));
                lore.add(fr.influencraft.fluxpets.Texts.of(left(t("action.edit"))));
                lore.add(fr.influencraft.fluxpets.Texts.of(right(t("action.receive-one"))));
                lore.add(fr.influencraft.fluxpets.Texts.of(shift(t("action.receive-stack"))));
                meta.lore(lore);
            });
            set(CONTENT_SLOTS[i], item, (p, c) -> {
                if (c.isRightClick()) {
                    giveCopies(p, c, reg.cardItem(card));
                } else {
                    new CardMenu(plugin, card.id()).open(p);
                }
            });
        }
        pages(page, pageCount(cards.size()), target -> new CardsMenu(plugin, target).open(player));

        set(4, icon("paper", t("cards.info.name", "%count%", String.valueOf(cards.size())),
                lines("cards.info.lore")));
        set(49, icon("writable_book", t("cards.new.name"),
                join(lines("cards.new.lore"), List.of(left(t("action.create"))))), (p, c) ->
                ask(p, t("cards.new.prompt"), answer -> {
                    String id = answer.toLowerCase().replaceAll("[^a-z0-9_]", "_");
                    if (reg.card(id) != null) {
                        plugin.send(p, "already-exists", "%id%", id);
                        return;
                    }
                    reg.createCard(id);
                    plugin.send(p, "created", "%id%", id);
                }));
        back(new MainMenu(plugin));
    }
}
