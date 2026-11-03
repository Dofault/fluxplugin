package fr.influencraft.flux.tab;

import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.permission.PlayerMeta;
import fr.influencraft.flux.api.tab.TabList;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flux's tab list ({@link TabList}, file {@code plugins/Flux/tab.yml}), in place of the TAB plugin:
 * <ul>
 *   <li>header and footer: the first design whose condition fits the viewer (group, permission, world), animations;</li>
 *   <li>name in the list ({@code tab-prefix}, {@code tab-name}, {@code tab-suffix}) and above the head
 *   ({@code tag-prefix}, {@code tag-suffix}), per LuckPerms primary group over {@code _default_};</li>
 *   <li>order of the list (Paper's list order: meta values, higher first; same order = by name);</li>
 *   <li>line below the name (a number, shown with a text of its own).</li>
 * </ul>
 * Templates use pieces {@code {id}} / {@code {id:arg}} computed in Java (Flux's own, or given by a plugin with
 * {@link TabList#piece}), then placeholders {@code %...%} (Flux's, then PlaceholderAPI) for third-party data.
 * Nothing is sent when nothing changed. Nametags and the line below the name are packets (see {@link Nametags}).
 */
public final class TabService implements TabList.Backend, Listener {

    private static final Pattern PIECE = Pattern.compile("\\{([a-z0-9_]+)(?::([^{}]*))?}");

    private record Design(String name, List<String> groups, String permission, List<String> worlds, String header, String footer) {
    }

    private record Custom(String placeholder, String equals, String permission, String set, String empty) {
    }

    private record Animation(long intervalMs, List<String> frames) {
    }

    /** What a player's client was last sent, and what everyone was last sent about a player. */
    private static final class Drawn {
        String header, footer;
        String listName;
        int order = Integer.MIN_VALUE;
        String tagPrefix, tagSuffix;
        Integer belowValue;
        String belowText;
    }

    private final JavaPlugin flux;
    private final Nametags nametags;
    private final Map<UUID, Drawn> drawn = new HashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    private boolean enabled;
    private List<Design> designs = List.of();
    private Map<String, Map<String, String>> groups = Map.of();
    private List<String> sorting = List.of();
    private boolean nametagsOn, collision, belowOn;
    /** Players nobody can push, whatever tab.yml says (TabList.collision, e.g. FluxAFK's zone). */
    private final Set<UUID> noCollision = new HashSet<>();
    private String belowTitle = "", belowValue = "", belowText = "";
    private Map<String, Custom> customs = Map.of();
    private Map<String, Animation> animations = Map.of();

    public TabService(JavaPlugin flux, Nametags nametags) {
        this.flux = flux;
        this.nametags = nametags;
    }

    public void start() {
        load();
        Bukkit.getPluginManager().registerEvents(this, flux);
        TabList.bind(this);
    }

    public void stop() {
        TabList.bind(null);
        clearAll();
    }

    /** (Re)reads tab.yml: everything is drawn again. */
    public void load() {
        clearAll();
        File file = new File(flux.getDataFolder(), "tab.yml");
        if (!file.exists()) flux.saveResource("tab.yml", false);
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        enabled = c.getBoolean("enabled", true);
        List<Design> ds = new ArrayList<>();
        ConfigurationSection d = c.getConfigurationSection("header-footer");
        if (d != null) {
            for (String name : d.getKeys(false)) {
                ConfigurationSection s = d.getConfigurationSection(name);
                if (s == null) continue;
                ds.add(new Design(name, lower(s.getStringList("groups")), s.getString("permission"), lower(s.getStringList("worlds")),
                        String.join("\n", s.getStringList("header")), String.join("\n", s.getStringList("footer"))));
            }
        }
        designs = ds;
        Map<String, Map<String, String>> gs = new HashMap<>();
        ConfigurationSection g = c.getConfigurationSection("names");
        if (g != null) {
            for (String group : g.getKeys(false)) {
                ConfigurationSection s = g.getConfigurationSection(group);
                if (s == null) continue;
                Map<String, String> fields = new HashMap<>();
                for (String k : s.getKeys(false)) fields.put(k, s.getString(k, ""));
                gs.put(group.toLowerCase(Locale.ROOT), fields);
            }
        }
        groups = gs;
        sorting = c.getStringList("sorting");
        nametagsOn = c.getBoolean("nametags.enabled", true) && nametags != null;
        collision = c.getBoolean("nametags.collision", true);
        belowOn = c.getBoolean("below-name.enabled", false) && nametags != null;
        belowTitle = c.getString("below-name.title", "");
        belowValue = c.getString("below-name.value", "0");
        belowText = c.getString("below-name.text", "");
        Map<String, Custom> cs = new HashMap<>();
        ConfigurationSection p = c.getConfigurationSection("pieces");
        if (p != null) {
            for (String id : p.getKeys(false)) {
                ConfigurationSection s = p.getConfigurationSection(id);
                if (s == null) continue;
                cs.put(id.toLowerCase(Locale.ROOT), new Custom(s.getString("placeholder"), s.getString("equals"), s.getString("permission"),
                        s.getString("set", ""), s.getString("empty", "")));
            }
        }
        customs = cs;
        Map<String, Animation> as = new HashMap<>();
        ConfigurationSection a = c.getConfigurationSection("animations");
        if (a != null) {
            for (String id : a.getKeys(false)) {
                ConfigurationSection s = a.getConfigurationSection(id);
                if (s != null) as.put(id.toLowerCase(Locale.ROOT), new Animation(Math.max(50, s.getLong("interval-ms", 200)), s.getStringList("frames")));
            }
        }
        animations = as;
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        if (!enabled) return;
        long headerTicks = Math.max(1, c.getLong("refresh.header-ticks", 3));
        long nameTicks = Math.max(1, c.getLong("refresh.names-ticks", 20));
        tasks.add(Bukkit.getScheduler().runTaskTimer(flux, () -> Bukkit.getOnlinePlayers().forEach(this::header), headerTicks, headerTicks));
        tasks.add(Bukkit.getScheduler().runTaskTimer(flux, () -> Bukkit.getOnlinePlayers().forEach(this::draw), nameTicks, nameTicks));
        for (Player viewer : Bukkit.getOnlinePlayers()) joined(viewer);
    }

    private static List<String> lower(List<String> in) {
        return in.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    // ------------------------------------------------------------------ players

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (enabled) joined(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        noCollision.remove(p.getUniqueId());
        Drawn d = drawn.remove(p.getUniqueId());
        if (d == null) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer == p) continue;
            if (nametagsOn) nametags.removeTeam(viewer, team(p));
            if (belowOn) nametags.removeScore(viewer, p.getName());
        }
    }

    /** A client starts: the line below the name, everyone's nametag, then this player drawn for everyone. */
    private void joined(Player viewer) {
        drawn.put(viewer.getUniqueId(), new Drawn());
        if (belowOn) nametags.createBelowName(viewer, Texts.parse(belowTitle, viewer));
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other == viewer) continue;
            Drawn o = drawn.get(other.getUniqueId());
            if (o == null || o.tagPrefix == null) continue;
            if (nametagsOn) nametags.createTeam(viewer, team(other), other.getName(), Texts.parse(o.tagPrefix, other), Texts.parse(o.tagSuffix, other), collides(other));
            if (belowOn && o.belowValue != null) nametags.score(viewer, other.getName(), o.belowValue, shown(o.belowText, other));
        }
        draw(viewer);
        header(viewer);
    }

    private void clearAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Drawn d = drawn.get(p.getUniqueId());
            if (d == null) continue;
            p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            p.playerListName(null);
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (nametagsOn && d.tagPrefix != null) nametags.removeTeam(viewer, team(p));
            }
            if (belowOn) nametags.removeBelowName(p);
        }
        drawn.clear();
    }

    /** Team of a player's nametag: named after him, so players of the same order are listed by name. */
    private static String team(Player p) {
        return "flux_" + p.getName().toLowerCase(Locale.ROOT);
    }

    @Override
    public void refresh(Player player) {
        if (enabled && drawn.containsKey(player.getUniqueId())) draw(player);
    }

    @Override
    public boolean collision(Player player, boolean collides) {
        boolean changed = collides ? noCollision.remove(player.getUniqueId()) : noCollision.add(player.getUniqueId());
        if (!enabled || !nametagsOn) return false;
        Drawn d = drawn.get(player.getUniqueId());
        if (changed && d != null && d.tagPrefix != null) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                nametags.updateTeam(viewer, team(player), Texts.parse(d.tagPrefix, player), Texts.parse(d.tagSuffix, player), collides(player));
            }
        }
        return true;
    }

    private boolean collides(Player p) {
        return collision && !noCollision.contains(p.getUniqueId());
    }

    // ------------------------------------------------------------------ drawing

    private void header(Player viewer) {
        Drawn d = drawn.get(viewer.getUniqueId());
        if (d == null) return;
        Design design = design(viewer);
        String header = design == null ? "" : render(design.header(), viewer);
        String footer = design == null ? "" : render(design.footer(), viewer);
        if (header.equals(d.header) && footer.equals(d.footer)) return;
        d.header = header;
        d.footer = footer;
        viewer.sendPlayerListHeaderAndFooter(lines(header, viewer), lines(footer, viewer));
    }

    private static Component lines(String text, Player player) {
        if (text.isEmpty()) return Component.empty();
        List<Component> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) out.add(Texts.parse(line, player));
        return Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), out);
    }

    private Design design(Player viewer) {
        String group = PlayerMeta.group(viewer);
        String world = viewer.getWorld().getName().toLowerCase(Locale.ROOT);
        for (Design d : designs) {
            if (!d.groups().isEmpty() && !d.groups().contains(group)) continue;
            if (d.permission() != null && !viewer.hasPermission(d.permission())) continue;
            if (!d.worlds().isEmpty() && !d.worlds().contains(world)) continue;
            return d;
        }
        return null;
    }

    /** Name, order, nametag and line below the name of a player: sent only when they changed. */
    private void draw(Player p) {
        Drawn d = drawn.get(p.getUniqueId());
        if (d == null) return;
        Map<String, String> f = fields(PlayerMeta.group(p));
        String listName = render(f.getOrDefault("tab-prefix", "") + f.getOrDefault("tab-name", "{player}") + f.getOrDefault("tab-suffix", ""), p);
        if (!listName.equals(d.listName)) {
            d.listName = listName;
            p.playerListName(Texts.parse(listName, p));
        }
        int order = order(p);
        if (order != d.order) {
            d.order = order;
            p.setPlayerListOrder(order);
        }
        if (nametagsOn) {
            String prefix = render(f.getOrDefault("tag-prefix", ""), p), suffix = render(f.getOrDefault("tag-suffix", ""), p);
            if (d.tagPrefix == null) {
                d.tagPrefix = prefix;
                d.tagSuffix = suffix;
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    nametags.createTeam(viewer, team(p), p.getName(), Texts.parse(prefix, p), Texts.parse(suffix, p), collides(p));
                }
            } else if (!prefix.equals(d.tagPrefix) || !suffix.equals(d.tagSuffix)) {
                d.tagPrefix = prefix;
                d.tagSuffix = suffix;
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    nametags.updateTeam(viewer, team(p), Texts.parse(prefix, p), Texts.parse(suffix, p), collides(p));
                }
            }
        }
        if (belowOn) {
            int value = number(render(belowValue, p));
            String text = render(belowText, p);
            if (!Objects.equals(d.belowValue, value) || !text.equals(d.belowText)) {
                d.belowValue = value;
                d.belowText = text;
                for (Player viewer : Bukkit.getOnlinePlayers()) nametags.score(viewer, p.getName(), value, shown(text, p));
            }
        }
    }

    private static Component shown(String text, Player p) {
        return text == null || text.isEmpty() ? null : Texts.parse(text, p);
    }

    private Map<String, String> fields(String group) {
        Map<String, String> out = new HashMap<>(groups.getOrDefault("_default_", Map.of()));
        out.putAll(groups.getOrDefault(group, Map.of()));
        return out;
    }

    /** Paper list order, higher first: each sorting value (0-999) one more "digit" of the order. */
    private int order(Player p) {
        long order = 0;
        for (String key : sorting) {
            int v = Math.max(0, Math.min(999, number(render(key, p))));
            order = order * 1000 + v;
        }
        return (int) Math.min(Integer.MAX_VALUE, order);
    }

    private static int number(String text) {
        String digits = text.replaceAll("[^0-9-]", "");
        if (digits.isEmpty() || digits.equals("-")) return 0;
        try {
            return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Long.parseLong(digits)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ templates

    /** Pieces first, then placeholders (Flux's own, then PlaceholderAPI). Colours are left for the parser. */
    String render(String template, Player p) {
        if (template == null || template.isEmpty()) return "";
        String text = template;
        if (text.indexOf('{') >= 0) {
            Matcher m = PIECE.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String v = piece(m.group(1), m.group(2), p);
                m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
            }
            m.appendTail(sb);
            text = sb.toString();
        }
        return text.indexOf('%') >= 0 ? Texts.placeholders(text, p) : text;
    }

    private String piece(String id, String arg, Player p) {
        switch (id) {
            case "player":
                return p.getName();
            case "nick": {
                String nick = Texts.plain(p.displayName());
                return nick.isBlank() ? p.getName() : nick;
            }
            case "ping":
                return String.valueOf(p.getPing());
            case "online":
                return String.valueOf(Bukkit.getOnlinePlayers().stream().filter(p::canSee).count());
            case "max":
                return String.valueOf(Bukkit.getMaxPlayers());
            case "version":
                return Bukkit.getMinecraftVersion();
            case "world":
                return p.getWorld().getName();
            case "group":
                return PlayerMeta.group(p);
            case "meta":
                return arg == null ? "" : PlayerMeta.meta(p, arg);
            case "date":
                return new SimpleDateFormat(arg == null ? "dd/MM/yyyy" : arg, Locale.FRANCE).format(new Date());
            case "tps": {
                double tps = Math.min(20, Bukkit.getTPS()[2]);
                return (tps >= 18 ? "&#2ECF7B" : tps >= 15 ? "&#FFD94D" : "&#FF5C5C") + Format.decimal(tps);
            }
            case "ram_used":
                return String.valueOf((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576);
            case "ram_max":
                return String.valueOf(Runtime.getRuntime().maxMemory() / 1_048_576);
            case "balance", "balance_raw", "balance_short": {
                Currency c = Economy.get(arg == null ? "vault" : arg);
                if (c == null) return "";
                double v = c.balance(p);
                return id.equals("balance") ? c.format(v) : id.equals("balance_raw") ? String.valueOf((long) v) : Economy.compact(v);
            }
            case "afk": {
                long seconds = arg == null ? 300 : Math.max(1, Format.parseDuration(arg));
                return p.getIdleDuration().getSeconds() >= seconds ? "true" : "";
            }
            case "animation": {
                Animation a = arg == null ? null : animations.get(arg.toLowerCase(Locale.ROOT));
                if (a == null || a.frames().isEmpty()) return "";
                return a.frames().get((int) ((System.currentTimeMillis() / a.intervalMs()) % a.frames().size()));
            }
            default:
                break;
        }
        Custom c = customs.get(id);
        if (c != null) return custom(c, p);
        return TabList.value(id, p);
    }

    /** A piece of tab.yml: {@code set} when the placeholder is not empty (or equals {@code equals}) or the permission is held. */
    private String custom(Custom c, Player p) {
        boolean on;
        if (c.permission() != null) {
            on = p.hasPermission(c.permission());
        } else if (c.placeholder() != null) {
            String v = render(c.placeholder(), p).trim();
            on = c.equals() == null ? !v.isEmpty() && !v.startsWith("%") : v.equalsIgnoreCase(c.equals());
        } else {
            on = false;
        }
        return on ? c.set() : c.empty();
    }
}
