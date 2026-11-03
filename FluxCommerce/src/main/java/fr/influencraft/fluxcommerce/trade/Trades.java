package fr.influencraft.fluxcommerce.trade;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.flux.api.inventory.Inventories;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.util.Experience;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Requests and trades in progress. A request waits for the other player ({@code requests.expire-seconds}); once
 * accepted both players get the trade window. Every change of an offer cancels both validations and locks the
 * validate button for a moment ({@code trade.change-lock-seconds}); once both validated, a countdown runs
 * ({@code trade.confirm-seconds}) and the trade is made: everything is checked again (money, experience, room, game
 * mode, distance) before anything moves. A trade ended any other way gives every offered item back to its owner.
 * Main thread only.
 */
final class Trades {

    /** Stacks one player may offer: the slots of his side in the menu. */
    static final int MAX_STACKS = 12;
    /** A player typing an amount for longer than this ends the trade. */
    private static final long FORM_TIMEOUT_MS = 120_000L;

    /** A request from {@code from} to {@code to}, forgotten after {@code expiry}. */
    private record Request(UUID from, String fromName, UUID to, String toName, BukkitTask expiry) {
    }

    private final TradeModule plugin;
    private final Map<UUID, Trade> trades = new HashMap<>();
    /** Pending requests by receiver, then by sender (oldest first). */
    private final Map<UUID, LinkedHashMap<UUID, Request>> requests = new HashMap<>();
    private BukkitTask watcher;

    Trades(TradeModule plugin) {
        this.plugin = plugin;
    }

    /** Checks both players of every trade once a second (online, game mode, world, distance). */
    void start() {
        watcher = Bukkit.getScheduler().runTaskTimer(plugin.host(), this::watch, 20L, 20L);
    }

    /** Server stopping or plugin disabled: every trade is cancelled, items given back. */
    void stop() {
        if (watcher != null) watcher.cancel();
        for (Trade t : new HashSet<>(trades.values())) abort(t, "aborted-stopped");
        for (Map<UUID, Request> byFrom : requests.values()) for (Request r : byFrom.values()) r.expiry().cancel();
        requests.clear();
    }

    Trade of(Player player) {
        return player == null ? null : trades.get(player.getUniqueId());
    }

    Collection<Trade> all() {
        return new HashSet<>(trades.values());
    }

    // ------------------------------------------------------------------ requests

    /** Sneak + right click or /trade &lt;player&gt;: sends a request, or accepts the one {@code to} already sent. */
    void request(Player from, Player to) {
        String why = plugin.cannotTrade(from, to);
        if (why != null) {
            refuse(from, why, to.getName());
            return;
        }
        if (pending(from.getUniqueId(), to.getUniqueId()) != null) {
            accept(from, to.getName());
            return;
        }
        if (pending(to.getUniqueId(), from.getUniqueId()) != null) {
            refuse(from, "request-already-sent", to.getName());
            return;
        }
        if (!plugin.accepting(to)) {
            refuse(from, "request-not-accepting", to.getName());
            return;
        }
        long ticks = Math.max(1, plugin.getConfig().getLong("requests.expire-seconds", 30)) * 20L;
        UUID fromId = from.getUniqueId(), toId = to.getUniqueId();
        BukkitTask expiry = Bukkit.getScheduler().runTaskLater(plugin.host(), () -> {
            Request r = remove(toId, fromId);
            Player sender = Bukkit.getPlayer(fromId);
            if (r != null && sender != null) plugin.messages().send(sender, "request-expired", "%player%", r.toName());
        }, ticks);
        requests.computeIfAbsent(toId, k -> new LinkedHashMap<>())
                .put(fromId, new Request(fromId, from.getName(), toId, to.getName(), expiry));
        plugin.messages().send(from, "request-sent", "%player%", to.getName(),
                "%seconds%", String.valueOf(ticks / 20));
        plugin.messages().send(to, "request-received", "%player%", from.getName(), "%cmd%", plugin.label());
        Sounds.play(to, plugin.getConfig().getString("requests.sound", "@alert"));
        Sounds.cue(from, "click");
    }

    /** /trade accept [player]: the request of this player, or the latest one. */
    void accept(Player to, String fromName) {
        Request r = find(to, fromName);
        if (r == null) {
            refuse(to, "request-none", fromName == null ? "" : fromName);
            return;
        }
        remove(r.to(), r.from());
        Player from = Bukkit.getPlayer(r.from());
        if (from == null) {
            refuse(to, "request-offline", r.fromName());
            return;
        }
        String why = plugin.cannotTrade(to, from);
        if (why != null) {
            refuse(to, why, from.getName());
            return;
        }
        open(from, to);
    }

