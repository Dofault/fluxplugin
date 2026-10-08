package fr.influencraft.influpet.gui;

import fr.influencraft.influpet.CardRegistry;
import fr.influencraft.influpet.InfluPet;
import fr.influencraft.influpet.PetHook;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Accueil de l'édition : boosters, cartes, familiers. */
public final class MainMenu extends Menu {

    private final int page;

    public MainMenu(InfluPet plugin) {
        this(plugin, 0);
    }

    public MainMenu(InfluPet plugin, int page) {
        super(plugin, 6, plugin.text("editor.main.title"));
        this.page = page;
    }

    @Override
    protected void build(Player player) {
        List<CardRegistry.Booster> boosters = new ArrayList<>(plugin.registry().boosters());
        int pages = pageCount(boosters.size());
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            int index = page * CONTENT_SLOTS.length + i;
            if (index >= boosters.size()) {
                break;
            }
            CardRegistry.Booster b = boosters.get(index);
            ItemStack item = plugin.registry().boosterItem(b);
            List<String> lore = join(lines("main.booster.lore", "%id%", b.id(), "%cards%", String.valueOf(b.cards()),
                    "%contents%", b.contents().isEmpty() ? t("main.booster.contents-all")
                            : t("main.booster.contents-count", "%count%", String.valueOf(b.contents().size()))),
                    List.of(left(t("action.edit")), right(t("action.receive-one")), shift(t("action.receive-stack"))));
            set(CONTENT_SLOTS[i], decorate(item, b.name(), lore), (p, c) -> {
                if (c.isRightClick()) {
                    giveCopies(p, c, plugin.registry().boosterItem(b));
                } else {
                    new BoosterMenu(plugin, b.id()).open(p);
                }
            });
        }
        pages(page, pages, target -> new MainMenu(plugin, target).open(player));

        set(4, icon("influitems:settings", t("main.info.name"), lines("main.info.lore")));

        set(45, icon("chest", t("main.new-booster.name"),
                join(lines("main.new-booster.lore"), List.of(left(t("action.create"))))), (p, c) ->
                ask(p, t("main.new-booster.prompt"), answer -> {
                    String id = answer.toLowerCase().replaceAll("[^a-z0-9_]", "_");
                    if (plugin.registry().booster(id) != null) {
                        plugin.send(p, "already-exists", "%id%", id);
                        return;
                    }
                    plugin.registry().createBooster(id);
                    plugin.send(p, "created", "%id%", id);
                }));
        set(47, icon("influcards:carte_test", t("main.cards.name"),
                join(lines("main.cards.lore", "%count%", String.valueOf(plugin.registry().cards().size())),
                        List.of(left(t("main.cards.action"))))),
                (p, c) -> new CardsMenu(plugin, 0).open(p));
        set(51, icon("bone", t("main.pets.name"),
                join(PetHook.available()
                        ? lines("main.pets.lore", "%count%", String.valueOf(plugin.registry().pets().stream()
                        .filter(CardRegistry.PetEntry::enabled).count()))
                        : lines("pets-unavailable"), List.of(left(t("main.pets.action"))))),
                (p, c) -> new PetsMenu(plugin, 0).open(p));
        set(53, icon("influitems:accept", t("main.reload.name"),
                join(lines("main.reload.lore"), List.of(left(t("main.reload.action"))))),
                (p, c) -> {
                    plugin.reloadConfig();
                    plugin.registry().load();
                    plugin.send(p, "reloaded", "%cards%", String.valueOf(plugin.registry().cards().size()),
                            "%boosters%", String.valueOf(plugin.registry().boosters().size()));
                    refresh(p);
                });
    }
}
