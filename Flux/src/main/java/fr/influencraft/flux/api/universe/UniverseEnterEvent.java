package fr.influencraft.flux.api.universe;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * The player now carries the profile of {@link #universe()}: after a swap, or at join once his profile was checked.
 * Main thread. Plugins set up what belongs to that universe here (class, interface...), never on world change.
 */
public final class UniverseEnterEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();
    private final String universe;
    private final String previous;
    private final boolean join;

    public UniverseEnterEvent(Player player, String universe, String previous, boolean join) {
        super(player);
        this.universe = universe;
        this.previous = previous;
        this.join = join;
    }

    public String universe() {
        return universe;
    }

    /** The universe he came from (the same as {@link #universe()} at a join without swap). */
    public String previous() {
        return previous;
    }

    /** True when it happens as the player joins. */
    public boolean join() {
        return join;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
