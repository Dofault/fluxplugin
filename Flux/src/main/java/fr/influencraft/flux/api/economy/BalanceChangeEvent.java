package fr.influencraft.flux.api.economy;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * The balance of a player changed in a currency, whatever plugin made the change (fired by the money events adapter
 * of Flux, {@code providers.money-events}: CMI today). May be fired off the main thread.
 */
public final class BalanceChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final String currency;
    private final double from;
    private final double to;
    private final boolean deposit;

    public BalanceChangeEvent(UUID player, String currency, double from, double to, boolean deposit) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.currency = currency;
        this.from = from;
        this.to = to;
        this.deposit = deposit;
    }

    public UUID player() { return player; }

    /** Currency id ({@code vault} for the main money). */
    public String currency() { return currency; }

    public double from() { return from; }

    public double to() { return to; }

    /** Money given to the player (deposit), as opposed to taken or set. */
    public boolean deposit() { return deposit; }

    public double delta() { return to - from; }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
