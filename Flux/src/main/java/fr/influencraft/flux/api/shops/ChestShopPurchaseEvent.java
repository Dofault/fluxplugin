package fr.influencraft.flux.api.shops;

import net.kyori.adventure.text.Component;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** A player is about to trade with a chest shop: cancel it to refuse (with a message). */
public final class ChestShopPurchaseEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ChestShop shop;
    private final UUID customer;
    private final double total;
    private boolean cancelled;
    private Component reason;

    public ChestShopPurchaseEvent(ChestShop shop, UUID customer, double total) {
        this.shop = shop;
        this.customer = customer;
        this.total = total;
    }

    public ChestShop shop() { return shop; }

    public UUID customer() { return customer; }

    public double total() { return total; }

    public Component reason() { return reason; }

    /** Refuses the trade, the message is shown to the customer. */
    public void refuse(Component message) {
        cancelled = true;
        reason = message;
    }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancel) { cancelled = cancel; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
