package fr.influencraft.fluxtopdown;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** config.yml, read once per reload. */
record Settings(String dungeon,
                double cameraDistance, double cameraPitch, double cameraYaw, double cameraMinDistance,
                double cursorSpeed, double cursorWidth, double cursorHeight, String cursorText, float cursorScale,
                double cursorDistance, double hotspotX, double hotspotY,
                double moveSpeed, int maxPath, int restSlot, Material weapon,
                double basicRange, double basicCoefficient, List<Spell> spells) {

    /** One spell of the kit (keys 1 to 4). Durations in ticks. */
    record Spell(int key, String id, String type, double range, double width, double radius, int windup, int delay,
                 int hits, int duration, double coefficient, int cooldown, boolean magic) {

        /** Spells cast at once, without aiming. */
        boolean instant() {
            return type.equals("spin");
        }
    }

    static Settings read(FileConfiguration c) {
        List<Spell> spells = new ArrayList<>();
        ConfigurationSection s = c.getConfigurationSection("spells");
        for (int key = 1; key <= 4; key++) {
            ConfigurationSection x = s == null ? null : s.getConfigurationSection(String.valueOf(key));
            if (x == null) continue;
            spells.add(new Spell(key, x.getString("id", "spell" + key), x.getString("type", "line").toLowerCase(Locale.ROOT),
                    x.getDouble("range", 8), x.getDouble("width", 1.5), x.getDouble("radius", 3), x.getInt("windup", 0),
                    x.getInt("delay", 0), Math.max(1, x.getInt("hits", 1)), Math.max(1, x.getInt("duration", 20)),
                    x.getDouble("coefficient", 1), Math.max(0, x.getInt("cooldown", 60)), x.getBoolean("magic", false)));
        }
        List<Double> hotspot = c.getDoubleList("cursor.hotspot");
        Material weapon = Material.matchMaterial(c.getString("weapon", "IRON_SWORD"));
        return new Settings(c.getString("dungeon", "moisson"),
                c.getDouble("camera.distance", 13), c.getDouble("camera.pitch", 55), c.getDouble("camera.yaw", 180),
                c.getDouble("camera.min-distance", 3),
                c.getDouble("cursor.speed", 0.03), c.getDouble("cursor.width", 1.15), c.getDouble("cursor.height", 0.62),
                c.getString("cursor.text", "+"), (float) c.getDouble("cursor.scale", 0.35), c.getDouble("cursor.distance", 2),
                hotspot.size() > 0 ? hotspot.get(0) : 0, hotspot.size() > 1 ? hotspot.get(1) : 0,
                c.getDouble("move.speed", 0.24), c.getInt("move.max-distance", 48),
                Math.max(4, Math.min(8, c.getInt("rest-slot", 4))), weapon == null ? Material.IRON_SWORD : weapon,
                c.getDouble("basic.range", 2.8), c.getDouble("basic.coefficient", 1), List.copyOf(spells));
    }

    Spell spell(int key) {
        for (Spell s : spells) if (s.key() == key) return s;
        return null;
    }
}