    /** /trade deny [player]. */
    void deny(Player to, String fromName) {
        Request r = find(to, fromName);
        if (r == null) {
            refuse(to, "request-none", fromName == null ? "" : fromName);
            return;
        }
        remove(r.to(), r.from());
        plugin.messages().send(to, "request-denied", "%player%", r.fromName());
        Player from = Bukkit.getPlayer(r.from());
        if (from != null) {
            plugin.messages().send(from, "request-denied-sender", "%player%", to.getName());
            Sounds.cue(from, "error");
        }
    }

    /** Names of the players whose request {@code to} can accept (completion). */
    List<String> requesters(Player to) {
        List<String> out = new ArrayList<>();
        Map<UUID, Request> mine = requests.get(to.getUniqueId());
        if (mine != null) for (Request r : mine.values()) out.add(r.fromName());
        return out;
    }

    private Request find(Player to, String fromName) {
        Map<UUID, Request> mine = requests.get(to.getUniqueId());
        if (mine == null || mine.isEmpty()) return null;
        if (fromName == null || fromName.isBlank()) {
            Request last = null;
            for (Request r : mine.values()) last = r;
            return last;
        }
        for (Request r : mine.values()) if (r.fromName().equalsIgnoreCase(fromName)) return r;
        return null;
    }

    private Request pending(UUID to, UUID from) {
        Map<UUID, Request> mine = requests.get(to);
        return mine == null ? null : mine.get(from);
    }

    private Request remove(UUID to, UUID from) {
        Map<UUID, Request> mine = requests.get(to);
        if (mine == null) return null;
        Request r = mine.remove(from);
        if (mine.isEmpty()) requests.remove(to);
        if (r != null) r.expiry().cancel();
        return r;
    }

    /** A player leaves: his requests, sent and received, are forgotten. */
    void forgetRequests(UUID player) {
        Map<UUID, Request> received = requests.remove(player);
        if (received != null) for (Request r : received.values()) r.expiry().cancel();
        for (UUID to : new ArrayList<>(requests.keySet())) remove(to, player);
    }

    // ------------------------------------------------------------------ opening

    /** Starts a trade (requests between them forgotten) and opens both windows. */
    void open(Player first, Player second) {
        remove(first.getUniqueId(), second.getUniqueId());
        remove(second.getUniqueId(), first.getUniqueId());
        Trade t = new Trade(first, second);
        trades.put(first.getUniqueId(), t);
        trades.put(second.getUniqueId(), t);
        plugin.getLogger().info("Trade started: " + first.getName() + " - " + second.getName());
        for (Player p : List.of(first, second)) {
            Player other = p == first ? second : first;
            if (!showWindow(p)) {
                abort(t, "aborted-window");
                return;
            }
            plugin.messages().send(p, "started", "%player%", other.getName());
            Sounds.play(p, plugin.sound("started"));
        }
    }

    /** Opens (again) the trade window of this player. */
    boolean showWindow(Player p) {
        for (String list : TradeMenu.LISTS) Lists.invalidate(p, list);
        return FluxApi.open(p, TradeMenu.MENU);
    }

    // ------------------------------------------------------------------ offers

    /** Click in his inventory: the stack (left click) or one item (right click) of this slot joins his offer. */
    void add(Player p, int slot, boolean one) {
        Trade t = of(p);
        if (t == null || t.over) return;
        Trade.Side s = t.side(p.getUniqueId());
        ItemStack there = p.getInventory().getItem(slot);
        if (there == null || there.getType().isAir()) return;
        if (plugin.blacklisted(there)) {
            refuse(p, "item-refused", "");
            return;
        }
        ItemStack moving = there.clone();
        moving.setAmount(one ? 1 : there.getAmount());
        // merged into an offered stack of the same item when there is room, else a new stack
        List<ItemStack> after = merged(s.stacks(), moving);
        if (after.size() > MAX_STACKS) {
            refuse(p, "offer-full", String.valueOf(MAX_STACKS));
            return;
        }
        Player partner = t.other(s).player();
        if (partner != null && plugin.getConfig().getBoolean("trade.block-when-full", true) && !Inventories.fits(partner, after)) {
            refuse(p, "partner-full", t.other(s).name);
            return;
        }
        if (moving.getAmount() >= there.getAmount()) p.getInventory().setItem(slot, null);
        else there.setAmount(there.getAmount() - moving.getAmount());
        s.items.clear();
        for (ItemStack stack : after) s.items.add(new Trade.Offered(t.nextId(), stack));
        Sounds.cue(p, "click");
        changed(t, s);
    }

