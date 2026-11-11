package fr.influencraft.fluxdungeons.dungeon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GalleryPlanTest {

    private static final GalleryPlan.Settings SETTINGS = GalleryPlan.Settings.of(30, 63);

    @Test
    void everyPlanIsWalkableToTheNest() {
        for (long seed = 0; seed < 150; seed++) {
            GalleryPlan p = GalleryPlan.generate(SETTINGS, seed);
            assertTrue(p.walkable(), "seed " + seed);
            assertNotNull(p.entrance);
            assertNotNull(p.nest);
            assertEquals(GalleryPlan.Kind.NEST, p.nest.kind());
            assertEquals(1, p.nest.level(), "the nest is on the lower level");
        }
    }

    @Test
    void airStaysInsideTheShellAndUnderTheArena() {
        for (long seed = 0; seed < 60; seed++) {
            GalleryPlan p = GalleryPlan.generate(SETTINGS, seed);
            int s = SETTINGS.size();
            for (int y = SETTINGS.bottom(); y <= SETTINGS.top(); y++) {
                for (int z = 0; z < s; z++) {
                    for (int x = 0; x < s; x++) {
                        if (!p.air(x, y, z)) continue;
                        assertTrue(x >= 2 && z >= 2 && x <= s - 3 && z <= s - 3, "shell, seed " + seed);
                        assertTrue(y <= p.roof() && y > SETTINGS.bottom(), "roof, seed " + seed + " y " + y);
                    }
                }
            }
        }
    }

    @Test
    void trailRunsFromTheEntranceToTheNest() {
        for (long seed = 0; seed < 60; seed++) {
            GalleryPlan p = GalleryPlan.generate(SETTINGS, seed);
            assertFalse(p.trail.isEmpty());
            GalleryPlan.Point first = p.trail.get(0), last = p.trail.get(p.trail.size() - 1);
            assertTrue(Math.hypot(first.x() - p.entrance.x(), first.z() - p.entrance.z()) < 0.01, "starts at the entrance");
            assertTrue(Math.hypot(last.x() - p.nest.x(), last.z() - p.nest.z()) < 0.01, "ends at the nest");
            for (int i = 1; i < p.trail.size(); i++) {
                GalleryPlan.Point a = p.trail.get(i - 1), b = p.trail.get(i);
                assertTrue(Math.hypot(a.x() - b.x(), a.z() - b.z()) < 1.0, "no jump along the trail, seed " + seed);
            }
        }
    }

    @Test
    void networkHasDeadEndsAndLoops() {
        GalleryPlan p = GalleryPlan.generate(SETTINGS, 42);
        assertEquals(3, p.deadEnds.size());
        assertTrue(p.tunnels.size() >= 16 + 2 + 2, "two trees of 8 links, loops, ramps, spurs");
        assertTrue(p.dug() > 2000, "a real network");
    }

    @Test
    void printsAMap() {
        GalleryPlan p = GalleryPlan.generate(SETTINGS, 7);
        System.out.println("upper level\n" + p.map(SETTINGS.upper() + 1) + "lower level\n" + p.map(SETTINGS.lower() + 1)
                + "dug " + p.dug() + ", trail " + p.trail.size() + " points");
    }
}
