package fr.influencraft.fluxdungeons.dungeon;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The launch screen of a dungeon (menu {@code donjon_sas}, drawn by {@code assets/menus/gen_menus.py}, 2026-11-14):
 * a card per member (head, ready or waiting), the difficulty the leader picks (three coloured buttons, the chosen one
 * lit), the ready button. The run starts once everybody is ready; the leader then spends a key of the dungeon's job
 * (checked by the launcher). Nothing is chosen besides: a dungeon is played with the rpg profile as it is.
 * <p>
 * Lists: {@code donjon_sas_boutons} (leave, info, class, key), {@code donjon_sas_membres}, {@code donjon_sas_etats},
 * {@code donjon_sas_niveaux}, {@code donjon_sas_pret}; placeholders {@code sas_title}, {@code sas_level},
 * {@code sas_ready} (FluxDungeons' placeholders); actions {@code [fluxdungeon] sas level <n> | ready | leave}.
 */
public final class Lobby implements Listener {

    public static final String MENU = "donjon_sas";
    private static final List<String> LISTS = List.of("donjon_sas_boutons", "donjon_sas_membres", "donjon_sas_etats",
            "donjon_sas_niveaux", "donjon_sas_pret");
    private static final int SEATS = 5;
    /** A lobby nobody launches is dropped after this long. */
    private static final long TIMEOUT_MS = 5 * 60_000;
    private static final String[] LEVEL_ICONS = {"itemsadder:fluxui:btn_right_green", "itemsadder:fluxui:btn_right_gold",
            "itemsadder:fluxui:btn_right_red"};

    /** A party getting ready for a dungeon. */
    private static final class Party {
        final DungeonDefinition def;
        final List<UUID> members;
        final Set<UUID> ready = new HashSet<>();
        final long created = System.currentTimeMillis();
        /** Started by the staff or Typewriter: no key spent. */
        final boolean free;
        /** Difficulty level chosen by the leader (first member), index in the dungeon's levels. */
        int level;

        Party(DungeonDefinition def, List<UUID> members, boolean free) {
            this.def = def;
            this.members = members;
            this.free = free;
            this.level = defaultLevel(def);
        }
    }

    /** Starts the run once everybody is ready (checks again, the players may have changed meanwhile). */
    public interface Launcher {
        void launch(DungeonDefinition def, List<Player> players, int level, boolean free);
    }

    /** What the key button says: keys of the leader for this dungeon, -1 when no key is needed. */
    public interface KeyInfo {
        int keys(Player leader, DungeonDefinition def);
    }

    /** The level offered first: the one named Normal, else the first. */
    private static int defaultLevel(DungeonDefinition def) {
        for (int i = 0; i < def.levels().size(); i++) if (def.levels().get(i).name().equalsIgnoreCase("normal")) return i;
        return 0;
    }

    private final FluxPlugin plugin;
    private Launcher launcher;
    private KeyInfo keyInfo = (p, d) -> -1;
    private final Map<UUID, Party> byPlayer = new HashMap<>();

    /** Class and level of a player in the rpg ("Guerrier 5"), null when he has none yet. */
    private java.util.function.Function<UUID, String> rpgClass = u -> null;

    void classLine(java.util.function.Function<UUID, String> classLine) {
        this.rpgClass = classLine == null ? u -> null : classLine;
    }

    Lobby(FluxPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 200L, 200L);
        Lists.register(plugin, "donjon_sas_boutons", q -> buttons(q.viewer()));
        Lists.register(plugin, "donjon_sas_membres", q -> members(q.viewer()));
        Lists.register(plugin, "donjon_sas_etats", q -> states(q.viewer()));
        Lists.register(plugin, "donjon_sas_niveaux", q -> levels(q.viewer()));
        Lists.register(plugin, "donjon_sas_pret", q -> readyButton(q.viewer()));
    }

    void launcher(Launcher launcher) {
        this.launcher = launcher;
    }

    public void keyInfo(KeyInfo info) {
        this.keyInfo = info == null ? (p, d) -> -1 : info;
    }

    public boolean waiting(Player p) {
        return byPlayer.containsKey(p.getUniqueId());
    }

    /** Dungeon of the launch screen this player waits on (he may have closed it), null when none. */
    public DungeonDefinition dungeonOf(Player p) {
        Party party = byPlayer.get(p.getUniqueId());
        return party == null ? null : party.def;
    }

    /** Opens the launch screen for these players (already checked), at the difficulty level chosen (-1: default). */
    public void open(DungeonDefinition def, List<Player> players, int level, boolean free) {
        List<UUID> ids = new ArrayList<>();
        for (Player p : players) ids.add(p.getUniqueId());
        Party party = new Party(def, ids, free);
        if (level >= 0 && level < def.levels().size()) party.level = level;
        for (Player p : players) byPlayer.put(p.getUniqueId(), party);
        for (Player p : players) show(p);
    }

    /** Opens the screen again for a player of a lobby (he closed it); false when he is in none. */
    public boolean reopen(Player p) {
        if (!byPlayer.containsKey(p.getUniqueId())) return false;
        show(p);
        return true;
    }

    /** The player gives up: the whole party's lobby is closed. */
    public void cancel(Player p) {
        Party party = byPlayer.get(p.getUniqueId());
        if (party != null) close(party, "lobby.cancelled", p.getName());
    }

    // ---------------------------------------------------------------- menu actions ([fluxdungeon] sas ...)

    /** {@code level <n>}, {@code ready}, {@code leave}. */
    public void act(Player p, String[] args) {
        Party party = byPlayer.get(p.getUniqueId());
        if (party == null) {
            close(p);
            return;
        }
        switch (args.length == 0 ? "" : args[0]) {
            case "level" -> {
                int n;
                try {
                    n = Integer.parseInt(args.length > 1 ? args[1] : "");
                } catch (NumberFormatException e) {
                    return;
                }
                if (!leader(party, p.getUniqueId()) || n < 0 || n >= party.def.levels().size() || n == party.level) {
                    if (!leader(party, p.getUniqueId())) Sounds.cue(p, "error");
                    return;
                }
                party.level = n;
                // another level: everybody agrees again
                party.ready.clear();
                Sounds.cue(p, "click");
                redraw(party);
            }
            case "ready" -> toggleReady(p, party);
            case "leave" -> cancel(p);
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- placeholders

    /** {@code sas_title}, {@code sas_level}, {@code sas_ready}; null for another one. */
    public String placeholder(Player p, String params) {
        Party party = byPlayer.get(p.getUniqueId());
        return switch (params) {
            case "sas_title" -> party == null ? "" : Texts.plain(Texts.colors(plugin.messages().raw("lobby.field",
                    "%dungeon%", shortName(party.def), "%level%", party.def.levels().get(party.level).name())));
            case "sas_level" -> party == null ? "-1" : String.valueOf(party.level);
            case "sas_ready" -> party == null ? "" : Texts.plain(Texts.colors(plugin.messages().raw("lobby.ready-count",
                    "%ready%", String.valueOf(party.ready.size()), "%total%", String.valueOf(party.members.size()))));
            default -> null;
        };
    }

    /** Short name shown in the field: the dungeon's job when it has one. */
    private String shortName(DungeonDefinition def) {
        String job = def.job();
        if (job.isEmpty()) return Texts.plain(Texts.colors(def.name()));
        String name = fr.influencraft.flux.api.jobs.Jobs.available() ? fr.influencraft.flux.api.jobs.Jobs.get().displayName(job) : job;
        return plugin.messages().raw("lobby.field-job", "%job%", Texts.plain(Texts.colors(name == null ? job : name)));
    }

    // ---------------------------------------------------------------- lists

    private List<ListEntry> buttons(Player viewer) {
        Party party = byPlayer.get(viewer.getUniqueId());
        List<ListEntry> out = new ArrayList<>();
        if (party == null) return out;
        String m = "lobby.";
        out.add(button("leave", Items.of("itemsadder:fluxui:btn_close_red", raw(m + "leave"),
                Lore.create().description(raw(m + "leave-desc")).left(raw(m + "leave-action")).build(), viewer),
                "[fluxdungeon] sas leave", ""));
        Lore info = Lore.create().description(party.def.description())
                .kv(raw(m + "players"), party.def.minPlayers() + "-" + party.def.maxPlayers())
                .kv(raw(m + "level"), party.def.recommendedLevel());
        out.add(button("info", Items.of("itemsadder:fluxui:btn_info_blue", party.def.name(), info.build(), viewer), "", ""));
        String line = rpgClass.apply(viewer.getUniqueId());
        out.add(button("class", Items.of("BOOK", line == null ? raw(m + "no-class") : line,
                Lore.create().description(raw(m + "class-desc")).left(raw(m + "class-change")).build(), viewer),
                "[close]", "[player] classe"));
        Player leader = Bukkit.getPlayer(party.members.get(0));
        int keys = party.free || leader == null ? -1 : keyInfo.keys(leader, party.def);
        Lore key = Lore.create();
        if (keys < 0) key.description(raw(m + "key-free"));
        else {
            key.description(raw(m + "key-desc", "%player%", leader.getName()))
                    .kv(raw(m + "key-owned"), String.valueOf(keys));
            if (keys > 0) key.ok(raw(m + "key-ok"));
            else key.ko(raw(m + "key-missing"));
        }
        ItemStack keyItem = Items.of("itemsadder:fluxdonjon:cle_donjon", raw(m + "key"), key.build(), viewer);
        out.add(button("key", keyItem, "", ""));
        return out;
    }

    private static ListEntry button(String id, ItemStack item, String left, String left2) {
        return ListEntry.of(id).item(item == null ? new ItemStack(Material.BARRIER) : item).set("left", left).set("left2", left2);
    }

    private List<ListEntry> members(Player viewer) {
        Party party = byPlayer.get(viewer.getUniqueId());
        List<ListEntry> out = new ArrayList<>();
        if (party == null) return out;
        int n = 0;
        for (UUID u : party.members) {
            if (n++ >= SEATS) break;
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            skull.editMeta(meta -> {
                if (meta instanceof SkullMeta sm) sm.setOwningPlayer(Bukkit.getOfflinePlayer(u));
            });
            Lore lore = Lore.create();
            String line = rpgClass.apply(u);
            if (line != null) lore.kv(raw("lobby.class"), line);
            if (leader(party, u)) lore.info(raw("lobby.leader"));
            Player p = Bukkit.getPlayer(u);
            out.add(ListEntry.of(u.toString()).item(Items.dress(skull, p == null ? "?" : p.getName(), lore.build(), viewer)));
        }
        return out;
    }

    private List<ListEntry> states(Player viewer) {
        Party party = byPlayer.get(viewer.getUniqueId());
        List<ListEntry> out = new ArrayList<>();
        if (party == null) return out;
        int n = 0;
        for (UUID u : party.members) {
            if (n++ >= SEATS) break;
            boolean ready = party.ready.contains(u);
            ItemStack item = Items.of(ready ? "itemsadder:fluxui:btn_check_green" : "itemsadder:fluxui:btn_lock_grey",
                    raw(ready ? "lobby.head-ready" : "lobby.head-waiting"), List.of(), viewer);
            out.add(ListEntry.of(u.toString()).item(item));
        }
        return out;
    }

    private List<ListEntry> levels(Player viewer) {
        Party party = byPlayer.get(viewer.getUniqueId());
        List<ListEntry> out = new ArrayList<>();
        if (party == null) return out;
        boolean lead = leader(party, viewer.getUniqueId());
        List<DungeonDefinition.Level> levels = party.def.levels();
        for (int i = 0; i < Math.min(3, levels.size()); i++) {
            DungeonDefinition.Level l = levels.get(i);
            Lore lore = Lore.create().description(raw("lobby.difficulty-desc"));
            lore.kv(raw("lobby.difficulty-spells"), !l.spells() ? raw("lobby.spells-off")
                    : l.abilities() > 0 ? raw("lobby.spells-count", "%count%", String.valueOf(l.abilities())) : raw("lobby.spells-on"));
            lore.kv(raw("lobby.difficulty-boosters"), String.valueOf(party.def.boosters(i)));
            if (i == party.level) lore.ok(raw("lobby.difficulty-chosen"));
            else if (lead) lore.left(raw("lobby.difficulty-choose"));
            else lore.line(raw("lobby.difficulty-leader", "%player%", name(party.members.get(0))));
            ItemStack item = Items.of(LEVEL_ICONS[Math.min(i, LEVEL_ICONS.length - 1)],
                    raw("lobby.difficulty", "%level%", l.name()), lore.build(), viewer);
            if (item != null && i == party.level) item.editMeta(m -> m.setEnchantmentGlintOverride(true));
            out.add(ListEntry.of(String.valueOf(i)).item(item));
        }
        return out;
    }

    private List<ListEntry> readyButton(Player viewer) {
        Party party = byPlayer.get(viewer.getUniqueId());
        if (party == null) return List.of();
        boolean ready = party.ready.contains(viewer.getUniqueId());
        ItemStack item = Items.of(ready ? "itemsadder:fluxui:btn_check_green" : "itemsadder:fluxui:btn_check_grey",
                raw(ready ? "lobby.ready" : "lobby.not-ready"),
                Lore.create().description(raw("lobby.ready-desc", "%ready%", String.valueOf(party.ready.size()),
                                "%total%", String.valueOf(party.members.size())))
                        .left(raw(ready ? "lobby.unready-action" : "lobby.ready-action")).build(), viewer);
        return List.of(ListEntry.of("ready").item(item));
    }

    private String raw(String key, String... vars) {
        return plugin.messages().raw(key, vars);
    }

    private static boolean leader(Party party, UUID u) {
        return !party.members.isEmpty() && party.members.get(0).equals(u);
    }

    private static String name(UUID u) {
        Player p = Bukkit.getPlayer(u);
        return p == null ? "?" : p.getName();
    }

    // ---------------------------------------------------------------- showing

    private void show(Player p) {
        for (String list : LISTS) Lists.invalidate(p, list);
        if (!FluxApi.open(p, MENU)) plugin.getLogger().warning("Menu " + MENU + " missing: run assets/menus/gen_menus.py");
    }

    /** Draws the screen again for every member who has it open. */
    private void redraw(Party party) {
        for (UUID u : party.members) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            for (String list : LISTS) Lists.invalidate(p, list);
            MenuView view = FluxApi.view(p);
            if (view != null && view.isOpen() && MENU.equals(view.menuId())) view.refresh();
        }
    }

    private static void close(Player p) {
        MenuView view = FluxApi.view(p);
        if (view != null && view.isOpen() && MENU.equals(view.menuId())) view.close();
    }

    private void toggleReady(Player p, Party party) {
        if (!party.ready.add(p.getUniqueId())) {
            party.ready.remove(p.getUniqueId());
            Sounds.cue(p, "toggle-off");
            redraw(party);
            return;
        }
        Sounds.cue(p, "confirm");
        if (!party.ready.containsAll(party.members)) {
            redraw(party);
            return;
        }
        List<Player> players = new ArrayList<>();
        for (UUID u : party.members) {
            Player m = Bukkit.getPlayer(u);
            if (m == null) {
                close(party, "lobby.cancelled", "?");
                return;
            }
            players.add(m);
        }
        for (UUID u : party.members) byPlayer.remove(u);
        for (Player m : players) close(m);
        launcher.launch(party.def, players, party.level, party.free);
    }

    // ---------------------------------------------------------------- end of a lobby

    private void close(Party party, String key, String who) {
        for (UUID u : party.members) {
            if (byPlayer.get(u) == party) byPlayer.remove(u);
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            close(p);
            plugin.messages().send(p, key, "%player%", who, "%dungeon%", party.def.name());
        }
    }

    private void expire() {
        long now = System.currentTimeMillis();
        for (Party party : new HashSet<>(byPlayer.values())) {
            if (now - party.created > TIMEOUT_MS) close(party, "lobby.expired", "");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Party party = byPlayer.get(e.getPlayer().getUniqueId());
        if (party != null) close(party, "lobby.cancelled", e.getPlayer().getName());
    }

    /** Everybody out (server stop). */
    void closeAll() {
        for (Party party : new HashSet<>(byPlayer.values())) close(party, "lobby.expired", "");
    }
}
