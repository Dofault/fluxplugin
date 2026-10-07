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
        super(plugin, 6, "&#FF7A59Cartes &#6B7A8F▸ &#EADBC8Boosters");
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
            List<String> lore = new ArrayList<>();
            lore.add("&#6B7A8F" + b.id() + " • " + b.cards() + " tirages");
            lore.add("&#6B7A8FContenu : &#EADBC8" + (b.contents().isEmpty()
                    ? "tout" : b.contents().size() + " récompenses"));
            lore.add("");
            lore.add(left("Modifier"));
            lore.add(right("Recevoir un exemplaire"));
            lore.add(shift("Recevoir une pile (Maj + clic droit)"));
            set(CONTENT_SLOTS[i], decorate(item, b.name(), lore), (p, c) -> {
                if (c.isRightClick()) {
                    giveCopies(p, c, plugin.registry().boosterItem(b));
                } else {
                    new BoosterMenu(plugin, b.id()).open(p);
                }
            });
        }
        pages(page, pages, target -> new MainMenu(plugin, target).open(player));

        set(4, icon("influitems:settings", "&#FF7A59Édition des cartes",
                "&#EADBC8Paramètre les boosters, les cartes",
                "&#EADBC8et les familiers obtenables.",
                "",
                "&#6B7A8FTout est enregistré dans",
                "&#6B7A8Fplugins/InfluPet/config.yml."));

        set(45, icon("chest", "&#FF7A59Nouveau booster",
                "&#EADBC8Crée un booster vide.", "", left("Créer")), (p, c) ->
                ask(p, "&#EADBC8Identifiant du nouveau booster &#6B7A8F(lettres, chiffres, _) :", answer -> {
                    String id = answer.toLowerCase().replaceAll("[^a-z0-9_]", "_");
                    if (plugin.registry().booster(id) != null) {
                        plugin.send(p, "already-exists", "%id%", id);
                        return;
                    }
                    plugin.registry().createBooster(id);
                    plugin.send(p, "created", "%id%", id);
                }));
        set(47, icon("influcards:carte_test", "&#FF7A59Cartes",
                "&#EADBC8" + plugin.registry().cards().size() + " cartes.", "", left("Gérer les cartes")),
                (p, c) -> new CardsMenu(plugin, 0).open(p));
        set(51, icon("bone", "&#FF7A59Familiers",
                PetHook.available()
                        ? "&#EADBC8" + plugin.registry().pets().stream().filter(CardRegistry.PetEntry::enabled).count()
                        + " obtenables dans les boosters."
                        : "&#D9534FEcoPets n'est pas chargé.",
                "", left("Gérer les familiers")), (p, c) -> new PetsMenu(plugin, 0).open(p));
        set(53, icon("influitems:accept", "&#FF7A59Recharger",
                "&#EADBC8Relit config.yml (après une", "&#EADBC8modification à la main).", "", left("Recharger")),
                (p, c) -> {
                    plugin.reloadConfig();
                    plugin.registry().load();
                    plugin.send(p, "reloaded", "%cards%", String.valueOf(plugin.registry().cards().size()),
                            "%boosters%", String.valueOf(plugin.registry().boosters().size()));
                    refresh(p);
                });
    }
}
