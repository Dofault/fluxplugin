package fr.influencraft.fluxcosmetics;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Particles around the wearer of a cosmetic (its {@code trail}, one of the {@code trails} of the config): the astral
 * cape. One dust particle now and then behind him, between his knees and his shoulders, going from one colour to
 * another while it fades; the client lets it drift and die within two seconds.
 *
 * <p>Nothing is kept per player: a trail stops with the tick after its cosmetic is taken off, its wearer leaves,
 * turns invisible or enters the wardrobe (he is its camera then, his mannequin shows the cosmetic), and no entity is
 * ever spawned. Only those who see the wearer get the particles; none in the rpg universe, where vanilla cosmetics
 * are not drawn.</p>
 */
final class Trails {

    /** Slots a cosmetic with a trail may be worn in. */
    private static final EquipmentSlot[] SLOTS = Outfits.ARMOUR;
    private static final double RANGE = 32;

    /**
     * {@code colours}: pairs (first colour, colour it turns into), one pair picked per particle; {@code period}: ticks
     * between two particles; heights above his feet and distances in blocks.
     */
    private record Trail(List<Color[]> colours, int period, float size, double low, double high, double radius, double behind) {
    }

    private final FluxCosmetics plugin;
    private final Map<String, Trail> trails = new HashMap<>();
    private BukkitTask task;
    private int tick;

    Trails(FluxCosmetics plugin) {
        this.plugin = plugin;
    }

    void load(ConfigurationSection config) {
        trails.clear();
        ConfigurationSection section = config.getConfigurationSection("trails");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            ConfigurationSection c = section.getConfigurationSection(id);
            if (c == null) continue;
            List<Color[]> colours = new ArrayList<>();
            for (String pair : c.getStringList("colours")) {
                String[] parts = pair.trim().split("\\s+");
                try {
                    Color from = colour(parts[0]);
                    colours.add(new Color[]{from, parts.length > 1 ? colour(parts[1]) : from});
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Trail " + id + ": " + pair + " is not a pair of #RRGGBB colours");
                }
            }
            if (colours.isEmpty()) {
                plugin.getLogger().warning("Trail " + id + ": no colours, left out");
                continue;
            }
            double low = c.getDouble("low", 0.45);
            trails.put(id, new Trail(List.copyOf(colours), Math.max(1, c.getInt("period", 5)),
                    (float) Math.clamp(c.getDouble("size", 0.6), 0.1, 4), low, Math.max(low, c.getDouble("high", 1.35)),
                    Math.max(0, c.getDouble("radius", 0.5)), c.getDouble("behind", 0.35)));
        }
    }

    private static Color colour(String hex) {
        return Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16));
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::run, 20L, 1L);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    private void run() {
        tick++;
        if (trails.isEmpty()) return;
        Outfits outfits = plugin.outfits();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!outfits.ready(p) || outfits.rpg().in(p)) continue;
            for (EquipmentSlot slot : SLOTS) {
                String key = outfits.worn(p, slot);
                Outfits.Cosmetic c = key == null ? null : outfits.get(key);
                Trail trail = c == null || c.trail() == null ? null : trails.get(c.trail());
                // wearers are spread over the ticks of a period by their entity id
                if (trail != null && (tick + p.getEntityId()) % trail.period() == 0 && shown(p)) emit(p, trail);
            }
        }
    }

    /** Whether his cosmetics are seen where he stands. */
    private boolean shown(Player p) {
        return !p.isDead() && p.getGameMode() != GameMode.SPECTATOR && !p.isInvisible()
                && !p.hasPotionEffect(PotionEffectType.INVISIBILITY) && !plugin.wardrobe().active(p);
    }

    private void emit(Player p, Trail trail) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double yaw = Math.toRadians(p.getBodyYaw());
        // behind him: the opposite of where his body faces; side: along his shoulders
        double backX = Math.sin(yaw), backZ = -Math.cos(yaw);
        double side = random.nextDouble(-trail.radius(), trail.radius() + 1e-6);
        double depth = trail.behind() + random.nextDouble(0.15);
        Location at = p.getLocation().add(backX * depth + Math.cos(yaw) * side,
                random.nextDouble(trail.low(), trail.high() + 1e-6), backZ * depth + Math.sin(yaw) * side);
        Color[] pair = trail.colours().get(random.nextInt(trail.colours().size()));
        Particle.DustTransition dust = new Particle.DustTransition(pair[0], pair[1], trail.size());
        for (Player viewer : p.getWorld().getPlayers()) {
            if (viewer != p && (!viewer.canSee(p) || viewer.getLocation().distanceSquared(at) > RANGE * RANGE)) continue;
            viewer.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0, 0, 0, 0, dust);
        }
    }
}
