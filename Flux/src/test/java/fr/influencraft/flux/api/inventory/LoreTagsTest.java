package fr.influencraft.flux.api.inventory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An item's lore kept as tags draws the same as built, and its shown form drops exactly the action lines. */
class LoreTagsTest {

    @Test
    void tagsRoundTrip() {
        Lore lore = Lore.create().description("Gives life.").kv("Level", 3).item("a perk").ok("fine").right("Drink");
        assertEquals(lore.build(), Lore.fromTags(lore.tags(), null));
    }

    @Test
    void shownFormDropsActionsOnly() {
        List<String> tags = Lore.create().description("Gives life.").kv("Level", 3).right("Drink").shiftLeft("All").tags();
        List<String> shown = Lore.fromTags(tags, null, false);
        assertEquals(Lore.create().description("Gives life.").kv("Level", 3).build(), shown);
        assertTrue(Lore.isAction("@right Drink"));
        assertTrue(Lore.isAction("@shift-left All"));
        assertFalse(Lore.isAction("@desc right"));
        assertFalse(Lore.isAction("plain text"));
    }
}
