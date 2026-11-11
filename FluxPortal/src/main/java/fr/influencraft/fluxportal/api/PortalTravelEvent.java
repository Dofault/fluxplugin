package fr.influencraft.fluxportal.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** A player is about to go through a portal; cancel to keep him on this side. Main thread. */
public final class PortalTravelEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Portal portal;
    private final Location to;
    private boolean cancelled;

    public PortalTravelEvent(Player player, Portal portal, Location to) {
        super(player);
        this.portal = portal;
        this.to = to;
    }

    public Portal portal() {
        return portal;
    }

    /** Where he will come out (may be changed by moving this location). */
    public Location to() {
        return to;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
