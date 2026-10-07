package fr.influencraft.influcard.gui;

import fr.influencraft.influcard.CardRegistry;
import fr.influencraft.influcard.InfluCard;
import fr.influencraft.influcard.PetHook;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Familiers EcoPets : lesquels sortent des boosters, avec quelle rareté, et que faire d'un
 * familier déjà adopté.
 */
public final class PetsMenu extends Menu {

    private final int page;

    public PetsMenu(InfluCard plugin, int page) {
        super(plugin, 6, "&#FF7A59Cartes &#6B7A8F▸ &#EADBC8Familiers");
        this.page = page;
    }

    @Override
    protected void build(Player player) {
        var reg = plugin.registry();
        back(new MainMenu(plugin));
        if (!PetHook.available()) {
            set(22, icon("barrier", "&#D9534FEcoPets n'est pas chargé",
                    "&#EADBC8Les familiers ne peuvent pas", "&#EADBC8sortir des boosters."));
            return;
        }
        List<PetHook.PetInfo> pets = new ArrayList<>(PetHook.pets());
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            int index = page * CONTENT_SLOTS.length + i;
            if (index >= pets.size()) {
                break;
            }
            PetHook.PetInfo info = pets.get(index);
            CardRegistry.PetEntry entry = reg.pet(info.id());
            boolean enabled = entry != null && entry.enabled();
            CardRegistry.Rarity rarity = entry != null ? entry.rarity() : reg.rarities().getFirst();
            ItemStack item = PetHook.icon(player, info.id());
            List<String> lore = new ArrayList<>();
            lore.add("&#6B7A8F" + info.id());
            lore.add("&#EADBC8Rareté : &r" + rarity.tag());
            lore.add(enabled ? "&#5FB3B3✔ Obtenable dans les boosters" : "&#6B7A8F✘ Pas obtenable");
            lore.add("");
            lore.add(left(enabled ? "Rendre non obtenable" : "Rendre obtenable"));
            lore.add(right("Rareté suivante"));
            lore.add(shift("Rareté précédente (Maj + clic droit)"));
            set(CONTENT_SLOTS[i], glow(decorate(item, rarity.tag() + " &r" + info.name(), lore), enabled), (p, c) -> {
                if (c == ClickType.LEFT || c == ClickType.SHIFT_LEFT) {
                    reg.setPet(info.id(), "enabled", !enabled);
                } else if (c == ClickType.RIGHT) {
                    reg.setPet(info.id(), "rarity", reg.nextRarity(rarity, 1).id());
                } else if (c == ClickType.SHIFT_RIGHT) {
                    reg.setPet(info.id(), "rarity", reg.nextRarity(rarity, -1).id());
                }
                refresh(p);
            });
        }
        pages(page, pageCount(pets.size()), target -> new PetsMenu(plugin, target).open(player));

        set(4, icon("bone", "&#FF7A59Familiers",
                "&#EADBC8Un familier obtenable peut sortir",
                "&#EADBC8des boosters (selon leur contenu) :",
                "&#EADBC8il est débloqué à sa révélation.",
                "",
                "&#6B7A8FUn familier ne sort qu'une fois par booster."));

        boolean xp = reg.duplicateMode() == CardRegistry.DuplicatePetMode.XP;
        double amount = reg.duplicateXp();
        set(49, icon(xp ? "experience_bottle" : "influitems:stairs", "&#FF7A59Familier déjà adopté",
                xp ? "&#EADBC8Mode : &#FFF4E0peut ressortir (peluche)" : "&#EADBC8Mode : &#FFF4E0nouveau tirage",
                xp ? "&#EADBC8XP en plus de la peluche : &#FFF4E0" + String.format(Locale.FRANCE, "%.0f", amount) + " XP"
                        : "&#EADBC8Un familier déjà adopté n'est",
                xp ? "" : "&#EADBC8jamais tiré pour ce joueur.",
                "",
                left("Changer de mode"),
                xp ? right("+100 XP") : "",
                xp ? shift("-100 XP (Maj + clic droit)") : ""), (p, c) -> {
            if (c == ClickType.LEFT) {
                reg.setDuplicate(xp ? CardRegistry.DuplicatePetMode.REROLL : CardRegistry.DuplicatePetMode.XP, amount);
            } else if (xp && c == ClickType.RIGHT) {
                reg.setDuplicate(CardRegistry.DuplicatePetMode.XP, amount + 100);
            } else if (xp && c == ClickType.SHIFT_RIGHT) {
                reg.setDuplicate(CardRegistry.DuplicatePetMode.XP, Math.max(0, amount - 100));
            }
            refresh(p);
        });
    }
}
