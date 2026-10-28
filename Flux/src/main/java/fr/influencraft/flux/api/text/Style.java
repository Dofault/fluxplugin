package fr.influencraft.flux.api.text;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The look of every button of the suite, set once in {@code plugins/Flux/style.yml} (look only, never words: those
 * live in the language files): lore frame, colours of the name,
 * the description, the plain text, key/value lines, list items and states, the click actions, the filters. Any
 * text may use {@code <smallcaps>...</smallcaps>} ({@link #applyTags}). A plugin may use a variant ({@code variants.<name>} of the file, only the keys it changes) to keep its own
 * colours. Read by {@link fr.influencraft.flux.api.inventory.Lore} (code) and by the {@code @tags} of menu files.
 */
public final class Style {

    /** The 4 click actions a button can show. */
    public enum Click { LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT }

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("frame.enabled", "true"), Map.entry("frame.line", "&#FFD94D│ "),
            Map.entry("frame.branch", "&#FFD94D├ "), Map.entry("frame.corner", "&#FFD94D└ "),
            Map.entry("name", "&#FFD94D%text%"), Map.entry("text", "&#B376E5%text%"), Map.entry("description", "&f%text%"),
            Map.entry("key-value", "&#B376E5%key% : &f%value%"), Map.entry("item", "&8➤ &7%item%"),
            Map.entry("states.ok", "&#2ECF7B%text%"), Map.entry("states.ko", "&#FF5C5C%text%"), Map.entry("states.info", "&#FFD94D%text%"),
            Map.entry("actions.left", "&r&7%img_left_click% &f%label%"),
            Map.entry("actions.right", "&r&7%img_right_click% &f%label%"),
            Map.entry("actions.shift-left", "&r&7⇧%img_left_click% &f%label%"),
            Map.entry("actions.shift-right", "&r&7⇧%img_right_click% &f%label%"),
            Map.entry("filter.selected", "&f» %label%"), Map.entry("filter.unselected", "&#B376E5%label%"),
            Map.entry("sections.gap", "true"));

    private static Map<String, String> base = new HashMap<>(DEFAULTS);
    private static final Map<String, Map<String, String>> VARIANTS = new HashMap<>();
    private static final Style DEFAULT = new Style(null);

    private final String variant;

    private Style(String variant) {
        this.variant = variant;
    }

    /** The suite's style. */
    public static Style get() {
        return DEFAULT;
    }

    /** A plugin's variant (falls back on the suite's style for every key it does not set). */
    public static Style of(String variant) {
        return variant == null || variant.isBlank() ? DEFAULT : new Style(variant.toLowerCase(Locale.ROOT));
    }

    /** Called by Flux with style.yml. */
    public static void configure(ConfigurationSection file) {
        Map<String, String> read = new HashMap<>(DEFAULTS);
        VARIANTS.clear();
        if (file != null) {
            for (String key : file.getKeys(true)) {
                if (key.startsWith("variants") || file.isConfigurationSection(key)) continue;
                read.put(key, file.getString(key));
            }
            ConfigurationSection v = file.getConfigurationSection("variants");
            if (v != null) {
                for (String name : v.getKeys(false)) {
                    ConfigurationSection s = v.getConfigurationSection(name);
                    if (s == null) continue;
                    Map<String, String> own = new HashMap<>();
                    for (String key : s.getKeys(true)) if (!s.isConfigurationSection(key)) own.put(key, s.getString(key));
                    VARIANTS.put(name.toLowerCase(Locale.ROOT), own);
                }
            }
        }
        base = read;
    }

    public String value(String key) {
        if (variant != null) {
            Map<String, String> own = VARIANTS.get(variant);
            if (own != null && own.containsKey(key)) return own.get(key);
        }
        return base.getOrDefault(key, "");
    }

    private boolean flag(String key) {
        return Boolean.parseBoolean(value(key));
    }

    // ---------------------------------------------------------------- pieces

    public boolean framed() {
        return flag("frame.enabled");
    }

    public String frameLine() {
        return framed() ? value("frame.line") : "";
    }

    public String frameBranch() {
        return framed() ? value("frame.branch") : "";
    }

    public String frameCorner() {
        return framed() ? value("frame.corner") : "";
    }

    public boolean sectionGap() {
        return flag("sections.gap");
    }

    /** A format of the file applied to a text: {@code %text%} replaced, or the text appended (colour-only format). */
    private String fill(String key, String text) {
        String format = value(key);
        return format.contains("%text%") ? format.replace("%text%", text) : format + text;
    }

    /** Button name (a colour in the name itself wins over the format's). */
    public String name(String text) {
        return fill("name", text);
    }

    public String description(String text) {
        return fill("description", text);
    }

    public String text(String text) {
        return fill("text", text);
    }

    public String keyValue(String key, String val) {
        return value("key-value").replace("%key%", key).replace("%value%", val);
    }

    public String item(String text) {
        return value("item").replace("%item%", text).replace("%text%", text);
    }

    public String state(String state, String text) {
        return fill("states." + state, text);
    }

    public String action(Click click, String label) {
        return value("actions." + click.name().toLowerCase(Locale.ROOT).replace('_', '-')).replace("%label%", label);
    }

    public String filterOption(String label, boolean selected) {
        return value(selected ? "filter.selected" : "filter.unselected").replace("%label%", label);
    }

    /** Label of the « next filter » action: a word, so it comes from the language file (filter-next), not the style. */
    public String filterNext() {
        return fr.influencraft.flux.api.util.Format.word("filter-next");
    }

    public String filterPrevious() {
        return fr.influencraft.flux.api.util.Format.word("filter-previous");
    }

    // ---------------------------------------------------------------- small caps

    private static final java.util.regex.Pattern SMALL_CAPS = java.util.regex.Pattern.compile("<smallcaps>(.*?)</smallcaps>");

    /**
     * {@code <smallcaps>...</smallcaps>} anywhere (style formats, names, menu texts, messages): the inside in small
     * caps. Applied by Flux on every displayed line, after the placeholders.
     */
    public static String applyTags(String text) {
        if (text == null || text.indexOf("<smallcaps>") < 0) return text;
        java.util.regex.Matcher m = SMALL_CAPS.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(smallCaps(m.group(1))));
        m.appendTail(out);
        return out.toString();
    }

    private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

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

    /** Lines of a list of keys, for tooling. */
    public static List<String> keys() {
        return List.copyOf(DEFAULTS.keySet());
    }
}
