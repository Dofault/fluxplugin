package fr.influencraft.flux.api.reward;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pending rewards of the suite. Plugins register their {@link RewardSource}; the rewards plugin (FluxRewards)
 * claims them all at once and also provides a generic {@link Mailbox} so that any plugin, or any config through a
 * console command, can hand a reward (commands + slots it needs) to a player without dropping it.
 *
 * <pre>
 *   Rewards.register(this, new BoosterSource());
 *   Rewards.queue(player, "jobs", "&fBooster Mineur", "itemsadder:influpet:booster_mineur", 1,
 *           List.of("fluxpets give %player% booster_mineur 1"));
 * </pre>
 */
public final class Rewards {

    /** Storage of rewards queued by commands, provided by the rewards plugin. */
    public interface Mailbox {
        /** Keeps a reward for the player ({@code %player%} in commands = his name at claim time). */
        void queue(OfflinePlayer player, String source, String label, String icon, int slots, List<String> commands);
    }

    private record Owned(Plugin owner, RewardSource source) {
    }

    private static final Map<String, Owned> SOURCES = new ConcurrentHashMap<>();
    private static volatile Mailbox mailbox;
    private static volatile Plugin mailboxOwner;
    /** Called when a source's pending rewards change, to refresh menus and reminders. */
    private static volatile java.util.function.Consumer<OfflinePlayer> changed = p -> {
    };

    private Rewards() {
    }

    public static void register(Plugin owner, RewardSource source) {
        SOURCES.put(source.id().toLowerCase(Locale.ROOT), new Owned(owner, source));
    }

    public static void unregisterAll(Plugin owner) {
        SOURCES.values().removeIf(o -> o.owner() == owner);
        if (mailboxOwner == owner) {
            mailbox = null;
            mailboxOwner = null;
        }
    }

    public static List<RewardSource> sources() {
        return SOURCES.values().stream().map(Owned::source).toList();
    }

    public static RewardSource source(String id) {
        Owned o = SOURCES.get(id.toLowerCase(Locale.ROOT));
        return o == null ? null : o.source();
    }

    /** Called by the rewards plugin. */
    public static void provideMailbox(Plugin owner, Mailbox box, java.util.function.Consumer<OfflinePlayer> onChange) {
        mailbox = box;
        mailboxOwner = owner;
        changed = onChange;
    }

    /** Whether a mailbox is available (the rewards plugin is installed). */
    public static boolean hasMailbox() {
        return mailbox != null;
    }

    /** Queues a reward in the mailbox. False when there is no mailbox: the caller must deliver it another way. */
    public static boolean queue(OfflinePlayer player, String source, String label, String icon, int slots, List<String> commands) {
        Mailbox box = mailbox;
        if (box == null) return false;
        box.queue(player, source, label, icon, Math.max(0, slots), List.copyOf(commands));
        return true;
    }

    /** A source tells that the pending rewards of a player changed (new reward, claimed elsewhere). */
    public static void changed(OfflinePlayer player) {
        changed.accept(player);
    }
}
