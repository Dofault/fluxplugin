package fr.influencraft.flux.hud;

import com.destroystokyo.paper.profile.ProfileProperty;
import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.flux.api.client.Focus;
import fr.influencraft.flux.api.client.TextShader;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.hud.Hud;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.PlayerSettings;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flux's HUD ({@link Hud}, file {@code plugins/Flux/hud.yml}), in place of MythicHUD.
 *
 * <p>Java players with the pack loaded get two boss bars of an empty picture (YELLOW, see Draws): the first carries
 * the backgrounds, the second everything over them. The client sorts what a single text draws by texture, so a
 * background and the text over it must be two texts; the first one carries a tall invisible glyph reaching the
 * second's line, so that the game draws the second above it. The text shader then moves every part to its place
 * ({@link HudLayout}). Both bars are kept below every other boss bar: when another one appears they are shown
 * again (adapters/packetevents/BossBarOrder), so they never push the others down.</p>
 *
 * <p>Bedrock players (no pack) get one boss bar with the same data in plain text ({@code bedrock} of hud.yml).</p>
 */
public final class HudService implements Hud.Backend, Listener {

    private static final Key FONT = Key.key("flux", "hud");
    private static final Key GUI_ATLAS = Key.key("minecraft", "gui");
    /** Tall invisible glyph of the font (2 px wide picture at height 40: 40 px wide, advance 41). */
    private static final String SPACER = "";
    private static final int SPACER_ADVANCE = 41;
    private static final Pattern PIECE = Pattern.compile("\\{([a-z0-9_]+)}");
    private static final Set<String> FAST = Set.of("x", "y", "z", "compass");

    private final JavaPlugin flux;
    private final TextWidths widths;
    private final Map<UUID, View> views = new ConcurrentHashMap<>();
    private HudLayout layout = new HudLayout();
    private boolean enabled;
    private int refreshTicks = 4;
    private int valueTicks = 20;
    private boolean hideWhenFocused = true;
    private BossBar.Color javaColour = BossBar.Color.YELLOW;
    private boolean bedrockEnabled;
    private String bedrockText = "";
    private String bedrockProgress = "";
    private BossBar.Color bedrockColour = BossBar.Color.PURPLE;
    private BukkitTask task;
    private long tick;

    /** What a player is shown. */
    private static final class View {
        BossBar back;
        BossBar front;
        BossBar bedrock;
        boolean shown;
        boolean bedrockShown;
        final Map<String, String> values = new HashMap<>();
        long valuesAt;
        /** Values to read again at the next draw (new view, Hud.refresh). Never a "very old" valuesAt: tick - Long.MIN_VALUE overflows. */
        boolean stale = true;
        Component head;
        long reorderMinute;
        int reorders;
    }

    public HudService(JavaPlugin flux) {
        this.flux = flux;
        this.widths = new TextWidths(flux);
    }

    public void start() {
        Hud.bind(this);
        flux.getServer().getPluginManager().registerEvents(this, flux);
        load();
    }

