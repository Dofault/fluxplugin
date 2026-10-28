package fr.influencraft.flux.api.inventory;

import fr.influencraft.flux.api.text.Style;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Lore of a button in the suite's style ({@code plugins/Flux/style.yml}, see {@link Style}). Sections always come in
 * the same order, whatever order the calls were made in: description, key/values, list, text and states, actions,
 * a framed blank line between two sections. The frame ({@code │}, {@code ├}, {@code └}), colours and click icons come
 * from the style, so changing the file changes every menu of the suite.
 *
 * <pre>
 *   Lore.create().description("Rang de départ.").kv("Salaire", "100").item("1 home").ok("Validé").left("Voir").build()
 * </pre>
 * Long lines are cut by Flux when the item is shown ({@code lore.max-line-length}).
 */
public final class Lore {

    /** Colours of the suite's convention, for text built by hand inside a line. */
    public static final String GOLD = "&#FFD94D", PURPLE = "&#B376E5", GREEN = "&#2ECF7B", RED = "&#f0716f";

    private enum Section { DESCRIPTION, VALUES, LIST, TEXT }

    private final Style style;
    private final Map<Section, List<String>> sections = new EnumMap<>(Section.class);
    private final List<String> actions = new ArrayList<>();
    private final List<String> unframed = new ArrayList<>();

    private Lore(Style style) {
        this.style = style;
        for (Section s : Section.values()) sections.put(s, new ArrayList<>());
    }

    public static Lore create() {
        return new Lore(Style.get());
    }

    /** With a plugin's style variant ({@code variants.<name>} of style.yml). */
    public static Lore create(String variant) {
        return new Lore(Style.of(variant));
    }

    // ---------------------------------------------------------------- content

    /** Description paragraph(s), description colour (white by default). */
    public Lore description(String... paragraphs) {
        for (String p : paragraphs) sections.get(Section.DESCRIPTION).add(style.description(p));
        return this;
    }

    public Lore description(List<String> paragraphs) {
        return description(paragraphs.toArray(new String[0]));
    }

    /** Key/value information ({@code Niveau : 12}), value always in the value colour. */
    public Lore kv(String key, Object value) {
        sections.get(Section.VALUES).add(style.keyValue(key, String.valueOf(value)));
        return this;
    }

    /** Item of a list (perks, rewards...): the list format; a colour code in the text keeps its own colour. */
    public Lore item(String text) {
        sections.get(Section.LIST).add(style.item(text));
        return this;
    }

    public Lore items(List<String> texts) {
        for (String t : texts) item(t);
        return this;
    }

    /** Plain text line (text colour, a colour code in the text takes over). */
    public Lore line(String text) {
        sections.get(Section.TEXT).add(text.isEmpty() ? "" : style.text(text));
        return this;
    }

    public Lore lines(List<String> texts) {
        for (String t : texts) line(t);
        return this;
    }

    public Lore ok(String text) {
        sections.get(Section.TEXT).add(style.state("ok", text));
        return this;
    }

    public Lore ko(String text) {
        sections.get(Section.TEXT).add(style.state("ko", text));
        return this;
    }

    public Lore info(String text) {
        sections.get(Section.TEXT).add(style.state("info", text));
        return this;
    }

    /** Line written as is, outside the frame, after everything else. */
    public Lore raw(String text) {
        unframed.add(text);
        return this;
    }

    /** Filter options: the selected one marked, then the next / previous actions (left / right click). */
    public Lore filter(List<String> options, int selected) {
        for (int i = 0; i < options.size(); i++) sections.get(Section.LIST).add(style.filterOption(options.get(i), i == selected));
        return left(style.filterNext()).right(style.filterPrevious());
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
        actions.add(style.action(click, label));
        return this;
    }

    /** Action line with its own icon and colours. */
    public Lore action(String text) {
        actions.add(text);
        return this;
    }

    // ---------------------------------------------------------------- build

    public List<String> build() {
        List<String> body = new ArrayList<>();
        for (Section s : Section.values()) {
            List<String> lines = sections.get(s);
            if (lines.isEmpty()) continue;
            if (!body.isEmpty() && style.sectionGap()) body.add("");
            body.addAll(lines);
        }
        List<String> out = new ArrayList<>(body.size() + actions.size() + unframed.size());
        for (int i = 0; i < body.size(); i++) {
            boolean last = i == body.size() - 1 && actions.isEmpty();
            out.add((last ? style.frameCorner() : style.frameLine()) + body.get(i));
        }
        if (!body.isEmpty() && !actions.isEmpty() && style.sectionGap()) out.add(stripTrailingSpace(style.frameLine()));
        for (int i = 0; i < actions.size(); i++) {
            out.add((i == actions.size() - 1 ? style.frameCorner() : style.frameBranch()) + actions.get(i));
        }
        out.addAll(unframed);
        return out;
    }

    private static String stripTrailingSpace(String s) {
        return s.endsWith(" ") ? s.substring(0, s.length() - 1) : s;
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
                case "option" -> lore.sections.get(Section.LIST).add(arg); // pre-formatted filter option
                case "raw" -> lore.raw(arg);
                default -> lore.line(arg);
            }
        }
        return lore.build();
    }
}
