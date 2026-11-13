package fr.influencraft.fluxpass;

import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.text.ItemSprites;
import fr.influencraft.flux.api.text.Messages;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.fluxpass.Missions.Category;
import fr.influencraft.fluxpass.Missions.Mission;
import fr.influencraft.fluxpass.Missions.Variant;
import fr.influencraft.fluxpass.PassStore.PlayerPass;
import fr.influencraft.fluxpass.PassStore.Progress;
import fr.influencraft.fluxpass.Season.Track;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the pass menu and the sidebar show, built in Java.
 * <ul>
 *   <li>lists {@code pass_free_<page>} / {@code pass_premium_<page>}: the tiers of a page of the path (entry id = the
 *   tier), in the state of the player (claimed, to claim, premium required, locked)</li>
 *   <li>{@code pass_categories}: the mission categories, the one shown glows, a closed one shows when it opens</li>
 *   <li>{@code pass_missions}: missions of the category shown, centred on the row</li>
 *   <li>placeholders {@code %fluxpass_...%}: tier, points, season end, page of the player's tier, step colours of the
 *   path, and the missions still to do (todo_*) for the sidebar of Typewriter</li>
 * </ul>
 */
final class PassMenu {

    static final String CATEGORIES = "pass_categories", MISSIONS = "pass_missions", FREE = "pass_free_", PREMIUM = "pass_premium_";

    private final FluxPass plugin;
    private final PassService pass;
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();
    int perPage = 7, missionSlots = 7;
    private static final List<String> registered = new ArrayList<>();

    PassMenu(FluxPass plugin, PassService pass) {
        this.plugin = plugin;
        this.pass = pass;
    }

    /** Registers the lists of every page of the path (called again when the number of tiers changes). */
    void register() {
        Lists.register(plugin, CATEGORIES, q -> categories(q.viewer()));
        Lists.register(plugin, MISSIONS, q -> missions(q.viewer()));
        registered.clear();
        int pages = pages();
        for (int n = 1; n <= pages; n++) {
            int page = n;
            Lists.register(plugin, FREE + n, q -> tiers(q.viewer(), page, Track.FREE));
            Lists.register(plugin, PREMIUM + n, q -> tiers(q.viewer(), page, Track.PREMIUM));
            registered.add(FREE + n);
            registered.add(PREMIUM + n);
        }
    }

    int pages() {
        return Math.max(1, (pass.season.tiers + perPage - 1) / perPage);
    }

    static void invalidate(Player player) {
        Lists.invalidate(player, CATEGORIES);
        Lists.invalidate(player, MISSIONS);
        for (String id : registered) Lists.invalidate(player, id);
    }

    void select(Player player, String category) {
        selected.put(player.getUniqueId(), category.toLowerCase(Locale.ROOT));
        Lists.invalidate(player, CATEGORIES);
        Lists.invalidate(player, MISSIONS);
    }

    void forget(Player player) {
        selected.remove(player.getUniqueId());
    }

    private Messages texts() {
        return plugin.messages();
    }

    // ---------------------------------------------------------------- tiers

    private List<ListEntry> tiers(Player player, int page, Track track) {
        PlayerPass pp = pass.player(player.getUniqueId());
        if (pp == null) return List.of();
        List<ListEntry> out = new ArrayList<>();
        int first = (page - 1) * perPage + 1;
        for (int t = first; t < first + perPage && t <= pass.season.tiers; t++) {
            Season.Reward reward = pass.season.reward(t, track);
            if (reward == null) {
                out.add(ListEntry.gap());
                continue;
            }
            Lore lore = Lore.create().items(reward.lines());
            boolean glow = false;
            if (pass.claimed(pp, t, track)) lore.ok(texts().raw("menu.claimed"));
            else if (track == Track.PREMIUM && !pass.premium(player)) lore.ko(texts().raw("menu.premium-required"));
            else if (pp.tier >= t) {
                lore.left(texts().raw("menu.claim"));
                glow = true;
            } else lore.ko(texts().raw("menu.not-reached"));
            String name = texts().raw(track == Track.FREE ? "menu.tier-free" : "menu.tier-premium", "%tier%", String.valueOf(t));
            ItemStack item = Items.of(reward.icon(), name, lore.build(), player);
            item.setAmount(reward.amount());
            if (glow) item.editMeta(m -> m.setEnchantmentGlintOverride(true));
            out.add(ListEntry.of(String.valueOf(t)).item(item));
        }
        return out;
    }

