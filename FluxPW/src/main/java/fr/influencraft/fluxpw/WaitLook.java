package fr.influencraft.fluxpw;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What a player sees and hears while waiting for a teleport: a ring of particles, a title each second, sounds.
 *
 * <p>By default it is read from CMI's own files, so the warps of FluxPW wait exactly like CMI's warps, homes and spawn,
 * and the staff sets it once, in CMI: the particle preset of {@code Settings/ParticleEffects.yml} ({@code GlyphHead}),
 * the {@code info.WarmUp.DontMove} text of CMI's locale, and its warm-up and warp sounds ({@code Sounds} of
 * {@code config.yml}). CMI's files are read again when they change. Without CMI, or for a value CMI does not have,
 * FluxPW's own {@code teleport} settings and {@code teleport-title} / {@code teleport-subtitle} texts are used.</p>
 */
final class WaitLook {

    /** A ring in CMI's format: {@code circle;effect:reddust;c:179,118,229;part:12;radius:0.55;offset:0,1,0;pitchc:5;yawc:8}. */
    static final class Ring {
        Particle particle = Particle.DUST;
        Object data;
        int points = 10;
        long interval = 1;
        double radius = 1, offsetX, offsetY, offsetZ, pitch, yaw, pitchStep, yawStep;

        static Ring parse(String spec, java.util.logging.Logger log) {
            if (spec == null || spec.isBlank()) return null;
            Ring r = new Ring();
            Color colour = Color.RED;
            String effect = "reddust";
            for (String part : spec.split(";")) {
                int colon = part.indexOf(':');
                String key = (colon < 0 ? part : part.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
                String value = colon < 0 ? "" : part.substring(colon + 1).trim();
                try {
                    switch (key) {
                        case "effect" -> effect = value.toLowerCase(Locale.ROOT);
                        case "c", "color" -> colour = rgb(value, colour);
                        case "part" -> r.points = Math.max(1, Integer.parseInt(value));
                        case "radius", "r" -> r.radius = Double.parseDouble(value);
                        case "offset" -> {
                            String[] xyz = value.split(",");
                            r.offsetX = Double.parseDouble(xyz[0]);
                            r.offsetY = Double.parseDouble(xyz[1]);
                            r.offsetZ = Double.parseDouble(xyz[2]);
                        }
                        case "pitch" -> r.pitch = Double.parseDouble(value);
                        case "yaw" -> r.yaw = Double.parseDouble(value);
                        case "pitchc" -> r.pitchStep = Double.parseDouble(value);
                        case "yawc" -> r.yawStep = Double.parseDouble(value);
                        case "interval" -> r.interval = Math.max(1, Long.parseLong(value));
                        default -> {
                            // circle, dur, twist, [playerName] angles...: CMI's own, not drawn by FluxPW
                        }
                    }
                } catch (RuntimeException e) {
                    // value FluxPW does not read (e.g. yaw:[playerName]): its default stays
                }
            }
            r.particle = particle(effect);
            if (r.particle == null) {
                log.warning("Unknown particle effect '" + effect + "' in the teleport ring, using reddust.");
                r.particle = Particle.DUST;
            }
            if (r.particle.getDataType() == Particle.DustOptions.class) r.data = new Particle.DustOptions(colour, 1f);
            else if (r.particle.getDataType() != Void.class) r.particle = Particle.ENCHANT;
            return r;
        }

        /** CMI's effect names (the old Bukkit ones) or a particle name. */
        private static Particle particle(String effect) {
            String name = switch (effect) {
                case "reddust", "coloured_dust", "colored_dust" -> "DUST";
                case "flying_glyph" -> "ENCHANT";
                case "witch_magic" -> "WITCH";
                case "magic_crit" -> "ENCHANTED_HIT";
                case "happy_villager" -> "HAPPY_VILLAGER";
                default -> effect;
            };
            try {
                return Particle.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private static Color rgb(String value, Color fallback) {
            String[] c = value.split(",");
            if (c.length != 3) return fallback;
            return Color.fromRGB(Integer.parseInt(c[0].trim()), Integer.parseInt(c[1].trim()), Integer.parseInt(c[2].trim()));
        }

        /** One frame around {@code feet}: points on a circle, turned by pitch then yaw, a little more each frame. */
        void draw(Location feet, int frame) {
            double pitchRad = Math.toRadians(pitch + frame * pitchStep), yawRad = Math.toRadians(yaw + frame * yawStep);
            double cp = Math.cos(pitchRad), sp = Math.sin(pitchRad), cw = Math.cos(yawRad), sw = Math.sin(yawRad);
            double cx = feet.getX() + offsetX, cy = feet.getY() + offsetY, cz = feet.getZ() + offsetZ;
            for (int i = 0; i < points; i++) {
                double a = 2 * Math.PI * i / points;
                // upright circle, tilted around X (pitch 90 = flat halo), then turned around Y
                double x = radius * Math.cos(a), y = radius * Math.sin(a);
                double y1 = y * cp, z1 = y * sp;
                double x2 = x * cw - z1 * sw, z2 = x * sw + z1 * cw;
                feet.getWorld().spawnParticle(particle, cx + x2, cy + y1, cz + z2, 1, 0, 0, 0, 0, data);
            }
        }
    }

    private final FluxPW plugin;

    // FluxPW's own settings, used without CMI
    private boolean fromCmi = true, animation = true;
    private String cmiPreset = "GlyphHead", ownRing = "";
    private String ownStart = "", ownTick = "", ownArrive = "", ownCancel = "";

    // what is used now
    Ring ring;
    /** Raw texts with {@code %time%}; empty = not shown. */
    String title = "", subtitle = "", actionBar = "";
    String soundStart = "", soundTick = "", soundArrive = "", soundCancel = "";

    private long cmiStamp = -1;
    private Map<String, String> soundKeys;

    WaitLook(FluxPW plugin) {
        this.plugin = plugin;
    }

    /** Reads {@code teleport} of the config; CMI's files are read on the next {@link #refresh()}. */
    void settings(ConfigurationSection teleport) {
        ConfigurationSection t = teleport == null ? new YamlConfiguration() : teleport;
        fromCmi = t.getBoolean("look-from-cmi", true);
        cmiPreset = t.getString("cmi-particle", "GlyphHead");
        animation = t.getBoolean("particles-enabled", true);
        ownRing = t.getString("particles", "circle;effect:reddust;c:179,118,229;part:12;radius:0.55;offset:0,1,0;pitchc:5;yawc:8");
        ownStart = t.getString("sounds.start", "");
        ownTick = t.getString("sounds.tick", "");
        ownArrive = t.getString("sounds.arrive", "");
        ownCancel = t.getString("sounds.cancel", "");
        cmiStamp = -1;
        refresh();
    }

    /** Called before each teleport: reads CMI's files again when one of them changed. */
    void refresh() {
        File cmi = cmiFolder();
        if (cmi == null) {
            if (cmiStamp != 0) own();
            cmiStamp = 0;
            return;
        }
        File config = new File(cmi, "config.yml"), particles = new File(cmi, "Settings/ParticleEffects.yml");
        File translations = new File(cmi, "Translations");
        long stamp = config.lastModified() + particles.lastModified() * 31 + translations.lastModified() * 17;
        File[] locales = translations.listFiles((d, n) -> n.startsWith("Locale_"));
        if (locales != null) for (File f : locales) stamp += f.lastModified();
        if (stamp == cmiStamp) return;
        cmiStamp = stamp;
        own();
        try {
            YamlConfiguration c = YamlConfiguration.loadConfiguration(config);
            YamlConfiguration p = YamlConfiguration.loadConfiguration(particles);
            File locale = new File(translations, "Locale_" + c.getString("Language", "EN") + ".yml");
            if (!locale.isFile()) locale = new File(translations, "Locale_EN.yml");
            YamlConfiguration l = YamlConfiguration.loadConfiguration(locale);
            if (animation) {
                String spec = p.getString("Particles." + cmiPreset, "");
                if (!spec.isBlank()) ring = Ring.parse(spec, plugin.getLogger());
            }
            String dontMove = l.getString("info.WarmUp.DontMove", "");
            if (!dontMove.isBlank()) texts(dontMove.replace("[time]", "%time%"));
            if (c.getBoolean("Sounds.Enabled", true)) {
                soundStart = cmiSound(c.getString("Sounds.CommandWarmup"), soundStart);
                soundTick = cmiSound(c.getString("Sounds.CommandWarmupRunning"), soundTick);
                soundArrive = cmiSound(c.getString("Sounds.TeleportWarp"), soundArrive);
                soundCancel = cmiSound(c.getString("Sounds.TeleportFail"), soundCancel);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not read CMI's teleport look, using FluxPW's: " + e.getMessage());
            own();
        }
    }

    private File cmiFolder() {
        if (!fromCmi) return null;
        Plugin cmi = Bukkit.getPluginManager().getPlugin("CMI");
        return cmi == null || !cmi.isEnabled() ? null : cmi.getDataFolder();
    }

    private void own() {
        ring = animation ? Ring.parse(ownRing, plugin.getLogger()) : null;
        title = plugin.messages().raw("teleport-title");
        subtitle = plugin.messages().raw("teleport-subtitle");
        actionBar = "";
        soundStart = ownStart;
        soundTick = ownTick;
        soundArrive = ownArrive;
        soundCancel = ownCancel;
    }

    /** A CMI message: {@code !title!...!subtitle!...}, {@code !actionbar!...}, or plain text (shown in the action bar). */
    private void texts(String message) {
        title = subtitle = actionBar = "";
        int t = message.indexOf("!title!"), s = message.indexOf("!subtitle!");
        if (t >= 0 || s >= 0) {
            if (t >= 0) title = message.substring(t + 7, s > t ? s : message.length());
            if (s >= 0) subtitle = message.substring(s + 10, t > s ? t : message.length());
        } else {
            actionBar = message.startsWith("!actionbar!") ? message.substring(11) : message;
        }
    }

    /** {@code entity_experience_orb_pickup:1:1} (CMI) to {@code minecraft:entity.experience_orb.pickup 1 1}. */
    private String cmiSound(String spec, String fallback) {
        if (spec == null || spec.isBlank()) return fallback;
        String[] parts = spec.split(":");
        if (soundKeys == null) {
            soundKeys = new HashMap<>();
            for (Sound sound : Registry.SOUNDS) {
                NamespacedKey key = Registry.SOUNDS.getKey(sound);
                if (key != null) soundKeys.put(key.getKey().replace('.', '_'), key.toString());
            }
        }
        String key = soundKeys.get(parts[0].toLowerCase(Locale.ROOT).replace('.', '_'));
        if (key == null) return fallback;
        return key + " " + (parts.length > 1 ? parts[1] : "1") + " " + (parts.length > 2 ? parts[2] : "1");
    }
}
