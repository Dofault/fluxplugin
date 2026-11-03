package fr.influencraft.fluxlands;

import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.nation.Nation;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Effects of the countries, kept and applied by FluxLands (2026-11-01) instead of Lands: Lands has no API for them, only
 * its own menu. Each country level unlocks effects ({@code country-effects.levels} of the config, added to those of
 * the levels below) and a number of effects active at once; the head of the country picks them in the menu
 * {@code pays_effets}. Every few seconds, the members of the country standing in one of its towns get them (ambient,
 * without particles, a bit longer than the period so they never blink).
 */
final class CountryEffects {

    /** Effects a country level gives access to, and how many may be active at once. */
    record Unlocks(List<PotionEffectType> pool, int active) {
    }

    private final FluxLands plugin;
    private BukkitTask task;
    private int durationTicks = 300;

    CountryEffects(FluxLands plugin) {
        this.plugin = plugin;
        start();
    }

    /** (Re)starts the application task with the period of the config (start and /fluxlands reload). */
    void start() {
        if (task != null) task.cancel();
        int seconds = Math.max(1, plugin.getConfig().getInt("country-effects.every-seconds", 3));
        // night vision blinks under 10 s left: keep every effect above that between two passes
        durationTicks = Math.max(240, seconds * 20 + 220);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::apply, 40L, seconds * 20L);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    // ------------------------------------------------------------------ rules

    /** Effects unlocked up to {@code level} (added level after level) and the active count of the highest one set. */
    Unlocks unlocks(int level) {
        ConfigurationSection levels = plugin.getConfig().getConfigurationSection("country-effects.levels");
        Set<PotionEffectType> pool = new LinkedHashSet<>();
        int active = 0;
        if (levels != null) {
            for (int l = 1; l <= level; l++) {
                ConfigurationSection s = levels.getConfigurationSection(String.valueOf(l));
                if (s == null) continue;
                for (String key : s.getStringList("effects")) {
                    PotionEffectType t = type(key);
                    if (t != null) pool.add(t);
                    else plugin.getLogger().warning("country-effects: unknown effect « " + key + " »");
                }
                if (s.contains("active")) active = Math.max(0, s.getInt("active"));
            }
        }
        return new Unlocks(List.copyOf(pool), active);
    }

    Unlocks unlocks(Nation n) {
        return unlocks(Pays.niveau(n));
    }

    /** Effects the country has switched on that its level still allows (oldest first, cut at the active count). */
    List<PotionEffectType> active(Nation n) {
        Unlocks u = unlocks(n);
        List<PotionEffectType> out = new ArrayList<>();
        for (String key : plugin.registre().effetsPays(Pays.id(n))) {
            PotionEffectType t = type(key);
            if (t != null && u.pool().contains(t) && out.size() < u.active()) out.add(t);
        }
        return out;
    }

    /**
     * Switches an effect on or off for a country. Switching one on when the active count is reached replaces the
     * oldest. Returns null when done, else the reason (not unlocked).
     */
    String toggle(Nation n, PotionEffectType type) {
        Unlocks u = unlocks(n);
        if (!u.pool().contains(type)) return "Cet effet n'est pas encore débloqué par le niveau du pays.";
        List<PotionEffectType> current = new ArrayList<>(active(n));
        if (!current.remove(type)) {
            if (u.active() <= 0) return "Le niveau du pays ne permet encore aucun effet actif.";
            while (current.size() >= u.active()) current.remove(0);
            current.add(type);
        }
        plugin.registre().definirEffetsPays(Pays.id(n), current.stream().map(t -> t.getKey().getKey()).toList());
        return null;
    }

    // ------------------------------------------------------------------ application

    private void apply() {
        if (plugin.registre().tousEffetsPays().isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Land here = plugin.villes().a(p.getLocation());
            Nation n = here == null ? null : here.getNation();
            if (n == null || !member(p, n)) continue;
            for (PotionEffectType t : active(n)) {
                PotionEffect own = p.getPotionEffect(t);
                // never shorten or weaken an effect the player got elsewhere (potion, beacon)
                if (own != null && !own.isAmbient() && (own.getAmplifier() > 0 || own.getDuration() > durationTicks)) continue;
                p.addPotionEffect(new PotionEffect(t, durationTicks, 0, true, false, true));
            }
        }
    }

    /** The player lives in one of the country's towns. */
    private boolean member(Player p, Nation n) {
        for (Land l : plugin.villes().duJoueur(p)) if (n.isMember(l)) return true;
        return false;
    }

    // ------------------------------------------------------------------ names

    /** Effect from its Minecraft key (speed, jump_boost) or a former Bukkit name (JUMP, FAST_DIGGING...). */
    static PotionEffectType type(String key) {
        if (key == null || key.isBlank()) return null;
        String k = switch (key.trim().toUpperCase(Locale.ROOT)) {
            case "JUMP" -> "jump_boost";
            case "FAST_DIGGING" -> "haste";
            case "SLOW_DIGGING" -> "mining_fatigue";
            case "INCREASE_DAMAGE" -> "strength";
            case "DAMAGE_RESISTANCE" -> "resistance";
            case "SLOW" -> "slowness";
            case "CONFUSION" -> "nausea";
            case "HEAL" -> "instant_health";
            case "HARM" -> "instant_damage";
            default -> key.trim().toLowerCase(Locale.ROOT);
        };
        return Registry.EFFECT.get(NamespacedKey.minecraft(k));
    }

    /** Name translated by each client, in the player's language. */
    static String name(PotionEffectType t) {
        return "<lang:" + t.translationKey() + ">";
    }
}
