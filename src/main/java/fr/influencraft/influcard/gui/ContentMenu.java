package fr.influencraft.influcard.gui;

import fr.influencraft.influcard.CardRegistry;
import fr.influencraft.influcard.CardRegistry.CardReward;
import fr.influencraft.influcard.CardRegistry.PetReward;
import fr.influencraft.influcard.CardRegistry.Reward;
import fr.influencraft.influcard.InfluCard;
import fr.influencraft.influcard.PetHook;
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

    public ContentMenu(InfluCard plugin, String id, int page) {
        super(plugin, 6, "&#FF7A59Contenu &#6B7A8F▸ &#EADBC8" + id);
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
                lore.add("&#5FB3B3Familier");
                if (!p.pet().enabled()) {
                    lore.add("&#D9534FDésactivé (menu Familiers) :");
                    lore.add("&#D9534Fjamais tiré, même inclus.");
                }
            } else {
                lore.add("&#EADBC8Carte");
            }
            lore.add(in ? "&#5FB3B3✔ Dans ce booster" : "&#6B7A8F✘ Hors de ce booster");
            lore.add("&#EADBC8Chance par tirage : &#FFF4E0" + InfluCard.percent(reg.chance(b, r)));
            lore.add("");
            lore.add(left(in ? "Retirer" : "Ajouter"));
            set(CONTENT_SLOTS[i], glow(decorate(item, reg.displayName(r), lore), in), (p, c) -> {
                reg.toggleContent(reg.booster(id), r.key());
                refresh(p);
            });
        }
        pages(page, pageCount(all.size()), target -> new ContentMenu(plugin, id, target).open(player));

        set(4, icon("chest", "&#FF7A59Contenu de " + b.name(),
                b.contents().isEmpty()
                        ? "&#EADBC8Mode : &#FFF4E0tout le contenu"
                        : "&#EADBC8Mode : &#FFF4E0liste choisie (" + b.contents().size() + ")",
                "",
                "&#6B7A8FEn mode « tout », les nouvelles cartes",
                "&#6B7A8Fet familiers obtenables y entrent seuls.",
                "&#6B7A8FCliquer une récompense passe en liste choisie."));
        set(49, icon("influitems:chest_delete", "&#FF7A59Tout inclure",
                "&#EADBC8Revient au mode « tout le contenu ».", "", left("Réinitialiser")), (p, c) -> {
            reg.setBooster(id, "contents", List.of());
            refresh(p);
        });
        back(new BoosterMenu(plugin, id));
    }
}
