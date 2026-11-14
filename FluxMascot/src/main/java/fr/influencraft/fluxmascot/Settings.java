package fr.influencraft.fluxmascot;

import org.bukkit.configuration.file.FileConfiguration;

/** config.yml, read once per reload. Durations in ticks, distances in blocks. */
record Settings(String model, float scale, double distance, double minDistance, String voice, int voiceDelay,
                int voiceDuration, float voiceVolume, double voiceHeight, double leaveDistance, double speed,
                double maxDistance, boolean roam, String network, double roamMaxDistance, double relocateMin,
                double relocateMax, double pauseChance, int pauseMin, int pauseMax, double clickWidth,
                double clickHeight, String idle, String appear, String greet, String talk, String walk, String vanish,
                int appearTicks, int greetTicks, int vanishTicks) {

    static Settings read(FileConfiguration c) {
        return new Settings(c.getString("model", "robot_accueil"), (float) Math.max(0.1, c.getDouble("scale", 0.7)),
                Math.max(1, c.getDouble("distance", 5)), Math.max(1, c.getDouble("min-distance", 2)),
                c.getString("voice.sound", ""), Math.max(0, c.getInt("voice.delay", 30)),
                Math.max(1, c.getInt("voice.duration", 365)), (float) c.getDouble("voice.volume", 1.5),
                c.getDouble("voice.height", 1.35),
                Math.max(0, c.getDouble("leave.distance", 4)), Math.max(0.1, c.getDouble("speed", 0.84)),
                c.getDouble("max-distance", 40),
                c.getBoolean("roam.enabled", true), c.getString("roam.network", "network.json"),
                c.getDouble("roam.max-distance", 60), c.getDouble("roam.relocate-min", 12),
                c.getDouble("roam.relocate-max", 35), c.getDouble("roam.pause-chance", 0.3),
                Math.max(1, c.getInt("roam.pause-min", 40)), Math.max(1, c.getInt("roam.pause-max", 120)),
                c.getDouble("click.width", 1.2), c.getDouble("click.height", 2.0),
                c.getString("animations.idle", "idle"), c.getString("animations.appear", "appear"),
                c.getString("animations.greet", "greet"), c.getString("animations.talk", "talk"),
                c.getString("animations.walk", "walk"), c.getString("animations.vanish", "vanish"),
                Math.max(1, c.getInt("animations.appear-ticks", 10)), Math.max(1, c.getInt("animations.greet-ticks", 40)),
                Math.max(1, c.getInt("animations.vanish-ticks", 8)));
    }
}
