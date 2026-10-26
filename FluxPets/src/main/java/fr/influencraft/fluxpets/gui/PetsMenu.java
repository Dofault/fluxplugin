package fr.influencraft.fluxpets.gui;

import fr.influencraft.fluxpets.CardRegistry;
import fr.influencraft.fluxpets.FluxPets;
import fr.influencraft.fluxpets.PetHook;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Familiers EcoPets : lesquels sortent des boosters, avec quelle rareté, et que faire d'un
 * familier déjà adopté.
 */
public final class PetsMenu extends Menu {

    private final int page;

    public PetsMenu(FluxPets plugin, int page) {
        super(plugin, 6, plugin.text("editor.pets.title"));
        this.page = page;
    }

    @Override
    protected void build(Player player) {
        var reg = plugin.registry();
        back(new MainMenu(plugin));
        if (!PetHook.available()) {
            set(22, icon("barrier", t("pets.unavailable.name"), lines("pets.unavailable.lore")));
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
            List<String> lore = join(lines(enabled ? "pets.pet.lore-enabled" : "pets.pet.lore-disabled",
                    "%id%", info.id(), "%rarity%", rarity.tag()),
                    List.of(left(t(enabled ? "pets.pet.disable" : "pets.pet.enable")),
                            right(t("action.next-rarity")), shift(t("pets.pet.previous-rarity"))));
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

        set(4, icon("bone", t("pets.info.name"), lines("pets.info.lore")));

        boolean xp = reg.duplicateMode() == CardRegistry.DuplicatePetMode.XP;
        double amount = reg.duplicateXp();
        set(49, icon(xp ? "experience_bottle" : "repeater", t("pets.duplicate.name"),
                join(lines(xp ? "pets.duplicate.lore-xp" : "pets.duplicate.lore-reroll",
                                "%xp%", String.format(FluxPets.locale(), "%.0f", amount)),
                        xp ? List.of(left(t("pets.duplicate.switch")), right(t("pets.duplicate.more")),
                                shift(t("pets.duplicate.less")))
                                : List.of(left(t("pets.duplicate.switch"))))), (p, c) -> {
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
