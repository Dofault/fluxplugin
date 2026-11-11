package fr.influencraft.fluxdungeons.dungeon;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfferingTest {

    private static Offering herbarium(boolean ordered) {
        return new Offering(1, 64, 2, List.of(
                new Offering.Entry(Material.BROWN_MUSHROOM, 1, "la chair brune du sous-bois"),
                new Offering.Entry(Material.RED_MUSHROOM, 1, "le chapeau rouge"),
                new Offering.Entry(Material.GLOW_BERRIES, 2, "la baie qui luit")), ordered, "Alambic", "fdgrant_tisane_sylve", "");
    }

    @Test
    void unorderedTakesAnyAskedItemUpToItsAmount() {
        Offering o = herbarium(false);
        int[] p = o.fresh();
        int[] taken = new int[1];
        assertEquals(Offering.Result.ACCEPTED, o.offer(p, Material.GLOW_BERRIES, 5, taken));
        assertEquals(2, taken[0], "never more than asked");
        assertEquals(Offering.Result.ENOUGH, o.offer(p, Material.GLOW_BERRIES, 1, taken));
        assertEquals(0, taken[0]);
        assertEquals(Offering.Result.WRONG, o.offer(p, Material.POPPY, 1, taken));
        assertEquals(Offering.Result.ACCEPTED, o.offer(p, Material.RED_MUSHROOM, 1, taken));
        assertFalse(o.complete(p));
        assertEquals(Offering.Result.COMPLETE, o.offer(p, Material.BROWN_MUSHROOM, 1, taken));
        assertTrue(o.complete(p));
    }

    @Test
    void orderedRefusesAnItemBeforeItsTurn() {
        Offering o = herbarium(true);
        int[] p = o.fresh();
        int[] taken = new int[1];
        assertEquals(Offering.Result.NOT_YET, o.offer(p, Material.RED_MUSHROOM, 1, taken));
        assertEquals(0, p[1], "a refused item changes nothing");
        assertEquals(Offering.Result.ACCEPTED, o.offer(p, Material.BROWN_MUSHROOM, 1, taken));
        assertEquals(Offering.Result.NOT_YET, o.offer(p, Material.GLOW_BERRIES, 1, taken));
        assertEquals(Offering.Result.ACCEPTED, o.offer(p, Material.RED_MUSHROOM, 1, taken));
        assertEquals(Offering.Result.ACCEPTED, o.offer(p, Material.GLOW_BERRIES, 1, taken));
        assertEquals(Offering.Result.COMPLETE, o.offer(p, Material.GLOW_BERRIES, 1, taken));
    }

    @Test
    void readsBackWhatItWrites() {
        Offering o = herbarium(true);
        Offering back = Offering.read(o.toMap());
        assertNotNull(back);
        assertEquals(o, back);
        assertNull(Offering.read(Map.of("items", List.of())), "no altar, no offering");
        assertNull(Offering.read(Map.of("altar", List.of(0, 0, 0), "items", List.of(Map.of("item", "not_a_block")))));
    }
}
