package fr.influencraft.fluxpw;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One player warp. Its location is kept as a world name and coordinates, so a warp whose world is not loaded keeps
 * its place and works again once the world is back. Main thread only; saved by {@link WarpStore}.
 */
final class Warp {

    enum Status { OPENED, CLOSED, PASSWORD_PROTECTED }

    final UUID id;
    String name;
    /** Name shown in menus (colour codes allowed), null = the name. */
    String displayName;
    UUID owner;
    String ownerName;
    String world;
    double x, y, z;
    float yaw, pitch;
    /** Icon: a custom item id ({@code namespace:id}) or a material name; null = the default icon. */
    String icon;
    String description;
    /** Key of its category in the config. */
    String category;
    Status status = Status.OPENED;
    String password;
    int admission;
    int visits;
    /** Sum of the stars given; the average is {@link #rating()}. */
    int ratingSum;
    long created;
    long featuredUntil;
    final Set<UUID> reviewers = new HashSet<>();
    final Set<UUID> visitors = new HashSet<>();
    final Set<UUID> blocked = new HashSet<>();
    /** Visits since the server started (not saved). */
    int today;

    Warp(UUID id) {
        this.id = id;
    }

    /** Null while the world is not loaded. */
    Location location() {
        World w = world == null ? null : Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    void location(Location l) {
        world = l.getWorld().getName();
        x = l.getX();
        y = l.getY();
        z = l.getZ();
        yaw = l.getYaw();
        pitch = l.getPitch();
    }

    String shownName() {
        return displayName == null || displayName.isBlank() ? name : displayName;
    }

    /** Average stars, 0 without any rating. */
    double rating() {
        return reviewers.isEmpty() ? 0 : (double) ratingSum / reviewers.size();
    }

    /** Listed and visitable (a password-protected warp is: the password is asked on the way). */
    boolean open() {
        return status != Status.CLOSED;
    }

    boolean featured() {
        return featuredUntil > System.currentTimeMillis();
    }

    boolean owner(UUID player) {
        return player.equals(owner);
    }
}
