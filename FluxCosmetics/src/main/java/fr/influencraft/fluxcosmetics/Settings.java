package fr.influencraft.fluxcosmetics;

import org.bukkit.configuration.file.FileConfiguration;

/** The wardrobe's settings (config.yml). */
record Settings(double distance, double minDistance, double maxDistance, double zoomSpeed, float turnSpeed,
                float minPitch, float maxPitch, double targetHeight, float startPitch,
                String cameraItem, String leaveItem) {

    static Settings read(FileConfiguration c) {
        double min = Math.max(0.8, c.getDouble("camera.min-distance", 1.6));
        double max = Math.max(min, c.getDouble("camera.max-distance", 6.0));
        double start = Math.clamp(c.getDouble("camera.distance", 3.2), min, max);
        float minPitch = (float) Math.max(-89, c.getDouble("camera.min-pitch", -25));
        float maxPitch = (float) Math.min(89, Math.max(minPitch, c.getDouble("camera.max-pitch", 70)));
        return new Settings(start, min, max, Math.max(0, c.getDouble("camera.zoom-speed", 0.12)),
                (float) Math.clamp(c.getDouble("camera.turn-speed", 3), 0, 45),
                minPitch, maxPitch, c.getDouble("camera.target-height", 1.0),
                (float) Math.clamp(c.getDouble("camera.start-pitch", 12), minPitch, maxPitch),
                c.getString("hotbar.camera", "SPYGLASS"), c.getString("hotbar.leave", "BARRIER"));
    }
}
