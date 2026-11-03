package fr.influencraft.fluxtags;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.TitleOverlay;
import fr.influencraft.flux.api.data.Database;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.jobs.Jobs;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.fluxtags.api.Tags;
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
 *   <li>{@code rang} (shown until the player picks another tag or none): follows his current rank (an older rank's
 *   tag cannot be kept);</li>
 *   <li>{@code metier_<job>}: unlocked at the first job tier (level 50), then a new look at every tier up to 300;</li>
 *   <li>specials ({@code specials} of the config): unlocked by {@code fluxtags.tag.<id>}.</li>
 * </ul>
 * Images: pack {@code fluxtags} of ItemsAdder ({@code assets/gen_tags.py}), glyph {@code <image>} for the chat and
 * {@code <image>_r<row>} for row 1 to 5 of the menu, {@code rang_<rank>_p} for the profile. Placeholders:
 * {@code %fluxtags_tag%}, {@code %fluxtags_tag_spaced%}, {@code %fluxtags_name%}, {@code %fluxtags_unlocked%},
 * {@code %fluxtags_total%}, {@code %fluxtags_rows%} (title of /tags), {@code %fluxtags_profile%} (title of the profile:
 * the rank tag of the shown player).
 */
public final class FluxTags extends FluxPlugin implements Listener {

    private static final String NONE = "aucun";
    /** The rank tag: shown until the player picks another one (or none). */
    private static final String RANK = "rang";
    private static final int ROWS = 6;
    /** Window x of the tags in /tags and of the rank tag in the profile (assets/gen_fonds.py). */
    private static final int TAG_X = 46;
    private static final int PROFILE_X = 110;   // right part of the identity card header (vanilla theme)
    private static final int TAG_HEIGHT = 9;

    /** A tag of the catalogue; {@code job} or {@code special} set for those kinds. */
    private record Tag(String id, String job, String special, Tags.Provided provided) {
        Tag(String id, String job, String special) {
            this(id, job, special, null);
        }
    }

    /** Own tags (rebuilt by settings()); see all() for the tags of other plugins. */
    private final Map<String, Tag> catalogue = new LinkedHashMap<>();
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();
    private final Map<String, int[]> sizes = new HashMap<>();
    private String table;
    private List<Integer> tiers = List.of(50, 100, 150, 200, 250, 300);
    private String rankPlaceholder;
    private boolean jobsLoaded;