    // ---------------------------------------------------------------- categories and missions

    private String selected(Player player) {
        String id = selected.get(player.getUniqueId());
        if (id != null && pass.missions.category(id) != null) return id;
        return pass.missions.all().isEmpty() ? "" : pass.missions.all().get(0).id();
    }

    private List<ListEntry> categories(Player player) {
        PlayerPass pp = pass.player(player.getUniqueId());
        if (pp == null) return List.of();
        List<ListEntry> out = new ArrayList<>();
        String current = selected(player);
        long now = System.currentTimeMillis();
        for (Category c : pass.missions.all()) {
            int done = 0;
            for (Mission m : c.missions()) if (pass.progress(pp, c, m).done) done++;
            Lore lore = Lore.create().kv(texts().raw("menu.missions-done"), done + "&7/&f" + c.missions().size());
            if (c.renewal() == Missions.Renewal.DAILY) lore.kv(texts().raw("menu.renews-in"), Format.duration((c.next(now) - now) / 1000));
            ItemStack item;
            if (!pass.open(c, now) && now < c.opens(now)) {
                item = Items.of("itemsadder:influitems:lock", c.name(),
                        Lore.create().kv(texts().raw("menu.opens-in"), Format.duration((c.opens(now) - now) / 1000)).build(), player);
            } else if (c.id().equals(current)) {
                item = Items.of(c.icon(), c.name(), lore.build(), player);
                item.editMeta(m -> m.setEnchantmentGlintOverride(true));
            } else {
                item = Items.of(c.icon(), c.name(), lore.left(texts().raw("menu.show-missions")).build(), player);
            }
            item.setAmount(c.amount());
            out.add(ListEntry.of(c.id()).item(item));
        }
        return out;
    }

    private List<ListEntry> missions(Player player) {
        PlayerPass pp = pass.player(player.getUniqueId());
        Category c = pass.missions.category(selected(player));
        if (pp == null || c == null) return List.of();
        long now = System.currentTimeMillis();
        boolean open = pass.open(c, now);
        List<ListEntry> missions = new ArrayList<>();
        for (int i = 0; i < c.missions().size() && i < missionSlots; i++) {
            Mission m = c.missions().get(i);
            Variant v = pass.drawn(c, m);
            if (v == null) continue;
            Progress pr = pass.progress(pp, c, m);
            Lore lore = Lore.create();
            if (!v.description().isEmpty()) lore.description(v.text());
            lore.kv(texts().raw("menu.reward"), texts().raw("menu.reward-points", "%points%", String.valueOf(m.points())));
            lore.kv(texts().raw("menu.progress"), (int) Math.floor(pr.value) + "&7/&f" + v.amount());
            if (m.premium()) lore.info(texts().raw("menu.premium-mission"));
            String icon;
            if (pr.done) {
                icon = "itemsadder:influitems:accept";
                lore.line(Lore.GREEN + texts().raw("menu.mission-done"));
            } else if (!open) {
                icon = "itemsadder:influitems:lock";
                lore.ko(texts().raw("menu.mission-closed"));
            } else {
                icon = v.icon();
                lore.info(texts().raw("menu.mission-running"));
            }
            // no colour: Items.of gives the name style
            String name = v.name().isEmpty() ? m.name() : v.name();
            missions.add(ListEntry.of(m.id()).item(Items.of(icon, name, lore.build(), player)));
        }
        return centred(missions);
    }

    /** Centred on the row: an odd count side by side, an even count around the empty middle slot. */
    private List<ListEntry> centred(List<ListEntry> entries) {
        int c = entries.size(), middle = missionSlots / 2;
        List<ListEntry> out = new ArrayList<>();
        int first = middle - c / 2;
        for (int i = 0; i < first; i++) out.add(ListEntry.gap());
        for (int i = 0; i < c; i++) {
            if (c % 2 == 0 && out.size() == middle) out.add(ListEntry.gap());
            out.add(entries.get(i));
        }
        return out;
    }

