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

/**
 * The dungeon menu of /donjon (2026-11-16, file written by {@code assets/menus/gen_menus.py}): the dungeons in a line,
 * from the first one (level 10) to the last (level 100) and a « ? » after it (the adventure goes on), joined by Flux's
 * path (green up to the last dungeon climbed, orange to the one to play, off beyond), turned a page at a time
 * ({@link #PER_PAGE} dungeons a page, an arrow at each end); it opens on the page of the dungeon to play, which glows.
 * Left click on a dungeon: its launch screen; right click: its page of the dungeon shop. Above the line: class,
 * party, tokens, shop.
 * <p>
 * Lists {@code donjons_boutons} (back, info, class, party, tokens, shop) and {@code donjons_liste} (the dungeons of the
 * whole line: Flux pages it, {@code [list] donjons_liste prev / next / page <n>}, with the page sound of every menu);
 * each page of the menu ({@code donjons_p<n>}) only adds its picture (frames in each job's colour, levels) and its
 * path, coloured by {@code %fluxdungeons_tl_route_<n>%}, and follows the list's page ({@code [page-swap]
 * donjons_p%flux_list_donjons_liste_page%}). {@code %fluxdungeons_tl_current%}: the page of the dungeon to play.
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
    }

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
    }

    public void open(Player p) {
        invalidate(p);
        FluxApi.open(p, LIST);
    }

    private void invalidate(Player p) {
        Lists.invalidate(p, "donjons_liste");
        Lists.invalidate(p, "donjons_boutons");
    }

    /** {@code play <id>}. */
    public void act(Player p, String[] args) {
        if (args.length < 2) return;
        switch (args[0]) {
            case "play" -> {
                DungeonDefinition def = host.dungeon(args[1]);
                if (def == null) return;
                p.closeInventory();
                host.play(p, def, 0);
            }
            default -> {
            }
        }
    }

    /** {@code tl_current} (page of the dungeon to play), {@code tl_route_<page>} (colours of its path); null for another one. */
    public String placeholder(Player p, String params) {
        if (params.equals("tl_current")) return String.valueOf(currentPage(p));
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
     * before (on the first page, the start of the road: red while nothing is beaten, green after), then each place and the way to the next, the way out to the next page (when there
     * is one). A way takes the colour of the place it leads to.
     */
    private String route(Player p, int pageNumber) {
        int dungeons = line().size(), total = places(), k = climbed(p);
        int first = (pageNumber - 1) * PER_PAGE, last = Math.min(total, first + PER_PAGE) - 1;
        List<String> out = new ArrayList<>();
        // the way in: from the page before; on the first page the start of the road, red while nothing is beaten
        out.add(first > 0 ? state(first, k, dungeons) : k == 0 ? "rouge" : "vert");
        for (int i = first; i <= last; i++) {
            out.add(state(i, k, dungeons));
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

    /** Back, info, then class, party, tokens and shop above the line. */
    private List<ListEntry> buttons(Player viewer) {
        List<ListEntry> out = new ArrayList<>();
        out.add(button("close", Items.of("itemsadder:fluxui:btn_back_orange", raw("menu.close"),
                Lore.create().left(raw("menu.close-action")).build(), viewer), "[menu] main", ""));
        out.add(button("info", Items.of("itemsadder:fluxui:btn_info_blue", raw("menu.info"),
                Lore.create().description(raw("menu.info-desc")).description(raw("menu.info-keys"))
                        .description(raw("menu.info-rewards")).build(), viewer), "", ""));
        String line = host.classLine(viewer);
        out.add(button("class", Items.of("BOOK", line == null ? raw("menu.no-class") : line,
                Lore.create().description(raw("menu.class-desc")).left(raw("menu.class-action")).build(), viewer),
                "[close]", "[player] classe"));
        out.add(button("party", Items.of("PLAYER_HEAD", raw("menu.party"),
                Lore.create().description(host.partyLine(viewer)).description(raw("menu.party-desc"))
                        .left(raw("menu.party-action")).build(), viewer), "[close]", "[player] groupe"));
        Currency money = tokens.currency();
        Lore tokenLore = Lore.create().description(raw("menu.tokens-desc"))
                .kv(raw("menu.tokens-owned"), money == null ? "0" : money.format(money.balance(viewer)));
        boolean potions = potionsHere.test(viewer);
        if (potions) tokenLore.left(raw("menu.potions-action"));
        out.add(button("tokens", Items.of("itemsadder:fluxdonjon:jeton_donjon", raw("menu.tokens"), tokenLore.build(), viewer),
                potions ? "[close]" : "", potions ? "[player] potions" : ""));
        out.add(button("shop", Items.of("itemsadder:fluxdonjon:boutique", raw("menu.shop"),
                Lore.create().description(raw("menu.shop-desc")).left(raw("menu.shop-open")).build(), viewer),
                "[fluxdungeon] shop open", ""));
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
