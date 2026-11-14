package fr.influencraft.fluxdungeons.reward;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.data.Database;
import fr.influencraft.flux.api.effects.Effects;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.jobs.JobAction;
import fr.influencraft.flux.api.jobs.JobDrops;
import fr.influencraft.flux.api.jobs.Jobs;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.flux.api.value.Values;
import fr.influencraft.fluxdungeons.dungeon.DungeonDefinition;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Dungeon keys (2026-11-14): the leader of a party spends one key of the dungeon's job to launch it. A key drops
 * while doing the job's paid actions in the semi-rp world: each action rolls its points (FluxValue) against the points
 * of {@code keys.minutes} of optimal play, so one key comes every that many minutes of work on average, whatever the
 * block (a diamond is worth more than a stone, its chance is as much higher). Counted by Flux ({@code Effects.count}
 * on {@code Values.jobSources}), never by listening to blocks.
 * <p>
 * Keys are a number per player and job ({@code flux_dungeons_keys}), not an item: they drop in the semi-rp world and are
 * spent in the rpg one, whose inventories are apart. FluxJobs shows them first in the job's paid actions
 * ({@link JobDrops}) with the chance of each action.
 */
public final class DungeonKeys implements Listener {

    private final FluxPlugin plugin;
    private final Supplier<Collection<DungeonDefinition>> dungeons;
    private final String table = Database.table("flux_dungeons_keys");
    private final Map<UUID, Map<String, Integer>> cache = new ConcurrentHashMap<>();
    /** Keys found before the player's stored ones were read (a drop right at join): added once they are. */
    private final Map<UUID, Map<String, Integer>> pending = new ConcurrentHashMap<>();
    private final List<Effects.Counter> counters = new ArrayList<>();
    private boolean required = true;
    private double minutes = 20;
    private String bypass = "fluxdungeons.admin";

