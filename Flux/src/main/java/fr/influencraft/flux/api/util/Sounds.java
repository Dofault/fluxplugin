package fr.influencraft.flux.api.util;

import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Sounds of the suite. Interface feedback uses named cues configured once in Flux ({@code sounds} section of
 * {@code plugins/Flux/config.yml}), so every plugin sounds the same and the server changes them in one place:
 * {@code Sounds.cue(player, "error")}, or {@code "@error"} wherever a sound is written in a config or a menu.
 * Other sounds are written {@code "<key> [volume] [pitch]"}, e.g. {@code "minecraft:ui.button.click 1 1.2"}
 * (any resource pack sound key). Empty or {@code "none"}: no sound.
 *
 * <p>Cues, one per meaning: {@code page} (a menu opens or turns a page), {@code click} (a button),
 * {@code confirm} (first click of a double confirmation, nothing done yet), {@code success} (an action worked:
 * bought, sold, deposited), {@code complete} (something earned: a level, a finished offering, a claimed reward),
 * {@code error}, {@code remove}, {@code toggle-on}, {@code toggle-off}, {@code alert} (something needs the player's attention: a reward
 * waiting, a staff alert; at most once every 3 seconds per player, a burst of alerts rings once). Former cues
 * still answer: {@code open} plays {@code page}, {@code reward} plays {@code complete}, {@code close} is silent.</p>
 */
public final class Sounds {

    private static final java.util.Map<String, String> CUES = new java.util.concurrent.ConcurrentHashMap<>(java.util.Map.ofEntries(
            java.util.Map.entry("page", "minecraft:item.book.page_turn"),
            java.util.Map.entry("click", "minecraft:ui.button.click 0.6 1.4"),
            java.util.Map.entry("confirm", "minecraft:entity.experience_orb.pickup 0.6 0.7"),
            java.util.Map.entry("success", "minecraft:entity.experience_orb.pickup 0.8 1.2"),
            java.util.Map.entry("error", "minecraft:entity.villager.no"),
            java.util.Map.entry("complete", "minecraft:entity.player.levelup 1 1.4"),
            java.util.Map.entry("toggle-on", "minecraft:block.amethyst_block.chime 1 1.4"),
            java.util.Map.entry("toggle-off", "minecraft:block.amethyst_block.hit 1 0.8"),
            java.util.Map.entry("remove", "minecraft:entity.item.break 0.6 1"),
            java.util.Map.entry("alert", "minecraft:block.note_block.pling 0.7 1.6")));

    /** Cues merged into another one (menus and configs written before keep working). */
    private static final java.util.Map<String, String> MERGED = java.util.Map.of("open", "page", "reward", "complete", "close", "");

    /** Minimum time between two plays of a cue for one player, in ms. */
    private static final java.util.Map<String, Long> COOLDOWNS = java.util.Map.of("alert", 3000L);
    private static final java.util.Map<String, Long> LAST_PLAYED = new java.util.concurrent.ConcurrentHashMap<>();

    private Sounds() {
    }

    /** Called by Flux from its config (section {@code sounds}). */
    public static void configure(org.bukkit.configuration.ConfigurationSection section) {
        if (section == null) return;
        for (String key : section.getKeys(false)) if (!MERGED.containsKey(key.toLowerCase(Locale.ROOT))) CUES.put(key.toLowerCase(Locale.ROOT), section.getString(key, "none"));
    }

    /**
     * Sounds of one click: the action lines of a click run as a batch ({@link #begin}/{@link #end}) and only one sound
     * plays, so a [list] page sound, a menu opening sound and a [sound] line never stack. A sound written in the
     * menu ({@link #explicit}) wins over the ones played by code, else the first one played is kept.
     */
    private static final class Batch {
        String raw;
        boolean explicit;
    }

    private static final java.util.Map<java.util.UUID, Batch> BATCHES = new java.util.HashMap<>();

    /** Starts the sound batch of a click (main thread). */
    public static void begin(Player player) {
        if (player != null) BATCHES.put(player.getUniqueId(), new Batch());
    }

    /** Ends the batch of a click and plays its sound. */
    public static void end(Player player) {
        Batch b = player == null ? null : BATCHES.remove(player.getUniqueId());
        if (b != null && b.raw != null) emit(player, b.raw);
    }

    /** Plays a named cue of the suite. */
    public static void cue(Player player, String name) {
        play(player, "@" + name);
    }

    /** Plays a sound ({@code "<key> [volume] [pitch]"}, or {@code "@cue"}). */
    public static void play(Player player, String spec) {
        record(player, resolve(player, spec), false);
    }

    /** Sound written by a menu author ({@code [sound]} action): during a click, it replaces the automatic ones. */
    public static void explicit(Player player, String spec) {
        record(player, resolve(player, spec), true);
    }

    private static void record(Player player, String raw, boolean explicit) {
        if (player == null || raw == null) return;
        Batch b = org.bukkit.Bukkit.isPrimaryThread() ? BATCHES.get(player.getUniqueId()) : null;
        if (b == null) {
            emit(player, raw);
        } else if (explicit ? !b.explicit : b.raw == null) {
            b.raw = raw;
            b.explicit = explicit;
        }
    }

    /** The sound to play ({@code key [volume] [pitch]}), null for none or a cue on cooldown. */
    private static String resolve(Player player, String spec) {
        if (spec == null) return null;
        String trimmed = spec.trim();
        if (!trimmed.startsWith("@")) {
            String key = trimmed.split("\\s+")[0];
            return key.isEmpty() || key.equalsIgnoreCase("none") ? null : trimmed;
        }
        String key = trimmed.substring(1).toLowerCase(Locale.ROOT);
        key = MERGED.getOrDefault(key, key);
        String cue = CUES.get(key);
        Long cooldown = COOLDOWNS.get(key);
        if (cue != null && cooldown != null && player != null) {
            long now = System.currentTimeMillis();
            String id = player.getUniqueId() + ":" + key;
            Long last = LAST_PLAYED.get(id);
            if (last != null && now - last < cooldown) return null;
            LAST_PLAYED.put(id, now);
        }
        return cue == null || cue.trim().startsWith("@") ? null : resolve(player, cue);
    }

    private static void emit(Player player, String raw) {
        String[] parts = raw.trim().split("\\s+");
        float volume = parts.length > 1 ? parse(parts[1], 1f) : 1f;
        float pitch = parts.length > 2 ? parse(parts[2], 1f) : 1f;
        player.playSound(player.getLocation(), parts[0].toLowerCase(Locale.ROOT), SoundCategory.MASTER, volume, pitch);
    }

    private static float parse(String s, float fallback) {
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
