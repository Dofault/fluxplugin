package fr.influencraft.fluxportal;

import fr.influencraft.fluxportal.api.PortalEnd;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalMathTest {

    private static final double R = 2;

    @Test
    void crossingNeedsTheHoleAndBothSides() {
        PortalEnd e = new PortalEnd("w", 0, 64, 0, 0);   // front faces south (+z)
        assertTrue(PortalMath.crosses(e, new Vector(0, 64, 0.5), new Vector(0, 64, -0.5), R), "through the middle");
        assertTrue(PortalMath.crosses(e, new Vector(1, 65, -0.5), new Vector(1, 65, 0.5), R), "from the back");
        assertFalse(PortalMath.crosses(e, new Vector(3, 64, 0.5), new Vector(3, 64, -0.5), R), "beside the ring");
        assertFalse(PortalMath.crosses(e, new Vector(0, 64, 1), new Vector(0, 64, 0.2), R), "not through yet");
        assertFalse(PortalMath.crosses(e, new Vector(0, 64, 0.5), new Vector(1, 64, 0.5), R), "along it");
    }

    @Test
    void walkingInFrontComesOutOfTheOtherFront() {
        PortalEnd a = new PortalEnd("w", 0, 64, 0, 0);       // front south
        PortalEnd b = new PortalEnd("w", 100, 70, 50, 90);   // front west (-x)
        // walking north (into a's front), facing north (yaw 180), 0.5 right of the middle seen from the front
        Vector point = new Vector(0.5, 64.3, 0);
        PortalMath.Exit out = PortalMath.travel(a, b, point, 180, new Vector(0, 0, -0.3));
        assertEquals(-0.3, out.velocity().getX(), 1e-9, "goes out west, b's front");
        assertEquals(0, out.velocity().getZ(), 1e-9);
        assertEquals(90, out.yaw(), 1e-4, "faces west");
        assertTrue(out.point().getX() < 100, "in front of b");
        assertEquals(70.3, out.point().getY(), 1e-9, "same height on the ring");
    }

    /** A thousand random rings and moves: the speed keeps its size, the traveller ends out of the exit's plane. */
    @Test
    void randomTravelsKeepSpeedAndLeaveTheRing() {
        Random r = new Random(5);
        for (int i = 0; i < 1000; i++) {
            PortalEnd a = new PortalEnd("w", r.nextDouble() * 100, 64, r.nextDouble() * 100, (float) (r.nextDouble() * 360 - 180));
            PortalEnd b = new PortalEnd("w", r.nextDouble() * 100, 80, r.nextDouble() * 100, (float) (r.nextDouble() * 360 - 180));
            Vector v = new Vector(r.nextGaussian(), r.nextGaussian() * 0.2, r.nextGaussian());
            Vector point = a.center().add(a.right().multiply(r.nextDouble() - 0.5)).add(new Vector(0, r.nextDouble() - 0.5, 0));
            PortalMath.Exit out = PortalMath.travel(a, b, point, 0, v);
            assertEquals(v.length(), out.velocity().length(), 1e-9);
            double depth = out.point().clone().subtract(b.center()).dot(b.normal());
            assertEquals(PortalMath.STEP_OUT, Math.abs(depth), 1e-6);   // yaws are floats
            double along = out.velocity().dot(b.normal());
            assertTrue(along * depth >= 0 || Math.abs(along) < 1e-9, "comes out on the side he moves to");
        }
    }

    @Test
    void goingThereAndBackLandsWhereHeStarted() {
        PortalEnd a = new PortalEnd("w", 10, 64, 10, 37);
        PortalEnd b = new PortalEnd("w", -40, 70, 5, -120);
        Vector v = PortalEnd.direction(37 + 180).multiply(0.4);
        PortalMath.Exit there = PortalMath.travel(a, b, a.center().add(a.right().multiply(0.6)), 37 + 180, v);
        PortalMath.Exit back = PortalMath.travel(b, a, there.point(), there.yaw(), there.velocity().clone().multiply(-1));
        Vector flat = back.point().clone().subtract(a.center());
        flat.subtract(a.normal().multiply(flat.dot(a.normal())));
        assertEquals(0.6, flat.length(), 1e-9, "same place on the ring");
    }
}
