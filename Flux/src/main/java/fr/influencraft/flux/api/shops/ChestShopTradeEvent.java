package fr.influencraft.flux.api.shops;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** A trade with a chest shop happened. */
public final class ChestShopTradeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ChestShop shop;
    private final UUID customer;
    private final String customerName;
    private final int amount;
    private final double total;
    private final double tax;

    public ChestShopTradeEvent(ChestShop shop, UUID customer, String customerName, int amount, double total, double tax) {
        this.shop = shop;
        this.customer = customer;
        this.customerName = customerName;
        this.amount = amount;
        this.total = total;
        this.tax = tax;
    }

    public ChestShop shop() { return shop; }

    public UUID customer() { return customer; }

    public String customerName() { return customerName; }

    /** Amount traded as counted by the shop plugin (bundles or items). */
    public int amount() { return amount; }

    /** Money of the trade, tax excluded. */
    public double total() { return total; }

    public double tax() { return tax; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
