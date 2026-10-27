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
 * <p>Cues: {@code open}, {@code close}, {@code page}, {@code click}, {@code confirm} (first click of a double
 * confirmation), {@code success}, {@code error}, {@code complete}, {@code reward}, {@code toggle-on},
 * {@code toggle-off}, {@code remove}.</p>
 */
public final class Sounds {

    private static final java.util.Map<String, String> CUES = new java.util.concurrent.ConcurrentHashMap<>(java.util.Map.ofEntries(
            java.util.Map.entry("open", "minecraft:block.chest.open 0.6 1.2"),
            java.util.Map.entry("close", "minecraft:block.chest.close 0.6 1.2"),
            java.util.Map.entry("page", "minecraft:item.book.page_turn"),
            java.util.Map.entry("click", "minecraft:ui.button.click 0.6 1.4"),
            java.util.Map.entry("confirm", "minecraft:entity.experience_orb.pickup 0.6 0.7"),
            java.util.Map.entry("success", "minecraft:entity.experience_orb.pickup 0.8 1.2"),
            java.util.Map.entry("error", "minecraft:entity.villager.no"),
            java.util.Map.entry("complete", "minecraft:entity.player.levelup 1 1.4"),
            java.util.Map.entry("reward", "minecraft:ui.toast.challenge_complete 0.8 1"),
            java.util.Map.entry("toggle-on", "minecraft:block.amethyst_block.chime 1 1.4"),
            java.util.Map.entry("toggle-off", "minecraft:block.amethyst_block.hit 1 0.8"),
            java.util.Map.entry("remove", "minecraft:entity.item.break 0.6 1")));

    private Sounds() {
    }

    /** Called by Flux from its config (section {@code sounds}). */
    public static void configure(org.bukkit.configuration.ConfigurationSection section) {
        if (section == null) return;
        for (String key : section.getKeys(false)) CUES.put(key.toLowerCase(Locale.ROOT), section.getString(key, "none"));
    }

    /** Plays a named cue of the suite. */
    public static void cue(Player player, String name) {
        String spec = CUES.get(name.toLowerCase(Locale.ROOT));
        if (spec != null) play(player, spec);
    }

    public static void play(Player player, String spec) {
        if (player == null || spec == null) return;
        String trimmed = spec.trim();
        if (trimmed.startsWith("@")) {
            cue(player, trimmed.substring(1));
            return;
        }
        String[] parts = trimmed.split("\\s+");
        if (parts[0].isEmpty() || parts[0].equalsIgnoreCase("none")) return;
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
