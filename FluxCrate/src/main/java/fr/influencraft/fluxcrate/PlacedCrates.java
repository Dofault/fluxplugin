package fr.influencraft.fluxcrate;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Crates placed in the worlds ({@code placed.yml}: {@code world@x,y,z: <crate>}): the text floating above each one
 * (a text display, not saved with the chunk, put back while the chunk is loaded) and a ring of particles.
 */
final class PlacedCrates {

    private static final NamespacedKey HOLOGRAM = new NamespacedKey("fluxcrate", "hologram");

    private final FluxCrate plugin;
    private final Map<String, String> placed = new LinkedHashMap<>(); // world@x,y,z -> crate
    private final Map<String, UUID> holograms = new HashMap<>();
    private double angle;

    PlacedCrates(FluxCrate plugin) {
        this.plugin = plugin;
    }

    private File file() {
        return new File(plugin.getDataFolder(), "placed.yml");
    }

    void load() {
        removeHolograms();
        placed.clear();
        ConfigurationSection s = YamlConfiguration.loadConfiguration(file()).getConfigurationSection("crates");
        if (s != null) for (String k : s.getKeys(false)) placed.put(k, s.getString(k));
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(java.util.List.of("Crates placed in the worlds, written by FluxCrate (/fluxcrate place, remove)."));
        placed.forEach((k, v) -> y.set("crates." + k, v));
        try {
            y.save(file());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save placed.yml: " + e.getMessage());
        }
    }

    static String key(Location l) {
        return l.getWorld().getName() + "@" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    /** Crate placed on this block, null when none. */
    String at(Block b) {
        return placed.get(key(b.getLocation()));
    }

    void place(Location l, String crate) {
        placed.put(key(l), crate);
        save();
    }

    boolean remove(Location l) {
        String k = key(l);
        boolean had = placed.remove(k) != null;
        UUID h = holograms.remove(k);
        if (h != null && Bukkit.getEntity(h) != null) Bukkit.getEntity(h).remove();
        if (had) save();
        return had;
    }

    int size() {
        return placed.size();
    }

    private static Location location(String key) {
        int at = key.lastIndexOf('@');
        if (at < 0) return null;
        World w = Bukkit.getWorld(key.substring(0, at));
        if (w == null) {
            for (World any : Bukkit.getWorlds()) if (any.getName().equalsIgnoreCase(key.substring(0, at))) w = any;
        }
        String[] p = key.substring(at + 1).split(",");
        if (w == null || p.length != 3) return null;
        try {
            return new Location(w, Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Every second: text back above the crates of loaded chunks, particles for players around. */
    void tick() {
        angle += Math.PI / 8;
        for (Map.Entry<String, String> e : placed.entrySet()) {
            Location l = location(e.getKey());
            Crate crate = plugin.crate(e.getValue());
            if (l == null || crate == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            UUID h = holograms.get(e.getKey());
            Entity entity = h == null ? null : Bukkit.getEntity(h);
            if (entity == null || !entity.isValid()) holograms.put(e.getKey(), spawn(l, crate).getUniqueId());
            particles(l, crate);
        }
    }

    private TextDisplay spawn(Location l, Crate crate) {
        Location at = l.clone().add(0.5, 1.6, 0.5);
        return l.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.text(fr.influencraft.flux.api.text.Texts.parse(String.join("\n", crate.hologram())));
            d.getPersistentDataContainer().set(HOLOGRAM, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private void particles(Location l, Crate crate) {
        Color color;
        try {
            color = Color.fromRGB(Integer.parseInt(crate.particle().replace("#", ""), 16));
        } catch (NumberFormatException ex) {
            color = Color.WHITE;
        }
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.0f);
        for (int i = 0; i < 4; i++) {
            double a = angle + i * Math.PI / 2;
            l.getWorld().spawnParticle(Particle.DUST, l.getX() + 0.5 + 0.6 * Math.cos(a), l.getY() + 1.1,
                    l.getZ() + 0.5 + 0.6 * Math.sin(a), 1, 0, 0, 0, 0, dust);
        }
    }

    /** On disable and reload: the texts (they would vanish with their chunk anyway). */
    void removeHolograms() {
        for (UUID h : holograms.values()) {
            Entity e = Bukkit.getEntity(h);
            if (e != null) e.remove();
        }
        holograms.clear();
    }
}
