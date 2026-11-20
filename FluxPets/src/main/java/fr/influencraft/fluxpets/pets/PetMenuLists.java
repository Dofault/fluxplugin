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
 *   <li>{@code familier_levels}: the levels of the pet chosen with {@code /pets select};</li>
 *   <li>{@code familier_portrait}: its card with its whole sheet (description, effects, level, progress);</li>
 *   <li>{@code familier_remove}: operators only, takes the chosen pet out of their collection (testing).</li>
 * </ul>
 * {@link #button} is the one button of a pet, the same in every menu showing it (the pets menu, the Careers menu
 * through Flux's {@code Companions.button}): adopted, its sheet and clicks; not adopted, its sheet at level 1.
 * Each entry carries its click actions ({@code %entry_left%}, {@code %entry_left2%}, {@code %entry_right%},
 * {@code %entry_right2%}), empty when the entry has none.
 */
public final class PetMenuLists {

    public static final String SLOTS = "familiers_slots", VIEW = "familiers_view", LEVELS = "familier_levels",
            PORTRAIT_ITEM = "familier_portrait", REMOVE = "familier_remove";
    private static PetMenuLists instance;
    private static final String PORTRAIT = "itemsadder:influportraits:portrait_";
    private static final int SLOT_COUNT = 5;
    /** Clasps joining the slots of a pet that takes several (pack fluxpetboss, scripts/influcard/gen_familiers_boss.py). */
    private static final String CLASP = "itemsadder:fluxpetboss:slot_";

    private final FluxPets plugin;

    public PetMenuLists(FluxPets plugin) {
        this.plugin = plugin;
        instance = this;
    }

    /** The button of a pet for this viewer (null before the menus are registered). */
    public static ListEntry buttonOf(Player viewer, Pet pet) {
        return instance == null ? null : instance.button(viewer, pet);
    }

    public void register() {
        Lists.register(plugin, SLOTS, q -> slots(q.viewer()));
        Lists.register(plugin, VIEW, q -> view(q.viewer()));
        Lists.register(plugin, LEVELS, q -> levels(q.viewer()));
        Lists.registerItem(plugin, PORTRAIT_ITEM, this::portrait);
        Lists.register(plugin, REMOVE, q -> remove(q.viewer()));
    }

    /** The lists are built again (a pet changed, a filter...). */
    public static void invalidate(Player player) {
        Lists.invalidate(player, SLOTS);
        Lists.invalidate(player, VIEW);
        Lists.invalidate(player, LEVELS);
        Lists.invalidate(player, PORTRAIT_ITEM);
        Lists.invalidate(player, REMOVE);
        fr.influencraft.flux.api.pets.Companions.changed(player);
    }

    private String t(String key, String... vars) {
        return plugin.text("menu." + key, vars);
    }

    private String name(Pet pet, int level) {
        return pet.name() + " " + t("level-suffix", "%level%", String.valueOf(level));
    }

    private static final java.util.regex.Pattern COLOURS = java.util.regex.Pattern.compile("&#[0-9A-Fa-f]{6}|&[0-9a-fk-orA-FK-OR]");

    /**
     * A pet's effect lines (effects-description, rewards-description of its file) in the suite's style (2026-11-16):
     * « Name : value » is a key : value; a name followed by its indented explanation is a key : value too (the
     * explanation as value, the indented lines under a « Name : value » added to its value); any other sentence a
     * description. The colours of the files are dropped: style.yml colours everything.
     */
    static void effects(Lore lore, List<String> lines) {
        String key = null;
        StringBuilder value = null;
        String glue = " ";
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String text = COLOURS.matcher(raw).replaceAll("").trim();
            if (text.isEmpty()) continue;
            boolean indented = raw.startsWith(" ");
            if (indented && key != null) {
                value.append(value.length() == 0 ? "" : glue).append(text);
                continue;
            }
            if (key != null) lore.kv(key, value.toString());
            key = null;
            int colon = text.indexOf(" : ");
            boolean titled = i + 1 < lines.size() && lines.get(i + 1).startsWith(" ");
            if (colon > 0) {
                key = text.substring(0, colon);
                value = new StringBuilder(text.substring(colon + 3));
                glue = " · ";
            } else if (titled) {
                key = text;
                value = new StringBuilder();
                glue = " ";
            } else lore.description(text);
        }
        if (key != null) lore.kv(key, value.toString());
    }

    /**
     * Description, effects at the player's level and progress of a pet; for a pet he has not adopted, its effects at
     * level 1 and no progress.
     */
    private Lore sheet(Player player, Pet pet) {
        int level = PetData.level(player, pet);
        Lore lore = Lore.create().description(COLOURS.matcher(pet.description()).replaceAll(""));   // style.yml colours it
        effects(lore, pet.effectsDescription(Math.max(1, level)));
        if (pet.slots() > 1) lore.info(t("slots-taken", "%slots%", String.valueOf(pet.slots())));
        if (pet.mountable() && Pets.mountsEnabled()) {
            lore.info(t("mount-from", "%level%", String.valueOf(pet.mount().level())));
        }
        if (level < 1) return lore;
        String progress = level >= pet.maxLevel() ? "100" : Pet.nice(Math.round(PetData.progress(player, pet) * 10000) / 100.0);
        return lore.kv(t("progress"), progress + " %");
    }

    /**
     * The button of a pet, the same wherever it is shown. Adopted: its sheet, left click to activate or put away,
     * right click for its progress (glowing while active). Not adopted: its sheet at level 1, no click (an admin adopts it with a left click).
     */
    private ListEntry button(Player player, Pet pet) {
        int level = PetData.level(player, pet);
        Lore lore = sheet(player, pet);
        if (level < 1) {
            lore.ko(t("not-adopted"));
            // an admin adopts it from here (with its reveal, the menu closes to show it): testing
            boolean admin = fr.influencraft.flux.api.staff.Staff.isAdmin(player);
            if (admin) lore.left(t("admin-adopt"));
            ItemStack item = Items.of(PORTRAIT + pet.id(), pet.name(), lore.build(), player);
            return ListEntry.of(pet.id()).item(item)
                    .set("left", admin ? "[player] pets adopt " + pet.id() : "").set("left2", admin ? "[close]" : "")
                    .set("right", "").set("right2", "");
        }
        boolean active = PetData.isActive(player, pet);
        if (active) lore.ok(t("active"));
        lore.left(t(active ? "put-away" : "activate")).right(t("see-progress"));
        ItemStack item = Items.of(PORTRAIT + pet.id(), name(pet, level), lore.build(), player);
        if (item != null && active) item.editMeta(m -> m.setEnchantmentGlintOverride(true));
        return ListEntry.of(pet.id()).item(item)
                .set("left", "[player] pets toggle " + pet.id()).set("left2", "[refresh]")
                .set("right", "[player] pets select " + pet.id()).set("right2", "[menu] familier");
    }

    /** The card of the chosen pet, with its whole sheet (progress menu). */
    private ItemStack portrait(Player player) {
        Pet pet = PetView.selected(player);
        if (pet == null) return null;
        int level = PetData.level(player, pet);
        fr.influencraft.fluxpets.CardRegistry registry = plugin.registry();
        fr.influencraft.fluxpets.CardRegistry.PetEntry entry = registry.pet(pet.id());
        fr.influencraft.fluxpets.CardRegistry.Card card = entry == null ? null : registry.cardOf(entry);
        String icon = card == null ? PORTRAIT + pet.id() : "itemsadder:" + card.itemId();
        Lore lore = sheet(player, pet).kv(t("level"), level + " / " + pet.maxLevel());
        return Items.of(icon, name(pet, level), lore.build(), player);
    }

    /** Operators only: a button taking the chosen pet out of their own collection (two clicks). */
    private List<ListEntry> remove(Player player) {
        Pet pet = PetView.selected(player);
        if (pet == null || !fr.influencraft.flux.api.staff.Staff.isAdmin(player) || !PetData.has(player, pet)) return List.of();
        ItemStack item = Items.of("itemsadder:influitems:chest_delete", t("remove"),
                Lore.create().description(t("remove-description")).left(t("remove-action")).build(), player);
        return List.of(ListEntry.of(pet.id()).item(item).set("confirm", t("remove-confirm")));
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

    /** Adopted pets of the current filter; an operator sees every pet, the ones he lacks after his own. */
    private List<ListEntry> view(Player player) {
        List<ListEntry> out = new ArrayList<>();
        for (Pet pet : PetView.list(player)) out.add(button(player, pet));
        return out;
    }

    private List<ListEntry> levels(Player player) {
        List<ListEntry> out = new ArrayList<>();
        Pet pet = PetView.selected(player);
        if (pet == null) return out;
        int level = PetData.level(player, pet);
        for (int n = 1; n <= Math.min(10, pet.maxLevel()); n++) {
            Lore lore = Lore.create();
            effects(lore, pet.rewardsDescription(n));
            String icon;
            if (n <= level) {
                icon = "itemsadder:influitems:checkmark";
                lore.ok(t("unlocked"));
            } else if (n == level + 1) {
                icon = "itemsadder:influitems:tracking";
                String progress = Pet.nice(Math.round(PetData.progress(player, pet) * 10000) / 100.0);
                lore.kv(t("progress"), progress + " %")
                        .kv(t("cards"), Pet.nice(PetData.xp(player, pet)) + " / " + Pet.nice(pet.xpForLevel(level + 1)));
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
