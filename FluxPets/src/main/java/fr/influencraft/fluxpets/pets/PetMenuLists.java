package fr.influencraft.fluxpets.pets;

import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.fluxpets.FluxPets;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Content of the pets menus, built in Java (2026-11-13; hundreds of placeholders before):
 * <ul>
 *   <li>{@code familiers_slots}: the 5 slots of active pets (pet, free or locked);</li>
 *   <li>{@code familiers_view}: the adopted pets of the current filter, active ones glowing (Flux pages them);</li>
 *   <li>{@code familier_levels}: the levels of the pet chosen with {@code /pets select}.</li>
 * </ul>
 * Each entry carries its click actions ({@code %entry_left%}, {@code %entry_left2%}, {@code %entry_right%},
 * {@code %entry_right2%}), empty when the entry has none.
 */
public final class PetMenuLists {

    public static final String SLOTS = "familiers_slots", VIEW = "familiers_view", LEVELS = "familier_levels";
    private static final String PORTRAIT = "itemsadder:influportraits:portrait_";
    private static final int SLOT_COUNT = 5;
    /** Clasps joining the slots of a pet that takes several (pack fluxpetboss, scripts/influcard/gen_familiers_boss.py). */
    private static final String CLASP = "itemsadder:fluxpetboss:slot_";

    private final FluxPets plugin;

    public PetMenuLists(FluxPets plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Lists.register(plugin, SLOTS, q -> slots(q.viewer()));
        Lists.register(plugin, VIEW, q -> view(q.viewer()));
        Lists.register(plugin, LEVELS, q -> levels(q.viewer()));
    }

    /** The lists are built again (a pet changed, a filter...). */
    public static void invalidate(Player player) {
        Lists.invalidate(player, SLOTS);
        Lists.invalidate(player, VIEW);
        Lists.invalidate(player, LEVELS);
    }

    private String t(String key, String... vars) {
        return plugin.text("menu." + key, vars);
    }

    private String name(Pet pet, int level) {
        return pet.name() + " " + t("level-suffix", "%level%", String.valueOf(level));
    }

    /** Description, effects at the player's level and progress of a pet. */
    private Lore sheet(Player player, Pet pet) {
        int level = PetData.level(player, pet);
        Lore lore = Lore.create().description(pet.description());
        for (String line : pet.effectsDescription(level)) lore.line(line);
        String progress = level >= pet.maxLevel() ? "100" : Pet.nice(Math.round(PetData.progress(player, pet) * 10000) / 100.0);
        if (pet.slots() > 1) lore.info(t("slots-taken", "%slots%", String.valueOf(pet.slots())));
        if (pet.mountable() && Pets.mountsEnabled()) {
            lore.info(t("mount-from", "%level%", String.valueOf(pet.mount().level())));
        }
        return lore.kv(t("progress"), progress + " %");
    }

    /**
     * The 5 slots of active pets. A pet taking several slots (legendary boss pets) fills them all: its portrait in the
     * middle one, golden clasps on the others (same tooltip and clicks), so its card is never stretched.
     */
    private List<ListEntry> slots(Player player) {
        List<ListEntry> out = new ArrayList<>();
        int max = PetData.maxActive(player);
        int n = 1;
        for (Pet pet : PetData.actives(player)) {
            int level = PetData.level(player, pet);
            int width = Math.min(pet.slots(), SLOT_COUNT - n + 1);
            int middle = (width - 1) / 2;
            for (int k = 0; k < width; k++, n++) {
                String icon = k == middle ? PORTRAIT + pet.id()
                        : k < middle ? CLASP + "left" : k == width - 1 ? CLASP + "right" : CLASP + "middle";
                ItemStack item = Items.of(icon, name(pet, level),
                        sheet(player, pet).left(t("see-progress")).right(t("put-away")).build(), player);
                out.add(ListEntry.of(String.valueOf(n)).item(item)
                        .set("left", "[player] pets select " + pet.id()).set("left2", "[menu] familier")
                        .set("right", "[player] pets toggle " + pet.id()).set("right2", "[refresh]"));
            }
        }
        for (; n <= SLOT_COUNT; n++) {
            ItemStack item;
            if (n > max) {
                String path = "gui.pet-slots.locked.grades." + n;
                String grade = Pets.settings().has(path) ? Pets.settings().getString(path) : "";
                item = Items.of("itemsadder:influitems:lock", t("slot-locked"),
                        Lore.create().description(t("slot-locked-description"))
                                .kv(t("rank-needed"), fr.influencraft.flux.api.text.Texts.placeholders(grade, player)).build(), player);
            } else {
                item = Items.of("itemsadder:influitems:add", t("slot-free"),
                        Lore.create().description(t("slot-free-description")).build(), player);
            }
            out.add(ListEntry.of(String.valueOf(n)).item(item).set("left", "").set("left2", "").set("right", "").set("right2", ""));
        }
        return out;
    }

    private List<ListEntry> view(Player player) {
        List<ListEntry> out = new ArrayList<>();
        for (Pet pet : PetView.list(player)) {
            int level = PetData.level(player, pet);
            if (level < 1) continue;
            boolean active = PetData.isActive(player, pet);
            Lore lore = sheet(player, pet);
            if (active) lore.ok(t("active"));
            lore.left(t(active ? "put-away" : "activate")).right(t("see-progress"));
            ItemStack item = Items.of(PORTRAIT + pet.id(), name(pet, level), lore.build(), player);
            if (item != null && active) item.editMeta(m -> m.setEnchantmentGlintOverride(true));
            out.add(ListEntry.of(pet.id()).item(item)
                    .set("left", "[player] pets toggle " + pet.id()).set("left2", "[refresh]")
                    .set("right", "[player] pets select " + pet.id()).set("right2", "[menu] familier"));
        }
        return out;
    }

    private List<ListEntry> levels(Player player) {
        List<ListEntry> out = new ArrayList<>();
        Pet pet = PetView.selected(player);
        if (pet == null) return out;
        int level = PetData.level(player, pet);
        for (int n = 1; n <= Math.min(10, pet.maxLevel()); n++) {
            Lore lore = Lore.create();
            for (String line : pet.rewardsDescription(n)) lore.line(line);
            String icon;
            if (n <= level) {
                icon = "itemsadder:influitems:checkmark";
                lore.ok(t("unlocked"));
            } else if (n == level + 1) {
                icon = "itemsadder:influitems:tracking";
                String progress = Pet.nice(Math.round(PetData.progress(player, pet) * 10000) / 100.0);
                lore.kv(t("progress"), progress + " %")
                        .kv(t("cards"), Pet.nice(PetData.xp(player, pet)) + "&#B376E5/&f" + Pet.nice(pet.xpForLevel(level + 1)));
            } else {
                icon = "itemsadder:influitems:lock";
                lore.ko(t("locked"));
            }
            ItemStack item = Items.of(icon, t("level-name", "%level%", String.valueOf(n)), lore.build(), player);
            if (item != null) item.setAmount(n);
            out.add(ListEntry.of(String.valueOf(n)).item(item));
        }
        return out;
    }
}
