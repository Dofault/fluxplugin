package fr.influencraft.flux.api.inventory;

import fr.influencraft.flux.api.text.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lore of a button in the suite's style ({@code plugins/Flux/style.yml}, see {@link Style}). The lines are handed to
 * the style's lore template, which decides their order, colours and spacing (blank lines only between content), then
 * the frame ({@code │}, {@code ├}, {@code └}) is added: changing the file changes every menu of the suite.
 *
 * <pre>
 *   Lore.create().description("Rang de départ.").kv("Salaire", "100").item("1 home").ok("Validé").left("Voir").build()
 * </pre>
 * Long lines are cut by Flux when the item is shown ({@code lore.max-line-length}).
 */
public final class Lore {

    /** Colours of the suite's convention, for text built by hand inside a line. */
    public static final String GOLD = "&#FFD94D", PURPLE = "&#B376E5", GREEN = "&#2ECF7B", RED = "&#f0716f";

    private final Style style;
    private final List<Style.Entry> entries = new ArrayList<>();
    private final List<String> unframed = new ArrayList<>();

    private Lore(Style style) {
        this.style = style;
    }

    public static Lore create() {
        return new Lore(Style.get());
    }

    /** With a plugin's style variant ({@code variants.<name>} of style.yml). */
    public static Lore create(String variant) {
        return new Lore(Style.of(variant));
    }

    private Lore add(String kind, Map<String, String> fields, boolean selected) {
        entries.add(new Style.Entry(kind, fields, selected));
        return this;
    }

    private Lore add(String kind, String text) {
        return add(kind, Map.of("text", text), false);
    }

    // ---------------------------------------------------------------- content

    /** Description paragraph(s) ({@code {description}} block). */
    public Lore description(String... paragraphs) {
        for (String p : paragraphs) add("description", p);
        return this;
    }

    public Lore description(List<String> paragraphs) {
        return description(paragraphs.toArray(new String[0]));
    }

    /** Key/value information ({@code {values}} block, {@code {key}} and {@code {value}}). */
    public Lore kv(String key, Object value) {
        return add("values", Map.of("key", key, "value", String.valueOf(value)), false);
    }

    /** Item of a list (perks, rewards...): {@code {list}} block; a colour code in the text keeps its own colour. */
    public Lore item(String text) {
        return add("list", text);
    }

    public Lore items(List<String> texts) {
        for (String t : texts) item(t);
        return this;
    }

    /** Plain text line ({@code {text}} block). */
    public Lore line(String text) {
        return add("text", text);
    }

    public Lore lines(List<String> texts) {
        for (String t : texts) line(t);
        return this;
    }

    public Lore ok(String text) {
        return add("ok", text);
    }

    public Lore ko(String text) {
        return add("ko", text);
    }

    public Lore info(String text) {
        return add("info", text);
    }

    /** Line written as is, outside the frame, after everything else. */
    public Lore raw(String text) {
        unframed.add(text);
        return this;
    }

    /** Filter options ({@code {filter}} block, the selected one through {@code {selected}}), then next / previous. */
    public Lore filter(List<String> options, int selected) {
        for (int i = 0; i < options.size(); i++) add("filter", Map.of("text", options.get(i)), i == selected);
        return left(style.filterNext()).right(style.filterPrevious());
    }

    /** Filter option already formatted (by {@link Style#filterOption}): placed in the filter block as is. */
    public Lore option(String formatted) {
        return formatted == null || formatted.isBlank() ? this : add("filter", Map.of("raw", formatted), false);
    }

    // ---------------------------------------------------------------- actions (4 at most)

    public Lore left(String label) {
        return action(Style.Click.LEFT, label);
    }

    public Lore right(String label) {
        return action(Style.Click.RIGHT, label);
    }

    public Lore shiftLeft(String label) {
        return action(Style.Click.SHIFT_LEFT, label);
    }

    public Lore shiftRight(String label) {
        return action(Style.Click.SHIFT_RIGHT, label);
    }

    public Lore action(Style.Click click, String label) {
        return add("actions", Map.of("icon", style.icon(click), "label", label), false);
    }

    /** Action line with its own icon and colours. */
    public Lore action(String text) {
        return add("actions", Map.of("raw", text), false);
    }

    // ---------------------------------------------------------------- build

    public List<String> build() {
        List<String> out = new ArrayList<>(style.lore(entries));
        out.addAll(unframed);
        return out;
    }

    // ---------------------------------------------------------------- menu files

    /**
     * Lore of a menu file written with tags: {@code @desc}, {@code @kv Key | Value}, {@code @item}, {@code @text},
     * {@code @ok}, {@code @ko}, {@code @info}, {@code @left}, {@code @right}, {@code @shift-left}, {@code @shift-right},
     * {@code @raw}; a line without a tag is plain text. Returns null when no line has a tag (lore kept as written).
     * {@code @filter} lines are expanded by Flux before (they need the viewer).
     */
    public static List<String> fromTags(List<String> lines, String variant) {
        boolean tagged = false;
        for (String l : lines) {
            if (l.startsWith("@")) {
                tagged = true;
                break;
            }
        }
        if (!tagged) return null;
        Lore lore = create(variant);
        for (String l : lines) {
            String tag = l.startsWith("@") ? l.substring(1).split(" ", 2)[0].toLowerCase(java.util.Locale.ROOT) : "text";
            String arg = l.startsWith("@") ? (l.contains(" ") ? l.substring(l.indexOf(' ') + 1) : "") : l;
            switch (tag) {
                case "desc", "description" -> lore.description(arg);
                case "kv" -> {
                    int bar = arg.indexOf('|');
                    if (bar < 0) lore.kv(arg.trim(), "");
                    else lore.kv(arg.substring(0, bar).trim(), arg.substring(bar + 1).trim());
                }
                case "item" -> lore.item(arg);
                case "ok" -> lore.ok(arg);
                case "ko" -> lore.ko(arg);
                case "info" -> lore.info(arg);
                case "left" -> lore.left(arg);
                case "right" -> lore.right(arg);
                case "shift-left" -> lore.shiftLeft(arg);
                case "shift-right" -> lore.shiftRight(arg);
                case "option" -> lore.option(arg); // pre-formatted filter option
                case "raw" -> lore.raw(arg);
                default -> lore.line(arg);
            }
        }
        return lore.build();
    }
}
