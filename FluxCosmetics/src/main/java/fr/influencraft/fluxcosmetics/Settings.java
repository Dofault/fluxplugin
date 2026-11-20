package fr.influencraft.fluxcosmetics;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/** The wardrobe's settings (config.yml). */
record Settings(double distance, double minDistance, double maxDistance, double zoomStep,
                float turnSpeed, float pitchSpeed, float minPitch, float maxPitch, double targetHeight, double minHeight, double maxHeight,
                double mouseHeight, float startPitch, List<Shot> shots) {

    /**
     * How the camera frames one category of the cosmetics menu: the height of the piece above the mannequin's feet,
     * the distance, the camera's angle around it, and how far the view tilts below the piece so it shows in the
     * upper middle of the screen (the menu covers the lower part).
     */
    record Shot(double height, double distance, float pitch, float lookDown) {
    }

    /** Categories of the menu, in its filter order. */
    static final String[] CATEGORIES = {"head", "chest", "legs", "feet", "tags"};
    private static final double[][] DEFAULTS = {{1.75, 1.3, 6}, {1.25, 1.6, 8}, {0.75, 1.6, 10}, {0.15, 1.4, 16}, {1.0, 3.4, 8}};

    /** The shot of a category (index of the menu's filter), the last one past the end. */
    Shot shot(int category) {
        return shots.get(Math.clamp(category, 0, shots.size() - 1));
    }

    private static List<Shot> shots(FileConfiguration c) {
        float look = (float) Math.clamp(c.getDouble("menu-camera.look-down", 17), 0, 40);
        List<Shot> out = new ArrayList<>();
        for (int i = 0; i < CATEGORIES.length; i++) {
            ConfigurationSection s = c.getConfigurationSection("menu-camera.shots." + CATEGORIES[i]);
            double[] d = DEFAULTS[i];
            out.add(s == null ? new Shot(d[0], d[1], (float) d[2], look)
                    : new Shot(s.getDouble("height", d[0]), Math.max(0.6, s.getDouble("distance", d[1])),
                    (float) Math.clamp(s.getDouble("pitch", d[2]), -25, 70),
                    (float) Math.clamp(s.getDouble("look-down", look), 0, 40)));
        }
        return List.copyOf(out);
    }

    static Settings read(FileConfiguration c) {
        double min = Math.max(0.5, c.getDouble("camera.min-distance", 0.6));
        double max = Math.max(min, c.getDouble("camera.max-distance", 6.0));
        double start = Math.clamp(c.getDouble("camera.distance", 3.2), min, max);
        double minH = c.getDouble("camera.min-height", 0.1);
        double maxH = Math.max(minH, c.getDouble("camera.max-height", 1.9));
        float minPitch = (float) Math.max(-89, c.getDouble("camera.min-pitch", -25));
        float maxPitch = (float) Math.min(89, Math.max(minPitch, c.getDouble("camera.max-pitch", 70)));
        return new Settings(start, min, max, Math.max(0, c.getDouble("camera.zoom-step", 0.4)),
                (float) Math.clamp(c.getDouble("camera.turn-speed", 3), 0, 45),
                (float) Math.clamp(c.getDouble("camera.pitch-speed", 2), 0, 45),
                minPitch, maxPitch, Math.clamp(c.getDouble("camera.target-height", 1.0), minH, maxH), minH, maxH,
                Math.max(0, c.getDouble("camera.mouse-height", 0.03)),
                (float) Math.clamp(c.getDouble("camera.start-pitch", 12), minPitch, maxPitch), shots(c));
    }
}
