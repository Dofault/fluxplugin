package fr.influencraft.fluxspawner;

import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.flux.api.inventory.Inventories;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.music.Music;
import fr.influencraft.flux.api.util.Experience;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * What players do to a spawner: give towards its next level (experience, money, items; kills come by themselves),
 * level it up, fill its fuel tank, turn it into another mob. Every amount is worked out by {@link Settings}.
 */
final class Upgrades {

    /** A goal of the next level for one spawner: what it asks and what was given. */
    record Goal(Requirement requirement, String key, long required, long given) {
        boolean done() {
            return given >= required;
        }

        long missing() {
            return Math.max(0, required - given);
        }
    }

    private final FluxSpawner plugin;

    Upgrades(FluxSpawner plugin) {
        this.plugin = plugin;
    }

    /** Goals to leave the spawner's level, empty at the top. */
    List<Goal> goals(Spawner s, SpawnerType type) {
        Level next = plugin.settings().next(type, s.level);
        if (next == null) return List.of();
        List<Goal> out = new ArrayList<>();
        for (Requirement r : next.requirements()) {
            long required = plugin.settings().required(r, type, s.level);
            if (required <= 0) continue;
            String key = r.key(type);
            out.add(new Goal(r, key, required, Math.min(required, s.progress(key))));
        }
        return out;
    }

    boolean ready(Spawner s, SpawnerType type) {
        if (plugin.settings().next(type, s.level) == null) return false;
        for (Goal g : goals(s, type)) if (!g.done()) return false;
        return true;
    }

    // ---------------------------------------------------------------- giving

    /** Left click ({@code all} true: as much as needed) or right click (a little: one level, one stack). */
    void give(Player p, Spawner s, String key, boolean all, Runnable reopen) {
        SpawnerType type = plugin.settings().type(s.type);
        if (type == null) return;
        Goal goal = null;
        for (Goal g : goals(s, type)) if (g.key().equals(key)) goal = g;
        if (goal == null) return;
        if (goal.done()) {
            plugin.send(p, "goal-done");
            Sounds.cue(p, "error");
            return;
        }
        switch (goal.requirement().kind()) {
            case KILLS -> {
                plugin.send(p, "goal-kills", "%missing%", Format.number(goal.missing()));
                Sounds.cue(p, "click");
            }
            case XP -> giveXp(p, s, type, goal, all);
            case ITEM -> giveItems(p, s, type, goal, all);
            case MONEY -> giveCurrency(p, s, type, goal, plugin.money(), reopen);
            case CURRENCY -> giveCurrency(p, s, type, goal, Economy.get(goal.requirement().currency()), reopen);
        }
    }

    private void giveXp(Player p, Spawner s, SpawnerType type, Goal goal, boolean all) {
        long have = Experience.total(p);
        long amount = all ? Math.min(goal.missing(), have) : Math.min(goal.missing(), Math.max(1, Experience.pointsOfLevels(p, 1)));
        if (amount <= 0 || !Experience.take(p, amount)) {
            plugin.send(p, "no-xp");
            Sounds.cue(p, "error");
            return;
        }
        added(p, s, type, goal.key(), amount, Format.number(amount) + " " + plugin.raw("units.xp"));
    }

    private void giveItems(Player p, Spawner s, SpawnerType type, Goal goal, boolean all) {
        String item = goal.requirement().resolvedItem(type);
        Predicate<ItemStack> match = matcher(item);
        int have = Inventories.count(p, match);
        long amount = Math.min(goal.missing(), all ? have : Math.min(have, 64));
        if (amount <= 0) {
            plugin.send(p, "no-item", "%item%", itemName(item));
            Sounds.cue(p, "error");
            return;
        }
        if (!Inventories.take(p, match, (int) amount)) return;
        added(p, s, type, goal.key(), amount, Format.number(amount) + " " + itemName(item));
    }

