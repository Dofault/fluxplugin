package fr.influencraft.fluxportal;

import fr.influencraft.fluxportal.api.PortalEnd;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameTest {

    private static final double E = 1e-9;

    @Test
    void standingPutsThePlaneOnTheFrontFaceOfTheFloorColumn() {
        // facing south (+z): the plane is the south face of the floor block's column, mid-block across
        PortalEnd south = PortalEnd.standing("w", 10, 64, 20, 0, 4);
        assertEquals(10.5, south.x(), E);
        assertEquals(21.0, south.z(), E);
        assertEquals(67.0, south.y(), E);   // bottom row 65, 4 high: middle at 67
        // facing west (-x): the west face
        PortalEnd west = PortalEnd.standing("w", 10, 64, 20, 1, 5);
        assertEquals(10.0, west.x(), E);
        assertEquals(20.5, west.z(), E);
        assertEquals(67.5, west.y(), E);
        assertEquals(65, new Frame(west, 5, 5).bottomY());
        assertEquals(65, new Frame(south, 5, 4).bottomY());
    }

    @Test
    void walkingInFromTheFrontEnters() {
        Frame f = new Frame(PortalEnd.standing("w", 0, 64, 0, 0, 4), 5, 4);   // plane z = 1, front +z
        // eyes 1.62 above the floor of the hole (65)
        assertTrue(f.entered(0.5, 66.62, 1.2, 0.5, 66.62, 0.9, 0.15));
        // from behind: nothing
        assertFalse(f.entered(0.5, 66.62, 0.9, 0.5, 66.62, 1.2, 0.15));
        // beside the hole (2.5 across is the edge)
        assertFalse(f.entered(3.2, 66.62, 1.2, 3.2, 66.62, 0.9, 0.15));
        assertTrue(f.entered(2.8, 66.62, 1.2, 2.8, 66.62, 0.9, 0.15));
        // above it
        assertFalse(f.entered(0.5, 69.5, 1.2, 0.5, 69.5, 0.9, 0.15));
    }

    @Test
    void goingThroughKeepsLeftAndRightAndComesOutInFront() {
        Frame here = new Frame(PortalEnd.standing("w", 0, 64, 0, 0, 4), 5, 4);      // front +z
        Frame there = new Frame(PortalEnd.standing("w", 100, 70, 100, 3, 4), 5, 4); // front +x
        // just through the plane, one block to the hole's "across" side, walking in (-z)
        double x = 0.5 + 1, z = 0.95;
        Vector out = here.carry(there, x, 65, z);
        assertTrue(there.c(out.getX(), out.getZ()) > 0, "comes out in front of the other side");
        assertEquals(-here.a(x, z), there.a(out.getX(), out.getZ()), E);
        assertEquals(here.b(65), there.b(out.getY()), E);
        Vector speed = here.carryDirection(there, new Vector(0, 0, -0.3));
        assertEquals(0.3, speed.getX(), E);   // walking into one leads out of the other's front
        assertEquals(0, speed.getZ(), E);
        // facing into the hole (yaw 180, north) comes out facing east (-90 = 270)
        assertEquals(90f, here.turnTo(there), E);
    }
}
