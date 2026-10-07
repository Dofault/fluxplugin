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
import java.util.Comparator;
import java.util.List;

/**
 * Menu des familiers (/familiers), repris de PetsGUI d'EcoPets : familiers adoptés triés par niveau décroissant
 * dans la zone pet-area (pages) : clic = l'activer (en plus des autres, selon le maximum du joueur) ou le
 * ranger s'il est actif ; premier familier actif en haut (clic : sa progression), tout ranger, cacher / montrer,
 * fermer. Pas de bouton « retirer en œuf » : les familiers forment une collection.
 */
public final class PetsMenu {

    private static Menu menu;
    private static final List<int[]> AREA = new ArrayList<>();

    private PetsMenu() {
    }

    public static void open(Player player) {
        if (menu == null) {
            update();
        }
        menu.open(player);
    }

    /** Reconstruit le menu (démarrage, rechargement). */
    public static void update() {
        Config c = Pets.settings();
        AREA.clear();
        for (int row = c.getInt("gui.pet-area.top-left.row"); row <= c.getInt("gui.pet-area.bottom-right.row"); row++) {
            for (int col = c.getInt("gui.pet-area.top-left.column"); col <= c.getInt("gui.pet-area.bottom-right.column"); col++) {
                AREA.add(new int[]{row, col});
            }
        }
        menu = build(c);
        PetLevelMenu.clear();
    }

    /** Familiers adoptés, du plus haut niveau au plus bas (ordre du menu d'EcoPets). */
    private static List<Pet> unlocked(Player player) {
        List<Pet> pets = new ArrayList<>();
        for (Pet pet : Pets.values()) {
            if (PetData.has(player, pet)) {
                pets.add(pet);
            }
        }
        pets.sort(Comparator.comparingInt((Pet p) -> PetData.level(player, p)).reversed());
        return pets;
    }

    private static Pet at(Player player, Menu menu, int index) {
        List<Pet> pets = unlocked(player);
        int paged = (menu.getPage(player) - 1) * AREA.size() + index;
        return paged >= 0 && paged < pets.size() ? pets.get(paged) : null;
    }

    private static Menu build(Config c) {
        MenuBuilder builder = Menu.builder(c.getInt("gui.rows"))
                .setTitle(PetItems.menuTitle(c.getString("gui.title")))
                .setMask(new FillerMask(MaskItems.fromItemNames(c.getStrings("gui.mask.materials")),
                        c.getStrings("gui.mask.pattern").toArray(new String[0])))
                .maxPages(player -> Math.max(1, (int) Math.ceil(unlocked(player).size() / (double) Math.max(1, AREA.size()))))
                .onRender((player, m) -> {
                    if (m.getPage(player) > m.getMaxPage(player)) {
                        m.setState(player, "page", 1);
                    }
                });
        var clickSound = PlayableSound.create(c.getSubsection("gui.pet-icon.click"));
        for (int i = 0; i < AREA.size(); i++) {
            int index = i;
            builder.setSlot(AREA.get(i)[0], AREA.get(i)[1], Slot.builder((player, m) -> {
                Pet pet = at(player, m, index);
                return pet == null ? new ItemStack(Material.AIR) : PetItems.icon(player, pet);
            }).onLeftClick((event, slot, m) -> {
                Player player = (Player) event.getWhoClicked();
                Pet pet = at(player, m, index);
                if (pet == null) {
                    return;
                }
                if (PetData.isActive(player, pet)) {
                    // familier actif : le clic le range (plusieurs familiers actifs possibles)
                    if (PetData.deactivate(player, pet)) {
                        PetMessages.send(player, "deactivated-pet", "%pet%", pet.name());
                    }
                } else if (!pet.canActivate(player)) {
                    PetMessages.send(player, "cannot-activate-pet", "%pet%", pet.name());
                    return;
                } else {
                    PetData.Activation result = PetData.activate(player, pet);
                    if (result == PetData.Activation.FULL) {
                        PetCommands.tell(player, pet, result);
                        return;
                    }
                }
                if (clickSound != null) {
                    clickSound.playTo(player);
                }
                m.refresh(player);
            }).build());
        }
        var pageSound = PlayableSound.create(c.getSubsection("gui.page-change-sound"));
        builder.addComponent(c.getInt("gui.prev-page.location.row"), c.getInt("gui.prev-page.location.column"),
                pageChanger(PageChanger.Direction.BACKWARDS, c.getString("gui.prev-page.item"), null, pageSound));
        builder.addComponent(c.getInt("gui.next-page.location.row"), c.getInt("gui.next-page.location.column"),
                pageChanger(PageChanger.Direction.FORWARDS, c.getString("gui.next-page.item"),
                        c.has("gui.next-page.item-inactive") ? c.getString("gui.next-page.item-inactive") : null, pageSound));
        builder.setSlot(c.getInt("gui.pet-info.row"), c.getInt("gui.pet-info.column"), Slot.builder((player, m) -> {
            Pet pet = PetData.active(player);
            return pet == null ? PetItems.fromConfig(c.getSubsection("gui.pet-info.no-active")) : PetItems.info(player, pet);
        }).onLeftClick((event, slot, m) -> {
            Player player = (Player) event.getWhoClicked();
            Pet pet = PetData.active(player);
            if (pet != null) {
                PetLevelMenu.open(player, pet);
            }
        }).build());
        if (c.getBool("gui.close.enabled")) {
            builder.setSlot(c.getInt("gui.close.location.row"), c.getInt("gui.close.location.column"),
                    Slot.builder(PetItems.build(c.getString("gui.close.item"), c.getString("gui.close.name"), null))
                            .onLeftClick((event, slot) -> event.getWhoClicked().closeInventory()).build());
        }
        builder.setSlot(c.getInt("gui.deactivate-pet.location.row"), c.getInt("gui.deactivate-pet.location.column"),
                Slot.builder(PetItems.build(c.getString("gui.deactivate-pet.item"), c.getString("gui.deactivate-pet.name"), null))
                        .onLeftClick((event, slot, m) -> {
                            Player player = (Player) event.getWhoClicked();
                            for (Pet pet : PetData.deactivateAll(player)) {
                                PetMessages.send(player, "deactivated-pet", "%pet%", pet.name());
                            }
                            m.refresh(player);
                        }).build());
        builder.setSlot(c.getInt("gui.toggle.location.row"), c.getInt("gui.toggle.location.column"),
                Slot.builder((player, m) -> PetItems.fromConfig(c.getSubsection(PetData.hidden(player)
                        ? "gui.toggle.show-pet" : "gui.toggle.hide-pet")))
                        .onLeftClick((event, slot, m) -> {
                            Player player = (Player) event.getWhoClicked();
                            PetData.setHidden(player, !PetData.hidden(player));
                            m.refresh(player);
                        }).build());
        return builder.build();
    }

    static PageChanger pageChanger(PageChanger.Direction direction, String active, String inactive,
                                   com.willfp.eco.core.sound.AbstractPlayableSound<?> sound) {
        var b = PageChanger.builder(direction).active(PetItems.build(active, null, null));
        if (inactive != null) {
            b.inactive(PetItems.build(inactive, null, null));
        }
        if (sound != null) {
            b.sound(sound);
        }
        return b.build();
    }
}