    private void giveCurrency(Player p, Spawner s, SpawnerType type, Goal goal, Currency currency, Runnable reopen) {
        if (currency == null || !currency.available()) {
            plugin.send(p, "no-currency");
            return;
        }
        long balance = (long) Math.floor(currency.balance(p));
        if (balance <= 0) {
            plugin.send(p, "no-money", "%price%", currency.format(goal.missing()));
            Sounds.cue(p, "error");
            return;
        }
        long max = Math.min(goal.missing(), balance);
        p.closeInventory();
        Dialogs.form(p, plugin.raw("forms.money.title"))
                .body(plugin.raw("forms.money.body", "%missing%", currency.format(goal.missing()), "%balance%", currency.format(balance)))
                .amount("value", plugin.raw("forms.money.field"), max, 1, max)
                .onSubmit((pl, answer) -> {
                    long amount = answer.amount("value");
                    Spawner now = plugin.spawners().get(s.id);
                    if (now == null || amount <= 0) return;
                    long missing = Math.max(0, goal.required() - now.progress(goal.key()));
                    amount = Math.min(amount, missing);
                    if (amount <= 0) {
                        reopen.run();
                        return;
                    }
                    if (!currency.take(pl, amount, Origin.of(plugin, "upgrade").notifyPlayer())) {
                        plugin.send(pl, "no-money", "%price%", currency.format(amount));
                        Sounds.cue(pl, "error");
                    } else {
                        added(pl, now, type, goal.key(), amount, null);
                    }
                    reopen.run();
                })
                .onCancel(pl -> reopen.run())
                .open();
    }

    private void added(Player p, Spawner s, SpawnerType type, String key, long amount, String what) {
        s.progress.merge(key, amount, Long::sum);
        plugin.store().save(s);
        if (what != null) plugin.send(p, "given", "%what%", what);
        if (ready(s, type)) {
            plugin.send(p, "goals-complete");
            Sounds.cue(p, "complete");
        } else {
            Sounds.cue(p, "success");
        }
    }

    /** Counts a kill by a player towards the next level. */
    void killed(Spawner s, SpawnerType type) {
        if (plugin.settings().next(type, s.level) == null) return;
        long required = 0;
        for (Goal g : goals(s, type)) if (g.requirement().kind() == Requirement.Kind.KILLS) required = g.required();
        if (required > 0 && s.progress("kills") < required) s.progress.merge("kills", 1L, Long::sum);
    }

    /** Every goal reached: the spawner goes up one level, what was given is spent. */
    void levelUp(Player p, Spawner s) {
        SpawnerType type = plugin.settings().type(s.type);
        if (type == null) return;
        Level next = plugin.settings().next(type, s.level);
        if (next == null) {
            plugin.send(p, "max-level");
            Sounds.cue(p, "error");
            return;
        }
        if (!ready(s, type)) {
            plugin.send(p, "goals-missing");
            Sounds.cue(p, "error");
            return;
        }
        s.level = next.number();
        s.progress.clear();
        plugin.store().save(s);
        String[] vars = plugin.vars(s, type);
        plugin.send(p, "level-up", vars);
        if (!Music.jingle(p, "niveau")) Sounds.cue(p, "complete");
        if (s.level >= plugin.settings().maxLevel(type)) {
            String broadcast = plugin.raw("max-level-broadcast", vars);
            if (!broadcast.isBlank()) for (Player o : Bukkit.getOnlinePlayers()) plugin.send(o, "max-level-broadcast", vars);
        }
        plugin.refresh(s);
    }

    // ---------------------------------------------------------------- fuel

