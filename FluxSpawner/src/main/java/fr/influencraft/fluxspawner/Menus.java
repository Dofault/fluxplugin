package fr.influencraft.fluxspawner;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.inventory.Inventories;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.ListProvider;
import fr.influencraft.flux.api.list.ListQuery;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The spawner menus, built in Java and placed by the menu files:
 * <ul>
 *   <li>{@code spawners} (/spawners): the player's spawners, filter all / on / paused;</li>
 *   <li>{@code spawner}: one spawner (production, waiting mobs, fuel, next level, on / off, mob, pick up);</li>
 *   <li>{@code spawner_upgrade}: goals of the next level, level up; {@code spawner_levels}: every level;</li>
 *   <li>{@code spawner_fuel}: accepted fuels and the tank; {@code spawner_types}: change the mob.</li>
 * </ul>
 * Buttons run {@code [spawner] <verb> [argument]}.
 */
final class Menus {

    static final String LIST = "spawners_list";
    private static final List<String> LISTS = List.of("spawners_info", "spawners_filter", LIST, "spawner_info",
            "spawner_production", "spawner_stock", "spawner_fuel_button", "spawner_upgrade_button", "spawner_toggle",
            "spawner_type_button", "spawner_pickup", "spawner_next", "spawner_goals", "spawner_levelup",
            "spawner_levels_info", "spawner_levels", "spawner_tank", "spawner_fuels", "spawner_types_info", "spawner_types");
    private static final List<String> FILTERS = List.of("all", "active", "paused");

    private final FluxSpawner plugin;
    /** Spawner each player looks at. */
    private final Map<UUID, UUID> current = new HashMap<>();
    /** Whose spawners a staff member lists ({@code /fluxspawner list <player>}). */
    private final Map<UUID, UUID> listed = new HashMap<>();

    Menus(FluxSpawner plugin) {
        this.plugin = plugin;
    }

    private String raw(String key, String... vars) {
        return plugin.raw(key, vars);
    }

    void register() {
        Lists.registerItem(plugin, "spawners_info", this::listInfo);
        Lists.registerItem(plugin, "spawners_filter", this::filterButton);
        Lists.register(plugin, LIST, new ListProvider() {
            public List<ListEntry> entries(ListQuery q) {
                return listEntries(q.viewer(), q.filter());
            }

            public List<String> filters() {
                return FILTERS.stream().map(f -> raw("filters." + f)).toList();
            }
        });
        Lists.registerItem(plugin, "spawner_info", p -> with(p, this::overview));
        Lists.registerItem(plugin, "spawner_production", p -> with(p, this::production));
        Lists.registerItem(plugin, "spawner_stock", p -> with(p, this::stock));
        Lists.registerItem(plugin, "spawner_fuel_button", p -> with(p, this::fuelButton));
        Lists.registerItem(plugin, "spawner_upgrade_button", p -> with(p, this::upgradeButton));
        Lists.registerItem(plugin, "spawner_toggle", p -> with(p, this::toggleButton));
        Lists.registerItem(plugin, "spawner_type_button", p -> with(p, this::typeButton));
        Lists.registerItem(plugin, "spawner_pickup", p -> with(p, this::pickupButton));
        Lists.registerItem(plugin, "spawner_next", p -> with(p, this::nextLevel));
        Lists.register(plugin, "spawner_goals", q -> goalEntries(q.viewer()));
        Lists.registerItem(plugin, "spawner_levelup", p -> with(p, this::levelUpButton));
        Lists.registerItem(plugin, "spawner_levels_info", p -> with(p, this::levelsInfo));
        Lists.register(plugin, "spawner_levels", q -> levelEntries(q.viewer()));
        Lists.registerItem(plugin, "spawner_tank", p -> with(p, this::tank));
        Lists.register(plugin, "spawner_fuels", q -> fuelEntries(q.viewer()));
        Lists.registerItem(plugin, "spawner_types_info", p -> with(p, this::typesInfo));
        Lists.register(plugin, "spawner_types", q -> typeEntries(q.viewer()));
        FluxApi.registerAction(plugin, "spawner", (player, view, argument) -> act(player, argument.trim()));
    }

    void invalidate() {
        for (String id : LISTS) Lists.invalidate(id);
    }