    public void stop() {
        Hud.bind(null);
        HandlerList.unregisterAll(this);
        if (task != null) task.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) hide(p);
        views.clear();
    }

    /** While plugins load: the HUD's part of the text shader, from hud.yml as it is on disk (ClientPack#early). */
    public static void earlyShaderPart(JavaPlugin flux) {
        File file = new File(flux.getDataFolder(), "hud.yml");
        if (!file.isFile()) return;
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        if (!c.getBoolean("enabled", true)) return;
        String glsl = HudLayout.read(c, flux.getLogger()).glsl();
        if (glsl != null) TextShader.part(flux, "flux_hud", glsl, "flux_hud_vertex();");
    }

    /** (Re)reads hud.yml. */
    public void load() {
        File file = new File(flux.getDataFolder(), "hud.yml");
        if (!file.exists()) flux.saveResource("hud.yml", false);
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        enabled = c.getBoolean("enabled", true);
        refreshTicks = Math.max(1, c.getInt("refresh-ticks", 4));
        valueTicks = Math.max(refreshTicks, c.getInt("value-ticks", 20));
        hideWhenFocused = c.getBoolean("hide-when-focused", true);
        javaColour = colour(c.getString("bar-colour", "YELLOW"), BossBar.Color.YELLOW);
        layout = HudLayout.read(c, flux.getLogger());
        ConfigurationSection b = c.getConfigurationSection("bedrock");
        bedrockEnabled = b != null && b.getBoolean("enabled", true);
        bedrockText = b == null ? "" : b.getString("text", "");
        bedrockProgress = b == null ? "" : b.getString("progress", "");
        bedrockColour = colour(b == null ? null : b.getString("colour"), BossBar.Color.PURPLE);
        currencyIcons();
        String glsl = enabled ? layout.glsl() : null;
        TextShader.part(flux, "flux_hud", glsl, glsl == null ? null : "flux_hud_vertex();");
        for (Player p : Bukkit.getOnlinePlayers()) hide(p);
        views.clear();
        if (task != null) task.cancel();
        task = enabled ? Bukkit.getScheduler().runTaskTimer(flux, this::tick, 20L, refreshTicks) : null;
        flux.getLogger().info("HUD: " + layout.elements.size() + " element(s), " + layout.groups.size() + " group(s), "
                + layout.slotCount() + " place(s) in the shader" + (enabled ? "" : " (disabled)"));
    }

    /**
     * {@code image: "currency:<id>"} shows the currency's own icon ({@code currencies.<id>.icon} of Flux's config, as
     * the menus and the chat do): its ItemsAdder texture is added to the gui atlas as {@code flux:hud/currency_<id>},
     * never copied.
     */
    private void currencyIcons() {
        Map<String, String> sprites = new HashMap<>();
        File contents = new File(flux.getDataFolder().getParentFile(), "ItemsAdder/contents");
        for (HudLayout.Element e : layout.elements) {
            for (HudLayout.Part part : e.parts()) {
                if (!(part instanceof HudLayout.Image i) || !i.sprite().startsWith("currency:")) continue;
                String id = i.sprite().substring("currency:".length());
                String icon = Economy.icon(id);
                String[] spec = icon == null ? new String[0] : icon.split(":");
                String texture = spec.length == 3 && spec[0].equalsIgnoreCase("itemsadder")
                        ? fr.influencraft.flux.adapters.itemsadder.ItemsAdderTextures.texture(contents, spec[1], spec[2]) : null;
                if (texture == null) {
                    flux.getLogger().warning("hud.yml " + e.id() + ": no picture for the icon of currency " + id + " (" + icon + ")");
                    continue;
                }
                sprites.put(currencySprite(id), texture);
            }
        }
        fr.influencraft.flux.pack.GuiAtlas.set("hud", sprites);
    }

    private static String currencySprite(String currency) {
        return "flux:hud/currency_" + currency.toLowerCase(Locale.ROOT);
    }

    @Override
    public void refresh(Player player) {
        View v = views.get(player.getUniqueId());
        if (v != null) v.stale = true;
        if (enabled) draw(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // the player's settings are read from the database once, off the main thread, before the HUD needs them
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(flux, () -> PlayerSettings.has(p, "flux:hud"));
    }

    @EventHandler
    public void onPack(PlayerResourcePackStatusEvent e) {
        if (e.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            Bukkit.getScheduler().runTaskLater(flux, () -> refresh(e.getPlayer()), 5L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        views.remove(e.getPlayer().getUniqueId());
    }


    private void tick() {
        tick += refreshTicks;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                draw(p);
            } catch (RuntimeException ex) {
                flux.getLogger().warning("HUD of " + p.getName() + " not drawn: " + ex);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private void draw(Player p) {
        if (!p.isOnline()) return;
        View v = views.computeIfAbsent(p.getUniqueId(), id -> new View());
        if (hideWhenFocused && Focus.focused(p)) {
            hide(p);
            return;
        }
        boolean bedrock = Clients.bedrock(p);
        if (bedrock) {
            drawBedrock(p, v);
            return;
        }
        if (!packLoaded(p) || layout.empty()) {
            hide(p);
            return;
        }
        if (v.stale || tick - v.valuesAt >= valueTicks) {
            v.stale = false;
            v.values.clear();
            v.valuesAt = tick;
        }
        TitleBuilder back = new TitleBuilder();
        TitleBuilder front = new TitleBuilder();
        // each group in the corner the player chose in the settings (group "setting", a choice option)
        Map<HudLayout.Group, HudLayout.Corner> corners = new HashMap<>();
        for (HudLayout.Group g : layout.groups) {
            if (g.setting() != null) corners.put(g, HudLayout.Corner.of(PlayerSettings.get(p, "flux:" + g.setting(), null), g.byDefault()));
        }
        HudLayout.Arrangement arrangement = layout.arrange(corners, e -> shown(p, v, e));
        for (HudLayout.Row row : arrangement.rows()) drawRow(p, v, row, back, front);
        for (HudLayout.Placed at : arrangement.placed()) {
            for (HudLayout.Part part : at.element().parts()) {
                int slot = layout.slot(part, at.place());
                if (slot == 0) continue;
                TitleBuilder to = part.layer() == HudLayout.Layer.BACK ? back : front;
                switch (part) {
                    case HudLayout.Image i -> {
                        String sprite = i.sprite().startsWith("currency:")
                                ? currencySprite(i.sprite().substring("currency:".length())) : fill(i.sprite(), p, v);
                        if (sprite.isBlank()) continue;
                        to.add(Component.object(ObjectContents.sprite(GUI_ATLAS, Key.key(sprite))), slot, i.colour(), 8);
                    }
                    case HudLayout.Head h -> to.add(head(p, v), slot, h.colour(), 8);
                    case HudLayout.Text t -> {
                        int x = 0;
                        for (HudLayout.Run run : t.runs()) {
                            String text = clean(fill(run.text(), p, v));
                            if (t.small()) text = small(text);
                            if (text.isEmpty()) continue;
                            x += to.addText(text, t.small(), slot, run.colour(), widths.width(text, t.small()));
                        }
                        to.close(x);
                    }
                }
            }
        }
        if (back.empty() && front.empty()) {
            hide(p);
            return;
        }
        if (!front.empty()) back.spacer();
        Component backTitle = back.build();
        Component frontTitle = front.build();
        if (v.back == null) {
            v.back = BossBar.bossBar(backTitle, 0f, javaColour, BossBar.Overlay.PROGRESS);
            v.front = BossBar.bossBar(frontTitle, 0f, javaColour, BossBar.Overlay.PROGRESS);
        } else {
            if (!v.back.name().equals(backTitle)) v.back.name(backTitle);
            if (!v.front.name().equals(frontTitle)) v.front.name(frontTitle);
        }
        if (!v.shown) {
            v.shown = true;
            p.showBossBar(v.back);
            p.showBossBar(v.front);
        }
    }

    // ------------------------------------------------------------------------------------------------ boxes

    /** What a box shows: its segments with their icon character and text, and its width. */
    private record BoxContent(List<String> icons, List<List<String>> texts, List<List<Integer>> colours, int width) {
    }

    /**
     * A row of boxes, side by side from the corner's edge: each box is its background (pieces of the HUD font
     * repeated to its width), then for each segment an icon and a text. Everything is drawn at its natural size,
     * so the boxes are laid out here by the advances of the font.
     */
    private void drawRow(Player p, View v, HudLayout.Row row, TitleBuilder back, TitleBuilder front) {
        List<BoxContent> boxes = new ArrayList<>();
        int total = 0;
        for (HudLayout.Element e : row.boxes()) {
            BoxContent b = box(p, v, e);
            if (b == null) continue;
            if (!boxes.isEmpty()) total += layout.gapBetweenBoxes();
            boxes.add(b);
            total += b.width();
        }
        if (boxes.isEmpty()) return;
        int panel = layout.boxSlot(HudLayout.BoxKind.PANEL, row.corner(), row.y());
        int icon = layout.boxSlot(HudLayout.BoxKind.ICON, row.corner(), row.y());
        int text = layout.boxSlot(HudLayout.BoxKind.TEXT, row.corner(), row.y());
        if (panel == 0 || icon == 0 || text == 0) return;
        int x = row.corner().anchorX == 0 ? 0 : -total;
        int white = layout.white();
        for (BoxContent b : boxes) {
            back.place(Component.text(panel(b.width())).font(FONT), panel, white, x, b.width());
            int at = x + layout.boxPadding;
            for (int i = 0; i < b.icons().size(); i++) {
                if (i > 0) at += layout.segmentGap;
                String ic = b.icons().get(i);
                if (ic != null) {
                    int w = widths.width(ic, true) - 1;
                    front.place(Component.text(ic).font(FONT), icon, white, at, w + 1);
                    at += w + (b.texts().get(i).isEmpty() ? 0 : layout.iconGap);
                }
                List<String> runs = b.texts().get(i);
                for (int r = 0; r < runs.size(); r++) {
                    String t = runs.get(r);
                    int w = widths.width(t, true);
                    front.place(Component.text(t).font(FONT), text, b.colours().get(i).get(r), at, w);
                    at += w;
                }
                if (!runs.isEmpty()) at -= 1; // the last letter's spacing
            }
            x += b.width() + layout.gapBetweenBoxes();
        }
    }

    /** A box's segments for this player, or null when none is shown. */
    private BoxContent box(Player p, View v, HudLayout.Element e) {
        List<String> icons = new ArrayList<>();
        List<List<String>> texts = new ArrayList<>();
        List<List<Integer>> colours = new ArrayList<>();
        int width = 0;
        for (HudLayout.Segment s : e.box()) {
            if (s.setting() != null && !PlayerSettings.flag(p, "flux:" + s.setting(), true)) continue;
            if (!s.universes().isEmpty() || !s.exceptUniverses().isEmpty()) {
                String universe = fr.influencraft.flux.api.universe.Universes.of(p).toLowerCase(Locale.ROOT);
                if (!s.universes().isEmpty() && !s.universes().contains(universe)) continue;
                if (s.exceptUniverses().contains(universe)) continue;
            }
            if (s.requires() != null) {
                String r = value(p, v, s.requires());
                if (r == null || r.isBlank()) continue;
            }
            String ic = s.icon() == null ? null : icons().get(fill(s.icon(), p, v));
            List<String> runs = new ArrayList<>();
            List<Integer> cs = new ArrayList<>();
            int w = 0;
            for (HudLayout.Run run : s.runs()) {
                String t = small(clean(fill(run.text(), p, v)));
                if (t.isEmpty()) continue;
                runs.add(t);
                cs.add(run.colour());
                w += widths.width(t, true);
            }
            if (ic == null && runs.isEmpty()) continue;
            if (!icons.isEmpty()) width += layout.segmentGap;
            if (ic != null) width += widths.width(ic, true) - 1 + (runs.isEmpty() ? 0 : layout.iconGap);
            if (!runs.isEmpty()) width += w - 1;
            icons.add(ic);
            texts.add(runs);
            colours.add(cs);
        }
        if (icons.isEmpty()) return null;
        return new BoxContent(icons, texts, colours, width + 2 * layout.boxPadding);
    }

    /** Background of a box {@code width} px wide: left edge, 8 px and 1 px middles, right edge (each then -1 px). */
    private static String panel(int width) {
        int inner = Math.max(0, width - 4);
        StringBuilder b = new StringBuilder("\uE030\uE010");
        for (int i = 0; i < inner / 8; i++) b.append("\uE031\uE010");
        for (int i = 0; i < inner % 8; i++) b.append("\uE032\uE010");
        return b.append("\uE033\uE010").toString();
    }

    private Map<String, String> icons;

    /** Icon id -> character of the HUD font (hud_icons.json, Flux/assets/hud/gen_hud.py). */
    private Map<String, String> icons() {
        if (icons != null) return icons;
        Map<String, String> read = Map.of();
        try (java.io.InputStream in = flux.getResource("hud_icons.json")) {
            if (in != null) read = new com.google.gson.Gson().fromJson(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
                    new com.google.gson.reflect.TypeToken<Map<String, String>>() {
                    }.getType());
        } catch (Exception e) {
            flux.getLogger().warning("hud_icons.json not readable: " + e.getMessage());
        }
        icons = read == null ? Map.of() : read;
        return icons;
    }

    /** A text in the HUD font: capitals without accents, characters it lacks left out. */
    private String small(String text) {
        String plain = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(plain.length());
        plain.codePoints().filter(widths::small).forEach(b::appendCodePoint);
        return b.toString();
    }

    /** An element is shown unless its setting is off or a piece it requires is empty. */
    private boolean shown(Player p, View v, HudLayout.Element e) {
        if (e.setting() != null && !PlayerSettings.flag(p, "flux:" + e.setting(), true)) return false;
        if (e.requires() == null) return true;
        String r = value(p, v, e.requires());
        return r != null && !r.isBlank();
    }

    private void drawBedrock(Player p, View v) {
        if (v.shown) hideJava(p, v);
        if (!bedrockEnabled || bedrockText.isBlank()) {
            hideBedrock(p, v);
            return;
        }
        if (v.stale || tick - v.valuesAt >= valueTicks) {
            v.stale = false;
            v.values.clear();
            v.valuesAt = tick;
        }
        Component title = Texts.parse(fill(bedrockText, p, v), p);
        float progress = progress(fill(bedrockProgress, p, v));
        if (v.bedrock == null) {
            v.bedrock = BossBar.bossBar(title, progress, bedrockColour, BossBar.Overlay.PROGRESS);
        } else {
            if (!v.bedrock.name().equals(title)) v.bedrock.name(title);
            if (v.bedrock.progress() != progress) v.bedrock.progress(progress);
        }
        if (!v.bedrockShown) {
            v.bedrockShown = true;
            p.showBossBar(v.bedrock);
        }
    }

    /** "a/b" (or a number from 0 to 1) as a bar's fill. */
    private static float progress(String text) {
        try {
            String t = text.replace(" ", "").replace(',', '.');
            if (t.isEmpty()) return 0f;
            int slash = t.indexOf('/');
            double v = slash < 0 ? Double.parseDouble(t) : Double.parseDouble(t.substring(0, slash)) / Double.parseDouble(t.substring(slash + 1));
            return (float) Math.max(0, Math.min(1, v));
        } catch (RuntimeException e) {
            return 0f;
        }
    }

    private void hide(Player p) {
        View v = views.get(p.getUniqueId());
        if (v == null) return;
        hideJava(p, v);
        hideBedrock(p, v);
    }

    private static void hideJava(Player p, View v) {
        if (!v.shown) return;
        v.shown = false;
        p.hideBossBar(v.back);
        p.hideBossBar(v.front);
    }

    private static void hideBedrock(Player p, View v) {
        if (!v.bedrockShown) return;
        v.bedrockShown = false;
        p.hideBossBar(v.bedrock);
    }

    /** Shows the HUD's bars again so that they come below a boss bar shown after them. Main thread. */
    public void putLast(Player p) {
        View v = views.get(p.getUniqueId());
        if (v == null || !v.shown || !p.isOnline()) return;
        long minute = System.currentTimeMillis() / 60_000;
        if (v.reorderMinute != minute) {
            v.reorderMinute = minute;
            v.reorders = 0;
        }
        if (++v.reorders == 20) {
            flux.getLogger().warning("HUD of " + p.getName() + " put back below other boss bars 20 times this minute: a plugin keeps"
                    + " showing a boss bar again (each time the HUD may blink).");
        }
        p.hideBossBar(v.back);
        p.hideBossBar(v.front);
        p.showBossBar(v.back);
        p.showBossBar(v.front);
    }

    @SuppressWarnings("deprecation")
    private static boolean packLoaded(Player p) {
        try {
            return p.getResourcePackStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------ values

    private String fill(String template, Player p, View v) {
        if (template.indexOf('{') < 0) return template.indexOf('%') >= 0 ? Texts.placeholders(template, p) : template;
        Matcher m = PIECE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = value(p, v, m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(out);
        String s = out.toString();
        return s.indexOf('%') >= 0 ? Texts.placeholders(s, p) : s;
    }

    /** A piece's value: position ones each time, the others kept for {@code value-ticks}. */
    private String value(Player p, View v, String id) {
        if (FAST.contains(id)) return fast(p, id);
        String cached = v.values.get(id);
        if (cached != null || v.values.containsKey(id)) return cached;
        String value = slow(p, id);
        v.values.put(id, value);
        return value;
    }

    private static String fast(Player p, String id) {
        Location l = p.getLocation();
        return switch (id) {
            case "x" -> String.valueOf(l.getBlockX());
            case "y" -> String.valueOf(l.getBlockY());
            case "z" -> String.valueOf(l.getBlockZ());
            // frame of the compass pointing north (0 = straight up when facing north), as InfluBridge did
            default -> {
                double yaw = ((l.getYaw() % 360.0) + 360.0) % 360.0;
                int frame = (int) Math.round(((360.0 - yaw) % 360.0) / 11.25) % 32;
                yield String.format(Locale.ROOT, "%02d", frame);
            }
        };
    }

    private static String slow(Player p, String id) {
        switch (id) {
            case "player":
                return p.getName();
            case "balance":
                return amount(Economy.main(), p);
            case "influence":
                return amount(Economy.get("influence"), p);
            case "biome":
                return biome(p);
            case "city": {
                String name = fr.influencraft.flux.api.claims.Claims.claimName(p.getLocation());
                return name == null ? "" : name;
            }
            default:
                if (id.startsWith("balance_")) return amount(Economy.get(id.substring("balance_".length())), p);
                return Hud.value(id, p);
        }
    }

    /** The biome where the player stands, in the server's language (its id, readable, for a biome of a datapack). */
    @SuppressWarnings("deprecation")
    private static String biome(Player p) {
        org.bukkit.NamespacedKey key = p.getLocation().getBlock().getBiome().getKey();
        String name = fr.influencraft.flux.api.text.Translations.server("biome." + key.getNamespace() + "." + key.getKey());
        if (name != null) return name;
        String path = key.getKey();
        return path.substring(path.lastIndexOf('/') + 1).replace('_', ' ');
    }

    /** Whole amount, thousands grouped with a plain space; short form from ten million on (fits the card). */
    private static String amount(Currency c, Player p) {
        if (c == null) return null;
        double v = c.balance(p);
        return Math.abs(v) >= 10_000_000 ? Economy.compact(v) : Format.number(v);
    }

    /** No-break and narrow spaces as plain ones (the width table knows those), no formatting codes. */
    private static String clean(String text) {
        StringBuilder b = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == ' ' || c == ' ' || c == ' ') b.append(' ');
            else if ((c == '§' || c == '&') && i + 1 < text.length() && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(text.charAt(i + 1)) >= 0) i++;
            else b.append(c);
        }
        return b.toString();
    }

    /** The player's head with his skin given in full: nothing to look up on the client's side. */
    private static Component head(Player p, View v) {
        if (v.head != null) return v.head;
        PlayerHeadObjectContents.Builder b = ObjectContents.playerHead().id(p.getUniqueId()).name(p.getName()).hat(true);
        for (ProfileProperty prop : p.getPlayerProfile().getProperties()) {
            if (!prop.getName().equals("textures")) continue;
            b.profileProperty(prop.getSignature() == null ? PlayerHeadObjectContents.property(prop.getName(), prop.getValue())
                    : PlayerHeadObjectContents.property(prop.getName(), prop.getValue(), prop.getSignature()));
        }
        v.head = Component.object(b.build());
        return v.head;
    }

    private static BossBar.Color colour(String name, BossBar.Color fallback) {
        if (name == null) return fallback;
        try {
            return BossBar.Color.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------------------------------------ titles

    /** One boss bar title: parts that each start at the centre and step back by their width. */
    private static final class TitleBuilder {
        private final List<Component> parts = new ArrayList<>();

        boolean empty() {
            return parts.isEmpty();
        }

        /** An 8 px object (sprite, head) of a slot, then back to the centre. */
        void add(Component object, int slot, int colour, int width) {
            parts.add(mark(object, slot, colour));
            parts.add(space(-width));
        }

        /** A run of text; the slot is closed by {@link #close} with the runs' total width. */
        int addText(String text, boolean small, int slot, int colour, int width) {
            Component c = Component.text(text);
            parts.add(mark(small ? c.font(FONT) : c, slot, colour));
            return width;
        }

        void close(int width) {
            if (width != 0) parts.add(space(-width));
        }

        /** Content of a slot {@code offset} px from the centre, {@code advance} px wide, then back to the centre. */
        void place(Component content, int slot, int colour, int offset, int advance) {
            if (offset != 0) parts.add(space(offset));
            parts.add(mark(content, slot, colour));
            parts.add(space(-(offset + advance)));
        }

        /** The tall invisible glyph, first: makes the client draw the next bar's title above this one. */
        void spacer() {
            parts.addFirst(space(-SPACER_ADVANCE));
            parts.addFirst(Component.text(SPACER).font(FONT).color(TextColor.color(0xFFFFFF)).shadowColor(ShadowColor.none()));
        }

        Component build() {
            return Component.empty().children(parts);
        }

        private static Component mark(Component c, int slot, int colour) {
            int green = HudLayout.green(slot);
            return c.color(TextColor.color(HudLayout.MARKER, green, HudLayout.blue(slot, colour, false)))
                    .shadowColor(ShadowColor.shadowColor(0xFF000000 | HudLayout.MARKER << 16 | green << 8 | HudLayout.blue(slot, colour, true)));
        }

        /** Negative (or positive) space of the HUD font, in powers of two. */
        private static TextComponent space(int px) {
            StringBuilder s = new StringBuilder();
            int left = Math.abs(px);
            int base = px < 0 ? 0xE010 : 0xE020;
            for (int bit = 9; bit >= 0; bit--) {
                while (left >= (1 << bit)) {
                    s.append((char) (base + bit));
                    left -= 1 << bit;
                }
            }
            return Component.text(s.toString()).font(FONT);
        }
    }
}