    /** Puts fuel in: one item, one stack, or as much as the tank takes ({@code mode} one, stack, all). */
    void fuel(Player p, Spawner s, String item, String mode) {
        SpawnerType type = plugin.settings().type(s.type);
        if (type == null || !plugin.settings().fuels(type).contains(item)) return;
        double per = plugin.settings().fuelSeconds(item, type, s.level);
        if (per <= 0) {
            plugin.send(p, "fuel-worthless");
            Sounds.cue(p, "error");
            return;
        }
        double room = plugin.settings().tankSeconds(type, s.level) - s.fuel;
        if (room < 1) {
            plugin.send(p, "tank-full");
            Sounds.cue(p, "error");
            return;
        }
        Predicate<ItemStack> match = matcher(item);
        int have = Inventories.count(p, match);
        if (have <= 0) {
            plugin.send(p, "no-item", "%item%", itemName(item));
            Sounds.cue(p, "error");
            return;
        }
        int fits = (int) Math.max(1, Math.floor(room / per));
        int want = switch (mode) {
            case "one" -> 1;
            case "stack" -> 64;
            default -> Integer.MAX_VALUE;
        };
        int units = Math.min(Math.min(want, have), fits);
        if (!Inventories.take(p, match, units)) return;
        double before = s.fuel;
        s.fuel = Math.min(plugin.settings().tankSeconds(type, s.level), s.fuel + units * per);
        plugin.store().save(s);
        plugin.send(p, "fuel-added", "%count%", Format.number(units), "%item%", itemName(item),
                "%time%", Production.time(s.fuel - before), "%fuel%", Production.time(s.fuel));
        Sounds.cue(p, "success");
        plugin.refresh(s);
    }

    // ---------------------------------------------------------------- type change

    /** Turns the spawner into another mob for a price; its level stays, its waiting mobs and goals go. */
    void change(Player p, Spawner s, SpawnerType to, Runnable reopen) {
        SpawnerType from = plugin.settings().type(s.type);
        if (from == null || to == null || from == to || !plugin.settings().changeType) return;
        if (to.permission() != null && !p.hasPermission(to.permission())) {
            plugin.send(p, "type-locked");
            Sounds.cue(p, "error");
            return;
        }
        double price = plugin.settings().changePrice(to);
        Currency money = plugin.money();
        p.closeInventory();
        String question = plugin.raw("forms.change.body", "%from%", from.displayName(), "%to%", to.displayName(),
                "%price%", price > 0 ? money.format(price) : plugin.raw("free"));
        Dialogs.confirm(p, plugin.raw("forms.change.title"), question, pl -> {
            Spawner now = plugin.spawners().get(s.id);
            if (now == null || !now.placed()) return;
            if (price > 0 && !money.take(pl, price, Origin.of(plugin, "change_type").notifyPlayer())) {
                plugin.send(pl, "no-money", "%price%", money.format(price));
                Sounds.cue(pl, "error");
                reopen.run();
                return;
            }
            plugin.production().clear(now);
            now.type = to.id();
            now.level = Math.min(now.level, plugin.settings().maxLevel(to));
            now.progress.clear();
            now.pending = 0;
            plugin.production().dress(now, to);
            plugin.store().save(now);
            plugin.send(pl, "type-changed", plugin.vars(now, to));
            Sounds.cue(pl, "complete");
            plugin.refresh(now);
            reopen.run();
        }, pl -> reopen.run());
    }

    // ---------------------------------------------------------------- items

    /** Matches an item id of the suite: a plain vanilla item for a material name, the custom item for a namespaced id. */
    static Predicate<ItemStack> matcher(String item) {
        if (item.contains(":")) return i -> i != null && item.equalsIgnoreCase(CustomItems.id(i));
        Material m = Material.matchMaterial(item);
        return i -> i != null && m != null && i.getType() == m && CustomItems.id(i) == null
                && (!i.hasItemMeta() || !i.getItemMeta().hasDisplayName());
    }

    /** Name of an item id in the game's language of each player ({@code <lang:...>}), or the custom item's own name. */
    String itemName(String item) {
        if (item.contains(":")) {
            Settings.Essence e = plugin.settings().essence(item);
            if (e != null) return plugin.raw("essences." + e.id() + ".name");
            ItemStack stack = CustomItems.item(item);
            if (stack != null && stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
                return fr.influencraft.flux.api.text.Texts.ampersand(stack.getItemMeta().displayName());
            }
            return item;
        }
        Material m = Material.matchMaterial(item);
        return m == null ? item : "<lang:" + m.translationKey() + ">";
    }
}
