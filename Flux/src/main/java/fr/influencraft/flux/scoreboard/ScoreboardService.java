package fr.influencraft.flux.scoreboard;

import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.flux.api.client.Focus;
import fr.influencraft.flux.api.client.Sidebar;
import fr.influencraft.flux.api.scoreboard.Scoreboard;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.hud.TextWidths;
import fr.influencraft.flux.api.util.PlayerSettings;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The scoreboard on the right of the screen, drawn by Flux from {@code plugins/Flux/scoreboard.yml} in place of the
 * one of Typewriter (whose sidebar is held back while Flux shows its own, {@link Sidebar#claim}).
 *
 * <ul>
 *   <li>Sections of lines, each shown when what it {@code requires} is there (a list with rows, or a text that is not
 *   blank once its placeholders are read). Lines may repeat over a list ({@code each}: a plugin's
 *   {@link Scoreboard#list}, or a placeholder cut in pieces, {@code lists} of the file).</li>
 *   <li>Colours by name ({@code <frame>...</frame>}, {@code colours} of the file): changing one changes every line.</li>
 *   <li>{@code {branch}}: "├" on every line of a section that has it, "└" on the last one (the frame of the menus).</li>
 *   <li>Width in pixels: lines longer than {@code max-width} go on to the next line ({@code wrap-indent}), the
 *   scoreboard is never narrower than {@code min-width}, {@code centre} lines are centred. Measured with the widths
 *   of the game's font; the exact spaces come from the HUD's font (Java players with the pack).</li>
 *   <li>Sent as packets to each player, nothing written to the server's scoreboards (the teams of the tab list and
 *   other plugins are left alone). At most 15 lines (the game's limit): the last ones are dropped.</li>
 * </ul>
 */
public final class ScoreboardService implements Listener, Scoreboard.Backend {

    /** What the packet adapter does for the scoreboard. */
    public interface Packets {
        void create(Player player, String objective, Component title);

        void title(Player player, String objective, Component title);

        void remove(Player player, String objective);

        void line(Player player, String objective, String entry, int score, Component text);

        void removeLine(Player player, String objective, String entry);
    }

    static final String OBJECTIVE = "flux_sidebar";
    private static final int MAX_LINES = 15;
    private static final Key HUD_FONT = Key.key("flux", "hud");
    private static final Pattern KEY = Pattern.compile("\\{([a-z0-9_]+)}");

    private record Line(String text, String each, int limit, String requires, boolean centre) {
    }

    private record Section(String requires, List<Line> lines) {
    }

    private record Split(String placeholder, String separator) {
    }

    /** A line ready to draw: text, centred or not, what its wrapped parts start with. */
    private record Built(Component text, boolean centre, String carry) {
    }

    /** What a player's client shows. */
    private static final class Shown {
        Component title;
        final List<Component> lines = new ArrayList<>();
        boolean created;
    }

    private final JavaPlugin flux;
    private final Packets packets;
    private final TextWidths widths;
    private final Map<UUID, Shown> shown = new HashMap<>();
    private BukkitTask task;

    private boolean enabled;
    private int refreshTicks = 10;
    private boolean hideWhenFocused = true;
    private String setting;
    private int minWidth;
    private int maxWidth = 160;
    private String wrapIndent = "";
    private String branch = "├";
    private String corner = "└";
    private String carryBranch = "│";
    /** "auto": the game's shadow (text colour at a quarter), "none", or a fixed #AARRGGBB / #RRGGBB. */
    private String shadow = "auto";
    private Map<String, String> colours = Map.of();
    private String title = "";
    private final List<Section> sections = new ArrayList<>();
    private final Map<String, Split> lists = new HashMap<>();

    public ScoreboardService(JavaPlugin flux, Packets packets) {
        this.flux = flux;
        this.packets = packets;
        this.widths = new TextWidths(flux);
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, flux);
        Scoreboard.bind(this);
        load();
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        HandlerList.unregisterAll(this);
        Scoreboard.bind(null);
        for (Player p : Bukkit.getOnlinePlayers()) clear(p, true);
        shown.clear();
    }

    /** Reads scoreboard.yml (again) and redraws every scoreboard. */
    public void load() {
        File file = new File(flux.getDataFolder(), "scoreboard.yml");
        if (!file.exists()) flux.saveResource("scoreboard.yml", false);
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        enabled = c.getBoolean("enabled", true) && packets != null;
        refreshTicks = Math.max(1, c.getInt("refresh-ticks", 10));
        hideWhenFocused = c.getBoolean("hide-when-focused", true);
        setting = c.getString("setting", "");
        if (setting != null && setting.isBlank()) setting = null;
        minWidth = Math.max(0, c.getInt("min-width", 0));
        maxWidth = Math.max(40, c.getInt("max-width", 160));
        wrapIndent = c.getString("wrap-indent", "  ");
        List<String> b = c.getStringList("branch");
        branch = b.size() > 0 ? b.get(0) : "├";
        corner = b.size() > 1 ? b.get(1) : "└";
        carryBranch = b.size() > 2 ? b.get(2) : "│";
        shadow = c.getString("text-shadow", "auto").trim();
        Map<String, String> cs = new LinkedHashMap<>();
        ConfigurationSection colourSection = c.getConfigurationSection("colours");
        if (colourSection != null) {
            for (String k : colourSection.getKeys(false)) {
                String v = colourSection.getString(k, "");
                if (v.matches("#?[0-9A-Fa-f]{6}")) cs.put(k.toLowerCase(Locale.ROOT), v.startsWith("#") ? v : "#" + v);
                else flux.getLogger().warning("scoreboard.yml: colour " + k + " is not #RRGGBB (" + v + ")");
            }
        }
        colours = cs;
        title = c.getString("title", "");
        lists.clear();
        ConfigurationSection ls = c.getConfigurationSection("lists");
        if (ls != null) {
            for (String id : ls.getKeys(false)) {
                lists.put(id.toLowerCase(Locale.ROOT), new Split(ls.getString(id + ".placeholder", ""), ls.getString(id + ".split", ", ")));
            }
        }
        sections.clear();
        for (Map<?, ?> s : c.getMapList("sections")) {
            List<Line> lines = new ArrayList<>();
            Object raw = s.get("lines");
            if (raw instanceof List<?> l) for (Object o : l) {
                Line line = line(o);
                if (line != null) lines.add(line);
            }
            sections.add(new Section(string(s.get("requires")), lines));
        }
        if (sections.isEmpty() && enabled) flux.getLogger().warning("scoreboard.yml: no section, the scoreboard is empty");

        if (task != null) task.cancel();
        task = null;
        for (Player p : Bukkit.getOnlinePlayers()) clear(p, !enabled);
        shown.clear();
        if (!enabled) return;
        task = Bukkit.getScheduler().runTaskTimer(flux, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) draw(p);
        }, 1L, refreshTicks);
    }

    private static Line line(Object o) {
        if (o == null) return new Line("", null, 0, null, false);
        if (o instanceof Map<?, ?> m) {
            String each = string(m.get("each"));
            Object limit = m.get("limit");
            return new Line(Objects.requireNonNullElse(string(m.get("text")), ""), each == null ? null : each.toLowerCase(Locale.ROOT),
                    limit instanceof Number n ? n.intValue() : 0, string(m.get("requires")), Boolean.TRUE.equals(m.get("centre")));
        }
        return new Line(String.valueOf(o), null, 0, null, false);
    }

    private static String string(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        return s.isBlank() ? null : s;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        shown.remove(e.getPlayer().getUniqueId());
        Sidebar.release(e.getPlayer().getUniqueId());
    }

    @Override
    public void refresh(Player player) {
        if (enabled && player.isOnline()) draw(player);
    }

    // ------------------------------------------------------------------ drawing

    private void draw(Player p) {
        Sidebar.claim(p, OBJECTIVE);
        boolean off = (hideWhenFocused && Focus.focused(p)) || (setting != null && !PlayerSettings.flag(p, "flux:" + setting, true));
        if (off) {
            clear(p, false);
            return;
        }
        List<Built> built = build(p);
        boolean bedrock = Clients.bedrock(p);
        List<Component> lines = new ArrayList<>();
        List<Boolean> centred = new ArrayList<>();
        for (Built b : built) {
            List<Component> parts = wrap(b.text(), b.carry(), p);
            for (Component part : parts) {
                lines.add(part);
                centred.add(b.centre());
            }
        }
        while (lines.size() > MAX_LINES) {
            lines.remove(lines.size() - 1);
            centred.remove(centred.size() - 1);
        }
        // width: the widest line, the title and min-width; the title is padded to it (the game centres the title)
        Component head = render(title, p);
        int width = Math.max(minWidth, width(head));
        for (Component l : lines) width = Math.max(width, width(l));
        Component titleShown = head;
        int pad = width - width(head);
        if (pad > 0) titleShown = Component.empty().append(space(pad / 2, bedrock)).append(head).append(space(pad - pad / 2, bedrock));
        for (int i = 0; i < lines.size(); i++) {
            if (!centred.get(i)) continue;
            int left = (width - width(lines.get(i))) / 2;
            if (left > 0) lines.set(i, Component.empty().append(space(left, bedrock)).append(lines.get(i)));
        }
        if (!bedrock && !shadow.equalsIgnoreCase("none")) {
            titleShown = shadowed(titleShown);
            lines.replaceAll(this::shadowed);
        }
        send(p, titleShown, lines);
    }

    /**
     * The game draws the sidebar without text shadow; a text with a shadow colour gets one anyway (client 26.2,
     * {@code Font#getShadowColor}): every piece gets the shadow the game would give it (its colour at a quarter, as
     * in chat) or the fixed {@code text-shadow} colour.
     */
    private Component shadowed(Component line) {
        List<Run> runs = new ArrayList<>();
        flatten(line, Style.empty(), runs);
        ShadowColor fixed = shadow.startsWith("#") ? ShadowColor.fromHexString(shadow.length() == 7 ? "#FF" + shadow.substring(1) : shadow) : null;
        Component out = Component.empty();
        for (Run r : runs) {
            ShadowColor s = fixed;
            if (s == null) {
                int rgb = r.style().color() == null ? 0xFFFFFF : r.style().color().value();
                s = ShadowColor.shadowColor(((rgb >> 16 & 0xFF) / 4) << 16 | ((rgb >> 8 & 0xFF) / 4) << 8 | (rgb & 0xFF) / 4 | 0xFF000000);
            }
            out = out.append(Component.text(r.text(), r.style().shadowColor(s)));
        }
        return out;
    }

    /** The sections shown to this player, {branch} resolved, not yet wrapped. */
    private List<Built> build(Player p) {
        List<Built> out = new ArrayList<>();
        for (Section s : sections) {
            if (!passes(s.requires(), p)) continue;
            List<String> raws = new ArrayList<>();
            List<Boolean> centre = new ArrayList<>();
            for (Line l : s.lines()) {
                if (!passes(l.requires(), p)) continue;
                if (l.each() == null) {
                    raws.add(l.text());
                    centre.add(l.centre());
                    continue;
                }
                List<Map<String, String>> rows = rows(l.each(), p);
                int n = l.limit() > 0 ? Math.min(l.limit(), rows.size()) : rows.size();
                for (int i = 0; i < n; i++) {
                    raws.add(fill(l.text(), rows.get(i)));
                    centre.add(l.centre());
                }
            }
            int last = -1;
            for (int i = 0; i < raws.size(); i++) if (raws.get(i).contains("{branch}")) last = i;
            for (int i = 0; i < raws.size(); i++) {
                String raw = raws.get(i);
                boolean branched = raw.contains("{branch}");
                String carry = branched && i != last ? carryBranch : " ";
                raw = raw.replace("{branch}", i == last ? corner : branch);
                out.add(new Built(render(raw, p), centre.get(i), carry));
            }
        }
        return out;
    }

    private boolean passes(String requires, Player p) {
        if (requires == null) return true;
        String id = requires.toLowerCase(Locale.ROOT);
        if (Scoreboard.has(id) || lists.containsKey(id)) return !rows(id, p).isEmpty();
        return !Texts.plain(requires, p).isBlank();
    }

    private List<Map<String, String>> rows(String id, Player p) {
        if (Scoreboard.has(id)) return Scoreboard.rows(id, p);
        Split split = lists.get(id);
        if (split == null || split.placeholder().isEmpty()) return List.of();
        String value = Texts.placeholders(split.placeholder(), p);
        if (value == null || value.isBlank() || value.equals(split.placeholder())) return List.of();
        List<Map<String, String>> out = new ArrayList<>();
        for (String part : split.separator().isEmpty() ? new String[]{value} : value.split(Pattern.quote(split.separator()))) {
            if (!part.isBlank()) out.add(Map.of("text", part.strip()));
        }
        return out;
    }

    private static String fill(String template, Map<String, String> row) {
        Matcher m = KEY.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = row.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Colour names to their colour, then the usual text pipeline (placeholders, MiniMessage, legacy codes). */
    private Component render(String raw, Player p) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        String text = raw;
        if (text.indexOf('<') >= 0) {
            for (Map.Entry<String, String> c : colours.entrySet()) {
                text = text.replace("<" + c.getKey() + ">", "<" + c.getValue() + ">").replace("</" + c.getKey() + ">", "</" + c.getValue() + ">");
            }
        }
        return Texts.parse(text, p);
    }

    // ------------------------------------------------------------------ width

    private record Run(String text, Style style) {
    }

    /** Text pieces of a component with their full style (parents' style taken down to the children). */
    private static void flatten(Component c, Style parent, List<Run> out) {
        Style style = c.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        String text = c instanceof TextComponent t ? t.content() : PlainTextComponentSerializer.plainText().serialize(c.children(List.of()));
        if (!text.isEmpty()) out.add(new Run(text, style));
        for (Component child : c.children()) flatten(child, style, out);
    }

    private int width(Component c) {
        List<Run> runs = new ArrayList<>();
        flatten(c, Style.empty(), runs);
        int w = 0;
        for (Run r : runs) w += width(r);
        return w;
    }

    private int width(Run r) {
        if (HUD_FONT.equals(r.style().font())) return spaceWidth(r.text());
        int w = widths.width(r.text(), false);
        if (r.style().decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) {
            w += (int) r.text().codePoints().filter(cp -> cp != ' ').count();
        }
        return w;
    }

    /** A line wider than max-width goes on below, at spaces, each next part after wrap-indent ({carry}). */
    private List<Component> wrap(Component line, String carry, Player p) {
        if (width(line) <= maxWidth) return List.of(line);
        List<Run> runs = new ArrayList<>();
        flatten(line, Style.empty(), runs);
        // one entry per character: code point, style, width
        List<int[]> chars = new ArrayList<>();
        List<Style> styles = new ArrayList<>();
        for (Run r : runs) {
            r.text().codePoints().forEach(cp -> {
                chars.add(new int[]{cp, width(new Run(new String(Character.toChars(cp)), r.style()))});
                styles.add(r.style());
            });
        }
        Component indent = render(wrapIndent.replace("{carry}", carry), p);
        int indentWidth = width(indent);
        List<Component> out = new ArrayList<>();
        int start = 0;
        boolean first = true;
        while (start < chars.size()) {
            if (!first) while (start < chars.size() && chars.get(start)[0] == ' ') start++;
            if (start >= chars.size()) break;
            int limit = first ? maxWidth : maxWidth - indentWidth;
            int w = 0, end = start, lastSpace = -1;
            while (end < chars.size() && w + chars.get(end)[1] <= limit) {
                if (chars.get(end)[0] == ' ') lastSpace = end;
                w += chars.get(end)[1];
                end++;
            }
            if (end < chars.size() && lastSpace > start) end = lastSpace;
            if (end == start) end = start + 1; // a single character wider than the line
            Component part = rebuild(chars, styles, start, end);
            out.add(first ? part : Component.empty().append(indent).append(part));
            first = false;
            start = end;
        }
        return out;
    }

    private static Component rebuild(List<int[]> chars, List<Style> styles, int from, int to) {
        Component out = Component.empty();
        StringBuilder sb = new StringBuilder();
        Style current = null;
        for (int i = from; i < to; i++) {
            if (current != null && !styles.get(i).equals(current)) {
                out = out.append(Component.text(sb.toString(), current));
                sb.setLength(0);
            }
            current = styles.get(i);
            sb.appendCodePoint(chars.get(i)[0]);
        }
        if (current != null && sb.length() > 0) out = out.append(Component.text(sb.toString(), current));
        return out;
    }

    /** Exact space of n pixels: the HUD font's spaces (1, 2, 4... px) for Java, plain spaces (4 px) for Bedrock. */
    private static Component space(int n, boolean bedrock) {
        if (n <= 0) return Component.empty();
        if (bedrock) return Component.text(" ".repeat(Math.max(1, Math.round(n / 4f))));
        StringBuilder sb = new StringBuilder();
        for (int bit = 0; bit < 10 && n > 0; bit++) {
            if ((n & (1 << bit)) != 0) sb.append((char) (0xE020 + bit));
        }
        return Component.text(sb.toString()).font(HUD_FONT);
    }

    private static int spaceWidth(String text) {
        int w = 0;
        for (char ch : text.toCharArray()) {
            if (ch >= 0xE020 && ch < 0xE02A) w += 1 << (ch - 0xE020);
            else if (ch >= 0xE010 && ch < 0xE01A) w -= 1 << (ch - 0xE010);
        }
        return w;
    }

    // ------------------------------------------------------------------ packets

    private void send(Player p, Component titleShown, List<Component> lines) {
        Shown s = shown.computeIfAbsent(p.getUniqueId(), k -> new Shown());
        if (!s.created) {
            packets.create(p, OBJECTIVE, titleShown);
            s.created = true;
            s.title = titleShown;
            s.lines.clear();
        } else if (!titleShown.equals(s.title)) {
            packets.title(p, OBJECTIVE, titleShown);
            s.title = titleShown;
        }
        boolean count = lines.size() != s.lines.size();
        for (int i = 0; i < lines.size(); i++) {
            // the game sorts by score, highest first: the first line has the highest
            if (count || !lines.get(i).equals(s.lines.get(i))) packets.line(p, OBJECTIVE, entry(i), lines.size() - i, lines.get(i));
        }
        for (int i = lines.size(); i < s.lines.size(); i++) packets.removeLine(p, OBJECTIVE, entry(i));
        s.lines.clear();
        s.lines.addAll(lines);
    }

    private static String entry(int i) {
        return "flux_line_" + i;
    }

    /** Takes Flux's scoreboard off this player's screen; {@code release}: gives the sidebar back to other plugins. */
    private void clear(Player p, boolean release) {
        Shown s = shown.remove(p.getUniqueId());
        if (s != null && s.created && packets != null) packets.remove(p, OBJECTIVE);
        if (release) Sidebar.release(p.getUniqueId());
    }
}
