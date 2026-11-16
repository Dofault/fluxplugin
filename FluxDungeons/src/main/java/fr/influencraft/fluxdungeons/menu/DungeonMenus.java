package fr.influencraft.fluxdungeons.menu;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.pets.Companions;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
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
 * The dungeon menus of /donjon (2026-11-14, files written by {@code assets/menus/gen_menus.py}):
 * <ul>
 *   <li>{@code donjons}: the dungeon tower (2026-11-16): a road climbing from floor 1 (level 10) at the bottom to
 *   floor 10 (level 100), a "?" above it (the tower goes on). Each floor shows its boss, its level, the party it is
 *   made for and whether the player beat it (glint, and a lit cell drawn by Flux through {@code won_<floor>}); a
 *   left click shows the dungeon, a right click its page of the shop. Lists {@code donjons_liste} (one entry per
 *   floor, in floor order), {@code donjons_boutons} (back, the "?", info, class, party, tokens, shop);</li>
 *   <li>{@code donjon}: the dungeon chosen with {@code [fluxdungeon] view <id>}: its boss (picture of its page), what
 *   can be won ({@code donjon_butin}), a button per difficulty to play it ({@code donjon_niveaux},
 *   {@code [fluxdungeon] play <level>}), the key ({@code donjon_boutons}).</li>
 * </ul>
 * Buttons are built here, the files only place them; placeholder {@code selected} (dungeon shown).
 */
public final class DungeonMenus {

    public static final String LIST = "donjons", DETAIL = "donjon";
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

        /** Opens the dungeon shop on a dungeon's page (null: the last one). */
        void shop(Player player, String dungeon);