    /** Click on one of his offered stacks: it goes back to his inventory (all of it, or one item). */
    void takeBack(Player p, int offeredId, boolean one) {
        Trade t = of(p);
        if (t == null || t.over) return;
        Trade.Side s = t.side(p.getUniqueId());
        Trade.Offered o = s.offered(offeredId);
        if (o == null) return;
        ItemStack back = o.item().clone();
        if (one) back.setAmount(1);
        if (!Inventories.fits(p, back)) {
            refuse(p, "inventory-full", "");
            return;
        }
        if (back.getAmount() >= o.item().getAmount()) s.items.remove(o);
        else o.item().setAmount(o.item().getAmount() - back.getAmount());
        Inventories.giveOrDrop(p, List.of(back));
        Sounds.cue(p, "remove");
        changed(t, s);
    }

    void setExperience(Player p, long points, int levels) {
        Trade t = of(p);
        if (t == null || t.over) return;
        Trade.Side s = t.side(p.getUniqueId());
        if (s.xp == points && s.xpLevels == levels) return;
        s.xp = Math.max(0, points);
        s.xpLevels = points <= 0 ? -1 : levels;
        changed(t, s);
    }

    void setMoney(Player p, long amount) {
        Trade t = of(p);
        if (t == null || t.over) return;
        Trade.Side s = t.side(p.getUniqueId());
        if (s.money == amount) return;
        s.money = Math.max(0, amount);
        changed(t, s);
    }

