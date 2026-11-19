package fr.influencraft.flux.spawn;

import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.data.Database;
import fr.influencraft.flux.api.spawn.Spawns;
import fr.influencraft.flux.api.text.Messages;
import fr.influencraft.flux.api.universe.Universes;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Spawn groups ({@link Spawns}, {@code plugins/Flux/spawns.yml}): the place of a player's group replaces the server's
 * spawn for {@code /spawn}, the respawn after a death (no bed nor anchor) and the very first join. The group of each
 * player is kept in {@code flux_spawn_players}, read before he joins (pre-login), so it is known at once.
 *
 * <p>Only the semi-rp side is concerned: a respawn or a teleport into another universe (rpg lobby, dungeons) is left
 * to the plugin that owns it.</p>
 */
public final class SpawnService implements Listener, Spawns.Backend {

    private static final String ADMIN = "flux.spawn.admin";
    /** Commands that mean "take me to the spawn" when typed without argument. */
    private static final Set<String> SPAWN_COMMANDS = Set.of("spawn", "cmi:spawn", "essentials:spawn");

    private final JavaPlugin flux;
    private final Supplier<Messages> messages;
    private final String table = Database.table("flux_spawn_players");
    private final Map<UUID, String> groups = new ConcurrentHashMap<>();
    /** Players put in the first-join group at this join: taken to its place once they are in. */
    private final Set<UUID> arriving = ConcurrentHashMap.newKeySet();
    /** Place of each group; the world is looked up when used (Multiverse may load it after Flux). */
    private record Place(String world, double x, double y, double z, float yaw, float pitch) {
        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }

    private final Map<String, Place> places = new LinkedHashMap<>();
    private File file;
    private String firstJoin = "";
    private boolean respawn = true;

    private SpawnService(JavaPlugin flux, Supplier<Messages> messages) {
        this.flux = flux;
        this.messages = messages;
    }

