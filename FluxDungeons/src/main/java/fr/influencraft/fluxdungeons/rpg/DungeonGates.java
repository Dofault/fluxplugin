package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.FluxPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * The gates of the rpg lobby (plugins/FluxDungeons/lobby.yml, written by assets/maps/gen_lobby.py): a box in the lobby
 * world per dungeon, its opening drawn in particles of the dungeon's colour. Walking into a gate does what
 * {@code /donjon jouer <dungeon>} does (the launch screen opens) and pushes the player gently back out; a gate whose
 * dungeon does not exist yet only says so. Nothing is placed in the world.
 */
public final class DungeonGates {

    record Gate(String dungeon, String name, int x0, int y0, int z0, int x1, int y1, int z1, Vector out, Color color) {
        boolean contains(Location l) {
            double x = l.getX(), y = l.getY(), z = l.getZ();
            return x >= x0 && x < x1 + 1 && y >= y0 - 0.5 && y < y1 + 1 && z >= z0 && z < z1 + 1;
        }
    }

    private final FluxPlugin plugin;
    private final Predicate<String> exists;
    private final List<Gate> gates = new ArrayList<>();
    private final Map<UUID, Gate> inside = new HashMap<>();
    private String worldName = "flux_rpg";
    private BukkitTask task;
    private int ticks;

    public DungeonGates(FluxPlugin plugin, Predicate<String> exists) {
        this.plugin = plugin;
        this.exists = exists;
    }

    public void load() {
        gates.clear();
        File f = new File(plugin.getDataFolder(), "lobby.yml");
        if (!f.isFile()) return;
        YamlConfiguration c = YamlConfiguration.loadConfiguration(f);
        worldName = c.getString("world", worldName);
        for (Map<?, ?> m : c.getMapList("gates")) {
            try {
                List<?> a = (List<?>) m.get("from"), b = (List<?>) m.get("to"), o = (List<?>) m.get("out");
                Color color = Color.fromRGB(Integer.parseInt(String.valueOf(m.get("color")).replace("#", ""), 16));
                gates.add(new Gate(String.valueOf(m.get("dungeon")), String.valueOf(m.get("name")),
                        num(a, 0), num(a, 1), num(a, 2), num(b, 0), num(b, 1), num(b, 2),
                        new Vector(num(o, 0), 0, num(o, 2)), color));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("lobby.yml: a gate is malformed (" + m + ")");
            }
        }
        plugin.getLogger().info(gates.size() + " dungeon gate(s) in " + worldName + ".");
    }

    private static int num(List<?> l, int i) {
        return ((Number) l.get(i)).intValue();
    }

    public void start() {
        load();
        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 5L);
    }

    private void tick() {
        World w = Bukkit.getWorld(worldName);
        if (w == null || gates.isEmpty()) return;
        ticks += 5;
        List<Player> players = w.getPlayers();
        for (Player p : players) {
            Gate now = null;
            for (Gate g : gates) if (g.contains(p.getLocation())) now = g;
            Gate before = inside.get(p.getUniqueId());
            if (now != null && now != before) enter(p, now);
            if (now == null) inside.remove(p.getUniqueId());
            else inside.put(p.getUniqueId(), now);
        }
        if (ticks % 10 != 0 || players.isEmpty()) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Gate g : gates) {
            Location mid = new Location(w, (g.x0() + g.x1() + 1) / 2.0, g.y0(), (g.z0() + g.z1() + 1) / 2.0);
            boolean seen = false;
            for (Player p : players) if (p.getLocation().distanceSquared(mid) < 48 * 48) seen = true;
            if (!seen) continue;
            Particle.DustOptions dust = new Particle.DustOptions(g.color(), 1.3f);
            for (int i = 0; i < 6; i++) {
                Location at = new Location(w, r.nextDouble(g.x0(), g.x1() + 1), r.nextDouble(g.y0(), g.y1() + 1), r.nextDouble(g.z0(), g.z1() + 1));
                w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust);
                w.spawnParticle(Particle.PORTAL, at, 2, 0.1, 0.2, 0.1, 0.3);
            }
        }
    }

    private void enter(Player p, Gate g) {
        if (exists.test(g.dungeon())) p.performCommand("donjon jouer " + g.dungeon());
        else plugin.messages().send(p, "rpg.gate-soon", "%dungeon%", g.name());
        Vector out = g.out().clone();
        if (out.lengthSquared() > 0) p.setVelocity(out.normalize().multiply(0.55).setY(0.2));
    }
}