    /**
     * Same items together, each stack as full as its item allows: what the offer would hold with {@code adding}
     * (a copy, nothing changed).
     */
    private static List<ItemStack> merged(List<ItemStack> offered, ItemStack adding) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack i : offered) out.add(i.clone());
        int left = adding.getAmount();
        for (ItemStack i : out) {
            if (left <= 0) break;
            if (!i.isSimilar(adding) || i.getAmount() >= i.getMaxStackSize()) continue;
            int put = Math.min(left, i.getMaxStackSize() - i.getAmount());
            i.setAmount(i.getAmount() + put);
            left -= put;
        }
        while (left > 0) {
            ItemStack rest = adding.clone();
            rest.setAmount(Math.min(left, adding.getMaxStackSize()));
            out.add(rest);
            left -= rest.getAmount();
        }
        return out;
    }

    /** An offer changed: both validations are cancelled and nobody can validate for a moment. */
    void changed(Trade t, Trade.Side by) {
        Trade.Side other = t.other(by);
        boolean otherWasReady = other.ready;
        t.stopTasks();
        t.a.ready = false;
        t.b.ready = false;
        long lock = Math.max(0, plugin.getConfig().getLong("trade.change-lock-seconds", 2));
        t.lockedUntil = System.currentTimeMillis() + lock * 1000L;
        if (lock > 0) t.unlockTask = Bukkit.getScheduler().runTaskLater(plugin.host(), () -> {
            t.unlockTask = null;
            refresh(t);
        }, lock * 20L + 1);
        Player watcher = other.player();
        if (watcher != null && otherWasReady) {
            plugin.messages().actionBar(watcher, "offer-changed", "%player%", by.name);
            Sounds.play(watcher, plugin.sound("changed"));
        }
        refresh(t);
    }

    /** The validate button: validates, or cancels his validation (and the countdown). */
    void toggleReady(Player p) {
        Trade t = of(p);
        if (t == null || t.over) return;
        Trade.Side s = t.side(p.getUniqueId());
        if (s.ready) {
            s.ready = false;
            t.stopTasks();
            Sounds.cue(p, "toggle-off");
            refresh(t);
            return;
        }
        if (t.locked()) {
            refuse(p, "locked", "");
            return;
        }
        if (t.a.empty() && t.b.empty()) {
            refuse(p, "nothing", "");
            return;
        }
        String missing = plugin.cannotPay(p, s);
        if (missing != null) {
            refuse(p, missing, "");
            return;
        }
        s.ready = true;
        Sounds.cue(p, "toggle-on");
        if (t.other(s).ready) countdown(t);
        else refresh(t);
    }

    private void countdown(Trade t) {
        int seconds = Math.max(0, plugin.getConfig().getInt("trade.confirm-seconds", 3));
        if (seconds == 0) {
            complete(t);
            return;
        }
        t.secondsLeft = seconds;
        for (Trade.Side s : List.of(t.a, t.b)) {
            Player p = s.player();
            if (p != null) Sounds.play(p, plugin.sound("countdown"));
        }
        t.countdownTask = Bukkit.getScheduler().runTaskTimer(plugin.host(), () -> {
            if (t.over) return;
            t.secondsLeft--;
            if (t.secondsLeft <= 0) {
                t.countdownTask.cancel();
                t.countdownTask = null;
                complete(t);
                return;
            }
            for (Trade.Side s : List.of(t.a, t.b)) {
                Player p = s.player();
                if (p != null) Sounds.play(p, plugin.sound("countdown"));
            }
            refresh(t);
        }, 20L, 20L);
        refresh(t);
    }

    // ------------------------------------------------------------------ the trade itself

    /**
     * Both validated and the countdown ran out: everything is checked again, then items, experience and money move.
     * A check that fails cancels the validations (the trade stays open) with the reason.
     */
    private void complete(Trade t) {
        if (t.over) return;
        t.stopTasks();
        Player pa = t.a.player(), pb = t.b.player();
        if (pa == null || pb == null) {
            abort(t, "aborted-left", pa == null ? t.a.id : t.b.id, null, null);
            return;
        }
        String why = plugin.cannotKeepTrading(pa, pb);
        if (why != null) {
            abort(t, why);
            return;
        }
        if (!stillPossible(t, t.a, pa, pb) || !stillPossible(t, t.b, pb, pa)) return;

        Currency money = plugin.money();
        Origin origin = plugin.origin("");
        // money first: the only step that may still fail (economy unavailable); rolled back if the second one does
        if (t.a.money > 0 && !money.take(pa, t.a.money, origin.verb("given").detail(t.b.name))) {
            failed(t, pa);
            return;
        }
        if (t.b.money > 0 && !money.take(pb, t.b.money, origin.verb("given").detail(t.a.name))) {
            if (t.a.money > 0) money.give(pa, t.a.money, plugin.origin("refund"));
            failed(t, pb);
            return;
        }
        t.over = true;
        trades.remove(t.a.id);
        trades.remove(t.b.id);
        if (t.a.xp > 0) Experience.take(pa, t.a.xp);
        if (t.b.xp > 0) Experience.take(pb, t.b.xp);
        List<ItemStack> toB = t.a.stacks(), toA = t.b.stacks();
        t.a.items.clear();
        t.b.items.clear();
        Inventories.giveOrDrop(pa, toA);
        Inventories.giveOrDrop(pb, toB);
        if (t.a.xp > 0) Experience.give(pb, t.a.xp);
        if (t.b.xp > 0) Experience.give(pa, t.b.xp);
        if (t.a.money > 0) money.give(pb, t.a.money, origin.verb("received").detail(t.a.name));
        if (t.b.money > 0) money.give(pa, t.b.money, origin.verb("received").detail(t.b.name));
        closeWindow(pa);
        closeWindow(pb);
        plugin.summary(pa, t.b.name, toB, toA, t.a, t.b);
        plugin.summary(pb, t.a.name, toA, toB, t.b, t.a);
        if (t.a.money > 0) {
            Economy.notify(pa, money, -t.a.money, origin.verb("given").detail(t.b.name));
            Economy.notify(pb, money, t.a.money, origin.verb("received").detail(t.a.name));
        }
        if (t.b.money > 0) {
            Economy.notify(pb, money, -t.b.money, origin.verb("given").detail(t.a.name));
            Economy.notify(pa, money, t.b.money, origin.verb("received").detail(t.b.name));
        }
        Sounds.play(pa, plugin.sound("completed"));
        Sounds.play(pb, plugin.sound("completed"));
        plugin.history().record(t.a, toB, t.b, toA);
        plugin.getLogger().info("Trade completed: " + t.a.name + " gave " + TradeHistory.describe(toB, t.a) + " | "
                + t.b.name + " gave " + TradeHistory.describe(toA, t.b));
    }

    /** Can {@code giver} still give his offer, and {@code receiver} hold it? Else both validations are cancelled. */
    private boolean stillPossible(Trade t, Trade.Side side, Player giver, Player receiver) {
        String missing = plugin.cannotPay(giver, side);
        if (missing != null) {
            unready(t, giver, missing, receiver, "partner-cannot-pay", side.name);
            return false;
        }
        if (!Inventories.fits(receiver, side.stacks())) {
            unready(t, receiver, "inventory-full-end", giver, "partner-full", receiver.getName());
            return false;
        }
        return true;
    }

    private void failed(Trade t, Player who) {
        Player other = t.other(t.side(who.getUniqueId())).player();
        unready(t, who, "failed", other, "failed", "");
    }

    private void unready(Trade t, Player p, String key, Player other, String otherKey, String name) {
        t.stopTasks();
        t.a.ready = false;
        t.b.ready = false;
        refuse(p, key, name);
        if (other != null) refuse(other, otherKey, name);
        refresh(t);
    }

    /**
     * Ends a trade without making it: every offered item goes back to its owner (dropped at his feet if his inventory
     * is full), both windows close. Message {@code key} to both players ({@code %player%} = the other one), or, when
     * one player caused it, {@code key} to the other one ({@code %player%} = him) and {@code key-you} (when the lang
     * file has it) to him.
     *
     * @param cause   the player who ended it (closed the window, left, died), or null
     * @param leaving the player leaving the server or dying (still usable during his event: no message, no window)
     * @param drops   where the items of {@code leaving} go instead of his inventory (death drops), or null
     */
    void abort(Trade t, String key, UUID cause, Player leaving, List<ItemStack> drops) {
        if (t.over) return;
        t.over = true;
        t.stopTasks();
        trades.remove(t.a.id);
        trades.remove(t.b.id);
        String causeName = cause == null ? null : t.side(cause) == null ? null : t.side(cause).name;
        for (Trade.Side s : List.of(t.a, t.b)) {
            boolean left = leaving != null && leaving.getUniqueId().equals(s.id);
            Player p = left ? leaving : s.player();
            List<ItemStack> back = s.stacks();
            s.items.clear();
            if (left && drops != null) {
                drops.addAll(back);
                continue;
            }
            if (p == null) {
                // cannot happen (a leaving player is handed in): logged so the staff can give them back
                if (!back.isEmpty()) plugin.getLogger().severe("Items of " + s.name + " not given back: " + TradeHistory.describe(back, s));
                continue;
            }
            Inventories.giveOrDrop(p, back);
            if (left) continue;
            closeWindow(p);
            boolean caused = s.id.equals(cause);
            String message = caused && plugin.messages().has(key + "-you") ? key + "-you" : key;
            plugin.messages().send(p, message, "%player%", causeName != null && !caused ? causeName : t.other(s).name);
            Sounds.play(p, plugin.sound("aborted"));
        }
        plugin.getLogger().info("Trade cancelled (" + key + (causeName == null ? "" : " by " + causeName) + "): "
                + t.a.name + " - " + t.b.name);
    }

    void abort(Trade t, String key) {
        abort(t, key, null, null, null);
    }

    /** A trade window closed: unless the player is typing an amount or the window opened again, the trade ends. */
    void windowClosed(Player p) {
        Bukkit.getScheduler().runTask(plugin.host(), () -> {
            Trade t = of(p);
            if (t == null || t.over || !p.isOnline()) return;
            Trade.Side s = t.side(p.getUniqueId());
            if (s.editing) return;
            MenuView view = FluxApi.view(p);
            if (view != null && view.isOpen() && TradeMenu.MENU.equals(view.menuId())) return;
            abort(t, "aborted-cancelled", p.getUniqueId(), null, null);
        });
    }

    private void closeWindow(Player p) {
        MenuView view = FluxApi.view(p);
        if (view != null && view.isOpen() && TradeMenu.MENU.equals(view.menuId())) view.close();
    }

    private void watch() {
        for (Trade t : all()) {
            if (t.over) continue;
            Player pa = t.a.player(), pb = t.b.player();
            if (pa == null || pb == null) {
                abort(t, "aborted-left", pa == null ? t.a.id : t.b.id, null, null);
                continue;
            }
            String why = plugin.cannotKeepTrading(pa, pb);
            if (why != null) {
                abort(t, why);
                continue;
            }
            // a form left open (or lost by the client) must not hold the other player forever
            long idle = System.currentTimeMillis() - FORM_TIMEOUT_MS;
            for (Trade.Side s : List.of(t.a, t.b)) {
                if (s.editing && s.editingSince < idle) {
                    abort(t, "aborted-idle", s.id, null, null);
                    break;
                }
            }
        }
    }

    /** Both windows drawn again (lists, texts, title). */
    void refresh(Trade t) {
        for (Trade.Side s : List.of(t.a, t.b)) {
            Player p = s.player();
            if (p == null) continue;
            for (String list : TradeMenu.LISTS) Lists.invalidate(p, list);
            MenuView view = FluxApi.view(p);
            if (view != null && view.isOpen() && TradeMenu.MENU.equals(view.menuId())) view.refresh();
        }
    }

    private void refuse(Player p, String key, String name) {
        plugin.messages().send(p, key, "%player%", name, "%max%", String.valueOf(MAX_STACKS));
        Sounds.cue(p, "error");
    }
}
