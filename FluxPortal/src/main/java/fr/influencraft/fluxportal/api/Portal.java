package fr.influencraft.fluxportal.api;

/**
 * A permanent portal: a name, its two sides (either may be missing while it is being set up), the size of its
 * rectangular hole in blocks and whether it is open. Once both sides exist and it is open, each side shows the other
 * and walking through either one leads out of the other.
 */
public record Portal(String name, PortalEnd side1, PortalEnd side2, int width, int height, boolean open) {

    public boolean complete() {
        return side1 != null && side2 != null;
    }

    /** Side 1 or 2. */
    public PortalEnd side(int side) {
        return side == 1 ? side1 : side2;
    }

    public PortalEnd other(PortalEnd end) {
        return end.equals(side1) ? side2 : side1;
    }

    public Portal withSide(int side, PortalEnd end) {
        return side == 1 ? new Portal(name, end, side2, width, height, open) : new Portal(name, side1, end, width, height, open);
    }

    public Portal withOpen(boolean open) {
        return new Portal(name, side1, side2, width, height, open);
    }
}
