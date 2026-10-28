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
 *   {description}&amp;f{text}{/description}
 *
 *   {values}&amp;#B376E5{key} : &amp;f{value}{/values}
 * </pre>
 * Each block is repeated for every element of its kind and vanishes when there is none; a blank line of the template
 * only shows between two lines of content (consecutive blank runs merge into the longest one). The frame
 * ({@code │ ├ └}) is added to every produced line, the button name has its own template, the click icons a map.
 * A plugin may use a variant ({@code variants.<name>}: any of these keys). Any text may use
 * {@code <smallcaps>...</smallcaps>} ({@link #applyTags}).
 */
public final class Style {

    /** The 4 click actions a button can show. */
    public enum Click { LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT }

    /** One line of content handed to the template. */
    public record Entry(String kind, Map<String, String> fields, boolean selected) {
    }

    private static final String DEFAULT_LORE = """
            {description}&f{text}{/description}

            {values}&#B376E5{key} : &f{value}{/values}

            {list}&8➤ &7{text}{/list}
            {filter}{selected}&f» {text}{/selected}{unselected}&#B376E5{text}{/unselected}{/filter}

            {text}&#B376E5{text}{/text}
            {ok}&#2ECF7B{text}{/ok}
            {ko}&#FF5C5C{text}{/ko}
            {info}&#FFD94D{text}{/info}

            {actions}{icon} &f{label}{/actions}
            """;

    private record Look(String lore, List<String> frame, String name, Map<String, String> icons, boolean framed) {
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
        return new Look(DEFAULT_LORE, List.of("&#FFD94D│ ", "&#FFD94D├ ", "&#FFD94D└ "), "&#FFD94D{text}",
                Map.of("left", "&r&7%img_left_click%", "right", "&r&7%img_right_click%",
                        "shift-left", "&r&7⇧%img_left_click%", "shift-right", "&r&7⇧%img_right_click%"), true);
    }

    private static Look read(ConfigurationSection s, Look fallback) {
        if (s == null) return fallback;
        List<String> frame = s.isList("frame") ? s.getStringList("frame") : fallback.frame();
        if (frame.size() < 3) frame = fallback.frame();
        Map<String, String> icons = new HashMap<>(fallback.icons());
        ConfigurationSection ic = s.getConfigurationSection("icons");
        if (ic != null) for (String k : ic.getKeys(false)) icons.put(k.toLowerCase(Locale.ROOT), ic.getString(k, ""));
        return new Look(s.getString("lore", fallback.lore()), frame, s.getString("name", fallback.name()), icons,
                s.getBoolean("framed", fallback.framed()));
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
        return look().name().replace("{text}", text);
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

    private static final Pattern BLOCK = Pattern.compile("\\{(\\w+)}(.*)\\{/\\1}");

    /**
     * The lore of these entries: the template filled top to bottom, blank lines only between content, then the frame
     * (the last action, or the last line, gets the corner).
     */
    public List<String> lore(List<Entry> entries) {
        return render(entries, look().framed());
    }

    private List<String> render(List<Entry> entries, boolean frame) {
        Map<String, List<Entry>> byKind = new HashMap<>();
        for (Entry e : entries) byKind.computeIfAbsent(e.kind(), k -> new ArrayList<>()).add(e);
        // produced lines (kind, text), blank runs as ("", count)
        List<String[]> rows = new ArrayList<>();
        // blank lines between two blocks with content: runs separated only by vanished blocks merge (longest wins)
        int run = 0, gap = 0;
        for (String line : look().lore().split("\n", -1)) {
            if (line.isBlank()) {
                gap = Math.max(gap, ++run);
                continue;
            }
            run = 0;
            Matcher m = BLOCK.matcher(line);
            if (!m.find()) continue;
            List<Entry> own = byKind.getOrDefault(m.group(1), List.of());
            if (own.isEmpty()) continue;
            if (gap > 0 && !rows.isEmpty()) rows.add(new String[]{"", String.valueOf(gap)});
            gap = 0;
            String prefix = line.substring(0, m.start()), suffix = line.substring(m.end());
            for (Entry e : own) rows.add(new String[]{e.kind(), prefix + fill(m.group(2), e) + suffix});
        }
        List<String[]> lines = new ArrayList<>();
        for (String[] r : rows) {
            if (r[0].isEmpty()) for (int i = 0; i < Integer.parseInt(r[1]); i++) lines.add(new String[]{"", ""});
            else lines.add(r);
        }
        if (!frame) {
            List<String> out = new ArrayList<>();
            for (String[] l : lines) out.add(l[1]);
            return out;
        }
        List<String> f = look().frame();
        List<String> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            boolean last = i == lines.size() - 1;
            boolean action = lines.get(i)[0].equals("actions");
            String prefix = last ? f.get(2) : action ? f.get(1) : f.get(0);
            String text = lines.get(i)[1];
            out.add(text.isEmpty() ? stripEnd(prefix) : prefix + text);
        }
        return out;
    }

    private static final Pattern CONDITION = Pattern.compile("\\{(selected|unselected)}(.*?)\\{/\\1}");

    private static String fill(String template, Entry e) {
        if (e.fields().containsKey("raw")) return e.fields().get("raw");
        Matcher c = CONDITION.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (c.find()) {
            boolean show = c.group(1).equals("selected") == e.selected();
            c.appendReplacement(sb, Matcher.quoteReplacement(show ? c.group(2) : ""));
        }
        c.appendTail(sb);
        String out = sb.toString();
        for (Map.Entry<String, String> field : e.fields().entrySet()) out = out.replace("{" + field.getKey() + "}", field.getValue());
        return out;
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
