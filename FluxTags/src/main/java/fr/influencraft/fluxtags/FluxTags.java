package fr.influencraft.fluxtags;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.data.Database;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.jobs.Jobs;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FluxTags: collectible tags. A player shows one tag at most, picked in {@code /tags}:
 * <ul>
 *   <li>{@code rang}: follows his current rank (an older rank's tag cannot be kept);</li>
 *   <li>{@code metier_<job>}: unlocked at the first job tier (level 50), then a new look at every tier up to 300;</li>
 *   <li>specials ({@code specials} of the config): unlocked by {@code fluxtags.tag.<id>}.</li>
 * </ul>
 * Images: pack {@code fluxtags} of ItemsAdder ({@code assets/gen_tags.py}), glyph {@code <image>} for the chat and
 * {@code <image>_r<row>} for row 1 to 5 of the menu. Placeholders: {@code %fluxtags_tag%}, {@code %fluxtags_tag_spaced%},
 * {@code %fluxtags_name%}, {@code %fluxtags_unlocked%}, {@code %fluxtags_total%}, {@code %fluxtags_rows%} (menu title).
 */
public final class FluxTags extends FluxPlugin implements Listener {

    private static final String NONE = "aucun";
    private static final int ROWS = 5;
    private static final int TAG_X = 43;

    /** A tag of the catalogue; {@code job} or {@code special} set for those kinds. */
    private record Tag(String id, String job, String special) {
    }

    private final Map<String, Tag> catalogue = new LinkedHashMap<>();
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();
    private final Map<String, int[]> sizes = new HashMap<>();
    private String table;
    private List<Integer> tiers = List.of(50, 100, 150, 200, 250, 300);
    private String rankPlaceholder;

    @Override
    protected void enable() {
        table = Database.table("fluxtags_selected");
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (uuid " + Database.dialect().key(36)
                    + " PRIMARY KEY, tag VARCHAR(64) NOT NULL)");
        } catch (SQLException e) {
            getLogger().severe("Could not create " + table + ": " + e.getMessage());
        }
        settings();
        placeholders("fluxtags", this::placeholder);
        list("tags", q -> entries(q.viewer()));
        FluxApi.registerAction(this, "tags", (player, view, argument) -> pick(player, argument.trim()));
        command("tags")
                .description("Choose the tag shown next to your name.")
                .permission("fluxtags.use")
                .executor((sender, label, args) -> {
                    Player p = Players.of(sender);
                    if (p != null) FluxApi.open(p, "tags");
                    return true;
                })
                .register();
        command("fluxtags")
                .description("Tags administration.")
                .permission("fluxtags.admin")
                .usage(messages().raw("admin-usage"))
                .executor(this::onAdmin)
                .completer((sender, args) -> args.length == 1 ? List.of("set", "clear", "reload")
                        : args.length == 2 ? Players.complete(args[1])
                        : args.length == 3 ? List.copyOf(catalogue.keySet()) : List.of())
                .register();
        Bukkit.getPluginManager().registerEvents(this, this);
        fr.influencraft.flux.api.profile.Profiles.register(this, "tags", 60, new fr.influencraft.flux.api.profile.Profiles.Section() {
            public org.bukkit.inventory.ItemStack item(Player viewer, OfflinePlayer subject, int page) {
                Tag t = catalogue.get(selection(subject.getUniqueId()));
                Player online = subject.getPlayer();
                Lore lore = Lore.create();
                String image = t == null || online == null ? null : image(online, t);
                lore.kv(messages().raw("current"), t == null || t.id().equals(NONE) ? "-"
                        : (image != null ? glyph(image) + " " : "") + name(viewer, t));
                if (online != null) lore.kv("Débloqués", catalogue.values().stream()
                        .filter(x -> !x.id().equals(NONE) && image(online, x) != null).count() + "&7/&f" + (catalogue.size() - 1));
                if (fr.influencraft.flux.api.profile.Profiles.own(viewer)) lore.left("Mes tags");
                return Items.of("NAME_TAG", "Tags", lore.build(), viewer);
            }

            public boolean click(Player viewer, OfflinePlayer subject, boolean right) {
                if (fr.influencraft.flux.api.profile.Profiles.own(viewer) && !right) FluxApi.open(viewer, "tags");
                return false;
            }
        });
    }

    @Override
    public void reload() {
        super.reload();
        settings();
    }

    private void settings() {
        rankPlaceholder = getConfig().getString("rank-placeholder", "%luckperms_current_group_on_track_standard%");
        tiers = getConfig().getIntegerList("job-tiers");
        if (tiers.isEmpty()) tiers = List.of(50, 100, 150, 200, 250, 300);
        catalogue.clear();
        catalogue.put(NONE, new Tag(NONE, null, null));
        catalogue.put("rang", new Tag("rang", null, null));
        if (Jobs.available()) {
            for (String job : Jobs.get().ids()) catalogue.put("metier_" + job, new Tag("metier_" + job, job, null));
        }
        ConfigurationSection specials = getConfig().getConfigurationSection("specials");
        if (specials != null) for (String id : specials.getKeys(false)) catalogue.put(id, new Tag(id, null, id));
        sizes.clear();
        YamlConfiguration glyphs = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "glyphs.yml"));
        for (String id : glyphs.getKeys(false)) {
            List<Integer> wh = glyphs.getIntegerList(id);
            if (wh.size() == 2) sizes.put(id, new int[]{wh.get(0), wh.get(1)});
        }
    }

    // ---------------------------------------------------------------- tags of a player

    /** Image id of a tag for this player (rank or tier included), null when locked. */
    private String image(Player p, Tag t) {
        if (t.id().equals(NONE)) return null;
        if (t.job() != null) {
            int tier = tier(p, t.job());
            return tier == 0 ? null : "metier_" + t.job() + "_" + tier;
        }
        if (t.special() != null) return p.hasPermission("fluxtags.tag." + t.special()) ? t.special() : null;
        String rank = Texts.placeholders(rankPlaceholder, p).trim().toLowerCase(Locale.ROOT);
        return rank.isEmpty() || rank.indexOf('%') >= 0 ? null : "rang_" + rank;
    }

    private int level(Player p, String job) {
        return Jobs.available() ? Jobs.get().level(p, job) : 0;
    }

    /** 0 = locked, 1 to 6 = tier reached. */
    private int tier(Player p, String job) {
        int lvl = level(p, job), tier = 0;
        for (int t : tiers) if (lvl >= t) tier++;
        return tier;
    }

    private String selection(UUID id) {
        return selected.computeIfAbsent(id, this::read);
    }

    private String read(UUID id) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("SELECT tag FROM " + table + " WHERE uuid = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : NONE;
            }
        } catch (SQLException e) {
            return NONE;
        }
    }

    private void write(UUID id, String tag) {
        selected.put(id, tag);
        String sql = Database.dialect().upsert(table, List.of("uuid", "tag"), List.of("uuid"), List.of("tag"));
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id.toString());
            ps.setString(2, tag);
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().warning("Could not save the tag of " + id + ": " + e.getMessage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        selected.remove(e.getPlayer().getUniqueId());
    }

    /** The shown tag's image, null when none (or no longer unlocked). */
    private String shown(Player p) {
        Tag t = catalogue.get(selection(p.getUniqueId()));
        return t == null ? null : image(p, t);
    }

    private String name(Player p, Tag t) {
        if (t.id().equals(NONE)) return messages().raw("none-name");
        if (t.job() != null) return strip(Jobs.get().displayName(t.job()));
        if (t.special() != null) return getConfig().getString("specials." + t.special() + ".name", t.special());
        return messages().raw("rank-name");
    }

    private static String strip(String s) {
        return s.replaceAll("&#[0-9A-Fa-f]{6}|&[0-9a-fk-or]", "");
    }

    // ---------------------------------------------------------------- menu

    private List<ListEntry> entries(Player p) {
        List<ListEntry> out = new ArrayList<>();
        String current = selection(p.getUniqueId());
        for (Tag t : catalogue.values()) {
            String image = image(p, t);
            boolean unlocked = t.id().equals(NONE) || image != null;
            boolean shown = t.id().equals(current) && unlocked;
            Lore lore = Lore.create();
            if (t.id().equals(NONE)) lore.description(messages().raw("none-description"));
            else if (t.job() != null) {
                lore.description(messages().raw("job-description", "%job%", name(p, t)));
                int tier = tier(p, t.job());
                lore.kv(messages().raw("level"), level(p, t.job()));
                if (tier > 0) lore.kv(messages().raw("rarity"), messages().rawList("rarities").get(Math.min(tier, 6) - 1));
                if (tier == 0) lore.ko(messages().raw("unlock-job", "%level%", String.valueOf(tiers.get(0)), "%job%", name(p, t)));
                else if (tier < tiers.size()) lore.info(messages().raw("next", "%level%", String.valueOf(tiers.get(tier))));
                else lore.ok(messages().raw("max"));
            } else if (t.special() != null) {
                lore.description(getConfig().getString("specials." + t.special() + ".description", ""));
                if (!unlocked) lore.ko(messages().raw("unlock-special"));
            } else {
                lore.description(messages().raw("rank-description"));
            }
            if (shown) lore.ok(messages().raw("current"));
            if (unlocked) {
                if (shown && !t.id().equals(NONE)) lore.left(messages().raw("remove"));
                else if (!shown) lore.left(messages().raw("pick"));
            }
            String icon = !unlocked ? "itemsadder:influitems:lock" : shown ? "itemsadder:influitems:accept" : "itemsadder:influitems:unlock";
            out.add(ListEntry.of(t.id()).item(Items.of(icon, name(p, t), lore.build(), p))
                    .set("clic", unlocked ? "[tags] " + t.id() : "[sound] @error")
                    .set("image", image == null ? (t.job() != null ? "metier_" + t.job() + "_1" : t.special()) : image)
                    .set("locked", String.valueOf(!unlocked)));
        }
        return out;
    }

    private void pick(Player p, String id) {
        Tag t = catalogue.get(id);
        if (t == null) return;
        String current = selection(p.getUniqueId());
        if (id.equals(NONE) || id.equals(current)) {
            write(p.getUniqueId(), NONE);
            messages().send(p, "cleared");
            return;
        }
        if (image(p, t) == null) {
            messages().send(p, "locked");
            return;
        }
        write(p.getUniqueId(), id);
        messages().send(p, "picked", "%tag%", name(p, t));
    }

    /**
     * The tag images of the shown page, one per row next to its button (title text, cursor at the window's left
     * edge before and after). A locked tag is drawn dark.
     */
    private String rows(Player p) {
        List<ListEntry> all = Lists.entries(p, "tags");
        int page = Lists.state(p, "tags").page();
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (int r = 0; r < ROWS; r++) {
            int index = page * ROWS + r;
            if (index >= all.size()) break;
            ListEntry e = all.get(index);
            String image = e.values().get("image");
            if (image == null) continue;
            String glyph = CustomItems.glyph("fluxtags:" + image + "_r" + (r + 1));
            int[] size = sizes.get(image);
            if (glyph == null || size == null) continue;
            out.append(space(TAG_X - cursor)).append("true".equals(e.values().get("locked")) ? "&8" : "&f").append(glyph);
            cursor = TAG_X + (int) (0.5 + size[0] * 18.0 / size[1]) + 1;
        }
        out.append(space(-cursor));
        return out.toString();
    }

    private static String space(int n) {
        if (n == 0) return "";
        String g = CustomItems.glyphs(":offset_" + n + ":");
        return g == null || g.startsWith(":") ? "" : g;
    }

    // ---------------------------------------------------------------- placeholders and admin

    private String placeholder(Player p, String key) {
        switch (key) {
            case "total" -> {
                return String.valueOf(catalogue.size() - 1);
            }
            case "rows" -> {
                return p == null ? "" : rows(p);
            }
            default -> {
            }
        }
        if (p == null) return "";
        String image = shown(p);
        return switch (key) {
            case "tag" -> image == null ? "" : glyph(image);
            case "tag_spaced" -> image == null ? "" : glyph(image) + " ";
            case "name" -> image == null ? "" : name(p, catalogue.get(selection(p.getUniqueId())));
            case "unlocked" -> String.valueOf(catalogue.values().stream().filter(t -> !t.id().equals(NONE) && image(p, t) != null).count());
            default -> null;
        };
    }

    /** Chat glyph, white (a tag keeps its own colours). */
    private static String glyph(String image) {
        String g = CustomItems.glyph("fluxtags:" + image);
        return g == null ? "" : "&f" + g;
    }

    private boolean onAdmin(CommandSender sender, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("reload")) {
            reload();
            messages().send(sender, "reloaded");
            return true;
        }
        if (args.length < 2 || !(sub.equals("set") || sub.equals("clear"))) {
            messages().send(sender, "admin-usage");
            return true;
        }
        OfflinePlayer target = Players.find(args[1]);
        if (target == null) {
            messages().send(sender, "unknown-player");
            return true;
        }
        String tag = sub.equals("clear") ? NONE : args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        if (!catalogue.containsKey(tag)) {
            messages().send(sender, "unknown-tag");
            return true;
        }
        write(target.getUniqueId(), tag);
        messages().send(sender, "admin-done", "%player%", Players.name(target, args[1]), "%tag%", tag);
        return true;
    }
}
