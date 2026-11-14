package fr.influencraft.fluxpets.pets.event;

import fr.influencraft.fluxpets.pets.Pet;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * A ridden pet is about to move this tick, before its path is checked for walls: listeners may clear the way (the
 * mole digs) and slow it down. {@code at} is the pet's feet, {@code direction} the horizontal direction it goes.
 */
public class PetMountMoveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Pet pet;
    private final Location at;
    private final Vector direction;
    private double speedFactor = 1;

    public PetMountMoveEvent(Player player, Pet pet, Location at, Vector direction) {
        this.player = player;
        this.pet = pet;
        this.at = at;
        this.direction = direction;
    }

    public Player getPlayer() {
        return player;
    }

    public Pet getPet() {
        return pet;
    }

    public Location getAt() {
        return at.clone();
    }

    public Vector getDirection() {
        return direction.clone();
    }

    public double getSpeedFactor() {
        return speedFactor;
    }

    /** Multiplies the pet's speed for this tick (the lowest factor asked wins). */
    public void setSpeedFactor(double factor) {
        speedFactor = Math.min(speedFactor, Math.max(0, factor));
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
