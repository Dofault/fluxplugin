package fr.influencraft.fluxcommerce.trade;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A trade between two players. The offered items are held here, out of both inventories, until the trade ends: they
 * go to the other player when it completes, back to their owner when it is cancelled (whatever the cause). Main
 * thread only.
 */
final class Trade {

    /** One stack offered, with an id that stays the same while it is offered (clicks of the menu). */
    record Offered(int id, ItemStack item) {
    }

    /** What one player offers, and where he stands. */
    static final class Side {
        final UUID id;
        final String name;
        final List<Offered> items = new ArrayList<>();
        /** Experience offered, in points (the truth), and the levels typed when given in levels (-1: typed in points). */
        long xp;
        int xpLevels = -1;
        long money;
        boolean ready;
        /** In a form of the trade (amount): his window is closed on purpose. */
        boolean editing;
        long editingSince;

        Side(Player player) {
            this.id = player.getUniqueId();
            this.name = player.getName();
        }

        Player player() {
            return Bukkit.getPlayer(id);
        }

        boolean empty() {
            return items.isEmpty() && xp <= 0 && money <= 0;
        }

        List<ItemStack> stacks() {
            List<ItemStack> out = new ArrayList<>(items.size());
            for (Offered o : items) out.add(o.item());
            return out;
        }

        Offered offered(int offeredId) {
            for (Offered o : items) if (o.id() == offeredId) return o;
            return null;
        }
    }

    final Side a, b;
    private int nextId;
    /** Nobody can validate before this time (ms): an offer just changed. */
    long lockedUntil;
    BukkitTask unlockTask, countdownTask;
    int secondsLeft = -1;
    boolean over;

    Trade(Player first, Player second) {
        this.a = new Side(first);
        this.b = new Side(second);
    }

    int nextId() {
        return ++nextId;
    }

    Side side(UUID player) {
        return a.id.equals(player) ? a : b.id.equals(player) ? b : null;
    }

    Side other(Side side) {
        return side == a ? b : a;
    }

    boolean counting() {
        return countdownTask != null;
    }

    boolean locked() {
        return System.currentTimeMillis() < lockedUntil;
    }

    void stopTasks() {
        if (countdownTask != null) countdownTask.cancel();
        if (unlockTask != null) unlockTask.cancel();
        countdownTask = null;
        unlockTask = null;
        secondsLeft = -1;
    }
}
