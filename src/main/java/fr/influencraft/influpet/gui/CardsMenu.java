package fr.influencraft.influpet.gui;

import fr.influencraft.influpet.CardRegistry;
import fr.influencraft.influpet.InfluPet;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Liste des cartes, création d'une carte. */
public final class CardsMenu extends Menu {

    private final int page;

    public CardsMenu(InfluPet plugin, int page) {
        super(plugin, 6, "&#FF7A59Cartes &#6B7A8F▸ &#EADBC8Collection");
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
                lore.add(fr.influencraft.influpet.Texts.of(""));
                lore.add(fr.influencraft.influpet.Texts.of("&#6B7A8F" + card.id()));
                lore.add(fr.influencraft.influpet.Texts.of(left("Modifier")));
                lore.add(fr.influencraft.influpet.Texts.of(right("Recevoir un exemplaire")));
                lore.add(fr.influencraft.influpet.Texts.of(shift("Recevoir une pile (Maj + clic droit)")));
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

        set(4, icon("influcards:carte_test", "&#FF7A59Cartes &#6B7A8F(" + cards.size() + ")",
                "&#EADBC8Le numéro de collection suit", "&#EADBC8l'ordre de création."));
        set(49, icon("influitems:accept", "&#FF7A59Nouvelle carte",
                "&#EADBC8Crée une carte (texture de test,", "&#EADBC8rareté la plus commune).", "",
                left("Créer")), (p, c) ->
                ask(p, "&#EADBC8Identifiant de la nouvelle carte &#6B7A8F(lettres, chiffres, _) :", answer -> {
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
