package fr.influencraft.flux.api.economy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Account history of the players: every balance change of every currency ({@link BalanceChangeEvent}) is recorded by
 * Flux with its {@link Origin}, whatever plugin made it, and shown in the account menu ({@code /compte}).
 *
 * <p>Where the origin comes from:</p>
 * <ol>
 *   <li>the origin given to {@link Currency#give(org.bukkit.OfflinePlayer, double, Origin)} / {@code take}, or set
 *   around any code with {@link #within} (a reward running {@code cmi money give}, a command dispatched for a
 *   feature);</li>
 *   <li>else the plugin found in the call stack: the outermost plugin that is not part of the money plumbing (Vault,
 *   CMI's economy, eco, Flux itself), refined by the class rules of Flux's config ({@code ledger.classes}: CMI's
 *   salary or {@code /pay}). EcoJobs, Lands, AuctionHouse or a plugin added next year are recorded without a line of
 *   code.</li>
 * </ol>
 *
 * Frequent small movements (a job paying every block) are merged: movements of the same currency, origin and
 * direction within {@code ledger.merge-seconds} form one line with their count. A movement with a
 * {@link Origin#detail} is never merged.
 *
 * <p>A currency implemented outside Flux records its movements by firing a {@link BalanceChangeEvent}.</p>
 */
public final class Ledger {

    /** One line of a player's history (several merged movements when {@link #count} &gt; 1). */
    public record Line(long id, String currency, Origin origin, double amount, int count, double balance, long first, long last) {
        public boolean gain() {
            return amount >= 0;
        }
    }

    /** Implemented by Flux. */
    public interface Backend {
        /** Lines of a player, most recent first (cached for online players, loaded from the database otherwise). */
        CompletableFuture<List<Line>> history(UUID player);

        /** Lines already loaded (online player, or history loaded earlier), else null. Main thread friendly. */
        List<Line> cached(UUID player);

        /** Label of an origin as players read it. */
        String label(Origin origin);
    }

    private static final ThreadLocal<Deque<Origin>> CONTEXT = ThreadLocal.withInitial(ArrayDeque::new);
    private static volatile Backend backend;

    private Ledger() {
    }

    /** Called by Flux. */
    public static void bind(Backend b) {
        backend = b;
    }

    public static boolean available() {
        return backend != null;
    }

    /**
     * Runs the code with this origin: every balance change it makes on this thread is recorded under it (even
     * through commands, e.g. {@code cmi money give} dispatched by a reward). Nested origins: the innermost wins.
     */
    public static <T> T within(Origin origin, Supplier<T> code) {
        if (origin == null || origin.unknown()) return code.get();
        Deque<Origin> stack = CONTEXT.get();
        stack.push(origin);
        try {
            return code.get();
        } finally {
            stack.pop();
        }
    }

    public static void run(Origin origin, Runnable code) {
        within(origin, () -> {
            code.run();
            return null;
        });
    }

    /** Origin set by {@link #within} on this thread, else null. */
    public static Origin current() {
        return CONTEXT.get().peek();
    }

    /** History of a player, most recent first. Completes off the main thread. */
    public static CompletableFuture<List<Line>> history(UUID player) {
        Backend b = backend;
        return b == null ? CompletableFuture.completedFuture(List.of()) : b.history(player);
    }

    /** History already in memory, or null (then ask {@link #history}). */
    public static List<Line> cached(UUID player) {
        Backend b = backend;
        return b == null ? null : b.cached(player);
    }

    public static String label(Origin origin) {
        Backend b = backend;
        return b == null ? origin.id() : b.label(origin);
    }
}