        /** The player plays the dungeon at this difficulty (index). */
        void play(Player player, DungeonDefinition def, int level);
    }

    private final FluxPlugin plugin;
    private final Host host;
    private final DungeonKeys keys;
    private final DungeonTokens tokens;
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();
    private java.util.function.Predicate<Player> potionsHere = p -> false;

    public DungeonMenus(FluxPlugin plugin, Host host, DungeonKeys keys, DungeonTokens tokens) {
        this.plugin = plugin;
        this.host = host;
        this.keys = keys;
        this.tokens = tokens;
        Lists.register(plugin, "donjons_liste", q -> list(q.viewer()));
        Lists.register(plugin, "donjons_boutons", q -> listButtons(q.viewer()));
        Lists.register(plugin, "donjon_boutons", q -> detailButtons(q.viewer()));
        Lists.register(plugin, "donjon_butin", q -> loot(q.viewer()));
    }

    public void open(Player p) {
        Lists.invalidate(p, "donjons_liste");
        Lists.invalidate(p, "donjons_boutons");
        FluxApi.open(p, LIST);
    }

    /** {@code view <id>}, {@code play <level>}. */
    public void act(Player p, String[] args) {
        if (args.length < 2) return;
        switch (args[0]) {
            case "view" -> {
                if (host.dungeon(args[1]) == null) return;
                selected.put(p.getUniqueId(), args[1]);
                for (String l : List.of("donjon_boutons", "donjon_butin")) Lists.invalidate(p, l);
            }
            case "play" -> {
                DungeonDefinition def = host.dungeon(selected.getOrDefault(p.getUniqueId(), ""));
                if (def == null) return;
                int level;
                try {
                    level = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    return;
                }
                p.closeInventory();
                host.play(p, def, level);
            }
            default -> {
            }
        }
    }

    /** {@code selected}, {@code selected_name}, {@code won_<floor>} (yes / no); null for another one. */
    public String placeholder(Player p, String params) {
        if (params.equals("selected")) return selected.getOrDefault(p.getUniqueId(), "");
        if (params.equals("selected_name")) {
            DungeonDefinition def = host.dungeon(selected.getOrDefault(p.getUniqueId(), ""));
            return def == null ? "" : Texts.plain(Texts.colors(def.name()));
        }
        if (params.startsWith("won_")) {
            var v = host.victories();
            for (DungeonDefinition d : host.dungeons()) {
                if (String.valueOf(d.floor()).equals(params.substring(4))) return v != null && v.beaten(p, d.id()) ? "yes" : "no";
            }
            return "no";
        }
        return null;
    }

    /** Where the potion merchant can be opened from the tokens button (the rpg universe). */
    public void potions(java.util.function.Predicate<Player> here) {
        this.potionsHere = here;
    }

    // ---------------------------------------------------------------- the tower

    /** The floors of the tower, from the bottom. */
    private List<DungeonDefinition> tower() {
        List<DungeonDefinition> out = new ArrayList<>();
        for (DungeonDefinition d : host.dungeons()) if (d.floor() > 0 && !d.pet().isEmpty()) out.add(d);
        out.sort(java.util.Comparator.comparingInt(DungeonDefinition::floor));
        return out;
    }

    private List<ListEntry> list(Player viewer) {
        List<ListEntry> out = new ArrayList<>();
        var victories = host.victories();
        int level = levelOf(viewer);
        for (DungeonDefinition def : tower()) {
            int wins = victories == null ? 0 : victories.wins(viewer, def.id());
            Lore lore = Lore.create().description(def.description())
                    .kv(raw("menu.floor"), def.floor())
                    .kv(raw("menu.level"), def.recommendedLevel())
                    .kv(raw("menu.players-recommended"), def.recommendedPlayers() <= 1 ? raw("menu.solo")
                            : raw("menu.party-size", "%count%", String.valueOf(def.recommendedPlayers())))
                    .kv(raw("menu.job"), keys.jobName(def.job()));
            if (keys.needs(viewer, def)) lore.kv(raw("menu.keys"), String.valueOf(keys.count(viewer, def.job())));
            if (wins > 0) lore.ok(raw("menu.beaten", "%count%", String.valueOf(wins)));
            else if (level >= 0 && level < Math.min(def.recommendedLevel(), 50)) lore.ko(raw("menu.too-high"));
            else lore.info(raw("menu.not-beaten"));
            if (def.recommendedLevel() > 50) lore.info(raw("menu.beyond-cap"));
            lore.left(raw("menu.see")).right(raw("menu.shop-action"));
            ItemStack item = Items.of(PORTRAIT + def.pet(), def.name(), lore.build(), viewer);
            if (item == null) item = Items.dress(new ItemStack(def.icon()), def.name(), lore.build(), viewer);
            if (wins > 0) item.editMeta(m -> m.setEnchantmentGlintOverride(true));
            out.add(ListEntry.of(def.id()).item(item).set("right", "[fluxdungeon] shop open " + def.id()));
        }
        return out;
    }

    private List<ListEntry> listButtons(Player viewer) {
        List<ListEntry> out = new ArrayList<>();
        out.add(button("close", Items.of("itemsadder:fluxui:btn_back_orange", raw("menu.close"),
                Lore.create().left(raw("menu.close-action")).build(), viewer), "[menu] main", ""));
        out.add(button("unknown", Items.of("itemsadder:fluxdonjon:etage_inconnu", raw("menu.unknown"),
                Lore.create().description(raw("menu.unknown-desc")).build(), viewer), "", ""));
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

    // ---------------------------------------------------------------- detail

    private DungeonDefinition shown(Player viewer) {
        return host.dungeon(selected.getOrDefault(viewer.getUniqueId(), ""));
    }

    private List<ListEntry> detailButtons(Player viewer) {
        DungeonDefinition def = shown(viewer);
        List<ListEntry> out = new ArrayList<>();
        out.add(button("back", Items.of("itemsadder:fluxui:btn_back_orange", raw("menu.back"),
                Lore.create().left(raw("menu.back-action")).build(), viewer), "[menu] " + LIST, ""));
        if (def == null) return out;
        out.add(button("info", Items.of("itemsadder:fluxui:btn_info_blue", def.name(),
                Lore.create().description(def.description())
                        .kv(raw("menu.players"), def.minPlayers() + "-" + def.maxPlayers())
                        .kv(raw("menu.level"), def.recommendedLevel()).build(), viewer), "", ""));
        Lore key = Lore.create().description(raw("menu.key-desc", "%job%", keys.jobName(def.job())));
        if (keys.needs(viewer, def)) {
            int n = keys.count(viewer, def.job());
            key.kv(raw("menu.keys"), String.valueOf(n));
            if (n > 0) key.ok(raw("menu.key-ok"));
            else key.ko(raw("menu.key-missing"));
        } else key.info(raw("menu.key-free"));
        key.left(raw("menu.key-action"));
        ItemStack keyItem = Items.of("itemsadder:fluxdonjon:cle_donjon", raw("menu.key", "%job%", keys.jobName(def.job())),
                key.build(), viewer);
        if (keyItem != null) keyItem.setAmount(Math.max(1, Math.min(64, keys.count(viewer, def.job()))));
        // the launch screen, at the easiest level: the difficulty is chosen there
        out.add(button("key", keyItem, "[fluxdungeon] play 0", ""));
        out.add(button("shop", Items.of("itemsadder:fluxdonjon:boutique", raw("menu.shop"),
                Lore.create().description(raw("menu.shop-desc")).left(raw("menu.shop-open")).build(), viewer),
                "[fluxdungeon] shop open " + def.id(), ""));
        return out;
    }

    /**
     * What the dungeon gives (2026-11-16): tokens only, by monster rank and the dungeon's reward, plus rpg experience;
     * what tokens buy on its page of the shop: the boss's mount (once beaten), boosters, books, its set.
     */
    private List<ListEntry> loot(Player viewer) {
        DungeonDefinition def = shown(viewer);
        List<ListEntry> out = new ArrayList<>();
        if (def == null) return out;
        DungeonDefinition.Level level = def.levels().get(0);
        Lore tokenLore = Lore.create().description(raw("loot.tokens-desc"));
        for (String rank : List.of("TRASH", "ELITE", "MINIBOSS", "BOSS")) {
            tokenLore.kv(raw("loot.rank." + rank.toLowerCase(java.util.Locale.ROOT)), tokens.base(rank));
        }
        tokenLore.kv(raw("loot.tokens-bonus"), "x" + Format.decimal(level.reward()));
        out.add(ListEntry.of("tokens").item(Items.of("itemsadder:fluxdonjon:jeton_donjon", raw("menu.tokens"), tokenLore.build(), viewer)).set("left", ""));
        if (!def.pet().isEmpty()) {
            boolean owned = Companions.owns(viewer, def.pet());
            var v = host.victories();
            Lore lore = Lore.create().description(raw("loot.pet-desc")).description(raw("loot.pet-mount"));
            if (owned) lore.ok(raw("loot.pet-owned"));
            else if (v != null && v.beaten(viewer, def.id())) lore.ok(raw("loot.pet-unlocked"));
            else lore.ko(raw("loot.pet-locked"));
            lore.left(raw("menu.shop-open"));
            String name = Companions.name(def.pet());
            ItemStack pet = Items.of(PORTRAIT + def.pet(), name == null ? def.pet() : name, lore.build(), viewer);
            out.add(ListEntry.of("pet").item(pet == null ? new ItemStack(Material.EGG) : pet).set("left", "[fluxdungeon] shop open " + def.id()));
        }
        if (!def.job().isEmpty()) {
            ItemStack booster = CustomItems.item("fluxpets:booster_metier_" + def.job(), viewer);
            if (booster != null) {
                String name = booster.hasItemMeta() && booster.getItemMeta().hasDisplayName()
                        ? Texts.ampersand(booster.getItemMeta().displayName()) : raw("loot.boosters");
                out.add(ListEntry.of("boosters").item(Items.dress(booster.clone(), name,
                        Lore.create().description(raw("loot.shop-desc", "%job%", keys.jobName(def.job()))).left(raw("menu.shop-open")).build(), viewer))
                        .set("left", "[fluxdungeon] shop open " + def.id()));
            }
        }
        return out;
    }

    private static ListEntry button(String id, ItemStack item, String left, String left2) {
        return ListEntry.of(id).item(item == null ? new ItemStack(Material.BARRIER) : item).set("left", left).set("left2", left2);
    }

    private String raw(String key, String... vars) {
        return plugin.messages().raw(key, vars);
    }
}
