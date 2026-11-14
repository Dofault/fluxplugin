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
 *   <li>{@code donjons}: the boss of each dungeon (its pet's portrait), click to see it. Lists
 *   {@code donjons_liste}, {@code donjons_boutons} (close, info, class, party, tokens);</li>
 *   <li>{@code donjon}: the dungeon chosen with {@code [fluxdungeon] view <id>}: its boss (picture of its page), what
 *   can be won ({@code donjon_butin}), a button per difficulty to play it ({@code donjon_niveaux},
 *   {@code [fluxdungeon] play <level>}), the key ({@code donjon_boutons}).</li>
 * </ul>
 * Buttons are built here, the files only place them; placeholder {@code selected} (dungeon shown).
 */
public final class DungeonMenus {

    public static final String LIST = "donjons", DETAIL = "donjon";
    private static final String PORTRAIT = "itemsadder:influportraits:portrait_";
    private static final String[] LEVEL_ICONS = {"itemsadder:fluxui:btn_right_green", "itemsadder:fluxui:btn_right_gold",
            "itemsadder:fluxui:btn_right_red"};

    /** What the menus need from the plugin. */
    public interface Host {
        Collection<DungeonDefinition> dungeons();

        DungeonDefinition dungeon(String id);

        /** Class line of the player ("Pyromancien 12"), null without a class. */
        String classLine(Player player);

        /** Party line ("Seul", "Groupe de 3"). */
        String partyLine(Player player);

        /** Money of a victory at this level, rpg experience of a victory. */
        double victoryMoney(DungeonDefinition def, int level);

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
        Lists.register(plugin, "donjon_niveaux", q -> levels(q.viewer()));
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
                for (String l : List.of("donjon_boutons", "donjon_butin", "donjon_niveaux")) Lists.invalidate(p, l);
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

    /** {@code selected}, {@code selected_name}; null for another one. */
    public String placeholder(Player p, String params) {
        if (params.equals("selected")) return selected.getOrDefault(p.getUniqueId(), "");
        if (params.equals("selected_name")) {
            DungeonDefinition def = host.dungeon(selected.getOrDefault(p.getUniqueId(), ""));
            return def == null ? "" : Texts.plain(Texts.colors(def.name()));
        }
        return null;
    }

    /** Where the potion merchant can be opened from the tokens button (the rpg universe). */
    public void potions(java.util.function.Predicate<Player> here) {
        this.potionsHere = here;
    }

    // ---------------------------------------------------------------- list

    private List<ListEntry> list(Player viewer) {
        List<ListEntry> out = new ArrayList<>();
        for (DungeonDefinition def : host.dungeons()) {
            if (def.pet().isEmpty()) continue;  // tests and imports without a boss pet are not listed
            Lore lore = Lore.create().description(def.description())
                    .kv(raw("menu.job"), keys.jobName(def.job()))
                    .kv(raw("menu.players"), def.minPlayers() + "-" + def.maxPlayers())
                    .kv(raw("menu.level"), def.recommendedLevel());
            if (keys.needs(viewer, def)) lore.kv(raw("menu.keys"), String.valueOf(keys.count(viewer, def.job())));
            if (Companions.owns(viewer, def.pet())) lore.ok(raw("menu.pet-owned"));
            lore.left(raw("menu.see"));
            ItemStack item = Items.of(PORTRAIT + def.pet(), def.name(), lore.build(), viewer);
            out.add(ListEntry.of(def.id()).item(item == null ? new ItemStack(def.icon()) : item));
        }
        return out;
    }

    private List<ListEntry> listButtons(Player viewer) {
        List<ListEntry> out = new ArrayList<>();
        out.add(button("close", Items.of("itemsadder:fluxui:btn_back_orange", raw("menu.close"),
                Lore.create().left(raw("menu.close-action")).build(), viewer), "[close]", ""));
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
        boolean shop = potionsHere.test(viewer);
        if (shop) tokenLore.left(raw("menu.potions-action"));
        out.add(button("tokens", Items.of("itemsadder:fluxdonjon:jeton_donjon", raw("menu.tokens"), tokenLore.build(), viewer),
                shop ? "[close]" : "", shop ? "[player] potions" : ""));
        return out;
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
        ItemStack keyItem = Items.of("itemsadder:fluxdonjon:cle_donjon", raw("menu.key", "%job%", keys.jobName(def.job())),
                key.build(), viewer);
        if (keyItem != null) keyItem.setAmount(Math.max(1, Math.min(64, keys.count(viewer, def.job()))));
        out.add(button("key", keyItem, "", ""));
        Currency money = tokens.currency();
        out.add(button("tokens", Items.of("itemsadder:fluxdonjon:jeton_donjon", raw("menu.tokens"),
                Lore.create().description(raw("menu.tokens-desc"))
                        .kv(raw("menu.tokens-owned"), money == null ? "0" : money.format(money.balance(viewer))).build(), viewer), "", ""));
        return out;
    }

    /** What can be won: the boss pet, the boosters, tokens, money and experience, the rpg loot with its chance. */
    private List<ListEntry> loot(Player viewer) {
        DungeonDefinition def = shown(viewer);
        List<ListEntry> out = new ArrayList<>();
        if (def == null) return out;
        if (!def.pet().isEmpty()) {
            boolean owned = Companions.owns(viewer, def.pet());
            Lore lore = Lore.create().description(raw("loot.pet-desc"))
                    .kv(raw("loot.rarity"), "&r&f%img_legendary%").description(raw("loot.pet-mount"));
            if (owned) lore.ok(raw("loot.pet-owned"));
            String name = Companions.name(def.pet());
            ItemStack pet = Items.of(PORTRAIT + def.pet(), name == null ? def.pet() : name, lore.build(), viewer);
            out.add(ListEntry.of("pet").item(pet == null ? new ItemStack(Material.EGG) : pet));
        }
        if (!def.job().isEmpty()) {
            ItemStack booster = CustomItems.item("fluxpets:booster_metier_" + def.job(), viewer);
            if (booster != null) {
                Lore lore = Lore.create().description(raw("loot.boosters-desc"));
                for (int i = 0; i < def.levels().size(); i++) {
                    lore.kv(def.levels().get(i).name(), "x" + def.boosters(i));
                }
                String name = booster.hasItemMeta() && booster.getItemMeta().hasDisplayName()
                        ? Texts.ampersand(booster.getItemMeta().displayName()) : raw("loot.boosters");
                out.add(ListEntry.of("boosters").item(Items.dress(booster.clone(), name, lore.build(), viewer)));
            }
        }
        Lore tokenLore = Lore.create().description(raw("loot.tokens-desc"));
        for (String rank : List.of("TRASH", "ELITE", "MINIBOSS", "BOSS")) {
            tokenLore.kv(raw("loot.rank." + rank.toLowerCase(java.util.Locale.ROOT)), String.valueOf(tokens.base(rank)));
        }
        tokenLore.info(raw("loot.tokens-levels"));
        out.add(ListEntry.of("tokens").item(Items.of("itemsadder:fluxdonjon:jeton_donjon", raw("menu.tokens"), tokenLore.build(), viewer)));
        Lore moneyLore = Lore.create().description(raw("loot.money-desc"));
        for (int i = 0; i < def.levels().size(); i++) {
            Currency main = Economy.main();
            double amount = host.victoryMoney(def, i);
            moneyLore.kv(def.levels().get(i).name(), main == null ? Format.decimal(amount) : main.format(amount));
        }
        out.add(ListEntry.of("money").item(Items.of("itemsadder:influitems:bitblock", raw("loot.money"), moneyLore.build(), viewer)));
        for (DungeonDefinition.Loot drop : def.loot()) {
            ItemStack item = CustomItems.item(drop.item(), viewer);
            if (item == null) continue;
            ItemStack shown = item.clone();
            Lore extra = Lore.create().kv(raw("loot.chance"), Format.decimal(drop.chance() * 100) + " %");
            List<String> lore = new ArrayList<>(shown.hasItemMeta() && shown.getItemMeta().hasLore()
                    ? shown.getItemMeta().lore().stream().map(Texts::ampersand).toList() : List.of());
            lore.addAll(extra.build());
            String name = shown.hasItemMeta() && shown.getItemMeta().hasDisplayName()
                    ? Texts.ampersand(shown.getItemMeta().displayName()) : null;
            out.add(ListEntry.of("loot_" + out.size()).item(Items.dress(shown, name, lore, viewer)));
        }
        return out;
    }

    private List<ListEntry> levels(Player viewer) {
        DungeonDefinition def = shown(viewer);
        List<ListEntry> out = new ArrayList<>();
        if (def == null) return out;
        List<DungeonDefinition.Level> levels = def.levels();
        boolean needsKey = keys.needs(viewer, def);
        int owned = keys.count(viewer, def.job());
        for (int i = 0; i < Math.min(3, levels.size()); i++) {
            DungeonDefinition.Level l = levels.get(i);
            Lore lore = Lore.create().description(raw("menu.level-desc"))
                    .kv(raw("menu.level-health"), "x" + Format.decimal(l.health()))
                    .kv(raw("menu.level-damage"), "x" + Format.decimal(l.damage()))
                    .kv(raw("menu.level-spells"), String.valueOf(l.abilities()))
                    .kv(raw("menu.level-boosters"), "x" + def.boosters(i));
            if (needsKey) {
                if (owned > 0) lore.info(raw("menu.level-key", "%job%", keys.jobName(def.job())));
                else lore.ko(raw("menu.key-missing"));
            }
            lore.left(raw("menu.level-play"));
            ItemStack item = Items.of(LEVEL_ICONS[Math.min(i, LEVEL_ICONS.length - 1)],
                    raw("menu.level-name", "%level%", l.name()), lore.build(), viewer);
            out.add(ListEntry.of(String.valueOf(i)).item(item));
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