    public static SpawnService start(JavaPlugin flux, Supplier<Messages> messages) {
        SpawnService service = new SpawnService(flux, messages);
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            Database.Dialect d = Database.dialect();
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + service.table + " (uuid " + d.key(36)
                    + " NOT NULL PRIMARY KEY, spawn_group " + d.key(32) + " NOT NULL, changed BIGINT NOT NULL)");
        } catch (SQLException e) {
            flux.getLogger().severe("Spawn groups unavailable (flux_spawn_players): " + e.getMessage());
            return null;
        }
        service.load();
        for (Player p : Bukkit.getOnlinePlayers()) service.read(p.getUniqueId());
        Spawns.bind(service);
        Bukkit.getPluginManager().registerEvents(service, flux);
        Commands.create(flux, "fluxspawn").description("Spawn groups: a player's group, a group's place.")
                .permission(ADMIN).suggest(false).executor(service::command).completer(service::complete).register();
        return service;
    }

    public void stop() {
        Spawns.bind(null);
    }

    // ---------------------------------------------------------------- config

    /** Reads spawns.yml (shipped when missing). */
    public void load() {
        file = new File(flux.getDataFolder(), "spawns.yml");
        if (!file.exists()) flux.saveResource("spawns.yml", false);
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        firstJoin = c.getString("first-join", "").trim().toLowerCase(Locale.ROOT);
        respawn = c.getBoolean("respawn", true);
        places.clear();
        ConfigurationSection gs = c.getConfigurationSection("groups");
        if (gs != null) for (String id : gs.getKeys(false)) {
            ConfigurationSection g = gs.getConfigurationSection(id);
            if (g == null) continue;
            places.put(id.toLowerCase(Locale.ROOT), new Place(g.getString("world", ""), g.getDouble("x"), g.getDouble("y"),
                    g.getDouble("z"), (float) g.getDouble("yaw"), (float) g.getDouble("pitch")));
        }
        if (!firstJoin.isEmpty() && !places.containsKey(firstJoin)) {
            flux.getLogger().warning("spawns.yml: first-join group " + firstJoin + " has no place, new players keep the server's spawn");
        }
    }

    private void savePlace(String group, Location at) {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        String path = "groups." + group;
        c.set(path + ".world", at.getWorld().getName());
        c.set(path + ".x", Math.round(at.getX() * 100) / 100.0);
        c.set(path + ".y", Math.round(at.getY() * 100) / 100.0);
        c.set(path + ".z", Math.round(at.getZ() * 100) / 100.0);
        c.set(path + ".yaw", (double) Math.round(at.getYaw()));
        c.set(path + ".pitch", (double) Math.round(at.getPitch()));
        try {
            c.save(file);
        } catch (IOException e) {
            flux.getLogger().warning("spawns.yml not saved: " + e.getMessage());
        }
        load();
    }

    // ---------------------------------------------------------------- backend

    @Override
    public String group(Player player) {
        return groups.getOrDefault(player.getUniqueId(), Spawns.NONE);
    }

    @Override
    public void set(OfflinePlayer player, String group) {
        String g = group == null ? "" : group.trim().toLowerCase(Locale.ROOT);
        if (g.equals("none") || g.equals("aucun")) g = "";
        UUID id = player.getUniqueId();
        if (g.isEmpty()) groups.remove(id);
        else groups.put(id, g);
        String stored = g;
        Bukkit.getScheduler().runTaskAsynchronously(flux, () -> write(id, stored));
    }

    @Override
    public Location location(String group) {
        Place at = places.get(group.toLowerCase(Locale.ROOT));
        return at == null ? null : at.location();
    }

    private void write(UUID id, String group) {
        try (Connection c = Database.connection()) {
            if (group.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE uuid = ?")) {
                    ps.setString(1, id.toString());
                    ps.executeUpdate();
                }
                return;
            }
            try (PreparedStatement ps = c.prepareStatement(Database.dialect().upsert(table,
                    List.of("uuid", "spawn_group", "changed"), List.of("uuid"), List.of("spawn_group", "changed")))) {
                ps.setString(1, id.toString());
                ps.setString(2, group);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            flux.getLogger().warning("Spawn group of " + id + " not saved: " + e.getMessage());
        }
    }

    /** Reads a player's group (blocking: called off the main thread, or for the few players online at a reload). */
    private void read(UUID id) {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT spawn_group FROM " + table + " WHERE uuid = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) groups.put(id, rs.getString(1));
                else groups.remove(id);
            }
        } catch (SQLException e) {
            flux.getLogger().warning("Spawn group of " + id + " unreadable: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- events

    /** Before he joins: his group, or the first-join one for a player never seen on this server. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        UUID id = e.getUniqueId();
        read(id);
        if (!firstJoin.isEmpty() && places.containsKey(firstJoin) && !groups.containsKey(id)
                && !Bukkit.getOfflinePlayer(id).hasPlayedBefore()) {
            groups.put(id, firstJoin);
            write(id, firstJoin);
            arriving.add(id);
        }
    }

    /** A new player: a few ticks after he is in (after the spawn plugins), to his group's place. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!arriving.remove(p.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(flux, () -> {
            Location at = location(group(p));
            if (p.isOnline() && at != null) p.teleport(at, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        groups.remove(e.getPlayer().getUniqueId());
        arriving.remove(e.getPlayer().getUniqueId());
    }

    /** {@code /spawn} without argument: his group's place instead of the server's spawn. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String[] words = e.getMessage().substring(1).trim().split("\\s+");
        String label = words[0].toLowerCase(Locale.ROOT);
        boolean spawn = words.length == 1 && SPAWN_COMMANDS.contains(label)
                || words.length == 2 && (label.equals("cmi") || label.equals("cmi:cmi")) && words[1].equalsIgnoreCase("spawn");
        if (!spawn) return;
        Location at = location(group(e.getPlayer()));
        if (at == null) return;
        e.setCancelled(true);
        e.getPlayer().teleport(at, PlayerTeleportEvent.TeleportCause.COMMAND);
    }

    /** After a death without bed nor anchor, in the semi-rp universe: his group's place. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        if (!respawn || e.isBedSpawn() || e.isAnchorSpawn()) return;
        Location at = location(group(e.getPlayer()));
        if (at == null) return;
        World target = e.getRespawnLocation().getWorld();
        if (target != null && !Universes.VANILLA.equals(Universes.of(target))) return;
        e.setRespawnLocation(at);
    }

    // ---------------------------------------------------------------- placeholder

    /** {@code %flux_spawn%}: the player's group, {@code none} without one. */
    public String placeholder(Player player) {
        if (player == null) return "";
        String g = group(player);
        return g.isEmpty() ? "none" : g;
    }

    // ---------------------------------------------------------------- command

    private boolean command(CommandSender sender, String label, String[] args) {
        Messages m = messages.get();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set" -> {
                if (args.length < 3) break;
                OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
                if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (target == null) {
                    m.send(sender, "spawn.unknown-player", "%player%", args[1]);
                    return true;
                }
                String g = args[2].toLowerCase(Locale.ROOT);
                if (!g.equals("none") && !g.equals("aucun") && !places.containsKey(g)) {
                    m.send(sender, "spawn.unknown-group", "%group%", args[2]);
                    return true;
                }
                set(target, g);
                m.send(sender, "spawn.set", "%player%", String.valueOf(target.getName()), "%group%", g);
                return true;
            }
            case "here" -> {
                if (args.length < 2 || !(sender instanceof Player p)) break;
                String g = args[1].toLowerCase(Locale.ROOT);
                if (g.equals("none") || g.equals("aucun")) break;
                savePlace(g, p.getLocation());
                m.send(sender, "spawn.placed", "%group%", g);
                return true;
            }
            case "info" -> {
                if (args.length < 2) break;
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    m.send(sender, "spawn.unknown-player", "%player%", args[1]);
                    return true;
                }
                m.send(sender, "spawn.info", "%player%", target.getName(), "%group%", placeholder(target));
                return true;
            }
            case "reload" -> {
                load();
                m.send(sender, "spawn.reloaded", "%groups%", String.join(", ", places.keySet()));
                return true;
            }
            default -> {
            }
        }
        m.send(sender, "spawn.usage");
        return true;
    }

    private List<String> complete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) out.addAll(List.of("set", "here", "info", "reload"));
        else if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("info"))) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("here")) out.addAll(places.keySet());
        else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            out.addAll(places.keySet());
            out.add("none");
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
