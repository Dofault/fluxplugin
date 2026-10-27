package fr.influencraft.flux.api.inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * Lore of a menu button in the suite's style: description lines after a gold {@code │} (the last one becomes
 * {@code └}), then the actions ({@code ├} / {@code └}, click icon then label). Built by code for list entries,
 * the same look as the generated menus:
 *
 * <pre>
 *   Lore.create().line("Niveau : &f12").line("&#2ECF7BMétier actif.").left("Voir le métier").build()
 * </pre>
 * Long lines are cut by Flux when the item is shown ({@code lore.max-line-length}).
 */
public final class Lore {

    public static final String GOLD = "&#FFD94D", PURPLE = "&#B376E5", GREEN = "&#2ECF7B", RED = "&#f0716f";
    private static final String LEFT = "&r&7%img_left_click% &f", RIGHT = "&r&7%img_right_click% &f";

    private final List<String> lines = new ArrayList<>();
    private final List<String> actions = new ArrayList<>();
    private final List<Boolean> framed = new ArrayList<>();

    private Lore() {
    }

    public static Lore create() {
        return new Lore();
    }

    /** Description line, purple by default (a colour code in the text takes over). */
    public Lore line(String text) {
        lines.add(PURPLE + text);
        framed.add(true);
        return this;
    }

    public Lore lines(List<String> texts) {
        for (String t : texts) line(t);
        return this;
    }

    /** Line written as is, without the {@code │} frame. */
    public Lore raw(String text) {
        lines.add(text);
        framed.add(false);
        return this;
    }

    /** Main action (left click). */
    public Lore left(String label) {
        actions.add(LEFT + label);
        return this;
    }

    /** Secondary action (right click). */
    public Lore right(String label) {
        actions.add(RIGHT + label);
        return this;
    }

    /** Action line with its own icon and colours. */
    public Lore action(String text) {
        actions.add(text);
        return this;
    }

    public List<String> build() {
        int lastFramed = -1;
        if (actions.isEmpty()) {
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (framed.get(i)) {
                    lastFramed = i;
                    break;
                }
            }
        }
        List<String> out = new ArrayList<>(lines.size() + actions.size());
        for (int i = 0; i < lines.size(); i++) {
            out.add(framed.get(i) ? GOLD + (i == lastFramed ? "└ " : "│ ") + lines.get(i) : lines.get(i));
        }
        for (int i = 0; i < actions.size(); i++) {
            out.add(GOLD + (i == actions.size() - 1 ? "└ " : "├ ") + actions.get(i));
        }
        return out;
    }
}