    @Override
    protected void enable() {
        table = Database.table("flux_tags_selected");
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            Database.renameTable(c, Database.table("fluxtags_selected"), table); // name before 2026-10-31
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (uuid " + Database.dialect().key(36)
                    + " PRIMARY KEY, tag VARCHAR(64) NOT NULL)");
        } catch (SQLException e) {
            getLogger().severe("Could not create " + table + ": " + e.getMessage());
        }
        settings();
        Tags.bind(new Tags.Service() {
            public String selected(UUID player) {
                return selection(player);
            }

            public void select(UUID player, String tag) {
                write(player, tag);
                Player online = Bukkit.getPlayer(player);
                if (online != null) fr.influencraft.flux.api.tab.TabList.refresh(online);
            }
        });
        placeholders("fluxtags", this::placeholder);
        // {tag} of Flux's tab list (tab.yml), straight from Java
        fr.influencraft.flux.api.tab.TabList.piece(this, "tag", p -> {
            String image = shown(p);
            return image == null ? "" : glyph(image);
        });
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
                        : args.length == 3 ? List.copyOf(all().keySet()) : List.of())
                .register();
        Bukkit.getPluginManager().registerEvents(this, this);
        fr.influencraft.flux.api.profile.Profiles.register(this, "tags", 60, new fr.influencraft.flux.api.profile.Profiles.Section() {
            public org.bukkit.inventory.ItemStack item(Player viewer, OfflinePlayer subject, int page) {
                Tag t = all().get(selection(subject.getUniqueId()));
                Player online = subject.getPlayer();
                Lore lore = Lore.create();
                String image = t == null || online == null ? null : image(online, t);
                lore.kv(messages().raw("current"), t == null || t.id().equals(NONE) ? "-"
                        : (image != null ? glyph(image) + " " : "") + name(viewer, t));
                if (online != null) lore.kv("Débloqués", all().values().stream()
                        .filter(x -> !x.id().equals(NONE) && image(online, x) != null).count() + "&7/&f" + (all().size() - 1));
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
        catalogue.put(RANK, new Tag(RANK, null, null));
        jobsLoaded = Jobs.available() && !Jobs.get().ids().isEmpty();
        if (jobsLoaded) {
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

    /** Own tags, then the tags given by other plugins ({@link Tags#register}), in the order of /tags. */
    private Map<String, Tag> all() {
        Map<String, Tags.Provided> provided = Tags.provided();
        if (provided.isEmpty()) return catalogue;
        Map<String, Tag> out = new LinkedHashMap<>(catalogue);
        for (Tags.Provided t : provided.values()) out.putIfAbsent(t.id(), new Tag(t.id(), null, null, t));
        return out;
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
        if (t.provided() != null) return t.provided().image().apply(p);
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
                return rs.next() ? rs.getString(1) : RANK;
            }
        } catch (SQLException e) {
            return RANK;
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
        Tag t = all().get(selection(p.getUniqueId()));
        return t == null ? null : image(p, t);
    }

    private String name(Player p, Tag t) {
        if (t.id().equals(NONE)) return messages().raw("none-name");
        if (t.job() != null) return strip(Jobs.get().displayName(t.job()));
        if (t.special() != null) return getConfig().getString("specials." + t.special() + ".name", t.special());
        if (t.provided() != null) return t.provided().name();
        return messages().raw("rank-name");
    }

    private static String strip(String s) {
        return s.replaceAll("&#[0-9A-Fa-f]{6}|&[0-9a-fk-or]", "");
    }

    // ---------------------------------------------------------------- menu

    /** EcoJobs loads its jobs after the server has started (after this plugin): the catalogue is rebuilt at first use. */
    private void jobsReady() {
        if (!jobsLoaded && Jobs.available() && !Jobs.get().ids().isEmpty()) settings();
    }

    private List<ListEntry> entries(Player p) {
        jobsReady();
        List<ListEntry> out = new ArrayList<>();
        String current = selection(p.getUniqueId());
        for (Tag t : all().values()) {
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
            } else if (t.provided() != null) {
                lore.description(t.provided().description());
                if (!unlocked) lore.ko(messages().raw("locked-tag"));
            } else {
                lore.description(messages().raw("rank-description"));
                if (!unlocked) lore.ko(messages().raw("locked-tag"));
            }
            if (shown) lore.ok(messages().raw("current"));
            if (unlocked) {
                if (shown && !t.id().equals(NONE)) lore.left(messages().raw("remove"));
                else if (!shown) lore.left(messages().raw("pick"));
            }
            // vanilla theme (2026-10-30): every tag is a name tag, the worn one glows; its name is the tag image itself
            String shownImage = image == null ? lockedImage(t) : image;
            String title = t.id().equals(NONE) || shownImage == null ? name(p, t) : "&f:" + shownImage + ":";
            org.bukkit.inventory.ItemStack tag = Items.of("NAME_TAG", title, lore.build(), p);
            if (shown && tag != null) {
                org.bukkit.inventory.meta.ItemMeta meta = tag.getItemMeta();
                meta.setEnchantmentGlintOverride(true);
                tag.setItemMeta(meta);
            }
            out.add(ListEntry.of(t.id()).item(tag)
                    .set("clic", unlocked ? "[tags] " + t.id() : "[sound] @error")
                    .set("image", image == null ? lockedImage(t) : image)
                    .set("locked", String.valueOf(!unlocked)));
        }
        return out;
    }

    /** Image drawn dark for a locked tag: the first look of a job, else the tag's own image id. */
    private static String lockedImage(Tag t) {
        if (t.job() != null) return "metier_" + t.job() + "_1";
        return t.special() != null ? t.special() : t.provided() != null ? t.id() : null;
    }

    private void pick(Player p, String id) {
        Tag t = all().get(id);
        if (t == null) return;
        String current = selection(p.getUniqueId());
        if (id.equals(NONE) || id.equals(current)) {
            write(p.getUniqueId(), NONE);
            messages().send(p, "cleared");
            fr.influencraft.flux.api.tab.TabList.refresh(p);
            return;
        }
        if (image(p, t) == null) {
            messages().send(p, "locked");
            return;
        }
        write(p.getUniqueId(), id);
        messages().send(p, "picked", "%tag%", name(p, t));
        fr.influencraft.flux.api.tab.TabList.refresh(p);
    }

    /**
     * The tag images of the shown page, one per row next to its button (title text, cursor at the window's left
     * edge before and after). A locked tag is drawn dark.
     */
    private String rows(Player p) {
        List<ListEntry> all = Lists.entries(p, "tags");
        int page = Lists.state(p, "tags").page();
        TitleOverlay out = new TitleOverlay("", 0);
        for (int r = 0; r < ROWS && page * ROWS + r < all.size(); r++) {
            ListEntry e = all.get(page * ROWS + r);
            String image = e.values().get("image");
            if (image != null) place(out, image + "_r" + (r + 1), image, TAG_X, "true".equals(e.values().get("locked")) ? "&8" : "&f");
        }
        return out.moveTo(0).resolved();
    }

    /** The rank tag of the player whose profile the viewer looks at, on the profile's line above the sections. */
    private String profile(Player viewer) {
        OfflinePlayer subject = fr.influencraft.flux.api.profile.Profiles.subject(viewer);
        String rank = Texts.placeholders(rankPlaceholder, subject).trim().toLowerCase(Locale.ROOT);
        if (rank.isEmpty() || rank.indexOf('%') >= 0) return "";
        TitleOverlay out = new TitleOverlay("", 0);
        place(out, "rang_" + rank + "_p", "rang_" + rank, PROFILE_X, "&f");
        return out.moveTo(0).resolved();
    }

    private void place(TitleOverlay out, String glyph, String image, int x, String colour) {
        int[] size = sizes.get(image);
        if (size != null && CustomItems.glyph("fluxtags:" + glyph) != null)
            out.add("fluxtags:" + glyph, x, (int) Math.round(size[0] * (double) TAG_HEIGHT / size[1]), colour);
    }

    // ---------------------------------------------------------------- placeholders and admin

    private String placeholder(Player p, String key) {
        jobsReady();
        switch (key) {
            case "total" -> {
                return String.valueOf(all().size() - 1);
            }
            case "rows" -> {
                return p == null ? "" : rows(p);
            }
            case "profile" -> {
                return p == null ? "" : profile(p);
            }
            default -> {
            }
        }
        if (p == null) return "";
        String image = shown(p);
        return switch (key) {
            case "tag" -> image == null ? "" : glyph(image);
            case "tag_spaced" -> image == null ? "" : glyph(image) + " ";
            case "name" -> image == null ? "" : name(p, all().get(selection(p.getUniqueId())));
            case "unlocked" -> String.valueOf(all().values().stream().filter(t -> !t.id().equals(NONE) && image(p, t) != null).count());
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
        if (!all().containsKey(tag)) {
            messages().send(sender, "unknown-tag");
            return true;
        }
        write(target.getUniqueId(), tag);
        messages().send(sender, "admin-done", "%player%", Players.name(target, args[1]), "%tag%", tag);
        return true;
    }
}
