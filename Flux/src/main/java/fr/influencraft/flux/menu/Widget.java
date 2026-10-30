package fr.influencraft.flux.menu;

import java.util.List;

/**
 * Dynamic piece of a menu drawn in its title with the vanilla theme fonts (pack {@code fluxui},
 * {@code Flux/assets/vanilla/gen_ui.py}), section {@code widgets.<id>} of a menu or a page. Positions are window
 * pixels (176 x 221, top left of the chest window). Everything static lives in the background image.
 * <ul>
 *   <li>{@link Text}: menu font (white, 1 px shadow), placeholders resolved, aligned on {@code x}</li>
 *   <li>{@link Image}: an ItemsAdder font image placed at {@code x} (its y is the image's own)</li>
 *   <li>{@link Bar}: fill of a progress bar whose empty frame is in the background, optional text and tooltip</li>
 *   <li>{@link Pager}: page buttons and "page/pages" field of a list, shown only when the list has several pages</li>
 *   <li>{@link Lit}: selected-slot highlight under a slot, when its conditions hold</li>
 *   <li>{@link Slots}: slot frames (added by the {@code theme:} section for every slot the menu uses)</li>
 * </ul>
 */
public sealed interface Widget {

    String id();

    List<Requirement> requirements();

    enum Align { LEFT, CENTER, RIGHT }

    /** {@code ink}: the font without shadow, engraved dark grey on the light window (titles, labels). */
    record Text(String id, int x, int y, Align align, String text, String colour, boolean ink,
                List<Requirement> requirements) implements Widget {
    }

    /** Slot frames drawn under these slots: vanilla (light window) or dark (containers). */
    record Slots(String id, int[] slots, boolean dark, List<Requirement> requirements) implements Widget {
    }

    record Image(String id, String glyph, int x, int width, List<Requirement> requirements) implements Widget {
    }

    /** {@code x, y}: top left of the bar frame; the fill starts 1 px inside. {@code progress / max} in 0..1. */
    record Bar(String id, int x, int y, int width, String progress, double max, String colour, String text,
               List<Requirement> requirements) implements Widget {
    }

    /** Buttons on {@code prev} / {@code next}, field centred between them on their row. */
    record Pager(String id, String list, int prev, int next, List<Requirement> requirements) implements Widget {
    }

    record Lit(String id, int slot, List<Requirement> requirements) implements Widget {
    }
}