    // ---------------------------------------------------------------- missions to do (sidebar)

    /** A mission to do: name of its variant, progress, icon of what to farm (sprite tag). */
    record Todo(String name, String progress, String icon) {
    }

    /** Missions still to do in the open categories, in menu order; premium ones only for premium players. */
    List<Todo> todos(Player player) {
        PlayerPass pp = pass.player(player.getUniqueId());
        if (pp == null) return List.of();
        long now = System.currentTimeMillis();
        List<Todo> out = new ArrayList<>();
        boolean premium = pass.premium(player);
        for (Category c : pass.missions.all()) {
            if (!pass.open(c, now)) continue;
            for (Mission m : c.missions()) {
                if (m.premium() && !premium) continue;
                Variant v = pass.drawn(c, m);
                Progress pr = pass.progress(pp, c, m);
                if (v == null || pr.done) continue;
                out.add(new Todo(v.name().isEmpty() ? m.name() : v.name(), (int) Math.floor(pr.value) + "/" + v.amount(),
                        ItemSprites.tag(v.icon())));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- placeholders

    String placeholder(Player player, String params) {
        String p = params.toLowerCase(Locale.ROOT).replace("_pass_", "_").replaceFirst("_pass$", "");
        PlayerPass pp = player == null ? null : pass.player(player.getUniqueId());
        long now = System.currentTimeMillis();
        switch (p) {
            case "max_tiers":
                return String.valueOf(pass.season.tiers);
            case "season":
                return pass.season.name;
            case "seasonend": {
                long left = Math.max(0, pass.season.end - now) / 1000;
                String full = Format.duration(left);
                return full.isEmpty() ? "0" : full.split("\\s+")[0];
            }
            case "seasonend_full":
                return Format.duration(Math.max(0, pass.season.end - now) / 1000);
            default:
                break;
        }
        if (pp == null) return "";
        if (p.equals("tier")) return String.valueOf(pp.tier);
        if (p.equals("points")) return String.valueOf(pp.points);
        if (p.equals("points_required")) return String.valueOf(pass.required(pp));
        if (p.equals("track")) return texts().raw(pass.premium(player) ? "track-premium" : "track-free");
        if (p.startsWith("tierpage_")) {
            int size = number(p.substring("tierpage_".length()), perPage);
            int page = Math.max(1, (Math.max(pp.tier, 1) + size - 1) / size);
            return String.valueOf(Math.min(page, Math.max(1, (pass.season.tiers + size - 1) / size)));
        }
        if (p.startsWith("tierstates_")) {
            String[] a = p.substring("tierstates_".length()).split("_");
            if (a.length < 2) return "";
            int first = number(a[0], 1), count = number(a[1], 0);
            List<String> out = new ArrayList<>();
            for (int t = first; t < first + count; t++) {
                out.add(t > pp.tier ? "eteint" : pass.claimed(pp, t, Track.FREE) ? "vert" : "orange");
            }
            return String.join(",", out);
        }
        if (p.startsWith("category_")) {
            String rest = p.substring("category_".length());
            int sep = rest.lastIndexOf('_');
            Category c = sep < 0 ? null : pass.missions.category(rest.substring(0, sep));
            if (c == null) return "";
            int done = 0;
            for (Mission m : c.missions()) if (pass.progress(pp, c, m).done) done++;
            return switch (rest.substring(sep + 1)) {
                case "done" -> String.valueOf(done);
                case "total" -> String.valueOf(c.missions().size());
                default -> "";
            };
        }
        if (p.startsWith("todo_")) {
            List<Todo> todos = todos(player);
            String rest = p.substring("todo_".length());
            if (rest.equals("count")) return String.valueOf(todos.size());
            String[] a = rest.split("_", 2);
            int n = number(a[0], 0);
            if (a.length != 2 || n < 1 || n > todos.size()) return "";
            Todo todo = todos.get(n - 1);
            return switch (a[1]) {
                case "name" -> todo.name();
                case "progress" -> todo.progress();
                case "icon" -> todo.icon();
                default -> "";
            };
        }
        return null;
    }

    private static int number(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
