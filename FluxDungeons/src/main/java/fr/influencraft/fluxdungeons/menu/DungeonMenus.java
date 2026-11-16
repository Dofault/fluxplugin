package fr.influencraft.fluxdungeons.menu;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.fluxdungeons.dungeon.DungeonDefinition;
import fr.influencraft.fluxdungeons.reward.DungeonKeys;
import fr.influencraft.fluxdungeons.reward.DungeonTokens;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The dungeon menu of /donjon (2026-11-16, file written by {@code assets/menus/gen_menus.py}): the dungeons in a line,
 * from the first one (level 10) to the last (level 100) and a « ? » after it (the adventure goes on), joined by Flux's
 * path (green up to the last dungeon climbed, orange to the one to play, off beyond), turned a page at a time
 * ({@link #PER_PAGE} dungeons a page, an arrow at each end); it opens on the page of the dungeon to play, which glows.
 * Left click on a dungeon: its launch screen; right click: its page of the dungeon shop. The line is in the inventory's
 * first row; above it, party and shop (tokens in the shop's and the info's tooltips, with his class level), then his
 * « stuff RPG » ({@code donjons_equipement}): weapon (a click: his class), armour, his inventory, potions, and the pet
 * he takes into dungeons (a click: menu {@code donjon_familier}, one pet, never a boss's).
 * <p>
 * Lists {@code donjons_boutons} (back, info, class, party, tokens, shop) and {@code donjons_liste} (the dungeons of the
 * whole line: Flux pages it, {@code [list] donjons_liste prev / next / page <n>}, with the page sound of every menu);
 * each page of the menu ({@code donjons_p<n>}) only adds its picture (frames in each job's colour, levels) and its
 * path, coloured by {@code %fluxdungeons_tl_route_<n>%}, and follows the list's page ({@code [page-swap]
 * donjons_p%flux_list_donjons_liste_page%}). {@link #open} sets the list's page to the dungeon to play.
 */
public final class DungeonMenus {

    public static final String LIST = "donjons";
    /** Dungeons on a page of the line (slots 28, 30, 32, 34 of the menu). */
    public static final int PER_PAGE = 4;
    private static final String PORTRAIT = "itemsadder:influportraits:portrait_";

    /** What the menus need from the plugin. */
    public interface Host {
        Collection<DungeonDefinition> dungeons();

        DungeonDefinition dungeon(String id);

        /** Class line of the player ("Pyromancien 12"), null without a class. */
        String classLine(Player player);

        /** Party line ("Seul", "Groupe de 3"). */
        String partyLine(Player player);

        /** Who beat which dungeon, null without the database. */
        fr.influencraft.fluxdungeons.reward.DungeonVictories victories();

        /** Opens the dungeon shop on a dungeon's page (null: every shop). */
        void shop(Player player, String dungeon);

        /** The player prepares the dungeon (its launch screen). */
        void play(Player player, DungeonDefinition def, int level);

        /** The pet each player takes into dungeons. */
        fr.influencraft.fluxdungeons.dungeon.PetChoice pets();

        /** True for a boss's pet (never taken into a dungeon). */
        boolean bossPet(String pet);

        /** His stats computed again (his armour changed). */
        void refreshGear(Player player);

        /** His class's spells in reading order (A first, basic attack, then the passive), empty without a class. */
        List<fr.influencraft.fluxdungeons.rpg.SpellBook.Spell> spells(Player player);
    }

    public static final String PET_MENU = "donjon_familier";
    public static final String ARMOUR_MENU = "rpg_armure";
    public static final String INVENTORY_MENU = "rpg_inventaire";
    private static final String RPG = fr.influencraft.fluxdungeons.rpg.RpgWorld.UNIVERSE;
    /** Armour slot each player is choosing a piece for (menu rpg_armure). */
    private final Map<UUID, Integer> choosing = new ConcurrentHashMap<>();

    private final FluxPlugin plugin;
    private final Host host;
    private final DungeonKeys keys;
    private final DungeonTokens tokens;
    private java.util.function.Predicate<Player> potionsHere = p -> false;

    public DungeonMenus(FluxPlugin plugin, Host host, DungeonKeys keys, DungeonTokens tokens) {
        this.plugin = plugin;
        this.host = host;
        this.keys = keys;
        this.tokens = tokens;
        Lists.register(plugin, "donjons_liste", q -> list(q.viewer()));
        Lists.register(plugin, "donjons_boutons", q -> buttons(q.viewer()));
        Lists.register(plugin, "donjons_equipement", q -> gear(q.viewer()));
        Lists.register(plugin, "donjons_sorts", q -> spells(q.viewer()));
        Lists.register(plugin, "donjon_familier_boutons", q -> petButtons(q.viewer()));
        Lists.register(plugin, "donjon_familiers", q -> pets(q.viewer()));
        Lists.register(plugin, "rpg_armure_boutons", q -> armourButtons(q.viewer()));
        Lists.register(plugin, "rpg_armures", q -> armours(q.viewer()));
        Lists.register(plugin, "rpg_inventaire_boutons", q -> inventoryButtons(q.viewer()));
        Lists.register(plugin, "rpg_inventaire_objets", q -> inventory(q.viewer()));
    }

    /**
     * Opens the line on the page of the dungeon to play. The page is set here, silently: done by a [list] action in
     * the menu's open-actions it played the filters' click sound at every opening.
     */
    public void open(Player p) {
        Lists.act(p, "donjons_liste", "page " + currentPage(p), PER_PAGE);
        invalidate(p);
        FluxApi.open(p, LIST);
    }

    private void invalidate(Player p) {
        for (String l : List.of("donjons_liste", "donjons_boutons", "donjons_equipement", "donjons_sorts", "donjon_familiers", "donjon_familier_boutons",
                "rpg_armures", "rpg_armure_boutons", "rpg_inventaire_objets")) {
            Lists.invalidate(p, l);
        }
    }

    /** {@code play <id>}, {@code pet <id|none>}, {@code armour <36-39>} (choose a piece), {@code wear <index>}. */
    public void act(Player p, String[] args) {
        if (args.length < 2) return;
        switch (args[0]) {
            case "play" -> {
                DungeonDefinition def = host.dungeon(args[1]);
                if (def == null) return;
                p.closeInventory();
                host.play(p, def, 0);
            }
            case "armour" -> chooseArmour(p, args[1]);
            case "wear" -> wear(p, args[1]);
            case "pet" -> {
                var choice = host.pets();
                if (choice == null) return;
                String pet = args[1].equals("none") ? null : args[1];
                if (pet != null && (host.bossPet(pet) || !fr.influencraft.flux.api.pets.Companions.owns(p, pet))) return;
                choice.set(p, pet);
                fr.influencraft.flux.api.util.Sounds.cue(p, "confirm");
                invalidate(p);
            }
            default -> {
            }
        }
    }

    /** {@code tl_route_<page>} (colours of a page's path); null for another one. */
    public String placeholder(Player p, String params) {
        if (params.startsWith("tl_route_")) {
            try {
                return route(p, Integer.parseInt(params.substring(9)));
            } catch (NumberFormatException e) {
                return "";
            }
        }
        return null;
    }

    /** Where the potion merchant can be opened from the tokens button (the rpg universe). */
    public void potions(java.util.function.Predicate<Player> here) {
        this.potionsHere = here;
    }

    // ---------------------------------------------------------------- the line

    /** The dungeons in order (their floor), without the tests and imports. */
    private List<DungeonDefinition> line() {
        List<DungeonDefinition> out = new ArrayList<>();
        for (DungeonDefinition d : host.dungeons()) if (d.floor() > 0 && !d.pet().isEmpty()) out.add(d);
        out.sort(java.util.Comparator.comparingInt(DungeonDefinition::floor));
        return out;
    }

    /** Places on the line: the dungeons then the « ? ». */
    private int places() {
        return line().size() + 1;
    }

    /**
     * Dungeons beaten one after the other from the first: the one to play is the next (a later dungeon beaten before
     * an earlier one does not move the road on).
     */
    private int climbed(Player p) {
        var v = host.victories();
        int n = 0;
        for (DungeonDefinition d : line()) {
            if (v == null || !v.beaten(p, d.id())) break;
            n++;
        }
        return n;
    }

    /** The page of the dungeon to play (the « ? » once every dungeon is beaten). */
    private int currentPage(Player p) {
        return climbed(p) / PER_PAGE + 1;
    }

    /** State of a place of the line: green beaten, orange the one to play, off beyond; the « ? » lights at the end. */
    private String state(int place, int climbed, int dungeons) {
        if (place >= dungeons) return climbed >= dungeons ? "vert" : "eteint";
        if (place < climbed) return "vert";
        return place == climbed ? "orange" : "eteint";
    }

    /**
     * Colours of the path of a page, one per cell in the order gen_menus.py writes them: the way in from the page
     * before (on the first page, the way to the first dungeon: always green), then each place and the way to the next, the way out to the next page (when there
     * is one). A way takes the colour of the place it leads to.
     */
    private String route(Player p, int pageNumber) {
        int dungeons = line().size(), total = places(), k = climbed(p);
        int first = (pageNumber - 1) * PER_PAGE, last = Math.min(total, first + PER_PAGE) - 1;
        List<String> out = new ArrayList<>();
        // the way in: from the page before; on the first page the way to the first dungeon, green (owner, 2026-11-16:
        // the road starts there, no stub before it): its cell and the first dungeon's are green, so the link is
        out.add(first > 0 ? state(first, k, dungeons) : "vert");
        for (int i = first; i <= last; i++) {
            out.add(i == 0 ? "vert" : state(i, k, dungeons));
            if (i < last) out.add(state(i + 1, k, dungeons));
        }
        if (last + 1 < total) out.add(state(last + 1, k, dungeons));
        return String.join(",", out);
    }

    private List<ListEntry> list(Player viewer) {
        List<DungeonDefinition> all = line();
        int k = climbed(viewer);
        int level = levelOf(viewer);
        var victories = host.victories();
        List<ListEntry> out = new ArrayList<>();
        // every place of the line: Flux shows PER_PAGE of them a page
        for (int i = 0; i < places(); i++) {
            if (i >= all.size()) {
                out.add(ListEntry.of("unknown").item(orBarrier(Items.of("itemsadder:fluxdonjon:etage_inconnu", raw("menu.unknown"),
                        Lore.create().description(raw("menu.unknown-desc")).build(), viewer))));
                continue;
            }
            DungeonDefinition def = all.get(i);
            boolean current = i == k;
            int wins = victories == null ? 0 : victories.wins(viewer, def.id());
            Lore lore = Lore.create().description(def.description())
                    .kv(raw("menu.level"), def.recommendedLevel())
                    .kv(raw("menu.players-recommended"), def.recommendedPlayers() <= 1 ? raw("menu.solo")
                            : raw("menu.party-size", "%count%", String.valueOf(def.recommendedPlayers())))
                    .kv(raw("menu.job"), keys.jobName(def.job()));
            if (keys.needs(viewer, def)) lore.kv(raw("menu.keys"), String.valueOf(keys.count(viewer, def.job())));
            if (current) lore.info(raw("menu.next-floor"));
            if (wins > 0) lore.ok(raw("menu.beaten", "%count%", String.valueOf(wins)));
            else if (level >= 0 && level < Math.min(def.recommendedLevel(), 50)) lore.ko(raw("menu.too-high"));
            else lore.info(raw("menu.not-beaten"));
            if (def.recommendedLevel() > 50) lore.info(raw("menu.beyond-cap"));
            lore.left(raw("menu.play")).right(raw("menu.shop-action"));
            ItemStack item = Items.of(PORTRAIT + def.pet(), def.name(), lore.build(), viewer);
            if (item == null) item = Items.dress(new ItemStack(def.icon()), def.name(), lore.build(), viewer);
            // the dungeon to play glows (the road shows those already climbed)
            if (current) item.editMeta(m -> m.setEnchantmentGlintOverride(true));
            out.add(ListEntry.of(def.id()).item(item).set("left", "[fluxdungeon] play " + def.id())
                    .set("right", "[fluxdungeon] shop open " + def.id()));
        }
        return out;
    }

    /** Back, info (tokens, class level), then party and shop (his class is the weapon of his stuff). */
    private List<ListEntry> buttons(Player viewer) {
        List<ListEntry> out = new ArrayList<>();
        Currency money = tokens.currency();
        String owned = money == null ? "0" : money.format(money.balance(viewer));
        String line = host.classLine(viewer);
        out.add(button("close", Items.of("itemsadder:fluxui:btn_back_orange", raw("menu.close"),
                Lore.create().left(raw("menu.close-action")).build(), viewer), "[menu] main", ""));
        out.add(button("info", Items.of("itemsadder:fluxui:btn_info_blue", raw("menu.info"),
                Lore.create().description(raw("menu.info-desc"))
                        .kv(raw("menu.class"), line == null ? raw("menu.no-class") : line)
                        .kv(raw("menu.tokens-owned"), owned).build(), viewer), "", ""));
        out.add(button("party", Items.of("PLAYER_HEAD", raw("menu.party"),
                Lore.create().description(host.partyLine(viewer)).description(raw("menu.party-desc"))
                        .left(raw("menu.party-action")).build(), viewer), "[close]", "[player] groupe"));
        out.add(button("shop", Items.of("itemsadder:fluxdonjon:boutique", raw("menu.shop"),
                Lore.create().description(raw("menu.shop-desc")).kv(raw("menu.tokens-owned"), owned)
                        .left(raw("menu.shop-open")).build(), viewer), "[fluxdungeon] shop open", ""));
        return out;
    }

    // ---------------------------------------------------------------- his gear

    private static final String[] ARMOUR = {"helmet", "chestplate", "leggings", "boots"};
    /** Index of each armour piece in an inventory's contents (PlayerInventory#getContents): helmet 39 ... boots 36. */
    private static final int[] ARMOUR_INDEX = {39, 38, 37, 36};

    /**
     * His rpg gear, as he really has it in the rpg universe, wherever he is (Flux's {@code Universes.contents}: live in
     * the rpg universe, else his rpg profile kept aside; read-only copies): weapon (a click: his class), armour (a click:
     * choose a piece among his rpg inventory), health and mana potions (the best he carries, with how many of that
     * kind), his rpg inventory to look at, his pet for dungeons.
     */
    private List<ListEntry> gear(Player viewer) {
        ItemStack[] contents = fr.influencraft.flux.api.universe.Universes.contents(viewer, RPG);
        boolean known = contents != null;
        List<ListEntry> out = new ArrayList<>();
        ItemStack weapon = null;
        int[] count = new int[2];
        ItemStack[] best = new ItemStack[2];
        String[] bestId = new String[2];
        if (known) {
            for (int i = 0; i < Math.min(36, contents.length); i++) {
                ItemStack it = contents[i];
                if (it == null || it.getType().isAir()) continue;
                String id = fr.influencraft.flux.api.items.CustomItems.id(it);
                if (id == null) continue;
                if (weapon == null && id.startsWith("fluxrpg:class_")) weapon = it;
                int kind = id.startsWith("fluxrpg:potion_soin_") ? 0 : id.startsWith("fluxrpg:potion_mana_") ? 1 : -1;
                if (kind < 0) continue;
                count[kind] += it.getAmount();
                if (bestId[kind] == null || id.compareTo(bestId[kind]) > 0) {
                    bestId[kind] = id;
                    best[kind] = it;
                }
            }
        }
        String unknown = "gear.unknown";
        // the weapon: his class
        String line = host.classLine(viewer);
        Lore wl = Lore.create().description(raw(known ? "gear.weapon-desc" : unknown))
                .kv(raw("menu.class"), line == null ? raw("menu.no-class") : line).left(raw("menu.class-action"));
        // no class yet: a book (choosing one)
        out.add(slot("weapon", weapon, line == null ? "BOOK" : "BLAZE_ROD", line == null ? raw("menu.no-class") : raw("gear.weapon"), wl, viewer)
                .set("left", "[player] classe"));
        for (int i = 0; i < ARMOUR.length; i++) {
            ItemStack piece = known && contents.length > ARMOUR_INDEX[i] ? contents[ARMOUR_INDEX[i]] : null;
            Lore lore = Lore.create().description(raw(known ? (piece == null ? "gear.empty-desc" : "gear.armour-desc") : unknown))
                    .left(raw("gear.armour-choose"));
            out.add(slot(ARMOUR[i], piece, "LIGHT_GRAY_STAINED_GLASS_PANE", raw("gear." + ARMOUR[i]), lore, viewer)
                    .set("left", "[fluxdungeon] armour " + ARMOUR_INDEX[i]));
        }
        Lore bag = Lore.create().description(raw("gear.inventory-desc")).left(raw("gear.inventory-open"));
        out.add(ListEntry.of("inventory").item(orBarrier(Items.of("BUNDLE", raw("gear.inventory"), bag.build(), viewer)))
                .set("left", known ? "[menu] " + INVENTORY_MENU : "").set("right", ""));
        for (int k = 0; k < 2; k++) {
            String key = k == 0 ? "health" : "mana";
            ItemStack shown = best[k];
            if (shown != null) shown.setAmount(Math.max(1, Math.min(64, count[k])));
            Lore lore = Lore.create().description(raw(known ? "gear.potions-desc" : unknown));
            if (known) lore.kv(raw("gear.potions-count"), String.valueOf(count[k]));
            if (potionsHere.test(viewer)) lore.left(raw("menu.potions-action"));
            ListEntry e = slot("potion_" + key, shown, "GLASS_BOTTLE", raw("gear.potions-" + key), lore, viewer);
            out.add(potionsHere.test(viewer) ? e.set("left", "[close]").set("right", "[player] potions") : e);
        }
        out.add(petSlot(viewer));
        return out;
    }

    // ---------------------------------------------------------------- spells

    /**
     * The « spells » line: his class's spells as the class pages tell them (SpellBook), A first, then F, the weapon's
     * spell, the basic attack and the passive, each with its icon, key and mana; read only. Without a class: a book that
     * opens the classes.
     */
    private List<ListEntry> spells(Player viewer) {
        List<ListEntry> out = new ArrayList<>();
        for (var sp : host.spells(viewer)) {
            Lore lore = Lore.create().description(sp.text())
                    .kv(raw("menu.spell-key"), raw("class-pages.keys." + sp.key()));
            if (sp.mana() >= 0) lore.kv(raw("menu.spell-cost"), sp.mana() == 0 ? raw("class-pages.free")
                    : raw("class-pages.mana", "%mana%", fr.influencraft.flux.api.util.Format.decimal(sp.mana())));
            ItemStack item = Items.of("itemsadder:" + sp.icon(), "&#FFD94D" + sp.name(), lore.build(), viewer);
            if (item == null) item = Items.of("ENCHANTED_BOOK", "&#FFD94D" + sp.name(), lore.build(), viewer);
            out.add(ListEntry.of("spell_" + sp.slot()).item(orBarrier(item)).set("left", "").set("right", ""));
        }
        if (out.isEmpty()) {
            Lore lore = Lore.create().description(raw("menu.spells-none")).left(raw("menu.class-action"));
            out.add(ListEntry.of("no_class").item(orBarrier(Items.of("BOOK", raw("menu.no-class"), lore.build(), viewer)))
                    .set("left", "[player] classe").set("right", ""));
        }
        return out;
    }

    // ---------------------------------------------------------------- choosing a piece of armour

    /** Opens the pieces of that slot he carries in his rpg inventory (wherever he is). */
    private void chooseArmour(Player p, String index) {
        int slot;
        try {
            slot = Integer.parseInt(index);
        } catch (NumberFormatException e) {
            return;
        }
        if (slot < 36 || slot > 39) return;
        ItemStack[] contents = fr.influencraft.flux.api.universe.Universes.contents(p, RPG);
        if (contents == null) {
            plugin.messages().send(p, "gear.armour-unknown");
            fr.influencraft.flux.api.util.Sounds.cue(p, "error");
            return;
        }
        if (candidates(contents, slot).isEmpty()) {
            plugin.messages().send(p, "gear.no-armour", "%piece%", raw("gear." + ARMOUR[39 - slot]));
            fr.influencraft.flux.api.util.Sounds.cue(p, "error");
            return;
        }
        choosing.put(p.getUniqueId(), slot);
        invalidate(p);
        FluxApi.open(p, ARMOUR_MENU);
    }

    /** Indexes (0-35) of the rpg pieces of that armour slot among these contents. */
    private static List<Integer> candidates(ItemStack[] contents, int slot) {
        org.bukkit.inventory.EquipmentSlot wanted = switch (slot) {
            case 39 -> org.bukkit.inventory.EquipmentSlot.HEAD;
            case 38 -> org.bukkit.inventory.EquipmentSlot.CHEST;
            case 37 -> org.bukkit.inventory.EquipmentSlot.LEGS;
            default -> org.bukkit.inventory.EquipmentSlot.FEET;
        };
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < Math.min(36, contents.length); i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir() || it.getType().getEquipmentSlot() != wanted) continue;
            String id = fr.influencraft.flux.api.items.CustomItems.id(it);
            if (id != null && id.startsWith("fluxrpg:")) out.add(i);
        }
        return out;
    }

    /**
     * Puts on the piece at that index of his rpg inventory, the worn one going to its place: one swap through Flux's
     * {@code Universes.editContents} (live in the rpg universe, else his rpg profile rewritten at once; refused during a
     * universe change), checked again inside it: the piece still there, the same as he saw, still of that slot.
     * Nothing is ever made or lost: two items change places.
     */
    private void wear(Player p, String index) {
        Integer slot = choosing.get(p.getUniqueId());
        int i;
        try {
            i = Integer.parseInt(index);
        } catch (NumberFormatException e) {
            return;
        }
        ItemStack[] seen = fr.influencraft.flux.api.universe.Universes.contents(p, RPG);
        ItemStack expected = seen == null || i < 0 || i >= seen.length ? null : seen[i];
        if (slot == null || expected == null) {
            fr.influencraft.flux.api.util.Sounds.cue(p, "error");
            return;
        }
        int armour = slot;
        boolean done = fr.influencraft.flux.api.universe.Universes.editContents(p, RPG, c -> {
            if (armour >= c.length || !candidates(c, armour).contains(i) || !expected.isSimilar(c[i])
                    || c[i].getAmount() != expected.getAmount()) return null;
            ItemStack worn = c[armour];
            c[armour] = c[i];
            c[i] = worn == null || worn.getType().isAir() ? null : worn;
            return c;
        });
        if (!done) {
            fr.influencraft.flux.api.util.Sounds.cue(p, "error");
            return;
        }
        if (RPG.equals(fr.influencraft.flux.api.universe.Universes.of(p))) host.refreshGear(p);
        choosing.remove(p.getUniqueId());
        fr.influencraft.flux.api.util.Sounds.cue(p, "success");
        invalidate(p);
        FluxApi.open(p, LIST);
    }

    private List<ListEntry> armourButtons(Player viewer) {
        return List.of(
                button("back", Items.of("itemsadder:fluxui:btn_back_orange", raw("pets.back"),
                        Lore.create().left(raw("pets.back-action")).build(), viewer), "[menu] " + LIST, ""),
                button("info", Items.of("itemsadder:fluxui:btn_info_blue", raw("gear.armour-title"),
                        Lore.create().description(raw("gear.armour-info")).build(), viewer), "", ""));
    }

    /** The piece he wears first (no click), then the pieces of that slot in his rpg inventory. */
    private List<ListEntry> armours(Player viewer) {
        Integer slot = choosing.get(viewer.getUniqueId());
        List<ListEntry> out = new ArrayList<>();
        ItemStack[] contents = fr.influencraft.flux.api.universe.Universes.contents(viewer, RPG);
        if (slot == null || contents == null || slot >= contents.length) return out;
        ItemStack worn = contents[slot];
        if (worn != null && !worn.getType().isAir()) {
            ListEntry e = slot("worn", worn, "BARRIER", raw("gear." + ARMOUR[39 - slot]),
                    Lore.create().ok(raw("gear.worn")), viewer);
            e.item().editMeta(m -> m.setEnchantmentGlintOverride(true));
            out.add(e);
        }
        for (int i : candidates(contents, slot)) {
            ItemStack it = contents[i];
            out.add(slot("piece_" + i, it, "BARRIER", raw("gear." + ARMOUR[39 - slot]),
                    Lore.create().left(raw("gear.wear")), viewer).set("left", "[fluxdungeon] wear " + i));
        }
        return out;
    }

    // ---------------------------------------------------------------- his rpg inventory, to look at

    private List<ListEntry> inventoryButtons(Player viewer) {
        return List.of(
                button("back", Items.of("itemsadder:fluxui:btn_back_orange", raw("pets.back"),
                        Lore.create().left(raw("pets.back-action")).build(), viewer), "[menu] " + LIST, ""),
                button("info", Items.of("itemsadder:fluxui:btn_info_blue", raw("gear.inventory"),
                        Lore.create().description(raw("gear.inventory-info")).build(), viewer), "", ""));
    }

    /** His rpg inventory as it is (copies, read only): the 27 slots, then the hotbar. */
    private List<ListEntry> inventory(Player viewer) {
        ItemStack[] contents = fr.influencraft.flux.api.universe.Universes.contents(viewer, RPG);
        List<ListEntry> out = new ArrayList<>();
        if (contents == null) return out;
        for (int k = 0; k < 36; k++) {
            int i = k < 27 ? k + 9 : k - 27;
            ItemStack it = i < contents.length ? contents[i] : null;
            out.add(it == null || it.getType().isAir() ? ListEntry.gap() : ListEntry.of("slot_" + i)
                    .item(fr.influencraft.flux.api.items.CustomItems.preview(it)));
        }
        return out;
    }

    /** A gear slot: the item itself (its own name and description, a line added), else a placeholder. */
    private ListEntry slot(String id, ItemStack item, String empty, String name, Lore extra, Player viewer) {
        ItemStack shown;
        if (item != null) {
            item = fr.influencraft.flux.api.items.CustomItems.preview(item); // shown, not held: no action lines
            List<String> lines = new ArrayList<>(item.hasItemMeta() && item.getItemMeta().hasLore()
                    ? item.getItemMeta().lore().stream().map(fr.influencraft.flux.api.text.Texts::ampersand).toList() : List.of());
            lines.addAll(extra.build());
            String own = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                    ? fr.influencraft.flux.api.text.Texts.ampersand(item.getItemMeta().displayName()) : name;
            shown = Items.dress(item, own, lines, viewer);
        } else {
            shown = orBarrier(Items.of(empty, name, extra.build(), viewer));
        }
        return ListEntry.of(id).item(shown).set("left", "").set("right", "");
    }

    /** His pet for dungeons (a click: choose it). */
    private ListEntry petSlot(Player viewer) {
        var choice = host.pets();
        String pet = choice == null ? null : choice.get(viewer);
        if (pet != null && !fr.influencraft.flux.api.pets.Companions.owns(viewer, pet)) pet = null;
        Lore lore = Lore.create().description(raw("gear.pet-desc")).left(raw("gear.pet-choose"));
        ItemStack item;
        if (pet == null) item = Items.of("LEAD", raw("gear.pet-none"), lore.build(), viewer);
        else {
            ItemStack portrait = fr.influencraft.flux.api.pets.Companions.portrait(pet);
            String name = fr.influencraft.flux.api.pets.Companions.name(pet);
            item = portrait == null ? Items.of("LEAD", name == null ? pet : name, lore.build(), viewer)
                    : Items.dress(portrait.clone(), name == null ? pet : name, lore.build(), viewer);
        }
        return ListEntry.of("pet").item(orBarrier(item)).set("left", "[menu] " + PET_MENU).set("right", "");
    }

    // ---------------------------------------------------------------- the pet menu

    private List<ListEntry> petButtons(Player viewer) {
        return List.of(
                button("back", Items.of("itemsadder:fluxui:btn_back_orange", raw("pets.back"),
                        Lore.create().left(raw("pets.back-action")).build(), viewer), "[menu] " + LIST, ""),
                button("info", Items.of("itemsadder:fluxui:btn_info_blue", raw("pets.info"),
                        Lore.create().description(raw("pets.info-desc")).build(), viewer), "", ""));
    }

    /** « None », then the pets he owns, a boss's excepted; the one he takes glows. */
    private List<ListEntry> pets(Player viewer) {
        var choice = host.pets();
        String chosen = choice == null ? null : choice.get(viewer);
        List<ListEntry> out = new ArrayList<>();
        Lore none = Lore.create().description(raw("pets.none-desc"));
        if (chosen == null) none.ok(raw("pets.taken"));
        else none.left(raw("pets.take"));
        ItemStack noneItem = orBarrier(Items.of("BARRIER", raw("gear.pet-none"), none.build(), viewer));
        if (chosen == null) noneItem.editMeta(m -> m.setEnchantmentGlintOverride(true));
        out.add(ListEntry.of("none").item(noneItem).set("left", "[fluxdungeon] pet none"));
        for (var c : fr.influencraft.flux.api.pets.Companions.owned(viewer)) {
            if (host.bossPet(c.id())) continue;
            boolean on = c.id().equals(chosen);
            Lore lore = Lore.create().kv(raw("pets.level"), c.level());
            if (on) lore.ok(raw("pets.taken"));
            else lore.left(raw("pets.take"));
            ItemStack portrait = fr.influencraft.flux.api.pets.Companions.portrait(c.id());
            ItemStack item = Items.dress((portrait != null ? portrait : c.icon() != null ? c.icon() : new ItemStack(Material.BONE)).clone(),
                    c.name(), lore.build(), viewer);
            if (on) item.editMeta(m -> m.setEnchantmentGlintOverride(true));
            out.add(ListEntry.of(c.id()).item(item).set("left", "[fluxdungeon] pet " + c.id()).set("name", c.name()));
        }
        return out;
    }

    /** The viewer's rpg level, -1 when unknown. */
    private int levelOf(Player viewer) {
        String line = host.classLine(viewer);
        if (line == null) return -1;
        try {
            return Integer.parseInt(line.substring(line.lastIndexOf(' ') + 1));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static ItemStack orBarrier(ItemStack i) {
        return i == null ? new ItemStack(Material.BARRIER) : i;
    }

    private static ListEntry button(String id, ItemStack item, String left, String left2) {
        return ListEntry.of(id).item(orBarrier(item)).set("left", left).set("left2", left2);
    }

    private String raw(String key, String... vars) {
        return plugin.messages().raw(key, vars);
    }
}
