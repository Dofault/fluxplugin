package fr.influencraft.flux.api.reward;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The reward manager of the suite. Every plugin hands its rewards to Flux ({@link #give}): Flux keeps them
 * ({@code plugins/Flux/rewards.db}) and delivers them all through one command ({@code /rewards}, renamable) and one
 * menu, within the room left in the inventory. Nothing is ever dropped, and a reward is marked as claimed before its
 * commands run, so it can never be delivered twice.
 *
 * <p>Rewards living elsewhere (the website's votes...) are moved into Flux by a {@link #collector}, called when the
 * player joins and before every claim.</p>
 *
 * <pre>
 *   Rewards.give(player, Reward.fromConfig(section, "referral", "&amp;fReferral bonus").key("referral:tier:5"));
 *   Rewards.collector(this, player -> moveWebsiteVotesIntoFlux(player));
 * </pre>
 */
public final class Rewards {

    /** Implemented by Flux. */
    public interface Backend {
        boolean give(OfflinePlayer player, Reward reward);

        boolean given(UUID player, String key);

        List<StoredReward> pending(UUID player);

        ClaimResult claim(Player player, String source, long id, boolean verbose);

        void open(Player player);
    }

    private static volatile Backend backend;
    private static final Map<Plugin, Consumer<Player>> COLLECTORS = new ConcurrentHashMap<>();

    private Rewards() {
    }

    public static void bind(Backend flux) {
        backend = flux;
    }

    /** True when Flux runs its reward manager. */
    public static boolean available() {
        return backend != null;
    }

    /**
     * Keeps a reward for the player until he claims it. Any thread. False when Flux is missing, when the database
     * refused it (logged) or when a reward with the same key was already given to him.
     */
    public static boolean give(OfflinePlayer player, Reward reward) {
        Backend b = backend;
        return b != null && player != null && reward != null && b.give(player, reward);
    }

    /** Whether a reward with this key was already given to the player (claimed or not). */
    public static boolean given(UUID player, String key) {
        Backend b = backend;
        return b != null && key != null && b.given(player, key);
    }

    /** Rewards waiting for the player, oldest first. */
    public static List<StoredReward> pending(UUID player) {
        Backend b = backend;
        return b == null ? List.of() : b.pending(player);
    }

    public static int count(UUID player) {
        return pending(player).size();
    }

    /** Claims everything that fits (main thread), with the usual messages. */
    public static ClaimResult claim(Player player) {
        return claim(player, null, true);
    }

    /** Claims what fits among the rewards of one source (null = all). */
    public static ClaimResult claim(Player player, String source, boolean verbose) {
        Backend b = backend;
        return b == null ? ClaimResult.NONE : b.claim(player, source == null ? null : source.toLowerCase(Locale.ROOT), -1, verbose);
    }

    /** Claims the waiting reward of this key when it fits (one case of a path in a menu). */
    public static ClaimResult claimKey(Player player, String key, boolean verbose) {
        Backend b = backend;
        if (b == null || key == null) return ClaimResult.NONE;
        for (StoredReward r : b.pending(player.getUniqueId())) {
            if (key.equals(r.reward().key())) return b.claim(player, null, r.id(), verbose);
        }
        return ClaimResult.NONE;
    }

    /** Whether a reward of this key waits for the player (given, not claimed yet). */
    public static boolean waiting(UUID player, String key) {
        return key != null && pending(player).stream().anyMatch(r -> key.equals(r.reward().key()));
    }

    /** Opens the rewards menu (claims everything when no menu is installed). */
    public static void open(Player player) {
        Backend b = backend;
        if (b != null) b.open(player);
    }

    /** Moves rewards kept elsewhere into Flux for this player; called on join and before every claim, main thread. */
    public static void collector(Plugin owner, Consumer<Player> collector) {
        COLLECTORS.put(owner, collector);
    }

    /** Called by Flux. */
    public static void collect(Player player) {
        for (Map.Entry<Plugin, Consumer<Player>> e : COLLECTORS.entrySet()) {
            try {
                e.getValue().accept(player);
            } catch (RuntimeException ex) {
                e.getKey().getLogger().warning("Reward collector failed for " + player.getName() + ": " + ex);
            }
        }
    }

    public static void unregisterAll(Plugin owner) {
        COLLECTORS.remove(owner);
    }
}