    public DungeonKeys(FluxPlugin plugin, Supplier<Collection<DungeonDefinition>> dungeons) throws SQLException {
        this.plugin = plugin;
        this.dungeons = dungeons;
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            Database.Dialect d = Database.dialect();
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (uuid " + d.key(36) + " NOT NULL, job "
                    + d.key(32) + " NOT NULL, amount INTEGER NOT NULL, PRIMARY KEY (uuid, job))");
        }
        for (Player p : Bukkit.getOnlinePlayers()) load(p.getUniqueId());
        JobDrops.register(plugin, new JobDrops.Source() {
            public ItemStack header(Player viewer, String job) {
                return keyButton(viewer, job);
            }

            public JobDrops.Line line(Player viewer, String job, JobAction action) {
                return chanceLine(job, action);
            }
        });
    }

    /** Settings ({@code keys} of the config) and the counters of every job that has a dungeon. */
    public void reload(ConfigurationSection c) {
        required = c == null || c.getBoolean("required", true);
        minutes = c == null ? 20 : Math.max(0.1, c.getDouble("minutes", 20));
        bypass = c == null ? "fluxdungeons.admin" : c.getString("bypass-permission", "fluxdungeons.admin");
        Effects.whenReady(plugin, this::count);
    }

    private void count() {
        counters.forEach(Effects.Counter::close);
        counters.clear();
        int n = 0;
        for (String job : jobs()) {
            for (Values.JobSource s : Values.jobSources(job)) {
                Effects.Counter counter = Effects.count(plugin, "key_" + job + "_" + n++, s.counter(1, true),
                        (player, points) -> roll(player, job, points));
                if (counter != null) counters.add(counter);
            }
        }
        plugin.getLogger().info(counters.size() + " job actions drop dungeon keys.");
    }

    private List<String> jobs() {
        List<String> out = new ArrayList<>();
        for (DungeonDefinition d : dungeons.get()) if (!d.job().isEmpty() && !out.contains(d.job())) out.add(d.job());
        return out;
    }

    private DungeonDefinition dungeonOf(String job) {
        for (DungeonDefinition d : dungeons.get()) if (d.job().equals(job)) return d;
        return null;
    }

    /** Points of play worth one key: {@code keys.minutes} of optimal play. */
    private double pointsPerKey() {
        return Values.pointsPerSecond() * 60 * minutes;
    }

    private void roll(Player player, String job, double points) {
        double per = pointsPerKey();
        if (per <= 0 || points <= 0 || dungeonOf(job) == null) return;
        double expected = points / per;
        int n = (int) expected;
        if (ThreadLocalRandom.current().nextDouble() < expected - n) n++;
        if (n <= 0) return;
        int now = add(player.getUniqueId(), job, n);
        plugin.messages().send(player, "keys.found", "%job%", jobName(job), "%amount%", String.valueOf(n),
                "%total%", String.valueOf(now));
        Sounds.cue(player, "complete");
    }

    // ---------------------------------------------------------------- keys of a player

    /** Keys of this job the player has (0 while his data is loading). */
    public int count(Player player, String job) {
        return cache.getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(job, 0);
    }

    /** A launch needs a key of this dungeon (it has a job, keys are required and the player is not staff). */
    public boolean needs(Player player, DungeonDefinition def) {
        return required && !def.job().isEmpty() && !player.hasPermission(bypass);
    }

    /** Spends one key of the dungeon's job; false (nothing spent) when he has none. */
    public boolean spend(Player player, DungeonDefinition def) {
        if (!needs(player, def)) return true;
        Map<String, Integer> mine = cache.get(player.getUniqueId());
        if (mine == null || mine.getOrDefault(def.job(), 0) <= 0) return false;
        int left = mine.merge(def.job(), -1, Integer::sum);
        save(player.getUniqueId(), def.job(), left);
        return true;
    }

    /** Gives keys (drops, staff); returns the new count. */
    public int add(UUID uuid, String job, int n) {
        Map<String, Integer> mine = cache.get(uuid);
        if (mine == null) {
            // not read yet: never write a count that would overwrite the stored one
            return pending.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).merge(job, n, Integer::sum);
        }
        int now = mine.merge(job, n, Integer::sum);
        save(uuid, job, now);
        return now;
    }

    public String jobName(String job) {
        String name = Jobs.available() ? Jobs.get().displayName(job) : null;
        return name == null ? job : Texts.plain(Texts.colors(name));
    }

    // ---------------------------------------------------------------- jobs menu

    private ItemStack keyButton(Player viewer, String job) {
        DungeonDefinition def = dungeonOf(job);
        if (def == null) return null;
        String name = jobName(job);
        Lore lore = Lore.create().description(plugin.messages().raw("keys.button-description", "%job%", name))
                .description(plugin.messages().raw("keys.button-use", "%dungeon%", def.name()))
                .kv(plugin.messages().raw("keys.button-average"), plugin.messages().raw("keys.button-every",
                        "%minutes%", Format.decimal(minutes)))
                .kv(plugin.messages().raw("keys.button-owned"), String.valueOf(count(viewer, job)));
        ItemStack item = Items.of("itemsadder:fluxdonjon:cle_donjon",
                plugin.messages().raw("keys.button-name", "%job%", name), lore.build(), viewer);
        if (item != null) item.setAmount(Math.max(1, Math.min(64, count(viewer, job))));
        return item;
    }

    /** "Clé : 1 sur N" on a paid action, from the points FluxValue gives it. */
    private JobDrops.Line chanceLine(String job, JobAction action) {
        if (dungeonOf(job) == null) return null;
        double per = pointsPerKey();
        String target = action.targets().isEmpty() ? null : action.targets().get(0);
        OptionalDouble points = Values.actionPoints(action.trigger(), target);
        if (per <= 0 || points.isEmpty() || points.getAsDouble() <= 0) return null;
        double oneIn = per / points.getAsDouble();
        String value = oneIn <= 1 ? plugin.messages().raw("keys.line-always")
                : plugin.messages().raw("keys.line-one-in", "%n%", Format.number(Math.round(oneIn)));
        return new JobDrops.Line(plugin.messages().raw("keys.line"), value);
    }

    // ---------------------------------------------------------------- storage

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        load(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cache.remove(e.getPlayer().getUniqueId());
    }

    private void load(UUID uuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Integer> found = new ConcurrentHashMap<>();
            try (Connection c = Database.connection();
                 PreparedStatement ps = c.prepareStatement("SELECT job, amount FROM " + table + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) found.put(rs.getString(1), rs.getInt(2));
                }
            } catch (SQLException ex) {
                plugin.getLogger().severe("Dungeon keys of " + uuid + " not read: " + ex.getMessage());
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                cache.put(uuid, found);
                // keys found meanwhile (a drop right at join, a staff gift to an offline player) are added
                Map<String, Integer> early = pending.remove(uuid);
                if (early != null) early.forEach((job, n) -> save(uuid, job, found.merge(job, n, Integer::sum)));
                if (Bukkit.getPlayer(uuid) == null) cache.remove(uuid);
            });
        });
    }

    private void save(UUID uuid, String job, int amount) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection c = Database.connection();
                 PreparedStatement ps = c.prepareStatement(Database.dialect().upsert(table, List.of("uuid", "job", "amount"),
                         List.of("uuid", "job"), List.of("amount")))) {
                ps.setString(1, uuid.toString());
                ps.setString(2, job);
                ps.setInt(3, Math.max(0, amount));
                ps.executeUpdate();
            } catch (SQLException ex) {
                plugin.getLogger().severe("Dungeon keys of " + uuid + " not saved: " + ex.getMessage());
            }
        });
    }

    public void shutdown() {
        counters.forEach(Effects.Counter::close);
        counters.clear();
        JobDrops.unregister(plugin);
    }
}