    void forget(Player p) {
        current.remove(p.getUniqueId());
        listed.remove(p.getUniqueId());
    }

    /** Redraws the menu of everyone looking at this spawner (fuel, level, status changed). */
    void refresh(Spawner s) {
        invalidate();
        for (Map.Entry<UUID, UUID> e : current.entrySet()) {
            if (!e.getValue().equals(s.id)) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            MenuView v = p == null ? null : FluxApi.view(p);
            if (v != null && v.isOpen() && v.menuId().startsWith("spawner")) v.refresh();
        }
    }

    // ---------------------------------------------------------------- opening

    void open(Player p, Spawner s) {
        current.put(p.getUniqueId(), s.id);
        invalidate();
        FluxApi.open(p, "spawner");
    }

    void openList(Player p, UUID owner) {
        if (owner == null || owner.equals(p.getUniqueId())) listed.remove(p.getUniqueId());
        else listed.put(p.getUniqueId(), owner);
        Lists.reset(p, LIST);
        invalidate();
        FluxApi.open(p, "spawners");
    }

    private UUID listOwner(Player p) {
        return listed.getOrDefault(p.getUniqueId(), p.getUniqueId());
    }

    private Spawner current(Player p) {
        Spawner s = plugin.spawners().get(current.get(p.getUniqueId()));
        return s != null && s.placed() && plugin.mayManage(p, s) ? s : null;
    }

    private interface Builder {
        ItemStack build(Player p, Spawner s, SpawnerType type);
    }

    private ItemStack with(Player p, Builder b) {
        Spawner s = current(p);
        SpawnerType type = s == null ? null : plugin.settings().type(s.type);
        return type == null ? null : b.build(p, s, type);
    }

    // ---------------------------------------------------------------- actions

    private void act(Player p, String argument) {
        String[] parts = argument.split("\\s+", 2);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        if (verb.equals("list")) {
            FluxApi.open(p, "spawners");
            return;
        }
        if (verb.equals("open") || verb.equals("toggle-id")) {
            Spawner s;
            try {
                s = plugin.spawners().get(UUID.fromString(arg));
            } catch (IllegalArgumentException e) {
                return;
            }
            if (s == null || !s.placed() || !plugin.mayManage(p, s)) return;
            if (verb.equals("toggle-id")) {
                plugin.toggle(p, s);
                invalidate();
                MenuView v = FluxApi.view(p);
                if (v != null && v.isOpen()) v.refresh();
                return;
            }
            if (!plugin.settings().remoteManagement && !near(p, s) && !fr.influencraft.flux.api.staff.Staff.isAdmin(p)) {
                plugin.send(p, "too-far");
                Sounds.cue(p, "error");
                return;
            }
            open(p, s);
            return;
        }
        Spawner s = current(p);
        if (s == null) {
            p.closeInventory();
            return;
        }
        SpawnerType type = plugin.settings().type(s.type);
        if (type == null) return;
        Runnable back = () -> {
            if (p.isOnline()) open(p, s);
        };
        switch (verb) {
            case "back" -> open(p, s);
            case "upgrade-menu" -> {
                invalidate();
                FluxApi.open(p, "spawner_upgrade");
            }
            case "upgrade-back" -> {
                invalidate();
                FluxApi.open(p, "spawner_upgrade");
            }
            case "levels-menu" -> {
                invalidate();
                FluxApi.open(p, "spawner_levels");
            }
            case "fuel-menu" -> {
                if (!plugin.settings().fuelEnabled) return;
                invalidate();
                FluxApi.open(p, "spawner_fuel");
            }
            case "types-menu" -> {
                if (!plugin.settings().changeType) return;
                invalidate();
                FluxApi.open(p, "spawner_types");
            }
            case "toggle" -> {
                plugin.toggle(p, s);
                plugin.refresh(s);
            }
            case "levelup" -> plugin.upgrades().levelUp(p, s);
            case "give", "give-some" -> {
                plugin.upgrades().give(p, s, arg, verb.equals("give"), () -> {
                    if (p.isOnline()) {
                        invalidate();
                        FluxApi.open(p, "spawner_upgrade");
                    }
                });
                plugin.refresh(s);
            }
            case "fuel" -> {
                String[] a = arg.split("\\s+");
                if (a.length == 2) plugin.upgrades().fuel(p, s, a[0], a[1]);
            }
            case "type" -> plugin.upgrades().change(p, s, plugin.settings().type(arg), () -> {
                if (p.isOnline()) open(p, s);
            });
            case "pickup" -> {
                p.closeInventory();
                String question = raw("forms.pickup.body", plugin.vars(s, type));
                Dialogs.confirm(p, raw("forms.pickup.title"), question, pl -> {
                    Spawner now = plugin.spawners().get(s.id);
                    if (now == null || !now.placed() || !plugin.mayManage(pl, now)) return;
                    var block = now.block();
                    plugin.pickup(now, pl);
                    if (block != null) block.setType(Material.AIR);
                }, pl -> back.run());
            }
            default -> {
            }
        }
    }

