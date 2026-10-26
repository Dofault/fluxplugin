package fr.influencraft.fluxpets.pets.event;

import fr.influencraft.fluxpets.pets.Pet;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Un joueur active un familier ; previous = familier actif avant (null si aucun). Annulable. */
public final class PetActivateEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Pet pet;
    private final Pet previous;
    private boolean cancelled;

    public PetActivateEvent(Player player, Pet pet, Pet previous) {
        this.player = player;
        this.pet = pet;
        this.previous = previous;
    }

    public Player getPlayer() {
        return player;
    }

    public Pet getPet() {
        return pet;
    }

    public Pet getPrevious() {
        return previous;
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
