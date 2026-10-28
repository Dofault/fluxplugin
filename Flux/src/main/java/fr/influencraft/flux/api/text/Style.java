package fr.influencraft.flux.api.text;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The look of every button of the suite, set in {@code plugins/Flux/style.yml} (look only, never words: those live in
 * the language files). The lore is one template, read top to bottom like an HTML page the data is poured into:
 * <pre>
 *   &lt;description&gt;&amp;f{text}&lt;/description&gt;
 *
 *   &lt;values&gt;&amp;#B376E5{key} : &amp;f{value}&lt;/values&gt;
 * </pre>
 * Tags are the structure, braces the data. Each block is repeated for every element of its kind and vanishes when there is none; a blank line of the template
 * only shows between two lines of content (consecutive blank runs merge into the longest one). The frame
 * ({@code │ ├ └}) is added to every produced line, the button name has its own template, the click icons a map.
 * A plugin may use a variant ({@code variants.<name>}: any of these keys). Any text may use
 * {@code <smallcaps>...</smallcaps>} ({@link #applyTags}).
 */
public final class Style {

    /** A block: {@code <kind>...</kind>}, only the kinds below (other tags such as {@code <smallcaps>} stay text). */
    private static final Pattern BLOCK = Pattern.compile("<(description|values|list|filter|text|ok|ko|info|actions)>(.*)</\\1>");
    private static final Pattern CONDITION = Pattern.compile("<(selected|unselected)>(.*?)</\\1>");

    /** The 4 click actions a button can show. */
    public enum Click { LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT }

    /** One line of content handed to the template. */
    public record Entry(String kind, Map<String, String> fields, boolean selected) {
    }

    private static final String DEFAULT_LORE = """
            <description>&f{text}</description>

            <values>&#B376E5{key} : &f{value}</values>

            <list>&8➤ &7{text}</list>
            <filter><selected>&f» {text}</selected><unselected>&#B376E5{text}</unselected></filter>

            <text>&#B376E5{text}</text>
            <ok>&#2ECF7B{text}</ok>
            <ko>&#FF5C5C{text}</ko>
            <info>&#FFD94D{text}</info>

            <actions>{icon} &f{label}</actions>
            """;

    /** A line of the template, parsed once: a blank line (kind null) or a block, its body for selected / other entries. */
    private record Block(String kind, String prefix, String selected, String unselected, String suffix) {
    }

    /**
     * Frame of a button (optional): {@code name} goes before the button name, {@code line} before the lines,
     * {@code branch} before the actions, {@code corner} before the last line.
     */
    public record Frame(boolean enabled, String name, String line, String branch, String corner) {
    }

    /**
     * {@code separator}: what each blank line of the template becomes (only between content); null = a blank line
     * (the frame's line alone when framed).
     */
    private record Look(String lore, List<Block> blocks, Frame frame, String separator, String name, Map<String, String> icons) {
        Look(String lore, Frame frame, String separator, String name, Map<String, String> icons) {
            this(lore, parse(lore), frame, separator, name, icons);
        }
    }

    /** Template to blocks, done when style.yml is read; lines that are neither blank nor a block are ignored. */
    private static List<Block> parse(String lore) {
        List<Block> out = new ArrayList<>();
        for (String line : lore.split("\n", -1)) {
            if (line.isBlank()) {
                out.add(new Block(null, "", "", "", ""));
                continue;
            }
            Matcher m = BLOCK.matcher(line);
            if (!m.find()) continue;
            out.add(new Block(m.group(1), line.substring(0, m.start()), condition(m.group(2), true),
                    condition(m.group(2), false), line.substring(m.end())));
        }
        return List.copyOf(out);
    }

    /** Keeps the inside of {@code <selected>} or {@code <unselected>}, drops the other. */
    private static String condition(String body, boolean selected) {
        Matcher c = CONDITION.matcher(body);
        StringBuilder sb = new StringBuilder();
        while (c.find()) c.appendReplacement(sb, Matcher.quoteReplacement(c.group(1).equals("selected") == selected ? c.group(2) : ""));
        c.appendTail(sb);
        return sb.toString();
    }

    private static Look base = defaults();
    private static final Map<String, Look> VARIANTS = new HashMap<>();
    private static final Style DEFAULT = new Style(null);

    private final String variant;

    private Style(String variant) {
        this.variant = variant;
    }

    public static Style get() {
        return DEFAULT;
    }

    /** A plugin's variant (the suite's look for every key it does not set). */
    public static Style of(String variant) {
        return variant == null || variant.isBlank() ? DEFAULT : new Style(variant.toLowerCase(Locale.ROOT));
    }

    private static Look defaults() {
        return new Look(DEFAULT_LORE, new Frame(true, "", "&#FFD94D│ ", "&#FFD94D├ ", "&#FFD94D└ "), null, "&#FFD94D{text}",
                Map.of("left", "&r&7%img_left_click%", "right", "&r&7%img_right_click%",
                        "shift-left", "&r&7%img_shift%&8+&7%img_left_click%", "shift-right", "&r&7%img_shift%&8+&7%img_right_click%"));
    }

    private static Look read(ConfigurationSection s, Look fallback) {
        if (s == null) return fallback;
        Frame frame = readFrame(s, fallback.frame());
        String separator = s.contains("separator") ? s.getString("separator", "") : fallback.separator();
        if (separator != null && separator.isEmpty()) separator = null;
        Map<String, String> icons = new HashMap<>(fallback.icons());
        ConfigurationSection ic = s.getConfigurationSection("icons");
        if (ic != null) for (String k : ic.getKeys(false)) icons.put(k.toLowerCase(Locale.ROOT), ic.getString(k, ""));
        return new Look(s.getString("lore", fallback.lore()), frame, separator, s.getString("name", fallback.name()), icons);
    }

    private static Frame readFrame(ConfigurationSection s, Frame f) {
        if (s.isBoolean("frame")) return new Frame(s.getBoolean("frame"), f.name(), f.line(), f.branch(), f.corner());
        ConfigurationSection c = s.getConfigurationSection("frame");
        if (c == null) return f;
        return new Frame(c.getBoolean("enabled", f.enabled()), c.getString("name", f.name()), c.getString("line", f.line()),
                c.getString("branch", f.branch()), c.getString("corner", f.corner()));
    }

    /** The suite's frame (Flux keeps it on wrapped lines). */
    public static Frame frame() {
        return base.frame();
    }

    /** Called by Flux with style.yml. */
    public static void configure(ConfigurationSection file) {
        base = read(file, defaults());
        VARIANTS.clear();
        ConfigurationSection v = file == null ? null : file.getConfigurationSection("variants");
        if (v != null) for (String name : v.getKeys(false)) VARIANTS.put(name.toLowerCase(Locale.ROOT), read(v.getConfigurationSection(name), base));
    }

    private Look look() {
        Look own = variant == null ? null : VARIANTS.get(variant);
        return own == null ? base : own;
    }

    // ---------------------------------------------------------------- pieces

    /** Button name through the name template (a colour in the name itself wins). */
    public String name(String text) {
        Frame f = look().frame();
        return (f.enabled() ? f.name() : "") + look().name().replace("{text}", text);
    }

    public String icon(Click click) {
        return look().icons().getOrDefault(click.name().toLowerCase(Locale.ROOT).replace('_', '-'), "");
    }

    /** One filter option rendered by the filter block (for plugins that hand pre-formatted options). */
    public String filterOption(String text, boolean selected) {
        List<String> out = render(List.of(new Entry("filter", Map.of("text", text), selected)), false);
        return out.isEmpty() ? text : out.get(0);
    }

    /** Label of the « next filter » action: a word, from the language files (filter-next), not the style. */
    public String filterNext() {
        return fr.influencraft.flux.api.util.Format.word("filter-next");
    }

    public String filterPrevious() {
        return fr.influencraft.flux.api.util.Format.word("filter-previous");
    }

    // ---------------------------------------------------------------- template


    /**
     * The lore of these entries: the template filled top to bottom, blank lines only between content, then the frame
     * (the last action, or the last line, gets the corner).
     */
    public List<String> lore(List<Entry> entries) {
        return render(entries, look().frame().enabled());
    }

    private List<String> render(List<Entry> entries, boolean frame) {
        Map<String, List<Entry>> byKind = new HashMap<>();
        for (Entry e : entries) byKind.computeIfAbsent(e.kind(), k -> new ArrayList<>()).add(e);
        // lines as (kind, text); blank lines only between content, runs separated by vanished blocks merge (longest wins)
        List<String[]> lines = new ArrayList<>();
        int run = 0, gap = 0;
        for (Block b : look().blocks()) {
            if (b.kind() == null) {
                gap = Math.max(gap, ++run);
                continue;
            }
            run = 0;
            List<Entry> own = byKind.get(b.kind());
            if (own == null) continue;
            if (!lines.isEmpty()) for (int i = 0; i < gap; i++) lines.add(new String[]{"gap", ""});
            gap = 0;
            for (Entry e : own) lines.add(new String[]{e.kind(), b.prefix() + fill(e.selected() ? b.selected() : b.unselected(), e) + b.suffix()});
        }
        String separator = look().separator();
        Frame f = look().frame();
        List<String> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String kind = lines.get(i)[0], text = lines.get(i)[1];
            if (kind.equals("gap") && separator != null) {
                out.add(separator);
                continue;
            }
            if (!frame) {
                out.add(text);
                continue;
            }
            boolean last = i == lines.size() - 1;
            String prefix = last ? f.corner() : kind.equals("actions") ? f.branch() : f.line();
            out.add(text.isEmpty() ? stripEnd(prefix) : prefix + text);
        }
        return out;
    }

    /**
     * The body with the entry's fields; {@code <base>} inside a field means « back to the colour this field starts
     * with in the template » (e.g. after a value written in another colour in the middle of a description).
     */
    private static String fill(String body, Entry e) {
        String raw = e.fields().get("raw");
        if (raw != null) return raw;
        String out = body;
        for (Map.Entry<String, String> field : e.fields().entrySet()) {
            String key = "{" + field.getKey() + "}", value = field.getValue();
            int at = body.indexOf(key);
            if (value.contains("<base>")) value = value.replace("<base>", at < 0 ? "" : colourAt(body.substring(0, at)));
            out = out.replace(key, value);
        }
        return out;
    }

    private static final Pattern CODE = Pattern.compile("&#[0-9a-fA-F]{6}|&[0-9a-fk-orA-FK-OR]");

    /** Colour and formats in force at the end of a template piece. */
    private static String colourAt(String text) {
        String colour = "";
        StringBuilder formats = new StringBuilder();
        Matcher m = CODE.matcher(text);
        while (m.find()) {
            String code = m.group();
            char c = Character.toLowerCase(code.charAt(1));
            if (code.startsWith("&#") || Character.isDigit(c) || (c >= 'a' && c <= 'f') || c == 'r') {
                colour = c == 'r' ? "&r" : code;
                formats.setLength(0);
            } else {
                formats.append(code);
            }
        }
        return colour + formats;
    }

    private static String stripEnd(String s) {
        return s.endsWith(" ") ? s.substring(0, s.length() - 1) : s;
    }

    // ---------------------------------------------------------------- small caps

    private static final Pattern SMALL_CAPS = Pattern.compile("<smallcaps>(.*?)</smallcaps>");
    private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    /** {@code <smallcaps>...</smallcaps>} anywhere: the inside in small caps. Applied by Flux on every shown line. */
    public static String applyTags(String text) {
        if (text == null || text.indexOf("<smallcaps>") < 0) return text;
        Matcher m = SMALL_CAPS.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(smallCaps(m.group(1))));
        m.appendTail(out);
        return out.toString();
    }

    /** Lower-case letters to small caps, colour codes, %placeholders% and :glyphs: untouched. */
    public static String smallCaps(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean placeholder = false, glyph = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '%') placeholder = !placeholder;
            if (ch == ':' && !placeholder) glyph = !glyph && text.indexOf(':', i + 1) > i;
            if ((ch == '&' || ch == '§') && i + 1 < text.length()) {
                int end = text.charAt(i + 1) == '#' ? Math.min(text.length(), i + 8) : i + 2;
                out.append(text, i, end);
                i = end - 1;
                continue;
            }
            if (!placeholder && !glyph && ch >= 'a' && ch <= 'z') out.append(SMALL.charAt(ch - 'a'));
            else out.append(ch);
        }
        return out.toString();
    }
}
