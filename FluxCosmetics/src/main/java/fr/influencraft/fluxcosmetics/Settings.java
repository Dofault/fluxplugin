package fr.influencraft.fluxcosmetics;

import org.bukkit.configuration.file.FileConfiguration;

/** The wardrobe's settings (config.yml). */
record Settings(double distance, double minDistance, double maxDistance, double zoomStep,
                float turnSpeed, float pitchSpeed, float minPitch, float maxPitch, double targetHeight, double minHeight, double maxHeight,
                double mouseHeight, float startPitch, double menuHeight) {

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
                (float) Math.clamp(c.getDouble("camera.start-pitch", 12), minPitch, maxPitch),
                Math.clamp(c.getDouble("camera.menu-height", -0.25), -2.0, maxH));
    }
}
