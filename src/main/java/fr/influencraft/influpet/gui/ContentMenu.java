package fr.influencraft.influpet.gui;

import fr.influencraft.influpet.CardRegistry;
import fr.influencraft.influpet.CardRegistry.CardReward;
import fr.influencraft.influpet.CardRegistry.PetReward;
import fr.influencraft.influpet.CardRegistry.Reward;
import fr.influencraft.influpet.InfluPet;
import fr.influencraft.influpet.PetHook;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Contenu d'un booster : cartes et familiers à inclure ou retirer. */
public final class ContentMenu extends Menu {

    private final String id;
    private final int page;

    public ContentMenu(InfluPet plugin, String id, int page) {
        super(plugin, 6, plugin.text("editor.content.title", "%id%", id));
        this.id = id;
        this.page = page;
    }

    @Override
    protected void build(Player player) {
        var reg = plugin.registry();
        CardRegistry.Booster b = reg.booster(id);
        if (b == null) {
            back(new MainMenu(plugin));
            return;
        }
        // toutes les récompenses possibles : cartes, puis familiers configurés (même désactivés)
        List<Reward> all = new ArrayList<>();
        reg.cards().forEach(c -> all.add(new CardReward(c)));
        if (PetHook.available()) {
            reg.pets().forEach(pet -> all.add(new PetReward(pet)));
        }
        Set<String> included = reg.contents(b).stream().map(Reward::key).collect(Collectors.toSet());

        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            int index = page * CONTENT_SLOTS.length + i;
            if (index >= all.size()) {
                break;
            }
            Reward r = all.get(index);
            boolean in = included.contains(r.key());
            ItemStack item = reg.displayItem(player, r);
            List<String> lore = new ArrayList<>();
            if (r instanceof PetReward p) {
                lore.addAll(lines(p.pet().enabled() ? "content.pet" : "content.pet-disabled"));
            } else {
                lore.addAll(lines("content.card"));
            }
            lore.addAll(lines(in ? "content.included" : "content.excluded", "%chance%",
                    InfluPet.percent(reg.chance(b, r))));
            lore.add(left(t(in ? "action.remove" : "action.add")));
            set(CONTENT_SLOTS[i], glow(decorate(item, reg.displayName(r), lore), in), (p, c) -> {
                reg.toggleContent(reg.booster(id), r.key());
                refresh(p);
            });
        }
        pages(page, pageCount(all.size()), target -> new ContentMenu(plugin, id, target).open(player));

        set(4, icon("chest", t("content.info.name", "%booster%", b.name()),
                lines(b.contents().isEmpty() ? "content.info.lore-all" : "content.info.lore-chosen",
                        "%count%", String.valueOf(b.contents().size()))));
        set(49, icon("influitems:chest_delete", t("content.reset.name"),
                join(lines("content.reset.lore"), List.of(left(t("content.reset.action"))))), (p, c) -> {
            reg.setBooster(id, "contents", List.of());
            refresh(p);
        });
        back(new BoosterMenu(plugin, id));
    }
}
