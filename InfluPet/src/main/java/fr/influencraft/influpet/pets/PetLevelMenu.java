package fr.influencraft.influpet.pets;

import com.willfp.eco.core.config.interfaces.Config;
import com.willfp.eco.core.gui.menu.Menu;
import com.willfp.eco.core.gui.menu.MenuBuilder;
import com.willfp.eco.core.gui.page.PageChanger;
import com.willfp.eco.core.gui.slot.FillerMask;
import com.willfp.eco.core.gui.slot.MaskItems;
import com.willfp.eco.core.gui.slot.Slot;
import com.willfp.eco.core.sound.PlayableSound;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Progression d'un familier niveau par niveau (repris de PetLevelGUI d'EcoPets). Les emplacements suivent le
 * motif progression-slots (1 à 9 puis a à z = ordre des niveaux), une page par tranche de niveaux. Chaque niveau :
 * débloqué, en cours (avec la progression) ou verrouillé, avec ses effets (rewards-description).
 */
public final class PetLevelMenu {

    private static final Map<String, Menu> MENUS = new HashMap<>();
    private static final String ORDER = "123456789abcdefghijklmnopqrstuvwxyz";

    private PetLevelMenu() {
    }

    static void clear() {
        MENUS.clear();
    }

    public static void open(Player player, Pet pet) {
        MENUS.computeIfAbsent(pet.id(), id -> build(pet)).open(player);
    }

    private static Menu build(Pet pet) {
        Config c = Pets.settings().getSubsection("level-gui");
        // position (ligne, colonne) de chaque niveau d'une page, dans l'ordre 1..9 a..z
        List<int[]> slots = new ArrayList<>();
        List<String> pattern = c.getStrings("progression-slots.pattern");
        for (char ch : ORDER.toCharArray()) {
            for (int row = 0; row < pattern.size(); row++) {
                int col = pattern.get(row).indexOf(ch);
                if (col >= 0) {
                    slots.add(new int[]{row + 1, col + 1});
                }
            }
        }
        int perPage = Math.max(1, slots.size());
        MenuBuilder builder = Menu.builder(c.getInt("rows"))
                .setTitle(PetItems.menuTitle(c.getString("title").replace("%pet%", pet.name())))
                .setMask(new FillerMask(MaskItems.fromItemNames(c.getStrings("mask.materials")),
                        c.getStrings("mask.pattern").toArray(new String[0])))
                .maxPages(player -> (int) Math.ceil(pet.maxLevel() / (double) perPage));
        boolean levelAsAmount = c.getBool("progression-slots.level-as-amount");
        for (int i = 0; i < slots.size(); i++) {
            int index = i;
            builder.setSlot(slots.get(i)[0], slots.get(i)[1], Slot.builder((player, m) -> {
                int level = (m.getPage(player) - 1) * perPage + index + 1;
                return level > pet.maxLevel() ? new ItemStack(Material.AIR) : levelItem(c, player, pet, level, levelAsAmount);
            }).build());
        }
        var sound = PlayableSound.create(c.getSubsection("progression-slots.page-change-sound"));
        builder.addComponent(c.getInt("progression-slots.prev-page.location.row"),
                c.getInt("progression-slots.prev-page.location.column"),
                PetsMenu.pageChanger(PageChanger.Direction.BACKWARDS, c.getString("progression-slots.prev-page.item"), null, sound));
        builder.addComponent(c.getInt("progression-slots.next-page.location.row"),
                c.getInt("progression-slots.next-page.location.column"),
                PetsMenu.pageChanger(PageChanger.Direction.FORWARDS, c.getString("progression-slots.next-page.item"),
                        c.has("progression-slots.next-page.item-inactive")
                                ? c.getString("progression-slots.next-page.item-inactive") : null, sound));
        if (c.getBool("progression-slots.close.enabled")) {
            builder.setSlot(c.getInt("progression-slots.close.location.row"), c.getInt("progression-slots.close.location.column"),
                    Slot.builder(PetItems.build(c.getString("progression-slots.close.item"),
                                    c.getString("progression-slots.close.name"), null))
                            .onLeftClick((event, slot) -> PetsMenu.open((Player) event.getWhoClicked())).build());
        }
        return builder.build();
    }

    private static ItemStack levelItem(Config c, Player player, Pet pet, int level, boolean levelAsAmount) {
        int current = PetData.level(player, pet);
        String state = level <= current ? "unlocked" : level == current + 1 ? "in-progress" : "locked";
        Config s = c.getSubsection("progression-slots." + state);
        List<String> lore = s.getStrings(state.equals("in-progress") && current + 1 >= pet.maxLevel()
                && s.has("max-level-lore") ? "max-level-lore" : "lore");
        // XP demandée pour CE niveau (EcoPets affichait celle du niveau d'après sur le niveau en cours)
        String required = Pet.nice(pet.xpForLevel(level));
        lore = lore.stream().map(l -> l.replace("%required_xp%", required)).toList();
        ItemStack item = PetItems.build(s.getString("item"), PetItems.title(s.getString("name"), pet, level),
                PetData.inject(lore, player, pet, level));
        if (levelAsAmount) {
            item.setAmount(Math.max(1, Math.min(level, item.getMaxStackSize())));
        }
        return item;
    }
}
