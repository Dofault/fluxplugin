package fr.influencraft.fluxcosmetics;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.TitleOverlay;
import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.cosmetics.Tags;
import fr.influencraft.flux.api.data.Database;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.jobs.Jobs;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.permission.PlayerMeta;
import fr.influencraft.flux.api.profile.Profiles;
import fr.influencraft.flux.api.tab.TabList;
import fr.influencraft.flux.api.text.Messages;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

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
 * Player tags (FluxTags until 2026-11-21, merged here: a tag is a cosmetic). A player wears one tag at most, chosen
 * in the cosmetics menu (category Tags, {@link #menuEntries}) or with {@code /tags <tag>}:
 * <ul>
 *   <li>{@code rang}: follows his current rank (an older rank's tag cannot be kept);</li>
 *   <li>{@code metier_<job>}: unlocked at the first job tier (level 50), then a new look at every tier up to 300;</li>
 *   <li>specials ({@code tags.specials} of the config): unlocked by {@code fluxcosmetics.tag.<id>}, or by the tag's
 *   own {@code permission} (staff tags: {@code flux.admin}); {@code hidden} ones are only listed for who has them,
 *   {@code shortcut} gives one a command of its own ({@code /_o});</li>
 *   <li>tags of other plugins ({@link Tags#register}, FluxFamily).</li>
 * </ul>
 * A player who never picked wears the tag {@code tags.default-tags} gives his main group (a staff member his staff
 * tag) when he has it, else his rank. Images: pack {@code fluxtags} ({@code assets/tags/gen_tags.py}), whose
 * {@code tag_glyphs.yml} (size and index in the menu sheet) is read from the data folder.
 *
 * <pre>
 *   %fluxcosmetics_tag%          the worn tag's glyph (chat format), _tag_spaced with a space after it
 *   %fluxcosmetics_tag_name%     its name
 *   %fluxcosmetics_tags_unlocked%, %fluxcosmetics_tags_total%
 *   %fluxcosmetics_tag_profile%  the profile's title: the shown player's rank tag, drawn from the menu sheet
 *   {tag}                        piece of Flux's tab list
 *   [tags] &lt;id&gt; [quiet]          wear a tag (the menu: quiet, the mannequin shows it); its own tag again = rank
 * </pre>
 */
final class PlayerTags implements Listener {

    static final String NONE = "aucun";
    /** The rank tag: shown until the player picks another one (or none). */
    static final String RANK = "rang";
    /** Nothing picked yet (no row): the default tag is worked out from what the player has. */
    private static final String UNSET = "";
    /** First character of the menu sheet font (gen_tags.MENU_BASE). */
    private static final int MENU_BASE = 0xE000;
    /** Window x the profile's rank tag is centred on: over the 4 section columns (vanilla theme). */
    private static final int PROFILE_CENTRE = 115;
    private static final int TAG_HEIGHT = 9;

    /** A tag of the catalogue; {@code job}, {@code special} or {@code provided} set for those kinds. */
    private record Tag(String id, String job, String special, Tags.Provided provided) {
        Tag(String id, String job, String special) {
            this(id, job, special, null);
        }
    }

    private final FluxCosmetics plugin;
    /** Own tags (rebuilt by settings()); see all() for the tags of other plugins. */
    private final Map<String, Tag> catalogue = new LinkedHashMap<>();
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();
    private final Map<String, int[]> sizes = new HashMap<>();
    /** Main group -> tag worn by a player of that group who never picked one. */
    private final Map<String, String> defaults = new HashMap<>();
    private String table;
    private List<Integer> tiers = List.of(50, 100, 150, 200, 250, 300);
    private String rankPlaceholder;
    private boolean jobsLoaded;

    PlayerTags(FluxCosmetics plugin) {
        this.plugin = plugin;
    }

    void start() {
        table = Database.table("flux_cosmetics_tags");
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            // former names: FluxTags' tables (fluxtags_selected before 2026-10-31, then flux_tags_selected)
            Database.renameTable(c, Database.table("fluxtags_selected"), Database.table("flux_tags_selected"));
            Database.renameTable(c, Database.table("flux_tags_selected"), table);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (uuid " + Database.dialect().key(36)
                    + " PRIMARY KEY, tag VARCHAR(64) NOT NULL)");
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not create " + table + ": " + e.getMessage());
        }
        settings();
        Tags.provide(new Tags.Service() {
            public String selected(UUID player) {
                return selection(player);
            }

            public void select(UUID player, String tag) {
                write(player, tag);
                Player online = Bukkit.getPlayer(player);
                if (online != null) TabList.refresh(online);
            }
        });
        FluxApi.registerPlaceholders(plugin, "fluxcosmetics", this::placeholder);
        // {tag} of Flux's tab list (tab.yml), straight from Java
        TabList.piece(plugin, "tag", p -> {
            String image = shown(p);
            return image == null ? "" : glyph(image);
        });
        FluxApi.registerAction(plugin, "tags", (player, view, argument) -> {
            String[] parts = argument.trim().split("\\s+");
            pick(player, parts[0], parts.length > 1 && parts[1].equalsIgnoreCase("quiet"));
        });
        Commands.create(plugin, "tags")
                .description("Choose the tag shown next to your name.")
                .permission("fluxcosmetics.use")
                .executor((sender, label, args) -> {
                    Player p = Players.of(sender);
                    if (p == null) return true;
                    if (args.length > 0) wear(p, args[0].toLowerCase(Locale.ROOT));
                    else plugin.menu().showTags(p);
                    return true;
                })
                .completer((sender, args) -> {
                    Player p = Players.of(sender);
                    if (p == null || args.length != 1) return List.of();
                    String typed = args[0].toLowerCase(Locale.ROOT);
                    return all().values().stream().filter(t -> t.id().equals(NONE) || image(p, t) != null)
                            .map(Tag::id).filter(id -> id.startsWith(typed)).toList();
                })
                .register();
        shortcuts();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Profiles.register(plugin, "tags", 60, new Profiles.Section() {
            public ItemStack item(Player viewer, OfflinePlayer subject, int page) {
                Tag t = all().get(selection(subject.getUniqueId()));
                Player online = subject.getPlayer();
                Lore lore = Lore.create();
                String image = t == null || online == null ? null : image(online, t);
                lore.kv(text("current"), t == null || t.id().equals(NONE) ? "-"
                        : (image != null ? glyph(image) + " " : "") + name(t));
                if (online != null) lore.kv(text("unlocked"), unlocked(online) + "&7/&f" + total(online));
                if (Profiles.own(viewer)) lore.left(text("mine"));
                return Items.of("NAME_TAG", text("profile-name"), lore.build(), viewer);
            }

            public boolean click(Player viewer, OfflinePlayer subject, boolean right) {
                if (Profiles.own(viewer) && !right) plugin.menu().showTags(viewer);
                return false;
            }
        });
    }

    void stop() {
        Tags.provide(null);
    }

    /** {@code tags} section of the config, the glyph sizes. */
    void settings() {
        FileConfiguration config = plugin.getConfig();
        rankPlaceholder = config.getString("tags.rank-placeholder", "%luckperms_current_group_on_track_standard%");
        defaults.clear();
        ConfigurationSection byGroup = config.getConfigurationSection("tags.default-tags");
        if (byGroup != null) for (String group : byGroup.getKeys(false)) {
            defaults.put(group.toLowerCase(Locale.ROOT), byGroup.getString(group, ""));
        }
        tiers = config.getIntegerList("tags.job-tiers");
        if (tiers.isEmpty()) tiers = List.of(50, 100, 150, 200, 250, 300);
        catalogue.clear();
        catalogue.put(NONE, new Tag(NONE, null, null));
        catalogue.put(RANK, new Tag(RANK, null, null));
        jobsLoaded = Jobs.available() && !Jobs.get().ids().isEmpty();
        if (jobsLoaded) {
            for (String job : Jobs.get().ids()) catalogue.put("metier_" + job, new Tag("metier_" + job, job, null));
        }
        ConfigurationSection specials = config.getConfigurationSection("tags.specials");
        if (specials != null) for (String id : specials.getKeys(false)) catalogue.put(id, new Tag(id, null, id));
        sizes.clear();
        File file = new File(plugin.getDataFolder(), "tag_glyphs.yml");
        if (!file.exists()) plugin.getLogger().warning("tag_glyphs.yml missing: run assets/tags/gen_tags.py (no tag in the profile title).");
        YamlConfiguration glyphs = YamlConfiguration.loadConfiguration(file);
        for (String id : glyphs.getKeys(false)) {
            List<Integer> wh = glyphs.getIntegerList(id);
            // [width, height, index in the menu sheet] (gen_tags.py)
            if (wh.size() >= 3) sizes.put(id, new int[]{wh.get(0), wh.get(1), wh.get(2)});
        }
    }

    /** One command per special tag with a {@code shortcut}: wears that tag ({@code /_o} for the operator tag). */
    private void shortcuts() {
        ConfigurationSection specials = plugin.getConfig().getConfigurationSection("tags.specials");
        if (specials == null) return;
        for (String id : specials.getKeys(false)) {
            String label = specials.getString(id + ".shortcut", "").trim();
            if (label.isEmpty()) continue;
            Commands.create(plugin, "tag_" + id)
                    .description("Wear the tag " + specials.getString(id + ".name", id) + ".")
                    .permission(permission(id))
                    .aliases(label)
                    .suggest(false)
                    .executor((sender, typed, args) -> {
                        Player p = Players.of(sender);
                        if (p != null) wear(p, id);
                        return true;
                    })
                    .register();
        }
    }

    /** Own tags, then the tags given by other plugins ({@link Tags#register}), in the order of the menu. */
    private Map<String, Tag> all() {
        Map<String, Tags.Provided> provided = Tags.provided();
        if (provided.isEmpty()) return catalogue;
        Map<String, Tag> out = new LinkedHashMap<>(catalogue);
        for (Tags.Provided t : provided.values()) out.putIfAbsent(t.id(), new Tag(t.id(), null, null, t));
        return out;
    }

    /** EcoJobs loads its jobs after the server has started (after this plugin): the catalogue is rebuilt at first use. */
    private void jobsReady() {
        if (!jobsLoaded && Jobs.available() && !Jobs.get().ids().isEmpty()) settings();
    }

    // ---------------------------------------------------------------- tags of a player

    /** Image id of a tag for this player (rank or tier included), null when locked. */
    private String image(Player p, Tag t) {
        if (t.id().equals(NONE)) return null;
        if (t.job() != null) {
            int tier = tier(p, t.job());
            return tier == 0 ? null : "metier_" + t.job() + "_" + tier;
        }
        if (t.special() != null) return p.hasPermission(permission(t.special())) ? t.special() : null;
        if (t.provided() != null) return t.provided().image().apply(p);
        String rank = Texts.placeholders(rankPlaceholder, p).trim().toLowerCase(Locale.ROOT);
        return rank.isEmpty() || rank.indexOf('%') >= 0 ? null : "rang_" + rank;
    }

    /** Node unlocking a special tag: its {@code permission}, else {@code fluxcosmetics.tag.<id>}. */
    private String permission(String special) {
        return plugin.getConfig().getString("tags.specials." + special + ".permission", "fluxcosmetics.tag." + special);
    }

    /** A special tag only listed for the players who have it (staff tags). */
    private boolean hidden(Tag t) {
        return t.special() != null && plugin.getConfig().getBoolean("tags.specials." + t.special() + ".hidden");
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
        String picked = selected.computeIfAbsent(id, this::read);
        if (!picked.equals(UNSET)) return picked;
        Player p = Bukkit.getPlayer(id);
        if (p != null && !defaults.isEmpty()) {
            String tag = defaults.get(PlayerMeta.group(p));
            Tag t = tag == null ? null : catalogue.get(tag);
            if (t != null && image(p, t) != null) return tag;
        }
        return RANK;
    }

    private String read(UUID id) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("SELECT tag FROM " + table + " WHERE uuid = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : UNSET;
            }
        } catch (SQLException e) {
            return UNSET;
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
            plugin.getLogger().warning("Could not save the tag of " + id + ": " + e.getMessage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        selected.remove(e.getPlayer().getUniqueId());
    }

    /** The worn tag's image, null when none (or no longer unlocked). */
    private String shown(Player p) {
        Tag t = all().get(selection(p.getUniqueId()));
        return t == null ? null : image(p, t);
    }

    /** Id of the tag he shows now, null when he shows none (saved outfits keep it). */
    String shownTag(Player p) {
        jobsReady();
        String id = selection(p.getUniqueId());
        Tag t = all().get(id);
        return t != null && image(p, t) != null ? id : null;
    }

    /** Picture of a tag in a menu: his look of it, or its first look while locked; null when unknown. */
    String menuImage(Player p, String id) {
        jobsReady();
        Tag t = all().get(id);
        if (t == null) return null;
        String image = image(p, t);
        return image != null ? image : lockedImage(t);
    }

    private String name(Tag t) {
        if (t.id().equals(NONE)) return text("none-name");
        if (t.job() != null) return strip(Jobs.get().displayName(t.job()));
        if (t.special() != null) return plugin.getConfig().getString("tags.specials." + t.special() + ".name", t.special());
        if (t.provided() != null) return t.provided().name();
        return text("rank-name");
    }

    private long unlocked(Player p) {
        return all().values().stream().filter(t -> !t.id().equals(NONE) && image(p, t) != null).count();
    }

    /** Tags he can see: hidden ones only when he has them. */
    private long total(Player p) {
        return all().values().stream().filter(t -> !t.id().equals(NONE) && (!hidden(t) || image(p, t) != null)).count();
    }

    private static String strip(String s) {
        return s.replaceAll("&#[0-9A-Fa-f]{6}|&[0-9a-fk-or]", "");
    }

    private Messages messages() {
        return plugin.messages();
    }

    private String text(String key, String... vars) {
        return messages().raw("tags." + key, vars);
    }

    // ---------------------------------------------------------------- the cosmetics menu

    /**
     * The tags category of the cosmetics menu: first a barrier taking the worn tag off (the rank's shows again), then
     * every tag as a name tag whose name is the tag image, the worn one glowing. Clicks are quiet: the mannequin shows
     * the tag. The worn tag clicked again comes off (back to the rank's, never to no tag at all).
     */
    List<ListEntry> menuEntries(Player p) {
        jobsReady();
        List<ListEntry> out = new ArrayList<>();
        String current = selection(p.getUniqueId());
        boolean rankShown = false;
        for (Tag t : all().values()) {
            if (t.id().equals(NONE)) continue;
            String image = image(p, t);
            boolean unlocked = image != null;
            if (!unlocked && hidden(t)) continue;
            boolean shown = t.id().equals(current) && unlocked;
            if (t.id().equals(RANK)) rankShown = shown;
            Lore lore = Lore.create();
            if (t.job() != null) {
                lore.description(text("job-description", "%job%", name(t)));
                int tier = tier(p, t.job());
                lore.kv(text("level"), level(p, t.job()));
                if (tier > 0) lore.kv(text("rarity"), messages().rawList("tags.rarities").get(Math.min(tier, 6) - 1));
                if (tier == 0) lore.ko(text("unlock-job", "%level%", String.valueOf(tiers.get(0)), "%job%", name(t)));
                else if (tier < tiers.size()) lore.info(text("next", "%level%", String.valueOf(tiers.get(tier))));
                else lore.ok(text("max"));
            } else if (t.special() != null) {
                lore.description(plugin.getConfig().getString("tags.specials." + t.special() + ".description", ""));
                if (!unlocked) lore.ko(text("unlock-special"));
            } else if (t.provided() != null) {
                lore.description(t.provided().description());
                if (!unlocked) lore.ko(text("locked-tag"));
            } else {
                lore.description(text("rank-description"));
                if (!unlocked) lore.ko(text("locked-tag"));
            }
            if (shown) lore.ok(text("current"));
            if (unlocked) lore.left(text(shown ? "remove" : "pick"));
            String picture = unlocked ? image : lockedImage(t);
            ItemStack item = Items.of("NAME_TAG", picture == null ? name(t) : "&f:" + picture + ":", lore.build(), p);
            if (shown) glint(item);
            String click = !unlocked ? "[sound] @error"
                    : shown ? (t.id().equals(RANK) ? "[sound] @error" : "[tags] " + RANK + " quiet")
                    : "[tags] " + t.id() + " quiet";
            out.add(ListEntry.of(t.id()).item(item).set("clic", click));
        }
        out.addFirst(noTag(p, rankShown));
        return out;
    }

    /** First choice of the tags: a barrier that takes the worn tag off, the rank's tag showing again. */
    private ListEntry noTag(Player viewer, boolean rankShown) {
        Lore lore = Lore.create().description(messages().raw("menu.no-tag-description"));
        if (rankShown) lore.ok(messages().raw("menu.no-tag-current"));
        else lore.left(messages().raw("menu.take-off"));
        ItemStack item = Items.of("BARRIER", messages().raw("menu.none-name"), lore.build(), viewer);
        if (rankShown) glint(item);
        return ListEntry.of(NONE).item(item).set("clic", rankShown ? "[sound] @error" : "[tags] " + RANK + " quiet");
    }

    private static void glint(ItemStack item) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
    }

    /** Image drawn for a locked tag: the first look of a job, else the tag's own image id. */
    private static String lockedImage(Tag t) {
        if (t.job() != null) return "metier_" + t.job() + "_1";
        return t.special() != null ? t.special() : t.provided() != null ? t.id() : null;
    }

    // ---------------------------------------------------------------- wearing a tag

    /** Wears a tag by its id (command): unlike a click in the menu, wearing the current tag again keeps it. */
    private void wear(Player p, String id) {
        jobsReady();
        Tag t = all().get(id);
        if (t == null || (!id.equals(NONE) && image(p, t) == null)) {
            messages().send(p, t == null || hidden(t) ? "tags.unknown" : "tags.locked");
            return;
        }
        write(p.getUniqueId(), id);
        if (id.equals(NONE)) messages().send(p, "tags.cleared");
        else messages().send(p, "tags.picked", "%tag%", name(t));
        TabList.refresh(p);
    }

    /** A click in a menu ({@code [tags]} action): the worn tag again (or none) takes it off. */
    void pick(Player p, String id, boolean quiet) {
        jobsReady();
        Tag t = all().get(id);
        if (t == null) return;
        String current = selection(p.getUniqueId());
        if (id.equals(NONE) || id.equals(current)) {
            write(p.getUniqueId(), NONE);
            if (!quiet) messages().send(p, "tags.cleared");
            TabList.refresh(p);
            return;
        }
        if (image(p, t) == null) {
            if (quiet) Sounds.cue(p, "error");
            else messages().send(p, "tags.locked");
            return;
        }
        write(p.getUniqueId(), id);
        if (!quiet) messages().send(p, "tags.picked", "%tag%", name(t));
        TabList.refresh(p);
    }

    /** {@code /cosmetique tag <player> <tag|aucun>} (admin): no unlock check, offline players too. */
    void adminSet(CommandSender sender, String playerName, String tag) {
        jobsReady();
        OfflinePlayer target = Players.find(playerName);
        if (target == null) {
            messages().send(sender, "admin.unknown-player", "%player%", playerName);
            return;
        }
        String id = tag.toLowerCase(Locale.ROOT);
        if (!all().containsKey(id)) {
            messages().send(sender, "tags.unknown");
            return;
        }
        Tags.select(target.getUniqueId(), id);
        messages().send(sender, "tags.admin-done", "%player%", Players.name(target, playerName), "%tag%", id);
    }

    /** Every tag id, for the admin command's completion. */
    List<String> ids() {
        jobsReady();
        return List.copyOf(all().keySet());
    }

    // ---------------------------------------------------------------- placeholders and pictures

    private String placeholder(Player p, String key) {
        jobsReady();
        if (key.equals("tag_profile")) return p == null ? "" : profile(p);
        if (p == null) return "";
        String image = shown(p);
        return switch (key) {
            case "tag" -> image == null ? "" : glyph(image);
            case "tag_spaced" -> image == null ? "" : glyph(image) + " ";
            case "tag_name" -> image == null ? "" : name(all().get(selection(p.getUniqueId())));
            case "tags_unlocked" -> String.valueOf(unlocked(p));
            case "tags_total" -> String.valueOf(total(p));
            default -> null;
        };
    }

    /** The rank tag of the player whose profile the viewer looks at, on the profile's line above the sections. */
    private String profile(Player viewer) {
        OfflinePlayer subject = Profiles.subject(viewer);
        String rank = Texts.placeholders(rankPlaceholder, subject).trim().toLowerCase(Locale.ROOT);
        int[] size = sizes.get("rang_" + rank);
        if (rank.isEmpty() || rank.indexOf('%') >= 0 || size == null) return "";
        int width = (int) Math.round(size[0] * (double) TAG_HEIGHT / size[1]);
        TitleOverlay out = new TitleOverlay("", 0);
        out.addText("<font:fluxtags:p>" + (char) (MENU_BASE + size[2]) + "</font>", PROFILE_CENTRE - width / 2, width, "&f");
        return out.moveTo(0).resolved();
    }

    /** Chat glyph, white (a tag keeps its own colours). */
    private static String glyph(String image) {
        String g = CustomItems.glyph("fluxtags:" + image);
        return g == null ? "" : "&f" + g;
    }
}
