package fr.influencraft.fluxportal.api;

/**
 * A permanent portal: a name, its two ends ({@code entry} and {@code exit}, either may be missing while it is being
 * set up) and the diameter of its rings. It works both ways once both ends exist.
 */
public record Portal(String name, PortalEnd entry, PortalEnd exit, double size) {

    public boolean complete() {
        return entry != null && exit != null;
    }

    /** Radius of the hole a traveller goes through (the ring itself is a little larger). */
    public double holeRadius() {
        return size * 0.4;
    }

    public PortalEnd other(PortalEnd end) {
        return end.equals(entry) ? exit : entry;
    }
}
