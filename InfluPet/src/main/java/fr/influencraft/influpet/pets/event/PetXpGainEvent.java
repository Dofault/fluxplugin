package fr.influencraft.influpet.pets.event;

import fr.influencraft.influpet.pets.Pet;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Un familier gagne de l'XP ; montant modifiable, multiplied = multiplicateur de permission appliqué. Annulable. */
public final class PetXpGainEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Pet pet;
    private final boolean multiplied;
    private double amount;
    private boolean cancelled;

    public PetXpGainEvent(Player player, Pet pet, double amount, boolean multiplied) {
        this.player = player;
        this.pet = pet;
        this.amount = amount;
        this.multiplied = multiplied;
    }

    public Player getPlayer() {
        return player;
    }

    public Pet getPet() {
        return pet;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public boolean isMultiplied() {
        return multiplied;
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
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