    private static boolean near(Player p, Spawner s) {
        var c = s.centre();
        return c != null && c.getWorld().equals(p.getWorld()) && c.distanceSquared(p.getLocation()) <= 32 * 32;
    }

    // ---------------------------------------------------------------- shared pieces

    /** The spawner as an icon: its mob's egg, its name and level. */
    private ItemStack icon(SpawnerType type, String name, Lore lore, Player p) {
        return flags(Items.of(type.icon().name(), name, lore.build(), p));
    }

    private static ItemStack flags(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    private String bar(double value, double max) {
        return Format.bar(value, Math.max(1, max), 20, raw("menu.bar-filled"), raw("menu.bar-empty"));
    }

    private String name(Spawner s, SpawnerType type) {
        return raw("menu.spawner-name", plugin.vars(s, type));
    }

    // ---------------------------------------------------------------- the list

    private ItemStack listInfo(Player p) {
        UUID owner = listOwner(p);
        List<Spawner> mine = plugin.spawners().placedBy(owner);
        Player ownerOnline = Bukkit.getPlayer(owner);
        Lore lore = Lore.create().description(raw(owner.equals(p.getUniqueId()) ? "menu.list-description" : "menu.list-description-other",
                "%owner%", plugin.nameOf(owner)));
        int active = plugin.spawners().activeCount(owner);
        if (ownerOnline != null) {
            lore.kv(raw("menu.active"), active + " / " + limit(plugin.settings().activeLimit(ownerOnline)));
            lore.kv(raw("menu.placed"), mine.size() + " / " + limit(plugin.settings().placedLimit(ownerOnline)));
        } else {
            lore.kv(raw("menu.active"), active);
            lore.kv(raw("menu.placed"), mine.size());
        }
        return Items.of("SPAWNER", raw("menu.list-name"), lore.build(), p);
    }

    private String limit(int n) {
        return n == Integer.MAX_VALUE ? raw("unlimited") : String.valueOf(n);
    }

    private ItemStack filterButton(Player p) {
        List<String> labels = FILTERS.stream().map(f -> raw("filters." + f)).toList();
        int selected = Math.floorMod(Lists.state(p, LIST).filter(), labels.size());
        return Items.of("itemsadder:influitems:filter", raw("menu.filter"), Lore.create().filter(labels, selected).build(), p);
    }

    private List<ListEntry> listEntries(Player p, int filter) {
        String f = FILTERS.get(Math.floorMod(filter, FILTERS.size()));
        List<ListEntry> out = new ArrayList<>();
        for (Spawner s : plugin.spawners().placedBy(listOwner(p))) {
            if (f.equals("active") && !s.active) continue;
            if (f.equals("paused") && s.active) continue;
            SpawnerType type = plugin.settings().type(s.type);
            if (type == null) continue;
            String[] vars = plugin.vars(s, type);
            Lore lore = Lore.create();
            lore.kv(raw("menu.status"), raw("status." + s.status.name().toLowerCase(Locale.ROOT)));
            lore.kv(raw("menu.place"), raw("menu.place-value", vars));
            lore.kv(raw("menu.production"), raw("menu.production-value", vars));
            lore.kv(raw("menu.waiting"), raw("menu.waiting-value", vars));
            if (plugin.settings().fuelEnabled) lore.kv(raw("menu.fuel"), raw("menu.fuel-value", vars));
            lore.left(raw("menu.manage"));
            lore.right(raw(s.active ? "menu.turn-off" : "menu.turn-on"));
            out.add(ListEntry.of(s.id.toString()).item(icon(type, name(s, type), lore, p))
                    .set("left", "[spawner] open " + s.id).set("right", "[spawner] toggle-id " + s.id));
        }
        return out;
    }

    // ---------------------------------------------------------------- one spawner

    private ItemStack overview(Player p, Spawner s, SpawnerType type) {
        String[] vars = plugin.vars(s, type);
        Lore lore = Lore.create().description(raw("menu.spawner-description", vars));
        lore.kv(raw("menu.owner"), s.ownerName == null ? "?" : s.ownerName);
        lore.kv(raw("menu.status"), raw("status." + s.status.name().toLowerCase(Locale.ROOT)));
        lore.kv(raw("menu.place"), raw("menu.place-value", vars));
        lore.kv(raw("menu.kills"), Format.number(s.kills));
        lore.kv(raw("menu.vanilla-xp"), raw(type.vanillaXp() ? "menu.yes" : "menu.no"));
        return icon(type, name(s, type), lore, p);
    }

    private ItemStack production(Player p, Spawner s, SpawnerType type) {
        String[] vars = plugin.vars(s, type);
        Lore lore = Lore.create().description(raw("menu.production-description", vars));
        lore.kv(raw("menu.production"), raw("menu.production-value", vars));
        Level next = plugin.settings().next(type, s.level);
        if (next != null) {
            lore.kv(raw("menu.next-production"), raw("menu.production-number",
                    "%rate%", Format.number(Math.round(plugin.settings().mobsPerHour(type, next.number())))));
        }
        if (plugin.settings().activationRange > 0) {
            lore.info(raw("menu.activation", "%range%", String.valueOf(plugin.settings().activationRange)));
        }
        return Items.of("CLOCK", raw("menu.production-name"), lore.build(), p);
    }

    private ItemStack stock(Player p, Spawner s, SpawnerType type) {
        String[] vars = plugin.vars(s, type);
        Lore lore = Lore.create().description(raw("menu.waiting-description", vars));
        lore.kv(raw("menu.waiting"), raw("menu.waiting-value", vars));
        lore.line(bar(s.waiting, plugin.settings().capacity(type, s.level)));
        if (s.status == Spawner.Status.FULL) lore.ko(raw("menu.full"));
        return Items.of("CHEST", raw("menu.waiting-name"), lore.build(), p);
    }

    private ItemStack fuelButton(Player p, Spawner s, SpawnerType type) {
        String[] vars = plugin.vars(s, type);
        if (!plugin.settings().fuelEnabled) {
            return Items.of("BLAZE_POWDER", raw("menu.fuel-name"), Lore.create().description(raw("menu.fuel-off")).build(), p);
        }
        Lore lore = Lore.create().description(raw("menu.fuel-description", vars));
        lore.kv(raw("menu.fuel"), raw("menu.fuel-value", vars));
        lore.line(bar(s.fuel, plugin.settings().tankSeconds(type, s.level)));
        if (s.fuel <= 0) lore.ko(raw("menu.fuel-empty"));
        lore.left(raw("menu.refuel"));
        return Items.of("BLAZE_POWDER", raw("menu.fuel-name"), lore.build(), p);
    }

    private ItemStack upgradeButton(Player p, Spawner s, SpawnerType type) {
        Level next = plugin.settings().next(type, s.level);
        Lore lore = Lore.create();
        if (next == null) {
            lore.description(raw("menu.upgrade-max"));
            lore.ok(raw("menu.max-level"));
            return Items.of("NETHER_STAR", raw("menu.upgrade-name"), lore.build(), p);
        }
        List<Upgrades.Goal> goals = plugin.upgrades().goals(s, type);
        long done = goals.stream().filter(Upgrades.Goal::done).count();
        lore.description(raw("menu.upgrade-description", "%next%", String.valueOf(next.number())));
        lore.kv(raw("menu.goals"), done + " / " + goals.size());
        if (done == goals.size()) lore.ok(raw("menu.upgrade-ready"));
        lore.left(raw("menu.see-goals"));
        return Items.of("EXPERIENCE_BOTTLE", raw("menu.upgrade-name"), lore.build(), p);
    }

    private ItemStack toggleButton(Player p, Spawner s, SpawnerType type) {
        Lore lore = Lore.create().description(raw(s.active ? "menu.toggle-on-description" : "menu.toggle-off-description"));
        Player owner = Bukkit.getPlayer(s.owner);
        if (owner != null) {
            lore.kv(raw("menu.active"), plugin.spawners().activeCount(s.owner) + " / " + limit(plugin.settings().activeLimit(owner)));
        }
        lore.left(raw(s.active ? "menu.turn-off" : "menu.turn-on"));
        return Items.of(s.active ? "LIME_DYE" : "GRAY_DYE", raw(s.active ? "menu.toggle-on" : "menu.toggle-off"), lore.build(), p);
    }

    private ItemStack typeButton(Player p, Spawner s, SpawnerType type) {
        Lore lore = Lore.create();
        if (!plugin.settings().changeType) {
            lore.description(raw("menu.type-off"));
        } else {
            lore.description(raw("menu.type-description"));
            lore.kv(raw("menu.mob"), type.displayName());
            lore.left(raw("menu.choose-mob"));
        }
        return Items.of(type.icon().name(), raw("menu.type-name"), lore.build(), p);
    }

    private ItemStack pickupButton(Player p, Spawner s, SpawnerType type) {
        Lore lore = Lore.create().description(raw("menu.pickup-description", plugin.vars(s, type)));
        lore.left(raw("menu.pickup"));
        return Items.of("itemsadder:influitems:chest_delete", raw("menu.pickup-name"), lore.build(), p);
    }

    // ---------------------------------------------------------------- next level

    private ItemStack nextLevel(Player p, Spawner s, SpawnerType type) {
        Level next = plugin.settings().next(type, s.level);
        Lore lore = Lore.create();
        if (next == null) {
            lore.description(raw("menu.upgrade-max"));
            return icon(type, name(s, type), lore, p);
        }
        Settings set = plugin.settings();
        lore.description(raw("menu.next-description", "%next%", String.valueOf(next.number())));
        lore.kv(raw("menu.production"), raw("menu.change", "%from%", Format.number(Math.round(set.mobsPerHour(type, s.level))),
                "%to%", Format.number(Math.round(set.mobsPerHour(type, next.number()))) + " " + raw("units.per-hour")));
        lore.kv(raw("menu.capacity"), raw("menu.change", "%from%", Format.number(set.capacity(type, s.level)),
                "%to%", Format.number(set.capacity(type, next.number()))));
        if (set.fuelEnabled) {
            lore.kv(raw("menu.tank"), raw("menu.change", "%from%", Production.time(set.tankSeconds(type, s.level)),
                    "%to%", Production.time(set.tankSeconds(type, next.number()))));
        }
        return icon(type, raw("menu.next-name", "%next%", String.valueOf(next.number())), lore, p);
    }

    private List<ListEntry> goalEntries(Player p) {
        Spawner s = current(p);
        SpawnerType type = s == null ? null : plugin.settings().type(s.type);
        if (type == null) return List.of();
        List<ListEntry> out = new ArrayList<>();
        for (Upgrades.Goal g : plugin.upgrades().goals(s, type)) {
            Requirement r = g.requirement();
            String kind = r.kind().name().toLowerCase(Locale.ROOT);
            String what = switch (r.kind()) {
                case ITEM -> plugin.upgrades().itemName(r.resolvedItem(type));
                case CURRENCY -> fr.influencraft.flux.api.economy.Economy.name(r.currency());
                default -> "";
            };
            Lore lore = Lore.create().description(raw("goals." + kind + ".description", "%item%", what, "%type%", type.displayName()));
            lore.kv(raw("menu.progress"), amount(r, g.given()) + " / " + amount(r, g.required()));
            lore.line(bar(g.given(), g.required()));
            String left = "[sound] @click", right = "[sound] @click";
            if (g.done()) {
                lore.ok(raw("menu.goal-done"));
            } else {
                switch (r.kind()) {
                    case KILLS -> lore.info(raw("goals.kills.hint"));
                    case XP -> {
                        lore.kv(raw("menu.you-have"), Format.number(fr.influencraft.flux.api.util.Experience.total(p)) + " " + raw("units.xp"));
                        lore.left(raw("goals.xp.all"));
                        lore.right(raw("goals.xp.one"));
                        left = "[spawner] give " + g.key();
                        right = "[spawner] give-some " + g.key();
                    }
                    case ITEM -> {
                        lore.kv(raw("menu.you-have"), Format.number(Inventories.count(p, Upgrades.matcher(r.resolvedItem(type)))));
                        lore.left(raw("goals.item.all"));
                        lore.right(raw("goals.item.stack"));
                        left = "[spawner] give " + g.key();
                        right = "[spawner] give-some " + g.key();
                    }
                    case MONEY, CURRENCY -> {
                        lore.left(raw("goals.money.give"));
                        left = "[spawner] give " + g.key();
                    }
                }
            }
            out.add(ListEntry.of(g.key()).item(flags(goalIcon(r, type, raw("goals." + kind + ".name", "%item%", what), lore, p)))
                    .set("left", left).set("right", right));
        }
        return out;
    }

    private String amount(Requirement r, long n) {
        return switch (r.kind()) {
            case MONEY -> plugin.money().format(n);
            case CURRENCY -> fr.influencraft.flux.api.economy.Economy.format(r.currency(), n);
            default -> Format.number(n);
        };
    }

    private ItemStack goalIcon(Requirement r, SpawnerType type, String name, Lore lore, Player p) {
        String material = switch (r.kind()) {
            case KILLS -> "IRON_SWORD";
            case XP -> "EXPERIENCE_BOTTLE";
            case MONEY -> "itemsadder:influitems:bitblock";
            case CURRENCY -> r.currency().equals("influence") ? "itemsadder:influitems:influence" : "GOLD_NUGGET";
            case ITEM -> null;
        };
        if (material != null) return Items.of(material, name, lore.build(), p);
        String item = r.resolvedItem(type);
        ItemStack stack = item.contains(":") ? CustomItems.item(item) : null;
        if (stack == null) {
            Material m = Material.matchMaterial(item);
            stack = new ItemStack(m == null ? Material.BARRIER : m);
        }
        return Items.dress(stack, name, lore.build(), p);
    }

    private ItemStack levelUpButton(Player p, Spawner s, SpawnerType type) {
        Level next = plugin.settings().next(type, s.level);
        Lore lore = Lore.create();
        if (next == null) {
            lore.ok(raw("menu.max-level"));
            return Items.of("NETHER_STAR", raw("menu.levelup-name"), lore.build(), p);
        }
        boolean ready = plugin.upgrades().ready(s, type);
        lore.description(raw("menu.levelup-description", "%next%", String.valueOf(next.number())));
        if (ready) lore.left(raw("menu.levelup"));
        else lore.ko(raw("menu.goals-missing"));
        return Items.of(ready ? "itemsadder:influitems:accept" : "itemsadder:influitems:lock", raw("menu.levelup-name"), lore.build(), p);
    }

    // ---------------------------------------------------------------- levels

    private ItemStack levelsInfo(Player p, Spawner s, SpawnerType type) {
        Lore lore = Lore.create().description(raw("menu.levels-description"));
        lore.kv(raw("menu.level"), s.level + " / " + plugin.settings().maxLevel(type));
        return icon(type, raw("menu.levels-name"), lore, p);
    }

    private List<ListEntry> levelEntries(Player p) {
        Spawner s = current(p);
        SpawnerType type = s == null ? null : plugin.settings().type(s.type);
        if (type == null) return List.of();
        Settings set = plugin.settings();
        List<ListEntry> out = new ArrayList<>();
        for (Level l : set.levels(type).values()) {
            Lore lore = Lore.create();
            lore.kv(raw("menu.production"), Format.number(Math.round(set.mobsPerHour(type, l.number()))) + " " + raw("units.per-hour"));
            lore.kv(raw("menu.capacity"), Format.number(set.capacity(type, l.number())));
            if (set.fuelEnabled) lore.kv(raw("menu.tank"), Production.time(set.tankSeconds(type, l.number())));
            if (l.number() < s.level) lore.ok(raw("menu.level-reached"));
            else if (l.number() == s.level) lore.info(raw("menu.level-current"));
            String material = l.number() <= s.level ? "EXPERIENCE_BOTTLE" : "GLASS_BOTTLE";
            ItemStack item = Items.of(material, raw("menu.level-name", "%level%", String.valueOf(l.number())), lore.build(), p);
            item.setAmount(Math.max(1, Math.min(64, l.number())));
            out.add(ListEntry.of(String.valueOf(l.number())).item(flags(item)).set("clic", "[sound] @click"));
        }
        return out;
    }

    // ---------------------------------------------------------------- fuel

    private ItemStack tank(Player p, Spawner s, SpawnerType type) {
        String[] vars = plugin.vars(s, type);
        Lore lore = Lore.create().description(raw("menu.tank-description", vars));
        lore.kv(raw("menu.fuel"), raw("menu.fuel-value", vars));
        lore.line(bar(s.fuel, plugin.settings().tankSeconds(type, s.level)));
        lore.info(raw("menu.fuel-burns"));
        return Items.of("BLAZE_POWDER", raw("menu.tank-name"), lore.build(), p);
    }

    private List<ListEntry> fuelEntries(Player p) {
        Spawner s = current(p);
        SpawnerType type = s == null ? null : plugin.settings().type(s.type);
        if (type == null) return List.of();
        List<ListEntry> out = new ArrayList<>();
        for (String fuel : plugin.settings().fuels(type)) {
            double per = plugin.settings().fuelSeconds(fuel, type, s.level);
            if (per <= 0) continue;
            ItemStack stack = fuel.contains(":") ? CustomItems.item(fuel) : null;
            if (stack == null) {
                Material m = Material.matchMaterial(fuel);
                if (m == null) continue;
                stack = new ItemStack(m);
            }
            Lore lore = Lore.create();
            lore.kv(raw("menu.fuel-each"), Production.time(per));
            lore.kv(raw("menu.you-have"), Format.number(Inventories.count(p, Upgrades.matcher(fuel))));
            lore.left(raw("menu.fuel-one"));
            lore.right(raw("menu.fuel-stack"));
            lore.shiftLeft(raw("menu.fuel-all"));
            String name = plugin.upgrades().itemName(fuel);
            out.add(ListEntry.of(fuel).item(flags(Items.dress(stack, name, lore.build(), p)))
                    .set("left", "[spawner] fuel " + fuel + " one").set("right", "[spawner] fuel " + fuel + " stack")
                    .set("shift_left", "[spawner] fuel " + fuel + " all"));
        }
        return out;
    }

    // ---------------------------------------------------------------- types

    private ItemStack typesInfo(Player p, Spawner s, SpawnerType type) {
        Lore lore = Lore.create().description(raw("menu.types-description"));
        lore.kv(raw("menu.mob"), type.displayName());
        return Items.of(type.icon().name(), raw("menu.types-name"), lore.build(), p);
    }

    private List<ListEntry> typeEntries(Player p) {
        Spawner s = current(p);
        SpawnerType now = s == null ? null : plugin.settings().type(s.type);
        if (now == null) return List.of();
        List<ListEntry> out = new ArrayList<>();
        for (SpawnerType t : plugin.settings().types.values()) {
            Lore lore = Lore.create();
            Settings set = plugin.settings();
            int level = Math.min(s.level, set.maxLevel(t));
            lore.kv(raw("menu.production"), Format.number(Math.round(set.mobsPerHour(t, level))) + " " + raw("units.per-hour"));
            lore.kv(raw("menu.vanilla-xp"), raw(t.vanillaXp() ? "menu.yes" : "menu.no"));
            String action = "[sound] @error";
            if (t == now) {
                lore.info(raw("menu.type-current"));
                action = "[sound] @click";
            } else if (t.permission() != null && !p.hasPermission(t.permission())) {
                lore.ko(raw("menu.type-locked"));
            } else {
                double price = set.changePrice(t);
                lore.kv(raw("menu.price"), price > 0 ? plugin.money().format(price) : raw("free"));
                lore.left(raw("menu.choose"));
                action = "[spawner] type " + t.id();
            }
            out.add(ListEntry.of(t.id()).item(flags(Items.of(t.icon().name(), t.displayName(), lore.build(), p))).set("clic", action));
        }
        return out;
    }
}
